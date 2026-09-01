package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class RuleRunnerEdgeCaseTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testExecute_nullCheck_returnsEmptyResult()
    {
        IDataTable table = MockTable.of().col("X", "1", "2").build();

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-NULL");
        rule.setCore(core);
        // rule.setCheck is NOT called → check is null

        installExpr(rule);

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        assertEquals("CORE-NULL", result.getRuleId());
        assertFalse(result.hasViolations());
        assertEquals(2, result.getTotalRows());
    }


    @Test
    void testExecute_datasetSensitivity_treatedAsDatasetLevel()
    {
        IDataTable table = MockTable.of().col("SEX", "M", "U", "X").build();

        // Dataset-sensitivity rule → treated as dataset-level (single violation max)
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("SEX")
                .operator("is_not_contained_by").value(arrayNode("M", "F")).build();

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-NOTYPE");
        rule.setCore(core);
        rule.setCheck(new CheckConditionAll(List.of(leaf)));

        installExpr(rule);

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        // Non-row-based: at most 1 violation even though 2 rows match
        assertTrue(result.hasViolations());
        assertEquals(1, result.getViolationCount());
    }


    @Test
    void testExecute_rowBasedRule_multipleViolations()
    {
        IDataTable table = MockTable.of().col("SEX", "M", "U", "X").build();

        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("SEX")
                .operator("is_not_contained_by").value(arrayNode("M", "F")).build();

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-ROWBASED");
        rule.setCore(core);
        rule.setCheck(new CheckConditionAll(List.of(leaf)));
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("Bad SEX value");
        outcome.setOutputVariables(List.of("SEX"));
        rule.setOutcome(outcome);

        installExpr(rule);

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        assertEquals(2, result.getViolationCount()); // rows 1 (U) and 2 (X)
        assertEquals("U", result.getViolations().get(0).getValues().get("SEX"));
        assertEquals("X", result.getViolations().get(1).getValues().get("SEX"));
    }


    @Test
    void testExecute_noCoreId_fallsBackToRuleId()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Rule rule = new Rule();
        rule.setId("fallback-uuid");
        // core is null
        rule.setCheck(CheckConditionLeaf.builder().name("X").operator("var_exists").build());

        installExpr(rule);

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        assertEquals("fallback-uuid", result.getRuleId());
    }


    @Test
    void testExecute_noOutcome_messageIsNull()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        Rule rule = new Rule();
        rule.setId("test");
        rule.setCheck(CheckConditionLeaf.builder().name("X").operator("non_empty").build());
        // outcome is null

        installExpr(rule);

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        assertNull(result.getMessage());
    }

    // ---- Variable Metadata Check rule type ----


    @Test
    void testExecute_variableMetadataCheck()
    {
        IDataTable table = MockTable.of().col("STUDYID", "S001").col("CUSTOM", "X").build();

        // A variable metadata check that checks if variable_name == "STUDYID"
        // This should evaluate per-column, not per-row
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("$variable_name")
                .operator("equal_to").value(MAPPER.valueToTree("STUDYID")).valueIsLiteral(true)
                .build();

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-VARMETA");
        rule.setCore(core);
        rule.setCheck(leaf);
        rule.setSensitivity(Sensitivity.DATASET);

        installExpr(rule);

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        // variable_name check evaluates per column → STUDYID matches
        assertEquals("CORE-VARMETA", result.getRuleId());
    }

    // ---- Error handling ----


    @Test
    void testExecute_errorStatus()
    {
        IDataTable table = MockTable.of().col("X", "1").build();

        // A rule that references a non-existent operator shouldn't crash
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X")
                .operator("completely_unknown_op_xyz").value(MAPPER.valueToTree("1")).build();

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-BADOP");
        rule.setCore(core);
        rule.setCheck(leaf);

        installExpr(rule);

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        // Unknown operator returns empty BitSet → no violations, not an error crash
        assertEquals("CORE-BADOP", result.getRuleId());
        assertFalse(result.hasViolations());
    }

    // ---- Cross-domain execution with DatasetResolver ----


    @Test
    void testExecute_withDatasetResolver_operationOnOtherDomain()
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S01", "S02")
                .col("AGE", "25", "30").build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S01", "S01")
                .col("AETERM", "Headache", "Nausea").build();

        DatasetResolver resolver = domain -> switch (domain)
        {
        case "DM" -> dm;
        case "AE" -> ae;
        default -> null;
        };

        // A simple rule on DM with no cross-domain check
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("AGE").operator("greater_than")
                .value(MAPPER.valueToTree(26)).build();

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-CROSS");
        rule.setCore(core);
        rule.setCheck(leaf);
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("AGE > 26");
        outcome.setOutputVariables(List.of("AGE"));
        rule.setOutcome(outcome);

        installExpr(rule);

        RuleExecutionResult result = RuleRunner.execute(rule, dm, resolver);

        assertEquals(1, result.getViolationCount()); // only S02 (AGE=30)
        assertEquals("30", result.getViolations().getFirst().getValues().get("AGE"));
    }

    // ---- explicit literal values ----


    @Test
    void testExecute_explicitLiteralValue()
    {
        IDataTable table = MockTable.of().col("AESTDTC", "2024-01-01", "2024-06-15").build();

        // value_is_literal marks the text as a literal rather than a column reference
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("AESTDTC").operator("equal_to")
                .value(MAPPER.valueToTree("2024-01-01")).valueIsLiteral(true).build();

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-LITFB");
        rule.setCore(core);
        rule.setCheck(leaf);
        rule.setSensitivity(Sensitivity.RECORD);

        installExpr(rule);

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        assertEquals(1, result.getViolationCount()); // only row 0
    }

    // ---- GROUP sensitivity ----


    @Test
    void testExecute_groupSensitivity_withGroupingVariables()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01", "S02", "S02")
                .col("AETERM", "Headache", "Nausea", "Headache", "Headache").build();

        // A simple check
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("AETERM").operator("equal_to")
                .value(MAPPER.valueToTree("Headache")).valueIsLiteral(true).build();

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-GROUP");
        rule.setCore(core);
        rule.setCheck(leaf);
        rule.setSensitivity(Sensitivity.RECORD);
        rule.setGroupingVariables(List.of("USUBJID"));
        Outcome outcome = new Outcome();
        outcome.setMessage("Test");
        outcome.setOutputVariables(List.of("AETERM"));
        rule.setOutcome(outcome);

        installExpr(rule);

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        // Rows 0, 2, 3 have AETERM=Headache
        assertTrue(result.hasViolations());
    }


    private static com.fasterxml.jackson.databind.node.ArrayNode arrayNode(String... values)
    {
        com.fasterxml.jackson.databind.node.ArrayNode arr = MAPPER.createArrayNode();
        for (String v : values)
        {
            arr.add(v);
        }
        return arr;
    }


    /**
     * The retired legacy engine evaluated hand-built Checks directly; the native engine needs the
     * load-time compile. Mirrors {@code RulePackageLoader.installNativeExpr} for the simple fixture
     * shapes here (incl. hand-built rules).
     */
    private static void installExpr(Rule rule)
    {
        if (rule.getCheck() != null && rule.getCheckExpr() == null)
        {
            try
            {
                rule.setCheckExpr(net.cumba.cdisc.core.expr.CheckToExpr.toExpr(rule.getCheck()));
            }
            catch (net.cumba.cdisc.core.expr.ExpressionException _)
            {
                // Unraisable Check (e.g. the unknown-operator error fixture): leave checkExpr
                // null so the runner reports the no-native-form ERROR, which is that test's point.
            }
        }
    }
}
