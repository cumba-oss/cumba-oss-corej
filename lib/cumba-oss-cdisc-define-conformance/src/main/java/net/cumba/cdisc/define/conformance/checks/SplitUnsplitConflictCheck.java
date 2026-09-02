package net.cumba.cdisc.define.conformance.checks;

import java.util.Optional;
import net.cumba.cdisc.define.conformance.eval.CustomCheck;
import net.cumba.cdisc.define.conformance.eval.DocumentContext;
import net.cumba.cdisc.define.conformance.tree.ElementNode;

/**
 * PMDA DD0115: "Split and unsplit datasets cannot both be listed in Define.xml" — a Domain group
 * with several ItemGroupDefs must not also contain the unsplit dataset itself.
 *
 * <p>
 * Custom because the conflict needs document-wide grouping ({@link SplitDatasets}). The unsplit
 * member of a group is recognised by {@code Name} equal to {@code Domain} (split parts carry
 * suffixed names, DD0050); the finding lands once per conflict, on that unsplit ItemGroupDef — the
 * split parts themselves are legitimate listings. The row's FDA 'split' sub-directory advice is
 * submission-layout guidance, not checkable in the document. Two unsplit twins sharing a Name (a
 * duplicate-dataset defect) fire here on each — the duplication itself is other rows' beat.
 * </p>
 */
public final class SplitUnsplitConflictCheck implements CustomCheck
{

    @Override
    public boolean satisfied(ElementNode aNode, DocumentContext aContext)
    {
        Optional<String> domain = SplitDatasets.domain(aNode);
        if (domain.isEmpty() || SplitDatasets.groupSize(domain.get(), aContext) < 2)
        {
            return true;
        }
        return !aNode.attribute("Name").map(domain.get()::equals).orElse(false);
    }

}
