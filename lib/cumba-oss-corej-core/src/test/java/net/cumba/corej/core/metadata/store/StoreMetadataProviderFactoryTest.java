package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.cumba.corej.core.exec.MetadataProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link StoreMetadataProviderFactory} over the synthetic P1 fixture store — the always-on half of
 * the P3 gate. (Its other half, the retired {@code StoreVsPickleProviderEquivalenceTest}, compared
 * this factory against the pickle-backed one over the real corpus; the pickle factory is deleted —
 * cache 8g — so the real-corpus leg now lives in {@code StoreMetadataProviderFactoryAdamTest}'s
 * integration tests and, at run scale, in {@code cumba-corej-rules}' store-backed suites.)
 *
 * <p>
 * The load-bearing assertions are the two {@code PUBLISHED_CT_PACKAGES} ones: the store path must
 * answer the store's <b>whole</b> published enumeration — including a package the store does not
 * even hold, and on the ADaM family, where the retained api-model path still answers only the
 * requested ids (plan §1.1-1, the CORE-000761 / CDISC-CG0289 over-fire defect).
 * </p>
 */
class StoreMetadataProviderFactoryTest
{

    @TempDir
    private Path temp;

    private StoreMetadataProviderFactory factory;

    @BeforeEach
    void openFixtureStore() throws IOException
    {
        Path file = temp.resolve("store.zip");
        MetadataStoreFixtures.populatedWriter().write(file);
        factory = StoreMetadataProviderFactory.open(file);
    }


    @Test
    void sdtmProviderAnswersTheWholePublishedEnumerationNotTheRequestedIds()
    {
        MetadataProvider provider = factory
                .forSdtm("sdtmig", "3-4", List.of(MetadataStoreFixtures.PKG_SDTM_1)).orElseThrow();
        // One package was requested; the store publishes four, one of which it does not even
        // hold. The provider must answer all four — deriving the list from what the run loaded
        // is the exact defect the store exists to fix (plan §1.1-1).
        assertEquals(MetadataStoreFixtures.publishedCtPackages(),
                provider.getPublishedCtPackages());
    }


    @Test
    void adamProviderAnswersTheWholePublishedEnumerationNotTheRequestedIds()
    {
        MetadataProvider provider = factory
                .forAdam("adamig", "1-3", List.of(MetadataStoreFixtures.ADAM_KEY),
                        List.of(MetadataStoreFixtures.PKG_SDTM_1), List.of())
                .orElseThrow();
        // The api-model fromAdam derives PUBLISHED_CT_PACKAGES from the requested ids — this is
        // the first path on which the ADaM family carries the full enumeration.
        assertEquals(MetadataStoreFixtures.publishedCtPackages(),
                provider.getPublishedCtPackages());
    }


    @Test
    void sdtmProviderServesCodelistsFromTheStore()
    {
        MetadataProvider provider = factory
                .forSdtm("sdtmig", "3-4", List.of(MetadataStoreFixtures.PKG_SDTM_1)).orElseThrow();
        assertEquals(Set.of("N", "U"), Set.copyOf(provider.getCodelistTerms("NY")));
        assertEquals(Optional.of(Boolean.FALSE), provider.isCodelistExtensible("NY"));
        assertEquals(java.util.Map.of("N", "No", "U", "Unknown"),
                provider.getCodelistTermMappings("NY"));
        // The library tables come from the stored IG product.
        assertEquals(List.of("STUDYID", "SEX"),
                provider.getDomainVariables("DM").stream().map(m -> m.get("name")).toList());
        assertEquals(List.of("DM", "LB"), provider.getStandardDatasetNames());
    }


    @Test
    void nonConfiguredPackageResolvesThroughTheLoader()
    {
        MetadataProvider provider = factory
                .forSdtm("sdtmig", "3-4", List.of(MetadataStoreFixtures.PKG_SDTM_1)).orElseThrow();
        // PKG_SDTM_2 was not requested; getCodelistAttribute must reach it via the store loader.
        List<String> values = provider.getCodelistAttribute(MetadataStoreFixtures.PKG_SDTM_2,
                "Codelist Value");
        assertEquals(Set.of("NY", "YESONLY"), Set.copyOf(values));
    }


    @Test
    void malformedAndAbsentPackageIdsDegradeToEmptyInsteadOfThrowing()
    {
        MetadataProvider provider = factory
                .forSdtm("sdtmig", "3-4", List.of(MetadataStoreFixtures.PKG_SDTM_1)).orElseThrow();
        // Malformed (a data-row artefact) and well-formed-but-absent both answer empty so the
        // rule SKIPs; MetadataStore.ctPackage would throw on the former by contract.
        assertEquals(List.of(),
                provider.getCodelistAttribute("not a package id", "Codelist Value"));
        assertEquals(List.of(),
                provider.getCodelistAttribute("sdtmct-2099-01-01", "Codelist Value"));
    }


    @Test
    void absentProductAnswersEmptySoCallersFallThrough()
    {
        assertTrue(factory.forSdtm("sdtmig", "9-9", List.of()).isEmpty());
        assertTrue(factory.forAdam("adamig", "9-9", List.of("standards/adam/adamig-9-9"), List.of(),
                List.of()).isEmpty());
    }


    @Test
    void adamProviderResolvesStructureKeyedVariablesFromTheStoredProduct()
    {
        MetadataProvider provider = factory
                .forAdam("adamig", "1-3", List.of(MetadataStoreFixtures.ADAM_KEY),
                        List.of(MetadataStoreFixtures.PKG_SDTM_1), List.of())
                .orElseThrow();
        assertTrue(provider.supportsStructureKeyedVariables());
        assertEquals(List.of(MetadataStoreFixtures.ADAM_KEY),
                provider.declaredStructureKeyedProducts());
        // The fixture ADSL structure publishes subClass "ADSL SUBCLASS", so the governing chain
        // needs that detected token — with none, only a base tier could answer and there is none.
        List<String> required = provider.getRequiredVariablesForStructure(
                "SUBJECT LEVEL ANALYSIS DATASET", List.of("ADSL SUBCLASS"));
        assertNotNull(required);
        assertEquals(List.of("USUBJID"), required);
    }


    @Test
    void sdtmProviderResolvesTheModelProductThroughModelHref()
    {
        MetadataProvider provider = factory
                .forSdtm("sdtmig", "3-4", List.of(MetadataStoreFixtures.PKG_SDTM_1)).orElseThrow();
        // The fixture model publishes a class-less top-level DM dataset; its presence in
        // isDomainCustom's model leg proves the modelHref → models/sdtm/2-0 resolution worked.
        assertFalse(provider.isDomainCustom("DM"));
        // A domain in neither the IG nor the model is custom.
        assertTrue(provider.isDomainCustom("XX"));
    }


    @Test
    void noConfiguredStoreResolvesToNull()
    {
        assertEquals(null, StoreMetadataProviderFactory
                .resolveConfiguredFile(temp.resolve("does-not-exist.zip").toString()));
    }

    // ------------------------------------------------------------------
    // resolveConfiguredFile precedence — the successors of the deleted
    // PickleMetadataProviderFactoryTest's resolveConfiguredDir tests (cache 8g)
    // ------------------------------------------------------------------


    @Test
    void explicitFileWins() throws IOException
    {
        Path file = temp.resolve("explicit-store.zip");
        MetadataStoreFixtures.populatedWriter().write(file);
        String prev = System.getProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
        try
        {
            // Even with the property pointing elsewhere, the explicit argument wins.
            System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY,
                    temp.resolve("other.zip").toString());
            assertEquals(file, StoreMetadataProviderFactory.resolveConfiguredFile(file.toString()));
        }
        finally
        {
            restoreProperty(prev);
        }
    }


    @Test
    void blankExplicit_fallsBackToSystemProperty() throws IOException
    {
        // env can't be set portably in-test; cover the sysprop branch (lower precedence than env,
        // higher than nothing). Guard against an env var being set in the CI environment.
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv(StoreMetadataProviderFactory.STORE_ENV) == null,
                "CDISC_METADATA_STORE set in environment");
        Path file = temp.resolve("prop-store.zip");
        MetadataStoreFixtures.populatedWriter().write(file);
        String prev = System.getProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
        try
        {
            System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, file.toString());
            assertEquals(file, StoreMetadataProviderFactory.resolveConfiguredFile("  "));
        }
        finally
        {
            restoreProperty(prev);
        }
    }


    @Test
    void nothingConfigured_returnsNull()
    {
        org.junit.jupiter.api.Assumptions.assumeTrue(
                System.getenv(StoreMetadataProviderFactory.STORE_ENV) == null,
                "CDISC_METADATA_STORE set in environment");
        String prev = System.getProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
        try
        {
            System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
            assertEquals(null, StoreMetadataProviderFactory.resolveConfiguredFile(null));
        }
        finally
        {
            restoreProperty(prev);
        }
    }


    private static void restoreProperty(String aPrevious)
    {
        if (aPrevious == null)
        {
            System.clearProperty(StoreMetadataProviderFactory.STORE_PROPERTY);
        }
        else
        {
            System.setProperty(StoreMetadataProviderFactory.STORE_PROPERTY, aPrevious);
        }
    }
}
