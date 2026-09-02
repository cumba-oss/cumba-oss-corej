package net.cumba.cdisc.core.expr.eval;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.cdisc.core.exec.ScalarSemantics;
import org.jspecify.annotations.Nullable;

/**
 * EC-46 — the interval a possibly-incomplete ISO-8601 value denotes, for date-extreme selection.
 *
 * <p>
 * SDTM/SEND {@code --DTC} variables permit <b>partial</b> dates ({@code 2012}, {@code 2012-06}),
 * <b>masked</b> components ({@code 2012-06--}, {@code 2012---15}, {@code ----06-15}) and <b>UTC
 * offsets</b>. Such a value does not denote a point in time but a <i>set</i> of candidate instants;
 * this class supplies that set's <b>hull</b> — its earliest and latest possible completion.
 * </p>
 *
 * <p>
 * <b>This class defines no ordering.</b> There is deliberately no {@code compare} method: EC-46's
 * selection rule never orders two incomplete values against each other, it only asks whether some
 * <i>determined</i> candidate dominates every candidate's bound. A comparator here would create a
 * third date policy alongside {@link ScalarSemantics#compareIso} (truncate-to-common-precision) and
 * raw lexicographic ordering — which the EC forbids.
 * </p>
 *
 * <h2>The hull, by shape</h2>
 * <table border="1">
 * <caption>Bounds by input shape</caption>
 * <tr>
 * <th>input</th>
 * <th>lower</th>
 * <th>upper</th>
 * </tr>
 * <tr>
 * <td>{@code 2012-06-15}</td>
 * <td>{@code 2012-06-15T00:00:00}</td>
 * <td>{@code 2012-06-15T23:59:59}</td>
 * </tr>
 * <tr>
 * <td>{@code 2012}</td>
 * <td>{@code 2012-01-01T00:00:00}</td>
 * <td>{@code 2012-12-31T23:59:59}</td>
 * </tr>
 * <tr>
 * <td>{@code 2012-06}</td>
 * <td>{@code 2012-06-01T00:00:00}</td>
 * <td>{@code 2012-06-30T23:59:59}</td>
 * </tr>
 * <tr>
 * <td>{@code 2012-02}</td>
 * <td>{@code 2012-02-01T00:00:00}</td>
 * <td>{@code 2012-02-29T23:59:59} (leap)</td>
 * </tr>
 * <tr>
 * <td>{@code 2012-06--}</td>
 * <td>{@code 2012-06-01T00:00:00}</td>
 * <td>{@code 2012-06-30T23:59:59}</td>
 * </tr>
 * <tr>
 * <td>{@code 2012---15}</td>
 * <td>{@code 2012-01-15T00:00:00}</td>
 * <td>{@code 2012-12-15T23:59:59}</td>
 * </tr>
 * <tr>
 * <td>{@code ----06-15}</td>
 * <td colspan="2"><i>null — cannot be positioned</i></td>
 * </tr>
 * </table>
 *
 * <p>
 * A month-masked value denotes a <b>non-contiguous</b> set ({@code 2012---15} is twelve dates, not
 * a range); the hull is the bounding interval, which is all the selection rule needs.
 * </p>
 *
 * <h2>The ISO interval of uncertainty (Fix #212)</h2>
 * <p>
 * SDTMIG v3.4 &sect;4.4.2 / SENDIG v3.1.1 &sect;4.4.2 <b>recommend</b> that an imprecise date be
 * recorded as two date/time values separated by a solidus — {@code 2003-01-01/2003-02-15}. Such a
 * value denotes <i>one</i> instant known to lie between the two components, so its hull is the
 * union of the halves' hulls: {@code lower} is the earliest either half could start and
 * {@code upper} the latest either could end (D1 + D3). Each half is normalised <b>independently</b>
 * — {@link ScalarSemantics#normalizeToUtc} only sees a <i>trailing</i> offset, so normalising the
 * whole string would leave a left-hand offset embedded (D2).
 * </p>
 * <p>
 * &#9888; {@link #intervalHalves} is deliberately <b>stricter</b> than
 * {@link CalendarDates#isValidDate}: SDTMIG defines exactly two components, so {@code a/b/c} and an
 * empty half yield {@code null} bounds (D6, D8), and either half being unpositionable makes
 * <b>both</b> bounds {@code null} rather than half an answer (D7). The two methods answer different
 * questions — "can this be placed on the calendar" versus "is this a well-formed value" — and this
 * divergence is intentional, not drift.
 * </p>
 *
 * <h2>Invariants</h2>
 * <ol>
 * <li><b>Both bounds order complete same-tier values exactly as their raw text does.</b> Every
 * value gets the same {@code T00:00:00} / {@code T23:59:59} suffix, so the suffix cancels — which
 * is what makes the all-complete path (every group in {@code /data/testdata}) byte-identical to the
 * pre-EC-46 engine. Note this is deliberately <i>not</i> {@code lower == upper}: a day-precision
 * value spans a day, and that span is exactly what makes {@code max{2012-06, 2012-06-30}} resolve
 * to {@code 2012-06-30} rather than to no value.</li>
 * <li><b>Normalisation precedes bounding.</b> {@code 2012-06-15T10:00+02:00} is the instant
 * {@code 2012-06-15T08:00Z}; bounding before applying the offset would give the wrong hull.</li>
 * </ol>
 */
