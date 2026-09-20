package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * F-corej-ct-02 / define-ct plan P1 — an <b>unresolvable</b> library codelist must produce a
 * visible {@code SKIPPED}, never a silent no-fire.
 *
 * <p>
 * Every shipped consumer of {@code var_codelist_extensible("LIBRARY")} gates on {@code == false}
 * (e.g. {@code CDISC-SEND-0296}), and both pre-fix miss behaviours — the accessor's defaulted
 * {@code true} and the attribute's absence — made that guard false. A run against the wrong CT
 * version (or none) therefore looked exactly like a clean run. The three states now are:
 * </p>
 * <ul>
 * <li>codelist bound and resolved → the rule evaluates (fires / passes on the real flag);</li>
 * <li>codelist bound but NOT resolvable → the rule reports {@code SKIPPED}, naming variable and
 * codelist;</li>
 * <li>no codelist bound at all → absent → the guard no-fires (D4, correct — nothing to check).</li>
 * </ul>
 */
class UnresolvableCodelistSkipTest
{

    private static Rule rule() throws Exception
    {
        // The CDISC-SEND-0296 shape: value-check against the non-extensible library codelist.
        String json = "{\"Core\":{\"Id\":\"R1\"}," + ""
                + "\"Sensitivity\":\"Record\",\"Check\":{\"all\":["
                + "{\"expression\": \"var_codelist_extensible(\\\"LIBRARY\\\") == false\"},"
                + "{\"expression\": \"not empty(var_codelist_coded_values(\\\"LIBRARY\\\"))\"},"
                + "{\"expression\": \"not empty(value())\"},"
                + "{\"expression\": \"value() not in var_codelist_coded_values(\\\"LIBRARY\\\")\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\",\"variable_value\"]}}";
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + json + "}}");
        Rule r = pkg.getRules().get("R1");
        assertEquals(null, r.getLoadError());
        assertNotNull(r.getCheckExpr());
        return r;
    }


    private static RuleExecutionResult run(Map<String, String> attrs) throws Exception
    {
        IDataTable vs = MockTable.of().name("VS").col("VSPOS", "BOGUS").build();
        MetadataProvider library = new StubMetadataProvider().variable("VS", attrs);
        return RuleRunner.execute(rule(), vs, _ -> null, "VS", library, null, null);
    }


    @Test
    void boundButUnresolvableCodelist_skipsWithReason() throws Exception
    {
        // The provider knows the variable HAS a codelist (the IG binds C66742) but the loaded CT
        // cannot resolve it — F-corej-ct-01's exact outcome when the CT selection is wrong.
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("name", "VSPOS");
        attrs.put("codelist", "C66742");
        RuleExecutionResult r = run(attrs);
        assertEquals(RuleExecutionStatus.SKIPPED, r.getStatus(),
                "an unresolvable codelist must SKIP visibly, not silently no-fire");
        assertFalse(r.hasViolations());
        assertNotNull(r.getStatusMessage());
        assertTrue(r.getStatusMessage().contains("C66742"), r.getStatusMessage());
        assertTrue(r.getStatusMessage().contains("VSPOS"), r.getStatusMessage());
    }


    @Test
    void resolvedNonExtensibleCodelist_stillFires() throws Exception
    {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("name", "VSPOS");
        attrs.put("codelist", "POSITION");
        attrs.put("codelist_extensible", "false");
        attrs.put("codelist_coded_values", "[\"SUPINE\",\"STANDING\"]");
        RuleExecutionResult r = run(attrs);
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus());
        assertTrue(r.hasViolations(), "the discriminating case: a resolved codelist still fires");
    }


    @Test
    void noCodelistBound_noFireNoSkip() throws Exception
    {
        // A variable without a codelist has nothing to check — absent stays absent (D4).
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("name", "VSPOS");
        RuleExecutionResult r = run(attrs);
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus());
        assertFalse(r.hasViolations());
    }
}
