package net.cumba.cdisc.core.report;

import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;
import lombok.CustomLog;
import net.cumba.cdisc.core.exec.AbsentDatasetSkip;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleExecutionStatus;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.exec.StudyRuleClassifier;
import net.cumba.cdisc.core.gen.GeneratedRuleInfo;
import net.cumba.cdisc.core.gen.GeneratedRulePackage;
import net.cumba.cdisc.core.gen.RuleCategory;
import net.cumba.cdisc.core.gen.RuleGenerator;
import net.cumba.cdisc.core.gen.SkippedSourceRule;
import net.cumba.cdisc.core.metadata.RuntimeDictionaryProvider;
import net.cumba.cdisc.core.metadata.VlmResolver;
import net.cumba.cdisc.core.model.Executability;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.cdisc.core.run.DatasetExecutionSummary;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.jspecify.annotations.Nullable;

/**
 * Orchestrator that runs the rule engine over the datasets of a single library and produces a
 * {@link ValidationReport}.
 *
 * <p>
 * Each library is scoped to exactly one CDISC standard (SDTM or ADaM). When validating a library
 * that needs cross-reference access to another library (e.g. ADaM checks that join with SDTM.DM),
 * the other library's datasets are added as <em>references</em> — visible to the
 * {@link DatasetResolver} but never iterated as validation targets themselves.
 * </p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 *
 * ValidationReport report = LibraryValidator.builder().provider(sdtmProvider)
 *         .rules(sdtmRulePackage).libraryUri("file:///study/sdtm/define.xml")
 *         .targetDataset("DM", "dm.xpt", dmTable).targetDataset("AE", "ae.xpt", aeTable)
 *         .referenceDataset("DM", dmTable) // for ADaM cross-ref (not used here)
 *         .validate();
 * }</pre>
 *
 * <p>
 * The validator is a pure orchestrator — it does not fetch data, talk to any manager, or know about
 * file formats. The caller is responsible for loading datasets and passing them in.
 * </p>
 *
 * <p>
 * {@link #validate()} is synchronous from the caller's perspective but internally validates
 * datasets in parallel using virtual threads. The report preserves the original dataset insertion
 * order regardless of which dataset finishes first.
 * </p>
 */
@CustomLog
public final class LibraryValidator
{

    private static final String PARAM_DOMAIN = "domain";

    private static final String PARAM_TABLE_SUPPLIER = "tableSupplier";

    /**
     * Synthetic dataset/domain label carried by the single representative finding of a
     * {@code Sensitivity=Study} rule after the per-dataset results are collapsed. Mirrors the
     * Python reference engine's {@code dataset="STUDY"} / {@code {"study": [...]}} relabel — a
     * study rule yields exactly one finding for the whole study rather than one per dataset.
     */
    private static final String STUDY_DATASET = "STUDY";

    /**
     * Kill-switch for the per-dataset expression-result cache
     * ({@code plans/PLAN-dataset-expression-cache.md}). When
     * {@code -Dcorej.exprCache.disabled=true} the per-dataset cache is not created ({@code null} is
     * threaded through, which is behaviour-identical to the pre-cache engine) — a production safety
     * valve and the A/B toggle for the Phase-3 benchmark.
     */
    private static final boolean EXPR_CACHE_DISABLED = Boolean
            .getBoolean("corej.exprCache.disabled");

    /**
     * System property name of the study-anchor kill-switch; see {@link #studyAnchorPassEnabled}.
     */
    private static final String STUDY_ANCHOR_PASS_PROPERTY = "corej.studyAnchorPass";

    /**
     * Whether the study-anchor pass is enabled. Default <b>on</b>: an anchor-eligible
     * {@code Sensitivity: Study} rule (see {@link StudyRuleClassifier}) runs once against a
     * synthetic study anchor instead of once per dataset. Setting
     * {@code -Dcorej.studyAnchorPass=false} routes every study rule back through the per-dataset
     * path and the cross-dataset collapse, which produces the same verdicts — a production safety
     * valve, and the A/B toggle the equivalence test drives.
     *
     * <p>
     * Read per {@link #validate()} rather than cached in a {@code static final}: a latched constant
     * cannot be flipped once the class is loaded, which would make the safety valve useless to an
     * operator diagnosing a live run — and would silently reduce the equivalence test to comparing
     * the anchor path against itself.
     * </p>
     *
     * @return {@code true} unless the property is explicitly set to {@code false}
     */
    private static boolean studyAnchorPassEnabled()
    {
        return !"false".equalsIgnoreCase(System.getProperty(STUDY_ANCHOR_PASS_PROPERTY));
    }

    /**
     * Callback fired after every individual rule execution. Carries enough context to write a
     * per-(dataset, rule) runtime line to a profiling file.
     *
     * <p>
     * In parallel-dataset mode the callback may be invoked concurrently from any virtual thread;
     * implementations must be thread-safe.
     */
    @FunctionalInterface
    public interface RuntimeListener
    {

        void onRuleExecuted(RuntimeEntry entry);
    }


    /**
     * Callback fired once per target dataset as it finishes validating, from inside
     * {@link #validate()}. In parallel-dataset mode (the default) it is invoked concurrently from
     * worker threads in completion order; in {@link Builder#sequential(boolean) sequential} mode it
     * is invoked on the calling thread, once per dataset in the validator's iteration order. Either
     * way {@code processed} is a strict 1..N completion count. Implementations must be thread-safe.
     */
    @FunctionalInterface
    public interface DatasetListener
    {

        /**
         * Called when one target dataset has finished validating.
         *
         * @param processed
         *            1-based count of datasets finished so far (completion order)
         * @param total
         *            total number of target datasets
         * @param domain
         *            the dataset / domain that just finished
         * @param datasetFindings
         *            violating-row count for this dataset (its contribution to the report's final
         *            finding count)
         */
        void onDatasetCompleted(int processed, int total, String domain, int datasetFindings);
    }


    /**
     * One per-rule runtime record. {@code coreId} is the rule's stable identity
     * ({@link Rule#effectiveId()}) — the CDISC CORE identifier for corpus rules, and for a wildcard
     * expansion the expanded id ({@code CG0001-AGE}), so each expansion still gets its own row.
     *
     * <p>
     * {@code elapsedMillis} measures the rule's individual wall time. With
     * {@link Builder#ruleThreads(int) ruleThreads &gt; 1} rules within a dataset run concurrently
     * and the sum of {@code elapsedMillis} across an entire run can exceed the validator's wall
     * time. For rules executed as part of a cohort (see
     * {@link net.cumba.cdisc.core.exec.CohortRunner}), the cohort's wall time is apportioned evenly
     * across its members — each member therefore reports an approximate
     * {@code cohortWallMs / cohortSize} rather than its true individual cost. The sum of cohort
     * members' {@code elapsedMillis} equals the cohort wall time. Use the validator's own elapsed
     * time when you need exact wall-clock totals.
     */
    public record RuntimeEntry(String domain, @Nullable String fileName, long rowCount,
            int columnCount, @Nullable String coreId, long elapsedMillis,
            RuleExecutionStatus status, int violationCount)
    {
    }

    private final MetadataProvider provider;

    /**
     * Sponsor Define-XML metadata (the "define" level), or {@code null} when no define is present.
     */
    private final @Nullable MetadataProvider defineProvider;

    /**
     * Per-record Define-XML value-level metadata resolver (VLM), or {@code null} when no Define-XML
     * value-level metadata is present. Carried onto the {@link EvaluationContext} for the
     * {@code vlm_*} accessors ({@code Value Check against Define XML VLM} rule type).
     */
    private final @Nullable VlmResolver vlmResolver;

    /**
     * T1 — runtime external-dictionary provider, or {@code null} when no dictionaries are supplied
     * (dictionary rules then SKIP).
     */
    private final @Nullable RuntimeDictionaryProvider dictionaryProvider;

    private final List<Rule> rules;

    private final @Nullable String libraryUri;

    private final Map<String, Dataset> targetDatasets;

    private final Map<String, Supplier<IDataTable>> referenceDatasets;

    private final List<String> libraryWarnings;

    private final boolean sequential;

    private final int ruleThreads;

    private final @Nullable RuntimeListener runtimeListener;

    private final @Nullable DatasetListener datasetListener;

    /**
     * Decorator applied to every async task before it is submitted to a parallel executor, on the
     * submitting thread. Lets a caller re-establish thread-bound context on the worker thread that
     * runs the task. Defaults to {@link UnaryOperator#identity()} (no-op).
     */
    private final UnaryOperator<Runnable> taskDecorator;

    /** Resolved per-rule findings cap passed to every rule execution; MAX_VALUE means unlimited. */
    private final int maxErrorsPerRule;

    /**
     * Resolved run <b>severity threshold</b> (Plan C §3.4) passed to every rule execution — the
     * weakest check level this run evaluates. Never {@code null}; the engine default is
     * {@code Warning}.
     */
    private final net.cumba.datatable.report.Severity severityThreshold;

    /**
     * Whether every check level {@code rule} declares sits below the run's
     * {@link #severityThreshold} — i.e. the run did not ask for this rule at all (Plan C §3.4 step
     * 2).
     *
     * <p>
     * Used to demote such a rule out of cohorting so it takes {@code RuleRunner.execute}, which
     * owns the SKIPPED-with-a-reason verdict. ⚑ Vacuous at the default threshold: every level any
     * shipped rule declares is at or above {@code Warning}.
     * </p>
     */
    private boolean belowSeverityThreshold(Rule rule)
    {
        return rule.effectiveCheckLevels().keySet().stream()
                .noneMatch(level -> level.compareTo(severityThreshold) <= 0);
    }

    /** Per-domain execution stats captured by the most recent {@link #validate()} (log-only). */
    private volatile List<DatasetExecutionSummary> executionSummaries = List.of();

