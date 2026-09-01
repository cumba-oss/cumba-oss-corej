package net.cumba.cdisc.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleExecutionStatus;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.exec.StubMetadataProvider;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.core.report.ValidationReportBuilder;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.FindingKind;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.junit.jupiter.api.Test;

/**
 * {@code Fix #356} — the per-domain {@code --} expansion
 * ({@link RuleGenerator#expandSdtmPrefixRules}) must resolve the wildcard <em>inside</em> an
 * {@code Outcome.Output_Variables} exclusion token
 * ({@link net.cumba.cdisc.core.model.OutputVariableToken}).
 *
 * <p>
 * The shipped map tested the raw entry — {@code v.startsWith("--") ? prefix + v.substring(2) : v} —
 * and a {@code "!--ORRES"} entry starts with the {@code !} marker, so it passed through unresolved
 * while the Check <em>was</em> resolved. The per-rule derivation that runs on every generated rule
 * ({@code RulePackageLoader.deriveOutputVariables}) then failed E-3 check 1 on the stale token
 * ("names nothing the rule derives"), tagged a {@code loadError}, and the rule reported
 * {@code ENGINE_ERROR} on every dataset it targeted.
 * </p>
 *
 * <p>
 * ⚠ The class-(c) apply lane measured exactly this and it was invisible to the suites: the
 * {@code .cdt} scenarios, the rulespec suite and the positive load/projection tests were all green
 * while 121 {@code ENGINE_ERROR} findings across 34 rules were being produced. Only
 * {@code gen-findings-snapshot.sh} saw it. So the last two tests here assert at the level that
 * failed — the executed status, and the report finding the snapshot is projected from — not at the
 * model level that stayed green.
 * </p>
 */
class PerDomainOutputExclusionTest
{

    /**
     * A record-level LB rule whose Check reads the wildcard {@code --ORRES}, reports the wildcard
     * {@code --STRESC}, and EXCLUDES {@code --ORRES} from the finding. Loaded through
     * {@link RulePackageLoader} so the pre-expansion E-3 validation runs on it too.
     */
    private static Rule staticRule() throws Exception
    {
        String pkg = """
                {"rules":{"R1":{
                  "Core":{"Id":"T-OVX"},
                  "Sensitivity":"Record",
                  "Check":{"name":"--ORRES","operator":"empty"},
                  "Outcome":{"Message":"m","Output_Variables":["--STRESC","!--ORRES"]}}}}""";
        RulePackage loaded = RulePackageLoader.loadFromString(pkg);
        Rule rule = loaded.getRules().get("R1");
        assertNotNull(rule);
        return rule;
    }


    /** One LB row: LBORRES empty (the Check fires), LBSTRESC populated (the retained sibling). */
    private static IDataTable lb()
    {
        return MockTable.of().name("LB").col("USUBJID", "S1").col("LBSEQ", "1").col("LBORRES", "")
                .col("LBSTRESC", "NEG").build();
    }


    private static Rule expandFor(String domain, IDataTable table) throws Exception
    {
        RuleGenerator generator = new RuleGenerator(new StubMetadataProvider(), null);
        generator.setDomainName(domain);
        generator.setStaticRules(List.of(staticRule()));
        GeneratedRulePackage out = generator.generate(table);
        List<Rule> mine = out.getRules().stream()
                .filter(r -> r.getCore() != null && "T-OVX".equals(r.getCore().getId())).toList();
        assertEquals(1, mine.size(), "exactly one per-domain expansion of T-OVX");
        return mine.get(0);
    }

    // ------------------------------------------------------------------ (a) the fix


    @Test
    void theExpandedRuleLoadsCleanlyAndResolvesTheWildcardInsideTheToken() throws Exception
    {
        Rule pre = staticRule();
        assertNull(pre.getLoadError(), "the authored rule must be valid before expansion");

        Rule concrete = expandFor("LB", lb());

        // The whole defect in one assertion: an unresolved `!--ORRES` is an E-3.1 load error.
        assertNull(concrete.getLoadError(),
                () -> "expanded rule must load cleanly, got: " + concrete.getLoadError());

        // The Check resolved …
        String check = new com.fasterxml.jackson.databind.ObjectMapper()
                .valueToTree(concrete.getCheck()).toString();
        assertTrue(check.contains("LBORRES"), check);
        assertFalse(check.contains("--ORRES"), check);

        // … and so did BOTH halves of the authored Output_Variables — the retained wildcard and
        // the name inside the exclusion token, marker preserved.
        assertNotNull(concrete.getOutcome());
        assertEquals(List.of("LBSTRESC", "!LBORRES"), concrete.getOutcome().getOutputVariables());

        // The effective list keeps the retained sibling and drops the excluded name.
        assertEquals(List.of("LBSTRESC"), concrete.getEffectiveOutputVariables());
        assertEquals(java.util.Set.of("LBORRES"), concrete.excludedOutputVariablesOrAuthored());
    }


