package net.cumba.corej.authoring;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.List;
import net.cumba.corej.authoring.RuleDiagnostic.Severity;
import net.cumba.corej.core.model.StandardRef;
import org.junit.jupiter.api.Test;

/**
 * {@link AuthoredRuleAssembler#assemble} over one authored rule: the nodes it produces, the rule it
 * loads and the diagnostics it raises instead of throwing.
 */
class AuthoredRuleAssemblerTest
{

    static final String CHECK = """
            Core:
              Id: "CDISC-XX0001"
              Status: "Published"
              Version: "1"
            Description: "AESEV and AESER agree"
            Executability: "Fully Executable"
            Scope:
              Domains:
                Include:
                - "AE"
            Check:
              expression: 'AESEV == "MILD" and AESER == "Y"'
            Outcome:
              Message: "a mild serious event"
            Source:
              Rule: "kept in the source, dropped from the package"
            Standards:
            - Organization: "CDISC"
              Standards:
              - Name: "SDTMIG"
                Version: "3.4"
                References:
                - Origin: "SDTM and SDTMIG Conformance Rules"
                  Rule_Identifier:
                    Id: "XX0001"
                    Version: "1"
                  Version: "2.0"
            """;

    static final String DOC = """
            Core: "CDISC-XX0001"
            Citations:
              CDISC|SDTMIG|3.4||XX0001|1:
              - Cited_Guidance: "the cited text"
                Document: "IG v3.4"
                Section: "6.2"
            """;

    static final List<StandardRef> STANDARDS = List
            .of(new StandardRef("sdtmig-3-4", StandardRef.Role.PRIMARY));

    @Test
    void aCleanRuleAssemblesLoadsAndRaisesNoDiagnostic()
    {
        AssembledRule r = AuthoredRuleAssembler.assemble(CHECK, DOC, STANDARDS);
        assertEquals("CDISC-XX0001", r.coreId());
        assertEquals(List.of(), r.diagnostics());
        assertFalse(r.parked());
        assertNotNull(r.rule());
        assertNull(r.rule().getLoadError());
        // canonical: full Authorities with the citation re-joined, Source kept, Standards gone
        JsonNode canonical = r.canonicalNode();
        assertNotNull(canonical);
        assertNull(canonical.get("Standards"));
        assertEquals("the cited text", canonical
                .at("/Authorities/0/Standards/0/References/0/Citations/0/Cited_Guidance").asText());
        assertTrue(canonical.has("Source"));
        // packaged: released shape
        JsonNode packaged = r.packagedNode();
        assertNotNull(packaged);
        assertFalse(packaged.has("Source"));
        assertEquals("XX0001", packaged.at("/Authorities/0/Rule_Ids/0").asText());
        assertTrue(packaged.at("/Authorities/0/Standards").isMissingNode(),
                "the trimmer collapses Authorities to Organization + Rule_Ids");
    }


    @Test
    void theCanonicalNodeEqualsRebuildRule() throws Exception
    {
        JsonNode check = AuthoredRuleAssembler.readYamlTree(CHECK);
        JsonNode doc = AuthoredRuleAssembler.readYamlTree(DOC);
        AssembledRule r = AuthoredRuleAssembler.assemble(CHECK, DOC, STANDARDS);
        assertEquals(AuthoredRuleAssembler.rebuildRule(check, doc), r.canonicalNode());
        assertEquals(List.of(new AuthoredRuleAssembler.StandardVersion("SDTMIG", "3.4")),
                AuthoredRuleAssembler.standardsOf(check));
        assertTrue(AuthoredRuleAssembler.belongs(check, "SDTMIG", "3.4"));
        assertFalse(AuthoredRuleAssembler.belongs(check, "SDTMIG", "3.3"));
    }


    @Test
    void aMalformedExpressionIsAPathedDiagnosticAtItsCharacter()
    {
        // A malformed Check fails at bind time, so the whole one-rule package refuses to load:
        // no rule, one PACKAGE_LOAD diagnostic naming /Check and the character the parser
        // stopped at (ExpressionException.getPosition()).
        String broken = CHECK.replace("AESEV == \"MILD\"", "AESEV == ==");
        AssembledRule r = AuthoredRuleAssembler.assemble(broken, null, STANDARDS);
        assertNull(r.rule());
        assertEquals(1, r.diagnostics().size(), r.diagnostics().toString());
        RuleDiagnostic d = r.diagnostics().getFirst();
        assertEquals("PACKAGE_LOAD", d.code());
        assertEquals(Severity.ERROR, d.severity());
        assertEquals("/Check", String.valueOf(d.path()));
        assertTrue(d.message().contains("(at position 9)"), d.message());
        assertNotNull(d.range());
        // line 12 is ` expression: 'AESEV == == and AESER == "Y"'`: the value starts at column
        // 15 with the quote, so position 9 is column 15 + 1 + 9.
        assertEquals(12, d.range().startLine());
        assertEquals(25, d.range().startColumn());
    }


