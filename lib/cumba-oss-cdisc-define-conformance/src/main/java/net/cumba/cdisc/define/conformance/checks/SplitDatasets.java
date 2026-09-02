package net.cumba.cdisc.define.conformance.checks;

import java.util.Optional;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.tree.ElementNode;

/**
 * Shared split-dataset mechanics for the PMDA P5b custom checks (DD0049/DD0050/DD0063/DD0114/
 * DD0115). The PMDA sheet's split criterion is uniform across those rows: a dataset is
 * <em>split</em> when more than one {@code ItemGroupDef} in the document carries the same
 * (non-blank) {@code Domain} value.
 */
final class SplitDatasets
{

    private SplitDatasets()
    {
    }


    /** The ItemGroupDef's non-blank {@code Domain} value, or empty. */
    static Optional<String> domain(ElementNode aNode)
    {
        return aNode.attribute("Domain").filter(v -> !v.isBlank());
    }


    /** How many ItemGroupDefs in the document carry the given {@code Domain} value. */
    static long groupSize(String aDomain, DocumentContext aContext)
    {
        long count = 0;
        for (ElementNode node : aContext.allNodes())
        {
            if ("ItemGroupDef".equals(node.localName())
                    && domain(node).map(aDomain::equals).orElse(false))
            {
                count++;
            }
        }
        return count;
    }


    /** True when the ItemGroupDef is one of several sharing its Domain (a split part). */
    static boolean isSplitPart(ElementNode aNode, DocumentContext aContext)
    {
        return domain(aNode).map(d -> groupSize(d, aContext) > 1).orElse(false);
    }


    /**
     * The dataset's observation-class name: the Define-XML 2.0 {@code def:Class} attribute when
     * present, else the 2.1 {@code def:Class} child element's {@code Name} — dispatched organically
     * by document shape, not by version gate (DD0053/DD0055 convention).
     */
    static Optional<String> datasetClass(ElementNode aNode)
    {
        Optional<String> attribute = aNode.attribute("Class").filter(v -> !v.isBlank());
        if (attribute.isPresent())
        {
            return attribute;
        }
        return aNode.children("Class").stream().map(c -> c.attribute("Name"))
                .flatMap(Optional::stream).filter(v -> !v.isBlank()).findFirst();
    }

}
