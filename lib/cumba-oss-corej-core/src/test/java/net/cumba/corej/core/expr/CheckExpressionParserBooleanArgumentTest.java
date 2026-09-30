package net.cumba.corej.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import net.cumba.corej.core.expr.ast.Expr;
import org.junit.jupiter.api.Test;

/**
 * Runbook W2a ({@code PLAN-operation-replacements} row P, owner D9 / D12): a parenthesised argument
 * is a whole expression, so a boolean can be handed to a function — {@code filter=(A == "b")}. The
 * arithmetic grouping the parenthesis served before still parses, and an unparenthesised comparison
 * in argument position is still refused (the grammar widened by exactly one arm).
 */
class CheckExpressionParserBooleanArgumentTest
{

    @Test
    void aParenthesisedComparisonIsAKeywordArgument()
    {
        Expr e = CheckExpressionParser.parse(
                "read_value(TSVAL, domain=TS, filter=(TSPARMCD == \"SPECIES\"), mode=\"FIRST\")");
        Expr.Call call = assertInstanceOf(Expr.Call.class, e);
        Expr.Binary filter = assertInstanceOf(Expr.Binary.class, call.kwargs().get("filter"));
        assertEquals(Expr.BinOp.EQ, filter.op());
        assertEquals("TSPARMCD", ((Expr.Ref) filter.left()).name());
    }


    @Test
    void aParenthesisedBooleanExpressionIsAPositionalArgumentToo()
    {
        Expr e = CheckExpressionParser.parse("f((A == \"b\" and not empty(C)))");
        Expr.Call call = assertInstanceOf(Expr.Call.class, e);
        assertInstanceOf(Expr.And.class, call.args().get(0));
    }


    @Test
    void arithmeticGroupingInOperandPositionStillParsesAsBefore()
    {
        // The widened arm is parseAtom's (operand position): `(A + B) * C` on the right of a
        // comparison grouped through it before and still does.
        Expr e = CheckExpressionParser.parse("D == (A + B) * C");
        Expr.Binary cmp = assertInstanceOf(Expr.Binary.class, e);
        assertEquals(Expr.BinOp.EQ, cmp.op());
        Expr.Binary mul = assertInstanceOf(Expr.Binary.class, cmp.right());
        assertEquals(Expr.BinOp.MUL, mul.op());
        Expr.Binary sum = assertInstanceOf(Expr.Binary.class, mul.left());
        assertEquals(Expr.BinOp.ADD, sum.op());
    }


    @Test
    void anUnparenthesisedComparisonInArgumentPositionIsStillRefused()
    {
        assertThrows(ExpressionException.class,
                () -> CheckExpressionParser.parse("f(x, filter=A == \"b\")"));
    }
}
