package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * The four SPEC §5.3 interval predicates (D27, phase 3b), end to end through the native evaluator.
 *
 * <p>
 * ⭐ The load-bearing assertion is the D27 identity — <b>{@code not date_overlaps(A, B)} is exactly
 * today's {@code !=}</b> — checked differentially against the compiled {@code date(A) != date(B)}
 * over every shape class the operator distinguishes: complete pairs on the fast path, partial
 * hulls, unpositionable strings, blanks, genuine missings and numeric cells. The implementation
 * shares the operator's code ({@code TemporalPredicates.dateOverlaps} flips
 * {@code Primitives.dateComparison}'s {@code !=} verdict), so this test is the pin that the wiring
 * — plans, argument resolution, absent-column folding — preserves the identity too.
 * </p>
 */
class TemporalPredicatesTest
{

    private static BitSet eval(String expr, EvaluationContext ctx)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }


    private static BitSet bits(int... set)
    {
        BitSet b = new BitSet();
        for (int i : set)
        {
            b.set(i);
        }
        return b;
    }


    private static EvaluationContext ctxOf(IDataTable t)
    {
        return EvaluationContext.builder().table(t).build();
    }


    /**
     * One row per shape class of the date operator: 0 equal complete pair, 1 different complete
     * pair, 2 day vs datetime of the same day (fast path at the coarser precision), 3 partial
     * containing a complete (indeterminate), 4 partial disjoint from a complete, 5 junk right, 6
     * masked right, 7 blank right, 8 blank left, 9 junk left.
     */
    private static EvaluationContext dateShapes()
    {
        IDataTable t = MockTable.of().name("AE")
                .col("A", "2012-06-15", "2012-06-15", "2012-06-15", "2012-06", "2012-06",
                        "2012-06-15", "2012-06-15", "2012-06-15", "", "UNKNOWN")
                .col("B", "2012-06-15", "2012-06-16", "2012-06-15T14:00", "2012-06-15",
                        "2012-07-01", "UNKNOWN", "----06-15", "", "2012-06-15", "2012-06-15")
                .build();
        return ctxOf(t);
    }


    @Test
    void notDateOverlapsIsExactlyTheDateNotEqualOperator()
    {
        EvaluationContext c = dateShapes();
        BitSet notEqual = eval("date(A) != date(B)", c);
        assertEquals(notEqual, eval("not date_overlaps(A, B)", c),
                "D27: not date_overlaps(A, B) must be byte-identical to today's !=");
        // And the underlying verdicts are the operator's, stated so a drift is readable here:
        // != fires on the definitely-different pairs — the disjoint complete pair (1) and the
        // disjoint partial (4) — plus the blank-left short-circuit (8) and, since H1b, EVERY
        // unpositionable operand in either position: junk right (5), masked right (6), blank
        // right (7) and junk left (9). ⭐ The identity itself is what matters and it is asserted
        // above: it survived the ruling because date_overlaps CALLS the operator.
        assertEquals(bits(1, 4, 5, 6, 7, 8, 9), notEqual);
    }


    @Test
    void notTimeOverlapsIsExactlyTheTimeNotEqualOperator()
    {
        IDataTable t = MockTable.of().name("AE").col("A", "08:30", "08:30", "10", "10", "08:30", "")
                .col("B", "08:30:45", "09:30", "10:30", "11:00", "UNKNOWN", "08:30").build();
        EvaluationContext c = ctxOf(t);
        BitSet notEqual = eval("time(A) != time(B)", c);
        assertEquals(notEqual, eval("not time_overlaps(A, B)", c),
                "the D27 identity holds for the time twin");
        // H1b: row 4's junk right operand now fires too, beside the blank-left row 5.
        assertEquals(bits(1, 3, 4, 5), notEqual);
    }


    @Test
    void dateOverlapsAnswersTheHullIntersection()
    {
        EvaluationContext c = dateShapes();
        // ⭐ H1b REVERSED the unpositionable rows: an unpositionable operand now overlaps
        // NOTHING. It used to overlap everything (unbounded hull ⇒ != false ⇒ overlaps true);
        // since the ruling the unbounded hull answers `negate`, so != fires and overlaps is
        // false — for junk right (5), masked right (6), blank right (7) and junk left (9), which
        // now agree with the blank-left row (8) that was already excluded by the operator's
        // missing short-circuit. Only genuinely overlapping hulls remain.
        assertEquals(bits(0, 2, 3), eval("date_overlaps(A, B)", c));
    }


    @Test
    void dateContainsIsHullInclusion()
    {
        IDataTable t = MockTable.of().name("AE")
                .col("O", "2012-06", "2012-06-15", "2012-06-15", "2012-06", "2012-06-15", "UNKNOWN",
                        "2012-06-15", "2012-06-15", "2012-06-14")
                .col("I", "2012-06-15", "2012-06", "2012-06-15T14:00", "2012-06", "2012-06-15",
                        "2012-06-15", "UNKNOWN", "", "2012-06-15T01:00+02:00")
                .build();
        EvaluationContext c = ctxOf(t);
        // 0: a month contains its day. 1: a day does NOT contain its month. 2: a day contains an
        // instant of that day. 3/4: self-containment holds for partial and complete alike.
        // 5-7: junk or blank on either side answers false (containment is an assertion).
        // 8: D25 — 2012-06-15T01:00+02:00 normalises to 2012-06-14T23:00Z, so the 14th contains
        // it even though its text says the 15th.
        assertEquals(bits(0, 2, 3, 4, 8), eval("date_contains(O, I)", c));
    }


    @Test
    void timeContainsIsHullInclusion()
    {
        IDataTable t = MockTable.of().name("AE").col("O", "10", "10:30", "10", "10", "25:00")
                .col("I", "10:30", "10", "10:30:45", "11:00", "10:30").build();
        EvaluationContext c = ctxOf(t);
        // The hour contains its minute (0) and its second (2), not the reverse (1), not a
        // different hour (3); junk contains nothing (4).
        assertEquals(bits(0, 2), eval("time_contains(O, I)", c));
    }


    @Test
    void predicatesOverNumericCellsFollowTheOperator()
    {
        // Numeric pairs ride the operator's numeric branch through the identity: equal SAS day
        // numbers overlap, different ones do not. A genuine missing numeric (null) takes the
        // missing short-circuit — does not overlap, so the negated form fires (row 2).
        IDataTable t = MockTable.of().name("AE").colLong("A", 19000L, 19000L, null)
                .colLong("B", 19000L, 19001L, 19000L).build();
        EvaluationContext c = ctxOf(t);
        BitSet notEqual = eval("date(A) != date(B)", c);
        assertEquals(notEqual, eval("not date_overlaps(A, B)", c));
        assertEquals(bits(1, 2), notEqual);
        assertEquals(bits(0), eval("date_overlaps(A, B)", c));
    }

}
