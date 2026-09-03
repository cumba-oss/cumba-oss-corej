package net.cumba.corej.core.metadata;

import java.util.List;
import java.util.Objects;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.datatable.metadata.IMetadataLibrary;
import org.jspecify.annotations.Nullable;

/**
 * Public facade for enriching a study {@link IMetadataLibrary} with CDISC standards metadata (SDTM
 * or ADaM + CT), producing either the enriched library itself or a ready-to-use
 * {@link MetadataProvider}.
 *
 * <p>
 * Two entry points are provided, one per standard, because each library represents exactly one
 * standard (see the design decision recorded during planning). The ADaM builder additionally
 * accepts an optional SDTM CT package as a codelist fallback, for ADaM variables that reference
 * SDTM terminology (e.g. {@code SEX}, {@code RACE}).
 * </p>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 *
 * // SDTM library enhancement
 * MetadataProvider sdtmProvider = LibraryMetadataEnhancer.forSdtm().study(studyMetadata)
 *         .standardName("sdtmig").standardVersion("3-4").sdtm(sdtmProduct).ct(sdtmCtPackage)
 *         .buildProvider();
 *
 * // ADaM library enhancement, with SDTM CT as codelist fallback
 * MetadataProvider adamProvider = LibraryMetadataEnhancer.forAdam().study(studyMetadata)
 *         .standardName("adamig").standardVersion("1-3").adam(adamProduct).ct(adamCtPackage)
 *         .sdtmCt(sdtmCtPackage) // optional
 *         .buildProvider();
 * }</pre>
 *
 * <p>
 * Either {@link SdtmBuilder#buildProvider()} / {@link AdamBuilder#buildProvider()} returns a
 * {@link MetadataProvider} for the rule engine, or {@link SdtmBuilder#buildMetadata()} /
 * {@link AdamBuilder#buildMetadata()} returns the underlying enriched {@link IMetadataLibrary} for
 * other uses (e.g. UI display).
 * </p>
 */
final class LibraryMetadataEnhancer
{

    private LibraryMetadataEnhancer()
    {
    }


    /**
     * Starts building an SDTM-scoped enhanced metadata library.
     */
    public static SdtmBuilder forSdtm()
    {
        return new SdtmBuilder();
    }


    /**
     * Starts building an ADaM-scoped enhanced metadata library.
     */
    public static AdamBuilder forAdam()
    {
        return new AdamBuilder();
    }

    // ------------------------------------------------------------------
    // SDTM builder
    // ------------------------------------------------------------------

    // Lombok-free fluent builder: required fields (standardVersion, product, ctPackage) are set
    // via setters and validated by Objects.requireNonNull in buildMetadata(); the all-fields
    // constructor cannot prove they are initialised. NullAway.Init is suppressed for that reason.
    @SuppressWarnings("NullAway.Init")
    public static final class SdtmBuilder
    {

        private @Nullable IMetadataLibrary study;

        private String standardName = "sdtmig";

        private String standardVersion;

        private SdtmProduct product;

        private @Nullable SdtmProduct modelProduct;

        private CtPackageRef ctPackage;

        private SdtmBuilder()
        {
        }


        /**
         * The study metadata library (primary). May be {@code null} if no study metadata is
         * available — in that case the CDISC standards library is used directly without enrichment,
         * which is mainly useful for tooling and tests.
         */
        public SdtmBuilder study(@Nullable IMetadataLibrary aStudy)
        {
            study = aStudy;
            return this;
        }


        /** The rule-engine standard identifier. Defaults to {@code "sdtmig"}. */
        public SdtmBuilder standardName(String aStandardName)
        {
            standardName = aStandardName;
            return this;
        }


        /** The rule-engine standard version, e.g. {@code "3-4"}. Required. */
        public SdtmBuilder standardVersion(String aStandardVersion)
        {
            standardVersion = aStandardVersion;
            return this;
        }


        /** The pre-fetched {@link SdtmProduct}. Required. */
        public SdtmBuilder sdtm(SdtmProduct aProduct)
        {
            product = aProduct;
            return this;
        }


