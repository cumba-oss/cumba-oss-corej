package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Review F2 (PLAN-extend-expression-engine) — load-time validation for the
 * {@code ds_exists}/{@code ds_not_exists} operand: the operators take a PLAIN dataset name only.
 * The dotted / filter / {@code ${...}} / {@code --}-prefix forms are rejected by the native
 * compiler, but a leaf authored directly in legacy JSON used to reach the runtime unguarded —
 * {@code evalDsExists} silently resolver-missed and {@code ds_not_exists} with a {@code ${...}}
 * name silently fired every row. The loader now tags such rules with a {@code loadError} so they
 * execute as ERROR.
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
                  "Check": {"name": "%s", "operator": "%s"}
                }
                """.formatted(coreId, name, operator);
        return onlyRule(RulePackageLoader.loadFromString(packageOf(ruleJson)));
    }


    @Test
    void dsExists_dottedName_tagsLoadError_andExecutesAsError() throws IOException
    {
        Rule rule = load("TEST-401", "ds_exists", "AE.AESTDY");
        assertNotNull(rule.getLoadError(), "dotted name must tag a loadError");
        assertEquals("[TEST-401] operator 'ds_exists' expects a plain dataset name,"
                + " found 'AE.AESTDY'", rule.getLoadError());

        IDataTable table = MockTable.of().name("ADAE").col("USUBJID", "S1").build();
        RuleExecutionResult result = RuleRunner.execute(rule, table);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
        assertEquals(rule.getLoadError(), result.getStatusMessage());
        assertEquals(1, result.getViolationCount(), "exactly one sentinel violation");
        assertEquals(rule.getLoadError(),
                result.getViolations().get(0).getValues().get("__error__"));
    }


    @Test
    void dsNotExists_placeholderName_tagsLoadError() throws IOException
    {
        Rule rule = load("TEST-402", "ds_not_exists", "AP${APERIOD}SDT");
        assertNotNull(rule.getLoadError(),
                "${...} name must tag a loadError (used to silently fire every row)");
        assertTrue(rule.getLoadError().contains("expects a plain dataset name"),
                rule.getLoadError());
        assertTrue(rule.getLoadError().contains("AP${APERIOD}SDT"), rule.getLoadError());
    }


    @Test
    void dsExists_filterAndPrefixForms_tagLoadError() throws IOException
    {
        Rule filter = load("TEST-403", "ds_exists", "DS.DSDECOD=DEATH");
        assertNotNull(filter.getLoadError(), "filter form must tag a loadError");
        assertTrue(filter.getLoadError().contains("expects a plain dataset name"),
                filter.getLoadError());

        Rule prefix = load("TEST-404", "ds_not_exists", "--DM");
        assertNotNull(prefix.getLoadError(), "--prefix form must tag a loadError");
        assertTrue(prefix.getLoadError().contains("expects a plain dataset name"),
                prefix.getLoadError());
    }


    @Test
    void dsExists_plainDatasetName_loadsClean() throws IOException
    {
        Rule rule = load("TEST-405", "ds_exists", "DM");
        assertNull(rule.getLoadError(), "plain dataset name must load cleanly");

        Rule negated = load("TEST-406", "ds_not_exists", "EX");
        assertNull(negated.getLoadError(), "plain dataset name on the negated twin too");
    }


    @Test
    void legacyExistsFamily_keepsPlaceholderAndDottedSurface() throws IOException
    {
        // The guard is ds_* specific: exists / var_exists keep their dotted and ${...} forms.
        assertNull(load("TEST-407", "var_exists", "AE.AESTDY").getLoadError());
        assertNull(load("TEST-408", "var_exists", "AP${APERIOD}SDT").getLoadError());
        assertNull(load("TEST-409", "var_not_exists", "--SEQ").getLoadError());
    }
}
