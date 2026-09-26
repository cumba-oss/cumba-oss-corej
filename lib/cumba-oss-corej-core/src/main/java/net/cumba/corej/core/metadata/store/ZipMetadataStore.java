package net.cumba.corej.core.metadata.store;

import com.fasterxml.jackson.databind.JsonNode;
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
import java.util.concurrent.ConcurrentHashMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * The in-memory {@link MetadataStore} implementation: one pass over the zip at {@link #open(Path)}
 * — manifest first, then hash-verify every part and parse the CT parts — after which every accessor
 * is a map lookup. See {@link StoreFormat} for the layout.
 *
 * <p>
 * ⭐ Products are bound LAZILY (PLAN-define-ct-evaluation review round 1, engine M1): their JSON
 * entries are hash-verified at open like every other part, but kept as bytes and bound to
 * {@link StoredProduct} on the first {@link #product(String)} of that key. Measured on a real seed
 * (206 CT packages, 34 IGs + 14 models), the product entries are ~12 MB of JSON of which the CDASH
 * levels format 3 added are ~5.6 MB, and a define run or a CT-picker build opens the store several
 * times and reads at most one or two products — binding all 48 up front cost every open ~20 ms and
 * ~8 MB of retained heap it never used. The catalogue and the product KEY set are still known at
 * open; only the binding moves. A product entry whose embedded key disagrees with its name is
 * therefore reported on first access rather than at open — as an {@link UncheckedIOException}
 * naming the entry, never as an empty answer.
 * </p>
 *
 * <p>
 * Opening is strict: an entry the manifest does not hash, a hashed entry that is missing, a hash
 * mismatch, a dangling id, a truncated binary part or a trailing byte all refuse to open. A store
 * is replaced atomically as a whole file (plan §5.2), so a structurally suspect one is corruption,
 * never a state to limp along with.
 * </p>
 *
 * <p>
 * ⭐ The format VERSION is read first, and alone (PLAN-define-ct-evaluation D-16, owner 2026-09-25):
 * {@code manifest.json} is opened by name, its {@code formatVersion} is read as a bare tree, and a
 * store of any other version is refused with {@link StoreFormatException} before any other entry is
 * decompressed, before the manifest is bound to {@link StoreManifest} and before a single part hash
 * is checked. So an old store — or a future one whose manifest this {@code StoreManifest} cannot
 * even bind — always surfaces as "format N", never as a binding error or a hash mismatch, and can
 * never be read with the fields it lacks silently empty.
 * </p>
 */
final class ZipMetadataStore implements MetadataStore
{

    private final StoreManifest manifest;

    /** Verified product entry bytes by key; an entry is removed once bound. */
    private final Map<String, byte[]> productBytes;

    /** Products bound so far; {@link Optional#empty()} for a key the store does not hold. */
    private final Map<String, Optional<StoredProduct>> products = new ConcurrentHashMap<>();

    private final Map<String, StoredCtPackage> ctPackages;

    private ZipMetadataStore(StoreManifest aManifest, Map<String, byte[]> aProductBytes,
            Map<String, StoredCtPackage> aCtPackages)
    {
        manifest = aManifest;
        productBytes = aProductBytes;
        ctPackages = aCtPackages;
    }


    /** Opens and fully loads the store at {@code aStore}; see {@link MetadataStore#open(Path)}. */
    static ZipMetadataStore open(Path aStore) throws IOException
    {
        try (ZipFile zip = new ZipFile(aStore.toFile()))
        {
            ZipEntry manifestEntry = zip.getEntry(StoreFormat.ENTRY_MANIFEST);
            if (manifestEntry == null)
            {
                throw new IOException(aStore + " is not a metadata store: no "
                        + StoreFormat.ENTRY_MANIFEST + " entry");
            }
            byte[] manifestBytes;
            try (InputStream in = zip.getInputStream(manifestEntry))
            {
                manifestBytes = in.readAllBytes();
            }
            // Version first, and alone (D-16): decided on the raw tree, before the manifest is
            // bound and before any other entry is touched.
            int found = formatVersionOf(manifestBytes, aStore);
            if (found != StoreFormat.FORMAT_VERSION)
            {
                throw new StoreFormatException(aStore, found, StoreFormat.FORMAT_VERSION);
            }
            Map<String, byte[]> entries = readEntries(zip);
            entries.remove(StoreFormat.ENTRY_MANIFEST);
            StoreManifest manifest = StoreFormat.mapper().readValue(manifestBytes,
                    StoreManifest.class);
            verifyParts(manifest, entries);
            return new ZipMetadataStore(manifest, productEntries(entries), readCtPackages(entries));
        }
    }


    /** The manifest's {@code formatVersion}, read as a bare tree so no other field is bound. */
    private static int formatVersionOf(byte[] aManifest, Path aStore) throws IOException
    {
        JsonNode version = StoreFormat.mapper().readTree(aManifest)
                .path(StoreFormat.MANIFEST_FORMAT_VERSION);
        if (!version.isInt())
        {
            throw new IOException(aStore + " is not a metadata store: " + StoreFormat.ENTRY_MANIFEST
                    + " carries no integer " + StoreFormat.MANIFEST_FORMAT_VERSION);
        }
        return version.intValue();
    }


    @Override
    public Optional<StoredProduct> product(String aKey)
    {
        if (aKey == null)
        {
            return Optional.empty();
        }
        return products.computeIfAbsent(aKey, this::bind);
    }


    /** Binds one product entry on first access; see the class note on lazy binding. */
    private Optional<StoredProduct> bind(String aKey)
    {
        byte[] bytes = productBytes.get(aKey);
        if (bytes == null)
        {
            return Optional.empty();
        }
        try
        {
            StoredProduct product = StoreFormat.mapper().readValue(bytes, StoredProduct.class);
            if (!aKey.equals(product.key()))
            {
                throw new IOException("product entry " + StoreFormat.productEntry(aKey)
                        + " declares mismatching key " + product.key());
            }
            productBytes.remove(aKey);
            return Optional.of(product);
        }
        catch (IOException e)
        {
            throw new java.io.UncheckedIOException(
                    "metadata store product entry " + StoreFormat.productEntry(aKey)
                            + " cannot be read; the store is corrupt: " + e.getMessage(),
                    e);
        }
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
    private static Map<String, byte[]> readEntries(ZipFile aZip) throws IOException
    {
        Map<String, byte[]> entries = new LinkedHashMap<>();
        Enumeration<? extends ZipEntry> names = aZip.entries();
        while (names.hasMoreElements())
        {
            ZipEntry entry = names.nextElement();
            if (entry.isDirectory())
            {
                continue;
            }
            try (InputStream in = aZip.getInputStream(entry))
            {
                if (entries.put(entry.getName(), in.readAllBytes()) != null)
                {
                    throw new IOException("duplicate zip entry: " + entry.getName());
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


    /**
     * Collects every {@code products/<key>.json} entry's (hash-verified) bytes by key, without
     * binding it — see the class note. A product entry that is not JSON-named is still refused at
     * open, because that is a layout fault, not a content one.
     */
    private static Map<String, byte[]> productEntries(Map<String, byte[]> aEntries)
        throws IOException
    {
        Map<String, byte[]> products = new ConcurrentHashMap<>();
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
            products.put(name.substring(StoreFormat.PRODUCT_PREFIX.length(),
                    name.length() - StoreFormat.PRODUCT_SUFFIX.length()), entry.getValue());
        }
        return products;
    }


    /**
     * One of the four fixed CT parts, which {@link #verifyParts} has already proved present — every
     * one of them is required to be declared in the manifest <em>and</em> every declared part is
     * required to be in the zip, both on pain of {@link IOException}, before this class parses
     * anything.
     *
     * <p>
     * ⚠ Stated as an assertion rather than left to a bare map read: the invariant lives in
     * {@link #verifyParts}, three call frames away, and a future part added to {@link StoreFormat}
     * but not to that method's fixed list would otherwise reach the readers as a {@code null} byte
     * array and surface as an unattributed {@code NullPointerException} deep in a binary decoder.
     * NullAway flags exactly that gap.
     * </p>
     *
     * @param aEntries
     *            the store's entries, as verifyParts accepted them
     * @param aName
     *            the fixed part's entry name
     * @return that part's bytes
     */
    private static byte[] fixedPart(Map<String, byte[]> aEntries, String aName)
    {
        return java.util.Objects.requireNonNull(aEntries.get(aName),
                () -> "metadata store part " + aName + " is missing after verifyParts accepted "
                        + "the store; the fixed-part inventory and the readers disagree");
    }


    /** Rebuilds every CT package from the four CT parts, materialising shared codelists once. */
    private static Map<String, StoredCtPackage> readCtPackages(Map<String, byte[]> aEntries)
        throws IOException
    {
        StoredTerm[] terms = readTerms(fixedPart(aEntries, StoreFormat.ENTRY_CT_TERMS));
        StoreFormat.CodelistsFile headers = StoreFormat.mapper().readValue(
                fixedPart(aEntries, StoreFormat.ENTRY_CT_CODELISTS_JSON),
                StoreFormat.CodelistsFile.class);
        StoredCodelist[] codelists = readCodelists(
                fixedPart(aEntries, StoreFormat.ENTRY_CT_CODELISTS_BIN), headers, terms);
        StoreFormat.PackagesFile packagesFile = StoreFormat.mapper().readValue(
                fixedPart(aEntries, StoreFormat.ENTRY_CT_PACKAGES), StoreFormat.PackagesFile.class);
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
                    header.name(), header.preferredTerm(), header.definition(), header.synonyms(),
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
