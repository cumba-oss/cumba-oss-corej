package net.cumba.corej.ruletest.cdt.ruletest;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;

/**
 * Test-only {@link MetadataProvider} backed by in-memory maps. Supports every operator the rule
 * engine routes through {@code OperationExecutor.evalLibrary} and its specialised relatives
 * (dataset-filtered variables, model-filtered variables, valid codelist dates). Every lookup
 * returns a safe empty default when the builder hasn't been told about the domain or codelist, so
 * tests only declare the fragments of the Library their rule actually reads.
 *
 * <p>
 * Domain / codelist keys are normalised to upper case, matching the engine's own case-folding in
 * {@code OperationExecutor}.
 * </p>
 *
 * <p>
 * Typical use inside a test:
 * </p>
 *
 * <pre>{@code
 *
 * MetadataProvider lib = MapBackedLibraryMetadataProvider.builder().standard("adamig")
 *         .version("1-3").datasetClass("ADLBC", "BDS")
 *         .requiredVariables("DM", "STUDYID", "USUBJID", "SUBJID")
 *         .codelistTerms("C66731", "Y", "N").build();
 * }</pre>
 */
public final class MapBackedLibraryMetadataProvider implements MetadataProvider
{

    private final String standard;

    private final String version;

    private final Map<String, List<String>> requiredVariables;

    private final Map<String, List<String>> expectedVariables;

    /**
     * Fix #368: Required variables keyed by ADaM <b>data structure</b>, not by domain.
     *
     * <p>
     * ⚠ Its presence is what makes {@link #supportsStructureKeyedVariables()} true, and that is
     * deliberate: a scenario that declares only the domain-keyed {@code required-variables} keeps
     * exercising the SDTM path, so an ADaM scenario written the old way cannot silently look like
     * it covers the structure-keyed one. The three {@code PMDA-AD0047} fixtures did exactly that
     * before Fix #368 — they hand-substituted {@code TRT01P} for the published {@code TRTxxP} and
     * keyed by {@code ADSL}, the one name where domain and structure coincide, so they passed while
     * the shipped rule was both vacuous and false-firing.
     * </p>
     */
    private final Map<String, List<String>> requiredVariablesForStructure;

    private final Map<String, List<String>> columnOrder;

    private final Map<String, List<String>> modelColumnOrder;

    private final Set<String> customDomains;

    private final Map<String, List<String>> codelistTerms;

    private final Map<String, Map<String, Map<String, String>>> variableMetadata;

    /**
     * CT2003 — per (domain, variable) submission value → NCI concept id ("C-code") maps, the input
     * of {@link MetadataProvider#getCodelistCodeMap}. Same three-level shape as
     * {@link #variableMetadata}: domain and variable keys are upper-cased, the innermost keys (the
     * codelist terms' submission values, e.g. {@code "Albumin"}) are stored <b>verbatim</b> because
     * the accessor looks them up with the cell value exactly as submitted.
     */
    private final Map<String, Map<String, Map<String, String>>> codelistCodes;

    private final Map<String, List<Map<String, String>>> domainVariables;

    private final Map<String, List<Map<String, String>>> modelVariables;

    /**
     * EC-85 — per observation <b>class</b> model-variable lists ({@code #library
     * model-class-variables CLASS NAME:ROLE ...}), the input of
     * {@link MetadataProvider#getModelVariablesForClass}. Class-keyed where {@link #modelVariables}
     * is domain-keyed; names are served unsubstituted ({@code --TERM}) exactly like
     * {@code model-variables}, and the executor's fallback path substitutes the dataset's prefix.
     */
    private final Map<String, List<Map<String, String>>> modelClassVariables;

    private final Map<String, Map<String, String>> datasetMetadata;

    private final Map<String, Boolean> codelistExtensible;

    private final Map<String, Map<String, String>> codelistTermMappings;

    private final List<String> publishedCtPackages;

    /**
     * Fix #147 — the library's canonical dataset (domain) names, or {@code null} when the scenario
     * declares none. {@code null} rather than empty is load-bearing: the {@code known_domain_only}
     * expansion filter treats "the library serves no list" as undecidable and skips with a reason,
     * where an empty list would mean "no domain is known" and silently filter everything out.
     */
    private final @Nullable List<String> standardDatasetNames;

