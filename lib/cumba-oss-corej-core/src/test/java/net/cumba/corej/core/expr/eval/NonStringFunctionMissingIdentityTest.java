package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.function.BiPredicate;
import java.util.stream.Stream;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.opentest4j.AssertionFailedError;

/**
 * Register {@code D85c} / {@code D86a} for the <b>non-string</b> VALUE functions, pinned at the
 * <b>verdict</b> level ({@code PLAN-missing-identity-nonstring-functions} §3 evidence (ii) and
 * (iii)): {@code len}, {@code char}, {@code abs} / {@code round} / {@code floor} / {@code ceil},
 * {@code num}, {@code year} / {@code month} / {@code day}, {@code earliest_possible} /
 * {@code latest_possible}, {@code coalesce}, {@code dy}, {@code date_diff_days} and {@code colref}
 * answer a missing input's <b>own</b> missing — {@code .A} stays {@code .A} — never a fresh
 * {@code MIS}.
 *
 * <p>
 * Over {@code X, Y ∈ {p1, p2, MIS, .A, .B}} (a 5 × 5 grid), with {@code p1 < p2} two present inputs
 * on which the function is strictly monotonic, so the function preserves both equality and order of
 * present values:
 * </p>
 * <ul>
 * <li>{@code f(X) == f(Y)} holds iff {@code X} and {@code Y} are the same grid value — two missings
 * are equal iff they are the same missing (D34 #5-2), so {@code f(.A) == f(.B)} is FALSE and
 * {@code f(.A) == f(.A)} TRUE. Before this plan both sides were the computed {@code MIS}, so
 * {@code f(.A) == f(.B)} was TRUE;</li>
 * <li>{@code f(X) < f(Y)} follows the total order of D34 #5: every missing below every present
 * value, two missings by their value byte (#5-1: {@code MIS} 64 &lt; {@code .A} 65 &lt; {@code .B}
 * 66). Before this plan {@code f(.A) < f(.B)} was {@code MIS < MIS}, false.</li>
 * </ul>
 * <p>
 * Every expected bit set is derived from a <em>reference over the fixture cells</em>
 * ({@link #rank}), not from the engine, and is asserted non-empty and non-full; the raw
 * {@code X == Y} / {@code X < Y} over the same columns is asserted against the same reference as a
 * control that the reference states the engine's order. The fixture is real
 * ({@link MissingCellTables}), since {@code MockTable} cannot mint {@code .A}. The boundary is
 * {@link TypedValue#missing()}, never {@code Vector.isMissing} (the F3 fold, which is true for
 * {@code ""} too).
 * </p>
 * <p>
 * ⚠ The byte-identical findings snapshot is blind to all of this — {@code testdata/study} is
 * Dataset-JSON, which cannot encode a special missing, and no {@code .cdt} holds one — so this
 * class, the identity rows of {@code BuiltinFunctionsTest} / {@code StudyDayTest} /
 * {@code CoalesceEmptySemanticsTest} / {@code PerRowListProducersNullFreeTest} (and, until runbook
 * W8 retired it with the computed target, {@code ComputedTargetOperationTest}) are the evidence.
 * </p>
 */
class NonStringFunctionMissingIdentityTest
{

    private static final int N = 5;

    /**
     * One function under test: the call template ({@code %s} is the operand), the operand column's
     * type, and its two present inputs {@code p1 < p2}.
     */
    record Case(String call, DataValueType type, Object p1, Object p2)
    {

        Object[] domain()
        {
            return new Object[]
            {
                    p1, p2, MissingValue.MIS, MissingValue.MIS_A, MissingValue.MIS_B
            };
        }


        String on(String operand)
        {
            return call.replace("%s", operand);
        }


        @Override
        public String toString()
        {
            return call + " over " + type;
        }
    }

