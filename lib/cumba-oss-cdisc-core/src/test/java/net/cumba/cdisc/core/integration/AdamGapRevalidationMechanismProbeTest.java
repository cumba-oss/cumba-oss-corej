package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
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
 * PLAN-adam-gap-revalidation Phase 2 — engine-capability probes for the ADaM gap mechanisms that
 * were not already covered by a dedicated integration test. Each mechanism is exercised with a
 * representative / edge rule from the shipping native corpus (ADaMIG 1-2):
 *
 * <ul>
 * <li>G1 value-indexed value-compare siblings — 0597 ({@code ${APERIOD:%02d}} single axis), 0603
 * (two axis), 0615 ({@code ${APHASEN:%d}} index), 0614 ({@code ${*}} match-any). These sibling rows
 * differ from the precedent-test representatives (0592/0598/0605/0604) only in the column suffix
 * and substitution axis.</li>
 * <li>G4 universal-absence — 0581 (fires when no treatment variable is present in a BDS
 * dataset).</li>
 * <li>G5 within-record across-columns max — 0084 (fires when some {@code TRxxEDT} exceeds
 * {@code TRTEDT}, via wildcard-expanded {@code >} comparison).</li>
 * <li>G6 wildcard token inside an {@code Operations[].name} — 0353 (the {@code max("AyIND", …)}
 * baseline-indicator comparison). This row was <b>not</b> supported when it was first written up:
 * the token was copied verbatim into the expanded rule and the comparison fired on every populated
 * row. Fix #152 substitutes it, and the probe below now expands the template and asserts both
 * directions instead of asserting that the raw template loads.</li>
 * </ul>
 *
 * <p>
 * A firing violating fixture + a non-firing clean fixture ⇒ the mechanism is SUPPORTED and the
 * registry's "unsupported / Not Executable" narrative is stale.
 * </p>
 */
class AdamGapRevalidationMechanismProbeTest
{

    private static final Path RULES_FILE = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/fixtures/rules/packages/rules-adamig-1-2.json");

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
     * Fix #223 — the declare channel. CDISC-AD0581 is scoped
     * {@code Scope.Data_Structures.Include:[BASIC DATA STRUCTURE, MEDICAL DEVICE BASIC DATA
     * STRUCTURE]} and CDISC-AD0084 {@code Include:[SUBJECT LEVEL ANALYSIS DATASET]}. On the 3-arg
     * {@code execute} the ADQS fixture had to carry {@code PARAMCD}/{@code AVAL} — columns no
     * {@code Check} of AD0581 reads — purely so the BDS inference would agree with the scope. The
     * declaration replaces that shaping.
     */
    private static final MetadataProvider DEFINE = new StubMetadataProvider()
            .declares("ADSL", "SUBJECT LEVEL ANALYSIS DATASET")
            .declares("ADQS", "BASIC DATA STRUCTURE")
            .declares("ADAE", "OCCURRENCE DATA STRUCTURE", "ADVERSE EVENT");

    private static int violationsOn(Rule rule, IDataTable table, Map<String, IDataTable> tables)
    {
        RuleExecutionResult result = RuleRunner.execute(rule, table, resolverOf(tables), null, null,
                null, DEFINE);
        return result.getViolationCount();
    }


    private static Map<String, IDataTable> tablesOf(IDataTable adsl, IDataTable primary,
            String primaryName)
    {
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put(primaryName, primary);
        return tables;
    }

    // -------- G1: 0597 — APEREDTM != ADSL.AP${APERIOD:%02d}EDTM (single axis) --------


