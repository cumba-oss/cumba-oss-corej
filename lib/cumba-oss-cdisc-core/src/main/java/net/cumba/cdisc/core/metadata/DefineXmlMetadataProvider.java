package net.cumba.cdisc.core.metadata;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.gen.DefineXMLProvider;
import org.jspecify.annotations.Nullable;

/**
 * Adapts a {@link DefineXMLProvider} (direct Define-XML access, read straight from the parsed
 * Define-XML structures) to the engine's {@link MetadataProvider} contract, serving the
 * <em>define</em> level of the three-level metadata model.
 *
 * <p>
 * This is the seam that lets a {@code Define Item Metadata Check against Library Metadata} rule
 * read its {@code define_variable_*} operands <b>directly from the define.xml</b> — bypassing the
 * lossy {@code ODM → IMetadataLibrary} (datatable) conversion that drops the codelist {@code ccode}
 * and coded codes.
 * </p>
 *
 * <p>
 * An optional {@code fallback} provider (the datatable-backed
 * {@link MetadataLibraryProvider#forDefine define provider}) makes this a safe drop-in for
 * <em>every</em> define rule type, not just the item-metadata family: variable- and codelist-scope
 * reads come from the ODM (authoritative {@code role}, plus the {@code ccode} /
 * {@code codelist_coded_codes} the datatable model omits), while dataset-level and other reads
 * delegate to the fallback so rule types that need define <em>dataset</em> metadata keep working.
 * Per-variable maps are <em>merged</em> (fallback base, ODM overlay) so no datatable-only key (e.g.
 * {@code length}, {@code format}, {@code core}) is lost.
 * </p>
 *
 * <p>
 * Per-variable attribute keys are mapped from the {@code DefineXMLProvider.getVariables} vocabulary
 * to the engine's provider-key channel (the {@code MetadataAttribute.providerKey()} names): notably
 * {@code dataType}&rarr;{@code simpleDatatype} and {@code orderNumber}&rarr;{@code ordinal}. The
 * list-valued {@code codelist_coded_codes} and {@code codelist_coded_values} are carried
 * JSON-encoded (see {@link DefineMetadataListCodec}).
 * </p>
 */
public final class DefineXmlMetadataProvider implements MetadataProvider
{

    /**
     * <b>R11</b> — delegated, never answered here. A Define-XML describes the sponsor's own
     * datasets; the name-keyed carry-over lookup is a CDISC <em>Library</em> question, so
     * inheriting the empty default would silently turn every carry-over check into "no candidates".
     */
    @Override
    public List<PublishedVariable> getPublishedVariablesByName(String variableName)
    {
        return fallback != null ? fallback.getPublishedVariablesByName(variableName) : List.of();
    }

    private final DefineXMLProvider define;

    private final @Nullable MetadataProvider fallback;

    public DefineXmlMetadataProvider(DefineXMLProvider aDefine)
    {
        this(aDefine, null);
    }


    /**
     * @param aDefine
     *            the direct Define-XML provider (ODM-backed)
     * @param aFallback
     *            the datatable-backed define provider for dataset-level / other reads, or
     *            {@code null} to serve only what the ODM exposes
     */
    public DefineXmlMetadataProvider(DefineXMLProvider aDefine,
            @Nullable MetadataProvider aFallback)
    {
        this.define = aDefine;
        this.fallback = aFallback;
    }


