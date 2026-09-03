package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.core.exec.ScalarSemantics;
import org.junit.jupiter.api.Test;

/**
 * FU-1 — parity of the ISO-8601 date predicates with the Python oracle
 * ({@code check_operators/helpers.py}: {@code is_valid_date} via
 * {@code isoparse}+{@code date_regex}, {@code is_complete_date} via
 * {@code datetime.fromisoformat}). A {@code --DTC} carrying a legitimate timezone ({@code Z} /
 * {@code ±HH:MM}), fractional seconds, or an interval-of-uncertainty ({@code a/b}) must NOT be
 * flagged {@code invalid_date}. Timezone / fractional values additionally count as <i>complete</i>
 * (Python: fromisoformat-parseable), while an interval is <i>incomplete</i>.
 *
 * <h2>&#9888;&#9888; This class no longer asserts UNCONDITIONAL parity (Fix #212 / EC-73)</h2>
 * <p>
 * Python's {@code date_regex} is purely <b>syntactic</b> about an interval: it accepts
 * {@code 2003-12-10/2003-12-01} as readily as the forward spelling. coreJ does not — SDTMIG
 * &sect;4.4.2 frames the two components as <i>"the beginning and the end of the interval of
 * uncertainty"</i>, so a backwards pair is a wrong data value and {@code invalid_date} fires on it.
 * That is a deliberate <b>Java-only</b> divergence, pinned by
 * {@link #validDate_rejectsInvertedInterval_javaOnlyDivergence()}.
 * </p>
 * <p>
 * &#9873; Every {@code INTERVAL} constant below runs <b>forward</b>, so the parity assertions in
 * this class would have stayed green either way. That is precisely the expired-justification shape
 * this header exists to prevent: a test whose stated justification has silently become false.
 * </p>
 */
class CalendarDatesParityTest
{

    private static final String DATE = "2023-01-15";

    private static final String DATETIME = "2023-01-15T10:30:00";

    private static final String ZULU = "2023-01-15T10:30:00Z";

    private static final String OFFSET = "2023-01-15T10:30:00+01:00";

    private static final String FRACTIONAL = "2023-01-15T10:30:00.000";

    private static final String FRACTIONAL_ZULU = "2023-01-15T10:30:00.000Z";

    private static final String INTERVAL = "2020-01-01/2020-02-01";

    /** &#9888; The same pair written backwards — Python accepts it, coreJ does not (EC-73). */
    private static final String INVERTED_INTERVAL = "2020-02-01/2020-01-01";

    private static final String BAD = "not-a-date";

    // ---- is_valid_date (drives invalid_date) --------------------------------

    @Test
    void validDate_acceptsDecoratedAndIntervalForms()
    {
        for (String s : new String[]
        {
                DATE, DATETIME, ZULU, OFFSET, FRACTIONAL, FRACTIONAL_ZULU, INTERVAL, "2023",
                "2023-01"
        })
        {
            assertTrue(CalendarDates.isValidDate(s), () -> "expected valid: " + s);
        }
    }


    @Test
    void validDate_rejectsGarbageAndCalendarImpossible()
    {
        assertFalse(CalendarDates.isValidDate(BAD));
        assertFalse(CalendarDates.isValidDate("2023-13-01"), "month 13");
        assertFalse(CalendarDates.isValidDate("2023-02-29"), "2023 not a leap year");
        // Interval with a calendar-impossible second half must still be rejected.
        assertFalse(CalendarDates.isValidDate("2020-01-01/2020-02-31"));
        assertFalse(CalendarDates.isValidDate("2020-01-01/" + BAD));
        assertFalse(CalendarDates.isValidDate(null));
    }


    /**
     * &#9888;&#9888; <b>EC-73 — the one place this class is NOT a parity test.</b>
     *
     * <p>
     * Python's {@code date_regex} matches {@code \d{4}-\d{2}-\d{2}/\d{4}-\d{2}-\d{2}} without ever
     * comparing the halves, so {@code is_valid_date("2020-02-01/2020-01-01")} is {@code True}
     * there. coreJ answers {@code false}: SDTMIG &sect;4.4.2 defines the components as the
     * beginning and the end, and every worked example runs forward. Reported through
     * {@code invalid_date} (D9) — there is no conformance-sheet row to author a dedicated rule
     * from, and a dedicated rule would double-report the same cell.
     * </p>
     *
     * <p>
     * &#9873; The divergence propagates to {@code is_partial_date} / {@code is_incomplete_date} as
     * well, which is the intended reading: a backwards interval is not a date <i>at any
     * completeness</i>.
     * </p>
     */
    @Test
    void validDate_rejectsInvertedInterval_javaOnlyDivergence()
    {
        assertTrue(CalendarDates.isValidDate(INTERVAL), "the forward spelling is valid in both");
        assertFalse(CalendarDates.isValidDate(INVERTED_INTERVAL),
                "EC-73: coreJ rejects a backwards interval; Python's date_regex accepts it");
        assertFalse(CalendarDates.isPartialDate(INVERTED_INTERVAL),
                "EC-73: not a date at any completeness");
        assertFalse(CalendarDates.isCompleteDate(INVERTED_INTERVAL));
        // ⚠ The STRUCTURAL predicate is untouched — it is the legacy shape check, and Fix #212
        // deliberately did not widen its blast radius beyond CalendarDates.
        assertTrue(ScalarSemantics.isPartialDate(INVERTED_INTERVAL),
                "the structural predicate stays syntactic, exactly like Python's date_regex");
    }


