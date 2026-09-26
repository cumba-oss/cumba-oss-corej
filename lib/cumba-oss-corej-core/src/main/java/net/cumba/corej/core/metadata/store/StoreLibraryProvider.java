package net.cumba.corej.core.metadata.store;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListSet;
import net.cumba.corej.define.conformance.library.LibraryProvider;
import org.jspecify.annotations.Nullable;

/**
 * A {@link LibraryProvider} over the unified metadata store's IG and model products
 * (PLAN-define-ct-evaluation §4.4) — the one library binding on every surface (T1-7, T1-8): no API
 * key, no network, the same store the dataset run reads.
 *
 * <p>
 * <b>Coverage (D-10):</b> the SDTMIG/SENDIG families and their variants only, as the API provider
 * it replaced; an ADaM (or any other) standard answers empty and is NOT recorded as a miss. <b>Keys
 * (D-11):</b> the document's standard name is folded hyphen-insensitively ({@code SDTMIG},
 * {@code SDTM-IG}) and a variant maps to its real catalogue key — {@code SDTMIG-MD 1.1} →
 * {@code standards/sdtmig/md-1-1}, {@code SENDIG-AR 1.0} → {@code standards/sendig/ar-1-0},
 * likewise {@code -AP}, {@code -DART}, {@code -GENETOX}. <b>Multi-codelist variables (D-12):</b>
 * {@link #variableCodelistCCodes} answers the whole {@code codelistIds} list;
 * {@link #variableCodelistCCode} its head. <b>Published versions (D-13):</b> from the store's
 * product catalogue, as of the seed. <b>Misses (D-14):</b> a product the store does not hold
 * answers empty AND is recorded, so the run's library-basis line can name it — the store
 * counterpart of the API provider's degraded-lookup list.
 * </p>
 */
public final class StoreLibraryProvider implements LibraryProvider
{

    private static final String STANDARDS = "standards/";

    private static final String MODELS = "models/";

    private static final String MDR = "/mdr/";

    private final MetadataStore store;

    private final Map<String, Optional<StoredProduct>> products = new ConcurrentHashMap<>();

    /** Store keys asked for and not held — sorted, for a deterministic basis line (D-14). */
    private final Set<String> missed = new ConcurrentSkipListSet<>();

    private StoreLibraryProvider(MetadataStore aStore)
    {
        store = aStore;
    }


    /** A provider over an open store. */
    public static StoreLibraryProvider over(MetadataStore aStore)
    {
        return new StoreLibraryProvider(aStore);
    }


    @Override
    public Optional<String> datasetLabel(String aStandardName, String aStandardVersion,
            String aDatasetName)
    {
        return igProduct(aStandardName, aStandardVersion).flatMap(p -> dataset(p, aDatasetName))
                .map(StoredDataset::label);
    }


    @Override
    public Optional<String> variableLabel(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        return variable(aStandardName, aStandardVersion, aDatasetName, aVariableName)
                .map(StoredVariable::label);
    }


    @Override
    public Optional<String> variableCodelistCCode(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        List<String> all = variableCodelistCCodes(aStandardName, aStandardVersion, aDatasetName,
                aVariableName);
        return all.isEmpty() ? Optional.empty() : Optional.of(all.get(0));
    }


    @Override
    public List<String> variableCodelistCCodes(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        List<String> ids = variable(aStandardName, aStandardVersion, aDatasetName, aVariableName)
                .map(StoredVariable::codelistIds).orElse(null);
        return ids == null ? List.of() : ids;
    }


    @Override
    public Optional<String> variableCoreDesignation(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        return variable(aStandardName, aStandardVersion, aDatasetName, aVariableName)
                .map(StoredVariable::core);
    }


    @Override
    public Optional<String> qualifierVariableLabel(String aStandardName, String aStandardVersion,
            String aFragment)
    {
        Optional<StoredProduct> model = igProduct(aStandardName, aStandardVersion)
                .map(StoredProduct::modelHref).flatMap(this::modelProduct);
        if (model.isEmpty())
        {
            return Optional.empty();
        }
        String wanted = "--" + aFragment;
        for (StoredClass clazz : model.get().classes())
        {
            String name = clazz.name();
            if (name == null
                    || !(name.equalsIgnoreCase("Events") || name.equalsIgnoreCase("Interventions")))
            {
                continue;
            }
            for (StoredVariable variable : clazz.classVariables())
            {
                if (wanted.equals(variable.name()) && variable.label() != null)
                {
                    return Optional.of(variable.label());
                }
            }
        }
        return Optional.empty();
    }


