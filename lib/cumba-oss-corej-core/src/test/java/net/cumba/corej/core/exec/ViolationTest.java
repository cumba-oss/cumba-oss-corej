package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import net.cumba.datatable.report.Severity;
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


    @Test
    void compatibilityConstructorsListNothingByName()
    {
        // PLAN-finding-values-record-level-only V8 (b): the name-only list is never null, and
        // the sites that go through the compatibility forms (sentinels, record paths, Group)
        // list nothing — as the shared empty constant.
        assertSame(List.of(), new Violation(0, Map.of()).getNamedColumns());
        assertSame(List.of(),
                new Violation(0, Map.of(), "S1", "1", Map.of(), null, null).getNamedColumns());
        Violation grouped = new Violation(0, Map.of(), "S1", "1", Map.of(), null, null,
                Map.of("USUBJID", "S1"));
        assertSame(List.of(), grouped.getNamedColumns());
        assertTrue(grouped.isGroupFinding());
        assertFalse(new Violation(0, Map.of()).isGroupFinding(),
                "no group key stamped: not a Group finding");
        assertTrue(
                new Violation(0, Map.of(), null, null, Map.of(), null, null, Map.of())
                        .isGroupFinding(),
                "an empty key (the whole dataset one group) is a Group finding");
    }


    @Test
    void withLevelCopiesEverySiblingField()
    {
        // §7 M3: the level merge re-stamps the claiming level and must lose NOTHING — this is
        // where groupKey used to fall off (a 7-arg rebuild).
        Violation.Unit unit = new Violation.Unit.Group(List.of("S1"));
        Violation v = new Violation(7, Map.of("AETERM", "H"), "S1", "2", Map.of("AESEQ", "2"),
                Severity.WARNING, unit, Map.of("USUBJID", "S1"), List.of("AESEV"));

        Violation stamped = v.withLevel(Severity.ERROR);

        assertEquals(Severity.ERROR, stamped.getLevel());
        assertEquals(7, stamped.getRow());
        assertEquals(Map.of("AETERM", "H"), stamped.getValues());
        assertEquals("S1", stamped.getUsubjid());
        assertEquals("2", stamped.getSeq());
        assertEquals(Map.of("AESEQ", "2"), stamped.getKeys());
        assertSame(unit, stamped.getUnit());
        assertEquals(Map.of("USUBJID", "S1"), stamped.getGroupKey());
        assertEquals(List.of("AESEV"), stamped.getNamedColumns());
        assertSame(v, v.withLevel(Severity.WARNING), "an unchanged level returns this");
    }

}
