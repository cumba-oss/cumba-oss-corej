package net.cumba.corej.core.metadata.store.seed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.metadata.store.StoredDataset;
import net.cumba.corej.core.metadata.store.StoredProduct;
import net.cumba.corej.core.metadata.store.StoredVariable;
import org.junit.jupiter.api.Test;

/**
 * The seed supplements register (PLAN-seed-sendig-dart-1-2 D1/D2, Q1 (c)): the shipped resource,
 * the loader's refusals — each proven by a planted document — and the provenance ref round trip.
 */
class StoreSupplementsTest
{

    private static final String DART_12 = "standards/sendig/dart-1-2";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static byte[] resourceBytes() throws IOException
    {
        try (InputStream in = StoreSupplements.class.getResourceAsStream(StoreSupplements.RESOURCE))
        {
            assertTrue(in != null, StoreSupplements.RESOURCE);
            return in.readAllBytes();
        }
    }


    /** A one-entry register over a minimal valid product, for the planted refusals. */
    private static ObjectNode document(String aKey)
    {
        ObjectNode variable = MAPPER.createObjectNode().put("name", "FXRESCAT").put("label",
                "Result Category");
        ObjectNode dataset = MAPPER.createObjectNode().put("name", "FX");
        dataset.putArray("datasetVariables").add(variable);
        ObjectNode clazz = MAPPER.createObjectNode().put("name", "Findings");
        clazz.putArray("datasets").add(dataset);
        ObjectNode product = MAPPER.createObjectNode().put("name", "SENDIG-DART v1.2");
        product.putArray("classes").add(clazz);
        ObjectNode entry = MAPPER.createObjectNode().put("key", aKey).put("approval", "E14");
        entry.set("product", product);
        ObjectNode doc = MAPPER.createObjectNode();
        doc.putArray("supplements").add(entry);
        return doc;
    }


    private static ObjectNode firstEntry(ObjectNode aDocument)
    {
        return (ObjectNode) aDocument.get("supplements").get(0);
    }


    /** The planted document's one variable row ({@code FX.FXRESCAT}). */
    private static ObjectNode row(ObjectNode aDocument)
    {
        return (ObjectNode) firstEntry(aDocument).get("product").get("classes").get(0)
                .get("datasets").get(0).get("datasetVariables").get(0);
    }


    private static String refusal(JsonNode aDocument)
    {
        return assertThrows(IllegalStateException.class, () -> StoreSupplements.parse(aDocument))
                .getMessage();
    }


    @Test
    void theShippedRegisterHoldsExactlyTheE14Product() throws IOException, NoSuchAlgorithmException
    {
        StoreSupplements register = StoreSupplements.load();
        assertEquals(1, register.supplements().size());
        StoreSupplements.Supplement dart = register.supplements().get(0);
        assertEquals(DART_12, dart.key());
        assertEquals("E14 owner 2026-10-07", dart.approval());
        assertEquals(Set.of(DART_12), StoreSupplements.shippedKeys());
        assertEquals(
                HexFormat.of()
                        .formatHex(MessageDigest.getInstance("SHA-256").digest(resourceBytes())),
                register.digest(), "the digest is the resource's bytes'");

        // The P1 extract's shape (plans/findings/sendig-dart-1-2/P1-laneA-extract.md).
        JsonNode product = dart.product();
        assertEquals("1.2", product.path("version").asText());
        assertEquals("/mdr/sdtm/1-6", product.path("_links").path("model").path("href").asText());
        int datasets = 0;
        int variables = 0;
        for (JsonNode clazz : product.path("classes"))
        {
            for (JsonNode dataset : clazz.path("datasets"))
            {
                datasets++;
                variables += dataset.path("datasetVariables").size();
            }
        }
        assertEquals(7, product.path("classes").size());
        assertEquals(32, datasets);
        assertEquals(670, variables);
        JsonNode document = MAPPER.readTree(resourceBytes());
        assertEquals("3c193b6f42643b16d1edd3f669f24baeb460cd699ddf0058684b5bbca3ef128d",
                document.path("supplements").get(0).path("authority").path("sha256").asText(),
                "the authority is the final SENDIG-DART 1.2 PDF");
    }


