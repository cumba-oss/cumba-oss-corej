package net.cumba.corej.core.metadata;

import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

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
import net.cumba.datatable.metadata.ICodeList;
import net.cumba.datatable.metadata.ICodelistEntry;
import net.cumba.datatable.metadata.IColumnMetadata;
import net.cumba.datatable.metadata.IDataTableMetadata;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
import net.cumba.web.api.Link;
import org.jspecify.annotations.Nullable;

/**
 * An {@link IMetadataLibrary} adapter for pre-fetched CDISC Library API objects. Populates the
 * {@link MetadataKeys} contract so that {@link MetadataLibraryProvider} can read the result
 * uniformly.
 *
 * <p>
 * Construct instances via the static factories:
 * </p>
 * <ul>
 * <li>{@link #fromSdtm(String, String, SdtmProduct, CtPackageRef)} — SDTM / SDTMIG</li>
 * <li>{@link #fromAdam(String, String, AdamProduct, CtPackageRef, CtPackageRef)} — ADaM / ADaMIG,
 * with an optional SDTM CT fallback for codelists that ADaM variables reference but that live in
 * SDTM CT (e.g. {@code SEX}, {@code RACE})</li>
 * </ul>
 *
 * <p>
 * The resulting library is eagerly built and immutable. API calls are never made during metadata
 * lookups — all data is pre-fetched by the caller.
 * </p>
 */
public final class CdiscLibraryMetadataLibrary implements IMetadataLibrary
{

    private final String name;

    private final String version;

    private final List<IDataTableMetadata> tables;

    private final Map<String, IDataTableMetadata> tableIndex;

    private final List<ICodeList> codelists;

    private final Map<String, ICodeList> codelistIndex;

    private final Map<String, Object> meta;

    private CdiscLibraryMetadataLibrary(String aName, String aVersion,
            List<IDataTableMetadata> aTables, List<ICodeList> aCodelists, Map<String, Object> aMeta)
    {
        name = aName;
        version = aVersion;
        tables = List.copyOf(aTables);
        codelists = List.copyOf(aCodelists);
        meta = Map.copyOf(aMeta);

        Map<String, IDataTableMetadata> tix = new LinkedHashMap<>();
        for (IDataTableMetadata t : tables)
        {
            tix.put(t.getName().toUpperCase(java.util.Locale.ROOT), t);
        }
        tableIndex = Collections.unmodifiableMap(tix);

        Map<String, ICodeList> cix = new LinkedHashMap<>();
        for (ICodeList c : codelists)
        {
            cix.putIfAbsent(c.getName(), c);
        }
        codelistIndex = Collections.unmodifiableMap(cix);
    }

    // ------------------------------------------------------------------
    // Factories
    // ------------------------------------------------------------------


    /**
     * Builds an SDTM / SDTMIG metadata library.
     *
     * @param aStandardName
     *            the rule-engine standard identifier (e.g. {@code "sdtmig"})
     * @param aStandardVersion
     *            the standard version identifier (e.g. {@code "3-4"})
     * @param aProduct
     *            the pre-fetched {@link SdtmProduct} (should be fetched with {@code expand=true} so
     *            classes and datasets are embedded)
     * @param aCtPackage
     *            the pre-fetched CT package containing the SDTM CT codelists (should be fetched
     *            with {@code expand=true}), paired with the id it was requested under
     */
    public static CdiscLibraryMetadataLibrary fromSdtm(String aStandardName,
            String aStandardVersion, SdtmProduct aProduct, CtPackageRef aCtPackage)
    {
        Objects.requireNonNull(aCtPackage, "ctPackage");
        // Online/enhancer path: PUBLISHED_CT_PACKAGES is just the single requested package. The
        // id comes from the request — CtPackage.name() is the API's display label
        // ("SDTM CT 2024-09-27"), not the id valid_codelist_dates prefix-matches.
        List<String> single = new ArrayList<>();
        if (aCtPackage.id() != null)
        {
            single.add(aCtPackage.id());
        }
        return fromSdtm(aStandardName, aStandardVersion, aProduct, aCtPackage, single);
    }


