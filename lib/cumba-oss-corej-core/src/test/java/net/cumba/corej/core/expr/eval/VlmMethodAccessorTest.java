package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.BitSet;
import java.util.Map;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.metadata.DefineXmlMetadataProvider;
import net.cumba.corej.core.metadata.OdmDefineXMLProvider;
import net.cumba.corej.core.metadata.VlmResolver;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Runbook W1b DV ({@code PLAN-engine-gap-define-vlm-method} §4.1, owner-ruled 2026-10-07): the
 * per-record accessors {@code vlm_has_method(X)}, {@code vlm_has_comment(X)},
 * {@code vlm_origin_type(X)}, the variable accessor {@code var_has_value_list("DEFINE")} and the Q3
 * (c) decode accessor {@code codelist_decode(X)}, on the real {@code define-vlm-method.xml}
 * overlay: LB.LBSTRESC documented per LBTESTCD (GLUC by a value-level method, HGB by a value-level
 * comment, SODIUM by nothing).
 */
class VlmMethodAccessorTest
{

    private static MetadataProvider define;

    private static VlmResolver vlm;

    @BeforeAll
    static void load() throws IOException
    {
        ODM odm;
        try (InputStream in = VlmMethodAccessorTest.class
                .getResourceAsStream("/define/define-vlm-method.xml"))
        {
            assertNotNull(in, "fixture");
            odm = new DefineXmlParser().parse(in);
        }
        OdmDefineXMLProvider provider = new OdmDefineXMLProvider(odm);
        define = new DefineXmlMetadataProvider(provider, null);
        VlmResolver r = VlmResolver.from(provider.metaDataVersion());
        assertNotNull(r, "the fixture carries value-level metadata");
        vlm = r;
    }


    private static Expr parse(String source)
    {
        return CheckExpressionParser.parse(source);
    }


    /** GLUC (value-level method), HGB (value-level comment), SODIUM (nothing), XXX (unmatched). */
    private static IDataTable lb()
    {
        return MockTable.of().name("LB").col("LBTESTCD", "GLUC", "HGB", "SODIUM", "XXX")
                .col("LBSTRESC", "5.1", "14", "140", "1").col("LBORRES", "a", "b", "c", "d")
                .col("DTYPE", "AVERAGE", "LOCF", "", "AVERAGE").build();
    }


    private static EvaluationContext ctx(IDataTable t, @Nullable VlmResolver resolver,
            @Nullable MetadataProvider defineProvider, String cursor)
    {
        return EvaluationContext.builder().table(t).datasetResolver(_ -> null).domainName("LB")
                .vlmResolver(resolver).defineProvider(defineProvider)
                .variables(Map.of("variable_name", cursor)).build();
    }


    private static BitSet eval(String source, EvaluationContext c)
    {
        return NativeExprEvaluator.evaluate(parse(source), c);
    }


    @Test
    void vlmHasMethodAnswersTheMatchedValueLevelItemRef()
    {
        EvaluationContext c = ctx(lb(), vlm, define, "LBSTRESC");
        BitSet has = eval("vlm_has_method(varname()) == true", c);
        assertTrue(has.get(0), "GLUC: ItemRef/@MethodOID at value level");
        assertFalse(has.get(1));
        assertFalse(has.get(2));
        assertFalse(has.get(3), "unmatched: missing, and missing == true is false");
        BitSet without = eval("vlm_has_method(varname()) == false", c);
        assertFalse(without.get(0));
        assertTrue(without.get(1), "HGB: matched entry without a method");
        assertTrue(without.get(2));
        assertFalse(without.get(3), "missing == false is false (the catalog's 0274 finding)");
        BitSet notTrue = eval("vlm_has_method(varname()) != true", c);
        assertTrue(notTrue.get(3), "!= true fires also where no entry matches");
        assertTrue(eval("empty(vlm_has_method(varname()))", c).get(3));
        assertTrue(eval("vlm_has_method(LBSTRESC) == true", c).get(0), "a named column");
        assertTrue(eval("vlm_has_method(\"LBSTRESC\") == true", c).get(0), "the string form");
    }


