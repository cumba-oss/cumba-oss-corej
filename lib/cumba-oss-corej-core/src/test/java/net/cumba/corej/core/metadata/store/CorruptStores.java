package net.cumba.corej.core.metadata.store;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipOutputStream;

/**
 * Damaged-store fixtures other packages' tests can reach (the store internals they need —
 * {@link StoreFormat}'s entry names and hashing — are package-private here on purpose).
 *
 * <ul>
 * <li>{@link #withMismatchingProductKey}: D-27 (PLAN-define-ct-evaluation review round 2, L1) — a
 * product entry whose embedded key mismatches its file name, hash-consistent, so the store OPENS
 * and the corruption surfaces on the first {@code product(key)} as an
 * {@code UncheckedIOException}.</li>
 * <li>{@link #relabelFormatVersion}: the manifest's {@code formatVersion} rewritten in place, the
 * trick every old-/newer-format test uses.</li>
 * </ul>
 */
public final class CorruptStores
{

    private CorruptStores()
    {
    }


    /** Copies {@code aSource} to {@code aTarget} with the product {@code aKey}'s key corrupted. */
    public static Path withMismatchingProductKey(Path aSource, Path aTarget, String aKey)
        throws IOException
    {
        return rewriteRehashed(aSource, aTarget, entries ->
        {
            String entry = StoreFormat.productEntry(aKey);
            byte[] bytes = entries.get(entry);
            if (bytes == null)
            {
                throw new IllegalArgumentException("no product entry " + entry + " in " + aSource);
            }
            String json = new String(bytes, StandardCharsets.UTF_8);
            String damaged = json.replace("\"key\":\"" + aKey + "\"",
                    "\"key\":\"standards/corrupt/9-9\"");
            if (damaged.equals(json))
            {
                throw new IllegalStateException("the product entry does not embed its key");
            }
            entries.put(entry, damaged.getBytes(StandardCharsets.UTF_8));
            return entries;
        });
    }


    /** Rewrites the manifest's {@code formatVersion} in place; fails if nothing changed. */
    public static void relabelFormatVersion(Path aStore, int aVersion) throws IOException
    {
        rewrite(aStore, aStore, entries ->
        {
            String manifest = new String(entries.get(StoreFormat.ENTRY_MANIFEST),
                    StandardCharsets.UTF_8);
            String relabelled = manifest.replaceFirst("\"formatVersion\" : \\d+",
                    "\"formatVersion\" : " + aVersion);
            if (relabelled.equals(manifest))
            {
                throw new IllegalStateException("the relabel must change the manifest");
            }
            entries.put(StoreFormat.ENTRY_MANIFEST, relabelled.getBytes(StandardCharsets.UTF_8));
            return entries;
        });
    }


    private static Path rewriteRehashed(Path aSource, Path aTarget,
            UnaryOperator<Map<String, byte[]>> aMutation)
        throws IOException
    {
        return rewrite(aSource, aTarget, entries ->
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
                        "\"" + Pattern.quote(part.getKey()) + "\" : \"[0-9a-f]{64}\"",
                        "\"" + part.getKey() + "\" : \"" + StoreFormat.sha256(part.getValue())
                                + "\"");
            }
            mutated.put(StoreFormat.ENTRY_MANIFEST, manifest.getBytes(StandardCharsets.UTF_8));
            return mutated;
        });
    }


    private static Path rewrite(Path aSource, Path aTarget,
            UnaryOperator<Map<String, byte[]>> aMutation)
        throws IOException
    {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(aSource.toFile()))
        {
            Enumeration<? extends ZipEntry> names = zip.entries();
            while (names.hasMoreElements())
            {
                ZipEntry entry = names.nextElement();
                entries.put(entry.getName(), zip.getInputStream(entry).readAllBytes());
            }
        }
        entries = aMutation.apply(entries);
        try (OutputStream out = Files.newOutputStream(aTarget);
                ZipOutputStream zip = new ZipOutputStream(out))
        {
            for (Map.Entry<String, byte[]> entry : entries.entrySet())
            {
                zip.putNextEntry(new ZipEntry(entry.getKey()));
                zip.write(entry.getValue());
                zip.closeEntry();
            }
        }
        return aTarget;
    }
}
