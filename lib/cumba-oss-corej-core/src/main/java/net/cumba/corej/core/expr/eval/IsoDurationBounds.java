package net.cumba.corej.core.expr.eval;

import java.math.BigDecimal;
import net.cumba.corej.core.exec.ScalarSemantics;
import org.jspecify.annotations.Nullable;

/**
 * The hull of an ISO-8601 <b>duration</b> in seconds — the {@code duration}-type sibling of
 * {@link IsoDateBounds} / {@link IsoTimeBounds} ({@code PLAN-engine-gap-dur-order} §5, runbook W1b
 * DO; owner rulings §6.1 Q1 / Q2, 2026-10-07).
 *
 * <p>
 * A duration carries no calendar, so its exact designators have one length each: {@code W} = 7 d,
 * {@code D} = 86 400 s (no DST), {@code H} = 3 600 s, {@code M} after {@code T} = 60 s, {@code S} =
 * 1 s, a fractional lowest component as written. <b>Months and years are ranges</b> (Q1 — the only
 * reading consistent with the date family's "definitely" rule): {@code PnM} = [n × 28 d, n × 31 d],
 * {@code PnY} = [n × 365 d, n × 366 d], a conservative envelope. So {@code PT90M}, {@code PT1H30M}
 * and {@code P0.0625D} are the same point, {@code P1W} is {@code P7D}, and {@code P1M} against
 * {@code P30D} is not decided either way. A leading {@code -} negates the hull (the
 * {@code invalid_duration} negative grammar, {@code negative=} default {@code true}).
 * </p>
 *
 * <p>
 * ⭐ <b>One parser.</b> The text is read by {@link ScalarSemantics#parseDuration} — the same call
 * the validity predicates answer through — so a value is positionable here <b>iff</b>
 * {@code is_valid_duration} says it is a duration (pinned by {@code IsoDurationBoundsTest}).
 * Everything else — blank, junk, {@code P}, a date — is <b>unpositionable</b>: {@link #hull}
 * answers {@code null}, and {@link IsoDurationComparison} reads that as SPEC §5.2(4)'s unbounded
 * hull. Unbounded is out of band ({@code null}), never an in-band saturated sentinel.
 * </p>
 *
 * <p>
 * <b>The engine's own carrier form.</b> {@code duration_between(start, end)} answers a range of
 * durations (each instant is its own hull), which ISO 8601 cannot spell. It is rendered as
 * {@code lower/upper}, two point durations ({@link #renderHull}: {@code P14DT1S/P45DT23H59M59S}),
 * which {@link #hull(String, boolean)} reads back as that range <b>only</b> where the compiler
 * knows the operand is {@code duration_between}'s own result (written, inside a {@code duration(…)}
 * marker, or through a binding holding it). It is <em>not</em> a valid ISO duration for the
 * validity predicates, so a DATA cell spelled {@code PT1H/PT2H} is unpositionable like any other
 * invalid duration (plan §5; runbook W1b fold, R5 B-L1) — the carrier is the family's internal
 * spelling, legible in a report.
 * </p>
 */
public final class IsoDurationBounds
{

    /**
     * A hull in seconds, {@code lower <= upper}; a point when they are equal.
     *
     * @param lower
     *            the shortest duration the value could denote, in seconds
     * @param upper
     *            the longest, in seconds
     */
    public record Hull(double lower, double upper)
    {

        /** Whether the value denotes exactly one duration. */
        public boolean isPoint()
        {
            return lower == upper;
        }
    }

    private static final BigDecimal SECONDS_PER_MINUTE = BigDecimal.valueOf(60);

    private static final BigDecimal SECONDS_PER_HOUR = BigDecimal.valueOf(3_600);

    private static final BigDecimal SECONDS_PER_DAY = BigDecimal.valueOf(86_400);

    private static final BigDecimal SECONDS_PER_WEEK = SECONDS_PER_DAY
            .multiply(BigDecimal.valueOf(7));

    /** The shortest calendar month, in seconds (Q1's envelope). */
    private static final BigDecimal MONTH_LOWER = SECONDS_PER_DAY.multiply(BigDecimal.valueOf(28));

    /** The longest calendar month, in seconds. */
    private static final BigDecimal MONTH_UPPER = SECONDS_PER_DAY.multiply(BigDecimal.valueOf(31));

    /** The shortest calendar year, in seconds. */
    private static final BigDecimal YEAR_LOWER = SECONDS_PER_DAY.multiply(BigDecimal.valueOf(365));

    /** The longest calendar year, in seconds. */
    private static final BigDecimal YEAR_UPPER = SECONDS_PER_DAY.multiply(BigDecimal.valueOf(366));

    /** The separator of the engine's own {@code lower/upper} carrier form. */
    private static final char SOLIDUS = '/';

    private IsoDurationBounds()
    {
    }


    /**
     * The hull of a value, or {@code null} when it is not a duration — an ISO-8601 duration
     * (signed). A {@code lower/upper} text is unpositionable here: the carrier is read only by
     * {@link #hull(String, boolean)} for {@code duration_between}'s own result.
     *
     * @param s
     *            the text
     * @return the hull, or {@code null} when unpositionable
     */
    public static @Nullable Hull hull(@Nullable String s)
    {
        return hull(s, false);
    }


