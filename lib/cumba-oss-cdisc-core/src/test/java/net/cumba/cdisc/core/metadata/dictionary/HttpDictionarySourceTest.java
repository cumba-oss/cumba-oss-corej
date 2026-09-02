package net.cumba.cdisc.core.metadata.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import net.cumba.cdisc.core.metadata.dictionary.HttpDictionarySource.Download;
import net.cumba.cdisc.core.metadata.dictionary.HttpDictionarySource.DownloadLimits;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link HttpDictionarySource} against a real HTTP server on loopback, serving the shapes the three
 * credential-free authorities actually serve — including NCI EVS's defining quirk, the
 * single-page-app shell answered with HTTP 200 for any unmatched path, and precisionFDA's 308
 * redirect. Nothing here touches the public internet.
 */
class HttpDictionarySourceTest
{

    /** Roughly the real EVS shell: HTTP 200, {@code text/html}, ~2.9 KB of app scaffold. */
    private static final byte[] SPA_SHELL = ("<!doctype html>\n<html lang=\"en\">\n<head>"
            + "<title>NCI EVS</title></head>\n<body><div id=\"root\"></div>"
            + "<script src=\"/static/js/main.js\"></script></body></html>\n" + "<!-- "
            + "x".repeat(2700) + " -->\n").getBytes(StandardCharsets.UTF_8);

    private static final String MEDRT_TXT = "Cyclooxygenase Inhibitors [MoA]\tN0000000160\tMED-RT\n"
            + "1-Compartment [PK]\tN0000170948\tMED-RT\n";

    private static final String SEND_TERMINOLOGY = String.join("\n",
            "Code\tCodelist Code\tCodelist Extensible (Yes/No)\tCodelist Name"
                    + "\tCDISC Submission Value\tCDISC Synonym(s)\tCDISC Definition"
                    + "\tNCI Preferred Term",
            "C88025\t\tNo\tNeoplasm Type\tNEOPLASM\t\tThe codelist itself\tNeoplasm",
            "C3677\tC88025\tNo\tNeoplasm Type\tADENOMA, BENIGN\t\tA benign one\tAdenoma",
            "C3678\tC88025\tNo\tNeoplasm Type\tADENOMA, MALIGNANT\t\tA malignant one\tAdenoma", "")
            + "\n";

    private static final String DATE_STAMP = "SEND Terminology 2026-03-27\n";

    private static final String UNII_RECORDS = "UNII\tDISPLAY_NAME\tMF\tINCHIKEY\n"
            + "R16CO5Y76E\tASPIRIN\tC9H8O4\tBSYNRYMUTXBXSQ\n"
            + "H4L5F6D7S8\tSODIUM CHLORIDE\tClNa\t\n";

    private HttpServer server;

    private String baseUri;

    /** Path (decoded) &rarr; [content-type, body]; anything unmatched gets the SPA shell. */
    private final Map<String, byte[]> files = new LinkedHashMap<>();

    private final Map<String, String> contentTypes = new LinkedHashMap<>();

    private byte[] dtsZip = new byte[0];

    private byte[] uniiZip = new byte[0];

    @BeforeEach
    void startServer() throws IOException
    {
        dtsZip = zip(Map.of("MEDRT_2026/MEDRT_Release_Notes_20260706.txt",
                "2026.07.06\nRelease notes.\n", "MEDRT_2026/Core_MEDRT_20260706_DTS.xml",
                "<terminology><namespace><version>2026.07.06</version></namespace></terminology>",
                "MEDRT_2026/unrelated.bin", "not wanted"));
        uniiZip = zip(Map.of("UNII_Records_4Aug2026.txt", UNII_RECORDS));

        files.clear();
        contentTypes.clear();
        serve("/ftp1/MED-RT/MEDRT.txt", "text/plain", MEDRT_TXT.getBytes(StandardCharsets.UTF_8));
        serve("/ftp1/MED-RT/Core_MEDRT_DTS.zip", "application/zip", dtsZip);
        serve("/ftp1/CDISC/SEND/SEND Terminology.txt", "text/plain",
                SEND_TERMINOLOGY.getBytes(StandardCharsets.UTF_8));
        serve("/ftp1/CDISC/SEND/SEND Publication Date Stamp.txt", "text/plain",
                DATE_STAMP.getBytes(StandardCharsets.UTF_8));
        serve("/gateway/UNII_Data.zip", "application/zip", uniiZip);

        server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", this::dispatch);
        server.createContext("/uniisearch/", this::redirectToGateway);
        server.start();
        baseUri = "http://" + InetAddress.getLoopbackAddress().getHostAddress() + ":"
                + server.getAddress().getPort();
    }


