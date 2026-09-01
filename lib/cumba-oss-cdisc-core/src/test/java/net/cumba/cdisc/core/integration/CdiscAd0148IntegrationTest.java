package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
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
 * Fix #30 — end-to-end integration tests for CDISC-AD0148 after the rule body was rewritten from a
 * {@code check_rule} placeholder to use the {@code is_not_integer} operator.
 *
 * <p>
 * The rule fires when PARAMN is populated but does not parse as an integer. Existing only in ADaMIG
 * 1-0; tests load from there.
 * </p>
 */
class CdiscAd0148IntegrationTest
{

    private static final Path RULES_FILE = Path.of(System.getProperty("projectBasedir"),
            "src/test/resources/fixtures/rules/packages/rules-adamig-1-0.json");

    private static RulePackage rulePackage;

    @BeforeAll
    static void loadPackage() throws Exception
    {
        rulePackage = RulePackageLoader.loadCombined(RULES_FILE);
    }


    private static Rule rule()
    {
        return rulePackage.getRules().values().stream()
                .filter(r -> r.getCore() != null && "CDISC-AD0148".equals(r.getCore().getId()))
                .findFirst().orElseThrow(() -> new AssertionError("CDISC-AD0148 not in package"));
    }


    private static int violationsOn(IDataTable table)
    {
        DatasetResolver resolver = name -> name.equals(table.getMetaData().getName()) ? table
                : null;
        RuleExecutionResult result = RuleRunner.execute(rule(), table, resolver);
        return result.getViolationCount();
    }

    // -----------------------------------------------------------------------
    // PARAMN integer values → no fire
    // -----------------------------------------------------------------------


    @Test
    void integerLong_noFire()
    {
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S1", "S1")
                .colLong("PARAMN", 1L, 2L, 3L).col("PARAMCD", "P1", "P1", "P1").name("ADLB")
                .build();

        assertEquals(0, violationsOn(adlb), "PARAMN values 1, 2, 3 are integers → no violations");
    }


    @Test
    void integerValuedDouble_noFire()
    {
        // 1.0 / 2.0 are integer-valued doubles per `d == Math.floor(d)`.
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S1").colDouble("PARAMN", 1.0, 2.0)
                .col("PARAMCD", "P1", "P1").name("ADLB").build();

        assertEquals(0, violationsOn(adlb), "Integer-valued doubles (1.0, 2.0) → no violations");
    }

    // -----------------------------------------------------------------------
    // PARAMN fractional → fire
    // -----------------------------------------------------------------------


    @Test
    void fractionalDouble_fires()
    {
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S1").colDouble("PARAMN", 1.5, 2.7)
                .col("PARAMCD", "P1", "P1").name("ADLB").build();

        assertEquals(2, violationsOn(adlb),
                "PARAMN values 1.5 and 2.7 are not integers → 2 violations");
    }

    // -----------------------------------------------------------------------
    // PARAMN non-numeric string → fire
    // -----------------------------------------------------------------------


    @Test
    void nonNumericString_fires()
    {
        IDataTable adlb = MockTable.of().col("USUBJID", "S1").col("PARAMN", "abc")
                .col("PARAMCD", "P1").name("ADLB").build();

        assertEquals(1, violationsOn(adlb), "Non-numeric PARAMN string fires");
    }

    // -----------------------------------------------------------------------
    // Mixed: some integer, some fractional, some non-numeric → fires only on bad rows
    // -----------------------------------------------------------------------


    @Test
    void mixedValues_firesOnlyOnNonInteger()
    {
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S2", "S3", "S4")
                .col("PARAMN", "1", "2.5", "abc", "42").col("PARAMCD", "P1", "P1", "P1", "P1")
                .name("ADLB").build();

        assertEquals(2, violationsOn(adlb),
                "Rows 2 (2.5) and 3 (abc) are not integers; rows 1 (1) and 4 (42) are");
    }

    // -----------------------------------------------------------------------
    // PARAMN empty → no fire (non_empty guard catches)
    // -----------------------------------------------------------------------


    @Test
    void emptyString_noFire()
    {
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S1").col("PARAMN", "", "  ")
                .col("PARAMCD", "P1", "P1").name("ADLB").build();

        // Note: " " is not strictly empty (contains whitespace) but also won't
        // parse as numeric. Engine's non_empty checks for null/empty-string only,
        // so the whitespace row reaches is_not_integer and fires.
        // The strict empty-string row is filtered.
        assertEquals(1, violationsOn(adlb),
                "Empty string filtered by non_empty; whitespace string not numeric → fires");
    }

    // -----------------------------------------------------------------------
    // Negative integers → no fire
    // -----------------------------------------------------------------------


    @Test
    void negativeIntegers_noFire()
    {
        IDataTable adlb = MockTable.of().col("USUBJID", "S1", "S1").colLong("PARAMN", -5L, -100L)
                .col("PARAMCD", "P1", "P1").name("ADLB").build();

        assertEquals(0, violationsOn(adlb), "Negative integers are still integers → no fire");
    }

    // -----------------------------------------------------------------------
    // Sanity: rule body has the expected shape (no loadError after rewrite)
    // -----------------------------------------------------------------------


    @Test
    void ruleLoadsCleanly()
    {
        Rule r = rule();
        assertNotNull(r);
        assertEquals(null, r.getLoadError(),
                "CDISC-AD0148 should load cleanly after the is_not_integer rewrite");
    }

}
