package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 of {@code PLAN-scope-variables-guard-migration}: {@link RuleRunner#execute} applies the
 * {@code Requirements.Variables} filter at runtime. A rule whose {@code All} names a column absent
 * from the dataset (or whose {@code None} names one present) is reported
 * {@link RuleExecutionStatus#SKIPPED} with the responsible variable in the message, rather than
 * silently producing no finding. A leading {@code --} in an entry resolves against the dataset's
 * domain prefix.
 */
class RuleRunnerVariableScopeTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }


    /**
     * A trivial single-leaf rule ({@code AESTDY exists}) carrying the given variable requirement.
     */
    private static Rule ruleWithVarScope(List<String> all, List<String> none)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-VARSCOPE");
        rule.setCore(core);
        rule.setCheck(expr("var_exists(\"AESTDY\")"));
        VariableRequirement vr = new VariableRequirement();
        vr.setAll(all);
        vr.setNone(none);
        Requirements req = new Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        return rule;
    }


    private static IDataTable aeTable(String... columns)
    {
        RealTables mt = RealTables.of("AE");
        for (String c : columns)
        {
            mt.str(c, "x");
        }
        return mt.build();
    }


    /**
     * PACK I E1 (PI-C4, ECT-Q5 ⓐ in the scope gate): a typed requirement on a column that holds no
     * value in any row is satisfied — the column has no type — so the rule EXECUTES; the valued
     * Char column under {@code :N} keeps the skip and its reason.
     */
    @Test
    void typedRequirementOnABlankColumn_ruleRuns()
    {
        Rule rule = ruleWithVarScope(List.of("RPRFDY:N"), null);
        IDataTable blank = RealTables.of("TP").str("USUBJID", "S1", "S2").str("AESTDY", "1", "2")
                .str("RPRFDY", "", "").build();
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, blank);
        assertNotEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "a blank column has no type to contradict RPRFDY:N: " + result.getStatusMessage());
        IDataTable valued = RealTables.of("TP").str("USUBJID", "S1", "S2").str("AESTDY", "1", "2")
                .str("RPRFDY", "", "7").build();
        RuleExecutionResult skipped = RuleRunnerCalls.execute(rule, valued);
        assertEquals(RuleExecutionStatus.SKIPPED, skipped.getStatus());
        assertTrue(skipped.getStatusMessage().contains("required to be Numeric but is Character"),
                skipped.getStatusMessage());
    }


    @Test
    void includeVariablePresent_ruleRuns()
    {
        Rule rule = ruleWithVarScope(List.of("AESTDTC"), null);
        IDataTable table = aeTable("USUBJID", "AESTDY", "AESTDTC");
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        assertNotEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "rule must run when the All variable is present");
    }


    @Test
    void includeVariableAbsent_ruleSkipped()
    {
        Rule rule = ruleWithVarScope(List.of("AESTDTC"), null);
        IDataTable table = aeTable("USUBJID", "AESTDY"); // no AESTDTC
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "rule must be skipped when a required All variable is absent");
        assertTrue(result.getStatusMessage().contains("AESTDTC"),
                "skip message names the responsible variable: " + result.getStatusMessage());
    }


    @Test
    void genericDashInclude_resolvedAndPresent_ruleRuns()
    {
        // --STDTC resolves to AESTDTC on AE.
        Rule rule = ruleWithVarScope(List.of("--STDTC"), null);
        IDataTable table = aeTable("USUBJID", "AESTDY", "AESTDTC");
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table, null, "AE");
        assertNotEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "--STDTC resolves to AESTDTC, which is present");
    }


    @Test
    void genericDashInclude_resolvedAndAbsent_ruleSkipped()
    {
        Rule rule = ruleWithVarScope(List.of("--STDTC"), null);
        IDataTable table = aeTable("USUBJID", "AESTDY"); // no AESTDTC
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table, null, "AE");
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "--STDTC resolves to AESTDTC, which is absent");
    }


    @Test
    void excludeVariablePresent_ruleSkipped()
    {
        Rule rule = ruleWithVarScope(null, List.of("POOLID"));
        IDataTable table = aeTable("USUBJID", "AESTDY", "POOLID");
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "rule must be skipped when a None variable is present");
        assertTrue(result.getStatusMessage().contains("POOLID"),
                "skip message names the responsible variable: " + result.getStatusMessage());
    }


    @Test
    void noVariableScope_ruleRuns()
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-NOSCOPE");
        rule.setCore(core);
        rule.setCheck(expr("var_exists(\"AESTDY\")"));
        IDataTable table = aeTable("USUBJID", "AESTDY");
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        assertNotEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "a rule without Requirements.Variables is never skipped by the variable gate");
    }
}
