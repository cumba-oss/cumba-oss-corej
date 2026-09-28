package net.cumba.corej.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * CX-1 probe — the {@code dataset_location} metadata accessor (source-URI basename, original
 * casing), and the {@code dataset_size} provider channel. A hand-written rule
 * ({@link #UPPERCASE_LOCATION_RULE}) binds {@code extract_metadata("dataset_location")} and fires
 * when the basename carries an uppercase letter; it runs through {@link RuleRunner} (which
 * evaluates the {@code extract_metadata} operation via
 * {@code OperationExecutor.evalExtractMetadata} — the CX-1 engine change) against a
 * {@link MockTable} carrying a source URI. The uppercase test only makes the basename observable:
 * each assertion below pins one edge of {@code fileNameFromUri}.
 */
class Cx1FileMetadataRulesProbeTest
{

    private static final String UPPERCASE_LOCATION_RULE = """
            {"rules":{"L1":{"Core":{"Id":"T-LOCATION-UPPER"},"Sensitivity":"Dataset",
             "Scope":{"Domains":{"Include":["ALL"]}},
             "Bindings":[{"name":"$dataset_location",
               "expression":"extract_metadata(\\"dataset_location\\")"}],
             "Check":{"expression":"$dataset_location =~ /[A-Z]/"},
             "Outcome":{"Message":"m","Output_Variables":["$dataset_location"]}}}}""";

    /**
     * Fires when the provider-supplied {@code dataset_size} metadata value exceeds one megabyte —
     * the channel a size rule reads; the threshold is arbitrary.
     */
    private static final String SIZE_RULE = """
            {"rules":{"L1":{"Core":{"Id":"T-DATASET-SIZE"},"Sensitivity":"Dataset",
             "Scope":{"Domains":{"Include":["ALL"]}},
             "Bindings":[{"name":"$dataset_size",
               "expression":"extract_metadata(\\"dataset_size\\")"}],
             "Check":{"expression":"$dataset_size > 1000000"},
             "Outcome":{"Message":"m","Output_Variables":["$dataset_size"]}}}}""";

    private static Rule load() throws IOException
    {
        return load(UPPERCASE_LOCATION_RULE);
    }


    private static Rule load(String json) throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("L1");
        assertNull(rule.getLoadError(), "the hand-written rule must load: " + rule.getLoadError());
        return rule;
    }


    private static int violations(Rule rule, IDataTable table)
    {
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table, _ -> null);
        return result.getViolationCount();
    }


    private static IDataTable withUri(String uri)
    {
        return MockTable.of().col("USUBJID", "S1").name("AE").uri(uri).build();
    }


    @Test
    void datasetLocation_percentDecoded_trailingSlash_and_opaqueUri() throws IOException
    {
        Rule rule = load();
        // control: the plain basename is read with its casing
        assertEquals(1, violations(rule, withUri("file:///study/sdtm/AE.xpt")),
                "uppercase basename 'AE.xpt' -> fires");
        assertEquals(0, violations(rule, withUri("file:///study/sdtm/ae.xpt")),
                "lowercase basename 'ae.xpt' -> no fire");
        // percent-decoded (%20 -> space); casing preserved through the decode
        assertEquals(1, violations(rule, withUri("file:///s/A%20E.xpt")),
                "percent-decoded 'A E.xpt' has an uppercase letter -> fires");
        assertEquals(0, violations(rule, withUri("file:///s/a%20e.xpt")),
                "percent-decoded 'a e.xpt' is lowercase -> no fire");
        // trailing slash -> empty basename -> null dataset_location -> no fire
        assertEquals(0, violations(rule, withUri("file:///s/dir/")),
                "trailing-slash path has an empty basename (null) -> no fire");
        // opaque URI (no path) -> getSchemeSpecificPart fallback
        assertEquals(1, violations(rule, withUri("urn:AE.xpt")),
                "opaque-URI basename 'AE.xpt' via scheme-specific-part -> fires");
    }


    /**
     * {@code extract_metadata("dataset_size")} reads the table's {@code dataset_size} metadata
     * value — the provider channel a size rule depends on; without it the rule goes inert, not red.
     */
    @Test
    void datasetSize_readsTheProviderMetadataValue() throws IOException
    {
        Rule rule = load(SIZE_RULE);
        IDataTable over = MockTable.of().col("USUBJID", "S1").name("AE")
                .metaValue("dataset_size", 2_000_000L).build();
        RuleExecutionResult fired = RuleRunnerCalls.execute(rule, over, _ -> null);
        assertEquals(1, fired.getViolationCount(), "2 MB is over the 1 MB threshold -> fires");
        assertEquals("2000000", fired.getViolations().get(0).getValues().get("$dataset_size"),
                "the finding projects the value read from the channel");
        IDataTable under = MockTable.of().col("USUBJID", "S1").name("AE")
                .metaValue("dataset_size", 1_000L).build();
        assertEquals(0, violations(rule, under), "1 KB is under the threshold -> no fire");
    }
}
