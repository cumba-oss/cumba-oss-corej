package net.cumba.corej.define.conformance.checks;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.tree.ElementNode;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/**
 * The five custom checks that had no scenario in this module at all: DEFINE-XML-0084 (cross-dataset
 * where clause needs a comment), PMDA-DD0150 (each declared IG standard needs its companion CT
 * standard), PMDA-DD0093 (an ARM ParameterOID must name a PARAMCD variable of an analysed dataset),
 * DEFINE-XML-0154 (variable-level Origin, with the value-level exemption) and DEFINE-XML-0249
 * (exactly one {@code nci:ExtCodeID} alias on a CT-referencing CodeList).
 *
 * <p>
 * All five are {@code custom}-kind checks: they decide a finding by walking the document rather
 * than by a declarative path, so their "return true" branches are the ones that quietly stop a rule
 * from firing. Each test below therefore pins both answers, not just the failing one.
 * </p>
 */
class DocumentStructureChecksTest
{

    private static DocumentContext context(String aMetaDataVersionBody)
    {
        String xml = """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1"
                     xmlns:arm="http://www.cdisc.org/ns/arm/v1.0">
                  <Study OID="ST.1">
                    <MetaDataVersion OID="MDV.1" Name="MDV">
                """ + aMetaDataVersionBody + """
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """;
        try (var in = new ByteArrayInputStream(xml.getBytes(UTF_8)))
        {
            return new DocumentContext(ElementNodeBuilder.build(DefineDomIo.parse(in)), "2.1", null,
                    null);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot parse test XML", e);
        }
    }


    private static ElementNode node(DocumentContext aContext, String aLocalName, String aOid)
    {
        return aContext.allNodes().stream()
                .filter(n -> aLocalName.equals(n.localName())
                        && n.attribute("OID").map(aOid::equals).orElse(false))
                .findFirst().orElseThrow();
    }

    // ------------------------------------------------------------------
    // DEFINE-XML-0084 - WhereClauseCrossDatasetCommentCheck
    // ------------------------------------------------------------------

    private static final String WHERE_CLAUSES = """
            <ItemGroupDef OID="IG.LB" Name="LB" Domain="LB">
              <ItemRef ItemOID="IT.LBTESTCD" Mandatory="Yes"/>
              <ItemRef ItemOID="IT.LBCAT" Mandatory="Yes"/>
              <ItemRef ItemOID="IT.SHARED" Mandatory="Yes"/>
            </ItemGroupDef>
            <ItemGroupDef OID="IG.VS" Name="VS" Domain="VS">
              <ItemRef ItemOID="IT.VSTESTCD" Mandatory="Yes"/>
              <ItemRef ItemOID="IT.SHARED" Mandatory="Yes"/>
            </ItemGroupDef>
            <def:WhereClauseDef OID="WC.SINGLE">
              <RangeCheck def:ItemOID="IT.LBTESTCD" Comparator="EQ"/>
            </def:WhereClauseDef>
            <def:WhereClauseDef OID="WC.SAME_DATASET">
              <RangeCheck def:ItemOID="IT.LBTESTCD" Comparator="EQ"/>
              <RangeCheck def:ItemOID="IT.LBCAT" Comparator="EQ"/>
            </def:WhereClauseDef>
            <def:WhereClauseDef OID="WC.SHARED_ITEM">
              <RangeCheck def:ItemOID="IT.SHARED" Comparator="EQ"/>
              <RangeCheck def:ItemOID="IT.LBCAT" Comparator="EQ"/>
            </def:WhereClauseDef>
            <def:WhereClauseDef OID="WC.CROSS">
              <RangeCheck def:ItemOID="IT.LBTESTCD" Comparator="EQ"/>
              <RangeCheck def:ItemOID="IT.VSTESTCD" Comparator="EQ"/>
            </def:WhereClauseDef>
            <def:WhereClauseDef OID="WC.CROSS_COMMENTED" def:CommentOID="COM.1">
              <RangeCheck def:ItemOID="IT.LBTESTCD" Comparator="EQ"/>
              <RangeCheck def:ItemOID="IT.VSTESTCD" Comparator="EQ"/>
            </def:WhereClauseDef>
            <def:WhereClauseDef OID="WC.CROSS_BLANK_COMMENT" def:CommentOID=" ">
              <RangeCheck def:ItemOID="IT.LBTESTCD" Comparator="EQ"/>
              <RangeCheck def:ItemOID="IT.VSTESTCD" Comparator="EQ"/>
            </def:WhereClauseDef>
            <def:WhereClauseDef OID="WC.ORPHAN_ITEMS">
              <RangeCheck def:ItemOID="IT.NOWHERE1" Comparator="EQ"/>
              <RangeCheck def:ItemOID="IT.NOWHERE2" Comparator="EQ"/>
            </def:WhereClauseDef>
            <def:WhereClauseDef OID="WC.ONE_ANCHORED">
              <RangeCheck def:ItemOID="IT.LBTESTCD" Comparator="EQ"/>
              <RangeCheck def:ItemOID="IT.NOWHERE1" Comparator="EQ"/>
            </def:WhereClauseDef>
            <def:WhereClauseDef OID="WC.REPEATED_ITEM">
              <RangeCheck def:ItemOID="IT.LBTESTCD" Comparator="EQ"/>
              <RangeCheck def:ItemOID="IT.LBTESTCD" Comparator="NE"/>
            </def:WhereClauseDef>
            """;

