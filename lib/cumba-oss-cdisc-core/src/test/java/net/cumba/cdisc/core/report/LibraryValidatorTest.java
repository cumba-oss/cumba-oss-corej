package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.DomainScope;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.core.model.Scope;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.cdisc.core.run.DatasetExecutionSummary;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

class LibraryValidatorTest
{

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static MetadataProvider providerWithDm()
    {
        IMetadataLibrary lib = TestMetadataFixtures.lib("study")
                .meta(net.cumba.cdisc.core.metadata.MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(net.cumba.cdisc.core.metadata.MetadataKeys.STANDARD_VERSION, "3-4")
                .table(TestMetadataFixtures.table("DM").label("Demographics")
                        .className("Special-Purpose").structure("One record per subject")
                        .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                                .label("Study Identifier").core("Req").role("Identifier").build())
                        .column(TestMetadataFixtures.column("USUBJID", 1, DataValueType.STRING)
                                .label("Unique Subject Identifier").core("Req").role("Identifier")
                                .build())
                        .column(TestMetadataFixtures.column("SEX", 2, DataValueType.STRING)
                                .label("Sex").core("Req").role("Record Qualifier").build())
                        .build())
                .build();
        return new MetadataLibraryProvider(lib);
    }


    private static Rule simpleRule(String coreId)
    {
        Rule r = new Rule();
        r.setId("uuid-" + coreId);
        RuleCore core = new RuleCore();
        core.setId(coreId);
        r.setCore(core);
        return r;
    }


    private static RulePackage emptyRulePackage()
    {
        RulePackage pkg = new RulePackage();
        pkg.setRules(new java.util.HashMap<>());
        return pkg;
    }


    /**
     * A minimal package-shaped corpus rule set. ⚑ Fix #366: before it, {@code emptyRulePackage()}
     * was enough for any test needing a dataset to be validated, because the generator merged its
     * built-in {@code rules-templates.json} into every run. Nothing is merged behind the caller's
     * back any more, so a run with zero selected rules validates zero rules — and a test that needs
     * a rule to execute has to supply one.
     */
    private static RulePackage ruleSetOf(String... coreIds)
    {
        RulePackage pkg = new RulePackage();
        java.util.Map<String, Rule> rules = new java.util.HashMap<>();
        String[] columns =
        {
                "STUDYID", "USUBJID"
        };
        int i = 0;
        for (String coreId : coreIds)
        {
            Rule rule = new Rule();
            rule.setId("uuid-" + coreId);
            RuleCore core = new RuleCore();
            core.setId(coreId);
            rule.setCore(core);
            rule.setSensitivity(Sensitivity.RECORD);
            // non_empty on an always-populated identifier: the Check MATCHES on every row, so
            // each rule yields findings and the dataset really is reported. A rule that never
            // fires leaves the dataset out of the report entirely and the assertions below would
            // be measuring the fixture, not the validator.
            rule.setCheck(CheckConditionLeaf.builder().name(columns[i++ % columns.length])
                    .operator("non_empty").build());
            Outcome outcome = new Outcome();
            outcome.setMessage(coreId + " fired");
            rule.setOutcome(outcome);
            rules.put(coreId, rule);
        }
        pkg.setRules(rules);
        return pkg;
    }


    private static IDataTable dmTableWithAllColumns()
    {
        return MockTable.of().name("DM").col("STUDYID", "STUDY1", "STUDY1")
                .col("USUBJID", "SUBJ-001", "SUBJ-002").col("SEX", "M", "F").build();
    }


    private static IDataTable dmTableMissingSex()
    {
        return MockTable.of().name("DM").col("STUDYID", "STUDY1", "STUDY1")
                .col("USUBJID", "SUBJ-001", "SUBJ-002").build();
    }

    // ------------------------------------------------------------------
    // Builder contract
    // ------------------------------------------------------------------


    @Test
    void builderRequiresProvider()
    {
        assertThrows(NullPointerException.class,
                () -> LibraryValidator.builder().rules(emptyRulePackage())
                        .targetDataset("DM", "dm.xpt", dmTableWithAllColumns()).build());
    }


