package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.BindingValue;
import net.cumba.corej.core.exec.DatasetLookup;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.BroadcastFold.BindColumnLevel;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@link BroadcastFold#bindColumnLevel} — the phase-5 bind-time classification of a bare
 * column-reference name: context variables first (resolution order — the Fix #10 shape), then
 * foldability, then primary-table presence. ⚠ This javadoc said "then primary-table AND JOINED
 * presence" until 2026-09-21; joined presence is no longer consulted, which is the whole point of
 * the case added at the bottom of this class.
 */
class BindColumnLevelTest
{

    private static EvaluationContext ctx(Map<String, Object> variables)
    {
        IDataTable table = MockTable.of().name("AE").col("AETERM", "x").build();
        return EvaluationContext.builder().table(table).variables(variables).build();
    }


    @Test
    void aPresentColumnIsARowRead()
    {
        assertEquals(BindColumnLevel.ROW, BroadcastFold.bindColumnLevel("AETERM", ctx(Map.of())));
    }


    @Test
    void anAbsentFoldableColumnIsADatasetConstant()
    {
        // D39a: absent from the primary table with no joins to surface it.
        assertEquals(BindColumnLevel.DATASET_ABSENT,
                BroadcastFold.bindColumnLevel("ZZFOO", ctx(Map.of())));
    }


    @Test
    void aScalarContextVariableWinsOverAbsence()
    {
        // Variables resolve before columns in both engines (Fix #10's DOMAIN injection).
        assertEquals(BindColumnLevel.DATASET_CONTEXT_SCALAR,
                BroadcastFold.bindColumnLevel("DOMAIN", ctx(Map.of("DOMAIN", "AE"))));
    }


    @Test
    void aNonScalarContextValueDoesNotCount()
    {
        // A per-row compiled binding (its hand-over a Vector; until runbook W8 a GroupedResult) is
        // not a scalar fact; the name then classifies by presence.
        BindingValue perRow = new BindingValue(new CompiledBinding("ZZFOO",
                CheckExpressionParser.parse("upper(AETERM)"), List.of(), Domain.ROW));
        assertEquals(BindColumnLevel.DATASET_ABSENT,
                BroadcastFold.bindColumnLevel("ZZFOO", ctx(Map.of("ZZFOO", perRow))));
    }


    @Test
    void nonFoldableNamesStayRowReads()
    {
        // `--` templates, dotted and $-shaped names never refine here — the D77b assertion (a
        // `--` reaching the evaluator) stays the evaluator's, and dotted names are join reads.
        EvaluationContext ctx = ctx(Map.of());
        assertEquals(BindColumnLevel.ROW, BroadcastFold.bindColumnLevel("--OCCUR", ctx));
        // W3d (P-1): a dotted name no join serves is an absent column of the merged schema —
        // dataset-level (aDottedNameOfAnUnservedJoinIsDatasetAbsent); only the flag stays a row
        // read
        assertEquals(BindColumnLevel.DATASET_ABSENT, BroadcastFold.bindColumnLevel("DM.ARM", ctx));
        assertEquals(BindColumnLevel.ROW, BroadcastFold.bindColumnLevel("DM._matched_", ctx));
        assertEquals(BindColumnLevel.ROW, BroadcastFold.bindColumnLevel("$op", ctx));
        assertEquals(BindColumnLevel.ROW, BroadcastFold.bindColumnLevel("lowercase", ctx));
    }


    /**
     * N24 for a join that is not served ({@code PLAN-merged-evaluation-table} W3d, P-1 / the
     * absent-column doctrine): a dotted name whose qualifier has no lookup — declared and not
     * supplied, or not declared — reads the not-supplied marker on every row, so it is an absent
     * column of the merged schema and binds at dataset level like a bare absent one; a served
     * join's present column stays a row read, its absent column folds (W3a). RED before: the
     * unserved join kept the row-level reading (one finding per row). In a corpus rule a keyed
     * join's key declaration ({@code Requirements}) SKIPs the rule before this fold is reached; the
     * fold is the engine's backstop for the undeclared and the hand-built shapes.
     */
    @Test
    void aDottedNameOfAnUnservedJoinIsDatasetAbsent()
    {
        SyntheticDataTable ex = new SyntheticDataTable("EX", List.of("USUBJID", "EXDOSE"),
                new String[]
                {
                        "S1"
                }, 1);
        DatasetLookup lookup = DatasetLookup.build("EX", ex, List.of("USUBJID"));
        EvaluationContext joined = EvaluationContext.builder()
                .table(new SyntheticDataTable("AE", List.of("USUBJID", "AETERM"), new String[]
                {
                        "S1"
                }, 1)).joinedDatasets(Map.of("EX", lookup))
                .datasetResolver(n -> "EX".equals(n) ? ex : null).build();
        assertEquals(BindColumnLevel.ROW, BroadcastFold.bindColumnLevel("EX.EXDOSE", joined));
        assertEquals(BindColumnLevel.DATASET_ABSENT,
                BroadcastFold.bindColumnLevel("EX.NOPE", joined), "W3a: the join lacks it");
        assertEquals(BindColumnLevel.DATASET_ABSENT,
                BroadcastFold.bindColumnLevel("DM.AGE", joined),
                "W3d: no join serves DM — the marker on every row is a dataset-level constant");
        assertEquals(BindColumnLevel.ROW, BroadcastFold.bindColumnLevel("DM._matched_", joined),
                "the flag is a condition, never folded here");
    }


    /**
     * ⭐⭐ <b>Site S4 of {@code PLAN-unqualified-name-primary-only}, pinned — and it was pinned by
     * NOTHING until this test existed (found by that plan's own review, twice).</b>
     *
     * <p>
     * {@code bindColumnLevel} used to answer {@code ROW} for a bare name the primary lacks whenever
     * some JOIN carried a column of that name. That is the name's meaning varying with its
     * surroundings, in the one place that decides BINDING SCOPE rather than a value — so it changes
     * how many findings a rule emits, via {@code absentColumnLeafLevel} → {@code foldLeaf} →
     * {@code DATASET_WITH_ABSENT}, not merely what a leaf reads.
     * </p>
     *
     * <p>
     * ⛔ The other five cases in this class build NO join, so every one of them passes either way:
     * the change was invisible to this class in both directions. That is why a green suite said
     * nothing about S4, and why this case asserts the JOIN-BEARING context specifically.
     * </p>
     */
    @Test
    void aBareNameTheJoinCarriesIsStillDatasetAbsent()
    {
        SyntheticDataTable ex = new SyntheticDataTable("EX", List.of("USUBJID", "AEXX"),
                new String[]
                {
                        "S1"
                }, 1);
        DatasetLookup lookup = DatasetLookup.build("EX", ex, List.of("USUBJID"));
        EvaluationContext joined = EvaluationContext.builder()
                .table(new SyntheticDataTable("AE", List.of("USUBJID", "AETERM"), new String[]
                {
                        "S1"
                }, 1)).joinedDatasets(Map.of("EX", lookup))
                .datasetResolver(n -> "EX".equals(n) ? ex : null).build();

        assertEquals(BindColumnLevel.DATASET_ABSENT, BroadcastFold.bindColumnLevel("AEXX", joined),
                "a bare name the PRIMARY lacks is a dataset-level absent column even when a join"
                        + " carries a column of that name -- joined presence is not consulted");
        assertEquals(BindColumnLevel.ROW, BroadcastFold.bindColumnLevel("AETERM", joined),
                "and a name the primary DOES have is still a row read, join or no join");
    }

}
