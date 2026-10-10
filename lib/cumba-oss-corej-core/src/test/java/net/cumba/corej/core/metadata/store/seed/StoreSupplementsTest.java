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
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.metadata.store.StoredProduct;
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