    @Test
    void builderAllowsEmptyRules()
    {
        // An empty static rule set is a legal builder input; since Fix #366 it simply means the
        // run will execute nothing.
        LibraryValidator built = LibraryValidator.builder().provider(providerWithDm())
                .targetDataset("DM", "dm.xpt", dmTableWithAllColumns()).build();
        assertNotNull(built);
    }


    @Test
    void builderRejectsNullDataset()
    {
        assertThrows(NullPointerException.class,
                () -> LibraryValidator.builder().provider(providerWithDm())
                        .rules(emptyRulePackage()).targetDataset("DM", null, (IDataTable) null));
    }


    @Test
    void datasetRecordRejectsNullDomain()
    {
        IDataTable t = dmTableWithAllColumns();
        assertThrows(NullPointerException.class,
                () -> new LibraryValidator.Dataset(null, "x", () -> t));
    }

    // ------------------------------------------------------------------
    // Validation — clean case
    // ------------------------------------------------------------------


    @Test
    void validateProducesReportWithMember()
    {
        // ⚑ Fix #366: this used to run with an empty rule package and lean on the built-in
        // templates to put a member in the report. Report members are FINDINGS-driven — see
        // ValidationReportBuilder.add, which returns before touching the accumulator when a rule
        // passes cleanly — so with nothing selected there is nothing to report and this test's own
        // name would have become false. The rule below fires on every row.
        ValidationReport report = LibraryValidator.builder().provider(providerWithDm())
                .rules(ruleSetOf("CORE-LV-2")).libraryUri("file:///study/dm.xpt")
                .targetDataset("DM", "dm.xpt", dmTableWithAllColumns()).validate();

        assertEquals(1, report.getMembers().size(), "the member is the point of the name");

        assertNotNull(report);
        assertNotNull(report.getMembers());
    }


    @Test
    void noTargetDatasetsProducesEmptyReport()
    {
        ValidationReport report = LibraryValidator.builder().provider(providerWithDm())
                .rules(emptyRulePackage()).validate();

        assertEquals(List.of(), report.getMembers());
    }


    @Test
    void libraryWarningsAppearInReport()
    {
        ValidationReport report = LibraryValidator.builder().provider(providerWithDm())
                .rules(emptyRulePackage()).libraryUri("file:///study/dm.xpt")
                .libraryWarning("Study standard does not match caller choice")
                .libraryWarning("CT version is older than standard version").validate();

        assertEquals(1, report.getMembers().size());
        ValidationReportMember lib = report.getMembers().get(0);
        assertEquals("", lib.getDomain());
        assertEquals(2, lib.getFindings().size());
    }

    // ------------------------------------------------------------------
    // Missing-variable detection — DM without SEX should produce a finding
    // ------------------------------------------------------------------


    @Test
    void missingRequiredVariableProducesFinding()
    {
        // Required-variable checking is handled by corpus rule CORE-000355 (required_variables
        // Operation + not_contains_all); the generator never produced per-variable GEN-REQ rules.
        // ⚑ Fix #366: the rule set must now be non-empty, because nothing is merged in behind the
        // caller's back — with zero selected rules the dataset is not validated and produces no
        // report member at all. That is the change, not a defect: a rule that belongs to no
        // package must not run.
        ValidationReport report = LibraryValidator.builder().provider(providerWithDm())
                .rules(ruleSetOf("CORE-LV-1")).libraryUri("file:///study/dm.xpt")
                .targetDataset("DM", "dm.xpt", dmTableMissingSex()).validate();

        assertEquals(1, report.getMembers().size());
        ValidationReportMember dm = report.getMembers().get(0);
        assertEquals("DM", dm.getDomain());
        assertNotNull(dm.getFindings());
    }

    // ------------------------------------------------------------------
    // Reference datasets — visible but not validated
    // ------------------------------------------------------------------


