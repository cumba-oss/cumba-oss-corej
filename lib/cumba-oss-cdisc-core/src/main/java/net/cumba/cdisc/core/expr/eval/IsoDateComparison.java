package net.cumba.cdisc.core.expr.eval;

import net.cumba.cdisc.core.exec.ScalarSemantics;
import org.jspecify.annotations.Nullable;

/**
 * Q16 — the {@code date_*} comparison family, evaluated over the <b>hull</b> a possibly-incomplete
 * ISO-8601 value denotes rather than over its raw text.
 *
 * <h2>The rule</h2>
 * <p>
 * A partial date denotes a <b>range</b>, and a comparison quantifies over it: <b>{@code A op B}
 * means "A op <i>every</i> candidate of B"</b> — uniform &forall; for all six operators, two-valued
 * (there is deliberately no INDETERMINATE). Written over the bounds:
 * </p>
 * <table border="1">
 * <caption>The &forall; test per operator</caption>
 * <tr>
 * <th>operator</th>
 * <th>test</th>
 * </tr>
 * <tr>
 * <td>{@code A < B}</td>
 * <td>{@code upper(A) < lower(B)}</td>
 * </tr>
 * <tr>
 * <td>{@code A > B}</td>
 * <td>{@code lower(A) > upper(B)}</td>
 * </tr>
 * <tr>
 * <td>{@code A <= B}</td>
 * <td>{@code upper(A) <= lower(B)}</td>
 * </tr>
 * <tr>
 * <td>{@code A >= B}</td>
 * <td>{@code lower(A) >= upper(B)}</td>
 * </tr>
 * <tr>
 * <td>{@code A == B}</td>
 * <td>both hulls are points <b>and</b> equal</td>
 * </tr>
 * <tr>
 * <td>{@code A != B}</td>
 * <td>the hulls are <b>disjoint</b></td>
 * </tr>
 * </table>
 *
 * <h2>&#9873;&#9873; Why complete-vs-complete does not go through the hulls</h2>
 * <p>
 * {@link IsoDateBounds} renders every bound at <b>second</b> precision, so a day-precision value's
 * hull is {@code [T00:00:00, T23:59:59]} — a span, not a point. That is exactly right for EC-46's
 * extreme selection, which only ever compares a lower against a lower or an upper against an upper
 * (the padding suffix cancels). The &forall; rule above does the opposite: it mixes a
 * <em>lower</em> against an <em>upper</em>, and there the suffix no longer cancels. Applied naively
 * it would make {@code 2026-01-17 >= 2026-01-17} <b>false</b> ({@code T00:00:00 >= T23:59:59}),
 * silencing every {@code date_greater_than_or_equal_to} rule on equal dates.
 * </p>
 * <p>
 * &#8658; When <b>both</b> operands are calendar-complete dates
 * ({@link CalendarDates#isCompleteDate}, i.e. day precision or finer) <b>and both cores are
 * readable</b> ({@link IsoDateBounds#isReadableCore}) the comparison is delegated to
 * {@link ScalarSemantics#compareIso} + {@link ScalarSemantics#matchCmp} over the operands'
 * <b>cores</b> ({@link IsoDateBounds#core} — trimmed, normalised to UTC, fractional seconds
 * stripped; {@code Fix #250}). A value's precision is its <b>uncertainty</b> only when it is
 * incomplete; a complete date is a known point at its own granularity, and two complete dates are
 * compared at the coarser of the two core precisions — the owner's 2026-08-13 ruling: <i>"if the
 * date is complete in both and none is partial, we take the minimal precision of both values and
 * use fast path compare (no interval). We only take the interval if one of the values has a
 * definitive partial."</i>
 * </p>
 *
 * <h2>&#9873;&#9873; The readability conjunct ({@code Fix #229}) — why it is not redundant</h2>
 * <p>
 * {@link IsoDateBounds#core} strips <b>one</b> decoration; {@link CalendarDates#isCompleteDate}
 * normalises its argument and therefore strips a <b>second</b> time. On a <i>stacked</i> tail the
 * two disagree, and the fast path then compared the <b>raw</b> operands: {@code 2012-06-15ZZ <
 * 2012-06-16ZZ} answered {@code true} for two values whose hulls are both {@code null} and which
 * {@link CalendarDates#isValidDate} calls invalid dates. Asking
 * {@link IsoDateBounds#isReadableCore} — the <b>same</b> predicate {@code IsoDateBounds.halfBound}
 * and {@link IsoDateBounds#isDetermined} ask, deliberately not a copy — sends such an operand to
 * the hull path instead, where its {@code null} bound answers {@code false} for all six operators.
 * &#9888; The conjuncts stay load-bearing now that the <b>cores</b> are compared:
 * {@code compareIso} strips a trailing decoration itself, so without them
 * {@code core("2012-06-15ZZ")} = {@code "2012-06-15Z"} would read as a date again — measured,
 * dropping them reopens all 2 565 moved pairs.
 * </p>
 * <p>
 * &#9888; {@code Fix #229} is strictly a <b>narrowing</b>: measured differentially over the whole
 * {@code IsoDateCorpus} <b>pair</b> space, 2 565 ordered pairs / 7 695 verdicts move and every one
 * of them moves {@code true} &rarr; {@code false}, always with an operand
 * {@link CalendarDates#isValidDate} already rejects. So that fix moved no comparison between two
 * valid dates.
 * </p>
 * <p>
 * &#9888;&#9888; <b>What {@code Fix #229} did NOT close — and {@code Fix #250} then did.</b> The
 * conjuncts gate on the <b>cores</b>, and the branch used to compare the <b>raw</b> operands — the
 * pre-Q16 path verbatim. {@code compareIso} normalises differently from {@link IsoDateBounds#core}
 * (no trim, no fractional-second strip, and its {@link ScalarSemantics#detectIsoPrecision} tier is
 * a <i>length</i> bucket), so {@code "2012-06-15.000" < "2012-06-15T10:30:45"} answered
 * {@code true}: the first read as hour precision and {@code '.' < 'T'}. Measured over
 * {@code IsoDateCorpus}: <b>260</b> ordered <i>valid/valid</i> readable-core pairs / 1 040 verdicts
 * read differently raw than through their cores — every moved pair carries a {@code '.'}-tail on at
 * least one side, and every one flips to <i>equal</i> under the core reading (520 verdicts
 * {@code false} &rarr; {@code true}, 520 the other way). {@code Fix #250} hands the cores to
 * {@code compareIso}, closing the family with zero residue (the core reading is idempotent,
 * measured). The unmeasured sibling — a <b>leading-space</b> operand, which
 * {@code Primitives.dateComparison} passes through untrimmed and no corpus value or fixture carries
 * — moves the same way: {@code fires(" 2012-06-15", "2012-06-15", ==)} is now {@code true}.
 * {@code IsoDateComparisonReadableCoreTest} pins both families differentially.
 * </p>
 * <p>
 * Otherwise — at least one operand is a truncated partial, a masked form, blank or junk — the hull
 * rule applies, with both hulls clipped to a common precision of at least {@link #DAY_PRECISION}
 * characters (see {@link #commonPrecision}), so a day-precision operand is a <b>point</b> and the
 * mixed lower/upper test is sound.
 * </p>
 *
 * <h2>Saturating bounds — "every comparison FALSE" is emergent</h2>
 * <p>
 * A blank, a junk token ({@code UNKNOWN}), a calendar-impossible date ({@code 2026-02-30}) and a
 * <b>year-masked</b> value ({@code ----06-15}) alike have an <b>unbounded</b> hull:
 * {@link IsoDateBounds#lower} / {@link IsoDateBounds#upper} return {@code null} for all of them.
 * Every one of the six tests above is then false, so all six operators answer <b>false</b> with no
 * per-operator guard clause to forget.
 * </p>
 * <p>
 * &#9888; Unbounded is represented <b>out of band</b> (a {@code null} bound), never as an in-band
 * {@code 0000-01-01} / {@code 9999-12-31}: {@code 9999} is a real clinical "ongoing" sentinel and a
 * study literally carrying {@code 9999-12-31} would otherwise make {@code A >= upper("")} fire.
 * </p>
 * <p>
 * &#9888; This deliberately breaks complementarity for such operands: {@code A == ""} and
 * {@code A != ""} are <b>both</b> false. That is intended — <i>you cannot compare against a value
 * you do not have.</i>
 * </p>
 *
 * <h2>&#9888;&#9888; The boundary with EC-46 / Fix #142 — deliberately NOT unified</h2>
 * <p>
 * Saturation belongs to this class only. {@code min_date} / {@code max_date} keep EC-46's rule that
 * an unpositionable value <i>wins every extreme</i> (the extreme becomes no value). Under
 * saturation {@code max{2012-06-15, ----06-15}} would answer {@code 9999-06-15} — a
 * confident-looking answer that is false. {@link IsoDateBounds} therefore still returns
 * {@code null} rather than a saturated bound, and this class is the only place that reads that
 * {@code null} as "spans everything".
 * </p>
 */
