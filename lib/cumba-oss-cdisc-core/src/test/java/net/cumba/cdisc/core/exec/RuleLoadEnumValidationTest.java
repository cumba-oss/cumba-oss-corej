package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Executability;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Phase 2 of {@code plans/PLAN-extend-expression-engine.md} — a rule whose {@code Rule_Type} /
 * {@code Sensitivity} / {@code Executability} carries a <b>present but unrecognized</b> string
 * fails at load time ({@link Rule#getLoadError()}) and executes as a rule ERROR via the existing
 * sentinel mechanism in {@link RuleRunner#execute}. An <b>absent</b> field stays legal (the
 * {@code null}-field semantics are unchanged).
 */
class RuleLoadEnumValidationTest
{

    private static final String SENSITIVITY_VALUES = "Record, Dataset, Group, Study";

    private static String ruleTypeRejection(String id, String raw)
    {
        return "[" + id + "] Rule_Type '" + raw
                + "' is no longer a rule field — the engine infers the evaluation domain from the"
                + " Check (PLAN-leaf-scope-domain-inference.md). Drop the field — if the rule"
                + " iterated the Define-XML ItemDefs (the former 'Define Item Metadata Check"
                + " against Library Metadata'), declare Variable_Universe: \"Define\" instead";
    }

    private static final String EXECUTABILITY_VALUES = "Fully Executable, Partially Executable, "
            + "Partially Executable - Possible Overreporting, "
            + "Partially Executable - Possible Underreporting, Not Executable";

    private static String packageOf(String ruleJson)
    {
        return "{\"rules\":{\"rule-1\":" + ruleJson + "}}";
    }


    private static Rule onlyRule(RulePackage pkg)
    {
        return pkg.getRules().values().iterator().next();
    }


    @Test
    void anyRuleType_isRejectedAtLoad() throws IOException
    {
        // PLAN-leaf-scope-domain-inference phase 7: Rule_Type is no longer a rule field. Any
        // value — even a formerly valid one — is a load error that names the replacement.
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-201"},
                  "Rule_Type": "Record Data",
                  "Check": {"name": "AESTDY", "operator": "non_empty"}
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertEquals(ruleTypeRejection("TEST-201", "Record Data"), rule.getLoadError());
        assertEquals("Record Data", rule.getRejectedRuleType(), "raw string kept verbatim");

        // Key presence is what is rejected: an explicit null is still the retired field.
        Rule nul = onlyRule(RulePackageLoader
                .loadFromString(packageOf(ruleJson.replace("\"Record Data\"", "null"))));
        assertEquals(ruleTypeRejection("TEST-201", "null"), nul.getLoadError());
    }


    @Test
    void invalidSensitivity_tagsLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-202"},
                  "Sensitivity": "Variable",
                  "Check": {"name": "AESTDY", "operator": "non_empty"}
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertEquals("[TEST-202] Invalid Sensitivity 'Variable' — expected one of: "
                + SENSITIVITY_VALUES, rule.getLoadError());
        assertNull(rule.getSensitivity());
        assertEquals("Variable", rule.getRawSensitivity());
    }


    @Test
    void invalidExecutability_tagsLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-203"},
                  "Executability": "Sort of Executable",
                  "Check": {"name": "AESTDY", "operator": "non_empty"}
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertEquals("[TEST-203] Invalid Executability 'Sort of Executable' — expected one of: "
                + EXECUTABILITY_VALUES, rule.getLoadError());
        assertNull(rule.getExecutability());
        assertEquals("Sort of Executable", rule.getRawExecutability());
    }


    @Test
    void invalidEnum_executesAsError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-204"},
                  "Sensitivity": "Variable",
                  "Check": {"name": "AESTDY", "operator": "non_empty"}
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertNotNull(rule.getLoadError());
        IDataTable table = MockTable.of().col("USUBJID", "S1").col("AESTDY", "1").name("ADAE")
                .build();
        RuleExecutionResult result = RuleRunner.execute(rule, table);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
        assertEquals(rule.getLoadError(), result.getStatusMessage());
        assertEquals(1, result.getViolationCount(), "exactly one sentinel violation");
        assertEquals(rule.getLoadError(),
                result.getViolations().get(0).getValues().get("__error__"));
    }


    @Test
    void multipleInvalidFields_allNamedInOneJoinedLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-205"},
                  "Rule_Type": "Bogus Type",
                  "Sensitivity": "Bogus Sensitivity",
                  "Executability": "Bogus Executability",
                  "Check": {"name": "AESTDY", "operator": "non_empty"}
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertEquals(ruleTypeRejection("TEST-205", "Bogus Type")
                + "; [TEST-205] Invalid Sensitivity 'Bogus Sensitivity' — expected one of: "
                + SENSITIVITY_VALUES
                + "; [TEST-205] Invalid Executability 'Bogus Executability' — expected one of: "
                + EXECUTABILITY_VALUES, rule.getLoadError());
    }


    @Test
    void preexistingLoadError_isPreserved_enumErrorsAppended() throws IOException
    {
        // The off-diagonal ${*}-with-equal_to operand error is set by validateOperandSubstitution
        // BEFORE the enum pass runs; the enum error must append, never clobber.
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-206"},
                  "Sensitivity": "Variable",
                  "Check": {"name": "PH${*}SDT", "operator": "equal_to", "value": "X"}
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertNotNull(rule.getLoadError());
        int operandIdx = rule.getLoadError().indexOf("PH${*}SDT");
        int enumIdx = rule.getLoadError().indexOf("Invalid Sensitivity 'Variable'");
        assertTrue(operandIdx >= 0, "operand-substitution error kept: " + rule.getLoadError());
        assertTrue(enumIdx >= 0, "enum error appended: " + rule.getLoadError());
        assertTrue(operandIdx < enumIdx, "pre-existing error first: " + rule.getLoadError());
        assertTrue(rule.getLoadError().contains("; "), "joined with `; `");
    }


    @Test
    void absentFields_stayLegal_andAreDerived() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-207"},
                  "Check": {"name": "AESTDY", "operator": "non_empty"}
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertNull(rule.getLoadError(), "absent enum fields leave loadError null");
        // PLAN-derive-rule-type-sensitivity phase 6: the corpus no longer carries
        // Sensitivity, so the loader derives it from the Check. The RAW field stays null —
        // it records what was authored, and nothing was.
        assertEquals(Sensitivity.RECORD, rule.getSensitivity(), "a per-record operand is Record");
        assertNotNull(rule.getDerivationRationale(), "the basis is recorded for reports/editor");
        // The typed setters keep the raw JSON string in sync, so the raw fields now carry the
        // derived text too. "Was this authored or derived?" is answered by derivationRationale,
        // which is populated only for fields the loader filled in.
        assertEquals("Record", rule.getRawSensitivity());
        assertTrue(rule.getDerivationRationale().containsKey("Sensitivity"));
        // Executability is NOT derivable and stays absent.
        assertNull(rule.getExecutability());
        assertNull(rule.getRawExecutability());
        IDataTable table = MockTable.of().col("USUBJID", "S1").col("AESTDY", "1").name("ADAE")
                .build();
        RuleExecutionResult result = RuleRunner.execute(rule, table);
        assertNotEquals(RuleExecutionStatus.ERROR, result.getStatus(),
                "rules without the enum fields still execute");
    }


    @Test
    void validValues_noLoadError() throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "TEST-208"},
                  "Sensitivity": "Record",
                  "Executability": "Fully Executable",
                  "Check": {"name": "AESTDY", "operator": "non_empty"}
                }
                """;
        Rule rule = onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
        assertNull(rule.getLoadError());
        assertEquals(Sensitivity.RECORD, rule.getSensitivity());
        assertEquals(Executability.FULLY_EXECUTABLE, rule.getExecutability());
        assertEquals("Record", rule.getRawSensitivity());
        assertEquals("Fully Executable", rule.getRawExecutability());
    }
}
