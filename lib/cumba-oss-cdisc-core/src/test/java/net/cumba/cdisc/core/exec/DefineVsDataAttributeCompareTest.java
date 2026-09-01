package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.metadata.DefineXmlMetadataProvider;
import net.cumba.cdisc.core.metadata.OdmDefineXMLProvider;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Phase 8 (theme T2) — the data-vs-define attribute-compare pilots authored on the shipping
 * level-aware {@code var_*(…, "DATA")} vs {@code var_*(…, "DEFINE")} accessors, exercised
 * end-to-end against the real {@code define-itemmeta-e2e.xml} overlay (DM: {@code AGE} label
 * "Age"/integer, {@code SEX} label "Sex"/text).
 *
 * <ul>
 * <li>FDA-SD1324 fires where the dataset variable label differs from the define label, and does not
 * where they match.</li>
 * <li>FDA-SD0059 fires where the dataset variable data type differs from the define type (a string
 * {@code AGE} column, {@code Char}, vs the define {@code integer}, {@code Num}).</li>
 * <li>Both rules are <b>SKIPPED</b> — never PASS/FAIL — when no Define-XML provider is supplied,
 * proving the input-availability gate (RuleRunner's {@code define_} operand-prefix skip).</li>
 * </ul>
 */
class DefineVsDataAttributeCompareTest
{

    private static MetadataProvider define;

    private static Rule sd1324;

    private static Rule sd0059;

    private static Rule sd0060;

    @BeforeAll
    static void load() throws IOException
    {
        ODM odm;
        try (InputStream in = DefineVsDataAttributeCompareTest.class
                .getResourceAsStream("/define/define-itemmeta-e2e.xml"))
        {
            odm = new DefineXmlParser().parse(in);
        }
        define = new DefineXmlMetadataProvider(new OdmDefineXMLProvider(odm));

        RulePackage pkg = RulePackageLoader
                .loadCombined(Path.of(System.getProperty("projectBasedir"),
                        "src/test/resources/fixtures/rules/packages", "rules-sdtmig-3-4.json"));
        sd1324 = find(pkg, "FDA-SD1324");
        sd0059 = find(pkg, "FDA-SD0059");
        sd0060 = find(pkg, "FDA-SD0060");
    }


    private static Rule find(RulePackage pkg, String id)
    {
        return pkg.getRules().values().stream()
                .filter(r -> r.getCore() != null && id.equals(r.getCore().getId())).findFirst()
                .orElseThrow(() -> new AssertionError(id + " not in package"));
    }


    /** DM with AGE label "WRONG" (define "Age") -> SD1324 fires; SEX label "Sex" matches. */
    @Test
    void sd1324_firesOnLabelMismatch()
    {
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").col("SEX", "M")
                .colMeta("AGE", "WRONG", 8, null).colMeta("SEX", "Sex", 1, null).build();

        RuleExecutionResult r = RuleRunner.execute(sd1324, dm, _ -> null, "DM", null, null, define);
        assertTrue(r.hasViolations(), "AGE data label (WRONG) != define label (Age)");
    }


    /** DM with matching labels -> SD1324 does not fire. */
    @Test
    void sd1324_noFireWhenLabelsMatch()
    {
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").col("SEX", "M")
                .colMeta("AGE", "Age", 8, null).colMeta("SEX", "Sex", 1, null).build();

        RuleExecutionResult r = RuleRunner.execute(sd1324, dm, _ -> null, "DM", null, null, define);
        assertFalse(r.hasViolations(), "labels match the define -> no finding");
    }


    /** No define provider -> SD1324 is SKIPPED (never PASS/FAIL). */
    @Test
    void sd1324_skippedWhenNoDefine()
    {
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").colMeta("AGE", "WRONG", 8, null)
                .build();

        RuleExecutionResult r = RuleRunner.execute(sd1324, dm, _ -> null, "DM", null, null, null);
        assertTrue(r.isSkipped(), "no Define-XML -> rule SKIPPED");
        assertFalse(r.hasViolations(), "SKIPPED rule reports no violations");
    }


    /** A string AGE column (Char) vs the define integer (Num) -> SD0059 fires. */
    @Test
    void sd0059_firesOnTypeMismatch()
    {
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").col("SEX", "M").build();

        RuleExecutionResult r = RuleRunner.execute(sd0059, dm, _ -> null, "DM", null, null, define);
        assertTrue(r.hasViolations(), "AGE data type Char != define type Num");
    }


    /** A numeric AGE column (Num) vs the define integer (Num) -> SD0059 does not fire. */
    @Test
    void sd0059_noFireWhenTypesMatch()
    {
        IDataTable dm = MockTable.of().name("DM").colLong("AGE", 56L).col("SEX", "M").build();

        RuleExecutionResult r = RuleRunner.execute(sd0059, dm, _ -> null, "DM", null, null, define);
        assertFalse(r.hasViolations(), "AGE numeric (Num) and SEX text (Char) match the define");
    }


    /** No define provider -> SD0059 is SKIPPED. */
    @Test
    void sd0059_skippedWhenNoDefine()
    {
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").build();

        RuleExecutionResult r = RuleRunner.execute(sd0059, dm, _ -> null, "DM", null, null, null);
        assertTrue(r.isSkipped(), "no Define-XML -> rule SKIPPED");
    }


    /** A data column absent from the define (ZZ) -> SD0060 fires; AGE/SEX are declared. */
    @Test
    void sd0060_firesOnVariableAbsentFromDefine()
    {
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").col("SEX", "M").col("ZZ", "x")
                .build();

        RuleExecutionResult r = RuleRunner.execute(sd0060, dm, _ -> null, "DM", null, null, define);
        assertTrue(r.hasViolations(), "ZZ is not declared in the Define-XML");
    }


    /** Every data column declared in the define -> SD0060 does not fire. */
    @Test
    void sd0060_noFireWhenAllVariablesInDefine()
    {
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").col("SEX", "M").build();

        RuleExecutionResult r = RuleRunner.execute(sd0060, dm, _ -> null, "DM", null, null, define);
        assertFalse(r.hasViolations(), "AGE and SEX are both declared in the define");
    }


    /** No define provider -> SD0060 is SKIPPED. */
    @Test
    void sd0060_skippedWhenNoDefine()
    {
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").col("ZZ", "x").build();

        RuleExecutionResult r = RuleRunner.execute(sd0060, dm, _ -> null, "DM", null, null, null);
        assertTrue(r.isSkipped(), "no Define-XML -> rule SKIPPED");
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
            + "" + "\"Sensitivity\":\"Dataset\",\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
            + "\"Check\":{\"all\":["
            + "{\"name\":\"define_variable_name\",\"operator\":\"non_empty\"},"
            + "{\"name\":\"variable_data_type\",\"operator\":\"not_equal_to\","
            + "\"value\":\"define_variable_data_type\"}]},"
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

        RuleExecutionResult r = RuleRunner.execute(rule, dm, _ -> null, "DM", null, null, define);
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

        RuleExecutionResult r = RuleRunner.execute(rule, dm, _ -> null, "DM", null, null, define);
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
