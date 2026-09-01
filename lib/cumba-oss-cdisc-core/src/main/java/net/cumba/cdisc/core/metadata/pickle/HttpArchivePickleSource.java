package net.cumba.cdisc.core.metadata.pickle;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Comparator;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.LongSupplier;
import java.util.zip.GZIPInputStream;

import org.apache.commons.compress.archivers.tar.TarArchiveEntry;
import org.apache.commons.compress.archivers.tar.TarArchiveInputStream;
import org.jspecify.annotations.Nullable;

/**
 * A {@link PickleSource} that downloads the pickle cache straight from a repository's source
 * archive over plain HTTPS — no {@code git} binary, no JGit, no forge API.
 *
 * <p>
 * Two requests. The first, {@code <repo>/info/refs?service=git-upload-pack}, is git's smart-HTTP
 * advertisement and yields the default branch (see {@link GitRefDiscovery}); it is skipped when an
 * explicit ref is supplied. The second fetches {@code <repo>/archive/<ref>.tar.gz} — the form
 * GitHub and Gitea share — and is streamed straight through gzip and tar, so the ~80 MB archive is
 * never buffered or written to disk.
 * </p>
 *
 * <p>
 * Only the pickles the seeder consumes are extracted: {@code standards_details.pkl},
 * {@code standards_models.pkl}, and the per-package CT files. That skips
 * {@code variables_metadata.pkl}, the rules pickles and the derived index maps. Extraction is
 * hardened against hostile archives — path traversal, absolute paths, links, runaway sizes and
 * runaway <em>expansion</em> are all rejected, the last three under the caller-visible
 * {@link ExtractionLimits}.
 * </p>
 */
public final class HttpArchivePickleSource implements PickleSource
{

    /** The archive URL shape GitHub and Gitea share. GitLab differs; override the template. */
    public static final String DEFAULT_ARCHIVE_URL_TEMPLATE = "{repo}/archive/{ref}.tar.gz";

    /** The public upstream that ships the pickle cache. */
    public static final String DEFAULT_REPO_URI = "https://github.com/cdisc-org/cdisc-rules-engine";

    /** The path within the repository holding the pickles. */
    public static final String DEFAULT_REPO_PATH = "resources/cache";

    /** Files the seeder actually reads, beyond the CT packages matched by {@code ct-}. */
    private static final Set<String> WANTED_STEMS = Set.of("standards_details.pkl",
            "standards_models.pkl");

    private static final int MAX_ADVERTISEMENT_BYTES = 64 * 1024;

    /** Copy buffer; also the granularity at which the streaming limits are re-checked. */
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    private final String repoUri;

    private final @Nullable String ref;

    private final String repoPath;

    private final String archiveUrlTemplate;

    private final @Nullable Path workDir;

    private final ExtractionLimits limits;

    private final HttpClient http;

    private @Nullable Path created;

    private @Nullable String resolvedRef;

    private @Nullable String resolvedSha;

    /**
     * Creates a source with the {@linkplain ExtractionLimits#defaults() default extraction limits}.
     *
     * @param aRepoUri
     *            the repository URI, e.g. {@code https://github.com/cdisc-org/cdisc-rules-engine}.
     * @param aRef
     *            the branch, tag or commit to fetch; {@code null} discovers the default branch.
     * @param aRepoPath
     *            the path inside the repository holding the pickles.
     * @param aArchiveUrlTemplate
     *            the archive URL template with {@code {repo}} and {@code {ref}} placeholders;
     *            {@code null} uses {@link #DEFAULT_ARCHIVE_URL_TEMPLATE}.
     * @param aWorkDir
     *            where to extract; {@code null} uses a temporary directory removed by
     *            {@link #close()}.
     */
    public HttpArchivePickleSource(String aRepoUri, @Nullable String aRef, String aRepoPath,
            @Nullable String aArchiveUrlTemplate, @Nullable Path aWorkDir)
    {
        this(aRepoUri, aRef, aRepoPath, aArchiveUrlTemplate, aWorkDir, ExtractionLimits.defaults());
    }


