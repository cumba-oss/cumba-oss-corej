package net.cumba.dataviewer.examples.cdt.ruletest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Loads a YAML "library sidecar" (referenced from a {@code #library-include} directive) into a
 * {@link MapBackedLibraryMetadataProvider.Builder}. The schema mirrors the inline {@code #library}
 * directive grammar one-to-one; every node is optional. Unknown keys are rejected so typos fail
 * loudly rather than being silently ignored.
 *
 * <pre>{@code
 * standard: sdtmig
 * version: "3-4"
 * published-ct-packages: [ sdtmct-2023-12-13 ]
 * custom-domains: [ ZZ ]
 * domains:
 *   LB:
 *     required-variables: [ STUDYID, DOMAIN, USUBJID, LBSEQ ]
 *     expected-variables: [ LBTEST ]
 *     column-order:       [ STUDYID, DOMAIN, USUBJID ]
 *     model-column-order: [ STUDYID, DOMAIN ]
 *     dataset-class: FINDINGS
 *     dataset-metadata: { structure: "One record per lab test per visit" }
 *     variables:        [ { name: LBORRES, role: Result, label: "My Label" } ]
 *     model-variables:  [ { name: "--DTC", role: Timing } ]
 *     variable-metadata:
 *       LBORRES: { label: "My Label", simpleDatatype: Char, core: Exp }
 *     codelist-codes:
 *       LBTESTCD: { ALB: C64431 }
 *       LBTEST:   { Albumin: C64431 }
 * codelists:
 *   C66742:
 *     terms: [ Y, N, U ]
 *     extensible: false
 *     term-mappings: { Y: "Yes", N: "No" }
 * }</pre>
 */
final class LibraryYaml
{

    private static final YAMLMapper YAML = new YAMLMapper();

    private static final Set<String> TOP_KEYS = Set.of("standard", "version",
            "published-ct-packages", "custom-domains", "domains", "codelists");

    private static final Set<String> DOMAIN_KEYS = Set.of("required-variables",
            "expected-variables", "column-order", "model-column-order", "dataset-class",
            "dataset-metadata", "variables", "model-variables", "variable-metadata",
            "codelist-codes");

    private static final Set<String> CODELIST_KEYS = Set.of("terms", "extensible", "term-mappings");

    private LibraryYaml()
    {
    }


    /**
     * Parse {@code aIn} as a YAML library sidecar and fold every declared fragment into
     * {@code aBuilder}. {@code aSource} is used only for error messages.
     */
    static void merge(MapBackedLibraryMetadataProvider.Builder aBuilder, InputStream aIn,
            String aSource)
        throws IOException
    {
        JsonNode root = YAML.readTree(aIn);
        if (root == null || root.isNull() || root.isMissingNode())
        {
            return; // empty document — nothing to merge
        }
        if (!root.isObject())
        {
            throw err(aSource, "root must be a YAML mapping");
        }
        requireKnownKeys(root, TOP_KEYS, aSource, "top-level");

        if (root.hasNonNull("standard"))
        {
            aBuilder.standard(asScalar(root.get("standard"), aSource, "standard"));
        }
        if (root.hasNonNull("version"))
        {
            aBuilder.version(asScalar(root.get("version"), aSource, "version"));
        }
        if (root.hasNonNull("published-ct-packages"))
        {
            aBuilder.publishedCtPackages(stringList(root.get("published-ct-packages"), aSource,
                    "published-ct-packages"));
        }
        if (root.hasNonNull("custom-domains"))
        {
            for (String d : stringList(root.get("custom-domains"), aSource, "custom-domains"))
            {
                aBuilder.customDomain(d);
            }
        }
        mergeChildren(root.get("domains"), aSource, "domains",
                (domain, node) -> mergeDomain(aBuilder, domain, node, aSource));
        mergeChildren(root.get("codelists"), aSource, "codelists",
                (code, node) -> mergeCodelist(aBuilder, code, node, aSource));
    }


    private static void mergeDomain(MapBackedLibraryMetadataProvider.Builder aBuilder,
            String aDomain, JsonNode aNode, String aSource)
    {
        if (!aNode.isObject())
        {
            throw err(aSource, "domain '" + aDomain + "' must be a mapping");
        }
        requireKnownKeys(aNode, DOMAIN_KEYS, aSource, "domain '" + aDomain + "'");

        applyVarNames(aNode, "required-variables", aSource, aDomain, aBuilder::requiredVariables);
        applyVarNames(aNode, "expected-variables", aSource, aDomain, aBuilder::expectedVariables);
        applyVarNames(aNode, "column-order", aSource, aDomain, aBuilder::columnOrder);
        applyVarNames(aNode, "model-column-order", aSource, aDomain, aBuilder::modelColumnOrder);

        // dataset-class is sugar for the dataset-metadata 'className' key, so combine both into a
        // single map (a separate datasetMetadata(...) call would otherwise replace the className).
        Map<String, String> dsMeta = new LinkedHashMap<>();
        if (aNode.hasNonNull("dataset-class"))
        {
            dsMeta.put("className",
                    asScalar(aNode.get("dataset-class"), aSource, aDomain + ".dataset-class"));
        }
        if (aNode.has("dataset-metadata"))
        {
            dsMeta.putAll(stringMap(aNode.get("dataset-metadata"), aSource,
                    aDomain + ".dataset-metadata"));
        }
        if (!dsMeta.isEmpty())
        {
            aBuilder.datasetMetadata(aDomain, dsMeta);
        }
        if (aNode.has("variables"))
        {
            aBuilder.domainVariables(aDomain,
                    mapList(aNode.get("variables"), aSource, aDomain + ".variables"));
        }
        if (aNode.has("model-variables"))
        {
            aBuilder.modelVariables(aDomain,
                    mapList(aNode.get("model-variables"), aSource, aDomain + ".model-variables"));
        }
        JsonNode vm = aNode.get("variable-metadata");
        if (vm != null && !vm.isNull())
        {
            if (!vm.isObject())
            {
                throw err(aSource, aDomain + ".variable-metadata must be a mapping");
            }
            for (Map.Entry<String, JsonNode> e : vm.properties())
            {
                aBuilder.variableMetadata(aDomain, e.getKey(), stringMap(e.getValue(), aSource,
                        aDomain + ".variable-metadata." + e.getKey()));
            }
        }
        // CT2003 — mirrors '#library codelist-codes DOMAIN VAR TERM=CODE ...'.
        JsonNode cc = aNode.get("codelist-codes");
        if (cc != null && !cc.isNull())
        {
            if (!cc.isObject())
            {
                throw err(aSource, aDomain + ".codelist-codes must be a mapping");
            }
            for (Map.Entry<String, JsonNode> e : cc.properties())
            {
                aBuilder.codelistCodes(aDomain, e.getKey(), stringMap(e.getValue(), aSource,
                        aDomain + ".codelist-codes." + e.getKey()));
            }
        }
    }


    private static void mergeCodelist(MapBackedLibraryMetadataProvider.Builder aBuilder,
            String aCode, JsonNode aNode, String aSource)
    {
        if (!aNode.isObject())
        {
            throw err(aSource, "codelist '" + aCode + "' must be a mapping");
        }
        requireKnownKeys(aNode, CODELIST_KEYS, aSource, "codelist '" + aCode + "'");

        if (aNode.has("terms"))
        {
            aBuilder.codelistTerms(aCode, stringList(aNode.get("terms"), aSource, aCode + ".terms")
                    .toArray(new String[0]));
        }
        if (aNode.hasNonNull("extensible"))
        {
            JsonNode ext = aNode.get("extensible");
            if (!ext.isBoolean())
            {
                throw err(aSource, "codelist '" + aCode + "' extensible must be true or false");
            }
            aBuilder.codelistExtensible(aCode, ext.booleanValue());
        }
        if (aNode.has("term-mappings"))
        {
            aBuilder.codelistTermMappings(aCode,
                    stringMap(aNode.get("term-mappings"), aSource, aCode + ".term-mappings"));
        }
    }

    // ---- helpers -------------------------------------------------------------------

    @FunctionalInterface
    private interface VarNamesFn
    {

        void apply(String aDomain, String... aVars);
    }

    private static void applyVarNames(JsonNode aDomainNode, String aKey, String aSource,
            String aDomain, VarNamesFn aFn)
    {
        if (aDomainNode.has(aKey))
        {
            aFn.apply(aDomain, stringList(aDomainNode.get(aKey), aSource, aDomain + "." + aKey)
                    .toArray(new String[0]));
        }
    }

    @FunctionalInterface
    private interface ChildFn
    {

        void apply(String aName, JsonNode aNode);
    }

    private static void mergeChildren(JsonNode aMap, String aSource, String aWhat, ChildFn aFn)
    {
        if (aMap == null || aMap.isNull())
        {
            return;
        }
        if (!aMap.isObject())
        {
            throw err(aSource, "'" + aWhat + "' must be a mapping");
        }
        for (Map.Entry<String, JsonNode> e : aMap.properties())
        {
            aFn.apply(e.getKey(), e.getValue());
        }
    }


    private static String asScalar(JsonNode aNode, String aSource, String aWhat)
    {
        if (aNode == null || aNode.isNull() || aNode.isContainerNode())
        {
            throw err(aSource, aWhat + " must be a scalar value");
        }
        return aNode.asText();
    }


    private static List<String> stringList(JsonNode aNode, String aSource, String aWhat)
    {
        if (aNode == null || !aNode.isArray())
        {
            throw err(aSource, aWhat + " must be a list");
        }
        List<String> out = new ArrayList<>();
        for (JsonNode el : aNode)
        {
            if (el.isContainerNode() || el.isNull())
            {
                throw err(aSource, aWhat + " entries must be scalar values");
            }
            out.add(el.asText());
        }
        return out;
    }


    private static Map<String, String> stringMap(JsonNode aNode, String aSource, String aWhat)
    {
        if (aNode == null || !aNode.isObject())
        {
            throw err(aSource, aWhat + " must be a mapping");
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> e : aNode.properties())
        {
            // A key containing '=' cannot round-trip through the key=value directive form
            // (parseKeyValues splits on the first '='), so reject it rather than silently corrupt.
            if (e.getKey().indexOf('=') >= 0)
            {
                throw err(aSource, aWhat + " key '" + e.getKey() + "' must not contain '='");
            }
            JsonNode v = e.getValue();
            if (v.isContainerNode() || v.isNull())
            {
                throw err(aSource, aWhat + "." + e.getKey() + " must be a scalar value");
            }
            out.put(e.getKey(), v.asText());
        }
        return out;
    }


    private static List<Map<String, String>> mapList(JsonNode aNode, String aSource, String aWhat)
    {
        if (aNode == null || !aNode.isArray())
        {
            throw err(aSource, aWhat + " must be a list");
        }
        List<Map<String, String>> out = new ArrayList<>();
        int i = 0;
        for (JsonNode el : aNode)
        {
            out.add(stringMap(el, aSource, aWhat + "[" + i + "]"));
            i++;
        }
        return out;
    }


    private static void requireKnownKeys(JsonNode aObj, Set<String> aKnown, String aSource,
            String aWhere)
    {
        for (Map.Entry<String, JsonNode> e : aObj.properties())
        {
            if (!aKnown.contains(e.getKey()))
            {
                throw err(aSource, "unknown " + aWhere + " key '" + e.getKey() + "'");
            }
        }
    }


    private static RuleTestCdtException err(String aSource, String aMessage)
    {
        return new RuleTestCdtException(aSource + ": " + aMessage);
    }
}
