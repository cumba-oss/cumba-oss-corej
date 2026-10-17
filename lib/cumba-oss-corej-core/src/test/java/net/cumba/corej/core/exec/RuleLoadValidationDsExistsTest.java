package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Review F2 (PLAN-extend-expression-engine) — the {@code ds_exists}/{@code ds_not_exists} operand
 * takes a PLAIN dataset name only. The dotted / filter / {@code ${...}} / {@code --}-prefix forms
 * are rejected by the native compiler, so the rule never acquires a {@code checkExpr}; since PACK I
 * E6 the compiler's refusal is the rule's LOAD error, which {@code RuleRunner} reports through its
 * load-error sentinel. Phase 7d (D121): the legacy-JSON leaf surface this guard once covered at
 * load is retired — the leaf form itself no longer binds — so the compiler rejection, surfaced as
 * that load error, is the whole contract.
 */
class RuleLoadValidationDsExistsTest
{

    private static String packageOf(String ruleJson)
    {
        return "{\"rules\":{\"rule-1\":" + ruleJson + "}}";
    }


    private static Rule onlyRule(RulePackage pkg)
    {
        return pkg.getRules().values().iterator().next();
    }


    private static Rule load(String coreId, String operator, String name) throws IOException
    {
        String ruleJson = """
                {
                  "Core": {"Id": "%s"},
                  "Sensitivity": "Record",
                  "Check": {"expression": "%s(\\"%s\\")"}
                }
                """.formatted(coreId, operator, name);
        return onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
    }


    @Test
    void dsExists_dottedName_hasNoNativeForm_andExecutesAsError() throws IOException
    {
        Rule rule = load("TEST-401", "ds_exists", "AE.AESTDY");
        assertNull(rule.getCheckExpr(),
                "a dotted dataset name must be refused by the compiler (no native form)");
        // PACK I E6 (PI-Q5 ⓐ): the compiler's refusal of a Check is the rule's load error — it no
        // longer loads clean to ERROR at every run
        assertNotNull(rule.getLoadError(), "the refusal is a load error");
        assertTrue(rule.getLoadError().contains("no native expression form"), rule.getLoadError());

        // three records: the sentinel is one per executed dataset, never one per record
        IDataTable table = MockTable.of().name("ADAE").col("USUBJID", "S1", "S2", "S3").build();
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
        assertNotNull(result.getStatusMessage());
        assertTrue(result.getStatusMessage().contains("no native expression form"),
                result.getStatusMessage());
        // since PACK I E6 the ERROR is the load error's (the loader refused the Check), and the
        // runner reports it as it reports every load error: the status message IS the error, and
        // ONE sentinel violation (row 0, `__error__` = the error) per executed dataset
        assertEquals(rule.getLoadError(), result.getStatusMessage());
        assertEquals(1, result.getViolationCount(), "one sentinel, not a finding per row");
        assertEquals(0L, result.getViolations().getFirst().getRow());
        assertEquals(java.util.Map.of("__error__", rule.getLoadError()),
                result.getViolations().getFirst().getValues());
    }


    @Test
    void dsNotExists_placeholderName_hasNoNativeForm() throws IOException
    {
        Rule rule = load("TEST-402", "ds_not_exists", "AP${APERIOD}SDT");
        assertNull(rule.getCheckExpr(),
                "${...} name must be refused (used to silently fire every row)");
    }


    @Test
    void dsExists_filterAndPrefixForms_haveNoNativeForm() throws IOException
    {
        assertNull(load("TEST-403", "ds_exists", "DS.DSDECOD=DEATH").getCheckExpr(),
                "filter form must be refused");
        assertNull(load("TEST-404", "ds_not_exists", "--DM").getCheckExpr(),
                "--prefix form must be refused");
    }


    @Test
    void dsExists_plainDatasetName_loadsClean() throws IOException
    {
        Rule rule = load("TEST-405", "ds_exists", "DM");
        assertNull(rule.getLoadError(), "plain dataset name must load cleanly");
        assertNotNull(rule.getCheckExpr());

        Rule negated = load("TEST-406", "ds_not_exists", "EX");
        assertNull(negated.getLoadError(), "plain dataset name on the negated twin too");
        assertNotNull(negated.getCheckExpr());
    }


    @Test
    void varExistsFamily_keepsPlaceholderAndDottedSurface() throws IOException
    {
        // The guard is ds_* specific: var_exists keeps its dotted and ${...} forms.
        assertNotNull(load("TEST-407", "var_exists", "AE.AESTDY").getCheckExpr());
        assertNotNull(load("TEST-408", "var_exists", "AP${APERIOD}SDT").getCheckExpr());
        assertNotNull(load("TEST-409", "var_not_exists", "--SEQ").getCheckExpr());
    }
}