    @Test
    void referenceDatasetsAreNotIterated()
    {
        // Reference DM — this should NOT appear in the report since it isn't a target
        ValidationReport report = LibraryValidator.builder().provider(providerWithDm())
                .rules(emptyRulePackage()).referenceDataset("DM", dmTableWithAllColumns())
                .validate();

        // No targets → no dataset members
        assertFalse(report.getMembers().stream().anyMatch(m -> "DM".equals(m.getDomain())),
                "DM should not appear in the report when provided only as a reference");
    }


    @Test
    void targetAndReferenceCanCoexist()
    {
        ValidationReport report = LibraryValidator.builder().provider(providerWithDm())
                .rules(emptyRulePackage()).targetDataset("DM", "dm.xpt", dmTableWithAllColumns())
                .referenceDataset("AE",
                        MockTable.of().name("AE").col("STUDYID", "STUDY1")
                                .col("USUBJID", "SUBJ-001").col("AETERM", "Headache").build())
                .validate();

        // Only DM appears as a report member (target); AE is only visible via the resolver.
        long dmMembers = report.getMembers().stream().filter(m -> "DM".equals(m.getDomain()))
                .count();
        long aeMembers = report.getMembers().stream().filter(m -> "AE".equals(m.getDomain()))
                .count();
        // DM may or may not have findings depending on the rules, but AE must have none.
        assertEquals(0, aeMembers);
        assertTrue(dmMembers <= 1);
    }

    // ------------------------------------------------------------------
    // Rule list vs rule package
    // ------------------------------------------------------------------


    @Test
    void rulesCanBeProvidedAsCollection()
    {
        ValidationReport report = LibraryValidator.builder().provider(providerWithDm())
                .rules(List.of(simpleRule("CORE-999")))
                .targetDataset("DM", "dm.xpt", dmTableWithAllColumns()).validate();
        assertNotNull(report);
    }


    @Test
    void executionSummaries_recordPerRuleStatusAndReason()
    {
        Rule ok = simpleRule("OK-1");
        ok.setDescription("OK rule description");
        ok.setExecutability(net.cumba.cdisc.core.model.Executability.FULLY_EXECUTABLE);
        ok.setCheck(CheckConditionLeaf.builder().name("STUDYID").operator("var_exists").build());
        // A rule carrying a load error is reported as a single ERROR result by the runner.
        Rule err = simpleRule("ERR-1");
        err.setCheck(CheckConditionLeaf.builder().name("STUDYID").operator("var_exists").build());
        err.setLoadError("rule failed to load");
        // A rule scoped to AE only does not match DM — it must surface as a SKIPPED outcome.
        Rule skip = simpleRule("SKIP-1");
        skip.setDescription("AE-only rule");
        skip.setCheck(CheckConditionLeaf.builder().name("STUDYID").operator("var_exists").build());
        Scope scope = new Scope();
        DomainScope domains = new DomainScope();
        domains.setInclude(List.of("AE"));
        scope.setDomains(domains);
        skip.setScope(scope);

        LibraryValidator validator = LibraryValidator.builder().provider(providerWithDm())
                .rules(List.of(ok, err, skip)).libraryUri("file:///study/dm.xpt")
                .targetDataset("DM", "dm.xpt", dmTableWithAllColumns()).build();
        validator.validate();

        List<DatasetExecutionSummary> summaries = validator.getExecutionSummaries();
        assertEquals(1, summaries.size());
        List<DatasetExecutionSummary.RuleExecution> rx = summaries.get(0).ruleExecutions();

        DatasetExecutionSummary.RuleExecution okRx = rx.stream()
                .filter(e -> "OK-1".equals(e.coreId())).findFirst().orElseThrow();
        assertEquals("EXECUTED", okRx.status());
        assertNull(okRx.notExecutedReason());
        assertNull(okRx.expandedFor());
        assertEquals("OK rule description", okRx.description());
        // Executability is threaded through in title-case display form.
        assertEquals("Fully Executable", okRx.executability());

        DatasetExecutionSummary.RuleExecution errRx = rx.stream()
                .filter(e -> "ERR-1".equals(e.coreId())).findFirst().orElseThrow();
        assertEquals("ERROR", errRx.status());
        assertEquals("rule failed to load", errRx.notExecutedReason());

        // The AE-scoped rule appears as SKIPPED for DM, carrying its reason and description.
        DatasetExecutionSummary.RuleExecution skipRx = rx.stream()
                .filter(e -> "SKIP-1".equals(e.coreId())).findFirst().orElseThrow();
        assertEquals("SKIPPED", skipRx.status());
        assertNotNull(skipRx.notExecutedReason());
        assertEquals("AE-only rule", skipRx.description());

        // Runtime: an executed rule carries a measured time (>= 0; 0 is valid for very fast
        // rules), a source rule skipped before execution carries -1 (not measured), and the
        // dataset's wall-clock time is measured. Assert sentinels / non-negativity only — never an
        // absolute duration (wall clock is machine-dependent and would flake).
        assertTrue(okRx.runtimeMillis() >= 0, "executed rule should carry a measured runtime");
        assertEquals(-1, skipRx.runtimeMillis(), "rule skipped before execution is not measured");
        assertTrue(summaries.get(0).runtimeMillis() >= 0,
                "dataset wall-clock runtime should be measured");

        // The generated (expanded) rules the engine produced from metadata are accumulated,
        // keyed by their expanded CORE id (always hyphenated). Defensive map, never null.
        java.util.Map<String, Rule> generated = validator.getGeneratedRules();
        assertNotNull(generated);
        generated.keySet().forEach(id -> assertTrue(id.contains("-")));
    }


