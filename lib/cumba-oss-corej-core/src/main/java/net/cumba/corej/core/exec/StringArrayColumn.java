package net.cumba.corej.core.exec;

import net.cumba.datatable.AbstractDataTableColumn;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * Minimal {@link AbstractDataTableColumn} backed by a pre-computed {@code String[]}, reported as
 * {@link DataValueType#STRING}; a {@code null} entry reads as missing. Used by
 * {@link ChildMatchPreMerger} to expose the J5-coerced {@code IDVARVAL} tokens in the merged view
 * without copying the other columns (values resolve via an {@code O(1)} array index).
 */
final class StringArrayColumn extends AbstractDataTableColumn
{

    private static final IDataValue MISSING_VALUE = DataValueSupport.getAsDataValue(null,
            DataValueType.MISSING);

    private final @Nullable String[] values;

    StringArrayColumn(int aIndex, @Nullable String[] aValues)
    {
        super(aIndex);
        values = aValues;
    }


    @Override
    public long getRowCount()
    {
        return values.length;
    }


    @Override
    public @Nullable Object getValue(long aRow)
    {
        return values[(int) aRow];
    }


    @Override
    public IDataValue getDataValue(long aRow)
    {
        String v = values[(int) aRow];
        return v == null ? MISSING_VALUE : DataValueSupport.getAsDataValue(v, DataValueType.STRING);
    }
}
