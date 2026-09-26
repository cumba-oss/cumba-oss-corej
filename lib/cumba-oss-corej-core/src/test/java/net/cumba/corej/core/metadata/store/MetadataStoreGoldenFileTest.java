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
     * Re-pinned 2026-09-26 with {@code FORMAT_VERSION} 2 &rarr; 3 (PLAN-define-ct-evaluation):
     * {@code ct/codelists.json} gained the codelist {@code name}, the three product entries gained
     * the T1-9 scalars and the CDASH slots, and {@code manifest.json} carries the new version; the
     * four fixed CT parts other than the headers are byte-identical to the format-2 pin. Taken from
     * a run in which the seeder byte-identity and the manifest legs were green, never from a red
     * run.<br>
     * Re-pinned 2026-09-08 with {@code FORMAT_VERSION} 1 &rarr; 2 (manifest.json and the model
     * product): {@code StoredVariable.examples} became a scalar string. Nothing else moved.
     * </p>
     */
    private static final String GOLDEN = """
            ct/codelists.bin cb574950e33f7dac2a168f8a2cd105e35704c38ac5de39b19c83b56f70afd24c
            ct/codelists.json 442e4c7d1eff80ef404cfe21c89fd9b060288f91c2a70d38d2c95302ba6c4c61
            ct/packages.json 8b0fa180c89e043f6479444720b1731d473ae2e566cc85c26a53a4a5c4f42a43
            ct/terms.bin 426cb169009a087ea1d9208324e53de6a9cde96421791dea446b28ea09ae35ff
            manifest.json 52fcb1183108104feb697491c78eed91f9aaf274b685c013ffc46c17ea46ee41
            products/models/sdtm/2-0.json 4d1f053c766a809899d2ac2616a4ee557f327e8adb4aec404fd906dbd428c568
            products/standards/adam/adamig-1-3.json d1802720a182f9670ded6186261486e3c81ae4b9074f583ff4d09ccf4022dbe8
            products/standards/sdtmig/3-4.json 7f9d3f9a8e7d02a75c9419507e1e93dd9c4153b4ae2cfb181fca5957693e1095
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
