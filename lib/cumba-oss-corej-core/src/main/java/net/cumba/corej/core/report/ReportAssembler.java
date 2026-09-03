package net.cumba.corej.core.report;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.model.Authority;
import net.cumba.corej.core.model.AuthorityStandard;
import net.cumba.corej.core.model.Reference;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleIdentifier;
import net.cumba.datatable.report.RowFindingSlab;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationFindingLocation;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.jspecify.annotations.Nullable;

/**
 * Assembles a {@link ValidationReport} into {@link ReportSections} — the neutral, map-based shape
 * every report writer consumes. <b>This class produces no bytes and knows no format.</b>
 *
 * <p>
 * It is the engine half of what used to be {@code JsonReportWriter}: the sections it builds carry
 * the exact field names, ordering and sorting of the Python CORE engine's report
 * ({@code cdisc_rules_engine.services.reporting.json_report.JsonReport}), so a writer that
 * serialises them verbatim reproduces that output. The parity rules below therefore live here, with
 * the assembly, rather than in any one writer.
 * </p>
 *
 * <h2>Section structure</h2>
 *
 * <pre>{@code
 * {
 *   "Conformance_Details": { ... metadata as flat key/value map ... },
 *   "Dataset_Details":     [ { filename, label, path, modification_date, size_kb, length, domain, columns }, ... ],
 *   "Issue_Summary":       [ { dataset, core_id, message, issues, domain }, ... ]   sorted by (dataset, core_id),
 *   "Issue_Details":       [ { core_id, message, executability, dataset,
 *                              USUBJID, row, SEQ, variables, values, domain }, ... ]   sorted by (core_id, dataset),
 *   "Rules_Report":        [ { core_id, version, cdisc_rule_id, fda_rule_id,
 *                              message, status }, ... ]   sorted by core_id,
 *   "Skipped_Rules":       [ { core_id, dataset, reason }, ... ]   insertion order
 * }
 * }</pre>
 *
 * <h2>Python parity notes</h2>
 * <ul>
 * <li>Section keys use the Python {@code _get_property_name} convention (spaces and dashes replaced
 * with underscores).</li>
 * <li>{@code Issue_Limit_Per_Sheet} is always emitted as JSON {@code null} (placeholder), matching
 * the Python output.</li>
 * <li>{@code variables} is the rule's output variables minus {@code USUBJID} and {@code SEQ}, which
 * the Python format pulls out as separate per-error fields.</li>
 * <li>Status strings in {@code Rules_Report} are upper-cased underscore-separated forms of the
 * Python {@code ExecutionStatus} enum: {@code SUCCESS}, {@code SKIPPED}, {@code EXECUTION_ERROR},
 * {@code ISSUE_REPORTED}. {@code SKIPPED} is reported only for a rule that was skipped on at least
 * one dataset and executed on <em>none</em> — a rule skipped on some datasets and executed on
 * others reports {@code SUCCESS}, with its per-dataset skips still listed in {@code Skipped_Rules}
 * (see {@link #buildSkippedEverywhereCoreIds()}).</li>
 * <li><b>Additive Java extension</b> (not present in the Python output): a {@code domain} field on
 * {@code Dataset_Details} / {@code Issue_Summary} / {@code Issue_Details}, and a {@code columns}
 * field on {@code Dataset_Details}. These disambiguate the datasets of a multi-dataset file (Excel
 * sheets, multi-member XPT) that share a {@code filename} / {@code dataset} label. All pre-existing
 * fields are unchanged, so the Python output remains a subset.</li>
 * <li><b>Additive Java extension</b>: the {@code Skipped_Rules} section lists every skipped (rule ×
 * dataset) pair with its human-readable reason — the failing scope criterion for generation-time
 * skips, the runner's status message for execution-time skips.</li>
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 *
 * ReportSections sections = new ReportAssembler().report(validationReport)
 *         .conformance(Conformance.builder().standard("SDTMIG").version("3.4")
 *                 .ctVersion("sdtmct-2024-09-27").defineXmlVersion("2.1").totalRuntimeSeconds(42.5)
 *                 .coreEngineVersion("0.5.0.0").build())
 *         .datasets(List.of(new DatasetInfo("ae.xpt", "Adverse Events", "/study/sdtm",
 *                 "2026-04-01", 1234.5, 9876L, "AE", 12)))
 *         .rules(allRules).sections();
 * }</pre>
 */
// Staged builder: `report` / `conformance` are set via the fluent report()/conformance() methods
// before sections(); NullAway's init check can't follow that staged-assignment pattern.
@SuppressWarnings("NullAway.Init")
public final class ReportAssembler
{

    private static final String STATUS_SUCCESS = "SUCCESS";

    private static final String STATUS_SKIPPED = "SKIPPED";

    private static final String STATUS_EXECUTION_ERROR = "EXECUTION_ERROR";

    private static final String STATUS_ISSUE_REPORTED = "ISSUE_REPORTED";

    private static final String FIELD_CORE_ID = "core_id";

    private static final DateTimeFormatter REPORT_TS = DateTimeFormatter
            .ofPattern("yyyy-MM-dd'T'HH:mm:ss");

    private ValidationReport report;

    private Conformance conformance;

    private List<DatasetInfo> datasets = List.of();

    private Collection<Rule> rules = List.of();

    /**
     * CORE ids of SDTM {@code --}-prefix expansions. Findings whose {@code core_id} is in this set
     * are bundled across datasets into a single {@code Issue_Summary} row (keyed by core id +
     * message); all other ids keep their per-dataset rows. Empty ⇒ no roll-up.
     */
    private Set<String> bundledCoreIds = Set.of();

    /** Per-rule execution runtime in ms, summed across datasets, keyed by CORE id. */
    private Map<String, Long> ruleRuntimesMillis = Map.of();

    /** Per-dataset wall-clock runtime in ms, keyed by {@link #datasetRuntimeKey}. */
    private Map<String, Long> datasetRuntimesMillis = Map.of();

    public ReportAssembler report(ValidationReport aReport)
    {
        report = aReport;
        return this;
    }


    public ReportAssembler conformance(Conformance aConformance)
    {
        conformance = aConformance;
        return this;
    }


