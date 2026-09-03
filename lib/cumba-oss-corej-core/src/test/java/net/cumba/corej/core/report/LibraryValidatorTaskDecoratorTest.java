package net.cumba.corej.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.UnaryOperator;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 isolation guarantee: a {@code taskDecorator} supplied to {@link LibraryValidator} must
 * run on the <em>submitting</em> thread and re-bind its captured context onto the worker that
 * executes each task — across <strong>both</strong> async submission points (the virtual-thread
 * dataset fan-out and the fixed-pool rule-cohort fan-out), including the nested case where a
 * dataset worker submits rule-cohort tasks.
 *
 * <p>
 * The proof uses a test-local {@link ThreadLocal} marker. The decorator snapshots the marker on the
 * submitting thread and re-installs it on the worker for the duration of the task; each task
 * records the marker it observed. With the marker set on the test thread before {@code validate()},
 * every observed marker must equal that known value — proving execution-time binding propagated
 * correctly across both pools and through the dataset→rule-pool nesting.
 */
class LibraryValidatorTaskDecoratorTest
{

    private static final ThreadLocal<String> MARKER = new ThreadLocal<>();

    private static final String KNOWN = "run-42";

    /**
     * A decorator mirroring the REST log-capture sink propagator: capture the marker on the
     * submitting thread at apply-time, then set/restore it around the task on the worker thread.
     */
    private static final class MarkerPropagator implements UnaryOperator<Runnable>
    {

        @Override
        public Runnable apply(Runnable task)
        {
            String captured = MARKER.get();
            return () ->
            {
                String prev = MARKER.get();
                if (captured != null)
                {
                    MARKER.set(captured);
                }
                else
                {
                    MARKER.remove();
                }
                try
                {
                    task.run();
                }
                finally
                {
                    if (prev != null)
                    {
                        MARKER.set(prev);
                    }
                    else
                    {
                        MARKER.remove();
                    }
                }
            };
        }
    }

    @Test
    void decorator_rebindsMarker_onBothDatasetAndRulePoolWorkers()
    {
        Set<String> datasetObserved = ConcurrentHashMap.newKeySet();
        Set<String> ruleObserved = ConcurrentHashMap.newKeySet();
        AtomicInteger datasetTasks = new AtomicInteger();
        AtomicInteger ruleTasks = new AtomicInteger();

        // Record the marker seen on the dataset worker (the supplyAsync body) and on the rule-pool
        // worker (the runtimeListener fires from inside the cohort task).
        LibraryValidator.RuntimeListener ruleProbe = _ ->
        {
            ruleObserved.add(String.valueOf(MARKER.get()));
            ruleTasks.incrementAndGet();
        };
        LibraryValidator.DatasetListener datasetProbe = (_, _, _, _) ->
        {
            datasetObserved.add(String.valueOf(MARKER.get()));
            datasetTasks.incrementAndGet();
        };

        int n = Math.min(4, Runtime.getRuntime().availableProcessors());

        MARKER.set(KNOWN);
        try
        {
            ValidationReport report = buildValidator().sequential(false).ruleThreads(Math.max(2, n))
                    .taskDecorator(new MarkerPropagator()).runtimeListener(ruleProbe)
                    .datasetListener(datasetProbe).validate();
            assertNotNull(report);
        }
        finally
        {
            MARKER.remove();
        }

        // The dataset fan-out (virtual threads) ran at least one task and saw the bound marker.
        assertTrue(datasetTasks.get() >= 2, "expected >= 2 dataset tasks, got " + datasetTasks);
        assertEquals(Set.of(KNOWN), datasetObserved,
                "dataset workers must observe the decorator-bound marker only");

        // The rule-cohort fan-out (fixed pool) ran tasks, nested inside the dataset worker, and the
        // same marker propagated through the nesting.
        assertTrue(ruleTasks.get() >= 1, "expected >= 1 rule task, got " + ruleTasks);
        assertEquals(Set.of(KNOWN), ruleObserved,
                "rule-cohort workers must observe the decorator-bound marker only (nested)");
    }


