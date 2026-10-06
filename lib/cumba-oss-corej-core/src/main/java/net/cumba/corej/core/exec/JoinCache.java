package net.cumba.corej.core.exec;

import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * The per-dataset cache of the merged evaluation views ({@code PLAN-merged-evaluation-table} P-3:
 * one view per merge signature, shared by every rule of the dataset whose merge agrees), and the
 * holder of the run's {@link SharedIndexCache}.
 *
 * <p>
 * The key-join lookups, their joined-side indexes, the key-match indexes and the SUPP pivot's
 * parsed qualifier rows and columns it cached until W6 retired with the pipeline that read them:
 * the datatable's shared matching indexes the partners of a view, and the view is the cached unit.
 * </p>
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
     * Thread-safe run-wide caches, shared across dataset validation threads so that a structure
     * derived from one table is built only once for the entire validation run.
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
     * <li><b>Wildcard column sets</b> ({@link #wildcardColumns}) — sub-key: regex and flags.</li>
     * <li><b>The merged schema's QNAM set</b> per SUPP table ({@link #getOrBuildSuppQnams}).</li>
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

        private final WildcardForeignColumnCache wildcardColumns;

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
            wildcardColumns = new WildcardForeignColumnCache(
                    new IdentityWeakCache<>(aIdentityHash));
            suppQnams = new IdentityWeakCache<>(aIdentityHash);
        }


        /**
         * The merged schema's QNAM set of one SUPP table, built once per table per run
         * ({@link SuppQualifiers}). Keyed by the SUPP table alone on purpose: the set is every
         * {@code QNAM} the table carries whether or not it became a column
         * ({@code MergedTable.suppQnams}), so it does not depend on the base it is paired with — a
         * member of a split family ({@code SUPPAE1}) paired with two bases answers one set
         * ({@code PLAN-split-supp-resolution} confirmation look F3, measured false:
         * {@code SuppMergeSourcesTest}).
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


        /** Returns the run's wildcard column cache, shared by every rule of the run. */
        WildcardForeignColumnCache wildcardColumns()
        {
            return wildcardColumns;
        }
    }

    /**
     * The merged evaluation views of this dataset, one per merge signature (P-3 of
     * {@code PLAN-merged-evaluation-table}): every rule whose keyed entries and SUPP flag agree
     * reads the same view, its row maps and its qualifier columns — or the
     * {@link net.cumba.datatable.merge.MergeException} the datatable refused the signature with.
     * Released with this cache when the dataset finishes.
     *
     * <p>
     * ⭐ <b>Keyed on the signature, not on the partners' identity</b> (T1 of the plan; the retired
     * {@code DatasetLookup} cache validated identity, D5 of
     * {@code PLAN-identity-safe-join-caches}). D5 guarded a key made of a 31-bit identity hash;
     * this key is a value (the partner names, keys and flags), so no two tables can collide in it.
     * What is left is a partner RELOADED as a new instance while its signature's view is cached —
     * and that is unreachable: (1) this cache is one dataset's ({@code LibraryValidator} builds one
     * per dataset, and the primary is held for the dataset's whole run; a view is cached only when
     * the rule evaluates that table itself); (2) a built view holds every partner strongly (its
     * parts are views over them), so the run's soft dataset memo cannot clear and reload a partner
     * while the view lives — any resolve in that time answers the very instance the view reads
     * ({@code JoinCacheViewIdentityTest}, with its planted control); (3) a resolver that loaded
     * afresh on every call would hand back an instance of the same file, whose answers are the
     * view's. ⚠ If a view ever stops pinning its partners (a weak part, a lazily re-resolved
     * partner), that test reds and this key must then carry, or validate, the partners' identity.
     * </p>
     */
    private final ConcurrentHashMap<MergedEvaluation.ViewKey, Object> views = new ConcurrentHashMap<>();

    /** Shared index cache (may be null if no cross-dataset sharing is desired). */
    private final @Nullable SharedIndexCache sharedIndex;

    /**
     * The standard the dataset's {@code SUPP--} and {@code RELREC} datasets are read under
     * ({@link net.cumba.datatable.merge.MergeStandard}): the run's standard, carried here because
     * the cache is per dataset and every view it builds is read under the same one (T2 fold, H1).
     */
    private final net.cumba.datatable.merge.MergeStandard mergeStandard;

    /**
     * Creates a JoinCache with a shared index cache for cross-dataset index reuse, reading the
     * supplemental and relationship datasets under
     * {@link net.cumba.datatable.merge.MergeStandard#UNKNOWN} (SEND inferred from a {@code POOLID}
     * column).
     *
     * @param sharedIndex
     *            the shared index cache, or {@code null} to disable cross-dataset sharing
     */
    public JoinCache(@Nullable SharedIndexCache sharedIndex)
    {
        this(sharedIndex, net.cumba.datatable.merge.MergeStandard.UNKNOWN);
    }


    /**
     * Creates a JoinCache reading the dataset's {@code SUPP--} and {@code RELREC} datasets under
     * the run's standard.
     *
     * @param sharedIndex
     *            the shared index cache, or {@code null} to disable cross-dataset sharing
     * @param mergeStandard
     *            the standard the merges are read under; {@code UNKNOWN} infers SEND from a
     *            {@code POOLID} column
     */
    public JoinCache(@Nullable SharedIndexCache sharedIndex,
            net.cumba.datatable.merge.MergeStandard mergeStandard)
    {
        this.sharedIndex = sharedIndex;
        this.mergeStandard = java.util.Objects.requireNonNull(mergeStandard, "mergeStandard");
    }


    /**
     * Creates a JoinCache without cross-dataset index sharing.
     */
    public JoinCache()
    {
        this(null);
    }


    /** The standard this dataset's {@code SUPP--} and {@code RELREC} datasets are read under. */
    public net.cumba.datatable.merge.MergeStandard mergeStandard()
    {
        return mergeStandard;
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
}
