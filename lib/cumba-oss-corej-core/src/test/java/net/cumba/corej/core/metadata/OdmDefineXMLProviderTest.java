package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Direct reads of the parsed Define-XML ODM by {@link OdmDefineXMLProvider}: variable metadata
 * (role, ccode, coded codes from {@code CodeList}/{@code Alias}), dataset metadata, codelist terms,
 * and the empty-result contracts for value-level / where-clause / key reads.
 */
class OdmDefineXMLProviderTest
{

    private static OdmDefineXMLProvider provider;

    @BeforeAll
    static void parse() throws IOException
    {
        ODM odm;
        try (InputStream in = OdmDefineXMLProviderTest.class
                .getResourceAsStream("/define/define-itemmeta-e2e.xml"))
        {
            odm = new DefineXmlParser().parse(in);
        }
        provider = new OdmDefineXMLProvider(odm);
    }


    @Test
    void variables_carryRoleCcodeAndCodedCodes()
    {
        List<Map<String, String>> vars = provider.getVariables("DM");
        assertEquals(2, vars.size());

        Map<String, String> age = vars.get(0);
        assertEquals("AGE", age.get("name"));
        assertEquals("Topic", age.get("role"));
        assertEquals("", age.get("ccode"), "AGE has no codelist");
        assertEquals("[]", age.get("codelist_coded_codes"));

        Map<String, String> sex = vars.get(1);
        assertEquals("SEX", sex.get("name"));
        assertEquals("C66731", sex.get("ccode"));
        assertEquals(List.of("C20197", "C16576"),
                DefineMetadataListCodec.decode(sex.get("codelist_coded_codes")));
        // GLOB-CT-005 variant: only the def:ExtendedValue="Yes" item (F) is an extension.
        assertEquals(List.of("F"),
                DefineMetadataListCodec.decode(sex.get("codelist_extended_values")));
        assertEquals("[]", vars.get(0).get("codelist_extended_values"),
                "no codelist -> empty extended list");
    }


    @Test
    void enumeratedItems_carryExtendedValues() throws IOException
    {
        // GLOB-CT-005 variant, EnumeratedItem leg: def:ExtendedValue="Yes" on an EnumeratedItem
        // (the -B twin of the CodeListItem case) is collected the same way; coded values carry
        // both items, extended values only the flagged one. Exact "Yes" match (the Define-XML
        // enumeration; mirrors the Python reader's == "Yes").
        ODM odm;
        try (InputStream in = OdmDefineXMLProviderTest.class
                .getResourceAsStream("/define/define-extended-enum.xml"))
        {
            odm = new DefineXmlParser().parse(in);
        }
        List<Map<String, String>> vars = new OdmDefineXMLProvider(odm).getVariables("VS");
        assertEquals(1, vars.size());
        Map<String, String> vspos = vars.get(0);
        assertEquals(List.of("SUPINE", "HEADSTAND"),
                DefineMetadataListCodec.decode(vspos.get("codelist_coded_values")));
        assertEquals(List.of("HEADSTAND"),
                DefineMetadataListCodec.decode(vspos.get("codelist_extended_values")));
    }


    @Test
    void variables_carryE2DefineAccessors() throws IOException
    {
        ODM odm;
        try (InputStream in = OdmDefineXMLProviderTest.class
                .getResourceAsStream("/define/define-e2-accessors.xml"))
        {
            odm = new DefineXmlParser().parse(in);
        }
        List<Map<String, String>> vars = new OdmDefineXMLProvider(odm).getVariables("DM");
        assertEquals(2, vars.size());

        // AGE: v2.0 ItemDef/@Origin attribute, ItemDef CommentOID, ItemRef MethodOID, no
        // external codelist.
        Map<String, String> age = vars.get(0);
        assertEquals("AGE", age.get("name"));
        assertEquals("Derived", age.get("origin_type"), "ItemDef/@Origin attribute");
        assertEquals("true", age.get("has_comment"), "CommentOID present -> has_comment");
        assertEquals("true", age.get("has_method"), "ItemRef MethodOID present -> has_method");
        assertEquals("", age.get("external_dictionary"));
        assertEquals("", age.get("external_dictionary_version"));

        // SEX: no Origin/Comment/Method, bound codelist declares an external dictionary.
        Map<String, String> sex = vars.get(1);
        assertEquals("SEX", sex.get("name"));
        assertEquals("", sex.get("origin_type"), "no Origin -> empty");
        assertEquals("false", sex.get("has_comment"));
        assertEquals("false", sex.get("has_method"));
        assertEquals("MEDDRA", sex.get("external_dictionary"));
        assertEquals("25.0", sex.get("external_dictionary_version"));
    }


