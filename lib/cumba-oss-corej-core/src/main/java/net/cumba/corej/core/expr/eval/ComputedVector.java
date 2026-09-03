package net.cumba.corej.core.expr.eval;

import java.util.function.IntFunction;

import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;

/**
 * A lazily-materialised {@link Vector} for value functions such as {@code lower(X)}, {@code len(X)}
 * or {@code date(X)}. Each row's value is produced on first access and memoised for the rest of the
 * evaluation, so a sub-expression referenced twice is computed once (within-evaluation common
 * sub-expression reuse — decision #8). The cache is per-instance and therefore scoped to one
 * evaluation / chunk; a fresh {@code ComputedVector} is built per evaluation.
 *
 * <p>
 * Not thread-safe: a single {@code ComputedVector} must not be shared across the cohort threads.
 * The native evaluator builds them inside the per-evaluation run state, never caching them across
 * runs.
 * </p>
 */
public final class ComputedVector implements Vector
{

    private final DataValueType declaredType;

    private final IntFunction<Object> producer;

    private final Object[] valueCache;

    private final IDataValue[] cellCache;

    private final boolean[] computed;

    /**
     * @param rowCount
     *            the number of rows the vector spans
     * @param declaredType
     *            the statically-declared result type (for operand-homogeneity checks)
     * @param producer
     *            computes the value for a given 0-based row index; may return {@code null} to
     *            denote a missing result
     */
    public ComputedVector(int rowCount, DataValueType declaredType, IntFunction<Object> producer)
    {
        this.declaredType = declaredType;
        this.producer = producer;
        this.valueCache = new Object[rowCount];
        this.cellCache = new IDataValue[rowCount];
        this.computed = new boolean[rowCount];
    }


    private Object value(int row)
    {
        if (!computed[row])
        {
            valueCache[row] = producer.apply(row);
            computed[row] = true;
        }
        return valueCache[row];
    }


    @Override
    public IDataValue dataValue(int row)
    {
        IDataValue cell = cellCache[row];
        if (cell == null)
        {
            cell = DataValues.of(value(row));
            cellCache[row] = cell;
        }
        return cell;
    }


    @Override
    public Object resolvedObject(int row)
    {
        return value(row);
    }


    @Override
    public DataValueType declaredType()
    {
        return declaredType;
    }

}
