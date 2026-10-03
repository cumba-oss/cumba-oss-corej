package net.cumba.corej.core.metadata.store.seed;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
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
 * After the errata are applied, the seed run scans every label — variable, CDASH field, dataset,
 * class, CDASH domain, data structure and variable set — and every CDASH prompt for a trailing
 * footnote marker and reports every hit; the seeding test fails on one, so new content cannot bring
 * a footnote back. The only hits the 2026-10-03 census found were the six labels of issue
 * {@code #284.1}, which the register removes.
 *
 * <h2>What counts as a marker (review F6)</h2>
 * <ul>
 * <li>{@code *}, {@code †}, {@code ‡} at the end;</li>
 * <li>a bracketed marker at the end: a number ({@code [1]}), a roman numeral from one to nine in
 * either case ({@code [i]} … {@code [ix]}, {@code [I]} … {@code [IX]}), or a letter {@code [a]},
 * {@code [b]}, {@code [d]}, {@code [e]}. Deliberately NOT {@code [x]}, {@code [xx]} or {@code [c]}:
 * those are template placeholders (and as roman numerals ten and a hundred, which no printed
 * footnote list reaches), so {@code [c]} is the one letter of a–e the scan cannot see;</li>
 * <li>a run of superscript digits at the end — unless it is a unit's exponent: the word it ends
 * contains a {@code /} ({@code mg/m²}) or is a one- to three-letter lower-case unit symbol
 * ({@code m²}, {@code cm³}).</li>
 * </ul>
 */
final class LabelFootnoteScan
{

    /** A label ending in {@code *}, {@code †}, {@code ‡} or a bracketed footnote marker. */
    static final Pattern FOOTNOTE_TAIL = Pattern.compile(
            ".*(\\*|†|‡|\\[(?:\\d+|i{1,3}|iv|vi{0,3}|ix|I{1,3}|IV|VI{0,3}|IX|[abde])\\])\\s*$");

    /** A label ending in superscript digits; group 1 is the word they end. */
    private static final Pattern SUPERSCRIPT_TAIL = Pattern
            .compile("(?:.*\\s)?(\\S*?)[⁰¹²³⁴⁵⁶⁷⁸⁹]+\\s*$");

    /** A unit word an exponent may end: contains a slash, or a short lower-case unit symbol. */
    private static final Pattern UNIT_WORD = Pattern.compile(".*/.*|[a-zµ]{1,3}");

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
                check(hits, cp, clazz.label());
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
                        field(hits, sp + "/fields[" + f.name() + "]", f);
                    }
                }
                for (StoredField f : clazz.cdashModelFields())
                {
                    field(hits, cp + "/cdashModelFields[" + f.name() + "]", f);
                }
            }
            for (StoredDataset ds : product.datasets())
            {
                dataset(hits, key, ds);
            }
            for (StoredDataStructure structure : product.dataStructures())
            {
                String sp = key + "/dataStructures[" + structure.name() + "]";
                check(hits, sp, structure.label());
                for (StoredVariableSet set : structure.variableSets())
                {
                    String vp = sp + "/variableSets[" + set.name() + "]";
                    check(hits, vp, set.label());
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
        check(aHits, dp, aDataset.label());
        for (StoredVariable v : aDataset.variables())
        {
            check(aHits, dp + "/variables[" + v.name() + "]", v.label());
        }
    }


    private static void domain(List<String> aHits, String aParent, StoredDomain aDomain)
    {
        String dp = aParent + "/domains[" + aDomain.name() + "]";
        check(aHits, dp, aDomain.label());
        for (StoredField f : aDomain.fields())
        {
            field(aHits, dp + "/fields[" + f.name() + "]", f);
        }
    }


    private static void field(List<String> aHits, String aPath, StoredField aField)
    {
        check(aHits, aPath, aField.label());
        if (aField.prompt() != null && endsInFootnote(aField.prompt()))
        {
            aHits.add(aPath + " prompt: '" + aField.prompt() + "'");
        }
    }


    private static void check(List<String> aHits, String aPath, @Nullable String aLabel)
    {
        if (aLabel != null && endsInFootnote(aLabel))
        {
            aHits.add(aPath + ": '" + aLabel + "'");
        }
    }


    /**
     * Whether a label or prompt ends in a footnote marker (the rule in the class comment).
     *
     * @param aText
     *            the text
     * @return {@code true} for a trailing marker
     */
    static boolean endsInFootnote(String aText)
    {
        if (FOOTNOTE_TAIL.matcher(aText).matches())
        {
            return true;
        }
        Matcher superscript = SUPERSCRIPT_TAIL.matcher(aText);
        return superscript.matches() && !UNIT_WORD.matcher(superscript.group(1)).matches();
    }
}
