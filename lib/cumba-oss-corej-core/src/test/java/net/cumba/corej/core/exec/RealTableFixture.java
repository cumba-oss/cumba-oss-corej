package net.cumba.corej.core.exec;

import java.util.ArrayList;
import java.util.List;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.values.DataValueType;

/**
 * A real {@link ColumnCachedDataTable} for tests that need genuine column metadata — the merge
 * views ({@code MergeDataTable}) rebuild every column meta through {@code toBuilder()}, which the
 * testkit's mocked metas do not offer. Same shape as {@code ChildMatchPreMergerTest}'s private
 * fixture, shared so the SUPP-merge tests do not copy it a third time.
 */
final class RealTableFixture
{

    private final String name;

    private final List<String> colNames = new ArrayList<>();

    private final List<DataValueType> colTypes = new ArrayList<>();

    private final List<Object[]> colData = new ArrayList<>();

    private RealTableFixture(String aName)
    {
        name = aName;
    }


    static RealTableFixture of(String aName)
    {
        return new RealTableFixture(aName);
    }


    RealTableFixture str(String aName, String... aValues)
    {
        colNames.add(aName);
        colTypes.add(DataValueType.STRING);
        colData.add(aValues);
        return this;
    }


    RealTableFixture dbl(String aName, Double... aValues)
    {
        colNames.add(aName);
        colTypes.add(DataValueType.DOUBLE);
        colData.add(aValues);
        return this;
    }


    RealTableFixture lng(String aName, Long... aValues)
    {
        colNames.add(aName);
        colTypes.add(DataValueType.LONG);
        colData.add(aValues);
        return this;
    }


    IDataTable build()
    {
        int colCount = colNames.size();
        int rowCount = colData.isEmpty() ? 0 : colData.get(0).length;
        CachedDataTableColumn[] cols = new CachedDataTableColumn[colCount];
        DataTableColumnMeta[] metas = new DataTableColumnMeta[colCount];
        for (int c = 0; c < colCount; c++)
        {
            cols[c] = new CachedDataTableColumn(c, colTypes.get(c));
            metas[c] = DataTableColumnMeta.builder().index(c).name(colNames.get(c))
                    .label(colNames.get(c)).type(colTypes.get(c)).build();
            Object[] data = colData.get(c);
            for (int r = 0; r < rowCount; r++)
            {
                cols[c].addElement(data[r]);
            }
            cols[c].complete();
        }
        DataTableMeta meta = DataTableMeta.builder().name(name).label(name).columns(metas)
                .rowCount(rowCount).totalRowCount(rowCount).build();
        return new ColumnCachedDataTable(meta, cols);
    }
}
