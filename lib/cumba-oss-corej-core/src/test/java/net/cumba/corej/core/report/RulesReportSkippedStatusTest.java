package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.RowFindingSlab;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.SkippedRuleEntry;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationFindingLocation;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.junit.jupiter.api.Test;

/**
 * {@code Fix #225} — {@code Rules_Report.status} must be able to say {@code SKIPPED}.
 *
 * <p>
 * A rule skipped on every dataset it reached used to read {@code SUCCESS}, indistinguishable from a
 * rule that ran and found nothing. That is a false assurance: the report claimed "checked, no
 * problem" where the truth was "could not check".
 * </p>
 *
 * <p>
 * ⚠ The cheap reading — <em>"no findings + at least one skip entry ⇒ SKIPPED"</em> — is wrong, and
 * {@link #partiallySkippedRuleStaysSuccess()} is the test that would catch it: a rule skipped on
 * one dataset and executed on another really did run, and labelling it {@code SKIPPED} would be
 * worse than the bug it replaces. The status is therefore driven by the report's <b>executed</b>
 * set, not by the absence of findings.
 * </p>
 *
 * <p>
 * The end-to-end halves of the same contract — both skip channels through a real
 * {@link LibraryValidator} run — live in {@link RulesReportSkippedStatusChannelsTest}.
 * </p>
 */
class RulesReportSkippedStatusTest
{

    private static final String CORE_001 = "CORE-001";

    private static final String CORE_002 = "CORE-002";

    private static final String STATUS = "status";

    private static final String RULES_REPORT = "Rules_Report";

    // ------------------------------------------------------------------
    // The state table of PLAN-rules-report-skipped-status.md §2.1
    // ------------------------------------------------------------------

    @Test
    void ruleSkippedOnEveryDatasetReportsSkipped()
    {
        // No findings, one skip entry, executed nowhere ⇒ SKIPPED.
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(List.of(skip(CORE_001, "AE"))).executedCoreIds(List.of()).build();

        assertEquals("SKIPPED", statusOf(rulesReport(report, rule(CORE_001)), CORE_001));
    }


    @Test
    void ruleSkippedOnEveryDatasetOfSeveralReportsSkipped()
    {
        // The same rule skipped on three datasets and executed on none is still one SKIPPED row.
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(
                        List.of(skip(CORE_001, "AE"), skip(CORE_001, "DM"), skip(CORE_001, "VS")))
                .executedCoreIds(List.of()).build();

        List<Map<String, Object>> rows = rulesReport(report, rule(CORE_001));
        assertEquals(1, rows.size(),
                "one Rules_Report row per rule, however many skips it carries");
        assertEquals("SKIPPED", rows.get(0).get(STATUS));
    }


    @Test
    void partiallySkippedRuleStaysSuccess()
    {
        // ⚠ THE REGRESSION GUARD. Skipped on VS, executed (cleanly) on AE ⇒ the rule DID run, so
        // SUCCESS. A naive "no findings + a skip entry ⇒ SKIPPED" reading fails exactly here.
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(List.of(skip(CORE_001, "VS"))).executedCoreIds(List.of(CORE_001))
                .build();

        assertEquals("SUCCESS", statusOf(rulesReport(report, rule(CORE_001)), CORE_001));
    }


    @Test
    void partiallySkippedRuleKeepsEverySkipEntry()
    {
        // ...and nothing is lost by staying SUCCESS: the per-dataset skips remain visible.
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(List.of(skip(CORE_001, "VS"), skip(CORE_001, "LB")))
                .executedCoreIds(List.of(CORE_001)).build();

        Map<String, Object> export = export(report, rule(CORE_001));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> skipped = (List<Map<String, Object>>) export.get("Skipped_Rules");
        assertEquals(2, skipped.size());
        assertEquals(List.of("VS", "LB"), skipped.stream().map(r -> r.get("dataset")).toList());
    }


    @Test
    void cleanRunWithoutAnySkipStaysSuccess()
    {
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(List.of()).executedCoreIds(List.of(CORE_001)).build();

        assertEquals("SUCCESS", statusOf(rulesReport(report, rule(CORE_001)), CORE_001));
    }


    @Test
    void ruleNeitherExecutedNorSkippedStaysSuccess()
    {
        // Q1 of the plan: "never reached" is not a state the v1 schema has a label for, so it keeps
        // the pre-existing SUCCESS. Pinned so a later change to it is a deliberate one.
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(List.of()).executedCoreIds(List.of()).build();

        assertEquals("SUCCESS", statusOf(rulesReport(report, rule(CORE_001)), CORE_001));
    }


    @Test
    void aReportWithoutTheExecutedSetNeverInventsSkipped()
    {
        // Back-compat: a ValidationReport deserialised from a pre-Fix-#225 document has no
        // executedCoreIds. It must degrade to the OLD behaviour for rules that did run, not
        // fabricate SKIPPED — hence "skipped everywhere" is only claimed when a skip entry exists.
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(List.of()).build();

        assertEquals("SUCCESS", statusOf(rulesReport(report, rule(CORE_001)), CORE_001));
        assertEquals(List.of(), report.getExecutedCoreIds());
    }

    // ------------------------------------------------------------------
    // C5 — findings still win; the new branch must not steal precedence
    // ------------------------------------------------------------------


