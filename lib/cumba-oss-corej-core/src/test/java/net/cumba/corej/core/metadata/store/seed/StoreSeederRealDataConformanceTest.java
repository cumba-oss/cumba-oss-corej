package net.cumba.corej.core.metadata.store.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import net.cumba.corej.core.metadata.pickle.LocalPickleSource;
import net.cumba.corej.core.metadata.pickle.PickleCache;
import net.cumba.corej.core.metadata.pickle.PickleCacheSeeder;
import net.cumba.corej.core.metadata.pickle.SeedOptions;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.RealCorpusLocator;
import net.cumba.corej.core.metadata.store.StoreProvenance;
import net.cumba.web.api.cache.GzipFileApiCache;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The plan §5.1 byte-identity conformance over the REAL corpora: the full 463 MB pickle cache (206
 * CT packages, 34 IGs, 14 models) seeded by {@link PickleStoreSeeder}, against
 * {@link WebApiStoreSeeder} reading a web-api cache generated from those same pickles by the
 * production {@code PickleCacheSeeder} — plus the one document that has no pickle source,
 * {@code /mdr/products}, copied from the recorded real cache (measured 2026-09-08: its 34
 * Implementation-Guide entries match the pickle's standards keys one for one).
 *
 * <p>
 * Skipped when the machine does not carry the corpora (the pickle corpus via
 * {@link RealCorpusLocator}, the recorded web cache at {@code /data/cdisc.metadata.library-cache});
 * the synthetic {@link StoreSeederConformanceTest} pins the same property everywhere. This run is
 * heavyweight (it unpickles ~420 MB twice and gzip-writes an intermediate cache) and exists to
 * prove the property, the measured sizes and the audit's dedup numbers on the real data.
 * </p>
 *
 * <p>
 * ⭐ The pickle-seeded store is seeded ONCE per class ({@link #pickleSeed(Path)}) and read by both
 * tests (PLAN-fast-gate-tests F4). Both used to seed it separately, with the identical seeder,
 * source and options, so the second seed was a third unpickle of the whole corpus that could only
 * produce the same bytes; the first test's byte comparison against the web-api store now covers the
 * very file the second test reads.
 * </p>
 */
class StoreSeederRealDataConformanceTest
{

    private static final Path REAL_WEB_CACHE = Path.of("/data/cdisc.metadata.library-cache");

    /** The one document in that cache this test needs; no pickle carries it. */
    private static final String PRODUCTS_KEY = "/api/mdr/products";

    /**
     * The name the recorded cache holds {@link #PRODUCTS_KEY} under.
     *
     * <p>
     * The recording predates Q14 (2026-09-11), which replaced the cache file-name encoding because
     * the old one mapped {@code /a/b} and {@code /a_b} onto one file. The ruling waived backward
     * compatibility for <em>caches</em> — they get refilled — but this directory is a frozen
     * <em>fixture</em>: there is no API key here to re-record it with. So the historical name is
     * named as history, and the copy is staged under whatever
     * {@link GzipFileApiCache#toCacheFileName(String)} calls it today. Nothing here re-implements
     * either encoding.
     * </p>
     */
    private static final String LEGACY_PRODUCTS_ENTRY = "api_mdr_products.json.gz";

    private static final List<StoreProvenance> FIXED_PROVENANCE = List
            .of(new StoreProvenance("conformance", "real-corpus", "2026-09-08T00:00:00Z"));

    /**
     * The fewest CDASH fields with a {@code _links.codelist} the real corpus held when format 4 was
     * cut (2026-09-28: cdashig 1-1-1 … 2-3, tig 1-0/cdash, models/cdash 1-0 … 1-3). A floor, not a
     * pin: the corpus locator is not version-pinned, and a newer corpus with more fields is no
     * defect. The exact figure is the source-derived count, which the stored count must equal field
     * by field ({@code assertCdashFieldCodelistIds}).
     */
    private static final int MIN_LINKED_CDASH_FIELDS = 2027;

    @TempDir
    private Path temp;

    /**
     * Holds the one pickle-seeded store; class-scoped, so it outlives each test's {@link #temp}.
     */
    @TempDir
    private static Path sharedTemp;

    /**
     * The pickle seed, made on first use by {@link #pickleSeed(Path)}; {@code null} until then and
     * again after the class ({@link #forgetSeed()}).
     */
    private static @Nullable PickleSeed seeded;

    /**
     * The pickle-seeded store and the seeder's report on it.
     *
     * @param store
     *            the seeded store zip.
     * @param report
     *            the seeder's report.
     */
    private record PickleSeed(Path store, StoreSeedReport report)
    {
    }

    @Test
    void bothSeedersProduceAByteIdenticalStoreFromTheRealCorpus() throws IOException
    {
        // F5 (final cross-plan review): the pickle corpus resolves through RealCorpusLocator —
        // the SAME resolution the factory's real-corpus tests use — so the two real-corpus test
        // classes can no longer look for the same corpus differently. A named-but-broken corpus
        // fails loudly in the locator; only absent-everywhere skips, with the named message.
        assumeTrue(RealCorpusLocator.locate().isPresent(), RealCorpusLocator.ABSENT_MESSAGE);
        Path realPickles = RealCorpusLocator.locate().orElseThrow();
        Path recordedProducts = recordedProductsEntry();
        assumeTrue(recordedProducts != null,
                "real web cache (for /mdr/products) not present - skipping");

        PickleSeed seed = pickleSeed(realPickles);
        Path fromPickles = seed.store();
        StoreSeedReport pickleReport = seed.report();

        Path webCache = Files.createDirectories(temp.resolve("web-cache"));
        new PickleCacheSeeder().seed(SeedOptions
                .builder(new LocalPickleSource(realPickles), webCache, SeedFixtures.BASE_URL)
                .writeMeta(false).build());
        // Staged under the name the cache reads it under today, whatever that is — the gzip bytes
        // are copied as they are, so nothing is re-compressed.
        Files.copy(recordedProducts,
                webCache.resolve(new GzipFileApiCache(webCache.toAbsolutePath(), ".json")
                        .toCacheFileName(PRODUCTS_KEY)),
                StandardCopyOption.REPLACE_EXISTING);

        Path fromWebApi = temp.resolve("from-web-api.zip");
        StoreSeedReport webReport = new WebApiStoreSeeder(SeedFixtures.offlineAccess(webCache))
                .seed(StoreSeedOptions.of(fromWebApi).withProvenanceOverride(FIXED_PROVENANCE));

        assertEquals(-1, Files.mismatch(fromPickles, fromWebApi),
                "the two seeders diverged on the real corpus; pickle report: "
                        + pickleReport.summary() + "; web report: " + webReport.summary());

        assertEquals(206, pickleReport.ctPackagesFetched());
        assertEquals(48, pickleReport.productsSeeded(), "34 IGs + 14 models");
        assertEquals(206, webReport.ctPackagesFetched());
        assertEquals(48, webReport.productsSeeded());

        try (MetadataStore store = MetadataStore.open(fromPickles))
        {
            assertEquals(206, store.publishedCtPackages().size());
            assertEquals(34, store.productCatalogue().size());
            assertTrue(store.product("standards/tig/1-0/sdtm").isPresent());
            assertTrue(store.product("models/adam/2-1").isPresent());
        }

        long size = Files.size(fromPickles);
        assertTrue(size > 2_000_000 && size < 6_000_000,
                "store size " + size + " bytes is wildly off the ~3.4 MB the audit measured");
        // Surfaced in the test log for the phase report; the assertion above is the gate.
        System.out.printf("real-corpus store: %,d bytes; pickle seed: %s%n", size,
                pickleReport.summary());
    }


    /**
     * NS3 (PLAN-define-ct-evaluation, T1-3 b / T1-9): the fields the format-3 and format-4 bumps
     * admitted really arrive from the REAL pickle corpus — asserted on the zip's own JSON, never
     * through the records, so this compiles against any format and reds by assertion, not by
     * compilation. The codelist {@code name} was the one field the source publishes at codelist
     * level that the store dropped (41 852 / 41 852 codelists carry it); the product scalars and
     * the CDASH domain/scenario/field levels are the T1-9 additions; the CDASH fields'
     * {@code codelistIds} are format 4's (PLAN-store-cdash-codelist-ids C5 — see
     * {@link #assertCdashFieldCodelistIds}).
     */
    @Test
    void theRealSeedCarriesEveryFieldTheFormatBumpAdmitted() throws IOException
    {
        assumeTrue(RealCorpusLocator.locate().isPresent(), RealCorpusLocator.ABSENT_MESSAGE);
        Path realPickles = RealCorpusLocator.locate().orElseThrow();
        Path store = pickleSeed(realPickles).store();

        ObjectMapper mapper = new ObjectMapper();
        try (ZipFile zip = new ZipFile(store.toFile()))
        {
            JsonNode headers = mapper
                    .readTree(zip.getInputStream(zip.getEntry("ct/codelists.json")))
                    .get("codelists");
            int nullName = 0;
            int sexVersions = 0;
            for (JsonNode header : headers)
            {
                if (!header.hasNonNull("name"))
                {
                    nullName++;
                }
                if ("C66731".equals(header.path("conceptId").asText()))
                {
                    sexVersions++;
                    assertEquals("Sex", header.path("name").asText(),
                            "the SEX codelist's display name, as sdtmct-2024-03-29.pkl publishes it");
                }
            }
            assertTrue(sexVersions >= 1, "C66731 must be among the codelist versions");
            assertEquals(0, nullName, "every codelist header carries its name (T1-3 b)");

            JsonNode sdtmig = mapper.readTree(
                    zip.getInputStream(zip.getEntry("products/standards/sdtmig/3-4.json")));
            assertTrue(sdtmig.hasNonNull("description") && sdtmig.hasNonNull("effectiveDate"),
                    "the product scalars the source publishes are stored (T1-9): " + sdtmig);

            JsonNode cdashig = mapper.readTree(
                    zip.getInputStream(zip.getEntry("products/standards/cdashig/2-3.json")));
            int domains = 0;
            int scenarios = 0;
            int fields = 0;
            for (JsonNode clazz : cdashig.path("classes"))
            {
                for (JsonNode domain : clazz.path("domains"))
                {
                    domains++;
                    fields += domain.path("fields").size();
                }
                for (JsonNode scenario : clazz.path("scenarios"))
                {
                    scenarios++;
                    fields += scenario.path("fields").size();
                }
            }
            assertTrue(domains > 0 && scenarios > 0 && fields > 0,
                    "CDASHIG 2.3 domains/scenarios/fields are stored as levels (T1-9, S18): "
                            + domains + "/" + scenarios + "/" + fields);

            assertCdashFieldCodelistIds(mapper, PickleCache.open(realPickles), zip);
        }
    }


    /**
     * C5 (PLAN-store-cdash-codelist-ids): source and store walked product by product over all four
     * CDASH field paths — the product's {@code domains[].fields}, and per class
     * {@code domains[].fields}, {@code scenarios[].fields} and {@code cdashModelFields}. Where the
     * source field has a {@code _links.codelist}, the stored {@code codelistIds} equals its refs'
     * trailing id segments, in order; where it has none, the stored key is ABSENT — never
     * {@code []}. The oracle is the pickle document itself, read through {@link PickleCache}, not
     * the projection under test.
     *
     * <p>
     * Since format 5 the errata register (PLAN-store-library-content-errata) adds and moves CDASH
     * fields — CDASH 1.1's three common timing fields per domain (#283.11), the CDASH model
     * {@code --OBJ} / {@code APID} / {@code SREL} / {@code RSUBJID} moves into their printed
     * domains (#283.24, #283.25) — so the walk matches elements BY NAME and every store-only or
     * source-only element must be one the register accounts for ({@link ErrataAllowances}); any
     * other difference is still a failure.
     * </p>
     */
    private static void assertCdashFieldCodelistIds(ObjectMapper aMapper, PickleCache aSource,
            ZipFile aStore)
        throws IOException
    {
        Set<String> keys = new LinkedHashSet<>(aSource.standardKeys());
        keys.addAll(aSource.modelKeys());
        int[] counts = new int[2]; // [0] linked, [1] unlinked
        ErrataAllowances allowances = ErrataAllowances.fromRegister();
        for (String key : keys)
        {
            JsonNode source = aMapper.valueToTree(
                    aSource.get(key).orElseThrow(() -> new AssertionError("unreadable " + key)));
            ZipEntry entry = aStore.getEntry("products/" + key + ".json");
            assertNotNull(entry, "the store holds no product " + key);
            JsonNode stored = aMapper.readTree(aStore.getInputStream(entry));
            allowances.indexSourceFields(source);
            compareFieldLists(key + ".domains", source.path("domains"), stored.path("domains"),
                    "name", counts, allowances, key, null);
            JsonNode sourceClasses = source.path("classes");
            JsonNode storedClasses = stored.path("classes");
            assertEquals(sourceClasses.size(), storedClasses.size(), key + ".classes");
            for (int c = 0; c < sourceClasses.size(); c++)
            {
                JsonNode from = sourceClasses.get(c);
                JsonNode to = storedClasses.get(c);
                String className = from.path("name").asText();
                String at = key + ".classes[" + className + "]";
                assertEquals(className, to.path("name").asText(), at);
                compareFieldLists(at + ".domains", from.path("domains"), to.path("domains"), "name",
                        counts, allowances, key, className);
                compareFieldLists(at + ".scenarios", from.path("scenarios"), to.path("scenarios"),
                        "scenario", counts, allowances, key, className);
                compareFields(at + ".cdashModelFields", from.path("cdashModelFields"),
                        to.path("cdashModelFields"), counts,
                        allowances.forClassFields(key, className));
            }
        }
        assertTrue(counts[0] >= MIN_LINKED_CDASH_FIELDS,
                "CDASH fields with a _links.codelist in the real corpus: " + counts[0]
                        + ", below the " + MIN_LINKED_CDASH_FIELDS + " measured at format 4");
        assertTrue(counts[1] > 0, "no CDASH field WITHOUT a link was compared - the absent-key"
                + " branch went unexercised");
        allowances.assertEveryAllowanceWasUsed();
        // Surfaced in the test log for the gate evidence; the assertions above are the gate.
        System.out.printf("CDASH fields compared: %,d with codelistIds, %,d without%n", counts[0],
                counts[1]);
    }


    /**
     * Each element matched by {@code aNameKey}; a store-only element must be a domain the register
     * creates. Each matched pair's {@code fields} compared by {@link #compareFields}.
     */
    private static void compareFieldLists(String aWhere, JsonNode aSource, JsonNode aStored,
            String aNameKey, int[] aCounts, ErrataAllowances aAllowances, String aProduct,
            @Nullable String aClass)
    {
        // A scenario name repeats within a class ("Horizontal-Generic" once per domain), so the
        // scenario key is scenario + domainName + ordinal; a domain's name is unique in its class.
        java.util.function.Function<JsonNode, String> keyOf = element -> "scenario".equals(aNameKey)
                ? element.path("scenario").asText() + "|" + element.path("domainName").asText()
                        + "|" + element.path("ordinal").asText()
                : element.path(aNameKey).asText();
        java.util.Map<String, JsonNode> storedByName = new java.util.LinkedHashMap<>();
        for (JsonNode element : aStored)
        {
            assertNull(storedByName.put(keyOf.apply(element), element),
                    aWhere + ": duplicate element key " + keyOf.apply(element));
        }
        for (JsonNode from : aSource)
        {
            String name = keyOf.apply(from);
            JsonNode to = storedByName.remove(name);
            assertNotNull(to, aWhere + "[" + name + "]: in the source, not in the store");
            compareFields(aWhere + "[" + name + "].fields", from.path("fields"), to.path("fields"),
                    aCounts,
                    aAllowances.forContainer(aProduct, aClass, name, "scenario".equals(aNameKey)));
        }
        for (String storeOnly : storedByName.keySet())
        {
            assertTrue(aAllowances.createsDomain(aProduct, storeOnly), aWhere + "[" + storeOnly
                    + "]: in the store, not in the source, and no errata" + " entry creates it");
            compareFields(aWhere + "[" + storeOnly + "].fields", aMapperArray(),
                    storedByName.get(storeOnly).path("fields"), aCounts,
                    aAllowances.forContainer(aProduct, aClass, storeOnly, false));
        }
    }


    private static JsonNode aMapperArray()
    {
        return new ObjectMapper().createArrayNode();
    }


    /**
     * Fields matched by name: the matched pairs' codelist links must agree and keep the source's
     * relative order; a source-only field must be one the register moves away, a store-only field
     * one the register adds or moves in.
     */
    private static void compareFields(String aWhere, JsonNode aSource, JsonNode aStored,
            int[] aCounts, ErrataAllowances.Container aAllowed)
    {
        java.util.Map<String, Integer> storedIndex = new java.util.LinkedHashMap<>();
        for (int i = 0; i < aStored.size(); i++)
        {
            storedIndex.put(aStored.get(i).path("name").asText(), i);
        }
        int lastMatched = -1;
        Set<String> seen = new LinkedHashSet<>();
        for (int i = 0; i < aSource.size(); i++)
        {
            JsonNode from = aSource.get(i);
            String name = from.path("name").asText();
            String at = aWhere + "[" + i + "] " + name;
            Integer j = storedIndex.get(name);
            if (j == null)
            {
                assertTrue(aAllowed.removed(name),
                        at + ": in the source, not in the store, and no errata entry moves it");
                continue;
            }
            seen.add(name);
            assertTrue(j > lastMatched, at + ": the store's field order differs from the source's");
            lastMatched = j;
            assertCodelistIds(at, from, aStored.get(j), aCounts);
        }
        for (String name : storedIndex.keySet())
        {
            if (seen.contains(name))
            {
                continue;
            }
            JsonNode to = aStored.get(storedIndex.get(name));
            if (aAllowed.movedIn(name))
            {
                // A field the register moved here keeps its source links: compared against the
                // source node from the container it left, so the move cannot drop a codelist.
                JsonNode from = aAllowed.sourceFieldOf(name);
                assertNotNull(from, aWhere + " " + name + ": moved in, but no source field");
                assertCodelistIds(aWhere + " " + name + " (moved)", from, to, aCounts);
                continue;
            }
            assertTrue(aAllowed.added(name),
                    aWhere + " " + name + ": in the store, not in the source, and no errata"
                            + " entry adds or moves it there");
            assertFalse(to.has("codelistIds"),
                    aWhere + " " + name + ": an added field carries no codelistIds");
        }
    }


    private static void assertCodelistIds(String aAt, JsonNode aFrom, JsonNode aTo, int[] aCounts)
    {
        JsonNode link = aFrom.path("_links").path("codelist");
        if (link.isMissingNode() || link.isNull())
        {
            aCounts[1]++;
            assertFalse(aTo.has("codelistIds"), aAt + ": no link, so no codelistIds key");
            return;
        }
        aCounts[0]++;
        List<String> expected = new ArrayList<>();
        for (JsonNode ref : link.isArray() ? link : List.of(link))
        {
            String href = ref.path("href").asText();
            expected.add(href.substring(href.lastIndexOf('/') + 1));
        }
        List<String> actual = new ArrayList<>();
        aTo.path("codelistIds").forEach(id -> actual.add(id.asText()));
        assertEquals(expected, actual, aAt);
    }

    /**
     * The CDASH structure changes the errata register makes, read from the register itself so this
     * test cannot drift from it: {@code add-field} (store-only fields of a CDASH IG domain) and
     * {@code move-field} (a field leaving a model class for a model domain, the domain created when
     * the entry says so). Every allowance must be USED by the walk — an allowance no difference
     * consumed means the register and the store disagree.
     */
    private static final class ErrataAllowances
    {

        /** Per container: the field names the register adds to it and removes from it. */
        record Container(Set<String> addedNames, Set<String> movedNames, Set<String> removedNames,
                Set<String> used, ErrataAllowances owner)
        {

            boolean added(String aName)
            {
                boolean ok = addedNames.contains(aName);
                if (ok)
                {
                    used.add("+" + aName);
                }
                return ok;
            }


            boolean movedIn(String aName)
            {
                boolean ok = movedNames.contains(aName);
                if (ok)
                {
                    used.add(">" + aName);
                }
                return ok;
            }


            @Nullable
            JsonNode sourceFieldOf(String aName)
            {
                return owner.sourceFields.get(aName);
            }


            boolean removed(String aName)
            {
                boolean ok = removedNames.contains(aName);
                if (ok)
                {
                    used.add("-" + aName);
                }
                return ok;
            }
        }

        private final java.util.Map<String, Container> containers = new java.util.HashMap<>();

        private final Set<String> createdDomains = new LinkedHashSet<>();

        private final Set<String> usedCreatedDomains = new LinkedHashSet<>();

        /** The current product's source fields by name, over all four CDASH field paths. */
        private final java.util.Map<String, JsonNode> sourceFields = new java.util.HashMap<>();

        void indexSourceFields(JsonNode aSource)
        {
            sourceFields.clear();
            for (JsonNode domain : aSource.path("domains"))
            {
                domain.path("fields").forEach(f -> sourceFields.put(f.path("name").asText(), f));
            }
            for (JsonNode clazz : aSource.path("classes"))
            {
                for (JsonNode domain : clazz.path("domains"))
                {
                    domain.path("fields")
                            .forEach(f -> sourceFields.put(f.path("name").asText(), f));
                }
                for (JsonNode scenario : clazz.path("scenarios"))
                {
                    scenario.path("fields")
                            .forEach(f -> sourceFields.put(f.path("name").asText(), f));
                }
                clazz.path("cdashModelFields")
                        .forEach(f -> sourceFields.put(f.path("name").asText(), f));
            }
        }


        static ErrataAllowances fromRegister()
        {
            ErrataAllowances a = new ErrataAllowances();
            for (StoreErrata.Entry e : StoreErrata.load().entries())
            {
                switch (e.kind())
                {
                case "add-field" -> a
                        .container(e.product(), e.where().get("class"), e.where().get("domain"),
                                false)
                        .addedNames().add(e.node().path("field").path("name").asText());
                case "move-field" ->
                {
                    String field = e.node().path("field").asText();
                    a.container(e.product(), e.node().path("from").path("class").asText(), null,
                            false).removedNames().add(field);
                    String domain = e.node().path("to").path("domain").asText();
                    a.container(e.product(), null, domain, false).movedNames().add(field);
                    if (e.node().path("createDomain").asBoolean(false))
                    {
                        a.createdDomains.add(e.product() + "/" + domain);
                    }
                }
                default ->
                {
                    // no structural change
                }
                }
            }
            return a;
        }


        private Container container(String aProduct, @Nullable String aClass,
                @Nullable String aName, boolean aScenario)
        {
            String key = aProduct + "|" + aClass + "|" + (aScenario ? "scenario:" : "") + aName;
            return containers.computeIfAbsent(key, k -> new Container(new LinkedHashSet<>(),
                    new LinkedHashSet<>(), new LinkedHashSet<>(), new LinkedHashSet<>(), this));
        }


        Container forContainer(String aProduct, @Nullable String aClass, String aName,
                boolean aScenario)
        {
            return container(aProduct, aClass, aName, aScenario);
        }


        Container forClassFields(String aProduct, String aClass)
        {
            return container(aProduct, aClass, null, false);
        }


        boolean createsDomain(String aProduct, String aDomain)
        {
            boolean ok = createdDomains.contains(aProduct + "/" + aDomain);
            if (ok)
            {
                usedCreatedDomains.add(aProduct + "/" + aDomain);
            }
            return ok;
        }


        void assertEveryAllowanceWasUsed()
        {
            List<String> unused = new ArrayList<>();
            for (java.util.Map.Entry<String, Container> e : containers.entrySet())
            {
                Container c = e.getValue();
                for (String n : c.addedNames())
                {
                    if (!c.used().contains("+" + n))
                    {
                        unused.add(e.getKey() + " +" + n);
                    }
                }
                for (String n : c.movedNames())
                {
                    if (!c.used().contains(">" + n))
                    {
                        unused.add(e.getKey() + " >" + n);
                    }
                }
                for (String n : c.removedNames())
                {
                    if (!c.used().contains("-" + n))
                    {
                        unused.add(e.getKey() + " -" + n);
                    }
                }
            }
            for (String d : createdDomains)
            {
                if (!usedCreatedDomains.contains(d))
                {
                    unused.add("created domain " + d);
                }
            }
            assertEquals(List.of(), unused,
                    "errata allowances the walk never needed - the register and the store disagree");
        }
    }

    /**
     * The store {@link PickleStoreSeeder} writes from the real pickle corpus with
     * {@link #FIXED_PROVENANCE}, seeded on the first call and handed to every later one. Nothing
     * writes to the store after the seed: both tests only open it for reading.
     *
     * @param aRealPickles
     *            the real pickle corpus.
     * @return the seed.
     * @throws IOException
     *             if the seed fails; a later call then seeds again rather than reusing a partial
     *             store.
     */
    private static synchronized PickleSeed pickleSeed(Path aRealPickles) throws IOException
    {
        PickleSeed seed = seeded;
        if (seed == null)
        {
            Path store = sharedTemp.resolve("from-pickles.zip");
            StoreSeedReport report = new PickleStoreSeeder(new LocalPickleSource(aRealPickles))
                    .seed(StoreSeedOptions.of(store).withProvenanceOverride(FIXED_PROVENANCE));
            seed = new PickleSeed(store, report);
            seeded = seed;
        }
        return seed;
    }


    /**
     * Drops the seed with the class. The static {@link #sharedTemp} is deleted after the class,
     * while this static holder would survive a same-JVM rerun (an IDE re-run, a second launcher
     * session) and hand it a path that no longer exists.
     */
    @AfterAll
    static synchronized void forgetSeed()
    {
        seeded = null;
    }


    /**
     * Locates {@code /mdr/products} in the recorded cache, under either the name the cache uses now
     * or the pre-Q14 name the directory was recorded with.
     *
     * <p>
     * Both are accepted on purpose. Looking only for the legacy name would turn a future
     * re-recording into a silent skip — the test would stop running and say nothing — and looking
     * only for the current name would skip on the directory that exists today.
     * </p>
     *
     * @return the entry, or {@code null} when the recorded cache is not on this machine.
     */
    private static Path recordedProductsEntry()
    {
        Path current = REAL_WEB_CACHE.resolve(
                new GzipFileApiCache(REAL_WEB_CACHE, ".json").toCacheFileName(PRODUCTS_KEY));
        if (Files.isRegularFile(current))
        {
            return current;
        }
        Path legacy = REAL_WEB_CACHE.resolve(LEGACY_PRODUCTS_ENTRY);
        return Files.isRegularFile(legacy) ? legacy : null;
    }
}
