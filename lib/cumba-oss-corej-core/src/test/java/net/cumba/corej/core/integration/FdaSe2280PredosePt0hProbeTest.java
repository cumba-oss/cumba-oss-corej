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
 * Probe for the (re-authored) FDA-SE2280 pre-dose PCELTM rule.
 *
 * <p>
 * SE2280 was catalogued as an engine gap on the claim that "pre-dose" is not a detectable
 * per-record condition. It is: a specimen collected <em>at or before</em> its reference point is
 * {@code PCDTC <= PCRFTDTC}, a plain same-row ISO date comparison. This probe loads the authored
 * {@code rules-src} check directly (no corpus regeneration), proves it converts to a native
 * expression, and confirms it fires on exactly the pre-dose / at-reference rows whose
 * {@code PCELTM} is not {@code "PT0H"} — and stays quiet on post-dose rows, on already-correct
 * {@code PT0H} rows, and (via the time-component guard) on date-only rows.
 * </p>
 */
class FdaSe2280PredosePt0hProbeTest
{

    private static final YAMLMapper MAPPER = (YAMLMapper) new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Rule loadRule() throws Exception
    {
        Path file = Path.of("src/test/resources/fixtures/rules/checks/FDA/FDA-SE2280.yaml");
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
    void convertsToNativeExpression() throws Exception
    {
        assertNotNull(loadRule().getCheckExpr(),
                "FDA-SE2280 must raise to a native expression (zero-legacy gate)");
    }


    @Test
    void firesOnlyOnPreDoseOrAtReferenceRowsWithWrongPceltm() throws Exception
    {
        Rule rule = loadRule();
        // row → (PCDTC, PCRFTDTC, PCELTM) : expectation
        // 1 pre-dose, correct PT0H → no fire
        // 2 pre-dose, wrong -PT1H → FIRE
        // 3 at-reference (equal), wrong PT2H → FIRE
        // 4 post-dose (after reference) → no fire
        // 5 date-only (no time component) → no fire (guarded out)
        // 6 reference time-point missing → no fire (guarded out)
        IDataTable pc = MockTable.of().col("USUBJID", "S1", "S1", "S1", "S1", "S1", "S1")
                .col("PCDTC", "2023-01-15T08:00", "2023-01-15T08:00", "2023-01-15T09:00",
                        "2023-01-15T10:00", "2023-01-15", "2023-01-15T08:00")
                .col("PCRFTDTC", "2023-01-15T09:00", "2023-01-15T09:00", "2023-01-15T09:00",
                        "2023-01-15T09:00", "2023-01-15", "")
                .col("PCELTM", "PT0H", "-PT1H", "PT2H", "PT1H", "PT2H", "PT2H").name("PC").build();

        assertEquals(2, violations(rule, pc),
                "only the pre-dose (-PT1H) and at-reference (PT2H) rows must fire");
    }


    private static Rule loadCompanionA() throws Exception
    {
        Path file = Path.of("src/test/resources/fixtures/rules/checks/FDA/FDA-SE2280-A.yaml");
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


    @Test
    void companionAConvertsToNativeExpression() throws Exception
    {
        assertNotNull(loadCompanionA().getCheckExpr(),
                "FDA-SE2280-A must raise to a native expression");
    }


    @Test
    void companionAFiresOnProfileRecordsWithDateOnlyTiming() throws Exception
    {
        Rule rule = loadCompanionA();
        // row → (PCDTC, PCRFTDTC, PCELTM) : expectation
        // 1 both full datetime, profile record → no fire
        // 2 PCDTC date-only, profile record → FIRE
        // 3 PCRFTDTC date-only, profile record → FIRE
        // 4 both date-only, profile record → FIRE
        // 5 date-only but PCELTM empty (not a profile) → no fire
        IDataTable pc = MockTable.of().col("USUBJID", "S1", "S1", "S1", "S1", "S1")
                .col("PCDTC", "2023-01-15T08:00", "2023-01-15", "2023-01-15T08:00", "2023-01-15",
                        "2023-01-15")
                .col("PCRFTDTC", "2023-01-15T09:00", "2023-01-15T09:00", "2023-01-15", "2023-01-15",
                        "2023-01-15")
                .col("PCELTM", "PT1H", "PT1H", "PT1H", "PT1H", "").name("PC").build();

        assertEquals(3, violations(rule, pc),
                "the three profile records with a date-only PCDTC or PCRFTDTC must fire");
    }


    private static Rule loadCompanionB() throws Exception
    {
        Path file = Path.of("src/test/resources/fixtures/rules/checks/FDA/FDA-SE2280-B.yaml");
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


    @Test
    void companionBConvertsToNativeExpression() throws Exception
    {
        assertNotNull(loadCompanionB().getCheckExpr(),
                "FDA-SE2280-B must raise to a native expression");
    }


    @Test
    void companionBFiresOnNegativePceltm() throws Exception
    {
        Rule rule = loadCompanionB();
        // PCELTM: PT0H | -PT30M | PT2H | -PT1H | empty
        IDataTable pc = MockTable.of().col("USUBJID", "S1", "S1", "S1", "S1", "S1")
                .col("PCELTM", "PT0H", "-PT30M", "PT2H", "-PT1H", "").name("PC").build();
        assertEquals(2, violations(rule, pc), "only the two negative-PCELTM rows fire");
    }
}
