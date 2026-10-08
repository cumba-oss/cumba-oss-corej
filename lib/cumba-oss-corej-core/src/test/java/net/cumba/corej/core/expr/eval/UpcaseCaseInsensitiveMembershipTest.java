package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code upcase} is {@code upper}'s registered alias, and so it takes the case-insensitive
 * membership path {@code upper} takes (runbook W2 UA, §3 table B;
 * {@code FINDINGS-unowned-residuals} G2): {@code upcase(X) in [...]} folds the probe and the
 * members, exactly as {@code upper(X) in
 * [...]} does - in all three positions {@code ExprCompiler.isUpperCall} decides (the probe unwrap
 * of a {@code colref($x)} probe, the case-insensitive flag, and an {@code upcase("...")} member).
 * {@code lower} / {@code lowcase} have no such path and stay value calls.
 *
 * <p>
 * Before UA, {@code upcase(X) not in ["a"]} compared the upper-cased value with the member as
 * written and fired on every row, where {@code upper(X) not in ["a"]} fires only on the value that
 * is not {@code a} in any case.
 * </p>
 */
class UpcaseCaseInsensitiveMembershipTest
{

    /** X: a / A / c; Y: z / z / C. */
    private static final IDataTable T = MockTable.of().col("X", "a", "A", "c")
            .col("Y", "z", "z", "C").build();

    private static BitSet eval(String expression)
    {
        return eval(expression, Map.of());
    }


    private static BitSet eval(String expression, Map<String, Object> variables)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expression),
                EvaluationContext.builder().table(T).variables(new LinkedHashMap<>(variables))
                        .build());
    }


    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    @Test
    void upcaseTakesTheCaseInsensitiveFlag()
    {
        assertEquals(bits(2), eval("upper(X) not in [\"a\"]"), "the upper reference answer");
        assertEquals(bits(2), eval("upcase(X) not in [\"a\"]"),
                "upcase folds the probe AND the members, as upper does");
        assertEquals(bits(0, 1), eval("upcase(X) in [\"a\"]"));
        assertEquals(eval("upper(X) in [\"A\", \"C\"]"), eval("upcase(X) in [\"A\", \"C\"]"));
    }


    @Test
    void anUpcaseMemberIsUnwrappedAsAnUpperMemberIs()
    {
        assertEquals(eval("upper(X) in [upper(\"a\")]"), eval("upper(X) in [upcase(\"a\")]"));
        assertEquals(bits(0, 1), eval("upper(X) in [upcase(\"a\")]"));
        assertEquals(eval("X in [upper(\"a\")]"), eval("X in [upcase(\"a\")]"));
    }


    @Test
    void anUpcaseOverAColrefProbeIsUnwrappedPerRow()
    {
        // colref($v) over a list of names reads [X, Y] per row: [a, z] / [A, z] / [c, C]
        Map<String, Object> v = Map.of("$v", List.of("X", "Y"));
        assertEquals(eval("upper(colref($v)) in [\"a\"]", v),
                eval("upcase(colref($v)) in [\"a\"]", v));
        assertEquals(bits(0, 1), eval("upcase(colref($v)) in [\"a\"]", v));
        assertEquals(bits(2), eval("upcase(colref($v)) in [\"c\"]", v));
    }


    @Test
    void lowerAndLowcaseStayValueCalls()
    {
        // no case-insensitive path exists for them: the folded value against the members as
        // written
        assertEquals(bits(0, 1), eval("lower(X) in [\"a\"]"));
        assertEquals(eval("lower(X) in [\"a\"]"), eval("lowcase(X) in [\"a\"]"));
        assertEquals(bits(), eval("lowcase(X) in [\"A\"]"));
    }
}
