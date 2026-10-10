package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import net.cumba.corej.core.model.Authority;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.RowFindingSlab;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationFindingLocation;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-report-v2-rule-metadata} — the v2 {@code Rules_Report} row carries the rule's <b>PMDA
 * ids</b> ({@code pmda_rule_id}) and its <b>declared severity</b> ({@code severity}).
 *
 * <ul>
 * <li>{@code pmda_rule_id} is {@link ReportAssembler#collectRuleIds} for {@code "PMDA"}, the same
 * oracle as the CDISC and FDA columns: sorted, comma-separated, {@code ""} when the rule has none
 * (RM2: a fixed key set per row).</li>
 * <li>{@code severity} is {@link Rule#effectiveSeverity()} spelled by
 * {@link Severity#getJsonValue()} — the rule's <b>declared</b> severity, never the level of its
 * findings (RM3): a multi-level rule reports WARNING / INFO findings under a declared
 * {@code Error}.</li>
 * <li>An orphan row (a finding of a rule the run did not supply) <b>omits</b> both keys, exactly as
 * it omits {@code relrec_key} (RM7): there is no rule to read them from.</li>
 * <li>⛔ Both keys are <b>v2-only</b>: v1 is a FROZEN published consumer schema (owner ruling
 * 2026-08-11), so the v1 projection strips them — freshly assembled and round-tripped alike.</li>
 * </ul>
 */
class RulesReportRuleMetadataTest
{

    private static final String CORE_001 = "CORE-001";

    private static final String RULES_REPORT = "Rules_Report";

    private static final String PMDA_RULE_ID = "pmda_rule_id";

    private static final String SEVERITY = "severity";

    @Test
    void theV2RowCarriesThePmdaIdsSortedAndCommaSeparated()
    {
        Rule rule = withAuthorities(rule(CORE_001), authority("PMDA", "SD1002", "SD0058"),
                authority("FDA", "SD0058"), authority("CDISC", "CG0001"));

        Map<String, Object> row = v2Row(cleanRun(), CORE_001, rule);

        assertEquals("SD0058, SD1002", row.get(PMDA_RULE_ID));
        assertEquals("SD0058", row.get("fda_rule_id"), "the other organizations stay apart");
        assertEquals("CG0001", row.get("cdisc_rule_id"));
    }


    @Test
    void aRuleWithoutPmdaIdsCarriesAnEmptyPmdaColumnNotAnAbsentOne()
    {
        // RM2: a fixed key set per row — "" on a rule without PMDA ids, like cdisc / fda.
        Rule rule = withAuthorities(rule(CORE_001), authority("CDISC", "CG0001"));

        Map<String, Object> row = v2Row(cleanRun(), CORE_001, rule);

        assertTrue(row.containsKey(PMDA_RULE_ID), String.valueOf(row));
        assertEquals("", row.get(PMDA_RULE_ID));
    }


    @Test
    void severityIsTheDeclaredSeveritySpelledByItsJsonValue()
    {
        Rule warning = rule(CORE_001);
        warning.setSeverity(Severity.WARNING);
        Rule reject = rule("CORE-002");
        reject.setSeverity(Severity.REJECT);
        Rule undeclared = rule("CORE-003");

        List<Map<String, Object>> rows = v2Rows(cleanRun(), warning, reject, undeclared);

        assertEquals("Warning", rowOf(rows, CORE_001).get(SEVERITY));
        assertEquals("Reject", rowOf(rows, "CORE-002").get(SEVERITY));
        assertEquals("Error", rowOf(rows, "CORE-003").get(SEVERITY),
                "an absent Severity is ERROR (Rule.effectiveSeverity), not blank");
    }


    @Test
    void severityIsTheRulesDeclaredSeverityNotItsFindingsLevel()
    {
        // RM3: a multi-level rule reports findings at a weaker level than it declares; the row
        // states the declaration. Here the findings are at INFO and the rule declares Error.
        ValidationReport report = ValidationReport.builder()
                .members(List.of(member("AE", finding(CORE_001, Severity.INFO))))
                .executedCoreIds(List.of(CORE_001)).build();

        Map<String, Object> row = v2Row(report, CORE_001, rule(CORE_001));

        assertEquals("ISSUE_REPORTED", row.get("status"), "precondition: the finding counted");
        assertEquals("Error", row.get(SEVERITY));
    }


    @Test
    void theNewKeysSitBeforeTheRuntimeSoTheCountTailIsUnchanged()
    {
        Map<String, Object> row = v2Row(cleanRun(), CORE_001, rule(CORE_001));

        assertEquals(List.of("core_id", "version", "cdisc_rule_id", "fda_rule_id", PMDA_RULE_ID,
                "message", "status", SEVERITY, "runtime_ms", "executed", "skipped", "errored"),
                List.copyOf(row.keySet()));
    }


    @Test
    void anOrphanRowOmitsBothKeys()
    {
        // RM7: a finding of a rule the run did not supply has no rule to read ids or a severity
        // from — the keys are absent, as relrec_key is, not "" and not a guessed default.
        ValidationReport orphan = ValidationReport.builder()
                .members(List.of(member("AE", finding("CORE-999", Severity.ERROR))))
                .executedCoreIds(List.of("CORE-999")).build();

        Map<String, Object> row = v2Row(orphan, "CORE-999");

        assertFalse(row.containsKey(PMDA_RULE_ID), String.valueOf(row));
        assertFalse(row.containsKey(SEVERITY), String.valueOf(row));
        assertEquals("", row.get("fda_rule_id"), "the frozen v1 columns stay as they were");
    }


    @Test
    void theV1DocumentNeverCarriesTheNewKeys()
    {
        ReportSections sections = new ReportAssembler().report(cleanRun())
                .rules(List.of(pmdaWarningRule())).sections();
        Map<String, Object> v2Row = rows(sections.toCombinedExportDocument()).get(0);
        assertEquals("SD1001", v2Row.get(PMDA_RULE_ID), "precondition: v2 carries the id");
        assertEquals("Warning", v2Row.get(SEVERITY), "precondition: v2 carries the severity");

        Map<String, Object> v1Row = rows(sections.toExportDocument()).get(0);

        assertFalse(v1Row.containsKey(PMDA_RULE_ID), "v1 is frozen — no pmda_rule_id");
        assertFalse(v1Row.containsKey(SEVERITY), "v1 is frozen — no severity");
        assertEquals(
                List.of("core_id", "version", "cdisc_rule_id", "fda_rule_id", "message", "status",
                        "runtime_ms"),
                List.copyOf(v1Row.keySet()), "the v1 row is exactly its frozen key set");
    }


    @Test
    void aRoundTrippedV2DocumentReprojectsToV1WithoutTheNewKeys()
    {
        Map<String, Object> v2 = new ReportAssembler().report(cleanRun())
                .rules(List.of(pmdaWarningRule())).sections().toCombinedExportDocument();

        Map<String, Object> v1Again = ReportSections.fromExportDocument(v2).toExportDocument();

        Map<String, Object> row = rows(v1Again).get(0);
        assertFalse(row.containsKey(PMDA_RULE_ID), "a round-tripped v2 re-projects without it");
        assertFalse(row.containsKey(SEVERITY), "a round-tripped v2 re-projects without it");
        assertEquals("SUCCESS", row.get("status"), "the frozen v1 fields survive the strip");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------


    private static ValidationReport cleanRun()
    {
        return ValidationReport.builder().members(List.of()).executedCoreIds(List.of(CORE_001))
                .build();
    }


    private static Rule pmdaWarningRule()
    {
        Rule rule = withAuthorities(rule(CORE_001), authority("PMDA", "SD1001"));
        rule.setSeverity(Severity.WARNING);
        return rule;
    }


    private static List<Map<String, Object>> rows(Map<String, Object> document)
    {
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) document.get(RULES_REPORT);
        return rows;
    }


    private static List<Map<String, Object>> v2Rows(ValidationReport report, Rule... rules)
    {
        return rows(new ReportAssembler().report(report).rules(List.of(rules)).sections()
                .toCombinedExportDocument());
    }


    private static Map<String, Object> v2Row(ValidationReport report, String coreId, Rule... rules)
    {
        return rowOf(v2Rows(report, rules), coreId);
    }


    private static Map<String, Object> rowOf(List<Map<String, Object>> rows, String coreId)
    {
        for (Map<String, Object> row : rows)
        {
            if (coreId.equals(row.get("core_id")))
            {
                return row;
            }
        }
        throw new AssertionError("no Rules_Report row for " + coreId);
    }


    private static Authority authority(String organization, String... ruleIds)
    {
        Authority authority = new Authority();
        authority.setOrganization(organization);
        authority.setRuleIds(List.of(ruleIds));
        return authority;
    }


    private static Rule withAuthorities(Rule rule, Authority... authorities)
    {
        rule.setAuthorities(List.of(authorities));
        return rule;
    }


    private static ValidationReportMember member(String domain, ValidationFinding finding)
    {
        return ValidationReportMember.builder().domain(domain)
                .fileName(domain.toLowerCase(Locale.ROOT) + ".xpt").findings(List.of(finding))
                .build();
    }


    private static ValidationFinding finding(String coreId, Severity severity)
    {
        return ValidationFinding.builder().source("cumba.core").ruleId(coreId)
                .kind(FindingKind.RULE_VIOLATION).severity(severity).message("AESEV is invalid")
                .variableNames(List.of("AESEV"))
                .location(ValidationFindingLocation.builder().dataset("AE")
                        .variableNames(List.of("AESEV")).build())
                .rows(RowFindingSlab.builder(1).addRow(0, new String[]
                {
                        "SEVERE"
                }).build()).build();
    }


    private static Rule rule(String coreId)
    {
        Rule r = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        r.setCore(core);
        return r;
    }

}
