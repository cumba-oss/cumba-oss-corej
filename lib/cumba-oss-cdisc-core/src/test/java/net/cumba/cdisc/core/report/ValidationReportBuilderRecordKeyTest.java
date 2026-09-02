package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.RecordKeyResolver;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.Violation;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationFindingLocation;
import net.cumba.datatable.report.ValidationReport;
import org.junit.jupiter.api.Test;

/**
 * Tests for the EC-40 record key and the D5/D17 location fix in {@link ValidationReportBuilder}.
 *
 * <p>
 * The worked cases mirror §9 of {@code plans/done/PLAN-finding-record-keys.md}: an ordinary record
 * rule (A), a rule that genuinely declares {@code USUBJID} (B), a SUPP dataset with a resolved key
 * (C), and a rule whose {@code Output_Variables} all failed to resolve (D).
 * </p>
 */
class ValidationReportBuilderRecordKeyTest
{

    /** §9 Case A — injected USUBJID / SEQ are kept out of the location's flagged columns. */
    @Test
    void caseA_injectedIdentityIsNotAFlaggedLocationColumn()
    {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("AETERM", "HEADACHE");
        Violation v = new Violation(41L, values, "STUDY-001-002", "3");

        ValidationFinding f = firstFinding(build(v, RecordKeyResolver.KeySource.NONE));

        // The slab schema is untouched — the v1 report reads USUBJID / SEQ from it.
        assertTrue(f.getVariableNames().contains("USUBJID"));
        assertTrue(f.getVariableNames().contains("SEQ"));
        // The location carries only the flagged column. "SEQ" in particular is not a real column
        // of AE (the real one is AESEQ), so it never belonged in a real-columns-only field.
        assertEquals(List.of("AETERM"), f.getLocation().getVariableNames());
    }


    /** §9 Case B — an identity name the rule declared itself survives in the location. */
    @Test
    void caseB_declaredUsubjidIsKeptAsAFlaggedColumn()
    {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("USUBJID", "STUDY-001-002");
        Violation v = new Violation(0L, values, "STUDY-001-002", "3");

        ValidationFinding f = firstFinding(build(v, RecordKeyResolver.KeySource.NONE));

        // USUBJID was projected by the rule's own Output_Variables, so it is genuinely flagged.
        assertEquals(List.of("USUBJID"), f.getLocation().getVariableNames());
    }


    /**
     * §9 Case B, harder — the union across the group matters. EC-37's omit-don't-null means an
     * unresolved Output_Variable is absent from some rows' values, so sampling one violation could
     * misclassify a declared name as injected.
     */
    @Test
    void caseB_declaredNameIsDetectedFromAnyViolationInTheGroup()
    {
        Map<String, String> declared = new LinkedHashMap<>();
        declared.put("USUBJID", "STUDY-001-002");
        declared.put("AETERM", "HEADACHE");
        Map<String, String> partial = new LinkedHashMap<>();
        partial.put("USUBJID", "STUDY-001-003");
        partial.put("AETERM", "COUGH");

        ValidationFinding f = firstFinding(build(
                List.of(new Violation(0L, declared, "STUDY-001-002", "1"),
                        new Violation(1L, partial, "STUDY-001-003", "2")),
                RecordKeyResolver.KeySource.NONE));

        assertTrue(f.getLocation().getVariableNames().contains("USUBJID"));
        assertFalse(f.getLocation().getVariableNames().contains("SEQ"));
    }


    /** §9 Case C — a resolved key lands on the location and in the parallel slab. */
    @Test
    void caseC_recordKeyIsCarriedOnTheLocationAndTheKeySlab()
    {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("QVAL", "Y");
        Map<String, String> keys = new LinkedHashMap<>();
        keys.put("RDOMAIN", "AE");
        keys.put("IDVAR", "AESEQ");
        keys.put("IDVARVAL", "3");
        keys.put("QNAM", "AESOSP");
        Violation v = new Violation(6L, values, "STUDY-001-002", null, keys);

        ValidationFinding f = firstFinding(build(v, RecordKeyResolver.KeySource.STRUCTURAL));
        ValidationFindingLocation loc = f.getLocation();

        assertEquals(List.of("QVAL"), loc.getVariableNames());
        assertEquals(List.of("RDOMAIN", "IDVAR", "IDVARVAL", "QNAM"), loc.getKeyVariableNames());
        assertEquals("STRUCTURAL", loc.getKeySource());
        // Values ride a slab of their own, aligned with the main slab's rows.
        assertEquals(keys, f.getRowKeys(0));
        assertEquals(f.getRows().rowCount(), f.getKeyRows().rowCount());
        // ... and never leak into the reported variable schema (D6).
        assertFalse(f.getVariableNames().contains("QNAM"));
        assertFalse(f.getVariableNames().contains("RDOMAIN"));
    }


    /**
     * §9 Case D — the accepted downside: when every schema entry was injected identity, the
     * location degrades to dataset-only rather than pointing at a column that does not exist.
     */
    @Test
    void caseD_allInjectedIdentityYieldsAnEmptyFlaggedColumnList()
    {
        Violation v = new Violation(0L, Map.of(), "STUDY-001-002", "3");

        ValidationFinding f = firstFinding(build(v, RecordKeyResolver.KeySource.NONE));

        assertTrue(f.getLocation().getVariableNames().isEmpty());
        // The dataset is still there, so the finding remains locatable to a row.
        assertEquals("AE", f.getLocation().getDataset());
        assertTrue(f.hasRows());
    }


