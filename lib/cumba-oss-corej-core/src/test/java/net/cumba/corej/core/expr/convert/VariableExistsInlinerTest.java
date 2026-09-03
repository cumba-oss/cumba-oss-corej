package net.cumba.corej.core.expr.convert;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.Operation;
import org.junit.jupiter.api.Test;

/**
 * {@link VariableExistsInliner}: a {@code variable_exists} operation consumed in the Check only as
 * {@code $X == true} / {@code $X == false} maps to the {@code var_exists(<col>)} /
 * {@code not var_exists(<col>)} check function; a {@code domain} qualifies the column; any other
 * reference shape leaves the operation eligible-free (field form).
 */
class VariableExistsInlinerTest
{

    private static Operation varExistsOp(String id, String name, String domain)
    {
        Operation op = new Operation();
        op.setId(id);
        op.setName(name);
        op.setDomain(domain);
        op.setOperator("variable_exists");
        return op;
    }


    private static Expr parse(String expr)
    {
        return CheckExpressionParser.parse(expr);
    }


    @Test
    void candidateColumnsQualifiesDomain()
    {
        Map<String, String> cols = VariableExistsInliner.candidateColumns(
                List.of(varExistsOp("$A", "EXVAMT", null), varExistsOp("$B", "EXVAMTU", "EX")));
        assertEquals(Map.of("$A", "EXVAMT", "$B", "EX.EXVAMTU"), cols);
    }


    @Test
    void candidateColumnsSkipsNonVariableExistsAndIncomplete()
    {
        Operation rc = new Operation();
        rc.setId("$RC");
        rc.setOperator("record_count");
        Operation noName = varExistsOp("$N", null, null);
        assertTrue(VariableExistsInliner.candidateColumns(List.of(rc, noName)).isEmpty());
        assertTrue(VariableExistsInliner.candidateColumns(null).isEmpty());
    }


    @Test
    void eligibleWhenEveryUseIsBoolCompare()
    {
        Map<String, String> cands = Map.of("$X", "EXVAMT");
        Expr e = parse("$X == true and ds_exists(EC)");
        assertEquals(cands, VariableExistsInliner.eligible(List.of(e), cands));
    }


    @Test
    void ineligibleWhenReferencedOutsideBoolCompare()
    {
        Map<String, String> cands = Map.of("$X", "EXVAMT");
        // $X also appears bare in empty($X) — not exclusively a == true/false operand.
        Expr e = parse("$X == true or empty($X)");
        assertTrue(VariableExistsInliner.eligible(List.of(e), cands).isEmpty());
    }


    @Test
    void rewriteEqTrueToVarExists()
    {
        Map<String, String> elig = Map.of("$X", "EXVAMT");
        Expr out = VariableExistsInliner.rewrite(parse("$X == true and ds_exists(EC)"), elig);
        assertEquals("var_exists(\"EXVAMT\") and ds_exists(EC)", ExpressionPrinter.print(out));
    }


    @Test
    void rewriteEqFalseToNotVarExists()
    {
        Map<String, String> elig = Map.of("$X", "EXVAMT");
        Expr out = VariableExistsInliner.rewrite(parse("$X == false"), elig);
        assertEquals("not var_exists(\"EXVAMT\")", ExpressionPrinter.print(out));
    }


    @Test
    void rewriteQualifiedColumn()
    {
        Map<String, String> elig = Map.of("$B", "EX.EXVAMTU");
        Expr out = VariableExistsInliner.rewrite(parse("$B == true and ds_exists(EC)"), elig);
        assertEquals("var_exists(EX.EXVAMTU) and ds_exists(EC)", ExpressionPrinter.print(out));
    }


    @Test
    void rewriteEmptyMapIsNoOp()
    {
        Expr in = parse("$X == true");
        assertEquals(in, VariableExistsInliner.rewrite(in, Map.of()));
    }


    @Test
    void rewriteOverOrBranch()
    {
        Map<String, String> elig = Map.of("$X", "EXVAMT");
        Expr out = VariableExistsInliner.rewrite(parse("$X == true or ds_exists(EC)"), elig);
        assertEquals("var_exists(\"EXVAMT\") or ds_exists(EC)", ExpressionPrinter.print(out));
    }


    @Test
    void rewriteWithBooleanLiteralOnLeft()
    {
        Map<String, String> elig = Map.of("$X", "EXVAMT");
        Expr out = VariableExistsInliner.rewrite(parse("true == $X"), elig);
        assertEquals("var_exists(\"EXVAMT\")", ExpressionPrinter.print(out));
    }


    @Test
    void rewriteLeavesNonMatchingBinaryRecursingChildren()
    {
        Map<String, String> elig = Map.of("$X", "EXVAMT");
        // The `AGE > 5` binary is not a $X==bool match — it is recursed into and left intact.
        Expr out = VariableExistsInliner.rewrite(parse("$X == true and AGE > 5"), elig);
        assertEquals("var_exists(\"EXVAMT\") and AGE > 5", ExpressionPrinter.print(out));
    }


    @Test
    void eligibleAcrossNotAndCallArgs()
    {
        // $X used only as `== true`; the `not` and the ds_exists(EC) call exercise the Not / Call
        // walk branches of the eligibility counters without disqualifying $X.
        Map<String, String> cands = Map.of("$X", "EXVAMT");
        Expr e = parse("$X == true and not ds_exists(EC)");
        assertEquals(cands, VariableExistsInliner.eligible(List.of(e), cands));
    }


    @Test
    void eligibleEmptyWhenNoCandidates()
    {
        assertTrue(VariableExistsInliner.eligible(List.of(parse("AGE > 5")), Map.of()).isEmpty());
    }

    // ---- reported(): which lowered operations survive for reporting -------------------------


    /**
     * The reporting warrant is narrow on purpose: only an id the rule <em>names</em> in
     * {@code Outcome.Output_Variables} keeps its operation. Everything else is still dropped, so
     * the corpus does not accumulate operations nobody reads.
     */
    @Test
    void reportedSelectsOnlyTheDeclaredOutputVariables()
    {
        assertEquals(java.util.Set.of("$X"), VariableExistsInliner
                .reported(java.util.Set.of("$X", "$Y"), List.of("$X", "EXVAMT")));
    }


    @Test
    void reportedIsEmptyWhenNothingIsDeclared()
    {
        assertTrue(VariableExistsInliner.reported(java.util.Set.of("$X"), null).isEmpty());
        assertTrue(VariableExistsInliner.reported(java.util.Set.of("$X"), List.of()).isEmpty());
        assertTrue(VariableExistsInliner.reported(java.util.Set.of(), List.of("$X")).isEmpty());
    }


    /** A declared output variable that is not an eligible id is not a retention reason. */
    @Test
    void reportedIgnoresOutputVariablesThatAreNotEligibleIds()
    {
        assertTrue(VariableExistsInliner.reported(java.util.Set.of("$X"), List.of("$Z", "EXVAMT"))
                .isEmpty());
    }
}