    static Stream<Arguments> cases()
    {
        return Stream.of(new Case("len(%s)", DataValueType.STRING, "a", "bb"),
                new Case("char(%s)", DataValueType.STRING, "A", "B"),
                new Case("abs(%s)", DataValueType.DOUBLE, 1.5, 2.5),
                new Case("abs(%s)", DataValueType.LONG, 1L, 2L),
                new Case("round(%s)", DataValueType.DOUBLE, 1.0, 2.0),
                new Case("round(%s)", DataValueType.LONG, 1L, 2L),
                new Case("floor(%s)", DataValueType.DOUBLE, 1.0, 2.0),
                new Case("floor(%s)", DataValueType.LONG, 1L, 2L),
                new Case("ceil(%s)", DataValueType.DOUBLE, 1.0, 2.0),
                new Case("ceil(%s)", DataValueType.LONG, 1L, 2L),
                new Case("num(%s)", DataValueType.STRING, "1.5", "2.5"),
                new Case("num(%s)", DataValueType.DOUBLE, 1.5, 2.5),
                new Case("num(%s)", DataValueType.LONG, 1L, 2L),
                new Case("abs(num(%s))", DataValueType.STRING, "1.5", "2.5"),
                new Case("year(%s)", DataValueType.STRING, "2021", "2022"),
                new Case("month(%s)", DataValueType.STRING, "2021-01", "2021-02"),
                new Case("day(%s)", DataValueType.STRING, "2021-01-01", "2021-01-02"),
                new Case("earliest_possible(%s)", DataValueType.STRING, "2021-01-01", "2021-01-02"),
                new Case("latest_possible(%s)", DataValueType.STRING, "2021-01-01", "2021-01-02"),
                new Case("coalesce(%s, Z)", DataValueType.STRING, "a", "b"),
                new Case("dy(%s, R)", DataValueType.STRING, "2021-01-02", "2021-01-03"),
                // runbook W2b: the ported date_diff_days hands a missing input through as dy does
                new Case("date_diff_days(%s, R)", DataValueType.STRING, "2021-01-02", "2021-01-03"),
                // The first hop names a DOUBLE column (IDVAR-style): colref(X) reads VX, whose
                // cells are the grid values; the identity is the second hop's (TR §E).
                new Case("colref(%s)", DataValueType.DOUBLE, 1.5, 2.5)).map(Arguments::of);
    }


    /**
     * {@code X} at row {@code r} is {@code domain[r / 5]}, {@code Y} is {@code domain[r % 5]};
     * {@code Z} is all {@code ""} (the skipped second operand of {@code coalesce}) and {@code R}
     * the constant reference date of {@code dy}. For {@code colref}, {@code X} / {@code Y} are
     * first-hop columns naming {@code VX} / {@code VY} on every row, and those DOUBLE columns carry
     * the grid values.
     */
    static IDataTable grid(Case c)
    {
        Object[] domain = c.domain();
        Object[] x = new Object[N * N];
        Object[] y = new Object[N * N];
        Object[] z = new Object[N * N];
        Object[] ref = new Object[N * N];
        for (int r = 0; r < N * N; r++)
        {
            x[r] = domain[r / N];
            y[r] = domain[r % N];
            z[r] = "";
            ref[r] = "2021-01-01";
        }
        MissingCellTables t = MissingCellTables.of("G");
        if (isColref(c))
        {
            Object[] nameX = new Object[N * N];
            Object[] nameY = new Object[N * N];
            java.util.Arrays.fill(nameX, "VX");
            java.util.Arrays.fill(nameY, "VY");
            return t.str("X", nameX).str("Y", nameY).dbl("VX", x).dbl("VY", y).build();
        }
        switch (c.type())
        {
        case DOUBLE -> t.dbl("X", x).dbl("Y", y);
        case LONG -> t.lng("X", x).lng("Y", y);
        default -> t.str("X", x).str("Y", y);
        }
        return t.str("Z", z).str("R", ref).build();
    }


    /** {@code f(X)} compiled as a value expression over the grid. */
    private static Vector valueOf(Case c, IDataTable t)
    {
        Vector result = ExprCompiler.evaluateValueExpression(CheckExpressionParser.parse(c.on("X")),
                EvaluationContext.builder().table(t).build());
        assertNotNull(result, c + " compiles to a value vector");
        return result;
    }


