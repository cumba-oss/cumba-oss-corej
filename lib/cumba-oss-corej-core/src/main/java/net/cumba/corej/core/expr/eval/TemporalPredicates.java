package net.cumba.corej.core.expr.eval;

import java.util.BitSet;
import net.cumba.corej.core.exec.ScalarSemantics;
import org.jspecify.annotations.Nullable;

/**
 * The four interval predicates of SPEC §5.3 (D27/D27a/D27c, phase 3b):
 * <b>{@code date_contains(outer, inner)}</b> · <b>{@code date_overlaps(a, b)}</b> ·
 * <b>{@code time_contains(outer, inner)}</b> · <b>{@code time_overlaps(a, b)}</b> —
 * container-first, matching {@code contains(haystack, needle)}.
 *
 * <h2>⭐ {@code not date_overlaps(A, B)} IS today's {@code !=} — by construction</h2>
 * <p>
 * D27's load-bearing identity (Review 0's 21 KEEP decisions rest on it) is not implemented as a
 * parallel formula that happens to agree: {@link #dateOverlaps} <b>calls</b>
 * {@link Primitives#dateComparison} with the {@code !=} operator shape and flips the verdict. Every
 * branch of the operator — the complete-vs-complete point compare, the ∀-over-clipped-hulls
 * disjointness, the numeric pair, the mixed-shape fallthrough, and both missing short-circuits — is
 * therefore shared, and the identity cannot drift.
 * </p>
 *
 * <p>
 * Three consequences of the identity, stated because they are decisions, not accidents:
 * </p>
 * <ul>
 * <li>⭐ <b>An unpositionable operand OVERLAPS NOTHING — reversed by H1b</b> (owner, 2026-09-17).
 * Its hull is unbounded (§5.2(4)) and the {@code !=} verdict there is now {@code negate}, i.e.
 * <b>true</b>, so {@code date_overlaps} answers {@code false} and {@code not date_overlaps(A,
 * junk)} <b>fires</b> — still exactly {@code A != junk}, which is the whole point of the identity
 * and is why this bullet moves rather than the code. ⚠ It used to read <i>"an unpositionable
 * operand overlaps everything"</i>, with the warning that a rule using {@code date_overlaps}
 * positively fires on junk; the opposite warning now applies, and both are answered by guarding
 * with {@code is_complete_date}/{@code is_valid_date}. ⚑ Zero corpus users: no rule in
 * {@code rules-src/checks} spells any of the four predicates (measured 2026-09-17), so this is a
 * contract change with no verdict behind it.</li>
 * <li><b>A missing operand does not overlap</b>: the operator's missing short-circuits answer the
 * {@code !=} with {@code negate = true}, so {@code date_overlaps} answers {@code false} — and the
 * negated form fires, exactly as today's {@code !=} fires on a missing cell (D34's total order
 * pointing the same way as D37's {@code not missing → true}).</li>
 * <li><b>{@code date_contains} is deliberately NOT the mirror</b>: containment is an
 * <i>assertion</i>, so any unpositionable or missing operand answers {@code false} — the
 * conservative direction. ⭐ Since H1b that is no longer a <em>split</em>: {@code overlaps} and
 * {@code contains} now agree on junk (both {@code false}), and the two reach it independently —
 * {@code contains} by its own guard, {@code overlaps} through the {@code !=} identity. The
 * complementarity break that remains is the order family's ({@code A < junk} and {@code A >= junk}
 * both false), not the equality pair's.</li>
 * </ul>
 *
 * <p>
 * {@code date_contains} compares like-with-like bounds (lower against lower, upper against upper)
 * on the raw second-rendered hulls, so the padding suffix cancels and no precision clipping is
 * needed — the mixed lower/upper test that forces {@link IsoDateComparison}'s clipping never occurs
 * here. A day-precision outer therefore contains any instant of its day, and
 * {@code date_contains(A, A)} holds for every positionable {@code A}. There is no numeric branch: a
 * SAS-numeric operand does not position as ISO text and answers {@code false} (the D55 family is
 * where numerics acquire a date reading).
 * </p>
 *
 * <p>
 * The {@code time_*} pair mirrors the {@code date_*} pair over {@link IsoTimeBounds} /
 * {@link Primitives#timeComparison}, with D27a's base rule carried by the name: a cross-base call
 * ({@code date_overlaps(date, time)}) is unwritable rather than diagnosed.
 * </p>
 */
public final class TemporalPredicates
{

    private TemporalPredicates()
    {
    }


    /**
     * {@code date_overlaps(a, b)} — the hulls can denote the same instant: ¬(today's {@code !=}).
     */
    public static BitSet dateOverlaps(Vector a, Vector b, int rowCount)
    {
        BitSet notEqual = Primitives.dateComparison(a, b, rowCount, 0, true, true);
        notEqual.flip(0, rowCount);
        return notEqual;
    }


    /** {@code time_overlaps(a, b)} — the time twin of {@link #dateOverlaps}. */
    public static BitSet timeOverlaps(Vector a, Vector b, int rowCount)
    {
        BitSet notEqual = Primitives.timeComparison(a, b, rowCount, 0, true, true);
        notEqual.flip(0, rowCount);
        return notEqual;
    }


    /**
     * {@code date_contains(outer, inner)} — every candidate of {@code inner} lies inside
     * {@code outer}'s hull; {@code false} whenever either side is missing or unpositionable.
     */
    public static BitSet dateContains(Vector outer, Vector inner, int rowCount)
    {
        return Primitives.scan(outer, rowCount, (dv, r) ->
        {
            if (ScalarSemantics.isMissing(dv))
            {
                return false;
            }
            Object in = inner.value(r).resolved();
            if (in == null)
            {
                return false;
            }
            String o = dv.getValueAsString();
            String i = in.toString();
            return containsHull(IsoDateBounds.lower(o), IsoDateBounds.upper(o),
                    IsoDateBounds.lower(i), IsoDateBounds.upper(i));
        });
    }


    /** {@code time_contains(outer, inner)} — the time twin of {@link #dateContains}. */
    public static BitSet timeContains(Vector outer, Vector inner, int rowCount)
    {
        return Primitives.scan(outer, rowCount, (dv, r) ->
        {
            if (ScalarSemantics.isMissing(dv))
            {
                return false;
            }
            Object in = inner.value(r).resolved();
            if (in == null)
            {
                return false;
            }
            String o = dv.getValueAsString();
            String i = in.toString();
            return containsHull(IsoTimeBounds.lower(o), IsoTimeBounds.upper(o),
                    IsoTimeBounds.lower(i), IsoTimeBounds.upper(i));
        });
    }


    /** Hull inclusion over like-with-like second-rendered bounds; any {@code null} ⇒ false. */
    private static boolean containsHull(@Nullable String loOuter, @Nullable String hiOuter,
            @Nullable String loInner, @Nullable String hiInner)
    {
        if (loOuter == null || hiOuter == null || loInner == null || hiInner == null)
        {
            return false;
        }
        return loOuter.compareTo(loInner) <= 0 && hiInner.compareTo(hiOuter) <= 0;
    }

}
