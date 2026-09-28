package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.metadata.DefineXmlMetadataProvider;
import net.cumba.corej.core.metadata.OdmDefineXMLProvider;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase 8 (theme T2) — the data-vs-define attribute compare on the level-aware
 * {@code var_*(…, "DATA")} vs {@code var_*(…, "DEFINE")} accessors, exercised end-to-end against
 * the real {@code define-itemmeta-e2e.xml} overlay (DM: {@code AGE} label "Age"/integer,
 * {@code SEX} label "Sex"/text), on a hand-written rule ({@link #LEGACY_TYPE_RULE}).
 */
class DefineVsDataAttributeCompareTest
{

    private static MetadataProvider define;

    @BeforeAll
    static void load() throws IOException
    {
        ODM odm;
        try (InputStream in = DefineVsDataAttributeCompareTest.class
                .getResourceAsStream("/define/define-itemmeta-e2e.xml"))
        {
            odm = new DefineXmlParser().parse(in);
        }
        define = new DefineXmlMetadataProvider(new OdmDefineXMLProvider(odm), null);
    }


    /** Sanity: the define overlay exposes the labels/types the rules compare against. */
    @Test
    void defineOverlayExposesExpectedMetadata()
    {
        Map<String, String> age = define.getVariableMetadata("DM", "AGE");
        assertTrue("Age".equals(age.get("label")), "define AGE label");
    }

    // --- EC-2 (Q-6b): the legacy kill-switch lane (--no-native-eval) must normalize the raw
    // Define vocab in RuleRunner.buildVariableMetadata, so `define_variable_data_type` is compared
    // as Num/Char (matching the data-side operand), not as the raw "integer"/"text" tokens. A
    // flat-authored CheckConditionAll rule (not a CheckConditionExpression) with nativeEval=false
    // takes the legacy operand cascade — the exact path that reads buildVariableMetadata's map. The
    // define overlay declares DM.AGE integer (→Num) and DM.SEX text (→Char).
    private static final String LEGACY_TYPE_RULE = "{\"rules\":{\"L1\":{\"Core\":{\"Id\":\"L1\"},"
            + "\"Sensitivity\":\"Dataset\",\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
            + "\"Check\":{\"all\":[" + "{\"expression\": \"not empty(var_name(\\\"DEFINE\\\"))\"},"
            + "{\"expression\": \"var_type(\\\"DATA\\\") != var_type(\\\"DEFINE\\\")\"}]},"
            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}}}";

    private static Rule loadLegacyTypeRule() throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadFromString(LEGACY_TYPE_RULE);
        Rule rule = pkg.getRules().get("L1");
        assertTrue(rule.getLoadError() == null, "flat rule must load: " + rule.getLoadError());
        return rule;
    }


    /**
     * Legacy lane: a numeric AGE column (Num) and text SEX column (Char) both match the define
     * (integer→Num, text→Char) once EC-2 normalizes the stored Define vocab, so the rule does NOT
     * fire. Before EC-2 the raw "integer" token compared unequal to "Num" and this over-fired.
     */
    @Test
    void ec2_legacyLane_noFireWhenTypesMatchAfterNormalization()
    {
        IDataTable dm = MockTable.of().name("DM").colLong("AGE", 56L).col("SEX", "M").build();
        Rule rule = assertDoesNotThrowLoad();

        RuleExecutionResult r = RuleRunnerCalls.execute(rule, dm, _ -> null, "DM", null, null,
                define);
        assertFalse(r.hasViolations(),
                "legacy lane: Num AGE == define integer(→Num), Char SEX == define text(→Char)");
    }


    /**
     * Legacy lane: a string AGE column (Char) still fires against the define integer (→Num),
     * proving the EC-2 normalization is idempotent for the data-side Num/Char operand and does not
     * mask a genuine type mismatch.
     */
    @Test
    void ec2_legacyLane_firesOnGenuineTypeMismatch()
    {
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").col("SEX", "M").build();
        Rule rule = assertDoesNotThrowLoad();

        RuleExecutionResult r = RuleRunnerCalls.execute(rule, dm, _ -> null, "DM", null, null,
                define);
        assertTrue(r.hasViolations(), "legacy lane: Char AGE != define integer(→Num)");
    }


    private static Rule assertDoesNotThrowLoad()
    {
        try
        {
            return loadLegacyTypeRule();
        }
        catch (IOException ex)
        {
            throw new AssertionError(ex);
        }
    }
}
