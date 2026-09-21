package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.ExpansionSource;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * Fix #147 — load-time validation of the {@code Expansion:} block.
 *
 * <p>
 * Every shape rejected here fails <b>silently</b> otherwise: the rule loads, the gate stays green
 * and the check tests nothing. That is precisely how {@code CDISC-AD0591} and {@code CDISC-AD0898}
 * shipped as no-ops, so the whole mechanism is built to fail loudly instead.
 * </p>
 */
class RuleLoadValidationExpansionTest
{

    private static String packageOf(String ruleJson)
    {
        return "{\"rules\":{\"rule-1\":" + ruleJson + "}}";
    }


    private static Rule load(String ruleJson) throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleJson));
        return pkg.getRules().values().iterator().next();
    }


    private static String errorOf(String ruleJson) throws IOException
    {
        String error = load(ruleJson).getLoadError();
        assertNotNull(error, "this Expansion shape must be rejected at load");
        return error;
    }


    @Test
    void aWellFormedBlockLoadsCleanAndBindsTheTypedSource() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-146-OK"},
                  "Expansion": [{"token": "&VAR", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(`&VAR`)"}]}
                }
                """);
        assertNull(rule.getLoadError());
        assertNotNull(rule.getExpansion());
        assertEquals(1, rule.getExpansion().size());
        assertEquals(ExpansionSource.SHARED_VARIABLES, rule.getExpansion().get(0).getOver());
        assertEquals("&VAR", rule.getExpansion().get(0).getToken());
    }


    @Test
    void anUnknownOverValueIsRejected() throws IOException
    {
        // Silently dropping the directive would leave '&VAR' unsubstituted, and the rule would
        // then test a column that cannot exist.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-OVER"},
                  "Expansion": [{"token": "&VAR", "over": "each_full_moon", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(`&VAR`)"}]}
                }
                """);
        assertTrue(error.contains("invalid 'over' value 'each_full_moon'"), error);
        assertTrue(error.contains("shared_variables"), "the message must list the valid values");
    }


    @Test
    void aSigilFreeTokenIsRejected() throws IOException
    {
        // 'VAR' is drawn from the CDISC name alphabet, so substituting it would also rewrite the
        // 'VAR' inside a real column such as VARNAME.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-SIGIL"},
                  "Expansion": [{"token": "VAR", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(VAR)"}]}
                }
                """);
        assertTrue(error.contains("carries no sigil"), error);
    }


    @Test
    void aTokenContainedInAnotherTokenIsRejected() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-OVERLAP"},
                  "Expansion": [
                    {"token": "&D", "over": "domain_from_variable", "pattern": "&DSEQ"},
                    {"token": "&DS", "over": "domain_from_variable", "pattern": "&DSX"}
                  ],
                  "Check": {"all": [{"expression": "not empty(`&DSEQ`)"}]}
                }
                """);
        assertTrue(error.contains("occurs inside token"), error);
    }


    @Test
    void aTokenInAVariableRequirementIsRejected() throws IOException
    {
        // The requirement gate runs BEFORE expansion (DatasetRuleResolver: describeScopeSkip, then
        // tryExpand), so it would match '&VAR' literally, skip the rule for every dataset, and the
        // template would never expand. That is how 25 CDISC-AD rules were silently always-skipped
        // once. ⚠ The bar re-pointed onto Requirements.Variables when Scope.Variables retired
        // (PLAN-scope-requirements-split phase 5); nothing about it was ever specific to Scope.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-SCOPE"},
                  "Requirements": {"Variables": {"All": ["&VAR"]}},
                  "Expansion": [{"token": "&VAR", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(`&VAR`)"}]}
                }
                """);
        assertTrue(error.contains("must not appear in Requirements.Variables.All"), error);
        assertTrue(error.contains("before expansion"), error);
    }


    @Test
    void mixingAnExpansionTokenWithEngineOwnedWildcardMarkersIsRejected() throws IOException
    {
        // The two expansions are independent walks; running one and silently ignoring the other
        // would leave half the template unresolved.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-MIX"},
                  "Expansion": [{"token": "&VAR", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [
                    {"expression": "not empty(`&VAR`)"},
                    {"expression": "var_exists(\\"TRTxxP\\")"}
                  ]}
                }
                """);
        assertTrue(error.contains("cannot be combined with the engine-owned wildcard markers"),
                error);
    }


    @Test
    void aTokenContainingTheDomainPrefixWildcardIsRejected() throws IOException
    {
        // '--' IS sigil-bearing, so the sigil check lets it through — but it already means "the
        // caller-supplied domain code" (EC-36 / Fix #125). A token containing it would make
        // substitution do a blind String.replace("--", ...) across the whole rule body.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-DASHDASH"},
                  "Expansion": [
                    {"token": "--D", "over": "domain_from_variable", "pattern": "--DSEQ"}
                  ],
                  "Check": {"all": [{"expression": "not empty(--DSEQ)"}]}
                }
                """);
        assertTrue(error.contains("contains '--'"), error);
        assertTrue(error.contains("EC-36"), error);
    }


    @Test
    void mixingAnExpansionWithTheWildcardMechanismDirectivesIsRejected() throws IOException
    {
        // DatasetRuleResolver.applyTemplatePostFilters derives the "expanded column" by cutting the
        // id
        // after the base id, which is wrong for a multi-directive token expansion.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-WCDIR"},
                  "wildcardExclude": ["TRTPN"],
                  "skipIfLibraryDefined": true,
                  "Expansion": [{"token": "&VAR", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(`&VAR`)"}]}
                }
                """);
        assertTrue(error.contains("wildcard-mechanism directives"), error);
        assertTrue(error.contains("wildcardExclude"), error);
        assertTrue(error.contains("skipIfLibraryDefined"), error);
    }


    @Test
    void sharedVariablesWithoutAWithDatasetIsRejected() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-WITH"},
                  "Expansion": [{"token": "&VAR", "over": "shared_variables"}],
                  "Check": {"all": [{"expression": "not empty(`&VAR`)"}]}
                }
                """);
        assertTrue(error.contains("requires a 'with' dataset name"), error);
    }


    @Test
    void domainFromVariableWithoutAPatternIsRejected() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-PAT"},
                  "Expansion": [{"token": "&DOM", "over": "domain_from_variable"}],
                  "Check": {"all": [{"expression": "not empty(`&DOMSEQ`)"}]}
                }
                """);
        assertTrue(error.contains("requires a 'pattern'"), error);
    }


    @Test
    void aPatternNotContainingItsTokenIsRejected() throws IOException
    {
        // Nothing would be captured, so every candidate would silently fail to match.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-NOCAP"},
                  "Expansion": [
                    {"token": "&DOM", "over": "domain_from_variable", "pattern": "SOMESEQ"}
                  ],
                  "Check": {"all": [{"expression": "not empty(`&DOMSEQ`)"}]}
                }
                """);
        assertTrue(error.contains("does not contain its token"), error);
    }


    @Test
    void anExpansionBlockWithoutACheckIsRejected() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-NOCHECK"},
                  "Expansion": [{"token": "&VAR", "over": "shared_variables", "with": "ADSL"}]
                }
                """);
        assertTrue(error.contains("no Check tree"), error);
    }


    @Test
    void aTokenlessEntryIsRejected() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-NOTOKEN"},
                  "Expansion": [{"over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(AGE)"}]}
                }
                """);
        assertTrue(error.contains("no 'token'"), error);
    }


    @Test
    void aRuleWithNoExpansionBlockIsUntouched() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-146-NONE"},
                  "Check": {"all": [{"expression": "var_exists(\\"TRTxxP\\")"}]}
                }
                """);
        assertNull(rule.getLoadError(),
                "the wildcard-combination bar must not fire on a rule with no Expansion block");
        assertNull(rule.getExpansion());
    }

    // ------------------------------------------------------------------
    // The all_* sources take no selector
    // ------------------------------------------------------------------


    /**
     * ⛔⛔ These three arms are guarded by <b>this test and nothing else</b>.
     * {@code validateExpansionDirective}'s {@code switch (over)} is a switch <b>statement</b> with
     * arrow arms, no {@code default} and no patterns, so it is NOT exhaustiveness-checked: a new
     * {@link net.cumba.corej.core.model.ExpansionSource} compiles clean there with no arm and no
     * warning, and a stray selector on it would then be read by nothing. The author's intended
     * narrowing would vanish silently, which is the exact failure the rejection exists to prevent.
     */
    @Test
    void anAllVariablesDirectiveRejectsAWithDataset() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-ALLVARS-WITH"},
                  "Expansion": [{"token": "&VAR", "over": "all_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(`&VAR`)"}]}
                }
                """);
        assertTrue(error.contains("does not take a 'with'"), error);
    }


    @Test
    void anAllNumericDirectiveRejectsAPattern() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-ALLNUM-PATTERN"},
                  "Expansion": [
                    {"token": "&VAR", "over": "all_numeric_variables", "pattern": "&VARSEQ"}
                  ],
                  "Check": {"all": [{"expression": "not empty(`&VAR`)"}]}
                }
                """);
        assertTrue(error.contains("does not take a 'pattern'"), error);
    }


    @Test
    void anAllCharacterDirectiveRejectsKnownDomainOnly() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-ALLCHAR-KDO"},
                  "Expansion": [
                    {"token": "&VAR", "over": "all_character_variables",
                     "known_domain_only": true}
                  ],
                  "Check": {"all": [{"expression": "not empty(`&VAR`)"}]}
                }
                """);
        assertTrue(error.contains("does not take 'known_domain_only'"), error);
    }


    /** The sensitivity arm: a well-formed all_* directive must NOT be rejected. */
    @Test
    void aBareAllVariablesDirectiveLoadsCleanly() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-ALLVARS-OK"},
                  "Expansion": [{"token": "&VAR", "over": "all_variables"}],
                  "Check": {"all": [{"expression": "not empty(`&VAR`)"}]}
                }
                """);
        assertNull(rule.getLoadError(), () -> "unexpected load error: " + rule.getLoadError());
        assertNotNull(rule.getExpansion());
    }

    // ------------------------------------------------------------------
    // G3 — an all_* expansion and the variable cursor are mutually exclusive
    // ------------------------------------------------------------------


    /**
     * ⛔⛔ The defect review round 1 caught, pinned. This Check contains neither {@code varname()}
     * nor {@code value()}, so a name-scanning guard passes it — yet {@code var_label("DATA")} is
     * the arity-1 form whose name defaults to the cursor, so each of the N minted rules would still
     * iterate every variable. The guard therefore keys on the evaluation DOMAIN.
     */
    @Test
    void anAllVariablesRuleWhoseCheckReadsTheCursorIsRejected() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-G3-ARITY1"},
                  "Expansion": [{"token": "&VAR", "over": "all_variables"}],
                  "Check": {"all": [
                    {"expression": "var_label(\\"DATA\\") != var_label(\\"LIBRARY\\")"}
                  ]}
                }
                """);
        assertTrue(error.contains("also reads the variable cursor"), error);
    }


    /** The spelling a name scan WOULD have caught — still rejected, for the same reason. */
    @Test
    void anAllNumericRuleUsingVarnameIsRejected() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-G3-VARNAME"},
                  "Expansion": [{"token": "&VAR", "over": "all_numeric_variables"}],
                  "Check": {"all": [{"expression": "ends_with(varname(), \\"DTC\\")"}]}
                }
                """);
        assertTrue(error.contains("also reads the variable cursor"), error);
    }


    /**
     * ⭐ The sensitivity arm. Without it the two tests above would still pass if the guard rejected
     * <em>every</em> {@code all_*} rule, which would make the feature unusable while looking
     * correct.
     */
    @Test
    void anAllVariablesRuleWithNoCursorReadLoadsCleanly() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-G3-OK"},
                  "Expansion": [{"token": "&VAR", "over": "all_variables"}],
                  "Check": {"all": [
                    {"expression": "var_label(\\"&VAR\\", \\"DATA\\") != \\"\\""}
                  ]}
                }
                """);
        assertNull(rule.getLoadError(), () -> "unexpected load error: " + rule.getLoadError());
    }


    /**
     * The guard must not fire on the two shipped {@code Expansion:} sources — they are not
     * {@code all_*}, and a cursor read alongside them was always legal.
     */
    @Test
    void aSharedVariablesRuleReadingTheCursorIsNotRejected() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-G3-SHARED"},
                  "Expansion": [{"token": "&VAR", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "ends_with(varname(), \\"DTC\\")"}]}
                }
                """);
        assertNull(rule.getLoadError(), () -> "unexpected load error: " + rule.getLoadError());
    }

}
