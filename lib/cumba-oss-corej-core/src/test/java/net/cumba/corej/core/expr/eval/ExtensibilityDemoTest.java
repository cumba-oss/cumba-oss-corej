package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.BitSet;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.ExprLowering;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.ast.Expr.BinOp;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Phase 6 — the extensibility demonstration. A construct with <b>no operator-leaf equivalent</b> is
 * added end-to-end and evaluated by the native backend, touching only a {@link FunctionProvider}
 * (or the existing parser/value-function machinery) and <b>never</b> {@code OperatorRegistry} or
 * {@code CheckEvaluator}. The same construct is (correctly) rejected by {@link ExprLowering} with a
 * "needs the native evaluator" error — demonstrating the strangler boundary moving outward.
 */
@ExtendWith(MockitoExtension.class)
class ExtensibilityDemoTest
{

    private static Expr ref(String n)
    {
        return new Expr.Ref(n, OperandKind.COLUMN);
    }

    /**
     * A demonstration provider contributing {@code is_palindrome(x)} — a boolean predicate with no
     * legacy operator. In production a provider like this would be discovered via the project SPI
     * ({@code GenericServiceFactory}); here it is registered programmatically (the SPI's other
     * supported path) so the demo function never ships in the real registry.
     */
    static final class DemoFunctions implements FunctionProvider
    {

        @Override
        public Collection<FunctionDescriptor> functions()
        {
            EvalFunction palindrome = (run, args) -> Primitives.stringPredicate(args.get(0),
                    run.rowCount(), DemoFunctions::isPalindrome);
            return List.of(
                    new FunctionDescriptor("is_palindrome", 1, FunctionKind.BOOLEAN, palindrome));
        }


        static boolean isPalindrome(String s)
        {
            return s.contentEquals(new StringBuilder(s).reverse());
        }
    }

    @Test
    void newFunctionViaProviderEvaluatesNativelyButDoesNotLower()
    {
        for (FunctionDescriptor d : new DemoFunctions().functions())
        {
            FunctionRegistry.register(d);
        }
        try
        {
            IDataTable t = MockTable.of().col("X", "ABA", "ABC", "").build();
            EvaluationContext ctx = EvaluationContext.builder().table(t).build();
            Expr call = new Expr.Call("is_palindrome", List.of(ref("X")), Map.of());

            // Native evaluates the brand-new function with zero engine changes.
            BitSet expected = new BitSet();
            expected.set(0);
            assertEquals(expected, NativeExprEvaluator.evaluate(call, ctx));

            // The v1 lowering has no operator for it — it stays a native-only construct.
            assertThrows(ExpressionException.class, () -> ExprLowering.toCheckCondition(call));
        }
        finally
        {
            FunctionRegistry.unregister("is_palindrome", 1);
        }
    }


    @Test
    void oneSidedLowerIsNativeOnly()
    {
        // lower(X) == "abc" applies the transform to a single operand — the symmetric
        // equal_to_case_insensitive operator cannot express it, so v1 lowering rejects it; the
        // native backend evaluates it directly.
        IDataTable t = MockTable.of().col("X", "ABC", "abc", "XYZ").build();
        EvaluationContext ctx = EvaluationContext.builder().table(t).build();
        Expr e = new Expr.Binary(BinOp.EQ, new Expr.Call("lower", List.of(ref("X")), Map.of()),
                new Expr.Lit(Expr.LitKind.STRING, "abc"));

        BitSet expected = new BitSet();
        expected.set(0);
        expected.set(1);
        assertEquals(expected, NativeExprEvaluator.evaluate(e, ctx));
        assertThrows(ExpressionException.class, () -> ExprLowering.toCheckCondition(e));
    }

}