    /** Maps one {@code getVariables} entry to the engine provider-key channel. */
    private static Map<String, String> toProviderKeys(Map<String, String> genVar)
    {
        Map<String, String> out = new LinkedHashMap<>();
        putIfPresent(out, "name", genVar.get("name"));
        putIfPresent(out, "label", genVar.get("label"));
        putIfPresent(out, "simpleDatatype", genVar.get("dataType"));
        putIfPresent(out, "length", genVar.get("length"));
        putIfPresent(out, "role", genVar.get("role"));
        putIfPresent(out, "codelist", genVar.get("codelist"));
        putIfPresent(out, "mandatory", genVar.get("mandatory"));
        putIfPresent(out, "ordinal", genVar.get("orderNumber"));
        putIfPresent(out, "ccode", genVar.get("ccode"));
        // Coded codes are a list, carried JSON-encoded; default to the empty list.
        String coded = genVar.get("codelist_coded_codes");
        out.put("codelist_coded_codes", coded != null ? coded : "[]");
        // E2 DEFINE-only accessors (plans/PLAN-group-b-followups.md): Origin Type, Comment/Method
        // presence, and the bound codelist's external-dictionary name/version.
        putIfPresent(out, "origin_type", genVar.get("origin_type"));
        putIfPresent(out, "has_comment", genVar.get("has_comment"));
        putIfPresent(out, "has_method", genVar.get("has_method"));
        putIfPresent(out, "external_dictionary", genVar.get("external_dictionary"));
        putIfPresent(out, "external_dictionary_version", genVar.get("external_dictionary_version"));
        // EC-19 (Value Check against Define XML Variable): the variable-level ItemDef codelist
        // guard + enumerated coded values (the latter list-valued, carried JSON-encoded, defaulting
        // to the empty list like codelist_coded_codes).
        putIfPresent(out, "has_codelist", genVar.get("has_codelist"));
        String codedValues = genVar.get("codelist_coded_values");
        out.put("codelist_coded_values", codedValues != null ? codedValues : "[]");
        // Fix #263 (r3 tranche-0 decision lane, finding A1): the sponsor-extended terms
        // (def:ExtendedValue="Yes") OdmDefineXMLProvider emits, backing
        // var_codelist_extended_values("DEFINE"). Carried JSON-encoded, defaulting to the empty
        // list exactly like codelist_coded_values; without this the accessor resolves empty on
        // every production and #define-xml read.
        String extendedValues = genVar.get("codelist_extended_values");
        out.put("codelist_extended_values", extendedValues != null ? extendedValues : "[]");
        // Fix #123: the variable-level CodedValue -> Decode map (JSON object), defaulting to the
        // empty object so a variable with no decode-carrying codelist reads as "no expectation".
        String codeDecode = genVar.get("codelist_code_decode");
        out.put("codelist_code_decode", codeDecode != null ? codeDecode : "{}");
        return out;
    }


    private static void putIfPresent(Map<String, String> map, String key, @Nullable String value)
    {
        if (value != null)
        {
            map.put(key, value);
        }
    }


    @Override
    public List<Map<String, String>> getDomainVariables(String domain)
    {
        List<Map<String, String>> out = new ArrayList<>();
        for (Map<String, String> v : define.getVariables(domain))
        {
            out.add(toProviderKeys(v));
        }
        return out;
    }


    @Override
    public Map<String, String> getVariableMetadata(String domain, String variable)
    {
        Map<String, String> direct = null;
        for (Map<String, String> v : define.getVariables(domain))
        {
            if (variable.equals(v.get("name")))
            {
                direct = toProviderKeys(v);
                break;
            }
        }
        if (direct == null)
        {
            // Not declared in the Define-XML — defer entirely to the fallback (if any).
            return fallback != null ? fallback.getVariableMetadata(domain, variable) : Map.of();
        }
        if (fallback == null)
        {
            return direct;
        }
        // Merge: datatable base (length / format / core …) overlaid with the ODM's authoritative
        // role and the ccode / codelist_coded_codes the datatable model omits.
        Map<String, String> merged = new LinkedHashMap<>(
                fallback.getVariableMetadata(domain, variable));
        merged.putAll(direct);
        return merged;
    }


    @Override
    public List<String> getColumnOrder(String domain)
    {
        List<String> order = define.getVariables(domain).stream().map(v -> v.get("name")).toList();
        if (order.isEmpty() && fallback != null)
        {
            return fallback.getColumnOrder(domain);
        }
        return order;
    }


    @Override
    public List<String> getDatasetNames()
    {
        // The dataset (ItemGroupDef) names come straight from the ODM; fall back to the
        // datatable-backed define provider only when the ODM declares none.
        List<String> names = define.getDatasetNames();
        if (names.isEmpty() && fallback != null)
        {
            return fallback.getDatasetNames();
        }
        return names;
    }


    @Override
    public List<String> getKeyVariables(String domain)
    {
        // The Define-XML KeySequence ordering is authoritative and read straight from the ODM
        // (ItemRef @KeySequence). Fall back to the datatable-backed define provider only when the
        // ODM declares no keys for the domain.
        List<String> keys = define.getKeyVariables(domain);
        if (keys.isEmpty() && fallback != null)
        {
            return fallback.getKeyVariables(domain);
        }
        return keys;
    }


    @Override
    public List<String> getCodelistTerms(String codelistCode)
    {
        List<String> terms = define.getCodelistTerms(codelistCode).stream()
                .map(t -> t.get("codedValue")).toList();
        if (terms.isEmpty() && fallback != null)
        {
            return fallback.getCodelistTerms(codelistCode);
        }
        return terms;
    }


    @Override
    public Map<String, String> getDatasetMetadata(String domain)
    {
        // Dataset-level define metadata (class, structure, key sequence, …) is richer in the
        // datatable model; defer to it when present.
        return fallback != null ? fallback.getDatasetMetadata(domain)
                : define.getDatasetMetadata(domain);
    }


