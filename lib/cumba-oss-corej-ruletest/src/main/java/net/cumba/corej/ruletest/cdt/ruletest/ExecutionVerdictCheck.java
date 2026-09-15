package net.cumba.corej.ruletest.cdt.ruletest;

import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario.Verdict;
import org.jspecify.annotations.Nullable;

/**
 * Judges a scenario's {@code #test expect=} verdict against the <b>execution state</b> a rule
 * actually reached — did it run, did it deliberately not run, or did it fail — before any question
 * of violations is asked.
 *
 * <p>
 * Main-scope and assertion-free on purpose, exactly like {@link ViolationLocationCheck}: it returns
 * a {@link Result} and the caller (a test) decides. That is what makes the four verdicts testable
 * without an engine run — a {@link Outcome} is plain data.
 * </p>
 *
 * <h2>Why execution state is asserted at all</h2>
 * <p>
 * A rule that never ran reports zero violations, and so does one that ran and found nothing; a rule
 * that <em>errored</em> reports zero real violations too. Without a positive verdict for each of
 * those states they all have to borrow {@code expect=noViolation}, which then passes whether or not
 * the scenario exercises anything — the fixture-rot {@link Verdict#SKIPPED} was introduced to stop,
 * and which {@link Verdict#EXECUTION_ERROR} closes for the failure case (ruling R7).
 * </p>
 *
 * <h2>⛔ {@code executionError} is not {@code severity=ERROR}</h2>
 * <p>
 * {@code severity=ERROR} and {@code #runLevel ERROR} name a check <em>severity level</em> — the
 * level a violation fires at. This verdict says the rule could not run at all. The token is the
 * longer {@code executionError} precisely so the two cannot be confused at a glance; see
 * {@link Verdict#EXECUTION_ERROR}.
 * </p>
 */
public final class ExecutionVerdictCheck
{

    private ExecutionVerdictCheck()
    {
    }

    /** Outcome of a verdict check: {@code pass=true} with empty detail, or a failure message. */
    public record Result(boolean pass, String detail)
    {
    }


    /**
     * The execution state of one rule against one scenario, aggregated across wildcard expansions.
     *
     * @param executed
     *            at least one (expansion of the) rule ran — neither SKIPPED nor ERROR.
     * @param errored
     *            at least one (expansion of the) rule failed. Deliberately <b>not</b> folded into
     *            {@code executed}: {@code expect=skipped} asserts a <em>deliberate</em>
     *            non-execution, so a rule broken into unconditional ERROR must fail such a scenario
     *            rather than satisfy it.
     * @param violated
     *            the run produced at least one violation. ⚠ An errored execution carries an
     *            {@code __error__} sentinel violation, so this is {@code true} for a failed rule as
     *            well — which is exactly why {@code errored} is judged first and {@code
     *            violated} is never read on its own.
     * @param violationCount
     *            the true violation count, for the failure message.
     * @param statusMessage
     *            the engine's own words for why the rule did not run
     *            ({@code RuleExecutionResult.getStatusMessage()}), or {@code null}.
     */
    public record Outcome(boolean executed, boolean errored, boolean violated, long violationCount,
            @Nullable String statusMessage)
    {
    }

    /** Reads the execution state of a single (non-wildcard) execution off the engine's result. */
    public static Outcome outcomeOf(RuleExecutionResult aResult)
    {
        boolean ran = aResult.getStatus() == RuleExecutionStatus.EXECUTED;
        return new Outcome(ran, aResult.isError(), aResult.hasViolations(),
                aResult.getViolationCount(), aResult.getStatusMessage());
    }


