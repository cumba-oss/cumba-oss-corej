package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import net.cumba.cdisc.define.DefineXmlParser;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-dictionary-seeder} Phase 6b (D6) — the define.xml as a dictionary
 * version-<em>selection</em> source: {@link OdmDefineXMLProvider#externalDictionaryVersions()}
 * extracts {@code CodeList/ExternalCodeList} declarations per house dictionary type, matched
 * case-insensitively and ignoring punctuation. Never a version check — the result only feeds the
 * store's selection.
 */
class OdmDefineXMLProviderDictionaryVersionsTest
{

    private static OdmDefineXMLProvider provider(String codeLists) throws IOException
    {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <ODM xmlns:def="http://www.cdisc.org/ns/def/v2.0" ODMVersion="1.3.2"
                     FileType="Snapshot" FileOID="DEF.DICT" CreationDateTime="2026-01-01T00:00:00">
                  <Study OID="S1">
                    <MetaDataVersion OID="MDV.1" Name="Dict" DefineVersion="2.0.0"
                                     StandardName="SDTM-IG" StandardVersion="3.4">
                %s
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """.formatted(codeLists);
        try (ByteArrayInputStream in = new ByteArrayInputStream(
                xml.getBytes(StandardCharsets.UTF_8)))
        {
            return new OdmDefineXMLProvider(new DefineXmlParser().parse(in));
        }
    }


    @Test
    void extractsDeclaredDictionaryVersionsByType() throws IOException
    {
        OdmDefineXMLProvider p = provider("""
                <CodeList OID="CL.AE" Name="AE Dict" DataType="text">
                  <ExternalCodeList Dictionary="MedDRA" Version="26.1"/>
                </CodeList>
                <CodeList OID="CL.CM" Name="CM Dict" DataType="text">
                  <ExternalCodeList Dictionary="WHODRUG" Version="SEP_2020"/>
                </CodeList>
                """);

        assertEquals(Map.of("meddra", "26.1", "whodrug", "SEP_2020"),
                p.externalDictionaryVersions());
    }


    /** Punctuation and case in the {@code Dictionary} attribute must not defeat the match. */
    @Test
    void matchesDictionaryNamesIgnoringCaseAndPunctuation() throws IOException
    {
        OdmDefineXMLProvider p = provider("""
                <CodeList OID="CL.1" Name="D1" DataType="text">
                  <ExternalCodeList Dictionary="MED-RT" Version="2026.07.06"/>
                </CodeList>
                <CodeList OID="CL.2" Name="D2" DataType="text">
                  <ExternalCodeList Dictionary="WHO Drug" Version="B3-SEP2020"/>
                </CodeList>
                """);

        assertEquals("2026.07.06", p.externalDictionaryVersions().get("medrt"));
        assertEquals("B3-SEP2020", p.externalDictionaryVersions().get("whodrug"));
    }


    /**
     * A dictionary the store does not model contributes nothing — the map only carries keys
     * {@code DictionaryStore.load} could act on.
     */
    @Test
    void unknownDictionariesAndIncompleteDeclarationsAreOmitted() throws IOException
    {
        OdmDefineXMLProvider p = provider("""
                <CodeList OID="CL.1" Name="D1" DataType="text">
                  <ExternalCodeList Dictionary="ICD-10" Version="2019"/>
                </CodeList>
                <CodeList OID="CL.2" Name="D2" DataType="text">
                  <ExternalCodeList Dictionary="MedDRA"/>
                </CodeList>
                <CodeList OID="CL.3" Name="D3" DataType="text">
                  <ExternalCodeList Version="27.0"/>
                </CodeList>
                <CodeList OID="CL.4" Name="Plain" DataType="text">
                  <CodeListItem CodedValue="A"/>
                </CodeList>
                """);

        assertTrue(p.externalDictionaryVersions().isEmpty(),
                "no modelled type is declared with a version: " + p.externalDictionaryVersions());
    }


    /**
     * Two codelists declaring different versions of one dictionary are contradictory; the first in
     * document order wins deterministically (the CLI option is the escape hatch).
     */
    @Test
    void conflictingDeclarationsKeepTheFirstInDocumentOrder() throws IOException
    {
        OdmDefineXMLProvider p = provider("""
                <CodeList OID="CL.1" Name="D1" DataType="text">
                  <ExternalCodeList Dictionary="MedDRA" Version="26.1"/>
                </CodeList>
                <CodeList OID="CL.2" Name="D2" DataType="text">
                  <ExternalCodeList Dictionary="MEDDRA" Version="27.0"/>
                </CodeList>
                """);

        assertEquals(Map.of("meddra", "26.1"), p.externalDictionaryVersions());
    }


    @Test
    void aDefineWithoutCodeListsDeclaresNothing() throws IOException
    {
        OdmDefineXMLProvider p = provider("");

        assertFalse(p.externalDictionaryVersions().containsKey("meddra"));
        assertTrue(p.externalDictionaryVersions().isEmpty());
    }

}
