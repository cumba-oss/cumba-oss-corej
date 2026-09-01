package net.cumba.cdisc.core.report.json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import java.io.IOException;
import java.io.OutputStream;
import java.util.Map;
import net.cumba.cdisc.core.report.ReportSections;
import net.cumba.cdisc.core.report.ReportWriter;

/**
 * Serialises {@link ReportSections} as JSON in the exact shape produced by the Python CORE engine
 * ({@code cdisc_rules_engine.services.reporting.json_report.JsonReport}).
 *
 * <h2>This class owns bytes, and nothing else</h2>
 *
 * <p>
 * <em>What</em> the report says is decided by {@code ReportAssembler} in the engine; the
 * <em>document</em> — the nested map whose key order is the published schema — is
 * {@link ReportSections#toExportDocument()} / {@link ReportSections#toCombinedExportDocument()},
 * also in the engine, beside its {@code fromExportDocument} inverse. All that is left here is
 * handing that document to Jackson, which is why this module is the only one of the three layers
 * that a deployment can leave out.
 * </p>
 *
 * <h2>Document structure</h2>
 *
 * <pre>{@code
 * v1 ("json")   : Conformance_Details, Dataset_Details, Issue_Summary, Issue_Details,
 *                 Rules_Report, Skipped_Rules
 * v2 ("json-2") : Report_Version, Conformance_Details, Dataset_Details, Issue_Summary,
 *                 Findings, Rules_Report, Skipped_Rules
 * }</pre>
 *
 * <h2>v1 is FROZEN (owner ruling, 2026-08-11)</h2>
 *
 * <p>
 * Two v1 traits read like defects and are not. Zero-row findings are omitted from
 * {@code Issue_Details}, and {@code Issue_Limit_Per_Sheet} is always JSON {@code null} even though
 * the XLSX writer substitutes a real limit into the same section map. Both are <em>by decision, not
 * by oversight</em>: v1 is a <b>published consumer schema</b>, and changing it silently is the
 * failure mode. The fix belongs in v2, whose {@code Findings} array already keeps dataset-scoped
 * zero-row findings. Both sites are annotated in {@code ReportAssembler}, next to the code that
 * implements them.
 * </p>
 */
public final class JsonReportWriter implements ReportWriter
{

    private final boolean combined;

    /**
     * @param aCombined
     *            {@code true} for the v2 combined-finding document, {@code false} for v1
     */
    public JsonReportWriter(boolean aCombined)
    {
        combined = aCombined;
    }


    @Override
    public void write(ReportSections aSections, OutputStream aOut) throws IOException
    {
        Map<String, Object> document = combined ? aSections.toCombinedExportDocument()
                : aSections.toExportDocument();
        // A fresh mapper per write: this is a once-per-report call, and a shared mutable mapper
        // would be a concurrency hazard for no measurable gain.
        ObjectMapper mapper = new ObjectMapper();
        mapper.disable(SerializationFeature.FAIL_ON_EMPTY_BEANS);
        mapper.writeValue(aOut, document);
    }
}
