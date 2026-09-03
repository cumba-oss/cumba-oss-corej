package net.cumba.corej.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.nio.file.Path;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end integration tests for CDISC-AD0734 after the rule body was rewritten from a
 * {@code check_rule} placeholder to use two {@code record_count} Operations and a cross-variable
 * comparison Check ({@code $bt_pop > 0 AND $bt_pop < $bt_total}). The rule fires once at dataset
 * level when BASETYPE is populated for some rows and missing for others.
 *
 * <p>
 * Existing only in ADaMIG 1-1; tests load from there.
 * </p>
 */
class CdiscAd0734IntegrationTest
{

    private static final Path RULES_FILE = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/fixtures/rules/packages/rules-adamig-1-1.json");

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


    @Test
    void mixed_populationAndUnpopulationCoexist_oneViolation()
    {
        Rule rule = findByCoreId("CDISC-AD0734");

        IDataTable table = MockTable.of().col("USUBJID", "S01", "S01", "S02")
                .col("PARAMCD", "PARAM1", "PARAM2", "PARAM1").col("BASETYPE", "BASE1", "", "BASE1")
                .name("ADBDS").build();

        RuleExecutionResult result = RuleRunner.execute(rule, table, self(table));

        assertEquals(1, result.getViolationCount(),
                "BASETYPE populated on rows 1+3 but empty on row 2 → mixed → fires once");
    }


    @Test
    void allPopulated_noViolation()
    {
        Rule rule = findByCoreId("CDISC-AD0734");

        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02")
                .col("BASETYPE", "BASE1", "BASE2").name("ADBDS").build();

        RuleExecutionResult result = RuleRunner.execute(rule, table, self(table));

        assertEquals(0, result.getViolationCount(),
                "All rows have BASETYPE → not mixed → no violation");
    }


    @Test
    void allEmpty_noViolation()
    {
        Rule rule = findByCoreId("CDISC-AD0734");

        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02").col("BASETYPE", "", "")
                .name("ADBDS").build();

        RuleExecutionResult result = RuleRunner.execute(rule, table, self(table));

        assertEquals(0, result.getViolationCount(),
                "All rows have BASETYPE empty → $bt_pop=0 → first leaf false → no violation");
    }


    @Test
    void emptyStringTreatedAsMissing()
    {
        // Verifies the engine empty-string-as-missing fix is in effect.
        // Without it, an empty-string BASETYPE would count as populated and
        // this test would erroneously become a "mixed" scenario despite
        // every row being empty.
        Rule rule = findByCoreId("CDISC-AD0734");

        IDataTable table = MockTable.of().col("USUBJID", "S01", "S02", "S03")
                .col("BASETYPE", "", "", "").name("ADBDS").build();

        RuleExecutionResult result = RuleRunner.execute(rule, table, self(table));

        assertEquals(0, result.getViolationCount(),
                "Empty strings are missing → no populated rows → no violation");
    }


    @Test
    void allRowsBasetypeMissing_noViolation()
    {
        Rule rule = findByCoreId("CDISC-AD0734");

        // BASETYPE column doesn't carry values at all (treated as missing).
        // record_count(filter={BASETYPE: "&"}) returns 0; record_count() (total)
        // returns 1; the first leaf $bt_pop > 0 is false → no violation.
        IDataTable table = MockTable.of().col("USUBJID", "S01").col("BASETYPE", "").name("ADBDS")
                .build();

        RuleExecutionResult result = RuleRunner.execute(rule, table, self(table));

        assertEquals(0, result.getViolationCount());
    }


    @Test
    void singleRowMixed_impossibleByDefinition()
    {
        // Single-row dataset: either populated (no mixed) or empty (no mixed).
        // Belt-and-braces — confirms the rule does not fire for the trivial case.
        Rule rule = findByCoreId("CDISC-AD0734");

        IDataTable table = MockTable.of().col("USUBJID", "S01").col("BASETYPE", "BASE1")
                .name("ADBDS").build();

        RuleExecutionResult result = RuleRunner.execute(rule, table, self(table));

        assertEquals(0, result.getViolationCount());
    }
}
