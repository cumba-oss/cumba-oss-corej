package net.cumba.corej.core.metadata;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.corej.core.metadata.MetadataLibraryProvider.DeclaredAdamProduct;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.datatable.metadata.IMetadataLibrary;
import org.jspecify.annotations.Nullable;

/**
 * Test-only construction glue for api-model-typed fixtures (cache 8g). The production api-model
 * overloads of {@link CdiscLibraryMetadataLibrary} and {@link MetadataLibraryProvider} were deleted
 * with the pickle read path — the engine is typed on the store's records alone — but this module's
 * behavioural tests build their products as api-model maps
 * ({@code MapResource.of(map, SdtmProduct.class)}), and rewriting several thousand lines of
 * fixtures onto {@code Stored*} records would re-type the pins without re-proving them. So the
 * deleted glue lives on here, verbatim: each method projects the api-model fixture one-way through
 * {@link ApiModelProjection} (itself a test fixture now) and calls the surviving stored-typed
 * factory — the same seam the production overloads used, so the tests keep exercising exactly the
 * code the engine runs.
 *
 * <p>
 * ⚠ Do not use from production code, and do not add behaviour: anything beyond
 * project-then-delegate belongs in the stored-typed factories themselves.
 * </p>
 */
public final class ApiModelLibraries
{

    private ApiModelLibraries()
    {
    }


    /**
     * The deleted {@code CdiscLibraryMetadataLibrary.fromSdtm(name, version, product, ct)}:
     * {@code PUBLISHED_CT_PACKAGES} is just the single requested package's id.
     */
    public static CdiscLibraryMetadataLibrary fromSdtm(String aStandardName,
            String aStandardVersion, SdtmProduct aProduct, CtPackageRef aCtPackage)
    {
        Objects.requireNonNull(aCtPackage, "ctPackage");
        List<String> single = new ArrayList<>();
        if (aCtPackage.id() != null)
        {
            single.add(aCtPackage.id());
        }
        return fromSdtm(aStandardName, aStandardVersion, aProduct, List.of(aCtPackage), single);
    }


    /** The deleted five-arg {@code fromSdtm} with an explicit published-CT-packages list. */
    public static CdiscLibraryMetadataLibrary fromSdtm(String aStandardName,
            String aStandardVersion, SdtmProduct aProduct, CtPackageRef aCtPackage,
            List<String> aPublishedCtPackages)
    {
        Objects.requireNonNull(aCtPackage, "ctPackage");
        return fromSdtm(aStandardName, aStandardVersion, aProduct, List.of(aCtPackage),
                aPublishedCtPackages);
    }


    /** The deleted multi-package {@code fromSdtm}. */
    public static CdiscLibraryMetadataLibrary fromSdtm(String aStandardName,
            String aStandardVersion, SdtmProduct aProduct, List<CtPackageRef> aCtPackages,
            List<String> aPublishedCtPackages)
    {
        Objects.requireNonNull(aProduct, "product");
        Objects.requireNonNull(aCtPackages, "ctPackages");
        return CdiscLibraryMetadataLibrary.fromStoredSdtm(aStandardName, aStandardVersion,
                ApiModelProjection.product(aProduct), projectRefs(aCtPackages),
                aPublishedCtPackages);
    }


    /** The deleted {@code fromAdam(name, version, product, adamCt, sdtmCt)}. */
    public static CdiscLibraryMetadataLibrary fromAdam(String aStandardName,
            String aStandardVersion, AdamProduct aProduct, CtPackageRef aAdamCtPackage,
            @Nullable CtPackageRef aSdtmCtPackage)
    {
        Objects.requireNonNull(aAdamCtPackage, "adamCtPackage");
        return fromAdam(aStandardName, aStandardVersion, aProduct, List.of(aAdamCtPackage),
                aSdtmCtPackage == null ? List.of() : List.of(aSdtmCtPackage));
    }


