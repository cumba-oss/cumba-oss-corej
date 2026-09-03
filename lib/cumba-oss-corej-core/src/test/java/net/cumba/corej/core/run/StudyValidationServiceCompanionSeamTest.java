package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.CompanionDomainsProvider;
import net.cumba.corej.core.run.StudyValidationService.StandardKind;
import net.cumba.datatable.manager.IDataTableManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * EC-14 layer (ii) — branch coverage for {@link StudyValidationService#maybeWrapCompanion} /
 * {@code companionFromPickle} without a real pickle cache: an empty {@code @TempDir} stands in for
 * a configured-but-productless cache, and the {@code apiLoader} seam supplies (or withholds) the
 * companion. The end-to-end pickle path lives in the rulespec module's
 * {@code StudyValidationServiceCompanionTest}, next to the bundled cache.
 */
class StudyValidationServiceCompanionSeamTest
{

    @TempDir
    Path emptyCacheDir;

    private StudyValidationParams.Builder base()
    {
        return StudyValidationParams.builder().manager(mock(IDataTableManager.class))
                .dataLibrary("x").pickleCacheDir(emptyCacheDir.toString());
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
        MetadataProvider companion = mock(MetadataProvider.class);

        MetadataProvider wrapped = StudyValidationService.maybeWrapCompanion(runProvider, params,
                StandardKind.ADAM, params.metadataProducts(), _ -> companion);

        assertSame(runProvider, wrapped,
                "R10: no declared companion means no wrap — never a guessed sdtmig 3-4");
    }


    /** A declared companion (what a rules package now contributes via R7) still wraps. */
    @Test
    void aDeclaredCompanionProductWrapsTheProvider()
    {
        StudyValidationParams params = base()
                .metadataProducts(List.of("standards/adam/adamig-1-3", "standards/sdtmig/3-4"))
                .build();
        MetadataProvider runProvider = mock(MetadataProvider.class);
        MetadataProvider companion = mock(MetadataProvider.class);
        when(companion.getStandardDatasetNames()).thenReturn(List.of("DM"));

        MetadataProvider wrapped = StudyValidationService.maybeWrapCompanion(runProvider, params,
                StandardKind.ADAM, params.metadataProducts(), _ -> companion);

        assertInstanceOf(CompanionDomainsProvider.class, wrapped);
    }


    @Test
    void adamRun_noCompanionAnywhere_returnsTheBaseUnwrapped()
    {
        StudyValidationParams params = base().metadataProducts(List.of("standards/adam/adamig-1-3"))
                .build();
        MetadataProvider runProvider = mock(MetadataProvider.class);

        assertSame(runProvider,
                StudyValidationService.maybeWrapCompanion(runProvider, params, StandardKind.ADAM,
                        params.metadataProducts(), _ -> null),
                "no companion must degrade, not fail");
        assertSame(runProvider,
                StudyValidationService.maybeWrapCompanion(runProvider, params, StandardKind.ADAM,
                        params.metadataProducts(), null),
                "a null apiLoader must degrade the same way");
    }


    /**
     * A declared TIG {@code adam} leg still routes the run into companion resolution (that gate is
     * unchanged) — but since R9 retired the leg-to-leg derivation, the {@code sdtm} leg must be
     * declared too. That is exactly what a TIG rules package now does: it declares all four legs as
     * primaries, and R7 appends them to the effective product list.
     */
    @Test
    void declaredTigLegs_makeTheRunAdamFamilyAndWrap()
    {
        StudyValidationParams params = base()
                .metadataProducts(List.of("standards/tig/1-0/adam", "standards/tig/1-0/sdtm"))
                .build();
        MetadataProvider runProvider = mock(MetadataProvider.class);
        MetadataProvider companion = mock(MetadataProvider.class);

        MetadataProvider wrapped = StudyValidationService.maybeWrapCompanion(runProvider, params,
                StandardKind.UNKNOWN, params.metadataProducts(), _ -> companion);

        assertInstanceOf(CompanionDomainsProvider.class, wrapped);
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
        MetadataProvider companion = mock(MetadataProvider.class);

        assertSame(runProvider,
                StudyValidationService.maybeWrapCompanion(runProvider, params, StandardKind.UNKNOWN,
                        params.metadataProducts(), _ -> companion),
                "the adam leg alone must not derive standards/tig/1-0/sdtm any more");
    }


    @Test
    void nonAdamRun_isReturnedUntouched()
    {
        StudyValidationParams params = base().metadataProducts(List.of("standards/sdtmig/3-4"))
                .build();
        MetadataProvider runProvider = mock(MetadataProvider.class);
        assertSame(runProvider, StudyValidationService.maybeWrapCompanion(runProvider, params,
                StandardKind.SDTM, params.metadataProducts(), null));
    }

    // ------------------------------------------------------------------
    // Phase 5 — a declared SDTM product reaches the companion, and NOTHING else
    // ------------------------------------------------------------------


    @Test
    void aDeclaredSdtmProductReachesTheCompanionLoader()
    {
        // The house table used to map an adamig 1-3 run to sdtmig 3-4. The declaration must be
        // what the loader is asked for — otherwise ruling 6 is wired but inert.
        StudyValidationParams params = base()
                .metadataProducts(List.of("standards/adam/adamig-1-3", "standards/sdtmig/3-1-1"))
                .build();
        List<CompanionSdtmDefaults.Companion> asked = new ArrayList<>();

        StudyValidationService.maybeWrapCompanion(mock(MetadataProvider.class), params,
                StandardKind.ADAM, params.metadataProducts(), c ->
                {
                    asked.add(c);
                    return mock(MetadataProvider.class);
                });

        assertEquals(1, asked.size());
        assertEquals("sdtmig", asked.get(0).loaderStandard());
        assertEquals("3-1-1", asked.get(0).loaderVersion());
        assertTrue(asked.get(0).declared());
        assertFalse(asked.get(0).defaulted());
    }


    @Test
    void theDeclaredSdtmSurfaceStaysNarrow()
    {
        // ⛔ §2.4's regression guard. Declaring an SDTM product on an ADaM run must change exactly
        // ONE accessor. Injecting the product into MetadataLibraryProvider instead would flip
        // hasSdtmProduct() and change how ADaM required/expected/column-order resolve, as a side
        // effect of naming an SDTM version.
        StudyValidationParams params = base().metadataProducts(List.of("standards/sdtmig/3-1-1"))
                .build();
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

        MetadataProvider wrapped = StudyValidationService.maybeWrapCompanion(runProvider, params,
                StandardKind.ADAM, params.metadataProducts(), _ -> companion);

        assertInstanceOf(CompanionDomainsProvider.class, wrapped);
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
                StandardKind.SDTM, params.metadataProducts(), _ -> mock(MetadataProvider.class)));
    }
}
