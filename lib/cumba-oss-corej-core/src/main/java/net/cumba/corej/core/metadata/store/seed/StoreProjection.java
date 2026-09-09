package net.cumba.corej.core.metadata.store.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredCodelist;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredTerm;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import org.jspecify.annotations.Nullable;

/**
 * The ONE projection from source metadata JSON onto the store's records, shared by
 * {@link PickleStoreSeeder} and {@link WebApiStoreSeeder}.
 *
 * <p>
 * Both sources describe the same documents — the web-api cache is historically a re-serialisation
 * of the pickles — so both seeders hand their trees ({@code Unpickler} output via
 * {@code valueToTree}, HTTP bodies via {@code readTree}) to <em>this</em> class. That is what makes
 * the byte-identity conformance of plan §5.1 structural rather than coincidental: a field read here
 * is read identically from both sources, and a field NOT read here exists in neither store. ⛔ Do
 * not fork per-source projection logic into the seeders.
 * </p>
 *
 * <p>
 * Wire-form differences the projection absorbs, deliberately in the direction of the engine's own
 * model (plan §5.1 — the canonical form is not the API wire format):
 * </p>
 * <ul>
 * <li>{@code extensible}: the pickles carry a real boolean, the API a JSON string
 * ({@code "false"}); both land as {@link Boolean}, anything unrecognisable as {@code null} — the
 * same leniency {@code CtCodelist.extensible()} applies, for the same reason;</li>
 * <li>{@code _links.codelist}: an ARRAY of refs (audit §3 correction), each reduced to its trailing
 * id segment;</li>
 * <li>an ADaM structure's {@code class} key lands in {@link StoredDataStructure#className()};</li>
 * <li>Python-only keys ({@code dataset_names}, {@code standard_type}) and all other {@code _links}
 * internals are simply never read.</li>
 * </ul>
 *
 * <p>
 * <p>
 * ⭐ <b>What this class reads is frozen by {@code /metadata/store/field-manifest.json}</b> — the
 * machine-readable form of AUDIT-metadata-reachable-fields.md §2/§3 — and
 * {@code StoreFieldManifestTest} projects a source document generated from that manifest and reds
 * if any declared field arrives {@code null}. It is what a hand-written fixture cannot be: it
 * cannot drift away from the frozen field set, which is how {@code examples} went missing from
 * every seeded store while the round-trip test stayed green. ⛔ A field added here without a
 * manifest row, or a manifest row without a read here, is a red test, not a silent narrowing. (A
 * dead {@code VARIABLE_SCALARS} constant used to stand in this place, declared and never read; the
 * manifest and its guard replaced it on 2026-09-08.)
 * </p>
 *
 * An instance additionally <b>interns</b> terms and codelists across every package it projects: 206
 * cumulative CT packages hold ~1.75 M term rows but only ~64 K distinct terms, and without sharing,
 * a full seed would materialise every row as its own object graph. Interning is purely a memory
 * measure — {@code MetadataStoreWriter} deduplicates by value equality regardless. Not thread-safe;
 * one instance per seed run.
 * </p>
 */
final class StoreProjection
{

    private final Map<StoredTerm, StoredTerm> termPool = new HashMap<>();

    private final Map<StoredCodelist, StoredCodelist> codelistPool = new HashMap<>();

    /**
     * Projects one CT package document ({@code {…, "codelists": […]}}) onto its stored form.
     *
     * @param aId
     *            the package id ({@code sdtmct-2024-09-27})
     * @param aPackage
     *            the package document; only {@code codelists} is read (audit §2 — every other
     *            package-level field has zero call sites)
     * @return the stored package
     */
    StoredCtPackage ctPackage(String aId, JsonNode aPackage)
    {
        List<StoredCodelist> codelists = new ArrayList<>();
        for (JsonNode codelist : array(aPackage, "codelists"))
        {
            codelists.add(codelist(codelist));
        }
        return new StoredCtPackage(aId, codelists);
    }


