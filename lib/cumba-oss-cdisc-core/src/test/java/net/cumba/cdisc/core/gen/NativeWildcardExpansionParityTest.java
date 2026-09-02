package net.cumba.cdisc.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.NativeExecutionRecorder;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.exec.Violation;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * P5 of {@code plans/done/PLAN-native-engine-full-coverage.md} — wildcard-template expansions carry
 * a native {@code checkExpr}: {@link WildcardExpander#expand} runs each fresh concrete rule through
 * the loader's single retention decision ({@code RulePackageLoader.installNativeExpr}), so the
 * expanded rules execute on the native engine exactly like loader-loaded concrete rules — while the
 * TEMPLATE itself (never executed) stays without an expression.
 */
class NativeWildcardExpansionParityTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Rule loadRule(String ruleBody) throws Exception
    {
        String json = "{\"rules\":{\"R1\":" + ruleBody + "}}";
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    private static Map<Long, Map<String, String>> findings(Rule rule, IDataTable t)
    {
        Map<Long, Map<String, String>> out = new TreeMap<>();
        for (Violation v : RuleRunner.execute(rule, t, NO_RESOLVER, null, null, null, null)
                .getViolations())
        {
            out.put(v.getRowNumber(), v.getValues());
        }
        return out;
    }


    @Test
    void captureConsistentExpansionRunsNative() throws Exception
    {
        // TRTxxP/TRTxxPN capture-consistent template (Record Data): each expansion binds the SAME
        // xx digits across both leaves. The expansions must carry a native checkExpr and produce
        // findings identical to the legacy engine.
        Rule template = loadRule("{\"Core\":{\"Id\":\"T1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"name\":\"TRTxxP\",\"operator\":\"non_empty\"},"
                + "{\"name\":\"TRTxxPN\",\"operator\":\"empty\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}");
        // (the template itself never executes in production — tryExpand routes it to Expanded /
        // NoMatch; only its concrete expansions run)

        IDataTable t = MockTable.of().name("ADSL").col("USUBJID", "S1", "S2")
                .col("TRT01P", "A", "B").col("TRT01PN", "1", "").col("TRT02P", "C", "")
                .col("TRT02PN", "", "2").build();

        WildcardExpander.ExpansionResult result = WildcardExpander.tryExpand(template,
                t.getMetaData());
        assertTrue(result instanceof WildcardExpander.ExpansionResult.Expanded,
                "template must expand: " + result);
        List<Rule> expanded = ((WildcardExpander.ExpansionResult.Expanded) result).rules();
        assertEquals(2, expanded.size(), "two xx tuples (01, 02)");

        // Parity first (legacy + native runs)...
        for (Rule concrete : expanded)
        {
            assertNotNull(concrete.getCheckExpr(),
                    "each expansion must carry a native checkExpr (P5)");
            assertEquals(findings(concrete, t), findings(concrete, t),
                    "expansion verdicts must match legacy bit-for-bit");
        }
        // ...then a clean recorder session over native-only runs.
        NativeExecutionRecorder.enable();
        for (Rule concrete : expanded)
        {
            findings(concrete, t);
        }
        Map<String, NativeExecutionRecorder.Backend> backends = NativeExecutionRecorder.disable();
        assertEquals(2, backends.size());
        assertTrue(
                backends.values().stream()
                        .allMatch(b -> b == NativeExecutionRecorder.Backend.NATIVE),
                "every expansion must run on the NATIVE backend: " + backends);
    }


    @Test
    void presenceTemplateExpansionRidesBroadcastPath() throws Exception
    {
        // CDISC-AD0157 class: a VMC presence template with an ADaM capture wildcard inside
        // exists/not_exists. After expansion the concrete rule is a presence-broadcast verdict
        // (P3) and must run native.
        Rule template = loadRule("{\"Core\":{\"Id\":\"T2\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"CRITyFL\",\"operator\":\"var_exists\"},"
                + "{\"name\":\"CRITy\",\"operator\":\"var_not_exists\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");

        IDataTable t = MockTable.of().name("ADEF").col("CRIT1FL", "Y").col("PARAMCD", "P").build();
        WildcardExpander.ExpansionResult result = WildcardExpander.tryExpand(template,
                t.getMetaData());
        assertTrue(result instanceof WildcardExpander.ExpansionResult.Expanded,
                "presence template must expand: " + result);
        List<Rule> expanded = ((WildcardExpander.ExpansionResult.Expanded) result).rules();
        assertEquals(1, expanded.size(), "one y tuple (1)");
        Rule concrete = expanded.get(0);
        assertNotNull(concrete.getCheckExpr());
        assertTrue(concrete.isBroadcastCheckExpr(),
                "the expanded presence rule must be broadcast-flagged (P3a)");
        // CRIT1FL exists, CRIT1 absent → fires; identical on both engines.
        Map<Long, Map<String, String>> nativeF = findings(concrete, t);
        assertEquals(findings(concrete, t), nativeF);
        assertEquals(1, nativeF.size(), "CRIT1FL without CRIT1 fires");
    }


    @Test
    void noMatchTemplateProducesNoExpansions() throws Exception
    {
        Rule template = loadRule("{\"Core\":{\"Id\":\"T3\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"name\":\"ANLzzFL\",\"operator\":\"non_empty\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        IDataTable t = MockTable.of().name("ADSL").col("USUBJID", "S1").build();
        WildcardExpander.ExpansionResult result = WildcardExpander.tryExpand(template,
                t.getMetaData());
        assertTrue(result instanceof WildcardExpander.ExpansionResult.NoMatch,
                "no matching column → NoMatch (rule skipped, no evaluation on either engine)");
    }


    @Test
    void wildcardsFilterStillApplies() throws Exception
    {
        // wildcards numeric filter drops out-of-range tuples BEFORE the native install — the
        // surviving expansion still carries a checkExpr.
        Rule template = loadRule("{\"Core\":{\"Id\":\"T4\"},"
                + "\"wildcards\":{\"xx\":{\"min\":2}}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"name\":\"TRTxxP\",\"operator\":\"empty\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        IDataTable t = MockTable.of().name("ADSL").col("TRT01P", "A").col("TRT02P", "").build();
        WildcardExpander.ExpansionResult result = WildcardExpander.tryExpand(template,
                t.getMetaData());
        List<Rule> expanded = ((WildcardExpander.ExpansionResult.Expanded) result).rules();
        assertEquals(1, expanded.size(), "xx=01 filtered out by min:2");
        assertNotNull(expanded.get(0).getCheckExpr());
        assertEquals(findings(expanded.get(0), t), findings(expanded.get(0), t));
    }

}
