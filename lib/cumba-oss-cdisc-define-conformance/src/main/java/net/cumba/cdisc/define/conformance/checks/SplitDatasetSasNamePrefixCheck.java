package net.cumba.cdisc.define.conformance.checks;

import java.util.Optional;
import net.cumba.cdisc.define.conformance.eval.CustomCheck;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.tree.ElementNode;

/**
 * PMDA DD0050: for split datasets (more than one ItemGroupDef shares the Domain), "the 2-letter
 * prefix in SASDatasetName attribute and Domain attribute must have the same value".
 *
 * <p>
 * Custom because split detection needs document-wide counting ({@link SplitDatasets}). The row is
 * enforced literally: the first two characters of {@code SASDatasetName} must equal the whole
 * {@code Domain} value — for the general-observation-class datasets the row targets, Domain is the
 * two-letter code, and a longer Domain can never equal a 2-character prefix (such a group is
 * DD0114's beat anyway: splits are GO-class-only). An absent/blank {@code SASDatasetName} is out of
 * reach (presence is DD0047's beat); comparison is case-sensitive ("same value", DD0048's looser
 * convention — no case rule invented).
 * </p>
 */
public final class SplitDatasetSasNamePrefixCheck implements CustomCheck
{

    @Override
    public boolean satisfied(ElementNode aNode, DocumentContext aContext)
    {
        Optional<String> domain = SplitDatasets.domain(aNode);
        if (domain.isEmpty() || SplitDatasets.groupSize(domain.get(), aContext) < 2)
        {
            return true;
        }
        Optional<String> sasDatasetName = aNode.attribute("SASDatasetName")
                .filter(v -> !v.isBlank());
        if (sasDatasetName.isEmpty())
        {
            return true;
        }
        String value = sasDatasetName.get();
        String prefix = value.substring(0, Math.min(2, value.length()));
        return prefix.equals(domain.get());
    }

}