    /**
     * Creates a source with explicit extraction limits.
     *
     * @param aRepoUri
     *            the repository URI, e.g. {@code https://github.com/cdisc-org/cdisc-rules-engine}.
     * @param aRef
     *            the branch, tag or commit to fetch; {@code null} discovers the default branch.
     * @param aRepoPath
     *            the path inside the repository holding the pickles.
     * @param aArchiveUrlTemplate
     *            the archive URL template with {@code {repo}} and {@code {ref}} placeholders;
     *            {@code null} uses {@link #DEFAULT_ARCHIVE_URL_TEMPLATE}.
     * @param aWorkDir
     *            where to extract; {@code null} uses a temporary directory removed by
     *            {@link #close()}.
     * @param aLimits
     *            the size and compression-ratio limits enforced while extracting.
     */
    public HttpArchivePickleSource(String aRepoUri, @Nullable String aRef, String aRepoPath,
            @Nullable String aArchiveUrlTemplate, @Nullable Path aWorkDir, ExtractionLimits aLimits)
    {
        repoUri = stripTrailingSlash(requireUsable(aRepoUri, "repoUri"));
        ref = aRef;
        repoPath = normaliseRepoPath(aRepoPath);
        archiveUrlTemplate = aArchiveUrlTemplate == null ? DEFAULT_ARCHIVE_URL_TEMPLATE
                : aArchiveUrlTemplate;
        workDir = aWorkDir;
        limits = Objects.requireNonNull(aLimits, "limits");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }


    /**
     * The ref actually fetched — the supplied one, or the discovered default branch.
     *
     * @return the ref, or empty before {@link #resolve()} has run.
     */
    public Optional<String> resolvedRef()
    {
        return Optional.ofNullable(resolvedRef);
    }


    /**
     * The commit id the fetched archive came from, when it could be established.
     *
     * <p>
     * It comes from the ref advertisement, which is only requested when no explicit ref was given —
     * so a caller that pins a ref gets an empty result here. That is the honest answer: the archive
     * endpoint returns no commit id of its own, and inventing one from the ref name would record
     * provenance that was never verified.
     * </p>
     *
     * @return the 40-character commit id, or empty before {@link #resolve()} has run, when an
     *         explicit ref was supplied, or when the server advertised no {@code HEAD} object id.
     */
    public Optional<String> resolvedSha()
    {
        return Optional.ofNullable(resolvedSha);
    }


    @Override
    public Optional<String> provenance()
    {
        String effectiveRef = resolvedRef;
        if (effectiveRef == null)
        {
            return Optional.empty();
        }
        String sha = resolvedSha;
        return Optional.of(repoUri + "@" + effectiveRef + (sha == null ? "" : " (" + sha + ")"));
    }


    @Override
    public Path resolve() throws IOException
    {
        String effectiveRef = ref != null && !ref.isBlank() ? ref : discoverDefaultBranch();
        resolvedRef = effectiveRef;

        Path target = workDir != null ? Files.createDirectories(workDir)
                : Files.createTempDirectory("corej-pickle-");
        if (workDir == null)
        {
            created = target;
        }

        URI archive = URI.create(archiveUrlTemplate.replace("{repo}", repoUri)//
                .replace("{ref}", effectiveRef));
        int extracted = download(archive, target);
        if (extracted == 0)
        {
            throw new IOException("no *.pkl entries found under '" + repoPath + "' in " + archive
                    + " — check the repository path and ref");
        }
        return target;
    }


    @Override
    public void close() throws IOException
    {
        // The JDK HttpClient owns a selector thread and an executor; leaving it open leaks both
        // on every seed run, which matters in a long-lived REST process.
        http.close();
        Path dir = created;
        created = null;
        if (dir == null || !Files.exists(dir))
        {
            return;
        }
        try (var walk = Files.walk(dir))
        {
            walk.sorted(Comparator.reverseOrder()).forEach(p ->
            {
                try
                {
                    Files.deleteIfExists(p);
                }
                catch (IOException e)
                {
                    throw new UncheckedIOException(e);
                }
            });
        }
        catch (UncheckedIOException e)
        {
            throw e.getCause();
        }
    }

