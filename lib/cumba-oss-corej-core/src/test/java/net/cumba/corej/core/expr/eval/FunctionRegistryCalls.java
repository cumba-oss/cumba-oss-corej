package net.cumba.corej.core.expr.eval;

import net.cumba.corej.core.expr.ExpressionException;

/**
 * Test shorthand for {@link FunctionRegistry}: resolves a registered function's implementation by
 * name, throwing {@link ExpressionException} when none is registered — what
 * {@code FunctionRegistry.resolve} did until {@code PLAN-retire-dead-multi-match-lookup} U3 (B10)
 * retired it: the compiler resolves through {@link FunctionRegistry#descriptor} /
 * {@code descriptorAccepting}, so the throwing lookup had test callers only. ⛔ A pure forwarder;
 * see {@code net.cumba.corej.core.exec.RuleRunnerCalls}.
 */
public final class FunctionRegistryCalls
{

    private FunctionRegistryCalls()
    {
    }


    /**
     * The implementation registered under {@code name}.
     *
     * @throws ExpressionException
     *             if no function is registered for the name
     */
    public static EvalFunction resolve(String name)
    {
        FunctionDescriptor descriptor = FunctionRegistry.descriptor(name);
        if (descriptor == null || descriptor.fn() == null)
        {
            throw new ExpressionException("No native function '" + name + "'");
        }
        return descriptor.fn();
    }
}
