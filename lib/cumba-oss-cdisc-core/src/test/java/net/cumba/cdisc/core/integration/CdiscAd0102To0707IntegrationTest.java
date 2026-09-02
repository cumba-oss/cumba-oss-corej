package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Fix #22 — end-to-end integration tests for the ADSL cross-dataset variable-metadata lookup rule
 * family (CDISC-AD0102/0103/0104/0500/0706/0707) after the rule bodies were re-routed onto Fix
 * #37's substitution syntax.
 *
 * <p>
 * Each rule now uses {@code ADSL.<template-with-${VAR[:fmt]}-or-${*}>} as the column reference; the
 * engine resolves the substituted column per row via the
 * {@link net.cumba.cdisc.core.exec.OperandSubstitutor} pipeline. Tests assemble a small ADSL
 * fixture and a primary BDS dataset and invoke {@link RuleRunner#execute} directly.
 * </p>
 */
class CdiscAd0102To0707IntegrationTest
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


    private static DatasetResolver resolverOf(Map<String, IDataTable> tables)
    {
        return tables::get;
    }


    private static int violationsOn(Rule rule, IDataTable table, DatasetResolver resolver)
    {
        RuleExecutionResult result = RuleRunner.execute(rule, table, resolver);
        return result.getViolationCount();
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0102 — TRT${APERIOD:%02d}P column must exist in ADSL
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0102_columnExists_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("TRT01P", "Drug A")
                .col("TRT02P", "Drug B").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1").colLong("APERIOD", 1L, 2L)
                .col("AETERM", "AE1", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0102");
        assertEquals(0, violationsOn(rule, adae, resolverOf(tables)),
                "ADSL has both TRT01P and TRT02P → no fire");
    }


    @Test
    void cdiscAd0102_columnMissing_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("TRT01P", "Drug A").name("ADSL")
                .build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1").colLong("APERIOD", 1L, 2L)
                .col("AETERM", "AE1", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0102");
        assertEquals(1, violationsOn(rule, adae, resolverOf(tables)),
                "ADSL missing TRT02P → APERIOD=2 row fires");
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0103 — TR${APERIOD:%02d}SDT must exist when APERIOD>1
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0103_period1_neverFires()
    {
        // APERIOD=1 is filtered by `greater_than 1` guard; no row reaches the substitution leaf.
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").name("ADSL") // no TR01SDT
                .build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").colLong("APERIOD", 1L)
                .col("AETERM", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0103");
        assertEquals(0, violationsOn(rule, adae, resolverOf(tables)),
                "APERIOD=1 is below the greater_than 1 guard → no fire");
    }


    @Test
    void cdiscAd0103_period2_columnMissing_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").name("ADSL") // no TR02SDT
                .build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").colLong("APERIOD", 2L)
                .col("AETERM", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0103");
        assertEquals(1, violationsOn(rule, adae, resolverOf(tables)),
                "APERIOD=2, ADSL missing TR02SDT → fires");
    }


    @Test
    void cdiscAd0103_period2_columnExists_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("TR02SDT", "2024-02-01")
                .name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").colLong("APERIOD", 2L)
                .col("AETERM", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0103");
        assertEquals(0, violationsOn(rule, adae, resolverOf(tables)), "ADSL has TR02SDT → no fire");
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0500 — APHASE value must equal at least one ADSL.APHASE${*} value
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0500_aphaseInSet_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("APHASE1", "Screening")
                .col("APHASE2", "Treatment").name("ADSL").build();
        IDataTable adlb = MockTable.of().col("USUBJID", "S1").col("APHASE", "Treatment")
                .col("PARAMCD", "P1").name("ADLB").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADLB", adlb);

        Rule rule = findByCoreId("CDISC-AD0500");
        assertEquals(0, violationsOn(rule, adlb, resolverOf(tables)),
                "APHASE=Treatment is in {Screening, Treatment} → no fire");
    }


    @Test
    void cdiscAd0500_aphaseNotInSet_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("APHASE1", "Screening")
                .col("APHASE2", "Treatment").name("ADSL").build();
        IDataTable adlb = MockTable.of().col("USUBJID", "S1").col("APHASE", "Followup")
                .col("PARAMCD", "P1").name("ADLB").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADLB", adlb);

        Rule rule = findByCoreId("CDISC-AD0500");
        assertEquals(1, violationsOn(rule, adlb, resolverOf(tables)),
                "APHASE=Followup not in {Screening, Treatment} → fires");
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0706 — ADSL.P${APERIOD:%02d}S${*} not_exists fires when no PxxSw
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0706_periodHasMatchingPxxSw_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("P02S1", "x").name("ADSL")
                .build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").colLong("APERIOD", 2L)
                .colLong("ASPER", 1L).col("AETERM", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0706");
        assertEquals(0, violationsOn(rule, adae, resolverOf(tables)),
                "ADSL has P02S1 → APERIOD=2 row finds at least one PxxSw → no fire");
    }


    @Test
    void cdiscAd0706_periodHasNoMatchingPxxSw_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("P01S1", "x").name("ADSL")
                .build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").colLong("APERIOD", 2L)
                .colLong("ASPER", 1L).col("AETERM", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0706");
        assertEquals(1, violationsOn(rule, adae, resolverOf(tables)),
                "ADSL has only P01S1, no P02S<w> → APERIOD=2 row fires");
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0707 — ADSL.P${*}S${ASPER:%d} not_exists fires when no PxxSw
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0707_asperHasMatchingPxxSw_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("P01S2", "x").name("ADSL")
                .build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").colLong("APERIOD", 1L)
                .colLong("ASPER", 2L).col("AETERM", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0707");
        assertEquals(0, violationsOn(rule, adae, resolverOf(tables)),
                "ADSL has P01S2 → ASPER=2 row finds at least one PxxS2 → no fire");
    }


    @Test
    void cdiscAd0707_asperHasNoMatchingPxxSw_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("P01S1", "x").name("ADSL")
                .build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").colLong("APERIOD", 1L)
                .colLong("ASPER", 2L).col("AETERM", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0707");
        assertEquals(1, violationsOn(rule, adae, resolverOf(tables)),
                "ADSL has only P01S1, no PxxS2 → ASPER=2 row fires");
    }

    // -----------------------------------------------------------------------
    // Sanity — all six rules are loaded with no `loadError`
    // -----------------------------------------------------------------------


    @Test
    void allSixRulesLoadCleanly()
    {
        String[] ids =
        {
                "CDISC-AD0102", "CDISC-AD0103", "CDISC-AD0104", "CDISC-AD0500", "CDISC-AD0706",
                "CDISC-AD0707"
        };
        for (String id : ids)
        {
            Rule rule = findByCoreId(id);
            assertNotNull(rule, id + " missing from package");
            assertEquals(null, rule.getLoadError(),
                    id + " should not have a loadError after Fix #37 migration");
        }
    }

}
