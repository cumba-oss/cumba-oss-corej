package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.cumba.corej.core.gen.CtStandardRef;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.manager.IDataTableLibraryRef;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.manager.IDataTableRef;
import net.cumba.datatable.manager.ILibraryMemberRef;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.testkit.TestMetadataFixtures;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Define-ct plan P2 (§4.1 / §4.2 / §4.5) — the run reads the Define-XML's declared CT packages and
 * reports a declaration/selection divergence as a run-level note. The define parse is hoisted above
 * the provider build (§4.5), so the same parsed model feeds the CT declaration, the define
 * provider, and the VLM resolver.
 */
class DefineCtSelectionServiceTest
{

    @TempDir
    Path tempDir;

    @org.junit.jupiter.api.AfterEach
    void clearConfiguredStore()
    {
        System.clearProperty(
                net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory.STORE_PROPERTY);
    }

    // ------------------------------------------------------------------
    // §4.2 precedence (unit level) — D1
    // ------------------------------------------------------------------


    @Test
    void populatedFieldWinsOutright()
    {
        List<CtStandardRef> declared = List.of(CtStandardRef.of("STD.1", "SDTM", "2023-12-15"));
        CtSelection sel = CtSelection.resolve(List.of("sdtmct-2026-03-27"), declared);
        assertEquals(CtSelection.Source.USER, sel.source());
        assertEquals(List.of("sdtmct-2026-03-27"), sel.packageIds(),
                "the declaration must not join a user selection");
    }


    @Test
    void blankFieldTakesTheDeclaredSetInDocumentOrder()
    {
        List<CtStandardRef> declared = List.of(CtStandardRef.of("STD.1", "SDTM", "2024-09-27"),
                CtStandardRef.of("STD.2", "ADaM", "2024-03-29"),
                CtStandardRef.of("STD.3", "SDTM", "2024-09-27"));
        CtSelection sel = CtSelection.resolve(List.of(), declared);
        assertEquals(CtSelection.Source.DEFINE, sel.source());
        assertEquals(List.of("sdtmct-2024-09-27", "adamct-2024-03-29"), sel.packageIds(),
                "document order, duplicates dropped");
    }


    @Test
    void nothingNamedResolvesToNone()
    {
        CtSelection sel = CtSelection.resolve(List.of(), List.of());
        assertEquals(CtSelection.Source.NONE, sel.source());
        assertEquals(List.of(), sel.packageIds());
    }

    // ------------------------------------------------------------------
    // The §4.2 note derivation (unit level)
    // ------------------------------------------------------------------


    @Test
    void noDeclarationYieldsNoNote()
    {
        assertNull(StudyValidationService.ctDeclarationMismatch(List.of(),
                List.of("sdtmct-2024-09-27")));
        assertNull(StudyValidationService.ctDeclarationMismatch(List.of(), List.of()));
    }


    @Test
    void agreementYieldsNoNote_orderInsensitive()
    {
        List<CtStandardRef> declared = List.of(CtStandardRef.of("STD.1", "SDTM", "2024-09-27"),
                CtStandardRef.of("STD.2", "ADaM", "2024-03-29"));
        assertNull(StudyValidationService.ctDeclarationMismatch(declared,
                List.of("adamct-2024-03-29", "sdtmct-2024-09-27")));
    }


    @Test
    void divergenceNamesBothSides()
    {
        List<CtStandardRef> declared = List.of(CtStandardRef.of("STD.1", "SDTM", "2023-12-15"));
        assertEquals("define declares sdtmct-2023-12-15; run used sdtmct-2026-03-27",
                StudyValidationService.ctDeclarationMismatch(declared,
                        List.of("sdtmct-2026-03-27")));
        assertEquals("define declares sdtmct-2023-12-15; run used none",
                StudyValidationService.ctDeclarationMismatch(declared, List.of()));
    }

    // ------------------------------------------------------------------
    // Full-run wiring: the note reaches the result's Conformance block
    // ------------------------------------------------------------------


    private static IMetadataLibrary studyMeta()
    {
        return TestMetadataFixtures.lib("study")
                .table(TestMetadataFixtures.table("DM").label("Demographics")
                        .column(TestMetadataFixtures.column("USUBJID", 0, DataValueType.STRING)
                                .label("Unique Subject Identifier").build())
                        .build())
                .build();
    }


