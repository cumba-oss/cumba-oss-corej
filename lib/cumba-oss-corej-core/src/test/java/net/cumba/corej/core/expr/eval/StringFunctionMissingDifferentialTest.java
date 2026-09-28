package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Locale;
import java.util.function.BiPredicate;
import java.util.function.Predicate;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.opentest4j.AssertionFailedError;

/**
 * Register D36 for the scalar string functions, pinned at the <b>verdict</b> level
 * ({@code PLAN-case-fold-missing-d36} §5): over {@code X, Y ∈ {"a", "A", "", MIS, MIS_A}} the
 * case-fold of a missing input is that missing — identity kept — and never {@code ""}, so
 * <ul>
 * <li>{@code upper(X) == upper(Y)} ≡ {@code equalsIgnoreCase(X, Y)} (two missings are equal iff
 * they are the same missing, D34 #5-2; a missing equals no string);</li>
 * <li>{@code upper(X) == ""} ≡ {@code upper(X) in [""]} ≡ "X is the empty string" (D34 #1 —
 * {@code ""} is present; a missing is not {@code ""}). ⚠ Only the {@code lower} spelling of the
 * membership reaches {@code caseFold}: {@code upper(X) in […]} is the case-insensitive membership
 * surface, which {@code ExprCompiler} unwraps to a probe of the bare {@code X}
 * ({@code isUpperCall}), so {@code upper(X) in [""]} tests that path, not the fold;</li>
 * <li>{@code empty(upper(X))} ≡ {@code empty(X)} (D34 #7 — the broad blank predicate is unmoved). ⚠
 * A <b>regression guard</b> for D34 #7, <b>not evidence for D36</b>: {@code empty} is true for a
 * missing <em>and</em> for {@code ""}, so it held over the old fold-to-{@code ""} as well;</li>
 * <li>the n-ary verdicts that DID move: {@code empty(concat(X, Y))} is true when either operand is
 * missing (the old {@code concat} let a missing contribute {@code ""}, so
 * {@code empty(concat(MIS, "b"))} was false), and {@code prefix(X, 2) == ""} /
 * {@code suffix(X, 2) == ""} hold for a present {@code ""} only (the old affix folded a missing
 * {@code X} to {@code ""}).</li>
 * </ul>
 * Every expected bit set is derived from a <em>reference predicate over the fixture cells</em>, not
 * from the engine, and each is asserted non-empty and non-full so an ERROR == ERROR pass cannot
 * hide. The fixture is real ({@link MissingCellTables}): {@code MockTable} cannot mint {@code .A},
 * and the {@code MIS_A} rows are what tell the mechanism apart from its one plausible slip — a
 * {@code null}-returning producer, which {@code TypedValue.resolved} folds to a fresh {@code MIS}.
 * {@link #aNullReturningFoldPassesTheMisRowsAndRedsTheMisARow} runs that slip against the same
 * assertions — a <b>helper sensitivity check</b> (it proves the assertion helpers can see the
 * slip), not a mutant of the production code; the production mutants are run as negative controls
 * against {@code BuiltinFunctions} itself.
 */
class StringFunctionMissingDifferentialTest
{

    /** The domain of one operand, in row-major order for the 5 × 5 grid. */
    static final Object[] DOMAIN =
    {
            "a", "A", "", MissingValue.MIS, MissingValue.MIS_A
    };

    private static final int N = DOMAIN.length;

    /** {@code X} at row {@code r} is {@code DOMAIN[r / 5]}, {@code Y} is {@code DOMAIN[r % 5]}. */
    static IDataTable grid()
    {
        Object[] x = new Object[N * N];
        Object[] y = new Object[N * N];
        for (int r = 0; r < N * N; r++)
        {
            x[r] = DOMAIN[r / N];
            y[r] = DOMAIN[r % N];
        }
        return MissingCellTables.of("G").str("X", x).str("Y", y).build();
    }


