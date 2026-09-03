package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.NativeExecutionRecorder;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.CheckOperator;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * P6a/P6c of {@code plans/done/PLAN-native-engine-full-coverage.md} — the ENGINE-surface
 * completeness gate (decision 6: the target is the engine, not the shipped rules).
 *
 * <p>
 * <b>6a — operator matrix:</b> every {@link CheckOperator} the legacy engine implements must raise
 * to the {@code Expr} IR and compile on the native backend from a minimal plain-operand leaf. A
 * future operator added to {@code CheckOperator} without native support fails this gate immediately
 * (the EXPECTED-LEGACY-ONLY set must stay empty).
 * </p>
 *
 * <p>
 * <b>6c — dispatch grid:</b> a representative rule for every native-eligible rule-type ×
 * sensitivity combination must actually EXECUTE on the native backend
 * ({@link NativeExecutionRecorder}), covering combinations the shipped corpus never exercises.
 * </p>
 */
class NativeEngineSurfaceTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The minimal plain-operand leaf for every legacy operator. Each entry is the simplest faithful
     * shape of that operator (plain columns, literal values, required modifier fields) — the
     * curated engine-surface matrix.
     */
    private static Map<String, CheckConditionLeaf> operatorMatrix()
    {
        Map<String, CheckConditionLeaf> m = new LinkedHashMap<>();
        // -- plain comparisons / predicates -------------------------------------------------
        m.put("equal_to",
                leaf("X", "equal_to").value(MAPPER.valueToTree("A")).valueIsLiteral(true).build());
        m.put("not_equal_to", leaf("X", "not_equal_to").value(MAPPER.valueToTree("A"))
                .valueIsLiteral(true).build());
        m.put("equal_to_case_insensitive", leaf("X", "equal_to_case_insensitive")
                .value(MAPPER.valueToTree("a")).valueIsLiteral(true).build());
        m.put("not_equal_to_case_insensitive", leaf("X", "not_equal_to_case_insensitive")
                .value(MAPPER.valueToTree("a")).valueIsLiteral(true).build());
        m.put("greater_than", leaf("X", "greater_than").value(MAPPER.valueToTree(1)).build());
        m.put("greater_than_or_equal_to",
                leaf("X", "greater_than_or_equal_to").value(MAPPER.valueToTree(1)).build());
        m.put("less_than", leaf("X", "less_than").value(MAPPER.valueToTree(9)).build());
        m.put("less_than_or_equal_to",
                leaf("X", "less_than_or_equal_to").value(MAPPER.valueToTree(9)).build());
        m.put("empty", leaf("X", "empty").build());
        m.put("non_empty", leaf("X", "non_empty").build());
        m.put("longer_than", leaf("X", "longer_than").value(MAPPER.valueToTree(8)).build());
        m.put("longer_than_or_equal_to",
                leaf("X", "longer_than_or_equal_to").value(MAPPER.valueToTree(8)).build());
        m.put("shorter_than", leaf("X", "shorter_than").value(MAPPER.valueToTree(8)).build());
        m.put("shorter_than_or_equal_to",
                leaf("X", "shorter_than_or_equal_to").value(MAPPER.valueToTree(8)).build());
        m.put("has_equal_length",
                leaf("X", "has_equal_length").value(MAPPER.valueToTree(2)).build());
        m.put("has_not_equal_length",
                leaf("X", "has_not_equal_length").value(MAPPER.valueToTree(2)).build());
        m.put("is_integer", leaf("X", "is_integer").build());
        m.put("is_not_integer", leaf("X", "is_not_integer").build());
        // -- substring / regex --------------------------------------------------------------
        m.put("contains",
                leaf("X", "contains").value(MAPPER.valueToTree("A")).valueIsLiteral(true).build());
        m.put("does_not_contain", leaf("X", "does_not_contain").value(MAPPER.valueToTree("A"))
                .valueIsLiteral(true).build());
        m.put("contains_case_insensitive", leaf("X", "contains_case_insensitive")
                .value(MAPPER.valueToTree("A")).valueIsLiteral(true).build());
        m.put("does_not_contain_case_insensitive", leaf("X", "does_not_contain_case_insensitive")
                .value(MAPPER.valueToTree("A")).valueIsLiteral(true).build());
        m.put("starts_with", leaf("X", "starts_with").value(MAPPER.valueToTree("A"))
                .valueIsLiteral(true).build());
        m.put("ends_with",
                leaf("X", "ends_with").value(MAPPER.valueToTree("A")).valueIsLiteral(true).build());
        m.put("matches_regex", leaf("X", "matches_regex").value(MAPPER.valueToTree("^A"))
                .valueIsLiteral(true).build());
        m.put("not_matches_regex", leaf("X", "not_matches_regex").value(MAPPER.valueToTree("^A"))
                .valueIsLiteral(true).build());
        m.put("does_not_equal_string_part", leaf("X", "does_not_equal_string_part")
                .value(MAPPER.valueToTree("Y")).regex(".{1}(..).*").build());
        // -- membership ----------------------------------------------------------------------
        m.put("is_contained_by", leaf("X", "is_contained_by")
                .value(MAPPER.createArrayNode().add("A").add("B")).build());
        m.put("is_not_contained_by", leaf("X", "is_not_contained_by")
                .value(MAPPER.createArrayNode().add("A").add("B")).build());
        m.put("is_contained_by_case_insensitive", leaf("X", "is_contained_by_case_insensitive")
                .value(MAPPER.createArrayNode().add("a")).build());
        m.put("is_not_contained_by_case_insensitive",
                leaf("X", "is_not_contained_by_case_insensitive")
                        .value(MAPPER.createArrayNode().add("a")).build());
        // -- affix compare / affix regex ------------------------------------------------------
        m.put("prefix_equal_to",
                leaf("X", "prefix_equal_to").prefix(2).value(MAPPER.valueToTree("FA")).build());
        m.put("suffix_equal_to",
                leaf("X", "suffix_equal_to").suffix(3).value(MAPPER.valueToTree("SEQ")).build());
        m.put("prefix_not_equal_to",
                leaf("X", "prefix_not_equal_to").prefix(2).value(MAPPER.valueToTree("FA")).build());
        m.put("prefix_is_not_contained_by", leaf("X", "prefix_is_not_contained_by").prefix(2)
                .value(MAPPER.createArrayNode().add("FA")).build());
        m.put("suffix_is_not_contained_by", leaf("X", "suffix_is_not_contained_by").suffix(3)
                .value(MAPPER.createArrayNode().add("SEQ")).build());
        m.put("prefix_matches_regex", leaf("X", "prefix_matches_regex").prefix(2)
                .value(MAPPER.valueToTree("(AP|FA)")).valueIsLiteral(true).build());
        m.put("not_prefix_matches_regex", leaf("X", "not_prefix_matches_regex").prefix(2)
                .value(MAPPER.valueToTree("(AP|FA)")).valueIsLiteral(true).build());
        m.put("suffix_matches_regex", leaf("X", "suffix_matches_regex").suffix(3)
                .value(MAPPER.valueToTree("SEQ")).valueIsLiteral(true).build());
        m.put("not_suffix_matches_regex", leaf("X", "not_suffix_matches_regex").suffix(3)
                .value(MAPPER.valueToTree("SEQ")).valueIsLiteral(true).build());
        // -- dates / durations ----------------------------------------------------------------
        m.put("date_equal_to", leaf("X", "date_equal_to").value(MAPPER.valueToTree("Y")).build());
        m.put("date_not_equal_to",
                leaf("X", "date_not_equal_to").value(MAPPER.valueToTree("Y")).build());
        m.put("date_greater_than",
                leaf("X", "date_greater_than").value(MAPPER.valueToTree("Y")).build());
        m.put("date_greater_than_or_equal_to",
                leaf("X", "date_greater_than_or_equal_to").value(MAPPER.valueToTree("Y")).build());
        m.put("date_less_than", leaf("X", "date_less_than").value(MAPPER.valueToTree("Y")).build());
        m.put("date_less_than_or_equal_to",
                leaf("X", "date_less_than_or_equal_to").value(MAPPER.valueToTree("Y")).build());
        m.put("date_part_equal_to",
                leaf("X", "date_part_equal_to").value(MAPPER.valueToTree("Y")).build());
        m.put("date_part_not_equal_to",
                leaf("X", "date_part_not_equal_to").value(MAPPER.valueToTree("Y")).build());
        m.put("time_part_equal_to",
                leaf("X", "time_part_equal_to").value(MAPPER.valueToTree("Y")).build());
        m.put("time_part_not_equal_to",
                leaf("X", "time_part_not_equal_to").value(MAPPER.valueToTree("Y")).build());
        m.put("invalid_date", leaf("X", "invalid_date").build());
        m.put("is_complete_date", leaf("X", "is_complete_date").build());
        m.put("is_incomplete_date", leaf("X", "is_incomplete_date").build());
        m.put("is_complete_date_part", leaf("X", "is_complete_date_part").build());
        m.put("is_not_complete_date_part", leaf("X", "is_not_complete_date_part").build());
        m.put("invalid_duration", leaf("X", "invalid_duration").build());
        // EC-22: the negative= kwarg form must also compile natively (the arm that parses the
        // boolean-literal kwarg). Extra matrix key — the completeness gate keys on CheckOperator
        // constants, so an additional template is compiled without breaking the one-per-operator
        // check.
        m.put("invalid_duration_negative", leaf("X", "invalid_duration").negative(true).build());
        // -- existence -------------------------------------------------------------------------
        m.put("ds_exists", leaf("DM", "ds_exists").build());
        m.put("ds_not_exists", leaf("DM", "ds_not_exists").build());
        m.put("var_exists", leaf("X", "var_exists").build());
        m.put("var_not_exists", leaf("X", "var_not_exists").build());
        m.put("var_is_null", leaf("X", "var_is_null").build());
        // -- arithmetic comparisons -------------------------------------------------------------
        m.put("not_equal_to_divide", leaf("X", "not_equal_to_divide")
                .value(MAPPER.createArrayNode().add("A").add("B")).build());
        m.put("not_equal_to_subtract", leaf("X", "not_equal_to_subtract")
                .value(MAPPER.createArrayNode().add("A").add("B")).build());
        m.put("not_equal_to_pctchg", leaf("X", "not_equal_to_pctchg")
                .value(MAPPER.createArrayNode().add("A").add("B")).build());
        // -- group / set / aggregate ------------------------------------------------------------
        m.put("has_multiple_values_for", leaf("X", "has_multiple_values_for")
                .value(MAPPER.valueToTree("K")).within(MAPPER.valueToTree("W")).build());
        m.put("present_on_multiple_rows_within", leaf("X", "present_on_multiple_rows_within")
                .within(MAPPER.valueToTree("W")).build());
        m.put("not_present_on_multiple_rows_within",
                leaf("X", "not_present_on_multiple_rows_within").within(MAPPER.valueToTree("W"))
                        .build());
        m.put("empty_within_except_last_row", leaf("X", "empty_within_except_last_row")
                .value(MAPPER.valueToTree("G")).ordering("O").build());
        m.put("does_not_have_next_corresponding_record",
                leaf("X", "does_not_have_next_corresponding_record").value(MAPPER.valueToTree("Y"))
                        .within(MAPPER.valueToTree("W")).ordering("O").build());
        m.put("target_is_not_sorted_by",
                leaf("X", "target_is_not_sorted_by")
                        .value(MAPPER.createArrayNode()
                                .add(MAPPER.createObjectNode().put("name", "O")
                                        .put("sort_order", "asc").put("null_position", "last")))
                        .within(MAPPER.valueToTree("W")).build());
        m.put("is_not_unique_relationship",
                leaf("X", "is_not_unique_relationship").value(MAPPER.valueToTree("Y")).build());
        m.put("is_not_unique_set",
                leaf("X", "is_not_unique_set").value(MAPPER.createArrayNode().add("K")).build());
        m.put("is_unique_set",
                leaf("X", "is_unique_set").value(MAPPER.createArrayNode().add("K")).build());
        m.put("is_inconsistent_across_dataset", leaf("X", "is_inconsistent_across_dataset")
                .value(MAPPER.createArrayNode().add("K")).build());
        m.put("inconsistent_enumerated_columns",
                leaf("X", "inconsistent_enumerated_columns").build());
        m.put("has_same_values", leaf("X", "has_same_values").build());
        m.put("not_contains_all",
                leaf("X", "not_contains_all").value(MAPPER.createArrayNode().add("A")).build());
        m.put("shares_no_elements_with",
                leaf("$a", "shares_no_elements_with").value(MAPPER.valueToTree("$b")).build());
        m.put("is_not_ordered_subset_of",
                leaf("$a", "is_not_ordered_subset_of").value(MAPPER.valueToTree("$b")).build());
        return m;
    }


    private static CheckConditionLeaf.CheckConditionLeafBuilder leaf(String name, String op)
    {
        return CheckConditionLeaf.builder().name(name).operator(op);
    }


    @Test
    void everyLegacyOperatorCompilesNatively()
    {
        Map<String, CheckConditionLeaf> matrix = operatorMatrix();

        // (1) The matrix itself must be COMPLETE: one entry per CheckOperator constant, so a new
        // operator cannot be added to the legacy surface without extending (and passing) this
        // gate.
        for (CheckOperator op : CheckOperator.values())
        {
            assertTrue(matrix.containsKey(op.getJsonValue()),
                    "engine-surface matrix is missing operator '" + op.getJsonValue()
                            + "' — add a minimal leaf template for it");
        }

        // (2) Every entry must raise to Expr and compile on the native backend.
        List<String> legacyOnly = new ArrayList<>();
        for (Map.Entry<String, CheckConditionLeaf> e : matrix.entrySet())
        {
            try
            {
                Expr expr = CheckToExpr.toExpr(new CheckConditionAll(List.of(e.getValue())));
                ExprCompiler.compile(expr);
            }
            catch (ExpressionException ex)
            {
                legacyOnly.add(e.getKey() + ": " + ex.getMessage());
            }
        }
        assertTrue(legacyOnly.isEmpty(),
                "EXPECTED-LEGACY-ONLY operators must be empty — the native engine must implement"
                        + " the full legacy surface (" + legacyOnly.size() + "):\n"
                        + String.join("\n", legacyOnly));
    }

    // ------------------------------------------------------------------
    // 6c — dispatch grid: representative rule per type × sensitivity runs NATIVE
    // ------------------------------------------------------------------


    private static Rule loadRule(String ruleBody) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + ruleBody + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        assertNotNull(rule.getCheckExpr(), "grid rule must retain a native checkExpr");
        return rule;
    }


    private static void assertRunsNative(Rule rule, IDataTable t)
    {
        NativeExecutionRecorder.enable();
        RuleRunner.execute(rule, t, _ -> null, "AE", null, null, null);
        Map<String, NativeExecutionRecorder.Backend> rec = NativeExecutionRecorder.disable();
        assertEquals(NativeExecutionRecorder.Backend.NATIVE, rec.get("R1"),
                "rule must execute on the NATIVE backend, got " + rec);
    }


    @Test
    void dispatchGrid_runsNativeForEveryEligibleCell() throws Exception
    {
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "a", "", "a")
                .col("USUBJID", "S1", "S1", "S2").col("AESEV", "MILD", "", "SEVERE").build();

        // RECORD_DATA × Record (row-level)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"name\":\"AETERM\",\"operator\":\"empty\"}]},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // RECORD_DATA × Dataset (non-row-based collapse — P3c)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"AETERM\",\"operator\":\"empty\"}]},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // RECORD_DATA × Group (hoisted grouped dispatch — B3)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Group\",\"Grouping_Variables\":[\"USUBJID\"],"
                + "\"Check\":{\"all\":[{\"name\":\"AETERM\",\"operator\":\"empty\"}]},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // VARIABLE_METADATA_CHECK × Dataset (presence broadcast — P3a)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"AEOCCUR\",\"operator\":\"var_exists\"}]},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // VARIABLE_METADATA_CHECK × Dataset (per-variable accessor broadcast — B4)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"variable_label\",\"operator\":\"longer_than\","
                + "\"value\":40}]},\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // VARIABLE_METADATA_CHECK × Record (variable_name-anchored — P4b; unshipped combination)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"name\":\"variable_name\","
                + "\"operator\":\"is_not_contained_by\","
                + "\"value\":[\"AETERM\",\"USUBJID\",\"AESEV\"]}]},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // DATASET_METADATA_CHECK × Dataset (ds_* accessor broadcast)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"dataset_name\",\"operator\":\"matches_regex\","
                + "\"value\":\"^AE\",\"value_is_literal\":true}]},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // VALUE_CHECK_WITH_VARIABLE_METADATA × Record (value()+guard per-(variable,row) — B4+)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "" + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"name\":\"variable_name\",\"operator\":\"equal_to\","
                + "\"value\":\"AESEV\",\"value_is_literal\":true},"
                + "{\"name\":\"variable_value\",\"operator\":\"empty\"}]},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // DOMAIN_PRESENCE_CHECK × Dataset (dataset-existence broadcast — B5)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"SUPPAE\",\"operator\":\"ds_not_exists\"}]},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);
    }

}
