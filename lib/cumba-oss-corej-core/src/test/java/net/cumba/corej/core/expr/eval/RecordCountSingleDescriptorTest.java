package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.OperationExecutor;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.convert.OperationDescriptors;
import net.cumba.corej.core.expr.convert.OperationExpressionParser;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.OperationType;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Phase 6b (D91e) — {@code record_count}'s namespace collision is closed: ONE descriptor
 * ({@link OperationDescriptors}), with the registry's parameterless entry as its bare-form fast
 * path. The two pins here are what make the fast path a <em>plan choice</em> rather than a
 * <em>meaning choice</em>:
 *
 * <ol>
 * <li>the bare registry implementation and the unfiltered/ungrouped executor implementation return
 * the same value on the same table (both read {@code table.getRowCount()}), so adding a keyword
 * argument can change performance, never semantics; and</li>
 * <li>the registry/operation name overlap is exactly {@code record_count} +
 * {@code dictionary_available} — a third silent overlap cannot appear, and an unknown keyword on
 * either now errors through the descriptor gate instead of selecting an implementation.</li>
 * </ol>
 */
class RecordCountSingleDescriptorTest
{

    @Test
    void bareBuiltinAndUnfilteredExecutorAgreeOnTheRowCount()
    {
        IDataTable t = MockTable.of().col("USUBJID", "S1", "S2", "S3").build();
        EvaluationContext ctx = EvaluationContext.builder().table(t).build();

        // the registry fast path (what a bare record_count() compiles to)
        Vector fast = (Vector) FunctionRegistry.resolve("record_count")
                .apply(new EvalRun(ctx, 0, 3), List.of());

        // the executor path (what any parameter-binding call compiles to), with no parameter bound
        Operation bare = OperationExpressionParser
                .fromCall(new Expr.Call("record_count", List.of(), Map.of()), "$n");
        Object executed = OperationExecutor.executeOne(bare, t, name -> null, null, Map.of());

        assertEquals(3L, ((Number) fast.value(0).resolved()).longValue());
        assertEquals(3L, ((Number) executed).longValue(),
                "record_count() and the unfiltered/ungrouped operation are the same function");
    }


    /** The one descriptor: parameters come from {@link OperationDescriptors}, nowhere else. */
    @Test
    void recordCountHasOneParameterList()
    {
        FunctionDescriptor operation = OperationDescriptors.of(OperationType.RECORD_COUNT);
        assertTrue(operation.parameter("filter") != null && operation.parameter("group") != null
                && operation.parameter("domain") != null && operation.parameter("regex") != null,
                "the operation surface");
        FunctionDescriptor fastPath = FunctionRegistry.descriptor("record_count");
        assertEquals(0, fastPath.parameters().size(),
                "the registry entry is the parameterless bare-form fast path, not a second"
                        + " parameter surface");
    }


    /**
     * The registry/operation overlap is closed and named: {@code record_count} (this test's
     * subject) and {@code dictionary_available} (the §9.C gate builtin, whose arity-1 form
     * {@code dictionary_available("X")} and operation form
     * {@code dictionary_available(external_dictionary_type="X")} both answer
     * {@code provider.isAvailable(X)}). Anything else appearing here is a NEW silent collision —
     * extend this list only with the equivalence argument that justifies it.
     */
    @Test
    void registryOperationNameOverlapIsExactlyTheTwoKnownFastPaths()
    {
        Set<String> overlap = new TreeSet<>();
        for (OperationType type : OperationType.values())
        {
            if (FunctionRegistry.isRegistered(type.getJsonValue()))
            {
                overlap.add(type.getJsonValue());
            }
        }
        assertEquals(new TreeSet<>(Set.of("record_count", "dictionary_available")), overlap);
    }

}
