package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * T3 — composite / multi-column cross-dataset membership. Exercises the locked native surface
 * {@code tuple(c1, c2, …) [not] in distinct([c1, c2, …], domain="D")}: the per-row composite key
 * built by the {@code tuple} value function is tested against the reference dataset's distinct
 * row-tuple set built by the list-target {@code distinct} operation. The cases prove (a)
 * whole-tuple (not element-wise) membership, (b) a per-row (not broadcast) verdict, and (c) the
 * positive / negative and 2- / 3-column shapes.
 */
class NativeTupleMembershipTest
{

    private static BitSet eval(String expr, EvaluationContext ctx)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }


    /** A resolver that serves {@code table} for {@code domain} and nothing else. */
    private static DatasetResolver resolverFor(String domain, IDataTable table)
    {
        return name -> domain.equals(name) ? table : null;
    }


    @Test
    void notInFiresPerRowOnAbsentTuples()
    {
        // Reference TV rows: (WEEK 1, 1) and (WEEK 2, 2).
        IDataTable tv = MockTable.of().name("TV").col("VISIT", "WEEK 1", "WEEK 2")
                .col("VISITNUM", "1", "2").build();
        // Primary rows chosen so each component exists in TV but only two pairs match a TV row.
        IDataTable vs = MockTable.of().name("VS")
                .col("VISIT", "WEEK 1", "WEEK 2", "WEEK 1", "WEEK 9")
                .col("VISITNUM", "1", "2", "2", "9").build();
        EvaluationContext ctx = EvaluationContext.builder().table(vs).domainPrefix("VS")
                .datasetResolver(resolverFor("TV", tv)).build();

        BitSet bits = eval(
                "tuple(VISIT, VISITNUM) not in distinct([VISIT, VISITNUM], domain=\"TV\")", ctx);

        // Rows 0 (WEEK 1,1) and 1 (WEEK 2,2) match a TV row → do not fire.
        assertFalse(bits.get(0), "(WEEK 1,1) is a TV row");
        assertFalse(bits.get(1), "(WEEK 2,2) is a TV row");
        // Row 2 (WEEK 1,2): both components appear in TV but NOT as a pair → fires (whole-tuple,
        // not
        // element-wise membership).
        assertTrue(bits.get(2), "(WEEK 1,2) is not a TV row even though both parts occur in TV");
        // Row 3 (WEEK 9,9): absent → fires.
        assertTrue(bits.get(3), "(WEEK 9,9) is not a TV row");
        assertEquals(2, bits.cardinality(), "exactly rows 2 and 3 fire");
    }


    @Test
    void inIsTheExactComplementOfNotIn()
    {
        IDataTable tv = MockTable.of().name("TV").col("VISIT", "WEEK 1", "WEEK 2")
                .col("VISITNUM", "1", "2").build();
        IDataTable vs = MockTable.of().name("VS")
                .col("VISIT", "WEEK 1", "WEEK 2", "WEEK 1", "WEEK 9")
                .col("VISITNUM", "1", "2", "2", "9").build();
        EvaluationContext ctx = EvaluationContext.builder().table(vs).domainPrefix("VS")
                .datasetResolver(resolverFor("TV", tv)).build();

        BitSet in = eval("tuple(VISIT, VISITNUM) in distinct([VISIT, VISITNUM], domain=\"TV\")",
                ctx);
        // Only the two matching rows are contained.
        assertTrue(in.get(0) && in.get(1));
        assertFalse(in.get(2) || in.get(3));
        assertEquals(2, in.cardinality());
    }


    @Test
    void threeColumnTupleAcrossSubjects()
    {
        // SV rows keyed by (USUBJID, VISIT, VISITNUM).
        IDataTable sv = MockTable.of().name("SV").col("USUBJID", "S1", "S1", "S2")
                .col("VISIT", "WEEK 1", "WEEK 2", "WEEK 1").col("VISITNUM", "1", "2", "1").build();
        // Primary EC-like rows: S1/WEEK 1/1 present, S2/WEEK 2/2 absent (S2 only has WEEK 1),
        // S1/WEEK 2/2 present.
        IDataTable ec = MockTable.of().name("EC").col("USUBJID", "S1", "S2", "S1")
                .col("VISIT", "WEEK 1", "WEEK 2", "WEEK 2").col("VISITNUM", "1", "2", "2").build();
        EvaluationContext ctx = EvaluationContext.builder().table(ec).domainPrefix("EC")
                .datasetResolver(resolverFor("SV", sv)).build();

        BitSet bits = eval("tuple(USUBJID, VISIT, VISITNUM) not in "
                + "distinct([USUBJID, VISIT, VISITNUM], domain=\"SV\")", ctx);

        assertFalse(bits.get(0), "(S1, WEEK 1, 1) is an SV row");
        assertTrue(bits.get(1), "(S2, WEEK 2, 2) is not an SV row (S2 only has WEEK 1)");
        assertFalse(bits.get(2), "(S1, WEEK 2, 2) is an SV row");
        assertEquals(1, bits.cardinality());
    }


    @Test
    void absentReferenceDatasetMakesEverythingNotContained()
    {
        // No reference dataset resolves ⇒ an empty tuple set ⇒ `not in` fires every row (mirroring
        // the single-column Primitives.membership contract for an empty set).
        IDataTable vs = MockTable.of().name("VS").col("VISIT", "WEEK 1", "WEEK 2")
                .col("VISITNUM", "1", "2").build();
        EvaluationContext ctx = EvaluationContext.builder().table(vs).domainPrefix("VS")
                .datasetResolver(_ -> null).build();

        BitSet bits = eval(
                "tuple(VISIT, VISITNUM) not in distinct([VISIT, VISITNUM], domain=\"TV\")", ctx);
        assertTrue(bits.get(0) && bits.get(1), "empty reference set ⇒ every row is not contained");
    }
}
