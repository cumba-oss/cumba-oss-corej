package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * {@link IsoTimeComparison} — the {@code time}-type operator (SPEC §5.2 mirrored onto time-of-day,
 * phase 3b): the complete-vs-complete point compare at the coarser precision, the ∀-over-clipped-
 * hulls rule for hour partials, and §5.2(4)'s all-six-false verdict for unpositionable operands.
 */
class IsoTimeComparisonTest
{

    private static boolean eq(String a, String b)
    {
        return IsoTimeComparison.fires(a, b, 0, true, false);
    }


    private static boolean ne(String a, String b)
    {
        return IsoTimeComparison.fires(a, b, 0, true, true);
    }


    private static boolean lt(String a, String b)
    {
        return IsoTimeComparison.fires(a, b, -1, false, false);
    }


    private static boolean le(String a, String b)
    {
        return IsoTimeComparison.fires(a, b, -1, true, false);
    }


    private static boolean gt(String a, String b)
    {
        return IsoTimeComparison.fires(a, b, 1, false, false);
    }


    private static boolean ge(String a, String b)
    {
        return IsoTimeComparison.fires(a, b, 1, true, false);
    }


    @Test
    void completePointsCompareAtTheCoarserPrecision()
    {
        // The SPEC-CHOICE minute floor: 10:30 vs 10:30:45 is the time analogue of
        // 2012-06-15 vs 2012-06-15T14:00 — equal at the coarser precision, not a finding.
        assertEquals(true, eq("10:30", "10:30:45"));
        assertEquals(false, ne("10:30", "10:30:45"));
        assertEquals(true, ge("10:30", "10:30:45"));
        assertEquals(true, le("10:30", "10:30:45"));
        assertEquals(false, lt("10:30", "10:30:45"));
        assertEquals(true, eq("08:30:00", "08:30:00"));
        assertEquals(true, ne("08:30:00", "09:30:00"));
        assertEquals(true, lt("08:30:00", "09:30:00"));
        assertEquals(false, gt("08:30:00", "09:30:00"));
    }


    @Test
    void equalCompleteTimesSatisfyOrEqualOperators()
    {
        // The regression the date fast path exists for, mirrored: an equal pair must satisfy
        // >= and <=.
        assertEquals(true, ge("10:30", "10:30"));
        assertEquals(true, le("10:30", "10:30"));
        assertEquals(false, gt("10:30", "10:30"));
        assertEquals(false, lt("10:30", "10:30"));
    }


    @Test
    void hourPartialTakesTheHullRule()
    {
        // T10 spans [10:00:00, 10:59:59] (D22): nothing inside the hour is definite...
        assertEquals(false, eq("10", "10:30"));
        assertEquals(false, ne("10", "10:30"));
        assertEquals(false, lt("10", "10:30"));
        assertEquals(false, gt("10", "10:30"));
        // ...but a disjoint hour is definitely different and definitely ordered.
        assertEquals(true, ne("10", "11:30"));
        assertEquals(true, lt("10", "11:30"));
        assertEquals(true, gt("12", "11:30"));
        // Two equal hour partials are not definitely equal (both hulls span the hour).
        assertEquals(false, eq("10", "10"));
        assertEquals(false, ne("10", "10"));
        // ∀ boundary: every candidate of "10" is <= every candidate of "11" — adjacent hours.
        assertEquals(true, le("10", "11"));
        assertEquals(false, ge("10", "11"));
    }


    @Test
    void offsetsNormaliseBeforeComparing()
    {
        // D25 on the time family: 13:30+02:00 is 11:30Z.
        assertEquals(true, eq("13:30+02:00", "11:30"));
        assertEquals(true, eq("13:30:00+02:00", "T11:30:00Z"));
        assertEquals(false, ne("13:30+02:00", "11:30"));
    }


    @Test
    void unpositionableOperandsAnswerFalseOnFivePredicatesAndFireOnNotEqual()
    {
        // SPEC §5.2(4): blank, junk, out-of-range and date-shaped operands have an unbounded
        // hull, so every PREDICATE answers false. ⭐ H1b (owner, 2026-09-17): `negate` then flips
        // it, so time_not_equal_to FIRES and complementarity is broken for the order family only.
        // The time class mirrors the date class here deliberately — see IsoTimeComparison's
        // branch (4) comment for why the two must not be allowed to diverge.
        for (String junk : new String[]
        {
                "", "  ", "UNKNOWN", "25:00", "10:60", "2012-06-15T10:30"
        })
        {
            assertEquals(false, eq("10:30", junk), junk);
            assertEquals(true, ne("10:30", junk), junk);
            assertEquals(false, lt("10:30", junk), junk);
            assertEquals(false, le("10:30", junk), junk);
            assertEquals(false, gt("10:30", junk), junk);
            assertEquals(false, ge("10:30", junk), junk);
            assertEquals(false, eq(junk, "10:30"), junk);
            assertEquals(true, ne(junk, "10:30"), junk);
        }
    }

}
