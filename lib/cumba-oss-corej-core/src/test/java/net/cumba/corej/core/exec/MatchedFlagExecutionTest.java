package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * End-to-end execution of the join-match flag {@code D._matched_} (phase 5b-J — spec §3.3, D88 /
 * {@code PLAN-join-match-flag.md}): a boolean record-level condition, true iff the row found at
 * least one partner in the joined dataset under the entry's keys. Rules load through
 * {@link RulePackageLoader} (so the parser, stage A and the compiler all run for real) and execute
 * through {@link RuleRunner}, on both join shapes: the row-expanded key join
 * ({@link KeyMatchExpandedLookup} — the corpus path for plain named entries) and the index join
 * ({@link DatasetLookup} — SUPP-qualifier entries, which the expander deliberately skips).
 */
class MatchedFlagExecutionTest
{

    private static Rule rule(String matchName, String joinType, String keysJson,
            String checkExpression)
        throws IOException
    {
        String body = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"TEST-MF\"},"
                + "\"Sensitivity\":\"Record\"," + "\"Match_Datasets\":[{\"Name\":\"" + matchName
                + "\",\"Keys\":" + keysJson + ",\"Join_Type\":\"" + joinType + "\"}],"
                + "\"Check\":{\"expression\":\"" + checkExpression.replace("\"", "\\\"") + "\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
        Rule rule = RulePackageLoader.loadFromString(body).getRules().get("R1");
        assertNotNull(rule);
        return rule;
    }