    @Test
    void dd0084FiresOnlyWhenTheReferencedItemsShareNoDataset()
    {
        WhereClauseCrossDatasetCommentCheck check = new WhereClauseCrossDatasetCommentCheck();
        DocumentContext ctx = context(WHERE_CLAUSES);
        // Fewer than two distinct items: nothing to be cross-dataset about.
        assertTrue(check.satisfied(node(ctx, "WhereClauseDef", "WC.SINGLE"), ctx));
        assertTrue(check.satisfied(node(ctx, "WhereClauseDef", "WC.REPEATED_ITEM"), ctx));
        // Both items live in LB.
        assertTrue(check.satisfied(node(ctx, "WhereClauseDef", "WC.SAME_DATASET"), ctx));
        // IT.SHARED belongs to LB and VS, so LB is still a common home: not provably cross-dataset.
        assertTrue(check.satisfied(node(ctx, "WhereClauseDef", "WC.SHARED_ITEM"), ctx));
        // Items no ItemGroupDef references are ignored, leaving fewer than two anchored items.
        assertTrue(check.satisfied(node(ctx, "WhereClauseDef", "WC.ORPHAN_ITEMS"), ctx));
        assertTrue(check.satisfied(node(ctx, "WhereClauseDef", "WC.ONE_ANCHORED"), ctx));
        // Genuinely cross-dataset and uncommented.
        assertFalse(check.satisfied(node(ctx, "WhereClauseDef", "WC.CROSS"), ctx));
        // A comment discharges it; a whitespace-only comment does not.
        assertTrue(check.satisfied(node(ctx, "WhereClauseDef", "WC.CROSS_COMMENTED"), ctx));
        assertFalse(check.satisfied(node(ctx, "WhereClauseDef", "WC.CROSS_BLANK_COMMENT"), ctx));
    }

    // ------------------------------------------------------------------
    // PMDA-DD0150 - StandardsCombinationCheck
    // ------------------------------------------------------------------


    private static boolean standardsSatisfied(String aStandardsBody)
    {
        DocumentContext ctx = context("<def:Standards>" + aStandardsBody + "</def:Standards>");
        return new StandardsCombinationCheck().satisfied(ctx.documentNode(), ctx);
    }


