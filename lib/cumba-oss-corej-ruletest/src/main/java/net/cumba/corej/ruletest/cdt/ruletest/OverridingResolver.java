package net.cumba.corej.ruletest.cdt.ruletest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * A {@link DatasetResolver.WithInventory} that layers a set of overrides plus a set of "dropped"
 * domain names on top of an underlying resolver. Used by {@code AbstractSdtmRuleTest} /
 * {@code AbstractAdamRuleTest} as the concrete return type of {@code resolverWith(...)} /
 * {@code resolverWithout(...)}.
 *
 * <p>
 * Unlike an anonymous lambda, this class exposes the overrides and dropped set so
 * {@link ScenarioCapture} can reconstruct the resolver state inside a captured scenario file.
 * </p>
 *
 * <p>
 * Lookup order:
 * </p>
 * <ol>
 * <li>If the domain name is in the dropped set → return {@code null}.</li>
 * <li>If the name is in overrides → return the override table.</li>
 * <li>Otherwise delegate to the underlying resolver.</li>
 * </ol>
 */
public final class OverridingResolver implements DatasetResolver.WithInventory
{

    private final DatasetResolver underlying;

    private final Map<String, IDataTable> overrides;

    private final Set<String> dropped;

    private OverridingResolver(DatasetResolver aUnderlying, Map<String, IDataTable> aOverrides,
            Set<String> aDropped)
    {
        this.underlying = aUnderlying;
        this.overrides = Collections.unmodifiableMap(new LinkedHashMap<>(aOverrides));
        this.dropped = Collections.unmodifiableSet(new LinkedHashSet<>(aDropped));
    }


    /**
     * Build a resolver with explicit overrides and no dropped names. Override keys are normalised
     * to upper case.
     */
    public static OverridingResolver overrides(DatasetResolver aUnderlying,
            Map<String, IDataTable> aOverrides)
    {
        Map<String, IDataTable> upper = new LinkedHashMap<>();
        if (aOverrides != null)
        {
            for (Map.Entry<String, IDataTable> e : aOverrides.entrySet())
            {
                if (e.getKey() != null)
                {
                    upper.put(e.getKey().toUpperCase(Locale.ROOT), e.getValue());
                }
            }
        }
        return new OverridingResolver(aUnderlying, upper, Set.of());
    }


    /**
     * Shorthand for a single-domain override.
     */
    public static OverridingResolver override(DatasetResolver aUnderlying, String aDomain,
            IDataTable aTable)
    {
        return overrides(aUnderlying, Map.of(aDomain.toUpperCase(Locale.ROOT), aTable));
    }


    /**
     * Build a resolver that hides the given names (returns {@code null} for them) while delegating
     * everything else to the underlying resolver.
     */
    public static OverridingResolver without(DatasetResolver aUnderlying, String... aDroppedDomains)
    {
        Set<String> set = new LinkedHashSet<>();
        for (String d : aDroppedDomains)
        {
            if (d != null)
            {
                set.add(d.toUpperCase(Locale.ROOT));
            }
        }
        return new OverridingResolver(aUnderlying, Map.of(), set);
    }


    /**
     * Returns a copy of this resolver with additional dropped names. Overrides are preserved.
     * Enables chaining of {@code resolverWith(...).without(...)} in tests that need both an
     * override and a dropped sibling.
     */
    public OverridingResolver without(String... aDroppedDomains)
    {
        Set<String> merged = new LinkedHashSet<>(this.dropped);
        for (String d : aDroppedDomains)
        {
            if (d != null)
            {
                merged.add(d.toUpperCase(Locale.ROOT));
            }
        }
        return new OverridingResolver(this.underlying, this.overrides, merged);
    }


    public Map<String, IDataTable> getOverrides()
    {
        return overrides;
    }


    public Set<String> getDropped()
    {
        return dropped;
    }


    public DatasetResolver getUnderlying()
    {
        return underlying;
    }


    @Override
    public @Nullable IDataTable resolve(String aName)
    {
        if (aName == null)
        {
            return null;
        }
        String up = aName.toUpperCase(Locale.ROOT);
        if (dropped.contains(up))
        {
            return null;
        }
        IDataTable ov = overrides.get(up);
        if (ov != null)
        {
            return ov;
        }
        return underlying.resolve(aName);
    }


    @Override
    public Set<String> availableDatasets()
    {
        Set<String> base;
        if (underlying instanceof DatasetResolver.WithInventory wi)
        {
            base = new LinkedHashSet<>(wi.availableDatasets());
        }
        else
        {
            base = new LinkedHashSet<>();
        }
        base.addAll(overrides.keySet());
        base.removeAll(dropped);
        return Collections.unmodifiableSet(base);
    }
}
