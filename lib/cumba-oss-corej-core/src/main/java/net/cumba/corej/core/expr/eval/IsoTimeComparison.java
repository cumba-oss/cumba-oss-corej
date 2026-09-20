package net.cumba.corej.core.expr.eval;

import net.cumba.corej.core.exec.ScalarSemantics;
import org.jspecify.annotations.Nullable;

/**
 * The {@code time}-type comparison operator — SPEC §5.2 applied to time-of-day, mirroring
 * {@link IsoDateComparison}'s three re-homed branches (D71a(1)–(3)) over {@link IsoTimeBounds}
 * hulls:
 *
 * <ol>
 * <li>normalise both operands to the common precision of their cores, <b>floored at minute
 * precision</b> (the time analogue of §5.2(1)'s day floor — see below);</li>
 * <li>if both cores are <b>complete</b> — minute precision or finer — compare as <b>points</b> at
 * the coarser of the two core precisions;</li>
 * <li>otherwise apply the <b>∀-over-candidates</b> rule to the clipped hulls;</li>
 * <li>an <b>unpositionable</b> value — blank, junk, {@code 25:00}, a date, a datetime — has an
 * unbounded hull, so all six <b>predicates</b> answer {@code false} with no per-operator guard
 * (§5.2(4)); ⭐ H1b then flips that by {@code negate}, so {@code time_not_equal_to} <b>fires</b>
 * while the other five stay silent.</li>
 * </ol>
 *
 * <p>
 * ⚠ <b>SPEC CHOICE — the completeness floor for times is the MINUTE.</b> §5.2 fixes the date floor
 * at the calendar day and D22 names {@code T10} a partial time, but no ruling places the
 * complete/partial boundary for time-of-day. The minute is chosen as the direct analogue of the
 * owner's day ruling (<i>"a complete date is a known point at its own granularity"</i>): a
 * clinically recorded time is a minute ({@code HH:MM} is the collected shape; seconds are rare), so
 * {@code 10:30} against {@code 10:30:45} compares equal at the coarser precision — exactly as
 * {@code 2012-06-15} against {@code 2012-06-15T14:00} compares equal at the day — while an
 * hour-only {@code T10} is a partial spanning {@code [10:00:00, 10:59:59]} and takes the ∀ rule.
 * </p>
 *
 * <p>
 * Two consequences carried over deliberately from the date operator: the flooring makes a
 * minute-precision operand a <b>point</b> under the hull rule, so the mixed lower/upper tests stay
 * sound; and complementarity breaks for unpositionable operands — {@code A == junk} and
 * {@code A != junk} are both {@code false} (<i>you cannot compare against a value you do not
 * have</i>).
 * </p>
 */
public final class IsoTimeComparison
{

    private IsoTimeComparison()
    {
    }


    /**
     * The per-row verdict of a {@code time}-operator comparison between two time-of-day strings —
     * the same contract as {@link IsoDateComparison#fires}.
     *
     * @param a
     *            the left operand's string form.
     * @param b
     *            the right operand's string form.
     * @param direction
     *            {@code 0} = equality, {@code 1} = greater, {@code -1} = less.
     * @param orEqual
     *            whether the operator admits equality ({@code >=} / {@code <=}; also set for
     *            {@code ==} / {@code !=}).
     * @param negate
     *            whether the operator is the negated form ({@code !=}).
     * @return {@code true} iff the leaf fires for this row.
     */
    public static boolean fires(String a, String b, int direction, boolean orEqual, boolean negate)
    {
        String coreA = IsoTimeBounds.core(a);
        String coreB = IsoTimeBounds.core(b);
        if (coreA != null && coreA.length() >= IsoTimeBounds.MINUTE_PRECISION && coreB != null
                && coreB.length() >= IsoTimeBounds.MINUTE_PRECISION)
        {
            // Branch (2): two complete times are points at their own granularity, compared at
            // the coarser of the two core precisions — the exact analogue of the date operator's
            // compareIso fast path (a core is already validated, UTC-rendered and
            // fraction-stripped, so the truncation-then-compare is the whole comparison).
            int prec = Math.min(coreA.length(), coreB.length());
            // clipKnown, not clip: both cores are non-null by the guard above, and clip answers
            // null for exactly a null bound — so the total form is the honest one here and keeps
            // the nullable overload for the hull bounds below, which really can be unbounded.
            int cmp = clipKnown(coreA, prec).compareTo(clipKnown(coreB, prec));
            return negate != ScalarSemantics.matchCmp(cmp, direction, orEqual);
        }
        int prec = commonPrecision(coreA, coreB);
        String loA = clip(IsoTimeBounds.lower(a), prec);
        String hiA = clip(IsoTimeBounds.upper(a), prec);
        String loB = clip(IsoTimeBounds.lower(b), prec);
        String hiB = clip(IsoTimeBounds.upper(b), prec);
        if (loA == null || hiA == null || loB == null || hiB == null)
        {
            // Branch (4): unbounded on at least one side — every one of the six PREDICATES is
            // false, and negate flips it (H1b). Mirrors IsoDateComparison exactly, including the
            // reason: the two classes are documented as mirrors, and letting them disagree on the
            // unpositionable verdict is precisely the kind of silent drift that doc promises not to
            // have. Zero corpus users today (the time() conversion is how a comparison reaches it).
            return negate;
        }
        if (direction == 0)
        {
            return negate ? disjoint(loA, hiA, loB, hiB) : samePoint(loA, hiA, loB, hiB);
        }
        boolean verdict = direction > 0 ? orEqual ? loA.compareTo(hiB) >= 0 : loA.compareTo(hiB) > 0
                : orEqual ? hiA.compareTo(loB) <= 0 : hiA.compareTo(loB) < 0;
        return negate != verdict;
    }


    /**
     * The width both hulls are clipped to: the finer of the two operands' own precisions, floored
     * at a whole minute. Flooring is what makes a minute-precision operand a <b>point</b> — the
     * time analogue of {@link IsoDateComparison}'s day floor.
     */
    private static int commonPrecision(@Nullable String coreA, @Nullable String coreB)
    {
        return Math.max(precisionOf(coreA), precisionOf(coreB));
    }


    private static int precisionOf(@Nullable String core)
    {
        if (core == null)
        {
            return IsoTimeBounds.MINUTE_PRECISION;
        }
        return Math.min(IsoTimeBounds.SECOND_PRECISION,
                Math.max(IsoTimeBounds.MINUTE_PRECISION, core.length()));
    }


    private static @Nullable String clip(@Nullable String bound, int precision)
    {
        return bound == null ? null : clipKnown(bound, precision);
    }


    /** {@link #clip} for a bound that is known to be positioned — total by construction. */
    private static String clipKnown(String aBound, int aPrecision)
    {
        return aBound.length() > aPrecision ? aBound.substring(0, aPrecision) : aBound;
    }


    /** {@code A != B} — the two candidate sets cannot overlap. */
    private static boolean disjoint(String loA, String hiA, String loB, String hiB)
    {
        return hiA.compareTo(loB) < 0 || loA.compareTo(hiB) > 0;
    }


    /** {@code A == B} — both operands are single candidates, and the same one. */
    private static boolean samePoint(String loA, String hiA, String loB, String hiB)
    {
        return loA.equals(hiA) && loB.equals(hiB) && loA.equals(loB);
    }

}
