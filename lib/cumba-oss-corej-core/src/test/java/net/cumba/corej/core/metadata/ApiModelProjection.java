package net.cumba.corej.core.metadata;

import java.util.ArrayList;
import java.util.List;
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
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredCodelist;
import net.cumba.corej.core.metadata.store.StoredCtPackage;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredTerm;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import net.cumba.web.api.Link;
import org.jspecify.annotations.Nullable;

/**
 * One-way projection of the CDISC Library api-model views onto the unified metadata store's own
 * records (cache plan §4.3.1, P3) — originally the seam that let {@link MetadataLibraryProvider}
 * and {@link CdiscLibraryMetadataLibrary} be typed on {@link StoredProduct} /
 * {@link StoredCtPackage} while the legacy sources were retained. Cache 8g deleted those sources;
 * this class was demoted to a TEST fixture (the {@code PickleCacheSeeder} precedent) because the
 * behavioural tests build their product fixtures as api-model maps, and it projects them at the
 * fixture boundary via {@link ApiModelLibraries}.
 *
 * <p>
 * ⚠ This is deliberately NOT rehydration (the rejected §4.3.1 option A would have built api-model
 * views over store content). The direction here is the opposite and terminal: api-model objects are
 * converted <em>into</em> the store's records at the fixture boundary, and everything downstream
 * reads only the store types.
 * </p>
 *
 * <p>
 * ⚠ The projection is <b>behaviour-preserving, not store-complete</b>: it carries exactly the
 * fields the engine reads today, so a provider built through it answers byte-for-byte what the
 * api-model-typed code answered. In particular {@code codelistIds} keeps the api-model's
 * first-link-only semantics ({@code ApiResource.getLink} returns the first entry of a link array),
 * and CT {@code definition}/{@code synonyms} — stored by the seeders, reachable by nothing — are
 * not projected. The full-fidelity source-JSON projection is {@code store.seed.StoreProjection}; do
 * not conflate the two.
 * </p>
 */
public final class ApiModelProjection
{

    private ApiModelProjection()
    {
    }


    /**
     * Projects an SDTM-family product (IG or Model). IG products fill {@code classes} (with nested
     * datasets); Model products additionally publish top-level {@code datasets}. Both slots are
     * projected from whatever the source carries, mirroring how the api-model view answers.
     *
     * @param aProduct
     *            the api-model product
     * @return the stored form (key is the empty string — the api-model view carries no store key,
     *         and nothing built from this projection reads one)
     */
    public static StoredProduct product(SdtmProduct aProduct)
    {
        List<StoredClass> classes = new ArrayList<>();
        for (SdtmClass klass : aProduct.classes())
        {
            classes.add(sdtmClass(klass));
        }
        List<StoredDataset> datasets = new ArrayList<>();
        for (SdtmDataset dataset : aProduct.datasets())
        {
            datasets.add(sdtmDataset(dataset));
        }
        return StoredProduct.builder().key("").name(aProduct.name().orElse(null))
                .label(aProduct.label().orElse(null)).version(aProduct.version().orElse(null))
                .modelHref(aProduct.modelLink().flatMap(Link::href).orElse(null)).classes(classes)
                .datasets(datasets).dataStructures(List.of()).build();
    }


    /**
     * Projects an ADaM-family product: {@code dataStructures} with their variable sets.
     *
     * @param aProduct
     *            the api-model product
     * @return the stored form (key {@code ""}, see {@link #product(SdtmProduct)})
     */
    public static StoredProduct product(AdamProduct aProduct)
    {
        List<StoredDataStructure> structures = new ArrayList<>();
        for (AdamDataStructure ds : aProduct.dataStructures())
        {
            structures.add(dataStructure(ds));
        }
        return StoredProduct.builder().key("").name(aProduct.name().orElse(null))
                .label(aProduct.label().orElse(null)).version(aProduct.version().orElse(null))
                .classes(List.of()).datasets(List.of()).dataStructures(structures).build();
    }


    /**
     * Projects a CT package, paired with the id it was requested under — {@code CtPackage.name()}
     * is the API's display label, never the id (see {@link CtPackageRef}), so the id must come from
     * the caller.
     *
     * @param aId
     *            the id the package was requested under; {@code null} for the anonymous empty
     *            package of the no-CT path
     * @param aPackage
     *            the api-model package
     * @return the stored form, codelists and terms in the source's own order
     */
    public static StoredCtPackage ctPackage(@Nullable String aId, CtPackage aPackage)
    {
        List<StoredCodelist> codelists = new ArrayList<>();
        for (CtCodelist codelist : aPackage.codelists())
        {
            codelists.add(codelist(codelist));
        }
        return new StoredCtPackage(aId, codelists);
    }


    private static StoredCodelist codelist(CtCodelist aCodelist)
    {
        List<StoredTerm> terms = new ArrayList<>();
        for (CtTerm term : aCodelist.terms())
        {
            terms.add(new StoredTerm(term.submissionValue().orElse(null),
                    term.conceptId().orElse(null), term.preferredTerm().orElse(null), null, null));
        }
        return new StoredCodelist(aCodelist.submissionValue().orElse(null),
                aCodelist.conceptId().orElse(null), aCodelist.preferredTerm().orElse(null), null,
                null, aCodelist.extensible().orElse(null), terms);
    }


