package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class SensitivityTest
{

    @Test
    void testFromJson_allValues()
    {
        assertEquals(Sensitivity.RECORD, Sensitivity.fromJson("Record"));
        assertEquals(Sensitivity.DATASET, Sensitivity.fromJson("Dataset"));
        assertEquals(Sensitivity.GROUP, Sensitivity.fromJson("Group"));
    }


    @Test
    void testFromJson_unknownReturnsNull()
    {
        assertNull(Sensitivity.fromJson("Unknown"));
        assertNull(Sensitivity.fromJson(null));
    }


    @Test
    void testJsonValue_roundTrip()
    {
        for (Sensitivity s : Sensitivity.values())
        {
            assertEquals(s, Sensitivity.fromJson(s.getJsonValue()));
        }
    }

}
