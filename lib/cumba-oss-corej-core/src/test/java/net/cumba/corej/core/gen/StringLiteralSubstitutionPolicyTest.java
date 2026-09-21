package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import java.util.function.UnaryOperator;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.gen.WildcardExpander.StringLiteralPolicy;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionExpression;
import org.junit.jupiter.api.Test;

/**
 * Phase 1 of {@code plans/PLAN-expansion-over-all-variables.md} — the string-literal substitution
 * policy, and the two-sided property that makes it safe.
 *
 * <p>
 * <b>Why this exists.</b> {@code ExprCompiler.metadataPlan} accepts a variable-scope accessor's
 * name operand <em>only</em> as a string literal or the {@code variable_name} operand — a
 * bareword/backtick reference is a {@link RuleDefinitionException}. The substitution walk, in turn,
 * left every scalar STRING literal alone. So the two halves missed each other by exactly one step:
 * {@code var_label(`&VAR`, "DATA")} substituted and then failed to compile, while
 * {@code var_label("&VAR", "DATA")} never substituted at all and the token survived into a resolved
 * rule. Owner ruling (2026-09-21) settles the spelling as the string form — <i>"the {@code "&VAR"}
 * makes clear that the name is used as a string"</i> — so the fix is in the substituter, and
 * {@code ExprCompiler} is deliberately untouched.
 * </p>
 *
 * <p>
 * <b>The dangerous half is the negative control.</b> Both expansion mechanisms share this walk, and
 * the wildcard flavour must NOT gain the wider policy: its markers are matched <em>inside</em>
 * names, and four shipped rules carry a value-position {@code "*"} STRING literal. Widening
 * unconditionally would rewrite those. {@link #wildcardFlavourLeavesValuePositionStarAlone()} is
 * the guard, and it is written against the real shipped shape rather than a stand-in.
 * </p>
 */
class StringLiteralSubstitutionPolicyTest
{

    /** The declared-token rewriter {@code TokenExpander} builds: a plain substring substitution. */
    private static UnaryOperator<String> tokenRename(Map<String, String> substitutions)
    {
        return n ->
        {
            String result = n;
            for (Map.Entry<String, String> e : substitutions.entrySet())
            {
                result = result.replace(e.getKey(), e.getValue());
            }
            return result;
        };
    }


    private static CheckCondition check(String source)
    {
        return new CheckConditionExpression(CheckExpressionParser.parse(source), source);
    }


    private static String printed(CheckCondition condition)
    {
        return ExpressionPrinter.print(((CheckConditionExpression) condition).expr());
    }


    private static CheckCondition expandToken(String source, String token, String value)
    {
        return WildcardExpander.substituteNames(check(source), tokenRename(Map.of(token, value)),
                StringLiteralPolicy.DECLARED_TOKEN_BEARING);
    }

    // ------------------------------------------------------------------
    // The feature: a token reaches a metadata accessor's name operand
    // ------------------------------------------------------------------


    /**
     * The whole point of the policy. Before it, the token survived here and the rule was dropped.
     */
    @Test
    void declaredTokenIsSubstitutedInsideAMetadataAccessorNameLiteral()
    {
        CheckCondition out = expandToken("var_label(\"&VAR\", \"DATA\") != \"\"", "&VAR", "AETERM");

        assertTrue(printed(out).contains("\"AETERM\""), printed(out));
        assertTrue(!printed(out).contains("&VAR"), "token survived: " + printed(out));
    }


    /** And the substituted form must actually compile — the half that was failing before. */
    @Test
    void theSubstitutedAccessorCompiles()
    {
        CheckCondition out = expandToken("var_label(\"&VAR\", \"DATA\") != \"\"", "&VAR", "AETERM");
        Expr expr = ((CheckConditionExpression) out).expr();

        assertTrue(NativeExprEvaluator.isSupported(expr), "expected a compilable expression");
    }


    /**
     * ⭐ Owner ruling R1, pinned as behaviour rather than prose: the backtick spelling reads as "the
     * variable whose VALUE names a variable", so it must stay a compile error. If this ever starts
     * passing, the distinction the ruling preserves has been silently widened.
     */
    @Test
    void theBacktickRefSpellingStillDoesNotCompile()
    {
        CheckCondition out = expandToken("var_label(`&VAR`, \"DATA\") != \"\"", "&VAR", "AETERM");
        Expr expr = ((CheckConditionExpression) out).expr();

        // The token IS substituted (it is a ref, and refs were always substituted) …
        assertTrue(printed(out).contains("AETERM"), printed(out));
        // … but a reference in a name position is not a name the compiler accepts.
        // isSupported swallows ExpressionException; a RuleDefinitionException — the "rule is
        // wrong" signal — propagates, which is exactly the distinction being pinned here.
        RuleDefinitionException ex = assertThrows(RuleDefinitionException.class,
                () -> NativeExprEvaluator.isSupported(expr));
        assertTrue(ex.getMessage().contains("name must be a string"), ex.getMessage());
    }

    // ------------------------------------------------------------------
    // The blast radius: what the wider policy must NOT touch
    // ------------------------------------------------------------------


    /** A value-position literal with no token is returned as the very same instance. */
    @Test
    void aValuePositionLiteralWithoutTheTokenIsUntouched()
    {
        String source = "not contains(var_label(\"LIBRARY\"), \"Screen Failure\")";
        CheckCondition out = expandToken(source, "&VAR", "AETERM");

        assertEquals("not contains(var_label(\"LIBRARY\"), \"Screen Failure\")", printed(out));
    }


