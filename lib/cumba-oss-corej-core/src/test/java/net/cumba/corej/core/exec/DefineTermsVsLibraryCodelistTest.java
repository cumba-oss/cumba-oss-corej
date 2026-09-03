package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * CT-004 (NRI-007, {@code PLAN-value-check-against-library-codelist} Phase 5b): every term the
 * define.xml enumerates for a variable's non-extensible library codelist must be a published
 * library term. The check is {@code library_variable_codelist_coded_values not_contains_all
 * define_variable_codelist_coded_values} (source set = library, required list = define — NOT the
 * inverted {@code is_not_contained_by} form, whose both-lists semantics mean "none of the define
 * terms are in the library"), guarded by {@code library_variable_codelist_extensible == false}.
 * Exercises the native path for {@code not contains_all} over two list-valued metadata accessors.
 */
class DefineTermsVsLibraryCodelistTest
{

    private static Rule rule() throws Exception
    {
        String json = "{\"Core\":{\"Id\":\"R1\"}," + "\"Variable_Universe\":\"Define\","
                + "\"Sensitivity\":\"Dataset\",\"Check\":{\"all\":["
                + "{\"name\":\"library_variable_codelist_extensible\",\"operator\":\"equal_to\",\"value\":false},"
                + "{\"name\":\"library_variable_codelist_coded_values\",\"operator\":\"not_contains_all\","
                + "\"value\":\"define_variable_codelist_coded_values\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"variable_name\",\"define_variable_codelist_coded_values\"]}}";
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + json + "}}");
        Rule r = pkg.getRules().get("R1");
        assertEquals(null, r.getLoadError());
        assertNotNull(r.getCheckExpr(), "CT-004 shape must compile to a native checkExpr");
        return r;
    }


    /** The raise must produce the accessor-call form on BOTH sides of contains_all. */
    @Test
    void raisesToAccessorCallForm() throws Exception
    {
        assertEquals(
                "var_codelist_extensible(\"LIBRARY\") == false and "
                        + "not contains_all(var_codelist_coded_values(variable_name, \"LIBRARY\"), "
                        + "var_codelist_coded_values(variable_name, \"DEFINE\"))",
                ExpressionPrinter.print(rule().getCheckExpr()));
    }


    /** Library provider: VS.VSPOS carries the given codelist values + extensibility. */
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


    /** Define provider: VS.VSPOS enumerates the given codelist values. */
    private static MetadataProvider define(String codedValuesJson)
    {
        return new StubMetadataProvider().variable("VS",
                Map.of("name", "VSPOS", "codelist_coded_values", codedValuesJson));
    }


    private static boolean fires(String defineValuesJson, String libraryValuesJson,
            String extensible)
        throws Exception
    {
        IDataTable vs = MockTable.of().name("VS").col("VSPOS", "SUPINE").build();
        RuleExecutionResult r = RuleRunner.execute(rule(), vs, _ -> null, "VS",
                library(libraryValuesJson, extensible), null, define(defineValuesJson));
        return r.hasViolations();
    }


    @Test
    void defineTermOutsideNonExtensibleLibraryCodelist_fires() throws Exception
    {
        assertTrue(fires("[\"SUPINE\",\"BOGUS\"]", "[\"SUPINE\",\"STANDING\"]", "false"));
    }


    @Test
    void defineTermsAllPublished_doesNotFire() throws Exception
    {
        assertFalse(fires("[\"SUPINE\",\"STANDING\"]", "[\"SUPINE\",\"STANDING\"]", "false"));
    }


    @Test
    void extensibleLibraryCodelist_doesNotFire() throws Exception
    {
        assertFalse(fires("[\"SUPINE\",\"BOGUS\"]", "[\"SUPINE\",\"STANDING\"]", "true"));
    }


    @Test
    void noLibraryCodelist_guardExcludes_doesNotFire() throws Exception
    {
        // Variable without a library codelist: extensible resolves null -> the == false guard
        // fails -> no fire, even though the empty source set alone would flag every define term.
        assertFalse(fires("[\"SUPINE\",\"BOGUS\"]", null, null));
    }


    @Test
    void emptyDefineList_doesNotFire() throws Exception
    {
        assertFalse(fires("[]", "[\"SUPINE\",\"STANDING\"]", "false"));
    }
}
