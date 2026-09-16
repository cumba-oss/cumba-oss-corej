package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The writer's two content comparators and its reproducible-output guarantee.
 *
 * <h2>Why this class exists</h2>
 *
 * <p>
 * Measured 2026-09-14: four of {@link MetadataStoreWriter}'s mutants had
 * {@code StoreSeederRealDataConformanceTest} as their only killer — the 22-second real-corpus
 * integration test that costs this module's mutation gate ~23 minutes on its own. The three
 * behaviours below are what that test was covering incidentally, and they are all reachable in
 * milliseconds from hand-built fixtures.
 * </p>
 *
 * <h2>⚠ What the comparators are FOR</h2>
 *
 * <p>
 * The store's term and codelist-version tables are content-addressed: identical content must
 * collapse onto one row (CT packages are cumulative snapshots, so the same term appears in dozens),
 * and differing content must not. Both properties run through the comparators, so a comparator that
 * reports "equal" too eagerly silently <b>loses data</b>, and one that reports "different" too
 * eagerly silently <b>bloats the store</b>. Neither shows up as an exception.
 * </p>
 */
class MetadataStoreWriterOrderTest
{

    @TempDir
    Path tempDir;

    /**
     * ⭐ Two terms alike in every scalar field and differing only in a synonym, at equal list length
     * — the case that reaches the element loop in {@code STRING_LIST_ORDER} and needs it to do its
     * job.
     *
     * <p>
     * A comparator that skipped the loop would fall through to {@code Integer.compare(size, size)}
     * = 0, declare the two terms identical, and <b>collapse them into one</b>: silent data loss, no
     * error. A comparator that ran the loop one step too far would index past the end of a list
     * whose elements it had already exhausted.
     * </p>
     */
    @Test
    void twoTermsDifferingOnlyInOneSynonymAreDistinct() throws IOException
    {
        StoredTerm zulu = new StoredTerm("X", "C1", "X", "d", List.of("alpha", "zulu"));
        StoredTerm beta = new StoredTerm("X", "C1", "X", "d", List.of("alpha", "beta"));

        List<StoredTerm> terms = readBackTerms(
                new StoredCtPackage("sdtmct-2020-01-01", List.of(new StoredCodelist("CL", "C0",
                        "CL", null, null, Boolean.TRUE, List.of(zulu, beta)))));

        assertEquals(2, terms.size(),
                "the synonym lists differ, so the terms are distinct and neither may be dropped");
        Set<List<String>> synonyms = terms.stream().map(StoredTerm::synonyms)
                .collect(Collectors.toSet());
        assertEquals(Set.of(List.of("alpha", "zulu"), List.of("alpha", "beta")), synonyms);
    }


    /**
     * The companion: identical synonym lists must compare equal and dedup, which is what makes the
     * loop's terminal condition reachable at all.
     *
     * <p>
     * ⚠ This is the half that catches an off-by-one in the loop bound rather than a skipped loop —
     * the two lists are exhausted together, so a comparator that takes one more step reads past the
     * end of both.
     * </p>
     */
    @Test
    void identicalTermsAcrossPackagesCollapseOntoOneRow() throws IOException
    {
        StoredTerm term = new StoredTerm("X", "C1", "X", "d", List.of("alpha", "zulu"));
        StoredCodelist cl = new StoredCodelist("CL", "C0", "CL", null, List.of("s1", "s2"),
                Boolean.TRUE, List.of(term));

        Path file = tempDir.resolve("dedup.zip");
        new MetadataStoreWriter()
                .addCtPackage(new StoredCtPackage("sdtmct-2020-01-01", List.of(cl)))
                .addCtPackage(new StoredCtPackage("sdtmct-2020-06-01", List.of(cl)))
                .publishedCtPackages(List.of("sdtmct-2020-01-01", "sdtmct-2020-06-01")).write(file);

        try (MetadataStore store = MetadataStore.open(file))
        {
            List<StoredTerm> a = store.ctPackage("sdtmct-2020-01-01").orElseThrow().codelists()
                    .get(0).terms();
            List<StoredTerm> b = store.ctPackage("sdtmct-2020-06-01").orElseThrow().codelists()
                    .get(0).terms();
            assertEquals(a, b, "the same content in two packages reads back identically");
            assertEquals(1, a.size());
            assertEquals(List.of("alpha", "zulu"), a.get(0).synonyms());
        }
    }


