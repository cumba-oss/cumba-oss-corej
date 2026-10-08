package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.metadata.MetadataKeys;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.ValidationFinding;
import net.cumba.datatable.report.ValidationReportMember;
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

    /** {@code ValidationReportBuilder.DEFINE_PARSE_ERROR_RULE_ID}, spelled out: the stable id. */
    private static final String DEFINE_PARSE = "CUMBA-DEFINE-PARSE";

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
        return run(CountingStudyManager.create(dmTable(), datatableDefine), define);
    }


    private StudyValidationResult run(IDataTableManager mgr, @Nullable Path define)
        throws IOException
    {
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


    /** The presence rule's own violation count on the one dataset of the run. */
    private static int violationsOf(StudyValidationResult result)
    {
        for (DatasetExecutionSummary s : result.executionSummaries())
        {
            for (DatasetExecutionSummary.RuleExecution e : s.ruleExecutions())
            {
                if (RULE_ID.equals(e.coreId()))
                {
                    return e.violations();
                }
            }
        }
        throw new AssertionError("the presence rule did not run: " + result.executionSummaries());
    }


    /** The run's {@code CUMBA-DEFINE-PARSE} findings (runbook W2 DP1b). */
    private static List<ValidationFinding> defineParseErrors(StudyValidationResult result)
    {
        List<ValidationFinding> out = new ArrayList<>();
        for (ValidationReportMember m : result.report().getMembers())
        {
            for (ValidationFinding f : m.getFindings())
            {
                if (DEFINE_PARSE.equals(f.getRuleId()))
                {
                    assertEquals("STUDY", m.getDomain());
                    out.add(f);
                }
            }
        }
        return out;
    }


    /**
     * A manager that answers {@code null} to the FIRST metadata-library request — the Define-XML
     * overlay's datatable conversion, which the service asks for before the run's provider — and
     * the study metadata afterwards: the no-fallback arm of a parse failure, reachable without the
     * run aborting for want of any metadata library.
     */
    private static IDataTableManager noDatatableDefine(IDataTableManager aDelegate)
    {
        java.util.concurrent.atomic.AtomicBoolean first = new java.util.concurrent.atomic.AtomicBoolean(
                true);
        return (IDataTableManager) java.lang.reflect.Proxy
                .newProxyInstance(IDataTableManager.class.getClassLoader(), new Class<?>[]
                {
                        IDataTableManager.class
                }, (proxy, method, args) ->
                {
                    if ("getMetadataLibrary".equals(method.getName()) && first.getAndSet(false))
                    {
                        return null;
                    }
                    try
                    {
                        return method.invoke(aDelegate, args);
                    }
                    catch (java.lang.reflect.InvocationTargetException e)
                    {
                        throw e.getCause();
                    }
                });
    }


    @Test
    void theRuleReportsOnceWhenNoDefineWasSupplied() throws IOException
    {
        StudyValidationResult result = run(studyMeta(), null);
        assertEquals(1, result.findingCount(), "one finding for one fact");
        assertEquals("EXECUTED", statusOf(result), "never SKIPPED for the absence it tests");
        assertEquals(List.of(), defineParseErrors(result), "no define, no parse error (DP1b)");
    }


    @Test
    void theRuleIsSilentWhenTheDefineParsed() throws IOException
    {
        StudyValidationResult result = run(studyMeta(), parseableDefine());
        assertEquals(0, result.findingCount(), "a parseable define: no CUMBA-DEFINE-PARSE (DP1b)");
        assertEquals("EXECUTED", statusOf(result));
    }


    @Test
    void theRuleIsSilentWhenTheSuppliedDefineFailedToParse() throws IOException
    {
        Path broken = tempDir.resolve("define.xml");
        Files.writeString(broken, "this is not a Define-XML document");
        // with the datatable fallback: the define provider is the lossy conversion
        StudyValidationResult withFallback = run(studyMeta(), broken);
        assertEquals(0, violationsOf(withFallback), "supplied is supplied (Q2)");
        assertEquals("EXECUTED", statusOf(withFallback));
        Object basis = withFallback.sections().conformanceDetails().get("Define_Metadata_Basis");
        assertNotNull(basis, "the degraded basis is still reported (unchanged)");
        // runbook W2 DP1b: the parse failure is the run's ONE finding, a study-level ERROR
        List<ValidationFinding> errors = defineParseErrors(withFallback);
        assertEquals(1, errors.size());
        assertEquals(basis.toString(), errors.get(0).getMessage());
        assertEquals(1, withFallback.findingCount(), "the define-parse error, nothing else");
    }


    @Test
    void aParseFailureWithoutTheDatatableFallbackIsReportedTheSameWay() throws IOException
    {
        Path broken = tempDir.resolve("define.xml");
        Files.writeString(broken, "this is not a Define-XML document");
        StudyValidationResult result = run(
                noDatatableDefine(CountingStudyManager.create(dmTable(), studyMeta())), broken);
        assertEquals(0, violationsOf(result), "supplied is supplied (Q2)");
        Object basis = result.sections().conformanceDetails().get("Define_Metadata_Basis");
        assertNotNull(basis);
        assertTrue(basis.toString().startsWith("unavailable"),
                "the no-fallback basis text: " + basis);
        List<ValidationFinding> errors = defineParseErrors(result);
        assertEquals(1, errors.size(), "one study-level ERROR on the no-fallback arm too");
        assertEquals(basis.toString(), errors.get(0).getMessage());
        assertEquals(Severity.ERROR, errors.get(0).getSeverity());
    }
}
