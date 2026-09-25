package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The {@code Requirements.Variables.All_Or_None} binding ({@code plans/PLAN-join-key-pairing.md},
 * ruling D1b: spelled like {@code Any}). The parse table itself is {@link AnyGroupsJson}'s and is
 * pinned by {@link AnyGroupsSerdeTest}; this class pins that the second facet goes through the same
 * table — flat is ONE group, nested is several, the mixed flag is set, the key is <em>known</em>
 * (never an unknown key), the write half round-trips, and the messages name the right facet.
 */
class AllOrNoneGroupsSerdeTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static VariableRequirement parse(String json)
    {
        try
        {
            return MAPPER.readValue(json, VariableRequirement.class);
        }
        catch (com.fasterxml.jackson.core.JacksonException e)
        {
            throw new IllegalStateException(e);
        }
    }


    @Test
    @DisplayName("nested groups bind, the key is known, Any and All stay untouched")
    void nestedBinds()
    {
        VariableRequirement vars = parse(
                "{\"All_Or_None\":[[\"VISITDY\",\"TV.VISITDY\"]," + "[\"VISIT\",\"TV.VISIT\"]]}");
        assertEquals(List.of(List.of("VISITDY", "TV.VISITDY"), List.of("VISIT", "TV.VISIT")),
                vars.getAllOrNoneGroups());
        assertFalse(vars.isAllOrNoneMixedShape());
        assertTrue(vars.getUnknownKeys().isEmpty(),
                "a bound facet must never reach the unknown-key collector: "
                        + vars.getUnknownKeys());
        assertNull(vars.getAnyGroups());
        assertNull(vars.getAll());
        assertEquals(List.of("VISITDY", "TV.VISITDY", "VISIT", "TV.VISIT"), vars.allOrNoneUnion());
    }


    @Test
    @DisplayName("a flat list is ONE group, exactly as a flat Any is")
    void flatIsOneGroup()
    {
        VariableRequirement vars = parse("{\"All_Or_None\":[\"VISITDY\",\"TV.VISITDY\"]}");
        assertEquals(List.of(List.of("VISITDY", "TV.VISITDY")), vars.getAllOrNoneGroups());
        assertFalse(vars.isAllOrNoneMixedShape());
    }


    @Test
    @DisplayName("a mixed array sets the shape flag for gate R4")
    void mixedShapeIsFlagged()
    {
        VariableRequirement vars = parse("{\"All_Or_None\":[\"A\",[\"B\",\"C\"]]}");
        assertTrue(vars.isAllOrNoneMixedShape());
        assertEquals(List.of(List.of("A"), List.of("B", "C")), vars.getAllOrNoneGroups());
    }


    @Test
    @DisplayName("[] is zero groups, null is null, and nothing is an empty union")
    void emptyAndNull()
    {
        assertEquals(List.of(), parse("{\"All_Or_None\":[]}").getAllOrNoneGroups());
        assertNull(parse("{\"All_Or_None\":null}").getAllOrNoneGroups());
        assertNull(parse("{}").getAllOrNoneGroups());
        assertEquals(List.of(), new VariableRequirement().allOrNoneUnion());
    }


    @Test
    @DisplayName("the key is case-sensitive: a near-miss spelling is an UNKNOWN key")
    void nearMissSpellingIsUnknown()
    {
        VariableRequirement vars = parse("{\"All_or_None\":[[\"A\",\"B\"]]}");
        assertNull(vars.getAllOrNoneGroups());
        assertTrue(vars.getUnknownKeys().contains("All_or_None"),
                "R2 can only reject what the collector records");
    }


    @Test
    @DisplayName("a bare scalar throws, and the message names THIS facet")
    void scalarThrowsNamingTheFacet()
    {
        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> parse("{\"All_Or_None\":\"VISITDY\"}"));
        assertTrue(ex.getCause().getMessage().contains("Requirements.Variables.All_Or_None"),
                ex.getCause().getMessage());
    }


    @Test
    @DisplayName("write: one group flat, several nested, null omitted — and it round-trips")
    void writeRoundTrips()
    {
        VariableRequirement one = new VariableRequirement();
        one.setAllOrNoneGroups(List.of(List.of("VISITDY", "TV.VISITDY")));
        JsonNode written = MAPPER.valueToTree(one).get("All_Or_None");
        assertTrue(written.isArray() && written.get(0).isTextual(),
                "one group writes FLAT: " + written);
        assertEquals(one.getAllOrNoneGroups(),
                parse(MAPPER.valueToTree(one).toString()).getAllOrNoneGroups());

        VariableRequirement two = new VariableRequirement();
        two.setAllOrNoneGroups(List.of(List.of("A", "B"), List.of("C", "D")));
        JsonNode nested = MAPPER.valueToTree(two).get("All_Or_None");
        assertTrue(nested.get(0).isArray(), "several groups write NESTED: " + nested);
        assertEquals(two.getAllOrNoneGroups(),
                parse(MAPPER.valueToTree(two).toString()).getAllOrNoneGroups());

        JsonNode none = MAPPER.valueToTree(new VariableRequirement()).get("All_Or_None");
        assertTrue(none == null || none.isNull(), "an absent facet writes nothing: " + none);
    }
}
