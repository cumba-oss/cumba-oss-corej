package net.cumba.corej.core.expr.eval;

import net.cumba.corej.core.exec.ScalarSemantics;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The value layer of the native evaluator: a column-wide, row-addressable operand.
 *
 * <p>
 * A {@code Vector} exposes a cell two ways, mirroring how the legacy engine splits operand
 * resolution between the left-hand "name" position and the right-hand "value" position:
 * </p>
 * <ul>
 * <li>{@link #dataValue(int)} — the typed {@link IDataValue} for the row, used where the legacy
 * engine reads the <i>name</i> column (LHS). Carries the declared {@link DataValueType} so the
 * polymorphic date comparison can decide numeric-vs-ISO exactly as the legacy operators do.</li>
 * <li>{@link #resolvedObject(int)} — the plain {@code Object} for the row, used where the legacy
 * engine resolves the <i>value</i> operand (RHS) via {@code ValueResolver}. A column reference
 * resolves to its cell's string (or {@code null} when missing/invalid), a literal to its boxed
 * value. This keeps {@code A == B}, {@code date(A) > date(B)}, etc. bit-for-bit aligned with the
 * legacy {@code resolve} path.</li>
 * </ul>
 *
 * <p>
 * Implementations are pure with respect to the underlying table and may be shared across threads
 * only insofar as the table is; {@link ComputedVector} memoises within a single evaluation and is
 * therefore single-thread / one-chunk scoped.
 * </p>
 */
public sealed interface Vector
        permits
        ColumnVector,
        ConstVector,
        ComputedVector,
        JoinedCandidatesVector
{

    /** The typed value at {@code row} (LHS-style access). Never {@code null}. */
    IDataValue dataValue(int row);


    /**
     * The resolved operand at {@code row} (RHS-style access), mirroring {@code ValueResolver}:
     * {@code null} when missing/invalid, a {@link String} for a column reference, or the boxed
     * literal value.
     */
    @Nullable
    Object resolvedObject(int row);


    /**
     * The authored column name this vector stands for, or {@code null} when it is not an authored
     * column reference.
     *
     * <p>
     * <b>J7</b> of {@code PLAN-joined-column-typing}: {@link ColumnTypeGate} used to gate only a
     * named {@link ColumnVector}, so a dotted joined reference — a {@link ComputedVector} — was
     * never type-checked however wrong its type was. The gate needs exactly two things, a name for
     * its message and a declared type to check, and both live on {@link Vector}. Exposing the name
     * here widens eligibility without a {@code NamedTypedVector} abstraction or an edit to this
     * interface's {@code permits} clause.
     * </p>
     *
     * <p>
     * ⛔ Returning {@code null} keeps a vector OUT of the gate, and several do so deliberately: the
     * {@code value()} cursor of a per-variable rule (an unnamed {@code ColumnVector}), an absent
     * column folded to an all-missing constant, and a {@code ${…}}-substituted operand, whose
     * column name resolves per row so no single declared type is correct.
     * </p>
     *
     * @return the authored name, or {@code null} when this vector is not gated.
     */
    default @Nullable String gatedName()
    {
        return null;
    }


    /**
     * The row's right-hand operand for a <b>plain numeric comparison</b> ({@code ==}/{@code !=} via
     * {@link Primitives#equality} and the four order operators via {@link Primitives#comparison}).
     *
     * <p>
     * Defaults to {@link #resolvedObject(int)} and is overridden only by {@link ColumnVector},
     * which returns the cell's {@link net.cumba.datatable.values.IDataValue} for a numeric column
     * instead of its text. That is the whole of Step A of {@code PLAN-joined-column-typing}:
     * resolving a numeric operand as text routes it through
     * {@code DataValueSupport.getAsDoubleCleaned}'s <b>12-significant-digit rounding</b>, so two
     * identical {@code DOUBLE} columns holding {@code 10000000000001} compared as {@code A != B}
     * answered TRUE — the engine reporting identical values as different.
     * </p>
     *
     * <p>
     * ⚠⚠ This exists as a <b>separate</b> accessor rather than as a change to
     * {@link #resolvedObject(int)} for a measured reason: roughly thirty call sites consume
     * {@code resolvedObject}, and the textual ones fold via {@code toString()}, not
     * {@code getValueAsString()}. {@code DataValueString.toString()} <b>quotes</b> its value and
     * the anonymous {@code DataValues.of} carries no {@code toString()} override at all, so
     * widening {@code resolvedObject} would corrupt membership needles, {@code str()} comparisons
     * and report text. Confining the typed operand to the two consumers that need the exact value
     * keeps every other surface byte-identical <b>by construction</b>.
     * </p>
     *
     * <p>
     * ⛔ The {@code date}/{@code date_part}/{@code time_part} family deliberately does NOT use this
     * (decision D11): those comparisons carry their own {@code DATE_EPSILON} tolerance over SAS day
     * numbers and keep resolving through {@link #resolvedObject(int)}.
     * </p>
     *
     * @param row
     *            the 0-based row index.
     * @return the typed operand where one is available, else exactly {@link #resolvedObject(int)}.
     */
    default @Nullable Object comparisonOperand(int row)
    {
        return resolvedObject(row);
    }


    /**
     * The statically-declared type of this vector, used by the compiler's operand-homogeneity check
     * (decision #15) and by callers that need a type hint without reading a cell.
     */
    DataValueType declaredType();


    /** Missing per F3: {@code null}, invalid, or empty string. */
    default boolean isMissing(int row)
    {
        return ScalarSemantics.isMissing(dataValue(row));
    }


    /** The string form of the cell at {@code row}. */
    default String asString(int row)
    {
        return dataValue(row).getValueAsString();
    }


    /** The double form of the cell at {@code row} ({@code NaN} if non-numeric). */
    default double asDouble(int row)
    {
        return dataValue(row).getValueAsDouble();
    }

}
