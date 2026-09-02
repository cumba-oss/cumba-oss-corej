package net.cumba.dataviewer.examples.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * The {@code #define} / {@code #define-include} scenario channel ({@code PLAN-coreJ-cdisc-provider}
 * Phase 3, Q4): the Define-XML level mirrors the {@code #library} grammar, backed by the same
 * {@link MapBackedLibraryMetadataProvider} double, and combines freely with any library channel so
 * one scenario can assert each metadata axis (data-library, data-define, define-library)
 * independently.
 */
class RuleTestCdtDefineTest
{

    private static final String VS_DATASET = """
            dataset VS
            col VSPOS type=Char
            ---
            SUPINE
            ---
            """;

    private static String scenario(String aDirectives)
    {
        return "#!RuleTest\n" + aDirectives + "\n" + VS_DATASET;
    }


    @Test
    void define_variableMetadata_storedIndependentlyOfLibrary()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test CORE-1 expect=violation domain=VS
                #library variable-metadata VS VSPOS role="Result Qualifier"
                #define variable-metadata VS VSPOS label="Position of Subject\""""), "t");

        assertNotNull(s.getDefine());
        assertNotNull(s.getLibrary());
        assertEquals("Position of Subject",
                s.getDefine().getVariableMetadata("VS", "VSPOS").get("label"));
        // The two levels never blend: the define double has no role, the library no label.
        assertNull(s.getDefine().getVariableMetadata("VS", "VSPOS").get("role"));
        assertNull(s.getLibrary().getVariableMetadata("VS", "VSPOS").get("label"));
    }


    @Test
    void define_withoutDirective_isNull()
    {
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test CORE-1 expect=violation domain=VS"""), "t");
        assertNull(s.getDefine());
    }


    @Test
    void define_combinesWithLibraryRef()
    {
        // #library-ref excludes inline #library, but the define axis is independent of it.
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test CORE-1 expect=violation domain=VS
                #library-ref standard=sdtmig version=3-4
                #define variable-metadata VS VSPOS label="Position\""""), "t");
        assertNotNull(s.getLibraryRef());
        assertNotNull(s.getDefine());
    }


    @Test
    void defineInclude_mergesSidecarThenInlineOverrides()
    {
        String sidecar = """
                domains:
                  VS:
                    variable-metadata:
                      VSPOS: { label: "Sidecar Label" }
                      VSTESTCD: { label: "Vital Signs Test Short Name" }
                """;
        RuleTestScenario s = RuleTestCdt.parse(scenario("""
                #test CORE-1 expect=violation domain=VS
                #define-include define.yaml
                #define variable-metadata VS VSPOS label="Inline Label\""""), "t",
                path -> "define.yaml".equals(path)
                        ? new ByteArrayInputStream(sidecar.getBytes(StandardCharsets.UTF_8))
                        : null);

        // Same merge contract as #library: sidecars first, an inline directive then replaces the
        // whole (domain, variable) entry; entries the inline lines don't touch survive.
        assertEquals("Inline Label", s.getDefine().getVariableMetadata("VS", "VSPOS").get("label"));
        assertEquals("Vital Signs Test Short Name",
                s.getDefine().getVariableMetadata("VS", "VSTESTCD").get("label"));
    }


    @Test
    void defineInclude_missingSidecar_reportsDefineDirective()
    {
        String content = scenario("""
                #test CORE-1 expect=violation domain=VS
                #define-include nope.yaml""");
        RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(content, "t", _ -> null));
        assertTrue(ex.getMessage().contains("#define-include"), ex.getMessage());
    }


    @Test
    void define_badKind_reportsDefineDirective()
    {
        String content = scenario("""
                #test CORE-1 expect=violation domain=VS
                #define no-such-kind VS""");
        RuleTestCdtException ex = assertThrows(RuleTestCdtException.class,
                () -> RuleTestCdt.parse(content, "t"));
        assertTrue(ex.getMessage().contains("#define: unknown kind"), ex.getMessage());
    }


    /**
     * End-to-end: a {@code define_*} operand rule executes against the scenario's define double
     * through the engine's define slot — the data-define axis.
     */
    @Test
    void define_double_servesDefineOperandsEndToEnd() throws Exception
    {
        String json = "{\"Core\":{\"Id\":\"R1\"}," + "\"Variable_Universe\":\"Define\","
                + "\"Sensitivity\":\"Dataset\",\"Check\":{\"all\":["
                + "{\"name\":\"define_variable_label\",\"operator\":\"not_equal_to\","
                + "\"value\":\"Position of Subject\",\"value_is_literal\":true}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"define_variable_label\"]}}";
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + json + "}}");
        Rule rule = pkg.getRules().get("R1");

        // domain-variables declares the ItemDef iteration list; variable-metadata carries the
        // attribute map the define accessors read — same split as the #library channel.
        RuleTestScenario match = RuleTestCdt.parse(scenario("""
                #test R1 expect=noViolation domain=VS
                #define domain-variables VS VSPOS:Qualifier
                #define variable-metadata VS VSPOS label="Position of Subject\""""), "t");
        RuleExecutionResult ok = RuleRunner.execute(rule, match.primaryTable(), match.resolver(),
                "VS", null, null, match.getDefine());
        assertFalse(ok.hasViolations(), "matching define label must not fire");

        RuleTestScenario mismatch = RuleTestCdt.parse(scenario("""
                #test R1 expect=violation domain=VS
                #define domain-variables VS VSPOS:Qualifier
                #define variable-metadata VS VSPOS label="Wrong Label\""""), "t");
        RuleExecutionResult bad = RuleRunner.execute(rule, mismatch.primaryTable(),
                mismatch.resolver(), "VS", null, null, mismatch.getDefine());
        assertTrue(bad.hasViolations(), "mismatching define label must fire");
    }
}