    public ReportAssembler datasets(List<DatasetInfo> aDatasets)
    {
        datasets = aDatasets != null ? aDatasets : List.of();
        return this;
    }


    public ReportAssembler rules(Collection<Rule> aRules)
    {
        rules = aRules != null ? aRules : List.of();
        return this;
    }


    /**
     * CORE ids of SDTM {@code --}-prefix expansions whose per-domain {@code Issue_Summary} rows are
     * rolled up into one row (Python parity). A {@code null} argument is treated as empty.
     */
    public ReportAssembler bundledCoreIds(Set<String> aBundledCoreIds)
    {
        bundledCoreIds = aBundledCoreIds != null ? aBundledCoreIds : Set.of();
        return this;
    }


    /**
     * Per-rule execution runtime in milliseconds (summed across the datasets a rule ran on), keyed
     * by CORE id. Surfaced as {@code runtime_ms} in {@code Rules_Report}; a missing entry yields
     * {@code -1} ("not measured").
     */
    public ReportAssembler ruleRuntimesMillis(Map<String, Long> aRuntimes)
    {
        ruleRuntimesMillis = aRuntimes != null ? aRuntimes : Map.of();
        return this;
    }


    /**
     * Per-dataset wall-clock runtime in milliseconds, keyed by {@link #datasetRuntimeKey}. Surfaced
     * as {@code runtime_ms} in {@code Dataset_Details}; a missing entry yields {@code -1}.
     */
    public ReportAssembler datasetRuntimesMillis(Map<String, Long> aRuntimes)
    {
        datasetRuntimesMillis = aRuntimes != null ? aRuntimes : Map.of();
        return this;
    }


    /** Stable key for the per-dataset runtime map: {@code filename} + NUL + {@code domain}. */
    public static String datasetRuntimeKey(@Nullable String filename, @Nullable String domain)
    {
        return (filename != null ? filename : "") + '\0' + (domain != null ? domain : "");
    }


    /**
     * Assembles every report section into the neutral, map-based {@link ReportSections} shape that
     * both the JSON and the XLSX writer consume. <b>This is the assembler's whole output</b> — a
     * writer never sees the {@link ValidationReport} model.
     *
     * <p>
     * {@code Issue_Details} rows are projected from the {@link IssueDetail} records into ordered
     * maps carrying the same field names <em>and</em> order the JSON document uses
     * ({@code core_id, message, executability, dataset, USUBJID, row, SEQ, variables, values,
     * domain}), so a writer can serialise the row map directly and reproduce the historical output
     * byte for byte.
     * </p>
     *
     * <p>
     * ⚠ Two of those pieces were <b>missing</b> from the sections before the writers were split
     * out, and building the JSON document from sections without them would have silently dropped
     * data: the trailing {@code domain} field of each {@code Issue_Details} row (an additive Java
     * extension, present on the record but not on the projected map), and the v2
     * {@link #combinedFindings() Findings} array, which had no representation here at all.
     * </p>
     *
     * @return every section of the report, never {@code null}
     */
    public ReportSections sections()
    {
        return new ReportSections(buildConformanceDetails(), buildDatasetDetails(),
                buildIssueSummary(), issueDetailRows(buildIssueDetails()), buildRulesReport(),
                buildSkippedRules(), buildCombinedFindings());
    }


    /**
     * The v2 combined-finding rows — one object per {@link ValidationFinding}, carrying its
     * location plus its multiple rows. Exposed separately as well as through {@link #sections()}
     * because it is the one section with no v1 counterpart.
     *
     * @return the {@code Findings} rows, never {@code null}
     */
    public List<Map<String, Object>> combinedFindings()
    {
        return buildCombinedFindings();
    }


    private static List<Map<String, Object>> issueDetailRows(List<IssueDetail> details)
    {
        List<Map<String, Object>> out = new ArrayList<>(details.size());
        for (IssueDetail d : details)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(FIELD_CORE_ID, d.coreId());
            m.put("message", d.message());
            m.put("executability", d.executability());
            m.put("dataset", d.dataset());
            m.put("USUBJID", d.usubjid());
            m.put("row", d.row());
            m.put("SEQ", d.seq());
            m.put("variables", d.variables());
            m.put("values", d.values());
            // ⚠ Additive Java extension, and the field whose absence would make a
            // sections -> JSON-document mapping lossy. XLSX ignores it (DETAIL_COLUMNS does not
            // name it); the JSON row map must carry it, last, exactly as the record does.
            m.put("domain", d.domain());
            out.add(m);
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Section builders
    // ------------------------------------------------------------------


    private Map<String, @Nullable Object> buildConformanceDetails()
    {
        Map<String, @Nullable Object> m = new LinkedHashMap<>();
        Conformance c = conformance != null ? conformance : Conformance.builder().build();

        putIfNotNull(m, "Report_Generation", c.reportGeneration != null ? c.reportGeneration
                : LocalDateTime.now(ZoneOffset.UTC).withNano(0).format(REPORT_TS));
        if (c.totalRuntimeSeconds != null)
        {
            m.put("Total_Runtime",
                    String.format(Locale.ROOT, "%.2f seconds", c.totalRuntimeSeconds));
        }
        putIfNotNull(m, "CORE_Engine_Version", c.coreEngineVersion);
        m.put("Issue_Limit_Per_Rule",
                c.issueLimitPerRule != null ? c.issueLimitPerRule.toString() : "None");
        m.put("Issue_Limit_Per_Dataset", c.issueLimitPerDataset ? "True" : "None");
        // Python emits this as a placeholder null.
        // v1 is FROZEN (owner ruling, 2026-08-11). Issue_Limit_Per_Sheet is always null here by
        // decision, not by oversight, even though XlsxReportWriter substitutes a real limit into
        // the same section map: v1 is a published consumer schema and changing it silently is the
        // failure mode. The fix belongs in v2. Nothing acts on v1.
        m.put("Issue_Limit_Per_Sheet", null);

        putIfNotNull(m, "Standard",
                c.standard != null ? c.standard.toUpperCase(Locale.ROOT) : null);
        putIfNotNull(m, "Sub_Standard", c.subStandard);
        if (c.version != null)
        {
            m.put("Version", "V" + c.version.replace("-", "."));
        }
        putIfNotNull(m, "TIG_Use_Case", c.tigUseCase);
        m.put("CT_Version", c.ctVersion != null ? c.ctVersion : "");
        putIfNotNull(m, "Define_XML_Version", c.defineXmlVersion);
        // Fix #369 — present ONLY on a run whose CDISC Library could not be consulted, so no
        // healthy report gains a key and the frozen v1 shape is unchanged for every existing
        // consumer. It exists because the alternative is the defect this fix closes: the log told
        // the truth about a degraded library while the report — the artefact anyone actually reads
        // — said nothing at all. With the Define-XML opt-in engaged it is also the record that the
        // basis was the sponsor's declaration rather than the standard's.
        putIfNotNull(m, "Library_Metadata_Basis", c.libraryMetadataBasis);
        // D13 item 1 — same contract as Library_Metadata_Basis, one input over: present ONLY on
        // a run in which some dictionary rule could not be answered, naming what loaded, what did
        // not and why, and the answerable count. Unlike its precedent it also reaches the XLSX
        // (Conformance Details row 21) and the REST projection — the report is the artefact
        // anyone actually reads, and under D12 the degraded state is the default.
        putIfNotNull(m, "Dictionary_Basis", c.dictionaryBasis);

        putIfNotNull(m, "UNII_Version", c.uniiVersion);
        putIfNotNull(m, "Med_RT_Version", c.medRtVersion);
        putIfNotNull(m, "MedDRA_Version", c.meddraVersion);
        putIfNotNull(m, "WHODRUG_Version", c.whodrugVersion);
        putIfNotNull(m, "SNOMED_Version", c.snomedVersion);
        putIfNotNull(m, "LOINC_Version", c.loincVersion);
        // Seven dictionary types, seven fields — neoplasm had none (§2.5).
        putIfNotNull(m, "Neoplasm_Version", c.neoplasmVersion);

        return m;
    }


    private List<Map<String, Object>> buildDatasetDetails()
    {
        List<Map<String, Object>> out = new ArrayList<>(datasets.size());
        for (DatasetInfo d : datasets)
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("filename", d.filename());
            m.put("label", d.label());
            m.put("path", d.path());
            m.put("modification_date", d.modificationDate());
            m.put("size_kb", d.sizeKb() != null ? d.sizeKb() : 0.0);
            m.put("length", d.length());
            m.put("domain", d.domain());
            m.put("columns", d.columns());
            // Per-dataset wall-clock runtime in ms (-1 when not measured / pre-feature).
            m.put("runtime_ms", datasetRuntimesMillis
                    .getOrDefault(datasetRuntimeKey(d.filename(), d.domain()), -1L));
            out.add(m);
        }
        return out;
    }


