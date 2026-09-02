package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * EC-44 — {@link IndexHelper#groupByPresent} and the five grouped {@link OperationExecutor}
 * evaluators that route through it.
 *
 * <p>
 * <b>The contract under test.</b> An absent column cannot differentiate any row from any other, so
 * every row is homogeneous with respect to that key and it partitions nothing: the column is
 * ignored, and when <em>no</em> declared column is present the whole dataset is one group. Before
 * EC-44 {@link IndexHelper#createIndex} returned {@code null} the moment any one group column was
 * missing, the operation bound to {@code null}, every comparison against the {@code $}-ref
 * evaluated false, and the rule reported SUCCESS with no findings — indistinguishable from "ran and
 * found nothing wrong". Seven shipped TS rules were dead on every study that simply did not collect
 * {@code TSGRPID}.
 * </p>
 */
@ExtendWith(MockitoExtension.class)
class IndexHelperGroupByPresentTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    // -----------------------------------------------------------------------
    // IndexHelper.groupByPresent — the partition itself
    // -----------------------------------------------------------------------

    @Test
    void oneOfTwoGroupColumnsAbsent_groupsOnTheSurvivor()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1", "S2").col("VAL", "a", "b", "c")
                .build();

        IndexHelper.Grouping g = IndexHelper.groupByPresent(t, List.of("USUBJID", "EPOCH"), "ctx");

        assertNotNull(g);
        assertEquals(List.of("USUBJID"), g.present());
        assertEquals(List.of("EPOCH"), g.dropped());
        assertEquals(Set.of(Set.of(0, 1), Set.of(2)), rowSets(g));
    }


    @Test
    void allGroupColumnsAbsent_isOneGroupOverEveryRow()
    {
        // The seven-TS-rule path: a single `group: [TSGRPID]` on a study that never collected it.
        IDataTable t = MockTable.of().col("TSPARMCD", "A", "B", "C").build();

        IndexHelper.Grouping g = IndexHelper.groupByPresent(t, List.of("TSGRPID"), "ctx");

        assertNotNull(g);
        assertEquals(List.of(), g.present());
        assertEquals(List.of("TSGRPID"), g.dropped());
        assertEquals(1, g.blocks().size());
        assertEquals(Set.of(Set.of(0, 1, 2)), rowSets(g));
    }


    /**
     * <b>The contract test — do not delete.</b> On the family-1 evaluators an absent column and a
     * column that is present but missing in every row must produce <em>the same groups</em> — every
     * row in one block either way. That structural identity is what makes dropping an absent column
     * exact rather than an approximation.
     *
     * <p>
     * ⚠ Re-pointed by {@code W38-A1} (Fix #249): the original assertion also required <em>the same
     * keys</em>, because {@code buildGroupKey} rendered both an absent component and a missing
     * component as {@code ""}. The <b>keys</b> now differ — an absent column still contributes
     * {@code ""} (there is no cell to classify), while a genuinely missing cell renders its marker
     * token — and that is fine for the contract, whose observable half is verdicts: each side of
     * every lookup renders the same classification, so a lookup agrees with its own build in both
     * scenarios and no verdict can tell them apart.
     * </p>
     *
     * <p>
     * It is also fragile: it holds only because these evaluators never call
     * {@link IndexHelper#isBlockKeyMissing}. Anyone "aligning" them with
     * {@link GroupSemantics#partition}'s drop-missing-key behaviour would silently return the five
     * operations to zero findings, which is the defect EC-44 exists to fix. There is deliberately
     * <b>no family-2 twin</b> of this test: on the {@code partition} paths a missing key drops the
     * row (Fix #122 / EC-26 parity), so absence and missingness legitimately differ there.
     * </p>
     */
    @Test
    void presentButAllValuesMissing_groupsIdenticallyToAbsent()
    {
        IDataTable absent = MockTable.of().col("TSPARMCD", "A", "B", "C").build();
        IDataTable allMissing = MockTable.of().col("TSPARMCD", "A", "B", "C")
                .col("TSGRPID", null, null, null).build();

        IndexHelper.Grouping fromAbsent = IndexHelper.groupByPresent(absent, List.of("TSGRPID"),
                "ctx");
        IndexHelper.Grouping fromMissing = IndexHelper.groupByPresent(allMissing,
                List.of("TSGRPID"), "ctx");

        assertNotNull(fromAbsent);
        assertNotNull(fromMissing);
        assertEquals(rowSets(fromAbsent), rowSets(fromMissing));
        assertEquals(Set.of(""), keys(fromAbsent),
                "an absent column has no cell to classify — its key component stays \"\"");
        assertEquals(Set.of(GroupKeyPolicy.KeyPart.MISSING_MIS.reportingForm()), keys(fromMissing),
                "a present-but-missing key names its identity — the W38-A1 half of the split");
    }


    /**
     * The degenerate key must be the full n-component encoding, not a single empty string: with two
     * declared columns it is one {@code NUL} separating two empty components. A 1-column test
     * cannot see the difference, and getting it wrong would make every row miss its group and
     * silently read {@code missingKeyDefault}.
     */
    @Test
    void degenerateKeyKeepsOneComponentPerDeclaredColumn()
    {
        IDataTable t = MockTable.of().col("DSCAT", "DISPOSITION EVENT", "DISPOSITION EVENT")
                .build();
        List<String> declared = List.of("USUBJID", "EPOCH");

        IndexHelper.Grouping g = IndexHelper.groupByPresent(t, declared, "ctx");

        assertNotNull(g);
        assertEquals(1, g.blocks().size());
        String key = g.blocks().get(0).key();
        assertEquals("\u0000", key,
                "expected two empty components joined by NUL, got " + debug(key));
        // and it must equal what the per-row lookup computes on the same table
        assertEquals(GroupedResult.buildKey(t.getMetaData(), t, declared, 0), key);
    }


    /**
     * The partial-drop partition must respect the surviving column's boundaries — a bug that
     * returned all rows in one block would still satisfy a single-subject fixture.
     */
    @Test
    void partialDropStillSeparatesTheSurvivingKeysValues()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1", "S2").build();

        Operation op = makeOp("$N", "record_count");
        op.setGroup(List.of("USUBJID", "EPOCH"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        GroupedResult gr = assertInstanceOf(GroupedResult.class, vars.get("$N"));

        assertEquals(2, gr.results().size());
        assertEquals(2L, gr.results()
                .get(GroupedResult.buildKey(t.getMetaData(), t, List.of("USUBJID", "EPOCH"), 0)));
        assertEquals(1L, gr.results()
                .get(GroupedResult.buildKey(t.getMetaData(), t, List.of("USUBJID", "EPOCH"), 2)));
    }


    @Test
    void noColumnAbsent_groupsExactlyAsBefore()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S2", "S1").build();

        IndexHelper.Grouping g = IndexHelper.groupByPresent(t, List.of("USUBJID"), "ctx");

        assertNotNull(g);
        assertEquals(List.of("USUBJID"), g.present());
        assertEquals(List.of(), g.dropped());
        assertEquals(Set.of(Set.of(0, 2), Set.of(1)), rowSets(g));
        // The keys are the raw values — byte-identical to the pre-EC-44 createIndex path.
        assertEquals(Set.of("S1", "S2"), keys(g));
    }


    @Test
    void emptyTable_hasNoGroups()
    {
        IDataTable t = MockTable.of().col("TSPARMCD", new String[0]).build();

        IndexHelper.Grouping present = IndexHelper.groupByPresent(t, List.of("TSPARMCD"), "ctx");
        IndexHelper.Grouping absent = IndexHelper.groupByPresent(t, List.of("TSGRPID"), "ctx");

        assertNotNull(present);
        assertNotNull(absent);
        assertEquals(List.of(), present.blocks());
        assertEquals(List.of(), absent.blocks());
    }


    /**
     * An unexpanded {@code $}-reference is a failure to resolve the rule's operation chain, not a
     * fact about the study's data — so it keeps the pre-EC-44 degrade instead of silently widening
     * the grouping to the whole dataset.
     */
    @Test
    void unexpandedOperationRef_isNotTreatedAsAnAbsentColumn()
    {
        IDataTable t = MockTable.of().col("X", "1").build();

        assertNull(IndexHelper.groupByPresent(t, List.of("$TIMING_VARIABLES"), "ctx"));
        assertNull(IndexHelper.groupByPresent(t, List.of("X", "$N"), "ctx"));
    }

    // -----------------------------------------------------------------------
    // The evaluators: the operation now produces a real result instead of null
    // -----------------------------------------------------------------------


    @Test
    void recordCountGrouped_absentGroupColumn_countsTheWholeDataset()
    {
        // CORE-000562's shape: record_count filtered to HLTSUBJI=N, grouped by the Perm TSGRPID,
        // on a TS dataset that does not carry TSGRPID.
        IDataTable t = MockTable.of().col("TSPARMCD", "HLTSUBJI", "TDIGRP", "TITLE")
                .col("TSVAL", "N", "", "A Study").build();

        Operation op = makeOp("$HLTSUBJI_N", "record_count");
        op.setGroup(List.of("TSGRPID"));
        op.setFilter(Map.of("TSPARMCD", "HLTSUBJI", "TSVAL", "N"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);

        GroupedResult gr = assertInstanceOf(GroupedResult.class, vars.get("$HLTSUBJI_N"));
        assertEquals(1, gr.results().size());
        assertEquals(1L, gr.results().values().iterator().next());
        // The declared column list is retained, so the per-row lookup key still matches.
        assertEquals(List.of("TSGRPID"), gr.groupColumns());
    }


    /**
     * The key built from the index side and the key {@link GroupedResult#getForRow} builds from a
     * row must agree, or every row would miss and read {@code missingKeyDefault}. They agree
     * because both render an absent column as {@code ""} — this is why {@link GroupedResult} needed
     * no change.
     */
    @Test
    void degenerateGroupKey_matchesTheRowSideLookupKey()
    {
        IDataTable t = MockTable.of().col("TSPARMCD", "HLTSUBJI", "TDIGRP").build();

        Operation op = makeOp("$N", "record_count");
        op.setGroup(List.of("TSGRPID"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        GroupedResult gr = assertInstanceOf(GroupedResult.class, vars.get("$N"));

        String rowKey = GroupedResult.buildKey(t.getMetaData(), t, List.of("TSGRPID"), 0);
        assertTrue(gr.results().containsKey(rowKey),
                "row-side key " + debug(rowKey) + " not among index-side keys "
                        + gr.results().keySet().stream().map(IndexHelperGroupByPresentTest::debug)
                                .collect(Collectors.joining(", ")));
        assertEquals(2L, gr.results().get(rowKey));
    }


    @Test
    void recordCountGrouped_missingKeyDefaultStillResolvesToZero()
    {
        IDataTable t = MockTable.of().col("TSPARMCD", "TITLE", "TITLE").build();

        Operation op = makeOp("$N", "record_count");
        op.setGroup(List.of("TSGRPID"));
        op.setFilter(Map.of("TSPARMCD", "HLTSUBJI"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        GroupedResult gr = assertInstanceOf(GroupedResult.class, vars.get("$N"));

        // No row matches the filter, but the group exists ⇒ 0, and an unknown key also reads 0.
        assertEquals(0L, gr.results().values().iterator().next());
        assertEquals(0L, gr.defaultForMissingKey());
    }


    @Test
    void maxGrouped_absentGroupColumn_isTheDatasetWideMaximum()
    {
        IDataTable t = MockTable.of().col("SEQ", "3", "7", "5").build();

        Operation op = makeOp("$MAX", "max");
        op.setName("SEQ");
        op.setGroup(List.of("TSGRPID"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        GroupedResult gr = assertInstanceOf(GroupedResult.class, vars.get("$MAX"));

        assertEquals(1, gr.results().size());
        assertEquals(7.0, gr.results().values().iterator().next());
    }


    @Test
    void distinctGrouped_absentGroupColumn_isTheDatasetWideDistinctSet()
    {
        IDataTable t = MockTable.of().col("ARM", "A", "B", "A").build();

        Operation op = makeOp("$D", "distinct");
        op.setName("ARM");
        op.setGroup(List.of("TSGRPID"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        GroupedResult gr = assertInstanceOf(GroupedResult.class, vars.get("$D"));

        assertEquals(1, gr.results().size());
        assertEquals(List.of("A", "B"), gr.results().values().iterator().next());
    }

    // -----------------------------------------------------------------------
    // EC-44 residual §7 — evalDistinctGrouped honours the operation filter
    // -----------------------------------------------------------------------


    /**
     * {@code evalDistinctGrouped} read {@code op.getFilter()} nowhere, unlike its three sibling
     * grouped evaluators ({@code evalRecordCountGrouped}, {@code evalMaxGrouped},
     * {@code evalDateExtremeGrouped}), so a {@code distinct} carrying a {@code filter:} meant
     * different things on the two lanes — the fork's {@code distinct.py} applies
     * {@code _filter_data} and groups the filtered frame. coreJ was the outlier.
     *
     * <p>
     * Measured before the change: of the shipped corpus (deduped by rule id) 13 rules carry a
     * grouped {@code distinct} and 11 a filtered one, and <b>none carries both</b> — so this aligns
     * a latent inconsistency and moves no shipped rule's result.
     * </p>
     */
    @Test
    void distinctGrouped_honoursTheOperationFilter()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1", "S1")
                .col("AEDECOD", "HEADACHE", "NAUSEA", "RASH").col("AESER", "Y", "N", "Y").build();

        Operation op = makeOp("$D", "distinct");
        op.setName("AEDECOD");
        op.setGroup(List.of("USUBJID"));
        op.setFilter(Map.of("AESER", "Y"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        GroupedResult gr = assertInstanceOf(GroupedResult.class, vars.get("$D"));

        assertEquals(1, gr.results().size());
        // NAUSEA is filtered out; without the filter this was [HEADACHE, NAUSEA, RASH].
        assertEquals(List.of("HEADACHE", "RASH"), gr.results().values().iterator().next());
    }


    /** The filter and the EC-44 absent-column drop compose: filter first, then one group. */
    @Test
    void distinctGrouped_filterAppliesWhenTheGroupColumnIsAbsentToo()
    {
        IDataTable t = MockTable.of().col("AEDECOD", "HEADACHE", "NAUSEA", "RASH")
                .col("AESER", "Y", "N", "Y").build();

        Operation op = makeOp("$D", "distinct");
        op.setName("AEDECOD");
        op.setGroup(List.of("TSGRPID"));
        op.setFilter(Map.of("AESER", "Y"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        GroupedResult gr = assertInstanceOf(GroupedResult.class, vars.get("$D"));

        assertEquals(1, gr.results().size());
        assertEquals(List.of("HEADACHE", "RASH"), gr.results().values().iterator().next());
    }


    /**
     * A group left with nothing after filtering contributes no entry — the same convention
     * {@code evalMaxGrouped} and {@code evalDateExtremeGrouped} already use (they {@code put} only
     * when a value survived).
     */
    @Test
    void distinctGrouped_groupFilteredEmpty_contributesNoEntry()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S2")
                .col("AEDECOD", "HEADACHE", "NAUSEA").col("AESER", "Y", "N").build();

        Operation op = makeOp("$D", "distinct");
        op.setName("AEDECOD");
        op.setGroup(List.of("USUBJID"));
        op.setFilter(Map.of("AESER", "Y"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        GroupedResult gr = assertInstanceOf(GroupedResult.class, vars.get("$D"));

        assertEquals(1, gr.results().size());
        assertEquals(List.of("HEADACHE"), gr.results().values().iterator().next());
    }


    @Test
    void maxDateGrouped_absentGroupColumn_isTheDatasetWideExtreme()
    {
        IDataTable t = MockTable.of().col("DTC", "2020-01-02", "2021-06-01", "2019-01-01").build();

        Operation op = makeOp("$MD", "max_date");
        op.setName("DTC");
        op.setGroup(List.of("TSGRPID"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        GroupedResult gr = assertInstanceOf(GroupedResult.class, vars.get("$MD"));

        assertEquals(1, gr.results().size());
        assertEquals("2021-06-01", gr.results().values().iterator().next());
    }


    @Test
    void hasMixedEmptinessWithinGroup_absentGroupColumn_asksTheQuestionOfTheWholeDataset()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S2").col("VAL", "x", "").build();

        Operation op = makeOp("$MIX", "has_mixed_emptiness_within_group");
        op.setName("VAL");
        op.setGroup(List.of("EPOCH"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), t, NO_RESOLVER);
        GroupedResult gr = assertInstanceOf(GroupedResult.class, vars.get("$MIX"));

        assertEquals(1, gr.results().size());
        assertEquals(true, gr.results().values().iterator().next());
    }


    /**
     * §5.4 — presence is resolved per table. When an operation carries {@code domain:} the table
     * being grouped is not the evaluation table, so a column present in one and absent from the
     * other legitimately yields keys that do not meet: the operation side collapses to one
     * {@code ""} group while the row side still renders real values, every row misses, and the
     * per-row read falls back to {@code missingKeyDefault}. This asymmetry pre-dates EC-44 (before
     * it, the whole operation was simply null); no shipped rule hits it — all four
     * {@code domain:}-carrying exposed rules are same-table. Pinned so a future reader can see it
     * was considered rather than overlooked.
     *
     * <p>
     * <b>EC-45 §5.3 — why the fallback is benign here is not luck, it is the classification.</b>
     * The operation is {@code record_count}, whose declared empty result is {@code 0L}: no record
     * for the group really <em>is</em> zero records, so the row reads a correct answer and a
     * {@code $N > 0} leaf does not fire. The property that matters is therefore the non-firing,
     * which the second half of this test now asserts rather than leaving implied. For a
     * {@link net.cumba.cdisc.core.model.EmptyResult#MISSING}-declaring aggregate the same path
     * reads "no value" instead, the comparison folds it to {@code ""} and the check fires — also
     * correct, and also decided by the declaration rather than by the shape of the join.
     * </p>
     *
     * <p>
     * ⚠ Do not "fix" this by widening the join. {@code IndexHelper.buildGroupKey} deliberately
     * emits the <b>full declared list</b> with {@code ""} for absent columns, so the family-1
     * cross-table join misses on purpose; a policy that instead dropped the absent column would
     * hand the row the study-wide aggregate — a plausible wrong number in place of a clean miss,
     * and two contradictory answers to one fact inside one engine.
     * </p>
     */
    @Test
    void crossTableAsymmetry_keysDoNotMeetAndTheRowSideReadsTheDefault()
    {
        IDataTable eval = MockTable.of().name("DS").col("USUBJID", "S1", "S2")
                .col("EPOCH", "SCREENING", "TREATMENT").build();
        IDataTable foreign = MockTable.of().name("DM").col("USUBJID", "S1", "S2").build();

        Operation op = makeOp("$N", "record_count");
        op.setDomain("DM");
        op.setGroup(List.of("USUBJID", "EPOCH"));

        Map<String, Object> vars = OperationExecutor.execute(List.of(op), eval,
                d -> "DM".equals(d) ? foreign : null);
        GroupedResult gr = assertInstanceOf(GroupedResult.class, vars.get("$N"));

        // Grouped on the foreign table, where EPOCH is absent ⇒ keys carry "" for it.
        String foreignKey = gr.results().keySet().iterator().next();
        String evalRowKey = GroupedResult.buildKey(eval.getMetaData(), eval,
                List.of("USUBJID", "EPOCH"), 0);
        assertEquals(2, gr.results().size());
        assertNotEquals(evalRowKey, foreignKey,
                "expected the cross-table keys to differ; both were " + debug(foreignKey));

        // EC-45 §5.3 — the property that actually matters: every evaluation row misses, and what
        // it then reads is record_count's DECLARED empty result (0L, "no record for the group is
        // zero records"), not an accident. A `$N > 0` leaf therefore does not fire.
        EvaluationContext ctx = EvaluationContext.builder().table(eval).build();
        for (long r = 0; r < eval.getRowCount(); r++)
        {
            assertNull(gr.getForRow(ctx, r), "row " + r + " must miss");
            assertEquals(0L, gr.getForRowOrDefault(ctx, r), "row " + r + " reads the declaration");
        }
    }

    // -----------------------------------------------------------------------
    // helpers
    // -----------------------------------------------------------------------


    private static Set<Set<Integer>> rowSets(IndexHelper.Grouping g)
    {
        return g.blocks().stream().map(b ->
        {
            Set<Integer> s = new java.util.LinkedHashSet<>();
            for (int r : b.rows())
            {
                s.add(r);
            }
            return s;
        }).collect(Collectors.toSet());
    }


    private static Set<String> keys(IndexHelper.Grouping g)
    {
        return g.blocks().stream().map(IndexHelper.GroupBlock::key).collect(Collectors.toSet());
    }


    private static String debug(String key)
    {
        return "[" + key.replace("\0", "\\0") + "]";
    }


    private static Operation makeOp(String id, String operator)
    {
        Operation op = new Operation();
        op.setId(id);
        op.setOperator(operator);
        return op;
    }
}
