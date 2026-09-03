package net.cumba.corej.ruletest.cdt.ruletest;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * Self-contained {@link DatasetResolver.WithInventory} backed by a fixed, uppercase-keyed map of
 * datasets. No fall-through to any shared study: an unrelated domain reference resolves to
 * {@code null} and operations like {@code dataset_names}/{@code study_domains} see exactly the
 * datasets declared in the owning scenario file.
 */
final class ScenarioResolver implements DatasetResolver.WithInventory
{

    private final Map<String, IDataTable> byName;

    private ScenarioResolver(Map<String, IDataTable> aByName)
    {
        byName = aByName;
    }


    /**
     * Build a resolver from the given datasets, keyed by name (uppercased). Later entries with the
     * same upper-cased name overwrite earlier ones.
     */
    static ScenarioResolver of(Iterable<? extends IDataTable> aTables)
    {
        Map<String, IDataTable> m = new LinkedHashMap<>();
        for (IDataTable t : aTables)
        {
            String name = t.getMetaData().getName();
            if (name != null)
            {
                m.put(name.toUpperCase(Locale.ROOT), t);
            }
        }
        return new ScenarioResolver(Collections.unmodifiableMap(m));
    }


    @Override
    public @Nullable IDataTable resolve(String aName)
    {
        if (aName == null)
        {
            return null;
        }
        return byName.get(aName.toUpperCase(Locale.ROOT));
    }


    @Override
    public Set<String> availableDatasets()
    {
        return byName.keySet();
    }
}
