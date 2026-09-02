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
 * Verdict pins for {@link ExprCompiler}'s boolean-node plans: {@code and}/{@code or} composition,
 * the unified boolean surface ({@code <bool> == true/false}, {@code <bool> == <bool>}) and the
 * {@code not equalsIgnoreCase} mapping. Every assertion is an exact violation {@link BitSet} with
 * at least one row on each side of the branch, so a nulled or inverted plan (the surviving
 * NullReturnVals / NegateConditionals mutants in {@code compileNot}, {@code compileOr},
 * {@code boolEqLiteral}, {@code boolEqBool}, {@code boolLiteralOf},
 * {@code compileCaseInsensitiveEquality}) changes a verdict this test asserts — a wrong plan here
 * silently changes which rows a correct rule reports.
 */
class ExprCompilerBooleanNodesVerdictTest
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


    /**
     * contains(AETERM,"HEAD") fires rows {0,2}; contains(AECAT,"X") fires rows {0,2,3} — the four
     * rows cover T/T, F/F, T/T, F/T so the XNOR/XOR verdicts are fully discriminating.
     */
    private static EvaluationContext ctx()
    {
        IDataTable t = MockTable.of().name("AE").col("AETERM", "HEADACHE", "NAUSEA", "HEADPAIN", "")
                .col("AECAT", "XRAY", "OTHER", "XCAT", "X").build();
        return EvaluationContext.builder().table(t).build();
    }

    // ---- boolEqBool: == is the per-row XNOR, != the XOR --------------------


    @Test
    void boolCallEqualsBoolCallIsXnor()
    {
        // Rows where the two verdicts AGREE: {0,2} (both true) and {1} (both false).
        assertEquals(bits(0, 1, 2),
                eval("contains(AETERM, \"HEAD\") == contains(AECAT, \"X\")", ctx()),
                "== over two boolean calls must fire exactly where the verdicts agree");
    }


    @Test
    void boolCallNotEqualsBoolCallIsXor()
    {
        // Row 3 is the only disagreement (AETERM missing -> contains false; AECAT "X" -> true).
        assertEquals(bits(3), eval("contains(AETERM, \"HEAD\") != contains(AECAT, \"X\")", ctx()),
                "!= over two boolean calls must fire exactly where the verdicts differ");
    }

    // ---- boolEqLiteral: identity vs complement ------------------------------


    @Test
    void boolCallComparedToLiteralSelectsIdentityOrComplement()
    {
        // contains(AECAT,"X") fires {0,2,3}; its complement is {1}.
        assertEquals(bits(0, 2, 3), eval("contains(AECAT, \"X\") == true", ctx()),
                "== true is the identity");
        assertEquals(bits(0, 2, 3), eval("contains(AECAT, \"X\") != false", ctx()),
                "!= false is the identity");
        assertEquals(bits(1), eval("contains(AECAT, \"X\") == false", ctx()),
                "== false is the complement");
        assertEquals(bits(1), eval("contains(AECAT, \"X\") != true", ctx()),
                "!= true is the complement");
    }


    @Test
    void literalOnTheLeftIsSymmetric()
    {
        assertEquals(bits(1), eval("false == contains(AECAT, \"X\")", ctx()),
                "a bool literal on the LEFT must take the same boolean surface");
        assertEquals(bits(0, 2, 3), eval("true == contains(AECAT, \"X\")", ctx()),
                "true == <bool> is the identity");
    }

    // ---- and / or composition ------------------------------------------------


    @Test
    void andIsTheRowIntersection()
    {
        assertEquals(bits(0, 2),
                eval("contains(AETERM, \"HEAD\") and contains(AECAT, \"X\")", ctx()),
                "and must fire exactly the intersection");
    }


    @Test
    void orIsTheRowUnion()
    {
        assertEquals(bits(0, 1), eval("AETERM == \"HEADACHE\" or AECAT == \"OTHER\"", ctx()),
                "or must fire exactly the union");
    }

    // ---- not equalsIgnoreCase → negated case-insensitive equality -------------


    @Test
    void equalsIgnoreCasePositiveAndNegatedVerdicts()
    {
        IDataTable t = MockTable.of().name("AE").col("VALC", "Abc", "xyz", "").build();
        EvaluationContext c = EvaluationContext.builder().table(t).build();
        assertEquals(bits(0), eval("equalsIgnoreCase(VALC, \"ABC\")", c),
                "case-insensitive equality must fire only the case-folded match");
        assertEquals(bits(1, 2), eval("not equalsIgnoreCase(VALC, \"ABC\")", c),
                "the negated surface must fire exactly the non-matching rows");
        // Both-empty is a MATCH (missing folds to ""), so the negation must NOT fire row 2. If the
        // `not equalsIgnoreCase` interception is lost, the fallthrough is an unsupported bool call
        // (equalsIgnoreCase is not a registered boolean function) and evaluation throws instead.
        assertEquals(bits(0, 1), eval("not equalsIgnoreCase(VALC, \"\")", c),
                "a both-empty row is equal, so its negation must not fire");
    }

    // ---- bare non-boolean nodes are rejected, not silently accepted ----------


    @Test
    void bareReferenceAndLiteralAreNotConditions()
    {
        EvaluationContext c = ctx();
        assertThrows(ExpressionException.class, () -> eval("AETERM", c),
                "a bare column reference must not compile as a condition");
        assertThrows(ExpressionException.class, () -> eval("true and contains(AECAT, \"X\")", c),
                "a bare literal must not compile as a condition");
    }

}
