package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.GroupedResult;
import net.cumba.corej.core.expr.eval.BroadcastFold.BindColumnLevel;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@link BroadcastFold#bindColumnLevel} — the phase-5 bind-time classification of a bare
 * column-reference name: context variables first (resolution order — the Fix #10 shape), then
 * foldability, then primary-table and joined presence (mirroring the missing-column fold's
 * eligibility, D39a).
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

}
