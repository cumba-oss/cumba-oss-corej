package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * CX-1 probe — the file/size dataset-metadata rules authored against the {@code dataset_location}
 * accessor (source-URI basename, original casing) and {@code dataset_size}. Loads each
 * {@code rules-src} check and runs it through {@link RuleRunner} (which evaluates the
 * {@code extract_metadata} operation via {@code OperationExecutor.evalExtractMetadata} — the CX-1
 * engine change) against a {@link MockTable} carrying a source URI / dataset-size metadata value.
 */
class Cx1FileMetadataRulesProbeTest
{

    private static final YAMLMapper MAPPER = (YAMLMapper) new YAMLMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private static Rule load(String path) throws Exception
    {
        Rule rule = MAPPER.readValue(Files.readString(Path.of(path)), Rule.class);
        // Form-B operations (PLAN-retire-corpus-transforms phase 8) carry no operator
        // until normalized — the same pass the loader and RuleScaffold run.
        RulePackageLoader.normalizeOperations(rule);
        net.cumba.cdisc.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    private static int violations(Rule rule, IDataTable table)
    {
        RuleExecutionResult result = RuleRunner.execute(rule, table, _ -> null);
        return result.getViolationCount();
    }


    private static IDataTable withUri(String uri)
    {
        MockTable t = MockTable.of().col("USUBJID", "S1").name("AE");
        if (uri != null)
        {
            t = t.uri(uri);
        }
        return t.build();
    }

    // -- SEND277 : file name must be lowercase (fires when the basename has an uppercase letter) --


    @Test
    void send277_firesOnUppercaseFileName() throws Exception
    {
        Rule rule = load("src/test/resources/fixtures/rules/checks/CDISC/CDISC-SEND-0277.yaml");
        assertEquals(1, violations(rule, withUri("file:///study/sdtm/AE.xpt")),
                "uppercase basename must fire");
        assertEquals(0, violations(rule, withUri("file:///study/sdtm/ae.xpt")),
                "all-lowercase basename must not fire");
        assertEquals(0, violations(rule, withUri(null)),
                "no source URI (null basename) must not fire");
    }

    // -- fileNameFromUri edge cases (exercised through SEND277's dataset_location accessor) --


    @Test
    void datasetLocation_percentDecoded_trailingSlash_and_opaqueUri() throws Exception
    {
        Rule rule = load("src/test/resources/fixtures/rules/checks/CDISC/CDISC-SEND-0277.yaml");
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

    // SEND272 / SD0062 moved off the dataset_location extension proxy to the authoritative
    // file_format key (CX-2) — their probes live in Cx2FileFormatRulesProbeTest.

    // -- SD1142 : dataset larger than 5 GB (5,000,000,000 bytes) --


    @Test
    void sd1142_firesWhenOver5Gb() throws Exception
    {
        Rule rule = load("src/test/resources/fixtures/rules/checks/PMDA/PMDA-SD1142.yaml");
        IDataTable over = MockTable.of().col("USUBJID", "S1").name("AE")
                .metaValue("dataset_size", 6_000_000_000L).build();
        assertEquals(1, violations(rule, over), "6 GB must fire");
        IDataTable under = MockTable.of().col("USUBJID", "S1").name("AE")
                .metaValue("dataset_size", 1_000L).build();
        assertEquals(0, violations(rule, under), "1 KB must not fire");
    }
}
