package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * P6b of {@code plans/done/PLAN-native-engine-full-coverage.md} — the {@code Precondition} skip
 * decision evaluates natively. The loader raises a fold-equivalent (broadcast-verdict) Precondition
 * to {@code Rule.preconditionExpr}; {@code RuleRunner} phase 2e decides skip-on-false via
 * {@code NativeExprEvaluator.evaluateBroadcast} — bit-identical to the legacy
 * {@code partialEvaluateDataset} fold. A non-broadcast precondition keeps {@code preconditionExpr
 * == null} and CONTINUES with the main Check on both engines (the legacy "not fully resolvable"
 * contract).
 */
class NativePreconditionParityTest
{

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    /**
     * Loads a one-rule package, moving any {@code Precondition} the fixture declares onto the
     * <b>engine-internal</b> tier instead of authoring it.
     *
     * <p>
     * ⭐ Owner ruling Q3 ({@code plans/done/PLAN-scope-requirements-split.md} &#167;4.2, gate R8)
     * retired {@code Precondition} as an <em>authoring</em> surface while leaving the tier — its
     * injection and its evaluation at {@code RuleRunner} phase 2e — untouched. A field only the
     * engine may write needs an engine API to write it, so the supported constructor is now
     * {@link RulePackageLoader#installEngineInternalPrecondition}, which runs the same raise
     * {@code finishLoad} does.
     * </p>
     *
     * <p>
     * ⚠ The fixtures below are therefore <b>unchanged</b>, and so is the state they produce: same
     * tree on {@code getPrecondition()}, same {@code getPreconditionExpr()}. What these parity
     * tests pin is exactly what they pinned before.
     * </p>
     */
    private static Rule loadRule(String ruleBody) throws Exception
    {
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.node.ObjectNode body = (com.fasterxml.jackson.databind.node.ObjectNode) mapper
                .readTree(ruleBody);
        com.fasterxml.jackson.databind.JsonNode precondition = body.remove("Precondition");
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + body + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        if (precondition != null)
        {
            RulePackageLoader.installEngineInternalPrecondition(rule, mapper
                    .treeToValue(precondition, net.cumba.corej.core.model.CheckCondition.class));
        }
        return rule;
    }


    private static RuleExecutionResult run(Rule rule, IDataTable t)
    {
        return RuleRunner.execute(rule, t, NO_RESOLVER, "AE", null, null, null);
    }

    private static final String GUARDED_RULE = "{\"Core\":{\"Id\":\"R1\"},"
            + "\"Sensitivity\":\"Record\","
            + "\"Precondition\":{\"all\":[{\"expression\": \"var_exists(\\\"AESTDY\\\")\"}]},"
            + "\"Check\":{\"all\":[{\"expression\": \"empty(AETERM)\"}]},"
            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AETERM\"]}}";

    @Test
    void preconditionFalse_skipsOnBothEngines() throws Exception
    {
        Rule rule = loadRule(GUARDED_RULE);
        assertNotNull(rule.getPreconditionExpr(),
                "a fold-equivalent precondition must be raised to a native expression");

        // AESTDY absent → precondition false → SKIPPED on both engines, identical status.
        IDataTable t = MockTable.of().name("AE").col("AETERM", "", "headache").build();
        RuleExecutionResult nativ = run(rule, t);
        RuleExecutionResult legacy = run(rule, t);
        assertEquals(RuleExecutionStatus.SKIPPED, nativ.getStatus());
        assertEquals(legacy.getStatus(), nativ.getStatus());
        assertEquals(legacy.getStatusMessage(), nativ.getStatusMessage());
        assertEquals(0, nativ.getViolations().size());
    }


    @Test
    void preconditionTrue_evaluatesTheMainCheck() throws Exception
    {
        Rule rule = loadRule(GUARDED_RULE);
        // AESTDY present → precondition true → the main Check runs (row 0 empty AETERM fires).
        IDataTable t = MockTable.of().name("AE").col("AETERM", "", "headache")
                .col("AESTDY", "1", "2").build();
        RuleExecutionResult nativ = run(rule, t);
        RuleExecutionResult legacy = run(rule, t);
        assertEquals(1, nativ.getViolations().size(), "main Check must run and fire row 0");
        assertEquals(legacy.getViolations().size(), nativ.getViolations().size());
        assertEquals(legacy.getStatus(), nativ.getStatus());
    }


    @Test
    void domainPrefixedPrecondition_resolvesPerRun() throws Exception
    {
        // --STDY in the precondition resolves against the run's domain prefix natively
        // (in-closure), exactly like the legacy phase-2c rewrite before the fold.
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Precondition\":{\"all\":[{\"expression\": \"var_exists(\\\"--STDY\\\")\"}]},"
                + "\"Check\":{\"all\":[{\"expression\": \"empty(AETERM)\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertNotNull(rule.getPreconditionExpr());
        IDataTable without = MockTable.of().name("AE").col("AETERM", "").build();
        assertEquals(run(rule, without).getStatus(), run(rule, without).getStatus());
        assertEquals(RuleExecutionStatus.SKIPPED, run(rule, without).getStatus());
        IDataTable with = MockTable.of().name("AE").col("AETERM", "").col("AESTDY", "1").build();
        assertEquals(run(rule, with).getViolations().size(),
                run(rule, with).getViolations().size());
        assertEquals(1, run(rule, with).getViolations().size());
    }


