package net.cumba.corej.core.report;

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
 * <li>{@code rulesReport} — {@code Rules_Report} rows ({@code core_id, version, cdisc_rule_id,
 * fda_rule_id, message, status, runtime_ms}, plus the v2-only D65 counts
 * {@code executed, skipped, errored} — see {@link #RULES_EXECUTED_COUNT} — and, on a rule with a
 * forward RELREC entry, the v2-only {@code relrec_key} — see {@link #RELREC_KEY}).</li>
 * <li>{@code skippedRules} — {@code Skipped_Rules} rows ({@code core_id, dataset, reason}); a Java
 * extension without a Python counterpart.</li>
 * <li>{@code combinedFindings} — the v2 {@code Findings} rows
 * ({@code core_id, message, executability, dataset, domain, location, variables, [columns], rows});
 * the one section with no v1 counterpart, and empty for a v1-only report. {@code columns} is the
 * additive, finding-level list of the raw columns the finding lists <em>by name without a
 * value</em>, present only when non-empty ({@code PLAN-finding-values-record-level-only}, owner Q-B
 * 2026-10-05) — so a record finding's object is byte-identical to a pre-P2 one.</li>
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
     * ⭐ <b>v2-only</b> key carrying the run's effective numeric-comparison tolerance (decision
     * <b>D13</b> of {@code PLAN-joined-column-typing}).
     *
     * <p>
     * Same study + same corpus + a different tolerance = different verdicts, so a finding that
     * cannot be reproduced from its own report is a traceability defect in an engine whose output
     * is evidence.
     * </p>
     *
     * <p>
     * ⛔ It is <b>v2-only by owner ruling, 2026-09-15</b>. {@code Conformance_Details} is otherwise
     * shared between the two documents, and v1 is a FROZEN published consumer schema (owner ruling
     * 2026-08-11, recorded at {@code ReportAssembler}'s {@code Issue_Limit_Per_Sheet}). A v1
     * document must not gain this key by any route — including the round trip through
     * {@link #fromExportDocument}, which is why {@link #toExportDocument} strips it.
     * </p>
     */
    static final String NUMERIC_TOLERANCE_DIGITS = "Numeric_Tolerance_Digits";

    /**
     * ⭐ <b>v2-only</b> {@code Rules_Report} row keys carrying the D65 execution counts — how often
     * the rule <b>executed</b> successfully, was <b>skipped</b>, and <b>errored</b>, each per (rule
     * × dataset).
     *
     * <p>
     * ⛔ v2-only for the same reason as {@link #NUMERIC_TOLERANCE_DIGITS}: v1 is a FROZEN published
     * consumer schema (owner ruling 2026-08-11), and D57 rules that per-binding / per-execution
     * detail lands in v2 or a new section, <b>never by widening v1</b>. The assembler puts the keys
     * into the shared {@code rulesReport} rows; {@link #toExportDocument} strips them so no v1
     * document — freshly assembled or round-tripped through {@link #fromExportDocument} — ever
     * carries them.
     * </p>
     */
    static final String RULES_EXECUTED_COUNT = "executed";

    /** See {@link #RULES_EXECUTED_COUNT}. */
    static final String RULES_SKIPPED_COUNT = "skipped";

    /** See {@link #RULES_EXECUTED_COUNT}. */
    static final String RULES_ERRORED_COUNT = "errored";

    /**
     * ⭐ <b>v2-only</b> {@code Rules_Report} row key stating the RELREC key a rule's forward
     * {@code RELREC} merge paired the related records on ({@code PLAN-merged-evaluation-table}
     * OD-9, "stated in the report's rule metadata"; {@code PLAN-merge-followups} FU-4 (a)): under
     * SDTM {@code STUDYID, USUBJID, RELID}, under SEND {@code STUDYID, RELID}, and when the run's
     * standard selects neither the inference the merge applied, spelled out. Present <b>only</b> on
     * the rows of rules with a forward RELREC entry (the keyless {@code Name: RELREC} entry): a
     * {@code Child} or keyed RELREC entry declares its own keys and states nothing.
     *
     * <p>
     * ⛔ v2-only for the same reason as {@link #RULES_EXECUTED_COUNT} (owner, FU-4: "v2 only"):
     * {@link #toExportDocument} strips it with the counts.
     * </p>
     */
    static final String RELREC_KEY = "relrec_key";

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
        // ⛔ v1 is FROZEN. Strip the v2-only tolerance key rather than assuming it is absent: a
        // document read back through fromExportDocument carries whatever its source had, so a v2
        // report re-projected to v1 would otherwise smuggle the key into the frozen schema.
        root.put(CONFORMANCE_DETAILS, withoutToleranceKey(conformanceDetails()));
        root.put(DATASET_DETAILS, datasetDetails());
        root.put(ISSUE_SUMMARY, issueSummary());
        root.put(ISSUE_DETAILS, issueDetails());
        root.put(RULES_REPORT, withoutV2OnlyRuleKeys(rulesReport()));
        root.put(SKIPPED_RULES, skippedRules());
        return root;
    }


    /**
     * Projects these sections into the <b>v2</b> combined-finding export document: the flat
     * {@code Issue_Details} is replaced by the combined {@code Findings} array and a
     * {@code Report_Version} discriminator leads the document. The shared metadata sections come
     * from the same assembly as v1.
     *
     * <p>
     * ⚠ {@code Conformance_Details} is the <b>one</b> section that is not byte-identical to v1: it
     * keeps {@link #NUMERIC_TOLERANCE_DIGITS} (D13), which {@link #toExportDocument} strips. Every
     * other shared section still is.
     * </p>
     *
     * <p>
     * ⚑ The key is put into the sections by {@code ReportAssembler}, <b>not</b> read from a static
     * here (review finding F7). Reading the live setting during projection made the output depend
     * on the JVM, which broke {@code JsonReportGoldenTest}'s deliberately environment-free fixture
     * — it has "no timestamp, no runtime and no environment in it" precisely so the comparison is a
     * real byte comparison. A document carries its own tolerance; the projection only routes it.
     * </p>
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


    /** {@code details} without the v2-only tolerance key, for the frozen v1 projection. */
    private static Map<String, @Nullable Object> withoutToleranceKey(
            Map<String, @Nullable Object> details)
    {
        if (!details.containsKey(NUMERIC_TOLERANCE_DIGITS))
        {
            return details;
        }
        Map<String, @Nullable Object> m = new LinkedHashMap<>(details);
        m.remove(NUMERIC_TOLERANCE_DIGITS);
        return m;
    }

    /** The v2-only {@code Rules_Report} row keys: the D65 counts and the RELREC key. */
    private static final List<String> V2_ONLY_RULE_KEYS = List.of(RULES_EXECUTED_COUNT,
            RULES_SKIPPED_COUNT, RULES_ERRORED_COUNT, RELREC_KEY);

    /**
     * {@code rows} without the v2-only keys — the D65 execution counts and the RELREC key
     * ({@link #RELREC_KEY}) — for the frozen v1 projection. Stripped rather than assumed absent,
     * for the same round-trip reason as {@link #withoutToleranceKey}: a document read back through
     * {@link #fromExportDocument} carries whatever its source had.
     */
    private static List<Map<String, Object>> withoutV2OnlyRuleKeys(List<Map<String, Object>> rows)
    {
        if (rows.stream().noneMatch(ReportSections::carriesV2OnlyRuleKey))
        {
            return rows;
        }
        List<Map<String, Object>> out = new ArrayList<>(rows.size());
        for (Map<String, Object> row : rows)
        {
            Map<String, Object> m = new LinkedHashMap<>(row);
            m.keySet().removeAll(V2_ONLY_RULE_KEYS);
            out.add(m);
        }
        return out;
    }


    private static boolean carriesV2OnlyRuleKey(Map<String, Object> row)
    {
        return V2_ONLY_RULE_KEYS.stream().anyMatch(row::containsKey);
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