    @Test
    void identityDefault_leavesBehaviourUnchanged()
    {
        Set<String> datasetObserved = ConcurrentHashMap.newKeySet();
        Set<String> ruleObserved = ConcurrentHashMap.newKeySet();

        LibraryValidator.RuntimeListener ruleProbe = _ -> ruleObserved
                .add(String.valueOf(MARKER.get()));
        LibraryValidator.DatasetListener datasetProbe = (_, _, _, _) -> datasetObserved
                .add(String.valueOf(MARKER.get()));

        int n = Math.min(4, Runtime.getRuntime().availableProcessors());

        // No taskDecorator() call -> default UnaryOperator.identity(). The marker set on the test
        // thread is NOT propagated to workers (no inheritance for the fixed pool; virtual threads
        // do not inherit thread-locals), so workers observe "null".
        MARKER.set(KNOWN);
        try
        {
            ValidationReport report = buildValidator().sequential(false).ruleThreads(Math.max(2, n))
                    .runtimeListener(ruleProbe).datasetListener(datasetProbe).validate();
            assertNotNull(report);
        }
        finally
        {
            MARKER.remove();
        }

        // Identity is a true no-op: workers do not see the test thread's marker.
        assertFalse(datasetObserved.contains(KNOWN),
                "identity default must not propagate the marker to dataset workers");
        assertFalse(ruleObserved.contains(KNOWN),
                "identity default must not propagate the marker to rule workers");
    }

    // ------------------------------------------------------------------
    // Fixture: two datasets so the virtual-thread fan-out fires, and four selected rules in TWO
    // Check shapes so RuleCohortGrouper forms more than one cohort and the rule-cohort pool submits
    // more than one task per dataset at ruleThreads > 1.
    // ⚑ Before Fix #366 the built-in templates supplied those rules from metadata; nothing is
    // merged in behind the caller's back any more, so the fixture has to select them. One Check
    // shape would collapse to a single cohort and leave the nesting proof at its floor.
    // ------------------------------------------------------------------


    private static LibraryValidator.Builder buildValidator()
    {
        MetadataProvider provider = providerWithTwoDomains();
        // ⚑ Fix #366: four selected rules, not an empty package. The rule-cohort fan-out this
        // class exists to probe only runs if there are rules to run, and the generator no longer
        // supplies any of its own — with an empty package `ruleTasks` is 0 and the nesting half of
        // the proof never executes.
        return LibraryValidator.builder().provider(provider)
                .rules(ruleSetOf("CORE-TD-1", "CORE-TD-2", "CORE-TD-3", "CORE-TD-4"))
                .libraryUri("file:///study/").targetDataset("DM", "dm.xpt", dmTable())
                .targetDataset("AE", "ae.xpt", aeTable());
    }


    private static MetadataProvider providerWithTwoDomains()
    {
        IMetadataLibrary lib = TestMetadataFixtures.lib("study")
                .meta(MetadataKeys.STANDARD_NAME, "sdtmig")
                .meta(MetadataKeys.STANDARD_VERSION, "3-4")
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
                .table(TestMetadataFixtures.table("AE").label("Adverse Events").className("Events")
                        .structure("One record per event per subject")
                        .column(TestMetadataFixtures.column("STUDYID", 0, DataValueType.STRING)
                                .label("Study Identifier").core("Req").role("Identifier").build())
                        .column(TestMetadataFixtures.column("USUBJID", 1, DataValueType.STRING)
                                .label("Unique Subject Identifier").core("Req").role("Identifier")
                                .build())
                        .column(TestMetadataFixtures.column("AETERM", 2, DataValueType.STRING)
                                .label("Reported Term for the Adverse Event").core("Req")
                                .role("Topic").build())
                        .build())
                .build();
        return new MetadataLibraryProvider(lib);
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
            // Alternate the operator, not just the column: the cohort grouper keys on the Check
            // shape, so four rules of one shape would be ONE cohort and ONE pool task.
            rule.setCheck(CheckConditionLeaf.builder().name(columns[i % columns.length])
                    .operator(i++ % 2 == 0 ? "empty" : "non_empty").build());
            Outcome outcome = new Outcome();
            outcome.setMessage(coreId + " fired");
            rule.setOutcome(outcome);
            rules.put(coreId, rule);
        }
        pkg.setRules(rules);
        return pkg;
    }


    private static IDataTable dmTable()
    {
        return MockTable.of().name("DM").col("STUDYID", "STUDY1", "STUDY1", "STUDY1", "STUDY1")
                .col("USUBJID", "SUBJ-001", "SUBJ-002", "SUBJ-003", "SUBJ-004")
                .col("SEX", "M", "F", "U", "M").build();
    }


    private static IDataTable aeTable()
    {
        return MockTable.of().name("AE").col("STUDYID", "STUDY1", "STUDY1", "STUDY1", "STUDY1")
                .col("USUBJID", "SUBJ-001", "SUBJ-002", "SUBJ-003", "SUBJ-004")
                .col("AETERM", "HEADACHE", "NAUSEA", "FATIGUE", "RASH").build();
    }
}