    /**
     * Fix #119 (review finding 1): the declared {@code def:Class} straight from the parsed ODM,
     * falling back to the datatable-backed provider — without this override the interface default
     * returned {@code null} and the whole declared channel (incl. {@code --define-first}) was a
     * silent no-op on the standard StudyValidationService path.
     */
    @Override
    public @Nullable String getDeclaredDatasetClass(String datasetName)
    {
        String declared = define.getDeclaredClass(datasetName);
        if (declared != null && !declared.isEmpty())
        {
            return declared;
        }
        return fallback != null ? fallback.getDeclaredDatasetClass(datasetName) : null;
    }


    /**
     * Fix #119 (review finding 1): declared {@code def:SubClass} names, ODM first then fallback.
     */
    @Override
    public List<String> getDeclaredSubClasses(String datasetName)
    {
        List<String> declared = define.getDeclaredSubClasses(datasetName);
        if (!declared.isEmpty())
        {
            return declared;
        }
        return fallback != null ? fallback.getDeclaredSubClasses(datasetName) : List.of();
    }


    @Override
    public List<String> getRequiredVariables(String domain)
    {
        return fallback != null ? fallback.getRequiredVariables(domain) : List.of();
    }


    @Override
    public List<String> getExpectedVariables(String domain)
    {
        return fallback != null ? fallback.getExpectedVariables(domain) : List.of();
    }

    // ⚠ Delegated for the same reason as CompanionDomainsProvider: the interface defaults say
    // "cannot answer", so a wrapper that omits them silently disables structure-keyed resolution
    // for every ADaM run that carries a define.xml.


    @Override
    public boolean supportsStructureKeyedVariables()
    {
        return fallback != null && fallback.supportsStructureKeyedVariables();
    }


    /**
     * Fix #369 — delegated to {@code fallback}. With no fallback this provider is pure Define-XML
     * and has no CDISC Library behind it to have failed, so {@code false} is correct.
     */
    @Override
    public boolean isLibraryUnavailable()
    {
        return fallback != null && fallback.isLibraryUnavailable();
    }


    /**
     * Fix #369 — the only capability method this decorator did not delegate. It is load-bearing:
     * {@code OperationExecutor.libraryAnswerable} uses {@code getDefineVersion() != null} as the
     * exact test for <em>"the fallback is a Define-XML library"</em>, so a wrapper inheriting the
     * {@code null} default would report "not define-backed" for a provider that is, and the
     * degraded define opt-in would silently never engage — the Fix #368 shape exactly.
     */
    @Override
    public @Nullable String getDefineVersion()
    {
        return fallback != null ? fallback.getDefineVersion() : null;
    }


    /**
     * ⚠ The <b>two-arg</b> form is the primary one (see
     * {@link MetadataProvider#getRequiredVariablesForStructure(String, List)}); delegating only the
     * one-arg convenience would silently drop the dataset's subclass.
     */
    @Override
    public @Nullable List<String> getRequiredVariablesForStructure(String structureToken,
            List<String> subclassTokens)
    {
        return fallback != null
                ? fallback.getRequiredVariablesForStructure(structureToken, subclassTokens)
                : null;
    }


    @Override
    public @Nullable List<String> getExpectedVariablesForStructure(String structureToken,
            List<String> subclassTokens)
    {
        return fallback != null
                ? fallback.getExpectedVariablesForStructure(structureToken, subclassTokens)
                : null;
    }


    /** Provenance (log-only) — from the structure-keyed fallback, when one is wired. */
    @Override
    public List<String> declaredStructureKeyedProducts()
    {
        return fallback != null ? fallback.declaredStructureKeyedProducts() : List.of();
    }


    @Override
    public List<String> getModelColumnOrder(String domain)
    {
        return fallback != null ? fallback.getModelColumnOrder(domain) : List.of();
    }


    @Override
    public boolean isDomainCustom(String domain)
    {
        // The define level alone cannot decide custom-vs-standard; CORE-000929's $domain_is_custom
        // resolves against the LIBRARY provider, so this is never the deciding source here.
        return fallback != null && fallback.isDomainCustom(domain);
    }


    @Override
    public boolean isCodelistExtensible(String codelistName)
    {
        return fallback != null && fallback.isCodelistExtensible(codelistName);
    }


    @Override
    public Map<String, String> getCodelistTermMappings(String codelistName)
    {
        return fallback != null ? fallback.getCodelistTermMappings(codelistName) : Map.of();
    }


    @Override
    public @Nullable String getStandard()
    {
        return fallback != null ? fallback.getStandard() : null;
    }


    @Override
    public @Nullable String getVersion()
    {
        return fallback != null ? fallback.getVersion() : null;
    }

}