    @Test
    void cdiscAd0597_mismatch_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("AP02EDTM", "2024-02-01T00:00")
                .name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").colLong("APERIOD", 2L)
                .col("APEREDTM", "1999-12-31T00:00").name("ADAE").build();
        Rule rule = findByCoreId("CDISC-AD0597");
        assertEquals(1, violationsOn(rule, adae, tablesOf(adsl, adae, "ADAE")),
                "APEREDTM != ADSL.AP02EDTM → fires");
    }


    @Test
    void cdiscAd0597_match_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("AP02EDTM", "2024-02-01T00:00")
                .name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").colLong("APERIOD", 2L)
                .col("APEREDTM", "2024-02-01T00:00").name("ADAE").build();
        Rule rule = findByCoreId("CDISC-AD0597");
        assertEquals(0, violationsOn(rule, adae, tablesOf(adsl, adae, "ADAE")),
                "APEREDTM == ADSL.AP02EDTM → no fire");
    }

    // -------- G1: 0615 — PHEDTM != ADSL.PH${APHASEN:%d}EDTM (index axis) --------


    @Test
    void cdiscAd0615_mismatch_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("PH2EDTM", "2024-02-01T00:00")
                .name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").colLong("APHASEN", 2L)
                .col("PHEDTM", "1999-12-31T00:00").name("ADAE").build();
        Rule rule = findByCoreId("CDISC-AD0615");
        assertEquals(1, violationsOn(rule, adae, tablesOf(adsl, adae, "ADAE")),
                "PHEDTM != ADSL.PH2EDTM → fires");
    }

    // -------- G1: 0614 — PHEDTM not in ADSL.PH${*}EDTM (match-any) --------


    @Test
    void cdiscAd0614_noMatch_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("PH1EDTM", "2024-01-01T00:00")
                .col("PH2EDTM", "2024-02-01T00:00").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").col("PHEDTM", "1999-12-31T00:00")
                .name("ADAE").build();
        Rule rule = findByCoreId("CDISC-AD0614");
        assertEquals(1, violationsOn(rule, adae, tablesOf(adsl, adae, "ADAE")),
                "PHEDTM in no ADSL.PH{n}EDTM → fires");
    }


    @Test
    void cdiscAd0614_match_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("PH1EDTM", "2024-01-01T00:00")
                .col("PH2EDTM", "2024-02-01T00:00").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1").col("PHEDTM", "2024-02-01T00:00")
                .name("ADAE").build();
        Rule rule = findByCoreId("CDISC-AD0614");
        assertEquals(0, violationsOn(rule, adae, tablesOf(adsl, adae, "ADAE")),
                "PHEDTM matches ADSL.PH2EDTM → no fire");
    }

    // -------- G4: 0581 — universal-absence (no treatment variable present) --------


    @Test
    void cdiscAd0581_noTreatmentVar_fires()
    {
        // BDS dataset carrying none of the treatment variables → the rule fires once (Dataset
        // sensitivity). Fix #223: ADQS is BDS because the sponsor DECLARES it so (see DEFINE) —
        // the fixture no longer carries PARAMCD / AVAL, which AD0581's Check never reads and
        // which existed only to make the column heuristic infer BASIC DATA STRUCTURE.
        IDataTable adbds = MockTable.of().col("USUBJID", "S1").name("ADQS").build();
        Rule rule = findByCoreId("CDISC-AD0581");
        assertEquals(1, violationsOn(rule, adbds, tablesOf(adbds, adbds, "ADQS")),
                "no treatment variable present in the BDS dataset → fires once");
    }


    @Test
    void cdiscAd0581_treatmentVarPresent_noFire()
    {
        IDataTable adbds = MockTable.of().col("USUBJID", "S1").col("TRTP", "Drug A").name("ADQS")
                .build();
        Rule rule = findByCoreId("CDISC-AD0581");
        assertEquals(0, violationsOn(rule, adbds, tablesOf(adbds, adbds, "ADQS")),
                "TRTP present → not_exists branch false → no fire");
    }

    // -------- G5: 0084 — TRTEDT vs max(TRxxEDT). G6: 0353 — max("AyIND", …). --------
    //
    // These use `xx`/`y` WildcardExpander tokens (not runtime ${...} substitution). The wildcard is
    // concretised at GENERATION time against the dataset's actual column metadata, so the raw
    // shipping template handed straight to RuleRunner.execute still names `ByIND` — a column no
    // fixture has — and reports nothing whatever the data says.
    //
    // Fix #152 corrected the G6 row here. It previously asserted only `getLoadError() == null` plus
    // "0 violations" on the RAW template over a fixture where A1IND == B1IND: three assertions that
    // cannot fail, since the unexpanded Check reads an absent column. That vacuous probe was the
    // whole evidential basis for `adam-gap-revalidation.md`'s SUPPORTED verdict while the defect
    // was live. The replacement runs the template through WildcardExpander.expand against real
    // column metadata — the same call RuleGenerator makes — and then executes BOTH directions on
    // the expanded rule, so a regression in either the expansion or the comparison turns it red.


    @Test
    void cdiscAd0084_shipsFullyExecutable_loadsClean_executesWithoutError()
    {
        Rule rule = findByCoreId("CDISC-AD0084");
        assertEquals(null, rule.getLoadError(), "CDISC-AD0084 loads without error");
        assertEquals(net.cumba.cdisc.core.model.Executability.FULLY_EXECUTABLE,
                rule.getExecutability(), "CDISC-AD0084 ships Fully Executable");
        // EC-8: AD0084 now uses the row_max Operation (name_pattern over TRxxEDT), so it evaluates
        // at this layer without any wildcard expansion. TRTEDT equals max(TR01EDT, TR02EDT) = 100,
        // so the not_equal_to Check does not fire → clean execution, 0 violations.
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").colLong("TRTEDT", 100L)
                .colLong("TR01EDT", 50L).colLong("TR02EDT", 100L).name("ADSL").build();
        assertEquals(0, violationsOn(rule, adsl, tablesOf(adsl, adsl, "ADSL")),
                "TRTEDT equals max(TRxxEDT) → row_max Check does not fire");
    }


    /** The BDS fixture the G6 probe expands against: one subject, one parameter, one baseline. */
    private static IDataTable ad0353Bds(String b1ind, String a1ind)
    {
        return MockTable.of().col("USUBJID", "S1").col("PARAMCD", "GLUC").col("BASETYPE", "LAST")
                .col("ABLFL", "Y").col("A1IND", a1ind).col("B1IND", b1ind).name("ADQS").build();
    }


    private static Rule expandedAd0353(IDataTable bds)
    {
        Rule template = findByCoreId("CDISC-AD0353");
        assertEquals(null, template.getLoadError(), "CDISC-AD0353 loads without error");
        assertEquals(net.cumba.cdisc.core.model.Executability.FULLY_EXECUTABLE,
                template.getExecutability(), "CDISC-AD0353 ships Fully Executable");
        List<Rule> expansions = net.cumba.cdisc.core.gen.WildcardExpander.expand(template,
                bds.getMetaData());
        assertEquals(1, expansions.size(), "one y binding (A1IND/B1IND) in the fixture metadata");
        return expansions.get(0);
    }


    /**
     * Fix #152 — the operation's {@code AyIND} must land on the same {@code y} as the Check's
     * {@code ByIND}. Before the fix this returned the literal template name, and the assertions
     * below could not be written at all.
     */
    @Test
    void cdiscAd0353_expansionBindsTheOperationName()
    {
        Rule expanded = expandedAd0353(ad0353Bds("NORMAL", "NORMAL"));
        List<net.cumba.cdisc.core.model.Operation> ops = expanded.getOperations();
        assertNotNull(ops);
        assertEquals(1, ops.size());
        assertEquals("A1IND", ops.get(0).getName(),
                "max(\"AyIND\", …) must expand alongside the Check's ByIND");
    }


    /**
     * The direction the defect broke: conformant data. {@code B1IND} equals the {@code ABLFL=Y}
     * baseline's {@code A1IND}, so nothing may be reported. Neuter the operation-name substitution
     * in {@code WildcardExpander.expandRule} and this goes RED with 1 violation — that is the live
     * over-report the five shipped rules were producing.
     */
    @Test
    void cdiscAd0353_baselineMatches_noFire()
    {
        IDataTable bds = ad0353Bds("NORMAL", "NORMAL");
        assertEquals(0, violationsOn(expandedAd0353(bds), bds, tablesOf(bds, bds, "ADQS")),
                "B1IND == A1IND on the baseline row → conformant → no finding");
    }


    /** The positive control on the same probe: genuinely non-conformant data still reports. */
    @Test
    void cdiscAd0353_baselineDiffers_fires()
    {
        IDataTable bds = ad0353Bds("HIGH", "LOW");
        assertEquals(1, violationsOn(expandedAd0353(bds), bds, tablesOf(bds, bds, "ADQS")),
                "B1IND != A1IND on the baseline row → fires");
    }

}