public final class IsoDateBounds
{

    /** Rendered at second precision so bounds of mixed tiers compare as plain strings. */
    private static final String LOW_TIME = "T00:00:00";

    private static final String HIGH_TIME = "T23:59:59";

    /** {@code 2012-06--} / {@code 2012-06-} — year and month known, day masked. */
    private static final Pattern DAY_MASKED = Pattern.compile("^(\\d{4})-(\\d{2})-{1,2}$");

    /** {@code 2012---15} / {@code 2012--15} — year and day known, month masked. */
    private static final Pattern MONTH_MASKED = Pattern.compile("^(\\d{4})-{2,3}(\\d{2})$");

    /** &#9873; SDTMIG &sect;4.4.2 — the interval of uncertainty's separator. Exactly two halves. */
    private static final char SOLIDUS = '/';

    private IsoDateBounds()
    {
    }


    /**
     * {@code true} iff {@code s} can be placed on the calendar at all, i.e. has a bounded hull.
     *
     * <p>
     * False for a <b>year-masked</b> value ({@code ----06-15}), an <b>unparseable token</b>
     * ({@code UNK}, {@code NOT DONE}) and a <b>structurally-invalid</b> date ({@code 2012-13-45})
     * alike — one predicate, no taxonomy at the call site. EC-46 OQ6/OQ7 rule that the reason does
     * not matter: the extreme cannot be determined in such a value's presence.
     * </p>
     */
    public static boolean canPosition(@Nullable String s)
    {
        return lower(s) != null;
    }


    /**
     * {@code true} iff {@code s} denotes a single known date (or instant) — the eligibility test
     * for <i>winning</i> an extreme. Day precision counts as determined: these are date extremes,
     * and {@code 2012-06-15} is a determinate calendar day.
     *
     * <p>
     * &#9873; <b>An interval of uncertainty is never determined</b> (D5) — uncertainty is what it
     * denotes.
     * </p>
     *
     * <p>
     * &#9888;&#9888; <b>This short-circuit is DEFENSIVE, and measurably changes nothing today.</b>
     * Measured 2026-08-11 over the whole differential corpus: <b>zero</b> solidus-bearing inputs
     * take a different answer with it than without. The reason is stronger than the "length
     * coincidence" the plan cites — {@link ScalarSemantics#isCompleteDate} requires {@code '-'} at
     * indices 4 and 7 (and {@code 'T'} / {@code ':'} at 10/13/16 at the longer tiers) with digits
     * everywhere else, so <b>no</b> string containing a solidus can satisfy it at length 10, 16 or
     * 19, whatever its halves are.
     * </p>
     *
     * <p>
     * &#8658; It is kept because it states the intent where a reader will look, and because the
     * property it relies on lives in a <i>different class</i>: widening
     * {@code ScalarSemantics.isCompleteDate}'s separator checks would silently make an interval
     * "determined" and let it win a date extreme. ⚠ <b>Do not claim a neuter test for it</b> — one
     * cannot exist while that structural argument holds. {@code IsoDateBoundsTest} records the
     * measurement instead.
     * </p>
     *
     * <p>
     * &#9873;&#9873; <b>{@code isDetermined ⇒ canPosition} — an invariant, and it is why the
     * {@link #isReadableCore} call is here ({@code Fix #226}).</b> This predicate gates on
     * {@link CalendarDates#isCompleteDate}, which <i>strips a second time</i> — the same
     * double-strip {@code Fix #220} removed from the bound path. Without the guard
     * {@code 2012-06-15ZZ} answered {@code true} here (its twice-stripped form is a complete date)
     * while both its bounds were {@code null}, because {@link #halfBound} reads the
     * <i>once</i>-stripped core and cannot decode it. {@code Fix #220} left <b>15</b> corpus inputs
     * in that state and pinned them; sharing the one guard closes the set. The invariant is
     * asserted over the whole differential corpus by
     * {@code IsoDateBoundsTest.Determinacy.isDeterminedImpliesCanPositionEverywhereInTheCorpus},
     * and the 15 are kept as a named regression record in
     * {@code IsoDateBoundsDispatchTest.MovedByFix220}.
     * </p>
     *
     * <p>
     * &#9888; <b>The guard must be the <i>same</i> call, not a second copy.</b> The
     * <b>invariant</b> holds only while the two ask the identical readability question of the
     * identical core; a hand-written duplicate here would drift from {@link #halfBound}'s and
     * reopen the break silently. &#9873; Note the two predicates are <i>not</i> equivalent and must
     * not become so — {@code canPosition} does <b>not</b> imply {@code isDetermined} ({@code 2012}
     * has a hull and is no determinate day), which {@code IsoDateBoundsTest.Determinacy} pins in
     * both directions.
     * </p>
     */
    public static boolean isDetermined(@Nullable String s)
    {
        if (s != null && s.indexOf(SOLIDUS) >= 0)
        {
            return false;
        }
        String core = core(s);
        return core != null && isReadableCore(core) && CalendarDates.isCompleteDate(core);
    }


