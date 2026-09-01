package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.ExpressionResultCache;
import net.cumba.cdisc.core.exec.JoinLookup;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 of {@code plans/PLAN-dataset-expression-cache.md}: the {@link ExprCompiler} caching
 * decorator. Verifies that a pure boolean leaf is cached, that the stored {@link BitSet} is cloned
 * on read (so an enclosing in-place flip cannot corrupt it), that the §3.6 gate and the
 * {@code null}-cache fast path disable caching, and — the correctness gate — that a cached run and
 * a cache-disabled run produce <b>identical</b> results.
 */
class ExprCompilerCacheTest
{

    private static Expr ref(String n)
    {
        return new Expr.Ref(n, OperandKind.COLUMN);
    }


    private static Expr call(String n, Expr... a)
    {
        return new Expr.Call(n, List.of(a), Map.of());
    }


    private static EvaluationContext ctx(IDataTable table, @Nullable ExpressionResultCache cache,
            Domain domain, Map<String, JoinLookup> joins)
    {
        return EvaluationContext.builder().table(table).exprCache(cache).evaluationDomain(domain)
                .joinedDatasets(joins).build();
    }


    private static BitSet eval(Expr e, EvaluationContext context)
    {
        return ExprCompiler.compile(e).evaluate(EvalRun.fullRange(context));
    }


    @Test
    void purePredicateIsCachedAndClonedOnRead()
    {
        IDataTable table = MockTable.of().col("VAR1", "a", "", "c").build();
        ExpressionResultCache cache = new ExpressionResultCache();
        Expr leaf = call("empty", ref("VAR1"));

        BitSet first = eval(leaf, ctx(table, cache, Domain.ROW, Map.of()));
        assertEquals(1, cache.size(), "the pure leaf should be cached once");

        // Corrupt the returned BitSet — a clone-on-read cache must shrug this off.
        BitSet expected = (BitSet) first.clone();
        first.flip(0, 3);

        BitSet second = eval(leaf, ctx(table, cache, Domain.ROW, Map.of()));
        assertEquals(expected, second, "the cached entry must not be corrupted by a consumer");
        assertEquals(1, cache.size());
    }


    @Test
    void cachedRunEqualsCacheDisabledRun()
    {
        IDataTable table = MockTable.of().col("VAR1", "a", "", "c").colDouble("N", 1.0, 5.0, 9.0)
                .build();
        List<Expr> leaves = List.of(call("empty", ref("VAR1")), call("non_empty", ref("VAR1")),
                new Expr.Binary(Expr.BinOp.GT, ref("N"), new Expr.Lit(Expr.LitKind.NUMBER, 4.0)),
                new Expr.And(List.of(call("non_empty", ref("VAR1")),
                        new Expr.Binary(Expr.BinOp.GT, ref("N"),
                                new Expr.Lit(Expr.LitKind.NUMBER, 4.0)))),
                new Expr.Not(call("empty", ref("VAR1"))));
        for (Expr leaf : leaves)
        {
            BitSet cached = eval(leaf,
                    ctx(table, new ExpressionResultCache(), Domain.ROW, Map.of()));
            BitSet uncached = eval(leaf, ctx(table, null, Domain.ROW, Map.of()));
            assertEquals(uncached, cached, "cached vs uncached must match for " + leaf);
        }
    }


    @Test
    void gateDeclinesAVariableCursorDomain()
    {
        IDataTable table = MockTable.of().col("VAR1", "a", "", "c").build();
        ExpressionResultCache cache = new ExpressionResultCache();
        Expr leaf = call("empty", ref("VAR1"));
        BitSet result = eval(leaf, ctx(table, cache, Domain.VARIABLE, Map.of()));
        assertEquals(0, cache.size(), "a variable-cursor domain must not populate the cache");
        assertEquals(eval(leaf, ctx(table, null, Domain.VARIABLE, Map.of())), result);
    }


    @Test
    void gateDeclinesWhenJoinsPresent()
    {
        IDataTable table = MockTable.of().col("VAR1", "a", "", "c").build();
        ExpressionResultCache cache = new ExpressionResultCache();
        BitSet result = eval(call("empty", ref("VAR1")),
                ctx(table, cache, Domain.ROW, Map.of("DM", mock(JoinLookup.class))));
        assertEquals(0, cache.size(), "a join-bearing rule must not populate the cache");
        assertEquals(
                eval(call("empty", ref("VAR1")),
                        ctx(table, null, Domain.ROW, Map.of("DM", mock(JoinLookup.class)))),
                result);
    }


    @Test
    void impureLeafIsNotCached()
    {
        IDataTable table = MockTable.of().col("VAR1", "a", "", "c").build();
        ExpressionResultCache cache = new ExpressionResultCache();
        // var_exists(...) reads the table schema -> not in the pure allow-list -> never cached.
        eval(call("var_exists", ref("VAR1")), ctx(table, cache, Domain.ROW, Map.of()));
        assertEquals(0, cache.size());
    }


    @Test
    void cacheIsReusedAcrossSeparatelyCompiledPrograms()
    {
        // Mirrors two different rules authoring the same pure leaf against one dataset: the second
        // program must hit the first's entry and return a correct, independent (cloned) BitSet.
        IDataTable table = MockTable.of().col("VAR1", "a", "", "c").build();
        ExpressionResultCache cache = new ExpressionResultCache();
        BitSet a = ExprCompiler.compile(call("empty", ref("VAR1")))
                .evaluate(EvalRun.fullRange(ctx(table, cache, Domain.ROW, Map.of())));
        BitSet b = ExprCompiler.compile(call("empty", ref("VAR1")))
                .evaluate(EvalRun.fullRange(ctx(table, cache, Domain.ROW, Map.of())));
        assertEquals(1, cache.size(), "the two programs must share one cache entry");
        assertEquals(a, b);
        a.flip(0, 3); // corrupt the first result -> the second (already returned) is independent
        assertEquals(eval(call("empty", ref("VAR1")), ctx(table, null, Domain.ROW, Map.of())), b);
    }


    @Test
    void nullCacheIsANoOp()
    {
        IDataTable table = MockTable.of().col("VAR1", "a", "", "c").build();
        // Must not throw; behaves exactly like the uncached engine.
        BitSet result = eval(call("empty", ref("VAR1")), ctx(table, null, Domain.ROW, Map.of()));
        assertEquals(eval(call("empty", ref("VAR1")), ctx(table, null, Domain.ROW, Map.of())),
                result);
    }

}