    private static BitSet eval(String expression, IDataTable t)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expression),
                EvaluationContext.builder().table(t).build());
    }


    /** The rows of the grid whose {@code (X, Y)} pair the reference predicate accepts. */
    private static BitSet rowsWhere(BiPredicate<Object, Object> reference)
    {
        BitSet b = new BitSet();
        for (int r = 0; r < N * N; r++)
        {
            if (reference.test(DOMAIN[r / N], DOMAIN[r % N]))
            {
                b.set(r);
            }
        }
        return b;
    }


    private static BitSet rowsWhereX(Predicate<Object> reference)
    {
        return rowsWhere((x, _) -> reference.test(x));
    }


    /** A verdict-level differential is only evidence when its baseline fires and does not flood. */
    private static void assertBaselineFires(BitSet expected)
    {
        assertFalse(expected.isEmpty(), "the reference predicate accepts no row — vacuous");
        assertTrue(expected.cardinality() < N * N,
                "the reference predicate accepts every row — vacuous");
    }


    /** The reference for {@code equalsIgnoreCase} over the domain, missings included. */
    private static boolean equalIgnoringCase(Object x, Object y)
    {
        if (x instanceof MissingValue || y instanceof MissingValue)
        {
            return x == y; // D34 #5-2: the same missing, and only that
        }
        return ((String) x).equalsIgnoreCase((String) y);
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "upper", "lower"
    })
    void foldOfXEqualsFoldOfYIffEqualsIgnoreCase(String fn)
    {
        IDataTable t = grid();
        BitSet expected = rowsWhere(StringFunctionMissingDifferentialTest::equalIgnoringCase);
        assertBaselineFires(expected);
        assertEquals(expected, eval("equalsIgnoreCase(X, Y)", t), "reference side");
        assertEquals(expected, eval(fn + "(X) == " + fn + "(Y)", t),
                fn + "(X) == " + fn + "(Y) must agree with equalsIgnoreCase(X, Y): a missing "
                        + "input's fold is that missing, so it equals the same missing and "
                        + "nothing else");
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "upper", "lower"
    })
    void foldEqualsEmptyLiteralIffXIsTheEmptyString(String fn)
    {
        IDataTable t = grid();
        BitSet expected = rowsWhereX(x -> x instanceof String s && s.isEmpty());
        assertBaselineFires(expected);
        assertEquals(expected, eval(fn + "(X) == \"\"", t),
                fn + "(X) == \"\" holds for a present \"\" only (D34 #1); a missing X is not \"\"");
        assertEquals(expected, eval(fn + "(X) in [\"\"]", t),
                fn + "(X) in [\"\"] — the membership spelling agrees with the equality one");
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "upper", "lower"
    })
    void emptyOfFoldIsEmptyOfX(String fn)
    {
        // A D34 #7 regression guard, not evidence for D36: it held over the old fold-to-"" too.
        IDataTable t = grid();
        BitSet expected = rowsWhereX(x -> x instanceof MissingValue || "".equals(x));
        assertBaselineFires(expected);
        assertEquals(expected, eval("empty(X)", t), "reference side (D34 #7)");
        assertEquals(expected, eval("empty(" + fn + "(X))", t),
                "empty(" + fn + "(X)) covers exactly what empty(X) covers");
    }


    @Test
    void emptyOfConcatIsTrueWhenEitherOperandIsMissing()
    {
        IDataTable t = grid();
        // Reference: concat(X, Y) is missing when either operand is (D36 names concat), else the
        // text X + Y — "" only when both are "".
        BitSet expected = rowsWhere((x, y) -> x instanceof MissingValue || y instanceof MissingValue
                || ("".equals(x) && "".equals(y)));
        assertBaselineFires(expected);
        BitSet actual = eval("empty(concat(X, Y))", t);
        assertEquals(expected, actual, "empty(concat(X, Y)) — a missing operand poisons concat");
        int misThenA = 3 * N; // X = MIS, Y = "a"
        assertTrue(actual.get(misThenA),
                "empty(concat(MIS, \"a\")) is now TRUE — the old concat answered \"a\" here");
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "prefix", "suffix"
    })
    void affixEqualsEmptyLiteralIffXIsTheEmptyString(String fn)
    {
        IDataTable t = grid();
        BitSet expected = rowsWhereX(x -> x instanceof String s && s.isEmpty());
        assertBaselineFires(expected);
        BitSet actual = eval(fn + "(X, 2) == \"\"", t);
        assertEquals(expected, actual,
                fn + "(X, 2) == \"\" holds for a present \"\" only — a missing X's affix is that "
                        + "missing, not \"\"");
        assertFalse(actual.get(3 * N), fn + "(MIS, 2) == \"\" is now FALSE (it used to be true)");
        assertFalse(actual.get(4 * N), fn + "(.A, 2) == \"\" is FALSE");
    }


    /**
     * The identity assertion the differential rests on, at the vector level: a fold of {@code X}
     * carries {@code X}'s own missing (D85c), and {@code ""} stays a present empty string.
     */
    private static void assertIdentityCarried(Vector fold)
    {
        for (int r = 0; r < N * N; r++)
        {
            Object x = DOMAIN[r / N];
            TypedValue tv = fold.value(r);
            if (x instanceof MissingValue mv)
            {
                assertSame(mv, tv.missing(), "row " + r + ": fold of " + mv + " carries " + mv);
            }
            else
            {
                assertNull(tv.missing(), "row " + r + ": fold of a present string is present");
                assertEquals(((String) x).toUpperCase(Locale.ROOT), fold.asString(r));
            }
        }
    }


    /** The weaker check a {@code MIS}-only suite makes — what the {@code MIS_A} rows add to. */
    private static void assertMisRowsAreMissing(Vector fold)
    {
        for (int r = 0; r < N * N; r++)
        {
            if (DOMAIN[r / N] == MissingValue.MIS)
            {
                assertSame(MissingValue.MIS, fold.value(r).missing(), "row " + r);
            }
        }
    }


    @Test
    void theProductionFoldCarriesEveryIdentity()
    {
        IDataTable t = grid();
        Vector fold = (Vector) FunctionRegistryCalls.resolve("upper")
                .apply(EvalRun.ofRowCount(N * N), java.util.List.of(VectorLayerTest.col(t, "X")));
        assertIdentityCarried(fold);
        assertMisRowsAreMissing(fold);
    }


    /**
     * The helper sensitivity check (plan §7): the one plausible mechanism slip is a producer that
     * returns {@code null} for a missing input — {@code TypedValue.resolved} then mints a fresh
     * {@code MIS}, so a suite that only ever feeds {@code MIS} stays green over it. Built here
     * through the real {@link ComputedVector} (its untyped channel, exactly as {@code caseFold}
     * produces) with the slip in the producer, and run against the same two assertions: the
     * {@code MIS}-only one passes, the identity one reds on the first {@code MIS_A} row.
     */
    @Test
    void aNullReturningFoldPassesTheMisRowsAndRedsTheMisARow()
    {
        IDataTable t = grid();
        Vector x = VectorLayerTest.col(t, "X");
        Vector mutant = new ComputedVector(N * N, DataValueType.STRING,
                row -> x.value(row).missing() != null ? null
                        : x.asString(row).toUpperCase(Locale.ROOT));
        assertMisRowsAreMissing(mutant); // a MIS-only suite cannot see the slip …
        AssertionFailedError red = assertThrows(AssertionFailedError.class,
                () -> assertIdentityCarried(mutant), "… the MIS_A rows must");
        assertTrue(red.getMessage().contains("carries .A"),
                "the red is the .A identity row, not something else: " + red.getMessage());
        int firstMisA = N * 4; // X = MIS_A from row 20
        assertSame(MissingValue.MIS, mutant.value(firstMisA).missing(),
                "the slip mints MIS where .A was owed — the difference the MIS_A rows pin");
    }

}