    @AfterEach
    void stopServer()
    {
        server.stop(0);
    }


    private void serve(String aPath, String aContentType, byte[] aBody)
    {
        files.put(aPath, aBody);
        contentTypes.put(aPath, aContentType);
    }


    /** Serves the registered file — or, exactly like NCI EVS, the SPA shell with HTTP 200. */
    private void dispatch(HttpExchange aExchange) throws IOException
    {
        String path = aExchange.getRequestURI().getPath();
        byte[] body = files.get(path);
        String contentType = body == null ? "text/html" : contentTypes.get(path);
        byte[] payload = body == null ? SPA_SHELL : body;
        aExchange.getResponseHeaders().set("Content-Type", contentType);
        aExchange.sendResponseHeaders(200, payload.length);
        try (OutputStream out = aExchange.getResponseBody())
        {
            out.write(payload);
        }
    }


    /** precisionFDA's shape: the stable URL 308-redirects to an API-gateway URL. */
    private void redirectToGateway(HttpExchange aExchange) throws IOException
    {
        aExchange.getResponseHeaders().set("Location", baseUri + "/gateway/UNII_Data.zip");
        aExchange.sendResponseHeaders(308, -1);
        aExchange.close();
    }


    private static byte[] zip(Map<String, String> aEntries) throws IOException
    {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes))
        {
            for (Map.Entry<String, String> e : aEntries.entrySet())
            {
                out.putNextEntry(new ZipEntry(e.getKey()));
                out.write(e.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return bytes.toByteArray();
    }


    private static String sha256(byte[] aBytes)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(aBytes));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }

    // ------------------------------------------------------------------
    // The three real sources, end to end against the stub authorities
    // ------------------------------------------------------------------


    /** MED-RT: flat file plus the DTS zip, whose version sources are extracted beside it. */
    @Test
    void medRtDownloadYieldsAConvertibleLayout() throws IOException
    {
        try (HttpDictionarySource source = HttpDictionarySource.medRt(baseUri + "/ftp1/"))
        {
            Path dir = source.resolve();

            assertEquals(MEDRT_TXT, Files.readString(dir.resolve("MEDRT.txt")));
            assertTrue(Files.isRegularFile(dir.resolve("MEDRT_Release_Notes_20260706.txt")),
                    "the release notes are extracted, flattened, beside the flat file");
            assertTrue(Files.isRegularFile(dir.resolve("Core_MEDRT_20260706_DTS.xml")));
            assertFalse(Files.exists(dir.resolve("unrelated.bin")),
                    "unwanted zip entries are not extracted");
            assertEquals("2026.07.06", new MedRtConverter().versionOf(dir),
                    "the converter must find a real version in the downloaded layout");

            List<DictionarySource.Artefact> artefacts = source.artefacts();
            assertEquals(2, artefacts.size());
            assertEquals(sha256(MEDRT_TXT.getBytes(StandardCharsets.UTF_8)),
                    artefacts.get(0).sha256(), "the SHA-256 is of the raw bytes as fetched");
            assertEquals(sha256(dtsZip), artefacts.get(1).sha256(),
                    "for a zip, of the zip itself, not its entries");
            assertTrue(source.provenance().contains("/ftp1/MED-RT/MEDRT.txt"), source.provenance());
        }
    }


    /**
     * Task 4, end to end: the neoplasm acquisition fetches the publication date stamp too, so an
     * install from the fetched layout yields a real version instead of being skipped — the Phase 3
     * finding this phase must not repeat.
     */
    @Test
    void aNeoplasmInstallFromTheFetchedLayoutHasARealVersion(@TempDir Path store) throws IOException
    {
        DictionaryInstaller installer = new DictionaryInstaller(store);

        String version = installer.install(HttpDictionarySource.neoplasm(baseUri + "/ftp1/"),
                new NeoplasmConverter(), "notice", false);

        assertEquals("2026-03-27", version,
                "the version must come from the fetched date stamp, never be empty: "
                        + installer.getReport().getSkipped());
        assertTrue(Files.isRegularFile(
                store.resolve("neoplasm").resolve("2026-03-27").resolve("neoplasm.json")));
        String sources = Files.readString(store.resolve(DictionaryInstaller.SOURCES_FILE));
        assertTrue(sources.contains("## neoplasm"), sources);
        assertTrue(sources.contains(sha256(SEND_TERMINOLOGY.getBytes(StandardCharsets.UTF_8))),
                "SOURCES.md must carry the raw artefact's SHA-256: " + sources);
        assertTrue(sources.contains("SEND%20Publication%20Date%20Stamp.txt"),
                "and the date stamp's URL: " + sources);
    }


    /** precisionFDA's 308 redirect is followed, and the records file extracted from the zip. */
    @Test
    void uniiRedirectIsFollowedAndTheZipExtracted() throws IOException
    {
        try (HttpDictionarySource source = HttpDictionarySource
                .unii(baseUri + "/uniisearch/archive/latest/UNII_Data.zip"))
        {
            Path dir = source.resolve();

            assertEquals(UNII_RECORDS, Files.readString(dir.resolve("UNII_Records_4Aug2026.txt")));
            assertEquals("4Aug2026", new UniiConverter().versionOf(dir));
            assertEquals(1, source.artefacts().size());
            assertEquals(sha256(uniiZip), source.artefacts().get(0).sha256());
        }
    }

    // ------------------------------------------------------------------
    // The SPA shell is an error, never content
    // ------------------------------------------------------------------


    /** A 200 {@code text/html} response where a data file was expected means "does not exist". */
    @Test
    void theSpaShellIsRejectedAsMissingFileNotSavedAsContent() throws IOException
    {
        files.remove("/ftp1/CDISC/SEND/SEND Publication Date Stamp.txt");

        try (HttpDictionarySource source = HttpDictionarySource.neoplasm(baseUri + "/ftp1/"))
        {
            IOException thrown = assertThrows(IOException.class, source::resolve);
            assertTrue(thrown.getMessage().contains("does not exist"), thrown.getMessage());
            assertTrue(thrown.getMessage().contains("Date%20Stamp"), thrown.getMessage());
        }
    }


    /** The shell is caught by the byte sniff even when the server mislabels its content type. */
    @Test
    void aMislabeledHtmlShellIsStillRejected() throws IOException
    {
        serve("/ftp1/MED-RT/MEDRT.txt", "text/plain", SPA_SHELL);

        try (HttpDictionarySource source = HttpDictionarySource.medRt(baseUri + "/ftp1/"))
        {
            IOException thrown = assertThrows(IOException.class, source::resolve);
            assertTrue(thrown.getMessage().contains("HTML page"), thrown.getMessage());
        }
    }


    @Test
    void aNonOkStatusIsAnError() throws IOException
    {
        server.createContext("/gone", exchange ->
        {
            exchange.sendResponseHeaders(404, -1);
            exchange.close();
        });
        try (HttpDictionarySource source = new HttpDictionarySource("test",
                List.of(new Download(baseUri + "/gone", "gone.txt", false, List.of())),
                DownloadLimits.defaults()))
        {
            IOException thrown = assertThrows(IOException.class, source::resolve);
            assertTrue(thrown.getMessage().contains("HTTP 404"), thrown.getMessage());
        }
    }


    /** A "zip" without the PK signature is refused before any extraction is attempted. */
    @Test
    void aNonZipServedAsAZipIsRefused() throws IOException
    {
        serve("/ftp1/MED-RT/Core_MEDRT_DTS.zip", "application/zip",
                "junk that is not a zip".getBytes(StandardCharsets.UTF_8));

        try (HttpDictionarySource source = HttpDictionarySource.medRt(baseUri + "/ftp1/"))
        {
            IOException thrown = assertThrows(IOException.class, source::resolve);
            assertTrue(thrown.getMessage().contains("not a zip archive"), thrown.getMessage());
        }
    }


    /** A zip that carries none of the wanted entries is an error, not a silent success. */
    @Test
    void aZipWithoutTheWantedEntriesIsAnError() throws IOException
    {
        serve("/ftp1/MED-RT/Core_MEDRT_DTS.zip", "application/zip",
                zip(Map.of("something/else.txt", "irrelevant")));

        try (HttpDictionarySource source = HttpDictionarySource.medRt(baseUri + "/ftp1/"))
        {
            IOException thrown = assertThrows(IOException.class, source::resolve);
            assertTrue(thrown.getMessage().contains("no entries matching"), thrown.getMessage());
        }
    }

    // ------------------------------------------------------------------
    // Limits — exercised at kilobyte scale, exactly why they are injectable
    // ------------------------------------------------------------------


    @Test
    void anOversizedArtefactIsRefusedMidDownload() throws IOException
    {
        try (HttpDictionarySource source = new HttpDictionarySource("test", List.of(
                new Download(baseUri + "/ftp1/MED-RT/MEDRT.txt", "MEDRT.txt", false, List.of())),
                new DownloadLimits(16, 1024, 1024, 100, 1024)))
        {
            IOException thrown = assertThrows(IOException.class, source::resolve);
            assertTrue(thrown.getMessage().contains("exceeds 16 bytes"), thrown.getMessage());
        }
    }


    @Test
    void anOversizedZipEntryIsRefused() throws IOException
    {
        try (HttpDictionarySource source = new HttpDictionarySource("test",
                List.of(new Download(baseUri + "/gateway/UNII_Data.zip", "UNII_Data.zip", true,
                        List.of("UNII_Records_*.txt"))),
                new DownloadLimits(1024 * 1024, 16, 1024 * 1024, 100, 1024)))
        {
            IOException thrown = assertThrows(IOException.class, source::resolve);
            assertTrue(thrown.getMessage().contains("exceeds 16 bytes"), thrown.getMessage());
        }
    }


    @Test
    void aZipBombTripsTheCompressionRatioGuard() throws IOException
    {
        // 4 MB of zeroes compresses to ~4 KB — an expansion far beyond 2:1 once past the
        // 1 KB grace, tripping the guard while everything stays kilobyte-scale on the wire.
        byte[] zeroes = new byte[4 * 1024 * 1024];
        serve("/gateway/UNII_Data.zip", "application/zip",
                zip(Map.of("UNII_Records_bomb.txt", new String(zeroes, StandardCharsets.UTF_8))));

        try (HttpDictionarySource source = new HttpDictionarySource("test",
                List.of(new Download(baseUri + "/gateway/UNII_Data.zip", "UNII_Data.zip", true,
                        List.of("UNII_Records_*.txt"))),
                new DownloadLimits(64L * 1024 * 1024, 64L * 1024 * 1024, 64L * 1024 * 1024, 2,
                        1024)))
        {
            IOException thrown = assertThrows(IOException.class, source::resolve);
            assertTrue(thrown.getMessage().contains("expands more than 2:1"), thrown.getMessage());
        }
    }


    /** The overflow pinning shared with the pickle source: a huge ratio saturates, never wraps. */
    @Test
    void ratioAllowanceSaturatesInsteadOfWrapping()
    {
        assertEquals(Long.MAX_VALUE,
                HttpDictionarySource.ratioAllowance(Long.MAX_VALUE, 1_000_003L));
        assertEquals(200L, HttpDictionarySource.ratioAllowance(100L, 2L));
    }

    // ------------------------------------------------------------------
    // close() — only what this source created
    // ------------------------------------------------------------------


    @Test
    void closeRemovesTheDirectoryItCreatedAndNothingElse(@TempDir Path unrelated) throws IOException
    {
        Path bystander = Files.writeString(unrelated.resolve("bystander.txt"), "untouched");
        HttpDictionarySource source = HttpDictionarySource.neoplasm(baseUri + "/ftp1/");
        Path dir = source.resolve();
        assertTrue(Files.isDirectory(dir));

        source.close();

        assertFalse(Files.exists(dir), "the temp directory this source created is removed");
        assertTrue(Files.exists(bystander), "and nothing else is touched");
        source.close(); // a second close is a no-op, not an error
    }


    @Test
    void closeBeforeResolveDeletesNothing() throws IOException
    {
        HttpDictionarySource source = HttpDictionarySource.medRt(baseUri + "/ftp1/");
        source.close();
        assertNotNull(source); // nothing to assert beyond "no exception, no side effect"
    }

    // ------------------------------------------------------------------
    // Construction contracts
    // ------------------------------------------------------------------


    @Test
    void aDownloadFileNameMustBePlain()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new Download("https://x/y", "../escape.txt", false, List.of()));
    }


    @Test
    void anUnzippedDownloadMustNameItsWantedEntries()
    {
        assertThrows(IllegalArgumentException.class,
                () -> new Download("https://x/y.zip", "y.zip", true, List.of()));
    }


    @Test
    void limitsMustBePositive()
    {
        assertThrows(IllegalArgumentException.class, () -> new DownloadLimits(0, 1, 1, 1, 1));
    }
}
