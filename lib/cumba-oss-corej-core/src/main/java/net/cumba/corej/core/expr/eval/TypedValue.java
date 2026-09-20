package net.cumba.corej.core.expr.eval;

import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * The uniform typed value carrier of the native evaluator — one row's operand as
 * {@code (type, value-or-missing)} (SPEC §1.1, phase 3 of {@code PLAN-typed-expression-engine}).
 *
 * <p>
 * Before this type, {@link Vector} exposed a cell <em>positionally</em>: {@code dataValue(row)}
 * where the legacy engine read the LHS "name" column and {@code resolvedObject(row)} where it
 * resolved the RHS "value" operand — the plan's oldest named defect, <i>"positional asymmetry
 * inside the value layer"</i>. A {@code column-reference} is a <b>type</b>, not a position (SPEC
 * §1.1), so both sides of any operator now resolve to this one carrier and ask it questions.
 * </p>
 *
 * <p>
 * ⭐ <b>Nothing is destroyed on the way in (D84b).</b> The carrier keeps the cell's own
 * {@link IDataValue} — the exact number, never a text round-trip (D84) — and, when the cell is
 * missing, decodes <b>which</b> {@link MissingValue} it was ({@link #missing()}), where the old
 * {@code @Nullable Object} resolution flattened every missing to {@code null} (§4.2, D12).
 * </p>
 *
 * <p>
 * ⚠⚠ <b>D85a — the carrier boundary.</b> A numeric {@code MissingValue} travels the datatable as a
 * quiet {@code NaN} whose mantissa bits {@code [50:43]} hold the marker byte, and
 * {@link #missingIdentityOf(IDataValue)} is where that encoding is decoded into the
 * {@code MissingValue} it names. "The engine has no {@code NaN} values" (D85) means {@code NaN}
 * never denotes a numeric <em>result</em>; it remains the on-the-wire encoding below this boundary,
 * so a raw read such as {@link IDataValue#getValueAsDouble()} on a missing cell still answers the
 * payload {@code NaN} — callers above the boundary go through {@link #isMissing()} /
 * {@link #missing()} instead of testing {@code NaN}.
 * </p>
 *
 * <p>
 * <b>Phase 3d closed the D99a residue.</b> The per-position comparison derivation
 * ({@code comparisonOperand()}, with its {@code COLUMN}/{@code TYPED_CELL}/{@code RESOLVED} source
 * enum) is gone: the scalar comparison primitives ({@code Primitives.equality} /
 * {@code Primitives.comparison}) read this carrier's typed channels directly —
 * {@link #sourceCell()} for a cell-backed operand (the exact value, never a text round-trip, D84)
 * and {@link #resolved()} only for a resolved literal / untyped computed payload. What remains of
 * the legacy shape is {@link #resolved()} itself — the untyped transport the SPI surface, the
 * collection paths (SPEC §1.5 types phase 6b introduces descriptors for) and the temporal families'
 * deliberate string reads (3b: a temporal value's runtime carrier is its ISO-8601 string; the type
 * lives in the compiler's routing, D102) still consume. It retires with the typed descriptor model
 * (phases 6b/7), not before.
 * </p>
 *
 * <p>
 * Not thread-safe beyond its construction: {@link #cell()} memoises a derived wrapper for
 * non-cell-sourced values, matching the evaluation-scoped, single-threaded contract of
 * {@link ComputedVector}.
 * </p>
 */
public final class TypedValue
{

    private final DataValueType type;

    /** The verbatim cell for a cell-sourced value; {@code null} for a resolved literal/computed. */
    private final @Nullable IDataValue sourceCell;

    /**
     * The resolved payload for a literal / untyped computed value; {@code null} when missing or
     * cell-sourced.
     */
    private final @Nullable Object payload;

    /** The missing identity; non-{@code null} iff this value is missing. */
    private final @Nullable MissingValue missing;

    /** Lazily-derived {@link DataValues#of} wrapper for non-cell-sourced values. */
    private @Nullable IDataValue derivedCell;

    private TypedValue(DataValueType type, @Nullable IDataValue sourceCell,
            @Nullable Object payload, @Nullable MissingValue missing)
    {
        this.type = type;
        this.sourceCell = sourceCell;
        this.payload = payload;
        this.missing = missing;
    }


    /**
     * A resolved primary-table cell ({@link ColumnVector}).
     *
     * @param declaredType
     *            the column's declared type, from its {@code DataTableColumnMeta}
     * @param cell
     *            the cell, kept verbatim
     * @return the carrier
     */
    public static TypedValue column(DataValueType declaredType, IDataValue cell)
    {
        return new TypedValue(declaredType, cell, null, missingIdentityOf(cell));
    }


    /**
     * A typed-producer cell ({@code ComputedVector.typed} — dotted joined references,
     * {@code ${…}}-substituted operands, arithmetic results).
     *
     * @param declaredType
     *            the producer's declared type
     * @param cell
     *            the produced cell. ⭐⭐ <b>Never {@code null}</b> — see
     *            {@link net.cumba.corej.core.exec.ScalarSemantics#computedMissing()} for the rule
     *            and for which of the three non-values a producer owes. A producer with no result
     *            hands back {@code computedMissing()} ({@link MissingValue#MIS}, D36 #8); one that
     *            must <em>carry</em> a specific missing identity (D85c/D86a propagation) hands the
     *            identity-bearing cell through; an absent column hands back its type-derived
     *            constant. This parameter used to be {@code @Nullable} and this method used to mint
     *            the {@code MIS} sentinel itself, which is exactly what let eleven producers return
     *            {@code null} unnoticed — the nullability is gone so NullAway rejects the next one
     *            at compile time
     * @return the carrier
     */
    public static TypedValue typedCell(DataValueType declaredType, IDataValue cell)
    {
        return new TypedValue(declaredType, cell, null, missingIdentityOf(cell));
    }


    /**
     * A literal or untyped computed value ({@link ConstVector}, plain {@link ComputedVector}).
     *
     * @param declaredType
     *            the statically-declared type
     * @param payload
     *            the resolved payload — a {@link String}, {@link Number}, {@link Boolean}, list, or
     *            {@code null} for a computed missing ({@link MissingValue#MIS}, D36 #8)
     * @return the carrier
     */
    public static TypedValue resolved(DataValueType declaredType, @Nullable Object payload)
    {
        return new TypedValue(declaredType, null, payload,
                payload == null ? MissingValue.MIS : null);
    }


    /**
     * ⭐ <b>D85a — the decode side of the carrier boundary</b>: which {@link MissingValue} a cell
     * carries, or {@code null} for a present value.
     *
     * <p>
     * Two encodings arrive here: a cell whose stored value <em>is</em> the {@code MissingValue}
     * object, and a numeric cell whose double is a quiet {@code NaN} with the marker byte in its
     * mantissa (bits {@code [50:43]}). A payload-less {@code NaN} — an invalid value rather than an
     * encoded marker — decodes to the generic {@link MissingValue#MIS}, the same disposition
     * {@code GroupKeyPolicy.keyPart} rules for the keying path.
     * </p>
     *
     * @param cell
     *            the cell to decode
     * @return the missing identity, or {@code null} when the cell holds a value
     */
    public static @Nullable MissingValue missingIdentityOf(IDataValue cell)
    {
        if (cell.getValue() instanceof MissingValue m)
        {
            return m;
        }
        if (cell.isMissingOrInvalid())
        {
            double d = cell.getValueAsDouble();
            return Double.isNaN(d) ? MissingValue.forValue(d, MissingValue.MIS) : MissingValue.MIS;
        }
        return null;
    }


    /** The statically-declared type this value was produced under. */
    public DataValueType type()
    {
        return type;
    }


    /**
     * Whether this value is a genuine missing. ⚠ This is <b>not</b> {@code Vector.isMissing}'s F3
     * notion — an empty string is a present value here (D34 #1); the F3 missing-or-empty fold stays
     * with the consumers that rule it.
     */
    public boolean isMissing()
    {
        return missing != null;
    }


    /**
     * <b>Which</b> missing this value is (D11/D12 — identities are distinct values), or
     * {@code null} for a present value. A <em>carried</em> missing keeps its identity; a
     * <em>computed</em> one is always {@link MissingValue#MIS} (D36 #8).
     */
    public @Nullable MissingValue missing()
    {
        return missing;
    }


    /**
     * The verbatim cell this value was produced from, or {@code null} for a resolved literal /
     * untyped computed value. The typed read path of the comparison primitives (phase 3d): a
     * cell-backed operand is consumed as its {@link IDataValue} — exact number, decoded missing —
     * never through a text round-trip (D84).
     */
    @Nullable
    IDataValue sourceCell()
    {
        return sourceCell;
    }


    /**
     * The typed cell — the legacy {@code Vector.dataValue} channel. Never {@code null}: a
     * non-cell-sourced value derives (and memoises) a {@link DataValues#of} wrapper, exactly as the
     * vectors used to.
     */
    public IDataValue cell()
    {
        if (sourceCell != null)
        {
            return sourceCell;
        }
        IDataValue c = derivedCell;
        if (c == null)
        {
            c = DataValues.of(payload);
            derivedCell = c;
        }
        return c;
    }


    /**
     * The untyped transport channel, reproducing the legacy {@code Vector.resolvedObject} shape:
     * {@code null} for a missing value (consumers that need the identity read {@link #missing()}),
     * the cell's string form for a cell-sourced value, the payload for a literal/computed one.
     *
     * <p>
     * ⚠ Still consumed by the SPI/builtin surface, the collection paths (lists and sets SPEC §1.5
     * types — the typed descriptor model of phase 6b is where they gain carriers) and the temporal
     * comparison families, whose string read is the 3b design (a temporal value's runtime carrier
     * is its ISO-8601 string, D102) rather than residue. The scalar numeric/equality comparisons no
     * longer touch it (phase 3d) — they read {@link #sourceCell()} / {@link #missing()}.
     * </p>
     */
    public @Nullable Object resolved()
    {
        if (missing != null)
        {
            return null;
        }
        if (sourceCell != null)
        {
            return sourceCell.getValueAsString();
        }
        return payload;
    }

}
