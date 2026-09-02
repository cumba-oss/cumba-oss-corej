package net.cumba.cdisc.define.conformance.report;

import lombok.Value;

/** One row of the report's per-rule execution summary (plan §3.4). */
@Value
public class RuleExecution
{

    String ruleId;

    ExecutionStatus status;

    /**
     * Findings the rule produced; always {@code 0} unless {@link #status} is
     * {@link ExecutionStatus#EXECUTED}.
     */
    int findingCount;

}
