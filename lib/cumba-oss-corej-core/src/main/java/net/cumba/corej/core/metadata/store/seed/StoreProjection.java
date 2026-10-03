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
import net.cumba.corej.core.metadata.store.StoredDomain;
import net.cumba.corej.core.metadata.store.StoredField;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredScenario;
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
 * id segment — on SDTM/SEND variables and, since format 4, on CDASH fields (T1-9's one
 * {@code _links}-derived exception, extended by the owner 2026-09-28,
 * PLAN-store-cdash-codelist-ids);</li>
 * <li>an ADaM structure's {@code class} key lands in {@link StoredDataStructure#className()};</li>
 * <li>Python-only keys ({@code dataset_names}, {@code standard_type} — added by the Python cache
 * builder, absent from the API documents) and all other {@code _links} internals (bar
 * {@code _links.codelist} above and a product's {@code _links.model}) are simply never read.
 * Everything else the pickles publish IS read, at every level, since format 3
 * (PLAN-define-ct-evaluation T1-9); the manifest's {@code excluded} blocks record the two
 * exceptions per level, and {@code StoreFieldManifestTest} leg 5 reds on a source key that is in
 * neither list.</li>
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
 * <b>Text normalisation (format 5, PLAN-store-library-content-errata §2.2):</b> every PRODUCT text
 * field goes through {@link StoreTextNormaliser} on the way in — literal {@code \n} sequences, line
 * breaks in short fields, HTML entities — and the instance records each changed site
 * ({@link #normalisedSites()}) and the counts by kind ({@link #normalisationCounts()}), which
 * {@link SeedRun} hands to the errata register and the seed report. CT packages are projected
 * verbatim. The field set is unchanged by this; the manifest guard above still holds.
 * </p>
 *
 * <p>
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

    private final List<String> normalisedSites = new ArrayList<>();

    private final Map<StoreTextNormaliser.Kind, Integer> normalisationCounts = new java.util.EnumMap<>(
            StoreTextNormaliser.Kind.class);

    /**
     * Projects one CT package document ({@code {…, "codelists": […]}}) onto its stored form.
     *
     * @param aId
     *            the package id ({@code sdtmct-2024-09-27})
     * @param aPackage
     *            the package document; only {@code codelists} is read. The pickles carry nothing
     *            else but the id echo ({@code package}); the API's package-level {@code name},
     *            {@code version} and {@code effectiveDate} are API-only and stay out
     *            (PLAN-define-ct-evaluation T1-9: the pickle cache is the universe)
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
        String path = aKey;
        List<StoredClass> classes = new ArrayList<>();
        for (JsonNode clazz : array(aProduct, "classes"))
        {
            classes.add(productClass(clazz, path + "/classes[" + text(clazz, "name") + "]"));
        }
        List<StoredDataset> datasets = new ArrayList<>();
        for (JsonNode dataset : array(aProduct, "datasets"))
        {
            datasets.add(dataset(dataset, path + "/datasets[" + text(dataset, "name") + "]"));
        }
        List<StoredDataStructure> structures = new ArrayList<>();
        for (JsonNode structure : array(aProduct, "dataStructures"))
        {
            structures.add(dataStructure(structure,
                    path + "/dataStructures[" + text(structure, "name") + "]"));
        }
        List<StoredDomain> domains = new ArrayList<>();
        for (JsonNode domain : array(aProduct, "domains"))
        {
            domains.add(domain(domain, path + "/domains[" + text(domain, "name") + "]"));
        }
        return StoredProduct.builder().key(aKey).name(ptext(aProduct, "name", path))
                .label(ptext(aProduct, "label", path)).version(text(aProduct, "version"))
                .modelHref(text(aProduct.path("_links").path("model"), "href"))
                .description(ptext(aProduct, "description", path))
                .effectiveDate(text(aProduct, "effectiveDate"))
                .registrationStatus(text(aProduct, "registrationStatus"))
                .source(ptext(aProduct, "source", path)).classes(classes).datasets(datasets)
                .dataStructures(structures).domains(domains).build();
    }


    /**
     * The text fields this projection normalised ({@link StoreTextNormaliser}), as
     * {@code <product key>/<path>.<field>} site strings — what the errata register's
     * {@code normalised} entries assert against (an entry whose site was NOT normalised this run is
     * stale: the source no longer carries the defect). Product documents only; CT is never
     * normalised.
     *
     * @return the sites, in projection order
     */
    List<String> normalisedSites()
    {
        return List.copyOf(normalisedSites);
    }


    /**
     * How many fields each kind of normalisation changed, for the seed report.
     *
     * @return the counts by kind
     */
    Map<StoreTextNormaliser.Kind, Integer> normalisationCounts()
    {
        return Map.copyOf(normalisationCounts);
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
                text(aCodelist, "conceptId"), text(aCodelist, "name"),
                text(aCodelist, "preferredTerm"), text(aCodelist, "definition"),
                stringList(aCodelist, "synonyms"), extensible(aCodelist), terms);
        return codelistPool.computeIfAbsent(projected, c -> c);
    }


    private StoredClass productClass(JsonNode aClass, String aPath)
    {
        List<StoredVariable> classVariables = new ArrayList<>();
        for (JsonNode variable : array(aClass, "classVariables"))
        {
            classVariables.add(
                    variable(variable, aPath + "/classVariables[" + text(variable, "name") + "]"));
        }
        List<StoredDataset> datasets = new ArrayList<>();
        for (JsonNode dataset : array(aClass, "datasets"))
        {
            datasets.add(dataset(dataset, aPath + "/datasets[" + text(dataset, "name") + "]"));
        }
        List<StoredDomain> domains = new ArrayList<>();
        for (JsonNode domain : array(aClass, "domains"))
        {
            domains.add(domain(domain, aPath + "/domains[" + text(domain, "name") + "]"));
        }
        List<StoredScenario> scenarios = new ArrayList<>();
        for (JsonNode scenario : array(aClass, "scenarios"))
        {
            scenarios.add(
                    scenario(scenario, aPath + "/scenarios[" + text(scenario, "scenario") + "]"));
        }
        List<StoredField> modelFields = new ArrayList<>();
        for (JsonNode field : array(aClass, "cdashModelFields"))
        {
            modelFields.add(field(field, aPath + "/cdashModelFields[" + text(field, "name") + "]"));
        }
        return new StoredClass(ptext(aClass, "name", aPath), ptext(aClass, "label", aPath),
                text(aClass, "ordinal"), classVariables, datasets,
                ptext(aClass, "description", aPath), domains, scenarios, modelFields);
    }


    /** A CDASH domain (a CDASH IG class's or a CDASH model's {@code domains[]} entry). */
    private StoredDomain domain(JsonNode aDomain, String aPath)
    {
        List<StoredField> fields = new ArrayList<>();
        for (JsonNode field : array(aDomain, "fields"))
        {
            fields.add(field(field, aPath + "/fields[" + text(field, "name") + "]"));
        }
        return new StoredDomain(ptext(aDomain, "name", aPath), ptext(aDomain, "label", aPath),
                text(aDomain, "ordinal"), ptext(aDomain, "description", aPath), fields);
    }


    /** A CDASH IG scenario (a class's {@code scenarios[]} entry). */
    private StoredScenario scenario(JsonNode aScenario, String aPath)
    {
        List<StoredField> fields = new ArrayList<>();
        for (JsonNode field : array(aScenario, "fields"))
        {
            fields.add(field(field, aPath + "/fields[" + text(field, "name") + "]"));
        }
        return new StoredScenario(text(aScenario, "scenario"), text(aScenario, "domain"),
                text(aScenario, "domainName"), text(aScenario, "ordinal"), fields);
    }


    /**
     * A CDASH field — the scalar union of domain, scenario and model-class fields, plus its
     * {@code _links.codelist} C-codes through the same {@link #codelistIds(JsonNode)} the variables
     * use (format 4; T1-9 extended by the owner 2026-09-28). Every CDASH field level goes through
     * here, so none can diverge.
     */
    private StoredField field(JsonNode aField, String aPath)
    {
        return StoredField.builder().name(ptext(aField, "name", aPath))
                .label(ptext(aField, "label", aPath)).ordinal(text(aField, "ordinal"))
                .core(text(aField, "core")).definition(ptext(aField, "definition", aPath))
                .simpleDatatype(text(aField, "simpleDatatype"))
                .codelistSubmissionValues(stringList(aField, "codelistSubmissionValues"))
                .completionInstructions(ptext(aField, "completionInstructions", aPath))
                .implementationNotes(ptext(aField, "implementationNotes", aPath))
                .mappingInstructions(ptext(aField, "mappingInstructions", aPath))
                .prompt(ptext(aField, "prompt", aPath))
                .questionText(ptext(aField, "questionText", aPath))
                .domainSpecific(text(aField, "domainSpecific")).codelistIds(codelistIds(aField))
                .build();
    }


    private StoredDataset dataset(JsonNode aDataset, String aPath)
    {
        List<StoredVariable> variables = new ArrayList<>();
        for (JsonNode variable : array(aDataset, "datasetVariables"))
        {
            variables.add(variable(variable, aPath + "/variables[" + text(variable, "name") + "]"));
        }
        return new StoredDataset(ptext(aDataset, "name", aPath), ptext(aDataset, "label", aPath),
                text(aDataset, "ordinal"), ptext(aDataset, "datasetStructure", aPath), variables,
                ptext(aDataset, "description", aPath), text(aDataset, "status"));
    }


    private StoredDataStructure dataStructure(JsonNode aStructure, String aPath)
    {
        List<StoredVariableSet> sets = new ArrayList<>();
        for (JsonNode set : array(aStructure, "analysisVariableSets"))
        {
            String setPath = aPath + "/variableSets[" + text(set, "name") + "]";
            List<StoredVariable> variables = new ArrayList<>();
            for (JsonNode variable : array(set, "analysisVariables"))
            {
                variables.add(
                        variable(variable, setPath + "/variables[" + text(variable, "name") + "]"));
            }
            sets.add(
                    new StoredVariableSet(ptext(set, "name", setPath), ptext(set, "label", setPath),
                            text(set, "ordinal"), variables, ptext(set, "description", setPath)));
        }
        return new StoredDataStructure(ptext(aStructure, "name", aPath),
                ptext(aStructure, "label", aPath), text(aStructure, "ordinal"),
                text(aStructure, "class"), text(aStructure, "subClass"), sets,
                ptext(aStructure, "description", aPath));
    }


    private StoredVariable variable(JsonNode aVariable, String aPath)
    {
        StoredVariable.StoredVariableBuilder builder = StoredVariable.builder()
                .name(ptext(aVariable, "name", aPath)).label(ptext(aVariable, "label", aPath))
                .ordinal(text(aVariable, "ordinal")).core(text(aVariable, "core"))
                .role(text(aVariable, "role")).simpleDatatype(text(aVariable, "simpleDatatype"))
                .description(ptext(aVariable, "description", aPath))
                .valueList(stringList(aVariable, "valueList"))
                .describedValueDomain(ptext(aVariable, "describedValueDomain", aPath))
                .codelistSubmissionValues(stringList(aVariable, "codelistSubmissionValues"))
                .roleDescription(ptext(aVariable, "roleDescription", aPath))
                .definition(ptext(aVariable, "definition", aPath))
                .notes(ptext(aVariable, "notes", aPath))
                .examples(ptext(aVariable, "examples", aPath))
                .usageRestrictions(ptext(aVariable, "usageRestrictions", aPath))
                .variableCcode(text(aVariable, "variableCcode"));
        return builder.codelistIds(codelistIds(aVariable)).build();
    }


    /**
     * A PRODUCT text field: {@link #text} plus {@link StoreTextNormaliser}, with the site recorded
     * when the value changed. Structural scalars (ordinal, core, role, type, status, hrefs) and
     * every CT field keep the plain reader.
     */
    private @Nullable String ptext(JsonNode aNode, String aField, String aPath)
    {
        String raw = text(aNode, aField);
        StoreTextNormaliser.Result result = StoreTextNormaliser.normalise(aField, raw);
        if (!result.kinds().isEmpty())
        {
            normalisedSites.add(aPath + "." + aField);
            for (StoreTextNormaliser.Kind kind : result.kinds())
            {
                normalisationCounts.merge(kind, 1, Integer::sum);
            }
        }
        return result.value();
    }


    /**
     * The codelist refs of a variable or a CDASH field — {@code _links.codelist} is an ARRAY (31
     * real variables carry 2–5 refs, audit §3 correction; 121 real CDASH fields carry more than
     * one, 2026-09-28), each ref's id being the trailing segment of its href
     * ({@code /mdr/root/ct/sdtmct/codelists/C66731} → {@code C66731}). A lone object is accepted
     * too, defensively. Absent link ⇒ {@code null}, matching every other unpublished field.
     */
    private static @Nullable List<String> codelistIds(JsonNode anElement)
    {
        JsonNode link = anElement.path("_links").path("codelist");
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
