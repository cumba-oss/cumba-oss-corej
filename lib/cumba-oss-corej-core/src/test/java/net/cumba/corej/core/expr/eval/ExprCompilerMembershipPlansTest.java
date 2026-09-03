package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Verdict pins for {@link ExprCompiler#compileMembership} and its operand classification: textual
 * vs numeric list literals (decision D2), the case-insensitive {@code upper(X)} surface, the
 * {@code $}-variable reference set, and the whole-tuple T3 membership. Each case asserts the exact
 * fired rows for BOTH polarities, so a mutant that swaps {@code in}/{@code not in}, drops the case
 * fold, or replaces the classified member set (the surviving mutants in {@code compileMembership},
 * {@code isUpperCall}, {@code numericMemberSet}, {@code compileTupleMembership}) moves an asserted
 * verdict.
 */
class ExprCompilerMembershipPlansTest
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


    private static EvaluationContext ctxOf(IDataTable t)
    {
        return EvaluationContext.builder().table(t).build();
    }

    // ---- textual list literal ------------------------------------------------


    @Test
    void textualMembershipBothPolarities()
    {
        IDataTable t = MockTable.of().name("DS").col("CD", "A", "B", "", "C").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 3), eval("CD in [\"A\", \"C\"]", c),
                "in must fire exactly the member rows");
        assertEquals(bits(1, 2), eval("CD not in [\"A\", \"C\"]", c),
                "not in must fire the complement — including the blank row (probed as \"\")");
        // The empty-string member is the author's opt-out for blanks: it must actually suppress.
        assertEquals(bits(1), eval("CD not in [\"A\", \"C\", \"\"]", c),
                "an explicit \"\" member must stop not-in firing on the blank row");
    }

    // ---- case-insensitive surface ----------------------------------------------


    @Test
    void upperWrappedMembershipFoldsProbeAndSet()
    {
        IDataTable t = MockTable.of().name("DS").col("UC", "abc", "ABC", "xyz", "").build();
        EvaluationContext c = ctxOf(t);
        // The set member is authored lower-case: only a case-folded compare matches "ABC".
        assertEquals(bits(0, 1), eval("upper(UC) in [\"abc\"]", c),
                "case-insensitive membership must fold BOTH the probe and the set");
        assertEquals(bits(2, 3), eval("upper(UC) not in [\"abc\"]", c),
                "the negated case-insensitive surface is the exact complement");
    }

    // ---- numeric list literal (decision D2) --------------------------------------


    @Test
    void allNumericListLiteralComparesNumerically()
    {
        IDataTable t = MockTable.of().name("DS").col("NUM", "10.0", "010", "15", "").build();
        EvaluationContext c = ctxOf(t);
        assertEquals(bits(0, 1), eval("NUM in [10, 20]", c),
                "an all-numeric list must parse the probe (10.0 and 010 are the member 10)");
        assertEquals(bits(2, 3), eval("NUM not in [10, 20]", c),
                "not in fires non-members INCLUDING the unparseable blank");
    }


    @Test
    void mixedNumericAndStringListIsARuleDefinitionError()
    {
        IDataTable t = MockTable.of().name("DS").col("NUM", "10").build();
        EvaluationContext c = ctxOf(t);
        assertThrows(RuleDefinitionException.class, () -> eval("NUM in [10, \"A\"]", c),
                "a mixed numeric/string list literal must be rejected, not silently coerced");
    }

    // ---- $-variable reference set --------------------------------------------------


    @Test
    void dollarReferenceSetMembership()
    {
        IDataTable t = MockTable.of().name("DS").col("CD", "A", "B", "", "C").build();
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("$ref", List.of("A", "C"))).build();
        assertEquals(bits(0, 3), eval("CD in $ref", c),
                "a $-list reference must resolve to the membership set");
        assertEquals(bits(1, 2), eval("CD not in $ref", c),
                "not in over a $-list reference is the exact complement");
    }

    // ---- T3 whole-tuple membership ---------------------------------------------------


    @Test
    void tupleMembershipIsWholeTupleNotElementWise()
    {
        IDataTable t = MockTable.of().name("SV").col("VISIT", "W1", "W1", "W2")
                .col("VISITNUM", "1", "2", "1").build();
        // The reference set holds the PAIRS (W1,1) and (W2,2): row 1 (W1,2) and row 2 (W2,1)
        // re-combine seen components, so only whole-tuple matching keeps them firing.
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("$tuples", Set.of(List.of("W1", "1"), List.of("W2", "2"))))
                .build();
        assertEquals(bits(1, 2), eval("tuple(VISIT, VISITNUM) not in $tuples", c),
                "tuple not-in must fire rows whose PAIR is absent, not rows whose parts are");
        assertEquals(bits(0), eval("tuple(VISIT, VISITNUM) in $tuples", c),
                "tuple in must fire only the exact-pair row");
    }


    @Test
    void tupleMembershipAbsentReferenceSetIsEmpty()
    {
        IDataTable t = MockTable.of().name("SV").col("VISIT", "W1").col("VISITNUM", "1").build();
        EvaluationContext c = ctxOf(t);
        // No $missing in the context: the reference tuple set is EMPTY, so `not in` fires every
        // row and `in` none — the single-column membership contract, not an error.
        assertEquals(bits(0), eval("tuple(VISIT, VISITNUM) not in $missing", c),
                "an absent reference set contains nothing, so not-in fires");
        assertEquals(bits(), eval("tuple(VISIT, VISITNUM) in $missing", c),
                "an absent reference set contains nothing, so in never fires");
    }

}
