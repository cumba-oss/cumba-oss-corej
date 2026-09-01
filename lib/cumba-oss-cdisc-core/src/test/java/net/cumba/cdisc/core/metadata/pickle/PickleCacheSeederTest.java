package net.cumba.cdisc.core.metadata.pickle;

import static net.cumba.cdisc.core.metadata.pickle.PickleCacheKeysTest.writePickle;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import net.cumba.cdisc.library.api.model.ct.CtCodelist;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.web.api.cache.GzipFileApiCache;
import net.cumba.web.api.json.JsonNodeResource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 2 — {@link PickleCacheSeeder}.
 *
 * <p>
 * Fixtures are pickled at runtime, so nothing here depends on the {@code cdisc-rules-engine}
 * submodule.
 * </p>
 */
class PickleCacheSeederTest
{

    private static final String BASE_URL = "https://api.library.cdisc.org/api/";

    /**
     * The query CoreJ appends to every seeded metadata endpoint except the CT package index. Part
     * of the cache <b>key</b>, so it is part of the cache <b>file name</b> too — URL-encoded by
     * {@code FileApiCache.toCacheFileName}, which is what {@code %3F} / {@code %3D} are.
     */
    private static final String EXPAND = "?expand=true";

    private static final String EXPAND_ENCODED = "%3Fexpand%3Dtrue";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------
    // Fixture builders
    // ------------------------------------------------------------------

    private static Map<String, Object> links(String aRel, String aHref)
    {
        Map<String, Object> target = new LinkedHashMap<>();
        target.put("href", aHref);
        Map<String, Object> links = new LinkedHashMap<>();
        links.put(aRel, target);
        return links;
    }


    /** A standards entry shaped like the pickle: real API body plus the Python-only extras. */
    private static Map<String, Object> standardsEntry(String aSelfHref)
    {
        Map<String, Object> variable = new LinkedHashMap<>();
        variable.put("name", "AETERM");
        variable.put("ordinal", "1");

        Map<String, Object> dataset = new LinkedHashMap<>();
        dataset.put("name", "AE");
        dataset.put("datasetVariables", List.of(variable));

        Map<String, Object> klass = new LinkedHashMap<>();
        klass.put("name", "Events");
        klass.put("datasets", List.of(dataset));

        Map<String, Object> body = new LinkedHashMap<>();
        body.put("_links", links("self", aSelfHref));
        body.put("name", "SDTMIG");
        body.put("version", "3-4");
        body.put("classes", List.of(klass));
        // Python-only additions that must not reach the cache.
        body.put("dataset_names", List.of("AE"));
        body.put("standard_type", "sdtm");
        return body;
    }


    private static Map<String, Object> ctPackageEntry(String aId, boolean aExtensible)
    {
        Map<String, Object> term = new LinkedHashMap<>();
        term.put("submissionValue", "MILD");
        term.put("preferredTerm", "Mild");
        term.put("conceptId", "C100001");

        Map<String, Object> codelist = new LinkedHashMap<>();
        codelist.put("conceptId", "C66769");
        codelist.put("submissionValue", "AESEV");
        // The pickle carries a real boolean here; the API sends a string.
        codelist.put("extensible", aExtensible);
        codelist.put("terms", List.of(term));

        Map<String, Object> pkg = new LinkedHashMap<>();
        pkg.put("package", aId);
        pkg.put("codelists", List.of(codelist));
        return pkg;
    }


    /** Writes a minimal but representative pickle cache and returns the directory. */
    private static Path pickleDir(Path aRoot) throws IOException
    {
        Path dir = Files.createDirectories(aRoot.resolve("pkl"));

        Map<String, Object> standards = new LinkedHashMap<>();
        standards.put("standards/sdtmig/3-4", standardsEntry("/mdr/sdtmig/3-4"));
        standards.put("standards/tig/1-0/sdtm", standardsEntry("/mdr/integrated/tig/1-0/sdtm"));
        writePickle(dir, "standards_details", standards);

        Map<String, Object> models = new LinkedHashMap<>();
        models.put("models/sdtm/2-0", standardsEntry("/mdr/sdtm/2-0"));
        models.put("models/adam/2-1", standardsEntry("/mdr/adam/adam-2-1"));
        writePickle(dir, "standards_models", models);

        writePickle(dir, "sdtmct-2024-09-27", ctPackageEntry("sdtmct-2024-09-27", false));
        writePickle(dir, "adamct-2024-03-29", ctPackageEntry("adamct-2024-03-29", true));
        return dir;
    }