    private List<Map<String, Object>> buildIssueSummary()
    {
        List<Map<String, Object>> out = new ArrayList<>();
        if (report == null)
        {
            return out;
        }
        // Two-pass roll-up for Python parity. Findings whose core_id is an SDTM `--`-prefix
        // expansion (in bundledCoreIds — one per domain, all sharing the base rule's bare CORE id,
        // e.g. `CORE-000767`) collapse into one summary row keyed by (core_id, message), with the
        // per-domain dataset names joined alphabetically into a single comma-separated string.
        // Every other id keeps its per-dataset rows. Issue_Details and Rules_Report are unaffected.
        Map<List<String>, BundleAccumulator> grouped = new LinkedHashMap<>();
        for (ValidationReportMember member : report.getMembers())
        {
            String domain = member.getDomain();
            if (domain == null || domain.isEmpty())
            {
                continue;
            }
            String datasetLabel = datasetLabelFor(member);
            for (ValidationFinding finding : member.getFindings())
            {
                if (isEngineError(finding))
                {
                    continue;
                }
                int rowCount = finding.getRowCount();
                if (rowCount <= 0)
                {
                    continue;
                }
                String originalCoreId = finding.getRuleId();
                boolean bundleAcrossDatasets = originalCoreId != null
                        && bundledCoreIds.contains(originalCoreId);
                String message = finding.getMessage();
                // SDTM `--` expansions (Python parity): bundle by (core_id, message) across
                // datasets. Every other id (ordinary CORE/CDISC-AD rules, synthetic GEN-*, wildcard
                // expansions) includes the dataset in the key so its per-dataset rows survive.
                String coreIdKey = originalCoreId != null ? originalCoreId : "";
                String datasetLabelKey = datasetLabel != null ? datasetLabel : "";
                String messageKey = message != null ? message : "";
                List<String> key = bundleAcrossDatasets ? List.of(coreIdKey, messageKey)
                        : List.of(coreIdKey, datasetLabelKey, messageKey);
                BundleAccumulator acc = grouped.computeIfAbsent(key,
                        _ -> new BundleAccumulator(originalCoreId, message));
                acc.addDataset(datasetLabel);
                acc.addDomain(domain);
                acc.addIssues(rowCount);
            }
        }
        for (BundleAccumulator acc : grouped.values())
        {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("dataset", acc.joinedDatasets());
            row.put(FIELD_CORE_ID, acc.coreId);
            row.put("message", acc.message);
            row.put("issues", acc.issues);
            row.put("domain", acc.joinedDomains());
            out.add(row);
        }
        out.sort(Comparator.<Map<String, Object>, String> comparing(r -> nullSafe(r, "dataset"))
                .thenComparing(r -> nullSafe(r, FIELD_CORE_ID)));
        return out;
    }

    /** Mutable accumulator for one bundled {@code Issue_Summary} row. */
    private static final class BundleAccumulator
    {

        private final @Nullable String coreId;

        private final @Nullable String message;

        // TreeSet keeps the dataset names sorted alphabetically with no further work.
        private final java.util.TreeSet<String> datasets = new java.util.TreeSet<>();

        // Domains contributing to this bundled row, sorted to match the joined-datasets ordering.
        private final java.util.TreeSet<String> domains = new java.util.TreeSet<>();

        private int issues;

        BundleAccumulator(@Nullable String aCoreId, @Nullable String aMessage)
        {
            coreId = aCoreId;
            message = aMessage;
        }


        void addDataset(String label)
        {
            if (label != null && !label.isEmpty())
            {
                datasets.add(label);
            }
        }


