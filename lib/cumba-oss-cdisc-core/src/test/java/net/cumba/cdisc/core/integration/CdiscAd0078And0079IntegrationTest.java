package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.EnumSet;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.exec.StubMetadataProvider;
import net.cumba.cdisc.core.gen.RuleCategory;
import net.cumba.cdisc.core.gen.RuleGenerator;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.core.model.WildcardFilter;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Fix #24 — end-to-end integration tests for CDISC-AD0078 / CDISC-AD0079 after the rule body gained
 * a {@code wildcards: {xx: {min: 2}}} filter.
 *
 * <p>
 * Both rules check the cited guidance "TRxxSDT/TRxxEDT are required when there is a TRTxxP other
 * than TRT01P". The new filter excludes xx=01 from the wildcard expansion so TRT01P present without
 * TR01SDT no longer fires a false positive in single-period studies. Multi-period studies (TRT02P+)
 * still get the expansion and fire when the matching SDT/EDT column is absent.
 * </p>
 */
class CdiscAd0078And0079IntegrationTest
{

    private static final Path RULES_FILE = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/fixtures/rules/packages/rules-adamig-1-3.json");

    private static RulePackage rulePackage;

    @BeforeAll
    static void loadPackage() throws Exception
    {
        rulePackage = RulePackageLoader.loadCombined(RULES_FILE);
    }


    private static Rule findByCoreId(String coreId)
    {
        return rulePackage.getRules().values().stream()
                .filter(r -> r.getCore() != null && coreId.equals(r.getCore().getId())).findFirst()
                .orElseThrow(() -> new AssertionError("Rule not in package: " + coreId));
    }


    private static DatasetResolver self(IDataTable t)
    {
        return name -> name.equals(t.getMetaData().getName()) ? t : null;
    }


    /**
     * Expands the wildcard rule against the given ADSL table — same path RuleRunner takes when
     * validating an ADSL dataset. Returns the list of concrete rules.
     */
    private static java.util.List<Rule> expand(Rule template, IDataTable adsl)
    {
        // Use a no-op library provider; CDISC-AD0078/0079 don't depend on Library metadata.
        MetadataProvider noOp = new MetadataProvider()
        {

            @Override
            public java.util.List<String> getRequiredVariables(String d)
            {
                return java.util.List.of();
            }


            @Override
            public java.util.List<String> getExpectedVariables(String d)
            {
                return java.util.List.of();
            }


            @Override
            public java.util.List<String> getColumnOrder(String d)
            {
                return java.util.List.of();
            }


            @Override
            public java.util.List<String> getModelColumnOrder(String d)
            {
                return java.util.List.of();
            }


            @Override
            public boolean isDomainCustom(String d)
            {
                return false;
            }


            @Override
            public java.util.List<String> getCodelistTerms(String c)
            {
                return java.util.List.of();
            }


            @Override
            public java.util.Map<String, String> getVariableMetadata(String d, String v)
            {
                return java.util.Map.of();
            }


            @Override
            public java.util.List<java.util.Map<String, String>> getDomainVariables(String d)
            {
                return java.util.List.of();
            }


            @Override
            public java.util.Map<String, String> getDatasetMetadata(String d)
            {
                return java.util.Map.of();
            }


            @Override
            public boolean isCodelistExtensible(String c)
            {
                return true;
            }


            @Override
            public java.util.Map<String, String> getCodelistTermMappings(String c)
            {
                return java.util.Map.of();
            }


            @Override
            public String getStandard()
            {
                return "ADaMIG";
            }


            @Override
            public String getVersion()
            {
                return "1.3";
            }
        };
        RuleGenerator gen = new RuleGenerator(noOp, null, null, "adamct-2025-09-26",
                EnumSet.of(RuleCategory.WILDCARD_EXPANSION));
        gen.setStaticRules(java.util.List.of(template));
        gen.setDomainName("ADSL");
        gen.setClassName("SUBJECT LEVEL ANALYSIS DATASET");
        gen.setDatasetResolver(self(adsl));
        return gen.generate(adsl).getRules();
    }

    /**
     * Fix #223 — the declare channel. CDISC-AD0078 / AD0079 are scoped
     * {@code Scope.Data_Structures.Include:[SUBJECT LEVEL ANALYSIS DATASET]}; the 7-arg
     * {@code execute} overload carries the sponsor's declaration, so the gate no longer has to
     * re-derive that structure from the dataset happening to be named {@code ADSL}.
     */
    private static final MetadataProvider DEFINE = new StubMetadataProvider().declares("ADSL",
            "SUBJECT LEVEL ANALYSIS DATASET");

    /** Fix #223 — every execution goes through the declare channel. */
    private static RuleExecutionResult run(Rule rule, IDataTable adsl)
    {
        return RuleRunner.execute(rule, adsl, self(adsl), null, null, null, DEFINE);
    }

    // ---- CDISC-AD0078: TRTxxP exists ∧ TRxxSDT not_exists, xx > 01 ----


