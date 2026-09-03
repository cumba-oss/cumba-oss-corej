package net.cumba.corej.core.model;

import net.cumba.corej.core.expr.ast.Expr;

/**
 * A <strong>native-only</strong> expression Check: an {@code {"expression": …}} leaf whose parsed
 * {@link Expr} has no legacy lowering (it uses native-only constructs such as the {@code var_*} /
 * {@code ds_*} metadata accessors with an arbitrary-literal name). Carries the parsed {@code expr}
 * (for native evaluation via {@code NativeExprEvaluator}) and the original {@code source} text (for
 * serialization). It is never lowered to operator-leaf form.
 *
 * <p>
 * Produced by {@link CheckConditionDeserializer} when {@code ExprLowering} cannot lower an
 * expression-form Check; consumed by the native evaluation path. The legacy engine has no operator
 * surface for it, so it is evaluated through the native backend wherever it is reached.
 * </p>
 */
public record CheckConditionExpression(Expr expr, String source) implements CheckCondition
{
}
