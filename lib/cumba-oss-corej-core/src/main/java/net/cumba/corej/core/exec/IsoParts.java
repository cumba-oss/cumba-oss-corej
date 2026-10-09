package net.cumba.corej.core.exec;

import net.cumba.corej.core.expr.eval.IsoDateBounds;

/**
 * Owner O24 (2026-10-09) — an ISO 8601 date / datetime cell as {@link CellOrder} compares it: its
 * parts, UTC-normalised, packed into ONE {@code long} whose plain comparison is the part-by-part
 * order with a missing part lowest. Read once per cell by a hand parser (no regex, no allocation on
 * the common shapes — owner priority: a 10 M-row datetime column with UTC offsets).
 *
 * <p>
 * The layout, each part as (value + 1) with {@code 0} for a missing part — month and day are
 * 1-based and stored as themselves: year 15 bits (bits 36–50), month 4 (32–35), day 5 (27–31), hour
 * 5 (22–26), minute 6 (16–21), second 6 (10–15), millisecond 10 (0–9) — 51 bits, so the class
 * {@link CellOrder} puts above them never collides.
 * </p>
 * <p>
 * The shapes read: {@code YYYY}, {@code YYYY-MM}, {@code YYYY-MM-DD}, then {@code Thh},
 * {@code Thh:mm}, {@code Thh:mm:ss}, a fraction ({@code .f} / {@code ,f}, the first three digits
 * are the millisecond), and an offset ({@code Z}, {@code ±hh}, {@code ±hhmm}, {@code ±hh:mm}) —
 * applied only where there is a time to shift; the SDTM masked forms {@code YYYY-MM-} /
 * {@code YYYY-MM--} (day missing), {@code YYYY--DD} / {@code YYYY---DD} (month missing),
 * {@code --MM-DD} / {@code ----MM-DD} (year missing); an interval {@code a/b} by its start. Every
 * part is validated on the calendar (leap-aware). Any other shape that {@link IsoDateBounds} can
 * still bound is placed by its lower bound; anything else is not ISO ({@code -1}) — an invalid
 * date, {@code 2015-UN-UN}, a space instead of {@code T}.
 * </p>
 */
final class IsoParts
{

    /** Not an ISO-placeable cell. */
    static final long NONE = -1L;

    private static final int MISSING = -1;

    private static final int MINUTES_PER_DAY = 24 * 60;

    private static final int MAX_OFFSET_MINUTES = 18 * 60;

    private IsoParts()
    {
    }


    /**
     * The packed parts of {@code text}, or {@link #NONE}.
     *
     * @param text
     *            the cell text (leading / trailing whitespace ignored)
     * @return the packed parts, never negative, or {@link #NONE}
     */
    static long pack(String text)
    {
        String s = text.strip();
        int slash = s.indexOf('/');
        if (slash >= 0)
        {
            // an interval of uncertainty: placed by its start, when the whole value is one
            if (slash == 0 || slash == s.length() - 1 || s.indexOf('/', slash + 1) >= 0
                    || IsoDateBounds.lower(s) == null)
            {
                return NONE;
            }
            long start = single(s.substring(0, slash));
            return start != NONE ? start : lowerBound(s);
        }
        long parts = single(s);
        return parts != NONE ? parts : lowerBound(s);
    }


    /** A shape the hand parser does not read, placed by the hull's lower bound if it has one. */
    private static long lowerBound(String s)
    {
        String low = IsoDateBounds.lower(s);
        return low == null ? NONE : single(low);
    }


