package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Operation;
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

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testExecute_simpleRule_withViolations()
    {
        // Rule: SEX must be in {M, F} — violations are rows where SEX is not M or F
        IDataTable table = MockTable.of().col("USUBJID", "SUBJ01", "SUBJ02", "SUBJ03")
                .col("SEX", "M", "U", "F").build();

        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("SEX")
                .operator("is_not_contained_by").value(arrayNode("M", "F")).build();

        Rule rule = buildRule("CORE-000001", "SEX not in codelist",
                new CheckConditionAll(List.of(leaf)), List.of("USUBJID", "SEX"));

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        assertEquals("CORE-000001", result.getRuleId());
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

        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("SEX")
                .operator("is_not_contained_by").value(arrayNode("M", "F")).build();

        Rule rule = buildRule("CORE-000002", "SEX must be M or F",
                new CheckConditionAll(List.of(leaf)), List.of("SEX"));

        RuleExecutionResult result = RuleRunner.execute(rule, table);

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

        CheckConditionLeaf nonEmpty = CheckConditionLeaf.builder().name("AETERM")
                .operator("non_empty").build();
        CheckConditionLeaf domainAE = CheckConditionLeaf.builder().name("DOMAIN")
                .operator("equal_to").value(MAPPER.valueToTree("AE")).valueIsLiteral(true).build();

        CheckConditionAll all = new CheckConditionAll(List.of(nonEmpty, domainAE));
        Rule rule = buildRule("CORE-000003", "Non-empty AETERM in AE domain", all,
                List.of("AETERM"));

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        assertEquals(1, result.getViolationCount());
        assertEquals(0, result.getViolations().get(0).getRow()); // row 0: AE + Headache
    }


    @Test
    void testExecute_missingColumn_noFalseViolations()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1", "S2").build();

        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("NONEXISTENT")
                .operator("non_empty").build();

        Rule rule = buildRule("CORE-000004", "test", new CheckConditionAll(List.of(leaf)),
                List.of());

        RuleExecutionResult result = RuleRunner.execute(rule, table);
        assertFalse(result.hasViolations());
    }

    // -----------------------------------------------------------------------
    // Integration tests: rules with operations
    // -----------------------------------------------------------------------


    @Test
    void testExecute_withOperations_variableInValue()
    {
        // Operation: distinct USUBJID from DM → $dm_usubjid = [S01, S02]
        // Check: USUBJID is_not_contained_by $dm_usubjid
        // AE table has S01, S03 → S03 is a violation
        IDataTable aeTable = MockTable.of().col("USUBJID", "S01", "S03", "S01").build();
        IDataTable dmTable = MockTable.of().col("USUBJID", "S01", "S02", "S01").build();

        Operation op = new Operation();
        op.setId("$dm_usubjid");
        op.setOperator("distinct");
        op.setName("USUBJID");
        op.setDomain("DM");

        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("USUBJID")
                .operator("is_not_contained_by").value(MAPPER.valueToTree("$dm_usubjid")).build();

        Rule rule = buildRule("CORE-OP-001", "USUBJID not in DM",
                new CheckConditionAll(List.of(leaf)), List.of("USUBJID"));
        rule.setOperations(List.of(op));

        DatasetResolver resolver = name -> "DM".equals(name) ? dmTable : null;
        RuleExecutionResult result = RuleRunner.execute(rule, aeTable, resolver);

        assertEquals(1, result.getViolationCount());
        assertEquals(1, result.getViolations().get(0).getRow()); // row 1: S03
        assertEquals("S03", result.getViolations().get(0).getValues().get("USUBJID"));
    }


    @Test
    void testExecute_withOperations_variableInName()
    {
        // Operation: variable_count → $VARIABLE_COUNT = 2
        // Check: $VARIABLE_COUNT greater_than 3 → all rows are violations (2 > 3 is false)
        IDataTable table = MockTable.of().col("A", "1", "2").col("B", "3", "4").build();

        Operation op = new Operation();
        op.setId("$VARIABLE_COUNT");
        op.setOperator("variable_count");

        // $VARIABLE_COUNT (=2) greater_than 3 → false → no violations
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("$VARIABLE_COUNT")
                .operator("greater_than").value(MAPPER.valueToTree(3)).build();

        Rule rule = buildRule("CORE-OP-002", "Too many variables",
                new CheckConditionAll(List.of(leaf)), List.of());
        rule.setOperations(List.of(op));

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        assertFalse(result.hasViolations());
    }


    @Test
    void testExecute_withOperations_variableInName_allViolations()
    {
        // variable_count = 5, check: $VARIABLE_COUNT greater_than 3 → true → violation
        // Dataset sensitivity → non-row-based → reports a single dataset-level violation
        IDataTable table = MockTable.of().col("A", "1", "2").col("B", "3", "4").col("C", "5", "6")
                .col("D", "7", "8").col("E", "9", "10").build();

        Operation op = new Operation();
        op.setId("$VARIABLE_COUNT");
        op.setOperator("variable_count");

        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("$VARIABLE_COUNT")
                .operator("greater_than").value(MAPPER.valueToTree(3)).build();

        Rule rule = buildRule("CORE-OP-003", "Too many variables",
                new CheckConditionAll(List.of(leaf)), List.of());
        rule.setOperations(List.of(op));

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        assertTrue(result.hasViolations());
        assertEquals(1, result.getViolationCount()); // dataset-level: single violation
    }


    @Test
    void testExecute_noOperations_sameAsBefore()
    {
        // Verify the no-operations path works unchanged
        IDataTable table = MockTable.of().col("SEX", "M", "X").build();

        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("SEX")
                .operator("is_not_contained_by").value(arrayNode("M", "F")).build();

        Rule rule = buildRule("CORE-000005", "Bad SEX", new CheckConditionAll(List.of(leaf)),
                List.of("SEX"));

        RuleExecutionResult result = RuleRunner.execute(rule, table);
        assertEquals(1, result.getViolationCount());
        assertEquals("X", result.getViolations().get(0).getValues().get("SEX"));
    }

    // -----------------------------------------------------------------------
    // Integration test: grouped operation (CORE-000239 pattern)
    // -----------------------------------------------------------------------


    @Test
    void testExecute_groupedOperation_perSubjectMinDate()
    {
        // CORE-000239: RFXSTDTC should equal earliest EX.EXSTDTC per subject
        IDataTable dmTable = MockTable.of().col("USUBJID", "S01", "S02", "S03")
                .col("RFXSTDTC", "2024-01-15", "2024-03-01", "2024-05-01").build();

        IDataTable exTable = MockTable.of().col("USUBJID", "S01", "S01", "S02", "S02", "S03")
                .col("EXSTDTC", "2024-01-15", "2024-02-01", "2024-02-10", "2024-03-01",
                        "2024-05-01")
                .build();

        // Operation 1: distinct USUBJID from EX
        Operation op1 = new Operation();
        op1.setId("$ex_usubjid");
        op1.setOperator("distinct");
        op1.setName("USUBJID");
        op1.setDomain("EX");

        // Operation 2: min_date of EXSTDTC grouped by USUBJID from EX
        Operation op2 = new Operation();
        op2.setId("$min_ex_exstdtc");
        op2.setOperator("min_date");
        op2.setName("EXSTDTC");
        op2.setDomain("EX");
        op2.setGroup(List.of("USUBJID"));

        // Check: USUBJID in $ex_usubjid AND RFXSTDTC != $min_ex_exstdtc
        CheckConditionLeaf inSubjects = CheckConditionLeaf.builder().name("USUBJID")
                .operator("is_contained_by").value(MAPPER.valueToTree("$ex_usubjid")).build();
        CheckConditionLeaf rfxMismatch = CheckConditionLeaf.builder().name("RFXSTDTC")
                .operator("not_equal_to").value(MAPPER.valueToTree("$min_ex_exstdtc")).build();

        CheckConditionAll check = new CheckConditionAll(List.of(inSubjects, rfxMismatch));
        Rule rule = buildRule("CORE-000239",
                "RFXSTDTC does not equal the earliest value of EX.EXSTDTC", check,
                List.of("USUBJID", "RFXSTDTC"));
        rule.setOperations(List.of(op1, op2));

        DatasetResolver resolver = name -> "EX".equals(name) ? exTable : null;
        RuleExecutionResult result = RuleRunner.execute(rule, dmTable, resolver);

        // S01: RFXSTDTC=2024-01-15, min EX=2024-01-15 → match, no violation
        // S02: RFXSTDTC=2024-03-01, min EX=2024-02-10 → mismatch! violation
        // S03: RFXSTDTC=2024-05-01, min EX=2024-05-01 → match, no violation
        assertEquals(1, result.getViolationCount());
        assertEquals(1, result.getViolations().get(0).getRow()); // row 1 = S02
        assertEquals("S02", result.getViolations().get(0).getValues().get("USUBJID"));
    }

    // -----------------------------------------------------------------------
    // Helpers
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


    private static com.fasterxml.jackson.databind.node.ArrayNode arrayNode(String... values)
    {
        com.fasterxml.jackson.databind.node.ArrayNode arr = MAPPER.createArrayNode();
        for (String v : values)
        {
            arr.add(v);
        }
        return arr;
    }

}
