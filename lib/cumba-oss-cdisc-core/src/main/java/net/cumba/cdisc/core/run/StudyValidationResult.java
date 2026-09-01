package net.cumba.cdisc.core.run;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.report.ReportAssembler;
import net.cumba.cdisc.core.report.ReportSections;
import net.cumba.datatable.report.ValidationReport;

/**
 * Outcome of a {@link StudyValidationService} run, carrying everything a caller needs to render
 * output (write a JSON file, serialize to a string, inspect findings, …) without re-running any
 * engine work.
 *
 * <p>
 * The {@link #conformance()} block already has the standard / version / substandard / use-case /
 * CT-version / define-version / engine-version / runtime fields populated.
 * </p>
 *
 * <h2>This record does not serialise (Fix #224)</h2>
 *
 * <p>
 * It used to carry seven conveniences — {@code reportWriter()}, {@code xlsxReportWriter(Integer)},
 * {@code writeReportTo}, {@code writeReportV2To}, {@code writeXlsxReportTo}, {@code toJsonString()}
 * and {@code toJsonV2String()} — which put JSON and Excel serialisation in the engine's
 * {@code run/} package and, through it, Apache POI on the engine's classpath. They are
 * <b>deleted</b>. What remains is {@link #sections()}: the neutral, format-agnostic report model. A
 * caller picks a format by name through {@link net.cumba.cdisc.core.report.ReportManager}, which
 * routes to whatever writer module the deployment ships:
 * </p>
 *
 * <pre>{@code
 * ReportManager manager = ServiceReportManager.getInstance();
 * ReportFormat json = manager.findReportFormat("json");
 * manager.writeReport(result.sections(), Path.of("report.json"), json, Map.of());
 * }</pre>
 *
 * @param report
 *            the assembled {@link ValidationReport}
 * @param conformance
 *            the conformance metadata block, ready to hand to {@link ReportAssembler#conformance}
 * @param datasets
 *            the per-dataset metadata for the {@code Dataset_Details} section
 * @param rules
 *            the rules actually used for validation (post-filter)
 * @param findingCount
 *            total finding rows across all datasets (the value the CLI logs)
 * @param totalRuntimeSeconds
 *            wall-clock duration of the run in seconds
 * @param executionSummaries
 *            per-domain execution stats (rules executed of total, findings, errors) for the per-run
 *            execution log; not written to the JSON validation report
 * @param generatedRules
 *            the synthetic generated (expanded) rules that actually ran, keyed by their expanded
 *            CORE id (e.g. {@code CG0001-AGE}); additive metadata for the run-scoped
 *            rule-definition view, not written to the JSON validation report
 * @param bundledCoreIds
 *            CORE ids of SDTM {@code --}-prefix expansions; the report writer rolls their
 *            per-domain rows up into a single {@code Issue_Summary} row (Python parity). Empty when
 *            no such expansion ran
 */
public record StudyValidationResult(ValidationReport report,
        ReportAssembler.Conformance conformance, List<ReportAssembler.DatasetInfo> datasets,
        List<Rule> rules, int findingCount, double totalRuntimeSeconds,
        List<DatasetExecutionSummary> executionSummaries, Map<String, Rule> generatedRules,
        Set<String> bundledCoreIds)
{

    public StudyValidationResult
    {
        Objects.requireNonNull(report, "report");
        Objects.requireNonNull(conformance, "conformance");
        datasets = List.copyOf(datasets);
        rules = List.copyOf(rules);
        executionSummaries = executionSummaries == null ? List.of()
                : List.copyOf(executionSummaries);
        generatedRules = generatedRules == null ? Map.of() : Map.copyOf(generatedRules);
        bundledCoreIds = bundledCoreIds == null ? Set.of() : Set.copyOf(bundledCoreIds);
    }


    /**
     * Convenience constructor without the additive {@code generatedRules} map and
     * {@code bundledCoreIds} set (both default to empty). Keeps callers that predate the run-scoped
     * rule-definition view compiling unchanged.
     */
    public StudyValidationResult(ValidationReport report, ReportAssembler.Conformance conformance,
            List<ReportAssembler.DatasetInfo> datasets, List<Rule> rules, int findingCount,
            double totalRuntimeSeconds, List<DatasetExecutionSummary> executionSummaries)
    {
        this(report, conformance, datasets, rules, findingCount, totalRuntimeSeconds,
                executionSummaries, Map.of(), Set.of());
    }


    /**
     * Convenience constructor without the additive {@code bundledCoreIds} set (defaults to empty).
     */
    public StudyValidationResult(ValidationReport report, ReportAssembler.Conformance conformance,
            List<ReportAssembler.DatasetInfo> datasets, List<Rule> rules, int findingCount,
            double totalRuntimeSeconds, List<DatasetExecutionSummary> executionSummaries,
            Map<String, Rule> generatedRules)
    {
        this(report, conformance, datasets, rules, findingCount, totalRuntimeSeconds,
                executionSummaries, generatedRules, Set.of());
    }


    /**
     * Assembles this result into the neutral {@link ReportSections} every report writer consumes —
     * the one and only rendering entry point on this record.
     *
     * @return the assembled sections, carrying both the v1 sections and the v2 combined findings
     */
    public ReportSections sections()
    {
        return new ReportAssembler().report(report).conformance(conformance).datasets(datasets)
                .rules(rules).ruleRuntimesMillis(ruleRuntimesByCoreId())
                .datasetRuntimesMillis(datasetRuntimesByKey()).bundledCoreIds(bundledCoreIds)
                .sections();
    }


    /** Per-rule runtime in ms summed across datasets, keyed by CORE id (skips unmeasured rules). */
    private Map<String, Long> ruleRuntimesByCoreId()
    {
        Map<String, Long> out = new LinkedHashMap<>();
        for (DatasetExecutionSummary s : executionSummaries)
        {
            for (DatasetExecutionSummary.RuleExecution e : s.ruleExecutions())
            {
                if (e.coreId() != null && e.runtimeMillis() >= 0)
                {
                    out.merge(e.coreId(), e.runtimeMillis(), Long::sum);
                }
            }
        }
        return out;
    }


    /**
     * Per-dataset wall-clock runtime in ms, keyed by {@link ReportAssembler#datasetRuntimeKey}.
     */
    private Map<String, Long> datasetRuntimesByKey()
    {
        Map<String, Long> out = new LinkedHashMap<>();
        for (DatasetExecutionSummary s : executionSummaries)
        {
            out.put(ReportAssembler.datasetRuntimeKey(s.fileName(), s.domain()), s.runtimeMillis());
        }
        return out;
    }

}