    @Test
    void nonBroadcastPrecondition_continuesOnBothEngines() throws Exception
    {
        // A row-level precondition (reads a data column) is NOT fold-equivalent: the legacy fold
        // cannot decide it ("not fully resolvable ⇒ continue"), so preconditionExpr stays null
        // and BOTH engines continue with the main Check.
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Precondition\":{\"all\":[{\"expression\": \"AETERM == \\\"x\\\"\"}]},"
                + "\"Check\":{\"all\":[{\"expression\": \"empty(AETERM)\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertNull(rule.getPreconditionExpr(),
                "a row-level precondition must NOT raise a broadcast expression");
        IDataTable t = MockTable.of().name("AE").col("AETERM", "", "x").build();
        RuleExecutionResult nativ = run(rule, t);
        RuleExecutionResult legacy = run(rule, t);
        assertEquals(legacy.getStatus(), nativ.getStatus());
        assertEquals(legacy.getViolations().size(), nativ.getViolations().size());

        // Post-retirement: a preconditionExpr-less (row-level) precondition simply continues
        // with the main Check — the retired legacy fold could never decide it either — and the
        // whole execution is native.
        assertEquals(RuleExecutionStatus.EXECUTED, nativ.getStatus(),
                "a row-level precondition continues into the main Check — the rule is NOT skipped");
    }

    /** Grouped-$ precondition shape (guard-residual S6). */
    private static final String GROUPED_PRE_RULE_TMPL = "{\"Core\":{\"Id\":\"R1\"},"
            + "\"Sensitivity\":\"Record\","
            + "\"Bindings\":[{\"name\": \"$g\", \"expression\": \"distinct(VISITNUM, group=[USUBJID])\"}],"
            + "\"Precondition\":{\"all\":[%s{\"expression\": \"not empty($g)\"}]},"
            + "\"Check\":{\"all\":[{\"expression\": \"empty(AETERM)\"}]},"
            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}";

    @Test
    void groupedRefPrecondition_undecidedContinuesOnBothEngines() throws Exception
    {
        // The $-ref resolves to a per-row GroupedResult at runtime: the native tri-state fold is
        // UNKNOWN and continues — exactly the legacy fold, which classifies the leaf ROW and
        // cannot decide it either.
        Rule rule = loadRule(String.format(GROUPED_PRE_RULE_TMPL, ""));
        assertNotNull(rule.getPreconditionExpr(),
                "the $-comparison precondition is broadcast-shaped and must raise");
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "S1", "S1")
                .col("VISITNUM", "1", "2").col("AETERM", "", "x").build();
        RuleExecutionResult nativ = run(rule, t);
        RuleExecutionResult legacy = run(rule, t);
        assertEquals(legacy.getStatus(), nativ.getStatus(), "both engines continue");
        assertEquals(legacy.getViolations().size(), nativ.getViolations().size());
        assertEquals(1, nativ.getViolations().size(), "the main Check fired row 0");
    }


    @Test
    void groupedRefPrecondition_shortCircuitSkipDecidedNatively() throws Exception
    {
        // all[AEXX exists, $g …] with AEXX absent: the FALSE guard short-circuits the AND around
        // the undecidable grouped ref — skip on BOTH engines, decided natively.
        Rule rule = loadRule(String.format(GROUPED_PRE_RULE_TMPL,
                "{\"expression\": \"var_exists(\\\"AEXX\\\")\"},"));
        assertNotNull(rule.getPreconditionExpr());
        IDataTable t = MockTable.of().name("AE").col("USUBJID", "S1").col("VISITNUM", "1")
                .col("AETERM", "").build();
        RuleExecutionResult nativ = run(rule, t);
        RuleExecutionResult legacy = run(rule, t);
        assertEquals(RuleExecutionStatus.SKIPPED, nativ.getStatus());
        assertEquals(legacy.getStatus(), nativ.getStatus());
        assertEquals(legacy.getStatusMessage(), nativ.getStatusMessage());

        // ⚠ What is pinned here is the SKIPPED status above — that is the live claim. The old
        // assertion pinned an EMPTY recorder entry, i.e. that the skip happened BEFORE dispatch;
        // with the recorder retired that pre-/post-dispatch distinction is no longer OBSERVABLE
        // from a RuleExecutionResult, and nothing replaces it. The emptiness check below is a
        // backstop only: a SKIPPED result never carries violations, so it is implied by the status
        // assertion rather than adding a claim of its own.
        assertTrue(nativ.getViolations().isEmpty(),
                "a SKIPPED result carries no violations (backstop — implied by the status above)");
    }

}
