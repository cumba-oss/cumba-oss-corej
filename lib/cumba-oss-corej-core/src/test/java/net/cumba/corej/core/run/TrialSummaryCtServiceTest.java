package net.cumba.corej.core.run;

import static net.cumba.corej.core.run.TsTestSupport.row;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import net.cumba.corej.core.RulePackageManifest;
import net.cumba.corej.core.metadata.store.MetadataStoreWriter;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredCodelist;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredTerm;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.run.StudyValidationService.StandardKind;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.manager.IDataTableManager;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PLAN-ct-packages-default-and-ts-source C9 / C10 — the Trial Summary as the run's second CT
 * source, through the REAL {@link StudyValidationService#validate} over a synthetic store and a
 * Mockito-free fake manager: the root helper (fold F4), T1 / T2 / T4 of §4.2, the "not read is
 * literal" contract, the CQ6 degrade on any unchecked load failure, and fold F1's mismatch basis.
 */
class TrialSummaryCtServiceTest
{

    @TempDir
    Path tempDir;

    @AfterEach
    void clearConfiguredStore()
    {
        System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
    }

    // ------------------------------------------------------------------
    // ctRootsOf — one answer for the selection and the store provider (F4)
    // ------------------------------------------------------------------


    @Test
    void ctRoots_followTheStoreProvidersBranches()
    {
        assertEquals(new StudyValidationService.CtRoots(List.of("sdtmct"), "sdtmct"),
                StudyValidationService.ctRootsOf(StandardKind.SDTM, RunStandard.of("sdtmig/3-4"),
                        List.of("standards/sdtmig/3-4")));
        // a sendig/* run carries standard "sendig": sendct precedes the sdtmct fallback root,
        // exactly the order tryStoreProvider loaded before this plan.
        assertEquals(new StudyValidationService.CtRoots(List.of("sendct", "sdtmct"), "sendct"),
                StudyValidationService.ctRootsOf(StandardKind.SDTM,
                        RunStandard.of("sendig/dart-1-1"), List.of("standards/sendig/dart-1-1")));
        // ADaM has no Trial Summary: CT from the define only (CTD-2).
        assertEquals(new StudyValidationService.CtRoots(List.of("adamct", "sdtmct"), null),
                StudyValidationService.ctRootsOf(StandardKind.ADAM, RunStandard.of("adamig-1-3"),
                        List.of("standards/adam/adamig-1-3")));
        // A TIG ADaM leg is ADaM family although its standard "tig" is UNKNOWN-kind.
        assertEquals(new StudyValidationService.CtRoots(List.of("adamct", "sdtmct"), null),
                StudyValidationService.ctRootsOf(StandardKind.UNKNOWN,
                        RunStandard.of("tig/1-0/adam"), List.of("standards/tig/1-0/adam")));
        // P0: a TIG SDTM / SEND leg run is UNKNOWN-kind, never store-served, never reads TS.
        for (String leg : List.of("sdtm", "send"))
        {
            assertEquals(new StudyValidationService.CtRoots(List.of(), null),
                    StudyValidationService.ctRootsOf(StandardKind.UNKNOWN,
                            RunStandard.of("tig/1-0/" + leg), List.of("standards/tig/1-0/" + leg)),
                    leg);
        }
    }

    // ------------------------------------------------------------------
    // Full runs
    // ------------------------------------------------------------------


    /** T1: blank field, no define, TS states CDISC CT the store holds → the run uses it, noted. */
    @Test
    void t1_trialSummaryCtIsAdoptedAndNoted() throws IOException
    {
        configureStore("sdtmig", "3-4", "sdtmct-2023-12-15");
        // 5e review L1: the member is named as a file ("ts.xpt"), so the TS lookup must match it
        // the way the dataset filter does (extension stripped, upper-cased).
        StudyValidationResult result = run("sdtmig", "3-4", null, List.of(), "ts.xpt",
                () -> TsTestSupport
                        .sdtmTs(List.of(row("S1", "TCNTRL", "PLACEBO", "CDISC CT", "2023-12-15"))));

        assertEquals("sdtmct-2023-12-15", result.sections().conformanceDetails().get("CT_Version"));
        String note = result.conformance().ctDeclarationMismatch();
        assertNotNull(note, "a CT set taken from data nobody selected must be visible (CQ7)");
        assertTrue(note.contains("CT taken from the Trial Summary: sdtmct-2023-12-15 (TSVCDREF "
                + "\"CDISC CT\", TSVCDVER \"2023-12-15\")"), note);
    }


    /**
     * T2: a TS-derived package the store does not hold aborts, naming it, its row and the hatch.
     */
    @Test
    void t2_unheldTrialSummaryPackageAbortsWithTheHatch() throws IOException
    {
        configureStore("sdtmig", "3-4", "sdtmct-2024-09-27");
        StudyValidationException e = assertThrows(StudyValidationException.class,
                () -> run("sdtmig", "3-4", null, List.of(), () -> TsTestSupport
                        .sdtmTs(List.of(row("S1", "A", "x", "CDISC CT", "2023-12-15")))));

        assertTrue(e.getMessage().startsWith("The Trial Summary (TS) states controlled-terminology"
                + " package 'sdtmct-2023-12-15' (TSVCDREF \"CDISC CT\", TSVCDVER \"2023-12-15\")"),
                e.getMessage());
        assertTrue(e.getMessage().contains(CtPresence.CT_FIELD_HATCH), e.getMessage());
        assertTrue(e.getMessage().contains("takes the Trial Summary out of play"), e.getMessage());
    }


    /**
     * 5e review LOW-2: a TS date no CDISC CT release carries (absent from the store's published
     * enumeration) is a typo — the abort says so instead of advising to seed it; a published date
     * the store merely lacks keeps the seeding advice.
     */
    @Test
    void t2_aDateNoReleaseCarriesIsNamedAsSuch_aPublishedOneIsSeedable() throws IOException
    {
        configureStore("sdtmig", "3-4", List.of("sdtmct-2024-09-27"),
                List.of("sdtmct-2024-09-27", "sdtmct-2023-12-15"));
        Supplier<IDataTable> typo = () -> TsTestSupport
                .sdtmTs(List.of(row("S1", "A", "x", "CDISC CT", "2023-12-16")));
        StudyValidationException unknown = assertThrows(StudyValidationException.class,
                () -> run("sdtmig", "3-4", null, List.of(), typo));
        assertTrue(unknown.getMessage().contains("'sdtmct-2023-12-16'"), unknown.getMessage());
        assertTrue(
                unknown.getMessage()
                        .contains("no CDISC CT release of that date is known to the store"),
                unknown.getMessage());
        assertFalse(unknown.getMessage().contains("Seed the store"), unknown.getMessage());
        assertTrue(unknown.getMessage().contains(CtPresence.CT_FIELD_HATCH), unknown.getMessage());

        Supplier<IDataTable> published = () -> TsTestSupport
                .sdtmTs(List.of(row("S1", "A", "x", "CDISC CT", "2023-12-15")));
        StudyValidationException seedable = assertThrows(StudyValidationException.class,
                () -> run("sdtmig", "3-4", null, List.of(), published));
        assertTrue(seedable.getMessage().contains("Seed the store with it"), seedable.getMessage());
        assertFalse(seedable.getMessage().contains("no CDISC CT release"), seedable.getMessage());
    }


    /** LOW-2, DEFINE branch: the same wording for a declared date no CDISC CT release carries. */
    @Test
    void define_aDateNoReleaseCarriesIsNamedAsSuch() throws IOException
    {
        configureStore("sdtmig", "3-4", List.of("sdtmct-2024-09-27"),
                List.of("sdtmct-2024-09-27", "sdtmct-2023-12-15"));
        Path typo = writeDefine("SDTM", "2023-12-16");
        StudyValidationException unknown = assertThrows(StudyValidationException.class,
                () -> run("sdtmig", "3-4", typo, List.of(), () -> TsTestSupport.sdtmTs(List.of())));
        assertTrue(unknown.getMessage().startsWith("The Define-XML declares"),
                unknown.getMessage());
        assertTrue(
                unknown.getMessage()
                        .contains("no CDISC CT release of that date is known to the store"),
                unknown.getMessage());
        assertFalse(unknown.getMessage().contains("Seed the store"), unknown.getMessage());

        Path published = tempDir.resolve("published-define.xml");
        Files.move(writeDefine("SDTM", "2023-12-15"), published);
        StudyValidationException seedable = assertThrows(StudyValidationException.class,
                () -> run("sdtmig", "3-4", published, List.of(),
                        () -> TsTestSupport.sdtmTs(List.of())));
        assertTrue(seedable.getMessage().contains("Seed the store with it"), seedable.getMessage());
    }


    /** CQ1 (a) end to end: a SEND run takes sendct-<date> from SNDCTVER, no sdtmct companion. */
    @Test
    void send_sndctverIsAdopted_noSdtmctCompanion() throws IOException
    {
        configureStore("sendig", "3-1-1", "sendct-2024-09-27");
        StudyValidationResult result = run("sendig", "3-1-1", null, List.of(),
                () -> TsTestSupport.sendTs(List.of(row("S1", "SNDCTVER", "SEND CT 2024-09-27"))));

        assertEquals("sendct-2024-09-27", result.sections().conformanceDetails().get("CT_Version"),
                "CQ2 (a): only the sendct package, never an sdtmct one on the same date");
    }


    /**
     * "Not read is literal" (§4.2): with a user list — or a consumed-root define — in play the TS
     * is never loaded. TS is a reference here (the dataset filter targets DM only), so the only
     * possible load is the selection's own.
     */
    @Test
    void userListOrConsumedRootDefine_neverLoadTheTrialSummary() throws IOException
    {
        configureStore("sdtmig", "3-4", "sdtmct-2024-09-27");
        AtomicInteger loads = new AtomicInteger();
        Supplier<IDataTable> ts = () ->
        {
            loads.incrementAndGet();
            return TsTestSupport.sdtmTs(List.of(row("S1", "A", "x", "CDISC CT", "1999-01-01")));
        };

        run("sdtmig", "3-4", null, List.of("sdtmct-2024-09-27"), ts);
        assertEquals(0, loads.get(), "a user list takes the TS out of play");

        run("sdtmig", "3-4", writeDefine("SDTM", "2024-09-27"), List.of(), ts);
        assertEquals(0, loads.get(), "a consumed-root declaration takes the TS out of play");

        // Sensitivity control: blank field, no define — the selection DOES read the TS (and its
        // 1999 date, absent from the store, aborts).
        assertThrows(StudyValidationException.class,
                () -> run("sdtmig", "3-4", null, List.of(), ts));
        assertEquals(1, loads.get(), "control: the TS source reads the member exactly once");
    }


    /** CQ6 (a) + fold F6: an unreadable TS — ANY unchecked failure — is "nothing from TS". */
    @Test
    void anUnreadableTrialSummaryDegradesToNothing() throws IOException
    {
        configureStore("sdtmig", "3-4", "sdtmct-2024-09-27");
        for (RuntimeException failure : List.of(new UncheckedIOException(new IOException("io")),
                new IllegalStateException("codec")))
        {
            StudyValidationResult result = run("sdtmig", "3-4", null, List.of(), () ->
            {
                throw failure;
            });
            assertEquals("", result.sections().conformanceDetails().get("CT_Version"),
                    failure.getClass().getSimpleName());
            assertNull(result.conformance().ctDeclarationMismatch(),
                    "CQ6: no note for an unreadable TS");
        }
    }


    /**
     * Fold F1: a define declaring only a root the run does not consume never puts the define in
     * play (CQ5 (a)); TS supplies, and the §4.5.1-ignored declaration is not flagged as a mismatch
     * against the TS-derived set.
     */
    @Test
    void unconsumedOnlyDefine_tsSupplies_noMismatchFlag() throws IOException
    {
        configureStore("sdtmig", "3-4", "sdtmct-2023-12-15");
        StudyValidationResult result = run("sdtmig", "3-4", writeDefine("DEFINE-XML", "2024-03-29"),
                List.of(),
                () -> TsTestSupport.sdtmTs(List.of(row("S1", "A", "x", "CDISC CT", "2023-12-15"))));

        assertEquals("sdtmct-2023-12-15", result.sections().conformanceDetails().get("CT_Version"));
        String note = result.conformance().ctDeclarationMismatch();
        assertNotNull(note);
        assertTrue(note.startsWith("CT Packages is blank"), "no 'define declares' part: " + note);
    }


    /** T4 + fold F3: only malformed CDISC-CT values → NONE, but the note lists them. */
    @Test
    void t4_malformedOnly_runsWithoutCt_andListsTheIgnoredValues() throws IOException
    {
        configureStore("sdtmig", "3-4", "sdtmct-2024-09-27");
        StudyValidationResult result = run("sdtmig", "3-4", null, List.of(),
                () -> TsTestSupport.sdtmTs(List.of(row("S1", "A", "x", "CDISC CT", "2023-02-30"))));

        assertEquals("", result.sections().conformanceDetails().get("CT_Version"));
        assertEquals(
                "Trial Summary CT value(s) ignored, not one valid ISO date (YYYY-MM-DD): "
                        + "TSVCDREF \"CDISC CT\", TSVCDVER \"2023-02-30\"",
                result.conformance().ctDeclarationMismatch());
    }


    /** CQ6: a TS without CDISC-CT rows adds nothing and no note (D3 row 1 unchanged). */
    @Test
    void aTrialSummaryWithoutCdiscCtRowsAddsNoNote() throws IOException
    {
        configureStore("sdtmig", "3-4", "sdtmct-2024-09-27");
        StudyValidationResult result = run("sdtmig", "3-4", null, List.of(),
                () -> TsTestSupport.sdtmTs(List.of(row("S1", "TRT", "DRUG A", "UNII", "2024"))));

        assertEquals("", result.sections().conformanceDetails().get("CT_Version"));
        assertNull(result.conformance().ctDeclarationMismatch());
    }

    // ------------------------------------------------------------------
    // Fixture plumbing
    // ------------------------------------------------------------------


    private StudyValidationResult run(String aStandard, String aVersion, @Nullable Path aDefine,
            List<String> aUserCts, Supplier<IDataTable> aTs)
        throws IOException
    {
        return run(aStandard, aVersion, aDefine, aUserCts, "TS", aTs);
    }


    private StudyValidationResult run(String aStandard, String aVersion, @Nullable Path aDefine,
            List<String> aUserCts, String aTsMember, Supplier<IDataTable> aTs)
        throws IOException
    {
        Map<String, Supplier<IDataTable>> members = new LinkedHashMap<>();
        members.put("DM", () -> TsTestSupport.table("DM", false, List.of("USUBJID"),
                List.of(row("SUBJ-001"))));
        members.put(aTsMember, aTs);
        IDataTableManager manager = TsTestSupport.manager(members, null);
        StudyValidationParams.Builder params = StudyValidationParams.builder().manager(manager)
                .dataLibrary(tempDir.toString()).datasetFilter(Set.of("DM"))
                .rulesDir(writeRules(aStandard, aVersion).toString())
                .rulesPackages(List.of(aStandard + "-" + aVersion))
                .metadataProducts(List.of("standards/" + aStandard + "/" + aVersion))
                .controlledTerminologyPackages(aUserCts);
        if (aDefine != null)
        {
            params.defineXmlPath(aDefine.toString());
        }
        return new StudyValidationService("0.0.0-test").validate(params.build());
    }


    private Path writeRules(String aStandard, String aVersion) throws IOException
    {
        Path dir = tempDir.resolve("rules-" + aStandard);
        if (Files.isDirectory(dir))
        {
            return dir;
        }
        Files.createDirectory(dir);
        String file = "rules-" + aStandard + "-" + aVersion + ".json";
        Files.writeString(dir.resolve(file), """
                {
                  "rules": {
                    "u1": {
                      "id": "u1",
                      "Core": {"Id": "CORE-TS-CT"},
                      "Check": {"expression": "var_exists(\\"USUBJID\\")"}
                    }
                  }
                }
                """);
        new RulePackageManifest("test", List.of(
                new RulePackageManifest.Entry(file, "CDISC", aStandard, aVersion, 1, List.of())))
                        .writeTo(dir);
        return dir;
    }


    private Path writeDefine(String aPublishingSet, String aVersion) throws IOException
    {
        Path define = tempDir.resolve("define-" + aPublishingSet + ".xml");
        Files.writeString(define, """
                <?xml version="1.0" encoding="UTF-8"?>
                <ODM xmlns:def="http://www.cdisc.org/ns/def/v2.1" ODMVersion="1.3.2"
                     FileType="Snapshot" FileOID="DEF.CT" CreationDateTime="2026-01-01T00:00:00">
                  <Study OID="S1">
                    <MetaDataVersion OID="MDV.1" Name="CT" DefineVersion="2.1.0">
                      <def:Standards>
                        <def:Standard OID="STD.1" Name="CDISC/NCI" Type="CT"
                                      PublishingSet="%s" Version="%s" Status="Final"/>
                      </def:Standards>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """.formatted(aPublishingSet, aVersion));
        return define;
    }


    /** A store with one IG product (dataset DM) and one NY-only CT package per given id. */
    private void configureStore(String aStandard, String aVersion, String... aCtIds)
        throws IOException
    {
        configureStore(aStandard, aVersion, List.of(aCtIds), List.of(aCtIds));
    }


    /** As above, with a published enumeration that may name packages the store does not hold. */
    private void configureStore(String aStandard, String aVersion, List<String> aCtIds,
            List<String> aPublished)
        throws IOException
    {
        String key = "standards/" + aStandard + "/" + aVersion;
        StoredVariable usubjid = StoredVariable.builder().name("USUBJID").ordinal("1").core("Req")
                .simpleDatatype("Char").build();
        StoredProduct ig = StoredProduct.builder().key(key).version(aVersion)
                .classes(List.of(new StoredClass(
                        "SpecialPurpose", null, "1", List.of(), List.of(new StoredDataset("DM",
                                "Demographics", "1", null, List.of(usubjid), null, null)),
                        null, List.of(), List.of(), List.of())))
                .build();
        MetadataStoreWriter writer = new MetadataStoreWriter().addProduct(ig);
        for (String id : aCtIds)
        {
            writer.addCtPackage(new StoredCtPackage(id,
                    List.of(new StoredCodelist("NY", "C66742", "No Yes Response", null, null, null,
                            Boolean.FALSE, List.of(new StoredTerm("N", "C49487", "No", null, null),
                                    new StoredTerm("Y", "C49488", "Yes", null, null))))));
        }
        Path file = tempDir.resolve("store-" + aStandard + ".zip");
        writer.publishedCtPackages(aPublished).productCatalogue(List.of(key)).write(file);
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, file.toString());
    }
}
