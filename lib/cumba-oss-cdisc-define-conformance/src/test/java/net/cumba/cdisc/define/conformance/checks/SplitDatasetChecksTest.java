package net.cumba.cdisc.define.conformance.checks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.tree.ElementNode;
import net.cumba.cdisc.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/**
 * Edge-branch coverage for the split-dataset custom checks (PMDA DD0049/DD0050/DD0063/DD0114/
 * DD0115) beyond the end-to-end fixtures of {@code PmdaP5bRulesTest}: blank/absent Domain,
 * SUPP/SQAP exclusions, absent SASDatasetName, 1-character SAS names, class read from attribute vs
 * child, class-less split parts.
 */
class SplitDatasetChecksTest
{

    private static DocumentContext context(String aBody)
    {
        String doc = """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <Study OID="ST.1">
                    <MetaDataVersion OID="MDV.1" Name="MDV">
                """ + aBody + """
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """;
        try (var in = new ByteArrayInputStream(doc.getBytes(StandardCharsets.UTF_8)))
        {
            return new DocumentContext(ElementNodeBuilder.build(DefineDomIo.parse(in)), "2.1", null,
                    null);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }


    private static ElementNode itemGroupDef(DocumentContext aContext, String aOid)
    {
        return aContext.allNodes().stream()
                .filter(n -> "ItemGroupDef".equals(n.localName())
                        && n.attribute("OID").map(aOid::equals).orElse(false))
                .findFirst().orElseThrow();
    }


    @Test
    void dd0049SkipsBlankDomainSuppqualAndSplitGroups()
    {
        NonSplitDatasetNameConsistencyCheck check = new NonSplitDatasetNameConsistencyCheck();
        DocumentContext ctx = context("""
                <ItemGroupDef OID="IG.NODOMAIN" Name="POOLDEF" SASDatasetName="POOLDEF"/>
                <ItemGroupDef OID="IG.BLANK" Name="XY" Domain="" SASDatasetName="XZ"/>
                <ItemGroupDef OID="IG.SUPPAE" Name="SUPPAE" Domain="AE" SASDatasetName="SUPPAE"/>
                <ItemGroupDef OID="IG.SQAPDM" Name="SQAPDM" Domain="APDM" SASDatasetName="SQAPDM"/>
                <ItemGroupDef OID="IG.QS1" Name="QSCG" Domain="QS" SASDatasetName="QSCG"/>
                <ItemGroupDef OID="IG.QS2" Name="QSMD" Domain="QS" SASDatasetName="QSMD"/>
                <ItemGroupDef OID="IG.VS" Name="VSX" Domain="VS" SASDatasetName="VS"/>
                <ItemGroupDef OID="IG.LB" Name="LB" Domain="LB"/>
                """);
        // No / blank Domain: out of reach.
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.NODOMAIN"), ctx));
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.BLANK"), ctx));
        // SUPP/SQAP name prefixes: the row's SUPPQUAL exclusion.
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.SUPPAE"), ctx));
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.SQAPDM"), ctx));
        // Split parts (two IGDs share Domain QS): DD0049 does not apply.
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.QS1"), ctx));
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.QS2"), ctx));
        // Non-split with Name != Domain: fires.
        assertFalse(check.satisfied(itemGroupDef(ctx, "IG.VS"), ctx));
        // Absent SASDatasetName: only the Name comparison applies, which matches.
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.LB"), ctx));
    }


    @Test
    void dd0050ComparesTheTwoCharacterPrefixOnSplitPartsOnly()
    {
        SplitDatasetSasNamePrefixCheck check = new SplitDatasetSasNamePrefixCheck();
        DocumentContext ctx = context("""
                <ItemGroupDef OID="IG.QS1" Name="QSCG" Domain="QS" SASDatasetName="QSCG"/>
                <ItemGroupDef OID="IG.QS2" Name="QSMD" Domain="QS" SASDatasetName="Q"/>
                <ItemGroupDef OID="IG.QS3" Name="QSXX" Domain="QS"/>
                <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM" SASDatasetName="XX"/>
                """);
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.QS1"), ctx), "matching prefix");
        assertFalse(check.satisfied(itemGroupDef(ctx, "IG.QS2"), ctx),
                "1-character SASDatasetName cannot equal a 2-letter Domain");
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.QS3"), ctx),
                "absent SASDatasetName is out of reach");
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.DM"), ctx),
                "non-split datasets are out of reach (DD0049's beat)");
    }


    @Test
    void dd0063RequiresAliasOnSplitPartsOnly()
    {
        SplitDatasetAliasCheck check = new SplitDatasetAliasCheck();
        DocumentContext ctx = context("""
                <ItemGroupDef OID="IG.QS1" Name="QSCG" Domain="QS">
                  <Alias Context="DomainDescription" Name="Questionnaires"/>
                </ItemGroupDef>
                <ItemGroupDef OID="IG.QS2" Name="QSMD" Domain="QS"/>
                <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM"/>
                """);
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.QS1"), ctx), "split part with Alias");
        assertFalse(check.satisfied(itemGroupDef(ctx, "IG.QS2"), ctx), "split part without Alias");
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.DM"), ctx),
                "non-split datasets are out of reach");
    }


    @Test
    void dd0114ReadsTheClassFromAttributeOrChildCaseInsensitively()
    {
        SplitDatasetClassCheck check = new SplitDatasetClassCheck();
        DocumentContext ctx = context("""
                <ItemGroupDef OID="IG.QS1" Name="QSCG" Domain="QS" def:Class="FINDINGS"/>
                <ItemGroupDef OID="IG.QS2" Name="QSMD" Domain="QS">
                  <def:Class Name="Findings About"/>
                </ItemGroupDef>
                <ItemGroupDef OID="IG.QS3" Name="QSXX" Domain="QS" def:Class="TRIAL DESIGN"/>
                <ItemGroupDef OID="IG.QS4" Name="QSYY" Domain="QS"/>
                <ItemGroupDef OID="IG.SE" Name="SE" Domain="SE" def:Class="SPECIAL PURPOSE"/>
                """);
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.QS1"), ctx), "2.0 attribute, GO class");
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.QS2"), ctx),
                "2.1 child Name, GO class, case-insensitive");
        assertFalse(check.satisfied(itemGroupDef(ctx, "IG.QS3"), ctx), "non-GO split part");
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.QS4"), ctx),
                "class-less split part is out of reach (DD0054's beat)");
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.SE"), ctx),
                "non-split datasets are out of reach regardless of class");
    }


    @Test
    void dd0115FlagsTheUnsplitTwinOfASplitGroup()
    {
        SplitUnsplitConflictCheck check = new SplitUnsplitConflictCheck();
        DocumentContext ctx = context("""
                <ItemGroupDef OID="IG.AE" Name="AE" Domain="AE"/>
                <ItemGroupDef OID="IG.AE1" Name="AE1" Domain="AE"/>
                <ItemGroupDef OID="IG.DM" Name="DM" Domain="DM"/>
                <ItemGroupDef OID="IG.NODOMAIN" Name="POOLDEF"/>
                """);
        assertFalse(check.satisfied(itemGroupDef(ctx, "IG.AE"), ctx),
                "the unsplit twin (Name = Domain) fires");
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.AE1"), ctx),
                "the split part itself is a legitimate listing");
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.DM"), ctx),
                "a lone dataset is out of reach");
        assertTrue(check.satisfied(itemGroupDef(ctx, "IG.NODOMAIN"), ctx), "no Domain, no group");
    }

}
