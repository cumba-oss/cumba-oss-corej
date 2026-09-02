package net.cumba.cdisc.define.conformance.checks;

import java.util.Locale;
import java.util.Set;
import net.cumba.cdisc.define.conformance.eval.CustomCheck;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.tree.ElementNode;

/**
 * PMDA DD0114: "Split datasets can only be used for general-observation-class datasets (Events,
 * Findings, Findings About, Interventions)" — every split part must belong to one of those four
 * classes.
 *
 * <p>
 * Custom because split detection needs document-wide counting ({@link SplitDatasets}). The class is
 * read version-organically (2.0 {@code def:Class} attribute / 2.1 {@code def:Class} child Name) and
 * compared case-insensitively: the row prints title case while the GNRLOBSC terms the documents
 * carry are upper case. A split part without any class value is out of reach — class presence is
 * DD0054's beat, and a missing class cannot prove a non-GO class.
 * </p>
 */
public final class SplitDatasetClassCheck implements CustomCheck
{

    private static final Set<String> GENERAL_OBSERVATION_CLASSES = Set.of("EVENTS", "FINDINGS",
            "FINDINGS ABOUT", "INTERVENTIONS");

    @Override
    public boolean satisfied(ElementNode aNode, DocumentContext aContext)
    {
        if (!SplitDatasets.isSplitPart(aNode, aContext))
        {
            return true;
        }
        return SplitDatasets.datasetClass(aNode)
                .map(c -> GENERAL_OBSERVATION_CLASSES.contains(c.toUpperCase(Locale.ROOT)))
                .orElse(true);
    }

}
