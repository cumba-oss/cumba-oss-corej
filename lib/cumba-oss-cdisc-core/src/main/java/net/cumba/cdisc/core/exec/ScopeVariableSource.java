package net.cumba.cdisc.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * Fix #124: supplies the metadata of datasets <em>other</em> than the one under validation, so
 * {@link ScopeMatcher} can decide a qualified {@code Scope.Variables} entry ({@code DM.ARM},
 * {@code ADSL.TRTxxPN}).
 *
 * <h2>Qualifier resolution</h2> Mirrors what {@code exists DM.ARM} already does in a {@code Check}
 * ({@code OperatorRegistry.existsAsDottedDatasetColumn}), with two documented scope-side
 * <b>supersets</b>:
 * <ol>
 * <li>a {@code --} in the qualifier is resolved against the primary table
 * ({@link OperationExecutor#resolveWildcard}), so {@code SUPP--.QVAL} works — the Check side does
 * not do this;</li>
 * <li>{@link DatasetResolver#resolve} by exact dataset name;</li>
 * <li>failing that, the union of {@link DatasetResolver.WithInventory#tablesForDomain}, so a split
 * domain ({@code LB} → {@code lbch}/{@code lbhe}/{@code lbur}) resolves to its members' columns —
 * the Check side does not do this either;</li>
 * <li>for a <em>literal</em> variable, a SUPP-pivot fallback scanning {@code SUPP<QUALIFIER>} for a
 * row with {@code QNAM == <variable>} (Fix #39 parity, shared implementation with
 * {@code OperatorRegistry}).</li>
 * </ol>
 * Because of (1) and (3) a hoist of an {@code exists DS.COL} guard from a {@code Check} into
 * {@code Scope.Variables} is behaviour-preserving only for <b>plain</b> qualifiers.
 *
 * <h2>Evaluability</h2> Only an inventory-capable resolver can answer a qualified entry: without an
 * inventory there is no way to distinguish "the dataset is genuinely absent" from "this resolver
 * cannot see other datasets" (the {@code RuleEditorService} plain-{@code .cdt} preview supplies a
 * non-null resolver that resolves nothing). {@link #of} therefore returns {@code null} for anything
 * that is not a {@link DatasetResolver.WithInventory}, and callers treat a {@code null} source as
 * "ignore qualified entries" plus a one-time WARN.
 *
 * <h2>Threading</h2> The production path validates cohorts in parallel, so both memos are
 * {@link ConcurrentHashMap}s. An instance is scoped to one primary dataset (the {@code --}
 * resolution is primary-dependent) and must not be shared across datasets.
 */
public final class ScopeVariableSource
{

    /** Separator for the {@link #suppMemo} composite key; never occurs in a name. */
    private static final char KEY_SEP = '\0';

    private final DatasetResolver.WithInventory resolver;

    private final IDataTable primary;

    private final Map<String, List<DataTableMeta>> metaMemo = new ConcurrentHashMap<>();

    private final Map<String, Boolean> suppMemo = new ConcurrentHashMap<>();

    private ScopeVariableSource(DatasetResolver.WithInventory resolver, IDataTable primary)
    {
        this.resolver = resolver;
        this.primary = primary;
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
        return resolver instanceof DatasetResolver.WithInventory inv
                ? new ScopeVariableSource(inv, primary)
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
        return metaMemo.computeIfAbsent(qualifier, this::resolveMetas);
    }


    /**
     * Returns {@code true} when the supplemental-qualifier dataset for {@code qualifier} delivers
     * {@code column} through a {@code QNAM} row — the Fix #39 lazy SUPP-pivot lookup, which brings
     * qualified scope entries into line with the {@code Check}-side dotted {@code exists}.
     *
     * <p>
     * The scan itself is {@code OperatorRegistry.existsInSuppQnam}, shared verbatim, so the
     * case-sensitive {@code QNAM} comparison and the missing/empty-cell skip are identical on both
     * paths. Review L8 — one deliberate deviation: the "don't look for {@code SUPPSUPPAE}" guard
     * below is case-<em>in</em>sensitive, where the Check side tests
     * {@code domain.startsWith("SUPP")} exactly. That makes a filename-derived lowercase
     * {@code suppae} behave correctly here; it is unobservable otherwise, since it would take a
     * dataset literally named {@code SUPPsuppae} to tell the two apart.
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
        if (resolved.toUpperCase(Locale.ROOT).startsWith("SUPP"))
        {
            return false;
        }
        return suppMemo.computeIfAbsent(resolved + KEY_SEP + column, _ ->
        {
            IDataTable supp = resolver.resolve("SUPP" + resolved);
            return supp != null && OperatorRegistry.existsInSuppQnam(supp, column);
        });
    }


    /**
     * Resolves a {@code --} in the qualifier against the primary table, mirroring how
     * {@code Match_Datasets.Name} is resolved in {@code RuleRunner.buildJoinedDatasets}.
     * <p>
     * Review M4 — note the asymmetry this inherits. {@link OperationExecutor#resolveWildcard}
     * substitutes the primary's <b>table name</b>, not its domain code, so on a split member
     * {@code lbch} the qualifier {@code SUPP--} becomes {@code SUPPlbch} (which does not exist)
     * rather than {@code SUPPLB}. The <em>unqualified</em> leg of the same gate resolves {@code --}
     * against the domain code instead ({@code ScopeMatcher.resolveScopeVariable}). The behaviour
     * here is deliberate: it is exactly what {@code Match_Datasets.Name} does, and diverging would
     * mean a qualifier resolved one way for the join and another for the scope gate. A qualified
     * {@code SUPP--.} entry is therefore not usable on split datasets.
     * </p>
     */
    private String resolveQualifierName(String qualifier)
    {
        String resolved = OperationExecutor.resolveWildcard(qualifier, primary);
        return resolved != null ? resolved : qualifier;
    }

    /**
     * Length of an SDTM domain code — the only qualifier for which the split-domain union can
     * possibly match, since {@code tablesForDomain} compares against each table's {@code DOMAIN}
     * cell.
     */
    private static final int DOMAIN_CODE_LENGTH = 2;

    /** Uncached resolution — see the class javadoc for the ordering and why it matters. */
    private List<DataTableMeta> resolveMetas(String qualifier)
    {
        String name = resolveQualifierName(qualifier);
        IDataTable exact = resolver.resolve(name);
        if (exact != null)
        {
            return List.of(exact.getMetaData());
        }
        // No dataset by that name: the qualifier may be an SDTM domain split across members
        // (LB -> lbch/lbhe/lbur). tablesForDomain is the existing data-driven union.
        //
        // Review M3 — bound the walk. tablesForDomain resolves EVERY dataset in the inventory and
        // reads a DOMAIN cell from each; in production `resolve` loads and parses the dataset, so
        // an unbounded call pulls the whole submission into memory. It can only ever match a
        // two-character SDTM domain code (that is what a DOMAIN cell holds), so a longer
        // qualifier — an ADaM dataset name, a SUPP name, or a typo, i.e. the common "genuinely
        // absent" case — is answered immediately without touching the inventory.
        if (name.length() != DOMAIN_CODE_LENGTH)
        {
            return List.of();
        }
        List<DataTableMeta> metas = new ArrayList<>();
        for (IDataTable table : resolver.tablesForDomain(name))
        {
            metas.add(table.getMetaData());
        }
        return List.copyOf(metas);
    }

}
