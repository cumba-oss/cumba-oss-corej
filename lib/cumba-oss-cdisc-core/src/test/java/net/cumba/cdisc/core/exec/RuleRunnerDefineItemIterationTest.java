package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * D1 of {@code plans/PLAN-define-item-metadata-parity-929-1081.md}, re-keyed by phase 5 of
 * {@code PLAN-leaf-scope-domain-inference.md}: a rule declaring {@code Variable_Universe: Define}
 * (the successor of the {@code Define Item Metadata Check against Library Metadata} type) iterates
 * the define.xml ItemDefs (the define provider's variables, in ItemDef order), not the dataset
 * columns. These tests prove a define-declared variable absent from the data is still checked, a
 * data column absent from the define is not, and the Dataset-sensitivity "first failing variable"
 * follows define order.
 */
class RuleRunnerDefineItemIterationTest
{

    private static final String ROLE_RULE = "{\"Core\":{\"Id\":\"R1\"},"
            + "\"Variable_Universe\":\"Define\","
            + "\"Sensitivity\":\"Dataset\",\"Check\":{\"all\":["
            + "{\"name\":\"define_variable_role\",\"operator\":\"not_equal_to\","
            + "\"value\":\"library_variable_role\"}]},"
            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
            + "[\"define_variable_role\",\"define_variable_name\",\"library_variable_role\"]}}";

    private static Rule roleRule() throws Exception
    {
        RulePackage pkg = RulePackageLoader
                .loadFromString("{\"rules\":{\"R1\":" + ROLE_RULE + "}}");
        Rule r = pkg.getRules().get("R1");
        assertEquals(null, r.getLoadError());
        return r;
    }


    private static Map<String, String> var(String name, String role)
    {
        return Map.of("name", name, "role", role);
    }


    private static RuleExecutionResult run(MetadataProvider library, MetadataProvider define,
            IDataTable table)
        throws Exception
    {
        return RuleRunner.execute(roleRule(), table, _ -> null, "DM", library, null, define);
    }


    @Test
    void defineOnlyVariable_absentFromData_isStillChecked() throws Exception
    {
        // SEX is declared in the Define-XML but is NOT a column in the dataset. Its define role
        // (Qualifier) differs from the library role (Identifier), so it must fire — the dataset
        // column loop would never have seen it.
        MetadataProvider define = new StubMetadataProvider()
                .variable("DM", var("AGE", "Identifier")).variable("DM", var("SEX", "Qualifier"));
        MetadataProvider library = new StubMetadataProvider()
                .variable("DM", var("AGE", "Identifier")).variable("DM", var("SEX", "Identifier"));
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").build();

        RuleExecutionResult r = run(library, define, dm);
        assertTrue(r.hasViolations(), "define-only SEX role mismatch must fire");
        assertEquals("SEX", r.getViolations().get(0).getValues().get("define_variable_name"));
    }


    @Test
    void dataOnlyColumn_absentFromDefine_isNotChecked() throws Exception
    {
        // ZZZ is a data column with no Define-XML ItemDef; only the define var AGE is iterated.
        MetadataProvider define = new StubMetadataProvider().variable("DM",
                var("AGE", "Qualifier"));
        MetadataProvider library = new StubMetadataProvider().variable("DM",
                var("AGE", "Identifier"));
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").col("ZZZ", "x").build();

        RuleExecutionResult r = run(library, define, dm);
        assertTrue(r.hasViolations());
        assertEquals(1, r.getViolations().size(), "only the define var AGE is evaluated");
        assertEquals("AGE", r.getViolations().get(0).getValues().get("define_variable_name"));
    }


    @Test
    void datasetSensitivity_reportsFirstFailingDefineVariable() throws Exception
    {
        // Both AAA and BBB mismatch; define order is AAA then BBB, so the single
        // Dataset-sensitivity
        // finding is AAA (the first in ItemDef order).
        MetadataProvider define = new StubMetadataProvider().variable("DM", var("AAA", "Qualifier"))
                .variable("DM", var("BBB", "Qualifier"));
        MetadataProvider library = new StubMetadataProvider()
                .variable("DM", var("AAA", "Identifier")).variable("DM", var("BBB", "Identifier"));
        IDataTable dm = MockTable.of().name("DM").col("AAA", "1").col("BBB", "2").build();

        RuleExecutionResult r = run(library, define, dm);
        assertEquals(1, r.getViolations().size(), "Dataset sensitivity emits exactly one finding");
        assertEquals("AAA", r.getViolations().get(0).getValues().get("define_variable_name"));
    }


    @Test
    void matchingRoles_noViolation() throws Exception
    {
        MetadataProvider define = new StubMetadataProvider().variable("DM",
                var("AGE", "Identifier"));
        MetadataProvider library = new StubMetadataProvider().variable("DM",
                var("AGE", "Identifier"));
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").build();

        assertFalse(run(library, define, dm).hasViolations());
    }


    @Test
    void noDefineVariables_noViolation() throws Exception
    {
        // Empty define ⇒ no rows to check ⇒ no findings (the provider is present, so not skipped).
        MetadataProvider define = new StubMetadataProvider();
        MetadataProvider library = new StubMetadataProvider().variable("DM",
                var("AGE", "Identifier"));
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").build();

        List<net.cumba.cdisc.core.exec.Violation> v = run(library, define, dm).getViolations();
        assertTrue(v.isEmpty());
    }
}
