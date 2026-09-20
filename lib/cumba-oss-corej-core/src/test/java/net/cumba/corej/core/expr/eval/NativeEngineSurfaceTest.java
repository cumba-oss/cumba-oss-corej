package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
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
 * <b>6a — operator matrix:</b> every operator of the retired v1 vocabulary, in its canonical
 * expression spelling, must parse and compile on the native backend. Phase 7d (D121) retired the
 * {@code CheckOperator} enum and the leaf model, so the matrix is keyed by the frozen operator
 * token census and each entry carries the exact expression the retired {@code CheckToExpr} raised
 * that operator's minimal leaf to (generated mechanically against the pre-retirement engine, so the
 * spellings are the raiser's own, not re-derived by hand). A retired-surface spelling losing native
 * support fails this gate immediately.
 * </p>
 *
 * <p>
 * <b>6c — dispatch grid:</b> a representative rule for every native-eligible rule-type ×
 * sensitivity combination must actually EXECUTE — reach a verdict rather than skip — covering
 * combinations the shipped corpus never exercises.
 * </p>
 */
class NativeEngineSurfaceTest
{

    /**
     * The number of entries the matrix must hold: one template per constant of the retired
     * {@code CheckOperator} enum (79), plus the extra {@code invalid_duration_negative} kwarg
     * template (EC-22). The exact-equality pin is the non-vacuity guard: a template silently
     * dropped from the matrix fails here rather than shrinking the gate.
     */
    private static final int MATRIX_SIZE = 80;

    /**
     * The minimal expression for every retired-vocabulary operator — each entry is exactly what
     * {@code CheckToExpr} raised that operator's minimal plain-operand leaf to.
     */
    private static Map<String, String> operatorMatrix()
    {
        Map<String, String> m = new LinkedHashMap<>();
        // -- plain comparisons / predicates -------------------------------------------------
        m.put("equal_to", "X == \"A\"");
        m.put("not_equal_to", "X != \"A\"");
        m.put("equal_to_case_insensitive", "equalsIgnoreCase(X, \"a\")");
        m.put("not_equal_to_case_insensitive", "not equalsIgnoreCase(X, \"a\")");
        m.put("greater_than", "X > 1");
        m.put("greater_than_or_equal_to", "X >= 1");
        m.put("less_than", "X < 9");
        m.put("less_than_or_equal_to", "X <= 9");
        m.put("empty", "empty(X)");
        m.put("non_empty", "not empty(X)");
        m.put("longer_than", "len(X) > 8");
        m.put("longer_than_or_equal_to", "len(X) >= 8");
        m.put("shorter_than", "len(X) < 8");
        m.put("shorter_than_or_equal_to", "len(X) <= 8");
        m.put("has_equal_length", "len(X) == 2");
        m.put("has_not_equal_length", "len(X) != 2");
        m.put("is_integer", "is_integer(X)");
        m.put("is_not_integer", "not is_integer(X)");
        // -- substring / regex --------------------------------------------------------------
        m.put("contains", "contains(X, \"A\")");
        m.put("does_not_contain", "not contains(X, \"A\")");
        m.put("contains_case_insensitive", "contains(upper(X), upper(\"A\"))");
        m.put("does_not_contain_case_insensitive", "not contains(upper(X), upper(\"A\"))");
        m.put("starts_with", "starts_with(X, \"A\")");
        m.put("ends_with", "ends_with(X, \"A\")");
        m.put("matches_regex", "X =~ /^A/");
        m.put("not_matches_regex", "X !~ /^A/");
        m.put("does_not_equal_string_part",
                "does_not_equal_string_part(X, Y, regex=\".{1}(..).*\")");
        // -- membership ----------------------------------------------------------------------
        m.put("is_contained_by", "X in [\"A\", \"B\"]");
        m.put("is_not_contained_by", "X not in [\"A\", \"B\"]");
        m.put("is_contained_by_case_insensitive", "upper(X) in [\"A\"]");
        m.put("is_not_contained_by_case_insensitive", "upper(X) not in [\"A\"]");
        // -- affix compare / affix regex ------------------------------------------------------
        m.put("prefix_equal_to", "prefix(X, 2) == FA");
        m.put("suffix_equal_to", "suffix(X, 3) == SEQ");
        m.put("prefix_not_equal_to", "prefix(X, 2) != FA");
        m.put("prefix_is_not_contained_by", "prefix(X, 2) not in [\"FA\"]");
        m.put("suffix_is_not_contained_by", "suffix(X, 3) not in [\"SEQ\"]");
        m.put("prefix_matches_regex", "prefix(X, 2) =~ /^(AP|FA)$/");
        m.put("not_prefix_matches_regex", "prefix(X, 2) !~ /^(AP|FA)$/");
        m.put("suffix_matches_regex", "suffix(X, 3) =~ /^SEQ$/");
        m.put("not_suffix_matches_regex", "suffix(X, 3) !~ /^SEQ$/");
        // -- dates / durations ----------------------------------------------------------------
        m.put("date_equal_to", "date(X) == Y");
        m.put("date_not_equal_to", "date(X) != Y");
        m.put("date_greater_than", "date(X) > Y");
        m.put("date_greater_than_or_equal_to", "date(X) >= Y");
        m.put("date_less_than", "date(X) < Y");
        m.put("date_less_than_or_equal_to", "date(X) <= Y");
        m.put("date_part_equal_to", "date_part(X) == Y");
        m.put("date_part_not_equal_to", "date_part(X) != Y");
        m.put("time_part_equal_to", "time_part(X) == Y");
        m.put("time_part_not_equal_to", "time_part(X) != Y");
        m.put("invalid_date", "invalid_date(X)");
        m.put("is_complete_date", "is_complete_date(X)");
        m.put("is_incomplete_date", "is_incomplete_date(X)");
        m.put("is_complete_date_part", "is_complete_date_part(X)");
        m.put("is_not_complete_date_part", "not is_complete_date_part(X)");
        m.put("invalid_duration", "invalid_duration(X)");
        // EC-22: the negative= kwarg form must also compile natively (the arm that parses the
        // boolean-literal kwarg). Extra matrix key beside the per-operator census.
        m.put("invalid_duration_negative", "invalid_duration(X, negative=true)");
        // -- existence -------------------------------------------------------------------------
        m.put("ds_exists", "ds_exists(\"DM\")");
        m.put("ds_not_exists", "ds_not_exists(\"DM\")");
        m.put("var_exists", "var_exists(\"X\")");
        m.put("var_not_exists", "var_not_exists(\"X\")");
        m.put("var_is_null", "var_is_null(X)");
        // -- arithmetic comparisons -------------------------------------------------------------
        m.put("not_equal_to_divide", "X != (A / B)");
        m.put("not_equal_to_subtract", "X != (A - B)");
        m.put("not_equal_to_pctchg", "X != (((A - B) / B) * 100)");
        // -- group / set / aggregate ------------------------------------------------------------
        m.put("has_multiple_values_for", "has_multiple_values_for(X, K, within=W)");
        m.put("present_on_multiple_rows_within", "present_on_multiple_rows_within(X, within=W)");
        m.put("not_present_on_multiple_rows_within",
                "not present_on_multiple_rows_within(X, within=W)");
        m.put("empty_within_except_last_row", "empty_within_except_last_row(X, G, ordering=O)");
        m.put("does_not_have_next_corresponding_record",
                "not has_next_corresponding_record(X, Y, ordering=O, within=W)");
        m.put("target_is_not_sorted_by", "not is_sorted_by(X, by=[asc(\"O\")], within=W)");
        m.put("is_not_unique_relationship", "not is_unique_relationship(X, Y)");
        m.put("is_not_unique_set", "not is_unique_set([X, K])");
        m.put("is_unique_set", "is_unique_set([X, K])");
        m.put("is_inconsistent_across_dataset", "is_inconsistent_across_dataset(X, keys=[K])");
        m.put("inconsistent_enumerated_columns", "inconsistent_enumerated_columns(X)");
        m.put("has_same_values", "has_same_values(X)");
        m.put("not_contains_all", "not contains_all(X, keys=[A])");
        m.put("shares_no_elements_with", "not shares_elements_with($a, $b)");
        m.put("is_not_ordered_subset_of", "not is_ordered_subset_of($a, $b)");
        return m;
    }


