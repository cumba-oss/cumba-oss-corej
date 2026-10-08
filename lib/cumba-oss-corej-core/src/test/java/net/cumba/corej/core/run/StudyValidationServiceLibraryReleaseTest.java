package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.manager.IDataTableLibraryRef;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runbook W1b LR ({@code OPEN-DECISIONS.md} §3.9 ⓐ, owner 2026-10-07; O19): the service releases
 * every library reference it acquires — the data library, a Define-XML overlay, a Define-XML
 * standing in for the data, each reference library — exactly once, on its own exit path, whether
 * the run completes, refuses ("no datasets") or is cancelled. One reference acquired for both the
 * data and the metadata is released once. Measured through {@link CountingStudyManager}, which
 * counts the references it hands out against the ones given back.
 *
 * <p>
 * Internal engine only (O19): a manager without a {@code releaseLibraryRef} call has nothing to
 * release to, so the test is skipped there (the OSS datatable manager's interface carries no such
 * call; this file stays the byte-identical twin).
 * </p>
 */
class StudyValidationServiceLibraryReleaseTest
{

    @TempDir
    Path tempDir;

    private static final List<String> CUSTOM_PRODUCT = List.of("standards/custom/1-0");

    @BeforeEach
    void onlyWhereTheManagerReleases()
    {
        boolean releasable;
        try
        {
            IDataTableManager.class.getMethod("releaseLibraryRef", IDataTableLibraryRef.class);
            releasable = true;
        }
        catch (NoSuchMethodException e)
        {
            releasable = false;
        }
        Assumptions.assumeTrue(releasable,
                "the manager interface has no releaseLibraryRef: nothing to release (O19)");
    }


    private static IDataTable dmTable()
    {
        return MockTable.of().name("DM").label("Demographics")
                .col("USUBJID", "SUBJ-001", "SUBJ-002").build();
    }


    private static IMetadataLibrary studyMeta()
    {
        return TestMetadataFixtures.lib("study").meta(MetadataKeys.STANDARD_NAME, "custom")
                .meta(MetadataKeys.STANDARD_VERSION, "1-0")
                .table(TestMetadataFixtures.table("DM").label("Demographics")
                        .className("Special-Purpose").structure("One record per subject")
                        .column(TestMetadataFixtures.column("USUBJID", 0, DataValueType.STRING)
                                .label("Unique Subject Identifier").core("Req").role("Identifier")
                                .build())
                        .build())
                .build();
    }


    private Path writeRule() throws IOException
    {
        Path dir = Files.createDirectory(tempDir.resolve("rules-" + System.nanoTime()));
        String fileName = "rules-custom-1-0.json";
        Files.writeString(dir.resolve(fileName), """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-X-911"},
                      "Scope": {"Domains": {"Include": ["ALL"]}},
                      "Check": {"expression": "empty(USUBJID)"},
                      "Outcome": {"Message": "USUBJID is blank."}
                    }
                  }
                }
                """);
        new net.cumba.corej.core.RulePackageManifest("test",
                List.of(new net.cumba.corej.core.RulePackageManifest.Entry(fileName, "CDISC",
                        "custom", "1-0", 1, List.of()))).writeTo(dir);
        return dir;
    }


    private Path parseableDefine() throws IOException
    {
        Path define = tempDir.resolve("define.xml");
        try (InputStream in = getClass().getResourceAsStream("/define/define-e2-accessors.xml"))
        {
            assertNotNull(in, "fixture");
            Files.copy(in, define);
        }
        return define;
    }


    /** Runs the service with {@code customise} applied to a complete parameter set. */
    private CountingStudyManager run(Consumer<StudyValidationParams.Builder> customise)
        throws IOException
    {
        IDataTableManager mgr = CountingStudyManager.create(dmTable(), studyMeta());
        StudyValidationParams.Builder b = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(writeRule().toString())
                .rulesPackages(List.of("custom-1-0")).metadataProducts(CUSTOM_PRODUCT);
        customise.accept(b);
        new StudyValidationService("0.0.0-test").validate(b.build());
        return CountingStudyManager.handler(mgr);
    }


    private static void assertBalanced(CountingStudyManager counting, int expected)
    {
        assertEquals(List.of(), counting.violations, "no foreign or repeated release");
        assertEquals(expected, counting.acquired.size(), "references acquired");
        assertEquals(counting.acquired.size(), counting.released.size(),
                "every acquired reference is released, exactly once");
    }


    @Test
    void theDataLibraryIsReleasedWhenTheRunCompletes() throws IOException
    {
        assertBalanced(run(_ ->
        {
        }), 1);
    }


    @Test
    void aDefineOverlayIsASecondReferenceReleasedToo() throws IOException
    {
        Path define = parseableDefine();
        assertBalanced(run(b -> b.defineXmlPath(define.toString())), 2);
    }


    @Test
    void aDefineStandingInForTheDataIsOneReferenceReleasedOnce() throws IOException
    {
        Path define = parseableDefine();
        assertBalanced(run(b -> b.dataLibrary(null).defineXmlPath(define.toString())), 1);
    }


    @Test
    void everyReferenceLibraryIsReleased() throws IOException
    {
        Path ref1 = Files.createDirectory(tempDir.resolve("ref1"));
        Path ref2 = Files.createDirectory(tempDir.resolve("ref2"));
        assertBalanced(run(b -> b.referenceData(List.of(ref1.toString(), ref2.toString()))), 3);
    }


    @Test
    void aRunRefusedForHavingNoDatasetsReleasesWhatItAcquired()
    {
        CountingStudyManager[] counting = new CountingStudyManager[1];
        IDataTableManager mgr = CountingStudyManager.create(dmTable(), studyMeta());
        counting[0] = CountingStudyManager.handler(mgr);
        assertThrows(StudyValidationException.class, () ->
        {
            StudyValidationParams.Builder b = StudyValidationParams.builder().manager(mgr)
                    .dataLibrary(tempDir.toString()).rulesDir(writeRule().toString())
                    .rulesPackages(List.of("custom-1-0")).metadataProducts(CUSTOM_PRODUCT)
                    .datasetFilter(Set.of("NOSUCH"));
            new StudyValidationService("0.0.0-test").validate(b.build());
        });
        assertBalanced(counting[0], 1);
    }


    @Test
    void aCancelledRunReleasesWhatItAcquired()
    {
        IDataTableManager mgr = CountingStudyManager.create(dmTable(), studyMeta());
        assertThrows(CancelledException.class, () ->
        {
            StudyValidationParams.Builder b = StudyValidationParams.builder().manager(mgr)
                    .dataLibrary(tempDir.toString()).rulesDir(writeRule().toString())
                    .rulesPackages(List.of("custom-1-0")).metadataProducts(CUSTOM_PRODUCT)
                    .cancellation(() -> true);
            new StudyValidationService("0.0.0-test").validate(b.build());
        });
        assertBalanced(CountingStudyManager.handler(mgr), 1);
    }
}
