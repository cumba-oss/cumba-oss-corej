package net.cumba.corej.core.run;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.run.StudyValidationService.StandardKind;
import net.cumba.datatable.manager.IDataTableManager;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Phase 7 of {@code plans/PLAN-metadata-product-selection.md} —
 * {@link StudyValidationService#tryPickleProvider} now serves <b>both</b> families (7a) and its
 * SDTM library layer follows the first declared SDTM-family product (§7-0). Environment-gated on
 * the real pickle cache ({@code CDISC_PICKLE_CACHE_DIR}); the hermetic dispositions (no dir, no
 * loadable family) run everywhere.
 */
class StudyValidationServicePickleProviderTest
{

    private static @Nullable String realCache()
    {
        String dir = System.getenv("CDISC_PICKLE_CACHE_DIR");
        return dir != null && Files.isDirectory(Path.of(dir)) ? dir : null;
    }


    private static StudyValidationParams.Builder base(String cacheDir)
    {
        return StudyValidationParams.builder().manager(mock(IDataTableManager.class))
                .dataLibrary("x").pickleCacheDir(cacheDir);
    }


    @Test
    void anAdamRunNowLoadsOfflineFromThePickle()
    {
        // Phase 7a — before it, this returned null unconditionally ("ADaM is deferred") and
        // every ADaM run went to the web-api cache.
        String real = realCache();
        assumeTrue(real != null, "no real pickle cache configured");
        StudyValidationParams params = base(real)
                .metadataProducts(List.of("standards/adam/adamig-1-3")).build();

        MetadataProvider p = StudyValidationService.tryPickleProvider(params, StandardKind.ADAM,
                params.metadataProducts(), RunStandard.of("standards/adam/adamig-1-3"));

        assertNotNull(p, "an ADaM run with a pickle cache must not need the API");
        assertTrue(p.supportsStructureKeyedVariables());
        assertEquals(List.of("standards/adam/adamig-1-3"), p.declaredStructureKeyedProducts());
    }


    @Test
    void aTwoProductAdamPickleRunCarriesTheOrderedList()
    {
        // The service-level slice of the CMTRT evidence: the declared order reaches the offline
        // provider through params.metadataProducts().
        String real = realCache();
        assumeTrue(real != null, "no real pickle cache configured");
        StudyValidationParams params = base(real)
                .metadataProducts(
                        List.of("standards/adam/adam-occds-1-1", "standards/adam/adamig-1-3"))
                .build();

        MetadataProvider p = StudyValidationService.tryPickleProvider(params, StandardKind.ADAM,
                params.metadataProducts(), RunStandard.of("standards/adam/adamig-1-3"));

        assertNotNull(p);
        assertEquals(List.of("standards/adam/adam-occds-1-1", "standards/adam/adamig-1-3"),
                p.declaredStructureKeyedProducts());
        List<String> ae = p.getRequiredVariablesForStructure("OCCURRENCE DATA STRUCTURE",
                List.of("ADVERSE EVENT"));
        assertNotNull(ae, "the declared supplement must make the occurrence token resolvable");
        assertFalse(ae.contains("CMTRT"), "AE governs; CMTRT must not be demanded of ADAE");
    }


    @Test
    void aDeclaredTigAdamLegRoutesAnUnknownKindRunToThePickle()
    {
        // §7-2 — "-s tig" resolves to StandardKind.UNKNOWN, but the declared tig/<v>/adam product
        // makes the run ADaM-family and, with the pickle configured, gives a TIG run product
        // metadata for the first time (before Phase 7 it built a study-only provider).
        String real = realCache();
        assumeTrue(real != null, "no real pickle cache configured");
        StudyValidationParams params = base(real)
                .metadataProducts(List.of("standards/tig/1-0/adam")).build();

        MetadataProvider p = StudyValidationService.tryPickleProvider(params, StandardKind.UNKNOWN,
                params.metadataProducts(), RunStandard.of("standards/tig/1-0/adam"));

        assertNotNull(p);
        assertEquals(List.of("standards/tig/1-0/adam"), p.declaredStructureKeyedProducts());
        // Phase 6a's REFERENDS evidence, now reachable through the run path.
        List<String> referends = p.getRequiredVariablesForStructure("REFERENCE DATA STRUCTURE");
        assertNotNull(referends);
        assertTrue(referends.contains("INPRM"), () -> String.valueOf(referends));
    }


    @Test
    void theSdtmLibraryLayerFollowsTheFirstDeclaredSdtmProduct()
    {
        // §7-0, proved with published data. Declaring 3-1-3 first moves the library layer to
        // 3-1-3, whose domain universe is measurably smaller (AG is one of the 28 domains SDTMIG
        // 3-4 added — plan Phase 5's measurement).
        //
        // ⚑ Both runs keep runStandard = sdtmig 3-4 (what the run's own package declares). That is
        // the tension the assertion is about: the DECLARED product, not the run standard, decides
        // the library layer. The old "defaulted" arm — no -mp at all, relying on §1b′ to imply
        // standards/sdtmig/3-4 — is gone with §1b′ itself (Plan 2 R5).
        String real = realCache();
        assumeTrue(real != null, "no real pickle cache configured");
        RunStandard runStandard = RunStandard.of("standards/sdtmig/3-4");

        StudyValidationParams explicit = base(real)
                .metadataProducts(List.of("standards/sdtmig/3-4")).build();
        StudyValidationParams reordered = base(real)
                .metadataProducts(List.of("standards/sdtmig/3-1-3")).build();

        MetadataProvider pExplicit = StudyValidationService.tryPickleProvider(explicit,
                StandardKind.SDTM, explicit.metadataProducts(), runStandard);
        MetadataProvider pReordered = StudyValidationService.tryPickleProvider(reordered,
                StandardKind.SDTM, reordered.metadataProducts(), runStandard);

        assertNotNull(pExplicit);
        assertNotNull(pReordered);
        List<String> names34 = pExplicit.getStandardDatasetNames();
        List<String> names313 = pReordered.getStandardDatasetNames();
        assertNotNull(names34);
        assertNotNull(names313);
        assertTrue(names34.contains("AG"), () -> String.valueOf(names34));
        assertFalse(names313.contains("AG"),
                "a 3-1-3 library layer must not enumerate the domains 3-4 added");
    }


    @Test
    void aProductlessAdamRunFallsThroughToTheApiPathWithTheWarning()
    {
        // adamig 9-9 is in no cache: forAdam declines, tryPickleProvider WARNs and returns null
        // so the API path still gets its chance — the SDTM disposition, mirrored.
        String real = realCache();
        assumeTrue(real != null, "no real pickle cache configured");
        StudyValidationParams params = base(real)
                .metadataProducts(List.of("standards/adam/adamig-9-9")).build();
        assertNull(StudyValidationService.tryPickleProvider(params, StandardKind.ADAM,
                params.metadataProducts(), RunStandard.of("standards/adam/adamig-9-9")));
    }


    @Test
    void noPickleDirStillFallsThroughToTheApiPath()
    {
        StudyValidationParams params = StudyValidationParams.builder()
                .manager(mock(IDataTableManager.class)).dataLibrary("x")
                .metadataProducts(List.of("standards/adam/adamig-1-3"))
                .pickleCacheDir("/nonexistent/phase7").build();
        assertNull(StudyValidationService.tryPickleProvider(params, StandardKind.ADAM,
                params.metadataProducts(), RunStandard.of("standards/adam/adamig-1-3")));
    }


    @Test
    void anUnknownKindWithNothingAdamShapedDeclaredStaysOnTheApiPath()
    {
        String real = realCache();
        assumeTrue(real != null, "no real pickle cache configured");
        // A bare TIG product key (standards/tig/1-0, no leg) is neither family's, so there is
        // nothing the pickle path could load — unchanged behaviour.
        StudyValidationParams params = base(real).metadataProducts(List.of("standards/tig/1-0"))
                .build();
        assertNull(StudyValidationService.tryPickleProvider(params, StandardKind.UNKNOWN,
                params.metadataProducts(), RunStandard.of("standards/tig/1-0")));
    }
}
