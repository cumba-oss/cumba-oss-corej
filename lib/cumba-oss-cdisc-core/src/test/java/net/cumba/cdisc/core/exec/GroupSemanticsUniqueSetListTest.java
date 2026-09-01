package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * {@link GroupSemantics#uniqueSetViolations(IDataTable, int, List, String, boolean, GroupKeyPolicy)}
 * — the single-list primitive of owner requirement #1 (2026-08-23,
 * {@code plans/PLAN-authoring-grammar-unique-set-and-output-exclusion.md} §3.4 D-4 / §3.2 D-6).
 * <b>No member is privileged</b>: an absent member in any position is the same partition as its
 * removal; a {@code null} (unresolved) member drops instead of short-circuiting; when every member
 * drops the documented degenerate flood stands; and {@code regex=} normalises member 0 exactly as
 * it normalises member 2.
 */
@ExtendWith(MockitoExtension.class)
class GroupSemanticsUniqueSetListTest
{

    /** Rows 0/1 share (A, B, C); row 2 differs on C only; row 3 differs on A only. */
    private static IDataTable table()
    {
        return MockTable.of().name("DS").col("A", "a1", "a1", "a1", "a2")
                .col("B", "b1", "b1", "b1", "b1").col("C", "c1", "c1", "c2", "c1").build();
    }


    private static BitSet dup(IDataTable t, List<? extends @Nullable String> keys)
    {
        return GroupSemantics.uniqueSetViolations(t, 4, keys, null, true,
                GroupKeyPolicy.FOLD_BLANK_KEYS);
    }


    private static BitSet unique(IDataTable t, List<? extends @Nullable String> keys)
    {
        return GroupSemantics.uniqueSetViolations(t, 4, keys, null, false,
                GroupKeyPolicy.FOLD_BLANK_KEYS);
    }


    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    @Test
    void fullTupleBaseline()
    {
        assertEquals(bits(0, 1), dup(table(), List.of("A", "B", "C")));
        assertEquals(bits(2, 3), unique(table(), List.of("A", "B", "C")));
    }


    @Test
    void anAbsentMemberInEveryPositionIsTheSamePartitionAsItsRemoval()
    {
        IDataTable t = table();
        // "X" is absent from the table. Insert it first, in the middle and last: the verdict is
        // the three-member verdict every time — no position is special.
        for (List<String> withAbsent : List.of(List.of("X", "A", "B", "C"),
                List.of("A", "X", "B", "C"), List.of("A", "B", "C", "X")))
        {
            assertEquals(dup(t, List.of("A", "B", "C")), dup(t, withAbsent), withAbsent.toString());
            assertEquals(unique(t, List.of("A", "B", "C")), unique(t, withAbsent),
                    withAbsent.toString());
        }
        // And the absent member in position 0 of a SHORTER tuple regroups on the survivors:
        // (B, C) makes rows 0/1/3 one group.
        assertEquals(bits(0, 1, 3), dup(t, List.of("X", "B", "C")));
        assertEquals(dup(t, List.of("B", "C")), dup(t, List.of("X", "B", "C")));
    }


    @Test
    void aNullMemberDropsRatherThanShortCircuiting()
    {
        IDataTable t = table();
        // D-4: a null member — an unresolved operand — is dropped like an absent column, in
        // every position. The retired contract returned an EMPTY BitSet for a null FIRST operand
        // (the old `nameColName == null` short-circuit), which under `not is_unique_set` went
        // silent; now it regroups.
        List<@Nullable String> nullFirst = Arrays.asList(null, "B", "C");
        List<@Nullable String> nullMiddle = Arrays.asList("B", null, "C");
        List<@Nullable String> nullLast = Arrays.asList("B", "C", null);
        for (List<@Nullable String> keys : List.of(nullFirst, nullMiddle, nullLast))
        {
            assertEquals(bits(0, 1, 3), dup(t, keys), keys.toString());
            assertEquals(bits(2), unique(t, keys), keys.toString());
        }
    }


    @Test
    void whenEveryMemberDropsTheTupleIsEmptyAndTheDocumentedDegenerateCaseStands()
    {
        IDataTable t = table();
        List<@Nullable String> allGone = new ArrayList<>();
        allGone.add("X");
        allGone.add(null);
        allGone.add("Y");
        // Empty tuple: every row carries the same key — all duplicates (flagDuplicates floods
        // the whole table), none unique (the complement is empty).
        assertEquals(bits(0, 1, 2, 3), dup(t, allGone));
        assertEquals(new BitSet(), unique(t, allGone));
        // The explicit empty list is the same runtime contract (an AUTHORED empty list is a load
        // error, RulePackageLoader.validateInlineUniqueSetShape — not this method's concern).
        assertEquals(bits(0, 1, 2, 3), dup(t, List.of()));
        // Zero rows: nothing to flag either way.
        assertTrue(GroupSemantics
                .uniqueSetViolations(t, 0, List.of("A"), null, true, GroupKeyPolicy.FOLD_BLANK_KEYS)
                .isEmpty());
    }


    @Test
    void regexNormalisesMemberZeroExactlyAsMemberTwo()
    {
        // Two date-time columns that agree at date granularity only; B is constant.
        IDataTable t = MockTable.of().name("DS").col("D1", "2024-01-01T10", "2024-01-01T11")
                .col("B", "b", "b").col("D2", "2024-02-02T08", "2024-02-02T09").build();
        String regex = "^\\d{4}-\\d{2}-\\d{2}";
        // Without the regex every row is distinct on both date-times.
        assertEquals(new BitSet(), GroupSemantics.uniqueSetViolations(t, 2,
                List.of("D1", "B", "D2"), null, true, GroupKeyPolicy.FOLD_BLANK_KEYS));
        // With it, member 0 (D1) and member 2 (D2) BOTH collapse to their date — rows 0/1 are one
        // tuple — whichever of the two sits first. D-6, asserted both ways.
        assertEquals(bits(0, 1), GroupSemantics.uniqueSetViolations(t, 2, List.of("D1", "B", "D2"),
                regex, true, GroupKeyPolicy.FOLD_BLANK_KEYS));
        assertEquals(bits(0, 1), GroupSemantics.uniqueSetViolations(t, 2, List.of("D2", "B", "D1"),
                regex, true, GroupKeyPolicy.FOLD_BLANK_KEYS));
        // And the single-member tuples agree with each other: D1 alone and D2 alone normalise
        // identically.
        assertEquals(
                GroupSemantics.uniqueSetViolations(t, 2, List.of("D1"), regex, true,
                        GroupKeyPolicy.FOLD_BLANK_KEYS),
                GroupSemantics.uniqueSetViolations(t, 2, List.of("D2"), regex, true,
                        GroupKeyPolicy.FOLD_BLANK_KEYS));
        assertEquals(bits(0, 1), GroupSemantics.uniqueSetViolations(t, 2, List.of("D2"), regex,
                true, GroupKeyPolicy.FOLD_BLANK_KEYS));
    }


    @Test
    void orderIsPreservedAndARepeatedMemberIsInert()
    {
        IDataTable t = table();
        // CDISC-AD0688's [USUBJID, USUBJID, SPDEVID] shape: a repeated member changes nothing.
        assertEquals(dup(t, List.of("A", "C")), dup(t, List.of("A", "A", "C")));
        assertEquals(dup(t, List.of("A", "C")), dup(t, List.of("C", "A")));
    }
}
