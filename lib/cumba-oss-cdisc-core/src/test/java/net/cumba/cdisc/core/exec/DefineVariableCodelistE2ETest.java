package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.metadata.DefineXmlMetadataProvider;
import net.cumba.cdisc.core.metadata.OdmDefineXMLProvider;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.cdisc.define.ODM;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

/**
 * End-to-end tests for the {@code Value Check against Define XML Variable} rule type (EC-19,
 * SD0037), driven against a real Define-XML overlay in which the {@code DM.SEX} variable's
 * {@code ItemDef} binds a user-defined, enumerated codelist ({@code M} / {@code F}) via
 * {@code ItemDef/CodeListRef} while {@code DM.AGE} binds no codelist. A value outside the
 * variable's codelist fires; a variable with no bound codelist never fires (the
 * {@code define_variable_has_codelist} guard is false); with no Define-XML supplied the rule is
 * SKIPPED.
 */
class DefineVariableCodelistE2ETest
{

    private static MetadataProvider define;

    @BeforeAll
    static void load() throws IOException
    {
        ODM odm;
        try (InputStream in = DefineVariableCodelistE2ETest.class
                .getResourceAsStream("/define/define-varcodelist-e2e.xml"))
        {
            odm = new DefineXmlParser().parse(in);
        }
        define = new DefineXmlMetadataProvider(new OdmDefineXMLProvider(odm));
    }


    private static Rule sd0037() throws IOException
    {
        String checkJson = "{\"all\":[{\"name\":\"variable_value\",\"operator\":\"non_empty\"},"
                + "{\"name\":\"define_variable_has_codelist\",\"operator\":\"equal_to\","
                + "\"value\":true,\"value_is_literal\":true},"
                + "{\"name\":\"variable_value\",\"operator\":\"is_not_contained_by\","
                + "\"value\":\"define_variable_codelist_coded_values\"}]}";
        String json = "{\"Core\":{\"Id\":\"FDA-SD0037\"}," + ""
                + "\"Sensitivity\":\"Record\",\"Check\":" + checkJson + ","
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\",\"variable_value\"]}}";
        RulePackage pkg = RulePackageLoader
                .loadFromString("{\"rules\":{\"FDA-SD0037\":" + json + "}}");
        Rule r = pkg.getRules().get("FDA-SD0037");
        assertNull(r.getLoadError(), "rule must load without error");
        assertNotNull(r.getCheckExpr(),
                "variable-codelist rule must compile to a native checkExpr");
        return r;
    }


    private static RuleExecutionResult run(Rule rule, IDataTable dm, MetadataProvider def)
    {
        return RuleRunner.execute(rule, dm, _ -> null, "DM", null, null, def, null);
    }


    @Test
    void firesOnlyForValuesOutsideTheVariableLevelCodelist() throws IOException
    {
        // SEX has an enumerated ItemDef codelist {M,F}: r0 "M" in list -> no; r1 "X" not in list ->
        // fire; r2 "" excluded by the non_empty guard. AGE binds no codelist -> has_codelist guard
        // false -> its out-of-range values never fire.
        IDataTable dm = MockTable.of().name("DM").col("SEX", "M", "X", "")
                .col("AGE", "40", "99", "1").build();
        RuleExecutionResult r = run(sd0037(), dm, define);
        assertEquals(1, r.getViolationCount(),
                "only the SEX value outside the variable's codelist fires (r1 X)");
    }


    @Test
    void doesNotFireWhenVariableHasNoCodelist() throws IOException
    {
        // A dataset of only AGE (no bound codelist): the has_codelist guard is false for every row,
        // so no value fires regardless of content.
        IDataTable dm = MockTable.of().name("DM").col("AGE", "1", "2", "3").build();
        RuleExecutionResult r = run(sd0037(), dm, define);
        assertEquals(0, r.getViolationCount(),
                "a variable with no bound codelist never fires (has_codelist guard false)");
    }


    @Test
    void skippedWhenNoDefine() throws IOException
    {
        IDataTable dm = MockTable.of().name("DM").col("SEX", "X").col("AGE", "99").build();
        RuleExecutionResult r = run(sd0037(), dm, null);
        assertTrue(r.isSkipped(), "no Define-XML -> variable-codelist rule SKIPPED");
        assertFalse(r.hasViolations());
    }

}