    private IDataTableManager managerWithDm() throws IOException
    {
        IDataTableManager mgr = mock(IDataTableManager.class);
        IDataTableLibraryRef lib = mock(IDataTableLibraryRef.class);
        when(lib.getUri()).thenReturn("file:///study");
        when(mgr.getLibraryRef(any(URI.class), any())).thenReturn(lib);
        when(mgr.getMetadataLibrary(any())).thenReturn(studyMeta());
        ILibraryMemberRef dm = mock(ILibraryMemberRef.class);
        when(dm.getName()).thenReturn("DM");
        when(dm.getUri()).thenReturn("file:///study/dm.csv");
        when(mgr.getLibraryMembers(any())).thenAnswer(_ -> Stream.of(dm));
        IDataTableRef ref = mock(IDataTableRef.class);
        when(mgr.getDataTableRef(any(ILibraryMemberRef.class), any())).thenReturn(ref);
        IDataTable dmTable = MockTable.of().name("DM").label("Demographics")
                .col("USUBJID", "SUBJ-001").build();
        when(mgr.getDataTable(ref)).thenReturn(dmTable);
        return mgr;
    }


    private Path writeRules() throws IOException
    {
        Path dir = Files.createDirectory(tempDir.resolve("rules"));
        Files.writeString(dir.resolve("rules-custom-1-0.json"), """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-CT-NOTE"},
                      "Check": {"name": "USUBJID", "operator": "var_exists"}
                    }
                  }
                }
                """);
        new net.cumba.corej.core.RulePackageManifest("test",
                List.of(new net.cumba.corej.core.RulePackageManifest.Entry("rules-custom-1-0.json",
                        "CDISC", "custom", "1-0", 1))).writeTo(dir);
        return dir;
    }


    private Path writeDefine(String standardsBlock) throws IOException
    {
        Path define = tempDir.resolve("define.xml");
        Files.writeString(define, """
                <?xml version="1.0" encoding="UTF-8"?>
                <ODM xmlns:def="http://www.cdisc.org/ns/def/v2.1" ODMVersion="1.3.2"
                     FileType="Snapshot" FileOID="DEF.CT" CreationDateTime="2026-01-01T00:00:00">
                  <Study OID="S1">
                    <MetaDataVersion OID="MDV.1" Name="CT" DefineVersion="2.1.0">
                %s
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """.formatted(standardsBlock));
        return define;
    }


    @Test
    void divergentDeclarationReachesTheConformanceBlock() throws IOException
    {
        Path define = writeDefine("""
                <def:Standards>
                  <def:Standard OID="STD.1" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"
                                Version="2023-12-15" Status="Final"/>
                </def:Standards>
                """);
        StudyValidationParams params = StudyValidationParams.builder().manager(managerWithDm())
                .dataLibrary(tempDir.toString()).defineXmlPath(define.toString())
                .rulesDir(writeRules().toString()).rulesPackages(List.of("custom-1-0"))
                .metadataProducts(List.of("standards/custom/1-0"))
                .controlledTerminologyPackages(List.of("sdtmct-2026-03-27")).build();

        StudyValidationResult result = new StudyValidationService("0.0.0-test").validate(params);

        assertEquals("define declares sdtmct-2023-12-15; run used sdtmct-2026-03-27",
                result.conformance().ctDeclarationMismatch());
    }


    @Test
    void defineWithoutCtDeclarationLeavesTheNoteNull() throws IOException
    {
        Path define = writeDefine("");
        StudyValidationParams params = StudyValidationParams.builder().manager(managerWithDm())
                .dataLibrary(tempDir.toString()).defineXmlPath(define.toString())
                .rulesDir(writeRules().toString()).rulesPackages(List.of("custom-1-0"))
                .metadataProducts(List.of("standards/custom/1-0"))
                .controlledTerminologyPackages(List.of("sdtmct-2026-03-27")).build();

        StudyValidationResult result = new StudyValidationService("0.0.0-test").validate(params);

        assertNull(result.conformance().ctDeclarationMismatch());
    }

    // ------------------------------------------------------------------
    // Full-run precedence over a real (synthetic) metadata store
    // ------------------------------------------------------------------


