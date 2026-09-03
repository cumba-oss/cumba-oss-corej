package net.cumba.corej.define.conformance.engine;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Path;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.RuleExecution;
import org.jspecify.annotations.Nullable;

/**
 * Serialises a {@link DefineConformanceReport} to pretty UTF-8 JSON (plan §3.4). The tree is built
 * explicitly rather than by bean introspection: {@link ConformanceFinding} is a Lombok
 * {@code @Value} type (no default constructor), so relying on Jackson's bean mapper is fragile and
 * would emit the fields in reflection order. Building an {@link ObjectNode} keeps the field order
 * stable and lets the writer omit the optional {@code element}/{@code attribute}/{@code xpath}/
 * {@code line}/{@code column} keys when null.
 *
 * <pre>
 * {
 *   "defineXml": "...",
 *   "defineVersion": "2.1",
 *   "generatedAt": "2026-07-03T…Z",
 *   "summary": {
 *     "findingsByCategory": { "SCHEMA": { "ERROR": 3 }, "PMDA": { "REJECT": 1 } },
 *     "executionsByStatus": { "EXECUTED": 40, "SKIPPED_MISSING_CT": 6 },
 *     "totalFindings": 4
 *   },
 *   "findings": [ { "ruleId": …, "xpath": …, "message": …, "category": …, "severity": … } ],
 *   "ruleExecutions": [ { "ruleId": …, "status": …, "findingCount": 0 } ]
 * }
 * </pre>
 */
public final class JsonReportWriter
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private JsonReportWriter()
    {
    }


    /** Writes the report as pretty JSON to a file. */
    public static void write(DefineConformanceReport aReport, Path aOutput) throws IOException
    {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(aOutput.toFile(), toTree(aReport));
    }


    /** Writes the report as pretty JSON to a stream (the stream is not closed). */
    public static void write(DefineConformanceReport aReport, OutputStream aOut) throws IOException
    {
        MAPPER.writerWithDefaultPrettyPrinter().writeValue(aOut, toTree(aReport));
    }


    /** Renders the report as a pretty JSON string. */
    public static String toJson(DefineConformanceReport aReport)
    {
        try
        {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(toTree(aReport));
        }
        catch (IOException e)
        {
            // Serialising an in-memory node tree cannot perform IO; rethrow defensively.
            throw new IllegalStateException("cannot serialise report", e);
        }
    }


    private static ObjectNode toTree(DefineConformanceReport aReport)
    {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("defineXml", aReport.defineXml());
        root.put("defineVersion", aReport.defineVersion());
        root.put("generatedAt", aReport.generatedAt().toString());

        ObjectNode summary = root.putObject("summary");
        ObjectNode byCategory = summary.putObject("findingsByCategory");
        aReport.findingsByCategory().forEach((category, bySeverity) ->
        {
            ObjectNode severityNode = byCategory.putObject(category.name());
            bySeverity.forEach((severity, count) -> severityNode.put(severity.name(), count));
        });
        ObjectNode byStatus = summary.putObject("executionsByStatus");
        aReport.executionsByStatus().forEach((status, count) -> byStatus.put(status.name(), count));
        summary.put("totalFindings", aReport.totalFindings());

        ArrayNode findings = root.putArray("findings");
        for (ConformanceFinding finding : aReport.findings())
        {
            ObjectNode node = findings.addObject();
            node.put("ruleId", finding.getRuleId());
            putIfPresent(node, "element", finding.getElement());
            putIfPresent(node, "attribute", finding.getAttribute());
            putIfPresent(node, "xpath", finding.getXpath());
            putIfPresent(node, "line", finding.getLine());
            putIfPresent(node, "column", finding.getColumn());
            node.put("message", finding.getMessage());
            node.put("category", finding.getCategory().name());
            node.put("severity", finding.getSeverity().name());
        }

        ArrayNode executions = root.putArray("ruleExecutions");
        for (RuleExecution execution : aReport.executions())
        {
            ObjectNode node = executions.addObject();
            node.put("ruleId", execution.getRuleId());
            node.put("status", execution.getStatus().name());
            node.put("findingCount", execution.getFindingCount());
        }
        return root;
    }


    private static void putIfPresent(ObjectNode aNode, String aKey, @Nullable String aValue)
    {
        if (aValue != null)
        {
            aNode.put(aKey, aValue);
        }
    }


    private static void putIfPresent(ObjectNode aNode, String aKey, @Nullable Integer aValue)
    {
        if (aValue != null)
        {
            aNode.put(aKey, aValue.intValue());
        }
    }

}
