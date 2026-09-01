package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Probe for the nine newly-authored PMDA-AD value-indexed rules
 * (PMDA-AD0102/0103/0104/0605/0607/0609/0611/0613/0615), each a mirror of its shipping CDISC-AD
 * twin.
 *
 * <p>
 * The rules use the {@code ADSL.<template-with-${VAR:fmt}>} substitution syntax the engine already
 * executes (see {@link CdiscAd0102To0707IntegrationTest}). This probe loads each authored
 * {@code rules-src} check directly (no corpus regeneration), proves it raises to a native
 * expression (zero-legacy gate), and replicates the firing behaviour of three representative twins:
 * AD0102 (TRTxxP not_exists), AD0103 (greater_than guard) and AD0605 (PHwSDT not_equal_to).
 * </p>
 */
class PmdaAdValueIndexedTwinsProbeTest
{

    private static final YAMLMapper MAPPER = (YAMLMapper) new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String[] AD_IDS =
    {
            "AD0102", "AD0103", "AD0104", "AD0605", "AD0607", "AD0609", "AD0611", "AD0613", "AD0615"
    };

    private static Rule loadRule(String adId) throws Exception
    {
        Path file = Path.of("src/test/resources/fixtures/rules/checks/PMDA/PMDA-" + adId + ".yaml");
        Rule rule = MAPPER.readValue(Files.readString(file), Rule.class);
        // rules-src no longer carries Rule_Type / Sensitivity — the loader
        // derives them, so a hand-bound rule must be completed the same way.
        // Form-B operations (PLAN-retire-corpus-transforms phase 8) carry no operator
        // until normalized — the same pass the loader and RuleScaffold run.
        RulePackageLoader.normalizeOperations(rule);
        RulePackageLoader.deriveOmittedFields(rule);
        rule.setCheckExpr(CheckToExpr.toExpr(rule.getCheck()));
        return rule;
    }


    private static DatasetResolver resolverOf(Map<String, IDataTable> tables)
    {
        return tables::get;
    }


    private static int violations(Rule rule, IDataTable table, DatasetResolver resolver)
    {
        RuleExecutionResult result = RuleRunner.execute(rule, table, resolver);
        return result.getViolationCount();
    }

    // -----------------------------------------------------------------------
    // All nine rules must raise to a native expression.
    // -----------------------------------------------------------------------


    @Test
    void allNineRulesConvertToNativeExpression() throws Exception
    {
        for (String adId : AD_IDS)
        {
            assertNotNull(loadRule(adId).getCheckExpr(),
                    "PMDA-" + adId + " must raise to a native expression (zero-legacy gate)");
        }
    }

    // -----------------------------------------------------------------------
    // AD0102 — ADSL.TRT${APERIOD:%02d}P must exist.
    // -----------------------------------------------------------------------


