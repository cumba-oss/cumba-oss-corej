package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Parser / writer coverage for the {@code #library-ref} directive — the declarative reference to a
 * real CDISC Library. Pure text round-trips; no network or Library access.
 */
class RuleTestCdtLibraryRefTest
{

    private static final String DATASET = """
            dataset ADSL
            col STUDYID type=Char
            col USUBJID type=Char
            col AGE type=Char
            ---
            CDISCPILOT01 | 01-701-1015 | 56
            ---
            """;

    private static String scenario(String aRefBlock)
    {
        return "#!RuleTest\n#test CDISC-AD0200 expect=violation domain=ADSL\n" + aRefBlock
                + DATASET;
    }

    // ---- parse --------------------------------------------------------------------


    @Test
    void parse_minimal_setsStandardAndVersion()
    {
        RuleTestScenario s = RuleTestCdt
                .parse(scenario("#library-ref standard=adamig version=1-3\n"), "t");
        LibraryRef ref = s.getLibraryRef();
        assertNotNull(ref);
        assertEquals("adamig", ref.getStandard());
        assertEquals("1-3", ref.getVersion());
        assertTrue(ref.getCtPackages().isEmpty());
        assertNull(ref.getSubstandard());
        // A ref scenario carries no inline synthetic provider.
        assertNull(s.getLibrary());
    }


    @Test
    void parse_allFields_singleLine()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario(
                "#library-ref standard=sdtmig version=3-4 ct=sdtmct-2024-09-27 substandard=sdtm\n"),
                "t");
        LibraryRef ref = s.getLibraryRef();
        assertNotNull(ref);
        assertEquals("sdtmig", ref.getStandard());
        assertEquals("3-4", ref.getVersion());
        assertEquals(List.of("sdtmct-2024-09-27"), ref.getCtPackages());
        assertEquals("sdtm", ref.getSubstandard());
    }


    @Test
    void parse_multiLine_accumulatesCtAndOverridesScalars()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #library-ref standard=adamig version=1-2 ct=adamct-2024-03-29
                #library-ref version=1-3 ct=adamct-2024-09-27
                """), "t");
        LibraryRef ref = s.getLibraryRef();
        assertNotNull(ref);
        assertEquals("adamig", ref.getStandard());
        // Later line overrides the scalar version.
        assertEquals("1-3", ref.getVersion());
        // ct= accumulates across lines, in order.
        assertEquals(List.of("adamct-2024-03-29", "adamct-2024-09-27"), ref.getCtPackages());
    }


    @Test
    void parse_noRef_leavesLibraryRefNull()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario(""), "t");
        assertNull(s.getLibraryRef());
    }

    // ---- rejection ----------------------------------------------------------------


    @Test
    void parse_missingStandard_rejected()
    {
        RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("#library-ref version=1-3\n"), "t"));
        assertTrue(ex.getMessage().contains("missing required standard"), ex.getMessage());
    }


    @Test
    void parse_missingVersion_rejected()
    {
        RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("#library-ref standard=adamig\n"), "t"));
        assertTrue(ex.getMessage().contains("missing required version"), ex.getMessage());
    }


    @Test
    void parse_unknownKey_rejected()
    {
        RuleTestCdtException ex = assertThrows(RuleTestCdtException.class, () -> RuleTestCdt
                .parse(scenario("#library-ref standard=adamig version=1-3 useCase=tig\n"), "t"));
        assertTrue(ex.getMessage().contains("unknown key 'useCase'"), ex.getMessage());
    }


    @Test
    void parse_defineVersionKey_rejected()
    {
        RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(
                        scenario("#library-ref standard=adamig version=1-3 defineVersion=2-1\n"),
                        "t"));
        assertTrue(ex.getMessage().contains("unknown key 'defineVersion'"), ex.getMessage());
    }


    @Test
    void parse_tokenWithoutEquals_rejected()
    {
        RuleTestCdtException ex = assertThrows(RuleTestCdtException.class, () -> RuleTestCdt
                .parse(scenario("#library-ref standard=adamig version=1-3 oops\n"), "t"));
        assertTrue(ex.getMessage().contains("expected key=value"), ex.getMessage());
    }


    @Test
    void parse_refPlusInlineLibrary_rejected()
    {
        RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("""
                        #library-ref standard=adamig version=1-3
                        #library standard=adamig version=1-3
                        """), "t"));
        assertTrue(ex.getMessage().contains("cannot be combined"), ex.getMessage());
    }


    @Test
    void parse_refPlusLibraryInclude_rejected()
    {
        RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("""
                        #library-ref standard=adamig version=1-3
                        #library-include some.yaml
                        """), "t"));
        assertTrue(ex.getMessage().contains("cannot be combined"), ex.getMessage());
    }

    // ---- round-trip ---------------------------------------------------------------


    @Test
    void roundTrip_reEmitsLibraryRefLine()
    {
        RuleTestScenario original = RuleTestCdt.parse(scenario(
                "#library-ref standard=sdtmig version=3-4 ct=sdtmct-2024-09-27 substandard=sdtm\n"),
                "orig");

        String out = RuleTestCdt.toString(original);
        assertTrue(out.contains("#library-ref standard=sdtmig version=3-4"), out);

        LibraryRef rt = RuleTestCdt.parse(out, "rt").getLibraryRef();
        assertNotNull(rt);
        assertEquals("sdtmig", rt.getStandard());
        assertEquals("3-4", rt.getVersion());
        assertEquals(List.of("sdtmct-2024-09-27"), rt.getCtPackages());
        assertEquals("sdtm", rt.getSubstandard());
    }


    @Test
    void roundTrip_multipleCtPackages_preserved()
    {
        RuleTestScenario original = RuleTestCdt.parse(scenario("""
                #library-ref standard=adamig version=1-3 ct=adamct-2024-03-29 ct=adamct-2024-09-27
                """), "orig");

        LibraryRef rt = RuleTestCdt.parse(RuleTestCdt.toString(original), "rt").getLibraryRef();
        assertNotNull(rt);
        assertEquals(List.of("adamct-2024-03-29", "adamct-2024-09-27"), rt.getCtPackages());
        assertNull(rt.getSubstandard());
    }
}
