package net.cumba.corej.core.exec;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.AbstractDataTable;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Row-remapped view of a base table: view row {@code i} presents the base table's row
 * {@code rowMap[i]}. The evaluation table of a row-mapped merged view ({@link MergedEvaluation}:
 * the primary part, row {@code i} the merged row {@code i}), the distinct real rows of
 * {@link RealRows} and the row-less base of {@link SuppQualifiers}. The column set and metadata are
 * the base table's; row identity ({@link #getRealRowIndex}) maps through to the base so a violation
 * on an expanded row is reported against the originating primary record.
 *
 * <p>
 * Implemented as a self-contained {@link AbstractDataTable} — using only the contract types present
 * across engine variants ({@link IDataTable}, {@link IDataBufferNumeric}) — rather than a
 * {@code datatable-impl} view class, which keeps it portable and avoids rebuilding the base
 * metadata (unnecessary here, since {@link #getRowCount} is overridden and the engine iterates via
 * {@code table.getRowCount()}). Born as the RELREC expander's table; renamed in W6 of
 * {@code PLAN-merged-evaluation-table}, when the last row expander it backed retired.
 * </p>
 */
final class RowMappedTable extends AbstractDataTable
{

    private final IDataTable base;

    /** Expanded row index -> base (primary) row index. */
    private final IDataBufferNumeric rowMap;

    /**
     * The real-row projection ({@link RealRows}, W3e of {@code PLAN-merged-evaluation-table}),
     * computed on first use and kept with the table — the merged view's table is cached per
     * (dataset, merge signature), so every rule of the signature shares it.
     */
    private volatile @Nullable RealRows realRows;

    RowMappedTable(IDataTable base, IDataBufferNumeric rowMap)
    {
        this.base = base;
        this.rowMap = rowMap;
        setMetaData(base.getMetaData());
    }


    /** The real-row projection of this table, computed once. */
    RealRows realRows()
    {
        RealRows computed = realRows;
        if (computed == null)
        {
            synchronized (this)
            {
                computed = realRows;
                if (computed == null)
                {
                    computed = RealRows.compute(this, rowMap::getValueAsLong, base.getRowCount());
                    realRows = computed;
                }
            }
        }
        return computed;
    }


    private long baseRow(long row)
    {
        return rowMap.getValueAsLong((int) ensureValidRow(row));
    }


    @Override
    public long getRowCount()
    {
        return rowMap.size();
    }


    @Override
    protected long sourceContentGeneration()
    {
        return base.contentGeneration();
    }


    @Override
    public @Nullable Object getValue(long aRow, int aColumn) throws IndexOutOfBoundsException
    {
        return base.getValue(baseRow(aRow), aColumn);
    }


    @Override
    public IDataValue getDataValue(long aRow, int aColumn)
    {
        return base.getDataValue(baseRow(aRow), aColumn);
    }


    @Override
    public long getRealRowIndex(long aRowIndex) throws IndexOutOfBoundsException
    {
        return base.getRealRowIndex(baseRow(aRowIndex));
    }
}
