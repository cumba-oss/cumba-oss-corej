package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ast.Expr;
import org.junit.jupiter.api.Test;

/**
 * The compiled-program caches of {@link NativeExprEvaluator}: a program is compiled once per
 * expression, and {@link NativeExprEvaluator#clearCacheForTesting()} empties BOTH caches. The
 * module's test isolation ({@code CompiledProgramCacheIsolationExtension}) depends on that clear;
 * if it stopped clearing, every test would silently share programs again.
 */
class NativeExprEvaluatorCacheTest
{

    @Test
    void aProgramIsCompiledOnceAndTheClearForcesARecompile()
    {
        Expr expr = CheckExpressionParser.parse("not empty(USUBJID)");
        ExprProgram program = NativeExprEvaluator.program(expr);
        BindingProgram binding = NativeExprEvaluator.bindingProgram(expr);
        assertSame(program, NativeExprEvaluator.program(expr), "the check program is cached");
        assertSame(binding, NativeExprEvaluator.bindingProgram(expr), "the binding is cached");

        NativeExprEvaluator.clearCacheForTesting();

        assertNotSame(program, NativeExprEvaluator.program(expr),
                "the clear must drop the check program");
        assertNotSame(binding, NativeExprEvaluator.bindingProgram(expr),
                "the clear must drop the binding program");
    }
}