    private static SeedReport seed(Path aPkl, Path aCache) throws IOException
    {
        return seed(aPkl, aCache, BASE_URL, false, false);
    }


    private static SeedReport seed(Path aPkl, Path aCache, String aBaseUrl, boolean aOverwrite,
            boolean aDryRun)
        throws IOException
    {
        try (LocalPickleSource source = new LocalPickleSource(aPkl))
        {
            return new PickleCacheSeeder().seed(SeedOptions.builder(source, aCache, aBaseUrl)
                    .overwriteExisting(aOverwrite).dryRun(aDryRun).build());
        }
    }


    private static JsonNode readEntry(Path aCache, String aPath) throws IOException
    {
        String json = new GzipFileApiCache(aCache.toAbsolutePath(), ".json").read(aPath)
                .orElseThrow(() -> new AssertionError("no cache entry at " + aPath));
        return MAPPER.readTree(json);
    }

    // ------------------------------------------------------------------
    // File naming — the contract with GzipFileApiCache
    // ------------------------------------------------------------------


    @Test
    void writesTheExactFileNamesTheCacheReaderExpects(@TempDir Path root) throws IOException
    {
        Path cache = root.resolve("cache");

        seed(pickleDir(root), cache);

        List<String> names = new ArrayList<>();
        try (var files = Files.list(cache))
        {
            files.map(p -> p.getFileName().toString()).filter(n -> n.endsWith(".json.gz"))
                    .forEach(names::add);
        }
        assertTrue(names.contains("api_mdr_sdtmig_3-4" + EXPAND_ENCODED + ".json.gz"),
                names.toString());
        assertTrue(names.contains("api_mdr_sdtm_2-0" + EXPAND_ENCODED + ".json.gz"),
                names.toString());
        assertTrue(
                names.contains(
                        "api_mdr_ct_packages_sdtmct-2024-09-27" + EXPAND_ENCODED + ".json.gz"),
                names.toString());
        // The CT package index is the one seeded endpoint CoreJ fetches unexpanded
        // (CoreLibraryAccessImpl:163 → getCtPackages()), so it keeps the bare name.
        assertTrue(names.contains("api_mdr_ct_packages.json.gz"), names.toString());
        assertFalse(names.contains("api_mdr_ct_packages" + EXPAND_ENCODED + ".json.gz"),
                names.toString());
    }


    /**
     * The defect this re-key fixes: the seeder used to write the key the <em>producer</em> (the
     * Python populator) fetched — the bare path — while CoreJ looks entries up under the key the
     * <em>consumer</em> requests. Those bare names must no longer be produced, or a seeded cache is
     * inert for exactly the entries it exists to provide.
     */
    @Test
    void producerShapedKeysAreNoLongerWritten(@TempDir Path root) throws IOException
    {
        Path cache = root.resolve("cache");

        seed(pickleDir(root), cache);

        assertFalse(Files.exists(cache.resolve("api_mdr_sdtmig_3-4.json.gz")));
        assertFalse(Files.exists(cache.resolve("api_mdr_sdtm_2-0.json.gz")));
        assertFalse(Files.exists(cache.resolve("api_mdr_adam_adam-2-1.json.gz")));
        assertFalse(Files.exists(cache.resolve("api_mdr_ct_packages_sdtmct-2024-09-27.json.gz")));
    }


    /**
     * {@code _links.self.href} must win over key-derivation. These two are the regression guards:
     * the key says {@code models/adam/2-1} but the endpoint is {@code /mdr/adam/adam-2-1}, and the
     * key says {@code standards/tig/1-0/sdtm} but the endpoint is
     * {@code /mdr/integrated/tig/1-0/sdtm}.
     */
    @Test
    void selfHrefWinsOverKeyDerivation(@TempDir Path root) throws IOException
    {
        Path cache = root.resolve("cache");

        seed(pickleDir(root), cache);

        assertTrue(
                Files.exists(cache.resolve("api_mdr_adam_adam-2-1" + EXPAND_ENCODED + ".json.gz")));
        assertFalse(Files.exists(cache.resolve("api_mdr_adam_2-1" + EXPAND_ENCODED + ".json.gz")));
        // No CdiscLibraryClient method for /mdr/integrated/** takes an expand flag, so the TIG
        // substandards are fetched bare and must be seeded bare.
        assertTrue(Files.exists(cache.resolve("api_mdr_integrated_tig_1-0_sdtm.json.gz")));
        assertFalse(Files.exists(
                cache.resolve("api_mdr_integrated_tig_1-0_sdtm" + EXPAND_ENCODED + ".json.gz")));
        assertFalse(Files.exists(cache.resolve("api_mdr_tig_1-0_sdtm.json.gz")));
    }


