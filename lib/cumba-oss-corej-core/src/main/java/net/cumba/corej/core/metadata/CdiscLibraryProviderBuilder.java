package net.cumba.corej.core.metadata;

import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;
import lombok.CustomLog;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.corej.core.CoreLibraryAccess;
import net.cumba.corej.core.CoreLibraryAccessImpl;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.datatable.metadata.IMetadataLibrary;
import org.jspecify.annotations.Nullable;

/**
 * Fix #57 — single-call facade that hides the CDISC Library product fetch and the
 * {@link LibraryMetadataEnhancer} builder chain behind a small fluent API. Lets the runtime entry
 * points (the dataviewer's {@code CoreEngineRunner.buildProvider} and the CLI's
 * {@code CdiscValidate.buildProvider}) drop their {@code cdisc.library.api.model.*} imports —
 * `SdtmProduct`, `AdamProduct`, the inner enhancer types — and replace ~50 lines of fetch / wire
 * boilerplate with a single {@link #buildOrDegraded()} call.
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 *
 * MetadataProvider provider = CdiscLibraryProviderBuilder.from(access).study(studyMetadata)
 *         .standard("sdtmig").version("3-4").ctPackageIds(List.of("sdtmct-2024-09-27")) // optional
 *                                                                                       // — empty
 *                                                                                       // list =
 *                                                                                       // no CT
 *                                                                                       // enrichment
 *         .onCtFetch(id -> log("Fetching CT package " + id)) // optional progress hook
 *         .buildOrDegraded();
 * }</pre>
 *
 * <h2>Branches</h2>
 *
 * <p>
 * {@link #buildOrDegraded()} returns the appropriate provider for the inputs:
 * </p>
 *
 * <ul>
 * <li><b>No access / unknown standard</b> → {@code new MetadataLibraryProvider(study)} (no product
 * enrichment, study-only).</li>
 * <li><b>No CT package matched, but access + standard valid</b> (Fix #65) → IG (and SDTM Model for
 * SDTM standards) is still fetched and threaded into the provider via the direct constructor; the
 * CT-enriched study layer is skipped because {@link CdiscLibraryMetadataLibrary#fromSdtm} requires
 * CT. Codelist-aware conformance rules surface as not-executed; every other Library-dependent rule
 * (CORE-000550 family, etc.) runs.</li>
 * <li><b>Library fetch fails</b> (HTTP error, malformed product response, version mismatch) →
 * {@code MetadataLibraryProvider.degraded(study, cause)}. The degraded provider's class- hierarchy
 * queries return the "library not available" signal so {@code RuleRunner}'s Phase 2a.1 probe (Fix
 * #42 Phase 1) reports affected rules as {@code SKIPPED}. A one-time WARN with the cause is logged
 * once at construction.</li>
 * <li><b>Happy path</b> (access + standard + matching CT id) → product fetched + threaded through
 * {@link LibraryMetadataEnhancer} into a product-aware {@link MetadataLibraryProvider} with
 * CT-enriched codelists.</li>
 * </ul>
 *
 * <p>
 * CT-package <em>selection</em> (which package to use given a list of candidate ids) now lives
 * inside this facade — {@link #resolveCtPackage} walks the supplied {@link #ctPackageIds} list,
 * picks the first whose id starts with the standard's CT prefix, and asks
 * {@link CoreLibraryAccessImpl#fetchCtPackage(String)} to materialise it. The caller no longer
 * needs to import {@link CtPackage}.
 * </p>
 *
 * <p>
 * Stateful builder; not thread-safe during configuration. The returned provider is thread-safe. Do
 * not reuse a builder instance across calls — instantiate fresh per provider.
 * </p>
 */
@CustomLog
public final class CdiscLibraryProviderBuilder
{

    private final @Nullable CoreLibraryAccess access;

    private @Nullable IMetadataLibrary study;

    private @Nullable String standardName;

    private @Nullable String standardVersion;

    private List<String> ctPackageIds = List.of();

    private List<String> metadataProducts = List.of();

    private boolean libraryAsStudy;

    private Consumer<String> onCtFetch = _ ->
    {
        // No-op by default; CLI sets this to its INFO logger so users see progress.
    };

    private CdiscLibraryProviderBuilder(@Nullable CoreLibraryAccess aAccess)
    {
        access = aAccess;
    }


