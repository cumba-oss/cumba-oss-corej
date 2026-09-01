package net.cumba.cdisc.core.exec;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * Test fixture builder producing <b>real</b> {@link DataTableMeta} + {@link CachedDataTableColumn}
 * backed tables (not Mockito mocks). Required by every split-domain test: {@code UnionDataTable}'s
 * constructor copies column metadata via {@code DataTableColumnMeta.builderFrom(cm)} →
 * {@code cm.toBuilder()}, which reads the real class's fields — a mocked
 * {@code DataTableColumnMeta} answers {@code null} and NPEs (the same reason
 * {@code ChildMatchPreMergerTest} carries its own real-table fixture).
 */
final class RealTables
{

    private final String name;

    private final List<String> colNames = new ArrayList<>();

    private final List<DataValueType> colTypes = new ArrayList<>();

    private final List<Object[]> colData = new ArrayList<>();

    private RealTables(String aName)
    {
        name = aName;
    }


    static RealTables of(String aName)
    {
        return new RealTables(aName);
    }


    RealTables str(String aName, String... aValues)
    {
        colNames.add(aName);
        colTypes.add(DataValueType.STRING);
        colData.add(aValues);
        return this;
    }


    RealTables lng(String aName, Long... aValues)
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


    /**
     * A {@link DatasetResolver.WithInventory} over the given tables, registered under their
     * upper-cased meta names (production registers upper-cased — {@code LibraryValidator}'s
     * resolver). {@code resolve} is exact-name (upper-cased lookup), so a split domain code misses
     * and the union fallback engages.
     */
    static DatasetResolver.WithInventory inventoryOf(IDataTable... tables)
    {
        Map<String, IDataTable> byName = new LinkedHashMap<>();
        for (IDataTable t : tables)
        {
            String n = t.getMetaData().getName();
            byName.put(n != null ? n.toUpperCase(Locale.ROOT) : "?", t);
        }
        return inventory(byName);
    }


    /** A {@link DatasetResolver.WithInventory} over exactly the given name→table map. */
    static DatasetResolver.WithInventory inventory(Map<String, IDataTable> byName)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String aName)
            {
                return aName == null ? null : byName.get(aName.toUpperCase(Locale.ROOT));
            }


            @Override
            public Set<String> availableDatasets()
            {
                return byName.keySet();
            }
        };
    }

}
