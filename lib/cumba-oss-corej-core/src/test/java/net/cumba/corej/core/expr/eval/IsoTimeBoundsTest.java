package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@link IsoTimeBounds} — the {@code time}-type hull (phase 3b of
 * {@code PLAN-typed-expression-engine}): accepted shapes and their second-rendered bounds, the D25
 * offset normalisation, and the unpositionable catalogue whose {@code null} bounds carry SPEC
 * §5.2(4).
 */
class IsoTimeBoundsTest
{

    @Test
    void hourPrecisionSpansTheHour()
    {
        assertEquals("10:00:00", IsoTimeBounds.lower("10"));
        assertEquals("10:59:59", IsoTimeBounds.upper("10"));
    }


    @Test
    void minutePrecisionSpansTheMinute()
    {
        assertEquals("10:30:00", IsoTimeBounds.lower("10:30"));
        assertEquals("10:30:59", IsoTimeBounds.upper("10:30"));
    }


    @Test
    void secondPrecisionIsAPoint()
    {
        assertEquals("10:30:45", IsoTimeBounds.lower("10:30:45"));
        assertEquals("10:30:45", IsoTimeBounds.upper("10:30:45"));
    }


    @Test
    void leadingTAndWhitespaceAreAccepted()
    {
        // SPEC §1.1 spells the partial time "T10".
        assertEquals("10:00:00", IsoTimeBounds.lower("T10"));
        assertEquals("08:30:00", IsoTimeBounds.lower(" T08:30 "));
    }


    @Test
    void fractionalTailIsStripped()
    {
        // Mirrors IsoDateBounds.core's fraction strip: the sub-second detail is not a hull axis.
        assertEquals("10:30:45", IsoTimeBounds.lower("10:30:45.500"));
        assertEquals("10:30:45", IsoTimeBounds.upper("10:30:45.500"));
        // The shared helper strips a .digits tail wherever it sits, so an ISO decimal-fraction
        // hour ("10.30") reads as the hour partial "10" — the fraction is dropped, not honoured.
        assertEquals("10:00:00", IsoTimeBounds.lower("10.30"));
        assertEquals("10:59:59", IsoTimeBounds.upper("10.30"));
    }


    @Test
    void offsetIsAppliedInstantPreserving()
    {
        // D25's normalisation feature, on the bare-time arm: 13:30:00+02:00 is 11:30:00 UTC.
        assertEquals("11:30:00", IsoTimeBounds.lower("13:30:00+02:00"));
        assertEquals("11:30:00", IsoTimeBounds.upper("13:30:00+02:00"));
        assertEquals("08:30:00", IsoTimeBounds.lower("08:30:00Z"));
        // Minute precision keeps its precision through the shift: still a one-minute hull.
        assertEquals("11:30:00", IsoTimeBounds.lower("13:30+02:00"));
        assertEquals("11:30:59", IsoTimeBounds.upper("13:30+02:00"));
    }


    @Test
    void hourOnlyOffsetFallsBackToPlainStrip()
    {
        // Documented residue: normalizeToUtc's bare-time shape needs minutes, so an hour-only
        // value's offset is stripped un-applied and the remaining HH hulls normally.
        assertEquals("13:00:00", IsoTimeBounds.lower("13+02:00"));
        assertEquals("13:59:59", IsoTimeBounds.upper("13+02:00"));
    }


    @Test
    void unpositionableShapesHaveNullBounds()
    {
        // Blank / junk / out-of-range / wrong shapes: SPEC §5.2(4)'s unbounded hull, out of band.
        for (String s : new String[]
        {
                null, "", "   ", "UNKNOWN", "24", "25:00", "10:60", "10:30:60", "1", "103", "10:3",
                "10:30:4", "2012-06-15", "2012-06-15T10:30", "10:00/10:30", "-10:30", "10:-3",
                "aa:bb"
        })
        {
            assertNull(IsoTimeBounds.lower(s), String.valueOf(s));
            assertNull(IsoTimeBounds.upper(s), String.valueOf(s));
        }
    }


    @Test
    void boundaryFieldValuesAreAccepted()
    {
        assertEquals("00:00:00", IsoTimeBounds.lower("00:00:00"));
        assertEquals("23:59:59", IsoTimeBounds.upper("23:59:59"));
        assertEquals("23:59:59", IsoTimeBounds.upper("23"));
    }

}
