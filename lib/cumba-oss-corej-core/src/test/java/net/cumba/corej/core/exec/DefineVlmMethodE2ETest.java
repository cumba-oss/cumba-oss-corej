package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.metadata.DefineXmlMetadataProvider;
import net.cumba.corej.core.metadata.OdmDefineXMLProvider;
import net.cumba.corej.core.metadata.VlmResolver;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runbook W1b DV end to end: one rule per new token through {@link RuleRunner} against the real
 * {@code define-vlm-method.xml} overlay — each fires where the record is undocumented, stays silent
 * where the define documents it, and is SKIPPED with no Define-XML supplied (the DEFINE provider
 * gate, keyed on the {@code vlm_} prefix and on {@code codelist_decode}).
 */
class DefineVlmMethodE2ETest
{

    private static MetadataProvider define;

    private static VlmResolver vlm;

    @BeforeAll
    static void load() throws IOException
    {
        ODM odm;
        try (InputStream in = DefineVlmMethodE2ETest.class
                .getResourceAsStream("/define/define-vlm-method.xml"))
        {
            assertNotNull(in, "fixture");
            odm = new DefineXmlParser().parse(in);
        }
        OdmDefineXMLProvider provider = new OdmDefineXMLProvider(odm);
        define = new DefineXmlMetadataProvider(provider, null);
        VlmResolver r = VlmResolver.from(provider.metaDataVersion());
        assertNotNull(r);
        vlm = r;
    }


    private static Rule rule(String id, String expression) throws IOException
    {
        String json = "{\"Core\":{\"Id\":\"" + id + "\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"LB\"]}}," + "\"Check\":{\"expression\":\""
                + expression.replace("\"", "\\\"") + "\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\"]}}";
        RulePackage pkg = RulePackageLoader
                .loadFromString("{\"rules\":{\"" + id + "\":" + json + "}}");
        Rule r = pkg.getRules().get(id);
        assertNull(r.getLoadError(), "rule must load without error");
        assertNotNull(r.getCheckExpr());
        return r;
    }


    private static IDataTable lb()
    {
        return MockTable.of().name("LB").col("LBTESTCD", "GLUC", "HGB", "SODIUM", "XXX")
                .col("LBSTRESC", "5.1", "14", "140", "1").col("DTYPE", "AVERAGE", "LOCF", "", "")
                .build();
    }


    private static RuleExecutionResult run(Rule rule, MetadataProvider def, VlmResolver resolver)
    {
        return RuleRunnerCalls.execute(rule, lb(), _ -> null, "LB", null, null, def, resolver);
    }


    @Test
    void vlmHasMethodRuleFiresOnTheMatchedRecordsWithoutAMethodAndSkipsWithoutADefine()
        throws IOException
    {
        // the cursor form: the rule walks every column, only LBSTRESC has value-level entries
        Rule r = rule("DV-E2E-001", "vlm_has_method(variable_name) == false");
        RuleExecutionResult with = run(r, define, vlm);
        assertFalse(with.isSkipped());
        assertEquals(2, with.getViolations().size(), "HGB and SODIUM: matched, no method");
        assertTrue(run(r, null, null).isSkipped(), "no Define-XML -> SKIPPED");
    }


    @Test
    void vlmHasCommentAndOriginTypeRulesRunThroughTheSameGate() throws IOException
    {
        Rule comment = rule("DV-E2E-002", "vlm_has_comment(variable_name) == true");
        assertEquals(1, run(comment, define, vlm).getViolations().size(), "HGB only");
        assertTrue(run(comment, null, null).isSkipped());
        Rule origin = rule("DV-E2E-003", "vlm_origin_type(variable_name) == \"\"");
        assertEquals(1, run(origin, define, vlm).getViolations().size(),
                "SODIUM: matched without an origin");
        assertTrue(run(origin, null, null).isSkipped());
    }


    @Test
    void codelistDecodeRuleIsGatedOnTheDefineToo() throws IOException
    {
        Rule r = rule("DV-E2E-004", "var_has_codelist(\"DEFINE\") == true and not empty(value())"
                + " and empty(codelist_decode(variable_name))");
        RuleExecutionResult with = run(r, define, vlm);
        assertFalse(with.isSkipped());
        assertEquals(1, with.getViolations().size(), "LOCF: a term without a decode");
        assertTrue(run(r, null, null).isSkipped(), "no Define-XML -> SKIPPED");
    }
}
