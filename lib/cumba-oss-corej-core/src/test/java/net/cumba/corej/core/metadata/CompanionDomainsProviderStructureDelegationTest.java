package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-library-template-label-resolution} — the two structure-keyed defaults are
 * SELF_DELEGATING on the interface (a domain-keyed provider's whole answer), so the decorator guard
 * does not demand their delegation; {@link CompanionDomainsProvider} wraps the ADaM run provider in
 * production and MUST forward them, else every non-ADSL answer silently reverts to the domain-keyed
 * read. This pins the forwarding, argument by argument.
 */
class CompanionDomainsProviderStructureDelegationTest
{

    /** A base that records every call and answers something a default would not. */
    private static MetadataProvider recording(List<String> calls)
    {
        return (MetadataProvider) Proxy.newProxyInstance(MetadataProvider.class.getClassLoader(),
                new Class<?>[]
                {
                        MetadataProvider.class
                }, (proxy, method, args) ->
                {
                    calls.add(method.getName() + Arrays.deepToString(args));
                    Class<?> rt = method.getReturnType();
                    if (method.getName().equals("getVariableMetadata") && args != null
                            && args.length == 5)
                    {
                        return Map.of("name", "FROM-BASE");
                    }
                    if (method.getName().equals("getColumnOrderForStructures"))
                    {
                        return List.of("FROM-BASE");
                    }
                    if (rt == boolean.class)
                    {
                        return false;
                    }
                    if (rt == List.class)
                    {
                        return List.of();
                    }
                    if (rt == Map.class)
                    {
                        return Map.of();
                    }
                    if (rt == Optional.class)
                    {
                        return Optional.empty();
                    }
                    return null;
                });
    }


    @Test
    void bothStructureKeyedReadsReachTheBaseWithTheSameArguments()
    {
        List<String> calls = new ArrayList<>();
        MetadataProvider base = recording(calls);
        MetadataProvider companion = recording(new ArrayList<>());
        CompanionDomainsProvider wrapped = new CompanionDomainsProvider(base, companion);
        IDataTable adlb = MockTable.of().col("PARAMCD", "ALB").col("AVAL", "1").name("ADLB")
                .build();
        List<String> structures = List.of(AdamDataStructureDetector.BDS);
        List<String> subclasses = List.of();

        assertEquals(Map.of("name", "FROM-BASE"),
                wrapped.getVariableMetadata(adlb, structures, subclasses, "ADLB", "ASTDT"));
        assertEquals(List.of("FROM-BASE"),
                wrapped.getColumnOrderForStructures(structures, subclasses, "ADLB"));
        assertEquals(2, calls.size(), calls.toString());
        assertEquals(true,
                calls.get(0).startsWith("getVariableMetadata[")
                        && calls.get(0).endsWith(", [BASIC DATA STRUCTURE], [], ADLB, ASTDT]"),
                calls.get(0));
        assertEquals("getColumnOrderForStructures[[BASIC DATA STRUCTURE], [], ADLB]", calls.get(1));
    }
}
