package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.metadata.DefineXmlMetadataProvider;
import net.cumba.cdisc.core.metadata.OdmDefineXMLProvider;
import net.cumba.cdisc.core.metadata.VlmResolver;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the {@code Value Check against Define XML VLM} rule type (Tier-1 rules
 * SD1231 / SD0037 / SD1228 / SD1229), driven against the real {@code define-vlm-e2e.xml} overlay
 * (LB: {@code LBSTRESC} has a ValueListDef with a simple condition {@code LBTESTCD = GLUC} ⇒
 * float/length-3, and a compound condition {@code LBTESTCD = PH AND LBSPEC = URINE} ⇒ text/length-7
 * bound to the pH codelist). Each rule fires on a genuine value-level violation, does not fire when
 * the value honours its value-level metadata (or when its compound condition does not match — the
 * AND parity guard), and is SKIPPED with no Define-XML supplied.
 */
class DefineVlmE2ETest
{

    private static MetadataProvider define;

    private static VlmResolver vlm;

    @BeforeAll
    static void load() throws IOException
    {
        ODM odm;
        try (InputStream in = DefineVlmE2ETest.class
                .getResourceAsStream("/define/define-vlm-e2e.xml"))
        {
            odm = new DefineXmlParser().parse(in);
        }
        OdmDefineXMLProvider provider = new OdmDefineXMLProvider(odm);
        define = new DefineXmlMetadataProvider(provider);
        vlm = VlmResolver.from(provider.metaDataVersion());
        org.junit.jupiter.api.Assertions.assertNotNull(vlm,
                "the fixture must carry value-level metadata (ValueListDef/WhereClauseDef)");
    }