    /**
     * {@code Fix #220}'s <b>readability</b> guard, extracted by {@code Fix #226} and widened to
     * package-private by {@code Fix #229} so all three call sites — {@link #halfBound},
     * {@link #isDetermined} and {@link IsoDateComparison#fires} — share <b>one</b> copy:
     * {@code true} iff {@code core} is a string this class can actually <i>read</i>, as opposed to
     * one a normalising validator would accept after stripping it again.
     *
     * <p>
     * &#9888;&#9888; <b>Why a shared predicate rather than two call sites that happen to agree.</b>
     * {@link #core} applies each strip <b>at most once</b> — one trailing offset, then one
     * fractional-seconds tail — and never repeats them. Both {@link CalendarDates#isValidDate}
     * (behind {@link #truncatedBound}) and {@link CalendarDates#isCompleteDate} (behind
     * {@link #isDetermined}) normalise their own argument and therefore judge a <i>further</i>
     * -stripped string, while the readers here index the once-stripped one. On a <b>stacked</b>
     * tail ({@code 2012-06-15ZZ}, {@code 2012.000ZZ}, {@code 2012-06-15+01:00+01:00}) the two
     * disagree. Asking "can the core be decoded exactly as written?" first is what makes every
     * downstream read safe — for the bound path (which used to render {@code 2012Z-12-31T23:59:59}
     * or throw), for the determinacy verdict (which used to claim a determinate calendar day for a
     * value with no hull) and for the <b>comparison</b> fast path (which used to answer
     * {@code "2012-06-15ZZ" < "2012-06-16ZZ"} &rarr; {@code true} for two values with no hull at
     * all) alike.
     * </p>
     *
     * <p>
     * &#9873;&#9873; <b>The third caller, and the one that moves findings ({@code Fix #229}).</b>
     * {@link IsoDateComparison#fires} used to open with the same shape — {@code core} gated by
     * {@link CalendarDates#isCompleteDate}, no readability guard — and its fast path then compared
     * the <i>raw</i> operands, so two undecodable-but-twice-strippable values got a verdict there.
     * Found by the {@code Fix #226} review and left open on purpose, because unlike
     * {@link #isDetermined} (shielded by {@code DateExtreme.add}'s {@code null}-bound return) that
     * one <b>does</b> move leaf verdicts and so needed its own authorisation. It has it:
     * {@code plans/done/PLAN-isodatecomparison-readable-core.md}. Measured differentially over the
     * whole {@code IsoDateCorpus} <b>pair</b> space, the close moves <b>2 565</b> ordered pairs /
     * <b>7 695</b> verdicts, <b>every one of them {@code true} &rarr; {@code false}</b> and every
     * one of them with at least one operand {@link CalendarDates#isValidDate} already rejects.
     * {@code IsoDateComparisonReadableCoreTest} asserts the invariant that keeps it closed.
     * </p>
     *
     * <p>
     * &#9888;&#9888; <b>Say exactly what is closed, and no more.</b> What the three readability
     * fixes close is the <b>once-vs-twice-strip readability</b> disagreement: no caller in this
     * package now reads a core its own {@link #core} cannot decode. The <i>second</i> family that
     * used to live one layer down — {@link IsoDateComparison#fires}'s fast path handed the
     * <b>raw</b> operands to {@link ScalarSemantics#compareIso}, whose normalisation differs from
     * {@link #core}'s (no trim, no fractional-second strip, a <i>length</i>-bucket precision tier),
     * measured at <b>260</b> ordered <i>valid</i>/<i>valid</i> readable-core pairs / 1 040 verdicts
     * (e.g. {@code "2012-06-15.000" < "2012-06-15T10:30:45"} was {@code true} because
     * {@code detectIsoPrecision} called the first hour-precision and {@code '.' < 'T'}) — was
     * closed by {@code Fix #250}, which hands the <b>cores</b> to {@code compareIso} on the owner's
     * 2026-08-13 ruling. &#9888; Even so, <b>do not read this as the package having a single date
     * normalisation</b>: {@code compareIso} itself still neither trims nor strips fractional
     * seconds, and any <i>new</i> caller that passes it raw text re-enters the same trap; inside
     * this package it is now reached only with cores.
     * </p>
     *
     * <p>
     * &#9873; The {@code isMaskedDate} disjunct changes no verdict on <b>any</b> of the three paths
     * and is kept for <b>intent</b> — see {@link #halfBound}'s javadoc for the containment
     * argument, and &#9888; <b>do not write a test claiming to catch its removal</b>. A masked core
     * can never be {@link ScalarSemantics#isCompleteDate} either: both predicates reduce their
     * argument the same way ({@code stripFractionalSeconds(stripTimezone(·))}) before matching, and
     * after that reduction a masked value is 7–9 characters while completeness demands 10/16/19 —
     * disjoint. &#9888; The <i>core</i> itself may be longer ({@code isMaskedDate("2012-06--Z")} is
     * {@code true}); it is the reduced form the lengths describe. So the disjunct is a no-op for
     * {@link #isDetermined} — and, by the identical argument, for {@link IsoDateComparison#fires}'s
     * fast path, which gates on the same {@link CalendarDates#isCompleteDate} — for a second,
     * independent reason.
     * </p>
     *
     * <p>
     * &#9873; It is a <b>guard, not a route</b>: every caller's arms are "yield the unpositionable
     * / indeterminate answer" or "proceed", never "branch A instead of branch B", so it cannot
     * reintroduce the {@code Fix #209} hazard of a validator's verdict deciding a dispatch.
     * Widening {@link ScalarSemantics#isoComponents} can only admit <i>more</i> values.
     * </p>
     */
    static boolean isReadableCore(String core)
    {
        return ScalarSemantics.isMaskedDate(core) || ScalarSemantics.isoComponents(core) != null;
    }


