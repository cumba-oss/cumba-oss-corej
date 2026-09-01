package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Fix #27 — end-to-end integration tests for CDISC-AD0897 after the rule body was re-routed onto
 * Fix #37's substitution syntax.
 *
 * <p>
 * The rule fires when a populated TRTA value matches none of the canonical actual-treatment columns
 * in ADSL: {@code ADSL.TRTA}, any {@code ADSL.TRT<digits>A}, or any {@code ADSL.TRTAG<digits>}.
 * Compared to the pre-Fix-#37 form (21 hardcoded leaves enumerating TRT01A..TRT10A and
 * TRTAG1..TRTAG9), the new form covers any number of digits.
 * </p>
 */
class CdiscAd0897IntegrationTest
{

    private static final Path RULES_FILE = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/fixtures/rules/packages/rules-adamig-1-3.json");

    private static RulePackage rulePackage;

    @BeforeAll
    static void loadPackage() throws Exception
    {
        rulePackage = RulePackageLoader.loadCombined(RULES_FILE);
    }


    private static Rule rule()
    {
        return rulePackage.getRules().values().stream()
                .filter(r -> r.getCore() != null && "CDISC-AD0897".equals(r.getCore().getId()))
                .findFirst().orElseThrow(() -> new AssertionError("CDISC-AD0897 not in package"));
    }


    private static DatasetResolver resolverOf(Map<String, IDataTable> tables)
    {
        return tables::get;
    }


    private static int violationsOn(IDataTable table, DatasetResolver resolver)
    {
        RuleExecutionResult result = RuleRunner.execute(rule(), table, resolver);
        return result.getViolationCount();
    }

    // -----------------------------------------------------------------------
    // Match against the canonical TRTA column → no fire
    // -----------------------------------------------------------------------


    @Test
    void trtaEqualsAdslTrta_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("TRTA", "Drug A").name("ADSL")
                .build();
        IDataTable adlb = MockTable.of().col("USUBJID", "S1").col("TRTA", "Drug A")
                .col("PARAMCD", "P1").name("ADLB").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADLB", adlb);

        assertEquals(0, violationsOn(adlb, resolverOf(tables)), "TRTA equals ADSL.TRTA → no fire");
    }

    // -----------------------------------------------------------------------
    // Match against TRT##A — exercises the ${*} wildcard family
    // -----------------------------------------------------------------------


    @Test
    void trtaEqualsAdslTrt02A_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("TRT01A", "Placebo")
                .col("TRT02A", "Drug A").name("ADSL").build();
        IDataTable adlb = MockTable.of().col("USUBJID", "S1").col("TRTA", "Drug A")
                .col("PARAMCD", "P1").name("ADLB").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADLB", adlb);

        assertEquals(0, violationsOn(adlb, resolverOf(tables)),
                "TRTA matches ADSL.TRT02A via ${*} → no fire");
    }


    @Test
    void trtaEqualsAdslTrt12A_noFire()
    {
        // Pre-Fix-#37, TRT12A wasn't covered by the hardcoded leaves (which only went to TRT10A).
        // With ${*}, any number of digits matches.
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("TRT12A", "Drug B").name("ADSL")
                .build();
        IDataTable adlb = MockTable.of().col("USUBJID", "S1").col("TRTA", "Drug B")
                .col("PARAMCD", "P1").name("ADLB").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADLB", adlb);

        assertEquals(0, violationsOn(adlb, resolverOf(tables)),
                "TRTA matches ADSL.TRT12A → no fire (Fix #37 covers any digit count)");
    }

    // -----------------------------------------------------------------------
    // Match against TRTAG# — exercises the second ${*} wildcard family
    // -----------------------------------------------------------------------


    @Test
    void trtaEqualsAdslTrtag3_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("TRTAG1", "Group A")
                .col("TRTAG2", "Group B").col("TRTAG3", "Drug A").name("ADSL").build();
        IDataTable adlb = MockTable.of().col("USUBJID", "S1").col("TRTA", "Drug A")
                .col("PARAMCD", "P1").name("ADLB").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADLB", adlb);

        assertEquals(0, violationsOn(adlb, resolverOf(tables)),
                "TRTA matches ADSL.TRTAG3 via ${*} → no fire");
    }

    // -----------------------------------------------------------------------
    // No match in any column → fire
    // -----------------------------------------------------------------------


    @Test
    void trtaMatchesNoColumn_fires()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("TRTA", "Placebo")
                .col("TRT01A", "Drug A").col("TRTAG1", "Group X").name("ADSL").build();
        IDataTable adlb = MockTable.of().col("USUBJID", "S1").col("TRTA", "Mystery Treatment")
                .col("PARAMCD", "P1").name("ADLB").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADLB", adlb);

        assertEquals(1, violationsOn(adlb, resolverOf(tables)),
                "TRTA matches no canonical ADSL column → fire");
    }

    // -----------------------------------------------------------------------
    // TRTA empty → no fire (non_empty guard)
    // -----------------------------------------------------------------------


    @Test
    void trtaEmpty_noFire()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("TRTA", "Drug A").name("ADSL")
                .build();
        IDataTable adlb = MockTable.of().col("USUBJID", "S1").col("TRTA", "").col("PARAMCD", "P1")
                .name("ADLB").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADLB", adlb);

        assertEquals(0, violationsOn(adlb, resolverOf(tables)),
                "Empty TRTA filtered by non_empty guard → no fire");
    }

    // -----------------------------------------------------------------------
    // Out-of-scope confirmation: ADSL itself isn't in scope (BDS/OCCDS only)
    // -----------------------------------------------------------------------


    @Test
    void scopeExcludesAdsl()
    {
        // Even with mismatched TRTA, ADSL itself is out-of-scope.
        // ScopeMatcher will reject the rule for ADSL.
        // M2-D23: the scope declaration moved from the inert Scope.Classes channel to the live
        // Scope.Data_Structures one — same token list, and it is now the channel RuleRunner gates
        // on.
        Rule r = rule();
        assertNotNull(r);
        assertNotNull(r.getScope());
        assertNotNull(r.getScope().getDataStructures());
        assertEquals(java.util.List.of("BASIC DATA STRUCTURE", "OCCURRENCE DATA STRUCTURE"),
                r.getScope().getDataStructures().getInclude(),
                "Scope narrowed to BDS+OCCDS — ADSL is excluded");
    }

}
