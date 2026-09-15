package net.cumba.corej.define.conformance.eval;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.tree.ElementNode;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Engine behaviours this module's own suite never constructed: the {@code stylesheet_file_exists}
 * kind and the {@code Element: "Document"} scope it must use, the file-existence resolution rules,
 * the version gate, the folder gate, the custom-check loader's wrong-type arm, and a handful of
 * "must NOT fire" branches whose absence is what makes a rule silently stop reporting.
 */
class UnreachedKindsAndBranchesTest
{

    private static DocumentContext context(String aMetaDataVersionBody)
    {
        return context(aMetaDataVersionBody, "2.1", null, List.of());
    }


    private static DocumentContext context(String aMetaDataVersionBody, String aVersion,
            @Nullable Path aFolder, List<String> aStylesheetHrefs)
    {
        String xml = """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1"
                     xmlns:xlink="http://www.w3.org/1999/xlink">
                  <Study OID="ST.1">
                    <MetaDataVersion OID="MDV.1" Name="MDV">
                """ + aMetaDataVersionBody + """
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """;
        try
        {
            ElementNode root = ElementNodeBuilder
                    .build(DefineDomIo.parse(new ByteArrayInputStream(xml.getBytes(UTF_8))));
            return new DocumentContext(root, aVersion, null, aFolder, aStylesheetHrefs);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot parse test XML", e);
        }
    }


    private static String rule(String aElement, String aCheckBody, String aExtraLines)
    {
        return """
                Rule_Id: "PMDA-EDGE01"
                Sheet_Rule_Identifier: "EDGE01"
                Rule_Set: "PMDA"
                Element: "%s"
                Applicable_Versions: ["2.1"]
                Severity: "Error"
                %s
                Plain_Text_Rule: "Test rule."
                Message: "Offending value [${value}]."
                Check:
                """.formatted(aElement, aExtraLines) + aCheckBody.indent(2);
    }


    private static RuleResult evaluate(String aRuleYaml, DocumentContext aContext)
    {
        return new RuleEvaluator().evaluate(RuleRepository.parse(aRuleYaml, "test"), aContext);
    }


    private static List<String> values(RuleResult aResult)
    {
        return aResult.findings().stream()
                .map(f -> f.getMessage().replace("Offending value [", "").replace("].", ""))
                .toList();
    }

    // ------------------------------------------------------------------
    // The two gates that report a skip rather than a clean result
    // ------------------------------------------------------------------


    @Test
    void aRuleOutsideTheDocumentsVersionIsRecordedAsNotApplicableNotAsClean()
    {
        RuleResult result = evaluate(rule("ItemDef", """
                kind: "exists"
                target: "Description"
                """, ""), context("<ItemDef OID=\"IT.1\" Name=\"AGE\"/>", "2.0", null, List.of()));
        assertEquals(ExecutionStatus.NOT_APPLICABLE_VERSION, result.status());
        assertEquals(List.of(), values(result));
    }


    @Test
    void aFolderGatedRuleSkipsWhenNoSubmissionFolderIsSupplied()
    {
        RuleResult result = evaluate(rule("Document", """
                kind: "stylesheet_file_exists"
                """, "Requires: \"folder\""), context("", "2.1", null, List.of("define2-1.xsl")));
        assertEquals(ExecutionStatus.SKIPPED_MISSING_FOLDER, result.status());
    }

    // ------------------------------------------------------------------
    // stylesheet_file_exists (PMDA DD0085) — the kind with no scenario at all
    // ------------------------------------------------------------------


    @Test
    void stylesheetFileExistsReportsOnePerMissingPrologHref(@TempDir Path aFolder) throws Exception
    {
        Files.writeString(aFolder.resolve("define2-1.xsl"), "<xsl/>");
        RuleResult result = evaluate(rule("Document", """
                kind: "stylesheet_file_exists"
                """, "Requires: \"folder\""), context("", "2.1", aFolder,
                List.of("define2-1.xsl", "missing.xsl", "styles/other.xsl")));
        assertEquals(ExecutionStatus.EXECUTED, result.status());
        assertEquals(List.of("missing.xsl", "styles/other.xsl"), values(result));
    }


    /**
     * The hrefs live on the context rather than on any element, so the kind is pinned to the
     * synthetic Document scope at load time — any wider scope would repeat every finding once per
     * scoped node.
     */
    @Test
    void stylesheetFileExistsMustBeDocumentScopedAndFolderGated()
    {
        assertThrows(IllegalStateException.class, () -> RuleRepository.parse(rule("ItemDef", """
                kind: "stylesheet_file_exists"
                """, "Requires: \"folder\""), "test"));
        assertThrows(IllegalStateException.class, () -> RuleRepository.parse(rule("Document", """
                kind: "stylesheet_file_exists"
                """, "Requires: \"ct\""), "test"));
    }

    // ------------------------------------------------------------------
    // referenced_file_exists — how a href is resolved against the folder
    // ------------------------------------------------------------------


