package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-message-folded-corpus-alignment} row 5 (Q6, P4): the report prints
 * {@code Outcome.Message_Folded} for a finding whose unit says the fold or the collapse produced it
 * ({@code Violation.Unit.Dataset#folded()}), and the execution's own message for every other
 * dataset-unit finding of the same rule — a dataset-constant verdict of an opted-in rule is not a
 * fold. Before this plan the builder keyed on the DATASET unit alone, so every broadcast TRUE of an
 * opted-in rule printed {@code Message_Folded}.
 */
class FoldedUnitMessageTest
{

    private static Rule rule()
    {
        Rule r = new Rule();
        r.setId("uuid-Q6");
        RuleCore core = new RuleCore();
        core.setId("Q6-REPORT");
        r.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage("per-row message");
        outcome.setMessageFolded("folded message");
        r.setOutcome(outcome);
        return r;
    }


    private static String reported(Violation.Unit unit)
    {
        Violation v = new Violation(0, Map.of(), null, null, Map.of(), null, unit, null, List.of());
        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("uuid-Q6")
                .message("per-row message").violations(List.of(v)).totalRows(3)
                .status(RuleExecutionStatus.EXECUTED).build();
        ValidationReport report = new ValidationReportBuilder().add("AE", "ae.xpt", rule(), result)
                .build();
        List<ValidationFinding> findings = report.getMembers().get(0).getFindings();
        assertEquals(1, findings.size(), "one finding expected, saw " + findings);
        return findings.get(0).getMessage();
    }


    @Test
    @DisplayName("a plain DATASET unit of an opted-in rule prints the execution's message")
    void aDatasetConstantVerdictPrintsMessage()
    {
        assertEquals("per-row message", reported(Violation.Unit.DATASET));
    }


    @Test
    @DisplayName("the FOLDED dataset unit prints Message_Folded")
    void aFoldedUnitPrintsMessageFolded()
    {
        assertEquals("folded message", reported(Violation.Unit.DATASET_FOLDED));
    }


    /** A level-keyed opted-in rule, run end to end: the runner stamps, the report resolves. */
    private static String reportedEndToEnd(String errorCheck, IDataTable table) throws Exception
    {
        String json = "{\"rules\":{\"T\":{\"Core\":{\"Id\":\"Q6-LEVELS\"},\"Sensitivity\":\"Record\","
                + "\"Severity\":\"Error\"," + "\"Check\":{\"ERROR\":{\"expression\":\"" + errorCheck
                + "\"}," + "\"WARNING\":{\"expression\":\"empty(AEBODSYS)\"}},"
                + "\"Outcome\":{\"Message\":\"per-row message\",\"Message_Folded\":\"folded message\","
                + "\"Output_Variables\":[\"AEBDSYCD\"]}}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("T");
        assertEquals(null, rule.getLoadError(), rule.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table, _ -> null, "AE", null,
                null, null, Integer.MAX_VALUE, null, null, null, Set.of(), Set.of());
        assertEquals(1, result.getViolationCount(), "one dataset finding: " + result);
        ValidationReport report = new ValidationReportBuilder().add("AE", "ae.xpt", rule, result)
                .build();
        List<ValidationFinding> findings = report.getMembers().get(0).getFindings();
        assertEquals(1, findings.size(), findings.toString());
        return findings.get(0).getMessage();
    }


    @Test
    @DisplayName("LOW-5 — a levelled rule's absent-decided broadcast fold prints Message_Folded end to end")
    void aLevelledAbsentDecidedFoldPrintsMessageFolded() throws Exception
    {
        // AEBDSYCD absent: the ERROR level's fold decides TRUE by the absence (DATASET_FOLDED);
        // AEBODSYS populated: the WARNING level fires nowhere. The report reads the unit's flag —
        // `folded ? DATASET_FOLDED : DATASET` sabotaged to DATASET prints "per-row message".
        IDataTable noCode = MockTable.of().name("AE").col("USUBJID", "01", "02")
                .col("AEBODSYS", "Cardiac", "Renal").build();
        assertEquals("folded message", reportedEndToEnd("empty(AEBDSYCD)", noCode));
    }


    @Test
    @DisplayName("LOW-5 — a levelled rule's dataset-constant verdict prints Message end to end")
    void aLevelledDatasetConstantVerdictPrintsMessage() throws Exception
    {
        IDataTable full = MockTable.of().name("AE").col("USUBJID", "01", "02")
                .col("AEBODSYS", "Cardiac", "Renal").col("AEBDSYCD", "1", "2").build();
        assertEquals("per-row message",
                reportedEndToEnd("empty(AEBDSYCD) or record_count() > 0", full));
    }
}
