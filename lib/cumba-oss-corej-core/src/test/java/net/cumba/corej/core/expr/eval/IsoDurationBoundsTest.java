package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.exec.ScalarSemantics;
import org.junit.jupiter.api.Test;

/**
 * {@link IsoDurationBounds} — the hull of an ISO 8601 duration in seconds ({@code
 * PLAN-engine-gap-dur-order} §5, runbook W1b DO): weeks and days are exact ({@code W} = 7 d,
 * {@code D} = 86 400 s — no DST, a duration carries no calendar), {@code H} / {@code M} after
 * {@code T} / {@code S} exact, a fractional lowest component as written; <b>months and years are
 * ranges</b> (Q1, owner 2026-10-07: {@code P1M} = [28 d, 31 d], {@code P1Y} = [365 d, 366 d]); a
 * leading {@code -} negates the hull; an invalid duration is unpositionable.
 */
class IsoDurationBoundsTest
{

    private static final double DAY = 86_400d;

    private static IsoDurationBounds.Hull hull(String s)
    {
        IsoDurationBounds.Hull h = IsoDurationBounds.hull(s);
        assertNotNull(h, s);
        return h;
    }


    @Test
    void everyDesignatorPositionsAtItsSecondCount()
    {
        assertEquals(new IsoDurationBounds.Hull(7 * DAY, 7 * DAY), hull("P1W"));
        assertEquals(new IsoDurationBounds.Hull(2 * DAY, 2 * DAY), hull("P2D"));
        assertEquals(new IsoDurationBounds.Hull(5 * 3600d, 5 * 3600d), hull("PT5H"));
        assertEquals(new IsoDurationBounds.Hull(90 * 60d, 90 * 60d), hull("PT90M"));
        assertEquals(new IsoDurationBounds.Hull(45d, 45d), hull("PT45S"));
        assertEquals(new IsoDurationBounds.Hull(DAY + 3600d + 60d + 1d, DAY + 3600d + 60d + 1d),
                hull("P1DT1H1M1S"));
    }


    @Test
    void synonymsAreTheSamePoint()
    {
        assertEquals(hull("P0D"), hull("P0W"), "P0D = P0W");
        assertEquals(hull("P0D"), hull("PT0S"), "P0D = PT0S");
        assertEquals(hull("P1W"), hull("P7D"), "P1W = P7D");
        assertEquals(hull("PT90M"), hull("PT1H30M"), "PT90M = PT1H30M");
        assertEquals(hull("P1D"), hull("PT24H"), "P1D = PT24H");
        assertTrue(hull("P1W").isPoint());
    }


    @Test
    void fractionsCountInTheLowestComponentOnly()
    {
        assertEquals(new IsoDurationBounds.Hull(1.5d, 1.5d), hull("PT1.5S"));
        assertEquals(new IsoDurationBounds.Hull(1.5d, 1.5d), hull("PT1,5S"), "comma decimal");
        assertEquals(new IsoDurationBounds.Hull(DAY / 2, DAY / 2), hull("P0.5D"));
        assertEquals(new IsoDurationBounds.Hull(DAY + 1800d, DAY + 1800d), hull("P1DT0.5H"));
        assertEquals(hull("P0.5D"), hull("PT12H"), "P0.5D = PT12H");
    }


    @Test
    void monthsAndYearsAreRanges()
    {
        assertEquals(new IsoDurationBounds.Hull(28 * DAY, 31 * DAY), hull("P1M"));
        assertEquals(new IsoDurationBounds.Hull(56 * DAY, 62 * DAY), hull("P2M"));
        assertEquals(new IsoDurationBounds.Hull(365 * DAY, 366 * DAY), hull("P1Y"));
        assertEquals(
                new IsoDurationBounds.Hull(365 * DAY + 28 * DAY + DAY, 366 * DAY + 31 * DAY + DAY),
                hull("P1Y1M1D"));
        assertFalse(hull("P1M").isPoint());
        // PT15M is minutes — a point; P15M is months — a range (G-9's ambiguity is the sponsor's)
        assertTrue(hull("PT15M").isPoint());
        assertFalse(hull("P15M").isPoint());
    }


    @Test
    void aLeadingMinusNegatesTheHull()
    {
        assertEquals(new IsoDurationBounds.Hull(-DAY, -DAY), hull("-P1D"));
        assertEquals(new IsoDurationBounds.Hull(-31 * DAY, -28 * DAY), hull("-P1M"),
                "the bounds swap so lower <= upper holds");
    }


    @Test
    void anInvalidDurationIsUnpositionable()
    {
        for (String s : List.of("", " ", "P", "PT", "junk", "1D", "P1S", "PT1D", "P1.5D1H",
                "2020-01-01", "P1DT", "P-1D", "p1d"))
        {
            assertNull(IsoDurationBounds.hull(s), s);
        }
        assertNull(IsoDurationBounds.hull(null));
    }


