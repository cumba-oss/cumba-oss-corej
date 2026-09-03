package net.cumba.corej.core.metadata.pickle;

import static net.cumba.corej.core.metadata.pickle.PickleCacheKeysTest.writePickle;
import static net.cumba.datatable.testkit.TestMetadataFixtures.lib;
import static net.cumba.datatable.testkit.TestMetadataFixtures.table;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.cumba.corej.core.CoreLibraryAccess;
import net.cumba.corej.core.CoreLibraryAccessImpl;
import net.cumba.corej.core.metadata.CdiscLibraryProviderBuilder;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.web.api.cache.ApiCache;
import net.cumba.web.api.cache.CacheEntry;
import net.cumba.web.api.cache.GzipFileApiCache;
import net.cumba.web.api.http.HttpRequest;
import net.cumba.web.api.http.HttpTransport;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 3 of {@code plans/PLAN-api-cache-key-query-strings.md} — the guard that stops the seeder
 * and the engine drifting apart on cache keys.
 *
 * <p>
 * {@link PickleCacheSeeder} writes into the same {@code GzipFileApiCache} that
 * {@link CdiscLibraryClient} reads, but through the <em>path-based</em> API, so nothing in the type
 * system forces the key it writes to be the key the client looks up. It already diverged once: the
 * seeder wrote the bare path the Python populator had fetched, while the client requests
 * {@code ?expand=true} — leaving a freshly seeded cache inert for exactly the entries it exists to
 * provide.
 * </p>
 *
 * <p>
 * Every expected key here is obtained by <b>driving the real client</b> and capturing the request
 * it issues, never by restating a path. A change to {@code CdiscLibraryClient}'s path construction,
 * to its {@code expand} handling, or to {@code ApiCache.toCacheKey} therefore moves both sides of
 * each assertion at once — which is the only way this test cannot rot.
 * </p>
 *
 * <p>
 * The tests come in two directions. The first group starts from a fixed list of endpoints (E1–E7)
 * and checks the seeder's output against it; that list is hand-maintained, so a <em>new</em> engine
 * call site is simply missing from it and its absence from the seeded cache goes unnoticed.
 * {@link #everyKeyTheEngineRequestsIsOneTheSeederWrote} inverts that: it drives the engine's real
 * library-backed startup path, records whatever requests fall out, and fails on any of them the
 * seeder did not write. See that method for how "known-unseedable" stays derived rather than
 * listed.
 * </p>
 *
 * <p>
 * <b>{@code expand} is a property of the call site, not of the endpoint.</b> Nearly every endpoint
 * on {@code CdiscLibraryClient} ships as a pair — {@code getProducts()} delegating to
 * {@code getProducts(false)} alongside {@code getProducts(boolean)} — so the same URL is
 * legitimately requested both ways and no per-endpoint policy table can be correct. The seeder
 * therefore carries no expand policy of its own; it carries the recorded request set. The two
 * families with no expand overload at all — {@code getCtPackages()} and the whole
 * {@code /mdr/integrated/**} group — are fetched bare for that structural reason.
 * </p>
 */
class SeederEngineKeyAgreementTest
{

    private static final String BASE_URL = "https://api.library.cdisc.org/api/";

    private static final String SDTM_CT_ID = "sdtmct-2024-09-27";

    private static final String ADAM_CT_ID = "adamct-2024-03-29";

    /** A transport that fails the test if the client ever reaches the network. */
    private static final HttpTransport NO_NETWORK = _ ->
    {
        throw new AssertionError("the client must be served from the recording cache");
    };

    // ------------------------------------------------------------------
    // Capturing what the engine asks for
    // ------------------------------------------------------------------

    /**
     * An {@link ApiCache} that records the key the client derives and answers every lookup, so the
     * call completes without a network round trip. The body is an empty JSON object;
     * {@code JsonNodeResource} returns empty accessors for missing fields rather than throwing, so
     * every typed endpoint parses it.
     */
    private static final class KeyRecordingCache implements ApiCache
    {

        private final List<String> keys = new ArrayList<>();

        @Override
        public Optional<CacheEntry> get(HttpRequest aRequest)
        {
            keys.add(toCacheKey(aRequest));
            return Optional.of(new CacheEntry(200, Map.of(), "{}"));
        }


        @Override
        public Optional<String> read(String aPath)
        {
            return Optional.empty();
        }


        @Override
        public void write(String aPath, String aContent)
        {
            throw new AssertionError("the recording cache is read-only");
        }


        @Override
        public boolean invalidate(String aPath)
        {
            return false;
        }


        String onlyKey()
        {
            assertEquals(1, keys.size(), "expected exactly one request, got " + keys);
            return keys.get(0);
        }
    }


    /** A single call against a client wired to the recording cache. */
    @FunctionalInterface
    private interface ClientCall
    {

        void run(CdiscLibraryClient aClient) throws IOException;
    }

    /**
     * Runs one client call and returns the cache key that call would look up.
     *
     * @param aCall
     *            the endpoint call to make.
     * @return the derived cache key.
     * @throws IOException
     *             never in practice — the recording cache always hits.
     */
    private static String engineKey(ClientCall aCall) throws IOException
    {
        KeyRecordingCache recorder = new KeyRecordingCache();
        CdiscLibraryClient client = CdiscLibraryClient.builder().baseUrl(BASE_URL).apiKey("test")
                .transport(NO_NETWORK).cache(recorder).build();
        aCall.run(client);
        return recorder.onlyKey();
    }

    // ------------------------------------------------------------------
    // Fixtures — one pickle entry per seeded endpoint in the E1–E7 table
    // ------------------------------------------------------------------


    private static Map<String, Object> standardsEntry(String aSelfHref)
    {
        Map<String, Object> self = new LinkedHashMap<>();
        self.put("href", aSelfHref);
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("self", self);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("_links", links);
        body.put("name", "fixture");
        return body;
    }


    private static Map<String, Object> ctPackageEntry(String aId)
    {
        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("package", aId);
        pkg.put("codelists", List.of());
        return pkg;
    }


    private static Path pickleDir(Path aRoot) throws IOException
    {
        Path dir = Files.createDirectories(aRoot.resolve("pkl"));

        Map<String, Object> standards = new LinkedHashMap<>();
        // E1 — an implementation guide.
        standards.put("standards/sdtmig/3-4", standardsEntry("/mdr/sdtmig/3-4"));
        // A TIG substandard: fetched bare, because no integrated endpoint takes expand.
        standards.put("standards/tig/1-0/sdtm", standardsEntry("/mdr/integrated/tig/1-0/sdtm"));
        writePickle(dir, "standards_details", standards);

        Map<String, Object> models = new LinkedHashMap<>();
        // E2 — the SDTM model. E3 — the ADaM model.
        models.put("models/sdtm/2-0", standardsEntry("/mdr/sdtm/2-0"));
        models.put("models/adam/2-1", standardsEntry("/mdr/adam/adam-2-1"));
        writePickle(dir, "standards_models", models);

        // E4 (the index, derived from the package files) and E5 (the package itself).
        writePickle(dir, "sdtmct-2024-09-27", ctPackageEntry("sdtmct-2024-09-27"));
        return dir;
    }


    private static SeedReport seed(Path aPkl, Path aCache) throws IOException
    {
        try (LocalPickleSource source = new LocalPickleSource(aPkl))
        {
            return new PickleCacheSeeder()
                    .seed(SeedOptions.builder(source, aCache, BASE_URL).build());
        }
    }

    // ------------------------------------------------------------------
    // The agreement
    // ------------------------------------------------------------------


    /**
     * Every key the seeder writes is a key the engine looks up, and every seeded endpoint in the
     * E1–E7 table is covered. Asserting set equality (rather than containment in one direction) is
     * what makes an accidentally-extra key a failure too.
     */
    @Test
    void everySeededKeyIsAKeyTheEngineLooksUp(@TempDir Path root) throws IOException
    {
        Set<String> expected = new LinkedHashSet<>();
        // E1 /mdr/{standard}/{version} — CdiscLibraryProviderBuilder:249
        expected.add(engineKey(c -> c.getSdtmVersion("sdtmig", "3-4", true)));
        // E2 /mdr/sdtm/{modelVersion} — CdiscLibraryProviderBuilder:374
        expected.add(engineKey(c -> c.getSdtmVersion("sdtm", "2-0", true)));
        // E3 /mdr/adam/{productId} — CdiscLibraryProviderBuilder:300
        expected.add(engineKey(c -> c.getAdamProduct("adam-2-1", true)));
        // E4 /mdr/ct/packages — CoreLibraryAccessImpl:163 (no expand overload exists)
        expected.add(engineKey(CdiscLibraryClient::getCtPackages));
        // E5 /mdr/ct/packages/{id} — CoreLibraryAccessImpl:201
        expected.add(engineKey(c -> c.getCtPackage("sdtmct-2024-09-27", true)));
        // The TIG substandard — getIntegratedSdtm has no expand overload.
        expected.add(engineKey(c -> c.getIntegratedSdtm("tig", "1-0")));

        SeedReport report = seed(pickleDir(root), root.resolve("cache"));

        assertEquals(new TreeSet<>(expected), new TreeSet<>(report.written()),
                "the seeder must write exactly the keys the engine derives");
        assertTrue(report.warnings().isEmpty(), report.warnings().toString());
    }


    /**
     * The regression this phase exists for, stated as an inequality: the producer's request shape
     * and the consumer's are different keys, and the seeder must be on the consumer's side.
     */
    @Test
    void theProducersBareKeyIsNotTheConsumersKey(@TempDir Path root) throws IOException
    {
        String expanded = engineKey(c -> c.getSdtmVersion("sdtmig", "3-4", true));
        String bare = engineKey(c -> c.getSdtmVersion("sdtmig", "3-4", false));

        assertNotEquals(bare, expanded,
                "if these collapse, the query has stopped participating in the key");

        SeedReport report = seed(pickleDir(root), root.resolve("cache"));

        assertTrue(report.written().contains(expanded), report.written().toString());
        assertFalse(report.written().contains(bare),
                "the bare key is what the Python populator fetched, not what CoreJ requests");
    }


    /**
     * A seeded entry must be reachable through the client's own lookup path, not merely written
     * under a matching string. This is the end-to-end form of the assertion: seed, then let a real
     * {@code GzipFileApiCache}-backed client resolve the endpoint.
     */
    @Test
    void aSeededEntryResolvesThroughTheRealCache(@TempDir Path root) throws IOException
    {
        Path cache = root.resolve("cache");
        seed(pickleDir(root), cache);

        // GzipFileApiCache(dir, ".json") is what CdiscLibraryClient.getCache() builds and what the
        // seeder writes through; cacheDir(...) alone would give a plain FileApiCache and read the
        // wrong extension.
        CdiscLibraryClient client = CdiscLibraryClient.builder().baseUrl(BASE_URL).apiKey("test")
                .transport(NO_NETWORK).cache(new GzipFileApiCache(cache.toAbsolutePath(), ".json"))
                .build();

        // NO_NETWORK throws if the lookup misses, so completing the call IS the assertion.
        assertEquals("fixture", client.getSdtmVersion("sdtmig", "3-4", true).name().orElse(null));
        assertEquals("fixture", client.getIntegratedSdtm("tig", "1-0").name().orElse(null));
    }


    /**
     * Endpoints with no pickle source must not be invented. {@code /mdr/rules/...} (E6) and
     * {@code /mdr/products} (E7) are live-seeder territory; a bogus entry under either key would be
     * served to the engine as though it were real.
     */
    @Test
    void endpointsWithoutAPickleSourceAreNotSeeded(@TempDir Path root) throws IOException
    {
        String rules = engineKey(c -> c.getRules("sdtmig", "3-4"));
        String products = engineKey(CdiscLibraryClient::getProducts);

        SeedReport report = seed(pickleDir(root), root.resolve("cache"));

        assertFalse(report.written().contains(rules), report.written().toString());
        assertFalse(report.written().contains(products), report.written().toString());
    }

    // ------------------------------------------------------------------
    // The exhaustive form: drive the ENGINE, not a table of endpoints
    // ------------------------------------------------------------------

    /**
     * Records every key the engine's cache lookups derive, and answers each one from a cache the
     * seeder has already filled.
     *
     * <p>
     * The lookup deliberately goes through {@link #readEntry(String)} — the <em>exact</em> key —
     * and never through the delegate's {@link ApiCache#get(HttpRequest)}, because that would
     * consult the legacy path-only fallback. The fallback resolves a bare-keyed entry for an
     * expanded request, so an end-to-end read succeeds even when the seeder and the engine disagree
     * on the key: routing through it would make this guard pass under the very defect it exists to
     * catch.
     * </p>
     */
    private static final class EngineRequestRecorder implements ApiCache
    {

        private final GzipFileApiCache seeded;

        private final Set<String> requested = new LinkedHashSet<>();

        EngineRequestRecorder(Path aSeededCacheDir)
        {
            seeded = new GzipFileApiCache(aSeededCacheDir.toAbsolutePath(), ".json");
        }


        @Override
        public Optional<CacheEntry> get(HttpRequest aRequest) throws IOException
        {
            String key = toCacheKey(aRequest);
            requested.add(key);
            return seeded.readEntry(key);
        }


        @Override
        public Optional<String> read(String aPath) throws IOException
        {
            return seeded.read(aPath);
        }


        @Override
        public void write(String aPath, String aContent)
        {
            throw new AssertionError("nothing may be written while recording: " + aPath);
        }


        @Override
        public boolean invalidate(String aPath)
        {
            return false;
        }
    }

    /** Fails every send, so a cache miss degrades the engine instead of reaching the network. */
    private static final HttpTransport OFFLINE = _ ->
    {
        throw new IOException("offline — the engine must be served from the seeded cache");
    };

    /**
     * The endpoint part of a cache key: the key with any query string removed. Two keys share an
     * endpoint exactly when they address the same resource with different request shapes, which is
     * the distinction the classification below turns on.
     */
    private static String endpointOf(String aKey)
    {
        int query = aKey.indexOf('?');
        return query < 0 ? aKey : aKey.substring(0, query);
    }


    /**
     * A pickle fixture that carries one entry per endpoint family the engine's startup path
     * touches. Separate from {@link #pickleDir(Path)} so the E1–E7 set-equality test keeps its own
     * exact expectations.
     */
    private static Path enginePickleDir(Path aRoot) throws IOException
    {
        Path dir = Files.createDirectories(aRoot.resolve("engine-pkl"));

        Map<String, Object> igLinks = new LinkedHashMap<>();
        igLinks.put("self", Map.of("href", "/mdr/sdtmig/3-4"));
        // Drives the Fix #61 best-effort SDTM Model fetch in CdiscLibraryProviderBuilder.
        igLinks.put("model", Map.of("href", "/mdr/sdtm/2-0"));
        Map<String, Object> ig = new LinkedHashMap<>();
        ig.put("_links", igLinks);
        ig.put("name", "fixture");
        ig.put("classes", List.of());

        Map<String, Object> standards = new LinkedHashMap<>();
        standards.put("standards/sdtmig/3-4", ig);
        standards.put("standards/adam/adamig-1-3", standardsEntry("/mdr/adam/adamig-1-3"));
        writePickle(dir, "standards_details", standards);

        Map<String, Object> models = new LinkedHashMap<>();
        models.put("models/sdtm/2-0", standardsEntry("/mdr/sdtm/2-0"));
        writePickle(dir, "standards_models", models);

        writePickle(dir, SDTM_CT_ID, ctPackageEntry(SDTM_CT_ID));
        writePickle(dir, ADAM_CT_ID, ctPackageEntry(ADAM_CT_ID));
        return dir;
    }


    /**
     * Runs the engine's library-backed startup path against a recording cache and returns every key
     * it asked for.
     *
     * <p>
     * Nothing here names an endpoint: the requests are whatever {@code CoreLibraryAccess} and
     * {@code CdiscLibraryProviderBuilder} choose to issue. Adding a fetch anywhere inside those
     * paths therefore lands in this set automatically — which is the whole point, and the reason
     * this cannot be satisfied by a hand-maintained endpoint table.
     * </p>
     */
    private static Set<String> recordEngineRequests(Path aSeededCacheDir)
    {
        EngineRequestRecorder recorder = new EngineRequestRecorder(aSeededCacheDir);
        CdiscLibraryClient client = CdiscLibraryClient.builder().baseUrl(BASE_URL).apiKey("test")
                .transport(OFFLINE).cache(recorder).build();
        CoreLibraryAccess access = CoreLibraryAccessImpl.forTesting(client);

        // --- CoreLibraryAccess: every method it declares (pinned by the reflection guard below).
        access.listCtPackageIds();
        try
        {
            access.loadRules("sdtmig", "3-4");
        }
        catch (IOException _)
        {
            // No pickle source backs /mdr/rules/**; the miss is the measurement.
        }

        // --- CdiscLibraryProviderBuilder: both non-degenerate StandardKind branches.
        IMetadataLibrary study = lib("study").table(table("AE").build()).build();
        CdiscLibraryProviderBuilder.from(access).study(study).standard("sdtmig").version("3-4")
                .ctPackageIds(List.of(SDTM_CT_ID)).buildOrDegraded();
        CdiscLibraryProviderBuilder.from(access).study(study).standard("adamig").version("1-3")
                .ctPackageIds(List.of(ADAM_CT_ID)).buildOrDegraded();

        return recorder.requested;
    }


    /**
     * <b>The inverted assertion.</b> Every key the engine actually requests must be a key the
     * seeder wrote — unless the pickle source cannot supply that endpoint at all.
     *
     * <p>
     * The earlier {@code everySeededKeyIsAKeyTheEngineLooksUp} runs the other way: it checks the
     * seeder's output against a hand-maintained E1–E7 list of endpoints, so a <em>new</em> engine
     * call site is simply absent from the list and nothing notices it went unseeded. Here the
     * engine's requests are the input, so an unseeded call site fails by construction.
     * </p>
     *
     * <h4>Why the "known-unseedable" carve-out is not a list</h4>
     *
     * <p>
     * {@code /mdr/products} and {@code /mdr/rules/**} have no pickle representation, so the seeder
     * legitimately writes nothing for them. That exemption is <em>derived</em>, not declared: an
     * endpoint is exempt exactly when the seeder produced <b>no key at all</b> for it from this
     * source. If a source ever gains those endpoints the seeder starts keying them, the exemption
     * evaporates on its own, and the equality below starts applying to them. What can never be
     * excused is the failure this whole plan is about — the seeder keying an endpoint it
     * <em>does</em> cover under a shape the engine never requests.
     * </p>
     *
     * <p>
     * The exemption is guarded from below rather than enumerated from above: the seed report's
     * counts are checked against the source's own {@code standardKeys() / modelKeys() /
     * publishedCtPackages()} enumeration, so a seeder that quietly stopped covering part of its
     * source cannot launder that regression into a "gap" and pass.
     * </p>
     *
     * <p>
     * <b>Residual, stated plainly:</b> a genuinely <em>new</em> endpoint family that the pickle
     * source also cannot back is classified as a gap and passes, exactly as {@code /mdr/products}
     * does. That is deliberate — nothing distinguishes the two, and a seeder cannot be asked to
     * emit a body no source carries. Making it fail would require the hand-maintained list this
     * test exists to remove.
     * </p>
     *
     * <p>
     * <b>Scope:</b> the driven paths are the ones in this module — {@code CoreLibraryAccess} (its
     * whole declared surface, pinned by
     * {@link #theRecordedEngineSurfaceCoversEveryCoreLibraryAccessMethod()}) and
     * {@code CdiscLibraryProviderBuilder}'s two non-degenerate branches. The CLI's
     * {@code CdiscLibraryBackedLibraryProvider} lives downstream in the CLI and is out of reach
     * here; its two call sites are {@code getSdtmVersion(.., true)} (the same shape covered below)
     * and {@code getProducts()} (unseedable, as above).
     * </p>
     */
    @Test
    void everyKeyTheEngineRequestsIsOneTheSeederWrote(@TempDir Path root) throws IOException
    {
        Path pkl = enginePickleDir(root);
        Path cache = root.resolve("engine-cache");
        SeedReport report = seed(pkl, cache);
        assertTrue(report.warnings().isEmpty(), report.warnings().toString());

        Set<String> seeded = new LinkedHashSet<>(report.written());
        Set<String> seededEndpoints = new TreeSet<>();
        seeded.forEach(k -> seededEndpoints.add(endpointOf(k)));

        // The "unseedable" arm below excuses any endpoint the seeder produced no key for, so a
        // seeder that quietly stopped enumerating part of its source would reclassify real
        // coverage as a gap and pass. Anchor the arm on the source's own enumeration — counts
        // only, so no endpoint or key mapping is restated here.
        PickleCache source = PickleCache.open(pkl);
        assertEquals(source.standardKeys().size() + source.modelKeys().size(),
                report.standardsWritten() + report.modelsWritten(),
                "the seeder skipped a standards/models entry its source offers");
        assertEquals(source.publishedCtPackages().size(), report.ctPackagesWritten(),
                "the seeder skipped a CT package its source offers");
        assertTrue(report.ctIndexWritten(), "the CT index went unseeded");

        Set<String> requested = recordEngineRequests(cache);
        assertFalse(requested.isEmpty(), "the engine issued no requests at all");

        Set<String> misKeyed = new TreeSet<>();
        Set<String> unseedable = new TreeSet<>();
        Set<String> covered = new TreeSet<>();
        for (String key : requested)
        {
            if (seeded.contains(key))
            {
                covered.add(key);
            }
            else if (seededEndpoints.contains(endpointOf(key)))
            {
                misKeyed.add(key);
            }
            else
            {
                unseedable.add(key);
            }
        }

        assertEquals(Set.of(), misKeyed,
                "the engine requests these keys, but the seeder keyed the same endpoint "
                        + "differently — a seeded cache is inert for exactly these lookups. "
                        + "Seeder wrote: " + new TreeSet<>(seeded));
        // Equality, not containment, and in the other direction too: the fixture holds exactly the
        // endpoints the driven startup path touches, so anything the seeder wrote must also have
        // been asked for. Without this the test could pass while the engine short-circuits early —
        // a degraded IG fetch, for instance, suppresses the dependent SDTM-Model and CT lookups,
        // and the remaining one-key agreement would still look green.
        assertEquals(new TreeSet<>(seeded), covered,
                "the seeder wrote keys the engine never asked for — either the seeder invented "
                        + "them or the driven startup path stopped short of requesting them");
        // Every remaining request is an endpoint the source cannot back at all. Stated as an
        // assertion (rather than left implicit) so the split stays visible in the failure text.
        unseedable.forEach(key -> assertFalse(seededEndpoints.contains(endpointOf(key)),
                "misclassified as unseedable: " + key));
    }


    /**
     * {@link #recordEngineRequests} drives {@code CoreLibraryAccess} by hand, so a method added to
     * that interface would issue requests nobody records. Pin the surface: a new method must be
     * driven above before this test can go green again.
     */
    @Test
    void theRecordedEngineSurfaceCoversEveryCoreLibraryAccessMethod()
    {
        Set<String> declared = new TreeSet<>();
        for (Method method : CoreLibraryAccess.class.getDeclaredMethods())
        {
            if (!Modifier.isStatic(method.getModifiers()))
            {
                declared.add(method.getName());
            }
        }
        assertEquals(new TreeSet<>(Set.of("loadRules", "listCtPackageIds")), declared,
                "CoreLibraryAccess gained or lost a method — drive it in recordEngineRequests "
                        + "so its requests are recorded, then update this guard");
    }
}
