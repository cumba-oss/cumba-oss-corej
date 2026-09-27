package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.GroupKeyPolicy;
import org.junit.jupiter.api.Test;

/**
 * ⭐ {@code PLAN-grouping-key-identity}, the grouping paths (family G): {@code GroupSemantics.group}
 * — the Check-level {@code within=}/{@code keys=} operators and {@code is_last_in_group} — and the
 * coalesced partition form their groups on the datatable index, whose key identity is exact and
 * {@code -0.0}-aware since that plan. So the two zeros are one group (register {@code D84}; owner
 * Q4: <i>"-0 and 0 are to be treated as one key everywhere"</i>), while the noise pair and a
 * {@code LONG} pair beyond 2^53 stay two (register {@code D64h}).
 *
 * <p>
 * Before the plan the raw index split the zeros, so {@code is_inconsistent_across_dataset} over
 * {@code VISITNUM} {@code -0.0}/{@code 0.0} flagged nothing, and {@code partitionCoalesced}
 * answered the zeros twice over — split by its singleton branch (the index), merged by its coalesce
 * branch ({@code KeyPart}).
 * </p>
 *
 * <p>
 * ⚠ The tables holding a {@code -0.0} are built raw ({@link RealTables#buildRaw()}): since
 * {@code NZL O1} (PLAN-negative-zero-on-load) no DOUBLE buffer stores a {@code -0.0}, and the zero
 * handling stays for computed keys ({@code NZL Q2}), whose shape the raw table is.
 * </p>
 */
class GroupingKeyIdentityTest
{

    private static final long TWO_53 = 9_007_199_254_740_992L;

    private static IDataTable vs()
    {
        return RealTables.of("VS").str("USUBJID", "S1", "S1", "S1", "S1", "S1", "S1")
                .dbl("VISITNUM", 4.9999999999994, 4.9999999999994, 5.0, -0.0, 0.0, 0.0)
                .dbl("VSSTRESN", 10.0, 11.0, 99.0, 1.0, 2.0, 3.0)
                .str("VISIT", "WEEK 5", "WEEK 5", "WEEK 5", "SCREENING X", "SCREENING", "SCREENING")
                .buildRaw();
    }


    private static List<List<Integer>> rows(List<int[]> aGroups)
    {
        List<List<Integer>> out = new ArrayList<>();
        for (int[] g : aGroups)
        {
            List<Integer> rows = new ArrayList<>();
            for (int r : g)
            {
                rows.add(r);
            }
            out.add(rows);
        }
        return out;
    }


    private static BitSet bits(int... aSet)
    {
        BitSet b = new BitSet();
        for (int i : aSet)
        {
            b.set(i);
        }
        return b;
    }


    @Test
    void theGroupingPrimitiveMergesTheZerosAndKeepsTheNoisePairApart()
    {
        List<List<Integer>> expected = List.of(List.of(0, 1), List.of(2), List.of(3, 4, 5));
        assertEquals(expected, rows(GroupSemantics.group(vs(), List.of("USUBJID", "VISITNUM"),
                GroupKeyPolicy.DROP_MISSING_KEYS)));
        assertEquals(expected, rows(
                GroupSemantics.group(vs(), List.of("VISITNUM"), GroupKeyPolicy.KEEP_MISSING_KEYS)));
    }


    @Test
    void aLongPairBeyondTwoToThe53StaysTwoGroupsOnEveryPath()
    {
        IDataTable t = RealTables.of("LB").lng("K", TWO_53, TWO_53 + 1, TWO_53).build();
        List<List<Integer>> expected = List.of(List.of(0, 2), List.of(1));
        assertEquals(expected,
                rows(GroupSemantics.group(t, List.of("K"), GroupKeyPolicy.DROP_MISSING_KEYS)));
        // the coalesce branch keys by KeyPart, which since the plan keeps the exact long too
        IDataTable two = RealTables.of("LB").lng("K", TWO_53, TWO_53 + 1, TWO_53)
                .str("P", "x", "x", "x").build();
        assertEquals(expected, rows(GroupSemantics.partitionCoalesced(two,
                List.of(List.of("K", "P")), GroupKeyPolicy.COALESCE_COMPONENT)));
    }


    @Test
    void theSingletonAndTheCoalesceBranchOfAPartitionAgree()
    {
        IDataTable t = vs();
        List<List<Integer>> singleton = rows(GroupSemantics.partitionCoalesced(t,
                List.of(List.of("VISITNUM")), GroupKeyPolicy.COALESCE_COMPONENT));
        List<List<Integer>> coalesce = rows(GroupSemantics.partitionCoalesced(t,
                List.of(List.of("VISITNUM", "VSSTRESN")), GroupKeyPolicy.COALESCE_COMPONENT));
        assertEquals(List.of(List.of(0, 1), List.of(2), List.of(3, 4, 5)), singleton);
        assertEquals(singleton, coalesce);
    }


    @Test
    void anInconsistencyAcrossTheTwoZerosIsFlagged()
    {
        IDataTable t = vs();
        BitSet flagged = GroupSemantics.inconsistentAcrossDatasetViolations(t, "VISIT",
                List.of("VISITNUM"), (int) t.getRowCount(), false,
                GroupKeyPolicy.KEEP_MISSING_KEYS);
        // the zero group {3,4,5} carries SCREENING X once and SCREENING twice: the minority fires.
        // The noise groups are consistent within themselves.
        assertEquals(bits(3), flagged);
    }


    /**
     * {@code has_multiple_values_for(…, within=APERIOD)} — {@code CDISC-AD0325}/{@code AD0326}'s
     * shape, and {@code APERIOD} is ADaM Num — through the compiled Check.
     */
    @Test
    void withinANumericPeriodTheTwoZerosAreOnePeriod()
    {
        IDataTable t = RealTables.of("ADSL").str("USUBJID", "S1", "S1", "S1")
                .dbl("APERIOD", -0.0, 0.0, 4.9999999999994).str("ASPER", "1", "2", "1")
                .str("ASPERC", "PERIOD A", "PERIOD A", "PERIOD A").buildRaw();
        BitSet fired = NativeExprEvaluator.evaluate(CheckExpressionParser.parse(
                "has_multiple_values_for(ASPER, ASPERC, keep_missings=true, within=APERIOD)"),
                EvaluationContext.builder().table(t).build());
        // periods -0.0 and 0.0 are ONE period, in which ASPERC "PERIOD A" has two ASPER values;
        // the third row is another period (the noise value is not zero) and stays silent
        assertEquals(bits(0, 1), fired);
    }
}