    @Test
    void cdiscAd0078_singlePeriodOnly_noFire()
    {
        // ADSL has TRT01P only — single-period design, no period dates required.
        // wildcards: {xx: {min: 2}} drops xx=01 from expansion → no rule generated.
        Rule template = findByCoreId("CDISC-AD0078");

        IDataTable adsl = MockTable.of().col("STUDYID", "S001").col("USUBJID", "S01")
                .col("TRT01P", "Drug A").name("ADSL").build();

        java.util.List<Rule> expanded = expand(template, adsl);

        assertEquals(0, expanded.size(),
                "ADSL has only TRT01P → xx=01 filtered out → no expansion");
    }


    @Test
    void cdiscAd0078_twoPeriods_trxxSdtMissing_fires()
    {
        // Multi-period design: TRT01P and TRT02P present, but TR02SDT missing → fires.
        // TRT01P is filtered out (xx=01); TRT02P produces an expansion that fires.
        Rule template = findByCoreId("CDISC-AD0078");

        IDataTable adsl = MockTable.of().col("STUDYID", "S001").col("USUBJID", "S01")
                .col("TRT01P", "Drug A").col("TRT02P", "Drug B").name("ADSL").build();

        java.util.List<Rule> expanded = expand(template, adsl);

        assertEquals(1, expanded.size(), "Only xx=02 expansion produced (xx=01 filtered)");
        Rule expanded0 = expanded.get(0);
        assertTrue(expanded0.getCore().getId().contains("TRT02P"), "Expansion is for xx=02");

        RuleExecutionResult result = run(expanded0, adsl);
        assertEquals(1, result.getViolationCount(), "TR02SDT missing → expanded rule fires");
    }


    @Test
    void cdiscAd0078_twoPeriods_trxxSdtPresent_noFire()
    {
        Rule template = findByCoreId("CDISC-AD0078");

        IDataTable adsl = MockTable.of().col("STUDYID", "S001").col("USUBJID", "S01")
                .col("TRT01P", "Drug A").col("TRT02P", "Drug B").col("TR02SDT", "2024-01-01")
                .name("ADSL").build();

        java.util.List<Rule> expanded = expand(template, adsl);

        assertEquals(1, expanded.size());
        RuleExecutionResult result = run(expanded.get(0), adsl);
        assertFalse(result.hasViolations(), "TR02SDT present → not_exists is false → no violation");
    }


    @Test
    void cdiscAd0078_threePeriods_partialCoverage_fires()
    {
        // TRT01P + TRT02P + TRT03P, but only TR02SDT present (TR03SDT missing).
        // xx=01 filtered; xx=02 expansion doesn't fire (TR02SDT present);
        // xx=03 expansion fires (TR03SDT missing).
        Rule template = findByCoreId("CDISC-AD0078");

        IDataTable adsl = MockTable.of().col("STUDYID", "S001").col("USUBJID", "S01")
                .col("TRT01P", "Drug A").col("TRT02P", "Drug B").col("TRT03P", "Drug C")
                .col("TR02SDT", "2024-01-01").name("ADSL").build();

        java.util.List<Rule> expanded = expand(template, adsl);

        assertEquals(2, expanded.size(), "xx=02 and xx=03 expansions; xx=01 filtered");

        int totalViolations = 0;
        for (Rule r : expanded)
        {
            totalViolations += run(r, adsl).getViolationCount();
        }
        assertEquals(1, totalViolations,
                "Only xx=03 fires (TR03SDT missing); xx=02 quiet (TR02SDT present)");
    }

    // ---- CDISC-AD0079: symmetric pattern for TRxxEDT ----


    @Test
    void cdiscAd0079_singlePeriodOnly_noFire()
    {
        Rule template = findByCoreId("CDISC-AD0079");

        IDataTable adsl = MockTable.of().col("STUDYID", "S001").col("USUBJID", "S01")
                .col("TRT01P", "Drug A").name("ADSL").build();

        java.util.List<Rule> expanded = expand(template, adsl);
        assertEquals(0, expanded.size());
    }


    @Test
    void cdiscAd0079_twoPeriods_trxxEdtMissing_fires()
    {
        Rule template = findByCoreId("CDISC-AD0079");

        IDataTable adsl = MockTable.of().col("STUDYID", "S001").col("USUBJID", "S01")
                .col("TRT01P", "Drug A").col("TRT02P", "Drug B").name("ADSL").build();

        java.util.List<Rule> expanded = expand(template, adsl);
        assertEquals(1, expanded.size());

        RuleExecutionResult result = run(expanded.get(0), adsl);
        assertEquals(1, result.getViolationCount());
    }

    // ---- Rule metadata sanity ----


    @Test
    void rule0078_carriesWildcardFilter()
    {
        Rule rule = findByCoreId("CDISC-AD0078");
        assertNotNull(rule.getWildcards(), "Expected wildcards to be loaded");
        WildcardFilter filter = rule.getWildcards().get("xx");
        assertNotNull(filter, "Expected filter on group 'xx'");
        assertEquals(2, filter.getMin());
        assertEquals(null, filter.getMax());
    }


    @Test
    void rule0079_carriesWildcardFilter()
    {
        Rule rule = findByCoreId("CDISC-AD0079");
        assertNotNull(rule.getWildcards());
        WildcardFilter filter = rule.getWildcards().get("xx");
        assertNotNull(filter);
        assertEquals(2, filter.getMin());
    }
}
