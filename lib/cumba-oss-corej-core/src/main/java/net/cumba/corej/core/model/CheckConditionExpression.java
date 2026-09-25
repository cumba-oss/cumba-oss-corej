package net.cumba.corej.core.model;

import net.cumba.corej.core.expr.ast.Expr;

/**
 * An expression Check: an {@code {"expression": …}} leaf kept AS the expression it was written as
 * (phase 7 of {@code PLAN-typed-expression-engine}). Carries the parsed {@link Expr} (for native
 * evaluation via {@code NativeExprEvaluator}) and the original {@code source} text (for
 * serialization).
 *
 * <p>
 * Produced by {@link CheckConditionDeserializer} for <b>every</b> {@code expression:} node and
 * consumed by the native evaluation path; it is the only leaf form of {@link CheckCondition} the
 * engine evaluates. ⚑ History: until phase 7 a v1 lowering pass ({@code ExprLowering}, deleted with
 * the operator-leaf model) stood between parse and evaluation and this record was minted only for
 * the expressions it could not lower; there is no "cannot lower" case any more.
 * </p>
 */
public record CheckConditionExpression(Expr expr, String source) implements CheckCondition
{
}