    /**
     * Re-interns a package read back from an existing store, so packages carried forward on a
     * re-seed (plan §5.2) share term/codelist instances with freshly projected ones.
     *
     * @param aPackage
     *            the carried package
     * @return an equal package over interned instances
     */
    StoredCtPackage intern(StoredCtPackage aPackage)
    {
        List<StoredCodelist> codelists = new ArrayList<>(aPackage.codelists().size());
        for (StoredCodelist codelist : aPackage.codelists())
        {
            codelists.add(codelistPool.computeIfAbsent(codelist, c -> c));
        }
        return new StoredCtPackage(aPackage.id(), codelists);
    }


    /**
     * Projects one product document — IG, foundational model or ADaM — onto its stored form. The
     * three shapes each fill their slots ({@code classes} with datasets, {@code classes} with class
     * variables plus top-level {@code datasets}, {@code dataStructures}); slots the document does
     * not carry stay empty.
     *
     * @param aKey
     *            the store key ({@code standards/…} or {@code models/…})
     * @param aProduct
     *            the product document
     * @return the stored product
     */
    StoredProduct product(String aKey, JsonNode aProduct)
    {
        List<StoredClass> classes = new ArrayList<>();
        for (JsonNode clazz : array(aProduct, "classes"))
        {
            classes.add(productClass(clazz));
        }
        List<StoredDataset> datasets = new ArrayList<>();
        for (JsonNode dataset : array(aProduct, "datasets"))
        {
            datasets.add(dataset(dataset));
        }
        List<StoredDataStructure> structures = new ArrayList<>();
        for (JsonNode structure : array(aProduct, "dataStructures"))
        {
            structures.add(dataStructure(structure));
        }
        return StoredProduct.builder().key(aKey).name(text(aProduct, "name"))
                .label(text(aProduct, "label")).version(text(aProduct, "version"))
                .modelHref(text(aProduct.path("_links").path("model"), "href")).classes(classes)
                .datasets(datasets).dataStructures(structures).build();
    }


    private StoredCodelist codelist(JsonNode aCodelist)
    {
        List<StoredTerm> terms = new ArrayList<>();
        for (JsonNode term : array(aCodelist, "terms"))
        {
            StoredTerm projected = new StoredTerm(text(term, "submissionValue"),
                    text(term, "conceptId"), text(term, "preferredTerm"), text(term, "definition"),
                    stringList(term, "synonyms"));
            terms.add(termPool.computeIfAbsent(projected, t -> t));
        }
        StoredCodelist projected = new StoredCodelist(text(aCodelist, "submissionValue"),
                text(aCodelist, "conceptId"), text(aCodelist, "preferredTerm"),
                text(aCodelist, "definition"), stringList(aCodelist, "synonyms"),
                extensible(aCodelist), terms);
        return codelistPool.computeIfAbsent(projected, c -> c);
    }


    private StoredClass productClass(JsonNode aClass)
    {
        List<StoredVariable> classVariables = new ArrayList<>();
        for (JsonNode variable : array(aClass, "classVariables"))
        {
            classVariables.add(variable(variable));
        }
        List<StoredDataset> datasets = new ArrayList<>();
        for (JsonNode dataset : array(aClass, "datasets"))
        {
            datasets.add(dataset(dataset));
        }
        return new StoredClass(text(aClass, "name"), text(aClass, "label"), text(aClass, "ordinal"),
                classVariables, datasets);
    }


    private StoredDataset dataset(JsonNode aDataset)
    {
        List<StoredVariable> variables = new ArrayList<>();
        for (JsonNode variable : array(aDataset, "datasetVariables"))
        {
            variables.add(variable(variable));
        }
        return new StoredDataset(text(aDataset, "name"), text(aDataset, "label"),
                text(aDataset, "ordinal"), text(aDataset, "datasetStructure"), variables);
    }


    private StoredDataStructure dataStructure(JsonNode aStructure)
    {
        List<StoredVariableSet> sets = new ArrayList<>();
        for (JsonNode set : array(aStructure, "analysisVariableSets"))
        {
            List<StoredVariable> variables = new ArrayList<>();
            for (JsonNode variable : array(set, "analysisVariables"))
            {
                variables.add(variable(variable));
            }
            sets.add(new StoredVariableSet(text(set, "name"), text(set, "label"),
                    text(set, "ordinal"), variables));
        }
        return new StoredDataStructure(text(aStructure, "name"), text(aStructure, "label"),
                text(aStructure, "ordinal"), text(aStructure, "class"),
                text(aStructure, "subClass"), sets);
    }


