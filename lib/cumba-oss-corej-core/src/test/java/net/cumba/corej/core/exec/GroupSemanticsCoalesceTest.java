package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * EC-24 coalesced {@code within} grouping key (FDA-SE2279). Exercises
 * {@link GroupSemantics#partitionCoalesced} directly (backward-compat delegation, first-populated
 * coalesce, {@code ""}-is-unpopulated, all-unpopulated-drops, absent-column) and the
 * {@code has_multiple_values_for} operator end-to-end on both engines (legacy operator and native
 * expression evaluator) to prove per-pool grouping with pooled (blank-USUBJID) rows.
 */
class GroupSemanticsCoalesceTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static Set<Set<Integer>> groupSet(List<int[]> groups)
    {
        Set<Set<Integer>> out = new HashSet<>();
        for (int[] g : groups)
        {
            Set<Integer> s = new HashSet<>();
            for (int r : g)
            {
                s.add(r);
            }
            out.add(s);
        }
        return out;
    }


    private static List<List<String>> comps(Object... entries)
    {
        List<List<String>> out = new ArrayList<>();
        for (Object e : entries)
        {
            if (e instanceof String s)
            {
                out.add(List.of(s));
            }
            else
            {
                @SuppressWarnings("unchecked")
                List<String> group = (List<String>) e;
                out.add(group);
            }
        }
        return out;
    }

    // ---- partitionCoalesced: backward compatibility ----


    @Test
    void singletonComponentsDelegateToIndexPartition()
    {
        // Two singleton components == the pre-EC-24 flat two-column composite key.
        IDataTable t = MockTable.of().col("A", "x", "x", "y", "y").col("B", "1", "2", "1", "1")
                .build();
        List<int[]> viaCoalesce = GroupSemantics.partitionCoalesced(t, comps("A", "B"));
        List<int[]> viaPartition = GroupSemantics.partition(t, List.of("A", "B"));
        assertEquals(groupSet(viaPartition), groupSet(viaCoalesce),
                "singleton components must reproduce the index-based partition exactly");
    }


    /**
     * ⭐⭐ <b>INVERTED by {@code W32-E3} (owner, 2026-08-12), not deleted.</b>
     *
     * <p>
     * This test was named {@code singletonEmptyStringIsARealKey} and asserted the opposite:
     * {@code Set.of(Set.of(0, 1), Set.of(2))} — <i>"a singleton component keeps {@code ""} as a
     * real key (pre-EC-24 contract): rows 0,1 share {@code ""}"</i>. That was a true statement
     * about the shipped engine and is now false <b>by ruling</b>: {@code ""} is handled exactly as
     * a {@code MissingValue}, so under {@code DROP_MISSING_KEYS} the blank-keyed rows are discarded
     * rather than forming a {@code ""} group.
     * </p>
     *
     * <p>
     * ⚑ It is inverted rather than removed because the assertion is the only thing standing between
     * this branch and a silent regression: with it deleted, a future change restoring the old fold
     * would go unnoticed. Same fixture, same call, opposite expectation.
     * </p>
     */
    @Test
    void singletonEmptyStringDropsTheRow()
    {
        // W32-E3: "" is blank under MISSING_OR_EMPTY, so rows 0 and 1 drop entirely and only the
        // populated "y" group survives. Under the pre-ruling contract this was {{0,1},{2}}.
        IDataTable t = MockTable.of().col("A", "", "", "y").build();
        List<int[]> groups = GroupSemantics.partitionCoalesced(t, comps("A"));
        assertEquals(Set.of(Set.of(2)), groupSet(groups),
                "W32-E3: a blank singleton key drops its rows, exactly as a genuine missing does");
    }


    @Test
    void singletonGenuineMissingDropsRow()
    {
        // A genuine missing (null) singleton key drops the row (isBlockKeyMissing contract).
        IDataTable t = MockTable.of().col("A", "x", null, "x").build();
        List<int[]> groups = GroupSemantics.partitionCoalesced(t, comps("A"));
        assertEquals(Set.of(Set.of(0, 2)), groupSet(groups));
    }

    // ---- partitionCoalesced: coalesce group ----


    @Test
    void coalesceTakesFirstPopulatedAndSeparatesPools()
    {
        // USUBJID blank -> fall through to POOLID; per-pool groups, no "" collapse.
        IDataTable t = MockTable.of().col("USUBJID", "", "", "", "001", "001")
                .col("POOLID", "P1", "P1", "P2", "", "").build();
        List<int[]> groups = GroupSemantics.partitionCoalesced(t,
                comps(List.of("USUBJID", "POOLID")));
        // rows 0,1 -> P1 ; row 2 -> P2 ; rows 3,4 -> 001
        assertEquals(Set.of(Set.of(0, 1), Set.of(2), Set.of(3, 4)), groupSet(groups));
    }


    @Test
    void coalesceEmptyStringCountsAsUnpopulated()
    {
        // USUBJID="" is UNPOPULATED in the coalesce (approved deviation) -> use POOLID.
        IDataTable t = MockTable.of().col("USUBJID", "", "  ", "A").col("POOLID", "P1", "P1", "P9")
                .build();
        List<int[]> groups = GroupSemantics.partitionCoalesced(t,
                comps(List.of("USUBJID", "POOLID")));
        // rows 0 ("" -> P1) and 1 (" " whitespace -> P1) group together; row 2 uses USUBJID=A
        assertEquals(Set.of(Set.of(0, 1), Set.of(2)), groupSet(groups));
    }


    @Test
    void coalesceAllUnpopulatedDropsRow()
    {
        // Both USUBJID and POOLID unpopulated -> component missing -> row drops.
        IDataTable t = MockTable.of().col("USUBJID", "", "A").col("POOLID", "", "").build();
        List<int[]> groups = GroupSemantics.partitionCoalesced(t,
                comps(List.of("USUBJID", "POOLID")));
        // row 0 drops (both blank); row 1 keyed by USUBJID=A
        assertEquals(Set.of(Set.of(1)), groupSet(groups));
    }


    @Test
    void mixedCoalesceAndSingletonComponent()
    {
        // component 0 = coalesce(USUBJID, POOLID); component 1 = PARAMCD (singleton, "" real key).
        IDataTable t = MockTable.of().col("USUBJID", "", "", "").col("POOLID", "P1", "P1", "P2")
                .col("PARAMCD", "X", "X", "X").build();
        List<int[]> groups = GroupSemantics.partitionCoalesced(t,
                comps(List.of("USUBJID", "POOLID"), "PARAMCD"));
        // (P1,X) rows 0,1 ; (P2,X) row 2
        assertEquals(Set.of(Set.of(0, 1), Set.of(2)), groupSet(groups));
    }


    @Test
    void absentCoalesceColumnIsSkippedNotFatal()
    {
        // EC-24 addendum (SE2279): the "NOPE" column is absent -> the component groups by its
        // present column (USUBJID) only; the coalesce populated-first semantics stay, so the
        // blank-USUBJID row still drops instead of forming a "" group.
        IDataTable t = MockTable.of().col("USUBJID", "", "001", "001", "002")
                .col("POOLID", "P1", "", "", "").build();
        List<int[]> groups = GroupSemantics.partitionCoalesced(t,
                comps(List.of("USUBJID", "NOPE")));
        // row 0 (blank USUBJID, no fallback column) drops; 001 pair groups; 002 alone.
        assertEquals(Set.of(Set.of(1, 2), Set.of(3)), groupSet(groups));
    }


    @Test
    void absentPoolidRestoresPerSubjectGrouping()
    {
        // The SE2279 production shape: a conventional study never collects POOLID. The
        // [[USUBJID, POOLID]] component must degrade to the plain per-USUBJID check instead of
        // silencing the whole rule.
        IDataTable t = MockTable.of().col("USUBJID", "001", "001", "002").build();
        List<int[]> groups = GroupSemantics.partitionCoalesced(t,
                comps(List.of("USUBJID", "POOLID")));
        assertEquals(Set.of(Set.of(0, 1), Set.of(2)), groupSet(groups));
    }


    @Test
    void absentSingletonComponentIsPrunedWhenACoalesceComponentIsDeclared()
    {
        // Mirrored cross-lane contract (Python fork prunes ALL components in the coalesce path):
        // once the declared shape carries a coalesce component, an absent SINGLETON component is
        // likewise dropped and the partition regroups on the survivors. Contrast: an all-singleton
        // shape keeps the strict flat contract (any absent column -> null, see partition()).
        IDataTable t = MockTable.of().col("USUBJID", "001", "001", "002").col("POOLID", "", "", "")
                .build();
        List<int[]> groups = GroupSemantics.partitionCoalesced(t,
                comps("VISITNUM", List.of("USUBJID", "POOLID")));
        // VISITNUM (absent singleton) is pruned; coalesce(USUBJID, POOLID) groups per subject.
        assertEquals(Set.of(Set.of(0, 1), Set.of(2)), groupSet(groups));
    }

    // ---- EC-44 (Fix #134): partition() and absent within columns ----


    @Test
    void partitionIgnoresAnAbsentWithinColumnAndGroupsOnTheSurvivor()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1", "S2").build();

        List<int[]> groups = GroupSemantics.partition(t, List.of("USUBJID", "EPOCH"));

        assertEquals(Set.of(Set.of(0, 1), Set.of(2)), groupSet(groups));
    }


    @Test
    void partitionWithEveryWithinColumnAbsentIsOneWholeTableGroup()
    {
        // The shape of CDISC-AD0325/AD0326 (within: APERIOD, Perm/Cond in every ADaM IG) on a
        // dataset with no period structure: one period ⇒ one group, not "no groups".
        IDataTable t = MockTable.of().col("ASPER", "1", "2", "3").build();

        assertEquals(Set.of(Set.of(0, 1, 2)),
                groupSet(GroupSemantics.partition(t, List.of("APERIOD"))));
    }


    @Test
    void partitionWithAnEmptyWithinListIsOneWholeTableGroup()
    {
        // The route inconsistentAcrossDatasetViolations takes once every comparator is absent —
        // it no longer short-circuits, it asks for a partition on nothing.
        IDataTable t = MockTable.of().col("LBSTRESU", "g/L", "mg/dL").build();

        assertEquals(Set.of(Set.of(0, 1)), groupSet(GroupSemantics.partition(t, List.of())));
    }


    /**
     * EC-53's <b>Q3</b>, answered by measurement rather than deferred.
     * {@code is_inconsistent_across_dataset} carries the same {@code nameIdx < 0} early-out as
     * {@code uniqueSetViolations} — but unlike it, it is a <b>fast path, not a carve-out</b>: it
     * returns what the all-missing contract already prescribes.
     *
     * <p>
     * ⚠ Q3's premise was wrong, though its answer is not. The plan asked the question because it
     * believed this operator shared {@code is_(not_)unique_set}'s membership of the fork's
     * {@code ABSENT_TARGET_AWARE_OPERATORS}; it does not — that set holds
     * {@code inconsistent_enumerated_columns}, a different operator
     * ({@code check_operators/dataframe_operators.py:76-88}). The verdict below rests on the Java
     * code alone and is unaffected.
     *
     * <p>
     * The reason is D.2. With {@code includeEmpty == false} a blank target is excluded from both
     * the count and the flag pass, so an all-blank target leaves {@code counts} empty; with
     * {@code includeEmpty == true} (Fix #121) every blank folds to the single canonical value
     * {@code ""}, so {@code counts.size() == 1}. Both land on {@code counts.size() <= 1} and flag
     * nothing — in every group, whatever the data. So EC-53 changed this operator not at all, and
     * that is a measured result, not an assumption.
     * </p>
     */
    @Test
    void absentTargetMatchesAllBlankTargetInBothEmptinessModes()
    {
        // A group (USUBJID S1) that WOULD be inconsistent if the target carried values — the
        // absence of a flag is attributable to the target, not to agreeable data.
        IDataTable absent = MockTable.of().col("USUBJID", "S1", "S1", "S2").build();
        IDataTable allBlank = MockTable.of().col("USUBJID", "S1", "S1", "S2")
                .col("LBSTRESU", "", "", "").build();
        IDataTable populated = MockTable.of().col("USUBJID", "S1", "S1", "S2")
                .col("LBSTRESU", "g/L", "mg/dL", "g/L").build();

        for (boolean includeEmpty : new boolean[]
        {
                false, true
        })
        {
            assertEquals(
                    GroupSemantics.inconsistentAcrossDatasetViolations(allBlank, "LBSTRESU",
                            List.of("USUBJID"), 3, includeEmpty),
                    GroupSemantics.inconsistentAcrossDatasetViolations(absent, "LBSTRESU",
                            List.of("USUBJID"), 3, includeEmpty),
                    "absent target must equal present-but-all-blank, includeEmpty=" + includeEmpty);
            assertEquals(new BitSet(),
                    GroupSemantics.inconsistentAcrossDatasetViolations(absent, "LBSTRESU",
                            List.of("USUBJID"), 3, includeEmpty),
                    "…and the shared answer is 'nothing flagged': at most one distinct value "
                            + "survives the blank fold, includeEmpty=" + includeEmpty);
        }
        // The control that makes the two assertions above non-vacuous: the same shape with a
        // POPULATED target does flag, so the group really was capable of being inconsistent.
        BitSet flagged = GroupSemantics.inconsistentAcrossDatasetViolations(populated, "LBSTRESU",
                List.of("USUBJID"), 3);
        assertEquals(2, flagged.cardinality(),
                "S1 holds two distinct units with no majority ⇒ both its rows are flagged");
        assertTrue(flagged.get(0));
        assertTrue(flagged.get(1));
        assertFalse(flagged.get(2));
    }


    @Test
    void partitionOnAnEmptyTableHasNoGroups()
    {
        IDataTable t = MockTable.of().col("USUBJID", new String[0]).build();

        assertEquals(Set.of(), groupSet(GroupSemantics.partition(t, List.of("USUBJID"))));
        assertEquals(Set.of(), groupSet(GroupSemantics.partition(t, List.of("EPOCH"))));
    }


    /**
     * <b>Absence is not missingness — the pin.</b> EC-44 changes what an <em>absent</em> column
     * means; a <em>missing value</em> in a column that <em>exists</em> must still drop the row (Fix
     * #122 / EC-26 parity). If someone ever "aligns" the two, this fails.
     */
    @Test
    void partitionStillDropsRowsWhoseSurvivingKeyValueIsMissing()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", null, "S2").build();

        // Row 1's key is missing ⇒ dropped. Rows 0 and 2 keep their own groups.
        assertEquals(Set.of(Set.of(0), Set.of(2)),
                groupSet(GroupSemantics.partition(t, List.of("USUBJID"))));
        // …and that is NOT the same as the column being absent, which yields one group of all rows.
        assertEquals(Set.of(Set.of(0, 1, 2)),
                groupSet(GroupSemantics.partition(t, List.of("EPOCH"))));
    }


    @Test
    void allComponentColumnsAbsentIsOneWholeTableGroup()
    {
        // EC-44 (Fix #134): no component survives -> nothing can tell one row from another, so the
        // whole table is ONE group. Until Fix #134 this returned null and the operator yielded no
        // flags ("the previous total-absence contract") — inherited from the EC-24 addendum rather
        // than derived, and the last place the engine disagreed with its own absent-column
        // contract. The partial-absence case above is unchanged.
        IDataTable t = MockTable.of().col("PCTPTREF", "D1", "D2").build();
        List<int[]> groups = GroupSemantics.partitionCoalesced(t,
                comps(List.of("USUBJID", "POOLID")));
        assertEquals(Set.of(Set.of(0, 1)), groupSet(groups));
    }


    @Test
    void allComponentColumnsAbsentOnAnEmptyTableIsNoGroups()
    {
        IDataTable t = MockTable.of().col("PCTPTREF", new String[0]).build();
        assertEquals(Set.of(), groupSet(
                GroupSemantics.partitionCoalesced(t, comps(List.of("USUBJID", "POOLID")))));
    }

    // ---- has_multiple_values_for end-to-end (legacy == native) ----


    private static JsonNode nestedWithin()
    {
        return MAPPER.valueToTree(List.of(List.of("USUBJID", "POOLID")));
    }


    private static IDataTable pooledTable()
    {
        // Mirrors spec COALESCE-within-pooled. KEY=PCTPTREF (value), DEPENDENT=PCNOMDY (name).
        return MockTable.of().col("USUBJID", "", "", "", "", "", "001", "001")
                .col("POOLID", "P1", "P1", "P1", "P2", "P2", "", "")
                .col("PCTPTREF", "R1", "C1", "C1", "R1", "C1", "R1", "R1")
                .col("PCNOMDY", "1", "5", "6", "2", "5", "1", "9").build();
    }


    /**
     * The expected per-pool verdict for {@link #pooledTable()}: rows 1,2 (P1/C1 -&gt; {5,6}) and
     * rows 5,6 (001/R1 -&gt; {1,9}) fire; no cross-pool fire.
     */
    private static BitSet expectedPerPool()
    {
        BitSet bs = new BitSet();
        bs.set(1);
        bs.set(2);
        bs.set(5);
        bs.set(6);
        return bs;
    }


    @Test
    void hasMultipleValuesForCoalescePerPool()
    {
        IDataTable t = pooledTable();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("PCNOMDY")
                .operator("has_multiple_values_for").value(MAPPER.valueToTree("PCTPTREF"))
                .within(nestedWithin()).build();
        BitSet bs = NativeExprEvaluator.evaluate(CheckToExpr.toExpr(leaf),
                EvaluationContext.builder().table(t).build());
        // Fires: rows 1,2 (P1/C1 -> {5,6}) and rows 5,6 (001/R1 -> {1,9}). No cross-pool fire.
        assertTrue(bs.get(1));
        assertTrue(bs.get(2));
        assertTrue(bs.get(5));
        assertTrue(bs.get(6));
        assertFalse(bs.get(0));
        assertFalse(bs.get(3));
        assertFalse(bs.get(4));
        assertEquals(4, bs.cardinality());
    }


    @Test
    void hasMultipleValuesForCoalesceSurvivesPrintParseRoundTrip()
    {
        IDataTable t = pooledTable();
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("PCNOMDY")
                .operator("has_multiple_values_for").value(MAPPER.valueToTree("PCTPTREF"))
                .within(nestedWithin()).build();
        EvaluationContext ctx = EvaluationContext.builder().table(t).build();
        // Native via CheckToExpr -> Expr -> evaluator (exercises withinOperand + withinComponents).
        BitSet native1 = NativeExprEvaluator.evaluate(CheckToExpr.toExpr(leaf), ctx);
        assertEquals(expectedPerPool(), native1, "coalesced within must fire per pool");
        // And via the printed text round-trip (proves the nested-list prints and parses back).
        String printed = ExpressionPrinter.print(CheckToExpr.toExpr(leaf));
        BitSet native2 = NativeExprEvaluator.evaluate(CheckExpressionParser.parse(printed), ctx);
        assertEquals(expectedPerPool(), native2,
                "verdict must survive a print/parse round-trip: " + printed);
    }
}
