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
 * Fix #29 — end-to-end integration tests for CDISC-AD0059 after the rule body was rewritten from a
 * wildcard-suffix template ({@code *TM exists} + {@code variable_data_type != Num}) to a
 * regex-based variable-metadata check ({@code variable_name matches_regex "TM$"} +
 * {@code variable_data_type !=
 * Num}). Both leaves are now variable-level metadata; the engine's per-column iteration evaluates
 * them naturally without losing the wildcard binding.
 *
 * <p>
 * The rule exists only in ADaMIG 1-0; this test loads from there.
 * </p>
 */
class CdiscAd0059IntegrationTest
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
                .filter(r -> r.getCore() != null && "CDISC-AD0059".equals(r.getCore().getId()))
                .findFirst().orElseThrow(() -> new AssertionError("CDISC-AD0059 not in package"));
    }


    private static int violationsOn(IDataTable table)
    {
        DatasetResolver resolver = name -> name.equals(table.getMetaData().getName()) ? table
                : null;
        RuleExecutionResult result = RuleRunner.execute(rule(), table, resolver);
        return result.getViolationCount();
    }

    // -----------------------------------------------------------------------
    // *TM column with Num data type → no fire (rule's required shape)
    // -----------------------------------------------------------------------


    @Test
    void asttmIsNumeric_noFire()
    {
        IDataTable adlb = MockTable.of().col("USUBJID", "S1").colLong("ASTTM", 12345L) // numeric →
                                                                                       // "Num"
                .name("ADLB").build();

        assertEquals(0, violationsOn(adlb), "ASTTM is numeric (Num) → no fire");
    }

    // -----------------------------------------------------------------------
    // *TM column with Char data type → fire (the bug case from Fix #29)
    // -----------------------------------------------------------------------


    @Test
    void asttmIsCharacter_fires()
    {
        IDataTable adlb = MockTable.of().col("USUBJID", "S1").col("ASTTM", "12:34:56") // STRING →
                                                                                       // "Char" →
                                                                                       // rule fires
                .name("ADLB").build();

        assertEquals(1, violationsOn(adlb), "ASTTM is Char → one violation");
    }

    // -----------------------------------------------------------------------
    // Multiple *TM columns mixed types → fires only for the Char ones
    // -----------------------------------------------------------------------


    @Test
    void mixedTmColumns_firesOnlyOnCharacter()
    {
        IDataTable adlb = MockTable.of().col("USUBJID", "S1").colLong("ASTTM", 100L) // Num — no
                                                                                     // fire
                .col("AENDTM", "23:45:00") // Char — fires
                .colLong("CRTM", 200L) // Num — no fire
                .col("STARTM", "08:00:00") // Char — fires
                .name("ADLB").build();

        assertEquals(2, violationsOn(adlb), "AENDTM and STARTM are Char → two violations");
    }

    // -----------------------------------------------------------------------
    // Non-*TM Char column → no fire (regex scopes to TM-suffixed columns)
    // -----------------------------------------------------------------------


    @Test
    void nonTmCharColumn_noFire()
    {
        // USUBJID and ADTC are Char columns but don't match TM$ regex.
        // Pre-Fix-#29, the engine bug would have fired on every Char column;
        // the regex rewrite scopes the check correctly to *TM columns.
        IDataTable adlb = MockTable.of().col("USUBJID", "S1").col("ADTC", "2024-01-15").name("ADLB")
                .build();

        assertEquals(0, violationsOn(adlb),
                "ADTC and USUBJID don't match TM$ regex → no fire (Fix #29 bug case)");
    }

    // -----------------------------------------------------------------------
    // Mix of *TM Char + non-*TM Char in same dataset
    // -----------------------------------------------------------------------


    @Test
    void mixOfTmAndNonTmChar_firesOnlyOnTm()
    {
        IDataTable adlb = MockTable.of().col("USUBJID", "S1").col("ASTTM", "08:00:00") // *TM Char —
                                                                                       // fires
                .col("ADTC", "2024-01-15") // non-*TM Char — no fire
                .col("AVISITN", "1") // non-*TM Char — no fire
                .name("ADLB").build();

        assertEquals(1, violationsOn(adlb),
                "Only ASTTM (TM-suffix Char) fires; ADTC/AVISITN don't match regex");
    }

    // -----------------------------------------------------------------------
    // Sanity: rule body has the expected shape (post-rewrite)
    // -----------------------------------------------------------------------


    @Test
    void ruleBodyIsRegexBased()
    {
        Rule r = rule();
        assertNotNull(r);
        // Sanity: rule shouldn't have a loadError after Fix #29 rewrite.
        assertEquals(null, r.getLoadError(),
                "CDISC-AD0059 should load cleanly after the regex rewrite");
    }

}