    private MapBackedLibraryMetadataProvider(Builder b)
    {
        this.standard = b.standard;
        this.version = b.version;
        this.requiredVariables = Map.copyOf(b.requiredVariables);
        this.expectedVariables = Map.copyOf(b.expectedVariables);
        this.requiredVariablesForStructure = Map.copyOf(b.requiredVariablesForStructure);
        this.columnOrder = Map.copyOf(b.columnOrder);
        this.modelColumnOrder = Map.copyOf(b.modelColumnOrder);
        this.customDomains = Set.copyOf(b.customDomains);
        this.codelistTerms = Map.copyOf(b.codelistTerms);
        this.variableMetadata = deepCopy(b.variableMetadata);
        this.codelistCodes = deepCopy(b.codelistCodes);
        this.domainVariables = deepCopyList(b.domainVariables);
        this.modelVariables = deepCopyList(b.modelVariables);
        this.modelClassVariables = deepCopyList(b.modelClassVariables);
        this.datasetMetadata = deepCopyLeaf(b.datasetMetadata);
        this.codelistExtensible = Map.copyOf(b.codelistExtensible);
        this.codelistTermMappings = deepCopyLeaf(b.codelistTermMappings);
        this.publishedCtPackages = List.copyOf(b.publishedCtPackages);
        this.standardDatasetNames = b.standardDatasetNames == null ? null
                : List.copyOf(b.standardDatasetNames);
    }


    public static Builder builder()
    {
        return new Builder();
    }


    /** Empty provider — returns defaults for every call. */
    public static MapBackedLibraryMetadataProvider empty()
    {
        return builder().build();
    }


    /**
     * Construct a variable-metadata map with a subset of the keys
     * {@link MetadataProvider#getDomainVariables(String)} documents ({@code name}, {@code role},
     * {@code label}, {@code simpleDatatype}, {@code core}, {@code ordinal}, {@code codelist}). The
     * returned map preserves insertion order.
     */
    public static Map<String, String> var(String name, String role)
    {
        Map<String, String> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("role", role);
        return m;
    }


    /**
     * Convenience for richer variable metadata. {@code extras} are interpreted as alternating
     * {@code key, value} pairs appended after {@code name} / {@code role}.
     */
    public static Map<String, String> var(String name, String role, String... extras)
    {
        if ((extras.length & 1) != 0)
        {
            throw new IllegalArgumentException("extras must be key/value pairs");
        }
        Map<String, String> m = var(name, role);
        for (int i = 0; i < extras.length; i += 2)
        {
            m.put(extras[i], extras[i + 1]);
        }
        return m;
    }

    // ---- MetadataProvider implementation --------------------------------


    @Override
    public List<String> getRequiredVariables(String aDomain)
    {
        return requiredVariables.getOrDefault(up(aDomain), List.of());
    }


    @Override
    public List<String> getExpectedVariables(String aDomain)
    {
        return expectedVariables.getOrDefault(up(aDomain), List.of());
    }


    @Override
    public boolean supportsStructureKeyedVariables()
    {
        return !requiredVariablesForStructure.isEmpty();
    }


    /**
     * The two-arg primary (see
     * {@link MetadataProvider#getRequiredVariablesForStructure(String, List)}).
     * {@code aSubclassTokens} is accepted and ignored: a scenario's
     * {@code required-variables-for-structure} map is authored per structure token, so there is no
     * published {@code subClass} axis here for a chain to walk. A scenario that wants to pin
     * subclass governance declares the two structures' outcomes as two tokens.
     */
    @Override
    public @Nullable List<String> getRequiredVariablesForStructure(String aStructureToken,
            List<String> aSubclassTokens)
    {
        // null, not List.of(): an undeclared structure means "the product has no such structure",
        // which must SKIP the rule. Declaring one with no variables is the empty-list case.
        return requiredVariablesForStructure.get(up(aStructureToken));
    }


    @Override
    public @Nullable List<String> getExpectedVariablesForStructure(String aStructureToken,
            List<String> aSubclassTokens)
    {
        // ADaM has no Exp core value, so Expected == Required. See MetadataProvider.
        return requiredVariablesForStructure.get(up(aStructureToken));
    }


    @Override
    public List<String> getColumnOrder(String aDomain)
    {
        return columnOrder.getOrDefault(up(aDomain), List.of());
    }


    @Override
    public List<String> getModelColumnOrder(String aDomain)
    {
        return modelColumnOrder.getOrDefault(up(aDomain), List.of());
    }


