package net.cumba.corej.core.exec;

/**
 * The calendar components an ISO-8601 value carries, as decoded by
 * {@link ScalarSemantics#isoComponents(String)} — the <b>layout</b> a structural validator returns
 * instead of a bare {@code boolean}.
 *
 * <p>
 * A component that the value does not carry is {@link #ABSENT}. For a <b>right-truncation</b>
 * prefix that is a <em>suffix</em> of the six: {@code 2012} carries only a year, {@code 2012-06} a
 * year and a month, and so on up to second precision.
 * </p>
 *
 * <p>
 * &#9873;&#9873; <b>An absence is NOT necessarily a suffix.</b> {@code Fix #215} taught
 * {@link ScalarSemantics#isoComponents(String)} the SDTM <b>masked</b> shapes, whose whole point is
 * that a hyphen placeholder for a <em>middle</em> unknown lets the components after it keep their
 * position: {@code 2012---15} decodes to an absent <b>month</b> with a <b>present day</b>, and
 * {@code ----06-15} to an absent <b>year</b> with a present month and day. &#9888;&#9888; A
 * consumer must therefore validate every component the layout carries and <b>skip</b> the absent
 * ones — stopping at the first {@link #ABSENT} was the pre-{@code Fix #215} shape and would
 * silently accept {@code 2012---32}.
 * </p>
 *
 * <h2>&#9873; Why this type exists</h2>
 *
 * <p>
 * {@code CalendarDates.isValidDate} used to gate on a {@code boolean} structural check and then
 * read its components with {@code charAt} at <b>fixed offsets</b> (5, 8, 11, 14, 17), relying on a
 * comment that said <i>"the structural check guarantees ASCII digits at these positions"</i>. That
 * invariant did not hold: the gate validated a <em>re-normalised copy</em> of the string while the
 * reads indexed the original, so a value carrying two stacked timezone offsets
 * ({@code 2012-06-15Z+01:00}) passed the gate at length 10 and was then read at index 11 of an
 * 11-character string — an uncaught {@link StringIndexOutOfBoundsException} out of the evaluator,
 * because the handler there catches {@link java.time.DateTimeException} only.
 * </p>
 *
 * <p>
 * &#9888; The same invariant is what {@code PLAN-is-partial-date-masked-forms.md} Phase 3 would
 * destroy deliberately: every SDTM <b>masked</b> form has a non-canonical length ({@code 2012-06-}
 * is 8, {@code 2012---15} is 9) and shifts the offsets of the components that follow the mask, so
 * no fixed-offset reader can survive widening the structural predicate. Handing back the decoded
 * layout removes fixed-offset reads from the consumer entirely: a component that is not there is
 * {@link #ABSENT} rather than a position to index.
 * </p>
 *
 * @param year
 *            the four-digit year, or {@link #ABSENT} for a <b>year-masked</b> value
 *            ({@code ----06-15}); present for every other shape
 * @param month
 *            month {@code 1..12} as written — <b>not</b> range-checked here, or {@link #ABSENT}
 * @param day
 *            day of month as written — not range-checked here, or {@link #ABSENT}
 * @param hour
 *            hour as written — not range-checked here, or {@link #ABSENT}
 * @param minute
 *            minute as written — not range-checked here, or {@link #ABSENT}
 * @param second
 *            second as written — not range-checked here, or {@link #ABSENT}
 */
public record IsoDateComponents(int year, int month, int day, int hour, int minute, int second)
{

    /**
     * The value of a component the string does not carry. Negative so it can never collide with a
     * decoded two-digit field, which is always {@code 0..99}.
     */
    public static final int ABSENT = -1;

}
