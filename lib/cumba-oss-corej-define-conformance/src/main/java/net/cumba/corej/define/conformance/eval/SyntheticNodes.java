package net.cumba.corej.define.conformance.eval;

import net.cumba.corej.define.conformance.tree.ElementNode;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;

/** Factory for the synthetic wrapper nodes the evaluator needs. */
final class SyntheticNodes
{

    private SyntheticNodes()
    {
    }


    /**
     * Wraps the document root in a synthetic {@code "Document"} node so document-level rules have
     * an anchor. Built through {@link ElementNodeBuilder}'s node contract: a bean-less node with
     * one child.
     */
    static ElementNode document(ElementNode aRoot)
    {
        return ElementNodeBuilder.synthetic(DocumentContext.DOCUMENT_SCOPE, aRoot);
    }

}