    private static StoredClass sdtmClass(SdtmClass aClass)
    {
        List<StoredVariable> classVariables = new ArrayList<>();
        for (SdtmVariable variable : aClass.classVariables())
        {
            classVariables.add(variable(variable));
        }
        List<StoredDataset> datasets = new ArrayList<>();
        for (SdtmDataset dataset : aClass.datasets())
        {
            datasets.add(sdtmDataset(dataset));
        }
        return new StoredClass(aClass.name().orElse(null), aClass.label().orElse(null),
                aClass.ordinal().orElse(null), classVariables, datasets);
    }


    private static StoredDataset sdtmDataset(SdtmDataset aDataset)
    {
        List<StoredVariable> variables = new ArrayList<>();
        for (SdtmVariable variable : aDataset.datasetVariables())
        {
            variables.add(variable(variable));
        }
        return new StoredDataset(aDataset.name().orElse(null), aDataset.label().orElse(null),
                aDataset.ordinal().orElse(null), aDataset.datasetStructure().orElse(null),
                variables);
    }


    private static StoredDataStructure dataStructure(AdamDataStructure aStructure)
    {
        List<StoredVariableSet> sets = new ArrayList<>();
        for (AdamVariableSet set : aStructure.analysisVariableSets())
        {
            List<StoredVariable> variables = new ArrayList<>();
            for (AdamVariable variable : set.analysisVariables())
            {
                variables.add(variable(variable));
            }
            sets.add(new StoredVariableSet(set.name().orElse(null), set.label().orElse(null),
                    set.ordinal().orElse(null), variables));
        }
        return new StoredDataStructure(aStructure.name().orElse(null),
                aStructure.label().orElse(null), aStructure.ordinal().orElse(null),
                aStructure.className().orElse(null), aStructure.subClass().orElse(null), sets);
    }


    /**
     * ⚠ Carries <b>every</b> variable field the api-model view exposes, not the six the resolver
     * used to read: {@code key_name} filters over library variables by an arbitrary caller-supplied
     * key (PLAN-library-variable-key-name-breadth.md), so a field dropped here is a rule that
     * silently matches nothing on any deployment still reading the pickle cache.
     *
     * <p>
     * ⚠⚠ Three fields the real payloads carry have <b>no typed accessor</b> on {@code SdtmVariable}
     * ({@code cumba-cdisc}, another repository): {@code definition} (341 real occurrences),
     * {@code variableCcode} (331) and {@code codelistSubmissionValues} (1 637). They are read here
     * through {@code ApiResource}'s untyped {@code getString} / {@code getStringList} — the same
     * JSON, one indirection lower — because the alternative is a provider that answers differently
     * depending on which cache it was built from, which is exactly what
     * {@code StoreVsPickleProviderEquivalenceTest} exists to forbid. ⭐ Adding the accessors to the
     * api-model interface is the tidier fix and belongs to that repository.
     * </p>
     */
    private static StoredVariable variable(SdtmVariable aVariable)
    {
        return StoredVariable.builder().name(aVariable.name().orElse(null))
                .label(aVariable.label().orElse(null)).ordinal(aVariable.ordinal().orElse(null))
                .core(aVariable.core().orElse(null)).role(aVariable.role().orElse(null))
                .simpleDatatype(aVariable.simpleDatatype().orElse(null))
                .description(aVariable.description().orElse(null))
                .roleDescription(aVariable.roleDescription().orElse(null))
                .notes(aVariable.notes().orElse(null)).examples(aVariable.examples().orElse(null))
                .usageRestrictions(aVariable.usageRestrictions().orElse(null))
                .describedValueDomain(aVariable.describedValueDomain().orElse(null))
                .valueList(nullIfEmpty(aVariable.valueList()))
                .definition(aVariable.getString("definition").orElse(null))
                .variableCcode(aVariable.getString("variableCcode").orElse(null))
                .codelistSubmissionValues(
                        nullIfEmpty(aVariable.getStringList("codelistSubmissionValues")))
                .codelistIds(codelistIds(aVariable.codelistLink())).build();
    }


    /**
     * An api-model string list as the store holds it: the api view collapses "absent" and "empty"
     * onto an empty list, and the store's convention is {@code null} for "the source did not
     * publish this" (an empty list would mean "published, and empty").
     */
    private static @Nullable List<String> nullIfEmpty(List<String> aValues)
    {
        return aValues.isEmpty() ? null : List.copyOf(aValues);
    }


    private static StoredVariable variable(AdamVariable aVariable)
    {
        // No role: the api-model AdamVariable publishes none, and the ADaM builders deliberately
        // leave it absent (see CdiscLibraryMetadataLibrary.buildAdamTable). `describedValueDomain`
        // and `codelistSubmissionValues` have no typed accessor here either — same untyped read,
        // and the same reason, as the SDTM variant above.
        return StoredVariable.builder().name(aVariable.name().orElse(null))
                .label(aVariable.label().orElse(null)).ordinal(aVariable.ordinal().orElse(null))
                .core(aVariable.core().orElse(null))
                .simpleDatatype(aVariable.simpleDatatype().orElse(null))
                .description(aVariable.description().orElse(null))
                .valueList(nullIfEmpty(aVariable.valueList()))
                .describedValueDomain(aVariable.getString("describedValueDomain").orElse(null))
                .codelistSubmissionValues(
                        nullIfEmpty(aVariable.getStringList("codelistSubmissionValues")))
                .codelistIds(codelistIds(aVariable.codelistLink())).build();
    }


    private static @Nullable List<String> codelistIds(java.util.Optional<Link> aLink)
    {
        return aLink.flatMap(Link::id).map(List::of).orElse(null);
    }
}
