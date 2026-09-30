package net.cumba.corej.core.exec;

import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import net.cumba.datatable.AbstractDataTableColumn;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The declared SUPP merge as a <b>pivot by reference</b> ({@code Supp_Merge}, rule-level boolean,
 * default {@code true} — owner P-Q1, 2026-09-28; C1 ruled (a) 2026-09-29: <i>"Supplementals are
 * used to give a place for non standard variables"</i>; {@code PLAN-operation-replacements} §2.3).
 *
 * <p>
 * A supplemental qualifier is <b>readable per record</b> and <b>visible to existence</b>, but it is
 * <b>not a variable of the parent dataset</b>: the table's column set stays the file's, so the
 * variable-metadata surface — the per-variable iteration, {@code get_column_order_from_dataset},
 * {@code variable_count}, length metadata — never sees it (the naive table augmentation moved 11
 * study rules, {@code CDISC-CG0013 / CG0351 / CG0664}, {@code FDA / PMDA-SD0058 / SD0060 / SD1079 /
 * SD1082}). Instead a bare reference the primary does not carry resolves through this pivot at the
 * readers that mean "the record's value": the compiler's bare-column plans, {@code var_exists} /
 * {@code var_is_null}, the finding's reported value and a bare {@code Requirements.Variables}
 * entry. A reader that does not ask this pivot simply does not see the qualifier — the safe failure
 * (a qualifier not seen, which the rules' scenarios catch), where a table augmentation fails the
 * other way (a qualifier reported as a variable, silently).
 * </p>
 *
 * <p>
 * The column for a {@code QNAM}: each {@code SUPP<domain>} row qualifies the parent record
 * ({@code USUBJID}, {@code POOLID}) + {@code IDVAR} = {@code IDVARVAL} (the subject key is the
 * pair, {@link SuppQnamIndex.SubjectKey} — SENDIG's pooled records carry a blank {@code USUBJID}
 * and a populated {@code POOLID}, and keyed on {@code USUBJID} alone one pool's qualifier qualified
 * every pool; the {@code IDVARVAL} text coerced to a numeric parent column as the child pre-merge
 * does, {@link ChildMatchIndex#normalizeJoinToken}, J5 / D4-R5); a blank {@code IDVAR} qualifies
 * every record of the subject or pool (SDTMIG 8.4), and a subject-level row naming neither
 * qualifies nothing; a record-level row wins over a subject-level one; among duplicates the first
 * row in dataset order wins. A record no row qualifies reads the present blank {@code ""} (the type
 * default of a char column, D34 #3); a matched row's {@code QVAL} cell is handed through, identity
 * kept. The parent's own column always wins: the pivot answers nothing for a name the primary
 * carries. A {@code SUPP--} / {@code SQ--} / {@code RELREC} primary is never pivoted.
 * {@code Supp_Merge:
 * false} answers nothing at all, and the same flag turns off the dotted existence pivot
 * ({@code OperatorRegistry.existsAsDottedDatasetColumn}, Fix #39) — one flag, one meaning.
 * </p>
 *
 * <p>
 * Costs: the SUPP table is parsed once per run through the shared join cache
 * ({@link SuppQnamIndex}, {@code JoinCache.SharedIndexCache}) or once per context without one; a
 * qualifier column materialises on its first read — once per run per (parent, QNAM) through the
 * same shared cache, else once per context — and is memoised on the context, so a QNAM no rule
 * reads costs nothing per row.
 * </p>
 */
public final class SuppPivot
{

    /** The present blank a record no qualifier row reaches reads as. */
    static final IDataValue BLANK = DataValueSupport.getAsDataValue("", DataValueType.STRING);

    private SuppPivot()
    {
    }

    /** Per-context memo: the SUPP index of the primary's domain and the columns read so far. */
    public static final class Memo
    {

        private volatile @Nullable IndexHolder index;

        private final Map<String, Optional<IDataTableColumn>> columns = new ConcurrentHashMap<>();
    }

    /**
     * The pivoted qualifier column for {@code name} on the context's table, or {@code null} when
     * the pivot does not apply: {@code Supp_Merge} off, the primary carries the column itself, no
     * {@code SUPP<domain>} resolves, it has no qualifier shape, or it carries no such {@code QNAM}.
     *
     * @param ctx
     *            the evaluation context (its table is the parent, its resolver finds the SUPP)
     * @param name
     *            the bare column name
     * @return the column, or {@code null}
     */
    public static @Nullable IDataTableColumn qualifierColumn(EvaluationContext ctx, String name)
    {
        if (!ctx.isSuppMerge() || ctx.getTable().getMetaData().getColumnIndex(name) >= 0)
        {
            return null;
        }
        Memo memo = ctx.getSuppPivot();
        Optional<IDataTableColumn> cached = memo.columns.get(name);
        if (cached != null)
        {
            return cached.orElse(null);
        }
        SuppQnamIndex index = index(ctx);
        IDataTableColumn column = null;
        if (index != null && index.qnams().contains(name))
        {
            long rows = ctx.getTable().getRowCount();
            if (rows <= Integer.MAX_VALUE)
            {
                column = new SuppQnamColumn(ctx.getTable(), index, name, (int) rows,
                        ctx.getSharedIndexCache());
            }
        }
        memo.columns.put(name, Optional.ofNullable(column));
        return column;
    }


    /**
     * Whether {@code name} is delivered as a supplemental qualifier of the context's table: the
     * primary lacks the column, {@code Supp_Merge} is on and {@code SUPP<domain>} carries the
     * {@code QNAM} (any row — the existence half of the merge, the same reading
     * {@code OperatorRegistry.existsInSuppQnam} applies on the dotted path).
     *
     * @param ctx
     *            the evaluation context
     * @param name
     *            the bare column name
     * @return whether the qualifier exists for this table
     */
    public static boolean exists(EvaluationContext ctx, String name)
    {
        return qualifierColumn(ctx, name) != null;
    }

    /** The resolved index, or the fact that there is none — memoised as one object. */
    private record IndexHolder(@Nullable SuppQnamIndex index)
    {
    }

    private static @Nullable SuppQnamIndex index(EvaluationContext ctx)
    {
        Memo memo = ctx.getSuppPivot();
        IndexHolder cached = memo.index;
        if (cached == null)
        {
            cached = new IndexHolder(resolveIndex(ctx));
            memo.index = cached;
        }
        return cached.index();
    }


    private static @Nullable SuppQnamIndex resolveIndex(EvaluationContext ctx)
    {
        DataTableMeta meta = ctx.getTable().getMetaData();
        String name = meta.getName();
        if (name == null || name.isEmpty())
        {
            return null;
        }
        DatasetResolver resolver = ctx.getDatasetResolver();
        String domain = DatasetIdentity.domainOfDataset(name, resolver);
        if (domain == null || domain.isEmpty())
        {
            return null;
        }
        IDataTable supp = resolver.resolve("SUPP" + domain);
        if (supp == null || supp == ctx.getTable())
        {
            return null;
        }
        JoinCache.SharedIndexCache cache = ctx.getSharedIndexCache();
        return cache != null ? cache.getOrBuildSuppQnamIndex(supp) : SuppQnamIndex.of(supp);
    }

    /**
     * One pivoted qualifier column: the parent's rows resolved against the SUPP rows carrying its
     * {@code QNAM}, materialised on first read and then fixed — through the run's shared cache when
     * the context has one, so the resolution runs once per (parent, QNAM) per run.
     */
    static final class SuppQnamColumn extends AbstractDataTableColumn
    {

        private final IDataTable parent;

        private final SuppQnamIndex qnamIndex;

        private final String qnam;

        private final int rowCount;

        private final JoinCache.@Nullable SharedIndexCache shared;

        /**
         * The materialised column, published through a final field so the double-checked read below
         * is a safe publication of the array (SpotBugs VO_VOLATILE_REFERENCE_TO_ARRAY: a volatile
         * reference to a bare array publishes the reference, not the elements).
         */
        private volatile @Nullable Materialised cells;

        SuppQnamColumn(IDataTable aParent, SuppQnamIndex aIndex, String aQnam, int aRowCount,
                JoinCache.@Nullable SharedIndexCache aShared)
        {
            super(0);
            parent = aParent;
            qnamIndex = aIndex;
            qnam = aQnam;
            rowCount = aRowCount;
            shared = aShared;
        }


        @Override
        public long getRowCount()
        {
            return rowCount;
        }


        @Override
        public @Nullable Object getValue(long aRow)
        {
            return getDataValue(aRow).getValue();
        }


        @Override
        public IDataValue getDataValue(long aRow)
        {
            return materialise()[(int) aRow];
        }


        private IDataValue[] materialise()
        {
            Materialised out = cells;
            if (out == null)
            {
                synchronized (this)
                {
                    out = cells;
                    if (out == null)
                    {
                        out = new Materialised(shared == null ? resolve()
                                : shared.getOrBuildSuppQnamColumn(parent, qnamIndex, qnam,
                                        this::resolve));
                        cells = out;
                    }
                }
            }
            return out.values();
        }

        /** The record key of a record-level match: the subject (or pool) and the coerced value. */
        private record RecordKey(SuppQnamIndex.SubjectKey subject, String token)
        {
        }

        private IDataValue[] resolve()
        {
            IDataValue[] out = new IDataValue[rowCount];
            Arrays.fill(out, BLANK);
            DataTableMeta pm = parent.getMetaData();
            int usubjidIdx = pm.getColumnIndex("USUBJID");
            int poolidIdx = pm.getColumnIndex("POOLID");
            // Record-level rows, grouped by the IDVAR they name, in first-seen order; then the
            // subject-level rows. First-seen wins within a group (putIfAbsent), an earlier group
            // wins over a later one, and any record-level match wins over a subject-level one.
            Map<String, Map<RecordKey, IDataValue>> byIdvar = new LinkedHashMap<>();
            Map<SuppQnamIndex.SubjectKey, IDataValue> bySubject = new HashMap<>();
            Map<String, Boolean> numericByIdvar = new HashMap<>();
            for (SuppQnamIndex.Entry e : qnamIndex.entries(qnam))
            {
                if (e.subjectLevel())
                {
                    bySubject.putIfAbsent(e.subject(), e.qval());
                    continue;
                }
                int ci = pm.getColumnIndex(e.idvar());
                if (ci < 0)
                {
                    continue; // the parent has no such column: the row can qualify no record
                }
                boolean numeric = numericByIdvar.computeIfAbsent(e.idvar(), _ ->
                {
                    DataValueType t = pm.getColumn(ci).getType();
                    return t == DataValueType.LONG || t == DataValueType.DOUBLE;
                });
                byIdvar.computeIfAbsent(e.idvar(), _ -> new HashMap<>()).putIfAbsent(
                        new RecordKey(e.subject(), token(e.idvarval(), numeric)), e.qval());
            }
            if (byIdvar.isEmpty() && bySubject.isEmpty())
            {
                return out;
            }
            // The parent side's subject keys, derived exactly as the SUPP side's (one derivation,
            // SuppQnamIndex.SubjectKey.of), once per row.
            SuppQnamIndex.SubjectKey[] subject = new SuppQnamIndex.SubjectKey[rowCount];
            for (int r = 0; r < rowCount; r++)
            {
                subject[r] = SuppQnamIndex.SubjectKey.of(parent, usubjidIdx, poolidIdx, r);
            }
            boolean[] set = new boolean[rowCount];
            for (Map.Entry<String, Map<RecordKey, IDataValue>> group : byIdvar.entrySet())
            {
                int ci = pm.getColumnIndex(group.getKey());
                boolean numeric = Boolean.TRUE.equals(numericByIdvar.get(group.getKey()));
                Map<RecordKey, IDataValue> lookup = group.getValue();
                for (int r = 0; r < rowCount; r++)
                {
                    if (set[r])
                    {
                        continue;
                    }
                    String cell = text(parent.getDataValue(r, ci));
                    if (cell == null)
                    {
                        continue;
                    }
                    IDataValue hit = lookup.get(new RecordKey(subject[r], token(cell, numeric)));
                    if (hit != null)
                    {
                        out[r] = hit;
                        set[r] = true;
                    }
                }
            }
            if (!bySubject.isEmpty())
            {
                for (int r = 0; r < rowCount; r++)
                {
                    if (!set[r])
                    {
                        IDataValue hit = bySubject.get(subject[r]);
                        if (hit != null)
                        {
                            out[r] = hit;
                        }
                    }
                }
            }
            return out;
        }


        private static @Nullable String text(IDataValue dv)
        {
            return dv.isMissingOrInvalid() ? null : dv.getValueAsString();
        }


        /** The coerced join token of a present text (never {@code null} for a present input). */
        private static String token(String raw, boolean numeric)
        {
            return java.util.Objects
                    .requireNonNull(ChildMatchIndex.normalizeJoinToken(raw, numeric));
        }

        /** The column's cells, one per parent row, never {@code null} and no element null. */
        private static final class Materialised
        {

            private final IDataValue[] values;

            Materialised(IDataValue[] aValues)
            {
                values = aValues;
            }


            IDataValue[] values()
            {
                return values;
            }
        }
    }
}