    /** One value (no solidus), or {@link #NONE}. */
    private static long single(String s)
    {
        int len = s.length();
        if (len >= 7 && s.charAt(0) == '-' && s.charAt(1) == '-')
        {
            return yearMasked(s, len);
        }
        if (len < 4 || !digits(s, 0, 4))
        {
            return NONE;
        }
        int year = number(s, 0, 4);
        if (len == 4)
        {
            return packed(year, MISSING, MISSING, MISSING, MISSING, MISSING, MISSING);
        }
        if (s.charAt(4) != '-' || len < 7)
        {
            return NONE;
        }
        if (s.charAt(5) == '-')
        {
            return monthMasked(s, len, year);
        }
        if (!digits(s, 5, 7))
        {
            return NONE;
        }
        int month = number(s, 5, 7);
        if (month < 1 || month > 12)
        {
            return NONE;
        }
        if (len == 7)
        {
            return packed(year, month, MISSING, MISSING, MISSING, MISSING, MISSING);
        }
        if (s.charAt(7) != '-')
        {
            return NONE;
        }
        if (len == 8 || (len == 9 && s.charAt(8) == '-'))
        {
            return packed(year, month, MISSING, MISSING, MISSING, MISSING, MISSING); // day masked
        }
        if (len < 10 || !digits(s, 8, 10))
        {
            return NONE;
        }
        int day = number(s, 8, 10);
        if (day < 1 || day > lengthOfMonth(year, month))
        {
            return NONE;
        }
        return time(s, len, year, month, day);
    }


    /** {@code YYYY-MM-DD} read; the time and offset that may follow. */
    private static long time(String s, int len, int year, int month, int day)
    {
        int pos = 10;
        int hour = MISSING;
        int minute = MISSING;
        int second = MISSING;
        int milli = MISSING;
        if (pos < len && s.charAt(pos) == 'T')
        {
            if (pos + 3 > len || !digits(s, pos + 1, pos + 3))
            {
                return NONE;
            }
            hour = number(s, pos + 1, pos + 3);
            pos += 3;
            if (pos < len && s.charAt(pos) == ':')
            {
                if (pos + 3 > len || !digits(s, pos + 1, pos + 3))
                {
                    return NONE;
                }
                minute = number(s, pos + 1, pos + 3);
                pos += 3;
                if (pos < len && s.charAt(pos) == ':')
                {
                    if (pos + 3 > len || !digits(s, pos + 1, pos + 3))
                    {
                        return NONE;
                    }
                    second = number(s, pos + 1, pos + 3);
                    pos += 3;
                    if (pos < len && (s.charAt(pos) == '.' || s.charAt(pos) == ','))
                    {
                        int from = ++pos;
                        while (pos < len && isDigit(s.charAt(pos)))
                        {
                            pos++;
                        }
                        if (pos == from)
                        {
                            return NONE;
                        }
                        milli = 0;
                        for (int k = 0; k < 3; k++)
                        {
                            milli = milli * 10 + (from + k < pos ? s.charAt(from + k) - '0' : 0);
                        }
                    }
                }
            }
            if (hour > 23 || minute > 59 || second > 59)
            {
                return NONE;
            }
        }
        int offset = 0;
        if (pos < len)
        {
            offset = offsetMinutes(s, pos, len);
            if (offset == Integer.MIN_VALUE)
            {
                return NONE;
            }
        }
        if (offset == 0 || hour == MISSING)
        {
            // a partial without a time ignores the offset (O24): there is nothing to shift
            return packed(year, month, day, hour, minute, second, milli);
        }
        int total = hour * 60 + Math.max(minute, 0) - offset;
        int y = year;
        int m = month;
        int d = day;
        if (total < 0)
        {
            total += MINUTES_PER_DAY;
            if (--d < 1)
            {
                if (--m < 1)
                {
                    m = 12;
                    y--;
                }
                d = lengthOfMonth(y, m);
            }
        }
        else if (total >= MINUTES_PER_DAY)
        {
            total -= MINUTES_PER_DAY;
            if (++d > lengthOfMonth(y, m))
            {
                d = 1;
                if (++m > 12)
                {
                    m = 1;
                    y++;
                }
            }
        }
        if (y < 0 || y > 9999)
        {
            return NONE;
        }
        // a whole-hour offset keeps a missing minute missing; a half-hour one makes it present
        int shiftedMinute = minute == MISSING && offset % 60 == 0 ? MISSING : total % 60;
        return packed(y, m, d, total / 60, shiftedMinute, second, milli);
    }


