package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.List;

import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.values.DataValueMissing;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;

/**
 * A <b>real</b>, Mockito-free table fixture whose cells may be any {@link MissingValue} — the one
 * shape {@code MockTable} cannot build: its {@code (String) null} mints {@code MIS} only, so a
 * suite written over it cannot tell a producer that carries {@code .A} from one that mints a fresh
 * {@code MIS}. A {@code MissingValue} cell is stored through
 * {@link OverlayDataTable#setDataValue(long, int, net.cumba.datatable.values.IDataValue)} as a
 * {@link DataValueMissing}; every other cell through {@code setValue}. {@code ""} is a present
 * empty string (D34 #1).
 */
final class MissingCellTables
{

    private final String name;

    private final List<String> names = new ArrayList<>();

    private final List<DataValueType> types = new ArrayList<>();

    private final List<Object[]> data = new ArrayList<>();

    private MissingCellTables(String name)
    {
        this.name = name;
    }


    static MissingCellTables of(String name)
    {
        return new MissingCellTables(name);
    }


    /** A {@code STRING} column; each cell a {@link String} or a {@link MissingValue}. */
    MissingCellTables str(String column, Object... cells)
    {
        return column(column, DataValueType.STRING, cells);
    }


    /** A {@code DOUBLE} column; each cell a {@link Double} or a {@link MissingValue}. */
    MissingCellTables dbl(String column, Object... cells)
    {
        return column(column, DataValueType.DOUBLE, cells);
    }


    /** A {@code LONG} column; each cell a {@link Long} or a {@link MissingValue}. */
    MissingCellTables lng(String column, Object... cells)
    {
        return column(column, DataValueType.LONG, cells);
    }


    private MissingCellTables column(String column, DataValueType type, Object... cells)
    {
        names.add(column);
        types.add(type);
        data.add(cells);
        return this;
    }


    IDataTable build()
    {
        int rows = data.isEmpty() ? 0 : data.get(0).length;
        OverlayDataTable t = OverlayDataTable.empty(name, name, rows);
        for (int c = 0; c < names.size(); c++)
        {
            t.addColumn(names.get(c), types.get(c), names.get(c));
        }
        for (int c = 0; c < names.size(); c++)
        {
            Object[] cells = data.get(c);
            if (cells.length != rows)
            {
                throw new IllegalArgumentException(
                        "column " + names.get(c) + " has " + cells.length + " cells, not " + rows);
            }
            for (int r = 0; r < rows; r++)
            {
                if (cells[r] instanceof MissingValue mv)
                {
                    t.setDataValue(r, c, new DataValueMissing(mv));
                }
                else
                {
                    t.setValue(r, c, cells[r]);
                }
            }
        }
        return t;
    }


    /** A one-column {@code STRING} table named {@code T}. */
    static IDataTable strings(String column, Object... cells)
    {
        return of("T").str(column, cells).build();
    }

}