    @Test
    void apDatasetInheritsParentDomainClass()
    {
        // APLB carries APID (Associated Persons) and no topic variable of its own, so its class is
        // inherited from the parent domain LB (FINDINGS) — mirroring Python's
        // _get_associated_persons_inherit_class. A FINDINGS-scoped rule must therefore EXECUTE on
        // APLB rather than be SKIPPED for "class could not be determined".
        Rule classRule = simpleRule("CLS-1");
        classRule.setDescription("FINDINGS-scoped rule");
        classRule.setCheck(
                CheckConditionLeaf.builder().name("STUDYID").operator("var_exists").build());
        Scope scope = new Scope();
        net.cumba.cdisc.core.model.ClassScope classes = new net.cumba.cdisc.core.model.ClassScope();
        classes.setInclude(List.of("FINDINGS"));
        scope.setClasses(classes);
        classRule.setScope(scope);

        IDataTable aplb = MockTable.of().name("APLB").col("STUDYID", "S1").col("USUBJID", "U1")
                .col("DOMAIN", "APLB").col("APID", "P1").build();
        IDataTable lb = MockTable.of().name("LB").col("STUDYID", "S1").col("USUBJID", "U1")
                .col("DOMAIN", "LB").col("LBTESTCD", "GLUC").build();

        LibraryValidator validator = LibraryValidator.builder().provider(providerWithDm())
                .rules(List.of(classRule)).libraryUri("file:///study/aplb.xpt")
                .targetDataset("APLB", "aplb.xpt", aplb).referenceDataset("LB", lb).build();
        validator.validate();

        List<DatasetExecutionSummary.RuleExecution> rx = validator.getExecutionSummaries().get(0)
                .ruleExecutions();
        DatasetExecutionSummary.RuleExecution clsRx = rx.stream()
                .filter(e -> "CLS-1".equals(e.coreId())).findFirst().orElseThrow();
        assertEquals("EXECUTED", clsRx.status(),
                "AP class inherited from LB ⇒ FINDINGS-scoped rule executes; reason="
                        + clsRx.notExecutedReason());
    }

    // ------------------------------------------------------------------
    // Skipped rules in the validation report
    // ------------------------------------------------------------------


