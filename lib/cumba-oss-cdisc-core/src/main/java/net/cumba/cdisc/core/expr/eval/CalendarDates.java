package net.cumba.cdisc.core.expr.eval;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.Month;

import net.cumba.cdisc.core.exec.IsoDateComponents;
import net.cumba.cdisc.core.exec.ScalarSemantics;

/**
 * Calendar-validating ISO-8601 date predicates — the native enhancement over the legacy
 * structural-only checks (decision #4, three-predicate model). On top of the structural shape check
 * ({@link ScalarSemantics#isoComponents(String)}, the decoder behind
 * {@link ScalarSemantics#isPartialDate(String)}: lengths 4/7/10/13/16/19 with the right separators
 * and ASCII digits, plus the SDTM masked shapes), these additionally reject calendar-impossible
 * values: month outside 1–12, an impossible day-of-month (leap-year aware via {@link LocalDate}),
 * and out-of-range hour/minute/second. So {@code 2024-13}, {@code 2023-02-29} and
 * {@code 2024-01-01T25:00} are <i>invalid</i> here even though the legacy structural validators
 * accept them — an intentional, parity-whitelisted divergence (Phase 5).
 *
 * <p>
 * &#9873;&#9873; <b>{@code Fix #215} made these mask-aware.</b> An SDTM masked value
 * ({@code 2012-06--}, {@code 2012---15}, {@code ----06-15}) is a <em>legal</em> partial date, so it
 * is now <b>valid</b> here rather than reported by {@code invalid_date}. A masked component is
 * <em>unknown</em>, not absent: the calendar checks below still apply to every component the value
 * <b>does</b> carry, so {@code 2012---32} and {@code ----13-01} remain invalid. &#9888; This
 * diverges from the Python {@code date_regex} oracle by design — filed {@code java-only-accepted}
 * under the Java-first policy, not ported.
 * </p>
 *
 * <p>
 * Three predicates (all require the value to be calendar-valid ISO at its precision):
 * </p>
 * <ul>
 * <li>{@link #isValidDate(String)} — umbrella: valid at any precision.</li>
 * <li>{@link #isCompleteDate(String)} — valid and all date components present (length
 * 10/16/19).</li>
 * <li>{@link #isPartialDate(String)} — valid but missing components (year- or month-precision, a
 * masked component, or a truncated time; a time without a full date is not possible since the date
 * prefix is required).</li>
 * </ul>
 *
 * <p>
 * A fourth predicate, {@link #isCompleteDatePart(String)} (Fix #157), asks only about the <em>date
 * portion</em> and ignores the time entirely — see its javadoc.
 * </p>
 */
public final class CalendarDates
{

    /**
     * The length of a complete ISO-8601 date portion, {@code YYYY-MM-DD}. The
     * {@link #isCompleteDatePart(String)} operand is truncated to this many characters before the
     * completeness test, which is exactly what {@code prefix(X, 10)} yields.
     */
    private static final int DATE_PART_LENGTH = 10;

    private CalendarDates()
    {
    }


