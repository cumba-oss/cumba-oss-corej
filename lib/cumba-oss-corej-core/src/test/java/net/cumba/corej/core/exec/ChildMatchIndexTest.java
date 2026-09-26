package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.Test;

/**
 * Plan J5 — {@link ChildMatchIndex#normalizeJoinToken} coercion of an IDVAR-join token to the
 * parent {@code IDVAR}-column type. Stripping is always applied; numeric canonicalization is gated
 * on the parent being numeric, so the parent (numeric) and child {@code IDVARVAL} (string) join
 * keys, and the value-reference comparison, agree exactly as Python's {@code dataset_preprocessor}
 * does.
 */
class ChildMatchIndexTest
{

    @Test
    void nullPassesThrough()
    {
        assertNull(ChildMatchIndex.normalizeJoinToken(null, true));
        assertNull(ChildMatchIndex.normalizeJoinToken(null, false));
    }


    @Test
    void numericParentStripsAndCanonicalizes()
    {
        // SAS padding stripped; integral float rendering collapses to the integer form.
        assertEquals("1", ChildMatchIndex.normalizeJoinToken("       1", true));
        assertEquals("1", ChildMatchIndex.normalizeJoinToken("1", true));
        assertEquals("1", ChildMatchIndex.normalizeJoinToken("1.0", true));
        assertEquals("1", ChildMatchIndex.normalizeJoinToken("01", true));
        assertEquals("1.5", ChildMatchIndex.normalizeJoinToken("1.5", true));
        assertEquals("1", ChildMatchIndex.normalizeJoinToken("1  ", true));
    }


    @Test
    void numericParentTokenIsThePlainCellText()
    {
        // B-MED-1 (PLAN-numeric-cleaning-and-key-text review): the coerced token is written into
        // the merged IDVARVAL and compared as TEXT against the parent cell
        // (`str(IDVARVAL) != str(colref(IDVAR))`, CDISC-CG0371 / FDA-SD0077 / PMDA-SD0077), so it
        // must spell the way DataValueDouble.getValueAsString spells: plain, never scientific.
        assertEquals("12345678.5", ChildMatchIndex.normalizeJoinToken("12345678.5", true),
                "Double.toString would say \"1.23456785E7\" and the rule fires falsely");
        assertEquals("0.0005", ChildMatchIndex.normalizeJoinToken("0.0005", true),
                "Double.toString would say \"5.0E-4\"");
        assertEquals("0.0005", ChildMatchIndex.normalizeJoinToken("5.0E-4", true),
                "symmetric: a scientific token folds onto the same plain text");
        assertEquals("100000000000000000000", ChildMatchIndex.normalizeJoinToken("1e20", true),
                "no long saturation");
    }


    @Test
    void numericParentKeepsEveryDigitBeyond2p53()
    {
        // PLAN-relrec-idvar-key-precision T1-1 (a), RRK E2: the token is canonicalised as an
        // exact decimal, never through a double — 9007199254740993 and 9007199254740992 are one
        // double but two keys, and a LONG parent cell spells all 16 digits (Long.toString).
        assertEquals("9007199254740993",
                ChildMatchIndex.normalizeJoinToken("9007199254740993", true));
        assertEquals("9007199254740992",
                ChildMatchIndex.normalizeJoinToken("9007199254740992", true));
        assertEquals("9007199254740993",
                ChildMatchIndex.normalizeJoinToken(" 9007199254740993.00 ", true),
                "trailing zeros and padding fold; the digits do not");
        assertEquals("100000000000000000001",
                ChildMatchIndex.normalizeJoinToken("100000000000000000001", true),
                "beyond long range too: no saturation, no double");
    }


    @Test
    void numericParentNonNumericTokenIsStrippedOnly()
    {
        assertEquals("ABC", ChildMatchIndex.normalizeJoinToken("  ABC  ", true));
    }


    @Test
    void stringParentStripsButDoesNotCanonicalize()
    {
        // Strip always (padding is non-semantic) ...
        assertEquals("1", ChildMatchIndex.normalizeJoinToken("       1", false));
        assertEquals("ABC", ChildMatchIndex.normalizeJoinToken("  ABC  ", false));
        // ... but a string parent keeps "01" distinct from "1" (matches Python's string coercion).
        assertEquals("01", ChildMatchIndex.normalizeJoinToken("01", false));
        assertEquals("1.0", ChildMatchIndex.normalizeJoinToken("1.0", false));
    }
}
