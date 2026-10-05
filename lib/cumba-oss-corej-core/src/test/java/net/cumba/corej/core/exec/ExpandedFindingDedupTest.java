package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.exactInventory;
import static net.cumba.corej.core.exec.QualifiedNameFixture.loadClean;
import static net.cumba.corej.core.exec.QualifiedNameFixture.matchJ;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-merged-evaluation-table} §3.8 (MET #22, P-4, P-6, P-18), W3e: within one (rule,
 * dataset) execution two findings from expanded rows are <b>one</b> when they report the same thing
 * — the same real row and the same values, every merged value compared by identity — and stay
 * <b>N</b> findings when a merged value they print differs. A table whose rows are distinct records
 * is never touched.
 */
class ExpandedFindingDedupTest
{

    /** P: k1, k2, k3 (k3 has no partner). */
    private static IDataTable primary()
    {
        return RealTables.of("P").str("K", "k1", "k2", "k3").str("A", "a1", "a2", "a3").build();
    }


    /** J: k1 three times — S = x, x, y — and k2 once. */
    private static IDataTable partner()
    {
        return RealTables.of("J").str("K", "k1", "k1", "k1", "k2").str("S", "x", "x", "y", "z")
                .build();
    }


    private static RuleExecutionResult run(String expression, IDataTable p, IDataTable j, int cap,
            String... out)
    {
        Rule r = loadClean(
                ruleJson("Record", matchJ("") + "," + check(expression) + "," + outcome(out)),
                true);
        return RuleRunnerCalls.execute(r, p, exactInventory(p, j), null, null, null, null, cap);
    }


    private static RuleExecutionResult run(String expression, String... out)
    {
        return run(expression, primary(), partner(), Integer.MAX_VALUE, out);
    }


    @Test
    @DisplayName("identical across the expanded rows of one record ⇒ ONE finding (the first kept)")
    void identicalAcrossExpandedRowsIsOneFinding()
    {
        // k1 expands to three rows, all fire; the Check reads no merged column, so the effective
        // Output_Variables print only the primary's columns.
        RuleExecutionResult r = run("K != \"k3\"", "K", "A");
        assertEquals(List.of(Map.of("K", "k1", "A", "a1"), Map.of("K", "k2", "A", "a2")),
                QualifiedNameFixture.values(r),
                "k1's three expanded rows report the same thing: one finding; k2 one");
        assertEquals(List.of(1L, 2L), QualifiedNameFixture.rows(r));
        assertEquals(2, r.getViolationCount(), "the count is of distinct findings");
    }


    @Test
    @DisplayName("Output_Variables differing in a merged value ⇒ N findings; equal ones collapse")
    void outputVariablesDifferingOnlyInAMergedValueStayN()
    {
        // k1's rows print J.S = x, x, y: the two x rows are one finding, y another.
        RuleExecutionResult r = run("J.S != \"none\" and K != \"k3\"", "K", "J.S");
        assertEquals(List.of(Map.of("K", "k1", "J.S", "x"), Map.of("K", "k1", "J.S", "y"),
                Map.of("K", "k2", "J.S", "z")), QualifiedNameFixture.values(r));
        assertEquals(List.of(1L, 1L, 2L), QualifiedNameFixture.rows(r),
                "two findings stay on primary row 1 — they name different related records");
    }


    @Test
    @DisplayName("P-4: a missing merged value and a literal \".\" are two values, not one")
    void aMissingAndALiteralDotAreTwoFindings()
    {
        // Both render ".", so a comparison of the rendered strings would make them one finding;
        // value identity keeps them apart (D11). A planted rendered-string identity reds this.
        IDataTable j = RealTables.of("J").str("K", "k1", "k1").str("S", ".", null).build();
        RuleExecutionResult r = run("J.S != \"none\"", primary(), j, Integer.MAX_VALUE, "K", "J.S");
        List<Map<String, String>> values = QualifiedNameFixture.values(r);
        assertEquals(2, r.getViolations().stream().filter(v -> v.getRow() == 0L).count(),
                "the literal and the missing are different related values: " + values);
        assertEquals(values.get(0).get("J.S"), values.get(1).get("J.S"),
                "the fixture's premise: both render the same text");
    }


    @Test
    @DisplayName("P-6: past the cap the count is an upper bound (duplicates of unvisited rows count)")
    void pastTheCapTheCountIsAnUpperBound()
    {
        // 5 firing rows (k1 ×3, k2, k3 — kept by the left join) hold 3 distinct findings. With
        // a cap of 1 the loop stops after the first: the rest are counted as they are.
        RuleExecutionResult capped = run("K != \"none\"", primary(), partner(), 1, "K");
        assertEquals(1, capped.getViolations().size());
        RuleExecutionResult uncapped = run("K != \"none\"", "K");
        assertEquals(3, uncapped.getViolationCount(), "below the cap the count is exact");
        assertTrue(capped.getViolationCount() >= uncapped.getViolationCount(),
                "an upper bound, never an under-count: " + capped.getViolationCount());
        assertEquals(5, capped.getViolationCount(),
                "the visited finding plus the 4 unvisited firing rows, duplicates included");
    }


    @Test
    @DisplayName("OD-6: totalRows counts the primary records evaluated, not the expanded rows")
    void totalRowsCountsRealRows()
    {
        RuleExecutionResult r = run("J.S != \"none\"", "K");
        assertEquals(3, r.getTotalRows(), "P's three records; k1's expansion is not a record");
    }


    @Test
    @DisplayName("the per-(column, row) site de-duplicates like the row path")
    void thePerVariableSiteDeduplicates()
    {
        // A value() rule runs per (column, row): column A fires on every row, k1's three expanded
        // rows print the same (variable_name, variable_value) — one finding per record.
        String body = matchJ("") + ",\"Check\":{\"all\":["
                + "{\"expression\":\"ends_with(varname(), \\\"A\\\")\"},"
                + "{\"expression\":\"len(value()) > 1\"}]},"
                + outcome("variable_name", "variable_value");
        Rule r = loadClean(ruleJson("Record", body), true);
        RuleExecutionResult res = RuleRunnerCalls.execute(r, primary(),
                exactInventory(primary(), partner()));
        assertEquals(
                List.of(Map.of("variable_name", "A", "variable_value", "a1"),
                        Map.of("variable_name", "A", "variable_value", "a2"),
                        Map.of("variable_name", "A", "variable_value", "a3")),
                QualifiedNameFixture.values(res), "one finding per (column, record)");
    }


    @Test
    @DisplayName("a non-expanded table is untouched: equal findings on distinct records all stay")
    void distinctRecordsWithEqualValuesAllStay()
    {
        IDataTable p = RealTables.of("P").str("K", "k1", "k2").str("A", "same", "same").build();
        IDataTable j = RealTables.of("J").str("K", "k1", "k2").str("S", "x", "x").build();
        RuleExecutionResult r = run("J.S == \"x\"", p, j, Integer.MAX_VALUE, "A", "J.S");
        assertEquals(2, r.getViolationCount(),
                "two records printing equal values are two findings — their real rows differ");
    }
}
