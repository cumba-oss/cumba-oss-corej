package net.cumba.corej.core.exec;

import java.util.Comparator;
import java.util.List;
import net.cumba.corej.core.expr.eval.IsoDateBounds;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The extreme-selection machinery shared by every extreme in the engine — the EC-51 candidate
 * filter ({@link #extremeCandidate}), the generic string extreme with EC-46's date rule
 * ({@link #genericStringExtreme}) and the EC-46 / EC-51 date accumulator ({@link DateExtreme}).
 * Moved here verbatim from the retired operation executor by runbook W5
 * ({@code PLAN-grouped-aggregate-functions} D-W5-8) because its readers outlive the executor: the
 * registry functions {@code max} / {@code max_date} / {@code min_date} ({@link GroupedAggregate})
 * and {@code row_max} ({@link RowMax}). ({@code date_diff_days}' grouped subtrahend was a third
 * until runbook W2b: its reference is a named {@code min_date} / {@code max_date} call now.)
 */
public final class Extremes
{

    private Extremes()
    {
    }


    /**
     * EC-46 OQ4 — the extreme for the <b>generic</b> {@code max}/{@code min} string fallback, which
     * is <i>not</i> date-only.
     *
     * <p>
     * Measured over the shipped corpus, the generic {@code max()} reaches this path for
     * {@code ANRIND} (5 rules) and {@code ATOXGR} (4) — Char <i>category</i> columns;
     * {@code AVAL}/{@code DSSTDY} take the numeric branch. Applying date semantics unconditionally
     * would make those 9 rules yield no value at all, because a category code cannot be positioned
     * on a calendar. So EC-46's rule is applied only when the group is unambiguously dates — every
     * candidate positionable, which no category column satisfies — and plain lexicographic order is
     * kept otherwise, exactly as before.
     * </p>
     *
     * <p>
     * ⚠ <b>Known limit:</b> a genuine date column carrying a junk token ({@code UNK}) fails the
     * all-dates test and so keeps lexicographic treatment — Defect E is not caught on <i>this</i>
     * path. That is acceptable because the generic operator has no date consumer left: measured
     * over the shipped corpus, <b>no</b> rule authors the generic {@code max()} over a date column
     * — every date extreme authors {@code max_date} (EC-46 OQ4) and runs through the date extremes
     * ({@code GroupedAggregate.dateExtremeOf}), where the rule applies in full. The routing here is
     * forward-looking.
     * </p>
     */
    public static @Nullable String genericStringExtreme(List<String> candidates, boolean findMax)
    {
        if (candidates.isEmpty())
        {
            return null;
        }
        if (candidates.stream().allMatch(IsoDateBounds::canPosition))
        {
            // EC-51 Half B: `false` is permanent here, not a default. This is the GENERIC
            // max fallback, whose operator cannot declare `missing_values` at all
            // (the retired operation parser's validateMissingValues) — and the list it is handed
            // has
            // already had its missing cells dropped, so the disposition has nothing left to see.
            DateExtreme extreme = new DateExtreme(findMax, false);
            candidates.forEach(extreme::add);
            return extreme.result();
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
     * determinate</b>.
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
     * accumulations are associative, so the two logical passes run in one loop and the outcome does
     * not depend on row order — which a running {@code compareTo} fold could not guarantee
     * ({@code {2012-06-02, 2012-06-01, 2012-06}} mis-answers pairwise).
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
     * the same {@code unbounded} state, so there is one determinability rule, not two.
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

        /** Raw cell text of the best determined candidate — the value ultimately returned. */
        private @Nullable String best;

        /** {@code best}'s own bound: upper for a max, lower for a min. */
        private @Nullable String bestBound;

        /** The extreme bound across every candidate, determined or not. */
        private @Nullable String limit;

        /** Set when some candidate cannot be positioned at all, i.e. the hull is unbounded. */
        private boolean unbounded;

        DateExtreme(boolean findMax, boolean missingIsIndeterminate)
        {
            this.findMax = findMax;
            this.missingIsIndeterminate = missingIsIndeterminate;
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
            String bound = findMax ? IsoDateBounds.upper(raw) : IsoDateBounds.lower(raw);
            if (bound == null)
            {
                unbounded = true;
                return;
            }
            if (limit == null || beats(bound, limit))
            {
                limit = bound;
            }
            if (IsoDateBounds.isDetermined(raw) && (bestBound == null || beats(bound, bestBound)))
            {
                best = raw;
                bestBound = bound;
            }
        }


        /** {@code null} when the extreme cannot be determined — the caller then emits no value. */
        @Nullable
        String result()
        {
            if (unbounded || best == null || bestBound == null || limit == null)
            {
                return null;
            }
            // The winner must reach the extreme of every rival's hull. Equality counts: it is the
            // non-strict case that makes min{2012-06, 2012-06-01} resolve to the complete date
            // (OQ1) and max{2012-06, 2012-06-30} to the complete date (the benign tie).
            return bestBound.equals(limit) ? best : null;
        }


        private boolean beats(String a, String b)
        {
            return findMax ? a.compareTo(b) > 0 : a.compareTo(b) < 0;
        }

    }
}