    /**
     * Writes a store carrying the {@code sdtmig/3-4} IG product plus one CT package per given id
     * (each holding the {@code NY} codelist), and configures it as the run's metadata store.
     */
    private void configureStore(String... ctPackageIds) throws IOException
    {
        Path file = tempDir.resolve("store.zip");
        net.cumba.corej.core.metadata.store.StoredVariable studyid = net.cumba.corej.core.metadata.store.StoredVariable
                .builder().name("USUBJID").ordinal("1").core("Req").simpleDatatype("Char").build();
        net.cumba.corej.core.metadata.store.StoredProduct ig = net.cumba.corej.core.metadata.store.StoredProduct
                .builder().key("standards/sdtmig/3-4").version("3-4")
                .classes(List.of(new net.cumba.corej.core.metadata.store.StoredClass(
                        "SpecialPurpose", null, "1", List.of(),
                        List.of(new net.cumba.corej.core.metadata.store.StoredDataset("DM",
                                "Demographics", "1", null, List.of(studyid))))))
                .build();
        net.cumba.corej.core.metadata.store.MetadataStoreWriter writer = new net.cumba.corej.core.metadata.store.MetadataStoreWriter()
                .addProduct(ig);
        for (String id : ctPackageIds)
        {
            writer.addCtPackage(new net.cumba.corej.core.metadata.store.StoredCtPackage(id,
                    List.of(new net.cumba.corej.core.metadata.store.StoredCodelist("NY", "C66742",
                            null, null, null, Boolean.FALSE,
                            List.of(new net.cumba.corej.core.metadata.store.StoredTerm("N",
                                    "C49487", "No", null, null),
                                    new net.cumba.corej.core.metadata.store.StoredTerm("Y",
                                            "C49488", "Yes", null, null))))));
        }
        writer.publishedCtPackages(List.of(ctPackageIds))
                .productCatalogue(List.of("standards/sdtmig/3-4")).write(file);
        System.setProperty(
                net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory.STORE_PROPERTY,
                file.toString());
    }


    private Path writeSdtmRules() throws IOException
    {
        Path dir = Files.createDirectory(tempDir.resolve("rules-sdtm"));
        Files.writeString(dir.resolve("rules-sdtmig-3-4.json"), """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-CT-SEL"},
                      "Check": {"name": "USUBJID", "operator": "var_exists"}
                    }
                  }
                }
                """);
        new net.cumba.corej.core.RulePackageManifest("test",
                List.of(new net.cumba.corej.core.RulePackageManifest.Entry("rules-sdtmig-3-4.json",
                        "CDISC", "sdtmig", "3-4", 1))).writeTo(dir);
        return dir;
    }


    private StudyValidationParams.Builder sdtmRunParams(Path define, List<String> userCts)
        throws IOException
    {
        return StudyValidationParams.builder().manager(managerWithDm())
                .dataLibrary(tempDir.toString()).defineXmlPath(define.toString())
                .rulesDir(writeSdtmRules().toString()).rulesPackages(List.of("sdtmig-3-4"))
                .metadataProducts(List.of("standards/sdtmig/3-4"))
                .controlledTerminologyPackages(userCts);
    }


    @Test
    void blankFieldTakesTheDeclaredCtThroughTheStore() throws IOException
    {
        // §4.4 row 2: blank field, declaration in play, package present → RUN with the declared
        // CT, and no mismatch note (declaration and run agree).
        configureStore("sdtmct-2024-09-27");
        Path define = writeDefine("""
                <def:Standards>
                  <def:Standard OID="STD.1" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"
                                Version="2024-09-27" Status="Final"/>
                </def:Standards>
                """);

        StudyValidationResult result = new StudyValidationService("0.0.0-test")
                .validate(sdtmRunParams(define, List.of()).build());

        assertEquals("sdtmct-2024-09-27", result.sections().conformanceDetails().get("CT_Version"),
                "CT_Version must report the define-derived selection the run actually used");
        assertNull(result.conformance().ctDeclarationMismatch());
    }


    @Test
    void populatedFieldOverridesTheDeclarationAndTheNoteReports() throws IOException
    {
        // §4.4 row 4: the user's list wins; the declaration is out of play but the divergence is
        // still reported as the run-level note.
        configureStore("sdtmct-2025-03-28");
        Path define = writeDefine("""
                <def:Standards>
                  <def:Standard OID="STD.1" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"
                                Version="2024-09-27" Status="Final"/>
                </def:Standards>
                """);

        StudyValidationResult result = new StudyValidationService("0.0.0-test")
                .validate(sdtmRunParams(define, List.of("sdtmct-2025-03-28")).build());

        assertEquals("sdtmct-2025-03-28", result.sections().conformanceDetails().get("CT_Version"));
        assertEquals("define declares sdtmct-2024-09-27; run used sdtmct-2025-03-28",
                result.conformance().ctDeclarationMismatch());
    }

    // ------------------------------------------------------------------
    // P5 — the §4.4 declared/named-package abort (rows 3, 5, 6) + §4.5.1
    // ------------------------------------------------------------------


