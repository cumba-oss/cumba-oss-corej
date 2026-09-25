package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.CompanionDomainsProvider;
import net.cumba.corej.core.run.StudyValidationService.StandardKind;
import net.cumba.datatable.manager.IDataTableManager;
import org.junit.jupiter.api.Test;

/**
 * EC-14 layer (ii) — branch coverage for {@link StudyValidationService#maybeWrapCompanion} without
 * a configured metadata store: {@code companionFromStore} resolves nothing (no
 * {@code CDISC_METADATA_STORE} / {@code cdisc.metadata.store} in a unit-test JVM), so the
 * {@code apiLoader} seam supplies (or withholds) the companion. (Until cache 8g the middle leg was
 * {@code companionFromPickle} over an empty {@code @TempDir} cache; that leg is deleted.) The
 * end-to-end store path lives in the rulespec module's {@code StudyValidationServiceCompanionTest},
 * next to the seeded store.
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
}
