package net.cumba.cdisc.core.metadata.dictionary;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.stream.Collectors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import org.jspecify.annotations.Nullable;

/**
 * A {@link DictionarySource} that downloads a raw vendor distribution over plain HTTPS into a
 * temporary directory — the acquisition half of PLAN-dictionary-seeder Phase 7a, for the three
 * dictionaries that need no credentials: MED-RT, UNII and the SEND CT neoplasm codelist.
 *
 * <p>
 * Modelled on {@code HttpArchivePickleSource}, and holding to the same contracts: the network
 * happens in {@link #resolve()} rather than the constructor, bodies are streamed rather than
 * buffered whole, the extraction guards are an injectable {@link DownloadLimits} record so they can
 * be exercised by kilobyte-sized fixtures, and {@link #close()} deletes only the directory this
 * source itself created.
 * </p>
 *
 * <p>
 * <b>The NCI EVS trap this class exists to catch:</b> the EVS site is a single-page app whose
 * server answers <em>any</em> unmatched path with HTTP 200 and the ~2.9 KB HTML shell. A downloader
 * that trusted the status code would save that shell as {@code MEDRT.txt} and hand it to a
 * converter, which would "succeed" with an empty dictionary. An HTML response where a data file was
 * expected is therefore treated as "the file does not exist", never as content. precisionFDA has
 * the complementary quirk — {@code HEAD} returns 403 — so everything here is a {@code GET}, and its
 * 308 redirect to the API gateway is followed ({@link HttpClient.Redirect#NORMAL}).
 * </p>
 *
 * <p>
 * Each downloaded artefact's SHA-256 is computed on the bytes as they arrive and exposed through
 * {@link #artefacts()}, which is what {@link DictionaryInstaller} records in {@code SOURCES.md}.
 * </p>
 */
public final class HttpDictionarySource implements DictionarySource
{

    /** The NCI EVS file mirror all {@code /ftp1} paths hang off. */
    public static final String DEFAULT_EVS_BASE_URL = "https://evs.nci.nih.gov/ftp1/";

    /** The precisionFDA "latest" UNII archive; 308-redirects to an API-gateway URL. */
    public static final String DEFAULT_UNII_ARCHIVE_URL = "https://precision.fda.gov/uniisearch/archive/latest/UNII_Data.zip";

    /** Copy buffer; also the granularity at which the streaming limits are re-checked. */
    private static final int COPY_BUFFER_BYTES = 64 * 1024;

    /** How many leading bytes are sniffed for an HTML shell before anything is written. */
    private static final int SNIFF_BYTES = 512;

    private final String name;

    private final List<Download> downloads;

    private final DownloadLimits limits;

    private final HttpClient http;

    private final List<Artefact> artefacts = new ArrayList<>();

    private @Nullable Path created;