        void addDomain(String domain)
        {
            if (domain != null && !domain.isEmpty())
            {
                domains.add(domain);
            }
        }


        void addIssues(int n)
        {
            issues += n;
        }


        String joinedDatasets()
        {
            return String.join(", ", datasets);
        }


        String joinedDomains()
        {
            return String.join(", ", domains);
        }
    }

    /**
     * Builds the v1 {@code Issue_Details} rows — one row per finding row, sorted by
     * {@code (core_id, dataset)}. Findings that carry no rows contribute nothing.
     *
     * <p>
     * <b>v1 is FROZEN (owner ruling, 2026-08-11).</b> Zero-row findings are omitted from
     * {@code Issue_Details} <em>by decision, not by oversight</em> — v1 is a published consumer
     * schema and changing it silently is the failure mode. The fix belongs in <b>v2</b>, whose
     * {@code Findings} array ({@link #buildCombinedFindings()}) already keeps dataset-scoped
     * zero-row findings as virtual findings with an empty {@code rows[]}. Nothing acts on v1.
     * </p>
     *
     * @return the {@code Issue_Details} records, never {@code null}
     */
    private List<IssueDetail> buildIssueDetails()
    {
        List<IssueDetail> out = new ArrayList<>();
        if (report == null)
        {
            return out;
        }
        for (ValidationReportMember member : report.getMembers())
        {
            String domain = member.getDomain();
            if (domain == null || domain.isEmpty())
            {
                continue;
            }
            String datasetLabel = datasetLabelFor(member);
            for (ValidationFinding finding : member.getFindings())
            {
                boolean engineError = isEngineError(finding);
                List<String> outputVars = collectOutputVariables(finding);
                RowFindingSlab slab = finding.getRows();
                List<String> names = finding.getVariableNames();
                if (slab.rowCount() == 0)
                {
                    // v1 is FROZEN (owner ruling, 2026-08-11): zero-row findings are dropped from
                    // Issue_Details by decision, not by oversight. v1 is a published consumer
                    // schema; the fix belongs in v2, where buildCombinedFindings keeps them.
                    continue;
                }
                String rawMessage = finding.getMessage();
                String message = engineError && rawMessage == null ? "" : rawMessage;
                for (int p = 0, rc = slab.rowCount(); p < rc; p++)
                {
                    RowCells c = extractRow(slab, p, names, domain, outputVars);
                    out.add(new IssueDetail(finding.getRuleId(), message,
                            finding.getExecutability(), datasetLabel, c.usubjid(),
                            c.row() > 0 ? c.row() : "", c.seq(), outputVars, c.values(), domain));
                }
            }
        }
        out.sort(Comparator.comparing((IssueDetail d) -> nullSafe(d.coreId()))
                .thenComparing(d -> nullSafe(d.dataset())));
        return out;
    }

    /** Per-row projection shared by v1 {@code Issue_Details} and v2 {@code Findings} rows. */
    private record RowCells(int row, String usubjid, String seq, List<String> values)
    {
    }

    /**
     * Projects one slab row into the per-row cells ({@code row}, {@code USUBJID}, {@code SEQ},
     * positional {@code values}) shared by the v1 {@code Issue_Details} record and the v2
     * {@code Findings} rows. Reuses v1's exact USUBJID/SEQ/values semantics so both outputs agree.
     */
    private static RowCells extractRow(RowFindingSlab slab, int p, List<String> names,
            String domain, List<String> outputVars)
    {
        Map<String, @Nullable String> values = slab.rowValues(p, names);
        String usubjid = values.getOrDefault("USUBJID", "");
        if (usubjid == null)
        {
            usubjid = "";
        }
        String seq = values.getOrDefault("SEQ", "");
        if (seq == null)
        {
            seq = "";
        }
        if (seq.isEmpty())
        {
            String v = values.get(domain.toUpperCase(Locale.ROOT) + "SEQ");
            seq = v != null ? v : "";
        }
        List<String> rowValues = new ArrayList<>(outputVars.size());
        for (String v : outputVars)
        {
            rowValues.add(processValue(values.get(v)));
        }
        if (rowValues.isEmpty())
        {
            rowValues = List.of("null");
        }
        int row = slab.rowIndexAt(p) >= 0 ? slab.rowIndexAt(p) + 1 : 0;
        return new RowCells(row, usubjid, seq, rowValues);
    }


    /**
     * Builds the v2 {@code Findings} array — one object per {@link ValidationFinding}, carrying its
     * never-null {@code location} plus its multiple rows. Unlike v1 {@code Issue_Details} (which
     * drops zero-row findings for Python parity), v2 keeps dataset-scoped zero-row findings (engine
     * / dataset-load errors) as <em>virtual findings</em> with an empty {@code rows[]}; only global
     * / library-level findings (empty domain) are excluded by the domain guard. Sorted by
     * {@code (core_id, dataset)}.
     */
    private List<Map<String, Object>> buildCombinedFindings()
    {
        List<Map<String, Object>> out = new ArrayList<>();
        if (report == null)
        {
            return out;
        }
        for (ValidationReportMember member : report.getMembers())
        {
            String domain = member.getDomain();
            if (domain == null || domain.isEmpty())
            {
                continue;
            }
            String datasetLabel = datasetLabelFor(member);
            for (ValidationFinding finding : member.getFindings())
            {
                out.add(buildCombinedFinding(finding, domain, datasetLabel));
            }
        }
        out.sort(Comparator.<Map<String, Object>, String> comparing(m -> nullSafe(m, FIELD_CORE_ID))
                .thenComparing(m -> nullSafe(m, "dataset")));
        return out;
    }