    @Test
    void baseUrlPathDrivesTheFileNamePrefix(@TempDir Path root) throws IOException
    {
        Path pkl = pickleDir(root);

        Path lib = root.resolve("lib-cache");
        seed(pkl, lib, "https://example.org/lib/", false, false);
        assertTrue(Files.exists(lib.resolve("lib_mdr_sdtmig_3-4" + EXPAND_ENCODED + ".json.gz")));

        Path bare = root.resolve("bare-cache");
        seed(pkl, bare, "https://example.org/", false, false);
        assertTrue(Files.exists(bare.resolve("mdr_sdtmig_3-4" + EXPAND_ENCODED + ".json.gz")));
    }


    @Test
    void basePathOfHandlesTheBaseUrlShapes()
    {
        assertEquals("/api", PickleCacheSeeder.basePathOf("https://api.library.cdisc.org/api/"));
        assertEquals("/api", PickleCacheSeeder.basePathOf("https://api.library.cdisc.org/api"));
        assertEquals("", PickleCacheSeeder.basePathOf("https://example.org/"));
        assertEquals("", PickleCacheSeeder.basePathOf("https://example.org"));
        assertEquals("/a/b", PickleCacheSeeder.basePathOf("https://example.org/a/b/"));
    }

    // ------------------------------------------------------------------
    // Body transformations
    // ------------------------------------------------------------------


    @Test
    void pythonOnlyKeysAreStrippedAndLinksRetained(@TempDir Path root) throws IOException
    {
        Path cache = root.resolve("cache");

        seed(pickleDir(root), cache);

        JsonNode body = readEntry(cache, "/api/mdr/sdtmig/3-4" + EXPAND);
        assertFalse(body.has("dataset_names"), "dataset_names must not reach the cache");
        assertFalse(body.has("standard_type"), "standard_type must not reach the cache");
        assertTrue(body.has("_links"), "_links drives model navigation and must survive");
        assertEquals("SDTMIG", body.path("name").asText());
    }


    /**
     * The CT envelope is rebuilt around the pickle's reprojected codelists: {@code name} is the API
     * label form, {@code version}/{@code effectiveDate} come from the id, and the fields the pickle
     * cannot carry are omitted rather than invented.
     */
    @Test
    void ctPackageEnvelopeIsRebuiltWithoutFabrication(@TempDir Path root) throws IOException
    {
        Path cache = root.resolve("cache");

        seed(pickleDir(root), cache);

        JsonNode sdtm = readEntry(cache, "/api/mdr/ct/packages/sdtmct-2024-09-27" + EXPAND);
        assertEquals("SDTM CT 2024-09-27", sdtm.path("name").asText());
        assertEquals("2024-09-27", sdtm.path("version").asText());
        assertEquals("2024-09-27", sdtm.path("effectiveDate").asText());
        assertEquals("/mdr/ct/packages/sdtmct-2024-09-27",
                sdtm.path("_links").path("self").path("href").asText());
        // Unrecoverable from the pickle — omitted, never fabricated.
        assertFalse(sdtm.has("label"));
        assertFalse(sdtm.has("description"));
        assertFalse(sdtm.has("source"));
        assertFalse(sdtm.has("registrationStatus"));
        // The Python-only "package" key is replaced by the envelope.
        assertFalse(sdtm.has("package"));

        // ADaM keeps its distinctive mixed-case prefix.
        assertEquals("ADaM CT 2024-03-29",
                readEntry(cache, "/api/mdr/ct/packages/adamct-2024-03-29" + EXPAND).path("name")
                        .asText());
    }