    /**
     * Check 6 and the normaliser in Java (lane A's emulation measured both): the projected
     * supplement is a text-normaliser fixpoint and no label ends in a footnote marker.
     */
    @Test
    void theProjectedSupplementNeedsNoNormalisationAndCarriesNoFootnoteMarker()
    {
        StoreSupplements.Supplement dart = StoreSupplements.load().supplements().get(0);
        StoreProjection projection = new StoreProjection();
        StoredProduct product = projection.product(dart.key(), dart.product());
        assertEquals(List.of(), List.copyOf(projection.normalisedSites()));
        assertEquals(List.of(), LabelFootnoteScan.scan(Map.of(dart.key(), product)));
        assertEquals("/mdr/sdtm/1-6", product.modelHref());
    }


    @Test
    void aWellFormedPlantedRegisterParses()
    {
        StoreSupplements parsed = StoreSupplements.parse(document(DART_12));
        assertEquals(List.of(DART_12),
                parsed.supplements().stream().map(StoreSupplements.Supplement::key).toList());
        assertEquals(64, parsed.digest().length());
    }


    /** Q1 (c): a variable description anywhere is refused — the CDISC Notes are not shipped. */
    @Test
    void aVariableDescriptionIsRefused()
    {
        ObjectNode doc = document(DART_12);
        ObjectNode variable = (ObjectNode) firstEntry(doc).get("product").get("classes").get(0)
                .get("datasets").get(0).get("datasetVariables").get(0);
        variable.put("description", "CDISC Notes text");
        String message = refusal(doc);
        assertTrue(message.contains("FX.FXRESCAT") && message.contains("Q1 (c)"), message);
    }


    /**
     * Review L1: the refusal runs on the PROJECTED product, so every slot of every
     * {@link StoredVariable} the projection reaches is covered — the class's dataset rows above,
     * and here the other three paths (class variables, top-level datasets, ADaM analysis variable
     * sets), one planted document each.
     */
    @Test
    void aVariableDescriptionOnAnyOtherProjectedPathIsRefused()
    {
        ObjectNode classVariable = document(DART_12);
        ObjectNode clazz = (ObjectNode) firstEntry(classVariable).get("product").get("classes")
                .get(0);
        clazz.putArray("classVariables").addObject().put("name", "--TESTCD").put("description",
                "CDISC Notes text");
        String message = refusal(classVariable);
        assertTrue(message.contains("Findings.--TESTCD carries a variable description")
                && message.contains("Q1 (c)"), message);

        ObjectNode topLevel = document(DART_12);
        ((ObjectNode) firstEntry(topLevel).get("product")).putArray("datasets").addObject()
                .put("name", "DP").putArray("datasetVariables").addObject().put("name", "DPDTC")
                .put("description", "CDISC Notes text");
        message = refusal(topLevel);
        assertTrue(message.contains("DP.DPDTC carries a variable description"), message);

        ObjectNode analysis = document(DART_12);
        ObjectNode structure = ((ObjectNode) firstEntry(analysis).get("product"))
                .putArray("dataStructures").addObject().put("name", "ADSL");
        structure.putArray("analysisVariableSets").addObject().put("name", "Identifier")
                .putArray("analysisVariables").addObject().put("name", "STUDYID")
                .put("description", "CDISC Notes text");
        message = refusal(analysis);
        assertTrue(message.contains("ADSL/Identifier.STUDYID carries a variable description"),
                message);
    }


    /**
     * Review L1: the other free-text variable slots the projection keeps are refused like the
     * description — notes, definition, examples, role description, usage restrictions — and an
     * empty string is refused too (present, not absent).
     */
    @Test
    void everyFreeTextVariableSlotIsRefused()
    {
        for (String slot : List.of("notes", "definition", "examples", "roleDescription",
                "usageRestrictions", "description"))
        {
            ObjectNode doc = document(DART_12);
            ((ObjectNode) firstEntry(doc).get("product").get("classes").get(0).get("datasets")
                    .get(0).get("datasetVariables").get(0)).put(slot, "");
            String message = refusal(doc);
            assertTrue(message.contains("FX.FXRESCAT carries a variable " + slot), message);
        }
    }


