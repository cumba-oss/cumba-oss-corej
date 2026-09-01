package net.cumba.cdisc.core.exec;

import java.util.List;

import lombok.Builder;
import lombok.Value;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

// @Builder.Default's generated all-args constructor trips NullAway's init check (NullAway#917);
// Lombok's @Value still enforces the field set at build() time.
@SuppressWarnings("NullAway.Init")
@Value
@Builder(toBuilder = true)
public class RuleExecutionResult
{

    @Nullable
    String ruleId;

    @Nullable
    String message;

    List<Violation> violations;

    long totalRows;

    /** Execution status. Defaults to {@link RuleExecutionStatus#EXECUTED}. */
    @Builder.Default
    RuleExecutionStatus status = RuleExecutionStatus.EXECUTED;

    /** Additional detail when status is SKIPPED or ERROR. */
    @Nullable
    String statusMessage;

    /**
     * True violation count, which may exceed {@link #violations} size when the per-rule findings
     * cap truncated the materialised list (see {@link ViolationSink} / {@link EngineLimits}). The
     * default {@code -1} means "no cap applied — use {@code violations.size()}", so the many
     * builder sites that do not set it stay correct.
     */
    @Builder.Default
    long totalViolationCount = -1;

    /**
     * Wall-clock execution time in milliseconds for this rule against one dataset, as measured by
     * the orchestrator (apportioned evenly across members for a cohort run). Default {@code -1}
     * means "not measured" (e.g. a rule skipped before execution); {@code 0} is a legitimately fast
     * rule.
     */
    @Builder.Default
    long runtimeMillis = -1;

    /**
     * EC-40 — which tier produced the record key carried on this execution's {@link Violation}s. A
     * property of the rule × dataset (one {@code RowKeySpec} is resolved per execution), not of an
     * individual row, which is why it lives here rather than on each violation.
     * {@link RecordKeyResolver.KeySource#NONE} whenever no key was resolved — including under the
     * default {@code corej.findingKeys=off}.
     */
    @Builder.Default
    RecordKeyResolver.KeySource keySource = RecordKeyResolver.KeySource.NONE;

    /**
     * The rule's <b>effective</b> severity for this execution — the authored {@code Severity}, or
     * {@link Severity#ERROR} when the rule omits the field.
     *
     * <p>
     * A property of the rule × dataset execution, not of an individual row, which is why it sits
     * here. An individual row may still be claimed by a <em>stricter</em> level once per-level
     * Checks land (phase 4); that per-row value rides on {@link Violation#getLevel()} and wins over
     * this one. {@code null} means "not resolved" and the reader falls back to {@code ERROR}.
     * </p>
     */
    @Builder.Default
    @Nullable
    Severity severity = null;

    public int getViolationCount()
    {
        if (totalViolationCount >= 0)
        {
            return (int) Math.min(totalViolationCount, Integer.MAX_VALUE);
        }
        return violations != null ? violations.size() : 0;
    }


    /**
     * {@code true} when the materialised {@link #violations} list was capped below the true total.
     */
    public boolean isTruncated()
    {
        return totalViolationCount > (violations != null ? violations.size() : 0);
    }


    public boolean hasViolations()
    {
        return getViolationCount() > 0;
    }


    public boolean isSkipped()
    {
        return status == RuleExecutionStatus.SKIPPED;
    }


    public boolean isError()
    {
        return status == RuleExecutionStatus.ERROR;
    }

}
