package net.cumba.corej.core.metadata.store;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import lombok.CustomLog;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.CdiscLibraryMetadataLibrary;
import net.cumba.corej.core.metadata.DeclaredAdamProducts;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.metadata.MetadataProductKeys;
import org.jspecify.annotations.Nullable;

/**
 * Builds a {@link MetadataProvider} from the unified metadata store (cache plan §4.3.1, P3) — the
 * successor of the deleted pickle-backed factory (cache 8g), reusing the same
 * {@link CdiscLibraryMetadataLibrary} / {@link MetadataLibraryProvider} resolver chain but sourcing
 * products and CT packages from a {@link MetadataStore} instead of pickles or the network.
 *
 * <p>
 * Every factory method returns {@link Optional#empty()} when the store lacks the run's products.
 * Since cache P4 there is no other metadata path behind this factory: the caller then runs
 * <b>degraded</b> (ruling R2 — the library-dependent rules SKIP visibly), it does not fall back to
 * anything.
 * </p>
 *
 * <p>
 * ⭐ {@code PUBLISHED_CT_PACKAGES} is always the store's <b>whole</b> published enumeration
 * ({@link MetadataStore#publishedCtPackages()}), never the ids this run happened to request — on
 * the ADaM side that makes the store path the first to carry the full enumeration at all (the
 * api-model path derives it from the requested ids, the live §1.1-1 over-fire defect).
 * </p>
 *
 * <p>
 * ⚠ A <em>requested</em> CT package the store does not hold is substituted by an empty package (and
 * an optional SDTM fallback package is dropped) — this factory stays total on purpose. The
 * named-and-missing <b>hard abort</b> is implemented at the run layer
 * ({@code StudyValidationService.requireNamedCtPackagesPresent}, define-ct §4.4 / P5, via
 * {@link #presence(String)}): every package the run <em>named</em> for a consumed root is checked
 * before the quiet substitution here could stand in for it. Do not duplicate that abort inside the
 * factory — callers that name nothing (no CT at all) legitimately rely on the empty substitution.
 * </p>
 */
@CustomLog
public final class StoreMetadataProviderFactory
{

    /** Environment variable naming the store file; see {@link #resolveConfiguredFile(String)}. */
    public static final String STORE_ENV = "CDISC_METADATA_STORE";

    /** System property naming the store file. */
    public static final String STORE_PROPERTY = "cdisc.metadata.store";

    private final MetadataStore store;

    /**
     * Creates a factory over an open store. The store belongs to the caller and is not closed here.
     *
     * @param aStore
     *            the open metadata store
     */
    public StoreMetadataProviderFactory(MetadataStore aStore)
    {
        store = aStore;
    }


    /**
     * Opens the store file and wraps it in a factory.
     *
     * @param aStore
     *            the store zip
     * @return the factory
     * @throws IOException
     *             when the file is missing, corrupt, or of an unknown format version
     */
    public static StoreMetadataProviderFactory open(Path aStore) throws IOException
    {
        return new StoreMetadataProviderFactory(MetadataStore.open(aStore));
    }


    /**
     * The store's total "is this CT package present" answer (define-ct plan §4.4's store reader
     * contract), delegated verbatim to {@link MetadataStore#presence(String)}. Exposed so the run
     * layer can abort on a <em>named</em>-but-unavailable package before quietly building a
     * provider around the empty substitution below.
     *
     * @param aCtPackageId
     *            the candidate CT package id
     * @return the presence answer; never throws
     */
    public Presence presence(String aCtPackageId)
    {
        return store.presence(aCtPackageId);
    }


