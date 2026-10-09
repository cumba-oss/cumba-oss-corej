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
 * every non-column value is boxed the same way, so comparisons against them behave identically.
 * </p>
 */
public final class DataValues
{

    private DataValues()
    {
    }


    /**
     * A computed number as a <b>typed</b> list member (runbook W2 fold, R5 B-MED-2): a
     * {@link DataValueType#DOUBLE} cell whose text is the number's canonical form
     * ({@link ExprCompiler#canonicalNumberText}: {@code 10}, never {@code 10.0}) — what a
     * dataset-level number renders, and what a member set keys a numeric cell by. A lifted
     * function's numeric answers are carried in a list as these, whichever lift case built the list
     * ({@code ElementwiseLift}, {@code ListVector}).
     *
     * @param value
     *            the number, never {@code null}
     * @return the cell
     */
    public static IDataValue number(Number value)
    {
        return new NumberCell(value, ExprCompiler.canonicalNumberText(value));
    }

    /**
     * {@link #number}'s cell. ⚠ Equal by its canonical text — a list of them is compared by value
     * wherever lists are (a dataset-level binding's uniformity proof, set operations, memo keys),
     * as the boxed numbers they replace were; {@code 3L} and {@code 3.0} are one number.
     */
    private static final class NumberCell implements IDataValue
    {

        private final Number value;

        private final String text;

        NumberCell(Number aValue, String aText)
        {
            value = aValue;
            text = aText;
        }


        @Override
        public Object getValue()
        {
            return value;
        }


        @Override
        public boolean isMissingOrInvalid()
        {
            return false;
        }


        @Override
        public String getValueAsString()
        {
            return text;
        }


        @Override
        public double getValueAsDouble()
        {
            return value.doubleValue();
        }


        @Override
        public DataValueType getType()
        {
            return DataValueType.DOUBLE;
        }


        @Override
        public boolean equals(@Nullable Object other)
        {
            return other instanceof NumberCell cell && text.equals(cell.text);
        }


        @Override
        public int hashCode()
        {
            return text.hashCode();
        }


        @Override
        public String toString()
        {
            return text;
        }
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


            // The value's own text — what Primitives.MemberSet keys a present member by
            // (PLAN-dynamic-column-functions §2.7: a colref(list) member must build the same text
            // member the ${*} collector's getValueAsString() does). The identity default
            // (DataValues$1@…) matched nothing.
            @Override
            public String toString()
            {
                return getValueAsString();
            }
        };
    }

}
