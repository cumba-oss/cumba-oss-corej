package net.cumba.corej.core.expr.eval;

import java.io.Serial;

/**
 * The typed <b>"the provider answered, but with nothing usable"</b> signal of the provider
 * capability ({@link ProviderNeed}; {@code PLAN-binding-expressions} §5.2 (c)).
 *
 * <p>
 * On the operation surface the same situation returned a {@code LIBRARY_NOT_AVAILABLE} sentinel,
 * which {@code RuleRunner}'s eager arm turned into {@code SKIPPED} (both went with the last
 * library-dependent operation in wave 4b). A function returns a {@link Vector}, and a sentinel
 * inside a vector would leak into every consumer (a membership set holding the sentinel, a
 * {@code not in} firing on every row). So a capability-carrying function that has nothing usable
 * <b>throws</b> this instead of answering its empty result — which would otherwise let
 * {@code not empty($x)} read {@code false} and the rule silently PASS.
 * </p>
 *
 * <p>
 * Exactly two places catch it:
 * </p>
 * <ul>
 * <li>{@code RuleRunner}'s eager compiled-binding arm, before the Check runs — the rule reports
 * {@code SKIPPED} with the operation arm's message, naming the function;</li>
 * <li>the {@code available(<call>)} availability gate ({@code ExprCompiler}'s argument arm for
 * {@code available}), which reads it as "not available" — the inline-in-Check route, where the
 * loader-injected {@code library_available() and available(<call>)} Precondition then SKIPs.</li>
 * </ul>
 * <p>
 * ⛔ Anywhere else it propagates, and the rule reports {@code ERROR}: loud, never a silent PASS.
 * {@code ExprCompiler}'s inline-operation plan raises it too when a provider-dependent inline
 * operation answers the sentinel, so a provider-dependent call nested inside a compiled binding is
 * gated the same way.
 * </p>
 */
public final class UnusableProviderAnswerException extends RuntimeException
{

    @Serial
    private static final long serialVersionUID = 1L;

    /** The function (or inline operation) whose answer was unusable. */
    private final String function;

    /** The provider it depends on. */
    private final ProviderNeed.Kind kind;

    /**
     * A signal naming the function and its provider.
     *
     * @param aFunction
     *            the function (or inline operation) name
     * @param aKind
     *            the provider it depends on
     * @param aDetail
     *            what was unusable, for the log and the error message
     */
    public UnusableProviderAnswerException(String aFunction, ProviderNeed.Kind aKind,
            String aDetail)
    {
        super(aFunction + ": " + aDetail);
        function = aFunction;
        kind = aKind;
    }


    /**
     * The function (or inline operation) whose answer was unusable.
     *
     * @return the function name
     */
    public String function()
    {
        return function;
    }


    /**
     * The provider the function depends on.
     *
     * @return the provider kind
     */
    public ProviderNeed.Kind kind()
    {
        return kind;
    }

}
