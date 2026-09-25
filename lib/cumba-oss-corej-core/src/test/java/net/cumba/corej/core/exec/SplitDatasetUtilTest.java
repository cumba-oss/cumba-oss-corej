package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

/**
 * Covers {@link SplitDatasetUtil} — the shared helper for unsplit-name canonicalization (Fix #1
 * dedup, Fix #12 letter-suffix recognition); a name is a split exactly when the result is shorter.
 */
class SplitDatasetUtilTest
{

    // ---- Digit-suffix splits ----

    @Test
    void digitSuffix_regular()
    {
        assertEquals("LB", SplitDatasetUtil.unsplitName("LB1"));
        assertEquals("AE", SplitDatasetUtil.unsplitName("AE2"));
        assertEquals("LB", SplitDatasetUtil.unsplitName("LB10"));
    }


    @Test
    void digitSuffix_supp()
    {
        assertEquals("SUPPDM", SplitDatasetUtil.unsplitName("SUPPDM1"));
    }


    @Test
    void digitSuffix_ap()
    {
        assertEquals("APMH", SplitDatasetUtil.unsplitName("APMH1"));
    }

    // ---- Letter-suffix SUPP/AP splits (Fix #12) ----


    @Test
    void letterSuffix_supp()
    {
        assertEquals("SUPPLBH", SplitDatasetUtil.unsplitName("SUPPLBHM"));
        assertEquals("SUPPFAC", SplitDatasetUtil.unsplitName("SUPPFACM"));
        assertEquals("SUPPAE", SplitDatasetUtil.unsplitName("SUPPAEX"));
    }


    @Test
    void letterSuffix_ap()
    {
        assertEquals("APFA", SplitDatasetUtil.unsplitName("APFAC"));
        assertEquals("APFAC", SplitDatasetUtil.unsplitName("APFACM"));
    }

    // ---- Non-splits ----


    @Test
    void nonSplits_plainDomains()
    {
        assertEquals("AE", SplitDatasetUtil.unsplitName("AE"));
        assertEquals("SUPPLB", SplitDatasetUtil.unsplitName("SUPPLB"));
        assertEquals("APFA", SplitDatasetUtil.unsplitName("APFA"));
    }


    @Test
    void nullAndEdgeCases()
    {
        // Names with leading digits or mixed case are not splits
        assertEquals(null, SplitDatasetUtil.unsplitName(null));
        assertEquals("", SplitDatasetUtil.unsplitName(""));
    }
}
