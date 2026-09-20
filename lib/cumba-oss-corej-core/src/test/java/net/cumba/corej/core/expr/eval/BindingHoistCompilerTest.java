package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.ExpressionResultCache;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Phase 6 of {@code plans/PLAN-typed-expression-engine.md} — the binding-hoist arm of
 * {@code ExprCompiler}'s cache decorators: with a {@code bindingHoist} memo on the context, a pure
 * subtree's result is computed once per (canonical text × row range) and reused by every
 * per-binding context sharing the memo; without one, behaviour is byte-identical to phase 5b.
 */
class BindingHoistCompilerTest
{

    private static IDataTable ex()
    {
        return MockTable.of().name("EX").col("EXDOSE", "1", "0", "2").col("EXTRT", "A", "", "C")
                .build();
    }


    private static EvaluationContext armed(IDataTable table, ExpressionResultCache hoist)
    {
        return EvaluationContext.builder().table(table).bindingHoist(hoist).build();
    }


    private static BitSet eval(Expr e, EvaluationContext ctx)
    {
        return ExprCompiler.compile(e).evaluate(EvalRun.fullRange(ctx));
    }


    @Test
    void armedAndUnarmedEvaluationsAgreeAndTheMemoIsPopulated()
    {
        IDataTable table = ex();
        Expr e = CheckExpressionParser.parse("EXTRT != \"A\" and EXDOSE == \"0\"");
        BitSet unarmed = eval(e, EvaluationContext.builder().table(table).build());
        ExpressionResultCache hoist = new ExpressionResultCache();
        BitSet armedBits = eval(e, armed(table, hoist));
        assertEquals(unarmed, armedBits, "the hoist must never change a verdict");
        // The whole expression is pure, so exactly the maximal (root) subtree is memoised.
        assertEquals(1, hoist.size());
        // A second evaluation against a sibling context sharing the memo reuses the entry.
        BitSet again = eval(e, armed(table, hoist));
        assertEquals(unarmed, again);
        assertEquals(1, hoist.size());
    }


    @Test
    void theMemoIsActuallyReadNotMerelyWritten()
    {
        // Seed the memo under the exact key the decorator derives; the evaluation must return the
        // seeded verdict, proving reads go through the memo rather than re-evaluating.
        IDataTable table = ex();
        Expr e = CheckExpressionParser.parse("EXDOSE == \"0\"");
        ExpressionResultCache hoist = new ExpressionResultCache();
        EvaluationContext ctx = armed(table, hoist);
        BitSet poisoned = new BitSet();
        poisoned.set(2);
        String canon = ExpressionPrinter.print(e);
        hoist.computeIfAbsent(
                DatasetExpressionCache.keyOf(table, canon + "@0:" + table.getRowCount(), null),
                () -> poisoned);
        assertEquals(poisoned, eval(e, ctx));
    }


    @Test
    void theStoredBitSetIsClonedOnRead()
    {
        IDataTable table = ex();
        Expr e = CheckExpressionParser.parse("EXDOSE == \"0\"");
        ExpressionResultCache hoist = new ExpressionResultCache();
        EvaluationContext ctx = armed(table, hoist);
        BitSet first = eval(e, ctx);
        first.flip(0, 3); // an enclosing NOT mutates its child's BitSet in place
        BitSet second = eval(e, ctx);
        assertTrue(second.get(1), "row 1 (EXDOSE == \"0\") must still be set after the flip");
        assertEquals(1, second.cardinality());
    }


    @Test
    void aPureValueOperandInsideAnImpureComparisonIsMemoisedToo()
    {
        // `len(EXTRT) == $n` is impure as a whole ($-ref), so the value twin wraps the pure
        // len(EXTRT) operand — the cachedValue hoist arm.
        IDataTable table = ex();
        Expr e = CheckExpressionParser.parse("len(EXTRT) == $n");
        ExpressionResultCache hoist = new ExpressionResultCache();
        EvaluationContext ctx = EvaluationContext.builder().table(table).variables(Map.of("$n", 1L))
                .bindingHoist(hoist).build();
        BitSet bits = eval(e, ctx);
        assertNotNull(bits);
        assertTrue(hoist.size() >= 1, "the pure value operand must be memoised");
        BitSet again = eval(e, ctx);
        assertEquals(bits, again);
    }


    @Test
    void distinctRangesKeySeparately()
    {
        // The {VAR} loop evaluates broadcast (one synthetic row); the {VAR,ROW} loop evaluates
        // the full range. One execution only ever uses one, but the key must still separate them.
        IDataTable table = ex();
        Expr e = CheckExpressionParser.parse("EXDOSE == \"0\"");
        ExpressionResultCache hoist = new ExpressionResultCache();
        EvaluationContext ctx = armed(table, hoist);
        ExprProgram program = ExprCompiler.compile(e);
        program.evaluate(EvalRun.fullRange(ctx));
        program.evaluate(new EvalRun(ctx, 0, 1));
        assertEquals(2, hoist.size(), "full-range and broadcast runs must not share an entry");
    }

}