    /**
     * Serves the engine's class-aware {@code GET_MODEL_COLUMN_ORDER} resolution
     * ({@code getStandardModelVariables}) from the synthetic {@code #library model-column-order}
     * map. Returns {@code null} when the scenario declared no model order for the table's domain,
     * preserving the production LIBRARY_NOT_AVAILABLE skip semantics.
     */
    @Override
    public @Nullable List<String> getStandardModelVariables(IDataTable aTable,
            DatasetResolver aResolver)
    {
        String name = aTable.getMetaData().getName();
        List<String> order = name != null ? modelColumnOrder.get(up(name)) : null;
        return order == null || order.isEmpty() ? null : order;
    }


    @Override
    public boolean isDomainCustom(String aDomain)
    {
        return customDomains.contains(up(aDomain));
    }


    @Override
    public List<String> getCodelistTerms(String aCodelistCode)
    {
        return codelistTerms.getOrDefault(up(aCodelistCode), List.of());
    }


    @Override
    public Map<String, String> getVariableMetadata(String aDomain, String aVariable)
    {
        Map<String, Map<String, String>> dm = variableMetadata.get(up(aDomain));
        if (dm == null)
        {
            return Map.of();
        }
        return dm.getOrDefault(up(aVariable), Map.of());
    }


    /**
     * <b>Plan 2, R11</b> — the NAME-keyed carry-over lookup, served from the same synthetic
     * {@code #library} variable-metadata map by scanning every declared domain for the name.
     *
     * <p>
     * ⛔ <b>Without this the harness would inherit the interface's empty default</b>, every
     * carry-over scenario would see "published nowhere", and a {@code noViolation} scenario would
     * pass for the wrong reason — the vacuous shape a {@code .cdt} fixture is most prone to. A
     * scenario declares the companion's published variables exactly as it declares any other
     * library metadata.
     * </p>
     */
    @Override
    public List<PublishedVariable> getPublishedVariablesByName(String aVariableName)
    {
        if (aVariableName == null || aVariableName.isEmpty())
        {
            return List.of();
        }
        String key = up(aVariableName);
        List<PublishedVariable> out = new ArrayList<>();
        for (Map.Entry<String, Map<String, Map<String, String>>> domain : variableMetadata
                .entrySet())
        {
            Map<String, String> meta = domain.getValue().get(key);
            if (meta != null && !meta.isEmpty())
            {
                out.add(new PublishedVariable(domain.getKey(), meta.get("label"),
                        meta.get("simpleDatatype")));
            }
        }
        return List.copyOf(out);
    }


    /**
     * CT2003 — serves the {@code library_variable_code_pair_matches} accessor from the synthetic
     * {@code #library codelist-codes} map. An undeclared (domain, variable) yields an empty map,
     * which the accessor reads as "no codelist bound" — no decision, so the rule stays silent
     * rather than firing.
     */
    @Override
    public Map<String, String> getCodelistCodeMap(String aDomain, String aVariable)
    {
        Map<String, Map<String, String>> dm = codelistCodes.get(up(aDomain));
        if (dm == null)
        {
            return Map.of();
        }
        return dm.getOrDefault(up(aVariable), Map.of());
    }


    @Override
    public List<Map<String, String>> getDomainVariables(String aDomain)
    {
        return domainVariables.getOrDefault(up(aDomain), List.of());
    }


    @Override
    public List<Map<String, String>> getModelVariables(String aDomain)
    {
        return modelVariables.getOrDefault(up(aDomain), List.of());
    }


    /**
     * EC-85 — serves a {@code get_model_filtered_variables(model_class=)} operation from the
     * synthetic {@code #library model-class-variables} map. The provider deliberately leaves
     * {@code getStandardModelVariablesForClass} at its interface default ({@code null}) so the
     * executor takes the same legacy fallback — and the same {@code --} substitution — as
     * {@code model-variables}. An undeclared class yields an empty list, which the executor turns
     * into LIBRARY_NOT_AVAILABLE (the rule SKIPs), never a vacuous empty set.
     */
    @Override
    public List<Map<String, String>> getModelVariablesForClass(String aModelClass)
    {
        return modelClassVariables.getOrDefault(up(aModelClass), List.of());
    }


    @Override
    public List<String> getPublishedCtPackages()
    {
        return publishedCtPackages;
    }


    /** Fix #147 — see {@link #standardDatasetNames}. */
    @Override
    public @Nullable List<String> getStandardDatasetNames()
    {
        return standardDatasetNames;
    }


    @Override
    public Map<String, String> getDatasetMetadata(String aDomain)
    {
        return datasetMetadata.getOrDefault(up(aDomain), Map.of());
    }