    private static BitSet eval(String expression, IDataTable t)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expression),
                EvaluationContext.builder().table(t).build());
    }


    /**
     * The D34 #5 order key of a grid value: a missing ranks by its value byte, below every present
     * value; {@code p1} below {@code p2}.
     */
    private static int rank(Object[] domain, Object v)
    {
        if (v instanceof MissingValue mv)
        {
            return mv.getValue(); // #5-1: MIS 64 < .A 65 < .B 66
        }
        return v.equals(domain[0]) ? 1000 : 1001;
    }


    private static BitSet rowsWhere(Object[] domain, BiPredicate<Object, Object> reference)
    {
        BitSet b = new BitSet();
        for (int r = 0; r < N * N; r++)
        {
            if (reference.test(domain[r / N], domain[r % N]))
            {
                b.set(r);
            }
        }
        return b;
    }


    private static void assertBaselineFires(BitSet expected)
    {
        assertFalse(expected.isEmpty(), "the reference accepts no row — vacuous");
        assertTrue(expected.cardinality() < N * N, "the reference accepts every row — vacuous");
    }


    /** Whether {@code f}'s result is text (a STRING vector), not a number. */
    private static boolean isTextualResult(Case c)
    {
        // colref is not one: its present second hop is the named DOUBLE cell's text
        // (ScalarSemantics.resolvedString), which the plain `<` reads as the number it spells.
        return c.call().startsWith("earliest_possible") || c.call().startsWith("latest_possible")
                || c.call().startsWith("coalesce");
    }


    private static boolean isColref(Case c)
    {
        return c.call().startsWith("colref");
    }


    /**
     * The column holding the grid values behind operand {@code operand} — the operand itself, or
     * for {@code colref} the column its first hop names — which the raw controls compare.
     */
    private static String raw(Case c, String operand)
    {
        return isColref(c) ? "V" + operand : operand;
    }


    /** Row index of the grid pair {@code (domain[i], domain[j])}. */
    private static int row(int i, int j)
    {
        return i * N + j;
    }

    private static final int A = 3;

    private static final int B = 4;

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void equalityOfTwoResultsIsIdentityOfTheirInputs(Case c)
    {
        IDataTable t = grid(c);
        Object[] domain = c.domain();
        BitSet expected = rowsWhere(domain, (x, y) -> rank(domain, x) == rank(domain, y));
        assertBaselineFires(expected);
        assertEquals(expected, eval(raw(c, "X") + " == " + raw(c, "Y"), t),
                "control: the raw column equality");
        BitSet actual = eval(c.on("X") + " == " + c.on("Y"), t);
        assertEquals(expected, actual,
                c + ": f(X) == f(Y) iff X and Y are the same value — a missing input's result is"
                        + " that missing, which equals the same missing and nothing else");
        assertFalse(actual.get(row(A, B)), c + ": f(.A) == f(.B) is FALSE (D34 #5-2)");
        assertTrue(actual.get(row(A, A)), c + ": f(.A) == f(.A) is TRUE");
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void orderOfTwoResultsFollowsTheMissingByteOrder(Case c)
    {
        IDataTable t = grid(c);
        Object[] domain = c.domain();
        // ⚠ A TEXTUAL result (the hull bounds, coalesce over strings) compared untagged with `<`
        // compiles to the numeric-only plain comparison, which never fires for two PRESENT strings
        // (HullBoundsBareOperandProbeTest; the date spelling needs the date() tag). That is
        // present-value behaviour this plan does not touch, so the one present-present pair is
        // expected false for them; every pair involving a missing is the D34 #5 order under test.
        boolean textual = isTextualResult(c);
        BitSet order = rowsWhere(domain, (x, y) -> rank(domain, x) < rank(domain, y));
        BitSet expected = rowsWhere(domain, (x, y) -> rank(domain, x) < rank(domain, y)
                && !(textual && !(x instanceof MissingValue) && !(y instanceof MissingValue)));
        assertBaselineFires(expected);
        if (c.type() != DataValueType.STRING)
        {
            // A raw order comparison over a Char column is a column-type mismatch (the gate), so
            // the engine-side control exists for the numeric columns only; the reference itself is
            // the engine-independent rank either way (the full order: the raw columns are
            // numeric, so their present pair orders even where f's textual result would not).
            assertEquals(order, eval(raw(c, "X") + " < " + raw(c, "Y"), t),
                    "control: the raw column order");
        }
        BitSet actual = eval(c.on("X") + " < " + c.on("Y"), t);
        assertEquals(expected, actual,
                c + ": f(X) < f(Y) follows D34 #5 — missings below every value, by value byte");
        assertTrue(actual.get(row(A, B)), c + ": f(.A) < f(.B) (65 < 66)");
        assertFalse(actual.get(row(B, A)), c + ": not f(.B) < f(.A)");
        assertTrue(actual.get(row(2, A)), c + ": f(MIS) < f(.A) (64 < 65)");
    }


    /**
     * The vector-level identity every verdict above rests on: {@code f(X)} carries {@code X}'s own
     * missing (D85c) and a present input gives a present result.
     */
    private static void assertIdentityCarried(Object[] domain, Vector result)
    {
        for (int r = 0; r < N * N; r++)
        {
            Object x = domain[r / N];
            TypedValue tv = result.value(r);
            if (x instanceof MissingValue mv)
            {
                assertSame(mv, tv.missing(),
                        "row " + r + ": the result of " + mv + " carries " + mv);
            }
            else
            {
                assertNull(tv.missing(), "row " + r + ": a present input gives a present result");
            }
        }
    }


    /** The weaker check a {@code MIS}-only suite makes — what the {@code .A} rows add to. */
    private static void assertMisRowsAreMissing(Object[] domain, Vector result)
    {
        for (int r = 0; r < N * N; r++)
        {
            if (domain[r / N] == MissingValue.MIS)
            {
                assertSame(MissingValue.MIS, result.value(r).missing(), "row " + r);
            }
        }
    }


    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void theProductionVectorCarriesEveryIdentity(Case c)
    {
        IDataTable t = grid(c);
        Vector result = valueOf(c, t);
        assertIdentityCarried(c.domain(), result);
        assertMisRowsAreMissing(c.domain(), result);
    }


    /**
     * The helper sensitivity check (§3 evidence (iii)): the one plausible slip is a producer that
     * answers {@code null} for a missing input — {@code TypedValue.resolved} then mints a fresh
     * {@code MIS}, so a suite that only feeds {@code MIS} stays green over it. Built through the
     * real untyped {@link ComputedVector} with the slip in the producer, as {@code len} was before
     * this plan, and run against the same two assertions: the {@code MIS}-only one passes, the
     * identity one reds on the first {@code .A} row. (The production mutant — the same slip put
     * back into {@code BuiltinFunctions} — is run as a negative control against the suite, and is
     * recorded in the plan's status file.)
     */
    @Test
    void aNullReturningProducerPassesTheMisRowsAndRedsTheMisARow()
    {
        Case c = new Case("len(%s)", DataValueType.STRING, "a", "bb");
        IDataTable t = grid(c);
        Vector x = VectorLayerTest.col(t, "X");
        Vector mutant = new ComputedVector(N * N, DataValueType.LONG,
                row -> x.value(row).missing() != null ? null : (long) x.asString(row).length());
        assertMisRowsAreMissing(c.domain(), mutant); // a MIS-only suite cannot see the slip …
        AssertionFailedError red = assertThrows(AssertionFailedError.class,
                () -> assertIdentityCarried(c.domain(), mutant), "… the .A rows must");
        assertTrue(red.getMessage().contains("carries .A"),
                "the red is the .A identity row, not something else: " + red.getMessage());
        assertSame(MissingValue.MIS, mutant.value(row(A, 0)).missing(),
                "the slip mints MIS where .A was owed — the difference the .A rows pin");
    }


    /**
     * The production shape of a SAS special missing: a DOUBLE cell whose quiet-{@code NaN} payload
     * carries the marker byte (a {@code DataValueDouble}, not a {@code DataValueMissing}, which is
     * what the grid's {@link MissingCellTables} cells are). {@code abs}, {@code num} and
     * {@code coalesce} hand that cell's identity through as well; a payload-less {@code NaN} is the
     * plain {@code MIS}.
     */
    @Test
    void aNanEncodedDoubleSourceKeepsItsIdentity()
    {
        IDataTable t = MissingCellTables.of("T").dbl("X", 1.5, MissingValue.MIS_A.asDouble(),
                MissingValue.MIS_B.asDouble(), Double.NaN).str("Z", "", "", "", "").build();
        Vector source = VectorLayerTest.col(t, "X");
        // Row r + 1 carries identities[r]; row 0 is present.
        MissingValue[] identities =
        {
                MissingValue.MIS_A, MissingValue.MIS_B, MissingValue.MIS
        };
        for (int r = 0; r < identities.length; r++)
        {
            assertFalse(source.value(r + 1).cell().getValue() instanceof MissingValue,
                    "row " + (r + 1) + ": the fixture cell is a NaN-encoded double");
            assertSame(identities[r], source.value(r + 1).missing(), "control: the source decodes");
        }
        for (String call : List.of("abs(X)", "num(X)", "coalesce(X, Z)"))
        {
            Vector result = ExprCompiler.evaluateValueExpression(CheckExpressionParser.parse(call),
                    EvaluationContext.builder().table(t).build());
            assertNotNull(result, call);
            assertNull(result.value(0).missing(),
                    call + ": a present input gives a present result");
            for (int r = 0; r < identities.length; r++)
            {
                assertSame(identities[r], result.value(r + 1).missing(), call + " row " + (r + 1)
                        + ": the NaN-encoded " + identities[r] + " is carried");
            }
        }
    }


    /** The present inputs the grid relies on really are present (a non-vacuity control). */
    @Test
    void theGridsPresentInputsArePresent()
    {
        List<Arguments> all = cases().toList();
        assertEquals(23, all.size(),
                "every function of §2 with a scalar result is in the grid, colref included");
        for (Arguments a : all)
        {
            Case c = (Case) a.get()[0];
            IDataTable t = grid(c);
            Vector result = valueOf(c, t);
            assertNull(result.value(row(0, 0)).missing(), c + ": f(p1) is present");
            assertNull(result.value(row(1, 0)).missing(), c + ": f(p2) is present");
        }
    }

}
