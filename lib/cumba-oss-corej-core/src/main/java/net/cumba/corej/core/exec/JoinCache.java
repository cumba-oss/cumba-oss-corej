package net.cumba.corej.core.exec;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;

import net.cumba.corej.core.exec.DatasetLookup.SharedJoinedIndex;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * Caches cross-dataset join structures to avoid redundant index construction when multiple rules
 * join the same datasets with the same keys.
 * <p>
 * Two levels of caching:
 * <ol>
 * <li><b>Lookup cache</b> — caches complete {@link DatasetLookup} instances per (datasetName, keys)
 * combination. A single lookup contains both the joined-side index and the primary-to-joined row
 * map. Since the row map is built lazily on first use for a given primary table and then cached,
 * reusing the same lookup across rules for the same primary table avoids all repeated work. Backed
 * by a {@link ConcurrentHashMap} so multiple rule threads validating the same primary dataset can
 * share lookups safely.</li>
 * <li><b>Index cache</b> — caches the joined-side index ({@link DatasetLookup.SharedJoinedIndex}: a
 * primitive {@code HashLookup} keyed by a 32-bit hash of the join columns) independently of the
 * primary table. This index is immutable once built and is safe to share across threads. When
 * multiple primary datasets all join to the same reference dataset (e.g., DM by USUBJID), the index
 * is built once and shared, avoiding repeated scans of the reference dataset.</li>
 * </ol>
 *
 * <h2>Typical lifecycle</h2>
 *
 * <pre>{@code
 * // Created once per study validation, shared across dataset threads:
 * JoinCache.SharedIndexCache sharedIndex = new JoinCache.SharedIndexCache();
 *
 * // Created once per dataset validation thread:
 * JoinCache cache = new JoinCache(sharedIndex);
 *
 * // Passed to each RuleRunner.execute() call for the same dataset:
 * RuleRunner.execute(rule, table, resolver, prefix, provider, cache);
 * }</pre>
 */
public final class JoinCache
{

    /**
     * Thread-safe cache for joined-side indexes. Shared across dataset validation threads so that a
     * reference dataset (e.g., DM) is indexed only once for the entire validation run.
     */
    public static final class SharedIndexCache
    {

        private final ConcurrentHashMap<String, SharedJoinedIndex> cache = new ConcurrentHashMap<>();

        /**
         * Child-match indexes cached per {@code (parent identity, IDVAR column)} pair.
         * {@link ChildMatchIndex#build} may return {@code null} when the parent lacks the named
         * IDVAR or {@code USUBJID} column; {@code null} values are intentionally cached so repeated
         * {@code preMerge} calls on the same un-buildable combination do not re-scan the parent.
         * The map therefore stores an {@code Optional}-equivalent wrapper.
         */
        private final ConcurrentHashMap<String, ChildMatchIndexHolder> childMatchCache = new ConcurrentHashMap<>();

        /**
         * Returns a cached index or builds and caches one.
         *
         * @param dataset
         *            the joined dataset
         * @param keyColumns
         *            the join key columns
         * @return the joined-side index, never {@code null}
         */
        SharedJoinedIndex getOrBuild(IDataTable dataset, List<String> keyColumns)
        {
            String cacheKey = indexCacheKey(dataset, keyColumns);
            return cache.computeIfAbsent(cacheKey,
                    _ -> DatasetLookup.buildSharedIndex(dataset, keyColumns));
        }


        /**
         * Returns the cached child-match index for {@code (parent, standardKeys, idvarCol)} or
         * builds and caches one. Identity-hash keying is safe within a validation run for the same
         * reason as {@link #getOrBuild(IDataTable, List)} — the production resolver hands out a
         * stable {@code IDataTable} instance per domain. The standard-key list is part of the cache
         * key so two rules declaring different keys do not share an index.
         * <p>
         * May return {@code null} when the parent lacks the named IDVAR column or a declared
         * standard-key column; the {@code null} is itself cached so the failed build is not retried
         * per rule.
         * </p>
         */
        @Nullable
        ChildMatchIndex getOrBuildChildMatchIndex(IDataTable aParent, List<String> aStandardKeyCols,
                String aIdvarCol)
        {
            String key = "childmatch|" + System.identityHashCode(aParent) + "|"
                    + String.join(",", aStandardKeyCols) + "|" + aIdvarCol;
            return childMatchCache
                    .computeIfAbsent(key,
                            _ -> new ChildMatchIndexHolder(
                                    ChildMatchIndex.build(aParent, aStandardKeyCols, aIdvarCol)))
                    .index();
        }


