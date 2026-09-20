package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.cumba.corej.core.model.*;
import org.junit.jupiter.api.Test;

/**
 * The {@code CheckCondition} grammar after phase 7d of {@code PLAN-typed-expression-engine}
 * (D121/D121b): composites and {@code expression:} nodes bind; the retired v1 operator-leaf form is
 * a LOUD failure naming the retired form — the load-time successor of the OPGAP pin's rule-level
 * {@code NO_NATIVE_CHECK_EXPR} contract, served one stage earlier.
 */
class CheckConditionDeserializationTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Test
    void testAllOverExpressions() throws Exception
    {
        String json = """
                {
                  "all": [
                    { "expression": "AGE > 18" },
                    { "expression": "SEX == \\"M\\"" }
                  ]
                }
                """;

        CheckCondition condition = MAPPER.readValue(json, CheckCondition.class);
        assertInstanceOf(CheckConditionAll.class, condition);
        CheckConditionAll all = (CheckConditionAll) condition;
        assertEquals(2, all.getConditions().size());

        CheckConditionExpression first = assertInstanceOf(CheckConditionExpression.class,
                all.getConditions().get(0));
        assertEquals("AGE > 18", first.source());
        assertNotNull(first.expr());
    }


    @Test
    void testNestedAnyInsideAll() throws Exception
    {
        String json = """
                {
                  "all": [
                    { "expression": "var_exists(\\"A\\")" },
                    {
                      "any": [
                        { "expression": "B == 1" },
                        { "expression": "C == 2" }
                      ]
                    }
                  ]
                }
                """;

        CheckCondition condition = MAPPER.readValue(json, CheckCondition.class);
        assertInstanceOf(CheckConditionAll.class, condition);
        CheckConditionAll all = (CheckConditionAll) condition;
        assertEquals(2, all.getConditions().size());
        assertInstanceOf(CheckConditionExpression.class, all.getConditions().get(0));
        assertInstanceOf(CheckConditionAny.class, all.getConditions().get(1));

        CheckConditionAny any = (CheckConditionAny) all.getConditions().get(1);
        assertEquals(2, any.getConditions().size());
    }


    @Test
    void testNotCondition() throws Exception
    {
        String json = """
                {
                  "not": { "expression": "FLAG == true" }
                }
                """;

        CheckCondition condition = MAPPER.readValue(json, CheckCondition.class);
        CheckConditionNot not = assertInstanceOf(CheckConditionNot.class, condition);
        assertInstanceOf(CheckConditionExpression.class, not.getCondition());
    }

    // ------------------------------------------------------------------
    // ⭐ D121b — the retired operator-leaf form fails LOUD, naming the fix
    // ------------------------------------------------------------------


    @Test
    void operatorLeafIsRejectedNamingTheRetiredForm()
    {
        String json = """
                {
                  "name": "AESER",
                  "operator": "is_not_contained_by",
                  "value": ["Y", "N"]
                }
                """;

        Exception ex = assertThrows(Exception.class,
                () -> MAPPER.readValue(json, CheckCondition.class));
        assertTrue(ex.getMessage().contains("operator-leaf Check form"),
                "the rejection must name the retired form: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("retired"),
                "the rejection must say the form is retired: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("expression"),
                "the rejection must point the author at `expression:`: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("is_not_contained_by"),
                "the rejection must echo the authored operator: " + ex.getMessage());
    }


    @Test
    void operatorLeafInsideCompositeIsRejected()
    {
        String json = """
                {
                  "all": [
                    { "expression": "AGE > 18" },
                    { "name": "SEX", "operator": "equal_to", "value": "M" }
                  ]
                }
                """;

        Exception ex = assertThrows(Exception.class,
                () -> MAPPER.readValue(json, CheckCondition.class));
        assertTrue(ex.getMessage().contains("operator-leaf Check form"),
                "a leaf nested in a composite must be rejected too: " + ex.getMessage());
    }


    @Test
    void unknownConditionShapeIsRejectedNotBoundAsAnEmptyLeaf()
    {
        // Pre-phase-7 this bound as an ALL-NULL leaf that loaded clean and checked nothing.
        String json = """
                { "nmae": "TYPO", "oeprator": "equal_to" }
                """;

        Exception ex = assertThrows(Exception.class,
                () -> MAPPER.readValue(json, CheckCondition.class));
        assertTrue(ex.getMessage().contains("not a recognised Check condition"),
                "an unknown shape must be rejected loudly: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("nmae"),
                "the rejection must list the keys it saw: " + ex.getMessage());
    }

    // ------------------------------------------------------------------
    // ⭐ D121 — malformed COMPOSITES fail loud too (H3, 2026-09-17). The composite
    // deserializer is the grammar's sole producer of empty `And`/`Or` (the expression
    // parser can never yield one), and `ExprCompiler.compileAnd` documents an empty
    // `And` as vacuous truth — so `{"all": <anything but a non-empty list>}` used to
    // load clean and FIRE ON EVERY ROW, from a natural YAML slip. Every row of the
    // probe table is pinned here, the correct spelling as the control (above,
    // testAllOverExpressions / ruleWithMappingAllFailsTheLoadLoudly's green twin).
    // ------------------------------------------------------------------


    @Test
    void allAsMappingInsteadOfListIsRejectedNamingTheListForm()
    {
        // The natural YAML slip: a single condition written as a mapping under `all:`.
        String json = """
                { "all": { "expression": "AETERM == \\"X\\"" } }
                """;

        Exception ex = assertThrows(Exception.class,
                () -> MAPPER.readValue(json, CheckCondition.class));
        assertTrue(ex.getMessage().contains("must be a LIST"),
                "the rejection must name the list requirement: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("all: [ { expression: ... } ]"),
                "the rejection must show the correct spelling: " + ex.getMessage());
    }


    @Test
    void allNullIsRejected()
    {
        String json = """
                { "all": null }
                """;

        Exception ex = assertThrows(Exception.class,
                () -> MAPPER.readValue(json, CheckCondition.class));
        assertTrue(ex.getMessage().contains("must be a LIST"),
                "`all: null` is not a condition list: " + ex.getMessage());
    }


    @Test
    void emptyAllIsRejectedAsVacuousTruth()
    {
        // Decided deliberately (D121): an empty conjunction fires on every row, which is never
        // what an author meant — measured zero users across all generated packages and fixtures.
        String json = """
                { "all": [] }
                """;

        Exception ex = assertThrows(Exception.class,
                () -> MAPPER.readValue(json, CheckCondition.class));
        assertTrue(ex.getMessage().contains("holds no conditions"),
                "an empty `all` must be rejected: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("fires on every row"),
                "the rejection must say WHY an empty `all` is dangerous: " + ex.getMessage());
    }


    @Test
    void emptyAnyIsRejectedAsInert()
    {
        String json = """
                { "any": [] }
                """;

        Exception ex = assertThrows(Exception.class,
                () -> MAPPER.readValue(json, CheckCondition.class));
        assertTrue(ex.getMessage().contains("holds no conditions"),
                "an empty `any` (silently inert) must be rejected: " + ex.getMessage());
    }


    @Test
    void allOfOnlyNullElementsIsRejectedLikeEmpty()
    {
        // Null ELEMENTS are dropped (deliberate, keeps downstream walkers null-free) — but a
        // composite that ends up empty after the drop lands in the same vacuous truth.
        String json = """
                { "all": [ null ] }
                """;

        Exception ex = assertThrows(Exception.class,
                () -> MAPPER.readValue(json, CheckCondition.class));
        assertTrue(ex.getMessage().contains("holds no conditions"),
                "`all: [null]` empties to the same vacuous truth: " + ex.getMessage());
    }


    @Test
    void notNullIsRejectedInsteadOfNpeingThePackage()
    {
        // `not: null` used to build CheckConditionNot(null), load clean, and NPE later in the
        // load pipeline — killing the whole package with an exception naming no rule.
        String json = """
                { "not": null }
                """;

        Exception ex = assertThrows(Exception.class,
                () -> MAPPER.readValue(json, CheckCondition.class));
        assertFalse(ex instanceof NullPointerException,
                "the rejection must be a grammar error, not an NPE: " + ex);
        assertTrue(ex.getMessage().contains("`not:` holds no condition"),
                "the rejection must name the empty `not`: " + ex.getMessage());
    }


    /** The package-level twin: the mapping slip fails the LOAD loudly, with the rule locatable. */
    @Test
    void ruleWithMappingAllFailsTheLoadLoudly()
    {
        String pkg = """
                {"rules":{"R1":{
                  "Core":{"Id":"R1"},
                  "Check":{"all":{"expression":"AETERM == \\"X\\""}},
                  "Outcome":{"Message":"m"}}}}
                """;

        Exception ex = assertThrows(Exception.class, () -> RulePackageLoader.loadFromString(pkg));
        assertTrue(ex.getMessage().contains("must be a LIST"),
                "the load failure must name the malformed composite: " + ex.getMessage());

        // The green twin — the same rule spelled correctly loads clean.
        String ok = """
                {"rules":{"R1":{
                  "Core":{"Id":"R1"},
                  "Check":{"all":[{"expression":"AETERM == \\"X\\""}]},
                  "Outcome":{"Message":"m"}}}}
                """;
        assertDoesNotThrow(() -> RulePackageLoader.loadFromString(ok),
                "the correct one-element list form is the control and must keep loading");
    }


    /** The package-level `not: null` twin: a load error naming the grammar, never an NPE. */
    @Test
    void ruleWithNullNotFailsTheLoadWithoutNpe()
    {
        String pkg = """
                {"rules":{"R1":{
                  "Core":{"Id":"R1"},
                  "Check":{"not":null},
                  "Outcome":{"Message":"m"}}}}
                """;

        Exception ex = assertThrows(Exception.class, () -> RulePackageLoader.loadFromString(pkg));
        assertFalse(ex instanceof NullPointerException,
                "the load failure must be the grammar rejection, not the downstream NPE: " + ex);
        assertTrue(ex.getMessage().contains("`not:` holds no condition"),
                "the load failure must name the empty `not`: " + ex.getMessage());
    }


    /**
     * ⭐ The rule-level pin: a PACKAGE carrying an operator-leaf {@code Check} fails its load loudly
     * — D121's "incorrect rules should fail loud on load / parse / compile and not need any
     * fallback", the same loudness an unparseable {@code expression:} has always had.
     */
    @Test
    void ruleWithOperatorLeafCheckFailsTheLoadLoudly()
    {
        String pkg = """
                {"rules":{"R1":{
                  "Core":{"Id":"R1"},
                  "Check":{"all":[{"name":"AETERM","operator":"empty"}]},
                  "Outcome":{"Message":"m"}}}}
                """;

        Exception ex = assertThrows(Exception.class, () -> RulePackageLoader.loadFromString(pkg));
        assertTrue(ex.getMessage().contains("operator-leaf Check form"),
                "the load failure must name the retired form: " + ex.getMessage());
        assertTrue(ex.getMessage().contains("expression"),
                "the load failure must point the author at `expression:`: " + ex.getMessage());
    }

}
