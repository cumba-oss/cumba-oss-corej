package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ExpressionException;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Verdict pins for the group / aggregate operator plans {@link ExprCompiler#compileBoolCall}
 * dispatches: uniqueness, functional dependency (incl. the {@code within=} partition, the EC-24
 * coalesce component and the {@code include_empty=} switch), cross-dataset consistency, enumerated
 * columns, set operators over {@code $}-operation lists, ordering and neighbouring-record checks.
 * Every case asserts the exact fired rows plus a non-firing counterpart, so a nulled dispatch
 * branch (a NullReturnVals mutant makes the whole plan null and evaluation blow up), a flipped
 * polarity, or a dropped kwarg wire changes an asserted verdict — exactly the class of engine
 * defect a rule reviewer cannot see.
 */
class ExprCompilerGroupAggregateVerdictTest
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

    // ---- uniqueness ---------------------------------------------------------------


    @Test
    void uniqueSetAndUniqueValueVerdicts()
    {
        IDataTable t = MockTable.of().name("DS").col("K1", "a", "a", "b", "c")
                .col("K2", "x", "x", "y", "z").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 1), eval("is_not_unique_set([K1, K2])", c),
                "duplicate (K1,K2) tuples must fire, unique ones must not");
        assertEquals(bits(2, 3), eval("is_unique_set([K1, K2])", c),
                "is_unique_set fires exactly the unique tuples");
        assertEquals(bits(0, 1), eval("is_not_unique_value(K1)", c),
                "single-column duplicate values must fire");
        assertEquals(bits(2, 3), eval("is_unique_value(K1)", c),
                "is_unique_value is the exact complement");
    }


    @Test
    void uniqueRelationshipVerdicts()
    {
        // "a" maps to two VALs (not 1:1); "b" maps to one.
        IDataTable t = MockTable.of().name("DS").col("NM", "a", "a", "b").col("VL", "1", "2", "3")
                .build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 1), eval("is_not_unique_relationship(NM, VL)", c),
                "a NAME with two distinct VALUEs is not a 1:1 relationship");
        assertEquals(bits(2), eval("is_unique_relationship(NM, VL)", c),
                "the positive twin fires exactly the complement");
    }

    // ---- has_multiple_values_for -----------------------------------------------------


    @Test
    void hasMultipleValuesForFiresPerKeyNotPerDataset()
    {
        IDataTable t = MockTable.of().name("DS").col("NAME", "v1", "v2", "w", "w")
                .col("KEY", "k1", "k1", "k2", "k2").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 1), eval("has_multiple_values_for(NAME, KEY)", c),
                "only the key with two distinct dependents fires");
    }


    @Test
    void withinPartitionLimitsTheDependencyScope()
    {
        // The same KEY value in two partitions: only the partition holding two distinct NAMEs
        // fires. Without within= the whole dataset is one scope and every row would fire.
        IDataTable t = MockTable.of().name("DS").col("NAME", "v1", "v2", "x", "x")
                .col("KEY", "k", "k", "k", "k").col("G", "g1", "g1", "g2", "g2").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 1, 2, 3), eval("has_multiple_values_for(NAME, KEY)", c),
                "without within= the dependency spans the dataset");
        assertEquals(bits(0, 1), eval("has_multiple_values_for(NAME, KEY, within=G)", c),
                "with within= only the violating partition fires");
    }


    @Test
    void nestedWithinComponentIsACoalesceGroupNotACompositeKey()
    {
        // C0 populated on row 0 only; C1 carries the fallback. Coalesced, rows 0 and 1 share the
        // partition value "p" (row0 from C0, row1 from C1) and expose the two distinct NAMEs; as a
        // flat composite key (C0,C1) every row would be its own group and nothing would fire.
        IDataTable t = MockTable.of().name("DS").col("NAME", "v1", "v2", "v3")
                .col("KEY", "k", "k", "k").col("C0", "p", "", "").col("C1", "zz", "p", "q").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 1), eval("has_multiple_values_for(NAME, KEY, within=[[C0, C1]])", c),
                "a nested [[C0, C1]] component must coalesce, not form a composite key");
        assertEquals(bits(), eval("has_multiple_values_for(NAME, KEY, within=[C0, C1])", c),
                "the flat [C0, C1] spelling is a composite key and must NOT fire here");
    }


    @Test
    void includeEmptyLetsBlanksParticipateInTheDependency()
    {
        IDataTable t = MockTable.of().name("DS").col("NAME", "v", "").col("KEY", "k", "k").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(), eval("has_multiple_values_for(NAME, KEY)", c),
                "by default a blank dependent is excluded (D.13) — one real value, no fire");
        assertEquals(bits(0, 1), eval("has_multiple_values_for(NAME, KEY, include_empty=true)", c),
                "include_empty=true makes the blank a real second value — both rows fire");
        assertEquals(bits(), eval("has_multiple_values_for(NAME, KEY, include_empty=false)", c),
                "an explicit include_empty=false is the default behaviour");
    }

    // ---- is_inconsistent_across_dataset ------------------------------------------------


    @Test
    void inconsistentAcrossDatasetFiresTheInconsistentGroupOnly()
    {
        IDataTable t = MockTable.of().name("DS").col("NAME", "a", "b", "c")
                .col("K", "s1", "s1", "s2").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 1), eval("is_inconsistent_across_dataset(NAME, keys=[K])", c),
                "the s1 group holds two NAME values and must fire; s2 must not");
    }


    @Test
    void inconsistentAcrossDatasetIncludeEmptySwitch()
    {
        IDataTable t = MockTable.of().name("DS").col("NAME", "a", "", "c")
                .col("K", "s1", "s1", "s2").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(), eval("is_inconsistent_across_dataset(NAME, keys=[K])", c),
                "a blank NAME is excluded by default — s1 has one value, no fire");
        assertEquals(bits(0, 1),
                eval("is_inconsistent_across_dataset(NAME, keys=[K], include_empty=true)", c),
                "include_empty=true makes the blank a second distinct value");
    }

    // ---- inconsistent_enumerated_columns -------------------------------------------------


    @Test
    void enumeratedColumnGapFires()
    {
        IDataTable t = MockTable.of().name("TS").col("TSVAL", "v", "v", "")
                .col("TSVAL1", "", "x", "").col("TSVAL2", "w", "y", "").build();
        EvaluationContext c = ctxOf(t);
        // Row 0: TSVAL1 empty but TSVAL2 populated — a gap. Row 1 is dense, row 2 all-blank.
        assertEquals(bits(0), eval("inconsistent_enumerated_columns(TSVAL)", c),
                "only a populated column AFTER a blank one is a gap");
    }

    // ---- has_same_values -------------------------------------------------------------------


    @Test
    void hasSameValuesFiresOnlyWhenEveryValueIsIdentical()
    {
        IDataTable t = MockTable.of().name("DS").col("H", "A", "A", "A").col("H2", "A", "B", "A")
                .build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 1, 2), eval("has_same_values(H)", c),
                "a constant column means the categorization is meaningless — all rows fire");
        assertEquals(bits(), eval("has_same_values(H2)", c), "two distinct values must not fire");
    }

    // ---- not_contains_all / contains_all ---------------------------------------------------


    @Test
    void notContainsAllOverAColumnSource()
    {
        IDataTable t = MockTable.of().name("DS").col("SRC", "A", "B", "A").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 1, 2), eval("not_contains_all(SRC, keys=[\"A\", \"C\"])", c),
                "a required value missing from the column's distinct set flags every row");
        assertEquals(bits(), eval("not_contains_all(SRC, keys=[\"A\", \"B\"])", c),
                "all required values present — no row fires");
        assertEquals(bits(0, 1, 2), eval("contains_all(SRC, keys=[\"A\", \"B\"])", c),
                "the positive twin fires where containment HOLDS");
    }


    @Test
    void notContainsAllOverDollarOperandsAndTheAbsentGuard()
    {
        IDataTable t = MockTable.of().name("DS").col("X", "r0", "r1").build();
        EvaluationContext c = EvaluationContext.builder().table(t).variables(Map.of("$src",
                List.of("A", "B"), "$req", List.of("A", "C"), "$reqOk", List.of("A"))).build();
        assertEquals(bits(0, 1), eval("not_contains_all($src, $req)", c),
                "a $-source lacking a required token flags every row");
        assertEquals(bits(), eval("not_contains_all($src, $reqOk)", c),
                "a $-source containing every required token fires nothing");
        // A $-source ABSENT from the context short-circuits to no rows (the evaluateLeaf guard),
        // NOT to the empty-set flag-all contract.
        assertEquals(bits(), eval("not_contains_all($absent, $req)", c),
                "an absent $-source must short-circuit to an empty verdict");
    }


    @Test
    void notContainsAllPerRowTokenBranch()
    {
        IDataTable t = MockTable.of().name("DS").col("TOK", "A;B", "A;X", "").build();
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("$allowed", List.of("A", "B"))).build();
        // Per-row verdict: row 1 carries the out-of-set token X; the blank row makes no decision.
        assertEquals(bits(1), eval("not_contains_all($allowed, split_by(TOK, \";\"))", c),
                "the split_by token branch must fire PER ROW, only where a token is out of set");
    }

    // ---- shares_no_elements_with -----------------------------------------------------------


    @Test
    void sharesNoElementsWithVerdictsAndGuards()
    {
        IDataTable t = MockTable.of().name("DS").col("X", "r0", "r1").build();
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("$a", List.of("A", "B"), "$b", List.of("B"), "$c", List.of("Z")))
                .build();
        assertEquals(bits(), eval("shares_no_elements_with($a, $b)", c),
                "a shared element means no violation");
        assertEquals(bits(0, 1), eval("shares_no_elements_with($a, $c)", c),
                "disjoint sets flag every row");
        assertEquals(bits(), eval("shares_no_elements_with($a, keys=[\"B\"])", c),
                "the keys= literal form: shared element, no violation");
        assertEquals(bits(0, 1), eval("shares_no_elements_with($a, keys=[\"Z\"])", c),
                "the keys= literal form: disjoint, all rows flagged");
        assertEquals(bits(), eval("shares_no_elements_with($absent, $b)", c),
                "an absent $-NAME short-circuits to an empty verdict, never flag-all");
    }

    // ---- is_not_ordered_subset_of ------------------------------------------------------------


    @Test
    void orderedSubsetVerdicts()
    {
        IDataTable t = MockTable.of().name("DS").col("X", "r0", "r1").build();
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("$sub", List.of("A", "C"), "$outOfOrder", List.of("C", "A"),
                        "$sup", List.of("A", "B", "C")))
                .build();
        assertEquals(bits(), eval("is_not_ordered_subset_of($sub, $sup)", c),
                "an in-order subsequence (gaps allowed) is no violation");
        assertEquals(bits(0, 1), eval("is_not_ordered_subset_of($outOfOrder, $sup)", c),
                "an out-of-order pair flags every row");
        assertEquals(bits(0, 1), eval("is_ordered_subset_of($sub, $sup)", c),
                "the positive twin fires where the subset relation HOLDS");
        assertEquals(bits(), eval("is_not_ordered_subset_of($absent, $sup)", c),
                "an absent $-NAME short-circuits to an empty verdict");
    }

    // ---- present_on_multiple_rows_within and its negation -------------------------------------


    @Test
    void presentOnMultipleRowsWithinBothPolarities()
    {
        IDataTable t = MockTable.of().name("DS").col("NAME", "n1", "n1", "n2")
                .col("G", "g", "g", "g").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 1), eval("present_on_multiple_rows_within(NAME, within=G)", c),
                "a (G,NAME) pair on two rows fires those rows");
        assertEquals(bits(2), eval("not present_on_multiple_rows_within(NAME, within=G)", c),
                "the Q1 negation fires exactly the singleton row — never a structural flip");
    }

    // ---- empty_within_except_last_row -----------------------------------------------------------


    @Test
    void emptyWithinExceptLastRowSparesTheLastRow()
    {
        IDataTable t = MockTable.of().name("DS").col("V", "", "x", "").col("G", "g", "g", "g")
                .col("O", "1", "2", "3").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0), eval("empty_within_except_last_row(V, G, ordering=O)", c),
                "a blank V fires except on the group's LAST ordered row");
    }

    // ---- not is_sorted_by
    // -------------------------------------------------------------------------


    @Test
    void targetNotSortedByFlagsTheUnsortedGroup()
    {
        IDataTable sorted = MockTable.of().name("DS").col("T", "5", "10", "20")
                .col("S", "1", "2", "3").build();
        IDataTable unsorted = MockTable.of().name("DS").col("T", "10", "5", "20")
                .col("S", "1", "2", "3").build();
        assertEquals(bits(), eval("not is_sorted_by(T, by=[asc(\"S\")])", ctxOf(sorted)),
                "a numerically non-decreasing target (5,10,20 — numeric, not textual) passes");
        assertEquals(bits(0, 1, 2), eval("not is_sorted_by(T, by=[asc(\"S\")])", ctxOf(unsorted)),
                "a decreasing step flags the whole group");
    }

    // ---- not has_next_corresponding_record
    // ---------------------------------------------------------


    @Test
    void nextCorrespondingRecordFiresTheBrokenChainRow()
    {
        IDataTable t = MockTable.of().name("SE").col("SEENDTC", "A", "B", "C")
                .col("SESTDTC", "Z", "A", "X").col("U", "s", "s", "s").col("SEQ", "1", "2", "3")
                .build();
        EvaluationContext c = ctxOf(t);
        // Row 0's END "A" matches row 1's START "A" (corresponds); row 1's END "B" does not match
        // row 2's START "X" (fires); row 2 is last (never fires).
        assertEquals(bits(1),
                eval("not has_next_corresponding_record(SEENDTC, SESTDTC, within=U,"
                        + " ordering=SEQ)", c),
                "only the row whose next ordered record does not correspond may fire");
    }


    @Test
    void unknownNeighbourRelationIsRejected()
    {
        IDataTable t = MockTable.of().name("SE").col("SEENDTC", "A").col("SESTDTC", "A")
                .col("U", "s").col("SEQ", "1").build();
        EvaluationContext c = ctxOf(t);
        assertThrows(ExpressionException.class,
                () -> eval("not has_next_corresponding_record(SEENDTC, SESTDTC, within=U,"
                        + " ordering=SEQ, relation=\"~=\")", c),
                "an unknown relation= spelling must be rejected, never silently identity");
    }

}