    /**
     * Resolves the configured store file with explicit-first precedence: a non-blank
     * {@code aExplicit} wins, otherwise the {@link #STORE_ENV} environment variable, then the
     * {@link #STORE_PROPERTY} system property. Returns {@code null} when nothing resolves to an
     * existing regular file.
     *
     * <p>
     * ⭐ {@code aExplicit} is the tier a surface's own store field/flag belongs on
     * ({@code StudyValidationParams.metadataStore()} routes it here for the run): a store the user
     * <em>named</em> must outrank an ambient {@code CDISC_METADATA_STORE}. Publishing the user's
     * choice into {@link #STORE_PROPERTY} instead ranks it BELOW the environment — the F2
     * precedence inversion. The env-over-property order of the two ambient tiers is deliberate and
     * must stay: operators set the environment variable on purpose, and the system property doubles
     * as the app-default publication channel ({@code ~/.cumbaDataBrowser/metadata-cache.zip} via
     * {@code cumba-oss-datatable}'s {@code MetadataCacheLocator} cascade — that cascade lives
     * downstream, so this factory has no app-default tier of its own).
     * </p>
     *
     * @param aExplicit
     *            an explicit file (a CLI flag value, the run's own
     *            {@code StudyValidationParams.metadataStore()}); may be {@code null}/blank
     * @return the resolved file, or {@code null} when none is configured or it is not a file
     */
    public static @Nullable Path resolveConfiguredFile(@Nullable String aExplicit)
    {
        String configured = (aExplicit != null && !aExplicit.isBlank()) ? aExplicit
                : System.getenv(STORE_ENV);
        if (configured == null || configured.isBlank())
        {
            configured = System.getProperty(STORE_PROPERTY);
        }
        if (configured == null || configured.isBlank())
        {
            return null;
        }
        Path file = Path.of(configured);
        return Files.isRegularFile(file) ? file : null;
    }


    /**
     * Builds an SDTM/SDTMIG/SENDIG provider for the given standard and version, merging the given
     * CT packages (define-ct plan §4.3: precedence order, newest first, own root before a fallback
     * root; the head package wins per codelist submission value). An empty list → no CT.
     *
     * @param aStandard
     *            e.g. {@code sdtmig} / {@code sendig}
     * @param aVersion
     *            e.g. {@code 3-4}
     * @param aCtPackageIds
     *            the CT package ids to merge, in precedence order
     * @return the provider, or empty when the IG product is not in the store
     */
    public Optional<MetadataProvider> forSdtm(String aStandard, String aVersion,
            List<String> aCtPackageIds)
    {
        String igKey = MetadataProductKeys.standardsKey(aStandard, aVersion);
        return store.product(igKey).map(ig ->
        {
            StoredProduct model = modelFor(ig);
            List<StoredCtPackage> packages = new ArrayList<>();
            for (String id : aCtPackageIds)
            {
                // A requested-but-absent package is substituted empty — the pickle path's
                // disposition, kept identical on purpose (see the class javadoc).
                packages.add(ctPackageOrEmpty(id));
            }
            if (packages.isEmpty())
            {
                // No CT selected: keep the pre-merge shape — a single anonymous empty package, so
                // codelist reads degrade to empty exactly as before.
                packages.add(new StoredCtPackage(null, List.of()));
            }
            CdiscLibraryMetadataLibrary library = CdiscLibraryMetadataLibrary
                    .fromStoredSdtm(aStandard, aVersion, ig, packages, store.publishedCtPackages());
            // The provider's configured package (the getCodelistAttribute multi-version anchor)
            // is the precedence head; further packages remain reachable via the loader.
            StoredCtPackage head = packages.get(0);
            return MetadataLibraryProvider.forStoredSdtm(library, ig, model, aStandard, aVersion,
                    head.id(), head, this::loadCtPackage);
        });
    }


