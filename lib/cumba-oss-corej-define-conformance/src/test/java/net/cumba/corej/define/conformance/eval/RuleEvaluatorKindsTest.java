package net.cumba.corej.define.conformance.eval;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.ct.CtProvider;
import net.cumba.corej.define.conformance.report.Category;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.report.Severity;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.tree.ElementNode;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RuleEvaluator} check kinds and gates not exercised by the shipped pilot rules:
 * {@code not_exists}, {@code consistent_across_document}, {@code referenced_file_exists},
 * {@code custom}, case-insensitive {@code one_of}, explicit-pattern {@code matches_regex}, the
 * {@code *} and parent-qualified selectors, the CT gate, {@code when} guards, message rendering,
 * and category / severity mapping.
 */
class RuleEvaluatorKindsTest
{

    private static DocumentContext context(String aXml)
    {
        return context(aXml, null, null);
    }


    private static DocumentContext context(String aXml, @Nullable CtProvider aCtProvider,
            @Nullable Path aSubmissionFolder)
    {
        try
        {
            ElementNode root = ElementNodeBuilder
                    .build(DefineDomIo.parse(new ByteArrayInputStream(aXml.getBytes(UTF_8))));
            return new DocumentContext(root, "2.1", aCtProvider, aSubmissionFolder);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot parse test XML", e);
        }
    }


    private static ConformanceRule rule(String aYaml)
    {
        return RuleRepository.parse(aYaml, "test");
    }


    private static String cdiscRule(String aElement, String aCheckBody)
    {
        return """
                Rule_Id: "DEFINE-XML-9999"
                Sheet_Rule_Identifier: "9999"
                Rule_Set: "CDISC"
                Element: "%s"
                Attribute: null
                Applicable_Versions: ["2.1"]
                Source_Type: "Specification"
                Plain_Text_Rule: "Test rule."
                Message: "Offending value [${value}]."
                Check:
                """.formatted(aElement) + aCheckBody.indent(2);
    }


    private static RuleResult evaluate(String aRuleYaml, DocumentContext aContext)
    {
        return new RuleEvaluator().evaluate(rule(aRuleYaml), aContext);
    }

    // ------------------------------------------------------------------
    // Kinds
    // ------------------------------------------------------------------


    @Test
    void notExistsFlagsNodesCarryingTheTarget()
    {
        DocumentContext context = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <ItemDef OID="IT.1"><def:Origin Type="Collected"/></ItemDef>
                  <ItemDef OID="IT.2"><def:Origin Type="Derived"/></ItemDef>
                  <ItemDef OID="IT.3"/>
                </ODM>
                """);
        RuleResult result = evaluate(cdiscRule("ItemDef", """
                kind: "not_exists"
                target: "def:Origin"
                """), context);
        assertEquals(ExecutionStatus.EXECUTED, result.status());
        assertEquals(2, result.findings().size());
        assertEquals("/ODM/ItemDef[@OID='IT.1']", result.findings().get(0).getXpath());
        assertEquals("/ODM/ItemDef[@OID='IT.2']", result.findings().get(1).getXpath());
    }


    @Test
    void consistentAcrossDocumentFlagsDeviantsAndSkipsEmptyValueNodes()
    {
        DocumentContext context = context("""
                <ODM>
                  <ItemDef OID="IT.1" DataType="text"/>
                  <ItemDef OID="IT.2" DataType="integer"/>
                  <ItemDef OID="IT.3" DataType="text"/>
                  <ItemDef OID="IT.4"/>
                </ODM>
                """);
        RuleResult result = evaluate(cdiscRule("ItemDef", """
                kind: "consistent_across_document"
                path: "@DataType"
                """), context);
        // First-seen value "text" wins; only the deviating IT.2 is flagged; the attribute-less
        // IT.4 contributes no value and is skipped, not flagged.
        assertEquals(1, result.findings().size());
        ConformanceFinding finding = result.findings().get(0);
        assertEquals("/ODM/ItemDef[@OID='IT.2']", finding.getXpath());
        assertEquals("Offending value [integer].", finding.getMessage());
    }


    @Test
    void referencedFileExistsChecksAgainstTheSubmissionFolder(@TempDir Path aFolder)
        throws Exception
    {
        Files.writeString(aFolder.resolve("dm.xpt"), "x", UTF_8);
        Files.writeString(aFolder.resolve("acrf.pdf"), "x", UTF_8);
        DocumentContext context = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1"
                     xmlns:xlink="http://www.w3.org/1999/xlink">
                  <def:leaf ID="LF.1" xlink:href="dm.xpt"/>
                  <def:leaf ID="LF.2" xlink:href="missing.xpt"/>
                  <def:leaf ID="LF.3" xlink:href="acrf.pdf#page=3"/>
                  <def:leaf ID="LF.4" xlink:href="#page=2"/>
                  <def:leaf ID="LF.5"/>
                </ODM>
                """);
        String ruleYaml = """
                Rule_Id: "PMDA-DD9999"
                Sheet_Rule_Identifier: "DD9999"
                Rule_Set: "PMDA"
                Element: "def:leaf"
                Attribute: "href"
                Applicable_Versions: ["2.1"]
                Requires: "folder"
                Plain_Text_Rule: "Referenced files must exist."
                Message: "File [${value}] not found."
                Check:
                  kind: "referenced_file_exists"
                  attribute: "href"
                """;
        // Existing file passes, '#fragment' is stripped before the lookup, a fragment-only href
        // is skipped, only the genuinely missing file fires.
        RuleResult result = new RuleEvaluator().evaluate(rule(ruleYaml),
                new DocumentContext(context.root(), "2.1", null, aFolder));
        assertEquals(ExecutionStatus.EXECUTED, result.status());
        assertEquals(1, result.findings().size());
        ConformanceFinding finding = result.findings().get(0);
        assertEquals("File [missing.xpt] not found.", finding.getMessage());
        assertEquals(Category.PMDA, finding.getCategory());
        // PMDA rule without a Severity column defaults to ERROR.
        assertEquals(Severity.ERROR, finding.getSeverity());