    /**
     * Confirmation look L-b: the raw scan backs the projected check. A free-text member the
     * projection does not keep — a container value, a member nested inside a variable row, a
     * variable row under an array the projection does not read — would still ship verbatim in the
     * resource, so the raw document is refused too, whatever the value type. Each planted document
     * here passes the projected check and is caught only by the raw scan.
     */
    @Test
    void aFreeTextMemberTheProjectionDoesNotKeepIsRefusedByTheRawScan()
    {
        ObjectNode listValued = document(DART_12);
        row(listValued).putArray("description").add("CDISC Notes text");
        String message = refusal(listValued);
        assertTrue(message
                .contains("free-text member 'description' at"
                        + " /classes/0/datasets/0/datasetVariables/0/description")
                && message.contains("Q1 (c)"), message);

        ObjectNode objectValued = document(DART_12);
        ((ObjectNode) firstEntry(objectValued).get("product").get("classes").get(0))
                .putArray("classVariables").addObject().put("name", "--TESTCD").putObject("notes")
                .put("text", "CDISC Notes text");
        message = refusal(objectValued);
        assertTrue(message.contains("'notes' at /classes/0/classVariables/0/notes"), message);

        ObjectNode jsonNull = document(DART_12);
        row(jsonNull).putNull("roleDescription");
        message = refusal(jsonNull);
        assertTrue(message.contains("'roleDescription' at"), message);

        ObjectNode nested = document(DART_12);
        row(nested).putObject("_links").putArray("codelist").addObject()
                .put("href", "/mdr/root/ct/sendct/codelists/C66742")
                .put("definition", "CDISC Notes text");
        message = refusal(nested);
        assertTrue(message.contains(
                "'definition' at /classes/0/datasets/0/datasetVariables/0/_links/codelist/0/definition"),
                message);

        ObjectNode unread = document(DART_12);
        ((ObjectNode) firstEntry(unread).get("product").get("classes").get(0).get("datasets")
                .get(0)).putArray("variables").addObject().put("name", "DPDTC")
                        .put("usageRestrictions", "CDISC Notes text");
        message = refusal(unread);
        assertTrue(message.contains("'usageRestrictions' at /classes/0/datasets/0/variables/0"),
                message);

        ObjectNode examples = document(DART_12);
        row(examples).putArray("examples").add(1);
        message = refusal(examples);
        assertTrue(message.contains("'examples' at"), message);
    }


    /**
     * The control of the raw scan: the product, a class, a dataset (of a class or top-level), a
     * data structure and an analysis variable set may carry a {@code description} of their own —
     * the shipped register does (product, classes, datasets) — and only the description; a variable
     * row never.
     */
    @Test
    void aDescriptionOfTheProductAClassADatasetOrAStructureIsNotVariableText()
    {
        ObjectNode doc = document(DART_12);
        ObjectNode product = (ObjectNode) firstEntry(doc).get("product");
        product.put("description", "Product description");
        ObjectNode clazz = (ObjectNode) product.get("classes").get(0);
        clazz.put("description", "Class description");
        ((ObjectNode) clazz.get("datasets").get(0)).put("description", "Dataset description");
        product.putArray("datasets").addObject().put("name", "DP").put("description",
                "Top-level dataset description");
        ObjectNode structure = product.putArray("dataStructures").addObject().put("name", "ADSL")
                .put("description", "Structure description");
        structure.putArray("analysisVariableSets").addObject().put("name", "Identifier")
                .put("description", "Set description").putArray("analysisVariables").addObject()
                .put("name", "STUDYID");
        assertEquals(1, StoreSupplements.parse(doc).supplements().size());

        ObjectNode classNotes = document(DART_12);
        ((ObjectNode) firstEntry(classNotes).get("product").get("classes").get(0)).put("notes",
                "CDISC Notes text");
        assertTrue(refusal(classNotes).contains("'notes' at /classes/0/notes"),
                "only the description is a container's own");
    }