    @Test
    void noKeyResolved_leavesKeyFieldsEmptyAndSourceNull()
    {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("AETERM", "HEADACHE");
        Violation v = new Violation(0L, values, "S01", "1");

        ValidationFinding f = firstFinding(build(v, RecordKeyResolver.KeySource.NONE));

        assertTrue(f.getLocation().getKeyVariableNames().isEmpty());
        assertNull(f.getLocation().getKeySource());
        assertEquals(0, f.getKeyRows().rowCount());
        assertTrue(f.getRowKeys(0).isEmpty());
    }


    @Test
    void keySourceIsNullWhenTheTierResolvedNothingEvenIfReported()
    {
        // Defensive: a NONE tier must never surface a key source, whatever the result says.
        Map<String, String> values = new LinkedHashMap<>();
        values.put("AETERM", "HEADACHE");
        Violation v = new Violation(0L, values, "S01", "1");

        ValidationFinding f = firstFinding(build(v, RecordKeyResolver.KeySource.DEFINE_KEY));

        // No keys on the violation => no key schema => no source, despite the DEFINE_KEY tier.
        assertTrue(f.getLocation().getKeyVariableNames().isEmpty());
        assertNull(f.getLocation().getKeySource());
    }


    /** D7 (review finding #2) — a key column blank on every row of the finding is dropped. */
    @Test
    void keyColumnsEmptyOnEveryRowAreDropped()
    {
        Map<String, String> keys1 = new LinkedHashMap<>();
        keys1.put("QNAM", "AESOSP");
        keys1.put("AESPID", "");
        keys1.put("AEREFID", "");
        Map<String, String> keys2 = new LinkedHashMap<>();
        keys2.put("QNAM", "AESER");
        keys2.put("AESPID", "");
        keys2.put("AEREFID", "R1");

        ValidationFinding f = firstFinding(build(
                List.of(new Violation(0L, Map.of("QVAL", "Y"), "S01", null, keys1),
                        new Violation(1L, Map.of("QVAL", "N"), "S02", null, keys2)),
                RecordKeyResolver.KeySource.STRUCTURAL));

        // AESPID is blank on both rows and goes; AEREFID is populated on one and stays.
        assertEquals(List.of("QNAM", "AEREFID"), f.getLocation().getKeyVariableNames());
        assertEquals(Map.of("QNAM", "AESOSP", "AEREFID", ""), f.getRowKeys(0));
        assertEquals(Map.of("QNAM", "AESER", "AEREFID", "R1"), f.getRowKeys(1));
    }


    /** D7 taken to its limit — an all-blank key leaves no key at all, and no source. */
    @Test
    void anEntirelyBlankKeyIsDroppedCompletely()
    {
        Map<String, String> blank = new LinkedHashMap<>();
        blank.put("AESPID", "");
        blank.put("AEREFID", "");

        ValidationFinding f = firstFinding(
                build(List.of(new Violation(0L, Map.of("AETERM", "X"), "S01", "1", blank)),
                        RecordKeyResolver.KeySource.SPONSOR_ID));

        assertTrue(f.getLocation().getKeyVariableNames().isEmpty());
        assertNull(f.getLocation().getKeySource());
        assertEquals(0, f.getKeyRows().rowCount());
    }


    /**
     * Review finding #5 — a variable the rule declared in {@code Output_Variables} is never treated
     * as injected identity, even when it resolved on no row.
     */
    @Test
    void declaredButUnresolvedUsubjidIsStillAFlaggedColumn()
    {
        Rule rule = ruleWithOutputVariables("USUBJID");
        // USUBJID declared but unresolved => omitted from values (EC-37 omit-don't-null).
        Violation v = new Violation(0L, Map.of(), "STUDY-001-002", "3");

        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("CORE-KEY-TEST")
                .message("test message").violations(List.of(v)).totalRows(10)
                .keySource(RecordKeyResolver.KeySource.NONE).build();
        ValidationFinding f = firstFinding(
                new ValidationReportBuilder().add("AE", "ae.xpt", rule, result).build());

        assertEquals(List.of("USUBJID"), f.getLocation().getVariableNames());
    }


    private static Rule ruleWithOutputVariables(String... aNames)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-KEY-TEST");
        rule.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage("test message");
        outcome.setOutputVariables(List.of(aNames));
        rule.setOutcome(outcome);
        return rule;
    }


    private static ValidationFinding firstFinding(ValidationReport aReport)
    {
        return aReport.getMembers().getFirst().getFindings().getFirst();
    }


    private static ValidationReport build(Violation aViolation, RecordKeyResolver.KeySource aSource)
    {
        return build(List.of(aViolation), aSource);
    }


    private static ValidationReport build(List<Violation> aViolations,
            RecordKeyResolver.KeySource aSource)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CORE-KEY-TEST");
        rule.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage("test message");
        rule.setOutcome(outcome);

        RuleExecutionResult result = RuleExecutionResult.builder().ruleId("CORE-KEY-TEST")
                .message("test message").violations(aViolations).totalRows(10).keySource(aSource)
                .build();

        return new ValidationReportBuilder().add("AE", "ae.xpt", rule, result).build();
    }

}
