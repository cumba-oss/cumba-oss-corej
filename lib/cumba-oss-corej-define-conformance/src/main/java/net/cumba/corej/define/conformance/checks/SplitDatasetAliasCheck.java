package net.cumba.corej.define.conformance.checks;

import net.cumba.corej.define.conformance.eval.CustomCheck;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.tree.ElementNode;

/**
 * PMDA DD0063: an {@code Alias} child "is required for each split dataset (where more than one
 * ItemGroupDef exists for the same Domain) to provide a description for the full dataset".
 *
 * <p>
 * Custom because split detection needs document-wide counting ({@link SplitDatasets}). The row
 * demands only the Alias element's presence — its {@code Context} value ('DomainDescription') is
 * DD0064's beat, so any Alias child satisfies this rule. Deliberate DISAGREEMENT with CDISC twin
 * 0095 stands (crossref): 0095 conditions the Alias on Domain≠Name, this row on the >1-IGD split
 * count — the PMDA wording wins for the PMDA rule set.
 * </p>
 */
public final class SplitDatasetAliasCheck implements CustomCheck
{

    @Override
    public boolean satisfied(ElementNode aNode, DocumentContext aContext)
    {
        return !SplitDatasets.isSplitPart(aNode, aContext) || !aNode.children("Alias").isEmpty();
    }

}