    @Override
    public List<String> publishedStandardVersions(String aStandardName)
    {
        Family family = Family.of(aStandardName);
        if (family == null)
        {
            return List.of();
        }
        String prefix = STANDARDS + family.family + "/";
        List<String> versions = new ArrayList<>();
        for (String key : store.productCatalogue())
        {
            if (!key.startsWith(prefix))
            {
                continue;
            }
            String tail = key.substring(prefix.length());
            if (family.variant.isEmpty())
            {
                if (!tail.isEmpty() && Character.isDigit(tail.charAt(0)) && !tail.contains("/"))
                {
                    versions.add(tail.replace('-', '.'));
                }
            }
            else if (tail.startsWith(family.variant + "-"))
            {
                versions.add(tail.substring(family.variant.length() + 1).replace('-', '.'));
            }
        }
        return List.copyOf(versions);
    }


    /**
     * The store keys asked for and not held, sorted — for the run's library-basis line (D-14).
     * Meaningful after the engine ran; empty before.
     */
    public List<String> missedProducts()
    {
        return List.copyOf(missed);
    }


    /**
     * The catalogue key for an IG standard the document names, or empty for a standard outside the
     * SDTM/SEND families (D-10). Package-private for the key-grammar test (D-11).
     */
    static Optional<String> igKey(String aStandardName, String aStandardVersion)
    {
        Family family = Family.of(aStandardName);
        if (family == null || aStandardVersion == null || aStandardVersion.isBlank())
        {
            return Optional.empty();
        }
        String version = aStandardVersion.trim().replace('.', '-');
        String tail = family.variant.isEmpty() ? version : family.variant + "-" + version;
        return Optional.of(STANDARDS + family.family + "/" + tail);
    }


    private Optional<StoredProduct> igProduct(String aStandardName, String aStandardVersion)
    {
        return igKey(aStandardName, aStandardVersion).flatMap(this::product);
    }


    /** {@code /mdr/sdtm/2-0} → {@code models/sdtm/2-0}; empty for a null or foreign href. */
    private Optional<StoredProduct> modelProduct(@Nullable String aModelHref)
    {
        if (aModelHref == null || !aModelHref.startsWith(MDR))
        {
            return Optional.empty();
        }
        return product(MODELS + aModelHref.substring(MDR.length()).replaceAll("/+$", ""));
    }


    private Optional<StoredProduct> product(String aKey)
    {
        return products.computeIfAbsent(aKey, key ->
        {
            Optional<StoredProduct> held = store.product(key);
            if (held.isEmpty())
            {
                missed.add(key);
            }
            return held;
        });
    }


    private Optional<StoredVariable> variable(String aStandardName, String aStandardVersion,
            String aDatasetName, String aVariableName)
    {
        Optional<StoredDataset> dataset = igProduct(aStandardName, aStandardVersion)
                .flatMap(p -> dataset(p, aDatasetName));
        if (dataset.isEmpty())
        {
            return Optional.empty();
        }
        for (StoredVariable variable : dataset.get().variables())
        {
            if (aVariableName.equals(variable.name()))
            {
                return Optional.of(variable);
            }
        }
        return Optional.empty();
    }


    /** The IG's dataset by name: under a class first, then the product's top-level list. */
    private static Optional<StoredDataset> dataset(StoredProduct aProduct, String aDatasetName)
    {
        for (StoredClass clazz : aProduct.classes())
        {
            for (StoredDataset dataset : clazz.datasets())
            {
                if (aDatasetName.equals(dataset.name()))
                {
                    return Optional.of(dataset);
                }
            }
        }
        for (StoredDataset dataset : aProduct.datasets())
        {
            if (aDatasetName.equals(dataset.name()))
            {
                return Optional.of(dataset);
            }
        }
        return Optional.empty();
    }

    /** An SDTM/SEND-family standard name, split into its catalogue family and variant suffix. */
    private record Family(String family, String variant)
    {

        /** {@code SDTMIG}/{@code SDTM-IG} → sdtmig, {@code SENDIG-AR} → sendig + ar; else null. */
        static @Nullable Family of(@Nullable String aStandardName)
        {
            if (aStandardName == null)
            {
                return null;
            }
            String folded = aStandardName.replace("-", "").replace(" ", "")
                    .toUpperCase(Locale.ROOT);
            for (String base : new String[]
            {
                    "SDTMIG", "SENDIG"
            })
            {
                if (folded.startsWith(base))
                {
                    return new Family(base.toLowerCase(Locale.ROOT),
                            folded.substring(base.length()).toLowerCase(Locale.ROOT));
                }
            }
            return null;
        }
    }
}
