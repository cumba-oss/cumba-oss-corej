package net.cumba.corej.define.conformance.ordering;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.List;
import java.util.Objects;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.report.Category;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.Severity;
import net.cumba.corej.define.conformance.tree.ElementNode;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/**
 * {@link GlobalElementOrderingCheck}: canonical order accepted (2.1 and 2.0 shapes), synthetic
 * sibling swaps flagged once per offending child with dual-id emission, unknown / vendor-extension
 * elements ignored as anchors, and {@code xs:choice} members rank-tied.
 */
class GlobalElementOrderingCheckTest
{

    private final GlobalElementOrderingCheck check = new GlobalElementOrderingCheck();

    private static ElementNode fixture(String aName)
    {
        try (InputStream in = GlobalElementOrderingCheckTest.class
                .getResourceAsStream("/fixtures/" + aName))
        {
            return ElementNodeBuilder.build(DefineDomIo.parse(Objects.requireNonNull(in, aName)));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot load fixture " + aName, e);
        }
    }


    private static ElementNode parse(String aXml)
    {
        try (var in = new ByteArrayInputStream(aXml.getBytes(UTF_8)))
        {
            return ElementNodeBuilder.build(DefineDomIo.parse(in));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot parse test XML", e);
        }
    }


    /** One defect = exactly two findings; this asserts the invariant pair shape. */
    private static void assertDualEmission(List<ConformanceFinding> aPair, String aElement,
            String aParentXpath)
    {
        assertEquals(2, aPair.size());

        ConformanceFinding cdisc = aPair.get(0);
        assertEquals(GlobalElementOrderingCheck.CDISC_RULE_ID, cdisc.getRuleId());
        assertEquals(Category.SCHEMA, cdisc.getCategory());
        assertEquals(Severity.ERROR, cdisc.getSeverity());
        assertEquals(aElement, cdisc.getElement());
        assertEquals(aParentXpath, cdisc.getXpath());
        assertTrue(
                cdisc.getMessage()
                        .contains("Element " + aElement + " is out of the canonical order under"),
                cdisc.getMessage());
        assertTrue(cdisc.getMessage().contains(aParentXpath), cdisc.getMessage());

        ConformanceFinding pmda = aPair.get(1);
        assertEquals(GlobalElementOrderingCheck.PMDA_RULE_ID, pmda.getRuleId());
        assertEquals(Category.PMDA, pmda.getCategory());
        assertEquals(Severity.ERROR, pmda.getSeverity());
        assertEquals(aElement, pmda.getElement());
        assertEquals(aParentXpath, pmda.getXpath());
        assertTrue(pmda.getMessage().startsWith("Element in wrong position within Define.xml"),
                pmda.getMessage());
        assertTrue(pmda.getMessage().contains(aParentXpath), pmda.getMessage());
    }


    @Test
    void cleanDocumentInCanonicalOrderHasNoFindings()
    {
        assertEquals(List.of(), check.check(fixture("pilot-clean-21.xml")));
    }


    @Test
    void syntheticSwapsAreFlaggedOncePerOffendingChildWithDualIds()
    {
        List<ConformanceFinding> findings = check.check(fixture("ordering-violations-21.xml"));

        // Three defects, two findings each, in document order of the offending child.
        assertEquals(6, findings.size());

        String mdv = "/ODM/Study[@OID='ST.ORD']/MetaDataVersion[@OID='MDV.1']";
        // Defect 1: ItemGroupDef written before def:ValueListDef — the later, lower-ranked
        // sibling (ValueListDef) is the reported child.
        assertDualEmission(findings.subList(0, 2), "ValueListDef", mdv);
        // Defect 2: CodeListRef written before Description inside ItemDef.
        assertDualEmission(findings.subList(2, 4), "Description",
                mdv + "/ItemDef[@OID='IT.DM.STUDYID']");
        // Defect 3: Alias written before Decode inside CodeListItem.
        assertDualEmission(findings.subList(4, 6), "Decode",
                mdv + "/CodeList[@OID='CL.X']/CodeListItem");
    }


    @Test
    void unknownAndExtensionElementsAreIgnoredAsAnchors()
    {
        // arm:AnalysisResultDisplays mid-MetaDataVersion and vendor vx:* elements interleaved
        // everywhere — none may raise the running rank or be reported.
        assertEquals(List.of(), check.check(fixture("ordering-extensions-21.xml")));
    }


    @Test
    void define20ShapedCleanDocumentHasNoFindings()
    {
        // The 2.0-vs-2.1 content-model differences are presence-only, so the single merged
        // table accepts a canonical 2.0 document unchanged (no version gate needed).
        assertEquals(List.of(), check.check(fixture("ordering-clean-20.xml")));
    }


