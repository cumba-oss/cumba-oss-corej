package net.cumba.corej.core.metadata.store.seed;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;
import net.cumba.corej.core.metadata.store.StoredClass;
import net.cumba.corej.core.metadata.store.StoredDataStructure;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import net.cumba.corej.core.metadata.store.StoredVariableSet;
import org.jspecify.annotations.Nullable;

/**
 * The seed supplements (PLAN-seed-sendig-dart-1-2 D1/D2): whole products the seed sources do not
 * serve, shipped in the class-path resource {@value #RESOURCE} beside the errata register, each
 * under an owner approval (E14: SENDIG-DART 1.2, a one-time extract of the printed IG).
 *
 * <p>
 * A supplement's {@code product} is a <em>source-shaped</em> document — the CDISC Library product
 * JSON the pickle and the Web API deliver — so {@link SeedRun} projects it through the run's one
 * {@link StoreProjection} and the errata register and the footnote scan apply to it unchanged. The
 * loader refuses (a build defect, never a runtime condition) a key outside the store's product-key
 * grammar or outside {@code standards/} (the catalogue holds IG keys only), a duplicate key, a
 * missing approval, and — owner ruling Q1 (c) — any variable of the PROJECTED product carrying a
 * free-text slot (description, notes, definition, examples, role description, usage restrictions),
 * and any of those member names anywhere in the RAW product document, whatever its value type, bar
 * the {@code description} of the product, a class, a dataset, a data structure or an analysis
 * variable set: the CDISC Notes text is not shipped.
 * </p>
 *
 * <p>
 * The register's sha256 is recorded with the applied keys as the {@value #PROVENANCE_SOURCE}
 * provenance line, which tells a later seed whether a baseline's copy of a key came from a
 * supplement or from a source (D3 (ii)).
 * </p>
 */
public final class StoreSupplements
{

    /** The class-path resource. */
    static final String RESOURCE = "/metadata/store/store-supplements.json";

    /** The manifest provenance source of the supplements' line (D6, F7). */
    static final String PROVENANCE_SOURCE = "store-supplement";

    /** The ref prefix of the digest, as the errata register's. */
    static final String DIGEST_PREFIX = "sha256:";

    /** Separates the digest from the applied keys in the provenance ref. */
    static final String APPLIED_SEPARATOR = " applied:";

    /** Separates the applied keys. */
    static final String KEY_SEPARATOR = ",";

    /**
     * The store's product-key grammar ({@code StoreFormat.PRODUCT_KEY}), re-checked here so a
     * malformed key fails at load with the register named, not deep inside the writer.
     */
    private static final Pattern PRODUCT_KEY = Pattern
            .compile("[A-Za-z0-9][A-Za-z0-9._-]*(?:/[A-Za-z0-9][A-Za-z0-9._-]*)*");

    private static final String STANDARDS_PREFIX = "standards/";

    /** The free-text variable members of owner ruling Q1 (c), as the source documents name them. */
    private static final Set<String> FREE_TEXT_MEMBERS = Set.of("description", "notes",
            "definition", "examples", "roleDescription", "usageRestrictions");

    /**
     * The arrays whose elements are not variables, so — like the product itself — may carry a
     * {@code description} of their own (the projection keeps it as the class's, dataset's,
     * structure's or variable set's description).
     */
    private static final Set<String> DESCRIBED_CONTAINERS = Set.of("classes", "datasets",
            "dataStructures", "analysisVariableSets");

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /**
     * The shipped register's keys, loaded on first use of {@link #shippedKeys()} (holder idiom).
     */
    private static final class Shipped
    {

        static final Set<String> KEYS = keysOf(load());
    }


    /**
     * One supplement.
     *
     * @param key
     *            the product key it supplies
     * @param approval
     *            the owner approval it ships under
     * @param product
     *            the source-shaped product document
     */
    record Supplement(String key, String approval, JsonNode product)
    {

        /** Null checks. */
        Supplement
        {
            Objects.requireNonNull(key, "key");
            Objects.requireNonNull(approval, "approval");
            Objects.requireNonNull(product, "product");
        }
    }

    private final List<Supplement> supplements;

    private final String digest;

    private StoreSupplements(List<Supplement> aSupplements, String aDigest)
    {
        supplements = List.copyOf(aSupplements);
        digest = aDigest;
    }


    /**
     * Loads the register from the class path.
     *
     * @return the register
     * @throws IllegalStateException
     *             when the resource is missing or malformed — a build defect
     */
    static StoreSupplements load()
    {
        try (InputStream in = StoreSupplements.class.getResourceAsStream(RESOURCE))
        {
            if (in == null)
            {
                throw new IllegalStateException("seed supplements missing: " + RESOURCE);
            }
            byte[] bytes = in.readAllBytes();
            return new StoreSupplements(parseEntries(MAPPER.readTree(bytes)), sha256(bytes));
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("seed supplements unreadable: " + RESOURCE, e);
        }
    }


