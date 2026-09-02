package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Outcome pins for the Define-ItemDef finding builder ({@code buildDefineVariableViolation} and the
 * define-universe loop of {@code evaluateMetadataNative}). A {@code Variable_Universe:
 * Define} rule iterates the define.xml ItemDefs, and its finding is keyed on the DEFINE INDEX and
 * sourced from the define provider left-joined to the library — so a mutant here changes which
 * ItemDef a finding points at (the row), or which define/library attributes the report shows, while
 * the rule still fires. Every test asserts exact rows and exact values maps.
 */
class RuleRunnerDefineItemFindingTest
{

    private static final String CHECK = "{\"all\":[{\"name\":\"define_variable_role\","
            + "\"operator\":\"not_equal_to\",\"value\":\"library_variable_role\"}]}";

    private static Rule load(String ruleJson) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + ruleJson + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    /** Define provider: a name-less ItemDef first, then AAA (mismatch), BBB (match), CCC. */
    private static MetadataProvider define()
    {
        return new StubMetadataProvider().variable("DM", Map.of("role", "Orphan"))
                .variable("DM",
                        Map.of("name", "AAA", "role", "Qualifier", "label", "Aaa Label",
                                "codelist_coded_codes", "[\"C2\",\"C1\"]"))
                .variable("DM", Map.of("name", "BBB", "role", "Identifier"))
                .variable("DM", Map.of("name", "CCC", "role", "Qualifier"));
    }


    private static MetadataProvider library()
    {
        return new StubMetadataProvider()
                .variable("DM", Map.of("name", "AAA", "role", "Identifier"))
                .variable("DM", Map.of("name", "BBB", "role", "Identifier"))
                .variable("DM", Map.of("name", "CCC", "role", "Identifier"));
    }


    private static IDataTable dm()
    {
        return MockTable.of().name("DM").col("AAA", "1").col("STUDYID", "S1").build();
    }


    /**
     * RECORD sensitivity keys each finding on the ItemDef's DEFINE INDEX — including the index
     * consumed by a name-less ItemDef, which is skipped but still counted. The projected values
     * come from the define row (with the {@code codelist_coded_codes} list re-encoded SORTED into
     * the canonical bracket form), the library row, the {@code record_count} builtin and an
     * Operation {@code $}-result. An increment mutant shifts every finding onto the wrong ItemDef;
     * an unsorted codelist output diverges from the reference engine's stringification.
     */
    @Test
    void recordSensitivityKeysFindingsOnDefineIndexWithExactValues() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Variable_Universe\":\"Define\","
                + "\"Sensitivity\":\"Record\","
                + "\"Operations\":[{\"id\":\"$flag\",\"operator\":\"variable_exists\","
                + "\"name\":\"STUDYID\"}]," + "\"Check\":" + CHECK + ","
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\","
                + "\"define_variable_role\",\"library_variable_role\","
                + "\"define_variable_codelist_coded_codes\",\"record_count\",\"$flag\"]}}");

        RuleExecutionResult r = RuleRunner.execute(rule, dm(), _ -> null, "DM", library(), null,
                define());

        assertEquals(2, r.getViolations().size(), "AAA and CCC mismatch; BBB matches");

        Violation aaa = r.getViolations().get(0);
        assertEquals(1L, aaa.getRow(),
                "AAA is ItemDef index 1 — the name-less ItemDef consumed index 0");
        Map<String, String> expectedAaa = new LinkedHashMap<>();
        expectedAaa.put("variable_name", "AAA");
        expectedAaa.put("define_variable_role", "Qualifier");
        expectedAaa.put("library_variable_role", "Identifier");
        expectedAaa.put("define_variable_codelist_coded_codes", "[C1, C2]");
        expectedAaa.put("record_count", "1");
        expectedAaa.put("$flag", "true");
        assertEquals(expectedAaa, aaa.getValues());

        Violation ccc = r.getViolations().get(1);
        assertEquals(3L, ccc.getRow(), "CCC is ItemDef index 3");
        assertEquals("CCC", ccc.getValues().get("variable_name"));
        assertEquals("Qualifier", ccc.getValues().get("define_variable_role"));
    }


    /**
     * DATASET sensitivity collapses to the FIRST failing ItemDef in define order, reported at row 0
     * — never at the ItemDef's own index, which is out of the data-row range and would resolve to a
     * spurious row key.
     */
    @Test
    void datasetSensitivityReportsFirstFailingItemDefAtRowZero() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Variable_Universe\":\"Define\","
                + "\"Sensitivity\":\"Dataset\"," + "\"Check\":" + CHECK + ","
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"variable_name\"]}}");

        RuleExecutionResult r = RuleRunner.execute(rule, dm(), _ -> null, "DM", library(), null,
                define());

        assertEquals(1, r.getViolations().size(), "DATASET sensitivity emits exactly one");
        assertEquals(0L, r.getViolations().get(0).getRow());
        assertEquals("AAA", r.getViolations().get(0).getValues().get("variable_name"));
    }


    /**
     * All effective Output_Variables excluded (E-2) → the define default projection: the define
     * row's attributes ({@code define_variable_*}, incl. the label copied to
     * {@code variable_label}) left-joined to the library row's ({@code library_variable_*}), minus
     * the exclusions. The label-less CCC proves variable_label is gated on the define label
     * existing.
     */
    @Test
    void defaultProjectionJoinsDefineAndLibraryAttributesMinusExclusions() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},\"Variable_Universe\":\"Define\","
                + "\"Sensitivity\":\"Record\"," + "\"Check\":" + CHECK + ","
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":"
                + "[\"!define_variable_role\",\"!library_variable_role\",\"!variable_name\"]}}");

        RuleExecutionResult r = RuleRunner.execute(rule, dm(), _ -> null, "DM", library(), null,
                define());

        assertEquals(2, r.getViolations().size());
        Map<String, String> aaa = r.getViolations().get(0).getValues();
        Map<String, String> expectedAaa = new LinkedHashMap<>();
        expectedAaa.put("define_variable_name", "AAA");
        expectedAaa.put("define_variable_label", "Aaa Label");
        expectedAaa.put("define_variable_codelist_coded_codes", "[C1, C2]");
        expectedAaa.put("variable_label", "Aaa Label");
        expectedAaa.put("library_variable_name", "AAA");
        assertEquals(expectedAaa, aaa,
                "define + library attributes minus the three excluded entries");

        Map<String, String> ccc = r.getViolations().get(1).getValues();
        assertTrue(!ccc.containsKey("variable_label"),
                "an ItemDef without a define label carries no variable_label");
        assertEquals("CCC", ccc.get("define_variable_name"));
        assertEquals("CCC", ccc.get("library_variable_name"));
        assertTrue(!ccc.containsKey("define_variable_role"), "excluded entry stays excluded");
    }
}