    @Test
    void row3_missingDeclaredPackageAbortsNamingIt() throws IOException
    {
        // Blank field, declaration in play, declared package absent from the serving store.
        configureStore("sdtmct-2024-09-27");
        Path define = writeDefine("""
                <def:Standards>
                  <def:Standard OID="STD.1" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"
                                Version="2023-12-15" Status="Final"/>
                </def:Standards>
                """);
        StudyValidationParams params = sdtmRunParams(define, List.of()).build();

        StudyValidationException e = org.junit.jupiter.api.Assertions.assertThrows(
                StudyValidationException.class,
                () -> new StudyValidationService("0.0.0-test").validate(params));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("sdtmct-2023-12-15"),
                e.getMessage());
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("out of play"),
                "the abort must name the escape hatch: " + e.getMessage());
    }


    @Test
    void row5_missingUserPackageAbortsNamingIt() throws IOException
    {
        configureStore("sdtmct-2024-09-27");
        Path define = writeDefine("");
        StudyValidationParams params = sdtmRunParams(define, List.of("sdtmct-2023-12-15")).build();

        StudyValidationException e = org.junit.jupiter.api.Assertions.assertThrows(
                StudyValidationException.class,
                () -> new StudyValidationService("0.0.0-test").validate(params));
        org.junit.jupiter.api.Assertions.assertTrue(e.getMessage().contains("sdtmct-2023-12-15"),
                e.getMessage());
    }


    @Test
    void row6_populatedFieldMakesAMissingDeclarationANonEvent() throws IOException
    {
        // ⭐ Row 6 IS row 4: with the field populated the declaration is not consulted, not
        // resolved and NOT VALIDATED — a missing (even malformed) declared package must be a
        // non-event. This is the escape hatch a naive "validate everything referenced"
        // implementation breaks; if the run validated the declaration it would abort here.
        configureStore("sdtmct-2024-09-27");
        Path define = writeDefine("""
                <def:Standards>
                  <def:Standard OID="STD.1" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"
                                Version="1999-01-01" Status="Final"/>
                  <def:Standard OID="STD.2" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"
                                Version="Draft" Status="Draft"/>
                </def:Standards>
                """);

        StudyValidationResult result = new StudyValidationService("0.0.0-test")
                .validate(sdtmRunParams(define, List.of("sdtmct-2024-09-27")).build());

        assertEquals("sdtmct-2024-09-27", result.sections().conformanceDetails().get("CT_Version"));
        assertEquals("define declares sdtmct-1999-01-01, sdtmct-Draft; run used sdtmct-2024-09-27",
                result.conformance().ctDeclarationMismatch(),
                "the mismatch note keeps reporting even though the declaration is out of play");
    }


    @Test
    void declaredPackageForAnUnconsumedRootIsIgnoredSilently() throws IOException
    {
        // §4.5.1 — a define legitimately covers more than one run validates. The declared cdashct
        // package is ABSENT from the store, and the run must still proceed: an SDTM run never
        // names a cdashct id, so there is nothing to honour and nothing to abort on.
        configureStore("sdtmct-2024-09-27");
        Path define = writeDefine("""
                <def:Standards>
                  <def:Standard OID="STD.1" Name="CDISC/NCI" Type="CT" PublishingSet="CDASH"
                                Version="2024-09-27" Status="Final"/>
                  <def:Standard OID="STD.2" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM"
                                Version="2024-09-27" Status="Final"/>
                </def:Standards>
                """);

        StudyValidationResult result = new StudyValidationService("0.0.0-test")
                .validate(sdtmRunParams(define, List.of()).build());

        assertNull(result.conformance().ctDeclarationMismatch(),
                "an unconsumed-root declared package is not a divergence");
    }

    // ------------------------------------------------------------------
    // requireNamedCtPackagesPresent (unit level)
    // ------------------------------------------------------------------


    @Test
    void presenceGate_messagesDistinguishSourceAndMalformed() throws IOException
    {
        configureStore("sdtmct-2024-09-27");
        Path file = tempDir.resolve("store.zip");
        net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory factory = net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory
                .open(file);

        // Present: no throw, whatever the source.
        StudyValidationService.requireNamedCtPackagesPresent(factory, file,
                List.of("sdtmct-2024-09-27"), CtSelection.Source.USER);
        StudyValidationService.requireNamedCtPackagesPresent(factory, file, List.of(),
                CtSelection.Source.NONE);

        StudyValidationException user = org.junit.jupiter.api.Assertions.assertThrows(
                StudyValidationException.class,
                () -> StudyValidationService.requireNamedCtPackagesPresent(factory, file,
                        List.of("sdtmct-2020-01-01"), CtSelection.Source.USER));
        org.junit.jupiter.api.Assertions.assertTrue(user.getMessage().contains("sdtmct-2020-01-01"),
                user.getMessage());

        StudyValidationException malformed = org.junit.jupiter.api.Assertions.assertThrows(
                StudyValidationException.class,
                () -> StudyValidationService.requireNamedCtPackagesPresent(factory, file,
                        List.of("sdtmct-Draft"), CtSelection.Source.DEFINE));
        org.junit.jupiter.api.Assertions.assertTrue(
                malformed.getMessage().contains("not a valid CT package id"),
                malformed.getMessage());
    }
}