        /**
         * Fix #61: optional pre-fetched SDTM <em>Model</em> product (e.g. {@code /mdr/sdtm/2-0}).
         * Used by the SUPP/SQ class-resolution branch to source the {@code RELATIONSHIP} class
         * variables when the IG's {@code SUPPQUAL} dataset has empty {@code datasetVariables}. May
         * be {@code null} — the resolver then falls through to the canonical hard-coded list.
         */
        public SdtmBuilder sdtmModel(@Nullable SdtmProduct aModelProduct)
        {
            modelProduct = aModelProduct;
            return this;
        }


        /**
         * The pre-fetched SDTM CT package paired with the id it was requested under. Required.
         *
         * @param aCtPackage
         *            the CT package reference.
         * @return this builder.
         */
        public SdtmBuilder ct(CtPackageRef aCtPackage)
        {
            ctPackage = aCtPackage;
            return this;
        }


        /**
         * As {@link #ct(CtPackageRef)}, taking the requested id and package separately.
         *
         * @param aCtPackageId
         *            the CT package id (e.g. {@code sdtmct-2024-09-27}); {@code null} when unknown,
         *            in which case {@code CtVersion} / {@code PublishedCtPackages} stay unset.
         * @param aCtPackage
         *            the CT package.
         * @return this builder.
         */
        public SdtmBuilder ct(@Nullable String aCtPackageId, CtPackage aCtPackage)
        {
            return ct(new CtPackageRef(aCtPackageId, aCtPackage));
        }


        /**
         * Builds the enriched {@link IMetadataLibrary}.
         */
        public IMetadataLibrary buildMetadata()
        {
            Objects.requireNonNull(standardName, "standardName");
            Objects.requireNonNull(standardVersion, "standardVersion");
            Objects.requireNonNull(product, "sdtm product");
            Objects.requireNonNull(ctPackage, "ct package");

            IMetadataLibrary standards = CdiscLibraryMetadataLibrary.fromSdtm(standardName,
                    standardVersion, product, ctPackage);
            if (study == null)
            {
                return standards;
            }
            return new EnrichedMetadataLibrary(study, standards);
        }


        /**
         * Builds the enriched metadata and wraps it in a {@link MetadataLibraryProvider} for the
         * rule engine. Threads the stored {@link SdtmProduct} through to the provider so
         * class-hierarchy queries (Fix #55) can walk it directly.
         */
        public MetadataProvider buildProvider()
        {
            return new MetadataLibraryProvider(buildMetadata(), product, modelProduct, standardName,
                    standardVersion);
        }
    }

    // ------------------------------------------------------------------
    // ADaM builder
    // ------------------------------------------------------------------


    // Lombok-free fluent builder: required fields (standardVersion, product, adamCtPackage) are set
    // via setters and validated by Objects.requireNonNull in buildMetadata(); the all-fields
    // constructor cannot prove they are initialised. NullAway.Init is suppressed for that reason.
    @SuppressWarnings("NullAway.Init")
    public static final class AdamBuilder
    {

        private @Nullable IMetadataLibrary study;

        private String standardName = "adamig";

        private String standardVersion;

        private AdamProduct product;

        private CtPackageRef adamCtPackage;

        private @Nullable CtPackageRef sdtmCtPackage;

        private List<MetadataLibraryProvider.DeclaredAdamProduct> declaredProducts = List.of();

        private AdamBuilder()
        {
        }


        /** The study metadata library (primary). See {@link SdtmBuilder#study}. */
        public AdamBuilder study(@Nullable IMetadataLibrary aStudy)
        {
            study = aStudy;
            return this;
        }


        /** The rule-engine standard identifier. Defaults to {@code "adamig"}. */
        public AdamBuilder standardName(String aStandardName)
        {
            standardName = aStandardName;
            return this;
        }


        /** The rule-engine standard version, e.g. {@code "1-3"}. Required. */
        public AdamBuilder standardVersion(String aStandardVersion)
        {
            standardVersion = aStandardVersion;
            return this;
        }


        /** The pre-fetched {@link AdamProduct}. Required. */
        public AdamBuilder adam(AdamProduct aProduct)
        {
            product = aProduct;
            return this;
        }


