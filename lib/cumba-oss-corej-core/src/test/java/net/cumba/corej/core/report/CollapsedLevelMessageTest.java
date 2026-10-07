package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PACK 4 review MED-3 ({@code PLAN-present-empty-column-fold} §4.3, per-path table): a level-keyed
 * rule whose claiming level declares its OWN {@code Message} still reports its collapsed finding
 * under {@code Outcome.Message_Folded} — the level's message is its per-row message; the report
 * resolves a folded unit before the level's message. End to end: the runner collapses, the report
 * builder prints.
 */
class CollapsedLevelMessageTest
{

    private static final String FOLDED = "AEBDSYCD is not populated on any record of the dataset";

    @Test
    @DisplayName("a collapsed finding of a level with its own Message prints Message_Folded")
    void aCollapsedLevelFindingPrintsMessageFolded() throws Exception
    {
        String json = "{\"rules\":{\"T\":{\"Core\":{\"Id\":\"C7-LEVEL-MSG\"},\"Sensitivity\":\"Record\","
                + "\"Severity\":\"Error\","
                + "\"Check\":{\"ERROR\":{\"expression\":\"empty(AEBDSYCD)\",\"Message\":\"level message\"},"
                + "\"WARNING\":{\"expression\":\"empty(AEBODSYS)\"}},"
                + "\"Outcome\":{\"Message\":\"per-row message\",\"Message_Folded\":\"" + FOLDED
                + "\",\"Output_Variables\":[\"AEBDSYCD\"]}}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("T");
        assertNull(rule.getLoadError(), rule.getLoadError());
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "01", "02").col("AEBODSYS", "", "")
                .col("AEBDSYCD", "", "").build();
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, t, _ -> null, "AE", null, null,
                null, Integer.MAX_VALUE, null, null, null, Set.of(), Set.of());
        assertEquals(1, result.getViolationCount(), "one collapsed finding: " + result);
        ValidationReport report = new ValidationReportBuilder().add("AE", "ae.xpt", rule, result)
                .build();
        List<ValidationFinding> findings = report.getMembers().get(0).getFindings();
        assertEquals(1, findings.size(), findings.toString());
        assertEquals(FOLDED, findings.get(0).getMessage(),
                "the folded unit's message, not the claiming level's own 'level message'");
    }
}