    /**
     * Starts a builder around the given {@link CoreLibraryAccess}. {@code aAccess} may be
     * {@code null} — the builder will short-circuit to a study-only provider in
     * {@link #buildOrDegraded()} (mirrors the runtime's "no API key configured" fallback).
     */
    public static CdiscLibraryProviderBuilder from(@Nullable CoreLibraryAccess aAccess)
    {
        return new CdiscLibraryProviderBuilder(aAccess);
    }


    /** The study-side {@link IMetadataLibrary} (Define-XML, .dblib, study columns). Required. */
    public CdiscLibraryProviderBuilder study(@Nullable IMetadataLibrary aStudy)
    {
        study = aStudy;
        return this;
    }


    /**
     * Use the CDISC Library product itself as the authoritative metadata source, with <em>no</em>
     * study overlay. In normal (study-backed) mode the study decides which datasets/columns exist
     * and the product only enriches them, so per-variable queries
     * ({@link MetadataProvider#getVariableMetadata}, {@code getRequiredVariables}, …) return empty
     * for any dataset the study doesn't declare. In library-as-study mode the provider is built
     * over the product-derived {@link CdiscLibraryMetadataLibrary} directly, so those queries
     * return the IG's published metadata for every standard dataset/variable.
     *
     * <p>
     * <b>Requires a CT package</b> — the product-derived library is assembled by
     * {@link CdiscLibraryMetadataLibrary#fromSdtm}/{@code fromAdam}, which need a
     * {@link CtPackage}. When no CT package is resolved this mode degrades to the normal
     * study-backed build (so the call still never throws); supply a matching
     * {@code ctPackageIds(...)} entry to get the library-as-study behaviour. Intended for tools
     * that want to evaluate rules against the real Library without a Define-XML study (e.g. the
     * rule-test {@code #library-ref} harness).
     * </p>
     */
    public CdiscLibraryProviderBuilder libraryAsStudy()
    {
        libraryAsStudy = true;
        return this;
    }


    /** The standard identifier (e.g. {@code "sdtmig"}, {@code "adamig"}, {@code "sendig"}). */
    public CdiscLibraryProviderBuilder standard(String aStandardName)
    {
        standardName = aStandardName;
        return this;
    }


    /** The standard version (e.g. {@code "3-4"}, {@code "1-3"}). */
    public CdiscLibraryProviderBuilder version(String aVersion)
    {
        standardVersion = aVersion;
        return this;
    }


    /**
     * Candidate CT-package ids (e.g. {@code "sdtmct-2024-09-27"}, {@code "adamct-2024-03-29"}). The
     * builder walks the list and picks the first id whose prefix matches the standard's CT root
     * (SDTM/SEND → {@code "sdtmct"}, ADaM → {@code "adamct"}), then fetches it via the no-throw
     * {@link CoreLibraryAccessImpl#fetchCtPackage(String)} helper. Empty list / no match → no CT
     * enrichment (Fix #65 degraded path still fires correctly). {@code null} is treated as empty.
     */
    public CdiscLibraryProviderBuilder ctPackageIds(List<String> aIds)
    {
        ctPackageIds = aIds != null ? List.copyOf(aIds) : List.of();
        return this;
    }


    /**
     * The run's declared metadata products as resolved {@code standards/...} cache keys, in the
     * user's precedence order ({@code --metadata-products}; see
     * {@code StudyValidationParams.metadataProducts()}, which is never empty — an omitted flag
     * defaults to the single product implied by {@code -s}/{@code -v}).
     *
     * <p>
     * ⭐ <b>This is what makes ruling 1 and ruling 2 reachable at runtime.</b> Without it
     * {@link #buildAdam} fetches only the {@code -s}/{@code -v} product, the provider's ordered
     * product list has exactly one entry and the whole precedence chain is inert on more than one
     * product — a green build proving nothing.
     * </p>
     *
     * <p>
     * ⚠ Each branch consumes only its <b>own family's</b> keys. The ADaM branch walks the
     * ADaM-family keys ({@code standards/adam/<id>} and, since §7-2, the
     * {@code standards/tig/<v>/adam} leg); an SDTM-family key declared on an ADaM run must stay
     * <b>narrow</b> (plan §2.4) — injecting an SDTM product into this provider would change how
     * <em>ADaM</em> variables resolve as a side effect. The companion-SDTM path (a decorator
     * overriding exactly one accessor) is where a declared SDTM product belongs. Conversely the
     * SDTM branch follows the first declared SDTM-family key (§7-0: the library layer follows the
     * FIRST declared product of the run's own family) and ignores ADaM keys.
     * </p>
     *
     * @param aProducts
     *            resolved cache keys, highest precedence first; {@code null} ⇒ empty.
     * @return this builder.
     */
    public CdiscLibraryProviderBuilder metadataProducts(@Nullable List<String> aProducts)
    {
        metadataProducts = aProducts != null ? List.copyOf(aProducts) : List.of();
        return this;
    }


