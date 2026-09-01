package net.cumba.cdisc.core.report;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleExecutionStatus;
import net.cumba.cdisc.core.exec.Violation;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.datatable.report.RowFindingSlab;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.report.ValidationReportMember;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ValidationReportBuilder}'s {@code withIdentity} branches: when a
 * {@link Violation} carries USUBJID / SEQ outside the values map, the builder should fold them into
 * the per-row values so JsonReportWriter and the UI see them via the values map.
 */
class ValidationReportBuilderIdentityTest
{

    @Test
    void violationWithUsubjidOnly_foldsIntoValues()
    {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("AETERM", "Headache");
        Violation v = new Violation(0L, values, "S01", null);

        ValidationReport report = build(v, List.of("USUBJID", "AETERM"));
        assertNotNull(report);
        ValidationReportMember member = report.getMembers().getFirst();
        assertEquals(1, member.getFindings().size());
        ValidationFinding f = member.getFindings().getFirst();
        assertTrue(f.hasRows());
        RowFindingSlab slab = f.getRows();
        List<String> names = f.getVariableNames();
        int usubjidIdx = names.indexOf("USUBJID");
        int aetermIdx = names.indexOf("AETERM");
        assertTrue(usubjidIdx >= 0, "schema should include USUBJID");
        assertEquals("S01", slab.valueAt(0, usubjidIdx));
        assertEquals("Headache", slab.valueAt(0, aetermIdx));
    }


    @Test
    void violationWithSeqOnly_foldsIntoValues()
    {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("AETERM", "Nausea");
        Violation v = new Violation(0L, values, null, "1");

        ValidationReport report = build(v, List.of("SEQ", "AETERM"));
        ValidationFinding f = report.getMembers().getFirst().getFindings().getFirst();
        int seqIdx = f.getVariableNames().indexOf("SEQ");
        assertTrue(seqIdx >= 0);
        assertEquals("1", f.getRows().valueAt(0, seqIdx));
    }


    @Test
    void violationWithoutIdentity_usesValuesAsIs()
    {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("AETERM", "Cough");
        Violation v = new Violation(0L, values, null, null);

        ValidationReport report = build(v, List.of("AETERM"));
        ValidationFinding f = report.getMembers().getFirst().getFindings().getFirst();
        int aetermIdx = f.getVariableNames().indexOf("AETERM");
        assertEquals("Cough", f.getRows().valueAt(0, aetermIdx));
    }


    @Test
    void violationWithIdentityButValuesNull_buildsMapFromIdentity()
    {
        // Values are null but USUBJID/SEQ are set — withIdentity should build a fresh map.
        Violation v = new Violation(0L, null, "S99", "3");

        ValidationReport report = build(v, List.of("USUBJID", "SEQ"));
        assertNotNull(report);
        ValidationFinding f = report.getMembers().getFirst().getFindings().getFirst();
        int usubjidIdx = f.getVariableNames().indexOf("USUBJID");
        int seqIdx = f.getVariableNames().indexOf("SEQ");
        assertTrue(usubjidIdx >= 0);
        assertTrue(seqIdx >= 0);
        assertEquals("S99", f.getRows().valueAt(0, usubjidIdx));
        assertEquals("3", f.getRows().valueAt(0, seqIdx));
    }


    @Test
    void violationWithIdentityAlreadyInValues_usesAsIs()
    {
        // USUBJID + SEQ already in values; the optimisation returns values unchanged.
        Map<String, String> values = new LinkedHashMap<>();
        values.put("USUBJID", "S01");
        values.put("SEQ", "1");
        values.put("AETERM", "Rash");
        Violation v = new Violation(0L, values, "S01", "1");

        ValidationReport report = build(v, List.of("USUBJID", "SEQ", "AETERM"));
        ValidationFinding f = report.getMembers().getFirst().getFindings().getFirst();
        int aetermIdx = f.getVariableNames().indexOf("AETERM");
        assertEquals("Rash", f.getRows().valueAt(0, aetermIdx));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------


    private static ValidationReport build(Violation v, List<String> outputVars)
    {
        ValidationReportBuilder b = new ValidationReportBuilder();
        Rule rule = new Rule();
        rule.setId(java.util.UUID.randomUUID().toString());
        RuleCore core = new RuleCore();
        core.setId("CORE-IDENTITY");
        rule.setCore(core);
        Outcome o = new Outcome();
        o.setMessage("identity check");
        o.setOutputVariables(outputVars);
        rule.setOutcome(o);

        RuleExecutionResult result = RuleExecutionResult.builder().ruleId(rule.getId())
                .message("identity check").totalRows(1).status(RuleExecutionStatus.EXECUTED)
                .violations(List.of(v)).build();
        b.add("AE", "ae.xpt", rule, result);
        ValidationReport rep = b.build();
        assertTrue(rep.getMembers().size() >= 1);
        return rep;
    }
}
