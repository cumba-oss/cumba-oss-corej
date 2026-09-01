package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.cumba.cdisc.core.model.*;
import org.junit.jupiter.api.Test;

class CheckConditionDeserializationTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void testSimpleAll() throws Exception
    {
        String json = """
                {
                  "all": [
                    { "name": "AGE", "operator": "greater_than", "value": 18 },
                    { "name": "SEX", "operator": "equal_to", "value": "M" }
                  ]
                }
                """;

        CheckCondition condition = MAPPER.readValue(json, CheckCondition.class);
        assertInstanceOf(CheckConditionAll.class, condition);
        CheckConditionAll all = (CheckConditionAll) condition;
        assertEquals(2, all.getConditions().size());

        CheckConditionLeaf leaf0 = (CheckConditionLeaf) all.getConditions().get(0);
        assertEquals("AGE", leaf0.getName());
        assertEquals(CheckOperator.GREATER_THAN, leaf0.getCheckOperator());
        assertTrue(leaf0.getValue().isInt());
        assertEquals(18, leaf0.getValue().intValue());

        CheckConditionLeaf leaf1 = (CheckConditionLeaf) all.getConditions().get(1);
        assertEquals("SEX", leaf1.getName());
        assertEquals("M", leaf1.getValue().textValue());
    }


    @Test
    void testNestedAnyInsideAll() throws Exception
    {
        String json = """
                {
                  "all": [
                    { "name": "A", "operator": "var_exists" },
                    {
                      "any": [
                        { "name": "B", "operator": "equal_to", "value": 1 },
                        { "name": "C", "operator": "equal_to", "value": 2 }
                      ]
                    }
                  ]
                }
                """;

        CheckCondition condition = MAPPER.readValue(json, CheckCondition.class);
        assertInstanceOf(CheckConditionAll.class, condition);
        CheckConditionAll all = (CheckConditionAll) condition;
        assertEquals(2, all.getConditions().size());
        assertInstanceOf(CheckConditionLeaf.class, all.getConditions().get(0));
        assertInstanceOf(CheckConditionAny.class, all.getConditions().get(1));

        CheckConditionAny any = (CheckConditionAny) all.getConditions().get(1);
        assertEquals(2, any.getConditions().size());
    }


    @Test
    void testNotCondition() throws Exception
    {
        String json = """
                {
                  "not": { "name": "FLAG", "operator": "equal_to", "value": true }
                }
                """;

        CheckCondition condition = MAPPER.readValue(json, CheckCondition.class);
        assertInstanceOf(CheckConditionNot.class, condition);
        CheckConditionNot not = (CheckConditionNot) condition;
        assertInstanceOf(CheckConditionLeaf.class, not.getCondition());

        CheckConditionLeaf leaf = (CheckConditionLeaf) not.getCondition();
        assertEquals("FLAG", leaf.getName());
        assertTrue(leaf.getValue().isBoolean());
        assertTrue(leaf.getValue().booleanValue());
    }


    @Test
    void testLeafWithModifiers() throws Exception
    {
        String json = """
                {
                  "name": "TESTVAR",
                  "operator": "prefix_equal_to",
                  "value": "XY",
                  "prefix": 2,
                  "suffix": 3,
                  "value_is_literal": true,
                  "value_is_reference": false,
                  "type_insensitive": true,
                  "negative": false,
                  "regex": "^[A-Z]+$",
                  "within": "DOMAIN",
                  "ordering": "ASC"
                }
                """;

        CheckCondition condition = MAPPER.readValue(json, CheckCondition.class);
        assertInstanceOf(CheckConditionLeaf.class, condition);
        CheckConditionLeaf leaf = (CheckConditionLeaf) condition;

        assertEquals("TESTVAR", leaf.getName());
        assertEquals("prefix_equal_to", leaf.getOperator());
        assertEquals(CheckOperator.PREFIX_EQUAL_TO, leaf.getCheckOperator());
        assertEquals("XY", leaf.getValue().textValue());
        assertEquals(2, leaf.getPrefix());
        assertEquals(3, leaf.getSuffix());
        assertTrue(leaf.getValueIsLiteral());
        assertFalse(leaf.getValueIsReference());
        assertTrue(leaf.getTypeInsensitive());
        assertFalse(leaf.getNegative());
        assertEquals("^[A-Z]+$", leaf.getRegex());
        assertEquals(java.util.List.of("DOMAIN"), leaf.getWithinColumns());
        assertEquals("ASC", leaf.getOrdering());
    }


    @Test
    void testLeafWithArrayValue() throws Exception
    {
        String json = """
                {
                  "name": "AESER",
                  "operator": "is_not_contained_by",
                  "value": ["Y", "N"]
                }
                """;

        CheckCondition condition = MAPPER.readValue(json, CheckCondition.class);
        assertInstanceOf(CheckConditionLeaf.class, condition);
        CheckConditionLeaf leaf = (CheckConditionLeaf) condition;

        assertTrue(leaf.getValue().isArray());
        assertEquals(2, leaf.getValue().size());
        assertEquals("Y", leaf.getValue().get(0).textValue());
        assertEquals("N", leaf.getValue().get(1).textValue());
    }


    @Test
    void testLeafWithBooleanValue() throws Exception
    {
        String json = """
                {
                  "name": "$domain_is_custom",
                  "operator": "equal_to",
                  "value": false
                }
                """;

        CheckCondition condition = MAPPER.readValue(json, CheckCondition.class);
        assertInstanceOf(CheckConditionLeaf.class, condition);
        CheckConditionLeaf leaf = (CheckConditionLeaf) condition;

        assertTrue(leaf.getValue().isBoolean());
        assertFalse(leaf.getValue().booleanValue());
    }


    @Test
    void testLeafWithNumericValue() throws Exception
    {
        String json = """
                {
                  "name": "$VARIABLE_COUNT",
                  "operator": "less_than",
                  "value": 2
                }
                """;

        CheckCondition condition = MAPPER.readValue(json, CheckCondition.class);
        assertInstanceOf(CheckConditionLeaf.class, condition);
        CheckConditionLeaf leaf = (CheckConditionLeaf) condition;

        assertTrue(leaf.getValue().isInt());
        assertEquals(2, leaf.getValue().intValue());
    }


    @Test
    void testLeafWithNoValue() throws Exception
    {
        String json = """
                {
                  "name": "VISITNUM",
                  "operator": "var_exists"
                }
                """;

        CheckCondition condition = MAPPER.readValue(json, CheckCondition.class);
        assertInstanceOf(CheckConditionLeaf.class, condition);
        CheckConditionLeaf leaf = (CheckConditionLeaf) condition;

        assertEquals("VISITNUM", leaf.getName());
        assertEquals(CheckOperator.VAR_EXISTS, leaf.getCheckOperator());
        assertNull(leaf.getValue());
    }

}
