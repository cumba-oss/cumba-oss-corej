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

import net.cumba.corej.core.metadata.store.StoredCodelist;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredTerm;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import net.cumba.datatable.metadata.ICodeList;
import net.cumba.datatable.metadata.ICodelistEntry;
import net.cumba.datatable.metadata.IColumnMetadata;
import net.cumba.datatable.metadata.IDataTableMetadata;
import net.cumba.datatable.metadata.IMetadataLibrary;
import net.cumba.datatable.values.DataValueType;
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
 * <li>{@link #fromStoredSdtm(String, String, StoredProduct, List, List)} — SDTM / SDTMIG</li>
 * <li>{@link #fromStoredAdam(String, String, StoredProduct, List, List, List)} — ADaM / ADaMIG,
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
     * The SDTM-family factory — cache plan §4.3.1: the library is built from the unified store's
     * own records. (The api-model {@code fromSdtm} overloads that projected onto this method were
     * deleted with the pickle read path, cache 8g.)
     *
     * @param aStandardName
     *            the standard name (e.g. {@code sdtmig})
     * @param aStandardVersion
     *            the standard version (e.g. {@code 3-4})
     * @param aProduct
     *            the stored IG product
     * @param aCtPackages
     *            the requested CT packages in <b>precedence order</b> (newest first); must be
     *            non-empty. Each package's own {@link StoredCtPackage#id()} is the id it was
     *            requested under ({@code null} for the anonymous no-CT package).
     * @param aPublishedCtPackages
     *            the published CT package ids for {@code PUBLISHED_CT_PACKAGES}. ⚠ On the
     *            store-backed path this MUST be the store's whole published enumeration
     *            ({@code MetadataStore.publishedCtPackages()}), never the requested ids — deriving
     *            it from what a run loaded is the live over-fire defect the store exists to fix
     *            (plan §1.1-1).
     * @return the assembled library
     */
    public static CdiscLibraryMetadataLibrary fromStoredSdtm(String aStandardName,
            String aStandardVersion, StoredProduct aProduct, List<StoredCtPackage> aCtPackages,
            List<String> aPublishedCtPackages)
    {
        Objects.requireNonNull(aStandardName, "standardName");
        Objects.requireNonNull(aStandardVersion, "standardVersion");
        Objects.requireNonNull(aProduct, "product");
        Objects.requireNonNull(aCtPackages, "ctPackages");
        Objects.requireNonNull(aPublishedCtPackages, "publishedCtPackages");
        if (aCtPackages.isEmpty())
        {
            throw new IllegalArgumentException(
                    "ctPackages must be non-empty — the no-CT path constructs "
                            + "MetadataLibraryProvider directly");
        }

        // 1. Build codelists with concept-id → submission-value resolution map. The precedence
        // order of aCtPackages is load-bearing: buildCodelists collapses the packages into one
        // map keyed by submission value with putIfAbsent, so the FIRST package carrying a
        // codelist wins (define-ct plan §4.3 — merge within a publishing set, order across).
        List<ICodeList> codelists = buildCodelists(aCtPackages);
        Map<String, String> conceptIdToSubmissionValue = buildConceptIdIndex(codelists);

        // 2. Iterate classes → datasets, building tables.
        List<IDataTableMetadata> tables = new ArrayList<>();
        for (net.cumba.corej.core.metadata.store.StoredClass klass : aProduct.classes())
        {
            String className = klass.name();
            List<StoredVariable> classVariables = sortByOrdinal(klass.classVariables());
            List<String> modelColumnOrder = classVariables.stream().map(StoredVariable::name)
                    .filter(Objects::nonNull).toList();
            List<Map<String, String>> modelVariables = sdtmModelVariables(classVariables);

            for (StoredDataset dataset : klass.datasets())
            {
                tables.add(buildSdtmTable(dataset, className, modelColumnOrder, modelVariables,
                        conceptIdToSubmissionValue));
            }
        }

        Map<String, Object> libMeta = new LinkedHashMap<>();
        libMeta.put(MetadataKeys.STANDARD_NAME, aStandardName);
        libMeta.put(MetadataKeys.STANDARD_VERSION, aStandardVersion);
        List<String> ctVersions = ctVersionPerRoot(aCtPackages);
        if (!ctVersions.isEmpty())
        {
            libMeta.put(MetadataKeys.CT_VERSION, ctVersions);
        }
        if (!aPublishedCtPackages.isEmpty())
        {
            libMeta.put(MetadataKeys.PUBLISHED_CT_PACKAGES, List.copyOf(aPublishedCtPackages));
        }

        return new CdiscLibraryMetadataLibrary(aStandardName, aStandardVersion, tables, codelists,
                libMeta);
    }


    /**
     * The ADaM-family factory — see {@link #fromStoredSdtm} for the store seam and the
     * {@code aPublishedCtPackages} contract (on the store-backed path: the store's whole published
     * enumeration, never the requested ids).
     *
     * <p>
     * ⛔ ADaM-first ordering is the whole cross-set precedence mechanism: the two package lists are
     * concatenated ADaM-then-SDTM and collapsed by {@code buildCodelists}' submission-value
     * {@code putIfAbsent} — there is no per-root slot in the resulting library, deliberately.
     * Within each list the caller's order is the precedence order (newest first).
     * </p>
     *
     * @param aStandardName
     *            the standard name (e.g. {@code adamig})
     * @param aStandardVersion
     *            the standard version (e.g. {@code 1-3})
     * @param aProduct
     *            the stored ADaM product
     * @param aAdamCtPackages
     *            the ADaM CT packages in precedence order; must be non-empty
     * @param aSdtmCtPackages
     *            SDTM CT packages exposed as a fallback for ADaM variables referencing SDTM-defined
     *            terminology (e.g. {@code SEX}, {@code RACE}); may be empty
     * @param aPublishedCtPackages
     *            the published CT package ids for {@code PUBLISHED_CT_PACKAGES}
     * @return the assembled library
     */
    public static CdiscLibraryMetadataLibrary fromStoredAdam(String aStandardName,
            String aStandardVersion, StoredProduct aProduct, List<StoredCtPackage> aAdamCtPackages,
            List<StoredCtPackage> aSdtmCtPackages, List<String> aPublishedCtPackages)
    {
        Objects.requireNonNull(aStandardName, "standardName");
        Objects.requireNonNull(aStandardVersion, "standardVersion");
        Objects.requireNonNull(aProduct, "product");
        Objects.requireNonNull(aAdamCtPackages, "adamCtPackages");
        Objects.requireNonNull(aSdtmCtPackages, "sdtmCtPackages");
        Objects.requireNonNull(aPublishedCtPackages, "publishedCtPackages");
        if (aAdamCtPackages.isEmpty())
        {
            throw new IllegalArgumentException(
                    "adamCtPackages must be non-empty — the no-CT path constructs "
                            + "MetadataLibraryProvider directly");
        }

        // 1. Merge codelists from ADaM CT (primary) and SDTM CT (fallback), each set internally
        // in precedence order.
        List<StoredCtPackage> orderedCts = new ArrayList<>(aAdamCtPackages);
        orderedCts.addAll(aSdtmCtPackages);
        List<ICodeList> codelists = buildCodelists(orderedCts);
        Map<String, String> conceptIdToSubmissionValue = buildConceptIdIndex(codelists);

        // 2. Iterate data structures → variable sets → variables.
        List<IDataTableMetadata> tables = new ArrayList<>();
        for (StoredDataStructure ds : aProduct.dataStructures())
        {
            String className = ds.className();

            List<StoredVariable> flattened = new ArrayList<>();
            for (StoredVariableSet set : ds.variableSets())
            {
                flattened.addAll(set.variables());
            }
            List<StoredVariable> ordered = sortByOrdinal(flattened);
            List<String> modelColumnOrder = ordered.stream().map(StoredVariable::name)
                    .filter(Objects::nonNull).toList();
            List<Map<String, String>> modelVariables = adamModelVariables(ordered);

            tables.add(buildAdamTable(ds, className, ordered, modelColumnOrder, modelVariables,
                    conceptIdToSubmissionValue));
        }

        Map<String, Object> libMeta = new LinkedHashMap<>();
        libMeta.put(MetadataKeys.STANDARD_NAME, aStandardName);
        libMeta.put(MetadataKeys.STANDARD_VERSION, aStandardVersion);
        List<String> ctVersions = ctVersionPerRoot(orderedCts);
        if (!ctVersions.isEmpty())
        {
            libMeta.put(MetadataKeys.CT_VERSION, ctVersions);
        }
        if (!aPublishedCtPackages.isEmpty())
        {
            libMeta.put(MetadataKeys.PUBLISHED_CT_PACKAGES, List.copyOf(aPublishedCtPackages));
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


    private static IDataTableMetadata buildSdtmTable(StoredDataset aDataset,
            @Nullable String aClassName, List<String> aModelColumnOrder,
            List<Map<String, String>> aModelVariables,
            Map<String, String> aConceptIdToSubmissionValue)
    {
        String name = aDataset.name() == null ? "" : aDataset.name();
        String label = aDataset.label();
        String structure = aDataset.datasetStructure();

        List<StoredVariable> ordered = sortByOrdinal(aDataset.variables());
        List<IColumnMetadata> columns = new ArrayList<>(ordered.size());
        int idx = 0;
        for (StoredVariable v : ordered)
        {
            columns.add(buildColumn(
                    new ColumnSpec(v.name() == null ? "" : v.name(), v.label(), v.simpleDatatype(),
                            v.core(), v.role(), firstCodelistId(v), idx++),
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
    private static List<Map<String, String>> sdtmModelVariables(
            List<StoredVariable> aClassVariables)
    {
        List<Map<String, String>> out = new ArrayList<>(aClassVariables.size());
        int idx = 0;
        for (StoredVariable v : aClassVariables)
        {
            Map<String, String> m = new LinkedHashMap<>();
            putIfPresent(m, "name", v.name());
            putIfPresent(m, "label", v.label());
            putIfPresent(m, "simpleDatatype", v.simpleDatatype());
            putIfPresent(m, "core", v.core());
            putIfPresent(m, "role", v.role());
            m.put("ordinal", Integer.toString(idx++));
            out.add(Collections.unmodifiableMap(m));
        }
        return Collections.unmodifiableList(out);
    }


    private static List<StoredVariable> sortByOrdinal(List<StoredVariable> aVariables)
    {
        List<StoredVariable> copy = new ArrayList<>(aVariables);
        copy.sort(Comparator.comparingInt(v -> parseOrdinal(v.ordinal())));
        return copy;
    }


    private static void putIfPresent(Map<String, String> aMap, String aKey, @Nullable String aValue)
    {
        if (aValue != null)
        {
            aMap.put(aKey, aValue);
        }
    }


    /**
     * The variable's first codelist ref — identical to the api-model path's
     * {@code codelistLink().flatMap(Link::id)}, whose {@code getLink} contract returns the first
     * entry of a link array. ⚠ 31 real variables carry 2–5 refs; consuming beyond the first is a
     * deliberate non-goal of P3 (behaviour preservation) and a known follow-up.
     */
    private static @Nullable String firstCodelistId(StoredVariable aVariable)
    {
        List<String> ids = aVariable.codelistIds();
        return ids == null || ids.isEmpty() ? null : ids.get(0);
    }

    // ------------------------------------------------------------------
    // ADaM builders
    // ------------------------------------------------------------------


    private static IDataTableMetadata buildAdamTable(StoredDataStructure aDs,
            @Nullable String aClassName, List<StoredVariable> aOrderedVariables,
            List<String> aModelColumnOrder, List<Map<String, String>> aModelVariables,
            Map<String, String> aConceptIdToSubmissionValue)
    {
        String name = aDs.name() == null ? "" : aDs.name();
        String label = aDs.label();

        List<IColumnMetadata> columns = new ArrayList<>(aOrderedVariables.size());
        int idx = 0;
        for (StoredVariable v : aOrderedVariables)
        {
            columns.add(buildColumn(
                    new ColumnSpec(v.name() == null ? "" : v.name(), v.label(), v.simpleDatatype(),
                            v.core(), /* role */ null, firstCodelistId(v), idx++),
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


    private static List<Map<String, String>> adamModelVariables(List<StoredVariable> aVariables)
    {
        List<Map<String, String>> out = new ArrayList<>(aVariables.size());
        int idx = 0;
        for (StoredVariable v : aVariables)
        {
            Map<String, String> m = new LinkedHashMap<>();
            putIfPresent(m, "name", v.name());
            putIfPresent(m, "label", v.label());
            putIfPresent(m, "simpleDatatype", v.simpleDatatype());
            putIfPresent(m, "core", v.core());
            // No "role": the ADaM sources publish none. Even if a stored ADaM variable carried
            // one, emitting it here would silently widen what the deleted api-model path
            // answered — keep the shape stable.
            m.put("ordinal", Integer.toString(idx++));
            out.add(Collections.unmodifiableMap(m));
        }
        return Collections.unmodifiableList(out);
    }

    // ------------------------------------------------------------------
    // Column / codelist shared helpers
    // ------------------------------------------------------------------

    /**
     * Snapshot of the seven column properties from upstream ({@link StoredVariable}) needed to
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


    /**
     * {@code CT_VERSION}'s value (define-ct plan §4.3): the precedence-winning package id per CT
     * <b>root</b> ({@code sdtmct} / {@code adamct} / {@code sendct} …), in first-seen order —
     * ADaM-first on {@code fromAdam} by construction. The winner is the first id of its root in
     * {@code aRefs}, because the same order decided the codelist merge. Anonymous packages
     * ({@code id == null}) contribute nothing, matching the pre-merge behaviour of leaving
     * {@code CtVersion} unset rather than inventing a value.
     */
    private static List<String> ctVersionPerRoot(List<StoredCtPackage> aPackages)
    {
        Map<String, String> winnerPerRoot = new LinkedHashMap<>();
        for (StoredCtPackage pkg : aPackages)
        {
            String id = pkg.id();
            if (id == null)
            {
                continue;
            }
            int i = id.indexOf("ct-");
            String root = i >= 0 ? id.substring(0, i + 2) : id;
            winnerPerRoot.putIfAbsent(root, id);
        }
        return List.copyOf(winnerPerRoot.values());
    }


    private static List<ICodeList> buildCodelists(List<StoredCtPackage> aPackages)
    {
        // Primary-wins merge: the first package's codelist for a given submission
        // value takes precedence over later packages.
        Map<String, ICodeList> seen = new LinkedHashMap<>();
        for (StoredCtPackage pkg : aPackages)
        {
            for (StoredCodelist cl : pkg.codelists())
            {
                String submissionValue = cl.submissionValue();
                if (submissionValue == null || submissionValue.isEmpty())
                {
                    continue;
                }
                seen.putIfAbsent(submissionValue, buildCodelist(cl));
            }
        }
        return List.copyOf(seen.values());
    }


    /**
     * Builds the engine-facing {@link ICodeList} from one stored codelist version — the P3 seam of
     * PLAN-codelist-terms-returntype.md §4.1.1: same target type as before, different source. The
     * stored {@code definition}/{@code synonyms} (term and codelist level) are deliberately NOT
     * projected — no accessor and no meta-key exists for them (cache plan §4.3.1), which is the
     * future-proofing the §3.2 ruling bought.
     */
    private static ICodeList buildCodelist(StoredCodelist aCodelist)
    {
        String submissionValue = aCodelist.submissionValue();
        String conceptId = aCodelist.conceptId();
        Boolean extensible = aCodelist.extensible();

        List<ICodelistEntry> entries = new ArrayList<>();
        for (StoredTerm term : aCodelist.terms())
        {
            String code = term.submissionValue();
            if (code == null)
            {
                continue;
            }
            String decode = term.preferredTerm() == null ? "" : term.preferredTerm();
            entries.add(new CdiscCodelistEntry(code, decode, term.conceptId()));
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
        String preferredTerm = aCodelist.preferredTerm();
        if (preferredTerm != null)
        {
            meta.put(MetadataKeys.CODELIST_PREFERRED_TERM, preferredTerm);
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
