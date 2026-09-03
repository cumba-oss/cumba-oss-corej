package net.cumba.corej.define.conformance.checks;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.tree.ElementNode;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import org.junit.jupiter.api.Test;

/**
 * Edge-branch coverage for PMDA DD0075's custom check beyond the end-to-end fixtures: multiple
 * AnnotatedCRF declarations (set membership, not first-only), blank leafIDs, no AnnotatedCRF at
 * all, and MetaDataVersion scoping of the AnnotatedCRF pool. The guard (CRF/Collected dispatch)
 * lives in the rule file and is exercised by {@code PmdaP5bRulesTest}; here the check is called
 * directly on Origin nodes.
 */
class CrfOriginAnnotatedCrfReferenceCheckTest
{

    private final CrfOriginAnnotatedCrfReferenceCheck check = new CrfOriginAnnotatedCrfReferenceCheck();

    private static DocumentContext context(String aDoc)
    {
        try (var in = new ByteArrayInputStream(aDoc.getBytes(StandardCharsets.UTF_8)))
        {
            return new DocumentContext(ElementNodeBuilder.build(DefineDomIo.parse(in)), "2.1", null,
                    null);
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }


    private static ElementNode originOf(DocumentContext aContext, String aItemOid)
    {
        return aContext.allNodes().stream()
                .filter(n -> "ItemDef".equals(n.localName())
                        && n.attribute("OID").map(aItemOid::equals).orElse(false))
                .flatMap(n -> n.children("Origin").stream()).findFirst().orElseThrow();
    }


    @Test
    void membershipIsCheckedAgainstAllAnnotatedCrfDeclarations()
    {
        DocumentContext ctx = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <Study OID="ST.1">
                    <MetaDataVersion OID="MDV.1" Name="MDV">
                      <def:AnnotatedCRF>
                        <def:DocumentRef leafID="LF.ACRF1"/>
                      </def:AnnotatedCRF>
                      <def:AnnotatedCRF>
                        <def:DocumentRef leafID="LF.ACRF2"/>
                      </def:AnnotatedCRF>
                      <ItemDef OID="IT.SECOND" Name="SECOND" DataType="text">
                        <def:Origin Type="Collected" Source="Investigator">
                          <def:DocumentRef leafID="LF.ACRF2"/>
                        </def:Origin>
                      </ItemDef>
                      <ItemDef OID="IT.WRONG" Name="WRONG" DataType="text">
                        <def:Origin Type="Collected" Source="Investigator">
                          <def:DocumentRef leafID="LF.OTHER"/>
                        </def:Origin>
                      </ItemDef>
                      <ItemDef OID="IT.NONE" Name="NONE" DataType="text">
                        <def:Origin Type="Collected" Source="Investigator"/>
                      </ItemDef>
                      <ItemDef OID="IT.BLANK" Name="BLANK" DataType="text">
                        <def:Origin Type="Collected" Source="Investigator">
                          <def:DocumentRef leafID=""/>
                        </def:Origin>
                      </ItemDef>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """);
        assertTrue(check.satisfied(originOf(ctx, "IT.SECOND"), ctx),
                "a reference to the SECOND AnnotatedCRF satisfies (set membership, not first-only)");
        assertFalse(check.satisfied(originOf(ctx, "IT.WRONG"), ctx),
                "a reference outside the AnnotatedCRF set fires");
        assertFalse(check.satisfied(originOf(ctx, "IT.NONE"), ctx),
                "a qualifying Origin without any DocumentRef fires");
        assertTrue(check.satisfied(originOf(ctx, "IT.BLANK"), ctx),
                "a blank leafID is ignored (XSD presence defect, the pre-pass's beat)");
    }


    @Test
    void annotatedCrfPoolIsScopedToTheEnclosingMetaDataVersion()
    {
        DocumentContext ctx = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <Study OID="ST.1">
                    <MetaDataVersion OID="MDV.1" Name="MDV1">
                      <def:AnnotatedCRF>
                        <def:DocumentRef leafID="LF.ACRF1"/>
                      </def:AnnotatedCRF>
                    </MetaDataVersion>
                    <MetaDataVersion OID="MDV.2" Name="MDV2">
                      <ItemDef OID="IT.CROSS" Name="CROSS" DataType="text">
                        <def:Origin Type="Collected" Source="Subject">
                          <def:DocumentRef leafID="LF.ACRF1"/>
                        </def:Origin>
                      </ItemDef>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """);
        assertFalse(check.satisfied(originOf(ctx, "IT.CROSS"), ctx),
                "another MetaDataVersion's AnnotatedCRF is not in this Origin's pool");
    }


    @Test
    void withoutAnyAnnotatedCrfEveryQualifyingOriginFires()
    {
        DocumentContext ctx = context("""
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1">
                  <Study OID="ST.1">
                    <MetaDataVersion OID="MDV.1" Name="MDV">
                      <ItemDef OID="IT.CRF" Name="CRF" DataType="text">
                        <def:Origin Type="CRF">
                          <def:DocumentRef leafID="LF.SOMEWHERE"/>
                        </def:Origin>
                      </ItemDef>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """);
        assertFalse(check.satisfied(originOf(ctx, "IT.CRF"), ctx),
                "with no AnnotatedCRF there is nothing to match");
    }

}
