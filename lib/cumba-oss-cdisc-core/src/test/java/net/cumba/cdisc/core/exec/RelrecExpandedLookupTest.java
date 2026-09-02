package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link RelrecExpandedLookup} — the per-expanded-row RELREC join lookup.
 */
class RelrecExpandedLookupTest
{

    /**
     * Two target domains (AE ordinal 0, CM ordinal 1) plus two defensive ordinals (-1 and an
     * out-of-range 5) so every branch of {@link RelrecExpandedLookup#lookup} is exercised.
     */
    private static RelrecExpandedLookup fixture()
    {
        IDataTable ae = MockTable.of().col("AETERM", "HEADACHE", "NAUSEA")
                .col("AEDECOD", "HA", null).name("AE").build();
        IDataTable cm = MockTable.of().col("CMTERM", "ASPIRIN").col("CMTRT", "ASA").name("CM")
                .build();
        // expanded rows: 0->AE/0, 1->AE/1, 2->CM/0, 3->ord -1 (none), 4->ord 5 (out of range)
        int[] ord =
        {
                0, 0, 1, -1, 5
        };
        long[] tgtRow =
        {
                0, 1, 0, 0, 0
        };
        return new RelrecExpandedLookup(List.of(ae, cm), ord, tgtRow);
    }


    @Test
    void literalColumnLookup()
    {
        RelrecExpandedLookup lk = fixture();
        assertEquals("HEADACHE", lk.lookup(null, 0, "AETERM"));
        assertEquals("NAUSEA", lk.lookup(null, 1, "AETERM"));
        assertEquals("ASPIRIN", lk.lookup(null, 2, "CMTERM"));
    }


    @Test
    void wildcardResolvesPerTargetDomain()
    {
        RelrecExpandedLookup lk = fixture();
        // "**TERM" -> domainPrefix(AE)="AE" -> "AETERM" for ordinal 0
        assertEquals("HEADACHE", lk.lookup(null, 0, "**TERM"));
        // same suffix, different ordinal -> domainPrefix(CM)="CM" -> "CMTERM"
        assertEquals("ASPIRIN", lk.lookup(null, 2, "**TERM"));
        // second call hits the per-ordinal column-index cache
        assertEquals("NAUSEA", lk.lookup(null, 1, "**TERM"));
    }


    @Test
    void missingCellAndMissingColumnReturnNull()
    {
        RelrecExpandedLookup lk = fixture();
        // row 1 AEDECOD is blank -> missing-or-invalid -> null. ⚠ Resolving a blank CHARACTER
        // cell to "" instead is the blindness step, blocked on the date_* defect recorded in
        // ScalarSemantics.resolvedString.
        assertNull(lk.lookup(null, 1, "AEDECOD"));
        // column not present on the target -> colIdx < 0 -> null (cached)
        assertNull(lk.lookup(null, 0, "NOSUCH"));
        assertNull(lk.lookup(null, 0, "NOSUCH"));
    }


    @Test
    void outOfRangeNegativeNullColumnAndBadOrdinalReturnNull()
    {
        RelrecExpandedLookup lk = fixture();
        assertNull(lk.lookup(null, -1, "AETERM"));
        assertNull(lk.lookup(null, 99, "AETERM"));
        assertNull(lk.lookup(null, 0, null));
        assertNull(lk.lookup(null, 3, "AETERM")); // ordinal -1
        assertNull(lk.lookup(null, 4, "AETERM")); // ordinal 5 >= size
    }


    @Test
    void hasColumnDistinguishesAbsentFromMissing()
    {
        RelrecExpandedLookup lk = fixture();
        // present columns (literal + wildcard-resolved) -> true
        assertTrue(lk.hasColumn(null, 0, "AETERM"));
        assertTrue(lk.hasColumn(null, 0, "**TERM")); // -> AETERM
        assertTrue(lk.hasColumn(null, 2, "**TRT")); // CM -> CMTRT
        // absent column (wildcard resolves to a non-existent parent column) -> false
        assertFalse(lk.hasColumn(null, 0, "**TRT")); // AE has no AETRT
        // defensive: out-of-range / bad ordinal / null -> false
        assertFalse(lk.hasColumn(null, -1, "AETERM"));
        assertFalse(lk.hasColumn(null, 99, "AETERM"));
        assertFalse(lk.hasColumn(null, 3, "AETERM")); // ordinal -1
        assertFalse(lk.hasColumn(null, 4, "AETERM")); // ordinal 5 >= size
        assertFalse(lk.hasColumn(null, 0, null));
    }


    @Test
    void datasetNameIsRelrec()
    {
        assertEquals("RELREC", fixture().getDatasetName());
    }


    @Test
    void lookupAllReturnsSingletonOrEmpty()
    {
        RelrecExpandedLookup lk = fixture();
        assertEquals(List.of("HEADACHE"), lk.lookupAll(null, 0, "AETERM"));
        assertTrue(lk.lookupAll(null, 1, "AEDECOD").isEmpty());
        assertTrue(lk.lookupAll(null, 0, "NOSUCH").isEmpty());
    }
}
