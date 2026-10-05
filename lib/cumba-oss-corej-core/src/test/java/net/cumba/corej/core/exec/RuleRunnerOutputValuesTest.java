package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@code RuleRunner.extractOutputValues}: unresolved {@code Output_Variables} entries
 * are <em>omitted</em> from {@link Violation#getValues()}. Historical rationale (the Python lane
 * and its parity adapter were removed in wave 33): Python's {@code actions.py:272} emitted a
 * {@code "Not in dataset"} sentinel into {@code ValidationErrorEntity.value}, but the adapter
 * ({@code engine_adapter.py:639}) stripped it — the engine's contract was fixed as "post-strip
 * Python" and is kept, with sentinel emission deferred to the report writer if needed.
 */
class RuleRunnerOutputValuesTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }


    @Test
    void unknownOutputVariableIsOmittedFromValues()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02", "S03")
                .col("SEX", "M", "U", "F").build();

        net.cumba.corej.core.model.CheckConditionExpression leaf = expr(
                "SEX not in [\"M\", \"F\"]");

        // AESEV / AESMIE are NOT columns on this table.
        Rule rule = buildRule("CORE-TEST-1", "SEX not in codelist",
                new CheckConditionAll(List.of(leaf)), List.of("USUBJID", "SEX", "AESEV", "AESMIE"));

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        assertEquals(1, result.getViolationCount());
        // A rule with no Sensitivity collapses to one dataset-level finding at the first firing
        // row; PLAN-finding-values-record-level-only V5 lists its raw columns by name (they
        // printed row 2's values until then). The unresolved entries are in NEITHER channel.
        Violation v = result.getViolations().get(0);
        Map<String, String> values = v.getValues();
        assertEquals(List.of("USUBJID", "SEX"), v.getNamedColumns());
        assertFalse(values.containsKey("USUBJID"), values.toString());
        assertFalse(values.containsKey("AESEV"),
                "Unresolved Output_Variables omitted from values map");
        assertFalse(values.containsKey("AESMIE"));
        assertFalse(v.getNamedColumns().contains("AESEV"), "…and never listed by name either");
    }


    @Test
    void presentColumnsResolveAbsentColumnsOmitted()
    {
        IDataTable table = MockTable.of().col("AETERM", "Headache", "Cough", "Cold")
                .col("DOMAIN", "AE", "AE", "AE").build();
        net.cumba.corej.core.model.CheckConditionExpression leaf = expr("not empty(AETERM)");
        Rule rule = buildRule("CORE-TEST-2", "AETERM must be populated",
                new CheckConditionAll(List.of(leaf)), List.of("AETERM", "DOMAIN", "AESMIE"));

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        assertTrue(result.hasViolations());
        Violation v = result.getViolations().get(0);
        // Present columns resolve — listed by name on this collapsed dataset-level finding (V5);
        // the absent one is omitted from both channels.
        assertEquals(List.of("AETERM", "DOMAIN"), v.getNamedColumns());
        assertFalse(v.getValues().containsKey("AESMIE"));
        assertFalse(v.getNamedColumns().contains("AESMIE"));
    }


    @Test
    void noSentinelLiteralEmittedAnywhere()
    {
        // Negative regression: the literal "Not in dataset" must never appear as a value.
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02").col("SEX", "M", "U").build();
        net.cumba.corej.core.model.CheckConditionExpression leaf = expr(
                "SEX not in [\"M\", \"F\"]");
        Rule rule = buildRule("CORE-TEST-3", "SEX", new CheckConditionAll(List.of(leaf)),
                List.of("USUBJID", "SEX", "AESEV")); // AESEV unresolved

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        Map<String, String> values = result.getViolations().get(0).getValues();
        assertTrue(values.values().stream().noneMatch("Not in dataset"::equals),
                "no \"Not in dataset\" sentinel — adapter strips it on Python lane, "
                        + "Java engine layer omits it");
    }

    // -----------------------------------------------------------------------
    // Helpers (mirror RuleRunnerTest's pattern)
    // -----------------------------------------------------------------------


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
