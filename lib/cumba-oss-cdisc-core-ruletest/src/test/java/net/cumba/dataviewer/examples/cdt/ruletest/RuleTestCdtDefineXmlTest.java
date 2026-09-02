package net.cumba.dataviewer.examples.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * The {@code #define-xml} scenario directive: a real Define-XML sidecar (relative file name,
 * resolved by the runner against the scenario's directory) serving both the {@code define_*}
 * provider level and the {@code define_vlm_*} value-level resolver — the production Define-XML path
 * in miniature. Mutually exclusive with the synthetic {@code #define} / {@code #define-include}
 * channel.
 */
class RuleTestCdtDefineXmlTest
{

    private static final String LB_DATASET = """
            dataset LB
            col LBTESTCD type=Char
            col LBSTRESC type=Char
            ---
            GLUC |
            ---
            """;

    private static String scenario(String aDirectives)
    {
        return "#!RuleTest\n" + aDirectives + "\n" + LB_DATASET;
    }


    @Test
    void defineXml_parsed()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test FDA-X expect=violation domain=LB
                #define-xml my-define.xml"""), "t");

        assertEquals("my-define.xml", s.getDefineXml());
        assertNull(s.getDefine());
    }


    @Test
    void defineXml_absent_isNull()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("#test FDA-X expect=violation domain=LB"),
                "t");

        assertNull(s.getDefineXml());
    }


    @Test
    void defineXml_roundTripsThroughWriter()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test FDA-X expect=violation domain=LB
                #define-xml my-define.xml"""), "t");

        String written = RuleTestCdt.toString(s);
        assertTrue(written.contains("#define-xml my-define.xml"),
                "writer must re-emit the directive, got:\n" + written);
        assertEquals("my-define.xml", RuleTestCdt.parse(written, "roundtrip").getDefineXml());
    }


    @Test
    void defineXml_missingValue_rejected()
    {
        RuleTestCdtException e = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("""
                        #test FDA-X expect=violation domain=LB
                        #define-xml"""), "t"));

        assertTrue(e.getMessage().contains("exactly one file name"), e.getMessage());
    }


    @Test
    void defineXml_duplicate_rejected()
    {
        RuleTestCdtException e = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("""
                        #test FDA-X expect=violation domain=LB
                        #define-xml a.xml
                        #define-xml b.xml"""), "t"));

        assertTrue(e.getMessage().contains("duplicate #define-xml"), e.getMessage());
    }


    @Test
    void defineXml_combinedWithSyntheticDefine_rejected()
    {
        RuleTestCdtException e = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(scenario("""
                        #test FDA-X expect=violation domain=LB
                        #define-xml a.xml
                        #define variable-metadata LB LBSTRESC label="X\""""), "t"));

        assertTrue(e.getMessage().contains("#define-xml cannot be combined"), e.getMessage());
    }
}
