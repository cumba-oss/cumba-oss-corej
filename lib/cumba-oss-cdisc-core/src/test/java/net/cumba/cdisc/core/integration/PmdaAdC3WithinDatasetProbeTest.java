package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.exec.StubMetadataProvider;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.gen.WildcardExpander;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Probe for the 28 newly-authored PMDA-AD "C3 cross-study" rules
 * (PMDA-AD0280/0281/0283/0284/0287/0288/0289/0290/0293/0294/0295/0296/0310/0311, each with a
 * {@code B} version-split twin).
 *
 * <p>
 * These rows were originally dispositioned NOT-ENGINE ("inconsistent value ... across studies") and
 * are re-authored under a sanctioned reinterpretation: the "across studies" wording is dropped and
 * treated as a <strong>within-dataset</strong> unique (1-to-1) relationship between a paired
 * character/numeric variable pair (X, Y). Each rule fires when its subject variable X is not
 * functionally determined by its partner Y — i.e. a single Y value maps to more than one X value —
 * via the {@code has_multiple_values_for} group operator (the shape mirrors CDISC-AD0106). The four
 * {@code SMQzzSC}/{@code SMQzzSCN} rules use a paired {@code zz} wildcard resolved by
 * {@link WildcardExpander}.
 * </p>
 *
 * <p>
 * <b>Fix #223 — the structure is DECLARED, not re-derived.</b> AD0280 / AD0293 / AD0310 are gated
 * by {@code Scope.Data_Structures} + {@code Scope.Subclasses}. Every execution here goes through
 * the 7-arg {@code RuleRunner.execute} overload carrying a {@code defineProvider}, so
 * {@link net.cumba.cdisc.core.metadata.AdamDataStructureDetector} /
 * {@link net.cumba.cdisc.core.metadata.AdamSubclassDetector} read the sponsor's declaration
 * ({@link #DEFINE}) instead of inferring a structure out of the fixture's columns. Before this the
 * fixtures had to carry an {@code AETERM} column no {@code Check} reads, purely so the OCCDS /
 * ADVERSE EVENT inference would agree with the scope — make-the-data-true wearing a test's clothes.
 * The column is gone; the declaration carries it.
 * </p>
 */
class PmdaAdC3WithinDatasetProbeTest
{

    private static final YAMLMapper MAPPER = (YAMLMapper) new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** All 28 authored ids (base + B version-split twin). */
    private static final String[] AD_IDS =
    {
            "AD0280", "AD0280B", "AD0281", "AD0281B", "AD0283", "AD0283B", "AD0284", "AD0284B",
            "AD0287", "AD0287B", "AD0288", "AD0288B", "AD0289", "AD0289B", "AD0290", "AD0290B",
            "AD0293", "AD0293B", "AD0294", "AD0294B", "AD0295", "AD0295B", "AD0296", "AD0296B",
            "AD0310", "AD0310B", "AD0311", "AD0311B"
    };

    private static Rule loadRule(String adId) throws Exception
    {
        Path file = Path.of("src/test/resources/fixtures/rules/checks/PMDA/PMDA-" + adId + ".yaml");
        Rule rule = MAPPER.readValue(Files.readString(file), Rule.class);
        // Form-B operations (PLAN-retire-corpus-transforms phase 8) carry no operator
        // until normalized — the same pass the loader and RuleScaffold run.
        RulePackageLoader.normalizeOperations(rule);
        rule.setCheckExpr(CheckToExpr.toExpr(rule.getCheck()));
        return rule;
    }

    /**
     * Fix #223 — what the sponsor declares ADAE to be. Handed to the {@code defineProvider}
     * parameter of the 7-arg {@code RuleRunner.execute}, this is tier 1 of the
     * {@code Scope.Data_Structures} / {@code Scope.Subclasses} determination and beats the column
     * heuristic outright.
     */
    private static final MetadataProvider DEFINE = new StubMetadataProvider().declares("ADAE",
            "OCCURRENCE DATA STRUCTURE", "ADVERSE EVENT");

    private static int violations(Rule rule, IDataTable table)
    {
        RuleExecutionResult result = RuleRunner.execute(rule, table, _ -> null, null, null, null,
                DEFINE);
        return result.getViolationCount();
    }

    // -----------------------------------------------------------------------
    // All 28 rules must raise to a native expression (has_multiple_values_for is a Java-only group
    // operator; confirm it still converts). The four SMQzz templates carry wildcard tokens but the
    // Check itself is a plain has_multiple_values_for leaf, so it must convert too.
    // -----------------------------------------------------------------------


    @Test
    void allTwentyEightRulesConvertToNativeExpression() throws Exception
    {
        for (String adId : AD_IDS)
        {
            assertNotNull(loadRule(adId).getCheckExpr(),
                    "PMDA-" + adId + " must raise to a native expression");
        }
    }

    // -----------------------------------------------------------------------
    // AD0280 — X=AESEV, Y=AESEVN. Fires when a single AESEVN value maps to two AESEV values.
    // -----------------------------------------------------------------------


    @Test
    void ad0280_firesWhenOneAesevnMapsToTwoAesev() throws Exception
    {
        Rule rule = loadRule("AD0280");
        // AESEVN=1 maps to both MILD and SEVERE -> inconsistent within the dataset.
        IDataTable adae = MockTable.of().col("AESEV", "MILD", "SEVERE", "MODERATE")
                .col("AESEVN", "1", "1", "2").name("ADAE").build();
        assertTrue(violations(rule, adae) >= 1,
                "AESEVN=1 maps to two AESEV values -> at least one violation");
    }


    @Test
    void ad0280_noFireWhenConsistent() throws Exception
    {
        Rule rule = loadRule("AD0280");
        IDataTable adae = MockTable.of().col("AESEV", "MILD", "MILD", "MODERATE")
                .col("AESEVN", "1", "1", "2").name("ADAE").build();
        assertEquals(0, violations(rule, adae),
                "each AESEVN value maps to exactly one AESEV -> no violation");
    }

    // -----------------------------------------------------------------------
    // AD0284 — X=ASEVN, Y=ASEV (BDS/OCCDS analysis pair). Fires when a single ASEV value maps to
    // two
    // ASEVN values.
    // -----------------------------------------------------------------------


    @Test
    void ad0284_firesWhenOneAsevMapsToTwoAsevn() throws Exception
    {
        Rule rule = loadRule("AD0284");
        IDataTable ds = MockTable.of().col("ASEVN", "1", "2", "3")
                .col("ASEV", "MILD", "MILD", "SEVERE").name("ADAE").build();
        assertTrue(violations(rule, ds) >= 1,
                "ASEV=MILD maps to ASEVN 1 and 2 -> at least one violation");
    }


    @Test
    void ad0284_noFireWhenConsistent() throws Exception
    {
        Rule rule = loadRule("AD0284");
        IDataTable ds = MockTable.of().col("ASEVN", "1", "1", "3")
                .col("ASEV", "MILD", "MILD", "SEVERE").name("ADAE").build();
        assertEquals(0, violations(rule, ds), "each ASEV value maps to one ASEVN -> no violation");
    }

    // -----------------------------------------------------------------------
    // AD0293 — X=AETOXGR, Y=AETOXGRN. Fires when a single AETOXGRN value maps to two AETOXGR
    // values.
    // -----------------------------------------------------------------------


    @Test
    void ad0293_firesWhenOneAetoxgrnMapsToTwoAetoxgr() throws Exception
    {
        Rule rule = loadRule("AD0293");
        IDataTable adae = MockTable.of().col("AETOXGR", "GRADE 1", "GRADE 2", "GRADE 3")
                .col("AETOXGRN", "1", "1", "3").name("ADAE").build();
        assertTrue(violations(rule, adae) >= 1,
                "AETOXGRN=1 maps to two AETOXGR values -> at least one violation");
    }


    @Test
    void ad0293_noFireWhenConsistent() throws Exception
    {
        Rule rule = loadRule("AD0293");
        IDataTable adae = MockTable.of().col("AETOXGR", "GRADE 1", "GRADE 1", "GRADE 3")
                .col("AETOXGRN", "1", "1", "3").name("ADAE").build();
        assertEquals(0, violations(rule, adae),
                "each AETOXGRN value maps to one AETOXGR -> no violation");
    }

    // -----------------------------------------------------------------------
    // AD0310 — X=SMQzzSC, Y=SMQzzSCN. The paired zz wildcard must expand to concrete SMQ01SC /
    // SMQ01SCN columns sharing the same index, then fire on within-dataset inconsistency.
    // -----------------------------------------------------------------------


    @Test
    void ad0310_pairedWildcardExpandsAndFires() throws Exception
    {
        Rule rule = loadRule("AD0310");
        // SMQ01SCN=1 maps to both BROAD and NARROW -> inconsistent within the dataset.
        IDataTable adae = MockTable.of().col("SMQ01SC", "BROAD", "NARROW", "BROAD")
                .col("SMQ01SCN", "1", "1", "2").name("ADAE").build();

        WildcardExpander.ExpansionResult res = WildcardExpander.tryExpand(rule, adae.getMetaData());
        assertTrue(res instanceof WildcardExpander.ExpansionResult.Expanded,
                "AD0310 must expand its zz wildcard against SMQ01SC / SMQ01SCN");
        List<Rule> expanded = ((WildcardExpander.ExpansionResult.Expanded) res).rules();
        assertEquals(1, expanded.size(), "one concrete SMQ index (01) present -> one expansion");
        Rule concrete = expanded.get(0);
        assertNotNull(concrete.getCheckExpr(), "expanded SMQ rule installs its native expression");
        assertTrue(violations(concrete, adae) >= 1,
                "SMQ01SCN=1 maps to two SMQ01SC values -> at least one violation");
    }


    @Test
    void ad0310_pairedWildcardNoFireWhenConsistent() throws Exception
    {
        Rule rule = loadRule("AD0310");
        IDataTable adae = MockTable.of().col("SMQ01SC", "BROAD", "BROAD", "NARROW")
                .col("SMQ01SCN", "1", "1", "2").name("ADAE").build();

        WildcardExpander.ExpansionResult res = WildcardExpander.tryExpand(rule, adae.getMetaData());
        assertTrue(res instanceof WildcardExpander.ExpansionResult.Expanded,
                "AD0310 must expand against the concrete SMQ01 columns");
        Rule concrete = ((WildcardExpander.ExpansionResult.Expanded) res).rules().get(0);
        assertFalse(violations(concrete, adae) > 0,
                "each SMQ01SCN value maps to one SMQ01SC -> no violation");
    }
}
