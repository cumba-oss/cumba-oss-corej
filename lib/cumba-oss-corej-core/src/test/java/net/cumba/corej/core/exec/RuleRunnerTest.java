package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RuleRunnerTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }


    @Test
    void testExecute_simpleRule_withViolations()
    {
        // Rule: SEX must be in {M, F} — violations are rows where SEX is not M or F
        IDataTable table = MockTable.of().col("USUBJID", "SUBJ01", "SUBJ02", "SUBJ03")
                .col("SEX", "M", "U", "F").build();

        Rule rule = buildRule("CDISC-CG0176", "SEX not in codelist",
                new CheckConditionAll(List.of(expr("SEX not in [\"M\", \"F\"]"))),
                List.of("USUBJID", "SEX"));

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);

        assertEquals("CDISC-CG0176", result.getRuleId());
        assertEquals("SEX not in codelist", result.getMessage());
        assertEquals(3, result.getTotalRows());
        assertTrue(result.hasViolations());
        assertEquals(1, result.getViolationCount());

        Violation v = result.getViolations().get(0);
        assertEquals(1, v.getRow()); // 0-based row index
        assertEquals(2, v.getRowNumber()); // 1-based
        assertEquals("SUBJ02", v.getValues().get("USUBJID"));
        assertEquals("U", v.getValues().get("SEX"));
    }


    @Test
    void testExecute_noViolations()
    {
        IDataTable table = MockTable.of().col("SEX", "M", "F").build();

        Rule rule = buildRule("CDISC-CG0208", "SEX must be M or F",
                new CheckConditionAll(List.of(expr("SEX not in [\"M\", \"F\"]"))), List.of("SEX"));

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);

        assertFalse(result.hasViolations());
        assertEquals(0, result.getViolationCount());
        assertEquals(2, result.getTotalRows());
    }


    @Test
    void testExecute_compositeCheck()
    {
        // all(non_empty(AETERM), equal_to(DOMAIN, "AE"))
        IDataTable table = MockTable.of().col("DOMAIN", "AE", "AE", "DM")
                .col("AETERM", "Headache", "", "N/A").build();

        CheckConditionAll all = new CheckConditionAll(
                List.of(expr("not empty(AETERM)"), expr("DOMAIN == \"AE\"")));
        Rule rule = buildRule("CDISC-CG0299", "Non-empty AETERM in AE domain", all,
                List.of("AETERM"));

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);

        assertEquals(1, result.getViolationCount());
        assertEquals(0, result.getViolations().get(0).getRow()); // row 0: AE + Headache
    }


    @Test
    void testExecute_missingColumn_noFalseViolations()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1", "S2").build();

        Rule rule = buildRule("CDISC-CG0101", "test",
                new CheckConditionAll(List.of(expr("not empty(NONEXISTENT)"))), List.of());

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        assertFalse(result.hasViolations());
    }

    // -----------------------------------------------------------------------
    // Integration tests: rules with bindings (declared operations until runbook W8)
    // -----------------------------------------------------------------------


    @Test
    void testExecute_withOperations_variableInValue()
    {
        // Binding: distinct USUBJID from DM → $dm_usubjid = [S01, S02]
        // Check: USUBJID is_not_contained_by $dm_usubjid
        // AE table has S01, S03 → S03 is a violation
        IDataTable aeTable = MockTable.of().col("USUBJID", "S01", "S03", "S01").build();
        IDataTable dmTable = MockTable.of().col("USUBJID", "S01", "S02", "S01").build();

        // (distinct is a registry function since runbook W7: the binding is a compiled one,
        // loaded through the production loader.)
        Rule rule = loadedRule("CORE-OP-001", "$dm_usubjid", "distinct(USUBJID, domain=\"DM\")",
                "USUBJID not in $dm_usubjid", List.of("USUBJID"));

        DatasetResolver resolver = name -> "DM".equals(name) ? dmTable : null;
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, aeTable, resolver);

        assertEquals(1, result.getViolationCount());
        assertEquals(1, result.getViolations().get(0).getRow()); // row 1: S03
        assertEquals("S03", result.getViolations().get(0).getValues().get("USUBJID"));
    }


    @Test
    void testExecute_withOperations_variableInName()
    {
        // Binding: record_count() → $RECORD_COUNT = 2
        // Check: $RECORD_COUNT greater_than 3 → no row is a violation (2 > 3 is false). (The
        // vehicle was a declared variable_count operation until runbook W8.)
        IDataTable table = MockTable.of().col("A", "1", "2").col("B", "3", "4").build();

        Rule rule = loadedRule("CORE-OP-002", "$RECORD_COUNT", "record_count()",
                "$RECORD_COUNT > 3", List.of());

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);

        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertFalse(result.hasViolations());
    }


    @Test
    void testExecute_withOperations_variableInName_allViolations()
    {
        // distinct(A) = ["1", "2"], check: "2" in $VALUES → true → violation
        // Dataset sensitivity → non-row-based → reports a single dataset-level violation. (The
        // vehicle was variable_count until wave 4b, then record_count until runbook W6, made each
        // a registry function.)
        IDataTable table = MockTable.of().col("A", "1", "2").col("B", "3", "4").col("C", "5", "6")
                .col("D", "7", "8").col("E", "9", "10").build();

        Rule rule = loadedRule("CORE-OP-003", "$VALUES", "distinct(A)", "\"2\" in $VALUES",
                List.of());

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);

        assertTrue(result.hasViolations());
        assertEquals(1, result.getViolationCount()); // dataset-level: single violation
    }


    @Test
    void testExecute_noOperations_sameAsBefore()
    {
        // Verify the no-operations path works unchanged
        IDataTable table = MockTable.of().col("SEX", "M", "X").build();

        Rule rule = buildRule("CDISC-CG0102", "Bad SEX",
                new CheckConditionAll(List.of(expr("SEX not in [\"M\", \"F\"]"))), List.of("SEX"));

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        assertEquals(1, result.getViolationCount());
        assertEquals("X", result.getViolations().get(0).getValues().get("SEX"));
    }

    // -----------------------------------------------------------------------
    // Integration test: grouped operation (CDISC-CG0148 pattern) — retired with the carrier
    // -----------------------------------------------------------------------

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------


    /** A one-binding rule through the production loader (a compiled binding, since W7). */
    private static Rule loadedRule(String coreId, String binding, String expression, String check,
            List<String> outputVars)
    {
        try
        {
            StringBuilder vars = new StringBuilder();
            for (String v : outputVars)
            {
                vars.append(vars.length() == 0 ? "" : ",").append('"').append(v).append('"');
            }
            String json = "{\"rules\":{\"" + coreId + "\":{\"Core\":{\"Id\":\"" + coreId + "\"},"
                    + "\"Bindings\":[{\"name\":\"" + binding + "\",\"expression\":\""
                    + expression.replace("\"", "\\\"") + "\"}]," + "\"Check\":{\"expression\":\""
                    + check.replace("\"", "\\\"") + "\"},"
                    + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[" + vars + "]}}}}";
            Rule rule = net.cumba.corej.core.RulePackageLoader.loadFromString(json).getRules()
                    .get(coreId);
            assertNull(rule.getLoadError(), rule.getLoadError());
            return rule;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + expression, e);
        }
    }


    private static Rule buildRule(String coreId, String message, CheckConditionAll check,
            List<String> outputVars)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage(message);
        outcome.setOutputVariables(outputVars);
        rule.setOutcome(outcome);
        rule.setCheck(check);
        net.cumba.corej.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }

}
