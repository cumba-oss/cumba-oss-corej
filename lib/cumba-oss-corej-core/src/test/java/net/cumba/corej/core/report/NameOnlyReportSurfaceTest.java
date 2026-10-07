package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.FindingScope;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationFindingLocation;
import net.cumba.datatable.report.ValidationReport;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * {@code PLAN-finding-values-record-level-only} P2 — the report surfaces of the name-only state
 * (V6: a column a finding lists <em>by name without a value</em> is a state of its own, never
 * {@code ""}, a missing marker or {@code null}):
 *
 * <ul>
 * <li>the report model ({@link ValidationReportBuilder}): {@code Violation.getNamedColumns()}
 * becomes the finding's {@code namedColumns}, part of the finding partition, flagged in the
 * location (the highlight P1's interim lost); the {@code variable_name} collapse's partner-less
 * column — 5b's S7, a {@code null} slab cell until now — is the finding's first name-only column;
 * the slabs store no {@code null} at all (S7 / S8 / S9);</li>
 * <li>v2 ({@link ReportAssembler}): the additive finding-level {@code columns} field, present only
 * when non-empty (owner Q-B, 2026-10-05); no {@code ["null"]} padding beside it; a Group finding's
 * anchor {@code USUBJID} / {@code SEQ} blank unless the column is a group variable (owner
 * Q-A);</li>
 * <li>v1: frozen — the names go into {@code variables} with the {@code "null"} token, at the rule's
 * authored positions when the rule is known (else appended), never the identity column; a Group
 * finding keeps its anchors.</li>
 * </ul>
 */
class NameOnlyReportSurfaceTest
{

    private static Rule rule(String coreId, Domain domain)
    {
        Rule r = new Rule();
        r.setId("uuid-" + coreId);
        RuleCore core = new RuleCore();
        core.setId(coreId);
        r.setCore(core);
        r.setEvaluationDomain(domain);
        return r;
    }


    private static RuleExecutionResult result(String ruleId, List<Violation> violations)
    {
        return RuleExecutionResult.builder().ruleId(ruleId).message("m").violations(violations)
                .totalRows(10).status(RuleExecutionStatus.EXECUTED).build();
    }


    private static Map<String, String> values(String... kv)
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2)
        {
            m.put(kv[i], kv[i + 1]);
        }
        return m;
    }


    /** A non-record finding: values, no identity, the given name-only columns. */
    private static Violation nameOnly(long row, Map<String, String> values, String... named)
    {
        return new Violation(row, values, null, null, Map.of(), null, Violation.Unit.DATASET, null,
                List.of(named));
    }


    /** A record finding: values and a row identity, nothing listed by name. */
    private static Violation record(long row, String usubjid, String seq,
            Map<String, String> values)
    {
        return new Violation(row, values, usubjid, seq, Map.of(), null,
                new Violation.Unit.Row(row));
    }


    /** A Group finding: D94c values, the anchor row's identity and the block's group key. */
    private static Violation grouped(long row, String usubjid, String seq,
            Map<String, String> values, Map<String, String> groupKey)
    {
        return new Violation(row, values, usubjid, seq, Map.of(), null,
                new Violation.Unit.Group(List.copyOf(groupKey.values())), groupKey);
    }


    private static ValidationFinding onlyFinding(Rule rule, RuleExecutionResult result)
    {
        ValidationReport report = new ValidationReportBuilder().add("AE", "ae.xpt", rule, result)
                .build();
        assertEquals(1, report.getMembers().size());
        List<ValidationFinding> findings = report.getMembers().get(0).getFindings();
        assertEquals(1, findings.size(), "one finding expected, saw " + findings);
        return findings.get(0);
    }


    private static ReportSections sections(Rule rule, RuleExecutionResult result)
    {
        ValidationReport report = new ValidationReportBuilder().add("AE", "ae.xpt", rule, result)
                .build();
        return new ReportAssembler().report(report).rules(List.of(rule)).sections();
    }


    @SuppressWarnings("unchecked")
    private static Map<String, Object> onlyV2Finding(ReportSections sections)
    {
        List<Map<String, Object>> findings = (List<Map<String, Object>>) sections
                .toCombinedExportDocument().get("Findings");
        assertEquals(1, findings.size());
        return findings.get(0);
    }


    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> v2Rows(Map<String, Object> finding)
    {
        return (List<Map<String, Object>>) finding.get("rows");
    }


    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> v1Rows(ReportSections sections)
    {
        return (List<Map<String, Object>>) sections.toExportDocument().get("Issue_Details");
    }

    // ------------------------------------------------------------------ the model


    @Test
    void aNonRecordFindingListsItsRawColumnsByNameAndHighlightsThem()
    {
        // A Dataset-sensitivity rule evaluated row by row (V5): scope RECORD, the first firing
        // row named, its raw column listed by name beside the finding's own binding value.
        Rule rule = rule("T-DS-ROW", Domain.ROW);
        ValidationFinding f = onlyFinding(rule,
                result("T-DS-ROW", List.of(nameOnly(3, values("$count", "3"), "RANDFL"))));
        assertEquals(List.of("$count"), f.getVariableNames(), "valued entries only");
        assertEquals(List.of("RANDFL"), f.getNamedColumns());
        assertEquals("3", f.getRows().valueAt(0, 0));
        assertEquals(List.of("RANDFL"), f.getLocation().getVariableNames(),
                "the name-only column is what the finding is about, so it is highlighted;"
                        + " the $ binding is no column");
        assertEquals(FindingScope.RECORD, f.getScope());
    }


    @Test
    void aDatasetVerdictListsByNameButHighlightsNothingUnderDatasetScope()
    {
        Rule rule = rule("T-DS", Domain.DATASET);
        ValidationFinding f = onlyFinding(rule,
                result("T-DS", List.of(nameOnly(0, values("record_count", "12"), "AEDTC"))));
        assertEquals(List.of("AEDTC"), f.getNamedColumns());
        assertEquals(List.of(), f.getLocation().getVariableNames(),
                "FindingLocations.columnsFor prunes every column under DATASET scope, as before");
    }


    @Test
    void s7ThePartnerLessVariableNameCollapseIsTheFindingsFirstNameOnlyColumn()
    {
        Rule rule = rule("T-VAR", Domain.VARIABLE);
        ValidationFinding f = onlyFinding(rule, result("T-VAR", List.of(nameOnly(0,
                values("variable_name", "AETERM", "library_variable_role", "Topic"), "DOMAIN"))));
        assertEquals(List.of("library_variable_role"), f.getVariableNames(),
                "variable_name is consumed by the collapse; with no partner it fills no slot");
        assertEquals(List.of("AETERM", "DOMAIN"), f.getNamedColumns(),
                "the collapsed variable first, then the engine's own list");
        assertEquals("Topic", f.getRows().valueAt(0, 0));
        // The subject stays the first highlighted column (it used to be hoisted to the schema's
        // front). library_variable_role is a metadata attribute, not a column: it stays reported
        // above but is not located (PLAN-name-only-followups F3 — the engine drops its own
        // metadata vocabulary, which the shape-only FindingLocations filter cannot see).
        assertEquals(List.of("AETERM", "DOMAIN"), f.getLocation().getVariableNames());
        for (int c = 0; c < f.getRows().variableCount(); c++)
        {
            assertTrue(f.getRows().valueAt(0, c) != null, "no null slab cell (S7 retired)");
        }
    }


    @Test
    void theVariableNameCollapseWithAPartnerIsUnchanged()
    {
        Rule rule = rule("T-VAR-P", Domain.VARIABLE);
        ValidationFinding f = onlyFinding(rule, result("T-VAR-P", List.of(nameOnly(0,
                values("variable_name", "AETERM", "variable_label", "Reported Term")))));
        assertEquals(List.of("AETERM"), f.getVariableNames());
        assertEquals("Reported Term", f.getRows().valueAt(0, 0));
        assertEquals(List.of(), f.getNamedColumns());
    }


    @Test
    void twoPartnerLessVariablesArePartitionedIntoTwoFindings()
    {
        // Both share the (empty) collapsed schema; the name-only list is part of the key (§7 M3).
        Rule rule = rule("T-VAR-2", Domain.VARIABLE);
        ValidationReport report = new ValidationReportBuilder().add("AE", "ae.xpt", rule,
                result("T-VAR-2", List.of(nameOnly(0, values("variable_name", "AETERM")),
                        nameOnly(1, values("variable_name", "AESEQ")))))
                .build();
        List<ValidationFinding> findings = report.getMembers().get(0).getFindings();
        assertEquals(2, findings.size());
        assertEquals(List.of("AETERM"), findings.get(0).getNamedColumns());
        assertEquals(List.of("AESEQ"), findings.get(1).getNamedColumns());
    }


    @Test
    void aValuedColumnIsNeverAlsoNameOnly()
    {
        // variable_name = DOMAIN beside a DOMAIN entry: the value is the stronger statement.
        Rule rule = rule("T-VAR-D", Domain.VARIABLE);
        ValidationFinding f = onlyFinding(rule, result("T-VAR-D",
                List.of(nameOnly(0, values("variable_name", "DOMAIN", "DOMAIN", "AE"), "DOMAIN"))));
        assertEquals(List.of("DOMAIN"), f.getVariableNames());
        assertEquals("AE", f.getRows().valueAt(0, 0));
        assertEquals(List.of(), f.getNamedColumns());
    }


    @Test
    void aNullCellIsRefusedAtTheProducerInsideThePerRuleContainment()
    {
        // P2 review MED-1 (b): the report model stores no null (5b S7 / S9), and the refusal now
        // happens when the Violation is BUILT — inside LibraryValidator.executeRule's per-rule
        // catch, so a producer defect ERRORs one rule instead of reaching the report build.
        Map<String, String> withNull = new HashMap<>();
        withNull.put("AETERM", null);
        NullPointerException e = assertThrows(NullPointerException.class,
                () -> record(2, "S1", "1", withNull));
        assertTrue(e.getMessage().contains("AETERM"), e.getMessage());
        assertThrows(NullPointerException.class,
                () -> new Violation(0, Map.of(), null, null, Map.of(), null, null, null, null),
                "namedColumns is required (P1 review L-4)");
    }


    @Test
    void theKeySlabRefusalIsOneEngineErrorFindingNotALostReport()
    {
        // P2 review MED-1 (a): a key COLUMN the row's key map lacks is a producer defect the key
        // slab refuses (5b S8). The report build has no per-rule containment, so the refusal
        // used to throw out of ValidationReportBuilder.add and lose the whole run's report; it is
        // now ONE ENGINE_ERROR finding for that rule × dataset, naming the defect — and another
        // rule's findings in the same report survive.
        Rule rule = rule("T-KEY", Domain.ROW);
        Violation a = new Violation(0, values("AETERM", "x"), "S1", "1", values("IDVAR", "AESEQ"),
                null, new Violation.Unit.Row(0));
        Violation b = new Violation(1, values("AETERM", "y"), "S2", "2", values("QNAM", "AESOSP"),
                null, new Violation.Unit.Row(1));
        Rule other = rule("T-OK", Domain.ROW);
        ValidationReport report = new ValidationReportBuilder()
                .add("AE", "ae.xpt", rule, result("T-KEY", List.of(a, b)))
                .add("AE", "ae.xpt", other,
                        result("T-OK", List.of(record(3, "S3", "3", values("AETERM", "z")))))
                .build();
        List<ValidationFinding> findings = report.getMembers().get(0).getFindings();
        assertEquals(2, findings.size(), findings.toString());
        ValidationFinding error = findings.get(0);
        assertEquals("T-KEY", error.getRuleId());
        assertEquals(FindingKind.ENGINE_ERROR, error.getKind());
        assertTrue(error.getMessage().contains("Report model defect")
                && error.getMessage().contains("IDVAR"), error.getMessage());
        assertEquals("T-OK", findings.get(1).getRuleId());
        assertEquals(FindingKind.RULE_VIOLATION, findings.get(1).getKind());
    }


    @Test
    void aNonIllegalStateRefusalIsContainedTooAndAddsNoPartialFindings()
    {
        // Confirmation look LOW-2: the report build can throw more than IllegalStateException —
        // here Math.toIntExact's ArithmeticException on a row index past int range. It must be
        // contained the same way: ONE ENGINE_ERROR finding for the rule × dataset, and none of
        // the rule's findings built before the throw (the AETERM group precedes the failing
        // AEDECOD group), while another rule's findings survive.
        Rule rule = rule("T-ROW", Domain.ROW);
        Violation fine = record(0, "S1", "1", values("AETERM", "x"));
        Violation tooFar = record(1L << 32, "S2", "2", values("AEDECOD", "y"));
        Rule other = rule("T-OK", Domain.ROW);
        ValidationReport report = new ValidationReportBuilder()
                .add("AE", "ae.xpt", rule, result("T-ROW", List.of(fine, tooFar)))
                .add("AE", "ae.xpt", other,
                        result("T-OK", List.of(record(3, "S3", "3", values("AETERM", "z")))))
                .build();
        List<ValidationFinding> findings = report.getMembers().get(0).getFindings();
        assertEquals(2, findings.size(), findings.toString());
        ValidationFinding error = findings.get(0);
        assertEquals("T-ROW", error.getRuleId());
        assertEquals(FindingKind.ENGINE_ERROR, error.getKind());
        assertTrue(error.getMessage().contains("Report model defect"), error.getMessage());
        assertEquals("T-OK", findings.get(1).getRuleId());
        assertEquals(FindingKind.RULE_VIOLATION, findings.get(1).getKind());
    }


    @Test
    void aGroupFindingIsGroupKeyedAndKeepsItsAnchorIdentityInTheModel()
    {
        Rule rule = rule("T-GRP", Domain.ROW);
        ValidationFinding f = onlyFinding(rule, result("T-GRP", List.of(
                grouped(1, "A", "2", values("AEDECOD", "{HEADACHE}"), values("USUBJID", "A")),
                grouped(2, "B", "1", values("AEDECOD", "{RASH}"), values("USUBJID", "B")))));
        ValidationFindingLocation loc = f.getLocation();
        assertTrue(loc.isGroupKeyed());
        assertEquals(List.of("USUBJID"), loc.getKeyVariableNames());
        assertEquals(Map.of("USUBJID", "A"), f.getRowKeys(0));
        // v1 reads the anchor from the main slab, so the model keeps it there (frozen, D66).
        assertEquals(List.of("AEDECOD", "USUBJID", "SEQ"), f.getVariableNames());
        assertEquals("2", f.getRowValues(0).get("SEQ"));
        assertEquals(List.of(), f.getNamedColumns(), "a Group finding lists nothing (Q-A)");
    }


    @Test
    void aOneGroupFindingIsStillGroupKeyedWithAnEmptyKeyListAndNoAnchorKeys()
    {
        // No grouping column in the data set: the whole data set is one group; the anchor row's
        // EC-40 key must not become the finding's key.
        Rule rule = rule("T-GRP-1", Domain.ROW);
        Violation v = new Violation(1, values("AEDECOD", "{RASH, HEADACHE}"), "A", "2",
                values("AESEQ", "2"), null, new Violation.Unit.Group(List.of()), Map.of());
        ValidationFinding f = onlyFinding(rule, result("T-GRP-1", List.of(v)));
        assertTrue(f.getLocation().isGroupKeyed());
        assertEquals(List.of(), f.getLocation().getKeyVariableNames());
        assertEquals(0, f.getKeyRows().variableCount(),
                "the anchor's EC-40 key is not the finding's");
    }

    // ------------------------------------------------------------------ the documents


    @Test
    void v2CarriesTheNameOnlyColumnsAsAnAdditiveFieldAndV1AppendsThemWithItsNullToken()
    {
        Rule rule = rule("T-DOC", Domain.ROW);
        ReportSections s = sections(rule,
                result("T-DOC", List.of(nameOnly(0, values("$count", "3"), "RANDFL", "ARMCD"))));

        Map<String, Object> v2 = onlyV2Finding(s);
        assertEquals(List.of("$count"), v2.get("variables"));
        assertEquals(List.of("RANDFL", "ARMCD"), v2.get("columns"));
        assertEquals(
                List.of("core_id", "message", "executability", "dataset", "domain", "location",
                        "variables", "columns", "rows"),
                List.copyOf(v2.keySet()), "the field sits between variables and rows");
        assertEquals(List.of("3"), v2Rows(v2).get(0).get("values"),
                "values stay positional with variables; no token for a name-only column");

        List<Map<String, Object>> v1 = v1Rows(s);
        assertEquals(1, v1.size());
        assertEquals(List.of("$count", "RANDFL", "ARMCD"), v1.get(0).get("variables"),
                "v1 has no name-only state: the names are appended");
        assertEquals(List.of("3", "null", "null"), v1.get(0).get("values"),
                "the frozen format's sanctioned token (owner Q-B)");
        assertFalse(v1.get(0).containsKey("columns"), "v1 is frozen");
    }


    /**
     * P2 review LOW-1 (coordinator decision under Q-B, "v1 frozen, the name kept with its token"):
     * v1's {@code variables} read in the rule's {@code Output_Variables} order, as the last release
     * printed them — the name-only names interleaved at their authored positions (a {@code --}
     * entry too), not appended — and the identity column v1 strips from the valued ones
     * ({@code USUBJID}) never appears there with {@code "null"}. v2 is unchanged.
     */
    @Test
    void v1InterleavesTheNameOnlyColumnsInAuthoredOrderAndStripsTheIdentity()
    {
        Rule rule = rule("T-ORD", Domain.ROW);
        net.cumba.corej.core.model.Outcome outcome = new net.cumba.corej.core.model.Outcome();
        outcome.setOutputVariables(List.of("RANDFL", "$count", "USUBJID", "--ARMCD", "$tail"));
        rule.setOutcome(outcome);
        ReportSections s = sections(rule, result("T-ORD", List.of(
                nameOnly(0, values("$count", "3", "$tail", "t"), "RANDFL", "USUBJID", "AEARMCD"))));

        List<Map<String, Object>> v1 = v1Rows(s);
        assertEquals(List.of("RANDFL", "$count", "AEARMCD", "$tail"), v1.get(0).get("variables"),
                "the authored order, USUBJID stripped");
        assertEquals(List.of("null", "3", "null", "t"), v1.get(0).get("values"));

        Map<String, Object> v2 = onlyV2Finding(s);
        assertEquals(List.of("$count", "$tail"), v2.get("variables"));
        assertEquals(List.of("RANDFL", "USUBJID", "AEARMCD"), v2.get("columns"),
                "v2 lists every name-only column, its own field");
    }


    @Test
    void aRecordFindingHasNoColumnsFieldAndKeepsItsPadding()
    {
        Rule rule = rule("T-REC", Domain.ROW);
        ReportSections s = sections(rule,
                result("T-REC", List.of(record(4, "S1", "2", values("AETERM", "HEADACHE")),
                        record(7, "S2", "1", values()))));
        // Two schemas (one row valued, one not) -> two findings; the builder partitions by schema.
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> findings = (List<Map<String, Object>>) s
                .toCombinedExportDocument().get("Findings");
        assertEquals(2, findings.size());
        for (Map<String, Object> f : findings)
        {
            assertFalse(f.containsKey("columns"), "a record finding lists nothing: byte-identical");
        }
        Map<String, Object> valued = findings.get(0).get("variables").equals(List.of("AETERM"))
                ? findings.get(0)
                : findings.get(1);
        Map<String, Object> bare = valued == findings.get(0) ? findings.get(1) : findings.get(0);
        assertEquals(List.of("HEADACHE"), v2Rows(valued).get(0).get("values"));
        assertEquals(List.of("null"), v2Rows(bare).get(0).get("values"),
                "a row with no variables keeps the Python-parity padding (no columns field)");
    }


    @Test
    void v2DoesNotPadAnEmptyValueListBesideAColumnsField()
    {
        // The S7 case: a per-variable finding whose only entry was the collapsed variable.
        Rule rule = rule("T-S7-DOC", Domain.VARIABLE);
        ReportSections s = sections(rule,
                result("T-S7-DOC", List.of(nameOnly(0, values("variable_name", "AETERM")))));
        Map<String, Object> v2 = onlyV2Finding(s);
        assertEquals(List.of(), v2.get("variables"));
        assertEquals(List.of("AETERM"), v2.get("columns"));
        assertEquals(List.of(), v2Rows(v2).get(0).get("values"),
                "a \"null\" token here would read as AETERM's value");
        assertEquals(List.of("AETERM"), v1Rows(s).get(0).get("variables"));
        assertEquals(List.of("null"), v1Rows(s).get(0).get("values"), "v1: the token, as before");
    }


    @Test
    void v2BlanksAGroupFindingsAnchorIdentityUnlessTheColumnIsAGroupVariableAndV1KeepsIt()
    {
        Rule rule = rule("T-GRP-DOC", Domain.ROW);
        // Grouped by AEDECOD: USUBJID / AESEQ are the anchor row's, not the group's.
        ReportSections byDecod = sections(rule, result("T-GRP-DOC", List.of(grouped(1, "A", "2",
                values("AEDECOD", "HEADACHE"), values("AEDECOD", "HEADACHE")))));
        Map<String, Object> row = v2Rows(onlyV2Finding(byDecod)).get(0);
        assertEquals("", row.get("USUBJID"), "anchor identity dropped (owner Q-A)");
        assertEquals("", row.get("SEQ"));
        assertEquals(2, row.get("row"), "the anchor row number is still named (§7 M2)");
        assertEquals(Map.of("AEDECOD", "HEADACHE"), row.get("keys"));
        Map<String, Object> v1 = v1Rows(byDecod).get(0);
        assertEquals("A", v1.get("USUBJID"), "v1 is frozen: it keeps anchoring");
        assertEquals("2", v1.get("SEQ"));

        // Grouped by USUBJID: the subject IS the group variable and prints.
        ReportSections byUsubjid = sections(rule, result("T-GRP-DOC", List.of(
                grouped(1, "A", "2", values("AEDECOD", "{HEADACHE}"), values("USUBJID", "A")))));
        Map<String, Object> keyedRow = v2Rows(onlyV2Finding(byUsubjid)).get(0);
        assertEquals("A", keyedRow.get("USUBJID"));
        assertEquals("", keyedRow.get("SEQ"), "AESEQ is not a group variable here");
    }


    @Test
    void v2PrintsTheAnchorSeqWhenTheDataSetsSeqOrABareSeqIsAGroupVariable()
    {
        // The shared test (ValidationFindingLocation.isHiddenAnchorIdentity): SEQ — the report's
        // canonical name — prints when SEQ or the location data set's <DATASET>SEQ is a group key.
        Rule rule = rule("T-GRP-SEQ", Domain.ROW);
        ReportSections byAeseq = sections(rule, result("T-GRP-SEQ", List.of(grouped(1, "A", "2",
                values("AEDECOD", "{HEADACHE}"), values("USUBJID", "A", "AESEQ", "2")))));
        Map<String, Object> aeseqRow = v2Rows(onlyV2Finding(byAeseq)).get(0);
        assertEquals("A", aeseqRow.get("USUBJID"));
        assertEquals("2", aeseqRow.get("SEQ"), "AESEQ is the group variable");

        ReportSections byBareSeq = sections(rule, result("T-GRP-SEQ", List
                .of(grouped(1, "A", "2", values("AEDECOD", "{HEADACHE}"), values("seq", "2")))));
        Map<String, Object> bareRow = v2Rows(onlyV2Finding(byBareSeq)).get(0);
        assertEquals("", bareRow.get("USUBJID"), "USUBJID is not a group variable here");
        assertEquals("2", bareRow.get("SEQ"), "a bare SEQ key, matched ignoring case");
    }

    /**
     * {@code PLAN-name-only-followups} F3 — a finding's location names <b>data columns only</b>: an
     * entry of the engine's metadata vocabulary ({@code OutputEntryKind.isMetadataOrVirtual} — a
     * registered builtin or a {@code variable_} / {@code library_variable_} /
     * {@code define_variable_} / {@code define_vlm_} / {@code dataset_} / {@code library_dataset_}
     * / {@code define_dataset_} name, or {@code record_count}) stays a reported column
     * ({@code getVariableNames()}, the values) but is never highlighted. The shared
     * {@code FindingLocations} filter knows token shapes only, so the engine drops its own
     * vocabulary before calling it.
     */
    @Nested
    class LocationNamesDataColumnsOnly
    {

        @ParameterizedTest
        @ValueSource(strings =
        {
                // a registered builtin
                "define_variable_decode_matches",
                // the prefix arms, each with a provider-copied key that is NOT a registered
                // builtin, so the arm itself (not the builtin set) has to catch it
                "variable_custom_fact", "library_variable_custom_fact",
                "define_variable_custom_fact", "define_vlm_custom_fact", "dataset_custom_fact",
                "library_dataset_custom_fact", "define_dataset_custom_fact",
                // the one exact-name virtual
                "record_count"
        })
        void aMetadataEntryIsReportedButNotLocated(String aToken)
        {
            Rule rule = rule("T-F3", Domain.ROW);
            ValidationFinding f = onlyFinding(rule, result("T-F3",
                    List.of(record(2, "S1", "1", values("AETERM", "HEADACHE", aToken, "x")))));
            assertTrue(f.getVariableNames().contains(aToken),
                    "the report columns are unchanged: " + f.getVariableNames());
            assertEquals(List.of("AETERM"), f.getLocation().getVariableNames(),
                    aToken + " names no column of the data set");
        }


        @Test
        void aPartnerLessVariableValueIsNotLocated()
        {
            // P4: variable_value names the column cursor, not a column. Without a variable_name to
            // collapse into, it survives into the schema — and stays out of the location.
            Rule rule = rule("T-F3-VV", Domain.VARIABLE);
            ValidationFinding f = onlyFinding(rule, result("T-F3-VV",
                    List.of(nameOnly(0, values("variable_value", "x"), "AETERM"))));
            assertEquals(List.of("variable_value"), f.getVariableNames());
            assertEquals(List.of("AETERM"), f.getLocation().getVariableNames());
        }


        @Test
        void realNameOnlyAndDeclaredColumnsStay()
        {
            // A real column, a name-only column and a column the rule reports itself (USUBJID,
            // declared through its values) stay; only the metadata entry goes.
            Rule rule = rule("T-F3-KEEP", Domain.ROW);
            ValidationFinding f = onlyFinding(rule,
                    result("T-F3-KEEP", List.of(nameOnly(0,
                            values("AETERM", "HEADACHE", "variable_length", "200", "USUBJID", "S1"),
                            "AEDECOD"))));
            assertEquals(List.of("AETERM", "variable_length", "USUBJID"), f.getVariableNames());
            assertEquals(List.of("AEDECOD", "AETERM", "USUBJID"),
                    f.getLocation().getVariableNames());
        }


        @Test
        void anUpperCaseColumnWithAMetadataPrefixStays()
        {
            // P3: the classification is case-sensitive, as OutputEntryKind.of reads it — an
            // upper-case DATASET_NAME is read from the column, so it is located like a column.
            Rule rule = rule("T-F3-UC", Domain.ROW);
            ValidationFinding f = onlyFinding(rule, result("T-F3-UC", List
                    .of(record(1, "S1", "1", values("DATASET_NAME", "AE", "dataset_name", "AE")))));
            assertEquals(List.of("DATASET_NAME"), f.getLocation().getVariableNames());
        }


        @Test
        void aDatasetScopedFindingStillLocatesNoColumn()
        {
            Rule rule = rule("T-F3-DS", Domain.DATASET);
            ValidationFinding f = onlyFinding(rule, result("T-F3-DS",
                    List.of(nameOnly(0, values("AETERM", "x", "record_count", "12"), "AEDTC"))));
            assertEquals(List.of(), f.getLocation().getVariableNames());
        }


        @Test
        void aResolvedColumnWithAMetadataPrefixIsLocated()
        {
            // The vocabulary classifies Output_Variables ENTRIES, never a column a finding names:
            // a provider may keep a lower-case column such as dataset_src, and a per-variable rule
            // over all columns resolves variable_name to it. Partner-less, it is the name-only
            // subject; with a partner, the collapsed slot; listed by the engine, a raw column.
            Rule rule = rule("T-F3-RES", Domain.VARIABLE);
            ValidationFinding nameOnly = onlyFinding(rule, result("T-F3-RES", List.of(
                    nameOnly(0, values("variable_name", "dataset_src", "variable_size", "8")))));
            assertEquals(List.of("dataset_src"), nameOnly.getNamedColumns());
            assertEquals(List.of("variable_size"), nameOnly.getVariableNames());
            assertEquals(List.of("dataset_src"), nameOnly.getLocation().getVariableNames(),
                    "the subject stays located, the variable_size entry does not");

            ValidationFinding slot = onlyFinding(rule,
                    result("T-F3-RES", List.of(nameOnly(0, values("variable_name", "dataset_src",
                            "variable_data_type", "Char", "variable_size", "8")))));
            assertEquals(List.of("dataset_src", "variable_size"), slot.getVariableNames());
            assertEquals(List.of("dataset_src"), slot.getLocation().getVariableNames());

            ValidationFinding listed = onlyFinding(rule("T-F3-RES-ROW", Domain.ROW),
                    result("T-F3-RES-ROW",
                            List.of(nameOnly(0, values("record_count", "12"), "dataset_src"))));
            assertEquals(List.of("dataset_src"), listed.getLocation().getVariableNames());
        }


        @Test
        void theV2LocationFollows()
        {
            Rule rule = rule("T-F3-V2", Domain.ROW);
            ReportSections s = sections(rule, result("T-F3-V2", List.of(record(2, "S1", "1",
                    values("AEBODSYS", "X", "define_variable_name", "AEBODSYS")))));
            Map<String, Object> v2 = onlyV2Finding(s);
            assertEquals(List.of("AEBODSYS", "define_variable_name"), v2.get("variables"),
                    "the reported columns keep the metadata entry");
            @SuppressWarnings("unchecked")
            Map<String, Object> location = (Map<String, Object>) v2.get("location");
            assertEquals(List.of("AEBODSYS"), location.get("variables"));
        }
    }
}