    /**
     * Progress hook fired once per CT-package fetch attempt, before the network call. Default
     * no-op; the CLI sets this to its INFO logger so users see a {@code "Fetching CT package
     * sdtmct-2024-09-27..."} line per attempt. {@code null} resets to the no-op default.
     */
    public CdiscLibraryProviderBuilder onCtFetch(Consumer<String> aHook)
    {
        onCtFetch = aHook != null ? aHook : _ ->
        {
            // explicit no-op
        };
        return this;
    }


    /**
     * Builds the {@link MetadataProvider}. Never throws — Library fetch failures are caught and
     * translated into a degraded provider (so the rule run can proceed with library-dependent rules
     * surfacing as {@code SKIPPED}).
     */
    public MetadataProvider buildOrDegraded()
    {
        IMetadataLibrary studyLib = Objects.requireNonNull(study,
                "study is required — pass IMetadataLibrary via .study(...)");
        if (access == null)
        {
            return new MetadataLibraryProvider(studyLib);
        }
        StandardKind kind = StandardKind.fromName(standardName);
        if (kind == StandardKind.UNKNOWN)
        {
            return new MetadataLibraryProvider(studyLib);
        }
        // Cross-package "friend" cast: CoreLibraryAccessImpl is public-but-unconstructible
        // for downstream modules; we are inside cdisc.core so the cast is safe and gives us
        // access to the underlying CdiscLibraryClient + the no-throw fetchCtPackage helper.
        // Guard with instanceof so a test that supplies a non-impl CoreLibraryAccess (e.g.
        // Mockito mock of the interface) degrades to the study-only provider instead of
        // throwing a ClassCastException at runtime.
        if (!(access instanceof CoreLibraryAccessImpl impl))
        {
            return new MetadataLibraryProvider(study);
        }
        CdiscLibraryClient client = impl.client();
        // kind != UNKNOWN above implies standardName matched a known prefix, so it is non-null.
        String stdName = Objects.requireNonNull(standardName, "standardName (kind != UNKNOWN)")
                .toLowerCase(Locale.ROOT);
        String ctPrefix = kind == StandardKind.SDTM ? "sdtmct" : "adamct";
        CtPackageRef ctPackage = resolveCtPackage(impl, ctPrefix);
        return switch (kind)
        {
        case SDTM -> buildSdtm(studyLib, client, stdName, ctPackage);
        case ADAM -> buildAdam(studyLib, client, stdName, ctPackage);
        default -> new MetadataLibraryProvider(studyLib);
        };
    }


