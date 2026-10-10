package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.run.DatasetExecutionSummary;
import net.cumba.corej.core.run.StudyValidationResult;
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
 * {@code PLAN-report-v2-rule-metadata} §2.2 — the <b>summary-only</b> entry points
 * {@link ReportAssembler#rulesReport()} and {@link StudyValidationResult#rulesReport()} return
 * exactly the {@code Rules_Report} rows that {@code sections()} assembles (RM8: the same row maps),
 * without building the combined findings or the issue details.
 *
 * <p>
 * One fixture run carries one case per edge of the status logic: an engine error beside an issue
 * (error wins), a rule skipped everywhere ({@code SKIPPED}), a rule skipped on one dataset and
 * executed on another ({@code SUCCESS}), a finding on a member with an empty domain (ignored by the
 * status walk), an orphan finding (a rule the run did not supply), and a supplied rule without a
 * CORE id (no row). The expected statuses are asserted too, so the parity cannot hold vacuously
 * over rows that never exercised the edge.
 * </p>
 */
class RulesReportEntryPointTest
{

    private static final String ERROR_AND_ISSUE = "CORE-001";

    private static final String SKIPPED_EVERYWHERE = "CORE-002";

    private static final String PARTIALLY_SKIPPED = "CORE-003";

    private static final String EMPTY_DOMAIN_ONLY = "CORE-004";

    private static final String ORPHAN = "CORE-999";

    @Test
    void theAssemblerEntryPointEqualsTheSectionsRulesReportOnEveryEdge()
    {
        List<Map<String, Object>> fromSections = assembler().sections().rulesReport();
        List<Map<String, Object>> summaryOnly = assembler().rulesReport();

        assertEquals(fromSections, summaryOnly);
        assertEdgeStatuses(summaryOnly);
    }


    @Test
    void theEntryPointIsRepeatableOnOneAssembler()
    {
        // sections() calls the same method; neither call may consume state the other needs.
        ReportAssembler assembler = assembler();
        List<Map<String, Object>> first = assembler.rulesReport();
        assertEquals(first, assembler.sections().rulesReport());
        assertEquals(first, assembler.rulesReport());
    }


    @Test
    void theResultEntryPointEqualsTheResultSectionsWithTheSameWiring()
    {
        // StudyValidationResult wires private runtimes and the conformance; a consumer calling
        // rulesReport() must get the same rows as sections() — including the summed runtime.
        DatasetExecutionSummary ae = summary("AE", execution(ERROR_AND_ISSUE, 7),
                execution(PARTIALLY_SKIPPED, 5));
        DatasetExecutionSummary dm = summary("DM", execution(ERROR_AND_ISSUE, 4));
        ReportAssembler.Conformance send = ReportAssembler.Conformance.builder().standard("sendig")
                .version("3-1-1").build();
        StudyValidationResult result = new StudyValidationResult(report(), send, List.of(), rules(),
                0, 0.0, List.of(ae, dm), Map.of(), Set.of());

        List<Map<String, Object>> summaryOnly = result.rulesReport();

        assertEquals(result.sections().rulesReport(), summaryOnly);
        assertEdgeStatuses(summaryOnly);
        assertEquals(11L, rowOf(summaryOnly, ERROR_AND_ISSUE).get("runtime_ms"),
                "the runtimes are summed across datasets, as sections() wires them");
        assertEquals(5L, rowOf(summaryOnly, PARTIALLY_SKIPPED).get("runtime_ms"));
        assertEquals(-1L, rowOf(summaryOnly, SKIPPED_EVERYWHERE).get("runtime_ms"));
    }


    private static void assertEdgeStatuses(List<Map<String, Object>> rows)
    {
        assertEquals("EXECUTION_ERROR", rowOf(rows, ERROR_AND_ISSUE).get("status"));
        assertEquals("boom", rowOf(rows, ERROR_AND_ISSUE).get("message"));
        assertEquals("SKIPPED", rowOf(rows, SKIPPED_EVERYWHERE).get("status"));
        assertEquals("SUCCESS", rowOf(rows, PARTIALLY_SKIPPED).get("status"));
        assertEquals(1, rowOf(rows, PARTIALLY_SKIPPED).get("skipped"));
        assertEquals("SUCCESS", rowOf(rows, EMPTY_DOMAIN_ONLY).get("status"),
                "a finding on an empty-domain member does not count toward the status");
        Map<String, Object> orphan = rowOf(rows, ORPHAN);
        assertEquals("ISSUE_REPORTED", orphan.get("status"));
        assertFalse(orphan.containsKey("severity"), "an orphan row omits the severity");
        assertEquals("Warning", rowOf(rows, SKIPPED_EVERYWHERE).get("severity"));
        assertEquals(5, rows.size(), "four supplied rules with ids + one orphan; the null-id rule"
                + " contributes no row: " + rows);
    }


    private static ReportAssembler assembler()
    {
        return new ReportAssembler().report(report()).rules(rules())
                .ruleRuntimesMillis(Map.of(ERROR_AND_ISSUE, 12L))
                .conformance(ReportAssembler.Conformance.builder().standard("sdtmig").build());
    }


    private static ValidationReport report()
    {
        return ValidationReport.builder()
                .members(List.of(member("AE", issue(ERROR_AND_ISSUE), issue(ORPHAN)),
                        member("DM", error(ERROR_AND_ISSUE, "boom")),
                        member("", issue(EMPTY_DOMAIN_ONLY))))
                .skippedRules(List.of(skip(SKIPPED_EVERYWHERE, "AE"),
                        skip(SKIPPED_EVERYWHERE, "DM"), skip(PARTIALLY_SKIPPED, "DM")))
                .executedCoreIds(List.of(ERROR_AND_ISSUE, ERROR_AND_ISSUE, PARTIALLY_SKIPPED,
                        EMPTY_DOMAIN_ONLY, ORPHAN))
                .build();
    }


    private static List<Rule> rules()
    {
        Rule skipped = rule(SKIPPED_EVERYWHERE);
        skipped.setSeverity(Severity.WARNING);
        return List.of(rule(ERROR_AND_ISSUE), skipped, rule(PARTIALLY_SKIPPED),
                rule(EMPTY_DOMAIN_ONLY), rule(null));
    }


    private static Map<String, Object> rowOf(List<Map<String, Object>> rows, String coreId)
    {
        for (Map<String, Object> row : rows)
        {
            if (coreId.equals(row.get("core_id")))
            {
                return row;
            }
        }
        throw new AssertionError("no Rules_Report row for " + coreId);
    }


    private static DatasetExecutionSummary summary(String domain,
            DatasetExecutionSummary.RuleExecution... executions)
    {
        return new DatasetExecutionSummary(domain, domain.toLowerCase(Locale.ROOT) + ".xpt",
                executions.length, executions.length, 0, 20L, List.of(), List.of(executions));
    }


    private static DatasetExecutionSummary.RuleExecution execution(String coreId, long millis)
    {
        return new DatasetExecutionSummary.RuleExecution(coreId, coreId, "EXECUTED", 0, millis,
                null, null, null, null);
    }


    private static SkippedRuleEntry skip(String coreId, String dataset)
    {
        return SkippedRuleEntry.builder().coreId(coreId).dataset(dataset)
                .reason("scope did not match " + dataset).build();
    }


    private static ValidationReportMember member(String domain, ValidationFinding... findings)
    {
        return ValidationReportMember.builder().domain(domain)
                .fileName(domain.toLowerCase(Locale.ROOT) + ".xpt").findings(List.of(findings))
                .build();
    }


    private static ValidationFinding issue(String coreId)
    {
        return ValidationFinding.builder().source("cumba.core").ruleId(coreId)
                .kind(FindingKind.RULE_VIOLATION).severity(Severity.ERROR)
                .message("AESEV is invalid").variableNames(List.of("AESEV"))
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
        if (coreId != null)
        {
            RuleCore core = new RuleCore();
            core.setId(coreId);
            r.setCore(core);
        }
        return r;
    }

}