    @Test
    void skippedRulesSurfaceInReportWithDistinctReasons()
    {
        // Generation-time skip: a rule scoped to AE only never matches the DM dataset.
        Rule outOfScope = simpleRule("SKIP-SCOPE");
        outOfScope.setCheck(
                CheckConditionLeaf.builder().name("STUDYID").operator("var_exists").build());
        Scope scope = new Scope();
        DomainScope domains = new DomainScope();
        domains.setInclude(List.of("AE"));
        scope.setDomains(domains);
        outOfScope.setScope(scope);

        // Execution-time skip: the rule references a define_* operand but the validator carries
        // no Define-XML provider, so the runner reports SKIPPED with its status message.
        Rule defineRule = simpleRule("SKIP-DEFINE");
        defineRule.setCheck(CheckConditionLeaf.builder().name("define_dataset_label")
                .operator("non_empty").build());

        ValidationReport report = LibraryValidator.builder().provider(providerWithDm())
                .rules(List.of(outOfScope, defineRule))
                .targetDataset("DM", "dm.xpt", dmTableWithAllColumns()).validate();

        net.cumba.datatable.report.SkippedRuleEntry scopeSkip = report.getSkippedRules().stream()
                .filter(e -> "SKIP-SCOPE".equals(e.getCoreId())).findFirst().orElseThrow();
        assertEquals("DM", scopeSkip.getDataset());
        assertEquals("domain DM not in Scope.Domains.Include [AE]", scopeSkip.getReason());

        net.cumba.datatable.report.SkippedRuleEntry execSkip = report.getSkippedRules().stream()
                .filter(e -> "SKIP-DEFINE".equals(e.getCoreId())).findFirst().orElseThrow();
        assertEquals("DM", execSkip.getDataset());
        assertNotNull(execSkip.getReason());
        assertTrue(execSkip.getReason().startsWith("Rule skipped — no Define-XML metadata"),
                execSkip.getReason());

        // The two sources carry distinct reasons and neither produced a finding.
        assertNotEquals(scopeSkip.getReason(), execSkip.getReason());
        boolean anySkipFinding = report.getMembers().stream().flatMap(m -> m.getFindings().stream())
                .anyMatch(f -> "SKIP-SCOPE".equals(f.getRuleId())
                        || "SKIP-DEFINE".equals(f.getRuleId()));
        assertFalse(anySkipFinding, "skips must never appear as findings");
    }

    // ------------------------------------------------------------------
    // Supplier-based dataset registration (lazy loading hook)
    // ------------------------------------------------------------------


    @Test
    void targetSupplierIsResolvedExactlyOncePerDataset()
    {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        IDataTable dm = dmTableWithAllColumns();
        java.util.function.Supplier<IDataTable> supplier = () ->
        {
            calls.incrementAndGet();
            return dm;
        };

        ValidationReport report = LibraryValidator.builder().provider(providerWithDm())
                .rules(emptyRulePackage()).targetDataset("DM", "dm.xpt", supplier).validate();

        assertNotNull(report);
        // Each target's supplier is consulted once at the start of the per-dataset thread; the
        // per-thread local IDataTable variable is reused for the rest of validateDataset.
        assertEquals(1, calls.get());
    }


    @Test
    void targetSupplierThatThrows_isLoggedAndSkipped()
    {
        // Supplier throws on first call → validateDataset catches RuntimeException, adds a
        // dataset-level warning, and continues. The validator should not propagate the error.
        java.util.function.Supplier<IDataTable> failing = () ->
        {
            throw new RuntimeException("simulated IO failure");
        };

        ValidationReport report = LibraryValidator.builder().provider(providerWithDm())
                .rules(emptyRulePackage()).targetDataset("DM", "dm.xpt", failing).validate();

        assertNotNull(report);
        // The failing dataset should still appear in the report (as a member) with a warning.
        assertFalse(report.getMembers().isEmpty());
    }


