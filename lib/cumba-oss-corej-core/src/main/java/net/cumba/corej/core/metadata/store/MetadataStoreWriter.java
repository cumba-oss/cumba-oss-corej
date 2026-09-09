package net.cumba.corej.core.metadata.store;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.jspecify.annotations.Nullable;

/**
 * Writes a {@linkplain MetadataStore metadata store} zip. Deterministic by construction: the same
 * input produces byte-identical entry content regardless of insertion order, wall clock or
 * platform, which is what makes the two seeders' byte-identity conformance test (plan §5.1)
 * meaningful.
 *
 * <p>
 * The CT dedup happens here (plan §3.4): every distinct term — identity is full five-field value
 * equality — is written once into {@code ct/terms.bin} in content order; every distinct codelist
 * version (header plus term-id set) is written once; a package is then just an ordered list of
 * codelist-version ids. The term ids assigned here are internal to the file and are reassigned on
 * every rebuild (plan §5.2).
 * </p>
 *
 * <p>
 * ⚠ Within a codelist, terms are stored as a deduplicated id SET (sorted for the varint-delta
 * coding), so source term order inside a codelist is not preserved and exact duplicate term rows
 * collapse. See {@link StoredCodelist}.
 * </p>
 */
public final class MetadataStoreWriter
{

    /** A fixed, timezone-independent timestamp for every zip entry, for reproducible output. */
    private static final LocalDateTime ENTRY_TIME = LocalDateTime.of(2026, 1, 1, 0, 0, 0);

    private static final Comparator<String> NULLS_FIRST = Comparator
            .nullsFirst(Comparator.naturalOrder());

    private static final Comparator<List<String>> STRING_LIST_ORDER = Comparator
            .nullsFirst((a, b) ->
            {
                for (int i = 0; i < Math.min(a.size(), b.size()); i++)
                {
                    int byElement = NULLS_FIRST.compare(a.get(i), b.get(i));
                    if (byElement != 0)
                    {
                        return byElement;
                    }
                }
                return Integer.compare(a.size(), b.size());
            });

    /** Content order of the term table: deterministic, content-only, nulls first. */
    private static final Comparator<StoredTerm> TERM_ORDER = Comparator
            .comparing(StoredTerm::submissionValue, NULLS_FIRST)
            .thenComparing(StoredTerm::conceptId, NULLS_FIRST)
            .thenComparing(StoredTerm::preferredTerm, NULLS_FIRST)
            .thenComparing(StoredTerm::definition, NULLS_FIRST)
            .thenComparing(StoredTerm::synonyms, STRING_LIST_ORDER);

    /** Content order of the codelist-version table. */
    private static final Comparator<CodelistVersion> CODELIST_ORDER = Comparator
            .comparing(CodelistVersion::submissionValue, NULLS_FIRST)
            .thenComparing(CodelistVersion::conceptId, NULLS_FIRST)
            .thenComparing(CodelistVersion::preferredTerm, NULLS_FIRST)
            .thenComparing(CodelistVersion::definition, NULLS_FIRST)
            .thenComparing(CodelistVersion::synonyms, STRING_LIST_ORDER)
            .thenComparing(v -> v.extensible() == null ? null : v.extensible().toString(),
                    NULLS_FIRST)
            .thenComparing(CodelistVersion::termIds, (a, b) ->
            {
                for (int i = 0; i < Math.min(a.size(), b.size()); i++)
                {
                    int byElement = Integer.compare(a.get(i), b.get(i));
                    if (byElement != 0)
                    {
                        return byElement;
                    }
                }
                return Integer.compare(a.size(), b.size());
            });

    private final Map<String, StoredProduct> products = new TreeMap<>();

    private final Map<String, StoredCtPackage> ctPackages = new TreeMap<>();

    private final List<StoreProvenance> provenance = new ArrayList<>();

    private @Nullable List<String> publishedCtPackages;

    private List<String> productCatalogue = List.of();

    /**
     * Adds one projected product.
     *
     * @throws IllegalArgumentException
     *             on a duplicate or grammar-violating key
     */
    public MetadataStoreWriter addProduct(StoredProduct aProduct)
    {
        String key = aProduct.key();
        if (key == null || !StoreFormat.PRODUCT_KEY.matcher(key).matches())
        {
            throw new IllegalArgumentException("invalid product key: " + key);
        }
        if (products.putIfAbsent(key, aProduct) != null)
        {
            throw new IllegalArgumentException("duplicate product key: " + key);
        }
        return this;
    }