    /**
     * As {@link #fromSdtm(String, String, SdtmProduct, CtPackageRef)}, but with an explicit list of
     * published CT package names for {@code PUBLISHED_CT_PACKAGES} (J9). The pickle path enumerates
     * the full set from the cache directory ({@code PickleCache.publishedCtPackages()}) so
     * {@code valid_codelist_dates} sees every published date, rather than only the requested one.
     *
     * @param aStandardName
     *            the standard name (e.g. {@code SDTMIG})
     * @param aStandardVersion
     *            the standard version
     * @param aProduct
     *            the SDTM IG product model
     * @param aCtPackage
     *            the requested CT package, paired with its id (the id drives {@code CT_VERSION};
     *            the package drives the codelists)
     * @param aPublishedCtPackages
     *            the published CT package <em>ids</em> for {@code PUBLISHED_CT_PACKAGES}
     * @return the assembled library
     */
    public static CdiscLibraryMetadataLibrary fromSdtm(String aStandardName,
            String aStandardVersion, SdtmProduct aProduct, CtPackageRef aCtPackage,
            List<String> aPublishedCtPackages)
    {
        Objects.requireNonNull(aStandardName, "standardName");
        Objects.requireNonNull(aStandardVersion, "standardVersion");
        Objects.requireNonNull(aProduct, "product");
        Objects.requireNonNull(aCtPackage, "ctPackage");
        Objects.requireNonNull(aPublishedCtPackages, "publishedCtPackages");

        // 1. Build codelists with concept-id → submission-value resolution map.
        List<ICodeList> codelists = buildCodelists(List.of(aCtPackage.pkg()));
        Map<String, String> conceptIdToSubmissionValue = buildConceptIdIndex(codelists);

        // 2. Iterate classes → datasets, building tables.
        List<IDataTableMetadata> tables = new ArrayList<>();
        for (SdtmClass klass : aProduct.classes())
        {
            String className = klass.name().orElse(null);
            List<SdtmVariable> classVariables = sortByOrdinal(klass.classVariables());
            List<String> modelColumnOrder = classVariables.stream().map(v -> v.name().orElse(null))
                    .filter(Objects::nonNull).toList();
            List<Map<String, String>> modelVariables = sdtmModelVariables(classVariables);

            for (SdtmDataset dataset : klass.datasets())
            {
                tables.add(buildSdtmTable(dataset, className, modelColumnOrder, modelVariables,
                        conceptIdToSubmissionValue));
            }
        }

        Map<String, Object> libMeta = new LinkedHashMap<>();
        libMeta.put(MetadataKeys.STANDARD_NAME, aStandardName);
        libMeta.put(MetadataKeys.STANDARD_VERSION, aStandardVersion);
        if (aCtPackage.id() != null)
        {
            libMeta.put(MetadataKeys.CT_VERSION, aCtPackage.id());
        }
        if (!aPublishedCtPackages.isEmpty())
        {
            libMeta.put(MetadataKeys.PUBLISHED_CT_PACKAGES, List.copyOf(aPublishedCtPackages));
        }

        return new CdiscLibraryMetadataLibrary(aStandardName, aStandardVersion, tables, codelists,
                libMeta);
    }


