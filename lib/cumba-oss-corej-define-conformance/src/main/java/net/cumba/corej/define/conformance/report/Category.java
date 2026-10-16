package net.cumba.corej.define.conformance.report;

import java.util.List;
import java.util.Locale;
import java.util.Objects;

/**
 * Finding category (plan §1). Three are the engine's own: {@link #XSD} marks findings from the XSD
 * pre-pass, {@link #SCHEMA} / {@link #SPECIFICATION} mirror the CDISC sheet's {@code Source Type}
 * column. Every other category is a <b>rule family</b> — a rule without {@code Source_Type} reports
 * under its family's name ({@code PMDA} for the PMDA sheet), so the set of categories is open and
 * follows the corpus, not a closed enum (PLAN-define-families-from-packages, DF1 (a)). The report
 * summary counts findings per category × severity.
 *
 * <p>
 * An open value with a fixed order: the engine categories first, in the order above, then the
 * family categories by name. That reproduces the former enum's order ({@code XSD, SCHEMA,
 * SPECIFICATION, PMDA}) exactly on the shipped corpus, which is what keeps the JSON report's
 * {@code findingsByCategory} block byte-identical.
 * </p>
 *
 * <p>
 * Names are normalised — trimmed, upper-cased with {@link Locale#ROOT} — so a rule saying
 * {@code Rule_Set: "Pmda"} reports under {@code PMDA}, the same key the Java-implemented PMDA-sheet
 * checks use. The manifest refuses a family spelled like an engine category, so the two kinds never
 * merge.
 * </p>
 *
 * @param name
 *            the normalised category name, as written into reports
 */
public record Category(String name) implements Comparable<Category>
{

    public static final Category XSD = new Category("XSD");

    public static final Category SCHEMA = new Category("SCHEMA");

    public static final Category SPECIFICATION = new Category("SPECIFICATION");

    /** The engine categories, in summary order. */
    private static final List<Category> ENGINE = List.of(XSD, SCHEMA, SPECIFICATION);

    public Category
    {
        Objects.requireNonNull(name, "name");
        String normalised = name.trim().toUpperCase(Locale.ROOT);
        if (normalised.isEmpty())
        {
            throw new IllegalArgumentException("a finding category needs a name");
        }
        name = normalised;
    }


    /**
     * The category for a name: the engine constant when the (normalised) name is an engine
     * category, so those stay the shared instances; else a family category.
     */
    public static Category of(String aName)
    {
        Category wanted = new Category(aName);
        for (Category engine : ENGINE)
        {
            if (engine.equals(wanted))
            {
                return engine;
            }
        }
        return wanted;
    }


    /** Whether a name (compared normalised) is one of the engine's own categories. */
    public static boolean isEngineCategoryName(String aName)
    {
        return ENGINE.contains(new Category(aName));
    }


    /** The engine categories first, in today's order, then family categories by name. */
    @Override
    public int compareTo(Category aOther)
    {
        int mine = ENGINE.indexOf(this);
        int theirs = ENGINE.indexOf(aOther);
        if (mine >= 0 || theirs >= 0)
        {
            if (mine < 0)
            {
                return 1;
            }
            if (theirs < 0)
            {
                return -1;
            }
            return Integer.compare(mine, theirs);
        }
        return name.compareTo(aOther.name);
    }


    /**
     * The name alone, as the former enum printed — Lombok {@code toString}s and log lines rely on
     * it.
     */
    @Override
    public String toString()
    {
        return name;
    }

}