    /**
     * Parses a register document ({@code {"supplements": [...]}}).
     *
     * @param aDocument
     *            the document
     * @return the register; its {@link #digest()} is that of the document's compact JSON
     * @throws IllegalStateException
     *             on any of the refusals of the class comment
     */
    static StoreSupplements parse(JsonNode aDocument)
    {
        List<Supplement> entries = parseEntries(aDocument);
        try
        {
            return new StoreSupplements(entries, sha256(MAPPER.writeValueAsBytes(aDocument)));
        }
        catch (com.fasterxml.jackson.core.JsonProcessingException e)
        {
            throw new IllegalStateException("seed supplements: not serialisable", e);
        }
    }


    private static List<Supplement> parseEntries(JsonNode aDocument)
    {
        JsonNode list = aDocument.path("supplements");
        if (!list.isArray())
        {
            throw new IllegalStateException("seed supplements: no 'supplements' list");
        }
        List<Supplement> entries = new ArrayList<>();
        Set<String> keys = new LinkedHashSet<>();
        for (JsonNode node : list)
        {
            String key = node.path("key").asText();
            if (!PRODUCT_KEY.matcher(key).matches() || !key.startsWith(STANDARDS_PREFIX))
            {
                throw new IllegalStateException("seed supplements: '" + key
                        + "' is not a standards/ product key of the store's grammar");
            }
            if (!keys.add(key))
            {
                throw new IllegalStateException("seed supplements: duplicate key " + key);
            }
            String approval = node.path("approval").asText();
            if (approval.isBlank())
            {
                throw new IllegalStateException("seed supplements: " + key + " names no approval");
            }
            JsonNode product = node.get("product");
            if (product == null || !product.isObject())
            {
                throw new IllegalStateException("seed supplements: " + key + " has no product");
            }
            refuseVariableText(key, product);
            refuseRawText(key, product, "", true);
            entries.add(new Supplement(key, approval, product));
        }
        return entries;
    }


    /**
     * Owner ruling Q1 (c), checked on the PROJECTED product (review L1): no {@link StoredVariable}
     * the projection reaches — a class's variables, a class's or a top-level dataset's variables,
     * an analysis variable set's variables — may carry a free-text slot (description, notes,
     * definition, examples, role description, usage restrictions). Checking what the projection
     * keeps covers every source path by construction; {@code StoreSupplementsTest} reds when a new
     * record path to a variable appears.
     */
    private static void refuseVariableText(String aKey, JsonNode aProduct)
    {
        StoredProduct projected = new StoreProjection().product(aKey, aProduct);
        for (StoredClass clazz : projected.classes())
        {
            refuseVariableText(aKey, clazz.name(), clazz.classVariables());
            for (StoredDataset dataset : clazz.datasets())
            {
                refuseVariableText(aKey, dataset.name(), dataset.variables());
            }
        }
        for (StoredDataset dataset : projected.datasets())
        {
            refuseVariableText(aKey, dataset.name(), dataset.variables());
        }
        for (StoredDataStructure structure : projected.dataStructures())
        {
            for (StoredVariableSet set : structure.variableSets())
            {
                refuseVariableText(aKey, structure.name() + "/" + set.name(), set.variables());
            }
        }
    }


    private static void refuseVariableText(String aKey, @Nullable String aOwner,
            List<StoredVariable> aVariables)
    {
        for (StoredVariable variable : aVariables)
        {
            String slot = freeTextSlot(variable);
            if (slot != null)
            {
                throw new IllegalStateException("seed supplements: " + aKey + " " + aOwner + "."
                        + variable.name() + " carries a variable " + slot
                        + " - owner ruling Q1 (c): the CDISC Notes text is not shipped");
            }
        }
    }


