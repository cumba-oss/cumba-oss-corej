package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Outcome pins for the per-variable metadata-check finding builders —
 * {@code evaluateMetadataNative} → {@code buildVariableMetadata} / {@code buildVariableViolation}.
 * These methods decide the CONTENT of a Variable-Metadata-Check finding: which variable it names,
 * which row it carries, and which library/define/data attribute values it reports. A mutant here
 * leaves the rule firing but makes the report lie about what was found — the invisible-harm case
 * this module exists to prevent — so every test asserts the exact projected values map, and each
 * enrichment source has a negative twin (a column where that source must NOT contribute).
 */
class RuleRunnerMetadataFindingProjectionTest
{

    private static Rule load(String ruleJson) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + ruleJson + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    /** define_variable_role != library_variable_role — reads BOTH provider levels. */
    private static String roleMismatchCheck()
    {
        return "{\"all\":[{\"name\":\"define_variable_role\",\"operator\":\"not_equal_to\","
                + "\"value\":\"library_variable_role\"}]}";
    }


    private static Map<String, String> var(String name, String role, String simpleDatatype)
    {
        return Map.of("name", name, "role", role, "simpleDatatype", simpleDatatype);
    }


    /**
     * The full Output_Variables projection of one firing variable: data-level attributes
     * (name/label/type/length/format), library-level attributes (raw {@code simpleDatatype} for the
     * synthesized {@code library_variable_data_type} alias), define-level attributes (normalized
     * {@code integer} → {@code Num}), the {@code record_count} builtin, the {@code dataset_label}
     * dataset-scope virtual, and an Operation {@code $}-result. Every value is exact — a mutant
     * that drops or reroutes any of these sources ships a finding whose reported metadata is wrong
     * while the verdict stays right. RECORD sensitivity keeps the column index as the violation
     * row.
     */
    @Test
    void outputVariablesProjectEveryMetadataSourceExactly() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\","
                + "\"Operations\":[{\"id\":\"$flag\",\"operator\":\"variable_exists\","
                + "\"name\":\"STUDYID\"}]," + "\"Check\":" + roleMismatchCheck() + ","
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\","
                + "\"variable_label\",\"variable_data_type\",\"variable_length\","
                + "\"variable_format\",\"library_variable_role\",\"library_variable_data_type\","
                + "\"define_variable_role\",\"define_variable_data_type\",\"record_count\","
                + "\"dataset_label\",\"$flag\"]}}");

        MetadataProvider library = new StubMetadataProvider()
                .variable("DM", var("STUDYID", "Identifier", "text"))
                .variable("DM", var("AGE", "Identifier", "text"));
        MetadataProvider define = new StubMetadataProvider()
                .variable("DM", var("STUDYID", "Identifier", "text"))
                .variable("DM", var("AGE", "Qualifier", "integer"));
        IDataTable dm = MockTable.of().name("DM").label("Demographics").col("STUDYID", "S1", "S1")
                .colMeta("STUDYID", "Study Identifier", 8, null).col("AGE", "56", "61")
                .colMeta("AGE", "Age", 8, "8.").build();

        RuleExecutionResult r = RuleRunner.execute(rule, dm, _ -> null, "DM", library, null,
                define);

        assertEquals(1, r.getViolations().size(), "only AGE mismatches");
        Violation v = r.getViolations().get(0);
        assertEquals(1L, v.getRow(), "RECORD sensitivity keeps the column index (AGE = 1)");

        Map<String, String> expected = new LinkedHashMap<>();
        expected.put("variable_name", "AGE");
        expected.put("variable_label", "Age");
        expected.put("variable_data_type", "Char");
        expected.put("variable_length", "8");
        expected.put("variable_format", "8.");
        expected.put("library_variable_role", "Identifier");
        expected.put("library_variable_data_type", "text");
        expected.put("define_variable_role", "Qualifier");
        expected.put("define_variable_data_type", "Num");
        expected.put("record_count", "2");
        expected.put("dataset_label", "Demographics");
        expected.put("$flag", "true");
        assertEquals(expected, v.getValues());
    }


    /**
     * A firing NUMERIC column reports {@code variable_data_type = Num} — the Char/Num class comes
     * from the loaded column type, and a mutant flipping the mapping mislabels every variable in
     * every metadata finding.
     */
    @Test
    void numericColumnReportsNumDataType() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\"," + "\"Check\":"
                + roleMismatchCheck() + ","
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\","
                + "\"variable_data_type\"]}}");

        MetadataProvider library = new StubMetadataProvider().variable("DM",
                var("AGE", "Identifier", "integer"));
        MetadataProvider define = new StubMetadataProvider().variable("DM",
                var("AGE", "Qualifier", "integer"));
        IDataTable dm = MockTable.of().name("DM").colLong("AGE", 56L, 61L).build();

        RuleExecutionResult r = RuleRunner.execute(rule, dm, _ -> null, "DM", library, null,
                define);

        assertEquals(1, r.getViolations().size());
        // The two Check operands are DERIVED into the effective Output_Variables (EC-37), so the
        // finding carries them alongside the authored pair.
        assertEquals(
                Map.of("variable_name", "AGE", "variable_data_type", "Num", "define_variable_role",
                        "Qualifier", "library_variable_role", "Identifier"),
                r.getViolations().get(0).getValues());
    }


    /**
     * When every effective Output_Variables entry is excluded (E-2, the {@code !name} syntax) the
     * finding takes the metadata DEFAULT projection: variable_name + variable_label plus every
     * library_variable_* / define_variable_* attribute the providers hold for the variable, MINUS
     * the author's exclusions. The label-less column proves variable_label is gated on the label
     * actually existing — a negated gate would fabricate a null label entry (or drop a real one),
     * and a mutant skipping either provider block strips that provider's attributes from every
     * default-projected metadata finding.
     */
    @Test
    void defaultProjectionCarriesProviderAttributesAndOmitsMissingLabel() throws Exception
    {
        // The deriver adds variable_name to every VMC rule's effective list, so all THREE must be
        // excluded for the effective list to empty out and the default projection to engage.
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\"," + "\"Check\":"
                + roleMismatchCheck() + "," + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"!define_variable_role\",\"!library_variable_role\",\"!variable_name\"]}}");

        MetadataProvider library = new StubMetadataProvider()
                .variable("DM", var("AGE", "Identifier", "text"))
                .variable("DM", var("ZED", "Identifier", "text"));
        MetadataProvider define = new StubMetadataProvider()
                .variable("DM", var("AGE", "Qualifier", "integer"))
                .variable("DM", var("ZED", "Qualifier", "text"));
        IDataTable dm = MockTable.of().name("DM").col("AGE", "56").colMeta("AGE", "Age", 0, null)
                .col("ZED", "z").build();

        RuleExecutionResult r = RuleRunner.execute(rule, dm, _ -> null, "DM", library, null,
                define);

        assertEquals(2, r.getViolations().size(), "both mismatching columns fire");
        Map<String, String> age = r.getViolations().get(0).getValues();
        Map<String, String> expectedAge = new LinkedHashMap<>();
        expectedAge.put("variable_label", "Age");
        expectedAge.put("library_variable_name", "AGE");
        expectedAge.put("library_variable_simpleDatatype", "text");
        expectedAge.put("define_variable_name", "AGE");
        expectedAge.put("define_variable_simpleDatatype", "integer");
        assertEquals(expectedAge, age, "provider attributes minus the three excluded entries");

        Map<String, String> zed = r.getViolations().get(1).getValues();
        assertTrue(!zed.containsKey("variable_label"),
                "a label-less column must not carry a variable_label entry");
        assertEquals("ZED", zed.get("library_variable_name"));
        assertEquals("text", zed.get("library_variable_simpleDatatype"));
        assertEquals("text", zed.get("define_variable_simpleDatatype"));
        assertTrue(!zed.containsKey("define_variable_role"), "excluded entry stays excluded");
        assertTrue(!zed.containsKey("variable_name"), "excluded entry stays excluded");
    }


    /**
     * Sensitivity decides the violation row and the collapse: DATASET reports exactly ONE finding
     * (the first failing variable in column order) at row 0; RECORD reports one finding per failing
     * variable at its column index. Confusing the two either floods the report or hides all but one
     * defect.
     */
    @Test
    void datasetSensitivityCollapsesToFirstFailingVariableAtRowZero() throws Exception
    {
        String outcome = "\"Outcome\":{\"Message\":\"m\","
                + "\"Output_Variables\":[\"variable_name\"]}";
        Rule dataset = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Dataset\",\"Check\":"
                + roleMismatchCheck() + "," + outcome + "}");
        Rule record = load("{\"Core\":{\"Id\":\"R1\"},\"Sensitivity\":\"Record\",\"Check\":"
                + roleMismatchCheck() + "," + outcome + "}");

        MetadataProvider library = new StubMetadataProvider()
                .variable("DM", var("STUDYID", "Identifier", "text"))
                .variable("DM", var("AGE", "Identifier", "text"));
        MetadataProvider define = new StubMetadataProvider()
                .variable("DM", var("STUDYID", "Qualifier", "text"))
                .variable("DM", var("AGE", "Qualifier", "text"));
        IDataTable dm = MockTable.of().name("DM").col("STUDYID", "S1").col("AGE", "56").build();

        RuleExecutionResult ds = RuleRunner.execute(dataset, dm, _ -> null, "DM", library, null,
                define);
        assertEquals(1, ds.getViolations().size(), "DATASET sensitivity emits exactly one");
        assertEquals(0L, ds.getViolations().get(0).getRow());
        assertEquals("STUDYID", ds.getViolations().get(0).getValues().get("variable_name"),
                "the FIRST failing variable in column order");

        RuleExecutionResult rec = RuleRunner.execute(record, dm, _ -> null, "DM", library, null,
                define);
        assertEquals(2, rec.getViolations().size(), "RECORD sensitivity emits one per variable");
        assertEquals(0L, rec.getViolations().get(0).getRow());
        assertEquals(1L, rec.getViolations().get(1).getRow());
        assertEquals("AGE", rec.getViolations().get(1).getValues().get("variable_name"));
    }
}
