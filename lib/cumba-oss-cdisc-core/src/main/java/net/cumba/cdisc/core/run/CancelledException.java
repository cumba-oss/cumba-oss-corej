package net.cumba.cdisc.core.run;

/**
 * Thrown by {@link StudyValidationService} when a run is aborted because the caller-supplied
 * cancellation check (see {@link StudyValidationParams#cancellation()}) reported {@code true}.
 *
 * <p>
 * Cancellation is checked once before validation begins and again before each target dataset is
 * loaded. A {@code CancelledException} therefore aborts the run cleanly at a dataset boundary — no
 * partial report is produced. Callers that prefer a partial result can catch this and synthesise
 * one, but the service itself surfaces cancellation as this exception so the abort is always
 * observable.
 * </p>
 */
public final class CancelledException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    /** Creates a cancellation signal with a default message. */
    public CancelledException()
    {
        super("study validation cancelled");
    }


    /**
     * Creates a cancellation signal with an explicit message.
     *
     * @param message
     *            human-readable detail (e.g. the phase at which cancellation was observed)
     */
    public CancelledException(String message)
    {
        super(message);
    }
}
