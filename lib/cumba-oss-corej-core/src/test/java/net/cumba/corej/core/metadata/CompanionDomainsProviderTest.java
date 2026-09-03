package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * EC-14 layer (ii) — {@link CompanionDomainsProvider} surfaces the companion's standard dataset
 * names and delegates everything else to the base run provider.
 */
class CompanionDomainsProviderTest
{

    /** Minimal test double returning recognisable, tagged values. */
    private static final class StubProvider implements MetadataProvider
    {

        private final String tag;

        private final @Nullable List<String> standardDatasetNames;

        StubProvider(String aTag, @Nullable List<String> aStandardDatasetNames)
        {
            tag = aTag;
            standardDatasetNames = aStandardDatasetNames;
        }


        @Override
        public @Nullable List<String> getStandardDatasetNames()
        {
            return standardDatasetNames;
        }


        @Override
        public @Nullable String getDeclaredDatasetClass(String aDatasetName)
        {
            return tag + "-DECLARED-" + aDatasetName;
        }


        @Override
        public List<String> getDeclaredSubClasses(String aDatasetName)
        {
            return List.of(tag + "-SUBCLASS-" + aDatasetName);
        }


        @Override
        public List<String> getDatasetNames()
        {
            return List.of(tag + "-DS");
        }


        @Override
        public List<String> getRequiredVariables(String domain)
        {
            return List.of(tag + "-REQ-" + domain);
        }


        @Override
        public List<String> getExpectedVariables(String domain)
        {
            return List.of(tag + "-EXP");
        }


        @Override
        public List<Map<String, String>> getModelVariablesForClass(String aModelClass)
        {
            return List.of(Map.of("who", tag, "class", aModelClass));
        }


        @Override
        public @Nullable List<Map<String, String>> getStandardModelVariablesForClass(
                IDataTable aTable, DatasetResolver aResolver, String aModelClass)
        {
            return List.of(Map.of("name", tag + "-" + aModelClass));
        }


        @Override
        public List<String> getColumnOrder(String domain)
        {
            return List.of(tag + "-COL");
        }


        @Override
        public List<String> getModelColumnOrder(String domain)
        {
            return List.of(tag + "-MODEL");
        }


        @Override
        public boolean isDomainCustom(String domain)
        {
            return true;
        }


        @Override
        public List<String> getCodelistTerms(String codelistCode)
        {
            return List.of(tag + "-TERM");
        }


        @Override
        public Map<String, String> getVariableMetadata(String domain, String variable)
        {
            return Map.of("who", tag);
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String domain)
        {
            return List.of(Map.of("who", tag));
        }


        @Override
        public Map<String, String> getDatasetMetadata(String domain)
        {
            return Map.of("who", tag);
        }


        @Override
        public boolean isCodelistExtensible(String codelistName)
        {
            return true;
        }


        @Override
        public Map<String, String> getCodelistTermMappings(String codelistName)
        {
            return Map.of("who", tag);
        }


        @Override
        public String getStandard()
        {
            return tag + "-STD";
        }


        @Override
        public String getVersion()
        {
            return tag + "-VER";
        }


        @Override
        public @Nullable String getDatasetClass(String aDomain)
        {
            return tag + "-CLASS";
        }
    }

    @Test
    void standardDatasetNamesComeFromCompanion()
    {
        MetadataProvider base = new StubProvider("BASE", null);
        MetadataProvider companion = new StubProvider("COMP", List.of("AE", "DM", "LB"));
        CompanionDomainsProvider p = new CompanionDomainsProvider(base, companion);
        assertEquals(List.of("AE", "DM", "LB"), p.getStandardDatasetNames());
    }


