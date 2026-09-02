package net.cumba.cdisc.core.metadata.dictionary;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import org.junit.jupiter.api.Test;

/**
 * The two halves of house-format validation, and — the point of the class — the demonstration that
 * they catch <b>different</b> things.
 *
 * <p>
 * The case contract is a consistency audit driven by the sections a document happens to have, so an
 * empty document passes it trivially. If only that half existed, a converter that emitted nothing
 * would ship. These tests pin that gap and pin that completeness closes it.
 * </p>
 */
class HouseFormatValidatorTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ------------------------------------------------------------------
    // The gap between the two checks — why both exist
    // ------------------------------------------------------------------

    @Test
    void anEmptyDocumentPassesTheCaseContractButFailsCompleteness() throws IOException
    {
        JsonNode empty = json("{\"type\":\"unii\"}");

        assertEquals(List.of(), HouseFormatValidator.caseContractViolations("unii", empty),
                "an empty document is trivially self-consistent — this is the gap");
        assertFalse(HouseFormatValidator.completenessViolations("unii", empty).isEmpty(),
                "completeness is what notices there is nothing there");
    }


    @Test
    void completenessNamesEveryMissingLevelAndRegistry() throws IOException
    {
        List<String> v = HouseFormatValidator.completenessViolations("unii",
                json("{\"type\":\"unii\"}"));

        assertEquals(3, v.size(), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.contains("levels[UNII]")), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.contains("levels[SRS]")), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.contains("pairs[unii]")), v.toString());
    }


    /** A level present but empty is as useless as one absent, and must read the same. */
    @Test
    void anEmptyLevelCountsAsMissing() throws IOException
    {
        List<String> v = HouseFormatValidator.completenessViolations("loinc", json(
                "{\"type\":\"loinc\",\"levels\":{\"LOINC\":{}},\"pairs\":{\"loinc\":{\"1\":\"x\"}}}"));

        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("levels[LOINC]"), v.get(0));
    }


    @Test
    void aCompleteReleaseSizedDictionaryPassesAllThreeChecks()
    {
        JsonNode doc = releaseSizedMedrt(1_200);

        assertEquals(List.of(), HouseFormatValidator.validate("medrt", doc));
        assertEquals(List.of(), HouseFormatValidator.cardinalityViolations("medrt", doc),
                "the advisory floor check is clean too");
    }

    // ------------------------------------------------------------------
    // A5a: the population comes from the CALLER, never from the artefact
    // ------------------------------------------------------------------


    /**
     * Every converter deliberately emits no {@code type} (the installer stamps it seven lines
     * before validating). Keying the requirement off the artefact's own field made the check
     * silently vacuous for any caller that forgot the stamp — an empty document with no
     * {@code type} passed completeness outright.
     */
    @Test
    void completenessIsKeyedOffTheCallerNotTheDocumentsOwnTypeField() throws IOException
    {
        List<String> v = HouseFormatValidator.completenessViolations("unii", json("{}"));

        assertEquals(3, v.size(),
                "an un-stamped empty document must fail exactly like a stamped one: " + v);
        assertTrue(v.stream().anyMatch(s -> s.contains("levels[UNII]")), v.toString());
    }


    /** A {@code type} field that disagrees with the caller is a violation in its own right. */
    @Test
    void aTypeFieldDisagreeingWithTheCallerIsAViolation() throws IOException
    {
        List<String> v = HouseFormatValidator.completenessViolations("icd10",
                json("{\"type\":\"meddra\"}"));

        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("'meddra'"), v.get(0));
        assertTrue(v.get(0).contains("'icd10'"), v.get(0));
    }

    // ------------------------------------------------------------------
    // A5b: the cardinality floors — presence is not wholeness
    // ------------------------------------------------------------------


    /**
     * A {@code pt.asc} truncated by an interrupted unzip satisfies every presence clause; only a
     * floor notices. The violation carries the actual count, so the operator sees
     * {@code attributes.neoplasm: 3} instead of hunting for the truncation.
     */
    @Test
    void aTruncatedSectionFailsItsCardinalityFloorNamingTheCount() throws IOException
    {
        List<String> v = HouseFormatValidator.cardinalityViolations("neoplasm", json(
                "{\"type\":\"neoplasm\",\"attributes\":{\"neoplasm\":{\"A, BENIGN\":\"BENIGN\","
                        + "\"B, MALIGNANT\":\"MALIGNANT\",\"C, BENIGN\":\"BENIGN\"}}}"));

        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("attributes.neoplasm: 3"), v.get(0));
        assertTrue(v.get(0).contains("at least 250"), v.get(0));
    }


    @Test
    void aSectionAtItsFloorPasses()
    {
        assertEquals(List.of(),
                HouseFormatValidator.cardinalityViolations("medrt", releaseSizedMedrt(1_000)));
        assertFalse(HouseFormatValidator.cardinalityViolations("medrt", releaseSizedMedrt(999))
                .isEmpty(), "and one below it does not — the floor is real");
    }


    @Test
    void anUnknownTypeHasNoCardinalityFloor() throws IOException
    {
        assertEquals(List.of(),
                HouseFormatValidator.cardinalityViolations("icd10", json("{\"type\":\"icd10\"}")));
    }


    /** A valid medrt document with {@code aCodes} MEDRTCD entries (the floored section). */
    private static JsonNode releaseSizedMedrt(int aCodes)
    {
        ObjectNode root = MAPPER.createObjectNode();
        root.put("type", "medrt");
        ObjectNode levels = root.putObject("levels");
        ObjectNode names = levels.putObject("MEDRT");
        ObjectNode codes = levels.putObject("MEDRTCD");
        ObjectNode pairs = root.putObject("pairs").putObject("medrt");
        for (int i = 0; i < aCodes; i++)
        {
            String name = "Concept " + i + " [EPC]";
            String nui = "N" + String.format(Locale.ROOT, "%010d", i);
            names.put(name.toUpperCase(Locale.ROOT), name);
            codes.put(nui, nui);
            pairs.put(nui, name);
        }
        return root;
    }


    /** meddra is the one type whose hierarchy is required — without it two rules can never fire. */
    @Test
    void meddraWithoutAHierarchyIsIncomplete() throws IOException
    {
        List<String> v = HouseFormatValidator.completenessViolations("meddra",
                json("{\"type\":\"meddra\",\"levels\":{\"PT\":{\"A\":\"A\"}}}"));

        assertTrue(v.stream().anyMatch(s -> s.contains("hierarchy")), v.toString());
    }


    /** neoplasm requires no levels at all — only its attribute map. */
    @Test
    void neoplasmNeedsOnlyItsAttributeMap() throws IOException
    {
        assertEquals(List.of(), HouseFormatValidator.completenessViolations("neoplasm", json(
                "{\"type\":\"neoplasm\",\"attributes\":{\"neoplasm\":{\"ADENOMA, BENIGN\":\"BENIGN\"}}}")));
    }


    /** A type this build's corpus does not read is not constrained. */
    @Test
    void anUnknownTypeIsNotConstrained() throws IOException
    {
        assertEquals(List.of(),
                HouseFormatValidator.completenessViolations("icd10", json("{\"type\":\"icd10\"}")));
    }

    // ------------------------------------------------------------------
    // The case contract, lifted intact
    // ------------------------------------------------------------------


    @Test
    void aKeyThatIsNotTheCaseFoldOfItsValueIsRejected() throws IOException
    {
        List<String> v = HouseFormatValidator.caseContractViolations("unii",
                json("{\"type\":\"unii\",\"levels\":{\"SRS\":{\"Aspirin\":\"Aspirin\"}}}"));

        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("case-fold"), v.get(0));
    }


    /**
     * A4 / owner ruling — clause 1 is scoped WITHIN a level: the engine consults only the level a
     * rule's {@code dictionary_term_type} names, so two levels disagreeing on case (WHO's own
     * convention for drug names vs ATC texts) is the vendor's prerogative, not an inconsistency.
     * The pre-A4 cross-level clause made every real WHODrug distribution fail validation.
     */
    @Test
    void twoPreferredFormsForOneTermAcrossLevelsAreAccepted() throws IOException
    {
        assertEquals(List.of(),
                HouseFormatValidator.caseContractViolations("meddra",
                        json("{\"type\":\"meddra\",\"levels\":{\"LLT\":{\"HEADACHE\":\"Headache\"},"
                                + "\"PT\":{\"HEADACHE\":\"HEADACHE\"}}}")),
                "each level keeps its own preferred form");
    }


    /**
     * Within one level the clause still bites. A well-formed level object cannot carry two keys
     * with one fold, so the case arises only alongside broken keys — but a validator must not rely
     * on one violation masking another.
     */
    @Test
    void twoPreferredFormsForOneTermWithinALevelAreRejected() throws IOException
    {
        List<String> v = HouseFormatValidator.caseContractViolations("meddra",
                json("{\"type\":\"meddra\",\"levels\":{\"PT\":{\"A1\":\"Headache\","
                        + "\"A2\":\"HEADACHE\"}}}"));

        assertTrue(v.stream().anyMatch(s -> s.contains("two preferred forms")), v.toString());
        assertTrue(v.stream().anyMatch(s -> s.contains("levels[PT]")), v.toString());
    }


    /**
     * When a fold has several per-level forms, a pairs/attributes value must match ONE of them — a
     * spelling matching none is still a violation.
     */
    @Test
    void aPairsValueMatchingNoLevelsFormIsStillRejected() throws IOException
    {
        List<String> v = HouseFormatValidator.caseContractViolations("whodrug",
                json("{\"type\":\"whodrug\",\"levels\":{\"PT\":{\"IBUPROFEN\":\"IBUPROFEN\"},"
                        + "\"ATC\":{\"IBUPROFEN\":\"Ibuprofen\"}},"
                        + "\"pairs\":{\"whodrug\":{\"X\":\"ibuprofen\"}}}"));

        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("'ibuprofen'"), v.get(0));
    }


    @Test
    void anUnresolvableHierarchyAncestorIsRejected() throws IOException
    {
        List<String> v = HouseFormatValidator.caseContractViolations("meddra", json(
                "{\"type\":\"meddra\",\"levels\":{\"HLT\":{\"HEADACHES NEC\":\"Headaches NEC\"}},"
                        + "\"hierarchy\":{\"Headaches NEC\":[\"Nervous system disorders\"]}}"));

        assertEquals(1, v.size(), v.toString());
        assertTrue(v.get(0).contains("is not a term in any level"), v.get(0));
    }


    /** A decode that is not a term of this dictionary is unconstrained — BENIGN, a LOINC name. */
    @Test
    void aNonTermDecodeIsUnconstrained() throws IOException
    {
        assertEquals(List.of(), HouseFormatValidator.caseContractViolations("neoplasm", json(
                "{\"type\":\"neoplasm\",\"attributes\":{\"neoplasm\":{\"ADENOMA, BENIGN\":\"BENIGN\"}}}")));
    }


    private static JsonNode json(String aJson) throws IOException
    {
        return MAPPER.readTree(aJson);
    }

}
