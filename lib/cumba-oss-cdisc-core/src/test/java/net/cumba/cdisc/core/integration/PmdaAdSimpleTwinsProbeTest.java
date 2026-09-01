package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.exec.StubMetadataProvider;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Probe for the five newly-authored simple PMDA-AD rules (PMDA-AD0084/0098/0099/0651/0652), each a
 * verbatim mirror of its shipping CDISC-AD twin (CDISC-AD0084/0098/0099/0651/0652).
 *
 * <p>
 * All five load directly from {@code rules-src} (no corpus regeneration) and must raise to a native
 * expression (the repo's zero-legacy gate).
 * </p>
 *
 * <p>
 * All five are generation-time wildcard templates. AD0084 ({@code TRxxEDT}), AD0651
 * ({@code ONTRxxFL}) and AD0652 ({@code ONTRTwFL}) use {@code xx}/{@code w} capture tokens; AD0098
 * ({@code *SDY} vs {@code *EDY}) and AD0099 ({@code *STDY} vs {@code *ENDY}) use star-suffix
 * prefix-keyed sibling pairing. In every case the wildcard is concretised by the generator against
 * a dataset's real column metadata (see
 * {@link AdamGapRevalidationMechanismProbeTest#cdiscAd0084_shipsFullyExecutable_loadsClean_executesWithoutError()}),
 * not by {@code RuleRunner} on a {@code MockTable} — a raw template supplies no column metadata, so
 * the wildcard never expands and no violation is produced at this layer (verified empirically: an
 * {@code ADTSDY 10 > ADTEDY 5} record does not fire the raw AD0098 template). Their probe is
 * therefore native-convert plus executes-without-error, mirroring how the CDISC-AD0084 twin is
 * probed.
 * </p>
 */
class PmdaAdSimpleTwinsProbeTest
{

    private static final YAMLMapper MAPPER = (YAMLMapper) new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static final String[] AD_IDS =
    {
            "AD0084", "AD0098", "AD0099", "AD0651", "AD0652"
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
     * Fix #223 — the declare channel. PMDA-AD0084 is scoped
     * {@code Scope.Data_Structures.Include:[SUBJECT LEVEL ANALYSIS DATASET]}; without a declaration
     * the gate resolves that structure from the dataset being <em>named</em> {@code ADSL}, which is
     * tier 3 (local-only) re-derivation. Declaring it makes the fixture say what the sponsor says
     * rather than leaving the engine to guess from the name.
     */
    private static final MetadataProvider DEFINE = new StubMetadataProvider()
            .declares("ADSL", "SUBJECT LEVEL ANALYSIS DATASET")
            .declares("ADQS", "BASIC DATA STRUCTURE")
            .declares("ADAE", "OCCURRENCE DATA STRUCTURE", "ADVERSE EVENT");

    private static RuleExecutionResult execute(Rule rule, IDataTable table)
    {
        return RuleRunner.execute(rule, table, _ -> null, null, null, null, DEFINE);
    }


    private static int violations(Rule rule, IDataTable table)
    {
        return execute(rule, table).getViolationCount();
    }

    // -----------------------------------------------------------------------
    // All five rules must raise to a native expression (zero-legacy gate).
    // -----------------------------------------------------------------------


    @Test
    void allFiveRulesConvertToNativeExpression() throws Exception
    {
        for (String adId : AD_IDS)
        {
            assertNotNull(loadRule(adId).getCheckExpr(),
                    "PMDA-" + adId + " must raise to a native expression (zero-legacy gate)");
        }
    }

    // -----------------------------------------------------------------------
    // AD0098 — *SDY > *EDY star-suffix sibling pairing (generation-time
    // wildcard; not runtime-expanded by RuleRunner on a MockTable).
    // -----------------------------------------------------------------------


    @Test
    void ad0098_executesWithoutError() throws Exception
    {
        Rule rule = loadRule("AD0098");
        // The retired legacy engine tolerated executing the RAW template; natively a wildcard
        // template must be expanded first (the production generator path). ADTSDY 10 > ADTEDY 5
        // violates on the concretised pair.
        IDataTable adbds = MockTable.of().col("USUBJID", "S1").colLong("ADTSDY", 10L)
                .colLong("ADTEDY", 5L).name("ADQS").build();
        int total = 0;
        for (Rule expanded : net.cumba.cdisc.core.gen.WildcardExpander.expand(rule,
                adbds.getMetaData()))
        {
            total += violations(expanded, adbds);
        }
        assertEquals(1, total,
                "*SDY/*EDY sibling pairing concretises to ADTSDY/ADTEDY and fires on 10 > 5");
    }

    // -----------------------------------------------------------------------
    // AD0099 — *STDY > *ENDY star-suffix sibling pairing (generation-time
    // wildcard; not runtime-expanded by RuleRunner on a MockTable).
    // -----------------------------------------------------------------------


    @Test
    void ad0099_executesWithoutError() throws Exception
    {
        Rule rule = loadRule("AD0099");
        IDataTable adbds = MockTable.of().col("USUBJID", "S1").colLong("ASTDY", 12L)
                .colLong("AENDY", 4L).name("ADQS").build();
        int total = 0;
        for (Rule expanded : net.cumba.cdisc.core.gen.WildcardExpander.expand(rule,
                adbds.getMetaData()))
        {
            total += violations(expanded, adbds);
        }
        assertEquals(1, total,
                "*STDY/*ENDY sibling pairing concretises to ASTDY/AENDY and fires on 12 > 4");
    }

    // -----------------------------------------------------------------------
    // AD0084 / AD0651 / AD0652 — generation-time wildcard templates. Not
    // runtime-expanded by RuleRunner on a MockTable (mirrors the CDISC-AD0084
    // probe). Evidence: native-convert (above) + executes without error.
    // -----------------------------------------------------------------------


    @Test
    void ad0084_executesWithoutError() throws Exception
    {
        Rule rule = loadRule("AD0084");
        // EC-8: AD0084 now uses the row_max Operation (name_pattern over TRxxEDT), evaluated at
        // this
        // layer with no wildcard expansion. TRTEDT equals max(TR01EDT, TR02EDT) = 100, so the
        // not_equal_to Check does not fire → clean execution, 0 violations.
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").colLong("TRTEDT", 100L)
                .colLong("TR01EDT", 50L).colLong("TR02EDT", 100L).name("ADSL").build();
        RuleExecutionResult result = execute(rule, adsl);
        // Fix #223: 0 violations alone cannot tell "executed and stayed quiet" from "skipped by
        // the Scope.Data_Structures gate" — this rule has no paired _fires twin to act as the
        // positive control, so the not-skipped assertion IS the control. It is what makes the
        // declared SUBJECT LEVEL ANALYSIS DATASET (see DEFINE) observable here.
        assertFalse(result.isSkipped(),
                "declared SUBJECT LEVEL ANALYSIS DATASET → AD0084 is in scope, not skipped");
        assertEquals(0, result.getViolationCount(),
                "TRTEDT equals max(TRxxEDT) → row_max Check does not fire");
    }


    @Test
    void ad0651_executesWithoutError() throws Exception
    {
        Rule rule = loadRule("AD0651");
        IDataTable adoccds = MockTable.of().col("USUBJID", "S1").col("ONTR01FL", "Y").name("ADAE")
                .build();
        assertEquals(0, violations(rule, adoccds),
                "raw xx wildcard template executes without error (no runtime expansion)");
    }


    @Test
    void ad0652_executesWithoutError() throws Exception
    {
        Rule rule = loadRule("AD0652");
        IDataTable adoccds = MockTable.of().col("USUBJID", "S1").col("ONTRT1FL", "Y").name("ADAE")
                .build();
        assertEquals(0, violations(rule, adoccds),
                "raw w wildcard template executes without error (no runtime expansion)");
    }
}
