package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.cumba.corej.core.model.JoinType;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.AbstractDataTableColumn;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.impl.databuffer.DataBufferFactory;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import net.cumba.datatable.merge.Cardinality;
import net.cumba.datatable.merge.KeyColumn;
import net.cumba.datatable.merge.KeyedMergeSpec;
import net.cumba.datatable.merge.MergeKind;
import net.cumba.datatable.merge.MergeMeta;
import net.cumba.datatable.merge.MergeRequest;
import net.cumba.datatable.merge.MergedPart;
import net.cumba.datatable.merge.MergedTable;
import net.cumba.datatable.merge.MergedTables;
import net.cumba.datatable.merge.SuppMergeSpec;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The merged evaluation view of one rule execution ({@code PLAN-merged-evaluation-table} §3.1,
 * W3a): the primary's declared keyed {@code Match_Datasets} parts and its {@code SUPP<domain>}
 * qualifiers as columns of one {@code net.cumba.datatable.merge.MergedTable} (QUALIFIED naming,
 * {@code _matched_} flags), built once per (primary table, merge signature) through the dataset's
 * {@link JoinCache} (P-3) and served to every reader the rule has:
 *
 * <ul>
 * <li>the evaluation table is the view's <b>primary part</b> — the primary itself when no merge
 * drops or expands a row, else the primary row-mapped exactly as the view maps it, so evaluation
 * row {@code i} is merged row {@code i};</li>
 * <li>every keyed part is a {@link MergedViewLookup} in the context's joined-dataset map, under the
 * entry's authored name — the {@link JoinLookup} readers (the compiler's dotted plans, the
 * {@code _matched_} flag, the output projection, the group readers) read the view through it;</li>
 * <li>a {@code SUPP<domain>} qualifier is a view column handed out by {@link SuppPivot} as a bare
 * name of the record ({@link Built#suppColumn}) — the type default where no row qualifies the
 * record, and a {@link MergeErrorReadException} where the column carries a {@code MERGE_ERROR}
 * (OD-7).</li>
 * </ul>
 *
 * <p>
 * <b>What stays on today's pipeline until its wave</b> (the legacy arm in {@code RuleRunner}): a
 * rule with a forward RELREC entry (W5, MQ11's ERROR when combined with a key expansion), a rule
 * with a chained qualified key (composed by the engine; the datatable refuses it), and a
 * {@code Child: true} parent, which {@code ChildMatchPreMerger} still merges BARE into the table
 * this view is built on (W4). {@link #setLegacyForTests} routes every execution to that arm, which
 * is what the corpus differential compares the view against.
 * </p>
 *
 * <p>
 * Performance: no per-row work on a cache hit — the key is the list of {@link KeyedMergeSpec}s
 * (every field that changes a merge, P-3) plus the SUPP flag; the row maps live in the datatable's
 * compact int buffers; the primary-part row map is copied into one int buffer per cached view so
 * the hot path of a primary read stays one buffer read ({@link RelrecExpandedTable}, retired with
 * the legacy expander in W6). A rule whose {@code Child} pre-merge replaced the table builds its
 * view per execution (W4 moves the parent into the view).
 * </p>
 */
public final class MergedEvaluation
{

    private static volatile boolean legacyForTests;

    private static volatile @Nullable MergeKind plantForTests;

    private MergedEvaluation()
    {
    }


    /**
     * Test hook: with {@code true} every execution runs today's pipeline (the key-match expander,
     * the first-wins lookups, the SUPP pivot) instead of the view — the base of the corpus
     * differential. Production never sets it.
     *
     * @param aLegacy
     *            whether to force the legacy arm.
     */
    public static void setLegacyForTests(boolean aLegacy)
    {
        legacyForTests = aLegacy;
    }


    /**
     * Test hook — the sensitivity plant: every read of a part of {@code aKind} answers the next
     * merged row's cell, so a differential that cannot see the view's reads stays green under it.
     *
     * @param aKind
     *            {@link MergeKind#KEYED} or {@link MergeKind#SUPP}, or {@code null} to remove the
     *            plant.
     */
    public static void plantForTests(@Nullable MergeKind aKind)
    {
        plantForTests = aKind;
    }

    /**
     * The fixed prefix of the rule ERROR a flagged column raises (OD-7) — the classifier's key in
     * the rules repository ({@code ViolationNormaliser.MERGE_ERROR_MESSAGE_PREFIX}, verbatim).
     */
    public static final String MERGE_ERROR_MESSAGE_PREFIX = "MERGE_ERROR on ";

    /**
     * The fixed prefix of the rule ERROR a merge the datatable refuses raises (a degenerate or
     * clashing key, a {@code Cardinality} violation) — the classifier's key in the rules repository
     * ({@code ViolationNormaliser.MERGE_REFUSED_MESSAGE_PREFIX}, verbatim).
     */
    public static final String MERGE_REFUSED_MESSAGE_PREFIX = "merged evaluation table: ";

    /**
     * The merge signature of one execution (P-3): every field that changes the merge — the keyed
     * specs (their {@code Cardinality} and their partner's SUPP key included), the supplemental
     * datasets that resolve for the primary and the authored {@code Supp_Keys} ({@code null} for
     * the IG's).
     */
    record ViewKey(List<KeyedMergeSpec> keyed, List<String> supp,
            @Nullable List<KeyColumn> suppKeys)
    {

        ViewKey
        {
            keyed = List.copyOf(keyed);
            supp = List.copyOf(supp);
            suppKeys = suppKeys == null ? null : List.copyOf(suppKeys);
        }
    }


    /** The built view and what the execution reads from it. */
    public static final class Built
    {

        private final MergedTable view;

        private final IDataTable table;

        private final Map<String, JoinLookup> lookups;

        private final Map<String, String> flagged;

        private final boolean suppShifted;

        /** Whether the view is the dataset's cached one for its merge signature (P-3). */
        private final boolean shared;

        /** name → {@link ViewColumn}, the flag (a String) of a flagged column, or {@link #NONE}. */
        private final ConcurrentHashMap<String, Object> suppColumns = new ConcurrentHashMap<>();

        private static final Object NONE = new Object();

        private Built(MergedTable aView, IDataTable aTable, Map<String, JoinLookup> aLookups,
                Map<String, String> aFlagged, boolean aSuppShifted, boolean aShared)
        {
            view = aView;
            table = aTable;
            lookups = Collections.unmodifiableMap(aLookups);
            flagged = Collections.unmodifiableMap(aFlagged);
            suppShifted = aSuppShifted;
            shared = aShared;
        }


        /**
         * Whether this is the dataset's cached view of its merge signature (P-3), shared by every
         * rule of the signature — {@code false} for a view built per execution (a {@code Child}
         * pre-merge replaced the table, or no cache). The expression cache keys a merged leaf on a
         * shared view only ({@code DatasetExpressionCache.cacheableAt}).
         *
         * @return whether the view is shared
         */
        public boolean isShared()
        {
            return shared;
        }


        /** The merged view. */
        public MergedTable view()
        {
            return view;
        }


        /**
         * The evaluation table: the view's primary part (the primary, row-mapped as the view is).
         */
        public IDataTable table()
        {
            return table;
        }


        /** One {@link MergedViewLookup} per keyed part, under the entry's authored name. */
        public Map<String, JoinLookup> lookups()
        {
            return lookups;
        }


        /** The merged columns carrying a {@code MERGE_ERROR}, name → flag (OD-7). */
        public Map<String, String> flagged()
        {
            return flagged;
        }


        /**
         * The qualifier column for a bare name the primary does not carry, or {@code null} when the
         * view has no SUPP column of that name; memoised per name.
         *
         * @throws MergeErrorReadException
         *             when the column carries a {@code MERGE_ERROR} (OD-7)
         */
        @Nullable
        IDataTableColumn suppColumn(String name)
        {
            Object cached = suppColumns.computeIfAbsent(name, this::resolveSupp);
            if (cached instanceof String flag)
            {
                throw new MergeErrorReadException(name, flag);
            }
            return cached instanceof ViewColumn column ? column : null;
        }


        /** The view's metadata of the SUPP qualifier column {@code name} (resolved first). */
        DataTableColumnMeta suppColumnMeta(String name)
        {
            return view.getMetaData().getColumn(view.getColumnIndex(name));
        }


        /** The QNAM set the primary's SUPP parts carry — the merged schema's qualifiers (M8). */
        public java.util.Set<String> suppQnams()
        {
            return view.suppQnams();
        }


        private Object resolveSupp(String name)
        {
            int idx = view.getColumnIndex(name);
            if (idx < 0 || view.partOf(idx).kind() != MergeKind.SUPP)
            {
                return NONE;
            }
            String flag = flagged.get(view.getMetaData().getColumn(idx).getName());
            return flag != null ? flag : new ViewColumn(view, idx, suppShifted);
        }
    }


    /** One merged column as a column of the evaluation table (same rows by construction). */
    private static final class ViewColumn extends AbstractDataTableColumn
    {

        private final MergedTable view;

        private final int column;

        private final boolean shifted;

        private ViewColumn(MergedTable aView, int aIndex, boolean aShifted)
        {
            super(aIndex);
            view = aView;
            column = aIndex;
            shifted = aShifted;
        }


        @Override
        public long getRowCount()
        {
            return view.getRowCount();
        }


        private long row(long aRow)
        {
            return shifted ? (aRow + 1) % view.getRowCount() : aRow;
        }


        @Override
        public @Nullable Object getValue(long aRow)
        {
            return view.getValue(row(aRow), column);
        }


        @Override
        public IDataValue getDataValue(long aRow)
        {
            return view.getDataValue(row(aRow), column);
        }
    }

    /**
     * Builds (or takes from the dataset's cache) the view of one execution, or answers {@code null}
     * when the execution stays on the legacy arm.
     *
     * @param rule
     *            the rule (its {@code Match_Datasets} and {@code Supp_Merge})
     * @param primary
     *            the table the rule evaluates — the dataset, or its {@code Child} pre-merge
     * @param original
     *            the dataset as resolved (the cache key when {@code primary} is it)
     * @param resolver
     *            the run's resolver
     * @param joinCache
     *            the dataset's cache, or {@code null} to build uncached
     * @param ruleId
     *            for messages
     * @param domainPrefix
     *            the dataset's domain code, the SUPP name's fallback
     * @return the built view, or {@code null} for the legacy arm
     * @throws net.cumba.datatable.merge.MergeException
     *             when the datatable refuses the merge (mapped to the rule's ERROR)
     */
    static @Nullable Built build(Rule rule, IDataTable primary, IDataTable original,
            DatasetResolver resolver, @Nullable JoinCache joinCache, @Nullable String ruleId,
            @Nullable String domainPrefix)
    {
        if (legacyForTests || resolver == null || legacyArmed(rule.getMatchDatasets()))
        {
            // (a hand-built execution without a resolver has nothing to merge)
            return null;
        }
        List<KeyedMergeSpec> keyed = new ArrayList<>();
        if (!keyedSpecs(rule, primary, resolver, ruleId, keyed, rule.isSuppMergeEnabled()))
        {
            return null; // a chained qualified key: composed by the engine until its wave
        }
        String dataset = Objects.requireNonNullElse(primary.getMetaData().getName(), "");
        String domain = null;
        List<String> supp = List.of();
        if (rule.isSuppMergeEnabled() && !dataset.isEmpty() && !neverPivoted(dataset))
        {
            domain = DatasetIdentity.domainOfDataset(dataset, resolver);
            if (domain != null && !domain.isEmpty())
            {
                // the IG's names for the primary (its own SUPP and its domain's, P-7), kept only
                // where a dataset resolves and is not the primary itself: a SUPP merge is asked
                // of the view only when there is a supplemental dataset to merge
                supp = new ArrayList<>(2);
                for (String name : SuppMergeSpec.defaultSuppNames(dataset, domain))
                {
                    IDataTable t = SplitDomainResolution.resolveTableOrThrow(resolver, name,
                            ruleId);
                    if (t != null && t != primary)
                    {
                        supp.add(name);
                    }
                }
            }
        }
        if (keyed.isEmpty() && supp.isEmpty())
        {
            // nothing to merge: the legacy arm expands nothing, joins nothing and pivots nothing,
            // so the execution is the same on either and no empty view is built
            return null;
        }
        String primaryDomain = domain != null ? domain : domainPrefix;
        // Supp_Keys (MET #18): the authored key, else the IG's (SuppMergeSpec.DEFAULT_KEYS)
        List<KeyColumn> suppKeys = rule.suppKeyColumns();
        SuppMergeSpec suppSpec = supp.isEmpty() ? null
                : SuppMergeSpec.builder().suppDatasets(supp).keys(suppKeys).build();
        MergeRequest request = request(primary, keyed, suppSpec, primaryDomain, resolver, ruleId);
        if (joinCache != null && primary == original)
        {
            return joinCache.mergedView(new ViewKey(keyed, supp, suppKeys),
                    () -> construct(primary, request, true));
        }
        return construct(primary, request, false);
    }


    /**
     * Whether a rule runs today's pipeline rather than the merged view, whatever the data: it
     * carries a forward RELREC entry (W5; MQ11's ERROR when combined with a key expansion) or a
     * keyed entry with a chained qualified key ({@code DM.RPATHCD}, composed by the engine; the
     * datatable refuses it). The one statement of the legacy arm's population, read by
     * {@link #build} and by the loader's gates on the fields only the view serves
     * ({@code Cardinality}, {@code Supp_Keys} — {@code RulePackageLoader.checkCardinality} /
     * {@code checkSuppKeys}).
     *
     * @param entries
     *            the rule's {@code Match_Datasets}, possibly {@code null}
     * @return whether the rule stays on the legacy arm
     */
    public static boolean legacyArmed(@Nullable List<MatchDataset> entries)
    {
        if (entries == null)
        {
            return false;
        }
        if (RelrecRowExpander.findForwardRelrec(entries) != null)
        {
            return true;
        }
        for (MatchDataset md : entries)
        {
            if (md != null && servedAsKeyedPart(md) && md.hasQualifiedKeys())
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Whether an entry becomes a keyed part of the view: named, not {@code Child: true} (W4), with
     * keys.
     *
     * @param md
     *            the entry
     * @return whether the view builds a keyed part for it
     */
    public static boolean servedAsKeyedPart(MatchDataset md)
    {
        return md.getName() != null && !Boolean.TRUE.equals(md.getChild()) && md.getKeys() != null
                && !md.getKeys().isEmpty();
    }


    private static MergeRequest request(IDataTable primary, List<KeyedMergeSpec> keyed,
            @Nullable SuppMergeSpec supp, @Nullable String primaryDomain, DatasetResolver resolver,
            @Nullable String ruleId)
    {
        return MergeRequest.builder().primary(primary).primaryDomain(primaryDomain).keyed(keyed)
                .supp(supp)
                .resolver(name -> SplitDomainResolution.resolveTableOrThrow(resolver, name, ruleId))
                .matchedFlags(true).build();
    }


    private static Built construct(IDataTable primary, MergeRequest request, boolean shared)
    {
        MergedTable view = MergedTables.build(request);
        MergeKind plant = plantForTests;
        IDataTable table = primary;
        if (view.isRowMapped())
        {
            int rows = Math.toIntExact(view.getRowCount());
            int primaryRows = Math.toIntExact(primary.getRowCount());
            IDataBufferNumeric rowMap = DataBufferFactory.get().createForRange(0,
                    Math.max(0, primaryRows - 1L));
            rowMap.setExpectedSize(rows);
            for (int i = 0; i < rows; i++)
            {
                rowMap.addValue(view.getRealRowIndex(i));
            }
            table = new RelrecExpandedTable(primary, rowMap);
        }
        Map<String, JoinLookup> lookups = new LinkedHashMap<>();
        for (MergedPart part : view.parts())
        {
            if (part.kind() == MergeKind.KEYED)
            {
                lookups.put(part.qualifier(),
                        new MergedViewLookup(view, part, plant == MergeKind.KEYED));
            }
        }
        Map<String, String> flagged = new LinkedHashMap<>();
        for (DataTableColumnMeta column : view.getMetaData().getColumns())
        {
            Object flag = column.getMetaData(MergeMeta.MERGE_ERROR);
            if (flag != null)
            {
                flagged.put(column.getName(), String.valueOf(flag));
            }
        }
        return new Built(view, table, lookups, flagged, plant == MergeKind.SUPP, shared);
    }


    /** {@code SuppPivot}'s rule: a SUPP / SQ / RELREC primary has no supplemental dataset. */
    private static boolean neverPivoted(String dataset)
    {
        String upper = dataset.toUpperCase(Locale.ROOT);
        return upper.startsWith("SUPP") || upper.startsWith("SQ") || "RELREC".equals(upper);
    }


    /**
     * Fills {@code out} with one spec per keyed entry whose partner resolves, running the engine's
     * own key gates first (a malformed sided key, the D4 key-type identity, a degenerate key — the
     * same exceptions and messages the expander raised); answers {@code false} when an entry
     * carries a chained qualified key.
     */
    private static boolean keyedSpecs(Rule rule, IDataTable primary, DatasetResolver resolver,
            @Nullable String ruleId, List<KeyedMergeSpec> out, boolean suppMerge)
    {
        List<KeyColumn> suppKeys = rule.suppKeyColumns();
        List<MatchDataset> entries = rule.getMatchDatasets();
        if (entries == null)
        {
            return true;
        }
        for (MatchDataset md : entries)
        {
            String name = md.getName();
            if (name == null || !servedAsKeyedPart(md))
            {
                continue; // a Child parent (W4) or a keyless entry: no keyed part
            }
            if (md.hasQualifiedKeys())
            {
                return false;
            }
            String malformedKey = md.malformedKeyElement();
            if (malformedKey != null)
            {
                throw new MalformedSidedKeyException(name, malformedKey);
            }
            String dsName = Objects.requireNonNull(DatasetIdentity.resolveWildcard(name, primary));
            String qualifier = name.contains("--") ? dsName : name;
            IDataTable partner = SplitDomainResolution.resolveTableOrThrow(resolver, dsName,
                    ruleId);
            if (partner == null)
            {
                continue; // not supplied: no part, the dotted reads take the not-supplied default
            }
            boolean expandable = JoinKeyTypes.governedByKeyTypeCheck(md);
            if (expandable)
            {
                KeyMatchRowExpander.checkKeys(primary, partner, md);
            }
            List<String> left = Objects.requireNonNull(md.getKeys()); // servedAsKeyedPart
            List<String> right = md.getRightKeys();
            List<KeyColumn> keys = new ArrayList<>();
            for (int i = 0; i < left.size(); i++)
            {
                String r = right != null && i < right.size() ? right.get(i) : left.get(i);
                keys.add(Objects.equals(left.get(i), r) ? KeyColumn.of(left.get(i))
                        : KeyColumn.sided(left.get(i), r));
            }
            BitSet mask = md.getFilter() != null && !md.getFilter().isBlank()
                    ? MatchFilter.mask(md, partner, ruleId, resolver, null)
                    : null;
            // An entry outside the key-type check (a SUPP-named or wildcard-named partner) was a
            // first-wins lookup that never dropped a primary row, whatever Join_Type the loader
            // stamped: it keeps every row (left). An expandable entry honours its Join_Type.
            String jt = md.getJoinType();
            net.cumba.datatable.merge.JoinType joinType = !expandable
                    ? net.cumba.datatable.merge.JoinType.LEFT
                    : JoinType.isAbsent(jt) ? net.cumba.datatable.merge.JoinType.INNER
                            : net.cumba.datatable.merge.JoinType.parse(Objects.requireNonNull(jt));
            out.add(KeyedMergeSpec.builder().qualifier(qualifier).dataset(dsName).keys(keys)
                    .joinType(joinType).joinAsString(md.joinKeysAsString())
                    .keepMissings(md.keepMissingKeys()).typeChecked(expandable).partnerMask(mask)
                    .cardinality(
                            Objects.requireNonNullElse(md.cardinality(), Cardinality.MANY_TO_MANY))
                    .supp(suppMerge ? partnerSupp(partner, dsName, resolver, ruleId, suppKeys)
                            : null)
                    .build());
        }
        return true;
    }


    /**
     * MQ5 ({@code PLAN-merged-evaluation-table} W3d): under the rule's {@code Supp_Merge} a keyed
     * partner's own {@code SUPP<dataset>} / {@code SUPP<domain>} qualifiers are columns of the view
     * too, named {@code <Name>.<QNAM>} — the same IG names and the same P-7 choice as the
     * primary's; {@code null} when no supplemental dataset resolves for the partner.
     */
    private static @Nullable SuppMergeSpec partnerSupp(IDataTable partner, String dsName,
            DatasetResolver resolver, @Nullable String ruleId, @Nullable List<KeyColumn> suppKeys)
    {
        if (neverPivoted(dsName))
        {
            return null;
        }
        String domain = DatasetIdentity.domainOfDataset(dsName, resolver);
        if (domain == null || domain.isEmpty())
        {
            return null;
        }
        List<String> supp = new ArrayList<>(2);
        for (String name : SuppMergeSpec.defaultSuppNames(dsName, domain))
        {
            IDataTable t = SplitDomainResolution.resolveTableOrThrow(resolver, name, ruleId);
            if (t != null && t != partner)
            {
                supp.add(name);
            }
        }
        return supp.isEmpty() ? null
                : SuppMergeSpec.builder().suppDatasets(supp).keys(suppKeys).build();
    }


    /** The rule ERROR message of a read of a flagged column (OD-7). */
    static String flagMessage(String column, String flag)
    {
        return MERGE_ERROR_MESSAGE_PREFIX + column + ": " + flag;
    }
}
