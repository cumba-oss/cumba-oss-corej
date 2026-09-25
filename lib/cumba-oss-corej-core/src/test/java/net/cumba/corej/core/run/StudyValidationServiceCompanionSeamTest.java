package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.CompanionDomainsProvider;
import net.cumba.corej.core.metadata.store.MetadataStoreWriter;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.run.StudyValidationService.StandardKind;
import net.cumba.datatable.manager.IDataTableManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * EC-14 layer (ii) — {@link StudyValidationService#maybeWrapCompanion}: an ADaM-family run (an
 * {@code ADAM} kind, or a run whose first declared TIG leg is {@code adam}) is wrapped in a
 * {@link CompanionDomainsProvider} when a companion SDTM product is declared <b>and</b> the
 * metadata store serves it; every other run is returned unwrapped.
 *
 * <p>
 * Two kinds of test live here. The ones that configure no store can only ever observe the
 * <b>unwrapped</b> answer — since U11 of {@code PLAN-retire-dead-multi-match-lookup} the store is
 * the only companion source, so without one they cannot tell the family gate from the store lookup
 * and pin the "degrade, never fail" direction alone. The family gate itself is pinned by the
 * {@code gate*} tests, which hand the run a hermetic synthetic store
 * ({@link StudyValidationParams#metadataStore()}) that DOES serve {@code sdtmig/3-4}: there the
 * only thing standing between a run and the wrap is the gate, in both directions. The end-to-end
 * store path over the seeded corpus store lives in the rule-corpus repository's
 * {@code StudyValidationServiceCompanionTest}.
 * </p>
 */
class StudyValidationServiceCompanionSeamTest
{

    private StudyValidationParams.Builder base()
    {
        return StudyValidationParams.builder().manager(mock(IDataTableManager.class))
                .dataLibrary("x");
    }


    /**
     * ⛔ <b>R10 — this test's subject was DELETED, and it now asserts the opposite.</b> It used to
     * be {@code unmappedAdamProduct_defaults_thenWrapsTheApiLoadedCompanion}: {@code adamig 9-9}
     * was unmapped, fell back to {@code DEFAULT_COMPANION_SDTMIG = "3-4"} and got wrapped with a
     * warning (Q-12d). There is no fallback any more — an ADaM product declaring no companion
     * resolves to none, so the provider comes back <b>unwrapped</b> and {@code standard_domains}
     * rules SKIP loudly instead of validating against a guessed SDTMIG.
     */
    @Test
    void unmappedAdamProduct_noLongerDefaults_soTheProviderIsNotWrapped()
    {
        StudyValidationParams params = base().metadataProducts(List.of("standards/adam/adamig-9-9"))
                .build();
        MetadataProvider runProvider = mock(MetadataProvider.class);

        MetadataProvider wrapped = StudyValidationService.maybeWrapCompanion(runProvider, params,
                StandardKind.ADAM, params.metadataProducts());

        assertSame(runProvider, wrapped,
                "R10: no declared companion means no wrap — never a guessed sdtmig 3-4");
    }


    @Test
    void adamRun_noCompanionAnywhere_returnsTheBaseUnwrapped()
    {
        StudyValidationParams params = base().metadataProducts(List.of("standards/adam/adamig-1-3"))
                .build();
        MetadataProvider runProvider = mock(MetadataProvider.class);

        assertSame(
                runProvider, StudyValidationService.maybeWrapCompanion(runProvider, params,
                        StandardKind.ADAM, params.metadataProducts()),
                "no companion must degrade, not fail");
    }


    /**
     * ⛔ R9 — the TIG {@code adam} leg ALONE no longer conjures the {@code sdtm} leg. The run is
     * still ADaM-family (the gate is unchanged), but with nothing declared there is no companion.
     */
    @Test
    void aTigAdamLegAlone_noLongerDerivesTheSdtmLeg()
    {
        StudyValidationParams params = base().metadataProducts(List.of("standards/tig/1-0/adam"))
                .build();
        MetadataProvider runProvider = mock(MetadataProvider.class);

        assertNull(CompanionSdtmDefaults.resolve(params.metadataProducts()),
                "the adam leg alone must not derive standards/tig/1-0/sdtm any more");
        assertSame(runProvider, StudyValidationService.maybeWrapCompanion(runProvider, params,
                StandardKind.UNKNOWN, params.metadataProducts()));
    }


    @Test
    void nonAdamRun_isReturnedUntouched()
    {
        StudyValidationParams params = base().metadataProducts(List.of("standards/sdtmig/3-4"))
                .build();
        MetadataProvider runProvider = mock(MetadataProvider.class);
        assertSame(runProvider, StudyValidationService.maybeWrapCompanion(runProvider, params,
                StandardKind.SDTM, params.metadataProducts()));
    }

    // ------------------------------------------------------------------
    // Phase 5 — a declared SDTM product reaches the companion, and NOTHING else
    // ------------------------------------------------------------------


    @Test
    void aDeclaredSdtmProductIsTheCompanionAskedFor()
    {
        // The house table used to map an adamig 1-3 run to sdtmig 3-4. The declaration must be
        // what the store is asked for — otherwise ruling 6 is wired but inert. (Until U11 of
        // PLAN-retire-dead-multi-match-lookup this captured the Companion through the API loader
        // seam; that parameter is gone, and maybeWrapCompanion asks the store for exactly the
        // resolve() answer.)
        List<String> products = List.of("standards/adam/adamig-1-3", "standards/sdtmig/3-1-1");

        CompanionSdtmDefaults.Companion asked = CompanionSdtmDefaults.resolve(products);

        assertNotNull(asked);
        assertEquals("sdtmig", asked.loaderStandard());
        assertEquals("3-1-1", asked.loaderVersion());
    }


    @Test
    void theDeclaredSdtmSurfaceStaysNarrow()
    {
        // ⛔ §2.4's regression guard. Declaring an SDTM product on an ADaM run must change exactly
        // ONE accessor. Injecting the product into MetadataLibraryProvider instead would flip
        // hasSdtmProduct() and change how ADaM required/expected/column-order resolve, as a side
        // effect of naming an SDTM version.
        MetadataProvider runProvider = mock(MetadataProvider.class);
        when(runProvider.getRequiredVariablesForStructure("BASIC DATA STRUCTURE", List.of()))
                .thenReturn(List.of("USUBJID", "PARAMCD"));
        when(runProvider.getRequiredVariables("ADSL")).thenReturn(List.of("STUDYID"));
        when(runProvider.getColumnOrder("ADSL")).thenReturn(List.of("STUDYID", "USUBJID"));
        MetadataProvider companion = mock(MetadataProvider.class);
        when(companion.getStandardDatasetNames()).thenReturn(List.of("DM", "AE"));
        when(companion.getRequiredVariablesForStructure("BASIC DATA STRUCTURE", List.of()))
                .thenReturn(List.of("NEVER", "REACHED"));
        when(companion.getRequiredVariables("ADSL")).thenReturn(List.of("NEVER"));

        // The wrap itself is store-backed and pinned by the rules repository's
        // StudyValidationServiceCompanionTest; this is the wrapper's surface.
        MetadataProvider wrapped = new CompanionDomainsProvider(runProvider, companion);

        assertEquals(List.of("DM", "AE"), wrapped.getStandardDatasetNames(),
                "the one accessor the companion answers");
        assertEquals(List.of("USUBJID", "PARAMCD"),
                wrapped.getRequiredVariablesForStructure("BASIC DATA STRUCTURE", List.of()),
                "ADaM variable resolution must be untouched by a declared SDTM product");
        assertEquals(List.of("STUDYID"), wrapped.getRequiredVariables("ADSL"));
        assertEquals(List.of("STUDYID", "USUBJID"), wrapped.getColumnOrder("ADSL"));
    }


    @Test
    void aDeclaredSdtmProductOnANonAdamRunChangesNothing()
    {
        // The companion exists for ADaM runs only; an SDTM run already has its own product.
        StudyValidationParams params = base().metadataProducts(List.of("standards/sdtmig/3-1-1"))
                .build();
        MetadataProvider runProvider = mock(MetadataProvider.class);

        assertSame(runProvider, StudyValidationService.maybeWrapCompanion(runProvider, params,
                StandardKind.SDTM, params.metadataProducts()));
    }

    // ------------------------------------------------------------------
    // The ADaM-family gate, observed through a store that DOES serve the companion
    // ------------------------------------------------------------------


    /** Positive control: with the store serving sdtmig/3-4, an ADaM run IS wrapped. */
    @Test
    void gateAdmitsAnAdamRun(@TempDir Path dir) throws IOException
    {
        StudyValidationParams params = withCompanionStore(dir);
        List<String> effective = List.of("standards/adam/adamig-1-3", "standards/sdtmig/3-4");
        MetadataProvider runProvider = mock(MetadataProvider.class);

        MetadataProvider wrapped = StudyValidationService.maybeWrapCompanion(runProvider, params,
                StandardKind.ADAM, effective);

        CompanionDomainsProvider companion = assertInstanceOf(CompanionDomainsProvider.class,
                wrapped, "the store serves the declared companion, so an ADaM run is wrapped");
        assertEquals(List.of("DM"), companion.getStandardDatasetNames(),
                "the companion's domains come from the store");
    }


    /**
     * The gate's negative direction, made observable: an SDTM run declaring the same SDTM product,
     * against the same store, is NOT wrapped — the store would serve it, so only the gate refuses.
     */
    @Test
    void gateRefusesAnSdtmRunEvenWhenTheStoreServesTheCompanion(@TempDir Path dir)
        throws IOException
    {
        StudyValidationParams params = withCompanionStore(dir);
        List<String> effective = List.of("standards/sdtmig/3-4");
        assertNotNull(CompanionSdtmDefaults.resolve(effective),
                "precondition: the SDTM product resolves as a companion, so only the gate decides");
        MetadataProvider runProvider = mock(MetadataProvider.class);

        assertSame(runProvider, StudyValidationService.maybeWrapCompanion(runProvider, params,
                StandardKind.SDTM, effective), "an SDTM run is never wrapped");
    }


    /**
     * The gate's TIG arm: a run whose first declared TIG leg is {@code adam} is ADaM-family even
     * though its kind is not {@code ADAM}, so with a declared SDTM product it IS wrapped.
     */
    @Test
    void gateAdmitsATigAdamRun(@TempDir Path dir) throws IOException
    {
        StudyValidationParams params = withCompanionStore(dir);
        List<String> effective = List.of("standards/tig/1-0/adam", "standards/sdtmig/3-4");
        MetadataProvider runProvider = mock(MetadataProvider.class);

        assertInstanceOf(
                CompanionDomainsProvider.class, StudyValidationService
                        .maybeWrapCompanion(runProvider, params, StandardKind.UNKNOWN, effective),
                "a TIG adam-leg run is ADaM-family, so it is wrapped");
    }


    /** The same UNKNOWN-kind run with no TIG adam leg is not ADaM-family, and is not wrapped. */
    @Test
    void gateRefusesAnUnknownRunWithoutATigAdamLeg(@TempDir Path dir) throws IOException
    {
        StudyValidationParams params = withCompanionStore(dir);
        List<String> effective = List.of("standards/sdtmig/3-4");
        MetadataProvider runProvider = mock(MetadataProvider.class);

        assertSame(runProvider, StudyValidationService.maybeWrapCompanion(runProvider, params,
                StandardKind.UNKNOWN, effective));
    }


    /** Run parameters naming a hermetic store that carries exactly {@code sdtmig/3-4} (DM). */
    private StudyValidationParams withCompanionStore(Path dir) throws IOException
    {
        Path file = dir.resolve("store.zip");
        StoredVariable studyid = StoredVariable.builder().name("STUDYID").ordinal("1").core("Req")
                .simpleDatatype("Char").build();
        StoredProduct ig = StoredProduct.builder().key("standards/sdtmig/3-4").version("3-4")
                .classes(List.of(new StoredClass("SpecialPurpose", null, "1", List.of(), List
                        .of(new StoredDataset("DM", "Demographics", "1", null, List.of(studyid))))))
                .build();
        new MetadataStoreWriter().addProduct(ig).publishedCtPackages(List.of())
                .productCatalogue(List.of("standards/sdtmig/3-4")).write(file);
        return base().metadataStore(file.toString()).build();
    }
}
