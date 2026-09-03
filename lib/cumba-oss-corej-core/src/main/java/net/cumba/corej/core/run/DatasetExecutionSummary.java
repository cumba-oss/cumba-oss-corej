package net.cumba.corej.core.run;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Per-domain execution statistics for one validated dataset, surfaced for the per-run execution log
 * (it is not part of the JSON validation report). Captures how many rules ran of the total selected
 * for the run, how many findings were attributed to the domain, and any errors with their location.
 *
 * @param domain
 *            the dataset / domain name (the library member name, e.g. {@code LB} or {@code LBHE})
 * @param fileName
 *            the source file name, when known
 * @param rulesExecuted
 *            number of rules that ran for this domain (status {@code EXECUTED}) — the "X"
 * @param rulesTotal
 *            total rules selected for the run (the run's effective rule set) — the "Y"
 * @param findings
 *            number of findings attributed to this domain
 * @param runtimeMillis
 *            this dataset's wall-clock validation time in milliseconds ({@code -1} when not
 *            measured, e.g. the dataset failed to load)
 * @param errors
 *            errors encountered for this domain (rule errors + a dataset load failure), each with
 *            the originating rule id and message
 * @param ruleExecutions
 *            per-rule outcome of every rule run against this domain (EXECUTED / SKIPPED / ERROR),
 *            including the expansion variable for generated rules and the reason a rule was not
 *            executed — surfaced for the per-run "rules by dataset" view
 */
public record DatasetExecutionSummary(String domain, @Nullable String fileName, int rulesExecuted,
        int rulesTotal, int findings, long runtimeMillis, List<RuleError> errors,
        List<RuleExecution> ruleExecutions)
{

    public DatasetExecutionSummary
    {
        errors = errors == null ? List.of() : List.copyOf(errors);
        ruleExecutions = ruleExecutions == null ? List.of() : List.copyOf(ruleExecutions);
    }

    /**
     * One error encountered while validating a domain.
     *
     * @param ruleId
     *            the originating rule id (CORE id), or the synthetic dataset-load-error id
     * @param message
     *            the error detail
     */
    public record RuleError(@Nullable String ruleId, @Nullable String message)
    {
    }


    /**
     * One rule's outcome against a single dataset.
     *
     * @param coreId
     *            the rule's CORE id (e.g. {@code CG0001} or {@code CDISC-AD0001}); for a generated
     *            (expanded) rule this is the expanded id (e.g. {@code CG0001-AGE})
     * @param generatedId
     *            the rule's stable identity ({@code Rule#effectiveId()}): the synthetic {@code id}
     *            for a rule that carries no {@code Core}, otherwise the CORE id — so it equals
     *            {@code coreId} for every corpus rule
     * @param status
     *            {@code EXECUTED}, {@code SKIPPED} or {@code ERROR}
     * @param violations
     *            number of violations the rule reported for this dataset
     * @param runtimeMillis
     *            this rule's execution time in milliseconds against this dataset (apportioned for a
     *            cohort run); {@code -1} when not measured (e.g. skipped before execution)
     * @param expandedFor
     *            for a generated rule, the variable the template was expanded for (the primary
     *            wildcard column); {@code null} for non-generated rules
     * @param notExecutedReason
     *            why the rule was not executed (the status message) when {@code SKIPPED} or
     *            {@code ERROR}; {@code null} when {@code EXECUTED}
     * @param description
     *            the rule's human-readable description ({@code Rule#getDescription()}), or
     *            {@code null} when the rule carries none
     * @param executability
     *            the rule's declared executability in title-case display form (e.g.
     *            {@code "Fully Executable"}), or {@code null} when the rule declares none
     */
    public record RuleExecution(@Nullable String coreId, @Nullable String generatedId,
            String status, int violations, long runtimeMillis, @Nullable String expandedFor,
            @Nullable String notExecutedReason, @Nullable String description,
            @Nullable String executability)
    {
    }
}