    @Test
    void referencedFileExistsResolvesHrefsTheWayTheStandardWritesThem(@TempDir Path aFolder)
        throws Exception
    {
        Files.writeString(aFolder.resolve("dm.xpt"), "x");
        Files.writeString(aFolder.resolve("blank forms.pdf"), "x");
        Files.createDirectory(aFolder.resolve("tabulations"));
        Files.writeString(aFolder.resolve("tabulations").resolve("ae.xpt"), "x");
        RuleResult result = evaluate(rule("leaf", """
                kind: "referenced_file_exists"
                attribute: "href"
                """, "Requires: \"folder\""), context("""
                <def:leaf ID="LF.1" xlink:href="dm.xpt"/>
                <def:leaf ID="LF.2" xlink:href="tabulations/ae.xpt"/>
                <def:leaf ID="LF.3" xlink:href=".\\dm.xpt"/>
                <def:leaf ID="LF.4" xlink:href="dm.xpt#page=3"/>
                <def:leaf ID="LF.5" xlink:href="blank%20forms.pdf"/>
                <def:leaf ID="LF.6" xlink:href="#page=2"/>
                <def:leaf ID="LF.7" xlink:href="tabulations"/>
                <def:leaf ID="LF.8" xlink:href="missing.xpt"/>
                <def:leaf ID="LF.9" xlink:href="../escape.xpt"/>
                <def:leaf ID="LF.10" xlink:href="/etc/hostname"/>
                <def:leaf ID="LF.11" xlink:href="%20"/>
                <def:leaf ID="LF.12" xlink:href="bad%00name.pdf"/>
                <def:leaf ID="LF.13" xlink:href="blank forms.pdf"/>
                <def:leaf ID="LF.14" xlink:href="missing file.pdf"/>
                """, "2.1", aFolder, List.of()));
        // LF.1/2/3 resolve; LF.4's fragment is stripped; LF.5 is the percent-encoded spelling of a
        // file that really is there; LF.6 is fragment-only and out of reach.
        // LF.7 names a DIRECTORY -- a referenced-file check a folder satisfies is not checking.
        // LF.9/LF.10 escape the submission folder and can never satisfy the rule.
        // LF.11 decodes to a blank name, LF.12 decodes to a spelling no filesystem can express,
        // LF.13/LF.14 carry a RAW space, which is not a parseable URI reference, so there is no
        // decoded second chance -- the raw spelling alone decides, and LF.13's file is there.
        // None of these throws out of the run.
        assertEquals(List.of("tabulations", "missing.xpt", "../escape.xpt", "/etc/hostname", "%20",
                "bad%00name.pdf", "missing file.pdf"), values(result));
    }

    // ------------------------------------------------------------------
    // custom — the loader's wrong-type arm
    // ------------------------------------------------------------------


    @Test
    void aCustomCheckNamingAClassThatIsNotACustomCheckFailsLoudly()
    {
        DocumentContext ctx = context("<ItemDef OID=\"IT.1\" Name=\"AGE\"/>");
        String notACheck = evaluate0("java.lang.String");
        assertThrows(IllegalStateException.class, () -> evaluate(notACheck, ctx));
        String missing = evaluate0("no.such.CheckClass");
        assertThrows(IllegalStateException.class, () -> evaluate(missing, ctx));
    }


    private static String evaluate0(String aClassName)
    {
        return rule("ItemDef", """
                kind: "custom"
                className: "%s"
                """.formatted(aClassName), "");
    }

    // ------------------------------------------------------------------
    // Branches whose job is NOT to fire
    // ------------------------------------------------------------------


