package net.cumba.dataviewer.examples.cdt;

import java.io.IOException;
import java.nio.file.Path;

import net.cumba.datatable.IDataTable;

/**
 * Thin adapter that delegates to {@link net.cumba.datatable.provider.cdt.CdtWriter} in the CDT
 * provider module. Kept in place so existing callers continue to compile.
 */
public final class CdtWriter
{

    private CdtWriter()
    {
    }


    /**
     * Writes the given table to {@code aPath} in {@code .cdt} format, overwriting any existing
     * content.
     */
    public static void write(IDataTable aTable, Path aPath) throws IOException
    {
        net.cumba.datatable.provider.cdt.CdtWriter.write(aTable, aPath);
    }


    /**
     * Serialises the table to a {@code .cdt}-formatted string.
     */
    public static String toString(IDataTable aTable)
    {
        return net.cumba.datatable.provider.cdt.CdtWriter.toString(aTable);
    }
}
