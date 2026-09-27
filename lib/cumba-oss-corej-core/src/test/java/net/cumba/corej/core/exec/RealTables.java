package net.cumba.corej.core.exec;

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
import net.cumba.datatable.impl.support.OverlayDataTable;
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
public final class RealTables
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


    /**
     * A real {@code DOUBLE} column. A {@code NaN} carrying a {@code MissingValue} payload
     * ({@code MissingValue.MIS_A.asDouble()}) is stored as such, so the cell decodes to that marker
     * — the way a real numeric buffer carries a SAS special missing, and the one fixture shape that
     * can put a {@code MIS_A} into a key column ({@code MockTable} has no such column).
     */
    RealTables dbl(String aName, Double... aValues)
    {
        colNames.add(aName);
        colTypes.add(DataValueType.DOUBLE);
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
     * The same table held <b>raw</b> in an {@link OverlayDataTable}: no DOUBLE buffer sees the
     * cells, so a {@code -0.0} stays a {@code -0.0}. Since {@code NZL O1}
     * (PLAN-negative-zero-on-load) a buffer-built table ({@link #build()}) stores a {@code -0.0} as
     * {@code 0.0}, so a signed-zero test built that way passes with the key-level zero handling
     * never entered. A raw {@code -0.0} is the shape of a computed key, which that handling is kept
     * for ({@code NZL Q2}).
     */
    IDataTable buildRaw()
    {
        int colCount = colNames.size();
        int rowCount = colData.isEmpty() ? 0 : colData.get(0).length;
        OverlayDataTable t = OverlayDataTable.empty(name, name, rowCount);
        for (int c = 0; c < colCount; c++)
        {
            t.addColumn(colNames.get(c), colTypes.get(c), colNames.get(c));
        }
        for (int c = 0; c < colCount; c++)
        {
            Object[] data = colData.get(c);
            for (int r = 0; r < rowCount; r++)
            {
                t.setValue(r, c, data[r]);
            }
        }
        return t;
    }


    /**
     * A {@link DatasetResolver.WithInventory} over the given tables, registered under their
     * upper-cased meta names (production registers upper-cased — {@code LibraryValidator}'s
     * resolver). {@code resolve} is exact-name (upper-cased lookup), so a split domain code misses
     * and the union fallback engages.
     */
    public static DatasetResolver.WithInventory inventoryOf(IDataTable... tables)
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
