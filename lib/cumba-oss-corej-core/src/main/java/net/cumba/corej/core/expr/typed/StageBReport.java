package net.cumba.corej.core.expr.typed;

import java.util.List;

/**
 * The result of one (rule × dataset) stage-B check: the per-binding findings (D41) and the declared
 * skips (D89a — an unresolvable {@code Filter} column that <em>is</em> declared in
 * {@code Requirements.Variables} skips the rule cleanly with a reason instead of erroring). Phase 4
 * detects and reports; the {@code ERROR} channel stays the shipped one (the {@code ColumnTypeGate}
 * exception path and the {@code loadError} sentinel), and per-binding statuses reach the JSON
 * report in v2 (phase 5b), never by widening v1 (D57).
 */
public record StageBReport(List<StageBFinding> findings, List<String> skips)
{

    public StageBReport
    {
        findings = List.copyOf(findings);
        skips = List.copyOf(skips);
    }


    /** The findings whose kind is armed — the ones that file a bind error. */
    public List<StageBFinding> armedFindings()
    {
        return findings.stream().filter(f -> f.kind().armed()).toList();
    }


    /** The findings whose kind is observe-only. */
    public List<StageBFinding> observedFindings()
    {
        return findings.stream().filter(f -> !f.kind().armed()).toList();
    }

}