    private static Rule rule(String id, String checkJson, String outputVars) throws IOException
    {
        String json = "{\"Core\":{\"Id\":\"" + id + "\"}," + ""
                + "\"Sensitivity\":\"Record\",\"Check\":" + checkJson + ","
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[" + outputVars + "]}}";
        RulePackage pkg = RulePackageLoader
                .loadFromString("{\"rules\":{\"" + id + "\":" + json + "}}");
        Rule r = pkg.getRules().get(id);
        assertEquals(null, r.getLoadError(), "rule must load without error");
        org.junit.jupiter.api.Assertions.assertNotNull(r.getCheckExpr(),
                "VLM rule must compile to a native checkExpr");
        return r;
    }


    private static RuleExecutionResult run(Rule rule, IDataTable lb, MetadataProvider def,
            VlmResolver resolver)
    {
        return RuleRunner.execute(rule, lb, _ -> null, "LB", null, null, def, resolver);
    }

    // ---- SD1231: value length within the value-level @Length --------------------------------


    @Test
    void lengthCheck_firesOnOverlongMatchedValues_respectsCompoundCondition()
    {
        Rule sd1231 = ruleSd1231();
        // r0 GLUC len 4 > 3 fire; r1 GLUC len 2 ok; r2 PH+URINE len 8 > 7 fire; r3 PH+BLOOD len 8
        // but compound condition not matched (LBSPEC != URINE) -> no fire; r4 HGB unmatched.
        IDataTable lb = MockTable.of().name("LB").col("LBTESTCD", "GLUC", "GLUC", "PH", "PH", "HGB")
                .col("LBSPEC", "", "", "URINE", "BLOOD", "")
                .col("LBSTRESC", "1234", "12", "ABCDEFGH", "ABCDEFGH", "999999999").build();
        RuleExecutionResult r = run(sd1231, lb, define, vlm);
        assertEquals(2, r.getViolationCount(),
                "only the two matched, overlong values fire (r0 GLUC, r2 PH+URINE)");
    }


    @Test
    void lengthCheck_skippedWhenNoDefine()
    {
        IDataTable lb = MockTable.of().name("LB").col("LBTESTCD", "GLUC").col("LBSPEC", "")
                .col("LBSTRESC", "1234").build();
        RuleExecutionResult r = run(ruleSd1231(), lb, null, null);
        assertTrue(r.isSkipped(), "no Define-XML -> VLM rule SKIPPED");
        assertFalse(r.hasViolations());
    }


    private static Rule ruleSd1231()
    {
        try
        {
            return rule("FDA-SD1231",
                    "{\"all\":[{\"name\":\"variable_value\",\"operator\":\"non_empty\"},"
                            + "{\"name\":\"variable_value_length\",\"operator\":\"greater_than\","
                            + "\"value\":\"define_vlm_length\"}]}",
                    "\"variable_name\",\"variable_value\"");
        }
        catch (IOException e)
        {
            throw new AssertionError(e);
        }
    }

    // ---- SD0037 / SD1228: value in the value-level codelist ---------------------------------


    @Test
    void codelistMembership_firesOnlyForMatchedValuesOutsideTheValueLevelCodelist()
        throws IOException
    {
        Rule sd0037 = rule("FDA-SD0037",
                "{\"all\":[{\"name\":\"variable_value\",\"operator\":\"non_empty\"},"
                        + "{\"name\":\"define_vlm_has_codelist\",\"operator\":\"equal_to\","
                        + "\"value\":true,\"value_is_literal\":true},"
                        + "{\"name\":\"variable_value\",\"operator\":\"is_not_contained_by\","
                        + "\"value\":\"define_vlm_codelist_coded_values\"}]}",
                "\"variable_name\",\"variable_value\"");
        // r0 PH+URINE ACIDIC in codelist -> no; r1 PH+URINE PURPLE not in codelist -> fire; r2
        // PH+BLOOD PURPLE compound not matched -> no; r3 GLUC has no value-level codelist -> no.
        IDataTable lb = MockTable.of().name("LB").col("LBTESTCD", "PH", "PH", "PH", "GLUC")
                .col("LBSPEC", "URINE", "URINE", "BLOOD", "")
                .col("LBSTRESC", "ACIDIC", "PURPLE", "PURPLE", "PURPLE").build();
        RuleExecutionResult r = run(sd0037, lb, define, vlm);
        assertEquals(1, r.getViolationCount(),
                "only the matched value outside its value-level codelist fires (r1)");
    }

    // ---- SD1230: value conforms to the value-level data type --------------------------------


    @Test
    void datatypeConformance_firesOnNonConformingMatchedValues() throws IOException
    {
        Rule sd1230 = rule("FDA-SD1230",
                "{\"all\":[{\"name\":\"variable_value\",\"operator\":\"non_empty\"},"
                        + "{\"name\":\"define_vlm_type_conforms\",\"operator\":\"equal_to\","
                        + "\"value\":false,\"value_is_literal\":true}]}",
                "\"variable_name\",\"variable_value\"");
        // GLUC value-level type is float. r0 "12.5" conforms; r1 "abc" not numeric -> fire; r2
        // PH+URINE "ACIDIC" is text -> conforms; r3 GLUC empty -> non_empty guard excludes; r4 HGB
        // unmatched -> no fire.
        IDataTable lb = MockTable.of().name("LB")
                .col("LBTESTCD", "GLUC", "GLUC", "PH", "GLUC", "HGB")
                .col("LBSPEC", "", "", "URINE", "", "")
                .col("LBSTRESC", "12.5", "abc", "ACIDIC", "", "xyz").build();
        RuleExecutionResult r = run(sd1230, lb, define, vlm);
        assertEquals(1, r.getViolationCount(),
                "only the non-numeric value under the float value-level type fires (r1)");
    }

    // ---- CT2004 / CT2005: value-level codelist extensibility --------------------------------


    @Test
    void nonExtensibleValueLevelCodelist_ct2004_fires() throws IOException
    {
        Rule ct2004 = rule("FDA-CT2004",
                "{\"all\":[{\"name\":\"variable_value\",\"operator\":\"non_empty\"},"
                        + "{\"name\":\"define_vlm_codelist_extensible\",\"operator\":\"equal_to\","
                        + "\"value\":false,\"value_is_literal\":true},"
                        + "{\"name\":\"define_vlm_has_codelist\",\"operator\":\"equal_to\","
                        + "\"value\":true,\"value_is_literal\":true},"
                        + "{\"name\":\"variable_value\",\"operator\":\"is_not_contained_by\","
                        + "\"value\":\"define_vlm_codelist_coded_values\"}]}",
                "\"variable_name\",\"variable_value\"");
        // The fixture's PH value-level codelist (C99999) is marked non-extensible in the library.
        // r0 PH+URINE PURPLE not in {ACIDIC,NEUTRAL,BASIC} -> fire; r1 ACIDIC in list -> no; r2
        // PH+BLOOD compound not matched -> no; r3 GLUC has no value-level codelist -> no.
        StubMetadataProvider library = new StubMetadataProvider().extensible("C99999", false);
        IDataTable lb = MockTable.of().name("LB").col("LBTESTCD", "PH", "PH", "PH", "GLUC")
                .col("LBSPEC", "URINE", "URINE", "BLOOD", "")
                .col("LBSTRESC", "PURPLE", "ACIDIC", "PURPLE", "PURPLE").build();
        RuleExecutionResult r = RuleRunner.execute(ct2004, lb, _ -> null, "LB", library, null,
                define, vlm);
        assertEquals(1, r.getViolationCount(),
                "only the matched value outside its non-extensible value-level codelist fires");
    }


    @Test
    void extensibleValueLevelCodelist_ct2004_doesNotFire() throws IOException
    {
        Rule ct2004 = rule("FDA-CT2004",
                "{\"all\":[{\"name\":\"variable_value\",\"operator\":\"non_empty\"},"
                        + "{\"name\":\"define_vlm_codelist_extensible\",\"operator\":\"equal_to\","
                        + "\"value\":false,\"value_is_literal\":true},"
                        + "{\"name\":\"define_vlm_has_codelist\",\"operator\":\"equal_to\","
                        + "\"value\":true,\"value_is_literal\":true},"
                        + "{\"name\":\"variable_value\",\"operator\":\"is_not_contained_by\","
                        + "\"value\":\"define_vlm_codelist_coded_values\"}]}",
                "\"variable_name\",\"variable_value\"");
        // When the same codelist is EXTENSIBLE, CT2004 (the non-extensible check) does not fire.
        StubMetadataProvider library = new StubMetadataProvider().extensible("C99999", true);
        IDataTable lb = MockTable.of().name("LB").col("LBTESTCD", "PH").col("LBSPEC", "URINE")
                .col("LBSTRESC", "PURPLE").build();
        RuleExecutionResult r = RuleRunner.execute(ct2004, lb, _ -> null, "LB", library, null,
                define, vlm);
        assertEquals(0, r.getViolationCount(), "an extensible codelist does not fire CT2004");
    }

    // ---- CT2006: code/decode pairing under the value-level condition ------------------------


    @Test
    void codeDecodePairing_ct2006() throws IOException
    {
        Rule ct2006 = rule("FDA-CT2006",
                "{\"all\":[{\"name\":\"variable_value\",\"operator\":\"non_empty\"},"
                        + "{\"name\":\"define_vlm_decode_matches\",\"operator\":\"equal_to\","
                        + "\"value\":false,\"value_is_literal\":true}]}",
                "\"variable_name\",\"variable_value\"");
        // QSTESTCD has a value-level codelist (under QSCAT=FUNC) mapping WALK -> "Walk Test". Its
        // paired decode QSTEST must carry the matching decode. r0 match -> no fire; r1 wrong decode
        // -> fire; r2 QSCAT!=FUNC (condition not matched) -> no fire; r3 code not in codelist ->
        // no.
        IDataTable qs = MockTable.of().name("QS").col("QSCAT", "FUNC", "FUNC", "OTHER", "FUNC")
                .col("QSTESTCD", "WALK", "WALK", "WALK", "XYZ")
                .col("QSTEST", "Walk Test", "Wrong", "Wrong", "x").build();
        RuleExecutionResult r = RuleRunner.execute(ct2006, qs, _ -> null, "QS", null, null, define,
                vlm);
        assertEquals(1, r.getViolationCount(),
                "only the matched code with a wrong paired decode fires (CT2006)");
    }

    // ---- SD1229: mandatory value-level variable null ----------------------------------------


    @Test
    void mandatoryNull_firesOnEmptyValueUnderMandatoryCondition() throws IOException
    {
        Rule sd1229 = rule("FDA-SD1229",
                "{\"all\":[{\"name\":\"define_vlm_mandatory\",\"operator\":\"equal_to\","
                        + "\"value\":\"Yes\",\"value_is_literal\":true},"
                        + "{\"name\":\"variable_value\",\"operator\":\"empty\"}]}",
                "\"variable_name\"");
        // r0 GLUC (Mandatory=Yes) empty -> fire; r1 GLUC populated -> no; r2 PH+URINE
        // (Mandatory=No)
        // empty -> no; r3 HGB unmatched empty -> no.
        IDataTable lb = MockTable.of().name("LB").col("LBTESTCD", "GLUC", "GLUC", "PH", "HGB")
                .col("LBSPEC", "", "", "URINE", "").col("LBSTRESC", "", "5", "", "").build();
        RuleExecutionResult r = run(sd1229, lb, define, vlm);
        assertEquals(1, r.getViolationCount(),
                "only the empty value under a Mandatory=Yes value-level condition fires (r0)");
    }

}
