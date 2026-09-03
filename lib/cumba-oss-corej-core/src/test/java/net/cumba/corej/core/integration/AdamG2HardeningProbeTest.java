package net.cumba.corej.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.exec.StubMetadataProvider;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Hardening probe for the eight ADaM G2 cross-standard value-compare rules (CDISC-AD0204..0210 and
 * CDISC-AD0367). Each compares an ADSL variable against its {@code DM.<var>} counterpart after a
 * {@code Match_Datasets: [{DM, USUBJID}]} join. Previously these were un-guarded value compares:
 * with DM <em>absent</em>, {@code DM.<var>} resolved to empty and {@code "42" != ""} fired
 * spuriously (a false positive vs. the Python engine, which self-skips).
 *
 * <p>
 * The fix mirrors CDISC-AD0053's presence guard: a leading
 * {@code not(all($datasets shares_no_elements_with [DM]))} term (with an {@code Operations:
 * $datasets = dataset_names()} block) so the compare only runs when DM is co-loaded. This probe
 * loads the authored {@code rules-src} checks directly (no corpus regeneration) and asserts the new
 * skip-on-DM-absent behaviour on a representative rule (CDISC-AD0204, AGE), plus that all eight
 * remain native-convertible.
 * </p>
 */
class AdamG2HardeningProbeTest
{

    private static final YAMLMapper MAPPER = (YAMLMapper) new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String[] G2_IDS =
    {
            "CDISC-AD0204", "CDISC-AD0205", "CDISC-AD0206", "CDISC-AD0207", "CDISC-AD0208",
            "CDISC-AD0209", "CDISC-AD0210", "CDISC-AD0367"
    };

    private static Rule loadRule(String coreId) throws Exception
    {
        Path file = Path.of("src/test/resources/fixtures/rules/checks/CDISC/" + coreId + ".yaml");
        Rule rule = MAPPER.readValue(Files.readString(file), Rule.class);
        // Form-B operations (PLAN-retire-corpus-transforms phase 8) carry no operator
        // until normalized — the same pass the loader and RuleScaffold run.
        RulePackageLoader.normalizeOperations(rule);
        rule.setCheckExpr(CheckToExpr.toExpr(rule.getCheck()));
        return rule;
    }


    /**
     * A resolver that enumerates its dataset names, required by the {@code dataset_names()}
     * presence guard. When DM is not among the supplied tables the guard is false → the rule
     * self-skips.
     */
    private static DatasetResolver.WithInventory inventoryResolverOf(Map<String, IDataTable> tables)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String domainName)
            {
                return tables.get(domainName);
            }


            @Override
            public java.util.Set<String> availableDatasets()
            {
                return tables.keySet();
            }
        };
    }

    /**
     * Fix #223 — the declare channel. The eight G2 rules are scoped
     * {@code Scope.Data_Structures.Include:[SUBJECT LEVEL ANALYSIS DATASET]}; on the 3-arg
     * {@code execute} the gate resolved that from the dataset merely being <em>named</em>
     * {@code ADSL}, which is tier-3 local-only re-derivation. The 7-arg overload lets the fixture
     * state what the sponsor declared instead.
     */
    private static final MetadataProvider DEFINE = new StubMetadataProvider().declares("ADSL",
            "SUBJECT LEVEL ANALYSIS DATASET");

    private static int violationsOn(Rule rule, IDataTable table, DatasetResolver resolver)
    {
        RuleExecutionResult result = RuleRunner.execute(rule, table, resolver, null, null, null,
                DEFINE);
        return result.getViolationCount();
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0204 (AGE) — representative of the eight guarded value compares.
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0204_ageMismatch_dmCoLoaded_fires() throws Exception
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("AGE", "42").name("ADSL").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S1").col("AGE", "43").name("DM").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("DM", dm);

        Rule rule = loadRule("CDISC-AD0204");
        assertEquals(1, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "ADSL.AGE=42 != DM.AGE=43 with DM co-loaded → fires");
    }


    @Test
    void cdiscAd0204_ageMatch_dmCoLoaded_noFire() throws Exception
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("AGE", "42").name("ADSL").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S1").col("AGE", "42").name("DM").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("DM", dm);

        Rule rule = loadRule("CDISC-AD0204");
        assertEquals(0, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "ADSL.AGE == DM.AGE with DM co-loaded → no fire");
    }


    @Test
    void cdiscAd0204_dmAbsent_guardSkips_noFire() throws Exception
    {
        // THE HARDENING: with DM not supplied, the dataset_names() guard is false → self-skip.
        // Before the guard, DM.AGE resolved to empty and "42" != "" fired spuriously (a false
        // positive vs. the Python engine). This is the behaviour change.
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("AGE", "42").name("ADSL").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl); // no DM

        Rule rule = loadRule("CDISC-AD0204");
        assertEquals(0, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "DM absent from $datasets → dataset_names() guard false → self-skip (no fire)");
    }

    // -----------------------------------------------------------------------
    // All eight rules must remain native-convertible after the transform.
    // -----------------------------------------------------------------------


    @Test
    void allEightConvertToNativeExpression() throws Exception
    {
        for (String coreId : G2_IDS)
        {
            assertNotNull(loadRule(coreId).getCheckExpr(),
                    coreId + " must raise to a native expression (zero-legacy gate)");
        }
    }
}
