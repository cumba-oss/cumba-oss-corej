package net.cumba.cdisc.define.conformance.checks;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.cumba.cdisc.define.conformance.eval.CustomCheck;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.tree.ElementNode;

/**
 * DEFINE-XML-0084 (CDISC sheet row 84): a {@code def:WhereClauseDef} whose range-check conditions
 * reference variables from <b>two or more datasets</b> must carry a {@code def:CommentOID}.
 *
 * <p>
 * "References a dataset" is resolved through membership: each {@code RangeCheck}'s
 * {@code def:ItemOID} is attributed to the {@code ItemGroupDef}s whose {@code ItemRef}s reference
 * that item. Because one {@code ItemDef} may legally be shared by several datasets, the check uses
 * the conservative reading: the where clause is cross-dataset only when the referenced items have
 * <b>no single dataset in common</b> (the intersection of their containing-dataset sets is empty) —
 * then at least two datasets are definitely involved. Items no dataset references are ignored here
 * (dangling/orphan concerns belong to other rules). The first shipped {@code custom}-kind check
 * (budget 1/15, plan §11 Q4).
 */
public final class WhereClauseCrossDatasetCommentCheck implements CustomCheck
{

    @Override
    public boolean satisfied(ElementNode aNode, DocumentContext aContext)
    {
        List<String> itemOids = aNode.children("RangeCheck").stream()
                .map(rc -> rc.attribute("ItemOID")).flatMap(Optional::stream).distinct().toList();
        if (itemOids.size() < 2)
        {
            return true;
        }

        List<Set<String>> datasetsPerItem = new ArrayList<>();
        for (String itemOid : itemOids)
        {
            Set<String> datasets = containingDatasets(itemOid, aContext);
            if (!datasets.isEmpty())
            {
                datasetsPerItem.add(datasets);
            }
        }
        if (datasetsPerItem.size() < 2)
        {
            return true;
        }

        Set<String> common = new HashSet<>(datasetsPerItem.get(0));
        for (Set<String> datasets : datasetsPerItem)
        {
            common.retainAll(datasets);
        }
        if (!common.isEmpty())
        {
            // All referenced items can live in one dataset — not provably cross-dataset.
            return true;
        }
        return aNode.attribute("CommentOID").filter(v -> !v.isBlank()).isPresent();
    }


    /** OIDs of the ItemGroupDefs whose ItemRefs reference the given ItemDef OID. */
    private static Set<String> containingDatasets(String aItemOid, DocumentContext aContext)
    {
        Set<String> out = new HashSet<>();
        for (ElementNode node : aContext.allNodes())
        {
            if (!"ItemGroupDef".equals(node.localName()))
            {
                continue;
            }
            for (ElementNode itemRef : node.children("ItemRef"))
            {
                if (itemRef.attribute("ItemOID").map(aItemOid::equals).orElse(false))
                {
                    node.attribute("OID").ifPresent(out::add);
                    break;
                }
            }
        }
        return out;
    }

}
