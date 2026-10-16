package net.cumba.corej.core.expr.eval;

import net.cumba.corej.core.exec.ScalarSemantics;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * The value layer of the native evaluator: a column-wide, row-addressable operand.
 *
 * <p>
 * A {@code Vector} resolves a row to <b>one</b> {@link TypedValue} — {@code (type,
 * value-or-missing)} — whichever side of an operator it stands on. The former split into a
 * {@code dataValue} (LHS "name" position) and a {@code resolvedObject} (RHS "value" position,
 * {@code @Nullable Object}) is gone: a {@code column-reference} is a <b>type</b>, not a position
 * (SPEC §1.1). Phase 3d retired the last per-position derivation
 * ({@code TypedValue.comparisonOperand()}) — the scalar comparison primitives read the carrier's
 * typed channels directly; {@link TypedValue#resolved()} remains as the untyped transport of the
 * SPI/collection/temporal surfaces until the typed descriptor model (phases 6b/7).
 * </p>
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
        ListVector,
        BoolListVector
{

    /** The row's operand as the uniform typed carrier. Never {@code null}. */
    TypedValue value(int row);


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
     * {@code value()} cursor of a per-variable rule (an unnamed {@link ColumnVector}), an absent
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
     * Whether this vector stands for an authored column that holds <b>no value in any row</b> —
     * every cell missing or {@code ""} ({@link net.cumba.datatable.ColumnContent#isBlank()}). The
     * column-type gate exempts such a column from its three checks ({@code ECT-Q5} ⓐ, owner
     * 2026-10-16; B5 S12 / T14): its declared type is the provider's guess (CSV types an all-blank
     * column Char, XLSX Num), a property of the transport, not of the data, and the ERROR it raised
     * hid the (rule, dataset)'s other findings.
     *
     * <p>
     * The default is {@code false} — a computed value is never exempt. {@link ColumnVector} scans
     * its column; the joined {@link ComputedVector} answers from the SOURCE column through
     * {@code JoinLookup.sourceColumnIsBlank} (never from the merged cells, D72a-1); a remapped
     * vector delegates to its inner vector, as it delegates {@link #gatedName()}. The gate asks
     * only at its throw point, after the kinds disagree, so the common path costs nothing.
     * </p>
     *
     * @return {@code true} only for an authored column known to be blank in every row
     */
    default boolean isBlank()
    {
        return false;
    }


    /**
     * The statically-declared type of this vector, used by the compiler's operand-homogeneity check
     * (decision #15) and by callers that need a type hint without reading a cell.
     */
    DataValueType declaredType();


    /**
     * Missing per F3: {@code null}, invalid, or empty string. ⚠ Deliberately wider than
     * {@link TypedValue#isMissing()} — the F3 fold treats {@code ""} as blank, which D34 #1 keeps
     * apart from a genuine {@link net.cumba.datatable.values.MissingValue}. Phase 3d moved the
     * scalar comparisons onto the typed carrier but deliberately kept their <em>verdicts</em> on
     * this fold; the D34 #5 total order (a missing low operand fires {@code <}/{@code <=}) is a
     * ruled change no phase row owns yet — see the phase-3d report.
     */
    default boolean isMissing(int row)
    {
        return ScalarSemantics.isMissing(value(row).cell());
    }


    /** The string form of the cell at {@code row}. */
    default String asString(int row)
    {
        return value(row).cell().getValueAsString();
    }


    /**
     * The double form of the cell at {@code row}. ⚠ A raw read <b>below</b> the D85a carrier
     * boundary: a missing or non-numeric cell answers the carrier {@code NaN}, which never leaves
     * the guarded call sites as a result — callers test {@link #isMissing(int)} /
     * {@link TypedValue#isMissing()} first.
     */
    default double asDouble(int row)
    {
        return value(row).cell().getValueAsDouble();
    }

}
