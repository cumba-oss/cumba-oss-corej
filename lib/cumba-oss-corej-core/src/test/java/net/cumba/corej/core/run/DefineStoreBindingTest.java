package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.metadata.store.MetadataStoreWriter;
import net.cumba.corej.core.metadata.store.StoreFormatException;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.metadata.store.StoredCodelist;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredTerm;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * N2b (PLAN-define-ct-evaluation §4.2): the D1 matrix, the R2 degradations, the T1-2 (a) aborts
 * (byte-equal to the dataset run's wording), the T1-10 (a) abort, and the D-2 order.
 */
class DefineStoreBindingTest
{

    private static final String SDTM_NEW = "sdtmct-2024-03-29";

    private static final String SDTM_OLD = "sdtmct-2023-12-15";

    private static final String DEFINE_NEW = "define-xmlct-2024-03-29";

    @TempDir
    Path tempDir;

    private static String define21(String aStandards)
    {
        return """
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                     xmlns:def="http://www.cdisc.org/ns/def/v2.1" FileOID="x" FileType="Snapshot"
                     ODMVersion="1.3.2" CreationDateTime="2026-01-01T00:00:00">
                  <Study OID="ST">
                    <GlobalVariables><StudyName>S</StudyName><StudyDescription>d</StudyDescription>
                      <ProtocolName>p</ProtocolName></GlobalVariables>
                    <MetaDataVersion OID="MDV" Name="m" def:DefineVersion="2.1.0">
                      <def:Standards>
                %s
                      </def:Standards>
                    </MetaDataVersion>
                  </Study>
                </ODM>
                """.formatted(aStandards);
    }

    private static final String DECLARES_BOTH = define21(
            """
                    <def:Standard OID="STD.CT1" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM" Version="2024-03-29" Status="Final"/>
                    <def:Standard OID="STD.CT2" Name="CDISC/NCI" Type="CT" PublishingSet="DEFINE-XML" Version="2024-03-29" Status="Final"/>
                    <def:Standard OID="STD.IG" Name="SDTMIG" Type="IG" Version="3.4" Status="Final"/>
                    """);

    private static final String DECLARES_MISSING = define21(
            """
                    <def:Standard OID="STD.CT1" Name="CDISC/NCI" Type="CT" PublishingSet="SDTM" Version="1999-01-01" Status="Final"/>
                    """);

    private static final String DEFINE_20 = """
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3" xmlns:def="http://www.cdisc.org/ns/def/v2.0"
                 FileOID="x" FileType="Snapshot" ODMVersion="1.3.2" CreationDateTime="2026-01-01T00:00:00">
              <Study OID="ST">
                <GlobalVariables><StudyName>S</StudyName><StudyDescription>d</StudyDescription>
                  <ProtocolName>p</ProtocolName></GlobalVariables>
                <MetaDataVersion OID="MDV" Name="m" def:DefineVersion="2.0.0"
                                 def:StandardName="SDTM-IG" def:StandardVersion="3.2"/>
              </Study>
            </ODM>
            """;

    private Path define(String aName, String aXml) throws IOException
    {
        return Files.writeString(tempDir.resolve(aName), aXml, StandardCharsets.UTF_8);
    }


    private static StoredCtPackage pkg(String aId, String aName, String aCode)
    {
        return new StoredCtPackage(aId,
                List.of(new StoredCodelist(aName.toUpperCase(java.util.Locale.ROOT), aCode, aName,
                        aName, null, null, Boolean.FALSE,
                        List.of(new StoredTerm("X", "C1", "X", null, null)))));
    }


    private Path store() throws IOException
    {
        Path file = tempDir.resolve("store.zip");
        new MetadataStoreWriter().addCtPackage(pkg(SDTM_NEW, "Sex", "C66731"))
                .addCtPackage(pkg(SDTM_OLD, "Sex", "C66731"))
                .addCtPackage(pkg(DEFINE_NEW, "Standard Name", "C170452"))
                .publishedCtPackages(List.of(SDTM_NEW, SDTM_OLD, DEFINE_NEW))
                .productCatalogue(List.of()).write(file);
        return file;
    }

    // ---- D1 ---------------------------------------------------------------------------------


    @Test
    void aUserListWinsOutrightOverTheDeclaration() throws IOException
    {
        try (DefineStoreBinding b = DefineStoreBinding.resolve(define("d.xml", DECLARES_BOTH),
                List.of(SDTM_OLD), store()))
        {
            assertEquals(CtSelection.Source.USER, b.selection().source());
            assertEquals(List.of(SDTM_OLD), b.selection().packageIds());
            assertTrue(b.ctProvider().isPresent());
            assertTrue(b.basis().startsWith("CT basis: " + SDTM_OLD + " ("), b.basis());
            assertTrue(b.basis().contains("explicit selection"), b.basis());
            assertTrue(b.libraryProvider().isPresent(), "the library half binds regardless");
        }
    }


    @Test
    void aBlankListTakesTheDeclarationInDefineXmlCtFirstOrder() throws IOException
    {
        try (DefineStoreBinding b = DefineStoreBinding.resolve(define("d.xml", DECLARES_BOTH),
                List.of(), store()))
        {
            assertEquals(CtSelection.Source.DEFINE, b.selection().source());
            assertTrue(b.ctProvider().isPresent());
            assertTrue(b.basis().startsWith("CT basis: " + DEFINE_NEW + ", " + SDTM_NEW + " ("),
                    "D-2: define-xmlct first: " + b.basis());
            assertTrue(b.basis().contains("def:Standards"), b.basis());
            assertTrue(b.ctProvider().orElseThrow().codelistByName("Standard Name").isPresent());
        }
    }


    @Test
    void aTwoZeroDefineNamesNothingSoTheCtRulesSkipButTheLibraryBinds() throws IOException
    {
        try (DefineStoreBinding b = DefineStoreBinding.resolve(define("d.xml", DEFINE_20),
                List.of(), store()))
        {
            assertEquals(CtSelection.Source.NONE, b.selection().source());
            assertTrue(b.ctProvider().isEmpty());
            assertTrue(b.basis().startsWith("CT basis: none - no CT package is named"), b.basis());
            assertTrue(b.basis().contains("SKIPPED_MISSING_CT"), b.basis());
            assertTrue(b.libraryProvider().isPresent(),
                    "T1-7 a: the library half binds whenever the store opens");
            assertTrue(b.libraryBasis().startsWith("Library basis: store "), b.libraryBasis());
        }
    }

    // ---- R2: no store / unopenable store ------------------------------------------------------


    @Test
    void noStoreLeavesBothHalvesUnboundEvenWithAUserList() throws IOException
    {
        try (DefineStoreBinding b = DefineStoreBinding.resolve(define("d.xml", DECLARES_BOTH),
                List.of("sdtmct-1999-01-01"), null))
        {
            assertTrue(b.ctProvider().isEmpty(), "R2, not D3: nothing serves, nothing aborts");
            assertTrue(b.libraryProvider().isEmpty());
            assertTrue(b.basis().contains("no metadata store is configured"), b.basis());
            assertTrue(b.libraryBasis().contains("no metadata store is configured"),
                    b.libraryBasis());
            assertTrue(b.libraryBasis().contains("SKIPPED_MISSING_LIBRARY"), b.libraryBasis());
        }
    }


    @Test
    void aStoreThatIsNotAZipLeavesBothHalvesUnboundWithTheReason() throws IOException
    {
        Path bogus = Files.writeString(tempDir.resolve("bogus.zip"), "not a store");
        try (DefineStoreBinding b = DefineStoreBinding.resolve(define("d.xml", DECLARES_BOTH),
                List.of(), bogus))
        {
            assertTrue(b.ctProvider().isEmpty());
            assertTrue(b.libraryProvider().isEmpty());
            assertTrue(b.basis().contains("cannot be opened"), b.basis());
            assertTrue(b.basis().contains(bogus.toString()), b.basis());
        }
    }

    // ---- T1-2 (a): a NAMED package the store lacks aborts, worded as the dataset run ---------


    @Test
    void aDeclaredPackageTheStoreLacksAbortsNamingTheEscapeHatch() throws IOException
    {
        Path store = store();
        Path define = define("d.xml", DECLARES_MISSING);
        StudyValidationException abort = assertThrows(StudyValidationException.class,
                () -> DefineStoreBinding.resolve(define, List.of(), store),
                "T1-2 (a): a declared package the store does not hold ABORTS the define run");
        assertTrue(abort.getMessage().contains("sdtmct-1999-01-01"), abort.getMessage());
        assertTrue(abort.getMessage().contains(store.toString()), abort.getMessage());
        assertTrue(abort.getMessage().contains("fill the CT Packages field"), abort.getMessage());
        assertEquals(
                datasetRunMessage(store, List.of("sdtmct-1999-01-01"), CtSelection.Source.DEFINE),
                abort.getMessage(),
                "byte-equal to the dataset run's wording (one helper, CtPresence)");
    }


    @Test
    void aUserPackageTheStoreLacksAbortsWithTheRequestedWording() throws IOException
    {
        Path store = store();
        Path define = define("d.xml", DECLARES_BOTH);
        StudyValidationException abort = assertThrows(StudyValidationException.class,
                () -> DefineStoreBinding.resolve(define, List.of(SDTM_NEW, "sdtmct-1999-01-01"),
                        store));
        assertTrue(abort.getMessage().contains("'sdtmct-1999-01-01' was requested"),
                abort.getMessage());
        assertEquals(datasetRunMessage(store, List.of(SDTM_NEW, "sdtmct-1999-01-01"),
                CtSelection.Source.USER), abort.getMessage());
    }


    @Test
    void aMalformedUserIdAbortsWithTheMalformedWording() throws IOException
    {
        Path store = store();
        Path define = define("d.xml", DECLARES_BOTH);
        StudyValidationException abort = assertThrows(StudyValidationException.class,
                () -> DefineStoreBinding.resolve(define, List.of("SDTMCT-2024"), store));
        assertTrue(abort.getMessage().contains("is not a valid CT package id"), abort.getMessage());
        assertEquals(datasetRunMessage(store, List.of("SDTMCT-2024"), CtSelection.Source.USER),
                abort.getMessage());
    }


    /** The dataset run's own wording for the same input, captured from its helper. */
    private static String datasetRunMessage(Path aStore, List<String> aIds,
            CtSelection.Source aSource)
        throws IOException
    {
        StoreMetadataProviderFactory factory = StoreMetadataProviderFactory.open(aStore);
        StudyValidationException e = assertThrows(StudyValidationException.class,
                () -> StudyValidationService.requireNamedCtPackagesPresent(factory, aStore, aIds,
                        aSource));
        return e.getMessage();
    }

    // ---- T1-10 (a) ---------------------------------------------------------------------------


    @Test
    void anOldFormatStoreAbortsOnEverySurfaceNamingTheReseed() throws IOException
    {
        Path old = tempDir.resolve("format-v2.zip");
        try (InputStream in = getClass().getResourceAsStream("/metadata/store/format-v2.zip"))
        {
            assertNotNull(in);
            Files.copy(in, old);
        }
        Path define = define("d.xml", DEFINE_20);
        StudyValidationException abort = assertThrows(StudyValidationException.class,
                () -> DefineStoreBinding.resolve(define, List.of(), old),
                "even with nothing named, an old-format store aborts (T1-10 a)");
        assertTrue(abort.getMessage().contains("format 2"), abort.getMessage());
        assertTrue(abort.getMessage().contains("re-seed"), abort.getMessage());
        assertTrue(abort.getCause() instanceof StoreFormatException);
    }

    // ---- D-2 and D-8 -------------------------------------------------------------------------


    @Test
    void precedenceOrderPutsDefineXmlCtFirstThenRootsInAppearanceOrderNewestFirst()
    {
        assertEquals(
                List.of("define-xmlct-2024-03-29", "define-xmlct-2020-12-18", "sdtmct-2024-03-29",
                        "sdtmct-2023-12-15", "adamct-2024-03-29"),
                DefineStoreBinding.precedenceOrder(
                        List.of("sdtmct-2023-12-15", "adamct-2024-03-29", "define-xmlct-2020-12-18",
                                "sdtmct-2024-03-29", "define-xmlct-2024-03-29")));
        assertEquals(List.of(), DefineStoreBinding.precedenceOrder(List.of()));
    }


    @Test
    void anUnparseableDefineDeclaresNothingAndDoesNotThrow() throws IOException
    {
        Path broken = define("broken.xml", "<ODM><unclosed>");
        try (DefineStoreBinding b = DefineStoreBinding.resolve(broken, List.of(), store()))
        {
            assertEquals(CtSelection.Source.NONE, b.selection().source(), "D-8");
            assertFalse(b.ctProvider().isPresent());
            assertTrue(b.libraryProvider().isPresent());
        }
    }
}