    /**
     * {@code true} iff {@code s} is a calendar-real ISO-8601 value at any precision.
     *
     * <p>
     * An <b>interval of uncertainty</b> {@code a/b} (SDTMIG &sect;4.4.2) is valid iff it has
     * <b>exactly two</b> components, both are calendar-valid, <b>and</b> it runs forward —
     * {@code 2003-12-10/2003-12-01} is rejected (Fix #212, EC-73; Python accepts it). The ordering
     * test is {@link IsoDateBounds#intervalDefinitelyInverted}, which is conservative twice over:
     * it fires only when no completion of either half is consistent with the other, and only when
     * the pair is backwards <i>as written</i> as well as <i>as instants</i>. So
     * {@code 2003-12-15/2003-12} (Dec 15 lies inside December) and
     * {@code 2012-06-15T10/2012-06-15T10+01:00} (backwards only under the offset-less-means-UTC
     * convention) both stay valid.
     * </p>
     */
    public static boolean isValidDate(String s)
    {
        if (s == null)
        {
            return false;
        }
        // ISO interval "a/b" (SDTMIG v3.4 §4.4.2 / SENDIG v3.1.1 §4.4.2 — an interval of
        // uncertainty is RECOMMENDED for an imprecise --DTC, not merely tolerated): valid iff both
        // halves are calendar-valid AND the pair runs forward.
        // ⚠ Fix #212 / EC-73 — the ordering conjunct is a coreJ-only divergence. The IG frames the
        // two components as "the beginning and the end of the interval of uncertainty", so a
        // backwards pair is a wrong data value; Python's date_regex is purely syntactic and
        // accepts it. Reported through invalid_date (D9) rather than a new rule, because no
        // conformance sheet carries a row to author one from.
        // ⚠ Cycle note: intervalInverted calls back into isValidDate — but only on a HALF, which
        // carries no solidus, so the recursion terminates in one step. Both classes live in
        // net.cumba.cdisc.core.expr.eval; the cycle is intra-package and deliberate.
        int slash = s.indexOf('/');
        if (slash >= 0)
        {
            // D6 — SDTMIG defines EXACTLY two components. ⚠ This arm used to recurse on the tail,
            // which accepted a/b/c. Two things were wrong with that, both found by the Fix #212
            // review: (1) it is NOT Python parity — the fork's date_regex
            // (check_operators/helpers.py) is $-anchored with exactly one optional half, so
            // "2003/2004/2005" is False there and was true here; (2) the ordering conjunct below
            // was silently skipped for the FIRST pair, because intervalHalves rejects a
            // three-component value, so "2005/2003" was invalid while "2005/2003/2004" was valid.
            // Rejecting outright fixes both, aligns isValidDate with IsoDateBounds.intervalHalves,
            // and removes an unbounded recursion.
            if (s.indexOf('/', slash + 1) >= 0)
            {
                return false;
            }
            return isValidDate(s.substring(0, slash)) && isValidDate(s.substring(slash + 1))
                    && !IsoDateBounds.intervalDefinitelyInverted(s);
        }
        // Reduce to the date/time core (drop a trailing timezone then fractional seconds), then
        // decode that core into its components. The decoder both gates and reads, so — unlike the
        // superseded "structural boolean + charAt at fixed offsets" shape — the calendar checks
        // below cannot index a position the gate never inspected. See IsoDateComponents.
        String core = ScalarSemantics.stripFractionalSeconds(ScalarSemantics.stripTimezone(s));
        IsoDateComponents c = ScalarSemantics.isoComponents(core);
        if (c == null)
        {
            return false;
        }
        // Validate every component the value CARRIES and skip the ones it does not. Every decoded
        // field is 0..99 by construction, so only the ranges below can reject.
        //
        // ⚠⚠ Fix #215 — this used to RETURN at the first ABSENT component, which was sound only
        // while absence was always a trailing run. It is not: a masked value replaces a MIDDLE
        // component, so 2012---15 carries an absent month and a present day, and ----06-15 an
        // absent year with a present month and day. Returning early there would have accepted
        // 2012---32 and ----13-01. See IsoDateComponents.
        if (c.month() != IsoDateComponents.ABSENT && (c.month() < 1 || c.month() > 12))
        {
            return false;
        }
        if (c.day() != IsoDateComponents.ABSENT && !dayIsPossible(c))
        {
            return false;
        }
        if (c.hour() != IsoDateComponents.ABSENT && c.hour() > 23)
        {
            return false;
        }
        if (c.minute() != IsoDateComponents.ABSENT && c.minute() > 59)
        {
            return false;
        }
        return c.second() == IsoDateComponents.ABSENT || c.second() <= 59;
    }