    /** {@code extensible} is written back as the API's string form, both polarities. */
    @Test
    void extensibleIsWrittenAsTheApiStringForm(@TempDir Path root) throws IOException
    {
        Path cache = root.resolve("cache");

        seed(pickleDir(root), cache);

        JsonNode nonExt = readEntry(cache, "/api/mdr/ct/packages/sdtmct-2024-09-27" + EXPAND)
                .path("codelists").get(0).path("extensible");
        assertTrue(nonExt.isTextual(), "must be a JSON string, as the API sends it");
        assertEquals("false", nonExt.asText());

        JsonNode ext = readEntry(cache, "/api/mdr/ct/packages/adamct-2024-03-29" + EXPAND)
                .path("codelists").get(0).path("extensible");
        assertTrue(ext.isTextual());
        assertEquals("true", ext.asText());
    }


    @Test
    void ctIndexListsEveryPackageSorted(@TempDir Path root) throws IOException
    {
        Path cache = root.resolve("cache");

        seed(pickleDir(root), cache);

        JsonNode packages = readEntry(cache, "/api/mdr/ct/packages").path("_links")
                .path("packages");
        assertEquals(2, packages.size());
        assertEquals("/mdr/ct/packages/adamct-2024-03-29", packages.get(0).path("href").asText());
        assertEquals("/mdr/ct/packages/sdtmct-2024-09-27", packages.get(1).path("href").asText());
        assertEquals("Terminology", packages.get(0).path("type").asText());
    }

    // ------------------------------------------------------------------
    // Round trip — the test that proves meaning is preserved
    // ------------------------------------------------------------------


    /**
     * Seed, read the entry back through the cache, and view it as the typed product the live client
     * would produce. The class → dataset → variable walk must survive the round trip, and the CT
     * codelist must expose a usable {@code extensible} despite being stored as a string.
     */
    @Test
    void seededEntriesRoundTripIntoTheSameTypedViews(@TempDir Path root) throws IOException
    {
        Path cache = root.resolve("cache");
        seed(pickleDir(root), cache);

        SdtmProduct product = JsonNodeResource.of(readEntry(cache, "/api/mdr/sdtmig/3-4" + EXPAND),
                SdtmProduct.class);
        assertEquals(1, product.classes().size());
        assertEquals("Events", product.classes().get(0).name().orElse(null));
        assertEquals("AE", product.classes().get(0).datasets().get(0).name().orElse(null));
        assertEquals("AETERM", product.classes().get(0).datasets().get(0).datasetVariables().get(0)
                .name().orElse(null));

        CtPackage pkg = JsonNodeResource.of(
                readEntry(cache, "/api/mdr/ct/packages/sdtmct-2024-09-27" + EXPAND),
                CtPackage.class);
        assertEquals(1, pkg.codelists().size());
        CtCodelist codelist = pkg.codelists().get(0);
        assertEquals("AESEV", codelist.submissionValue().orElse(null));
        assertEquals("C66769", codelist.conceptId().orElse(null));
        // Depends on the Fix B lenient parse — a strict read would be empty here.
        assertFalse(codelist.extensible().orElseThrow());
        assertEquals("MILD", codelist.terms().get(0).submissionValue().orElse(null));
        assertEquals("C100001", codelist.terms().get(0).conceptId().orElse(null));
    }

    // ------------------------------------------------------------------
    // Run modes
    // ------------------------------------------------------------------


    @Test
    void reportCountsWhatWasWritten(@TempDir Path root) throws IOException
    {
        SeedReport report = seed(pickleDir(root), root.resolve("cache"));

        assertEquals(2, report.standardsWritten());
        assertEquals(2, report.modelsWritten());
        assertEquals(2, report.ctPackagesWritten());
        assertTrue(report.ctIndexWritten());
        assertTrue(report.skipped().isEmpty());
        assertTrue(report.warnings().isEmpty(), report.warnings().toString());
        assertNotNull(report.summary());
    }


    @Test
    void dryRunReportsWithoutWriting(@TempDir Path root) throws IOException
    {
        Path cache = root.resolve("cache");

        SeedReport report = seed(pickleDir(root), cache, BASE_URL, false, true);

        assertEquals(2, report.standardsWritten());
        assertEquals(7, report.written().size());
        assertFalse(Files.exists(cache.resolve("api_mdr_sdtmig_3-4" + EXPAND_ENCODED + ".json.gz")),
                "a dry run must not touch the filesystem");
    }


