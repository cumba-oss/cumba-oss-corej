package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
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
 *
 * <p>
 * {@code PLAN-expansion-token-delimiters}: a token is {@code &NAME&} with
 * {@code NAME = [A-Z][A-Z0-9]*}, written bare in a Check. Gate <b>G1</b> pins the token's form,
 * <b>G2</b> rejects an undeclared complete token on every rule, <b>G3</b> rejects the old
 * undelimited spelling anywhere in an Expansion rule's rewritten surfaces.
 * </p>
 */
class RuleLoadValidationExpansionTest
{

    private static final String G1 = "must have the form &NAME& with NAME = [A-Z][A-Z0-9]*";

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
                  "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(&VAR&)"}]}
                }
                """);
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertNotNull(rule.getExpansion());
        assertEquals(1, rule.getExpansion().size());
        assertEquals(ExpansionSource.SHARED_VARIABLES, rule.getExpansion().get(0).getOver());
        assertEquals("&VAR&", rule.getExpansion().get(0).getToken());
    }


    @Test
    void theBacktickFormOfATokenStillLoads() throws IOException
    {
        // S8: backticks are generally needed for names with spaces, so the form stays valid.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-146-BT"},
                  "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(`&VAR&`)"}]}
                }
                """);
        assertNull(rule.getLoadError(), rule.getLoadError());
    }


    @Test
    void anUnknownOverValueIsRejected() throws IOException
    {
        // Silently dropping the directive would leave '&VAR&' unsubstituted, and the rule would
        // then test a column that cannot exist.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-OVER"},
                  "Expansion": [{"token": "&VAR&", "over": "each_full_moon", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(&VAR&)"}]}
                }
                """);
        assertTrue(error.contains("invalid 'over' value 'each_full_moon'"), error);
        assertTrue(error.contains("shared_variables"), "the message must list the valid values");
    }

    // ------------------------------------------------------------------
    // G1 — the token's form
    // ------------------------------------------------------------------


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
        assertTrue(error.contains("Expansion token 'VAR' " + G1), error);
        assertTrue(error.contains("(e.g. '&DOM&')"), error);
    }


    @Test
    void anUndelimitedOrMisspelledTokenIsRejected() throws IOException
    {
        // The old spelling, a lower-case name, a missing opening '&', a '_' in the name.
        for (String token : List.of("&DOM", "&dom&", "DOM&", "&D_M&", "&&", "&1&"))
        {
            String error = errorOf("""
                    {
                      "Core": {"Id": "TEST-146-FORM"},
                      "Expansion": [{"token": "%s", "over": "shared_variables", "with": "ADSL"}],
                      "Check": {"all": [{"expression": "not empty(AGE)"}]}
                    }
                    """.formatted(token));
            assertTrue(error.contains("Expansion token '" + token + "' " + G1),
                    token + ": " + error);
        }
    }


    @Test
    void aTokenContainingTheDomainPrefixWildcardIsRejectedByTheFormGate() throws IOException
    {
        // R-5.20 is subsumed by R-5.17: '--D' cannot match &NAME&, so the former dedicated
        // "contains '--'" check is gone.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-DASHDASH"},
                  "Expansion": [
                    {"token": "--D", "over": "domain_from_variable", "pattern": "--DSEQ"}
                  ],
                  "Check": {"all": [{"expression": "not empty(--DSEQ)"}]}
                }
                """);
        assertTrue(error.contains("Expansion token '--D' " + G1), error);
    }


    @Test
    void aTokenDeclaredTwiceIsRejected() throws IOException
    {
        // Two directives with the same token: substitutions.put would keep one binding while the
        // expanded id carries both suffixes (review E-M2).
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-TWICE"},
                  "Expansion": [
                    {"token": "&V&", "over": "shared_variables", "with": "ADSL"},
                    {"token": "&V&", "over": "shared_variables", "with": "ADAE"}
                  ],
                  "Check": {"all": [{"expression": "not empty(&V&)"}]}
                }
                """);
        assertTrue(error.contains("Expansion token '&V&' is declared twice"), error);
    }


    @Test
    void twoDistinctTokensAreNotAnOverlap() throws IOException
    {
        // The retired "no token is a prefix of another" check rejected `&D` / `&DS`; delimited
        // tokens cannot contain each other, so `&D&` beside `&DS&` is a legal pair.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-146-PAIR"},
                  "Expansion": [
                    {"token": "&D&", "over": "domain_from_variable", "pattern": "&D&SEQ"},
                    {"token": "&DS&", "over": "shared_variables", "with": "ADSL"}
                  ],
                  "Check": {"all": [{"expression": "not empty(&D&SEQ) and not empty(&DS&)"}]}
                }
                """);
        assertNull(rule.getLoadError(), rule.getLoadError());
    }


    @Test
    void aTokenInAVariableRequirementIsRejected() throws IOException
    {
        // The requirement gate runs BEFORE expansion (DatasetRuleResolver: describeScopeSkip, then
        // tryExpand), so it would match '&VAR&' literally, skip the rule for every dataset, and
        // the template would never expand. That is how 25 CDISC-AD rules were silently
        // always-skipped once. ⚠ The bar re-pointed onto Requirements.Variables when
        // Scope.Variables retired (PLAN-scope-requirements-split phase 5); nothing about it was
        // ever specific to Scope.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-SCOPE"},
                  "Requirements": {"Variables": {"All": ["&VAR&"]}},
                  "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(&VAR&)"}]}
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
                  "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [
                    {"expression": "not empty(&VAR&)"},
                    {"expression": "var_exists(\\"TRTxxP\\")"}
                  ]}
                }
                """);
        assertTrue(error.contains("cannot be combined with the engine-owned wildcard markers"),
                error);
    }


    @Test
    void mixingAnExpansionWithTheWildcardMechanismDirectivesIsRejected() throws IOException
    {
        // DatasetRuleResolver.applyTemplatePostFilters derives the "expanded column" by cutting the
        // id after the base id, which is wrong for a multi-directive token expansion.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-WCDIR"},
                  "wildcardExclude": ["TRTPN"],
                  "skipIfLibraryDefined": true,
                  "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(&VAR&)"}]}
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
                  "Expansion": [{"token": "&VAR&", "over": "shared_variables"}],
                  "Check": {"all": [{"expression": "not empty(&VAR&)"}]}
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
                  "Expansion": [{"token": "&DOM&", "over": "domain_from_variable"}],
                  "Check": {"all": [{"expression": "not empty(&DOM&SEQ)"}]}
                }
                """);
        assertTrue(error.contains("requires a 'pattern'"), error);
    }


    @Test
    void aPatternMustContainItsTokenExactlyOnce() throws IOException
    {
        // Nothing would be captured with zero occurrences; with two, bindDomainFromVariable's
        // indexOf would silently capture against the first only.
        String none = errorOf("""
                {
                  "Core": {"Id": "TEST-146-NOCAP"},
                  "Expansion": [
                    {"token": "&DOM&", "over": "domain_from_variable", "pattern": "SOMESEQ"}
                  ],
                  "Check": {"all": [{"expression": "not empty(&DOM&SEQ)"}]}
                }
                """);
        assertTrue(none.contains(
                "Expansion pattern 'SOMESEQ' must contain its token '&DOM&' exactly once (found 0)"),
                none);
        String twice = errorOf("""
                {
                  "Core": {"Id": "TEST-146-TWOCAP"},
                  "Expansion": [
                    {"token": "&DOM&", "over": "domain_from_variable", "pattern": "&DOM&X&DOM&"}
                  ],
                  "Check": {"all": [{"expression": "not empty(&DOM&SEQ)"}]}
                }
                """);
        assertTrue(twice.contains("exactly once (found 2)"), twice);
    }


    @Test
    void anExpansionBlockWithoutACheckIsRejected() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-146-NOCHECK"},
                  "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}]
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
    // G2 — an undeclared complete token, on EVERY rule
    // ------------------------------------------------------------------


    @Test
    void anUndeclaredTokenInTheCheckOfARuleWithoutExpansionIsRejected() throws IOException
    {
        // Without G2 the rule would reach the run as a column literally named `&X&`, which the
        // absent-column doctrine evaluates silently.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-G2-NOEXP"},
                  "Check": {"all": [{"expression": "not empty(&X&)"}]}
                }
                """);
        assertTrue(error.contains(
                "undeclared expansion token '&X&' in Check (declare it in Expansion:, or remove it)"),
                error);
    }


    @Test
    void anUndeclaredTokenInAStringLiteralIsRejected() throws IOException
    {
        String error = errorOf(
                """
                        {
                          "Core": {"Id": "TEST-G2-LIT"},
                          "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}],
                          "Check": {"all": [
                            {"expression": "var_label(\\"&OTH&\\", \\"DATA\\") != var_label(&VAR&, \\"LIBRARY\\")"}
                          ]}
                        }
                        """);
        assertTrue(error.contains("undeclared expansion token '&OTH&' in Check"), error);
        assertFalse(error.contains("'&VAR&' in Check"), "the declared token is fine: " + error);
    }


    @Test
    void anUndeclaredTokenInOutputVariablesIsRejected() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-G2-OV"},
                  "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(&VAR&)"}]},
                  "Outcome": {"Message": "m", "Output_Variables": ["&VAR&", "ADSL.&OTH&"]}
                }
                """);
        assertTrue(error.contains("undeclared expansion token '&OTH&' in Outcome.Output_Variables"),
                error);
    }


    @Test
    void anUndeclaredTokenInAMatchDatasetsFilterIsRejected() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-G2-MD"},
                  "Check": {"all": [{"expression": "not empty(AGE)"}]},
                  "Match_Datasets": [{"Name": "ADSL", "Keys": ["USUBJID"], "Join_Type": "left",
                                      "Filter": "&Z& == \\"1\\""}]
                }
                """);
        assertTrue(error.contains("undeclared expansion token '&Z&' in Match_Datasets[0]"), error);
    }


    @Test
    void operatorTextIsNotAnUndeclaredToken() throws IOException
    {
        // `A&&B&&C` — the one shape where a scan without the operator step would find a "token":
        // the text `&B&` sits between the two operators. The scan reads `&&` as the operator
        // (ExpansionTokens S1 step 2), so G2 sees no token and the rule loads clean.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-G2-ANDAND"},
                  "Check": {"all": [{"expression": "A&&B&&C"}]}
                }
                """);
        assertNull(rule.getLoadError(), rule.getLoadError());
    }


    @Test
    void anUndeclaredTokenInALevelMessageIsRejected() throws IOException
    {
        // A level's own Message is substituted by the expander (LevelCheck.map), so it is a G2
        // surface like Outcome.Message — an undeclared token there would survive into the run.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-G2-LEVELMSG"},
                  "Check": {"ERROR": {"expression": "not empty(AGE)", "Message": "&X& is blank"}}
                }
                """);
        assertTrue(error.contains("undeclared expansion token '&X&' in Check[ERROR].Message"),
                error);
    }


    @Test
    void anUndeclaredTokenInAScopeListIsRejected() throws IOException
    {
        // Scope name lists are matched BEFORE expansion; a token there is matched literally. They
        // are surfaces so G2 catches an undeclared one and R6 a declared one (below).
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-G2-SCOPE"},
                  "Scope": {"Domains": {"Include": ["AE"], "Exclude": ["&X&"]}},
                  "Check": {"all": [{"expression": "not empty(AGE)"}]}
                }
                """);
        assertTrue(error.contains("undeclared expansion token '&X&' in Scope.Domains.Exclude"),
                error);
    }


    @Test
    void aDeclaredTokenInAScopeListIsRejected() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-R6-SCOPE"},
                  "Scope": {"Datasets": {"Include": ["&DOM&"]}},
                  "Expansion": [
                    {"token": "&DOM&", "over": "domain_from_variable", "pattern": "&DOM&SEQ"}
                  ],
                  "Check": {"all": [{"expression": "not empty(&DOM&SEQ)"}]}
                }
                """);
        assertTrue(error.contains("Expansion token '&DOM&' must not appear in"
                + " Scope.Datasets.Include entry '&DOM&'"), error);
        assertTrue(error.contains("scope gate runs before expansion"), error);
    }


    @Test
    void aDeclaredTokenInARegexLiteralIsRejected() throws IOException
    {
        // A regex literal is never substituted (WildcardExpander.substituteLit skips REGEX), so a
        // declared token inside one passes G2, is left in place by the expander and drops every
        // expansion at run time with a survivor reason. Refuse it at load instead.
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-REGEX"},
                  "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "&VAR& =~ /^&VAR&$/"}]}
                }
                """);
        assertTrue(error.contains("expansion token '&VAR&' inside the regex literal /^&VAR&$/"
                + " in Check (a regex literal is never substituted)"), error);
        // The same token outside the regex is fine: no G2 error is reported for it.
        assertFalse(error.contains("undeclared"), error);
    }


    @Test
    void aDeclaredTokenInABindingRegexLiteralIsRejected() throws IOException
    {
        String error = errorOf(
                """
                        {
                          "Core": {"Id": "TEST-REGEX-BINDING"},
                          "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}],
                          "Bindings": [{"name": "$n", "expression": "record_count(filter=(&VAR& =~ /&VAR&/))"}],
                          "Check": {"all": [{"expression": "$n > 0"}]}
                        }
                        """);
        assertTrue(error.contains(
                "expansion token '&VAR&' inside the regex literal /&VAR&/" + " in Bindings[0]"),
                error);
    }


    @Test
    void anHtmlEntityInTheDescriptionOfAPlainRuleIsNotAnError() throws IOException
    {
        // The corpus carries `&lt;&gt;` in PMDA-AD0586's Description: a stray, not a complete
        // token, and G3 is scoped to rules that declare an Expansion block.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-G2-ENTITY"},
                  "Description": "base &lt;&gt; 0 and R&D",
                  "Check": {"all": [{"expression": "not empty(AGE)"}]}
                }
                """);
        assertNull(rule.getLoadError(), rule.getLoadError());
    }

    // ------------------------------------------------------------------
    // G3 — the old undelimited spelling anywhere in an Expansion rule
    // ------------------------------------------------------------------


    @Test
    void theOldSpellingInTheCheckIsALexerError()
    {
        // A malformed Check fails the package at parse time (ExpressionLoaderTest
        // .malformedExpressionFailsLoudly), so the lexer's message is what the author reads.
        IOException ex = assertThrows(IOException.class, () -> load("""
                {
                  "Core": {"Id": "TEST-G3-CHECK"},
                  "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(&VAR)"}]}
                }
                """));
        assertTrue(ex.getMessage().contains("unterminated expansion token '&VAR'"),
                ex.getMessage());
        assertTrue(ex.getMessage().contains("or did you mean the operator '&&'?"), ex.getMessage());
    }


    @Test
    void theOldSpellingInAStringLiteralIsRejected() throws IOException
    {
        String error = errorOf(
                """
                        {
                          "Core": {"Id": "TEST-G3-LIT"},
                          "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}],
                          "Check": {"all": [
                            {"expression": "var_label(\\"&VAR\\", \\"DATA\\") != var_label(&VAR&, \\"LIBRARY\\")"}
                          ]}
                        }
                        """);
        assertTrue(error.contains("unterminated expansion token '&VAR' in Check"), error);
        assertTrue(error.contains("&NAME&"), error);
    }


    @Test
    void theOldSpellingInOutputVariablesAndKeysAndRequirementsIsRejected() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-G3-SURF"},
                  "Requirements": {"Variables": {"All": ["&VAR"]}},
                  "Expansion": [
                    {"token": "&VAR&", "over": "shared_variables", "with": "ADSL"},
                    {"token": "&DOM&", "over": "domain_from_variable", "pattern": "&DOM&SEQ"}
                  ],
                  "Check": {"all": [{"expression": "not empty(&VAR&) and not empty(&DOM&SEQ)"}]},
                  "Outcome": {"Message": "m", "Output_Variables": ["&VAR", "&DOM&SEQ"]},
                  "Match_Datasets": [{"Name": "&DOM&", "Keys": ["USUBJID", "&DOMSEQ"],
                                      "Join_Type": "left"}]
                }
                """);
        assertTrue(
                error.contains("unterminated expansion token '&VAR' in Outcome.Output_Variables"),
                error);
        assertTrue(error.contains("unterminated expansion token '&DOMSEQ' in Match_Datasets[0]"),
                error);
        // R6 no longer sees `&VAR` (it tests contains("&VAR&")); G3 is what catches it now.
        assertTrue(error.contains("unterminated expansion token '&VAR' in Requirements.Variables"),
                error);
    }


    @Test
    void theOldSpellingInThePatternIsRejected() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-G3-PATTERN"},
                  "Expansion": [
                    {"token": "&DOM&", "over": "domain_from_variable", "pattern": "&DOMSEQ"}
                  ],
                  "Check": {"all": [{"expression": "not empty(&DOM&SEQ)"}]}
                }
                """);
        assertTrue(error.contains("exactly once (found 0)"), error);
        assertTrue(error.contains("unterminated expansion token '&DOMSEQ'"), error);
    }


    @Test
    void aLowerCaseOrAdjacentTokenInAnOutputVariableIsRejected() throws IOException
    {
        String lower = errorOf("""
                {
                  "Core": {"Id": "TEST-G3-LOWER"},
                  "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(&VAR&)"}]},
                  "Outcome": {"Message": "m", "Output_Variables": ["&var&"]}
                }
                """);
        assertTrue(lower.contains("upper case"), lower);
        String adjacent = errorOf("""
                {
                  "Core": {"Id": "TEST-G3-ADJ"},
                  "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(&VAR&)"}]},
                  "Outcome": {"Message": "m", "Output_Variables": ["&VAR&&VAR&"]}
                }
                """);
        assertTrue(adjacent.contains("separate an expansion token from '&' / '&&' by a space"),
                adjacent);
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
                  "Expansion": [{"token": "&VAR&", "over": "all_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "not empty(&VAR&)"}]}
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
                    {"token": "&VAR&", "over": "all_numeric_variables", "pattern": "&VAR&SEQ"}
                  ],
                  "Check": {"all": [{"expression": "not empty(&VAR&)"}]}
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
                    {"token": "&VAR&", "over": "all_character_variables",
                     "known_domain_only": true}
                  ],
                  "Check": {"all": [{"expression": "not empty(&VAR&)"}]}
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
                  "Expansion": [{"token": "&VAR&", "over": "all_variables"}],
                  "Check": {"all": [{"expression": "not empty(&VAR&)"}]}
                }
                """);
        assertNull(rule.getLoadError(), () -> "unexpected load error: " + rule.getLoadError());
        assertNotNull(rule.getExpansion());
    }

    // ------------------------------------------------------------------
    // G3 (all_*) — an all_* expansion and the variable cursor are mutually exclusive
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
                  "Expansion": [{"token": "&VAR&", "over": "all_variables"}],
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
                  "Expansion": [{"token": "&VAR&", "over": "all_numeric_variables"}],
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
                  "Expansion": [{"token": "&VAR&", "over": "all_variables"}],
                  "Check": {"all": [
                    {"expression": "var_label(\\"&VAR&\\", \\"DATA\\") != \\"\\""}
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
                  "Expansion": [{"token": "&VAR&", "over": "shared_variables", "with": "ADSL"}],
                  "Check": {"all": [{"expression": "ends_with(varname(), \\"DTC\\")"}]}
                }
                """);
        assertNull(rule.getLoadError(), () -> "unexpected load error: " + rule.getLoadError());
    }


    /**
     * ⛔ Review round 1: G3's domain test lives at the end of {@code installCompiledLevels}, and two
     * earlier returns skip it — a level {@code tryRaiseToExpr} cannot raise, and one
     * {@code NativeExprEvaluator} does not support. An {@code all_*} rule taking either path
     * carried no load error, expanded to one rule per column, and each minted copy then reported
     * the per-rule "no native expression form" ERROR: N duplicated rows where one belongs, and the
     * R6 violation never reported at all. It is now a load error of its own.
     */
    @Test
    void anAllVariablesRuleWithNoNativeFormIsRejected() throws IOException
    {
        String error = errorOf("""
                {
                  "Core": {"Id": "TEST-G3-NONNATIVE"},
                  "Expansion": [{"token": "&VAR&", "over": "all_variables"}],
                  "Check": {"all": [{"expression": "no_such_native_function_xyz(USUBJID)"}]}
                }
                """);
        assertTrue(error.contains("no native expression form"), error);
    }


    /**
     * The sensitivity arm for the one above: the SAME non-native Check without an {@code all_*}
     * expansion must NOT acquire this error, or the new guard is just rejecting broken rules twice
     * and says nothing about expansions.
     */
    @Test
    void aNonNativeRuleWithoutAnAllExpansionIsNotRejectedByThisGuard() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-G3-NONNATIVE-PLAIN"},
                  "Check": {"all": [{"expression": "no_such_native_function_xyz(USUBJID)"}]}
                }
                """);
        String error = rule.getLoadError();
        assertTrue(error == null || !error.contains("no native expression form"),
                () -> "this guard must be about all_* expansions, not about non-native rules: "
                        + error);
    }

}