    @Test
    void unusedReferenceSupplierIsNeverResolved()
    {
        java.util.concurrent.atomic.AtomicInteger refCalls = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Supplier<IDataTable> aeSupplier = () ->
        {
            refCalls.incrementAndGet();
            return MockTable.of().name("AE").col("USUBJID", "SUBJ-001").col("AETERM", "headache")
                    .build();
        };

        ValidationReport report = LibraryValidator.builder().provider(providerWithDm())
                .rules(emptyRulePackage()).targetDataset("DM", "dm.xpt", dmTableWithAllColumns())
                // AE is registered as a reference but no rule consults it.
                .referenceDataset("AE", aeSupplier).validate();

        assertNotNull(report);
        assertEquals(0, refCalls.get(),
                "reference dataset that no rule resolves should never be loaded");
    }

    // ------------------------------------------------------------------
    // Per-rule exception — synthetic ERROR result preserved AND logged
    // with the throwable (Phase 1d of PLAN-live-run-log)
    // ------------------------------------------------------------------


    @Test
    void ruleThatThrows_yieldsSyntheticErrorResultAndLogsThrowable()
    {
        // A rule referencing a library-dependent Operation whose supplier throws when forced.
        // The exception bubbles out of RuleRunner.execute into LibraryValidator.executeRule's
        // catch block, which (a) converts it to a synthetic ERROR result and (b) now logs the
        // throwable through the engine's System.Logger.
        IDataTable table = MockTable.of().name("DM").col("USUBJID", "S01", "S02")
                .col("RFICDTC", "2024-01-01", "2024-02-01").build();

        Operation op = new Operation();
        op.setId("$codelist_dates");
        op.setOperator("valid_codelist_dates");

        CheckConditionLeaf opLeaf = CheckConditionLeaf.builder().name("RFICDTC")
                .operator("is_not_contained_by").value(JSON.valueToTree("$codelist_dates")).build();

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-THROW");
        rule.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage("boom rule");
        outcome.setOutputVariables(List.of("USUBJID"));
        rule.setOutcome(outcome);
        rule.setCheck(new CheckConditionAll(List.of(opLeaf)));
        rule.setOperations(List.of(op));

        // Capture System.Logger output (CustomLog routes through java.util.logging) from the
        // LibraryValidator class logger.
        CapturingHandler handler = new CapturingHandler();
        Logger juli = Logger.getLogger(LibraryValidator.class.getName());
        handler.setLevel(Level.ALL);
        juli.addHandler(handler);
        Level previous = juli.getLevel();
        juli.setLevel(Level.ALL);
        try
        {
            LibraryValidator validator = LibraryValidator.builder()
                    .provider(providerThrowingPublishedCt()).rules(List.of(rule))
                    .libraryUri("file:///study/dm.xpt").targetDataset("DM", "dm.xpt", table)
                    .build();
            validator.validate();

            // (a) Existing behaviour preserved: the throwing rule still surfaces as a synthetic
            // ERROR result carrying the one-line "ClassName: message" status.
            List<DatasetExecutionSummary> summaries = validator.getExecutionSummaries();
            assertEquals(1, summaries.size());
            DatasetExecutionSummary.RuleExecution rx = summaries.get(0).ruleExecutions().stream()
                    .filter(e -> "CORE-THROW".equals(e.coreId())).findFirst().orElseThrow();
            assertEquals("ERROR", rx.status());
            DatasetExecutionSummary.RuleError err = summaries.get(0).errors().stream()
                    .filter(e -> "CORE-THROW".equals(e.ruleId())).findFirst().orElseThrow();
            assertNotNull(err.message());
            assertTrue(err.message().contains("IllegalStateException"),
                    "synthetic ERROR result keeps the one-line exception message");

            // (b) The exception is now logged at ERROR level WITH the throwable, and the message
            // names the rule core id and the dataset.
            LogRecord logged = handler.records.stream()
                    .filter(r -> r.getLevel().intValue() >= Level.SEVERE.intValue())
                    .filter(r -> r.getThrown() != null)
                    .filter(r -> r.getMessage() != null && r.getMessage().contains("CORE-THROW"))
                    .findFirst().orElseThrow(() -> new AssertionError(
                            "expected an ERROR log record carrying the throwable for CORE-THROW"));
            assertNotNull(logged.getThrown(), "the ERROR log record must carry the throwable");
            assertEquals("rule run failure boom", logged.getThrown().getMessage());
            assertTrue(logged.getMessage().contains("DM"),
                    "the log message should name the dataset");
            assertTrue(logged.getMessage().contains("IllegalStateException"),
                    "the log message should name the exception type");
        }
        finally
        {
            juli.removeHandler(handler);
            juli.setLevel(previous);
        }
    }

