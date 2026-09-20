package net.cumba.corej.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * Fix #19 — end-to-end integration tests for CDISC-AD0044 and CDISC-AD0045 in the ADaMIG v1.3 rule
 * package. ⚑ The rule bodies once used the {@code time_part_not_equal_to} /
 * {@code date_part_not_equal_to} operators; phase 3c's retyping and phase 3b's {@code time()}
 * conversion replaced that spelling with the two-sided expression form, and the {@code *DTM} side
 * sits on the LHS so the engine extracts the partial precision via the polymorphic dispatch.
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


    /**
     * ⚠⚠ <b>This asserted the legacy OPERATOR spelling until 2026-09-16, and that was the wrong
     * thing to pin.</b> Phase 3c's D71b retyping made the fixture two-sided
     * ({@code time_part(date(*DTM)) != time(*TM)}), and phase 3b's {@code time()} conversion has no
     * legacy operator surface at all — so the rule no longer lowers to
     * {@code time_part_not_equal_to} and correctly never will: {@code CheckOperator} has
     * <b>zero</b> authored call sites (D91c) and phase 7 retires the operator IR entirely.
     *
     * <p>
     * ⭐ So the sanity check now pins <b>what the rule compares</b> — the time part of {@code *DTM}
     * against {@code *TM} — which is the property the test was always for, and which survives the
     * representation change it was accidentally pinned to.
     * </p>
     */
    @Test
    void cdiscAd0044_ruleBodyComparesTheTimePartAgainstTM()
    {
        Rule rule = findByCoreId("CDISC-AD0044");
        String json = rule.toString(); // @Data toString — sufficient for a sanity check.
        assertEquals(true, json.contains("time_part"),
                "CDISC-AD0044 must compare the time part of *DTM: " + json);
        assertEquals(true, json.contains("*TM"), "CDISC-AD0044 must compare against *TM: " + json);
    }


    /** The {@code date_part} twin of {@link #cdiscAd0044_ruleBodyComparesTheTimePartAgainstTM}. */
    @Test
    void cdiscAd0045_ruleBodyComparesTheDatePartAgainstDT()
    {
        Rule rule = findByCoreId("CDISC-AD0045");
        String json = rule.toString();
        assertEquals(true, json.contains("date_part"),
                "CDISC-AD0045 must compare the date part of *DTM: " + json);
        assertEquals(true, json.contains("*DT"), "CDISC-AD0045 must compare against *DT: " + json);
    }
}
