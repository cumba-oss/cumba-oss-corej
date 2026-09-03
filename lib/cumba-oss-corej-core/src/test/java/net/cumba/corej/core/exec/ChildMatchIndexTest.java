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