    private Map<String, Object> buildCombinedFinding(ValidationFinding finding, String domain,
            String datasetLabel)
    {
        RowFindingSlab slab = finding.getRows();
        List<String> names = finding.getVariableNames();
        List<String> outputVars = collectOutputVariables(finding);

        ValidationFindingLocation loc = finding.getLocation();
        List<String> keyVars = loc.getKeyVariableNames();

        List<Map<String, Object>> rows = new ArrayList<>(slab.rowCount());
        for (int p = 0, rc = slab.rowCount(); p < rc; p++)
        {
            RowCells c = extractRow(slab, p, names, domain, outputVars);
            Map<String, Object> rowMap = new LinkedHashMap<>();
            rowMap.put("row", c.row() > 0 ? c.row() : "");
            rowMap.put("USUBJID", c.usubjid());
            rowMap.put("SEQ", c.seq());
            // EC-40: the record key, omitted entirely when none resolved so the default
            // corej.findingKeys=off leaves this object byte-identical to a pre-EC-40 build.
            if (!keyVars.isEmpty())
            {
                rowMap.put("keys", finding.getRowKeys(p));
            }
            rowMap.put("values", c.values());
            rows.add(rowMap);
        }

        Map<String, Object> locMap = new LinkedHashMap<>();
        locMap.put("dataset", loc.getDataset());
        locMap.put("variables", loc.getVariableNames());
        if (!keyVars.isEmpty())
        {
            locMap.put("keyVariables", keyVars);
            locMap.put("keySource", loc.getKeySource());
        }

        boolean engineError = isEngineError(finding);
        String rawMessage = finding.getMessage();
        Map<String, Object> f = new LinkedHashMap<>();
        f.put(FIELD_CORE_ID, finding.getRuleId());
        f.put("message", engineError && rawMessage == null ? "" : rawMessage);
        f.put("executability", finding.getExecutability());
        f.put("dataset", datasetLabel);
        f.put("domain", domain);
        f.put("location", locMap);
        f.put("variables", outputVars);
        f.put("rows", rows);
        return f;
    }


    private List<Map<String, Object>> buildRulesReport()
    {
        List<Map<String, Object>> out = new ArrayList<>();

        // Build per-rule status from the report. Rules referenced by the report but absent from
        // the supplied rule list are still emitted; rules supplied but never seen in the report
        // are reported as SUCCESS.
        Map<String, RuleStatus> statusByCoreId = buildStatusByCoreId();
        // Rules that were skipped somewhere and executed nowhere — the only ones that may report
        // SKIPPED. Findings always win, so this is consulted only when the rule produced none.
        Set<String> skippedEverywhere = buildSkippedEverywhereCoreIds();

        // Emit one row per supplied rule. Status falls back to SUCCESS for rules that produced
        // no findings and were not skipped everywhere.
        Set<String> seen = new LinkedHashSet<>();
        for (Rule rule : rules)
        {
            String coreId = coreIdOf(rule);
            if (coreId == null)
            {
                continue;
            }
            seen.add(coreId);
            RuleStatus rs = statusByCoreId.get(coreId);
            String status = rs != null ? rs.status()
                    : skippedEverywhere.contains(coreId) ? STATUS_SKIPPED : STATUS_SUCCESS;
            // Rules with findings carry the message from the violation/error; rules with no
            // findings (SUCCESS) fall back to the rule's declared Outcome.Message so the report
            // tells consumers what the rule was checking — Python parity.
            String message = rs != null ? rs.message() : declaredMessageOf(rule);
            out.add(rulesReportRow(coreId, rule, status, message,
                    ruleRuntimesMillis.getOrDefault(coreId, -1L)));
        }
        // Emit any orphan findings (in report, not in rules list).
        for (Map.Entry<String, RuleStatus> e : statusByCoreId.entrySet())
        {
            if (!seen.contains(e.getKey()))
            {
                out.add(rulesReportRow(e.getKey(), null, e.getValue().status(),
                        e.getValue().message(), ruleRuntimesMillis.getOrDefault(e.getKey(), -1L)));
            }
        }

        out.sort(Comparator.comparing(r -> nullSafe(r, FIELD_CORE_ID)));
        return out;
    }


    /**
     * Builds the {@code Skipped_Rules} section: one row per skipped (rule × dataset) pair, in the
     * report's insertion order. {@code reason} carries the runner's full status message for
     * execution-time skips (e.g. {@code "Rule skipped — no Library access"}) and the scope
     * describer's text verbatim for generation-time skips (e.g. {@code "domain EX not in
     * Scope.Domains.Include [AE, CM]"}).
     */
    private List<Map<String, Object>> buildSkippedRules()
    {
        List<Map<String, Object>> out = new ArrayList<>();
        if (report == null)
        {
            return out;
        }
        for (net.cumba.datatable.report.SkippedRuleEntry e : report.getSkippedRules())
        {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put(FIELD_CORE_ID, e.getCoreId());
            m.put("dataset", e.getDataset());
            m.put("reason", e.getReason());
            out.add(m);
        }
        return out;
    }


    /**
     * The CORE ids that may report {@code SKIPPED} in {@code Rules_Report}: those carrying at least
     * one {@code Skipped_Rules} entry and <b>no</b> recorded execution anywhere in the run.
     *
     * <p>
     * Skipping is a per-(rule × dataset) verdict, so a skip entry on its own says nothing about the
     * rule as a whole. The report's executed set — every rule that reached the runner and was not
     * skipped, whether or not it produced a finding — is what separates the two cases:
     * </p>
     * <ul>
     * <li><b>skipped everywhere</b> ⇒ {@code SKIPPED}: the rule genuinely never ran, and reporting
     * {@code SUCCESS} would be a false assurance ("checked, no problem" where the truth is "could
     * not check");</li>
     * <li><b>skipped on some datasets, executed on others</b> ⇒ {@code SUCCESS}: the rule
     * <em>did</em> run and found nothing. Rolling that up to {@code SKIPPED} would hide a real
     * execution, and no information is lost — every {@code (core_id, dataset, reason)} triple stays
     * in {@code Skipped_Rules}.</li>
     * </ul>
     *
     * <p>
     * Both skip channels feed this: execution-time skips (a rule the runner declined to evaluate)
     * and generation-time skips (a rule whose scope did not match the dataset, filtered out before
     * execution). They are indistinguishable here by design — a rule skipped only through the
     * second channel never ran either.
     * </p>
     */
    private Set<String> buildSkippedEverywhereCoreIds()
    {
        if (report == null)
        {
            return Set.of();
        }
        List<net.cumba.datatable.report.SkippedRuleEntry> skipped = report.getSkippedRules();
        if (skipped.isEmpty())
        {
            return Set.of();
        }
        Set<String> executed = new LinkedHashSet<>(report.getExecutedCoreIds());
        Set<String> out = new LinkedHashSet<>();
        for (net.cumba.datatable.report.SkippedRuleEntry e : skipped)
        {
            String coreId = e.getCoreId();
            if (coreId != null && !executed.contains(coreId))
            {
                out.add(coreId);
            }
        }
        return out;
    }


