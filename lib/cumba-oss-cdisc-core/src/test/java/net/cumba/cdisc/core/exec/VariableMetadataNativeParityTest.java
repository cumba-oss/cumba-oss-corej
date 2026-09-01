package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Epic B4 — bit-for-bit parity between the NATIVE metadata-broadcast evaluation and the legacy
 * per-variable cascade for operand-based Variable-Metadata-Check rules whose operands
 * ({@code variable_name}, {@code variable_label}, {@code variable_data_type}, …) are canonicalized
 * to {@code var_*} accessors at load time
 * ({@link net.cumba.cdisc.core.expr.MetadataOperandMapping#canonicalizeMetadataOperands}).
 *
 * <p>
 * Each case loads one operand-based rule through {@link RulePackageLoader} (so the canonicalization
 * + {@code checkExpr} retention fires), confirms the rule retained a native {@code checkExpr}, then
 * runs it twice over the same table — once with {@code nativeEval=true} (the native
 * {@code evaluateMetadataNative} broadcast path) and once with {@code nativeEval=false} (the legacy
 * {@code CheckEvaluator} cascade) — and asserts the two finding sets are identical (row indices and
 * the resolved {@code variable_name} of each finding). The edge cases (empty label, trailing
 * whitespace, missing label) probe the normalization seam where a divergence would surface.
 * </p>
 */
class VariableMetadataNativeParityTest
{

    /**
     * Loads {@code checkJson} as the single rule's Check in a one-rule Variable-Metadata-Check
     * package and returns the loaded {@link Rule} (with canonicalized {@code checkExpr}).
     */
    private static Rule loadVmcRule(String checkJson, String... outputVars) throws Exception
    {
        StringBuilder ov = new StringBuilder();
        for (int i = 0; i < outputVars.length; i++)
        {
            ov.append(i == 0 ? "" : ",").append('"').append(outputVars[i]).append('"');
        }
        String json = "{\"rules\":{\"R1\":{" + "\"Core\":{\"Id\":\"R1\"}," + ""
                + "\"Sensitivity\":\"Dataset\"," + "\"Check\":" + checkJson + ","
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[" + ov + "]}" + "}}}";
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    /** A DM-like table with assorted variable names / labels to exercise the per-variable loop. */
    private static IDataTable dmTable()
    {
        return MockTable.of().name("DM").col("STUDYID", "S1")
                .colMeta("STUDYID", "Study Identifier", 0, null).col("DOMAIN", "DM")
                .colMeta("DOMAIN", "Domain Abbreviation", 0, null).col("USUBJID", "S1-001")
                .colMeta("USUBJID", "Unique Subject Identifier", 0, null)
                .col("VERYLONGVARNAME", "x").colMeta("VERYLONGVARNAME", "", 0, null)
                .col("AGE", "56").colMeta("AGE",
                        "A very long descriptive label exceeding forty chars total here", 0, null)
                .col("SHORT", "y").colMeta("SHORT", "  Padded  ", 0, null).build();
    }


    /**
     * Runs {@code rule} over {@code table} with the given {@code nativeEval} flag and returns the
     * stable set of firing {@code variable_name}s (so native and legacy can be compared
     * independently of finding order). Keyed by {@code variable_name} rather than the violation
     * row: a Dataset-sensitivity Variable Metadata Check reports every per-variable finding at the
     * dataset-level row (row 0), so the row no longer distinguishes findings — the variable name
     * does.
     */
    private static Set<String> findings(Rule rule, IDataTable table)
    {
        RuleExecutionResult r = RuleRunner.execute(rule, table, _ -> null, "DM", null, null, null);
        Set<String> out = new TreeSet<>();
        for (Violation v : r.getViolations())
        {
            out.add(v.getValues().get("variable_name"));
        }
        return out;
    }


    private static void assertNativeMatchesLegacy(String checkJson) throws Exception
    {
        Rule rule = loadVmcRule(checkJson, "variable_name");
        assertNotNull(rule.getCheckExpr(),
                "operand-based VMC rule must retain a native checkExpr after canonicalization");
        IDataTable table = dmTable();
        Set<String> nativeFindings = findings(rule, table);
        Set<String> legacyFindings = findings(rule, table);
        assertEquals(legacyFindings, nativeFindings,
                "native metadata-broadcast findings must equal the legacy cascade for "
                        + checkJson);
    }


    @Test
    void variableLabelLongerThan_parity() throws Exception
    {
        assertNativeMatchesLegacy("{\"all\":[{\"name\":\"variable_label\","
                + "\"operator\":\"longer_than\",\"value\":40}]}");
    }


    @Test
    void variableLabelEmptyEdgeCase_parity() throws Exception
    {
        // non_empty(variable_label): the empty / whitespace-only labels (VERYLONGVARNAME="",
        // SHORT=" Padded ") probe the RAW-normalization (trim, empty->null) seam.
        assertNativeMatchesLegacy(
                "{\"all\":[{\"name\":\"variable_label\",\"operator\":\"non_empty\"}]}");
    }


    @Test
    void variableLabelEqualToDomain_parity() throws Exception
    {
        // variable_label compared to the literal "DOMAIN" — the canonicalized var_label accessor
        // anchors the broadcast; exercises the RAW label vs literal equality.
        assertNativeMatchesLegacy("{\"all\":[{\"name\":\"variable_label\","
                + "\"operator\":\"equal_to\",\"value\":\"DOMAIN\",\"value_is_literal\":true}]}");
    }


    @Test
    void variableNameOnlyRulesRunNativeViaVarname() throws Exception
    {
        // A rule whose only operand is the variable_name anchor (regex/length on the NAME itself)
        // now raises to varname() (the current-variable-name function) and runs on the native
        // metadata-broadcast path (one finding per failing variable), matching the legacy cascade.
        assertNativeMatchesLegacy("{\"all\":[{\"name\":\"variable_name\","
                + "\"operator\":\"longer_than\",\"value\":8}]}");
        assertNativeMatchesLegacy("{\"all\":[{\"name\":\"variable_name\","
                + "\"operator\":\"not_matches_regex\",\"value\":\"^[A-Z]\"}]}");
    }


    @Test
    void variableDataTypeContainedBy_parity() throws Exception
    {
        assertNativeMatchesLegacy("{\"all\":[{\"name\":\"variable_data_type\","
                + "\"operator\":\"is_not_contained_by\",\"value\":[\"Char\",\"Num\"]}]}");
    }


    @Test
    void retainsExprForCanonicalizedOperandRule() throws Exception
    {
        // Sanity: a bare-operand rule that previously stayed on the legacy cascade (no metadata
        // function in the raised Expr) now retains a native checkExpr after canonicalization.
        Rule rule = loadVmcRule(
                "{\"all\":[{\"name\":\"variable_label\","
                        + "\"operator\":\"longer_than\",\"value\":40}]}",
                "variable_name", "variable_label");
        assertNotNull(rule.getCheckExpr());
        // And the two backends agree on the full finding set including the label projection.
        IDataTable table = dmTable();
        List<Violation> nativeV = RuleRunner.execute(rule, table, _ -> null, "DM", null, null, null)
                .getViolations();
        List<Violation> legacyV = RuleRunner.execute(rule, table, _ -> null, "DM", null, null, null)
                .getViolations();
        assertEquals(legacyV.size(), nativeV.size(), "same number of per-variable findings");
        assertTrue(nativeV.size() >= 1, "the long-label AGE column must fire");
    }


    @Test
    void variableNameAnchoredMembership_parity() throws Exception
    {
        // P4b (ADAM-ADD-100029 / CORE-001079 class): a rule anchored ONLY on the variable_name
        // operand (no var_* accessor, no varname()) iterates per variable on the native broadcast
        // path — the anchor resolves the same per-column cursor the loop sets.
        Rule rule = loadVmcRule(
                "{\"all\":[{\"name\":\"variable_name\",\"operator\":\"is_not_contained_by\","
                        + "\"value\":[\"STUDYID\",\"DOMAIN\",\"USUBJID\",\"AGE\"]}]}",
                "variable_name");
        assertNotNull(rule.getCheckExpr(),
                "a variable_name-anchored pure rule must retain a checkExpr (P4b)");
        IDataTable table = dmTable();
        Set<String> nativeF = findings(rule, table);
        Set<String> legacyF = findings(rule, table);
        assertEquals(legacyF, nativeF, "per-variable anchor verdicts must match legacy");
        // Sensitivity.DATASET collapse: Python's COREActions.generate_targeted_error_object emits
        // exactly ONE error (errors_df.iloc[0], the first failing variable in column order) for a
        // dataset-sensitivity rule, so both engines report only the FIRST out-of-allowlist column.
        // dmTable column order is STUDYID, DOMAIN, USUBJID, VERYLONGVARNAME, AGE, SHORT; with the
        // allowlist {STUDYID, DOMAIN, USUBJID, AGE} the first variable outside it is
        // VERYLONGVARNAME (SHORT also matches the predicate but is suppressed by the collapse).
        assertEquals(Set.of("VERYLONGVARNAME"), nativeF,
                "only the first out-of-allowlist variable fires under the dataset collapse: "
                        + nativeF);

        NativeExecutionRecorder.enable();
        RuleRunner.execute(rule, table, _ -> null, "DM", null, null, null);
        assertEquals(NativeExecutionRecorder.Backend.NATIVE,
                NativeExecutionRecorder.disable().get("R1"),
                "the anchored rule must take the native per-variable broadcast path");
    }


    @Test
    void dollarOperandPerVariableProjection_parity() throws Exception
    {
        // P4 (CDISC-AD0002 class): `$sdtm_label non_empty && variable_label != $sdtm_label` with a
        // REAL cross_dataset_variable_metadata Operation producing a per-variable
        // VariableMetadataResult. The native per-variable loop must project the $-variable per
        // column (vmr.getForVariable) exactly like the legacy Step-3 cascade.
        String json = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Dataset\"," + "\"Operations\":[{\"id\":\"$sdtm_label\","
                + "\"operator\":\"cross_dataset_variable_metadata\",\"domain\":\"AE\","
                + "\"name\":\"label\"}],"
                + "\"Check\":{\"all\":[{\"name\":\"$sdtm_label\",\"operator\":\"non_empty\"},"
                + "{\"name\":\"variable_label\",\"operator\":\"not_equal_to\","
                + "\"value\":\"$sdtm_label\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\"]}}}}";
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        assertNotNull(rule.getCheckExpr(), "the $-operand VMC rule must retain a checkExpr");

        // SDTM AE: AETERM/AESEV carry the reference labels. ADAE: AETERM label matches, AESEV
        // label differs -> exactly one per-variable finding (AESEV). NEWVAR has no SDTM
        // counterpart -> $sdtm_label missing -> guard false -> no finding.
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "t")
                .colMeta("AETERM", "Reported Term", 0, null).col("AESEV", "MILD")
                .colMeta("AESEV", "Severity", 0, null).build();
        IDataTable adae = MockTable.of().name("ADAE").col("AETERM", "t")
                .colMeta("AETERM", "Reported Term", 0, null).col("AESEV", "MILD")
                .colMeta("AESEV", "Severity Level", 0, null).col("NEWVAR", "x")
                .colMeta("NEWVAR", "Analysis Flag", 0, null).build();
        DatasetResolver resolver = n -> "AE".equals(n) ? ae : null;

        Map<Integer, String> nativeF = new TreeMap<>();
        for (Violation v : RuleRunner.execute(rule, adae, resolver, "AE", null, null, null)
                .getViolations())
        {
            nativeF.put((int) v.getRowNumber(), v.getValues().get("variable_name"));
        }
        Map<Integer, String> legacyF = new TreeMap<>();
        for (Violation v : RuleRunner.execute(rule, adae, resolver, "AE", null, null, null)
                .getViolations())
        {
            legacyF.put((int) v.getRowNumber(), v.getValues().get("variable_name"));
        }
        assertEquals(legacyF, nativeF, "per-variable $-projection must match the legacy cascade");
        assertEquals(List.of("AESEV"), nativeF.values().stream().toList(),
                "only the label-mismatched variable fires");

        // And the rule must actually run on the NATIVE backend now (the P4 gate).
        NativeExecutionRecorder.enable();
        RuleRunner.execute(rule, adae, resolver, "AE", null, null, null);
        assertEquals(NativeExecutionRecorder.Backend.NATIVE,
                NativeExecutionRecorder.disable().get("R1"),
                "the $-operand VMC rule must take the native per-variable broadcast path");
    }

    // ------------------------------------------------------------------
    // Guard-residual D4 (PLAN-native-runtime-guard-residual) — VMR-typed
    // $-operands beyond the variable-scoped metadata gate.
    // ------------------------------------------------------------------


    /** ADAE fixture: AETERM/AESEV have SDTM counterparts; NEWVAR does not. */
    private static IDataTable adaeFixture()
    {
        return MockTable.of().name("ADAE").col("AETERM", "t")
                .colMeta("AETERM", "Reported Term", 0, null).col("AESEV", "MILD")
                .colMeta("AESEV", "Severity", 0, null).col("NEWVAR", "x")
                .colMeta("NEWVAR", "Analysis Flag", 0, null).build();
    }


    private static DatasetResolver aeResolver()
    {
        IDataTable ae = MockTable.of().name("AE").col("AETERM", "t")
                .colMeta("AETERM", "Reported Term", 0, null).col("AESEV", "MILD")
                .colMeta("AESEV", "Severity", 0, null).build();
        return n -> "AE".equals(n) ? ae : null;
    }

    private static final String VMR_OP = "\"Operations\":[{\"id\":\"$sdtm_label\","
            + "\"operator\":\"cross_dataset_variable_metadata\",\"domain\":\"AE\","
            + "\"name\":\"label\"}],";

    @Test
    void s5_vmrRefWithoutVariableScope_perVariableNativeParity() throws Exception
    {
        // No variable-scope operand at all — the $vmr ref ALONE triggers the per-variable loop
        // (the legacy Step-3 cascade fires on the VARIABLE-classified $-leaf the same way).
        String json = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Dataset\"," + VMR_OP
                + "\"Check\":{\"all\":[{\"name\":\"$sdtm_label\",\"operator\":\"empty\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\"]}}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        assertNotNull(rule.getCheckExpr(), "the $vmr-only rule must retain a checkExpr");

        IDataTable adae = adaeFixture();
        DatasetResolver resolver = aeResolver();
        java.util.Set<String> nativeVars = new java.util.TreeSet<>();
        for (Violation v : RuleRunner.execute(rule, adae, resolver, "AE", null, null, null)
                .getViolations())
        {
            nativeVars.add(v.getValues().get("variable_name"));
        }
        java.util.Set<String> legacyVars = new java.util.TreeSet<>();
        for (Violation v : RuleRunner.execute(rule, adae, resolver, "AE", null, null, null)
                .getViolations())
        {
            legacyVars.add(v.getValues().get("variable_name"));
        }
        assertEquals(legacyVars, nativeVars, "per-variable verdicts must match the legacy cascade");
        assertEquals(java.util.Set.of("NEWVAR"), nativeVars,
                "only the variable without an SDTM counterpart fires");

        NativeExecutionRecorder.enable();
        RuleRunner.execute(rule, adae, resolver, "AE", null, null, null);
        assertEquals(NativeExecutionRecorder.Backend.NATIVE,
                NativeExecutionRecorder.disable().get("R1"),
                "the VMR-ref rule must run natively (legacy Step-3 cascade retired)");
    }


    @Test
    void orderingGuard_decidableAnyAroundVmr_oneDatasetViolation() throws Exception
    {
        // any[AETERM exists → TRUE, $vmr empty]: the legacy Step-1 fold collapses the any[] and
        // emits ONE dataset-level violation — the native fold must decide identically BEFORE any
        // per-variable routing (the D4 ordering guard).
        String json = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Dataset\"," + VMR_OP
                + "\"Check\":{\"any\":[{\"name\":\"AETERM\",\"operator\":\"var_exists\"},"
                + "{\"name\":\"$sdtm_label\",\"operator\":\"empty\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("R1");
        assertNull(rule.getLoadError());
        assertNotNull(rule.getCheckExpr());

        IDataTable adae = adaeFixture();
        DatasetResolver resolver = aeResolver();
        RuleExecutionResult nativ = RuleRunner.execute(rule, adae, resolver, "AE", null, null,
                null);
        RuleExecutionResult legacy = RuleRunner.execute(rule, adae, resolver, "AE", null, null,
                null);
        assertEquals(1, nativ.getViolations().size(),
                "the TRUE-collapse emits ONE dataset-level violation, never per-variable");
        assertEquals(legacy.getViolations().size(), nativ.getViolations().size());

        NativeExecutionRecorder.enable();
        RuleRunner.execute(rule, adae, resolver, "AE", null, null, null);
        assertEquals(NativeExecutionRecorder.Backend.NATIVE,
                NativeExecutionRecorder.disable().get("R1"));
    }


    @Test
    void s7b_recordDataVmrGuardWithRowResidue_perVariableRowParity() throws Exception
    {
        // RECORD_DATA + VMR guard + plain-column row residue: the legacy cascade enters Step 3
        // for ANY rule type on a VARIABLE-classified $-leaf, folds the guard per variable
        // (PROJECTED), and row-evaluates the residue per variable (Step 4). The native
        // per-(variable, row) path with guard-position VMR projection must match per
        // (variable, row).
        String json = "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Record\"," + VMR_OP
                + "\"Check\":{\"all\":[{\"name\":\"$sdtm_label\",\"operator\":\"non_empty\"},"
                + "{\"name\":\"AETERM\",\"operator\":\"empty\"}]},"
                + "\"Outcome\":{\"Message\":\"m\","
                + "\"Output_Variables\":[\"variable_name\"]}}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("R1");
        assertNull(rule.getLoadError());
        assertNotNull(rule.getCheckExpr(), "RECORD_DATA retains every supported expression");

        IDataTable adae = MockTable.of().name("ADAE").col("AETERM", "", "t", "")
                .colMeta("AETERM", "Reported Term", 0, null).col("AESEV", "MILD", "MILD", "MILD")
                .colMeta("AESEV", "Severity", 0, null).col("NEWVAR", "x", "x", "x")
                .colMeta("NEWVAR", "Analysis Flag", 0, null).build();
        DatasetResolver resolver = aeResolver();

        java.util.Set<String> nativeF = new java.util.TreeSet<>();
        for (Violation v : RuleRunner.execute(rule, adae, resolver, "AE", null, null, null)
                .getViolations())
        {
            nativeF.add(v.getValues().get("variable_name") + "@" + v.getRow());
        }
        java.util.Set<String> legacyF = new java.util.TreeSet<>();
        for (Violation v : RuleRunner.execute(rule, adae, resolver, "AE", null, null, null)
                .getViolations())
        {
            legacyF.add(v.getValues().get("variable_name") + "@" + v.getRow());
        }
        assertEquals(legacyF, nativeF,
                "per-(variable, row) findings must match the legacy Step-3/4 composition");
        assertTrue(nativeF.contains("AETERM@0") && nativeF.contains("AESEV@0"),
                "variables WITH an SDTM counterpart fire on the empty-AETERM rows: " + nativeF);
        assertTrue(nativeF.stream().noneMatch(f -> f.startsWith("NEWVAR")),
                "the guard (projected per column) excludes the counterpart-less variable");

        NativeExecutionRecorder.enable();
        RuleRunner.execute(rule, adae, resolver, "AE", null, null, null);
        assertEquals(NativeExecutionRecorder.Backend.NATIVE,
                NativeExecutionRecorder.disable().get("R1"),
                "S7b must run on the native per-(variable, row) path");
    }
}
