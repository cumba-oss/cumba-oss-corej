package net.cumba.corej.core.expr.eval;

/**
 * A rule read a resolved dataset column against its declared type without an explicit conversion —
 * a {@code Char} column where a numeric value is expected (R3/R4: author {@code num(X)}), or a
 * {@code Num} column where a character value is expected (R9: a number's text form is a formatting
 * decision, so no conformance verdict may depend on it; per R12 there is no {@code char()}
 * conversion and such a rule is defective).
 *
 * <p>
 * Thrown <b>per dataset execution, from inside the compiled value plans</b> — never at rule load.
 * The column type is only known once the rule runs against a concrete table
 * ({@code ExprCompiler.nameRefPlan} resolves it from {@code run.ctx()}), and a load-time
 * ({@code Rule.loadError}) wiring would error every dataset of a study for a wildcard rule,
 * including the domains where the sponsor shipped the column correctly
 * (PLAN-column-type-conformance §10 F1).
 * </p>
 *
 * <p>
 * {@code RuleRunner.execute} maps it to {@link net.cumba.corej.core.exec.RuleExecutionStatus#ERROR}
 * with the {@code __error__} sentinel violation — the same channel
 * {@code InvalidJoinedDomainException} uses — so the mismatch surfaces as an actionable rule error
 * instead of a silently wrong verdict.
 * </p>
 *
 * <p>
 * ⚠ The gate deliberately fires <b>only</b> for an operand that is an authored column name resolved
 * in the primary table (a named {@link ColumnVector}) — never for an absent column (EC-38: an
 * absent column is an all-missing column, not a type error), a {@code $}-reference, a dotted /
 * joined name, a {@code value()} cursor read, or any computed operand (§10 F9). The cohort fast
 * path ({@code CohortRunner}) is structurally out of reach: its members' value side is always a
 * dotted foreign reference, which carries no declared type and so never sets an expectation.
 * </p>
 */
public final class ColumnTypeMismatchException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    public ColumnTypeMismatchException(String message)
    {
        super(message);
    }

}
