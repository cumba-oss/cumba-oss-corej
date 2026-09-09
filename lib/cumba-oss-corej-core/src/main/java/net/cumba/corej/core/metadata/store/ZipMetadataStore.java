package net.cumba.corej.core.metadata.store;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The eager, in-memory {@link MetadataStore} implementation: one pass over the zip at
 * {@link #open(Path)} — manifest first, then hash-verify and parse every part — after which every
 * accessor is a map lookup. See {@link StoreFormat} for the layout.
 *
 * <p>
 * Opening is strict: an entry the manifest does not hash, a hashed entry that is missing, a hash
 * mismatch, a dangling id, a truncated binary part or a trailing byte all refuse to open. A store
 * is replaced atomically as a whole file (plan §5.2), so a structurally suspect one is corruption,
 * never a state to limp along with.
 * </p>
 */
final class ZipMetadataStore implements MetadataStore
{

    private final StoreManifest manifest;

    private final Map<String, StoredProduct> products;

    private final Map<String, StoredCtPackage> ctPackages;

    private ZipMetadataStore(StoreManifest aManifest, Map<String, StoredProduct> aProducts,
            Map<String, StoredCtPackage> aCtPackages)
    {
        manifest = aManifest;
        products = aProducts;
        ctPackages = aCtPackages;
    }


    /** Opens and fully loads the store at {@code aStore}; see {@link MetadataStore#open(Path)}. */
    static ZipMetadataStore open(Path aStore) throws IOException
    {
        Map<String, byte[]> entries = readEntries(aStore);
        byte[] manifestBytes = entries.remove(StoreFormat.ENTRY_MANIFEST);
        if (manifestBytes == null)
        {
            throw new IOException(aStore + " is not a metadata store: no "
                    + StoreFormat.ENTRY_MANIFEST + " entry");
        }
        StoreManifest manifest = StoreFormat.mapper().readValue(manifestBytes, StoreManifest.class);
        if (manifest.formatVersion() != StoreFormat.FORMAT_VERSION)
        {
            throw new IOException("unsupported metadata store format version "
                    + manifest.formatVersion() + " (this reader knows " + StoreFormat.FORMAT_VERSION
                    + "); re-seed the store");
        }
        verifyParts(manifest, entries);
        return new ZipMetadataStore(manifest, readProducts(entries), readCtPackages(entries));
    }


    @Override
    public Optional<StoredProduct> product(String aKey)
    {
        return aKey == null ? Optional.empty() : Optional.ofNullable(products.get(aKey));
    }


    @Override
    public Optional<StoredCtPackage> ctPackage(String aId)
    {
        if (presence(aId) == Presence.MALFORMED)
        {
            throw new IllegalArgumentException("malformed CT package id: " + aId);
        }
        return Optional.ofNullable(ctPackages.get(aId));
    }


    @Override
    public List<String> publishedCtPackages()
    {
        return manifest.publishedCtPackages();
    }


    @Override
    public Presence presence(String aCtPackageId)
    {
        if (aCtPackageId == null || !StoreFormat.CT_PACKAGE_ID.matcher(aCtPackageId).matches())
        {
            return Presence.MALFORMED;
        }
        return ctPackages.containsKey(aCtPackageId) ? Presence.PRESENT : Presence.ABSENT;
    }


    @Override
    public List<String> productCatalogue()
    {
        return manifest.productCatalogue();
    }


    @Override
    public StoreManifest manifest()
    {
        return manifest;
    }


    @Override
    public void close()
    {
        // Fully in memory; nothing to release. Kept for a lazier future format revision.
    }


    /** Reads every zip entry's uncompressed bytes, preserving zip order. */
    private static Map<String, byte[]> readEntries(Path aStore) throws IOException
    {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipFile zip = new ZipFile(aStore.toFile()))
        {
            Enumeration<? extends ZipEntry> names = zip.entries();
            while (names.hasMoreElements())
            {
                ZipEntry entry = names.nextElement();
                if (entry.isDirectory())
                {
                    continue;
                }
                try (InputStream in = zip.getInputStream(entry))
                {
                    if (entries.put(entry.getName(), in.readAllBytes()) != null)
                    {
                        throw new IOException("duplicate zip entry: " + entry.getName());
                    }
                }
            }
        }
        return entries;
    }


    /**
     * Verifies the manifest's part inventory against the zip's: every declared part present with a
     * matching sha256, no undeclared extras, and the four fixed CT parts all declared.
     */
    private static void verifyParts(StoreManifest aManifest, Map<String, byte[]> aEntries)
        throws IOException
    {
        Map<String, String> declared = aManifest.partHashes();
        for (String fixed : List.of(StoreFormat.ENTRY_CT_TERMS, StoreFormat.ENTRY_CT_CODELISTS_JSON,
                StoreFormat.ENTRY_CT_CODELISTS_BIN, StoreFormat.ENTRY_CT_PACKAGES))
        {
            if (!declared.containsKey(fixed))
            {
                throw new IOException("metadata store manifest does not declare " + fixed);
            }
        }
        for (Map.Entry<String, String> part : declared.entrySet())
        {
            byte[] content = aEntries.get(part.getKey());
            if (content == null)
            {
                throw new IOException("metadata store is missing part " + part.getKey());
            }
            String actual = StoreFormat.sha256(content);
            if (!actual.equals(part.getValue()))
            {
                throw new IOException("metadata store part " + part.getKey()
                        + " is corrupt: manifest sha256 " + part.getValue() + ", actual " + actual);
            }
        }
        for (String name : aEntries.keySet())
        {
            if (!declared.containsKey(name))
            {
                throw new IOException(
                        "metadata store holds undeclared entry " + name + "; refusing to open");
            }
        }
    }


    /** Parses every {@code products/<key>.json} entry, checking the embedded key. */
    private static Map<String, StoredProduct> readProducts(Map<String, byte[]> aEntries)
        throws IOException
    {
        Map<String, StoredProduct> products = new HashMap<>();
        for (Map.Entry<String, byte[]> entry : aEntries.entrySet())
        {
            String name = entry.getKey();
            if (!name.startsWith(StoreFormat.PRODUCT_PREFIX))
            {
                continue;
            }
            if (!name.endsWith(StoreFormat.PRODUCT_SUFFIX))
            {
                throw new IOException("metadata store holds a non-JSON product entry: " + name);
            }
            String key = name.substring(StoreFormat.PRODUCT_PREFIX.length(),
                    name.length() - StoreFormat.PRODUCT_SUFFIX.length());
            StoredProduct product = StoreFormat.mapper().readValue(entry.getValue(),
                    StoredProduct.class);
            if (!key.equals(product.key()))
            {
                throw new IOException(
                        "product entry " + name + " declares mismatching key " + product.key());
            }
            products.put(key, product);
        }
        return products;
    }


    /** Rebuilds every CT package from the four CT parts, materialising shared codelists once. */
    private static Map<String, StoredCtPackage> readCtPackages(Map<String, byte[]> aEntries)
        throws IOException
    {
        StoredTerm[] terms = readTerms(aEntries.get(StoreFormat.ENTRY_CT_TERMS));
        StoreFormat.CodelistsFile headers = StoreFormat.mapper().readValue(
                aEntries.get(StoreFormat.ENTRY_CT_CODELISTS_JSON), StoreFormat.CodelistsFile.class);
        StoredCodelist[] codelists = readCodelists(aEntries.get(StoreFormat.ENTRY_CT_CODELISTS_BIN),
                headers, terms);
        StoreFormat.PackagesFile packagesFile = StoreFormat.mapper().readValue(
                aEntries.get(StoreFormat.ENTRY_CT_PACKAGES), StoreFormat.PackagesFile.class);
        Map<String, StoredCtPackage> packages = new HashMap<>();
        for (Map.Entry<String, List<Integer>> pkg : packagesFile.packages().entrySet())
        {
            String id = pkg.getKey();
            if (!StoreFormat.CT_PACKAGE_ID.matcher(id).matches())
            {
                throw new IOException("metadata store holds a malformed CT package id: " + id);
            }
            List<StoredCodelist> members = new ArrayList<>(pkg.getValue().size());
            for (Integer ref : pkg.getValue())
            {
                if (ref == null || ref < 0 || ref >= codelists.length)
                {
                    throw new IOException("CT package " + id + " references codelist version " + ref
                            + " of " + codelists.length);
                }
                members.add(codelists[ref]);
            }
            packages.put(id, new StoredCtPackage(id, members));
        }
        return packages;
    }


    /** Parses {@code ct/terms.bin}: varint count, then five varint-delimited fields per term. */
    private static StoredTerm[] readTerms(byte[] aBytes) throws IOException
    {
        ByteArrayInputStream in = new ByteArrayInputStream(aBytes);
        int count = Varint.readCount(in, "term count");
        StoredTerm[] terms = new StoredTerm[count];
        for (int i = 0; i < count; i++)
        {
            terms[i] = new StoredTerm(Varint.readString(in), Varint.readString(in),
                    Varint.readString(in), Varint.readString(in), Varint.readStringList(in));
        }
        expectExhausted(in, StoreFormat.ENTRY_CT_TERMS);
        return terms;
    }


    /**
     * Parses {@code ct/codelists.bin} — per version a varint term count and a varint-delta id list
     * — and joins each id list to its header row and the shared term table.
     */
    private static StoredCodelist[] readCodelists(byte[] aBytes, StoreFormat.CodelistsFile aHeaders,
            StoredTerm[] aTerms)
        throws IOException
    {
        ByteArrayInputStream in = new ByteArrayInputStream(aBytes);
        int count = Varint.readCount(in, "codelist version count");
        if (count != aHeaders.codelists().size())
        {
            throw new IOException(StoreFormat.ENTRY_CT_CODELISTS_BIN + " holds " + count
                    + " versions but " + StoreFormat.ENTRY_CT_CODELISTS_JSON + " holds "
                    + aHeaders.codelists().size());
        }
        StoredCodelist[] codelists = new StoredCodelist[count];
        for (int i = 0; i < count; i++)
        {
            StoreFormat.CodelistHeader header = aHeaders.codelists().get(i);
            int termCount = Varint.readCount(in, "codelist term count");
            if (termCount != header.termCount())
            {
                throw new IOException("codelist version " + i + " holds " + termCount
                        + " terms but its header declares " + header.termCount());
            }
            List<StoredTerm> members = new ArrayList<>(termCount);
            int id = -1;
            for (int t = 0; t < termCount; t++)
            {
                long delta = Varint.readUnsigned(in);
                id = t == 0 ? (int) delta : id + (int) delta;
                if (id < 0 || id >= aTerms.length || (t > 0 && delta == 0))
                {
                    throw new IOException("codelist version " + i + " references term " + id
                            + " of " + aTerms.length);
                }
                members.add(aTerms[id]);
            }
            codelists[i] = new StoredCodelist(header.submissionValue(), header.conceptId(),
                    header.preferredTerm(), header.definition(), header.synonyms(),
                    header.extensible(), members);
        }
        expectExhausted(in, StoreFormat.ENTRY_CT_CODELISTS_BIN);
        return codelists;
    }


    private static void expectExhausted(ByteArrayInputStream aIn, String aEntry) throws IOException
    {
        if (aIn.read() >= 0)
        {
            throw new IOException(aEntry + " has trailing bytes; the store is corrupt");
        }
    }
}
