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
        // no rule, one diagnostic naming /Check and the character the parser stopped at
        // (ExpressionException.getPosition()) — EXPRESSION_SYNTAX, since the root cause is the
        // expression (editor note, review 8a-A).
        String broken = CHECK.replace("AESEV == \"MILD\"", "AESEV == ==");
        AssembledRule r = AuthoredRuleAssembler.assemble(broken, null, STANDARDS);
        assertNull(r.rule());
        assertEquals(1, r.diagnostics().size(), r.diagnostics().toString());
        RuleDiagnostic d = r.diagnostics().getFirst();
        assertEquals(AuthoredRuleAssembler.EXPRESSION_SYNTAX, d.code());
        assertEquals(Severity.ERROR, d.severity());
        assertEquals(RuleDiagnostic.Document.CHECK, d.document());
        assertEquals("/Check", String.valueOf(d.path()));
        assertTrue(d.message().contains("(at position 9)"), d.message());
        assertNotNull(d.range());
        // line 12 is ` expression: 'AESEV == == and AESER == "Y"'`: the value starts at column
        // 15 with the quote, so position 9 is column 15 + 1 + 9.
        assertEquals(12, d.range().startLine());
        assertEquals(25, d.range().startColumn());
    }


    /**
     * Review 8a-A F1, the CDISC-CG0270 repro: in a {@code >-} block (most of the corpus) the
     * expression's character was placed on the {@code >} line, one line above the text.
     */
    @Test
    void aMalformedExpressionInAFoldedBlockIsLocatedOnItsCharacter()
    {
        String broken = CHECK.replace("  expression: 'AESEV == \"MILD\" and AESER == \"Y\"'\n",
                "  expression: >-\n    AESEV == == and AESER == \"Y\"\n");
        assertFalse(broken.equals(CHECK), "the mutation must land");
        AssembledRule r = AuthoredRuleAssembler.assemble(broken, null, STANDARDS);
        RuleDiagnostic d = r.errors().getFirst();
        assertEquals("/Check", String.valueOf(d.path()));
        assertTrue(d.message().contains("(at position 9)"), d.message());
        assertEquals(new RuleDiagnostic.TextRange(13, 14, 13, 14), d.range());
    }


    /**
     * Review 8a-A F2: a half-typed {@code Standards} block threw NPE / CCE out of the editor's
     * never-throwing entry point, and out of the corpus gate as a bare NPE. A shape diagnostic with
     * the path now — and a loud, worded refusal from {@code rebuildRule}.
     */
    @Test
    void aHalfTypedStandardsBlockIsAShapeDiagnostic() throws Exception
    {
        String head = CHECK.substring(0, CHECK.indexOf("Standards:"));
        String[] shapes =
        {
                // no Organization
                "Standards:\n- Standards:\n  - Name: \"SDTMIG\"\n    Version: \"3.4\"\n",
                // cut right after the Organization
                "Standards:\n- Organization: \"CDISC\"\n",
                // a null authority
                "Standards: [null]\n",
                // a null standard
                "Standards:\n- Organization: \"CDISC\"\n  Standards: [null]\n",
                // a scalar reference
                "Standards:\n- Organization: \"CDISC\"\n  Standards:\n  - Name: \"SDTMIG\"\n"
                        + "    Version: \"3.4\"\n    References: [\"XX0001\"]\n"
        };
        for (String shape : shapes)
        {
            String yaml = head + shape;
            AssembledRule r = AuthoredRuleAssembler.assemble(yaml, null, STANDARDS);
            RuleDiagnostic d = r.errors().stream().filter(e -> "STANDARDS_SHAPE".equals(e.code()))
                    .findFirst().orElse(null);
            assertNotNull(d, shape + " -> " + r.diagnostics());
            assertNotNull(d.path(), d.toString());
            assertTrue(String.valueOf(d.path()).startsWith("/Standards"), d.toString());
            assertNull(r.canonicalNode(), "no node from a refused shape");
            assertNull(r.rule());
            JsonNode check = AuthoredRuleAssembler.readYamlTree(yaml);
            IllegalStateException e = org.junit.jupiter.api.Assertions.assertThrows(
                    IllegalStateException.class,
                    () -> AuthoredRuleAssembler.rebuildRule(check, null), shape);
            assertTrue(e.getMessage().contains("CDISC-XX0001"), e.getMessage());
        }
    }


    /**
     * Review 8a-A F3: a single loader message containing {@code "; "} (here gate 3's "an empty
     * entry names nothing; remove it …") was split into a second, pathless diagnostic. The engine
     * keeps its load errors one by one now.
     */
    @Test
    void aLoadMessageContainingASemicolonIsOneDiagnostic()
    {
        String withEmptyEntry = CHECK.replace("    - \"AE\"\n", "    - \"\"\n    - \"AE\"\n");
        AssembledRule r = AuthoredRuleAssembler.assemble(withEmptyEntry, null, STANDARDS);
        assertNotNull(r.rule());
        assertTrue(r.rule().getLoadError().contains("; "), "premise: " + r.rule().getLoadError());
        assertEquals(1, r.errors().size(), r.errors().toString());
        assertEquals(r.rule().getLoadError(), r.errors().getFirst().message());
    }


    /**
     * Review 8a-A F4: a second YAML document was dropped silently — the reader took the first, the
     * location walk ran on into the second.
     */
    @Test
    void aSecondDocumentIsAnError()
    {
        AssembledRule r = AuthoredRuleAssembler.assemble(CHECK + "---\nCore:\n  Id: \"other\"\n",
                null, STANDARDS);
        RuleDiagnostic d = r.errors().stream().filter(e -> "MULTI_DOCUMENT".equals(e.code()))
                .findFirst().orElse(null);
        assertNotNull(d, r.diagnostics().toString());
        assertNotNull(d.range());
        assertEquals(28, d.range().startLine(), "the second document's marker");
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


    /**
     * PACK 3 ride-along (the rule editor's stop-gap in {@code RuleAnalyser}): the corpus gate drops
     * a parked rule before anything is derived, so a parked rule whose {@code Sensitivity} cannot
     * be derived is not an error here either — its one diagnostic stays {@code PARKED}.
     */
    @Test
    void aParkedRuleWithAnUnderivableSensitivityRaisesNoSensitivityError()
    {
        String parkedNoCheck = CHECK.replace("\"Fully Executable\"", "\"Not Executable\"")
                .replace("Check:\n  expression: 'AESEV == \"MILD\" and AESER == \"Y\"'\n", "");
        AssembledRule r = AuthoredRuleAssembler.assemble(parkedNoCheck, null, STANDARDS);
        assertTrue(r.parked());
        assertNull(r.rule());
        assertEquals(List.of("PARKED"), r.diagnostics().stream().map(RuleDiagnostic::code).toList(),
                "a parked rule is never loaded, so nothing is derived for it: " + r.diagnostics());
        assertFalse(r.hasErrors());
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


    /**
     * Editor notes (review 8a-A): a docs sidecar is read as the corpus reads it — one document, its
     * {@code Core} naming this rule — and its diagnostics say they are about the docs file.
     */
    @Test
    void theDocSidecarIsCheckedAsTheCorpusJoinsIt()
    {
        AssembledRule other = AuthoredRuleAssembler.assemble(CHECK,
                DOC.replace("Core: \"CDISC-XX0001\"", "Core: \"CDISC-XX0002\""), STANDARDS);
        RuleDiagnostic mismatch = other.errors().getFirst();
        assertEquals(AuthoredRuleAssembler.DOC_CORE_MISMATCH, mismatch.code());
        assertEquals(RuleDiagnostic.Document.DOCS, mismatch.document());
        assertEquals(new RuleDiagnostic.TextRange(1, 1, 1, 4), mismatch.range());
        assertNotNull(other.rule(), "the rule itself still assembles");

        AssembledRule two = AuthoredRuleAssembler.assemble(CHECK, DOC + "---\nCore: x\n",
                STANDARDS);
        assertEquals(AuthoredRuleAssembler.MULTI_DOCUMENT, two.errors().getFirst().code());
        assertEquals(RuleDiagnostic.Document.DOCS, two.errors().getFirst().document());

        AssembledRule broken = AuthoredRuleAssembler.assemble(CHECK, "Core: [x\n", STANDARDS);
        assertEquals("DOC_YAML_SYNTAX", broken.errors().getFirst().code());
        assertEquals(RuleDiagnostic.Document.DOCS, broken.errors().getFirst().document());
        assertEquals(List.of(), AuthoredRuleAssembler.assemble(CHECK, DOC, STANDARDS).diagnostics(),
                "the matching sidecar raises nothing");
    }


    /** Review 8a-A F4: the corpus tools' reader refuses a second document too. */
    @Test
    void theSharedReaderRefusesASecondDocument()
    {
        org.junit.jupiter.api.Assertions.assertThrows(
                com.fasterxml.jackson.core.JsonProcessingException.class,
                () -> AuthoredRuleAssembler.readYamlTree("a: 1\n---\nb: 2\n"));
    }


    @Test
    void theNotAMappingCaseIsADiagnostic()
    {
        AssembledRule r = AuthoredRuleAssembler.assemble("- a\n- b\n", null, List.of());
        assertEquals("NOT_A_MAPPING", r.diagnostics().getFirst().code());
        assertNull(r.packagedNode());
    }
}