    /**
     * The earliest instant {@code s} could denote, or {@code null} when it cannot be positioned.
     *
     * <p>
     * Rendered as {@code yyyy-MM-ddTHH:mm:ss} so that values of differing precision are directly
     * string-comparable.
     * </p>
     */
    public static @Nullable String lower(@Nullable String s)
    {
        return bound(s, false);
    }


    /** The latest instant {@code s} could denote, or {@code null} when it cannot be positioned. */
    public static @Nullable String upper(@Nullable String s)
    {
        return bound(s, true);
    }


    /**
     * Invariant 2 — apply any offset instant-preserving BEFORE deriving the hull, reusing the
     * existing helper rather than re-implementing offset arithmetic. Returns {@code null} for a
     * blank input or one whose offset cannot be applied.
     *
     * <p>
     * Package-private rather than private so {@link IsoDateComparison} can classify an operand
     * against the <b>same</b> normalisation the bounds are derived from — the alternative was a
     * second copy of this two-call composition, which is exactly the drift this class exists to
     * avoid.
     * </p>
     */
    static @Nullable String core(@Nullable String s)
    {
        if (s == null || s.trim().isEmpty())
        {
            return null;
        }
        return ScalarSemantics.stripFractionalSeconds(ScalarSemantics.normalizeToUtc(s.trim()));
    }