    /**
     * Builds an ADaM / ADaMIG metadata library.
     *
     * @param aStandardName
     *            the rule-engine standard identifier (e.g. {@code "adamig"})
     * @param aStandardVersion
     *            the standard version identifier (e.g. {@code "1-3"})
     * @param aProduct
     *            the pre-fetched {@link AdamProduct}
     * @param aAdamCtPackage
     *            the pre-fetched ADaM CT package, paired with its id
     * @param aSdtmCtPackage
     *            optional SDTM CT package whose codelists are exposed as a fallback for ADaM
     *            variables that reference SDTM-defined terminology (e.g. {@code SEX},
     *            {@code RACE}). Pass {@code null} when not needed.
     */
    public static CdiscLibraryMetadataLibrary fromAdam(String aStandardName,
            String aStandardVersion, AdamProduct aProduct, CtPackageRef aAdamCtPackage,
            @Nullable CtPackageRef aSdtmCtPackage)
    {
        Objects.requireNonNull(aStandardName, "standardName");
        Objects.requireNonNull(aStandardVersion, "standardVersion");
        Objects.requireNonNull(aProduct, "product");
        Objects.requireNonNull(aAdamCtPackage, "adamCtPackage");

        // 1. Merge codelists from ADaM CT (primary) and SDTM CT (fallback).
        List<CtPackage> ctPackages = new ArrayList<>();
        ctPackages.add(aAdamCtPackage.pkg());
        if (aSdtmCtPackage != null)
        {
            ctPackages.add(aSdtmCtPackage.pkg());
        }
        List<ICodeList> codelists = buildCodelists(ctPackages);
        Map<String, String> conceptIdToSubmissionValue = buildConceptIdIndex(codelists);

        // 2. Iterate data structures → variable sets → variables.
        List<IDataTableMetadata> tables = new ArrayList<>();
        for (AdamDataStructure ds : aProduct.dataStructures())
        {
            String className = ds.className().orElse(null);

            List<AdamVariable> flattened = new ArrayList<>();
            for (AdamVariableSet set : ds.analysisVariableSets())
            {
                flattened.addAll(set.analysisVariables());
            }
            List<AdamVariable> ordered = sortAdamByOrdinal(flattened);
            List<String> modelColumnOrder = ordered.stream().map(v -> v.name().orElse(null))
                    .filter(Objects::nonNull).toList();
            List<Map<String, String>> modelVariables = adamModelVariables(ordered);

            tables.add(buildAdamTable(ds, className, ordered, modelColumnOrder, modelVariables,
                    conceptIdToSubmissionValue));
        }

        Map<String, Object> libMeta = new LinkedHashMap<>();
        libMeta.put(MetadataKeys.STANDARD_NAME, aStandardName);
        libMeta.put(MetadataKeys.STANDARD_VERSION, aStandardVersion);
        if (aAdamCtPackage.id() != null)
        {
            libMeta.put(MetadataKeys.CT_VERSION, aAdamCtPackage.id());
        }
        List<String> publishedCtPackages = new ArrayList<>();
        if (aAdamCtPackage.id() != null)
        {
            publishedCtPackages.add(aAdamCtPackage.id());
        }
        if (aSdtmCtPackage != null && aSdtmCtPackage.id() != null)
        {
            publishedCtPackages.add(aSdtmCtPackage.id());
        }
        if (!publishedCtPackages.isEmpty())
        {
            libMeta.put(MetadataKeys.PUBLISHED_CT_PACKAGES, List.copyOf(publishedCtPackages));
        }

        return new CdiscLibraryMetadataLibrary(aStandardName, aStandardVersion, tables, codelists,
                libMeta);
    }

    // ------------------------------------------------------------------
    // IMetadataLibrary implementation
    // ------------------------------------------------------------------


    @Override
    public String getName()
    {
        return name;
    }


    @Override
    public String getVersion()
    {
        return version;
    }


    @Override
    public boolean isColumnNameCaseSensitive()
    {
        return false;
    }


    @Override
    public List<IDataTableMetadata> getDataTables()
    {
        return tables;
    }


    @Override
    public Optional<IDataTableMetadata> getDataTable(String aName)
    {
        if (aName == null)
        {
            return Optional.empty();
        }
        return Optional.ofNullable(tableIndex.get(aName.toUpperCase(java.util.Locale.ROOT)));
    }


    @Override
    public List<ICodeList> getCodelists()
    {
        return codelists;
    }


    @Override
    public Optional<ICodeList> getCodelist(String aName)
    {
        if (aName == null)
        {
            return Optional.empty();
        }
        return Optional.ofNullable(codelistIndex.get(aName));
    }


    @Override
    public Set<String> getMetaKeys()
    {
        return Collections.unmodifiableSet(meta.keySet());
    }


    @Override
    public Optional<Object> getMetaValue(String aKey)
    {
        return Optional.ofNullable(meta.get(aKey));
    }

    // ------------------------------------------------------------------
    // SDTM builders
    // ------------------------------------------------------------------


    private static IDataTableMetadata buildSdtmTable(SdtmDataset aDataset,
            @Nullable String aClassName, List<String> aModelColumnOrder,
            List<Map<String, String>> aModelVariables,
            Map<String, String> aConceptIdToSubmissionValue)
    {
        String name = aDataset.name().orElse("");
        String label = aDataset.label().orElse(null);
        String structure = aDataset.datasetStructure().orElse(null);

        List<SdtmVariable> ordered = sortByOrdinal(aDataset.datasetVariables());
        List<IColumnMetadata> columns = new ArrayList<>(ordered.size());
        int idx = 0;
        for (SdtmVariable v : ordered)
        {
            columns.add(buildColumn(new ColumnSpec(v.name().orElse(""), v.label().orElse(null),
                    v.simpleDatatype().orElse(null), v.core().orElse(null), v.role().orElse(null),
                    v.codelistLink().flatMap(Link::id).orElse(null), idx++),
                    aConceptIdToSubmissionValue));
        }

        Map<String, Object> tableMeta = new LinkedHashMap<>();
        tableMeta.put(MetadataKeys.IS_CUSTOM_DOMAIN, false);
        if (aClassName != null)
        {
            tableMeta.put(MetadataKeys.CLASS_NAME, aClassName);
        }
        if (structure != null)
        {
            tableMeta.put(MetadataKeys.DATASET_STRUCTURE, structure);
        }
        if (!aModelColumnOrder.isEmpty())
        {
            tableMeta.put(MetadataKeys.MODEL_COLUMN_ORDER, aModelColumnOrder);
        }
        if (!aModelVariables.isEmpty())
        {
            tableMeta.put(MetadataKeys.MODEL_VARIABLES, aModelVariables);
        }

        return new CdiscTableMetadata(name, label, aClassName, structure, columns, tableMeta);
    }