    @Test
    void everyRetiredVocabularyOperatorCompilesNatively()
    {
        Map<String, String> matrix = operatorMatrix();

        // (1) Non-vacuity: the matrix must stay COMPLETE. The retired vocabulary is frozen, so
        // exact equality pins that no template was silently dropped.
        assertEquals(MATRIX_SIZE, matrix.size(),
                "engine-surface matrix must keep one template per retired-vocabulary operator");

        // (2) Every entry must parse and compile on the native backend.
        List<String> unsupported = new ArrayList<>();
        for (Map.Entry<String, String> e : matrix.entrySet())
        {
            try
            {
                ExprCompiler.compile(CheckExpressionParser.parse(e.getValue()));
            }
            catch (ExpressionException ex)
            {
                unsupported.add(e.getKey() + ": " + ex.getMessage());
            }
        }
        assertTrue(unsupported.isEmpty(),
                "the native engine must implement the full retired-vocabulary surface ("
                        + unsupported.size() + "):\n" + String.join("\n", unsupported));
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
        RuleExecutionResult result = RuleRunner.execute(rule, t, _ -> null, "AE", null, null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                "rule must reach a verdict on the native backend, got " + result.getStatus() + ": "
                        + result.getStatusMessage());
    }


    @Test
    void dispatchGrid_runsNativeForEveryEligibleCell() throws Exception
    {
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "a", "", "a")
                .col("USUBJID", "S1", "S1", "S2").col("AESEV", "MILD", "", "SEVERE").build();

        // RECORD_DATA × Record (row-level)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"expression\":\"empty(AETERM)\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // RECORD_DATA × Dataset (non-row-based collapse — P3c)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"expression\":\"empty(AETERM)\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // RECORD_DATA × Group (hoisted grouped dispatch — B3)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Group\",\"Grouping_Variables\":[\"USUBJID\"],"
                + "\"Check\":{\"expression\":\"empty(AETERM)\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // VARIABLE_METADATA_CHECK × Dataset (presence broadcast — P3a)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"expression\":\"var_exists(\\\"AEOCCUR\\\")\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // VARIABLE_METADATA_CHECK × Dataset (per-variable accessor broadcast — B4)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"expression\":\"len(variable_label) > 40\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // VARIABLE_METADATA_CHECK × Record (variable_name-anchored — P4b; unshipped combination)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"expression\":"
                + "\"variable_name not in [\\\"AETERM\\\", \\\"USUBJID\\\", \\\"AESEV\\\"]\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // DATASET_METADATA_CHECK × Dataset (ds_* accessor broadcast)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"expression\":\"dataset_name =~ /^AE/\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // VALUE_CHECK_WITH_VARIABLE_METADATA × Record (value()+guard per-(variable,row) — B4+)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"expression\":"
                + "\"variable_name == \\\"AESEV\\\" and empty(variable_value)\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);

        // DOMAIN_PRESENCE_CHECK × Dataset (dataset-existence broadcast — B5)
        assertRunsNative(loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"expression\":\"ds_not_exists(\\\"SUPPAE\\\")\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}"), ae);
    }

}
