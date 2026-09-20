package net.cumba.corej.core.expr.eval;

import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;

import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.JoinLookup;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.jspecify.annotations.Nullable;

/**
 * The vector of an <b>unqualified foreign reference</b> — a name absent from the primary table but
 * carried by a {@code Match_Datasets} join (B2 of
 * {@code plans/done/PLAN-native-engine-residuals.md}). It exposes the SAME two views the legacy
 * engine has:
 *
 * <ul>
 * <li><b>Scalar view</b> ({@link #value}) — the first non-null value across all
 * {@link JoinLookup#lookupAll} matches, scanning every join: bit-for-bit the legacy VALUE-position
 * contract. Used wherever the vector is consumed as a plain operand.</li>
 * <li><b>Candidates view</b> ({@link #candidates}) — the row's full match list from the single
 * <em>live</em> lookup: per the NAME-position contract the live lookup is the FIRST joined lookup
 * (in registration order) with at least one matched row anywhere in the table — the legacy
 * {@code found} latch; rows then vote with ANY-MATCH over their candidates
 * ({@link Primitives#scan}), an empty list votes once with a missing probe, and {@code null} (no
 * live lookup) casts no vote at all.</li>
 * </ul>
 *
 * <p>
 * An optional per-value {@code transform} supports the pure unary value functions the converter
 * wraps around name operands ({@code len(X) > n} for {@code longer_than}, {@code upper(X)} for the
 * case-insensitive surfaces): candidates are transformed value-by-value, preserving the legacy
 * per-candidate evaluation order (the legacy engine applies the operator's value logic per matched
 * value inside {@code forEachJoinedValue}).
 * </p>
 *
 * <p>
 * Like {@link ComputedVector}, instances are single-evaluation scoped (the live-lookup latch is
 * memoised per instance).
 * </p>
 */
public final class JoinedCandidatesVector implements Vector
{

    private final EvaluationContext ctx;

    private final int rowCount;

    private final String name;

    /** Per-value transform for pure unary wrappers; identity when {@code null}. */
    private final @Nullable UnaryOperator<String> transform;

    private final DataValueType declaredType;

    /** Memoised scalar view (the legacy value-position first-non-null contract). */
    private final ComputedVector scalar;

    /** Live-lookup latch: resolved at most once per instance. */
    private boolean liveResolved;

    private @Nullable JoinLookup live;

    JoinedCandidatesVector(EvaluationContext ctx, int rowCount, String name)
    {
        this(ctx, rowCount, name, null, DataValueType.STRING);
    }


    private JoinedCandidatesVector(EvaluationContext ctx, int rowCount, String name,
            @Nullable UnaryOperator<String> transform, DataValueType declaredType)
    {
        this.ctx = ctx;
        this.rowCount = rowCount;
        this.name = name;
        this.transform = transform;
        this.declaredType = declaredType;
        // Shape 3: with no transform the value position reads typed cells, so the scalar view
        // stops rounding a numeric joined value through getValueAsString(). A transform (upper/len)
        // is text by definition, so that case keeps the untyped producer.
        this.scalar = transform == null
                ? ComputedVector.typed(rowCount, declaredType, this::firstNonNullCell)
                : new ComputedVector(rowCount, declaredType, this::firstNonNull);
    }


    /**
     * A view of this vector with {@code next} applied per value (composing with any existing
     * transform), declared as {@code type}. Used by the compiler to propagate candidates through
     * the pure unary value functions.
     */
    public JoinedCandidatesVector mapped(UnaryOperator<String> next, DataValueType type)
    {
        UnaryOperator<String> prev = transform;
        UnaryOperator<String> composed = prev == null ? next : s -> next.apply(prev.apply(s));
        return new JoinedCandidatesVector(ctx, rowCount, name, composed, type);
    }


    /**
     * The row's joined candidate values (transformed), from the live lookup — or {@code null} when
     * NO lookup matched anywhere (the row casts no vote; legacy returns an empty BitSet). An empty
     * list means the live lookup matched elsewhere but not on this row (the missing-probe case).
     */
    public @Nullable List<String> candidates(int row)
    {
        JoinLookup lookup = liveLookup();
        if (lookup == null)
        {
            return null;
        }
        List<String> raw = lookup.lookupAll(ctx.getTable(), row, name);
        if (transform == null || raw.isEmpty())
        {
            return raw;
        }
        List<String> out = new java.util.ArrayList<>(raw.size());
        for (String v : raw)
        {
            out.add(v == null ? null : transform.apply(v));
        }
        return out;
    }


