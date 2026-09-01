package net.cumba.cdisc.core.metadata.pickle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.cumba.cdisc.core.metadata.MetadataProductCatalogue;
import net.cumba.cdisc.core.metadata.pickle.ProductKeyResolver.Result;
import org.junit.jupiter.api.Test;

/**
 * Phase 1a of {@code plans/PLAN-metadata-product-selection.md} — {@code --metadata-products} token
 * resolution by <b>unique-suffix match</b> against the cache's standard keys. Never a dash-split
 * heuristic and never a family table: an ambiguous or absent token errors instead of guessing.
 */
class ProductKeyResolverTest
{

    /** A realistic key set covering all three published shapes (§2.5 of the plan). */
    private static final Set<String> KEYS = new LinkedHashSet<>(List.of(//
            "standards/sdtmig/3-4", //
            "standards/sdtmig/3-3", //
            "standards/adam/adamig-1-3", //
            "standards/adam/adam-occds-1-1", //
            "standards/tig/1-0/sdtm", //
            "standards/tig/1-0/adam"));

    private static String resolved(String token, Set<String> keys)
    {
        Result r = ProductKeyResolver.resolve(token, keys);
        assertInstanceOf(Result.Resolved.class, r, () -> token + " -> " + r);
        return ((Result.Resolved) r).cacheKey();
    }


    @Test
    void fullKeyResolvesVerbatim()
    {
        assertEquals("standards/adam/adamig-1-3", resolved("adam/adamig-1-3", KEYS));
        assertEquals("standards/tig/1-0/sdtm", resolved("tig/1-0/sdtm", KEYS));
    }


    @Test
    void standardsPrefixIsTolerated()
    {
        assertEquals("standards/adam/adamig-1-3", resolved("standards/adam/adamig-1-3", KEYS));
    }


    @Test
    void bareProductIdResolvesWhenUnique()
    {
        assertEquals("standards/adam/adamig-1-3", resolved("adamig-1-3", KEYS));
        assertEquals("standards/adam/adam-occds-1-1", resolved("adam-occds-1-1", KEYS));
        assertEquals("standards/sdtmig/3-4", resolved("sdtmig/3-4", KEYS));
    }


    @Test
    void dottedVersionIsDashNormalised()
    {
        assertEquals("standards/adam/adamig-1-3", resolved("adamig-1.3", KEYS));
    }


    @Test
    void caseAndWhitespaceAreNormalised()
    {
        assertEquals("standards/adam/adamig-1-3", resolved("  ADAMIG-1-3 ", KEYS));
    }


    @Test
    void ambiguousSuffixIsAnErrorListingTheMatches()
    {
        // Two keys end in /x-1-0 — a bare "x-1-0" must not guess between them.
        Set<String> keys = new LinkedHashSet<>(
                List.of("standards/adam/x-1-0", "standards/sendig/x-1-0"));
        Result r = ProductKeyResolver.resolve("x-1-0", keys);
        Result.Ambiguous a = assertInstanceOf(Result.Ambiguous.class, r);
        assertEquals(List.of("standards/adam/x-1-0", "standards/sendig/x-1-0"), a.matches());
    }


    @Test
    void absentTokenListsCandidates()
    {
        Result r = ProductKeyResolver.resolve("adamig-9-9", KEYS);
        Result.NotFound nf = assertInstanceOf(Result.NotFound.class, r);
        assertEquals("adamig-9-9", nf.token());
        assertTrue(nf.candidates().contains("standards/adam/adamig-1-3"),
                () -> "candidates should include the near-miss: " + nf.candidates());
    }


    @Test
    void emptyKeySetAcceptsFullFormOnly()
    {
        // No cache configured: full-key form passes verbatim, a bare suffix is refused.
        assertEquals("standards/adam/adamig-1-3", resolved("adam/adamig-1-3", Set.of()));
        Result bare = ProductKeyResolver.resolve("adamig-1-3", Set.of());
        assertInstanceOf(Result.NotFound.class, bare);
    }


    @Test
    void blankTokenIsNotFound()
    {
        assertInstanceOf(Result.NotFound.class, ProductKeyResolver.resolve("  ", KEYS));
    }


    @Test
    void resolveAllPreservesOrderAndDeduplicates()
    {
        List<String> out = ProductKeyResolver
                .resolveAll(List.of("adam-occds-1-1", "adamig-1.3", "adam/adam-occds-1-1"), KEYS);
        assertEquals(List.of("standards/adam/adam-occds-1-1", "standards/adam/adamig-1-3"), out);
    }