    /**
     * Adds one CT package.
     *
     * @throws IllegalArgumentException
     *             on a duplicate or malformed id — the store never holds a package its own
     *             {@link Presence} check would call {@link Presence#MALFORMED}
     */
    public MetadataStoreWriter addCtPackage(StoredCtPackage aPackage)
    {
        String id = aPackage.id();
        if (id == null || !StoreFormat.CT_PACKAGE_ID.matcher(id).matches())
        {
            throw new IllegalArgumentException("malformed CT package id: " + id);
        }
        if (ctPackages.putIfAbsent(id, aPackage) != null)
        {
            throw new IllegalArgumentException("duplicate CT package id: " + id);
        }
        return this;
    }


    /**
     * Sets the published CT package enumeration — REQUIRED, even when empty. It is first-class data
     * the seeder must state explicitly (plan §4.3); this writer refuses to derive it from the
     * packages that happen to be added.
     */
    public MetadataStoreWriter publishedCtPackages(List<String> aIds)
    {
        publishedCtPackages = List.copyOf(aIds);
        return this;
    }


    /** Sets the declarable product catalogue (may stay empty). */
    public MetadataStoreWriter productCatalogue(List<String> aKeys)
    {
        productCatalogue = List.copyOf(aKeys);
        return this;
    }


    /** Appends one provenance line to the manifest. */
    public MetadataStoreWriter addProvenance(StoreProvenance aProvenance)
    {
        provenance.add(aProvenance);
        return this;
    }


    /**
     * Writes the store to {@code aTarget} (created or truncated).
     *
     * @throws IllegalStateException
     *             if {@link #publishedCtPackages(List)} was never called
     */
    public void write(Path aTarget) throws IOException
    {
        if (publishedCtPackages == null)
        {
            throw new IllegalStateException(
                    "publishedCtPackages was never set; the published enumeration is first-class"
                            + " data and must be stated explicitly (it may be empty)");
        }
        Map<String, byte[]> parts = new TreeMap<>();
        buildCtParts(parts);
        for (Map.Entry<String, StoredProduct> product : products.entrySet())
        {
            parts.put(StoreFormat.productEntry(product.getKey()),
                    StoreFormat.mapper().writeValueAsBytes(product.getValue()));
        }
        Map<String, String> partHashes = new TreeMap<>();
        parts.forEach((entry, bytes) -> partHashes.put(entry, StoreFormat.sha256(bytes)));
        StoreManifest manifest = new StoreManifest(StoreFormat.FORMAT_VERSION, provenance,
                partHashes, publishedCtPackages, productCatalogue);
        byte[] manifestBytes = StoreFormat.prettyWriter().writeValueAsBytes(manifest);
        try (OutputStream out = Files.newOutputStream(aTarget);
                ZipOutputStream zip = new ZipOutputStream(out))
        {
            putEntry(zip, StoreFormat.ENTRY_MANIFEST, manifestBytes);
            for (Map.Entry<String, byte[]> part : parts.entrySet())
            {
                putEntry(zip, part.getKey(), part.getValue());
            }
        }
    }


