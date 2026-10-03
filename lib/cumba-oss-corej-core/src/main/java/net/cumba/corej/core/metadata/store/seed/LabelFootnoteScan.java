package net.cumba.corej.core.metadata.store.seed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredDomain;
import net.cumba.corej.core.metadata.store.StoredField;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredScenario;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import org.jspecify.annotations.Nullable;

/**
 * <b>Owner rule 2026-10-03:</b> <i>"No label in the store should reference a footnote in the
 * printed docs. The labels are to be used in documents that do not contain these footnotes."</i>
 * After the errata are applied, the seed run scans every variable and CDASH field label for a
 * trailing footnote marker — {@code *}, {@code †}, {@code ‡}, superscript digits, {@code [i]} /
 * {@code [1]} — and reports every hit; the seeding test fails on one, so new content cannot bring a
 * footnote back. The only hits the 2026-10-03 census found were the six labels of issue
 * {@code #284.1}, which the register removes.
 */
final class LabelFootnoteScan
{

    /** A label ending in a footnote marker (template placeholders like {@code [domain]} do not). */
    static final Pattern FOOTNOTE_TAIL = Pattern
            .compile(".*(\\*|†|‡|[⁰¹²³⁴⁵⁶⁷⁸⁹]+|\\[(?:[ivxlc]+|\\d+)\\])\\s*$");

    private LabelFootnoteScan()
    {
    }


    /**
     * Scans the products' labels.
     *
     * @param aProducts
     *            the products to scan
     * @return {@code <product key>/<path>: '<label>'} for every label ending in a marker
     */
    static List<String> scan(Map<String, StoredProduct> aProducts)
    {
        List<String> hits = new ArrayList<>();
        for (StoredProduct product : aProducts.values())
        {
            String key = product.key();
            for (StoredClass clazz : product.classes())
            {
                String cp = key + "/classes[" + clazz.name() + "]";
                for (StoredVariable v : clazz.classVariables())
                {
                    check(hits, cp + "/classVariables[" + v.name() + "]", v.label());
                }
                for (StoredDataset ds : clazz.datasets())
                {
                    dataset(hits, cp, ds);
                }
                for (StoredDomain domain : clazz.domains())
                {
                    domain(hits, cp, domain);
                }
                for (StoredScenario scenario : clazz.scenarios())
                {
                    String sp = cp + "/scenarios[" + scenario.scenario() + "]";
                    for (StoredField f : scenario.fields())
                    {
                        check(hits, sp + "/fields[" + f.name() + "]", f.label());
                    }
                }
                for (StoredField f : clazz.cdashModelFields())
                {
                    check(hits, cp + "/cdashModelFields[" + f.name() + "]", f.label());
                }
            }
            for (StoredDataset ds : product.datasets())
            {
                dataset(hits, key, ds);
            }
            for (StoredDataStructure structure : product.dataStructures())
            {
                String sp = key + "/dataStructures[" + structure.name() + "]";
                for (StoredVariableSet set : structure.variableSets())
                {
                    String vp = sp + "/variableSets[" + set.name() + "]";
                    for (StoredVariable v : set.variables())
                    {
                        check(hits, vp + "/variables[" + v.name() + "]", v.label());
                    }
                }
            }
            for (StoredDomain domain : product.domains())
            {
                domain(hits, key, domain);
            }
        }
        return hits;
    }


    private static void dataset(List<String> aHits, String aParent, StoredDataset aDataset)
    {
        String dp = aParent + "/datasets[" + aDataset.name() + "]";
        for (StoredVariable v : aDataset.variables())
        {
            check(aHits, dp + "/variables[" + v.name() + "]", v.label());
        }
    }


    private static void domain(List<String> aHits, String aParent, StoredDomain aDomain)
    {
        String dp = aParent + "/domains[" + aDomain.name() + "]";
        for (StoredField f : aDomain.fields())
        {
            check(aHits, dp + "/fields[" + f.name() + "]", f.label());
        }
    }


    private static void check(List<String> aHits, String aPath, @Nullable String aLabel)
    {
        if (aLabel != null && FOOTNOTE_TAIL.matcher(aLabel).matches())
        {
            aHits.add(aPath + ": '" + aLabel + "'");
        }
    }
}