    /**
     * The deleted multi-package {@code fromAdam}: {@code PUBLISHED_CT_PACKAGES} keeps the legacy
     * requested-ids derivation (the store-backed production path passes the store's whole
     * enumeration instead — plan §1.1-1).
     */
    public static CdiscLibraryMetadataLibrary fromAdam(String aStandardName,
            String aStandardVersion, AdamProduct aProduct, List<CtPackageRef> aAdamCtPackages,
            List<CtPackageRef> aSdtmCtPackages)
    {
        Objects.requireNonNull(aProduct, "product");
        Objects.requireNonNull(aAdamCtPackages, "adamCtPackages");
        Objects.requireNonNull(aSdtmCtPackages, "sdtmCtPackages");
        List<String> requestedIds = new ArrayList<>();
        for (CtPackageRef ref : aAdamCtPackages)
        {
            if (ref.id() != null)
            {
                requestedIds.add(ref.id());
            }
        }
        for (CtPackageRef ref : aSdtmCtPackages)
        {
            if (ref.id() != null)
            {
                requestedIds.add(ref.id());
            }
        }
        return CdiscLibraryMetadataLibrary.fromStoredAdam(aStandardName, aStandardVersion,
                ApiModelProjection.product(aProduct), projectRefs(aAdamCtPackages),
                projectRefs(aSdtmCtPackages), requestedIds);
    }


    /** The deleted {@code MetadataLibraryProvider(library, sdtmProduct, name, version)} ctor. */
    public static MetadataLibraryProvider provider(IMetadataLibrary aLibrary,
            @Nullable SdtmProduct aProduct, @Nullable String aStandardName,
            @Nullable String aStandardVersion)
    {
        return provider(aLibrary, aProduct, null, aStandardName, aStandardVersion);
    }


    /** The deleted IG-plus-Model constructor. */
    public static MetadataLibraryProvider provider(IMetadataLibrary aLibrary,
            @Nullable SdtmProduct aProduct, @Nullable SdtmProduct aModelProduct,
            @Nullable String aStandardName, @Nullable String aStandardVersion)
    {
        return MetadataLibraryProvider.forStoredSdtm(aLibrary, projectSdtm(aProduct),
                projectSdtm(aModelProduct), aStandardName, aStandardVersion);
    }


    /** The deleted CT-carrying constructor (the pickle factory's shape). */
    public static MetadataLibraryProvider provider(IMetadataLibrary aLibrary,
            @Nullable SdtmProduct aProduct, @Nullable SdtmProduct aModelProduct,
            @Nullable String aStandardName, @Nullable String aStandardVersion,
            @Nullable String aCtPackageId, @Nullable CtPackage aCtPackage,
            @Nullable Function<String, Optional<CtPackage>> aCtPackageLoader)
    {
        return MetadataLibraryProvider.forStoredSdtm(aLibrary, projectSdtm(aProduct),
                projectSdtm(aModelProduct), aStandardName, aStandardVersion, aCtPackageId,
                aCtPackage == null ? null : ApiModelProjection.ctPackage(aCtPackageId, aCtPackage),
                aCtPackageLoader == null ? null
                        : id -> aCtPackageLoader.apply(id)
                                .map(pkg -> ApiModelProjection.ctPackage(id, pkg)));
    }


    /** The deleted {@code MetadataLibraryProvider(library, adamProduct, name, version)} ctor. */
    public static MetadataLibraryProvider adamProvider(IMetadataLibrary aLibrary,
            @Nullable AdamProduct aProduct, @Nullable String aStandardName,
            @Nullable String aStandardVersion)
    {
        return MetadataLibraryProvider.forStoredAdam(aLibrary,
                aProduct == null ? null : ApiModelProjection.product(aProduct), aStandardName,
                aStandardVersion);
    }


    /** The deleted {@code DeclaredAdamProduct(cacheKey, AdamProduct)} compatibility ctor. */
    public static DeclaredAdamProduct declared(String aCacheKey, AdamProduct aProduct)
    {
        return new DeclaredAdamProduct(aCacheKey,
                ApiModelProjection.product(Objects.requireNonNull(aProduct, "product")));
    }


    private static @Nullable StoredProduct projectSdtm(@Nullable SdtmProduct aProduct)
    {
        return aProduct == null ? null : ApiModelProjection.product(aProduct);
    }


    /** The deleted {@code projectRefs} helper, verbatim. */
    private static List<StoredCtPackage> projectRefs(List<CtPackageRef> aRefs)
    {
        List<StoredCtPackage> out = new ArrayList<>(aRefs.size());
        for (CtPackageRef ref : aRefs)
        {
            out.add(ApiModelProjection.ctPackage(ref.id(), ref.pkg()));
        }
        return out;
    }
}
