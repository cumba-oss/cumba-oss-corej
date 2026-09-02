package net.cumba.cdisc.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.cumba.cdisc.core.metadata.AdamDataStructureDetector;
import net.cumba.cdisc.core.metadata.AdamSubclassDetector;
import net.cumba.cdisc.core.metadata.MetadataKeys;
import net.cumba.cdisc.core.report.LibraryValidator;
import net.cumba.cdisc.core.report.ValidationReportBuilder;
import net.cumba.cdisc.core.run.StudyValidationParams.RuleSelectionMode;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.manager.IDataTableLibraryRef;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.manager.IDataTableRef;
import net.cumba.datatable.manager.ILibraryMemberRef;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReportMember;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for {@link StudyValidationService}. The data-table manager is mocked, so the tests run
 * fully offline. Using {@code standard = "custom"} keeps {@code StandardKind} UNKNOWN, which makes
 * {@code CdiscLibraryProviderBuilder.buildOrDegraded()} return a study-only provider with no
 * network call.
 */
class StudyValidationServiceTest
{

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // Mock manager helpers
    // ------------------------------------------------------------------

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


    private static ILibraryMemberRef member(String name, URI uri)
    {
        ILibraryMemberRef m = mock(ILibraryMemberRef.class);
        when(m.getName()).thenReturn(name);
        when(m.getUri()).thenReturn(uri != null ? uri.toString() : null);
        return m;
    }


    /** A manager whose single library member is DM, backed by the given table. */
    private IDataTableManager managerWith(IDataTable dmTable) throws IOException
    {
        IDataTableManager mgr = mock(IDataTableManager.class);
        IDataTableLibraryRef lib = mock(IDataTableLibraryRef.class);
        when(lib.getUri()).thenReturn("file:///study");
        when(mgr.getLibraryRef(any(URI.class), any())).thenReturn(lib);
        when(mgr.getMetadataLibrary(any())).thenReturn(studyMeta());

        ILibraryMemberRef dm = member("DM", URI.create("file:///study/dm.csv"));
        when(mgr.getLibraryMembers(any())).thenAnswer(_ -> Stream.of(dm));

        IDataTableRef ref = mock(IDataTableRef.class);
        when(mgr.getDataTableRef(any(ILibraryMemberRef.class), any())).thenReturn(ref);
        when(mgr.getDataTable(ref)).thenReturn(dmTable);
        return mgr;
    }

    /**
     * ⚑ Plan 2 (R5) — a run no longer carries {@code -s} / {@code -v}. These fixtures' packages
     * declare no CDISC Library standard, so the run's standard is derived from
     * {@code --metadata-products}. {@code standards/custom/1-0} is byte-for-byte the key the
     * removed {@code -s custom -v 1-0} pair used to imply ({@code PickleProductSource
     * .standardsKey}), so {@code StandardKind} stays {@code UNKNOWN} and the provider stays offline
     * exactly as before.
     */
    private static final List<String> CUSTOM_PRODUCT = List.of("standards/custom/1-0");

    private static IDataTable dmTable()
    {
        return MockTable.of().name("DM").label("Demographics")
                .col("USUBJID", "SUBJ-001", "SUBJ-002").build();
    }


    private Path writeRules(String fileName, String coreId) throws IOException
    {
        Path dir = Files.createDirectory(tempDir.resolve("rules-" + System.nanoTime()));
        Path f = dir.resolve(fileName);
        Files.writeString(f, """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "%s"},
                      "Check": {"name": "USUBJID", "operator": "var_exists"}
                    }
                  }
                }
                """.formatted(coreId));
        // Emit a packages.json so the conventional (manifest-driven) resolver finds this file under
        // the default CDISC family. standard/version come from the fileName convention
        // (rules-<standard>-<version>.json) the callers use, e.g. rules-custom-1-0.json.
        String base = fileName.substring("rules-".length(), fileName.length() - ".json".length());
        int dash = base.indexOf('-');
        String standard = base.substring(0, dash);
        String version = base.substring(dash + 1);
        new net.cumba.cdisc.core.RulePackageManifest("test",
                java.util.List.of(new net.cumba.cdisc.core.RulePackageManifest.Entry(fileName,
                        "CDISC", standard, version, 1))).writeTo(dir);
        return dir;
    }


    /**
     * Writes a rules dir holding one family-scoped package per family (each with a single
     * {@code <FAMILY>-X-001} rule) plus a {@code packages.json} manifest, all at
     * {@code (standard, version) = (custom, 1-0)}.
     */
    private Path writeFamilyRules(String... fams) throws IOException
    {
        Path dir = Files.createDirectory(tempDir.resolve("rules-" + System.nanoTime()));
        java.util.List<net.cumba.cdisc.core.RulePackageManifest.Entry> entries = new java.util.ArrayList<>();
        for (String fam : fams)
        {
            String file = "rules-" + fam.toLowerCase(java.util.Locale.ROOT) + "-custom-1-0.json";
            Files.writeString(dir.resolve(file), """
                    {
                      "rules": {
                        "u1": {
                          "id": "u1",
                          "Core": {"Id": "%s-X-001"},
                          "Check": {"name": "USUBJID", "operator": "var_exists"}
                        }
                      }
                    }
                    """.formatted(fam));
            entries.add(new net.cumba.cdisc.core.RulePackageManifest.Entry(file, fam, "custom",
                    "1-0", 1));
        }
        new net.cumba.cdisc.core.RulePackageManifest("test", entries).writeTo(dir);
        return dir;
    }

    // ------------------------------------------------------------------
    // ⛔ Phase 11 finding F9 — the declared-subclass WARN latch is JVM-global
    // ------------------------------------------------------------------


    @Test
    void validate_reArmsTheDeclaredSubclassWarnLatch() throws IOException
    {
        // AdamSubclassDetector.WARNED_DECLARATIONS is a static Set keyed only by
        // (datasetName, token). In the REST server that spans studies: study A's mis-declared
        // ADAE warns into A's run log, and study B's identically-named, identically-mis-declared
        // dataset is then SILENT — its operator never learns the declaration was dropped.
        // Nothing in resolve()'s arguments distinguishes one run from another, so the fix is a
        // per-run re-arm at the top of validate(). This test pins the CALL SITE, not just the
        // method: deleting the reset from StudyValidationService.validate reds step 3.
        AdamSubclassDetector.resetDeclarationWarnings();

        // 1. the first study reports the dropped declaration…
        List<String> firstStudy = captureWarnings(StudyValidationServiceTest::misdeclaredAdae);
        assertTrue(firstStudy.stream().anyMatch(m -> m.contains("declares subclass")),
                () -> "the first occurrence must warn; got " + firstStudy);

        // 2. …and is latched so it does not repeat once per rule inside that same study.
        List<String> sameStudyAgain = captureWarnings(StudyValidationServiceTest::misdeclaredAdae);
        assertTrue(sameStudyAgain.isEmpty(),
                () -> "the latch must still suppress repeats within one run; got "
                        + sameStudyAgain);

        // 3. ⭐ a NEW run re-arms it, so the next study is not silenced by the previous one.
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeFamilyRules("FDA");
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .rulesPackages(List.of("fda-custom-1-0")).metadataProducts(CUSTOM_PRODUCT).build();
        new StudyValidationService("0.0.0-test").validate(params);

        List<String> secondStudy = captureWarnings(StudyValidationServiceTest::misdeclaredAdae);
        assertTrue(secondStudy.stream().anyMatch(m -> m.contains("declares subclass")),
                () -> "a run must re-arm the latch, or the second study is silenced by the "
                        + "first; got " + secondStudy);
    }