    /**
     * The guard of L1's "every path by construction" (confirmation look L-a): every record PATH
     * from {@link StoredProduct} to a {@link StoredVariable} list, walked over every record
     * component and deduplicated only on the recursion stack — so a second path to an EXISTING
     * holder (a dataset list added to another record) reds here as much as a new holder does.
     */
    @Test
    void theRefusalWalksEveryRecordPathThatHoldsVariables()
    {
        Set<String> paths = new TreeSet<>();
        collectVariablePaths(StoredProduct.class, "", paths, new HashSet<>());
        assertEquals(Set.of("StoredProduct.classes>StoredClass.classVariables",
                "StoredProduct.classes>StoredClass.datasets>StoredDataset.variables",
                "StoredProduct.datasets>StoredDataset.variables",
                "StoredProduct.dataStructures>StoredDataStructure.variableSets"
                        + ">StoredVariableSet.variables"),
                paths);

        // The sensitivity arm: a direct list, a nested holder, and a second path to that holder.
        Set<String> planted = new TreeSet<>();
        collectVariablePaths(PlantedHolder.class, "", planted, new HashSet<>());
        assertEquals(Set.of("PlantedHolder.extra", "PlantedHolder.nested>StoredDataset.variables",
                "PlantedHolder.more>StoredDataset.variables"), planted);
    }

    /** A record the guard must see through: a direct variable list and two paths to a holder. */
    private record PlantedHolder(List<StoredVariable> extra, StoredDataset nested,
            List<StoredDataset> more)
    {
    }

    private static void collectVariablePaths(Class<?> aType, String aPrefix, Set<String> aPaths,
            Set<Class<?>> aStack)
    {
        if (!aType.isRecord() || !aStack.add(aType))
        {
            return;
        }
        for (RecordComponent component : aType.getRecordComponents())
        {
            String step = aPrefix + aType.getSimpleName() + "." + component.getName();
            List<Class<?>> elements = new ArrayList<>();
            elements.add(component.getType());
            if (component.getGenericType() instanceof ParameterizedType generic)
            {
                for (Type argument : generic.getActualTypeArguments())
                {
                    if (argument instanceof Class<?> type)
                    {
                        elements.add(type);
                    }
                }
            }
            for (Class<?> element : elements)
            {
                if (element == StoredVariable.class)
                {
                    aPaths.add(step);
                }
                collectVariablePaths(element, step + ">", aPaths, aStack);
            }
        }
        aStack.remove(aType);
    }


    @Test
    void aKeyOutsideTheGrammarOrOutsideStandardsIsRefused()
    {
        assertTrue(refusal(document("standards/sendig/dart 1-2")).contains("not a standards/"));
        assertTrue(refusal(document("models/sdtm/1-6")).contains("not a standards/"));
        assertTrue(refusal(document("")).contains("not a standards/"));
    }


    @Test
    void aDuplicateKeyAMissingApprovalOrAMissingProductIsRefused()
    {
        ObjectNode duplicate = document(DART_12);
        ((ArrayNode) duplicate.get("supplements")).add(firstEntry(document(DART_12)));
        assertTrue(refusal(duplicate).contains("duplicate key"));

        ObjectNode unapproved = document(DART_12);
        firstEntry(unapproved).put("approval", " ");
        assertTrue(refusal(unapproved).contains("names no approval"));

        ObjectNode productless = document(DART_12);
        firstEntry(productless).remove("product");
        assertTrue(refusal(productless).contains("has no product"));

        assertTrue(refusal(MAPPER.createObjectNode()).contains("no 'supplements' list"));
    }


    @Test
    void theProvenanceRefRoundTripsTheAppliedKeys()
    {
        StoreSupplements register = StoreSupplements.parse(document(DART_12));
        String none = register.provenanceRef(List.of());
        assertEquals("sha256:" + register.digest(), none);
        assertEquals(Set.of(), StoreSupplements.appliedKeys(none));

        String two = register.provenanceRef(List.of(DART_12, "standards/x/1-0"));
        assertEquals("sha256:" + register.digest() + " applied:" + DART_12 + ",standards/x/1-0",
                two);
        assertEquals(Set.of(DART_12, "standards/x/1-0"), StoreSupplements.appliedKeys(two));
        assertEquals(Set.of(DART_12),
                StoreSupplements.appliedKeys("sha256:other applied:" + DART_12),
                "the applied keys are read under any digest");
    }
}
