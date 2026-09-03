package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
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

    /**
     * A trivial single-leaf rule ({@code AESTDY exists}) carrying the given variable requirement.
     */
    private static Rule ruleWithVarScope(List<String> all, List<String> none)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-VARSCOPE");
        rule.setCore(core);
        rule.setCheck(CheckConditionLeaf.builder().name("AESTDY").operator("var_exists").build());
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
        MockTable mt = MockTable.of().name("AE");
        for (String c : columns)
        {
            mt.col(c, "x");
        }
        return mt.build();
    }


    @Test
    void includeVariablePresent_ruleRuns()
    {
        Rule rule = ruleWithVarScope(List.of("AESTDTC"), null);
        IDataTable table = aeTable("USUBJID", "AESTDY", "AESTDTC");
        RuleExecutionResult result = RuleRunner.execute(rule, table);
        assertNotEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "rule must run when the All variable is present");
    }


    @Test
    void includeVariableAbsent_ruleSkipped()
    {
        Rule rule = ruleWithVarScope(List.of("AESTDTC"), null);
        IDataTable table = aeTable("USUBJID", "AESTDY"); // no AESTDTC
        RuleExecutionResult result = RuleRunner.execute(rule, table);
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
        RuleExecutionResult result = RuleRunner.execute(rule, table, null, "AE");
        assertNotEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "--STDTC resolves to AESTDTC, which is present");
    }


    @Test
    void genericDashInclude_resolvedAndAbsent_ruleSkipped()
    {
        Rule rule = ruleWithVarScope(List.of("--STDTC"), null);
        IDataTable table = aeTable("USUBJID", "AESTDY"); // no AESTDTC
        RuleExecutionResult result = RuleRunner.execute(rule, table, null, "AE");
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "--STDTC resolves to AESTDTC, which is absent");
    }


    @Test
    void excludeVariablePresent_ruleSkipped()
    {
        Rule rule = ruleWithVarScope(null, List.of("POOLID"));
        IDataTable table = aeTable("USUBJID", "AESTDY", "POOLID");
        RuleExecutionResult result = RuleRunner.execute(rule, table);
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
        rule.setCheck(CheckConditionLeaf.builder().name("AESTDY").operator("var_exists").build());
        IDataTable table = aeTable("USUBJID", "AESTDY");
        RuleExecutionResult result = RuleRunner.execute(rule, table);
        assertNotEquals(RuleExecutionStatus.SKIPPED, result.getStatus(),
                "a rule without Requirements.Variables is never skipped by the variable gate");
    }
}