    /**
     * An {@code ADAE} declaring {@code TIME-TO-EVENT}, whose precondition is a BASIC DATA STRUCTURE
     * it does not have — the declaration is dropped with the one-time WARN.
     */
    private static void misdeclaredAdae()
    {
        AdamSubclassDetector.resolve("ADAE", List.of(AdamDataStructureDetector.OCCDS), List.of(),
                List.of(AdamSubclassDetector.TIME_TO_EVENT), true);
    }


    /** Runs {@code aBody} with {@link AdamSubclassDetector}'s WARN output collected. */
    private static List<String> captureWarnings(Runnable aBody)
    {
        Logger logger = Logger.getLogger(AdamSubclassDetector.class.getName());
        List<LogRecord> records = new ArrayList<>();
        Handler handler = new Handler()
        {

            @Override
            public void publish(LogRecord aRecord)
            {
                if (aRecord.getLevel().intValue() >= Level.WARNING.intValue())
                {
                    records.add(aRecord);
                }
            }


            @Override
            public void flush()
            {
                // nothing buffered
            }


            @Override
            public void close()
            {
                // nothing to release
            }
        };
        handler.setLevel(Level.ALL);
        Level previous = logger.getLevel();
        logger.addHandler(handler);
        logger.setLevel(Level.ALL);
        try
        {
            aBody.run();
        }
        finally
        {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
        return records.stream().map(r -> MessageFormat.format(r.getMessage(), r.getParameters()))
                .toList();
    }


    @Test
    void validate_selectsOnlyTheRequestedPackage() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeFamilyRules("FDA", "PMDA");

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .rulesPackages(List.of("fda-custom-1-0")).metadataProducts(CUSTOM_PRODUCT).build();

        StudyValidationResult result = new StudyValidationService("0.0.0-test").validate(params);
        assertEquals(1, result.rules().size(),
                "only the named FDA package should load, not every package in the dir");
    }


    @Test
    void validate_unionsMultiplePackages() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeFamilyRules("FDA", "PMDA");

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .rulesPackages(List.of("fda-custom-1-0", "pmda-custom-1-0"))
                .metadataProducts(CUSTOM_PRODUCT).build();

