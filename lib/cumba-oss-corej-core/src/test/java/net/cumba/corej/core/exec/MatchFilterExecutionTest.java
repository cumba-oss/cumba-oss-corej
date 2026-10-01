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
import net.cumba.corej.core.KeyedJoinFixtures;
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
        Rule rule = RulePackageLoader.loadFromString(KeyedJoinFixtures.declared(body)).getRules()
                .get("R1");
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
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                inventory(study(primary, ae())), "DM", null, null, null);
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
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                inventory(study(primary, ae())), "DM", null, null, null);
        assertEquals(List.of("P1", "P3"), firedSubjects(result, primary));
    }


    @Test
    @DisplayName("a filter dropping every right row leaves every left row unmatched")
    void filterRemovingAllRowsUnmatchesEverything() throws IOException
    {
        Rule r = rule(aeJoin("AEOUT == \"NEVER\""), "not AE._matched_");
        IDataTable primary = dm();
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                inventory(study(primary, ae())), "DM", null, null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of("P1", "P2", "P3"), firedSubjects(result, primary));
    }


    @Test
    @DisplayName("a filter dropping nothing is verdict-identical to no filter")
    void filterRemovingNothingChangesNothing() throws IOException
    {
        IDataTable primary = dm();
        RuleExecutionResult filtered = RuleRunnerCalls.execute(
                rule(aeJoin("non_empty(AEOUT)"), "not AE._matched_"), primary,
                inventory(study(primary, ae())), "DM", null, null, null);
        RuleExecutionResult plain = RuleRunnerCalls.execute(rule(aeJoin(null), "not AE._matched_"),
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
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                inventory(study(primary, supp)), "AE", null, null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of("P1"), firedSubjects(result, primary));
    }


    @Test
    @DisplayName("an undeclared filter column the joined dataset lacks is a bind ERROR (D89)")
    void unresolvableFilterColumnErrs() throws IOException
    {
        Rule r = rule(aeJoin("AEBOGUS == \"X\""), "not AE._matched_");
        IDataTable primary = dm();
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                inventory(study(primary, ae())), "DM", null, null, null);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
        assertTrue(String.valueOf(result.getStatusMessage()).contains("AE.AEBOGUS"),
                result.getStatusMessage());
    }


    /**
     * {@code PLAN-stage-a-parameter-type-arming} C6: a {@code Filter} is compiled at run time and
     * never parameter-checked by stage A, so the R1 seam stays at the compile sites — a quoted name
     * at a column-reference slot inside a Filter is still refused (the rule ERRORs) rather than
     * compiling silently against a constant string.
     */
    @Test
    @DisplayName("a quoted name at a column slot inside a Filter is still refused (C6)")
    void aQuotedColumnNameInsideAFilterIsStillRefused() throws IOException
    {
        Rule r = rule(aeJoin("dy(\"AEOUT\", AEOUT) > 0"), "not AE._matched_");
        assertNull(r.getLoadError(),
                "stage A never parameter-checks a Filter: " + r.getLoadError());
        IDataTable primary = dm();
        // The compile-site seam refuses the Filter when it is compiled, at run time — as an
        // ExpressionException out of the runner (the validator's catch-all makes it the rule's
        // ERROR in production; the D89 bind error below is the status-channel sibling). What this
        // control pins is the REFUSAL and its wording, not the channel.
        net.cumba.corej.core.expr.ExpressionException refused = org.junit.jupiter.api.Assertions
                .assertThrows(net.cumba.corej.core.expr.ExpressionException.class,
                        () -> RuleRunnerCalls.execute(r, primary, inventory(study(primary, ae())),
                                "DM", null, null, null));
        assertTrue(String.valueOf(refused.getMessage())
                .contains("takes a column reference, not the literal AEOUT"), refused.getMessage());
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
        Rule r = RulePackageLoader.loadFromString(KeyedJoinFixtures.declared(body)).getRules()
                .get("R1");
        assertNotNull(r);
        assertNull(r.getLoadError());
        IDataTable primary = dm();
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                inventory(study(primary, ae())), "DM", null, null, null);
        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus(), result.getStatusMessage());
    }


    @Test
    @DisplayName("a filter reading the study inventory sees the run's datasets (W4 L3)")
    void aFilterReadingTheStudyInventorySeesTheRunsDatasets() throws IOException
    {
        // Combined review of runbook W2–W8, W4 L3: the filter's sub-context carried no resolver,
        // so dataset_names() hit the loud no-inventory arm (D-W4-5) and the rule ERRORed. With the
        // run's study handed through it answers as filterComposesWithTheFlag does. RED before the
        // fix: status ERROR.
        Rule r = rule(aeJoin("AEOUT == \"FATAL\" and \"DM\" in dataset_names()"),
                "AE._matched_ and DTHFL != \"Y\"");
        assertNull(r.getLoadError(), "the filter loads: " + r.getLoadError());
        IDataTable primary = dm();
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                inventory(study(primary, ae())), "DM", null, null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of("P1"), firedSubjects(result, primary));
    }


    @Test
    @DisplayName("a computed colref in the Filter takes the absent default of its numeric position")
    void aColrefInTheFilterTakesTheAuthoredAbsentDefault() throws IOException
    {
        // PLAN-dynamic-column-functions §2.3 step 2 (review round 1, lane B M2): the Filter's
        // sub-context carries the colref call sites' numeric expectations. AE has no ZZ, so the
        // COMPUTED `colref(concat("Z", "Z")) < 3` is MIS < 3 — every AE row kept, every subject
        // with an AE matched; without the carry it is "" < 3, keeping none.
        Rule r = rule(aeJoin("colref(concat(\"Z\", \"Z\")) < 3"), "AE._matched_");
        assertNull(r.getLoadError(), r.getLoadError());
        IDataTable primary = dm();
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                inventory(study(primary, ae())), "DM", null, null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(List.of("P1", "P2", "P3"), firedSubjects(result, primary));
    }


    @Test
    @DisplayName("a WRITTEN colref in the Filter is FILTER_UNRESOLVABLE, like the authored name")
    void aWrittenColrefInTheFilterIsUnresolvableLikeTheAuthoredName() throws IOException
    {
        // Review round 2 (M2, coordinator decision (a)): a literal colref("ZZ") names its column
        // as plainly as ZZ does, so stage B holds it to the same D89 contract — an absent filter
        // column is an armed bind error (declare it in Requirements.Variables to skip instead).
        IDataTable primary = dm();
        // Round 3: an authored column read INSIDE a colref argument (`colref(ZZCOL)`) is still a
        // plain filter column — the colref arm must not stop the walk.
        for (String filter : List.of("ZZ < 3", "colref(\"ZZ\") < 3", "colref([\"ZZ\"]) != \"\"",
                "colref(ZZCOL) == \"Y\""))
        {
            RuleExecutionResult result = RuleRunnerCalls.execute(
                    rule(aeJoin(filter), "AE._matched_"), primary, inventory(study(primary, ae())),
                    "DM", null, null, null);
            assertEquals(RuleExecutionStatus.ERROR, result.getStatus(), filter);
            assertTrue(String.valueOf(result.getStatusMessage()).contains("FILTER_UNRESOLVABLE"),
                    filter + " → " + result.getStatusMessage());
        }
    }


    @Test
    @DisplayName("a WRITTEN dotted colref in the Filter is FILTER_LEFT_REFERENCE, like DM.SEX")
    void aWrittenDottedColrefInTheFilterIsALeftReference() throws IOException
    {
        // Review round 3: the filter runs on AE's own rows before the join, so a written
        // colref("DM.DTHFL") is the same left-side reference as the authored DM.DTHFL — a load
        // error — never a silent absent default that filters every row out.
        for (String filter : List.of("DM.DTHFL == \"Y\"", "colref(\"DM.DTHFL\") == \"Y\"",
                "colref([\"AEOUT\", \"DM.DTHFL\"]) != \"\""))
        {
            Rule r = rule(aeJoin(filter), "AE._matched_");
            assertNotNull(r.getLoadError(), filter);
            assertTrue(r.getLoadError().contains("FILTER_LEFT_REFERENCE"), r.getLoadError());
        }
        assertNull(rule(aeJoin("colref(\"AEOUT\") == \"FATAL\""), "AE._matched_").getLoadError());
    }
}
