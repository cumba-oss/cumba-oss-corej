package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code date()} and {@code time()} as CONVERSIONS (phase 3b, SPEC §1.2/§5.1): the erased-mode path
 * is retired for {@code date} — the conversion stays in the operand plan as an interpretive
 * identity (the temporal carrier is the ISO string) — and {@code time()} is new (Review 0's E1).
 * Routing: a conversion on either side selects the family, and the D71b rewrite target
 * {@code date(A) op date(B)} is verdict-identical to the historical one-sided tag spelling
 * {@code date(A) op B} by construction.
 */
class DateTimeConversionTest
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


    @Test
    void bothSidedAndOneSidedDateSpellingsAreVerdictIdentical()
    {
        IDataTable t = MockTable.of().name("AE")
                .col("A", "2012-06-15", "2012-06", "2012-06", "2012-06-15", "UNKNOWN")
                .col("B", "2012-06-16", "2012-06-15", "2012-07-01", "2012-06-15T14:00", "2012")
                .build();
        EvaluationContext c = ctxOf(t);
        for (String op : new String[]
        {
                "==", "!=", "<", "<=", ">", ">="
        })
        {
            BitSet tagged = eval("date(A) " + op + " B", c);
            assertEquals(tagged, eval("date(A) " + op + " date(B)", c),
                    "D71b: adding date() to the untagged side must not move a verdict (" + op
                            + ")");
            assertEquals(tagged, eval("A " + op + " date(B)", c),
                    "the conversion routes the family from either side (" + op + ")");
        }
        // And the family's own semantics hold through the conversion spelling: the D98a fast
        // path (day vs same-day datetime is EQUAL at the coarser precision, row 3), the hull
        // rule (row 1 indeterminate, row 2 disjoint), and §5.2(4) (row 4).
        assertEquals(bits(3), eval("date(A) == date(B)", c));
        // H1b: row 4 ("UNKNOWN" vs "2012") is unpositionable on the left and now fires under !=.
        assertEquals(bits(0, 2, 4), eval("date(A) != date(B)", c));
    }


    @Test
    void dateConversionInValuePositionIsTheInterpretiveIdentity()
    {
        // In value position the conversion is the identity over the string carrier — the same
        // verdict the erased-tag path produced, now by a real (kept) plan node.
        IDataTable t = MockTable.of().name("AE").col("A", "2012-06-15", "", "UNKNOWN").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(eval("non_empty(A)", c), eval("non_empty(date(A))", c));
        assertEquals(eval("is_complete_date(A)", c), eval("is_complete_date(date(A))", c));
    }


    @Test
    void timeFamilyComparesThroughTheTimeOperator()
    {
        IDataTable t = MockTable.of().name("AE").col("A", "08:30", "08:30", "10", "10", "08:30")
                .col("B", "08:30:45", "09:30", "10:30", "11:00", "UNKNOWN").build();
        EvaluationContext c = ctxOf(t);
        // Minute floor: 08:30 vs 08:30:45 equal at the coarser precision (row 0); complete pairs
        // order (row 1); an hour partial is indeterminate inside its hour (row 2) and ordered
        // against a disjoint hour (row 3); junk answers false on all six (row 4).
        assertEquals(bits(0), eval("time(A) == time(B)", c));
        // H1b: row 4's junk right operand now fires under != (five predicates still false).
        assertEquals(bits(1, 3, 4), eval("time(A) != time(B)", c));
        assertEquals(bits(1, 3), eval("time(A) < time(B)", c));
        assertEquals(bits(0, 1, 3), eval("time(A) <= time(B)", c));
        assertEquals(bits(), eval("time(A) > time(B)", c));
        // One-sided spelling routes identically.
        assertEquals(bits(0), eval("time(A) == B", c));
    }


    @Test
    void timeOperatorNumericAndMixedBranchesMirrorTheDateOperator()
    {
        // Two numeric cells compare numerically (SAS seconds-of-day); a numeric/ISO mix is a
        // malformed pair and fires regardless of direction — both exactly as the date operator
        // rules its own numeric and mixed shapes.
        IDataTable t = MockTable.of().name("AE").colLong("N", 34200L, 34200L, 34200L)
                .colLong("M", 34200L, 34260L, null).col("S", "09:30:00", "09:30:00", "09:30:00")
                .build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0), eval("time(N) == time(M)", c));
        assertEquals(bits(1, 2), eval("time(N) != time(M)", c));
        // Mixed numeric LHS vs ISO text RHS: fires for every operator (rows 0-2).
        assertEquals(bits(0, 1, 2), eval("time(N) == time(S)", c));
        assertEquals(bits(0, 1, 2), eval("time(N) != time(S)", c));
        // The hour-hijack guard: an hour-only text comparand parses as a number but must take
        // the hull reading — "10" against "10:30" is indeterminate, not a mixed-shape FIRE.
        IDataTable t2 = MockTable.of().name("AE").col("A", "10:30").col("B", "10").build();
        EvaluationContext c2 = ctxOf(t2);
        assertEquals(bits(), eval("time(A) == time(B)", c2));
        assertEquals(bits(), eval("time(A) != time(B)", c2));
    }


    @Test
    void d55DateOverANumericColumnIsObservedNotErrored()
    {
        // ⚠ D55 is OBSERVE-ONLY in phase 3b (E2: the date_from_sas_* rewrite does not exist yet
        // and real ADaM *DT/*DTM columns are numeric) — this pin is what keeps the observation
        // from eroding silently: it must fire for a numeric column under date()/time(), must
        // stay silent for a character column, and must NOT change the verdict (the numeric pair
        // still compares numerically, today's behaviour).
        IDataTable t = MockTable.of().name("AD").colLong("ADT", 19000L).colLong("AEDT", 19001L)
                .col("ADTC", "2012-06-15").build();
        EvaluationContext c = ctxOf(t);
        java.util.List<String> seen = new java.util.ArrayList<>();
        ColumnTypeGate.setIsoConversionObserver(seen::add);
        try
        {
            assertEquals(bits(0), eval("date(ADT) != date(AEDT)", c));
            assertEquals(2, seen.size(), "both numeric operands observed");
            assertEquals(true, seen.get(0).contains("D55"));
            seen.clear();
            eval("time(ADT) == time(AEDT)", c);
            assertEquals(2, seen.size(), "time() observes the same shape");
            seen.clear();
            eval("date(ADTC) == date(ADTC)", c);
            assertEquals(0, seen.size(), "a character column is not the D55 shape");
        }
        finally
        {
            ColumnTypeGate.setIsoConversionObserver(null);
        }
    }

}
