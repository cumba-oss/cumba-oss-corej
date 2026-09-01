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
 * NRI-008: the {@code Value Check against Library Metadata} rule type — a data value must be a term
 * in the variable's non-extensible CDISC Library codelist. Exercises the native
 * {@code var_codelist_extensible("LIBRARY") == false and not empty(var_codelist_coded_values("LIBRARY"))
 * and not empty(value()) and value() not in var_codelist_coded_values("LIBRARY")} expression and
 * the library-provider population of {@code codelist_coded_values} / {@code codelist_extensible}.
 */
class ValueCheckAgainstLibraryCodelistTest
{

    private static Rule rule() throws Exception
    {
        String json = "{\"Core\":{\"Id\":\"R1\"}," + ""
                + "\"Sensitivity\":\"Record\",\"Check\":{\"all\":["
                + "{\"name\":\"library_variable_codelist_extensible\",\"operator\":\"equal_to\",\"value\":false},"
                + "{\"name\":\"library_variable_codelist_coded_values\",\"operator\":\"non_empty\"},"
                + "{\"name\":\"variable_value\",\"operator\":\"non_empty\"},"
                + "{\"name\":\"variable_value\",\"operator\":\"is_not_contained_by\","
                + "\"value\":\"library_variable_codelist_coded_values\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\",\"variable_value\"]}}";
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + json + "}}");
        Rule r = pkg.getRules().get("R1");
        assertEquals(null, r.getLoadError());
        assertNotNull(r.getCheckExpr(),
                "value-check-against-library must compile to a native checkExpr");
        return r;
    }


    /**
     * A library provider whose {@code VS.VSPOS} carries the given codelist values + extensibility.
     */
    private static MetadataProvider library(String codedValuesJson, String extensible)
    {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("name", "VSPOS");
        if (codedValuesJson != null)
        {
            attrs.put("codelist_coded_values", codedValuesJson);
        }
        if (extensible != null)
        {
            attrs.put("codelist_extensible", extensible);
        }
        return new StubMetadataProvider().variable("VS", attrs);
    }


    private static boolean fires(String cellValue, String codedValuesJson, String extensible)
        throws Exception
    {
        IDataTable vs = MockTable.of().name("VS").col("VSPOS", cellValue).build();
        RuleExecutionResult r = RuleRunner.execute(rule(), vs, _ -> null, "VS",
                library(codedValuesJson, extensible), null, null);
        return r.hasViolations();
    }


    @Test
    void valueNotInNonExtensibleCodelist_fires() throws Exception
    {
        assertTrue(fires("BOGUS", "[\"SUPINE\",\"STANDING\"]", "false"));
    }


    @Test
    void valueInCodelist_doesNotFire() throws Exception
    {
        assertFalse(fires("SUPINE", "[\"SUPINE\",\"STANDING\"]", "false"));
    }


    @Test
    void extensibleCodelist_doesNotFire() throws Exception
    {
        // An out-of-CT value on an extensible codelist is permitted — the extensible==false guard
        // excludes it.
        assertFalse(fires("BOGUS", "[\"SUPINE\",\"STANDING\"]", "true"));
    }


    @Test
    void emptyValue_doesNotFire() throws Exception
    {
        // An empty cell is a missing-value (different rule), not a CT violation.
        assertFalse(fires("", "[\"SUPINE\",\"STANDING\"]", "false"));
    }


    @Test
    void noLibraryCodelist_doesNotFire() throws Exception
    {
        // Variable with no library codelist: coded-values absent + extensible absent → no fire.
        assertFalse(fires("BOGUS", null, null));
    }
}
