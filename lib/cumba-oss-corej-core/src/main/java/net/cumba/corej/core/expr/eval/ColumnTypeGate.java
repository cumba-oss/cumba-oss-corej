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
 * ⭐ <b>Since phase 4 of {@code PLAN-typed-expression-engine.md} this gate IS stage B's armed
 * column-type check (D15)</b> — the typed engine absorbed it rather than growing a second gate: a
 * raise here, per (rule, dataset) through {@code RuleRunner}'s single catch site (D74a), stays the
 * one erroring path, while {@code expr.typed.StageBChecker} adds the per-binding <em>reporting</em>
 * the throw-on-first-mismatch shape cannot (D41) and reuses this gate's expectation notions for the
 * absent-column default (D76/D76a) — the one case this gate deliberately skips.
 * </p>
 *
 * <p>
 * <b>Eligibility (§10 F9, widened by J7 of {@code PLAN-joined-column-typing}):</b> a vector is
 * gated when it reports a {@link Vector#gatedName()} — an authored column name that resolved to a
 * real, typed column. ⭐ That now includes a <b>dotted joined reference</b> ({@code DM.AGE}), which
 * was previously invisible to the gate because it is a {@link ComputedVector}: its declared type
 * said {@code Char} whatever the foreign column really was, so the gate reasoned about a type the
 * data did not have. ⚠ A {@code ${…}}-substituted operand stays OUT: it resolves its column name
 * per row, so no single declared type is correct. Everything else passes untouched: an absent
 * column (folded to an all-missing constant — EC-38), a {@code $}-reference, a literal, a computed
 * value, a {@code num(...)} conversion (which publishes {@link DataValueType#DOUBLE} and thereby
 * <em>satisfies</em> the numeric direction), and the {@code value()} cursor read of a per-variable
 * rule (a {@code ColumnVector} with a {@code null} name — the operand is not an authored column
 * name, and erroring it would make every generic wildcard rule unusable on the other type).
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

    /**
     * The two authored kinds the gate compares. Public since phase 4 of
     * {@code PLAN-typed-expression-engine.md}: this gate <b>is</b> stage B's armed column-type
     * check (D15 — the typed engine absorbs it rather than growing a second gate), and the stage-B
     * checker ({@code expr.typed.StageBChecker}) classifies through the gate's own kind vocabulary
     * so the two layers cannot hold different ideas of Char/Num.
     */
    public enum Kind
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
    public static @Nullable Kind kindOf(DataValueType t)
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


    /**
     * The gated operand behind {@code v}: any vector that names an authored column and carries a
     * real declared type, else {@code null}.
     *
     * <p>
     * <b>J7</b> ({@code PLAN-joined-column-typing}): this used to require a named
     * {@link ColumnVector}, which left every <em>joined</em> reference un-gated however wrong its
     * type was — a dotted {@code DM.AGE} is a {@link ComputedVector}. Eligibility is now "an
     * authored column name that resolved to a real, typed column", expressed through
     * {@link Vector#gatedName()}, so a vector opts in by naming itself rather than by its class.
     * </p>
     */
    private static @Nullable Vector gatedColumn(@Nullable Vector v)
    {
        return v != null && v.gatedName() != null ? v : null;
    }


    private static String describe(Vector cv)
    {
        return cv.gatedName() + " is declared "
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
        Vector cv = gatedColumn(v);
        if (cv != null && kindOf(cv.declaredType()) == Kind.CHARACTER)
        {
            throw new ColumnTypeMismatchException("column-type mismatch: " + describe(cv) + " but "
                    + context + " expects a numeric value — author num(" + cv.gatedName()
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
        Vector cv = gatedColumn(v);
        if (cv != null && kindOf(cv.declaredType()) == Kind.NUMERIC)
        {
            throw new ColumnTypeMismatchException(
                    "column-type mismatch: " + describe(cv) + " but " + context
                            + " expects a character value — a numeric column's text form depends on"
                            + " formatting, so this rule cannot be evaluated as authored");
        }
    }


    /**
     * ⚠ <b>D55, OBSERVE-ONLY — deliberately not armed in phase 3b</b> (the E2 escalation of
     * {@code FINDINGS-review0-date-equality.md}, measured 2026-09-16): a {@code date()} /
     * {@code time()} conversion over a resolved <b>numeric</b> column is the {@code date(NUM)}
     * shape D55 makes a bind error — but the ruled rewrite ({@code date_from_sas_days} /
     * {@code date_from_sas_datetime}) exists <b>nowhere</b> yet (not in the engine, not in the
     * corpus), the four shape-4 ADaM rules' phase-3c rewrite will put {@code date()} over
     * {@code *DT}/{@code *DTM} columns that are <b>numeric in real ADaM</b> while all eight of
     * their {@code .cdt} fixtures declare {@code type=Char} (no numeric acceptance coverage), and
     * every one of the corpus' 119 {@code date(…)} sites today wraps an SDTM/SEND {@code *DTC} /
     * {@code *TPT} text column. Arming now would set a trap 3c walks into with every gate green.
     * The observation logs at DEBUG and notifies the measurement observer, and is pinned by a test
     * so it cannot erode silently.
     */
    public static void observeIsoConversionRead(@Nullable Vector v, String context)
    {
        Vector cv = gatedColumn(v);
        if (cv != null && kindOf(cv.declaredType()) == Kind.NUMERIC)
        {
            String message = "column-type mismatch (D55, observe-only): " + describe(cv) + " but "
                    + context + " reads an ISO-8601 text value — date_from_sas_days(...) / "
                    + "date_from_sas_datetime(...) is the ruled rewrite once those functions "
                    + "exist";
            LOGGER.log(System.Logger.Level.DEBUG, message);
            java.util.function.Consumer<String> observer = ISO_OBSERVER.get();
            if (observer != null)
            {
                observer.accept(message);
            }
        }
    }

    private static final System.Logger LOGGER = System.getLogger(ColumnTypeGate.class.getName());

    /** Measurement hook for {@link #observeIsoConversionRead}; test / measurement use only. */
    private static final java.util.concurrent.atomic.AtomicReference<java.util.function.@Nullable Consumer<String>> ISO_OBSERVER = new java.util.concurrent.atomic.AtomicReference<>();

    /** Sets (or clears) the D55 observation hook. Test / measurement use only. */
    public static void setIsoConversionObserver(
            java.util.function.@Nullable Consumer<String> observer)
    {
        ISO_OBSERVER.set(observer);
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
        Vector lc = gatedColumn(lv);
        Vector rc = gatedColumn(rv);
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
        Vector charSide = lk == Kind.CHARACTER ? lc : rc;
        if (charSide != null)
        {
            sb.append(" — author num(").append(charSide.gatedName())
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