    /**
     * Generated (expanded) rules that ran, keyed by their expanded CORE id (e.g.
     * {@code CG0001-AGE}). Additive accumulator surfaced via {@link #getGeneratedRules()};
     * thread-safe map because dataset validation may fan out across threads.
     */
    private final Map<String, Rule> generatedRules = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * CORE ids of rules produced by SDTM {@code --}-prefix expansion (one per domain, all sharing
     * their base rule's bare CORE id). The report writer bundles exactly these across datasets into
     * a single {@code Issue_Summary} row — Python parity. Thread-safe: populated as datasets fan
     * out.
     */
    private final Set<String> sdtmPrefixExpandedIds = java.util.concurrent.ConcurrentHashMap
            .newKeySet();

    /**
     * {@code Fix #222} — the datasets whose absence <b>this run already reports</b>, derived once
     * from the run's own rule list by {@link AbsentDatasetSkip#reportedDatasets(Collection)}: a
     * rule whose entire Check is a bare dataset-presence test covers that dataset.
     *
     * <p>
     * Derived from the <em>effective</em> rule list rather than from the package file, which is the
     * honest reading of the plan's precondition (<i>"the run already reports D's absence"</i>) and
     * strictly safer: a run may select several family packages, and an include/exclude filter can
     * drop the presence rule from a run that still executes its dependants. For the default
     * single-family unfiltered run the two readings coincide exactly. Empty ⇒ the engine behaves
     * exactly as it did before the fix.
     * </p>
     */
    private final Set<String> presenceReportedDatasets;

    /**
     * {@code Fix #218} — the datasets belonging to a CDISC standard this run does <b>not</b>
     * validate ({@code plans/PLAN-cross-standard-absence-skip.md}). Supplied by the run layer
     * ({@code StudyValidationService}) because it is a property of the <em>invocation</em>, not of
     * the rule package: {@link #presenceReportedDatasets} is package-scoped and structurally cannot
     * express a cross-standard dependency — no ADaM package reports {@code DM}, and per the owner's
     * invocation ruling none may. Empty ⇒ the engine behaves exactly as it did before the fix.
     */
    private final Set<String> crossStandardDatasets;

    private LibraryValidator(Builder aBuilder)
    {
        provider = aBuilder.provider;
        defineProvider = aBuilder.defineProvider;
        vlmResolver = aBuilder.vlmResolver;
        dictionaryProvider = aBuilder.dictionaryProvider;
        rules = List.copyOf(aBuilder.rules);
        libraryUri = aBuilder.libraryUri;
        targetDatasets = Map.copyOf(aBuilder.targetDatasets);
        referenceDatasets = Map.copyOf(aBuilder.referenceDatasets);
        libraryWarnings = List.copyOf(aBuilder.libraryWarnings);
        sequential = aBuilder.sequential;
        ruleThreads = aBuilder.ruleThreads;
        runtimeListener = aBuilder.runtimeListener;
        datasetListener = aBuilder.datasetListener;
        taskDecorator = aBuilder.taskDecorator;
        maxErrorsPerRule = net.cumba.cdisc.core.exec.EngineLimits
                .resolve(aBuilder.maxErrorsPerRule);
        severityThreshold = net.cumba.cdisc.core.exec.EngineLimits
                .resolveSeverityThreshold(aBuilder.severityThreshold);
        presenceReportedDatasets = AbsentDatasetSkip.reportedDatasets(rules);
        crossStandardDatasets = aBuilder.crossStandardDatasets;
        if (!presenceReportedDatasets.isEmpty())
        {
            LOGGER.log(Level.DEBUG,
                    "Fix #222: this run reports the absence of {0} — its dependants are silenced "
                            + "(SKIPPED) rather than flooded when one of them is missing.",
                    presenceReportedDatasets);
        }
        if (!crossStandardDatasets.isEmpty())
        {
            LOGGER.log(Level.DEBUG,
                    "Fix #218: {0} cross-standard dataset name(s) known to this run; a rule whose "
                            + "whole Check depends on one that was not supplied reports SKIPPED "
                            + "rather than a vacuous PASS.",
                    crossStandardDatasets.size());
        }
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------


    /**
     * Runs rule generation and execution against every target dataset and builds a
     * {@link ValidationReport}.
     * <p>
     * Datasets are validated in parallel using virtual threads by default. When the builder is
     * configured with {@link Builder#sequential(boolean)}, datasets are validated one after the
     * other on the calling thread instead — useful for profiling and for callers that want to
     * orchestrate parallelism at the rule level. Either way, the report preserves the original
     * dataset insertion order.
     */
    public ValidationReport validate()
    {
        ValidationReportBuilder reportBuilder = new ValidationReportBuilder()
                .libraryUri(libraryUri);

        // Emit any library-level warnings first — they are independent of datasets.
        for (String warning : libraryWarnings)
        {
            reportBuilder.libraryWarning(warning);
        }

        // Build a DatasetResolver spanning target + reference datasets.
        // This is read-only and safe for concurrent access.
        DatasetResolver resolver = buildResolver();

        // Shared join index cache — when datasets run in parallel, this lets
        // them reuse joined-side indexes (e.g., DM USUBJID index built once
        // and shared across dataset threads). Thread-safe via ConcurrentHashMap.
        // In sequential mode it still serves as a single per-run cache.
        net.cumba.cdisc.core.exec.JoinCache.SharedIndexCache sharedIndex = new net.cumba.cdisc.core.exec.JoinCache.SharedIndexCache();

        // Study-anchor pass: rules whose finding belongs to the study rather than to any dataset
        // are executed ONCE, outside the per-dataset loop, and excluded from per-dataset
        // generation so they never run N times only to be collapsed back to one. Rules that are
        // study-sensitivity but NOT anchor-eligible (their Check reads the dataset under
        // evaluation) stay on the per-dataset path and are collapsed below, so this is a fast
        // path and never a correctness gate.
        List<Rule> anchorRules = anchorEligibleRules();
        List<StudyRuleResult> anchorResults = runStudyAnchorPass(anchorRules, resolver);
        List<Rule> perDatasetRules = rulesExcluding(anchorRules);

        List<Dataset> orderedTargets = new ArrayList<>(targetDatasets.values());
        List<DatasetResult> datasetResults = new ArrayList<>(orderedTargets.size());

        // Live per-dataset progress: a thread-safe completion counter so the parallel path can
        // report "N done so far" in completion order (the report itself is still assembled in
        // insertion order below, so report content and ordering are unaffected).
        int total = orderedTargets.size();
        java.util.concurrent.atomic.AtomicInteger completed = new java.util.concurrent.atomic.AtomicInteger();

        if (sequential)
        {
            for (Dataset ds : orderedTargets)
            {
                DatasetResult result = validateDataset(ds, resolver, sharedIndex, perDatasetRules);
                datasetResults.add(result);
                fireDatasetCompleted(completed.incrementAndGet(), total, ds.domain(),
                        findingsOf(result));
            }
        }
        else
        {
            // Fan out — one virtual thread per target dataset. Virtual threads
            // are lightweight and ideal for I/O-mixed workloads; the JVM
            // schedules them across available carriers automatically.
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor())
            {
                Executor wrapped = decorated(executor);
                List<CompletableFuture<DatasetResult>> futures = orderedTargets.stream()
                        .map(ds -> CompletableFuture.supplyAsync(() ->
                        {
                            DatasetResult r = validateDataset(ds, resolver, sharedIndex,
                                    perDatasetRules);
                            // Fire on the worker thread, in completion order, before the result
                            // is joined and merged below.
                            fireDatasetCompleted(completed.incrementAndGet(), total, ds.domain(),
                                    findingsOf(r));
                            return r;
                        }, wrapped)).toList();

                // Join all futures (preserving insertion order). The merge happens single-threaded
                // below, after the study-sensitivity collapse — no synchronization needed.
                for (CompletableFuture<DatasetResult> future : futures)
                {
                    datasetResults.add(future.join());
                }
            }
        }

        // Study-sensitivity collapse (mirrors the Python reference engine's
        // _collapse_to_study_result): a Sensitivity=Study rule executes per dataset (so
        // dataset-scoped operands resolve) but yields exactly ONE representative finding for the
        // study. Collapse across datasets BEFORE the per-dataset merge so only the collapsed,
        // "STUDY"-labelled result lands in the report. Must run after every dataset result is
        // collected — it is inherently cross-dataset.
        List<StudyRuleResult> studyResults = new ArrayList<>(anchorResults);
        studyResults.addAll(collapseStudyResults(datasetResults));

        // Merge each dataset's (now study-rule-free) results into the report, then append the
        // collapsed study results labelled with the synthetic "STUDY" dataset/domain.
        for (DatasetResult result : datasetResults)
        {
            result.mergeInto(reportBuilder);
        }
        for (StudyRuleResult sr : studyResults)
        {
            // No file name: a study-level finding belongs to the study, not to whichever dataset
            // happened to be the representative. The report writers fall back to the domain when
            // the file name is absent, so the finding reads dataset=STUDY / domain=STUDY — which
            // is also what the Python reference engine emits (dataset='STUDY'). Passing
            // sr.fileName() here surfaced an arbitrary "ae.json" against a STUDY domain.
            reportBuilder.add(STUDY_DATASET, null, sr.rule(), sr.result());
        }

        ValidationReport report = reportBuilder.build();
        executionSummaries = buildExecutionSummaries(datasetResults, studyResults, report);
        return report;
    }


    /** Wrap an executor so each task is decorated on the SUBMITTING thread before it runs. */
    private Executor decorated(ExecutorService raw)
    {
        return task -> raw.execute(taskDecorator.apply(task));
    }


    private void fireDatasetCompleted(int processed, int total, String domain, int datasetFindings)
    {
        if (datasetListener != null)
        {
            datasetListener.onDatasetCompleted(processed, total, domain, datasetFindings);
        }
    }


    /**
     * This dataset's contribution to the progress finding count: the number of violating rows
     * across its rule results, using the <em>true</em> per-rule total
     * ({@link RuleExecutionResult#getViolationCount()}). Each {@code Violation} is one row. In the
     * common (uncapped) case this equals the dataset's portion of {@code countFindings}, and a
     * running sum across completed datasets converges to the report total. When a rule's findings
     * were capped (per-rule findings cap — see {@link net.cumba.cdisc.core.exec.EngineLimits}),
     * this still reports the true violation count, so it can exceed the number of finding rows
     * actually materialised into the report for that rule; the difference is surfaced by the
     * "findings capped" log line and {@link RuleExecutionResult#isTruncated()}.
     */
    private static int findingsOf(DatasetResult result)
    {
        int n = 0;
        for (DatasetResult.RuleResultEntry e : result.ruleResults)
        {
            n += e.result().getViolationCount();
        }
        return n;
    }


    /**
     * The rule set for the per-dataset loop: everything except the rules the anchor pass already
     * executed. Identity-based so a caller supplying two equal-but-distinct rule objects keeps
     * both, matching how the rest of the validator treats the list.
     *
     * @param anchorRules
     *            rules already executed by the study-anchor pass
     * @return the rules still to be generated and run per dataset
     */
    private List<Rule> rulesExcluding(List<Rule> anchorRules)
    {
        if (anchorRules.isEmpty())
        {
            return rules;
        }
        Set<Rule> excluded = java.util.Collections
                .newSetFromMap(new IdentityHashMap<>(anchorRules.size()));
        excluded.addAll(anchorRules);
        List<Rule> remaining = new ArrayList<>(rules.size() - anchorRules.size());
        for (Rule r : rules)
        {
            if (!excluded.contains(r))
            {
                remaining.add(r);
            }
        }
        return remaining;
    }


    /**
     * The study rules that may be executed once against the study anchor rather than once per
     * dataset. Empty when the anchor pass is disabled via {@code -Dcorej.studyAnchorPass=false}.
     *
     * <p>
     * A rule carrying a {@code loadError} is deliberately excluded: it must surface its ERROR
     * sentinel through the normal path rather than be quietly evaluated here.
     * </p>
     */
    private List<Rule> anchorEligibleRules()
    {
        if (!studyAnchorPassEnabled())
        {
            return List.of();
        }
        List<Rule> eligible = new ArrayList<>();
        for (Rule rule : rules)
        {
            if (rule != null && rule.getLoadError() == null
                    && StudyRuleClassifier.isAnchorEligible(rule))
            {
                eligible.add(rule);
            }
        }
        return eligible;
    }


    /**
     * Executes each anchor-eligible study rule exactly once against a synthetic one-row anchor
     * table standing in for the study.
     *
     * <p>
     * The anchor carries no columns — by construction these rules read nothing about the dataset
     * under evaluation — but the real {@link DatasetResolver} is passed through, so
     * {@code ds_exists} and friends see the true study inventory. Because the pass sits outside the
     * per-dataset loop it also fires when the study contains no analysable datasets at all, which
     * is precisely when "the DM dataset is missing" most needs saying.
     * </p>
     *
     * @param anchorRules
     *            the rules classified as anchor-eligible
     * @param resolver
     *            resolver spanning the study's target + reference datasets
     * @return one result per rule, ready to be reported under the {@code STUDY} pseudo-domain
     */
    private List<StudyRuleResult> runStudyAnchorPass(List<Rule> anchorRules,
            DatasetResolver resolver)
    {
        if (anchorRules.isEmpty())
        {
            return List.of();
        }
        IDataTable anchor = net.cumba.datatable.impl.support.OverlayDataTable.empty(STUDY_DATASET,
                "Study-level check", 1);
        List<StudyRuleResult> executed = new ArrayList<>(anchorRules.size());
        for (Rule rule : anchorRules)
        {
            // domainPrefix is null: `--` substitution is a per-dataset notion and an anchor-
            // eligible rule carries no wildcard column by construction.
            RuleExecutionResult result = executeRule(rule, anchor, resolver, null, null, null,
                    STUDY_DATASET, null);
            executed.add(new StudyRuleResult(rule, result));
        }
        // Supplying two rule packages that both carry a Core.Id (e.g. two SDTMIG versions of one
        // family) puts two distinct Rule objects with the same identity in the list, and the
        // study fact must still be reported once. Reduce with EXACTLY the collapse's rule —
        // group by effectiveId(), prefer the first non-skipped result — so the anchor path and
        // the per-dataset path cannot disagree on which duplicate represents the group. Picking a
        // different representative here (e.g. simply the first) would let a skipped copy mask a
        // firing one, losing a finding the fallback path reports.
        List<StudyRuleResult> out = pickRepresentatives(executed);
        LOGGER.log(Level.DEBUG,
                "Study-anchor pass executed {0} rule(s) once for the study, reported {1}.",
                executed.size(), out.size());
        return out;
    }


    /**
     * Study-sensitivity collapse — mirrors the Python reference engine's
     * {@code _collapse_to_study_result} ({@code rules_engine.py}). For every {@code Sensitivity=
     * Study} rule, gathers its per-dataset results across all datasets, removes them from the
     * per-dataset result lists, and returns one representative result per rule: the first
     * <em>non-skipped</em> result, or — when every dataset skipped it — the first result. The
     * caller re-adds each representative under the synthetic {@link #STUDY_DATASET} domain, so a
     * study rule produces exactly one finding for the whole study, carrying the representative's
     * messages / violations unchanged.
     *
     * <p>
     * Datasets keep their execution (insertion) order, so "first" is deterministic and matches
     * Python's iteration order over the per-dataset result dict. Rules are grouped by
     * {@link Rule#effectiveId()} — the {@code Core.Id} for file-loaded rules, the synthetic
     * {@code id} for generated ones. A rule carrying neither is left untouched (defensive: it
     * cannot be grouped, so it keeps its per-dataset results rather than silently collapsing with
     * unrelated rules).
     * </p>
     */
    private static List<StudyRuleResult> collapseStudyResults(List<DatasetResult> datasetResults)
    {
        Map<String, List<StudyRuleResult>> byRuleId = new LinkedHashMap<>();
        for (DatasetResult ds : datasetResults)
        {
            for (DatasetResult.RuleResultEntry e : ds.ruleResults)
            {
                String ruleId = studyRuleId(e.rule());
                if (ruleId != null)
                {
                    byRuleId.computeIfAbsent(ruleId, _ -> new ArrayList<>())
                            .add(new StudyRuleResult(e.rule(), e.result()));
                }
            }
            // Drop the study-rule entries from this dataset — they are re-emitted once, collapsed.
            ds.ruleResults.removeIf(e -> studyRuleId(e.rule()) != null);
        }
        List<StudyRuleResult> collapsed = new ArrayList<>(byRuleId.size());
        for (List<StudyRuleResult> candidates : byRuleId.values())
        {
            collapsed.add(representativeOf(candidates));
        }
        return collapsed;
    }


    /**
     * Groups results by {@link Rule#effectiveId()} and keeps one representative per group, using
     * the same rule as the collapse. Results carrying no identity cannot be grouped and are all
     * kept. Input order is preserved.
     */
    private static List<StudyRuleResult> pickRepresentatives(List<StudyRuleResult> results)
    {
        Map<String, List<StudyRuleResult>> byRuleId = new LinkedHashMap<>();
        List<StudyRuleResult> unidentified = new ArrayList<>();
        for (StudyRuleResult r : results)
        {
            String id = r.rule().effectiveId();
            if (id == null)
            {
                unidentified.add(r);
            }
            else
            {
                byRuleId.computeIfAbsent(id, _ -> new ArrayList<>()).add(r);
            }
        }
        if (byRuleId.size() + unidentified.size() == results.size())
        {
            // No duplicates — keep the caller's order exactly.
            return results;
        }
        List<StudyRuleResult> out = new ArrayList<>(byRuleId.size() + unidentified.size());
        for (List<StudyRuleResult> candidates : byRuleId.values())
        {
            out.add(representativeOf(candidates));
        }
        out.addAll(unidentified);
        return out;
    }


    /**
     * The representative of a study rule's results: the first <em>non-skipped</em> result, or the
     * first result when every one was skipped. Shared by the study-anchor pass and the
     * cross-dataset collapse so the two paths always agree.
     */
    private static StudyRuleResult representativeOf(List<StudyRuleResult> candidates)
    {
        for (StudyRuleResult c : candidates)
        {
            if (!c.result().isSkipped())
            {
                return c;
            }
        }
        return candidates.get(0);
    }


    /**
     * The cross-dataset-stable grouping id for a {@code Sensitivity=Study} rule, or {@code null}
     * when the rule is not study-sensitivity (or carries no id and therefore cannot be grouped).
     */
    private static @Nullable String studyRuleId(Rule rule)
    {
        return rule.getSensitivity() == Sensitivity.STUDY ? rule.effectiveId() : null;
    }

    /**
     * One study rule's representative result. Deliberately carries no source file: the finding is
     * reported against the synthetic {@link #STUDY_DATASET} domain with no file name, so the
     * dataset that happened to produce the representative never leaks into the report.
     */
    private record StudyRuleResult(Rule rule, RuleExecutionResult result)
    {
    }

    /**
     * Per-domain execution stats from the most recent {@link #validate()} (empty before it runs).
     */
    public List<DatasetExecutionSummary> getExecutionSummaries()
    {
        // Defensive copy; the stored value is already immutable so this is effectively free.
        return List.copyOf(executionSummaries);
    }


    /**
     * Generated (expanded) rules that ran, keyed by their expanded CORE id (e.g.
     * {@code CG0001-AGE}). Empty before {@link #validate()} runs or when no rules were generated.
     * Defensive copy.
     */
    public Map<String, Rule> getGeneratedRules()
    {
        return new LinkedHashMap<>(generatedRules);
    }


    /**
     * CORE ids of SDTM {@code --}-prefix expansions seen during the most recent
     * {@link #validate()}. The report writer bundles exactly these ids across datasets in
     * {@code Issue_Summary}. Empty before {@code validate()} runs or when no {@code --} rule was
     * expanded. Defensive copy.
     */
    public Set<String> getSdtmPrefixExpandedIds()
    {
        return Set.copyOf(sdtmPrefixExpandedIds);
    }


    /**
     * Assembles the per-domain {@link DatasetExecutionSummary} list from the validated dataset
     * results and the built report: {@code rulesExecuted} counts non-skipped, non-error rule
     * outcomes; errors collect ERROR-status rules plus any dataset load failure; {@code rulesTotal}
     * is the run's selected rule count; findings are read back from the report's per-domain member.
     *
     * <p>
     * {@code Sensitivity=Study} rules were collapsed out of the per-dataset {@code ruleResults} (so
     * they appear on no dataset row); a single synthetic {@code STUDY} summary row is appended so
     * each study rule is still accounted for exactly once — mirroring the Python reference engine,
     * which retains the collapsed result under the {@code "study"} key in its rules-execution
     * report.
     * </p>
     */
    private List<DatasetExecutionSummary> buildExecutionSummaries(List<DatasetResult> results,
            List<StudyRuleResult> studyResults, ValidationReport report)
    {
        int total = rules.size();
        List<DatasetExecutionSummary> out = new ArrayList<>(results.size() + 1);
        for (DatasetResult r : results)
        {
            int executed = 0;
            List<DatasetExecutionSummary.RuleError> errors = new ArrayList<>();
            List<DatasetExecutionSummary.RuleExecution> ruleExecutions = new ArrayList<>();
            for (DatasetResult.RuleResultEntry e : r.ruleResults)
            {
                RuleExecutionResult res = e.result();
                String status = res.isSkipped() ? "SKIPPED" : res.isError() ? "ERROR" : "EXECUTED";
                String reason = res.isSkipped() || res.isError() ? res.getStatusMessage() : null;
                ruleExecutions.add(new DatasetExecutionSummary.RuleExecution(coreIdOf(e.rule()),
                        e.rule().effectiveId(), status, res.getViolationCount(),
                        res.getRuntimeMillis(), expandedVariableOf(e.rule()), reason,
                        e.rule().getDescription(), executabilityOf(e.rule())));
                if (expandedVariableOf(e.rule()) != null)
                {
                    // A generated (expanded) rule that actually ran — keep the synthetic instance
                    // so
                    // it can be surfaced as the "expanded" rule definition. First write wins.
                    generatedRules.putIfAbsent(coreIdOf(e.rule()), e.rule());
                }
                if (res.isSkipped())
                {
                    continue;
                }
                if (res.isError())
                {
                    errors.add(new DatasetExecutionSummary.RuleError(coreIdOf(e.rule()),
                            res.getStatusMessage()));
                }
                else
                {
                    executed++;
                }
            }
            // Source rules dropped before generation because their scope did not match this dataset
            // (domain / class / variables) are surfaced as SKIPPED outcomes, so every selected rule
            // is accounted for per domain.
            for (SkippedSourceRule s : r.skippedSourceRules)
            {
                ruleExecutions.add(new DatasetExecutionSummary.RuleExecution(coreIdOf(s.rule()),
                        s.rule().effectiveId(), "SKIPPED", 0, -1, expandedVariableOf(s.rule()),
                        s.reason(), s.rule().getDescription(), executabilityOf(s.rule())));
            }
            for (String loadError : r.loadErrors)
            {
                errors.add(new DatasetExecutionSummary.RuleError(
                        ValidationReportBuilder.DATASET_LOAD_ERROR_RULE_ID, loadError));
            }
            out.add(new DatasetExecutionSummary(r.domain, r.fileName, executed, total,
                    findingsCountFor(report, r.domain, r.fileName), r.runtimeMillis, errors,
                    ruleExecutions));
        }
        appendStudySummary(out, studyResults, total, report);
        return List.copyOf(out);
    }


    /**
     * Appends the single synthetic {@code STUDY} {@link DatasetExecutionSummary} carrying one
     * {@link DatasetExecutionSummary.RuleExecution} per collapsed study rule, so a
     * {@code Sensitivity=Study} rule — collapsed out of every dataset's {@code ruleResults} — is
     * still accounted for exactly once in the execution log. No row is appended when there are no
     * study rules.
     */
    private void appendStudySummary(List<DatasetExecutionSummary> out,
            List<StudyRuleResult> studyResults, int total, ValidationReport report)
    {
        if (studyResults.isEmpty())
        {
            return;
        }
        int executed = 0;
        long runtimeMillis = 0L;
        List<DatasetExecutionSummary.RuleError> errors = new ArrayList<>();
        List<DatasetExecutionSummary.RuleExecution> ruleExecutions = new ArrayList<>();
        for (StudyRuleResult sr : studyResults)
        {
            Rule rule = sr.rule();
            RuleExecutionResult res = sr.result();
            String status = res.isSkipped() ? "SKIPPED" : res.isError() ? "ERROR" : "EXECUTED";
            String reason = res.isSkipped() || res.isError() ? res.getStatusMessage() : null;
            runtimeMillis += Math.max(0L, res.getRuntimeMillis());
            ruleExecutions.add(new DatasetExecutionSummary.RuleExecution(coreIdOf(rule),
                    rule.effectiveId(), status, res.getViolationCount(), res.getRuntimeMillis(),
                    expandedVariableOf(rule), reason, rule.getDescription(),
                    executabilityOf(rule)));
            if (res.isSkipped())
            {
                continue;
            }
            if (res.isError())
            {
                errors.add(new DatasetExecutionSummary.RuleError(coreIdOf(rule),
                        res.getStatusMessage()));
            }
            else
            {
                executed++;
            }
        }
        // A synthetic study-level row: not tied to a source file. Its runtime is the sum of the
        // collapsed rules' own execution times — a real, non-negative number, so the `-1`
        // "not measured" sentinel never reaches the runtime CSV for a row that did execute.
        // Findings are the collapsed STUDY-labelled members produced by validate().
        out.add(new DatasetExecutionSummary(STUDY_DATASET, null, executed, total,
                studyFindingsCount(report), runtimeMillis, errors, ruleExecutions));
    }


    /**
     * Total findings across every {@code STUDY}-labelled report member (collapsed study findings).
     */
    private static int studyFindingsCount(ValidationReport report)
    {
        int count = 0;
        for (ValidationReportMember m : report.getMembers())
        {
            if (STUDY_DATASET.equalsIgnoreCase(m.getDomain()))
            {
                count += m.getFindingsCount();
            }
        }
        return count;
    }


    /**
     * The variable a generated (expanded) rule was produced for: the segment after the last
     * {@code -} of the expanded CORE id (e.g. {@code CG0001-AGE} → {@code AGE}). Returns
     * {@code null} for non-generated rules — plain CORE ids (e.g. {@code CDISC-AD0001}) also
     * contain hyphens, so the split is only meaningful when the rule carries the
     * {@code "Generated"} status marker. CDISC variable names are hyphen-free, so the last-hyphen
     * split is unambiguous.
     */
    private static @Nullable String expandedVariableOf(Rule rule)
    {
        RuleCore core = rule.getCore();
        if (core == null || !"Generated".equals(core.getStatus()) || core.getId() == null)
        {
            return null;
        }
        String id = core.getId();
        int dash = id.lastIndexOf('-');
        return dash >= 0 && dash < id.length() - 1 ? id.substring(dash + 1) : null;
    }


    private static int findingsCountFor(ValidationReport report, @Nullable String domain,
            @Nullable String fileName)
    {
        int count = 0;
        for (ValidationReportMember m : report.getMembers())
        {
            boolean sameDomain = domain == null ? m.getDomain() == null
                    : domain.equalsIgnoreCase(m.getDomain());
            boolean sameFile = Objects.equals(fileName, m.getFileName());
            if (sameDomain && sameFile)
            {
                count += m.getFindingsCount();
            }
        }
        return count;
    }


    private static @Nullable String coreIdOf(Rule rule)
    {
        return rule.effectiveId();
    }


    /**
     * The rule's declared executability in title-case display form (e.g.
     * {@code "Fully Executable"}), or {@code null} when the rule (or its executability) is absent.
     * Uses the {@code @JsonValue} title-case form, not the lower-cased Python report form.
     */
    private static @Nullable String executabilityOf(Rule rule)
    {
        Executability e = rule != null ? rule.getExecutability() : null;
        return e != null ? e.getJsonValue() : null;
    }


    private DatasetResolver buildResolver()
    {
        Map<String, Supplier<IDataTable>> mutable = new LinkedHashMap<>();
        // Target datasets take precedence — they represent the study under test.
        for (Dataset d : targetDatasets.values())
        {
            Supplier<IDataTable> raw = d.tableSupplier;
            mutable.put(d.domain.toUpperCase(java.util.Locale.ROOT), raw);
        }
        // Reference datasets fill in for cross-reference checks.
        for (Map.Entry<String, Supplier<IDataTable>> e : referenceDatasets.entrySet())
        {
            Supplier<IDataTable> raw = e.getValue();
            mutable.putIfAbsent(e.getKey().toUpperCase(java.util.Locale.ROOT), raw);
        }
        // Freeze the lookup map before publishing it to the resolver so concurrent reads from
        // rule threads can never observe partial state. The captured reference is safe to share
        // unsynchronised because the map is immutable and the suppliers it forwards to are
        // documented to memoise (DatasetEntry, CdiscValidate.memoised — both thread-safe).
        Map<String, Supplier<IDataTable>> lookup = Map.copyOf(mutable);
        Set<String> inventory = lookup.keySet();
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                if (name == null)
                {
                    return null;
                }
                Supplier<IDataTable> s = lookup.get(name.toUpperCase(java.util.Locale.ROOT));
                return s != null ? s.get() : null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                return inventory;
            }
        };
    }


    /**
     * Validates a single dataset — generates rules, executes them, and collects results into a
     * thread-local {@link DatasetResult}. No shared mutable state is touched, making this safe for
     * concurrent invocation.
     *
     * @param aDataset
     *            the dataset to validate
     * @param aResolver
     *            the dataset resolver
     * @param aSharedIndex
     *            shared join index cache for cross-dataset index reuse (thread-safe)
     */
    private DatasetResult validateDataset(Dataset aDataset, DatasetResolver aResolver,
            net.cumba.cdisc.core.exec.JoinCache.SharedIndexCache aSharedIndex,
            List<Rule> aDatasetRules)
    {
        long startDs = System.currentTimeMillis();
        String domain = aDataset.domain;
        String fileName = aDataset.fileName;

        DatasetResult dsResult = new DatasetResult(domain, fileName);

        // First access on this dataset's per-thread context — triggers the lazy load if the
        // caller registered the dataset via the Supplier overload. Subsequent accesses to the
        // same supplier (e.g. from rules that reference the dataset domain via the resolver)
        // hit the supplier's memoised cache cheaply.
        // If the supplier throws (e.g. the source file was deleted or is corrupt), record a
        // dataset-level warning and skip — the rest of the run still proceeds. Mirrors the
        // existing rule-generation-failure handling below.
        IDataTable table;
        try
        {
            // tableSupplier is Supplier<IDataTable> (non-null element under @NullMarked), so its
            // get() is non-null. Capture the invariant explicitly for NullAway.
            table = Objects.requireNonNull(aDataset.tableSupplier.get(),
                    "tableSupplier produced a non-null table");
        }
        catch (RuntimeException e)
        {
            dsResult.addLoadError(
                    "Dataset '" + domain + "' could not be opened as a table: " + e.getMessage());
            dsResult.runtimeMillis = System.currentTimeMillis() - startDs;
            return dsResult;
        }

        // Fix #59: extract the CDISC domain code from the loaded table (DOMAIN-column-first
        // with unsplit-name fallback). The caller-supplied {@code aDataset.domain} is the
        // library member name (e.g. {@code LBHE}); the CDISC code (e.g. {@code LB}) is what
        // class lookups, rule scope matching, and `--` substitution need. The member name
        // stays authoritative for the report-side {@code DatasetResult.domain} and runtime
        // listener events constructed below.
        String cdiscDomain = net.cumba.cdisc.core.metadata.CdiscDomainResolver.cdiscDomainOf(table);
        String memberName = table.getMetaData().getName();

        // Fix #60: resolve the class via the 3-tier resolver (Define-XML / product reverse-walk /
        // custom-domain sniffer). The previous metadata-only path missed product and sniffer
        // fallbacks, so QS / LB-style datasets without explicit className metadata had every
        // class-scoped rule skipped under the strict-on-null behaviour from Fix #41.
        String className = classNameFor(memberName, cdiscDomain, table, aResolver);

        // RuleGenerator is created fresh per dataset — no shared state.
        // ⚠⚠ RuleCategory.corpusDeliveryOnly(), never the 2-arg constructor's EnumSet.allOf: this
        // is the ONLY production construction site, and it is where "no rule may fire unless it is
        // in a package the user selected" is enforced (Fix #366). The two enabled values are not
        // generators — they are the corpus delivery path; read their javadoc before touching this.
        RuleGenerator generator = new RuleGenerator(provider, null, null, provider.getVersion(),
                RuleCategory.corpusDeliveryOnly());
        generator.setStaticRules(aDatasetRules);
        generator.setDatasetResolver(aResolver);
        generator.setDomainName(cdiscDomain);
        if (className != null)
        {
            generator.setClassName(className);
        }

        GeneratedRulePackage pkg;
        try
        {
            pkg = generator.generate(table);
        }
        catch (RuntimeException e)
        {
            // If rule generation itself fails, record a library-level warning
            // and skip this dataset — no rules can run against it.
            dsResult.addWarning("Rule generation failed for " + domain + ": " + e.getMessage());
            return dsResult;
        }
        // Record source rules that did not match this dataset's scope (domain / class / variables)
        // so they appear as SKIPPED outcomes for the dataset rather than vanishing.
        dsResult.skippedSourceRules.addAll(pkg.getSkippedSourceRules());

        // Track the bare CORE ids of SDTM `--`-prefix expansions so the report writer can bundle
        // their per-domain rows back into one Issue_Summary row (the marker used to be the
        // GEN-EXP-<domain>- prefix; the id is now bare, so the category is the signal instead).
        for (GeneratedRuleInfo info : pkg.getReport().getGeneratedRules())
        {
            if (info.category() == RuleCategory.SDTM_PREFIX_EXPANSION && info.ruleId() != null)
            {
                sdtmPrefixExpandedIds.add(info.ruleId());
            }
        }

        // Fix #59 completion: the execution-time `--` substitution prefix must be the CDISC domain
        // code (RuleRunner.resolveOperationPrefix / CheckConditionTransformer.resolvePrefixes /
        // EvaluationContext.domainPrefix), NOT the member name truncated to two characters. The old
        // `prefixOf(domain)` returned "SU" for a SUPP dataset like "SUPPLB", so an operation with
        // `domain: "SUPP--"` was rewritten to "SUPPSU" (resolveOperationPrefix's SUPP-aware branch
        // only fires for a prefix starting with "SUPP"/"SQAP" of length > 4). resolve("SUPPSU")
        // then missed and the operation result was absent — e.g. CORE-000712's $rdomain_variables.
        // cdiscDomain ("SUPPLB" here) feeds the SUPP-aware branch correctly, yielding "SUPPLB".
        String domainPrefix = cdiscDomain;

        // Per-dataset join cache — reuses join structures across rules that
        // reference the same datasets (e.g., all rules joining DM by USUBJID
        // share one DatasetLookup with its pre-built row map).
        net.cumba.cdisc.core.exec.JoinCache joinCache = new net.cumba.cdisc.core.exec.JoinCache(
                aSharedIndex);

        // Per-dataset expression-result cache — reuses pure leaf results across the rules of this
        // dataset (PLAN-dataset-expression-cache.md). Strictly per-dataset (keyed on table
        // instance identity); never shared across datasets. Null when disabled via the
        // kill-switch, which is behaviour-identical to the pre-cache engine.
        net.cumba.cdisc.core.exec.@Nullable ExpressionResultCache exprCache = EXPR_CACHE_DISABLED
                ? null
                : new net.cumba.cdisc.core.exec.ExpressionResultCache();

        List<Rule> generatedRules = pkg.getRules();

        // Group rules into cohorts before scheduling. A cohort is a set of rules that share an
        // identical Check shape modulo column names — they can be evaluated in a single shared
        // row pass through the primary table. Length-1 groups (singletons or ineligible rules)
        // run via the existing per-rule path; groups of size >= 2 go through CohortRunner.
        // The grouper preserves input order, so when we drain results into dsResult below the
        // sequence matches generatedRules iteration order — keeping report parity.
        // Fix #222: a rule whose readings of an absent, already-reported dataset are suppressed
        // must not be cohorted — CohortRunner's shared row pass reads Rule.getCheckExpr() directly
        // and cannot honour the per-(rule, dataset) decision. Demoting routes it back through
        // RuleRunner.execute, which owns it.
        // Plan C §3.4: a rule whose EVERY declared level is below the run threshold must not be
        // cohorted either — the cohort path has no threshold gate, so it would evaluate a rule the
        // run did not ask for. Demoting routes it back through RuleRunner.execute, which reports
        // the SKIPPED-with-a-reason the threshold demands. ⚑ Vacuous at the default threshold.
        List<List<Rule>> cohorts = net.cumba.cdisc.core.exec.RuleCohortGrouper.group(generatedRules,
                table.getMetaData(),
                r -> belowSeverityThreshold(r) || AbsentDatasetSkip
                        .decide(r, aResolver, presenceReportedDatasets, crossStandardDatasets,
                                table.getMetaData().getName(), domainPrefix)
                        .applies());

        if (ruleThreads <= 1 || generatedRules.size() < 2)
        {
            // Sequential path — bit-identical to Phase 1 modulo cohort batching; zero fan-out.
            for (List<Rule> cohort : cohorts)
            {
                if (cohort.size() == 1)
                {
                    Rule rule = cohort.get(0);
                    RuleExecutionResult result = executeRule(rule, table, aResolver, domainPrefix,
                            joinCache, exprCache, domain, fileName);
                    dsResult.addRuleResult(rule, result);
                }
                else
                {
                    executeAndRecordCohort(cohort, new CohortRunCtx(table, aResolver, domainPrefix,
                            joinCache, exprCache, domain, fileName), dsResult);
                }
            }
        }
        else
        {
            // Parallel path — submit one task per cohort (cohort = unit of work). Cohorts run in
            // parallel; rules within a cohort still share their row pass.
            RuleExecutionResult[] slots = executeCohortsInParallel(generatedRules, cohorts,
                    new CohortRunCtx(table, aResolver, domainPrefix, joinCache, exprCache, domain,
                            fileName));
            for (int i = 0; i < generatedRules.size(); i++)
            {
                dsResult.addRuleResult(generatedRules.get(i), slots[i]);
            }
        }

        if (exprCache != null)
        {
            LOGGER.log(Level.DEBUG,
                    "Dataset {0}: expression cache held {1} distinct pure leaf result(s).", domain,
                    exprCache.size());
        }

        // Fire the runtime listener for every input rule that the generator filtered out by
        // scope. The runtime report becomes a complete audit: one entry per (input rule × dataset)
        // tuple, executed or otherwise. statusMessage carries the skip reason for diagnostics.
        if (runtimeListener != null && !pkg.getSkippedSourceRules().isEmpty())
        {
            for (SkippedSourceRule skipped : pkg.getSkippedSourceRules())
            {
                Rule rule = skipped.rule();
                runtimeListener.onRuleExecuted(new RuntimeEntry(domain, fileName,
                        table.getRowCount(), table.getColumnCount(), rule.effectiveId(), 0L,
                        RuleExecutionStatus.SKIPPED, 0));
            }
        }

        long endDs = System.currentTimeMillis();
        long rtDs = endDs - startDs;
        dsResult.runtimeMillis = rtDs;
        LOGGER.log(Level.DEBUG, "Validated data set {0} in {1}ms.", aDataset.domain, rtDs);
        return dsResult;
    }


    /**
     * Runs one rule against the primary table, returning its {@link RuleExecutionResult} and firing
     * the {@link RuntimeListener}. Pure of any shared writable state besides the (thread-safe)
     * listener — safe to call from any worker thread.
     */
    private RuleExecutionResult executeRule(Rule rule, IDataTable table, DatasetResolver resolver,
            @Nullable String domainPrefix, net.cumba.cdisc.core.exec.@Nullable JoinCache joinCache,
            net.cumba.cdisc.core.exec.@Nullable ExpressionResultCache exprCache, String domain,
            @Nullable String fileName)
    {
        LOGGER.log(Level.DEBUG, "Will execute rule {0} on {1}", rule.effectiveId(),
                table.getMetaData().getName());
        long start = System.currentTimeMillis();
        RuleExecutionResult result;
        try
        {
            result = RuleRunner.execute(rule, table, resolver, domainPrefix, provider, joinCache,
                    defineProvider, maxErrorsPerRule, exprCache, dictionaryProvider, vlmResolver,
                    presenceReportedDatasets, crossStandardDatasets, severityThreshold);
        }
        catch (RuntimeException e)
        {
            String coreId = rule.effectiveId();
            String datasetName = table.getMetaData().getName();
            // Surface the failure (with its stack trace) to anything listening on the engine
            // logger — the REST live-log capture renders the trace. The synthetic ERROR result
            // below still carries the one-line message into the structured report. The
            // log(Level, String, Throwable) overload does no MessageFormat substitution, so the
            // message is built by concatenation.
            LOGGER.log(Level.ERROR, "Rule " + coreId + " failed on dataset " + datasetName + ": "
                    + e.getClass().getSimpleName() + ": " + e.getMessage(), e);
            // Unexpected exception — convert to a synthetic ERROR result so the finding still
            // makes it into the report.
            result = RuleExecutionResult.builder().ruleId(rule.effectiveId()).message("")
                    .violations(List.of()).totalRows(table.getRowCount())
                    .status(RuleExecutionStatus.ERROR)
                    .statusMessage(e.getClass().getSimpleName() + ": " + e.getMessage()).build();
        }
        long rt = System.currentTimeMillis() - start;
        result = result.toBuilder().runtimeMillis(rt).build();
        LOGGER.log(Level.DEBUG, "Executed rule {0} on {1} in {2}ms.", rule.effectiveId(),
                table.getMetaData().getName(), rt);
        logIfTruncated(rule, table.getMetaData().getName(), result);
        if (runtimeListener != null)
        {
            runtimeListener.onRuleExecuted(new RuntimeEntry(domain, fileName, table.getRowCount(),
                    table.getColumnCount(), rule.effectiveId(), rt, result.getStatus(),
                    result.getViolationCount()));
        }
        return result;
    }


    /**
     * Emits an INFO line when a rule's findings were capped below the true total (the per-rule
     * findings cap — see {@link net.cumba.cdisc.core.exec.EngineLimits}). Mirrors the Python
     * engine's "error limit reached" diagnostic so a truncated report is never silently partial.
     */
    private static void logIfTruncated(Rule rule, @Nullable String datasetName,
            RuleExecutionResult result)
    {
        if (result.isTruncated())
        {
            String coreId = rule.effectiveId();
            LOGGER.log(Level.INFO, "Rule {0}: findings capped at {1} of {2} on dataset {3} "
                    + "(raise corej.maxErrorsPerRule / MAX_ERRORS_PER_RULE, 0 = unlimited).",
                    coreId, result.getViolations().size(), result.getViolationCount(), datasetName);
        }
    }

    /**
     * Per-dataset runtime context threaded through cohort execution. Bundles the 6 fields that stay
     * constant for every cohort within one dataset's validation pass; saves passing them one-by-one
     * through the sequential and parallel cohort runners.
     */
    private record CohortRunCtx(IDataTable table, DatasetResolver resolver, String domainPrefix,
            net.cumba.cdisc.core.exec.JoinCache joinCache,
            net.cumba.cdisc.core.exec.@Nullable ExpressionResultCache exprCache, String domain,
            @Nullable String fileName)
    {
    }

    /**
     * Sequential cohort execution helper — runs every rule in the cohort in one shared row pass and
     * records each rule's result + runtime entry. Used by the single-threaded path.
     */
    private void executeAndRecordCohort(List<Rule> cohort, CohortRunCtx ctx, DatasetResult dsResult)
    {
        long start = System.currentTimeMillis();
        List<RuleExecutionResult> results;
        try
        {
            results = net.cumba.cdisc.core.exec.CohortRunner.executeCohort(cohort, ctx.table(),
                    ctx.resolver(), ctx.domainPrefix(), provider, ctx.joinCache(), maxErrorsPerRule,
                    ctx.exprCache());
        }
        catch (RuntimeException e)
        {
            // If the cohort path itself blows up (e.g. an assumption-violating rule slipped
            // through the grouper), fall back to per-rule execution so the run completes.
            LOGGER.log(Level.WARNING, "Cohort execution failed; falling back to per-rule. {0}",
                    e.toString());
            for (Rule rule : cohort)
            {
                RuleExecutionResult result = executeRule(rule, ctx.table(), ctx.resolver(),
                        ctx.domainPrefix(), ctx.joinCache(), ctx.exprCache(), ctx.domain(),
                        ctx.fileName());
                dsResult.addRuleResult(rule, result);
            }
            return;
        }
        long elapsed = System.currentTimeMillis() - start;
        // Apportion cohort wall time across members for per-rule profiling. Sum-of-elapsed across
        // all RuntimeEntry rows still reflects total CPU work (the runtime-listener Javadoc
        // already states sum may exceed wall time under any parallelism mode).
        long perRuleMs = cohort.isEmpty() ? elapsed : elapsed / cohort.size();
        for (int i = 0; i < cohort.size(); i++)
        {
            Rule rule = cohort.get(i);
            RuleExecutionResult result = results.get(i).toBuilder().runtimeMillis(perRuleMs)
                    .build();
            dsResult.addRuleResult(rule, result);
            logIfTruncated(rule, ctx.domain(), result);
            if (runtimeListener != null)
            {
                runtimeListener.onRuleExecuted(new RuntimeEntry(ctx.domain(), ctx.fileName(),
                        ctx.table().getRowCount(), ctx.table().getColumnCount(), rule.effectiveId(),
                        perRuleMs, result.getStatus(), result.getViolationCount()));
            }
        }
        LOGGER.log(Level.DEBUG, "Executed cohort of {0} rules on {1} in {2}ms.", cohort.size(),
                ctx.table().getMetaData().getName(), elapsed);
    }


    /**
     * Fans cohort execution out across a fixed-size pool of platform threads, returning per-rule
     * results in submission order. Cohorts are the unit of work — within a cohort the row pass is
     * shared (single-threaded over the rows for that cohort); across cohorts, work fans out.
     * <p>
     * Platform (not virtual) threads: rule evaluation is CPU-bound (HashLookup probes against
     * in-heap data with no blocking I/O), so cache locality from a small fixed carrier set
     * outperforms virtual threads' scheduler hop. The pool is created and closed per dataset so
     * threads don't accumulate across the run.
     */
    private RuleExecutionResult[] executeCohortsInParallel(List<Rule> generatedRules,
            List<List<Rule>> cohorts, CohortRunCtx ctx)
    {
        int n = Math.clamp(cohorts.size(), 1, ruleThreads);
        RuleExecutionResult[] slots = new RuleExecutionResult[generatedRules.size()];

        // Build a rule -> slot index map once so each cohort's results land in the right place.
        IdentityHashMap<Rule, Integer> slotByRule = new IdentityHashMap<>(generatedRules.size());
        for (int i = 0; i < generatedRules.size(); i++)
        {
            slotByRule.put(generatedRules.get(i), i);
        }

        try (ExecutorService pool = Executors.newFixedThreadPool(n, r ->
        {
            Thread t = new Thread(r, "cdisc-rule-" + ctx.domain());
            t.setDaemon(true);
            return t;
        }))
        {
            Executor wrapped = decorated(pool);
            List<CompletableFuture<Void>> futures = new ArrayList<>(cohorts.size());
            for (List<Rule> cohort : cohorts)
            {
                final List<Rule> finalCohort = cohort;
                futures.add(CompletableFuture.runAsync(
                        () -> runCohortIntoSlots(finalCohort, ctx, slots, slotByRule), wrapped));
            }
            for (CompletableFuture<Void> f : futures)
            {
                f.join();
            }
        }
        return slots;
    }


    /**
     * Worker body called from {@link #executeCohortsInParallel}: executes one cohort (sequential
     * row pass within the cohort) and stores each rule's result into its assigned slot. Distinct
     * cohorts write to disjoint slot indices, so the slot array stays race-free.
     */
    private void runCohortIntoSlots(List<Rule> cohort, CohortRunCtx ctx,
            RuleExecutionResult[] slots, Map<Rule, Integer> slotByRule)
    {
        if (cohort.size() == 1)
        {
            Rule rule = cohort.get(0);
            slots[Objects.requireNonNull(slotByRule.get(rule))] = executeRule(rule, ctx.table(),
                    ctx.resolver(), ctx.domainPrefix(), ctx.joinCache(), ctx.exprCache(),
                    ctx.domain(), ctx.fileName());
            return;
        }
        long start = System.currentTimeMillis();
        List<RuleExecutionResult> results;
        try
        {
            results = net.cumba.cdisc.core.exec.CohortRunner.executeCohort(cohort, ctx.table(),
                    ctx.resolver(), ctx.domainPrefix(), provider, ctx.joinCache(), maxErrorsPerRule,
                    ctx.exprCache());
        }
        catch (RuntimeException e)
        {
            LOGGER.log(Level.WARNING, "Cohort execution failed; falling back to per-rule. {0}",
                    e.toString());
            for (Rule rule : cohort)
            {
                slots[Objects.requireNonNull(slotByRule.get(rule))] = executeRule(rule, ctx.table(),
                        ctx.resolver(), ctx.domainPrefix(), ctx.joinCache(), ctx.exprCache(),
                        ctx.domain(), ctx.fileName());
            }
            return;
        }
        long elapsed = System.currentTimeMillis() - start;
        long perRuleMs = cohort.isEmpty() ? elapsed : elapsed / cohort.size();
        for (int i = 0; i < cohort.size(); i++)
        {
            Rule rule = cohort.get(i);
            RuleExecutionResult result = results.get(i).toBuilder().runtimeMillis(perRuleMs)
                    .build();
            slots[Objects.requireNonNull(slotByRule.get(rule))] = result;
            logIfTruncated(rule, ctx.domain(), result);
            if (runtimeListener != null)
            {
                runtimeListener.onRuleExecuted(new RuntimeEntry(ctx.domain(), ctx.fileName(),
                        ctx.table().getRowCount(), ctx.table().getColumnCount(), rule.effectiveId(),
                        perRuleMs, result.getStatus(), result.getViolationCount()));
            }
        }
        LOGGER.log(Level.DEBUG, "Executed cohort of {0} rules on {1} in {2}ms.", cohort.size(),
                ctx.table().getMetaData().getName(), elapsed);
    }


    /**
     * Fix #60: resolves the dataset's observation class via the provider's full 3-tier resolver
     * (Define-XML → product reverse-walk → custom-domain sniffer). The member name keys tier 1; the
     * CDISC domain code keys tiers 2/3 — needed for split datasets like {@code LBHE} where the
     * member name and CDISC code differ. The dataset's actual columns are passed so the tier-3
     * sniffer can classify datasets the metadata library does not carry (e.g. {@code SUPP--}),
     * mirroring Python's {@code handle_custom_domains} on the loaded dataset.
     *
     * <p>
     * When the provider cannot resolve a class and the dataset is an Associated Persons domain
     * ({@code AP--} carrying an {@code APID} column), the class is inherited from the parent domain
     * (e.g. {@code APLB} → the {@code LB} dataset's class), mirroring Python's
     * {@code _get_associated_persons_inherit_class}. Unlike Python — which raises on a missing
     * parent or a nested AP reference — this degrades gracefully to {@code null} (the rule stays
     * SKIPPED) rather than aborting the dataset.
     * </p>
     */
    private @Nullable String classNameFor(@Nullable String aMemberName, String aCdiscDomain,
            IDataTable aTable, DatasetResolver aResolver)
    {
        return classNameFor(aMemberName, aCdiscDomain, aTable, aResolver, false);
    }


    private @Nullable String classNameFor(@Nullable String aMemberName, String aCdiscDomain,
            IDataTable aTable, DatasetResolver aResolver, boolean aApRecursed)
    {
        Set<String> columns = columnNames(aTable);
        String className = provider.getDatasetClass(aMemberName, aCdiscDomain, columns);
        if (className != null)
        {
            return className;
        }
        // AP-- inherit (Python _get_associated_persons_inherit_class): an Associated Persons domain
        // carries an APID column; its class is inherited from the parent domain named by the AP
        // suffix (e.g. APLB -> LB). Applied only after the custom-domain sniff failed, matching
        // Python's order. Single-level recursion: a nested AP parent returns null via the guard.
        // The DOMAIN column must be present: Python derives ap_suffix from dataset_metadata.domain
        // (the DOMAIN value), which is None when the column is absent — so no inherit then. Gating
        // on the column also guarantees aCdiscDomain here is the DOMAIN value, not a member-name
        // fallback, so substring(2) is the true AP suffix.
        //
        // Sibling predicate: OperationExecutor.apSuffixOf (EC-36) computes the same Python
        // ap_suffix for `--` variable-name resolution. The two are deliberately NOT shared: this
        // one gates on the DOMAIN *column* and reads the already-resolved aCdiscDomain, while
        // apSuffixOf gates on a non-empty row-0 DOMAIN *value*. Unifying them would change which
        // class an AP dataset inherits — a Scope.Classes-wide blast radius unrelated to EC-36.
        // If either is edited, re-check the other.
        if (!aApRecursed && columns.contains("APID") && columns.contains("DOMAIN")
                && aCdiscDomain != null && aCdiscDomain.length() >= 4
                && aCdiscDomain.toUpperCase(java.util.Locale.ROOT).startsWith("AP"))
        {
            String parentDomain = aCdiscDomain.substring(2);
            IDataTable parent = aResolver.resolve(parentDomain);
            if (parent != null)
            {
                String parentCdisc = net.cumba.cdisc.core.metadata.CdiscDomainResolver
                        .cdiscDomainOf(parent);
                String parentMember = parent.getMetaData().getName();
                return classNameFor(parentMember, parentCdisc, parent, aResolver, true);
            }
            LOGGER.log(Level.DEBUG,
                    "AP dataset {0}: parent domain {1} not in study; class left undetermined",
                    aMemberName, parentDomain);
        }
        return className;
    }


    /**
     * Upper-/mixed-case column names of the loaded dataset, for the tier-3 custom-domain sniffer.
     */
    private static Set<String> columnNames(IDataTable aTable)
    {
        var meta = aTable.getMetaData();
        Set<String> names = HashSet.newHashSet(meta.getColumnCount());
        for (int i = 0; i < meta.getColumnCount(); i++)
        {
            names.add(meta.getColumn(i).getName());
        }
        return names;
    }

    // ------------------------------------------------------------------
    // Builder
    // ------------------------------------------------------------------


    public static Builder builder()
    {
        return new Builder();
    }

    // The staged builder leaves the required `provider` unset until build()/provider() is called —
    // a standard builder pattern NullAway's init check can't follow.
    @SuppressWarnings("NullAway.Init")
    public static final class Builder
    {

        private MetadataProvider provider;

        private @Nullable MetadataProvider defineProvider;

        private @Nullable VlmResolver vlmResolver;

        private @Nullable RuntimeDictionaryProvider dictionaryProvider;

        private final List<Rule> rules = new ArrayList<>();

        private @Nullable String libraryUri;

        private final Map<String, Dataset> targetDatasets = new LinkedHashMap<>();

        private final Map<String, Supplier<IDataTable>> referenceDatasets = new LinkedHashMap<>();

        private final List<String> libraryWarnings = new ArrayList<>();

        private Set<String> crossStandardDatasets = Set.of();

        private boolean sequential;

        private int ruleThreads = 1;

        private @Nullable RuntimeListener runtimeListener;

        private @Nullable DatasetListener datasetListener;

        private UnaryOperator<Runnable> taskDecorator = UnaryOperator.identity();

        /**
         * Per-run findings-cap override; {@code null} uses the global {@code EngineLimits} config.
         */
        private @Nullable Integer maxErrorsPerRule;

        /** Per-run severity threshold; {@code null} uses the engine default ({@code Warning}). */
        private net.cumba.datatable.report.@Nullable Severity severityThreshold;

        private Builder()
        {
        }


        /** The metadata provider that the rule engine should consult. */
        public Builder provider(MetadataProvider aProvider)
        {
            provider = aProvider;
            return this;
        }


        /**
         * The sponsor Define-XML metadata provider (the "define" level), or {@code null} when no
         * Define-XML is present. Carried for the {@code define_*} operand family.
         */
        public Builder defineProvider(@Nullable MetadataProvider aDefineProvider)
        {
            defineProvider = aDefineProvider;
            return this;
        }


        /**
         * The per-record Define-XML value-level metadata resolver (VLM), or {@code null} when no
         * Define-XML value-level metadata is present. Carried for the {@code vlm_*} accessors.
         */
        public Builder vlmResolver(@Nullable VlmResolver aVlmResolver)
        {
            vlmResolver = aVlmResolver;
            return this;
        }


        /**
         * T1 — the runtime external-dictionary provider (MedDRA / WHODrug / … value-maps), or
         * {@code null} when no dictionaries are supplied (dictionary rules then SKIP).
         */
        public Builder dictionaryProvider(@Nullable RuntimeDictionaryProvider aDictionaryProvider)
        {
            dictionaryProvider = aDictionaryProvider;
            return this;
        }


        /**
         * The rule package to run. Either this or {@link #rules(Collection)} must be called.
         */
        public Builder rules(RulePackage aPackage)
        {
            Objects.requireNonNull(aPackage, "rule package");
            rules.clear();
            Map<String, Rule> pkgRules = aPackage.getRules();
            if (pkgRules != null)
            {
                rules.addAll(pkgRules.values());
            }
            return this;
        }


        /** Explicit list of rules to run (alternative to {@link #rules(RulePackage)}). */
        public Builder rules(Collection<Rule> aRules)
        {
            Objects.requireNonNull(aRules, "rules");
            rules.clear();
            rules.addAll(aRules);
            return this;
        }


        /**
         * {@code Fix #218} — the run's <b>cross-standard coverage</b>: the dataset names belonging
         * to a CDISC standard this run does <b>not</b> validate
         * ({@code plans/PLAN-cross-standard-absence-skip.md}).
         *
         * <p>
         * On an ADaM-family run this is the companion SDTM domain catalogue, so a rule whose whole
         * Check depends on a <em>cross-standard</em> dataset that was not supplied to the run
         * reports {@code SKIPPED} instead of a vacuous PASS. Empty (the default) is
         * behaviour-identical to the pre-{@code Fix #218} engine, which is why every caller that
         * does not set it is unaffected.
         * </p>
         *
         * @param aDatasets
         *            dataset names; upper-cased and copied defensively
         * @return this builder
         */
        public Builder crossStandardDatasets(Collection<String> aDatasets)
        {
            Objects.requireNonNull(aDatasets, "crossStandardDatasets");
            Set<String> out = new LinkedHashSet<>();
            for (String name : aDatasets)
            {
                if (name != null && !name.isBlank())
                {
                    out.add(name.trim().toUpperCase(java.util.Locale.ROOT));
                }
            }
            crossStandardDatasets = Set.copyOf(out);
            return this;
        }


        /**
         * URI of the library under test. Used as the {@code fileName} for any synthetic
         * library-level findings in the produced report.
         */
        public Builder libraryUri(@Nullable String aLibraryUri)
        {
            libraryUri = aLibraryUri;
            return this;
        }


        /**
         * Registers a dataset to validate. Targets are both iterated for rule execution and exposed
         * to the {@link DatasetResolver}.
         *
         * @param aDomain
         *            the dataset / domain name, e.g. {@code "DM"}
         * @param aFileName
         *            the source file name (used as <code>fileName</code> in the report)
         * @param aTable
         *            the loaded data table
         */
        public Builder targetDataset(String aDomain, @Nullable String aFileName, IDataTable aTable)
        {
            Objects.requireNonNull(aTable, "table");
            return targetDataset(aDomain, aFileName, () -> aTable);
        }


        /**
         * Registers a dataset to validate via a {@link Supplier}, deferring the resolution of the
         * {@link IDataTable} until the validator's per-dataset thread starts work. The supplier is
         * expected to memoise so repeated calls (e.g. via the {@link DatasetResolver}) return the
         * same instance cheaply.
         */
        public Builder targetDataset(String aDomain, @Nullable String aFileName,
                Supplier<IDataTable> aTableSupplier)
        {
            Objects.requireNonNull(aDomain, PARAM_DOMAIN);
            Objects.requireNonNull(aTableSupplier, PARAM_TABLE_SUPPLIER);
            targetDatasets.put(aDomain.toUpperCase(java.util.Locale.ROOT),
                    new Dataset(aDomain, aFileName, aTableSupplier));
            return this;
        }


        /**
         * Registers a reference dataset. References are visible to the {@link DatasetResolver} so
         * cross-dataset rules can look them up, but are <em>not</em> themselves iterated for
         * validation.
         */
        public Builder referenceDataset(String aDomain, IDataTable aTable)
        {
            Objects.requireNonNull(aTable, "table");
            return referenceDataset(aDomain, () -> aTable);
        }


        /**
         * Registers a reference dataset via a {@link Supplier}. The supplier is consulted only when
         * a rule actually resolves the domain — references that no rule consults are never loaded.
         */
        public Builder referenceDataset(String aDomain, Supplier<IDataTable> aTableSupplier)
        {
            Objects.requireNonNull(aDomain, PARAM_DOMAIN);
            Objects.requireNonNull(aTableSupplier, PARAM_TABLE_SUPPLIER);
            referenceDatasets.put(aDomain.toUpperCase(java.util.Locale.ROOT), aTableSupplier);
            return this;
        }


        /**
         * Adds a library-level warning to the resulting report. Use for conditions that don't map
         * to any single dataset (e.g. study/caller standard mismatch).
         */
        public Builder libraryWarning(String aMessage)
        {
            Objects.requireNonNull(aMessage, "message");
            libraryWarnings.add(aMessage);
            return this;
        }


        /**
         * When {@code true}, datasets are validated one after the other on the calling thread
         * instead of in parallel virtual threads. Defaults to {@code false} (parallel).
         * <p>
         * Set this when profiling per-rule runtime, or when the caller intends to parallelise at a
         * different granularity (e.g. across rules within a single dataset).
         */
        public Builder sequential(boolean aSequential)
        {
            sequential = aSequential;
            return this;
        }


        /**
         * Per-run override of the per-rule findings cap. {@code null} (the default) uses the global
         * {@code corej.maxErrorsPerRule} / {@code MAX_ERRORS_PER_RULE} configuration; a value
         * {@code <= 0} means unlimited.
         *
         * @param aMaxErrorsPerRule
         *            the per-rule cap, or {@code null} to follow the global configuration
         * @return this builder
         */
        public Builder maxErrorsPerRule(@Nullable Integer aMaxErrorsPerRule)
        {
            maxErrorsPerRule = aMaxErrorsPerRule;
            return this;
        }


        /**
         * The run's <b>severity threshold</b> (Plan C §3.4, ruling 4) — the weakest check level to
         * evaluate. Declared levels below it are not evaluated; a rule whose every declared level
         * is below it reports {@code SKIPPED} with that reason.
         *
         * @param aSeverityThreshold
         *            the weakest rung to evaluate, or {@code null} for the engine default
         *            ({@code Warning})
         * @return this builder
         */
        public Builder severityThreshold(
                net.cumba.datatable.report.@Nullable Severity aSeverityThreshold)
        {
            severityThreshold = aSeverityThreshold;
            return this;
        }


        /**
         * Sets the number of platform threads used to evaluate rules within a single dataset.
         * Defaults to {@code 1} (rules evaluated on the calling thread, byte-identical to the
         * pre-Phase-2 behaviour).
         * <p>
         * Values above {@link Runtime#availableProcessors()} are clamped down with a debug-level
         * log message — running more rule threads than CPU carriers wastes context-switch overhead
         * for a CPU-bound workload.
         * <p>
         * Throws {@link IllegalArgumentException} for {@code n < 1}.
         *
         * @param n
         *            number of rule worker threads; must be {@code >= 1}
         */
        public Builder ruleThreads(int n)
        {
            if (n < 1)
            {
                throw new IllegalArgumentException(
                        "ruleThreads must be >= 1 (got " + n + "); use 1 to disable fan-out");
            }
            int max = Runtime.getRuntime().availableProcessors();
            if (n > max)
            {
                LOGGER.log(Level.DEBUG, "ruleThreads={0} clamped to availableProcessors={1}", n,
                        max);
                n = max;
            }
            ruleThreads = n;
            return this;
        }


        /**
         * Registers a callback that fires after each individual rule execution, with timing and
         * outcome details. Useful for producing a runtime profile alongside the validation report.
         * <p>
         * The listener may be invoked from any worker thread — the implementation must be
         * thread-safe. Specifically: virtual threads in parallel-dataset mode (the default), or
         * platform threads when {@link #ruleThreads(int) ruleThreads &gt; 1}.
         */
        public Builder runtimeListener(@Nullable RuntimeListener aListener)
        {
            runtimeListener = aListener;
            return this;
        }


        /**
         * Registers a callback that fires once per target dataset as it finishes validating, live
         * from inside {@link #validate()}.
         * <p>
         * In parallel-dataset mode (the default) the listener is invoked concurrently from worker
         * threads in completion order; in {@link #sequential(boolean) sequential} mode it is
         * invoked on the calling thread in the validator's dataset iteration order. Implementations
         * must be thread-safe.
         */
        public Builder datasetListener(@Nullable DatasetListener aListener)
        {
            datasetListener = aListener;
            return this;
        }


        /**
         * Registers a decorator applied to every async task before it is submitted to a parallel
         * executor, on the submitting thread. Lets a caller re-establish thread-bound context (e.g.
         * a log-capture sink) on the worker thread that ultimately runs the task — robust against
         * thread pooling, reuse, and virtual-vs-platform threads. Defaults to
         * {@link UnaryOperator#identity()} (no-op). Must not be {@code null}.
         */
        public Builder taskDecorator(UnaryOperator<Runnable> aDecorator)
        {
            taskDecorator = Objects.requireNonNull(aDecorator, "taskDecorator");
            return this;
        }


        /**
         * Builds the validator and runs {@link LibraryValidator#validate()}.
         */
        public ValidationReport validate()
        {
            return build().validate();
        }


        /** Builds the validator without running it. */
        public LibraryValidator build()
        {
            Objects.requireNonNull(provider, "provider");
            // An empty static rule list is still accepted — but since Fix #366 nothing is
            // merged in behind the caller's back, so it means zero rules run and, because
            // report members are findings-driven, zero report members.
            return new LibraryValidator(this);
        }
    }

    // ------------------------------------------------------------------
    // Per-dataset result (single-threaded; coordinator-only writer)
    // ------------------------------------------------------------------


    /**
     * Collects rule execution results and warnings for a single dataset. Always written from one
     * thread:
     * <ul>
     * <li>Sequential rule loop — written from the per-dataset coordinator thread directly.</li>
     * <li>Parallel rule fan-out — written from the coordinator thread <em>after</em> all rule tasks
     * have joined, draining a per-rule slot array in submission order. This preserves the
     * rule-iteration order required for byte-for-byte report parity.</li>
     * </ul>
     * No internal synchronisation required.
     */
    private static final class DatasetResult
    {

        final String domain;

        final @Nullable String fileName;

        final List<String> warnings = new ArrayList<>();

        final List<String> loadErrors = new ArrayList<>();

        final List<RuleResultEntry> ruleResults = new ArrayList<>();

        final List<SkippedSourceRule> skippedSourceRules = new ArrayList<>();

        /** Wall-clock validation time for this dataset in ms; {@code -1} until set. */
        long runtimeMillis = -1;

        DatasetResult(String aDomain, @Nullable String aFileName)
        {
            domain = aDomain;
            fileName = aFileName;
        }


        void addWarning(String aMessage)
        {
            warnings.add(aMessage);
        }


        void addLoadError(String aMessage)
        {
            loadErrors.add(aMessage);
        }


        void addRuleResult(Rule aRule, RuleExecutionResult aResult)
        {
            ruleResults.add(new RuleResultEntry(aRule, aResult));
        }


        /** Replays this dataset's results into the given report builder. */
        void mergeInto(ValidationReportBuilder aBuilder)
        {
            for (String w : warnings)
            {
                aBuilder.libraryWarning(w);
            }
            for (String e : loadErrors)
            {
                aBuilder.datasetLoadError(domain, fileName, e);
            }
            for (RuleResultEntry e : ruleResults)
            {
                aBuilder.add(domain, fileName, e.rule, e.result);
            }
            // Generation-time scope skips never reach add() (the generator filters those rules
            // out before execution) — forward them with their reasons so both skip sources land
            // in the report's skipped-rules section. Ordering matches buildExecutionSummaries:
            // after this dataset's rule results.
            for (SkippedSourceRule s : skippedSourceRules)
            {
                aBuilder.skippedRule(domain, fileName, s.rule(), s.reason());
            }
        }

        private record RuleResultEntry(Rule rule, RuleExecutionResult result)
        {
        }
    }

    // ------------------------------------------------------------------
    // Dataset descriptor
    // ------------------------------------------------------------------


    /**
     * Immutable descriptor for a dataset passed to the validator. The {@code tableSupplier} is
     * consulted lazily inside the validator's per-dataset virtual thread; suppliers are expected to
     * memoise the resolved {@link IDataTable} so that repeated lookups via the
     * {@link DatasetResolver} share a single instance.
     */
    public record Dataset(String domain, @Nullable String fileName,
            Supplier<IDataTable> tableSupplier)
    {

        public Dataset
        {
            Objects.requireNonNull(domain, PARAM_DOMAIN);
            Objects.requireNonNull(tableSupplier, PARAM_TABLE_SUPPLIER);
        }
    }

}
