package net.cumba.corej.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionExpression;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code {"expression": ...}} leaf form is recognised by
 * {@link net.cumba.corej.core.model.CheckConditionDeserializer} and <b>kept as the expression it
 * was written as</b>, including inside an {@code all:} composite (phase 7d: the old-style
 * operator-leaf half of the former mixed Check is retired).
 *
 * <p>
 * ⭐ <b>Phase 7 of {@code PLAN-typed-expression-engine} turned this class inside out, and that is
 * the point.</b> It used to assert that an expression leaf <em>lowered into the same AST as the
 * equivalent old-style leaves</em> — {@code IEORRES != "N"} becoming a {@code CheckConditionLeaf}
 * with {@code operator=not_equal_to}, and {@code A == "1" && B == "2"} becoming a
 * {@code CheckConditionAll} of two leaves. That lowering was a round-trip through a representation
 * the evaluator never used ({@code RulePackageLoader.installNativeExpr} raised it straight back to
 * {@code Expr}), and phase 7 removed it. These tests now pin the <b>property</b> — the expression
 * survives, structurally, and still composes with the leaf form — rather than the spelling it used
 * to be flattened into (D109a: a test that pins a REPRESENTATION blocks the migration that
 * representation exists to enable).
 * </p>
 */
class ExpressionLoaderTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CheckCondition read(String json) throws Exception
    {
        return MAPPER.readValue(json, CheckCondition.class);
    }


    @Test
    void compositeOfExpressions() throws Exception
    {
        // Phase 7d (D121): the old-style operator-leaf half of the former "mixed" Check is
        // retired; the composite grammar itself survives, over expression nodes.
        String mixed = "{\"all\":[" + "{\"expression\":\"IECAT == \\\"INCLUSION\\\"\"},"
                + "{\"expression\":\"IEORRES != \\\"N\\\"\"}]}";
        CheckConditionAll all = assertInstanceOf(CheckConditionAll.class, read(mixed));
        assertEquals(2, all.getConditions().size());
        assertInstanceOf(CheckConditionExpression.class, all.getConditions().get(0));

        // The expression branch keeps its Expr: `!=` is a Binary, not a `not_equal_to` leaf.
        CheckConditionExpression expr = assertInstanceOf(CheckConditionExpression.class,
                all.getConditions().get(1));
        Expr.Binary binary = assertInstanceOf(Expr.Binary.class, expr.expr());
        assertEquals(Expr.BinOp.NEQ, binary.op());
        assertEquals(new Expr.Ref("IEORRES", OperandKind.COLUMN), binary.left());
        assertEquals(new Expr.Lit(Expr.LitKind.STRING, "N"), binary.right());
        assertEquals("IEORRES != \"N\"", expr.source(), "the authored text is kept verbatim");
    }


    @Test
    void expressionLeafKeepsItsOwnConjunction() throws Exception
    {
        // It used to expand into a CheckConditionAll of two leaves. The conjunction is now the
        // expression's own And node — one condition, not two.
        CheckCondition c = read("{\"expression\":\"A == \\\"1\\\" && B == \\\"2\\\"\"}");
        CheckConditionExpression expr = assertInstanceOf(CheckConditionExpression.class, c);
        Expr.And and = assertInstanceOf(Expr.And.class, expr.expr());
        assertEquals(2, and.parts().size());
    }


    @Test
    void malformedExpressionFailsLoudly()
    {
        assertThrows(Exception.class, () -> read("{\"expression\":\"A == \\\"unterminated\"}"));
    }

}