    /** {@code Z}, {@code ±hh}, {@code ±hhmm} or {@code ±hh:mm} to the end, else MIN_VALUE. */
    private static int offsetMinutes(String s, int pos, int len)
    {
        char sign = s.charAt(pos);
        if (sign == 'Z')
        {
            return pos + 1 == len ? 0 : Integer.MIN_VALUE;
        }
        if ((sign != '+' && sign != '-') || pos + 3 > len || !digits(s, pos + 1, pos + 3))
        {
            return Integer.MIN_VALUE;
        }
        int hours = number(s, pos + 1, pos + 3);
        int minutes = 0;
        int rest = pos + 3;
        if (rest < len)
        {
            int from = s.charAt(rest) == ':' ? rest + 1 : rest;
            if (from + 2 != len || !digits(s, from, len))
            {
                return Integer.MIN_VALUE;
            }
            minutes = number(s, from, len);
        }
        int total = hours * 60 + minutes;
        if (minutes > 59 || total > MAX_OFFSET_MINUTES)
        {
            return Integer.MIN_VALUE;
        }
        return sign == '-' ? -total : total;
    }


    /** {@code YYYY--DD} / {@code YYYY---DD}: the month missing. */
    private static long monthMasked(String s, int len, int year)
    {
        int from = len == 8 ? 6 : 7;
        if ((len != 8 && len != 9) || (len == 9 && s.charAt(6) != '-') || !digits(s, from, len))
        {
            return NONE;
        }
        int day = number(s, from, len);
        return day < 1 || day > 31 ? NONE
                : packed(year, MISSING, day, MISSING, MISSING, MISSING, MISSING);
    }


    /** {@code --MM-DD} / {@code ----MM-DD}: the year missing. */
    private static long yearMasked(String s, int len)
    {
        int lead = len - 5;
        if ((len != 7 && len != 9) || (len == 9 && (s.charAt(2) != '-' || s.charAt(3) != '-'))
                || !digits(s, lead, lead + 2) || s.charAt(lead + 2) != '-'
                || !digits(s, lead + 3, len))
        {
            return NONE;
        }
        int month = number(s, lead, lead + 2);
        int day = number(s, lead + 3, len);
        if (month < 1 || month > 12 || day < 1 || day > lengthOfMonth(2000, month))
        {
            return NONE; // 2000 is a leap year: --02-29 is a date of some year
        }
        return packed(MISSING, month, day, MISSING, MISSING, MISSING, MISSING);
    }


    private static long packed(int year, int month, int day, int hour, int minute, int second,
            int milli)
    {
        return ((long) (year + 1) << 36) | ((long) Math.max(month, 0) << 32)
                | ((long) Math.max(day, 0) << 27) | ((long) (hour + 1) << 22)
                | ((long) (minute + 1) << 16) | ((long) (second + 1) << 10) | (milli + 1L);
    }


    /** The days of {@code month} (1-12) in {@code year}, proleptic Gregorian, no allocation. */
    private static int lengthOfMonth(int year, int month)
    {
        return switch (month)
        {
        case 2 -> (year % 4 == 0 && (year % 100 != 0 || year % 400 == 0)) ? 29 : 28;
        case 4, 6, 9, 11 -> 30;
        default -> 31;
        };
    }


    private static boolean digits(String s, int from, int to)
    {
        if (to > s.length())
        {
            return false;
        }
        for (int i = from; i < to; i++)
        {
            if (!isDigit(s.charAt(i)))
            {
                return false;
            }
        }
        return true;
    }


    private static boolean isDigit(char c)
    {
        return c >= '0' && c <= '9';
    }


    private static int number(String s, int from, int to)
    {
        int n = 0;
        for (int i = from; i < to; i++)
        {
            n = n * 10 + (s.charAt(i) - '0');
        }
        return n;
    }
}