    /**
     * &#9873; <b>A divergence Fix #212 CLOSED rather than opened</b>, found by its code review.
     *
     * <p>
     * Python's {@code date_regex} (`check_operators/helpers.py`) is {@code $}-anchored with
     * <b>exactly one</b> optional {@code /}-half, so {@code 2003/2004/2005} is {@code False} there.
     * coreJ used to answer {@code true} by recursing on the tail — an over-acceptance nobody had
     * noticed. Worse, the recursion made the EC-73 ordering test skip the <b>first</b> pair, so
     * appending a third component <i>laundered</i> an inverted interval.
     * </p>
     */
    @Test
    void validDate_rejectsMultiComponentIntervals_parityRestored()
    {
        assertFalse(CalendarDates.isValidDate("2003/2004/2005"),
                "SDTMIG §4.4.2 defines exactly two components, and Python's date_regex agrees");
        assertFalse(CalendarDates.isValidDate("2003-01-01/2003-02-01/2003-03-01"));
        // The laundering this closes: the same inverted pair, alone and with a tail appended.
        assertFalse(CalendarDates.isValidDate("2005/2003"), "inverted");
        assertFalse(CalendarDates.isValidDate("2005/2003/2004"),
                "a third component must not launder an inverted first pair");
        // The two-component forms are unaffected.
        assertTrue(CalendarDates.isValidDate("2003/2004"));
    }


    /**
     * &#9888;&#9888; <b>The conservative half of EC-73, and the one most likely to be got
     * wrong.</b>
     *
     * <p>
     * {@code IsoDateBounds.intervalInverted} compares <i>instants</i>, and
     * {@code ScalarSemantics.normalizeToUtc} assumes an offset-less value is <b>already UTC</b>.
     * When only one half carries an offset, that <i>convention</i> — not the data — is what makes
     * the pair look backwards. Reporting `invalid_date` on it would be a false positive under
     * FDA-SD0003 (<code>*DTC</code> &times; Domains ALL) on data whose two halves are textually
     * identical bar the offset.
     * </p>
     *
     * <p>
     * &#9873; So {@code isValidDate} reports on {@code intervalDefinitelyInverted}, which requires
     * the pair to run backwards <b>as written</b> as well. A verdict needs certainty; a comparison
     * does not.
     * </p>
     */
    @Test
    void validDate_doesNotReportAnInversionThatOnlyTheUtcConventionCreates()
    {
        String asymmetric = "2020-01-15T10:00/2020-01-15T10:30+01:00";
        // The instant reading DOES say inverted — 10:30+01:00 is 09:30Z, before 10:00Z…
        assertTrue(IsoDateBounds.intervalInverted(asymmetric),
                "precondition: the instant reading sees an inversion");
        // …and that is exactly why the reported predicate must not be that one.
        assertFalse(IsoDateBounds.intervalDefinitelyInverted(asymmetric));
        assertTrue(CalendarDates.isValidDate(asymmetric),
                "a one-sided offset is a precision/format question (CDISC-SEND-0070), not an "
                        + "ordering violation");
        // Symmetric offsets shift both halves equally, so a genuine inversion still fires.
        assertTrue(CalendarDates.isValidDate("2020-01-15T10:00+01:00/2020-01-15T10:30+01:00"));
        assertFalse(CalendarDates.isValidDate("2020-01-15T10:30+01:00/2020-01-15T10:00+01:00"),
                "backwards under BOTH readings — still rejected");
    }


