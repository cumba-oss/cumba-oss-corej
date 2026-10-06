package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.IntFunction;
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

    MergedViewLookup(MergedTable aView, MergedPart aPart, IntFunction<@Nullable MergedPart> aPartOf,
            boolean aShifted)
    {
        view = aView;
        part = aPart;
        partOf = aPartOf;
        meta = aView.getMetaData();
        shifted = aShifted;
    }


    /** The merged column of a partner column name, or {@code -1}; memoised per name. */
    private int column(String name)
    {
        return columns.computeIfAbsent(name, this::resolve);
    }


    private Integer resolve(String name)
    {
        // the keyed part's own columns, and (MQ5, W3d) the qualifiers the partner's SUPP<domain>
        // delivers — a SUPP part under the same qualifier, named <Name>.<QNAM> by the view
        int i = meta.getColumnIndex(part.qualifier() + "." + name);
        MergedPart owner = i < 0 ? null : partOf.apply(i);
        return owner != null && owner.qualifier().equals(part.qualifier()) ? i : -1;
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
        int idx = column(columnName);
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
    public @Nullable String lookup(IDataTable primaryTable, long row, String columnName)
    {
        if (columnName == null)
        {
            return null;
        }
        int idx = column(columnName);
        if (idx < 0)
        {
            return null;
        }
        long r = row(row);
        return view.isSupplied(r, idx) ? ScalarSemantics.resolvedString(view, idx, r) : null;
    }


    @Override
    public boolean hasColumn(IDataTable primaryTable, long row, String columnName)
    {
        return columnName != null && column(columnName) >= 0;
    }


    @Override
    public boolean lacksColumnOnEveryRow(String columnName)
    {
        return column(columnName) < 0;
    }


    @Override
    public DataValueType declaredTypeOf(String columnName)
    {
        int idx = column(columnName);
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
