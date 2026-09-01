package net.cumba.cdisc.core.run;

/**
 * Thrown by {@link StudyValidationService} for operational failures that are not I/O errors and not
 * cancellation — conditions a CLI would historically surface as a usage-style error and a non-zero
 * exit code (e.g. "library contains no datasets", "no rules selected for validation").
 *
 * <p>
 * I/O failures (unresolvable path, unreadable file) continue to propagate as
 * {@link java.io.IOException}; cancellation propagates as {@link CancelledException}. This
 * exception is reserved for the in-between cases that have a clear, user-facing message but no
 * checked-I/O cause.
 * </p>
 */
public final class StudyValidationException extends RuntimeException
{

    private static final long serialVersionUID = 1L;

    /**
     * @param message
     *            human-readable description of the operational failure
     */
    public StudyValidationException(String message)
    {
        super(message);
    }


    /**
     * Wraps a lower-level failure that must reach the user as an operational error rather than a
     * stack trace — e.g. an unresolvable DECLARED product key, which {@code ProductKeyResolver}
     * reports as an {@link IllegalArgumentException} no CLI catch handles (review finding R-8).
     */
    public StudyValidationException(String message, Throwable cause)
    {
        super(message, cause);
    }
}