        private static String indexCacheKey(IDataTable dataset, List<String> keyColumns)
        {
            // Use identity hash of the table object + key columns as cache key.
            // Identity hash is sufficient because IDataTable instances are stable
            // within a validation run (same object reference = same data).
            return System.identityHashCode(dataset) + "|" + String.join(",", keyColumns);
        }
    }


    /**
     * Holder that permits caching a {@code null} {@link ChildMatchIndex} (failed build) in a
     * {@link ConcurrentHashMap}, which itself forbids {@code null} values.
     */
    private record ChildMatchIndexHolder(@Nullable ChildMatchIndex index)
    {
    }

    /**
     * Per-dataset lookup cache: cacheKey → JoinLookup. {@link ConcurrentHashMap} so concurrent rule
     * threads validating the same primary dataset can share entries without external
     * synchronisation.
     */
    private final ConcurrentHashMap<String, JoinLookup> lookupCache = new ConcurrentHashMap<>();

    /** Shared index cache (may be null if no cross-dataset sharing is desired). */
    private final @Nullable SharedIndexCache sharedIndex;

    /**
     * Creates a JoinCache with a shared index cache for cross-dataset index reuse.
     *
     * @param sharedIndex
     *            the shared index cache, or {@code null} to disable cross-dataset sharing
     */
    public JoinCache(@Nullable SharedIndexCache sharedIndex)
    {
        this.sharedIndex = sharedIndex;
    }


    /**
     * Creates a JoinCache without cross-dataset index sharing.
     */
    public JoinCache()
    {
        this(null);
    }


    /**
     * Returns the shared index cache, or {@code null} when this {@code JoinCache} was constructed
     * without one. Exposed so {@link ChildMatchPreMerger} can cache {@link ChildMatchIndex}
     * instances cross-rule.
     */
    @Nullable
    SharedIndexCache getSharedIndexCache()
    {
        return sharedIndex;
    }


    /**
     * Returns a cached {@link DatasetLookup} for the given dataset and keys, or builds and caches
     * one.
     *
     * @param dsName
     *            the joined dataset name
     * @param dataset
     *            the joined dataset
     * @param keys
     *            the join key columns
     * @return the lookup, or {@code null} if the dataset is null
     */
    @Nullable
    DatasetLookup getOrBuildLookup(String dsName, @Nullable IDataTable dataset, List<String> keys)
    {
        if (dataset == null)
        {
            return null;
        }
        String key = lookupCacheKey(dsName, keys);
        // computeIfAbsent guarantees the build runs once per key even under concurrent access from
        // multiple rule threads. The mapping function may briefly block other threads asking for
        // the same key — acceptable since DatasetLookup construction is fast (it defers the
        // multi-MB row map to ensureJoinMap, called outside this lock).
        JoinLookup existing = lookupCache.computeIfAbsent(key, _ ->
        {
            SharedJoinedIndex prebuiltIndex = sharedIndex != null
                    ? sharedIndex.getOrBuild(dataset, keys)
                    : null;
            return prebuiltIndex != null ? DatasetLookup.build(dsName, dataset, keys, prebuiltIndex)
                    : DatasetLookup.build(dsName, dataset, keys);
        });
        return existing instanceof DatasetLookup dl ? dl : null;
    }


    /**
     * Caches a JoinLookup under the given name (used for RELREC and other non-key-based lookups).
     */
    void put(String name, JoinLookup lookup)
    {
        lookupCache.put(name, lookup);
    }


    /**
     * Returns a previously cached JoinLookup by name.
     */
    @Nullable
    JoinLookup get(String name)
    {
        return lookupCache.get(name);
    }


    private static String lookupCacheKey(String dsName, List<String> keys)
    {
        return dsName + "|" + String.join(",", keys);
    }

}