    /**
     * Builds the per-class Model variable maps (name, label, role, core, ordinal, simpleDatatype)
     * used by {@code get_model_filtered_variables} (Fix #3). Mirrors the column-map shape produced
     * by {@code MetadataLibraryProvider.columnToMap}.
     */
    private static List<Map<String, String>> sdtmModelVariables(List<SdtmVariable> aClassVariables)
    {
        List<Map<String, String>> out = new ArrayList<>(aClassVariables.size());
        int idx = 0;
        for (SdtmVariable v : aClassVariables)
        {
            Map<String, String> m = new LinkedHashMap<>();
            v.name().ifPresent(s -> m.put("name", s));
            v.label().ifPresent(s -> m.put("label", s));
            v.simpleDatatype().ifPresent(s -> m.put("simpleDatatype", s));
            v.core().ifPresent(s -> m.put("core", s));
            v.role().ifPresent(s -> m.put("role", s));
            m.put("ordinal", Integer.toString(idx++));
            out.add(Collections.unmodifiableMap(m));
        }
        return Collections.unmodifiableList(out);
    }


    private static List<SdtmVariable> sortByOrdinal(List<SdtmVariable> aVariables)
    {
        List<SdtmVariable> copy = new ArrayList<>(aVariables);
        copy.sort(Comparator.comparingInt(v -> parseOrdinal(v.ordinal().orElse(null))));
        return copy;
    }

    // ------------------------------------------------------------------
    // ADaM builders
    // ------------------------------------------------------------------


    private static IDataTableMetadata buildAdamTable(AdamDataStructure aDs,
            @Nullable String aClassName, List<AdamVariable> aOrderedVariables,
            List<String> aModelColumnOrder, List<Map<String, String>> aModelVariables,
            Map<String, String> aConceptIdToSubmissionValue)
    {
        String name = aDs.name().orElse("");
        String label = aDs.label().orElse(null);

        List<IColumnMetadata> columns = new ArrayList<>(aOrderedVariables.size());
        int idx = 0;
        for (AdamVariable v : aOrderedVariables)
        {
            columns.add(buildColumn(
                    new ColumnSpec(v.name().orElse(""), v.label().orElse(null),
                            v.simpleDatatype().orElse(null), v.core().orElse(null), /* role */ null,
                            v.codelistLink().flatMap(Link::id).orElse(null), idx++),
                    aConceptIdToSubmissionValue));
        }

        Map<String, Object> tableMeta = new LinkedHashMap<>();
        tableMeta.put(MetadataKeys.IS_CUSTOM_DOMAIN, false);
        if (aClassName != null)
        {
            tableMeta.put(MetadataKeys.CLASS_NAME, aClassName);
        }
        if (!aModelColumnOrder.isEmpty())
        {
            tableMeta.put(MetadataKeys.MODEL_COLUMN_ORDER, aModelColumnOrder);
        }
        if (!aModelVariables.isEmpty())
        {
            tableMeta.put(MetadataKeys.MODEL_VARIABLES, aModelVariables);
        }

        // ADaM datasets don't carry a datasetStructure field like SDTM.
        return new CdiscTableMetadata(name, label, aClassName, /* structure */ null, columns,
                tableMeta);
    }


    private static List<Map<String, String>> adamModelVariables(List<AdamVariable> aVariables)
    {
        List<Map<String, String>> out = new ArrayList<>(aVariables.size());
        int idx = 0;
        for (AdamVariable v : aVariables)
        {
            Map<String, String> m = new LinkedHashMap<>();
            v.name().ifPresent(s -> m.put("name", s));
            v.label().ifPresent(s -> m.put("label", s));
            v.simpleDatatype().ifPresent(s -> m.put("simpleDatatype", s));
            v.core().ifPresent(s -> m.put("core", s));
            m.put("ordinal", Integer.toString(idx++));
            out.add(Collections.unmodifiableMap(m));
        }
        return Collections.unmodifiableList(out);
    }


