package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * EC-51 Half B (Fix #145) — the load-time rejections around {@code missing_values:}.
 *
 * <p>
 * <b>Why these are load errors and not warnings.</b> Every mapper in this repo runs with
 * {@code FAIL_ON_UNKNOWN_PROPERTIES=false} and there is no global Jackson naming strategy, so the
 * failure mode of this field is <em>silent loss</em>: an unusable declaration would simply be
 * ignored and the rule would keep running with the {@code skip} default, with nothing anywhere
 * telling its author. Every case below therefore asserts on {@code loadError}, which makes the rule
 * report ERROR.
 * </p>
 */
class MissingValuesLoadValidationTest
{

    /**
     * ⚠ The body is collapsed to a single line first. These fixtures are text blocks, and a rule
     * expression long enough to wrap would otherwise carry a raw newline <em>inside</em> a JSON
     * string literal — which Jackson rejects outright, so the test would fail on its own formatting
     * rather than on the behaviour it pins. (Spotless reflows the blocks, so any
     * indentation-sensitive fix-up is brittle by construction.)
     */
    private static Rule loadRule(String ruleBody) throws IOException
    {
        String body = ruleBody.replaceAll("\\s*\\R\\s*", " ");
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"x\":" + body + "}}");
        assertNotNull(pkg.getRules());
        return pkg.getRules().values().iterator().next();
    }


    private static void assertLoadErrorMentions(Rule rule, String fragment)
    {
        String error = rule.getLoadError();
        assertNotNull(error, "expected a load error");
        assertTrue(error.contains(fragment),
                "expected the load error to mention `" + fragment + "`, got: " + error);
    }

    // -----------------------------------------------------------------------
    // The happy path
    // -----------------------------------------------------------------------


    @Test
    void indeterminateOnANegativeConsumerLoads() throws IOException
    {
        Rule rule = loadRule(
                """
                        {"Core":{"Id":"T-MV1","Status":"Draft","Version":"1"},
                         "Check":{"all":[{"expression": "date($min_ex) != EXSTDTC"}]},
                         "Bindings":[{"name":"$min_ex",
                            "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"indeterminate\\")"}]}""");
        assertNull(rule.getLoadError());
        // (min_date is a registry function since W5: the declaration lives in the compiled
        // binding's call, read by GroupedAggregate.spec — the Operation record retired in W8 never
        // carried it.)
        assertEquals("$min_ex", rule.getCompiledBindings().get(0).name());
    }


    @Test
    void skipIsAcceptedOnEveryConsumerIncludingPositiveOnes() throws IOException
    {
        // `skip` is the default and changes nothing, so the polarity gate must not fire for it —
        // otherwise 21 of the 35 date-extreme rules could not state their own disposition.
        Rule rule = loadRule(
                """
                        {"Core":{"Id":"T-MV2","Status":"Draft","Version":"1"},
                         "Check":{"all":[{"expression": "date($min_ex) > EXSTDTC"}]},
                         "Bindings":[{"name":"$min_ex",
                            "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"skip\\")"}]}""");
        assertNull(rule.getLoadError());
        assertEquals("$min_ex", rule.getCompiledBindings().get(0).name());
    }

    // -----------------------------------------------------------------------
    // The value must be one of the two dispositions
    // -----------------------------------------------------------------------


    @Test
    void unknownValueIsRejected() throws IOException
    {
        // NOT `reference_extreme`'s lenient read: a typo must not silently mean `skip`.
        Rule rule = loadRule(
                """
                        {"Core":{"Id":"T-MV3","Status":"Draft","Version":"1"},
                         "Check":{"all":[{"expression": "date($m) != X"}]},
                         "Bindings":[{"name":"$m",
                            "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"indeterminant\\")"}]}""");
        assertLoadErrorMentions(rule, "indeterminant");
    }


    @Test
    void nonStringValueIsRejected() throws IOException
    {
        // The PLURAL key invites a list (`missing_values: ["", " "]`). It is a string enum, and a
        // list must be an error rather than something the parser coerces or the mapper drops.
        Rule rule = loadRule(
                """
                        {"Core":{"Id":"T-MV4","Status":"Draft","Version":"1"},
                         "Check":{"all":[{"expression": "date($m) != X"}]},
                         "Bindings":[{"name":"$m",
                            "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=[\\"\\", \\" \\"])"}]}""");
        assertNotNull(rule.getLoadError());
    }


    @Test
    void numericValueIsRejectedRatherThanRenderedAsText() throws IOException
    {
        Rule rule = loadRule("""
                {"Core":{"Id":"T-MV5","Status":"Draft","Version":"1"},
                 "Check":{"all":[{"expression": "date($m) != X"}]},
                 "Bindings":[{"name":"$m",
                    "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=1)"}]}""");
        assertLoadErrorMentions(rule, "missing_values");
    }

    // -----------------------------------------------------------------------
    // The operator must be one that consumes it
    // -----------------------------------------------------------------------


    @Test
    void unsupportedOperatorsAreRejected() throws IOException
    {
        // record_count / distinct: counting or collecting blanks is not a determinability
        // question. row_max / row_min: a blank horizontal cell (TRxxEDT) encodes "period not
        // used", so `indeterminate` would kill the rule (E0). max: the generic string fallback
        // also serves Char CATEGORY columns (ANRIND, ATOXGR) — a rule that wants date
        // determinability authors max_date, as FDA-SD0080 was moved to do.
        for (String operator : new String[]
        {
                "distinct"
        })
        {
            Rule rule = loadRule("""
                    {"Core":{"Id":"T-MV6","Status":"Draft","Version":"1"},
                     "Check":{"all":[{"expression": "$m != X"}]},
                     "Bindings":[{"name":"$m",
                        "expression":"%s(EXSTDTC, missing_values=\\"skip\\")"}]}"""
                    .formatted(operator));
            // Since runbook W2b no operation consumes missing_values (date_diff_days, the last one,
            // is a registry function), and since W7 distinct is a registry function too: the
            // keyword is the binder's unknown-parameter error.
            assertLoadErrorMentions(rule, "'" + operator + "' has no parameter 'missing_values'");
        }
        // record_count is a registry function since W6 (PLAN-record-count-function): its
        // descriptor declares no missing_values parameter, so the keyword is the binder's
        // unknown-argument error on the function surface.
        Rule count = loadRule("""
                {"Core":{"Id":"T-MV6c","Status":"Draft","Version":"1"},
                 "Check":{"all":[{"expression": "$m != X"}]},
                 "Bindings":[{"name":"$m",
                    "expression":"record_count(missing_values=\\"skip\\")"}]}""");
        assertLoadErrorMentions(count, "'record_count' has no parameter 'missing_values'");
        // max is a registry function since W5 (PLAN-grouped-aggregate-functions): its descriptor
        // declares no missing_values parameter (only the two date extremes do), so the keyword is
        // the binder's unknown-argument error — the same load error, on the function surface.
        Rule max = loadRule("""
                {"Core":{"Id":"T-MV6","Status":"Draft","Version":"1"},
                 "Check":{"all":[{"expression": "$m != X"}]},
                 "Bindings":[{"name":"$m",
                    "expression":"max(EXSTDTC, group=[USUBJID], missing_values=\\"skip\\")"}]}""");
        assertLoadErrorMentions(max, "has no parameter 'missing_values'");
        // row_max is a registry function since wave 3 (PLAN-per-row-functions): it declares no
        // missing_values parameter at all, so the keyword is the binder's unknown-argument error.
        Rule rowMax = loadRule(
                """
                        {"Core":{"Id":"T-MV6","Status":"Draft","Version":"1"},
                         "Check":{"all":[{"expression": "$m != X"}]},
                         "Bindings":[{"name":"$m",
                            "expression":"row_max(name_pattern=\\"^TR\\", missing_values=\\"skip\\")"}]}""");
        assertLoadErrorMentions(rowMax, "missing_values");
        // variable_count likewise since wave 4b (PLAN-scalar-metadata-functions).
        Rule variableCount = loadRule("""
                {"Core":{"Id":"T-MV6","Status":"Draft","Version":"1"},
                 "Check":{"all":[{"expression": "$m != X"}]},
                 "Bindings":[{"name":"$m",
                    "expression":"variable_count(\\"--LNKGRP\\", missing_values=\\"skip\\")"}]}""");
        assertLoadErrorMentions(variableCount, "missing_values");
    }


    @Test
    void theTwoDateExtremeOperatorsAreAccepted() throws IOException
    {
        for (String operator : new String[]
        {
                "min_date", "max_date"
        })
        {
            Rule rule = loadRule(
                    """
                            {"Core":{"Id":"T-MV7","Status":"Draft","Version":"1"},
                             "Check":{"all":[{"expression": "$m != X"}]},
                             "Bindings":[{"name":"$m",
                                "expression":"%s(EXSTDTC, group=[USUBJID], missing_values=\\"indeterminate\\")"}]}"""
                            .formatted(operator));
            assertNull(rule.getLoadError(), operator + " must accept missing_values");
        }
    }


    /**
     * Since runbook W2b {@code date_diff_days} is a registry function whose grouped reference is a
     * named {@code min_date} / {@code max_date} argument: the disposition is declared on that
     * aggregate, never on the day count. On {@code date_diff_days} itself it is an unknown keyword
     * — the operation's Mode 1 rejection (a same-record reference, where the declaration would have
     * changed nothing) holds by construction.
     */
    @Test
    void dateDiffDaysTakesItOnlyThroughItsNamedAggregate() throws IOException
    {
        Rule composed = loadRule("""
                {"Core":{"Id":"T-MV7a","Status":"Draft","Version":"1"},
                 "Check":{"all":[{"expression": "$m != X"}]},
                 "Bindings":[{"name":"$m","expression":"date_diff_days(MYDTC, min_date(SJSTDTC,
                    domain=SJ, group=[USUBJID], missing_values=\\"indeterminate\\"))"}]}""");
        assertNull(composed.getLoadError(), composed.getLoadError());

        Rule direct = loadRule("""
                {"Core":{"Id":"T-MV7b","Status":"Draft","Version":"1"},
                 "Check":{"all":[{"expression": "$m != X"}]},
                 "Bindings":[{"name":"$m","expression":"date_diff_days(MYDTC,
                    REF, missing_values=\\"indeterminate\\")"}]}""");
        assertLoadErrorMentions(direct, "missing_values");
    }


    /**
     * A FIELD-FORM operation used to bypass {@code OperationExpressionParser.fromCall} (both
     * retired in runbook W8), so the loader re-ran the guard over the normalised list; the claim
     * kept is that a binding's call declaring {@code missing_values} where its function has no such
     * parameter is a load error, never an unsupported combination bound and dropped at runtime.
     */
    @Test
    void aBindingCallWithoutTheParameterIsValidatedToo() throws IOException
    {
        Rule rule = loadRule(
                """
                        {"Core":{"Id":"T-MV8","Status":"Draft","Version":"1"},
                         "Check":{"all":[{"expression": "$m != X"}]},
                         "Bindings":[{"name": "$m", "expression": "distinct(EXSTDTC, missing_values=\\"skip\\")"}]}""");
        assertLoadErrorMentions(rule, "'distinct' has no parameter 'missing_values'");
    }

    // -----------------------------------------------------------------------
    // OQ3 — the polarity gate
    // -----------------------------------------------------------------------


    @Test
    void indeterminateOnAPositivePolarityConsumerIsRejected() throws IOException
    {
        // The whole point of `indeterminate` is that the dependent check REPORTS. A
        // date_greater_than consumer reads a no-value extreme as "no violation", so the
        // declaration would silence the check instead — the exact opposite of its purpose, and
        // nothing downstream would catch it.
        // The expression walker names the comparison itself; a date(…) wrapper is transparent
        // to operand identity AND to the reported operator name (phase 7d — the leaf-era
        // date_* spellings retired with the leaf model, the polarity rule is unchanged).
        java.util.Map<String, String> spellings = new java.util.LinkedHashMap<>();
        spellings.put("greater_than", "date($m) > EXSTDTC");
        spellings.put("less_than", "date($m) < EXSTDTC");
        spellings.put("equal_to", "date($m) == EXSTDTC");
        spellings.put("greater_than_or_equal_to", "date($m) >= EXSTDTC");
        spellings.put("less_than_or_equal_to", "date($m) <= EXSTDTC");
        for (java.util.Map.Entry<String, String> e : spellings.entrySet())
        {
            Rule rule = loadRule(
                    """
                            {"Core":{"Id":"T-MV9","Status":"Draft","Version":"1"},
                             "Check":{"all":[{"expression":"%s"}]},
                             "Bindings":[{"name":"$m",
                                "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"indeterminate\\")"}]}"""
                            .formatted(e.getValue()));
            assertLoadErrorMentions(rule, "positive-polarity leaf `" + e.getKey() + "`");
        }
    }


    /**
     * The corpus shape §4 counts among the 21 skip-by-construction rules: the assertion itself is
     * negative, but a positive {@code equal_to} self-anchor sits in the same {@code all:}, and one
     * silent conjunct kills the rule just as surely as a silent assertion would.
     */
    @Test
    void aPositiveSelfAnchorInTheSameAllIsEnoughToReject() throws IOException
    {
        Rule rule = loadRule(
                """
                        {"Core":{"Id":"T-MV10","Status":"Draft","Version":"1"},
                         "Check":{"all":[
                            {"expression": "$min_ds == DSSTDTC"},
                            {"expression": "$min_ds != DM.RFICDTC"}]},
                         "Bindings":[{"name":"$min_ds",
                            "expression":"min_date(DSSTDTC, group=[USUBJID], missing_values=\\"indeterminate\\")"}]}""");
        assertLoadErrorMentions(rule, "positive-polarity leaf `equal_to`");
    }


    @Test
    void theBindingIsAlsoFoundWhenItIsTheLeafValue() throws IOException
    {
        // The `$`-ref can sit on either side of the comparison; the polarity conclusion is the
        // same, because a missing LHS and a null RHS both no-fire a positive operator.
        Rule rule = loadRule(
                """
                        {"Core":{"Id":"T-MV11","Status":"Draft","Version":"1"},
                         "Check":{"all":[{"expression": "date(EXSTDTC) == $m"}]},
                         "Bindings":[{"name":"$m",
                            "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"indeterminate\\")"}]}""");
        assertLoadErrorMentions(rule, "positive-polarity leaf `equal_to`");
    }


    @Test
    void anEnclosingNotInvertsWhichSideReports() throws IOException
    {
        // not(date_greater_than) FIRES when the extreme has no value, so it is a reporting path
        // and must be allowed…
        Rule allowed = loadRule(
                """
                        {"Core":{"Id":"T-MV12","Status":"Draft","Version":"1"},
                         "Check":{"not":{"expression": "date($m) > EXSTDTC"}},
                         "Bindings":[{"name":"$m",
                            "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"indeterminate\\")"}]}""");
        assertNull(allowed.getLoadError());

        // …and not(date_not_equal_to) goes silent, so the same inversion must reject it.
        Rule rejected = loadRule(
                """
                        {"Core":{"Id":"T-MV13","Status":"Draft","Version":"1"},
                         "Check":{"not":{"expression": "date($m) != EXSTDTC"}},
                         "Bindings":[{"name":"$m",
                            "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"indeterminate\\")"}]}""");
        assertLoadErrorMentions(rejected, "positive-polarity leaf `not_equal_to`");
    }


    /**
     * An UNDECLARED operation is never judged: the polarity gate is entered only when some
     * operation actually carries {@code indeterminate}, so it costs the shipped corpus — which
     * declares it nowhere — nothing at all.
     */
    @Test
    void aPositiveConsumerIsFineWhenNothingIsDeclared() throws IOException
    {
        Rule rule = loadRule("""
                {"Core":{"Id":"T-MV14","Status":"Draft","Version":"1"},
                 "Check":{"all":[{"expression": "date($m) > EXSTDTC"}]},
                 "Bindings":[{"name":"$m","expression":"min_date(EXSTDTC, group=[USUBJID])"}]}""");
        assertNull(rule.getLoadError());
    }


    /**
     * The {@code Precondition} tree (Fix #13) gates whether the Check runs at all, so a
     * positive-polarity consumer there silences the rule just as dead as one in the Check — and it
     * is a separate tree, so a walker that only visits {@code Check} would miss it entirely.
     */
    @Test
    void aSilencingConsumerInThePreconditionIsRejectedToo() throws IOException
    {
        Rule rule = loadRule(
                """
                        {"Core":{"Id":"T-MV20","Status":"Draft","Version":"1"},
                         "Precondition":{"all":[{"expression": "date($m) > EXSTDTC"}]},
                         "Check":{"all":[{"expression": "date(EXSTDTC) != $m"}]},
                         "Bindings":[{"name":"$m",
                            "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"indeterminate\\")"}]}""");
        assertLoadErrorMentions(rule, "positive-polarity leaf `greater_than`");
    }

    // -----------------------------------------------------------------------
    // OQ3 — the native-only Check that never lowers to operator leaves
    // -----------------------------------------------------------------------


    /**
     * A Check using a native-only construct (here {@code var_label(…, "DEFINE")}, whose
     * arbitrary-literal name has no legacy surface) stays a {@code CheckConditionExpression}
     * carrying the raw {@code Expr}, so the leaf walker never sees it. The guard has to reason over
     * the expression tree directly or a Phase-3 author writing a native rule would get no
     * protection at all.
     */
    @Test
    void nativeExpressionCheckIsWalkedToo() throws IOException
    {
        Rule rejected = loadRule(
                """
                        {"Core":{"Id":"T-MV16","Status":"Draft","Version":"1"},
                         "Sensitivity":"Dataset",
                         "Check":{"expression":
                            "var_label(\\"EXSTDTC\\", \\"DEFINE\\") == \\"x\\" and $m > EXSTDTC"},
                         "Bindings":[{"name":"$m",
                            "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"indeterminate\\")"}]}""");
        assertLoadErrorMentions(rejected, "positive-polarity leaf `greater_than`");

        // …and the negative infix form is a reporting path, so it loads.
        Rule allowed = loadRule(
                """
                        {"Core":{"Id":"T-MV17","Status":"Draft","Version":"1"},
                         "Sensitivity":"Dataset",
                         "Check":{"expression":
                            "var_label(\\"EXSTDTC\\", \\"DEFINE\\") == \\"x\\" and $m != EXSTDTC"},
                         "Bindings":[{"name":"$m",
                            "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"indeterminate\\")"}]}""");
        assertNull(allowed.getLoadError());
    }


    @Test
    void nativeExpressionNotAndOrAreWalkedWithTheRightPolarity() throws IOException
    {
        // `not(... == ...)` reports when the extreme has no value ⇒ allowed.
        Rule allowed = loadRule(
                """
                        {"Core":{"Id":"T-MV18","Status":"Draft","Version":"1"},
                         "Sensitivity":"Dataset",
                         "Check":{"expression":
                            "var_label(\\"EXSTDTC\\", \\"DEFINE\\") == \\"x\\" and not($m == EXSTDTC)"},
                         "Bindings":[{"name":"$m",
                            "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"indeterminate\\")"}]}""");
        assertNull(allowed.getLoadError());

        // An `or` branch is walked as well — a silenced disjunct is still a lost alternative.
        Rule rejected = loadRule(
                """
                        {"Core":{"Id":"T-MV19","Status":"Draft","Version":"1"},
                         "Sensitivity":"Dataset",
                         "Check":{"expression":
                            "var_label(\\"EXSTDTC\\", \\"DEFINE\\") == \\"x\\" or $m < EXSTDTC"},
                         "Bindings":[{"name":"$m",
                            "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"indeterminate\\")"}]}""");
        assertLoadErrorMentions(rejected, "positive-polarity leaf `less_than`");
    }

    // -----------------------------------------------------------------------
    // The INLINE (Form-A) authoring surface — a separate load path entirely
    // -----------------------------------------------------------------------


    /**
     * ⚠ A call authored <b>inline</b> in the Check expression never reaches the rule's bindings, so
     * a rule can declare {@code missing_values} with no binding at all (until runbook W8: with
     * {@code getOperations()} {@code null}). Before this was closed, the rule below loaded
     * completely clean — no {@code loadError}, no bindings, a {@code CheckConditionExpression}
     * check — and then went silent at runtime on exactly the shape the polarity gate exists to
     * reject. Measured, not hypothesised.
     */
    @Test
    void anInlineCallOnAPositiveComparisonIsRejected() throws IOException
    {
        Rule rule = loadRule("""
                {"Core":{"Id":"T-MV21","Status":"Draft","Version":"1"},
                 "Check":{"expression":"min_date(EXSTDTC, group=[USUBJID],
                    missing_values=\\"indeterminate\\") > EXSTDTC"}}""");
        assertLoadErrorMentions(rule, "positive-polarity leaf `greater_than`");
    }


    @Test
    void anInlineCallOnANegativeComparisonLoads() throws IOException
    {
        Rule rule = loadRule("""
                {"Core":{"Id":"T-MV22","Status":"Draft","Version":"1"},
                 "Check":{"expression":"min_date(EXSTDTC, group=[USUBJID],
                    missing_values=\\"indeterminate\\") != EXSTDTC"}}""");
        assertNull(rule.getLoadError());
    }


    /**
     * The value and operator rejections have to reach the inline surface too. The native compiler's
     * own {@code fromCall} throw would only degrade the rule to LEGACY evaluation rather than
     * erroring it — silent, which is what this field's whole design rules out.
     */
    @Test
    void theValueAndOperatorRejectionsReachInlineCallsToo() throws IOException
    {
        Rule badValue = loadRule(
                """
                        {"Core":{"Id":"T-MV23","Status":"Draft","Version":"1"},
                         "Check":{"expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"nope\\") != EXSTDTC"}}""");
        assertLoadErrorMentions(badValue, "nope");

        Rule badOperator = loadRule(
                """
                        {"Core":{"Id":"T-MV24","Status":"Draft","Version":"1"},
                         "Check":{"expression":"EXSTDTC in distinct(EXSTDTC, missing_values=\\"skip\\")"}}""");
        // (distinct is a registry function since runbook W7: the binder's unknown-keyword error)
        assertLoadErrorMentions(badOperator, "'distinct' has no parameter 'missing_values'");
    }


    /**
     * ⚠ A comparison operand is not always a bare {@code $}-ref: {@code ExprLowering} strips the
     * {@code date(…)} / {@code num(…)} / {@code lowcase(…)} wrappers before naming it, so
     * {@code date($m) == X} means what {@code $m == X} means. A literal {@code instanceof Ref} test
     * judged the first and missed the second — the same rule text getting two verdicts depending on
     * whether an unrelated sibling conjunct happened to block lowering. {@code CDISC-CG0143} ships
     * this shape.
     */
    @Test
    void aWrappedReferenceIsStillRecognisedAsTheConsumer() throws IOException
    {
        Rule rule = loadRule(
                """
                        {"Core":{"Id":"T-MV25","Status":"Draft","Version":"1"},
                         "Sensitivity":"Dataset",
                         "Check":{"expression":"var_label(\\"EXSTDTC\\", \\"DEFINE\\") == \\"x\\"
                            and date($m) == EXSTDTC"},
                         "Bindings":[{"name":"$m",
                            "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"indeterminate\\")"}]}""");
        assertLoadErrorMentions(rule, "positive-polarity leaf `equal_to`");
    }


    /**
     * A declaration on an operation the Check does not consume at all cannot silence anything, so
     * it loads. (It is still pointless, but pointless is not the hazard this guard addresses.)
     */
    @Test
    void anUnconsumedDeclarationIsNotRejected() throws IOException
    {
        Rule rule = loadRule(
                """
                        {"Core":{"Id":"T-MV15","Status":"Draft","Version":"1"},
                         "Check":{"all":[{"expression": "date(EXSTDTC) > DM.RFSTDTC"}]},
                         "Bindings":[{"name":"$unused",
                            "expression":"min_date(EXSTDTC, group=[USUBJID], missing_values=\\"indeterminate\\")"}]}""");
        assertNull(rule.getLoadError());
    }

}
