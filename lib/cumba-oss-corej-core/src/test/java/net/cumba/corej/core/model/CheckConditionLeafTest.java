package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CheckConditionLeafTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void testGetCheckOperator_known()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X").operator("equal_to")
                .build();
        assertEquals(CheckOperator.EQUAL_TO, leaf.getCheckOperator());
    }


    @Test
    void testGetCheckOperator_unknown()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X")
                .operator("some_future_operator").build();
        assertNull(leaf.getCheckOperator());
    }


    @Test
    void testGetCheckOperator_null()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X").build();
        assertNull(leaf.getCheckOperator());
    }


    @Test
    void testBuilder_allFields()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("TESTVAR")
                .operator("prefix_equal_to").value(MAPPER.valueToTree("XY")).valueIsLiteral(true)
                .valueIsReference(false).typeInsensitive(true).negative(false).regex("^[A-Z]+$")
                .prefix(2).suffix(3).within(MAPPER.valueToTree("DOMAIN")).ordering("ASC").build();

        assertEquals("TESTVAR", leaf.getName());
        assertEquals("prefix_equal_to", leaf.getOperator());
        assertEquals("XY", leaf.getValue().textValue());
        assertTrue(leaf.getValueIsLiteral());
        assertFalse(leaf.getValueIsReference());
        assertTrue(leaf.getTypeInsensitive());
        assertFalse(leaf.getNegative());
        assertEquals("^[A-Z]+$", leaf.getRegex());
        assertEquals(2, leaf.getPrefix());
        assertEquals(3, leaf.getSuffix());
        assertEquals(java.util.List.of("DOMAIN"), leaf.getWithinColumns());
        assertEquals("ASC", leaf.getOrdering());
    }


    @Test
    void testBuilder_minimalFields()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X").operator("var_exists")
                .build();

        assertEquals("X", leaf.getName());
        assertEquals("var_exists", leaf.getOperator());
        assertNull(leaf.getValue());
        assertNull(leaf.getValueIsLiteral());
        assertNull(leaf.getValueIsReference());
        assertNull(leaf.getPrefix());
        assertNull(leaf.getSuffix());
    }


    @Test
    void testIsSealed_implementsCheckCondition()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("X").operator("var_exists")
                .build();
        assertInstanceOf(CheckCondition.class, leaf);
    }

}
