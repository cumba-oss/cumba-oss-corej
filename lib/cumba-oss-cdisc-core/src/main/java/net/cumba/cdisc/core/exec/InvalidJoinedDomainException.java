package net.cumba.cdisc.core.exec;

import java.io.Serial;

/**
 * Thrown by a resolution site when a {@code Match_Datasets} name / {@code RDOMAIN} value resolves
 * to a split domain whose members cannot be unioned ({@link DomainResolution.Invalid}). Caught at
 * the top of rule execution ({@code RuleRunner.execute} / {@code CohortRunner.executeCohort}) and
 * turned into a {@code RuleExecutionStatus.ERROR} result with an {@code __error__} sentinel
 * violation — the same "virtual finding" shape as the unsupported RELREC×key-expansion combination.
 */
final class InvalidJoinedDomainException extends RuntimeException
{

    @Serial
    private static final long serialVersionUID = 1L;

    /** The two-character domain code that could not be unioned. */
    private final String domain;

    InvalidJoinedDomainException(String domain, String message)
    {
        super(message);
        this.domain = domain;
    }


    String domain()
    {
        return domain;
    }

}
