package net.cumba.corej.core.expr.eval;

import java.util.function.IntFunction;

import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * A lazily-materialised {@link Vector} for value functions such as {@code lower(X)}, {@code len(X)}
 * or {@code date(X)}. Each row's value is produced on first access and memoised for the rest of the
 * evaluation, so a sub-expression referenced twice is computed once (within-evaluation common
 * sub-expression reuse — decision #8). The cache is per-instance and therefore scoped to one
 * evaluation / chunk; a fresh {@code ComputedVector} is built per evaluation.
 *
 * <p>
 * Not thread-safe: a single {@code ComputedVector} must not be shared across the rule threads. The
 * native evaluator builds them inside the per-evaluation run state, never caching them across runs.
 * </p>
 */
public final class ComputedVector implements Vector
{

    private final DataValueType declaredType;

    private final IntFunction<Object> producer;

    /**
     * Optional typed producer ({@code null} for an ordinary computed vector). When present the row
     * is produced as an {@link IDataValue} that keeps its own type, and {@link #resolvedObject} is
     * derived from it as text rather than the other way round.
     *
     * <p>
     * Step B of {@code PLAN-joined-column-typing}. A dotted joined reference used to publish
     * {@code STRING} and resolve through {@code JoinLookup.lookup}, whose text form routes a
     * numeric value through {@code getAsDoubleCleaned}'s 12-significant-digit rounding.
     * </p>
     */
    private final @Nullable IntFunction<@Nullable IDataValue> typedProducer;

    /** J7: the authored name this vector stands for, when it is a gated column reference. */
    private final @Nullable String gatedName;

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
        this(rowCount, declaredType, producer, null, null);
    }


    private ComputedVector(int rowCount, DataValueType declaredType, IntFunction<Object> producer,
            @Nullable IntFunction<@Nullable IDataValue> typedProducer, @Nullable String gatedName)
    {
        this.declaredType = declaredType;
        this.producer = producer;
        this.typedProducer = typedProducer;
        // F8: final and constructor-set. This field decides whether the vector is gated at all, so
        // an unsafely-published instance reading null would silently exit the column-type gate.
        this.gatedName = gatedName;
        this.valueCache = new Object[rowCount];
        this.cellCache = new IDataValue[rowCount];
        this.computed = new boolean[rowCount];
    }


    /**
     * A vector whose rows are produced as <b>typed</b> {@link IDataValue}s.
     *
     * <p>
     * ⭐ {@link #resolvedObject} is derived from the produced cell's {@code getValueAsString()}, so
     * every textual consumer — membership needles, {@code str()} surfaces, report text — sees
     * exactly the text the untyped vector produced. Only {@link #dataValue} and
     * {@link #comparisonOperand} expose the type. That containment is deliberate: roughly thirty
     * call sites consume {@code resolvedObject} and the textual ones fold via {@code toString()},
     * which quotes on {@code DataValueString} and is unoverridden on {@code DataValues.of}.
     * </p>
     *
     * @param rowCount
     *            the number of rows the vector spans
     * @param declaredType
     *            the joined column's declared type
     * @param typedProducer
     *            computes the row's typed value, {@code null} for a missing result
     * @return the typed vector
     */
    public static ComputedVector typed(int rowCount, DataValueType declaredType,
            IntFunction<@Nullable IDataValue> typedProducer)
    {
        return typedInternal(rowCount, declaredType, typedProducer, null);
    }


    /**
     * {@link #typed(int, DataValueType, IntFunction)} with a <b>gated name</b> (J7), so the
     * column-type gate can check this vector and name it in a mismatch message.
     *
     * @param rowCount
     *            the number of rows the vector spans
     * @param declaredType
     *            the joined column's declared type
     * @param typedProducer
     *            computes the row's typed value, {@code null} for a missing result
     * @param gatedName
     *            the authored name, or {@code null} to stay outside the gate
     * @return the typed vector
     */
    public static ComputedVector typed(int rowCount, DataValueType declaredType,
            IntFunction<@Nullable IDataValue> typedProducer, @Nullable String gatedName)
    {
        return typedInternal(rowCount, declaredType, typedProducer, gatedName);
    }


    private static ComputedVector typedInternal(int rowCount, DataValueType declaredType,
            IntFunction<@Nullable IDataValue> typedProducer, @Nullable String gatedName)
    {
        // ⚑ The untyped producer is never invoked for a typed vector (value() branches on
        // typedProducer), so it is a guard rather than a code path: if the branching is ever
        // removed, this throws loudly instead of silently producing a second, divergent value.
        return new ComputedVector(rowCount, declaredType, _ ->
        {
            throw new IllegalStateException(
                    "a typed ComputedVector must produce through its typed producer");
        }, typedProducer, gatedName);
    }


    private Object value(int row)
    {
        if (!computed[row])
        {
            if (typedProducer != null)
            {
                // F9: produce ONCE and derive both views from the result. The typed path used to
                // call the producer from here (through a text-converting wrapper) and AGAIN from
                // dataValue, so a row whose text and typed forms were both read cost two
                // lookupValue calls -- or two full lookupAllValues scans for a candidates vector.
                IDataValue cell = typedProducer.apply(row);
                cellCache[row] = cell == null
                        ? DataValueSupport.getAsDataValue(null, DataValueType.MISSING)
                        : cell;
                valueCache[row] = cell == null || cell.isMissingOrInvalid() ? null
                        : cell.getValueAsString();
            }
            else
            {
                valueCache[row] = producer.apply(row);
            }
            computed[row] = true;
        }
        return valueCache[row];
    }


    @Override
    public IDataValue dataValue(int row)
    {
        if (typedProducer != null)
        {
            // value() populates cellCache for the typed path -- one producer call, both views.
            value(row);
            return cellCache[row];
        }
        IDataValue cell = cellCache[row];
        if (cell == null)
        {
            cell = DataValues.of(value(row));
            cellCache[row] = cell;
        }
        return cell;
    }


    /**
     * {@inheritDoc} For a typed vector this is the produced cell, so a numeric joined operand
     * reaches the comparison as a value rather than as rounded text.
     */
    @Override
    public @Nullable Object comparisonOperand(int row)
    {
        if (typedProducer != null)
        {
            IDataValue cell = dataValue(row);
            return cell.isMissingOrInvalid() ? resolvedObject(row) : cell;
        }
        return resolvedObject(row);
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


    @Override
    public @Nullable String gatedName()
    {
        return gatedName;
    }

}
