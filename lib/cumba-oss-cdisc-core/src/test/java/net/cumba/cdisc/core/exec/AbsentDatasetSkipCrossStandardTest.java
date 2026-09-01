package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.metadata.MetadataKeys;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.metadata.TestMetadataFixtures;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.core.report.LibraryValidator;
import net.cumba.cdisc.core.report.ReportAssembler;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * {@code Fix #218} — {@code plans/PLAN-cross-standard-absence-skip.md}: a dependency on a dataset
 * belonging to a CDISC standard the run did not receive must report {@code SKIPPED}, never
 * {@code PASS}.
 *
 * <h2>Why this is a separate mechanism from {@code Fix #222}</h2> {@code Fix #222}'s precondition
 * is <i>"the run already reports this dataset's absence"</i>, which is <b>package-scoped</b>. No
 * ADaM package reports {@code DM}, and per the owner's invocation ruling none may — so the 26 ADaM
 * rules that depend on SDTM (19 on {@code DM}, 7 on {@code AE}) evaluated to <b>no findings</b> and
 * were reported as <b>PASS</b>. The report said <i>"checked, no problem"</i> where the truth was
 * <i>"could not check"</i>.
 *
 * <h2>How these tests are built</h2>
 * <ul>
 * <li>every behavioural case runs through {@link RuleRunner#execute} or a real
 * {@link LibraryValidator} — per {@code EC-45}, a probe that does not run through
 * {@code RuleRunner} proves nothing about shipped behaviour;</li>
 * <li>every case is paired with a <b>control</b> that differs only in the cross-standard set, so a
 * test that would still pass with the mechanism removed is visible immediately;</li>
 * <li>the rule bodies are the <b>shipped</b> expression shapes, measured from
 * {@code lib/corej-cdisc-rules/rules/} on 2026-08-11 — note {@code var_exists}, not {@code exists}:
 * the authored {@code exists} operator lowers to {@code var_exists} in the shipped corpus.</li>
 * </ul>
 */
class AbsentDatasetSkipCrossStandardTest
{

    // --------------------------------------------------------------------------------- fixtures

    /**
     * {@code CDISC-AD0204}'s shipped shape — {@code var_exists(DM.AGE) and AGE != DM.AGE}. The
     * guard is a <b>top-level conjunct</b> that reads {@code DM}, which is why suppressing DM
     * collapses the whole Check: the rule provably could not have fired.
     */
    private static final String AD0204 = """
            {"Core":{"Id":"CDISC-AD0204"},"Sensitivity":"Record",
             "Scope":{"Domains":{"Include":["ALL"]}},
             "Match_Datasets":[{"Name":"DM","Keys":["USUBJID"]}],
             "Check":{"expression":"var_exists(DM.AGE) and AGE != DM.AGE"},
             "Outcome":{"Message":"m","Output_Variables":["AGE"]}}""";

    /** {@code CDISC-AD0640}'s shipped shape — the {@code AE} half of the population. */
    private static final String AD0640 = """
            {"Core":{"Id":"CDISC-AD0640"},"Sensitivity":"Record",
             "Scope":{"Domains":{"Include":["ALL"]}},
             "Match_Datasets":[{"Name":"AE","Keys":["USUBJID"]}],
             "Check":{"expression":"var_exists(AE.AETRTEM) and not var_exists(\\"AETRTEM\\")"},
             "Outcome":{"Message":"m","Output_Variables":["USUBJID"]}}""";

    /**
     * The {@code CDISC-CG0007} shape, but with the foreign dataset on a cross-standard footing:
     * {@code all[ any[ <local> , <reads DM> ] , <local> ]}. The {@code or} survives suppression, so
     * the Check does <b>not</b> collapse — and {@code Fix #218}'s gate then leaves the rule
     * completely alone.
     */
    private static final String NON_COLLAPSING = """
            {"Core":{"Id":"R-OR"},"Sensitivity":"Record",
             "Scope":{"Domains":{"Include":["ALL"]}},
             "Match_Datasets":[{"Name":"DM","Keys":["USUBJID"]}],
             "Check":{"expression":"(FLAG == \\"X\\" or empty(DM.RFSTDTC)) and not empty(DY)"},
             "Outcome":{"Message":"m","Output_Variables":["DY"]}}""";

    /** Depends on one presence-covered dataset ({@code TS}) and one cross-standard one. */
    private static final String BOTH_KINDS = """
            {"Core":{"Id":"R-BOTH"},"Sensitivity":"Record",
             "Scope":{"Domains":{"Include":["ALL"]}},
             "Match_Datasets":[{"Name":"DM","Keys":["USUBJID"]},
                               {"Name":"TS","Keys":["STUDYID"]}],
             "Check":{"expression":"var_exists(DM.AGE) and not empty(TS.TSVAL)"},
             "Outcome":{"Message":"m","Output_Variables":["USUBJID"]}}""";

    private static Rule load(String ruleBody) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + ruleBody + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        assertNotNull(rule.getCheckExpr(), "the native Check expression is what decide() walks");
        return rule;
    }


    /**
     * A genuinely cohort-eligible cross-standard rule.
     *
     * <p>
     * ⚠⚠ The {@code Join_Type} clear is deliberate and is itself a measurement.
     * {@code RulePackageLoader.normalizeJoinTypes} stamps {@code "inner"} onto <b>every</b>
     * {@code Match_Datasets} entry, and {@link RuleCohortGrouper}'s equality predicate rejects any
     * entry with a non-null {@code Join_Type} — so as the corpus is loaded today <b>no</b>
     * Match_Datasets rule can form an equality cohort at all. Clearing it here reconstructs the
     * shape the grouper does cluster, so the demotion below is asserted against a real cohort
     * rather than against a shape that was a singleton anyway (which would be vacuous).
     * </p>
     *
     * @param id
     *            the rule's CORE id
     * @param column
     *            the primary column compared against {@code DM.<column>}
     * @return the loaded rule
     */
    private static Rule cohortable(String id, String column) throws Exception
    {
        // The single-leaf `<col> != DM.<col>` shape RuleCohortGrouper's EQUALITY arm clusters —
        // PMDA-AD0204's pre-guard form. Inline rather than a constant: Error Prone's
        // InlineFormatString rejects a single-use format-string constant.
        Rule rule = load("""
                {"Core":{"Id":"%1$s"},"Sensitivity":"Record",
                 "Scope":{"Domains":{"Include":["ALL"]}},
                 "Match_Datasets":[{"Name":"DM","Keys":["USUBJID"]}],
                 "Check":{"all":[{"name":"%2$s","operator":"not_equal_to","value":"DM.%2$s"}]},
                 "Outcome":{"Message":"m","Output_Variables":["%2$s"]}}""".formatted(id, column));
        rule.getMatchDatasets().forEach(md -> md.setJoinType(null));
        return rule;
    }


    private static DatasetResolver resolverOf(Map<String, IDataTable> inventory)
    {
        return inventory::get;
    }


    /** {@code adsl} — two subjects, both with a populated AGE that differs from any DM. */
    private static IDataTable adsl()
    {
        return MockTable.of().name("ADSL").col("USUBJID", "S1", "S2").col("AGE", "31", "32")
                .build();
    }


    private static RuleExecutionResult run(Rule rule, IDataTable primary, DatasetResolver resolver,
            Set<String> reported, Set<String> crossStandard)
    {
        return RuleRunner.execute(rule, primary, resolver, primary.getMetaData().getName(), null,
                null, null, Integer.MAX_VALUE, null, null, null, reported, crossStandard);
    }

    // ---------------------------------------------------- the headline: SKIPPED, never a PASS


    @Test
    void dmAbsentAndCrossStandardSkipsInsteadOfPassing() throws Exception
    {
        Rule rule = load(AD0204);
        IDataTable adsl = adsl();
        DatasetResolver noDm = resolverOf(Map.of("ADSL", adsl));

        // CONTROL — the pre-Fix #218 engine. DM is neither presence-covered nor known to be
        // cross-standard, so the guard `var_exists(DM.AGE)` is simply false and the rule reports
        // a clean EXECUTED with zero findings. That PASS is the false assurance this fix removes.
        RuleExecutionResult before = run(rule, adsl, noDm, Set.of(), Set.of());
        assertEquals(RuleExecutionStatus.EXECUTED, before.getStatus());
        assertEquals(0, before.getViolations().size(),
                "control: the guard is false, so the rule silently passes");

        // Fix #218 — DM belongs to a standard this run did not receive, and was not supplied.
        RuleExecutionResult after = run(rule, adsl, noDm, Set.of(), Set.of("DM"));
        assertEquals(RuleExecutionStatus.SKIPPED, after.getStatus(),
                "a dependency the run cannot meet must SKIP, not PASS");
        assertEquals(0, after.getViolations().size());
        assertNotNull(after.getStatusMessage());
        assertTrue(after.getStatusMessage().contains("DM"), after.getStatusMessage());
        assertTrue(after.getStatusMessage().contains("not supplied to this run"),
                after.getStatusMessage());
    }


    @Test
    void aeCrossStandardPopulationSkipsToo() throws Exception
    {
        Rule rule = load(AD0640);
        IDataTable adsl = adsl();
        DatasetResolver noAe = resolverOf(Map.of("ADSL", adsl));

        assertEquals(RuleExecutionStatus.EXECUTED,
                run(rule, adsl, noAe, Set.of(), Set.of()).getStatus());
        assertEquals(RuleExecutionStatus.SKIPPED,
                run(rule, adsl, noAe, Set.of(), Set.of("AE")).getStatus(),
                "the 7-rule AE half of the population behaves identically to the DM half");
    }


    @Test
    void dmPresentEvaluatesNormally() throws Exception
    {
        Rule rule = load(AD0204);
        IDataTable adsl = adsl();
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1", "S2").col("AGE", "99", "32")
                .build();
        // DM IS in the cross-standard catalogue and IS supplied ⇒ the rule really runs, and finds
        // the genuine mismatch on row 0. This is the neuter control for the test above: remove the
        // `resolve(D) != null` short-circuit and this assertion goes red.
        RuleExecutionResult r = run(rule, adsl, resolverOf(Map.of("ADSL", adsl, "DM", dm)),
                Set.of(), Set.of("DM"));
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus());
        assertEquals(1, r.getViolations().size(), "31 != 99 on row 0 is a genuine finding");
    }


    @Test
    void anEmptyCrossStandardSetIsTheIdentity() throws Exception
    {
        // The kill-switch shape: with no cross-standard fact the engine is byte-identical to the
        // pre-Fix #218 one, whatever the resolver says.
        Rule rule = load(AD0204);
        IDataTable adsl = adsl();
        AbsentDatasetSkip.Decision d = AbsentDatasetSkip.decide(rule,
                resolverOf(Map.of("ADSL", adsl)), Set.of(), Set.of(), "ADSL", "ADSL");
        assertFalse(d.applies());
        assertEquals(AbsentDatasetSkip.Decision.NONE, d);
    }

    // ------------------------------------------------ the trigger is NOT a target-ness test


    @Test
    void dmSuppliedAsAReferenceKeepsTheRuleRunning() throws Exception
    {
        // ⚠⚠ The sharp corollary of the owner's ruling: under `--dataset` filtering a co-located
        // SDTM dataset loads as a ReferenceDataset — visible, resolvable, NOT validated. A
        // target-ness test would SKIP precisely the rules this fix exists to run, so the predicate
        // must be "was it loaded at all". Proven here through a real LibraryValidator: DM is a
        // REFERENCE (it never appears as a report member) and the rule still evaluates.
        Rule rule = load(AD0204);
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1", "S2").col("AGE", "99", "32")
                .build();
        ValidationReport report = LibraryValidator.builder().provider(adamProvider())
                .rules(List.of(rule)).libraryUri("file:///study")
                .crossStandardDatasets(Set.of("DM")).targetDataset("ADSL", "adsl.json", adsl())
                .referenceDataset("DM", () -> dm).build().validate();

        assertFalse(report.getMembers().stream().anyMatch(m -> "DM".equals(m.getDomain())),
                "DM is a reference: it must never be iterated as a validation target");
        assertEquals(List.of("CDISC-AD0204#1"), findingIds(report),
                "the cross-standard rule must still run when SDTM is supplied as a reference");
        assertTrue(skippedRows(report, List.of(rule)).isEmpty(),
                "nothing may be skipped while the dependency is met");
    }


    @Test
    void dmNotSuppliedAtAllIsSkippedInTheReport() throws Exception
    {
        // The same run with DM simply not there. The ONLY difference from the test above is the
        // absence of `.referenceDataset("DM", ...)`.
        Rule rule = load(AD0204);
        ValidationReport report = LibraryValidator.builder().provider(adamProvider())
                .rules(List.of(rule)).libraryUri("file:///study")
                .crossStandardDatasets(Set.of("DM")).targetDataset("ADSL", "adsl.json", adsl())
                .build().validate();

        assertEquals(List.of(), findingIds(report));
        List<Map<String, Object>> skipped = skippedRows(report, List.of(rule)).stream()
                .filter(r -> "CDISC-AD0204".equals(r.get("core_id"))).toList();
        assertEquals(1, skipped.size(), "one Skipped_Rules row per (rule x dataset)");
        assertEquals("ADSL", skipped.get(0).get("dataset"));
        String reason = String.valueOf(skipped.get(0).get("reason"));
        assertTrue(reason.contains("DM") && reason.contains("not supplied to this run"), reason);
    }


    @Test
    void withoutTheRunLevelFactTheSameRunReportsPass() throws Exception
    {
        // CONTROL for the two tests above: identical inputs, no cross-standard fact ⇒ the rule
        // reports a clean pass and nothing is skipped. This is what shipped before Fix #218.
        Rule rule = load(AD0204);
        ValidationReport report = LibraryValidator.builder().provider(adamProvider())
                .rules(List.of(rule)).libraryUri("file:///study")
                .targetDataset("ADSL", "adsl.json", adsl()).build().validate();

        assertEquals(List.of(), findingIds(report));
        assertTrue(
                skippedRows(report, List.of(rule)).stream()
                        .noneMatch(r -> "CDISC-AD0204".equals(r.get("core_id"))),
                "control: without the run-level fact the false assurance is exactly what happens");
    }

    // -------------------------------------------------- the gate: collapse, and only collapse


    @Test
    void aNonCollapsingCrossStandardRuleIsLeftCompletelyAlone() throws Exception
    {
        Rule rule = load(NON_COLLAPSING);
        IDataTable adsl = MockTable.of().name("ADSL").col("USUBJID", "S1", "S2", "S3")
                .col("FLAG", "X", "Z", "Z").col("DY", "1", "1", "").build();
        DatasetResolver noDm = resolverOf(Map.of("ADSL", adsl));

        RuleExecutionResult before = run(rule, adsl, noDm, Set.of(), Set.of());
        RuleExecutionResult after = run(rule, adsl, noDm, Set.of(), Set.of("DM"));

        // ⚑ Fix #218's self-limiting gate: the `or` survives suppression, so the rule could still
        // have fired — and the cross-standard arm therefore changes NOTHING. Same status, same
        // findings, same rows. Widening the arm to suppress surviving branches would silently
        // delete findings, which is exactly what Fix #222's K5b exists to prevent.
        assertEquals(before.getStatus(), after.getStatus());
        assertEquals(rows(before), rows(after),
                "a rule with a surviving branch must be untouched by the cross-standard arm");
        assertEquals(RuleExecutionStatus.EXECUTED, after.getStatus());
        assertFalse(after.getViolations().isEmpty(), "the control must not be vacuous");
    }

    // ------------------------------------------------------ the two reasons stay distinguishable


    @Test
    void thePresenceCoveredReasonIsUnchanged() throws Exception
    {
        // Fix #222's wording is reproduced verbatim so nothing downstream that reads it breaks.
        Rule rule = load(BOTH_KINDS);
        IDataTable adsl = adsl();
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1", "S2").col("AGE", "1", "2")
                .build();
        RuleExecutionResult r = run(rule, adsl, resolverOf(Map.of("ADSL", adsl, "DM", dm)),
                Set.of("TS"), Set.of("DM"));
        assertEquals(RuleExecutionStatus.SKIPPED, r.getStatus());
        assertEquals("Rule skipped — TS is not present in the study and reported by a "
                + "dataset-presence rule", r.getStatusMessage());
    }


    @Test
    void bothReasonsCoexistAndStayApart() throws Exception
    {
        Rule rule = load(BOTH_KINDS);
        IDataTable adsl = adsl();
        AbsentDatasetSkip.Decision d = AbsentDatasetSkip.decide(rule,
                resolverOf(Map.of("ADSL", adsl)), Set.of("TS"), Set.of("DM"), "ADSL", "ADSL");
        assertTrue(d.collapsed());
        // ⚠ They are DIFFERENT facts about the run: TS is a property of the DATA (the submission
        // omitted a dataset the run reports on), DM of the INVOCATION (a whole standard was not
        // supplied). Collapsing them into one clause would lose that.
        assertEquals(List.of("TS"), d.suppressedDatasets());
        assertEquals(List.of("DM"), d.unsuppliedDatasets());
        assertEquals("Rule skipped — TS is not present in the study and reported by a "
                + "dataset-presence rule; DM was not supplied to this run (a dependency on "
                + "another CDISC standard)", d.skipReason());
    }


    @Test
    void aPresenceCoveredDatasetStaysOnTheFix222Arm() throws Exception
    {
        // A dataset named by BOTH sets: its absence IS reported, so the more informative reason
        // wins and the cross-standard clause must not appear.
        Rule rule = load(AD0204);
        IDataTable adsl = adsl();
        AbsentDatasetSkip.Decision d = AbsentDatasetSkip.decide(rule,
                resolverOf(Map.of("ADSL", adsl)), Set.of("DM"), Set.of("DM"), "ADSL", "ADSL");
        assertTrue(d.collapsed());
        assertEquals(List.of("DM"), d.suppressedDatasets());
        assertEquals(List.of(), d.unsuppliedDatasets());
    }

    // ------------------------------------------------------------------ the existing guards hold


    @Test
    void theIntentOptOutStillWins() throws Exception
    {
        // K5c is not bypassed by the new arm: for these four the foreign dataset is the thing being
        // TESTED, so silencing it would destroy the finding however the run was invoked.
        for (String id : AbsentDatasetSkip.INTENT_OPT_OUT_RULE_IDS)
        {
            Rule rule = load(AD0204.replace("CDISC-AD0204", id));
            IDataTable adsl = adsl();
            AbsentDatasetSkip.Decision d = AbsentDatasetSkip.decide(rule,
                    resolverOf(Map.of("ADSL", adsl)), Set.of(), Set.of("DM"), "ADSL", "ADSL");
            assertFalse(d.applies(), id + " must never be silenced");
        }
    }


    @Test
    void aRuleNeverSuppressesTheDatasetItRunsAgainst() throws Exception
    {
        // A rule executing ON DM cannot declare DM absent, whatever the resolver or the catalogue
        // says — otherwise an SDTM run of the same rule would silence itself.
        Rule rule = load(AD0204);
        AbsentDatasetSkip.Decision d = AbsentDatasetSkip.decide(rule, _ -> null, Set.of(),
                Set.of("DM"), "DM", "DM");
        assertFalse(d.applies());
    }

    // ------------------------------------------------ the cohort path must honour the decision


    @Test
    void aCrossStandardRuleIsDemotedOutOfItsCohort() throws Exception
    {
        // ⚠⚠ CohortRunner evaluates a cohort with a shared row pass that reads Rule.getCheckExpr()
        // directly, so it cannot honour a per-(rule, dataset) decision. Leaving the grouper
        // un-widened would let a cohorted cross-standard rule keep reporting PASS.
        //
        // ⚠ The two rules below are the single-leaf `<col> != DM.<col>` EQUALITY shape, which is
        // what RuleCohortGrouper actually clusters — a two-leaf guarded rule is ineligible anyway,
        // so asserting the demotion on one would be VACUOUS.
        Rule a = cohortable("R-A", "AGE");
        Rule b = cohortable("R-B", "SEX");
        IDataTable adsl = MockTable.of().name("ADSL").col("USUBJID", "S1").col("AGE", "31")
                .col("SEX", "M").build();
        DatasetResolver noDm = resolverOf(Map.of("ADSL", adsl));

        // CONTROL — no cross-standard fact: the two rules share ONE cohort.
        List<List<Rule>> before = RuleCohortGrouper.group(List.of(a, b), adsl.getMetaData(),
                r -> AbsentDatasetSkip.decide(r, noDm, Set.of(), Set.of(), "ADSL", "ADSL")
                        .applies());
        assertEquals(List.of(2), before.stream().map(List::size).toList(),
                "control: the shape really does cohort, or the assertion below proves nothing");

        // With the fact present BOTH are demoted to singletons and run through RuleRunner, which
        // owns the decision — and there they report SKIPPED rather than a vacuous PASS.
        List<List<Rule>> after = RuleCohortGrouper.group(List.of(a, b), adsl.getMetaData(),
                r -> AbsentDatasetSkip.decide(r, noDm, Set.of(), Set.of("DM"), "ADSL", "ADSL")
                        .applies());
        assertEquals(List.of(1, 1), after.stream().map(List::size).toList(),
                "every cross-standard rule must run through the per-rule path");
        assertEquals(RuleExecutionStatus.SKIPPED,
                run(a, adsl, noDm, Set.of(), Set.of("DM")).getStatus());
    }

    // --------------------------------------------------------------------------------- helpers


    private static List<Long> rows(RuleExecutionResult result)
    {
        return result.getViolations().stream().map(Violation::getRow).sorted().toList();
    }


    private static MetadataLibraryProvider adamProvider()
    {
        IMetadataLibrary lib = TestMetadataFixtures.lib("study")
                .meta(MetadataKeys.STANDARD_NAME, "adamig")
                .meta(MetadataKeys.STANDARD_VERSION, "1-3")
                .table(TestMetadataFixtures.table("ADSL").label("Subject Level Analysis Dataset")
                        .column(TestMetadataFixtures.column("USUBJID", 0, DataValueType.STRING)
                                .label("Unique Subject Identifier").core("Req").role("Identifier")
                                .build())
                        .build())
                .build();
        return new MetadataLibraryProvider(lib);
    }


    /** The findings of the rule under test, as {@code <coreId>#<rowCount>}. */
    private static List<String> findingIds(ValidationReport report)
    {
        List<String> ids = new ArrayList<>();
        report.getMembers()
                .forEach(m -> m.getFindings().stream()
                        .filter(f -> f.getRuleId() != null && f.getRuleId().startsWith("CDISC-AD"))
                        .forEach(f -> ids.add(f.getRuleId() + "#" + f.getRowCount())));
        return ids;
    }


    private static List<Map<String, Object>> skippedRows(ValidationReport report, List<Rule> rules)
    {
        Map<String, Object> export = new ReportAssembler().report(report).rules(rules).sections()
                .toExportDocument();
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) export.get("Skipped_Rules");
        return rows == null ? List.of() : rows;
    }

}