    /**
     * The two components of an ISO interval of uncertainty, or {@code null} when {@code s} is not
     * one. Package-private so {@link IsoDateComparison} can derive an interval's precision from its
     * halves (D4) instead of from the whole string's length.
     *
     * <p>
     * &#9888; <b>Deliberately NOT clamped.</b> {@link #lower} / {@link #upper} take the min/max
     * across both halves (D3) so {@code lower <= upper} holds by construction — which makes an
     * inverted interval <i>unobservable</i> through the public bounds. {@link #intervalInverted}
     * therefore reads THIS, not the public bounds; if it read the public ones it could never fire.
     * See {@code PLAN-iso-interval-of-uncertainty.md} &sect;3.
     * </p>
     */
    static String @Nullable [] intervalHalves(@Nullable String s)
    {
        if (s == null)
        {
            return null;
        }
        String t = s.trim();
        int cut = t.indexOf(SOLIDUS);
        if (cut < 0)
        {
            return null;
        }
        String left = t.substring(0, cut);
        String right = t.substring(cut + 1);
        // D6 — SDTMIG defines exactly two components; a third is a shape no standard describes.
        // D8 — an empty half anchors nothing.
        if (right.indexOf(SOLIDUS) >= 0 || left.isEmpty() || right.isEmpty())
        {
            return null;
        }
        return new String[]
        {
                left, right
        };
    }


    /**
     * &#9873; SDTMIG &sect;4.4.2 — {@code true} iff {@code s} is an interval of uncertainty whose
     * start is <b>definitely</b> after its end, i.e. no completion of either half is consistent
     * with the other.
     *
     * <p>
     * Conservative by construction: the test is {@code lower(start) > upper(end)}, so a coarser end
     * that could still contain the start does not fire. {@code 2003-12-15/2003-12} is satisfiable
     * (Dec 15 lies inside December) and answers {@code false}; {@code 2003-12/2003-11-15} answers
     * {@code true}. Equal endpoints are a degenerate point, not an error, and an unpositionable
     * half is not <i>definitely</i> anything.
     * </p>
     *
     * <p>
     * Reads the <b>raw per-half</b> bounds — see {@link #intervalHalves}. This is the
     * <b>instant</b> reading, and it is what {@link #bound} uses to decide whether the D1 hull is
     * safe. &#9888; It is <b>not</b> what {@code invalid_date} reports on — see
     * {@link #intervalDefinitelyInverted}.
     * </p>
     */
    static boolean intervalInverted(@Nullable String s)
    {
        String @Nullable [] halves = intervalHalves(s);
        if (halves == null)
        {
            return false;
        }
        String lo = halfBound(halves[0], false);
        String hi = halfBound(halves[1], true);
        return lo != null && hi != null && lo.compareTo(hi) > 0;
    }


    /**
     * The predicate {@link CalendarDates#isValidDate} reports on (D9 / EC-73): {@code true} iff the
     * interval runs backwards under <b>both</b> readings — as instants <i>and</i> as written.
     *
     * <p>
     * &#9888;&#9888; <b>Why the second conjunct exists.</b> {@link #intervalInverted} normalises
     * through {@link ScalarSemantics#normalizeToUtc}, which <i>assumes an offset-less value is
     * already UTC</i>. That convention is fine for <b>comparing</b> two cells, and the engine uses
     * it everywhere. It is not fine as the sole basis for <b>reporting a conformance violation on
     * one cell</b>: when only one half carries an offset, it is the convention rather than the data
     * that makes the pair backwards. {@code 2012-06-15T10/2012-06-15T10+01:00} has two halves whose
     * text is identical bar the offset, and a sponsor who omitted the offset on one side would get
     * a false {@code invalid_date} from FDA-SD0003 (<code>*DTC</code> &times; Domains ALL) — the
     * exact failure the design exists to avoid.
     * </p>
     *
     * <p>
     * &#9873; A verdict needs certainty; a comparison does not. Requiring both readings keeps the
     * <i>"fires only when no completion is consistent"</i> promise honest, and it is strictly a
     * <b>narrowing</b>: it can only remove reports, never add one. Symmetric offsets shift both
     * halves equally, so a genuinely backwards interval still fires under either reading.
     * </p>
     */
    static boolean intervalDefinitelyInverted(@Nullable String s)
    {
        String @Nullable [] halves = intervalHalves(s);
        if (halves == null || !intervalInverted(s))
        {
            return false;
        }
        return intervalInverted(ScalarSemantics.stripTimezone(halves[0]) + SOLIDUS
                + ScalarSemantics.stripTimezone(halves[1]));
    }