    @Test
    void vlmHasCommentAnswersTheMatchedValueLevelItemDef()
    {
        EvaluationContext c = ctx(lb(), vlm, define, "LBSTRESC");
        BitSet has = eval("vlm_has_comment(varname()) == true", c);
        assertFalse(has.get(0));
        assertTrue(has.get(1), "HGB: ItemDef/@def:CommentOID at value level");
        assertFalse(has.get(2));
        assertFalse(has.get(3));
        assertTrue(eval("empty(vlm_has_comment(varname()))", c).get(3), "unmatched: missing");
    }


    @Test
    void vlmOriginTypeFollowsTheVariableLevelOriginRule()
    {
        EvaluationContext c = ctx(lb(), vlm, define, "LBSTRESC");
        assertTrue(eval("vlm_origin_type(varname()) == \"Derived\"", c).get(0),
                "def:Origin/@Type on the value-level ItemDef");
        assertTrue(eval("vlm_origin_type(varname()) == \"Collected\"", c).get(1),
                "the v1.0 @Origin attribute fallback");
        assertTrue(eval("vlm_origin_type(varname()) == \"\"", c).get(2),
                "a matched entry without an origin answers \"\" (D14)");
        assertTrue(eval("empty(vlm_origin_type(varname()))", c).get(3), "unmatched: missing");
    }


