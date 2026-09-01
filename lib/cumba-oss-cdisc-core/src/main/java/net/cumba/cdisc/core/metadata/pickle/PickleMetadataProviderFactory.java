package net.cumba.cdisc.core.metadata.pickle;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

import lombok.CustomLog;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.metadata.CdiscLibraryMetadataLibrary;
import net.cumba.cdisc.core.metadata.CtPackageRef;
import net.cumba.cdisc.core.metadata.DeclaredAdamProducts;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import org.jspecify.annotations.Nullable;

/**
 * Builds a {@link MetadataProvider} from the Python engine's pickle metadata cache, reusing the
 * existing {@link CdiscLibraryMetadataLibrary} / {@link MetadataLibraryProvider} resolver chain
 * (model column order, SUPP/AP shimming, IG-override merge, codelist lookups) — but sourcing the
 * underlying CDISC Library products from pickles instead of the network.
 *
 * <p>
 * Every factory method returns {@link Optional#empty()} when the required pickle data is absent, so
 * callers degrade exactly as they do when no Library access is configured (the rule is then
 * SKIPPED, the pre-existing behaviour).
 * </p>
 */
@CustomLog
public final class PickleMetadataProviderFactory
{

    private final PickleProductSource source;

    public PickleMetadataProviderFactory(PickleProductSource aSource)
    {
        source = aSource;
    }


    /**
     * Convenience factory from an explicit cache directory.
     *
     * @param aCacheDir
     *            the Python engine's {@code resources/cache} directory.
     */
    public static PickleMetadataProviderFactory open(Path aCacheDir)
    {
        return new PickleMetadataProviderFactory(
                new PickleProductSource(PickleCache.open(aCacheDir)));
    }


    /**
     * From the {@code CDISC_PICKLE_CACHE_DIR} / {@code cdisc.pickle.cache.dir} configuration, or
     * empty when not configured.
     */
    public static Optional<PickleMetadataProviderFactory> openIfConfigured()
    {
        return PickleCache.openIfConfigured()
                .map(c -> new PickleMetadataProviderFactory(new PickleProductSource(c)));
    }


    /**
     * Resolves the pickle cache directory with explicit-first precedence: a non-blank
     * {@code aExplicit} wins, otherwise the {@code CDISC_PICKLE_CACHE_DIR} environment variable,
     * then the {@code cdisc.pickle.cache.dir} system property. Returns {@code null} when nothing
     * resolves to an existing directory.
     *
     * <p>
     * This complements {@link PickleCache#openIfConfigured()} (which takes no explicit override and
     * does not check existence) — it is what callers like the CLI {@code --pickle-cache} flag need.
     * </p>
     *
     * @param aExplicit
     *            an explicit directory (e.g. a CLI flag value); may be {@code null}/blank.
     * @return the resolved directory, or {@code null} when none is configured or it is not a
     *         directory.
     */
    public static @Nullable Path resolveConfiguredDir(@Nullable String aExplicit)
    {
        String configured = (aExplicit != null && !aExplicit.isBlank()) ? aExplicit
                : System.getenv(PickleCache.CACHE_DIR_ENV);
        if (configured == null || configured.isBlank())
        {
            configured = System.getProperty(PickleCache.CACHE_DIR_PROPERTY);
        }
        if (configured == null || configured.isBlank())
        {
            return null;
        }
        Path dir = Path.of(configured);
        return Files.isDirectory(dir) ? dir : null;
    }


    /**
     * Builds an SDTM/SDTMIG/SENDIG provider for the given standard and version, optionally enriched
     * with a CT package.
     *
     * @param aStandard
     *            e.g. {@code sdtmig} / {@code sendig}.
     * @param aVersion
     *            e.g. {@code 3-4}.
     * @param aCtPackageId
     *            CT package id (e.g. {@code sdtmct-2024-09-27}); {@code null}/absent → no CT.
     * @return the provider, or empty when the IG product is not in the pickle cache.
     */
    public Optional<MetadataProvider> forSdtm(String aStandard, String aVersion,
            @Nullable String aCtPackageId)
    {
        return source.igSdtm(aStandard, aVersion).map(ig ->
        {
            SdtmProduct model = source.sdtmModelFor(ig).orElse(null);
            CtPackage ct = aCtPackageId == null ? source.emptyCtPackage()
                    : source.ctPackage(aCtPackageId).orElseGet(source::emptyCtPackage);
            // J9: hand the full set of published CT packages enumerated from the cache directory so
            // PUBLISHED_CT_PACKAGES is populated (the pickle path otherwise leaves it empty and
            // valid_codelist_dates over-fires).
            CdiscLibraryMetadataLibrary library = CdiscLibraryMetadataLibrary.fromSdtm(aStandard,
                    aVersion, ig, new CtPackageRef(aCtPackageId, ct),
                    source.cache().publishedCtPackages());
            return new MetadataLibraryProvider(library, ig, model, aStandard, aVersion,
                    aCtPackageId, ct, source::ctPackage);
        });
    }