    private static @Nullable String bound(@Nullable String s, boolean high)
    {
        if (s != null && s.indexOf(SOLIDUS) >= 0)
        {
            // The interval arm. Note it also swallows the shapes intervalHalves REJECTS (a/b/c,
            // an empty half): those get null bounds here rather than falling through to the
            // scalar path, which — measured pre-Fix-#212 — handed detectIsoPrecision a string it
            // cannot read and threw a DateTimeException out of the evaluator. Since Fix #220 the
            // scalar path's own readability guard would also refuse them, so this is now
            // defence in depth; it stays because D6/D8 are a deliberate SHAPE verdict of this
            // class ("exactly two components"), not a consequence of what halfBound can decode.
            String @Nullable [] halves = intervalHalves(s);
            return halves == null ? null : intervalBound(halves, high);
        }
        return halfBound(s, high);
    }


    /**
     * <b>D1</b> — the hull an interval actually denotes: the earliest its <i>start</i> could be and
     * the latest its <i>end</i> could be. <b>D3</b> — but never an inverted hull: when the data
     * runs backwards the min/max across both halves is used instead, so {@code lower <= upper}
     * holds by construction and no consumer can be handed one.
     *
     * <p>
     * &#9873; Taking min/max <i>unconditionally</i> would satisfy D3 but break D1: for the
     * satisfiable {@code 2003-12-15/2003-12} it answers {@code 2003-12-01}, when the earliest the
     * value can denote is {@code 2003-12-15}. That is invisible to {@link IsoDateComparison#fires}
     * (a wider hull can only make a leaf under-fire) but wrong for {@link IsoDateComparison#bound},
     * which backs the {@code earliest_possible} / {@code latest_possible} <b>value</b> builtins and
     * is read by rule authors.
     * </p>
     *
     * <p>
     * D7: if <i>any</i> half-bound is unpositionable both public bounds are {@code null}, because
     * {@link IsoDateComparison#fires} discards the pair on a single null anyway — half an answer
     * buys nothing and misleads.
     * </p>
     */
    private static @Nullable String intervalBound(String[] halves, boolean high)
    {
        String loLeft = halfBound(halves[0], false);
        String hiLeft = halfBound(halves[0], true);
        String loRight = halfBound(halves[1], false);
        String hiRight = halfBound(halves[1], true);
        if (loLeft == null || hiLeft == null || loRight == null || hiRight == null)
        {
            return null;
        }
        // Plain compareTo: every bound is rendered at second precision by construction.
        if (loLeft.compareTo(hiRight) <= 0)
        {
            return high ? hiRight : loLeft; // D1 — and lower <= upper follows from the test above.
        }
        // D3 — the data runs backwards; widen to the union so the hull is still well-formed.
        if (high)
        {
            return hiLeft.compareTo(hiRight) >= 0 ? hiLeft : hiRight;
        }
        return loLeft.compareTo(loRight) <= 0 ? loLeft : loRight;
    }


