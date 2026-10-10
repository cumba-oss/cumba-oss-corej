package net.cumba.corej.core.metadata.store.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.metadata.pickle.LocalPickleSource;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.MetadataStoreWriter;
import net.cumba.corej.core.metadata.store.StoreProvenance;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredVariable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The errata register through a whole seed run, with the SHIPPED register (review round of
 * PLAN-store-library-content-errata):
 *
 * <ul>
 * <li><b>F1</b> — a stale entry is reported in the seed report (its list, the summary, the detail
 * lines), NOT applied, and NOT a warning, so a seed from a source that is ahead of the register
 * does not fail on the CLI's exit code;</li>
 * <li><b>F2</b> — a pickle-scoped entry is applied by the pickle seeder and reported as not
 * applicable by the Web API seeder, which leaves the value as the source serves it;</li>
 * <li><b>F3</b> — the register's sha256 is recorded in the manifest provenance, and a baseline
 * recorded under another digest has its products re-acquired, never carried (its CT packages still
 * are).</li>
 * </ul>
 *
 * <p>
 * The fixture is {@link SeedFixtures}' miniature corpus with two roots added to the SDTM 2.0
 * model's General Observations class, both targets of real {@code #283.36} entries (pickle only):
 * {@code NHOID} still carries the defect the entry corrects, {@code --EVLINT} already carries the
 * printed quotes — CDISC fixed it upstream, so its entry is stale.
 * </p>
 */
class SeedRunErrataTest
{

    private static final String NHOID_ENTRY = "283.36-2-0-GeneralObservations-NHOID";

    private static final String EVLINT_ENTRY = "283.36-2-0-GeneralObservations---EVLINT";

    private static final String NHOID_SOURCE = "A/California/7/2009 (H1N1)";

