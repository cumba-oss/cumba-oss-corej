package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.BitSet;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ExpressionException;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Verdict pins for {@link ExprCompiler#operandPlan}'s resolution branches and the
 * {@code X != <arithmetic>} native surface: the {@code --}-prefix domain wildcard (resolved from
 * the context at eval time), the divide / subtract / pctchg shapes routed through the shared
 * {@code ArithmeticSemantics}, and the decline of non-native wildcards. A mutant that re-routes an
 * operand branch ({@code operandPlan}'s kind tests, {@code isDomainPrefixWildcard},
 * {@code isArith}, {@code isHundred}, the pctchg structural test) makes a correct rule read the
 * wrong column or compute the wrong arithmetic — asserted here as exact row verdicts.
 */
class ExprCompilerOperandArithmeticPlansTest
{

    private static BitSet eval(String expr, EvaluationContext ctx)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }


    private static BitSet bits(int... set)
    {
        BitSet b = new BitSet();
        for (int i : set)
        {
            b.set(i);
        }
        return b;
    }

    // ---- --prefix domain wildcard ------------------------------------------------


    @Test
    void domainPrefixWildcardResolvesAgainstTheContextPrefix()
    {
        IDataTable ae = MockTable.of().name("AE").col("AESEQ", "1", "2").build();
        EvaluationContext c = EvaluationContext.builder().table(ae).domainName("AE")
                .domainPrefix("AE").variableWildcardPrefix("AE").build();
        assertEquals(bits(0), eval("--SEQ == \"1\"", c),
                "--SEQ must resolve to AESEQ under the AE prefix (name position)");
        assertEquals(bits(1), eval("--SEQ != \"1\"", c),
                "the resolved column must carry real values, not a vacuous fold");
    }


    @Test
    void unresolvedDomainPrefixLeavesTheNameUnresolvable()
    {
        // No prefix in the context: --SEQ stays "--SEQ", which is not a column of the table, so
        // the name-position fold makes the predicate compute over all-missing (== "" fires).
        IDataTable ae = MockTable.of().name("AE").col("AESEQ", "1", "2").build();
        EvaluationContext c = EvaluationContext.builder().table(ae).build();
        assertEquals(bits(), eval("--SEQ == \"1\"", c),
                "without a prefix the wildcard must NOT silently read AESEQ");
    }

    // ---- X != arithmetic ------------------------------------------------------------


    @Test
    void notEqualToDivideVerdict()
    {
        IDataTable t = MockTable.of().name("LB").col("X", "2.5", "3", "")
                .colDouble("A", 10.0, 10.0, 10.0).colDouble("B", 4.0, 4.0, 4.0).build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertEquals(bits(1), eval("X != A / B", c),
                "X != A/B fires exactly where X differs from 2.5; missing X never fires");
    }


    @Test
    void notEqualToSubtractVerdict()
    {
        IDataTable t = MockTable.of().name("LB").col("X", "6", "7", "")
                .colDouble("A", 10.0, 10.0, 10.0).colDouble("B", 4.0, 4.0, 4.0).build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertEquals(bits(1), eval("X != A - B", c),
                "X != A-B fires exactly where X differs from 6; missing X never fires");
    }


    @Test
    void notEqualToPercentChangeVerdict()
    {
        IDataTable t = MockTable.of().name("LB").col("X", "150", "151", "")
                .colDouble("A", 10.0, 10.0, 10.0).colDouble("B", 4.0, 4.0, 4.0).build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertEquals(bits(1), eval("X != ((A - B) / B) * 100", c),
                "the pctchg shape must compute ((A-B)/B)*100 = 150, not a plain product");
    }


    @Test
    void missingArithmeticOperandNeverFires()
    {
        IDataTable t = MockTable.of().name("LB").col("X", "9", "9").col("A", "10", "")
                .col("B", "4", "4").build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertEquals(bits(0), eval("X != A / B", c),
                "a missing arithmetic operand makes no decision — only the complete row fires");
    }

    // ---- non-native operands decline loudly ---------------------------------------------


    @Test
    void listLiteralIsNotAScalarOperand()
    {
        IDataTable t = MockTable.of().name("AE").col("X", "1").build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertThrows(ExpressionException.class, () -> eval("X == [\"A\", \"B\"]", c),
                "a list literal in scalar position must decline, never coerce");
    }

}
