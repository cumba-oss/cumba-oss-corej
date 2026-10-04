package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Phase 3 of {@code plans/done/PLAN-auto-output-variables.md} (EC-37): the finding projection
 * resolves dataset-scope virtual variables from the evaluation context, the row identity falls back
 * to the ADaM {@code ASEQ} (D5b), and an effective entry that resolves to nothing — derived or
 * authored alike since {@code PLAN-output-variable-null-key-provenance} — carries its typed default
 * ({@code ""} for a char entry), never a null-valued key and never a provenance-dependent omission.
 */
class RuleRunnerProjectionCompletenessTest
{

    // ------------------------------------------------------------- D5b / ASEQ

    @Test
    void adamSequenceFallsBackToAseq()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1-001").col("ASEQ", "7")
                .col("AVAL", "1.0").build();

        RuleRunner.RowIdentity ri = RuleRunner.readRowIdentity(table, "ADAE", 0);

        assertEquals("S1-001", ri.usubjid());
        assertEquals("7", ri.seq());
    }


    @Test
    void domainSeqIsPreferredOverAseq()
    {
        IDataTable table = MockTable.of().col("USUBJID", "S1-001").col("AESEQ", "3")
                .col("ASEQ", "9").build();

        RuleRunner.RowIdentity ri = RuleRunner.readRowIdentity(table, "AE", 0);

        assertEquals("3", ri.seq());
    }


    @Test
    void aseqFallbackAppliesWithNullDomainToo()
    {
        // Domain-less call: the literal "SEQ" name is skipped, then ASEQ resolves.
        IDataTable table = MockTable.of().col("USUBJID", "S1-001").col("ASEQ", "2").build();

        RuleRunner.RowIdentity ri = RuleRunner.readRowIdentity(table, null, 0);

        assertEquals("2", ri.seq());
    }

    // ------------------------------------------------------------- dataset-scope virtuals


    @Test
    void datasetScopeVirtualsResolveFromContext()
    {
        IDataTable table = MockTable.of().name("AE").label("Adverse Events")
                .col("AETERM", "Headache", "Cough").build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();

        Map<String, String> values = RuleRunner.extractOutputValues(table, ctx,
                List.of("dataset_name", "dataset_label", "record_count", "AETERM"), 0);

        assertEquals("AE", values.get("dataset_name"));
        assertEquals("Adverse Events", values.get("dataset_label"));
        assertEquals("2", values.get("record_count"));
        assertEquals("Headache", values.get("AETERM"));
    }


    @Test
    void unresolvableDatasetScopeNameIsOmittedAndAResolvableOneCarriesItsDefault()
    {
        // dataset_structure exists only at the DEFINE/LIBRARY levels; with no providers the read
        // resolves to nothing and the key carries the char default "" (never null;
        // PLAN-output-variable-null-key-provenance §2.1 case 3 — it was omitted before). A name
        // that is no dataset-scope attribute at all is unresolvable and omitted.
        IDataTable table = MockTable.of().name("AE").col("AETERM", "Headache").build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();

        Map<String, String> values = RuleRunner.extractOutputValues(table, ctx,
                List.of("define_dataset_structure", "dataset_bogus"), 0);

        assertEquals("", values.get("define_dataset_structure"), values.toString());
        assertFalse(values.containsKey("dataset_bogus"));
    }

    // ------------------------------------------------------------- typed default (§2.1)


    private static Rule loadVmcRule(String... outputVars) throws Exception
    {
        StringBuilder ov = new StringBuilder();
        for (int i = 0; i < outputVars.length; i++)
        {
            ov.append(i == 0 ? "" : ",").append('"').append(outputVars[i]).append('"');
        }
        String json = """
                {"rules":{"R1":{
                  "Core":{"Id":"R1"},
                  "Sensitivity":"Record",
                  "Check":{"all":[
                    {"expression": "ends_with(varname(), \\"AETERM\\")"},
                    {"expression": "empty(var_format(\\"DATA\\"))"}
                  ]},
                  "Outcome":{"Message":"m","Output_Variables":[%s]}
                }}}""".formatted(ov);
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    @Test
    void derivedEntryResolvingToNothingCarriesItsTypedDefault() throws Exception
    {
        // variable_format is DERIVED (the Check reads it); the column declares no format, so the
        // derived key carries the char default "" — PLAN-output-variable-null-key-provenance §2.1
        // (it was omitted before: EC-37 phase 3.3's omit-don't-null applied to derived entries).
        Rule rule = loadVmcRule("variable_name");
        assertTrue(rule.getEffectiveOutputVariables().contains("variable_format"),
                "precondition: variable_format must be a derived effective entry");
        assertTrue(rule.getCheckExpr() != null, "precondition: rule must raise natively");
        IDataTable table = MockTable.of().name("AE").col("AETERM", "X", "Y").build();

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);

        assertEquals(1, result.getViolationCount(),
                "status=" + result.getStatus() + " violations=" + result.getViolations());
        Map<String, String> values = result.getViolations().get(0).getValues();
        assertEquals("AETERM", values.get("variable_name"));
        assertEquals("", values.get("variable_format"),
                "a derived char entry resolving to nothing carries \"\": " + values);
    }


    @Test
    void authoredEntryResolvingToNothingCarriesTheSameTypedDefault() throws Exception
    {
        // The same rule with variable_format AUTHORED projects identically — one policy, no
        // provenance split (D1); the historical null-valued key is gone.
        Rule rule = loadVmcRule("variable_name", "variable_format");
        IDataTable table = MockTable.of().name("AE").col("AETERM", "X", "Y").build();

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);

        assertEquals(1, result.getViolationCount(),
                "status=" + result.getStatus() + " violations=" + result.getViolations());
        Map<String, String> values = result.getViolations().get(0).getValues();
        assertEquals("", values.get("variable_format"),
                "an authored char entry resolving to nothing carries \"\", never null: " + values);
    }
}
