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
        // PLAN-cg0218-transition-day-precision: the one exception is joined(…); a quantifier
        // itself is an ordinary call, so a bare comparison in ITS argument position stays refused
        assertThrows(ExpressionException.class,
                () -> CheckExpressionParser.parse("any(A == \"b\")"));
    }


    /**
     * {@code PLAN-cg0218-transition-day-precision} P1: {@code joined(<condition>)} takes a boolean
     * by declaration, so the parser reads its argument as a whole expression without the extra
     * parentheses, the printer writes it bare again, and the parenthesised spelling still parses to
     * the same tree.
     */
    @Test
    void joinedTakesABareBooleanExpressionAndRoundTrips()
    {
        String text = "none(joined(date(SE.SESTDTC) <= date(SVSTDTC) and EPOCH == SE.EPOCH))";
        Expr e = CheckExpressionParser.parse(text);
        Expr.Call none = assertInstanceOf(Expr.Call.class, e);
        Expr.Call joined = assertInstanceOf(Expr.Call.class, none.args().get(0));
        assertEquals("joined", joined.name());
        assertInstanceOf(Expr.And.class, joined.args().get(0));
        assertEquals(text, ExpressionPrinter.print(e), "printed bare, no second parenthesis");
        assertEquals(e,
                CheckExpressionParser.parse(
                        "none(joined((date(SE.SESTDTC) <= date(SVSTDTC) and EPOCH == SE.EPOCH)))"),
                "the parenthesised spelling is the same tree");
        assertEquals("any(joined(not empty(SE.EPOCH)))", ExpressionPrinter
                .print(CheckExpressionParser.parse("any(joined(not empty(SE.EPOCH)))")));
        assertEquals("all(joined(SE.EPOCH in [\"A\", \"B\"] or empty(SE.EPOCH)))",
                ExpressionPrinter.print(CheckExpressionParser
                        .parse("all(joined(SE.EPOCH in [\"A\", \"B\"] or empty(SE.EPOCH)))")));
    }
}