    @Override
    public boolean isCodelistExtensible(String aCodelistName)
    {
        // Default true when unknown (matches the interface JavaDoc).
        return codelistExtensible.getOrDefault(up(aCodelistName), Boolean.TRUE);
    }


    @Override
    public Map<String, String> getCodelistTermMappings(String aCodelistName)
    {
        return codelistTermMappings.getOrDefault(up(aCodelistName), Map.of());
    }


    @Override
    public String getStandard()
    {
        return standard;
    }


    @Override
    public String getVersion()
    {
        return version;
    }

    // ---- Introspection (for scenario round-trip) -----------------------------


    public Map<String, List<String>> getRequiredVariablesMap()
    {
        return requiredVariables;
    }


    public Map<String, List<String>> getExpectedVariablesMap()
    {
        return expectedVariables;
    }


    public Map<String, List<String>> getColumnOrderMap()
    {
        return columnOrder;
    }


    public Map<String, List<String>> getModelColumnOrderMap()
    {
        return modelColumnOrder;
    }


    public Set<String> getCustomDomainsSet()
    {
        return customDomains;
    }


    public Map<String, List<String>> getCodelistTermsMap()
    {
        return codelistTerms;
    }


    public Map<String, List<Map<String, String>>> getDomainVariablesMap()
    {
        return domainVariables;
    }


    public Map<String, List<Map<String, String>>> getModelVariablesMap()
    {
        return modelVariables;
    }


    public Map<String, List<Map<String, String>>> getModelClassVariablesMap()
    {
        return modelClassVariables;
    }


    public Map<String, Map<String, String>> getDatasetMetadataMap()
    {
        return datasetMetadata;
    }


    public Map<String, Boolean> getCodelistExtensibleMap()
    {
        return codelistExtensible;
    }


    public Map<String, Map<String, String>> getCodelistTermMappingsMap()
    {
        return codelistTermMappings;
    }


    public Map<String, Map<String, Map<String, String>>> getVariableMetadataMap()
    {
        return variableMetadata;
    }


    public Map<String, Map<String, Map<String, String>>> getCodelistCodesMap()
    {
        return codelistCodes;
    }


    public List<String> getPublishedCtPackagesList()
    {
        return publishedCtPackages;
    }


    /** True iff this provider carries no declarations beyond default standard/version. */
    public boolean isEmpty()
    {
        return requiredVariables.isEmpty() && expectedVariables.isEmpty()
                && requiredVariablesForStructure.isEmpty() && columnOrder.isEmpty()
                && modelColumnOrder.isEmpty() && customDomains.isEmpty() && codelistTerms.isEmpty()
                && variableMetadata.isEmpty() && codelistCodes.isEmpty()
                && domainVariables.isEmpty() && modelVariables.isEmpty()
                && modelClassVariables.isEmpty() && datasetMetadata.isEmpty()
                && codelistExtensible.isEmpty() && codelistTermMappings.isEmpty()
                && publishedCtPackages.isEmpty() && standardDatasetNames == null;
    }

    // ---- Helpers --------------------------------------------------------------


    private static String up(String aValue)
    {
        return aValue == null ? "" : aValue.toUpperCase(Locale.ROOT);
    }


    private static Map<String, Map<String, Map<String, String>>> deepCopy(
            Map<String, Map<String, Map<String, String>>> aSrc)
    {
        Map<String, Map<String, Map<String, String>>> out = new HashMap<>();
        for (var e : aSrc.entrySet())
        {
            Map<String, Map<String, String>> inner = new HashMap<>();
            for (var ie : e.getValue().entrySet())
            {
                inner.put(ie.getKey(), Map.copyOf(ie.getValue()));
            }
            out.put(e.getKey(), Collections.unmodifiableMap(inner));
        }
        return Collections.unmodifiableMap(out);
    }


    private static Map<String, List<Map<String, String>>> deepCopyList(
            Map<String, List<Map<String, String>>> aSrc)
    {
        Map<String, List<Map<String, String>>> out = new HashMap<>();
        for (var e : aSrc.entrySet())
        {
            List<Map<String, String>> list = new ArrayList<>();
            for (Map<String, String> m : e.getValue())
            {
                list.add(Map.copyOf(m));
            }
            out.put(e.getKey(), Collections.unmodifiableList(list));
        }
        return Collections.unmodifiableMap(out);
    }