    /**
     * The bound of a single solidus-free value — the whole of the pre-Fix-#212 {@code bound()},
     * extracted so each half of an interval is normalised <b>independently</b> (D2), plus
     * ({@code Fix #220}) the <b>readability</b> guard that used to sit on the interval arm alone —
     * {@link #isReadableCore}, which {@code Fix #226} extracted so {@link #isDetermined} asks the
     * identical question. It is therefore both the scalar path and the interval arm's per-half
     * worker.
     *
     * <p>
     * &#9888;&#9888; <b>Why the guard is here.</b> {@link #truncatedBound} gates on
     * {@link CalendarDates#isValidDate}, which <i>normalises its own argument</i>, and then reads
     * {@code core} itself with {@link ScalarSemantics#detectIsoPrecision} and fixed substrings.
     * {@link #core} strips only ONE decoration, so for a <b>stacked</b> tail ({@code 2012.000ZZ},
     * {@code 2012Z+01:00}) the two disagree — the validator judges a further-normalised string
     * while the reader indexes the raw one. Measured over {@code IsoDateCorpus} immediately before
     * {@code Fix #220}: <b>25</b> solidus-free inputs rendered a string that is not a date
     * ({@code 2012Z-12-31T23:59:59}, {@code 2012-06Z-01T00:00:00}) and one of them
     * ({@code 2012.000ZZ}) threw {@code YearMonth.of(2012, 0)} out of the evaluator. Asking whether
     * the core is readable at all closes both classes; the ids are enumerated in
     * {@code IsoDateBoundsDispatchTest.MovedByFix220}.
     * </p>
     *
     * <p>
     * &#9888;&#9888; <b>And a third class that neither number counts.</b> Five further inputs
     * ({@code 2012-06-15T10:30:45ZZ} and its four siblings) came out of {@link #truncatedBound}'s
     * {@code default} arm — {@code core.substring(0, 19)} — as the perfectly well-formed
     * {@code 2012-06-15T10:30:45}, with the unread residue silently <b>discarded</b>. A census of
     * "is the output a date" cannot find those, which is why the moved set is <b>30</b> and not 25.
     * They are the reason this guard is a <i>precondition</i> on the input rather than a sanity
     * check on the output.
     * </p>
     *
     * <p>
     * &#9873; This is the same defect {@code Fix #209} removed from {@link CalendarDates} — gate
     * with a normalising validator, then read at fixed offsets — which had survived one class over.
     * {@code Fix #212} wrote the guard for the interval arm and its javadoc recorded that the
     * scalar path still lacked it; {@code Fix #220} moved it here, after which both paths are
     * immune by construction and the interval arm needs no wrapper of its own. {@code Fix #226}
     * then found the <i>same</i> defect one method over — {@link #isDetermined} gates on
     * {@link CalendarDates#isCompleteDate}, which strips again too — and closed it by
     * <b>sharing</b> this guard rather than copying it; {@code Fix #229} closed the third and last
     * instance, {@link IsoDateComparison#fires}'s fast path, the same way. See
     * {@link #isReadableCore}.
     * </p>
     *
     * <p>
     * &#9873; It is a <b>guard, not a route</b> — see {@link #isReadableCore}, which also carries
     * the "widening {@link ScalarSemantics#isoComponents} can only admit more values" argument.
     * </p>
     *
     * <p>
     * &#9888; <b>The {@code isMaskedDate} disjunct changes no verdict, and is kept for intent
     * only.</b> {@code isMaskedDate} strips a trailing decoration before matching its shapes and
     * {@code isoComponents} deliberately does not, so 35 corpus inputs ({@code 2012-06--ZZ},
     * {@code 2012---15Z+01:00}, &hellip;) are masked-shaped yet undecodable exactly as written —
     * the disjunct is what lets those through the guard. It is <b>provably</b> a no-op all the
     * same: {@link #maskedBound}'s anchored patterns can only answer for an <i>undecorated</i>
     * masked core, which {@code isoComponents} already decodes, so all 35 reach {@code null} either
     * way. &#9888;&#9888; <b>Do not describe it as load-bearing and do not write a test claiming to
     * catch its removal</b> — none can exist while that containment holds. It is kept so the guard
     * reads as a <i>readability</i> test rather than as a second, weaker copy of the shape
     * dispatch, and so that widening the masked family cannot make it start rejecting.
     * </p>
     *
     * <p>
     * &#9888; It belongs here and <b>not</b> inside {@link #truncatedBound}: that method is
     * package-private precisely so a test can call the branch the dispatch did <i>not</i> take
     * ({@code IsoDateBoundsDispatchTest.Disjoint}), and guarding it there would silence the hazard
     * those tests exist to demonstrate. The two placements are otherwise indistinguishable through
     * the public API, because the masked branch is only ever reached when
     * {@code isMaskedDate(core)} — which the guard admits unconditionally.
     * </p>
     */
    private static @Nullable String halfBound(@Nullable String s, boolean high)
    {
        String core = core(s);
        if (core == null || !isReadableCore(core))
        {
            return null;
        }
        // ⚠ Dispatch on the value's SHAPE, and only on its shape. This used to run truncatedBound
        // first and treat its null as "not right-truncated, try the masks" — which was true only
        // because CalendarDates.isValidDate rejects every masked form. That made a validator's
        // verdict load-bearing for routing: the moment isValidDate accepts masked forms
        // (PLAN-is-partial-date-masked-forms.md Phase 3) the fallback stops being reached and
        // 2012-06-- would be bounded as a right-truncated value — detectIsoPrecision reads it as
        // month precision (length 9 → tier 7) and would emit the nonsense "2012-06---01T00:00:00".
        // Asking isMaskedDate up front makes the two branches disjoint by construction, so
        // widening the validator cannot reroute anything.
        return ScalarSemantics.isMaskedDate(core) ? maskedBound(core, high)
                : truncatedBound(core, high);
    }