    /**
     * Judges {@code aScenario.getExpect()} against {@code aOutcome}.
     *
     * <p>
     * Violations are only weighed once the execution state agrees with the declared verdict, so a
     * failure message always names the <em>first</em> thing that is wrong rather than a downstream
     * symptom.
     * </p>
     *
     * @return a passing result, or a failing one whose detail names the engine's own reason.
     */
    public static Result verify(RuleTestScenario aScenario, Outcome aOutcome)
    {
        String id = aScenario.getCoreId();
        Verdict expect = aScenario.getExpect();
        return switch (expect)
        {
        case VIOLATION, NO_VIOLATION ->
        {
            Result state = requireRan(id, expect, aOutcome);
            if (!state.pass())
            {
                yield state;
            }
            if (expect == Verdict.VIOLATION)
            {
                yield aOutcome.violated() ? pass()
                        : fail(id + " expected a violation but got none");
            }
            yield aOutcome.violated() ? fail(id + " expected no violation but got "
                    + aOutcome.violationCount() + " violation(s)") : pass();
        }
        case SKIPPED ->
        {
            if (aOutcome.errored())
            {
                yield fail(id + " declares expect=skipped but the rule ERRORED rather than being"
                        + " skipped: " + reason(aOutcome)
                        + " — a broken rule must not satisfy a skip contract; declare"
                        + " 'expect=executionError' if the failure IS the contract under test.");
            }
            if (aOutcome.executed())
            {
                yield fail(id + " declares expect=skipped but the rule executed"
                        + (aOutcome.violated()
                                ? " and produced " + aOutcome.violationCount() + " violation(s)"
                                : " (and produced no violation)")
                        + " — either the scenario now exercises the rule (change the expect) or"
                        + " the gate it was testing no longer fires");
            }
            yield pass();
        }
        case EXECUTION_ERROR ->
        {
            if (!aOutcome.errored())
            {
                yield fail(id + " declares expect=executionError but the rule did not error: "
                        + (aOutcome.executed()
                                ? "it executed normally"
                                        + (aOutcome.violated()
                                                ? " and produced " + aOutcome.violationCount()
                                                        + " violation(s)"
                                                : " and produced no violation")
                                : "it never ran at all (" + reason(aOutcome)
                                        + ") — that is 'expect=skipped', not an execution error")
                        + " — the failure this scenario was written to pin no longer happens,"
                        + " or the fixture no longer reaches it.");
            }
            String want = aScenario.getExpectErrorMessage();
            if (want != null)
            {
                String got = aOutcome.statusMessage();
                if (got == null || !got.contains(want))
                {
                    yield fail(id + " errored as declared, but #expect-execution-error \"" + want
                            + "\" does not occur in the engine's message: "
                            + (got == null ? "<none>" : "\"" + got + "\"")
                            + " — the rule is failing for a different reason than the scenario"
                            + " pins.");
                }
            }
            yield pass();
        }
        };
    }


    /**
     * The shared guard behind {@code violation} / {@code noViolation}: both assert the rule
     * <b>ran</b>.
     *
     * <p>
     * ⚠ An ERROR is reported separately from a skip, and named with the engine's own error text.
     * Both states report zero real violations, so without this the scenario passes or fails for a
     * reason that has nothing to do with what it asserts — and in the ERROR case the old advice
     * ("declare expect=skipped") pointed at a verdict that would then fail too.
     * </p>
     */
    private static Result requireRan(String aId, Verdict aExpect, Outcome aOutcome)
    {
        if (aOutcome.errored())
        {
            return fail(aId + " declares expect=" + aExpect.token() + " but the rule ERRORED: "
                    + reason(aOutcome)
                    + " — an errored rule reports no usable verdict. Fix the rule or the fixture,"
                    + " or declare 'expect=executionError' if the failure IS the contract under"
                    + " test.");
        }
        if (!aOutcome.executed())
        {
            return fail(aId + " declares expect=" + aExpect.token()
                    + " but the rule never executed: " + reason(aOutcome)
                    + " — the scenario is not exercising the rule. Give the fixture what the rule"
                    + " needs, or declare 'expect=skipped' if not executing IS the contract under"
                    + " test.");
        }
        return pass();
    }


    private static String reason(Outcome aOutcome)
    {
        String msg = aOutcome.statusMessage();
        return msg == null || msg.isBlank() ? "<the engine gave no reason>" : msg;
    }


    private static Result pass()
    {
        return new Result(true, "");
    }


    private static Result fail(String aDetail)
    {
        return new Result(false, aDetail);
    }
}
