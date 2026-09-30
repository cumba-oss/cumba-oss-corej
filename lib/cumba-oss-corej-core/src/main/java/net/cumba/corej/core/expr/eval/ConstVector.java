package net.cumba.corej.core.expr.eval;

import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * A broadcast {@link Vector}: the same resolved value for every row. Used for literals,
 * run-constant lists, and pre-resolved {@code $}-operation scalar results. The wrapping
 * {@link TypedValue} is computed once at construction (the value is row-independent), so per-row
 * reads allocate nothing.
 *
 * @param value
 *            the broadcast value (a {@link String}, {@link Number}, {@link Boolean}, list, or
 *            {@code null})
 * @param declaredType
 *            the statically-declared type for operand-homogeneity checks
 * @param typed
 *            the cached {@link TypedValue} carrier for {@code value}
 */
public record ConstVector(@Nullable Object value, DataValueType declaredType,
        TypedValue typed) implements Vector
{

    /**
     * Builds a {@code ConstVector} for {@code value}, deriving its declared type.
     *
     * <p>
     * ⭐ A list value holds no {@code null} element (register {@code NNL §1}): when {@code value} is
     * a {@link java.util.Collection} it passes the {@link net.cumba.corej.core.exec.ListValueGuard}
     * here, once per construction — the birth site of every literal list binding,
     * {@code variableVector} over an operation result, the list accessors
     * ({@code DefineMetadataListCodec.decode}), {@code CodelistAttributes}, the injected
     * {@code library_variable_*_values} and every compile-time constant fold. Per-row reads of the
     * cached {@link TypedValue} never rescan it. A scalar {@code null} is the untyped scalar
     * channel ({@code NF §1}, still {@code target}) and is not this guard's concern.
     * </p>
     */
    public static ConstVector of(@Nullable Object value)
    {
        return of(value, () -> "a constant list value (ConstVector.of)");
    }


    /**
     * As {@link #of(Object)}, naming the <b>producer</b> of a list value for the
     * {@link net.cumba.corej.core.exec.ListValueGuard}'s message — the list accessor, the variable,
     * the function — so a rule that ERRORs on a {@code null} element says where the list was born
     * ({@code PLAN-no-null-list-elements} review round 1, LOW-1). The one-argument form names only
     * <i>"a constant list value"</i>; a birth site that knows its producer uses this one.
     *
     * @param value
     *            the broadcast value
     * @param producer
     *            names the producer — evaluated only when a {@code null} element is found
     * @return the vector
     */
    public static ConstVector of(@Nullable Object value,
            java.util.function.Supplier<String> producer)
    {
        if (value instanceof java.util.Collection<?>)
        {
            net.cumba.corej.core.exec.ListValueGuard.requireNoNullElement(value, producer);
        }
        DataValueType type = typeOf(value);
        return new ConstVector(value, type, TypedValue.resolved(type, value));
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
    public TypedValue value(int row)
    {
        return typed;
    }


    /**
     * The value a list / membership reader folds: the broadcast value, except that a numeric one
     * carried from a data cell reads as that cell's text — exactly what the per-row
     * {@code resolved()} of the same cell answers. A dataset-level answer built from a cell
     * ({@code GroupedAggregate.constantCell}: an ungrouped {@code read_value}, {@code max}) keeps
     * the cell's number as its hand-over payload, and folded as that payload a DOUBLE {@code 42}
     * read {@code "42.0"} where the cell's text is {@code "42"} (combined review of runbook W2–W8,
     * round 2 L3). A literal number carries no cell and is its own value.
     *
     * @return the value to fold into a list or a member set
     */
    public @Nullable Object memberValue()
    {
        return value instanceof Number ? typed.resolved() : value;
    }

}
