package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.metadata.VlmResolver;
import net.cumba.cdisc.define.Alias;
import net.cumba.cdisc.define.CheckValue;
import net.cumba.cdisc.define.CodeList;
import net.cumba.cdisc.define.CodeListItem;
import net.cumba.cdisc.define.CodeListRef;
import net.cumba.cdisc.define.ItemDef;
import net.cumba.cdisc.define.ItemGroupDef;
import net.cumba.cdisc.define.ItemRef;
import net.cumba.cdisc.define.MetaDataVersion;
import net.cumba.cdisc.define.RangeCheck;
import net.cumba.cdisc.define.ValueListDef;
import net.cumba.cdisc.define.ValueListRef;
import net.cumba.cdisc.define.WhereClauseDef;
import net.cumba.cdisc.define.WhereClauseRef;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Native-compiler tests for the per-record {@code vlm_*} value-level metadata accessors (the
 * {@code Value Check against Define XML VLM} rule type). Exercises the native form of the Tier-1
 * rules — SD1231 (length), SD1229 (mandatory-null), SD0037/SD1228 (value-level codelist membership)
 * — plus the DEFINE provider-level scan that drives the SKIP-when-no-Define gate.
 */
class VlmAccessorNativeTest
{

    private static Expr parse(String source)
    {
        return CheckExpressionParser.parse(source);
    }


    private static CheckValue cv(String value)
    {
        CheckValue c = new CheckValue();
        c.setValue(value);
        return c;
    }


    /**
     * LB define where LBSTRESC has one value-level condition: {@code LBTESTCD EQ GLUC} ⇒ float,
     * length 3, codelist {mg/dL, mmol/L}.
     */
    private static VlmResolver resolver()
    {
        ItemDef lbtestcd = ItemDef.builder().oid("IT.LBTESTCD").name("LBTESTCD").dataType("text")
                .build();
        ItemDef lbstresc = ItemDef.builder().oid("IT.LBSTRESC").name("LBSTRESC").dataType("text")
                .valueListRef(ValueListRef.builder().valueListOID("VL.LBSTRESC").build()).build();
        CodeList cl = CodeList.builder().oid("CL.U")
                .codeListItems(List.of(CodeListItem.builder().codedValue("mg/dL").build(),
                        CodeListItem.builder().codedValue("mmol/L").build()))
                .aliases(List.of(Alias.builder().context("nci:ExtCodeID").name("C_GLUC").build()))
                .build();
        ItemDef vlGluc = ItemDef.builder().oid("IT.LBSTRESC.GLUC").name("SET_GLUC")
                .dataType("float").length(3)
                .codeListRef(CodeListRef.builder().codeListOID("CL.U").build()).build();
        WhereClauseDef wc = WhereClauseDef.builder().oid("WC.GLUC")
                .rangeChecks(List.of(RangeCheck.builder().comparator("EQ").itemOID("IT.LBTESTCD")
                        .checkValues(List.of(cv("GLUC"))).build()))
                .build();
        ValueListDef vl = ValueListDef.builder().oid("VL.LBSTRESC")
                .itemRefs(List.of(ItemRef.builder().itemOID("IT.LBSTRESC.GLUC").orderNumber(1)
                        .mandatory("Yes")
                        .whereClauseRefs(
                                List.of(WhereClauseRef.builder().whereClauseOID("WC.GLUC").build()))
                        .build()))
                .build();
        ItemGroupDef lb = ItemGroupDef.builder().oid("IG.LB").name("LB").domain("LB")
                .itemRefs(List.of(ItemRef.builder().itemOID("IT.LBSTRESC").build(),
                        ItemRef.builder().itemOID("IT.LBTESTCD").build()))
                .build();
        MetaDataVersion mdv = MetaDataVersion.builder().itemGroupDefs(List.of(lb))
                .itemDefs(List.of(lbstresc, lbtestcd, vlGluc)).valueListDefs(List.of(vl))
                .whereClauseDefs(List.of(wc)).codeLists(List.of(cl)).build();
        VlmResolver r = VlmResolver.from(mdv);
        return java.util.Objects.requireNonNull(r);
    }