    private static Map<String, Map<String, String>> deepCopyLeaf(
            Map<String, Map<String, String>> aSrc)
    {
        Map<String, Map<String, String>> out = new HashMap<>();
        for (var e : aSrc.entrySet())
        {
            out.put(e.getKey(), Map.copyOf(e.getValue()));
        }
        return Collections.unmodifiableMap(out);
    }

    // ---- Builder --------------------------------------------------------------

    public static final class Builder
    {

        private String standard = "test";

        private String version = "1";

        private final Map<String, List<String>> requiredVariables = new HashMap<>();

        private final Map<String, List<String>> expectedVariables = new HashMap<>();

        private final Map<String, List<String>> requiredVariablesForStructure = new HashMap<>();

        private final Map<String, List<String>> columnOrder = new HashMap<>();

        private final Map<String, List<String>> modelColumnOrder = new HashMap<>();

        private final Set<String> customDomains = new HashSet<>();

        private final Map<String, List<String>> codelistTerms = new HashMap<>();

        private final Map<String, Map<String, Map<String, String>>> variableMetadata = new HashMap<>();

        private final Map<String, Map<String, Map<String, String>>> codelistCodes = new HashMap<>();

        private final Map<String, List<Map<String, String>>> domainVariables = new HashMap<>();

        private final Map<String, List<Map<String, String>>> modelVariables = new HashMap<>();

        private final Map<String, List<Map<String, String>>> modelClassVariables = new HashMap<>();

        private final Map<String, Map<String, String>> datasetMetadata = new HashMap<>();

        private final Map<String, Boolean> codelistExtensible = new HashMap<>();

        private final Map<String, Map<String, String>> codelistTermMappings = new HashMap<>();

        private final List<String> publishedCtPackages = new ArrayList<>();

        private @Nullable List<String> standardDatasetNames;

        public Builder standard(String aStandard)
        {
            this.standard = aStandard;
            return this;
        }


        public Builder version(String aVersion)
        {
            this.version = aVersion;
            return this;
        }

        // ---- Simple per-domain lists ------------------------------------------


        public Builder requiredVariables(String aDomain, String... aVars)
        {
            requiredVariables.put(up(aDomain), List.of(aVars));
            return this;
        }


        public Builder expectedVariables(String aDomain, String... aVars)
        {
            expectedVariables.put(up(aDomain), List.of(aVars));
            return this;
        }


        /**
         * Fix #368: the published Required list for an ADaM data structure, e.g.
         * {@code requiredVariablesForStructure("BASIC DATA STRUCTURE", "PARAM", "PARAMCD")}. Names
         * must be given <b>as the standard publishes them</b> — templates included ({@code TRTxxP},
         * not {@code TRT01P}) — or the scenario re-creates the very substitution the engine is
         * responsible for.
         */
        public Builder requiredVariablesForStructure(String aStructureToken, String... aVars)
        {
            requiredVariablesForStructure.put(up(aStructureToken), List.of(aVars));
            return this;
        }


        public Builder columnOrder(String aDomain, String... aVars)
        {
            columnOrder.put(up(aDomain), List.of(aVars));
            return this;
        }


        public Builder modelColumnOrder(String aDomain, String... aVars)
        {
            modelColumnOrder.put(up(aDomain), List.of(aVars));
            return this;
        }


        public Builder customDomain(String aDomain)
        {
            customDomains.add(up(aDomain));
            return this;
        }

        // ---- Codelists --------------------------------------------------------


        public Builder codelistTerms(String aCodelistCode, String... aTerms)
        {
            codelistTerms.put(up(aCodelistCode), List.of(aTerms));
            return this;
        }


        public Builder codelistExtensible(String aCodelistName, boolean aExtensible)
        {
            codelistExtensible.put(up(aCodelistName), aExtensible);
            return this;
        }


        public Builder codelistTermMappings(String aCodelistName, Map<String, String> aMappings)
        {
            codelistTermMappings.put(up(aCodelistName), new LinkedHashMap<>(aMappings));
            return this;
        }

        // ---- Variable metadata -----------------------------------------------


        public Builder variableMetadata(String aDomain, String aVariable,
                Map<String, String> aMetadata)
        {
            variableMetadata.computeIfAbsent(up(aDomain), _ -> new HashMap<>()).put(up(aVariable),
                    new LinkedHashMap<>(aMetadata));
            return this;
        }


