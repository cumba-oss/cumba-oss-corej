package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * End-to-end execution of the {@code Match_Datasets} pre-merge {@code Filter} (phase 5b-J — spec
 * §3.3 / D88c, {@code PLAN-join-match-flag.md} §8): the filter restricts the joined dataset's own
 * rows <b>before</b> the key index is built, so together with {@code D._matched_} it expresses
 * <i>"does this row have a QUALIFYING partner"</i> — the composed shape the 2026-08 plan calls the
 * strongest argument for building both halves.
 */
class MatchFilterExecutionTest
{

    private static Rule rule(String matchJson, String checkExpression) throws IOException
    {
        String body = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"TEST-FLT\"},"
                + "\"Sensitivity\":\"Record\"," + "\"Match_Datasets\":[" + matchJson + "],"
                + "\"Check\":{\"expression\":\"" + checkExpression.replace("\"", "\\\"") + "\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
        Rule rule = RulePackageLoader.loadFromString(body).getRules().get("R1");
        assertNotNull(rule);
        return rule;
    }


    private static String aeJoin(@Nullable String filter)
    {
        return "{\"Name\":\"AE\",\"Keys\":[\"USUBJID\"],\"Join_Type\":\"left\""
                + (filter == null ? "" : ",\"Filter\":\"" + filter.replace("\"", "\\\"") + "\"")
                + "}";
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


    private static List<String> firedSubjects(RuleExecutionResult result, IDataTable primary)
    {
        int col = primary.getMetaData().getColumnIndex("USUBJID");
        return result.getViolations().stream()
                .map(v -> String.valueOf(primary.getValue(v.getRow(), col))).distinct()
                .collect(Collectors.toList());
    }


    private static IDataTable dm()
    {
        return MockTable.of().name("DM").col("USUBJID", "P1", "P2", "P3").col("DTHFL", "", "Y", "")
                .build();
    }


    private static IDataTable ae()
    {
        // P1: MILD + FATAL; P2: FATAL; P3: MILD only.
        return MockTable.of().name("AE").col("USUBJID", "P1", "P1", "P2", "P3")
                .col("AEOUT", "MILD", "FATAL", "FATAL", "MILD").build();
    }


    @Test
    @DisplayName("the composed shape: Filter + AE._matched_ asks 'has a QUALIFYING partner'")
    void filterComposesWithTheFlag() throws IOException
    {
        // "subject has a FATAL AE and DTHFL is not Y" — the owner's CG0134 example shape.
        Rule r = rule(aeJoin("AEOUT == \"FATAL\""), "AE._matched_ and DTHFL != \"Y\"");
        assertNull(r.getLoadError(), "filtered join must load cleanly");
        IDataTable primary = dm();
        RuleExecutionResult result = RuleRunner.execute(r, primary, inventory(study(primary, ae())),
                "DM", null, null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        // P1 has a FATAL AE and DTHFL != Y -> fires; P2 has FATAL but DTHFL=Y; P3 has only MILD.
        assertEquals(List.of("P1"), firedSubjects(result, primary));
    }


    @Test
    @DisplayName("without the filter the same rule matches on ANY partner — the filter is live")
    void withoutTheFilterEveryPartnerCounts() throws IOException
    {
        Rule r = rule(aeJoin(null), "AE._matched_ and DTHFL != \"Y\"");
        IDataTable primary = dm();
        RuleExecutionResult result = RuleRunner.execute(r, primary, inventory(study(primary, ae())),
                "DM", null, null, null);
        assertEquals(java.util.List.of("P1", "P3"), firedSubjects(result, primary));
    }


    @Test
    @DisplayName("a filter dropping every right row leaves every left row unmatched")
    void filterRemovingAllRowsUnmatchesEverything() throws IOException
    {
        Rule r = rule(aeJoin("AEOUT == \"NEVER\""), "not AE._matched_");
        IDataTable primary = dm();
        RuleExecutionResult result = RuleRunner.execute(r, primary, inventory(study(primary, ae())),
                "DM", null, null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of("P1", "P2", "P3"), firedSubjects(result, primary));
    }


    @Test
    @DisplayName("a filter dropping nothing is verdict-identical to no filter")
    void filterRemovingNothingChangesNothing() throws IOException
    {
        IDataTable primary = dm();
        RuleExecutionResult filtered = RuleRunner.execute(
                rule(aeJoin("non_empty(AEOUT)"), "not AE._matched_"), primary,
                inventory(study(primary, ae())), "DM", null, null, null);
        RuleExecutionResult plain = RuleRunner.execute(rule(aeJoin(null), "not AE._matched_"),
                primary, inventory(study(primary, ae())), "DM", null, null, null);
        assertEquals(plain.getStatus(), filtered.getStatus());
        assertEquals(firedSubjects(plain, primary), firedSubjects(filtered, primary));
    }


    @Test
    @DisplayName("the DatasetLookup path filters too (SUPP entry bypasses row expansion)")
    void datasetLookupPathAppliesTheFilter() throws IOException
    {
        String join = "{\"Name\":\"SUPPAE\",\"Keys\":[\"USUBJID\"],\"Join_Type\":\"left\","
                + "\"Filter\":\"QNAM == \\\"AESI\\\"\"}";
        Rule r = rule(join, "not SUPPAE._matched_");
        IDataTable primary = MockTable.of().name("AE").col("USUBJID", "P1", "P2").build();
        // P1 has only a non-AESI qualifier row -> filtered away -> unmatched; P2 has AESI.
        IDataTable supp = MockTable.of().name("SUPPAE").col("USUBJID", "P1", "P2")
                .col("QNAM", "OTHER", "AESI").build();
        RuleExecutionResult result = RuleRunner.execute(r, primary, inventory(study(primary, supp)),
                "AE", null, null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of("P1"), firedSubjects(result, primary));
    }


    @Test
    @DisplayName("an undeclared filter column the joined dataset lacks is a bind ERROR (D89)")
    void unresolvableFilterColumnErrs() throws IOException
    {
        Rule r = rule(aeJoin("AEBOGUS == \"X\""), "not AE._matched_");
        IDataTable primary = dm();
        RuleExecutionResult result = RuleRunner.execute(r, primary, inventory(study(primary, ae())),
                "DM", null, null, null);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
        assertTrue(String.valueOf(result.getStatusMessage()).contains("AE.AEBOGUS"),
                result.getStatusMessage());
    }


    @Test
    @DisplayName("declaring the filter column in Requirements.Variables skips cleanly (D89a)")
    void declaredUnresolvableFilterColumnSkips() throws IOException
    {
        String body = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"TEST-FLT\"},"
                + "\"Sensitivity\":\"Record\","
                + "\"Requirements\":{\"Variables\":{\"All\":[\"AE.AEBOGUS\"]}},"
                + "\"Match_Datasets\":[" + aeJoin("AEBOGUS == \"X\"") + "],"
                + "\"Check\":{\"expression\":\"not AE._matched_\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
        Rule r = RulePackageLoader.loadFromString(body).getRules().get("R1");
        assertNotNull(r);
        assertNull(r.getLoadError());
        IDataTable primary = dm();
        RuleExecutionResult result = RuleRunner.execute(r, primary, inventory(study(primary, ae())),
                "DM", null, null, null);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(), result.getStatusMessage());
    }
}
