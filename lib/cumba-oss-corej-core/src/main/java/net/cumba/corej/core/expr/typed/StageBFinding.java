package net.cumba.corej.core.expr.typed;

/**
 * One stage-B checker finding: a {@link StageBErrorKind}, the <b>binding</b> it belongs to — the
 * authored variable (column) name, per D41's <i>"detect per binding, report per binding … the
 * report must say which variable"</i> — and a message. Whether it errors the (rule, dataset) is the
 * kind's {@link StageBErrorKind#armed()} property, not the finding's.
 */
public record StageBFinding(StageBErrorKind kind, String binding, String message)
{

    @Override
    public String toString()
    {
        return kind + " [" + binding + "]: " + message;
    }

}