    /**
     * Whether {@code c}'s day-of-month is possible — {@code c.day()} is known to be present, and
     * {@code c.month()} has already been range-checked when it is present.
     *
     * <p>
     * &#9873; A masked component is <b>unknown, not absent from the calendar</b>: the value denotes
     * some real date, so the day is possible exactly when <em>some</em> completion of the unknown
     * components admits it. That is the same "interval of uncertainty" reading
     * {@code IsoDateBounds.hull} uses when it clamps {@code 2012---31} to December rather than
     * rejecting it, and the two must not disagree about which masked values exist.
     * </p>
     */
    private static boolean dayIsPossible(IsoDateComponents c)
    {
        int day = c.day();
        if (day < 1)
        {
            return false;
        }
        if (c.month() == IsoDateComponents.ABSENT)
        {
            // Month unknown (2012---15): possible iff some month can hold the day. Every year has
            // a 31-day month, so this is the plain 1..31 range and the year cannot narrow it.
            return day <= 31;
        }
        if (c.year() == IsoDateComponents.ABSENT)
        {
            // Year unknown (----02-29): possible iff some year's copy of that month can hold the
            // day — February's 29 in a leap year, every other month's fixed length.
            return day <= Month.of(c.month()).maxLength();
        }
        try
        {
            // Leap-aware: throws for 2023-02-29, 2024-04-31, etc. This is the ONLY statement the
            // handler guards, so the catch cannot silently absorb an unrelated failure.
            LocalDate.of(c.year(), c.month(), day);
        }
        catch (DateTimeException _)
        {
            return false;
        }
        return true;
    }


    /** {@code true} iff {@code s} is calendar-valid and structurally complete (length 10/16/19). */
    public static boolean isCompleteDate(String s)
    {
        return isValidDate(s) && ScalarSemantics.isCompleteDate(s);
    }


    /** {@code true} iff {@code s} is calendar-valid but missing components (not complete). */
    public static boolean isPartialDate(String s)
    {
        return isValidDate(s) && !ScalarSemantics.isCompleteDate(s);
    }


    /**
     * {@code true} iff the <b>date portion</b> of {@code s} — its first {@value #DATE_PART_LENGTH}
     * characters — is a complete, calendar-valid {@code YYYY-MM-DD} date, <b>whatever follows
     * it</b> (Fix #157). Exactly equivalent to {@code isCompleteDate(prefix(s, 10))}, since
     * {@code prefix} yields the whole string when it is shorter than the requested length and no
     * string shorter than {@value #DATE_PART_LENGTH} can be a complete date.
     *
     * <p>
     * This is the predicate SDTMIG §4.4.4's study-day derivation actually needs (<i>"comparing the
     * <b>date portion</b> of the respective date/time variables"</i>). It differs from the other
     * three on exactly one class of value: a complete date carrying a <b>truncated time</b>, e.g.
     * {@code 2020-01-01T10} (length 13), which {@link #isCompleteDate(String)} rejects and
     * {@link #isPartialDate(String)} accepts, but whose date portion is complete.
     * </p>
     *
     * <table>
     * <caption>Contract</caption>
     * <tr>
     * <th>{@code s}</th>
     * <th>result</th>
     * </tr>
     * <tr>
     * <td>{@code null} / {@code ""}</td>
     * <td>{@code false}</td>
     * </tr>
     * <tr>
     * <td>{@code "banana"}, {@code "2020-13-45"}, {@code "2020-02-30"}</td>
     * <td>{@code false}</td>
     * </tr>
     * <tr>
     * <td>{@code "2020"}, {@code "2020-01"}</td>
     * <td>{@code false}</td>
     * </tr>
     * <tr>
     * <td>{@code "2020-01-01"}</td>
     * <td>{@code true}</td>
     * </tr>
     * <tr>
     * <td>{@code "2020-01-01T10"}</td>
     * <td>{@code true}</td>
     * </tr>
     * <tr>
     * <td>{@code "2020-01-01T10:30:00"}</td>
     * <td>{@code true}</td>
     * </tr>
     * <tr>
     * <td>{@code "2020-01-01Tbanana"}</td>
     * <td>{@code true} — only the date portion is judged</td>
     * </tr>
     * </table>
     *
     * <p>
     * ⚠ The last row is deliberate, not an oversight: a structurally broken <em>time</em> is the
     * business of {@code invalid_date}, which owns it in both engines. This predicate asserts one
     * thing only.
     * </p>
     */
    public static boolean isCompleteDatePart(String s)
    {
        return s != null && s.length() >= DATE_PART_LENGTH
                && isCompleteDate(s.substring(0, DATE_PART_LENGTH));
    }

}
