package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.exec.StubMetadataProvider;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Probe for the six newly-authored PMDA-AD SMQ/CQ MedDRA-index rules
 * (PMDA-AD0299/0303/0304/0312/0313/0314).
 *
 * <p>
 * The four non-Draft rules (AD0299/0303 variable-name index legality; AD0312/0313 indexed value
 * checks) must raise to a native expression (zero-legacy gate). AD0312/0313 are the most directly
 * testable: they are {@code Value Check with Variable Metadata} rules that identify the
 * SMQzz-indexed scope column by a {@code variable_name} regex and then apply a value-membership
 * check, so a bad value fires and a valid / null value does not. AD0304 and AD0314 are authored
 * Draft (grouped all-or-none presence, and an unresolved 'original/prior MedDRA' variable family,
 * respectively) and are therefore not asserted here.
 * </p>
 *
 * <p>
 * <b>Fix #223 — the structure is DECLARED, not re-derived.</b> AD0312/AD0313 are gated by
 * {@code Scope.Data_Structures} + {@code Scope.Subclasses}. Every execution here goes through the
 * 7-arg {@code RuleRunner.execute} overload carrying a {@code defineProvider}, so
 * {@link net.cumba.cdisc.core.metadata.AdamDataStructureDetector} /
 * {@link net.cumba.cdisc.core.metadata.AdamSubclassDetector} read the sponsor's declaration
 * ({@link #DEFINE}). Before this every fixture carried an {@code AETERM} column no {@code Check}
 * reads, present only because it is the OCCDS suffix carrier and an ADVERSE EVENT subclass marker —
 * without it the rules were SKIPPED. That is make-the-data-true; the column is gone and the
 * declaration carries it.
 * </p>
 */
class PmdaAdSmqCqProbeTest
{

    private static final YAMLMapper MAPPER = (YAMLMapper) new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /** The four rules authored non-Draft — each must convert to a native expression. */
    private static final String[] NATIVE_IDS =
    {
            "AD0299", "AD0303", "AD0312", "AD0313"
    };

    private static Rule loadRule(String adId) throws Exception
    {
        Path file = Path.of("src/test/resources/fixtures/rules/checks/PMDA/PMDA-" + adId + ".yaml");
        Rule rule = MAPPER.readValue(Files.readString(file), Rule.class);
        // rules-src no longer carries Rule_Type / Sensitivity — the loader
        // derives them, so a hand-bound rule must be completed the same way.
        // Form-B operations (PLAN-retire-corpus-transforms phase 8) carry no operator
        // until normalized — the same pass the loader and RuleScaffold run.
        RulePackageLoader.normalizeOperations(rule);
        RulePackageLoader.deriveOmittedFields(rule);
        rule.setCheckExpr(CheckToExpr.toExpr(rule.getCheck()));
        return rule;
    }

    /** Fix #223 — what the sponsor declares ADAE to be (see the class javadoc). */
    private static final MetadataProvider DEFINE = new StubMetadataProvider().declares("ADAE",
            "OCCURRENCE DATA STRUCTURE", "ADVERSE EVENT");

    private static int violations(Rule rule, IDataTable table)
    {
        RuleExecutionResult result = RuleRunner.execute(rule, table, _ -> null, null, null, null,
                DEFINE);
        return result.getViolationCount();
    }

    // -----------------------------------------------------------------------
    // All four non-Draft rules must raise to a native expression.
    // -----------------------------------------------------------------------


    @Test
    void nonDraftRulesConvertToNativeExpression() throws Exception
    {
        for (String adId : NATIVE_IDS)
        {
            assertNotNull(loadRule(adId).getCheckExpr(),
                    "PMDA-" + adId + " must raise to a native expression (zero-legacy gate)");
        }
    }

    // -----------------------------------------------------------------------
    // AD0312 — SMQzzSC value must be BROAD, NARROW or null.
    // -----------------------------------------------------------------------


    @Test
    void ad0312_firesWhenSmqScValueIllegal() throws Exception
    {
        Rule rule = loadRule("AD0312");
        // SMQ01NAM is present but is NOT an SC column, so the regex must not pick it up.
        IDataTable adae = MockTable.of().col("USUBJID", "S1").col("SMQ01NAM", "Some Query")
                .col("SMQ01SC", "MEDIUM").name("ADAE").build();

        assertEquals(1, violations(rule, adae), "SMQ01SC='MEDIUM' is not BROAD/NARROW -> fires");
    }


    @Test
    void ad0312_noFireForBroadNarrowOrNull() throws Exception
    {
        Rule rule = loadRule("AD0312");
        IDataTable broadNarrow = MockTable.of().col("USUBJID", "S1", "S2")
                .col("SMQ01SC", "BROAD", "NARROW").name("ADAE").build();
        assertEquals(0, violations(rule, broadNarrow), "BROAD and NARROW are allowed -> no fire");

        IDataTable nullValue = MockTable.of().col("USUBJID", "S1").col("SMQ01SC", "").name("ADAE")
                .build();
        assertEquals(0, violations(rule, nullValue), "null (empty) SMQ01SC -> no fire");
    }

    // -----------------------------------------------------------------------
    // AD0313 — SMQzzSCN value must be 1, 2 or null.
    // -----------------------------------------------------------------------


    @Test
    void ad0313_firesWhenSmqScnValueIllegal() throws Exception
    {
        Rule rule = loadRule("AD0313");
        IDataTable adae = MockTable.of().col("USUBJID", "S1").colLong("SMQ01SCN", 3L).name("ADAE")
                .build();
        assertEquals(1, violations(rule, adae), "SMQ01SCN=3 is not 1 or 2 -> fires");
    }


    @Test
    void ad0313_noFireForOneTwoOrNull() throws Exception
    {
        Rule rule = loadRule("AD0313");
        IDataTable oneTwo = MockTable.of().col("USUBJID", "S1", "S2").colLong("SMQ01SCN", 1L, 2L)
                .name("ADAE").build();
        assertEquals(0, violations(rule, oneTwo), "1 and 2 are allowed -> no fire");

        IDataTable nullValue = MockTable.of().col("USUBJID", "S1").col("SMQ01SCN", "").name("ADAE")
                .build();
        assertEquals(0, violations(rule, nullValue), "null (empty) SMQ01SCN -> no fire");
    }
}
