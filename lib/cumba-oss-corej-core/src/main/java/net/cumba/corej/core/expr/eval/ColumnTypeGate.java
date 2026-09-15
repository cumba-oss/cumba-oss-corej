package net.cumba.corej.core.expr.eval;

import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * The column-type mismatch gate (PLAN-column-type-conformance Phase 3; rulings R4/R5/R8/R9/R12):
 * raises {@link ColumnTypeMismatchException} when a rule reads a resolved dataset column against
 * its declared type without an explicit conversion. The <b>data's</b> declared type is
 * authoritative (R5) — no CDISC Library lookup is involved.
 *
 * <p>
 * <b>Eligibility (§10 F9):</b> a vector is gated only when it is a <em>named</em>
 * {@link ColumnVector} — an authored column name that resolved in the primary table. Everything
 * else passes untouched: an absent column (folded to an all-missing constant — EC-38), a
 * {@code $}-reference, a dotted / joined name ({@code ComputedVector} /
 * {@code JoinedCandidatesVector}), a literal, a computed value, a {@code num(...)} conversion
 * (which publishes {@link DataValueType#DOUBLE} and thereby <em>satisfies</em> the numeric
 * direction), and the {@code value()} cursor read of a per-variable rule (a {@code ColumnVector}
 * with a {@code null} name — the operand is not an authored column name, and erroring it would make
 * every generic wildcard rule unusable on the other type).
 * </p>
 *
 * <p>
 * <b>Deliberately not gated</b> (recorded so absence is not mistaken for coverage): the
 * {@code str()==str()} type-insensitive surface and the {@code upper}/{@code lowcase}
 * case-insensitive surfaces (explicit string modes); the {@code date}/{@code date_part}/
 * {@code time_part} families (their polymorphic numeric-vs-ISO dispatch is type-directed by
 * design); character-function <em>subjects</em> ({@code len}/{@code prefix}/{@code substring}/…
 * over a Num column — 0 corpus instances measured 2026-09-13, and gating them would need a coercion
 * semantics with no demand); and dynamic membership sets ({@code $}-lists, accessors, grouped
 * results — their member types are not statically known).
 * </p>
 */
public final class ColumnTypeGate
{

    /** The two authored kinds the gate compares. */
    enum Kind
    {
        NUMERIC, CHARACTER
    }

    private ColumnTypeGate()
    {
    }


    /**
     * The gate-relevant kind of a declared column type: {@code STRING} → CHARACTER, {@code LONG}/
     * {@code DOUBLE} → NUMERIC, anything else (BOOLEAN, MISSING, dates) → {@code null} (not gated).
     */
    static @Nullable Kind kindOf(DataValueType t)
    {
        if (t == DataValueType.STRING)
        {
            return Kind.CHARACTER;
        }
        if (t == DataValueType.LONG || t == DataValueType.DOUBLE)
        {
            return Kind.NUMERIC;
        }
        return null;
    }


    /** The gated column behind {@code v}: a named {@link ColumnVector}, else {@code null}. */
    private static @Nullable ColumnVector gatedColumn(@Nullable Vector v)
    {
        return v instanceof ColumnVector cv && cv.name() != null ? cv : null;
    }


    private static String describe(ColumnVector cv)
    {
        return cv.name() + " is declared "
                + (kindOf(cv.declaredType()) == Kind.CHARACTER ? "Char" : "Num") + " ("
                + cv.declaredType() + ")";
    }


    /**
     * Numeric-expected position (an order comparison operand, a numeric function argument, a
     * numeric-literal membership probe, an arithmetic operand): a resolved {@code Char} column
     * errors — the author must write {@code num(X)} (R3).
     */
    public static void requireNumericRead(@Nullable Vector v, String context)
    {
        ColumnVector cv = gatedColumn(v);
        if (cv != null && kindOf(cv.declaredType()) == Kind.CHARACTER)
        {
            throw new ColumnTypeMismatchException("column-type mismatch: " + describe(cv) + " but "
                    + context + " expects a numeric value — author num(" + cv.name()
                    + ") if the rule means a numeric read of its content");
        }
    }


    /**
     * Character-expected position (a regex subject, a string-literal membership probe): a resolved
     * {@code Num} column errors — a number's text form is formatting-dependent (R9), and per R12
     * there is no {@code char()} conversion, so the rule is defective as authored.
     */
    public static void requireCharacterRead(@Nullable Vector v, String context)
    {
        ColumnVector cv = gatedColumn(v);
        if (cv != null && kindOf(cv.declaredType()) == Kind.NUMERIC)
        {
            throw new ColumnTypeMismatchException(
                    "column-type mismatch: " + describe(cv) + " but " + context
                            + " expects a character value — a numeric column's text form depends on"
                            + " formatting, so this rule cannot be evaluated as authored");
        }
    }


    /**
     * Plain (non-case-, non-type-insensitive) {@code ==}/{@code !=}: the two operands' kinds must
     * agree wherever both are known. A side's kind is its resolved column's declared type when the
     * side is a named {@link ColumnVector}; otherwise the statically-known kind the compiler passed
     * ({@code NUMERIC} for a numeric literal or a {@code num(...)} conversion, {@code CHARACTER}
     * for a string literal, {@code null} for everything else — a {@code $}-ref, a computed value,
     * an absent-column fold never set an expectation).
     */
    public static void requireAgreedEquality(@Nullable Vector lv, @Nullable Vector rv,
            @Nullable Kind lStatic, @Nullable Kind rStatic)
    {
        ColumnVector lc = gatedColumn(lv);
        ColumnVector rc = gatedColumn(rv);
        Kind lk = lc != null ? kindOf(lc.declaredType()) : lStatic;
        Kind rk = rc != null ? kindOf(rc.declaredType()) : rStatic;
        if (lk == null || rk == null || lk == rk || (lc == null && rc == null))
        {
            return;
        }
        StringBuilder sb = new StringBuilder("column-type mismatch: ");
        sb.append(lc != null ? describe(lc)
                : "the left operand is " + (lk == Kind.NUMERIC ? "numeric" : "character"));
        sb.append(" and ");
        sb.append(rc != null ? describe(rc)
                : "the right operand is " + (rk == Kind.NUMERIC ? "numeric" : "character"));
        ColumnVector charSide = lk == Kind.CHARACTER ? lc : rc;
        if (charSide != null)
        {
            sb.append(" — author num(").append(charSide.name())
                    .append(") if the rule means a numeric comparison of its content");
        }
        else
        {
            sb.append(" — a numeric column's text form depends on formatting, so this rule cannot"
                    + " be evaluated as authored");
        }
        throw new ColumnTypeMismatchException(sb.toString());
    }

}
