package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Define-ct plan P6 — the run-level CT-selection directives: {@code #ct-packages} (the run's
 * {@code CT Packages} field), {@code #ct-available} (what the run's metadata store holds),
 * {@code #expect-abort} (§4.4 abort rows), and {@code #expect-ct-mismatch} (the §4.2 run-level
 * note). Absent directives stay {@code null} so the rule-level corpus runner is untouched.
 */
class RuleTestCdtCtSelectionTest
{

    private static final String VS_DATASET = """
            dataset VS
            col VSSTAT type=Char
            ---
            BOGUS
            ---
            """;

    private static String scenario(String aDirectives)
    {
        return "#!RuleTest\n" + aDirectives + "\n" + VS_DATASET;
    }


    @Test
    @DisplayName("#ct-packages / #ct-available parse to id lists; 'none' is the empty store")
    void ctDirectivesParse()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test CT-X expect=violation domain=VS
                #ct-packages sdtmct-2024-09-27 sdtmct-2025-03-28
                #ct-available sdtmct-2024-09-27"""), "t");
        assertEquals(List.of("sdtmct-2024-09-27", "sdtmct-2025-03-28"), s.getCtPackages());
        assertEquals(List.of("sdtmct-2024-09-27"), s.getCtAvailable());

        RuleTestScenario none = RuleTestCdt.parse(scenario("""
                #test CT-X expect=skipped domain=VS
                #ct-available none"""), "t");
        assertNull(none.getCtPackages(), "absent directive = blank field, not empty list");
        assertEquals(List.of(), none.getCtAvailable(), "'none' = a store holding no CT package");
    }


    @Test
    @DisplayName("absent directives stay null — the corpus rule-level format is untouched")
    void absentDirectivesAreNull()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("#test CT-X expect=violation domain=VS"),
                "t");
        assertNull(s.getCtPackages());
        assertNull(s.getCtAvailable());
        assertNull(s.getExpectAbort());
        assertNull(s.getExpectCtMismatch());
    }


    @Test
    @DisplayName("#expect-abort requires expect=skipped and excludes #expect-ct-mismatch")
    void expectAbortConstraints()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test CT-X expect=skipped domain=VS
                #expect-abort "sdtmct-2023-12-15\""""), "t");
        assertEquals("sdtmct-2023-12-15", s.getExpectAbort());

        RuleTestCdtException wrongVerdict = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("""
                        #test CT-X expect=violation domain=VS
                        #expect-abort "x\""""), "t"));
        assertTrue(wrongVerdict.getMessage().contains("expect=skipped"), wrongVerdict.getMessage());

        RuleTestCdtException blend = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("""
                        #test CT-X expect=skipped domain=VS
                        #expect-abort "x"
                        #expect-ct-mismatch "y\""""), "t"));
        assertTrue(blend.getMessage().contains("cannot be combined"), blend.getMessage());
    }


    @Test
    @DisplayName("an id-less #ct-packages is an authoring error, not a blank field")
    void emptyPayloadRejected()
    {
        RuleTestCdtException e = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("""
                        #test CT-X expect=violation domain=VS
                        #ct-packages"""), "t"));
        assertTrue(e.getMessage().contains("omit the directive"), e.getMessage());
        // 'none' outside the single-token form is also an authoring error.
        assertThrows(RuleTestCdtException.class, () -> RuleTestCdt.parse(scenario("""
                #test CT-X expect=violation domain=VS
                #ct-available sdtmct-2024-09-27 none"""), "t"));
    }


    @Test
    @DisplayName("all four directives round-trip through the writer")
    void directivesRoundTrip()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test CT-X expect=violation domain=VS
                #ct-packages sdtmct-2024-09-27
                #ct-available none
                #expect-ct-mismatch "define declares sdtmct-2023-12-15\""""), "t");

        String written = RuleTestCdt.toString(s);
        RuleTestScenario back = RuleTestCdt.parse(written, "roundtrip");
        assertEquals(List.of("sdtmct-2024-09-27"), back.getCtPackages());
        assertEquals(List.of(), back.getCtAvailable());
        assertEquals("define declares sdtmct-2023-12-15", back.getExpectCtMismatch());

        RuleTestScenario abort = RuleTestCdt.parse(scenario("""
                #test CT-X expect=skipped domain=VS
                #expect-abort "sdtmct-2023-12-15\""""), "t");
        assertEquals("sdtmct-2023-12-15",
                RuleTestCdt.parse(RuleTestCdt.toString(abort), "roundtrip").getExpectAbort());
    }
}
