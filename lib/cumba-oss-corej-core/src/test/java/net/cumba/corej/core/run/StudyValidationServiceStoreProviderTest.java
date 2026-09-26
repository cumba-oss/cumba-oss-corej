package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.store.MetadataStoreWriter;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredCodelist;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredTerm;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import net.cumba.corej.core.run.StudyValidationService.StandardKind;
import net.cumba.datatable.manager.IDataTableManager;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Cache P3/P4 wiring — {@link StudyValidationService#tryStoreProvider} consults the unified
 * metadata store when {@code cdisc.metadata.store} names one, and answers {@code null} — since P4
 * that means the run DEGRADES (library-dependent rules SKIP; there is no other metadata path) —
 * when nothing is configured, the file is not a store, or the store lacks the run's product.
 * Hermetic: the store is a synthetic fixture written per test.
 */
class StudyValidationServiceStoreProviderTest
{

    private static final List<String> PUBLISHED = List.of("sdtmct-2024-09-27", "sdtmct-2025-03-28",
            "adamct-2024-03-29");

    @TempDir
    private Path temp;

    @AfterEach
    void clearConfiguredStore()
    {
        System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
    }


    @Test
    void noConfiguredStoreAnswersNull()
    {
        assertNull(tryStore(params(), StandardKind.SDTM, List.of(),
                RunStandard.of("standards/sdtmig/3-4")));
    }


    @Test
    void aConfiguredStoreServesAnSdtmRunWithTheFullPublishedEnumeration() throws IOException
    {
        configureStore();
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(mock(IDataTableManager.class)).dataLibrary("x")
                .controlledTerminologyPackages(List.of("sdtmct-2024-09-27")).build();

        MetadataProvider p = tryStore(params, StandardKind.SDTM, List.of(),
                RunStandard.of("standards/sdtmig/3-4"));

        assertNotNull(p, "a configured store carrying the product must serve the run");
        assertEquals("sdtmig", p.getStandard());
        // The §1.1-1 fix on the wired path: all published packages, not the one requested.
        assertEquals(PUBLISHED, p.getPublishedCtPackages());
        assertEquals(List.of("N", "Y"), p.getCodelistTerms("NY").stream().sorted().toList());
    }


    @Test
    void aConfiguredStoreServesAnAdamRun() throws IOException
    {
        configureStore();
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(mock(IDataTableManager.class)).dataLibrary("x")
                .metadataProducts(List.of("standards/adam/adamig-1-3")).build();

        MetadataProvider p = tryStore(params, StandardKind.ADAM, params.metadataProducts(),
                RunStandard.of("standards/adam/adamig-1-3"));

        assertNotNull(p);
        assertTrue(p.supportsStructureKeyedVariables());
        assertEquals(List.of("standards/adam/adamig-1-3"), p.declaredStructureKeyedProducts());
        assertEquals(PUBLISHED, p.getPublishedCtPackages());
    }


    @Test
    void aStoreLackingTheProductAnswersNullSoTheRunDegrades() throws IOException
    {
        configureStore();
        assertNull(tryStore(params(), StandardKind.SDTM, List.of(),
                RunStandard.of("standards/sdtmig/9-9")));
    }


    @Test
    void anUnreadableConfiguredStoreAnswersNullInsteadOfAbortingTheRun() throws IOException
    {
        Path bogus = temp.resolve("bogus.zip");
        Files.writeString(bogus, "this is not a metadata store");
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, bogus.toString());
        assertNull(tryStore(params(), StandardKind.SDTM, List.of(),
                RunStandard.of("standards/sdtmig/3-4")));
    }


    /**
     * T1-10 (a), PLAN-define-ct-evaluation: an OLD-FORMAT store is the one store-open failure that
     * is NOT the R2 degraded run. A format bump hits every user on the same upgrade, and under R2
     * every one of them would silently lose the library-dependent rules until they read a log — so
     * the run ABORTS, naming the file, the format found and "re-seed". The store that is not a zip
     * (above) still degrades: that is a per-user accident, and R2 is unchanged for it.
     */
    @Test
    void aFormat2StoreAbortsTheRunNamingTheReseed() throws IOException
    {
        Path old = temp.resolve("format-v2.zip");
        try (java.io.InputStream in = getClass()
                .getResourceAsStream("/metadata/store/format-v2.zip"))
        {
            assertNotNull(in, "the committed format-2 fixture is on the test classpath");
            Files.copy(in, old);
        }
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, old.toString());

        StudyValidationException abort = org.junit.jupiter.api.Assertions.assertThrows(
                StudyValidationException.class,
                () -> tryStore(params(), StandardKind.SDTM, List.of(),
                        RunStandard.of("standards/sdtmig/3-4")),
                "an old-format store must ABORT the run (T1-10 a), never degrade it (R2)");
        assertTrue(abort.getMessage().contains(old.toString()), abort.getMessage());
        assertTrue(abort.getMessage().contains("format 2"), abort.getMessage());
        assertTrue(abort.getMessage().contains("re-seed"), abort.getMessage());
        assertTrue(abort
                .getCause() instanceof net.cumba.corej.core.metadata.store.StoreFormatException,
                "the typed cause travels, so every surface can append its own remedy");
    }


    /**
     * D-27 (review round 2, L1): a corrupt product entry binds lazily, so it surfaces on the
     * factory's first access, not at open. Disposition: the R2 degraded run (null, loud), never an
     * escaping UncheckedIOException — and the companion path degrades the same way.
     */
    @Test
    void aCorruptProductEntryDegradesTheRunInsteadOfEscaping() throws IOException
    {
        Path intact = temp.resolve("intact.zip");
        writeStore(intact);
        Path damaged = net.cumba.corej.core.metadata.store.CorruptStores.withMismatchingProductKey(
                intact, temp.resolve("damaged.zip"), "standards/sdtmig/3-4");
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, damaged.toString());

        assertNull(tryStore(params(), StandardKind.SDTM, List.of("standards/sdtmig/3-4"),
                new RunStandard("sdtmig", "sdtmig", "3-4")), "degrade, never throw");
        assertNull(StudyValidationService.companionFromStore(
                new CompanionSdtmDefaults.Companion("sdtmig", "3-4", "SDTMIG 3.4"), null));
    }


    private StudyValidationParams params()
    {
        return StudyValidationParams.builder().manager(mock(IDataTableManager.class))
                .dataLibrary("x").build();
    }


    /**
     * ⭐ F2 (final cross-plan review) — a store the caller NAMED on the run's own parameters must
     * outrank the ambient configuration. At baseline the dialog fields reached the engine as real
     * parameters no environment variable could outrank; the store rework left the system property
     * as the surfaces' only channel, and that is the LOSING tier of {@code resolveConfiguredFile}'s
     * precedence — so an ambient {@code CDISC_METADATA_STORE} silently overrode the store the user
     * typed. The environment variable cannot be set in-process, so the ambient tier here is the
     * system property — one rank BELOW the environment: a named store that outranks even the
     * property proves nothing, but one that LOSES to it (the pre-fix behaviour:
     * {@code tryStoreProvider} ignored the params slot and resolved the ambient store, which lacks
     * the product, answering {@code null}) loses to the environment a fortiori.
     */
    @Test
    void aStoreNamedOnTheRunsOwnParamsOutranksTheAmbientConfiguration() throws IOException
    {
        // The ambient tier names a store that cannot serve the run (no products at all)...
        Path ambient = temp.resolve("ambient-store.zip");
        new MetadataStoreWriter().publishedCtPackages(List.of()).productCatalogue(List.of())
                .write(ambient);
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, ambient.toString());
        // ...while the run's own parameters name the populated one.
        Path named = temp.resolve("named-store.zip");
        writeStore(named);
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(mock(IDataTableManager.class)).dataLibrary("x")
                .metadataStore(named.toString()).build();

        MetadataProvider p = tryStore(params, StandardKind.SDTM, List.of(),
                RunStandard.of("standards/sdtmig/3-4"));

        assertNotNull(p, "the store named on the run's own parameters must serve the run — "
                + "resolving the ambient store instead is the F2 precedence inversion");
        assertEquals("sdtmig", p.getStandard());
        assertEquals(PUBLISHED, p.getPublishedCtPackages());
    }


    private void configureStore() throws IOException
    {
        Path file = temp.resolve("store.zip");
        writeStore(file);
        System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, file.toString());
    }


    private static void writeStore(Path file) throws IOException
    {
        StoredVariable studyid = StoredVariable.builder().name("STUDYID").ordinal("1").core("Req")
                .simpleDatatype("Char").build();
        StoredProduct ig = StoredProduct.builder().key("standards/sdtmig/3-4").version("3-4")
                .classes(List.of(new StoredClass(
                        "SpecialPurpose", null, "1", List.of(), List.of(new StoredDataset("DM",
                                "Demographics", "1", null, List.of(studyid), null, null)),
                        null, List.of(), List.of(), List.of())))
                .build();
        StoredProduct adam = StoredProduct.builder().key("standards/adam/adamig-1-3").version("1-3")
                .dataStructures(
                        List.of(new StoredDataStructure("ADSL", null, "1",
                                "SUBJECT LEVEL ANALYSIS DATASET", null,
                                List.of(new StoredVariableSet("Identifier", null, "1",
                                        List.of(StoredVariable.builder().name("USUBJID")
                                                .ordinal("1").core("Req").build()),
                                        null)),
                                null)))
                .build();
        StoredCtPackage ct = new StoredCtPackage("sdtmct-2024-09-27",
                List.of(new StoredCodelist("NY", "C66742", "No Yes Response", null, null, null,
                        Boolean.FALSE, List.of(new StoredTerm("N", "C49487", "No", null, null),
                                new StoredTerm("Y", "C49488", "Yes", null, null)))));
        new MetadataStoreWriter().addProduct(ig).addProduct(adam).addCtPackage(ct)
                .publishedCtPackages(PUBLISHED)
                .productCatalogue(List.of("standards/sdtmig/3-4", "standards/adam/adamig-1-3"))
                .write(file);
    }


    /** The retired 4-argument form: the CT selection defaulted to the user's field alone (U13). */
    private static @Nullable MetadataProvider tryStore(StudyValidationParams aParams,
            StandardKind aKind, List<String> aProducts, RunStandard aRunStandard)
    {
        return StudyValidationService.tryStoreProvider(aParams, aKind, aProducts, aRunStandard,
                CtSelection.resolve(aParams.controlledTerminologyPackages(), List.of()));
    }

}
