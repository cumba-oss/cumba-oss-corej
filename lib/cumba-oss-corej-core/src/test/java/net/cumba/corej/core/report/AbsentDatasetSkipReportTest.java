package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * {@code Fix #222} at the <b>report surface</b>, end-to-end through {@link LibraryValidator}.
 *
 * <p>
 * Two things are pinned here that the unit-level tests cannot reach:
 * </p>
 * <ol>
 * <li><b>The precondition really is derived from the run</b> ({@code K5}). The two runs below are
 * byte-identical except that one of them <em>contains the DM presence rule</em>. That single rule —
 * not a flag, not a list — is what turns the flood into silence.</li>
 * <li><b>SKIP is distinguishable from PASS in the output.</b> A rule that reports PASS when it
 * could not be evaluated is a false assurance; the {@code Skipped_Rules} section names the rule,
 * the dataset and the reason, and the dataset that <em>is</em> missing is still reported once by
 * the presence rule.</li>
 * </ol>
 */
class AbsentDatasetSkipReportTest
{

    /** {@code CDISC-CG0368}: the bare DM presence rule — the whole precondition, as a rule. */
    private static Rule dmPresenceRule() throws Exception
    {
        return one("""
                {"rules": {"CDISC-CG0368": {
                  "Core": { "Id": "CDISC-CG0368", "Status": "Published" },
                  "Sensitivity": "Study",
                  "Description": "DM must be present in the study",
                  "Check": { "expression": "not ds_exists(\\"DM\\")" },
                  "Outcome": { "Message": "The DM dataset is not present in the study." }
                }}}
                """, "CDISC-CG0368");
    }


    /** {@code PMDA-AD0204}'s unguarded shape: floods one finding per row when DM is absent. */
    private static Rule dmDependentRule() throws Exception
    {
        return one("""
                {"rules": {"DEP-1": {
                  "Core": { "Id": "DEP-1", "Status": "Published" },
                  "Sensitivity": "Record",
                  "Description": "AGE must match DM.AGE",
                  "Match_Datasets": [ { "Name": "DM", "Keys": ["STUDYID"] } ],
                  "Check": { "expression": "not empty(AGE) and AGE != DM.AGE" },
                  "Outcome": { "Message": "AGE does not match DM.AGE",
                               "Output_Variables": ["AGE"] }
                }}}
                """, "DEP-1");
    }


    private static Rule one(String json, String id) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        return pkg.getRules().get(id);
    }


    private static MetadataProvider provider()
    {
        IMetadataLibrary lib = TestMetadataFixtures.lib("study")
                .meta(MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(MetadataKeys.STANDARD_VERSION, "3-4")
                .table(TestMetadataFixtures.table("AE").label("Adverse Events").className("Events")
                        .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                                .label("Study Identifier").core("Req").role("Identifier").build())
                        .build())
                .build();
        return new MetadataLibraryProvider(lib);
    }


    /** Two rows, both with a populated AGE — and no DM anywhere in the study. */
    private static IDataTable ae()
    {
        return MockTable.of().name("AE").col("STUDYID", "S1", "S1").col("AGE", "31", "32").build();
    }


    private static ValidationReport validate(List<Rule> rules) throws Exception
    {
        return LibraryValidator.builder().provider(provider()).rules(rules)
                .libraryUri("file:///study").targetDataset("AE", "ae.json", ae()).build()
                .validate();
    }


    private static List<Map<String, Object>> skippedRows(ValidationReport report, List<Rule> rules)
    {
        Map<String, Object> export = new ReportAssembler().report(report).rules(rules).sections()
                .toExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) export.get("Skipped_Rules");
        return rows == null ? List.of() : rows;
    }


    /**
     * The findings of the two rules under test, as {@code <coreId>#<rowCount>}. The metadata
     * provider also drives {@code RuleGenerator}'s synthetic {@code GEN-*} library rules, which are
     * irrelevant here and deliberately filtered out.
     */
    private static List<String> findingRuleIds(ValidationReport report)
    {
        List<String> ids = new ArrayList<>();
        report.getMembers()
                .forEach(m -> m.getFindings().stream().filter(
                        f -> "DEP-1".equals(f.getRuleId()) || "CDISC-CG0368".equals(f.getRuleId()))
                        .forEach(f -> ids.add(f.getRuleId() + "#" + f.getRowCount())));
        return ids;
    }


    @Test
    void withoutThePresenceRuleTheDependantFloods() throws Exception
    {
        // CONTROL. Nothing in this run reports DM's absence, so the precondition is unmet and the
        // dependant behaves exactly as it did before Fix #222.
        List<Rule> rules = List.of(dmDependentRule());
        ValidationReport report = validate(rules);

        assertEquals(List.of("DEP-1#2"), findingRuleIds(report),
                "control: an absent DM makes DM.AGE all-missing, so both rows fire");
        assertTrue(skippedRows(report, rules).isEmpty(), "nothing is skipped without coverage");
    }


    @Test
    void addingThePresenceRuleSilencesTheDependantAndSaysSo() throws Exception
    {
        // The ONLY difference from the control is that the run now contains the presence rule.
        List<Rule> rules = List.of(dmPresenceRule(), dmDependentRule());
        ValidationReport report = validate(rules);

        // The absence is reported ONCE, by the rule that exists to report it.
        assertEquals(List.of("CDISC-CG0368#1"), findingRuleIds(report),
                "the flood is gone and the presence rule carries the finding");

        // ⚠ and the dependant is visibly SKIPPED, not silently passed: a rule that reports PASS
        // when it could not be evaluated is a false assurance.
        List<Map<String, Object>> skipped = skippedRows(report, rules).stream()
                .filter(r -> "DEP-1".equals(r.get("core_id"))).toList();
        assertEquals(1, skipped.size(), "one Skipped_Rules row per (rule x dataset)");
        assertEquals("AE", skipped.get(0).get("dataset"));
        String reason = String.valueOf(skipped.get(0).get("reason"));
        assertTrue(reason.contains("DM"),
                "the reason must name the responsible dataset: " + reason);
        assertTrue(reason.startsWith("Rule skipped"), reason);

        // The presence rule itself is never skipped — the mechanism cannot eat its own
        // precondition.
        assertFalse(skippedRows(report, rules).stream()
                .anyMatch(r -> "CDISC-CG0368".equals(r.get("core_id"))));
    }

}
