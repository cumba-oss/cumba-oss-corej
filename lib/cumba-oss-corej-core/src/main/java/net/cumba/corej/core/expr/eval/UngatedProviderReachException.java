package net.cumba.corej.core.expr.eval;

import java.util.Objects;

/**
 * ⛔ A provider-backed registry function was <b>reached</b> although its provider gate should have
 * SKIPPED the rule before any row was read — no provider on the run, or the declared type not
 * loaded ({@code PLAN-function-surface-wave1} D-W1-3 (vii)). The function never answers its empty
 * default instead (for a {@code PREDICATE} that is {@code false}, which the corpus's
 * {@code $x == false} Checks would turn into a finding on every row: a silent false FAIL); it
 * throws this, and {@code RuleRunner} reports the rule as ERROR with the message. It is a tripwire
 * for a gate hole, never a SKIP path: the gates decide from the function's {@link ProviderNeed
 * provider capability} before evaluation, so a rule that reaches this arm has bypassed them — which
 * is exactly what the wave's S3a sabotage proves the pins can see.
 */
public final class UngatedProviderReachException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    private final String function;

    /**
     * @param aFunction
     *            the function that was reached
     * @param aDetail
     *            what was missing: no provider, or the type and its state
     */
    public UngatedProviderReachException(String aFunction, String aDetail)
    {
        super(aFunction + " reached " + aDetail
                + " — the provider gate should have skipped this rule before any row was read");
        this.function = Objects.requireNonNull(aFunction);
    }


    /**
     * The function that was reached.
     *
     * @return the function name as authored
     */
    public String function()
    {
        return function;
    }

}
