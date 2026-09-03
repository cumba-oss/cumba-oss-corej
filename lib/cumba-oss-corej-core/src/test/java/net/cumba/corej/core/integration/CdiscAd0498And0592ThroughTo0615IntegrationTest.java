package net.cumba.corej.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Fix #21 — end-to-end integration tests for the PERIOD_PHASE rule family (CDISC-AD0498, 0592–0615)
 * against the post-edit ADaMIG v1.3 rule package.
 *
 * <p>
 * Each rule now declares a {@code lookup_period_phase_column} or {@code period_phase_column_exists}
 * Operation; the Check leaves reference the Operation result via {@code $<id>}. The integration
 * tests assemble a small ADSL fixture and a primary BDS dataset, then invoke
 * {@link RuleRunner#execute} directly — no wildcard expansion, since the rules no longer carry
 * static column-name wildcards.
 * </p>
 */
class CdiscAd0498And0592ThroughTo0615IntegrationTest
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
    // CDISC-AD0498 — PxxSw existence
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0498_missingColumnFires()
    {
        // ADSL has P01S1 but not P02S1.
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("P01S1", "x").name("ADSL")
                .build();
        // Rows: (APERIOD=1, ASPER=1) → P01S1 exists, no fire;
        // (APERIOD=2, ASPER=1) → P02S1 absent, fires.
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1").colLong("APERIOD", 1L, 2L)
                .colLong("ASPER", 1L, 1L).col("AETERM", "AE1", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0498");
        assertNotNull(rule);
        assertEquals(1, violationsOn(rule, adae, resolverOf(tables)),
                "(APERIOD=2, ASPER=1) row fires; (APERIOD=1, ASPER=1) row does not");
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0592 — APxx deterministic
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0592_match_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("AP01SDT", "2024-01-01")
                .col("AP02SDT", "2024-02-01").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1").colLong("APERIOD", 1L, 2L)
                .col("APERSDT", "2024-01-01", "2024-02-01").col("AETERM", "AE1", "AE1").name("ADAE")
                .build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0592");
        assertEquals(0, violationsOn(rule, adae, resolverOf(tables)),
                "APERSDT matches APxxSDT for both rows → no fire");
    }


    @Test
    void cdiscAd0592_mismatch_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("AP01SDT", "2024-01-01")
                .col("AP02SDT", "2024-02-01").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1").colLong("APERIOD", 1L, 2L)
                .col("APERSDT", "2024-01-01", "2024-99-99") // second row mismatches
                .col("AETERM", "AE1", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0592");
        assertEquals(1, violationsOn(rule, adae, resolverOf(tables)),
                "Second row APERSDT differs from AP02SDT → one violation");
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0598 — PxxSw deterministic
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0598_match_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("P01S1SDT", "2024-01-01")
                .col("P02S1SDT", "2024-02-01").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1").colLong("APERIOD", 1L, 2L)
                .colLong("ASPER", 1L, 1L).col("ASPRSDT", "2024-01-01", "2024-02-01")
                .col("AETERM", "AE1", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0598");
        assertEquals(0, violationsOn(rule, adae, resolverOf(tables)),
                "ASPRSDT matches PxxSwSDT → no fire");
    }


    @Test
    void cdiscAd0598_mismatch_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("P01S1SDT", "2024-01-01")
                .col("P02S1SDT", "2024-02-01").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1").colLong("APERIOD", 1L, 2L)
                .colLong("ASPER", 1L, 1L).col("ASPRSDT", "2024-01-01", "1999-12-31")
                .col("AETERM", "AE1", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0598");
        assertEquals(1, violationsOn(rule, adae, resolverOf(tables)),
                "Second row ASPRSDT differs from P02S1SDT → one violation");
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0605 — PHw deterministic
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0605_match_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("PH1SDT", "2024-01-01")
                .col("PH2SDT", "2024-02-01").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1").colLong("APHASEN", 1L, 2L)
                .col("PHSDT", "2024-01-01", "2024-02-01").col("AETERM", "AE1", "AE1").name("ADAE")
                .build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0605");
        assertEquals(0, violationsOn(rule, adae, resolverOf(tables)),
                "PHSDT matches PHwSDT (w=APHASEN) → no fire");
    }


    @Test
    void cdiscAd0605_mismatch_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("PH1SDT", "2024-01-01")
                .col("PH2SDT", "2024-02-01").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").colLong("APHASEN", 1L)
                .col("PHSDT", "2099-09-09").col("AETERM", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0605");
        assertEquals(1, violationsOn(rule, adae, resolverOf(tables)),
                "PHSDT differs from PH1SDT → one violation");
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0604 — PHw match-any (APHASEN absent)
    //
    // Rule body uses {@code is_not_contained_by} so the {@code List<String>}
    // GroupedResult value from {@code lookup_period_phase_column} is consumed
    // as a set-membership test: fire iff PHSDT is not in the set of all
    // {@code PHwSDT} values for the row's USUBJID.
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0604_phsdtMatchesOnePhwSdt_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("PH1SDT", "2024-01-01")
                .col("PH2SDT", "2024-02-01").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").col("PHSDT", "2024-02-01") // matches
                                                                                         // PH2SDT
                .col("AETERM", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0604");
        assertEquals(0, violationsOn(rule, adae, resolverOf(tables)),
                "PHSDT is contained in {PH1SDT, PH2SDT} → no fire");
    }


    @Test
    void cdiscAd0604_phsdtMatchesNoPhwSdt_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("PH1SDT", "2024-01-01")
                .col("PH2SDT", "2024-02-01").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").col("PHSDT", "1999-12-31") // matches
                                                                                         // neither
                .col("AETERM", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        Rule rule = findByCoreId("CDISC-AD0604");
        assertEquals(1, violationsOn(rule, adae, resolverOf(tables)),
                "PHSDT is not in {PH1SDT, PH2SDT} → one violation");
    }

    // -----------------------------------------------------------------------
    // Out-of-scope confirmation — ADSL itself is now scope-excluded
    // -----------------------------------------------------------------------


    @Test
    void scope_excludesSubjectLevel()
    {
        // The post-edit Scope is BASIC DATA STRUCTURE + OCCURRENCE DATA STRUCTURE,
        // so SUBJECT LEVEL ANALYSIS DATASET (ADSL) is excluded.
        // M2-D23: the declaration moved from Scope.Classes to Scope.Data_Structures, so the
        // matcher under test is the data-structure one — the channel RuleRunner actually gates on.
        Rule rule = findByCoreId("CDISC-AD0592");
        assertTrue(ScopeMatcher.matchesDataStructure(rule, "BASIC DATA STRUCTURE"));
        assertTrue(ScopeMatcher.matchesDataStructure(rule, "OCCURRENCE DATA STRUCTURE"));
        assertEquals(false,
                ScopeMatcher.matchesDataStructure(rule, "SUBJECT LEVEL ANALYSIS DATASET"));

        Rule rule0498 = findByCoreId("CDISC-AD0498");
        assertEquals(false,
                ScopeMatcher.matchesDataStructure(rule0498, "SUBJECT LEVEL ANALYSIS DATASET"));

        Rule rule0604 = findByCoreId("CDISC-AD0604");
        assertEquals(false,
                ScopeMatcher.matchesDataStructure(rule0604, "SUBJECT LEVEL ANALYSIS DATASET"));
    }

}
