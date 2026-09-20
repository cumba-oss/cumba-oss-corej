package net.cumba.corej.core.expr.eval;

import static net.cumba.corej.core.expr.eval.VectorLayerTest.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.regex.Pattern;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ <b>M2 of the terminal review: equality across the missing boundary, brought into D34 #5-2 — as
 * far as it can go while H1 is open.</b>
 *
 * <p>
 * Phase 6c (D117) made the four <b>order</b> operators total under D34 #5/#5-1/#5-2 and left every
 * equality family folding a {@link MissingValue} to {@code ""}. The two then contradicted each
 * other, breaking the trichotomy {@code ScalarSemantics.compareNumericTolerant}'s own javadoc
 * requires — <em>"exactly one of {@code <}, {@code ==}, {@code >} holds for any pair"</em>. D34
 * #5-2 already stated the equality half (<em>"two missings are equal iff they are the same
 * missing"</em>); it was simply unimplemented.
 * </p>
 *
 * <p>
 * The semantics is written down <b>ONCE</b> — {@code Primitives.compareWithMissing}, read by
 * {@link Primitives#orderWithMissing} and {@link Primitives#equalsWithMissing}. Trichotomy is
 * therefore structural rather than asserted per family.
 * </p>
 *
 * <p>
 * ⭐⭐ <b>The plain {@code ==}/{@code in} families and D13's {@code len} limb landed with D131a.</b>
 * They were withheld in the first pass not because the semantics was unsettled — it is settled,
 * right here — but because {@code ExprCompiler.nameRefPlan} folded an absent <em>character</em>
 * column to {@code ALL_MISSING} instead of D76's {@code ""} while its sibling {@code valueRefPlan}
 * applied D76 (H1 / D131a). Routing equality through the total order while that held turned every
 * absent char column into a <em>low missing</em> in a second operator family: measured,
 * {@code ABSENT != ABSENT} and {@code ABSENT != BLANK_COLUMN} fired on every row, and
 * {@code len(ABSENT) == 0} went false while {@code len(BLANK) == 0} stayed true — the EC-43
 * absent-equals-blank contract (D96a). With both operand positions folding to {@code ""} the whole
 * family is free, and {@code AbsentColumnTypeDefaultTest} pins that pairing.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Every assertion here has a blank-cell control beside it.</b> D34 #1/#3 and D96c keep an
 * empty string a <em>present</em> value, so the shipped half must be invisible to a blank — and the
 * cheapest way to regress it would be to widen the boundary from
 * {@code TypedValue.missingIdentityOf} to {@code ScalarSemantics.isMissing}, which no assertion
 * about missings alone would catch.
 * </p>
 */
class MissingValueEqualityTotalityTest
{

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
    @DisplayName("#5-2 — equality is the same total order read at cmp == 0")
    void equalsWithMissing_isTheTotalOrderAtZero()
    {
        MissingValue mis = MissingValue.MIS;
        MissingValue misA = MissingValue.MIS_A;
        assertTrue(Primitives.equalsWithMissing(mis, mis), ". == . — the same missing");
        assertFalse(Primitives.equalsWithMissing(mis, misA), ". != .A — distinct identities");
        assertFalse(Primitives.equalsWithMissing(mis, null), "a missing equals no present value");
        assertFalse(Primitives.equalsWithMissing(null, mis), "…from either side");
    }


    /**
     * ⭐ <b>The trichotomy, asserted as a property rather than as three fixtures.</b> For every
     * ordered pair of missing identities exactly one of {@code <}, {@code ==}, {@code >} must hold,
     * and {@code <=} must be {@code < or ==}. This is the assertion the M2 defect failed: before
     * the fix {@code MIS_A == MIS_B} and {@code MIS_A < MIS_B} were both true.
     */
    @Test
    @DisplayName("trichotomy holds over every pair of missing identities and over missing/present")
    void trichotomy_overEveryIdentityPair()
    {
        MissingValue[] ids = MissingValue.values();
        for (MissingValue l : ids)
        {
            for (MissingValue r : ids)
            {
                check(l, r);
            }
            check(l, null);
            check(null, l);
        }
    }


    private static void check(MissingValue lhs, MissingValue rhs)
    {
        boolean lt = Primitives.orderWithMissing(lhs, rhs, -1, false);
        boolean gt = Primitives.orderWithMissing(lhs, rhs, 1, false);
        boolean eq = Primitives.equalsWithMissing(lhs, rhs);
        String where = lhs + " vs " + rhs;
        assertEquals(1, (lt ? 1 : 0) + (gt ? 1 : 0) + (eq ? 1 : 0),
                "exactly one of <, ==, > must hold for " + where);
        assertEquals(lt || eq, Primitives.orderWithMissing(lhs, rhs, -1, true), "<= at " + where);
        assertEquals(gt || eq, Primitives.orderWithMissing(lhs, rhs, 1, true), ">= at " + where);
    }


    /**
     * ⭐ <b>The one row shape the temporal families move</b>: two operands that are the SAME missing
     * now compare equal, where the pre-fix path answered {@code negate} for every missing pair.
     * Missing-vs-present and two <em>different</em> missings answered "not equal" before and still
     * do — which is why no guarded corpus rule moves, and why the absent-column fold is untouched
     * here (its two positions fold to {@code MIS} and {@code ""}, a mixed pair either way).
     */
    @Test
    @DisplayName("date: the same missing on both sides is EQUAL, and <= agrees with ==")
    void dateEquality_sameMissingIsEqual()
    {
        Vector missing = ConstVector.of(null);
        IDataTable t = MockTable.of().col("A", (String) null).build();
        ColumnVector a = col(t, "A");
        assertEquals(bits(0), Primitives.dateComparison(a, missing, 1, 0, false, false),
                "date(MIS) == date(MIS) — the same missing");
        assertEquals(new BitSet(), Primitives.dateComparison(a, missing, 1, 0, false, true),
                "date_not_equal_to must be FALSE on the same missing");
        assertEquals(bits(0), Primitives.dateComparison(a, missing, 1, -1, true, false),
                "…and <= agrees with == (the trichotomy across families)");
        assertEquals(new BitSet(), Primitives.dateComparison(a, missing, 1, -1, false, false),
                "…while < is FALSE");
    }


    /** The same, on the {@code time} family — 3b's type, which shares {@code missingVerdict}. */
    @Test
    @DisplayName("time: the same missing on both sides is EQUAL too")
    void timeEquality_sameMissingIsEqual()
    {
        Vector missing = ConstVector.of(null);
        IDataTable t = MockTable.of().col("A", (String) null).build();
        ColumnVector a = col(t, "A");
        assertEquals(bits(0), Primitives.timeComparison(a, missing, 1, 0, false, false),
                "time(MIS) == time(MIS)");
        assertEquals(new BitSet(), Primitives.timeComparison(a, missing, 1, 0, false, true),
                "time_not_equal_to must be FALSE on the same missing");
    }


    /**
     * The blank control for the temporal families: a blank left cell still short-circuits to
     * {@code negate} before the right operand is resolved (D96c / SPEC §5.2(4), D102f), so
     * {@code date("") != X} still fires and the M2 fix is invisible to it.
     */
    @Test
    @DisplayName("date: a BLANK left operand keeps its negate short-circuit, untouched")
    void dateEquality_blankLeftUnchanged()
    {
        IDataTable t = MockTable.of().col("A", "").build();
        ColumnVector a = col(t, "A");
        assertEquals(new BitSet(),
                Primitives.dateComparison(a, ConstVector.of("2020-01-15"), 1, 0, false, false),
                "date(\"\") == X is FALSE");
        assertEquals(bits(0),
                Primitives.dateComparison(a, ConstVector.of("2020-01-15"), 1, 0, false, true),
                "date(\"\") != X still FIRES");
    }


    /**
     * D13's regex limb — <b>shipped</b>, because it is the one limb that neither travels with
     * {@code ==} (D81 makes {@code in} a disjunction of it) nor moves an absent column on any
     * authored shape. Re-measured 2026-09-19
     * ({@code grep -roh '=~\|!~' rules-src/checks --include=*.yaml | wc -l} from the rules
     * repository): of the <b>237</b> {@code =~}/{@code !~} sites exactly <b>one</b> carries a
     * pattern that matches the empty string ({@code CDISC-CG0112}, {@code /^\d*\.?\d*$/}) and it is
     * already guarded by {@code not empty(--DOSTXT)}. ⚑ Both figures moved on the CORE-family
     * retirement: 254 sites before it, and the second empty-matching carrier was
     * {@code CDISC-CG0112}'s CORE twin.
     */
    @Test
    @DisplayName("D13: a MissingValue matches no regex, /^$/ included — a blank still matches it")
    void d13_regex()
    {
        Pattern anchoredEmpty = Pattern.compile("^$");
        IDataTable t = MockTable.of().col("C", "x", (String) null).build();
        ColumnVector c = col(t, "C");
        assertEquals(new BitSet(), Primitives.regexFind(c, anchoredEmpty, 2, false),
                "neither a value nor a MissingValue matches /^$/");
        assertEquals(bits(0, 1), Primitives.regexFind(c, anchoredEmpty, 2, true),
                "!~ /^$/ is TRUE on the missing — a MissingValue matches nothing");
        IDataTable b = MockTable.of().col("C", "x", "").build();
        assertEquals(bits(1), Primitives.regexFind(col(b, "C"), anchoredEmpty, 2, false),
                "a BLANK still matches /^$/ (D34 #1) — the control on the boundary");
    }


    /**
     * ⭐⭐ <b>D34 #5-2 on the PLAIN {@code ==}/{@code !=} family</b> — the arm M2 reported missing.
     * Two distinct missing identities are NOT equal (they used to be, both folding to {@code ""});
     * the same identity is; and a missing equals no present value. The blank control beside it is
     * the boundary: widening {@code TypedValue.missingIdentityOf} to
     * {@code ScalarSemantics.isMissing} would pass every assertion about missings and fail here.
     */
    @Test
    @DisplayName("plain ==: distinct missings are NOT equal; the same missing is; blank untouched")
    void plainEquality_takesTheTotalOrder()
    {
        Vector misA = missingVector(MissingValue.MIS_A);
        Vector misB = missingVector(MissingValue.MIS_B);
        assertEquals(new BitSet(), Primitives.equality(misA, misB, 1, false, false, false, false),
                "MIS_A == MIS_B is FALSE — distinct identities (#5-1); it was TRUE before");
        assertEquals(bits(0), Primitives.equality(misA, misB, 1, true, false, false, false),
                "…so MIS_A != MIS_B FIRES");
        assertEquals(bits(0), Primitives.equality(misA, misA, 1, false, false, false, false),
                "MIS_A == MIS_A is TRUE — the same missing (#5-2)");

        // ⛔ Trichotomy across families, on the very pair that broke it: before this arm
        // MIS_A == MIS_B and MIS_A < MIS_B were BOTH true.
        assertTrue(Primitives.orderWithMissing(MissingValue.MIS_A, MissingValue.MIS_B, -1, false),
                "MIS_A < MIS_B (the order half, unchanged)");
    }


    /**
     * ⭐ <b>D12</b>: {@code VAR == ""} is true for an empty string and false for a
     * {@code MissingValue} — the ruling's exact words, and the one shape D76b says the
     * absent-column tie-break turns on. {@code empty()} stays the missing-or-empty predicate (D12),
     * which is what makes this a refinement rather than a loss of expressiveness.
     */
    @Test
    @DisplayName("D12: X == \"\" is TRUE for a blank and FALSE for a MissingValue")
    void d12_emptyStringLiteral()
    {
        ConstVector emptyLiteral = ConstVector.of("");
        assertEquals(new BitSet(), Primitives.equality(missingVector(MissingValue.MIS),
                emptyLiteral, 1, false, false, false, false), "«missing» == \"\" is FALSE (D12)");
        assertEquals(bits(0), Primitives.equality(missingVector(MissingValue.MIS), emptyLiteral, 1,
                true, false, false, false), "…so «missing» != \"\" FIRES");
        IDataTable blank = MockTable.of().col("C", "").build();
        assertEquals(bits(0),
                Primitives.equality(col(blank, "C"), emptyLiteral, 1, false, false, false, false),
                "BLANK == \"\" stays TRUE (D34 #1) — the control on the boundary");
    }


    /**
     * ⭐ <b>D13's membership limb, which is D12's arm reached through D81</b> ({@code in} IS a
     * disjunction of {@code ==}). Vacuous on the authored corpus — no authored list carries
     * {@code ""} (D81b) — and implemented because SPEC §4.4 requires it, with the blank control
     * beside it.
     */
    @Test
    @DisplayName("D13: a MissingValue is a member of no list, \"\" included; a blank still is")
    void d13_membership()
    {
        java.util.Set<String> optOut = java.util.Set.of("", "Y", "N");
        assertEquals(new BitSet(),
                Primitives.membership(missingVector(MissingValue.MIS), optOut, 1, false, false),
                "«missing» in [\"\",\"Y\",\"N\"] is FALSE (D13)");
        assertEquals(bits(0),
                Primitives.membership(missingVector(MissingValue.MIS), optOut, 1, true, false),
                "…so is_not_contained_by FIRES on it");
        IDataTable blank = MockTable.of().col("C", "").build();
        assertEquals(bits(0), Primitives.membership(col(blank, "C"), optOut, 1, false, false),
                "a BLANK is a present \"\" and IS a member — the control");
    }


    /**
     * ⚠⚠ A vector of ONE genuine missing identity. {@code MockTable} cannot express this: its
     * {@code colSasMissing} only ever produces {@link MissingValue#MIS}, and a {@code colDouble}
     * carrying {@code MissingValue.asDouble()}'s encoded NaN is not decoded as missing by the mock
     * cell at all — two fixtures built that way passed <b>vacuously</b> earlier in this programme.
     * A typed {@link ComputedVector} over a {@code DataValueMissing} is the only shape that
     * actually carries the identity.
     */
    private static Vector missingVector(MissingValue identity)
    {
        return ComputedVector.typed(1, net.cumba.datatable.values.DataValueType.STRING,
                _ -> new net.cumba.datatable.values.DataValueMissing(identity));
    }

}