    private StoredVariable variable(JsonNode aVariable)
    {
        StoredVariable.StoredVariableBuilder builder = StoredVariable.builder()
                .name(text(aVariable, "name")).label(text(aVariable, "label"))
                .ordinal(text(aVariable, "ordinal")).core(text(aVariable, "core"))
                .role(text(aVariable, "role")).simpleDatatype(text(aVariable, "simpleDatatype"))
                .description(text(aVariable, "description"))
                .valueList(stringList(aVariable, "valueList"))
                .describedValueDomain(text(aVariable, "describedValueDomain"))
                .codelistSubmissionValues(stringList(aVariable, "codelistSubmissionValues"))
                .roleDescription(text(aVariable, "roleDescription"))
                .definition(text(aVariable, "definition")).notes(text(aVariable, "notes"))
                .examples(text(aVariable, "examples"))
                .usageRestrictions(text(aVariable, "usageRestrictions"))
                .variableCcode(text(aVariable, "variableCcode"));
        return builder.codelistIds(codelistIds(aVariable)).build();
    }


    /**
     * The variable's codelist refs — {@code _links.codelist} is an ARRAY (31 real variables carry
     * 2–5 refs; audit §3 correction), each ref's id being the trailing segment of its href
     * ({@code /mdr/root/ct/sdtmct/codelists/C66731} → {@code C66731}). A lone object is accepted
     * too, defensively. Absent link ⇒ {@code null}, matching every other unpublished field.
     */
    private static @Nullable List<String> codelistIds(JsonNode aVariable)
    {
        JsonNode link = aVariable.path("_links").path("codelist");
        if (link.isMissingNode() || link.isNull())
        {
            return null;
        }
        List<String> ids = new ArrayList<>();
        if (link.isArray())
        {
            for (JsonNode ref : link)
            {
                addRefId(ids, ref);
            }
        }
        else
        {
            addRefId(ids, link);
        }
        return ids;
    }


    private static void addRefId(List<String> aIds, JsonNode aRef)
    {
        String href = text(aRef, "href");
        if (href == null || href.isBlank())
        {
            return;
        }
        aIds.add(href.substring(href.lastIndexOf('/') + 1));
    }


    /**
     * {@code extensible} in the engine's own form: the pickles carry a boolean, the API a string;
     * both are accepted, and anything else — absent, null, unrecognisable — is {@code null} rather
     * than a silent {@code false} (the {@code CtCodelist.extensible()} rule).
     */
    private static @Nullable Boolean extensible(JsonNode aCodelist)
    {
        JsonNode value = aCodelist.get("extensible");
        if (value == null || value.isNull())
        {
            return null;
        }
        if (value.isBoolean())
        {
            return value.booleanValue();
        }
        if (value.isTextual())
        {
            if ("true".equalsIgnoreCase(value.textValue()))
            {
                return Boolean.TRUE;
            }
            if ("false".equalsIgnoreCase(value.textValue()))
            {
                return Boolean.FALSE;
            }
        }
        return null;
    }


    /** The field as text, or {@code null} when absent or JSON null. */
    private static @Nullable String text(JsonNode aNode, String aField)
    {
        JsonNode value = aNode.get(aField);
        if (value == null || value.isNull() || value.isContainerNode())
        {
            return null;
        }
        return value.asText();
    }


    /**
     * The field as a string list, or {@code null} when absent or JSON null — {@code null}
     * (unpublished) and an empty list are distinct and both round-trip through the store.
     */
    private static @Nullable List<String> stringList(JsonNode aNode, String aField)
    {
        JsonNode value = aNode.get(aField);
        if (value == null || value.isNull() || !value.isArray())
        {
            return null;
        }
        List<String> values = new ArrayList<>(value.size());
        for (JsonNode element : value)
        {
            if (!element.isNull())
            {
                values.add(element.asText());
            }
        }
        return values;
    }


    /** The field as an array to iterate, or an empty array when absent or not an array. */
    private static JsonNode array(JsonNode aNode, String aField)
    {
        JsonNode value = aNode.get(aField);
        return value != null && value.isArray() ? value : JsonNodeFactory.instance.arrayNode();
    }
}