    @Test
    void aLoadErrorBecomesAPathedDiagnostic()
    {
        // An empty Scope.Domains.Include entry is a per-rule load error: the rule loads, the
        // diagnostic names the key the loader names, located in the authored text.
        String withEmptyEntry = CHECK.replace("    - \"AE\"\n", "    - \"\"\n    - \"AE\"\n");
        assertFalse(withEmptyEntry.equals(CHECK), "the mutation must land");
        AssembledRule r = AuthoredRuleAssembler.assemble(withEmptyEntry, null, STANDARDS);
        assertNotNull(r.rule(), "diagnostics: " + r.diagnostics());
        assertTrue(r.hasErrors(),
                "diagnostics: " + r.diagnostics() + " loadError: " + r.rule().getLoadError());
        RuleDiagnostic d = r.errors().getFirst();
        assertEquals(LoadErrorDiagnostics.LOAD_ERROR, d.code());
        assertEquals(Severity.ERROR, d.severity());
        assertEquals("/Scope/Domains/Include", String.valueOf(d.path()), d.message());
        assertNotNull(d.range(), "the key was located in the authored text");
        assertEquals(9, d.range().startLine());
    }


    @Test
    void aParkedRuleProducesNodesButNoRule()
    {
        String parked = CHECK.replace("\"Fully Executable\"", "\"Not Executable\"");
        AssembledRule r = AuthoredRuleAssembler.assemble(parked, null, STANDARDS);
        assertTrue(r.parked());
        assertNull(r.rule());
        assertNotNull(r.packagedNode());
        assertEquals(1, r.diagnostics().size());
        RuleDiagnostic d = r.diagnostics().getFirst();
        assertEquals("PARKED", d.code());
        assertEquals(Severity.INFO, d.severity());
        assertEquals("/Executability", String.valueOf(d.path()));
        assertFalse(r.hasErrors());
    }


    @Test
    void aYamlSyntaxErrorIsADiagnosticWithALocation()
    {
        AssembledRule r = AuthoredRuleAssembler.assemble("Core: [unterminated\n  Id: x\n", null,
                List.of());
        assertEquals(AuthoredRuleAssembler.UNKNOWN_ID, r.coreId());
        assertNull(r.rule());
        assertNull(r.canonicalNode());
        assertEquals(1, r.diagnostics().size());
        assertEquals("YAML_SYNTAX", r.diagnostics().getFirst().code());
        assertNotNull(r.diagnostics().getFirst().range());
    }


    @Test
    void aMissingCoreIdIsAnErrorAndNothingIsLoaded()
    {
        String noId = CHECK.replace("  Id: \"CDISC-XX0001\"\n", "");
        AssembledRule r = AuthoredRuleAssembler.assemble(noId, null, STANDARDS);
        assertEquals(AuthoredRuleAssembler.UNKNOWN_ID, r.coreId());
        assertNull(r.rule());
        assertNotNull(r.packagedNode(), "the nodes are still produced");
        assertEquals("CORE_ID_MISSING", r.errors().getFirst().code());
        assertEquals("/Core/Id", String.valueOf(r.errors().getFirst().path()));
    }


    @Test
    void anUnderivableSensitivityIsAnErrorAndTheRuleStillLoads()
    {
        // No Check at all: the classifier has nothing to derive Sensitivity from.
        String noCheck = CHECK
                .replace("Check:\n  expression: 'AESEV == \"MILD\" and AESER == \"Y\"'\n", "");
        AssembledRule r = AuthoredRuleAssembler.assemble(noCheck, null, STANDARDS);
        assertTrue(
                r.diagnostics().stream().anyMatch(d -> "SENSITIVITY_UNDERIVABLE".equals(d.code())),
                r.diagnostics().toString());
        assertEquals("/Sensitivity", String.valueOf(r.diagnostics().stream()
                .filter(d -> "SENSITIVITY_UNDERIVABLE".equals(d.code())).findFirst().get().path()));
    }


    @Test
    void theDocSidecarIsOptional()
    {
        AssembledRule r = AuthoredRuleAssembler.assemble(CHECK, null, STANDARDS);
        assertNotNull(r.canonicalNode());
        assertTrue(r.canonicalNode().at("/Authorities/0/Standards/0/References/0/Citations")
                .isMissingNode());
        assertEquals(List.of(), r.diagnostics());
    }


    @Test
    void theNotAMappingCaseIsADiagnostic()
    {
        AssembledRule r = AuthoredRuleAssembler.assemble("- a\n- b\n", null, List.of());
        assertEquals("NOT_A_MAPPING", r.diagnostics().getFirst().code());
        assertNull(r.packagedNode());
    }
}