    @Test
    void variables_carryVariableLevelCodelistGuardAndCodedValues() throws IOException
    {
        // EC-19: has_codelist true iff the ItemDef binds a CodeListRef; codelist_coded_values is
        // the
        // codelist's enumerated submission values (JSON-encoded), defaulting to [] when no
        // codelist.
        ODM odm;
        try (InputStream in = OdmDefineXMLProviderTest.class
                .getResourceAsStream("/define/define-varcodelist-e2e.xml"))
        {
            odm = new DefineXmlParser().parse(in);
        }
        List<Map<String, String>> vars = new OdmDefineXMLProvider(odm).getVariables("DM");
        assertEquals(2, vars.size());

        Map<String, String> sex = vars.get(0);
        assertEquals("SEX", sex.get("name"));
        assertEquals("true", sex.get("has_codelist"),
                "ItemDef CodeListRef present -> has_codelist");
        assertEquals(List.of("M", "F"),
                DefineMetadataListCodec.decode(sex.get("codelist_coded_values")));

        Map<String, String> age = vars.get(1);
        assertEquals("AGE", age.get("name"));
        assertEquals("false", age.get("has_codelist"), "no CodeListRef -> has_codelist false");
        assertEquals("[]", age.get("codelist_coded_values"));
    }


    @Test
    void datasetMetadataAndNames()
    {
        assertEquals(List.of("DM"), provider.getDatasetNames());
        Map<String, String> dm = provider.getDatasetMetadata("DM");
        assertEquals("DM", dm.get("name"));
        assertEquals("DM", dm.get("domain"));
    }


    @Test
    void codelistTerms_returnsCodedValues()
    {
        List<String> coded = provider.getCodelistTerms("CL.SEX").stream()
                .map(t -> t.get("codedValue")).toList();
        assertEquals(List.of("M", "F"), coded);
    }


    @Test
    void emptyContracts()
    {
        assertTrue(provider.getVariables("NOPE").isEmpty(), "unknown domain -> no variables");
        assertTrue(provider.getValueLevelMetadata("DM", "SEX").isEmpty());
        assertTrue(provider.getWhereClauseConditions("WC.1").isEmpty());
        assertTrue(provider.getKeyVariables("DM").isEmpty());
        assertTrue(provider.getCodelistTerms("CL.UNKNOWN").isEmpty());
        assertTrue(provider.getDatasetMetadata("NOPE").isEmpty());
    }


    @Test
    void variables_carryCodeDecodeMap() throws IOException
    {
        // Fix #123: codelist_code_decode carries the ItemDef codelist's CodedValue -> Decode
        // mapping, and is "{}" wherever there is nothing to compare against.
        ODM odm;
        try (InputStream in = OdmDefineXMLProviderTest.class
                .getResourceAsStream("/define/define-vardecode-e2e.xml"))
        {
            odm = new DefineXmlParser().parse(in);
        }
        Map<String, Map<String, String>> byName = new java.util.HashMap<>();
        for (Map<String, String> v : new OdmDefineXMLProvider(odm).getVariables("XX"))
        {
            byName.put(v.get("name"), v);
        }
        // CodeListItem codelist -> populated, in document order.
        assertEquals("{\"ALB\":\"Albumin\",\"BILI\":\"Bilirubin\"}",
                byName.get("PARAMCD").get("codelist_code_decode"));
        // EnumeratedItem-only codelist -> coded values but no decodes.
        assertEquals("{}", byName.get("PARAM").get("codelist_code_decode"));
        // No CodeListRef at all.
        assertEquals("{}", byName.get("AGE").get("codelist_code_decode"));
    }

}
