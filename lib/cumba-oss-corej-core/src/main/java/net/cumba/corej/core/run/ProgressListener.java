package net.cumba.corej.core.run;

import net.cumba.corej.core.report.LibraryValidator;

/**
 * Optional progress callback fired by {@link StudyValidationService} as a validation run advances.
 * Every method has a no-op default, so implementations only override the events they care about.
 *
 * <p>
 * Designed for a long-running embedding (e.g. a REST service streaming progress to a client). The
 * CLI does not use it. Pass {@code null} to {@link StudyValidationParams} for "no progress
 * reporting".
 * </p>
 *
 * <h2>Threading</h2>
 *
 * <p>
 * {@link #onDatasetsDiscovered(int)} is fired once from the service's orchestration thread.
 * {@link #onDatasetCompleted(int, int, String, int)} is fired as each dataset finishes, live from
 * inside {@link LibraryValidator#validate()}: in the validator's parallel-dataset mode it may be
 * invoked concurrently from worker threads in completion order, so an implementation must be
 * thread-safe (the same contract {@link #onRuleExecuted} carries). {@code StudyValidationService}
 * always drives the validator in sequential mode, so embedders using the service see
 * single-threaded callbacks with a strictly increasing {@code processed} count.
 * {@link #onRuleExecuted} is wired straight to
 * {@link LibraryValidator.RuntimeListener#onRuleExecuted} and inherits its threading contract: it
 * may be invoked concurrently from worker threads when rule-level parallelism is enabled, so an
 * implementation that counts rule executions must be thread-safe.
 * </p>
 */
public interface ProgressListener
{

    /**
     * Fired once, after the data library has been enumerated, with the number of validation-target
     * datasets that will be processed. Lazy reference datasets are not counted.
     *
     * @param totalDatasets
     *            number of target datasets to validate
     */
    default void onDatasetsDiscovered(int totalDatasets)
    {
        // no-op
    }


    /**
     * Fired once per target dataset as it finishes validating, live from inside
     * {@link LibraryValidator#validate()}. In the validator's parallel-dataset mode it may be
     * invoked concurrently from worker threads in completion order; via
     * {@code StudyValidationService} (sequential mode) it arrives in target order on a single
     * thread. Implementations must be thread-safe.
     *
     * @param processed
     *            1-based count of datasets finished so far (completion order)
     * @param totalDatasets
     *            total number of target datasets (same value as {@link #onDatasetsDiscovered(int)})
     * @param domain
     *            the dataset / domain name that completed
     * @param datasetFindings
     *            violating-row count for this dataset (its contribution to the report's final
     *            finding count); a running sum across completed datasets converges to that total
     */
    default void onDatasetCompleted(int processed, int totalDatasets, String domain,
            int datasetFindings)
    {
        // no-op
    }


    /**
     * Fired after every individual rule execution, carrying timing and outcome details. This is the
     * service's pass-through of {@link LibraryValidator.RuntimeListener} — see that type for the
     * exact semantics of {@code coreId}, {@code ruleId} and {@code elapsedMillis}. Use it to count
     * {@code rulesExecuted} or to build a runtime profile.
     *
     * @param entry
     *            the per-rule runtime record
     */
    default void onRuleExecuted(LibraryValidator.RuntimeEntry entry)
    {
        // no-op
    }
}
