package net.cumba.corej.core.expr.typed;

/**
 * One stage-A checker finding: a {@link StageAErrorKind} and a message naming the offending
 * construct. Whether it parks the rule is the kind's {@link StageAErrorKind#armed()} property, not
 * the finding's.
 */
public record StageAFinding(StageAErrorKind kind, String message)
{

    @Override
    public String toString()
    {
        return kind + ": " + message;
    }

}
