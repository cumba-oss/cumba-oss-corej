package net.cumba.corej.core.expr.eval;

import net.cumba.corej.core.exec.ScalarSemantics;

/**
 * The {@code duration}-type comparison operator ({@code PLAN-engine-gap-dur-order} §5, runbook W1b
 * DO) — SPEC §5.2 applied to durations, {@link IsoDateComparison}'s three re-homed branches over
 * {@link IsoDurationBounds} hulls:
 *
 * <ol>
 * <li>two <b>points</b> — every duration without a month or year component, whatever its spelling —
 * compare as points: {@code PT90M == PT1H30M}, {@code P2D > PT5H}, {@code P1W == P7D};</li>
 * <li>otherwise the <b>∀-over-candidates</b> rule on the hulls: {@code P1M < P32D} holds (every
 * candidate of [28 d, 31 d] is shorter), {@code P1M == P30D} and {@code P1M != P30D} are both
 * {@code false} (not decided either way — exactly the date family's reading of a partial
 * date);</li>
 * <li>an <b>unpositionable</b> value — blank, junk, a date — has an unbounded hull, so all six
 * <b>predicates</b> answer {@code false}; {@code negate} then flips that (H1b), so {@code !=} fires
 * on junk while the other five stay silent.</li>
 * </ol>
 *
 * <p>
 * The verdict is reached only through the {@code duration(…)} marker (or the
 * {@code duration_between} hull operand) at compile time — never by sniffing a cell's content (§6.1
 * Q3, ruled explicit; D-W1-2a: <i>"A string is a string."</i>).
 * </p>
 */
public final class IsoDurationComparison
{

    private IsoDurationComparison()
    {
    }


    /**
     * The per-row verdict of a {@code duration}-operator comparison between two strings — the same
     * contract as {@link IsoDateComparison#fires}.
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
        IsoDurationBounds.Hull ha = IsoDurationBounds.hull(a);
        IsoDurationBounds.Hull hb = IsoDurationBounds.hull(b);
        if (ha == null || hb == null)
        {
            // Branch (3): unbounded on at least one side — every PREDICATE is false, negate flips
            // it (H1b), exactly as the date and time operators answer.
            return negate;
        }
        if (ha.isPoint() && hb.isPoint())
        {
            int cmp = Double.compare(ha.lower(), hb.lower());
            return negate != ScalarSemantics.matchCmp(cmp, direction, orEqual);
        }
        if (direction == 0)
        {
            // `==` needs two equal points (not the case here); `!=` needs disjoint hulls
            return negate && (ha.upper() < hb.lower() || ha.lower() > hb.upper());
        }
        boolean verdict = direction > 0
                ? orEqual ? ha.lower() >= hb.upper() : ha.lower() > hb.upper()
                : orEqual ? ha.upper() <= hb.lower() : ha.upper() < hb.lower();
        return negate != verdict;
    }

}
