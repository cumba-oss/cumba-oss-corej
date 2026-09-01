package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.datatable.IDataTable;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void unknownOutputVariableIsOmittedFromValues()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02", "S03")
                .col("SEX", "M", "U", "F").build();

        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("SEX")
                .operator("is_not_contained_by").value(arrayNode("M", "F")).build();

        // AESEV / AESMIE are NOT columns on this table.
        Rule rule = buildRule("CORE-TEST-1", "SEX not in codelist",
                new CheckConditionAll(List.of(leaf)), List.of("USUBJID", "SEX", "AESEV", "AESMIE"));

        RuleExecutionResult result = RuleRunner.execute(rule, table);
        assertEquals(1, result.getViolationCount());
        Map<String, String> values = result.getViolations().get(0).getValues();
        assertEquals("S02", values.get("USUBJID"));
        assertEquals("U", values.get("SEX"));
        assertFalse(values.containsKey("AESEV"),
                "Unresolved Output_Variables omitted from values map");
        assertFalse(values.containsKey("AESMIE"));
    }


    @Test
    void presentColumnsResolveAbsentColumnsOmitted()
    {
        IDataTable table = MockTable.of().col("AETERM", "Headache", "Cough", "Cold")
                .col("DOMAIN", "AE", "AE", "AE").build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("AETERM").operator("non_empty")
                .build();
        Rule rule = buildRule("CORE-TEST-2", "AETERM must be populated",
                new CheckConditionAll(List.of(leaf)), List.of("AETERM", "DOMAIN", "AESMIE"));

        RuleExecutionResult result = RuleRunner.execute(rule, table);
        assertTrue(result.hasViolations());
        Map<String, String> values = result.getViolations().get(0).getValues();
        assertEquals("Headache", values.get("AETERM"));
        assertEquals("AE", values.get("DOMAIN"));
        assertFalse(values.containsKey("AESMIE"));
    }


    @Test
    void noSentinelLiteralEmittedAnywhere()
    {
        // Negative regression: the literal "Not in dataset" must never appear as a value.
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02").col("SEX", "M", "U").build();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("SEX")
                .operator("is_not_contained_by").value(arrayNode("M", "F")).build();
        Rule rule = buildRule("CORE-TEST-3", "SEX", new CheckConditionAll(List.of(leaf)),
                List.of("USUBJID", "SEX", "AESEV")); // AESEV unresolved

        RuleExecutionResult result = RuleRunner.execute(rule, table);
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
        net.cumba.cdisc.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    private static ArrayNode arrayNode(String... values)
    {
        ArrayNode arr = MAPPER.createArrayNode();
        for (String v : values)
        {
            arr.add(v);
        }
        return arr;
    }
}
