package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
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
import java.util.Map;
import java.util.Objects;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.stream.Stream;
import net.cumba.corej.core.RulePackageManifest;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.manager.IDataTableLibraryRef;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.manager.IDataTableRef;
import net.cumba.datatable.manager.ILibraryMemberRef;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * End-to-end proof of owner ruling X1 ({@code plans/PLAN-use-case-scope-filter.md}): <i>"a client
 * can provide a use case and if a use case is given the filter should exclude all rules that
 * mention a use case but a different one."</i>
 *
 * <p>
 * Driven through {@link StudyValidationService#validate}, which every product surface (CLI, REST,
 * the data browser's manager) calls, so a filter wired anywhere below it but not reached from it
 * reds here. The fixture is one target dataset ({@code DM}, two rows) and four rules that each fire
 * on it when they run:
 * </p>
 * <ul>
 * <li>{@code UC-INDH} — {@code Use_Case: "INDH"}, a per-dataset rule;</li>
 * <li>{@code UC-NONE} — no {@code Use_Case};</li>
 * <li>{@code UC-MULTI} — {@code Use_Case: "NONCLIN, PROD"} (T1-1: <b>any</b> listed code includes
 * it);</li>
 * <li>{@code UC-STUDY} — {@code Use_Case: "INDH"} on an <b>anchor-eligible</b> study rule. It is
 * the arm that catches the plausible wrong implementation: a filter living only in
 * {@code DatasetRuleResolver.describeScopeSkip} never sees an anchor rule, because the study-anchor
 * pass removes those from the per-dataset path.</li>
 * </ul>
 */
class StudyValidationServiceUseCaseTest
{

    private static final List<String> CUSTOM_PRODUCT = List.of("standards/custom/1-0");

    private static final String PACKAGE = "custom-1-0";

    private static final String STUDY = "STUDY";

    @TempDir
    Path tempDir;

    // ------------------------------------------------------------------
    // Fixture
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


    /** A manager whose single library member is a two-subject DM. */
    private static IDataTableManager manager() throws IOException
    {
        IDataTableManager mgr = mock(IDataTableManager.class);
        IDataTableLibraryRef lib = mock(IDataTableLibraryRef.class);
        when(lib.getUri()).thenReturn("file:///study");
        when(mgr.getLibraryRef(any(URI.class), any())).thenReturn(lib);
        when(mgr.getMetadataLibrary(any())).thenReturn(studyMeta());
        ILibraryMemberRef dm = mock(ILibraryMemberRef.class);
        when(dm.getName()).thenReturn("DM");
        when(dm.getUri()).thenReturn("file:///study/dm.csv");
        when(mgr.getLibraryMembers(any())).thenAnswer(_ -> Stream.of(dm));
        IDataTable table = MockTable.of().name("DM").label("Demographics")
                .col("USUBJID", "SUBJ-001", "SUBJ-002").build();
        IDataTableRef ref = mock(IDataTableRef.class);
        when(mgr.getDataTableRef(any(ILibraryMemberRef.class), any())).thenReturn(ref);
        when(mgr.getDataTable(ref)).thenReturn(table);
        return mgr;
    }


    /** A per-dataset rule that flags every DM row ({@code USUBJID} is never empty there). */
    private static String rowRule(String coreId, String scope)
    {
        return """
                "%s": {
                  "Core": {"Id": "%s"},
                  %s
                  "Check": {"expression": "not empty(USUBJID)"},
                  "Outcome": {"Message": "%s fired"}
                }""".formatted(coreId, coreId, scope, coreId);
    }


    /** An anchor-eligible study rule that fires once: dataset XX is never in the study. */
    private static String studyRule(String coreId, String useCase)
    {
        return """
                "%s": {
                  "Core": {"Id": "%s"},
                  "Sensitivity": "Study",
                  "Scope": {"Domains": {"Include": ["ALL"]}, "Use_Case": "%s"},
                  "Check": {"expression": "not ds_exists(\\"XX\\")"},
                  "Outcome": {"Message": "%s fired"}
                }""".formatted(coreId, coreId, useCase, coreId);
    }


    private static String useCaseScope(String useCase)
    {
        return "\"Scope\": {\"Use_Case\": \"" + useCase + "\"},";
    }


    /** The four-rule fixture package of the class javadoc. */
    private static String fourRules()
    {
        return String.join(",\n", rowRule("UC-INDH", useCaseScope("INDH")), rowRule("UC-NONE", ""),
                rowRule("UC-MULTI", useCaseScope("NONCLIN, PROD")), studyRule("UC-STUDY", "INDH"));
    }


    /** Writes {@code rules-custom-1-0.json} + its manifest holding the given rule entries. */
    private Path rulesDir(String ruleEntries) throws IOException
    {
        Path dir = Files.createDirectory(tempDir.resolve("rules-" + System.nanoTime()));
        String fileName = "rules-" + PACKAGE + ".json";
        Files.writeString(dir.resolve(fileName), "{ \"rules\": {\n" + ruleEntries + "\n} }");
        new RulePackageManifest("test", List.of(
                new RulePackageManifest.Entry(fileName, "CDISC", "custom", "1-0", 1, List.of())))
                        .writeTo(dir);
        return dir;
    }


    private StudyValidationParams.Builder params(Path rulesDir) throws IOException
    {
        return StudyValidationParams.builder().manager(manager()).dataLibrary(tempDir.toString())
                .rulesDir(rulesDir.toString()).rulesPackages(List.of(PACKAGE))
                .metadataProducts(CUSTOM_PRODUCT);
    }


    private StudyValidationResult run(String ruleEntries,
            @org.jspecify.annotations.Nullable String useCase)
        throws IOException
    {
        return new StudyValidationService("0.0.0-test")
                .validate(params(rulesDir(ruleEntries)).useCase(useCase).build());
    }

    // ------------------------------------------------------------------
    // Report accessors
    // ------------------------------------------------------------------


    private static long findings(StudyValidationResult result, String coreId)
    {
        return result.sections().issueDetails().stream()
                .filter(row -> coreId.equals(row.get("core_id"))).count();
    }


    private static List<Map<String, Object>> skipRows(StudyValidationResult result, String coreId)
    {
        return result.sections().skippedRules().stream()
                .filter(row -> coreId.equals(row.get("core_id"))).toList();
    }


    private static Map<String, Object> rulesReportRow(StudyValidationResult result, String coreId)
    {
        return result.sections().rulesReport().stream()
                .filter(row -> coreId.equals(row.get("core_id"))).findFirst()
                .orElseThrow(() -> new AssertionError("no Rules_Report row for " + coreId));
    }


    private static List<DatasetExecutionSummary.RuleExecution> executions(
            StudyValidationResult result, String domain, String coreId)
    {
        List<DatasetExecutionSummary.RuleExecution> out = new ArrayList<>();
        for (DatasetExecutionSummary s : result.executionSummaries())
        {
            if (domain.equals(s.domain()))
            {
                s.ruleExecutions().stream().filter(e -> coreId.equals(e.coreId()))
                        .forEach(out::add);
            }
        }
        return out;
    }


    /**
     * Asserts {@code coreId} ran nowhere and is reported SKIPPED on DM with exactly {@code reason}.
     */
    private static void assertSkippedOnDm(StudyValidationResult result, String coreId,
            String reason)
    {
        assertEquals(0, findings(result, coreId), coreId + " must produce no finding");
        List<Map<String, Object>> rows = skipRows(result, coreId);
        assertEquals(1, rows.size(), () -> coreId + ": one Skipped_Rules row per (rule × dataset);"
                + " got " + result.sections().skippedRules());
        assertEquals(reason, rows.get(0).get("reason"), coreId);
        assertEquals("SKIPPED", rulesReportRow(result, coreId).get("status"), coreId);
        assertEquals(1, rulesReportRow(result, coreId).get("skipped"), coreId);
        List<DatasetExecutionSummary.RuleExecution> dm = executions(result, "DM", coreId);
        assertEquals(1, dm.size(), () -> coreId + " on DM: " + dm);
        assertEquals("SKIPPED", dm.get(0).status(), coreId);
        assertEquals(reason, dm.get(0).notExecutedReason(), coreId);
    }


    private static void assertFires(StudyValidationResult result, String coreId)
    {
        assertTrue(findings(result, coreId) > 0,
                () -> coreId + " must fire; issue details: " + result.sections().issueDetails());
        assertTrue(skipRows(result, coreId).isEmpty(),
                () -> coreId + " must not be skipped: " + result.sections().skippedRules());
        assertNotEquals("SKIPPED", rulesReportRow(result, coreId).get("status"), coreId);
    }

    // ------------------------------------------------------------------
    // A1 — no use case: nothing is filtered
    // ------------------------------------------------------------------


    @Test
    void a1_noUseCase_everyRuleRuns() throws IOException
    {
        StudyValidationResult result = run(fourRules(), null);

        for (String id : List.of("UC-INDH", "UC-NONE", "UC-MULTI", "UC-STUDY"))
        {
            assertFires(result, id);
        }
        assertTrue(result.sections().skippedRules().isEmpty(),
                () -> "no use case filters nothing: " + result.sections().skippedRules());
        assertNull(result.sections().conformanceDetails().get("TIG_Use_Case"));
        // The study rule really took the anchor path — otherwise A5 below proves nothing.
        assertEquals(1, executions(result, STUDY, "UC-STUDY").size(),
                "UC-STUDY must be executed once on the study anchor when no use case is given");
    }


    @Test
    void a1_blankUseCase_isNoUseCase() throws IOException
    {
        StudyValidationResult result = run(fourRules(), "   ");

        for (String id : List.of("UC-INDH", "UC-NONE", "UC-MULTI", "UC-STUDY"))
        {
            assertFires(result, id);
        }
        assertNull(result.sections().conformanceDetails().get("TIG_Use_Case"),
                "a blank use case is none, so the report must not echo it");
    }

    // ------------------------------------------------------------------
    // A2 / A5 — NONCLIN: the INDH-only rules are skipped, on both paths
    // ------------------------------------------------------------------


    @Test
    void a2_nonclin_skipsTheRulesThatNameOnlyAnotherUseCase() throws IOException
    {
        StudyValidationResult result = run(fourRules(), "NONCLIN");

        assertFires(result, "UC-NONE");
        assertFires(result, "UC-MULTI");
        String reason = "use case NONCLIN not in Scope.Use_Case [INDH]";
        assertSkippedOnDm(result, "UC-INDH", reason);
        assertSkippedOnDm(result, "UC-STUDY", reason);
        assertEquals("NONCLIN", result.sections().conformanceDetails().get("TIG_Use_Case"));
        // The full selection stays in the result, so Rules_Report can say SKIPPED at all.
        assertEquals(4, result.rules().size());
    }


    @Test
    void a5_anOutOfUseCaseAnchorRuleIsNotExecutedOnTheStudyAnchor() throws IOException
    {
        StudyValidationResult result = run(fourRules(), "NONCLIN");

        assertTrue(executions(result, STUDY, "UC-STUDY").isEmpty(),
                () -> "UC-STUDY must not run on the study anchor: "
                        + executions(result, STUDY, "UC-STUDY"));
        assertTrue(
                result.report().getMembers().stream()
                        .filter(m -> STUDY.equalsIgnoreCase(m.getDomain()))
                        .flatMap(m -> m.getFindings().stream())
                        .noneMatch(f -> "UC-STUDY".equals(f.getRuleId())
                                || Objects.toString(f.getMessage(), "").contains("UC-STUDY")),
                "no STUDY finding may come from the out-of-use-case anchor rule");
    }

    // ------------------------------------------------------------------
    // A3 / A4 — case-insensitive, trimmed, multi-code
    // ------------------------------------------------------------------


    @Test
    void a3_lowerCaseIndh_matchesCaseInsensitively() throws IOException
    {
        StudyValidationResult result = run(fourRules(), "indh");

        assertFires(result, "UC-INDH");
        assertFires(result, "UC-NONE");
        assertFires(result, "UC-STUDY");
        assertSkippedOnDm(result, "UC-MULTI",
                "use case indh not in Scope.Use_Case [NONCLIN, PROD]");
    }


    @Test
    void a4_paddedProd_isTrimmed_andAnyListedCodeIncludesTheRule() throws IOException
    {
        StudyValidationResult result = run(fourRules(), " PROD ");

        assertFires(result, "UC-MULTI");
        assertFires(result, "UC-NONE");
        assertSkippedOnDm(result, "UC-INDH", "use case PROD not in Scope.Use_Case [INDH]");
        assertSkippedOnDm(result, "UC-STUDY", "use case PROD not in Scope.Use_Case [INDH]");
        assertEquals("PROD", result.sections().conformanceDetails().get("TIG_Use_Case"),
                "the report echoes the normalised value");
    }

    // ------------------------------------------------------------------
    // A6 — T1-3: a value that is not a single code is rejected at the boundary
    // ------------------------------------------------------------------


    @Test
    void a6_aCommaListIsRejectedAtBuild() throws IOException
    {
        StudyValidationParams.Builder b = params(rulesDir(fourRules())).useCase("INDH, PROD");
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, b::build);
        assertTrue(ex.getMessage().contains("'INDH, PROD'"), ex.getMessage());
    }

    // ------------------------------------------------------------------
    // A7 — T1-4: a malformed rule Use_Case is a load error, never a silent skip
    // ------------------------------------------------------------------


    @Test
    void a7_aMalformedRuleUseCaseSurfacesAsAnErrorNotASkip() throws IOException
    {
        StudyValidationResult result = run(String.join(",\n",
                rowRule("UC-BAD", useCaseScope("INDH;PROD")), rowRule("UC-NONE", "")), "NONCLIN");

        assertFires(result, "UC-NONE");
        assertTrue(skipRows(result, "UC-BAD").isEmpty(),
                () -> "a malformed Use_Case must not read as out-of-use-case: "
                        + result.sections().skippedRules());
        Map<String, Object> row = rulesReportRow(result, "UC-BAD");
        assertEquals(1, row.get("errored"), () -> "UC-BAD: " + row);
        assertTrue(
                result.executionSummaries().stream().flatMap(s -> s.errors().stream())
                        .anyMatch(e -> "UC-BAD".equals(e.ruleId())
                                && Objects.toString(e.message(), "").contains("Use_Case")),
                () -> "the ERROR must name the Use_Case defect: " + result.executionSummaries());
    }

    // ------------------------------------------------------------------
    // A8 — a use case over a selection where no rule declares one: all run, one WARNING
    // ------------------------------------------------------------------


    @Test
    void a8_aUseCaseThatFiltersNothingIsWarnedAbout() throws IOException
    {
        String rules = String.join(",\n", rowRule("UC-A", ""), rowRule("UC-B", ""));
        List<String> warnings = new ArrayList<>();
        StudyValidationResult result = capture(Level.WARNING, warnings,
                () -> run(rules, "NONCLIN"));

        assertFires(result, "UC-A");
        assertFires(result, "UC-B");
        assertTrue(
                warnings.stream()
                        .anyMatch(m -> m.contains("NONCLIN")
                                && m.contains("no selected rule declares Scope.Use_Case")),
                () -> "the user must be told the use case filters nothing: " + warnings);
    }


    @Test
    void theInfoLineCountsTheExcludedRules() throws IOException
    {
        List<String> infos = new ArrayList<>();
        capture(Level.INFO, infos, () -> run(fourRules(), "NONCLIN"));

        assertTrue(
                infos.stream()
                        .anyMatch(m -> m.contains("Use case NONCLIN: 2 of 4 selected"
                                + " rule(s) declare another use case")),
                () -> "INFO lines: " + infos);
    }


    @Test
    void aRunWhoseEveryRuleIsOutsideTheUseCaseReportsThemSkippedAndDoesNotThrow() throws IOException
    {
        StudyValidationResult result = run(rowRule("UC-INDH", useCaseScope("INDH")), "PROD");

        assertSkippedOnDm(result, "UC-INDH", "use case PROD not in Scope.Use_Case [INDH]");
        assertFalse(result.rules().isEmpty(), "the selection itself was not empty");
    }

    // ------------------------------------------------------------------
    // Log capture
    // ------------------------------------------------------------------

    @FunctionalInterface
    private interface IoSupplier<T>
    {

        T get() throws IOException;
    }

    /**
     * Runs {@code body} collecting the formatted records the service logs at exactly {@code level}.
     */
    private static StudyValidationResult capture(Level level, List<String> sink,
            IoSupplier<StudyValidationResult> body)
        throws IOException
    {
        Logger logger = Logger.getLogger(StudyValidationService.class.getName());
        Handler handler = new Handler()
        {

            @Override
            public void publish(LogRecord aRecord)
            {
                if (aRecord.getLevel().equals(level))
                {
                    Object[] params = aRecord.getParameters();
                    sink.add(params == null ? aRecord.getMessage()
                            : MessageFormat.format(aRecord.getMessage(), params));
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
        Level previous = logger.getLevel();
        logger.setLevel(Level.ALL);
        logger.addHandler(handler);
        try
        {
            return body.get();
        }
        finally
        {
            logger.removeHandler(handler);
            logger.setLevel(previous);
        }
    }
}
