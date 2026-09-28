package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.metadata.DefineXmlMetadataProvider;
import net.cumba.corej.core.metadata.OdmDefineXMLProvider;
import net.cumba.corej.core.metadata.VlmResolver;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Two ItemDefs whose names differ ONLY in letter case ({@code PARAM} / {@code param},
 * {@code LBSTRESC} / {@code lbstresc}) in one define: the three case-insensitive define name
 * lookups (register {@code CIT §1}) agree on which one a column reads — an exact spelling wins, and
 * any other spelling reads the FIRST declared ({@code PLAN-case-insensitive-templates} review round
 * 3, LOW-3). Before, {@code DefineXmlMetadataProvider.getVariableMetadata} took the first match
 * ignoring case even when a later ItemDef matched exactly, while {@code VlmResolver} and
 * {@code ExprCompiler.resolveDecodePartner} kept the LAST (a case-insensitive {@code TreeMap}'s
 * {@code put}) — under the FIRST spelling's key, so even an exact probe read the other ItemDef.
 *
 * <p>
 * Plus LOW-2: {@code getColumnOrder} skips an ItemDef without a {@code Name}, as
 * {@code getVariableMetadata} and {@code VlmResolver} already did. Mockito-free: the real parser
 * over a real fixture, real tables, the real rule runner.
 * </p>
 */
class DefineCaseVariantNamesTest
{

    private static OdmDefineXMLProvider odm;

    private static MetadataProvider define;

    private static VlmResolver vlm;

    @BeforeAll
    static void load() throws IOException
    {
        try (InputStream in = DefineCaseVariantNamesTest.class
                .getResourceAsStream("/define/define-case-variant-names.xml"))
        {
            ODM parsed = new DefineXmlParser().parse(in);
            odm = new OdmDefineXMLProvider(parsed);
        }
        define = new DefineXmlMetadataProvider(odm, null);
        vlm = VlmResolver.from(odm.metaDataVersion());
        assertNotNull(vlm, "the fixture carries value-level metadata");
    }


    @Test
    void theVariableLookupPrefersTheExactSpellingAndOtherwiseTheFirstDeclared()
    {
        assertEquals("PARAM", define.getVariableMetadata("XX", "PARAM").get("name"));
        assertEquals("Parameter (upper)", define.getVariableMetadata("XX", "PARAM").get("label"));
        assertEquals("param", define.getVariableMetadata("XX", "param").get("name"),
                "an exact spelling wins over an earlier ItemDef that matches only ignoring case");
        assertEquals("Parameter (lower)", define.getVariableMetadata("XX", "param").get("label"));
        assertEquals("PARAM", define.getVariableMetadata("XX", "Param").get("name"),
                "any other spelling reads the FIRST declared");
    }


    @Test
    void theValueLevelLookupPrefersTheExactSpellingAndOtherwiseTheFirstDeclared()
    {
        Map<String, String> gluc = Map.of("LBTESTCD", "GLUC");
        VlmResolver.VlmMatch upper = vlm.resolve("LB", "LBSTRESC", gluc::get);
        VlmResolver.VlmMatch lower = vlm.resolve("LB", "lbstresc", gluc::get);
        VlmResolver.VlmMatch mixed = vlm.resolve("LB", "LbStResc", gluc::get);
        assertNotNull(upper);
        assertNotNull(lower);
        assertNotNull(mixed);
        assertEquals(10, upper.length(), "LBSTRESC reads its own ValueListDef (VL.LB.A)");
        assertEquals(99, lower.length(), "lbstresc reads its own ValueListDef (VL.LB.B)");
        assertEquals(10, mixed.length(), "any other spelling reads the FIRST declared (LBSTRESC)");
    }


    private static int decodeMismatches(IDataTable aXx)
    {
        String id = "CIT1-DECODE-VARIANTS";
        String json = "{\"rules\":{\"" + id + "\":{\"Core\":{\"Id\":\"" + id + "\"},"
                + "\"Sensitivity\":\"Record\",\"Check\":{\"all\":["
                + "{\"expression\": \"not empty(value())\"},"
                + "{\"expression\": \"define_variable_decode_matches(variable_name) == false\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\"]}}}}";
        try
        {
            Rule r = RulePackageLoader.loadFromString(json).getRules().get(id);
            assertNotNull(r);
            assertNull(r.getLoadError(), r.getLoadError());
            RuleExecutionResult result = RuleRunnerCalls.execute(r, aXx, _ -> null, "XX", null,
                    null, define, null);
            assertFalse(result.isSkipped());
            return result.getViolationCount();
        }
        catch (IOException e)
        {
            throw new AssertionError(e);
        }
    }


    /**
     * The decode partner of {@code PARAMCD} is confirmed by the partner's own codelist:
     * {@code PARAM} binds the decodes, {@code param} binds something else. The {@code BILI} row
     * carries the decode of {@code ALB}, so a confirmed pair fires once and an unconfirmed one not
     * at all.
     */
    @Test
    void theDecodePartnerPrefersTheExactSpellingAndOtherwiseTheFirstDeclared()
    {
        assertEquals(1,
                decodeMismatches(RealTables.of("XX").str("PARAMCD", "ALB", "BILI")
                        .str("PARAM", "Albumin", "Albumin").build()),
                "PARAM reads its own ItemDef, whose codelist confirms the pair");
        assertEquals(1,
                decodeMismatches(RealTables.of("XX").str("PARAMCD", "ALB", "BILI")
                        .str("Param", "Albumin", "Albumin").build()),
                "any other spelling reads the FIRST declared (PARAM) and is confirmed too");
        assertEquals(0,
                decodeMismatches(RealTables.of("XX").str("PARAMCD", "ALB", "BILI")
                        .str("param", "Albumin", "Albumin").build()),
                "param reads its OWN ItemDef, whose codelist rejects the pair");
    }


    @Test
    void theColumnOrderSkipsAnItemDefWithoutAName()
    {
        List<Map<String, String>> declared = odm.getVariables("XX");
        assertEquals(4, declared.size(), "precondition: the parser keeps the nameless ItemDef");
        assertTrue(declared.stream().anyMatch(v -> v.get("name") == null),
                "precondition: one ItemDef carries no Name");
        assertEquals(List.of("PARAMCD", "PARAM", "param"), define.getColumnOrder("XX"));
    }
}