    /** Context bound to LBSTRESC as the current variable, with the VLM resolver attached. */
    private static EvaluationContext ctx(IDataTable t, VlmResolver vlm)
    {
        return EvaluationContext.builder().table(t).datasetResolver(_ -> null).domainName("LB")
                .vlmResolver(vlm).variables(Map.of("variable_name", "LBSTRESC")).build();
    }


    /** As {@link #ctx} plus a CDISC CT library provider for the extensibility accessor. */
    private static EvaluationContext ctxLib(IDataTable t, VlmResolver vlm,
            net.cumba.cdisc.core.exec.MetadataProvider library)
    {
        return EvaluationContext.builder().table(t).datasetResolver(_ -> null).domainName("LB")
                .vlmResolver(vlm).libraryProvider(library)
                .variables(Map.of("variable_name", "LBSTRESC")).build();
    }


    @Test
    void lengthCheckFiresOnlyOnMatchedOverlongValue()
    {
        // r0 GLUC len 4 > 3 -> fire; r1 GLUC len 2 -> no fire; r2 HGB (unmatched) -> no fire.
        IDataTable t = MockTable.of().col("LBTESTCD", "GLUC", "GLUC", "HGB")
                .col("LBSTRESC", "1234", "12", "999999").build();
        BitSet bits = NativeExprEvaluator.evaluate(parse("len(value()) > vlm_length(varname())"),
                ctx(t, resolver()));
        assertTrue(bits.get(0), "GLUC row with length 4 > declared 3 fires (SD1231)");
        assertFalse(bits.get(1), "GLUC row within length 3 does not fire");
        assertFalse(bits.get(2), "unmatched (HGB) row has no VLM length -> no fire");
    }


    @Test
    void mandatoryNullCheck()
    {
        // GLUC is Mandatory=Yes: an empty LBSTRESC under GLUC fires (SD1229); non-empty / unmatched
        // does not.
        IDataTable t = MockTable.of().col("LBTESTCD", "GLUC", "GLUC", "HGB")
                .col("LBSTRESC", "", "5", "").build();
        BitSet bits = NativeExprEvaluator.evaluate(
                parse("vlm_mandatory(varname()) == \"Yes\" and empty(value())"),
                ctx(t, resolver()));
        assertTrue(bits.get(0), "empty value under a Mandatory=Yes condition fires");
        assertFalse(bits.get(1), "populated value does not fire");
        assertFalse(bits.get(2), "unmatched row (no vlm_mandatory) does not fire");
    }


    @Test
    void valueLevelCodelistMembership()
    {
        // SD0037/SD1228: a matched value not in the value-level codelist fires; an in-list value or
        // an unmatched row does not.
        IDataTable t = MockTable.of().col("LBTESTCD", "GLUC", "GLUC", "HGB")
                .col("LBSTRESC", "pH", "mg/dL", "anything").build();
        BitSet bits = NativeExprEvaluator.evaluate(
                parse("value() not in vlm_codelist_coded_values(varname())"), ctx(t, resolver()));
        assertTrue(bits.get(0), "\"pH\" is not in {mg/dL, mmol/L} under GLUC -> fire");
        assertFalse(bits.get(1), "\"mg/dL\" is in the value-level codelist -> no fire");
        assertFalse(bits.get(2), "unmatched (HGB) row has no value-level codelist -> no fire");
    }


