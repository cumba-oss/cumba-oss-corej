package net.cumba.cdisc.define.conformance.report;

/**
 * Per-rule execution outcome, recorded in the report's execution summary so gated skips (plan §3.6)
 * are visible, never silent.
 */
public enum ExecutionStatus
{

    /** The rule ran against the document (it may or may not have produced findings). */
    EXECUTED,

    /** The rule declares {@code Requires: ct} and no {@code CtProvider} was supplied. */
    SKIPPED_MISSING_CT,

    /** The rule declares {@code Requires: folder} and no submission folder was supplied. */
    SKIPPED_MISSING_FOLDER,

    /** The rule declares {@code Requires: library} and no {@code LibraryProvider} was supplied. */
    SKIPPED_MISSING_LIBRARY,

    /** The document's detected Define-XML version is outside the rule's applicable versions. */
    NOT_APPLICABLE_VERSION;

}
