package net.cumba.cdisc.core.metadata.pickle;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import net.cumba.cdisc.library.api.model.adam.AdamProduct;
import net.cumba.cdisc.library.api.model.ct.CtPackage;
import net.cumba.cdisc.library.api.model.sdtm.SdtmProduct;
import net.cumba.web.api.ApiResource;
import net.cumba.web.api.Link;
import net.cumba.web.api.json.JsonNodeResource;

/**
 * Maps {@link PickleCache} entries (raw CDISC Library JSON, as nested {@code Map}/{@code List}) to
 * the engine's typed {@link net.cumba.web.api.ApiResource} views ({@link SdtmProduct},
 * {@link AdamProduct}, {@link CtPackage}). A pickle {@code Map} is converted to a Jackson
 * {@link JsonNode} via {@link ObjectMapper#valueToTree} and wrapped through
 * {@link JsonNodeResource#of(JsonNode, Class)} — the same JSON-backed view the live
 * {@code CdiscLibraryClient} produces — so the downstream {@code CdiscLibraryMetadataLibrary} /
 * {@code MetadataLibraryProvider} consume identical data whether fetched from the API or read from
 * the pickle cache.
 *
 * <p>
 * Cache-key construction mirrors the Python engine's {@code cdisc_rules_engine.utilities.utils}
 * helpers (including the ADaM standard/version normalisation).
 * </p>
 */
public final class PickleProductSource
{

    /** ADaM products that normalise to standard {@code "adam"} with a prefixed version. */
    private static final List<String> ADAM_PRODUCTS = List.of("adamig", "adam-adae", "adam-md",
            "adam-nca", "adam-occds", "adam-tte", "adam-poppk");

    private final PickleCache cache;

    private final ObjectMapper mapper;

    public PickleProductSource(PickleCache aCache)
    {
        cache = aCache;
        mapper = new ObjectMapper();
    }


    /** The backing cache (exposed for availability checks). */
    public PickleCache cache()
    {
        return cache;
    }


    /**
     * The SDTM/SDTMIG/SENDIG IG product for the given standard and version (cache key
     * {@code standards/<standard>/<version>}).
     */
    public Optional<SdtmProduct> igSdtm(String aStandard, String aVersion)
    {
        return cache.get(standardsKey(aStandard, aVersion)).map(m -> view(m, SdtmProduct.class));
    }


    /** The ADaM IG product for the given standard and version. */
    public Optional<AdamProduct> igAdam(String aStandard, String aVersion)
    {
        return cache.get(standardsKey(aStandard, aVersion)).map(m -> view(m, AdamProduct.class));
    }


    /**
     * An ADaM-family product by its verbatim {@code standards/...} cache key — the form a resolved
     * {@code --metadata-products} entry carries. Unlike {@link #igAdam(String, String)} this
     * reaches the TIG ADaM leg too ({@code standards/tig/1-0/adam}), whose key is not derivable
     * from a {@code (standard, version)} pair (§7-2 of
     * {@code plans/PLAN-metadata-product-selection.md}).
     *
     * @param aCacheKey
     *            the product's {@code standards/...} cache key
     * @return the product, or empty when the cache has no such key
     */
    public Optional<AdamProduct> adamProduct(String aCacheKey)
    {
        return cache.get(aCacheKey).map(m -> view(m, AdamProduct.class));
    }


    /**
     * The underlying SDTM Model product for an IG product, resolved from the IG's
     * {@code _links.model.href} (e.g. {@code /mdr/sdtm/2-0} → key {@code models/sdtm/2-0}). Mirrors
     * the Python {@code get_model_details_cache_key_from_ig}.
     */
    public Optional<SdtmProduct> sdtmModelFor(SdtmProduct aIgProduct)
    {
        return modelKeyFromIg(aIgProduct).flatMap(cache::get).map(m -> view(m, SdtmProduct.class));
    }


    /** The CT package {@code id} (e.g. {@code sdtmct-2024-09-27}). */
    public Optional<CtPackage> ctPackage(String aPackageId)
    {
        return cache.getCtPackage(aPackageId).map(m -> view(m, CtPackage.class));
    }


    /**
     * An empty {@link CtPackage} (no codelists), for the no-CT case where a non-null is required.
     */
    public CtPackage emptyCtPackage()
    {
        return JsonNodeResource.of(JsonNodeFactory.instance.objectNode(), CtPackage.class);
    }


    private <T extends ApiResource> T view(Map<String, Object> aJson, Class<T> aType)
    {
        JsonNode node = mapper.valueToTree(aJson);
        return JsonNodeResource.of(node, aType);
    }


    private Optional<String> modelKeyFromIg(SdtmProduct aIgProduct)
    {
        return aIgProduct.getLink("model").flatMap(Link::href).flatMap(href ->
        {
            String[] parts = href.split("/", -1);
            // /mdr/<standard>/<version> → parts = ["", "mdr", standard, version]
            if (parts.length < 4)
            {
                return Optional.<String> empty();
            }
            String standard = parts[2];
            String version = parts[3];
            if (version.startsWith(standard + "-"))
            {
                version = version.substring(standard.length() + 1);
            }
            return Optional.of(modelKey(standard, version));
        });
    }


    /**
     * The {@code standards/...} cache key for a run's {@code (standard, version)} pair — e.g.
     * {@code ("sdtmig", "3-4")} → {@code standards/sdtmig/3-4}, {@code ("adamig", "1-3")} →
     * {@code standards/adam/adamig-1-3} (ADaM products normalise to family {@code adam} with a
     * prefixed version). Public because it is also the <b>default metadata product</b> an omitted
     * {@code --metadata-products} implies (see {@code StudyValidationParams#metadataProducts()}).
     */
    public static String standardsKey(String aStandard, String aVersion)
    {
        String[] norm = normalize(aStandard, aVersion);
        // Cache keys use dash-form versions (e.g. 3-4), but callers may pass the dotted form (3.4).
        return "standards/" + norm[0] + "/" + norm[1].replace('.', '-');
    }


    private static String modelKey(String aStandard, String aModelVersion)
    {
        return "models/" + aStandard + "/" + aModelVersion.replace('.', '-');
    }


    private static String[] normalize(String aStandard, String aVersion)
    {
        String lower = aStandard.toLowerCase(Locale.ROOT);
        if (ADAM_PRODUCTS.contains(lower))
        {
            return new String[]
            {
                    "adam", lower + "-" + aVersion
            };
        }
        return new String[]
        {
                aStandard, aVersion
        };
    }
}
