package net.cumba.corej.define.conformance.checks;

import java.util.HashSet;
import java.util.Set;
import net.cumba.corej.define.conformance.eval.CustomCheck;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.tree.ElementNode;

/**
 * PMDA-DD0150 (document-level): a v2.1 define.xml's {@code def:Standards} must pair each declared
 * data-standard IG with its matching CT standard. For every {@code def:Standard} with
 * {@code Type="IG"}, the required companion is determined by the IG {@code Name}:
 *
 * <ul>
 * <li>{@code Name} begins with {@code SENDIG} ⇒ a {@code Type="CT"} standard with
 * {@code PublishingSet="SEND"};</li>
 * <li>{@code Name} equals {@code SDTMIG} ⇒ a {@code Type="CT"} standard with
 * {@code PublishingSet="SDTM"};</li>
 * <li>{@code Name} equals {@code ADaMIG} ⇒ a {@code Type="CT"} standard with
 * {@code PublishingSet="SDTM"} or {@code PublishingSet="ADaM"}.</li>
 * </ul>
 *
 * <p>
 * Scoped to the synthetic {@code Document} node so a missing companion is reported exactly once. A
 * custom check is warranted because the requirement is a conjunction on a single
 * {@code def:Standard} element (Type <em>and</em> Name/PublishingSet together) combined with a
 * cross-element existence test — neither the {@code exists}/{@code when} path grammar (which tests
 * attributes independently across all matching elements) nor any other declarative kind can express
 * it. Documents declaring no recognised IG standard raise no finding.
 * </p>
 */
public final class StandardsCombinationCheck implements CustomCheck
{

    @Override
    public boolean satisfied(ElementNode aNode, DocumentContext aContext)
    {
        Set<String> ctPublishingSets = new HashSet<>();
        for (ElementNode standard : aContext.allNodes())
        {
            if ("Standard".equals(standard.localName())
                    && standard.attribute("Type").map("CT"::equals).orElse(false))
            {
                standard.attribute("PublishingSet").ifPresent(ctPublishingSets::add);
            }
        }
        for (ElementNode standard : aContext.allNodes())
        {
            if (!"Standard".equals(standard.localName())
                    || !standard.attribute("Type").map("IG"::equals).orElse(false))
            {
                continue;
            }
            String name = standard.attribute("Name").orElse("");
            if (name.startsWith("SENDIG"))
            {
                if (!ctPublishingSets.contains("SEND"))
                {
                    return false;
                }
            }
            else if ("SDTMIG".equals(name))
            {
                if (!ctPublishingSets.contains("SDTM"))
                {
                    return false;
                }
            }
            else if ("ADaMIG".equals(name) && !ctPublishingSets.contains("SDTM")
                    && !ctPublishingSets.contains("ADaM"))
            {
                return false;
            }
        }
        return true;
    }

}
