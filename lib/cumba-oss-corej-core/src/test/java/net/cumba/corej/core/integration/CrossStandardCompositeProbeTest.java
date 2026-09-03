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
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Probe for the four newly-authored cross-standard / composite-key / grouped rules:
 * <ul>
 * <li>{@code PMDA-AD0258} — ADAE↔SDTM AE record-key traceability (join on STUDYID/USUBJID/AESEQ,
 * fire on the un-matched ADAE record). Cross-standard reference join + null-check, guarded by
 * {@code dataset_names()} (mirrors CDISC-AD0053).</li>
 * <li>{@code PMDA-AD0127} — grouped existence: within (USUBJID, PARAMCD), fire when BASE/BASEC is
 * populated but no row in the group carries {@code ABLFL=Y} (mirrors CDISC-AD0128).</li>
 * <li>{@code CDISC-AD0899} — G2 cross-standard VALUE equality (representative pairing ADSL.RFSTDTC
 * vs DM.RFSTDTC, mirrors CDISC-AD0204).</li>
 * </ul>
 *
 * <p>
 * Each non-Draft rule must raise to a native expression ({@link CheckToExpr#toExpr}). Firing is
 * asserted for AD0258 (composite-key absent-parent join) and CDISC-AD0899 (value mismatch),
 * modelling the SDTM reference co-loading pattern from {@code AdamGapRevalidationG2ProbeTest}:
 * reference co-loaded + mismatch/absent-parent → fires; reference absent → the
 * {@code dataset_names()} guard self-skips. {@code CDISC-AD0898} ships {@code Status: Draft}
 * (genuine engine gap — see its ExecutabilityHint) and is intentionally excluded from the
 * conversion/firing assertions.
 * </p>
 *
 * <p>
 * The AD0258 ADAE fixtures carry an {@code AETERM} column no Check reads: the rule is gated by
 * {@code Scope.Data_Structures} + {@code Scope.Subclasses}, which
 * {@link net.cumba.corej.core.metadata.AdamDataStructureDetector} /
 * {@link net.cumba.corej.core.metadata.AdamSubclassDetector} derive from the dataset's columns, so
 * an ADAE table without an OCCDS / ADVERSE EVENT signal column would be SKIPPED rather than
 * evaluated. (CDISC-AD0899 is scoped by {@code Scope.Classes} on the ADSL name and needs none.)
 * </p>
 */
class CrossStandardCompositeProbeTest
{

    private static final YAMLMapper MAPPER = (YAMLMapper) new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Rule loadRule(String relativePath) throws Exception
    {
        Rule rule = MAPPER.readValue(Files.readString(Path.of(relativePath)), Rule.class);
        // Form-B operations (PLAN-retire-corpus-transforms phase 8) carry no operator
        // until normalized — the same pass the loader and RuleScaffold run.
        RulePackageLoader.normalizeOperations(rule);
        rule.setCheckExpr(CheckToExpr.toExpr(rule.getCheck()));
        return rule;
    }


    /**
     * A resolver that also enumerates its dataset names, required by rules that use
     * {@code dataset_names()} (AD0258's and CDISC-AD0899's presence guards).
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


    private static int violationsOn(Rule rule, IDataTable table, DatasetResolver resolver)
    {
        RuleExecutionResult result = RuleRunner.execute(rule, table, resolver);
        return result.getViolationCount();
    }

    // -----------------------------------------------------------------------
    // All three non-Draft rules must raise to a native expression.
    // -----------------------------------------------------------------------


    @Test
    void nonDraftRulesConvertToNativeExpression() throws Exception
    {
        String[] nonDraft =
        {
                "src/test/resources/fixtures/rules/checks/PMDA/PMDA-AD0258.yaml",
                "src/test/resources/fixtures/rules/checks/PMDA/PMDA-AD0127.yaml",
                "src/test/resources/fixtures/rules/checks/CDISC/CDISC-AD0899.yaml"
        };
        for (String path : nonDraft)
        {
            assertNotNull(loadRule(path).getCheckExpr(),
                    path + " must raise to a native expression (zero-legacy gate)");
        }
    }

    // -----------------------------------------------------------------------
    // PMDA-AD0258 — ADAE↔AE composite-key (STUDYID/USUBJID/AESEQ) traceability.
    // -----------------------------------------------------------------------


    @Test
    void ad0258_aeParentAbsent_aeCoLoaded_fires() throws Exception
    {
        // ADAE AESEQ=1, but AE only has AESEQ=2 for the same subject → the composite
        // (STUDYID/USUBJID/AESEQ) join finds no match → AE.AESEQ empty → fires.
        // AETERM is a scope fixture, not a check input — see the class javadoc.
        IDataTable adae = MockTable.of().col("STUDYID", "ST1").col("USUBJID", "S1")
                .col("AESEQ", "1").col("AETERM", "HEADACHE").name("ADAE").build();
        IDataTable ae = MockTable.of().col("STUDYID", "ST1").col("USUBJID", "S1").col("AESEQ", "2")
                .name("AE").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADAE", adae);
        tables.put("AE", ae);

        Rule rule = loadRule("src/test/resources/fixtures/rules/checks/PMDA/PMDA-AD0258.yaml");
        assertEquals(1, violationsOn(rule, adae, inventoryResolverOf(tables)),
                "ADAE AESEQ=1 has no matching AE row (AE only has AESEQ=2) → fires");
    }


    @Test
    void ad0258_aeParentPresent_aeCoLoaded_noFire() throws Exception
    {
        IDataTable adae = MockTable.of().col("STUDYID", "ST1").col("USUBJID", "S1")
                .col("AESEQ", "1").col("AETERM", "HEADACHE").name("ADAE").build();
        IDataTable ae = MockTable.of().col("STUDYID", "ST1").col("USUBJID", "S1").col("AESEQ", "1")
                .name("AE").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADAE", adae);
        tables.put("AE", ae);

        Rule rule = loadRule("src/test/resources/fixtures/rules/checks/PMDA/PMDA-AD0258.yaml");
        assertEquals(0, violationsOn(rule, adae, inventoryResolverOf(tables)),
                "ADAE AESEQ=1 has a matching AE row → no fire");
    }


    @Test
    void ad0258_aeAbsent_guardSelfSkips_noFire() throws Exception
    {
        // AE not co-loaded → dataset_names() guard false → self-skip (no spurious fire),
        // even though ADAE AESEQ=1 would be "absent from AE" if AE were present.
        IDataTable adae = MockTable.of().col("STUDYID", "ST1").col("USUBJID", "S1")
                .col("AESEQ", "1").col("AETERM", "HEADACHE").name("ADAE").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADAE", adae); // no AE

        Rule rule = loadRule("src/test/resources/fixtures/rules/checks/PMDA/PMDA-AD0258.yaml");
        assertEquals(0, violationsOn(rule, adae, inventoryResolverOf(tables)),
                "AE absent from $datasets → dataset_names() guard false → self-skip");
    }

    // -----------------------------------------------------------------------
    // CDISC-AD0899 — G2 cross-standard VALUE equality (ADSL.RFSTDTC vs DM.RFSTDTC).
    // -----------------------------------------------------------------------


    @Test
    void cdiscAd0899_rfstdtcMismatch_dmCoLoaded_fires() throws Exception
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("RFSTDTC", "2024-01-01")
                .name("ADSL").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S1").col("RFSTDTC", "2024-02-01").name("DM")
                .build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("DM", dm);

        Rule rule = loadRule("src/test/resources/fixtures/rules/checks/CDISC/CDISC-AD0899.yaml");
        assertEquals(1, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "ADSL.RFSTDTC != DM.RFSTDTC with DM co-loaded → fires");
    }


    @Test
    void cdiscAd0899_rfstdtcMatch_dmCoLoaded_noFire() throws Exception
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("RFSTDTC", "2024-01-01")
                .name("ADSL").build();
        IDataTable dm = MockTable.of().col("USUBJID", "S1").col("RFSTDTC", "2024-01-01").name("DM")
                .build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("DM", dm);

        Rule rule = loadRule("src/test/resources/fixtures/rules/checks/CDISC/CDISC-AD0899.yaml");
        assertEquals(0, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "ADSL.RFSTDTC == DM.RFSTDTC → no fire");
    }


    @Test
    void cdiscAd0899_dmAbsent_guardSelfSkips_noFire() throws Exception
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("RFSTDTC", "2024-01-01")
                .name("ADSL").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl); // no DM

        Rule rule = loadRule("src/test/resources/fixtures/rules/checks/CDISC/CDISC-AD0899.yaml");
        assertEquals(0, violationsOn(rule, adsl, inventoryResolverOf(tables)),
                "DM absent from $datasets → dataset_names() guard false → self-skip");
    }
}