    /**
     * Walks the validation report and collects per-rule status keyed by core id. Engine errors win
     * over issue-reported when the same rule is seen multiple times.
     */
    private Map<String, RuleStatus> buildStatusByCoreId()
    {
        Map<String, RuleStatus> statusByCoreId = new LinkedHashMap<>();
        if (report == null)
        {
            return statusByCoreId;
        }
        for (ValidationReportMember member : report.getMembers())
        {
            String domain = member.getDomain();
            if (domain == null || domain.isEmpty())
            {
                continue;
            }
            for (ValidationFinding finding : member.getFindings())
            {
                mergeFindingStatus(statusByCoreId, finding);
            }
        }
        return statusByCoreId;
    }


    private void mergeFindingStatus(Map<String, RuleStatus> statusByCoreId,
            ValidationFinding finding)
    {
        String coreId = finding.getRuleId();
        if (coreId == null)
        {
            return;
        }
        RuleStatus existing = statusByCoreId.get(coreId);
        String newStatus = isEngineError(finding) ? STATUS_EXECUTION_ERROR : STATUS_ISSUE_REPORTED;
        if (existing == null)
        {
            statusByCoreId.put(coreId, new RuleStatus(newStatus, finding.getMessage()));
        }
        else if (STATUS_EXECUTION_ERROR.equals(newStatus))
        {
            // Engine error wins over issue reported.
            statusByCoreId.put(coreId,
                    new RuleStatus(STATUS_EXECUTION_ERROR, finding.getMessage()));
        }
    }


    private static Map<String, Object> rulesReportRow(@Nullable String coreId, @Nullable Rule rule,
            String status, @Nullable String message, long runtimeMs)
    {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(FIELD_CORE_ID, coreId);
        m.put("version", "1");
        m.put("cdisc_rule_id", collectRuleIds(rule, "CDISC"));
        m.put("fda_rule_id", collectRuleIds(rule, "FDA"));
        m.put("message", message);
        m.put("status", status);
        // Summed per-rule runtime in ms across the datasets this rule ran on (-1 = not measured).
        m.put("runtime_ms", runtimeMs);
        return m;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------


    /**
     * Resolves the {@code dataset} string emitted into {@code Issue_Summary} /
     * {@code Issue_Details}. Prefers the source file name (e.g. {@code "ae.xpt"}) for parity with
     * the Python report; falls back to the upper-cased domain when the member carries no file name
     * (library-level findings, synthesised members).
     */
    private static String datasetLabelFor(ValidationReportMember member)
    {
        String fileName = member.getFileName();
        if (fileName != null && !fileName.isEmpty())
        {
            return fileName;
        }
        return member.getDomain();
    }


    private static List<String> collectOutputVariables(ValidationFinding finding)
    {
        // Schema is on the finding itself now; exclude the stable row-identity columns.
        Set<String> out = new LinkedHashSet<>();
        for (String name : finding.getVariableNames())
        {
            if ("USUBJID".equalsIgnoreCase(name) || "SEQ".equalsIgnoreCase(name))
            {
                continue;
            }
            out.add(name);
        }
        return new ArrayList<>(out);
    }


    /**
     * Mirrors Python {@code BaseReportData.process_values}: nulls and blanks become the literal
     * string {@code "null"}, otherwise the trimmed value is kept.
     *
     * <p>
     * Right-trim only: leading whitespace is intentional data and must be preserved in the report
     * (rule {@code CORE-000867} flags text variables with leading spaces — without preserving them,
     * every flagged value would render with the leading whitespace stripped and the finding would
     * be useless to investigate). Trailing whitespace is fixed-width padding from the source format
     * and is safe to strip for report-display purposes.
     * </p>
     */
    private static String processValue(@Nullable String value)
    {
        if (value == null)
        {
            return "null";
        }
        String trimmed = value.stripTrailing();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("nan"))
        {
            return "null";
        }
        return trimmed;
    }


    private static boolean isEngineError(ValidationFinding finding)
    {
        return finding.getKind() == net.cumba.datatable.report.FindingKind.ENGINE_ERROR;
    }


    private static @Nullable String coreIdOf(Rule rule)
    {
        return rule == null ? null : rule.effectiveId();
    }


    /**
     * Returns the rule's declared {@code Outcome.Message}, or {@code null} when the rule has no
     * outcome / no message. Used as the {@code Rules_Report.message} fallback for SUCCESS rows so
     * the report carries the rule's intent text on every row, matching Python's behaviour.
     */
    private static @Nullable String declaredMessageOf(Rule rule)
    {
        if (rule == null || rule.getOutcome() == null)
        {
            return null;
        }
        return rule.getOutcome().getMessage();
    }


    /**
     * Joins the published rule ids for the given organization, sorted and comma-separated. Mirrors
     * {@code RuleValidationResult._get_rule_ids}.
     *
     * <p>
     * This is the oracle for the {@code cdisc_rule_id} / {@code fda_rule_id} columns of the xlsx
     * Rules Report, of the JSON report's {@code Rules_Report}, and of {@code GET
     * /api/checks/{id}/rules}. It is <b>public rather than private</b> so
     * {@code ReleasedRuleIdColumnsTest} (in {@code cumba-oss-corej-rules}, which owns the corpus)
     * can assert that a released package and its authored source produce identical columns — the
     * whole justification for the §10 strip of
     * {@code plans/PLAN-rules-corpus-build-integration.md}. Computing the columns a second way in
     * the test would have guarded the copy, not the code.
     * </p>
     *
     * @param rule
     *            the rule, or {@code null}
     * @param organization
     *            the publishing organization, e.g. {@code "CDISC"} or {@code "FDA"}
     * @return the ids, comma-separated and sorted; empty when there are none
     */
    public static String collectRuleIds(@Nullable Rule rule, String organization)
    {
        if (rule == null)
        {
            return "";
        }
        List<Authority> authorities = rule.getAuthorities();
        if (authorities == null)
        {
            return "";
        }
        Set<String> ids = new java.util.TreeSet<>();
        for (Authority authority : authorities)
        {
            if (!organization.equals(authority.getOrganization()))
            {
                continue;
            }
            collectRuleIdsFromAuthority(authority, ids);
        }
        return String.join(", ", ids);
    }


