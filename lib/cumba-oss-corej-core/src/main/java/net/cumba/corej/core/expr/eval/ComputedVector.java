package net.cumba.corej.core.expr.eval;

import java.util.function.IntFunction;

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
     * is produced as an {@link IDataValue} that keeps its own type, and the carrier's resolved
     * channel is derived from it as text rather than the other way round.
     *
     * <p>
     * Step B of {@code PLAN-joined-column-typing}. A dotted joined reference used to publish
     * {@code STRING} and resolve through {@code JoinLookup.lookup}, whose text form routes a
     * numeric value through {@code getAsDoubleCleaned}'s 12-significant-digit rounding.
     * </p>
     */
    private final @Nullable IntFunction<IDataValue> typedProducer;

    /** J7: the authored name this vector stands for, when it is a gated column reference. */
    private final @Nullable String gatedName;

    /**
     * Per-row memo of the produced carrier — one producer call per row, however many channels of
     * the {@link TypedValue} are read (F9: produce ONCE, derive every view from the result).
     */
    private final @Nullable TypedValue[] rowCache;

    /**
     * @param rowCount
     *            the number of rows the vector spans
     * @param declaredType
     *            the statically-declared result type (for operand-homogeneity checks)
     * @param producer
     *            computes the value for a given 0-based row index.
     *            <p>
     *            ⚑ TARGET-INVARIANT(null-free-value-channel) — <b>NOT YET ENFORCED ON THIS
     *            CONSTRUCTOR.</b> A row value is owed as a real value or a
     *            {@link net.cumba.datatable.values.MissingValue} and never {@code null}
     *            ({@link net.cumba.corej.core.exec.ScalarSemantics#computedMissing()} carries the
     *            rule and the promotion condition), and this is the channel every VALUE builtin
     *            produces through — so it is the widest surface on which the rule is still
     *            violated. Today a {@code null} here is tolerated and
     *            {@link TypedValue#resolved(DataValueType, Object)} folds it to
     *            {@code MissingValue.MIS}. It is tolerated only because {@code IntFunction<Object>}
     *            is a GENERIC type argument, whose lambda return NullAway does not check — not
     *            because {@code null} is legitimate. ⛔ Do not add a new {@code null}-returning
     *            producer here; hand back {@code ScalarSemantics.computedMissing()} instead, so the
     *            day this channel is hardened is a signature change and not a sweep.
     *            </p>
     */
    public ComputedVector(int rowCount, DataValueType declaredType, IntFunction<Object> producer)
    {
        this(rowCount, declaredType, producer, null, null);
    }


    private ComputedVector(int rowCount, DataValueType declaredType, IntFunction<Object> producer,
            @Nullable IntFunction<IDataValue> typedProducer, @Nullable String gatedName)
    {
        this.declaredType = declaredType;
        this.producer = producer;
        this.typedProducer = typedProducer;
        // F8: final and constructor-set. This field decides whether the vector is gated at all, so
        // an unsafely-published instance reading null would silently exit the column-type gate.
        this.gatedName = gatedName;
        this.rowCache = new TypedValue[rowCount];
    }


    /**
     * A vector whose rows are produced as <b>typed</b> {@link IDataValue}s.
     *
     * <p>
     * ⭐ The carrier's resolved channel derives from the produced cell's {@code getValueAsString()},
     * so every textual consumer — membership needles, {@code str()} surfaces, report text — sees
     * exactly the text the untyped vector produced. Only the cell channel (and the typed reads the
     * comparison primitives take from it since phase 3d) exposes the type
     * ({@link TypedValue#typedCell}). That containment is deliberate: roughly thirty call sites
     * consume the resolved channel and the textual ones fold via {@code toString()}, which quotes
     * on {@code DataValueString} and is unoverridden on {@code DataValues.of}.
     * </p>
     *
     * @param rowCount
     *            the number of rows the vector spans
     * @param declaredType
     *            the joined column's declared type
     * @param typedProducer
     *            computes the row's typed value. ⭐ <b>Never {@code null}</b> — and, unlike the
     *            untyped constructor above, that is <b>enforced</b>: the {@code @Nullable} is gone
     *            from this type argument, so NullAway (ERROR, JSpecify mode, main sources) rejects
     *            a {@code null}-returning producer at compile time. A producer with no result hands
     *            back {@link net.cumba.corej.core.exec.ScalarSemantics#computedMissing()}; one
     *            carrying a specific missing identity hands the identity-bearing cell through; an
     *            absent column hands back its type-derived constant
     * @return the typed vector
     */
    public static ComputedVector typed(int rowCount, DataValueType declaredType,
            IntFunction<IDataValue> typedProducer)
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
     *            computes the row's typed value; never {@code null} (see
     *            {@link #typed(int, DataValueType, IntFunction)})
     * @param gatedName
     *            the authored name, or {@code null} to stay outside the gate
     * @return the typed vector
     */
    public static ComputedVector typed(int rowCount, DataValueType declaredType,
            IntFunction<IDataValue> typedProducer, @Nullable String gatedName)
    {
        return typedInternal(rowCount, declaredType, typedProducer, gatedName);
    }


    private static ComputedVector typedInternal(int rowCount, DataValueType declaredType,
            IntFunction<IDataValue> typedProducer, @Nullable String gatedName)
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


    @Override
    public TypedValue value(int row)
    {
        TypedValue tv = rowCache[row];
        if (tv == null)
        {
            tv = typedProducer != null
                    ? TypedValue.typedCell(declaredType, typedProducer.apply(row))
                    : TypedValue.resolved(declaredType, producer.apply(row));
            rowCache[row] = tv;
        }
        return tv;
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
