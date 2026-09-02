package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.exec.StubMetadataProvider;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Fix #26 — end-to-end integration tests for CDISC-AD0131 and CDISC-AD0735 after both rules were
 * rewritten to use the new {@code has_mixed_emptiness_within_group} Operation.
 *
 * <p>
 * The Operation produces a per-group {@code Boolean} indicating whether the named column has mixed
 * populated / unpopulated values within each group. Rules iterate via {@code Sensitivity: Group} +
 * {@code Grouping_Variables:
 * ["PARAMCD"]} and fire one violation per group where the boolean is true.
 * </p>
 */
class CdiscAd0131And0735IntegrationTest
{

    private static RulePackage pkg10;

    private static RulePackage pkg12;

    private static RulePackage pkg13;

    @BeforeAll
    static void loadPackages() throws Exception
    {
        pkg10 = RulePackageLoader.loadCombined(Path.of(System.getProperty("projectBasedir"),
                "src/test/resources/fixtures/rules/packages/rules-adamig-1-0.json"));
        pkg12 = RulePackageLoader.loadCombined(Path.of(System.getProperty("projectBasedir"),
                "src/test/resources/fixtures/rules/packages/rules-adamig-1-2.json"));
        pkg13 = RulePackageLoader.loadCombined(Path.of(System.getProperty("projectBasedir"),
                "src/test/resources/fixtures/rules/packages/rules-adamig-1-3.json"));
    }


    private static Rule findRule(RulePackage p, String coreId)
    {
        return p.getRules().values().stream()
                .filter(r -> r.getCore() != null && coreId.equals(r.getCore().getId())).findFirst()
                .orElseThrow(() -> new AssertionError(coreId + " not in package"));
    }

    /**
     * Fix #223 — the declare channel. Both rules are scoped
     * {@code Scope.Data_Structures.Include:[BASIC DATA STRUCTURE]}; the 7-arg {@code execute}
     * overload lets the fixture declare ADLB's structure rather than have it inferred from the
     * {@code PARAMCD} column happening to be a BDS indicator.
     */
    private static final MetadataProvider DEFINE = new StubMetadataProvider().declares("ADLB",
            "BASIC DATA STRUCTURE");

