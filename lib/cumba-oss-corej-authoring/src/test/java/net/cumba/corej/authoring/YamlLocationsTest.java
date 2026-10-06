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