        // Without a submission folder the Requires gate skips the rule.
        RuleResult skipped = new RuleEvaluator().evaluate(rule(ruleYaml),
                new DocumentContext(context.root(), "2.1", null, null));
        assertEquals(ExecutionStatus.SKIPPED_MISSING_FOLDER, skipped.status());
        assertEquals(List.of(), skipped.findings());
    }


    @Test
    void customCheckIsInstantiatedByClassNameAndFiresPerUnsatisfiedNode()
    {
        DocumentContext context = context("""
                <ODM>
                  <ItemDef OID="IT.1"/>
                  <ItemDef/>
                </ODM>
                """);
        RuleResult result = evaluate(cdiscRule("ItemDef", """
                kind: "custom"
                className: "net.cumba.corej.define.conformance.eval.OidPresentCustomCheck"
                """), context);
        assertEquals(1, result.findings().size());
        assertEquals("/ODM/ItemDef[2]", result.findings().get(0).getXpath());
    }


    @Test
    void unknownCustomCheckClassNameFailsFast()
    {
        DocumentContext context = context("<ODM><ItemDef OID=\"IT.1\"/></ODM>");
        ConformanceRule bogus = rule(cdiscRule("ItemDef", """
                kind: "custom"
                className: "com.example.NoSuchCheck"
                """));
        RuleEvaluator evaluator = new RuleEvaluator();
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> evaluator.evaluate(bogus, context));
        assertTrue(e.getMessage().contains("com.example.NoSuchCheck"), e.getMessage());
    }


    @Test
    void oneOfCaseInsensitiveAcceptsAnyCasingAndSkipsAbsentValues()
    {
        DocumentContext context = context("""
                <ODM>
                  <ItemRef ItemOID="IT.1" Mandatory="YES"/>
                  <ItemRef ItemOID="IT.2" Mandatory="Maybe"/>
                  <ItemRef ItemOID="IT.3"/>
                </ODM>
                """);
        RuleResult result = evaluate(cdiscRule("ItemRef", """
                kind: "one_of"
                attribute: "Mandatory"
                values: ["Yes", "No"]
                caseInsensitive: true
                """), context);
        assertEquals(1, result.findings().size());
        assertEquals("Offending value [Maybe].", result.findings().get(0).getMessage());
    }


    @Test
    void matchesRegexWithExplicitPattern()
    {
        DocumentContext context = context("""
                <ODM>
                  <ItemGroupDef OID="IG.1" Domain="DM"/>
                  <ItemGroupDef OID="IG.2" Domain="d1"/>
                  <ItemGroupDef OID="IG.3"/>
                </ODM>
                """);
        RuleResult result = evaluate(cdiscRule("ItemGroupDef", """
                kind: "matches_regex"
                attribute: "Domain"
                pattern: "[A-Z]{2}"
                """), context);
        assertEquals(1, result.findings().size());
        assertEquals("Offending value [d1].", result.findings().get(0).getMessage());
        assertEquals("/ODM/ItemGroupDef[@OID='IG.2']", result.findings().get(0).getXpath());
    }


    @Test
    void uniqueAmongSiblingsSkipsNodesWithoutTheAttribute()
    {
        DocumentContext context = context("""
                <ODM>
                  <Description>
                    <TranslatedText lang="en">a</TranslatedText>
                    <TranslatedText lang="en">b</TranslatedText>
                    <TranslatedText>c</TranslatedText>
                  </Description>
                </ODM>
                """);
        RuleResult result = evaluate(cdiscRule("TranslatedText", """
                kind: "unique_among_siblings"
                attribute: "lang"
                """), context);
        // The duplicate "en" fires once; the attribute-less third sibling is skipped.
        assertEquals(1, result.findings().size());
        assertEquals("Offending value [en].", result.findings().get(0).getMessage());
    }


    @Test
    void hashTextAttributeChecksTheElementsTextContent()
    {
        DocumentContext context = context("""
                <ODM>
                  <StudyName>PILOT</StudyName>
                  <StudyName>OTHER</StudyName>
                </ODM>
                """);
        RuleResult result = evaluate(cdiscRule("StudyName", """
                kind: "one_of"
                attribute: "#text"
                values: ["PILOT"]
                """), context);
        assertEquals(1, result.findings().size());
        assertEquals("Offending value [OTHER].", result.findings().get(0).getMessage());
    }

    // ------------------------------------------------------------------
    // Scope selection
    // ------------------------------------------------------------------


    @Test
    void wildcardSelectorScopesEveryRealElementButNotTheSyntheticDocument()
    {
        DocumentContext context = context("""
                <ODM>
                  <A OID="1"/>
                  <B/>
                </ODM>
                """);
        RuleResult result = evaluate(cdiscRule("*", """
                kind: "exists"
                target: "@OID"
                """), context);
        // ODM and B lack an OID; the synthetic Document node must not be scoped (it would be a
        // third finding).
        assertEquals(2, result.findings().size());
    }


    @Test
    void qualifiedSelectorMatchesOnlyTheDeclaredParentChain()
    {
        String xml = """
                <ODM>
                  <ValueListDef OID="VL.1">
                    <ItemRef ItemOID="IT.A"/>
                  </ValueListDef>
                  <ItemGroupDef OID="IG.1">
                    <ItemRef ItemOID="IT.B" Mandatory="Yes"/>
                  </ItemGroupDef>
                </ODM>
                """;
        String check = """
                kind: "exists"
                target: "@Mandatory"
                """;
        RuleResult scoped = evaluate(cdiscRule("ValueListDef/ItemRef", check), context(xml));
        assertEquals(1, scoped.findings().size());
        assertEquals("/ODM/ValueListDef[@OID='VL.1']/ItemRef", scoped.findings().get(0).getXpath());

        // A chain that matches no parent produces an empty scope — executed, no findings.
        RuleResult noMatch = evaluate(cdiscRule("CodeList/ItemRef", check), context(xml));
        assertEquals(ExecutionStatus.EXECUTED, noMatch.status());
        assertEquals(List.of(), noMatch.findings());

        // A chain reaching above the root cannot match either.
        RuleResult aboveRoot = evaluate(cdiscRule("NoSuchRoot/ODM", check), context(xml));
        assertEquals(List.of(), aboveRoot.findings());
    }

    // ------------------------------------------------------------------
    // Gates and guards
    // ------------------------------------------------------------------


    @Test
    void ctRequirementSkipsWithoutProviderAndRunsWithOne()
    {
        String ruleYaml = """
                Rule_Id: "DEFINE-XML-9998"
                Sheet_Rule_Identifier: "9998"
                Rule_Set: "CDISC"
                Element: "CodeList"
                Attribute: null
                Applicable_Versions: ["2.1"]
                Source_Type: "Specification"
                Requires: "ct"
                Plain_Text_Rule: "Test rule."
                Message: "Test message."
                Check:
                  kind: "exists"
                  target: "@OID"
                """;
        String xml = "<ODM><CodeList OID=\"CL.1\"/></ODM>";

        RuleResult skipped = evaluate(ruleYaml, context(xml));
        assertEquals(ExecutionStatus.SKIPPED_MISSING_CT, skipped.status());
        assertEquals(List.of(), skipped.findings());

        RuleResult executed = new RuleEvaluator().evaluate(rule(ruleYaml),
                context(xml, _ -> Optional.empty(), null));
        assertEquals(ExecutionStatus.EXECUTED, executed.status());
        assertEquals(List.of(), executed.findings());
    }


    @Test
    void whenGuardLimitsTheCheckToMatchingNodes()
    {
        DocumentContext context = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <ItemDef OID="IT.1"><def:Origin Type="Collected"/></ItemDef>
                  <ItemDef OID="IT.2"><def:Origin Type="Derived"/></ItemDef>
                </ODM>
                """);
        // Neither ItemDef has a Length attribute, but only the Collected one is in reach.
        RuleResult result = evaluate(cdiscRule("ItemDef", """
                kind: "exists"
                target: "@Length"
                when:
                  path: "Origin/@Type"
                  equals: "Collected"
                """), context);
        assertEquals(1, result.findings().size());
        assertEquals("/ODM/ItemDef[@OID='IT.1']", result.findings().get(0).getXpath());
    }

    // ------------------------------------------------------------------
    // Finding metadata
    // ------------------------------------------------------------------


    @Test
    void schemaSourceTypeMapsToSchemaCategory()
    {
        DocumentContext context = context("<ODM><Study OID=\"ST.1\"/></ODM>");
        RuleResult result = evaluate("""
                Rule_Id: "DEFINE-XML-9997"
                Sheet_Rule_Identifier: "9997"
                Rule_Set: "CDISC"
                Element: "Study"
                Attribute: "StudyName"
                Applicable_Versions: ["2.1"]
                Source_Type: "Schema"
                Plain_Text_Rule: "Test rule."
                Message: "Missing StudyName."
                Check:
                  kind: "exists"
                  target: "@StudyName"
                """, context);
        ConformanceFinding finding = result.findings().get(0);
        assertEquals(Category.SCHEMA, finding.getCategory());
        assertEquals(Severity.ERROR, finding.getSeverity());
        assertEquals("Study", finding.getElement());
        assertEquals("StudyName", finding.getAttribute());
        // not_exists/exists findings carry no offending value — ${value} renders empty.
        assertEquals("Missing StudyName.", finding.getMessage());
    }


    @Test
    void nonSchemaSourceTypeMapsToSpecificationCategory()
    {
        DocumentContext context = context("<ODM><Study OID=\"ST.1\"/></ODM>");
        RuleResult result = evaluate(cdiscRule("Study", """
                kind: "exists"
                target: "@StudyName"
                """), context);
        assertEquals(Category.SPECIFICATION, result.findings().get(0).getCategory());
    }


    @Test
    void pmdaSheetSeverityIsUsedVerbatim()
    {
        DocumentContext context = context("<ODM><Study OID=\"ST.1\"/></ODM>");
        RuleResult result = evaluate("""
                Rule_Id: "PMDA-DD9998"
                Sheet_Rule_Identifier: "DD9998"
                Rule_Set: "PMDA"
                Element: "Study"
                Attribute: "StudyName"
                Applicable_Versions: ["2.1"]
                Severity: "Reject"
                Plain_Text_Rule: "Test rule."
                Message: "Missing StudyName."
                Check:
                  kind: "exists"
                  target: "@StudyName"
                """, context);
        ConformanceFinding finding = result.findings().get(0);
        assertEquals(Category.PMDA, finding.getCategory());
        assertEquals(Severity.REJECT, finding.getSeverity());
    }

}
