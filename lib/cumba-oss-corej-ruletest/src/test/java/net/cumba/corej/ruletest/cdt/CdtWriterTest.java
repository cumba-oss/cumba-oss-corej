package net.cumba.corej.ruletest.cdt;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import net.cumba.datatable.impl.support.OverlayDataTable;
import org.junit.jupiter.api.Test;

/**
 * Round-trip test: load → write → load again should yield identical content (up to minor
 * formatting).
 */
class CdtWriterTest
{

    private static final String SIMPLE = """
            # Round-trip test
            dataset ADSL

            col STUDYID type=Char label="Study Identifier"
            col USUBJID type=Char length=20
            col AGE     type=Num
            ---
            S1 | 01-001 | 42
            S1 | 01-002 | 37
            S1 | 01-003 |
            """;

    @Test
    void roundTripPreservesStructureAndData()
    {
        OverlayDataTable table = CdtLoader.parse(SIMPLE, "test");
        assertNotNull(table);
        assertEquals(3L, table.getRowCount());
        assertEquals(3, table.getMetaData().getColumnCount());

        String written = CdtWriter.toString(table);
        OverlayDataTable reloaded = CdtLoader.parse(written, "roundtrip");
        assertEquals(table.getRowCount(), reloaded.getRowCount());
        assertEquals(table.getMetaData().getColumnCount(), reloaded.getMetaData().getColumnCount());
        for (long r = 0; r < table.getRowCount(); r++)
        {
            for (int c = 0; c < table.getMetaData().getColumnCount(); c++)
            {
                Object a = table.getValue(r, c);
                Object b = reloaded.getValue(r, c);
                assertEquals(String.valueOf(a), String.valueOf(b), "row " + r + " col " + c);
            }
        }
    }


    @Test
    void headerEmittedWithDatasetName()
    {
        OverlayDataTable table = CdtLoader.parse(SIMPLE, "test");
        String out = CdtWriter.toString(table);
        assertEquals(true, out.startsWith("dataset ADSL"));
    }
}
