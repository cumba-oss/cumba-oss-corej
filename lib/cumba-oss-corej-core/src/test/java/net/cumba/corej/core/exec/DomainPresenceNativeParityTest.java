package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Epic B5 — bit-for-bit parity between the NATIVE dataset-level evaluation and the legacy
 * {@code CheckConditionOptimizer.partialEvaluateDataset} fold for {@code Domain Presence Check}
 * rules. A Domain-Presence Check is dataset-existence logic
 * ({@code ds_exists}/{@code ds_not_exists} on a dataset name, optionally guarded by
 * {@code $}-variable comparisons), and its verdict is dataset-level — one finding for the dataset,
 * not per row.
 *
 * <p>
 * Each case loads a one-rule package through {@link RulePackageLoader} (so the {@code checkExpr} is
 * retained — see {@code RulePackageLoader.isDatasetPresenceExpr}), confirms the rule retained a
 * native {@code checkExpr}, then runs it twice over the same submission — once with
 * {@code nativeEval=true} (the native {@code evaluateDomainPresenceNative} broadcast path) and once
 * with {@code nativeEval=false} (the legacy partial-evaluate fold) — and asserts the two finding
 * sets are identical for BOTH the present and the absent dataset cases. Coverage spans a bare
 * {@code ds_not_exists}, a bare {@code ds_exists}, a two-clause
 * {@code ds_exists AND ds_not_exists}, and a {@code var_exists} column-presence clause guarding a
 * {@code ds_exists} dataset clause (the migrated CORE-000291 shape that replaced the retired
 * {@code variable_exists} operation).
 * </p>
 */
class DomainPresenceNativeParityTest
{

    /** A resolver over a fixed name → table inventory; returns {@code null} for absent names. */
    private static DatasetResolver resolverOf(Map<String, IDataTable> inventory)
    {
        return inventory::get;
    }


    /** A minimal one-row dataset with the given name (and optional columns). */
    private static IDataTable ds(String name, String... cols)
    {
        MockTable mt = MockTable.of().name(name);
        if (cols.length == 0)
        {
            mt.col("X", "1");
        }
        for (String c : cols)
        {
            mt.col(c, "1");
        }
        return mt.build();
    }


