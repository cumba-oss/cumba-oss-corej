package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.expr.eval.DatasetExpressionCache;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link ExpressionResultCache} (Phase 2 of
 * {@code plans/PLAN-dataset-expression-cache.md}): compute-once semantics, the null-result no-cache
 * rule, concurrency under the parallel rule fan-out, and the {@link EvaluationContext} field
 * wiring.
 */
class ExpressionResultCacheTest
{

    private static DatasetExpressionCache.Key key(IDataTable table, String column)
    {
        return DatasetExpressionCache.keyOf(table, new Expr.Ref(column, OperandKind.COLUMN), "AE");
    }


    @Test
    void computesOnceThenServesFromCache()
    {
        ExpressionResultCache cache = new ExpressionResultCache();
        var k = key(mock(IDataTable.class), "VAR1");
        AtomicInteger calls = new AtomicInteger();
        Object first = cache.computeIfAbsent(k, () ->
        {
            calls.incrementAndGet();
            return "RESULT";
        });
        Object second = cache.computeIfAbsent(k, () ->
        {
            calls.incrementAndGet();
            return "OTHER";
        });
        assertEquals("RESULT", first);
        assertSame(first, second);
        assertEquals(1, calls.get());
        assertEquals(1, cache.size());
    }


    @Test
    void nullResultIsNotCached()
    {
        ExpressionResultCache cache = new ExpressionResultCache();
        var k = key(mock(IDataTable.class), "VAR1");
        AtomicInteger calls = new AtomicInteger();
        assertNull(cache.computeIfAbsent(k, () ->
        {
            calls.incrementAndGet();
            return null;
        }));
        assertEquals(0, cache.size());
        // A second request recomputes (nothing was stored).
        assertNull(cache.computeIfAbsent(k, () ->
        {
            calls.incrementAndGet();
            return null;
        }));
        assertEquals(2, calls.get());
    }


    @Test
    void distinctKeysAreStoredSeparately()
    {
        ExpressionResultCache cache = new ExpressionResultCache();
        IDataTable table = mock(IDataTable.class);
        cache.computeIfAbsent(key(table, "VAR1"), () -> "a");
        cache.computeIfAbsent(key(table, "VAR2"), () -> "b");
        assertEquals(2, cache.size());
    }


    @Test
    void concurrentComputeConvergesOnOneStoredValue() throws Exception
    {
        ExpressionResultCache cache = new ExpressionResultCache();
        var k = key(mock(IDataTable.class), "VAR1");
        AtomicInteger calls = new AtomicInteger();
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try
        {
            List<Callable<Object>> tasks = new ArrayList<>();
            for (int i = 0; i < threads; i++)
            {
                tasks.add(() -> cache.computeIfAbsent(k, () ->
                {
                    calls.incrementAndGet();
                    return "V";
                }));
            }
            List<Future<Object>> results = pool.invokeAll(tasks);
            for (Future<Object> f : results)
            {
                assertEquals("V", f.get());
            }
        }
        finally
        {
            pool.shutdownNow();
        }
        // Deliberately non-locking (unified-callable-surface §3.2 review): suppliers may nest,
        // so the mapping runs OUTSIDE the map lock — under contention it may execute more than
        // once, but every caller observes the single first-stored value and the map holds one
        // entry. (The old "exactly once" pin belonged to the locking implementation, whose
        // in-lock supplier was a forbidden recursive update once value subtrees nested.)
        assertTrue(calls.get() >= 1 && calls.get() <= threads,
                "the mapping ran between 1 and N times, got " + calls.get());
        assertEquals(1, cache.size(), "exactly one entry stored for the contended key");
        assertEquals(1, cache.size());
    }


    @Test
    void evaluationContextCarriesTheCache()
    {
        ExpressionResultCache cache = new ExpressionResultCache();
        IDataTable table = mock(IDataTable.class);
        EvaluationContext withCache = EvaluationContext.builder().table(table).exprCache(cache)
                .build();
        assertSame(cache, withCache.getExprCache());
        // Default (mirrors libraryProvider): null when not set.
        EvaluationContext withoutCache = EvaluationContext.builder().table(table).build();
        assertNull(withoutCache.getExprCache());
        assertTrue(withCache.getExprCache() == cache);
    }

}
