package net.cumba.corej.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import net.cumba.corej.core.metadata.AdamSubclassDetector;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * End-to-end integration tests for Fix #18 — CDISC-AD0640 through CDISC-AD0646 (Variable Metadata
 * Check / Dataset sensitivity, "AE has variable X but ADAE does not"). The rules are loaded from
 * the actual ADaMIG 1-3 rule package so the test exercises the rule body wired in by Fix #18, not a
 * synthetic copy.
 * <p>
 * Each rule has the same shape:
 * </p>
 *
 * <pre>
 *   all:
 *     - name: AE.&lt;col&gt;,           operator: exists      (or SUPPAE.QNAM=AETRTEM for 0640)
 *     - name: &lt;col&gt;,              operator: not_exists
 * </pre>
 *
 * <p>
 * Sensitivity = Dataset → at most one violation per dataset.
 * </p>
 */
class CdiscAd0640To0646IntegrationTest
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


    /**
     * The ADAE fixture. {@code AETERM} is a scope fixture, not a check input: the seven rules are
     * gated by {@code Scope.Data_Structures} / {@code Scope.Subclasses}, both derived from the
     * dataset's columns, so the table has to carry the detector's signal column or every rule is
     * SKIPPED. {@code AETERM} ends with an OCCDS suffix (→ {@code OCCURRENCE DATA STRUCTURE}) and
     * is one of the two {@code ADVERSE EVENT} subclass markers. No rule in this family references
     * it, so it is inert for the Checks themselves.
     */
    private static IDataTable adae(String... extraCols)
    {
        MockTable mt = MockTable.of().col("USUBJID", "S01").col("AESEQ", "1").col("AETERM",
                "HEADACHE");
        mt.name("ADAE");
        for (String c : extraCols)
        {
            mt.col(c, "1");
        }
        return mt.build();
    }


    private static IDataTable ae(String... extraCols)
    {
        MockTable mt = MockTable.of().col("USUBJID", "S01").col("AESEQ", "1");
        mt.name("AE");
        for (String c : extraCols)
        {
            mt.col(c, "1");
        }
        return mt.build();
    }


    private static DatasetResolver resolverOf(IDataTable ae, IDataTable suppae)
    {
        return name ->
        {
            if ("AE".equals(name))
            {
                return ae;
            }
            if ("SUPPAE".equals(name))
            {
                return suppae;
            }
            return null;
        };
    }

    // ---- CDISC-AD0640: AE.AETRTEM exists ∧ AETRTEM not_exists ----
    // Fix #39 rewrite (2026-04-28): Check now uses the cleaner AE.AETRTEM
    // dotted form. The lazy SUPP-pivot lookup in
    // OperatorRegistry.existsAsDottedDatasetColumn handles the SUPPAE.QNAM
    // path automatically, so the rule still fires when AETRTEM is delivered
    // via the supplemental qualifier mechanism — and now also fires when AE
    // itself carries AETRTEM as a literal column (more accurate per the
    // cited guidance).


    @Test
    void cdiscAd0640_suppaeHasAetrtem_adaeMissingAetrtem_oneViolation()
    {
        Rule rule = findByCoreId("CDISC-AD0640");

        IDataTable adaeTable = adae(); // no AETRTEM column
        IDataTable aeTable = ae(); // AE carries no AETRTEM either
        IDataTable suppae = MockTable.of().col("USUBJID", "S01").col("QNAM", "AETRTEM")
                .col("QVAL", "Y").name("SUPPAE").build();

        RuleExecutionResult result = RuleRunner.execute(rule, adaeTable,
                resolverOf(aeTable, suppae));

        assertEquals(1, result.getViolationCount(),
                "AETRTEM delivered via SUPPAE.QNAM → AE.AETRTEM exists is true → ADAE missing AETRTEM fires");
    }


    @Test
    void cdiscAd0640_aeHasAetrtemDirectly_adaeMissingAetrtem_oneViolation()
    {
        // New scenario unlocked by Fix #39: AE carries AETRTEM literally (no
        // SUPPAE involvement). The rule still fires because AE.AETRTEM exists.
        Rule rule = findByCoreId("CDISC-AD0640");

        IDataTable adaeTable = adae(); // no AETRTEM column
        IDataTable aeTable = ae("AETRTEM");

        RuleExecutionResult result = RuleRunner.execute(rule, adaeTable, resolverOf(aeTable, null));

        assertEquals(1, result.getViolationCount(),
                "AE has AETRTEM as a literal column → AE.AETRTEM exists → ADAE missing AETRTEM fires");
    }


    @Test
    void cdiscAd0640_suppaeHasAetrtem_adaeAlsoHasAetrtem_noViolation()
    {
        Rule rule = findByCoreId("CDISC-AD0640");

        IDataTable adaeTable = adae("AETRTEM");
        IDataTable aeTable = ae();
        IDataTable suppae = MockTable.of().col("USUBJID", "S01").col("QNAM", "AETRTEM")
                .col("QVAL", "Y").name("SUPPAE").build();

        RuleExecutionResult result = RuleRunner.execute(rule, adaeTable,
                resolverOf(aeTable, suppae));

        assertFalse(result.hasViolations(),
                "ADAE already has AETRTEM column → not_exists branch is false → no violation");
    }


    @Test
    void cdiscAd0640_suppaeWithoutAetrtemRow_noViolation()
    {
        Rule rule = findByCoreId("CDISC-AD0640");

        IDataTable adaeTable = adae();
        IDataTable aeTable = ae();
        IDataTable suppae = MockTable.of().col("USUBJID", "S01").col("QNAM", "OTHER")
                .col("QVAL", "X").name("SUPPAE").build();

        RuleExecutionResult result = RuleRunner.execute(rule, adaeTable,
                resolverOf(aeTable, suppae));

        assertFalse(result.hasViolations(),
                "Neither AE nor SUPPAE has AETRTEM → AE.AETRTEM exists is false → no violation");
    }


    @Test
    void cdiscAd0640_neitherAeNorSuppaeAvailable_noViolation()
    {
        // Defence in depth: when neither AE nor SUPPAE is loaded, the dotted
        // lookup must short-circuit to false (no source for AETRTEM) and the
        // rule must not fire spuriously.
        Rule rule = findByCoreId("CDISC-AD0640");

        IDataTable adaeTable = adae();

        RuleExecutionResult result = RuleRunner.execute(rule, adaeTable, resolverOf(null, null));

        assertFalse(result.hasViolations(), "Neither AE nor SUPPAE in the resolver → no fire");
    }

    // ---- CDISC-AD0641 … CDISC-AD0646 ----
    // Each rule has the shape `AE.<col> exists ∧ <col> not_exists` — six near-identical
    // (rule id, column name) pairs. Parameterised over the (rule, column) tuple to keep the
    // six "AE has X, ADAE missing X → one violation" / "both have X → no violation" pairs
    // in a single source of truth.


    @ParameterizedTest(name = "{0}: AE has {1}, ADAE missing {1} → 1 violation")
    @CsvSource(
    {
            "CDISC-AD0641, AESTDY", "CDISC-AD0642, AEENDY", "CDISC-AD0643, AEDUR",
            "CDISC-AD0644, AESEV", "CDISC-AD0645, AETOXGR", "CDISC-AD0646, AEACN"
    })
    void cdiscAd_0641To0646_aeHasCol_adaeMissingCol_oneViolation(String coreId, String column)
    {
        Rule rule = findByCoreId(coreId);

        IDataTable adaeTable = adae(); // no <column>
        IDataTable aeTable = ae(column);

        RuleExecutionResult result = RuleRunner.execute(rule, adaeTable, resolverOf(aeTable, null));

        assertEquals(1, result.getViolationCount());
    }


    @ParameterizedTest(name = "{0}: both AE and ADAE have {1} → no violation")
    @CsvSource(
    {
            "CDISC-AD0641, AESTDY", "CDISC-AD0642, AEENDY", "CDISC-AD0643, AEDUR",
            "CDISC-AD0644, AESEV", "CDISC-AD0645, AETOXGR", "CDISC-AD0646, AEACN"
    })
    void cdiscAd_0641To0646_aeAndAdaeBothHaveCol_noViolation(String coreId, String column)
    {
        Rule rule = findByCoreId(coreId);

        IDataTable adaeTable = adae(column);
        IDataTable aeTable = ae(column);

        RuleExecutionResult result = RuleRunner.execute(rule, adaeTable, resolverOf(aeTable, null));

        assertFalse(result.hasViolations());
    }

    // ---- CDISC-AD0641 — additional standalone scenario ----


    @Test
    void cdiscAd0641_aeMissingAestdy_noViolation()
    {
        Rule rule = findByCoreId("CDISC-AD0641");

        IDataTable adaeTable = adae();
        IDataTable aeTable = ae(); // no AESTDY

        RuleExecutionResult result = RuleRunner.execute(rule, adaeTable, resolverOf(aeTable, null));

        assertFalse(result.hasViolations(),
                "AE missing AESTDY → exists branch false → no violation regardless of ADAE");
    }

    // ---- CDISC-AD0646 — additional standalone scenarios (AEACN populated-row edge cases) ----


    @Test
    void cdiscAd0646_aeAeacnAllEmpty_noViolation()
    {
        // AE has the AEACN column but no row is populated (every row stores "").
        // Per CDISC convention an empty string is missing, so the rule's
        // record_count(filter={AEACN: "&"}) must yield 0 → not_exists branch
        // alone cannot fire the rule. Verifies OperationExecutor.rowMatchesFilter
        // treats "" as missing (alignment with evalNonEmpty).
        Rule rule = findByCoreId("CDISC-AD0646");

        IDataTable adaeTable = adae();
        IDataTable aeTable = MockTable.of().col("USUBJID", "S01").col("AESEQ", "1").col("AEACN", "")
                .name("AE").build();

        RuleExecutionResult result = RuleRunner.execute(rule, adaeTable, resolverOf(aeTable, null));

        assertFalse(result.hasViolations(),
                "AE.AEACN column exists but all rows empty → not populated → no violation");
    }


    @Test
    void cdiscAd0646_aeAeacnMixedPopulation_oneViolation()
    {
        // At least one AE row has AEACN populated → rule fires once at dataset
        // level. Verifies that the populated-row count does the right thing
        // when populated and empty rows coexist.
        Rule rule = findByCoreId("CDISC-AD0646");

        IDataTable adaeTable = adae();
        IDataTable aeTable = MockTable.of().col("USUBJID", "S01", "S02").col("AESEQ", "1", "2")
                .col("AEACN", "", "DOSE NOT CHANGED").name("AE").build();

        RuleExecutionResult result = RuleRunner.execute(rule, adaeTable, resolverOf(aeTable, null));

        assertEquals(1, result.getViolationCount(),
                "AE has one populated AEACN row → rule fires once on the ADAE dataset");
    }

    // ---- Out-of-scope confirmation ----
    // The gate moved from the dataset NAME to the dataset's detected structure and
    // subclass: the seven rules no longer carry Scope.Domains at all, they carry
    // Scope.Data_Structures = [OCCURRENCE DATA STRUCTURE] + Scope.Subclasses =
    // [ADVERSE EVENT], and AdamDataStructureDetector / AdamSubclassDetector derive
    // both from the dataset's columns. The intent is unchanged — these rules apply
    // to AE analysis datasets and nothing else — so the equivalent of the old
    // "ADAE yes, ADCM/ADMH no" assertion is: in scope for an OCCDS dataset carrying
    // the ADVERSE EVENT subclass, out of scope for a plain BDS dataset and for an
    // OCCDS dataset without that subclass (ADCM/ADMH are OCCDS via CMTRT / MHTERM,
    // but neither carries AETERM or AEDECOD, so neither detects ADVERSE EVENT).


    @Test
    void allSevenRules_areScopedToAdverseEventOccdsOnly()
    {
        String[] coreIds =
        {
                "CDISC-AD0640", "CDISC-AD0641", "CDISC-AD0642", "CDISC-AD0643", "CDISC-AD0644",
                "CDISC-AD0645", "CDISC-AD0646"
        };
        for (String id : coreIds)
        {
            Rule rule = findByCoreId(id);
            assertNotNull(rule.getScope(), id + ": expected Scope present");
            assertNotNull(rule.getScope().getDataStructures(),
                    id + ": expected Scope.Data_Structures present");
            assertNotNull(rule.getScope().getSubclasses(),
                    id + ": expected Scope.Subclasses present");
            assertNull(
                    ScopeMatcher.describeDataStructureMismatch(rule,
                            AdamDataStructureDetector.OCCDS),
                    id + " must apply to an OCCURRENCE DATA STRUCTURE dataset");
            assertNull(
                    ScopeMatcher.describeSubclassMismatch(rule, AdamSubclassDetector.ADVERSE_EVENT),
                    id + " must apply to the ADVERSE EVENT subclass");
            assertNotNull(
                    ScopeMatcher.describeDataStructureMismatch(rule, AdamDataStructureDetector.BDS),
                    id + " must NOT apply to a BASIC DATA STRUCTURE dataset");
            assertNotNull(ScopeMatcher.describeSubclassMismatch(rule, (String) null),
                    id + " must NOT apply to an OCCDS dataset without the ADVERSE EVENT subclass"
                            + " (the ADCM / ADMH case)");
        }
    }

}
