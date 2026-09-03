package net.cumba.corej.define.conformance.eval;

import net.cumba.corej.define.conformance.tree.ElementNode;

/**
 * A hand-written check backing a {@code Check.kind: custom} rule (plan §3.3 escape hatch,
 * budget-capped at 15 corpus-wide). Implementations are stateless, have a public no-arg
 * constructor, and are instantiated once per validation run by class name.
 */
public interface CustomCheck
{

    /**
     * Decides whether one scoped element satisfies the rule.
     *
     * @param aNode
     *            one scoped element that passed the rule's {@code when} guard
     * @param aContext
     *            the shared document context
     * @return {@code true} when the node satisfies the rule (no finding); {@code false} to emit a
     *         finding at the node
     */
    boolean satisfied(ElementNode aNode, DocumentContext aContext);

}