    /**
     * Phase 7a of {@code plans/PLAN-metadata-product-selection.md} — builds an ADaM provider from
     * the pickle cache, so ADaM runs work offline exactly as SDTM ones do.
     *
     * <p>
     * ⚠⚠⚠ The ordered declared-product list is assembled by {@link DeclaredAdamProducts#assemble} —
     * <b>the same single construction site the API path uses</b>
     * ({@code CdiscLibraryProviderBuilder.buildAdam}) — with a pickle-backed fetcher. Constructing
     * the list here directly would leave the multi-product feature silently inert offline; see that
     * class's javadoc.
     * </p>
     *
     * <p>
     * §7-0: the <em>library</em> layer ({@link CdiscLibraryMetadataLibrary#fromAdam}, the
     * dataset/variable universe) follows the <b>first</b> declared product of the run's own family,
     * i.e. the assembled list's head — under §1b′ that is the {@code -s}/{@code -v} product unless
     * the user reordered deliberately, mirroring the API path.
     * </p>
     *
     * @param aStandard
     *            the run standard (e.g. {@code adamig}, {@code tig}).
     * @param aVersion
     *            the run version (e.g. {@code 1-3}).
     * @param aDeclaredProducts
     *            the resolved {@code standards/...} metadata-product keys, highest precedence first
     *            ({@code StudyValidationParams.metadataProducts()} — never empty).
     * @param aAdamCtPackageId
     *            ADaM CT package id (e.g. {@code adamct-2024-03-29}); {@code null} → no CT.
     * @param aSdtmCtPackageId
     *            optional SDTM CT package id used as a codelist fallback; {@code null} → none.
     * @return the provider, or empty when a needed product is not in the pickle cache (the caller
     *         then falls back to the CDISC Library API path, exactly as
     *         {@link #forSdtm(String, String, String)} callers do).
     * @throws net.cumba.cdisc.core.metadata.UnmappedMetadataProductException
     *             when a declared product's structures map to no token at all (plan §6b — a bad
     *             declaration must reach the user, never be degraded around)
     */
    public Optional<MetadataProvider> forAdam(String aStandard, String aVersion,
            List<String> aDeclaredProducts, @Nullable String aAdamCtPackageId,
            @Nullable String aSdtmCtPackageId)
    {
        List<MetadataLibraryProvider.DeclaredAdamProduct> declared;
        try
        {
            declared = DeclaredAdamProducts.assemble(aStandard, aVersion, aDeclaredProducts,
                    key -> source.adamProduct(key).orElseThrow(
                            () -> new IOException("pickle cache has no product for key " + key)));
        }
        catch (IOException e)
        {
            // Absent product ⇒ this source cannot serve the run; the caller falls back to the
            // API path (same disposition as forSdtm's empty). UnmappedMetadataProductException
            // deliberately propagates — a bad declaration is not an availability problem.
            LOGGER.log(System.Logger.Level.INFO,
                    "Pickle cache cannot serve the declared ADaM products ({0}); "
                            + "falling back to the CDISC Library API path.",
                    e.getMessage());
            return Optional.empty();
        }
        CtPackage adamCt = aAdamCtPackageId == null ? source.emptyCtPackage()
                : source.ctPackage(aAdamCtPackageId).orElseGet(source::emptyCtPackage);
        CtPackageRef sdtmCtRef = aSdtmCtPackageId == null ? null
                : source.ctPackage(aSdtmCtPackageId).map(p -> new CtPackageRef(aSdtmCtPackageId, p))
                        .orElse(null);
        // §7-0 — the library layer follows the first declared product of the run's own family.
        CdiscLibraryMetadataLibrary library = CdiscLibraryMetadataLibrary.fromAdam(aStandard,
                aVersion, declared.get(0).product(), new CtPackageRef(aAdamCtPackageId, adamCt),
                sdtmCtRef);
        return Optional.of(new MetadataLibraryProvider(library, declared, aStandard, aVersion));
    }
}
