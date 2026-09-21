package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;
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

    // ------------------------------------------------------------------
    // Type suffix (PLAN-variable-type-requirements, rulings D5 / D7)
    // ------------------------------------------------------------------


    /**
     * ⭐ Ruling D5's control: an entry with no tag parses byte-for-byte as it did before the feature
     * existed. Every message the matcher builds comes off {@code variable()} / {@code raw()}, so
     * this is what makes "the 2 163 authored entries are unchanged" true rather than hoped.
     */
    @Test
    void anEntryWithoutATagIsUnchangedAndCarriesNoKind()
    {
        for (String raw : List.of("AESEQ", "--DTC", "TRTxxP", "DM.ARM", "/^AE.*$/", "AE*"))
        {
            ScopeVariableEntry e = ScopeVariableEntry.parse(raw);
            assertNull(e.requiredKind(), raw + " must demand no type");
            assertEquals(raw, e.raw(), raw + " raw");
            assertFalse(ScopeVariableEntry.hasTypeSuffix(raw), raw + " has no tag");
            assertNull(ScopeVariableEntry.malformedTypeSuffix(raw), raw + " is well formed");
        }
    }


    @Test
    void theFourTagsAreAcceptedCaseInsensitively()
    {
        for (String raw : List.of("AESEQ:N", "AESEQ:n", "AESEQ:Num", "AESEQ:NUM", "AESEQ:num"))
        {
            ScopeVariableEntry e = ScopeVariableEntry.parse(raw);
            assertEquals(ColumnTypeGate.Kind.NUMERIC, e.requiredKind(), raw);
            assertEquals("AESEQ", e.variable(), raw + " variable half");
            assertEquals(raw, e.raw(), raw + " raw is preserved verbatim");
        }
        for (String raw : List.of("AETERM:C", "AETERM:c", "AETERM:Char", "AETERM:CHAR"))
        {
            ScopeVariableEntry e = ScopeVariableEntry.parse(raw);
            assertEquals(ColumnTypeGate.Kind.CHARACTER, e.requiredKind(), raw);
            assertEquals("AETERM", e.variable(), raw + " variable half");
        }
    }


    /**
     * ⭐ D7 demands the four tags be <b>indistinguishable after the parse</b>, not merely both
     * accepted: nothing downstream may be able to tell which spelling the author typed. Only
     * {@code raw()} — deliberately — differs.
     */
    @Test
    void shortAndLongTagsAreIndistinguishableApartFromRaw()
    {
        ScopeVariableEntry shortForm = ScopeVariableEntry.parse("AESEQ:N");
        ScopeVariableEntry longForm = ScopeVariableEntry.parse("AESEQ:Num");
        assertEquals(shortForm.requiredKind(), longForm.requiredKind());
        assertEquals(shortForm.qualifier(), longForm.qualifier());
        assertEquals(shortForm.variable(), longForm.variable());
    }


    /**
     * ⛔ Trap 1: {@code isWholeEntryRegex} tests the LAST character, so the tag has to come off
     * before the regex test — otherwise this entry stops being a regex and is split on its first
     * dot, and the resulting pattern matches no column at all, silently.
     */
    @Test
    void aTaggedRegexStaysARegex()
    {
        ScopeVariableEntry e = ScopeVariableEntry.parse("/^AE.*$/:N");
        assertFalse(e.isQualified(), "a tagged regex must not be split on its dot");
        assertEquals("/^AE.*$/", e.variable());
        assertEquals(ColumnTypeGate.Kind.NUMERIC, e.requiredKind());
        assertTrue(ScopeVariableEntry.isWholeEntryRegex(e.variable()));
    }


    /**
     * The mirror control: a regex whose own syntax contains a colon ({@code (?:…)}) carries no tag
     * and is not malformed. Without the regex carve-out in
     * {@link ScopeVariableEntry#malformedTypeSuffix} this legal entry would be rejected at load.
     */
    @Test
    void aRegexContainingAColonIsNeitherTaggedNorMalformed()
    {
        String raw = "/^(?:AE|CM)TERM$/";
        ScopeVariableEntry e = ScopeVariableEntry.parse(raw);
        assertNull(e.requiredKind(), "the colon is regex syntax, not a tag");
        assertEquals(raw, e.variable());
        assertNull(ScopeVariableEntry.malformedTypeSuffix(raw));
    }


    @Test
    void aQualifiedEntryKeepsItsQualifierAndTakesTheTag()
    {
        ScopeVariableEntry e = ScopeVariableEntry.parse("DM.ARM:Char");
        assertTrue(e.isQualified());
        assertEquals("DM", e.qualifier());
        assertEquals("ARM", e.variable());
        assertEquals(ColumnTypeGate.Kind.CHARACTER, e.requiredKind());
    }


    /**
     * ⚠ The near-misses ruling D7 makes likely ({@code :Numeric}, {@code :Character}) are rejected,
     * and so is a stray colon. The matcher must never silently read {@code X:Z} as a column named
     * {@code X} — these are the loader's to reject (gate R9), which is why
     * {@link ScopeVariableEntry#parse} leaves them whole and reports them here instead.
     */
    @Test
    void malformedSuffixesAreReportedAndNotSilentlyStripped()
    {
        for (String raw : List.of("AESEQ:", "AESEQ:Z", "AESEQ:NN", "AESEQ:Numeric",
                "AETERM:Character", "A:B:C"))
        {
            assertNotNull(ScopeVariableEntry.malformedTypeSuffix(raw), raw + " must be reported");
        }
        ScopeVariableEntry e = ScopeVariableEntry.parse("AESEQ:Z");
        assertNull(e.requiredKind(), "an unrecognised tag demands nothing");
        assertEquals("AESEQ:Z", e.variable(), "and is NOT silently read as the column AESEQ");
    }


    @Test
    void surroundingWhitespaceInTheTagIsTolerated()
    {
        assertEquals(ColumnTypeGate.Kind.NUMERIC,
                ScopeVariableEntry.parse("AESEQ: N").requiredKind());
        assertEquals(ColumnTypeGate.Kind.CHARACTER,
                ScopeVariableEntry.parse("AETERM:Char ").requiredKind());
    }

}
