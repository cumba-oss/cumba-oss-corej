package net.cumba.corej.core.metadata.store.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import net.cumba.corej.core.metadata.LibraryVariableAttributes;
import net.cumba.corej.core.metadata.store.MetadataStore;
import net.cumba.corej.core.metadata.store.MetadataStoreWriter;
import net.cumba.corej.core.metadata.store.RealCorpusLocator;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The guard behind {@code /metadata/store/field-manifest.json}: the manifest is the
 * machine-readable form of AUDIT-metadata-reachable-fields.md §2/§3, and this test reds whenever
 * the code and the manifest stop agreeing about what the store carries.
 *
 * <p>
 * It exists because the store had already been narrowed once without anything going red.
 * {@code StoredVariable.examples} was typed {@code List<String>} against an array-only reader while
 * every real occurrence is a JSON string, so the field was <b>dropped from every seeded store</b>;
 * the round-trip test stayed green because its fixture invented a two-element list. Fixtures are
 * hand-written and therefore cannot catch that class of defect — so nothing here is hand-written:
 * the source documents this test projects are <em>generated from the manifest itself</em>, one
 * sentinel value per declared field.
 * </p>
 *
 * <p>
 * Four independent legs, each catching a different half of that defect:
 * </p>
 * <ol>
 * <li>the manifest names exactly the record components, level by level (a field appearing in one
 * and not the other);</li>
 * <li>the manifest and the records agree on every field's TYPE — scalar vs list is the
 * {@code examples} case, and a names-only guard would let it straight through;</li>
 * <li>{@link StoreProjection} actually READS every declared field out of a source document that
 * publishes it — this is the leg that sees an array-only reader aimed at a scalar field, which no
 * amount of reflection over the record type can;</li>
 * <li>the projected records survive a real write/read round trip with every field still
 * populated.</li>
 * </ol>
 *
 * <p>
 * A fifth leg (2026-09-26, PLAN-define-ct-evaluation NS1) closes the gap the first four could not
 * see: it walks the SOURCE documents and reds on a key that is neither a manifest field nor a
 * recorded {@code excluded} entry — which is exactly how the codelist {@code name} stayed out of
 * the store. What remains unseen: whether the manifest's own {@code reached-by} column is still
 * true (that is the audit's argument, re-measured by hand), and whether the real source data
 * matches the declared type — the {@code examples} defect was ultimately a measurement question.
 * </p>
 */
class StoreFieldManifestTest
{

    private static final String RESOURCE = "/metadata/store/field-manifest.json";

    private static final String CT_ID = "sdtmct-2024-09-27";

    private static final String PRODUCT_KEY = "standards/sdtmig/3-4";

    private static final String AUDIT = "AUDIT-metadata-reachable-fields.md §2/§3 (the TABLES, not"
            + " the prose counts — the prose has been wrong twice)";

    private static final String FIX = " — if the store's field set really is meant to change, amend"
            + " the audit table and " + RESOURCE + " in the same commit; if it is not, this is a"
            + " silent narrowing of every store seeded from here on.";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static JsonNode manifest;

    private static Map<String, JsonNode> levels;

    @TempDir
    Path tempDir;

    @BeforeAll
    static void loadManifest() throws IOException
    {
        try (InputStream in = StoreFieldManifestTest.class.getResourceAsStream(RESOURCE))
        {
            assertNotNull(in, RESOURCE + " is not on the classpath");
            manifest = MAPPER.readTree(in);
        }
        levels = new LinkedHashMap<>();
        for (JsonNode level : manifest.get("levels"))
        {
            levels.put(level.get("level").asText(), level);
        }
    }


    /** Leg 1 — a field in the manifest and not the record, or the reverse. */
    @Test
    void theManifestNamesExactlyTheStoresRecordComponents() throws ClassNotFoundException
    {
        for (Map.Entry<String, JsonNode> level : levels.entrySet())
        {
            Class<?> record = recordOf(level.getValue());
            Set<String> declared = new TreeSet<>();
            for (JsonNode field : level.getValue().get("fields"))
            {
                declared.add(field.get("field").asText());
            }
            Set<String> actual = new TreeSet<>();
            for (RecordComponent component : record.getRecordComponents())
            {
                actual.add(component.getName());
            }
            assertEquals(declared, actual,
                    () -> "the field manifest and " + record.getSimpleName()
                            + " disagree about WHICH fields the " + level.getKey()
                            + " level carries." + " Manifest: " + declared + "; record: " + actual
                            + ". Frozen by " + AUDIT + FIX);
        }
    }


    /**
     * Leg 2 — the type half. ⭐ This is the leg that catches the {@code examples} defect at its
     * declaration: a names-only guard passes a field that quietly became a list.
     */
    @Test
    void theManifestAndTheRecordsAgreeOnEveryFieldType() throws ClassNotFoundException
    {
        for (Map.Entry<String, JsonNode> level : levels.entrySet())
        {
            Class<?> record = recordOf(level.getValue());
            for (JsonNode field : level.getValue().get("fields"))
            {
                String name = field.get("field").asText();
                RecordComponent component = componentOf(record, name);
                String expected = javaTypeOf(field);
                String actual = describe(component.getGenericType());
                assertEquals(expected, actual, () -> "the field manifest and "
                        + record.getSimpleName() + " disagree about the TYPE of " + level.getKey()
                        + "." + name + ": manifest says `" + field.get("type").asText() + "` (= "
                        + expected + "), the record declares " + actual + ". A scalar"
                        + " retyped to a list is exactly how `examples` was lost — the"
                        + " reader then returns null for every real occurrence and the"
                        + " field vanishes from every seeded store. Frozen by " + AUDIT + FIX);
            }
        }
    }


    /**
     * Leg 3 — the read half. The source documents are generated from the manifest, so a field the
     * projection stopped reading (or reads with the wrong reader, or under the wrong source key)
     * arrives {@code null} here. No fixture can drift away from the manifest, because there is no
     * fixture.
     */
    @Test
    void theProjectionReadsEveryFieldTheManifestDeclares() throws ReflectiveOperationException
    {
        StoreProjection projection = new StoreProjection();
        verify("product", projection.product(PRODUCT_KEY, sourceFor("product")), "product");
        verify("ctPackage", projection.ctPackage(CT_ID, sourceFor("ctPackage")), "ctPackage");
    }


    /** Leg 4 — and the writer and reader keep them all, over a real store file. */
    @Test
    void everyFieldTheManifestDeclaresSurvivesAStoreRoundTrip() throws Exception
    {
        StoreProjection projection = new StoreProjection();
        Path file = tempDir.resolve("field-manifest-probe.zip");
        new MetadataStoreWriter().addProduct(projection.product(PRODUCT_KEY, sourceFor("product")))
                .addCtPackage(projection.ctPackage(CT_ID, sourceFor("ctPackage")))
                .publishedCtPackages(List.of(CT_ID)).productCatalogue(List.of(PRODUCT_KEY))
                .write(file);
        try (MetadataStore store = MetadataStore.open(file))
        {
            assertEquals(manifest.get("formatVersion").asInt(), store.manifest().formatVersion(),
                    "the field manifest declares a different on-disk format version than the"
                            + " writer produces; a field-set change is a format change" + FIX);
            verify("product", store.product(PRODUCT_KEY).orElseThrow(), "product");
            verify("ctPackage", store.ctPackage(CT_ID).orElseThrow(), "ctPackage");
        }
    }


    /**
     * The manifest's variable scalars are the {@code key_name} vocabulary — one statement of the
     * set, not two. This replaces {@code StoreProjection.VARIABLE_SCALARS}, which was declared and
     * never read.
     */
    @Test
    void theKeyNameVocabularyIsExactlyTheManifestsScalarVariableFields()
    {
        Set<String> declared = new TreeSet<>();
        for (JsonNode field : levels.get("variable").get("fields"))
        {
            if ("scalar".equals(field.get("type").asText()))
            {
                declared.add(field.get("field").asText());
            }
        }
        assertEquals(declared, new TreeSet<>(LibraryVariableAttributes.KEYS),
                () -> "LibraryVariableAttributes.KEYS and the field manifest's scalar variable"
                        + " fields have drifted apart. A key served but not stored empties the"
                        + " filter silently; a field stored but not served makes the rule a load"
                        + " error. Frozen by " + AUDIT + FIX);
    }


    /**
     * Leg 5 — source coverage (PLAN-define-ct-evaluation NS1, T1-9). The blind spot the class
     * javadoc used to state — <i>"a field the SOURCE publishes that is missing from both the
     * manifest and the records"</i> — is exactly how the codelist {@code name} stayed out of the
     * store for a month. This leg walks the SOURCE documents (the synthetic {@code SeedFixtures} —
     * whose CDASH IG product alone reaches the domain, scenario and field levels, so every level is
     * reached without the corpus — and the real pickle corpus when {@link RealCorpusLocator}
     * resolves it: the newest package of every CT family — phase 0b measured all 206 packages
     * carrying one key set per level — plus every IG and model product) and reds on any key that is
     * neither a manifest {@code field} (by its {@code source}) nor an {@code excluded} entry with a
     * reason. T1-9 rules the pickle cache the universe: every key it publishes is stored, unless it
     * is {@code _links} or a Python cache-builder addition, and that decision is recorded here, not
     * implied by silence.
     */
    @Test
    void everySourceKeyIsEitherAStoredFieldOrADeliberateExclusion()
    {
        SourceWalker walker = new SourceWalker();
        walker.ctPackage(SeedFixtures.ctPackage("sdtmct-2024-09-27", false));
        walker.ctPackage(SeedFixtures.qsPackage());
        walker.product(SeedFixtures.igDoc());
        walker.product(SeedFixtures.tigDoc());
        walker.product(SeedFixtures.adamDoc());
        walker.product(SeedFixtures.sdtmModelDoc());
        walker.product(SeedFixtures.adamModelDoc());
        walker.product(SeedFixtures.cdashigDoc());
        RealCorpusLocator.locate().ifPresent(walker::realCorpus);

        // Non-vacuity: the walk reached every level, and each with at least one key.
        assertTrue(walker.ctPackages >= 1 && walker.codelists >= 1 && walker.terms >= 1,
                "the walk must reach packages, codelists and terms: " + walker.ctPackages + "/"
                        + walker.codelists + "/" + walker.terms);
        for (String level : levels.keySet())
        {
            assertFalse(walker.seen.getOrDefault(level, Map.of()).isEmpty(),
                    "the source walk reached no `" + level + "` document, so this leg proves"
                            + " nothing about that level");
        }

        List<String> uncovered = new ArrayList<>();
        for (Map.Entry<String, Map<String, Integer>> level : walker.seen.entrySet())
        {
            Set<String> allowed = allowedSourceKeys(level.getKey());
            for (Map.Entry<String, Integer> key : level.getValue().entrySet())
            {
                if (!allowed.contains(key.getKey()))
                {
                    uncovered.add(level.getKey() + "." + key.getKey() + " (" + key.getValue()
                            + " occurrence(s))");
                }
            }
        }
        assertEquals(List.of(), uncovered, () -> "the SOURCE publishes these keys and the store"
                + " neither stores them nor records why not. Under T1-9 every pickle key is"
                + " stored unless it is _links or a Python cache-builder addition; add a manifest"
                + " field (and the record component + projection read) or an `excluded` entry"
                + " with its reason: " + uncovered + FIX);
    }


    /**
     * The keys the manifest accounts for at one level: field sources (head segment) + exclusions.
     */
    private static Set<String> allowedSourceKeys(String aLevel)
    {
        JsonNode level = levels.get(aLevel);
        assertNotNull(level, "the source walk reached level `" + aLevel
                + "`, which the manifest does not declare" + FIX);
        Set<String> allowed = new TreeSet<>();
        for (JsonNode field : level.get("fields"))
        {
            if (field.path("sourceless").asBoolean())
            {
                continue;
            }
            String source = field.path("source").asText(field.get("field").asText());
            int cut = source.length();
            for (char c : new char[]
            {
                    '.', '['
            })
            {
                int at = source.indexOf(c);
                if (at >= 0)
                {
                    cut = Math.min(cut, at);
                }
            }
            allowed.add(source.substring(0, cut));
        }
        for (JsonNode excluded : level.path("excluded"))
        {
            allowed.add(excluded.get("source").asText());
        }
        return allowed;
    }


    /** The manifest itself has to be well formed, or the four legs above quietly check nothing. */
    @Test
    void theManifestRowsAreWellFormed()
    {
        Set<String> types = Set.of("scalar", "boolean", "list", "nested");
        Set<String> reaches = Set.of("both", "coreJ", "python", "neither", "none");
        for (Map.Entry<String, JsonNode> level : levels.entrySet())
        {
            Set<String> seen = new TreeSet<>();
            for (JsonNode field : level.getValue().get("fields"))
            {
                String name = field.get("field").asText();
                String where = level.getKey() + "." + name;
                assertTrue(seen.add(name), where + " is declared twice");
                String type = field.get("type").asText();
                assertTrue(types.contains(type), where + " has unknown type `" + type + "`");
                String reach = field.get("reached-by").asText();
                assertTrue(reaches.contains(reach),
                        where + " has unknown reached-by `" + reach + "`");
                if ("nested".equals(type))
                {
                    assertTrue(levels.containsKey(field.get("element").asText()),
                            where + " points at unknown level `" + field.get("element") + "`");
                }
            }
            Set<String> excludedSeen = new TreeSet<>();
            for (JsonNode excluded : level.getValue().path("excluded"))
            {
                String source = excluded.path("source").asText("");
                String where = level.getKey() + " excluded `" + source + "`";
                assertFalse(source.isBlank(), where + " names no source key");
                assertTrue(excludedSeen.add(source), where + " is listed twice");
                assertFalse(excluded.path("reason").asText("").isBlank(),
                        where + " carries no reason - an exclusion without a reason is a silent"
                                + " narrowing (T1-9)");
                assertFalse(seen.contains(source),
                        where + " is also a stored field of the same level");
            }
        }
        assertEquals(12, levels.size(), "a store level was added or removed without the manifest"
                + " saying so; the levels are term, codelist, ctPackage, variable, dataset, class,"
                + " variableSet, dataStructure, product, domain, scenario and field" + FIX);
    }

    // -----------------------------------------------------------------------------------------
    // Leg 5's source walk
    // -----------------------------------------------------------------------------------------

    /**
     * Walks source documents in the pickles' shape (which the synthetic {@code SeedFixtures} copy)
     * and records, per manifest level, every top-level key each document carries. The nesting is
     * the source's own: which child list belongs to which level is knowledge of the SOURCE, and
     * stating it here is the point — the manifest can then be checked against it.
     */
    private static final class SourceWalker
    {

        final Map<String, Map<String, Integer>> seen = new TreeMap<>();

        int ctPackages;

        int codelists;

        int terms;

        void ctPackage(Map<String, ?> aPackage)
        {
            ctPackages++;
            record("ctPackage", aPackage);
            for (Map<String, ?> codelist : children(aPackage, "codelists"))
            {
                codelists++;
                record("codelist", codelist);
                for (Map<String, ?> term : children(codelist, "terms"))
                {
                    terms++;
                    record("term", term);
                }
            }
        }


        void product(Map<String, ?> aProduct)
        {
            record("product", aProduct);
            for (Map<String, ?> clazz : children(aProduct, "classes"))
            {
                record("class", clazz);
                children(clazz, "classVariables").forEach(v -> record("variable", v));
                children(clazz, "datasets").forEach(this::dataset);
                children(clazz, "domains").forEach(this::domain);
                for (Map<String, ?> scenario : children(clazz, "scenarios"))
                {
                    record("scenario", scenario);
                    children(scenario, "fields").forEach(f -> record("field", f));
                }
                children(clazz, "cdashModelFields").forEach(f -> record("field", f));
            }
            children(aProduct, "datasets").forEach(this::dataset);
            children(aProduct, "domains").forEach(this::domain);
            for (Map<String, ?> structure : children(aProduct, "dataStructures"))
            {
                record("dataStructure", structure);
                for (Map<String, ?> set : children(structure, "analysisVariableSets"))
                {
                    record("variableSet", set);
                    children(set, "analysisVariables").forEach(v -> record("variable", v));
                }
            }
        }


        /** The newest package of every CT family, and every IG and model product. */
        void realCorpus(Path aDir)
        {
            net.cumba.corej.core.metadata.pickle.PickleCache cache = net.cumba.corej.core.metadata.pickle.PickleCache
                    .open(aDir);
            Map<String, String> newestPerFamily = new TreeMap<>();
            for (String id : cache.publishedCtPackages())
            {
                int at = id.indexOf("ct-");
                if (at > 0)
                {
                    newestPerFamily.merge(id.substring(0, at), id,
                            (a, b) -> a.compareTo(b) >= 0 ? a : b);
                }
            }
            assertFalse(newestPerFamily.isEmpty(), "the real corpus enumerates no CT package");
            for (String id : newestPerFamily.values())
            {
                ctPackage(cache.getCtPackage(id)
                        .orElseThrow(() -> new AssertionError("enumerated but unreadable: " + id)));
            }
            for (String key : cache.standardKeys())
            {
                product(cache.get(key).orElseThrow(() -> new AssertionError(key)));
            }
            for (String key : cache.modelKeys())
            {
                product(cache.get(key).orElseThrow(() -> new AssertionError(key)));
            }
        }


        private void dataset(Map<String, ?> aDataset)
        {
            record("dataset", aDataset);
            children(aDataset, "datasetVariables").forEach(v -> record("variable", v));
        }


        private void domain(Map<String, ?> aDomain)
        {
            record("domain", aDomain);
            children(aDomain, "fields").forEach(f -> record("field", f));
        }


        private void record(String aLevel, Map<String, ?> aDocument)
        {
            Map<String, Integer> keys = seen.computeIfAbsent(aLevel, l -> new TreeMap<>());
            for (String key : aDocument.keySet())
            {
                keys.merge(key, 1, Integer::sum);
            }
        }


        @SuppressWarnings("unchecked")
        private static List<Map<String, ?>> children(Map<String, ?> aDocument, String aKey)
        {
            Object value = aDocument.get(aKey);
            if (!(value instanceof List<?> list))
            {
                return List.of();
            }
            List<Map<String, ?>> out = new ArrayList<>();
            for (Object element : list)
            {
                if (element instanceof Map<?, ?> map)
                {
                    out.add((Map<String, ?>) map);
                }
            }
            return out;
        }
    }

    // -----------------------------------------------------------------------------------------
    // Manifest-driven source documents and verification
    // -----------------------------------------------------------------------------------------

    /**
     * A source document for one level, generated from the manifest: every declared field is
     * published, with a value naming the field it belongs to, so a reader that picks up the wrong
     * key fails on the value rather than passing.
     */
    private static ObjectNode sourceFor(String aLevel)
    {
        ObjectNode node = MAPPER.createObjectNode();
        for (JsonNode field : levels.get(aLevel).get("fields"))
        {
            if (field.path("sourceless").asBoolean())
            {
                continue;
            }
            String name = field.get("field").asText();
            String source = field.path("source").asText(name);
            String sentinel = sentinel(aLevel, name);
            switch (field.get("type").asText())
            {
            case "scalar" -> put(node, source, MAPPER.getNodeFactory().textNode(sentinel));
            case "boolean" -> put(node, source, MAPPER.getNodeFactory().booleanNode(true));
            case "list" -> put(node, source,
                    source.contains("[]") ? MAPPER.getNodeFactory().textNode(href(sentinel))
                            : MAPPER.createArrayNode().add(sentinel));
            case "nested" -> put(node, source,
                    MAPPER.createArrayNode().add(sourceFor(field.get("element").asText())));
            default -> throw new IllegalStateException(aLevel + "." + name);
            }
        }
        return node;
    }


    /**
     * Sets {@code aValue} at a source path: a plain key, a dotted path ({@code _links.model.href}),
     * or an array-of-objects path ({@code _links.codelist[].href}).
     */
    private static void put(ObjectNode aRoot, String aPath, JsonNode aValue)
    {
        int bracket = aPath.indexOf("[].");
        if (bracket >= 0)
        {
            ObjectNode element = MAPPER.createObjectNode();
            put(element, aPath.substring(bracket + 3), aValue);
            put(aRoot, aPath.substring(0, bracket), MAPPER.createArrayNode().add(element));
            return;
        }
        ObjectNode target = aRoot;
        // split(…, -1): the one-argument form drops trailing empty fields, so a malformed
        // manifest path ending in '.' would silently navigate to its parent instead of failing.
        String[] segments = aPath.split("\\.", -1);
        for (int i = 0; i < segments.length - 1; i++)
        {
            JsonNode child = target.get(segments[i]);
            ObjectNode next;
            if (child instanceof ObjectNode existing)
            {
                next = existing;
            }
            else
            {
                next = MAPPER.createObjectNode();
                target.set(segments[i], next);
            }
            target = next;
        }
        target.set(segments[segments.length - 1], aValue);
    }


    /** Walks a projected record against the manifest, level by level. */
    private static void verify(String aLevel, Object aRecord, String aPath)
        throws ReflectiveOperationException
    {
        for (JsonNode field : levels.get(aLevel).get("fields"))
        {
            String name = field.get("field").asText();
            String where = aPath + "." + name;
            Object value = componentOf(aRecord.getClass(), name).getAccessor().invoke(aRecord);
            assertNotNull(value, () -> where + " came back NULL. The manifest declares it, the"
                    + " source document published it, and the store did not keep it — which is"
                    + " precisely how `examples` was lost: an array-only reader aimed at a scalar"
                    + " source field, dropped from every seeded store while a fixture-fed"
                    + " round-trip test stayed green. Frozen by " + AUDIT + FIX);
            boolean loose = field.path("transformed").asBoolean()
                    || field.path("sourceless").asBoolean();
            switch (field.get("type").asText())
            {
            case "scalar" ->
            {
                assertFalse(((String) value).isBlank(), where + " came back blank" + FIX);
                if (!loose)
                {
                    assertEquals(sentinel(aLevel, name), value,
                            where + " was read from the wrong source key" + FIX);
                }
            }
            case "boolean" -> assertEquals(true, value,
                    where + " did not survive as the source published it" + FIX);
            case "list" ->
            {
                List<?> list = (List<?>) value;
                assertFalse(list.isEmpty(), () -> where + " came back EMPTY though the source"
                        + " published it — the same silent-drop shape as a null" + FIX);
                if (!loose)
                {
                    assertEquals(List.of(sentinel(aLevel, name)), list,
                            where + " was read from the wrong source key" + FIX);
                }
            }
            case "nested" ->
            {
                List<?> list = (List<?>) value;
                assertEquals(1, list.size(),
                        where + " did not carry the one element the source published" + FIX);
                verify(field.get("element").asText(), list.get(0), where);
            }
            default -> throw new IllegalStateException(where);
            }
        }
    }


    private static String sentinel(String aLevel, String aField)
    {
        return aLevel + "." + aField;
    }


    private static String href(String aSentinel)
    {
        return "/mdr/root/ct/sdtmct/codelists/" + aSentinel;
    }


    private static Class<?> recordOf(JsonNode aLevel) throws ClassNotFoundException
    {
        return Class.forName(aLevel.get("record").asText());
    }


    private static RecordComponent componentOf(Class<?> aRecord, String aName)
    {
        for (RecordComponent component : aRecord.getRecordComponents())
        {
            if (component.getName().equals(aName))
            {
                return component;
            }
        }
        throw new AssertionError(aRecord.getSimpleName() + " has no component `" + aName
                + "`, which the field manifest declares" + FIX);
    }


    /** The Java type the manifest's {@code type} (plus {@code element}) prescribes. */
    private static String javaTypeOf(JsonNode aField) throws ClassNotFoundException
    {
        return switch (aField.get("type").asText())
        {
        case "scalar" -> "String";
        case "boolean" -> "Boolean";
        case "list" -> "List<String>";
        case "nested" -> "List<"
                + recordOf(levels.get(aField.get("element").asText())).getSimpleName() + ">";
        default -> throw new IllegalStateException(aField.toString());
        };
    }


    /** {@code String}, {@code List<String>}, {@code List<StoredTerm>} — generics included. */
    private static String describe(Type aType)
    {
        if (aType instanceof ParameterizedType parameterized)
        {
            List<String> arguments = new ArrayList<>();
            for (Type argument : parameterized.getActualTypeArguments())
            {
                arguments.add(describe(argument));
            }
            return describe(parameterized.getRawType()) + "<" + String.join(", ", arguments) + ">";
        }
        return aType instanceof Class<?> raw ? raw.getSimpleName() : aType.getTypeName();
    }
}