    /**
     * Builds an ADaM provider from the store — the store-backed sibling of the deleted
     * {@code PickleMetadataProviderFactory.forAdam}, assembling the ordered declared-product list
     * through the same {@link DeclaredAdamProducts#assemble} choke point (⚠⚠ both cache paths MUST
     * funnel through it; see that class).
     *
     * @param aStandard
     *            the run standard (e.g. {@code adamig}, {@code tig})
     * @param aVersion
     *            the run version (e.g. {@code 1-3})
     * @param aDeclaredProducts
     *            the resolved {@code standards/...} metadata-product keys, highest precedence first
     * @param aAdamCtPackageIds
     *            ADaM CT package ids in precedence order; may be empty (no CT)
     * @param aSdtmCtPackageIds
     *            SDTM CT fallback package ids in precedence order; may be empty
     * @return the provider, or empty when a needed product is not in the store (the caller then
     *         degrades the run: library-dependent rules SKIP)
     * @throws net.cumba.corej.core.metadata.UnmappedMetadataProductException
     *             when a declared product's structures map to no token at all (plan §6b — a bad
     *             declaration must reach the user, never be degraded around)
     */
    public Optional<MetadataProvider> forAdam(String aStandard, String aVersion,
            List<String> aDeclaredProducts, List<String> aAdamCtPackageIds,
            List<String> aSdtmCtPackageIds)
    {
        List<MetadataLibraryProvider.DeclaredAdamProduct> declared;
        try
        {
            declared = DeclaredAdamProducts.assemble(aStandard, aVersion, aDeclaredProducts,
                    key -> store.product(key).orElseThrow(
                            () -> new IOException("metadata store has no product for key " + key)));
        }
        catch (IOException e)
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "Metadata store cannot serve the declared ADaM products ({0}); the run "
                            + "degrades and the library-dependent rules will SKIP.",
                    e.getMessage());
            return Optional.empty();
        }
        List<StoredCtPackage> adamPackages = new ArrayList<>();
        for (String id : aAdamCtPackageIds)
        {
            adamPackages.add(ctPackageOrEmpty(id));
        }
        if (adamPackages.isEmpty())
        {
            // No ADaM CT selected: keep the pre-merge shape — a single anonymous empty package.
            adamPackages.add(new StoredCtPackage(null, List.of()));
        }
        // The SDTM fallback keeps its legacy disposition: a package the store lacks is dropped
        // (it was optional), never substituted by an empty one.
        List<StoredCtPackage> sdtmPackages = new ArrayList<>();
        for (String id : aSdtmCtPackageIds)
        {
            loadCtPackage(id).ifPresent(sdtmPackages::add);
        }
        // §7-0 — the library layer follows the first declared product of the run's own family.
        CdiscLibraryMetadataLibrary library = CdiscLibraryMetadataLibrary.fromStoredAdam(aStandard,
                aVersion, declared.get(0).product(), adamPackages, sdtmPackages,
                store.publishedCtPackages());
        return Optional.of(new MetadataLibraryProvider(library, declared, aStandard, aVersion));
    }


    /**
     * The total, malformed-safe CT loader handed to the provider: {@link Presence#PRESENT} loads,
     * everything else — including a malformed id from a data row — answers empty so the rule SKIPs
     * instead of the run aborting ({@link MetadataStore#ctPackage} throws on a malformed id by
     * contract; data-derived ids must not reach it unchecked).
     */
    private Optional<StoredCtPackage> loadCtPackage(String aId)
    {
        return store.presence(aId) == Presence.PRESENT ? store.ctPackage(aId) : Optional.empty();
    }


    private StoredCtPackage ctPackageOrEmpty(String aId)
    {
        return loadCtPackage(aId).orElseGet(() ->
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Metadata store does not hold requested CT package {0}; substituting an "
                            + "empty package (legacy disposition, see the define plan §4.4 for "
                            + "the eventual abort).",
                    aId);
            return new StoredCtPackage(aId, List.of());
        });
    }


    /**
     * The IG's underlying model product, resolved from its {@code modelHref}
     * ({@code /mdr/<std>/<ver>} → store key {@code models/<std>/<ver>} — the same derivation as the
     * deleted {@code PickleProductSource.modelKeyFromIg} used). {@code null} when the IG names no
     * model or the store lacks it (the provider then falls through to the canonical SUPPQUAL list,
     * tier C).
     */
    private @Nullable StoredProduct modelFor(StoredProduct aIgProduct)
    {
        String href = aIgProduct.modelHref();
        if (href == null)
        {
            return null;
        }
        String[] parts = href.split("/", -1);
        // /mdr/<standard>/<version> → parts = ["", "mdr", standard, version]
        if (parts.length < 4)
        {
            return null;
        }
        String standard = parts[2];
        String version = parts[3];
        if (version.startsWith(standard + "-"))
        {
            version = version.substring(standard.length() + 1);
        }
        return store.product("models/" + standard + "/" + version.replace('.', '-')).orElse(null);
    }
}
