package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * R-P3 ({@code plans/done/PLAN-native-engine-residuals.md}) — the Tier-B define accessors
 * {@code var_ccode} / {@code var_codelist_coded_codes} (CORE-000929's {@code define_variable_ccode}
 * / {@code define_variable_codelist_coded_codes} operands). The legacy Step-3 cascade injects every
 * {@code define_variable_<key>} from the define provider's per-variable map
 * ({@code RuleRunner.buildVariableMetadata}); the accessors read the SAME map keys
 * ({@code ExprCompiler.readProviderLevel}), so native == legacy per variable by construction —
 * asserted here end-to-end with a provider that actually exposes the keys.
 */
class TierBDefineAccessorParityTest
{

    /** A define provider exposing the Tier-B keys for two AE variables. */
    private static final MetadataProvider DEFINE = new MetadataProvider()
    {

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
        public boolean isCodelistExtensible(String codelistName)
        {
            return false;
        }


        @Override
        public Map<String, String> getCodelistTermMappings(String codelistName)
        {
            return Map.of();
        }


        @Override
        public Map<String, String> getVariableMetadata(String d, String v)
        {
            Map<String, String> m = new HashMap<>();
            m.put("name", v);
            m.put("ccode", "C66734");
            // AEACN's coded code is NOT in the rule's list (fires); AETERM's is (no finding).
            m.put("codelist_coded_codes", "AEACN".equals(v) ? "C99999" : "C12345");
            return m;
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String d)
        {
            // The define ItemDefs (D1 row universe): a define-item rule iterates these, in order.
            return List.of(getVariableMetadata(d, "AEACN"), getVariableMetadata(d, "AETERM"));
        }
    };

    private static final String RULE = "{\"Core\":{\"Id\":\"R1\"},"
            + "\"Variable_Universe\":\"Define\"," + "\"Sensitivity\":\"Dataset\","
            + "\"Check\":{\"all\":["
            + "{\"name\":\"define_variable_ccode\",\"operator\":\"equal_to\","
            + "\"value\":\"C66734\",\"value_is_literal\":true},"
            + "{\"name\":\"define_variable_codelist_coded_codes\","
            + "\"operator\":\"is_not_contained_by\",\"value\":[\"C12345\",\"C67890\"]}]},"
            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[]}}";

    private static Rule loadRule() throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + RULE + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertEquals(null, rule.getLoadError());
        return rule;
    }


    private static Map<Long, Map<String, String>> findings(Rule rule, IDataTable table)
    {
        RuleExecutionResult r = RuleRunner.execute(rule, table, _ -> null, "AE", null, null,
                DEFINE);
        Map<Long, Map<String, String>> out = new HashMap<>();
        for (Violation v : r.getViolations())
        {
            out.put(v.getRowNumber(), v.getValues());
        }
        return out;
    }


    @Test
    void ccodeAndCodedCodesAccessors_iterateDefineItemDefs() throws Exception
    {
        // D1 of PLAN-define-item-metadata-parity-929-1081: a define-item rule iterates the define
        // ItemDefs (getDomainVariables: AEACN, AETERM). AEACN's coded code (C99999) is outside the
        // rule's list so is_not_contained_by fires; AETERM's (C12345) is inside so it does not.
        // var_codelist_coded_codes is now compared element-wise (D4).
        Rule rule = loadRule();
        assertNotNull(rule.getCheckExpr(),
                "Tier-B define operands must now retain a native checkExpr (var_ccode /"
                        + " var_codelist_coded_codes accessors)");

        IDataTable ae = MockTable.of().name("AE").col("AEACN", "x").col("AETERM", "y").build();
        Map<Long, Map<String, String>> nativeF = findings(rule, ae);
        assertEquals(1, nativeF.size(), "only AEACN's coded code is outside the list");
        // Output_Variables is auto-derived from the Check operands, so the finding projects
        // define_variable_ccode and define_variable_codelist_coded_codes (AEACN's values).
        Map<String, String> finding = nativeF.values().iterator().next();
        assertEquals("C66734", finding.get("define_variable_ccode"));
        // Coded codes are emitted in the canonical list form (Python parity), even for one code.
        assertEquals("[C99999]", finding.get("define_variable_codelist_coded_codes"));
    }


    @Test
    void runsOnNativeBackend() throws Exception
    {
        Rule rule = loadRule();
        IDataTable ae = MockTable.of().name("AE").col("AEACN", "x").build();

        NativeExecutionRecorder.enable();
        RuleRunner.execute(rule, ae, _ -> null, "AE", null, null, DEFINE);
        assertEquals(NativeExecutionRecorder.Backend.NATIVE,
                NativeExecutionRecorder.disable().get("R1"),
                "the Tier-B rule must evaluate on the NATIVE backend");
    }
}