    /**
     * The conservative half of EC-73: an interval that is merely <i>imprecise</i> is still valid.
     * If this ever goes red, {@code invalid_date} has started false-positiving on legitimate
     * uncertainty — which is the failure FDA-SD0003 ({@code *DTC} &times; Domains ALL) would
     * broadcast across every submission.
     */
    @Test
    void validDate_stillAcceptsEverySatisfiableInterval()
    {
        for (String s : new String[]
        {
                // SDTMIG v3.4 §4.4.2's own worked examples
                "2003-12-15T10:00/2003-12-15T10:30", "2003-01-01/2003-02-15",
                "2003-12-01/2003-12-10", "2003-01-01/2003-06-30",
                // a coarser end that could still contain the start — NOT definitely inverted
                "2003-12-15/2003-12", "2003/2003-01-01",
                // a degenerate point
                "2003-12-15/2003-12-15",
                // asymmetric precision: CDISC-SEND-0070's business, not invalid_date's
                "2003-01-01/2003-06"
        })
        {
            assertTrue(CalendarDates.isValidDate(s), () -> "must stay valid: " + s);
        }
    }

    // ---- is_complete_date ---------------------------------------------------


    @Test
    void completeDate_timezoneAndFractionalCountAsComplete()
    {
        for (String s : new String[]
        {
                DATE, DATETIME, ZULU, OFFSET, FRACTIONAL, FRACTIONAL_ZULU
        })
        {
            assertTrue(CalendarDates.isCompleteDate(s), () -> "expected complete: " + s);
        }
    }


    @Test
    void completeDate_partialsAndIntervalAreIncomplete()
    {
        assertFalse(CalendarDates.isCompleteDate("2023"));
        assertFalse(CalendarDates.isCompleteDate("2023-01"));
        // An interval is never a single complete instant (Python fromisoformat fails on it).
        assertFalse(CalendarDates.isCompleteDate(INTERVAL));
        assertFalse(CalendarDates.isCompleteDate(BAD));
    }

    // ---- is_incomplete_date / is_partial_date -------------------------------


    @Test
    void partialDate_yearMonthAndIntervalArePartial()
    {
        assertTrue(CalendarDates.isPartialDate("2023"));
        assertTrue(CalendarDates.isPartialDate("2023-01"));
        assertTrue(CalendarDates.isPartialDate(INTERVAL), "an interval is valid-but-not-complete");
    }


    @Test
    void partialDate_completeAndDecoratedFormsAreNotPartial()
    {
        assertFalse(CalendarDates.isPartialDate(DATE));
        assertFalse(CalendarDates.isPartialDate(ZULU), "a tz datetime is complete, not partial");
        assertFalse(CalendarDates.isPartialDate(FRACTIONAL));
        assertFalse(CalendarDates.isPartialDate(BAD));
    }

    // ---- structural predicates (invalidDateStructural / OperatorRegistry) ---


    @Test
    void scalarPartialDate_acceptsDecoratedAndInterval()
    {
        assertTrue(ScalarSemantics.isPartialDate(ZULU));
        assertTrue(ScalarSemantics.isPartialDate(OFFSET));
        assertTrue(ScalarSemantics.isPartialDate(FRACTIONAL));
        assertTrue(ScalarSemantics.isPartialDate(FRACTIONAL_ZULU));
        assertTrue(ScalarSemantics.isPartialDate(INTERVAL));
        assertFalse(ScalarSemantics.isPartialDate(BAD));
        assertFalse(ScalarSemantics.isPartialDate(null));
        assertFalse(ScalarSemantics.isPartialDate(""));
    }


    @Test
    void scalarCompleteDate_timezoneAndFractionalComplete_intervalNot()
    {
        assertTrue(ScalarSemantics.isCompleteDate(ZULU));
        assertTrue(ScalarSemantics.isCompleteDate(OFFSET));
        assertTrue(ScalarSemantics.isCompleteDate(FRACTIONAL));
        assertFalse(ScalarSemantics.isCompleteDate("2023"));
        assertFalse(ScalarSemantics.isCompleteDate(INTERVAL));
        assertFalse(ScalarSemantics.isCompleteDate(null));
    }

    // ---- normalization helpers ----------------------------------------------


    @Test
    void stripHelpers_removeDecorationsInOrder()
    {
        assertEquals("2023-01-15T10:30:00", ScalarSemantics.stripTimezone(ZULU));
        assertEquals("2023-01-15T10:30:00", ScalarSemantics.stripTimezone(OFFSET));
        assertEquals("2023-01-15T10:30:00", ScalarSemantics.stripFractionalSeconds(FRACTIONAL));
        assertEquals("2023-01-15T10:30:00", ScalarSemantics
                .stripFractionalSeconds(ScalarSemantics.stripTimezone(FRACTIONAL_ZULU)));
        // A '.' not followed exclusively by digits is left intact.
        assertEquals("2023-01-15T10:30:00.x",
                ScalarSemantics.stripFractionalSeconds("2023-01-15T10:30:00.x"));
        assertEquals(DATE, ScalarSemantics.stripTimezone(DATE));
        assertEquals(DATE, ScalarSemantics.stripFractionalSeconds(DATE));
    }
}
