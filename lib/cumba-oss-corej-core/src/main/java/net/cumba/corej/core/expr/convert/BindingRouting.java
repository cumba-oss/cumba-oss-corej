package net.cumba.corej.core.expr.convert;

import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.OperationType;
import org.jspecify.annotations.Nullable;

/**
 * The one routing decision of wave 0 ({@code PLAN-binding-expressions}, ruling D-W0-1): which of a
 * rule's {@code Bindings:} entries keeps today's operation path and which compiles like the
 * {@code Check}.
 *
 * <p>
 * ⭐ <b>A single top-level {@link OperationType} call keeps the operation path</b>
 * ({@code Operation} → {@code OperationExecutor}); <b>every other expression is a compiled
 * binding</b>. The operation path is deleted in wave 7 anyway, so moving the 892 existing bindings
 * now would move them twice. ⛔ This class is the <em>only</em> spelling of that predicate: the
 * loader ({@code RulePackageLoader.normalizeOperations}) and the corpus test tree (its
 * operation-round-trip gate and its doc census) all call {@link #isOperationCall}, so the
 * population they judge is exactly the one the engine routed — a copy of the predicate is how the
 * two would drift.
 * </p>
 */
public final class BindingRouting
{

    private BindingRouting()
    {
    }


    /**
     * Whether a parsed binding expression is a single top-level operation call — the shape that
     * keeps the operation path.
     *
     * @param parsed
     *            the parsed binding expression
     * @return {@code true} for a call whose name is an {@link OperationType}
     */
    public static boolean isOperationCall(Expr parsed)
    {
        return parsed instanceof Expr.Call call && OperationType.fromJson(call.name()) != null;
    }


    /**
     * Parses a binding expression, or answers {@code null} when it does not parse. An unparseable
     * binding stays on the operation path, whose normaliser reports it with the established
     * {@code invalid operation expression} load error.
     *
     * @param expression
     *            the authored {@code expression:} text
     * @return the parsed expression, or {@code null}
     */
    public static @Nullable Expr tryParse(@Nullable String expression)
    {
        if (expression == null || expression.isBlank())
        {
            return null;
        }
        try
        {
            return CheckExpressionParser.parse(expression);
        }
        catch (ExpressionException _)
        {
            return null;
        }
    }


    /**
     * {@link #isOperationCall} over authored text: {@code true} for a single top-level operation
     * call <b>and</b> for text that does not parse (which stays on the operation path, see
     * {@link #tryParse}).
     *
     * @param expression
     *            the authored {@code expression:} text
     * @return whether the binding keeps the operation path
     */
    public static boolean routesToOperation(@Nullable String expression)
    {
        Expr parsed = tryParse(expression);
        return parsed == null || isOperationCall(parsed);
    }

}
