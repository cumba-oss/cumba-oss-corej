package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Plan C phase 0, probe C — does the hand-authored hull leg agree with {@code date()} on
 * <b>complete</b> dates?
 *
 * <p>
 * Plan C §3.5 proposes re-authoring a date-ordering rule's ERROR leg as
 * {@code earliest_possible(A) > latest_possible(B)} and claims it is a no-op rewrite of today's
 * {@code date(A) > date(B)}. That claim rests on the padding cancelling: a day-precision hull is
 * {@code [T00:00:00, T23:59:59]}, and mixing a <em>lower</em> against an <em>upper</em> would
 * otherwise make {@code 2026-01-17 >= 2026-01-17} false.
 * </p>
 *
 * <p>
 * The two paths clip differently, and that is what this probe pins: {@link IsoDateComparison#fires}
 * clips both hulls to the <b>pair's common</b> precision, while {@link IsoDateComparison#bound} —
 * which backs the two builtins — clips to <b>each value's own</b> precision. At equal precision the
 * clip cancels the padding and the legs agree; at mixed precision it cannot.
 * </p>
 */
@DisplayName("probe C — earliest_possible/latest_possible vs date() on complete dates")
class HullBoundsCompleteDateProbeTest
{

    /** {@code date(a) OP b} — the shipped comparison. */
    private static boolean viaDate(String a, String b, int direction, boolean orEqual)
    {
        return IsoDateComparison.fires(a, b, direction, orEqual, false);
    }


    /** The hand-authored leg: {@code earliest_possible(a) OP latest_possible(b)}. */
    private static boolean viaHull(String a, String b, int direction, boolean orEqual)
    {
        String lo = IsoDateComparison.bound(a, false);
        String hi = IsoDateComparison.bound(b, true);
        if (lo == null || hi == null)
        {
            return false;
        }
        int c = lo.compareTo(hi);
        return direction > 0 ? orEqual ? c >= 0 : c > 0 : orEqual ? c <= 0 : c < 0;
    }


    @ParameterizedTest(name = "[{0}] {1} vs {2}")
    @CsvSource(
    {
            // ---- equal precision (day) — the padding cancels, both legs must agree
            "same day,           2012-06-15,          2012-06-15",
            "a one day later,    2012-06-16,          2012-06-15",
            "a one day earlier,  2012-06-14,          2012-06-15",
            "month boundary,     2012-07-01,          2012-06-30",
            "year boundary,      2013-01-01,          2012-12-31",
            "leap day,           2012-02-29,          2012-02-28",
            // ---- equal precision (second)
            "same second,        2012-06-15T10:30:45, 2012-06-15T10:30:45",
            "one second later,   2012-06-15T10:30:46, 2012-06-15T10:30:45"
    })
    @DisplayName("equal-precision complete dates — the hull leg reproduces date() exactly")
    void equalPrecisionAgrees(String label, String a, String b)
    {
        assertEquals(viaDate(a, b, 1, false), viaHull(a, b, 1, false), label + " — strict >");
        assertEquals(viaDate(a, b, 1, true), viaHull(a, b, 1, true), label + " — >=");
        assertEquals(viaDate(a, b, -1, false), viaHull(a, b, -1, false), label + " — strict <");
    }


    @Test
    @DisplayName("⛔ MIXED-precision complete dates — the legs DISAGREE; phase 5 must not rewrite these")
    void mixedPrecisionDisagrees()
    {
        String finer = "2012-06-15T10:30:45";
        String coarser = "2012-06-15";

        // date(): both operands are complete, so the fast path compares the CORES at the coarser
        // of the two precisions — the same day, so "after" is false.
        assertEquals(false, viaDate(finer, coarser, 1, false), "date(): same day at day precision");

        // the hull leg: bound() clips to each value's OWN precision, so a second-precision lower
        // is compared against a day-precision upper and the suffix no longer cancels.
        assertEquals(true, viaHull(finer, coarser, 1, false),
                "hull leg: T10:30:45 sorts after the bare day");
    }

}
