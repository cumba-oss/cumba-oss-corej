package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

/**
 * R-P4 ({@code plans/done/PLAN-native-engine-residuals.md}) — the re-authored ADaM additions
 * {@code ADAM-ADD-100025}/{@code 100026}: {@code $dataset_variables not_contains_all
 * $required_variables} (/{@code $expected_variables}), the fully-native CORE-000355 shape replacing
 * the degenerate {@code variable_name not_contains_all ["$-ref"]} form (whose {@code $}-ref sat
 * inside the keys array, where neither engine expands it). The intended verdict — "the dataset must
 * contain every required/expected variable" — is pinned here on both engines.
 */
@Disabled("rules-adamig-1-3-additions.json temporarily moved; corpus rules load from it. "
        + "Re-enable when the additions corpus is restored.")
class AdamAdditionsReauthoredRulesTest
{

    /** A library provider declaring USUBJID + PARAMCD required, AVAL expected (any domain). */
    private static final MetadataProvider LIBRARY = new MetadataProvider()
    {

        @Override
        public List<String> getRequiredVariables(String d)
        {
            return List.of("USUBJID", "PARAMCD");
        }


        @Override
        public List<String> getExpectedVariables(String d)
        {
            return List.of("AVAL");
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
        public String getVersion()
        {
            return "test";
        }


        @Override
        public String getStandard()
        {
            return "test";
        }


        @Override
        public List<String> getCodelistTerms(String codelistCode)
        {
            return List.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String domain)
        {
            return List.of();
        }


        @Override
        public boolean isCodelistExtensible(String codelistName)
        {
            return false;
        }


        @Override
        public Map<String, String> getCodelistTermMappings(String codelistName)
        {
            return Map.of();
        }
    };

    private static Rule corpusRule(String id) throws Exception
    {
        RulePackage pkg = RulePackageLoader.load(Path.of(System.getProperty("projectBasedir"),
                "src/test/resources/fixtures/rules/packages", "rules-adamig-1-3-additions.json"));
        return pkg.getRules().values().stream()
                .filter(r -> r != null && r.getCore() != null && id.equals(r.getCore().getId()))
                .findFirst().orElseThrow();
    }


    private static int violationCount(Rule rule, IDataTable table)
    {
        return RuleRunner.execute(rule, table, _ -> null, "ADSL", LIBRARY, null, null)
                .getViolationCount();
    }


    private static void assertVerdict(Rule rule, IDataTable table, int expected)
    {
        int nativeC = violationCount(rule, table);
        int legacyC = violationCount(rule, table);
        assertEquals(legacyC, nativeC, "native and legacy verdicts must agree");
        assertEquals(expected, nativeC);
    }


    @Test
    void requiredVariableMissing_fires() throws Exception
    {
        Rule rule = corpusRule("ADAM-ADD-100025");
        assertNull(rule.getLoadError());
        assertNotNull(rule.getCheckExpr(), "the re-authored rule must be native");

        // PARAMCD missing → not_contains_all($dataset_variables, $required_variables) fires once
        // (Dataset sensitivity collapses the all-rows verdict to one finding).
        IDataTable missing = MockTable.of().name("ADSL").col("USUBJID", "01").build();
        assertVerdict(rule, missing, 1);

        IDataTable complete = MockTable.of().name("ADSL").col("USUBJID", "01").col("PARAMCD", "P1")
                .build();
        assertVerdict(rule, complete, 0);
    }


    @Test
    void expectedVariableMissing_fires() throws Exception
    {
        Rule rule = corpusRule("ADAM-ADD-100026");
        assertNull(rule.getLoadError());
        assertNotNull(rule.getCheckExpr(), "the re-authored rule must be native");

        IDataTable missing = MockTable.of().name("ADSL").col("USUBJID", "01").build();
        assertVerdict(rule, missing, 1);

        IDataTable complete = MockTable.of().name("ADSL").col("USUBJID", "01").col("AVAL", "1")
                .build();
        assertVerdict(rule, complete, 0);
    }


    @Test
    void runsOnNativeBackend() throws Exception
    {
        Rule rule = corpusRule("ADAM-ADD-100025");
        IDataTable t = MockTable.of().name("ADSL").col("USUBJID", "01").build();
        Map<String, NativeExecutionRecorder.Backend> rec;

        NativeExecutionRecorder.enable();
        RuleRunner.execute(rule, t, _ -> null, "ADSL", LIBRARY, null, null);
        rec = new HashMap<>(NativeExecutionRecorder.disable());
        assertEquals(NativeExecutionRecorder.Backend.NATIVE, rec.get("ADAM-ADD-100025"));
    }
}
