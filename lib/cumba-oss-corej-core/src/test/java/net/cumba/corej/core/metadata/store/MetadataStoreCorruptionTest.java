package net.cumba.corej.core.metadata.store;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A damaged store must refuse to open with a message naming the damage — a store is replaced
 * atomically as one file, so anything structurally wrong is corruption, never a degraded mode. Also
 * pins the writer's own input validation.
 */
class MetadataStoreCorruptionTest
{

    @TempDir
    Path tempDir;

    private Path intact;

    @BeforeEach
    void writeIntactStore() throws IOException
    {
        intact = tempDir.resolve("intact.zip");
        MetadataStoreFixtures.populatedWriter().write(intact);
    }


    @Test
    void missingPartRefusesToOpen() throws IOException
    {
        Path damaged = rewrite("missing.zip", entries ->
        {
            entries.remove(StoreFormat.ENTRY_CT_TERMS);
            return entries;
        });
        IOException failure = assertThrows(IOException.class, () -> MetadataStore.open(damaged));
        assertTrue(failure.getMessage().contains("missing part ct/terms.bin"),
                failure.getMessage());
    }


    @Test
    void corruptPartRefusesToOpen() throws IOException
    {
        Path damaged = rewrite("corrupt.zip", entries ->
        {
            byte[] bytes = entries.get(StoreFormat.ENTRY_CT_CODELISTS_JSON).clone();
            bytes[bytes.length / 2] ^= 0x01;
            entries.put(StoreFormat.ENTRY_CT_CODELISTS_JSON, bytes);
            return entries;
        });
        IOException failure = assertThrows(IOException.class, () -> MetadataStore.open(damaged));
        assertTrue(failure.getMessage().contains("corrupt"), failure.getMessage());
        assertTrue(failure.getMessage().contains(StoreFormat.ENTRY_CT_CODELISTS_JSON),
                failure.getMessage());
    }


    @Test
    void undeclaredExtraEntryRefusesToOpen() throws IOException
    {
        Path damaged = rewrite("extra.zip", entries ->
        {
            entries.put("smuggled.txt", "boo".getBytes(StandardCharsets.UTF_8));
            return entries;
        });
        IOException failure = assertThrows(IOException.class, () -> MetadataStore.open(damaged));
        assertTrue(failure.getMessage().contains("undeclared"), failure.getMessage());
    }


    @Test
    void unknownFormatVersionRefusesToOpen() throws IOException
    {
        Path damaged = rewrite("future.zip", entries ->
        {
            String manifest = new String(entries.get(StoreFormat.ENTRY_MANIFEST),
                    StandardCharsets.UTF_8);
            entries.put(StoreFormat.ENTRY_MANIFEST,
                    manifest.replace("\"formatVersion\" : " + StoreFormat.FORMAT_VERSION,
                            "\"formatVersion\" : 99").getBytes(StandardCharsets.UTF_8));
            return entries;
        });
        StoreFormatException failure = assertThrows(StoreFormatException.class,
                () -> MetadataStore.open(damaged));
        assertEquals(99, failure.foundVersion());
        assertEquals(StoreFormat.FORMAT_VERSION, failure.knownVersion());
        assertTrue(failure.getMessage().contains("format 99"), failure.getMessage());
        // Engine L1 (PLAN-define-ct-evaluation review round 1): a NEWER store says so - the
        // remedy is an upgrade (or a deliberate replacement), which each SURFACE phrases.
        assertTrue(failure.writtenByNewerBuild());
        assertTrue(failure.getMessage().contains("written by a newer build"), failure.getMessage());
        assertTrue(!failure.getMessage().contains("upgrade this tool"),
                "neutral: no surface remedy in the engine (review round 2): "
                        + failure.getMessage());
        assertTrue(!failure.getMessage().contains("must be re-seeded"), failure.getMessage());
    }


    /**
     * The PREVIOUS format is refused, whatever the current one is (PLAN-store-cdash-codelist-ids
     * C4): a store the current writer produced, relabelled to {@code FORMAT_VERSION - 1}, fails
     * with the typed exception and the neutral re-seed wording, never as "newer". The reader checks
     * the version before it decompresses or binds anything else (D-16), so a relabel exercises
     * exactly the path a real previous-format layout takes — {@code format-v2.zip} stays the one
     * real old layout in the tree ({@code MetadataStoreFormatV2RefusalTest}).
     */
    @Test
    void thePreviousFormatVersionIsRefusedWithTheReSeedWording() throws IOException
    {
        Path previous = tempDir.resolve("previous.zip");
        MetadataStoreFixtures.populatedWriter().write(previous);
        CorruptStores.relabelFormatVersion(previous, StoreFormat.FORMAT_VERSION - 1);
        StoreFormatException failure = assertThrows(StoreFormatException.class,
                () -> MetadataStore.open(previous));
        assertEquals(StoreFormat.FORMAT_VERSION - 1, failure.foundVersion());
        assertEquals(StoreFormat.FORMAT_VERSION, failure.knownVersion());
        assertFalse(failure.writtenByNewerBuild(), "an OLDER store is not a newer build's");
        assertTrue(failure.getMessage().contains("format " + (StoreFormat.FORMAT_VERSION - 1)),
                failure.getMessage());
        assertTrue(failure.getMessage().contains("the store must be re-seeded"),
                failure.getMessage());
        assertFalse(failure.getMessage().contains("newer build"), failure.getMessage());
    }


