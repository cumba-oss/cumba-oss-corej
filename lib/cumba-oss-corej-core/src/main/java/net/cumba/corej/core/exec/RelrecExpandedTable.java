package net.cumba.corej.core.exec;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.AbstractDataTable;
import net.cumba.datatable.impl.databuffer.IDataBufferNumeric;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Row-remapped view of a base table for forward RELREC expansion: expanded row {@code i} presents
 * the base table's row {@code rowMap[i]}. The column set and metadata are the base table's; row
 * identity ({@link #getRealRowIndex}) maps through to the base so a violation on an expanded row is
 * reported against the originating primary record.
 *
 * <p>
 * Implemented as a self-contained {@link AbstractDataTable} — using only the contract types present
 * across engine variants ({@link IDataTable}, {@link IDataBufferNumeric}) — rather than a
 * {@code datatable-impl} view class. This keeps {@link RelrecRowExpander} portable and avoids
 * rebuilding the base metadata (which is unnecessary here, since {@link #getRowCount} is overridden
 * and the engine iterates via {@code table.getRowCount()}).
 * </p>
 */
final class RelrecExpandedTable extends AbstractDataTable
{

    private final IDataTable base;

    /** Expanded row index -> base (primary) row index. */
    private final IDataBufferNumeric rowMap;

    RelrecExpandedTable(IDataTable base, IDataBufferNumeric rowMap)
    {
        this.base = base;
        this.rowMap = rowMap;
        setMetaData(base.getMetaData());
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
