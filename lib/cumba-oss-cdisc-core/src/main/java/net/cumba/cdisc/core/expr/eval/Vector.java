package net.cumba.cdisc.core.expr.eval;

import net.cumba.cdisc.core.exec.ScalarSemantics;
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