    @Test
    void secondRunSkipsExistingEntriesByDefault(@TempDir Path root) throws IOException
    {
        Path pkl = pickleDir(root);
        Path cache = root.resolve("cache");
        seed(pkl, cache);

        SeedReport second = seed(pkl, cache);

        assertEquals(0, second.standardsWritten());
        assertEquals(0, second.ctPackagesWritten());
        assertFalse(second.ctIndexWritten());
        assertEquals(7, second.skipped().size());
    }


    @Test
    void overwriteRewritesExistingEntries(@TempDir Path root) throws IOException
    {
        Path pkl = pickleDir(root);
        Path cache = root.resolve("cache");
        seed(pkl, cache);

        SeedReport second = seed(pkl, cache, BASE_URL, true, false);

        assertEquals(2, second.standardsWritten());
        assertEquals(2, second.ctPackagesWritten());
        assertTrue(second.ctIndexWritten());
        assertTrue(second.skipped().isEmpty());
    }


    /** Seeded entries are auditable: the sidecar records where they came from. */
    @Test
    void metaSidecarRecordsProvenance(@TempDir Path root) throws IOException
    {
        Path cache = root.resolve("cache");

        seed(pickleDir(root), cache);

        Path meta = cache.resolve("api_mdr_sdtmig_3-4" + EXPAND_ENCODED + ".json.gz.meta");
        assertTrue(Files.exists(meta));
        JsonNode node = MAPPER.readTree(Files.readString(meta));
        assertEquals(200, node.path("statusCode").asInt());
        assertEquals("seeded-from-pickle",
                node.path("headers").path("x-cache-source").get(0).asText());
    }

    // ------------------------------------------------------------------
    // Degradation
    // ------------------------------------------------------------------


    @Test
    void unparseableCtIdWarnsAndOmitsDerivedFields(@TempDir Path root) throws IOException
    {
        Path dir = Files.createDirectories(root.resolve("pkl"));
        writePickle(dir, "weirdct-notadate", ctPackageEntry("weirdct-notadate", false));
        Path cache = root.resolve("cache");

        SeedReport report = seed(dir, cache);

        assertEquals(1, report.ctPackagesWritten());
        assertEquals(1, report.warnings().size(), report.warnings().toString());
        JsonNode body = readEntry(cache, "/api/mdr/ct/packages/weirdct-notadate" + EXPAND);
        assertFalse(body.has("name"));
        assertFalse(body.has("version"));
        assertTrue(body.has("codelists"));
    }


    @Test
    void unknownCtPrefixWarnsButStillSynthesisesAName(@TempDir Path root) throws IOException
    {
        Path dir = Files.createDirectories(root.resolve("pkl"));
        writePickle(dir, "zzzct-2024-09-27", ctPackageEntry("zzzct-2024-09-27", false));
        Path cache = root.resolve("cache");

        SeedReport report = seed(dir, cache);

        assertEquals(1, report.warnings().size(), report.warnings().toString());
        assertEquals("ZZZCT CT 2024-09-27",
                readEntry(cache, "/api/mdr/ct/packages/zzzct-2024-09-27" + EXPAND).path("name")
                        .asText());
    }


    @Test
    void missingSelfHrefFallsBackToKeyDerivation(@TempDir Path root) throws IOException
    {
        Path dir = Files.createDirectories(root.resolve("pkl"));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", "SDTMIG");
        Map<String, Object> standards = new LinkedHashMap<>();
        standards.put("standards/sdtmig/3-4", body);
        writePickle(dir, "standards_details", standards);
        Path cache = root.resolve("cache");

        SeedReport report = seed(dir, cache);

        assertEquals(1, report.standardsWritten());
        assertTrue(Files.exists(cache.resolve("api_mdr_sdtmig_3-4" + EXPAND_ENCODED + ".json.gz")));
    }