public final class IsoDateComparison
{

    /** {@code YYYY-MM-DD} — the coarsest precision the hull rule ever compares at. */
    private static final int DAY_PRECISION = 10;

    /** Second precision — the width {@link IsoDateBounds} renders its bounds at. */
    private static final int MAX_PRECISION = 19;

    private IsoDateComparison()
    {
    }


    /**
     * The per-row verdict of a {@code date_*} comparison between two ISO-8601 strings.
     *
     * @param a
     *            the left operand's string form.
     * @param b
     *            the right operand's string form.
     * @param direction
     *            {@code 0} = equality, {@code 1} = greater, {@code -1} = less — the
     *            {@link ScalarSemantics#matchCmp} convention.
     * @param orEqual
     *            whether the operator admits equality ({@code >=} / {@code <=}; also set for
     *            {@code ==} / {@code !=}).
     * @param negate
     *            whether the operator is the negated form. Only {@code date_not_equal_to} sets it,
     *            and it always arrives with {@code direction == 0}; for a hypothetical negated
     *            inequality the plain complement is returned, which is <i>"not definitely X"</i>
     *            and over-reports — no such expression exists in the corpus.
     * @return {@code true} iff the leaf fires for this row.
     */
    public static boolean fires(String a, String b, int direction, boolean orEqual, boolean negate)
    {
        String coreA = IsoDateBounds.core(a);
        String coreB = IsoDateBounds.core(b);
        if (coreA != null && coreB != null && IsoDateBounds.isReadableCore(coreA)
                && IsoDateBounds.isReadableCore(coreB) && CalendarDates.isCompleteDate(coreA)
                && CalendarDates.isCompleteDate(coreB))
        {
            // Fix #229 — the readability conjuncts are NOT redundant with isCompleteDate: core()
            // strips once and isCompleteDate strips again. Without them "2012-06-15ZZ" <
            // "2012-06-16ZZ" answered true for two values with null hulls — and they still gate
            // this branch now that the CORES are compared, because compareIso strips a trailing
            // decoration itself: core("2012-06-15ZZ") is "2012-06-15Z", which compareIso would
            // read as a date again (measured: dropping the conjuncts reopens all 2 565 moved
            // pairs). Shared with IsoDateBounds.halfBound/isDetermined, never copied.
            //
            // Fix #250 — the comparison runs on the CORES. The pre-Q16 path classified on the
            // cores and then compared the raw operands, so a fractional-second tail reached
            // compareIso unstripped (it shifts detectIsoPrecision's length bucket, and '.' sorts
            // before 'T': "2012-06-15.000" < "2012-06-15T10:30:45" answered true) and a
            // leading-space operand never compared equal to its trimmed twin. Two complete dates
            // are points at their own granularity, compared at the coarser of the two CORE
            // precisions.
            return negate != ScalarSemantics.matchCmp(ScalarSemantics.compareIso(coreA, coreB),
                    direction, orEqual);
        }
        int prec = commonPrecision(coreA, coreB);
        String loA = clip(IsoDateBounds.lower(a), prec);
        String hiA = clip(IsoDateBounds.upper(a), prec);
        String loB = clip(IsoDateBounds.lower(b), prec);
        String hiB = clip(IsoDateBounds.upper(b), prec);
        if (loA == null || hiA == null || loB == null || hiB == null)
        {
            // Unbounded on at least one side — every one of the six tests is false.
            return false;
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
     * The earliest ({@code high == false}) or latest instant the cell could denote, rendered at the
     * value's own precision but never coarser than a day, or {@code null} when it cannot be
     * positioned. Backs the {@code earliest_possible} / {@code latest_possible} builtins.
     */
    public static @Nullable String bound(@Nullable String value, boolean high)
    {
        String core = IsoDateBounds.core(value);
        return clip(high ? IsoDateBounds.upper(value) : IsoDateBounds.lower(value),
                precisionOf(core));
    }


    /**
     * The width both hulls are clipped to: the finer of the two operands' own precisions, floored
     * at a whole day. Flooring is what makes a day-precision operand a <b>point</b> — without it
     * {@code 2026-01-17 >= 2026-01} would compare {@code …T00:00:00} against {@code …T23:59:59} and
     * answer for the wrong reason.
     */
    private static int commonPrecision(@Nullable String coreA, @Nullable String coreB)
    {
        return Math.max(precisionOf(coreA), precisionOf(coreB));
    }


    /**
     * The operand's own precision tier, floored at a day and capped at a second.
     *
     * <p>
     * &#9873; <b>An interval of uncertainty gets its precision from its halves</b> (Fix #212, D4).
     * {@link ScalarSemantics#detectIsoPrecision} buckets purely on <b>length</b>, so
     * {@code 2003-12/2003-12} — 15 characters — used to read as tier 13 and clip a month-precision
     * interval's hull to the <i>hour</i>. The halves say month, and the <b>coarser</b> of the two
     * is the safe reading (CDISC-SEND-0070 requires them equal, and does not always hold).
     * </p>
     */
    private static int precisionOf(@Nullable String core)
    {
        if (core == null)
        {
            return DAY_PRECISION;
        }
        String @Nullable [] halves = IsoDateBounds.intervalHalves(core);
        int tier = halves == null ? ScalarSemantics.detectIsoPrecision(core)
                : Math.min(halfPrecision(halves[0]), halfPrecision(halves[1]));
        return Math.min(MAX_PRECISION, Math.max(DAY_PRECISION, tier));
    }


    /**
     * One half's tier, normalised the same way {@link IsoDateBounds} normalises it before bounding
     * — otherwise an offset carried on one side only is read as extra precision.
     */
    private static int halfPrecision(String half)
    {
        String core = IsoDateBounds.core(half);
        return core == null ? DAY_PRECISION : ScalarSemantics.detectIsoPrecision(core);
    }


    private static @Nullable String clip(@Nullable String bound, int precision)
    {
        if (bound == null)
        {
            return null;
        }
        return bound.length() > precision ? bound.substring(0, precision) : bound;
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
