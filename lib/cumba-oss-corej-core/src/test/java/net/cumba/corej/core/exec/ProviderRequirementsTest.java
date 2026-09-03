package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ProviderRequirements} — the DERIVED {@code Library} / {@code Define} / {@code Dictionary}
 * dependencies behind loader gate R5 ({@code plans/PLAN-scope-requirements-split.md} &#167;4.5).
 *
 * <p>
 * ⚠⚠ The single most likely way to get &#167;4.5 wrong is to read <b>one</b> surface. Each provider
 * has more than one, and a derivation that misses one <em>reds rules that are correct</em>. This
 * class drives each surface separately, and then the union, so a regression on any one of them is
 * attributable.
 * </p>
 */
class ProviderRequirementsTest
{

    private static Rule load(String ruleBody) throws IOException
    {
        Rule rule = RulePackageLoader
                .loadFromString(
                        "{\"rules\":{\"R1\":{\"Core\":{\"Id\":\"TEST-PR\"}," + ruleBody + "}}}")
                .getRules().get("R1");
        assertNotNull(rule);
        return rule;
    }


    private static ProviderRequirements of(String ruleBody) throws IOException
    {
        return ProviderRequirements.of(load(ruleBody));
    }


    @Test
    @DisplayName("a rule needing nothing derives all three false")
    void noProviderNeeded() throws IOException
    {
        ProviderRequirements derived = of(
                "\"Check\":{\"all\":[{\"name\":\"AESEV\",\"operator\":\"var_exists\"}]}");
        assertEquals(new ProviderRequirements(false, false, false), derived);
    }


    @Test
    @DisplayName("surface 1 — a declared library / define / dictionary Operation")
    void declaredOperations() throws IOException
    {
        assertTrue(of("\"Operations\":[{\"id\":\"$r\",\"operator\":\"required_variables\"}],"
                + "\"Check\":{\"all\":[{\"name\":\"$r\",\"operator\":\"empty\"}]}").library());
        assertTrue(of("\"Operations\":[{\"id\":\"$d\",\"operator\":\"define_variable_names\"}],"
                + "\"Check\":{\"all\":[{\"name\":\"$d\",\"operator\":\"empty\"}]}").define());
        assertTrue(of("\"Operations\":[{\"id\":\"$x\","
                + "\"operator\":\"valid_external_dictionary_code\","
                + "\"external_dictionary_type\":\"meddra\",\"name\":\"AEDECOD\"}],"
                + "\"Check\":{\"all\":[{\"name\":\"$x\",\"operator\":\"equal_to\","
                + "\"value\":false}]}").dictionary());
    }


    /**
     * ⚠ {@code DICTIONARY_AVAILABLE} is the availability <b>gate</b>, not a dependency: it returns
     * a well-defined {@code false} with no dictionary loaded, which is exactly why
     * {@code RuleRunner}'s eager arm excludes it. Counting it would make every gated rule declare a
     * dependency it does not have.
     */
    @Test
    @DisplayName("⚠ dictionary_available is the GATE, never a dependency")
    void dictionaryAvailableIsNotADependency() throws IOException
    {
        Rule rule = load("\"Operations\":[{\"id\":\"$a\",\"operator\":\"dictionary_available\","
                + "\"external_dictionary_type\":\"meddra\"}],"
                + "\"Check\":{\"all\":[{\"name\":\"$a\",\"operator\":\"equal_to\","
                + "\"value\":true}]}");
        assertFalse(ProviderRequirements.of(rule).dictionary());
    }


    @Test
    @DisplayName("surface 2 — a bare library_* / define_* operand with NO Operations entry")
    void operandPrefixSurface() throws IOException
    {
        assertTrue(
                of("\"Check\":{\"all\":[{\"name\":\"library_variable_role\","
                        + "\"operator\":\"equal_to\",\"value\":\"Topic\"}]}").library(),
                "CORE-001081's shape: the dependency exists with no Operations entry at all");
        assertTrue(of("\"Check\":{\"all\":[{\"name\":\"define_variable_name\","
                + "\"operator\":\"non_empty\"}]}").define());
    }


    /**
     * Surface 3 — an <b>inlined</b> operation call in the Check expression, which the loader gates
     * with an injected {@code Precondition} rather than an {@code Operations} entry.
     *
     * <p>
     * ⚠ This surface has <b>zero carriers in the shipped corpus</b>
     * ({@code CrossCorpusDerivationTest} asserts zero injected gates corpus-wide), so it can only
     * ever be exercised by a hand-authored fixture. It is implemented and tested anyway because
     * gate R5 compares an authored declaration against this derivation: a rule that inlines a
     * library call and honestly declares {@code Library: true} must not be rejected.
     * </p>
     */
    @Test
    @DisplayName("surface 3 — an INLINED operation call")
    void inlinedCallSurface() throws IOException
    {
        ProviderRequirements lib = of("\"Check\":{\"expression\":\"domain_is_custom() == false\"}");
        assertTrue(lib.library(), "domain_is_custom is library-dependent and is inlined here");

        ProviderRequirements dict = of("\"Check\":{\"expression\":"
                + "\"valid_external_dictionary_value(AEDECOD, dictionary_term_type=\\\"PT\\\","
                + " external_dictionary_type=\\\"meddra\\\") == false\"}");
        assertTrue(dict.dictionary());
        assertFalse(dict.library());
    }


    @Test
    @DisplayName("the walk reaches nested positions — and, or, not, comparison operands")
    void theWalkReachesNestedPositions() throws IOException
    {
        assertTrue(
                of("\"Check\":{\"expression\":"
                        + "\"not (empty(AETERM) or domain_is_custom() == false)\"}").library(),
                "a call under not(or(...)) must still be seen");
        assertTrue(of("\"Check\":{\"expression\":"
                + "\"empty(AETERM) and (domain_is_custom() == false)\"}").library());
    }


    @Test
    @DisplayName("a non-dependent inline call needs nothing")
    void nonDependentInlineCall() throws IOException
    {
        assertEquals(new ProviderRequirements(false, false, false),
                of("\"Check\":{\"expression\":\"record_count() == 0\"}"));
    }


    @Test
    @DisplayName("the surfaces UNION — one rule can need two providers by two routes")
    void surfacesUnion() throws IOException
    {
        ProviderRequirements derived = of(
                "\"Operations\":[{\"id\":\"$d\",\"operator\":\"define_variable_names\"}],"
                        + "\"Check\":{\"all\":[{\"name\":\"library_variable_role\","
                        + "\"operator\":\"equal_to\",\"value\":\"$d\"}]}");
        assertTrue(derived.define(), "from the declared Operation");
        assertTrue(derived.library(), "from the bare operand prefix");
        assertFalse(derived.dictionary());
    }


    @Test
    @DisplayName("a Check with no expression surface derives from the Operations alone")
    void unraisableCheckStillDerivesFromOperations() throws IOException
    {
        Rule rule = load("\"Operations\":[{\"id\":\"$r\",\"operator\":\"required_variables\"}],"
                + "\"Check\":{\"all\":[{\"name\":\"$r\",\"operator\":\"empty\"}]}");
        rule.setCheckExpr(null);
        assertTrue(ProviderRequirements.of(rule).library());
    }


    @Test
    @DisplayName("a rule with no Check and no Operations at all is handled")
    void emptyRule()
    {
        assertEquals(new ProviderRequirements(false, false, false),
                ProviderRequirements.of(new Rule()));
    }

}
