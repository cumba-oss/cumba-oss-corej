package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.RulePackageManifest;
import net.cumba.corej.core.gen.CtStandardRef;
import net.cumba.corej.core.metadata.OdmDefineXMLProvider;
import net.cumba.corej.core.metadata.store.MetadataStoreWriter;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredCodelist;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredTerm;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.run.DatasetExecutionSummary;
import net.cumba.corej.core.run.StudyValidationException;
import net.cumba.corej.core.run.StudyValidationParams;
import net.cumba.corej.core.run.StudyValidationResult;
import net.cumba.corej.core.run.StudyValidationService;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.manager.IDataTableLibraryRef;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.manager.IDataTableRef;
import net.cumba.datatable.manager.ILibraryMemberRef;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Define-ct plan P6 — the run-level CT-selection scenarios: one {@code .cdt} per row of the §4.4
 * state table, plus the same-set merge, the Define-XML 2.0 fallback, and the §4.5.1 unconsumed-root
 * case. Each scenario drives the REAL run path — {@link StudyValidationService#validate} over a
 * per-scenario synthetic metadata store — so the precedence (P4), the named-package abort (P5) and
 * the §4.2 mismatch note are asserted where they live, not on extracted helpers.
 *
 * <h2>Scenario semantics</h2>
 * <ul>
 * <li>{@code #ct-packages} — the run's {@code CT Packages} field; absent = blank.</li>
 * <li>{@code #ct-available} — the CT packages the seeded store holds ({@code none} = no CT at all);
 * absent = every package the scenario references. The store always holds the {@code sdtmig/3-4} IG
 * product — availability here is a CT axis, not a product axis.</li>
 * <li>The <b>newest</b> available package holds only the {@code NY} codelist (bound by
 * {@code VSSTAT}); every <b>older</b> one holds only {@code AGEU} (bound by {@code VSAGEU}). A
 * scenario whose dataset carries both columns therefore fires both violations only when the
 * multi-package merge really loaded both packages — a first-id-wins regression leaves one codelist
 * unresolvable and the rule SKIPs instead.</li>
 * <li>{@code #expect-abort} — the run must throw {@link StudyValidationException} naming the
 * substring; {@code #expect-ct-mismatch} — the completed run's {@code CT_Declaration_Mismatch} note
 * must contain the substring, and its absence asserts NO note.</li>
 * </ul>
 */
class CtSelectionScenarioTest
{

    private static final String FIXTURES = "net/cumba/corej/ruletest/ctselection_fixtures/";

    /** The one CT-dependent rule every scenario runs — the CDISC-SEND-0296 shape. */
    private static final String RULE_ID = "CT-RUN-1";

    @TempDir
    Path tempDir;

    @AfterEach
    void clearConfiguredStore()
    {
        System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
    }


    @ParameterizedTest
    @ValueSource(strings =
    {
            "row1-nothing-named-skips.cdt", "row2-declared-adopted-runs.cdt",
            "row3-declared-missing-aborts.cdt", "row4-user-wins-note-reports.cdt",
            "row5-user-missing-aborts.cdt", "row6-invalid-declaration-nonevent.cdt",
            "merge-two-declared-same-set.cdt", "define-20-falls-back.cdt",
            "unconsumed-root-ignored.cdt"
    })
    void scenario(String aFile) throws IOException
    {
        RuleTestScenario scenario = RuleTestCdt.loadResource(FIXTURES + aFile);

        // 1. Materialise the define sidecar (when declared) and read its declared CT set.
        Path definePath = null;
        List<CtStandardRef> declared = List.of();
        if (scenario.getDefineXml() != null)
        {
            definePath = copyResource(scenario.getDefineXml());
            declared = new OdmDefineXMLProvider(
                    new net.cumba.cdisc.define.DefineXmlParser().parse(definePath.toFile()))
                            .declaredCtPackages();
        }

        // 2. Seed the store: IG product always; CT packages per #ct-available (default: every
        // package the scenario references).
        List<String> available = scenario.getCtAvailable();
        if (available == null)
        {
            Set<String> referenced = new LinkedHashSet<>();
            if (scenario.getCtPackages() != null)
            {
                referenced.addAll(scenario.getCtPackages());
            }
            for (CtStandardRef ref : declared)
            {
                referenced.add(ref.packageId());
            }
            available = new ArrayList<>(referenced);
        }
        configureStore(available);

        // 3. The run: the scenario's datasets behind a mocked manager, the fixed CT rule, and the
        // scenario's CT Packages field.
        StudyValidationParams.Builder params = StudyValidationParams.builder()
                .manager(managerOf(scenario.getDatasets())).dataLibrary(tempDir.toString())
                .rulesDir(writeRules().toString()).rulesPackages(List.of("sdtmig-3-4"))
                .metadataProducts(List.of("standards/sdtmig/3-4")).controlledTerminologyPackages(
                        scenario.getCtPackages() != null ? scenario.getCtPackages() : List.of());
        if (definePath != null)
        {
            params.defineXmlPath(definePath.toString());
        }

        String expectAbort = scenario.getExpectAbort();
        if (expectAbort != null)
        {
            StudyValidationException e = org.junit.jupiter.api.Assertions.assertThrows(
                    StudyValidationException.class,
                    () -> new StudyValidationService("0.0.0-test").validate(params.build()),
                    aFile + ": the run must abort");
            assertTrue(e.getMessage().contains(expectAbort),
                    aFile + ": abort must name '" + expectAbort + "', got: " + e.getMessage());
            return;
        }

        StudyValidationResult result = new StudyValidationService("0.0.0-test")
                .validate(params.build());

        // 4. The rule's verdict. Row 1's SKIP is the D3/R2 boundary pin: reaching this point at
        // all already proves the run did NOT abort.
        DatasetExecutionSummary.RuleExecution rule = ruleExecution(result);
        switch (scenario.getExpect())
        {
        case VIOLATION ->
        {
            assertEquals("EXECUTED", rule.status(), aFile + ": " + rule.notExecutedReason());
            assertTrue(rule.violations() > 0, aFile + ": the rule must fire");
        }
        case NO_VIOLATION ->
        {
            assertEquals("EXECUTED", rule.status(), aFile + ": " + rule.notExecutedReason());
            assertEquals(0, rule.violations(), aFile);
        }
        case SKIPPED -> assertEquals("SKIPPED", rule.status(),
                aFile + ": a run with nothing named proceeds and the CT-dependent rule SKIPs "
                        + "visibly — specifically NOT an abort");
        // ⚑ Was absent, and the switch had no default: a CT scenario declaring
        // expect=executionError fell straight through and PASSED without its verdict ever being
        // looked at. No scenario declares it today, which is why nothing was red. Error Prone
        // [MissingCasesInEnumSwitch] is what found it; the arm below is the same contract
        // ExecutionVerdictCheck enforces for the general harness.
        // ⚠ The status string is "ERROR", not "EXECUTION_ERROR": DatasetExecutionSummary.
        // RuleExecution#status is documented as EXECUTED / SKIPPED / ERROR, and LibraryValidator
        // builds it as `res.isError() ? "ERROR" : …`. Asserting the enum's own name here would
        // have failed every correctly-erroring scenario.
        case EXECUTION_ERROR -> assertEquals("ERROR", rule.status(),
                aFile + ": the scenario declares expect=executionError, so the rule must have"
                        + " ERRORED — " + rule.notExecutedReason());
        }
        Integer expectCount = scenario.getExpectViolationCount();
        if (expectCount != null)
        {
            assertEquals(expectCount.intValue(), rule.violations(),
                    aFile + ": violation count (the merge scenario needs BOTH packages loaded)");
        }

        // 5. The §4.2 run-level note: presence with substring, or pinned absence.
        String note = result.conformance().ctDeclarationMismatch();
        String expectNote = scenario.getExpectCtMismatch();
        if (expectNote != null)
        {
            assertNotNull(note, aFile + ": expected a CT_Declaration_Mismatch note");
            assertTrue(note.contains(expectNote),
                    aFile + ": note must contain '" + expectNote + "', got: " + note);
        }
        else
        {
            assertNull(note, aFile + ": no CT_Declaration_Mismatch note expected, got: " + note);
        }
    }

    // ------------------------------------------------------------------
    // Fixture plumbing
    // ------------------------------------------------------------------


    private Path copyResource(String aName) throws IOException
    {
        ClassLoader cl = getClass().getClassLoader();
        try (InputStream in = cl.getResourceAsStream(FIXTURES + aName))
        {
            assertNotNull(in, "sidecar resource missing: " + aName);
            Path out = tempDir.resolve(aName);
            Files.copy(in, out);
            return out;
        }
    }


    /**
     * The one rule every scenario runs: a value check against the bound library codelist
     * (non-extensible, value outside the coded values ⇒ violation) — the shape whose three outcomes
     * separate the state table's rows: resolved ⇒ fires, bound-but-unresolvable ⇒ visible SKIP
     * (define P1), no codelist ⇒ no-fire.
     */
    private Path writeRules() throws IOException
    {
        Path dir = Files.createDirectory(tempDir.resolve("rules"));
        Files.writeString(dir.resolve("rules-sdtmig-3-4.json"), """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "%s"},
                      "Sensitivity": "Record",
                      "Check": {"all": [
                        {"expression": "var_codelist_extensible(\\"LIBRARY\\") == false"},
                        {"expression": "not empty(var_codelist_coded_values(\\"LIBRARY\\"))"},
                        {"expression": "not empty(value())"},
                        {"expression": "value() not in var_codelist_coded_values(\\"LIBRARY\\")"}
                      ]},
                      "Outcome": {"Message": "value outside non-extensible codelist",
                                  "Output_Variables": ["variable_name", "variable_value"]}
                    }
                  }
                }
                """.formatted(RULE_ID));
        new RulePackageManifest("test",
                List.of(new RulePackageManifest.Entry("rules-sdtmig-3-4.json", "CDISC", "sdtmig",
                        "3-4", 1))).writeTo(dir);
        return dir;
    }


    /**
     * Seeds the scenario's metadata store: the {@code sdtmig/3-4} IG product (dataset {@code VS}
     * with {@code VSSTAT} bound to codelist concept {@code C66742} = NY and {@code VSAGEU} bound to
     * {@code C66781} = AGEU), plus one CT package per available id — NY only in the newest, AGEU
     * only in every older one (see the class javadoc for why that split proves the merge).
     */
    private void configureStore(List<String> aAvailableCtIds) throws IOException
    {
        StoredVariable vsstat = StoredVariable.builder().name("VSSTAT").ordinal("1").core("Exp")
                .simpleDatatype("Char").codelistIds(List.of("C66742")).build();
        StoredVariable vsageu = StoredVariable.builder().name("VSAGEU").ordinal("2").core("Perm")
                .simpleDatatype("Char").codelistIds(List.of("C66781")).build();
        StoredProduct ig = StoredProduct.builder().key("standards/sdtmig/3-4").version("3-4")
                .classes(List.of(new StoredClass("Findings", null, "1", List.of(),
                        List.of(new StoredDataset("VS", "Vital Signs", "1", null,
                                List.of(vsstat, vsageu))))))
                .build();
        MetadataStoreWriter writer = new MetadataStoreWriter().addProduct(ig);

        List<String> newestFirst = new ArrayList<>(aAvailableCtIds);
        newestFirst.sort(Comparator.reverseOrder());
        for (int i = 0; i < newestFirst.size(); i++)
        {
            StoredCodelist codelist = i == 0
                    ? new StoredCodelist("NY", "C66742", null, null, null, Boolean.FALSE,
                            List.of(new StoredTerm("N", "C49487", "No", null, null),
                                    new StoredTerm("Y", "C49488", "Yes", null, null)))
                    : new StoredCodelist("AGEU", "C66781", null, null, null, Boolean.FALSE,
                            List.of(new StoredTerm("YEARS", "C29848", "Years", null, null),
                                    new StoredTerm("MONTHS", "C29846", "Months", null, null)));
            writer.addCtPackage(new StoredCtPackage(newestFirst.get(i), List.of(codelist)));
        }
        Path file = tempDir.resolve("store.zip");
        writer.publishedCtPackages(newestFirst).productCatalogue(List.of("standards/sdtmig/3-4"))
                .write(file);
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, file.toString());
    }


    /** A mocked manager whose library members are exactly the scenario's datasets. */
    private IDataTableManager managerOf(List<OverlayDataTable> aDatasets) throws IOException
    {
        IDataTableManager mgr = mock(IDataTableManager.class);
        IDataTableLibraryRef lib = mock(IDataTableLibraryRef.class);
        when(lib.getUri()).thenReturn("file:///study");
        when(mgr.getLibraryRef(any(URI.class), any())).thenReturn(lib);

        List<ILibraryMemberRef> members = new ArrayList<>();
        for (IDataTable table : aDatasets)
        {
            String name = table.getMetaData().getName();
            ILibraryMemberRef member = mock(ILibraryMemberRef.class);
            when(member.getName()).thenReturn(name);
            when(member.getUri()).thenReturn("file:///study/" + name + ".csv");
            IDataTableRef ref = mock(IDataTableRef.class);
            when(mgr.getDataTableRef(member, null)).thenReturn(ref);
            when(mgr.getDataTable(ref)).thenReturn(table);
            members.add(member);
        }
        when(mgr.getLibraryMembers(any())).thenAnswer(_ -> members.stream());
        return mgr;
    }


    private DatasetExecutionSummary.RuleExecution ruleExecution(StudyValidationResult aResult)
    {
        for (DatasetExecutionSummary summary : aResult.executionSummaries())
        {
            for (DatasetExecutionSummary.RuleExecution rule : summary.ruleExecutions())
            {
                if (RULE_ID.equals(rule.coreId()))
                {
                    return rule;
                }
            }
        }
        throw new AssertionError("rule " + RULE_ID + " not found in the execution summaries");
    }
}
