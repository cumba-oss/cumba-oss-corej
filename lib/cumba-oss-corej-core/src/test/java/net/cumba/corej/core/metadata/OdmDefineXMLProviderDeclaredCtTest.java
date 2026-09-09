package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.corej.core.gen.CtStandardRef;
import org.junit.jupiter.api.Test;

/**
 * Define-ct plan §4.1 — {@link OdmDefineXMLProvider#declaredCtPackages()} derives the CT packages a
 * Define-XML 2.1 declares via {@code def:Standards}: {@code Type="CT"} entries only, in document
 * order, with the package id derived as {@code <publishingset-lowercase>ct-<version>}.
 */
class OdmDefineXMLProviderDeclaredCtTest
{

    private static OdmDefineXMLProvider provider(String standardsBlock, String defNs)
        throws IOException
    {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ODM xmlns:def="http://www.cdisc.org/ns/def/v%s" ODMVersion="1.3.2"
                     FileType="Snapshot" FileOID="DEF.CT" CreationDateTime="2026-01-01T00:00:00">
                  <Study OID="S1">
                    <MetaDataVersion OID="MDV.1" Name="CT" DefineVersion="%s.0"
                                     StandardName="SDTM-IG" StandardVersion="3.4">
                %s
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """.formatted(defNs, defNs, standardsBlock);
        try (ByteArrayInputStream in = new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8)))
        {
            return new OdmDefineXMLProvider(new DefineXmlParser().parse(in));
        }
    }


    @Test
    void ctEntriesYieldPackageIdsInDocumentOrder() throws IOException
    {
        OdmDefineXMLProvider p = provider("""
                <def:Standards>
                  <def:Standard OID="STD.1" Name="SDTMIG" Type="IG" Version="3.4" Status="Final"/>
                  <def:Standard OID="STD.2" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"
                                Version="2023-12-15" Status="Final"/>
                  <def:Standard OID="STD.3" Name="CDISC/NCI" Type="CT" PublishingSet="ADaM"
                                Version="2024-03-29" Status="Final"/>
                </def:Standards>
                """, "2.1");

        List<CtStandardRef> declared = p.declaredCtPackages();
        assertEquals(2, declared.size(), "the IG entry must not contribute");
        assertEquals(new CtStandardRef("STD.2", "SDTM", "2023-12-15", "sdtmct-2023-12-15"),
                declared.get(0));
        assertEquals(new CtStandardRef("STD.3", "ADaM", "2024-03-29", "adamct-2024-03-29"),
                declared.get(1));
    }


    @Test
    void twoCtEntriesOfOnePublishingSetBothSurvive() throws IOException
    {
        // §4.3 — the mixed-version define (two SDTM CT versions in one document) is exactly the
        // case the merge exists for; the accessor must not collapse it.
        OdmDefineXMLProvider p = provider("""
                <def:Standards>
                  <def:Standard OID="STD.1" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"
                                Version="2024-09-27" Status="Final"/>
                  <def:Standard OID="STD.2" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"
                                Version="2025-03-28" Status="Final"/>
                </def:Standards>
                """, "2.1");

        assertEquals(List.of("sdtmct-2024-09-27", "sdtmct-2025-03-28"),
                p.declaredCtPackages().stream().map(CtStandardRef::packageId).toList());
    }


    @Test
    void defineWithoutStandardsIsEmpty() throws IOException
    {
        // Structurally the Define-XML 2.0 case: no def:Standards element exists before 2.1, so
        // the run falls back to the user's CT selection (§4.2).
        assertTrue(provider("", "2.0").declaredCtPackages().isEmpty());
        assertTrue(provider("", "2.1").declaredCtPackages().isEmpty());
    }


    @Test
    void ctEntryWithoutPublishingSetOrVersionIsSkipped() throws IOException
    {
        // A CT-typed entry that cannot name a package is a defect of the DOCUMENT (a Define-XML
        // rule's business) — it contributes nothing and must never abort the run here.
        OdmDefineXMLProvider p = provider("""
                <def:Standards>
                  <def:Standard OID="STD.1" Name="CDISC/NCI" Type="CT" Version="2023-12-15"/>
                  <def:Standard OID="STD.2" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"/>
                  <def:Standard OID="STD.3" Name="CDISC/NCI" Type="CT" PublishingSet="SEND"
                                Version="2024-12-13"/>
                </def:Standards>
                """, "2.1");

        assertEquals(List.of("sendct-2024-12-13"),
                p.declaredCtPackages().stream().map(CtStandardRef::packageId).toList());
    }


    @Test
    void packageIdDerivationLowerCasesThePublishingSet()
    {
        CtStandardRef ref = CtStandardRef.of(null, "ADaM", "2024-03-29");
        assertEquals("adamct-2024-03-29", ref.packageId());
        assertNull(ref.oid());
    }
}
