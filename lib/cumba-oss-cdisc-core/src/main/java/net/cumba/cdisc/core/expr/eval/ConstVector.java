package net.cumba.cdisc.core.expr.eval;

import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * A broadcast {@link Vector}: the same resolved value for every row. Used for literals,
 * run-constant lists, and pre-resolved {@code $}-operation scalar results. The wrapping
 * {@link IDataValue} is computed once at construction (the value is row-independent), so per-row
 * reads allocate nothing.
 *
 * @param value
 *            the broadcast value (a {@link String}, {@link Number}, {@link Boolean}, list, or
 *            {@code null})
 * @param declaredType
 *            the statically-declared type for operand-homogeneity checks
 * @param cell
 *            the cached {@link IDataValue} wrapper for {@code value}
 */
public record ConstVector(@Nullable Object value, DataValueType declaredType,
        IDataValue cell) implements Vector
{

    /** Builds a {@code ConstVector} for {@code value}, deriving its declared type. */
    public static ConstVector of(@Nullable Object value)
    {
        return new ConstVector(value, typeOf(value), DataValues.of(value));
    }


    private static DataValueType typeOf(@Nullable Object value)
    {
        if (value == null)
        {
            return DataValueType.MISSING;
        }
        if (value instanceof Long || value instanceof Integer)
        {
            return DataValueType.LONG;
        }
        if (value instanceof Number)
        {
            return DataValueType.DOUBLE;
        }
        if (value instanceof Boolean)
        {
            return DataValueType.BOOLEAN;
        }
        return DataValueType.STRING;
    }


    @Override
    public IDataValue dataValue(int row)
    {
        return cell;
    }


    @Override
    public @Nullable Object resolvedObject(int row)
    {
        return value;
    }

}