        /**
         * The full ordered list of <b>declared</b> ADaM products (ruling 1 of
         * {@code PLAN-metadata-product-selection}: first-match-wins on the user's
         * {@code --metadata-products} order), each paired with its {@code standards/...} cache key.
         *
         * <p>
         * ⚠ Distinct from {@link #adam(AdamProduct)}, and both are needed. The single
         * {@code product} is the <em>library</em> product: it is what
         * {@link CdiscLibraryMetadataLibrary#fromAdam} turns into the dataset/variable universe the
         * study is enriched against — and per §7-0 (owner ruling 2026-08-28) it is the <b>first
         * declared product of the run's own family</b>, i.e. this list's head, which under §1b′ is
         * the {@code -s}/{@code -v} product unless the user reordered deliberately
         * ({@code CdiscLibraryProviderBuilder.buildAdam} passes exactly that). The list here drives
         * only the <em>product-keyed</em> accessors (the structure-keyed variable lists and the
         * class-hierarchy walks). Empty (the default) ⇒ the provider is built from {@code product}
         * alone, exactly as before.
         * </p>
         *
         * @param aProducts
         *            the declared products, highest precedence first; {@code null} ⇒ empty.
         * @return this builder.
         */
        public AdamBuilder declaredProducts(
                @Nullable List<MetadataLibraryProvider.DeclaredAdamProduct> aProducts)
        {
            declaredProducts = aProducts != null ? List.copyOf(aProducts) : List.of();
            return this;
        }


        /**
         * The pre-fetched ADaM CT package paired with the id it was requested under. Required.
         *
         * @param aCtPackage
         *            the CT package reference.
         * @return this builder.
         */
        public AdamBuilder ct(CtPackageRef aCtPackage)
        {
            adamCtPackage = aCtPackage;
            return this;
        }


        /**
         * As {@link #ct(CtPackageRef)}, taking the requested id and package separately.
         *
         * @param aCtPackageId
         *            the ADaM CT package id (e.g. {@code adamct-2024-09-27}); {@code null} when
         *            unknown.
         * @param aCtPackage
         *            the CT package.
         * @return this builder.
         */
        public AdamBuilder ct(@Nullable String aCtPackageId, CtPackage aCtPackage)
        {
            return ct(new CtPackageRef(aCtPackageId, aCtPackage));
        }


        /**
         * Optional SDTM CT package used as a codelist fallback for ADaM variables that reference
         * SDTM-defined terminology (e.g. {@code SEX}, {@code RACE}).
         *
         * @param aSdtmCtPackage
         *            the SDTM CT package reference, or {@code null}.
         * @return this builder.
         */
        public AdamBuilder sdtmCt(@Nullable CtPackageRef aSdtmCtPackage)
        {
            sdtmCtPackage = aSdtmCtPackage;
            return this;
        }


        /**
         * As {@link #sdtmCt(CtPackageRef)}, taking the requested id and package separately.
         *
         * @param aCtPackageId
         *            the SDTM CT package id, or {@code null} when unknown.
         * @param aSdtmCtPackage
         *            the SDTM CT package.
         * @return this builder.
         */
        public AdamBuilder sdtmCt(@Nullable String aCtPackageId, CtPackage aSdtmCtPackage)
        {
            return sdtmCt(new CtPackageRef(aCtPackageId, aSdtmCtPackage));
        }


        /**
         * Builds the enriched {@link IMetadataLibrary}.
         */
        public IMetadataLibrary buildMetadata()
        {
            Objects.requireNonNull(standardName, "standardName");
            Objects.requireNonNull(standardVersion, "standardVersion");
            Objects.requireNonNull(product, "adam product");
            Objects.requireNonNull(adamCtPackage, "ct package");

            IMetadataLibrary standards = CdiscLibraryMetadataLibrary.fromAdam(standardName,
                    standardVersion, product, adamCtPackage, sdtmCtPackage);
            if (study == null)
            {
                return standards;
            }
            return new EnrichedMetadataLibrary(study, standards);
        }


        /**
         * Builds the enriched metadata and wraps it in a {@link MetadataLibraryProvider} for the
         * rule engine. Threads the stored {@link AdamProduct} through to the provider so
         * class-hierarchy queries (Fix #55) can walk it directly.
         */
        public MetadataProvider buildProvider()
        {
            IMetadataLibrary metadata = buildMetadata();
            if (declaredProducts.isEmpty())
            {
                return new MetadataLibraryProvider(metadata, product, standardName,
                        standardVersion);
            }
            return new MetadataLibraryProvider(metadata, declaredProducts, standardName,
                    standardVersion);
        }
    }

}
