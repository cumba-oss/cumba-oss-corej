package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Runbook W1b DP1a end to end ({@code PLAN-engine-gap-define-presence} §4.2): the study-level
 * presence rule {@code not define_available()} reports once when the run was given no Define-XML
 * and stays silent when one was supplied — parsed cleanly, or failing to parse with and without the
 * datatable fallback (Q2: supplied is supplied; the provider's null-ness is NOT the fact).
 */
class StudyValidationServiceDefinePresenceTest
{

    @TempDir
    Path tempDir;

    private static final List<String> CUSTOM_PRODUCT = List.of("standards/custom/1-0");

    private static final String RULE_ID = "CORE-X-910";

    private static IDataTable dmTable()
    {
        return MockTable.of().name("DM").label("Demographics")
                .col("USUBJID", "SUBJ-001", "SUBJ-002").build();
    }


    private static IMetadataLibrary studyMeta()
    {
        return TestMetadataFixtures.lib("study").meta(MetadataKeys.STANDARD_NAME, "custom")
                .meta(MetadataKeys.STANDARD_VERSION, "1-0")
                .table(TestMetadataFixtures.table("DM").label("Demographics")
                        .className("Special-Purpose").structure("One record per subject")
                        .column(TestMetadataFixtures.column("USUBJID", 0, DataValueType.STRING)
                                .label("Unique Subject Identifier").core("Req").role("Identifier")
                                .build())
                        .build())
                .build();
    }


    private Path writePresenceRule() throws IOException
    {
        Path dir = Files.createDirectory(tempDir.resolve("rules-" + System.nanoTime()));
        String fileName = "rules-custom-1-0.json";
        Files.writeString(dir.resolve(fileName), """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "%s"},
                      "Sensitivity": "Study",
                      "Scope": {"Domains": {"Include": ["ALL"]}},
                      "Check": {"expression": "not define_available()"},
                      "Outcome": {"Message": "No Define-XML was supplied to this run."}
                    }
                  }
                }
                """.formatted(RULE_ID));
        new net.cumba.corej.core.RulePackageManifest("test",
                List.of(new net.cumba.corej.core.RulePackageManifest.Entry(fileName, "CDISC",
                        "custom", "1-0", 1, List.of()))).writeTo(dir);
        return dir;
    }


    private StudyValidationResult run(@Nullable IMetadataLibrary datatableDefine,
            @Nullable Path define)
        throws IOException
    {
        IDataTableManager mgr = CountingStudyManager.create(dmTable(), datatableDefine);
        StudyValidationParams.Builder b = StudyValidationParams.builder().manager(mgr)
                .dataLibrary(tempDir.toString()).rulesDir(writePresenceRule().toString())
                .rulesPackages(List.of("custom-1-0")).metadataProducts(CUSTOM_PRODUCT);
        if (define != null)
        {
            b.defineXmlPath(define.toString());
        }
        return new StudyValidationService("0.0.0-test").validate(b.build());
    }


    /** The presence rule's execution status on the one dataset of the run. */
    private static String statusOf(StudyValidationResult result)
    {
        for (DatasetExecutionSummary s : result.executionSummaries())
        {
            for (DatasetExecutionSummary.RuleExecution e : s.ruleExecutions())
            {
                if (RULE_ID.equals(e.coreId()))
                {
                    return e.status();
                }
            }
        }
        throw new AssertionError("the presence rule did not run: " + result.executionSummaries());
    }


    private Path parseableDefine() throws IOException
    {
        Path define = tempDir.resolve("define.xml");
        try (InputStream in = getClass().getResourceAsStream("/define/define-e2-accessors.xml"))
        {
            assertNotNull(in, "fixture");
            Files.copy(in, define);
        }
        return define;
    }


    @Test
    void theRuleReportsOnceWhenNoDefineWasSupplied() throws IOException
    {
        StudyValidationResult result = run(studyMeta(), null);
        assertEquals(1, result.findingCount(), "one finding for one fact");
        assertEquals("EXECUTED", statusOf(result), "never SKIPPED for the absence it tests");
    }


    @Test
    void theRuleIsSilentWhenTheDefineParsed() throws IOException
    {
        StudyValidationResult result = run(studyMeta(), parseableDefine());
        assertEquals(0, result.findingCount());
        assertEquals("EXECUTED", statusOf(result));
    }


    @Test
    void theRuleIsSilentWhenTheSuppliedDefineFailedToParse() throws IOException
    {
        Path broken = tempDir.resolve("define.xml");
        Files.writeString(broken, "this is not a Define-XML document");
        // with the datatable fallback: the define provider is the lossy conversion
        StudyValidationResult withFallback = run(studyMeta(), broken);
        assertEquals(0, withFallback.findingCount(), "supplied is supplied (Q2)");
        assertEquals("EXECUTED", statusOf(withFallback));
        assertNotNull(withFallback.sections().conformanceDetails().get("Define_Metadata_Basis"),
                "the degraded basis is still reported (unchanged)");
        // The no-fallback arm (define provider null after a parse failure) is not reachable
        // through the service: a define overlay whose datatable conversion also fails aborts
        // the run before validation ("attaches no metadata library"). DefineAvailableTest pins
        // that arm on the context directly (UNPARSEABLE with no provider => true).
    }
}
