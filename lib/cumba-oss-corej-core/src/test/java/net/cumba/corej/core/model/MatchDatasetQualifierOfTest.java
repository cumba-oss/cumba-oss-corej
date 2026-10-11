package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * {@link MatchDataset#qualifierOf}: a bare key is qualified when it carries exactly one {@code .}
 * with text on BOTH sides. Each refusal below is one edge of that definition.
 */
class MatchDatasetQualifierOfTest
{

    @Test
    void oneDotWithTextOnBothSidesIsQualified()
    {
        assertEquals("DM", MatchDataset.qualifierOf("DM.RPATHCD"));
        assertEquals("D", MatchDataset.qualifierOf("D.X"), "one character either side suffices");
    }


    @Test
    void anythingElseIsUnqualified()
    {
        assertNull(MatchDataset.qualifierOf("USUBJID"), "no dot");
        assertNull(MatchDataset.qualifierOf(".RPATHCD"), "no text before the dot");
        assertNull(MatchDataset.qualifierOf("DM."), "no text after the dot");
        assertNull(MatchDataset.qualifierOf("."), "a dot alone");
        assertNull(MatchDataset.qualifierOf("DM.SUPP.QVAL"), "a second dot");
    }
}
