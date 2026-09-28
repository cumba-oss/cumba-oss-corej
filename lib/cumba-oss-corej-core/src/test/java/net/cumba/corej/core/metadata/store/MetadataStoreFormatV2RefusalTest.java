package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The old-format disposition (PLAN-define-ct-evaluation T1-10 a, D-16): a format-2 store is refused
 * WHOLE, with a typed {@link StoreFormatException}, before any part is decompressed or bound — so a
 * store lacking the fields the formats after 2 added (the codelist {@code name} among them) can
 * never be read with them silently empty.
 *
 * <p>
 * {@code /metadata/store/format-v2.zip} is the only real format-2 byte layout in the tree: it was
 * written ONCE by the unmodified format-2 writer ({@code MetadataStoreFixtures.populatedWriter()}
 * at {@code cumba-corej} 1f8d276) and committed; its sha256 is pinned here so a regenerated fixture
 * cannot quietly become a current-format store that this test then "refuses" for the wrong reason.
 * The PREVIOUS format's refusal, whatever it is, is {@code MetadataStoreCorruptionTest}'s relabel
 * test.
 * </p>
 */
class MetadataStoreFormatV2RefusalTest
{

    static final String FIXTURE = "/metadata/store/format-v2.zip";

    static final String FIXTURE_SHA256 = "09d8b56527cbdc9db7091f8161367e463c3cd9466720b6bf5af9994e9f14b0c8";

    @TempDir
    Path tempDir;

    /** NS2: the fixture is refused with the typed exception, found 2 / known current, "re-seed". */
    @Test
    void aFormat2StoreIsRefusedWholeWithATypedException() throws IOException
    {
        Path store = fixture();
        StoreFormatException refused = org.junit.jupiter.api.Assertions
                .assertThrows(StoreFormatException.class, () -> MetadataStore.open(store));
        assertEquals(2, refused.foundVersion());
        assertEquals(StoreFormat.FORMAT_VERSION, refused.knownVersion());
        assertTrue(refused.getMessage().contains(store.toString()), refused.getMessage());
        assertTrue(refused.getMessage().contains("format 2"), refused.getMessage());
        assertTrue(refused.getMessage().contains("re-seed"), refused.getMessage());
    }


    /**
     * NS2b (i) — version first: a manifest the current {@code StoreManifest} cannot bind (an extra
     * top-level key) still surfaces as "format 2", never as a Jackson binding error.
     */
    @Test
    void theVersionIsReadBeforeTheManifestIsBound() throws IOException
    {
        Path store = rewrite("unbindable.zip", entries ->
        {
            String manifest = new String(entries.get(StoreFormat.ENTRY_MANIFEST),
                    StandardCharsets.UTF_8);
            entries.put(StoreFormat.ENTRY_MANIFEST,
                    manifest.replaceFirst("\\{", "{\n  \"futureShape\" : { \"x\" : 1 },")
                            .getBytes(StandardCharsets.UTF_8));
            return entries;
        });
        StoreFormatException refused = org.junit.jupiter.api.Assertions
                .assertThrows(StoreFormatException.class, () -> MetadataStore.open(store));
        assertEquals(2, refused.foundVersion());
    }


    /**
     * NS2b (ii) — version first: a corrupted PART in a format-2 store surfaces as "format 2", never
     * as the part-hash mismatch, because no part is verified before the version is known.
     */
    @Test
    void theVersionIsReadBeforeAnyPartIsVerified() throws IOException
    {
        Path store = rewrite("corrupt-part.zip", entries ->
        {
            entries.put(StoreFormat.ENTRY_CT_TERMS, new byte[]
            {
                    0, 1, 2, 3
            });
            return entries;
        });
        StoreFormatException refused = org.junit.jupiter.api.Assertions
                .assertThrows(StoreFormatException.class, () -> MetadataStore.open(store));
        assertEquals(2, refused.foundVersion());
    }


    /** The writer's side of D-16: {@code manifest.json} is the FIRST zip entry it writes. */
    @Test
    void theWriterPutsTheManifestFirst() throws IOException
    {
        Path store = tempDir.resolve("current.zip");
        MetadataStoreFixtures.populatedWriter().write(store);
        try (ZipFile zip = new ZipFile(store.toFile()))
        {
            assertEquals(StoreFormat.ENTRY_MANIFEST, zip.entries().nextElement().getName(),
                    "the version must sit at the beginning of the file (owner, 2026-09-25)");
        }
    }


    /** A store the CURRENT writer produces opens — the refusal is about the version, not zips. */
    @Test
    void aCurrentFormatStoreStillOpens() throws IOException
    {
        Path store = tempDir.resolve("current-opens.zip");
        MetadataStoreFixtures.populatedWriter().write(store);
        try (MetadataStore opened = MetadataStore.open(store))
        {
            assertEquals(StoreFormat.FORMAT_VERSION, opened.manifest().formatVersion());
        }
    }


    /** The committed fixture, copied to a temp file and checked against its pinned sha256. */
    private Path fixture() throws IOException
    {
        Path out = tempDir.resolve("format-v2.zip");
        try (InputStream in = MetadataStoreFormatV2RefusalTest.class.getResourceAsStream(FIXTURE))
        {
            assertNotNull(in, FIXTURE + " is not on the test classpath");
            Files.copy(in, out);
        }
        assertEquals(FIXTURE_SHA256, sha256(Files.readAllBytes(out)),
                FIXTURE + " is not the committed format-2 fixture; regenerating it with a later"
                        + " writer would make this test refuse a store for the wrong reason");
        return out;
    }


    private Path rewrite(String aName, UnaryOperator<Map<String, byte[]>> aMutation)
        throws IOException
    {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(fixture().toFile()))
        {
            Enumeration<? extends ZipEntry> names = zip.entries();
            while (names.hasMoreElements())
            {
                ZipEntry entry = names.nextElement();
                entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        Map<String, byte[]> mutated = aMutation.apply(entries);
        Path out = tempDir.resolve(aName);
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(out)))
        {
            for (Map.Entry<String, byte[]> entry : mutated.entrySet())
            {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return out;
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
}
