package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.expr.ExpressionPrinter;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * GLOB-CT-001 ({@code DRAFT-900022}, {@code PLAN-coreJ-codelist-conformance} Phase 2): the codelist
 * bound to a variable in the define.xml (NCI C-code) must be the codelist the CDISC Library expects
 * for that variable. Exercises the cross-level {@code var_ccode("DEFINE") != var_ccode("LIBRARY")}
 * comparison with {@code non_empty} guards on both sides (a variable without a library expectation
 * or without a define binding is excluded, never fired on).
 */
class DefineCodelistIdentityVsLibraryTest
{

    private static Rule rule() throws Exception
    {
        String json = "{\"Core\":{\"Id\":\"R1\"}," + "\"Variable_Universe\":\"Define\","
                + "\"Sensitivity\":\"Dataset\",\"Check\":{\"all\":["
                + "{\"name\":\"library_variable_ccode\",\"operator\":\"non_empty\"},"
                + "{\"name\":\"define_variable_ccode\",\"operator\":\"non_empty\"},"
                + "{\"name\":\"define_variable_ccode\",\"operator\":\"not_equal_to\","
                + "\"value\":\"library_variable_ccode\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"define_variable_name\",\"define_variable_ccode\",\"library_variable_ccode\"]}}";
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + json + "}}");
        Rule r = pkg.getRules().get("R1");
        assertEquals(null, r.getLoadError());
        assertNotNull(r.getCheckExpr(), "GLOB-CT-001 shape must compile to a native checkExpr");
        return r;
    }


    @Test
    void raisesToCrossLevelAccessorForm() throws Exception
    {
        assertEquals(
                "not empty(var_ccode(\"LIBRARY\")) and not empty(var_ccode(\"DEFINE\")) "
                        + "and var_ccode(\"DEFINE\") != var_ccode(\"LIBRARY\")",
                ExpressionPrinter.print(rule().getCheckExpr()));
    }


    private static MetadataProvider provider(String ccode)
    {
        Map<String, String> attrs = ccode == null ? Map.of("name", "VSPOS")
                : Map.of("name", "VSPOS", "ccode", ccode);
        return new StubMetadataProvider().variable("VS", attrs);
    }


    private static boolean fires(String defineCcode, String libraryCcode) throws Exception
    {
        IDataTable vs = MockTable.of().name("VS").col("VSPOS", "SUPINE").build();
        RuleExecutionResult r = RuleRunner.execute(rule(), vs, _ -> null, "VS",
                provider(libraryCcode), null, provider(defineCcode));
        return r.hasViolations();
    }


    @Test
    void differentCodelistBound_fires() throws Exception
    {
        assertTrue(fires("C99999", "C71148"));
    }


    @Test
    void matchingCodelist_doesNotFire() throws Exception
    {
        assertFalse(fires("C71148", "C71148"));
    }


    @Test
    void noLibraryExpectation_guardExcludes() throws Exception
    {
        assertFalse(fires("C99999", null));
    }


    @Test
    void noDefineBinding_guardExcludes() throws Exception
    {
        assertFalse(fires(null, "C71148"));
    }
}
