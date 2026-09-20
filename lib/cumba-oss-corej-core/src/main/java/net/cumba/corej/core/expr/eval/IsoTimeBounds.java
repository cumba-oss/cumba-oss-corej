package net.cumba.corej.core.expr.eval;

import net.cumba.corej.core.exec.ScalarSemantics;
import org.jspecify.annotations.Nullable;

/**
 * The hull of a possibly-incomplete ISO-8601 <b>time-of-day</b> value — the {@code time}-type
 * sibling of {@link IsoDateBounds} (SPEC §1.1 / §5.1, D22: <i>"time-of-day is its own type … it
 * inherits the interval problem: {@code T10} is a partial time"</i>).
 *
 * <p>
 * A partial time denotes a <b>range</b> of instants-of-day: {@code 10} (or {@code T10}) spans
 * {@code [10:00:00, 10:59:59]}, {@code 10:30} spans {@code [10:30:00, 10:30:59]}, and
 * {@code 10:30:45} is a point. Both bounds are rendered at <b>second</b> precision
 * ({@code HH:mm:ss}), mirroring {@link IsoDateBounds}' second-precision rendering, so
 * like-with-like bound comparisons are plain lexicographic.
 * </p>
 *
 * <p>
 * <b>Accepted shapes</b>, after trimming: an optional leading {@code 'T'} (SPEC §1.1 spells the
 * partial time {@code T10}), then {@code HH}, {@code HH:MM} or {@code HH:MM:SS}, optionally with a
 * fractional-second tail ({@code .fff} — stripped, exactly as {@link IsoDateBounds#core} strips it)
 * and a trailing offset ({@code Z} / {@code ±HH:MM} / {@code ±HHMM}). An offset is applied
 * <b>instant-preserving</b> and the value rendered in UTC (D25's normalisation feature, via
 * {@link ScalarSemantics#normalizeToUtc}'s bare-time arm), so {@code 13:30:00+02:00} bounds as
 * {@code 11:30:00}. ⚠ An <b>hour-only</b> value with an offset is not a shape
 * {@code normalizeToUtc} can position ({@code BARE_TIME_SHAPE} requires minutes); its offset is
 * stripped un-applied — the legacy fallback — and the remaining {@code HH} hulls normally.
 * </p>
 *
 * <p>
 * Everything else — blank, junk, out-of-range fields ({@code 25}, {@code 10:71}), a date, a
 * datetime, an interval ({@code 10:00/10:30} — no ruling and no corpus user gives the shape a
 * meaning for times) — is <b>unpositionable</b>: {@link #lower}/{@link #upper} return {@code null},
 * and {@link IsoTimeComparison} reads that as SPEC §5.2(4)'s unbounded hull. As in
 * {@link IsoDateBounds}, unbounded is out of band ({@code null}), never an in-band saturated
 * sentinel.
 * </p>
 */
public final class IsoTimeBounds
{

    /** {@code HH} — hour precision, the coarsest a time value can carry. */
    static final int HOUR_PRECISION = 2;

    /** {@code HH:MM} — minute precision. */
    static final int MINUTE_PRECISION = 5;

    /** {@code HH:MM:SS} — second precision, the width both bounds are rendered at. */
    static final int SECOND_PRECISION = 8;

    private IsoTimeBounds()
    {
    }


    /** The earliest instant-of-day {@code s} could denote, or {@code null} when unpositionable. */
    public static @Nullable String lower(@Nullable String s)
    {
        return bound(core(s), false);
    }


    /** The latest instant-of-day {@code s} could denote, or {@code null} when unpositionable. */
    public static @Nullable String upper(@Nullable String s)
    {
        return bound(core(s), true);
    }


    /**
     * The normalised, validated core of a time value — trimmed, offset applied instant-preserving
     * (UTC rendering, D25), fractional seconds stripped, the optional leading {@code 'T'} removed —
     * or {@code null} when {@code s} is not a positionable time-of-day. The core's <b>length</b> is
     * its precision tier: {@link #HOUR_PRECISION}, {@link #MINUTE_PRECISION} or
     * {@link #SECOND_PRECISION}.
     */
    static @Nullable String core(@Nullable String s)
    {
        if (s == null)
        {
            return null;
        }
        String t = s.trim();
        if (t.isEmpty())
        {
            return null;
        }
        // Offset arithmetic reuses the SAME normalisation the date family uses (never a copy):
        // normalizeToUtc applies a bare-time offset instant-preserving and renders in UTC at the
        // input's precision. Shapes it cannot position (hour-only with an offset) fall back to
        // the plain strip, and the un-shifted remainder is validated below like any other.
        t = ScalarSemantics.stripFractionalSeconds(ScalarSemantics.normalizeToUtc(t));
        if (!t.isEmpty() && t.charAt(0) == 'T')
        {
            t = t.substring(1);
        }
        return isValidCore(t) ? t : null;
    }


    /** Whether {@code t} is {@code HH}[{@code :MM}[{@code :SS}]] with every field in range. */
    private static boolean isValidCore(String t)
    {
        int n = t.length();
        if (n != HOUR_PRECISION && n != MINUTE_PRECISION && n != SECOND_PRECISION)
        {
            return false;
        }
        if (!fieldInRange(t, 0, 23))
        {
            return false;
        }
        if (n >= MINUTE_PRECISION && (t.charAt(2) != ':' || !fieldInRange(t, 3, 59)))
        {
            return false;
        }
        return n < SECOND_PRECISION || (t.charAt(5) == ':' && fieldInRange(t, 6, 59));
    }


    /** Whether the two characters at {@code at} are digits forming a value {@code <= max}. */
    private static boolean fieldInRange(String t, int at, int max)
    {
        char hi = t.charAt(at);
        char lo = t.charAt(at + 1);
        if (hi < '0' || hi > '9' || lo < '0' || lo > '9')
        {
            return false;
        }
        return (hi - '0') * 10 + lo - '0' <= max;
    }


    /** Pads a valid core to its {@code HH:mm:ss} hull bound; {@code null} in, {@code null} out. */
    private static @Nullable String bound(@Nullable String core, boolean high)
    {
        if (core == null)
        {
            return null;
        }
        return switch (core.length())
        {
        case HOUR_PRECISION -> core + (high ? ":59:59" : ":00:00");
        case MINUTE_PRECISION -> core + (high ? ":59" : ":00");
        default -> core;
        };
    }

}
