package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.exec.StubMetadataProvider;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * PLAN-adam-gap-revalidation Phase 3.1 — Java confirmation that the G2 cross-standard value join
 * ({@code ADSL.<var> vs DM.<var>} after a {@code Match_Datasets: [{DM, USUBJID}]} join) executes on
 * the shipping native corpus when the SDTM reference dataset (DM) is co-loaded via the
 * {@link DatasetResolver}. Representative rule: CDISC-AD0204 ({@code AGE != DM.AGE}). Edge rules:
 * CDISC-AD0210 ({@code ARM != DM.ARM}, character), CDISC-AD0367 ({@code ACTARM != DM.ACTARM}), and
 * CDISC-AD0053 (USUBJID membership in DM).
 *
 * <p>
 * Verdict semantics: the rule fires on a deliberate value mismatch and does not fire on a matching
 * fixture ⇒ SUPPORTED (registry {@code ⚠SHIPS-DISPUTED} was a run-configuration assumption, not an
 * engine incapability). The "DM absent" configuration documents the self-skip behaviour the
 * registry originally assumed.
 * </p>
 */
class AdamGapRevalidationG2ProbeTest
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


    /**
     * A resolver that also enumerates its dataset names, required by rules that use
     * {@code dataset_names()} (e.g. CDISC-AD0053's presence guard).
     */
    private static DatasetResolver.WithInventory inventoryResolverOf(Map<String, IDataTable> tables)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String domainName)
            {
                return tables.get(domainName);
            }


            @Override
            public java.util.Set<String> availableDatasets()
            {
                return tables.keySet();
            }
        };
    }

    /**
     * Fix #223 — the declare channel. CDISC-AD0204 / AD0210 / AD0367 are scoped
     * {@code Scope.Data_Structures.Include:[SUBJECT LEVEL ANALYSIS DATASET]}; the 7-arg
     * {@code execute} overload lets the fixture declare that instead of leaving the gate to
     * re-derive it from the dataset name.
     */
    private static final MetadataProvider DEFINE = new StubMetadataProvider()
            .declares("ADSL", "SUBJECT LEVEL ANALYSIS DATASET")
            .declares("ADAE", "OCCURRENCE DATA STRUCTURE", "ADVERSE EVENT");

    private static int violationsOn(Rule rule, IDataTable table, DatasetResolver resolver)
    {
        RuleExecutionResult result = RuleRunner.execute(rule, table, resolver, null, null, null,
                DEFINE);
        return result.getViolationCount();
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0204 — ADSL.AGE must equal DM.AGE for matching USUBJID
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0204_ageMismatch_dmCoLoaded_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("AGE", "42").name("ADSL").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S1").col("AGE", "43").name("DM").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("DM", dm);

        Rule rule = findByCoreId("CDISC-AD0204");
        assertEquals(1, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "ADSL.AGE=42 != DM.AGE=43 with DM co-loaded as reference → fires");
    }


    @Test
    void cdiscAd0204_ageMatch_dmCoLoaded_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("AGE", "42").name("ADSL").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S1").col("AGE", "42").name("DM").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("DM", dm);

        Rule rule = findByCoreId("CDISC-AD0204");
        assertEquals(0, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "ADSL.AGE == DM.AGE with DM co-loaded → no fire");
    }


    @Test
    void cdiscAd0204_dmAbsent_selfSkips_noFire()
    {
        // UPDATE (2026-07): CDISC-AD0204/0210/0367 were aligned to the CDISC-AD0053
        // pattern — each now guards on `not(shares_no_elements_with(dataset_names(),
        // [DM]))`. With DM absent from the supplied datasets the guard is false, so
        // the rule self-skips (no spurious fire). This supersedes the earlier
        // "fires spuriously / co-load DM is a run requirement" finding: the guard
        // now makes DM-absent a clean skip rather than a false-positive hazard. (The
        // G2 value join still fires on a real mismatch when DM IS co-loaded — see
        // cdiscAd0204_ageMismatch_dmCoLoaded_fires.)
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("AGE", "42").name("ADSL").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl); // no DM

        Rule rule = findByCoreId("CDISC-AD0204");
        assertEquals(0, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "DM absent from $datasets → dataset_names() guard false → self-skip (no fire)");
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0210 — character-value join (ARM != DM.ARM)
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0210_armMismatch_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("ARM", "Placebo").name("ADSL")
                .build();
        IDataTable dm = MockTable.of().col("USUBJID", "S1").col("ARM", "Drug A").name("DM").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("DM", dm);

        Rule rule = findByCoreId("CDISC-AD0210");
        assertEquals(1, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "ADSL.ARM != DM.ARM with DM co-loaded → fires");
    }


    @Test
    void cdiscAd0210_armMatch_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("ARM", "Drug A").name("ADSL")
                .build();
        IDataTable dm = MockTable.of().col("USUBJID", "S1").col("ARM", "Drug A").name("DM").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("DM", dm);

        Rule rule = findByCoreId("CDISC-AD0210");
        assertEquals(0, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "ADSL.ARM == DM.ARM → no fire");
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0367 — ACTARM != DM.ACTARM
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0367_actarmMismatch_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("ACTARM", "Placebo").name("ADSL")
                .build();
        IDataTable dm = MockTable.of().col("USUBJID", "S1").col("ACTARM", "Drug A").name("DM")
                .build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("DM", dm);

        Rule rule = findByCoreId("CDISC-AD0367");
        assertEquals(1, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "ADSL.ACTARM != DM.ACTARM with DM co-loaded → fires");
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0053 — USUBJID must be present in DM
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0053_usubjidMissingFromDm_fires()
    {
        // ADSL subject S9 has no DM row → left join leaves DM.USUBJID empty → fires.
        IDataTable adsl = MockTable.of().col("USUBJID", "S9").name("ADSL").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S1").name("DM").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("DM", dm);

        Rule rule = findByCoreId("CDISC-AD0053");
        assertEquals(1, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "ADSL.USUBJID=S9 absent from DM → fires");
    }


    @Test
    void cdiscAd0053_usubjidPresentInDm_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").name("ADSL").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S1").name("DM").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("DM", dm);

        Rule rule = findByCoreId("CDISC-AD0053");
        assertEquals(0, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "ADSL.USUBJID=S1 present in DM → no fire");
    }


    @Test
    void cdiscAd0053_dmAbsent_selfSkips_noFire()
    {
        // Contrast to the un-guarded value compares (0204 etc.): 0053's Check is guarded by
        // `not(shares_no_elements_with($datasets, [DM]))` where $datasets = dataset_names(). When
        // DM
        // is NOT among the supplied datasets the guard is false → the rule self-skips (no spurious
        // fire), even though ADSL.USUBJID=S9 would be "absent from DM" if DM were present.
        IDataTable adsl = MockTable.of().col("USUBJID", "S9").name("ADSL").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl); // no DM

        Rule rule = findByCoreId("CDISC-AD0053");
        assertEquals(0, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "DM absent from $datasets → dataset_names() guard false → self-skip (no fire)");
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0591 — shared-name ADSL/ADaM variable value equality.
    //
    // FINDING (Phase 3): this rule ships an UNRESOLVED template. Its native
    // Check references $adsl_value / $current_value but the shipped rule carries
    // NO Operations block and NO Match_Datasets — the operands never resolve, so
    // `not empty($adsl_value)` is false and the rule is a silent no-op. It cannot
    // enforce its intent (dynamic enumeration of the shared-name variable set is
    // unsupported). GENUINE-ENGINE-GAP (T3). Only present in the ADaMIG 1-2
    // package, so this probe loads that package directly.
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0591_sharedVarMismatch_neverFires_documentsGenuineGap() throws Exception
    {
        RulePackage pkg12 = RulePackageLoader
                .loadCombined(Path.of(System.getProperty("projectBasedir"),
                        "src/test/resources/fixtures/rules/packages/rules-adamig-1-2.json"));
        Rule rule = pkg12.getRules().values().stream()
                .filter(r -> r.getCore() != null && "CDISC-AD0591".equals(r.getCore().getId()))
                .findFirst().orElseThrow();

        // A deliberate shared-variable mismatch: ADSL and ADAE both carry AGE with
        // different values for S1. A working shared-value check would fire; the
        // unresolved template does not.
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("AGE", "42").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").col("AGE", "99").name("ADAE").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("ADAE", adae);

        assertEquals(0, violationsOn(rule, adae, resolverOf(tables)),
                "CDISC-AD0591 template operands never resolve → silent no-op (genuine T3 gap)");
    }

}