    private static final String EVLINT_FIXED = "\"-P2M\" to represent a period of the past 2"
            + " months as the evaluation interval for a question from a questionnaire.";

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
        Map<String, Object> sdtm = SeedFixtures.sdtmModelDoc();
        @SuppressWarnings("unchecked")
        Map<String, Object> generalObservations = new LinkedHashMap<>(
                ((List<Map<String, Object>>) sdtm.get("classes")).get(0));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> roots = new ArrayList<>(
                (List<Map<String, Object>>) generalObservations.get("classVariables"));
        roots.add(root("NHOID", "Non-host Organism Identifier", "3", NHOID_SOURCE));
        roots.add(root("--EVLINT", "Evaluation Interval", "4", EVLINT_FIXED));
        generalObservations.put("classVariables", roots);
        // The real model's class name (the miniature corpus spells it without the space).
        generalObservations.put("name", "General Observations");
        sdtm.put("classes", List.of(generalObservations));
        Map<String, Object> models = new LinkedHashMap<>();
        models.put(SeedFixtures.SDTM_MODEL_KEY, sdtm);
        models.put(SeedFixtures.ADAM_MODEL_KEY, SeedFixtures.adamModelDoc());
        SeedFixtures.writePickle(pickleDir.resolve("standards_models.pkl"), models);
    }


    private static Map<String, Object> root(String aName, String aLabel, String aOrdinal,
            String aExamples)
    {
        Map<String, Object> variable = new LinkedHashMap<>();
        variable.put("name", aName);
        variable.put("label", aLabel);
        variable.put("ordinal", aOrdinal);
        variable.put("simpleDatatype", "Char");
        variable.put("examples", aExamples);
        return variable;
    }


    private StoreSeedReport pickleSeed(Path aTarget) throws IOException
    {
        return new PickleStoreSeeder(new LocalPickleSource(pickleDir))
                .seed(StoreSeedOptions.of(aTarget));
    }


    private static String examples(Path aStore, String aRoot) throws IOException
    {
        try (MetadataStore store = MetadataStore.open(aStore))
        {
            StoredClass clazz = store.product(SeedFixtures.SDTM_MODEL_KEY).orElseThrow().classes()
                    .get(0);
            return clazz.classVariables().stream().filter(v -> aRoot.equals(v.name()))
                    .map(StoredVariable::examples).findFirst().orElseThrow();
        }
    }


    @Test
    void aStaleEntryIsReportedNotAppliedAndNotAWarning() throws IOException
    {
        StoreSeedReport report = pickleSeed(target);

        assertTrue(report.errataApplied().contains(NHOID_ENTRY), report.errataApplied().toString());
        assertEquals("\"" + NHOID_SOURCE + "\"", examples(target, "NHOID"), "the live entry");

        List<String> stale = report.errataStale().stream()
                .filter(s -> s.startsWith(EVLINT_ENTRY + ":")).toList();
        assertEquals(1, stale.size(), report.errataStale().toString());
        assertTrue(stale.get(0).contains("examples expected '-P2M to represent"), stale.get(0));
        assertEquals(EVLINT_FIXED, examples(target, "--EVLINT"),
                "D4: reported, NOT applied - the source's value stays");

        assertEquals(List.of(), report.warnings(),
                "F1: a stale entry is no warning, so a user seed does not fail on it");
        assertTrue(report.summary().contains("(" + report.errataStale().size() + " stale,"),
                "the summary counts the stale entries: " + report.summary());
        assertFalse(report.summary().contains(EVLINT_ENTRY),
                "the summary names no stale id - details() does: " + report.summary());
        String ref = report.sourceRef();
        assertTrue(report.summary().endsWith(ref == null ? " warnings)" : " from " + ref),
                "the summary keeps its shape, provenance last: " + report.summary());
        assertTrue(
                report.details()
                        .contains("errata entry stale, reported and NOT applied: " + stale.get(0)),
                report.details().toString());
        assertEquals(List.of(), report.errataNotApplicable(), "a pickle seed runs every entry");
    }


    @Test
    void theWebApiSeederLeavesPickleOnlyEntriesAlone() throws IOException
    {
        Path cache = Files.createDirectories(temp.resolve("web-cache"));
        SeedFixtures.writeWebCache(pickleDir, cache);
        StoreSeedReport report = new WebApiStoreSeeder(SeedFixtures.offlineAccess(cache))
                .seed(StoreSeedOptions.of(target));

        assertEquals(NHOID_SOURCE, examples(target, "NHOID"),
                "F2: a (seed?) row is not applied to the Library's value");
        assertTrue(
                report.errataNotApplicable().stream().anyMatch(s -> s.equals(NHOID_ENTRY
                        + ": registered for [pickle], not applicable" + " to a webapi seed")),
                report.errataNotApplicable().toString());
        assertFalse(report.errataApplied().contains(NHOID_ENTRY));
        assertTrue(report.errataStale().stream().noneMatch(s -> s.startsWith(EVLINT_ENTRY)),
                "a pickle-only entry is never stale for a Web API seed: " + report.errataStale());
        long pickleOnly = StoreErrata.load().entries().stream().filter(e -> !e.sources().isEmpty())
                .count();
        assertEquals(pickleOnly, report.errataNotApplicable().size());
        assertTrue(report.summary().contains(pickleOnly + " not for this source"),
                report.summary());

        Path fromPickles = temp.resolve("from-pickles.zip");
        pickleSeed(fromPickles);
        assertEquals("\"" + NHOID_SOURCE + "\"", examples(fromPickles, "NHOID"),
                "the same source seeded from the pickles is corrected");
    }


    @Test
    void theRegisterDigestIsRecordedInTheProvenance() throws IOException
    {
        pickleSeed(target);
        try (MetadataStore store = MetadataStore.open(target))
        {
            List<StoreProvenance> provenance = store.manifest().provenance();
            assertEquals(PickleStoreSeeder.PROVENANCE_SOURCE, provenance.get(0).source());
            StoreProvenance last = provenance.get(provenance.size() - 1);
            assertEquals(StoreErrata.PROVENANCE_SOURCE, last.source());
            assertEquals("sha256:" + StoreErrata.load().digest(), last.ref());
            assertEquals(1, provenance.stream()
                    .filter(p -> StoreErrata.PROVENANCE_SOURCE.equals(p.source())).count());
        }
        StoreSeedOptions override = StoreSeedOptions.of(target)
                .withProvenanceOverride(List.of(
                        new StoreProvenance(StoreErrata.PROVENANCE_SOURCE, "sha256:stale", ""),
                        new StoreProvenance("conformance", "fixture", "")));
        new PickleStoreSeeder(new LocalPickleSource(pickleDir)).seed(override);
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertEquals(
                    List.of(new StoreProvenance("conformance", "fixture", ""),
                            new StoreProvenance(StoreSupplements.PROVENANCE_SOURCE,
                                    "sha256:" + StoreSupplements.load().digest() + " applied:"
                                            + SeedFixtures.DART_12_KEY,
                                    ""),
                            new StoreProvenance(StoreErrata.PROVENANCE_SOURCE,
                                    "sha256:" + StoreErrata.load().digest(), "")),
                    store.manifest().provenance(),
                    "an override keeps its lines, but the supplements line (F7) and the digest"
                            + " line are always this build's, the digest line last (D10)");
        }
    }


    @Test
    void aBaselineCorrectedUnderAnotherRegisterHasItsProductsReacquiredNotCarried()
        throws IOException
    {
        pickleSeed(target);
        Path current = temp.resolve("current.zip");
        Files.copy(target, current);
        relabelDigest(target, "sha256:" + "0".repeat(64));
        // The source now offers only the two models: the three IG products could only come from
        // the baseline.
        Files.delete(pickleDir.resolve("standards_details.pkl"));

        StoreSeedReport other = pickleSeed(target);
        assertEquals(0, other.productsCarried(),
                "F3: products corrected under another register are never carried");
        assertEquals(3, other.ctPackagesCarried(), "CT packages are untouched by the register");
        assertEquals(List.of(), other.warnings());
        try (MetadataStore store = MetadataStore.open(target))
        {
            assertTrue(store.product(SeedFixtures.IG_KEY).isEmpty());
            assertTrue(store.product(SeedFixtures.SDTM_MODEL_KEY).isPresent());
        }

        StoreSeedReport same = pickleSeed(current);
        assertEquals(3, same.productsCarried(),
                "the control: under the same digest the baseline products are carried");
        try (MetadataStore store = MetadataStore.open(current))
        {
            assertTrue(store.product(SeedFixtures.IG_KEY).isPresent());
        }
    }


    /** Rewrites a store with its errata provenance line's ref replaced, everything else kept. */
    private void relabelDigest(Path aStore, String aRef) throws IOException
    {
        MetadataStoreWriter writer = new MetadataStoreWriter();
        try (MetadataStore store = MetadataStore.open(aStore))
        {
            for (String entry : store.manifest().partHashes().keySet())
            {
                if (entry.startsWith("products/") && entry.endsWith(".json"))
                {
                    writer.addProduct(store.product(entry.substring("products/".length(),
                            entry.length() - ".json".length())).orElseThrow());
                }
            }
            for (String id : store.publishedCtPackages())
            {
                store.ctPackage(id).ifPresent(writer::addCtPackage);
            }
            writer.publishedCtPackages(store.publishedCtPackages())
                    .productCatalogue(store.productCatalogue());
            for (StoreProvenance line : store.manifest().provenance())
            {
                writer.addProvenance(StoreErrata.PROVENANCE_SOURCE.equals(line.source())
                        ? new StoreProvenance(line.source(), aRef, line.fetchedAt())
                        : line);
            }
        }
        Path rewritten = temp.resolve("relabelled.zip");
        writer.write(rewritten);
        Files.move(rewritten, aStore, StandardCopyOption.REPLACE_EXISTING);
    }
}
