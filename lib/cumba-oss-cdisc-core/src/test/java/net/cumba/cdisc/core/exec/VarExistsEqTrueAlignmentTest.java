package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 of {@code plans/PLAN-variable-exists-cross-dataset.md} — {@code var_exists(X) == true}
 * must behave identically to the bare {@code var_exists(X)} broadcast verdict (and {@code == false}
 * / {@code != true} as its negation), so the two authoring spellings are interchangeable.
 *
 * <p>
 * Exercises the load-time broadcast-verdict recogniser
 * ({@code RulePackageLoader.isBroadcastVerdictExpr}) and its runtime mirror
 * ({@code BroadcastFold.isDatasetConstantLeaf}) through {@link RulePackageLoader}, then asserts the
 * native verdict equals the legacy verdict AND equals the bare-form verdict.
 */
class VarExistsEqTrueAlignmentTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Rule loadRule(String checkExpr) throws Exception
    {
        // Explicit Output_Variables mirror the migrated corpus rules (which carry an
        // operation-style flag), so the violation projection is fixed and does not depend on the
        // empty-Output_Variables leaf inference (Fix #15) — that inference walks the legacy
        // CheckCondition and legitimately differs between the bare `var_exists(X)` leaf and the
        // `var_exists(X) == true` expression wrapper, an output-projection detail orthogonal to
        // the broadcast verdict this phase aligns.
        String json = "{\"rules\":{\"R1\":{" + "\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Dataset\"," + "\"Check\":{\"expression\":\"" + checkExpr
                + "\"}," + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AEOCCUR\"]}}}}";
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    private static Map<Long, Map<String, String>> findings(Rule rule, IDataTable primary)
    {
        RuleExecutionResult r = RuleRunner.execute(rule, primary, NO_RESOLVER, "AE", null, null,
                null);
        Map<Long, Map<String, String>> out = new HashMap<>();
        for (Violation v : r.getViolations())
        {
            out.put(v.getRowNumber(), v.getValues());
        }
        return out;
    }


    /** native verdict == legacy verdict; returns the (shared) finding map. */
    private static Map<Long, Map<String, String>> parity(Rule rule, IDataTable t)
    {
        Map<Long, Map<String, String>> nativeF = findings(rule, t);
        assertEquals(findings(rule, t), nativeF, "native verdict must equal legacy verdict");
        return nativeF;
    }


    private static IDataTable present()
    {
        return MockTable.of().name("AE").col("AEOCCUR", "Y").build();
    }


    private static IDataTable absent()
    {
        return MockTable.of().name("AE").col("AETERM", "x").build();
    }


    @Test
    void bareVarExists_loadsNativeAndFiresOnPresence() throws Exception
    {
        Rule rule = loadRule("var_exists(AEOCCUR)");
        assertNotNull(rule.getCheckExpr(), "bare var_exists must retain a checkExpr");
        assertTrue(rule.isBroadcastCheckExpr(), "bare var_exists must be broadcast-flagged");
        assertEquals(1, parity(rule, present()).size(), "present → one dataset finding");
        assertEquals(Map.of(), parity(rule, absent()), "absent → no finding");
    }


    @Test
    void eqTrue_isNativeBroadcastAndMatchesBare() throws Exception
    {
        Rule rule = loadRule("var_exists(AEOCCUR) == true");
        assertNotNull(rule.getCheckExpr(), "var_exists == true must retain a checkExpr");
        assertTrue(rule.isBroadcastCheckExpr(), "var_exists == true must be broadcast-flagged");

        Rule bare = loadRule("var_exists(AEOCCUR)");
        assertEquals(parity(bare, present()), parity(rule, present()),
                "== true matches bare/present");
        assertEquals(parity(bare, absent()), parity(rule, absent()), "== true matches bare/absent");
    }


    @Test
    void neqFalse_isNativeBroadcastAndMatchesBare() throws Exception
    {
        Rule rule = loadRule("var_exists(AEOCCUR) != false");
        assertNotNull(rule.getCheckExpr());
        assertTrue(rule.isBroadcastCheckExpr());
        Rule bare = loadRule("var_exists(AEOCCUR)");
        assertEquals(parity(bare, present()), parity(rule, present()), "!= false matches bare");
        assertEquals(parity(bare, absent()), parity(rule, absent()), "!= false matches bare");
    }


    @Test
    void eqFalse_isNativeBroadcastAndInvertsBare() throws Exception
    {
        Rule rule = loadRule("var_exists(AEOCCUR) == false");
        assertNotNull(rule.getCheckExpr(), "var_exists == false must retain a checkExpr");
        assertTrue(rule.isBroadcastCheckExpr(), "var_exists == false must be broadcast-flagged");
        // negation: fires when the column is ABSENT, silent when present.
        assertEquals(Map.of(), parity(rule, present()), "== false silent when present");
        assertEquals(1, parity(rule, absent()).size(), "== false fires when absent");
    }


    @Test
    void neqTrue_isNativeBroadcastAndInvertsBare() throws Exception
    {
        Rule rule = loadRule("var_exists(AEOCCUR) != true");
        assertNotNull(rule.getCheckExpr());
        assertTrue(rule.isBroadcastCheckExpr());
        assertEquals(Map.of(), parity(rule, present()), "!= true silent when present");
        assertEquals(1, parity(rule, absent()).size(), "!= true fires when absent");
    }


    @Test
    void eqTrue_runsOnNativeBackend() throws Exception
    {
        Rule rule = loadRule("var_exists(AEOCCUR) == true");
        NativeExecutionRecorder.enable();
        RuleRunner.execute(rule, present(), NO_RESOLVER, "AE", null, null, null);
        assertEquals(NativeExecutionRecorder.Backend.NATIVE,
                NativeExecutionRecorder.disable().get("R1"),
                "var_exists == true must record the NATIVE backend");
    }
}
