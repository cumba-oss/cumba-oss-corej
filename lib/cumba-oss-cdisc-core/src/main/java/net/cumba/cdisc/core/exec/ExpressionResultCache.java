package net.cumba.cdisc.core.exec;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.cumba.cdisc.core.expr.eval.DatasetExpressionCache;
import org.jspecify.annotations.Nullable;

/**
 * Per-dataset, cross-rule cache of pure-expression-leaf results
 * ({@code plans/PLAN-dataset-expression-cache.md}). Mirrors {@link JoinCache}'s lifecycle: created
 * once per dataset validation and shared (thread-safe) across every rule executed for that dataset,
 * which under {@code ruleThreads > 1} run on parallel worker threads against the <em>same</em>
 * {@link net.cumba.datatable.IDataTable} instance.
 *
 * <p>
 * Scope is <b>strictly per-dataset</b>: a {@code Key} is keyed on the table instance identity, so a
 * cache must never be shared across datasets (unlike {@code JoinCache}'s cross-dataset
 * {@code SharedIndexCache}, there is no such tier here). The stored value is a
 * {@code net.cumba.cdisc.core.expr.eval.Vector} or a {@link java.util.BitSet}; both are treated as
 * read-only — a {@code BitSet} consumer must clone before any in-place mutation (the {@code Not} /
 * {@code CheckEvaluator} flip paths). Wiring the cache into the compiler is a later phase; this
 * class is the storage + lifecycle.
 * </p>
 */
public final class ExpressionResultCache
{

    /**
     * The entries. {@link ConcurrentHashMap} so the parallel rule fan-out shares results without
     * external synchronisation. Values are never {@code null} (a {@code computeIfAbsent} mapping
     * that yields {@code null} stores nothing — the leaf simply isn't cached).
     */
    private final ConcurrentHashMap<DatasetExpressionCache.Key, Object> entries = new ConcurrentHashMap<>();

    /**
     * Returns the cached result for {@code key}, computing and storing it on first request. A
     * {@code compute} that yields {@code null} is <b>not</b> cached (and {@code null} is returned)
     * — so an unresolvable leaf never poisons the map.
     *
     * <p>
     * <b>Deliberately non-locking</b> (plan unified-callable-surface §3.2 review): the supplier
     * runs <em>outside</em> any map lock — get, compute, then {@code putIfAbsent} — because
     * suppliers nest (a cached boolean subtree evaluates its cached value subtrees), and running a
     * nesting supplier inside {@code ConcurrentHashMap.computeIfAbsent} is a forbidden recursive
     * update. The trade-off: under contention two threads may compute the same pure expression
     * concurrently; both results are identical (pure inputs), the first store wins, and every
     * caller sees the winning entry — duplicated work, never divergent results. The
     * {@code putIfAbsent} store is also the safe-publication point for lazily-built results
     * materialised before caching.
     * </p>
     *
     * @param key
     *            the cache key (table identity + canonical expression + domain prefix)
     * @param compute
     *            computes the result on a miss; may run more than once across threads
     * @return the cached or freshly-computed result (a {@code Vector} or {@code BitSet}), or
     *         {@code null} when {@code compute} yielded {@code null}
     */
    public @Nullable Object computeIfAbsent(DatasetExpressionCache.Key key,
            Supplier<@Nullable Object> compute)
    {
        Object present = entries.get(key);
        if (present != null)
        {
            return present;
        }
        Object computed = compute.get();
        if (computed == null)
        {
            return null;
        }
        Object raced = entries.putIfAbsent(key, computed);
        return raced != null ? raced : computed;
    }


    /** The number of distinct cached leaves — for the per-dataset peak-size sanity log (§6 Q5). */
    public int size()
    {
        return entries.size();
    }

}
