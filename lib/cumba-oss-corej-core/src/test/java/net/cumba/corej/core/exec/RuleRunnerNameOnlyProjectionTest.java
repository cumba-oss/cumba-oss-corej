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
        assertEquals(new java.util.LinkedHashSet<>(v.getNamedColumns()).size(),
                v.getNamedColumns().size(), "each name once (L-1): " + describe(v));
        for (String name : v.getNamedColumns())
        {
            // A $ name is listed only as a per-row binding (owner Q-C); the tests that list one
            // name it in expectedNames.
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
        // Q6 (PLAN-message-folded-corpus-alignment): the fold is opt-in — the rule declares
        // Message_Folded (inert on the per-row table: AEDECOD is present and not blank throughout).
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"expression":"empty(AEDECOD)"},
                         "Outcome":{"Message":"m","Message_Folded":"m (folded)","Output_Variables":["AETERM"]}}""");
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
        // value (V6).
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Dataset",
                 "Bindings":[{"name":"$n","expression":"AENUM + 1"}],
                 "Check":{"expression": "record_count() == 0"},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","$n","record_count"]}}""");
        IDataTable empty = MockTable.of().name("AE").col("AETERM").colDouble("AENUM").build();

        Violation v = single(RuleRunnerCalls.execute(rule, empty));

        // AENUM is derived from the binding ($n reads it): a projected raw column too. $n is a
        // per-row binding: owner Q-C lists it by name on a non-record finding — on an empty
        // primary too (its typed default "." was 5b's M4; there is no row to belong to).
        assertNameOnly(v, List.of("AETERM", "$n", "AENUM"));
        assertEquals("0", v.getValues().get("record_count"), describe(v));
    }

    // ------------------------------------------------------------------ Q-C — a per-row binding


    @Test
    void qcAPerRowBindingOnADatasetVerdictIsListedByNameAScalarOneKeepsItsValue() throws Exception
    {
        // Owner Q-C (2026-10-05, "agree, name only"). A Record rule whose Check the
        // missing-column fold decides at dataset level (AESTDY absent): a dataset verdict at row
        // 0. $end (AEENDY per row) at row 0 is a record's value through the binding channel —
        // listed by name; $n (record_count) is the finding's own value and prints (V2).
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name":"$end","expression":"AEENDY"},
                             {"name":"$n","expression":"record_count()"}],
                 "Check":{"expression":"not var_exists(\\"AESTDY\\") or $end < AESTDY"},
                 "Outcome":{"Message":"m","Output_Variables":["$end","$n"]}}""");
        IDataTable folded = MockTable.of().name("AE").col("USUBJID", "S1", "S2")
                .colDouble("AEENDY", 2.0, 30.0).build();
        IDataTable perRow = MockTable.of().name("AE").col("USUBJID", "S1", "S2")
                .colDouble("AEENDY", 2.0, 30.0).colDouble("AESTDY", 1.0, 40.0).build();

        Violation verdict = single(RuleRunnerCalls.execute(rule, folded));
        Violation record = single(RuleRunnerCalls.execute(rule, perRow));

        assertNull(verdict.getUsubjid(), "a dataset verdict: " + describe(verdict));
        assertNameOnly(verdict, List.of("$end", "AEENDY"));
        assertEquals("2", verdict.getValues().get("$n"), describe(verdict));

        // Control — the same binding on a record finding prints its row's value.
        assertEquals("S2", record.getUsubjid(), describe(record));
        assertEquals("30.0", record.getValues().get("$end"), describe(record));
        assertEquals("2", record.getValues().get("$n"), describe(record));
        assertSame(List.of(), record.getNamedColumns(), describe(record));
    }

    // ------------------------------------------------------------------ P1 review L-1 / L-4 / L-5
    // / L-2


    @Test
    void l1AColumnTwoEntriesResolveToIsListedOnce() throws Exception
    {
        // `--TERM` resolves to AETERM, which the rule also names: one column of the finding, one
        // key of the values map — listed once.
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Dataset",
                 "Check":{"expression":"var_exists(\\"AETERM\\")"},
                 "Outcome":{"Message":"m","Output_Variables":["AETERM","--TERM"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "H").build();

        Violation v = single(RuleRunnerCalls.execute(rule, ae, _ -> null, "AE"));

        assertNameOnly(v, List.of("AETERM"));
    }


    @Test
    void l4AFix18IdentifierNoJoinServesIsAbsentOnANonRecordFinding()
    {
        // A {VAR}-domain context, no join for SUPPAE: the dotted identifier names no column the
        // finding can see — omitted as absent; a present raw column beside it is listed. The
        // record-level arm keeps the name-as-value spelling.
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "H").build();
        EvaluationContext ctx = EvaluationContext.builder().table(ae)
                .evaluationDomain(net.cumba.corej.core.expr.eval.Domain.VARIABLE).build();
        List<String> ovs = List.of("SUPPAE.QNAM", "AETERM");

        RuleRunner.NameOnlyProjection nonRecord = RuleRunner.extractNameOnlyOutputValues(ae, ctx,
                ovs, 0);
        Map<String, String> recordLevel = RuleRunner.extractOutputValues(ae, ctx, ovs, 0);

        assertEquals(List.of("AETERM"), nonRecord.namedColumns());
        assertTrue(nonRecord.values().isEmpty(), nonRecord.toString());
        assertEquals("SUPPAE.QNAM", recordLevel.get("SUPPAE.QNAM"));
        assertEquals("H", recordLevel.get("AETERM"));
    }


    @Test
    void l5ASuppDeliveredQualifierIsListedByNameOnANonRecordFinding()
    {
        // W2a: AETRTEM is no AE column — SUPPAE delivers it (the declared SUPP merge). On a
        // non-record
        // finding it is a raw column like any other: listed by name; on a record-level
        // projection it reports the record's qualifier value.
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").col("AESEQ", "1").build();
        IDataTable suppae = MockTable.of().name("SUPPAE").col("RDOMAIN", "AE").col("USUBJID", "S1")
                .col("IDVAR", "AESEQ").col("IDVARVAL", "1").col("QNAM", "AETRTEM").col("QVAL", "Y")
                .build();
        // W6: the qualifier is a column of the merged view's SUPP part, the context's only
        // source of qualifiers (the legacy pivot over the resolver is retired)
        EvaluationContext ctx = ExecCalls
                .viewContext(new Rule(), ae,
                        name -> "SUPPAE".equalsIgnoreCase(name) ? suppae
                                : "AE".equalsIgnoreCase(name) ? ae : null,
                        "AE")
                .ruleId("R1").build();
        IDataTable table = ctx.getTable();
        List<String> ovs = List.of("AETRTEM", "AENOSUCH");

        RuleRunner.NameOnlyProjection nonRecord = RuleRunner.extractNameOnlyOutputValues(table, ctx,
                ovs, 0);
        Map<String, String> recordLevel = RuleRunner.extractOutputValues(table, ctx, ovs, 0);

        assertEquals(List.of("AETRTEM"), nonRecord.namedColumns(),
                "the SUPP qualifier listed, the absent column in neither channel");
        assertTrue(nonRecord.values().isEmpty(), nonRecord.toString());
        assertEquals(Map.of("AETRTEM", "Y"), recordLevel);
    }


    @Test
    void l2AColumnLevelFindingExpandsAPatternEntry() throws Exception
    {
        // CG0349's shape with the raw column spelled as a glob: the column-level path now
        // resolves its entries as the row / dataset projections do (L-2: `--`, ${*}, patterns) —
        // AETER? names the present AETERM, so it is listed by name. Before, the unexpanded
        // spelling named no column and was omitted. (A `--` entry is already specialised before
        // the runner on this path — measured: no `--` reaches it — so the glob is the probe.)
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"all":[{"expression": "ends_with(varname(), \\"AESEV\\")"},
                                 {"expression": "empty(var_format(\\"DATA\\"))"}]},
                 "Outcome":{"Message":"m","Output_Variables":["variable_name","AETER?"]}}""");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X").col("AESEV", "MILD").build();

        Violation v = single(RuleRunnerCalls.execute(rule, ae, _ -> null, "AE"));

        assertEquals("AESEV", v.getValues().get("variable_name"), describe(v));
        assertNameOnly(v, List.of("AETERM"));
    }


    @Test
    void qcAPerRowBindingOnAColumnLevelFindingIsListedByName() throws Exception
    {
        // Owner Q-C on the column-level per-variable path (buildVariableViolation). A {VAR} rule
        // (no row cursor: the Check reads only the variable cursor) may still REPORT a per-row
        // binding — Output_Variables is not part of the domain inference — so $end reaches the
        // finding, which has no row. Before this fix its value was the Vector's toString (an
        // engine object, not even a record's value). Now it is listed by name; the scalar $n and
        // the VAR-only $fmt (the column's own metadata) keep their values (V2 / V4).
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Bindings":[{"name":"$end","expression":"AEENDY"},
                                     {"name":"$n","expression":"record_count()"},
                                     {"name":"$fmt","expression":"var_format(\\"DATA\\")"}],
                         "Check":{"all":[{"expression": "ends_with(varname(), \\"AETERM\\")"},
                                         {"expression": "empty(var_format(\\"DATA\\"))"}]},
                         "Outcome":{"Message":"m","Output_Variables":["variable_name","$end","$n","$fmt"]}}""");
        assertEquals(net.cumba.corej.core.expr.eval.Domain.VARIABLE, rule.getEvaluationDomain(),
                "the probe needs the column-level path: a {VAR} domain");
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "X", "Y")
                .colDouble("AEENDY", 2.0, 30.0).build();
        IDataTable empty = MockTable.of().name("AE").col("AETERM").colDouble("AEENDY").build();

        Violation v = single(RuleRunnerCalls.execute(rule, ae));
        Violation onEmpty = single(RuleRunnerCalls.execute(rule, empty));

        for (Violation finding : List.of(v, onEmpty))
        {
            assertEquals("AETERM", finding.getValues().get("variable_name"), describe(finding));
            assertNameOnly(finding, List.of("$end", "AEENDY"));
            assertEquals("", finding.getValues().get("$fmt"), describe(finding));
        }
        assertEquals("2", v.getValues().get("$n"), describe(v));
        assertEquals("0", onEmpty.getValues().get("$n"), describe(onEmpty));
    }


    @Test
    void qcAPerRowBindingOnADefineItemFindingIsListedByName() throws Exception
    {
        // The same on the Define-universe column-level path (buildDefineVariableViolation): the
        // ItemDef finding has no row, so the per-row $age is listed by name.
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record","Variable_Universe":"Define",
                         "Bindings":[{"name":"$age","expression":"AGE"},
                                     {"name":"$n","expression":"record_count()"}],
                         "Check":{"expression":
                           "not empty(var_name(\\"DEFINE\\")) and var_role(\\"DEFINE\\") != var_role(\\"LIBRARY\\")"},
                         "Outcome":{"Message":"m","Output_Variables":["define_variable_name","$age","$n"]}}""");
        assertEquals(net.cumba.corej.core.expr.eval.Domain.VARIABLE, rule.getEvaluationDomain());
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").build();

        Violation v = single(RuleRunnerCalls.execute(rule, dm, _ -> null, "DM",
                defineProvider("Record Qualifier"), null, defineProvider("Identifier")));

        assertEquals("AGE", v.getValues().get("define_variable_name"), describe(v));
        assertNameOnly(v, List.of("$age", "AGE"));
        assertEquals("1", v.getValues().get("$n"), describe(v));
    }


    private static MetadataProvider defineProvider(String role)
    {
        return net.cumba.corej.core.metadata.MetadataLibraryProvider
                .forDefine(net.cumba.datatable.testkit.TestMetadataFixtures.lib("x")
                        .table(net.cumba.datatable.testkit.TestMetadataFixtures.table("DM")
                                .column(net.cumba.datatable.testkit.TestMetadataFixtures
                                        .column("AGE", 0,
                                                net.cumba.datatable.values.DataValueType.LONG)
                                        .role(role).build())
                                .build())
                        .build());
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
