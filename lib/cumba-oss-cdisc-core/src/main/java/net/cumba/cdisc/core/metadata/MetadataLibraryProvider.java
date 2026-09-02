package net.cumba.cdisc.core.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import lombok.CustomLog;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.library.api.model.adam.AdamDataStructure;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.cdisc.library.api.model.adam.AdamVariable;
import net.cumba.cdisc.library.api.model.adam.AdamVariableSet;
import net.cumba.cdisc.library.api.model.ct.CtCodelist;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.ct.CtTerm;
import net.cumba.cdisc.library.api.model.sdtm.SdtmClass;
import net.cumba.cdisc.library.api.model.sdtm.SdtmDataset;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.cdisc.library.api.model.sdtm.SdtmVariable;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.metadata.ICodeList;
import net.cumba.datatable.metadata.ICodelistEntry;
import net.cumba.datatable.metadata.IColumnMetadata;
import net.cumba.datatable.metadata.IDataTableMetadata;
import net.cumba.datatable.metadata.IMetadataElement;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;

/**
 * {@link MetadataProvider} implementation backed by an arbitrary {@link IMetadataLibrary} with
 * optional direct access to a pre-fetched CDISC Library {@link SdtmProduct} or {@link AdamProduct}
 * for class-hierarchy queries (Fix #55).
 *
 * <p>
 * This is the canonical adapter used by the rule engine to read metadata from any well-behaved
 * {@link IMetadataLibrary} producer. It does <em>not</em> know about any specific source format
 * (Define-XML, CDISC Library, dblib, etc.) — it reads the uniform meta-key contract described in
 * {@link MetadataKeys}, with documented fallbacks to the typed getters where available.
 * </p>
 *
 * <p>
 * When constructed with a typed {@link SdtmProduct} or {@link AdamProduct}, methods that need to
 * walk the model class hierarchy ({@link #getModelColumnOrder(String)},
 * {@link #getStandardModelVariables(IDataTable, DatasetResolver)},
 * {@link #getDatasetClass(String)}) consult the products directly rather than the flattened
 * per-table key contract; per-table study-side queries continue to flow through the underlying
 * {@link IMetadataLibrary} so Define-XML enrichment is preserved.
 * </p>
 *
 * <h2>Library-failure handling</h2>
 *
 * <p>
 * {@link #degraded(IMetadataLibrary, Throwable)} produces an instance whose class-hierarchy
 * accessors return the "library not available" signal (empty list for
 * {@link #getModelColumnOrder(String)}, {@code null} for
 * {@link #getStandardModelVariables(IDataTable, DatasetResolver)}, {@code null} for
 * {@link #getDatasetClass(String)}). All non-class-hierarchy queries continue to work via the
 * underlying {@link IMetadataLibrary}. The runtime entry points use this factory when the CDISC
 * Library product fetch fails (HTTP error, malformed response).
 * </p>
 *
 * <p>
 * Behavioural guarantees:
 * </p>
 * <ul>
 * <li>All list-returning methods return an empty list (never {@code null}) for unknown domains,
 * variables or codelists, except {@link #getStandardModelVariables(IDataTable, DatasetResolver)}
 * which returns {@code null} as the documented "library not available" signal.</li>
 * <li>All map-returning methods return an empty map (never {@code null}) under the same
 * conditions.</li>
 * <li>{@link #getStandard()} and {@link #getVersion()} return {@code null} when the underlying
 * library does not populate the respective meta keys.</li>
 * <li>{@link #isDomainCustom(String)} returns the value of the
 * {@link MetadataKeys#IS_CUSTOM_DOMAIN} key; absent is interpreted as {@code false}. An unknown
 * domain (not present in the library at all) is also treated as non-custom — the engine cannot know
 * whether a missing table is custom or simply not in the metadata.</li>
 * <li>Codelist lookup tries (1) {@link IMetadataLibrary#getCodelist(String)} by name, (2) by
 * {@link MetadataKeys#CODELIST_CONCEPT_ID} meta key, and (3) by
 * {@link MetadataKeys#CODELIST_SUBMISSION_VALUE} meta key. No format-specific string mangling.</li>
 * <li>Instances are immutable, stateless, and safe for concurrent use.</li>
 * </ul>
 */
@CustomLog
public final class MetadataLibraryProvider implements MetadataProvider
{

    private static final String ROLE_IDENTIFIER = "Identifier";

    private static final String ROLE_RECORD_QUALIFIER = "Record Qualifier";

    private static final String ATTR_ORDINAL = "ordinal";

    private static final String ATTR_LABEL = "label";

    private static final String ATTR_SIMPLE_DATATYPE = "simpleDatatype";

    private static final String VAR_USUBJID = "USUBJID";

    private static final String DOMAIN_SUPPQUAL = "SUPPQUAL";

    /**
     * <b>Fix #373</b> — the codelist family, i.e. exactly the attributes {@code columnToMap}
     * derives from a column's <em>bound codelist</em> and that leg 2's class walk cannot know.
     * These are the keys {@link #withCanonicalCodelist} back-fills from the canonical name.
     *
     * <p>
     * ⚠ Keep in sync with {@code columnToMap}. Adding a codelist-derived attribute there without
     * adding it here silently reintroduces the gap for {@code SUPP--}/{@code SQ--}/{@code AP--} —
     * the exact defect this list exists to close.
     * </p>
     */
    private static final List<String> CODELIST_ATTRIBUTES = List.of("codelist", "ccode",
            "codelist_coded_values", "codelist_coded_codes", "codelist_extensible");

    private static final String CLASS_FINDINGS_ABOUT = "FINDINGS ABOUT";

    private static final String CLASS_ASSOCIATED_PERSONS = "ASSOCIATED PERSONS";

    /**
     * FU-4: synthetic class token for a genuinely structure-less ADaM dataset (ADAMOTHER). Returned
     * by {@link #getDatasetClass(String, String, Set)} only when the run family is ADaM and every
     * resolution tier fell through, so that rules scoping {@code Scope.Classes.Include:["ADAM
     * OTHER"]} can still reach such datasets (Fix #41 strict-on-null would otherwise make them
     * unreachable by any Classes-scoped rule). Mirrors the Python
     * {@code rule_processor.rule_applies_to_class} "ADAM OTHER" fallback.
     */
    private static final String CLASS_ADAM_OTHER = "ADAM OTHER";

    /**
     * Product {@code class} strings that denote a structure token under a different spelling —
     * upper-cased keys, {@link AdamDataStructureDetector} tokens as values.
     *
     * <p>
     * The CDISC Library renamed the ADaM structure classes at {@code adamig-1-3}. Every other
     * {@code class} value in every ADaM product is already a detector token verbatim, so the first
     * three entries cover every product measured on 2026-08-27:
     * {@code adamig-1-0}/{@code 1-1}/{@code 1-2} ({@code ADSL}, {@code BDS}) and
     * {@code adam-occds-1-0} ({@code OCCDS}).
     * </p>
     *
     * <p>
     * ⭐ <b>{@code ADAE} is the fourth, and it does not travel alone.</b> {@code adam-adae-1-0}
     * publishes its single structure under the class {@code "ADAE"}, which is a spelling of the
     * occurrence data structure and nothing else — without the alias, declaring
     * {@code adam/adam-adae-1-0} is a <b>silent no-op</b>: no token reaches it. ⛔⛔ But the alias
     * alone is a false-positive machine. {@code ADAE} publishes {@code subClass == null}, so the
     * moment it shares the {@code OCCURRENCE DATA STRUCTURE} token with a base structure, the
     * governing chain reads it as a <em>base</em> and {@code required_variables()} demands its 5
     * AE-specific {@code Req} names ({@code AETERM}, {@code AEDECOD}, {@code AEBODSYS},
     * {@code AESER}, {@code AESEQ}) of <b>every</b> occurrence dataset — ADCM, ADMH, everything.
     * {@link #SUBCLASS_OVERRIDES} is what stops that, and the two entries must never be separated:
     * before the alias, {@code ADAE} was harmless only because it shared a token with nothing.
     * </p>
     */
    private static final Map<String, String> ADAM_CLASS_ALIASES = Map.of(//
            "ADSL", AdamDataStructureDetector.ADSL, //
            "BDS", AdamDataStructureDetector.BDS, //
            "OCCDS", AdamDataStructureDetector.OCCDS, //
            "ADAE", AdamDataStructureDetector.OCCDS);

    /**
     * A structure identified by the product that publishes it and its own name — the key shape of
     * {@link #STRUCTURE_CLASS_OVERRIDES} and {@link #SUBCLASS_OVERRIDES}.
     *
     * <p>
     * ⚠ Keyed on {@code (cacheKey, structureName)} rather than on the bare name so an override can
     * never leak onto a like-named structure in another product: {@code adamig-1-3} also publishes
     * a {@code BDS}, and {@code adam-occds-1-1} an {@code AE}.
     * </p>
     *
     * @param cacheKey
     *            the declaring product's {@code standards/...} cache key
     * @param structureName
     *            the structure's published {@code name}, canonicalised (trimmed, upper-cased)
     */
    private record StructureRef(String cacheKey, String structureName)
    {

        static StructureRef of(String aCacheKey, @Nullable String aStructureName)
        {
            return new StructureRef(aCacheKey.trim().toLowerCase(Locale.ROOT),
                    aStructureName == null ? "" : aStructureName.trim().toUpperCase(Locale.ROOT));
        }
    }

    /**
     * Published structures whose {@code class} is <b>null</b>, with the class the published content
     * establishes. Without an entry here such a structure is skipped outright by
     * {@link #adamStructuresForToken} and is unreachable by any token.
     *
     * <p>
     * One case today: {@code adam-tte-1-0}'s {@code BDS for TTE} publishes no class at all.
     * {@code BASIC DATA STRUCTURE} is not a guess — {@code adamig-1-3/TTE} publishes exactly that
     * class for the same concept, the structure carries {@code CNSR} (the engine's own TTE
     * signature column), and its six {@code Req} names ({@code AVAL}, {@code PARAM},
     * {@code PARAMCD}, {@code STUDYID}, {@code TRTP}, {@code USUBJID}) are BDS's own.
     * </p>
     */
    private static final Map<StructureRef, String> STRUCTURE_CLASS_OVERRIDES = Map.of(
            StructureRef.of("standards/adam/adam-tte-1-0", "BDS for TTE"),
            AdamDataStructureDetector.BDS);

    /**
     * Published structures whose {@code subClass} is <b>null</b> but whose identity as a
     * specialisation is established by their published variable content.
     *
     * <p>
     * ⛔⛔ <b>This is why "null {@code subClass} ⇒ base" is wrong.</b> A null {@code subClass} is an
     * absence of information, not a statement of base-ness, and {@link #governingChain} necessarily
     * reads it as one. Corroboration, measured 2026-08-27 against the pickle cache:
     * </p>
     * <ul>
     * <li>{@code adam-adae-1-0/ADAE} — <b>0</b> {@code core} conflicts against the {@code OCCDS}
     * base across 48 shared names (vs <b>7</b> against {@code adam-occds-1-1/AE}), and of its 37
     * unique variables <b>5 are {@code Req} and all 5 are AE-specific</b>. It is an
     * {@code ADVERSE EVENT} structure.</li>
     * <li>{@code adam-tte-1-0/BDS for TTE} — publishes {@code CNSR} and BDS's own {@code Req} set;
     * {@code adamig-1-3/TTE} publishes {@code TIME-TO-EVENT} for the same concept.</li>
     * </ul>
     *
     * <p>
     * ⚑ This is <b>not</b> synthesis (ruling 4). Ruling 4 forbids inventing metadata CDISC never
     * published — a label for {@code APAE}. Classifying a <em>published</em> structure into the
     * <em>published</em> vocabulary is a vocabulary alias, the same move
     * {@link #ADAM_CLASS_ALIASES} already makes for {@code adam-occds-1-0}'s class {@code "OCCDS"}.
     * </p>
     */
    private static final Map<StructureRef, String> SUBCLASS_OVERRIDES = Map.of(//
            StructureRef.of("standards/adam/adam-adae-1-0", "ADAE"),
            AdamSubclassDetector.ADVERSE_EVENT, //
            StructureRef.of("standards/adam/adam-tte-1-0", "BDS for TTE"),
            AdamSubclassDetector.TIME_TO_EVENT);

    private final IMetadataLibrary library;

    private final @Nullable SdtmProduct sdtmProduct;

    /**
     * Optional SDTM Model product (response from {@code /mdr/sdtm/{version}}), separate from the IG
     * product. Used by Fix #61 to resolve {@code SUPPQUAL} (and {@code SQ*}) variables via the
     * Model's {@code Relationship} class when the IG's {@code SUPPQUAL} dataset has empty
     * {@code datasetVariables}. May be {@code null} — the resolver then falls through to the
     * canonical hard-coded SUPPQUAL list (Fix #61 tier C).
     */
    private final @Nullable SdtmProduct sdtmModelProduct;

    /**
     * Declared ADaM products, in the user's precedence order (ruling 1 of
     * {@code plans/PLAN-metadata-product-selection.md}: first-match-wins). Never {@code null}; may
     * be empty (an SDTM/Define-only provider). Each entry carries the {@code standards/...} cache
     * key it was declared under, so every answer is traceable to the product that supplied it
     * (provenance is not optional with N products).
     */
    private final List<DeclaredAdamProduct> adamProducts;

    private final @Nullable String standardName;

    private final @Nullable String standardVersion;

    private final boolean libraryFailed;

    /**
     * The CT package id this provider was configured with (e.g. {@code "sdtmct-2024-09-27"}), or
     * {@code null} when no CT package was supplied. Paired with {@link #configuredCtPackage}.
     */
    private final @Nullable String configuredCtPackageId;

    /**
     * The pre-fetched {@link CtPackage} for {@link #configuredCtPackageId}, used by
     * {@link #getCodelistAttribute(String, String)} when the requested id matches the configured
     * one. {@code null} when no CT package was supplied.
     */
    private final @Nullable CtPackage configuredCtPackage;

    /**
     * Loader for CT packages other than the configured one (e.g. when a {@code TS} row references a
     * different version than {@code config.ct_packages[0]}). Backed by the pickle cache.
     * {@code null} when no pickle source is wired (network / Define-XML providers).
     */
    private final @Nullable Function<String, Optional<CtPackage>> ctPackageLoader;

    /**
     * One declared ADaM product with its provenance: the {@code standards/...} cache key it was
     * declared under (e.g. {@code standards/adam/adamig-1-3}) and the fetched product.
     */
    public record DeclaredAdamProduct(String cacheKey, AdamProduct product)
    {

        public DeclaredAdamProduct
        {
            Objects.requireNonNull(cacheKey, "cacheKey");
            Objects.requireNonNull(product, "product");
        }
    }


    /**
     * One published data structure together with the declared product that supplied it.
     *
     * <p>
     * ⚠⚠ <b>Provenance is per structure, not per answer</b> (plan Phase 8). Candidate structures
     * for a token are pooled across <em>all</em> declared products, so a single resolved list can
     * draw its governing tier from one product and the base behind it from another. Carrying the
     * cache key on each structure is what keeps every contributing structure traceable to the
     * product that published it — in the logs, and in the override lookups
     * ({@link #SUBCLASS_OVERRIDES}, {@link #STRUCTURE_CLASS_OVERRIDES}), which are keyed by product
     * precisely so they cannot leak onto a like-named structure elsewhere.
     * </p>
     */
    private record SourcedStructure(String cacheKey, AdamDataStructure structure)
    {

        /** {@code NAME@standards/...} — the provenance token used in log lines. */
        String describe()
        {
            return structure.name().orElse("?") + "@" + cacheKey;
        }
    }


    /**
     * One rung of the governing chain: every structure of a single specificity level
     * ({@code subClass}, or {@code null} for the base rung) supplied by <b>one</b> declared
     * product.
     *
     * <p>
     * Specificity orders the chain; the user's product order is only the <b>tie-break among equal
     * specificity</b> (plan Phase 8, ruling 1 × ruling 2). So the first declared product that
     * publishes anything at a level supplies that whole level, and the union that survives inside a
     * rung is a union among <em>that product's</em> equally-specific structures.
     * </p>
     */
    private record ChainTier(@Nullable String subClass, String cacheKey,
            List<SourcedStructure> structures)
    {
    }

    public MetadataLibraryProvider(IMetadataLibrary aLibrary)
    {
        this(aLibrary, null, null, List.of(), null, null, false, null, null, null);
    }


    /**
     * Builds a provider over a sponsor's Define-XML metadata, with <em>no</em> CDISC Library
     * product — i.e. the "define" level of the three-level metadata model (data / define /
     * library). Every per-variable / per-dataset query returns exactly what the Define-XML declares
     * (label, type, {@code core}, role, codelist), with no standards fallback. Equivalent to the
     * study-only constructor but named for its role so call sites read clearly.
     *
     * @param aDefine
     *            the Define-XML metadata library (e.g. {@code DefineMetadataLibrary})
     */
    public static MetadataLibraryProvider forDefine(IMetadataLibrary aDefine)
    {
        return new MetadataLibraryProvider(aDefine);
    }


    /**
     * Constructs a provider with direct access to a pre-fetched {@link SdtmProduct}. Class
     * hierarchy queries (model column order, standard model variables, dataset class) consult the
     * product directly. Per-table queries still flow through {@code aLibrary} so Define-XML
     * enrichment is preserved.
     */
    public MetadataLibraryProvider(IMetadataLibrary aLibrary, @Nullable SdtmProduct aProduct,
            @Nullable String aStandardName, @Nullable String aStandardVersion)
    {
        this(aLibrary, aProduct, null, List.of(), aStandardName, aStandardVersion, false, null,
                null, null);
    }


    /**
     * Fix #61: constructs a provider with both the SDTM-IG product and the underlying SDTM Model
     * product. The Model is consulted only by the SUPP/SQ class-resolution branch (tier B in the
     * A→B→C cascade); other queries continue to use the IG. {@code aModelProduct} may be
     * {@code null} when the Model fetch failed at runtime — the resolver then uses the canonical
     * hard-coded SUPPQUAL list (tier C) so SUPP/SQ rules still execute.
     */
    public MetadataLibraryProvider(IMetadataLibrary aLibrary, @Nullable SdtmProduct aProduct,
            @Nullable SdtmProduct aModelProduct, @Nullable String aStandardName,
            @Nullable String aStandardVersion)
    {
        this(aLibrary, aProduct, aModelProduct, List.of(), aStandardName, aStandardVersion, false,
                null, null, null);
    }


