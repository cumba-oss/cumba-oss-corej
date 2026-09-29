package net.cumba.corej.core.expr.eval;

import static net.cumba.corej.core.expr.eval.VectorLayerTest.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code coalesce(a, b[, c])} skips a <b>scalar</b> operand exactly when {@code empty()} would flag
 * it — a genuine missing <b>or</b> the empty string — so an absent char column, which folds to
 * {@code ""} ({@code D34 #3}), falls through to the next operand instead of short-circuiting on the
 * fold.
 *
 * <p>
 * Owner, 2026-09-22: <em>"the function should use empty() and not isMissing, so an empty string
 * counts as an absent value"</em>; design A of {@code PLAN-coalesce-empty-semantics}, numeric arm
 * unchanged. ⚠ The plan's premise — that the descriptor tests a narrower predicate than
 * {@code empty()} — was measured FALSE at the go: {@link Vector#isMissing(int)} already routes
 * through {@code DataValueSupport.isEmptyOrMissing}, which counts {@code ""}. This class therefore
 * pins semantics that were already true and were nowhere pinned through the compiled, absent-column
 * path, which is where the misreading came from.
 * </p>
 *
 * <p>
 * ⚠ Scalar operands only. Two pre-existing divergences from {@code empty()} are NOT pinned here and
 * have no corpus caller: an empty <b>collection</b> operand ({@code empty()} tests cardinality,
 * {@code coalesce} sees the text {@code "[]"}), and an <b>unresolved</b> operand (the whole
 * {@code coalesce} call compiles to nothing, where {@code empty()} fires every row).
 * </p>
 */
class CoalesceEmptySemanticsTest
{

    private static BitSet eval(String expression, IDataTable table)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expression),
                EvaluationContext.builder().table(table).build());
    }


    private static Vector coalesce(int rowCount, Vector... args)
    {
        Vector[] padded = args.length == 3 ? args : new Vector[]
        {
                args[0], args[1], null
        };
        return (Vector) FunctionRegistryCalls.resolve("coalesce")
                .apply(EvalRun.ofRowCount(rowCount), java.util.Arrays.asList(padded));
    }


    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    @Test
    @DisplayName("an absent char column falls through to the next operand (the plan's own example)")
    void absentCharColumnFallsThrough()
    {
        IDataTable absent = MockTable.of().name("DM").col("ALT", "UNSCHEDULED", "V2").build();
        IDataTable blank = MockTable.of().name("DM").col("VISIT", "", "")
                .col("ALT", "UNSCHEDULED", "V2").build();
        IDataTable present = MockTable.of().name("DM").col("VISIT", "V1", "V2")
                .col("ALT", "UNSCHEDULED", "V2").build();
        String expression = "coalesce(VISIT, ALT) == \"UNSCHEDULED\"";

        assertEquals(bits(0), eval(expression, absent),
                "absent VISIT folds to \"\", which coalesce skips — ALT is consulted");
        assertEquals(eval(expression, blank), eval(expression, absent),
                "absent and present-but-all-blank must agree (EC-43)");
        assertEquals(bits(), eval(expression, present), "a present VISIT wins over ALT");
    }


    @Test
    @DisplayName("the four shapes: absent char, absent numeric, present-missing, present-\"\"")
    void fourShapes()
    {
        IDataTable t = MockTable.of().col("A", "a", "", (String) null, " ")
                .col("B", "b", "b", "b", "b").build();
        Vector b = col(t, "B");

        // absent char column: the fold product is a broadcast "" (ExprCompiler, D34 #3).
        Vector absentChar = coalesce(4, ConstVector.of(""), b);
        // absent numeric-expected column: the fold product is ALL_MISSING (D34 #4).
        Vector absentNum = coalesce(4, ConstVector.of(null), b);
        for (int row = 0; row < 4; row++)
        {
            assertEquals("b", absentChar.asString(row), "absent char skipped, row " + row);
            assertEquals("b", absentNum.asString(row), "absent numeric skipped, row " + row);
        }

        Vector present = coalesce(4, col(t, "A"), b);
        assertEquals("a", present.asString(0), "a real value wins");
        assertEquals("b", present.asString(1), "present \"\" is skipped (design A)");
        assertEquals("b", present.asString(2), "a genuine missing is skipped");
        assertEquals(" ", present.asString(3),
                "whitespace is not empty — empty(\" \") is false, so it is kept");
    }


    @Test
    @DisplayName("coalesce skips exactly the rows empty() flags")
    void skipsExactlyWhatEmptyFlags()
    {
        IDataTable t = MockTable.of().col("A", "a", "", (String) null, " ")
                .col("B", "b", "b", "b", "b").build();
        BitSet empty = (BitSet) FunctionRegistryCalls.resolve("empty").apply(EvalRun.ofRowCount(4),
                List.of(col(t, "A")));
        Vector co = coalesce(4, col(t, "A"), col(t, "B"));
        BitSet fellThrough = new BitSet();
        for (int row = 0; row < 4; row++)
        {
            if ("b".equals(co.asString(row)))
            {
                fellThrough.set(row);
            }
        }
        assertEquals(bits(1, 2), empty, "fixture control: empty() flags \"\" and missing only");
        assertEquals(empty, fellThrough, "coalesce falls through on exactly the empty() rows");
    }


    @Test
    @DisplayName("numeric arm unchanged: 0 is a real value, only a missing cell is skipped")
    void numericZeroIsKept()
    {
        IDataTable t = MockTable.of().colLong("N", 0L, null).colLong("M", 7L, 7L).build();
        Vector co = coalesce(2, col(t, "N"), col(t, "M"));
        assertEquals(0.0, co.asDouble(0), "0 is not empty — kept");
        assertEquals(7.0, co.asDouble(1), "a missing numeric cell is skipped");
    }


    @Test
    @DisplayName("an empty-string LITERAL is absent: coalesce(\"\", \"a\") returns \"a\"")
    void emptyStringLiteralIsAbsent()
    {
        IDataTable t = MockTable.of().name("DM").col("X", "x", "y").build();
        assertEquals(bits(0, 1), eval("coalesce(\"\", \"a\") == \"a\"", t),
                "the literal \"\" is skipped and \"a\" is returned");
        assertEquals(bits(), eval("coalesce(\"\", \"a\") == \"\"", t),
                "the result is never the skipped \"\"");
    }


    @Test
    @DisplayName("numeric 0 and 0.0 are real values - columns and literals; only missing is skipped")
    void numericZeroAndZeroPointZeroAreKept()
    {
        // row 0 holds 0 / 0.0, row 1 a genuine missing.
        IDataTable t = MockTable.of().name("VS").colLong("L", 0L, null).colDouble("D", 0.0, null)
                .colLong("M", 7L, 7L).build();
        assertEquals(bits(0), eval("coalesce(L, M) == 0", t), "long 0 kept on row 0");
        assertEquals(bits(1), eval("coalesce(L, M) == 7", t), "missing long skipped on row 1");
        assertEquals(bits(0), eval("coalesce(D, M) == 0", t), "double 0.0 kept on row 0");
        assertEquals(bits(1), eval("coalesce(D, M) == 7", t), "missing double skipped on row 1");
        assertEquals(bits(0, 1), eval("coalesce(0, 7) == 0", t), "literal 0 kept");
        assertEquals(bits(0, 1), eval("coalesce(0.0, 7) == 0", t), "literal 0.0 kept");
    }


    @Test
    @DisplayName("every operand empty or missing -> the result is missing; arity 3 reaches c")
    void allEmptyIsMissingAndArity3ReachesC()
    {
        IDataTable t = MockTable.of().col("A", "", "").col("B", (String) null, "").col("C", "c", "")
                .build();
        Vector co = coalesce(2, col(t, "A"), col(t, "B"), col(t, "C"));
        assertEquals("c", co.asString(0), "A \"\" and B missing both skipped -> C");
        // ⚠ TypedValue.isMissing, NOT Vector.isMissing: the vector fold is also true for "", so
        // it could not tell a genuine missing result from an "" one (D34: missing != "").
        assertSame(MissingValue.MIS, co.value(1).missing(),
                "all three \"\" -> the computed MIS: a genuine missing, not \"\"");
    }


    /**
     * Once every operand is skipped, the result is D86a over the operands that are genuinely
     * missing ({@code TypedValue.missing()} non-null): one distinct identity ⇒ that operand's own
     * cell, two or more ⇒ {@code MIS}, none (every operand a present {@code ""}) ⇒ the computed
     * {@code MIS} as before ({@code PLAN-missing-identity-nonstring-functions} §3). The skip
     * predicate itself is unchanged — {@code empty()}'s (CO1) — and so is the present branch.
     */
    @Test
    @DisplayName("all operands skipped -> D86a over the missing ones; the present branch unchanged")
    void allSkippedIsD86aOverTheMissingOperands()
    {
        MissingValue a = MissingValue.MIS_A;
        MissingValue b = MissingValue.MIS_B;
        MissingValue mis = MissingValue.MIS;
        IDataTable t = MissingCellTables.of("T").str("A", a, a, a, "", "", mis, "x", a, "", a)
                .str("B", "", a, b, "", b, a, a, "", "", a)
                .str("C", "", "", "", "", "", "", "", b, a, "").build();
        Vector two = coalesce(10, col(t, "A"), col(t, "B"));
        assertSame(a, two.value(0).missing(), "coalesce(.A, \"\") is .A");
        assertSame(a, two.value(1).missing(), "coalesce(.A, .A) is .A — one distinct identity");
        assertSame(mis, two.value(2).missing(), "coalesce(.A, .B) is MIS — two identities");
        assertSame(mis, two.value(3).missing(), "coalesce(\"\", \"\") is the computed MIS");
        assertSame(b, two.value(4).missing(), "coalesce(\"\", .B) is .B");
        assertSame(mis, two.value(5).missing(), "coalesce(MIS, .A) is MIS — MIS and .A differ");
        assertNull(two.value(6).missing(), "a present operand still wins");
        assertEquals("x", two.asString(6));

        Vector three = coalesce(10, col(t, "A"), col(t, "B"), col(t, "C"));
        assertSame(mis, three.value(7).missing(), "coalesce(.A, \"\", .B) is MIS");
        assertSame(a, three.value(8).missing(),
                "coalesce(\"\", \"\", .A) is .A — the third operand");
        assertSame(a, three.value(9).missing(), "coalesce(.A, .A, \"\") is .A");
    }
}
