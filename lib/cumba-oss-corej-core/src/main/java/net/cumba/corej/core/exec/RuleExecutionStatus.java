package net.cumba.corej.core.exec;

/**
 * Status of a rule execution.
 */
public enum RuleExecutionStatus
{

    /** The rule was evaluated successfully. */
    EXECUTED,

    /** The rule was skipped (e.g., no Library provider available). */
    SKIPPED,

    /** The rule failed to execute due to an error. */
    ERROR

}