    /**
     * The size and compression limits enforced while extracting an archive.
     *
     * <p>
     * Injectable rather than hard-wired so the boundaries can be exercised by a test with a
     * kilobyte-sized fixture instead of a gigabyte-sized one — an untestable guard is a guard
     * nobody can prove still works.
     * </p>
     *
     * @param maxEntryBytes
     *            the largest single extracted entry, in bytes.
     * @param maxTotalBytes
     *            the largest total extraction, in bytes.
     * @param maxCompressionRatio
     *            the largest tolerated ratio of bytes written to compressed bytes read — a zip-bomb
     *            guard, since a bomb is small on the wire and enormous on disk.
     * @param compressionRatioGraceBytes
     *            how much may be written before the ratio is enforced at all. Without it a
     *            legitimate small archive, whose first kilobytes inflate from almost nothing, would
     *            trip the ratio on statistical noise.
     */
    public record ExtractionLimits(long maxEntryBytes, long maxTotalBytes, long maxCompressionRatio,
            long compressionRatioGraceBytes)
    {

        /** Validates that every limit is positive. */
        public ExtractionLimits
        {
            requirePositive(maxEntryBytes, "maxEntryBytes");
            requirePositive(maxTotalBytes, "maxTotalBytes");
            requirePositive(maxCompressionRatio, "maxCompressionRatio");
            requirePositive(compressionRatioGraceBytes, "compressionRatioGraceBytes");
        }


        /**
         * The production limits: 512 MiB per entry, 2 GiB in total, and a 100:1 compression ratio
         * once 16 MiB have been written.
         *
         * <p>
         * The real upstream archive extracts roughly 420 MiB of pickles from an ~80 MiB download,
         * i.e. about 5:1 — so 100:1 leaves an order of magnitude of headroom while still being far
         * below what a bomb needs to be dangerous.
         * </p>
         *
         * @return the default limits.
         */
        public static ExtractionLimits defaults()
        {
            return new ExtractionLimits(512L * 1024 * 1024, 2048L * 1024 * 1024, 100L,
                    16L * 1024 * 1024);
        }


        private static void requirePositive(long aValue, String aName)
        {
            if (aValue <= 0)
            {
                throw new IllegalArgumentException(aName + " must be positive: " + aValue);
            }
        }
    }


    /** Counts the bytes pulled from the wire, so the gzip expansion ratio can be measured. */
    private static final class CountingInputStream extends FilterInputStream
    {

        private long count;

        CountingInputStream(InputStream aIn)
        {
            super(aIn);
        }


        long count()
        {
            return count;
        }


        @Override
        public int read() throws IOException
        {
            int b = in.read();
            if (b >= 0)
            {
                count++;
            }
            return b;
        }


        @Override
        public int read(byte[] aBuffer, int aOffset, int aLength) throws IOException
        {
            int n = in.read(aBuffer, aOffset, aLength);
            if (n > 0)
            {
                count += n;
            }
            return n;
        }


        @Override
        public long skip(long aCount) throws IOException
        {
            long n = in.skip(aCount);
            count += n;
            return n;
        }
    }

    /** Asks the forge which branch {@code HEAD} points at. */
    private String discoverDefaultBranch() throws IOException
    {
        URI advertisement = URI.create(repoUri + "/info/refs?service=git-upload-pack");
        HttpRequest request = HttpRequest.newBuilder(advertisement).timeout(Duration.ofSeconds(60))
                .GET().build();
        // Stream and cap: ofByteArray would materialise the whole body first, so a hostile or
        // misconfigured host could OOM the JVM before any size limit was consulted.
        HttpResponse<InputStream> response = send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        byte[] capped;
        try (InputStream body = response.body())
        {
            if (response.statusCode() != 200)
            {
                throw new IOException("ref discovery failed for " + advertisement + ": HTTP "
                        + response.statusCode());
            }
            capped = body.readNBytes(MAX_ADVERTISEMENT_BYTES);
        }
        // The same advertisement carries HEAD's commit id — the only provenance a plain archive
        // download can offer, and what SeedReport.sourceRef records.
        resolvedSha = GitRefDiscovery.headSha(capped).orElse(null);
        // Servers that omit the symref capability still respond to the literal ref "HEAD".
        return GitRefDiscovery.defaultBranch(capped).map(HttpArchivePickleSource::requireSafeRef)
                .orElse("HEAD");
    }


