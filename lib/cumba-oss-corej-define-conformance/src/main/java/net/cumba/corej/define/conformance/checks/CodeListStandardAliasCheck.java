package net.cumba.corej.define.conformance.checks;

import net.cumba.corej.define.conformance.eval.CustomCheck;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.tree.ElementNode;

/**
 * DEFINE-XML-0249 (CDISC sheet row 249): a {@code CodeList} that references a CDISC Controlled
 * Terminology standard (carries a {@code def:StandardOID} attribute — the rule's {@code when}
 * guard) must have <b>exactly one</b> {@code Alias} child with {@code Context="nci:ExtCodeID"}.
 *
 * <p>
 * Custom because the sheet's formal rule is a count over attribute-filtered children
 * ({@code count(Alias[@Context='nci:ExtCodeID']) = 1}): {@code cardinality_at_most} cannot filter
 * children by attribute, and the {@code when}-grammar is any-match, so neither the lower nor the
 * upper bound is declaratively expressible. One {@code custom}-kind check against the corpus-wide
 * budget of 15 (plan §11 Q4; running count tracked by the coordinator).
 * </p>
 */
public final class CodeListStandardAliasCheck implements CustomCheck
{

    private static final String NCI_EXT_CODE_ID = "nci:ExtCodeID";

    @Override
    public boolean satisfied(ElementNode aNode, DocumentContext aContext)
    {
        long count = aNode.children("Alias").stream().filter(
                alias -> alias.attribute("Context").map(NCI_EXT_CODE_ID::equals).orElse(false))
                .count();
        return count == 1;
    }

}
