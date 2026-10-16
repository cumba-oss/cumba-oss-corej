package net.cumba.corej.core.exec;

import java.util.Comparator;
import java.util.List;
import net.cumba.corej.core.expr.eval.IsoDateBounds;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The extreme-selection machinery shared by every extreme in the engine — the EC-51 candidate
 * filter ({@link #extremeCandidate}), the generic string extreme — plain text order, owner K3
 * ({@link #genericStringExtreme}) — and the EC-46 / EC-51 date accumulator ({@link DateExtreme}).
 * Moved here verbatim from the retired operation executor by runbook W5
 * ({@code PLAN-grouped-aggregate-functions} D-W5-8) because its readers outlive the executor: the
 * registry functions {@code max} / {@code max_date} / {@code min_date} ({@link GroupedAggregate})
 * and {@code row_max} ({@link RowMax}), and since {@code PLAN-scalar-date-extremes} the per-row
 * pair extremes {@code earliest_date} / {@code latest_date} ({@link ScalarDateExtremes}), which run
 * a two-cell {@link DateExtreme} block so that they can never drift from the grouped ones.
 * ({@code date_diff_days}' grouped subtrahend was a third until runbook W2b: its reference is a
 * named {@code min_date} / {@code max_date} call now.)
 */
public final class Extremes
{

    private Extremes()
    {
    }


    /**
     * The extreme of the <b>generic</b> {@code max} / {@code row_max} text fallback: plain text
     * order, nothing else.
     *
     * <p>
     * ⭐ <b>Owner K3, 2026-09-30: <i>"no, special handling for special texts. Text is text."</i></b>
     * Until then this arm applied EC-46's date rule whenever every candidate was a positionable ISO
     * date ({@code max{2012-06, 2012-06-15}} was <em>indeterminate</em>, a computed missing) — a
     * special case for date-looking text inside the text branch. A character value ranks as text
     * like any other now; the date rule belongs to {@code max_date} / {@code min_date}
     * ({@link DateExtreme}, {@code GroupedAggregate.dateExtremeOf}), which are the date functions
     * and keep it in full. Measured over the shipped corpus at the ruling: no {@code max} or
     * {@code row_max} rule reads a date column (every date extreme authors {@code max_date}), so no
     * verdict moved.
     * </p>
     *
     * @param candidates
     *            the populated candidate texts (blank and missing cells already dropped)
     * @param findMax
     *            {@code true} for the maximum, {@code false} for the minimum
     * @return the winning text, or {@code null} for no candidate
     */
    public static @Nullable String genericStringExtreme(List<String> candidates, boolean findMax)
    {
        if (candidates.isEmpty())
        {
            return null;
        }
        Comparator<String> order = Comparator.naturalOrder();
        return candidates.stream().max(findMax ? order : order.reversed()).orElse(null);
    }


    /**
     * EC-51 — the single candidate filter shared by every extreme selector. Returns the cell's
     * <b>raw</b> text when it may serve as a candidate, or {@code null} when it may not.
     *
     * <p>
     * Before EC-51 this test was re-inlined at five sites in three syntactic shapes, and nothing in
     * the rule layer declared it: {@code Operation} has no field for it, and a {@code Check}
     * conjunct filters the <i>evaluation</i> table while an extreme is taken over the
     * <i>foreign</i> one. An operation {@code filter:} cannot express it either — it is
     * equality/membership only ({@code rowMatchesFilter}), so bare non-emptiness is not
     * expressible, though filtering <i>on</i> a column does incidentally drop rows where that
     * column is blank.
     * </p>
     *
     * <p>
     * <b>Deliberately stricter than {@link ScalarSemantics#isMissing}, which does not strip.</b> A
     * whitespace-only cell used to win every {@code min} — {@code " "} sorts below every digit —
     * and, at the retired {@code row_max}/{@code row_min} operations, its mere presence forced the
     * horizontal reducer out of numeric mode, so a {@code max} could move too ({@code row_max} is
     * the registry function {@link RowMax} since wave 3, which shares this filter). Providers are
     * expected to right-trim, but a stray blank must not decide a group's extreme.
     * {@code isMissing} itself is left untouched: its 37 call sites carry
     * {@code empty}/{@code non_empty}, grouping keys and comparison folding, and widening those is
     * not this change.
     * </p>
     *
     * <p>
     * <b>Blankness is defined explicitly rather than via {@code String.strip()}</b>, because
     * {@code strip()} follows {@code Character.isWhitespace}, which excludes the non-breaking
     * spaces that Python's {@code str.strip()} removes. Leaving it to the two runtimes' defaults
     * would make an NBSP-only cell a candidate on one lane and not the other.
     * </p>
     *
     * <p>
     * The <b>raw</b> string is returned, never a trimmed one, so the selected extreme stays the
     * verbatim cell text (Fix #137, {@code Q2 = raw string}). Mirrored in the fork by
     * {@code BaseOperation._extreme_candidate}; the two must stay in step.
     * </p>
     */
    public static @Nullable String extremeCandidate(@Nullable IDataValue dv)
    {
        if (dv == null || dv.isMissingOrInvalid())
        {
            return null;
        }
        String s = dv.getValueAsString();
        return s == null || isBlank(s) ? null : s;
    }


    /**
     * EC-51 — {@code true} when every code point is blank, using a set that matches Python's
     * {@code str.strip()}: {@link Character#isWhitespace} plus the four separators it excludes —
     * NEL ({@code U+0085}), NBSP ({@code U+00A0}), FIGURE SPACE ({@code U+2007}) and NARROW NBSP
     * ({@code U+202F}). An empty string is blank.
     */
    private static boolean isBlank(String s)
    {
        return s.codePoints().allMatch(cp -> Character.isWhitespace(cp) || cp == 0x0085
                || cp == 0x00A0 || cp == 0x2007 || cp == 0x202F);
    }

    /**
     * EC-46 — accumulates date-extreme candidates and yields the extreme <b>only when it is
     * determinate</b>; among the tied determined candidates it selects by the call's {@code ties}
     * mode ({@code PLAN-date-extreme-precision-tie}, ruling DTB: DT1 (a), DT2 (a), DT3 (a), DT4
     * (b)).
     *
     * <p>
     * The rule (EC-46 §4.2, verdicts OQ1–OQ3):
     * </p>
     *
     * <blockquote>The extreme yields a value only when a <i>determined</i> candidate wins against
     * every possible completion of every other candidate.</blockquote>
     *
     * <p>
     * Operationally: track (a) the best determined candidate by its own bound and (b) the extreme
     * bound over <i>all</i> candidates. The result is the former iff it reaches the latter. Both
     * accumulations run in one loop ({@code {2012-06-02, 2012-06-01, 2012-06}} mis-answers under a
     * running pairwise {@code compareTo} fold).
     * </p>
     *
     * <p>
     * <b>The tie-break, and row order.</b> This javadoc used to say that <i>"the outcome does not
     * depend on row order"</i>. It did — on an <b>equal primary bound</b> the strict comparison
     * kept the first-seen candidate, so {@code min{2020-01-01, 2020-01-01T00:00}} (both start at
     * {@code T00:00:00}) answered whichever row came first, and so did an equal hull spelt twice
     * ({@code …T08:00Z} / {@code …T09:00+01:00}); under D130's text comparison a reader saw which.
     * Since {@code PLAN-date-extreme-precision-tie} the order among the determined candidates is
     * total, so the answer is row-order free in both modes:
     * </p>
     * <ul>
     * <li><b>{@code ties="hull"}</b> (the default — an ordering question wants the conservative
     * hull): the extreme primary bound (lower for a min, upper for a max), then the <b>wider
     * hull</b> (T2 — the date beside a same-edge {@code …T00:00} / {@code …T23:59}, as the rule
     * guide states), then the DT3 key.</li>
     * <li><b>{@code ties="precise"}</b> (a copy check — DT1 (a)): within the hull winner {@code W},
     * the <b>earliest atom</b> for a min — the determined candidate with the earliest upper bound,
     * then the latest lower bound, then the DT3 key — and the <b>latest atom</b> for a max (latest
     * lower, earliest upper, DT3): {@code min/max{2020-01-01, 2020-01-01T08:00}} answer
     * {@code …T08:00}. Exact in one pass: the refined candidate {@code Y} lies inside {@code W}'s
     * hull by construction ({@code W} is a candidate of {@code Y}'s search and {@code Y} of
     * {@code W}'s: for a min {@code Y.upper <= W.upper} and {@code Y.lower >= W.lower}), so the
     * refinement never crosses the EC-46 decision.</li>
     * <li><b>The DT3 key</b> (T8), for an equal hull spelt differently: the longer <b>trimmed</b>
     * text (an explicit offset, a fraction — stripped before bounding), then code-point order,
     * ascending for a min and descending for a max.</li>
     * </ul>
     *
     * <p>
     * &#9873; <b>An undetermined candidate inside {@code W} (T11).</b> Hour precision
     * ({@code …T08}) and an interval are not {@link IsoDateBounds#isDetermined determined}, so they
     * never win (DT2 (a): EC-46 determinability is unchanged), yet such a value can lie inside the
     * winning day — {@code min{2020-01-01, …T08, …T09:15}}: {@code …T08} is certainly earlier than
     * {@code …T09:15}. Answering the earliest <i>determined</i> atom would then answer a value that
     * is provably not the extreme, so the precise mode keeps the hull answer {@code W} whenever an
     * undetermined candidate that does not contain {@code W} could reach the refined value (for a
     * min: its lower bound is strictly before the refined value's upper; for a max: its upper
     * strictly after the refined lower — at point equality the candidate is at best a tie with the
     * refined value and cannot beat it). A candidate containing {@code W} ({@code min{2020-01,
     * 2020-01-01, …T08:00}}: the month) does not block — the day pins it, which is DT2 (a)'s ruled
     * consequence. Only the precise mode tracks the undetermined candidates, and it keeps O(1)
     * state, never a list of hulls: their best primary bound (the smallest lower for a min), the
     * best secondary bound among those sharing it (the smallest upper — the one that decides
     * whether some such candidate fails to contain {@code W}) and the second-best primary bound
     * (the smallest lower strictly after the best — the nearest candidate that cannot contain
     * {@code W}); the block test reads those three.
     * </p>
     *
     * <p>
     * A present-but-unpositionable candidate (year-masked, junk token, structurally-invalid date)
     * needs <b>no special case</b>: its hull is unbounded, so no determined candidate can reach it
     * and the group yields nothing. That is EC-46 OQ6/OQ7's "it wins" outcome, arrived at without a
     * short-circuit — and, unlike a short-circuit, it never returns the junk token as the value.
     * </p>
     *
     * <p>
     * <b>Missing candidates are the rule's business, not this class's</b> —
     * {@link #extremeCandidate} (EC-51 Half A, Fix #141) drops them, on both lanes, and by default
     * an empty cell stays <i>skipped</i> while only a present unusable value makes the extreme
     * indeterminate. Inverting that unconditionally would make nearly every group indeterminate,
     * which is why EC-51 Half B (Fix #145) makes it a per-operation <b>declaration</b>
     * ({@code missing_values: indeterminate}) rather than a new default. Both dispositions land in
     * the same {@code unbounded} state, so there is one determinability rule, not two — and the
     * tie-break runs only once the gate answers (T3).
     * </p>
     */
    static final class DateExtreme
    {

        private final boolean findMax;

        /**
         * EC-51 Half B — {@code true} when the declaring operation says a missing candidate makes
         * the extreme undeterminable ({@code missing_values: indeterminate}); {@code false} for the
         * default {@code skip}.
         */
        private final boolean missingIsIndeterminate;

        /** DT4 (b) — {@code ties="precise"}: refine within the hull winner (the class comment). */
        private final boolean precise;

        /** Raw cell text of the hull winner — the value returned in the default mode. */
        private @Nullable String best;

        /** {@code best}'s hull. */
        private IsoDateBounds.@Nullable Hull bestHull;

        /** The extreme bound across every candidate, determined or not. */
        private @Nullable String limit;

        /** Set when some candidate cannot be positioned at all, i.e. the hull is unbounded. */
        private boolean unbounded;

        /** Precise mode: raw text of the refined candidate (the earliest / latest atom). */
        private @Nullable String refined;

        /** {@code refined}'s hull. */
        private IsoDateBounds.@Nullable Hull refinedHull;

        /**
         * Precise mode only (T11): the undetermined candidates' best primary bound — the smallest
         * lower for a min, the largest upper for a max — or {@code null} while none was seen.
         */
        private @Nullable String undeterminedPrimary;

        /**
         * The best secondary bound — the smallest upper for a min, the largest lower for a max —
         * among the undetermined candidates whose primary bound equals
         * {@link #undeterminedPrimary}.
         */
        private @Nullable String undeterminedSecondary;

        /**
         * The best primary bound among the undetermined candidates strictly worse than
         * {@link #undeterminedPrimary}, or {@code null}.
         */
        private @Nullable String undeterminedSecondBest;

        DateExtreme(boolean findMax, boolean missingIsIndeterminate, boolean precise)
        {
            this.findMax = findMax;
            this.missingIsIndeterminate = missingIsIndeterminate;
            this.precise = precise;
        }


        /**
         * EC-51 — the single entry point for a <b>raw cell</b>: it applies the shared candidate
         * filter ({@link #extremeCandidate}) and this accumulator's missing-value disposition, so
         * no selector site re-inlines either. A missing cell is dropped under {@code skip} and
         * makes the extreme undeterminable under {@code indeterminate}.
         */
        void addCell(@Nullable IDataValue dv)
        {
            String raw = extremeCandidate(dv);
            if (raw == null)
            {
                if (missingIsIndeterminate)
                {
                    unbounded = true;
                }
                return;
            }
            add(raw);
        }


        void add(String raw)
        {
            add(raw, IsoDateBounds.hullOf(raw));
        }


        /**
         * The candidate with its hull read once (T10) — package-private so a test can feed a
         * hand-built hull.
         */
        void add(String raw, IsoDateBounds.@Nullable Hull hull)
        {
            if (hull == null)
            {
                unbounded = true;
                return;
            }
            String bound = primary(hull);
            if (limit == null || beats(bound, limit))
            {
                limit = bound;
            }
            if (!hull.determined())
            {
                if (precise)
                {
                    noteUndetermined(hull);
                }
                return;
            }
            if (best == null || bestHull == null || outranks(raw, hull, best, bestHull))
            {
                best = raw;
                bestHull = hull;
            }
            if (precise && (refined == null || refinedHull == null
                    || refines(raw, hull, refined, refinedHull)))
            {
                refined = raw;
                refinedHull = hull;
            }
        }


        /** {@code null} when the extreme cannot be determined — the caller then emits no value. */
        @Nullable
        String result()
        {
            String b = best;
            IsoDateBounds.Hull bh = bestHull;
            if (unbounded || b == null || bh == null || limit == null)
            {
                return null;
            }
            // The winner must reach the extreme of every rival's hull. Equality counts: it is the
            // non-strict case that makes min{2012-06, 2012-06-01} resolve to the complete date
            // (OQ1) and max{2012-06, 2012-06-30} to the complete date (the benign tie).
            if (!primary(bh).equals(limit))
            {
                return null;
            }
            String r = refined;
            IsoDateBounds.Hull rh = refinedHull;
            if (!precise || r == null || rh == null || blockedByUndetermined(bh, rh))
            {
                return b;
            }
            return r;
        }


        /**
         * T11 (the class comment): folds an undetermined candidate into the O(1) state — the best
         * primary bound, the best secondary bound among the candidates sharing it, and the
         * second-best primary bound.
         */
        private void noteUndetermined(IsoDateBounds.Hull hull)
        {
            String p = primary(hull);
            String s = secondary(hull);
            String best = undeterminedPrimary;
            if (best == null || beats(p, best))
            {
                // the former best is now the best among those strictly worse than p
                undeterminedSecondBest = best;
                undeterminedPrimary = p;
                undeterminedSecondary = s;
            }
            else if (p.equals(best))
            {
                String bestSecondary = undeterminedSecondary;
                if (bestSecondary == null || beats(s, bestSecondary))
                {
                    undeterminedSecondary = s;
                }
            }
            else if (undeterminedSecondBest == null || beats(p, undeterminedSecondBest))
            {
                undeterminedSecondBest = p;
            }
        }


        /**
         * T11 (the class comment): whether an undetermined candidate that does not contain the hull
         * winner could reach the refined value — strictly beat its far bound (DT review L1: at
         * point equality the candidate is at best a tie and cannot be the extreme). Decided from
         * the three tracked bounds: a candidate sharing the winner's primary bound fails to contain
         * the winner exactly when its secondary bound stops short of the winner's, and every
         * candidate with a strictly worse primary bound fails to contain it; the best of each kind
         * reaches furthest.
         */
        private boolean blockedByUndetermined(IsoDateBounds.Hull winner,
                IsoDateBounds.Hull refinedOne)
        {
            String best = undeterminedPrimary;
            String bestSecondary = undeterminedSecondary;
            if (best == null || bestSecondary == null)
            {
                return false;
            }
            String winnerPrimary = primary(winner);
            String reach = secondary(refinedOne);
            if (beats(best, winnerPrimary))
            {
                // unreachable once result() has matched the winner to the limit the undetermined
                // candidates contributed to; conservative all the same
                return true;
            }
            if (best.equals(winnerPrimary))
            {
                boolean oneDoesNotContainTheWinner = beats(bestSecondary, secondary(winner));
                String secondBest = undeterminedSecondBest;
                return (oneDoesNotContainTheWinner && beats(best, reach))
                        || (secondBest != null && beats(secondBest, reach));
            }
            return beats(best, reach);
        }


        /** The ranking bound: upper for a max, lower for a min. */
        private String primary(IsoDateBounds.Hull hull)
        {
            return findMax ? hull.upper() : hull.lower();
        }


        /** The other bound: lower for a max, upper for a min. */
        private String secondary(IsoDateBounds.Hull hull)
        {
            return findMax ? hull.lower() : hull.upper();
        }


        /**
         * The hull order (both modes): the extreme primary bound, then the wider hull (T2), then
         * the DT3 key — {@code true} when {@code raw} outranks the current winner.
         */
        private boolean outranks(String raw, IsoDateBounds.Hull hull, String other,
                IsoDateBounds.Hull otherHull)
        {
            int c = findMax ? otherHull.upper().compareTo(hull.upper())
                    : hull.lower().compareTo(otherHull.lower());
            if (c == 0)
            {
                // T2 — the wider hull: the earlier lower for a max, the later upper for a min
                c = findMax ? hull.lower().compareTo(otherHull.lower())
                        : otherHull.upper().compareTo(hull.upper());
            }
            return c != 0 ? c < 0 : textOutranks(raw, other);
        }


        /**
         * The precise order (DT1 (a)): the earliest upper then the latest lower for a min (the
         * earliest atom), the latest lower then the earliest upper for a max (the latest atom),
         * then the DT3 key.
         */
        private boolean refines(String raw, IsoDateBounds.Hull hull, String other,
                IsoDateBounds.Hull otherHull)
        {
            int c = findMax ? otherHull.lower().compareTo(hull.lower())
                    : hull.upper().compareTo(otherHull.upper());
            if (c == 0)
            {
                c = findMax ? hull.upper().compareTo(otherHull.upper())
                        : otherHull.lower().compareTo(hull.lower());
            }
            return c != 0 ? c < 0 : textOutranks(raw, other);
        }


        /**
         * DT3 / T8 — on an equal hull: the longer trimmed text, then code-point order ascending for
         * a min and descending for a max (trimmed, then raw, so the order is total).
         */
        private boolean textOutranks(String raw, String other)
        {
            String a = raw.trim();
            String b = other.trim();
            int c = Integer.compare(b.length(), a.length());
            if (c == 0)
            {
                c = findMax ? b.compareTo(a) : a.compareTo(b);
            }
            if (c == 0)
            {
                c = findMax ? other.compareTo(raw) : raw.compareTo(other);
            }
            return c < 0;
        }


        private boolean beats(String a, String b)
        {
            return findMax ? a.compareTo(b) > 0 : a.compareTo(b) < 0;
        }

    }
}
