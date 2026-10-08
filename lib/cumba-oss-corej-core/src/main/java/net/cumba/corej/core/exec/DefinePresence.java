package net.cumba.corej.core.exec;

/**
 * Whether the run was given a Define-XML, and what became of it — the one fact behind
 * {@code define_available()} ({@code PLAN-engine-gap-define-presence} §4, runbook W1b DP1a) and
 * behind the study-level parse-failure ERROR (DP1b, W2). Carried on the {@link EvaluationContext}
 * from {@code StudyValidationService}, where the Define-XML path is read and parsed.
 *
 * <p>
 * The define <b>provider</b> is not this fact: on a direct-parse failure the service falls back to
 * the datatable conversion when one exists and to {@code null} when it does not, so
 * {@code defineProvider != null} answers neither "supplied" nor "parsed" (R7 fold, 2026-10-08).
 * </p>
 */
public enum DefinePresence
{
    /** No Define-XML was supplied to the run (the default of a context built without the fact). */
    ABSENT,
    /** A Define-XML was supplied and parsed. */
    PARSED,
    /**
     * A Define-XML was supplied but could not be parsed directly; the run degraded (owner Q2,
     * 2026-10-07: supplied is supplied — {@code define_available()} is true, the parse failure is
     * reported on its own).
     */
    UNPARSEABLE
}