    /**
     * Right-truncated (or complete) values: {@code 2012}, {@code 2012-06}, {@code 2012-06-15},
     * {@code …Thh}, {@code …Thh:mm}, {@code …Thh:mm:ss}.
     *
     * <p>
     * &#9888; Only ever reached for a value {@link ScalarSemantics#isMaskedDate} rejects — see
     * {@link #bound}. Package-private so a test can pin that routing by calling both branches
     * directly instead of inferring it from the public result.
     * </p>
     */
    // PMD false positive: AvoidUsingHardCodedIP matches the ISO clock literals ":59:59" /
    // ":00:00" below against its IPv6 pattern. They are time-of-day suffixes, not addresses.
    @SuppressWarnings("PMD.AvoidUsingHardCodedIP")
    static @Nullable String truncatedBound(String core, boolean high)
    {
        if (!CalendarDates.isValidDate(core))
        {
            return null;
        }
        return switch (ScalarSemantics.detectIsoPrecision(core))
        {
        case 4 -> core + (high ? "-12-31" + HIGH_TIME : "-01-01" + LOW_TIME);
        case 7 -> core + (high ? "-" + lastDayOfMonth(core) + HIGH_TIME : "-01" + LOW_TIME);
        case 10 -> core + (high ? HIGH_TIME : LOW_TIME);
        case 13 -> core + (high ? ":59:59" : ":00:00");
        case 16 -> core + (high ? ":59" : ":00");
        // Second precision or finer — already a point (fractional seconds were stripped).
        default -> core.substring(0, Math.min(core.length(), 19));
        };
    }


    /**
     * SDTM masked forms — the branch {@link #bound} routes every
     * {@link ScalarSemantics#isMaskedDate} value to, whatever the calendar validator makes of it.
     *
     * <p>
     * A <b>year</b>-masked value ({@code ----06-15}, {@code --06-15}) is unpositionable and yields
     * {@code null}: nothing anchors it on the calendar, so its hull is unbounded (EC-46 OQ6). So is
     * any shape not matched here — junk is unpositionable, which is the safe verdict.
     * </p>
     *
     * <p>
     * &#9873; {@link #DAY_MASKED} and {@link #MONTH_MASKED} together accept exactly the day- and
     * month-masked halves of {@code isMaskedDate}; the year-masked half falls through to
     * {@code null}. That containment is what makes {@link #bound}'s shape-first dispatch
     * behaviour-preserving, and it is pinned by a test rather than left to inspection.
     * </p>
     */
    static @Nullable String maskedBound(String core, boolean high)
    {
        Matcher day = DAY_MASKED.matcher(core);
        if (day.matches())
        {
            return hull(day.group(1), day.group(2), null, high);
        }
        Matcher month = MONTH_MASKED.matcher(core);
        if (month.matches())
        {
            return hull(month.group(1), null, month.group(2), high);
        }
        return null;
    }


    /** Fills each unknown component with its extreme and validates the result on the calendar. */
    private static @Nullable String hull(String year, @Nullable String month, @Nullable String day,
            boolean high)
    {
        try
        {
            int y = Integer.parseInt(year);
            int m = month != null ? Integer.parseInt(month) : high ? 12 : 1;
            if (m < 1 || m > 12)
            {
                return null;
            }
            int d = day != null ? Integer.parseInt(day)
                    : high ? YearMonth.of(y, m).lengthOfMonth() : 1;
            if (day != null && month == null && high)
            {
                // A masked month with a known day: the latest month that can hold that day still
                // bounds the set, so clamp rather than reject (2012---31 -> December, not invalid).
                m = latestMonthHolding(y, d);
                if (m == 0)
                {
                    return null;
                }
            }
            LocalDate.of(y, m, d); // leap-aware; rejects 2012-02-30, 2012---32, …
            return "%04d-%02d-%02d%s".formatted(y, m, d, high ? HIGH_TIME : LOW_TIME);
        }
        catch (NumberFormatException | DateTimeException _)
        {
            return null;
        }
    }


    /** The last month of {@code year} that has at least {@code day} days, or 0 if none does. */
    private static int latestMonthHolding(int year, int day)
    {
        for (int m = 12; m >= 1; m--)
        {
            if (YearMonth.of(year, m).lengthOfMonth() >= day)
            {
                return m;
            }
        }
        return 0;
    }


    /** Leap-aware last day of a {@code yyyy-MM} value, as a zero-padded two-digit string. */
    private static String lastDayOfMonth(String yearMonth)
    {
        int y = Integer.parseInt(yearMonth.substring(0, 4));
        int m = Integer.parseInt(yearMonth.substring(5, 7));
        return "%02d".formatted(YearMonth.of(y, m).lengthOfMonth());
    }

}