    /** Loads {@code ruleJson} (a single rule body) as the sole rule of a package. */
    private static Rule loadRule(String ruleBody) throws Exception
    {
        String json = "{\"rules\":{\"R1\":" + ruleBody + "}}";
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    /**
     * Runs {@code rule} over {@code primary} with the given resolver and {@code nativeEval} flag,
     * returning the resulting violation values keyed by row number (Domain-Presence verdicts are
     * dataset-level, so at most one finding at row 0).
     */
    private static Map<Long, Map<String, String>> findings(Rule rule, IDataTable primary,
            DatasetResolver resolver)
    {
        RuleExecutionResult r = RuleRunner.execute(rule, primary, resolver, null, null, null, null);
        Map<Long, Map<String, String>> out = new HashMap<>();
        for (Violation v : r.getViolations())
        {
            out.put(v.getRowNumber(), v.getValues());
        }
        return out;
    }


    private static void assertParity(Rule rule, IDataTable primary, DatasetResolver resolver)
    {
        Map<Long, Map<String, String>> nativeF = findings(rule, primary, resolver);
        Map<Long, Map<String, String>> legacyF = findings(rule, primary, resolver);
        assertEquals(legacyF, nativeF,
                "native dataset-level verdict must equal the legacy partial-evaluate fold");
    }


    @Test
    void bareNotExists_parity() throws Exception
    {
        // CDISC-AD0001: ds_not_exists(ADSL) — fires when ADSL is absent.
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"ADSL\",\"operator\":\"ds_not_exists\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertNotNull(rule.getCheckExpr(), "Domain-Presence rule must retain a native checkExpr");

        IDataTable dm = ds("DM");
        // Present: ADSL exists → not_exists is false → no finding.
        assertParity(rule, dm, resolverOf(Map.of("DM", dm, "ADSL", ds("ADSL"))));
        // Absent: ADSL missing → not_exists is true → one dataset-level finding.
        assertParity(rule, dm, resolverOf(Map.of("DM", dm)));
    }


    @Test
    void bareExists_parity() throws Exception
    {
        // CORE-000043: ds_exists(TP).
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"TP\",\"operator\":\"ds_exists\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertNotNull(rule.getCheckExpr());

        IDataTable ts = ds("TS");
        assertParity(rule, ts, resolverOf(Map.of("TS", ts, "TP", ds("TP"))));
        assertParity(rule, ts, resolverOf(Map.of("TS", ts)));
    }


    @Test
    void existsAndNotExists_parity() throws Exception
    {
        // CORE-000739: ds_exists(TA) and ds_not_exists(EX) — both the present and absent sides
        // exercised.
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"TA\",\"operator\":\"ds_exists\"},"
                + "{\"name\":\"EX\",\"operator\":\"ds_not_exists\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertNotNull(rule.getCheckExpr());

        IDataTable ts = ds("TS");
        // TA present, EX absent → both clauses true → finding.
        assertParity(rule, ts, resolverOf(Map.of("TS", ts, "TA", ds("TA"))));
        // TA present, EX present → ds_not_exists(EX) false → no finding.
        assertParity(rule, ts, resolverOf(Map.of("TS", ts, "TA", ds("TA"), "EX", ds("EX"))));
        // TA absent → ds_exists(TA) false → no finding.
        assertParity(rule, ts, resolverOf(Map.of("TS", ts)));
    }


    @Test
    void varExistsGuarded_parity() throws Exception
    {
        // Migrated CORE-000291 shape: var_exists(EXVAMT) and ds_exists(EC). The var_exists leaf is
        // column presence on the primary table; the generic ds_exists(EC) resolves to dataset
        // presence
        // for a Domain Presence Check — the two-clause native broadcast path end-to-end. (Replaces
        // the retired $EXVAMT_EXISTS == true variable_exists Operation; verdict-identical.)
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[" + "{\"name\":\"EXVAMT\",\"operator\":\"var_exists\"},"
                + "{\"name\":\"EC\",\"operator\":\"ds_exists\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}");
        assertNotNull(rule.getCheckExpr());

        // EXVAMT present on the primary table and EC present → both clauses true → finding.
        IDataTable ecWithCol = ds("EC", "EXVAMT");
        assertParity(rule, ecWithCol, resolverOf(Map.of("EC", ecWithCol)));
        // EXVAMT absent → var_exists(EXVAMT) is false → no finding.
        IDataTable ecNoCol = ds("EC");
        assertParity(rule, ecNoCol, resolverOf(Map.of("EC", ecNoCol)));
        // EXVAMT present but EC dataset absent from inventory → ds_exists(EC) false → no finding.
        assertParity(rule, ecWithCol, resolverOf(Map.of("XX", ds("XX"))));
    }


    @Test
    void outputProjection_parity() throws Exception
    {
        // With an explicit Output_Variable, both backends must project the same dataset-level value
        // map onto the single row-0 finding.
        Rule rule = loadRule("{\"Core\":{\"Id\":\"R1\"}," + "\"Sensitivity\":\"Dataset\","
                + "\"Check\":{\"all\":[{\"name\":\"ADSL\",\"operator\":\"ds_not_exists\"}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"STUDYID\"]}}");
        assertNotNull(rule.getCheckExpr());

        IDataTable dm = MockTable.of().name("DM").col("STUDYID", "S1").build();
        Map<Long, Map<String, String>> nativeF = findings(rule, dm, resolverOf(Map.of("DM", dm)));
        Map<Long, Map<String, String>> legacyF = findings(rule, dm, resolverOf(Map.of("DM", dm)));
        assertEquals(legacyF, nativeF, "output projection must match");
        // Dataset-level verdict → exactly one finding built from the row-0 Violation, which
        // Violation.getRowNumber() reports 1-based as row 1.
        assertEquals(List.of(1L), nativeF.keySet().stream().sorted().toList(),
                "exactly one dataset-level finding");
        assertEquals("S1", nativeF.get(1L).get("STUDYID"), "STUDYID projected onto the finding");
    }
}