    /**
     * The legacy {@code forEachJoinedValue} latch: the first joined lookup (registration order)
     * with at least one matched row anywhere in the table; {@code null} when none has.
     */
    private @Nullable JoinLookup liveLookup()
    {
        if (!liveResolved)
        {
            outer: for (Map.Entry<String, JoinLookup> e : ctx.getJoinedDatasets().entrySet())
            {
                JoinLookup lookup = e.getValue();
                for (int r = 0; r < rowCount; r++)
                {
                    if (!lookup.lookupAll(ctx.getTable(), r, name).isEmpty())
                    {
                        live = lookup;
                        break outer;
                    }
                }
            }
            liveResolved = true;
        }
        return live;
    }


    /**
     * The typed sibling of {@link #candidates}, with the same three-way contract: {@code null} when
     * no lookup is live (the row casts no vote), an empty list when the live lookup matched
     * elsewhere but not here (the missing-probe case), else the row's matched cells.
     *
     * <p>
     * Shape 3 of {@code PLAN-joined-column-typing}. ⚠ Used by {@link Primitives#scan}, which builds
     * each candidate's cell for the row test — typing the vector without typing {@code scan} would
     * have moved the lie downstream rather than closing it (review R2-2), because every candidate
     * would still have reached the test as a STRING-typed cell.
     * </p>
     *
     * <p>
     * ⚑ A transform ({@code upper}/{@code len}) is text by definition, so a transformed vector
     * wraps its transformed strings rather than carrying the parent cell through.
     * </p>
     *
     * @param row
     *            the 0-based row index.
     * @return the row's typed candidates, or {@code null} when no lookup is live.
     */
    public @Nullable List<IDataValue> candidateCells(int row)
    {
        JoinLookup lookup = liveLookup();
        if (lookup == null)
        {
            return null;
        }
        if (transform != null)
        {
            List<String> raw = candidates(row);
            if (raw == null)
            {
                return null;
            }
            List<IDataValue> out = new java.util.ArrayList<>(raw.size());
            for (String v : raw)
            {
                out.add(DataValues.of(v));
            }
            return out;
        }
        return lookup.lookupAllValues(ctx.getTable(), row, name);
    }


    /**
     * {@link #firstNonNull} over typed cells — the value-position contract, untransformed.
     *
     * <p>
     * ⚠ <b>The exhausted-scan case is a computed MIS on purpose.</b> The scan spans every join and
     * every candidate, so where no candidate is non-missing there is neither one foreign column to
     * take a declared type from (this vector's {@code declaredType} is {@code STRING} by
     * construction, not the foreign column's own type) nor one cell to take a missing identity
     * from. {@code MissingValue.MIS} is therefore the honest answer (D36 #8) and it is
     * behaviour-identical to the {@code null} this used to return, which
     * {@link TypedValue#typedCell} minted into exactly that. ⚑ What it does NOT express is the
     * absent/unmatched distinction: a row with no matched partner owes the foreign column's
     * type-derived constant (D72/D72a-1), and reaching that from here needs the real foreign type —
     * recorded as a residue rather than guessed at.
     * </p>
     */
    private IDataValue firstNonNullCell(int row)
    {
        for (JoinLookup lookup : ctx.getJoinedDatasets().values())
        {
            for (IDataValue v : lookup.lookupAllValues(ctx.getTable(), row, name))
            {
                if (!v.isMissingOrInvalid())
                {
                    return v;
                }
            }
        }
        return net.cumba.corej.core.exec.ScalarSemantics.computedMissing();
    }


    /** First non-null value across ALL lookups' matches (the value-position contract). */
    private @Nullable Object firstNonNull(int row)
    {
        for (JoinLookup lookup : ctx.getJoinedDatasets().values())
        {
            for (String v : lookup.lookupAll(ctx.getTable(), row, name))
            {
                if (v != null)
                {
                    return transform == null ? v : transform.apply(v);
                }
            }
        }
        return null;
    }


    @Override
    public TypedValue value(int row)
    {
        return scalar.value(row);
    }


    @Override
    public DataValueType declaredType()
    {
        return declaredType;
    }

}