    /**
     * ⛔⛔ <b>Both authority shapes, and neither may be assumed absent.</b> A released package
     * carries the flat {@code Rule_Ids} and no {@code Standards} at all
     * ({@code ReleaseShapeTrimmer}); the authored source, the CDISC-Library ingestion path
     * ({@code LibraryRuleMapper}) and the rule editor carry the nested tree and no
     * {@code Rule_Ids}. This method used to {@code return} on a null {@code Standards}, which
     * silently blanked both report columns for every released rule — no exception, no log, because
     * an unbound {@code Rule_Ids} key is swallowed by {@code Rule}'s {@code @JsonAnySetter} and
     * {@code FAIL_ON_UNKNOWN_PROPERTIES} is off.
     */
    private static void collectRuleIdsFromAuthority(Authority authority, Set<String> ids)
    {
        List<String> flat = authority.getRuleIds();
        if (flat != null)
        {
            for (String id : flat)
            {
                if (id != null)
                {
                    ids.add(id);
                }
            }
        }
        if (authority.getStandards() == null)
        {
            return;
        }
        for (AuthorityStandard standard : authority.getStandards())
        {
            if (standard.getReferences() == null)
            {
                continue;
            }
            for (Reference reference : standard.getReferences())
            {
                RuleIdentifier ri = reference.getRuleIdentifier();
                if (ri != null && ri.getId() != null)
                {
                    ids.add(ri.getId());
                }
            }
        }
    }


    private static void putIfNotNull(Map<String, @Nullable Object> m, String key,
            @Nullable Object value)
    {
        if (value != null)
        {
            m.put(key, value);
        }
    }


    private static String nullSafe(Map<String, Object> m, String key)
    {
        Object v = m.get(key);
        return v != null ? v.toString() : "";
    }


    private static String nullSafe(@Nullable String s)
    {
        return s != null ? s : "";
    }

    // ------------------------------------------------------------------
    // Value types
    // ------------------------------------------------------------------

    /**
     * Conformance metadata describing the validation run. All fields are optional; null values are
     * omitted from the output (except {@code Issue_Limit_Per_Sheet} which is always emitted as
     * null, and {@code CT_Version} which defaults to {@code ""}).
     */
    public static final class Conformance
    {

        private final @Nullable String reportGeneration;

        private final @Nullable Double totalRuntimeSeconds;

        private final @Nullable String coreEngineVersion;

        private final @Nullable Object issueLimitPerRule;

        private final boolean issueLimitPerDataset;

        private final @Nullable String standard;

        private final @Nullable String subStandard;

        private final @Nullable String version;

        private final @Nullable String tigUseCase;

        private final @Nullable String ctVersion;

        private final @Nullable String defineXmlVersion;

        private final @Nullable String libraryMetadataBasis;

        private final @Nullable String uniiVersion;

        private final @Nullable String medRtVersion;

        private final @Nullable String meddraVersion;

        private final @Nullable String whodrugVersion;

        private final @Nullable String snomedVersion;

        private final @Nullable String loincVersion;

        private final @Nullable String neoplasmVersion;

        private final @Nullable String dictionaryBasis;

        private Conformance(Builder b)
        {
            reportGeneration = b.reportGeneration;
            totalRuntimeSeconds = b.totalRuntimeSeconds;
            coreEngineVersion = b.coreEngineVersion;
            issueLimitPerRule = b.issueLimitPerRule;
            issueLimitPerDataset = b.issueLimitPerDataset;
            standard = b.standard;
            subStandard = b.subStandard;
            version = b.version;
            tigUseCase = b.tigUseCase;
            ctVersion = b.ctVersion;
            defineXmlVersion = b.defineXmlVersion;
            libraryMetadataBasis = b.libraryMetadataBasis;
            uniiVersion = b.uniiVersion;
            medRtVersion = b.medRtVersion;
            meddraVersion = b.meddraVersion;
            whodrugVersion = b.whodrugVersion;
            snomedVersion = b.snomedVersion;
            loincVersion = b.loincVersion;
            neoplasmVersion = b.neoplasmVersion;
            dictionaryBasis = b.dictionaryBasis;
        }


        /**
         * D13 item 1 — the run-level dictionary degradation line, or {@code null} on a run whose
         * every dictionary rule could be answered. Exposed so the CLI (Phase 6b) can print the same
         * line to stderr that the report carries as {@code Dictionary_Basis}.
         */
        public @Nullable String dictionaryBasis()
        {
            return dictionaryBasis;
        }


        public static Builder builder()
        {
            return new Builder();
        }

        public static final class Builder
        {

            private @Nullable String reportGeneration;

            private @Nullable Double totalRuntimeSeconds;

            private @Nullable String coreEngineVersion;

            private @Nullable Object issueLimitPerRule;

            private boolean issueLimitPerDataset;

            private @Nullable String standard;

            private @Nullable String subStandard;

            private @Nullable String version;

            private @Nullable String tigUseCase;

            private @Nullable String ctVersion;

            private @Nullable String defineXmlVersion;

            private @Nullable String libraryMetadataBasis;

            private @Nullable String uniiVersion;

            private @Nullable String medRtVersion;

            private @Nullable String meddraVersion;

            private @Nullable String whodrugVersion;

            private @Nullable String snomedVersion;

            private @Nullable String loincVersion;

            private @Nullable String neoplasmVersion;

            private @Nullable String dictionaryBasis;

            public Builder reportGeneration(@Nullable String s)
            {
                reportGeneration = s;
                return this;
            }


            public Builder totalRuntimeSeconds(double s)
            {
                totalRuntimeSeconds = s;
                return this;
            }


            public Builder coreEngineVersion(@Nullable String s)
            {
                coreEngineVersion = s;
                return this;
            }


            public Builder issueLimitPerRule(@Nullable Object o)
            {
                issueLimitPerRule = o;
                return this;
            }


            public Builder issueLimitPerDataset(boolean b)
            {
                issueLimitPerDataset = b;
                return this;
            }


            public Builder standard(@Nullable String s)
            {
                standard = s;
                return this;
            }


