package net.cumba.corej.core.expr.eval;

import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * Factory for ad-hoc {@link IDataValue} wrappers around plain Java objects (broadcast literals,
 * {@code $}-operation results, joined-dataset string values, computed values).
 *
 * <p>
 * The wrapping semantics are (which now delegates here): {@code null} → missing; a {@link Number}
 * reports {@link DataValueType#DOUBLE} and a numeric double; anything else reports
 * {@link DataValueType#STRING} with the value's {@code toString()}. Keeping this in one place means
 * the native evaluator and the legacy engine box non-column values the same way, so comparisons
 * against them behave identically.
 * </p>
 */
public final class DataValues
{

    private DataValues()
    {
    }


    /** Wraps {@code value} as an {@link IDataValue}; {@code null} becomes a missing value. */
    public static IDataValue of(@Nullable Object value)
    {
        return new IDataValue()
        {

            @Override
            public Object getValue()
            {
                return value != null ? value : MissingValue.MIS;
            }


            @Override
            public boolean isMissingOrInvalid()
            {
                return value == null;
            }


            @Override
            public String getValueAsString()
            {
                return value != null ? value.toString() : "";
            }


            @Override
            public double getValueAsDouble()
            {
                if (value instanceof Number n)
                {
                    return n.doubleValue();
                }
                if (value instanceof String s)
                {
                    try
                    {
                        return Double.parseDouble(s);
                    }
                    catch (NumberFormatException _)
                    {
                        return Double.NaN;
                    }
                }
                return Double.NaN;
            }


            @Override
            public DataValueType getType()
            {
                if (value == null)
                {
                    return DataValueType.MISSING;
                }
                if (value instanceof Number)
                {
                    return DataValueType.DOUBLE;
                }
                return DataValueType.STRING;
            }
        };
    }

}