    /**
     * The hull of {@code s}, or {@code null} when unpositionable — an ISO-8601 duration (signed),
     * and, where {@code carrier} is set ({@code duration_between}'s own result), also the
     * {@code lower/upper} carrier of two point durations written lower first.
     *
     * @param s
     *            the text
     * @param carrier
     *            whether the text is {@code duration_between}'s own result, so its
     *            {@code lower/upper} carrier positions
     * @return the hull, or {@code null} when unpositionable
     */
    public static @Nullable Hull hull(@Nullable String s, boolean carrier)
    {
        if (s == null)
        {
            return null;
        }
        String t = s.trim();
        int slash = t.indexOf(SOLIDUS);
        if (slash < 0)
        {
            return single(t);
        }
        if (!carrier)
        {
            return null;
        }
        Hull lower = single(t.substring(0, slash));
        Hull upper = single(t.substring(slash + 1));
        if (lower == null || upper == null || !lower.isPoint() || !upper.isPoint()
                || lower.lower() > upper.lower())
        {
            return null;
        }
        return new Hull(lower.lower(), upper.lower());
    }


    private static @Nullable Hull single(String s)
    {
        ScalarSemantics.IsoDuration d = ScalarSemantics.parseDuration(s, true);
        if (d == null)
        {
            return null;
        }
        BigDecimal lower = BigDecimal.ZERO;
        BigDecimal upper = BigDecimal.ZERO;
        BigDecimal exact = BigDecimal.ZERO;
        exact = exact.add(scaled(d.weeks(), SECONDS_PER_WEEK))
                .add(scaled(d.days(), SECONDS_PER_DAY)).add(scaled(d.hours(), SECONDS_PER_HOUR))
                .add(scaled(d.minutes(), SECONDS_PER_MINUTE))
                .add(d.seconds() == null ? BigDecimal.ZERO : d.seconds());
        lower = lower.add(exact).add(scaled(d.months(), MONTH_LOWER))
                .add(scaled(d.years(), YEAR_LOWER));
        upper = upper.add(exact).add(scaled(d.months(), MONTH_UPPER))
                .add(scaled(d.years(), YEAR_UPPER));
        if (d.negative())
        {
            // the bounds swap; `+ 0.0` keeps a zero positive so a record compares as the
            // positive spelling's does
            return new Hull(-upper.doubleValue() + 0.0, -lower.doubleValue() + 0.0);
        }
        return new Hull(lower.doubleValue(), upper.doubleValue());
    }


    private static BigDecimal scaled(@Nullable BigDecimal component, BigDecimal unit)
    {
        return component == null ? BigDecimal.ZERO : component.multiply(unit);
    }


    /**
     * The shortest duration {@code s} could denote, rendered as a point duration ({@link #render}),
     * or {@code null} when unpositionable — {@code earliest_possible} over a {@code duration(…)}
     * operand (Q6).
     */
    public static @Nullable String lower(@Nullable String s)
    {
        Hull h = hull(s);
        return h == null ? null : render(h.lower());
    }


    /** The longest duration {@code s} could denote, as {@link #lower}; {@code latest_possible}. */
    public static @Nullable String upper(@Nullable String s)
    {
        Hull h = hull(s);
        return h == null ? null : render(h.upper());
    }


    /**
     * The canonical point form of {@code seconds}: {@code P[nD][T[nH][nM][nS]]} with every
     * component below its unit's size ({@code PT1M30S}, never {@code PT90S}), a fraction on the
     * seconds only, a leading {@code -} for a negative duration, {@code PT0S} for zero. Every
     * rendering positions back to the same point.
     *
     * @param seconds
     *            the duration in seconds
     * @return the ISO-8601 text
     */
    public static String render(double seconds)
    {
        BigDecimal total = BigDecimal.valueOf(Math.abs(seconds));
        BigDecimal days = whole(total.divideToIntegralValue(SECONDS_PER_DAY));
        BigDecimal rest = total.subtract(days.multiply(SECONDS_PER_DAY));
        BigDecimal hours = whole(rest.divideToIntegralValue(SECONDS_PER_HOUR));
        rest = rest.subtract(hours.multiply(SECONDS_PER_HOUR));
        BigDecimal minutes = whole(rest.divideToIntegralValue(SECONDS_PER_MINUTE));
        BigDecimal secs = rest.subtract(minutes.multiply(SECONDS_PER_MINUTE));
        StringBuilder sb = new StringBuilder(24);
        if (seconds < 0)
        {
            sb.append('-');
        }
        sb.append('P');
        if (days.signum() != 0)
        {
            sb.append(days.toPlainString()).append('D');
        }
        boolean time = hours.signum() != 0 || minutes.signum() != 0 || secs.signum() != 0;
        if (time)
        {
            sb.append('T');
            if (hours.signum() != 0)
            {
                sb.append(hours.toPlainString()).append('H');
            }
            if (minutes.signum() != 0)
            {
                sb.append(minutes.toPlainString()).append('M');
            }
            if (secs.signum() != 0)
            {
                sb.append(secs.stripTrailingZeros().toPlainString()).append('S');
            }
        }
        else if (days.signum() == 0)
        {
            sb.append("T0S");
        }
        return sb.toString();
    }


    /** An integral quotient at scale 0, so it renders as {@code 31}, never {@code 31.0}. */
    private static BigDecimal whole(BigDecimal quotient)
    {
        return quotient.setScale(0, java.math.RoundingMode.DOWN);
    }


    /**
     * The carrier of a hull: {@link #render} of a point, else {@code lower/upper} — two point
     * durations, which {@link #hull} reads back as the range.
     *
     * @param lower
     *            the shortest duration, in seconds
     * @param upper
     *            the longest, in seconds
     * @return the text
     */
    public static String renderHull(double lower, double upper)
    {
        return lower == upper ? render(lower) : render(lower) + SOLIDUS + render(upper);
    }

}
