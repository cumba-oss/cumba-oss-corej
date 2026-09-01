package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * GLOB-CT-005 variant ({@code DRAFT-900023}, {@code PLAN-coreJ-codelist-conformance} Phase 3): a
 * define.xml item flagged {@code def:ExtendedValue="Yes"} on a codelist that is non-extensible in
 * the CDISC Library. Exercises the {@code var_codelist_extended_values("DEFINE")} list accessor
 * behind the {@code var_codelist_extensible("LIBRARY") == false} guard (null-safe on variables
 * without a library codelist).
 */
class DefineExtendedValuesVsLibraryTest
{

    private static Rule rule() throws Exception
    {
        String json = "{\"Core\":{\"Id\":\"R1\"}," + "\"Variable_Universe\":\"Define\","
                + "\"Sensitivity\":\"Dataset\",\"Check\":{\"all\":["
                + "{\"name\":\"library_variable_codelist_extensible\",\"operator\":\"equal_to\",\"value\":false},"
                + "{\"name\":\"define_variable_codelist_extended_values\",\"operator\":\"non_empty\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"define_variable_name\",\"define_variable_codelist_extended_values\"]}}";
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + json + "}}");
        Rule r = pkg.getRules().get("R1");
        assertEquals(null, r.getLoadError());
        assertNotNull(r.getCheckExpr(), "GLOB-CT-005 variant must compile to a native checkExpr");
        return r;
    }


    private static MetadataProvider library(String extensible)
    {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("name", "VSPOS");
        if (extensible != null)
        {
            attrs.put("codelist_extensible", extensible);
        }
        return new StubMetadataProvider().variable("VS", attrs);
    }


    private static MetadataProvider define(String extendedValuesJson)
    {
        return new StubMetadataProvider().variable("VS",
                Map.of("name", "VSPOS", "codelist_extended_values", extendedValuesJson));
    }


    private static boolean fires(String extendedValuesJson, String libraryExtensible)
        throws Exception
    {
        IDataTable vs = MockTable.of().name("VS").col("VSPOS", "SUPINE").build();
        RuleExecutionResult r = RuleRunner.execute(rule(), vs, _ -> null, "VS",
                library(libraryExtensible), null, define(extendedValuesJson));
        return r.hasViolations();
    }


    @Test
    void extendedTermsOnNonExtensibleCodelist_fires() throws Exception
    {
        assertTrue(fires("[\"BOGUS\"]", "false"));
    }


    @Test
    void extendedTermsOnExtensibleCodelist_doesNotFire() throws Exception
    {
        assertFalse(fires("[\"BOGUS\"]", "true"));
    }


    @Test
    void noExtendedTerms_doesNotFire() throws Exception
    {
        assertFalse(fires("[]", "false"));
    }


    @Test
    void noLibraryCodelist_guardExcludes() throws Exception
    {
        assertFalse(fires("[\"BOGUS\"]", null));
    }
}
