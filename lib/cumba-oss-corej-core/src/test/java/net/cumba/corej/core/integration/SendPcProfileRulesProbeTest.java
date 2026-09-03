package net.cumba.corej.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Probes for the three re-validated SEND PC §6.3.11 rules that were previously gapped as "no data
 * signal": CDISC-SEND-0327 (pre-dose PCELTM=PT0H, the SE2280 twin), CDISC-SEND-0326 (PCELTM must be
 * populated on a profile record), and CDISC-SEND-0324 (a quantifiably out-of-range result must
 * carry the matching BLQ/ALQ token). Each is loaded from {@code rules-src} directly (no corpus
 * regeneration), proven native-convertible, and exercised for firing correctness.
 */
class SendPcProfileRulesProbeTest
{

    private static final YAMLMapper MAPPER = (YAMLMapper) new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Rule load(String id) throws Exception
    {
        Path file = Path.of("src/test/resources/fixtures/rules/checks/CDISC/" + id + ".yaml");
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


    private static int violations(Rule rule, IDataTable pc)
    {
        RuleExecutionResult result = RuleRunner.execute(rule, pc, _ -> null);
        return result.getViolationCount();
    }


    @Test
    void allThreeConvertToNative() throws Exception
    {
        assertNotNull(load("CDISC-SEND-0327").getCheckExpr(), "SEND-0327 native");
        assertNotNull(load("CDISC-SEND-0326").getCheckExpr(), "SEND-0326 native");
        assertNotNull(load("CDISC-SEND-0324").getCheckExpr(), "SEND-0324 native");
    }


    @Test
    void send0327_firesOnPreDoseOrAtReferenceWithWrongPceltm() throws Exception
    {
        Rule rule = load("CDISC-SEND-0327");
        // pre-dose correct PT0H | pre-dose -PT1H | at-ref PT2H | post-dose | date-only | null ref
        IDataTable pc = MockTable.of().col("USUBJID", "S1", "S1", "S1", "S1", "S1", "S1")
                .col("PCDTC", "2023-01-15T08:00", "2023-01-15T08:00", "2023-01-15T09:00",
                        "2023-01-15T10:00", "2023-01-15", "2023-01-15T08:00")
                .col("PCRFTDTC", "2023-01-15T09:00", "2023-01-15T09:00", "2023-01-15T09:00",
                        "2023-01-15T09:00", "2023-01-15", "")
                .col("PCELTM", "PT0H", "-PT1H", "PT2H", "PT1H", "PT2H", "PT2H").name("PC").build();
        assertEquals(2, violations(rule, pc), "pre-dose (-PT1H) and at-reference (PT2H) rows fire");
    }


    @Test
    void send0326_firesWhenProfileRecordHasNoPceltm() throws Exception
    {
        Rule rule = load("CDISC-SEND-0326");
        // profile record + PCELTM | profile record, PCELTM empty | non-profile (no PCTPTREF)
        IDataTable pc = MockTable.of().col("USUBJID", "S1", "S1", "S1")
                .col("PCTPTREF", "DOSE", "DOSE", "").col("PCELTM", "PT1H", "", "").name("PC")
                .build();
        assertEquals(1, violations(rule, pc), "only the profile record with an empty PCELTM fires");
    }


    @Test
    void send0324_firesWhenOutOfRangeResultLacksToken() throws Exception
    {
        // 2026-07-26 redesign (INFO pass): SEND324 is now a genuine LOQ-detection rule —
        // PCORRES < PCLLOQ must carry PCSTRESC='BLQ', PCORRES > PCULOQ (sponsor-provided,
        // model-permissible) must carry 'ALQ'. Textual PCORRES skips (numeric coercion).
        Rule rule = load("CDISC-SEND-0324");
        // below w/o token (fires) | below w/ token (ok) | textual PCORRES (skips)
        // | above w/ wrong token (fires) | in range (ok)
        IDataTable pc = MockTable.of().col("USUBJID", "S1", "S1", "S1", "S1", "S1")
                .col("PCORRES", "2.5", "3.1", "BLQ", "250", "50")
                .col("PCLLOQ", "5", "5", "5", "5", "5")
                .col("PCULOQ", "100", "100", "100", "100", "100")
                .col("PCSTRESC", "2.5", "BLQ", "BLQ", "BLQ", "50").name("PC").build();
        assertEquals(2, violations(rule, pc),
                "the token-less below-limit row and the mis-tokened above-limit row fire");
    }
}
