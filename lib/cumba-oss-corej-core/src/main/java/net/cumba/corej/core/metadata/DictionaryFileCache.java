package net.cumba.corej.core.metadata;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import org.jspecify.annotations.Nullable;

/**
 * D8 — process-wide cache of parsed house-format dictionary files, keyed by file path and validated
 * against the file's size and modification time on every lookup.
 *
 * <p>
 * <b>Why it exists.</b> {@code StudyValidationService} builds its dictionary provider per
 * validation run, and the REST service builds a new {@code StudyValidationService} per HTTP request
 * — so with a real store every request would gzip-decompress and Jackson-parse a multi-MB
 * {@code unii.json.gz} (and {@link ValueMapDictionary}'s constructor would rebuild the folded twin
 * indexes) from scratch. The parse is the expensive step; everything around it — listing the store
 * directory, reading the small selection manifest, composing the provider — stays per-run, so
 * per-run concerns like the requested-version selection never leak into the cache key.
 * </p>
 *
 * <p>
 * <b>Freshness.</b> An entry is reused only while the file's ({@code size}, {@code mtime}) pair is
 * unchanged, so an install performed while a long-lived process is running is picked up on the next
 * run rather than masked. The attributes are read <em>before</em> the parse: if the file is
 * replaced mid-load, the stale attributes disagree with the replacement on the next lookup and the
 * entry self-heals. The one undetectable case is a rewrite that preserves both size and mtime
 * within the file system's timestamp granularity — the same limitation every mtime-validated cache
 * (e.g. {@code make}) accepts.
 * </p>
 *
 * <p>
 * <b>Concurrency.</b> A cold load is serialised per file through a dedicated per-key monitor, so
 * two concurrent requests arriving at a cold cache parse the file once, not twice — and, unlike a
 * {@link ConcurrentHashMap#compute} lambda (whose contract asks for <em>short</em> computations
 * because it pins a bin lock), a multi-second gzip+Jackson parse of one file never blocks threads
 * loading a <em>different</em> file. The cached {@link ValueMapDictionary} is immutable (all fields
 * final, never mutated after construction), so sharing one instance across concurrent validation
 * runs is safe. In the rare race where an eviction discards a per-key monitor mid-load, two threads
 * may parse the same file concurrently — a duplicated parse, never a wrong answer.
 * </p>
 *
 * <p>
 * <b>Eviction — the cache is bounded.</b> Values are strong references, so an unbounded map in a
 * long-lived REST service would pin every version ever selected for the life of the process —
 * multi-GB across a few MedDRA and UNII releases. After every fresh parse the cache (1) drops
 * entries whose files no longer exist (an uninstalled or replaced version), then (2) drops
 * least-recently-used entries until at most {@value #MAX_ENTRIES} remain. The bound is entries, not
 * bytes: a store holds one file per (type, version), so {@value #MAX_ENTRIES} comfortably covers
 * every type of a live run plus a version switch, while retiring releases nothing selects any more.
 * In the steady state (all hits) no sweep runs.
 * </p>
 */
final class DictionaryFileCache
{

    /** The LRU bound: the maximum number of parsed files kept alive at once. */
    static final int MAX_ENTRIES = 16;

    /** One cached parse; {@code lastUsed} is the logical access clock driving LRU eviction. */
    private static final class Entry
    {

        final long size;

        final FileTime mtime;

        final ValueMapDictionary dict;

        volatile long lastUsed;

        Entry(long aSize, FileTime aMtime, ValueMapDictionary aDict)
        {
            size = aSize;
            mtime = aMtime;
            dict = aDict;
        }
    }

    private static final ConcurrentHashMap<Path, Entry> CACHE = new ConcurrentHashMap<>();

    /** Per-file parse monitors, so concurrent cold loads of ONE file parse once. */
    private static final ConcurrentHashMap<Path, Object> LOCKS = new ConcurrentHashMap<>();

    /** Logical clock: monotonically increasing access stamps for LRU ordering. */
    private static final AtomicLong CLOCK = new AtomicLong();

    private DictionaryFileCache()
    {
    }


    /**
     * The parsed dictionary for {@code aFile} — the cached instance while the file's size and mtime
     * are unchanged, else a fresh parse.
     */
    static ValueMapDictionary load(Path aFile) throws IOException
    {
        Path key = aFile.toAbsolutePath().normalize();
        // Read the attributes BEFORE parsing: if the file is swapped between this read and the
        // parse, the entry carries the OLD attributes with the NEW content and the next lookup
        // reloads. The opposite order could pin stale content forever.
        BasicFileAttributes attrs = Files.readAttributes(key, BasicFileAttributes.class);
        Entry hit = freshEntry(key, attrs);
        if (hit != null)
        {
            return hit.dict;
        }
        Entry entry;
        synchronized (LOCKS.computeIfAbsent(key, _ -> new Object()))
        {
            // Double-checked under the per-file monitor: a concurrent loader may have parsed
            // this exact (size, mtime) already while we waited.
            hit = freshEntry(key, attrs);
            if (hit != null)
            {
                return hit.dict;
            }
            entry = new Entry(attrs.size(), attrs.lastModifiedTime(), ValueMapDictionary.load(key));
            entry.lastUsed = CLOCK.incrementAndGet();
            CACHE.put(key, entry);
        }
        evict();
        return entry.dict;
    }


    /** The cached entry when it matches the just-read attributes, touched for LRU; else null. */
    private static @Nullable Entry freshEntry(Path aKey, BasicFileAttributes aAttrs)
    {
        Entry entry = CACHE.get(aKey);
        if (entry != null && entry.size == aAttrs.size()
                && entry.mtime.equals(aAttrs.lastModifiedTime()))
        {
            entry.lastUsed = CLOCK.incrementAndGet();
            return entry;
        }
        return null;
    }


    /**
     * Runs after every fresh parse (i.e. an install or removal is in progress): drops entries whose
     * backing file vanished, then least-recently-used entries down to {@value #MAX_ENTRIES}. The
     * store holds a handful of files per installed version, so the sweep is trivial when it runs.
     */
    private static void evict()
    {
        for (Path p : CACHE.keySet())
        {
            if (!Files.exists(p))
            {
                remove(p);
            }
        }
        while (CACHE.size() > MAX_ENTRIES)
        {
            Path eldest = null;
            long eldestUsed = Long.MAX_VALUE;
            for (Map.Entry<Path, Entry> e : CACHE.entrySet())
            {
                long used = e.getValue().lastUsed;
                if (used < eldestUsed)
                {
                    eldestUsed = used;
                    eldest = e.getKey();
                }
            }
            if (eldest == null)
            {
                return;
            }
            remove(eldest);
        }
    }


    private static void remove(Path aKey)
    {
        CACHE.remove(aKey);
        // Drop the parse monitor too, or LOCKS would grow with every file ever seen. A loader
        // holding the discarded monitor at this instant merely risks one duplicated parse.
        LOCKS.remove(aKey);
    }

}
