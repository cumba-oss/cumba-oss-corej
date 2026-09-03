package net.cumba.corej.define.conformance.checks;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import net.cumba.corej.define.conformance.eval.CustomCheck;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.tree.ElementNode;

/**
 * PMDA-DD0093 (Analysis Results Metadata): when an {@code arm:AnalysisResult} carries a
 * {@code ParameterOID}, that OID must reference a {@code PARAMCD} variable that belongs to one of
 * the result's analysed datasets — and a dataset carrying a {@code PARAMCD} variable is, by
 * definition, a BDS. The sheet's two clauses ("at least one arm:AnalysisDataset must be a BDS" and
 * "ParameterOID must reference a PARAMCD variable for that Dataset") therefore collapse into the
 * single, mechanically decidable membership check implemented here, avoiding a fragile match on the
 * ADaM class-name string (which differs between Define-XML 2.0's {@code def:Class} attribute and
 * 2.1's {@code def:Class} child element).
 *
 * <p>
 * The rule's {@code when} guard already restricts the scope to results that carry a non-blank
 * {@code ParameterOID}. A custom check (rather than a declarative kind) is warranted because the
 * decision follows an OID across two structures — the {@code ParameterOID} to its {@code ItemDef}
 * and each {@code arm:AnalysisDataset}'s {@code ItemGroupOID} to its {@code ItemGroupDef}'s
 * {@code ItemRef} membership — a cross-reference conjunction no single declarative kind expresses.
 * </p>
 */
public final class ArmParameterOidUsageCheck implements CustomCheck
{

    @Override
    public boolean satisfied(ElementNode aNode, DocumentContext aContext)
    {
        Optional<String> parameterOid = aNode.attribute("ParameterOID").filter(v -> !v.isBlank());
        if (parameterOid.isEmpty())
        {
            // The when-guard should preclude this; treat an absent ParameterOID as out of reach.
            return true;
        }
        Optional<ElementNode> parameter = aContext.oidResolver().resolve("ItemDef", "OID",
                parameterOid.get());
        if (parameter.isEmpty()
                || !parameter.get().attribute("Name").map("PARAMCD"::equals).orElse(false))
        {
            // A dangling ParameterOID, or one that names a non-PARAMCD variable, is improper use.
            return false;
        }
        for (String datasetOid : analysedDatasetOids(aNode))
        {
            Optional<ElementNode> group = aContext.oidResolver().resolve("ItemGroupDef", "OID",
                    datasetOid);
            if (group.isPresent() && referencesItem(group.get(), parameterOid.get()))
            {
                return true;
            }
        }
        return false;
    }


    /** The {@code ItemGroupOID}s of the result's {@code arm:AnalysisDataset} children. */
    private static List<String> analysedDatasetOids(ElementNode aResult)
    {
        List<String> oids = new ArrayList<>();
        for (ElementNode datasets : aResult.children("AnalysisDatasets"))
        {
            for (ElementNode dataset : datasets.children("AnalysisDataset"))
            {
                dataset.attribute("ItemGroupOID").ifPresent(oids::add);
            }
        }
        return oids;
    }


    /** Whether the {@code ItemGroupDef} has an {@code ItemRef} to the given item OID. */
    private static boolean referencesItem(ElementNode aGroup, String aItemOid)
    {
        for (ElementNode itemRef : aGroup.children("ItemRef"))
        {
            if (itemRef.attribute("ItemOID").map(aItemOid::equals).orElse(false))
            {
                return true;
            }
        }
        return false;
    }

}