    @Test
    void ad0102_firesWhenTrtxxPMissing() throws Exception
    {
        Rule rule = loadRule("AD0102");
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("TRT01P", "Drug A").name("ADSL")
                .build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1").colLong("APERIOD", 1L, 2L)
                .col("AETERM", "AE1", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        assertEquals(1, violations(rule, adae, resolverOf(tables)),
                "ADSL has TRT01P but no TRT02P → APERIOD=2 row fires");
    }


    @Test
    void ad0102_noFireWhenAllTrtxxPPresent() throws Exception
    {
        Rule rule = loadRule("AD0102");
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("TRT01P", "Drug A")
                .col("TRT02P", "Drug B").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1").colLong("APERIOD", 1L, 2L)
                .col("AETERM", "AE1", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        assertEquals(0, violations(rule, adae, resolverOf(tables)),
                "ADSL has both TRT01P and TRT02P → no fire");
    }

    // -----------------------------------------------------------------------
    // AD0103 — TR${APERIOD:%02d}SDT checked for every populated APERIOD.
    // -----------------------------------------------------------------------


    @Test
    void ad0103_everyPopulatedPeriodFiresWhenTrxxSdtMissing() throws Exception
    {
        Rule rule = loadRule("AD0103");
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").name("ADSL") // no TRxxSDT columns
                .build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1").colLong("APERIOD", 1L, 2L)
                .col("AETERM", "AE1", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        assertEquals(2, violations(rule, adae, resolverOf(tables)),
                "no period guard: both APERIOD=1 (missing TR01SDT) and APERIOD=2 (missing TR02SDT) "
                        + "fire");
    }

    // -----------------------------------------------------------------------
    // AD0605 — PHSDT must equal ADSL.PH${APHASEN:%d}SDT.
    // -----------------------------------------------------------------------


    @Test
    void ad0605_firesWhenPhsdtMismatch() throws Exception
    {
        Rule rule = loadRule("AD0605");
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("PH1SDT", "2024-01-01")
                .col("PH2SDT", "2024-02-01").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1").colLong("APHASEN", 1L, 2L)
                .col("PHSDT", "2024-01-01", "2024-09-09").col("AETERM", "AE1", "AE1").name("ADAE")
                .build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        assertEquals(1, violations(rule, adae, resolverOf(tables)),
                "APHASEN=2 row: PHSDT 2024-09-09 != PH2SDT 2024-02-01 → fires; APHASEN=1 matches");
    }


    /**
     * Fix #269 / EC-84 — a dataset that MIXES APHASEN-populated and APHASEN-blank rows used to
     * abort the whole run: {@code Primitives.scan} resolves the substituted right-hand operand for
     * <em>every</em> row (not just the rows still in the {@code all} candidate mask), so the blank
     * driver raised {@code OperandSubstitutor.SubstitutionException} out through
     * {@code RuleRunner}. The unresolvable substitution is now "no value" for that row — the same
     * disposition an absent target column already had — so the rule evaluates and only the genuine
     * mismatch fires.
     */
    @Test
    void ad0605_mixedAphasenPopulationEvaluatesInsteadOfCrashing() throws Exception
    {
        Rule rule = loadRule("AD0605");
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("PH1SDT", "2024-01-01")
                .col("PH2SDT", "2024-02-01").name("ADSL").build();
        // Row 0: APHASEN=1, PHSDT matches PH1SDT → silent.
        // Row 1: APHASEN missing → the driver cannot resolve a column name.
        // Row 2: APHASEN=2, PHSDT 2024-09-09 != PH2SDT 2024-02-01 → fires.
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1", "S1")
                .colLong("APHASEN", 1L, null, 2L)
                .col("PHSDT", "2024-01-01", "2024-07-07", "2024-09-09")
                .col("AETERM", "AE1", "AE1", "AE1").name("ADAE").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        assertEquals(1, violations(rule, adae, resolverOf(tables)),
                "only the APHASEN=2 mismatch fires; the blank-driver row neither crashes nor "
                        + "fires");
    }


    /**
     * Fix #269 / EC-84 — the under-reach half of the same pin. At least one row must survive the
     * leading {@code not empty(APHASEN) and not empty(PHSDT)} conjuncts, otherwise
     * {@code compileAnd}'s empty-mask short-circuit skips the comparison entirely and the assertion
     * is vacuous (that is exactly what the pre-fix fixture law exploited). Row 0 keeps the mask
     * non-empty; row 1's blank driver must then be silent rather than firing.
     */
    @Test
    void ad0605_blankDriverRowIsSilentWhileAPopulatedRowKeepsTheMaskAlive() throws Exception
    {
        Rule rule = loadRule("AD0605");
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("PH1SDT", "2024-01-01")
                .name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1").colLong("APHASEN", 1L, null)
                .col("PHSDT", "2024-01-01", "2024-09-09").col("AETERM", "AE1", "AE1").name("ADAE")
                .build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        assertEquals(0, violations(rule, adae, resolverOf(tables)),
                "row 0 matches and keeps the candidate mask non-empty; row 1's blank APHASEN is "
                        + "excluded by the rule's own guard, so nothing fires");
    }


    @Test
    void ad0605_noFireWhenPhsdtMatches() throws Exception
    {
        Rule rule = loadRule("AD0605");
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("PH1SDT", "2024-01-01")
                .col("PH2SDT", "2024-02-01").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1").colLong("APHASEN", 1L, 2L)
                .col("PHSDT", "2024-01-01", "2024-02-01").col("AETERM", "AE1", "AE1").name("ADAE")
                .build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);

        assertEquals(0, violations(rule, adae, resolverOf(tables)),
                "both rows: PHSDT equals PHwSDT for w=APHASEN → no fire");
    }
}
