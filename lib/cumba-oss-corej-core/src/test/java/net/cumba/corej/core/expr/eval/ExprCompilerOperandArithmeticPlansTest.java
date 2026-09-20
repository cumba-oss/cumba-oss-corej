package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.BitSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Verdict pins for {@link ExprCompiler#operandPlan}'s resolution branches and the
 * {@code X != <arithmetic>} surface (first-class since phase 3d, D83): the {@code --}-prefix domain
 * wildcard (resolved from the context at eval time), the divide / subtract / pctchg rule shapes now
 * compiled as ordinary nested arithmetic through {@code ArithmeticSemantics}, and the decline of
 * non-native wildcards. A mutant that re-routes an operand branch ({@code operandPlan}'s kind
 * tests, {@code isDomainPrefixWildcard}, {@code isArith}) makes a correct rule read the wrong
 * column or compute the wrong arithmetic — asserted here as exact row verdicts.
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
    void domainPrefixWildcardIsSpecialisedBeforeEvaluation()
    {
        // D77: the specialisation stage rewrites --SEQ to AESEQ at bind time; the evaluator then
        // reads the concrete column.
        IDataTable ae = MockTable.of().name("AE").col("AESEQ", "1", "2").build();
        EvaluationContext c = EvaluationContext.builder().table(ae).domainName("AE")
                .domainPrefix("AE").variableWildcardPrefix("AE").build();
        java.util.function.Function<String, BitSet> evalSpecialised = src -> NativeExprEvaluator
                .evaluate(net.cumba.corej.core.exec.ExprPrefixResolver
                        .resolve(CheckExpressionParser.parse(src), "AE", "AE"), c);
        assertEquals(bits(0), evalSpecialised.apply("--SEQ == \"1\""),
                "--SEQ specialises to AESEQ under the AE prefix (name position)");
        assertEquals(bits(1), evalSpecialised.apply("--SEQ != \"1\""),
                "the resolved column must carry real values, not a vacuous fold");
    }


    @Test
    void anUnspecialisedDomainPrefixReachingTheEvaluatorIsAnError()
    {
        // D77b: the engine asserts concreteness — silently reading nothing (the old contract) is
        // exactly how the F1 divergence survived.
        IDataTable ae = MockTable.of().name("AE").col("AESEQ", "1", "2").build();
        EvaluationContext c = EvaluationContext.builder().table(ae).build();
        org.junit.jupiter.api.Assertions.assertThrows(
                net.cumba.corej.core.expr.ExpressionException.class,
                () -> eval("--SEQ == \"1\"", c),
                "an unresolved wildcard must never silently read (or miss) AESEQ");
    }

    // ---- X != arithmetic (first-class since phase 3d, D83) --------------------------


    @Test
    void notEqualToDivideVerdict()
    {
        // Owner ruling 2026-09-13: a character CELL never converts -- DataValueString
        // .getValueAsDouble() is a hard NaN -- so a fixture that needs the NUMERIC path must
        // declare a numeric column. Do not put this back to col(...) with digit strings.
        // ⭐ Phase 3d (D85c/D86a + D34 #5-2): the missing-X row FIRES — a missing X never equals
        // the present quotient 2.5. The fused shape's caller used to skip it; the skip existed
        // only to freeze verdicts until this phase (see ArithmeticSemantics).
        IDataTable t = MockTable.of().name("LB").colDouble("X", 2.5, 3.0, null)
                .colDouble("A", 10.0, 10.0, 10.0).colDouble("B", 4.0, 4.0, 4.0).build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertEquals(bits(1, 2), eval("X != A / B", c),
                "X != A/B fires where X differs from 2.5 — including the missing-X row (3d)");
    }


    @Test
    void notEqualToSubtractVerdict()
    {
        IDataTable t = MockTable.of().name("LB").colDouble("X", 6.0, 7.0, null)
                .colDouble("A", 10.0, 10.0, 10.0).colDouble("B", 4.0, 4.0, 4.0).build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertEquals(bits(1, 2), eval("X != A - B", c),
                "X != A-B fires where X differs from 6 — including the missing-X row (3d)");
    }


    @Test
    void notEqualToPercentChangeVerdict()
    {
        IDataTable t = MockTable.of().name("LB").colDouble("X", 150.0, 151.0, null)
                .colDouble("A", 10.0, 10.0, 10.0).colDouble("B", 4.0, 4.0, 4.0).build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertEquals(bits(1, 2), eval("X != ((A - B) / B) * 100", c),
                "the pctchg shape computes ((A-B)/B)*100 = 150 as nested first-class arithmetic");
    }


    @Test
    void missingArithmeticOperandPropagatesAndFires()
    {
        // ⭐ Phase 3d (D85c/D86/D86a): a missing operand propagates into the result — the RHS of
        // row 1 IS the missing value — and a present X never equals a missing result
        // (D34 #5-2), so the row now fires where the fused caller used to skip it.
        IDataTable t = MockTable.of().name("LB").colDouble("X", 9.0, 9.0).colDouble("A", 10.0, null)
                .colDouble("B", 4.0, 4.0).build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertEquals(bits(0, 1), eval("X != A / B", c),
                "row 0: 9 != 2.5 fires; row 1: missing A propagates, 9 != <missing> fires (3d)");
    }


    @Test
    void zeroDivisorIsMissingAndFires()
    {
        // ⭐ D85/D83e: a zero denominator is MissingValue.MIS — a "no result", never NaN — and
        // under D34 #5-2's total comparison a present X never equals MIS, so the row FIRES. The
        // legacy NaN answered "no violation", conflating no-result with do-not-fire (D85b).
        IDataTable t = MockTable.of().name("LB").colDouble("X", 2.5, 2.5).colDouble("A", 10.0, 10.0)
                .colDouble("B", 0.0, 4.0).build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertEquals(bits(0), eval("X != A / B", c),
                "the zero-divisor row fires (MIS != 2.5); the computable row matches and stays");
        IDataTable pct = MockTable.of().name("LB").colDouble("X", 150.0, 150.0)
                .colDouble("A", 10.0, 10.0).colDouble("B", 0.0, 4.0).build();
        assertEquals(bits(0),
                eval("X != ((A - B) / B) * 100", EvaluationContext.builder().table(pct).build()),
                "a zero pctchg baseline is the same no-result verdict; the computable row matches");
        // subtract has no divisor — a zero B is an ordinary value there.
        IDataTable s = MockTable.of().name("LB").colDouble("X", 10.0).colDouble("A", 10.0)
                .colDouble("B", 0.0).build();
        assertEquals(bits(), eval("X != A - B", EvaluationContext.builder().table(s).build()),
                "10 == 10 - 0: a zero subtrahend never takes the no-result branch");
    }


    @Test
    void arithmeticSemanticsRefusesAZeroDenominator()
    {
        // The caller answers MIS before arithmetic runs (D85); reaching divide with a zero
        // denominator is an engine defect and must be loud, never a silent NaN.
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> net.cumba.corej.core.exec.ArithmeticSemantics.divide(10.0, 0.0));
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