    /**
     * Creates a source over an explicit download list.
     *
     * @param aName
     *            the dictionary type this source feeds, used only in messages and the temp-dir
     *            name.
     * @param aDownloads
     *            the artefacts to fetch, in order.
     * @param aLimits
     *            the size and compression-ratio limits enforced while downloading and extracting.
     */
    public HttpDictionarySource(String aName, List<Download> aDownloads, DownloadLimits aLimits)
    {
        name = requireUsable(aName, "name");
        downloads = List.copyOf(aDownloads);
        if (downloads.isEmpty())
        {
            throw new IllegalArgumentException("at least one download is required");
        }
        limits = Objects.requireNonNull(aLimits, "limits");
        http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL).build();
    }


    /**
     * The MED-RT distribution from NCI EVS: the flat {@code MEDRT.txt} the converter reads, plus
     * the DTS archive — wanted only for {@code MEDRT_Release_Notes*.txt} and the
     * {@code Core_MEDRT_*_DTS.xml}, the two files {@code MedRtConverter.versionOf} can read a
     * release identifier from. Without one of them the install would be skipped as versionless.
     *
     * @param aEvsBaseUrl
     *            the EVS mirror base, normally {@link #DEFAULT_EVS_BASE_URL}.
     * @return the source.
     */
    public static HttpDictionarySource medRt(String aEvsBaseUrl)
    {
        String base = ensureTrailingSlash(aEvsBaseUrl);
        return new HttpDictionarySource("medrt",
                List.of(new Download(base + "MED-RT/MEDRT.txt", "MEDRT.txt", false, List.of()),
                        new Download(base + "MED-RT/Core_MEDRT_DTS.zip", "Core_MEDRT_DTS.zip", true,
                                List.of("MEDRT_Release_Notes*.txt", "Core_MEDRT_*_DTS.xml"))),
                DownloadLimits.defaults());
    }


    /**
     * The FDA UNII archive from precisionFDA: {@code UNII_Data.zip}, extracted for the
     * {@code UNII_Records_<date>.txt} the converter reads (the date token in that name is also the
     * version).
     *
     * @param aArchiveUrl
     *            the archive URL, normally {@link #DEFAULT_UNII_ARCHIVE_URL}.
     * @return the source.
     */
    public static HttpDictionarySource unii(String aArchiveUrl)
    {
        return new HttpDictionarySource("unii", List.of(
                new Download(aArchiveUrl, "UNII_Data.zip", true, List.of("UNII_Records_*.txt"))),
                DownloadLimits.defaults());
    }


    /**
     * The SEND controlled terminology from NCI EVS, for the neoplasm codelist: the terminology file
     * <b>and</b> the publication date stamp. The stamp is not optional — it is the only place
     * {@code NeoplasmConverter.versionOf} can read a version from, and without it the installer
     * skips neoplasm entirely (the Phase 3 finding this factory must not repeat). Both file names
     * carry spaces, URL-encoded here.
     *
     * @param aEvsBaseUrl
     *            the EVS mirror base, normally {@link #DEFAULT_EVS_BASE_URL}.
     * @return the source.
     */
    public static HttpDictionarySource neoplasm(String aEvsBaseUrl)
    {
        String base = ensureTrailingSlash(aEvsBaseUrl);
        return new HttpDictionarySource("neoplasm",
                List.of(new Download(base + "CDISC/SEND/SEND%20Terminology.txt",
                        "SEND Terminology.txt", false, List.of()),
                        new Download(base + "CDISC/SEND/SEND%20Publication%20Date%20Stamp.txt",
                                "SEND Publication Date Stamp.txt", false, List.of())),
                DownloadLimits.defaults());
    }


    @Override
    public Path resolve() throws IOException
    {
        Path target = Files.createTempDirectory("corej-dictionary-" + name + "-");
        created = target;
        for (Download download : downloads)
        {
            fetch(download, target);
        }
        return target;
    }


    @Override
    public String provenance()
    {
        return downloads.stream().map(Download::url).collect(Collectors.joining(", "));
    }


    @Override
    public List<Artefact> artefacts()
    {
        return List.copyOf(artefacts);
    }


    @Override
    public void close() throws IOException
    {
        // The JDK HttpClient owns a selector thread and an executor; leaving it open would leak
        // both on every install run.
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
     * One artefact to fetch.
     *
     * @param url
     *            where to {@code GET} it from (never {@code HEAD} — precisionFDA answers 403).
     * @param fileName
     *            the plain file name to save it under in the resolved directory.
     * @param unzip
     *            whether the artefact is a zip whose wanted entries must be extracted beside it.
     * @param wantedEntryGlobs
     *            for a zip, the file-name globs selecting which entries to extract (matched against
     *            the entry's base name; everything else is skipped).
     */
    public record Download(String url, String fileName, boolean unzip,
            List<String> wantedEntryGlobs)
    {

        /** Validates the URL and confines the file name to a plain name. */
        public Download
        {
            requireUsable(url, "url");
            requireUsable(fileName, "fileName");
            if (fileName.contains("/") || fileName.contains("\\"))
            {
                throw new IllegalArgumentException(
                        "fileName must be a plain file name: " + fileName);
            }
            wantedEntryGlobs = List.copyOf(wantedEntryGlobs);
            if (unzip && wantedEntryGlobs.isEmpty())
            {
                throw new IllegalArgumentException(
                        "an unzipped download must name its wanted entries: " + fileName);
            }
        }
    }


    /**
     * The size and compression limits enforced while downloading and extracting.
     *
     * <p>
     * Injectable rather than hard-wired so the boundaries can be exercised by a test with a
     * kilobyte-sized fixture instead of a gigabyte-sized one — an untestable guard is a guard
     * nobody can prove still works.
     * </p>
     *
     * @param maxArtefactBytes
     *            the largest single downloaded artefact, in bytes.
     * @param maxEntryBytes
     *            the largest single extracted zip entry, in bytes.
     * @param maxTotalBytes
     *            the largest total extraction per zip, in bytes.
     * @param maxCompressionRatio
     *            the largest tolerated ratio of bytes written to compressed bytes read — a zip-bomb
     *            guard, since a bomb is small on the wire and enormous on disk.
     * @param compressionRatioGraceBytes
     *            how much may be written before the ratio is enforced at all; without it a
     *            legitimate small archive, whose first kilobytes inflate from almost nothing, would
     *            trip the ratio on statistical noise.
     */
    public record DownloadLimits(long maxArtefactBytes, long maxEntryBytes, long maxTotalBytes,
            long maxCompressionRatio, long compressionRatioGraceBytes)
    {

        /** Validates that every limit is positive. */
        public DownloadLimits
        {
            requirePositive(maxArtefactBytes, "maxArtefactBytes");
            requirePositive(maxEntryBytes, "maxEntryBytes");
            requirePositive(maxTotalBytes, "maxTotalBytes");
            requirePositive(maxCompressionRatio, "maxCompressionRatio");
            requirePositive(compressionRatioGraceBytes, "compressionRatioGraceBytes");
        }


        /**
         * The production limits: 256 MiB per artefact and per entry, 1 GiB per extraction, and a
         * 100:1 compression ratio once 16 MiB have been written.
         *
         * <p>
         * The largest real artefact is the ~14 MB UNII zip expanding to a ~50 MB records file —
         * under 4:1 — so these leave more than an order of magnitude of headroom while staying far
         * below what a bomb needs to be dangerous.
         * </p>
         *
         * @return the default limits.
         */
        public static DownloadLimits defaults()
        {
            return new DownloadLimits(256L * 1024 * 1024, 256L * 1024 * 1024, 1024L * 1024 * 1024,
                    100L, 16L * 1024 * 1024);
        }


        private static void requirePositive(long aValue, String aName)
        {
            if (aValue <= 0)
            {
                throw new IllegalArgumentException(aName + " must be positive: " + aValue);
            }
        }
    }


    /** Counts the bytes pulled from a stream, so the zip expansion ratio can be measured. */
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

    /** Downloads one artefact, digesting it on the way past, then extracts it if it is a zip. */
    private void fetch(Download aDownload, Path aTarget) throws IOException
    {
        HttpRequest request = HttpRequest.newBuilder(URI.create(aDownload.url()))
                .timeout(Duration.ofMinutes(30)).GET().build();
        HttpResponse<InputStream> response = send(request,
                HttpResponse.BodyHandlers.ofInputStream());
        if (response.statusCode() != 200)
        {
            // Close the body: discarding it un-drained leaks the connection until GC.
            try (InputStream _ = response.body())
            {
                throw new IOException("download failed for " + aDownload.url() + ": HTTP "
                        + response.statusCode());
            }
        }
        Path file = aTarget.resolve(aDownload.fileName());
        MessageDigest digest = sha256();
        try (InputStream body = rejectHtmlShell(response, aDownload.url());
                OutputStream out = Files.newOutputStream(file))
        {
            byte[] buffer = new byte[COPY_BUFFER_BYTES];
            long total = 0;
            int read;
            while ((read = body.read(buffer)) > 0)
            {
                total += read;
                if (total > limits.maxArtefactBytes())
                {
                    throw new IOException(aDownload.fileName() + " from " + aDownload.url()
                            + " exceeds " + limits.maxArtefactBytes() + " bytes");
                }
                digest.update(buffer, 0, read);
                out.write(buffer, 0, read);
            }
        }
        catch (IOException e)
        {
            try
            {
                Files.deleteIfExists(file);
            }
            catch (IOException cleanup)
            {
                // The download failure is the news; a failed cleanup must not replace it.
                e.addSuppressed(cleanup);
            }
            throw e;
        }
        artefacts.add(new Artefact(aDownload.fileName(), aDownload.url(),
                HexFormat.of().formatHex(digest.digest())));
        if (aDownload.unzip())
        {
            extractWanted(file, aTarget, aDownload);
        }
    }


    /**
     * Wraps the response body so that an HTML page served where a data file was expected is
     * rejected before a single byte is written.
     *
     * <p>
     * The check is two-armed on purpose: the {@code Content-Type} header catches the EVS SPA shell
     * (served as {@code text/html} with HTTP 200 for any unmatched path), and the leading-bytes
     * sniff catches a server that mislabels an error page. None of the real artefacts — TSV text, a
     * zip — can legitimately begin with an HTML document tag.
     * </p>
     */
    private static InputStream rejectHtmlShell(HttpResponse<InputStream> aResponse, String aUrl)
        throws IOException
    {
        String contentType = aResponse.headers().firstValue("Content-Type").orElse("")
                .toLowerCase(Locale.ROOT);
        PushbackInputStream in = new PushbackInputStream(aResponse.body(), SNIFF_BYTES);
        byte[] head;
        try
        {
            head = in.readNBytes(SNIFF_BYTES);
        }
        catch (IOException e)
        {
            in.close();
            throw e;
        }
        if (contentType.startsWith("text/html") || looksLikeHtml(head))
        {
            in.close();
            throw new IOException("the server answered with an HTML page instead of the file — on "
                    + "NCI EVS the single-page-app shell is served with HTTP 200 for any unmatched "
                    + "path, so the file most likely does not exist: " + aUrl);
        }
        in.unread(head);
        return in;
    }


    /** Whether the leading bytes, past whitespace and a UTF-8 BOM, open an HTML document. */
    private static boolean looksLikeHtml(byte[] aHead)
    {
        String text = new String(aHead, StandardCharsets.UTF_8).stripLeading();
        if (text.startsWith("\uFEFF"))
        {
            text = text.substring(1).stripLeading();
        }
        String lower = text.toLowerCase(Locale.ROOT);
        return lower.startsWith("<!doctype html") || lower.startsWith("<html");
    }


    /** Extracts the wanted entries of a downloaded zip into the target directory. */
    private void extractWanted(Path aZip, Path aTarget, Download aDownload) throws IOException
    {
        requireZipMagic(aZip, aDownload.url());
        List<PathMatcher> wanted = aDownload.wantedEntryGlobs().stream()
                .map(g -> FileSystems.getDefault().getPathMatcher("glob:" + g)).toList();
        int extracted = 0;
        long total = 0;
        try (CountingInputStream counted = new CountingInputStream(Files.newInputStream(aZip));
                ZipInputStream zip = new ZipInputStream(counted))
        {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null)
            {
                if (entry.isDirectory())
                {
                    continue;
                }
                String base = safeBaseNameOf(entry.getName());
                if (base == null || wanted.stream().noneMatch(m -> m.matches(Path.of(base))))
                {
                    continue;
                }
                // Refuse on the declared size first: a header that admits it is over the cap must
                // not cost a single written byte. A header that lies is caught mid-copy instead.
                long declared = entry.getSize();
                if (declared > limits.maxEntryBytes())
                {
                    throw new IOException("zip entry " + entry.getName() + " exceeds "
                            + limits.maxEntryBytes() + " bytes");
                }
                // Subtraction rather than addition: a hostile header may declare any size, and
                // total + declared could wrap negative and sail past the cap.
                if (declared > 0 && declared > limits.maxTotalBytes() - total)
                {
                    throw new IOException(
                            "zip extraction would exceed " + limits.maxTotalBytes() + " bytes");
                }
                total += copyGuarded(zip, aTarget.resolve(base), entry.getName(), total,
                        counted::count);
                extracted++;
            }
        }
        if (extracted == 0)
        {
            throw new IOException("no entries matching " + aDownload.wantedEntryGlobs()
                    + " found in " + aDownload.fileName() + " from " + aDownload.url());
        }
    }


    /**
     * Refuses a downloaded "zip" that does not carry the zip signature — the belt to the HTML
     * sniff's braces, for a server that serves an error page as {@code application/zip}.
     */
    private static void requireZipMagic(Path aZip, String aUrl) throws IOException
    {
        byte[] head;
        try (InputStream in = Files.newInputStream(aZip))
        {
            head = in.readNBytes(2);
        }
        if (head.length < 2 || head[0] != 'P' || head[1] != 'K')
        {
            throw new IOException(
                    "the downloaded file is not a zip archive (no PK signature): " + aUrl);
        }
    }


    /**
     * Copies one zip entry, re-checking every limit before each buffer is written and removing the
     * partial file if one is breached.
     *
     * @return the number of bytes written.
     */
    private long copyGuarded(ZipInputStream aZip, Path aOut, String aName, long aTotalSoFar,
            LongSupplier aCompressedBytesRead)
        throws IOException
    {
        byte[] buffer = new byte[COPY_BUFFER_BYTES];
        long written = 0;
        try (OutputStream out = Files.newOutputStream(aOut))
        {
            int read;
            while ((read = aZip.read(buffer)) > 0)
            {
                long entryTotal = written + read;
                long grandTotal = aTotalSoFar + entryTotal;
                if (entryTotal > limits.maxEntryBytes())
                {
                    throw new IOException(
                            "zip entry " + aName + " exceeds " + limits.maxEntryBytes() + " bytes");
                }
                if (grandTotal > limits.maxTotalBytes())
                {
                    throw new IOException(
                            "zip extraction would exceed " + limits.maxTotalBytes() + " bytes");
                }
                long compressed = Math.max(aCompressedBytesRead.getAsLong(), 1L);
                if (grandTotal > limits.compressionRatioGraceBytes()
                        && grandTotal > ratioAllowance(limits.maxCompressionRatio(), compressed))
                {
                    throw new IOException("zip expands more than " + limits.maxCompressionRatio()
                            + ":1 (" + grandTotal + " bytes from " + compressed
                            + " read) — refusing " + aName);
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
     * Saturates instead of wrapping, so a caller that switches the guard off with a huge ratio does
     * not turn every entry into a "bomb" through overflow — the same pinning as
     * {@code HttpArchivePickleSource.ratioAllowance}.
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
     * The base name of a zip entry, or {@code null} when it is unusable. Entries are flattened to
     * their base name — the converters glob a flat directory — which also removes any traversal
     * path a hostile archive could carry; the whitelist keeps a hostile name from reaching
     * {@link Path#of} at all.
     */
    private static @Nullable String safeBaseNameOf(String aEntryName)
    {
        String normalised = aEntryName.replace('\\', '/');
        String base = normalised.substring(normalised.lastIndexOf('/') + 1);
        return base.matches("[A-Za-z0-9][A-Za-z0-9._ -]*") ? base : null;
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


    private static MessageDigest sha256()
    {
        try
        {
            return MessageDigest.getInstance("SHA-256");
        }
        catch (NoSuchAlgorithmException e)
        {
            // Every conformant JRE ships SHA-256; a JRE without it cannot run this application.
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }


    private static String requireUsable(String aValue, String aName)
    {
        if (aValue == null || aValue.isBlank())
        {
            throw new IllegalArgumentException(aName + " must not be blank");
        }
        return aValue;
    }


    private static String ensureTrailingSlash(String aUrl)
    {
        String url = requireUsable(aUrl, "baseUrl");
        return url.endsWith("/") ? url : url + "/";
    }
}
