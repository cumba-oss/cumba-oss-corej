package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import net.cumba.corej.core.exec.ArithmeticSemantics;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueDouble;
import net.cumba.datatable.values.DataValueMissing;
import net.cumba.datatable.values.DataValueString;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Phase 3d (D83): {@code + - * /} as ordinary typed operators — the shapes the fused
 * {@code not_equal_to_*} evaluator rejected ({@code X + 1 > Y} parsed and then failed to compile),
 * the D85c/D86/D86a missing-identity propagation with commutativity as the pinned governing
 * property, and the D83c retirement of the arithmetic epsilon into the general relative tolerance.
 */
class ArithmeticFirstClassTest
{

    private static BitSet eval(String expr, IDataTable t)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr),
                EvaluationContext.builder().table(t).build());
    }


    private static BitSet bits(int... set)
    {
        BitSet b = new BitSet();
        for (int i : set)
        {
            b.set(i);
        }
        return b;
    }

    // ---- the shapes D83 exists for --------------------------------------------------------


    @Test
    @DisplayName("X + 1 > Y compiles and evaluates — the plan's §1 thesis case (D83)")
    void additionInAnOrderComparison()
    {
        IDataTable t = MockTable.of().name("LB").colDouble("X", 1.0, 5.0, null)
                .colDouble("Y", 3.0, 3.0, 3.0).build();
        // row0: 2 > 3 no; row1: 6 > 3 fires; row2: missing X ⇒ missing sum ⇒ order comparison
        // makes no decision (the shipped missing-LHS contract; the D34 total order is a later,
        // separately-owned change).
        assertEquals(bits(1), eval("X + 1 > Y", t));
    }


    @Test
    @DisplayName("arithmetic nests and appears on either comparison side")
    void nestedAndEitherSide()
    {
        IDataTable t = MockTable.of().name("LB").colDouble("A", 10.0, 8.0).colDouble("B", 4.0, 4.0)
                .colDouble("X", 1.0, 1.0).build();
        // A / 2 - B: row0 1, row1 0 — arithmetic as the LEFT operand, with precedence.
        // ⚠ Finding (report §SPEC): a comparison may not START with a parenthesised value —
        // "(A - B) / 2 != X" fails to parse ("unexpected trailing '/'"), because a leading "("
        // opens a grouped BOOLEAN expression. Parenthesised arithmetic works in any non-leading
        // position (the pctchg RHS relies on it).
        assertEquals(bits(1), eval("A / 2 - B != X", t), "arithmetic as the LEFT operand");
        // multiplication: row0/1 X*2 = 2 > 5 no; and division on the RHS of an order operator.
        assertEquals(bits(), eval("X * 2 > 5", t));
        assertEquals(bits(0, 1), eval("A / 4 >= X", t), "10/4 and 8/4 are both >= 1");
    }


    @Test
    @DisplayName("an absent operand column makes no decision; an absent compared column follows the negative-leaf contract")
    void absentColumns()
    {
        IDataTable t = MockTable.of().name("LB").colDouble("X", 2.5).colDouble("A", 10.0).build();
        // Operand column NOPE is absent: the operand plan resolves to nothing, the arithmetic
        // plan yields no vector, the predicate makes no decision — the fused shapes' shipped
        // absent-operand verdict, deliberately preserved (no EC-43 fold on value operands).
        assertEquals(bits(), eval("X != A / NOPE", t));
        // The COMPARED column absent takes the general EC-43/EC-49 negative-leaf contract the
        // fused special case used to bypass: fold to all-missing, and != fires.
        assertEquals(bits(0), eval("NOPE != A / 4", t),
                "an absent compared column folds to missing and != fires (EC-49)");
    }


    @Test
    @DisplayName("a resolved Char operand errors — arithmetic is numeric per se (R3/R4)")
    void charOperandErrors()
    {
        IDataTable t = MockTable.of().name("LB").colDouble("X", 2.5).col("C", "abc").build();
        assertThrows(ColumnTypeMismatchException.class, () -> eval("X != C / 2", t),
                "a Char operand inside the arithmetic");
        assertThrows(ColumnTypeMismatchException.class, () -> eval("C != X / 2", t),
                "a Char column compared against an arithmetic result (static NUMERIC side)");
    }

    // ---- D85c/D86/D86a — identity propagation, commutativity pinned -----------------------


    @Test
    @DisplayName("D86a identity matrix on the production row function")
    void identityMatrix()
    {
        TypedValue five = TypedValue.column(DataValueType.DOUBLE, new DataValueDouble(5.0));
        TypedValue two = TypedValue.column(DataValueType.DOUBLE, new DataValueDouble(2.0));
        TypedValue misA = TypedValue.column(DataValueType.DOUBLE,
                new DataValueMissing(MissingValue.MIS_A));
        TypedValue misB = TypedValue.column(DataValueType.DOUBLE,
                new DataValueMissing(MissingValue.MIS_B));

        assertEquals(7.0, identity(Expr.BinOp.ADD, five, two).getValueAsDouble(),
                "no missing operand ⇒ the ordinary result");
        assertSame(MissingValue.MIS_A, missingOf(identity(Expr.BinOp.ADD, misA, five)),
                ".A + 5 is .A — the identity is carried, not collapsed (D85c)");
        assertSame(MissingValue.MIS_A, missingOf(identity(Expr.BinOp.ADD, five, misA)),
                "5 + .A is .A — commutativity is the governing property (D86)");
        assertSame(MissingValue.MIS_A, missingOf(identity(Expr.BinOp.MUL, misA, misA)),
                ".A * .A is .A — one distinct identity");
        // ⭐ RE-BASED 2026-09-18: these five rows used to assert the ENCODING (`null`) rather than
        // the VALUE. An expression result is a real value or a MissingValue and never null (see
        // ScalarSemantics.computedMissing), so a created MIS is now that cell. The VERDICTS are
        // unchanged — each row was, and still is, MissingValue.MIS — which is why this re-base
        // moved no behaviour. Asserting the identity, not the encoding, is also what makes these
        // rows survive the next representation change.
        assertSame(MissingValue.MIS,
                missingOf(ExprCompiler.arithmeticCell(Expr.BinOp.ADD, misA, misB)),
                ".A + .B is MIS — two distinct identities collapse (D86)");
        assertSame(MissingValue.MIS,
                missingOf(ExprCompiler.arithmeticCell(Expr.BinOp.ADD, misB, misA)),
                ".B + .A agrees with .A + .B");
        assertSame(MissingValue.MIS,
                missingOf(ExprCompiler.arithmeticCell(Expr.BinOp.DIV, five,
                        TypedValue.column(DataValueType.DOUBLE, new DataValueDouble(0.0)))),
                "a zero divisor is MIS directly — no identity to propagate (D85/D83e)");
        assertSame(MissingValue.MIS,
                missingOf(ExprCompiler.arithmeticCell(Expr.BinOp.ADD, five,
                        TypedValue.column(DataValueType.STRING, new DataValueString("abc")))),
                "an unreadable present operand is a computed missing (D36 #8)");
        assertSame(MissingValue.MIS,
                missingOf(ExprCompiler.arithmeticCell(Expr.BinOp.SUB,
                        TypedValue.column(DataValueType.DOUBLE,
                                new DataValueDouble(Double.POSITIVE_INFINITY)),
                        TypedValue.column(DataValueType.DOUBLE,
                                new DataValueDouble(Double.POSITIVE_INFINITY)))),
                "∞ - ∞ creates NaN, and no NaN ever denotes a result — computed MIS (D85)");
    }


    private static IDataValue identity(Expr.BinOp op, TypedValue l, TypedValue r)
    {
        IDataValue cell = ExprCompiler.arithmeticCell(op, l, r);
        assertTrue(cell != null, "this matrix row expects a produced cell");
        return cell;
    }


    private static @org.jspecify.annotations.Nullable MissingValue missingOf(IDataValue cell)
    {
        return TypedValue.missingIdentityOf(cell);
    }


    @Test
    @DisplayName("combineIdentities is symmetric and associative — the D86a n-ary rule by folding")
    void combineIsACommutativeFold()
    {
        MissingValue[] domain =
        {
                null, MissingValue.MIS, MissingValue.MIS_A, MissingValue.MIS_B
        };
        for (MissingValue a : domain)
        {
            for (MissingValue b : domain)
            {
                assertSame(ArithmeticSemantics.combineIdentities(a, b),
                        ArithmeticSemantics.combineIdentities(b, a), "symmetry: " + a + "," + b);
                for (MissingValue c : domain)
                {
                    assertSame(
                            ArithmeticSemantics.combineIdentities(
                                    ArithmeticSemantics.combineIdentities(a, b), c),
                            ArithmeticSemantics.combineIdentities(a,
                                    ArithmeticSemantics.combineIdentities(b, c)),
                            "associativity: " + a + "," + b + "," + c);
                }
            }
        }
    }


    @Test
    @DisplayName("verdict-level commutativity: X != A + B and X != B + A are bit-identical")
    void verdictCommutativity()
    {
        // Row shapes: both present · left missing (.A) · right missing (.A) · both missing with
        // DIFFERENT identities (.A/.B, encoded in the quiet-NaN payload) · both plain missing.
        double encA = MissingValue.MIS_A.asDouble();
        double encB = MissingValue.MIS_B.asDouble();
        IDataTable t = MockTable.of().name("LB").colDouble("X", 3.0, 3.0, 3.0, 3.0, 3.0)
                .colDouble("A", 1.0, encA, 1.0, encA, null)
                .colDouble("B", 2.0, 2.0, encA, encB, null).build();
        BitSet ab = eval("X != A + B", t);
        BitSet ba = eval("X != B + A", t);
        assertEquals(ab, ba, "commutativity is the governing property (D86)");
        assertEquals(bits(1, 2, 3, 4), ab,
                "3 != 1+2 matches; every missing-operand row fires against the present X (3d)");
        assertEquals(eval("X != A * B", t), eval("X != B * A", t), "and for *");
    }

    // ---- D83c — the epsilon retirement, observable ----------------------------------------


    @Test
    @DisplayName("D83c: the arithmetic != takes the relative tolerance, not the retired 1e-10")
    void toleranceIsRelativeNow()
    {
        // |X - A/B| = 1e-5: beyond the retired ABSOLUTE EPSILON = 1e-10 (the fused shape fired),
        // within the general RELATIVE tolerance at 12 significant digits (1e8 · 1e-11 = 1e-3).
        IDataTable t = MockTable.of().name("LB").colDouble("X", 1.0e8)
                .colDouble("A", 2.0e8 + 2.0e-5).colDouble("B", 2.0).build();
        assertEquals(bits(), eval("X != A / B", t),
                "equal within the relative tolerance — the absolute arithmetic epsilon is gone");
        // And a genuine mismatch beyond the relative tolerance still fires.
        IDataTable t2 = MockTable.of().name("LB").colDouble("X", 1.0e8)
                .colDouble("A", 2.0e8 + 4.0e-3).colDouble("B", 2.0).build();
        assertEquals(bits(0), eval("X != A / B", t2));
    }

}
