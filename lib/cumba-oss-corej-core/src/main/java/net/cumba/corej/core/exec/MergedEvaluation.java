package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
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
import net.cumba.datatable.merge.ChildMergeSpec;
import net.cumba.datatable.merge.KeyColumn;
import net.cumba.datatable.merge.KeyDropWarning;
import net.cumba.datatable.merge.KeyedMergeSpec;
import net.cumba.datatable.merge.MergeKind;
import net.cumba.datatable.merge.MergeMeta;
import net.cumba.datatable.merge.MergeRequest;
import net.cumba.datatable.merge.MergeStandard;
import net.cumba.datatable.merge.MergedPart;
import net.cumba.datatable.merge.MergedTable;
import net.cumba.datatable.merge.MergedTables;
import net.cumba.datatable.merge.RelrecPartSpec;
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
 * <li>every keyed part is a {@link MergedViewLookup} under the entry's authored name
 * ({@link Built#lookups}, matched ignoring letter case — P-19 / MET #6), the one join map of the
 * execution ({@link EvaluationContext#joins}) — the {@link JoinLookup} readers (the compiler's
 * dotted plans, the {@code _matched_} flag, the output projection, the group readers) read the view
 * through it;</li>
 * <li>a {@code SUPP<domain>} qualifier is a view column handed out as a bare name of the record
 * ({@link Built#suppColumn}, read through {@link EvaluationContext#suppQualifier}) — the type
 * default where no row qualifies the record, and a {@link MergeErrorReadException} where the column
 * carries a {@code MERGE_ERROR} (OD-7). The SUPP sources of the primary and of every keyed partner
 * are the one chooser's ({@link SplitDomainResolution#suppMergeSources},
 * {@code PLAN-split-supp-resolution} D4 (b) / Q5 (b)): the dataset's own {@code SUPP<dataset>} plus
 * the domain-level members of its split {@code SUPP<domain>} / {@code SQAP<xx>} family —
 * {@code SUPPAE1} + {@code SUPPAE2} for {@code AE}, exactly {@code SUPPLBCH} for {@code LBCH} (D12)
 * — handed to the datatable as member <b>names</b> and answered from a pre-resolved map (D11), so
 * no member is ever fed twice;</li>
 * <li>every {@code Child: true} parent domain is a part qualified with that domain (W4, MQ1 —
 * {@link ChildEntries}), a {@link MergedViewLookup} under the domain like a keyed part, supplied on
 * the rows whose parent is that domain;</li>
 * <li>every related domain a forward RELREC entry reaches is a part qualified with that domain (W5,
 * §3.6 — {@link RelrecEntries}), the rows the cross product of the related records per relationship
 * (MET #17, OD-4); each domain's {@link MergedViewLookup} sits under the domain and the
 * {@code RELREC} entry's own under {@code RELREC}, whose {@code _matched_} is "the record has any
 * relationship" (P-14). A related domain the rule names but no relationship reaches has no part:
 * its reads take the not-supplied default and its flag is false ({@link Built#relrecDeclared},
 * P-1).</li>
 * </ul>
 *
 * <p>
 * <b>A chained qualified key</b> ({@code Keys: [DM.RPATHCD, RPHASE]} on entry {@code TP} — the
 * record side read from the row's bound {@code DM} record, {@code PLAN-rprfdy-offset-tp-join} C1)
 * is composed here (W6, the datatable's §4 contract): the view is built in <b>stages</b>, a new
 * stage at each entry carrying a qualified key, over the previous stage's merged table as its
 * primary — which carries {@code DM.RPATHCD} as a column, so the datatable keys on it directly (W1
 * fold M3). Every part keeps its column range in the final table (a stage's primary columns are the
 * previous table's, in order), the evaluation row is the final table's row, and the rows keep the
 * entry order of the retired expander (an earlier entry the major factor). The engine's own key
 * gates run first ({@link KeyGates}: where each component is ({@code REG-Q3}), the D4 kinds of the
 * source column), so a chained key's errors keep their messages. There is no other pipeline: the
 * view is the only reader of every part ({@code PLAN-merged-evaluation-table} W6 retired the
 * key-match expander, the first-wins lookups and the SUPP pivot).
 * </p>
 *
 * <p>
 * Performance: no per-row work on a cache hit — the key is the list of {@link KeyedMergeSpec}s
 * (every field that changes a merge, P-3) plus the SUPP flag; the row maps live in the datatable's
 * compact int buffers; the primary-part row map is copied into one int buffer per cached view so
 * the hot path of a primary read stays one buffer read ({@link RowMappedTable}). The {@code Child}
 * parts are cached like every other part: the pointer-token view of the primary
 * ({@link ChildEntries#withPointerTokens}) is built inside the cached construction, so the rules of
 * one signature share it.
 * </p>
 */
public final class MergedEvaluation
{

    private static volatile @Nullable MergeKind plantForTests;

    private MergedEvaluation()
    {
    }


    /**
     * Test hook — the sensitivity plant: every read of a part of {@code aKind} answers the next
     * merged row's cell, so a uniformity test that cannot see the view's reads stays green under
     * it.
     *
     * @param aKind
     *            {@link MergeKind#KEYED} or {@link MergeKind#SUPP}, or {@code null} to remove the
     *            plant.
     */
    public static void plantForTests(@Nullable MergeKind aKind)
    {
        plantForTests = aKind;
    }

    private static final System.Logger LOGGER = System.getLogger(MergedEvaluation.class.getName());

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
     * member datasets chosen for the primary (their names, {@code SUPPAE1} + {@code SUPPAE2} for a
     * split family — D11) and the authored {@code Supp_Keys} ({@code null} for the IG's).
     */
    record ViewKey(List<KeyedMergeSpec> keyed, List<String> supp,
            @Nullable List<KeyColumn> suppKeys, @Nullable ChildMergeSpec child,
            @Nullable RelrecPartSpec relrec)
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

        /** Every part but the primary, in column order, across the stages (a chained key). */
        private final List<MergedPart> parts;

        /** The QNAM set the primary's SUPP parts carry (the base stage's, M8). */
        private final java.util.Set<String> suppQnams;

        private final Map<String, String> flagged;

        private final boolean suppShifted;

        /** Whether the view is the dataset's cached one for its merge signature (P-3). */
        private final boolean shared;

        /** Whether the rule declares a forward RELREC entry (W5, P-14). */
        private final boolean relrecDeclared;

        /** Whether the view carries a {@code Child} merge (W4, MQ1): one part per parent domain. */
        private final boolean childDeclared;

        /** name → {@link ViewColumn}, the flag (a String) of a flagged column, or {@link #NONE}. */
        private final ConcurrentHashMap<String, Object> suppColumns = new ConcurrentHashMap<>();

        private static final Object NONE = new Object();

        /**
         * Package-private so a test of the readers can stand a view over its own lookups (the
         * {@code ExecCalls} fixtures); production builds through {@link MergedEvaluation#build}.
         */
        @SuppressWarnings("checkstyle:ParameterNumber")
        Built(MergedTable aView, IDataTable aTable, Map<String, JoinLookup> aLookups,
                List<MergedPart> aParts, java.util.Set<String> aSuppQnams,
                Map<String, String> aFlagged, boolean aSuppShifted, boolean aShared,
                boolean aRelrecDeclared, boolean aChildDeclared)
        {
            view = aView;
            table = aTable;
            // the one join map of the execution, matched ignoring letter case (P-19 / MET #6): a
            // dataset name is case-insensitive, upper case expected — j.S, J.s and J.S resolve to
            // the one join declared as J; two names differing only by case keep the last one
            // (Stage A refuses that shape at load, DUPLICATE_MATCH_DATASET_NAME)
            java.util.TreeMap<String, JoinLookup> joins = new java.util.TreeMap<>(
                    String.CASE_INSENSITIVE_ORDER);
            joins.putAll(aLookups);
            lookups = Collections.unmodifiableMap(joins);
            parts = List.copyOf(aParts);
            suppQnams = Collections.unmodifiableSet(new java.util.LinkedHashSet<>(aSuppQnams));
            flagged = Collections.unmodifiableMap(aFlagged);
            suppShifted = aSuppShifted;
            shared = aShared;
            relrecDeclared = aRelrecDeclared;
            childDeclared = aChildDeclared;
        }


        /**
         * Whether the rule declares a forward RELREC entry (W5, P-14): every concrete qualifier the
         * rule reads that names no entry and no part is then a related domain no relationship
         * reaches on this study — its {@code _matched_} flag is {@code false} on every row and its
         * columns read the not-supplied default (P-1), never a missing-join error.
         *
         * @return whether a declared-but-unserved qualifier is a RELREC related domain
         */
        public boolean relrecDeclared()
        {
            return relrecDeclared;
        }


        /**
         * Whether the view carries a {@code Child} merge (W4, MQ1): its parts are the parent
         * domains the primary's rows point at on this study, so a parent domain no row points at
         * has no part — its {@code _matched_} flag ("this row carries a D record") is {@code false}
         * on every row, never a missing-join error (T2 M3).
         *
         * @return whether a part-less qualifier may be a parent domain no row points at
         */
        public boolean childDeclared()
        {
            return childDeclared;
        }


        /**
         * Whether this is the dataset's cached view of its merge signature (P-3), shared by every
         * rule of the signature — {@code false} for a view built per execution (no cache). The
         * expression cache keys a merged leaf on a shared view only
         * ({@code DatasetExpressionCache.cacheableAt}).
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


        /**
         * One {@link MergedViewLookup} per keyed, {@code Child} and RELREC part, under the entry's
         * authored name (the parent domain, the related domain) — matched ignoring letter case.
         */
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
            return suppQnams;
        }


        /**
         * The part a merged column belongs to — {@code null} for a column of the primary — across
         * the stages of a chained key, whose final table carries an earlier stage's parts as
         * primary columns at the same indices.
         *
         * @param column
         *            a column of {@link #view()}
         * @return its part, or {@code null}
         */
        @Nullable
        MergedPart partOf(int column)
        {
            return partContaining(parts, column);
        }


        private Object resolveSupp(String name)
        {
            int idx = view.getColumnIndex(name);
            MergedPart part = idx < 0 ? null : partOf(idx);
            if (part == null || part.kind() != MergeKind.SUPP)
            {
                return NONE;
            }
            String flag = flagged.get(view.getMetaData().getColumn(idx).getName());
            return flag != null ? flag : new ViewColumn(view, idx, suppShifted);
        }
    }

    /** The part of {@code parts} whose column range holds {@code column}, or {@code null}. */
    static @Nullable MergedPart partContaining(List<MergedPart> parts, int column)
    {
        for (MergedPart part : parts)
        {
            if (part.contains(column))
            {
                return part;
            }
        }
        return null;
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
     * when there is nothing to merge (no resolver, no part a declared entry or SUPP dataset
     * supplies) — the execution then reads the primary alone.
     *
     * @param rule
     *            the rule (its {@code Match_Datasets} and {@code Supp_Merge})
     * @param primary
     *            the table the rule evaluates
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
     * @return the built view, or {@code null} when nothing is merged
     * @throws net.cumba.datatable.merge.MergeException
     *             when the datatable refuses the merge (mapped to the rule's ERROR)
     */
    static @Nullable Built build(Rule rule, IDataTable primary, IDataTable original,
            DatasetResolver resolver, @Nullable JoinCache joinCache, @Nullable String ruleId,
            @Nullable String domainPrefix)
    {
        return build(rule, primary, original, resolver, joinCache, ruleId, domainPrefix, null);
    }


    /**
     * {@link #build(Rule, IDataTable, IDataTable, DatasetResolver, JoinCache, String, String)}, the
     * {@code REG-Q3} key-drop WARNINGs collected into {@code keyDropWarnings} instead of logged:
     * {@code RuleRunner} logs them only once Stage B's declared skip is decided against — a rule
     * that SKIPS never ran, so it must not report a key it "ignored" (T2 review, lane 1 L6).
     *
     * @param rule
     *            the rule
     * @param primary
     *            the table the rule evaluates
     * @param original
     *            the dataset as resolved
     * @param resolver
     *            the run's resolver
     * @param joinCache
     *            the dataset's cache, or {@code null}
     * @param ruleId
     *            for messages
     * @param domainPrefix
     *            the dataset's domain code
     * @param keyDropWarnings
     *            receives each WARNING text, or {@code null} to log them here
     * @return the built view, or {@code null} when nothing is merged
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    static @Nullable Built build(Rule rule, IDataTable primary, IDataTable original,
            DatasetResolver resolver, @Nullable JoinCache joinCache, @Nullable String ruleId,
            @Nullable String domainPrefix, @Nullable List<String> keyDropWarnings)
    {
        ChildMergeSpec child = resolver == null ? null
                : ChildEntries.spec(rule.getMatchDatasets(), primary);
        RelrecPartSpec relrec = RelrecEntries.spec(rule.getMatchDatasets());
        if (resolver == null)
        {
            return null; // a hand-built execution without a resolver has nothing to merge
        }
        // D11 (PLAN-split-supp-resolution): every SUPP member chosen for the view — the primary's
        // and each keyed partner's — by name, matched ignoring case; the datatable's callback
        // answers a chosen name from here and every other name through the widened seam
        ChosenSupp chosen = new ChosenSupp();
        List<KeyedMergeSpec> keyed = new ArrayList<>();
        Map<String, IDataTable> partners = new LinkedHashMap<>();
        keyedSpecs(rule, primary, resolver, ruleId, keyed, rule.isSuppMergeEnabled(), chosen,
                partners, keyDropWarnings);
        String dataset = Objects.requireNonNullElse(primary.getMetaData().getName(), "");
        String domain = null;
        List<String> supp = List.of();
        if (rule.isSuppMergeEnabled() && !dataset.isEmpty()
                && !DatasetIdentity.hasNoSupplemental(dataset))
        {
            domain = DatasetIdentity.domainOfDataset(dataset, resolver);
            if (domain != null && !domain.isEmpty())
            {
                refuseUnloadableFamily(resolver, domain, ruleId);
                // the one chooser (D4 (b)): the primary's own SUPP and the domain-level members
                // of its family, as tables; a SUPP merge is asked of the view only when there
                // is a supplemental dataset to merge
                supp = chosen.add(SplitDomainResolution.suppMergeSources(resolver, primary, dataset,
                        domain, ruleId));
            }
        }
        if (keyed.isEmpty() && supp.isEmpty() && child == null && relrec == null)
        {
            // nothing to merge: no empty view is built, the execution reads the primary alone
            return null;
        }
        chosen.refuseCollisions(partners, resolver, ruleId);
        String primaryDomain = domain != null ? domain : domainPrefix;
        // Supp_Keys (MET #18): the authored key, else the IG's (SuppMergeSpec.DEFAULT_KEYS)
        List<KeyColumn> suppKeys = rule.suppKeyColumns();
        SuppMergeSpec suppSpec = supp.isEmpty() ? null
                : SuppMergeSpec.builder().suppDatasets(supp).keys(suppKeys).build();
        net.cumba.datatable.merge.DatasetResolver datasets = name ->
        {
            IDataTable member = chosen.get(name);
            return member != null ? member
                    : SplitDomainResolution.resolveTableOrThrow(resolver, name, ruleId);
        };
        // the run's standard (H1): the dataset's cache carries it, so the SUPP-- and RELREC
        // readings never depend on which permissible columns the study happened to include; an
        // execution without a cache keeps the inference (UNKNOWN)
        MergeStandard standard = joinCache != null ? joinCache.mergeStandard()
                : MergeStandard.UNKNOWN;
        if (joinCache != null && primary == original)
        {
            return joinCache.mergedView(new ViewKey(keyed, supp, suppKeys, child, relrec),
                    () -> construct(primary, keyed, suppSpec, child, relrec, primaryDomain,
                            standard, datasets, true));
        }
        return construct(primary, keyed, suppSpec, child, relrec, primaryDomain, standard, datasets,
                false);
    }


    /**
     * Whether an entry becomes a keyed part of the view: named, not {@code Child: true} (a parent
     * is its own part, {@link ChildEntries}), with keys.
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


    @SuppressWarnings("checkstyle:ParameterNumber")
    private static Built construct(IDataTable dataset, List<KeyedMergeSpec> keyed,
            @Nullable SuppMergeSpec supp, @Nullable ChildMergeSpec child,
            @Nullable RelrecPartSpec relrec, @Nullable String primaryDomain, MergeStandard standard,
            net.cumba.datatable.merge.DatasetResolver datasets, boolean shared)
    {
        // J5 (ChildEntries): a Child primary's IDVARVAL reads as the pointer's join token
        IDataTable primary = child == null ? dataset
                : ChildEntries.withPointerTokens(dataset, child, datasets);
        List<List<KeyedMergeSpec>> stages = stages(keyed);
        MergedTable view = MergedTables.build(MergeRequest.builder().primary(primary)
                .primaryDomain(primaryDomain).keyed(stages.get(0)).supp(supp).child(child)
                .relrec(relrec).resolver(datasets).matchedFlags(true).standard(standard).build());
        List<MergedPart> parts = new ArrayList<>(view.parts().subList(1, view.parts().size()));
        java.util.Set<String> suppQnams = view.suppQnams();
        boolean rowMapped = view.isRowMapped();
        List<MergedTable> stageViews = new ArrayList<>(stages.size());
        stageViews.add(view);
        for (int s = 1; s < stages.size(); s++)
        {
            // a chained key (W6): the next stage keys on a column of the table built so far
            // (DM.RPATHCD), which is that stage's primary; its columns keep their indices
            view = MergedTables.build(MergeRequest.builder().primary(view)
                    .primaryDomain(primaryDomain).keyed(stages.get(s)).resolver(datasets)
                    .matchedFlags(true).standard(standard).build());
            parts.addAll(view.parts().subList(1, view.parts().size()));
            rowMapped |= view.isRowMapped();
            stageViews.add(view);
        }
        MergeKind plant = plantForTests;
        IDataTable table = primary;
        if (rowMapped)
        {
            // the final table's row chains through every stage's primary part to the primary's
            // own POSITION (part 0's base-row map, MergedTable.primaryRowOf — a stage that is not
            // row-mapped answers the row itself) — never view.getRealRowIndex: that also maps
            // through a non-positional primary's own real rows (a sorted / filtered view), and
            // RowMappedTable maps the entry through the primary again (T2 L1)
            int rows = Math.toIntExact(view.getRowCount());
            int primaryRows = Math.toIntExact(primary.getRowCount());
            IDataBufferNumeric rowMap = DataBufferFactory.get().createForRange(0,
                    Math.max(0, primaryRows - 1L));
            rowMap.setExpectedSize(rows);
            for (int i = 0; i < rows; i++)
            {
                long position = i;
                for (int s = stageViews.size() - 1; s >= 0; s--)
                {
                    position = stageViews.get(s).primaryRowOf(position);
                }
                rowMap.addValue(position);
            }
            table = new RowMappedTable(primary, rowMap);
        }
        List<MergedPart> flat = List.copyOf(parts);
        java.util.function.IntFunction<@Nullable MergedPart> partOf = column -> partContaining(flat,
                column);
        Map<String, String> flagged = new LinkedHashMap<>();
        for (DataTableColumnMeta column : view.getMetaData().getColumns())
        {
            Object flag = column.getMetaData(MergeMeta.MERGE_ERROR);
            // A Child parent's flag (a pointer naming a column its parent lacks) errors no rule:
            // the rows it concerns read the not-supplied marker, exactly as the pre-merge left
            // them, and the referenced-record rules (CDISC-CG0371, FDA-/PMDA-SD0077) report them
            // — the precedent of OD-1 (serve the row; the conformance rule reports it).
            if (flag != null
                    && !MergeKind.CHILD.name().equals(column.getMetaData(MergeMeta.MERGE_KIND)))
            {
                flagged.put(column.getName(), String.valueOf(flag));
            }
        }
        Map<String, JoinLookup> lookups = new LinkedHashMap<>();
        for (MergedPart part : flat)
        {
            // a keyed partner, a Child parent domain, a RELREC related domain — and the RELREC
            // entry's own flag part (qualifier RELREC, one column: "has any relationship", P-14);
            // each reads the flag map, so a dynamic read of a flagged partner-SUPP qualifier
            // (MQ5) or RELREC-domain column errors (OD-7, T2 M4) — a CHILD column is never in it
            if (part.kind() == MergeKind.KEYED || part.kind() == MergeKind.CHILD
                    || part.kind() == MergeKind.RELREC)
            {
                lookups.put(part.qualifier(), new MergedViewLookup(view, part, partOf,
                        plant == part.kind(), Collections.unmodifiableMap(flagged)));
            }
        }
        return new Built(view, table, lookups, flat, suppQnams, flagged, plant == MergeKind.SUPP,
                shared, relrec != null, child != null);
    }


    /**
     * The stages of the keyed parts (W6, a chained key): the entries up to the first one that
     * carries a qualified left key form the base stage, and every such entry opens a new stage with
     * the entries after it — so its source (an EARLIER entry, the loader's D-SRC) is a column of
     * the stage's primary, and the entry order of the retired expander is kept.
     */
    private static List<List<KeyedMergeSpec>> stages(List<KeyedMergeSpec> keyed)
    {
        List<List<KeyedMergeSpec>> out = new ArrayList<>();
        List<KeyedMergeSpec> current = new ArrayList<>();
        for (KeyedMergeSpec spec : keyed)
        {
            if (chained(spec) && !current.isEmpty())
            {
                out.add(current);
                current = new ArrayList<>();
            }
            current.add(spec);
        }
        out.add(current);
        return out;
    }


    /** Whether a spec keys on a column of an earlier part ({@code DM.RPATHCD}). */
    private static boolean chained(KeyedMergeSpec spec)
    {
        for (KeyColumn key : spec.getKeys())
        {
            if (key.leftQualifier() != null)
            {
                return true;
            }
        }
        return false;
    }

    /**
     * The SUPP members chosen for one view (D11 of {@code PLAN-split-supp-resolution}): member name
     * → member table, matched ignoring letter case, for the primary and every keyed partner. The
     * datatable resolves a {@code suppDatasets} name through the view's callback; answering it from
     * here — never from the seam, which answers a family name with the union of every member — is
     * what keeps an exactly-named {@code SUPPAE} shipped beside {@code SUPPAE1} from being fed
     * twice.
     */
    static final class ChosenSupp
    {

        private final java.util.TreeMap<String, IDataTable> byName = new java.util.TreeMap<>(
                String.CASE_INSENSITIVE_ORDER);

        /**
         * Registers the chooser's members and answers their names, in feeding order.
         *
         * @param members
         *            the chosen member tables
         * @return their names
         * @throws net.cumba.datatable.merge.MergeException
         *             two chosen members whose names differ by case alone — the datatable could
         *             serve only one of them under the name
         */
        List<String> add(List<IDataTable> members)
        {
            List<String> names = new ArrayList<>(members.size());
            for (IDataTable member : members)
            {
                String name = Objects.requireNonNullElse(member.getMetaData().getName(), "");
                IDataTable earlier = byName.putIfAbsent(name, member);
                if (earlier != null && earlier != member)
                {
                    throw new net.cumba.datatable.merge.MergeException(
                            "two supplemental datasets named " + name
                                    + " (differing in letter case only) qualify this view; the"
                                    + " merge can serve only one of them under the name");
                }
                names.add(name);
            }
            return names;
        }


        /** The chosen member of that name, or {@code null}. */
        @Nullable
        IDataTable get(String name)
        {
            return byName.get(name);
        }


        /**
         * F1 of {@code PLAN-split-supp-resolution}'s confirmation look: a keyed partner whose name
         * equals a chosen member's ignoring case, where the seam answered the partner with a
         * different table (a non-{@code Child} keyed {@code SUPPAE} / {@code SUPP--} on an
         * {@code AE} that ships an exact {@code SUPPAE} beside {@code SUPPAE1}: the key gates
         * checked the family union, the datatable would get the exact member from this map and drop
         * {@code SUPPAE1}'s rows silently). Refused loudly — the rule's MERGE_REFUSED ERROR.
         *
         * @throws net.cumba.datatable.merge.MergeException
         *             the collision
         */
        void refuseCollisions(Map<String, IDataTable> partners, DatasetResolver resolver,
                @Nullable String ruleId)
        {
            for (Map.Entry<String, IDataTable> partner : partners.entrySet())
            {
                IDataTable member = byName.get(partner.getKey());
                if (member != null && member != partner.getValue())
                {
                    throw new net.cumba.datatable.merge.MergeException("the Match_Datasets partner "
                            + partner.getKey() + " resolves to the supplemental family (every "
                            + "member of " + partner.getKey() + ") while the SUPP merge feeds "
                            + "its member " + member.getMetaData().getName()
                            + " under that name; one name cannot serve both — name the member"
                            + " or declare Supp_Merge: false");
                }
            }
        }
    }

    /**
     * Fills {@code out} with one spec per keyed entry whose partner resolves, running the engine's
     * own key gates first ({@link KeyGates}: a malformed sided key, the D4 key-type identity, a
     * degenerate key — the exceptions and messages the retired expander raised). A chained
     * qualified key ({@code DM.RPATHCD}) becomes a sided key component whose left name is the
     * source part's column, composed by the stages of {@link #construct}.
     *
     * <p>
     * ⭐ <b>{@code REG-Q3}</b> (owner, 2026-10-09): a key component present on one side only leaves
     * the key with a log WARNING naming the rule — decided here, for <b>every</b> served keyed part
     * (a {@code SUPP*} / {@code SQ*} / {@code --} / {@code RELREC} named entry is a keyed part the
     * gates do not govern, review M1), once per rule and dataset, and handed to the caller's sink
     * ({@code RuleRunner} logs it once Stage B has not skipped the rule) or logged here without
     * one; the datatable, which has no rule, logs the same drop at DEBUG. A qualified component
     * that leaves the key is left out of the spec (the datatable refuses a chained key whose record
     * side it cannot see); an entry whose only qualified component left therefore joins in the
     * stage before it, on its plain components.
     * </p>
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    private static void keyedSpecs(Rule rule, IDataTable primary, DatasetResolver resolver,
            @Nullable String ruleId, List<KeyedMergeSpec> out, boolean suppMerge, ChosenSupp chosen,
            Map<String, IDataTable> partners, @Nullable List<String> keyDropWarnings)
    {
        List<KeyColumn> suppKeys = rule.suppKeyColumns();
        List<MatchDataset> entries = rule.getMatchDatasets();
        if (entries == null)
        {
            return;
        }
        // the resolved partner of every ordinary keyed entry so far — a chained key's source
        IdentityHashMap<MatchDataset, IDataTable> resolved = new IdentityHashMap<>();
        for (MatchDataset md : entries)
        {
            String name = md.getName();
            if (name == null || !servedAsKeyedPart(md))
            {
                continue; // a Child parent (its own part) or a keyless entry: no keyed part
            }
            String malformedKey = md.malformedKeyElement();
            if (malformedKey != null)
            {
                throw new MalformedSidedKeyException(name, malformedKey);
            }
            // D6 / D10: SUPP-- / SQAP-- is the supplemental family of the primary's domain
            String dsName = Objects
                    .requireNonNull(DatasetIdentity.resolveDatasetWildcard(name, primary));
            String qualifier = name.contains("--") ? dsName : name;
            IDataTable partner = SplitDomainResolution.resolveTableOrThrow(resolver, dsName,
                    ruleId);
            if (partner == null)
            {
                continue; // not supplied: no part, the dotted reads take the not-supplied default
            }
            partners.put(dsName, partner);
            if (suppMerge)
            {
                refuseSuppQualifierKey(md, name, primary, resolver, ruleId);
            }
            boolean expandable = JoinKeyTypes.governedByKeyTypeCheck(md);
            List<KeyGates.KeySide> sides;
            if (expandable)
            {
                sides = KeyGates.check(primary, partner, md, entries, resolved);
                resolved.put(md, partner);
            }
            else
            {
                sides = KeyGates.sides(primary, partner, md);
            }
            List<String> left = Objects.requireNonNull(md.getKeys()); // servedAsKeyedPart
            List<String> right = md.getRightKeys();
            List<@Nullable String> qualifiers = md.keyQualifiers();
            List<KeyColumn> keys = new ArrayList<>();
            for (int i = 0; i < left.size(); i++)
            {
                String r = right != null && i < right.size() ? right.get(i) : left.get(i);
                KeyGates.KeySide side = sides.get(i);
                if (side.oneSideOnly())
                {
                    String warning = "[" + Objects.requireNonNullElse(ruleId, "?")
                            + "] Match_Datasets " + name + ": "
                            + KeyDropWarning.format(dsName, KeyDropWarning.keyText(left.get(i), r),
                                    side.onRecordSide() ? side.recordDataset() : dsName);
                    if (keyDropWarnings != null)
                    {
                        keyDropWarnings.add(warning);
                    }
                    else
                    {
                        LOGGER.log(System.Logger.Level.WARNING, warning);
                    }
                }
                if (!side.joins() && i < qualifiers.size() && qualifiers.get(i) != null)
                {
                    continue; // REG-Q3: a dropped qualified component never reaches the datatable
                }
                keys.add(Objects.equals(left.get(i), r) ? KeyColumn.of(left.get(i))
                        : KeyColumn.sided(left.get(i), r));
            }
            BitSet mask = md.getFilter() != null && !md.getFilter().isBlank()
                    ? MatchFilter.mask(md, partner, ruleId, resolver, null)
                    : null;
            // An entry outside the key-type check (a SUPP-named or wildcard-named partner) was a
            // first-wins lookup that never dropped a primary row, whatever Join_Type the loader
            // stamped: it keeps every row (left). An expandable entry honours its Join_Type. An
            // AUTHORED inner on a keyed SUPP* / SQ* / RELREC entry is refused at load
            // (RulePackageLoader.checkJoinTypeOnLeftOnlyEntry, PLAN-silently-ignored-authoring-
            // inputs E4), so the LEFT below never overrides what an author wrote.
            // ⚠ The CARDINALITY of such an entry (SUPP- / SQ / -- named, a keyed RELREC) did
            // change: the retired lookup took the FIRST matching partner row; the view expands
            // n:m like every keyed part (Cardinality unset ⇒ MANY_TO_MANY), so two partner rows
            // on one key give the record two rows. Recorded, not refused (T2 L2): the population
            // is 0 in both corpora (plan §2.5, "first match wins is retired"), and an author who
            // means one partner row declares Cardinality n:1 and the view checks it (P-16).
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
                    .supp(suppMerge
                            ? partnerSupp(partner, dsName, resolver, ruleId, suppKeys, chosen)
                            : null)
                    .build());
        }
    }


    /**
     * A left key the primary lacks but one of its SUPP sources (the one chooser's members,
     * {@link SplitDomainResolution#suppMergeSources} — its {@code SUPP<dataset>} and the
     * domain-level members of its family) supplies as a qualifier ({@code QNAM}) is refused — the
     * rule's ERROR under the merged table's prefix ({@link #MERGE_REFUSED_MESSAGE_PREFIX}), never
     * served: the datatable keys the raw primary before the SUPP parts exist, so the key would be
     * absent from the primary and leave the join ({@code REG-Q3}'s one-side drop) — a silent
     * fan-out over the rest of the key, where the author named a real qualifier. A QNAM is not an
     * absent column, so the refusal stays. Serving it is a datatable change (both twins), assessed
     * in the plan's T1. Costs nothing unless a key is absent from the primary (corpus exposure 0,
     * W3f).
     */
    private static void refuseSuppQualifierKey(MatchDataset md, String name, IDataTable primary,
            DatasetResolver resolver, @Nullable String ruleId)
    {
        String dataset = Objects.requireNonNullElse(primary.getMetaData().getName(), "");
        List<String> left = md.getKeys();
        if (left == null || dataset.isEmpty() || DatasetIdentity.hasNoSupplemental(dataset))
        {
            return;
        }
        List<IDataTable> sources = null;
        for (String key : left)
        {
            if (key == null || primary.getMetaData().getColumnIndex(key) >= 0)
            {
                continue;
            }
            if (sources == null)
            {
                String domain = DatasetIdentity.domainOfDataset(dataset, resolver);
                sources = domain == null || domain.isEmpty() ? List.of()
                        : SplitDomainResolution.suppMergeSources(resolver, primary, dataset, domain,
                                ruleId);
            }
            for (IDataTable supp : sources)
            {
                if (carriesQnam(supp, key))
                {
                    throw new net.cumba.datatable.merge.MergeException("the key " + key
                            + " of the Match_Datasets entry " + name + " is not a column of "
                            + dataset + " but a supplemental qualifier (QNAM) of "
                            + supp.getMetaData().getName()
                            + " — a SUPP qualifier cannot key a join yet, so the key would be"
                            + " dropped from the join (a key column the dataset lacks) and the"
                            + " join would run on the rest");
                }
            }
        }
    }


    /** Whether a supplemental dataset carries a row whose {@code QNAM} is {@code qnam}. */
    private static boolean carriesQnam(IDataTable supp, String qnam)
    {
        int col = supp.getMetaData().getColumnIndex("QNAM");
        if (col < 0)
        {
            return false;
        }
        IDataTableColumn column = supp.getColumn(col);
        long rows = supp.getRowCount();
        for (long r = 0; r < rows; r++)
        {
            IDataValue dv = column.getDataValue(r);
            if (!dv.isMissingOrInvalid() && qnam.equalsIgnoreCase(dv.getValueAsString().strip()))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * MQ5 ({@code PLAN-merged-evaluation-table} W3d): under the rule's {@code Supp_Merge} a keyed
     * partner's own SUPP qualifiers are columns of the view too, named {@code <Name>.<QNAM>} — the
     * same chooser as the primary's ({@link SplitDomainResolution#suppMergeSources}: its
     * {@code SUPP<dataset>} and the domain-level members of its family; a partner that resolves as
     * a split union — {@code LB} on {@code lbch} / {@code lbhe} / {@code lbur} — contains every
     * member and is fed the whole family, D12), registered in the view's chosen members (D11);
     * {@code null} when no supplemental dataset resolves for the partner.
     */
    private static @Nullable SuppMergeSpec partnerSupp(IDataTable partner, String dsName,
            DatasetResolver resolver, @Nullable String ruleId, @Nullable List<KeyColumn> suppKeys,
            ChosenSupp chosen)
    {
        if (DatasetIdentity.hasNoSupplemental(dsName))
        {
            return null;
        }
        String domain = DatasetIdentity.domainOfDataset(dsName, resolver);
        if (domain == null || domain.isEmpty())
        {
            return null;
        }
        refuseUnloadableFamily(resolver, domain, ruleId);
        List<String> supp = chosen.add(
                SplitDomainResolution.suppMergeSources(resolver, partner, dsName, domain, ruleId));
        return supp.isEmpty() ? null
                : SuppMergeSpec.builder().suppDatasets(supp).keys(suppKeys).build();
    }


    /**
     * D3 for a family a member of which cannot be loaded ({@code PLAN-split-supp-resolution} review
     * L2; B5 W1 fold M5): the view is refused — MERGE_REFUSED on every rule whose view feeds that
     * family — never built over the members that happened to load.
     *
     * @throws net.cumba.datatable.merge.MergeException
     *             the refusal
     */
    private static void refuseUnloadableFamily(DatasetResolver resolver, String domain,
            @Nullable String ruleId)
    {
        String failure = SplitDomainResolution.suppFamilyLoadFailure(resolver, domain, ruleId);
        if (failure != null)
        {
            throw new net.cumba.datatable.merge.MergeException(failure);
        }
    }


    /** The rule ERROR message of a read of a flagged column (OD-7). */
    static String flagMessage(String column, String flag)
    {
        return MERGE_ERROR_MESSAGE_PREFIX + column + ": " + flag;
    }
}
