package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.MetadataProvider.PublishedVariable;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * End-to-end pins for the R11 carry-over wiring inside {@code evaluateMetadataNative}: the
 * {@code library_variable_label_values} / {@code library_variable_data_type_values} operands must
 * reach the EVALUATION context before the per-column verdict is taken (not merely the finding),
 * must be published only for variables the run's own library does NOT define, and must never cost a
 * companion lookup on rules that do not name a carry-over operand. The rule shape mirrors
 * {@code DRAFT-900044} (multi-level ERROR/INFO), so these tests also pin the per-level claiming of
 * per-variable findings — the level each finding carries is part of what the report says.
 */
class RuleRunnerCarryOverWiringTest
{

    /** The DRAFT-900044 shape: ERROR = label not among the published ones; INFO = ambiguous. */
    private static final String CARRY_OVER_RULE = """
            {"Core":{"Id":"R1"},"Sensitivity":"Record",
             "Check":{
               "ERROR":{"expression":"not empty(library_variable_label_values) and \
            not empty(var_label(\\"DATA\\")) and \
            upper(normalize_space(var_label(\\"DATA\\"))) not in library_variable_label_values"},
               "INFO":{"expression":"count(library_variable_label_values) > 1 and \
            not empty(var_label(\\"DATA\\")) and \
            upper(normalize_space(var_label(\\"DATA\\"))) in library_variable_label_values",
                       "Message":"ambiguous label"}},
             "Outcome":{"Message":"label mismatch","Output_Variables":
               ["variable_name","variable_label","library_variable_label_values"]}}""";

    private static Rule load(String ruleJson) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + ruleJson + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    private static RuleExecutionResult run(Rule rule, IDataTable table, MetadataProvider library)
    {
        // INFO threshold so the weaker rung is evaluated at all (the default WARNING excludes it).
        return RuleRunner.execute(rule, table, _ -> null, "ADSL", library, null, null,
                Integer.MAX_VALUE, null, null, null, Set.of(), Set.of(), Severity.INFO);
    }


    /**
     * ⭐ The defect R11/Phase 6 fixed: the candidates must be in the map the VERDICT reads. A
     * variable the run's own library does not define, whose label is not among the companion's
     * published labels, fires the ERROR rung; a variable whose label matches one of SEVERAL
     * published labels fires the INFO rung; a variable the library DOES define is not carried over
     * and fires nothing. The findings' values (incl. the normalised candidate list) and their
     * claimed levels are asserted exactly — remove the injection, negate its gate, or drop the
     * finding-side copy and one of these assertions goes red.
     */
    @Test
    void carriedOverOperandsDecideTheVerdictAndReachTheFinding() throws Exception
    {
        Rule rule = load(CARRY_OVER_RULE);

        MetadataProvider library = mock(MetadataProvider.class);
        when(library.getVariableMetadata(anyString(), anyString())).thenReturn(Map.of());
        when(library.getVariableMetadata("ADSL", "STUDYID"))
                .thenReturn(Map.of("name", "STUDYID", "label", "Study Identifier"));
        when(library.getPublishedVariablesByName(anyString())).thenReturn(List.of());
        when(library.getPublishedVariablesByName("RFSTDTC")).thenReturn(
                List.of(new PublishedVariable("DM", "Subject Reference Start Date/Time", "Char")));
        when(library.getPublishedVariablesByName("SREL"))
                .thenReturn(List.of(new PublishedVariable("RELREC", "Related Subject", "Char"),
                        new PublishedVariable("APRELSUB", "Relationship of Subject", "Char")));

        IDataTable adsl = MockTable.of().name("ADSL").col("RFSTDTC", "2024-01-01")
                .colMeta("RFSTDTC", "Totally Different", 0, null).col("SREL", "x")
                .colMeta("SREL", "Related Subject", 0, null).col("STUDYID", "S1")
                .colMeta("STUDYID", "Wrong Label On Purpose", 0, null).build();

        RuleExecutionResult r = run(rule, adsl, library);

        assertFalse(r.isSkipped(), () -> String.valueOf(r.getStatusMessage()));
        assertEquals(2, r.getViolations().size(),
                "exactly the carried-over mismatch and the ambiguous match fire");

        Violation error = r.getViolations().get(0);
        assertEquals(Severity.ERROR, error.getLevel());
        assertEquals(
                Map.of("variable_name", "RFSTDTC", "variable_label", "Totally Different",
                        "library_variable_label_values", "[SUBJECT REFERENCE START DATE/TIME]"),
                error.getValues());

        Violation info = r.getViolations().get(1);
        assertEquals(Severity.INFO, info.getLevel());
        assertEquals(Map.of("variable_name", "SREL", "variable_label", "Related Subject",
                "library_variable_label_values", "[RELATED SUBJECT, RELATIONSHIP OF SUBJECT]"),
                info.getValues());
    }


    /**
     * A variable with NO companion publication produces no operands, so the rule has nothing to say
     * — EXECUTED with zero findings, never a fabricated candidate list.
     */
    @Test
    void noPublishedCandidatesMeansNoFindings() throws Exception
    {
        Rule rule = load(CARRY_OVER_RULE);
        MetadataProvider library = mock(MetadataProvider.class);
        when(library.getVariableMetadata(anyString(), anyString())).thenReturn(Map.of());
        when(library.getPublishedVariablesByName(anyString())).thenReturn(List.of());

        IDataTable adsl = MockTable.of().name("ADSL").col("XYZ", "1")
                .colMeta("XYZ", "Some Label", 0, null).build();

        RuleExecutionResult r = run(rule, adsl, library);
        assertFalse(r.isSkipped());
        assertTrue(r.getViolations().isEmpty());
    }


    /**
     * The hot-path contract: a metadata rule that does NOT name a carry-over operand must never pay
     * the per-column companion lookup ({@code getPublishedVariablesByName}). A mutant forcing
     * {@code referencesCarryOverOperand} to true silently adds a library round-trip per column to
     * every metadata rule in the corpus.
     */
    @Test
    void nonCarryOverMetadataRuleNeverAsksForPublishedVariables() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\","
                + "\"Check\":{\"all\":[{\"name\":\"library_variable_role\","
                + "\"operator\":\"equal_to\",\"value\":\"Identifier\","
                + "\"value_is_literal\":true}]},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"variable_name\",\"library_variable_role\"]}}");

        MetadataProvider library = mock(MetadataProvider.class);
        when(library.getVariableMetadata(anyString(), anyString())).thenReturn(Map.of());
        when(library.getVariableMetadata("ADSL", "AGE"))
                .thenReturn(Map.of("name", "AGE", "role", "Identifier"));

        IDataTable adsl = MockTable.of().name("ADSL").col("AGE", "56").col("ZZZ", "1").build();

        RuleExecutionResult r = RuleRunner.execute(rule, adsl, _ -> null, "ADSL", library, null,
                null);

        assertEquals(1, r.getViolations().size(), "the library-defined Identifier column fires");
        assertEquals("AGE", r.getViolations().get(0).getValues().get("variable_name"));
        assertEquals("Identifier",
                r.getViolations().get(0).getValues().get("library_variable_role"));
        verify(library, never()).getPublishedVariablesByName(anyString());
    }
}
