package net.cumba.corej.core.metadata.store.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.pickle.LocalPickleSource;
import net.cumba.corej.core.metadata.pickle.ProductKeyResolver;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.StoreLibraryProvider;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.metadata.store.StoreProvenance;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The E14 seed supplement through a whole seed run (PLAN-seed-sendig-dart-1-2 D3, D6/F7, D7/F11 as
 * narrowed by D10, D9, F10, F12, Q1 (c)), with the SHIPPED supplements register over the miniature
 * {@link SeedFixtures} corpus — which, like the pinned pickle, does not serve
 * {@code standards/sendig/dart-1-2}.
 */
class SeedRunSupplementTest
{

    private static final String DART_12 = SeedFixtures.DART_12_KEY;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static final String SOURCE_LABEL = "DART 1.2 as a future source serves it";

    @TempDir
    private Path temp;

    private Path pickleDir;

    private Path target;

    @BeforeEach
    void writeSource() throws IOException
    {
        pickleDir = Files.createDirectories(temp.resolve("pickles"));
        target = temp.resolve("store").resolve("metadata-cache.zip");
        SeedFixtures.writePickleDir(pickleDir);
    }


    private StoreSeedReport pickleSeed(Path aTarget) throws IOException
    {
        return new PickleStoreSeeder(new LocalPickleSource(pickleDir))
                .seed(StoreSeedOptions.of(aTarget));
    }


    /** Rewrites the source's standards with {@code standards/sendig/dart-1-2} served or not. */
    private void writeStandards(boolean aServeDart12) throws IOException
    {
        Map<String, Object> standards = new LinkedHashMap<>();
        standards.put(SeedFixtures.IG_KEY, SeedFixtures.igDoc());
        standards.put(SeedFixtures.TIG_KEY, SeedFixtures.tigDoc());
        standards.put(SeedFixtures.ADAM_KEY, SeedFixtures.adamDoc());
        if (aServeDart12)
        {
            standards.put(DART_12, sourceDart12());
        }
        SeedFixtures.writePickle(pickleDir.resolve("standards_details.pkl"), standards);
    }


    /** A source-served DART 1.2: deliberately unlike the supplement (one dataset, no FXRESCAT). */
    private static Map<String, Object> sourceDart12()
    {
        Map<String, Object> doc = SeedFixtures.igDoc();
        doc.put("name", "SENDIG-DART v1.2");
        doc.put("label", SOURCE_LABEL);
        doc.put("version", "1.2");
        doc.put("_links", Map.of("model", Map.of("href", "/mdr/sdtm/1-6")));
        return doc;
    }


    private static StoreProvenance line(MetadataStore aStore, String aSource)
    {
        return aStore.manifest().provenance().stream().filter(p -> aSource.equals(p.source()))
                .findFirst().orElseThrow(() -> new AssertionError(
                        "no provenance line " + aSource + " in " + aStore.manifest().provenance()));
    }