    /** Streams the archive, extracting only the wanted pickles. Returns how many were written. */
    private int download(URI aArchive, Path aTarget) throws IOException
    {
        HttpRequest request = HttpRequest.newBuilder(aArchive).timeout(Duration.ofMinutes(30)).GET()
                .build();
        HttpResponse<InputStream> response = send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200)
        {
            // Close the body: discarding it un-drained leaks the connection until GC.
            try (InputStream _ = response.body())
            {
                throw new IOException("archive download failed for " + aArchive + ": HTTP "
                        + response.statusCode());
            }
        }
        try (CountingInputStream body = new CountingInputStream(response.body());
                GZIPInputStream gz = new GZIPInputStream(body);
                TarArchiveInputStream tar = new TarArchiveInputStream(gz))
        {
            return extract(tar, aTarget, body::count);
        }
    }


    /**
     * Extracts the wanted entries.
     *
     * <p>
     * Ordering matters twice here. The containment check runs <b>before</b> the wanted-file filter,
     * so a traversal attempt is rejected loudly rather than being silently dropped by a filter that
     * happens to exclude it — a guard whose only witness is another guard cannot be shown to work.
     * And every size limit is consulted <b>before</b> bytes are written, not after, so a run cannot
     * overshoot its cap by a whole entry.
     * </p>
     *
     * @param aCompressedBytesRead
     *            how many bytes have been pulled off the wire so far, for the ratio guard.
     */
    private int extract(TarArchiveInputStream aTar, Path aTarget, LongSupplier aCompressedBytesRead)
        throws IOException
    {
        Path root = aTarget.toAbsolutePath().normalize();
        int count = 0;
        long total = 0;
        TarArchiveEntry entry;
        while ((entry = aTar.getNextEntry()) != null)
        {
            if (!entry.isFile() || entry.isSymbolicLink() || entry.isLink())
            {
                continue;
            }
            String relative = withinRepoPath(entry.getName());
            if (relative == null)
            {
                continue;
            }
            Path out = root.resolve(relative).normalize();
            if (!out.startsWith(root))
            {
                throw new IOException(
                        "archive entry escapes the target directory: " + entry.getName());
            }
            if (!isWanted(relative))
            {
                continue;
            }
            // Refuse on the declared size first: a header that admits it is over the cap must not
            // cost a single written byte. A header that lies is caught mid-copy instead.
            long declared = entry.getSize();
            if (declared > limits.maxEntryBytes())
            {
                throw new IOException("archive entry " + entry.getName() + " exceeds "
                        + limits.maxEntryBytes() + " bytes");
            }
            // Subtraction rather than addition: a PAX header may declare any size it likes, and
            // total + declared would wrap negative and sail past the cap it is meant to enforce.
            if (declared > 0 && declared > limits.maxTotalBytes() - total)
            {
                throw new IOException(
                        "archive extraction would exceed " + limits.maxTotalBytes() + " bytes");
            }
            Path parent = out.getParent();
            if (parent == null)
            {
                // Only reachable if the entry resolved to a filesystem root; nothing to extract to.
                throw new IOException("archive entry has no parent directory: " + entry.getName());
            }
            Files.createDirectories(parent);
            total += copyGuarded(aTar, out, entry.getName(), total, aCompressedBytesRead);
            count++;
        }
        return count;
    }


    /**
     * Copies one entry, re-checking every limit before each buffer is written and removing the
     * partial file if one is breached.
     *
     * @return the number of bytes written.
     */
    private long copyGuarded(TarArchiveInputStream aTar, Path aOut, String aName, long aTotalSoFar,
            LongSupplier aCompressedBytesRead)
        throws IOException
    {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long written = 0;
        try (OutputStream out = Files.newOutputStream(aOut))
        {
            int read;
            while ((read = aTar.read(buffer)) > 0)
            {
                long entryTotal = written + read;
                long grandTotal = aTotalSoFar + entryTotal;
                if (entryTotal > limits.maxEntryBytes())
                {
                    throw new IOException("archive entry " + aName + " exceeds "
                            + limits.maxEntryBytes() + " bytes");
                }
                if (grandTotal > limits.maxTotalBytes())
                {
                    throw new IOException(
                            "archive extraction would exceed " + limits.maxTotalBytes() + " bytes");
                }
                long compressed = Math.max(aCompressedBytesRead.getAsLong(), 1L);
                if (grandTotal > limits.compressionRatioGraceBytes()
                        && grandTotal > ratioAllowance(limits.maxCompressionRatio(), compressed))
                {
                    throw new IOException("archive expands more than "
                            + limits.maxCompressionRatio() + ":1 (" + grandTotal + " bytes from "
                            + compressed + " read) — refusing " + aName);
                }
                out.write(buffer, 0, read);
                written = entryTotal;
            }
        }
        catch (IOException e)
        {
            try
            {
                Files.deleteIfExists(aOut);
            }
            catch (IOException cleanup)
            {
                // The breach is the news; a failed cleanup must not replace it.
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        return written;
    }


    /**
     * How many uncompressed bytes the configured ratio allows for the compressed bytes read so far.
     *
     * <p>
     * Saturates instead of wrapping. A ratio limit near {@code Long.MAX_VALUE} — which is how a
     * caller switches the guard off — multiplied by the bytes read overflows to a <em>negative</em>
     * allowance for many inputs, and every entry would then compare as a bomb. Whether it does so
     * depends on the low bits of the byte count, which is precisely why this cannot be left to an
     * end-to-end test to catch and is pinned directly instead.
     * </p>
     *
     * @param aMaxRatio
     *            the configured maximum expansion ratio.
     * @param aCompressedBytesRead
     *            the compressed bytes consumed so far, at least 1.
     * @return the allowance, capped at {@link Long#MAX_VALUE}.
     */
    static long ratioAllowance(long aMaxRatio, long aCompressedBytesRead)
    {
        try
        {
            return Math.multiplyExact(aMaxRatio, aCompressedBytesRead);
        }
        catch (ArithmeticException _)
        {
            return Long.MAX_VALUE;
        }
    }


    /**
     * Maps an archive entry to its path relative to {@link #repoPath}, or {@code null} when it lies
     * outside. Source archives wrap everything in a single top-level directory
     * ({@code cdisc-rules-engine-main/}), which is stripped first.
     *
     * <p>
     * Traversal segments are deliberately <b>not</b> filtered here: an entry that claims to live
     * under {@link #repoPath} but climbs back out of it is a hostile archive, and
     * {@link #extract(TarArchiveInputStream, Path, LongSupplier)} rejects it outright. Dropping it
     * quietly at this point would leave the containment check with nothing to catch.
     * </p>
     */
    private @Nullable String withinRepoPath(String aEntryName)
    {
        String name = aEntryName.replace('\\', '/');
        if (name.startsWith("/"))
        {
            return null;
        }
        int slash = name.indexOf('/');
        if (slash < 0)
        {
            return null;
        }
        String withoutTopLevel = name.substring(slash + 1);
        String prefix = repoPath + "/";
        return withoutTopLevel.startsWith(prefix) ? withoutTopLevel.substring(prefix.length())
                : null;
    }


    /** Only the pickles the seeder reads: the two standards files and the CT packages. */
    private static boolean isWanted(String aRelativePath)
    {
        if (aRelativePath.contains("/"))
        {
            return false;
        }
        String lower = aRelativePath.toLowerCase(Locale.ROOT);
        if (!lower.endsWith(".pkl"))
        {
            return false;
        }
        return WANTED_STEMS.contains(lower) || lower.contains("ct-");
    }


    private <T> HttpResponse<T> send(HttpRequest aRequest, HttpResponse.BodyHandler<T> aHandler)
        throws IOException
    {
        try
        {
            return http.send(aRequest, aHandler);
        }
        catch (InterruptedException e)
        {
            Thread.currentThread().interrupt();
            throw new IOException("interrupted while fetching " + aRequest.uri(), e);
        }
    }


    /**
     * Validates a server-advertised branch name before it is interpolated into the archive URL. The
     * advertisement is untrusted input: {@code symref=HEAD:refs/heads/../../../x} would otherwise
     * produce {@code <repo>/archive/../../../x.tar.gz}.
     */
    private static String requireSafeRef(String aRef)
    {
        if (!aRef.matches("[A-Za-z0-9._/-]+") || aRef.contains(".."))
        {
            throw new IllegalStateException(
                    "refusing an unsafe branch name advertised by the server: " + aRef);
        }
        return aRef;
    }


    private static String requireUsable(String aValue, String aName)
    {
        if (aValue == null || aValue.isBlank())
        {
            throw new IllegalArgumentException(aName + " must not be blank");
        }
        return aValue;
    }


    private static String stripTrailingSlash(String aUrl)
    {
        String url = aUrl;
        while (url.endsWith("/"))
        {
            url = url.substring(0, url.length() - 1);
        }
        return url;
    }


    private static String normaliseRepoPath(String aPath)
    {
        String path = requireUsable(aPath, "repoPath").replace('\\', '/');
        if (path.startsWith("/") || path.contains(".."))
        {
            throw new IllegalArgumentException(
                    "repoPath must be a relative path without '..': " + aPath);
        }
        return stripTrailingSlash(path);
    }
}
