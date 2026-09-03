package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.library.api.client.CdiscLibraryClient;
import net.cumba.cdisc.library.api.model.products.Products;
import net.cumba.corej.core.CoreLibraryAccess;
import net.cumba.corej.core.CoreLibraryAccessImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 7b of {@code plans/PLAN-metadata-product-selection.md} — the source-agnostic product
 * catalogue. The hermetic tests pin the <b>filter</b> (§7-1: a naive enumeration offers models and
 * sub-resources as selectable products; the type filter is what keeps the catalogue honest); the
 * environment-gated test proves the union over the two <b>real</b> caches.
 */
class MetadataProductCatalogueTest
{

    // ------------------------------------------------------------------
    // Fixture: a /mdr/products response shaped exactly like the real payload
    // ------------------------------------------------------------------

    private static Map<String, Object> link(String href, String type)
    {
        Map<String, Object> l = new LinkedHashMap<>();
        l.put("href", href);
        l.put("title", href);
        l.put("type", type);
        return l;
    }


    private static Map<String, Object> group(String family, List<Map<String, Object>> links)
    {
        Map<String, Object> inner = new LinkedHashMap<>();
        inner.put(family, links);
        Map<String, Object> g = new LinkedHashMap<>();
        g.put("_links", inner);
        return g;
    }


    /** Mirrors the measured {@code api_mdr_products.json.gz} shape, including the traps. */
    private static Products products()
    {
        Map<String, Object> groups = new LinkedHashMap<>();
        groups.put("data-analysis", group("adam", List.of(//
                link("/mdr/adam/adam-2-1", "Foundational Model"), // the model trap
                link("/mdr/adam/adamig-1-3", "Implementation Guide"), //
                link("/mdr/adam/adam-occds-1-1", "Implementation Guide"))));
        Map<String, Object> tabulation = new LinkedHashMap<>();
        tabulation.put("sdtm", List.of(link("/mdr/sdtm/2-0", "Foundational Model")));
        tabulation.put("sdtmig", List.of(link("/mdr/sdtmig/3-4", "Implementation Guide"),
                link("/mdr/sdtmig/ap-1-0", "Implementation Guide")));
        tabulation.put("sendig", List.of(link("/mdr/sendig/dart-1-1", "Implementation Guide")));
        Map<String, Object> dt = new LinkedHashMap<>();
        dt.put("_links", tabulation);
        groups.put("data-tabulation", dt);
        Map<String, Object> terminology = new LinkedHashMap<>();
        terminology.put("packages",
                List.of(link("/mdr/ct/packages/sdtmct-2024-09-27", "Terminology")));
        Map<String, Object> term = new LinkedHashMap<>();
        term.put("_links", terminology);
        groups.put("terminology", term);
        Map<String, Object> qrs = new LinkedHashMap<>();
        qrs.put("instrument",
                List.of(link("/mdr/qrs/instruments/AIMS01/versions/2-0", "QRS Instrument")));
        Map<String, Object> q = new LinkedHashMap<>();
        q.put("_links", qrs);
        groups.put("qrs", q);
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("_links", groups);
        return net.cumba.web.api.dev.MapResource.of(root, Products.class);
    }


    @Test
    void theApiContributionKeepsOnlyTwoSegmentImplementationGuides() throws IOException
    {
        CdiscLibraryClient client = mock(CdiscLibraryClient.class);
        when(client.getProducts()).thenReturn(products());

        Set<String> keys = MetadataProductCatalogue.apiProductKeys(client);

        assertEquals(Set.of("standards/adam/adamig-1-3", "standards/adam/adam-occds-1-1",
                "standards/sdtmig/3-4", "standards/sdtmig/ap-1-0", "standards/sendig/dart-1-1"),
                keys);
        // The traps, by name: models are not declarable products…
        assertFalse(keys.contains("standards/adam/adam-2-1"));
        assertFalse(keys.contains("standards/sdtm/2-0"));
        // …and neither are CT packages or QRS instruments (both fail the type filter AND the
        // two-segment shape guard).
        assertTrue(keys.stream().noneMatch(k -> k.contains("/packages/")), keys.toString());
        assertTrue(keys.stream().noneMatch(k -> k.contains("/instruments/")), keys.toString());
    }


    @Test
    void ofExposesKeysSourcesAndThePickleFlag()
    {
        MetadataProductCatalogue c = MetadataProductCatalogue.of(Set.of("standards/sdtmig/3-4"),
                true, List.of("pickle cache /x"));
        assertEquals(Set.of("standards/sdtmig/3-4"), c.keys());
        assertTrue(c.pickleConfigured());
        assertEquals(List.of("pickle cache /x"), c.sources());
    }

