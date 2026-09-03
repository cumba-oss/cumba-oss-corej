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
 * to the ADaM {@code ASEQ} (D5b), and a <em>derived</em> effective entry that resolves to nothing
 * is omitted rather than inserted as a null-valued key (authored entries keep the historical null).
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
    void unresolvableDatasetScopeNameIsOmitted()
    {
        // dataset_structure exists only at the DEFINE/LIBRARY levels; with no providers the
        // read resolves to nothing and the key is omitted (never a null-valued entry).
        IDataTable table = MockTable.of().name("AE").col("AETERM", "Headache").build();
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();

        Map<String, String> values = RuleRunner.extractOutputValues(table, ctx,
                List.of("define_dataset_structure", "dataset_bogus"), 0);

        assertFalse(values.containsKey("define_dataset_structure"));
        assertFalse(values.containsKey("dataset_bogus"));
    }

    // ------------------------------------------------------------- omit-don't-null (3.3)


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
                    {"name":"variable_name","operator":"matches_regex","value":"^AETERM$"},
                    {"name":"variable_format","operator":"empty"}
                  ]},
                  "Outcome":{"Message":"m","Output_Variables":[%s]}
                }}}""".formatted(ov);
        RulePackage pkg = RulePackageLoader.loadFromString(json);
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    @Test
    void derivedEntryResolvingToNothingIsOmitted() throws Exception
    {
        // variable_format is DERIVED (the Check reads it); the column declares no format, so
        // the derived key is dropped from the finding instead of landing as a null value.
        Rule rule = loadVmcRule("variable_name");
        assertTrue(rule.getEffectiveOutputVariables().contains("variable_format"),
                "precondition: variable_format must be a derived effective entry");
        assertTrue(rule.getCheckExpr() != null, "precondition: rule must raise natively");
        IDataTable table = MockTable.of().name("AE").col("AETERM", "X", "Y").build();

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        assertEquals(1, result.getViolationCount(),
                "status=" + result.getStatus() + " violations=" + result.getViolations());
        Map<String, String> values = result.getViolations().get(0).getValues();
        assertEquals("AETERM", values.get("variable_name"));
        assertFalse(values.containsKey("variable_format"),
                "derived null-resolving entry must be omitted");
    }


    @Test
    void authoredEntryResolvingToNothingKeepsTheNullKey() throws Exception
    {
        // The same rule with variable_format AUTHORED keeps today's null-valued key, so no
        // existing fixture flips (Phase 3.3 applies to derived entries only).
        Rule rule = loadVmcRule("variable_name", "variable_format");
        IDataTable table = MockTable.of().name("AE").col("AETERM", "X", "Y").build();

        RuleExecutionResult result = RuleRunner.execute(rule, table);

        assertEquals(1, result.getViolationCount(),
                "status=" + result.getStatus() + " violations=" + result.getViolations());
        Map<String, String> values = result.getViolations().get(0).getValues();
        assertTrue(values.containsKey("variable_format"),
                "authored entry keeps the historical null-valued key");
        assertNull(values.get("variable_format"));
    }
}
