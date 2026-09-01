package net.cumba.cdisc.core.metadata.pickle;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Stream;
import java.util.zip.GZIPOutputStream;
import net.cumba.cdisc.core.metadata.pickle.HttpArchivePickleSource.ExtractionLimits;
import net.razorvine.pickle.Pickler;
import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link HttpArchivePickleSource} against a real HTTP server on loopback serving a real
 * {@code .tar.gz}. Nothing here touches the public internet.
 */
class HttpArchivePickleSourceTest
{

    private static final String TOP = "cdisc-rules-engine-main";

    private HttpServer server;

    private String baseUri;

    private byte[] archive = new byte[0];

    private byte[] advertisement = new byte[0];

    private int archiveStatus = 200;

    @BeforeEach
    void startServer() throws IOException
    {
        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/repo/info/refs", this::serveAdvertisement);
        server.createContext("/repo/archive/", this::serveArchive);
        server.start();
        baseUri = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort() + "/repo";
    }


    @AfterEach
    void stopServer()
    {
        server.stop(0);
    }


    private void serveAdvertisement(HttpExchange aExchange) throws IOException
    {
        aExchange.sendResponseHeaders(200, advertisement.length);
        try (OutputStream out = aExchange.getResponseBody())
        {
            out.write(advertisement);
        }
    }


    private void serveArchive(HttpExchange aExchange) throws IOException
    {
        if (archiveStatus != 200)
        {
            aExchange.sendResponseHeaders(archiveStatus, -1);
            aExchange.close();
            return;
        }
        aExchange.sendResponseHeaders(200, archive.length);
        try (OutputStream out = aExchange.getResponseBody())
        {
            out.write(archive);
        }
    }

    // ------------------------------------------------------------------
    // Archive construction
    // ------------------------------------------------------------------

    /** Not a record: Error Prone rightly rejects array-typed record components. */
    private static final class Entry
    {

        private final String name;

        private final byte[] content;

        private final boolean symlink;

        Entry(String aName, byte[] aContent, boolean aSymlink)
        {
            name = aName;
            content = aContent.clone();
            symlink = aSymlink;
        }


        String name()
        {
            return name;
        }


        byte[] content()
        {
            return content.clone();
        }


        boolean symlink()
        {
            return symlink;
        }
    }

    private static byte[] pickle(Object aValue) throws IOException
    {
        return new Pickler().dumps(aValue);
    }


