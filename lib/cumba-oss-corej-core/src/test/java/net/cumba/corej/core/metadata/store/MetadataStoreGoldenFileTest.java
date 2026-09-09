package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Enumeration;
import java.util.HexFormat;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Pins the on-disk layout, byte for byte: the exact zip entry list and the sha256 of every entry's
 * UNCOMPRESSED content (compressed bytes may legally vary with the JDK's deflater; content may
 * not). Any format change — field order, encoding, ordering rule, dedup identity, manifest shape —
 * lands here as a red test and must be a conscious decision, because a store on a user's disk
 * cannot be re-seeded retroactively.
 *
 * <p>
 * ⚠ If this test fails after an intentional format change: bump {@link StoreFormat#FORMAT_VERSION}
 * (a store of the old version must refuse to open, not misparse) and re-pin the hashes below.
 * </p>
 */
class MetadataStoreGoldenFileTest
{

    /**
     * {@code <entry name> <sha256 of uncompressed content>}, sorted by entry name.
     *
     * <p>
     * Re-pinned 2026-09-08 with {@code FORMAT_VERSION} 1 &rarr; 2 (manifest.json and the model
     * product): {@code StoredVariable.examples} became a scalar string. Nothing else moved.
     * </p>
     */
    private static final String GOLDEN = """
            ct/codelists.bin cb574950e33f7dac2a168f8a2cd105e35704c38ac5de39b19c83b56f70afd24c
            ct/codelists.json 17e8dea9636547a08849109b9fc97e71e9c6f7db3d7303a651b73babf6a0071c
            ct/packages.json 8b0fa180c89e043f6479444720b1731d473ae2e566cc85c26a53a4a5c4f42a43
            ct/terms.bin 426cb169009a087ea1d9208324e53de6a9cde96421791dea446b28ea09ae35ff
            manifest.json fd9c544978a05a66106a0f8ba8fdbb4cb1dd9bbcfc212a2c99d915d9a5373075
            products/models/sdtm/2-0.json 68856a63159aca51e70d5e888dbdff9f2abbe06a691aeb3525bfcfe23a438d8e
            products/standards/adam/adamig-1-3.json 3d8bcb5f4f2de174ae6496b1e5b20c664c6855b22aa059fef8551196765cd92b
            products/standards/sdtmig/3-4.json f588526eb79b4b8cf814400d0ce4aa8e86099432232fbc0e4ac8ee2a2d049b2c
            """;

    @TempDir
    Path tempDir;

    @Test
    void onDiskLayoutIsPinned() throws IOException
    {
        Path file = tempDir.resolve("golden.zip");
        MetadataStoreFixtures.populatedWriter().write(file);
        assertEquals(GOLDEN, describe(file),
                "The store's on-disk layout changed. If intentional, bump"
                        + " StoreFormat.FORMAT_VERSION and re-pin; a silent format drift would"
                        + " misread every store already on a user's disk.");
    }


    @Test
    void rewritingTheSameContentIsByteDeterministic() throws IOException
    {
        Path first = tempDir.resolve("first.zip");
        Path second = tempDir.resolve("second.zip");
        MetadataStoreFixtures.populatedWriter().write(first);
        MetadataStoreFixtures.populatedWriter().write(second);
        // Whole-FILE equality: same JDK, same input => identical bytes. This is the property the
        // P2 seeder byte-identity conformance test builds on (plan §5.1).
        assertEquals(HexFormat.of().formatHex(digest(Files.readAllBytes(first))),
                HexFormat.of().formatHex(digest(Files.readAllBytes(second))));
    }


    private static String describe(Path aStore) throws IOException
    {
        Map<String, String> hashes = new TreeMap<>();
        try (ZipFile zip = new ZipFile(aStore.toFile()))
        {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            while (entries.hasMoreElements())
            {
                ZipEntry entry = entries.nextElement();
                hashes.put(entry.getName(),
                        HexFormat.of().formatHex(digest(zip.getInputStream(entry).readAllBytes())));
            }
        }
        StringBuilder description = new StringBuilder();
        hashes.forEach(
                (name, hash) -> description.append(name).append(' ').append(hash).append('\n'));
        return description.toString();
    }


    private static byte[] digest(byte[] aBytes)
    {
        try
        {
            return MessageDigest.getInstance("SHA-256").digest(aBytes);
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException(e);
        }
    }
}
