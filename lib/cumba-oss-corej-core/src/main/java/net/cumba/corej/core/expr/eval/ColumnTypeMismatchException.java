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
 * joined name, a {@code value()} cursor read, or any computed operand (§10 F9).
 * </p>
 *
 * <h2>⚑ The cohort fast path, and why this section is now history</h2>
 * <p>
 * This javadoc used to carry four paragraphs about {@code CohortRunner}'s exposure to this
 * exception. The cohort runner is <b>retired</b> ({@code PLAN-retire-cohort-runner.md}), so the
 * mismatch now has exactly one path — {@code RuleRunner.execute}, which maps it to that rule's own
 * {@code RuleExecutionStatus.ERROR} through the {@code __error__} channel. One lesson is worth
 * carrying forward, because it generalises past the class that prompted it:
 * </p>
 * <p>
 * ⛔ <b>A shared catch is only as correct as the invariant it rests on.</b> The retired runner
 * caught {@code InvalidJoinedDomainException} for a whole cohort, which was sound because every
 * member shared one {@code Match_Datasets} list — a grouper invariant. An earlier version of this
 * javadoc proposed adding a parallel cohort-wide {@code catch} for <em>this</em> exception, and
 * that would have been a defect: there was no comparable invariant for a column type, because the
 * MEMBERSHIP {@code CohortKey} was the constant
 * {@code (MEMBERSHIP, [], "is_not_contained_by", false, "")} and carried no column, so every
 * membership-eligible rule on a dataset shared one cohort while each member resolved its own. The
 * catch would have turned one rule's mistyped column into an ERROR for every bystander — a wrong
 * verdict traded for a saved re-run. It was therefore caught per member instead.
 * </p>
 * <p>
 * ⚠ And it was never reachable anyway: measured 2026-09-14, the authored corpus and all generated
 * packages contain <b>zero</b> {@code is_not_contained_by} rules, so no MEMBERSHIP cohort ever
 * existed on the shipped corpus. Re-measured 2026-09-15 from the other end, over a whole study run:
 * 7 419 rule executions produced 7 419 single-member groups, no cohort of two or more ever formed,
 * and the runner's hand-written row predicate was never once reached.
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