    /**
     * H1 regression guard. {@code FileApiCache.write} deliberately swallows {@code IOException}
     * ("cache write failures are non-fatal"), so without an explicit post-write check an unwritable
     * target produces a report claiming everything was seeded — and the CLI exits 0.
     */
    @Test
    void anUnwritableTargetIsReportedRatherThanClaimedAsSuccess(@TempDir Path root)
        throws IOException
    {
        Path pkl = pickleDir(root);
        Path cache = Files.createDirectories(root.resolve("readonly"));
        assumeTrue(cache.toFile().setWritable(false), "cannot make the directory read-only here");
        // setWritable(false) returning true only means the permission bits changed — root (and
        // ACL/CAP_DAC_OVERRIDE environments, e.g. containerised CI runners) ignores the bits, so
        // the directory can still be perfectly writable. Probe the EFFECTIVE state and skip where
        // the premise cannot be staged; asserting it there inverts the test into a false red.
        boolean unwritable;
        try
        {
            Files.delete(Files.createTempFile(cache, "probe", ".tmp"));
            unwritable = false;
        }
        catch (IOException _)
        {
            unwritable = true;
        }
        if (!unwritable)
        {
            cache.toFile().setWritable(true);
        }
        assumeTrue(unwritable,
                "environment ignores permission bits (running as root?) — cannot stage an unwritable directory");
        try
        {
            SeedReport report = seed(pkl, cache);

            assertEquals(0, report.written().size(), "nothing landed, so nothing may be reported");
            assertEquals(0, report.standardsWritten());
            assertEquals(0, report.ctPackagesWritten());
            assertFalse(report.warnings().isEmpty(), "a silent write failure must warn");
            assertTrue(report.warnings().get(0).contains("silently failed"),
                    report.warnings().toString());
        }
        finally
        {
            cache.toFile().setWritable(true);
        }
    }


    /** M1: key-derivation is wrong for ADaM models and TIG, so falling back must warn. */
    @Test
    void keyDerivationFallbackWarns(@TempDir Path root) throws IOException
    {
        Path dir = Files.createDirectories(root.resolve("pkl"));
        Map<String, Object> noLinks = new LinkedHashMap<>();
        noLinks.put("name", "SDTMIG");
        Map<String, Object> standards = new LinkedHashMap<>();
        standards.put("standards/sdtmig/3-4", noLinks);
        writePickle(dir, "standards_details", standards);

        SeedReport report = seed(dir, root.resolve("cache"));

        assertEquals(1, report.standardsWritten());
        assertEquals(1, report.warnings().size(), report.warnings().toString());
        assertTrue(report.warnings().get(0).contains("derived from the key"),
                report.warnings().toString());
    }


