package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * <b>D29 / D66a</b> ({@code PLAN-typed-expression-engine.md}, phase 5b) — a Group-sensitivity
 * finding is <b>located by its group variables</b>: the engine stamps the block's grouping key on
 * every grouped violation, the report builder routes it into the finding's key channel with key
 * source {@code GROUP}, and the <b>v2</b> report keys the group finding by the group variables.
 *
 * <p>
 * ⛔ <b>v1 stays anchored at the block's first flagged row</b> (D66: <i>"v1 anchors a group finding
 * at its first flagged row, v2 keys it by the group variables"</i> — a deliberate v1 trait, not an
 * oversight). {@link #v1IssueDetailsKeepsTheAnchorRowAndGainsNoKeys()} pins it.
 * </p>
 */
class GroupFindingKeyTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }


    /** Group rule flagging {@code AEOUT == "FATAL"} rows, grouped by {@code USUBJID}. */
    private static Rule fatalRule()
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-G2");
        rule.setCore(core);
        rule.setSensitivity(Sensitivity.GROUP);
        rule.setGroupingVariables(List.of("USUBJID"));
        rule.setCheckExpr(CheckExpressionParser.parse("AEOUT == \"FATAL\""));
        rule.setCheck(expr("AEOUT == FATAL"));
        Outcome outcome = new Outcome();
        outcome.setOutputVariables(List.of("AEDECOD"));
        rule.setOutcome(outcome);
        return rule;
    }


    private static IDataTable ae(String[] usubjid, String[] aeout, String[] aedecod)
    {
        OverlayDataTable t = OverlayDataTable.empty("AE", "AE", usubjid.length);
        t.addColumn("USUBJID", DataValueType.STRING, "USUBJID");
        t.addColumn("AEOUT", DataValueType.STRING, "AEOUT");
        t.addColumn("AEDECOD", DataValueType.STRING, "AEDECOD");
        for (int r = 0; r < usubjid.length; r++)
        {
            t.setValue(r, "USUBJID", usubjid[r]);
            t.setValue(r, "AEOUT", aeout[r]);
            t.setValue(r, "AEDECOD", aedecod[r]);
        }
        return t;
    }


    private static ReportSections run()
    {
        // Subjects A (rows 0-1, flagged at row 1) and B (rows 2-3, flagged at row 2).
        IDataTable table = ae(new String[]
        {
                "A", "A", "B", "B"
        }, new String[]
        {
                "OTHER", "FATAL", "FATAL", "OTHER"
        }, new String[]
        {
                "X", "HEADACHE", "RASH", "Y"
        });
        Rule rule = fatalRule();
        RuleExecutionResult result = RuleRunner.execute(rule, table);
        assertEquals(2, result.getViolations().size(), "one violation per failing group");
        net.cumba.datatable.report.ValidationReport report = new ValidationReportBuilder()
                .add("AE", "ae.xpt", rule, result).build();
        return new ReportAssembler().report(report).rules(List.of(rule)).sections();
    }


    @Test
    void v2FindingIsKeyedByTheGroupVariables()
    {
        Map<String, Object> v2 = run().toCombinedExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) v2.get("Findings");
        assertEquals(1, findings.size(), "one finding, two group rows");

        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) findings.get(0).get("location");
        assertEquals(List.of("USUBJID"), location.get("keyVariables"),
                "D29: the group variables ARE the finding's key variables");
        assertEquals("GROUP", location.get("keySource"),
                "a group-keyed finding is distinguishable from a record-keyed one");

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) findings.get(0).get("rows");
        assertEquals(2, rows.size());
        assertEquals(Map.of("USUBJID", "A"), rows.get(0).get("keys"),
                "each row carries its block's group key");
        assertEquals(Map.of("USUBJID", "B"), rows.get(1).get("keys"));
    }


    @Test
    void v1IssueDetailsKeepsTheAnchorRowAndGainsNoKeys()
    {
        Map<String, Object> v1 = run().toExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> details = (List<Map<String, Object>>) v1.get("Issue_Details");
        assertEquals(2, details.size(), "v1: one anchored row per failing group, as before");
        // The anchors are the blocks' first flagged rows (1-based rows 2 and 3), untouched.
        assertEquals(2, details.get(0).get("row"));
        assertEquals(3, details.get(1).get("row"));
        for (Map<String, Object> row : details)
        {
            assertFalse(row.containsKey("keys"), "v1 is frozen — no key field appears");
            assertNotNull(row.get("USUBJID"));
        }
    }


    @Test
    void aSingleGroupFallbackFindingCarriesNoKeyChannel()
    {
        // Grouping column absent -> whole dataset is one group; there is no group key to name,
        // and the finding must not invent one (D39d's "derive, don't invent", one level up).
        IDataTable table = ae(new String[]
        {
                "A", "B"
        }, new String[]
        {
                "FATAL", "FATAL"
        }, new String[]
        {
                "NAUSEA", "RASH"
        });
        Rule rule = fatalRule();
        rule.setGroupingVariables(List.of("ZZGRP"));
        RuleExecutionResult result = RuleRunner.execute(rule, table);
        net.cumba.datatable.report.ValidationReport report = new ValidationReportBuilder()
                .add("AE", "ae.xpt", rule, result).build();
        Map<String, Object> v2 = new ReportAssembler().report(report).rules(List.of(rule))
                .sections().toCombinedExportDocument();

        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) v2.get("Findings");
        assertEquals(1, findings.size());
        @SuppressWarnings("unchecked")
        Map<String, Object> location = (Map<String, Object>) findings.get(0).get("location");
        assertFalse(location.containsKey("keyVariables"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) findings.get(0).get("rows");
        assertTrue(rows.stream().noneMatch(r -> r.containsKey("keys")));
    }

}
