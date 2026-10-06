package net.cumba.corej.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.authoring.RuleDiagnostic.TextRange;
import net.cumba.corej.authoring.RuleDiagnostic.YamlPath;
import org.junit.jupiter.api.Test;

class YamlLocationsTest
{

    private static final String DOC = """
            Core:
              Id: "CDISC-XX0001"
              Status: "Published"
            Scope:
              Domains:
                Include:
                - "AE"
                - "CM"
            Check:
              expression: 'x'
            """;

    @Test
    void mappingKeysAreLocatedAtTheirKeyToken()
    {
        YamlLocations idx = YamlLocations.index(DOC);
        assertEquals(new TextRange(1, 1, 1, 4), idx.rangeOf(YamlPath.of("Core")));
        assertEquals(new TextRange(2, 3, 2, 4), idx.rangeOf(YamlPath.of("Core/Id")));
        assertEquals(new TextRange(3, 3, 3, 8), idx.rangeOf(YamlPath.of("Core/Status")));
        assertEquals(new TextRange(5, 3, 5, 9), idx.rangeOf(YamlPath.of("Scope/Domains")));
        assertEquals(new TextRange(9, 1, 9, 5), idx.rangeOf(YamlPath.of("Check")));
        assertEquals(new TextRange(10, 3, 10, 12), idx.rangeOf(YamlPath.of("Check/expression")));
    }


    @Test
    void sequenceElementsAreLocatedByIndex()
    {
        YamlLocations idx = YamlLocations.index(DOC);
        TextRange ae = idx.rangeOf(YamlPath.of("Scope/Domains/Include/0"));
        TextRange cm = idx.rangeOf(YamlPath.of("Scope/Domains/Include/1"));
        assertNotNull(ae);
        assertNotNull(cm);
        assertEquals(7, ae.startLine());
        assertEquals(8, cm.startLine());
    }


    @Test
    void anUnknownPathHasNoRange()
    {
        assertNull(YamlLocations.index(DOC).rangeOf(YamlPath.of("Nope/Nothing")));
    }


    @Test
    void aBrokenDocumentIndexesItsPrefix()
    {
        YamlLocations idx = YamlLocations.index("Core:\n  Id: x\nScope: [unterminated\n");
        assertNotNull(idx.rangeOf(YamlPath.of("Core/Id")));
        assertTrue(idx.size() >= 2);
        assertFalse(idx.isComplete());
        assertTrue(YamlLocations.index(DOC).isComplete());
    }


    private static @org.jspecify.annotations.Nullable TextRange at(String aDoc, int aOffset)
    {
        YamlLocations.ValueLocation v = YamlLocations.index(aDoc)
                .valueOf(YamlPath.of("Check/expression"));
        assertNotNull(v, "the expression value is indexed");
        return v.rangeAt(aOffset);
    }


    /**
     * Review 8a-A F1, measured on CDISC-CG0270: a {@code >-} block scalar (3 480 of the corpus's ~4
     * 540 expressions) was located on its {@code >} indicator, a line above the content.
     */
    @Test
    void aFoldedBlockScalarIsLocatedOnItsContentLine()
    {
        String doc = """
                Check:
                  expression: >-
                    TSPARMCD == == "AGEMAX"
                """;
        assertEquals(TextRange.at(3, 17, 1), at(doc, 12));
        assertEquals(TextRange.at(3, 5, 1), at(doc, 0));
    }


    /** A folded block over several lines: each line break folded to a space, offsets carried. */
    @Test
    void aMultiLineFoldedBlockIsLocatedLineByLine()
    {
        String doc = """
                Check:
                  expression: >-
                    A == "x" and
                    B == == "y"
                """;
        assertEquals(TextRange.at(3, 5, 1), at(doc, 0));
        assertEquals(TextRange.at(4, 10, 1), at(doc, 18));
    }


    /** A literal block keeps its line breaks; offsets still land on their characters. */
    @Test
    void aLiteralBlockIsLocatedLineByLine()
    {
        String doc = """
                Check:
                  expression: |
                    A ==
                    B == == y
                """;
        // "A ==\nB == == y\n": offset 10 is the second "==" of the second line
        assertEquals(TextRange.at(4, 10, 1), at(doc, 10));
    }


    /** Review 8a-A F1: {@code \"} shifted every later offset (777 corpus expressions). */
    @Test
    void aDoubleQuotedScalarWithEscapesIsLocatedPastThem()
    {
        String doc = """
                Check:
                  expression: "X == \\"A\\" and == Y"
                """;
        assertEquals(TextRange.at(2, 31, 1), at(doc, 13));
        assertEquals(TextRange.at(2, 16, 1), at(doc, 0));
    }


    /** Review 8a-A F1: a {@code \} line continuation (303 corpus expressions). */
    @Test
    void aDoubleQuotedContinuationIsLocatedOnTheContinuedLine()
    {
        String doc = """
                Check:
                  expression: "date_diff(A,\\
                    \\ B) == =="
                """;
        assertEquals(TextRange.at(3, 13, 1), at(doc, 19));
        assertEquals(TextRange.at(3, 7, 1), at(doc, 13));
    }


    /** A single-quoted scalar's {@code ''} is one character of the value. */
    @Test
    void aSingleQuotedScalarWithADoubledQuoteIsLocatedPastIt()
    {
        String doc = """
                Check:
                  expression: 'X == ''A'' and == Y'
                """;
        assertEquals(TextRange.at(2, 31, 1), at(doc, 13));
    }


    /** A plain scalar, on one line and folded over two. */
    @Test
    void aPlainScalarIsLocated()
    {
        assertEquals(TextRange.at(2, 20, 1), at("Check:\n  expression: X == == Y\n", 5));
        assertEquals(TextRange.at(3, 8, 1), at("Check:\n  expression: X ==\n    Y == == Z\n", 8));
        // a tag (or an anchor) before the scalar is not part of its text
        assertEquals(TextRange.at(2, 26, 1), at("Check:\n  expression: !!str X == == Y\n", 5));
    }


    /**
     * The verify-or-null guard: where the source cannot be mapped character for character (an alias
     * carries another node's text), no range rather than a wrong one — the caller falls back to the
     * key.
     */
    @Test
    void aValueTheIndexCannotMapHasNoRange()
    {
        String doc = """
                Shared: &e 'X == == Y'
                Check:
                  expression: *e
                """;
        YamlLocations.ValueLocation alias = YamlLocations.index(doc)
                .valueOf(YamlPath.of("Check/expression"));
        assertTrue(alias == null || alias.rangeAt(5) == null, String.valueOf(alias));
        assertNull(at("Check:\n  expression: X == == Y\n", 99), "past the end");
    }


    @Test
    void pathsRoundTripThroughTheirSpellings()
    {
        assertEquals("/Core/Id", YamlPath.of("Core/Id").toString());
        assertEquals("/Core/Id", YamlPath.of("/Core/Id").toString());
        assertEquals("/Outcome/Output_Variables",
                YamlPath.ofDotted("Outcome.Output_Variables").toString());
        assertEquals("/", YamlPath.of("").toString());
        assertEquals("/A/B", YamlPath.of("A").child("B").toString());
    }
}
