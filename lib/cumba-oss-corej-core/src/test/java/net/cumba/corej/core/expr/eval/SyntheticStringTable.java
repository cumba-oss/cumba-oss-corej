package net.cumba.corej.core.expr.eval;

import java.util.List;

import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.impl.AbstractDataTable;
import net.cumba.datatable.values.DataValueType;

/**
 * A fast, real (non-Mockito) {@link net.cumba.datatable.IDataTable} of {@code STRING} columns over
 * plain arrays — suitable for the corpus parity harness (thousands of small tables) and the
 * big-table benchmark (millions of rows), neither of which Mockito-based {@code MockTable} can
 * handle at scale. An empty-string cell is treated as missing by both engines
 * ({@code ScalarSemantics.isMissing}), so {@code ""} doubles as the missing marker.
 */
final class SyntheticStringTable extends AbstractDataTable
{

    private final DataTableMeta meta;

    private final String[][] data; // [col][row]

    private final int rowCount;

    SyntheticStringTable(String name, List<String> columns, String[] valueCycle, int rows)
    {
        this.rowCount = rows;
        DataTableColumnMeta[] colMetas = new DataTableColumnMeta[columns.size()];
        this.data = new String[columns.size()][rows];
        for (int c = 0; c < columns.size(); c++)
        {
            colMetas[c] = DataTableColumnMeta.builder().name(columns.get(c)).index(c)
                    .type(DataValueType.STRING).build();
            for (int r = 0; r < rows; r++)
            {
                // Offset by column so different columns hold different values on the same row.
                data[c][r] = valueCycle[(r + c) % valueCycle.length];
            }
        }
        this.meta = DataTableMeta.builder().name(name).label(name).rowCount(rows)
                .totalRowCount(rows).columns(colMetas).build();
    }


    @Override
    public long getRowCount()
    {
        return rowCount;
    }


    @Override
    public DataTableMeta getMetaData()
    {
        return meta;
    }


    @Override
    public Object getValue(long aRow, int aColumn)
    {
        return data[aColumn][(int) aRow];
    }

}