    /**
     * ⭐ {@code REGEX} is a distinct {@link Expr.LitKind}, which is the single fact that makes the
     * wider policy cheap: a regex whose text happens to contain the token is never a candidate.
     */
    @Test
    void aRegexLiteralContainingTheTokenTextIsNotRewritten()
    {
        CheckCondition out = expandToken("varname() !~ /^&VAR[0-9]$/", "&VAR", "AETERM");

        assertTrue(printed(out).contains("&VAR"),
                "a REGEX literal must survive verbatim: " + printed(out));
        assertTrue(!printed(out).contains("AETERM"), printed(out));
    }


    /**
     * A NUMBER literal is not a string and must survive the wider policy untouched — checked
     * through the public walk rather than a test-only hook into the private method.
     */
    @Test
    void nonStringScalarLiteralsAreReturnedUnchanged()
    {
        CheckCondition out = expandToken("record_count() > 1", "&VAR", "AETERM");

        assertEquals("record_count() > 1", printed(out));
    }

    // ------------------------------------------------------------------
    // The negative control — the wildcard flavour is bit-for-bit unchanged
    // ------------------------------------------------------------------


    /**
     * ⛔⛔ The regression this policy could have caused. {@code CDISC-AD0018}, {@code AD0708},
     * {@code AD0709} and {@code PMDA-AD0018} all carry
     * {@code not contains(var_label("LIBRARY"), "*")}, where the {@code "*"} is a <b>value</b> —
     * arg 1 of {@code contains()}, not arg 0 of an exists call. Under the wildcard flavour's own
     * rewriter, {@code "*"} maps to a concrete column name, so had the policy been widened
     * unconditionally this literal would have become a column name and the leaf would have changed
     * meaning silently.
     *
     * <p>
     * The default (2-arg) entry point must therefore still be {@code EXISTS_NAME_ONLY}.
     * </p>
     */
    @Test
    void wildcardFlavourLeavesValuePositionStarAlone()
    {
        String source = "not contains(var_label(\"LIBRARY\"), \"*\")";
        // The wildcard flavour's rewriter: a whole-name map lookup, "*" -> a concrete column.
        UnaryOperator<String> wildcardRename = n -> "*".equals(n) ? "AEDECOD" : n;

        CheckCondition viaDefault = WildcardExpander.substituteNames(check(source), wildcardRename);
        CheckCondition viaExplicit = WildcardExpander.substituteNames(check(source), wildcardRename,
                StringLiteralPolicy.EXISTS_NAME_ONLY);

        assertEquals(source, printed(viaDefault), "the 2-arg entry point must not have widened");
        assertEquals(source, printed(viaExplicit));
    }


    /**
     * The same rewriter under the wider policy DOES rewrite it — which is exactly why the two
     * flavours must not share one policy. This test fails if the enum ever collapses to one value.
     */
    @Test
    void theWiderPolicyWouldHaveRewrittenIt_whichIsWhyTheFlavoursDiffer()
    {
        String source = "not contains(var_label(\"LIBRARY\"), \"*\")";
        UnaryOperator<String> wildcardRename = n -> "*".equals(n) ? "AEDECOD" : n;

        CheckCondition widened = WildcardExpander.substituteNames(check(source), wildcardRename,
                StringLiteralPolicy.DECLARED_TOKEN_BEARING);

        assertEquals("not contains(var_label(\"LIBRARY\"), \"AEDECOD\")", printed(widened),
                "if this stops differing from the EXISTS_NAME_ONLY result, the negative control "
                        + "above has become vacuous");
    }

    // ------------------------------------------------------------------
    // Structure
    // ------------------------------------------------------------------


    /** The exists-family arg-0 rewrite is unchanged under both policies. */
    @Test
    void existsFamilyNameLiteralIsRewrittenUnderBothPolicies()
    {
        String source = "var_exists(\"&VAR\")";
        UnaryOperator<String> rename = tokenRename(Map.of("&VAR", "AETERM"));

        assertEquals("var_exists(\"AETERM\")", printed(WildcardExpander
                .substituteNames(check(source), rename, StringLiteralPolicy.EXISTS_NAME_ONLY)));
        assertEquals("var_exists(\"AETERM\")",
                printed(WildcardExpander.substituteNames(check(source), rename,
                        StringLiteralPolicy.DECLARED_TOKEN_BEARING)));
    }


    /** List-literal elements are still walked, and a token inside one is substituted. */
    @Test
    void tokenInsideAListLiteralIsSubstituted()
    {
        CheckCondition out = expandToken("varname() in [\"&VAR\", \"USUBJID\"]", "&VAR", "AETERM");

        assertTrue(printed(out).contains("\"AETERM\""), printed(out));
        assertTrue(printed(out).contains("\"USUBJID\""), printed(out));
    }


    /** A fresh tree is returned when anything changed, and the walk is structure-preserving. */
    @Test
    void substitutionReturnsAFreshTree()
    {
        CheckCondition in = check("var_label(\"&VAR\", \"DATA\") != \"\"");
        CheckCondition out = WildcardExpander.substituteNames(in,
                tokenRename(Map.of("&VAR", "AETERM")), StringLiteralPolicy.DECLARED_TOKEN_BEARING);

        assertNotSame(in, out);
        assertInstanceOf(CheckConditionExpression.class, out);
    }
}