    @Test
    void theProjectionCarriesTheRetainedSiblingAndNotTheExcludedName() throws Exception
    {
        IDataTable table = lb();
        Rule concrete = expandFor("LB", table);

        RuleExecutionResult result = RuleRunner.execute(concrete, table);
        assertEquals(1, result.getViolationCount(), "LBORRES is empty on the only row");
        Map<String, String> values = result.getViolations().get(0).getValues();
        // Both arms — a filter that removed everything would pass the absence half alone.
        assertTrue(values.containsKey("LBSTRESC"), values.toString());
        assertEquals("NEG", values.get("LBSTRESC"));
        assertFalse(values.containsKey("LBORRES"), values.toString());
        assertFalse(values.containsKey("--ORRES"), values.toString());
    }

    // ------------------------------------------------------------------ (c) the snapshot level


    @Test
    void theExpandedRuleEXECUTESRatherThanErroring() throws Exception
    {
        IDataTable table = lb();
        Rule concrete = expandFor("LB", table);

        RuleExecutionResult result = RuleRunner.execute(concrete, table);
        // The snapshot's `status:` rows come from exactly this value. With the defect live it was
        // ERROR on every targeted dataset.
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> "status message: " + result.getStatusMessage());
        assertNull(result.getStatusMessage());
    }


    @Test
    void theReportCarriesNoEngineErrorFinding() throws Exception
    {
        IDataTable table = lb();
        Rule concrete = expandFor("LB", table);
        RuleExecutionResult result = RuleRunner.execute(concrete, table);

        ValidationReport report = new ValidationReportBuilder()
                .add("LB", "lb.xpt", concrete, result).build();
        List<ValidationFinding> findings = report.getMembers().stream()
                .map(ValidationReportMember::getFindings).flatMap(List::stream).toList();
        assertEquals(1, findings.size(), findings.toString());
        ValidationFinding finding = findings.get(0);
        // The projection the findings snapshot renders: kind, and the row's output values.
        assertEquals(FindingKind.RULE_VIOLATION, finding.getKind(), finding.getMessage());
        Map<String, String> values = finding.getFirstRowValues();
        assertNotNull(values);
        assertTrue(values.containsKey("LBSTRESC"), values.toString());
        assertFalse(values.containsKey("LBORRES"), values.toString());
    }

    // ------------------------------------------------------------------ the other rewrite sites

    // ⚑ anExclusionTokenDoesNotFailTheRequireAllWildcardsGate lived here and was removed with
    // its subject (Fix #366). It pinned Fix #356's second blind rewrite site: the
    // `requireAllWildcardsInDataset` gate inside applyTemplatePostFilters asked
    // `datasetColumns.contains(ov)` of the RAW entry, so an exclusion token was looked up as a
    // column literally named "!TR01SDT" and the whole expansion was dropped in silence. That gate
    // is deleted — the field was legal only in rules-templates.json, had zero corpus carriers, and
    // is gone from the Rule model — so there is no code path left to hold. Fix #356's OTHER half,
    // the effective-output-variable projection, is pinned by the tests above and below.


    @Test
    void aTemplateWithNoExclusionIsUnaffected() throws Exception
    {
        // The control: the same expansion without a token, so the fix cannot be passing by
        // disabling the substitution it is supposed to perform.
        String pkg = """
                {"rules":{"R1":{
                  "Core":{"Id":"T-OVY"},
                  "Sensitivity":"Record",
                  "Check":{"name":"--ORRES","operator":"empty"},
                  "Outcome":{"Message":"m","Output_Variables":["--ORRES","--STRESC"]}}}}""";
        Rule template = RulePackageLoader.loadFromString(pkg).getRules().get("R1");
        assertNotNull(template);

        IDataTable table = lb();
        RuleGenerator generator = new RuleGenerator(new StubMetadataProvider(), null);
        generator.setDomainName("LB");
        generator.setStaticRules(List.of(template));
        Rule concrete = generator.generate(table).getRules().stream()
                .filter(r -> r.getCore() != null && "T-OVY".equals(r.getCore().getId())).findFirst()
                .orElseThrow();

        assertNull(concrete.getLoadError(), concrete.getLoadError());
        assertNotNull(concrete.getOutcome());
        assertEquals(List.of("LBORRES", "LBSTRESC"), concrete.getOutcome().getOutputVariables());
    }
}