    @Test
    void violationBeatsSkipped()
    {
        ValidationReport report = ValidationReport.builder()
                .members(List.of(member("AE", finding(CORE_001, FindingKind.RULE_VIOLATION))))
                .skippedRules(List.of(skip(CORE_001, "VS"))).executedCoreIds(List.of()).build();

        // Note the deliberately hostile setup: the executed set is EMPTY, so only the finding stops
        // this row reading SKIPPED.
        assertEquals("ISSUE_REPORTED", statusOf(rulesReport(report, rule(CORE_001)), CORE_001));
    }


    @Test
    void engineErrorBeatsSkipped()
    {
        ValidationReport report = ValidationReport.builder()
                .members(List.of(member("AE", finding(CORE_001, FindingKind.ENGINE_ERROR))))
                .skippedRules(List.of(skip(CORE_001, "VS"))).executedCoreIds(List.of()).build();

        assertEquals("EXECUTION_ERROR", statusOf(rulesReport(report, rule(CORE_001)), CORE_001));
    }


    @Test
    void oneRuleSkippedDoesNotBleedIntoAnother()
    {
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(List.of(skip(CORE_001, "AE"))).executedCoreIds(List.of(CORE_002))
                .build();

        List<Map<String, Object>> rows = rulesReport(report, rule(CORE_001), rule(CORE_002));
        assertEquals("SKIPPED", statusOf(rows, CORE_001));
        assertEquals("SUCCESS", statusOf(rows, CORE_002));
    }


    @Test
    void skippedRowKeepsTheRulesDeclaredMessage()
    {
        // The status changes; the message column does not. The skip REASON stays in Skipped_Rules,
        // which is the section that carries per-dataset detail.
        Rule rule = rule(CORE_001);
        net.cumba.corej.core.model.Outcome outcome = new net.cumba.corej.core.model.Outcome();
        outcome.setMessage("AESEV is required.");
        rule.setOutcome(outcome);

        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(List.of(skip(CORE_001, "AE"))).executedCoreIds(List.of()).build();

        Map<String, Object> row = rulesReport(report, rule).get(0);
        assertEquals("SKIPPED", row.get(STATUS));
        assertEquals("AESEV is required.", row.get("message"));
    }

    // ------------------------------------------------------------------
    // C2 — the XLSX side of this behaviour is pinned in the cumba-oss-corej-report-xlsx module
    // (RulesReportSkippedStatusXlsxTest): the writer left the engine in Fix #224, and an
    // engine test cannot reach it without recreating the POI dependency the split removed.
    // What stays here is the status derivation itself, asserted on the shared sections above.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------
    // The builder side of the executed set
    // ------------------------------------------------------------------


    @Test
    void skippedEntriesAlsoReachTheV2Report()
    {
        // json2 shares buildRulesReport() with v1, so the corrected status must appear there too.
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(List.of(skip(CORE_001, "AE"))).executedCoreIds(List.of()).build();

        Map<String, Object> v2 = new ReportAssembler().report(report).rules(List.of(rule(CORE_001)))
                .sections().toCombinedExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) v2.get(RULES_REPORT);
        assertEquals("SKIPPED", rows.get(0).get(STATUS));
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------


    private static Map<String, Object> export(ValidationReport report, Rule... rules)
    {
        return new ReportAssembler().report(report).rules(List.of(rules)).sections()
                .toExportDocument();
    }


    private static List<Map<String, Object>> rulesReport(ValidationReport report, Rule... rules)
    {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) export(report, rules)
                .get(RULES_REPORT);
        return rows;
    }


    private static String statusOf(List<Map<String, Object>> rows, String coreId)
    {
        for (Map<String, Object> row : rows)
        {
            if (coreId.equals(row.get("core_id")))
            {
                return String.valueOf(row.get(STATUS));
            }
        }
        return "<no row for " + coreId + ">";
    }


    private static SkippedRuleEntry skip(String coreId, String dataset)
    {
        return SkippedRuleEntry.builder().coreId(coreId).dataset(dataset)
                .reason("scope did not match " + dataset).build();
    }


    private static ValidationReportMember member(String domain, ValidationFinding finding)
    {
        return ValidationReportMember.builder().domain(domain)
                .fileName(domain.toLowerCase(Locale.ROOT) + ".xpt").findings(List.of(finding))
                .build();
    }


    private static ValidationFinding finding(String coreId, FindingKind kind)
    {
        return ValidationFinding.builder().source("cumba.core").ruleId(coreId).kind(kind)
                .severity(Severity.ERROR).message("boom").variableNames(List.of("AESEV"))
                .location(ValidationFindingLocation.builder().dataset("AE")
                        .variableNames(List.of("AESEV")).build())
                .rows(RowFindingSlab.builder(1).addRow(0, new String[]
                {
                        "SEVERE"
                }).build()).build();
    }


    private static Rule rule(String coreId)
    {
        Rule r = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        r.setCore(core);
        return r;
    }


    @Test
    void skippedStatusIsNotClaimedWhenTheSkipEntryHasNoCoreId()
    {
        // A synthetic rule without an identifier yields a null-coreId skip entry; it must not
        // poison the lookup for the rules that do have one.
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(
                        List.of(SkippedRuleEntry.builder().dataset("AE").reason("no id").build()))
                .executedCoreIds(List.of()).build();

        List<Map<String, Object>> rows = rulesReport(report, rule(CORE_001));
        assertEquals("SUCCESS", statusOf(rows, CORE_001));
        assertFalse(rows.isEmpty());
        assertTrue(rows.stream().allMatch(r -> r.get(STATUS) != null));
    }

}
