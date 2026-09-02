package net.cumba.cdisc.define.conformance.rule;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** {@link RegexFormats}: full-match semantics of every named canned pattern. */
class RegexFormatsTest
{

    private static boolean matches(String aFormat, String aValue)
    {
        return RegexFormats.byName(aFormat).matcher(aValue).matches();
    }


    @Test
    void iso8601DatetimeAcceptsOptionalFractionAndZone()
    {
        assertTrue(matches("iso8601-datetime", "2024-03-01T10:15:30"));
        assertTrue(matches("iso8601-datetime", "2024-03-01T10:15:30.5+02:00"));
        assertTrue(matches("iso8601-datetime", "2024-03-01T10:15:30.123-05:00"));
        assertTrue(matches("iso8601-datetime", "2024-03-01T10:15:30Z"));
        assertFalse(matches("iso8601-datetime", "2024-03-01"));
        assertFalse(matches("iso8601-datetime", "2024-03-01T10:15"));
        assertFalse(matches("iso8601-datetime", "2024-03-01 10:15:30"));
    }


    @Test
    void iso8601DateIsCompleteDateOnly()
    {
        assertTrue(matches("iso8601-date", "2024-03-01"));
        assertFalse(matches("iso8601-date", "2024-3-1"));
        assertFalse(matches("iso8601-date", "2024-03-01T10:15:30"));
    }


    @Test
    void integerAcceptsOptionalSign()
    {
        assertTrue(matches("integer", "42"));
        assertTrue(matches("integer", "-5"));
        assertTrue(matches("integer", "+7"));
        assertFalse(matches("integer", "4.2"));
        assertFalse(matches("integer", "abc"));
    }


    @Test
    void nonNegativeIntegerRejectsMinus()
    {
        assertTrue(matches("non-negative-integer", "0"));
        assertTrue(matches("non-negative-integer", "+3"));
        assertTrue(matches("non-negative-integer", "17"));
        assertFalse(matches("non-negative-integer", "-1"));
        assertFalse(matches("non-negative-integer", "x"));
    }


    @Test
    void positiveIntegerRejectsZero()
    {
        assertTrue(matches("positive-integer", "1"));
        assertTrue(matches("positive-integer", "007"));
        assertTrue(matches("positive-integer", "+42"));
        assertFalse(matches("positive-integer", "0"));
        assertFalse(matches("positive-integer", "-1"));
    }


    @Test
    void meddraVersionEndsInPointZeroOrPointOne()
    {
        assertTrue(matches("meddra-version", "9.0"));
        assertTrue(matches("meddra-version", "14.1"));
        assertFalse(matches("meddra-version", "9.2"));
        assertFalse(matches("meddra-version", "abc"));
        assertFalse(matches("meddra-version", "9"));
    }


    @Test
    void unknownFormatNameThrowsAndNamesTheKnownFormats()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RegexFormats.byName("no-such-format"));
        assertTrue(e.getMessage().contains("no-such-format"), e.getMessage());
        assertTrue(e.getMessage().contains("iso8601-date"), e.getMessage());
    }

}