    private static DatasetResolver.WithInventory inventory(Map<String, IDataTable> byName)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                return name == null ? null : byName.get(name);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return byName.keySet();
            }
        };
    }


    private static Map<String, IDataTable> study(IDataTable... tables)
    {
        Map<String, IDataTable> byName = new LinkedHashMap<>();
        for (IDataTable t : tables)
        {
            byName.put(t.getMetaData().getName(), t);
        }
        return byName;
    }


    /** The primary-row USUBJIDs the violations landed on, via the expanded row's real index. */
    private static List<String> firedSubjects(RuleExecutionResult result, IDataTable primary)
    {
        int col = primary.getMetaData().getColumnIndex("USUBJID");
        return result.getViolations().stream()
                .map(v -> String.valueOf(primary.getValue(v.getRow(), col)))
                .collect(Collectors.toList());
    }


    private static IDataTable adae()
    {
        return MockTable.of().name("ADAE").col("USUBJID", "P1", "P2", "P3")
                .col("DTHFL", "", "", "Y").build();
    }

    // ------------------------------------------------------------------
    // The row-expanded key-join path (plain named entry -> KeyMatchExpandedLookup)
    // ------------------------------------------------------------------


    @Test
    @DisplayName("not DM._matched_ fires exactly the rows with no partner (left join, expansion path)")
    void unmatchedRowsFire() throws IOException
    {
        Rule r = rule("DM", "left", "[\"USUBJID\"]", "not DM._matched_");
        assertNull(r.getLoadError(), "flag rule must load cleanly");
        IDataTable primary = adae();
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "P1", "P3").build();
        RuleExecutionResult result = RuleRunner.execute(r, primary, inventory(study(primary, dm)),
                "ADAE", null, null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of("P2"), firedSubjects(result, primary));
    }


    @Test
    @DisplayName("every row matched -> no findings")
    void allMatchedIsClean() throws IOException
    {
        Rule r = rule("DM", "left", "[\"USUBJID\"]", "not DM._matched_");
        IDataTable primary = adae();
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "P1", "P2", "P3").build();
        RuleExecutionResult result = RuleRunner.execute(r, primary, inventory(study(primary, dm)),
                "ADAE", null, null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of(), result.getViolations());
    }


    @Test
    @DisplayName("a joined dataset with zero rows -> every row unmatched, every row fires")
    void emptyJoinedDatasetFiresEveryRow() throws IOException
    {
        Rule r = rule("DM", "left", "[\"USUBJID\"]", "not DM._matched_");
        IDataTable primary = adae();
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", new String[0]).build();
        RuleExecutionResult result = RuleRunner.execute(r, primary, inventory(study(primary, dm)),
                "ADAE", null, null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of("P1", "P2", "P3"), firedSubjects(result, primary));
    }


    @Test
    @DisplayName("a matched row with a BLANK joined cell is still matched — the flag is not the empty() proxy")
    void matchedRowWithBlankCellIsMatched() throws IOException
    {
        // P1's DM row exists but carries a blank ARM; empty(DM.ARM) would fire, the flag must not.
        Rule r = rule("DM", "left", "[\"USUBJID\"]", "not DM._matched_");
        IDataTable primary = adae();
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "P1", "P2", "P3")
                .col("ARM", "", "A", "B").build();
        RuleExecutionResult result = RuleRunner.execute(r, primary, inventory(study(primary, dm)),
                "ADAE", null, null, null);
        assertEquals(List.of(), result.getViolations(),
                "a partner row with blank cells is a partner");
    }


    @Test
    @DisplayName("the positive flag composes: AE._matched_ and DTHFL != Y (multi-partner rows)")
    void positiveFlagWithMultiPartnerRows() throws IOException
    {
        // DM side: P1 has two AE partners, P2 none, P3 one (but DTHFL=Y so the conjunct blocks).
        Rule r = rule("AE", "left", "[\"USUBJID\"]", "AE._matched_ and DTHFL != \"Y\"");
        IDataTable primary = MockTable.of().name("DM").col("USUBJID", "P1", "P2", "P3")
                .col("DTHFL", "", "", "Y").build();
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "P1", "P1", "P3")
                .col("AEDECOD", "H", "N", "F").build();
        RuleExecutionResult result = RuleRunner.execute(r, primary, inventory(study(primary, ae)),
                "DM", null, null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        // P1 appears once per expanded (P1, AE-partner) pair; P2 (unmatched) and P3 (DTHFL=Y)
        // never fire.
        Set<String> fired = Set.copyOf(firedSubjects(result, primary));
        assertEquals(Set.of("P1"), fired);
    }

    // ------------------------------------------------------------------
    // The index-join path (SUPP qualifier entry -> DatasetLookup)
    // ------------------------------------------------------------------


    @Test
    @DisplayName("not SUPPAE._matched_ works on the DatasetLookup path too")
    void datasetLookupPathAnswersTheFlag() throws IOException
    {
        Rule r = rule("SUPPAE", "left", "[\"USUBJID\"]", "not SUPPAE._matched_");
        IDataTable primary = MockTable.of().name("AE").col("USUBJID", "P1", "P2").build();
        IDataTable supp = MockTable.of().name("SUPPAE").col("USUBJID", "P2").col("QNAM", "X")
                .build();
        RuleExecutionResult result = RuleRunner.execute(r, primary, inventory(study(primary, supp)),
                "AE", null, null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of("P1"), firedSubjects(result, primary));
    }

    // ------------------------------------------------------------------
    // Absent joined dataset — the three ruled outcomes, never silence
    // ------------------------------------------------------------------


    @Test
    @DisplayName("absent joined dataset + qualified Requirements.Variables declaration -> clean SKIP (D89a)")
    void absentDatasetDeclaredSkips() throws IOException
    {
        String body = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"TEST-MF\"},"
                + "\"Sensitivity\":\"Record\","
                + "\"Requirements\":{\"Variables\":{\"All\":[\"DM.USUBJID\"]}},"
                + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],"
                + "\"Join_Type\":\"left\"}]," + "\"Check\":{\"expression\":\"not DM._matched_\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
        Rule r = RulePackageLoader.loadFromString(body).getRules().get("R1");
        assertNotNull(r);
        assertNull(r.getLoadError());
        IDataTable primary = adae();
        RuleExecutionResult result = RuleRunner.execute(r, primary, inventory(study(primary)),
                "ADAE", null, null, null);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(), result.getStatusMessage());
        assertNotEquals(null, result.getStatusMessage());
    }

    // ------------------------------------------------------------------
    // Lookup-level unit checks
    // ------------------------------------------------------------------


    @Test
    void datasetLookupMatchedRowReadsTheJoinMap()
    {
        IDataTable primary = MockTable.of().name("AE").col("USUBJID", "P1", "P2", "P3").build();
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "P1", "P3").build();
        DatasetLookup lookup = DatasetLookup.build("DM", dm, List.of("USUBJID"));
        assertNotNull(lookup);
        assertTrue(lookup.matchedRow(primary, 0));
        assertTrue(!lookup.matchedRow(primary, 1));
        assertTrue(lookup.matchedRow(primary, 2));
    }
}
