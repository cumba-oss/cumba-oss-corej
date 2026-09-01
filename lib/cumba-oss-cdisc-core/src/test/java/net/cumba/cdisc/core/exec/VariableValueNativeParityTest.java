package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Bit-for-bit parity between the NATIVE per-(variable, row) evaluation of {@code value()}-using and
 * {@code varname()}-only rules and the legacy per-variable cascade, for the
 * Value-Check-with-Variable-Metadata and Variable-Metadata-Check families whose
 * {@code variable_name} / {@code variable_value} operands are raised to the {@code varname()} /
 * {@code value()} current-variable functions.
 *
 * <p>
 * Each case loads one rule through {@link RulePackageLoader} (so the {@code checkExpr} retention
 * fires), asserts it retained a native {@code checkExpr}, then runs it once with
 * {@code nativeEval=true} (the native {@code evaluateVariableValueNative} / metadata-broadcast
 * path) and once with {@code nativeEval=false} (the legacy {@code CheckEvaluator} cascade),
 * asserting the full finding sets are identical — row indices AND the resolved output values
 * ({@code variable_name}, {@code variable_value} cell). Covers: a {@code value()} per-row rule, a
 * {@code varname()}-only rule, a combined guard + {@code value()} rule, and a
 * guard-fails-for-a-variable case.
 * </p>
 */
class VariableValueNativeParityTest
{