    /** Builds the four CT parts: shared term table, version headers, id lists, package index. */
    private void buildCtParts(Map<String, byte[]> aParts) throws IOException
    {
        // 1. Distinct terms, content-ordered; id = position.
        Map<StoredTerm, Integer> termIds = new HashMap<>();
        for (StoredCtPackage pkg : ctPackages.values())
        {
            for (StoredCodelist codelist : pkg.codelists())
            {
                for (StoredTerm term : codelist.terms())
                {
                    termIds.putIfAbsent(term, 0);
                }
            }
        }
        List<StoredTerm> terms = new ArrayList<>(termIds.keySet());
        terms.sort(TERM_ORDER);
        for (int id = 0; id < terms.size(); id++)
        {
            termIds.put(terms.get(id), id);
        }

        // 2. Distinct codelist versions (header + sorted term-id set), content-ordered.
        Map<CodelistVersion, Integer> versionIds = new HashMap<>();
        Map<String, List<Integer>> packageRefs = new TreeMap<>();
        List<List<CodelistVersion>> perPackage = new ArrayList<>();
        for (StoredCtPackage pkg : ctPackages.values())
        {
            List<CodelistVersion> versions = new ArrayList<>(pkg.codelists().size());
            for (StoredCodelist codelist : pkg.codelists())
            {
                CodelistVersion version = CodelistVersion.of(codelist, termIds);
                versionIds.putIfAbsent(version, 0);
                versions.add(version);
            }
            perPackage.add(versions);
        }
        List<CodelistVersion> versions = new ArrayList<>(versionIds.keySet());
        versions.sort(CODELIST_ORDER);
        for (int id = 0; id < versions.size(); id++)
        {
            versionIds.put(versions.get(id), id);
        }
        int packageIndex = 0;
        for (String pkgId : ctPackages.keySet())
        {
            packageRefs.put(pkgId,
                    perPackage.get(packageIndex++).stream().map(versionIds::get).toList());
        }

        // 3. Serialise the four parts.
        ByteArrayOutputStream termsBin = new ByteArrayOutputStream();
        Varint.writeUnsigned(termsBin, terms.size());
        for (StoredTerm term : terms)
        {
            Varint.writeString(termsBin, term.submissionValue());
            Varint.writeString(termsBin, term.conceptId());
            Varint.writeString(termsBin, term.preferredTerm());
            Varint.writeString(termsBin, term.definition());
            Varint.writeStringList(termsBin, term.synonyms());
        }
        aParts.put(StoreFormat.ENTRY_CT_TERMS, termsBin.toByteArray());

        ByteArrayOutputStream codelistsBin = new ByteArrayOutputStream();
        Varint.writeUnsigned(codelistsBin, versions.size());
        List<StoreFormat.CodelistHeader> headers = new ArrayList<>(versions.size());
        for (CodelistVersion version : versions)
        {
            List<Integer> ids = version.termIds();
            Varint.writeUnsigned(codelistsBin, ids.size());
            int previous = 0;
            for (int i = 0; i < ids.size(); i++)
            {
                int id = ids.get(i);
                Varint.writeUnsigned(codelistsBin, i == 0 ? id : id - previous);
                previous = id;
            }
            headers.add(new StoreFormat.CodelistHeader(version.submissionValue(),
                    version.conceptId(), version.preferredTerm(), version.definition(),
                    version.synonyms(), version.extensible(), ids.size()));
        }
        aParts.put(StoreFormat.ENTRY_CT_CODELISTS_BIN, codelistsBin.toByteArray());
        aParts.put(StoreFormat.ENTRY_CT_CODELISTS_JSON,
                StoreFormat.mapper().writeValueAsBytes(new StoreFormat.CodelistsFile(headers)));
        aParts.put(StoreFormat.ENTRY_CT_PACKAGES,
                StoreFormat.mapper().writeValueAsBytes(new StoreFormat.PackagesFile(packageRefs)));
    }


    private static void putEntry(ZipOutputStream aZip, String aName, byte[] aContent)
        throws IOException
    {
        ZipEntry entry = new ZipEntry(aName);
        entry.setTimeLocal(ENTRY_TIME);
        aZip.putNextEntry(entry);
        aZip.write(aContent);
        aZip.closeEntry();
    }

    /**
     * A codelist version's dedup identity: the header fields plus the sorted, deduplicated term-id
     * list. Two codelists with identical content — common, since CT packages are cumulative
     * snapshots — collapse onto one version id.
     */
    private record CodelistVersion(@Nullable String submissionValue, @Nullable String conceptId,
            @Nullable String preferredTerm, @Nullable String definition,
            @Nullable List<String> synonyms, @Nullable Boolean extensible, List<Integer> termIds)
    {

        static CodelistVersion of(StoredCodelist aCodelist, Map<StoredTerm, Integer> aTermIds)
        {
            List<Integer> ids = aCodelist.terms().stream().map(aTermIds::get).distinct().sorted()
                    .toList();
            return new CodelistVersion(aCodelist.submissionValue(), aCodelist.conceptId(),
                    aCodelist.preferredTerm(), aCodelist.definition(), aCodelist.synonyms(),
                    aCodelist.extensible(), ids);
        }
    }
}
