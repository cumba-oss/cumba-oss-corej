package net.cumba.corej.define.conformance.engine;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.define.conformance.report.Category;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.ExecutionStatus;
import net.cumba.corej.define.conformance.report.RuleExecution;
import net.cumba.corej.define.conformance.report.Severity;

/**
 * The immutable outcome of one {@link DefineConformanceEngine} run (plan §3.4): the findings in
 * emission order (pre-pass, then global ordering, then rules) and one {@link RuleExecution} row per
 * shipped rule. The derived accessors summarise the two lists for report headers and the JSON
 * summary block.
 *
 * @param defineXml
 *            the validated file, as supplied (path string)
 * @param defineVersion
 *            the effective Define-XML version ({@code "2.0"} / {@code "2.1"}) the rules ran against
 * @param generatedAt
 *            when this report was produced
 * @param findings
 *            every finding, in emission order
 * @param executions
 *            per-rule execution rows (empty when the document could not be parsed)
 */
public record DefineConformanceReport(String defineXml, String defineVersion, Instant generatedAt,
        List<ConformanceFinding> findings, List<RuleExecution> executions)
{

    public DefineConformanceReport
    {
        findings = List.copyOf(findings);
        executions = List.copyOf(executions);
    }


    /** Total findings across all categories and severities. */
    public int totalFindings()
    {
        return findings.size();
    }


    /** Finding counts keyed by category, then severity (only non-zero cells present). */
    public Map<Category, Map<Severity, Long>> findingsByCategory()
    {
        Map<Category, Map<Severity, Long>> out = new EnumMap<>(Category.class);
        for (ConformanceFinding finding : findings)
        {
            out.computeIfAbsent(finding.getCategory(), _ -> new EnumMap<>(Severity.class))
                    .merge(finding.getSeverity(), 1L, Long::sum);
        }
        return out;
    }


    /** How many rules landed in each execution status (only non-zero statuses present). */
    public Map<ExecutionStatus, Long> executionsByStatus()
    {
        Map<ExecutionStatus, Long> out = new EnumMap<>(ExecutionStatus.class);
        for (RuleExecution execution : executions)
        {
            out.merge(execution.getStatus(), 1L, Long::sum);
        }
        return out;
    }

}
