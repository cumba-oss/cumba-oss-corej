package net.cumba.corej.core.metadata.store;

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
                    manifest.replace("\"formatVersion\" : 2", "\"formatVersion\" : 99")
                            .getBytes(StandardCharsets.UTF_8));
            return entries;
        });
        IOException failure = assertThrows(IOException.class, () -> MetadataStore.open(damaged));
        assertTrue(failure.getMessage().contains("format version 99"), failure.getMessage());
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