    private static byte[] tarGz(Entry... aEntries) throws IOException
    {
        ByteArrayOutputStream raw = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(raw);
                TarArchiveOutputStream tar = new TarArchiveOutputStream(gz))
        {
            tar.setLongFileMode(TarArchiveOutputStream.LONGFILE_POSIX);
            for (Entry e : aEntries)
            {
                TarArchiveEntry entry;
                if (e.symlink())
                {
                    entry = new TarArchiveEntry(e.name(), TarArchiveEntry.LF_SYMLINK);
                    entry.setLinkName("/etc/passwd");
                    entry.setSize(0);
                    tar.putArchiveEntry(entry);
                }
                else
                {
                    entry = new TarArchiveEntry(e.name());
                    entry.setSize(e.content().length);
                    tar.putArchiveEntry(entry);
                    tar.write(e.content());
                }
                tar.closeArchiveEntry();
            }
        }
        return raw.toByteArray();
    }


    private static Entry pkl(String aPath, String aKey) throws IOException
    {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put(aKey, Map.of("name", "x"));
        return new Entry(aPath, pickle(body), false);
    }


    private HttpArchivePickleSource source(Path aWorkDir)
    {
        return new HttpArchivePickleSource(baseUri, "main", "resources/cache", null, aWorkDir);
    }

    // ------------------------------------------------------------------
    // Tests
    // ------------------------------------------------------------------


    @Test
    void extractsOnlyTheWantedPickles(@TempDir Path work) throws IOException
    {
        archive = tarGz(pkl(TOP + "/resources/cache/standards_details.pkl", "standards/sdtmig/3-4"),
                pkl(TOP + "/resources/cache/standards_models.pkl", "models/sdtm/2-0"),
                pkl(TOP + "/resources/cache/sdtmct-2024-09-27.pkl", "package"),
                // Consumed by nobody in the seeder — must be skipped.
                pkl(TOP + "/resources/cache/variables_metadata.pkl", "library_variables_metadata"),
                pkl(TOP + "/resources/cache/rules.pkl", "rules"),
                // Outside the repo path.
                pkl(TOP + "/other/standards_details.pkl", "x"),
                new Entry(TOP + "/README.md", "hi".getBytes(StandardCharsets.UTF_8), false));

        Path dir;
        try (HttpArchivePickleSource s = source(work.resolve("out")))
        {
            dir = s.resolve();
        }

        assertTrue(Files.exists(dir.resolve("standards_details.pkl")));
        assertTrue(Files.exists(dir.resolve("standards_models.pkl")));
        assertTrue(Files.exists(dir.resolve("sdtmct-2024-09-27.pkl")));
        assertFalse(Files.exists(dir.resolve("variables_metadata.pkl")));
        assertFalse(Files.exists(dir.resolve("rules.pkl")));
        assertFalse(Files.exists(dir.resolve("README.md")));
    }


    /** The extracted directory must be directly usable by the seeder. */
    @Test
    void theExtractedDirectoryDrivesTheSeeder(@TempDir Path work) throws IOException
    {
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("package", "sdtmct-2024-09-27");
        ct.put("codelists", java.util.List.of());
        archive = tarGz(
                new Entry(TOP + "/resources/cache/sdtmct-2024-09-27.pkl", pickle(ct), false));

        Path cache = work.resolve("cache");
        try (HttpArchivePickleSource s = source(work.resolve("out")))
        {
            SeedReport report = new PickleCacheSeeder().seed(
                    SeedOptions.builder(s, cache, "https://api.library.cdisc.org/api/").build());
            assertEquals(1, report.ctPackagesWritten());
        }
        // The seeder keys by the request CoreJ issues, and CoreLibraryAccessImpl:201 fetches a CT
        // package with expand=true — hence the encoded query in the file name.
        assertTrue(Files.exists(
                cache.resolve("api_mdr_ct_packages_sdtmct-2024-09-27%3Fexpand%3Dtrue.json.gz")));
    }


    @Test
    void discoversTheDefaultBranchWhenNoRefIsGiven(@TempDir Path work) throws IOException
    {
        advertisement = ("001e# service=git-upload-pack\n0000015"
                + "8941161a4c7e1dd76ece0c0590d28dabcc11e5f2 HEAD\0symref=HEAD:refs/heads/trunk\n")
                        .getBytes(StandardCharsets.UTF_8);
        archive = tarGz(
                pkl(TOP + "/resources/cache/standards_details.pkl", "standards/sdtmig/3-4"));

        try (HttpArchivePickleSource s = new HttpArchivePickleSource(baseUri, null,
                "resources/cache", null, work.resolve("out")))
        {
            s.resolve();
            assertEquals("trunk", s.resolvedRef().orElse(null));
        }
    }


    @Test
    void fallsBackToHeadWhenNoSymrefIsAdvertised(@TempDir Path work) throws IOException
    {
        advertisement = "001e# service=git-upload-pack\n".getBytes(StandardCharsets.UTF_8);
        archive = tarGz(
                pkl(TOP + "/resources/cache/standards_details.pkl", "standards/sdtmig/3-4"));

        try (HttpArchivePickleSource s = new HttpArchivePickleSource(baseUri, null,
                "resources/cache", null, work.resolve("out")))
        {
            s.resolve();
            assertEquals("HEAD", s.resolvedRef().orElse(null));
            assertTrue(s.resolvedSha().isEmpty(), "no object id was advertised");
        }
    }

    // ------------------------------------------------------------------
    // Provenance (L3)
    // ------------------------------------------------------------------

    private static final String HEAD_SHA = "8941161a4c7e1dd76ece0c0590d28dabcc11e5f2";

    /** The advertised commit id reaches the seed report, so a seeded cache records its origin. */
    @Test
    void theResolvedShaIsRecordedInTheSeedReport(@TempDir Path work) throws IOException
    {
        advertisement = ("001e# service=git-upload-pack\n0000015" + HEAD_SHA
                + " HEAD\0symref=HEAD:refs/heads/trunk\n").getBytes(StandardCharsets.UTF_8);
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("package", "sdtmct-2024-09-27");
        ct.put("codelists", java.util.List.of());
        archive = tarGz(
                new Entry(TOP + "/resources/cache/sdtmct-2024-09-27.pkl", pickle(ct), false));

        SeedReport report;
        try (HttpArchivePickleSource s = new HttpArchivePickleSource(baseUri, null,
                "resources/cache", null, work.resolve("out")))
        {
            assertTrue(s.provenance().isEmpty(), "nothing is known before resolve()");
            report = new PickleCacheSeeder().seed(SeedOptions
                    .builder(s, work.resolve("cache"), "https://api.library.cdisc.org/api/")
                    .build());
            assertEquals(HEAD_SHA, s.resolvedSha().orElse(null));
        }

        assertEquals(baseUri + "@trunk (" + HEAD_SHA + ")", report.sourceRef());
        assertTrue(report.summary().contains(HEAD_SHA), report.summary());
    }


    /**
     * With an explicit ref no advertisement is fetched, so there is no commit id to record. The
     * report says what is known — the repository and the ref — and does not invent the rest.
     */
    @Test
    void anExplicitRefIsRecordedWithoutACommitId(@TempDir Path work) throws IOException
    {
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("package", "sdtmct-2024-09-27");
        ct.put("codelists", java.util.List.of());
        archive = tarGz(
                new Entry(TOP + "/resources/cache/sdtmct-2024-09-27.pkl", pickle(ct), false));

        SeedReport report;
        try (HttpArchivePickleSource s = source(work.resolve("out")))
        {
            report = new PickleCacheSeeder().seed(SeedOptions
                    .builder(s, work.resolve("cache"), "https://api.library.cdisc.org/api/")
                    .build());
            assertTrue(s.resolvedSha().isEmpty());
        }

        assertEquals(baseUri + "@main", report.sourceRef());
    }


    /** A local directory cannot identify itself, and says so rather than guessing. */
    @Test
    void aLocalSourceReportsNoProvenance(@TempDir Path work) throws IOException
    {
        Path pkl = Files.createDirectories(work.resolve("pkl"));
        Map<String, Object> ct = new LinkedHashMap<>();
        ct.put("package", "sdtmct-2024-09-27");
        ct.put("codelists", java.util.List.of());
        Files.write(pkl.resolve("sdtmct-2024-09-27.pkl"), pickle(ct));

        SeedReport report;
        try (LocalPickleSource s = new LocalPickleSource(pkl))
        {
            report = new PickleCacheSeeder().seed(SeedOptions
                    .builder(s, work.resolve("cache"), "https://api.library.cdisc.org/api/")
                    .build());
        }

        assertNull(report.sourceRef());
        assertFalse(report.summary().contains(" from "), report.summary());
    }


    @Test
    void symlinkEntriesAreIgnored(@TempDir Path work) throws IOException
    {
        archive = tarGz(
                new Entry(TOP + "/resources/cache/evil-ct-2024-09-27.pkl", new byte[0], true),
                pkl(TOP + "/resources/cache/standards_details.pkl", "standards/sdtmig/3-4"));

        Path dir;
        try (HttpArchivePickleSource s = source(work.resolve("out")))
        {
            dir = s.resolve();
        }

        assertFalse(Files.exists(dir.resolve("evil-ct-2024-09-27.pkl")));
        assertTrue(Files.exists(dir.resolve("standards_details.pkl")));
    }


    /**
     * A traversal entry must be <b>rejected</b>, not silently dropped.
     *
     * <p>
     * The predecessor of this test asserted that no file appeared at {@code <work>/escape-….pkl}
     * and passed — but that path is not where a missing guard would have put the file. With the
     * extraction root at {@code <work>/out}, an entry of {@code …/cache/../../../escape-….pkl}
     * resolves three levels <em>above</em> the root, i.e. outside {@code work} altogether. The
     * assertion could not fail whatever the production code did, and it was the only test claiming
     * to pin the path-traversal guard. Two things fix that here: the escape target is computed
     * rather than guessed (and the computation is itself asserted, so the fixture cannot rot into
     * aiming at an impossible path again), and the rejection is required to be an exception, which
     * a silent skip does not satisfy.
     * </p>
     */
    @Test
    void traversalEntriesAreRejected(@TempDir Path work) throws IOException
    {
        // Nested deeply enough that root/../../.. is still inside the @TempDir and observable.
        Path root = work.resolve("a/b/c/out");
        Path escapeTarget = root.resolve("../../../escape-ct-2024-09-27.pkl").normalize();
        assertEquals(work.resolve("a/escape-ct-2024-09-27.pkl"), escapeTarget,
                "the fixture must aim at the path an unguarded extraction would really write");

        archive = tarGz(pkl(TOP + "/resources/cache/../../../escape-ct-2024-09-27.pkl", "x"),
                pkl(TOP + "/resources/cache/standards_details.pkl", "standards/sdtmig/3-4"));

        try (HttpArchivePickleSource s = source(root))
        {
            IOException e = assertThrows(IOException.class, s::resolve);
            assertTrue(e.getMessage().contains("escapes the target directory"), e.getMessage());
        }

        assertFalse(Files.exists(escapeTarget), "the traversal entry must not have been written");
        // Belt and braces: nothing named like the hostile entry anywhere under the temp dir.
        try (Stream<Path> walk = Files.walk(work))
        {
            assertTrue(walk.noneMatch(p -> p.getFileName().toString().startsWith("escape-")),
                    "no escaped file may exist anywhere below the temp directory");
        }
    }


    /** An entry whose name resolves to an absolute path is caught by the same guard. */
    @Test
    void anEntryResolvingToAnAbsolutePathIsRejected(@TempDir Path work) throws IOException
    {
        // A doubled separator leaves "/escape-ct-….pkl" after the repo-path prefix is stripped,
        // which Path.resolve treats as absolute and therefore outside any root.
        archive = tarGz(pkl(TOP + "/resources/cache//escape-ct-2024-09-27.pkl", "x"));

        try (HttpArchivePickleSource s = source(work.resolve("out")))
        {
            IOException e = assertThrows(IOException.class, s::resolve);
            assertTrue(e.getMessage().contains("escapes the target directory"), e.getMessage());
        }
        assertFalse(Files.exists(Path.of("/escape-ct-2024-09-27.pkl")));
    }

    // ------------------------------------------------------------------
    // Extraction limits (M6)
    // ------------------------------------------------------------------


    /** Limits with everything but the entry cap set out of the way. */
    private static ExtractionLimits entryCap(long aBytes)
    {
        return new ExtractionLimits(aBytes, Long.MAX_VALUE, Long.MAX_VALUE, Long.MAX_VALUE);
    }


    private HttpArchivePickleSource source(Path aWorkDir, ExtractionLimits aLimits)
    {
        return new HttpArchivePickleSource(baseUri, "main", "resources/cache", null, aWorkDir,
                aLimits);
    }


    /** Bytes that are neither compressible nor a valid pickle — extraction never parses them. */
    private static Entry filler(String aName, int aSize)
    {
        byte[] content = new byte[aSize];
        for (int i = 0; i < aSize; i++)
        {
            content[i] = (byte) (i * 31 + 7);
        }
        return new Entry(aName, content, false);
    }


    /** An entry of exactly the cap is fine — the boundary is inclusive. */
    @Test
    void anEntryExactlyAtTheEntryCapIsExtracted(@TempDir Path work) throws IOException
    {
        archive = tarGz(filler(TOP + "/resources/cache/sdtmct-2024-09-27.pkl", 1024));

        Path dir;
        try (HttpArchivePickleSource s = source(work.resolve("out"), entryCap(1024)))
        {
            dir = s.resolve();
        }

        assertEquals(1024, Files.size(dir.resolve("sdtmct-2024-09-27.pkl")));
    }


    /** One byte over is refused — and refused before anything is written. */
    @Test
    void anEntryOneByteOverTheEntryCapIsRefusedWithoutWritingIt(@TempDir Path work)
        throws IOException
    {
        archive = tarGz(filler(TOP + "/resources/cache/sdtmct-2024-09-27.pkl", 1025));

        Path out = work.resolve("out");
        try (HttpArchivePickleSource s = source(out, entryCap(1024)))
        {
            IOException e = assertThrows(IOException.class, s::resolve);
            assertTrue(e.getMessage().contains("exceeds 1024 bytes"), e.getMessage());
        }
        assertFalse(Files.exists(out.resolve("sdtmct-2024-09-27.pkl")),
                "the over-sized entry must not be left on disk, not even partially");
    }


    /**
     * The total cap is consulted <em>before</em> the entry that would breach it is copied, so a run
     * can no longer overshoot by a whole entry — the M6 finding.
     */
    @Test
    void theTotalCapIsEnforcedBeforeTheOvershootingEntryIsWritten(@TempDir Path work)
        throws IOException
    {
        archive = tarGz(filler(TOP + "/resources/cache/sdtmct-2024-01-01.pkl", 1000),
                filler(TOP + "/resources/cache/sdtmct-2024-09-27.pkl", 1000));

        Path out = work.resolve("out");
        try (HttpArchivePickleSource s = source(out,
                new ExtractionLimits(Long.MAX_VALUE, 1500, Long.MAX_VALUE, Long.MAX_VALUE)))
        {
            IOException e = assertThrows(IOException.class, s::resolve);
            assertTrue(e.getMessage().contains("would exceed 1500 bytes"), e.getMessage());
        }
        assertEquals(1000, Files.size(out.resolve("sdtmct-2024-01-01.pkl")));
        assertFalse(Files.exists(out.resolve("sdtmct-2024-09-27.pkl")),
                "the second entry would have taken the run to 2000 bytes — it must not be written");
    }


    /** Two entries that land exactly on the total cap are both extracted. */
    @Test
    void anExtractionExactlyAtTheTotalCapSucceeds(@TempDir Path work) throws IOException
    {
        archive = tarGz(filler(TOP + "/resources/cache/sdtmct-2024-01-01.pkl", 1000),
                filler(TOP + "/resources/cache/sdtmct-2024-09-27.pkl", 1000));

        Path dir;
        try (HttpArchivePickleSource s = source(work.resolve("out"),
                new ExtractionLimits(Long.MAX_VALUE, 2000, Long.MAX_VALUE, Long.MAX_VALUE)))
        {
            dir = s.resolve();
        }

        assertEquals(1000, Files.size(dir.resolve("sdtmct-2024-01-01.pkl")));
        assertEquals(1000, Files.size(dir.resolve("sdtmct-2024-09-27.pkl")));
    }


    /**
     * A zip bomb is small on the wire and enormous on disk, so neither size cap sees it coming: a
     * megabyte of zeros is a couple of hundred compressed bytes. The ratio guard is what catches
     * it, and it stops the copy mid-entry rather than after it.
     */
    @Test
    void aHighlyCompressibleEntryTripsTheRatioGuard(@TempDir Path work) throws IOException
    {
        // 1 MiB of zeros: well inside both size caps, ~1000:1 on the wire.
        archive = tarGz(new Entry(TOP + "/resources/cache/sdtmct-2024-09-27.pkl",
                new byte[1024 * 1024], false));
        assertTrue(archive.length < 64 * 1024,
                "the fixture must actually be a bomb, compressed size: " + archive.length);

        Path out = work.resolve("out");
        try (HttpArchivePickleSource s = source(out,
                new ExtractionLimits(Long.MAX_VALUE, Long.MAX_VALUE, 10, 64 * 1024)))
        {
            IOException e = assertThrows(IOException.class, s::resolve);
            assertTrue(e.getMessage().contains("expands more than 10:1"), e.getMessage());
        }
        assertFalse(Files.exists(out.resolve("sdtmct-2024-09-27.pkl")),
                "the partial file must be removed, not left as a 1 MiB fragment");
    }


    /** The same fixture extracts cleanly under the ratio the real archive needs. */
    @Test
    void aCompressibleEntryUnderTheRatioIsExtracted(@TempDir Path work) throws IOException
    {
        archive = tarGz(new Entry(TOP + "/resources/cache/sdtmct-2024-09-27.pkl",
                new byte[1024 * 1024], false));

        Path dir;
        try (HttpArchivePickleSource s = source(work.resolve("out"),
                new ExtractionLimits(Long.MAX_VALUE, Long.MAX_VALUE, 100_000, 64 * 1024)))
        {
            dir = s.resolve();
        }

        assertEquals(1024 * 1024, Files.size(dir.resolve("sdtmct-2024-09-27.pkl")));
    }


    /**
     * The ratio allowance must saturate rather than wrap.
     *
     * <p>
     * A ratio limit near {@code Long.MAX_VALUE} is how a caller switches the guard off, and
     * multiplying it by the compressed byte count overflows. Asserted here rather than through an
     * extraction because whether the overflow lands on a negative number depends on the low bits of
     * the byte count: an end-to-end fixture happens to read an odd number of compressed bytes,
     * {@code Long.MAX_VALUE * odd} stays positive, and the test then passes against the wrapping
     * implementation — it was written that way first and could not be made to fail.
     * </p>
     */
    @Test
    void theRatioAllowanceSaturatesInsteadOfWrapping()
    {
        assertEquals(100_000L, HttpArchivePickleSource.ratioAllowance(100, 1000));
        // Long.MAX_VALUE * 2 wraps to -2; * 1024 wraps to -1024.
        assertEquals(Long.MAX_VALUE, HttpArchivePickleSource.ratioAllowance(Long.MAX_VALUE, 2));
        assertEquals(Long.MAX_VALUE, HttpArchivePickleSource.ratioAllowance(Long.MAX_VALUE, 1024));
        // (1 << 62) * 4 wraps to exactly 0 — an allowance of zero rejects every entry.
        assertEquals(Long.MAX_VALUE, HttpArchivePickleSource.ratioAllowance(1L << 62, 4));
        // Below the overflow threshold the exact product is kept.
        assertEquals(Long.MAX_VALUE, HttpArchivePickleSource.ratioAllowance(Long.MAX_VALUE, 1));
    }


    /** The production defaults are the ones the real archive is known to fit inside. */
    @Test
    void theDefaultLimitsAreTheDocumentedOnes()
    {
        ExtractionLimits defaults = ExtractionLimits.defaults();

        assertEquals(512L * 1024 * 1024, defaults.maxEntryBytes());
        assertEquals(2048L * 1024 * 1024, defaults.maxTotalBytes());
        assertEquals(100L, defaults.maxCompressionRatio());
        assertEquals(16L * 1024 * 1024, defaults.compressionRatioGraceBytes());
    }


    @Test
    void nonPositiveLimitsAreRejected()
    {
        assertThrows(IllegalArgumentException.class, () -> new ExtractionLimits(0, 1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExtractionLimits(1, -1, 1, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExtractionLimits(1, 1, 0, 1));
        assertThrows(IllegalArgumentException.class, () -> new ExtractionLimits(1, 1, 1, 0));
    }


    @Test
    void anArchiveWithNoMatchingPicklesIsAnError(@TempDir Path work) throws IOException
    {
        archive = tarGz(
                new Entry(TOP + "/README.md", "hi".getBytes(StandardCharsets.UTF_8), false));

        try (HttpArchivePickleSource s = source(work.resolve("out")))
        {
            IOException e = assertThrows(IOException.class, s::resolve);
            assertTrue(e.getMessage().contains("no *.pkl entries found"), e.getMessage());
        }
    }


    @Test
    void anHttpErrorIsReportedWithTheStatus(@TempDir Path work) throws IOException
    {
        archiveStatus = 404;

        try (HttpArchivePickleSource s = source(work.resolve("out")))
        {
            IOException e = assertThrows(IOException.class, s::resolve);
            assertTrue(e.getMessage().contains("404"), e.getMessage());
        }
    }


    /** A temp work dir is removed on close; a caller-supplied one is kept. */
    @Test
    void temporaryWorkDirectoryIsCleanedUpButASuppliedOneIsKept(@TempDir Path work)
        throws IOException
    {
        archive = tarGz(
                pkl(TOP + "/resources/cache/standards_details.pkl", "standards/sdtmig/3-4"));

        Path temp;
        try (HttpArchivePickleSource s = new HttpArchivePickleSource(baseUri, "main",
                "resources/cache", null, null))
        {
            temp = s.resolve();
            assertTrue(Files.exists(temp));
        }
        assertFalse(Files.exists(temp), "a temp work dir must not outlive the source");

        Path supplied = work.resolve("keepme");
        try (HttpArchivePickleSource s = source(supplied))
        {
            s.resolve();
        }
        assertTrue(Files.isDirectory(supplied), "a caller-supplied work dir must be left alone");
    }


    @Test
    void theArchiveUrlTemplateIsHonoured(@TempDir Path work) throws IOException
    {
        archive = tarGz(
                pkl(TOP + "/resources/cache/standards_details.pkl", "standards/sdtmig/3-4"));

        // GitLab-style layout, served by the same context prefix.
        try (HttpArchivePickleSource s = new HttpArchivePickleSource(baseUri, "main",
                "resources/cache", "{repo}/archive/{ref}/pkg.tar.gz", work.resolve("out")))
        {
            assertTrue(Files.exists(s.resolve().resolve("standards_details.pkl")));
        }
    }


    @Test
    void invalidConstructorArgumentsAreRejected()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new HttpArchivePickleSource("", "main", "resources/cache", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpArchivePickleSource(baseUri, "main", "/absolute", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpArchivePickleSource(baseUri, "main", "../escape", null, null));
        assertThrows(IllegalArgumentException.class,
                () -> new HttpArchivePickleSource(baseUri, "main", "  ", null, null));
    }
}
