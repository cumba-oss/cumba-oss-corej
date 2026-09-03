package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Pins the special comparison forms {@code ExprCompiler.compileComparison} routes before its
 * generic tail: the {@code str(...)} type-insensitive pair, the three boolean-operand shapes, and
 * the {@code NEQ}-with-affix case.
 *
 * <p>
 * Twelve mutants survived in that method, and one shape — {@code true == booleanCall()}, the
 * literal on the LEFT — was not merely unasserted but <b>NO_COVERAGE</b>: no test in the module
 * ever wrote a comparison that way round. Its mirror {@code booleanCall() == true} was covered, so
 * the asymmetry was invisible.
 * </p>
 */
class ExprCompilerComparisonFormsTest
{

    private static EvaluationContext ctx()
    {
        IDataTable t = MockTable.of().name("AE").col("AEDECOD", "ABC", "ACHE")
                .col("NAME", "ABC", "ZZZ").col("TSVAL", "", "X").build();
        return EvaluationContext.builder().table(t).build();
    }


    private static BitSet fired(String source)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(source), ctx());
    }


    private static BitSet rows(int... idx)
    {
        BitSet b = new BitSet();
        for (int i : idx)
        {
            b.set(i);
        }
        return b;
    }


    @Test
    void theStrWrappedPairIsComparedTypeInsensitively()
    {
        assertEquals(rows(0), fired("str(AEDECOD) == str(NAME)"));
        assertEquals(rows(1), fired("str(AEDECOD) != str(NAME)"));
    }


    @Test
    void aBooleanCallComparedToALiteralOnTheRight()
    {
        assertEquals(rows(0), fired("empty(TSVAL) == true"));
        assertEquals(rows(1), fired("empty(TSVAL) == false"));
    }


    @Test
    void aBooleanCallComparedToALiteralOnTheLeft()
    {
        // ⚑ The previously uncovered shape: the compiler has a separate branch for the literal in
        // the LEFT operand, and it must agree with the right-hand form above.
        assertEquals(rows(0), fired("true == empty(TSVAL)"));
        assertEquals(rows(1), fired("false == empty(TSVAL)"));
    }


    @Test
    void twoBooleanCallsComparedToEachOther()
    {
        // empty(TSVAL) is [true, false]; empty(NAME) is [false, false] — they agree on row 1.
        assertEquals(rows(1), fired("empty(TSVAL) == empty(NAME)"));
        assertEquals(rows(0), fired("empty(TSVAL) != empty(NAME)"));
    }


    @Test
    void anAffixCallOnTheLeftOfAnInequalityStaysComplementaryWithItsEquality()
    {
        // prefix(NAME, 2) is ["AB", "ZZ"]. NEQ takes its own branch; EQ falls through to the
        // generic tail, and the two must remain exact complements.
        assertEquals(rows(0), fired("prefix(NAME, 2) == \"AB\""));
        assertEquals(rows(1), fired("prefix(NAME, 2) != \"AB\""));
    }

}
