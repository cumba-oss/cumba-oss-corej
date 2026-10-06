package net.cumba.corej.core.exec;

import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.cumba.corej.core.exec.DatasetLookup.SharedJoinedIndex;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.IDataValue;
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
     * <li><b>Key-match indexes</b> ({@link #getOrBuildKeyMatchIndex}) — sub-key:
     * {@link KeyMatchIndex.SpecKey}.</li>
     * <li><b>Wildcard column sets</b> ({@link #wildcardColumns}) — sub-key: regex and flags.</li>
     * </ul>
     *
     * <p>
     * Each lives exactly as long as its table ({@code PLAN-keymatch-shared-join-index} Q3 (d)):
     * tables are soft-memoised and may reload as a new instance mid-run, which gets fresh entries
     * and never a stale hit. ⚠ A cached value must never hold its own table strongly, or it pins it
     * (the retired {@code ChildMatchIndex} dropped its parent for that reason, D3). Each cache is
     * its own map, so a long {@code computeIfAbsent} build never holds a bin lock over another
     * cache's entries.
     * </p>
     */
    public static final class SharedIndexCache
    {

        private final IdentityWeakCache<List<String>, SharedJoinedIndex> lookupIndexes;

        private final IdentityWeakCache<KeyMatchIndex.SpecKey, KeyMatchIndex> keyMatchIndexes;

        private final WildcardForeignColumnCache wildcardColumns;

        private final IdentityWeakCache<String, SuppQnamIndexHolder> suppQnamIndexes;

        /**
         * The materialised pivot columns ({@link SuppPivot}), keyed by the PARENT table and the
         * (index, QNAM) pair — once per run, not once per rule (combined review of runbook W2–W8,
         * XCUT PERF 1: a QNAM column materialised per rule × dataset).
         */
        private final IdentityWeakCache<SuppQnamColumnKey, IDataValue[]> suppQnamColumns;

        /** The merged schema's QNAM set per SUPP table ({@link SuppQualifiers}, W3d). */
        private final IdentityWeakCache<String, java.util.Set<String>> suppQnams;

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
            keyMatchIndexes = new IdentityWeakCache<>(aIdentityHash);
            wildcardColumns = new WildcardForeignColumnCache(
                    new IdentityWeakCache<>(aIdentityHash));
            suppQnamIndexes = new IdentityWeakCache<>(aIdentityHash);
            suppQnamColumns = new IdentityWeakCache<>(aIdentityHash);
            suppQnams = new IdentityWeakCache<>(aIdentityHash);
        }


        /**
         * The merged schema's QNAM set of one SUPP table, built once per table per run
         * ({@link SuppQualifiers}).
         */
        java.util.Set<String> getOrBuildSuppQnams(IDataTable aSupp,
                java.util.function.Function<IDataTable, java.util.Set<String>> aBuild)
        {
            return suppQnams.getOrBuild(aSupp, "", _ -> aBuild.apply(aSupp));
        }


        /** How many QNAM sets were built on this cache (tests: one per SUPP table per run). */
        int suppQnamsBuildCount()
        {
            return suppQnams.buildCount();
        }


        /**
         * The parsed qualifier rows of one {@code SUPP--} table, built once per table per run for
         * the declared SUPP merge ({@link SuppPivot}) and the existence probes (the legacy pivot,
         * until W6; existence reads {@link SuppQualifiers}); {@code null} when the table has no
         * {@code QNAM} column.
         */
        @Nullable
        SuppQnamIndex getOrBuildSuppQnamIndex(IDataTable aSupp)
        {
            return suppQnamIndexes
                    .getOrBuild(aSupp, "", _ -> new SuppQnamIndexHolder(SuppQnamIndex.of(aSupp)))
                    .index();
        }


        int suppQnamIndexBuildCount()
        {
            return suppQnamIndexes.buildCount();
        }


        /**
         * The cells of one pivoted qualifier column of {@code aParent} — {@code aQnam} of
         * {@code anIndex} resolved against the parent's rows — built once per run through
         * {@code aBuild} and shared by every rule reading that qualifier of that table.
         *
         * @param aParent
         *            the parent table (identity)
         * @param anIndex
         *            the parsed {@code SUPP--} index the column is resolved from (identity)
         * @param aQnam
         *            the qualifier name
         * @param aBuild
         *            the resolution, run at most once per (parent, index, QNAM)
         * @return the cells, one per parent row
         */
        IDataValue[] getOrBuildSuppQnamColumn(IDataTable aParent, SuppQnamIndex anIndex,
                String aQnam, Supplier<IDataValue[]> aBuild)
        {
            return suppQnamColumns.getOrBuild(aParent, new SuppQnamColumnKey(anIndex, aQnam),
                    _ -> aBuild.get());
        }


        int suppQnamColumnBuildCount()
        {
            return suppQnamColumns.buildCount();
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

    }


    private record SuppQnamIndexHolder(@Nullable SuppQnamIndex index)
    {
    }


    /**
     * The sub-key of a materialised pivot column: the index (identity — {@link SuppQnamIndex} does
     * not override {@code equals}) and the qualifier name.
     */
    private record SuppQnamColumnKey(SuppQnamIndex index, String qnam)
    {
    }

    /**
     * Per-dataset lookup cache: cacheKey → JoinLookup. {@link ConcurrentHashMap} so concurrent rule
     * threads validating the same primary dataset can share entries without external
     * synchronisation.
     */
    private final ConcurrentHashMap<String, JoinLookup> lookupCache = new ConcurrentHashMap<>();

    /**
     * The merged evaluation views of this dataset, one per merge signature (P-3 of
     * {@code PLAN-merged-evaluation-table}): every rule whose keyed entries and SUPP flag agree
     * reads the same view, its row maps and its qualifier columns — or the
     * {@link net.cumba.datatable.merge.MergeException} the datatable refused the signature with.
     * Released with this cache when the dataset finishes.
     */
    private final ConcurrentHashMap<MergedEvaluation.ViewKey, Object> views = new ConcurrentHashMap<>();

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
     * without one.
     */
    @Nullable
    SharedIndexCache getSharedIndexCache()
    {
        return sharedIndex;
    }


    /**
     * The merged view of one merge signature, built once per dataset by {@code construct} under the
     * map's lock so concurrent rule threads asking for the same signature share one build.
     *
     * <p>
     * ⭐ <b>A refusal is a fact of the signature too</b> ({@code PLAN-merged-evaluation-table} W3f,
     * P-16): the datatable decides a {@code Cardinality} violation — a key posting longer than one
     * on a side declared {@code 1} — from the data and the signature alone, so the
     * {@link net.cumba.datatable.merge.MergeException} is memoised beside the views and every rule
     * of the signature re-raises it. Without the memo each of them would rebuild the partner's
     * index to find the same duplicate (113 DM joins author {@code n:1}).
     * </p>
     *
     * @throws net.cumba.datatable.merge.MergeException
     *             the signature's refusal, the same instance for every rule asking
     */
    MergedEvaluation.Built mergedView(MergedEvaluation.ViewKey key,
            Supplier<MergedEvaluation.Built> construct)
    {
        Object built = views.computeIfAbsent(key, _ ->
        {
            try
            {
                return construct.get();
            }
            catch (net.cumba.datatable.merge.MergeException refused)
            {
                return refused;
            }
        });
        if (built instanceof net.cumba.datatable.merge.MergeException refused)
        {
            throw refused;
        }
        return (MergedEvaluation.Built) built;
    }


    /** The merge signatures decided on this cache so far, built or refused (tests). */
    int mergedViewCount()
    {
        return views.size();
    }


    /** The merged views built on this cache so far (tests). */
    java.util.Collection<MergedEvaluation.Built> mergedViews()
    {
        return views.values().stream().filter(MergedEvaluation.Built.class::isInstance)
                .map(MergedEvaluation.Built.class::cast).toList();
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
     * Returns a previously cached JoinLookup by name.
     *
     * <p>
     * ⚑ No production caller: a read seam for the concurrency tests, which assert that one
     * {@code DatasetLookup} instance survives across parallel waves
     * ({@code JoinCacheConcurrencyTest}). Kept as that instrument when its {@code put} twin — which
     * nothing called at all — was retired (2026-09-25, U2 / A34).
     * </p>
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
