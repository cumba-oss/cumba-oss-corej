package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class ExecutabilityTest
{

    @Test
    void testFromJson_allValues()
    {
        assertEquals(Executability.FULLY_EXECUTABLE, Executability.fromJson("Fully Executable"));
        assertEquals(Executability.PARTIALLY_EXECUTABLE,
                Executability.fromJson("Partially Executable"));
        assertEquals(Executability.PARTIALLY_EXECUTABLE_POSSIBLE_OVERREPORTING,
                Executability.fromJson("Partially Executable - Possible Overreporting"));
        assertEquals(Executability.PARTIALLY_EXECUTABLE_POSSIBLE_UNDERREPORTING,
                Executability.fromJson("Partially Executable - Possible Underreporting"));
        assertEquals(Executability.NOT_EXECUTABLE, Executability.fromJson("Not Executable"));
    }


    @Test
    void testFromJson_unknownReturnsNull()
    {
        assertNull(Executability.fromJson("Unknown"));
        assertNull(Executability.fromJson(null));
    }


    @Test
    void testJsonValue_roundTrip()
    {
        for (Executability e : Executability.values())
        {
            assertEquals(e, Executability.fromJson(e.getJsonValue()));
        }
    }


    @Test
    void testPythonValue_matchesPythonReportForm()
    {
        assertEquals("fully executable", Executability.FULLY_EXECUTABLE.getPythonValue());
        assertEquals("partially executable", Executability.PARTIALLY_EXECUTABLE.getPythonValue());
        assertEquals("partially executable - possible overreporting",
                Executability.PARTIALLY_EXECUTABLE_POSSIBLE_OVERREPORTING.getPythonValue());
        assertEquals("partially executable - possible underreporting",
                Executability.PARTIALLY_EXECUTABLE_POSSIBLE_UNDERREPORTING.getPythonValue());
        assertEquals("not executable", Executability.NOT_EXECUTABLE.getPythonValue());
    }


    @Test
    void testPythonValue_neverNullOrEmpty()
    {
        // Every enum constant must carry a non-empty pythonValue — JsonReportWriter
        // would otherwise emit a missing executability field for declared rules.
        for (Executability e : Executability.values())
        {
            assertNotNull(e.getPythonValue(), e.name());
            assertFalse(e.getPythonValue().isEmpty(), e.name());
        }
    }

}
