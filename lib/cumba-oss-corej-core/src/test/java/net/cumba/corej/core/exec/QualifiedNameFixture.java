package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.KeyedJoinFixtures;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;

/**
 * The runner-level fixture of {@code PLAN-qualified-name-uniformity-review} phase 1's per-surface
 * classes: a primary {@code P} and a dataset {@code J} with the IDENTICAL column set and data,
 * joined 1:1 on {@code K}, so that a surface reading {@code J.X} and the same surface reading
 * {@code X} have exactly one legitimate answer. The function harness is
 * {@code expr.eval.QualifiedNameUniformityTest}; these classes cover the surfaces that have no
 * descriptor to generate a roster from (plan §3 phase 1).
 */
final class QualifiedNameFixture
{

    private QualifiedNameFixture()
    {
    }


    /** The six-row table under {@code name}; {@code except} (or {@code null}) drops a column. */
    static IDataTable table(String name, @Nullable String except)
    {
        RealTables t = RealTables.of(name);
        if (!"K".equals(except))
        {
            t.str("K", "k1", "k2", "k3", "k4", "k5", "k6");
        }
        if (!"S".equals(except))
        {
            t.str("S", "a", "b", "", "A", "b", "c");
        }
        if (!"N".equals(except))
        {
            t.dbl("N", 1.0, 2.5, MissingValue.MIS_UNKNOWN.asDouble(), MissingValue.MIS_A.asDouble(),
                    1.0, 3.0);
        }
        if (!"G".equals(except))
        {
            t.str("G", "g1", "g1", "g2", "g2", "g3", "g3");
        }
        if (!"L".equals(except))
        {
            t.lng("L", 1L, 2L, null, 3L, 1L, 2L);
        }
        return t.build();
    }


    static IDataTable primary()
    {
        return table("P", null);
    }


    static IDataTable joined()
    {
        return table("J", null);
    }


    /** An EXACT-case inventory over the given tables (a lower-case name stays lower-case). */
    static DatasetResolver.WithInventory exactInventory(IDataTable... tables)
    {
        Map<String, IDataTable> byName = new LinkedHashMap<>();
        for (IDataTable t : tables)
        {
            byName.put(String.valueOf(t.getMetaData().getName()), t);
        }
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String aName)
            {
                return byName.get(aName);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return new TreeSet<>(byName.keySet());
            }
        };
    }


    /** A Record rule over {@code P}: the JSON body minus the Core / Sensitivity boilerplate. */
    static String ruleJson(String sensitivity, String body)
    {
        return "{\"rules\":{\"R\":{\"Core\":{\"Id\":\"T-QNU\"},\"Sensitivity\":\"" + sensitivity
                + "\"," + body + "}}}";
    }


    static String matchJ(String extra)
    {
        return "\"Match_Datasets\":[{\"Name\":\"J\",\"Keys\":[\"K\"],\"Join_Type\":\"left\"" + extra
                + "}]";
    }


    static String check(String expression)
    {
        return "\"Check\":{\"expression\":\""
                + expression.replace("\\", "\\\\").replace("\"", "\\\"") + "\"}";
    }


    static String outcome(String... outputVariables)
    {
        StringBuilder sb = new StringBuilder(
                "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[");
        for (int i = 0; i < outputVariables.length; i++)
        {
            sb.append(i == 0 ? "" : ",").append('"').append(outputVariables[i]).append('"');
        }
        return sb.append("]}").toString();
    }


    /** Loads a package JSON (declaring the join keys as the loader's gate wants, D-REQ). */
    static Rule load(String packageJson, boolean declareKeys)
    {
        try
        {
            String json = declareKeys ? KeyedJoinFixtures.declared(packageJson) : packageJson;
            Rule r = RulePackageLoader.loadFromString(json).getRules().get("R");
            assertNotNull(r, "the fixture rule R");
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    static Rule loadClean(String packageJson, boolean declareKeys)
    {
        Rule r = load(packageJson, declareKeys);
        assertNull(r.getLoadError(), r.getLoadError());
        return r;
    }


    static RuleExecutionResult run(Rule rule, IDataTable primary, DatasetResolver inventory)
    {
        return RuleRunnerCalls.execute(rule, primary, inventory);
    }


    static RuleExecutionResult executed(Rule rule, IDataTable primary, DatasetResolver inventory)
    {
        RuleExecutionResult r = run(rule, primary, inventory);
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), r.getStatusMessage());
        return r;
    }


    /** The violation rows, in report order. */
    static List<Long> rows(RuleExecutionResult result)
    {
        List<Long> out = new ArrayList<>();
        for (Violation v : result.getViolations())
        {
            out.add(v.getRowNumber());
        }
        return out;
    }


    /** The reported values per violation, in report order (the writers print exactly these). */
    static List<Map<String, String>> values(RuleExecutionResult result)
    {
        List<Map<String, String>> out = new ArrayList<>();
        for (Violation v : result.getViolations())
        {
            out.add(new LinkedHashMap<>(v.getValues()));
        }
        return out;
    }


    /** {@code values} with every key renamed through {@code rename} (e.g. {@code J.S → S}). */
    static List<Map<String, String>> renamed(List<Map<String, String>> values,
            Map<String, String> rename)
    {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> v : values)
        {
            Map<String, String> m = new LinkedHashMap<>();
            for (Map.Entry<String, String> e : v.entrySet())
            {
                m.put(rename.getOrDefault(e.getKey(), e.getKey()), e.getValue());
            }
            out.add(m);
        }
        return out;
    }
}