    private MetadataProvider buildSdtm(IMetadataLibrary aStudy, CdiscLibraryClient aClient,
            String aStdName, @Nullable CtPackageRef aCtPackage)
    {
        // standardVersion is mandatory for any library-backed build (UNKNOWN kind already
        // returned).
        String version = Objects.requireNonNull(standardVersion,
                "standardVersion is required — pass via .version(...)");
        // §7-0 — the library layer follows the FIRST declared SDTM-family product. Under §1b′ an
        // omitted --metadata-products defaults to the -s/-v product's own key, so the loader pair
        // below equals (aStdName, version) for every existing invocation; it differs only when
        // the user deliberately declared another SDTM-family product first. A declared list with
        // no SDTM-family key at all (e.g. only ADaM products on an SDTM run) falls back to the
        // -s/-v pair, today's behaviour.
        MetadataProductKeys.SdtmLoader loader = MetadataProductKeys
                .firstSdtmLoader(metadataProducts);
        String libStd = loader != null ? loader.standard() : aStdName;
        String libVersion = loader != null ? loader.version() : version;
        SdtmProduct product;
        try
        {
            if ("tig".equals(libStd))
            {
                // §7-1: TIG is pickle-only — the API has no TIG endpoints and the API cache no
                // TIG payloads. Fail with the stated reason instead of a mysterious HTTP error.
                throw new IOException("Declared metadata product standards/tig/" + libVersion
                        + " is a TIG product, which the CDISC Library API path cannot load; "
                        + "TIG products are available only from the pickle metadata cache "
                        + "(--pickle-cache / CDISC_PICKLE_CACHE_DIR)");
            }
            product = aClient.getSdtmVersion(libStd, libVersion, true);
        }
        catch (IOException | RuntimeException e)
        {
            return MetadataLibraryProvider.degraded(aStudy, e);
        }
        // Fix #61: best-effort fetch of the underlying SDTM Model product (e.g. /mdr/sdtm/2-0)
        // for SUPP/SQ class-variable resolution. The IG's `_links.model` carries the version;
        // when missing or the fetch fails, the provider falls through to the canonical
        // hard-coded SUPPQUAL list (tier C). Failure here MUST NOT degrade the whole provider
        // — only the SUPPQUAL branch needs the Model.
        SdtmProduct modelProduct = fetchSdtmModelBestEffort(aClient, product);
        // Fix #65: the IG product drives every class-hierarchy query (model column order,
        // standard model variables, dataset class). Pre-Fix-#65 a missing CT package
        // short-circuited to a study-only provider, leaving every Library-dependent rule
        // (CORE-000550 family) reporting "no product / degraded mode — rule will be
        // skipped" even though the user's run was perfectly capable of fetching the IG.
        // Now: when no CT package is selected, we still load the IG (and Model) and
        // construct the provider directly without the CT-enriched study layer
        // (CdiscLibraryMetadataLibrary.fromSdtm requires CT). Codelist-aware conformance
        // rules continue to surface as not-executed when CT is missing, but every other
        // Library-dependent rule runs.
        // §7-0: the provider/library labels describe the LIBRARY product, i.e. the loader pair —
        // identical to (aStdName, version) unless the user declared another SDTM-family product
        // first.
        if (aCtPackage == null)
        {
            return new MetadataLibraryProvider(aStudy, product, modelProduct, libStd, libVersion);
        }
        // libraryAsStudy: pass a null study so LibraryMetadataEnhancer returns the pure
        // product-derived library (every IG dataset/variable), rather than overlaying a study that
        // would gate which datasets are visible.
        return LibraryMetadataEnhancer.forSdtm()//
                .study(libraryAsStudy ? null : aStudy)//
                .standardName(libStd)//
                .standardVersion(libVersion)//
                .sdtm(product)//
                .sdtmModel(modelProduct)//
                .ct(aCtPackage)//
                .buildProvider();
    }


    private MetadataProvider buildAdam(IMetadataLibrary aStudy, CdiscLibraryClient aClient,
            String aStdName, @Nullable CtPackageRef aCtPackage)
    {
        // standardVersion is mandatory for any library-backed build (UNKNOWN kind already
        // returned).
        String version = Objects.requireNonNull(standardVersion,
                "standardVersion is required — pass via .version(...)");
        List<MetadataLibraryProvider.DeclaredAdamProduct> declared;
        try
        {
            // ⚠⚠⚠ THE single construction site for the ordered declared-product list (Phase 7:
            // the pickle path — PickleMetadataProviderFactory.forAdam — calls the same method
            // with a pickle-backed fetcher, so both cache sources walk one list assembly).
            declared = DeclaredAdamProducts.assemble(aStdName, version, metadataProducts,
                    apiAdamFetcher(aClient));
        }
        catch (UnmappedMetadataProductException e)
        {
            // Plan §6b: a declared product that can never answer is a bad declaration and must
            // reach the user (ruling 3) — never be degraded around like an availability problem.
            throw e;
        }
        catch (IOException | RuntimeException e)
        {
            return MetadataLibraryProvider.degraded(aStudy, e);
        }
        // §7-0 — the LIBRARY layer (CdiscLibraryMetadataLibrary.fromAdam's dataset/variable
        // universe, via LibraryMetadataEnhancer) follows the FIRST declared product of the run's
        // own family. Under §1b′ that is the -s/-v product for every existing invocation.
        AdamProduct product = declared.get(0).product();
        // Fix #65: same product-without-CT path for ADaM. AdamProduct goes through the 4-arg
        // constructor (no SDTM Model fetch — ADaM doesn't use the SUPPQUAL cascade).
        if (aCtPackage == null)
        {
            return new MetadataLibraryProvider(aStudy, declared, aStdName, version);
        }
        // libraryAsStudy: see buildSdtm — null study yields the pure product-derived library.
        return LibraryMetadataEnhancer.forAdam()//
                .study(libraryAsStudy ? null : aStudy)//
                .standardName(aStdName)//
                .standardVersion(version)//
                .adam(product)//
                .declaredProducts(declared)//
                .ct(aCtPackage)//
                .buildProvider();
    }


