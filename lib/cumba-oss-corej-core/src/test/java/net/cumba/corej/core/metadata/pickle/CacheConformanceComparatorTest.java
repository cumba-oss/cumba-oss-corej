package net.cumba.corej.core.metadata.pickle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.web.api.cache.GzipFileApiCache;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Exercises {@link CacheConformanceComparator} against the case it exists for: a pickle-seeded
 * entry versus the same entry as the live API would have cached it.
 */
class CacheConformanceComparatorTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String PREFIX = "/api";

    private static void write(Path aDir, String aEndpoint, String aJson) throws IOException
    {
        new GzipFileApiCache(aDir.toAbsolutePath(), ".json").write(aEndpoint, aJson);
    }


    private static String json(Object aValue) throws IOException
    {
        return MAPPER.writeValueAsString(aValue);
    }


    /** A CT package as the live API sends it: string extensibility, full envelope. */
    private static String apiCtPackage() throws IOException
    {
        return json(Map.of("name", "SDTM CT 2024-09-27", "label",
                "SDTM Controlled Terminology Package 58 Effective 2024-09-27", "description",
                "CDISC Controlled Terminology for SDTM", "source", "CDISC", "registrationStatus",
                "Final", "version", "2024-09-27", "codelists",
                List.of(Map.of("conceptId", "C66769", "submissionValue", "AESEV", "extensible",
                        "false", "terms", List.of(Map.of("submissionValue", "MILD", "preferredTerm",
                                "Mild", "conceptId", "C100001"))))));
    }


    /** The same package as the seeder writes it: no label/description/source, string extensible. */
    private static String seededCtPackage() throws IOException
    {
        return json(Map.of("name", "SDTM CT 2024-09-27", "version", "2024-09-27", "effectiveDate",
                "2024-09-27", "codelists",
                List.of(Map.of("conceptId", "C66769", "submissionValue", "AESEV", "extensible",
                        "false", "terms", List.of(Map.of("submissionValue", "MILD", "preferredTerm",
                                "Mild", "conceptId", "C100001"))))));
    }


    @Test
    void droppedEnvelopeFieldsAreIgnoredNotFailures(@TempDir Path root) throws IOException
    {
        Path live = root.resolve("live");
        Path seeded = root.resolve("seeded");
        write(live, PREFIX + "/mdr/ct/packages/sdtmct-2024-09-27", apiCtPackage());
        write(seeded, PREFIX + "/mdr/ct/packages/sdtmct-2024-09-27", seededCtPackage());

        CacheConformanceComparator.Result result = CacheConformanceComparator.compare(live, seeded,
                PREFIX);

        assertTrue(result.conforms(), result.describe());
        // The bodies really do differ — the comparator classified that difference as ignorable.
        assertEquals(1, result.ignoredDifferences().size());
    }


    /** Boolean and string extensibility must normalise to the same projection. */
    @Test
    void extensibleWireFormsAreEquivalent(@TempDir Path root) throws IOException
    {
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        write(a, PREFIX + "/mdr/ct/packages/x", json(Map.of("codelists",
                List.of(Map.of("submissionValue", "AESEV", "extensible", "false")))));
        write(b, PREFIX + "/mdr/ct/packages/x", json(Map.of("codelists",
                List.of(Map.of("submissionValue", "AESEV", "extensible", false)))));

        assertTrue(CacheConformanceComparator.compare(a, b, PREFIX).conforms());
    }


    /** A genuine engine-visible difference must fail. */
    @Test
    void divergentExtensibilityIsAFailure(@TempDir Path root) throws IOException
    {
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        write(a, PREFIX + "/mdr/ct/packages/x", json(Map.of("codelists",
                List.of(Map.of("submissionValue", "AESEV", "extensible", "false")))));
        write(b, PREFIX + "/mdr/ct/packages/x", json(Map.of("codelists",
                List.of(Map.of("submissionValue", "AESEV", "extensible", "true")))));

        CacheConformanceComparator.Result result = CacheConformanceComparator.compare(a, b, PREFIX);

        assertFalse(result.conforms());
        assertEquals(1, result.projectionDifferences().size());
    }


    /** A class-name difference — the sdtmig 3-4 defect — must fail. */
    @Test
    void divergentClassNamesAreAFailure(@TempDir Path root) throws IOException
    {
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        write(a, PREFIX + "/mdr/sdtmig/3-4",
                json(Map.of("classes", List.of(Map.of("name", "Special-Purpose")))));
        write(b, PREFIX + "/mdr/sdtmig/3-4",
                json(Map.of("classes", List.of(Map.of("name", "SpecialPurpose")))));

        CacheConformanceComparator.Result result = CacheConformanceComparator.compare(a, b, PREFIX);

        assertFalse(result.conforms());
        assertEquals(List.of("/api/mdr/sdtmig/3-4"), result.projectionDifferences());
    }


    @Test
    void missingEndpointsAreReportedPerSide(@TempDir Path root) throws IOException
    {
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        write(a, PREFIX + "/mdr/sdtmig/3-4", json(Map.of("classes", List.of())));
        write(b, PREFIX + "/mdr/sdtmig/3-3", json(Map.of("classes", List.of())));

        CacheConformanceComparator.Result result = CacheConformanceComparator.compare(a, b, PREFIX);

        assertFalse(result.conforms());
        assertEquals(1, result.onlyInA().size());
        assertEquals(1, result.onlyInB().size());
    }


    @Test
    void identicalCachesConform(@TempDir Path root) throws IOException
    {
        Path a = root.resolve("a");
        Path b = root.resolve("b");
        write(a, PREFIX + "/mdr/ct/packages/sdtmct-2024-09-27", seededCtPackage());
        write(b, PREFIX + "/mdr/ct/packages/sdtmct-2024-09-27", seededCtPackage());

        CacheConformanceComparator.Result result = CacheConformanceComparator.compare(a, b, PREFIX);

        assertTrue(result.conforms());
        assertTrue(result.ignoredDifferences().isEmpty());
    }


    @Test
    void absentDirectoriesYieldEmptyEndpointSets(@TempDir Path root) throws IOException
    {
        CacheConformanceComparator.Result result = CacheConformanceComparator
                .compare(root.resolve("nope-a"), root.resolve("nope-b"), PREFIX);

        assertTrue(result.conforms());
    }

    // ------------------------------------------------------------------
    // The case the comparator was built for: a real seeder run against a
    // hand-built cache holding what the live API would have returned.
    // ------------------------------------------------------------------


    /**
     * A pickle directory carrying one IG and one CT package, in the shape the Python populator
     * writes them: {@code _links.self.href} on the standards entry, {@code dataset_names} added,
     * {@code extensible} converted to a real boolean, and the CT envelope reduced to
     * {@code {package, codelists}}.
     */
    private static Path picklesForConformance(Path aRoot) throws IOException
    {
        Path dir = Files.createDirectories(aRoot.resolve("pkl"));

        Map<String, Object> variable = new LinkedHashMap<>(
                Map.of("name", "AESEV", "label", "Severity", "ordinal", "1"));
        Map<String, Object> dataset = new LinkedHashMap<>(
                Map.of("name", "AE", "datasetVariables", List.of(variable)));
        Map<String, Object> klass = new LinkedHashMap<>(Map.of("name", "Events", "classVariables",
                List.of(variable), "datasets", List.of(dataset)));
        Map<String, Object> ig = new LinkedHashMap<>();
        ig.put("_links", Map.of("self", Map.of("href", "/mdr/sdtmig/3-4")));
        ig.put("name", "SDTMIG");
        ig.put("classes", List.of(klass));
        // Python-only additions the seeder must strip before writing.
        ig.put("dataset_names", Set.of("AE"));
        ig.put("standard_type", "tabulation");
        PickleCacheKeysTest.writePickle(dir, "standards_details",
                new LinkedHashMap<>(Map.of("standards/sdtmig/3-4", ig)));

        Map<String, Object> term = new LinkedHashMap<>(
                Map.of("submissionValue", "MILD", "preferredTerm", "Mild", "conceptId", "C100001"));
        Map<String, Object> codelist = new LinkedHashMap<>(Map.of("conceptId", "C66769",
                "submissionValue", "AESEV", "extensible", false, "terms", List.of(term)));
        PickleCacheKeysTest.writePickle(dir, "sdtmct-2024-09-27", new LinkedHashMap<>(
                Map.of("package", "sdtmct-2024-09-27", "codelists", List.of(codelist))));
        return dir;
    }


    /**
     * A seeded cache must conform with one filled from the live API.
     *
     * <p>
     * This is the acceptance test {@link CacheConformanceComparator} was written for, and the one
     * gap the class's own unit tests left: everything else here compares hand-built fixture against
     * hand-built fixture, or a seeder run against itself — neither of which can catch the seeder
     * projecting a body the engine reads differently from the API's. Here one side is a real
     * {@link PickleCacheSeeder} run and the other is written by hand as the CDISC Library
     * serialises it, <em>including</em> the envelope fields {@code get_codelist_terms_map}
     * permanently drops, the string-valued {@code extensible}, and the {@code _links} the pickle
     * has no room for.
     * </p>
     *
     * <p>
     * The keys are the ones CoreJ requests ({@code ?expand=true}), because that is what the seeder
     * writes and what a live response would have been cached under.
     * </p>
     */
    @Test
    void aSeededCacheConformsWithAHandBuiltApiCache(@TempDir Path root) throws IOException
    {
        Path seeded = root.resolve("seeded");
        try (LocalPickleSource source = new LocalPickleSource(picklesForConformance(root)))
        {
            SeedReport report = new PickleCacheSeeder().seed(SeedOptions
                    .builder(source, seeded, "https://api.library.cdisc.org/api/").build());
            assertTrue(report.warnings().isEmpty(), report.warnings().toString());
            assertEquals(1, report.standardsWritten());
            assertEquals(1, report.ctPackagesWritten());
        }

        Path live = root.resolve("live");
        // The IG as the API sends it: no dataset_names / standard_type, and carrying the
        // description / effectiveDate / registrationStatus the pickle never stored.
        write(live, PREFIX + "/mdr/sdtmig/3-4?expand=true", json(Map.of("_links",
                Map.of("self", Map.of("href", "/mdr/sdtmig/3-4")), "name", "SDTMIG", "description",
                "Study Data Tabulation Model Implementation Guide", "effectiveDate", "2021-11-27",
                "registrationStatus", "Final", "classes",
                List.of(Map.of("name", "Events", "classVariables",
                        List.of(Map.of("name", "AESEV", "label", "Severity", "ordinal", "1")),
                        "datasets", List.of(Map.of("name", "AE", "datasetVariables", List.of(Map
                                .of("name", "AESEV", "label", "Severity", "ordinal", "1")))))))));
        // The CT package as the API sends it: full envelope, extensible as a string.
        write(live, PREFIX + "/mdr/ct/packages/sdtmct-2024-09-27?expand=true", apiCtPackage());
        // The package index, which the engine fetches bare.
        write(live, PREFIX + "/mdr/ct/packages",
                json(Map.of("_links",
                        Map.of("packages",
                                List.of(Map.of("href", "/mdr/ct/packages/sdtmct-2024-09-27",
                                        "title", "sdtmct-2024-09-27", "type", "Terminology"))))));

        CacheConformanceComparator.Result result = CacheConformanceComparator.compare(live, seeded,
                PREFIX);

        assertTrue(result.conforms(), result.describe());
        // Both entries really do differ outside the projection — otherwise this would be proving
        // nothing but that two identical files are identical.
        assertEquals(2, result.ignoredDifferences().size(), result.describe());
    }


    /** The comparator is usable against a real seeder run, not just hand-built fixtures. */
    @Test
    void aSeededCacheConformsWithItself(@TempDir Path root) throws IOException
    {
        Path pkl = Files.createDirectories(root.resolve("pkl"));
        PickleCacheKeysTest.writePickle(pkl, "sdtmct-2024-09-27",
                new java.util.LinkedHashMap<>(Map.of("package", "sdtmct-2024-09-27", "codelists",
                        List.of(Map.of("conceptId", "C1", "submissionValue", "AESEV", "extensible",
                                false, "terms", List.of())))));

        Path first = root.resolve("first");
        Path second = root.resolve("second");
        for (Path target : List.of(first, second))
        {
            try (LocalPickleSource source = new LocalPickleSource(pkl))
            {
                new PickleCacheSeeder().seed(SeedOptions
                        .builder(source, target, "https://api.library.cdisc.org/api/").build());
            }
        }

        assertTrue(CacheConformanceComparator.compare(first, second, PREFIX).conforms());
    }
}
