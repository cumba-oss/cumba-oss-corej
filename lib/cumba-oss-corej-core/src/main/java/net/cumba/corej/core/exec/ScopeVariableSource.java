package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * Fix #124: supplies the metadata of datasets <em>other</em> than the one under validation, so
 * {@link ScopeMatcher} can decide a qualified {@code Scope.Variables} entry ({@code DM.ARM},
 * {@code ADSL.TRTxxPN}).
 *
 * <h2>Qualifier resolution</h2> Mirrors what {@code exists DM.ARM} already does in a {@code Check}
 * ({@code OperatorRegistry.existsAsDottedDatasetColumn}, undeclared arm), with two documented
 * scope-side <b>supersets</b>:
 * <ol>
 * <li>a {@code --} in the qualifier is resolved against the primary table
 * ({@link DatasetIdentity#resolveDatasetWildcard}: {@code SUPP--} / {@code SQAP--} is the
 * supplemental family of the primary's domain code, every other shape as before), so
 * {@code SUPP--.QVAL} works — the Check side does not do this;</li>
 * <li>{@link DatasetResolver#resolve} by dataset name, ignoring case;</li>
 * <li>failing that, the members of a split domain ({@code LB} → {@code lbch}/{@code lbhe}/
 * {@code lbur}: {@link SplitDomainResolution#membersOf}, fault-tolerant per entry) or — for a
 * supplemental family name ({@code SUPPAE}, {@code SQAPMH}; {@code PLAN-split-supp-resolution} D2)
 * — the family's members ({@link SplitDomainResolution#suppFamilyMembers}: {@code SUPPAE1} +
 * {@code SUPPAE2}, the exact {@code SUPPAE} among them), so the entry resolves to its members'
 * columns, never a built union — a type question ({@code ScopeMatcher}'s {@code agreedKind},
 * {@link #suppQvalType}) is answered as the union itself would answer it
 * ({@code UnionDataTable.resolvedColumnType}: a member holding no value in the column defers to the
 * valued members, ECT-Q7);</li>
 * <li>for a <em>literal</em> variable, a SUPP-pivot fallback over the qualifier's SUPP sources (the
 * one chooser's members, {@link SplitDomainResolution#suppMergeSources}) for a row with
 * {@code QNAM == <variable>} (Fix #39 parity, shared implementation with
 * {@code OperatorRegistry}).</li>
 * </ol>
 * Because of (1) and (3) a hoist of an {@code exists DS.COL} guard from a {@code Check} into
 * {@code Scope.Variables} is behaviour-preserving only for <b>plain</b> qualifiers.
 *
 * <h2>Evaluability</h2> Only an inventory-capable resolver can answer a qualified entry: without an
 * inventory there is no way to distinguish "the dataset is genuinely absent" from "this resolver
 * cannot see other datasets" (a plain-{@code .cdt} preview supplies a non-null resolver that
 * resolves nothing). {@link #of} therefore returns {@code null} for anything that is not a
 * {@link DatasetResolver.WithInventory}.
 *
 * <p>
 * ⭐ <b>What a {@code null} source MEANS changed on 2026-09-10</b> (owner ruling, disposition (b) of
 * {@code <meta>/plans/PLAN-qualified-requirements-cross-standard.md} §8.4). It used to mean "ignore
 * qualified entries" plus a one-time WARN — which left a rule whose {@code Check}-side guard had
 * been hoisted into {@code Requirements} running with nothing in its place. An undecidable entry
 * now skips the rule with a reason naming the <em>resolver</em>, not the dataset — the only policy
 * since K6 (2026-09-25) retired the "ignore" option. No WARN is emitted any more, in any leg. In
 * the {@code Any} leg an undecidable entry is a mismatch like any other, so a decidable sibling in
 * the same group can still satisfy the group on its own; when none does, the group reports the
 * undecidable reason rather than "absent" ({@code ScopeMatcher.describeAnyLeg}).
 * </p>
 *
 * <h2>Threading</h2> The production path validates rules in parallel
 * ({@code LibraryValidator.executeRulesInParallel}, one task per rule at {@code ruleThreads > 1}),
 * so both memos are {@link ConcurrentHashMap}s — and they stay that way: the fan-out is real, only
 * its unit of work changed when the cohort runner was retired
 * ({@code PLAN-retire-cohort-runner.md}). An instance is scoped to one primary dataset (the
 * {@code --} resolution is primary-dependent) and must not be shared across datasets.
 */
public final class ScopeVariableSource
{

    /** Separator for the {@link #suppMemo} composite key; never occurs in a name. */
    private static final char KEY_SEP = '\0';

    private final DatasetResolver.WithInventory resolver;

    private final IDataTable primary;

    /** The rule's declared SUPP merge; {@code false} answers every SUPP-QNAM probe false. */
    private final boolean suppMerge;

    /**
     * The run's shared index cache, or {@code null}: a SUPP-QNAM probe reads the one memoised QNAM
     * set of the SUPP table ({@link SuppQualifiers}; combined review of runbook W2–W8, round 2 L2).
     */
    private final JoinCache.@Nullable SharedIndexCache sharedIndex;

    private final Map<String, List<IDataTable>> tableMemo = new ConcurrentHashMap<>();

    private final Map<String, Boolean> suppMemo = new ConcurrentHashMap<>();

    /**
     * Ruling D9's memo: the {@code QVAL} column type of each {@code SUPP<domain>} table.
     * <p>
     * ⚠ Keyed by the resolved qualifier <b>alone</b>, deliberately separate from {@link #suppMemo}:
     * {@code QVAL}'s type does not depend on which variable is being looked for, so folding it into
     * that memo's {@code resolved + KEY_SEP + column} key would re-read the metadata once per
     * variable instead of once per dataset.
     * </p>
     * <p>
     * ⚠⚠ A {@link ConcurrentHashMap} cannot hold a {@code null} value, and "no QVAL column" is a
     * real answer here — so the absent case is stored as {@link DataValueType#MISSING}, which is
     * the engine's own spelling of <em>unknown</em> ({@code JoinLookup.declaredTypeOf} uses it for
     * exactly this) and which {@link net.cumba.corej.core.expr.eval.ColumnTypeGate#kindOf} maps to
     * {@code null} anyway.
     * </p>
     */
    private final Map<String, DataValueType> qvalMemo = new ConcurrentHashMap<>();

    private ScopeVariableSource(DatasetResolver.WithInventory resolver, IDataTable primary,
            boolean suppMerge, JoinCache.@Nullable SharedIndexCache sharedIndex)
    {
        this.resolver = resolver;
        this.primary = primary;
        this.suppMerge = suppMerge;
        this.sharedIndex = sharedIndex;
    }


    /**
     * Returns a source for the given resolver, or {@code null} when the resolver cannot enumerate
     * datasets and therefore cannot answer a qualified entry (see the class javadoc).
     *
     * @param resolver
     *            the dataset resolver in effect, possibly {@code null}
     * @param primary
     *            the dataset under validation — required, since {@code --} qualifier resolution
     *            reads its name
     * @return the source, or {@code null} when qualified entries cannot be evaluated
     */
    public static @Nullable ScopeVariableSource of(@Nullable DatasetResolver resolver,
            IDataTable primary)
    {
        return of(resolver, primary, true);
    }


    /**
     * As {@link #of(DatasetResolver, IDataTable)}, with the rule's declared SUPP merge
     * ({@code Rule#isSuppMergeEnabled()}): {@code false} makes {@link #existsViaSuppQnam} answer
     * {@code false} for every probe ({@code PLAN-operation-replacements} §2.3 — one flag, one
     * meaning on the existence and the value path).
     *
     * @param resolver
     *            the run's resolver
     * @param primary
     *            the dataset under evaluation
     * @param suppMerge
     *            whether the rule's SUPP merge is on
     * @return the source, or {@code null} when the resolver cannot enumerate datasets
     */
    public static @Nullable ScopeVariableSource of(@Nullable DatasetResolver resolver,
            IDataTable primary, boolean suppMerge)
    {
        return of(resolver, primary, suppMerge, null);
    }


    /**
     * As {@link #of(DatasetResolver, IDataTable, boolean)}, reading a {@code SUPP--} table's
     * qualifiers through the run's shared index (one parse per table per run, shared with the
     * pivot).
     *
     * @param resolver
     *            the run's resolver
     * @param primary
     *            the dataset under evaluation
     * @param suppMerge
     *            whether the rule's SUPP merge is on
     * @param sharedIndex
     *            the run's shared index cache, or {@code null}
     * @return the source, or {@code null} when the resolver cannot enumerate datasets
     */
    static @Nullable ScopeVariableSource of(@Nullable DatasetResolver resolver, IDataTable primary,
            boolean suppMerge, JoinCache.@Nullable SharedIndexCache sharedIndex)
    {
        return resolver instanceof DatasetResolver.WithInventory inv
                ? new ScopeVariableSource(inv, primary, suppMerge, sharedIndex)
                : null;
    }


    /**
     * Returns the dataset name {@code qualifier} actually addresses — i.e. with a {@code --}
     * resolved against the primary ({@code SUPP--} → {@code SUPPAE} when validating AE). Mismatch
     * messages name this rather than the raw entry half, so a reader is told which dataset was
     * looked for instead of having to redo the substitution.
     *
     * @param qualifier
     *            the qualifier half of a scope entry
     * @return the resolved dataset name
     */
    public String resolvedQualifier(String qualifier)
    {
        return resolveQualifierName(qualifier);
    }


    /**
     * Returns the metadata of every table backing {@code qualifier} — one entry for a plain
     * dataset, several for a split domain — or an <b>empty</b> list when the dataset is not
     * available.
     * <p>
     * Metadata rather than a flat name set, because {@link DataTableMeta#getColumnIndex} honours a
     * per-table case-sensitivity flag; flattening would hard-code one casing policy and diverge
     * from the primary-dataset leg of the same gate.
     * </p>
     *
     * @param qualifier
     *            the dataset or domain named by a qualified scope entry
     * @return the backing metadata, empty when the dataset is not available
     */
    public List<DataTableMeta> metasOf(String qualifier)
    {
        return metasOf(tablesOf(qualifier));
    }


    /**
     * The tables behind {@link #metasOf}: the dataset the qualifier names, or the members of the
     * split domain / supplemental family it names — for the type question, which the union's own
     * resolution answers from the members' CONTENT, not their declared types alone (ECT-Q7).
     *
     * @param qualifier
     *            the dataset or domain named by a qualified scope entry
     * @return the backing tables, empty when the dataset is not available
     */
    public List<IDataTable> tablesOf(String qualifier)
    {
        return tableMemo.computeIfAbsent(qualifier, this::resolveTables);
    }


    /**
     * W2a (C1 ruled (a)): whether {@code column} is delivered as a supplemental qualifier of the
     * PRIMARY dataset — {@code Supp_Merge} on and one of the primary's SUPP sources (the one
     * chooser's members, {@link SplitDomainResolution#suppMergeSources}: on {@code LBCH} its own
     * {@code SUPPLBCH}, on {@code AE} the {@code SUPPAE} family — exactly what the merged view
     * feeds, {@code PLAN-split-supp-resolution} PG-H1) carrying the {@code QNAM}. The bare-entry
     * counterpart of {@link #existsViaSuppQnam}.
     *
     * @param column
     *            the bare column name
     * @return whether a SUPP row delivers it
     */
    public boolean localQualifier(String column)
    {
        if (!suppMerge)
        {
            return false;
        }
        String name = primary.getMetaData().getName();
        if (name == null)
        {
            return false;
        }
        String domain = DatasetIdentity.domainOfDataset(name, resolver);
        if (domain == null || domain.isEmpty())
        {
            return false;
        }
        return suppMemo.computeIfAbsent(KEY_SEP + domain + KEY_SEP + column, _ ->
        {
            for (IDataTable supp : SplitDomainResolution.suppMergeSources(resolver, primary, name,
                    domain, null))
            {
                if (SuppQualifiers.ofSupp(primary, domain, supp, sharedIndex).contains(column))
                {
                    return true;
                }
            }
            return false;
        });
    }


    /**
     * Returns {@code true} when the supplemental-qualifier dataset for {@code qualifier} delivers
     * {@code column} through a {@code QNAM} row — the Fix #39 lazy SUPP-pivot lookup, which brings
     * qualified scope entries into line with the {@code Check}-side dotted {@code exists}.
     *
     * <p>
     * The set itself is {@link SuppQualifiers}' — the merged schema's QNAM set over the qualifier's
     * SUPP sources (the one chooser's members), shared with the Check-side dotted
     * {@code var_exists} — so the case-sensitive {@code QNAM} comparison (a {@code QNAM} cell is a
     * string value, register {@code CIT §3}) and the blank-cell skip are identical on both paths,
     * and both guard a SUPP / SQ / RELREC qualifier the same way
     * ({@link DatasetIdentity#hasNoSupplemental}, ignoring case).
     * </p>
     *
     * @param qualifier
     *            the (already {@code --}-resolved) foreign dataset name
     * @param column
     *            the literal variable name being looked for
     * @return whether {@code SUPP<qualifier>} carries the column as a qualifier row
     */
    public boolean existsViaSuppQnam(String qualifier, String column)
    {
        String resolved = resolveQualifierName(qualifier);
        // a SUPP / SQ / RELREC qualifier has no supplemental of its own (the IG's rule; the guard
        // follows the merge's, PLAN-split-supp-resolution C5)
        return suppMerge && !DatasetIdentity.hasNoSupplemental(resolved)
                && suppMemo.computeIfAbsent(resolved + KEY_SEP + column, _ ->
                {
                    // W3d: the merged schema's QNAM set for the foreign dataset (SuppQualifiers,
                    // the IG's SUPP names for it) — the dataset itself is the base the SUPP
                    // qualifies; absent, the SUPP stands in (review H2: SUPPAE without AE)
                    IDataTable foreign = resolveIgnoringCase(resolved);
                    String domain = java.util.Objects.requireNonNullElse(
                            DatasetIdentity.domainOfDataset(resolved, resolver), resolved);
                    return SuppQualifiers.of(foreign, resolved, domain, resolver, sharedIndex, null)
                            .contains(column);
                });
    }


    /**
     * Ruling <b>D9</b> — the declared type of {@code SUPP<qualifier>}'s {@code QVAL} column, which
     * is the type of any variable that qualifier delivers through the QNAM pivot rather than as a
     * column of its own.
     *
     * <p>
     * The pivot ({@link #existsViaSuppQnam}) answers existence only — the variable arrives as a
     * {@code QNAM} <b>row</b>, so it has no {@code DataTableColumnMeta}. Its values live in
     * {@code QVAL}, so {@code QVAL}'s type is the delivered variable's type. Read from the SUPP
     * table's own metadata (<b>D6</b>: the data's type, never the standard's assertion that
     * {@code QVAL} is Char), so a study shipping something unusual is reported honestly.
     * </p>
     *
     * <p>
     * The SUPP tables read are the qualifier's SUPP sources (the one chooser's members,
     * {@link SplitDomainResolution#suppMergeSources}; {@code PLAN-split-supp-resolution} C5): the
     * type the union of those members resolves for {@code QVAL}
     * ({@code UnionDataTable.resolvedColumnType}, ECT-Q7: a member holding no value in the column
     * defers to the valued members); members holding values that disagree, or no member with a
     * {@code QVAL} column, answer {@link DataValueType#MISSING}.
     * </p>
     *
     * @param qualifier
     *            the qualifier half of the entry, {@code --} not yet resolved
     * @return {@code QVAL}'s resolved type, or {@link DataValueType#MISSING} when no SUPP table or
     *         {@code QVAL} column is there, or the valued members disagree — i.e. "unknown", which
     *         does not block (D2)
     */
    public DataValueType suppQvalType(String qualifier)
    {
        String resolved = resolveQualifierName(qualifier);
        return qvalMemo.computeIfAbsent(resolved, name ->
        {
            IDataTable foreign = resolveIgnoringCase(name);
            String domain = java.util.Objects
                    .requireNonNullElse(DatasetIdentity.domainOfDataset(name, resolver), name);
            List<IDataTable> sources = SplitDomainResolution.suppMergeSources(resolver, foreign,
                    name, domain, null);
            DataValueType type = sources.isEmpty() ? null
                    : net.cumba.datatable.impl.view.UnionDataTable
                            .resolvedColumnType(sources.toArray(IDataTable[]::new), "QVAL");
            return type == null ? DataValueType.MISSING : type;
        });
    }


    /**
     * Resolves a {@code --} in the qualifier against the primary table, mirroring how
     * {@code Match_Datasets.Name} is resolved for the merged view ({@code MergedEvaluation}) — the
     * one helper {@link DatasetIdentity#resolveDatasetWildcard}.
     * <p>
     * Review M4 of the scope gate recorded an asymmetry here: {@code resolveWildcard} substituted
     * the primary's <b>table name</b>, so on a split member {@code lbch} a qualified {@code SUPP--}
     * became {@code SUPPlbch} while the unqualified leg resolved {@code --} against the domain
     * code. Since {@code PLAN-split-supp-resolution} (Q6, D6) {@code SUPP--} is the supplemental
     * family of the primary's <em>domain code</em> in every dataset-name position — {@code SUPPLB}
     * on {@code lbch}, {@code SQAPMH} on {@code APMH} (D10) — so the qualifier, the join and the
     * unqualified leg agree, and a qualified {@code SUPP--.} entry is usable on a split dataset. A
     * SUPP / SQAP primary keeps Fix #33 (the parent prefix).
     * </p>
     */
    private String resolveQualifierName(String qualifier)
    {
        String resolved = DatasetIdentity.resolveDatasetWildcard(qualifier, primary);
        return resolved != null ? resolved : qualifier;
    }


    /**
     * The dataset {@code name} addresses: the resolver's exact answer, else the inventory entry
     * that equals it ignoring letter case ({@code PLAN-merged-evaluation-table} W3d, P-19 / MET #6
     * — a dataset name is case-insensitive; the product's resolvers already upper-case, this makes
     * the gate independent of the resolver's own policy), else {@code null}.
     */
    private @Nullable IDataTable resolveIgnoringCase(String name)
    {
        return SplitDomainResolution.byName(resolver, name);
    }

    /**
     * Length of an SDTM domain code — the only qualifier for which the split-domain members can
     * possibly match, since the walk compares against each table's {@code DOMAIN} cell.
     */
    private static final int DOMAIN_CODE_LENGTH = 2;

    /** Uncached resolution — see the class javadoc for the ordering and why it matters. */
    private List<IDataTable> resolveTables(String qualifier)
    {
        String name = resolveQualifierName(qualifier);
        if (SplitDomainResolution.isSuppFamilyName(name))
        {
            // PLAN-split-supp-resolution D2 / D3 (PG-H1): a supplemental family name answers its
            // members — the exactly-named table among them — never the union
            return List.copyOf(SplitDomainResolution.suppFamilyMembers(resolver, name, null));
        }
        IDataTable exact = resolveIgnoringCase(name);
        if (exact != null)
        {
            return List.of(exact);
        }
        // No dataset by that name: the qualifier may be an SDTM domain split across members
        // (LB -> lbch/lbhe/lbur) — the seam's own member walk (SplitDomainResolution.membersOf:
        // memoised per run, sorted, fault-tolerant per entry, so an unreadable UNRELATED dataset
        // never throws through the scope gate — PLAN-split-supp-resolution confirmation look
        // side (a); tablesForDomain resolved every entry unguarded).
        //
        // Review M3 — bound the walk. The walk resolves EVERY dataset in the inventory and
        // reads a DOMAIN cell from each; in production `resolve` loads and parses the dataset, so
        // an unbounded call pulls the whole submission into memory. It can only ever match a
        // two-character SDTM domain code (that is what a DOMAIN cell holds), so a longer
        // qualifier — an ADaM dataset name, a split SUPP member name, or a typo, i.e. the common
        // "genuinely absent" case — is answered immediately without touching the inventory.
        if (name.length() != DOMAIN_CODE_LENGTH)
        {
            return List.of();
        }
        return List.copyOf(SplitDomainResolution.membersOf(resolver, name, null));
    }


    private static List<DataTableMeta> metasOf(List<IDataTable> members)
    {
        List<DataTableMeta> metas = new ArrayList<>(members.size());
        for (IDataTable table : members)
        {
            metas.add(table.getMetaData());
        }
        return List.copyOf(metas);
    }

}