        StudyValidationResult result = new StudyValidationService("0.0.0-test").validate(params);
        assertEquals(2, result.rules().size(), "both named packages should load");
    }

    // ------------------------------------------------------------------
    // Happy path
    // ------------------------------------------------------------------


    @Test
    void validate_writesReportAndResult() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-001");

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .rulesPackages(List.of("custom-1-0")).metadataProducts(CUSTOM_PRODUCT).build();

        StudyValidationResult result = new StudyValidationService("0.0.0-test").validate(params);

        assertNotNull(result.report());
        assertNotNull(result.conformance());
        assertEquals(1, result.datasets().size());
        assertEquals(1, result.rules().size());
        assertTrue(result.totalRuntimeSeconds() >= 0.0);

        // Serialisation moved to the report-writer modules (Fix #224); what the service owes its
        // caller is a complete set of assembled sections.
        assertTrue(result.sections().toExportDocument().containsKey("Conformance_Details"));
        assertFalse(result.sections().conformanceDetails().isEmpty());
    }


    @Test
    void validate_firesProgressEvents() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-002");

        AtomicInteger discovered = new AtomicInteger(-1);
        List<String> completed = new ArrayList<>();
        AtomicInteger ruleEvents = new AtomicInteger();
        AtomicInteger findingsSeen = new AtomicInteger();
        // Records whether the first dataset-completed event arrives before the last rule event —
        // proving completion is delivered live (interleaved with rule execution), not replayed
        // after the run finishes.
        AtomicInteger ruleEventsAtFirstCompletion = new AtomicInteger(-1);
        ProgressListener listener = new ProgressListener()
        {

            @Override
            public void onDatasetsDiscovered(int totalDatasets)
            {
                discovered.set(totalDatasets);
            }


            @Override
            public void onDatasetCompleted(int processed, int totalDatasets, String domain,
                    int datasetFindings)
            {
                completed.add(processed + "/" + totalDatasets + ":" + domain);
                findingsSeen.addAndGet(datasetFindings);
                ruleEventsAtFirstCompletion.compareAndSet(-1, ruleEvents.get());
            }


            @Override
            public void onRuleExecuted(LibraryValidator.RuntimeEntry entry)
            {
                ruleEvents.incrementAndGet();
            }
        };

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .progressListener(listener).rulesPackages(List.of("custom-1-0"))
                .metadataProducts(CUSTOM_PRODUCT).build();

        StudyValidationResult result = new StudyValidationService().validate(params);

        assertEquals(1, discovered.get());
        assertEquals(List.of("1/1:DM"), completed);
        assertTrue(ruleEvents.get() >= 1, "at least one rule should have executed");
        // The running per-dataset findings tally counts rule-violation rows on completed datasets,
        // so it never exceeds the report's authoritative count (which also includes any
        // engine-error / library-warning pseudo-findings).
        assertTrue(findingsSeen.get() <= result.findingCount(),
                "live tally must not exceed the final finding count");
        // The dataset completed only after its rules had run (live, not pre-emptively replayed).
        assertEquals(ruleEvents.get(), ruleEventsAtFirstCompletion.get(),
                "single-dataset run: completion fires after all its rule events");
    }

    // ------------------------------------------------------------------
    // Error paths
    // ------------------------------------------------------------------


    @Test
    void validate_noDataInputs_throwsStudyValidationException()
    {
        IDataTableManager mgr = mock(IDataTableManager.class);
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr).build();
        assertThrows(StudyValidationException.class,
                () -> new StudyValidationService().validate(params));
    }


    @Test
    void validate_emptyLibrary_throwsStudyValidationException() throws IOException
    {
        IDataTableManager mgr = mock(IDataTableManager.class);
        IDataTableLibraryRef lib = mock(IDataTableLibraryRef.class);
        when(mgr.getLibraryRef(any(URI.class), any())).thenReturn(lib);
        when(mgr.getLibraryMembers(any())).thenAnswer(_ -> Stream.empty());

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).build();
        StudyValidationException ex = assertThrows(StudyValidationException.class,
                () -> new StudyValidationService().validate(params));
        assertTrue(ex.getMessage().contains("no datasets"));
    }


    @Test
    void validate_datasetFilterMatchesNothing_throwsStudyValidationException() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).datasetFilter(Set.of("NOSUCH")).build();
        StudyValidationException ex = assertThrows(StudyValidationException.class,
                () -> new StudyValidationService().validate(params));
        assertTrue(ex.getMessage().contains("filter"));
    }


    @Test
    void validate_datasetFilterWithFileExtension_matchesMember() throws IOException
    {
        // Regression: the web UI sends the uploaded file name ("dm.csv"); the library member is
        // named "DM" (extension stripped). The filter must match it, not abort the run.
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-EXT");
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .datasetFilter(Set.of("dm.csv")).rulesPackages(List.of("custom-1-0"))
                .metadataProducts(CUSTOM_PRODUCT).build();

        StudyValidationResult result = new StudyValidationService().validate(params);

        assertEquals(1, result.datasets().size());
        assertEquals(1, result.executionSummaries().size());
        assertEquals("DM", result.executionSummaries().get(0).domain());
    }


    @Test
    void validate_datasetFilterBareName_matchesMember() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-BARE");
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .datasetFilter(Set.of("dm")).rulesPackages(List.of("custom-1-0"))
                .metadataProducts(CUSTOM_PRODUCT).build();

        StudyValidationResult result = new StudyValidationService().validate(params);

        assertEquals(1, result.datasets().size());
    }


    @Test
    void validate_executionSummaries_carryPerRuleExecutions() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-RX");
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .rulesPackages(List.of("custom-1-0")).metadataProducts(CUSTOM_PRODUCT).build();

        StudyValidationResult result = new StudyValidationService().validate(params);

        assertEquals(1, result.executionSummaries().size());
        DatasetExecutionSummary dm = result.executionSummaries().get(0);
        assertFalse(dm.ruleExecutions().isEmpty(), "per-rule executions should be recorded");
        DatasetExecutionSummary.RuleExecution rx = dm.ruleExecutions().get(0);
        assertEquals("EXECUTED", rx.status());
        assertEquals("CORE-X-RX", rx.coreId());
        assertNotNull(rx.generatedId());
        // Non-generated rule → no expansion variable, no not-executed reason.
        assertEquals(null, rx.expandedFor());
        assertEquals(null, rx.notExecutedReason());
    }


    @Test
    void validate_emitsRunNarrativeLines() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-LOG");

        CapturingHandler handler = new CapturingHandler();
        Logger juli = Logger.getLogger(StudyValidationService.class.getName());
        handler.setLevel(Level.ALL);
        juli.addHandler(handler);
        Level previous = juli.getLevel();
        juli.setLevel(Level.ALL);
        try
        {
            StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                    .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                    .rulesPackages(List.of("custom-1-0")).metadataProducts(CUSTOM_PRODUCT).build();

            new StudyValidationService().validate(params);

            List<String> lines = handler.formatted();
            assertTrue(lines.stream().anyMatch(l -> l.contains("Selected")),
                    "should emit the rule-selection line");
            assertTrue(lines.stream().anyMatch(l -> l.contains("[target] DM")),
                    "should emit the dataset-target line");
            assertTrue(lines.stream().anyMatch(l -> l.contains("Validation complete")),
                    "should emit the completion line");
        }
        finally
        {
            juli.removeHandler(handler);
            juli.setLevel(previous);
        }
    }


    @Test
    void stripExtUpper_variants()
    {
        assertEquals("", StudyValidationService.stripExtUpper(null));
        assertEquals("LB", StudyValidationService.stripExtUpper("lb.csv"));
        assertEquals("LB", StudyValidationService.stripExtUpper("LB"));
        assertEquals("SUPPLB", StudyValidationService.stripExtUpper("supplb.xpt"));
        // Only the last extension is stripped.
        assertEquals("A.B", StudyValidationService.stripExtUpper("a.b.c"));
    }


    @Test
    void validate_datasetFilterUnmatchedEntry_warns() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-WARN");

        CapturingHandler handler = new CapturingHandler();
        Logger juli = Logger.getLogger(StudyValidationService.class.getName());
        handler.setLevel(Level.ALL);
        juli.addHandler(handler);
        Level previous = juli.getLevel();
        juli.setLevel(Level.ALL);
        try
        {
            // "dm" matches DM; "ghost.csv" matches nothing → a warning, but the run still proceeds.
            StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                    .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                    .datasetFilter(Set.of("dm", "ghost.csv")).rulesPackages(List.of("custom-1-0"))
                    .metadataProducts(CUSTOM_PRODUCT).build();

            StudyValidationResult result = new StudyValidationService().validate(params);

            assertEquals(1, result.datasets().size());
            assertTrue(
                    handler.records.stream()
                            .filter(r -> r.getLevel().intValue() >= Level.WARNING.intValue())
                            .map(r -> MessageFormat.format(r.getMessage(), r.getParameters()))
                            .anyMatch(l -> l.contains("GHOST") && l.contains("did not match")),
                    "unmatched filter entry should produce a warning line");
        }
        finally
        {
            juli.removeHandler(handler);
            juli.setLevel(previous);
        }
    }


    @Test
    void validate_noRulesSelected_throwsStudyValidationException() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        // Empty rules dir → nothing to select. ⚑ Plan 2 (R3) moved this failure from the
        // rules-empty guard ("no rules selected for validation") forward to the SELECTION step,
        // so the message is now the actionable one; the subject — an empty rules dir must fail
        // loud rather than validate nothing — is unchanged.
        Path emptyRulesDir = Files.createDirectory(tempDir.resolve("empty-rules"));
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(emptyRulesDir.toString()).build();
        StudyValidationException ex = assertThrows(StudyValidationException.class,
                () -> new StudyValidationService().validate(params));
        assertTrue(ex.getMessage().contains("No rule package selected"), ex.getMessage());
        assertTrue(ex.getMessage().contains(emptyRulesDir.toAbsolutePath().toString()),
                "the message must name the directory searched: " + ex.getMessage());
    }


    @Test
    void validate_ruleSelectionModeNone_selectsNoRules() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-003");
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .ruleSelectionMode(RuleSelectionMode.NONE).rulesPackages(List.of("custom-1-0"))
                .metadataProducts(CUSTOM_PRODUCT).build();
        assertThrows(StudyValidationException.class,
                () -> new StudyValidationService().validate(params));
    }


    @Test
    void validate_includeFilterMatchesNoRule_selectsNoRules() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-004");
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .includeRules(List.of("CORE-NOPE")).rulesPackages(List.of("custom-1-0"))
                .metadataProducts(CUSTOM_PRODUCT).build();
        assertThrows(StudyValidationException.class,
                () -> new StudyValidationService().validate(params));
    }


    @Test
    void validate_includeFilterMatchesRule_keepsIt() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-005");
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .includeRules(List.of("CORE-X-005")).rulesPackages(List.of("custom-1-0"))
                .metadataProducts(CUSTOM_PRODUCT).build();
        StudyValidationResult result = new StudyValidationService().validate(params);
        assertEquals(1, result.rules().size());
    }


    @Test
    void validate_excludeFilterDropsRule_selectsNoRules() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-006");
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .excludeRules(List.of("CORE-X-006")).rulesPackages(List.of("custom-1-0"))
                .metadataProducts(CUSTOM_PRODUCT).build();
        assertThrows(StudyValidationException.class,
                () -> new StudyValidationService().validate(params));
    }


    @Test
    void validate_pathNotFound_throwsIOException()
    {
        IDataTableManager mgr = mock(IDataTableManager.class);
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.resolve("nope").toString()).build();
        IOException ex = assertThrows(IOException.class,
                () -> new StudyValidationService().validate(params));
        assertTrue(ex.getMessage().contains("path not found"));
    }


    @Test
    void validate_defineXmlMissingWithData_throwsIOException() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString())
                .defineXmlPath(tempDir.resolve("missing.xml").toString()).build();
        IOException ex = assertThrows(IOException.class,
                () -> new StudyValidationService().validate(params));
        assertTrue(ex.getMessage().contains("define.xml not found"));
    }

    // ------------------------------------------------------------------
    // Cancellation
    // ------------------------------------------------------------------


    @Test
    void validate_cancelledBeforeDatasetLoad_throwsCancelled() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-007");
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .cancellation(() -> true).rulesPackages(List.of("custom-1-0"))
                .metadataProducts(CUSTOM_PRODUCT).build();
        assertThrows(CancelledException.class, () -> new StudyValidationService().validate(params));
    }


    @Test
    void validate_cancelledDuringRuleExecution_throwsCancelled() throws IOException
    {
        // Flip cancellation to true only once enumeration (loadDatasets) has run — so the
        // pre-enumeration check passes and the per-rule cancel check inside the runtime listener
        // is the first place cancellation is observed as true. The listener runs on the
        // orchestration thread (sequential mode) and is not swallowed by the validator, so the
        // CancelledException propagates out of validate().
        AtomicBoolean cancel = new AtomicBoolean();
        // Build the mock table up front: MockTable.build() uses Mockito internally, so it must
        // not run inside an unfinished when(...).thenReturn(...) on the manager.
        IDataTable table = dmTable();
        IMetadataLibrary meta = studyMeta();
        ILibraryMemberRef dm = member("DM", URI.create("file:///study/dm.csv"));
        IDataTableManager mgr = mock(IDataTableManager.class);
        IDataTableLibraryRef lib = mock(IDataTableLibraryRef.class);
        IDataTableRef ref = mock(IDataTableRef.class);
        when(lib.getUri()).thenReturn("file:///study");
        when(mgr.getLibraryRef(any(URI.class), any())).thenReturn(lib);
        when(mgr.getMetadataLibrary(any())).thenReturn(meta);
        when(mgr.getLibraryMembers(any())).thenAnswer(_ ->
        {
            cancel.set(true);
            return Stream.of(dm);
        });
        when(mgr.getDataTableRef(any(ILibraryMemberRef.class), any())).thenReturn(ref);
        when(mgr.getDataTable(ref)).thenReturn(table);

        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-008");
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .cancellation(cancel::get).rulesPackages(List.of("custom-1-0"))
                .metadataProducts(CUSTOM_PRODUCT).build();
        assertThrows(CancelledException.class, () -> new StudyValidationService().validate(params));
    }


    @Test
    void cancelledExceptionMessages()
    {
        assertFalse(new CancelledException().getMessage().isEmpty());
        assertEquals("phase x", new CancelledException("phase x").getMessage());
    }

    // ------------------------------------------------------------------
    // Rules-directory resolution (explicit > env > system property > default)
    // ------------------------------------------------------------------


    @Test
    void rulesDirResolutionPrecedence()
    {
        // Explicit value always wins, even when env / sysprop are also set.
        assertEquals("/explicit",
                StudyValidationService.resolveRulesDir("/explicit", "/env", "/prop"));
        // Env beats the system property.
        assertEquals("/env", StudyValidationService.resolveRulesDir(null, "/env", "/prop"));
        assertEquals("/env", StudyValidationService.resolveRulesDir("  ", "/env", "/prop"));
        // System property used when there is no explicit value and no env var.
        assertEquals("/prop", StudyValidationService.resolveRulesDir(null, null, "/prop"));
        assertEquals("/prop", StudyValidationService.resolveRulesDir(null, " ", "/prop"));
    }


    @Test
    void rulesDirResolutionFallsBackToDefault()
    {
        assertEquals(StudyValidationService.DEFAULT_RULES_DIR,
                StudyValidationService.resolveRulesDir(null, null, null));
        assertEquals(StudyValidationService.DEFAULT_RULES_DIR,
                StudyValidationService.resolveRulesDir("", "", ""));
        // The default is the cwd-relative ./rules (no more deployment-path baked in).
        assertEquals("./rules", StudyValidationService.DEFAULT_RULES_DIR);
    }

    // ------------------------------------------------------------------
    // Phase 1/2/4a: exclusion, load-error finding, execution summary
    // ------------------------------------------------------------------


    @Test
    void validate_excludesDesignatedRulesFileFromDatasets() throws IOException
    {
        // A rule pack written into the library dir: used as the rules source, never a dataset.
        Path rulesFile = tempDir.resolve("rules.json");
        Files.writeString(rulesFile, """
                { "rules": { "u1": { "id":"u1","Core":{"Id":"CORE-X-010"},
                  "Check":{"name":"USUBJID","operator":"var_exists"} } } }
                """);

        IDataTableManager mgr = mock(IDataTableManager.class);
        IDataTableLibraryRef lib = mock(IDataTableLibraryRef.class);
        when(lib.getUri()).thenReturn("file:///study");
        when(mgr.getLibraryRef(any(URI.class), any())).thenReturn(lib);
        when(mgr.getMetadataLibrary(any())).thenReturn(studyMeta());
        ILibraryMemberRef dm = member("DM", tempDir.resolve("dm.csv").toUri());
        ILibraryMemberRef rules = member("rules", rulesFile.toUri());
        when(mgr.getLibraryMembers(any())).thenAnswer(_ -> Stream.of(dm, rules));
        IDataTable table = dmTable();
        IDataTableRef ref = mock(IDataTableRef.class);
        when(mgr.getDataTableRef(any(ILibraryMemberRef.class), any())).thenReturn(ref);
        when(mgr.getDataTable(ref)).thenReturn(table);

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(tempDir.resolve("norules").toString())
                .rulesFiles(List.of(rulesFile.toString())).metadataProducts(CUSTOM_PRODUCT).build();

        StudyValidationResult result = new StudyValidationService().validate(params);

        // Only DM is a dataset; the designated rules file is excluded from enumeration.
        assertEquals(1, result.datasets().size());
        assertEquals("dm.csv", result.datasets().get(0).filename());
    }


    @Test
    void validate_targetCannotBeOpened_recordsErrorFindingAndContinues() throws IOException
    {
        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-011");

        IDataTableManager mgr = mock(IDataTableManager.class);
        IDataTableLibraryRef lib = mock(IDataTableLibraryRef.class);
        when(lib.getUri()).thenReturn("file:///study");
        when(mgr.getLibraryRef(any(URI.class), any())).thenReturn(lib);
        when(mgr.getMetadataLibrary(any())).thenReturn(studyMeta());
        ILibraryMemberRef dm = member("DM", tempDir.resolve("dm.csv").toUri());
        when(mgr.getLibraryMembers(any())).thenAnswer(_ -> Stream.of(dm));
        IDataTableRef ref = mock(IDataTableRef.class);
        when(mgr.getDataTableRef(any(ILibraryMemberRef.class), any())).thenReturn(ref);
        when(mgr.getDataTable(ref)).thenThrow(new IOException("corrupt file"));

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .rulesPackages(List.of("custom-1-0")).metadataProducts(CUSTOM_PRODUCT).build();

        // The run completes — the load failure is a finding, not an abort.
        StudyValidationResult result = new StudyValidationService().validate(params);

        assertTrue(result.findingCount() >= 1);
        ValidationReportMember member = result.report().getMembers().stream()
                .filter(m -> "DM".equalsIgnoreCase(m.getDomain())).findFirst().orElseThrow();
        ValidationFinding finding = member.getFindings().get(0);
        assertEquals(ValidationReportBuilder.DATASET_LOAD_ERROR_RULE_ID, finding.getRuleId());
        assertEquals(Severity.ERROR, finding.getSeverity());
    }


    @Test
    void validate_capturesPerDomainExecutionSummary() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeRules("rules-custom-1-0.json", "CORE-X-012");

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .rulesPackages(List.of("custom-1-0")).metadataProducts(CUSTOM_PRODUCT).build();

        StudyValidationResult result = new StudyValidationService().validate(params);

        assertEquals(1, result.executionSummaries().size());
        DatasetExecutionSummary summary = result.executionSummaries().get(0);
        assertEquals("DM", summary.domain());
        assertEquals(1, summary.rulesTotal());
        assertTrue(summary.rulesExecuted() >= 0);
    }

    // ------------------------------------------------------------------
    // Pickle-cache id helpers (pure logic; the pickle-cache-gated provider tests live in
    // corej-cdisc-rules/StudyValidationServicePickleTest — PLAN-engine-rules-decoupling Q4)
    // ------------------------------------------------------------------


    @Test
    void ctIdWithPrefix_picksMatchingId()
    {
        assertEquals("sdtmct-2024-09-27", StudyValidationService
                .ctIdWithPrefix(List.of("adamct-2024-03-29", "sdtmct-2024-09-27"), "sdtmct"));
        assertEquals(null,
                StudyValidationService.ctIdWithPrefix(List.of("adamct-2024-03-29"), "sdtmct"));
        assertEquals(null, StudyValidationService.ctIdWithPrefix(List.of(), "sdtmct"));
    }

    /**
     * Collects the {@link LogRecord}s emitted by the service's class logger. {@link System.Logger}
     * (via Lombok's {@code @CustomLog}) routes through {@code java.util.logging}, so attaching this
     * to {@code Logger.getLogger(StudyValidationService.class.getName())} captures the run
     * narrative.
     */
    private static final class CapturingHandler extends Handler
    {

        final List<LogRecord> records = new ArrayList<>();

        /**
         * The captured records with their {@code {0}} placeholders substituted —
         * {@link LogRecord#getMessage()} returns the raw pattern, so the parameters must be folded
         * in via {@link MessageFormat} to recover the text a reader would see.
         */
        List<String> formatted()
        {
            return records.stream()
                    .map(r -> MessageFormat.format(r.getMessage(), r.getParameters())).toList();
        }


        @Override
        public void publish(LogRecord logRecord)
        {
            records.add(logRecord);
        }


        @Override
        public void flush()
        {
            // no-op
        }


        @Override
        public void close()
        {
            // no-op
        }
    }

    // ------------------------------------------------------------------
    // Plan 2 Phase 1 — rules-package selection (R1, R2, R3, R12; rulings Q2, Q4)
    // ------------------------------------------------------------------

    /**
     * Writes a rules dir holding one package per given short name, each with a single
     * {@code <SHORT>-X-001} rule, and NO {@code packages.json} — so only the {@code -rp} arm can
     * find them (R12: the filesystem decides what can run).
     */
    private Path writeUnmanifestedPackages(String... shortNames) throws IOException
    {
        Path dir = Files.createDirectory(tempDir.resolve("rules-" + System.nanoTime()));
        for (String shortName : shortNames)
        {
            Files.writeString(dir.resolve("rules-" + shortName + ".json"), """
                    {
                      "rules": {
                        "u1": {
                          "id": "u1",
                          "Core": {"Id": "%s-X-001"},
                          "Check": {"name": "USUBJID", "operator": "var_exists"}
                        }
                      }
                    }
                    """.formatted(shortName.toUpperCase(java.util.Locale.ROOT)));
        }
        return dir;
    }


    /** R1: a short name selects {@code rules-<short>.json}. */
    @Test
    void rulesPackage_shortNameSelectsThatPackage() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeUnmanifestedPackages("alpha-1-0");

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .rulesPackages(List.of("alpha-1-0")).metadataProducts(CUSTOM_PRODUCT).build();

        StudyValidationResult result = new StudyValidationService("0.0.0-test").validate(params);
        assertEquals(1, result.rules().size(), "the named package's rule must run");
    }


    /**
     * ⛔ R12 — the filesystem decides, not the manifest. The fixture writes NO
     * {@code packages.json}, so a manifest-driven resolver would find nothing; {@code -rp} must
     * still run the package.
     */
    @Test
    void rulesPackage_runsPackageAbsentFromTheManifest() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeUnmanifestedPackages("orphan-1-0");
        assertFalse(Files.exists(rulesDir.resolve("packages.json")),
                "fixture precondition: the package is unmanifested");

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .rulesPackages(List.of("orphan-1-0")).metadataProducts(CUSTOM_PRODUCT).build();

        assertEquals(1, new StudyValidationService("0.0.0-test").validate(params).rules().size());
    }


    /**
     * ⛔ R3. ⚠ This is NOT "it used to pass silently" — validate()'s rules-empty guard already threw
     * {@code "no rules selected for validation."}. What is pinned here is the ACTIONABLE failure R3
     * asks for: the message must name the directory searched and the short names that were
     * available, so the user can fix the invocation without reading the source.
     */
    @Test
    void noRulePackageSelected_failsLoudInsteadOfValidatingNothing() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        // Packages exist on disk, but none matches (custom, 9-9) and none is named via -rp.
        Path rulesDir = writeUnmanifestedPackages("alpha-1-0", "beta-2-0");

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString()).build();

        StudyValidationException ex = assertThrows(StudyValidationException.class,
                () -> new StudyValidationService("0.0.0-test").validate(params));
        assertTrue(ex.getMessage().contains("No rule package selected"), ex.getMessage());
        assertTrue(ex.getMessage().contains(rulesDir.toAbsolutePath().toString()),
                "the message must name the rules directory searched: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("alpha-1-0") && ex.getMessage().contains("beta-2-0"),
                "the message must list the available short names: " + ex.getMessage());
    }


    /** R1: an unknown short name is an error naming what IS available — never a silent skip. */
    @Test
    void rulesPackage_unknownShortNameFailsNamingAvailable() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeUnmanifestedPackages("alpha-1-0");

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .rulesPackages(List.of("nope-9-9")).metadataProducts(CUSTOM_PRODUCT).build();

        StudyValidationException ex = assertThrows(StudyValidationException.class,
                () -> new StudyValidationService("0.0.0-test").validate(params));
        assertTrue(ex.getMessage().contains("Unknown rule package 'nope-9-9'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("alpha-1-0"),
                "the message must list what is available: " + ex.getMessage());
    }


    /**
     * ⭐ Ruling Q2 — a named package and a custom rules file UNION. Before Plan 2 a non-empty
     * {@code --rules-file} suppressed the conventional packages entirely; the union is new.
     */
    @Test
    void rulesPackageAndRulesFile_union() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeUnmanifestedPackages("alpha-1-0");
        Path sponsor = tempDir.resolve("sponsor-own-" + System.nanoTime() + ".json");
        Files.writeString(sponsor, """
                {
                  "rules": {
                    "s1": {
                      "id": "s1",
                      "Core": {"Id": "SPONSOR-X-001"},
                      "Check": {"name": "USUBJID", "operator": "var_exists"}
                    }
                  }
                }
                """);

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .rulesPackages(List.of("alpha-1-0")).rulesFiles(List.of(sponsor.toString()))
                .metadataProducts(CUSTOM_PRODUCT).build();

        StudyValidationResult result = new StudyValidationService("0.0.0-test").validate(params);
        assertEquals(2, result.rules().size(),
                "the package and the sponsor file must BOTH run (ruling Q2)");
    }


    /**
     * Selection contract: naming a package loads THAT package and nothing else. Two packages sit in
     * the rules dir — one manifested, one not — and only the named one may run. (Before Plan 2 this
     * pinned "an explicit selection suppresses the conventional (family, standard, version) arm";
     * that arm is gone, but the exclusion it guarded is the same assertion.)
     */
    @Test
    void namingAPackageLoadsThatPackageAndNothingElse() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        // A manifested package ...
        Path rulesDir = writeFamilyRules("CDISC");
        // ... plus an unmanifested package selectable only by short name.
        Files.writeString(rulesDir.resolve("rules-alpha-1-0.json"), """
                {
                  "rules": {
                    "u9": {
                      "id": "u9",
                      "Core": {"Id": "ALPHA-X-001"},
                      "Check": {"name": "USUBJID", "operator": "var_exists"}
                    }
                  }
                }
                """);

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .rulesPackages(List.of("alpha-1-0")).metadataProducts(CUSTOM_PRODUCT).build();

        StudyValidationResult result = new StudyValidationService("0.0.0-test").validate(params);
        assertEquals(1, result.rules().size(),
                "naming a package must not also pull in the other package on disk");
    }


    /** {@code availableShortNames} strips the invariant prefix/suffix and sorts. */
    @Test
    void availableShortNames_listsEveryPackageOnDisk() throws IOException
    {
        Path rulesDir = writeUnmanifestedPackages("beta-2-0", "alpha-1-0");
        Files.writeString(rulesDir.resolve("not-a-package.json"), "{}");
        Files.writeString(rulesDir.resolve("rules-ignored.txt"), "x");

        assertEquals(List.of("alpha-1-0", "beta-2-0"),
                StudyValidationService.availableShortNames(rulesDir));
    }


    /** An absent rules directory yields no names rather than throwing. */
    @Test
    void availableShortNames_emptyWhenDirectoryAbsent()
    {
        assertEquals(List.of(),
                StudyValidationService.availableShortNames(tempDir.resolve("no-such-dir")));
    }

    // ------------------------------------------------------------------
    // Plan 2 Phase 2 — declared standards (R6) and manifest/disk reconciliation (R12)
    // ------------------------------------------------------------------


    /** R6: a package file's own {@code standards} array is read. */
    @Test
    void declaredStandards_readFromThePackageFile() throws IOException
    {
        Path dir = Files.createDirectory(tempDir.resolve("std-" + System.nanoTime()));
        Path f = dir.resolve("rules-decl-1-0.json");
        Files.writeString(f, """
                {
                  "standards": [
                    {"id": "sdtmig/3-4", "role": "primary"},
                    {"id": "sendig/dart-1-1", "role": "companion"}
                  ],
                  "rules": {}
                }
                """);

        net.cumba.cdisc.core.model.RulePackage pkg = net.cumba.cdisc.core.RulePackageLoader.load(f);
        List<net.cumba.cdisc.core.model.StandardRef> declared = StudyValidationService
                .declaredStandards(f, pkg, new net.cumba.cdisc.core.RulePackageManifest(List.of()));

        assertEquals(2, declared.size());
        assertEquals("sdtmig/3-4", declared.get(0).id());
        assertEquals(net.cumba.cdisc.core.model.StandardRef.Role.PRIMARY, declared.get(0).role());
        assertEquals(net.cumba.cdisc.core.model.StandardRef.Role.COMPANION, declared.get(1).role());
    }


    /**
     * ⛔ The declaration must be a MODELLED property. If {@code standards} ever stops binding it
     * lands in {@code unknownKeys} and vanishes without trace — this pins that it does NOT.
     */
    @Test
    void declaredStandards_isModelledNotSwallowedAsAnUnknownKey() throws IOException
    {
        Path dir = Files.createDirectory(tempDir.resolve("std2-" + System.nanoTime()));
        Path f = dir.resolve("rules-decl-1-0.json");
        Files.writeString(f, """
                {"standards": [{"id": "sdtmig/3-4", "role": "primary"}], "rules": {}}
                """);

        net.cumba.cdisc.core.model.RulePackage pkg = net.cumba.cdisc.core.RulePackageLoader.load(f);
        assertFalse(pkg.getUnknownKeys().contains("standards"),
                "'standards' must bind to the model, not be swallowed as an unknown key");
        assertNotNull(pkg.getStandards());
    }


    /** R6 fallback: with nothing on the file, the manifest's cached copy answers. */
    @Test
    void declaredStandards_fallBackToTheManifestCache() throws IOException
    {
        Path dir = Files.createDirectory(tempDir.resolve("std3-" + System.nanoTime()));
        Path f = dir.resolve("rules-cached-1-0.json");
        Files.writeString(f, """
                {"rules": {}}
                """);
        net.cumba.cdisc.core.RulePackageManifest manifest = new net.cumba.cdisc.core.RulePackageManifest(
                List.of(new net.cumba.cdisc.core.RulePackageManifest.Entry("rules-cached-1-0.json",
                        "CDISC", "custom", "1-0", 0,
                        List.of(new net.cumba.cdisc.core.model.StandardRef("sdtmig/3-4",
                                net.cumba.cdisc.core.model.StandardRef.Role.PRIMARY)))));

        net.cumba.cdisc.core.model.RulePackage pkg = net.cumba.cdisc.core.RulePackageLoader.load(f);
        List<net.cumba.cdisc.core.model.StandardRef> declared = StudyValidationService
                .declaredStandards(f, pkg, manifest);

        assertEquals(1, declared.size());
        assertEquals("sdtmig/3-4", declared.get(0).id());
    }


    /** The FILE wins over the manifest cache — the cache is a cache, not an authority. */
    @Test
    void declaredStandards_fileWinsOverTheManifestCache() throws IOException
    {
        Path dir = Files.createDirectory(tempDir.resolve("std4-" + System.nanoTime()));
        Path f = dir.resolve("rules-both-1-0.json");
        Files.writeString(f, """
                {"standards": [{"id": "adam/adamig-1-3", "role": "primary"}], "rules": {}}
                """);
        net.cumba.cdisc.core.RulePackageManifest manifest = new net.cumba.cdisc.core.RulePackageManifest(
                List.of(new net.cumba.cdisc.core.RulePackageManifest.Entry("rules-both-1-0.json",
                        "CDISC", "custom", "1-0", 0,
                        List.of(new net.cumba.cdisc.core.model.StandardRef("sdtmig/3-4",
                                net.cumba.cdisc.core.model.StandardRef.Role.PRIMARY)))));

        net.cumba.cdisc.core.model.RulePackage pkg = net.cumba.cdisc.core.RulePackageLoader.load(f);
        assertEquals("adam/adamig-1-3",
                StudyValidationService.declaredStandards(f, pkg, manifest).get(0).id(),
                "the package file's own declaration must win");
    }


    /** An unknown role is an authoring error and must not be silently dropped. */
    @Test
    void standardRef_unknownRoleIsRejected()
    {
        assertThrows(IllegalArgumentException.class,
                () -> net.cumba.cdisc.core.model.StandardRef.Role.fromWire("supplementary"));
    }


    /**
     * ⛔ R12 mirror case — a manifest entry naming an absent file is an ERROR (a broken corpus), not
     * staleness. ⚑ This case is EMPTY in the shipped corpus, so without this fixture the guard
     * would ship unexercised.
     */
    @Test
    void manifestNamingAnAbsentFile_isAnError() throws IOException
    {
        Path dir = Files.createDirectory(tempDir.resolve("orphan-" + System.nanoTime()));
        new net.cumba.cdisc.core.RulePackageManifest(
                List.of(new net.cumba.cdisc.core.RulePackageManifest.Entry("rules-ghost-1-0.json",
                        "CDISC", "custom", "1-0", 1))).writeTo(dir);

        StudyValidationException ex = assertThrows(StudyValidationException.class,
                () -> StudyValidationService.validateManifestAgainstDisk(dir,
                        net.cumba.cdisc.core.RulePackageManifest.load(dir)));
        assertTrue(ex.getMessage().contains("rules-ghost-1-0.json"), ex.getMessage());
    }


    /**
     * ⛔ R12 — a package on disk that the manifest omits STILL RUNS; it is only logged. ⚑ Also empty
     * in the shipped corpus, so it too would otherwise ship unexercised.
     */
    @Test
    void unmanifestedPackageOnDisk_warnsButDoesNotVeto() throws IOException
    {
        Path dir = writeUnmanifestedPackages("listed-1-0", "extra-2-0");
        new net.cumba.cdisc.core.RulePackageManifest(
                List.of(new net.cumba.cdisc.core.RulePackageManifest.Entry("rules-listed-1-0.json",
                        "CDISC", "custom", "1-0", 1))).writeTo(dir);

        // Must NOT throw — the manifest has no veto.
        StudyValidationService.validateManifestAgainstDisk(dir,
                net.cumba.cdisc.core.RulePackageManifest.load(dir));

        // ... and the unmanifested package is still selectable and runnable.
        IDataTableManager mgr = managerWith(dmTable());
        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(dir.toString())
                .rulesPackages(List.of("extra-2-0")).metadataProducts(CUSTOM_PRODUCT).build();
        assertEquals(1, new StudyValidationService("0.0.0-test").validate(params).rules().size());
    }


    /** A rules directory with no manifest at all is a supported shape, not a wall of warnings. */
    @Test
    void noManifestAtAll_isNotReportedAsUnmanifested() throws IOException
    {
        Path dir = writeUnmanifestedPackages("loose-1-0");
        StudyValidationService.validateManifestAgainstDisk(dir,
                net.cumba.cdisc.core.RulePackageManifest.load(dir));
    }

    // ------------------------------------------------------------------
    // Plan 2 Phase 3 — R7: declared standards join the effective product list
    // ------------------------------------------------------------------


    private static net.cumba.cdisc.core.model.StandardRef primary(String id)
    {
        return new net.cumba.cdisc.core.model.StandardRef(id,
                net.cumba.cdisc.core.model.StandardRef.Role.PRIMARY);
    }


    private static net.cumba.cdisc.core.model.StandardRef companion(String id)
    {
        return new net.cumba.cdisc.core.model.StandardRef(id,
                net.cumba.cdisc.core.model.StandardRef.Role.COMPANION);
    }


    /** ⭐ R7 — the user's own entries keep precedence; declarations are appended LAST. */
    @Test
    void effectiveMetadataProducts_appendsDeclarationsLast() throws IOException
    {
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(managerWith(dmTable())).dataLibrary(tempDir.toString())
                .pickleCacheDir(TestPickle.dirOrNull())
                .metadataProducts(List.of("standards/sdtmig/3-2")).build();

        List<String> effective = StudyValidationService.effectiveMetadataProducts(params,
                List.of(primary("adam/adamig-1-3"), companion("sdtmig/3-4")));

        assertEquals("standards/sdtmig/3-2", effective.get(0),
                "R7: what the user typed must stay first");
        assertTrue(effective.contains("standards/adam/adamig-1-3"));
        assertTrue(effective.contains("standards/sdtmig/3-4"));
        assertTrue(
                effective.indexOf("standards/sdtmig/3-2") < effective
                        .indexOf("standards/adam/adamig-1-3"),
                "declarations must come after user entries");
    }


    /** A declaration that repeats a user entry must not duplicate it. */
    @Test
    void effectiveMetadataProducts_dedupes() throws IOException
    {
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(managerWith(dmTable())).dataLibrary(tempDir.toString())
                .pickleCacheDir(TestPickle.dirOrNull())
                .metadataProducts(List.of("standards/sdtmig/3-4")).build();

        List<String> effective = StudyValidationService.effectiveMetadataProducts(params,
                List.of(companion("sdtmig/3-4")));

        assertEquals(1, effective.stream().filter("standards/sdtmig/3-4"::equals).count(),
                "a declaration repeating a user entry must not duplicate it");
    }


    /** With nothing declared the list is exactly what the caller supplied. */
    @Test
    void effectiveMetadataProducts_noDeclarationsIsIdentity() throws IOException
    {
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(managerWith(dmTable())).dataLibrary(tempDir.toString())
                .metadataProducts(List.of("standards/adam/adamig-1-3")).build();

        assertEquals(List.of("standards/adam/adamig-1-3"),
                StudyValidationService.effectiveMetadataProducts(params, List.of()));
    }


    /**
     * ⛔ Owner ruling Q1 — a package declaring a product CDISC never published must FAIL LOUD, not
     * resolve to something plausible. {@code sendig/dart-1-2} is the real case: CDISC publishes
     * DART 1.2 <em>rules</em> but no DART 1.2 metadata product, so it is absent from
     * {@code /mdr/products} and from every {@code standards/} cache namespace.
     */
    @Test
    void effectiveMetadataProducts_unpublishedDeclarationFailsLoud() throws IOException
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(TestPickle.dirOrNull() != null,
                "needs a pickle cache to have a catalogue to refute the token against");
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(managerWith(dmTable())).dataLibrary(tempDir.toString())
                .pickleCacheDir(TestPickle.dirOrNull()).build();

        // ⛔ Review finding R-8 — the TYPE changed deliberately. These ids come from the PACKAGE's
        // declaration, not from the user's -mp, but ProductKeyResolver reports an
        // IllegalArgumentException that no CLI catch handles, so selecting one of the three
        // shipped packages declaring the unpublished sendig/dart-1-2 produced a raw stack trace
        // and exit 1 while blaming a flag the user never passed. Q1's fail-loud ruling is intact:
        // it still fails, and still names the token — it is now an operational error.
        StudyValidationException ex = assertThrows(StudyValidationException.class,
                () -> StudyValidationService.effectiveMetadataProducts(params,
                        List.of(primary("sendig/dart-1-2"))));
        assertTrue(ex.getMessage().contains("dart-1-2"), ex.getMessage());
        assertTrue(ex.getMessage().contains("rule package"),
                "the message must blame the DECLARATION, not the user's -mp: " + ex.getMessage());
    }

    /** Small helper: the configured pickle cache directory, or null when there is none. */
    private static final class TestPickle
    {

        private TestPickle()
        {
        }


        static String dirOrNull()
        {
            String env = System.getenv("CDISC_PICKLE_CACHE_DIR");
            return env != null && !env.isBlank() && Files.isDirectory(Path.of(env)) ? env : null;
        }
    }

    // ------------------------------------------------------------------
    // Plan 2 Phase 4 — ⛔ R4: -mp NEVER selects rules (the plan's required guard)
    // ------------------------------------------------------------------

    /**
     * ⛔⛔ <b>The regression guard the plan demands.</b> An ADaM run declares an SDTM product so its
     * companion domains resolve — and {@code rules-cdisc-sdtmig-3-4.json} really exists in the
     * shipped corpus, so "naming a product pulls in that standard's rules" is a concrete regression
     * risk, not a theoretical one.
     *
     * <p>
     * The fixture puts BOTH packages on disk, names only the ADaM one with {@code -rp}, and
     * declares the SDTM product via {@code -mp}. Exactly one rule must run. If {@code -mp} ever
     * reaches rule selection this fails with 2.
     * </p>
     */
    @Test
    void metadataProductsNeverSelectRules() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeUnmanifestedPackages("adam-pkg-1-0", "sdtmig-pkg-3-4");

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .rulesPackages(List.of("adam-pkg-1-0"))
                .metadataProducts(List.of("standards/sdtmig/3-4")).build();

        StudyValidationResult result = new StudyValidationService("0.0.0-test").validate(params);

        assertEquals(1, result.rules().size(),
                "R4: declaring standards/sdtmig/3-4 as METADATA must not pull in the "
                        + "sdtmig package's rules");
        assertEquals("ADAM-PKG-1-0-X-001", result.rules().get(0).getCore().getId(),
                "only the NAMED package's rule may run");
    }


    /**
     * The mirror: the SDTM package is selectable on its own, from the same directory. Without this
     * the test above could pass because the fixture never produced a loadable SDTM rule at all.
     */
    @Test
    void theUnselectedPackageIsItselfLoadable() throws IOException
    {
        IDataTableManager mgr = managerWith(dmTable());
        Path rulesDir = writeUnmanifestedPackages("adam-pkg-1-0", "sdtmig-pkg-3-4");

        StudyValidationParams params = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(rulesDir.toString())
                .rulesPackages(List.of("sdtmig-pkg-3-4"))
                .metadataProducts(List.of("standards/sdtmig/3-4")).build();

        assertEquals("SDTMIG-PKG-3-4-X-001",
                new StudyValidationService("0.0.0-test").validate(params).rules().get(0).getCore()
                        .getId(),
                "fixture precondition: the sdtmig package IS loadable when named");
    }


    /**
     * ⛔ R6 — a package that declares NO standard and a run with no {@code -mp} has nothing to
     * resolve metadata against. That must fail loud rather than pick something plausible.
     */
    @Test
    void noDeclaredStandardAndNoMetadataProduct_failsLoud()
    {
        StudyValidationException ex = assertThrows(StudyValidationException.class,
                () -> StudyValidationService.runStandardOf(List.of(), List.of()));
        assertTrue(ex.getMessage().contains("--metadata-products"), ex.getMessage());
    }


    /** With no declaration but an explicit {@code -mp}, the first product supplies the standard. */
    @Test
    void noDeclaredStandardFallsBackToTheFirstMetadataProduct()
    {
        RunStandard r = StudyValidationService.runStandardOf(List.of(),
                List.of("standards/sdtmig/3-4"));
        assertEquals("sdtmig", r.standard());
        assertEquals("3-4", r.version());
    }


    /**
     * ⛔⛔ <b>Review finding R-2 / ruling V2.</b> The two shipped TIG packages declare four primary
     * legs with {@code adam} FIRST. R7 appends all four, {@code tigLeg} returns the first, and the
     * run silently routed onto the ADaM leg — so an SDTM-shaped TIG run resolved every SDTM domain
     * against an ADaM provider that answers empty for all of them, turning a visible SKIP into a
     * vacuous PASS. A multi-leg declaration must now be disambiguated by the user.
     */
    @Test
    void aMultiLegTigPackageRequiresAnExplicitMetadataProduct()
    {
        // The BARE spelling a package's declared id actually carries — the strict tigLegOf
        // pattern requires "standards/", so reading a declared id with it silently answers
        // "no leg" for EVERY package. That is exactly how the first cut of this guard failed
        // to fire, so the bare form is pinned here deliberately.
        List<String> fourLegs = List.of("tig/1-0/adam", "tig/1-0/cdash", "tig/1-0/sdtm",
                "tig/1-0/send");

        StudyValidationException e = assertThrows(StudyValidationException.class,
                () -> StudyValidationService.requireDisambiguatedTigLeg(List.of(), fourLegs));
        assertTrue(e.getMessage().contains("more than one TIG leg"), e.getMessage());
        assertTrue(e.getMessage().contains("--metadata-products"),
                "the message must name the knob that resolves it: " + e.getMessage());

        // Naming a leg resolves it — R7 puts the user's product first, so that choice governs.
        StudyValidationService.requireDisambiguatedTigLeg(List.of("standards/tig/1-0/sdtm"),
                fourLegs);
    }


    /** A single declared leg is unambiguous, and a non-TIG declaration is untouched. */
    @Test
    void aSingleLegOrNonTigDeclarationNeedsNoDisambiguation()
    {
        StudyValidationService.requireDisambiguatedTigLeg(List.of(),
                List.of("standards/tig/1-0/sdtm"));
        StudyValidationService.requireDisambiguatedTigLeg(List.of(),
                List.of("standards/adam/adamig-1-3", "standards/sdtmig/3-4"));
    }


    /**
     * ⛔⛔ <b>Review finding R-6 — the R7 SEAM had no test at all.</b>
     *
     * <p>
     * {@code effectiveMetadataProducts} had four unit tests, every one calling it directly with a
     * hand-built a hand-built list of {@code StandardRef}. {@code selectRulePackages} had none. And
     * every Phase 1 integration fixture writes packages via {@code writeUnmanifestedPackages},
     * which emits {@code {"rules": {…}}} and <b>declares nothing</b> — so no test anywhere drove a
     * real package file's declaration into the product list. R7 is the ONLY replacement for the
     * deleted {@code DEFAULT_COMPANION_SDTMIG} fallback (R10), which makes this the one seam where
     * deleting that fallback could go wrong in silence.
     * </p>
     *
     * <p>
     * This pins the whole chain the fixtures skipped: a package FILE on disk carrying a
     * {@code standards} block ⇒ {@code selectRulePackages} ⇒ {@code selection.declared()} ⇒
     * {@code effectiveMetadataProducts} ⇒ the resolved companion key in the product list, ordered
     * after the user's own products.
     * </p>
     */
    @Test
    void aPackageFilesDeclaredCompanionReachesTheEffectiveProductList(@TempDir Path dir)
        throws IOException
    {
        Path pack = dir.resolve("rules-decl-1-0.json");
        Files.writeString(pack, """
                {
                  "standards": [
                    { "id": "adam/adamig-1-3", "role": "primary" },
                    { "id": "sdtmig/3-4", "role": "companion" }
                  ],
                  "rules": {}
                }
                """);
        new net.cumba.cdisc.core.RulePackageManifest("test",
                List.of(new net.cumba.cdisc.core.RulePackageManifest.Entry("rules-decl-1-0.json",
                        "DECL", "adamig", "1-3", 0))).writeTo(dir);

        StudyValidationParams params = StudyValidationParams.builder()
                .manager(managerWith(dmTable())).dataLibrary(tempDir.toString())
                .rulesDir(dir.toString()).rulesPackages(List.of("decl-1-0")).build();

        var selection = StudyValidationService.selectRulePackages(params);
        List<String> effective = StudyValidationService.effectiveMetadataProducts(params,
                selection.declared());

        assertEquals(2, selection.declared().size(),
                "both declared standards must survive selection: " + selection.declared());
        assertTrue(effective.stream().anyMatch(k -> k.endsWith("sdtmig/3-4")),
                "the package's DECLARED COMPANION must reach the effective product list — this is "
                        + "R7, the sole replacement for the deleted DEFAULT_COMPANION_SDTMIG: "
                        + effective);
        assertTrue(effective.stream().anyMatch(k -> k.endsWith("adam/adamig-1-3")),
                "and so must the declared primary: " + effective);
    }
}