    @Test
    void define20SwapIsStillFlagged()
    {
        ElementNode root = parse("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.0">
                  <Study OID="ST.20">
                    <GlobalVariables>
                      <StudyName>S</StudyName>
                      <StudyDescription>D</StudyDescription>
                      <ProtocolName>P</ProtocolName>
                    </GlobalVariables>
                    <MetaDataVersion OID="MDV.1" Name="M" def:DefineVersion="2.0.0">
                      <def:ValueListDef OID="VL.1">
                        <ItemRef ItemOID="IT.X" Mandatory="No"/>
                      </def:ValueListDef>
                      <def:AnnotatedCRF>
                        <def:DocumentRef leafID="LF.ACRF"/>
                      </def:AnnotatedCRF>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """);
        List<ConformanceFinding> findings = check.check(root);
        assertEquals(2, findings.size());
        assertDualEmission(findings, "AnnotatedCRF",
                "/ODM/Study[@OID='ST.20']/MetaDataVersion[@OID='MDV.1']");
    }


    @Test
    void choiceMembersShareOneRankAndRepeatedChildrenAreFine()
    {
        // FormalExpression before CheckValue (xs:choice — order carries no meaning), repeated
        // RangeChecks, repeated ItemRefs: all legal.
        ElementNode root = parse("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <Study OID="ST.CH">
                    <GlobalVariables>
                      <StudyName>S</StudyName>
                      <StudyDescription>D</StudyDescription>
                      <ProtocolName>P</ProtocolName>
                    </GlobalVariables>
                    <MetaDataVersion OID="MDV.1" Name="M" def:DefineVersion="2.1.0">
                      <def:WhereClauseDef OID="WC.1">
                        <RangeCheck Comparator="EQ" SoftHard="Soft" def:ItemOID="IT.A">
                          <FormalExpression Context="X">a=1</FormalExpression>
                          <CheckValue>1</CheckValue>
                        </RangeCheck>
                        <RangeCheck Comparator="EQ" SoftHard="Soft" def:ItemOID="IT.B">
                          <CheckValue>1</CheckValue>
                          <CheckValue>2</CheckValue>
                        </RangeCheck>
                      </def:WhereClauseDef>
                      <ItemGroupDef OID="IG.DM" Name="DM" Repeating="No" IsReferenceData="No"
                                    Purpose="Tabulation" def:Structure="One record per subject">
                        <ItemRef ItemOID="IT.A" Mandatory="Yes"/>
                        <ItemRef ItemOID="IT.B" Mandatory="No"/>
                      </ItemGroupDef>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """);
        assertEquals(List.of(), check.check(root));
    }


    @Test
    void choiceRankTieStillOrdersAgainstTheRestOfTheSequence()
    {
        // CheckValue AFTER MeasurementUnitRef breaks the sequence even though CheckValue /
        // FormalExpression are mutually unordered.
        ElementNode root = parse("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <Study OID="ST.CH2">
                    <GlobalVariables>
                      <StudyName>S</StudyName>
                      <StudyDescription>D</StudyDescription>
                      <ProtocolName>P</ProtocolName>
                    </GlobalVariables>
                    <MetaDataVersion OID="MDV.1" Name="M" def:DefineVersion="2.1.0">
                      <def:WhereClauseDef OID="WC.1">
                        <RangeCheck Comparator="EQ" SoftHard="Soft" def:ItemOID="IT.A">
                          <MeasurementUnitRef MeasurementUnitOID="MU.1"/>
                          <CheckValue>1</CheckValue>
                        </RangeCheck>
                      </def:WhereClauseDef>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """);
        List<ConformanceFinding> findings = check.check(root);
        assertEquals(2, findings.size());
        assertDualEmission(findings, "CheckValue",
                "/ODM/Study[@OID='ST.CH2']/MetaDataVersion[@OID='MDV.1']"
                        + "/WhereClauseDef[@OID='WC.1']/RangeCheck");
    }


    @Test
    void everyOffendingChildIsReportedNotJustTheFirst()
    {
        // MethodDef children DocumentRef, Description, FormalExpression: both Description and
        // FormalExpression rank below the already-seen DocumentRef — two defects, four findings.
        ElementNode root = parse("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <Study OID="ST.MM">
                    <GlobalVariables>
                      <StudyName>S</StudyName>
                      <StudyDescription>D</StudyDescription>
                      <ProtocolName>P</ProtocolName>
                    </GlobalVariables>
                    <MetaDataVersion OID="MDV.1" Name="M" def:DefineVersion="2.1.0">
                      <MethodDef OID="MT.1" Name="M1" Type="Computation">
                        <def:DocumentRef leafID="LF.1"/>
                        <Description>
                          <TranslatedText xml:lang="en">d</TranslatedText>
                        </Description>
                        <FormalExpression Context="X">a=1</FormalExpression>
                      </MethodDef>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """);
        List<ConformanceFinding> findings = check.check(root);
        assertEquals(4, findings.size());
        String methodDef = "/ODM/Study[@OID='ST.MM']/MetaDataVersion[@OID='MDV.1']"
                + "/MethodDef[@OID='MT.1']";
        assertDualEmission(findings.subList(0, 2), "Description", methodDef);
        assertDualEmission(findings.subList(2, 4), "FormalExpression", methodDef);
    }


    @Test
    void odmAndGlobalVariablesOrderIsChecked()
    {
        ElementNode root = parse("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3">
                  <AdminData/>
                  <Study OID="ST.GV">
                    <GlobalVariables>
                      <ProtocolName>P</ProtocolName>
                      <StudyName>S</StudyName>
                    </GlobalVariables>
                  </Study>
                </ODM>
                """);
        List<ConformanceFinding> findings = check.check(root);
        assertEquals(4, findings.size());
        assertDualEmission(findings.subList(0, 2), "Study", "/ODM");
        assertDualEmission(findings.subList(2, 4), "StudyName",
                "/ODM/Study[@OID='ST.GV']/GlobalVariables");
    }


    @Test
    void unknownParentsAreNotChecked()
    {
        // Known-name children shuffled INSIDE a vendor wrapper: the wrapper has no table entry,
        // so its children go unchecked.
        ElementNode root = parse("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:vx="http://example.org/vendor-extension">
                  <Study OID="ST.UP">
                    <GlobalVariables>
                      <StudyName>S</StudyName>
                      <StudyDescription>D</StudyDescription>
                      <ProtocolName>P</ProtocolName>
                    </GlobalVariables>
                    <vx:Wrapper>
                      <ProtocolName>P</ProtocolName>
                      <StudyName>S</StudyName>
                    </vx:Wrapper>
                  </Study>
                </ODM>
                """);
        assertEquals(List.of(), check.check(root));
    }

}
