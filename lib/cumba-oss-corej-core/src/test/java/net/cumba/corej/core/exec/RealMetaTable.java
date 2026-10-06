package net.cumba.corej.core.exec;

import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.AbstractDataTable;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * A real-metadata face over a {@code MockTable} fixture, whose column metadata is a Mockito mock
 * that the datatable's merge cannot copy ({@code DataTableColumnMeta.builderFrom} answers
 * {@code null} for it) — W6 of {@code PLAN-merged-evaluation-table}, where the fixtures of the
 * retired key-match expander and key-join lookups reach the merged view ({@link ExecCalls#expand},
 * {@link ExecCalls#viewLookup}). The cells are the mock's own, read through unchanged — so a
 * fixture keeps the shapes only {@code MockTable} can hold (a character {@code MissingValue} cell,
 * {@code colSasMissing}) — and the metadata is rebuilt from what the mock answers: name, label,
 * type, length and format per column.
 */
final class RealMetaTable extends AbstractDataTable
{

    private final IDataTable base;

    private RealMetaTable(IDataTable aBase)
    {
        base = aBase;
        DataTableMeta meta = aBase.getMetaData();
        DataTableColumnMeta[] columns = new DataTableColumnMeta[meta.getColumnCount()];
        for (int c = 0; c < columns.length; c++)
        {
            DataTableColumnMeta mock = meta.getColumn(c);
            columns[c] = DataTableColumnMeta.builder().index(c).name(mock.getName())
                    .label(mock.getLabel()).type(mock.getType()).length(mock.getLength())
                    .displayFormat(mock.getDisplayFormat()).build();
        }
        setMetaData(
                DataTableMeta.builder().name(meta.getName()).label(meta.getName()).columns(columns)
                        .rowCount(aBase.getRowCount()).totalRowCount(aBase.getRowCount()).build());
    }


    /**
     * {@code aTable} itself when its metadata is real, else a real-metadata face over it.
     *
     * @param aTable
     *            a fixture table, possibly {@code null}
     * @return the table the merged view can read, or {@code null}
     */
    static @Nullable IDataTable of(@Nullable IDataTable aTable)
    {
        if (aTable == null || aTable.getMetaData().getColumnCount() == 0
                || DataTableColumnMeta.builderFrom(aTable.getMetaData().getColumn(0)) != null)
        {
            return aTable;
        }
        return new RealMetaTable(aTable);
    }


    @Override
    public long getRowCount()
    {
        return base.getRowCount();
    }


    @Override
    public @Nullable Object getValue(long aRow, int aColumn) throws IndexOutOfBoundsException
    {
        return base.getValue(ensureValidRow(aRow), aColumn);
    }


    @Override
    public IDataValue getDataValue(long aRow, int aColumn)
    {
        return base.getDataValue(ensureValidRow(aRow), aColumn);
    }
}
