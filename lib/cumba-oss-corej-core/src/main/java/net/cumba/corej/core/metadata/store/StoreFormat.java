package net.cumba.corej.core.metadata.store;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectWriter;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * The metadata store's on-disk format, version {@value #FORMAT_VERSION}: entry names, id grammars,
 * the shared JSON codec and the part-hash algorithm. Everything here is deliberately
 * package-private except the format version — the layout is an implementation detail of
 * {@link MetadataStoreWriter} and the reader behind {@link MetadataStore}, pinned for outsiders
 * only by the golden-file test.
 *
 * <pre>
 * manifest.json         format version; provenance; per-part sha256; published CT package
 *                       enumeration; product catalogue
 * products/&lt;key&gt;.json   one projected product per standards/… and models/… key
 * ct/terms.bin          shared term table, varint-delimited, content-ordered
 * ct/codelists.json     codelist-version headers
 * ct/codelists.bin      per codelist-version, varint-delta term-id lists
 * ct/packages.json      package id → ordered codelist-version refs
 * </pre>
 */
final class StoreFormat
{

    /**
     * The current on-disk format version.
     *
     * <p>
     * <b>2</b> (2026-09-08) — {@code StoredVariable.examples} became a scalar {@code String}; a
     * version-1 store could hold it as a JSON array, which this reader would refuse to bind. Every
     * v1 store in existence in fact holds {@code null} there (the v1 writer's array-only reader
     * dropped all 260 real occurrences), but a reader must refuse a version it cannot parse in
     * general rather than rely on that.<br>
     * <b>1</b> — the initial layout.
     * </p>
     */
    static final int FORMAT_VERSION = 2;

    static final String ENTRY_MANIFEST = "manifest.json";

    static final String ENTRY_CT_TERMS = "ct/terms.bin";

    static final String ENTRY_CT_CODELISTS_JSON = "ct/codelists.json";

    static final String ENTRY_CT_CODELISTS_BIN = "ct/codelists.bin";

    static final String ENTRY_CT_PACKAGES = "ct/packages.json";

    static final String PRODUCT_PREFIX = "products/";

    static final String PRODUCT_SUFFIX = ".json";

    /**
     * The CT package id grammar: {@code <family>ct-<yyyy-MM-dd>}, family lowercase alphanumeric
     * with interior hyphens. Covers every family published to date ({@code sdtmct},
     * {@code define-xmlct}, {@code qs-ftct}, …); anything else is {@link Presence#MALFORMED}.
     */
    static final Pattern CT_PACKAGE_ID = Pattern.compile("[a-z][a-z0-9-]*ct-\\d{4}-\\d{2}-\\d{2}");

    /**
     * The product key grammar: slash-separated segments of word characters, dots and hyphens, each
     * starting alphanumerically — which is exactly the catalogue's own shape
     * ({@code standards/sdtmig/3-4}, {@code models/sdtm/2-0}, {@code standards/tig/1-0/sdtm}) and,
     * not by accident, guarantees a safe zip entry name (no {@code ..}, no absolute path).
     */
    static final Pattern PRODUCT_KEY = Pattern
            .compile("[A-Za-z0-9][A-Za-z0-9._-]*(?:/[A-Za-z0-9][A-Za-z0-9._-]*)*");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StoreFormat()
    {
    }


    /**
     * The shared JSON codec. Field inclusion is declared on the record types themselves
     * ({@code @JsonInclude(NON_NULL)}), so unpublished fields vanish from the entries.
     */
    static ObjectMapper mapper()
    {
        return MAPPER;
    }


    /**
     * A pretty-printing writer with a pinned {@code \n} line feed, so the manifest's bytes do not
     * depend on the writing platform's line separator.
     */
    static ObjectWriter prettyWriter()
    {
        DefaultPrettyPrinter printer = new DefaultPrettyPrinter()
                .withObjectIndenter(new DefaultIndenter("  ", "\n"))
                .withArrayIndenter(new DefaultIndenter("  ", "\n"));
        return MAPPER.writer(printer);
    }


    /** Lowercase sha256 hex of {@code aBytes} — the manifest's per-part hash. */
    static String sha256(byte[] aBytes)
    {
        try
        {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(aBytes));
        }
        catch (NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }


    /** The zip entry name holding product {@code aKey}. */
    static String productEntry(String aKey)
    {
        return PRODUCT_PREFIX + aKey + PRODUCT_SUFFIX;
    }

    /**
     * One codelist-version header row in {@code ct/codelists.json}; the row's index is the
     * codelist-version id that {@code ct/codelists.bin} and {@code ct/packages.json} reference.
     * {@code termCount} duplicates the bin's per-version count as a cross-check.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    record CodelistHeader(@Nullable String submissionValue, @Nullable String conceptId,
            @Nullable String preferredTerm, @Nullable String definition,
            @Nullable List<String> synonyms, @Nullable Boolean extensible, int termCount)
    {
    }


    /** The {@code ct/codelists.json} document. */
    record CodelistsFile(List<CodelistHeader> codelists)
    {
    }


    /** The {@code ct/packages.json} document: package id → ordered codelist-version ids. */
    record PackagesFile(Map<String, List<Integer>> packages)
    {
    }
}
