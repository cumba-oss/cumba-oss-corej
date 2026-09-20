package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.BitSet;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Guard-residual D2/D2b parity ({@code plans/done/PLAN-native-runtime-guard-residual.md}) — the
 * scenarios that previously fell through the runtime broadcast-safety guard (or the Step-1
 * fold-to-constant return) into legacy verdict production now decide natively via the tri-state
 * {@code BroadcastFold}, with verdicts byte-identical to the legacy engine and the rule reaching a
 * verdict (status {@code EXECUTED}) rather than skipping.
 */
class NativeGuardFallthroughParityTest
{

    private static Rule loadRule(String ruleBody) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + ruleBody + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        assertNotNull(rule.getCheckExpr(), "rule must retain a native checkExpr");
        return rule;
    }


    private static RuleExecutionResult run(Rule rule, IDataTable t, DatasetResolver resolver)
    {
        return RuleRunner.execute(rule, t, resolver, "AE", null, null, null);
    }


    private static BitSet rows(RuleExecutionResult r)
    {
        BitSet bs = new BitSet();
        for (Violation v : r.getViolations())
        {
            bs.set((int) v.getRow());
        }
        return bs;
    }

    /**
     * S2 shape — a mixed Check whose decidable guard short-circuits AROUND a runtime GroupedResult
     * {@code $}-ref: {@code any[AESTDY not_exists, VISITNUM not in $grouped]}.
     */
    private static final String S2_RULE = "{\"Core\":{\"Id\":\"R1\"},"
            + "\"Sensitivity\":\"Record\","
            + "\"Bindings\":[{\"name\": \"$sv_visitnum\", \"expression\": \"distinct(VISITNUM, domain=\\\"SV\\\", group=[USUBJID])\"}],"
            + "\"Check\":{\"any\":[{\"expression\": \"var_not_exists(\\\"AESTDY\\\")\"},"
            + "{\"expression\": \"VISITNUM not in $sv_visitnum\"}]},"
            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"VISITNUM\"]}}";

    private static final IDataTable SV = MockTable.of().name("SV").col("USUBJID", "S1", "S2")
            .col("VISITNUM", "1", "2").build();

    private static final DatasetResolver SV_RESOLVER = n -> "SV".equals(n) ? SV : null;

    @Test
    void s2_shortCircuitAroundGroupedRef_decidesNatively() throws Exception
    {
        Rule rule = loadRule(S2_RULE);

        // AESTDY absent → the not_exists guard is TRUE → the legacy Step-1 fold collapses the
        // any[] to a CONSTANT and emits ONE dataset-level violation at row 0 — even though the
        // rule is row-based. The native fold must decide identically (one violation, row 0),
        // never per-row findings, and never evaluate the grouped $-ref.
        IDataTable collapsed = MockTable.of().name("AE").col("USUBJID", "S1", "S1", "S2")
                .col("VISITNUM", "1", "3", "2").build();
        RuleExecutionResult nativ = run(rule, collapsed, SV_RESOLVER);
        RuleExecutionResult legacy = run(rule, collapsed, SV_RESOLVER);
        assertEquals(1, nativ.getViolations().size(),
                "the TRUE-collapse emits exactly ONE dataset-level violation");
        assertEquals(legacy.getViolations().size(), nativ.getViolations().size());
        assertEquals(legacy.getViolations().get(0).getRowNumber(),
                nativ.getViolations().get(0).getRowNumber());
        assertEquals(RuleExecutionStatus.EXECUTED, nativ.getStatus(),
                "the collapse must be DECIDED by the native fold — a verdict, not a skip");
    }


    @Test
    void s2_undecidedResidue_runsNativePerRow() throws Exception
    {
        Rule rule = loadRule(S2_RULE);

        // AESTDY present → the guard is FALSE → any[FALSE, U] is UNKNOWN → the regular dispatch
        // evaluates the full expr per row natively (grouped membership resolves per row).
        IDataTable residue = MockTable.of().name("AE").col("USUBJID", "S1", "S1", "S2")
                .col("VISITNUM", "1", "3", "1").col("AESTDY", "1", "2", "3").build();
        RuleExecutionResult nativ = run(rule, residue, SV_RESOLVER);
        RuleExecutionResult legacy = run(rule, residue, SV_RESOLVER);
        assertEquals(rows(legacy), rows(nativ), "per-row grouped residue must match legacy");
        BitSet expected = new BitSet();
        expected.set(1); // S1@3 not among S1's visits
        expected.set(2); // S2@1 not among S2's visits (per-row group resolution)
        assertEquals(expected, rows(nativ));
        assertEquals(RuleExecutionStatus.EXECUTED, nativ.getStatus(),
                "the undecided residue must be evaluated per row, not skipped");
    }


    @Test
    void s1_existsGuardFalseCollapse_decidesNativelyEmpty() throws Exception
    {
        // all[X exists, X == "Y"] on a dataset without X: both engines produce the empty verdict;
        // the native fold decides it — it is the only engine left — where previously the legacy
        // Step-1 fold returned the constant.
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"expression\": \"var_exists(\\\"AESTDY\\\")\"},"
                + "{\"expression\": \"AESTDY == \\\"1\\\"\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        IDataTable t = MockTable.of().name("AE").col("AETERM", "x", "y").build();
        RuleExecutionResult nativ = run(rule, t, _ -> null);
        RuleExecutionResult legacy = run(rule, t, _ -> null);
        assertEquals(0, nativ.getViolations().size());
        assertEquals(legacy.getViolations().size(), nativ.getViolations().size());
        assertEquals(RuleExecutionStatus.EXECUTED, nativ.getStatus(),
                "the empty verdict is DECIDED by the native fold — not a skip, which would also "
                        + "report zero violations");
    }


    @Test
    void missingColumnNotShape_foldsToOneDatasetFinding() throws Exception
    {
        // not(AEXX == "Y") with AEXX absent everywhere.
        //
        // UPDATED BY EC-43, AND AGAIN BY D111. The Fix #40 missing-column fold turned the leaf
        // FALSE (wrong polarity); EC-43 sent it to the row path, firing per row like a present-
        // but-all-blank column; D111 (typed-expression plan, phase 7) rules the granularity split:
        // the polarity stays EC-43's — the leaf folds to all-missing and `not(== "Y")` FIRES —
        // but absence is a schema fact decided before a row is read, so the check folds at
        // DATASET level and reports ONE finding, not one per row. The present-but-all-blank
        // sibling below keeps its per-row findings — that asymmetry is D111's content, pinned
        // corpus-side by EC43-not-equal-absent-operator / EC43-absent-equals-blank-control.
        //
        // (Note the two runs here are both the NATIVE backend — `nativ` and `legacy` invoke the
        // same `run`. This asserts a Java-internal shape, not Java/Python parity.)
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"not\":{\"all\":[{\"expression\": \"AEXX == \\\"Y\\\"\"}]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        IDataTable t = MockTable.of().name("AE").col("AETERM", "x", "y", "z").build();
        RuleExecutionResult nativ = run(rule, t, _ -> null);
        RuleExecutionResult legacy = run(rule, t, _ -> null);
        assertEquals(1, nativ.getViolations().size(),
                "one dataset-level finding — the absent-column fact reports once (D111)");
        assertEquals(legacy.getViolations().size(), nativ.getViolations().size());
        assertEquals(legacy.getViolations().get(0).getRowNumber(),
                nativ.getViolations().get(0).getRowNumber());
        assertEquals(RuleExecutionStatus.EXECUTED, nativ.getStatus(),
                "the absent-column fold is a decided verdict, not a skip");

        // With the column present the fold stays UNKNOWN and the row path evaluates per row —
        // parity held (rows 0 and 2 violate the negated equality).
        IDataTable present = MockTable.of().name("AE").col("AEXX", "N", "Y", "X").build();
        assertEquals(rows(run(rule, present, _ -> null)), rows(run(rule, present, _ -> null)));
        assertEquals(2, run(rule, present, _ -> null).getViolations().size());
    }


    @Test
    void theFallThroughShapeStillReachesAVerdict() throws Exception
    {
        // The Step-1 tripwire. It used to assert that no run recorded a LEGACY backend, which
        // could not fail: the recorder's enum had a single constant and allMatch is true on an
        // empty map. What it was reaching for — the fall-through shape is evaluated here, not
        // abandoned — is asserted directly.
        Rule s2 = loadRule(S2_RULE);
        IDataTable collapsed = MockTable.of().name("AE").col("USUBJID", "S1").col("VISITNUM", "9")
                .build();
        RuleExecutionResult r = run(s2, collapsed, SV_RESOLVER);
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(),
                "the fall-through shape must reach a verdict: " + r.getStatusMessage());
    }

}
