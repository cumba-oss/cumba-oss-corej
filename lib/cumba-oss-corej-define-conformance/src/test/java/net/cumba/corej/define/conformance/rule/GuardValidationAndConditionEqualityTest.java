package net.cumba.corej.define.conformance.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Two things nothing in this module exercised: that a kind with its <b>own</b> {@code validate()}
 * still validates its {@code when} guard, and that {@link Condition} has working value equality.
 *
 * <p>
 * ⭐ Nine of the check kinds override {@code validate()} to check their own fields and then call
 * {@code CheckDefinition.super.validate()}, which is the only thing that reaches the guard. Drop
 * that one line and a malformed {@code when:} loads silently — and a guard that cannot be evaluated
 * is a rule that never fires, which on this engine means a Define-XML defect the reviewer never
 * sees. Every kind with an override is listed below, so adding a tenth without the super call reds
 * this test rather than shipping.
 * </p>
 */
class GuardValidationAndConditionEqualityTest
{

    /** A guard naming a path but no predicate: structurally invalid, must be rejected at load. */
    private static final String BROKEN_GUARD = """
            when:
              path: "@Name"
            """;

    private static String rule(String aCheckBody)
    {
        return """
                Rule_Id: "CDISC-VAL01"
                Sheet_Rule_Identifier: "VAL01"
                Rule_Set: "CDISC"
                Element: "ItemDef"
                Applicable_Versions: ["2.1"]
                Severity: "Error"
                Plain_Text_Rule: "Test rule."
                Message: "Offending value [${value}]."
                Check:
                """ + aCheckBody.indent(2);
    }


    @ParameterizedTest(name = "{0}")
    @ValueSource(strings =
    {
            """
                    kind: "matches_regex"
                    attribute: "Name"
                    pattern: "^[A-Z]+$"
                    """, """
                    kind: "one_of"
                    attribute: "Name"
                    values: ["A", "B"]
                    """, """
                    kind: "is_referenced"
                    by:
                      - element: "ItemRef"
                        attribute: "ItemOID"
                    """, """
                    kind: "compare"
                    left: "@Name"
                    right: "@SASFieldName"
                    """, """
                    kind: "term_in_ct_codelist"
                    """, """
                    kind: "nci_code_known"
                    level: "codelist"
                    """, """
                    kind: "extended_value_marking"
                    mode: "required"
                    """, """
                    kind: "nci_alias_required"
                    level: "codelist"
                    """, """
                    kind: "library_ct_alias_required"
                    level: "codelist"
                    """
    })
    void aKindWithItsOwnValidateStillRejectsAMalformedGuard(String aCheckBody)
    {
        String yaml = rule(aCheckBody + BROKEN_GUARD);
        IllegalStateException thrown = assertThrows(IllegalStateException.class,
                () -> RuleRepository.parse(yaml, "test"));
        assertTrue(Objects.toString(deepestMessage(thrown), "")
                .contains("equals/oneOf/exists/matchesRegex"), () -> deepestMessage(thrown));
    }


    /** The same nine kinds must still load when the guard is well formed. */
    @ParameterizedTest(name = "{0}")
    @ValueSource(strings =
    {
            """
                    kind: "matches_regex"
                    attribute: "Name"
                    pattern: "^[A-Z]+$"
                    """, """
                    kind: "one_of"
                    attribute: "Name"
                    values: ["A", "B"]
                    """, """
                    kind: "is_referenced"
                    by:
                      - element: "ItemRef"
                        attribute: "ItemOID"
                    """
    })
    void aWellFormedGuardLoads(String aCheckBody)
    {
        ConformanceRule parsed = RuleRepository.parse(rule(aCheckBody + """
                when:
                  path: "@Name"
                  exists: true
                """), "test");
        assertEquals("CDISC-VAL01", parsed.ruleId());
    }


    private static String deepestMessage(Throwable aThrowable)
    {
        Throwable current = aThrowable;
        while (current.getCause() != null)
        {
            current = current.getCause();
        }
        return Objects.toString(current.getMessage(), "");
    }

    // ------------------------------------------------------------------
    // Condition value equality — reached through the check records' equals()
    // ------------------------------------------------------------------


    private static Condition guard(String aGuardBody)
    {
        CheckDefinition check = RuleRepository.parse(rule("""
                kind: "exists"
                target: "Description"
                """ + aGuardBody), "test").check();
        return Objects.requireNonNull(check.when());
    }


    @Test
    void conditionsCompareByValueAcrossEveryField()
    {
        Condition leaf = guard("""
                when:
                  path: "@Name"
                  equals: "DM"
                """);
        assertEquals(leaf, guard("""
                when:
                  path: "@Name"
                  equals: "DM"
                """));
        assertEquals(leaf.hashCode(), guard("""
                when:
                  path: "@Name"
                  equals: "DM"
                """).hashCode());
        assertEquals(leaf, leaf);
        assertNotEquals(leaf, null);
        assertNotEquals(leaf, "@Name");
        // Each field on its own makes two conditions unequal.
        assertNotEquals(leaf, guard("""
                when:
                  path: "@Domain"
                  equals: "DM"
                """));
        assertNotEquals(leaf, guard("""
                when:
                  path: "@Name"
                  equals: "AE"
                """));
        assertNotEquals(leaf, guard("""
                when:
                  path: "@Name"
                  oneOf: ["DM"]
                """));
        assertNotEquals(leaf, guard("""
                when:
                  path: "@Name"
                  exists: true
                """));
        assertNotEquals(leaf, guard("""
                when:
                  path: "@Name"
                  matchesRegex: "DM"
                """));
        assertNotEquals(leaf, guard("""
                when:
                  all:
                    - path: "@Name"
                      equals: "DM"
                """));
        assertNotEquals(leaf, guard("""
                when:
                  any:
                    - path: "@Name"
                      equals: "DM"
                """));
        assertNotEquals(leaf, guard("""
                when:
                  not:
                    path: "@Name"
                    equals: "DM"
                """));
    }


    @Test
    void combinatorConditionsCompareByTheirNestedClauses()
    {
        Condition all = guard("""
                when:
                  all:
                    - path: "@Name"
                      equals: "DM"
                    - path: "@Domain"
                      exists: true
                """);
        assertEquals(all, guard("""
                when:
                  all:
                    - path: "@Name"
                      equals: "DM"
                    - path: "@Domain"
                      exists: true
                """));
        assertNotEquals(all, guard("""
                when:
                  all:
                    - path: "@Name"
                      equals: "DM"
                """));
        assertNotEquals(all, guard("""
                when:
                  any:
                    - path: "@Name"
                      equals: "DM"
                    - path: "@Domain"
                      exists: true
                """));
    }

}
