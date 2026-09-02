package net.cumba.dataviewer.examples.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The {@code #dictionaries} scenario directive: an opt-in reference to an external-dictionary
 * bundle for the scenario run. Only the {@code dummy} bundle (the checked-in dummy dictionaries) is
 * accepted; the runner side resolves it to a {@code RuntimeDictionaryProvider}. Absent directive
 * means no provider, so dictionary-dependent rules SKIP rather than false-passing — through
 * {@code RuleRunner}'s eager dictionary arm for the declared ({@code $}-ref) form ({@code Fix
 * #268}), or through an injected {@code dictionary_available} gate for an inlined call.
 */
class RuleTestCdtDictionariesTest
{

    private static final String AE_DATASET = """
            dataset AE
            col AEDECOD type=Char
            ---
            Headache
            ---
            """;

    private static String scenario(String aDirectives)
    {
        return "#!RuleTest\n" + aDirectives + "\n" + AE_DATASET;
    }


    @Test
    void dictionaries_dummy_parsed()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test FDA-X expect=violation domain=AE
                #dictionaries dummy"""), "t");

        assertEquals("dummy", s.getDictionaries());
    }


    @Test
    void dictionaries_absent_isNull()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("#test FDA-X expect=violation domain=AE"),
                "t");

        assertNull(s.getDictionaries());
    }


    @Test
    void dictionaries_roundTripsThroughWriter()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test FDA-X expect=violation domain=AE
                #dictionaries dummy"""), "t");

        String written = RuleTestCdt.toString(s);
        assertTrue(written.contains("#dictionaries dummy"),
                "writer must re-emit the directive, got:\n" + written);
        assertEquals("dummy", RuleTestCdt.parse(written, "roundtrip").getDictionaries());
    }


    @Test
    void dictionaries_unsupportedValue_rejected()
    {
        RuleTestCdtException e = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("""
                        #test FDA-X expect=violation domain=AE
                        #dictionaries meddra-2024"""), "t"));

        assertTrue(e.getMessage().contains("unsupported #dictionaries value"), e.getMessage());
    }


    @Test
    void dictionaries_missingValue_rejected()
    {
        RuleTestCdtException e = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("""
                        #test FDA-X expect=violation domain=AE
                        #dictionaries"""), "t"));

        assertTrue(e.getMessage().contains("exactly one value"), e.getMessage());
    }


    @Test
    void dictionaries_duplicate_rejected()
    {
        RuleTestCdtException e = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("""
                        #test FDA-X expect=violation domain=AE
                        #dictionaries dummy
                        #dictionaries dummy"""), "t"));

        assertTrue(e.getMessage().contains("duplicate #dictionaries"), e.getMessage());
    }
}