    @Test
    void theNewTokensAnswerMissingWithoutValueLevelMetadataAndRefuseAQualifiedName()
    {
        EvaluationContext none = ctx(lb(), null, define, "LBSTRESC");
        assertEquals(4, eval("empty(vlm_has_method(varname()))", none).cardinality());
        assertEquals(4, eval("empty(vlm_has_comment(varname()))", none).cardinality());
        assertEquals(4, eval("empty(vlm_origin_type(varname()))", none).cardinality());
        for (String fn : new String[]
        {
                "vlm_has_method", "vlm_has_comment", "vlm_origin_type"
        })
        {
            RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                    () -> eval(fn + "(J.LBSTRESC) == true", ctx(lb(), vlm, define, "LBSTRESC")));
            assertTrue(ex.getMessage().contains("never qualified"), ex.getMessage());
            assertTrue(MetadataExprScan.providerLevelsUsed(parse(fn + "(varname()) != true"))
                    .contains(MetadataLevel.DEFINE), fn + " needs the DEFINE provider");
        }
    }


    @Test
    void theCompositeFiresOnlyWhereNeitherTheVariableNorTheRecordIsDocumented()
    {
        String composite = "var_has_method(\"DEFINE\") == false and var_has_comment(\"DEFINE\")"
                + " == false and vlm_has_method(varname()) != true and vlm_has_comment(varname())"
                + " != true";
        // LBSTRESC: documented per LBTESTCD only (a per-PARAMCD define) — the composite must NOT
        // fire on the documented records
        BitSet perValue = eval(composite, ctx(lb(), vlm, define, "LBSTRESC"));
        assertFalse(perValue.get(0), "GLUC: a value-level method documents it");
        assertFalse(perValue.get(1), "HGB: a value-level comment documents it");
        assertTrue(perValue.get(2), "SODIUM: matched, documented by nothing");
        assertTrue(perValue.get(3), "XXX: no entry at all");
        // LBORRES: a variable-level method documents every record
        assertEquals(0, eval(composite, ctx(lb(), vlm, define, "LBORRES")).cardinality());
        // LBTESTCD: documented nowhere — fires on every record
        assertEquals(4, eval(composite, ctx(lb(), vlm, define, "LBTESTCD")).cardinality());
    }


    @Test
    void varHasValueListReadsTheDefineValueListRef()
    {
        assertTrue(
                eval("var_has_value_list(\"DEFINE\") == true", ctx(lb(), vlm, define, "LBSTRESC"))
                        .get(0));
        assertTrue(
                eval("var_has_value_list(\"DEFINE\") == false", ctx(lb(), vlm, define, "LBTESTCD"))
                        .get(0));
        assertTrue(eval("var_has_value_list(\"LBSTRESC\", \"DEFINE\") == true",
                ctx(lb(), vlm, define, "LBTESTCD")).get(0), "a named variable");
        assertTrue(
                eval("empty(var_has_value_list(\"NOPE\", \"DEFINE\"))",
                        ctx(lb(), vlm, define, "LBTESTCD")).get(0),
                "a variable the define does not declare: missing");
        assertEquals("true", define.getVariableMetadata("LB", "LBSTRESC").get("has_value_list"));
        assertEquals("false", define.getVariableMetadata("LB", "DTYPE").get("has_value_list"));
    }


    @Test
    void codelistDecodeAnswersTheDefineCodelistsDecodeOfTheRecordsValue()
    {
        EvaluationContext c = ctx(lb(), vlm, define, "DTYPE");
        BitSet decoded = eval("codelist_decode(DTYPE) == \"Average of values\"", c);
        assertTrue(decoded.get(0), "AVERAGE has a Decode in DTYPE's codelist");
        assertFalse(decoded.get(1));
        assertFalse(decoded.get(2));
        assertTrue(decoded.get(3));
        BitSet missing = eval("empty(codelist_decode(DTYPE))", c);
        assertFalse(missing.get(0));
        assertTrue(missing.get(1), "LOCF is an EnumeratedItem: no decode");
        assertTrue(missing.get(2), "an empty value has no decode");
        assertTrue(eval("codelist_decode() == \"Average of values\"", c).get(0),
                "the cursor variable");
        assertTrue(eval("codelist_decode(varname()) == \"Average of values\"", c).get(0));
        assertTrue(eval("codelist_decode(\"DTYPE\") == \"Average of values\"", c).get(0),
                "a quoted name is a name (a name slot)");
        assertEquals(4, eval("empty(codelist_decode(LBTESTCD))", c).cardinality(),
                "a variable without a define codelist: missing on every row");
        assertEquals(4,
                eval("empty(codelist_decode(DTYPE))", ctx(lb(), vlm, null, "DTYPE")).cardinality(),
                "no Define-XML: missing on every row");
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> eval("codelist_decode(J.DTYPE) == \"x\"", c));
        assertTrue(ex.getMessage().contains("never qualified"), ex.getMessage());
        assertTrue(
                MetadataExprScan.providerLevelsUsed(parse("empty(codelist_decode(DTYPE))"))
                        .contains(MetadataLevel.DEFINE),
                "codelist_decode needs the DEFINE provider");
        // Q3 (a) + (c): the DTYPE record is documented by the value-level method of the result
        // variable OR by a decode of the DTYPE term — the rule fires only when neither holds
        String q3 = "not empty(DTYPE) and vlm_has_method(LBSTRESC) != true"
                + " and empty(codelist_decode(DTYPE))";
        BitSet fires = eval(q3, c);
        assertFalse(fires.get(0), "GLUC: the result's value-level method documents it (a)");
        assertTrue(fires.get(1), "HGB/LOCF: neither a method nor a decode");
        assertFalse(fires.get(2), "no DTYPE value");
        assertFalse(fires.get(3), "XXX/AVERAGE: the decode documents it (c)");
    }


    @Test
    void theSurfaceListsTheNewTokens()
    {
        String surface = net.cumba.corej.core.RuleLanguage.surface();
        assertTrue(surface.contains("\nvlm_has_method(name:unknown@name?) kind=value\n"), surface);
        assertTrue(surface.contains("\nvlm_has_comment(name:unknown@name?) kind=value\n"), surface);
        assertTrue(surface.contains("\nvlm_origin_type(name:unknown@name?) kind=value\n"), surface);
        assertTrue(surface.contains("\ncodelist_decode(name:unknown@name?) kind=value\n"), surface);
        assertTrue(
                surface.contains(
                        "\nvar_has_value_list scope=variable levels=DEFINE result=boolean\n"),
                surface);
    }
}