        /**
         * CT2003 — declare the variable's bound codelist as a submission value → NCI concept id
         * map, the input {@link MetadataProvider#getCodelistCodeMap} serves. Declaring it for a
         * (domain, variable) a second time REPLACES the previous map, matching
         * {@link #variableMetadata(String, String, Map)}.
         *
         * @param aDomain
         *            the domain name (upper-cased)
         * @param aVariable
         *            the variable name (upper-cased)
         * @param aCodes
         *            submission value → concept id; keys are kept verbatim
         * @return this builder
         */
        public Builder codelistCodes(String aDomain, String aVariable, Map<String, String> aCodes)
        {
            codelistCodes.computeIfAbsent(up(aDomain), _ -> new HashMap<>()).put(up(aVariable),
                    new LinkedHashMap<>(aCodes));
            return this;
        }


        /**
         * Set the full list of domain variables (for {@code get_dataset_filtered_variables} and
         * friends). Each entry typically carries at least {@code name} and {@code role} — use
         * {@link MapBackedLibraryMetadataProvider#var(String, String)}.
         */
        public Builder domainVariables(String aDomain, List<Map<String, String>> aVariables)
        {
            List<Map<String, String>> copy = new ArrayList<>();
            for (Map<String, String> v : aVariables)
            {
                copy.add(new LinkedHashMap<>(v));
            }
            domainVariables.put(up(aDomain), copy);
            return this;
        }


        /**
         * Append a single variable entry to the domain's variable list. Handy for building up a
         * small set inline.
         */
        public Builder domainVariable(String aDomain, String aName, String aRole)
        {
            domainVariables.computeIfAbsent(up(aDomain), _ -> new ArrayList<>())
                    .add(var(aName, aRole));
            return this;
        }


        public Builder modelVariables(String aDomain, List<Map<String, String>> aVariables)
        {
            List<Map<String, String>> copy = new ArrayList<>();
            for (Map<String, String> v : aVariables)
            {
                copy.add(new LinkedHashMap<>(v));
            }
            modelVariables.put(up(aDomain), copy);
            return this;
        }


        public Builder modelVariable(String aDomain, String aName, String aRole)
        {
            modelVariables.computeIfAbsent(up(aDomain), _ -> new ArrayList<>())
                    .add(var(aName, aRole));
            return this;
        }


        /**
         * EC-85 — declare an observation class's model-variable table (class-keyed; names
         * unsubstituted). Declaring a class twice replaces its list.
         */
        public Builder modelClassVariables(String aModelClass, List<Map<String, String>> aVariables)
        {
            List<Map<String, String>> copy = new ArrayList<>();
            for (Map<String, String> v : aVariables)
            {
                copy.add(new LinkedHashMap<>(v));
            }
            modelClassVariables.put(up(aModelClass), copy);
            return this;
        }


        public Builder modelClassVariable(String aModelClass, String aName, String aRole)
        {
            modelClassVariables.computeIfAbsent(up(aModelClass), _ -> new ArrayList<>())
                    .add(var(aName, aRole));
            return this;
        }

        // ---- Dataset metadata -------------------------------------------------


        /** Shorthand: set only the {@code "className"} entry of the dataset metadata map. */
        public Builder datasetClass(String aDomain, String aClassName)
        {
            return datasetMetadata(aDomain, Map.of("className", aClassName));
        }


        public Builder datasetMetadata(String aDomain, Map<String, String> aMetadata)
        {
            datasetMetadata.put(up(aDomain), new LinkedHashMap<>(aMetadata));
            return this;
        }

        // ---- CT packages ------------------------------------------------------


        /**
         * Fix #147 — declares the library's canonical dataset names, the list
         * {@code known_domain_only} expansion consults. Declaring it at all switches the filter
         * from "undecidable" to "decidable"; an explicitly empty declaration therefore means "the
         * library attests no domains", not "unknown".
         *
         * @param aNames
         *            the dataset names
         * @return this builder
         */
        public Builder standardDatasetNames(String... aNames)
        {
            standardDatasetNames = new ArrayList<>(List.of(aNames));
            return this;
        }


        public Builder publishedCtPackages(String... aIds)
        {
            publishedCtPackages.clear();
            for (String id : aIds)
            {
                publishedCtPackages.add(id);
            }
            return this;
        }


        public Builder publishedCtPackages(List<String> aIds)
        {
            publishedCtPackages.clear();
            publishedCtPackages.addAll(aIds);
            return this;
        }


        public MapBackedLibraryMetadataProvider build()
        {
            return new MapBackedLibraryMetadataProvider(this);
        }


        private static String up(String aValue)
        {
            return aValue == null ? "" : aValue.toUpperCase(Locale.ROOT);
        }
    }
}
