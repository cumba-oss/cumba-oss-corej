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
 * <b>D65 / D65a</b> ({@code PLAN-typed-expression-engine.md}, phase 5b) — {@code Rules_Report}
 * keeps <b>one entry per CORE id</b> and gains the three execution counts ({@code executed} /
 * {@code skipped} / {@code errored}, each per rule × dataset), and per-binding / per-dataset
 * engine-error messages <b>accumulate</b> rather than overwrite.
 *
 * <p>
 * ⛔ The counts are <b>v2-only</b>: v1 is a FROZEN published consumer schema (owner ruling
 * 2026-08-11), and D57 rules that per-execution detail lands in v2 or a new section, never by
 * widening v1. {@link #v1DocumentNeverCarriesTheCountKeys()} and
 * {@link #aRoundTrippedV2DocumentReprojectsToV1WithoutTheCounts()} are the pins.
 * </p>
 */
class RulesReportExecutionCountsTest
{

    private static final String CORE_001 = "CORE-001";

    private static final String RULES_REPORT = "Rules_Report";

    // ------------------------------------------------------------------
    // The three counts (D65)
    // ------------------------------------------------------------------

    @Test
    void executedCountsOncePerDatasetExecution()
    {
        // Ran cleanly on two datasets: no finding, but two entries in the executed multiset.
        ValidationReport report = ValidationReport.builder().members(List.of())
                .executedCoreIds(List.of(CORE_001, CORE_001)).build();

        Map<String, Object> row = v2Row(report, CORE_001, rule(CORE_001));
        assertEquals("SUCCESS", row.get("status"));
        assertEquals(2, row.get("executed"));
        assertEquals(0, row.get("skipped"));
        assertEquals(0, row.get("errored"));
    }


    @Test
    void anIssueReportingExecutionStillCountsAsExecuted()
    {
        // "Executed successfully" includes finding issues — the rule ran fine, the data did not.
        ValidationReport report = ValidationReport.builder()
                .members(List.of(member("AE",
                        finding(CORE_001, FindingKind.RULE_VIOLATION, "AETERM must not be null"))))
                .executedCoreIds(List.of(CORE_001, CORE_001)).build();

        Map<String, Object> row = v2Row(report, CORE_001, rule(CORE_001));
        assertEquals("ISSUE_REPORTED", row.get("status"));
        assertEquals(2, row.get("executed"));
        assertEquals(0, row.get("errored"));
    }


    @Test
    void erroredCountsOncePerErroredDatasetAndIsSubtractedFromExecuted()
    {
        // Three datasets: clean on DM, errored on AE and VS. The executed multiset carries all
        // three (an errored execution is still an execution), so executed = 3 - 2.
        ValidationReport report = ValidationReport.builder()
                .members(List.of(member("AE", error(CORE_001, "boom on AE")),
                        member("VS", error(CORE_001, "boom on VS"))))
                .executedCoreIds(List.of(CORE_001, CORE_001, CORE_001)).build();

        Map<String, Object> row = v2Row(report, CORE_001, rule(CORE_001));
        assertEquals("EXECUTION_ERROR", row.get("status"));
        assertEquals(1, row.get("executed"));
        assertEquals(2, row.get("errored"));
    }


    @Test
    void skippedCountsEverySkipEntryWhateverTheChannel()
    {
        // Two skip entries (the builder feeds both the execution-time and the generation-time
        // channel into the same list) plus one clean execution ⇒ SUCCESS with skipped=2.
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(List.of(skip(CORE_001, "AE"), skip(CORE_001, "VS")))
                .executedCoreIds(List.of(CORE_001)).build();

        Map<String, Object> row = v2Row(report, CORE_001, rule(CORE_001));
        assertEquals("SUCCESS", row.get("status"));
        assertEquals(1, row.get("executed"));
        assertEquals(2, row.get("skipped"));
        assertEquals(0, row.get("errored"));
    }


    @Test
    void aLoadParkedRuleErrorsOncePerTargetedDataset()
    {
        // SPEC §9's spec choice: "a load-parked rule counts as errored once per dataset it
        // targeted". The runner emits one ERROR result per (rule × dataset) for a Rule.loadError,
        // so the report carries one ENGINE_ERROR finding per targeted dataset — same message.
        ValidationReport report = ValidationReport.builder()
                .members(List.of(member("AE", error(CORE_001, "no native expression form")),
                        member("DM", error(CORE_001, "no native expression form"))))
                .executedCoreIds(List.of(CORE_001, CORE_001)).build();

        Map<String, Object> row = v2Row(report, CORE_001, rule(CORE_001));
        assertEquals(2, row.get("errored"));
        assertEquals(0, row.get("executed"));
        // D65a: the identical message is deduplicated, not repeated.
        assertEquals("no native expression form", row.get("message"));
    }


    @Test
    void theSyntheticDatasetLoadErrorClampsExecutedAtZero()
    {
        // CUMBA-DATASET-LOAD findings have no execution record behind them; the orphan row must
        // report errored without going negative on executed.
        ValidationReport report = ValidationReport.builder()
                .members(
                        List.of(member("LB",
                                error(ValidationReportBuilder.DATASET_LOAD_ERROR_RULE_ID,
                                        "could not open lb.xpt"))))
                .executedCoreIds(List.of()).build();

        Map<String, Object> row = v2Row(report, ValidationReportBuilder.DATASET_LOAD_ERROR_RULE_ID);
        assertEquals(0, row.get("executed"));
        assertEquals(1, row.get("errored"));
    }

    // ------------------------------------------------------------------
    // Message accumulation (D65a / SPEC §9)
    // ------------------------------------------------------------------


    @Test
    void distinctErrorMessagesAccumulateInsteadOfOverwriting()
    {
        // D41: "the report must say WHICH variable errored." Pre-D65a the second engine error
        // overwrote the first, so three datasets erroring on different variables named only the
        // last one. They now accumulate, distinct, first-seen order.
        ValidationReport report = ValidationReport.builder()
                .members(List.of(member("AE", error(CORE_001, "AESEV expects Num, found Char")),
                        member("VS", error(CORE_001, "VSPOS expects Num, found Char"))))
                .executedCoreIds(List.of(CORE_001, CORE_001)).build();

        Map<String, Object> row = v2Row(report, CORE_001, rule(CORE_001));
        assertEquals("AESEV expects Num, found Char; VSPOS expects Num, found Char",
                row.get("message"));
        assertEquals(2, row.get("errored"));
    }


    @Test
    void engineErrorMessageWinsOverAnEarlierIssueMessage()
    {
        // Status precedence is unchanged: engine error wins over issue reported, and the row's
        // message follows the status.
        ValidationReport report = ValidationReport.builder()
                .members(List.of(
                        member("AE", finding(CORE_001, FindingKind.RULE_VIOLATION, "AETERM null")),
                        member("VS", error(CORE_001, "boom"))))
                .executedCoreIds(List.of(CORE_001, CORE_001)).build();

        Map<String, Object> row = v2Row(report, CORE_001, rule(CORE_001));
        assertEquals("EXECUTION_ERROR", row.get("status"));
        assertEquals("boom", row.get("message"));
    }


    @Test
    void oneRowPerCoreIdHoweverManyDatasetsContributed()
    {
        ValidationReport report = ValidationReport.builder()
                .members(List.of(member("AE", error(CORE_001, "boom on AE")),
                        member("VS", error(CORE_001, "boom on VS")),
                        member("DM", finding(CORE_001, FindingKind.RULE_VIOLATION, "issue"))))
                .executedCoreIds(List.of(CORE_001, CORE_001, CORE_001)).build();

        List<Map<String, Object>> rows = v2Rows(report, rule(CORE_001));
        assertEquals(1, rows.size(), "Rules_Report keeps ONE entry per coreId (D65)");
    }

    // ------------------------------------------------------------------
    // v1 stays frozen (D57)
    // ------------------------------------------------------------------


    @Test
    void v1DocumentNeverCarriesTheCountKeys()
    {
        ValidationReport report = ValidationReport.builder().members(List.of())
                .skippedRules(List.of(skip(CORE_001, "AE"))).executedCoreIds(List.of(CORE_001))
                .build();

        Map<String, Object> v1 = new ReportAssembler().report(report).rules(List.of(rule(CORE_001)))
                .sections().toExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) v1.get(RULES_REPORT);
        for (Map<String, Object> row : rows)
        {
            assertFalse(row.containsKey("executed"), "v1 is frozen — no executed key");
            assertFalse(row.containsKey("skipped"), "v1 is frozen — no skipped key");
            assertFalse(row.containsKey("errored"), "v1 is frozen — no errored key");
        }
    }


    @Test
    void aRoundTrippedV2DocumentReprojectsToV1WithoutTheCounts()
    {
        // The round-trip hazard the tolerance key already guards against: a v2 document read back
        // through fromExportDocument carries the count keys, and a re-projection to v1 must strip
        // them rather than smuggle them into the frozen schema.
        ValidationReport report = ValidationReport.builder().members(List.of())
                .executedCoreIds(List.of(CORE_001)).build();
        Map<String, Object> v2 = new ReportAssembler().report(report).rules(List.of(rule(CORE_001)))
                .sections().toCombinedExportDocument();

        Map<String, Object> v1Again = ReportSections.fromExportDocument(v2).toExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) v1Again.get(RULES_REPORT);
        assertEquals(1, rows.size());
        assertFalse(rows.get(0).containsKey("executed"));
        assertFalse(rows.get(0).containsKey("skipped"));
        assertFalse(rows.get(0).containsKey("errored"));
        assertTrue(rows.get(0).containsKey("status"), "the frozen v1 fields survive the strip");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------


    private static List<Map<String, Object>> v2Rows(ValidationReport report, Rule... rules)
    {
        Map<String, Object> v2 = new ReportAssembler().report(report).rules(List.of(rules))
                .sections().toCombinedExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) v2.get(RULES_REPORT);
        return rows;
    }


    private static Map<String, Object> v2Row(ValidationReport report, String coreId, Rule... rules)
    {
        for (Map<String, Object> row : v2Rows(report, rules))
        {
            if (coreId.equals(row.get("core_id")))
            {
                return row;
            }
        }
        throw new AssertionError("no Rules_Report row for " + coreId);
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


    private static ValidationFinding finding(String coreId, FindingKind kind, String message)
    {
        return ValidationFinding.builder().source("cumba.core").ruleId(coreId).kind(kind)
                .severity(Severity.ERROR).message(message).variableNames(List.of("AESEV"))
                .location(ValidationFindingLocation.builder().dataset("AE")
                        .variableNames(List.of("AESEV")).build())
                .rows(RowFindingSlab.builder(1).addRow(0, new String[]
                {
                        "SEVERE"
                }).build()).build();
    }


    private static ValidationFinding error(String coreId, String message)
    {
        return ValidationFinding.builder().source("cumba.core").ruleId(coreId)
                .kind(FindingKind.ENGINE_ERROR).severity(Severity.ERROR).message(message)
                .variableNames(List.of()).location(ValidationFindingLocation.EMPTY)
                .rows(RowFindingSlab.EMPTY).build();
    }


    private static Rule rule(String coreId)
    {
        Rule r = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        r.setCore(core);
        return r;
    }

}
