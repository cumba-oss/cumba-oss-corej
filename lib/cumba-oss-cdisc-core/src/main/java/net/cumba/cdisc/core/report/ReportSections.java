package net.cumba.cdisc.core.report;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The validation-report sheets in a neutral, map-based shape shared by every report writer — and
 * the <b>only</b> engine type a writer module needs, which is what lets the writers live outside
 * the engine at all ({@link ReportWriter}). Holding the sections as plain {@link Map}/{@link List}
 * structures (rather than the engine's {@code ValidationReport} model) lets both the CLI path
 * (which builds them from a live {@link ReportAssembler}) and the REST path (which only has the
 * persisted JSON document) feed the same writers, so the Excel and JSON outputs never drift apart.
 *
 * <p>
 * Field shapes mirror the Python / Java JSON report exactly:
 * </p>
 * <ul>
 * <li>{@code conformanceDetails} — flat {@code Conformance_Details} map (underscore keys → values,
 * e.g. {@code "Standard" → "SDTMIG"}).</li>
 * <li>{@code datasetDetails} — {@code Dataset_Details} rows
 * ({@code filename, label, path, modification_date, size_kb, length}).</li>
 * <li>{@code issueSummary} — {@code Issue_Summary} rows
 * ({@code dataset, core_id, message, issues}).</li>
 * <li>{@code issueDetails} — {@code Issue_Details} rows ({@code core_id, message, executability,
 * dataset, USUBJID, row, SEQ, variables, values}); {@code variables}/{@code values} are
 * {@code List<String>}.</li>
 * <li>{@code rulesReport} — {@code Rules_Report} rows
 * ({@code core_id, version, cdisc_rule_id, fda_rule_id, message, status}).</li>
 * <li>{@code skippedRules} — {@code Skipped_Rules} rows ({@code core_id, dataset, reason}); a Java
 * extension without a Python counterpart.</li>
 * <li>{@code combinedFindings} — the v2 {@code Findings} rows
 * ({@code core_id, message, executability, dataset, domain, location, variables, rows}); the one
 * section with no v1 counterpart, and empty for a v1-only report.</li>
 * </ul>
 *
 * @param conformanceDetails
 *            the {@code Conformance_Details} key/value map
 * @param datasetDetails
 *            the {@code Dataset_Details} rows
 * @param issueSummary
 *            the {@code Issue_Summary} rows
 * @param issueDetails
 *            the {@code Issue_Details} rows
 * @param rulesReport
 *            the {@code Rules_Report} rows
 * @param skippedRules
 *            the {@code Skipped_Rules} rows
 * @param combinedFindings
 *            the v2 {@code Findings} rows; empty when the source carried no v2 section
 */
public record ReportSections(Map<String, @Nullable Object> conformanceDetails,
        List<Map<String, Object>> datasetDetails, List<Map<String, Object>> issueSummary,
        List<Map<String, Object>> issueDetails, List<Map<String, Object>> rulesReport,
        List<Map<String, Object>> skippedRules, List<Map<String, Object>> combinedFindings)
{

    /**
     * Convenience constructor for a v1-only report: {@code combinedFindings} defaults to empty.
     * Keeps callers that predate the v2 section — and every consumer that only ever renders the
     * five Python sheets plus {@code Skipped_Rules} — compiling unchanged.
     *
     * @param conformanceDetails
     *            the {@code Conformance_Details} key/value map
     * @param datasetDetails
     *            the {@code Dataset_Details} rows
     * @param issueSummary
     *            the {@code Issue_Summary} rows
     * @param issueDetails
     *            the {@code Issue_Details} rows
     * @param rulesReport
     *            the {@code Rules_Report} rows
     * @param skippedRules
     *            the {@code Skipped_Rules} rows
     */
    public ReportSections(Map<String, @Nullable Object> conformanceDetails,
            List<Map<String, Object>> datasetDetails, List<Map<String, Object>> issueSummary,
            List<Map<String, Object>> issueDetails, List<Map<String, Object>> rulesReport,
            List<Map<String, Object>> skippedRules)
    {
        this(conformanceDetails, datasetDetails, issueSummary, issueDetails, rulesReport,
                skippedRules, List.of());
    }

    /** JSON section keys, in the Python sheet order. */
    static final String CONFORMANCE_DETAILS = "Conformance_Details";

    static final String DATASET_DETAILS = "Dataset_Details";

    static final String ISSUE_SUMMARY = "Issue_Summary";

    static final String ISSUE_DETAILS = "Issue_Details";

    static final String RULES_REPORT = "Rules_Report";

    static final String SKIPPED_RULES = "Skipped_Rules";

    /** The v2-only section key; absent from a v1 document. */
    static final String FINDINGS = "Findings";

    /** The v2 discriminator key and its only value. */
    static final String REPORT_VERSION = "Report_Version";

    private static final String REPORT_VERSION_2 = "2.0";

    /**
     * Projects these sections into the <b>v1</b> export document: the nested {@link LinkedHashMap}
     * whose key order <em>is</em> the published v1 schema
     * ({@code Conformance_Details, Dataset_Details, Issue_Summary, Issue_Details, Rules_Report,
     * Skipped_Rules}).
     *
     * <p>
     * This is the exact inverse of {@link #fromExportDocument}, and it lives here — beside its
     * inverse, in the engine — rather than in the JSON writer module on purpose: the document is
     * plain {@code Map}/{@code List} assembly with no serialisation library in sight, and it is the
     * shape the frozen v1 schema is specified in. A writer module owns only <em>document →
     * bytes</em>. It also keeps a report recovered from storage round-tripping through the same
     * code path as a freshly assembled one.
     * </p>
     *
     * @return the v1 export document
     */
    public Map<String, Object> toExportDocument()
    {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(CONFORMANCE_DETAILS, conformanceDetails());
        root.put(DATASET_DETAILS, datasetDetails());
        root.put(ISSUE_SUMMARY, issueSummary());
        root.put(ISSUE_DETAILS, issueDetails());
        root.put(RULES_REPORT, rulesReport());
        root.put(SKIPPED_RULES, skippedRules());
        return root;
    }


    /**
     * Projects these sections into the <b>v2</b> combined-finding export document: the flat
     * {@code Issue_Details} is replaced by the combined {@code Findings} array and a
     * {@code Report_Version} discriminator leads the document. The four shared metadata sections
     * come from the same assembly as v1, so they stay byte-identical to it.
     *
     * @return the v2 export document
     */
    public Map<String, Object> toCombinedExportDocument()
    {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put(REPORT_VERSION, REPORT_VERSION_2);
        root.put(CONFORMANCE_DETAILS, conformanceDetails());
        root.put(DATASET_DETAILS, datasetDetails());
        root.put(ISSUE_SUMMARY, issueSummary());
        root.put(FINDINGS, combinedFindings());
        root.put(RULES_REPORT, rulesReport());
        root.put(SKIPPED_RULES, skippedRules());
        return root;
    }


    /**
     * Builds a {@link ReportSections} from an assembled export document — the same nested
     * {@code Map<String,Object>} that {@link #toExportDocument()} produces (and that the REST layer
     * recovers by parsing the persisted report JSON). Missing or wrongly-typed sections degrade to
     * empty collections rather than throwing, so a partial document still renders.
     *
     * @param document
     *            the export document keyed by section name ({@code Conformance_Details}, …)
     * @return the recovered sections
     */
    public static ReportSections fromExportDocument(@Nullable Map<String, Object> document)
    {
        Map<String, Object> doc = document != null ? document : Map.of();
        return new ReportSections(asMap(doc.get(CONFORMANCE_DETAILS)),
                asRows(doc.get(DATASET_DETAILS)), asRows(doc.get(ISSUE_SUMMARY)),
                asRows(doc.get(ISSUE_DETAILS)), asRows(doc.get(RULES_REPORT)),
                asRows(doc.get(SKIPPED_RULES)), asRows(doc.get(FINDINGS)));
    }


    private static Map<String, @Nullable Object> asMap(@Nullable Object value)
    {
        if (value instanceof Map<?, ?> m)
        {
            Map<String, @Nullable Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet())
            {
                out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return new LinkedHashMap<>();
    }


    private static List<Map<String, Object>> asRows(@Nullable Object value)
    {
        List<Map<String, Object>> out = new ArrayList<>();
        if (value instanceof List<?> list)
        {
            for (Object element : list)
            {
                if (element instanceof Map<?, ?>)
                {
                    out.add(asMap(element));
                }
            }
        }
        return out;
    }
}