    private static List<AdamVariable> sortAdamByOrdinal(List<AdamVariable> aVariables)
    {
        List<AdamVariable> copy = new ArrayList<>(aVariables);
        copy.sort(Comparator.comparingInt(v -> parseOrdinal(v.ordinal().orElse(null))));
        return copy;
    }

    // ------------------------------------------------------------------
    // Column / codelist shared helpers
    // ------------------------------------------------------------------

    /**
     * Snapshot of the seven column properties from upstream (SdtmVariable / AdamVariable) needed to
     * build a {@link CdiscColumnMetadata}. Bundled to keep the {@link #buildColumn} signature
     * manageable.
     */
    private record ColumnSpec(String name, @Nullable String label, @Nullable String simpleDatatype,
            @Nullable String core, @Nullable String role, @Nullable String codelistConceptId,
            int index)
    {
    }

    private static IColumnMetadata buildColumn(ColumnSpec spec,
            Map<String, String> aConceptIdToSubmissionValue)
    {
        String codelistRef = null;
        if (spec.codelistConceptId() != null && !spec.codelistConceptId().isEmpty())
        {
            // Resolve concept id → submission value when possible, otherwise keep raw id.
            codelistRef = aConceptIdToSubmissionValue.getOrDefault(spec.codelistConceptId(),
                    spec.codelistConceptId());
        }
        return new CdiscColumnMetadata(spec.name(), spec.label(), spec.index(),
                mapSimpleDatatype(spec.simpleDatatype()), spec.core(), spec.role(), codelistRef);
    }


    private static List<ICodeList> buildCodelists(List<CtPackage> aPackages)
    {
        // Primary-wins merge: the first package's codelist for a given submission
        // value takes precedence over later packages.
        Map<String, ICodeList> seen = new LinkedHashMap<>();
        for (CtPackage pkg : aPackages)
        {
            for (CtCodelist cl : pkg.codelists())
            {
                String submissionValue = cl.submissionValue().orElse(null);
                if (submissionValue == null || submissionValue.isEmpty())
                {
                    continue;
                }
                seen.putIfAbsent(submissionValue, buildCodelist(cl));
            }
        }
        return List.copyOf(seen.values());
    }


    private static ICodeList buildCodelist(CtCodelist aCodelist)
    {
        String submissionValue = aCodelist.submissionValue().orElse(null);
        String conceptId = aCodelist.conceptId().orElse(null);
        Boolean extensible = aCodelist.extensible().orElse(null);

        List<ICodelistEntry> entries = new ArrayList<>();
        for (CtTerm term : aCodelist.terms())
        {
            String code = term.submissionValue().orElse(null);
            if (code == null)
            {
                continue;
            }
            String decode = term.preferredTerm().orElse("");
            entries.add(new CdiscCodelistEntry(code, decode, term.conceptId().orElse(null)));
        }

        Map<String, Object> meta = new LinkedHashMap<>();
        if (conceptId != null)
        {
            meta.put(MetadataKeys.CODELIST_CONCEPT_ID, conceptId);
        }
        if (submissionValue != null)
        {
            meta.put(MetadataKeys.CODELIST_SUBMISSION_VALUE, submissionValue);
        }

        // Codelist name is the submission value, which the buildCodelists loop guarantees is
        // non-null/non-empty before this codelist is materialised.
        return new CdiscCodelist(
                Objects.requireNonNull(submissionValue, "codelist submission value"), extensible,
                entries, meta);
    }


    private static Map<String, String> buildConceptIdIndex(List<ICodeList> aCodelists)
    {
        Map<String, String> map = new HashMap<>();
        for (ICodeList cl : aCodelists)
        {
            Object conceptId = cl.getMetaValue(MetadataKeys.CODELIST_CONCEPT_ID).orElse(null);
            if (conceptId != null)
            {
                map.putIfAbsent(conceptId.toString(), cl.getName());
            }
        }
        return map;
    }


