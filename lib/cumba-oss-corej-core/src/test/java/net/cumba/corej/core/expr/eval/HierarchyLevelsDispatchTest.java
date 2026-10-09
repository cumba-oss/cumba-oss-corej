package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.exec.DictionaryFunctions;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W2 fold (R5 PB-L3): the levels {@code valid_external_dictionary_hierarchy} reads (W2 DI1,
 * derived at load from the column names) are bound into the function the <b>descriptor</b>
 * publishes ({@link DictionaryFunctions.LevelScoped}) — the compiler dispatches through it, never
 * around it to a hard-wired method. A function planted under the name is the one called, bound to
 * the levels its call's columns name.
 */
class HierarchyLevelsDispatchTest
{

    /** A planted descriptor function: records the levels it is bound to, answers every row. */
    private record Recording(List<String> seen) implements DictionaryFunctions.LevelScoped
    {

        @Override
        public Object apply(EvalRun run, List<Vector> args)
        {
            return atLevels(null, null).apply(run, args);
        }


        @Override
        public EvalFunction atLevels(@Nullable String childLevel, @Nullable String parentLevel)
        {
            return (run, _) ->
            {
                seen.add(childLevel + ">" + parentLevel);
                BitSet all = new BitSet();
                all.set(0, run.rowCount());
                return all;
            };
        }
    }

    @Test
    void theLevelsAreBoundIntoTheDescriptorsFunction()
    {
        FunctionDescriptor saved = FunctionRegistry.descriptor(DictionaryFunctions.HIERARCHY);
        assertNotNull(saved, "the hierarchy function is registered");
        List<String> seen = new ArrayList<>();
        FunctionRegistry.register(new FunctionDescriptor(DictionaryFunctions.HIERARCHY,
                saved.parameters(), FunctionKind.BOOLEAN, new Recording(seen)));
        try
        {
            IDataTable t = MockTable.of().name("QQ").col("QQHLT", "H", "I").col("QQSOC", "S", "T")
                    .build();
            BitSet answered = ExprCompiler
                    .compile(CheckExpressionParser.parse("valid_external_dictionary_hierarchy("
                            + "QQHLT, QQSOC, external_dictionary_type=\"meddra\")"))
                    .evaluate(EvalRun.fullRange(EvaluationContext.builder().table(t)
                            .evaluationDomain(Domain.ROW).build()));
            assertEquals(2, answered.cardinality(), "the planted function answered");
            assertEquals(List.of("HLT>SOC"), seen, "bound to the levels of its columns");
        }
        finally
        {
            FunctionRegistry.register(saved);
        }
    }
}