    @Test
    void datatypeConformance()
    {
        // GLUC value-level type is float: "abc" does not conform (fire), "12.5" conforms, HGB
        // unmatched -> no fire. The rule carries a non_empty(value()) guard (SD1230).
        IDataTable t = MockTable.of().col("LBTESTCD", "GLUC", "GLUC", "HGB")
                .col("LBSTRESC", "abc", "12.5", "xyz").build();
        BitSet bits = NativeExprEvaluator.evaluate(
                parse("not empty(value()) and vlm_type_conforms(varname()) == false"),
                ctx(t, resolver()));
        assertTrue(bits.get(0), "non-numeric value under a float value-level type fires");
        assertFalse(bits.get(1), "numeric value conforms -> no fire");
        assertFalse(bits.get(2), "unmatched row -> no fire");
    }


    @Test
    void codelistExtensibility_ct2004_nonExtensible_fires()
    {
        // CT2004: value not in a NON-extensible value-level codelist fires. GLUC codelist C_GLUC is
        // marked non-extensible in the library; "pH" is not in {mg/dL, mmol/L}.
        net.cumba.cdisc.core.exec.StubMetadataProvider lib = new net.cumba.cdisc.core.exec.StubMetadataProvider()
                .extensible("C_GLUC", false);
        IDataTable t = MockTable.of().col("LBTESTCD", "GLUC", "GLUC").col("LBSTRESC", "pH", "mg/dL")
                .build();
        BitSet bits = NativeExprEvaluator.evaluate(
                parse("not empty(value()) and vlm_codelist_extensible(varname()) == false "
                        + "and value() not in vlm_codelist_coded_values(varname())"),
                ctxLib(t, resolver(), lib));
        assertTrue(bits.get(0),
                "value outside a non-extensible value-level codelist fires (CT2004)");
        assertFalse(bits.get(1), "in-list value does not fire");
    }


    @Test
    void codelistExtensibility_ct2004_extensible_doesNotFire()
    {
        // The same value under an EXTENSIBLE codelist does NOT fire CT2004 (it is CT2005's
        // concern).
        net.cumba.cdisc.core.exec.StubMetadataProvider lib = new net.cumba.cdisc.core.exec.StubMetadataProvider()
                .extensible("C_GLUC", true);
        IDataTable t = MockTable.of().col("LBTESTCD", "GLUC").col("LBSTRESC", "pH").build();
        BitSet bits = NativeExprEvaluator.evaluate(
                parse("not empty(value()) and vlm_codelist_extensible(varname()) == false "
                        + "and value() not in vlm_codelist_coded_values(varname())"),
                ctxLib(t, resolver(), lib));
        assertFalse(bits.get(0), "an extensible codelist does not fire the non-extensible check");
    }


    @Test
    void codeDecodePairing_ct2006()
    {
        // QSTESTCD has a value-level codelist (under QSCAT=X) mapping CODE1 -> "Decode One". The
        // paired decode variable QSTEST (drop trailing "CD") must carry the matching decode.
        VlmResolver r = decodeResolver();
        IDataTable t = MockTable.of().col("QSCAT", "X", "X", "Y")
                .col("QSTESTCD", "CODE1", "CODE1", "CODE1")
                .col("QSTEST", "Decode One", "Wrong", "Decode One").build();
        EvaluationContext ctx = EvaluationContext.builder().table(t).datasetResolver(_ -> null)
                .domainName("QS").vlmResolver(r).variables(Map.of("variable_name", "QSTESTCD"))
                .build();
        BitSet bits = NativeExprEvaluator.evaluate(
                parse("not empty(value()) and vlm_decode_matches(varname()) == false"), ctx);
        assertFalse(bits.get(0), "matching decode -> no fire");
        assertTrue(bits.get(1), "wrong decode -> fire (CT2006)");
        assertFalse(bits.get(2), "value-level condition not matched -> no fire");
    }


    private static net.cumba.cdisc.define.Decode decode(String text)
    {
        net.cumba.cdisc.define.TranslatedText tt = new net.cumba.cdisc.define.TranslatedText();
        tt.setValue(text);
        net.cumba.cdisc.define.Decode d = new net.cumba.cdisc.define.Decode();
        d.setTranslatedTexts(List.of(tt));
        return d;
    }


