package net.cumba.corej.core.metadata;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link DefineMetadataListCodec}, focused on the {@code encodeStringMap} /
 * {@code decodeStringMap} pair added for Fix #123 (the {@code codelist_code_decode} attribute). The
 * codec is deliberately tolerant: anything that is not a well-formed JSON object decodes to an
 * empty map, so a missing or corrupt attribute means "no decode expectation" rather than an
 * execution error.
 */
class DefineMetadataListCodecTest
{

    @Test
    void mapRoundTripsPreservingOrder()
    {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("ALB", "Albumin");
        in.put("BILI", "Bilirubin");
        String encoded = DefineMetadataListCodec.encodeStringMap(in);
        assertEquals("{\"ALB\":\"Albumin\",\"BILI\":\"Bilirubin\"}", encoded);
        assertEquals(in, DefineMetadataListCodec.decodeStringMap(encoded));
    }


    @Test
    void emptyAndNullMapEncodeToEmptyObject()
    {
        assertEquals("{}", DefineMetadataListCodec.encodeStringMap(Map.of()));
        assertEquals("{}", DefineMetadataListCodec.encodeStringMap(null));
    }


    @Test
    void nullBlankAndNonObjectDecodeToEmptyMap()
    {
        assertTrue(DefineMetadataListCodec.decodeStringMap(null).isEmpty());
        assertTrue(DefineMetadataListCodec.decodeStringMap("").isEmpty());
        assertTrue(DefineMetadataListCodec.decodeStringMap("   ").isEmpty());
        assertTrue(DefineMetadataListCodec.decodeStringMap("{}").isEmpty());
        // A JSON array or a bare scalar is not a map — tolerated as "no mapping".
        assertTrue(DefineMetadataListCodec.decodeStringMap("[\"ALB\"]").isEmpty());
        assertTrue(DefineMetadataListCodec.decodeStringMap("ALB").isEmpty());
    }


    @Test
    void malformedJsonObjectDecodesToEmptyMapRatherThanThrowing()
    {
        assertTrue(DefineMetadataListCodec.decodeStringMap("{\"ALB\":").isEmpty());
        assertTrue(DefineMetadataListCodec.decodeStringMap("{not json}").isEmpty());
    }


    @Test
    void quotesAndUnicodeSurviveTheRoundTrip()
    {
        Map<String, String> in = new LinkedHashMap<>();
        in.put("Q", "He said \"yes\"");
        in.put("U", "Ünïcøde — µg/dL");
        in.put("B", "back\\slash");
        assertEquals(in, DefineMetadataListCodec
                .decodeStringMap(DefineMetadataListCodec.encodeStringMap(in)));
    }


    @Test
    void listCodecStillBehavesAsBefore()
    {
        // Guard against the new map methods disturbing the pre-existing list contract.
        assertEquals("[\"AE\",\"CM\"]", DefineMetadataListCodec.encode(List.of("AE", "CM")));
        assertEquals(List.of("AE", "CM"), DefineMetadataListCodec.decode("[\"AE\",\"CM\"]"));
        assertEquals(List.of(), DefineMetadataListCodec.decode(null));
        assertEquals(List.of("AE"), DefineMetadataListCodec.decode("AE"));
    }

}
