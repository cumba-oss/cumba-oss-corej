package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import net.cumba.corej.core.model.JoinType;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.AbstractDataTableColumn;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.impl.view.MergeDataTable;
import net.cumba.datatable.impl.view.ViewDataTable;
import net.cumba.datatable.merge.KeyColumn;
import net.cumba.datatable.merge.KeyedMergeSpec;
import net.cumba.datatable.merge.MergeKind;
import net.cumba.datatable.merge.MergeMeta;
import net.cumba.datatable.merge.MergeRequest;
import net.cumba.datatable.merge.MergedPart;
import net.cumba.datatable.merge.MergedTable;
import net.cumba.datatable.merge.MergedTables;
import net.cumba.datatable.merge.SuppMergeSpec;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * The W2 <b>shadow</b> of {@code PLAN-merged-evaluation-table}: the merged evaluation view
 * ({@code net.cumba.datatable.merge}, QUALIFIED naming) built <em>beside</em> today's join pipeline
 * for the keyed and the SUPP parts, and every read the rule makes through a {@link JoinLookup} or
 * the {@link SuppPivot} compared with the merged column at the same (real row, part row).
 *
 * <p>
 * <b>Off unless a test installs an {@link Observer}.</b> With none installed {@link #open} answers
 * {@code null} after one volatile read, {@code RuleRunner} keeps its own join map, the context
 * carries no recorder and {@code SuppPivot} pays one null check — so the shadow changes no finding
 * and costs nothing in a production run. With one installed the shadow still changes no finding:
 * every recorded read hands back the value today's reader produced; the merged view is only read to
 * compare.
 * </p>
 *
 * <p>
 * <b>Scope (W2).</b> Keyed {@code Match_Datasets} entries (expanded by {@code KeyMatchRowExpander}
 * or served by a first-wins {@link DatasetLookup}) and the primary's {@code SUPP<domain>}
 * qualifiers. An execution with a forward RELREC entry (W5) or a chained, qualified key (composed
 * by the engine, refused by the datatable) is reported as not shadowed; a {@code Child: true} entry
 * (W4) is left out of the view and its pre-merged columns are not compared.
 * </p>
 *
 * <p>
 * Deleted in W6 together with the readers it compares.
 * </p>
 */
public final class MergedTableShadow
{

    /** The divergence classes the shadow can report. */
    public enum Kind
    {
        /** The merged view could not be built where today's pipeline evaluated the rule. */
        BUILD_REFUSED,
        /** Today's lookup has no merged part to compare with. */
        PART_MISSING,
        /** The evaluation row set (real row × keyed part rows) differs. */
        ROW_SET,
        /** A read's (real row, part row) has no merged row. */
        ROW_ABSENT,
        /** A typed keyed read differs from the merged cell. */
        KEYED_VALUE,
        /** A text keyed read differs from the merged cell. */
        KEYED_TEXT,
        /** A keyed column-existence answer differs from the merged schema. */
        KEYED_COLUMN,
        /** A keyed declared type differs from the merged column's. */
        KEYED_TYPE,
        /** A {@code _matched_} answer differs from the merged flag. */
        KEYED_MATCHED,
        /** A SUPP qualifier read differs from the merged qualifier column. */
        SUPP_VALUE,
        /** A SUPP qualifier exists on one side only. */
        SUPP_EXISTS,
        /** The merged qualifier column carries a {@code MERGE_ERROR} (§3.5.3, ERROR (QNAM)). */
        SUPP_MERGE_ERROR
    }


    /**
     * One divergence: what diverged, in which part and column, at which real row and part row, and
     * the two answers rendered.
     */
    public record Divergence(Kind kind, String part, String column, long realRow, long partRow,
            String today, String merged)
    {
    }


    /**
     * One rule execution's shadow verdict.
     *
     * @param ruleId
     *            the rule
     * @param dataset
     *            the primary dataset
     * @param notShadowed
     *            why the execution was not shadowed, or {@code null}
     * @param keyedReads
     *            reads through a {@code JoinLookup} compared with the merged view
     * @param suppReads
     *            reads through the {@code SuppPivot} compared with the merged view
     * @param readsUncompared
     *            reads against a table other than the evaluation table (derived contexts)
     * @param counts
     *            divergences per class
     * @param examples
     *            the first divergences of each class (capped)
     * @param merged
     *            the merged view, or {@code null} when none was built
     * @param primary
     *            the primary table
     * @param resolver
     *            the run's resolver (the evidence a divergence is classified by)
     */
    public record Report(@Nullable String ruleId, String dataset, @Nullable String notShadowed,
            long keyedReads, long suppReads, long readsUncompared, Map<Kind, Long> counts,
            List<Divergence> examples, @Nullable MergedTable merged, IDataTable primary,
            DatasetResolver resolver)
    {

        /** Copies the collections, so a report is immutable. */
        public Report
        {
            counts = Map.copyOf(counts);
            examples = List.copyOf(examples);
        }


        /** The number of reads compared, both kinds. */
        public long readsCompared()
        {
            return keyedReads + suppReads;
        }


        /** The number of divergences, every class summed. */
        public long divergences()
        {
            return counts.values().stream().mapToLong(Long::longValue).sum();
        }
    }


    /** Receives every shadowed (and every declined) execution. */
    public interface Observer
    {

        /**
         * One execution's verdict, delivered when the execution ends.
         *
         * @param report
         *            the verdict
         */
        void onExecution(Report report);


        /**
         * The table the shadow reads merged <b>values</b> from — the merged view itself unless a
         * sensitivity control substitutes a sabotaged one (row structure is always read from the
         * real view, so a sabotaged row map shows as value divergences).
         *
         * @param merged
         *            the merged view
         * @return the table values are read from
         */
        default IDataTable valueView(MergedTable merged)
        {
            return merged;
        }
    }

    /** At most this many examples are kept per class and execution. */
    private static final int EXAMPLES_PER_KIND = 20;

    private static volatile @Nullable Observer observer;

    private MergedTableShadow()
    {
    }


    /**
     * Installs (or with {@code null} removes) the observer. Test use only: while one is installed
     * every rule execution builds a merged view beside today's pipeline.
     *
     * @param anObserver
     *            the observer, or {@code null} to switch the shadow off
     */
    public static void install(@Nullable Observer anObserver)
    {
        observer = anObserver;
    }


    /**
     * Opens the shadow of one execution, or answers {@code null} — at the cost of one volatile read
     * — when no observer is installed.
     */
    @SuppressWarnings("checkstyle:ParameterNumber")
    static @Nullable Session open(Rule rule, IDataTable primary, IDataTable evalTable,
            KeyMatchRowExpander.@Nullable KeyMatchExpansion keyExpansion, boolean forwardRelrec,
            Map<String, JoinLookup> joined, DatasetResolver resolver, @Nullable String ruleId,
            @Nullable String domainPrefix)
    {
        Observer o = observer;
        if (o == null)
        {
            return null;
        }
        String dataset = Objects.requireNonNullElse(primary.getMetaData().getName(), "");
        String declined = forwardRelrec ? "forward RELREC (W5)" : null;
        List<KeyedMergeSpec> keyed = new ArrayList<>();
        Set<String> unknownPartRows = new LinkedHashSet<>();
        if (declined == null)
        {
            declined = keyedSpecs(rule, primary, resolver, ruleId, keyed, unknownPartRows);
        }
        SuppMergeSpec supp = null;
        String domain = null;
        if (declined == null && rule.isSuppMergeEnabled() && !dataset.isEmpty())
        {
            domain = DatasetIdentity.domainOfDataset(dataset, resolver);
            if (domain != null && !domain.isEmpty())
            {
                supp = SuppMergeSpec.builder().build();
            }
        }
        if (declined == null && keyed.isEmpty() && supp == null)
        {
            declined = "no keyed or SUPP part";
        }
        if (declined != null)
        {
            o.onExecution(new Report(ruleId, dataset, declined, 0, 0, 0, Map.of(), List.of(), null,
                    primary, resolver));
            return null;
        }
        Session s = new Session(o, ruleId, dataset, primary, evalTable, resolver);
        MergeRequest request = MergeRequest.builder().primary(primary)
                .primaryDomain(domain != null ? domain : domainPrefix).keyed(keyed).supp(supp)
                .resolver(name -> SplitDomainResolution.resolveTableOrThrow(resolver, name, ruleId))
                .matchedFlags(true).build();
        try
        {
            s.attach(MergedTables.build(request), joined, keyExpansion, unknownPartRows);
        }
        catch (RuntimeException e)
        {
            s.diverge(new Divergence(Kind.BUILD_REFUSED, "", "", -1, -1, "evaluated",
                    e.getClass().getSimpleName() + ": " + e.getMessage()));
        }
        return s;
    }


    /**
     * Fills {@code out} with one spec per keyed entry today's pipeline serves through a lookup;
     * answers why the execution cannot be shadowed, or {@code null}.
     */
    private static @Nullable String keyedSpecs(Rule rule, IDataTable primary,
            DatasetResolver resolver, @Nullable String ruleId, List<KeyedMergeSpec> out,
            Set<String> unknownPartRows)
    {
        List<MatchDataset> entries = rule.getMatchDatasets();
        if (entries == null)
        {
            return null;
        }
        for (MatchDataset md : entries)
        {
            String name = md.getName();
            if (name == null || Boolean.TRUE.equals(md.getChild()) || md.getKeys() == null
                    || md.getKeys().isEmpty())
            {
                continue; // W4 (Child) or keyless: no keyed part
            }
            if (md.hasQualifiedKeys())
            {
                return "chained qualified key (composed by the engine)";
            }
            String dsName = Objects.requireNonNull(DatasetIdentity.resolveWildcard(name, primary));
            String qualifier = name.contains("--") ? dsName : name;
            List<String> left = md.getKeys();
            List<String> right = md.getRightKeys();
            List<KeyColumn> keys = new ArrayList<>();
            for (int i = 0; i < left.size(); i++)
            {
                String r = right != null && i < right.size() ? right.get(i) : left.get(i);
                keys.add(Objects.equals(left.get(i), r) ? KeyColumn.of(left.get(i))
                        : KeyColumn.sided(left.get(i), r));
            }
            String jt = md.getJoinType();
            BitSet mask = null;
            boolean expandable = JoinKeyTypes.governedByKeyTypeCheck(md);
            if (md.getFilter() != null && !md.getFilter().isBlank())
            {
                IDataTable partner = SplitDomainResolution.resolveTableOrThrow(resolver, dsName,
                        ruleId);
                if (partner != null)
                {
                    mask = MatchFilter.mask(md, partner, ruleId, resolver, null);
                }
                if (!expandable)
                {
                    // the first-wins lookup is built over a filtered COPY: its rows are not the
                    // partner's, so its reads compare by real row only
                    unknownPartRows.add(qualifier.toLowerCase(Locale.ROOT));
                }
            }
            out.add(KeyedMergeSpec.builder().qualifier(qualifier).dataset(dsName).keys(keys)
                    .joinType(JoinType.isAbsent(jt) ? net.cumba.datatable.merge.JoinType.INNER
                            : net.cumba.datatable.merge.JoinType.parse(Objects.requireNonNull(jt)))
                    .joinAsString(md.joinKeysAsString()).keepMissings(md.keepMissingKeys())
                    .typeChecked(expandable).partnerMask(mask).build());
        }
        return null;
    }


    /** Two typed values agree: the same missing marker, or the same type and value. */
    static boolean same(IDataValue a, IDataValue b)
    {
        if (a == b)
        {
            return true;
        }
        Object va = a.getValue();
        Object vb = b.getValue();
        if (va instanceof MissingValue || vb instanceof MissingValue)
        {
            return va == vb;
        }
        return a.getType() == b.getType() && Objects.equals(va, vb);
    }


    private static String render(@Nullable IDataValue v)
    {
        return v == null ? "null" : v.getType() + ":" + v.getValue();
    }

    /** One merged part a lookup's reads are compared with. */
    private static final class Part
    {

        private final MergedPart part;

        private final @Nullable ViewDataTable rows;

        private final boolean partRowsKnown;

        private @Nullable Map<Long, Integer> byRealAndPartRow;

        private Part(MergedPart aPart, @Nullable ViewDataTable aRows, boolean aPartRowsKnown)
        {
            part = aPart;
            rows = aRows;
            partRowsKnown = aPartRowsKnown;
        }


        long partRow(long merged)
        {
            ViewDataTable r = rows;
            return r == null ? merged : r.getBaseRowIndex(merged);
        }
    }


    /** The shadow of one rule execution. */
    static final class Session
    {

        private final Observer observer;

        private final @Nullable String ruleId;

        private final String dataset;

        private final IDataTable primary;

        private final IDataTable evalTable;

        private final DatasetResolver resolver;

        private final Map<Kind, LongAdder> counts = new ConcurrentHashMap<>();

        private final Map<Kind, List<Divergence>> examples = new ConcurrentHashMap<>();

        private final LongAdder keyedCompared = new LongAdder();

        private final LongAdder suppCompared = new LongAdder();

        private final LongAdder uncompared = new LongAdder();

        private final Set<String> erroredQnams = ConcurrentHashMap.newKeySet();

        private @Nullable MergedTable merged;

        private @Nullable IDataTable view;

        private final Map<String, Part> parts = new HashMap<>();

        private @Nullable Map<String, JoinLookup> joined;

        private long @Nullable [] mergedReal;

        private @Nullable Map<Long, Integer> firstMergedOfReal;

        private Session(Observer anObserver, @Nullable String aRuleId, String aDataset,
                IDataTable aPrimary, IDataTable anEvalTable, DatasetResolver aResolver)
        {
            observer = anObserver;
            ruleId = aRuleId;
            dataset = aDataset;
            primary = aPrimary;
            evalTable = anEvalTable;
            resolver = aResolver;
        }


        private void attach(MergedTable aMerged, Map<String, JoinLookup> aJoined,
                KeyMatchRowExpander.@Nullable KeyMatchExpansion keyExpansion,
                Set<String> unknownPartRows)
        {
            merged = aMerged;
            view = observer.valueView(aMerged);
            MergeDataTable impl = aMerged instanceof MergeDataTable m ? m : null;
            for (MergedPart p : aMerged.parts())
            {
                if (p.kind() != MergeKind.KEYED)
                {
                    continue;
                }
                ViewDataTable rows = null;
                if (impl != null && p.columnCount() > 0
                        && impl.part(impl.partOfColumn(p.firstColumn())) instanceof ViewDataTable v)
                {
                    rows = v;
                }
                String key = p.qualifier().toLowerCase(Locale.ROOT);
                parts.put(key, new Part(p, rows, !unknownPartRows.contains(key)));
            }
            Map<String, JoinLookup> wrapped = new LinkedHashMap<>();
            for (Map.Entry<String, JoinLookup> e : aJoined.entrySet())
            {
                Part p = parts.get(e.getKey().toLowerCase(Locale.ROOT));
                if (p == null)
                {
                    diverge(new Divergence(Kind.PART_MISSING, e.getKey(), "", -1, -1,
                            "lookup " + e.getValue().getClass().getSimpleName(), "no part"));
                    wrapped.put(e.getKey(), e.getValue());
                }
                else
                {
                    wrapped.put(e.getKey(), new RecordingLookup(e.getValue(), p));
                }
            }
            joined = wrapped;
            compareRowSets(aJoined, keyExpansion);
        }


        /** The join map the context carries: every lookup wrapped by its recorder. */
        Map<String, JoinLookup> joinedDatasets(Map<String, JoinLookup> fallback)
        {
            Map<String, JoinLookup> j = joined;
            return j != null ? j : fallback;
        }


        void diverge(Divergence d)
        {
            counts.computeIfAbsent(d.kind(), _ -> new LongAdder()).increment();
            List<Divergence> list = examples.computeIfAbsent(d.kind(),
                    _ -> Collections.synchronizedList(new ArrayList<>()));
            if (list.size() < EXAMPLES_PER_KIND)
            {
                list.add(d);
            }
        }


        /** Delivers the verdict; called once when the execution ends. */
        void close()
        {
            Map<Kind, Long> c = new EnumMap<>(Kind.class);
            counts.forEach((k, v) -> c.put(k, v.sum()));
            List<Divergence> ex = new ArrayList<>();
            for (Kind k : Kind.values())
            {
                List<Divergence> l = examples.get(k);
                if (l != null)
                {
                    synchronized (l)
                    {
                        ex.addAll(l);
                    }
                }
            }
            observer.onExecution(new Report(ruleId, dataset, null, keyedCompared.sum(),
                    suppCompared.sum(), uncompared.sum(), c, ex, merged, primary, resolver));
        }

        // ---- row structure


        private synchronized long[] mergedReal()
        {
            long[] r = mergedReal;
            if (r == null)
            {
                MergedTable m = Objects.requireNonNull(merged);
                int n = Math.toIntExact(m.getRowCount());
                r = new long[n];
                for (int i = 0; i < n; i++)
                {
                    r[i] = m.getRealRowIndex(i);
                }
                mergedReal = r;
            }
            return r;
        }


        private synchronized int firstMergedRow(long real)
        {
            Map<Long, Integer> f = firstMergedOfReal;
            if (f == null)
            {
                f = new HashMap<>();
                long[] r = mergedReal();
                for (int i = 0; i < r.length; i++)
                {
                    f.putIfAbsent(r[i], i);
                }
                firstMergedOfReal = f;
            }
            return f.getOrDefault(real, -1);
        }


        private synchronized int mergedRow(Part p, long real, long partRow)
        {
            Map<Long, Integer> idx = p.byRealAndPartRow;
            if (idx == null)
            {
                idx = new HashMap<>();
                long[] r = mergedReal();
                for (int i = 0; i < r.length; i++)
                {
                    idx.putIfAbsent(pairKey(r[i], p.partRow(i)), i);
                }
                p.byRealAndPartRow = idx;
            }
            return idx.getOrDefault(pairKey(real, partRow), -1);
        }


        private static long pairKey(long real, long partRow)
        {
            return (real << 32) ^ (partRow + 1);
        }


        /** The merged rows of one real row (the reads of a lookup whose part rows are unknown). */
        private List<Integer> mergedRowsOf(long real)
        {
            List<Integer> out = new ArrayList<>();
            long[] r = mergedReal();
            for (int i = 0; i < r.length; i++)
            {
                if (r[i] == real)
                {
                    out.add(i);
                }
            }
            return out;
        }


        /**
         * The evaluation row sets: today's rows (real row × the part row of every lookup) against
         * the merged rows, as multisets.
         */
        private void compareRowSets(Map<String, JoinLookup> aJoined,
                KeyMatchRowExpander.@Nullable KeyMatchExpansion keyExpansion)
        {
            List<String> keys = new ArrayList<>();
            List<JoinLookup> lookups = new ArrayList<>();
            List<Part> ps = new ArrayList<>();
            for (Map.Entry<String, JoinLookup> e : aJoined.entrySet())
            {
                Part p = parts.get(e.getKey().toLowerCase(Locale.ROOT));
                if (p != null && p.partRowsKnown)
                {
                    keys.add(e.getKey());
                    lookups.add(e.getValue());
                    ps.add(p);
                }
            }
            MergedTable m = Objects.requireNonNull(merged);
            int todayRows = Math.toIntExact(evalTable.getRowCount());
            int mergedRows = Math.toIntExact(m.getRowCount());
            Map<String, Integer> diff = new TreeMap<>();
            for (int i = 0; i < todayRows; i++)
            {
                StringBuilder t = new StringBuilder().append(evalTable.getRealRowIndex(i));
                for (JoinLookup l : lookups)
                {
                    t.append('|').append(boundRow(l, i));
                }
                diff.merge(t.toString(), 1, Integer::sum);
            }
            long[] real = mergedReal();
            for (int i = 0; i < mergedRows; i++)
            {
                StringBuilder t = new StringBuilder().append(real[i]);
                for (Part p : ps)
                {
                    t.append('|').append(p.partRow(i));
                }
                diff.merge(t.toString(), -1, Integer::sum);
            }
            diff.values().removeIf(v -> v == 0);
            if (!diff.isEmpty())
            {
                Map.Entry<String, Integer> first = diff.entrySet().iterator().next();
                diverge(new Divergence(Kind.ROW_SET, String.join(",", keys),
                        keyExpansion == null ? "" : "expanded", -1, -1,
                        "rows=" + todayRows + " first-diff " + first.getKey() + " x"
                                + first.getValue(),
                        "rows=" + mergedRows + " differing tuples=" + diff.size()));
            }
        }


        /** Today's part row of a lookup at an evaluation row, or {@code -2} when unknown. */
        private long boundRow(JoinLookup l, long row)
        {
            if (l instanceof KeyMatchExpandedLookup k)
            {
                return k.boundRowAt(row);
            }
            if (l instanceof DatasetLookup d)
            {
                return d.joinedRowAt(evalTable, row);
            }
            return -2;
        }

        // ---- keyed reads


        /**
         * The merged row of a keyed read, or {@code -1}; answers {@code -2} when not comparable.
         */
        private int keyedRow(JoinLookup delegate, Part p, IDataTable t, long row)
        {
            if (t != evalTable)
            {
                uncompared.increment();
                return -2;
            }
            keyedCompared.increment();
            long real = evalTable.getRealRowIndex(row);
            if (!p.partRowsKnown)
            {
                return -3;
            }
            long q = boundRow(delegate, row);
            int m = mergedRow(p, real, q);
            if (m < 0)
            {
                diverge(new Divergence(Kind.ROW_ABSENT, p.part.qualifier(), "", real, q,
                        "evaluated", "no merged row"));
            }
            return m;
        }


        private int columnOf(Part p, String column)
        {
            MergedTable m = Objects.requireNonNull(merged);
            int idx = m.getColumnIndex(p.part.qualifier() + "." + column);
            return idx >= 0 && p.part.contains(idx) ? idx : -1;
        }


        private IDataValue mergedValue(int m, int idx, boolean numericExpected)
        {
            IDataTable v = Objects.requireNonNull(view);
            if (idx < 0)
            {
                return JoinLookup.absentJoinedColumnValue(numericExpected);
            }
            if (!v.isSupplied(m, idx))
            {
                return DataValueSupport.defaultForType(
                        Objects.requireNonNull(merged).getMetaData().getColumn(idx).getType());
            }
            return v.getDataValue(m, idx);
        }


        private void compareValue(JoinLookup delegate, Part p, IDataTable t, long row,
                String column, boolean numericExpected, IDataValue today)
        {
            int m = keyedRow(delegate, p, t, row);
            if (m == -3)
            {
                int idx = columnOf(p, column);
                for (int candidate : mergedRowsOf(evalTable.getRealRowIndex(row)))
                {
                    if (same(today, mergedValue(candidate, idx, numericExpected)))
                    {
                        return;
                    }
                }
                diverge(new Divergence(Kind.KEYED_VALUE, p.part.qualifier(), column,
                        evalTable.getRealRowIndex(row), -2, render(today), "no merged row agrees"));
                return;
            }
            if (m < 0)
            {
                return;
            }
            IDataValue expected = mergedValue(m, columnOf(p, column), numericExpected);
            if (!same(today, expected))
            {
                diverge(new Divergence(Kind.KEYED_VALUE, p.part.qualifier(), column,
                        evalTable.getRealRowIndex(row), p.partRow(m), render(today),
                        render(expected)));
            }
        }


        private void compareText(JoinLookup delegate, Part p, IDataTable t, long row, String column,
                @Nullable String today)
        {
            int m = keyedRow(delegate, p, t, row);
            if (m < 0)
            {
                return;
            }
            int idx = columnOf(p, column);
            IDataTable v = Objects.requireNonNull(view);
            String expected = idx < 0 || !v.isSupplied(m, idx) ? null
                    : ScalarSemantics.resolvedString(v, idx, m);
            if (!Objects.equals(today, expected))
            {
                diverge(new Divergence(Kind.KEYED_TEXT, p.part.qualifier(), column,
                        evalTable.getRealRowIndex(row), p.partRow(m), String.valueOf(today),
                        String.valueOf(expected)));
            }
        }


        private void compareMatched(JoinLookup delegate, Part p, IDataTable t, long row,
                boolean today)
        {
            int m = keyedRow(delegate, p, t, row);
            if (m < 0)
            {
                return;
            }
            int flag = p.part.matchedColumn();
            boolean expected = flag >= 0
                    && Boolean.TRUE.equals(Objects.requireNonNull(view).getValue(m, flag));
            if (today != expected)
            {
                diverge(new Divergence(Kind.KEYED_MATCHED, p.part.qualifier(),
                        MergeMeta.MATCHED_FLAG, evalTable.getRealRowIndex(row), p.partRow(m),
                        String.valueOf(today), String.valueOf(expected)));
            }
        }


        private void compareSchema(Part p, String column, Kind kind, String today, String expected)
        {
            keyedCompared.increment();
            if (!today.equals(expected))
            {
                diverge(new Divergence(kind, p.part.qualifier(), column, -1, -1, today, expected));
            }
        }

        /** A lookup whose every answer is compared with the merged part. */
        private final class RecordingLookup implements JoinLookup
        {

            private final JoinLookup delegate;

            private final Part part;

            private RecordingLookup(JoinLookup aDelegate, Part aPart)
            {
                delegate = aDelegate;
                part = aPart;
            }


            @Override
            public @Nullable String lookup(IDataTable primaryTable, long row, String columnName)
            {
                String s = delegate.lookup(primaryTable, row, columnName);
                compareText(delegate, part, primaryTable, row, columnName, s);
                return s;
            }


            @Override
            public boolean hasColumn(IDataTable primaryTable, long row, String columnName)
            {
                boolean b = delegate.hasColumn(primaryTable, row, columnName);
                compareSchema(part, columnName, Kind.KEYED_COLUMN, String.valueOf(b),
                        String.valueOf(columnOf(part, columnName) >= 0));
                return b;
            }


            @Override
            public IDataValue lookupValue(IDataTable primaryTable, long row, String columnName,
                    boolean numericExpected)
            {
                IDataValue v = delegate.lookupValue(primaryTable, row, columnName, numericExpected);
                compareValue(delegate, part, primaryTable, row, columnName, numericExpected, v);
                return v;
            }


            @Override
            public boolean lacksColumnOnEveryRow(String columnName)
            {
                boolean b = delegate.lacksColumnOnEveryRow(columnName);
                compareSchema(part, columnName, Kind.KEYED_COLUMN, "lacks=" + b,
                        "lacks=" + (columnOf(part, columnName) < 0));
                return b;
            }


            @Override
            public DataValueType declaredTypeOf(String columnName)
            {
                DataValueType t = delegate.declaredTypeOf(columnName);
                int idx = columnOf(part, columnName);
                compareSchema(part, columnName, Kind.KEYED_TYPE, String.valueOf(t),
                        String.valueOf(idx < 0 ? DataValueType.MISSING
                                : Objects.requireNonNull(merged).getMetaData().getColumn(idx)
                                        .getType()));
                return t;
            }


            @Override
            public boolean matchedRow(IDataTable primaryTable, long row)
            {
                boolean b = delegate.matchedRow(primaryTable, row);
                compareMatched(delegate, part, primaryTable, row, b);
                return b;
            }


            @Override
            public String getDatasetName()
            {
                return delegate.getDatasetName();
            }
        }

        // ---- SUPP reads

        /** The merged SUPP qualifier column of a bare name, or {@code -1}. */
        private int suppColumnOf(String name)
        {
            MergedTable m = merged;
            if (m == null)
            {
                return -1;
            }
            int idx = m.getColumnIndex(name);
            return idx >= 0 && m.partOf(idx).kind() == MergeKind.SUPP ? idx : -1;
        }


        /**
         * Records one {@link SuppPivot#qualifierColumn} answer: compares the qualifier's existence
         * with the merged view and, when the pivot serves a column, wraps it so every cell read is
         * compared too.
         */
        @Nullable
        IDataTableColumn suppColumn(IDataTable table, String name,
                @Nullable IDataTableColumn column)
        {
            if (merged == null)
            {
                return column; // BUILD_REFUSED already reported
            }
            if (table != evalTable)
            {
                uncompared.increment();
                return column;
            }
            suppCompared.increment();
            int idx = suppColumnOf(name);
            if ((column != null) != (idx >= 0))
            {
                diverge(new Divergence(Kind.SUPP_EXISTS, "SUPP", name, -1, -1,
                        String.valueOf(column != null), String.valueOf(idx >= 0)));
            }
            if (column == null || idx < 0)
            {
                return column;
            }
            DataTableColumnMeta meta = Objects.requireNonNull(merged).getMetaData().getColumn(idx);
            Object error = meta.getMetaData(MergeMeta.MERGE_ERROR);
            if (error != null)
            {
                if (erroredQnams.add(name))
                {
                    diverge(new Divergence(Kind.SUPP_MERGE_ERROR, "SUPP", name, -1, -1, "read",
                            String.valueOf(error)));
                }
                return column;
            }
            return new RecordingSuppColumn(column, name, idx);
        }


        private void compareSupp(String name, int idx, long row, IDataValue today)
        {
            suppCompared.increment();
            long real = evalTable.getRealRowIndex(row);
            int m = firstMergedRow(real);
            if (m < 0)
            {
                diverge(new Divergence(Kind.ROW_ABSENT, "SUPP", name, real, -1, render(today),
                        "no merged row"));
                return;
            }
            IDataTable v = Objects.requireNonNull(view);
            IDataValue expected = v.isSupplied(m, idx) ? v.getDataValue(m, idx) : SuppPivot.BLANK;
            if (!same(today, expected))
            {
                diverge(new Divergence(Kind.SUPP_VALUE, "SUPP", name, real, -1, render(today),
                        render(expected)));
            }
        }

        /** A pivoted qualifier column whose every cell read is compared with the merged one. */
        private final class RecordingSuppColumn extends AbstractDataTableColumn
        {

            private final IDataTableColumn delegate;

            private final String name;

            private final int idx;

            private RecordingSuppColumn(IDataTableColumn aDelegate, String aName, int anIdx)
            {
                super(0);
                delegate = aDelegate;
                name = aName;
                idx = anIdx;
            }


            @Override
            public long getRowCount()
            {
                return delegate.getRowCount();
            }


            @Override
            public @Nullable Object getValue(long aRow)
            {
                return getDataValue(aRow).getValue();
            }


            @Override
            public IDataValue getDataValue(long aRow)
            {
                IDataValue v = delegate.getDataValue(aRow);
                compareSupp(name, idx, aRow, v);
                return v;
            }
        }
    }
}