    private static VlmResolver decodeResolver()
    {
        ItemDef qscat = ItemDef.builder().oid("IT.QSCAT").name("QSCAT").dataType("text").build();
        ItemDef qstest = ItemDef.builder().oid("IT.QSTEST").name("QSTEST").dataType("text").build();
        ItemDef qstestcd = ItemDef.builder().oid("IT.QSTESTCD").name("QSTESTCD").dataType("text")
                .valueListRef(ValueListRef.builder().valueListOID("VL.QSTESTCD").build()).build();
        CodeList cl = CodeList.builder().oid("CL.QS").codeListItems(List.of(
                CodeListItem.builder().codedValue("CODE1").decode(decode("Decode One")).build()))
                .build();
        ItemDef vlItem = ItemDef.builder().oid("IT.QSTESTCD.X").name("QSTESTCD_X").dataType("text")
                .codeListRef(CodeListRef.builder().codeListOID("CL.QS").build()).build();
        WhereClauseDef wc = WhereClauseDef.builder().oid("WC.X")
                .rangeChecks(List.of(RangeCheck.builder().comparator("EQ").itemOID("IT.QSCAT")
                        .checkValues(List.of(cv("X"))).build()))
                .build();
        ValueListDef vl = ValueListDef.builder().oid("VL.QSTESTCD")
                .itemRefs(List.of(ItemRef.builder().itemOID("IT.QSTESTCD.X").orderNumber(1)
                        .whereClauseRefs(
                                List.of(WhereClauseRef.builder().whereClauseOID("WC.X").build()))
                        .build()))
                .build();
        ItemGroupDef qs = ItemGroupDef.builder().oid("IG.QS").name("QS").domain("QS")
                .itemRefs(List.of(ItemRef.builder().itemOID("IT.QSTESTCD").build(),
                        ItemRef.builder().itemOID("IT.QSCAT").build(),
                        ItemRef.builder().itemOID("IT.QSTEST").build()))
                .build();
        MetaDataVersion mdv = MetaDataVersion.builder().itemGroupDefs(List.of(qs))
                .itemDefs(List.of(qstestcd, qscat, qstest, vlItem)).valueListDefs(List.of(vl))
                .whereClauseDefs(List.of(wc)).codeLists(List.of(cl)).build();
        return java.util.Objects.requireNonNull(VlmResolver.from(mdv));
    }


    @Test
    void nullResolverProducesNoFire()
    {
        // With no VLM resolver (no Define-XML), the accessor is null for every row -> nothing
        // fires.
        // The rule-level SKIPPED status is asserted in the end-to-end test; here we confirm the
        // accessor null-propagates.
        IDataTable t = MockTable.of().col("LBTESTCD", "GLUC").col("LBSTRESC", "123456").build();
        BitSet bits = NativeExprEvaluator.evaluate(parse("len(value()) > vlm_length(varname())"),
                EvaluationContext.builder().table(t).datasetResolver(_ -> null).domainName("LB")
                        .variables(Map.of("variable_name", "LBSTRESC")).build());
        assertTrue(bits.isEmpty(), "no resolver -> vlm_length null -> no fire");
    }


    @Test
    void vlmAccessorRequiresDefineLevel()
    {
        // The provider-level scan must report DEFINE for any vlm_* usage so RuleRunner SKIPs the
        // rule
        // when no Define-XML is supplied.
        assertTrue(
                MetadataExprScan.providerLevelsUsed(parse("vlm_length(varname()) > 0"))
                        .contains(MetadataLevel.DEFINE),
                "vlm_* accessors require the DEFINE provider (SKIP-when-no-Define gate)");
        assertEquals(1,
                MetadataExprScan
                        .providerLevelsUsed(
                                parse("variable_value not in vlm_codelist_coded_values(varname())"))
                        .size(),
                "the only provider level required is DEFINE");
    }

}
