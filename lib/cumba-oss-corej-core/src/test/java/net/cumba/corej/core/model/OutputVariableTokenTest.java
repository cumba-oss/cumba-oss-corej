package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

/**
 * The §5.1 token table of {@code PLAN-authoring-grammar-unique-set-and-output-exclusion} (E-1,
 * {@code Fix #354}), row by row. The {@code --X} row is the load-bearing negative: 1 437 corpus
 * entries begin with {@code --} and every one of them must stay an include.
 */
class OutputVariableTokenTest
{

    @Test
    void plainNameIsAnInclude()
    {
        assertFalse(OutputVariableToken.isExclusion("USUBJID"));
        assertEquals("USUBJID", OutputVariableToken.name("USUBJID"));
        assertNull(OutputVariableToken.malformed("USUBJID"));
    }


    @Test
    void domainWildcardStaysAnInclude()
    {
        // The single most important negative assertion of the phase: `--` never means exclusion.
        assertFalse(OutputVariableToken.isExclusion("--SEQ"));
        assertEquals("--SEQ", OutputVariableToken.name("--SEQ"));
        assertNull(OutputVariableToken.malformed("--SEQ"));
        assertEquals(List.of("--DTC", "--SEQ"),
                OutputVariableToken.includes(List.of("--DTC", "--SEQ")));
        assertEquals(Set.of(), OutputVariableToken.exclusions(List.of("--DTC", "--SEQ")));
    }


    @Test
    void bangNameIsAnExclusionOfTheVerbatimRemainder()
    {
        assertTrue(OutputVariableToken.isExclusion("!AETERM"));
        assertEquals("AETERM", OutputVariableToken.name("!AETERM"));
        assertNull(OutputVariableToken.malformed("!AETERM"));
    }


    @Test
    void bangWildcardExcludesTheWildcardVariableUntouched()
    {
        assertTrue(OutputVariableToken.isExclusion("!--DTC"));
        assertEquals("--DTC", OutputVariableToken.name("!--DTC"));
        assertNull(OutputVariableToken.malformed("!--DTC"));
    }


    @Test
    void bangOperationIdAndDottedNameExclude()
    {
        assertEquals("$ablfl_y_count", OutputVariableToken.name("!$ablfl_y_count"));
        assertEquals("DM.USUBJID", OutputVariableToken.name("!DM.USUBJID"));
        assertNull(OutputVariableToken.malformed("!$ablfl_y_count"));
        assertNull(OutputVariableToken.malformed("!DM.USUBJID"));
    }


    @Test
    void bareBangIsMalformed()
    {
        assertTrue(OutputVariableToken.isExclusion("!"));
        String why = OutputVariableToken.malformed("!");
        assertNotNull(why);
        assertTrue(why.contains("bare"), why);
        assertEquals("", OutputVariableToken.name("!"));
        // and it excludes nothing
        assertEquals(Set.of(), OutputVariableToken.exclusions(List.of("!")));
    }


    @Test
    void stackedBangIsMalformed()
    {
        String why = OutputVariableToken.malformed("!!X");
        assertNotNull(why);
        assertTrue(why.contains("stacks"), why);
    }


    @Test
    void nullAndEmptyAreNeitherExclusionNorMalformed()
    {
        assertFalse(OutputVariableToken.isExclusion(null));
        assertFalse(OutputVariableToken.isExclusion(""));
        assertNull(OutputVariableToken.malformed(null));
        assertNull(OutputVariableToken.malformed(""));
        assertEquals(List.of(), OutputVariableToken.includes(null));
        assertEquals(List.of(), OutputVariableToken.includes(List.of()));
        assertEquals(Set.of(), OutputVariableToken.exclusions(null));
        assertEquals(List.of(), OutputVariableToken.applyExclusions(null));
        assertEquals(List.of(), OutputVariableToken.applyExclusions(List.of()));
    }


    /**
     * {@code Fix #356} — {@code mapName} is the ONE way an {@code Output_Variables} rewriter may
     * touch an entry: the marker is detached, the function sees only the name, and the marker is
     * re-attached. The wildcard resolver below is the exact mapper the per-domain expansion uses.
     */
    @Test
    void mapNameResolvesInsideTheTokenAndReattachesTheMarker()
    {
        java.util.function.UnaryOperator<String> resolve = name -> name.startsWith("--")
                ? "LB" + name.substring(2)
                : name;
        assertEquals("LBSTRESC", OutputVariableToken.mapName("--STRESC", resolve));
        assertEquals("!LBSTRESC", OutputVariableToken.mapName("!--STRESC", resolve));
        // a non-wildcard entry is untouched on both sides of the marker
        assertEquals("USUBJID", OutputVariableToken.mapName("USUBJID", resolve));
        assertEquals("!$myop", OutputVariableToken.mapName("!$myop", resolve));
        // and a whole-name map lookup (the TokenExpander / WildcardExpander shape) hits the name
        java.util.Map<String, String> rename = java.util.Map.of("AyLO", "LBORRES");
        java.util.function.UnaryOperator<String> lookup = n -> rename.getOrDefault(n, n);
        assertEquals("LBORRES", OutputVariableToken.mapName("AyLO", lookup));
        assertEquals("!LBORRES", OutputVariableToken.mapName("!AyLO", lookup));
    }


    @Test
    void mapNameLeavesAMalformedTokenMalformed()
    {
        java.util.function.UnaryOperator<String> upper = name -> name
                .toUpperCase(java.util.Locale.ROOT);
        assertEquals("!", OutputVariableToken.mapName("!", upper));
        assertNotNull(OutputVariableToken.malformed(OutputVariableToken.mapName("!", upper)));
        assertEquals("!!X", OutputVariableToken.mapName("!!x", upper));
        assertNotNull(OutputVariableToken.malformed(OutputVariableToken.mapName("!!x", upper)));
    }


    @Test
    void includesKeepsOrderAndDropsEveryToken()
    {
        assertEquals(List.of("B", "A"),
                OutputVariableToken.includes(List.of("!X", "B", "!Y", "A")));
    }


    @Test
    void exclusionsDedupInFirstAppearanceOrder()
    {
        List<String> in = new ArrayList<>(List.of("!B", "A", "!C", "!B"));
        assertEquals(List.of("B", "C"), List.copyOf(OutputVariableToken.exclusions(in)));
    }


    @Test
    void applyExclusionsDropsTheTokenAndTheNameItNames()
    {
        assertEquals(List.of("A", "--X"),
                OutputVariableToken.applyExclusions(List.of("A", "!B", "B", "--X", "!--Y", "--Y")));
        // a token naming nothing listed just disappears; an exclusion never creates an entry
        assertEquals(List.of("A"), OutputVariableToken.applyExclusions(List.of("A", "!B")));
    }
}
