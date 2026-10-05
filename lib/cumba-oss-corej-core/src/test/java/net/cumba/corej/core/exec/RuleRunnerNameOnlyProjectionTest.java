package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-finding-values-record-level-only} P1 — <b>a raw column's value is printed only on a
 * record-level finding</b> (owner 2026-10-04); on every other finding the column is listed by name
 * without a value ({@link Violation#getNamedColumns()}, V8 (b)). One test per V-row, each asserting
 * BOTH channels of the same violation: the raw column is in the name-only list and not in the
 * values map, and the finding's own values (a binding, a dataset fact, the variable metadata) are
 * still printed. A test that only asserted the absence would pass a projection that dropped
 * everything.
 *
 * <p>
 * "Record-level" is decided by the <em>producing site</em> (§7 M1), never by the rule's
 * {@code Sensitivity}: {@link #m1ARecordRuleTheFoldDecidesAtDatasetLevelListsItsColumnsByName} runs
 * a Record rule twice — once decided per row (values), once decided by the missing-column fold
 * (names).
 * </p>
 */
class RuleRunnerNameOnlyProjectionTest
{

    private static Rule load(String body) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + body + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertNotNull(rule);
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    private static Violation single(RuleExecutionResult result)
    {
        assertNotNull(result);
        assertEquals(1, result.getViolationCount(), "status=" + result.getStatus() + " "
                + result.getStatusMessage() + " violations=" + result.getViolations());
        return result.getViolations().get(0);
    }


    /** Both channels at once: the names listed, none of them valued, every name a real column. */
    private static void assertNameOnly(Violation v, List<String> expectedNames)
    {
        assertEquals(expectedNames, v.getNamedColumns(), "name-only list of " + describe(v));
        for (String name : expectedNames)
        {
            assertFalse(v.getValues().containsKey(name),
                    name + " is listed by name and must not carry a value: " + describe(v));
        }
        for (String name : v.getNamedColumns())
        {
            assertFalse(name.startsWith("$"), "a binding is never name-only: " + describe(v));
            assertFalse(OutputEntryKind.isMetadataOrVirtual(name),
                    "a metadata / virtual entry is never name-only: " + describe(v));
        }
        for (String value : v.getValues().values())
        {
            assertNotNull(value, "NK §1: no null value — " + describe(v));
        }
    }


    private static String describe(Violation v)
    {
        return "row=" + v.getRow() + " values=" + v.getValues() + " named=" + v.getNamedColumns()
                + " usubjid=" + v.getUsubjid() + " groupKey=" + v.getGroupKey();
    }

    // ------------------------------------------------------------------ V1 — a dataset verdict


    @Test
    void v1ADatasetVerdictListsTheRawColumnByNameAndPrintsItsOwnValues() throws Exception
    {
        // The presence class (325 corpus rules): the column's NAME is the subject, yet row 0's
        // value was printed. The binding, the row count and the dataset fact are the finding's
        // own values and keep printing (V2, §7 M7).
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Dataset",
                         "Bindings":[{"name":"$n","expression":"record_count()"}],
                         "Check":{"expression":"var_exists(\\"AETERM\\") and not var_exists(\\"AEDECOD\\")"},
                         "Outcome":{"Message":"m","Output_Variables":["AETERM","record_count","$n","dataset_name"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1", "S2").col("AETERM", "H", "N")
                .build();

        Violation v = single(RuleRunnerCalls.execute(rule, ae));

        assertEquals(0, v.getRow());
        assertNameOnly(v, List.of("AETERM"));
        assertEquals("2", v.getValues().get("record_count"), describe(v));
        assertEquals("2", v.getValues().get("$n"), describe(v));
        assertEquals("AE", v.getValues().get("dataset_name"), describe(v));
        assertNull(v.getUsubjid(), "a dataset verdict carries no row identity");
        assertFalse(v.isGroupFinding());
    }


    @Test
    void v1AnAbsentColumnIsInNeitherChannel() throws Exception
    {
        // NK §1 case 1 is unchanged by the plan: an absent column is omitted, not listed.
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Dataset",
                 "Check":{"expression":"not var_exists(\\"AEDECOD\\")"},
                 "Outcome":{"Message":"m","Output_Variables":["AEDECOD","AETERM"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "H").build();

        Violation v = single(RuleRunnerCalls.execute(rule, ae));

        assertNameOnly(v, List.of("AETERM"));
        assertFalse(v.getValues().containsKey("AEDECOD"), describe(v));
    }

    // ------------------------------------------------------------------ V7 — a Study rule


    @Test
    void v7AStudyRuleIsADatasetVerdictForTheProjection() throws Exception
    {
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Study",
                         "Check":{"expression":"var_exists(\\"AETERM\\") and not var_exists(\\"AEDECOD\\")"},
                         "Outcome":{"Message":"m","Output_Variables":["AETERM","record_count"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "H").build();

        Violation v = single(RuleRunnerCalls.execute(rule, ae));

        assertNameOnly(v, List.of("AETERM"));
        assertEquals("1", v.getValues().get("record_count"), describe(v));
    }

    // ------------------------------------------------------------------ V5 — Dataset {ROW}


    @Test
    void v5ADatasetRuleEvaluatedRowByRowKeepsItsFiringRowAndListsItsColumnsByName() throws Exception
    {
        // CG0077's shape: Sensitivity Dataset, a per-row Check — one finding at the first firing
        // row (M2: the row index stays), its raw columns name-only.
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Dataset",
                 "Check":{"expression":"AESEV == \\"SEVERE\\""},
                 "Outcome":{"Message":"m","Output_Variables":["AESEV","USUBJID"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1", "S2", "S3")
                .col("AESEV", "MILD", "SEVERE", "SEVERE").build();

        Violation v = single(RuleRunnerCalls.execute(rule, ae));

        assertEquals(1, v.getRow(), "the first firing row is still named (M2)");
        assertNameOnly(v, List.of("AESEV", "USUBJID"));
        assertTrue(v.getValues().isEmpty(),
                "nothing but raw columns was projected: " + describe(v));
        assertNull(v.getUsubjid(), "the {ROW} dataset path passes no row identity (M2)");
    }

    // ------------------------------------------------------------------ V1 control — Record {ROW}


    @Test
    void controlARecordFindingKeepsEveryRawColumnValueAndListsNothing() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression":"AESEV == \\"SEVERE\\""},
                 "Outcome":{"Message":"m","Output_Variables":["AESEV","USUBJID"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1", "S2", "S3")
                .col("AESEV", "MILD", "SEVERE", "SEVERE").build();

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae);

        assertEquals(2, result.getViolationCount(), "status=" + result.getStatus());
        for (Violation v : result.getViolations())
        {
            assertEquals("SEVERE", v.getValues().get("AESEV"), describe(v));
            assertEquals(v.getUsubjid(), v.getValues().get("USUBJID"), describe(v));
            assertTrue(v.getNamedColumns().isEmpty(), describe(v));
            // Owner priority (performance first): no per-row allocation for the empty list — the
            // record path hands over the shared constant, not a fresh list per finding.
            assertSame(List.of(), v.getNamedColumns(), "the shared empty constant: " + describe(v));
        }
    }

    // ------------------------------------------------------------------ §7 M1 — the producing site
    // decides


    @Test
    void m1ARecordRuleTheFoldDecidesAtDatasetLevelListsItsColumnsByName() throws Exception
    {
        // Same Record rule twice. With AEDECOD present it is decided per row: a record finding,
        // AETERM valued. With AEDECOD ABSENT the missing-column fold decides the whole Check at
        // dataset level (empty("") is true for every row) — a dataset verdict through
        // datasetBroadcastResult, and row 0's AETERM is nobody's business: name-only. Keyed on
        // the rule's Sensitivity this finding would have printed a value (§7 M1).
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression":"empty(AEDECOD)"},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM"]}}""");
        IDataTable perRow = MockTable.of().name("AE").col("USUBJID", "S1", "S2")
                .col("AETERM", "H", "N").col("AEDECOD", "", "Headache").build();
        IDataTable folded = MockTable.of().name("AE").col("USUBJID", "S1", "S2")
                .col("AETERM", "H", "N").build();

        Violation record = single(RuleRunnerCalls.execute(rule, perRow));
        Violation verdict = single(RuleRunnerCalls.execute(rule, folded));

        assertEquals("H", record.getValues().get("AETERM"), describe(record));
        assertTrue(record.getNamedColumns().isEmpty(), describe(record));
        assertEquals("S1", record.getUsubjid(), describe(record));

        assertEquals(0, verdict.getRow(), describe(verdict));
        assertNameOnly(verdict, List.of("AETERM"));
        assertNull(verdict.getUsubjid(),
                "a dataset verdict carries no row identity: " + describe(verdict));
    }

    // ------------------------------------------------------------------ V3 / Q-A — a Group finding


    @Test
    void qaAGroupFindingKeepsItsDistinctSetsAndListsNothing() throws Exception
    {
        // Owner Q-A (2026-10-05): D94c stays — the group's raw columns report their distinct set
        // over the flagged rows (the key is constant, a varying one is the set). Nothing is
        // name-only; what a group-keyed surface drops is the anchor row's identity, which the
        // engine marks through isGroupFinding().
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Group",
                         "Grouping":{"Variables":["USUBJID"]},
                         "Check":{"expression":"not is_unique_set([USUBJID, AETERM]) and not empty(AETERM)"},
                         "Outcome":{"Message":"m","Output_Variables":["USUBJID","AETERM","AESEV"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1", "S1").col("AETERM", "H", "H")
                .col("AESEV", "MILD", "SEVERE").build();

        Violation v = single(RuleRunnerCalls.execute(rule, ae));

        assertTrue(v.isGroupFinding(), describe(v));
        assertEquals(Map.of("USUBJID", "S1"), v.getGroupKey(), describe(v));
        assertEquals("S1", v.getValues().get("USUBJID"), describe(v));
        assertEquals("H", v.getValues().get("AETERM"), describe(v));
        assertEquals("[MILD, SEVERE]", v.getValues().get("AESEV"),
                "D94c's distinct set: " + describe(v));
        assertTrue(v.getNamedColumns().isEmpty(), describe(v));
        assertEquals("S1", v.getUsubjid(),
                "the anchor row's identity still rides for v1: " + describe(v));
    }

    // ------------------------------------------------------------------ V4 — a per-variable
    // finding


    @Test
    void v4AColumnLevelFindingPrintsItsMetadataAndListsAPresentRawColumnByName() throws Exception
    {
        // CDISC-CG0349's shape — 5b's one hand-over row (present-column-omitted.tsv: CG0349 /
        // DOMAIN): a {VAR} rule with a raw column beside variable_name. The column is present,
        // the finding has no row, so the column is listed by name (OMITTED_TO_NAME_ONLY); the
        // metadata values print (V4).
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"all":[{"expression": "ends_with(varname(), \\"AETERM\\")"},
                                         {"expression": "empty(var_format(\\"DATA\\"))"}]},
                         "Outcome":{"Message":"m","Output_Variables":["variable_name","variable_label","DOMAIN","variable_value"]}}""");
        IDataTable withDomain = MockTable.of().name("AE").col("DOMAIN", "AE", "AE")
                .col("AETERM", "X", "Y").build();
        IDataTable withoutDomain = MockTable.of().name("AE").col("AETERM", "X", "Y").build();

        Violation listed = single(RuleRunnerCalls.execute(rule, withDomain));
        Violation omitted = single(RuleRunnerCalls.execute(rule, withoutDomain));

        assertEquals("AETERM", listed.getValues().get("variable_name"), describe(listed));
        assertEquals("", listed.getValues().get("variable_label"), describe(listed));
        assertNameOnly(listed, List.of("DOMAIN"));
        assertFalse(listed.getValues().containsKey("variable_value"),
                "variable_value on a column-level finding names the variable the finding already"
                        + " names — omitted, not listed twice: " + describe(listed));

        assertTrue(omitted.getNamedColumns().isEmpty(),
                "an absent column is in neither channel: " + describe(omitted));
        assertFalse(omitted.getValues().containsKey("DOMAIN"), describe(omitted));
    }

    // ------------------------------------------------------------------ §7 M6 — QNU N20


    @Test
    void n20APresentColumnOnAnEmptyPrimaryIsListedByNameNotPrintedEmpty() throws Exception
    {
        // N20 printed "" for a present column with no row to read; "no value" is a state, not a
        // value (V6). The binding's typed default (5b M4) is unchanged.
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Dataset",
                 "Bindings":[{"name":"$n","expression":"AENUM + 1"}],
                 "Check":{"expression": "record_count() == 0"},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","$n","record_count"]}}""");
        IDataTable empty = MockTable.of().name("AE").col("AETERM").colDouble("AENUM").build();

        Violation v = single(RuleRunnerCalls.execute(rule, empty));

        // AENUM is derived from the binding ($n reads it): a projected raw column too.
        assertNameOnly(v, List.of("AETERM", "AENUM"));
        assertEquals(".", v.getValues().get("$n"), describe(v));
        assertEquals("0", v.getValues().get("record_count"), describe(v));
    }

    // ------------------------------------------------------------------ the record path never
    // lists


    @Test
    void thePerVariableRowPathIsRecordLevel() throws Exception
    {
        // A {VAR,ROW} rule (value() per row): a (column, row) unit WITH a row — the cell is the
        // finding's value, nothing is listed.
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"all":[{"expression": "ends_with(varname(), \\"TERM\\")"},
                                         {"expression": "len(value()) > 3"}]},
                         "Outcome":{"Message":"m","Output_Variables":["variable_name","variable_value","USUBJID"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1", "S2")
                .col("AETERM", "Headache", "Flu").build();

        Violation v = single(RuleRunnerCalls.execute(rule, ae));

        assertEquals("AETERM", v.getValues().get("variable_name"), describe(v));
        assertEquals("Headache", v.getValues().get("variable_value"), describe(v));
        assertEquals("S1", v.getValues().get("USUBJID"), describe(v));
        assertSame(List.of(), v.getNamedColumns(), describe(v));
    }
}
