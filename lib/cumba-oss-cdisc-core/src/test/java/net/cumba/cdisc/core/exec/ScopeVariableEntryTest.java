package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Fix #124 — {@link ScopeVariableEntry} parsing: which {@code Scope.Variables} entries split into a
 * {@code DATASET.VARIABLE} pair and which stay whole. The {@code /…/} carve-out is the load-bearing
 * case: a regular expression contains dots by construction, so splitting one would corrupt every
 * regex entry.
 */
class ScopeVariableEntryTest
{

    // ------------------------------------------------------------------
    // Unqualified entries — the pre-existing corpus shapes
    // ------------------------------------------------------------------

    @Test
    void plainNamesAreUnqualified()
    {
        for (String raw : List.of("AESTDTC", "USUBJID", "TRTxxP", "ANLzzFL", "R2AyLO"))
        {
            ScopeVariableEntry e = ScopeVariableEntry.parse(raw);
            assertFalse(e.isQualified(), raw + " should not be qualified");
            assertNull(e.qualifier(), raw + " qualifier");
            assertEquals(raw, e.variable(), raw + " variable");
            assertEquals(raw, e.raw(), raw + " raw");
        }
    }


    @Test
    void domainPrefixAndGlobEntriesStayUnqualified()
    {
        for (String raw : List.of("--SEQ", "--OCCUR", "*DY", "AESTD?", "--*DT"))
        {
            assertFalse(ScopeVariableEntry.parse(raw).isQualified(), raw + " should stay whole");
        }
    }

    // ------------------------------------------------------------------
    // Qualified entries
    // ------------------------------------------------------------------


    @Test
    void dottedEntrySplitsAtFirstDot()
    {
        ScopeVariableEntry e = ScopeVariableEntry.parse("DM.ARM");
        assertTrue(e.isQualified());
        assertEquals("DM", e.qualifier());
        assertEquals("ARM", e.variable());
        assertEquals("DM.ARM", e.raw(), "raw is preserved verbatim for mismatch messages");
    }


    @Test
    void qualifierMayCarryTheDatasetWildcard()
    {
        ScopeVariableEntry e = ScopeVariableEntry.parse("SUPP--.QVAL");
        assertTrue(e.isQualified());
        assertEquals("SUPP--", e.qualifier());
        assertEquals("QVAL", e.variable());
    }


    @Test
    void variableHalfKeepsItsOwnPatternForms()
    {
        assertEquals("*DTC", ScopeVariableEntry.parse("DM.*DTC").variable());
        assertEquals("TRTxxPN", ScopeVariableEntry.parse("ADSL.TRTxxPN").variable());
        assertEquals("/^A.*$/", ScopeVariableEntry.parse("DM./^A.*$/").variable());
    }


    @Test
    void splitTakesTheFirstDotOnly()
    {
        // A.B.C is rejected by the loader; parse still has to be total and deterministic.
        ScopeVariableEntry e = ScopeVariableEntry.parse("A.B.C");
        assertEquals("A", e.qualifier());
        assertEquals("B.C", e.variable());
    }

    // ------------------------------------------------------------------
    // The /…/ carve-out
    // ------------------------------------------------------------------


    @Test
    void wholeEntryRegexIsNeverSplit()
    {
        for (String raw : List.of("/^DM\\..*/", "/A.B/", "/.*/", "/^(AE|DM)\\.SEQ$/"))
        {
            ScopeVariableEntry e = ScopeVariableEntry.parse(raw);
            assertFalse(e.isQualified(), raw + " is a regex, not a qualified entry");
            assertEquals(raw, e.variable(), raw + " stays whole");
        }
    }


    @Test
    void shortSlashLiteralsAreNotRegexAndSplitNormally()
    {
        // "/" and "//" are below the length-3 threshold, matching ScopeMatcher.scopePattern.
        assertFalse(ScopeVariableEntry.isWholeEntryRegex("/"));
        assertFalse(ScopeVariableEntry.isWholeEntryRegex("//"));
        assertTrue(ScopeVariableEntry.isWholeEntryRegex("/./"));
    }


    @Test
    void isWholeEntryRegexAgreesWithScopeMatcherRegexDetection()
    {
        // Pins the invariant the class javadoc claims: the two must never disagree about which
        // entries carry the /…/ form, or the loader and the matcher would split differently.
        for (String raw : List.of("/^A$/", "/", "//", "/./", "AESTDTC", "DM.ARM", "*DY"))
        {
            boolean viaEntry = ScopeVariableEntry.isWholeEntryRegex(raw);
            boolean viaMatcher = raw.length() > 2 && raw.startsWith("/") && raw.endsWith("/");
            assertEquals(viaMatcher, viaEntry, raw);
            if (viaEntry)
            {
                assertNotNull(ScopeMatcher.scopePattern(raw), raw + " compiles as a regex");
            }
        }
    }

    // ------------------------------------------------------------------
    // Degenerate dot positions — parse stays total, the loader rejects them
    // ------------------------------------------------------------------


    @Test
    void leadingOrTrailingDotDoesNotQualify()
    {
        for (String raw : List.of(".ARM", "DM.", ".", ".."))
        {
            ScopeVariableEntry e = ScopeVariableEntry.parse(raw);
            assertFalse(e.isQualified(), raw + " must not parse as qualified");
            assertEquals(raw, e.variable(), raw + " stays whole");
        }
    }

}
