package net.cumba.cdisc.define.conformance.eval;

import java.util.List;
import net.cumba.cdisc.define.conformance.report.ConformanceFinding;
import net.cumba.cdisc.define.conformance.report.ExecutionStatus;
import net.cumba.cdisc.define.conformance.rule.ConformanceRule;

/** The outcome of evaluating one rule against one document. */
public record RuleResult(ConformanceRule rule, ExecutionStatus status,
        List<ConformanceFinding> findings)
{

    public RuleResult
    {
        findings = List.copyOf(findings);
    }


    static RuleResult skipped(ConformanceRule aRule, ExecutionStatus aStatus)
    {
        return new RuleResult(aRule, aStatus, List.of());
    }

}