    /** M2: a non-absolute self href would build a key no live request ever produces. */
    @Test
    void nonAbsoluteSelfHrefIsRejectedAndWarned(@TempDir Path root) throws IOException
    {
        Path dir = Files.createDirectories(root.resolve("pkl"));
        Map<String, Object> body = standardsEntry("https://library.cdisc.org/mdr/sdtmig/3-4");
        Map<String, Object> standards = new LinkedHashMap<>();
        standards.put("standards/sdtmig/3-4", body);
        writePickle(dir, "standards_details", standards);
        Path cache = root.resolve("cache");

        SeedReport report = seed(dir, cache);

        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("not an absolute path")),
                report.warnings().toString());
        // Falls back to key-derivation, which for a plain sdtmig key is correct.
        assertTrue(Files.exists(cache.resolve("api_mdr_sdtmig_3-4" + EXPAND_ENCODED + ".json.gz")));
    }


    /**
     * An endpoint that cannot form a URI cannot be requested by the client either, so it must be
     * warned about rather than written under a key nothing will ever look up.
     */
    @Test
    void anEndpointThatIsNotAUsableUriIsWarnedAndSkipped(@TempDir Path root) throws IOException
    {
        Path dir = Files.createDirectories(root.resolve("pkl"));
        Map<String, Object> standards = new LinkedHashMap<>();
        standards.put("standards/sdtmig/3-4", standardsEntry("/mdr/sdtmig/3 4"));
        writePickle(dir, "standards_details", standards);
        Path cache = root.resolve("cache");

        SeedReport report = seed(dir, cache);

        assertEquals(0, report.standardsWritten());
        assertTrue(report.written().isEmpty(), report.written().toString());
        assertTrue(report.warnings().stream().anyMatch(w -> w.contains("not a usable URI")),
                report.warnings().toString());
    }


    /** M8: one corrupt pickle must not abort the remaining entries. */
    @Test
    void aCorruptCtPickleIsWarnedAndTheRunContinues(@TempDir Path root) throws IOException
    {
        Path dir = pickleDir(root);
        Files.write(dir.resolve("badct-2024-09-27.pkl"),
                "not a pickle".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Path cache = root.resolve("cache");

        SeedReport report = seed(dir, cache);

        assertEquals(2, report.ctPackagesWritten(), "the healthy CT packages still seed");
        assertEquals(2, report.standardsWritten());
        assertTrue(report.warnings().stream().anyMatch(w -> w.startsWith("badct-2024-09-27")),
                report.warnings().toString());
    }


    @Test
    void emptyPickleDirectoryProducesAnEmptyReport(@TempDir Path root) throws IOException
    {
        Path dir = Files.createDirectories(root.resolve("pkl"));

        SeedReport report = seed(dir, root.resolve("cache"));

        assertEquals(0, report.standardsWritten());
        assertEquals(0, report.modelsWritten());
        assertEquals(0, report.ctPackagesWritten());
        assertFalse(report.ctIndexWritten());
    }

    // ------------------------------------------------------------------
    // M7 — one seeder, many runs
    // ------------------------------------------------------------------


    /** Sequential reuse of one instance: the second run must not inherit the first's tallies. */
    @Test
    void oneInstanceCanBeReusedSequentially(@TempDir Path root) throws IOException
    {
        Path pkl = pickleDir(root);
        PickleCacheSeeder seeder = new PickleCacheSeeder();

        SeedReport first;
        SeedReport second;
        try (LocalPickleSource a = new LocalPickleSource(pkl))
        {
            first = seeder.seed(SeedOptions.builder(a, root.resolve("cache-1"), BASE_URL).build());
        }
        try (LocalPickleSource b = new LocalPickleSource(pkl))
        {
            second = seeder.seed(SeedOptions.builder(b, root.resolve("cache-2"), BASE_URL).build());
        }

        assertEquals(first.written(), second.written());
        assertEquals(first.standardsWritten(), second.standardsWritten());
        assertEquals(first.ctPackagesWritten(), second.ctPackagesWritten());
    }


    /**
     * Concurrent reuse of one instance.
     *
     * <p>
     * Each run seeds a pickle directory carrying exactly one, uniquely named CT package into its
     * own target directory, so a report that contains another run's key can only have come from
     * shared state. The seeder now keeps its run state in a per-call holder, which makes that
     * impossible; before, the tallies and the accumulating lists were instance fields wiped by a
     * {@code reset()} at the start of every call, so two overlapping runs corrupted each other's
     * reports.
     * </p>
     */
    @Test
    void oneInstanceServesConcurrentRunsWithoutMixingTheirReports(@TempDir Path root)
        throws Exception
    {
        int runs = 4;
        PickleCacheSeeder seeder = new PickleCacheSeeder();
        List<String> ids = new ArrayList<>();
        List<Callable<SeedReport>> tasks = new ArrayList<>();
        for (int i = 0; i < runs; i++)
        {
            String id = "sdtmct-2024-01-0" + (i + 1);
            ids.add(id);
            Path pkl = Files.createDirectories(root.resolve("pkl-" + i));
            writePickle(pkl, id, ctPackageEntry(id, false));
            Path cache = root.resolve("cache-" + i);
            tasks.add(() ->
            {
                try (LocalPickleSource source = new LocalPickleSource(pkl))
                {
                    return seeder.seed(SeedOptions.builder(source, cache, BASE_URL).build());
                }
            });
        }

        List<Future<SeedReport>> futures;
        ExecutorService pool = Executors.newFixedThreadPool(runs);
        try
        {
            futures = pool.invokeAll(tasks);
        }
        finally
        {
            pool.shutdown();
        }

        for (int i = 0; i < runs; i++)
        {
            SeedReport report = futures.get(i).get();
            assertEquals(1, report.ctPackagesWritten(), "run " + i + ": " + report.written());
            assertEquals(0, report.standardsWritten());
            assertTrue(report.warnings().isEmpty(), report.warnings().toString());
            // Its own package plus the index — and nothing belonging to a sibling run.
            assertEquals(
                    List.of("/api/mdr/ct/packages/" + ids.get(i) + EXPAND, "/api/mdr/ct/packages"),
                    report.written());
        }
    }
}
