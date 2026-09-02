package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.List;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.gen.WildcardExpander;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Fix #19 — end-to-end integration tests for CDISC-AD0044 and CDISC-AD0045 in the ADaMIG v1.3 rule
 * package. The rule bodies were rewritten to use the new {@code time_part_not_equal_to} /
 * {@code date_part_not_equal_to} operators and the {@code *DTM} side moved onto the LHS so the
 * engine extracts the partial precision via the polymorphic dispatch.
 * <p>
 * Numeric *DT/*DTM/*TM columns are fabricated via the typed {@link MockTable} factories
 * ({@code colLong} / {@code colDouble}). The rule's wildcard (e.g., {@code *DTM}) is expanded by
 * {@link WildcardExpander} against the fabricated table's column metadata to a concrete pair (ASTDT
 * / ASTDTM / ASTTM in this fixture); the integration test asserts the violation count for
 * matched-parts (valid) and mismatched-parts (invalid) data.
 * </p>
 */
class CdiscAd0044And0045IntegrationTest
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


    private static int totalViolations(Rule template, IDataTable table)
    {
        List<Rule> expanded = WildcardExpander.expand(template, table.getMetaData());
        int sum = 0;
        for (Rule r : expanded)
        {
            RuleExecutionResult result = RuleRunner.execute(r, table);
            sum += result.getViolationCount();
        }
        return sum;
    }

    // ---- CDISC-AD0044 — *TM must match the time-part of *DTM ----------------


    @Test
    void cdiscAd0044_matchingTimePart_noViolation()
    {
        // ASTDTM = 86400.5 → day 1, 00:00:00.5; ASTTM = 0.5 → matches.
        IDataTable t = MockTable.of().colDouble("ASTDTM", 86_400.5).colDouble("ASTTM", 0.5).build();

        Rule rule = findByCoreId("CDISC-AD0044");
        assertNotNull(rule);

        assertEquals(0, totalViolations(rule, t), "time-part of *DTM matches *TM → no violation");
    }


    @Test
    void cdiscAd0044_mismatchedTimePart_oneViolation()
    {
        // ASTDTM = 86400.5 (00:00:00.5), ASTTM = 0.6 → time-part mismatch.
        IDataTable t = MockTable.of().colDouble("ASTDTM", 86_400.5).colDouble("ASTTM", 0.6).build();

        Rule rule = findByCoreId("CDISC-AD0044");
        assertEquals(1, totalViolations(rule, t),
                "time-part of *DTM differs from *TM → one violation");
    }


    @Test
    void cdiscAd0044_missingValuesGuardedByNonEmpty_noViolation()
    {
        // non_empty guard on *TM short-circuits when ASTTM is missing — no fire.
        IDataTable t = MockTable.of().colDouble("ASTDTM", 86_400.5)
                .colDouble("ASTTM", (Double) null).build();

        Rule rule = findByCoreId("CDISC-AD0044");
        assertFalse(totalViolations(rule, t) > 0,
                "non_empty guard on *TM blocks the time_part check when *TM is missing");
    }

    // ---- CDISC-AD0045 — *DT must match the date-part of *DTM ----------------


    @Test
    void cdiscAd0045_matchingDatePart_noViolation()
    {
        // ASTDTM = 86400 → day 1; ASTDT = 1 → matches.
        IDataTable t = MockTable.of().colDouble("ASTDTM", 86_400.0).colLong("ASTDT", 1L).build();

        Rule rule = findByCoreId("CDISC-AD0045");
        assertNotNull(rule);

        assertEquals(0, totalViolations(rule, t), "date-part of *DTM matches *DT → no violation");
    }


    @Test
    void cdiscAd0045_mismatchedDatePart_oneViolation()
    {
        // ASTDTM = 86400 (day 1), ASTDT = 2 (day 2) → date-part mismatch.
        IDataTable t = MockTable.of().colDouble("ASTDTM", 86_400.0).colLong("ASTDT", 2L).build();

        Rule rule = findByCoreId("CDISC-AD0045");
        assertEquals(1, totalViolations(rule, t),
                "date-part of *DTM differs from *DT → one violation");
    }


    @Test
    void cdiscAd0045_missingValuesGuardedByNonEmpty_noViolation()
    {
        IDataTable t = MockTable.of().colDouble("ASTDTM", (Double) null).colLong("ASTDT", 1L)
                .build();

        Rule rule = findByCoreId("CDISC-AD0045");
        assertFalse(totalViolations(rule, t) > 0,
                "non_empty guard on *DTM blocks the date_part check when *DTM is missing");
    }

    // ---- Sanity: rule-body shape ------------------------------------------


    @Test
    void cdiscAd0044_ruleBodyUsesTimePartNotEqualTo()
    {
        Rule rule = findByCoreId("CDISC-AD0044");
        String json = rule.toString(); // @Data toString — sufficient for a sanity check.
        assertEquals(true, json.contains("time_part_not_equal_to"),
                "CDISC-AD0044 must use the new time_part_not_equal_to operator");
    }


    @Test
    void cdiscAd0045_ruleBodyUsesDatePartNotEqualTo()
    {
        Rule rule = findByCoreId("CDISC-AD0045");
        String json = rule.toString();
        assertEquals(true, json.contains("date_part_not_equal_to"),
                "CDISC-AD0045 must use the new date_part_not_equal_to operator");
    }
}
