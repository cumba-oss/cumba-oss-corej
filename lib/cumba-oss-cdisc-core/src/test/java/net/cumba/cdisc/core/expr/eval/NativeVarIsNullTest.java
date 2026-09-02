package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Map;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Native-compiler tests for the {@code var_is_null(X)} cursor predicate (T5a — per-variable
 * all-null). The verdict is TRUE when the named column is absent from the dataset, or present but
 * empty ("" / missing) for every record — mirroring the {@code variable_is_null} operation and the
 * Python reference engine's {@code (series.isnull() | (series == "")).all()}. Exercises both the
 * plain-column form (a fixed name) and the {@code varname()} cursor form used per variable in a
 * Variable Metadata Check (FDA-SD1078 / FDA-SD1149).
 */
class NativeVarIsNullTest
{

    private static Expr parse(String source)
    {
        return CheckExpressionParser.parse(source);
    }


    private static EvaluationContext ctx(IDataTable t, Map<String, Object> variables)
    {
        return EvaluationContext.builder().table(t).datasetResolver(_ -> null).variables(variables)
                .build();
    }


    private static BitSet allRows(int n)
    {
        BitSet bs = new BitSet(n);
        bs.set(0, n);
        return bs;
    }


    private static IDataTable twoVarTable()
    {
        // EMPTYVAR is empty/missing for every record; FULLVAR carries a value on row 1.
        return MockTable.of().col("USUBJID", "S01", "S02").col("EMPTYVAR", "", "")
                .col("FULLVAR", "", "x").build();
    }

    // ---- plain-column form --------------------------------------------------


    @Test
    void allEmptyColumnFiresEveryRow()
    {
        IDataTable t = twoVarTable();
        assertEquals(allRows(2),
                NativeExprEvaluator.evaluate(parse("var_is_null(EMPTYVAR)"), ctx(t, Map.of())),
                "an all-empty column is null → fires every row");
    }


    @Test
    void anyPopulatedRowIsNotNull()
    {
        IDataTable t = twoVarTable();
        assertTrue(NativeExprEvaluator.evaluate(parse("var_is_null(FULLVAR)"), ctx(t, Map.of()))
                .isEmpty(), "a column with any value is not null → no rows fire");
    }


    @Test
    void absentColumnIsNull()
    {
        IDataTable t = twoVarTable();
        assertEquals(allRows(2),
                NativeExprEvaluator.evaluate(parse("var_is_null(NOSUCHVAR)"), ctx(t, Map.of())),
                "an absent column reads as null (Python parity)");
    }


    @Test
    void plainColumnFormIsNativelySupported()
    {
        // The plain-column operand must compile natively (NativeCoverageTest contract).
        assertTrue(NativeExprEvaluator.isSupported(parse("var_is_null(AESEQ)")),
                "var_is_null on a plain column compiles natively");
    }

    // ---- varname() cursor form (per variable) -------------------------------


    @Test
    void cursorFormResolvesTheCurrentVariablePerColumn()
    {
        IDataTable t = twoVarTable();
        Expr expr = parse("var_is_null(varname())");
        // Cursor bound to the all-empty column → fires.
        assertTrue(
                NativeExprEvaluator.evaluateBroadcast(expr,
                        ctx(t, Map.of("variable_name", "EMPTYVAR"))),
                "cursor on the all-empty column → all-null");
        // Cursor bound to the populated column → does not fire.
        assertFalse(
                NativeExprEvaluator.evaluateBroadcast(expr,
                        ctx(t, Map.of("variable_name", "FULLVAR"))),
                "cursor on the populated column → not all-null");
    }


    @Test
    void membershipGuardCombinedWithCursorAllNull()
    {
        // The FDA-SD1078 / FDA-SD1149 shape: a Permissible/Expected-set guard plus the all-null
        // cursor predicate. Fires only for a variable that is both in the set AND all-empty.
        IDataTable t = twoVarTable();
        Expr expr = parse("varname() in [\"EMPTYVAR\", \"FULLVAR\"] and var_is_null(varname())");
        assertTrue(
                NativeExprEvaluator.evaluateBroadcast(expr,
                        ctx(t, Map.of("variable_name", "EMPTYVAR"))),
                "in-set and all-empty → fires");
        assertFalse(
                NativeExprEvaluator.evaluateBroadcast(expr,
                        ctx(t, Map.of("variable_name", "FULLVAR"))),
                "in-set but populated → does not fire");
        assertFalse(
                NativeExprEvaluator.evaluateBroadcast(
                        parse("varname() in [\"OTHER\"] and var_is_null(varname())"),
                        ctx(t, Map.of("variable_name", "EMPTYVAR"))),
                "all-empty but not in the set → does not fire");
    }
}