    /**
     * ⭐ Two codelist versions alike in every header field, differing only in which terms they carry
     * — the case that falls all the way through {@code CODELIST_ORDER} to its term-id comparator.
     *
     * <p>
     * Every earlier key (submission value, concept id, preferred term, definition, synonyms,
     * extensible) is deliberately equal, so if the term-id comparison were skipped or mis-bounded
     * the two versions would collapse and one package would read back the <b>other package's</b>
     * terms. CT packages are cumulative snapshots that revise codelists in place, so this is the
     * ordinary case, not an exotic one.
     * </p>
     */
    @Test
    void twoCodelistVersionsDifferingOnlyInTheirTermsStayDistinct() throws IOException
    {
        StoredTerm shared = new StoredTerm("A", "C-A", "A", null, List.of("sa"));
        StoredTerm onlyV1 = new StoredTerm("B", "C-B", "B", null, List.of("sb"));
        StoredTerm onlyV2 = new StoredTerm("C", "C-C", "C", null, List.of("sc"));

        // Identical headers; same term COUNT, different term content.
        StoredCodelist v1 = new StoredCodelist("NY", "C66742", "No Yes Response", "same def",
                List.of("syn"), Boolean.FALSE, List.of(shared, onlyV1));
        StoredCodelist v2 = new StoredCodelist("NY", "C66742", "No Yes Response", "same def",
                List.of("syn"), Boolean.FALSE, List.of(shared, onlyV2));

        Path file = tempDir.resolve("versions.zip");
        new MetadataStoreWriter()
                .addCtPackage(new StoredCtPackage("sdtmct-2021-01-01", List.of(v1)))
                .addCtPackage(new StoredCtPackage("sdtmct-2021-06-01", List.of(v2)))
                .publishedCtPackages(List.of("sdtmct-2021-01-01", "sdtmct-2021-06-01")).write(file);

        try (MetadataStore store = MetadataStore.open(file))
        {
            Set<String> p1 = submissionValues(store, "sdtmct-2021-01-01");
            Set<String> p2 = submissionValues(store, "sdtmct-2021-06-01");
            assertEquals(Set.of("A", "B"), p1, "the earlier package keeps its own terms");
            assertEquals(Set.of("A", "C"), p2, "the later package keeps its own terms");
            assertNotEquals(p1, p2, "the two versions did not collapse onto one another");
        }
    }


    /**
     * ⭐⭐ Every zip entry carries the fixed {@code ENTRY_TIME}, which is what makes the store
     * byte-reproducible.
     *
     * <p>
     * ⚠ The obvious test — write twice and compare the bytes — is <b>unsound here</b>, and the
     * reason is worth keeping: zip stores timestamps at two-second resolution, so two writes in the
     * same second produce identical bytes even with the stamping removed. That test would pass
     * against the defect it exists to catch, on a fast machine, every time. Asserting the stamped
     * value itself has no such hole.
     * </p>
     */
    @Test
    void everyEntryCarriesTheFixedTimestamp() throws IOException
    {
        Path file = tempDir.resolve("stamped.zip");
        MetadataStoreFixtures.populatedWriter().write(file);

        LocalDateTime expected = LocalDateTime.of(2026, 1, 1, 0, 0, 0);
        int checked = 0;
        try (ZipFile zip = new ZipFile(file.toFile()))
        {
            for (ZipEntry entry : zip.stream().toList())
            {
                assertEquals(expected, entry.getTimeLocal(),
                        "entry '" + entry.getName() + "' must carry the fixed timestamp");
                checked++;
            }
        }
        // ⚠ Pin the POPULATION, not just the property: an empty or near-empty zip would satisfy
        // the loop above while proving nothing about the writer.
        assertTrue(checked >= 5, "expected the populated fixture's entries, saw " + checked);
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------


    private List<StoredTerm> readBackTerms(StoredCtPackage aPackage) throws IOException
    {
        Path file = tempDir.resolve(aPackage.id() + ".zip");
        new MetadataStoreWriter().addCtPackage(aPackage).publishedCtPackages(List.of(aPackage.id()))
                .write(file);
        try (MetadataStore store = MetadataStore.open(file))
        {
            return store.ctPackage(aPackage.id()).orElseThrow().codelists().get(0).terms();
        }
    }


    private static Set<String> submissionValues(MetadataStore aStore, String aPackageId)
    {
        return aStore.ctPackage(aPackageId).orElseThrow().codelists().get(0).terms().stream()
                .map(StoredTerm::submissionValue).collect(Collectors.toSet());
    }
}
