package net.cumba.cdisc.core.metadata.pickle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import net.razorvine.pickle.Pickler;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 1 — key enumeration on {@link PickleCache} plus {@link LocalPickleSource}.
 *
 * <p>
 * Fixtures are pickled at runtime with {@link Pickler}, so the tests are hermetic: they do not
 * depend on the {@code cdisc-rules-engine} submodule being checked out.
 * </p>
 */
class PickleCacheKeysTest
{

    /** Writes {@code <stem>.pkl} containing the given map, pickled as Python would. */
    static void writePickle(Path aDir, String aStem, Map<String, Object> aContents)
        throws IOException
    {
        Files.write(aDir.resolve(aStem + ".pkl"), new Pickler().dumps(aContents));
    }


    private static Map<String, Object> selfLinked(String aHref)
    {
        Map<String, Object> self = new LinkedHashMap<>();
        self.put("href", aHref);
        Map<String, Object> links = new LinkedHashMap<>();
        links.put("self", self);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("_links", links);
        return body;
    }


    @Test
    void standardKeysEnumeratesStandardsDetails(@TempDir Path dir) throws IOException
    {
        Map<String, Object> standards = new LinkedHashMap<>();
        standards.put("standards/sdtmig/3-4", selfLinked("/mdr/sdtmig/3-4"));
        standards.put("standards/adam/adamig-1-3", selfLinked("/mdr/adam/adamig-1-3"));
        standards.put("standards/tig/1-0/sdtm", selfLinked("/mdr/integrated/tig/1-0/sdtm"));
        writePickle(dir, "standards_details", standards);

        PickleCache cache = PickleCache.open(dir);

        assertEquals(Set.of("standards/sdtmig/3-4", "standards/adam/adamig-1-3",
                "standards/tig/1-0/sdtm"), cache.standardKeys());
    }


    @Test
    void modelKeysEnumeratesStandardsModels(@TempDir Path dir) throws IOException
    {
        Map<String, Object> models = new LinkedHashMap<>();
        models.put("models/sdtm/2-0", selfLinked("/mdr/sdtm/2-0"));
        models.put("models/adam/2-1", selfLinked("/mdr/adam/adam-2-1"));
        writePickle(dir, "standards_models", models);

        PickleCache cache = PickleCache.open(dir);

        assertEquals(Set.of("models/sdtm/2-0", "models/adam/2-1"), cache.modelKeys());
    }


    /** An absent file yields an empty set rather than throwing — callers degrade. */
    @Test
    void missingFilesYieldEmptyKeySets(@TempDir Path dir)
    {
        PickleCache cache = PickleCache.open(dir);

        assertTrue(cache.standardKeys().isEmpty());
        assertTrue(cache.modelKeys().isEmpty());
    }


    /** Enumeration and lookup agree: every enumerated key resolves. */
    @Test
    void everyEnumeratedKeyResolves(@TempDir Path dir) throws IOException
    {
        Map<String, Object> standards = new LinkedHashMap<>();
        standards.put("standards/sdtmig/3-4", selfLinked("/mdr/sdtmig/3-4"));
        writePickle(dir, "standards_details", standards);

        PickleCache cache = PickleCache.open(dir);

        for (String key : cache.standardKeys())
        {
            assertTrue(cache.get(key).isPresent(), key);
        }
    }


    /** The returned sets are unmodifiable — callers cannot corrupt the memoised file map. */
    @Test
    void keySetsAreUnmodifiable(@TempDir Path dir) throws IOException
    {
        writePickle(dir, "standards_details",
                new LinkedHashMap<>(Map.of("standards/sdtmig/3-4", selfLinked("/mdr/sdtmig/3-4"))));

        Set<String> keys = PickleCache.open(dir).standardKeys();

        assertThrows(UnsupportedOperationException.class, () -> keys.add("standards/x/1"));
    }


    @Test
    void localPickleSourceResolvesAnExistingDirectory(@TempDir Path dir) throws IOException
    {
        try (LocalPickleSource source = new LocalPickleSource(dir))
        {
            assertEquals(dir, source.resolve());
        }
        // close() must not remove a directory it does not own.
        assertTrue(Files.isDirectory(dir));
    }


    @Test
    void localPickleSourceRejectsMissingDirectory(@TempDir Path dir)
    {
        try (LocalPickleSource source = new LocalPickleSource(dir.resolve("absent")))
        {
            assertThrows(NoSuchFileException.class, source::resolve);
        }
    }


    @Test
    void localPickleSourceRejectsARegularFile(@TempDir Path dir) throws IOException
    {
        Path file = Files.writeString(dir.resolve("not-a-dir.txt"), "x");
        try (LocalPickleSource source = new LocalPickleSource(file))
        {
            assertThrows(NotDirectoryException.class, source::resolve);
        }
    }
}
