package net.cumba.cdisc.define.conformance.checks;

import net.cumba.cdisc.define.conformance.eval.CustomCheck;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.tree.ElementNode;

/**
 * DEFINE-XML-0154 (CDISC sheet row 154): for regulatory submissions, {@code def:Origin} must be
 * provided for every variable definition.
 *
 * <p>
 * The rule's YAML guard already restricts the scope to Submission-context {@code ItemDef}s without
 * a {@code def:ValueListRef}. This check adds the spec's either-level placement exemption (p. 59;
 * review-batch-d WARN 2): an Origin-less ItemDef is conforming when it is a <b>value-level
 * member</b> — referenced by some {@code def:ValueListDef}'s {@code ItemRef} — because its Origin
 * obligation then lives at the value level (enforced by DEFINE-XML-0155/0155-B), not here. Custom
 * budget 2/15 (plan §11 Q4).
 * </p>
 */
public final class VariableLevelOriginCheck implements CustomCheck
{

    @Override
    public boolean satisfied(ElementNode aNode, DocumentContext aContext)
    {
        if (!aNode.children("Origin").isEmpty())
        {
            return true;
        }
        String oid = aNode.attribute("OID").orElse(null);
        if (oid == null)
        {
            // No identity: the missing-OID defect belongs to sheet id 137, not here.
            return true;
        }
        return isValueLevelMember(oid, aContext);
    }


    /** Whether some def:ValueListDef's ItemRef references the given ItemDef OID. */
    private static boolean isValueLevelMember(String aItemOid, DocumentContext aContext)
    {
        for (ElementNode node : aContext.allNodes())
        {
            if (!"ValueListDef".equals(node.localName()))
            {
                continue;
            }
            for (ElementNode itemRef : node.children("ItemRef"))
            {
                if (itemRef.attribute("ItemOID").map(aItemOid::equals).orElse(false))
                {
                    return true;
                }
            }
        }
        return false;
    }

}
