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
                  "Check": {"all": [{"name": "&VAR", "operator": "non_empty"}]}
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
                  "Check": {"all": [{"name": "&VAR", "operator": "non_empty"}]}
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
                  "Check": {"all": [{"name": "VAR", "operator": "non_empty"}]}
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
                  "Check": {"all": [{"name": "&DSEQ", "operator": "non_empty"}]}
                }
                """);
        assertTrue(error.contains("occurs inside token"), error);
    }


    @Test
    void aTokenInAVariableRequirementIsRejected() throws IOException
    {
        // The requirement gate runs BEFORE expansion (RuleGenerator: describeScopeSkip, then
        // tryExpand), so it would match '&VAR' literally, skip the rule for every dataset, and the
        // template would never expand. That is how 25 CDISC-AD rules were silently always-skipped
        // once. ⚠ The bar re-pointed onto Requirements.Variables when Scope.Variables retired
        // (PLAN-scope-requirements-split phase 5); nothing about it was ever specific to Scope.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-SCOPE"},
                  "Requirements": {"Variables": {"All": ["&VAR"]}},
                  "Expansion": [{"token": "&VAR", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"name": "&VAR", "operator": "non_empty"}]}
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
                    {"name": "&VAR", "operator": "non_empty"},
                    {"name": "TRTxxP", "operator": "var_exists"}
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
                  "Check": {"all": [{"name": "--DSEQ", "operator": "non_empty"}]}
                }
                """);
        assertTrue(error.contains("contains '--'"), error);
        assertTrue(error.contains("EC-36"), error);
    }


    @Test
    void mixingAnExpansionWithTheWildcardMechanismDirectivesIsRejected() throws IOException
    {
        // RuleGenerator.applyTemplatePostFilters derives the "expanded column" by cutting the id
        // after the base id, which is wrong for a multi-directive token expansion.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-WCDIR"},
                  "wildcardExclude": ["TRTPN"],
                  "skipIfLibraryDefined": true,
                  "Expansion": [{"token": "&VAR", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"name": "&VAR", "operator": "non_empty"}]}
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
                  "Check": {"all": [{"name": "&VAR", "operator": "non_empty"}]}
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
                  "Check": {"all": [{"name": "&DOMSEQ", "operator": "non_empty"}]}
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
                  "Check": {"all": [{"name": "&DOMSEQ", "operator": "non_empty"}]}
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
                  "Check": {"all": [{"name": "AGE", "operator": "non_empty"}]}
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
                  "Check": {"all": [{"name": "TRTxxP", "operator": "var_exists"}]}
                }
                """);
        assertNull(rule.getLoadError(),
                "the wildcard-combination bar must not fire on a rule with no Expansion block");
        assertNull(rule.getExpansion());
    }

}
