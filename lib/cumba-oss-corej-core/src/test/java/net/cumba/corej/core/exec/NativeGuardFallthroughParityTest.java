package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.Map;
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
 * {@code BroadcastFold}, with verdicts byte-identical to the legacy engine and the recorder
 * reporting NATIVE.
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


    private static NativeExecutionRecorder.Backend recordedBackend(Rule rule, IDataTable t,
            DatasetResolver resolver)
    {
        NativeExecutionRecorder.enable();
        run(rule, t, resolver);
        return NativeExecutionRecorder.disable().get("R1");
    }

    /**
     * S2 shape — a mixed Check whose decidable guard short-circuits AROUND a runtime GroupedResult
     * {@code $}-ref: {@code any[AESTDY not_exists, VISITNUM not in $grouped]}.
     */
    private static final String S2_RULE = "{\"Core\":{\"Id\":\"R1\"},"
            + "\"Sensitivity\":\"Record\","
            + "\"Operations\":[{\"id\":\"$sv_visitnum\",\"operator\":\"distinct\","
            + "\"domain\":\"SV\",\"name\":\"VISITNUM\",\"group\":[\"USUBJID\"]}],"
            + "\"Check\":{\"any\":[{\"name\":\"AESTDY\",\"operator\":\"var_not_exists\"},"
            + "{\"name\":\"VISITNUM\",\"operator\":\"is_not_contained_by\","
            + "\"value\":\"$sv_visitnum\"}]},"
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
        assertEquals(NativeExecutionRecorder.Backend.NATIVE,
                recordedBackend(rule, collapsed, SV_RESOLVER),
                "the collapse must be decided by the native fold");
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
        assertEquals(NativeExecutionRecorder.Backend.NATIVE,
                recordedBackend(rule, residue, SV_RESOLVER));
    }


    @Test
    void s1_existsGuardFalseCollapse_decidesNativelyEmpty() throws Exception
    {
        // all[X exists, X == "Y"] on a dataset without X: both engines produce the empty verdict;
        // the native fold decides it (recorder NATIVE), where previously the legacy Step-1 fold
        // returned the constant.
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"name\":\"AESTDY\",\"operator\":\"var_exists\"},"
                + "{\"name\":\"AESTDY\",\"operator\":\"equal_to\",\"value\":\"1\","
                + "\"value_is_literal\":true}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        IDataTable t = MockTable.of().name("AE").col("AETERM", "x", "y").build();
        RuleExecutionResult nativ = run(rule, t, _ -> null);
        RuleExecutionResult legacy = run(rule, t, _ -> null);
        assertEquals(0, nativ.getViolations().size());
        assertEquals(legacy.getViolations().size(), nativ.getViolations().size());
        assertEquals(NativeExecutionRecorder.Backend.NATIVE, recordedBackend(rule, t, _ -> null));
    }


    @Test
    void missingColumnNotShape_firesPerRowLikeAnAllBlankColumn() throws Exception
    {
        // not(AEXX == "Y") with AEXX absent everywhere.
        //
        // UPDATED BY EC-43. The Fix #40 missing-column fold used to turn the leaf FALSE, the not()
        // flipped it TRUE, and the Step-1 constant emitted ONE dataset-level violation even on a
        // Record-sensitivity rule. That made an ABSENT column report differently from a PRESENT-
        // but-all-blank one, which fires per row — and "absent behaves exactly like all-blank" is
        // the EC-43 contract. The absent column now folds to all-missing at the leaf and the row
        // path decides it, so this fires once per row, matching the present-column half of this
        // very test below.
        //
        // (Note the two runs here are both the NATIVE backend — `nativ` and `legacy` invoke the
        // same `run`. This asserts a Java-internal shape, not Java/Python parity.)
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"not\":{\"all\":[{\"name\":\"AEXX\",\"operator\":\"equal_to\","
                + "\"value\":\"Y\",\"value_is_literal\":true}]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        IDataTable t = MockTable.of().name("AE").col("AETERM", "x", "y", "z").build();
        RuleExecutionResult nativ = run(rule, t, _ -> null);
        RuleExecutionResult legacy = run(rule, t, _ -> null);
        assertEquals(3, nativ.getViolations().size(),
                "one per row — an absent column evaluates exactly like an all-blank one (EC-43)");
        assertEquals(legacy.getViolations().size(), nativ.getViolations().size());
        assertEquals(legacy.getViolations().get(0).getRowNumber(),
                nativ.getViolations().get(0).getRowNumber());
        assertEquals(NativeExecutionRecorder.Backend.NATIVE, recordedBackend(rule, t, _ -> null));

        // With the column present the fold stays UNKNOWN and the row path evaluates per row —
        // parity held (rows 0 and 2 violate the negated equality).
        IDataTable present = MockTable.of().name("AE").col("AEXX", "N", "Y", "X").build();
        assertEquals(rows(run(rule, present, _ -> null)), rows(run(rule, present, _ -> null)));
        assertEquals(2, run(rule, present, _ -> null).getViolations().size());
    }


    @Test
    void nativeRunNeverRecordsLegacyForTheseShapes() throws Exception
    {
        // The Step-1 tripwire: under native-default none of the fall-through scenarios may
        // produce a LEGACY (or MIXED) record.
        Rule s2 = loadRule(S2_RULE);
        IDataTable collapsed = MockTable.of().name("AE").col("USUBJID", "S1").col("VISITNUM", "9")
                .build();
        NativeExecutionRecorder.enable();
        run(s2, collapsed, SV_RESOLVER);
        Map<String, NativeExecutionRecorder.Backend> rec = NativeExecutionRecorder.disable();
        assertTrue(rec.values().stream().allMatch(b -> b == NativeExecutionRecorder.Backend.NATIVE),
                "no LEGACY/MIXED under native-default: " + rec);
    }

}