    @Test
    void positionableIffIsValidDuration()
    {
        // the differential against the validity predicates (plan §5.1): ONE parser decides both
        for (String s : List.of("P1D", "P1W", "PT1H", "P1M", "P1Y", "P1Y2M3DT4H5M6S", "PT0S", "P0D",
                "-P1D", "PT1.5S", "P1,5D", "", "P", "PT", "junk", "P1S", "PT1D", "P1.5D1H", "1D",
                "P1DT", "P-1D", "P1.5Y1D", "PT", "PT5", "P5", "T5H", "P1W1D"))
        {
            boolean valid = !ScalarSemantics.isInvalidDuration(s, true);
            assertEquals(valid, IsoDurationBounds.hull(s) != null,
                    s + " positionable iff is_valid_duration");
        }
    }


    @Test
    void renderingIsTheCanonicalPointForm()
    {
        assertEquals("PT0S", IsoDurationBounds.render(0d));
        assertEquals("P1D", IsoDurationBounds.render(DAY));
        assertEquals("P1DT1H1M1.5S", IsoDurationBounds.render(DAY + 3600d + 60d + 1.5d));
        assertEquals("PT1M30S", IsoDurationBounds.render(90d), "every component below its unit");
        assertEquals("PT59S", IsoDurationBounds.render(59d));
        assertEquals("-P2D", IsoDurationBounds.render(-2 * DAY));
        assertEquals("PT0.25S", IsoDurationBounds.render(0.25d));
        // every rendering positions back to the same point
        for (double seconds : new double[]
        {
                0d, 1d, 59d, 60d, 3600d, DAY, 7 * DAY, DAY + 3600d + 60d + 1.5d, -DAY, 0.001d
        })
        {
            assertEquals(new IsoDurationBounds.Hull(seconds, seconds),
                    hull(IsoDurationBounds.render(seconds)), String.valueOf(seconds));
        }
    }


    @Test
    void theHullBoundsRenderAsPointDurations()
    {
        // earliest_possible / latest_possible over a duration (Q6): the bound as a point duration
        assertEquals("P28D", IsoDurationBounds.lower("P1M"));
        assertEquals("P31D", IsoDurationBounds.upper("P1M"));
        assertEquals("P365D", IsoDurationBounds.lower("P1Y"));
        assertEquals("P366D", IsoDurationBounds.upper("P1Y"));
        assertEquals("P7D", IsoDurationBounds.lower("P1W"));
        assertEquals("P7D", IsoDurationBounds.upper("P1W"));
        assertEquals("-P31D", IsoDurationBounds.lower("-P1M"));
        assertEquals("-P28D", IsoDurationBounds.upper("-P1M"));
        assertNull(IsoDurationBounds.lower("junk"));
        assertNull(IsoDurationBounds.upper(""));
    }


    @Test
    void aRenderedHullReadsBackAsThatHullOnlyAsTheCarrier()
    {
        // the carrier of duration_between's range: lower/upper, both point forms
        assertEquals("P1D/P2D", IsoDurationBounds.renderHull(DAY, 2 * DAY));
        assertEquals("P1D", IsoDurationBounds.renderHull(DAY, DAY), "a point renders as a point");
        assertEquals(new IsoDurationBounds.Hull(DAY, 2 * DAY),
                IsoDurationBounds.hull("P1D/P2D", true));
        assertEquals(new IsoDurationBounds.Hull(-DAY, DAY),
                IsoDurationBounds.hull("-P1D/P1D", true));
        assertEquals(hull("P1D"), IsoDurationBounds.hull("P1D", true), "a point carrier");
        assertFalse(IsoDurationBounds.hull("P1D/P2D", true).isPoint());
        assertNull(IsoDurationBounds.hull("P2D/P1D", true), "a hull is written lower first");
        assertNull(IsoDurationBounds.hull("P1D/junk", true));
        assertNull(IsoDurationBounds.hull("P1D/P2D/P3D", true));
        // R5 B-L1 (dur-order §5: "an invalid duration is unpositionable"): the carrier is the
        // engine's own spelling of duration_between's result, never read from a DATA cell — there
        // it is no ISO duration, as the validity predicates say, so it does not position
        assertTrue(ScalarSemantics.isInvalidDuration("P1D/P2D", true));
        assertNull(IsoDurationBounds.hull("P1D/P2D"), "a data cell spelled lower/upper");
        assertNull(IsoDurationBounds.lower("PT1H/PT2H"));
        assertNull(IsoDurationBounds.upper("PT1H/PT2H"));
    }

}