    @Test
    void resolveAllReportsEveryFailureAtOnce()
    {
        Set<String> keys = new LinkedHashSet<>(
                List.of("standards/adam/x-1-0", "standards/sendig/x-1-0"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProductKeyResolver.resolveAll(List.of("nope-1", "x-1-0", "nope-2"), keys));
        // All three bad tokens are named in the single message.
        assertTrue(e.getMessage().contains("nope-1"), e.getMessage());
        assertTrue(e.getMessage().contains("x-1-0"), e.getMessage());
        assertTrue(e.getMessage().contains("nope-2"), e.getMessage());
    }


    @Test
    void resolveAllWithNoCacheNamesTheMissingCacheReason()
    {
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProductKeyResolver.resolveAll(List.of("adamig-1-3"), Set.of()));
        assertTrue(e.getMessage().contains("no pickle metadata cache"), e.getMessage());
    }


    @Test
    void resolveAllConfiguredWithNoTokensIsEmpty()
    {
        assertEquals(List.of(), ProductKeyResolver.resolveAllConfigured(List.of(), null, null));
    }

    // ------------------------------------------------------------------
    // Phase 7b — source-agnostic catalogue resolution
    // ------------------------------------------------------------------


    @Test
    void anEmptyCatalogueAcceptsFullFormOnly()
    {
        // No source available at all: full-form tokens pass verbatim, bare suffixes error with
        // the no-catalogue reason. (The residue of the old "no pickle cache => full-key tokens
        // only" rule, which Phase 7b superseded for API-backed deployments.)
        MetadataProductCatalogue none = MetadataProductCatalogue.of(Set.of(), false, List.of());
        assertEquals(List.of("standards/adam/adamig-1-3"),
                ProductKeyResolver.resolveAll(List.of("adam/adamig-1-3"), none));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProductKeyResolver.resolveAll(List.of("adamig-1-3"), none));
        assertTrue(e.getMessage().contains("no product catalogue"), e.getMessage());
    }


    @Test
    void aPickleOnlyCatalogueResolvesABareToken()
    {
        MetadataProductCatalogue pickleOnly = MetadataProductCatalogue.of(KEYS, true,
                List.of("pickle cache"));
        assertEquals(List.of("standards/adam/adamig-1-3"),
                ProductKeyResolver.resolveAll(List.of("adamig-1-3"), pickleOnly));
    }


    @Test
    void anApiOnlyCatalogueResolvesABareToken()
    {
        // §7c: an API-only deployment (no pickle cache anywhere) now resolves bare tokens too —
        // the catalogue supersedes the old pickle-only rule.
        MetadataProductCatalogue apiOnly = MetadataProductCatalogue.of(
                Set.of("standards/adam/adamig-1-3", "standards/sdtmig/3-4"), false,
                List.of("CDISC Library API product list"));
        assertEquals(List.of("standards/adam/adamig-1-3"),
                ProductKeyResolver.resolveAll(List.of("adamig-1-3"), apiOnly));
    }


    @Test
    void aBothConfiguredCatalogueResolvesAgainstTheUnion()
    {
        // Pickle contributes TIG (pickle-only, §7-1); the API side contributes the IGs. A token
        // of each source resolves through one union set.
        Set<String> union = new LinkedHashSet<>(KEYS);
        union.add("standards/cdashig/2-3");
        MetadataProductCatalogue both = MetadataProductCatalogue.of(union, true,
                List.of("pickle cache", "CDISC Library API product list"));
        assertEquals(List.of("standards/tig/1-0/adam", "standards/cdashig/2-3"),
                ProductKeyResolver.resolveAll(List.of("tig/1-0/adam", "cdashig/2-3"), both));
    }


    @Test
    void resolveAllConfiguredResolvesABareTokenAgainstTheRealCatalogue()
    {
        // The configured-catalogue path end to end (gated on the real caches): a bare token
        // resolves through the pickle/API union that resolveAllConfigured assembles itself.
        String pickleDir = System.getenv("CDISC_PICKLE_CACHE_DIR");
        org.junit.jupiter.api.Assumptions.assumeTrue(
                pickleDir != null
                        && java.nio.file.Files.isDirectory(java.nio.file.Path.of(pickleDir)),
                "no real pickle cache configured");
        assertEquals(List.of("standards/adam/adamig-1-3"), ProductKeyResolver.resolveAllConfigured(
                List.of("adamig-1-3"), pickleDir, System.getenv("CDISC_API_CACHE")));
    }


    @Test
    void aTigTokenInAnApiOnlyDeploymentFailsWithThePickleOnlyReason()
    {
        // §7-1: tig/1-0/* is pickle-only — the API cache has no TIG at all. The failure must
        // state that reason, not present a mysterious not-found.
        MetadataProductCatalogue apiOnly = MetadataProductCatalogue.of(
                Set.of("standards/sdtmig/3-4"), false, List.of("CDISC Library API product list"));
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> ProductKeyResolver.resolveAll(List.of("tig/1-0/adam"), apiOnly));
        assertTrue(e.getMessage().contains("TIG"), e.getMessage());
        assertTrue(e.getMessage().contains("pickle metadata cache"), e.getMessage());
    }
}
