package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Fix #64 regression: a {@code varname() not in $allowed_variables} rule must resolve
 * {@code $allowed_variables} as a list (not the literal token) so a variable IN the allowed list
 * does not fire. The rule built below is the shape {@code FDA-SD0058} ships (and its equivalence
 * classmates {@code CDISC-CG0013} / {@code CDISC-CG0351} / {@code PMDA-SD0058}):
 * {@code $allowed_variables = get_model_column_order()}, checked with
 * {@code varname() not in $allowed_variables}. Because it is a {@code Sensitivity.DATASET} Variable
 * Metadata Check, the engine reports a single dataset-level violation — the FIRST variable not in
 * the allowed list — and zero violations when every variable is allowed. The same single-violation
 * shape is pinned as a corpus drift guard by {@code rulespec/specs/FDA-SD0058.yaml} in the rules
 * repository, whose one {@code expected_violations} entry reports {@code variable_name: AEFOO}.
 *
 * <p>
 * Pre-Fix-#64, the per-variable {@code partialEvaluateVariable} fold called
 * {@code evaluateLeafAgainstMetadata} with a {@code varMeta} map that only carried
 * {@code VariableMetadataResult} entries from {@code ctx.getVariables()}. Plain
 * {@code List<String>} Operation results (like {@code $allowed_variables} from
 * {@code get_model_column_order}) were not copied through, so the metadata-leaf evaluator's
 * {@code metadata.containsKey(targetStr)} check missed them. {@code targetStr} stayed as the
 * literal {@code "$allowed_variables"} text and the operator's substring fallback
 * {@code !targetStr.contains(metaStr)} returned true for every column — one violation per column,
 * regardless of whether the column was in the allowed list.
 */
class VariableMetadataOperandListResolutionTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }


    private static Rule allowedVariablesRule()
    {
        // Operations: $allowed_variables = get_model_column_order
        Operation op = new Operation();
        op.setId("$allowed_variables");
        op.setOperator("get_model_column_order");

        // Check: { all: [{ name: "variable_name", op: "is_not_contained_by",
        // value: "$allowed_variables" }] }
        net.cumba.corej.core.model.CheckConditionExpression leaf = expr(
                "varname() not in $allowed_variables");

        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("FDA-SD0058");
        rule.setCore(core);
        rule.setOperations(List.of(op));
        rule.setCheck(new CheckConditionAll(List.of(leaf)));
        rule.setSensitivity(Sensitivity.DATASET);
        Outcome outcome = new Outcome();
        outcome.setMessage("Variables not listed in the Model List of Allowed Variables for "
                + "Observation Class should be in SUPPQUAL.");
        outcome.setOutputVariables(List.of("variable_name", "$allowed_variables"));
        rule.setOutcome(outcome);
        net.cumba.corej.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    private static MetadataProvider providerWithAllowed(List<String> aAllowed)
    {
        // Minimal MetadataProvider that only overrides the methods the rule actually calls:
        // getStandardModelVariables (drives $allowed_variables) + getStandard / getVersion (engine
        // metadata reads). Everything else stays at the interface defaults (mostly empty lists /
        // nulls), which are fine because the rule's Check leaf is the only consumer.
        return new MetadataProvider()
        {

            @Override
            public List<String> getStandardModelVariables(IDataTable aTable,
                    DatasetResolver aResolver)
            {
                return aAllowed;
            }


            @Override
            public String getStandard()
            {
                return "sdtmig";
            }


            @Override
            public String getVersion()
            {
                return "3-4";
            }


            @Override
            public List<String> getRequiredVariables(String d)
            {
                return List.of();
            }


            @Override
            public List<String> getExpectedVariables(String d)
            {
                return List.of();
            }


            @Override
            public List<String> getColumnOrder(String d)
            {
                return List.of();
            }


            @Override
            public List<String> getModelColumnOrder(String d)
            {
                return List.of();
            }


            @Override
            public boolean isDomainCustom(String d)
            {
                return false;
            }


            @Override
            public Map<String, String> getDatasetMetadata(String d)
            {
                return Map.of();
            }


            @Override
            public Map<String, String> getVariableMetadata(String d, String v)
            {
                return Map.of();
            }


            @Override
            public List<Map<String, String>> getDomainVariables(String d)
            {
                return List.of();
            }


            @Override
            public List<Map<String, String>> getModelVariables(String d)
            {
                return List.of();
            }


            @Override
            public List<String> getPublishedCtPackages()
            {
                return List.of();
            }


            @Override
            public List<String> getCodelistTerms(String c)
            {
                return List.of();
            }


            @Override
            public java.util.Optional<Boolean> isCodelistExtensible(String c)
            {
                return java.util.Optional.of(false);
            }


            @Override
            public Map<String, String> getCodelistTermMappings(String c)
            {
                return Map.of();
            }
        };
    }


    @Test
    void allColumnsAllowed_zeroViolations()
    {
        // Synthetic AE table; all four columns are in the allowed list.
        IDataTable table = MockTable.of().col("STUDYID", "S001").col("USUBJID", "U001")
                .col("AESEQ", "1").col("AETERM", "Headache").build();

        List<String> allowed = List.of("STUDYID", "USUBJID", "AESEQ", "AETERM", "AESTDTC",
                "AEENDTC");
        MetadataProvider provider = providerWithAllowed(allowed);

        RuleExecutionResult result = RuleRunner.execute(allowedVariablesRule(), table, _ -> null,
                "AE", provider);

        assertEquals("FDA-SD0058", result.getRuleId());
        assertEquals(0, result.getViolationCount(),
                "all columns in allowed list → no violations; pre-Fix-#64 fired 4 spurious "
                        + "violations because $allowed_variables was treated as a literal string");
    }


    @Test
    void mixedColumns_onlyDisallowedVariablesFire()
    {
        // Two columns are in the list (STUDYID, USUBJID); two are not (AECUSTOM, AEEXTRA).
        // A Sensitivity.DATASET Variable Metadata Check collapses to exactly ONE violation —
        // the first failing variable in column order — which is what
        // rulespec/specs/FDA-SD0058.yaml pins as a drift guard over the shipped rule. The first
        // disallowed column in iteration order is AECUSTOM.
        IDataTable table = MockTable.of().col("STUDYID", "S001").col("USUBJID", "U001")
                .col("AECUSTOM", "X").col("AEEXTRA", "Y").build();

        List<String> allowed = List.of("STUDYID", "USUBJID", "AESEQ", "AETERM");
        MetadataProvider provider = providerWithAllowed(allowed);

        RuleExecutionResult result = RuleRunner.execute(allowedVariablesRule(), table, _ -> null,
                "AE", provider);

        assertEquals(1, result.getViolationCount(),
                "dataset-sensitivity collapse: only the first disallowed variable fires");
        Map<String, Object> v0 = new LinkedHashMap<>(result.getViolations().get(0).getValues());
        // variable_name carries the offending column name (first disallowed in column order).
        assertEquals("AECUSTOM", v0.get("variable_name"));
    }


    @Test
    void noColumnsAllowed_everyColumnFires()
    {
        // Allowed list is non-empty (so the resolver doesn't yield LIBRARY_NOT_AVAILABLE) but
        // disjoint from the dataset's columns. Every column is unexpected, but the
        // Sensitivity.DATASET collapse reports only the first (STUDYID) — mirroring Python's
        // single-error-per-dataset contract.
        IDataTable table = MockTable.of().col("STUDYID", "S001").col("USUBJID", "U001").build();

        List<String> allowed = List.of("OTHER_VAR");
        MetadataProvider provider = providerWithAllowed(allowed);

        RuleExecutionResult result = RuleRunner.execute(allowedVariablesRule(), table, _ -> null,
                "AE", provider);

        assertEquals(1, result.getViolationCount());
        assertEquals("STUDYID", result.getViolations().get(0).getValues().get("variable_name"));
    }


    @Test
    void emptyAllowedList_skipsRule()
    {
        // When get_model_column_order returns an empty list, OperationExecutor maps it to
        // LIBRARY_NOT_AVAILABLE so RuleRunner Phase 2a.1 reports the rule SKIPPED. This is
        // existing Fix #42 Phase 1 behaviour — the test pins it down so a future change to the
        // SKIP-on-empty contract has to update both this test and the operator dispatch.
        IDataTable table = MockTable.of().col("STUDYID", "S001").build();
        MetadataProvider provider = providerWithAllowed(List.of());

        RuleExecutionResult result = RuleRunner.execute(allowedVariablesRule(), table, _ -> null,
                "AE", provider);

        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus());
        assertTrue(result.getViolations().isEmpty());
    }
}
