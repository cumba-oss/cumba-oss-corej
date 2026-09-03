package net.cumba.corej.define.conformance.eval;

import net.cumba.corej.define.conformance.tree.ElementNode;

/**
 * Test-only {@link CustomCheck}: a scoped element satisfies the rule iff it carries an {@code OID}
 * attribute. Referenced by class name from {@code custom}-kind rules in the evaluator tests.
 */
public final class OidPresentCustomCheck implements CustomCheck
{

    @Override
    public boolean satisfied(ElementNode aNode, DocumentContext aContext)
    {
        return aNode.attribute("OID").isPresent();
    }

}
