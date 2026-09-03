package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class CheckOperatorTest
{

    @Test
    void testFromJson_knownValues()
    {
        assertEquals(CheckOperator.EQUAL_TO, CheckOperator.fromJson("equal_to"));
        assertEquals(CheckOperator.NOT_EQUAL_TO, CheckOperator.fromJson("not_equal_to"));
        assertEquals(CheckOperator.VAR_EXISTS, CheckOperator.fromJson("var_exists"));
        assertEquals(CheckOperator.EMPTY, CheckOperator.fromJson("empty"));
        assertEquals(CheckOperator.MATCHES_REGEX, CheckOperator.fromJson("matches_regex"));
        assertEquals(CheckOperator.IS_CONTAINED_BY, CheckOperator.fromJson("is_contained_by"));
        assertEquals(CheckOperator.IS_NOT_UNIQUE_SET, CheckOperator.fromJson("is_not_unique_set"));
        assertEquals(CheckOperator.INVALID_DATE, CheckOperator.fromJson("invalid_date"));
    }


    @Test
    void testFromJson_unknownReturnsNull()
    {
        assertNull(CheckOperator.fromJson("unknown_operator"));
        assertNull(CheckOperator.fromJson(""));
        assertNull(CheckOperator.fromJson(null));
    }


    @Test
    void testJsonValue_roundTrip()
    {
        for (CheckOperator op : CheckOperator.values())
        {
            assertEquals(op, CheckOperator.fromJson(op.getJsonValue()),
                    "Round-trip failed for " + op);
        }
    }


    @Test
    void testAllOperatorsHaveJsonValue()
    {
        for (CheckOperator op : CheckOperator.values())
        {
            assertNotNull(op.getJsonValue(), "Null jsonValue for " + op);
            assertFalse(op.getJsonValue().isEmpty(), "Empty jsonValue for " + op);
        }
    }

}