    /**
     * Fix: constructs an SDTM provider that additionally carries its configured {@link CtPackage}
     * (and a loader for other packages) so the {@code get_codelist_attributes} operation can
     * extract raw codelist/term attributes from the typed CT model. Used by
     * {@code PickleMetadataProviderFactory.forSdtm}.
     *
     * @param aCtPackageId
     *            the configured CT package id (may be {@code null})
     * @param aCtPackage
     *            the pre-fetched configured CT package (may be {@code null})
     * @param aCtPackageLoader
     *            loader for non-configured CT packages by id (may be {@code null})
     */
    public MetadataLibraryProvider(IMetadataLibrary aLibrary, @Nullable SdtmProduct aProduct,
            @Nullable SdtmProduct aModelProduct, @Nullable String aStandardName,
            @Nullable String aStandardVersion, @Nullable String aCtPackageId,
            @Nullable CtPackage aCtPackage,
            @Nullable Function<String, Optional<CtPackage>> aCtPackageLoader)
    {
        this(aLibrary, aProduct, aModelProduct, List.of(), aStandardName, aStandardVersion, false,
                aCtPackageId, aCtPackage, aCtPackageLoader);
    }


    /**
     * Constructs a provider with direct access to a pre-fetched {@link AdamProduct}. Class
     * hierarchy queries (model column order, standard model variables, dataset class) consult the
     * product directly. Per-table queries still flow through {@code aLibrary} so Define-XML
     * enrichment is preserved.
     */
    public MetadataLibraryProvider(IMetadataLibrary aLibrary, @Nullable AdamProduct aProduct,
            @Nullable String aStandardName, @Nullable String aStandardVersion)
    {
        this(aLibrary, null, null,
                aProduct == null ? List.<DeclaredAdamProduct> of()
                        : List.of(new DeclaredAdamProduct(
                                derivedCacheKey(aStandardName, aStandardVersion), aProduct)),
                aStandardName, aStandardVersion, false, null, null, null);
    }


    /**
     * Constructs a provider over an <b>ordered list</b> of declared ADaM products (first-match-wins
     * on the user's precedence order — ruling 1). The single-product constructor above is the
     * {@code List.of(...)} special case.
     *
     * @param aProducts
     *            the declared products with their cache keys, highest precedence first; may be
     *            empty (then {@link #supportsStructureKeyedVariables()} is {@code false})
     */
    public MetadataLibraryProvider(IMetadataLibrary aLibrary, List<DeclaredAdamProduct> aProducts,
            @Nullable String aStandardName, @Nullable String aStandardVersion)
    {
        this(aLibrary, null, null, aProducts, aStandardName, aStandardVersion, false, null, null,
                null);
    }


    /**
     * The {@code standards/...} cache key implied by a single-product constructor's standard name
     * and version (the same key an omitted {@code --metadata-products} defaults to), or a stable
     * placeholder when the caller supplied neither.
     */
    private static String derivedCacheKey(@Nullable String aStandardName,
            @Nullable String aStandardVersion)
    {
        if (aStandardName == null || aStandardVersion == null)
        {
            return "<undeclared adam product>";
        }
        return net.cumba.cdisc.core.metadata.pickle.PickleProductSource.standardsKey(aStandardName,
                aStandardVersion);
    }


    private MetadataLibraryProvider(IMetadataLibrary aLibrary, @Nullable SdtmProduct aSdtmProduct,
            @Nullable SdtmProduct aSdtmModelProduct, List<DeclaredAdamProduct> aAdamProducts,
            @Nullable String aStandardName, @Nullable String aStandardVersion,
            boolean aLibraryFailed, @Nullable String aCtPackageId, @Nullable CtPackage aCtPackage,
            @Nullable Function<String, Optional<CtPackage>> aCtPackageLoader)
    {
        library = Objects.requireNonNull(aLibrary, "library");
        sdtmProduct = aSdtmProduct;
        sdtmModelProduct = aSdtmModelProduct;
        adamProducts = List.copyOf(aAdamProducts);
        standardName = aStandardName;
        standardVersion = aStandardVersion;
        libraryFailed = aLibraryFailed;
        configuredCtPackageId = aCtPackageId;
        configuredCtPackage = aCtPackage;
        ctPackageLoader = aCtPackageLoader;
    }


    /**
     * Builds a provider whose class-hierarchy accessors signal "library not available" while still
     * delegating non-class-hierarchy queries to the underlying {@code aLibrary}. The {@code aCause}
     * is logged once at WARN with the runtime entry point's surrounding context.
     */
    public static MetadataLibraryProvider degraded(IMetadataLibrary aLibrary, Throwable aCause)
    {
        LOGGER.log(System.Logger.Level.WARNING,
                "CDISC Library product fetch failed; class-hierarchy queries will be reported as "
                        + "library-not-available. Cause: {0}",
                aCause != null ? String.valueOf(aCause) : "<null>");
        return new MetadataLibraryProvider(aLibrary, null, null, List.of(), null, null, true, null,
                null, null);
    }

    // ------------------------------------------------------------------
    // Standard context
    // ------------------------------------------------------------------


    @Override
    public @Nullable String getStandard()
    {
        if (standardName != null)
        {
            return standardName;
        }
        return metaString(library, MetadataKeys.STANDARD_NAME).orElse(null);
    }


    @Override
    public @Nullable String getVersion()
    {
        if (standardVersion != null)
        {
            return standardVersion;
        }
        return metaString(library, MetadataKeys.STANDARD_VERSION).orElse(null);
    }


    @Override
    public @Nullable String getDefineVersion()
    {
        return metaString(library, IMetadataLibrary.META_KEY_DEFINE_VERSION).orElse(null);
    }


    /**
     * Fix #369 — {@code true} for a provider built by
     * {@link #degraded(IMetadataLibrary, Throwable)}, i.e. one whose CDISC Library product fetch
     * threw. The provider itself keeps answering truthfully from its study library; it is
     * {@code OperationExecutor.evalLibrary} that decides a non-library source is not an admissible
     * basis for a library-citing rule.
     */
    @Override
    public boolean isLibraryUnavailable()
    {
        return libraryFailed;
    }

    // ------------------------------------------------------------------
    // Variable queries
    // ------------------------------------------------------------------


    @Override
    public List<String> getRequiredVariables(String aDomain)
    {
        if (!hasSdtmProduct())
        {
            return columnNamesWhere(aDomain, col -> "Req".equals(coreOf(col)));
        }
        return resolvedNamesWhereCore(aDomain, core -> "Req".equals(core));
    }


    @Override
    public List<String> getExpectedVariables(String aDomain)
    {
        if (!hasSdtmProduct())
        {
            return columnNamesWhere(aDomain, col ->
            {
                String core = coreOf(col);
                return "Req".equals(core) || "Exp".equals(core);
            });
        }
        return resolvedNamesWhereCore(aDomain, core -> "Req".equals(core) || "Exp".equals(core));
    }


    @Override
    public List<String> getColumnOrder(String aDomain)
    {
        if (!hasSdtmProduct())
        {
            return columnsOf(aDomain).stream().map(IColumnMetadata::getName).toList();
        }
        return namesOf(buildResolvedSdtm(aDomain));
    }


    @Override
    public boolean supportsStructureKeyedVariables()
    {
        return hasAdamProduct();
    }


    /** The declared ADaM products' cache keys, in precedence order (provenance for logs). */
    @Override
    public List<String> declaredStructureKeyedProducts()
    {
        return adamProducts.stream().map(DeclaredAdamProduct::cacheKey).toList();
    }


    @Override
    public @Nullable List<String> getRequiredVariablesForStructure(String aStructureToken,
            List<String> aSubclassTokens)
    {
        return adamNamesWhereCore(aStructureToken, aSubclassTokens, core -> "Req".equals(core));
    }


    @Override
    public @Nullable List<String> getExpectedVariablesForStructure(String aStructureToken,
            List<String> aSubclassTokens)
    {
        // ADaM has no `Exp` core value at all (Req / Cond / Perm), so this is deliberately the
        // same predicate as the Required accessor rather than a widened one. See
        // MetadataProvider#getExpectedVariablesForStructure.
        return adamNamesWhereCore(aStructureToken, aSubclassTokens,
                core -> "Req".equals(core) || "Exp".equals(core));
    }


    /**
     * <b>Ruling 2 — the published {@code subClass} selects the governing structure.</b> The
     * published variable names for {@code aStructureToken} whose {@code core} satisfies
     * {@code aCorePredicate}, or {@code null} when no declared product has an <em>applicable</em>
     * structure for that token.
     *
     * <h4>The precedence chain, first tier wins per name</h4>
     *
     * <ol>
     * <li>the structures whose {@link AdamDataStructure#subClass()} equals a detected subclass
     * token, one tier per token in {@code aSubclassTokens} order (most specific first);</li>
     * <li>the <b>base</b> structures for the token — those publishing no {@code subClass}.</li>
     * </ol>
     *
     * <p>
     * ⭐ <b>The candidates are pooled across every declared product</b> (plan Phase 8). Specificity
     * decides the chain; the user's product order is the tie-break <em>among equal specificity
     * only</em>. So {@code -mp adam/adamig-1-3,adam/adam-nca-1-0} resolves an NCA dataset's
     * {@code BASIC DATA STRUCTURE} through {@code adam-nca-1-0}'s {@code ADNCA} even though
     * {@code adamig-1-3} — declared first — publishes a base BDS. Before Phase 8 the lookup
     * returned on the first product having <em>any</em> structure for the token and ran the chain
     * inside it, so that declaration silently resolved against the base BDS and the supplement was
     * never consulted. Ruling 2 is about <b>structures</b>, not products.
     * </p>
     *
     * <p>
     * ⚠ The chain <b>governs, it does not replace</b>. A tier claims every name it publishes —
     * whatever that name's {@code core} — and a later tier may neither re-state it nor overrule it;
     * but a name no earlier tier published still resolves from the tier behind it. So with
     * {@code adam-occds-1-1} and a detected {@code ADVERSE EVENT}: {@code --SEQ} is {@code Req}
     * because {@code AE} says so (the base {@code OCCDS} says {@code Cond} and is overruled), while
     * {@code CMTRT} — published only by {@code OCCDS} — is still Required.
     * </p>
     *
     * <p>
     * ⚠⚠ With <b>no</b> detected subclass the chain starts at step 2: <b>base structures only,
     * never the union</b>. That is the majority case, and it is what retires the silent
     * most-strict-wins merge this method used to perform — under which {@code adam-occds-1-1} made
     * {@code AEDECOD} and {@code --SEQ} Required of <em>every</em> occurrence dataset, because
     * <em>some</em> contributing structure said {@code Req}.
     * </p>
     *
     * <p>
     * The union survives <b>only inside one tier</b> — contributors of equal specificity (the same
     * {@code subClass}, or all of them base) — and is still logged there, so a product in which two
     * equally-specific structures genuinely disagree surfaces rather than averaging quietly.
     * </p>
     *
     * <p>
     * ⛔ <b>An empty chain is {@code null}, never an empty list.</b> A token can resolve to
     * structures that are <em>all</em> subclass-specific and none of them the dataset's — e.g.
     * {@code adam-nca-1-0} publishes {@code BASIC DATA STRUCTURE} only as
     * {@code NON-COMPARTMENTAL ANALYSIS}, so a plain BDS dataset has nothing applicable there.
     * Returning {@code List.of()} would say <em>"this structure requires nothing"</em> and pass the
     * rule vacuously; {@code null} says <em>"no such structure here"</em> and lets
     * {@code OperationExecutor}'s outer token chain try the next token, then SKIP loudly.
     * </p>
     *
     * <p>
     * ⚠ Since Phase 8 that emptiness is a statement about <b>every</b> declared product, not about
     * the first one to mention the token: the chain is empty only when no declared product
     * publishes an applicable structure. A plain BDS dataset under
     * {@code -mp adam/adam-nca-1-0,adam/adamig-1-3} therefore resolves against {@code adamig-1-3}'s
     * base BDS instead of going {@code null} on {@code adam-nca-1-0}'s subclass-only structure.
     * </p>
     *
     * <p>
     * ⚠⚠ Reads {@code adamProducts} <b>directly</b>, never {@code library}. That is the whole
     * point. On an ADaM run without a CT package {@code CdiscLibraryProviderBuilder.buildAdam}
     * binds {@code library} to the <em>study</em>, whose columns carry no {@code core} attribute —
     * so the domain-keyed {@link #getRequiredVariables} returns an empty list for <em>every</em>
     * dataset, {@code ADSL} included, and the rule passes green with the defect present.
     * </p>
     *
     * <p>
     * Ordering mirrors {@link CdiscLibraryMetadataLibrary#fromAdam}: variable sets flattened in
     * declaration order, then sorted by ordinal, so a reported list reads in the standard's own
     * order — now tier by tier, the governing structure's names first. Duplicates are dropped,
     * first occurrence winning.
     * </p>
     */
    private @Nullable List<String> adamNamesWhereCore(String aStructureToken,
            List<String> aSubclassTokens, Predicate<@Nullable String> aCorePredicate)
    {
        if (!hasAdamProduct() || aStructureToken == null)
        {
            return null;
        }
        List<ChainTier> chain = governingTiers(aStructureToken, aSubclassTokens);
        if (chain.isEmpty())
        {
            return null;
        }
        Set<String> out = new LinkedHashSet<>();
        Set<String> governed = new HashSet<>();
        for (ChainTier tier : chain)
        {
            if (tier.structures().size() > 1)
            {
                LOGGER.log(System.Logger.Level.INFO,
                        "Structure token {0} maps to {1} equally-specific data structures in {2} "
                                + "({3}, subClass {4}); their published variables are unioned",
                        aStructureToken, tier.structures().size(), tier.cacheKey(),
                        tier.structures().stream().map(s -> s.structure().name().orElse("?"))
                                .toList(),
                        tier.subClass());
            }
            Set<String> claimed = new LinkedHashSet<>();
            for (SourcedStructure sourced : tier.structures())
            {
                AdamDataStructure ds = sourced.structure();
                List<AdamVariable> flattened = new ArrayList<>();
                for (AdamVariableSet set : ds.analysisVariableSets())
                {
                    flattened.addAll(set.analysisVariables());
                }
                for (AdamVariable v : sortAdamByOrdinal(flattened))
                {
                    String name = v.name().orElse(null);
                    if (name == null || governed.contains(name))
                    {
                        // Governed by a more specific tier: its `core` there is the answer, and
                        // this tier may neither re-state nor overrule it. This is the line that
                        // retires most-strict-wins.
                        continue;
                    }
                    claimed.add(name);
                    if (aCorePredicate.test(v.core().orElse(null)))
                    {
                        out.add(name);
                    }
                }
            }
            governed.addAll(claimed);
        }
        return List.copyOf(out);
    }


