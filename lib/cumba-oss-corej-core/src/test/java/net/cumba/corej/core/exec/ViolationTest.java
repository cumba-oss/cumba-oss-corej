package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ViolationTest
{

    @Test
    void testRowNumber_oneBasedOffset()
    {
        Violation v = new Violation(0, Map.of());
        assertEquals(0, v.getRow());
        assertEquals(1, v.getRowNumber());
    }


    @Test
    void testRowNumber_otherRows()
    {
        Violation v = new Violation(99, Map.of("A", "1"));
        assertEquals(99, v.getRow());
        assertEquals(100, v.getRowNumber());
    }


    @Test
    void testValues()
    {
        Map<String, String> vals = Map.of("SEX", "M", "AGE", "25");
        Violation v = new Violation(5, vals);
        assertEquals(vals, v.getValues());
    }

}