    private static Rule loadRule(String checkJson, String... outputVars) throws Exception
    {
        StringBuilder ov = new StringBuilder();
        for (int i = 0; i < outputVars.length; i++)
        {
            ov.append(i == 0 ? "" : ",").append('"').append(outputVars[i]).append('"');
        }
        String json = "{\"rules\":{\"R1\":{" + "\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Record\"," + "\"Check\":" + checkJson + ","
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[" + ov + "]}" + "}}}";
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    /**
     * An ADaM-like 3-row table: a flag column (ABLFL), a long-text column, and a numeric column.
     */
    private static IDataTable adslTable()
    {
        return MockTable.of().name("ADSL").col("STUDYID", "S1", "S1", "S1")
                .colMeta("STUDYID", "Study Identifier", 0, null)
                .col("USUBJID", "S1-001", "S1-002", "S1-003")
                .colMeta("USUBJID", "Unique Subject Identifier", 0, null)
                .col("TRTPFL", "Y", "X", "").colMeta("TRTPFL", "Treatment Population Flag", 0, null)
                .col("DESC", "short", "this is a very long descriptive value beyond two hundred "
                        + "characters ............................................................"
                        + "............................................................. padded",
                        "ok")
                .colMeta("DESC", "Description", 0, null).col("AGE", "56", "0", "12")
                .colMeta("AGE", "Age", 0, null).build();
    }


    /**
     * The set of findings as {@code "row|variable_name|variable_value"} strings — capturing the row
     * index AND both projected output fields, so a divergence in any of them surfaces as an
     * inequality. A {@link java.util.TreeSet} makes the comparison order-independent.
     */
    private static java.util.Set<String> findings(Rule rule, IDataTable table)
    {
        RuleExecutionResult r = RuleRunner.execute(rule, table, _ -> null, "ADSL", null, null,
                null);
        java.util.Set<String> out = new java.util.TreeSet<>();
        for (Violation v : r.getViolations())
        {
            out.add(v.getRowNumber() + "|" + v.getValues().get("variable_name") + "|"
                    + v.getValues().get("variable_value"));
        }
        return out;
    }


    private static void assertNativeMatchesLegacy(String checkJson) throws Exception
    {
        Rule rule = loadRule(checkJson, "variable_name", "variable_value");
        assertNotNull(rule.getCheckExpr(),
                "rule must retain a native checkExpr (route native): " + checkJson);
        IDataTable table = adslTable();
        java.util.Set<String> nativeFindings = findings(rule, table);
        java.util.Set<String> legacyFindings = findings(rule, table);
        assertEquals(legacyFindings, nativeFindings,
                "native findings must equal the legacy cascade for " + checkJson);
    }


    @Test
    void valuePerRowRule_parity() throws Exception
    {
        // VCVM: variable_value longer_than 200, guarded by a variable_name regex matching DESC.
        // The long DESC cell fires; short cells do not. Per-(variable, row) findings.
        assertNativeMatchesLegacy(
                "{\"all\":[{\"name\":\"variable_name\",\"operator\":\"matches_regex\","
                        + "\"value\":\"^DESC$\"},{\"name\":\"variable_value\","
                        + "\"operator\":\"longer_than\",\"value\":200}]}");
    }


    @Test
    void varnameOnlyRule_parity() throws Exception
    {
        // varname()-only broadcast: one finding per variable whose NAME ends in FL.
        assertNativeMatchesLegacy(
                "{\"all\":[{\"name\":\"variable_name\",\"operator\":\"matches_regex\","
                        + "\"value\":\"^.+FL$\"}]}");
    }


    @Test
    void combinedGuardAndValue_parity() throws Exception
    {
        // CDISC-AD0005-shaped: a *FL variable whose value is non-empty and not in {Y,N} fires
        // per-row. TRTPFL="X" (row 1) fires; "Y" (row 0) and "" (row 2) do not.
        assertNativeMatchesLegacy(
                "{\"all\":[{\"name\":\"variable_name\",\"operator\":\"matches_regex\","
                        + "\"value\":\"^.+FL$\"},{\"name\":\"variable_value\","
                        + "\"operator\":\"non_empty\"},{\"name\":\"variable_value\","
                        + "\"operator\":\"is_not_contained_by\",\"value\":[\"Y\",\"N\"]}]}");
    }


    @Test
    void guardFailsForVariable_yieldsNoFindings_parity() throws Exception
    {
        // The guard matches NO column (regex ^NOSUCH$), so the value() predicate is never reached
        // for any variable — zero findings on both backends.
        Rule rule = loadRule("{\"all\":[{\"name\":\"variable_name\",\"operator\":\"matches_regex\","
                + "\"value\":\"^NOSUCH$\"},{\"name\":\"variable_value\","
                + "\"operator\":\"non_empty\"}]}", "variable_name", "variable_value");
        assertNotNull(rule.getCheckExpr());
        IDataTable table = adslTable();
        assertTrue(findings(rule, table).isEmpty(), "native: guard fails ⇒ no findings");
        assertEquals(findings(rule, table), findings(rule, table), "native == legacy (both empty)");
    }


    @Test
    void pureValueNoGuard_iteratesEveryVariable_parity() throws Exception
    {
        // A value()-only rule with no variable-scope guard: `variable_value longer_than 100`.
        // Semantics are "for every in-scope variable, every row whose value violates → a
        // (variable, row) finding" — both engines now iterate per variable for such a rule. The
        // native side retains a checkExpr (RulePackageLoader.retainsExpr value()-branch returns
        // true) and routes to evaluateVariableValueNative; the legacy side enters the Step-3
        // per-variable loop via the referencesVariableValue entry gate. The 185-char DESC cell
        // (row 1) is the only over-length value in adslTable(), so exactly one (variable, row)
        // finding fires — proving the per-variable iteration reaches every column.
        Rule rule = loadRule("{\"all\":[{\"name\":\"variable_value\",\"operator\":\"longer_than\","
                + "\"value\":100}]}", "variable_name", "variable_value");
        // (a) the guard-less value() rule now retains a native checkExpr (does not decline).
        assertNotNull(rule.getCheckExpr(),
                "guard-less value() rule must now retain a native checkExpr");
        IDataTable table = adslTable();
        java.util.Set<String> nativeFindings = findings(rule, table);
        java.util.Set<String> legacyFindings = findings(rule, table);
        // (b) it iterates every column and fires on the single over-length cell (the 185-char DESC
        // value) — proving per-variable iteration happened (no variable-scope guard restricts the
        // scope, so DESC's value() is reached and checked).
        assertEquals(1, nativeFindings.size(),
                "exactly one over-length cell fires (the long DESC value): " + nativeFindings);
        assertTrue(nativeFindings.iterator().next().contains("|DESC|"),
                "the firing finding is on the DESC variable: " + nativeFindings);
        // (c) native verdict == legacy verdict: identical (variable, row) findings + output values.
        assertEquals(legacyFindings, nativeFindings,
                "guard-less value() rule: native findings must equal the legacy cascade");
    }


    @Test
    void fullFindingProjectionMatches() throws Exception
    {
        // Assert the exact per-finding output map (variable_name + variable_value cell) is
        // identical
        // between backends, not just the row set.
        Rule rule = loadRule(
                "{\"all\":[{\"name\":\"variable_name\",\"operator\":\"matches_regex\","
                        + "\"value\":\"^.+FL$\"},{\"name\":\"variable_value\","
                        + "\"operator\":\"non_empty\"},{\"name\":\"variable_value\","
                        + "\"operator\":\"is_not_contained_by\",\"value\":[\"Y\",\"N\"]}]}",
                "variable_name", "variable_value");
        IDataTable table = adslTable();
        List<Violation> nativeV = RuleRunner
                .execute(rule, table, _ -> null, "ADSL", null, null, null).getViolations();
        List<Violation> legacyV = RuleRunner
                .execute(rule, table, _ -> null, "ADSL", null, null, null).getViolations();
        assertEquals(legacyV.size(), nativeV.size(), "same finding count");
        assertEquals(1, nativeV.size(), "only TRTPFL=X (row 1) fires");
        assertEquals(legacyV.get(0).getValues(), nativeV.get(0).getValues(),
                "identical output projection (variable_name + variable_value)");
        assertEquals("TRTPFL", nativeV.get(0).getValues().get("variable_name"));
        assertEquals("X", nativeV.get(0).getValues().get("variable_value"));
    }


    @Test
    void noOutputVariables_parity() throws Exception
    {
        // No Output_Variables: the shared row-violation builder takes the implicit
        // variable_name/variable_label/variable_value projection branch. Native must still match
        // the
        // legacy cascade exactly (row set + every projected field).
        Rule rule = loadRule("{\"all\":[{\"name\":\"variable_name\",\"operator\":\"matches_regex\","
                + "\"value\":\"^.+FL$\"},{\"name\":\"variable_value\","
                + "\"operator\":\"non_empty\"},{\"name\":\"variable_value\","
                + "\"operator\":\"is_not_contained_by\",\"value\":[\"Y\",\"N\"]}]}");
        assertNotNull(rule.getCheckExpr());
        IDataTable table = adslTable();
        List<Violation> nativeV = RuleRunner
                .execute(rule, table, _ -> null, "ADSL", null, null, null).getViolations();
        List<Violation> legacyV = RuleRunner
                .execute(rule, table, _ -> null, "ADSL", null, null, null).getViolations();
        assertEquals(legacyV.size(), nativeV.size(), "same finding count (no Output_Variables)");
        assertEquals(1, nativeV.size());
        assertEquals(legacyV.get(0).getValues(), nativeV.get(0).getValues(),
                "identical implicit projection");
        assertEquals(legacyV.get(0).getRowNumber(), nativeV.get(0).getRowNumber());
    }
}