    @Test
    void everythingElseDelegatesToBase()
    {
        MetadataProvider base = new StubProvider("BASE", List.of("SHOULD_NOT_APPEAR"));
        MetadataProvider companion = new StubProvider("COMP", List.of("AE"));
        CompanionDomainsProvider p = new CompanionDomainsProvider(base, companion);

        assertEquals(List.of("BASE-DS"), p.getDatasetNames());
        assertEquals(List.of("BASE-REQ-AE"), p.getRequiredVariables("AE"));
        assertEquals(List.of("BASE-EXP"), p.getExpectedVariables("AE"));
        assertEquals(List.of("BASE-COL"), p.getColumnOrder("AE"));
        assertEquals(List.of("BASE-MODEL"), p.getModelColumnOrder("AE"));
        assertTrue(p.isDomainCustom("AE"));
        assertEquals(List.of("BASE-TERM"), p.getCodelistTerms("C1"));
        assertEquals(Map.of("who", "BASE"), p.getVariableMetadata("AE", "AETERM"));
        assertEquals(List.of(Map.of("who", "BASE")), p.getDomainVariables("AE"));
        assertEquals(Map.of("who", "BASE"), p.getDatasetMetadata("AE"));
        assertTrue(p.isCodelistExtensible("C1"));
        assertEquals(Map.of("who", "BASE"), p.getCodelistTermMappings("C1"));
        assertEquals("BASE-STD", p.getStandard());
        assertEquals("BASE-VER", p.getVersion());
        assertEquals("BASE-CLASS", p.getDatasetClass("AE"));
        // The two/three-arg getDatasetClass overloads delegate to the single-arg default.
        assertEquals("BASE-CLASS", p.getDatasetClass("AE", "AE"));
        assertEquals("BASE-CLASS", p.getDatasetClass("AE", "AE", null));
        // EC-85: the two class-keyed accessors delegate too (a production decorator that
        // inherited the interface default would turn every model_class rule into a SKIP).
        assertEquals(List.of(Map.of("who", "BASE", "class", "EVENTS")),
                p.getModelVariablesForClass("EVENTS"));
        assertEquals(List.of(Map.of("name", "BASE-EVENTS")),
                p.getStandardModelVariablesForClass(null, null, "EVENTS"));
    }


    /**
     * EC-85: the interface defaults are "not served" — null for the resolver, empty for the map.
     */
    @Test
    void classKeyedAccessorsDefaultToNotServed()
    {
        MetadataProvider bare = new net.cumba.corej.core.exec.StubMetadataProvider();
        assertNull(bare.getStandardModelVariablesForClass(null, null, "EVENTS"));
        assertTrue(bare.getModelVariablesForClass("EVENTS").isEmpty());
    }


    /**
     * ⛔ Phase 11 finding F6b — the declared tier (Fix #119) is the <b>base</b> provider's fact, and
     * this wrapper inherited both interface defaults ({@code null} / {@code List.of()}) until now,
     * i.e. it told every caller the sponsor declares nothing.
     *
     * <p>
     * Without the two overrides this asserts, the wrapper answers {@code null} and {@code []}. The
     * damage is invisible on the {@code RuleRunner} path (the define provider is consulted first
     * and answers), and live in rule <b>generation</b>, which has no define tier:
     * {@code LibraryValidator} builds {@code RuleGenerator} with this wrapper,
     * {@code RuleGenerator} reads both accessors off it, and the generation-time
     * {@code Scope.Data_Structures} / {@code Scope.Subclasses} gate then reverts to the column
     * heuristic — the rule is dropped into {@code skippedSourceRules} and never runs at all.
     * </p>
     */
    @Test
    void theDeclaredTierIsTakenFromTheBaseProviderNotTheInterfaceDefault()
    {
        MetadataProvider base = new StubProvider("BASE", null);
        MetadataProvider companion = new StubProvider("COMP", List.of("AE"));
        CompanionDomainsProvider p = new CompanionDomainsProvider(base, companion);

        assertEquals("BASE-DECLARED-ADSL", p.getDeclaredDatasetClass("ADSL"));
        assertEquals(List.of("BASE-SUBCLASS-ADSL"), p.getDeclaredSubClasses("ADSL"));
    }


    @Test
    void companionNullStandardDatasetNamesFlowThroughAsNull()
    {
        MetadataProvider base = new StubProvider("BASE", List.of("AE"));
        MetadataProvider companion = new StubProvider("COMP", null);
        CompanionDomainsProvider p = new CompanionDomainsProvider(base, companion);
        assertNull(p.getStandardDatasetNames());
    }
}