            public Builder subStandard(@Nullable String s)
            {
                subStandard = s;
                return this;
            }


            public Builder version(@Nullable String s)
            {
                version = s;
                return this;
            }


            public Builder tigUseCase(@Nullable String s)
            {
                tigUseCase = s;
                return this;
            }


            public Builder ctVersion(@Nullable String s)
            {
                ctVersion = s;
                return this;
            }


            public Builder defineXmlVersion(@Nullable String s)
            {
                defineXmlVersion = s;
                return this;
            }


            /**
             * Fix #369 — what stood in for the CDISC Library on a degraded run, or {@code null}
             * (the normal case) when the Library itself answered.
             */
            public Builder libraryMetadataBasis(@Nullable String s)
            {
                libraryMetadataBasis = s;
                return this;
            }


            public Builder uniiVersion(@Nullable String s)
            {
                uniiVersion = s;
                return this;
            }


            public Builder medRtVersion(@Nullable String s)
            {
                medRtVersion = s;
                return this;
            }


            public Builder meddraVersion(@Nullable String s)
            {
                meddraVersion = s;
                return this;
            }


            public Builder whodrugVersion(@Nullable String s)
            {
                whodrugVersion = s;
                return this;
            }


            public Builder snomedVersion(@Nullable String s)
            {
                snomedVersion = s;
                return this;
            }


            public Builder loincVersion(@Nullable String s)
            {
                loincVersion = s;
                return this;
            }


            public Builder neoplasmVersion(@Nullable String s)
            {
                neoplasmVersion = s;
                return this;
            }


            public Builder dictionaryBasis(@Nullable String s)
            {
                dictionaryBasis = s;
                return this;
            }


            public Conformance build()
            {
                return new Conformance(this);
            }
        }
    }


    /**
     * Per-dataset metadata for the {@code Dataset_Details} section.
     *
     * @param filename
     *            file name (e.g. {@code "ae.xpt"})
     * @param label
     *            human-readable dataset label
     * @param path
     *            parent directory of the dataset file
     * @param modificationDate
     *            ISO timestamp of the file modification date
     * @param sizeKb
     *            file size in kilobytes ({@code bytes / 1000})
     * @param length
     *            number of records in the dataset
     * @param domain
     *            the dataset / domain name (e.g. {@code "AE"}); distinguishes the datasets of a
     *            multi-dataset file (Excel sheets, multi-member XPT) that share a {@code filename}
     * @param columns
     *            number of columns (variables) in the dataset, or {@code null} when unknown
     */
    public record DatasetInfo(@Nullable String filename, @Nullable String label,
            @Nullable String path, @Nullable String modificationDate, Double sizeKb, Long length,
            String domain, Integer columns)
    {

        /*
         * The requireNonNull that used to live here contradicted the component's own @Nullable
         * declaration, and SpotBugs 4.10 caught it (NP_PARAMETER_MUST_BE_NONNULL_BUT_MARKED_AS_
         * NULLABLE). It was not merely a documentation mismatch: the only production caller is
         * StudyValidationService.buildDatasetInfos, which passes DatasetEntry.fileName() -- itself
         * @Nullable, and genuinely null for a dataset with no URI or an empty URI path
         * (fileNameOf returns null there), i.e. any table not backed by a file. So the guard
         * turned a non-file-backed study into a NullPointerException at report assembly.
         *
         * Removed rather than pushed onto the caller: this is a Jackson DTO whose filename is
         * serialised into the report document and dereferenced by nobody, and every sibling
         * component is already @Nullable for the same "unknown" reason.
         *
         * NullAway cannot see this class of defect -- passing a @Nullable value to a @Nullable
         * parameter is legal, and requireNonNull on a nullable value is legal defensive code.
         * It took the SpotBugs bump to surface it.
         *
         * ⚠ This is an availability-for-visibility swap, not a pure NPE removal. Downstream
         * consumers were checked and all are null-tolerant, but one of them CHANGES BEHAVIOUR:
         * DatasetGroupAssembler (a downstream REST service) does `if (filename == null) continue;`, so a
         * non-file-backed dataset is now silently OMITTED from the REST grouped view where it
         * previously crashed report assembly outright. That is the better failure mode, but it
         * is a behaviour change and should not surprise anyone reading this later.
         */
    }


    /**
     * One row of the {@code Issue_Details} section. Public for Jackson — the field order matches
     * the Python output, with an additive trailing {@code domain} (the dataset/domain name, which
     * disambiguates findings of a multi-dataset file that share the filename {@code dataset}
     * label).
     */
    public record IssueDetail(@Nullable String coreId, @Nullable String message,
            @Nullable String executability, String dataset, String usubjid, Object row, String seq,
            List<String> variables, List<String> values, String domain)
    {

        @Override
        @com.fasterxml.jackson.annotation.JsonProperty(FIELD_CORE_ID)
        public @Nullable String coreId()
        {
            return coreId;
        }


        @Override
        @com.fasterxml.jackson.annotation.JsonProperty("message")
        public @Nullable String message()
        {
            return message;
        }


        @Override
        @com.fasterxml.jackson.annotation.JsonProperty("executability")
        public @Nullable String executability()
        {
            return executability;
        }


        @Override
        @com.fasterxml.jackson.annotation.JsonProperty("dataset")
        public String dataset()
        {
            return dataset;
        }


        @Override
        @com.fasterxml.jackson.annotation.JsonProperty("USUBJID")
        public String usubjid()
        {
            return usubjid;
        }


        @Override
        @com.fasterxml.jackson.annotation.JsonProperty("row")
        public Object row()
        {
            return row;
        }


        @Override
        @com.fasterxml.jackson.annotation.JsonProperty("SEQ")
        public String seq()
        {
            return seq;
        }


        @Override
        @com.fasterxml.jackson.annotation.JsonProperty("variables")
        public List<String> variables()
        {
            return variables;
        }


        @Override
        @com.fasterxml.jackson.annotation.JsonProperty("values")
        public List<String> values()
        {
            return values;
        }


        @Override
        @com.fasterxml.jackson.annotation.JsonProperty("domain")
        public String domain()
        {
            return domain;
        }
    }


    private record RuleStatus(String status, @Nullable String message)
    {
    }
}
