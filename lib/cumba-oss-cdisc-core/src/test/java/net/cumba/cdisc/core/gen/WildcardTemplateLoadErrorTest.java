package net.cumba.cdisc.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleExecutionStatus;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Review F4 (PLAN-extend-expression-engine) — a wildcard TEMPLATE rule tagged with a
 * {@code loadError} (e.g. an invalid {@code Sensitivity}) must surface as an ERROR execution and
 * never silently expand: {@link WildcardExpander#tryExpand} builds fresh {@link Rule} objects that
 * would drop the {@code loadError}, and the template itself never executes. The generator now
 * passes a load-error-tagged rule through unmodified into the generated package's static rules, so
 * {@link RuleRunner#execute} produces the ERROR sentinel for it (one per dataset). A valid template
 * still expands as before.
 */
class WildcardTemplateLoadErrorTest
{

    private static String packageOf(String ruleJson)
    {
        return "{\"rules\":{\"rule-1\":" + ruleJson + "}}";
    }


    private static Rule loadRule(String coreId, String sensitivity) throws Exception
    {
        String ruleJson = """
                {
                  "Core": {"Id": "%s"},
                  "Sensitivity": "%s",
                  "Check": {"name": "TRTxxP", "operator": "empty"},
                  "Outcome": {"Message": "m", "Output_Variables": ["USUBJID"]}
                }
                """.formatted(coreId, sensitivity);
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        return pkg.getRules().values().iterator().next();
    }


    private static IDataTable adsl()
    {
        return MockTable.of().name("ADSL").col("USUBJID", "S1").col("TRT01P", "").build();
    }


    private static GeneratedRulePackage generate(Rule staticRule, IDataTable table)
    {
        RuleGenerator generator = new RuleGenerator(new EmptyLibraryProvider(), null);
        generator.setDomainName("ADSL");
        generator.setStaticRules(List.of(staticRule));
        return generator.generate(table);
    }


    private static String coreId(Rule rule)
    {
        return rule.getCore() != null ? rule.getCore().getId() : rule.getId();
    }


    @Test
    void loadErrorTemplate_passesThroughUnexpanded_andExecutesAsError() throws Exception
    {
        Rule template = loadRule("T-ERR", "Bogus");
        assertNotNull(template.getLoadError(), "invalid Sensitivity must tag a loadError");
        assertTrue(template.getLoadError().contains("Invalid Sensitivity"),
                template.getLoadError());

        IDataTable table = adsl();
        GeneratedRulePackage out = generate(template, table);

        // The tagged template is passed through unmodified — no fresh-Rule expansion may
        // swallow the loadError.
        List<Rule> fromTemplate = out.getRules().stream()
                .filter(r -> coreId(r) != null && coreId(r).startsWith("T-ERR")).toList();
        assertEquals(1, fromTemplate.size(), "exactly the template itself, no expansions: "
                + fromTemplate.stream().map(WildcardTemplateLoadErrorTest::coreId).toList());
        org.junit.jupiter.api.Assertions.assertSame(template, fromTemplate.get(0),
                "pass-through must be the SAME rule object");
        assertNotNull(fromTemplate.get(0).getLoadError(), "loadError must survive generation");

        // Executing the generated package's rule yields the ERROR sentinel.
        RuleExecutionResult result = RuleRunner.execute(fromTemplate.get(0), table);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
        assertNotNull(result.getStatusMessage());
        assertTrue(result.getStatusMessage().contains("Invalid Sensitivity"),
                result.getStatusMessage());
        assertEquals(1, result.getViolationCount(), "one ERROR sentinel per dataset");
    }


    @Test
    void validTemplate_stillExpands() throws Exception
    {
        Rule template = loadRule("T-OK", "Record");
        assertNull(template.getLoadError(), "valid template must load cleanly");

        GeneratedRulePackage out = generate(template, adsl());

        List<String> ids = out.getRules().stream().map(WildcardTemplateLoadErrorTest::coreId)
                .filter(id -> id != null && id.startsWith("T-OK")).toList();
        assertEquals(List.of("T-OK-TRT01P"), ids,
                "the valid template expands to its concrete rule and is not itself executed");
        // The expansion executes normally (TRT01P empty → fires) — no ERROR.
        Rule concrete = out.getRules().stream().filter(r -> "T-OK-TRT01P".equals(coreId(r)))
                .findFirst().orElseThrow();
        RuleExecutionResult result = RuleRunner.execute(concrete, adsl());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertEquals(1, result.getViolationCount(), "TRT01P is empty on the only row");
    }

    /** Minimal no-op {@link net.cumba.cdisc.core.exec.MetadataProvider}. */
    private static final class EmptyLibraryProvider
            implements
            net.cumba.cdisc.core.exec.MetadataProvider
    {

        @Override
        public List<String> getRequiredVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getModelColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String domain)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String codelistCode)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getVariableMetadata(String domain, String variable)
        {
            return Map.of();
        }


        @Override
        public List<Map<String, String>> getDomainVariables(String domain)
        {
            return List.of();
        }


        @Override
        public Map<String, String> getDatasetMetadata(String domain)
        {
            return Map.of();
        }


        @Override
        public boolean isCodelistExtensible(String codelistName)
        {
            return true;
        }


        @Override
        public Map<String, String> getCodelistTermMappings(String codelistName)
        {
            return Map.of();
        }


        @Override
        public String getStandard()
        {
            return "adamig";
        }


        @Override
        public String getVersion()
        {
            return "1-1";
        }
    }
}
