package net.cumba.corej.core.metadata;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The one vocabulary of attribute keys a <b>library variable row</b> can carry, shared by the rule
 * loader (which rejects a {@code key_name} outside it) and the resolver that populates the rows —
 * so that what an author may filter on and what the engine actually publishes cannot drift apart.
 *
 * <p>
 * A library variable is handed to the filter operations as a {@code Map<String, String>} — the
 * Python {@code variables_metadata} shape documented on
 * {@link net.cumba.corej.core.exec.MetadataProvider#getStandardModelVariablesDetailed}. Every
 * producer in the engine populates it from the same closed set:
 * </p>
 * <ul>
 * <li>{@code MetadataLibraryProvider.scalarAttributes} — the one row builder, fed by
 * {@code substituteAndResolve} (the SDTM walks, both levels) and by {@code buildResolvedAdam} (the
 * ADaM walk, which then removes {@code role}: ADaM payloads publish none). It publishes every
 * <em>scalar</em> field {@code StoredVariable} holds, and only the fields the source
 * populated;</li>
 * <li>{@code MetadataLibraryProvider.relationshipFallbackVar} (the tier-C SUPPQUAL fallback) — a
 * hard-coded subset: {@code role}, {@code core}, {@code ordinal};</li>
 * <li>{@code ResolvedVariable.toAttributeMap} adds the {@code --}-substituted {@code name}.</li>
 * </ul>
 *
 * <h2>⛔ What is deliberately outside the vocabulary, and why</h2>
 *
 * <p>
 * {@code StoredVariable} additionally holds three <b>list-valued</b> fields — {@code valueList},
 * {@code codelistSubmissionValues} and {@code codelistIds} (coreJ's reduction of the payload's
 * {@code _links.codelist} array). None of them is a row key, and a {@code key_name} naming one is a
 * <b>load error</b>. The row is {@code Map<String, String>} and {@code key_value} is a scalar; the
 * Python engine compares {@code var.get(key_name) == key_value} against the raw payload object, so
 * {@code var.get("valueList") == "DM"} compares a list to a string and is <em>always</em> false.
 * Serving them could only ever be a no-op wearing a costume, and any encoding
 * ({@code DefineMetadataListCodec} or otherwise) would invent comparison semantics Python does not
 * have. A load error turns a silent no-op into a loud one. (P2 ruling, 2026-09-08.)
 * </p>
 *
 * <p>
 * ⚠ {@code examples} is <b>not</b> in that group, though the audit and the plan both listed it
 * there: measured over the whole real cache it is a scalar string in all 260 occurrences — see
 * {@link net.cumba.corej.core.metadata.store.StoredVariable}.
 * </p>
 *
 * <h2>⚠⚠ Membership here is necessary, not sufficient</h2>
 *
 * <p>
 * Whether a given row actually carries a key is <em>level- and dataset-dependent</em>, and cannot
 * be decided at load time:
 * </p>
 * <ul>
 * <li>{@code core} is <b>not</b> published by a standard SDTM domain's pure-Model walk
 * ({@code buildResolvedSdtmModel}), but <b>is</b> published by the IG-level walk
 * ({@code buildResolvedSdtm}), by the SUPP--/SQ-- cascade (whose tier A projects IG
 * {@code SUPPQUAL} dataset variables and whose tier C is the hard-coded RELATIONSHIP fallback
 * above), and by every ADaM dataset. So {@code get_model_filtered_variables(key_name="core")} is
 * inert on a standard SDTM domain and correct on a SUPP domain — which is why it is a runtime
 * diagnostic ({@code OperationExecutor.warnUnservedKeyName}) and not a load error.</li>
 * <li>{@code role} is symmetrically absent from every ADaM row.</li>
 * </ul>
 *
 * <h2>⛔ There is ONE vocabulary, not one per operation</h2>
 *
 * <p>
 * The plan asked for a per-level split — the model-product keys legal only on
 * {@code get_model_filtered_variables}, the IG-product keys only on
 * {@code get_dataset_filtered_variables}. <b>Measured, that split does not exist</b>, and encoding
 * it would forbid rules the engine demonstrably serves. Neither walk is confined to one product:
 * </p>
 * <ul>
 * <li>the <em>Model</em> walk falls through to the IG dataset's variables for a non-detectable
 * domain whose Model class and Model dataset are both empty (tier 3 of
 * {@code buildResolvedSdtmModel}), routes every SUPP--/SQ-- table to the IG {@code SUPPQUAL}
 * dataset (cascade tier A), and answers ADaM datasets from the ADaM product — so the IG-and-ADaM
 * fields ({@code core}, {@code description}, {@code describedValueDomain}) all reach it;</li>
 * <li>the <em>IG</em> walk builds its identifier / class / timing buckets from the <em>Model</em>
 * product's {@code classVariables} and merely overrides them by name with the IG dataset's
 * ({@code mergeIgOverride}); a model variable the IG dataset does not carry survives with its model
 * attributes, and a <em>custom</em> domain skips the IG merge entirely — so the model-only fields
 * ({@code notes}, {@code definition}, {@code examples}, {@code roleDescription},
 * {@code usageRestrictions}, {@code variableCcode}) all reach it too.</li>
 * </ul>
 *
 * <p>
 * ⭐ So the servable set is per-<em>dataset</em>, not per-operation, and the union is the same on
 * both filters. {@code LibraryVariableRowBreadthTest} pins both directions against the real
 * resolver, so a later "tightening" into two sets reds instead of silently refusing working rules.
 * </p>
 *
 * <p>
 * What <b>is</b> decidable at load is the complement: a key outside this set — the three
 * list-valued fields above, or a typo — is published by <em>no</em> level, so the filter provably
 * matches nothing on every dataset. That is {@code OperationExpressionParser.validateKeyName}.
 * </p>
 */
public final class LibraryVariableAttributes
{

    /**
     * The closed set of attribute keys a library variable row may carry: the identifying
     * {@code name}, the five original attributes, and the eight scalar fields
     * PLAN-library-variable-key-name-breadth.md P3 added. Kept in sync with
     * {@code MetadataLibraryProvider.scalarAttributes} by
     * {@code LibraryVariableRowBreadthTest.theVocabularyIsExactlyWhatTheResolverPublishes}.
     */
    public static final Set<String> KEYS = Set.of("name", "role", "core", "simpleDatatype", "label",
            "ordinal", "description", "roleDescription", "definition", "notes", "examples",
            "usageRestrictions", "variableCcode", "describedValueDomain");

    private LibraryVariableAttributes()
    {
    }


    /**
     * Returns {@code true} when at least one of the resolved variable rows carries the given
     * attribute key. Used by the runtime diagnostic to tell "the filter matched nothing" (the key
     * is served, no variable has that value) apart from "the level cannot serve this key at all"
     * (the FDA-SD1078 shape).
     *
     * @param aRows
     *            the resolved variable rows, as handed to the filter
     * @param aKey
     *            the {@code key_name} the rule declared
     * @return whether any row publishes {@code aKey}
     */
    public static boolean carriedByAny(List<Map<String, String>> aRows, String aKey)
    {
        for (Map<String, String> row : aRows)
        {
            if (row.containsKey(aKey))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Returns the union of the attribute keys the given rows actually publish, sorted, for a
     * diagnostic that has to tell the author what this level <em>does</em> serve.
     *
     * @param aRows
     *            the resolved variable rows
     * @return the sorted union of their key sets; empty when there are no rows
     */
    public static Set<String> publishedKeys(List<Map<String, String>> aRows)
    {
        Set<String> out = new TreeSet<>();
        for (Map<String, String> row : aRows)
        {
            out.addAll(row.keySet());
        }
        return new LinkedHashSet<>(out);
    }

}