    /**
     * The CDISC Library API's {@link DeclaredAdamProducts.AdamProductFetcher}:
     * {@code standards/adam/<id>} keys fetch via {@code GET /mdr/adam/<id>?expand=true}; a TIG ADaM
     * leg — ADaM-family since §7-2, but pickle-only (§7-1: the API serves no TIG) — fails with the
     * stated reason instead of a mysterious HTTP error.
     */
    private static DeclaredAdamProducts.AdamProductFetcher apiAdamFetcher(
            CdiscLibraryClient aClient)
    {
        return key ->
        {
            String id = MetadataProductKeys.adamProductIdOf(key);
            if (id == null)
            {
                // assemble() only fetches ADaM-family keys, so a null id here IS the TIG leg.
                throw new IOException("Declared metadata product " + key + " is a TIG product, "
                        + "which the CDISC Library API path cannot load; TIG products are "
                        + "available only from the pickle metadata cache (--pickle-cache / "
                        + "CDISC_PICKLE_CACHE_DIR)");
            }
            return aClient.getAdamProduct(id, true);
        };
    }


    /**
     * Walks {@link #ctPackageIds} for the first id matching {@code aCtPrefix} and asks the impl to
     * materialise it. The fetch is no-throw — {@link CoreLibraryAccessImpl#fetchCtPackage} returns
     * {@code null} on {@link IOException}, which we treat as "try the next candidate" for symmetry
     * with the legacy `fetchCt` loops in {@code CoreEngineRunner.fetchCt} (line 423) and
     * {@code CdiscValidate.fetchCt} (line 676). Empty list / no match → {@code null}, which routes
     * to the Fix #65 no-CT degraded path in {@link #buildOrDegraded()}.
     */
    private @Nullable CtPackageRef resolveCtPackage(CoreLibraryAccessImpl aImpl, String aCtPrefix)
    {
        for (String id : ctPackageIds)
        {
            if (id != null && id.startsWith(aCtPrefix))
            {
                onCtFetch.accept(id);
                CtPackage pkg = aImpl.fetchCtPackage(id);
                if (pkg != null)
                {
                    // Pair the package with the id it was requested under — CtPackage.name()
                    // carries the API's display label, not the id (see CtPackageRef).
                    return new CtPackageRef(id, pkg);
                }
            }
        }
        return null;
    }


    /**
     * Fix #61: best-effort SDTM Model fetch. Reads the IG's {@code _links.model.href} (e.g.
     * {@code /mdr/sdtm/2-0}) to derive the Model version, then calls
     * {@link CdiscLibraryClient#getSdtmVersion(String, String, boolean)} with {@code "sdtm"}.
     * Returns {@code null} on any failure — the caller (provider) treats absence as a signal to
     * fall through to the hard-coded SUPPQUAL list. A WARN is logged so operators can spot Library
     * access issues affecting only SUPP/SQ rule resolution.
     */
    private static @Nullable SdtmProduct fetchSdtmModelBestEffort(CdiscLibraryClient aClient,
            SdtmProduct aIgProduct)
    {
        if (aClient == null || aIgProduct == null)
        {
            return null;
        }
        String modelVersion = aIgProduct.modelLink().flatMap(net.cumba.web.api.Link::id)
                .orElse(null);
        if (modelVersion == null || modelVersion.isEmpty())
        {
            return null;
        }
        try
        {
            return aClient.getSdtmVersion("sdtm", modelVersion, true);
        }
        catch (IOException | RuntimeException e)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Failed to fetch SDTM Model version {0} (referenced by IG {1}); SUPP/SQ "
                            + "class-resolution will use the canonical hard-coded variable list. "
                            + "Cause: {2}",
                    modelVersion, aIgProduct.version().orElse("?"), String.valueOf(e));
            return null;
        }
    }

    /**
     * Maps a standard identifier string to the rule-engine standard kind. Mirrors the legacy
     * {@code StandardKind.fromName} that lived in both runtime entry points (CLI and manager
     * runtime); pulled into the facade so callers no longer need their own copy.
     */
    private enum StandardKind
    {

        SDTM, ADAM, UNKNOWN;

        static StandardKind fromName(@Nullable String aName)
        {
            if (aName == null)
            {
                return UNKNOWN;
            }
            String n = aName.toLowerCase(Locale.ROOT);
            if (n.startsWith("sdtm") || n.equals("send") || n.equals("sendig"))
            {
                return SDTM;
            }
            if (n.startsWith("adam"))
            {
                return ADAM;
            }
            return UNKNOWN;
        }
    }

}