    private static int violationsOn(Rule rule, IDataTable table)
    {
        DatasetResolver resolver = name -> name.equals(table.getMetaData().getName()) ? table
                : null;
        RuleExecutionResult result = RuleRunner.execute(rule, table, resolver, null, null, null,
                DEFINE);
        return result.getViolationCount();
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0131 — no gate, just mixed-BASETYPE-within-PARAMCD
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0131_mixedBasetype_fires()
    {
        // PARAMCD=GLUC has BASETYPE values "X" and "" → mixed.
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S2", "S3", "S4")
                .col("PARAMCD", "GLUC", "GLUC", "ALB", "ALB").col("BASETYPE", "X", "", "Y", "Y")
                .name("ADLB").build();

        Rule rule = findRule(pkg12, "CDISC-AD0131");
        assertEquals(1, violationsOn(rule, adlb),
                "GLUC group has mixed BASETYPE → 1 violation; ALB consistent → no violation");
    }


    @Test
    void cdiscAd0131_consistentBasetype_noFire()
    {
        // All groups have consistent BASETYPE.
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S2", "S3", "S4")
                .col("PARAMCD", "GLUC", "GLUC", "ALB", "ALB").col("BASETYPE", "X", "X", "Y", "Y")
                .name("ADLB").build();

        Rule rule = findRule(pkg12, "CDISC-AD0131");
        assertEquals(0, violationsOn(rule, adlb), "All groups consistent → no violations");
    }


    @Test
    void cdiscAd0131_allEmpty_noFire()
    {
        // All BASETYPE values empty in a group → not "mixed" → no fire.
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S2").col("PARAMCD", "GLUC", "GLUC")
                .col("BASETYPE", "", "").name("ADLB").build();

        Rule rule = findRule(pkg12, "CDISC-AD0131");
        assertEquals(0, violationsOn(rule, adlb), "All BASETYPE empty → not mixed → no fire");
    }


    @Test
    void cdiscAd0131_multipleMixedGroups_fireOnce_each()
    {
        // Two groups, both mixed → 2 violations.
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S2", "S3", "S4")
                .col("PARAMCD", "GLUC", "GLUC", "ALB", "ALB").col("BASETYPE", "X", "", "Y", "")
                .name("ADLB").build();

        Rule rule = findRule(pkg12, "CDISC-AD0131");
        assertEquals(2, violationsOn(rule, adlb), "Both groups mixed → 2 violations");
    }


    @Test
    void cdiscAd0131_alsoIn1_0()
    {
        // The rule exists in 1-0 too — just confirm load & execution.
        Rule rule = findRule(pkg10, "CDISC-AD0131");
        assertNotNull(rule);
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S2").col("PARAMCD", "GLUC", "GLUC")
                .col("BASETYPE", "X", "").name("ADLB").build();
        assertEquals(1, violationsOn(rule, adlb), "1-0's CDISC-AD0131 fires identically to 1-2's");
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0735 — same shape PLUS gate: only consider rows where BASE or BASEC populated
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0735_mixedBasetype_baseOrBasecPopulated_fires()
    {
        // GLUC: both rows have BASE populated so both qualify (qualifying_any_populated),
        // and BASETYPE is mixed (populated on one, empty on the other) → fires.
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S2").col("PARAMCD", "GLUC", "GLUC")
                .col("BASETYPE", "X", "").colDouble("BASE", 100.0, 200.0).col("BASEC", "", "")
                .name("ADLB").build();

        Rule rule = findRule(pkg13, "CDISC-AD0735");
        assertEquals(1, violationsOn(rule, adlb),
                "GLUC mixed AND BASE populated on both qualifying rows → fires");
    }


    @Test
    void cdiscAd0735_mixedBasetype_neitherBaseNorBasecOnFirstRow_noFire()
    {
        // qualifying_any_populated skips any row where neither BASE nor BASEC is populated.
        // Here no row qualifies, so the mixed-emptiness tally sees no rows → no violation
        // (even though the raw BASETYPE column is mixed).
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S2").col("PARAMCD", "GLUC", "GLUC")
                .col("BASETYPE", "X", "").colDouble("BASE", null, null).col("BASEC", "", "")
                .name("ADLB").build();

        Rule rule = findRule(pkg13, "CDISC-AD0735");
        assertEquals(0, violationsOn(rule, adlb),
                "No row qualifies (neither BASE nor BASEC populated) → no fire");
    }


    @Test
    void cdiscAd0735_consistentBasetype_noFire()
    {
        // Even with BASE/BASEC populated, no fire when BASETYPE is consistent.
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S2").col("PARAMCD", "GLUC", "GLUC")
                .col("BASETYPE", "X", "X").colDouble("BASE", 100.0, 200.0).col("BASEC", "", "")
                .name("ADLB").build();

        Rule rule = findRule(pkg13, "CDISC-AD0735");
        assertEquals(0, violationsOn(rule, adlb),
                "Consistent BASETYPE → no fire even with BASE populated");
    }


    @Test
    void cdiscAd0735_basecPopulatedSatisfiesGate_fires()
    {
        // Either BASE or BASEC populated makes a row qualify; here both rows qualify via BASEC.
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S2").col("PARAMCD", "GLUC", "GLUC")
                .col("BASETYPE", "X", "").colDouble("BASE", null, null)
                .col("BASEC", "BASEC_VALUE", "BASEC_VALUE2").name("ADLB").build();

        Rule rule = findRule(pkg13, "CDISC-AD0735");
        assertEquals(1, violationsOn(rule, adlb),
                "BASEC populated on both qualifying rows → fires (mixed BASETYPE)");
    }

    // -----------------------------------------------------------------------
    // Sanity: both rules load cleanly
    // -----------------------------------------------------------------------


    @Test
    void rulesLoadCleanly()
    {
        assertEquals(null, findRule(pkg12, "CDISC-AD0131").getLoadError(),
                "CDISC-AD0131 (1-2) should load cleanly");
        assertEquals(null, findRule(pkg10, "CDISC-AD0131").getLoadError(),
                "CDISC-AD0131 (1-0) should load cleanly");
        assertEquals(null, findRule(pkg13, "CDISC-AD0735").getLoadError(),
                "CDISC-AD0735 (1-3) should load cleanly");
    }

}
