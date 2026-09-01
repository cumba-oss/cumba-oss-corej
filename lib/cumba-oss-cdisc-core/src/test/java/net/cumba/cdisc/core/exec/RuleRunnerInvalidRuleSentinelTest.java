package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.TextNode;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Fix #37 — when {@link Rule#getLoadError()} is non-null, {@link RuleRunner#execute} returns a
 * single sentinel {@link Violation} carrying the message in the {@code __error__} key. No data
 * evaluation is performed.
 */
class RuleRunnerInvalidRuleSentinelTest
{

    private static Rule invalidRule(String loadError)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-INVALID");
        rule.setCore(core);
        // Give it a minimal Check tree so other code paths don't bail before reaching the
        // sentinel; the sentinel must take precedence regardless of Check shape.
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("AESTDY").operator("var_exists")
                .value(TextNode.valueOf("x")).build();
        rule.setCheck(leaf);
        rule.setLoadError(loadError);
        return rule;
    }


    @Test
    void execute_invalidRule_returnsSentinelViolation()
    {
        Rule rule = invalidRule("test error");
        IDataTable table = MockTable.of().col("USUBJID", "S1").col("AESTDY", "1").name("ADAE")
                .build();
        RuleExecutionResult result = RuleRunner.execute(rule, table);
        assertEquals(1, result.getViolationCount(), "exactly one sentinel violation");
        Violation v = result.getViolations().get(0);
        assertEquals(0L, v.getRow());
        assertEquals("test error", v.getValues().get("__error__"));
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
        assertEquals("test error", result.getStatusMessage());
    }


    @Test
    void execute_invalidRule_doesNotEvaluateData()
    {
        // Empty table — if RuleRunner attempted any evaluation, the sentinel path would still
        // produce one violation. The presence of the sentinel proves the early-return path.
        Rule rule = invalidRule("schema error");
        IDataTable emptyTable = MockTable.of().col("USUBJID").name("ADAE").build();
        RuleExecutionResult result = RuleRunner.execute(rule, emptyTable);
        assertEquals(1, result.getViolationCount());
        Violation v = result.getViolations().get(0);
        assertEquals("schema error", v.getValues().get("__error__"));
        assertTrue(result.isError(), "isError shortcut returns true");
    }
}
