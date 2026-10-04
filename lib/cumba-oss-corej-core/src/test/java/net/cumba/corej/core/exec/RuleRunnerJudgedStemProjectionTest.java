package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.metadata.LibraryTemplates;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-library-template-label-resolution} R2-3, round-2 T2 review L1 / L2 — the per-variable
 * finding reports the template its rule <b>judged</b>, whichever way the rule reaches the
 * stem-selecting read and whichever projection builds the finding:
 * <ul>
 * <li>L1: a {@code label_template("LIBRARY", stem="*DT")} read inside a <b>binding</b> selects the
 * judged stem exactly as one in the Check does — the scan reads the inlined Check, as the provider
 * scan beside it does;</li>
 * <li>L2: the metadata <b>default</b> projection (every effective Output_Variables entry excluded)
 * reports the judged stem's template too, and never the provider-internal per-stem keys
 * ({@link LibraryTemplates#STEM_TEMPLATE_KEY_PREFIX}).</li>
 * </ul>
 */
class RuleRunnerJudgedStemProjectionTest
{

    private static final String LABEL_TEMPLATE = "library_variable_label_template";

    /**
     * A nested stem column: its own (longest) stem is {@code *SDT}, the rule judges {@code *DT}.
     */
    private static final MetadataProvider LIBRARY = new StubMetadataProvider().variable("ADSL",
            Map.of("name", "RANDSDT", LibraryTemplates.LABEL_TEMPLATE_KEY, "{Start Date}",
                    LibraryTemplates.stemKey("*DT"), "{Date}", LibraryTemplates.stemKey("*SDT"),
                    "{Start Date}"));

    private static final IDataTable ADSL = MockTable.of().name("ADSL").col("RANDSDT", "2024-01-01")
            .colMeta("RANDSDT", "Randomization Start Date", 8, null).build();

    private static Rule load(String outputVariables) throws Exception
    {
        String json = """
                {"rules":{"R1":{"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Bindings":[{"name":"$t","expression":"label_template(\\"LIBRARY\\", stem=\\"*DT\\")"}],
                 "Check":{"all":[{"expression":"$t == \\"{Date}\\""}]},
                 "Outcome":{"Message":"m","Output_Variables":%s}}}}
                """
                .formatted(outputVariables);
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    private static Map<String, String> finding(Rule rule)
    {
        RuleExecutionResult r = RuleRunnerCalls.execute(rule, ADSL, _ -> null, "ADSL", LIBRARY,
                null, null);
        assertEquals(1, r.getViolations().size(), "the binding's *DT read fires on RANDSDT");
        return r.getViolations().get(0).getValues();
    }


    @Test
    void aStemReadInsideABindingReportsTheJudgedStemsTemplate() throws Exception
    {
        Map<String, String> values = finding(
                load("[\"variable_name\", \"library_variable_label_template\"]"));
        assertEquals("RANDSDT", values.get("variable_name"));
        assertEquals("{Date}", values.get(LABEL_TEMPLATE),
                "the binding judged *DT's template, not the column's own *SDT one");
    }


    @Test
    void theDefaultProjectionReportsTheJudgedStemAndNoPerStemKeys() throws Exception
    {
        Map<String, String> values = finding(load("[\"!$t\"]"));
        assertEquals("RANDSDT", values.get("library_variable_name"),
                "the default projection carries the library keys");
        assertTrue(
                values.keySet().stream()
                        .noneMatch(k -> k.startsWith(
                                "library_variable_" + LibraryTemplates.STEM_TEMPLATE_KEY_PREFIX)),
                "no provider-internal per-stem key: " + values.keySet());
        assertEquals("{Date}", values.get(LABEL_TEMPLATE),
                "the default projection reports the judged stem's template as well");
    }
}