    @Test
    void dd0150PairsEachIgStandardWithItsCompanionCtPublishingSet()
    {
        assertTrue(standardsSatisfied("""
                <def:Standard OID="S1" Name="SDTMIG" Type="IG" Version="3.4"/>
                <def:Standard OID="S2" Name="SDTM CT" Type="CT" PublishingSet="SDTM"
                              Version="2023-12-15"/>
                """));
        assertFalse(standardsSatisfied("""
                <def:Standard OID="S1" Name="SDTMIG" Type="IG" Version="3.4"/>
                <def:Standard OID="S2" Name="SEND CT" Type="CT" PublishingSet="SEND"
                              Version="2023-12-15"/>
                """));
        // Every SENDIG spelling (SENDIG, SENDIG-AR, SENDIG-DART) needs the SEND publishing set.
        assertTrue(standardsSatisfied("""
                <def:Standard OID="S1" Name="SENDIG-DART" Type="IG" Version="1.1"/>
                <def:Standard OID="S2" Name="SEND CT" Type="CT" PublishingSet="SEND"
                              Version="2023-12-15"/>
                """));
        assertFalse(standardsSatisfied("""
                <def:Standard OID="S1" Name="SENDIG" Type="IG" Version="3.1"/>
                <def:Standard OID="S2" Name="SDTM CT" Type="CT" PublishingSet="SDTM"
                              Version="2023-12-15"/>
                """));
        // ADaMIG accepts either SDTM or ADaM controlled terminology.
        assertTrue(standardsSatisfied("""
                <def:Standard OID="S1" Name="ADaMIG" Type="IG" Version="1.3"/>
                <def:Standard OID="S2" Name="ADaM CT" Type="CT" PublishingSet="ADaM"
                              Version="2023-12-15"/>
                """));
        assertTrue(standardsSatisfied("""
                <def:Standard OID="S1" Name="ADaMIG" Type="IG" Version="1.3"/>
                <def:Standard OID="S2" Name="SDTM CT" Type="CT" PublishingSet="SDTM"
                              Version="2023-12-15"/>
                """));
        assertFalse(standardsSatisfied("""
                <def:Standard OID="S1" Name="ADaMIG" Type="IG" Version="1.3"/>
                <def:Standard OID="S2" Name="SEND CT" Type="CT" PublishingSet="SEND"
                              Version="2023-12-15"/>
                """));
    }


    @Test
    void dd0150IsSilentForDocumentsDeclaringNoRecognisedIgStandard()
    {
        // No def:Standards at all, an IG the rule does not know, a CT-only declaration, a nameless
        // IG, and a CT standard with no PublishingSet: none of these is a DD0150 finding.
        DocumentContext empty = context("<ItemGroupDef OID=\"IG.DM\" Name=\"DM\"/>");
        assertTrue(new StandardsCombinationCheck().satisfied(empty.documentNode(), empty));
        assertTrue(standardsSatisfied("""
                <def:Standard OID="S1" Name="CDASHIG" Type="IG" Version="2.2"/>
                """));
        assertTrue(standardsSatisfied("""
                <def:Standard OID="S1" Name="SDTM CT" Type="CT" PublishingSet="SDTM"
                              Version="2023-12-15"/>
                """));
        assertTrue(standardsSatisfied("""
                <def:Standard OID="S1" Type="IG" Version="3.4"/>
                """));
        assertFalse(standardsSatisfied("""
                <def:Standard OID="S1" Name="SDTMIG" Type="IG" Version="3.4"/>
                <def:Standard OID="S2" Name="SDTM CT" Type="CT" Version="2023-12-15"/>
                """));
    }

    // ------------------------------------------------------------------
    // PMDA-DD0093 - ArmParameterOidUsageCheck
    // ------------------------------------------------------------------

    private static final String ARM_RESULTS = """
            <ItemGroupDef OID="IG.ADLB" Name="ADLB">
              <ItemRef ItemOID="IT.PARAMCD" Mandatory="Yes"/>
            </ItemGroupDef>
            <ItemGroupDef OID="IG.ADSL" Name="ADSL">
              <ItemRef ItemOID="IT.USUBJID" Mandatory="Yes"/>
            </ItemGroupDef>
            <ItemDef OID="IT.PARAMCD" Name="PARAMCD"/>
            <ItemDef OID="IT.USUBJID" Name="USUBJID"/>
            <arm:AnalysisResult OID="AR.OK" ParameterOID="IT.PARAMCD">
              <arm:AnalysisDatasets>
                <arm:AnalysisDataset ItemGroupOID="IG.ADSL"/>
                <arm:AnalysisDataset ItemGroupOID="IG.ADLB"/>
              </arm:AnalysisDatasets>
            </arm:AnalysisResult>
            <arm:AnalysisResult OID="AR.NOT_A_MEMBER" ParameterOID="IT.PARAMCD">
              <arm:AnalysisDatasets>
                <arm:AnalysisDataset ItemGroupOID="IG.ADSL"/>
              </arm:AnalysisDatasets>
            </arm:AnalysisResult>
            <arm:AnalysisResult OID="AR.DANGLING" ParameterOID="IT.NOWHERE">
              <arm:AnalysisDatasets>
                <arm:AnalysisDataset ItemGroupOID="IG.ADLB"/>
              </arm:AnalysisDatasets>
            </arm:AnalysisResult>
            <arm:AnalysisResult OID="AR.NOT_PARAMCD" ParameterOID="IT.USUBJID">
              <arm:AnalysisDatasets>
                <arm:AnalysisDataset ItemGroupOID="IG.ADSL"/>
              </arm:AnalysisDatasets>
            </arm:AnalysisResult>
            <arm:AnalysisResult OID="AR.DANGLING_DATASET" ParameterOID="IT.PARAMCD">
              <arm:AnalysisDatasets>
                <arm:AnalysisDataset ItemGroupOID="IG.NOWHERE"/>
              </arm:AnalysisDatasets>
            </arm:AnalysisResult>
            <arm:AnalysisResult OID="AR.NO_DATASETS" ParameterOID="IT.PARAMCD"/>
            <arm:AnalysisResult OID="AR.NO_PARAMETER"/>
            <arm:AnalysisResult OID="AR.BLANK_PARAMETER" ParameterOID=" ">
              <arm:AnalysisDatasets>
                <arm:AnalysisDataset ItemGroupOID="IG.ADLB"/>
              </arm:AnalysisDatasets>
            </arm:AnalysisResult>
            """;

