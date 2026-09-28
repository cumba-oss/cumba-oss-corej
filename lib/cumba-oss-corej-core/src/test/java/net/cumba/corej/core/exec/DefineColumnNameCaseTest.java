package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
 * A dataset column is matched to its Define-XML {@code ItemDef} — and to its value-level
 * {@code ValueListDef} — by NAME, ignoring letter case (owner ruling 2026-09-28,
 * <i>"Case-insensitive everywhere"</i>, {@code PLAN-case-insensitive-templates}, register
 * {@code CIT §1}). The lookups are keyed by the dataset's own spelling, so a SAS-exported
 * {@code paramcd} must read the {@code PARAMCD} ItemDef the define declares; compared
 * case-sensitively every {@code define_*} / {@code var_*("DEFINE")} / {@code vlm_*} read on a
 * lowercase dataset answered nothing and the rule went silent. The codelist and decode VALUES stay
 * exact — only the name lookup ignores case.
 *
 * <p>
 * Every lowercase case is paired with its upper-case twin, which must give the SAME verdict: the
 * twin is the control that the fixture fires at all. Mockito-free: {@link RealTables} builds real
 * tables; the define is the real parser over the real {@code /define/*.xml} fixtures.
 * </p>
 */
class DefineColumnNameCaseTest
{

    private static MetadataProvider decodeDefine;

    private static MetadataProvider vlmDefine;

    private static VlmResolver vlm;

    @BeforeAll
    static void load() throws IOException
    {
        decodeDefine = new DefineXmlMetadataProvider(
                new OdmDefineXMLProvider(parse("/define/define-vardecode-e2e.xml")), null);
        OdmDefineXMLProvider vlmOdm = new OdmDefineXMLProvider(parse("/define/define-vlm-e2e.xml"));
        vlmDefine = new DefineXmlMetadataProvider(vlmOdm, null);
        vlm = VlmResolver.from(vlmOdm.metaDataVersion());
        assertNotNull(vlm, "the fixture carries value-level metadata");
    }


    private static ODM parse(String aResource) throws IOException
    {
        try (InputStream in = DefineColumnNameCaseTest.class.getResourceAsStream(aResource))
        {
            return new DefineXmlParser().parse(in);
        }
    }


    private static Rule rule(String aId, String aCheckJson, String aOutputVars)
    {
        String json = "{\"Core\":{\"Id\":\"" + aId + "\"},\"Sensitivity\":\"Record\",\"Check\":"
                + aCheckJson + ",\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":["
                + aOutputVars + "]}}";
        try
        {
            Rule r = RulePackageLoader.loadFromString("{\"rules\":{\"" + aId + "\":" + json + "}}")
                    .getRules().get(aId);
            assertNotNull(r);
            assertNull(r.getLoadError(), r.getLoadError());
            assertNotNull(r.getCheckExpr(), "the rule compiles to a native checkExpr");
            return r;
        }
        catch (IOException e)
        {
            throw new AssertionError(e);
        }
    }

    // -- the provider lookup itself -----------------------------------------------------------


    /**
     * {@code DefineXmlMetadataProvider.getVariableMetadata} with the dataset's lowercase spelling
     * reads the upper-case ItemDef — the same map the upper-case name reads.
     */
    @Test
    void theDefineVariableLookupIgnoresTheCaseOfTheColumnName()
    {
        Map<String, String> upper = decodeDefine.getVariableMetadata("XX", "PARAM");
        assertEquals("PARAM", upper.get("name"), "control: the upper-case name reads the ItemDef");
        assertEquals(upper, decodeDefine.getVariableMetadata("XX", "param"));
        assertEquals(upper, decodeDefine.getVariableMetadata("XX", "Param"));
    }


    /** The value-level lookup of a lowercase column finds the upper-case ItemDef's ValueListDef. */
    @Test
    void theValueLevelLookupIgnoresTheCaseOfTheColumnName()
    {
        Map<String, String> row = Map.of("LBTESTCD", "GLUC");
        VlmResolver.VlmMatch upper = vlm.resolve("LB", "LBSTRESC", row::get);
        assertNotNull(upper, "control: the upper-case name resolves the GLUC where-clause");
        VlmResolver.VlmMatch lower = vlm.resolve("LB", "lbstresc", row::get);
        assertNotNull(lower, "a lowercase lbstresc reads LBSTRESC's value-level metadata");
        assertEquals(upper.dataType(), lower.dataType());
        assertEquals(upper.length(), lower.length());
    }

    // -- var_*("DEFINE") / define_variable_* on a lowercase dataset --------------------------


    /**
     * {@code var_type("DEFINE")} over a lowercase dataset: {@code avisitn} is the one column whose
     * ItemDef declares a numeric type ({@code integer}), so it — and only it — fires, and the
     * finding carries the {@code define_variable_simpleDatatype} the ItemDef declares.
     */
    @Test
    void aDefineVariableAccessorReadsTheItemDefOfALowercaseColumn()
    {
        Rule r = rule("CIT1-DEFINE-TYPE",
                "{\"expression\": \"var_type(\\\"DEFINE\\\") == \\\"Num\\\"\"}",
                "\"variable_name\",\"define_variable_simpleDatatype\"");
        for (boolean lowercase : new boolean[]
        {
                false, true
        })
        {
            IDataTable xx = RealTables.of("XX").str(lowercase ? "avisitn" : "AVISITN", "8")
                    .str(lowercase ? "avisit" : "AVISIT", "Week 8").build();
            RuleExecutionResult result = RuleRunnerCalls.execute(r, xx, _ -> null, "XX", null, null,
                    decodeDefine, null);
            assertFalse(result.isSkipped(), "lowercase=" + lowercase);
            assertEquals(1, result.getViolationCount(), "lowercase=" + lowercase);
            Map<String, String> finding = result.getViolations().get(0).getValues();
            assertEquals(lowercase ? "avisitn" : "AVISITN", finding.get("variable_name"),
                    "the finding names the dataset's own spelling");
            assertEquals("integer", finding.get("define_variable_simpleDatatype"),
                    "lowercase=" + lowercase);
        }
    }

    // -- define_variable_decode_matches, end to end -------------------------------------------


    private static int decodeMismatches(IDataTable aXx)
    {
        Rule r = rule("CIT1-DECODE", "{\"all\":[{\"expression\": \"not empty(value())\"},"
                + "{\"expression\": \"define_variable_decode_matches(variable_name) == false\"}]}",
                "\"variable_name\",\"variable_value\"");
        return RuleRunnerCalls.execute(r, aXx, _ -> null, "XX", null, null, decodeDefine, null)
                .getViolationCount();
    }


    /**
     * The realistic path: the cursor {@code paramcd} reads its ItemDef's code/decode map, the
     * resolver ({@code ExprCompiler.resolveDecodePartner}) confirms {@code param} by the define's
     * own codelist, and the mismatched row fires — exactly as on {@code PARAMCD} / {@code PARAM}.
     * Compared case-sensitively the ItemDef read answered nothing and the rule never decided.
     */
    @Test
    void decodeMatchesPairsALowercaseCodeVariableWithItsSuffixPartner()
    {
        assertEquals(1, decodeMismatches(RealTables.of("XX").str("PARAMCD", "ALB", "BILI")
                .str("PARAM", "Albumin", "Albumin").build()), "control: upper case");
        assertEquals(1,
                decodeMismatches(RealTables.of("XX").str("paramcd", "ALB", "BILI")
                        .str("param", "Albumin", "Albumin").build()),
                "BILI should decode to Bilirubin — on lowercase columns too");
    }


    /** The unique-match fallback ({@code ETCD} → {@code ELEMENT}) on lowercase columns. */
    @Test
    void decodeMatchesReachesTheFallbackPartnerOnLowercaseColumns()
    {
        assertEquals(1, decodeMismatches(RealTables.of("XX").str("ETCD", "SCRN", "PBO")
                .str("ELEMENT", "Screen", "Screen").build()), "control: upper case");
        assertEquals(1,
                decodeMismatches(RealTables.of("XX").str("etcd", "SCRN", "PBO")
                        .str("element", "Screen", "Screen").build()),
                "PBO should decode to Placebo — found through the fallback on lowercase columns");
    }


    /** Decode VALUES stay exact: a decode differing only in case is a mismatch. */
    @Test
    void decodeValuesStillCompareExactly()
    {
        assertEquals(1,
                decodeMismatches(RealTables.of("XX").str("paramcd", "ALB", "BILI")
                        .str("param", "Albumin", "BILIRUBIN").build()),
                "BILIRUBIN is not the decode Bilirubin — a value, compared exactly");
    }

    // -- vlm_*, end to end ---------------------------------------------------------------------


    /**
     * {@code FDA-SD1231} shape over a lowercase LB: the lowercase cursor {@code lbstresc} reads
     * {@code LBSTRESC}'s ValueListDef, and the where-clause's {@code LBTESTCD} / {@code LBSPEC}
     * read the lowercase cells — the two matched overlong values fire, as on upper case.
     */
    @Test
    void aValueLevelRuleResolvesOnALowercaseDataset()
    {
        Rule r = rule("CIT1-VLM", "{\"all\":[{\"expression\": \"not empty(value())\"},"
                + "{\"expression\": \"vlm_value_length(variable_name) > vlm_length(variable_name)\"}]}",
                "\"variable_name\",\"variable_value\"");
        for (List<String> names : List.of(List.of("LBTESTCD", "LBSPEC", "LBSTRESC"),
                List.of("lbtestcd", "lbspec", "lbstresc")))
        {
            IDataTable lb = RealTables.of("LB").str(names.get(0), "GLUC", "GLUC", "PH", "PH", "HGB")
                    .str(names.get(1), "", "", "URINE", "BLOOD", "")
                    .str(names.get(2), "1234", "12", "ABCDEFGH", "ABCDEFGH", "999999999").build();
            RuleExecutionResult result = RuleRunnerCalls.execute(r, lb, _ -> null, "LB", null, null,
                    vlmDefine, vlm);
            assertEquals(2, result.getViolationCount(),
                    names + ": r0 GLUC and r2 PH+URINE are the matched overlong values");
        }
    }
}