    private static DataValueType mapSimpleDatatype(@Nullable String aSimpleDatatype)
    {
        if (aSimpleDatatype == null)
        {
            return DataValueType.OTHER;
        }
        return switch (aSimpleDatatype)
        {
        case "Char", "text", "String" -> DataValueType.STRING;
        case "Num", "integer", "Integer" -> DataValueType.DOUBLE;
        case "float", "Float", "double", "Double" -> DataValueType.DOUBLE;
        case "boolean", "Boolean" -> DataValueType.BOOLEAN;
        default -> DataValueType.OTHER;
        };
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
    // Immutable backing records
    // ------------------------------------------------------------------

    private record CdiscTableMetadata(String name, @Nullable String label,
            @Nullable String className, @Nullable String structure, List<IColumnMetadata> columns,
            Map<String, Object> meta) implements IDataTableMetadata
    {

        @Override
        public String getName()
        {
            return name;
        }


        @Override
        public @Nullable String getLabel()
        {
            return label;
        }


        @Override
        public @Nullable URI getTableURI()
        {
            return null;
        }


        @Override
        public List<IColumnMetadata> getColumns()
        {
            // Unmodifiable: same reason as getEntries below (SpotBugs EI_EXPOSE_REP).
            return Collections.unmodifiableList(columns);
        }


        @Override
        public Optional<IColumnMetadata> getColumn(String aName)
        {
            if (aName == null)
            {
                return Optional.empty();
            }
            for (IColumnMetadata c : columns)
            {
                if (c.getName().equalsIgnoreCase(aName))
                {
                    return Optional.of(c);
                }
            }
            return Optional.empty();
        }


        @Override
        public @Nullable String getClassName()
        {
            return className;
        }


        @Override
        public @Nullable String getStructure()
        {
            return structure;
        }


        @Override
        public Set<String> getMetaKeys()
        {
            return Collections.unmodifiableSet(meta.keySet());
        }


        @Override
        public Optional<Object> getMetaValue(String aKey)
        {
            return Optional.ofNullable(meta.get(aKey));
        }
    }


    private record CdiscColumnMetadata(String name, @Nullable String label, int index,
            DataValueType type, @Nullable String core, @Nullable String role,
            @Nullable String codelist) implements IColumnMetadata
    {

        @Override
        public String getName()
        {
            return name;
        }


        @Override
        public @Nullable String getLabel()
        {
            return label;
        }


        @Override
        public @Nullable String getDisplayFormat()
        {
            return null;
        }


        @Override
        public int getIndex()
        {
            return index;
        }


        @Override
        public DataValueType getType()
        {
            return type;
        }


        @Override
        public int getLength()
        {
            return 0;
        }


        @Override
        public @Nullable String getNativeType()
        {
            // IColumnMetadata#getNativeType is @Nullable in this module's datatable, so the
            // pre-existing null return ("no source-native type") is preserved (no behavioural
            // change).
            return null;
        }


        @Override
        public int getKeySequence()
        {
            return 0;
        }


        @Override
        public boolean isByGroup()
        {
            return false;
        }


        @Override
        public @Nullable String getCore()
        {
            return core;
        }


        @Override
        public @Nullable String getRole()
        {
            return role;
        }


        @Override
        public @Nullable String getCodelist()
        {
            return codelist;
        }


        @Override
        public Set<String> getMetaKeys()
        {
            return Set.of();
        }


        @Override
        public Optional<Object> getMetaValue(String aKey)
        {
            return Optional.empty();
        }
    }


    private record CdiscCodelist(String name, @Nullable Boolean extensible,
            List<ICodelistEntry> entries, Map<String, Object> meta) implements ICodeList
    {

        @Override
        public String getName()
        {
            return name;
        }


        @Override
        public DataValueType getValueType()
        {
            return DataValueType.STRING;
        }


        @Override
        public List<ICodelistEntry> getEntries()
        {
            // Unmodifiable: the record is handed a plain ArrayList, so returning it directly let
            // a caller mutate the library's codelist (SpotBugs EI_EXPOSE_REP).
            return Collections.unmodifiableList(entries);
        }


        @Override
        public @Nullable Boolean isExtensible()
        {
            return extensible;
        }


        @Override
        public Set<String> getMetaKeys()
        {
            return Collections.unmodifiableSet(meta.keySet());
        }


        @Override
        public Optional<Object> getMetaValue(String aKey)
        {
            return Optional.ofNullable(meta.get(aKey));
        }
    }


    private record CdiscCodelistEntry(String code, String decode,
            @Nullable String conceptId) implements ICodelistEntry
    {

        @Override
        public String getCodeValue()
        {
            return code;
        }


        @Override
        public String getDecodeValue()
        {
            return decode;
        }


        @Override
        public @Nullable String getConceptId()
        {
            return conceptId;
        }


        @Override
        public Set<String> getMetaKeys()
        {
            return Set.of();
        }


        @Override
        public Optional<Object> getMetaValue(String aKey)
        {
            return Optional.empty();
        }
    }

}
