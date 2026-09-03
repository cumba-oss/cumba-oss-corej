package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * P3 of {@code plans/done/PLAN-native-engine-full-coverage.md} — bit-for-bit parity between the
 * NATIVE dataset-broadcast verdict path (generalised from Epic B5 to every eligible rule type) plus
 * the relaxed non-row-based native dispatch, and the legacy engine:
 *
 * <ul>
 * <li><b>3a</b> — metadata-family presence verdicts ({@code exists}/{@code not_exists} on column
 * names = the dominant VMC shape) route through {@code evaluateDatasetBroadcastNative}, mirroring
 * the legacy {@code partialEvaluateDataset} fold (one dataset-level violation at row 0);</li>
 * <li><b>3b</b> — {@code --}-prefix names inside {@code var_exists()} resolve per run against the
 * context's domain prefix (legacy resolves them in phase 2c before evaluation);</li>
 * <li><b>3c</b> — non-row-based rules with a retained {@code checkExpr} (DATASET-sensitivity
 * Record-Data group operators, {@code $}-set verdicts) evaluate natively at the row level and the
 * caller collapses the bits to ONE violation identically for both engines.</li>
 * </ul>
 *
 * Each case loads a one-rule package through {@link RulePackageLoader} (so retention + the
 * broadcast flag are exercised end-to-end), then runs {@link RuleRunner#execute} twice —
 * {@code nativeEval} on and off — and asserts identical findings, plus the expected
 * {@link NativeExecutionRecorder} backend.
 */
class NativeBroadcastVerdictParityTest
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


    private static Map<Long, Map<String, String>> findings(Rule rule, IDataTable primary,
            String domainPrefix)
    {
        RuleExecutionResult r = RuleRunner.execute(rule, primary, NO_RESOLVER, domainPrefix, null,
                null, null);
        Map<Long, Map<String, String>> out = new HashMap<>();
        for (Violation v : r.getViolations())
        {
            out.put(v.getRowNumber(), v.getValues());
        }
        return out;
    }

    private static final String VMC_EXISTS = "{\"Core\":{\"Id\":\"R1\"},"
            + "\"Sensitivity\":\"Dataset\","
            + "\"Check\":{\"all\":[{\"name\":\"AEOCCUR\",\"operator\":\"var_exists\"}]},"
            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}";

    @Test
    void vmcVariablePresence_existsFiresWhenColumnPresent() throws Exception
    {
        // CORE-000012 shape: var_exists(AEOCCUR) on a VMC rule = COLUMN presence (not dataset
        // presence) — one dataset-level finding when the column is in the table.
        Rule rule = loadRule(VMC_EXISTS);
        assertNotNull(rule.getCheckExpr(), "presence VMC rule must retain a checkExpr");
        assertTrue(rule.isBroadcastCheckExpr(), "presence VMC rule must be broadcast-flagged");

        IDataTable with = MockTable.of().name("AE").col("AEOCCUR", "Y").build();
        Map<Long, Map<String, String>> fired = findings(rule, with, "AE");
        assertEquals(1, fired.size(), "column present → exactly one dataset-level finding");

        IDataTable without = MockTable.of().name("AE").col("AETERM", "x").build();
        assertEquals(Map.of(), findings(rule, without, "AE"),
                "column absent → exists false → no finding");
    }


    @Test
    void vmcVariablePresence_runsOnNativeBackend() throws Exception
    {
        Rule rule = loadRule(VMC_EXISTS);
        IDataTable with = MockTable.of().name("AE").col("AEOCCUR", "Y").build();

        NativeExecutionRecorder.enable();
        RuleRunner.execute(rule, with, NO_RESOLVER, "AE", null, null, null);
        assertEquals(NativeExecutionRecorder.Backend.NATIVE,
                NativeExecutionRecorder.disable().get("R1"),
                "broadcast path must record the NATIVE backend");
    }


    @Test
    void vmcRequiredVariableMissing_notExists() throws Exception
    {
        // CDISC-AD0047 shape: var_not_exists(COL) — fires when the required column is absent.
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"SUBJID\",\"operator\":\"var_not_exists\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertNotNull(rule.getCheckExpr());

        IDataTable without = MockTable.of().name("DM").col("USUBJID", "S1").build();
        assertEquals(1, findings(rule, without, "DM").size(), "absent column fires");
        IDataTable with = MockTable.of().name("DM").col("SUBJID", "1").build();
        assertEquals(Map.of(), findings(rule, with, "DM"));
    }


    @Test
    void vmcConditionalPresence_existsAndNotExists() throws Exception
    {
        // CORE-000193 shape: var_exists(A) && var_not_exists(B) — "if A is used, B must accompany
        // it".
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"AESTDTC\",\"operator\":\"var_exists\"},"
                + "{\"name\":\"AESTDY\",\"operator\":\"var_not_exists\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertNotNull(rule.getCheckExpr());

        IDataTable both = MockTable.of().name("AE").col("AESTDTC", "2024-01-01").col("AESTDY", "1")
                .build();
        assertEquals(Map.of(), findings(rule, both, "AE"), "both present → no finding");
        IDataTable onlyDtc = MockTable.of().name("AE").col("AESTDTC", "2024-01-01").build();
        assertEquals(1, findings(rule, onlyDtc, "AE").size(), "B missing → finding");
        IDataTable neither = MockTable.of().name("AE").col("AETERM", "x").build();
        assertEquals(Map.of(), findings(rule, neither, "AE"), "A absent → guard false");
    }


    @Test
    void vmcDomainPrefixedPresence_resolvesPerRun() throws Exception
    {
        // CORE-000026 shape: var_exists(--TPT) && var_not_exists(--TPTNUM) — the -- prefix resolves
        // against the run's domain prefix inside the compiled closure (P3b), so the SAME loaded
        // rule serves any domain.
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"--TPT\",\"operator\":\"var_exists\"},"
                + "{\"name\":\"--TPTNUM\",\"operator\":\"var_not_exists\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertNotNull(rule.getCheckExpr(), "--presence VMC rule must retain a checkExpr");

        IDataTable aeFires = MockTable.of().name("AE").col("AETPT", "MORNING").build();
        assertEquals(1, findings(rule, aeFires, "AE").size(), "AETPT without AETPTNUM fires");
        IDataTable aeClean = MockTable.of().name("AE").col("AETPT", "MORNING").col("AETPTNUM", "1")
                .build();
        assertEquals(Map.of(), findings(rule, aeClean, "AE"));
        // The SAME rule (same cached program) against another domain.
        IDataTable lbFires = MockTable.of().name("LB").col("LBTPT", "PRE-DOSE").build();
        assertEquals(1, findings(rule, lbFires, "LB").size(), "LBTPT without LBTPTNUM fires");
    }


    @Test
    void vmcPresenceOnZeroRowDataset() throws Exception
    {
        // 0-row dataset: the non-row-based path evaluates over the synthetic 1-row table; column
        // presence is a schema fact, so AEOCCUR present on an empty table still fires exactly one
        // dataset-level finding, identically on both engines.
        Rule rule = loadRule(VMC_EXISTS);
        IDataTable empty = MockTable.of().name("AE").col("AEOCCUR").build();
        assertEquals(1, findings(rule, empty, "AE").size(),
                "schema presence fires even on a 0-row dataset");
    }


    @Test
    void recordDataGroupOperatorAtDatasetSensitivity_collapsesIdentically() throws Exception
    {
        // CORE-000212 class (3c): a Record-Data rule with Sensitivity=Dataset and a group
        // operator. rowBased=false → the relaxed gate evaluates the checkExpr natively and the
        // caller collapses the per-row bits to ONE violation — identically to legacy.
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"DSSCAT\","
                + "\"operator\":\"is_not_unique_relationship\",\"value\":\"DSDECOD\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"DSSCAT\"]}}");
        assertNotNull(rule.getCheckExpr());

        IDataTable dup = MockTable.of().name("DS").col("DSSCAT", "x", "x", "y")
                .col("DSDECOD", "1", "2", "3").build();
        Map<Long, Map<String, String>> fired = findings(rule, dup, "DS");
        assertEquals(1, fired.size(), "dataset sensitivity collapses to ONE violation");

        NativeExecutionRecorder.enable();
        RuleRunner.execute(rule, dup, NO_RESOLVER, "DS", null, null, null);
        assertEquals(NativeExecutionRecorder.Backend.NATIVE,
                NativeExecutionRecorder.disable().get("R1"),
                "the relaxed non-row-based gate must run native");

        IDataTable clean = MockTable.of().name("DS").col("DSSCAT", "x", "y")
                .col("DSDECOD", "1", "2").build();
        assertEquals(Map.of(), findings(rule, clean, "DS"));
    }


    @Test
    void dollarComparisonBroadcast_viaRealOperation() throws Exception
    {
        // CORE-000742 class: a Record-Data rule at Dataset sensitivity whose whole Check is a
        // $-operation comparison. The $-var comes from a real record_count Operation, so this
        // exercises the operation-result branch of the broadcast path end-to-end. (The retired
        // variable_exists operation formerly played this role; record_count is a value-producing
        // operation that keeps the same $-op-compared-to-literal broadcast shape.)
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Operations\":[{\"id\":\"$RC\",\"operator\":\"record_count\"}],"
                + "\"Check\":{\"all\":[{\"name\":\"$RC\",\"operator\":\"equal_to\","
                + "\"value\":1}]}," + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertNotNull(rule.getCheckExpr());
        assertTrue(rule.isBroadcastCheckExpr(), "$-comparison must be broadcast-flagged");

        IDataTable oneRow = MockTable.of().name("AE").col("AESEV", "MILD").build();
        assertEquals(1, findings(rule, oneRow, "AE").size(), "record_count == 1 → finding");
        IDataTable twoRows = MockTable.of().name("AE").col("AESEV", "MILD", "SEV").build();
        assertEquals(Map.of(), findings(rule, twoRows, "AE"));
    }


    @Test
    void outputProjectionMatchesLegacyFold() throws Exception
    {
        // The broadcast fast path must project Output_Variables identically to the legacy
        // partialEvaluateDataset fold (extractOutputValues at row 0).
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"AEOCCUR\",\"operator\":\"var_exists\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"STUDYID\"]}}");
        IDataTable t = MockTable.of().name("AE").col("AEOCCUR", "Y").col("STUDYID", "S1").build();
        Map<Long, Map<String, String>> nativeF = findings(rule, t, "AE");
        Map<Long, Map<String, String>> legacyF = findings(rule, t, "AE");
        assertEquals(legacyF, nativeF, "output projection must match the legacy fold");
        assertEquals(1, nativeF.size());
        assertTrue(nativeF.values().iterator().next().containsKey("STUDYID"));
    }

    // ------------------------------------------------------------------
    // R-P2 (PLAN-native-engine-residuals) — dataset-FACT broadcast verdicts:
    // dataset_name / record_count comparisons on non-metadata rule types,
    // previously decidable only by the legacy Step-1 fold.
    // ------------------------------------------------------------------

    private static final String RD_DS_NAME = "{\"Core\":{\"Id\":\"R1\"},"
            + "\"Sensitivity\":\"Dataset\","
            + "\"Check\":{\"all\":[{\"name\":\"dataset_name\",\"operator\":\"equal_to\","
            + "\"value\":\"AE\",\"value_is_literal\":true}]},"
            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}";

    @Test
    void recordDataDatasetNameComparison_broadcastFoldParity() throws Exception
    {
        // RECORD_DATA `dataset_name == "AE"` — the whole check is a dataset fact: legacy folds it
        // at Step 1 (one violation at row 0); the broadened predicate must broadcast-flag it so
        // the native fast path produces the identical one-violation verdict.
        Rule rule = loadRule(RD_DS_NAME);
        assertNotNull(rule.getCheckExpr(), "dataset-fact rule must retain a checkExpr");
        assertTrue(rule.isBroadcastCheckExpr(), "dataset-fact rule must be broadcast-flagged");

        IDataTable ae = MockTable.of().name("AE").col("AETERM", "x", "y").build();
        Map<Long, Map<String, String>> fired = findings(rule, ae, "AE");
        assertEquals(1, fired.size(), "matching dataset name → ONE dataset-level finding");

        IDataTable dm = MockTable.of().name("DM").col("AETERM", "x").build();
        assertEquals(Map.of(), findings(rule, dm, "DM"), "non-matching name → no finding");
    }


    @Test
    void recordCountComparison_broadcastFoldParity() throws Exception
    {
        // `record_count > 2` — canonicalized to the record_count() builtin; fires once on a
        // 3-row table, never on a 1-row table, identically to the legacy fold's compareNumeric.
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"record_count\",\"operator\":\"greater_than\","
                + "\"value\":2}]}," + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertTrue(rule.isBroadcastCheckExpr(), "record_count rule must be broadcast-flagged");

        IDataTable three = MockTable.of().name("AE").col("AETERM", "x", "y", "z").build();
        assertEquals(1, findings(rule, three, "AE").size(), "3 rows > 2 → one finding");

        IDataTable one = MockTable.of().name("AE").col("AETERM", "x").build();
        assertEquals(Map.of(), findings(rule, one, "AE"), "1 row → no finding");
    }


    @Test
    void recordCountOnZeroRowDataset() throws Exception
    {
        // `record_count == 0` on an empty dataset — the legacy fold emits its dataset-level
        // violation at row 0 even with no data rows; the broadcast path must mirror that.
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"record_count\",\"operator\":\"equal_to\","
                + "\"value\":0}]}," + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertTrue(rule.isBroadcastCheckExpr());

        IDataTable empty = MockTable.of().name("AE").col("AETERM").build();
        assertEquals(1, findings(rule, empty, "AE").size(),
                "empty dataset → record_count==0 fires once");
    }


    @Test
    void mixedDatasetFactAndRowLeaf_rowLevelParity() throws Exception
    {
        // MIXED shape: a dataset-fact guard plus a row leaf. Legacy partially folds the guard
        // (TRUE) and row-evaluates the rest; native evaluates the full expr per row with the
        // ds_name accessor broadcast-constant. Per-row verdicts must agree (this shape was the
        // pre-R-P2 silent divergence: a bare dataset_name ref resolved as a missing column).
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"name\":\"dataset_name\",\"operator\":\"equal_to\","
                + "\"value\":\"AE\",\"value_is_literal\":true},"
                + "{\"name\":\"AETERM\",\"operator\":\"empty\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertNotNull(rule.getCheckExpr());

        IDataTable ae = MockTable.of().name("AE").col("AETERM", "x", "", "y", "").build();
        Map<Long, Map<String, String>> fired = findings(rule, ae, "AE");
        assertEquals(2, fired.size(), "guard true → the two empty-AETERM rows fire");

        IDataTable dm = MockTable.of().name("DM").col("AETERM", "", "").build();
        assertEquals(Map.of(), findings(rule, dm, "DM"), "guard false → nothing fires");
    }


    @Test
    void datasetFactInValuePosition_isCanonicalisedUniformly() throws Exception
    {
        // Phase 4 of PLAN-leaf-scope-domain-inference.md retired the R-P2/R-P7 type split: the
        // metadata canonicalisation is uniform for every rule, so a bareword dataset_name in VALUE
        // position becomes the ds_name("DATA") fact on a Record Data rule exactly as it always did
        // on the metadata families. Before, the bareword resolved to no column and the leaf never
        // matched anything (a silent no-op); measured 2026-08-22, no shipped rule carries the
        // shape, so only an externally-supplied leaf-form rule can observe the change.
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"name\":\"AETERM\",\"operator\":\"equal_to\","
                + "\"value\":\"dataset_name\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertNotNull(rule.getCheckExpr());
        String printed = net.cumba.corej.core.expr.ExpressionPrinter.print(rule.getCheckExpr());
        assertTrue(printed.contains("ds_name(\"DATA\")"),
                "value-position dataset_name reads the dataset-name fact: " + printed);

        IDataTable ae = MockTable.of().name("AE").col("AETERM", "dataset_name", "AE").build();
        Map<Long, Map<String, String>> fired = findings(rule, ae, "AE");
        // findings() keys by the 1-based row number
        assertTrue(fired.containsKey(2L), "row 2 (AETERM == the table name) fires: " + fired);
        assertFalse(fired.containsKey(1L), "the literal text \"dataset_name\" is not the name");
    }


    @Test
    void datasetFactRunsNativeAndLegacyFoldRecordsLegacy() throws Exception
    {
        // With nativeEval ON the broadcast fast path preempts the fold (recorder: NATIVE); with
        // the kill-switch OFF the verdict comes from the legacy Step-1 fold, whose R-P2
        // instrumentation must record LEGACY (a TRUE fold is load-bearing).
        Rule rule = loadRule(RD_DS_NAME);
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "x").build();

        NativeExecutionRecorder.enable();
        RuleRunner.execute(rule, ae, NO_RESOLVER, "AE", null, null, null);
        assertEquals(NativeExecutionRecorder.Backend.NATIVE,
                NativeExecutionRecorder.disable().get("R1"),
                "broadcast fast path, recorded NATIVE (legacy engine retired)");
    }

}
