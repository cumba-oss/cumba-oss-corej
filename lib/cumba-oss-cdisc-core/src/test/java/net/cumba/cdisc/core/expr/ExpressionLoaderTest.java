package net.cumba.cdisc.core.expr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.fasterxml.jackson.databind.ObjectMapper;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import org.junit.jupiter.api.Test;

/**
 * Verifies the {@code {"expression": ...}} leaf form is recognised by
 * {@link net.cumba.cdisc.core.model.CheckConditionDeserializer} and lowers into the same AST as the
 * equivalent old-style leaves, including when the two forms are mixed in one {@code Check}.
 */
class ExpressionLoaderTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CheckCondition read(String json) throws Exception
    {
        return MAPPER.readValue(json, CheckCondition.class);
    }


    @Test
    void mixedOldAndExpressionLeaves() throws Exception
    {
        String mixed = "{\"all\":["
                + "{\"name\":\"IECAT\",\"operator\":\"equal_to\",\"value\":\"INCLUSION\","
                + "\"value_is_literal\":true}," + "{\"expression\":\"IEORRES != \\\"N\\\"\"}]}";
        CheckConditionAll all = assertInstanceOf(CheckConditionAll.class, read(mixed));
        assertEquals(2, all.getConditions().size());

        CheckConditionLeaf old = assertInstanceOf(CheckConditionLeaf.class,
                all.getConditions().get(0));
        assertEquals("equal_to", old.getOperator());

        CheckConditionLeaf expr = assertInstanceOf(CheckConditionLeaf.class,
                all.getConditions().get(1));
        assertEquals("IEORRES", expr.getName());
        assertEquals("not_equal_to", expr.getOperator());
        assertEquals("N", expr.getValue().asText());
    }


    @Test
    void expressionLeafExpandsToComposite() throws Exception
    {
        CheckCondition c = read("{\"expression\":\"A == \\\"1\\\" && B == \\\"2\\\"\"}");
        CheckConditionAll all = assertInstanceOf(CheckConditionAll.class, c);
        assertEquals(2, all.getConditions().size());
    }


    @Test
    void malformedExpressionFailsLoudly()
    {
        assertThrows(Exception.class, () -> read("{\"expression\":\"A == \\\"unterminated\"}"));
    }

}
