package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
import java.util.function.Supplier;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.merge.MergeMeta;
import net.cumba.datatable.merge.MergedPart;
import net.cumba.datatable.merge.MergedTable;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * A {@link JoinLookup} over one keyed part of the merged evaluation view
 * ({@code PLAN-merged-evaluation-table} W3a): every answer is read straight from the view at the
 * evaluation row — the evaluation table is the view's primary part, so evaluation row {@code i}
 * <em>is</em> merged row {@code i} and no key is probed and no row map is consulted here.
 *
 * <p>
 * The answers are those the retired expanded and first-wins lookups gave (W2 compared 6.9 M of
 * them; W6 retired those lookups and made this the only production implementation): an absent
 * partner column is the authored absent default ({@link JoinLookup#absentJoinedColumnValue}), an
 * unmatched row reads the partner column's type default (the not-supplied marker, MET #4), a
 * matched row hands the partner's cell through identity and all; the text form renders through
 * {@link ScalarSemantics#resolvedString}; {@code _matched_} is the part's supplied marker.
 * </p>
 *
 * <p>
 * <b>A flagged column</b> (OD-7, MET #15 — a partner's SUPP qualifier two rows qualify for one
 * record, MQ5; a RELREC related domain lacking the column a relationship names) is never read:
 * every value read — {@link #lookupValue}, {@link #columnReader}, {@link #lookup} — throws
 * {@link MergeErrorReadException}, the read-time backstop behind {@code RuleRunner}'s static gate
 * for a dynamic read ({@code colref}) no static walk sees (T2 M4). The existence answers
 * ({@link #hasColumn}, {@link #columnMeta}, {@link #declaredTypeOf}) still see the column: it
 * exists, its values cannot be supplied. A {@code Child} part is never flagged here (OD-1's
 * precedent: its rows read the not-supplied marker and the referenced-record rule reports them —
 * the builder leaves CHILD columns out of the flag map).
 * </p>
 *
 * <p>
 * Costs: one column-index memo per column name (filled once per name, shared by every rule the
 * cached view serves), then one {@code isSupplied} and one cell read per row — no per-row
 * allocation of this class's own.
 * </p>
 */
final class MergedViewLookup implements JoinLookup
{

    private final MergedTable view;

    private final MergedPart part;

    private final DataTableMeta meta;

    private final ConcurrentHashMap<String, Integer> columns = new ConcurrentHashMap<>();

    /**
     * The part of a merged column — {@code null} for the primary's — across the stages of a chained
     * key ({@link MergedEvaluation.Built#partOf}), whose final table carries an earlier stage's
     * parts as primary columns at the same indices.
     */
    private final IntFunction<@Nullable MergedPart> partOf;

    /** The test plant: rows read one merged row on ({@link MergedEvaluation#plantForTests}). */
    private final boolean shifted;

    /** Merged column name → its {@code MERGE_ERROR} flag (OD-7), CHILD columns excluded (OD-1). */
    private final Map<String, String> flagged;

    /**
     * The keyed partner table's declared {@code Encoding}, or {@code null} — for a Child parent or
     * a RELREC domain the source table's own, read lazily ({@link #declaredEncoding}).
     */
    private final @Nullable String encoding;

    /**
     * The part's source table as resolved for the merge: a keyed partner the builder holds, or the
     * dataset a Child parent / RELREC domain names, resolved on first use through the run's
     * resolver (B5 review L2) — {@code null} when the resolver refuses it (a type-clashing split
     * domain: the merge's own ERROR, not this lookup's), so {@link #sourceColumnIsBlank} then
     * answers "not blank" and {@link #declaredEncoding} none.
     */
    private final Supplier<@Nullable IDataTable> source;

    private boolean sourceResolved;

    private @Nullable IDataTable resolvedSource;

    MergedViewLookup(MergedTable aView, MergedPart aPart, IntFunction<@Nullable MergedPart> aPartOf,
            boolean aShifted, Map<String, String> aFlagged, @Nullable String aEncoding,
            Supplier<@Nullable IDataTable> aSource)
    {
        view = aView;
        part = aPart;
        partOf = aPartOf;
        meta = aView.getMetaData();
        shifted = aShifted;
        flagged = aFlagged;
        encoding = aEncoding;
        source = aSource;
    }


    private @Nullable IDataTable source()
    {
        if (!sourceResolved)
        {
            resolvedSource = source.get();
            sourceResolved = true;
        }
        return resolvedSource;
    }


    /**
     * {@inheritDoc} Answered from the SOURCE table's own content status
     * ({@code IDataTable.getColumnContent}, cached per table where the table caches it), never from
     * the merged cells (D72a-1: an unmatched row reads the type default there).
     */
    @Override
    public boolean sourceColumnIsBlank(String columnName)
    {
        IDataTable table = source();
        if (table == null)
        {
            return false;
        }
        int idx = table.getMetaData().getColumnIndex(columnName);
        return idx >= 0 && table.getColumnContent(idx).isBlank();
    }


    /**
     * {@inheritDoc} The keyed partner's declared {@code Encoding} as the builder read it; for a
     * Child parent or a RELREC domain the source table's own declared {@code Encoding}, or
     * {@code null} when it declares none or the source cannot be reached — a missing byte count
     * either way, never the primary's charset (B3 review LOW-1).
     */
    @Override
    public @Nullable String declaredEncoding()
    {
        if (encoding != null)
        {
            return encoding;
        }
        IDataTable table = source();
        Object declared = table == null ? null : table.getMetaData().getMetaData("Encoding");
        return declared == null ? null : declared.toString();
    }


    /**
     * The memo code of a partner column name: the merged column index, {@code -1} when the part has
     * no such column, or {@code -2 - index} when the column is flagged (OD-7) — so the hot path of
     * an unflagged read stays one memo lookup and one sign test.
     */
    private int column(String name)
    {
        return columns.computeIfAbsent(name, this::resolve);
    }


    /** The merged column index of a {@link #column} code ({@code -1} when absent). */
    private static int index(int code)
    {
        return code >= -1 ? code : -2 - code;
    }


    private Integer resolve(String name)
    {
        // the keyed part's own columns, and (MQ5, W3d) the qualifiers the partner's SUPP<domain>
        // delivers — a SUPP part under the same qualifier, named <Name>.<QNAM> by the view
        int i = meta.getColumnIndex(part.qualifier() + "." + name);
        MergedPart owner = i < 0 ? null : partOf.apply(i);
        if (owner == null || !owner.qualifier().equals(part.qualifier()))
        {
            return -1;
        }
        return flagged.containsKey(meta.getColumn(i).getName()) ? -2 - i : i;
    }


    /** The read of a flagged column (a {@link #column} code below {@code -1}): OD-7's ERROR. */
    private MergeErrorReadException flaggedRead(int code)
    {
        String merged = meta.getColumn(index(code)).getName();
        return new MergeErrorReadException(merged, String.valueOf(flagged.get(merged)));
    }


    @Override
    public List<DataTableColumnMeta> columns()
    {
        List<DataTableColumnMeta> out = new ArrayList<>(part.columnCount());
        for (int i = part.firstColumn(); i < part.firstColumn() + part.columnCount(); i++)
        {
            if (i != part.matchedColumn())
            {
                out.add(sourceMeta(i));
            }
        }
        return out;
    }


    @Override
    public @Nullable DataTableColumnMeta columnMeta(String columnName)
    {
        int idx = index(column(columnName));
        return idx < 0 ? null : sourceMeta(idx);
    }


    /** The merged column's metadata under its source name and its ordinal within the part. */
    private DataTableColumnMeta sourceMeta(int idx)
    {
        DataTableColumnMeta merged = meta.getColumn(idx);
        Object source = merged.getMetaData(MergeMeta.MERGED_SOURCE_COLUMN);
        String name = source != null ? String.valueOf(source)
                : merged.getName().substring(part.qualifier().length() + 1);
        return DataTableColumnMeta.builderFrom(merged).name(name)
                .index(idx - java.util.Objects.requireNonNull(partOf.apply(idx)).firstColumn())
                .build();
    }


    private long row(long row)
    {
        return shifted ? (row + 1) % view.getRowCount() : row;
    }


    @Override
    public IDataValue lookupValue(IDataTable primaryTable, long row, String columnName,
            boolean numericExpected)
    {
        int idx = column(columnName);
        if (idx < 0)
        {
            if (idx < -1)
            {
                throw flaggedRead(idx);
            }
            return JoinLookup.absentJoinedColumnValue(numericExpected);
        }
        long r = row(row);
        if (!view.isSupplied(r, idx))
        {
            return DataValueSupport.defaultForType(meta.getColumn(idx).getType());
        }
        return view.getDataValue(r, idx);
    }


    @Override
    public java.util.function.LongFunction<IDataValue> columnReader(IDataTable primaryTable,
            String columnName, boolean numericExpected)
    {
        // lookupValue with the column resolved once (T1): the name memo's hash lookup per cell
        // measured ~20 → ~16 ns per merged read through this reader (1 M rows, warm)
        int idx = column(columnName);
        if (idx < -1)
        {
            // thrown at the read, not here: a reader built for no row reads nothing
            return _ ->
            {
                throw flaggedRead(idx);
            };
        }
        if (idx < 0)
        {
            IDataValue absent = JoinLookup.absentJoinedColumnValue(numericExpected);
            return _ -> absent;
        }
        DataValueType type = meta.getColumn(idx).getType();
        if (shifted)
        {
            return row ->
            {
                long r = row(row);
                return view.isSupplied(r, idx) ? view.getDataValue(r, idx)
                        : DataValueSupport.defaultForType(type);
            };
        }
        return row -> view.isSupplied(row, idx) ? view.getDataValue(row, idx)
                : DataValueSupport.defaultForType(type);
    }


    @Override
    public @Nullable String lookup(IDataTable primaryTable, long row, String columnName)
    {
        if (columnName == null)
        {
            return null;
        }
        int idx = column(columnName);
        if (idx < 0)
        {
            if (idx < -1)
            {
                throw flaggedRead(idx);
            }
            return null;
        }
        long r = row(row);
        return view.isSupplied(r, idx) ? ScalarSemantics.resolvedString(view, idx, r) : null;
    }


    @Override
    public boolean hasColumn(IDataTable primaryTable, long row, String columnName)
    {
        return columnName != null && column(columnName) != -1;
    }


    @Override
    public boolean lacksColumnOnEveryRow(String columnName)
    {
        return column(columnName) == -1;
    }


    @Override
    public DataValueType declaredTypeOf(String columnName)
    {
        int idx = index(column(columnName));
        return idx < 0 ? DataValueType.MISSING : meta.getColumn(idx).getType();
    }


    @Override
    public boolean matchedRow(IDataTable primaryTable, long row)
    {
        int flag = part.matchedColumn();
        long r = row(row);
        return flag >= 0 ? Boolean.TRUE.equals(view.getValue(r, flag))
                : part.columnCount() > 0 && view.isSupplied(r, part.firstColumn());
    }


    @Override
    public String getDatasetName()
    {
        return part.source();
    }
}
