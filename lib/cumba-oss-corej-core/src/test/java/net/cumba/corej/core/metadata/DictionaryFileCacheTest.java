package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * D8 — the per-file dictionary parse cache behind {@link RuntimeDictionaryProvider#loadDirectory}.
 *
 * <p>
 * The two halves of the contract carry equal weight: an unchanged file must yield the <em>same</em>
 * parsed instance (or the REST service re-parses a multi-MB store per request), and a changed file
 * must yield a <em>fresh</em> one (or an install during a long-lived process is masked until
 * restart — the exact failure D8 forbids).
 * </p>
 */
class DictionaryFileCacheTest
{

    private static final String UNII_A = "{\"type\":\"unii\",\"levels\":{\"UNII\":{\"AAAA\":\"AAAA\"}}}";

    /** Same length as {@link #UNII_A}, different content — the size-preserving rewrite. */
    private static final String UNII_B = "{\"type\":\"unii\",\"levels\":{\"UNII\":{\"BBBB\":\"BBBB\"}}}";

    @Test
    void unchangedFileYieldsTheSameParsedInstance(@TempDir Path dir) throws IOException
    {
        Path file = dir.resolve("unii.json");
        Files.writeString(file, UNII_A);
        ValueMapDictionary first = DictionaryFileCache.load(file);
        ValueMapDictionary second = DictionaryFileCache.load(file);
        assertSame(first, second, "an unchanged file must be served from the cache");
        assertTrue(second.isValidTerm("UNII", "AAAA"));
    }


    @Test
    void changedSizeIsReloaded(@TempDir Path dir) throws IOException
    {
        Path file = dir.resolve("unii.json");
        Files.writeString(file, UNII_A);
        ValueMapDictionary first = DictionaryFileCache.load(file);
        Files.writeString(file,
                "{\"type\":\"unii\",\"levels\":{\"UNII\":{\"CCCCCCCC\":\"CCCCCCCC\"}}}");
        ValueMapDictionary second = DictionaryFileCache.load(file);
        assertNotSame(first, second, "a size change must invalidate the entry");
        assertTrue(second.isValidTerm("UNII", "CCCCCCCC"), "the fresh content is served");
        assertFalse(second.isValidTerm("UNII", "AAAA"), "the stale content is gone");
    }


    @Test
    void sizePreservingRewriteIsReloadedOnAnMtimeChange(@TempDir Path dir) throws IOException
    {
        Path file = dir.resolve("unii.json");
        Files.writeString(file, UNII_A);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000_000_000L));
        ValueMapDictionary first = DictionaryFileCache.load(file);
        // Same byte count, different content, explicitly bumped mtime — the install-over-install
        // shape a coarse size-only key would miss.
        Files.writeString(file, UNII_B);
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000_002_000L));
        ValueMapDictionary second = DictionaryFileCache.load(file);
        assertNotSame(first, second, "an mtime change must invalidate the entry");
        assertTrue(second.isValidTerm("UNII", "BBBB"));
        assertFalse(second.isValidTerm("UNII", "AAAA"));
    }


    @Test
    void vanishedFilesAreEvictedOnTheNextFreshLoad(@TempDir Path dir) throws IOException
    {
        Path fileA = dir.resolve("unii.json");
        Path fileB = dir.resolve("medrt.json");
        Files.writeString(fileA, UNII_A);
        Files.setLastModifiedTime(fileA, FileTime.fromMillis(1_000_000_000_000L));
        DictionaryFileCache.load(fileA);
        Files.delete(fileA);
        // A fresh load elsewhere sweeps the vanished entry.
        Files.writeString(fileB, "{\"type\":\"medrt\",\"levels\":{\"MEDRT\":{\"X\":\"X\"}}}");
        DictionaryFileCache.load(fileB);
        // Recreate fileA with the SAME size and mtime but different content: were the stale entry
        // still cached, it would (wrongly) be served; after eviction the new content must appear.
        Files.writeString(fileA, UNII_B);
        Files.setLastModifiedTime(fileA, FileTime.fromMillis(1_000_000_000_000L));
        ValueMapDictionary reloaded = DictionaryFileCache.load(fileA);
        assertTrue(reloaded.isValidTerm("UNII", "BBBB"),
                "a deleted file's entry must not survive its recreation");
        assertFalse(reloaded.isValidTerm("UNII", "AAAA"));
    }


    /**
     * A parse failure must surface as the original {@link IOException} (not the internal unchecked
     * wrapper the compute lambda uses), and must not poison the cache: fixing the file makes the
     * next load succeed.
     */
    @Test
    void aParseFailureRethrowsTheIoExceptionAndIsNotCached(@TempDir Path dir) throws IOException
    {
        Path file = dir.resolve("unii.json");
        Files.writeString(file, "this is not json at all {{{");
        org.junit.jupiter.api.Assertions.assertThrows(IOException.class,
                () -> DictionaryFileCache.load(file));
        Files.writeString(file, UNII_A);
        assertTrue(DictionaryFileCache.load(file).isValidTerm("UNII", "AAAA"),
                "a repaired file loads on the next attempt");
    }


    /**
     * Batch B9 — the cache is bounded. Strong values plus no LRU would pin every version ever
     * selected for the life of a REST process (multi-GB across a few MedDRA/UNII releases), so
     * after {@link DictionaryFileCache#MAX_ENTRIES} distinct live files the least-recently-used
     * entry is dropped — while the most recent ones keep the parse-once property.
     */
    @Test
    void theLeastRecentlyUsedEntryIsEvictedBeyondTheBound(@TempDir Path dir) throws IOException
    {
        int n = DictionaryFileCache.MAX_ENTRIES + 1;
        Path[] files = new Path[n];
        ValueMapDictionary[] first = new ValueMapDictionary[n];
        for (int i = 0; i < n; i++)
        {
            files[i] = dir.resolve("unii-" + i + ".json");
            Files.writeString(files[i],
                    "{\"type\":\"unii\",\"levels\":{\"UNII\":{\"T" + i + "\":\"T" + i + "\"}}}");
            first[i] = DictionaryFileCache.load(files[i]);
        }
        // files[0] is now the globally least-recently-used entry and must have been evicted;
        // the most recent one is still served from the cache.
        assertSame(first[n - 1], DictionaryFileCache.load(files[n - 1]),
                "a recently used entry stays cached");
        assertNotSame(first[0], DictionaryFileCache.load(files[0]),
                "the LRU entry beyond the bound must have been evicted and re-parsed");
    }


    /** Freshness survives eviction: a re-parsed entry serves the CURRENT file content. */
    @Test
    void anEvictedAndReloadedFileServesItsCurrentContent(@TempDir Path dir) throws IOException
    {
        Path victim = dir.resolve("unii-victim.json");
        Files.writeString(victim, UNII_A);
        DictionaryFileCache.load(victim);
        Files.writeString(victim, UNII_B); // same length — only mtime/eviction can catch this
        for (int i = 0; i < DictionaryFileCache.MAX_ENTRIES + 1; i++)
        {
            Path filler = dir.resolve("filler-" + i + ".json");
            Files.writeString(filler,
                    "{\"type\":\"unii\",\"levels\":{\"UNII\":{\"F" + i + "\":\"F" + i + "\"}}}");
            DictionaryFileCache.load(filler);
        }
        assertTrue(DictionaryFileCache.load(victim).isValidTerm("UNII", "BBBB"),
                "after eviction the reload must serve the rewritten content");
    }


    @Test
    void loadDirectoryServesCachedInstancesAcrossCalls(@TempDir Path dir) throws IOException
    {
        Files.writeString(dir.resolve("unii.json"), UNII_A);
        RuntimeDictionaryProvider first = RuntimeDictionaryProvider.loadDirectory(dir);
        RuntimeDictionaryProvider second = RuntimeDictionaryProvider.loadDirectory(dir);
        assertSame(first.dictionaryOf("unii"), second.dictionaryOf("unii"),
                "loadDirectory must reuse the per-file cache — this is the per-request path");
        assertTrue(second.isAvailable("unii"));
    }

}