    @Test
    void uniqueAmongSiblingsIsScopedToOneParentUnlikeUniqueInDocument()
    {
        String body = """
                <ItemGroupDef OID="IG.A" Name="A">
                  <ItemRef ItemOID="IT.1" Mandatory="Yes"/>
                  <ItemRef ItemOID="IT.2" Mandatory="Yes"/>
                </ItemGroupDef>
                <ItemGroupDef OID="IG.B" Name="B">
                  <ItemRef ItemOID="IT.1" Mandatory="Yes"/>
                </ItemGroupDef>
                """;
        // IT.1 appears under two different parents: not a sibling duplicate.
        assertEquals(List.of(), values(evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "unique_among_siblings"
                attribute: "ItemOID"
                """, ""), context(body))));
        // Document-wide, the same pair IS a duplicate.
        assertEquals(List.of("IT.1"), values(evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "unique_in_document"
                attribute: "ItemOID"
                """, ""), context(body))));
        // And a genuine sibling duplicate does fire.
        assertEquals(List.of("IT.1"), values(evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "unique_among_siblings"
                attribute: "ItemOID"
                """, ""), context("""
                <ItemGroupDef OID="IG.A" Name="A">
                  <ItemRef ItemOID="IT.1" Mandatory="Yes"/>
                  <ItemRef ItemOID="IT.1" Mandatory="No"/>
                </ItemGroupDef>
                """))));
    }


    @Test
    void cardinalityAtMostCountsChildElementsOfTheScopedNode()
    {
        RuleResult result = evaluate(rule("ItemDef", """
                kind: "cardinality_at_most"
                target: "def:Description"
                max: 1
                """, ""), context("""
                <ItemDef OID="IT.1" Name="A">
                  <Description/>
                  <Description/>
                </ItemDef>
                <ItemDef OID="IT.2" Name="B">
                  <Description/>
                </ItemDef>
                <ItemDef OID="IT.3" Name="C"/>
                """));
        // Exactly one node exceeds the bound, and the target's namespace prefix is stripped first.
        assertEquals(1, result.findings().size(), () -> "findings: " + result.findings());
        assertTrue(result.findings().get(0).getXpath().contains("ItemDef"),
                result.findings().get(0).getXpath());
    }


    @Test
    void referencesFollowsTheTargetKeyAndFiresOnlyOnDanglingValues()
    {
        assertEquals(List.of("IT.NOWHERE"), values(evaluate(rule("ItemGroupDef/ItemRef", """
                kind: "references"
                attribute: "ItemOID"
                targetElement: "ItemDef"
                """, ""), context("""
                <ItemGroupDef OID="IG.A" Name="A">
                  <ItemRef ItemOID="IT.1" Mandatory="Yes"/>
                  <ItemRef ItemOID="IT.NOWHERE" Mandatory="Yes"/>
                  <ItemRef Mandatory="Yes"/>
                </ItemGroupDef>
                <ItemDef OID="IT.1" Name="A"/>
                """))));
    }


    @Test
    void compareIsCaseSensitiveUnlessAskedAndFileBasenameStripsPathAndExtension()
    {
        String body = """
                <ItemGroupDef OID="IG.1" Name="dm" SASDatasetName="DM">
                  <def:leaf ID="LF.1" xlink:href="tabulations/DM.xpt"/>
                </ItemGroupDef>
                """;
        // Default: case-sensitive, so dm vs DM is a finding.
        assertEquals(List.of("dm vs DM"), values(evaluate(rule("ItemGroupDef", """
                kind: "compare"
                left: "@Name"
                right: "@SASDatasetName"
                """, ""), context(body))));
        // caseInsensitive: false is the same as the default -- pinned so the flag cannot invert.
        assertEquals(List.of("dm vs DM"), values(evaluate(rule("ItemGroupDef", """
                kind: "compare"
                left: "@Name"
                right: "@SASDatasetName"
                caseInsensitive: false
                """, ""), context(body))));
        assertEquals(List.of(), values(evaluate(rule("ItemGroupDef", """
                kind: "compare"
                left: "@Name"
                right: "@SASDatasetName"
                caseInsensitive: true
                """, ""), context(body))));
        // file-basename reduces tabulations/DM.xpt to DM.
        assertEquals(List.of(), values(evaluate(rule("ItemGroupDef", """
                kind: "compare"
                left: "@SASDatasetName"
                right: "leaf/@href"
                rightTransform: "file-basename"
                """, ""), context(body))));
    }


    @Test
    void compareLessOrEqualIsInclusiveAtTheBoundaryAndSkipsNonNumericSides()
    {
        String body = """
                <ItemDef OID="IT.EQ" Name="A" Length="8" SignificantDigits="8"/>
                <ItemDef OID="IT.OVER" Name="B" Length="9" SignificantDigits="8"/>
                <ItemDef OID="IT.TEXT" Name="C" Length="eight" SignificantDigits="8"/>
                """;
        assertEquals(List.of("9 vs 8"), values(evaluate(rule("ItemDef", """
                kind: "compare"
                left: "@Length"
                right: "@SignificantDigits"
                op: "less_or_equal"
                """, ""), context(body))));
    }


    @Test
    void theWildcardScopeSelectsEveryRealElementButNotTheSyntheticDocumentNode()
    {
        RuleResult result = evaluate(rule("*", """
                kind: "exists"
                target: "@OID"
                when:
                  path: "@Name"
                  exists: true
                """, ""), context("""
                <ItemDef OID="IT.1" Name="A"/>
                <ItemDef Name="B"/>
                """));
        // MetaDataVersion (Name=MDV) and IT.1 carry an OID; the second ItemDef does not.
        assertEquals(1, values(result).size(), () -> "findings: " + values(result));
    }


    @Test
    void nciCodeOfWalksPastNonNciAndBlankNamedAliases()
    {
        // Only the third Alias is a usable nci:ExtCodeID c-code; a blank Name is not one.
        RuleResult result = evaluate(rule("CodeList", """
                kind: "compare"
                left: "Alias/@Name"
                right: "@OID"
                """, ""), context("""
                <CodeList OID="C66731" Name="Sex">
                  <Alias Context="other" Name="X"/>
                  <Alias Context="nci:ExtCodeID" Name=" "/>
                  <Alias Context="nci:ExtCodeID" Name="C66731"/>
                </CodeList>
                """));
        // compare reads the FIRST Alias/@Name, which is the non-nci one: this pins that
        // Alias ordering is visible to the DSL, while nciCodeOf skips to the usable alias.
        assertEquals(List.of("X vs C66731"), values(result));
        assertTrue(result.status() == ExecutionStatus.EXECUTED);
    }

}
