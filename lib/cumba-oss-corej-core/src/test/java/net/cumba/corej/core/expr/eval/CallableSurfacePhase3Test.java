package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.ExpressionResultCache;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 of {@code plans/done/PLAN-unified-callable-surface.md} — the two features carried into
 * the unified callable ABI: §3.1 kwargs reach the function (raw {@link Expr} nodes, dropped before
 * for functions), and §3.2 pure VALUE subtrees memoise once per dataset run through
 * {@link net.cumba.corej.core.exec.ExpressionResultCache} (the {@code LazyValue} single-execution
 * replacement), shared across rules over the same table.
 */
class CallableSurfacePhase3Test
{

    private static Expr ref(String n)
    {
        return new Expr.Ref(n, OperandKind.COLUMN);
    }


    private static EvaluationContext ctx(IDataTable table, ExpressionResultCache cache)
    {
        return EvaluationContext.builder().table(table).exprCache(cache).joinedDatasets(Map.of())
                .evaluationDomain(Domain.ROW).build();
    }


    private static BitSet eval(Expr e, EvaluationContext context)
    {
        return ExprCompiler.compile(e).evaluate(EvalRun.fullRange(context));
    }


    @Test
    void kwargsReachABooleanFunction()
    {
        AtomicReference<Map<String, Expr>> seen = new AtomicReference<>();
        EvalFunction fn = new EvalFunction()
        {

            @Override
            public Object apply(EvalRun run, List<Vector> args)
            {
                return apply(run, args, Map.of());
            }


            @Override
            public Object apply(EvalRun run, List<Vector> args, Map<String, Expr> kwargs)
            {
                seen.set(kwargs);
                return new BitSet();
            }
        };
        FunctionRegistry
                .register(new FunctionDescriptor("kwarg_probe", 1, FunctionKind.BOOLEAN, fn));
        try
        {
            IDataTable table = MockTable.of().col("VAR1", "a", "b").build();
            Expr mark = new Expr.Lit(Expr.LitKind.STRING, "M1");
            Expr call = new Expr.Call("kwarg_probe", List.of(ref("VAR1")), Map.of("mark", mark));
            eval(call, ctx(table, new ExpressionResultCache()));
            assertNotNull(seen.get(), "the kwargs-aware apply form must be invoked");
            assertEquals(mark, seen.get().get("mark"),
                    "kwargs arrive as the call's raw Expr nodes");
        }
        finally
        {
            FunctionRegistry.unregister("kwarg_probe", 1);
        }
    }


    @Test
    void plainFunctionsIgnoreKwargsViaTheDefaultForm()
    {
        AtomicInteger positional = new AtomicInteger();
        EvalFunction fn = (_, _) ->
        {
            positional.incrementAndGet();
            return new BitSet();
        };
        FunctionRegistry
                .register(new FunctionDescriptor("kwarg_blind", 1, FunctionKind.BOOLEAN, fn));
        try
        {
            IDataTable table = MockTable.of().col("VAR1", "a", "b").build();
            Expr call = new Expr.Call("kwarg_blind", List.of(ref("VAR1")),
                    Map.of("mark", new Expr.Lit(Expr.LitKind.STRING, "M1")));
            eval(call, ctx(table, new ExpressionResultCache()));
            assertEquals(1, positional.get(),
                    "a lambda function runs through the positional default unchanged");
        }
        finally
        {
            FunctionRegistry.unregister("kwarg_blind", 1);
        }
    }


    @Test
    void pureValueSubtreeIsSharedAcrossRulesOverTheSameTable()
    {
        IDataTable table = MockTable.of().col("VAR1", "a", "B", "c").build();
        ExpressionResultCache cache = new ExpressionResultCache();

        // Rule 1: upper(VAR1) == "A". Both the boolean root and the pure value subtree
        // upper(VAR1) are cache-keyed.
        Expr upper = new Expr.Call("upper", List.of(ref("VAR1")), Map.of());
        Expr rule1 = new Expr.Binary(Expr.BinOp.EQ, upper, new Expr.Lit(Expr.LitKind.STRING, "A"));
        BitSet first = eval(rule1, ctx(table, cache));
        assertEquals(1, first.cardinality(), "row 0 ('a') violates upper(VAR1)=='A'");
        int afterRule1 = cache.size();
        assertTrue(afterRule1 >= 2,
                "boolean root AND the pure value subtree are both cached, got " + afterRule1);

        // Rule 2: a DIFFERENT boolean root sharing the same value subtree — the upper(VAR1)
        // entry is reused, so exactly one new (boolean) entry appears. This is the LazyValue
        // single-execution replacement across rules.
        Expr rule2 = new Expr.Binary(Expr.BinOp.EQ,
                new Expr.Call("upper", List.of(ref("VAR1")), Map.of()),
                new Expr.Lit(Expr.LitKind.STRING, "B"));
        BitSet second = eval(rule2, ctx(table, cache));
        assertEquals(1, second.cardinality(), "row 1 ('B') violates upper(VAR1)=='B'");
        assertEquals(afterRule1 + 1, cache.size(),
                "only the new boolean root is added - the value subtree entry is shared");
    }


    @Test
    void cachedAndUncachedValueRunsAgree()
    {
        IDataTable table = MockTable.of().col("VAR1", "x", "", "Z").build();
        Expr rule = new Expr.Binary(Expr.BinOp.EQ,
                new Expr.Call("upper", List.of(ref("VAR1")), Map.of()),
                new Expr.Lit(Expr.LitKind.STRING, "X"));
        BitSet cached = eval(rule, ctx(table, new ExpressionResultCache()));
        BitSet uncached = ExprCompiler.compile(rule).evaluate(EvalRun.fullRange(
                EvaluationContext.builder().table(table).joinedDatasets(Map.of()).build()));
        assertEquals(uncached, cached, "the memoised path is behaviour-identical");
    }

}
