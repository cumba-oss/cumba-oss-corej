package net.cumba.corej.core.metadata.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * {@code PLAN-send-base-ig-view} ({@code LVC-F1} (b)) — the base-IG view: a SENDIG-AR or
 * SENDIG-DART product plus the datasets of the base SENDIG that the product does not print.
 *
 * <p>
 * SENDIG-AR 1.0 and SENDIG-DART 1.1 / 1.2 print only the domains they introduce or change; each
 * document says the SENDIG domains it does not print are used unchanged, "according to the rules of
 * those domains" (SENDIG-AR §1.4, SENDIG-DART §1.3). Without the view the engine judged those
 * datasets against the SDTM model of their class (name, type and role only). With it, an AR / DART
 * run reads them from the base SENDIG the document names ({@code B-BASE}, owner 2026-10-15): label,
 * controlled terminology, core, variable order and dataset label.
 * </p>
 *
 * <ul>
 * <li><b>Whole datasets, domain-level precedence</b> ({@code B-GRAIN}). A domain the run's IG
 * defines is answered only by the run's IG (a DART-revised {@code BW} keeps DART's specification);
 * a domain it lacks is answered only by the base. Never a per-variable merge. {@code SUPPQUAL}
 * comes in like any other dataset ({@code B-SUPP}: SENDIG-AR prints none).</li>
 * <li><b>Ordinals.</b> The products' ordinals collide (AR's datasets are 1–8, the base's 1–30;
 * class 7 is Trial Design in AR and Relationship in the base). The composed product keeps the IG's
 * own ordinals and renumbers every added dataset, and every added class, to follow the IG's highest
 * — base order kept among them. A dataset joins the IG class of the same name; a base class the IG
 * lacks (AR has no Relationship class) is added after the IG's own classes.</li>
 * <li><b>Identity.</b> The composed product keeps the IG's key, name, version and
 * {@code modelHref}: the run is still an AR / DART run, and the carried datasets are walked against
 * the run's own model (SENDIG-AR §1.4: "based on the versions of the standards noted above").</li>
 * <li><b>Scope</b> ({@code B-SCOPE}): exactly the three products of {@link #BASE_IG}. The store is
 * not changed ({@code B-WHERE}): the view is composed at run time, once per provider.</li>
 * <li><b>One seam, three readers</b> ({@code PLAN-bugfix-engine-define} SV, T1). Every reader of a
 * stored IG product composes through this class: the data side
 * ({@code StoreMetadataProviderFactory.withBaseIgView}, which calls {@link #compose} itself because
 * it logs the {@link Composition}'s added datasets), the Define-XML side
 * ({@code StoreLibraryProvider}, through {@link #productOf}) and the rule editor's scope universe
 * ({@code RuleInsight.scopeUniverse}, through {@link #productOf}). Before 2026-10-16 only the data
 * side composed, so a define binding {@code LB} to SENDIG-AR 1.0 asked the library for a dataset
 * the raw product does not print and got empty answers.</li>
 * </ul>
 */
public final class BaseIgView
{

    /**
     * Run IG key → base IG key ({@code B-SCOPE}; values per {@code B-BASE}, ruled 2026-10-15: the
     * base each document names — SENDIG-AR 1.0 and SENDIG-DART 1.1 name SENDIG v3.1, SENDIG-DART
     * 1.2 names SENDIG v3.1.1).
     */
    static final Map<String, String> BASE_IG = Map.of("standards/sendig/ar-1-0",
            "standards/sendig/3-1", "standards/sendig/dart-1-1", "standards/sendig/3-1",
            "standards/sendig/dart-1-2", "standards/sendig/3-1-1");

    private BaseIgView()
    {
    }

    /**
     * The composed product and what it took from the base.
     *
     * @param product
     *            the IG with the base's missing datasets added (the IG itself when none is missing)
     * @param baseKey
     *            the base product's store key
     * @param addedDatasets
     *            the names of the datasets taken from the base, in their composed order
     */
    record Composition(StoredProduct product, String baseKey, List<String> addedDatasets)
    {

        /** Defensive copy. */
        Composition
        {
            addedDatasets = List.copyOf(addedDatasets);
        }
    }

    /**
     * The base IG key the run's IG reads its missing datasets from, or {@code null} when the run is
     * outside {@code B-SCOPE}.
     */
    static @Nullable String baseKeyFor(@Nullable String aIgKey)
    {
        return aIgKey == null ? null : BASE_IG.get(aIgKey);
    }


    /**
     * The stored product under {@code aKey}, composed with its base when {@code aKey} is a
     * {@link #BASE_IG} run IG and the store holds the base; the stored product as is when the key
     * is outside {@code B-SCOPE} or the base is missing (the data side logs that case; a reader
     * that needs to record it asks {@link #baseKeyFor} itself); empty when the store lacks
     * {@code aKey}. The one seam for readers that need only the product
     * ({@code PLAN-bugfix-engine-define} SV).
     *
     * @param aStore
     *            the metadata store
     * @param aKey
     *            a product key ({@code standards/sendig/ar-1-0})
     * @return the composed or stored product, or empty
     */
    public static Optional<StoredProduct> productOf(MetadataStore aStore, String aKey)
    {
        Optional<StoredProduct> stored = aStore.product(aKey);
        String baseKey = baseKeyFor(aKey);
        if (stored.isEmpty() || baseKey == null)
        {
            return stored;
        }
        return Optional.of(aStore.product(baseKey)
                .map(base -> compose(stored.get(), base).product()).orElse(stored.get()));
    }


    /**
     * Composes the view: {@code aIg} plus every dataset of {@code aBase} whose name {@code aIg}
     * does not define, re-ordinalled after {@code aIg}'s own.
     *
     * @param aIg
     *            the run's IG product
     * @param aBase
     *            the base SENDIG product
     * @return the composition; its product is {@code aIg} itself when the base adds nothing
     */
    static Composition compose(StoredProduct aIg, StoredProduct aBase)
    {
        Set<String> igNames = new HashSet<>();
        int maxDatasetOrdinal = 0;
        int maxClassOrdinal = 0;
        for (StoredClass klass : aIg.classes())
        {
            maxClassOrdinal = Math.max(maxClassOrdinal, ordinal(klass.ordinal()));
            for (StoredDataset ds : klass.datasets())
            {
                igNames.add(upper(ds.name()));
                maxDatasetOrdinal = Math.max(maxDatasetOrdinal, ordinal(ds.ordinal()));
            }
        }

        // The base's missing datasets, grouped by their base class, both in base ordinal order;
        // each renumbered after the IG's highest dataset ordinal.
        List<StoredClass> baseClasses = new ArrayList<>(aBase.classes());
        baseClasses.sort(Comparator.comparingInt(c -> ordinal(c.ordinal())));
        // Index-keyed, not record-keyed: a StoredClass hashes its whole dataset tree.
        List<StoredClass> addedClasses = new ArrayList<>();
        List<List<StoredDataset>> addedByClass = new ArrayList<>();
        List<String> added = new ArrayList<>();
        int nextDataset = maxDatasetOrdinal;
        for (StoredClass klass : baseClasses)
        {
            List<StoredDataset> datasets = new ArrayList<>(klass.datasets());
            datasets.sort(Comparator.comparingInt(d -> ordinal(d.ordinal())));
            List<StoredDataset> fromThisClass = new ArrayList<>();
            for (StoredDataset ds : datasets)
            {
                if (ds.name() == null || igNames.contains(upper(ds.name())))
                {
                    continue;
                }
                nextDataset++;
                fromThisClass.add(new StoredDataset(ds.name(), ds.label(),
                        Integer.toString(nextDataset), ds.datasetStructure(), ds.variables(),
                        ds.description(), ds.status()));
                added.add(ds.name());
            }
            if (!fromThisClass.isEmpty())
            {
                addedClasses.add(klass);
                addedByClass.add(fromThisClass);
            }
        }
        if (added.isEmpty())
        {
            return new Composition(aIg, aBase.key(), List.of());
        }

        List<StoredClass> classes = new ArrayList<>();
        boolean[] placed = new boolean[addedClasses.size()];
        for (StoredClass klass : aIg.classes())
        {
            List<StoredDataset> datasets = new ArrayList<>(klass.datasets());
            for (int i = 0; i < addedClasses.size(); i++)
            {
                if (sameClass(klass, addedClasses.get(i)))
                {
                    datasets.addAll(addedByClass.get(i));
                    placed[i] = true;
                }
            }
            classes.add(datasets.size() == klass.datasets().size() ? klass
                    : withDatasets(klass, klass.ordinal(), datasets));
        }
        int nextClass = maxClassOrdinal;
        for (int i = 0; i < addedClasses.size(); i++)
        {
            if (!placed[i])
            {
                nextClass++;
                classes.add(withDatasets(addedClasses.get(i), Integer.toString(nextClass),
                        addedByClass.get(i)));
            }
        }
        StoredProduct product = new StoredProduct(aIg.key(), aIg.name(), aIg.label(), aIg.version(),
                aIg.modelHref(), aIg.description(), aIg.effectiveDate(), aIg.registrationStatus(),
                aIg.source(), classes, aIg.datasets(), aIg.dataStructures(), aIg.domains());
        return new Composition(product, aBase.key(), added);
    }


    private static StoredClass withDatasets(StoredClass aClass, @Nullable String aOrdinal,
            List<StoredDataset> aDatasets)
    {
        return new StoredClass(aClass.name(), aClass.label(), aOrdinal, aClass.classVariables(),
                aDatasets, aClass.description(), aClass.domains(), aClass.scenarios(),
                aClass.cdashModelFields());
    }


    /** Class identity by name, ignoring case and punctuation ({@code Special-Purpose}). */
    private static boolean sameClass(StoredClass aLeft, StoredClass aRight)
    {
        return letters(aLeft.name()).equals(letters(aRight.name()));
    }


    private static String letters(@Nullable String aName)
    {
        return aName == null ? "" : upper(aName).replaceAll("[^A-Z]", "");
    }


    private static String upper(@Nullable String aName)
    {
        return aName == null ? "" : aName.toUpperCase(Locale.ROOT);
    }


    /** A stored ordinal as a number; absent or non-numeric ordinals count as 0. */
    private static int ordinal(@Nullable String aOrdinal)
    {
        if (aOrdinal == null)
        {
            return 0;
        }
        try
        {
            return Integer.parseInt(aOrdinal.trim());
        }
        catch (NumberFormatException e)
        {
            return 0;
        }
    }
}
