package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Phase 5 — timezone-correct ISO comparison. Unit tests for
 * {@link ScalarSemantics#normalizeToUtc(String)} and its wiring into
 * {@link ScalarSemantics#compareIso(String, String)}: a trailing offset is applied
 * instant-preserving and the value rendered in UTC at the input's precision tier; offset-less
 * values are assumed UTC and behave byte-identically to the legacy strip-only code; unparseable
 * offset-bearing values fall back to the legacy strip.
 */
class ScalarSemanticsTimezoneTest
{

    // ---- normalizeToUtc: rendering ------------------------------------------

    @Test
    void normalize_secondPrecision_positiveColonOffset()
    {
        assertEquals("2024-01-15T11:30:00",
                ScalarSemantics.normalizeToUtc("2024-01-15T13:30:00+02:00"));
    }


    @Test
    void normalize_secondPrecision_zSuffix()
    {
        assertEquals("2024-01-15T11:30:00", ScalarSemantics.normalizeToUtc("2024-01-15T11:30:00Z"));
    }


    @Test
    void normalize_fourDigitOffsetWithoutColon()
    {
        assertEquals("2024-01-15T11:30:00",
                ScalarSemantics.normalizeToUtc("2024-01-15T13:30:00+0200"));
    }


    @Test
    void normalize_negativeOffsets_colonAndCompactForms()
    {
        assertEquals("2024-01-15T11:30:00",
                ScalarSemantics.normalizeToUtc("2024-01-15T06:30:00-05:00"));
        assertEquals("2024-01-15T11:30:00",
                ScalarSemantics.normalizeToUtc("2024-01-15T06:30:00-0500"));
    }


    @Test
    void normalize_hourOnlyPrecisionPreserved()
    {
        // Tier 13 — ISO_OFFSET_DATE_TIME needs the :00 minute pad, output stays hour-only.
        assertEquals("2024-03-15T11", ScalarSemantics.normalizeToUtc("2024-03-15T13+02:00"));
    }


    @Test
    void normalize_minutePrecisionPreserved()
    {
        assertEquals("2024-03-15T11:30", ScalarSemantics.normalizeToUtc("2024-03-15T13:30+02:00"));
    }


    @Test
    void normalize_millisecondPrecisionPreserved()
    {
        assertEquals("2024-01-15T11:30:00.250",
                ScalarSemantics.normalizeToUtc("2024-01-15T13:30:00.250+02:00"));
    }


    @Test
    void normalize_offsetShiftsAcrossMidnight()
    {
        assertEquals("2024-03-15T23:30", ScalarSemantics.normalizeToUtc("2024-03-16T01:30+02:00"));
    }

    // ---- normalizeToUtc: offset-less input is byte-identical ----------------


    @Test
    void normalize_offsetlessInputsUnchanged()
    {
        assertEquals("2024-01-15T13:30:00", ScalarSemantics.normalizeToUtc("2024-01-15T13:30:00"));
        assertEquals("2024-01-15", ScalarSemantics.normalizeToUtc("2024-01-15"));
        assertEquals("2024-01", ScalarSemantics.normalizeToUtc("2024-01"));
        assertEquals("2024", ScalarSemantics.normalizeToUtc("2024"));
        assertEquals("13:30:00", ScalarSemantics.normalizeToUtc("13:30:00"));
    }


    @Test
    void normalize_nullAndEmpty()
    {
        assertNull(ScalarSemantics.normalizeToUtc(null));
        assertEquals("", ScalarSemantics.normalizeToUtc(""));
    }

    // ---- normalizeToUtc: legacy strip fallback -------------------------------


    @Test
    void normalize_garbageWithOffsetTail_fallsBackToStrip()
    {
        assertEquals("FOO", ScalarSemantics.normalizeToUtc("FOO+02:00"));
    }


    @Test
    void normalize_dateOnlyWithOffset_fallsBackToStrip()
    {
        // Sub-hour precision with an offset glued on is not a parseable ISO date-time.
        assertEquals("2024-03-15", ScalarSemantics.normalizeToUtc("2024-03-15+05:00"));
    }

    // ---- normalizeToUtc: bare times with offsets (review F7) -----------------


    @Test
    void normalize_bareTimeWithOffset_appliedInstantPreserving()
    {
        // Review F7: a bare time with an offset is normalized to UTC like a full datetime —
        // not stripped — so time_part comparisons stay consistent with the datetime path.
        assertEquals("11:30:00", ScalarSemantics.normalizeToUtc("13:30:00+02:00"));
        assertEquals("18:30:00", ScalarSemantics.normalizeToUtc("13:30:00-05:00"));
    }


    @Test
    void normalize_bareTimeWithCompactOffset()
    {
        assertEquals("11:30:00", ScalarSemantics.normalizeToUtc("13:30:00+0200"));
        assertEquals("18:30:00", ScalarSemantics.normalizeToUtc("13:30:00-0500"));
    }


    @Test
    void normalize_bareTimeLeadingT_preserved()
    {
        assertEquals("T11:30:00", ScalarSemantics.normalizeToUtc("T13:30:00+02:00"));
        assertEquals("T11:30", ScalarSemantics.normalizeToUtc("T13:30+02:00"));
    }


    @Test
    void normalize_bareTimeMinuteAndMillisecondPrecisionPreserved()
    {
        assertEquals("11:30", ScalarSemantics.normalizeToUtc("13:30+02:00"));
        assertEquals("11:30:00.250", ScalarSemantics.normalizeToUtc("13:30:00.250+02:00"));
    }


    @Test
    void normalize_bareTimeZSuffix_identity()
    {
        assertEquals("13:30:00", ScalarSemantics.normalizeToUtc("13:30:00Z"));
    }


    @Test
    void normalize_malformedBareTimeWithOffset_fallsBackToStrip()
    {
        // Time-shaped but out of range — OffsetTime.parse fails, legacy strip applies.
        assertEquals("99:99:99", ScalarSemantics.normalizeToUtc("99:99:99+02:00"));
        // Not a bare time shape at all (single-digit hour) — legacy strip too.
        assertEquals("9:30", ScalarSemantics.normalizeToUtc("9:30+02:00"));
    }


    @Test
    void normalize_calendarInvalidDateTimeWithOffset_fallsBackToStrip()
    {
        // Tier 16 shape, but unparseable — exercises the DateTimeParseException fallback.
        assertEquals("2024-13-45T99:99", ScalarSemantics.normalizeToUtc("2024-13-45T99:99+02:00"));
    }

    // ---- compareIso: equality and ordering across offsets -------------------


    @Test
    void compareIso_instantEqualAcrossOffsets()
    {
        assertEquals(0,
                ScalarSemantics.compareIso("2024-01-15T13:30:00+02:00", "2024-01-15T11:30:00Z"));
    }


    @Test
    void compareIso_sameWallClockDifferentOffsets_notEqual()
    {
        // 13:30+02:00 is 11:30 UTC — earlier than 13:30Z.
        assertTrue(ScalarSemantics.compareIso("2024-01-15T13:30:00+02:00",
                "2024-01-15T13:30:00Z") < 0);
    }


    @Test
    void compareIso_orderingAcrossOffsets()
    {
        // 13:30+02:00 = 11:30Z < 12:00Z; and the reverse direction.
        assertTrue(ScalarSemantics.compareIso("2024-01-15T13:30:00+02:00",
                "2024-01-15T12:00:00Z") < 0);
        assertTrue(ScalarSemantics.compareIso("2024-01-15T12:00:00Z",
                "2024-01-15T13:30:00+02:00") > 0);
    }


    @Test
    void compareIso_midnightShift_dayPrecision()
    {
        // 2024-03-16T01:30+02:00 is 2024-03-15T23:30 UTC.
        assertTrue(ScalarSemantics.compareIso("2024-03-16T01:30+02:00", "2024-03-16") < 0);
        assertEquals(0, ScalarSemantics.compareIso("2024-03-16T01:30+02:00", "2024-03-15"));
    }

    // ---- compareIso: offset-less behaviour pinned (legacy regression) -------


    @Test
    void compareIso_offsetless_dayVsSecond_precisionAutoTruncation()
    {
        assertEquals(0, ScalarSemantics.compareIso("2024-01-15", "2024-01-15T13:30:00"));
    }


    @Test
    void compareIso_offsetless_yearOnly()
    {
        assertTrue(ScalarSemantics.compareIso("2024", "2025-06-01") < 0);
        assertEquals(0, ScalarSemantics.compareIso("2024", "2024-06-01"));
    }


    @Test
    void compareIso_offsetless_plainInequality()
    {
        assertTrue(ScalarSemantics.compareIso("2024-01-16", "2024-01-15") > 0);
        assertTrue(ScalarSemantics.compareIso("2024-01-15T13:30", "2024-01-15T14:00") < 0);
    }


    @Test
    void compareIso_malformed_sameVerdictAsLegacyStrip()
    {
        // Both sides fall back to the plain strip — verdict unchanged vs the old code.
        assertEquals(0, ScalarSemantics.compareIso("FOO+02:00", "FOO"));
        assertEquals(0, ScalarSemantics.compareIso("2024-03-15+05:00", "2024-03-15"));
    }
}