    /**
     * Owner ruling Q1 (c) on the RAW document (confirmation look L-b), behind the projected check:
     * a free-text member the projection does not keep — a container value, a member nested inside a
     * variable row, a variable row under an array the projection does not read — would still ship
     * verbatim in the resource. Any {@link #FREE_TEXT_MEMBERS} name at any depth is refused,
     * whatever its value type (JSON null included); the one exception is the {@code description} of
     * the product or of an element of a {@link #DESCRIBED_CONTAINERS} array.
     *
     * @param aPointer
     *            the JSON pointer of {@code aNode} within the product
     * @param aMayDescribe
     *            whether {@code aNode} may carry a {@code description} of its own
     */
    private static void refuseRawText(String aKey, JsonNode aNode, String aPointer,
            boolean aMayDescribe)
    {
        if (aNode.isObject())
        {
            for (Map.Entry<String, JsonNode> member : aNode.properties())
            {
                String name = member.getKey();
                String pointer = aPointer + "/" + name;
                if (FREE_TEXT_MEMBERS.contains(name)
                        && !(aMayDescribe && "description".equals(name)))
                {
                    throw new IllegalStateException("seed supplements: " + aKey
                            + " carries a free-text member '" + name + "' at " + pointer
                            + " - owner ruling Q1 (c): the CDISC Notes text is not shipped");
                }
                refuseRawElements(aKey, member.getValue(), pointer,
                        DESCRIBED_CONTAINERS.contains(name));
            }
        }
        else if (aNode.isArray())
        {
            refuseRawElements(aKey, aNode, aPointer, false);
        }
    }


    /** {@link #refuseRawText} over an array's elements, or over a non-array value itself. */
    private static void refuseRawElements(String aKey, JsonNode aValue, String aPointer,
            boolean aElementsMayDescribe)
    {
        if (!aValue.isArray())
        {
            refuseRawText(aKey, aValue, aPointer, false);
            return;
        }
        for (int i = 0; i < aValue.size(); i++)
        {
            refuseRawText(aKey, aValue.get(i), aPointer + "/" + i, aElementsMayDescribe);
        }
    }


    /** The first free-text slot the variable carries, or {@code null} when it carries none. */
    private static @Nullable String freeTextSlot(StoredVariable aVariable)
    {
        if (aVariable.description() != null)
        {
            return "description";
        }
        if (aVariable.notes() != null)
        {
            return "notes";
        }
        if (aVariable.definition() != null)
        {
            return "definition";
        }
        if (aVariable.examples() != null)
        {
            return "examples";
        }
        if (aVariable.roleDescription() != null)
        {
            return "roleDescription";
        }
        return aVariable.usageRestrictions() != null ? "usageRestrictions" : null;
    }


    /**
     * The supplements, in register order.
     *
     * @return the supplements
     */
    List<Supplement> supplements()
    {
        return supplements;
    }


    /**
     * The sha256 (lower-case hex) of the register: of the resource's bytes for {@link #load()}, of
     * the document's compact JSON for {@link #parse(JsonNode)}.
     *
     * @return the digest
     */
    String digest()
    {
        return digest;
    }


    /**
     * The provenance ref for a run that applied the given keys: {@code sha256:<hex>}, followed by
     * {@value #APPLIED_SEPARATOR} and the comma-separated keys when any was applied.
     *
     * @param aApplied
     *            the applied keys, in order
     * @return the ref
     */
    String provenanceRef(List<String> aApplied)
    {
        String ref = DIGEST_PREFIX + digest;
        return aApplied.isEmpty() ? ref
                : ref + APPLIED_SEPARATOR + String.join(KEY_SEPARATOR, aApplied);
    }


    /**
     * The keys a provenance ref written by {@link #provenanceRef(List)} lists as applied — under
     * any digest: the question is where a stored copy came from, not which register supplied it.
     *
     * @param aRef
     *            the ref of a {@value #PROVENANCE_SOURCE} line
     * @return the applied keys; empty when none or when the ref has no applied part
     */
    static Set<String> appliedKeys(String aRef)
    {
        int at = aRef.indexOf(APPLIED_SEPARATOR);
        if (at < 0)
        {
            return Set.of();
        }
        Set<String> keys = new LinkedHashSet<>();
        for (String key : aRef.substring(at + APPLIED_SEPARATOR.length()).split(KEY_SEPARATOR, -1))
        {
            if (!key.isEmpty())
            {
                keys.add(key);
            }
        }
        return Set.copyOf(keys);
    }


    /**
     * The product keys this build's shipped register supplies — for a message that tells a user on
     * an older store to re-seed (D8), not for seeding.
     *
     * @return the keys
     */
    public static Set<String> shippedKeys()
    {
        return Shipped.KEYS;
    }


    private static Set<String> keysOf(StoreSupplements aRegister)
    {
        Set<String> keys = new LinkedHashSet<>();
        aRegister.supplements().forEach(s -> keys.add(s.key()));
        return Set.copyOf(keys);
    }


    private static String sha256(byte[] aBytes)
    {
        try
        {
            return java.util.HexFormat.of()
                    .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(aBytes));
        }
        catch (java.security.NoSuchAlgorithmException e)
        {
            throw new IllegalStateException("JVM without SHA-256", e);
        }
    }
}
