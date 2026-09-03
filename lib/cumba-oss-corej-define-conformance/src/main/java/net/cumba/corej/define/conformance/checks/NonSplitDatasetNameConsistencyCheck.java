package net.cumba.corej.define.conformance.checks;

import java.util.Locale;
import java.util.Optional;
import net.cumba.corej.define.conformance.eval.CustomCheck;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.tree.ElementNode;

/**
 * PMDA DD0049: for SDTM/SEND datasets that are neither SUPPQUAL nor split — "only one ItemGroupDef
 * exists for the Domain" — {@code Name}, {@code Domain} and {@code SASDatasetName} must all carry
 * the same value.
 *
 * <p>
 * Custom because the non-split condition needs document-wide counting ({@link SplitDatasets}),
 * outside the declarative DSL. The SDTM/SEND standard-family condition lives in the rule file's
 * {@code when} guard (DD0053's any-guard shape); this class owns the rest. Reading choices, each
 * from the row text: a blank/absent {@code Domain} puts the dataset out of reach (no group to count
 * — Domain presence is other rows' beat); the SUPPQUAL exclusion is matched on a {@code SUPP} /
 * {@code SQAP} dataset-name prefix (the associated-persons spelling included); an absent
 * {@code SASDatasetName} skips only that comparison (presence is DD0047's beat). Equality is
 * case-sensitive — "must have the same value", with no case-normalising language in the row
 * (DD0048's looser convention: no uppercase requirement is invented here).
 * </p>
 */
public final class NonSplitDatasetNameConsistencyCheck implements CustomCheck
{

    @Override
    public boolean satisfied(ElementNode aNode, DocumentContext aContext)
    {
        Optional<String> domain = SplitDatasets.domain(aNode);
        if (domain.isEmpty())
        {
            return true;
        }
        String name = aNode.attribute("Name").orElse("");
        String upperName = name.toUpperCase(Locale.ROOT);
        if (upperName.startsWith("SUPP") || upperName.startsWith("SQAP"))
        {
            return true;
        }
        if (SplitDatasets.groupSize(domain.get(), aContext) != 1)
        {
            return true;
        }
        if (!name.isBlank() && !name.equals(domain.get()))
        {
            return false;
        }
        Optional<String> sasDatasetName = aNode.attribute("SASDatasetName")
                .filter(v -> !v.isBlank());
        return sasDatasetName.map(domain.get()::equals).orElse(true);
    }

}
