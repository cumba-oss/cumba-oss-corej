package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.exec.ScalarSemantics;
import org.junit.jupiter.api.Test;

/**
 * {@link IsoDurationComparison} — the {@code duration}-type operator ({@code
 * PLAN-engine-gap-dur-order} §5, runbook W1b DO): {@link IsoDateComparison}'s three branches over
 * {@link IsoDurationBounds} hulls — two points compare as points, otherwise the ∀-over-candidates
 * rule, and an unpositionable operand answers every predicate {@code false} ({@code !=} flipped by
 * {@code negate}, H1b). So {@code PT90M == PT1H30M} holds, {@code P2D > PT5H} holds, and
 * {@code P1M == P30D} is not decided either way.
 */
class IsoDurationComparisonTest
{

    private static boolean eq(String a, String b)
    {
        return IsoDurationComparison.fires(a, b, 0, true, false);
    }


    private static boolean ne(String a, String b)
    {
        return IsoDurationComparison.fires(a, b, 0, true, true);
    }


    private static boolean lt(String a, String b)
    {
        return IsoDurationComparison.fires(a, b, -1, false, false);
    }


    private static boolean le(String a, String b)
    {
        return IsoDurationComparison.fires(a, b, -1, true, false);
    }


    private static boolean gt(String a, String b)
    {
        return IsoDurationComparison.fires(a, b, 1, false, false);
    }


    private static boolean ge(String a, String b)
    {
        return IsoDurationComparison.fires(a, b, 1, true, false);
    }


    @Test
    void twoPointsCompareAsPointsWhateverTheirSpelling()
    {
        assertTrue(eq("PT90M", "PT1H30M"));
        assertFalse(ne("PT90M", "PT1H30M"));
        assertTrue(le("PT90M", "PT1H30M"));
        assertTrue(ge("PT90M", "PT1H30M"));
        assertFalse(lt("PT90M", "PT1H30M"));
        assertFalse(gt("PT90M", "PT1H30M"));
        // the text-order counterexamples of the plan's §3: as text "PT9H" > "PT10H" and
        // "P2D" < "PT5H"
        assertTrue(lt("PT9H", "PT10H"));
        assertFalse(gt("PT9H", "PT10H"));
        assertTrue(gt("P2D", "PT5H"));
        assertTrue(ne("P2D", "PT5H"));
        assertTrue(eq("P0D", "P0W"));
        assertTrue(eq("P1W", "P7D"));
        assertTrue(lt("-P1D", "P0D"));
    }


    @Test
    void aRangeTakesTheForAllRule()
    {
        // P1M = [28 d, 31 d]
        assertFalse(eq("P1M", "P30D"), "not decided");
        assertFalse(ne("P1M", "P30D"), "not decided either way");
        assertTrue(lt("P1M", "P32D"));
        assertTrue(le("P1M", "P31D"));
        assertFalse(lt("P1M", "P31D"));
        assertTrue(gt("P1M", "P27D"));
        assertTrue(ge("P1M", "P28D"));
        assertFalse(ge("P1M", "P29D"));
        assertTrue(ne("P1M", "P32D"), "disjoint hulls differ");
        assertTrue(ne("P1M", "P1Y"));
        assertFalse(eq("P1M", "P1M"), "a range is never a point, even against itself");
        assertFalse(ne("P1M", "P1M"));
        // the symmetric reading from the point side
        assertTrue(gt("P32D", "P1M"));
        assertFalse(gt("P31D", "P1M"));
        assertTrue(lt("P27D", "P1M"));
    }


    @Test
    void anUnpositionableOperandAnswersEveryPredicateFalse()
    {
        for (String junk : List.of("", "junk", "P", "2020-01-01", "P1S", "10"))
        {
            assertFalse(eq("P1D", junk), junk);
            assertFalse(lt("P1D", junk), junk);
            assertFalse(le("P1D", junk), junk);
            assertFalse(gt("P1D", junk), junk);
            assertFalse(ge("P1D", junk), junk);
            assertTrue(ne("P1D", junk), "!= is flipped by negate (H1b): " + junk);
            assertFalse(eq(junk, "P1D"), junk);
            assertTrue(ne(junk, "P1D"), junk);
        }
    }


    @Test
    void everyValidDurationEqualsItselfAsAPointAndNoInvalidOneDoes()
    {
        // the differential (plan §5.1): a value is positionable iff is_valid_duration — and a
        // positionable point is equal to itself under the operator
        for (String s : List.of("P1D", "P1W", "PT1H", "PT90M", "PT1.5S", "P0D", "-P1D",
                "P1DT2H3M4S", "", "P", "PT", "junk", "1D", "P1S", "P1D/P2D", "5"))
        {
            boolean valid = !ScalarSemantics.isInvalidDuration(s, true);
            IsoDurationBounds.Hull hull = IsoDurationBounds.hull(s);
            assertEquals(valid, hull != null, s);
            if (valid)
            {
                assertEquals(hull.isPoint(), eq(s, s), s + " == itself iff it is a point");
                assertTrue(le(s, s) == hull.isPoint() && ge(s, s) == hull.isPoint(), s);
            }
            else
            {
                assertFalse(eq(s, s), s);
                assertTrue(ne(s, s), s);
            }
        }
    }

}
