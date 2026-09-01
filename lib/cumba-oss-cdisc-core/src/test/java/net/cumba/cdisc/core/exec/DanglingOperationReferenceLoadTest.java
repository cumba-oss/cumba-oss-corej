package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.CheckConditionExpression;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code plans/PLAN-dangling-operation-reference-load-check.md} — a {@code Check} (or
 * {@code Precondition}) that references a {@code $}-operand no {@code Operations} entry defines is
 * tagged at load, because at runtime it is <em>silent</em>: the name never enters the evaluation
 * context, {@code ExprCompiler.nameRefPlan} yields {@code null ⇒ empty BitSet}, and the rule
 * reports nothing whether the data is clean or not.
 *
 * <p>
 * <b>Always the {@code loadError} channel.</b> Until {@code Fix #159} a rule declaring
 * {@code Executability: "Not Executable"} was downgraded to a {@code loadWarning} so it could keep
 * loading and running; that field now <em>parks</em> the rule instead
 * ({@code RulePackageLoader.removeParkedRules}, pinned by {@link NotExecutableParksRuleLoadTest}),
 * so no rule declaring it ever reaches this gate and there is no severity split left to test.
 * </p>
 */
class DanglingOperationReferenceLoadTest
{

    private static final String DANGLES = "which no Operations entry defines";

    private static Rule load(String ruleJson) throws IOException
    {
        RulePackage pkg = RulePackageLoader
                .loadFromString("{\"rules\":{\"rule-1\":" + ruleJson + "}}");
        return pkg.getRules().values().iterator().next();
    }


    private static IDataTable table()
    {
        return MockTable.of().col("USUBJID", "S1").col("AETERM", "HEADACHE").name("ADAE").build();
    }

    // -----------------------------------------------------------------------
    // One severity — the error channel, whatever the rule declares
    // -----------------------------------------------------------------------


    @Test
    void executableRuleWithADanglingRef_tagsLoadError_andExecutesAsError() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-1"},
                  "Executability": "Fully Executable",
                  "Check": {"all": [{"name": "$never_defined", "operator": "non_empty"}]}
                }
                """);
        assertNotNull(rule.getLoadError(), "an executable rule must fail loud");
        assertTrue(rule.getLoadError().contains("$never_defined"), rule.getLoadError());
        assertTrue(rule.getLoadError().contains(DANGLES), rule.getLoadError());
        assertNull(rule.getLoadWarning(), "the executable case uses the error channel only");

        RuleExecutionResult result = RuleRunner.execute(rule, table());
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus());
        assertEquals(rule.getLoadError(), result.getStatusMessage());
        assertEquals(1, result.getViolationCount(), "exactly one sentinel violation");
        assertEquals(rule.getLoadError(),
                result.getViolations().get(0).getValues().get("__error__"));
    }


    @Test
    void notExecutableRuleWithADanglingRef_isParkedBeforeThisGateRuns() throws IOException
    {
        // Fix #159: the rule never enters the package, so this gate never judges it. That is why
        // the old "declared gap ⇒ loadWarning" branch could be removed rather than left
        // unreachable.
        RulePackage pkg = RulePackageLoader.loadFromString("""
                {"rules": {"rule-1": {
                  "Core": {"Id": "TEST-DOR-2"},
                  "Executability": "Not Executable",
                  "Check": {"all": [{"name": "$never_defined", "operator": "non_empty"}]}
                }}}
                """);
        assertTrue(pkg.getRules().isEmpty(),
                () -> "a Not Executable rule is parked, not warned: " + pkg.getRules().keySet());
    }


    @Test
    void absentExecutability_isAnError_becauseNoGapWasDeclared() throws IOException
    {
        // `Executability` is not derivable and the corpus may omit it. Omitting it is not a
        // declaration of a gap, so the rule is judged as one that claims to work.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-3"},
                  "Check": {"all": [{"name": "$never_defined", "operator": "non_empty"}]}
                }
                """);
        assertNull(rule.getExecutability());
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains(DANGLES), rule.getLoadError());
    }


    @Test
    void aPartiallyExecutableRuleIsStillAnError() throws IOException
    {
        // ⚠ Only the full "Not Executable" declaration parks a rule: a partially-executable rule
        // still claims that what it does check, it checks — so it loads, and its dangling operand
        // is an error like any other.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-4"},
                  "Executability": "Partially Executable",
                  "Check": {"all": [{"name": "$never_defined", "operator": "non_empty"}]}
                }
                """);
        assertNotNull(rule.getLoadError());
        assertNull(rule.getLoadWarning());
    }

    // -----------------------------------------------------------------------
    // The negative control — every reference resolves
    // -----------------------------------------------------------------------


    @Test
    void everyRefResolving_leavesBothChannelsUntouched() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-5"},
                  "Operations": [{"id": "$n", "operator": "record_count", "group": ["USUBJID"]}],
                  "Check": {"all": [{"name": "$n", "operator": "equal_to", "value": 1}]}
                }
                """);
        assertNull(rule.getLoadError());
        assertNull(rule.getLoadWarning());
    }


    @Test
    void aCheckWithNoOperandRefsAtAll_isUntouched() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-6"},
                  "Check": {"all": [{"name": "AETERM", "operator": "non_empty"}]}
                }
                """);
        assertNull(rule.getLoadError());
        assertNull(rule.getLoadWarning());
    }

    // -----------------------------------------------------------------------
    // Operand positions — the CDISC-AD0591 shape
    // -----------------------------------------------------------------------


    @Test
    void aDanglingRefInValuePositionOnly_isCaught() throws IOException
    {
        // CDISC-AD0591's third leaf is `$current_value != $adsl_value`. A name-position-only walk
        // catches half of such a rule and, where the name side resolves, none of it.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-7"},
                  "Operations": [{"id": "$n", "operator": "record_count", "group": ["USUBJID"]}],
                  "Check": {"all": [{"name": "$n", "operator": "not_equal_to",
                                     "value": "$never_defined"}]}
                }
                """);
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("$never_defined"), rule.getLoadError());
        assertTrue(!rule.getLoadError().contains("$n,") && !rule.getLoadError().contains(" $n "),
                "the defined operand must not be reported: " + rule.getLoadError());
    }


    @Test
    void aDanglingRefInACompositeNamesTarget_isCaught() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-8"},
                  "Check": {"all": [{"names": ["VISIT", "$never_defined"],
                                     "operator": "is_not_contained_by", "value": "$keys"}]}
                }
                """);
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("$never_defined"), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("$keys"), rule.getLoadError());
    }


    @Test
    void aDanglingRefInAnArrayValueOfAFunctionOperator_isCaught() throws IOException
    {
        // ⚠ An array `value` is NOT always a list of literals. For the function/group family
        // CheckToExpr.functionLeaf routes it to arrayOperand, which emits
        // `e.isTextual() ? ref(e) : literal(e)` — and ExprCompiler.expandRefKeys then splices a
        // $-keyed member out to its column list. This is the FDA-SD1117 / PMDA-SD1152 shape.
        // Missing it is not a silent PASS but a silent WRONG ANSWER: GroupSemantics
        // .uniqueSetViolations drops an unresolvable key column, coarsening the uniqueness set,
        // so the rule over-reports duplicates.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-21"},
                  "Check": {"all": [{"name": "USUBJID", "operator": "is_not_unique_set",
                                     "value": ["AETESTCD", "$never_defined"]}]}
                }
                """);
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("$never_defined"), rule.getLoadError());
    }


    @Test
    void aDefinedRefInAnArrayValue_isNotReported() throws IOException
    {
        // The negative control for the case above — the FDA-SD1117 shape as actually shipped.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-22"},
                  "Operations": [{"id": "$natural_key", "operator": "natural_key_variables"}],
                  "Check": {"all": [{"name": "USUBJID", "operator": "is_not_unique_set",
                                     "value": ["AETESTCD", "$natural_key"]}]}
                }
                """);
        assertNull(rule.getLoadError());
        assertNull(rule.getLoadWarning());
    }


    @Test
    void aDanglingRefInWithinOrOrdering_isCaught() throws IOException
    {
        // CheckToExpr.functionLeaf raises `within` entry-by-entry (withinOperand) and `ordering`
        // (ref(...)) as references. No shipped rule puts a `$` there — these are covered because
        // the engine would resolve one if it did.
        Rule within = load("""
                {
                  "Core": {"Id": "TEST-DOR-23"},
                  "Check": {"all": [{"name": "AEDECOD", "operator": "has_multiple_values_for",
                                     "within": ["USUBJID", "$never_defined"]}]}
                }
                """);
        assertNotNull(within.getLoadError(), "within is a reference position");
        assertTrue(within.getLoadError().contains("$never_defined"), within.getLoadError());

        // ⚠ withinOperand accepts a NESTED list (a coalesce-group) and raises each member with
        // ref(...) too, so the walk has to recurse rather than scan one level.
        Rule nested = load("""
                {
                  "Core": {"Id": "TEST-DOR-23N"},
                  "Check": {"all": [{"name": "AEDECOD", "operator": "has_multiple_values_for",
                                     "within": ["USUBJID", ["VISIT", "$never_defined"]]}]}
                }
                """);
        assertNotNull(nested.getLoadError(), "a nested within coalesce-group is walked too");
        assertTrue(nested.getLoadError().contains("$never_defined"), nested.getLoadError());

        Rule ordering = load("""
                {
                  "Core": {"Id": "TEST-DOR-24"},
                  "Check": {"all": [{"name": "AESEQ", "operator": "is_not_ordered_subset_of",
                                     "value": "AE", "ordering": "$never_defined"}]}
                }
                """);
        assertNotNull(ordering.getLoadError(), "ordering is a reference position");
        assertTrue(ordering.getLoadError().contains("$never_defined"), ordering.getLoadError());
    }


    @Test
    void aDollarValueOnASubstringOrRegexOperator_isNotCaught() throws IOException
    {
        // CheckToExpr emits these operands as literals REGARDLESS of value_is_literal
        // (substringValue / regex / affixMatches / lengthValue), so a leading `$` is a character
        // in a substring or pattern — never an operation id. Reporting it would reject a rule
        // matching a literal dollar amount.
        for (String operator : new String[]
        {
                "contains", "starts_with", "ends_with", "does_not_contain", "matches_regex",
                "not_matches_regex"
        })
        {
            Rule rule = load("""
                    {
                      "Core": {"Id": "TEST-DOR-25"},
                      "Check": {"all": [{"name": "AECOST", "operator": "%s", "value": "$50"}]}
                    }
                    """.formatted(operator));
            assertNull(rule.getLoadError(), operator + " reads its value as a literal");
            assertNull(rule.getLoadWarning(), operator + " reads its value as a literal");
        }
    }


    @Test
    void aDanglingRefInThePrecondition_isCaught() throws IOException
    {
        // The Precondition gates whether the Check runs at all, so an operand that never resolves
        // there is at least as fatal as one in the Check.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-9"},
                  "Precondition": {"all": [{"name": "$never_defined", "operator": "non_empty"}]},
                  "Check": {"all": [{"name": "AETERM", "operator": "non_empty"}]}
                }
                """);
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("$never_defined"), rule.getLoadError());
        // The message must name the surface it actually found — sending the reader to the Check
        // when the Precondition is the broken half wastes the diagnostic.
        assertTrue(rule.getLoadError().contains("] Precondition references"), rule.getLoadError());
        assertTrue(!rule.getLoadError().contains("] Check references"), rule.getLoadError());
    }


    @Test
    void aDanglingRefInBothSurfaces_namesBoth() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-26"},
                  "Precondition": {"all": [{"name": "$gate_op", "operator": "non_empty"}]},
                  "Check": {"all": [{"name": "$check_op", "operator": "non_empty"}]}
                }
                """);
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("] Check and Precondition references"),
                rule.getLoadError());
        assertTrue(rule.getLoadError().contains("$gate_op"), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("$check_op"), rule.getLoadError());
    }


    @Test
    void bothUndefinedOperandsAreNamedInOneFinding() throws IOException
    {
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-10"},
                  "Check": {"all": [{"name": "$adsl_value", "operator": "non_empty"},
                                    {"name": "$current_value", "operator": "not_equal_to",
                                     "value": "$adsl_value"}]}
                }
                """);
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("$adsl_value"), rule.getLoadError());
        assertTrue(rule.getLoadError().contains("$current_value"), rule.getLoadError());
        assertEquals(1, rule.getLoadError().split("\\$adsl_value", -1).length - 1,
                "each operand is named once: " + rule.getLoadError());
    }

    // -----------------------------------------------------------------------
    // It is an AST walk, not a `\$\w+` scan over the source text
    // -----------------------------------------------------------------------


    @Test
    void aDollarInsideAStringLiteral_isNotCaught() throws IOException
    {
        // A textual scan would report `$notanop` here. The walk only visits reference positions,
        // and ExprLowering marks a lowered literal with value_is_literal.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-11"},
                  "Check": {"expression": "AETERM == \\"$notanop\\""}
                }
                """);
        assertNull(rule.getLoadError(), "a string literal is not an operand reference");
        assertNull(rule.getLoadWarning());
    }


    @Test
    void aDollarInsideAStringLiteralOfAnUnloweredExpression_isNotCaught() throws IOException
    {
        // ⚠ The twin above lowers to leaf form, so it exercises the value_is_literal guard and NOT
        // the Expr walk. This one cannot lower (the var_* accessor with a literal name), so the
        // literal reaches collectOperandRefs(Expr, …) — the arm a `\\$\\w+` scan would get wrong.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-20"},
                  "Check": {"expression":
                    "var_label(\\"AESTDTC\\", \\"DEFINE\\") == \\"$notanop\\""}
                }
                """.replaceAll("\\s*\\R\\s*", " "));
        assertTrue(rule.getCheck() instanceof CheckConditionExpression,
                "fixture must exercise the expression arm, got " + rule.getCheck().getClass());
        assertNull(rule.getLoadError(), "a string literal is not an operand reference");
        assertNull(rule.getLoadWarning());
    }


    @Test
    void anExplicitValueIsLiteral_isNotCaught() throws IOException
    {
        // The leaf-form twin of the case above: with the flag set, CheckToExpr emits a STRING
        // literal, so the leading `$` is just a character.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-12"},
                  "Check": {"all": [{"name": "AETERM", "operator": "equal_to",
                                     "value": "$notanop", "value_is_literal": true}]}
                }
                """);
        assertNull(rule.getLoadError());
        assertNull(rule.getLoadWarning());
    }


    @Test
    void anOperandSubstitutionTemplateIsNotAnOperationReference() throws IOException
    {
        // Fix #37's `${VAR[:fmt]}` templates are owned by OperandSubstitutor and appear mid-name
        // in 42 shipped Check expressions (`ADSL.TRT${APERIOD:%02d}P`, 170 occurrences). They are
        // not operation ids and must not be reported here.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-13"},
                  "Check": {"all": [{"name": "TRT${APERIOD:%02d}P", "operator": "non_empty"}]}
                }
                """);
        assertNull(rule.getLoadError());
        assertNull(rule.getLoadWarning());
    }


    @Test
    void aWholeNameSubstitutionTemplateIsNotAnOperationReference() throws IOException
    {
        // The leading-`${` case: no shipped rule places a template at position 0 today, but the
        // sigil test alone would read one as an operation id. It is a template either way, and
        // validateOperandSubstitution — not this pass — is what judges it.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-19"},
                  "Check": {"all": [{"name": "${APERIOD:%02d}", "operator": "non_empty"}]}
                }
                """);
        assertNull(rule.getLoadError());
        assertNull(rule.getLoadWarning());
    }

    // -----------------------------------------------------------------------
    // The native-expression Check shape
    // -----------------------------------------------------------------------


    @Test
    void aDanglingRefInAnUnloweredNativeExpression_isCaught() throws IOException
    {
        // A `var_*` metadata accessor with an arbitrary-literal name has no legacy operator
        // surface, so ExprLowering cannot lower it and the Check stays a CheckConditionExpression —
        // reached through the Expr arm of the walk rather than the leaf arm.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-14"},
                  "Check": {"expression":
                    "var_label(\\"AESTDTC\\", \\"DEFINE\\") == \\"Start Date\\"
                     and not empty($never_defined)"}
                }
                """.replaceAll("\\s*\\R\\s*", " "));
        assertTrue(rule.getCheck() instanceof CheckConditionExpression,
                "fixture must exercise the expression arm, got " + rule.getCheck().getClass());
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("$never_defined"), rule.getLoadError());
    }


    @Test
    void anInlineOperationCallCannotDangle() throws IOException
    {
        // An operation authored inline in the Check carries its own declaration and has no `$`-id
        // to reference, so it never appears on either side of the subtraction.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-15"},
                  "Check": {"expression": "AESTDTC == min_date(AESTDTC)"}
                }
                """);
        assertNull(rule.getLoadError());
        assertNull(rule.getLoadWarning());
    }

    // -----------------------------------------------------------------------
    // Channel discipline
    // -----------------------------------------------------------------------


    @Test
    void aPreexistingLoadErrorIsPreserved_andThisOneAppended() throws IOException
    {
        // validateOperandSubstitution runs BEFORE this pass; overwriting its finding would lose
        // the first diagnosis.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-16"},
                  "Check": {"all": [{"name": "PH${*}SDT", "operator": "equal_to", "value": "X"},
                                    {"name": "$never_defined", "operator": "non_empty"}]}
                }
                """);
        assertNotNull(rule.getLoadError());
        int operandIdx = rule.getLoadError().indexOf("PH${*}SDT");
        int danglingIdx = rule.getLoadError().indexOf("$never_defined");
        assertTrue(operandIdx >= 0, "operand-substitution error kept: " + rule.getLoadError());
        assertTrue(danglingIdx >= 0, "dangling-ref error appended: " + rule.getLoadError());
        assertTrue(operandIdx < danglingIdx, "pre-existing error first: " + rule.getLoadError());
        assertTrue(rule.getLoadError().contains("; "), "joined with `; `");
    }


    @Test
    void aPreexistingLoadWarningIsPreserved_whileThisFindingTakesTheErrorChannel()
        throws IOException
    {
        // The domain-wildcard-prefix gate (validateEnumFields) warns before this pass. The two
        // channels are independent: the warning must survive untouched on getLoadWarning()
        // while the dangling-ref finding lands on getLoadError(). ⚠ Since Fix #159 there is no
        // Executability that would move this finding onto the warning channel — a rule declaring
        // "Not Executable" is parked and never reaches either gate. (The frame-compatibility
        // warning this test used to lean on died with phase 2 of
        // PLAN-leaf-scope-domain-inference.md.)
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-17"},
                  "Scope": {"Domains": {"Include": ["AE--"]}},
                  "Check": {"all": [{"name": "AETERM", "operator": "empty"},
                                    {"name": "$never_defined", "operator": "non_empty"}]}
                }
                """);
        assertNotNull(rule.getLoadWarning());
        assertTrue(rule.getLoadWarning().contains("2-character wildcard prefix"),
                "the wildcard-prefix warning kept on its own channel: " + rule.getLoadWarning());
        assertFalse(rule.getLoadWarning().contains("$never_defined"),
                "the dangling ref does not leak onto the warning channel: "
                        + rule.getLoadWarning());
        assertNotNull(rule.getLoadError());
        assertTrue(rule.getLoadError().contains("$never_defined"), rule.getLoadError());
        assertTrue(rule.getLoadError().contains(DANGLES), rule.getLoadError());
    }

    // -----------------------------------------------------------------------
    // The invariant this pass leans on: an inliner drops the operation and rewrites the Check
    // -----------------------------------------------------------------------


    @Test
    void inlinedOperationsAreDroppedInLockstepWithTheCheck() throws IOException
    {
        // installNativeExpr rewrites `$op == true/false` into var_exists(<col>), DROPS the inlined
        // operation from getOperations() — and rewrites rule.getCheck() to the inlined expression
        // in the same step (RulePackageLoader, the setCheck in inlineVariableExistsOps). That
        // lockstep is why this pass reads correctly on either side of retainNativeExpr. An inliner
        // that dropped the operation WITHOUT rewriting the tree would leave a `$`-ref this pass is
        // right to report — so if this assertion ever breaks, the inliner is the thing to fix.
        Rule rule = load("""
                {
                  "Core": {"Id": "TEST-DOR-18"},
                  "Operations": [{"id": "$ae_present", "operator": "variable_exists",
                                  "name": "AETERM"}],
                  "Check": {"all": [{"name": "$ae_present", "operator": "equal_to",
                                     "value": false}]}
                }
                """);
        assertNull(rule.getOperations(), "the inlined operation is dropped");
        assertTrue(rule.getCheck() instanceof CheckConditionExpression,
                "and the Check is rewritten with it, got " + rule.getCheck().getClass());
        assertEquals("not var_exists(\"AETERM\")",
                ((CheckConditionExpression) rule.getCheck()).source(),
                "no `$ae_present` survives in the tree");
        assertNull(rule.getLoadError());
        assertNull(rule.getLoadWarning());
    }
}
