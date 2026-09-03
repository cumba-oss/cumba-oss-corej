package net.cumba.corej.core.metadata.pickle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.AdamDataStructureDetector;
import net.cumba.corej.core.metadata.AdamSubclassDetector;
import net.cumba.corej.core.metadata.UnmappedMetadataProductException;
import net.razorvine.pickle.Pickler;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 7a of {@code plans/PLAN-metadata-product-selection.md} —
 * {@link PickleMetadataProviderFactory#forAdam}: ADaM runs work offline, and the ordered
 * declared-product list goes through the <b>same</b> construction site as the API path.
 *
 * <h2>Why the two-product test is the load-bearing one</h2>
 *
 * <p>
 * A pickle ADaM path that constructed its provider directly would bypass
 * {@code DeclaredAdamProducts.assemble} and the multi-product feature would be silently inert
 * offline — every single-product test green, ruling 1 unreachable. The only thing that proves the
 * wiring is a two-product pickle-backed build resolving <em>differently</em> from a one-product
 * one: the pickle mirror of Phase 3's {@code CMTRT} evidence.
 * </p>
 *
 * <p>
 * Hermetic fixtures are pickled at runtime with {@link Pickler} (the {@code PickleCacheKeysTest}
 * pattern) and mirror {@code CdiscLibraryProviderBuilderMetadataProductsTest}'s products, so the
 * two cache paths are demonstrably tested against the same shape. The environment-gated tests run
 * the same probes over the <b>real</b> cache.
 * </p>
 */
class PickleMetadataProviderFactoryAdamTest
{

    private static final String ADAMIG_KEY = "standards/adam/adamig-9-9";

    private static final String OCCDS_KEY = "standards/adam/adam-occds-9-9";

    private static final String TIG_ADAM_KEY = "standards/tig/9-9/adam";

    private static final String OCCDS_TOKEN = AdamDataStructureDetector.OCCDS;

    private static final String ADVERSE_EVENT = AdamSubclassDetector.ADVERSE_EVENT;

    // ------------------------------------------------------------------
    // Fixtures (raw maps — the exact shape the pickle cache holds)
    // ------------------------------------------------------------------

    private static Map<String, Object> adamVar(String name, String ordinal, String core)
    {
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("name", name);
        v.put("ordinal", ordinal);
        v.put("simpleDatatype", "Char");
        v.put("core", core);
        return v;
    }


    private static Map<String, Object> structure(String name, @Nullable String className,
            @Nullable String subClass, List<Map<String, Object>> vars)
    {
        Map<String, Object> set = new LinkedHashMap<>();
        set.put("name", "Variables");
        set.put("ordinal", "1");
        set.put("analysisVariables", vars);
        Map<String, Object> ds = new LinkedHashMap<>();
        ds.put("name", name);
        if (className != null)
        {
            ds.put("class", className);
        }
        if (subClass != null)
        {
            ds.put("subClass", subClass);
        }
        ds.put("analysisVariableSets", List.of(set));
        return ds;
    }


    @SafeVarargs

    @SuppressWarnings("varargs") // passes its own varargs array to List.of
    private static Map<String, Object> product(String name, Map<String, Object>... structures)
    {
        Map<String, Object> product = new LinkedHashMap<>();
        product.put("name", name);
        product.put("version", "9-9");
        product.put("dataStructures", List.of(structures));
        return product;
    }


    /** {@code adamig-9-9}: ADSL only — it publishes no occurrence structure at all. */
    private static Map<String, Object> adamig()
    {
        return product("adamig", structure("ADSL", "SUBJECT LEVEL ANALYSIS DATASET", null,
                List.of(adamVar("USUBJID", "1", "Req"))));
    }


    /** {@code adam-occds-9-9}: a base OCCDS and an AE specialisation that disagree on --SEQ. */
    private static Map<String, Object> occds()
    {
        return product("adam-occds",
                structure("OCCDS", "OCCURRENCE DATA STRUCTURE", null,
                        List.of(adamVar("USUBJID", "1", "Req"), adamVar("--SEQ", "2", "Cond"),
                                adamVar("CMTRT", "3", "Req"))),
                structure("AE", "OCCURRENCE DATA STRUCTURE", ADVERSE_EVENT,
                        List.of(adamVar("USUBJID", "1", "Req"), adamVar("--SEQ", "2", "Req"))));
    }


    private static PickleMetadataProviderFactory factory(Path dir,
            Map<String, Map<String, Object>> standards)
        throws IOException
    {
        Files.write(dir.resolve("standards_details.pkl"),
                new Pickler().dumps(new LinkedHashMap<>(standards)));
        return PickleMetadataProviderFactory.open(dir);
    }


    private static Map<String, Map<String, Object>> bothProducts()
    {
        Map<String, Map<String, Object>> standards = new LinkedHashMap<>();
        standards.put(ADAMIG_KEY, adamig());
        standards.put(OCCDS_KEY, occds());
        return standards;
    }

    // ------------------------------------------------------------------
    // ⭐ The demonstration — the pickle mirror of Phase 3's CMTRT evidence
    // ------------------------------------------------------------------


    @Test
    void aTwoProductPickleRunResolvesDifferentlyFromAOneProductOne(@TempDir Path dir)
        throws IOException
    {
        PickleMetadataProviderFactory factory = factory(dir, bothProducts());

        // (a) One product — today's run. adamig publishes no occurrence structure, so an
        // occurrence dataset cannot resolve at all: null, i.e. the loud SKIP.
        MetadataProvider one = factory.forAdam("adamig", "9-9", List.of(ADAMIG_KEY), null, null)
                .orElseThrow();
        assertNull(one.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)));
        assertEquals(List.of(ADAMIG_KEY), one.declaredStructureKeyedProducts());

        // (b) Two products, the supplement first: the token resolves from the supplement, the AE
        // structure governs --SEQ (Req), and CMTRT — published only by the base OCCDS behind it —
        // still resolves. Byte-for-byte the API path's answer for the same declaration.
        MetadataProvider two = factory
                .forAdam("adamig", "9-9", List.of(OCCDS_KEY, ADAMIG_KEY), null, null).orElseThrow();
        assertEquals(List.of("USUBJID", "--SEQ", "CMTRT"),
                two.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)));
        assertEquals(List.of("USUBJID", "CMTRT"),
                two.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of()));
        assertEquals(List.of(OCCDS_KEY, ADAMIG_KEY), two.declaredStructureKeyedProducts());

        assertNotEquals(one.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)),
                two.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)),
                "if these agree, the pickle path never carried the declared product list — the "
                        + "multi-product feature is inert offline");
    }


    @Test
    void theSingleProductDefaultUsesTheDerivedKey(@TempDir Path dir) throws IOException
    {
        // §1b′ / no -mp: the params default arrives as the single -s/-v key; the pickle path
        // resolves it exactly as the API path does.
        PickleMetadataProviderFactory factory = factory(dir, bothProducts());
        MetadataProvider p = factory.forAdam("adamig", "9-9", List.of(ADAMIG_KEY), null, null)
                .orElseThrow();
        assertTrue(p.supportsStructureKeyedVariables());
        assertEquals(List.of("USUBJID"),
                p.getRequiredVariablesForStructure(AdamDataStructureDetector.ADSL));
    }


    @Test
    void ctPackagesFlowIntoThePickleAdamProvider(@TempDir Path dir) throws IOException
    {
        // The forSdtm parity: configured ADaM and SDTM CT package ids resolve from the cache
        // directory (whole-file pickles) and feed CdiscLibraryMetadataLibrary.fromAdam.
        PickleMetadataProviderFactory factory = factory(dir, bothProducts());
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("name", "ct");
        Files.write(dir.resolve("adamct-9999-01-01.pkl"), new Pickler().dumps(ct));
        Files.write(dir.resolve("sdtmct-9999-01-01.pkl"), new Pickler().dumps(ct));

        MetadataProvider p = factory.forAdam("adamig", "9-9", List.of(OCCDS_KEY),
                "adamct-9999-01-01", "sdtmct-9999-01-01").orElseThrow();

        assertEquals(List.of(OCCDS_KEY), p.declaredStructureKeyedProducts());
        assertEquals(List.of("USUBJID", "CMTRT"),
                p.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of()));
    }

    // ------------------------------------------------------------------
    // §7-2 — the TIG ADaM leg enters the ordered list (pickle-only product)
    // ------------------------------------------------------------------


    @Test
    void aDeclaredTigAdamLegLoadsFromThePickle(@TempDir Path dir) throws IOException
    {
        Map<String, Map<String, Object>> standards = bothProducts();
        standards.put(TIG_ADAM_KEY, product("tig-adam", structure("REFERENDS",
                "REFERENCE DATA STRUCTURE", null, List.of(adamVar("INPRM", "1", "Req")))));
        PickleMetadataProviderFactory factory = factory(dir, standards);

        MetadataProvider p = factory
                .forAdam("tig", "9-9", List.of(TIG_ADAM_KEY, OCCDS_KEY), null, null).orElseThrow();

        assertEquals(List.of(TIG_ADAM_KEY, OCCDS_KEY), p.declaredStructureKeyedProducts());
        assertEquals(List.of("INPRM"), p.getRequiredVariablesForStructure(
                AdamDataStructureDetector.REFERENCE_DATA_STRUCTURE));
    }

    // ------------------------------------------------------------------
    // Fallback and failure dispositions
    // ------------------------------------------------------------------


    @Test
    void anAbsentDeclaredProductFallsBackToTheApiPath(@TempDir Path dir) throws IOException
    {
        // A declared product the pickle cannot deliver: the whole source declines (empty), the
        // caller falls back to the API path. Never a silently shortened list (ruling 1).
        PickleMetadataProviderFactory factory = factory(dir, bothProducts());
        assertEquals(Optional.empty(), factory.forAdam("adamig", "9-9",
                List.of("standards/adam/adam-nope-1-0", OCCDS_KEY), null, null));
    }


    @Test
    void anAbsentDefaultProductFallsBackToTheApiPath(@TempDir Path dir) throws IOException
    {
        // Nothing ADaM-shaped declared (an SDTM-only -mp) falls back to the -s/-v product; when
        // even that is not in the pickle, the source declines.
        PickleMetadataProviderFactory factory = factory(dir, bothProducts());
        assertEquals(Optional.empty(),
                factory.forAdam("adamig", "8-8", List.of("standards/sdtmig/3-4"), null, null));
    }


    @Test
    void aWhollyUnmappableDeclaredProductFailsLoudlyOnThePicklePathToo(@TempDir Path dir)
        throws IOException
    {
        // §6b runs at the shared choke point, so the pickle path fails the same bad declaration
        // the API path fails — never Optional.empty (which would silently retry via the API).
        Map<String, Map<String, Object>> standards = bothProducts();
        standards.put("standards/adam/adam-future-1-0", product("adam-future", structure("FUTURE",
                "A CLASS THIS ENGINE DOES NOT KNOW", null, List.of(adamVar("X", "1", "Req")))));
        PickleMetadataProviderFactory factory = factory(dir, standards);

        assertThrows(UnmappedMetadataProductException.class, () -> factory.forAdam("adamig", "9-9",
                List.of("standards/adam/adam-future-1-0", OCCDS_KEY), null, null));
    }

    // ------------------------------------------------------------------
    // Integration — the REAL pickle cache (skipped when not configured)
    // ------------------------------------------------------------------


    private static @Nullable Path realCache()
    {
        String dir = System.getenv("CDISC_PICKLE_CACHE_DIR");
        return dir != null && Files.isDirectory(Path.of(dir)) ? Path.of(dir) : null;
    }


    @Test
    void everyRealCachedAdamFamilyProductResolvesOffline()
    {
        Path real = realCache();
        assumeTrue(real != null, "no real pickle cache configured");
        PickleMetadataProviderFactory factory = PickleMetadataProviderFactory.open(real);
        // The 11 standards/adam products plus the TIG ADaM leg — §7a's "15 ADaM/TIG products"
        // minus the three non-ADaM TIG legs, which are not this provider's to load.
        List<String> keys = List.of("standards/adam/adamig-1-0", "standards/adam/adamig-1-1",
                "standards/adam/adamig-1-2", "standards/adam/adamig-1-3",
                "standards/adam/adam-adae-1-0", "standards/adam/adam-md-1-0",
                "standards/adam/adam-nca-1-0", "standards/adam/adam-occds-1-0",
                "standards/adam/adam-occds-1-1", "standards/adam/adam-poppk-1-0",
                "standards/adam/adam-tte-1-0", "standards/tig/1-0/adam");
        for (String key : keys)
        {
            Optional<MetadataProvider> p = factory.forAdam("adamig", "1-3", List.of(key), null,
                    null);
            assertTrue(p.isPresent(), () -> key + " did not resolve offline");
            assertTrue(p.get().supportsStructureKeyedVariables(), key);
            assertEquals(List.of(key), p.get().declaredStructureKeyedProducts(), key);
        }
    }


    @Test
    void theRealCacheTwoProductRunMirrorsThePhase3Evidence()
    {
        // Phase 3's CMTRT evidence (run A vs run B), replayed offline: adamig-1-3 alone cannot
        // answer the occurrence token; declaring adam-occds-1-1 first resolves it, the AE
        // specialisation governs, and CMTRT (base OCCDS: Req; AE: Not Used) is correctly NOT
        // demanded of an adverse-event dataset — while the base-only answer still carries it.
        Path real = realCache();
        assumeTrue(real != null, "no real pickle cache configured");
        PickleMetadataProviderFactory factory = PickleMetadataProviderFactory.open(real);

        MetadataProvider one = factory
                .forAdam("adamig", "1-3", List.of("standards/adam/adamig-1-3"), null, null)
                .orElseThrow();
        assertNull(one.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT)));

        MetadataProvider two = factory.forAdam("adamig", "1-3",
                List.of("standards/adam/adam-occds-1-1", "standards/adam/adamig-1-3"), null, null)
                .orElseThrow();
        List<String> ae = two.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of(ADVERSE_EVENT));
        assertTrue(ae != null && ae.contains("--SEQ") && ae.contains("--DECOD"),
                () -> String.valueOf(ae));
        assertFalse(ae.contains("CMTRT"),
                "AE governs: the Concomitant-Medication variable must not be demanded of ADAE");
        List<String> base = two.getRequiredVariablesForStructure(OCCDS_TOKEN, List.of());
        assertTrue(base != null && base.contains("CMTRT"), () -> String.valueOf(base));
    }
}
