package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.DatasetLookup;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.GroupedResult;
import net.cumba.corej.core.expr.eval.BroadcastFold.BindColumnLevel;
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
        // A per-row GroupedResult is not a scalar fact; the name then classifies by presence.
        assertEquals(BindColumnLevel.DATASET_ABSENT, BroadcastFold.bindColumnLevel("ZZFOO",
                ctx(Map.of("ZZFOO", new GroupedResult(java.util.List.of("USUBJID"), Map.of())))));
    }


    @Test
    void nonFoldableNamesStayRowReads()
    {
        // `--` templates, dotted and $-shaped names never refine here — the D77b assertion (a
        // `--` reaching the evaluator) stays the evaluator's, and dotted names are join reads.
        EvaluationContext ctx = ctx(Map.of());
        assertEquals(BindColumnLevel.ROW, BroadcastFold.bindColumnLevel("--OCCUR", ctx));
        assertEquals(BindColumnLevel.ROW, BroadcastFold.bindColumnLevel("DM.ARM", ctx));
        assertEquals(BindColumnLevel.ROW, BroadcastFold.bindColumnLevel("$op", ctx));
        assertEquals(BindColumnLevel.ROW, BroadcastFold.bindColumnLevel("lowercase", ctx));
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
