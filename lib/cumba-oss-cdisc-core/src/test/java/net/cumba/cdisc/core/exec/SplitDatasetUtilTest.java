package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link SplitDatasetUtil} — the shared helper for split-dataset detection and unsplit-name
 * canonicalization (Fix #1 dedup, Fix #12 letter-suffix recognition).
 */
class SplitDatasetUtilTest
{

    // ---- Digit-suffix splits ----

    @Test
    void digitSuffix_regular()
    {
        assertTrue(SplitDatasetUtil.isSplitDataset("LB1"));
        assertTrue(SplitDatasetUtil.isSplitDataset("AE2"));
        assertTrue(SplitDatasetUtil.isSplitDataset("LB10"));
        assertEquals("LB", SplitDatasetUtil.unsplitName("LB1"));
        assertEquals("AE", SplitDatasetUtil.unsplitName("AE2"));
        assertEquals("LB", SplitDatasetUtil.unsplitName("LB10"));
    }


    @Test
    void digitSuffix_supp()
    {
        assertTrue(SplitDatasetUtil.isSplitDataset("SUPPDM1"));
        assertEquals("SUPPDM", SplitDatasetUtil.unsplitName("SUPPDM1"));
    }


    @Test
    void digitSuffix_ap()
    {
        assertTrue(SplitDatasetUtil.isSplitDataset("APMH1"));
        assertEquals("APMH", SplitDatasetUtil.unsplitName("APMH1"));
    }

    // ---- Letter-suffix SUPP/AP splits (Fix #12) ----


    @Test
    void letterSuffix_supp()
    {
        assertTrue(SplitDatasetUtil.isSplitDataset("SUPPLBHM"));
        assertTrue(SplitDatasetUtil.isSplitDataset("SUPPFACM"));
        assertTrue(SplitDatasetUtil.isSplitDataset("SUPPAEX"));
        assertEquals("SUPPLBH", SplitDatasetUtil.unsplitName("SUPPLBHM"));
        assertEquals("SUPPFAC", SplitDatasetUtil.unsplitName("SUPPFACM"));
        assertEquals("SUPPAE", SplitDatasetUtil.unsplitName("SUPPAEX"));
    }


    @Test
    void letterSuffix_ap()
    {
        assertTrue(SplitDatasetUtil.isSplitDataset("APFAC"));
        assertTrue(SplitDatasetUtil.isSplitDataset("APFACM"));
        assertEquals("APFA", SplitDatasetUtil.unsplitName("APFAC"));
        assertEquals("APFAC", SplitDatasetUtil.unsplitName("APFACM"));
    }

    // ---- Non-splits ----


    @Test
    void nonSplits_plainDomains()
    {
        assertFalse(SplitDatasetUtil.isSplitDataset("AE"));
        assertFalse(SplitDatasetUtil.isSplitDataset("DM"));
        assertFalse(SplitDatasetUtil.isSplitDataset("SUPPLB")); // 6 chars — base SUPP domain
        assertFalse(SplitDatasetUtil.isSplitDataset("APFA")); // 4 chars — base AP domain
        assertEquals("AE", SplitDatasetUtil.unsplitName("AE"));
        assertEquals("SUPPLB", SplitDatasetUtil.unsplitName("SUPPLB"));
        assertEquals("APFA", SplitDatasetUtil.unsplitName("APFA"));
    }


    @Test
    void nullAndEdgeCases()
    {
        assertFalse(SplitDatasetUtil.isSplitDataset(null));
        assertFalse(SplitDatasetUtil.isSplitDataset(""));
        assertFalse(SplitDatasetUtil.isSplitDataset("A"));
        assertFalse(SplitDatasetUtil.isSplitDataset("AB")); // 2 chars — not a split
        // Names with leading digits or mixed case are not splits
        assertFalse(SplitDatasetUtil.isSplitDataset("1LB"));
        assertFalse(SplitDatasetUtil.isSplitDataset("Lb1"));
        assertEquals(null, SplitDatasetUtil.unsplitName(null));
        assertEquals("", SplitDatasetUtil.unsplitName(""));
    }
}