    @Test
    void dd0093RequiresTheParameterOidToNameAParamcdOfAnAnalysedDataset()
    {
        ArmParameterOidUsageCheck check = new ArmParameterOidUsageCheck();
        DocumentContext ctx = context(ARM_RESULTS);
        assertTrue(check.satisfied(node(ctx, "AnalysisResult", "AR.OK"), ctx));
        // PARAMCD exists but no analysed dataset carries it: the result is not BDS-anchored.
        assertFalse(check.satisfied(node(ctx, "AnalysisResult", "AR.NOT_A_MEMBER"), ctx));
        // A ParameterOID pointing nowhere, or at a non-PARAMCD variable, is improper use.
        assertFalse(check.satisfied(node(ctx, "AnalysisResult", "AR.DANGLING"), ctx));
        assertFalse(check.satisfied(node(ctx, "AnalysisResult", "AR.NOT_PARAMCD"), ctx));
        // An ItemGroupOID that resolves to nothing cannot satisfy the membership test.
        assertFalse(check.satisfied(node(ctx, "AnalysisResult", "AR.DANGLING_DATASET"), ctx));
        assertFalse(check.satisfied(node(ctx, "AnalysisResult", "AR.NO_DATASETS"), ctx));
        // The rule's when-guard already excludes these; the check treats them as out of reach.
        assertTrue(check.satisfied(node(ctx, "AnalysisResult", "AR.NO_PARAMETER"), ctx));
        assertTrue(check.satisfied(node(ctx, "AnalysisResult", "AR.BLANK_PARAMETER"), ctx));
    }

    // ------------------------------------------------------------------
    // DEFINE-XML-0154 - VariableLevelOriginCheck
    // ------------------------------------------------------------------


    @Test
    void dd0154ExemptsOnlyValueLevelMembersFromTheVariableLevelOrigin()
    {
        VariableLevelOriginCheck check = new VariableLevelOriginCheck();
        DocumentContext ctx = context("""
                <ItemDef OID="IT.WITH_ORIGIN" Name="AGE">
                  <def:Origin Type="Collected"/>
                </ItemDef>
                <ItemDef OID="IT.VALUE_LEVEL" Name="LBSTRESN"/>
                <ItemDef OID="IT.NO_ORIGIN" Name="LBORRES"/>
                <ItemDef Name="IT_WITHOUT_OID"/>
                <def:ValueListDef OID="VL.1">
                  <ItemRef ItemOID="IT.VALUE_LEVEL" Mandatory="Yes"/>
                </def:ValueListDef>
                <ItemGroupDef OID="IG.LB" Name="LB">
                  <ItemRef ItemOID="IT.NO_ORIGIN" Mandatory="Yes"/>
                </ItemGroupDef>
                """);
        assertTrue(check.satisfied(node(ctx, "ItemDef", "IT.WITH_ORIGIN"), ctx));
        // Origin obligation lives at the value level for a ValueListDef member.
        assertTrue(check.satisfied(node(ctx, "ItemDef", "IT.VALUE_LEVEL"), ctx));
        // An ItemGroupDef ItemRef is NOT a value-level reference: this one must fire.
        assertFalse(check.satisfied(node(ctx, "ItemDef", "IT.NO_ORIGIN"), ctx));
        ElementNode noOid = ctx.allNodes().stream()
                .filter(n -> "ItemDef".equals(n.localName()) && n.attribute("OID").isEmpty())
                .findFirst().orElseThrow();
        // No identity to match a ValueListDef against: sheet id 137's defect, not this one's.
        assertTrue(check.satisfied(noOid, ctx));
    }