    /**
     * The read side, red on a build without the supplement: a source that lacks
     * {@code sendig/dart-1-2} still yields a store that holds it, catalogues it, resolves the
     * packages' declaration and answers the DART 1.2 FX specification ({@code FXRESCAT} is Exp).
     */
    @Test
    void aSourceWithoutDart12YieldsAStoreThatServesIt() throws IOException
    {
        StoreSeedReport report = pickleSeed(target);

        assertEquals(List.of(DART_12), report.supplemented());
        assertEquals(List.of(), report.supplementSkipped());
        assertEquals(5, report.productsSeeded(), "D9: productsSeeded counts the source only");
        assertTrue(report.summary().contains(", 1 supplemented, "), report.summary());
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertTrue(store.product(DART_12).isPresent(), "the supplement is a stored product");
            assertTrue(store.productCatalogue().contains(DART_12),
                    store.productCatalogue().toString());
            assertEquals(new ProductKeyResolver.Result.Resolved(DART_12),
                    ProductKeyResolver.resolve("sendig/dart-1-2",
                            Set.copyOf(store.productCatalogue())),
                    "the packages' declaration resolves on a supplemented store");

            StoreLibraryProvider library = StoreLibraryProvider.over(store);
            assertEquals(Optional.of("Exp"),
                    library.variableCoreDesignation("SENDIG-DART", "1.2", "FX", "FXRESCAT"));
            assertEquals(List.of(), library.missedProducts(), "no DART 1.2 miss is recorded");
            assertTrue(library.publishedStandardVersions("SENDIG-DART").contains("1.2"),
                    library.publishedStandardVersions("SENDIG-DART").toString());

            MetadataProvider dart = new StoreMetadataProviderFactory(store)
                    .forSdtm("sendig", "dart-1-2", List.of()).orElseThrow();
            assertTrue(dart.getExpectedVariables("FX").contains("FXRESCAT"),
                    dart.getExpectedVariables("FX").toString());

            // F7 + D10: the supplements line names the digest and the applied key, and sits
            // right before the register digest line, which stays last.
            List<StoreProvenance> provenance = store.manifest().provenance();
            assertEquals(StoreErrata.PROVENANCE_SOURCE,
                    provenance.get(provenance.size() - 1).source());
            assertEquals(new StoreProvenance(StoreSupplements.PROVENANCE_SOURCE,
                    "sha256:" + StoreSupplements.load().digest() + " applied:" + DART_12, ""),
                    provenance.get(provenance.size() - 2));
        }
    }


    /**
     * Q1 (c): the supplement ships no variable description, so a DART 1.2 description read answers
     * ABSENT — never an empty string — while the variable itself is there. (The control, a SENDIG
     * 3.1.1 description that answers its text, needs the real corpus:
     * {@code StoreSeederRealDataConformanceTest}.)
     */
    @Test
    void aDart12VariableDescriptionReadsAbsent() throws IOException
    {
        pickleSeed(target);
        try (MetadataStore store = MetadataStore.open(target))
        {
            StoreLibraryProvider library = StoreLibraryProvider.over(store);
            assertEquals(Optional.empty(),
                    library.variableDescription("SENDIG-DART", "1.2", "FX", "FXRESCAT"));
            assertEquals(Optional.of("Result Category"),
                    library.variableLabel("SENDIG-DART", "1.2", "FX", "FXRESCAT"),
                    "the variable is held - only its description is absent");
            StoredProduct dart = store.product(DART_12).orElseThrow();
            List<StoredVariable> variables = dart.classes().stream()
                    .flatMap(c -> c.datasets().stream()).flatMap(d -> d.variables().stream())
                    .toList();
            assertEquals(670, variables.size(), "the P1 extract's 670 variables");
            assertTrue(variables.stream().allMatch(v -> v.description() == null),
                    "no DART 1.2 variable carries a description, not even an empty one");
        }
    }


    /**
     * D3 (i): a source that serves the key wins — its product is stored as the source projects it,
     * the supplement is IGNORED and the skip is reported (summary + details), not warned about.
     */
    @Test
    void aSourceThatServesTheKeyWinsAndTheSupplementIsIgnored() throws IOException
    {
        writeStandards(true);
        StoreSeedReport report = pickleSeed(target);

        String skip = DART_12 + ": the source now serves it - the E14 owner 2026-10-07 seed"
                + " supplement was IGNORED; retire it";
        assertEquals(List.of(skip), report.supplementSkipped());
        assertEquals(List.of(), report.supplemented());
        assertEquals(6, report.productsSeeded(), "the source's own DART 1.2 is a source product");
        assertEquals(List.of(), report.warnings(), "a skip is reported, NOT a warning");
        assertTrue(report.summary().contains("0 supplemented (" + skip + ")"), report.summary());
        assertTrue(report.details().contains("seed supplement IGNORED: " + skip),
                report.details().toString());
        String ref = report.sourceRef();
        assertTrue(report.summary().endsWith(ref == null ? " warnings)" : " from " + ref),
                "the summary keeps the source reference last: " + report.summary());

        StoredProduct expected = new StoreProjection().product(DART_12,
                MAPPER.valueToTree(sourceDart12()));
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertEquals(expected, store.product(DART_12).orElseThrow(),
                    "the source's product, exactly as the projection makes it");
            assertTrue(store.productCatalogue().contains(DART_12));
            assertEquals("sha256:" + StoreSupplements.load().digest(),
                    line(store, StoreSupplements.PROVENANCE_SOURCE).ref(),
                    "F7: the line is written with no applied key");
        }
    }


    /**
     * D3 (ii) / F10: a baseline holding a SOURCE-served copy (its supplements line does not list
     * the key) keeps that copy when the source stops offering it — the extract never replaces a
     * real Library product. The control: a baseline holding the SUPPLEMENTED copy gets the
     * supplement re-applied, never carried.
     */
    @Test
    void aBaselineSourceServedCopyIsNeverReplacedByTheSupplement() throws IOException
    {
        writeStandards(true);
        pickleSeed(target);
        writeStandards(false);

        StoreSeedReport report = pickleSeed(target);

        assertEquals(List.of(), report.supplemented());
        assertEquals(List.of(DART_12 + ": the existing store holds a source-served copy, carried"
                + " forward - the E14 owner 2026-10-07 seed supplement was IGNORED; retire it"),
                report.supplementSkipped());
        assertEquals(1, report.productsCarried());
        assertEquals(List.of(DART_12 + ": not offered by this source - carried forward unchanged"
                + " from the existing store"), report.warnings());
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertEquals(SOURCE_LABEL, store.product(DART_12).orElseThrow().label());
        }

        Path supplemented = temp.resolve("supplemented.zip");
        pickleSeed(supplemented);
        StoreSeedReport again = pickleSeed(supplemented);
        assertEquals(List.of(DART_12), again.supplemented(),
                "the control: a supplemented baseline copy is re-supplemented");
        assertEquals(0, again.productsCarried());
        assertEquals(List.of(), again.supplementSkipped());
    }


    /** F12: the catalogue reads the merge's outcome, so it refuses to run first. */
    @Test
    void theCatalogueUnionRefusesToRunBeforeTheMerge() throws IOException
    {
        SeedRun run = new SeedRun(StoreSeedOptions.of(target), StoreErrata.SOURCE_PICKLE);
        try
        {
            IllegalStateException refused = assertThrows(IllegalStateException.class,
                    () -> run.catalogueUnion(List.of(SeedFixtures.IG_KEY)));
            assertTrue(refused.getMessage().contains("before mergeProducts"), refused.getMessage());
            run.mergeProducts(Map.of());
            assertEquals(List.of(SeedFixtures.IG_KEY, DART_12),
                    run.catalogueUnion(List.of(SeedFixtures.IG_KEY)),
                    "after the merge the applied supplement joins the catalogue");
        }
        finally
        {
            run.close();
        }
    }


    /**
     * F11 as narrowed by D10: the missing-model warning fires when the source offers models but not
     * the supplement's ({@code models/sdtm/1-6}), and NOT when the source offers no model at all (a
     * one-IG cache, the CLI seed tests' shape) or offers that model.
     */
    @Test
    void theMissingModelWarningFiresOnlyWhenTheSourceOffersModelsButNotThatOne() throws IOException
    {
        assertEquals(SeedFixtures.DART_12_MODEL_WARNINGS, pickleSeed(target).warnings(),
                "models offered, models/sdtm/1-6 not");

        Files.delete(pickleDir.resolve("standards_models.pkl"));
        StoreSeedReport modelless = pickleSeed(temp.resolve("modelless.zip"));
        assertEquals(List.of(DART_12), modelless.supplemented());
        assertEquals(List.of(), modelless.warnings(), "a source without any model: no warning");

        Map<String, Object> models = new LinkedHashMap<>();
        models.put(SeedFixtures.SDTM_MODEL_KEY, SeedFixtures.sdtmModelDoc());
        models.put("models/sdtm/1-6", SeedFixtures.sdtmModelDoc());
        SeedFixtures.writePickle(pickleDir.resolve("standards_models.pkl"), models);
        StoreSeedReport withModel = pickleSeed(temp.resolve("with-model.zip"));
        assertEquals(List.of(), withModel.warnings(), "the linked model is held: no warning");
        try (MetadataStore store = MetadataStore.open(temp.resolve("with-model.zip")))
        {
            assertFalse(store.product("models/sdtm/1-6").isEmpty());
            assertEquals("/mdr/sdtm/1-6", store.product(DART_12).orElseThrow().modelHref());
        }
    }
}