    // ------------------------------------------------------------------
    // ⛔ Phase 11 finding F7 — one live /mdr/products call PER RESOLUTION, and a keyless
    // client that could still leave the machine
    // ------------------------------------------------------------------


    @Test
    void theConfiguredCatalogueIsMemoisedPerResolvedConfiguration(@TempDir Path aTemp)
        throws IOException
    {
        // configured() was called once per --metadata-products resolution and rebuilt everything
        // each time: a pickle-cache scan plus a GET /mdr/products. In the REST server that is one
        // product-list fetch per submitted run.
        Path one = Files.createDirectories(aTemp.resolve("api-one"));
        Path two = Files.createDirectories(aTemp.resolve("api-two"));

        MetadataProductCatalogue first = MetadataProductCatalogue.configured(null, one.toString());
        MetadataProductCatalogue again = MetadataProductCatalogue.configured(null, one.toString());
        MetadataProductCatalogue other = MetadataProductCatalogue.configured(null, two.toString());

        assertSame(first, again, "the same resolved configuration must not be rebuilt");
        assertNotSame(first, other, "a different configuration must not read another's answer");
    }


    @Test
    void aCatalogueWithNoApiKeyCannotLeaveTheMachine()
    {
        // CoreLibraryAccessImpl.open substitutes a "dummy" key when none is configured, so before
        // this guard a cache MISS issued a real request to api.library.cdisc.org that could only
        // 401 — from every unit test that resolves a token. The cache is consulted before the HTTP
        // call, so a loopback base URL keeps every cache hit working (pinned by
        // theConfiguredCatalogueUnionsTheRealPickleAndApiCaches below, which runs WITHOUT an API
        // key) and turns every miss into an immediate local failure.
        assertEquals(MetadataProductCatalogue.OFFLINE_BASE_URL,
                MetadataProductCatalogue.apiBaseUrl(null));
        assertEquals(MetadataProductCatalogue.OFFLINE_BASE_URL,
                MetadataProductCatalogue.apiBaseUrl("   "));
        assertNull(MetadataProductCatalogue.apiBaseUrl("a-real-key"),
                "a configured key means the client's own default base URL");

        CoreLibraryAccess keyless = MetadataProductCatalogue.openApiAccess(null, null);
        assertTrue(keyless instanceof CoreLibraryAccessImpl);
        String baseUrl = ((CoreLibraryAccessImpl) keyless).client().baseUrl();
        assertTrue(baseUrl.startsWith("http://127.0.0.1:"),
                () -> "a keyless catalogue client must be pointed at loopback; got " + baseUrl);
        assertFalse(baseUrl.contains("cdisc.org"),
                () -> "a keyless catalogue client must not address CDISC; got " + baseUrl);
    }

    // ------------------------------------------------------------------
    // Integration — the two REAL caches, unioned (skipped when not configured)
    // ------------------------------------------------------------------


    @Test
    void theConfiguredCatalogueUnionsTheRealPickleAndApiCaches()
    {
        String pickleDir = System.getenv("CDISC_PICKLE_CACHE_DIR");
        String apiDir = System.getenv("CDISC_API_CACHE");
        assumeTrue(pickleDir != null && Files.isDirectory(Path.of(pickleDir)),
                "no real pickle cache configured");
        assumeTrue(
                apiDir != null && Files.isRegularFile(Path.of(apiDir, "api_mdr_products.json.gz")),
                "no cached /mdr/products in the API cache");

        MetadataProductCatalogue c = MetadataProductCatalogue.configured(pickleDir, apiDir);

        assertTrue(c.pickleConfigured());
        // §7-1: the 30 real IG products appear identically in both sources; spot-check both
        // shapes plus the union's pickle-only member (TIG) and the excluded model.
        assertTrue(c.keys().contains("standards/adam/adamig-1-3"), () -> String.valueOf(c.keys()));
        assertTrue(c.keys().contains("standards/sdtmig/ap-1-0"));
        assertTrue(c.keys().contains("standards/tig/1-0/adam"),
                "TIG is pickle-only and must come from the pickle side of the union");
        assertFalse(c.keys().contains("standards/adam/adam-2-1"),
                "the ADaM model must not be offered as a declarable product");
        assertTrue(c.keys().stream().noneMatch(k -> k.contains("/datasets/")),
                "sub-resources must never be offered (§7-1's 361-path trap)");
        assertTrue(c.keys().stream().noneMatch(k -> k.contains("/classes/")));
    }
}