    // ------------------------------------------------------------------
    // DEFINE-XML-0249 - CodeListStandardAliasCheck
    // ------------------------------------------------------------------


    @Test
    void dd0249RequiresExactlyOneNciExtCodeIdAlias()
    {
        CodeListStandardAliasCheck check = new CodeListStandardAliasCheck();
        DocumentContext ctx = context("""
                <CodeList OID="CL.ONE" Name="Sex" def:StandardOID="STD.CT">
                  <Alias Context="nci:ExtCodeID" Name="C66731"/>
                  <Alias Context="something else" Name="ignored"/>
                </CodeList>
                <CodeList OID="CL.NONE" Name="Unit" def:StandardOID="STD.CT">
                  <Alias Context="something else" Name="ignored"/>
                </CodeList>
                <CodeList OID="CL.TWO" Name="Race" def:StandardOID="STD.CT">
                  <Alias Context="nci:ExtCodeID" Name="C74457"/>
                  <Alias Context="nci:ExtCodeID" Name="C17049"/>
                </CodeList>
                <CodeList OID="CL.NO_CONTEXT" Name="Frequency" def:StandardOID="STD.CT">
                  <Alias Name="C71113"/>
                </CodeList>
                """);
        assertTrue(check.satisfied(node(ctx, "CodeList", "CL.ONE"), ctx));
        assertFalse(check.satisfied(node(ctx, "CodeList", "CL.NONE"), ctx));
        // Two aliases is as much a violation as none: the sheet's formal rule is count() = 1.
        assertFalse(check.satisfied(node(ctx, "CodeList", "CL.TWO"), ctx));
        assertFalse(check.satisfied(node(ctx, "CodeList", "CL.NO_CONTEXT"), ctx));
    }

    // ------------------------------------------------------------------
    // PMDA-DD0114 - how the observation class is read off a split part
    // ------------------------------------------------------------------


    /**
     * {@code def:Class} is an attribute in Define-XML 2.0 and a child element in 2.1, and
     * {@link SplitDatasets#datasetClass} dispatches on document shape rather than on a version
     * gate. A blank attribute must fall through to the child, and a child without a usable
     * {@code Name} must be walked past — otherwise the class reads as absent and DD0114 goes quiet
     * on a split dataset that really is outside the general observation classes.
     */
    @Test
    void dd0114ReadsTheClassFromEitherShapeAndSkipsBlankOnes()
    {
        SplitDatasetClassCheck check = new SplitDatasetClassCheck();
        DocumentContext ctx = context("""
                <ItemGroupDef OID="IG.QS1" Name="QSCG" Domain="QS" def:Class="FINDINGS"/>
                <ItemGroupDef OID="IG.QS2" Name="QSMD" Domain="QS" def:Class=" ">
                  <def:Class Name="FINDINGS"/>
                </ItemGroupDef>
                <ItemGroupDef OID="IG.RL1" Name="RELREC1" Domain="RL">
                  <def:Class/>
                  <def:Class Name="RELATIONSHIP"/>
                </ItemGroupDef>
                <ItemGroupDef OID="IG.RL2" Name="RELREC2" Domain="RL"/>
                """);
        // 2.0 attribute shape.
        assertTrue(check.satisfied(node(ctx, "ItemGroupDef", "IG.QS1"), ctx));
        // Blank attribute falls through to the 2.1 child element.
        assertTrue(check.satisfied(node(ctx, "ItemGroupDef", "IG.QS2"), ctx));
        // First Class child carries no Name; the second does, and RELATIONSHIP is not a general
        // observation class, so the rule fires.
        assertFalse(check.satisfied(node(ctx, "ItemGroupDef", "IG.RL1"), ctx));
        // No class at all is DD0054's beat, not this row's.
        assertTrue(check.satisfied(node(ctx, "ItemGroupDef", "IG.RL2"), ctx));
    }

}
