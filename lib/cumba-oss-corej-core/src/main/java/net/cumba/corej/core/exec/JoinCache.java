package net.cumba.corej.core.exec;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

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
     *
     * <p>
     * ⭐⭐ <b>Every table-keyed cache here is identity-exact</b>
     * ({@code PLAN-identity-safe-join-caches} D1/D2): each is an {@link IdentityWeakCache}, which
     * compares the table by reference identity and holds it weakly, under a sub-key naming every
     * other input of the build. Before, three of them keyed on {@code System.identityHashCode}
     * alone — at most 31 bits, not unique — and validated nothing on a hit, so a collision handed
     * one table another table's index: a silent no-match (and false absence findings), a false
     * match, or an index out of bounds.
     * </p>
     *
     * <ul>
     * <li><b>Lookup indexes</b> ({@link #getOrBuild}) — sub-key: the key column names in order.
     * {@code DatasetLookup.buildSharedIndex} reads only the table and those names.</li>
     * <li><b>Child-match indexes</b> ({@link #getOrBuildChildMatchIndex}) — sub-key: the standard
     * keys in order and the IDVAR column. A {@code null} build (the parent lacks a column) is
     * cached too, through {@link ChildMatchIndexHolder}.</li>
     * <li><b>Key-match indexes</b> ({@link #getOrBuildKeyMatchIndex}) — sub-key:
     * {@link KeyMatchIndex.SpecKey}.</li>
     * <li><b>Wildcard column sets</b> ({@link #wildcardColumns}) — sub-key: regex and flags.</li>
     * </ul>
     *
     * <p>
     * Each lives exactly as long as its table ({@code PLAN-keymatch-shared-join-index} Q3 (d)):
     * tables are soft-memoised and may reload as a new instance mid-run, which gets fresh entries
     * and never a stale hit. ⚠ A cached value must never hold its own table strongly, or it pins it
     * — which is why {@code ChildMatchIndex} no longer carries its parent (D3). Each cache is its
     * own map, so a long {@code computeIfAbsent} build never holds a bin lock over another cache's
     * entries.
     * </p>
     */
    public static final class SharedIndexCache
    {

        /** Every input of {@code ChildMatchIndex.build} besides the parent table. */
        record ChildKey(List<String> standardKeys, String idvarCol)
        {

            ChildKey
            {
                standardKeys = List.copyOf(standardKeys);
            }
        }

        private final IdentityWeakCache<List<String>, SharedJoinedIndex> lookupIndexes;

        private final IdentityWeakCache<ChildKey, ChildMatchIndexHolder> childMatchIndexes;

        private final IdentityWeakCache<KeyMatchIndex.SpecKey, KeyMatchIndex> keyMatchIndexes;

        private final WildcardForeignColumnCache wildcardColumns;

        /** A cache for one validation run. */
        public SharedIndexCache()
        {
            this(System::identityHashCode);
        }


        /**
         * A cache whose tables are bucketed by {@code aIdentityHash} — ⭐ the test seam that proves
         * every hit is exact: a test forces every table into ONE bucket.
         */
        SharedIndexCache(java.util.function.ToIntFunction<Object> aIdentityHash)
        {
            lookupIndexes = new IdentityWeakCache<>(aIdentityHash);
            childMatchIndexes = new IdentityWeakCache<>(aIdentityHash);
            keyMatchIndexes = new IdentityWeakCache<>(aIdentityHash);
            wildcardColumns = new WildcardForeignColumnCache(
                    new IdentityWeakCache<>(aIdentityHash));
        }


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
            return lookupIndexes.getOrBuild(dataset, List.copyOf(keyColumns),
                    keys -> DatasetLookup.buildSharedIndex(dataset, keys));
        }


        /**
         * Returns the cached child-match index for {@code (parent, standardKeys, idvarCol)} or
         * builds and caches one. The standard-key list is part of the key so two rules declaring
         * different keys do not share an index.
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
            return childMatchIndexes.getOrBuild(aParent, new ChildKey(aStandardKeyCols, aIdvarCol),
                    key -> new ChildMatchIndexHolder(
                            ChildMatchIndex.build(aParent, key.standardKeys(), key.idvarCol())))
                    .index();
        }


        /**
         * Returns the key-match index for {@code aChild} under {@code aSpec}, building it with
         * {@code aBuild} on first use. Built once per (table instance, spec) even under a
         * concurrent cold start, and immutable afterwards.
         *
         * @param aChild
         *            the joined table, <b>unfiltered</b> — a {@code Filter} is applied per rule as
         *            a mask over the shared index, never baked into it.
         * @param aSpec
         *            the child-side key shape.
         * @param aBuild
         *            builds the index when it is not cached; must not touch this cache.
         * @return the index, never {@code null}.
         */
        KeyMatchIndex getOrBuildKeyMatchIndex(IDataTable aChild, KeyMatchIndex.SpecKey aSpec,
                Supplier<KeyMatchIndex> aBuild)
        {
            return keyMatchIndexes.getOrBuild(aChild, aSpec, _ -> aBuild.get());
        }


        /** Returns the run's wildcard column cache, shared by every rule of the run. */
        WildcardForeignColumnCache wildcardColumns()
        {
            return wildcardColumns;
        }


        /** Returns how many key-match indexes this cache has built so far. */
        int keyMatchIndexBuildCount()
        {
            return keyMatchIndexes.buildCount();
        }


        /** Returns how many child tables currently hold key-match indexes. */
        int keyMatchIndexedTableCount()
        {
            return keyMatchIndexes.tableCount();
        }


        /** Returns how many lookup indexes this cache has built so far. */
        int lookupIndexBuildCount()
        {
            return lookupIndexes.buildCount();
        }


        /** Returns how many live tables currently hold lookup indexes. */
        int lookupIndexedTableCount()
        {
            return lookupIndexes.tableCount();
        }


        /** Returns how many child-match indexes (including cached failed builds) are cached. */
        int childMatchIndexCount()
        {
            return childMatchIndexes.valueCount();
        }


        /** Returns how many child-match indexes this cache has built so far. */
        int childMatchIndexBuildCount()
        {
            return childMatchIndexes.buildCount();
        }


        /** Returns how many live parent tables currently hold child-match indexes. */
        int childMatchIndexedTableCount()
        {
            return childMatchIndexes.tableCount();
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
        // compute guarantees the build runs once per key even under concurrent access from
        // multiple rule threads. The mapping function may briefly block other threads asking for
        // the same key — acceptable since DatasetLookup construction is fast (it defers the
        // multi-MB row map to ensureJoinMap, called outside this lock).
        // ⭐ PLAN-identity-safe-join-caches D5: a cached lookup is reused ONLY for the very table
        // instance it was built over. The key is a NAME, and a name could resolve to a different
        // instance (a split union rebuilt for another resolver, a reloaded table); serving the old
        // lookup would then read the other instance's rows. Today the instance is always the same
        // (the cached lookup pins its table, one JoinCache per target dataset), so this check
        // never fires in production — and when it holds, the result is byte-identical.
        JoinLookup existing = lookupCache.compute(key, (_, current) ->
        {
            if (current instanceof DatasetLookup dl ? dl.dataset() == dataset : current != null)
            {
                return current; // same instance, or a non-key lookup registered under this name
            }
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
