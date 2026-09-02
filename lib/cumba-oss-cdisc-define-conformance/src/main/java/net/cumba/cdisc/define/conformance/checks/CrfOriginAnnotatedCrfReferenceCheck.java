package net.cumba.cdisc.define.conformance.checks;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.cumba.cdisc.define.conformance.eval.CustomCheck;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.tree.ElementNode;

/**
 * PMDA DD0075: a CRF-collected {@code def:Origin}'s {@code def:DocumentRef} must reference the
 * {@code def:AnnotatedCRF}'s DocumentRef — i.e. its {@code leafID} must be one the enclosing
 * MetaDataVersion's AnnotatedCRF declarations carry.
 *
 * <p>
 * Scoped to {@code Origin}; the CRF-origin qualification (2.0 {@code Type='CRF'} / 2.1
 * {@code Type='Collected'} + {@code Source} ∈ {Investigator, Subject}, DD0103's corrected
 * semantics) lives in the rule file's {@code when} guard. Custom for two reasons the DSL cannot
 * express: set-membership against ALL AnnotatedCRF DocumentRefs (a {@code compare} would pin the
 * first only), and the absent-DocumentRef shape — a qualifying Origin with <b>no</b> DocumentRef at
 * all cannot match the AnnotatedCRF and fires (review-pmda-p3 W1: this row owns that shape; DD0035
 * and DD0103 are DocumentRef-scoped and can never reach it). A DocumentRef whose {@code leafID} is
 * absent/blank is ignored (the XSD requires leafID; schema defects are the pre-pass's beat). With
 * no AnnotatedCRF in reach the set is empty and every qualifying Origin fires — nothing to match,
 * per the row.
 * </p>
 */
public final class CrfOriginAnnotatedCrfReferenceCheck implements CustomCheck
{

    @Override
    public boolean satisfied(ElementNode aNode, DocumentContext aContext)
    {
        List<ElementNode> documentRefs = aNode.children("DocumentRef");
        if (documentRefs.isEmpty())
        {
            return false;
        }
        Set<String> annotatedCrfLeafIds = annotatedCrfLeafIds(aNode, aContext);
        for (ElementNode documentRef : documentRefs)
        {
            Optional<String> leafId = documentRef.attribute("leafID").filter(v -> !v.isBlank());
            if (leafId.isPresent() && !annotatedCrfLeafIds.contains(leafId.get()))
            {
                return false;
            }
        }
        return true;
    }


    /**
     * leafIDs of every AnnotatedCRF DocumentRef in the Origin's enclosing MetaDataVersion (falling
     * back to the whole document when no MetaDataVersion ancestor exists).
     */
    private static Set<String> annotatedCrfLeafIds(ElementNode aOrigin, DocumentContext aContext)
    {
        List<ElementNode> pool = enclosingMetaDataVersion(aOrigin)
                .map(ElementNode::selfAndDescendants).orElseGet(aContext::allNodes);
        Set<String> out = new HashSet<>();
        for (ElementNode node : pool)
        {
            if ("AnnotatedCRF".equals(node.localName()))
            {
                for (ElementNode documentRef : node.children("DocumentRef"))
                {
                    documentRef.attribute("leafID").filter(v -> !v.isBlank()).ifPresent(out::add);
                }
            }
        }
        return out;
    }


    private static Optional<ElementNode> enclosingMetaDataVersion(ElementNode aNode)
    {
        Optional<ElementNode> current = aNode.parent();
        while (current.isPresent() && !"MetaDataVersion".equals(current.get().localName()))
        {
            current = current.get().parent();
        }
        return current;
    }

}