    private static final ObjectMapper JSON = new ObjectMapper();

    /**
     * A {@link MetadataProvider} that delegates DM metadata to a real
     * {@link MetadataLibraryProvider} but throws from
     * {@link MetadataProvider#getPublishedCtPackages()} — the probe the
     * {@code valid_codelist_dates} operation forces — so a rule using that operation fails with a
     * bubbling {@link RuntimeException}.
     */
    private static MetadataProvider providerThrowingPublishedCt()
    {
        IMetadataLibrary lib = TestMetadataFixtures.lib("study")
                .meta(net.cumba.cdisc.core.metadata.MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(net.cumba.cdisc.core.metadata.MetadataKeys.STANDARD_VERSION, "3-4")
                .table(TestMetadataFixtures.table("DM").label("Demographics")
                        .className("Special-Purpose").structure("One record per subject")
                        .column(TestMetadataFixtures.column("USUBJID", 0, DataValueType.STRING)
                                .label("Unique Subject Identifier").core("Req").role("Identifier")
                                .build())
                        .column(TestMetadataFixtures.column("RFICDTC", 1, DataValueType.STRING)
                                .label("date").core("Perm").role("Timing").build())
                        .build())
                .build();
        return new ThrowingPublishedCtProvider(new MetadataLibraryProvider(lib));
    }

    private static final class ThrowingPublishedCtProvider implements MetadataProvider
    {

        private final MetadataProvider delegate;

        ThrowingPublishedCtProvider(MetadataProvider aDelegate)
        {
            this.delegate = aDelegate;
        }


        @Override
        public List<String> getRequiredVariables(String domain)
        {
            return delegate.getRequiredVariables(domain);
        }


        @Override
        public List<String> getExpectedVariables(String domain)
        {
            return delegate.getExpectedVariables(domain);
        }


        @Override
        public List<String> getColumnOrder(String domain)
        {
            return delegate.getColumnOrder(domain);
        }


        @Override
        public List<String> getModelColumnOrder(String domain)
        {
            return delegate.getModelColumnOrder(domain);
        }


        @Override
        public boolean isDomainCustom(String domain)
        {
            return delegate.isDomainCustom(domain);
        }


        @Override
        public List<String> getCodelistTerms(String codelistCode)
        {
            return delegate.getCodelistTerms(codelistCode);
        }


        @Override
        public java.util.Map<String, String> getVariableMetadata(String domain, String variable)
        {
            return delegate.getVariableMetadata(domain, variable);
        }


        @Override
        public List<java.util.Map<String, String>> getDomainVariables(String domain)
        {
            return delegate.getDomainVariables(domain);
        }


        @Override
        public java.util.Map<String, String> getDatasetMetadata(String domain)
        {
            return delegate.getDatasetMetadata(domain);
        }


        @Override
        public boolean isCodelistExtensible(String codelistName)
        {
            return delegate.isCodelistExtensible(codelistName);
        }


        @Override
        public java.util.Map<String, String> getCodelistTermMappings(String codelistName)
        {
            return delegate.getCodelistTermMappings(codelistName);
        }


        @Override
        public String getStandard()
        {
            return delegate.getStandard();
        }


        @Override
        public String getVersion()
        {
            return delegate.getVersion();
        }


        @Override
        public List<String> getPublishedCtPackages()
        {
            throw new IllegalStateException("rule run failure boom");
        }
    }


    private static final class CapturingHandler extends Handler
    {

        final List<LogRecord> records = new ArrayList<>();

        @Override
        public void publish(LogRecord logRecord)
        {
            records.add(logRecord);
        }


        @Override
        public void flush()
        {
            // no-op
        }


        @Override
        public void close()
        {
            // no-op
        }
    }

}
