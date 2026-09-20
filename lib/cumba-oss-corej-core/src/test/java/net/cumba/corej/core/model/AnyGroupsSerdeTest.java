package net.cumba.corej.core.model;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * The {@code Requirements.Variables.Any} groups serde ({@link AnyGroupsJson} through
 * {@link VariableRequirement}'s {@code @JsonProperty("Any")} accessor pair) — ruling D7's model
 * shape and D2's per-element parse.
 *
 * <p>
 * ⚠ The parse is <b>per element</b>, never "first element decides": {@code ["A", ["B","C"]]} has a
 * scalar first and {@code [["B","C"], "A"]} an array first, and both must land in the same place —
 * parseable groups plus the {@code anyMixedShape} flag — so loader gate R4 can report <i>"mixed
 * shape"</i> with the rule id instead of a Jackson exception that loses it.
 * </p>
 */
class AnyGroupsSerdeTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static VariableRequirement parse(String anyJson)
    {
        try
        {
            return MAPPER.readValue("{\"Any\":" + anyJson + "}", VariableRequirement.class);
        }
        catch (com.fasterxml.jackson.core.JacksonException e)
        {
            throw new IllegalStateException(e);
        }
    }


    private static JsonNode writtenAny(VariableRequirement vars)
    {
        return MAPPER.valueToTree(vars).get("Any");
    }

    @Nested
    @DisplayName("parse — per element, never 'first element decides'")
    class Parse
    {

        @Test
        @DisplayName("a flat authored Any is ONE group")
        void flatIsOneGroup()
        {
            VariableRequirement vars = parse("[\"--STDTC\",\"--STRF\",\"--STRTPT\"]");
            assertEquals(List.of(List.of("--STDTC", "--STRF", "--STRTPT")), vars.getAnyGroups());
            assertFalse(vars.isAnyMixedShape());
        }


        @Test
        @DisplayName("a nested authored Any is one group per inner array")
        void nestedIsGroupPerArray()
        {
            VariableRequirement vars = parse("[[\"--STDTC\",\"--STRF\"],[\"--ENDTC\",\"--ENRF\"]]");
            assertEquals(List.of(List.of("--STDTC", "--STRF"), List.of("--ENDTC", "--ENRF")),
                    vars.getAnyGroups());
            assertFalse(vars.isAnyMixedShape());
        }


        /**
         * Revision 1's "first element decides" could not parse this shape at all: the array comes
         * first, so a {@code List<List<String>>} binding throws on element 2 — exactly the Jackson
         * exception that loses the rule id. The per-element loop parses it and flags it, and gate
         * R4 turns the flag into a load error that can name its rule.
         */
        @Test
        @DisplayName("a mixed array parses — scalars become singleton groups — and sets the flag")
        void mixedSetsTheFlag()
        {
            VariableRequirement scalarFirst = parse("[\"A\",[\"B\",\"C\"]]");
            assertEquals(List.of(List.of("A"), List.of("B", "C")), scalarFirst.getAnyGroups());
            assertTrue(scalarFirst.isAnyMixedShape());

            VariableRequirement arrayFirst = parse("[[\"B\",\"C\"],\"A\"]");
            assertEquals(List.of(List.of("B", "C"), List.of("A")), arrayFirst.getAnyGroups());
            assertTrue(arrayFirst.isAnyMixedShape(),
                    "an array-first mix must flag exactly like a scalar-first one");
        }


        @Test
        @DisplayName("Any: [] is ZERO groups — an empty list, not one empty group")
        void emptyIsZeroGroups()
        {
            VariableRequirement vars = parse("[]");
            assertEquals(List.of(), vars.getAnyGroups(),
                    "which R4 arm fires depends on this: zero groups is the unsatisfiable-empty"
                            + " error, one empty group would be a group-size error");
            assertFalse(vars.isAnyMixedShape());
        }


        @Test
        @DisplayName("Any: null stays null — distinct from []")
        void nullStaysNull()
        {
            assertNull(parse("null").getAnyGroups());
        }


        @Test
        @DisplayName("a bare scalar keeps throwing — no quiet widening")
        void bareScalarStillThrows()
        {
            assertThrows(JsonMappingException.class,
                    () -> MAPPER.readValue("{\"Any\":\"AESEV\"}", VariableRequirement.class),
                    "the pre-groups List<String> binding threw on Any: AESEV, and the groups"
                            + " parse must not quietly accept it");
        }


        @Test
        @DisplayName("null and blank entries are preserved verbatim — R3 reads them")
        void nullAndBlankEntriesSurvive()
        {
            VariableRequirement flat = parse("[\"A\",null,\"\"]");
            assertEquals(List.of(Arrays.asList("A", null, "")), flat.getAnyGroups());

            VariableRequirement nested = parse("[[\"A\",\"B\"],[\"C\",null]]");
            assertEquals(List.of(List.of("A", "B"), Arrays.asList("C", null)),
                    nested.getAnyGroups());
        }
    }


    @Nested
    @DisplayName("write — one group flat, several nested")
    class Write
    {

        @Test
        @DisplayName("one group serialises FLAT")
        void oneGroupWritesFlat()
        {
            VariableRequirement vars = new VariableRequirement();
            vars.setAnyGroups(List.of(List.of("A", "B")));
            JsonNode any = writtenAny(vars);
            assertTrue(any.isArray() && any.get(0).isTextual(),
                    "a one-group Any must not show a spuriously nested shape in toJson output: "
                            + any);
        }


        @Test
        @DisplayName("two groups serialise NESTED")
        void twoGroupsWriteNested()
        {
            VariableRequirement vars = new VariableRequirement();
            vars.setAnyGroups(List.of(List.of("A", "B"), List.of("C", "D")));
            JsonNode any = writtenAny(vars);
            assertTrue(any.isArray() && any.get(0).isArray(), any::toString);
        }


        @Test
        @DisplayName("round-trip identity: flat and nested both re-parse to the same groups")
        void roundTripBothWays()
        {
            for (String json : List.of("[\"A\",\"B\",\"C\"]", "[[\"A\",\"B\"],[\"C\",\"D\"]]", "[]",
                    "null"))
            {
                VariableRequirement first = parse(json);
                VariableRequirement second = parse(writtenAny(first).toString());
                assertEquals(first.getAnyGroups(), second.getAnyGroups(), json);
                assertEquals(first, second, json);
            }
        }
    }


    @Nested
    @DisplayName("anyUnion — flat, ordered, verbatim")
    class Union
    {

        @Test
        @DisplayName("group order then entry order")
        void order()
        {
            VariableRequirement vars = parse("[[\"B\",\"A\"],[\"D\",\"C\"]]");
            assertEquals(List.of("B", "A", "D", "C"), vars.anyUnion());
        }


        @Test
        @DisplayName("never null: empty for no groups and for zero groups")
        void emptyForAbsentFacet()
        {
            assertEquals(List.of(), new VariableRequirement().anyUnion());
            assertEquals(List.of(), parse("[]").anyUnion());
        }


        /**
         * ⚠⚠ The load-bearing contract: loader gate R3 finds empty/null entries by reading this
         * union. A "helpful" filtering union would make R3 silently stop checking — the exact shape
         * of {@code RequirementsLoadGateTest.emptyEntryInAnyAndNone}'s warning.
         */
        @Test
        @DisplayName("preserves EVERY entry verbatim — nulls and blanks included")
        void verbatimPreservation()
        {
            VariableRequirement vars = parse("[[\"A\",null],[\"\",\"D\"]]");
            assertEquals(Arrays.asList("A", null, "", "D"), vars.anyUnion());
        }


        @Test
        @DisplayName("the union is unmodifiable")
        void unmodifiable()
        {
            List<String> union = parse("[\"A\",\"B\"]").anyUnion();
            assertThrows(UnsupportedOperationException.class, () -> union.add("X"));
        }
    }
}
