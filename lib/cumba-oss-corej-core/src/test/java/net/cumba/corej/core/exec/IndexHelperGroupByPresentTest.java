package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.GroupKey;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * EC-44 — {@link IndexHelper#groupByPresent} and the grouped evaluators that route through it (the
 * five {@code OperationExecutor} evaluators when EC-44 landed; registry functions since the
 * runbook, the executor retired in W8).
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

    /**
     * {@code record_count(...)} as the registry function it is since runbook W6
     * ({@code RecordCount}, over this class's {@code groupByPresent}): every row's count through
     * the identity-key broadcast.
     */
    private static List<Object> counts(String aCall, IDataTable aTable, DatasetResolver aResolver)
    {
        EvaluationContext ctx = EvaluationContext.builder().table(aTable).datasetResolver(aResolver)
                .ruleId("GBP").build();
        net.cumba.corej.core.expr.eval.Vector v = net.cumba.corej.core.expr.eval.ExprCompiler
                .evaluateValueExpression(
                        net.cumba.corej.core.expr.CheckExpressionParser.parse(aCall), ctx);
        assertNotNull(v);
        List<Object> out = new java.util.ArrayList<>();
        for (int row = 0; row < ctx.rowCount(); row++)
        {
            out.add(v.value(row).cell().getValue());
        }
        return out;
    }


    /** The per-row list cells of a grouped list function (runbook W7, {@code distinct}). */
    private static List<Object> lists(String aCall, IDataTable aTable, DatasetResolver aResolver)
    {
        EvaluationContext ctx = EvaluationContext.builder().table(aTable).datasetResolver(aResolver)
                .ruleId("GBP").build();
        net.cumba.corej.core.expr.eval.Vector v = net.cumba.corej.core.expr.eval.ExprCompiler
                .evaluateValueExpression(
                        net.cumba.corej.core.expr.CheckExpressionParser.parse(aCall), ctx);
        assertNotNull(v);
        List<Object> out = new java.util.ArrayList<>();
        for (int row = 0; row < ctx.rowCount(); row++)
        {
            out.add(v.value(row).resolved());
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // IndexHelper.groupByPresent — the partition itself
    // -----------------------------------------------------------------------


    @Test
    void oneOfTwoGroupColumnsAbsent_groupsOnTheSurvivor()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1", "S2").col("VAL", "a", "b", "c")
                .build();

        IndexHelper.Grouping g = ExecCalls.groupByPresent(t, List.of("USUBJID", "EPOCH"), "ctx");

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

        IndexHelper.Grouping g = ExecCalls.groupByPresent(t, List.of("TSGRPID"), "ctx");

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
     * {@code ""} (there is no cell to classify), while a genuinely missing cell keys under its
     * {@code MissingValue} identity ({@code PLAN-grouping-key-identity}) — and that is fine for the
     * contract, whose observable half is verdicts: each side of every lookup derives the same key
     * ({@link GroupKeyIdentity#identityKey}), so a lookup agrees with its own build in both
     * scenarios and no verdict can tell them apart.
     * </p>
     *
     * <p>
     * It is also fragile: it holds only because these evaluators never call
     * {@link IndexHelper#isBlockKeyMissing}. Anyone "aligning" them with
     * {@link GroupSemantics#group}'s drop-missing-key behaviour would silently return the five
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

        IndexHelper.Grouping fromAbsent = ExecCalls.groupByPresent(absent, List.of("TSGRPID"),
                "ctx");
        IndexHelper.Grouping fromMissing = ExecCalls.groupByPresent(allMissing, List.of("TSGRPID"),
                "ctx");

        assertNotNull(fromAbsent);
        assertNotNull(fromMissing);
        assertEquals(rowSets(fromAbsent), rowSets(fromMissing));
        assertEquals(Set.of(""), keys(fromAbsent),
                "an absent column has no cell to classify — its key component stays \"\"");
        assertEquals(Set.of(MissingValue.MIS), keys(fromMissing),
                "a present-but-missing key names its identity — the W38-A1 half of the split");
    }


    /**
     * The degenerate key must be the full n-component key, not a single empty string: with two
     * declared columns it is {@code GroupKey.of("", "")}. A 1-column test cannot see the
     * difference, and getting it wrong would make every row miss its group and silently read
     * {@code missingKeyDefault} — H1 of {@code PLAN-grouping-key-identity}: the all-absent branch
     * used to build the TEXT key {@code "\0"}, which never met the lookup's identity key.
     */
    @Test
    void degenerateKeyKeepsOneComponentPerDeclaredColumn()
    {
        IDataTable t = MockTable.of().col("DSCAT", "DISPOSITION EVENT", "DISPOSITION EVENT")
                .build();
        List<String> declared = List.of("USUBJID", "EPOCH");

        IndexHelper.Grouping g = ExecCalls.groupByPresent(t, declared, "ctx");

        assertNotNull(g);
        assertEquals(1, g.blocks().size());
        Object key = g.blocks().get(0).key();
        assertEquals(GroupKey.of("", ""), key, "expected two empty components, got " + debug(key));
        // and it must equal what the per-row lookup computes on the same table
        assertEquals(GroupKeyIdentity.identityKey(t.getMetaData(), t, declared, 0), key);
    }


    /**
     * The partial-drop partition must respect the surviving column's boundaries — a bug that
     * returned all rows in one block would still satisfy a single-subject fixture.
     */
    @Test
    void partialDropStillSeparatesTheSurvivingKeysValues()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S1", "S2").build();
        // S1's two rows read 2, S2's row reads 1: the partition respects the survivor.
        assertEquals(List.of(2L, 2L, 1L),
                counts("record_count(group=[USUBJID, EPOCH])", t, NO_RESOLVER));
    }


    @Test
    void noColumnAbsent_groupsExactlyAsBefore()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S2", "S1").build();

        IndexHelper.Grouping g = ExecCalls.groupByPresent(t, List.of("USUBJID"), "ctx");

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

        IndexHelper.Grouping present = ExecCalls.groupByPresent(t, List.of("TSPARMCD"), "ctx");
        IndexHelper.Grouping absent = ExecCalls.groupByPresent(t, List.of("TSGRPID"), "ctx");

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

        assertNull(ExecCalls.groupByPresent(t, List.of("$TIMING_VARIABLES"), "ctx"));
        assertNull(ExecCalls.groupByPresent(t, List.of("X", "$N"), "ctx"));
    }

    // -----------------------------------------------------------------------
    // The evaluators: the operation now produces a real result instead of null
    // -----------------------------------------------------------------------


    @Test
    void recordCountGrouped_absentGroupColumn_countsTheWholeDataset()
    {
        // CDISC-CG0273's shape: a record_count filtered to TSPARMCD=HLTSUBJI / TSVAL=N, grouped
        // by a column the TS dataset does not carry. ⚠ The shipped rule groups by [STUDYID]; the
        // Permissible TSGRPID used here is this test's own choice of an ABSENT group column, which
        // is the case under test.
        IDataTable t = MockTable.of().col("TSPARMCD", "HLTSUBJI", "TDIGRP", "TITLE")
                .col("TSVAL", "N", "", "A Study").build();
        // One whole-table group holding the one matching row; the declared column list is
        // retained on both sides of the broadcast, so every row reads that group's 1.
        assertEquals(List.of(1L, 1L, 1L), counts(
                "record_count(group=[TSGRPID], filter=(TSPARMCD == \"HLTSUBJI\" and TSVAL == \"N\"))",
                t, NO_RESOLVER));
    }


    /**
     * The key built from the index side and the key a per-row lookup builds from a row (the retired
     * {@code GroupedResult.getForRow} until runbook W8) must agree, or every row would miss and
     * read {@code missingKeyDefault}. They agree because both sides are the one derivation
     * {@link GroupKeyIdentity#identityKey}, which keys an absent column as {@code ""}.
     */
    @Test
    void degenerateGroupKey_matchesTheRowSideLookupKey()
    {
        IDataTable t = MockTable.of().col("TSPARMCD", "HLTSUBJI", "TDIGRP").build();
        // Both rows find the degenerate whole-table group (its key is "" on both sides).
        assertEquals(List.of(2L, 2L), counts("record_count(group=[TSGRPID])", t, NO_RESOLVER));
    }


    @Test
    void recordCountGrouped_missingKeyDefaultStillResolvesToZero()
    {
        IDataTable t = MockTable.of().col("TSPARMCD", "TITLE", "TITLE").build();
        // No row matches the filter, but the group exists ⇒ 0 (and a row with no group reads the
        // same 0 — RecordCountFunctionTest).
        assertEquals(List.of(0L, 0L),
                counts("record_count(group=[TSGRPID], filter=(TSPARMCD == \"HLTSUBJI\"))", t,
                        NO_RESOLVER));
    }


    @Test
    void distinctGrouped_absentGroupColumn_isTheDatasetWideDistinctSet()
    {
        // (distinct is a registry function since runbook W7 — PLAN-distinct-function — answering
        // each row its group's list; the claim is the same.)
        IDataTable t = MockTable.of().col("ARM", "A", "B", "A").build();
        assertEquals(List.of(List.of("A", "B"), List.of("A", "B"), List.of("A", "B")),
                lists("distinct(ARM, group=[TSGRPID])", t, NO_RESOLVER));
    }

    // -----------------------------------------------------------------------
    // EC-44 residual §7 — the grouped distinct honours its filter (a registry function since W7)
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
        // NAUSEA is filtered out; without the filter this was [HEADACHE, NAUSEA, RASH].
        assertEquals(
                List.of(List.of("HEADACHE", "RASH"), List.of("HEADACHE", "RASH"),
                        List.of("HEADACHE", "RASH")),
                lists("distinct(AEDECOD, group=[USUBJID], filter=(AESER == \"Y\"))", t,
                        NO_RESOLVER));
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
     * {@code MISSING}-declaring aggregate (the retired {@code EmptyResult}) the same path reads "no
     * value" instead, the comparison folds it to {@code ""} and the check fires — also correct, and
     * also decided by the declaration rather than by the shape of the join.
     * </p>
     *
     * <p>
     * ⚠ Do not "fix" this by widening the join. {@code IndexHelper.buildGroupKey} deliberately keys
     * the <b>full declared list</b> with {@code ""} for absent columns, so the family-1 cross-table
     * join misses on purpose; a policy that instead dropped the absent column would hand the row
     * the study-wide aggregate — a plausible wrong number in place of a clean miss, and two
     * contradictory answers to one fact inside one engine.
     * </p>
     */
    @Test
    void crossTableAsymmetry_keysDoNotMeetAndTheRowSideReadsTheDefault()
    {
        IDataTable eval = MockTable.of().name("DS").col("USUBJID", "S1", "S2")
                .col("EPOCH", "SCREENING", "TREATMENT").build();
        IDataTable foreign = MockTable.of().name("DM").col("USUBJID", "S1", "S2").build();
        // Grouped on the foreign table, where EPOCH is absent, the keys carry "" for it; the
        // evaluated rows carry their real EPOCH, so no key meets and every row reads
        // record_count's declared empty result, 0 — "no record for the group" is zero records.
        assertEquals(List.of(0L, 0L), counts("record_count(domain=DM, group=[USUBJID, EPOCH])",
                eval, d -> "DM".equals(d) ? foreign : null));
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


    private static Set<Object> keys(IndexHelper.Grouping g)
    {
        return g.blocks().stream().map(IndexHelper.GroupBlock::key).collect(Collectors.toSet());
    }


    private static String debug(Object key)
    {
        return "[" + String.valueOf(key).replace("\0", "\\0") + "]";
    }

}