    /**
     * Engine M1 (lazy product binding): a product entry whose embedded key disagrees with its entry
     * name is a corruption the part hash cannot see (the writer or a hand edit put it there). It
     * used to refuse at open; since products bind on first access it surfaces on
     * {@code product(key)} — loudly, as an {@link java.io.UncheckedIOException} naming the entry,
     * never as an empty answer.
     */
    @Test
    void aProductEntryWithAMismatchingKeyFailsLoudlyOnFirstAccess() throws IOException
    {
        Path damaged = rewriteRehashed("mismatch.zip", entries ->
        {
            String entry = StoreFormat.productEntry(MetadataStoreFixtures.IG_KEY);
            String json = new String(entries.get(entry), StandardCharsets.UTF_8).replace(
                    "\"key\":\"" + MetadataStoreFixtures.IG_KEY + "\"",
                    "\"key\":\"standards/sdtmig/9-9\"");
            entries.put(entry, json.getBytes(StandardCharsets.UTF_8));
            return entries;
        });
        // The manifest's hash for the edited entry is rewritten too, so only the KEY is wrong.
        try (MetadataStore store = MetadataStore.open(damaged))
        {
            java.io.UncheckedIOException failure = assertThrows(java.io.UncheckedIOException.class,
                    () -> store.product(MetadataStoreFixtures.IG_KEY));
            assertTrue(failure.getMessage().contains("mismatching key"), failure.getMessage());
            assertTrue(failure.getMessage().contains(MetadataStoreFixtures.IG_KEY),
                    failure.getMessage());
            assertTrue(store.product(MetadataStoreFixtures.MODEL_KEY).isPresent(),
                    "the other products still bind");
        }
    }


    @Test
    void notAStoreRefusesToOpen() throws IOException
    {
        Path damaged = tempDir.resolve("plain.zip");
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(damaged)))
        {
            zip.putNextEntry(new ZipEntry("readme.txt"));
            zip.write("not a store".getBytes(StandardCharsets.UTF_8));
            zip.closeEntry();
        }
        IOException failure = assertThrows(IOException.class, () -> MetadataStore.open(damaged));
        assertTrue(failure.getMessage().contains("not a metadata store"), failure.getMessage());
    }


    @Test
    void missingFileRefusesToOpen()
    {
        assertThrows(IOException.class, () -> MetadataStore.open(tempDir.resolve("nowhere.zip")));
    }


    @Test
    void writerRequiresExplicitPublishedEnumeration()
    {
        MetadataStoreWriter writer = new MetadataStoreWriter()
                .addCtPackage(MetadataStoreFixtures.sdtmPackage1());
        assertThrows(IllegalStateException.class,
                () -> writer.write(tempDir.resolve("unpublished.zip")));
    }


    @Test
    void writerRejectsBadInput()
    {
        assertThrows(IllegalArgumentException.class, () -> new MetadataStoreWriter()
                .addCtPackage(new StoredCtPackage("SDTMCT-2023-06-30", List.of())));
        assertThrows(IllegalArgumentException.class,
                () -> new MetadataStoreWriter().addCtPackage(MetadataStoreFixtures.sdtmPackage1())
                        .addCtPackage(MetadataStoreFixtures.sdtmPackage1()));
        assertThrows(IllegalArgumentException.class, () -> new MetadataStoreWriter()
                .addProduct(StoredProduct.builder().key("../escape").build()));
        assertThrows(IllegalArgumentException.class,
                () -> new MetadataStoreWriter().addProduct(MetadataStoreFixtures.igProduct())
                        .addProduct(MetadataStoreFixtures.igProduct()));
    }


    /**
     * As {@link #rewrite}, but re-hashes every part in the manifest afterwards, so the mutation is
     * a CONTENT fault the part hashes cannot see (the shape of a writer bug or a hand edit).
     */
    private Path rewriteRehashed(String aName, UnaryOperator<Map<String, byte[]>> aMutation)
        throws IOException
    {
        return rewrite(aName, entries ->
        {
            Map<String, byte[]> mutated = aMutation.apply(entries);
            String manifest = new String(mutated.get(StoreFormat.ENTRY_MANIFEST),
                    StandardCharsets.UTF_8);
            for (Map.Entry<String, byte[]> part : mutated.entrySet())
            {
                if (part.getKey().equals(StoreFormat.ENTRY_MANIFEST))
                {
                    continue;
                }
                manifest = manifest.replaceAll(
                        "\"" + java.util.regex.Pattern.quote(part.getKey())
                                + "\" : \"[0-9a-f]{64}\"",
                        "\"" + part.getKey() + "\" : \"" + StoreFormat.sha256(part.getValue())
                                + "\"");
            }
            mutated.put(StoreFormat.ENTRY_MANIFEST, manifest.getBytes(StandardCharsets.UTF_8));
            return mutated;
        });
    }


    /** Copies the intact store entry by entry, letting {@code aMutation} damage the entry map. */
    private Path rewrite(String aName, UnaryOperator<Map<String, byte[]>> aMutation)
        throws IOException
    {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(intact.toFile()))
        {
            Enumeration<? extends ZipEntry> names = zip.entries();
            while (names.hasMoreElements())
            {
                ZipEntry entry = names.nextElement();
                entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        entries = aMutation.apply(entries);
        Path target = tempDir.resolve(aName);
        try (OutputStream out = Files.newOutputStream(target);
                ZipOutputStream zip = new ZipOutputStream(out))
        {
            for (Map.Entry<String, byte[]> entry : entries.entrySet())
            {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return target;
    }
}