    /**
     * ⭐⭐ <b>The single entry point to cross-product structure resolution.</b> Pools every declared
     * product's structures for {@code aStructureToken}, runs {@link #governingChain} over the
     * pooled set, announces provenance, and returns the resulting tiers most-specific-first. Empty
     * when no declared product publishes the token at all, and equally empty when the token exists
     * only under subclasses this dataset does not have (plan §3d — that emptiness is a statement
     * about <em>every</em> declared product, and is deliberately not distinguishable from "no
     * product has it": both mean "treat the structure as absent").
     *
     * <p>
     * ⛔⛔ <b>Phase 11 finding F1 — this method exists so there is exactly ONE of it.</b> Phase 8
     * fixed the ordering defect inside {@link #adamNamesWhereCore} only. Its neighbours
     * {@code findAdamDataStructureByClassName} and {@code adamDataStructureFor} had been widened
     * from one product to N in Phase 2 and left as plain first-match-wins walks over the raw
     * published {@code class} string — no specificity, no {@link #ADAM_CLASS_ALIASES}, no
     * product-keyed overrides. Measured: {@code -mp adam/adam-nca-1-0,adam/adamig-1-3} on a plain
     * BDS dataset resolved against {@code ADNCA}, while the reverse declaration order resolved
     * against {@code BDS} — the same defect Phase 8 had just retired one method away. Both
     * neighbours now route through here, so "the answer must not depend on declaration order" holds
     * for the domain-keyed accessors too, not only for the structure-keyed ones.
     * </p>
     *
     * @param aStructureToken
     *            a canonical {@link AdamDataStructureDetector#STRUCTURE_TOKENS} token
     * @param aSubclassTokens
     *            the dataset's detected subclass tokens, most specific first; may be empty, in
     *            which case only the base tier can govern
     */
    private List<ChainTier> governingTiers(String aStructureToken, List<String> aSubclassTokens)
    {
        List<SourcedStructure> pooled = adamStructuresForToken(aStructureToken);
        if (pooled.isEmpty())
        {
            return List.of();
        }
        List<ChainTier> chain = governingChain(pooled, aSubclassTokens);
        if (chain.isEmpty())
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "Structure token {0} exists in the declared product(s) {1} only under "
                            + "subclasses this dataset does not have ({2} published, {3} "
                            + "detected); treating the structure as absent rather than as "
                            + "requiring nothing",
                    aStructureToken,
                    pooled.stream().map(SourcedStructure::cacheKey).distinct().toList(),
                    pooled.stream().map(s -> s.describe() + " ("
                            + subClassOf(s.cacheKey(), s.structure()) + ")").toList(),
                    aSubclassTokens);
            return List.of();
        }
        logPooledProvenance(aStructureToken, chain);
        return chain;
    }


    /**
     * The governing chain for {@code aPooled} — the candidate structures for one token, pooled
     * across <b>every</b> declared product in the user's precedence order. One tier per detected
     * subclass token that some structure publishes (in {@code aSubclassTokens} order, most specific
     * first), then a final tier of the base structures (those publishing no {@code subClass}).
     * Empty tiers are omitted, and a structure whose {@code subClass} is <b>not</b> among the
     * detected tokens is excluded altogether — it describes a dataset this is not.
     *
     * <p>
     * ⭐ <b>Specificity is the primary key; the user's product order is only the tie-break among
     * equal specificity</b> (plan Phase 8). Each tier is therefore supplied by exactly one product
     * — {@link #tierAt} picks the first declared product publishing anything at that level — but
     * <em>different</em> tiers may come from different products, which is the whole point: a
     * supplement's specialisation governs even when the base product is declared first.
     * </p>
     *
     * <p>
     * ⚠ {@code subClass == null} is read as <em>base</em> here, which is right for every product
     * that pairs a base with its specialisations. It is <b>not</b> universally true —
     * {@code adam-adae-1-0}'s {@code ADAE} publishes no {@code subClass} and is emphatically not a
     * base. {@link #SUBCLASS_OVERRIDES} supplies the missing value for the two known cases, which
     * is why this method reads its subclass through {@link #subClassOf(String, AdamDataStructure)}
     * rather than off the structure directly; that indirection is what keeps
     * {@code ADAE → OCCURRENCE DATA STRUCTURE} from turning every occurrence dataset into an
     * adverse-event one. Since the pool spans products, the subclass is resolved against <b>each
     * structure's own</b> supplying product, so a product-keyed override can never fire on another
     * product's like-named structure.
     * </p>
     */
    private static List<ChainTier> governingChain(List<SourcedStructure> aPooled,
            List<String> aSubclassTokens)
    {
        List<ChainTier> chain = new ArrayList<>();
        Set<String> seenTokens = new LinkedHashSet<>();
        for (String raw : aSubclassTokens != null ? aSubclassTokens : List.<String> of())
        {
            if (raw == null || raw.isBlank())
            {
                continue;
            }
            String token = raw.trim().toUpperCase(Locale.ROOT);
            if (!seenTokens.add(token))
            {
                continue;
            }
            ChainTier tier = tierAt(aPooled, token);
            if (tier != null)
            {
                chain.add(tier);
            }
        }
        ChainTier base = tierAt(aPooled, null);
        if (base != null)
        {
            chain.add(base);
        }
        return List.copyOf(chain);
    }


    /**
     * The tier for one specificity level: the structures at that level published by the <b>first
     * declared product</b> that publishes any, or {@code null} when no pooled structure sits at the
     * level.
     *
     * <p>
     * ⚠⚠ <b>This is where ruling 1 lives after Phase 8.</b> Product order no longer decides which
     * product answers a <em>token</em> — that would hide a supplement's specialisation behind a
     * base product declared first. It decides which product answers a <em>specificity level</em>,
     * i.e. it is a tie-break between descriptions that are equally entitled to speak. When a later
     * product loses that tie-break it is logged, so the suppression is traceable rather than
     * silent.
     * </p>
     *
     * @param aPooled
     *            candidate structures for one token, in declared-product order
     * @param aSubClass
     *            the canonicalised subclass token, or {@code null} for the base level
     */
    private static @Nullable ChainTier tierAt(List<SourcedStructure> aPooled,
            @Nullable String aSubClass)
    {
        Map<String, List<SourcedStructure>> byProduct = new LinkedHashMap<>();
        for (SourcedStructure sourced : aPooled)
        {
            if (Objects.equals(aSubClass, subClassOf(sourced.cacheKey(), sourced.structure())))
            {
                byProduct.computeIfAbsent(sourced.cacheKey(), _ -> new ArrayList<>()).add(sourced);
            }
        }
        if (byProduct.isEmpty())
        {
            return null;
        }
        Map.Entry<String, List<SourcedStructure>> winner = byProduct.entrySet().iterator().next();
        if (byProduct.size() > 1)
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "{0} declared products publish an equally-specific data structure (subClass "
                            + "{1}); {2} governs it by declaration order and {3} is not consulted "
                            + "at that specificity",
                    byProduct.size(), aSubClass != null ? aSubClass : "<base>", winner.getKey(),
                    byProduct.keySet().stream().skip(1).toList());
        }
        return new ChainTier(aSubClass, winner.getKey(), List.copyOf(winner.getValue()));
    }


    /**
     * Announces a resolved chain whose tiers were supplied by <b>more than one</b> declared
     * product, naming the product behind every contributing structure.
     *
     * <p>
     * ⚠⚠ <b>Provenance must survive pooling.</b> Before Phase 8 one answer came from one product
     * and the cache key on the match was the whole story. A pooled answer can mix products — the
     * governing tier from a supplement, the base behind it from the IG — and a finding that names
     * no product is not traceable back to the metadata that produced it. Single-product chains are
     * left unlogged: they are the overwhelming majority and their provenance is already the run's
     * single declared product.
     * </p>
     */
    private static void logPooledProvenance(String aStructureToken, List<ChainTier> aChain)
    {
        if (aChain.stream().map(ChainTier::cacheKey).distinct().count() < 2)
        {
            return;
        }
        List<String> contributors = new ArrayList<>();
        for (ChainTier tier : aChain)
        {
            for (SourcedStructure sourced : tier.structures())
            {
                contributors.add(sourced.describe() + " ("
                        + (tier.subClass() != null ? tier.subClass() : "base") + ")");
            }
        }
        LOGGER.log(System.Logger.Level.INFO,
                "Structure token {0} resolves across {1} declared products; contributing "
                        + "structures, most specific first: {2}",
                aStructureToken, aChain.stream().map(ChainTier::cacheKey).distinct().count(),
                contributors);
    }


    /**
     * A structure's {@code subClass}, canonicalised; {@code null} when it is a base structure.
     * Reads {@link #SUBCLASS_OVERRIDES} first — a published null is an <em>absence of
     * information</em>, and for the two structures listed there the published variable content
     * supplies what the field omits.
     */
    private static @Nullable String subClassOf(String aCacheKey, AdamDataStructure aStructure)
    {
        String override = SUBCLASS_OVERRIDES
                .get(StructureRef.of(aCacheKey, aStructure.name().orElse(null)));
        if (override != null)
        {
            return override;
        }
        String raw = aStructure.subClass().orElse(null);
        if (raw == null || raw.isBlank())
        {
            return null;
        }
        return raw.trim().toUpperCase(Locale.ROOT);
    }


    /**
     * The structure token {@code aStructure} maps onto — its published {@code class} run through
     * {@link #ADAM_CLASS_ALIASES}, or {@link #STRUCTURE_CLASS_OVERRIDES} when the product publishes
     * no class at all — or {@code null} when it maps to no
     * {@link AdamDataStructureDetector#STRUCTURE_TOKENS} token and is therefore invisible to every
     * lookup.
     *
     * <p>
     * ⚠⚠ <b>The single mapping choke point.</b> {@link #adamStructuresForToken} matches against it
     * and {@code CdiscLibraryProviderBuilder}'s declaration-time check (plan §6b) reports on it, so
     * "which structures can a token reach" and "which structures did we just tell the user are
     * unreachable" cannot drift apart.
     * </p>
     *
     * @param aCacheKey
     *            the declaring product's cache key — the override maps are keyed by it
     * @param aStructure
     *            a published ADaM data structure
     * @return the canonical structure token, or {@code null} when the structure maps to none
     */
    static @Nullable String structureTokenOf(String aCacheKey, AdamDataStructure aStructure)
    {
        String override = STRUCTURE_CLASS_OVERRIDES
                .get(StructureRef.of(aCacheKey, aStructure.name().orElse(null)));
        if (override != null)
        {
            return override;
        }
        return canonicalStructureToken(aStructure.className().orElse(null));
    }


    /**
     * A published — or study-declared — ADaM {@code class} string reduced to its canonical
     * {@link AdamDataStructureDetector#STRUCTURE_TOKENS} token, or {@code null} when it maps to
     * none.
     *
     * <p>
     * ⚠⚠ <b>The alias step is the whole point.</b> The CDISC Library renamed the ADaM structure
     * classes at {@code adamig-1-3}: {@code 1-0}/{@code 1-1}/{@code 1-2} spell them {@code ADSL}
     * and {@code BDS}, {@code adam-occds-1-0} spells {@code OCCDS}, {@code adam-adae-1-0} spells
     * {@code ADAE}. A comparison against the raw string alone answers {@code 1-3} and silently
     * matches nothing for the older products.
     * </p>
     *
     * <p>
     * ⛔ <b>Phase 11 finding F1.</b> This was inlined in {@link #structureTokenOf} while
     * {@code findAdamDataStructureByClassName} compared raw {@code class} strings and
     * {@code adamClassForDomain} returned one verbatim — so declaring {@code adam/adam-adae-1-0}
     * made {@code getDatasetClass("ADAE")} answer the raw {@code "ADAE"}, which is in no token
     * vocabulary any consumer knows ({@code OperationExecutor}'s class-keyed grouping,
     * {@code LibraryValidator}'s AP-inherit walk, {@code ScopeMatcher.matchesClass}). Factoring it
     * here makes the mapping a single implementation shared by every caller.
     * </p>
     */
    static @Nullable String canonicalStructureToken(@Nullable String aPublishedClass)
    {
        if (aPublishedClass == null || aPublishedClass.isBlank())
        {
            return null;
        }
        String upper = aPublishedClass.trim().toUpperCase(Locale.ROOT);
        String canonical = ADAM_CLASS_ALIASES.getOrDefault(upper, upper);
        return AdamDataStructureDetector.STRUCTURE_TOKENS.contains(canonical) ? canonical : null;
    }


    /**
     * Every {@link AdamDataStructure} any declared product publishes for {@code aStructureToken},
     * <b>pooled across all of them</b> and carrying each one's supplying product. Structures appear
     * in declared-product order, and within a product in the product's own order. Empty when no
     * declared product defines a structure for the token.
     *
     * <p>
     * ⛔ <b>Phase 8 — this method used to stop at the first product that yielded anything</b>, and
     * the subclass chain then ran inside that one product. With
     * {@code -mp adam/adamig-1-3,adam/adam-nca-1-0} and the token {@code BASIC DATA STRUCTURE},
     * {@code adamig-1-3}'s base BDS matched, the method returned, and {@code adam-nca-1-0}'s
     * {@code ADNCA} was <em>never seen</em> — an NCA dataset silently resolved against the base
     * structure. It only looked correct for {@code OCCDS} because {@code adamig-1-3} publishes no
     * occurrence structure at all. Pooling is what makes ruling 2 a statement about structures
     * rather than about products; ruling 1 survives as {@link #tierAt}'s tie-break among equal
     * specificity.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>The alias map is mandatory, not a convenience.</b> The {@code class} strings were
     * renamed at {@code adamig-1-3}: {@code 1-0}/{@code 1-1}/{@code 1-2} spell them {@code ADSL}
     * and {@code BDS}, {@code 1-3} spells them {@code SUBJECT LEVEL ANALYSIS DATASET} and
     * {@code BASIC DATA STRUCTURE} (and {@code adam-occds-1-0} spells its {@code OCCDS} where
     * {@code adam-occds-1-1} spells {@code OCCURRENCE DATA STRUCTURE}). A resolver keyed on the raw
     * string alone answers {@code 1-3} and returns silently nothing for the three older versions —
     * the same silent-empty failure the {@code lint-rules.py} version-key bug produced, which went
     * unnoticed from the day it was written.
     * </p>
     */
    private List<SourcedStructure> adamStructuresForToken(String aStructureToken)
    {
        String token = aStructureToken.trim().toUpperCase(Locale.ROOT);
        List<SourcedStructure> pooled = new ArrayList<>();
        for (DeclaredAdamProduct declared : adamProducts)
        {
            for (AdamDataStructure ds : declared.product().dataStructures())
            {
                if (token.equals(structureTokenOf(declared.cacheKey(), ds)))
                {
                    pooled.add(new SourcedStructure(declared.cacheKey(), ds));
                }
            }
        }
        return List.copyOf(pooled);
    }


    /** True when at least one ADaM product is declared and the library has not failed. */
    private boolean hasAdamProduct()
    {
        return !libraryFailed && !adamProducts.isEmpty();
    }


    /** True when an SDTM IG product is configured and the library has not failed. */
    private boolean hasSdtmProduct()
    {
        return !libraryFailed && sdtmProduct != null;
    }


    /**
     * Algorithm-B name filter by {@code core} attribute. Builds the IG-base + Model-merge resolved
     * list ({@link #buildResolvedSdtm}) and keeps the substituted names whose {@code core}
     * attribute satisfies {@code aCorePredicate}, preserving merge order.
     */
    private List<String> resolvedNamesWhereCore(String aDomain,
            Predicate<@Nullable String> aCorePredicate)
    {
        List<String> out = new ArrayList<>();
        for (ResolvedVariable v : buildResolvedSdtm(aDomain))
        {
            if (aCorePredicate.test(v.attributes().get("core")))
            {
                out.add(v.name());
            }
        }
        return Collections.unmodifiableList(out);
    }


    /**
     * Returns the model-level column order for the given domain.
     *
     * <p>
     * Resolution order (Fix #55):
     * </p>
     * <ol>
     * <li><b>Product-first:</b> when an {@link SdtmProduct} or {@link AdamProduct} is configured
     * (and {@link #libraryFailed} is false), walk the product hierarchy to find the class owning
     * the dataset and return {@code SdtmClass.classVariables()} / the ADaM data-structure variable
     * set names ordered by ordinal. Domains not in the loaded product return an empty list.</li>
     * <li><b>Legacy fallback:</b> when no product is configured (no CDISC Library / no CT package
     * path, or pre-Fix-#55 callers), read {@link MetadataKeys#MODEL_COLUMN_ORDER} from the
     * per-table meta. This is documented as legacy and pays the price of being a flattened
     * representation; retiring the writer side is the deferred Fix #55 follow-up.</li>
     * </ol>
     */
    @Override
    public List<String> getModelColumnOrder(String aDomain)
    {
        if (libraryFailed)
        {
            return List.of();
        }
        if (sdtmProduct != null)
        {
            // Empty list either means "domain not in product" (custom) or "class has no
            // class-vars".
            // The Phase 1 SKIP shim in OperationExecutor maps empty → LIBRARY_NOT_AVAILABLE for
            // model column order operations, which is the correct behaviour for both cases.
            return sdtmModelColumnOrder(aDomain);
        }
        if (!adamProducts.isEmpty())
        {
            return adamModelColumnOrder(aDomain);
        }
        // Legacy path — pre-Fix-#55 callers and the no-product fallback.
        Optional<IDataTableMetadata> table = library.getDataTable(aDomain);
        if (table.isEmpty())
        {
            return List.of();
        }
        Optional<Object> raw = table.get().getMetaValue(MetadataKeys.MODEL_COLUMN_ORDER);
        if (raw.isEmpty())
        {
            return List.of();
        }
        Object value = raw.get();
        if (value instanceof List<?> list)
        {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of();
    }


    /**
     * EC-13 — the union of variable NAMES across every dataset the loaded SDTM IG product defines.
     * Walks {@code sdtmProduct.classes() → SdtmClass.datasets() → SdtmDataset.datasetVariables() →
     * name()} into an order-preserving {@link LinkedHashSet}, mirroring the Python reference
     * engine's {@code variable_names} (union of names over {@code variables_metadata}).
     *
     * @return the order-preserving union, or {@code null} when the library failed to load or no
     *         SDTM IG product is configured (⇒ the {@code variable_names} operation SKIPs the rule)
     */
    @Override
    public @Nullable List<String> getStandardVariableNames()
    {
        if (libraryFailed || sdtmProduct == null)
        {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        for (SdtmClass klass : sdtmProduct.classes())
        {
            for (SdtmDataset ds : klass.datasets())
            {
                for (SdtmVariable v : ds.datasetVariables())
                {
                    v.name().ifPresent(names::add);
                }
            }
        }
        return List.copyOf(names);
    }


    /**
     * <b>R11</b> — every published occurrence of a variable NAME across this provider's SDTM
     * product, scanned domain by domain.
     *
     * <p>
     * ⚑ <b>Bounded and data-free.</b> The scan walks only this product's own published domains (63
     * for SDTMIG 3.4) and reads no study data, which is the whole point: carry-over checking works
     * against the Library alone, where before it needed the SDTM datasets to be supplied.
     * </p>
     *
     * <p>
     * ⛔ Returns label and {@code simpleDatatype} only — the Library publishes no format and no
     * length (see {@link PublishedVariable}). Empty when the name is published nowhere, which is
     * the <b>not-applicable</b> case: measured, only 10 of 332 {@code adamig-1-3} variables have
     * any SDTM counterpart at all.
     * </p>
     */
    @Override
    public List<PublishedVariable> getPublishedVariablesByName(String variableName)
    {
        if (libraryFailed || variableName == null || variableName.isEmpty())
        {
            return List.of();
        }
        List<String> domains = getStandardDatasetNames();
        if (domains == null || domains.isEmpty())
        {
            return List.of();
        }
        List<PublishedVariable> out = new ArrayList<>();
        for (String domain : domains)
        {
            Map<String, String> meta = getVariableMetadata(domain, variableName);
            if (meta != null && !meta.isEmpty())
            {
                out.add(new PublishedVariable(domain, meta.get("label"),
                        meta.get("simpleDatatype")));
            }
        }
        return List.copyOf(out);
    }


    /**
     * EC-14 layer (i) — the canonical union of standard dataset (domain) NAMES: the IG product's
     * datasets ({@code sdtmProduct.classes() → SdtmClass.datasets() → name()}) unioned with the
     * SDTM Model product's top-level datasets ({@code sdtmModelProduct.datasets() → name()}),
     * preserving order via a {@link LinkedHashSet}. Mirrors the Python reference engine's
     * {@code standard_domains} ({@code standard ∪ model dataset_names}).
     *
     * @return the order-preserving union, or {@code null} when the library failed to load or no
     *         SDTM IG product is configured (⇒ the {@code standard_domains} operation SKIPs the
     *         rule; e.g. an ADaM run, which carries no SDTM product, until P5b)
     */
    @Override
    public @Nullable List<String> getStandardDatasetNames()
    {
        if (libraryFailed || sdtmProduct == null)
        {
            return null;
        }
        Set<String> names = new LinkedHashSet<>();
        for (SdtmClass klass : sdtmProduct.classes())
        {
            for (SdtmDataset ds : klass.datasets())
            {
                ds.name().ifPresent(names::add);
            }
        }
        if (sdtmModelProduct != null)
        {
            for (SdtmDataset ds : sdtmModelProduct.datasets())
            {
                ds.name().ifPresent(names::add);
            }
        }
        return List.copyOf(names);
    }


    /**
     * Fix #42 Phase 2 — class-aware allowed-variables resolver, ported from Python's
     * {@code cdisc_rules_engine.utilities.sdtm_utilities.get_variables_metadata_from_standard_model}.
     *
     * <p>
     * Five-step pipeline:
     * </p>
     * <ol>
     * <li><b>Compute the effective domain.</b> SUPP-prefixed and SQ-prefixed domains pivot to
     * {@code SUPPQUAL}; AP-prefixed domains strip the {@code AP} prefix and trigger the
     * {@code add_AP} merge of {@code ASSOCIATED PERSONS} identifiers. The {@code originalDomain}
     * (used for wildcard substitution) tracks the pre-strip name.</li>
     * <li><b>Look up the class.</b> Non-custom domains read the class from the per-table
     * {@link IMetadataLibrary} view (Define-XML / standard) or via product reverse-walk; custom
     * domains drop to Fix #41's {@link CustomDomainClassDetector}.</li>
     * <li><b>Walk the product class for variables.</b> Each class's
     * {@link SdtmClass#classVariables()} contributes the model-side variable list. For detectable
     * classes ({@code FINDINGS}, {@code FINDINGS ABOUT}, {@code EVENTS}, {@code INTERVENTIONS}),
     * the GENERAL OBSERVATIONS class contributes shared identifier and timing variables (when the
     * loaded product carries that class).</li>
     * <li><b>SUPP / AP shimming.</b> SUPP-prefixed and SQ-prefixed domains pull the
     * {@code SUPPQUAL} class. AP-prefixed domains additionally merge {@code ASSOCIATED PERSONS}
     * identifiers (excluding {@code USUBJID}).</li>
     * <li><b>Wildcard substitution.</b> {@code --} in variable names is replaced with the original
     * (pre-strip) domain prefix.</li>
     * </ol>
     *
     * <p>
     * The Java {@link SdtmProduct} is the IG product; some IG responses embed only the
     * class-specific variables and rely on the model link for shared identifiers/timing. When the
     * loaded product happens to embed identifiers/timing inside each class (current
     * {@code CdiscLibraryMetadataLibrary.fromSdtm} consumers, test fixtures), the deduplicating
     * pass keeps the result correct without double-counting.
     * </p>
     *
     * @return ordered, deduplicated allowed-variable names; empty list when the class is unknown;
     *         {@code null} when the library failed to load or no product is configured.
     */
    @Override
    public @Nullable List<String> getStandardModelVariables(IDataTable aTable,
            DatasetResolver aResolver)
    {
        if (libraryFailed)
        {
            return null;
        }
        if (sdtmProduct == null && adamProducts.isEmpty())
        {
            // No product access — caller falls back / treats as library-not-available.
            return null;
        }
        if (aTable == null)
        {
            return null;
        }
        // Fix #59: resolve via the CDISC domain code (DOMAIN-column-first), not the file/
        // member name. Without this an LBHE-shaped split lookup would walk the product for an
        // "LBHE" class that doesn't exist in the SDTM Model and return empty — the
        // CORE-000550-on-LBHE bug.
        String domain = CdiscDomainResolver.cdiscDomainOf(aTable);
        if (domain == null || domain.isEmpty())
        {
            return List.of();
        }
        if (sdtmProduct != null)
        {
            return resolveSdtmStandardModelVariables(domain);
        }
        // ADaM path
        return resolveAdamStandardModelVariables(domain);
    }


    @Override
    public @Nullable List<Map<String, String>> getStandardModelVariablesDetailed(IDataTable aTable,
            DatasetResolver aResolver)
    {
        // Same dispatch + degraded-mode handling as getStandardModelVariables; differs only in
        // the projection at the end. Returning null keeps the interface contract aligned with
        // the names-only signal so callers can use the same library-not-available test.
        if (libraryFailed)
        {
            return null;
        }
        if (sdtmProduct == null && adamProducts.isEmpty())
        {
            return null;
        }
        if (aTable == null)
        {
            return null;
        }
        // Fix #59: same CDISC-domain resolution as the names-only entry point.
        String domain = CdiscDomainResolver.cdiscDomainOf(aTable);
        if (domain == null || domain.isEmpty())
        {
            return List.of();
        }
        if (sdtmProduct != null)
        {
            return attributeMapsOf(buildResolvedSdtmModel(domain));
        }
        return attributeMapsOf(buildResolvedAdam(domain));
    }


    /**
     * EC-85 — the algorithm-A walk for an explicitly named observation class. The dataset's own
     * domain drives only the SUPP/AP shimming and the {@code --} substitution. {@code null} is
     * library-not-available: a failed or product-less library, the ADaM-only path (D-4 — ADaM has
     * no observation class to select), or a class the loaded model does not carry / carries empty —
     * so the caller SKIPs the rule rather than testing membership against nothing.
     */
    @Override
    public @Nullable List<Map<String, String>> getStandardModelVariablesForClass(IDataTable aTable,
            DatasetResolver aResolver, String aModelClass)
    {
        if (libraryFailed || sdtmProduct == null || aTable == null || aModelClass == null)
        {
            return null;
        }
        String domain = CdiscDomainResolver.cdiscDomainOf(aTable);
        if (domain == null || domain.isEmpty())
        {
            return List.of();
        }
        // Normalised exactly once, inside the walk (step 2) — normalise() is not idempotent on the
        // special-purpose aliases, so a second application here would silently re-map the class.
        List<ResolvedVariable> resolved = buildResolvedSdtmModel(domain, aModelClass);
        return resolved.isEmpty() ? null : attributeMapsOf(resolved);
    }


    @Override
    public @Nullable List<Map<String, String>> getStandardVariablesDetailed(IDataTable aTable,
            DatasetResolver aResolver)
    {
        // Algorithm B (IG-base + Model-merge) detailed accessor. Same guards / domain-resolution
        // as getStandardModelVariablesDetailed; differs only in the resolver it projects from
        // (buildResolvedSdtm vs buildResolvedSdtmModel).
        if (libraryFailed)
        {
            return null;
        }
        if (sdtmProduct == null && adamProducts.isEmpty())
        {
            return null;
        }
        if (aTable == null)
        {
            return null;
        }
        String domain = CdiscDomainResolver.cdiscDomainOf(aTable);
        if (domain == null || domain.isEmpty())
        {
            return List.of();
        }
        if (sdtmProduct != null)
        {
            return attributeMapsOf(buildResolvedSdtm(domain));
        }
        return attributeMapsOf(buildResolvedAdam(domain));
    }


    /**
     * Names-only projection of the algorithm-A {@link #buildResolvedSdtmModel} (pure Model walk).
     * Public callers consuming {@code List<String>} (e.g. {@link #getStandardModelVariables}) go
     * through this method.
     */
    private List<String> resolveSdtmStandardModelVariables(String aOriginalDomain)
    {
        return namesOf(buildResolvedSdtmModel(aOriginalDomain));
    }


    /**
     * <b>Fix #373</b> — step 1 of algorithm B as a value: the <em>canonical</em> CDISC name behind
     * a derived dataset name. {@code SUPP--} / {@code SQ--} → {@code SUPPQUAL}, {@code AP--} → the
     * stripped parent domain, anything else unchanged.
     *
     * <p>
     * Extracted from {@link #buildResolvedSdtm} so {@link #getVariableMetadata} and
     * {@link #getCodelistCodeMap} can ask leg 1 about the same canonical name leg 2 resolved,
     * without duplicating the rule. ⚠⚠ There are <b>THREE</b> copies of this canonicalisation, not
     * two — this one, {@code buildResolvedSdtm}'s step 1, and {@code buildResolvedSdtmModel}'s.
     * Keep all three in lockstep. {@code buildResolvedSdtm} additionally computes
     * {@code wildcardDomain} and {@code addAP}, which this deliberately does not return because no
     * caller outside the resolver needs them.
     * </p>
     */
    private static String canonicalSdtmDomain(String aOriginalDomain)
    {
        String upper = aOriginalDomain.toUpperCase(Locale.ROOT);
        if (upper.length() > 2 && (upper.startsWith("SUPP") || upper.startsWith("SQ")))
        {
            return DOMAIN_SUPPQUAL;
        }
        if (upper.startsWith("AP") && upper.length() > 2)
        {
            return aOriginalDomain.substring(2);
        }
        return aOriginalDomain;
    }


    /**
     * Resolves the SDTM/SDTMIG model-side allowed variables for the given dataset, mirroring
     * Python's {@code get_variables_metadata_from_standard_model}. Returns the rich
     * {@link ResolvedVariable} list (substituted name + full attribute map) so callers can project
     * to either name-only or Python-{@code variables_metadata}-shaped output.
     */
    private List<ResolvedVariable> buildResolvedSdtm(String aOriginalDomain)
    {
        // Step 1 — Effective domain: SUPP/SQ → SUPPQUAL, AP* → strip prefix.
        // ⚠ The name half of this is also `canonicalSdtmDomain` (Fix #373); the two must agree.
        String upper = aOriginalDomain.toUpperCase(Locale.ROOT);
        boolean addAP = false;
        String effectiveDomain = aOriginalDomain;
        String wildcardDomain = aOriginalDomain;
        if (upper.length() > 2 && (upper.startsWith("SUPP") || upper.startsWith("SQ")))
        {
            if (upper.startsWith("SQ"))
            {
                String parent = effectiveDomain.substring(2);
                if (parent.toUpperCase(Locale.ROOT).startsWith("AP"))
                {
                    addAP = true;
                }
            }
            effectiveDomain = DOMAIN_SUPPQUAL;
        }
        else if (upper.startsWith("AP") && upper.length() > 2)
        {
            effectiveDomain = aOriginalDomain.substring(2);
            wildcardDomain = effectiveDomain;
            addAP = true;
        }

        // Step 2 — Class resolution.
        // Special case: SUPP-prefixed and SQ-prefixed domains route to the SUPPQUAL variable set
        // via Fix #61's three-tier cascade — IG SUPPQUAL dataset → SDTM Model RELATIONSHIP class
        // → canonical hard-coded list. The cascade short-circuits the rest of the resolver
        // (steps 3–6) because SUPPQUAL is non-detectable, has no `--`-substituting variable
        // names, and produces a closed allowed-variable list directly.
        // Non-custom: product reverse-walk by domain.
        // Custom: Fix #41 sniffer (consults the per-table IMetadataLibrary view, not the runtime
        // DataTableMeta — IDataTableMetadata.getClassName() and the column list are what the
        // sniffer needs).
        String className = null;
        if (DOMAIN_SUPPQUAL.equals(effectiveDomain))
        {
            return resolveSuppQualVariables(wildcardDomain, addAP);
        }
        else
        {
            boolean isCustom = !sdtmProductHasDomain(effectiveDomain);
            if (!isCustom)
            {
                className = sdtmClassForDomain(effectiveDomain);
            }
            else
            {
                IDataTableMetadata meta = library.getDataTable(aOriginalDomain).orElse(null);
                if (meta != null)
                {
                    className = CustomDomainClassDetector.detectClass(meta, aOriginalDomain);
                }
            }
        }
        if (className == null || className.isEmpty())
        {
            return List.<ResolvedVariable> of();
        }

        // Step 3 — Walk the product class for variables (with detectable-class GenObs merging).
        // className is non-null here (guarded above), so ctClassName (poly-null) returns non-null.
        String classNameNorm = Objects.requireNonNull(ctClassName(className));
        SdtmClass klass = findBaseClassByName(classNameNorm);
        if (klass == null)
        {
            return List.<ResolvedVariable> of();
        }
        boolean detectable = isDetectableClass(classNameNorm);
        List<SdtmVariable> identifiers = new ArrayList<>();
        List<SdtmVariable> classVars = sortSdtmByOrdinal(klass.classVariables());
        List<SdtmVariable> timing = new ArrayList<>();

        if (detectable)
        {
            // For detectable classes, identifiers + timing come from GENERAL OBSERVATIONS when
            // present in the loaded product. The IG class's own classVariables list typically
            // already includes the General-Observations vars for self-contained IG products
            // (the dedupe pass below keeps the result correct).
            SdtmClass genObs = findBaseClassByName("GENERAL OBSERVATIONS");
            if (genObs != null)
            {
                for (SdtmVariable v : genObs.classVariables())
                {
                    String role = v.role().orElse("");
                    if (ROLE_IDENTIFIER.equalsIgnoreCase(role))
                    {
                        identifiers.add(v);
                    }
                    else if ("Timing".equalsIgnoreCase(role))
                    {
                        timing.add(v);
                    }
                }
            }
            // For FINDINGS ABOUT, merge FINDINGS class variables around --TEST. This produces a
            // mixed-ordinal sequence (FA --OBJ between FINDINGS --TEST and --ORRES) — must NOT be
            // re-sorted afterwards.
            if (CLASS_FINDINGS_ABOUT.equals(classNameNorm))
            {
                SdtmClass findings = findBaseClassByName("FINDINGS");
                if (findings != null)
                {
                    classVars = mergeFindingsAboutClassVariables(findings.classVariables(),
                            classVars);
                }
            }
        }

        // Step 4 — AP shimming: merge ASSOCIATED PERSONS identifiers, excluding USUBJID.
        if (addAP)
        {
            SdtmClass apClass = findBaseClassByName(CLASS_ASSOCIATED_PERSONS);
            if (apClass != null)
            {
                for (SdtmVariable v : apClass.classVariables())
                {
                    if (!VAR_USUBJID.equalsIgnoreCase(v.name().orElse("")))
                    {
                        identifiers.add(v);
                    }
                }
            }
        }

        // Sort identifiers and timing by ordinal. classVars is intentionally not re-sorted —
        // either it was already sorted at construction (non-detectable / non-FA paths) or the
        // FA merge has produced an order that should be preserved.
        identifiers = sortSdtmByOrdinal(identifiers);
        timing = sortSdtmByOrdinal(timing);

        // Step 5 — Substitute `--` in model-derived names so the merge can match by substituted
        // name. Mirrors Python's `replace_variable_wildcards(var_list, original_domain, ...)`
        // call before the IG-override merge.
        List<ResolvedVariable> idList = substituteAndResolve(identifiers, wildcardDomain);
        List<ResolvedVariable> classList = substituteAndResolve(classVars, wildcardDomain);
        List<ResolvedVariable> timingList = substituteAndResolve(timing, wildcardDomain);

        // Step 6 — IG-override merge (Fix #42 Phase 2 step 3). Custom domains skip this step
        // (Python: `if is_custom: variables_metadata = model_variables`); their model-derived
        // list is the final answer.
        boolean isCustom = !DOMAIN_SUPPQUAL.equals(effectiveDomain)
                && !sdtmProductHasDomain(effectiveDomain);
        if (!isCustom)
        {
            SdtmDataset igDataset = findSdtmDatasetByDomain(effectiveDomain);
            if (igDataset != null)
            {
                List<ResolvedVariable> igList = substituteAndResolve(
                        sortSdtmByOrdinal(igDataset.datasetVariables()), wildcardDomain);
                if (detectable)
                {
                    // DETECTABLE_CLASSES: merge IG into model-derived list. Override-by-name;
                    // insert-by-role for IG-only variables. Mirrors Python
                    // sdtm_utilities.py:178-212.
                    return mergeIgOverride(idList, classList, timingList, igList);
                }
                else
                {
                    // Non-detectable, non-custom: just IG variables (with optional AP merge for
                    // SQ-AP shapes). Mirrors Python sdtm_utilities.py:213-230.
                    if (addAP)
                    {
                        SdtmClass apClass = findBaseClassByName(CLASS_ASSOCIATED_PERSONS);
                        if (apClass != null)
                        {
                            List<SdtmVariable> apIds = new ArrayList<>();
                            for (SdtmVariable v : apClass.classVariables())
                            {
                                if (!VAR_USUBJID.equalsIgnoreCase(v.name().orElse("")))
                                {
                                    apIds.add(v);
                                }
                            }
                            List<SdtmVariable> combined = new ArrayList<>(
                                    igDataset.datasetVariables());
                            combined.addAll(apIds);
                            igList = substituteAndResolve(sortSdtmByOrdinal(combined),
                                    wildcardDomain);
                        }
                    }
                    return dedupeByNameKeepFirst(igList);
                }
            }
            // No IG dataset found — fall through to model-derived output. (SUPPQUAL routes here
            // since SUPPQUAL is a class without a single owning dataset.)
        }

        // Custom domain (or non-custom with no IG dataset entry): emit the model-derived list.
        return flattenUniqueByName(idList, classList, timingList);
    }


    /**
     * Pure-Model resolver mirroring Python's {@code get_variables_metadata_from_standard_model}
     * (algorithm A). Differs from {@link #buildResolvedSdtm} (algorithm B) in two ways:
     * <ul>
     * <li>The model-side base walk is <em>not</em> overwritten by the IG dataset variables
     * ({@code mergeIgOverride} is never called).</li>
     * <li>Non-detectable, non-custom domains resolve via a three-tier Model-first fallback — Model
     * class {@code classVariables} → Model domain {@code datasetVariables} → IG domain
     * {@code datasetVariables} — instead of returning the pure IG dataset variables.</li>
     * </ul>
     *
     * <p>
     * The class <em>name</em> is still resolved from the IG ({@link #sdtmClassForDomain}); only the
     * variables are sourced from the Model. Custom domains use the custom-class detector and emit
     * the Model walk. SUPP/SQ domains short-circuit through the shared
     * {@link #resolveSuppQualVariables} cascade, identical to algorithm B.
     * </p>
     *
     * <p>
     * Feeds {@link #getStandardModelVariables} (names) and
     * {@link #getStandardModelVariablesDetailed} (attribute maps).
     * </p>
     */
    private List<ResolvedVariable> buildResolvedSdtmModel(String aOriginalDomain)
    {
        return buildResolvedSdtmModel(aOriginalDomain, null);
    }


    /**
     * EC-85 — the algorithm-A walk with an optional <b>forced</b> observation class.
     *
     * @param aForcedClass
     *            when non-null, replaces the domain-derived class (step 2) with this normalised
     *            class name. Step 1 (SUPP/SQ → SUPPQUAL, AP* → strip prefix, {@code addAP}) and the
     *            {@code --} substitution stay driven by the dataset's <em>own</em> domain — that is
     *            what makes an EVENTS walk asked from {@code BW} answer {@code BWTERM}. A SUPPQUAL
     *            table short-circuits before the class is consulted (D-3). A forced class never
     *            falls to the domain-keyed non-detectable tiers 2/3 (D-2): those are keyed on the
     *            <em>domain</em> and would hand back this dataset's own variables.
     */
    private List<ResolvedVariable> buildResolvedSdtmModel(String aOriginalDomain,
            @Nullable String aForcedClass)
    {
        // Step 1 — Effective domain: SUPP/SQ → SUPPQUAL, AP* → strip prefix. Identical to
        // buildResolvedSdtm.
        String upper = aOriginalDomain.toUpperCase(Locale.ROOT);
        boolean addAP = false;
        String effectiveDomain = aOriginalDomain;
        String wildcardDomain = aOriginalDomain;
        if (upper.length() > 2 && (upper.startsWith("SUPP") || upper.startsWith("SQ")))
        {
            if (upper.startsWith("SQ"))
            {
                String parent = effectiveDomain.substring(2);
                if (parent.toUpperCase(Locale.ROOT).startsWith("AP"))
                {
                    addAP = true;
                }
            }
            effectiveDomain = DOMAIN_SUPPQUAL;
        }
        else if (upper.startsWith("AP") && upper.length() > 2)
        {
            effectiveDomain = aOriginalDomain.substring(2);
            wildcardDomain = effectiveDomain;
            addAP = true;
        }

        // Step 2 — Class resolution (SUPPQUAL short-circuit, IG class name, custom detector).
        // Identical to buildResolvedSdtm.
        String className;
        boolean isCustom;
        if (DOMAIN_SUPPQUAL.equals(effectiveDomain))
        {
            // D-3: a SUPP-- table has no general observation class; the forced class is ignored.
            return resolveSuppQualVariables(wildcardDomain, addAP);
        }
        if (aForcedClass != null)
        {
            // D-1: the class is the caller's, everything derived from the domain stays.
            className = aForcedClass;
        }
        else
        {
            isCustom = !sdtmProductHasDomain(effectiveDomain);
            if (!isCustom)
            {
                className = sdtmClassForDomain(effectiveDomain);
            }
            else
            {
                IDataTableMetadata meta = library.getDataTable(aOriginalDomain).orElse(null);
                className = meta == null ? null
                        : CustomDomainClassDetector.detectClass(meta, aOriginalDomain);
            }
        }
        if (className == null || className.isEmpty())
        {
            return List.<ResolvedVariable> of();
        }
        String classNameNorm = Objects.requireNonNull(ctClassName(className));

        if (isDetectableClass(classNameNorm))
        {
            // Detectable (or custom resolving to a detectable class): pure Model walk.
            return modelDetectableWalk(classNameNorm, wildcardDomain, addAP);
        }
        if (aForcedClass != null)
        {
            // D-2: tiers 2 and 3 below are DOMAIN-keyed and would return this dataset's own
            // variables under another class's name. A forced non-detectable class answers with
            // its own classVariables or nothing.
            SdtmClass forced = findBaseClassByName(classNameNorm);
            List<SdtmVariable> forcedVars = forced == null ? List.<SdtmVariable> of()
                    : forced.classVariables();
            return forcedVars.isEmpty() ? List.<ResolvedVariable> of()
                    : assembleNonDetectable(forcedVars, wildcardDomain, addAP);
        }

        // Non-detectable. Custom domains resolving to a non-detectable class emit the Model
        // class's classVariables directly (Python: custom → model walk). For both custom and
        // standard non-detectable domains the three-tier fallback below applies; the only
        // difference is that a custom domain has no IG dataset (tier 3 yields nothing), which is
        // the correct Python behaviour (custom → model only).
        SdtmClass klass = findBaseClassByName(classNameNorm);
        List<SdtmVariable> classVars = klass == null ? List.<SdtmVariable> of()
                : klass.classVariables();
        if (!classVars.isEmpty())
        {
            return assembleNonDetectable(classVars, wildcardDomain, addAP);
        }
        // Tier 2 — Model domain datasetVariables.
        SdtmDataset modelDataset = findModelDatasetByDomain(effectiveDomain);
        if (modelDataset != null && !modelDataset.datasetVariables().isEmpty())
        {
            return assembleNonDetectable(modelDataset.datasetVariables(), wildcardDomain, addAP);
        }
        // Tier 3 — IG domain datasetVariables.
        SdtmDataset igDataset = findSdtmDatasetByDomain(effectiveDomain);
        if (igDataset != null && !igDataset.datasetVariables().isEmpty())
        {
            return assembleNonDetectable(igDataset.datasetVariables(), wildcardDomain, addAP);
        }
        return List.<ResolvedVariable> of();
    }


    /**
     * Pure-Model detectable-class walk (algorithm A): build the identifier / class / timing buckets
     * from the Model base ({@link #findBaseClassByName}), merge GENERAL OBSERVATIONS identifiers
     * and timing, splice FINDINGS around {@code --TEST} for FINDINGS ABOUT, optionally merge
     * ASSOCIATED PERSONS identifiers, substitute {@code --}, and flatten with dedupe. Never
     * consults the IG dataset variables.
     */
    private List<ResolvedVariable> modelDetectableWalk(String aClassNameNorm,
            String aWildcardDomain, boolean aAddAP)
    {
        SdtmClass klass = findBaseClassByName(aClassNameNorm);
        if (klass == null)
        {
            return List.<ResolvedVariable> of();
        }
        List<SdtmVariable> identifiers = new ArrayList<>();
        List<SdtmVariable> classVars = sortSdtmByOrdinal(klass.classVariables());
        List<SdtmVariable> timing = new ArrayList<>();

        SdtmClass genObs = findBaseClassByName("GENERAL OBSERVATIONS");
        if (genObs != null)
        {
            for (SdtmVariable v : genObs.classVariables())
            {
                String role = v.role().orElse("");
                if (ROLE_IDENTIFIER.equalsIgnoreCase(role))
                {
                    identifiers.add(v);
                }
                else if ("Timing".equalsIgnoreCase(role))
                {
                    timing.add(v);
                }
            }
        }
        if (CLASS_FINDINGS_ABOUT.equals(aClassNameNorm))
        {
            SdtmClass findings = findBaseClassByName("FINDINGS");
            if (findings != null)
            {
                classVars = mergeFindingsAboutClassVariables(findings.classVariables(), classVars);
            }
        }
        if (aAddAP)
        {
            SdtmClass apClass = findBaseClassByName(CLASS_ASSOCIATED_PERSONS);
            if (apClass != null)
            {
                for (SdtmVariable v : apClass.classVariables())
                {
                    if (!VAR_USUBJID.equalsIgnoreCase(v.name().orElse("")))
                    {
                        identifiers.add(v);
                    }
                }
            }
        }
        identifiers = sortSdtmByOrdinal(identifiers);
        timing = sortSdtmByOrdinal(timing);

        List<ResolvedVariable> idList = substituteAndResolve(identifiers, aWildcardDomain);
        List<ResolvedVariable> classList = substituteAndResolve(classVars, aWildcardDomain);
        List<ResolvedVariable> timingList = substituteAndResolve(timing, aWildcardDomain);
        return flattenUniqueByName(idList, classList, timingList);
    }


    /**
     * Non-detectable assembly tail shared by the three Model-first fallback tiers of
     * {@link #buildResolvedSdtmModel}: sort by ordinal, optionally merge ASSOCIATED PERSONS
     * identifiers (minus USUBJID), substitute {@code --}, and dedupe. Mirrors Python's
     * non-detectable branches in {@code get_variables_metadata_from_standard_model}.
     */
    private List<ResolvedVariable> assembleNonDetectable(List<SdtmVariable> aVars,
            String aWildcardDomain, boolean aAddAP)
    {
        List<SdtmVariable> combined = sortSdtmByOrdinal(aVars);
        if (aAddAP)
        {
            SdtmClass apClass = findBaseClassByName(CLASS_ASSOCIATED_PERSONS);
            if (apClass != null)
            {
                List<SdtmVariable> merged = new ArrayList<>(combined);
                for (SdtmVariable v : apClass.classVariables())
                {
                    if (!VAR_USUBJID.equalsIgnoreCase(v.name().orElse("")))
                    {
                        merged.add(v);
                    }
                }
                combined = merged;
            }
        }
        return dedupeByNameKeepFirst(substituteAndResolve(combined, aWildcardDomain));
    }

    /**
     * Internal record carried across the Phase 2 resolver pipeline. Holds the
     * {@code --}-substituted variable name and the variable's full attribute map (role, core,
     * simpleDatatype, label, ordinal — whichever the source product populated). Both views are
     * needed: name-only consumers project {@code name()}; the
     * {@code getStandardModelVariablesDetailed} accessor (and the filtered-variable Operations)
     * project {@link #toAttributeMap()}.
     */
    private record ResolvedVariable(String name, Map<String, String> attributes)
    {

        String role()
        {
            return attributes.getOrDefault("role", "");
        }


        /**
         * Returns a Python-{@code variables_metadata}-shaped map: the resolver's {@code --}-
         * substituted name plus all source attributes (role, core, simpleDatatype, label, ordinal).
         * The output is unmodifiable; callers must build a copy if they need to mutate.
         */
        Map<String, String> toAttributeMap()
        {
            Map<String, String> out = new LinkedHashMap<>(attributes);
            out.put("name", name);
            return Collections.unmodifiableMap(out);
        }
    }

    /**
     * Substitutes {@code --} → {@code aDomain} on each variable's name and packs the variable's
     * attributes (role, core, simpleDatatype, label, ordinal) into a map for downstream filter
     * operations. Skips entries with null/empty names. The output preserves input order.
     */
    private static List<ResolvedVariable> substituteAndResolve(List<SdtmVariable> aVars,
            String aDomain)
    {
        List<ResolvedVariable> out = new ArrayList<>(aVars.size());
        for (SdtmVariable v : aVars)
        {
            String name = v.name().orElse(null);
            if (name == null || name.isEmpty())
            {
                continue;
            }
            String resolved = name.contains("--") ? name.replace("--", aDomain) : name;
            Map<String, String> attrs = new LinkedHashMap<>();
            v.role().ifPresent(r -> attrs.put("role", r));
            v.core().ifPresent(c -> attrs.put("core", c));
            v.simpleDatatype().ifPresent(t -> attrs.put(ATTR_SIMPLE_DATATYPE, t));
            v.label().ifPresent(l -> attrs.put(ATTR_LABEL, l));
            v.ordinal().ifPresent(o -> attrs.put(ATTR_ORDINAL, o));
            out.add(new ResolvedVariable(resolved, attrs));
        }
        return out;
    }

    /**
     * Fix #61: canonical SUPPQUAL variable list used as the last-resort fallback (tier C) when
     * neither the IG's {@code SUPPQUAL} dataset nor the SDTM Model's {@code RELATIONSHIP} class
     * supplies usable variables. Mirrors the SDTM Model 2.0 Relationship Datasets class. The list
     * is intentionally permissive (includes {@code POOLID} and {@code SPDEVID} from SDTM 2.0) so
     * {@code is_not_contained_by} checks don't flag legitimate Model 2.0 columns as unexpected when
     * running against a runtime where the IG/Model fetch failed.
     */
    private static final List<ResolvedVariable> RELATIONSHIP_FALLBACK_VARIABLES = List.of(
            relationshipFallbackVar("STUDYID", ROLE_IDENTIFIER, "Req", 1),
            relationshipFallbackVar("RDOMAIN", ROLE_IDENTIFIER, "Req", 2),
            relationshipFallbackVar(VAR_USUBJID, ROLE_IDENTIFIER, "Exp", 3),
            relationshipFallbackVar("POOLID", ROLE_IDENTIFIER, "Perm", 4),
            relationshipFallbackVar("SPDEVID", ROLE_IDENTIFIER, "Perm", 5),
            relationshipFallbackVar("IDVAR", ROLE_RECORD_QUALIFIER, "Exp", 6),
            relationshipFallbackVar("IDVARVAL", ROLE_RECORD_QUALIFIER, "Exp", 7),
            relationshipFallbackVar("QNAM", "Topic", "Req", 8),
            relationshipFallbackVar("QLABEL", "Synonym Qualifier", "Req", 9),
            relationshipFallbackVar("QVAL", "Result Qualifier", "Req", 10),
            relationshipFallbackVar("QORIG", ROLE_RECORD_QUALIFIER, "Req", 11),
            relationshipFallbackVar("QEVAL", ROLE_RECORD_QUALIFIER, "Perm", 12));

    private static ResolvedVariable relationshipFallbackVar(String aName, String aRole,
            String aCore, int aOrdinal)
    {
        Map<String, String> attrs = new LinkedHashMap<>();
        attrs.put("role", aRole);
        attrs.put("core", aCore);
        attrs.put(ATTR_ORDINAL, Integer.toString(aOrdinal));
        return new ResolvedVariable(aName, Collections.unmodifiableMap(attrs));
    }


    /**
     * Fix #61: A→B→C cascade for SUPP/SQ-prefixed datasets that have already been pivoted to the
     * effective {@code SUPPQUAL} domain. The canonical SUPPQUAL variables don't contain {@code --}
     * so {@code aWildcardDomain} is unused for substitution, but we keep the parameter for the AP
     * merge path that may add {@code --}-bearing identifiers from the {@code ASSOCIATED PERSONS}
     * class.
     *
     * <ul>
     * <li><b>Tier A:</b> IG product's {@code SUPPQUAL} dataset {@code datasetVariables}. The happy
     * path — modern SDTMIG payloads embed the full SUPPQUAL variable set inline.</li>
     * <li><b>Tier B:</b> SDTM Model product's {@code RELATIONSHIP} class {@code classVariables}.
     * Fires when the IG response leaves SUPPQUAL with empty {@code datasetVariables} (some pre-3-4
     * IG versions did this, deferring to the Model).</li>
     * <li><b>Tier C:</b> {@link #RELATIONSHIP_FALLBACK_VARIABLES} — invariant SUPPQUAL list.</li>
     * </ul>
     *
     * <p>
     * For SQ-AP shapes ({@code addAP == true}, e.g. {@code SQAPAE}), the resolver also merges
     * {@code ASSOCIATED PERSONS} class identifiers (minus {@code USUBJID}) when the IG product is
     * available, mirroring Python's non-detectable AP-merge branch
     * (<code>sdtm_utilities.py:418-427</code>).
     * </p>
     */
    private List<ResolvedVariable> resolveSuppQualVariables(String aWildcardDomain, boolean aAddAP)
    {
        // Tier A — IG product's SUPPQUAL dataset.
        SdtmDataset igSuppQual = findSdtmDatasetByDomain(DOMAIN_SUPPQUAL);
        List<ResolvedVariable> base = null;
        if (igSuppQual != null && !igSuppQual.datasetVariables().isEmpty())
        {
            base = substituteAndResolve(sortSdtmByOrdinal(igSuppQual.datasetVariables()),
                    aWildcardDomain);
        }
        // Tier B — SDTM Model RELATIONSHIP class. Tried for both null base and empty base.
        if (base == null || base.isEmpty())
        {
            base = resolveRelationshipModelClassVars(aWildcardDomain);
        }
        // Tier C — invariant canonical list.
        if (base == null || base.isEmpty())
        {
            base = RELATIONSHIP_FALLBACK_VARIABLES;
        }
        // SQ-AP merge: append ASSOCIATED PERSONS identifiers (minus USUBJID). Mirrors Python's
        // non-detectable add_AP branch. Only fires when the IG product is loaded; with no IG the
        // ASSOCIATED PERSONS class is unreachable and the addAP fallback isn't worth hard-coding.
        if (aAddAP)
        {
            base = mergeAssociatedPersonsIdentifiers(base, aWildcardDomain);
        }
        return base;
    }


    /**
     * Tier B of {@link #resolveSuppQualVariables}: probe the SDTM Model for the
     * {@code RELATIONSHIP} class under any of its known names and return its resolved class
     * variables, or {@code null} when nothing is available.
     */
    private @Nullable List<ResolvedVariable> resolveRelationshipModelClassVars(
            String aWildcardDomain)
    {
        SdtmClass modelRel = findSdtmModelClassByName("RELATIONSHIP");
        if (modelRel == null)
        {
            modelRel = findSdtmModelClassByName("RELATIONSHIPS");
        }
        if (modelRel == null)
        {
            modelRel = findSdtmModelClassByName("RELATIONSHIP DATASETS");
        }
        if (modelRel != null && !modelRel.classVariables().isEmpty())
        {
            return substituteAndResolve(sortSdtmByOrdinal(modelRel.classVariables()),
                    aWildcardDomain);
        }
        return null;
    }


    /**
     * SQ-AP merge tail of {@link #resolveSuppQualVariables}: append ASSOCIATED PERSONS identifiers
     * (minus USUBJID) to {@code aBase} when the IG product is loaded. Returns {@code aBase}
     * unchanged when AP class is unreachable or contributes no identifiers.
     */
    private List<ResolvedVariable> mergeAssociatedPersonsIdentifiers(List<ResolvedVariable> aBase,
            String aWildcardDomain)
    {
        SdtmClass apClass = findSdtmClassByName(CLASS_ASSOCIATED_PERSONS);
        if (apClass == null)
        {
            return aBase;
        }
        List<SdtmVariable> apIds = new ArrayList<>();
        for (SdtmVariable v : apClass.classVariables())
        {
            if (!VAR_USUBJID.equalsIgnoreCase(v.name().orElse("")))
            {
                apIds.add(v);
            }
        }
        if (apIds.isEmpty())
        {
            return aBase;
        }
        List<ResolvedVariable> merged = new ArrayList<>(aBase);
        merged.addAll(substituteAndResolve(sortSdtmByOrdinal(apIds), aWildcardDomain));
        return dedupeByNameKeepFirst(merged);
    }


    /**
     * Look up the base class used to source the model-side variable walk (identifiers, class
     * variables, timing). Prefers the SDTM <em>Model</em> product ({@link #sdtmModelProduct}) when
     * one is configured and carries the class, falling back to the IG product
     * ({@link #findSdtmClassByName}) when the Model is absent or doesn't define the class.
     *
     * <p>
     * This is the fix for the parity bug where the base walk read class variables from the IG
     * product. IG responses frequently leave {@code SdtmClass.classVariables()} empty and rely on
     * the linked Model for the shared identifier/class/timing variable definitions — so the walk
     * must source from the Model (mirroring Python's {@code model_metadata} usage in both
     * {@code get_variables_metadata_from_standard} and
     * {@code get_variables_metadata_from_standard_model}). When no Model is configured (e.g. legacy
     * single-product constructors) this falls back to the IG so today's behaviour is preserved.
     * </p>
     */
    private @Nullable SdtmClass findBaseClassByName(@Nullable String aNormalisedName)
    {
        SdtmClass model = findSdtmModelClassByName(aNormalisedName);
        if (model != null)
        {
            return model;
        }
        return findSdtmClassByName(aNormalisedName);
    }


    /** Look up a class in the SDTM <em>Model</em> product (separate from the IG). */
    private @Nullable SdtmClass findSdtmModelClassByName(@Nullable String aNormalisedName)
    {
        if (sdtmModelProduct == null || aNormalisedName == null)
        {
            return null;
        }
        for (SdtmClass klass : sdtmModelProduct.classes())
        {
            if (aNormalisedName.equals(ctClassName(klass.name().orElse(null))))
            {
                return klass;
            }
        }
        return null;
    }


    /**
     * Merges IG-level dataset variables into the model-derived (identifiers + classVars + timing)
     * list. Mirrors Python's IG-override branch in {@code sdtm_utilities.py:178-212}: each IG
     * variable either replaces a model entry with the same substituted name (preserving position),
     * or — for IG-only variables — is inserted by role (Identifier → after the identifiers section,
     * Timing → at the end, anything else → before the timing section). The final output is a
     * deduplicated list of resolved variables (with full attribute maps preserved) in merged order.
     */
    private static List<ResolvedVariable> mergeIgOverride(List<ResolvedVariable> aIdentifiers,
            List<ResolvedVariable> aClassVars, List<ResolvedVariable> aTiming,
            List<ResolvedVariable> aIgVars)
    {
        List<ResolvedVariable> merged = new ArrayList<>(
                aIdentifiers.size() + aClassVars.size() + aTiming.size() + aIgVars.size());
        merged.addAll(aIdentifiers);
        merged.addAll(aClassVars);
        merged.addAll(aTiming);

        Map<String, Integer> nameIndex = new java.util.HashMap<>();
        for (int i = 0; i < merged.size(); i++)
        {
            nameIndex.put(merged.get(i).name(), i);
        }
        int identifiersLen = aIdentifiers.size();
        int timingLen = aTiming.size();

        for (ResolvedVariable ig : aIgVars)
        {
            Integer idx = nameIndex.get(ig.name());
            if (idx != null)
            {
                // Override in place: model var with the same name is replaced by the IG var.
                // Order is preserved; nameIndex stays valid because we don't change positions.
                merged.set(idx, ig);
                continue;
            }
            int insertionPoint;
            String role = ig.role();
            if (ROLE_IDENTIFIER.equalsIgnoreCase(role))
            {
                insertionPoint = identifiersLen;
                identifiersLen++;
            }
            else if ("Timing".equalsIgnoreCase(role))
            {
                insertionPoint = merged.size();
                timingLen++;
            }
            else
            {
                insertionPoint = merged.size() - timingLen;
            }
            merged.add(insertionPoint, ig);
            // Rebuild the index — insertion shifted positions for entries after
            // insertionPoint. O(n) per IG-only insert; tolerable given typical IG sizes.
            nameIndex.clear();
            for (int i = 0; i < merged.size(); i++)
            {
                nameIndex.put(merged.get(i).name(), i);
            }
        }
        return dedupeByNameKeepFirst(merged);
    }


    /**
     * Flattens the given lists of resolved variables into a single list deduplicated by name,
     * preserving the first occurrence's full attribute map. Used by both the IG-override path
     * (single merged list) and the no-IG-merge path (three buckets — identifiers, classVars,
     * timing).
     */
    @SafeVarargs
    private static List<ResolvedVariable> flattenUniqueByName(List<ResolvedVariable>... aBuckets)
    {
        Map<String, ResolvedVariable> seen = new LinkedHashMap<>();
        for (List<ResolvedVariable> bucket : aBuckets)
        {
            for (ResolvedVariable v : bucket)
            {
                seen.putIfAbsent(v.name(), v);
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(seen.values()));
    }


    /** Deduplicates a flat list by name, preserving the first occurrence. */
    private static List<ResolvedVariable> dedupeByNameKeepFirst(List<ResolvedVariable> aMerged)
    {
        Map<String, ResolvedVariable> seen = new LinkedHashMap<>();
        for (ResolvedVariable v : aMerged)
        {
            seen.putIfAbsent(v.name(), v);
        }
        return Collections.unmodifiableList(new ArrayList<>(seen.values()));
    }


    /** Projects a list of resolved variables to a list of names. */
    private static List<String> namesOf(List<ResolvedVariable> aResolved)
    {
        List<String> out = new ArrayList<>(aResolved.size());
        for (ResolvedVariable v : aResolved)
        {
            out.add(v.name());
        }
        return Collections.unmodifiableList(out);
    }


    /** Projects a list of resolved variables to a list of attribute maps (Python-shaped). */
    private static List<Map<String, String>> attributeMapsOf(List<ResolvedVariable> aResolved)
    {
        List<Map<String, String>> out = new ArrayList<>(aResolved.size());
        for (ResolvedVariable v : aResolved)
        {
            out.add(v.toAttributeMap());
        }
        return Collections.unmodifiableList(out);
    }


    /**
     * Resolves the ADaM model-side allowed variables for the given dataset. ADaM has no
     * SUPP/AP/wildcard semantics — the resolver walks the data structure for the dataset and
     * returns its analysis variables, sorted by ordinal across variable sets. Falls back to
     * {@link #adamClassForDomain} matching when the dataset isn't owned directly.
     *
     * @return ordered list of variable names; empty list when no data structure owns the dataset.
     */
    private List<String> resolveAdamStandardModelVariables(String aDomain)
    {
        return namesOf(buildResolvedAdam(aDomain));
    }


    /**
     * Rich-form ADaM resolver — same dispatch as {@link #resolveAdamStandardModelVariables} but
     * returns {@link ResolvedVariable} entries with full attribute maps so detailed accessors (e.g.
     * {@link MetadataProvider#getStandardModelVariablesDetailed}) can project to
     * {@code List<Map<String,String>>} without re-walking.
     */
    private List<ResolvedVariable> buildResolvedAdam(String aDomain)
    {
        // First try direct dataset/data-structure name match. ADSL → ADSL data structure;
        // ADAE → BDS-class data structure (ADaM data-structure model).
        List<AdamDataStructure> structures;
        SourcedStructure named = adamDataStructureFor(aDomain);
        if (named != null)
        {
            structures = List.of(named.structure());
        }
        else
        {
            // The dataset isn't a directly-named ADaM data structure (e.g. ADAE for BDS). Fall
            // back to class match: the dataset's own className metadata (read from the
            // IMetadataLibrary view) or the conventional BDS prefix, resolved through the SAME
            // pooled specificity chain the structure-keyed accessors use (Phase 11 finding F1).
            String studyClassName = library.getDataTable(aDomain)
                    .map(IDataTableMetadata::getClassName).orElse(null);
            structures = adamStructuresForClassName(studyClassName, aDomain);
            if (structures.isEmpty() && aDomain.toUpperCase(Locale.ROOT).startsWith("AD")
                    && !"ADSL".equalsIgnoreCase(aDomain))
            {
                // Conventional fallback: AD-prefixed datasets that aren't ADSL belong to BDS.
                structures = adamStructuresForClassName(AdamDataStructureDetector.BDS, aDomain);
            }
        }
        if (structures.isEmpty())
        {
            return List.of();
        }
        Map<String, ResolvedVariable> seen = new LinkedHashMap<>();
        // Structure by structure, governing tier first: within a structure the standard's own
        // ordinal order, across structures first-occurrence-wins, so the governing structure's
        // attributes (notably `core`) are the ones reported. Mirrors adamNamesWhereCore.
        for (AdamDataStructure ds : structures)
        {
            List<AdamVariable> all = new ArrayList<>();
            for (AdamVariableSet set : ds.analysisVariableSets())
            {
                all.addAll(set.analysisVariables());
            }
            for (AdamVariable v : sortAdamByOrdinal(all))
            {
                String name = v.name().orElse(null);
                if (name == null || name.isEmpty())
                {
                    continue;
                }
                Map<String, String> attrs = new LinkedHashMap<>();
                v.label().ifPresent(l -> attrs.put(ATTR_LABEL, l));
                v.ordinal().ifPresent(o -> attrs.put(ATTR_ORDINAL, o));
                v.simpleDatatype().ifPresent(t -> attrs.put(ATTR_SIMPLE_DATATYPE, t));
                v.core().ifPresent(c -> attrs.put("core", c));
                // ADaM variables don't carry a "role" attribute the way SDTM does; leave absent.
                seen.putIfAbsent(name, new ResolvedVariable(name, attrs));
            }
        }
        return Collections.unmodifiableList(new ArrayList<>(seen.values()));
    }


    /**
     * Mirrors Python's FINDINGS_ABOUT class-variable merge. FINDINGS class vars are split around
     * the {@code --TEST} variable: pre-{@code --TEST} (inclusive) prepended, post-{@code --TEST}
     * appended after the FINDINGS ABOUT class vars.
     */
    private static List<SdtmVariable> mergeFindingsAboutClassVariables(
            List<SdtmVariable> aFindingsVars, List<SdtmVariable> aFindingsAboutVars)
    {
        List<SdtmVariable> sortedFindings = sortSdtmByOrdinal(aFindingsVars);
        int testIndex = -1;
        for (int i = 0; i < sortedFindings.size(); i++)
        {
            if ("--TEST".equals(sortedFindings.get(i).name().orElse(null)))
            {
                testIndex = i;
                break;
            }
        }
        if (testIndex < 0)
        {
            // No --TEST variable; fall through to a simple concatenation.
            List<SdtmVariable> out = new ArrayList<>(sortedFindings);
            out.addAll(aFindingsAboutVars);
            return out;
        }
        List<SdtmVariable> out = new ArrayList<>();
        out.addAll(sortedFindings.subList(0, testIndex + 1));
        out.addAll(aFindingsAboutVars);
        out.addAll(sortedFindings.subList(testIndex + 1, sortedFindings.size()));
        return out;
    }


    /** True when the given normalised class name is a detectable observation class. */
    private static boolean isDetectableClass(String aNormalisedClassName)
    {
        // EC-85: one vocabulary, shared with the `model_class` load-time validation.
        return SdtmObservationClasses.isDetectable(aNormalisedClassName);
    }


    /**
     * Mirrors Python's {@code convert_library_class_name_to_ct_class}: lowercases for the
     * special-purpose alias, otherwise uppercases. Ensures case-insensitive comparisons against
     * normalised constants like {@code "EVENTS"}, {@code CLASS_FINDINGS_ABOUT}.
     */
    private static @Nullable String ctClassName(@Nullable String aClassName)
    {
        // EC-85: one vocabulary, shared with the `model_class` load-time validation.
        return SdtmObservationClasses.normalise(aClassName);
    }


    /** True when the loaded SDTM product owns a dataset with the given name. */
    private boolean sdtmProductHasDomain(String aDomain)
    {
        if (sdtmProduct == null || aDomain == null)
        {
            return false;
        }
        for (SdtmClass klass : sdtmProduct.classes())
        {
            for (SdtmDataset ds : klass.datasets())
            {
                if (aDomain.equalsIgnoreCase(ds.name().orElse(null)))
                {
                    return true;
                }
            }
        }
        return false;
    }


    /** Look up a class by name (compared after {@link #ctClassName} normalisation). */
    private @Nullable SdtmClass findSdtmClassByName(@Nullable String aNormalisedName)
    {
        if (sdtmProduct == null || aNormalisedName == null)
        {
            return null;
        }
        for (SdtmClass klass : sdtmProduct.classes())
        {
            if (aNormalisedName.equals(ctClassName(klass.name().orElse(null))))
            {
                return klass;
            }
        }
        return null;
    }


    /**
     * Look up the IG-level {@link SdtmDataset} owning the given domain, scanning every loaded
     * class. Returns {@code null} if the domain isn't in the product (custom domain). The returned
     * dataset's {@link SdtmDataset#datasetVariables()} is the source of IG-level variables for Fix
     * #42 Phase 2's IG-override merge step (Python's
     * {@code IG_domain_details["datasetVariables"]}).
     */
    private @Nullable SdtmDataset findSdtmDatasetByDomain(String aDomain)
    {
        if (sdtmProduct == null || aDomain == null)
        {
            return null;
        }
        for (SdtmClass klass : sdtmProduct.classes())
        {
            for (SdtmDataset ds : klass.datasets())
            {
                if (aDomain.equalsIgnoreCase(ds.name().orElse(null)))
                {
                    return ds;
                }
            }
        }
        return null;
    }


    /**
     * Look up a dataset at the SDTM <em>Model</em> product's top level by domain name. Mirrors
     * Python's {@code get_model_domain_metadata}, which searches {@code model_details["datasets"]}
     * (the class-less Model datasets such as {@code DM}, {@code CO}, {@code SE}, and the
     * trial-design / relationship datasets). Returns {@code null} when no Model is configured or
     * the domain is absent.
     */
    private @Nullable SdtmDataset findModelDatasetByDomain(String aDomain)
    {
        if (sdtmModelProduct == null || aDomain == null)
        {
            return null;
        }
        for (SdtmDataset ds : sdtmModelProduct.datasets())
        {
            if (aDomain.equalsIgnoreCase(ds.name().orElse(null)))
            {
                return ds;
            }
        }
        return null;
    }


    /**
     * The data structures a dataset's <em>class</em> resolves to, governing tier first — the
     * domain-keyed counterpart of {@link #adamNamesWhereCore}'s structure-keyed walk, and routed
     * through the very same {@link #governingTiers} so the two cannot disagree.
     *
     * <p>
     * ⛔⛔ <b>Phase 11 finding F1 — what this replaces.</b> The predecessor,
     * {@code findAdamDataStructureByClassName}, compared the requested class against each product's
     * raw published {@code class} string and returned the <b>first</b> hit in declaration order.
     * Two defects fell out of that, both invisible while only one product could be declared:
     * </p>
     * <ol>
     * <li><b>The answer depended on declaration order.</b> {@code -mp
     * adam/adam-nca-1-0,adam/adamig-1-3} on a plain BDS dataset returned {@code adam-nca-1-0}'s
     * subclass-only {@code ADNCA}; the reverse order returned {@code adamig-1-3}'s base
     * {@code BDS}. Phase 8's own assertion — <i>the answer must not depend on declaration order</i>
     * — was true one method away and false here.</li>
     * <li><b>No alias and no override handling.</b> A raw-string comparison against
     * {@code BASIC DATA STRUCTURE} matches {@code adamig-1-3} and silently nothing in
     * {@code adamig-1-0}/{@code 1-1}/{@code 1-2}, which spell the same class {@code BDS}; and
     * {@code adam-tte-1-0}'s class-less {@code BDS for TTE}, which
     * {@link #STRUCTURE_CLASS_OVERRIDES} exists to reach, was unreachable.</li>
     * </ol>
     *
     * <p>
     * ⚠ The dataset's own columns are not available on this path, so the specificity chain is fed
     * the <b>study-declared</b> subclasses ({@link #getDeclaredSubClasses}) — the same
     * {@code def:SubClass} tier {@code AdamStructureContext.declaredSubClassesOf} reads. With no
     * declaration that leaves only the base tier, which is the correct conservative answer: a
     * supplement's specialisation must not be imposed on a dataset nothing says is one.
     * </p>
     *
     * @param aClassName
     *            a published or study-declared ADaM class string; {@code null}/blank yields empty
     * @param aDomain
     *            the dataset, used only to look up its declared subclasses
     * @return the contributing structures, governing tier first; empty when the class maps to no
     *         token, or when no declared product publishes an applicable structure for it
     */
    private List<AdamDataStructure> adamStructuresForClassName(@Nullable String aClassName,
            String aDomain)
    {
        String token = canonicalStructureToken(aClassName);
        if (token == null)
        {
            return List.of();
        }
        List<AdamDataStructure> out = new ArrayList<>();
        for (ChainTier tier : governingTiers(token, getDeclaredSubClasses(aDomain)))
        {
            for (SourcedStructure sourced : tier.structures())
            {
                out.add(sourced.structure());
            }
        }
        return List.copyOf(out);
    }


    /**
     * Resolves the dataset's observation class. Precedence (Fix #55):
     *
     * <ol>
     * <li><b>Define-XML / study class.</b> When {@link IMetadataLibrary#getDataTable(String)}
     * returns a table whose {@link IDataTableMetadata#getClassName()} is non-null and non-empty,
     * that wins (Define-XML may override the standard's class assignment).</li>
     * <li><b>Product reverse-walk.</b> When an {@link SdtmProduct} or {@link AdamProduct} is
     * configured, scan its classes / data structures for one that owns a dataset whose name
     * matches.</li>
     * <li><b>Custom-domain sniffer.</b> Fix #41 hooks in here. Until the sniffer ships, this tier
     * returns {@code null}.</li>
     * </ol>
     *
     * @return the class name, or {@code null} when none of the tiers can resolve it.
     */
    @Override
    public @Nullable String getDatasetClass(String aDomain)
    {
        // Single-arg form: caller has only one identifier — use it for every tier. Equivalent to
        // the historical (pre-Fix-#60) behaviour, but routed through the 2-arg implementation so
        // there is one resolver body. Callers with split-dataset awareness should prefer
        // {@link #getDatasetClass(String, String)}.
        return getDatasetClass(aDomain, aDomain);
    }


    /**
     * Fix #119: the dataset's declared study-metadata class (the Define-XML {@code def:Class} value
     * via {@link IDataTableMetadata#getClassName()}) — verbatim, no product walk, no curated
     * fallback, no heuristics. {@code null} when the study metadata declares nothing. Review
     * finding 3: reads the {@link #declaredSourceLibrary() undecorated study library} so a
     * standards-library class supplied by {@link EnrichedMetadataLibrary} enrichment never
     * masquerades as a declared define class.
     */
    @Override
    public @Nullable String getDeclaredDatasetClass(String aDatasetName)
    {
        return declaredSourceLibrary().getDataTable(aDatasetName)
                .map(IDataTableMetadata::getClassName).filter(c -> !c.isEmpty()).orElse(null);
    }


    /**
     * Fix #119: the dataset's declared Define-XML 2.1 {@code <def:SubClass>} names via
     * {@link IDataTableMetadata#getSubClassNames()}; empty when the study metadata declares none.
     */
    @Override
    public List<String> getDeclaredSubClasses(String aDatasetName)
    {
        return declaredSourceLibrary().getDataTable(aDatasetName)
                .map(IDataTableMetadata::getSubClassNames).orElse(List.of());
    }


    /**
     * The library to consult for <em>declared</em> study-metadata values: the undecorated primary
     * when {@link #library} is an {@link EnrichedMetadataLibrary}, else the library itself.
     */
    private IMetadataLibrary declaredSourceLibrary()
    {
        return library instanceof EnrichedMetadataLibrary enriched ? enriched.getPrimary()
                : library;
    }


    /**
     * Fix #60: two-argument resolver that respects the split between member name (used by the
     * study-side {@link IMetadataLibrary}) and CDISC domain code (used by the products and the
     * custom-domain sniffer).
     *
     * <p>
     * Tier 1 looks up the table via {@code library.getDataTable(memberName)} — the study library is
     * keyed by file/member name (e.g. {@code LBHE}), not the CDISC code (e.g. {@code LB}). Tier 2
     * walks the CDISC product by domain code. Tier 3 runs the custom-domain sniffer over the
     * dataset's column list (memberName-keyed IMetadataLibrary view) using the CDISC code as the
     * topic-variable prefix (so {@code <CDISC>TESTCD} etc. match correctly even when the member
     * name is {@code LBHE}).
     * </p>
     */
    @Override
    public @Nullable String getDatasetClass(@Nullable String aMemberName, String aCdiscDomain)
    {
        return getDatasetClass(aMemberName, aCdiscDomain, null);
    }


    @Override
    public @Nullable String getDatasetClass(@Nullable String aMemberName, String aCdiscDomain,
            @Nullable Set<String> aActualColumns)
    {
        // Tier 1 — Define-XML / study class wins. Always keyed by member name. A null member name
        // means the dataset has no usable name; tier 1 cannot resolve, so treat as "no table".
        Optional<IDataTableMetadata> table = aMemberName == null ? Optional.empty()
                : library.getDataTable(aMemberName);
        String studyClass = tier1StudyClass(table);
        if (studyClass != null)
        {
            return studyClass;
        }
        // Tier 2 — product reverse-walk by CDISC domain code (only when products are present and
        // not in failed state).
        String productClass = tier2ProductClass(aCdiscDomain);
        if (productClass != null)
        {
            return productClass;
        }
        // Tier 2.5 — curated, JSON-backed static fallback. Reached whenever the Library product
        // walk yields nothing (no API key, network failure, or a domain the product doesn't cover),
        // so known standard domains (e.g. DM -> SPECIAL PURPOSE) still resolve offline. Placed
        // before the heuristic sniffer so the curated mapping wins for known standard domains.
        String mapped = DomainClassMap.getInstance().classFor(standardFamily(), aCdiscDomain);
        if (mapped != null)
        {
            return mapped;
        }
        // Tier 3 — Fix #41 custom-domain sniffer. Prefer the actual dataset's columns when the
        // caller supplied them (Python parity: handle_custom_domains runs on the loaded dataset),
        // so SUPP-- and other datasets absent from the metadata library still classify. Otherwise
        // fall back to the IMetadataLibrary view (member-name-keyed). The topic-variable prefix is
        // the CDISC code so e.g. LBHE's columns are matched against LBTESTCD, not LBHETESTCD.
        // Returns null when no pattern matches; ScopeMatcher.matchesClass treats null strictly
        // under Fix #41, skipping any class-scoped rule on this dataset and emitting a one-time
        // WARN
        // per dataset from RuleGenerator.
        // Prefer the actual dataset's columns when supplied; otherwise the metadata-library table's
        // columns. This is the column set consulted by both the tier-3 sniffer and the FU-4 gate.
        Set<String> actualColumns = aActualColumns != null && !aActualColumns.isEmpty()
                ? aActualColumns
                : columnNames(table);
        String sniffed = CustomDomainClassDetector.detectClass(actualColumns, aCdiscDomain);
        if (sniffed != null)
        {
            return sniffed;
        }
        // FU-4: synthetic ADAMOTHER class token. Reaching here means every tier fell through: no
        // Define-XML / study class (tier 1), the domain is not a named ADaM data structure in the
        // product (tier 2), the curated DomainClassMap has no entry (tier 2.5), and the sniffer
        // recognised no custom-domain signature in the columns (tier 3). For an ADaM run whose
        // dataset also carries NONE of the BDS/OCCDS structural indicator columns, this is a
        // genuinely structure-less dataset, so return the "ADAM OTHER" sentinel instead of null.
        // Without it, Fix #41 strict-on-null makes the dataset unreachable by ANY Classes-scoped
        // rule; with it, only rules that opt in via Scope.Classes.Include:["ADAM OTHER"] reach it.
        // The positive structure-absence gate (no BDS/OCCDS indicator column) ensures a real
        // BDS/OCCDS dataset that merely lacks a resolved Define/library class is never mislabelled
        // "ADAM OTHER". Mirrors the Python base_data_service.get_dataset_class gate (same
        // bds_indicators / occds_indicators).
        if ("adam".equals(standardFamily()) && hasNoAdamStructureIndicators(actualColumns))
        {
            return CLASS_ADAM_OTHER;
        }
        return null;
    }


    /** The (case-preserving) column names of a metadata-library table, or empty when absent. */
    private static Set<String> columnNames(Optional<IDataTableMetadata> aTable)
    {
        if (aTable.isEmpty())
        {
            return Set.of();
        }
        Set<String> names = new HashSet<>();
        for (IColumnMetadata col : aTable.get().getColumns())
        {
            names.add(col.getName());
        }
        return names;
    }


    /**
     * Whether {@code aColumns} carries NONE of the ADaM BDS/OCCDS structural indicator columns —
     * the positive structure-absence signal gating the FU-4 "ADAM OTHER" sentinel. Delegates to
     * {@link AdamDataStructureDetector#hasNoStructureIndicators} — the single source of truth for
     * the BDS/OCCDS indicator sets, shared with the {@code Scope.Data_Structures} gate. (The
     * detector carries the {@code ARAMCD}→{@code PARAMCD} typo correction, user decision
     * 2026-07-26.)
     */
    private static boolean hasNoAdamStructureIndicators(@Nullable Set<String> aColumns)
    {
        return AdamDataStructureDetector.hasNoStructureIndicators(aColumns);
    }


    private @Nullable String tier1StudyClass(Optional<IDataTableMetadata> aTable)
    {
        if (aTable.isEmpty())
        {
            return null;
        }
        String studyClass = aTable.get().getClassName();
        if (studyClass == null || studyClass.isEmpty())
        {
            studyClass = metaString(aTable.get(), MetadataKeys.CLASS_NAME).orElse(null);
        }
        if (studyClass != null && !studyClass.isEmpty())
        {
            return studyClass;
        }
        return null;
    }


    private @Nullable String tier2ProductClass(String aCdiscDomain)
    {
        if (libraryFailed)
        {
            return null;
        }
        if (sdtmProduct != null)
        {
            String c = sdtmClassForDomain(aCdiscDomain);
            if (c != null)
            {
                return c;
            }
        }
        if (!adamProducts.isEmpty())
        {
            String c = adamClassForDomain(aCdiscDomain);
            if (c != null)
            {
                return c;
            }
        }
        return null;
    }


    /**
     * Derives the {@link DomainClassMap} standard family ({@code sdtm} / {@code send} /
     * {@code adam}) from this provider's configured standard. Returns {@code null} for an unknown
     * standard, in which case the map scans every family.
     */
    private @Nullable String standardFamily()
    {
        String std = getStandard();
        if (std == null)
        {
            return null;
        }
        String n = std.toLowerCase(Locale.ROOT);
        if (n.startsWith("send"))
        {
            return "send";
        }
        if (n.startsWith("sdtm"))
        {
            return "sdtm";
        }
        if (n.startsWith("adam"))
        {
            return "adam";
        }
        return null;
    }


    @Override
    public boolean isDomainCustom(String aDomain)
    {
        // Mirror Python's is_custom_domain (sdtm_utilities.py): a domain is custom iff it is absent
        // from BOTH the IG (standard) dataset list AND the SDTM Model dataset list. Compute from
        // the
        // loaded products when an SDTM product is configured; otherwise fall back to the per-table
        // IS_CUSTOM_DOMAIN flag for product-less (Define-XML / degraded) configurations.
        if (sdtmProduct != null)
        {
            return !sdtmProductHasDomain(aDomain) && findModelDatasetByDomain(aDomain) == null;
        }
        return library.getDataTable(aDomain)
                .flatMap(t -> metaBoolean(t, MetadataKeys.IS_CUSTOM_DOMAIN)).orElse(Boolean.FALSE);
    }


    @Override
    public Map<String, String> getVariableMetadata(String aDomain, String aVariable)
    {
        Optional<IColumnMetadata> direct = findColumn(aDomain, aVariable);
        if (direct.isPresent())
        {
            return columnToMap(direct.get());
        }
        // Algorithm-B fallback (mirrors Python's get_variables_metadata_from_standard, the source
        // the
        // VariablesMetadataWithLibraryMetadata dataset builder joins on): the IG dataset's own
        // column
        // list ({@link #findColumn}) carries only the IG-declared variables, so a GENERAL
        // OBSERVATIONS identifier / timing variable (STUDYID, DOMAIN, USUBJID, --SEQ, …) that the
        // SDTM Model contributes is absent from it. Resolve those from the merged IG-base + Model
        // list so library_variable_* is populated for them — matching Python, where the library
        // frame includes every model-merged variable with its simpleDatatype / label / role. Only
        // when an SDTM product is configured (degraded / Define-only runs keep the IG-only lookup).
        if (hasSdtmProduct())
        {
            for (ResolvedVariable v : buildResolvedSdtm(aDomain))
            {
                if (aVariable.equals(v.name()))
                {
                    return withCanonicalCodelist(v.toAttributeMap(), aDomain, aVariable);
                }
            }
        }
        return Map.of();
    }


    /**
     * <b>Fix #373</b> — leg 2 rebuilds a variable from the product's <em>class walk</em>, which
     * carries no codelist binding, so it publishes only {@code name, label, simpleDatatype,
     * ordinal, core, role}. A {@code SUPP--} / {@code SQ--} / {@code AP--} member therefore lost
     * the whole codelist family that the CANONICAL name publishes — measured: {@code AE.AESEV}
     * resolves 11 keys through leg 1 while {@code APAE.AESEV} resolved 6 through leg 2, and
     * {@code getCodelistCodeMap("AE","AESEV")} returned 3 entries against 0 for {@code APAE}.
     *
     * <p>
     * ⭐⭐ <b>The source is the CANONICAL name's OWN codelist, never the parent domain's.</b> For
     * {@code AP--} those coincide ({@code AP--} is structurally a copy of its parent), but for
     * {@code SUPP--} they emphatically do not: {@code SUPPQUAL} publishes {@code DOMAIN} for
     * {@code RDOMAIN} (83 terms) and {@code EVAL} for {@code QEVAL} (60 terms) and <b>nothing</b>
     * for {@code QVAL}, whose meaning varies per {@code QNAM} row. Borrowing the parent domain's
     * codelist for {@code SUPPAE.QVAL} would fire findings on conforming data — which is exactly
     * why this enriches from {@link #canonicalSdtmDomain}, not from the AP/SUPP parent.
     * </p>
     *
     * <p>
     * ⚠ {@code putIfAbsent} semantics: leg 2's own answer always wins. This only fills keys leg 2
     * does not have, so a variable the class walk already described completely is untouched, and a
     * canonical name with no codelist adds nothing (the no-codelist case stays absent, which is
     * what {@code var_codelist_extensible}'s D4 rule — <em>absent ⇒ treated extensible ⇒ no
     * fire</em> — depends on).
     * </p>
     */
    private Map<String, String> withCanonicalCodelist(Map<String, String> aBase,
            String aOriginalDomain, String aVariable)
    {
        String canonical = canonicalSdtmDomain(aOriginalDomain);
        if (canonical.equals(aOriginalDomain))
        {
            return aBase; // not a derived name — leg 1 already had its chance
        }
        Optional<IColumnMetadata> col = findColumn(canonical, aVariable);
        if (col.isEmpty())
        {
            return aBase;
        }
        Map<String, String> canonicalMap = columnToMap(col.get());
        Map<String, String> out = new LinkedHashMap<>(aBase);
        for (String key : CODELIST_ATTRIBUTES)
        {
            String value = canonicalMap.get(key);
            if (value != null)
            {
                out.putIfAbsent(key, value);
            }
        }
        return Collections.unmodifiableMap(out);
    }


    @Override
    public List<Map<String, String>> getDomainVariables(String aDomain)
    {
        return columnsOf(aDomain).stream().map(this::columnToMap).toList();
    }


    @Override
    public List<Map<String, String>> getModelVariables(String aDomain)
    {
        Optional<IDataTableMetadata> table = library.getDataTable(aDomain);
        if (table.isEmpty())
        {
            return List.of();
        }
        Optional<Object> raw = table.get().getMetaValue(MetadataKeys.MODEL_VARIABLES);
        if (raw.isEmpty())
        {
            return List.of();
        }
        Object value = raw.get();
        if (value instanceof List<?> list)
        {
            List<Map<String, String>> out = new ArrayList<>(list.size());
            for (Object item : list)
            {
                if (item instanceof Map<?, ?> map)
                {
                    out.add(Collections.unmodifiableMap(stringifyMap(map)));
                }
            }
            return Collections.unmodifiableList(out);
        }
        return List.of();
    }


    private static Map<String, String> stringifyMap(Map<?, ?> aMap)
    {
        Map<String, String> m = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : aMap.entrySet())
        {
            if (e.getKey() != null && e.getValue() != null)
            {
                m.put(e.getKey().toString(), e.getValue().toString());
            }
        }
        return m;
    }


    @Override
    public List<String> getPublishedCtPackages()
    {
        Optional<Object> raw = library.getMetaValue(MetadataKeys.PUBLISHED_CT_PACKAGES);
        if (raw.isEmpty())
        {
            return List.of();
        }
        Object value = raw.get();
        if (value instanceof List<?> list)
        {
            return list.stream().filter(Objects::nonNull).map(Object::toString).toList();
        }
        return List.of();
    }


    @Override
    public Map<String, String> getDatasetMetadata(String aDomain)
    {
        Optional<IDataTableMetadata> opt = library.getDataTable(aDomain);
        if (opt.isEmpty())
        {
            return Map.of();
        }
        IDataTableMetadata dt = opt.get();
        Map<String, String> result = new LinkedHashMap<>();
        putIfPresent(result, "name", dt.getName());
        putIfPresent(result, ATTR_LABEL, dt.getLabel());
        putIfPresent(result, "datasetStructure", structureOf(dt));
        putIfPresent(result, "className", classNameOf(dt));
        return Collections.unmodifiableMap(result);
    }

    // ------------------------------------------------------------------
    // Codelist queries
    // ------------------------------------------------------------------


    @Override
    public List<String> getCodelistTerms(String aCodelistCode)
    {
        return findCodelist(aCodelistCode).map(cl -> cl.getEntries().stream()
                .map(ICodelistEntry::getCodeValue).filter(Objects::nonNull).toList())
                .orElse(List.of());
    }


    @Override
    public List<String> getCodelistAttribute(String aCtPackageId, String aCtAttribute)
    {
        CtPackage pkg = resolveCtPackage(aCtPackageId);
        if (pkg == null)
        {
            return List.of();
        }
        // LinkedHashSet: order-preserving with O(1) membership (the attribute lists run to tens of
        // thousands of term codes, so an ArrayList-contains dedup would be O(n^2)).
        Set<String> out = new LinkedHashSet<>();
        switch (aCtAttribute)
        {
        case "Codelist CCODE" -> collectDistinct(out,
                pkg.codelists().stream().map(CtCodelist::conceptId));
        case "Codelist Value" -> collectDistinct(out,
                pkg.codelists().stream().map(CtCodelist::submissionValue));
        case "Term CCODE" -> collectDistinct(out,
                pkg.codelists().stream().flatMap(c -> c.terms().stream()).map(CtTerm::conceptId));
        case "Term Value", "Term Submission Value" -> collectDistinct(out, pkg.codelists().stream()
                .flatMap(c -> c.terms().stream()).map(CtTerm::submissionValue));
        case "Term Preferred Term" -> collectDistinct(out, pkg.codelists().stream()
                .flatMap(c -> c.terms().stream()).map(CtTerm::preferredTerm));
        default ->
        {
            // Unknown attribute — Python raises ValueError; we degrade to empty so a bad rule
            // SKIPs rather than aborting the suite.
        }
        }
        return List.copyOf(out);
    }


    /**
     * Resolves the requested CT package: the configured one when the id matches (or no id was
     * configured but a package was supplied), otherwise via the loader. Returns {@code null} when
     * neither yields a package.
     */
    private @Nullable CtPackage resolveCtPackage(String aCtPackageId)
    {
        // Exact match to the configured package id wins (avoids a redundant cache load).
        if (configuredCtPackage != null && aCtPackageId.equals(configuredCtPackageId))
        {
            return configuredCtPackage;
        }
        // Otherwise load the requested package by id from the pickle cache. (A null
        // configuredCtPackageId must NOT shadow the loader — a row may reference a package other
        // than, or in the absence of, the configured one.)
        if (ctPackageLoader != null)
        {
            CtPackage loaded = ctPackageLoader.apply(aCtPackageId).orElse(null);
            if (loaded != null)
            {
                return loaded;
            }
        }
        // Last resort: the configured package (possibly empty) when no loader / no cache hit.
        return configuredCtPackage;
    }


    private static void collectDistinct(Set<String> aOut, Stream<Optional<String>> aValues)
    {
        aValues.filter(Optional::isPresent).map(Optional::get).filter(Objects::nonNull)
                .filter(v -> !v.isEmpty()).forEach(aOut::add);
    }


    @Override
    public Map<String, String> getCodelistTermMappings(String aCodelistName)
    {
        Optional<ICodeList> cl = findCodelist(aCodelistName);
        if (cl.isEmpty())
        {
            return Map.of();
        }
        Map<String, String> mappings = cl.get().getEntries().stream()
                .filter(e -> e.getCodeValue() != null)
                .collect(Collectors.toMap(ICodelistEntry::getCodeValue,
                        e -> e.getDecodeValue() == null ? "" : e.getDecodeValue(), (a, _) -> a,
                        LinkedHashMap::new));
        return Collections.unmodifiableMap(mappings);
    }


    @Override
    public boolean isCodelistExtensible(String aCodelistName)
    {
        Optional<ICodeList> cl = findCodelist(aCodelistName);
        if (cl.isEmpty())
        {
            // Unknown codelist — default to extensible to avoid false positives.
            return true;
        }
        Boolean extensible = cl.get().isExtensible();
        // Per the ICodeList contract, null means "unknown".
        return extensible == null || extensible;
    }

    // ------------------------------------------------------------------
    // Product walks (Fix #55)
    // ------------------------------------------------------------------


    /**
     * Walks {@link #sdtmProduct} for the class owning {@code aDomain} and returns the ordered names
     * of {@code klass.classVariables()}. Empty list if the domain is not in the product.
     */
    private List<String> sdtmModelColumnOrder(String aDomain)
    {
        if (sdtmProduct == null || aDomain == null)
        {
            return List.of();
        }
        SdtmClass klass = sdtmClassFor(aDomain);
        if (klass == null)
        {
            return List.of();
        }
        List<SdtmVariable> vars = sortSdtmByOrdinal(klass.classVariables());
        List<String> out = new ArrayList<>(vars.size());
        for (SdtmVariable v : vars)
        {
            v.name().ifPresent(out::add);
        }
        return Collections.unmodifiableList(out);
    }


    private @Nullable String sdtmClassForDomain(String aDomain)
    {
        SdtmClass klass = sdtmClassFor(aDomain);
        return klass != null ? klass.name().orElse(null) : null;
    }


    private @Nullable SdtmClass sdtmClassFor(String aDomain)
    {
        if (sdtmProduct == null || aDomain == null)
        {
            return null;
        }
        for (SdtmClass klass : sdtmProduct.classes())
        {
            for (SdtmDataset ds : klass.datasets())
            {
                if (aDomain.equalsIgnoreCase(ds.name().orElse(null)))
                {
                    return klass;
                }
            }
        }
        return null;
    }


    /**
     * Walks {@link #adamProducts} (in precedence order) for the data structure owning
     * {@code aDomain} and returns the ordered names of its analysis variables (flattened across
     * variable sets). Empty list if the domain is in no declared product.
     */
    private List<String> adamModelColumnOrder(String aDomain)
    {
        if (aDomain == null)
        {
            return List.of();
        }
        SourcedStructure sourced = adamDataStructureFor(aDomain);
        if (sourced == null)
        {
            return List.of();
        }
        List<AdamVariable> all = new ArrayList<>();
        for (AdamVariableSet set : sourced.structure().analysisVariableSets())
        {
            all.addAll(set.analysisVariables());
        }
        List<AdamVariable> ordered = sortAdamByOrdinal(all);
        List<String> out = new ArrayList<>(ordered.size());
        for (AdamVariable v : ordered)
        {
            v.name().ifPresent(out::add);
        }
        return Collections.unmodifiableList(out);
    }


    /**
     * Tier 2 of {@link #getDatasetClass(String, String, Set)} on the ADaM side: the canonical
     * structure token of the declared structure named {@code aDomain}.
     *
     * <p>
     * ⛔⛔ <b>Phase 11 finding F1 — this returned {@code ds.className()} verbatim.</b> Declaring
     * {@code -mp adam/adam-adae-1-0} therefore made {@code getDatasetClass("ADAE")} answer the raw
     * {@code "ADAE"}, which is in none of {@link AdamDataStructureDetector#STRUCTURE_TOKENS} — so
     * every consumer of the class token ({@code OperationExecutor}'s class-keyed grouping,
     * {@code LibraryValidator}'s AP-inherit walk, {@code ScopeMatcher.matchesClass}) saw a token it
     * has no vocabulary for. Before the product could be declared at all the same dataset fell
     * through to the FU-4 {@code ADAM OTHER} sentinel, i.e. declaring the product made the answer
     * <em>worse</em>. Running the class through {@link #structureTokenOf} — alias table and
     * product-keyed override included — answers {@code OCCURRENCE DATA STRUCTURE}, and a class that
     * maps to no token yields {@code null} so the remaining tiers (curated map, sniffer,
     * {@code ADAM OTHER}) still get their turn.
     * </p>
     */
    private @Nullable String adamClassForDomain(String aDomain)
    {
        SourcedStructure sourced = adamDataStructureFor(aDomain);
        return sourced != null ? structureTokenOf(sourced.cacheKey(), sourced.structure()) : null;
    }


    /**
     * The declared structure <b>named</b> {@code aDomain}, with the product that published it.
     *
     * <p>
     * ⚠ Unlike the class-keyed {@link #adamStructuresForClassName}, first-match-wins on the user's
     * declaration order is <b>correct</b> here and is ruling 1 of
     * {@code plans/PLAN-metadata-product-selection.md}, not the Phase 8 defect: two products
     * publishing a structure of the same name are two equally specific descriptions of the same
     * thing, so there is no specificity with which to choose and the user's order decides. The
     * defect Phase 11's F1 fixed was order deciding between structures of <em>different</em>
     * specificity, which cannot arise from an exact name match.
     * </p>
     *
     * <p>
     * ⛔ The cache key is returned with the structure because {@link #adamClassForDomain} must run
     * it through the product-keyed {@link #STRUCTURE_CLASS_OVERRIDES}; reading
     * {@code ds.className()} directly is what made {@code getDatasetClass("ADAE")} answer the raw
     * {@code "ADAE"} under {@code -mp adam/adam-adae-1-0}.
     * </p>
     */
    private @Nullable SourcedStructure adamDataStructureFor(String aDomain)
    {
        if (aDomain == null)
        {
            return null;
        }
        for (DeclaredAdamProduct declared : adamProducts)
        {
            for (AdamDataStructure ds : declared.product().dataStructures())
            {
                if (aDomain.equalsIgnoreCase(ds.name().orElse(null)))
                {
                    return new SourcedStructure(declared.cacheKey(), ds);
                }
            }
        }
        return null;
    }


    private static List<SdtmVariable> sortSdtmByOrdinal(List<SdtmVariable> aVariables)
    {
        List<SdtmVariable> copy = new ArrayList<>(aVariables);
        copy.sort((a, b) -> Integer.compare(parseOrdinal(a.ordinal().orElse(null)),
                parseOrdinal(b.ordinal().orElse(null))));
        return copy;
    }


    private static List<AdamVariable> sortAdamByOrdinal(List<AdamVariable> aVariables)
    {
        List<AdamVariable> copy = new ArrayList<>(aVariables);
        copy.sort((a, b) -> Integer.compare(parseOrdinal(a.ordinal().orElse(null)),
                parseOrdinal(b.ordinal().orElse(null))));
        return copy;
    }


    private static int parseOrdinal(@Nullable String aOrdinal)
    {
        if (aOrdinal == null || aOrdinal.isEmpty())
        {
            return Integer.MAX_VALUE;
        }
        try
        {
            return Integer.parseInt(aOrdinal);
        }
        catch (NumberFormatException _)
        {
            return Integer.MAX_VALUE;
        }
    }

    // ------------------------------------------------------------------
    // Private helpers
    // ------------------------------------------------------------------


    private List<IColumnMetadata> columnsOf(String aDomain)
    {
        return library.getDataTable(aDomain).map(IDataTableMetadata::getColumns).orElse(List.of());
    }


    private List<String> columnNamesWhere(String aDomain, Predicate<IColumnMetadata> aPredicate)
    {
        return columnsOf(aDomain).stream().filter(aPredicate).map(IColumnMetadata::getName)
                .toList();
    }


    private Optional<IColumnMetadata> findColumn(String aDomain, String aVariable)
    {
        return library.getDataTable(aDomain).flatMap(t -> t.getColumn(aVariable));
    }


    /**
     * Looks up a codelist by name, then by concept id, then by submission value meta key. Handles
     * both name-based and C-code-based requests from the rule engine.
     */
    private Optional<ICodeList> findCodelist(String aNameOrCode)
    {
        if (aNameOrCode == null || aNameOrCode.isEmpty())
        {
            return Optional.empty();
        }
        // 1. Direct lookup by name
        Optional<ICodeList> direct = library.getCodelist(aNameOrCode);
        if (direct.isPresent())
        {
            return direct;
        }
        // 2. Scan for ConceptId meta-key match (NCI C-code)
        for (ICodeList cl : library.getCodelists())
        {
            if (metaString(cl, MetadataKeys.CODELIST_CONCEPT_ID).filter(aNameOrCode::equals)
                    .isPresent())
            {
                return Optional.of(cl);
            }
        }
        // 3. Scan for SubmissionValue meta-key match
        for (ICodeList cl : library.getCodelists())
        {
            if (metaString(cl, MetadataKeys.CODELIST_SUBMISSION_VALUE).filter(aNameOrCode::equals)
                    .isPresent())
            {
                return Optional.of(cl);
            }
        }
        return Optional.empty();
    }


    /**
     * E9 — the bound codelist's term submission value → term concept-id (NCI C-code) map for the
     * given variable. Built from the variable's bound codelist entries as
     * {@code getCodeValue() → getConceptId()} (the same two fields {@link #codelistCodedValues} and
     * {@link #codelistCodedCodes} read separately). Returns an empty map when the variable is
     * unknown, has no bound codelist, or the codelist is not found in the loaded CT. Backs the
     * {@code library_variable_code_pair_matches} accessor (FDA-CT2003 / PMDA-CT2003).
     */
    @Override
    public Map<String, String> getCodelistCodeMap(String aDomain, String aVariable)
    {
        Map<String, String> direct = codelistCodeMapOf(aDomain, aVariable);
        if (!direct.isEmpty())
        {
            return direct;
        }
        // Fix #373 — this accessor resolves through findColumn ALONE: unlike getVariableMetadata it
        // has no algorithm-B leg, so it never saw the SUPP/SQ → SUPPQUAL canonicalisation or the AP
        // strip. Measured: getCodelistCodeMap("AE","AESEV") → 3 entries, ("APAE","AESEV") → 0. It
        // backs library_variable_code_pair_matches (FDA-CT2003 / PMDA-CT2003), so the miss silenced
        // those rules on every AP and SUPP dataset.
        // ⚠ Same rule as withCanonicalCodelist: the CANONICAL name's own codelist, never the
        // parent domain's — SUPPQUAL's RDOMAIN/QEVAL are its own, and QVAL genuinely has none.
        String canonical = canonicalSdtmDomain(aDomain);
        return canonical.equals(aDomain) ? direct : codelistCodeMapOf(canonical, aVariable);
    }


    /**
     * The findColumn-keyed half of {@link #getCodelistCodeMap}, before Fix #373's canonical leg.
     */
    private Map<String, String> codelistCodeMapOf(String aDomain, String aVariable)
    {
        return findColumn(aDomain, aVariable).map(col ->
        {
            String name = codelistOf(col);
            if (name == null)
            {
                return Map.<String, String> of();
            }
            return findCodelist(name).map(cl ->
            {
                Map<String, String> map = new LinkedHashMap<>();
                for (ICodelistEntry entry : cl.getEntries())
                {
                    if (entry.getCodeValue() != null && entry.getConceptId() != null)
                    {
                        map.putIfAbsent(entry.getCodeValue(), entry.getConceptId());
                    }
                }
                return Collections.<String, String> unmodifiableMap(map);
            }).orElse(Map.of());
        }).orElse(Map.of());
    }


    private Map<String, String> columnToMap(IColumnMetadata aCol)
    {
        Map<String, String> map = new LinkedHashMap<>();
        putIfPresent(map, "name", aCol.getName());
        putIfPresent(map, ATTR_LABEL, aCol.getLabel());
        putIfPresent(map, ATTR_SIMPLE_DATATYPE, mapSimpleDatatype(aCol.getType()));
        map.put(ATTR_ORDINAL, Integer.toString(aCol.getIndex()));
        putIfPresent(map, "core", coreOf(aCol));
        putIfPresent(map, "role", roleOf(aCol));
        putIfPresent(map, "codelist", codelistOf(aCol));
        // The bound codelist's NCI C-code (CODELIST_CONCEPT_ID). Backs var_ccode(…,"LIBRARY") /
        // library_variable_ccode (Java↔Python parity — Python materialises ccode at the library
        // level in dataset_builders/base_dataset_builder.py). Absent when there's no codelist.
        putIfPresent(map, "ccode", ccodeOf(aCol));
        // Codelist term submission values and extensibility, resolved from the bound codelist. Back
        // the var_codelist_coded_values / var_codelist_extensible accessors (value-check-against-
        // library, NRI-008). A no-codelist variable leaves both absent → the rule's guards no-fire.
        putIfPresent(map, "codelist_coded_values", codelistCodedValues(aCol));
        putIfPresent(map, "codelist_coded_codes", codelistCodedCodes(aCol));
        putIfPresent(map, "codelist_extensible", codelistExtensible(aCol));
        // Display format and Mandatory (Define-XML ItemDef/ItemRef): absent at the Library level,
        // so
        // gated non-empty. These back the var_format / var_mandatory accessors (the operands never
        // referenced them).
        putIfPresent(map, "format", aCol.getDisplayFormat());
        putIfPresent(map, "mandatory", metaString(aCol, "Mandatory").orElse(null));
        // Declared length, when the level (define / library) carries one. A length of 0 means
        // "unspecified" and is omitted, so an absent length resolves to empty rather than "0".
        if (aCol.getLength() > 0)
        {
            map.put("length", Integer.toString(aCol.getLength()));
        }
        return Collections.unmodifiableMap(map);
    }


    /**
     * Returns the CDISC {@code simpleDatatype} for a given column type. Mapping:
     * <ul>
     * <li>{@code STRING} → {@code "Char"}</li>
     * <li>{@code LONG}, {@code DOUBLE}, {@code BOOLEAN} → {@code "Num"}</li>
     * <li>{@code COMPLEX}, {@code OTHER}, {@code VARIABLE}, {@code MISSING}, {@code null} → not
     * emitted</li>
     * </ul>
     */
    private static @Nullable String mapSimpleDatatype(DataValueType aType)
    {
        if (aType == null)
        {
            return null;
        }
        return switch (aType)
        {
        case STRING -> "Char";
        case LONG, DOUBLE, BOOLEAN -> "Num";
        default -> null;
        };
    }


    private static @Nullable String coreOf(IColumnMetadata aCol)
    {
        String value = aCol.getCore();
        return value != null ? value : metaString(aCol, MetadataKeys.CORE).orElse(null);
    }


    private static @Nullable String roleOf(IColumnMetadata aCol)
    {
        String value = aCol.getRole();
        return value != null ? value : metaString(aCol, MetadataKeys.ROLE).orElse(null);
    }


    private static @Nullable String codelistOf(IColumnMetadata aCol)
    {
        String value = aCol.getCodelist();
        return value != null ? value : metaString(aCol, MetadataKeys.CODELIST).orElse(null);
    }


    /**
     * The bound codelist's NCI C-code ({@code MetadataKeys.CODELIST_CONCEPT_ID}), or {@code null}
     * when the variable has no codelist, the codelist is not found in the loaded CT, or it exposes
     * no concept id. Backs the {@code var_ccode(…,"LIBRARY")} / {@code library_variable_ccode}
     * accessor.
     */
    private @Nullable String ccodeOf(IColumnMetadata aCol)
    {
        String name = codelistOf(aCol);
        if (name == null)
        {
            return null;
        }
        return findCodelist(name).flatMap(cl -> metaString(cl, MetadataKeys.CODELIST_CONCEPT_ID))
                .orElse(null);
    }


    /**
     * The bound codelist's term submission values as a JSON-encoded list (the {@code isList()}
     * channel decoded by {@code ExprCompiler}), or {@code null} when the variable has no codelist
     * or the codelist is not found in the loaded CT.
     */
    private @Nullable String codelistCodedValues(IColumnMetadata aCol)
    {
        String name = codelistOf(aCol);
        if (name == null)
        {
            return null;
        }
        return findCodelist(name)
                .map(cl -> DefineMetadataListCodec.encode(cl.getEntries().stream()
                        .map(ICodelistEntry::getCodeValue).filter(Objects::nonNull).toList()))
                .orElse(null);
    }


    /**
     * The bound codelist's term concept ids (NCI C-codes) as a JSON-encoded list, or {@code null}
     * when the variable has no codelist, the codelist is not found, or no term exposes a concept
     * id. Backs the {@code var_codelist_coded_codes("LIBRARY")} ADaM code accessor.
     */
    private @Nullable String codelistCodedCodes(IColumnMetadata aCol)
    {
        String name = codelistOf(aCol);
        if (name == null)
        {
            return null;
        }
        return findCodelist(name).map(cl ->
        {
            List<String> codes = cl.getEntries().stream().map(ICodelistEntry::getConceptId)
                    .filter(Objects::nonNull).toList();
            return codes.isEmpty() ? null : DefineMetadataListCodec.encode(codes);
        }).orElse(null);
    }


    /**
     * Lower-case {@code "true"} / {@code "false"} for the bound codelist's extensibility, or
     * {@code null} when the variable has no codelist or it is not found (treated as extensible — no
     * fire, D4).
     */
    private @Nullable String codelistExtensible(IColumnMetadata aCol)
    {
        String name = codelistOf(aCol);
        if (name == null)
        {
            return null;
        }
        return findCodelist(name).map(ICodeList::isExtensible).map(String::valueOf).orElse(null);
    }


    private static @Nullable String classNameOf(IDataTableMetadata aTable)
    {
        String value = aTable.getClassName();
        return value != null ? value : metaString(aTable, MetadataKeys.CLASS_NAME).orElse(null);
    }


    private static @Nullable String structureOf(IDataTableMetadata aTable)
    {
        String value = aTable.getStructure();
        return value != null ? value
                : metaString(aTable, MetadataKeys.DATASET_STRUCTURE).orElse(null);
    }


    private static Optional<String> metaString(IMetadataElement aElement, String aKey)
    {
        return aElement.getMetaValue(aKey).map(Object::toString).filter(s -> !s.isEmpty());
    }


    private static Optional<Boolean> metaBoolean(IMetadataElement aElement, String aKey)
    {
        return aElement.getMetaValue(aKey).map(value ->
        {
            if (value instanceof Boolean b)
            {
                return b;
            }
            return Boolean.parseBoolean(String.valueOf(value));
        });
    }


    private static void putIfPresent(Map<String, String> aMap, String aKey, @Nullable String aValue)
    {
        if (aValue != null && !aValue.isEmpty())
        {
            aMap.put(aKey, aValue);
        }
    }

}
