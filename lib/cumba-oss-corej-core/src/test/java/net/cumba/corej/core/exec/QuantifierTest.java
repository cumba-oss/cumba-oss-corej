package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.KeyedJoinFixtures;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.RuleDefinitionException;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.NativeExprEvaluator;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * The element-wise BOOLEAN predicates seen from a rule ({@code PLAN-elementwise-boolean-predicates}
 * Q1–Q3, P4–P8; runbook W1a EB1–EB3 + EB5): a roster predicate over {@code values([X, Y])}, over a
 * per-row list and over a dataset-level list answers a {@code list<boolean>} that {@code any} /
 * {@code all} / {@code none} fold per row; a {@code $} binding holding one is read by a quantifier;
 * a scalar argument is a singleton (P6); and every other position of a {@code list<boolean>} — a
 * condition, a comparison, a membership, a function argument, {@code Output_Variables} — fails the
 * rule's load, or (a shape visible only at run time) ERRORs the rule (P4 / P5, D35). C8's joined
 * arm is untouched ({@link JoinedQuantifierTest}, unmodified).
 */
class QuantifierTest
{

    // ------------------------------------------------------------------ fixtures

    /**
     * Four subjects. A / B: dates — row 1 both valid, row 2 A invalid B valid, row 3 both invalid,
     * row 4 A missing B valid. N / M: numbers — row 1 both numeric, row 2 N not, row 3 neither, row
     * 4 both. X: a per-row list ({@code split_by(X, ",")}) — row 1 one valid date, row 2 both
     * valid, row 3 none valid, row 4 missing.
     */
    private static IDataTable dt()
    {
        return RealTables.of("DT").str("USUBJID", "S1", "S2", "S3", "S4")
                .str("A", "2020-01-01", "2020-13-01", "x", null)
                .str("B", "2020-02-02", "2020-02-03", "2020-02-30", "2020-02-04")
                .str("N", "1", "x", "y", "2").str("M", "3", "4", "z", "5")
                .str("X", "2020-01-01,bad", "2020-01-01,2020-01-02", "bad,worse", null).build();
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private static String json(String check, List<String> outputs, String... bindings)
    {
        StringBuilder b = new StringBuilder(
                "{\"Core\":{\"Id\":\"T-EB\"},\"Sensitivity\":\"Record\"");
        if (bindings.length > 0)
        {
            b.append(",\"Bindings\":[");
            for (int i = 0; i < bindings.length; i += 2)
            {
                b.append(i == 0 ? "" : ",").append("{\"name\":\"").append(bindings[i])
                        .append("\",\"expression\":\"").append(esc(bindings[i + 1])).append("\"}");
            }
            b.append(']');
        }
        b.append(",\"Check\":{\"expression\":\"").append(esc(check))
                .append("\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[");
        for (int i = 0; i < outputs.size(); i++)
        {
            b.append(i == 0 ? "" : ",").append('"').append(esc(outputs.get(i))).append('"');
        }
        return b.append("]}}").toString();
    }


    private static Rule load(String ruleJson)
    {
        try
        {
            Rule r = RulePackageLoader
                    .loadFromString(
                            KeyedJoinFixtures.declared("{\"rules\":{\"R\":" + ruleJson + "}}"))
                    .getRules().get("R");
            assertNotNull(r);
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    private static RuleExecutionResult run(String ruleJson)
    {
        Rule r = load(ruleJson);
        assertNull(r.getLoadError(), r.getLoadError());
        IDataTable primary = dt();
        return RuleRunnerCalls.execute(r, primary, RealTables.inventoryOf(primary),
                primary.getMetaData().getName(), null);
    }


    private static List<Long> fired(String check, String... bindings)
    {
        RuleExecutionResult result = run(
                json("not empty(USUBJID) and (" + check + ")", List.of("USUBJID"), bindings));
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " → " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    private static String loadError(String check, String... bindings)
    {
        return String.valueOf(load(json(check, List.of("USUBJID"), bindings)).getLoadError());
    }

    // ------------------------------------------------------------------ the lifted argument


    @Test
    void aPredicateOverValuesIsQuantifiedPerRow()
    {
        // row 1: both valid; row 2: B valid; row 3: none; row 4: B valid (A missing)
        assertEquals(List.of(1L, 2L, 4L), fired("any(is_valid_date(values([A, B])))"));
        assertEquals(List.of(1L), fired("all(is_valid_date(values([A, B])))"));
        assertEquals(List.of(3L), fired("none(is_valid_date(values([A, B])))"));
        // the negated predicate: invalid_date fires on a blank, so row 4 has an invalid element
        assertEquals(List.of(1L), fired("none(invalid_date(values([A, B])))"));
        // under not / and / or as any condition
        assertEquals(List.of(2L, 3L, 4L), fired("not all(is_valid_date(values([A, B])))"));
    }


    @Test
    void aPerRowListIsQuantifiedElementByElementAndAMissingListIsASingleton()
    {
        // row 1: one of two valid; row 2: both; row 3: none; row 4: the missing X is the
        // predicate's own answer on the missing (false), read as a one-element list (P6)
        assertEquals(List.of(1L, 2L), fired("any(is_valid_date(split_by(X, \",\")))"));
        assertEquals(List.of(2L), fired("all(is_valid_date(split_by(X, \",\")))"));
        assertEquals(List.of(3L, 4L), fired("none(is_valid_date(split_by(X, \",\")))"));
    }


    @Test
    void aScalarArgumentIsASingleton()
    {
        // P6: any(b) = all(b) = b, none(b) = not b — exactly the scalar predicate's rows
        List<Long> valid = fired("is_valid_date(A)");
        assertEquals(List.of(1L), valid);
        assertEquals(valid, fired("any(is_valid_date(A))"));
        assertEquals(valid, fired("all(is_valid_date(A))"));
        assertEquals(List.of(2L, 3L, 4L), fired("none(is_valid_date(A))"));
    }

    // ------------------------------------------------------------------ the binding position


    @Test
    void aListBooleanBindingIsReadByAQuantifier()
    {
        // row 1: both numeric; row 2: M only; row 3: neither; row 4: both
        assertEquals(List.of(1L, 4L), fired("all($ok)", "$ok", "is_numeric(values([N, M]))"));
        assertEquals(List.of(1L, 2L, 4L), fired("any($ok)", "$ok", "is_numeric(values([N, M]))"));
        assertEquals(List.of(3L), fired("none($ok)", "$ok", "is_numeric(values([N, M]))"));
        // a binding of a binding
        assertEquals(List.of(3L),
                fired("none($ok)", "$cols", "values([N, M])", "$ok", "is_numeric($cols)"));
    }


    @Test
    void aConditionBindingIsASingleton()
    {
        // P7: the binding decides per evaluation — a scalar argument is today's condition result
        assertEquals(List.of(1L), fired("any($b)", "$b", "is_valid_date(A)"));
        assertEquals(List.of(1L), fired("$b == true", "$b", "is_valid_date(A)"));
        assertEquals(List.of(2L, 3L, 4L), fired("none($b)", "$b", "is_valid_date(A)"));
    }


    @Test
    void aDatasetLevelListIsQuantifiedOnceAndBroadcast()
    {
        // P8: $ok is one list<boolean> for the dataset — [true, false] — so any holds on every
        // row, all on none, none on none
        assertEquals(List.of(1L, 2L, 3L, 4L),
                fired("any($ok)", "$l", "[\"2020-01-01\", \"x\"]", "$ok", "is_valid_date($l)"));
        assertEquals(List.of(),
                fired("all($ok)", "$l", "[\"2020-01-01\", \"x\"]", "$ok", "is_valid_date($l)"));
        assertEquals(List.of(),
                fired("none($ok)", "$l", "[\"2020-01-01\", \"x\"]", "$ok", "is_valid_date($l)"));
        // the empty conventions (Q2): any([]) false, all([]) true, none([]) true
        assertEquals(List.of(), fired("any($ok)", "$l", "[]", "$ok", "is_valid_date($l)"));
        assertEquals(List.of(1L, 2L, 3L, 4L),
                fired("all($ok)", "$l", "[]", "$ok", "is_valid_date($l)"));
        assertEquals(List.of(1L, 2L, 3L, 4L),
                fired("none($ok)", "$l", "[]", "$ok", "is_valid_date($l)"));
    }

    // ------------------------------------------------------------------ the refusals (Q3 / P4 /
    // P5)


    @Test
    void aPredicateOverAListOutsideAQuantifierIsALoadErrorWithTheHint()
    {
        // P4, static: a condition position — the Check root, an and-operand, under not
        String bare = loadError("is_valid_date(values([A, B]))");
        assertTrue(bare.contains("any("), bare);
        assertTrue(bare.contains("one boolean per element"), bare);
        assertTrue(loadError("not empty(A) and is_valid_date(values([A, B]))").contains("any("));
        assertTrue(loadError("not is_valid_date(values([A, B]))").contains("any("));
        // Q3: compared, a membership operand, a function argument — with and without a binding
        assertTrue(loadError("is_valid_date(values([A, B])) == true").contains("any("));
        assertTrue(
                loadError("$ok == true", "$ok", "is_valid_date(values([A, B]))").contains("$ok"));
        assertTrue(
                loadError("true in $ok", "$ok", "is_valid_date(values([A, B]))").contains("$ok"));
        assertTrue(loadError("count($ok) > 1", "$ok", "is_valid_date(values([A, B]))")
                .contains("$ok"));
        // list-vs-list: ExprType.compatible(list<boolean>, list<boolean>) holds, so the refusal
        // is explicit (§3.5)
        assertTrue(loadError("$ok == $ok2", "$ok", "is_valid_date(values([A, B]))", "$ok2",
                "is_valid_date(values([B, A]))").contains("$ok"));
        // a binding reading a list<boolean> binding anywhere but a quantifier
        assertTrue(loadError("$n > 0", "$ok", "is_valid_date(values([A, B]))", "$n", "count($ok)")
                .contains("$ok"));
    }


    @Test
    void aQuantifierArgumentThatIsNotAPredicatesAnswerIsALoadError()
    {
        // P5: the cells of several columns, a string list, a lifted VALUE function's list
        assertTrue(loadError("any(values([A, B]))").contains("any("));
        assertTrue(loadError("any($s)", "$s", "[\"a\", \"b\"]").contains("any("));
        assertTrue(loadError("any(upper(values([A, B])))").contains("any("));
        // (a bare condition inside a quantifier — all(A == "x") — is the parser's refusal,
        // CheckExpressionParserBooleanArgumentTest)
        // the typed parameter: list<number> at is_valid_date's string parameter (R2's premise)
        assertTrue(loadError("any(is_valid_date($n))", "$n", "[1, 2]").contains("list<number>"));
    }


    @Test
    void aReportedListBooleanBindingIsALoadError()
    {
        String error = String.valueOf(load(
                json("any($ok)", List.of("USUBJID", "$ok"), "$ok", "is_valid_date(values([A, B]))"))
                        .getLoadError());
        assertTrue(error.contains("Output_Variables"), error);
        assertTrue(error.contains("$ok"), error);
        assertTrue(error.contains("list<boolean>"), error);
    }


    @Test
    void aBindingThatIsNeitherAListBooleanNorAConditionIsRefusedBoundOrInlineAlike()
    {
        // P5, static: a value binding is not a predicate's answer — judged on the binding's
        // expression, so `any($u)` and the inline spelling are one verdict (D3 / D16)
        String bound = loadError("any($u)", "$u", "coalesce(A, B)");
        assertTrue(bound.contains("any(…) takes one argument"), bound);
        assertTrue(loadError("any(coalesce(A, B))").contains("any(…) takes one argument"));
        assertTrue(loadError("any($k)", "$k", "values([A, B])").contains("any(…) takes"));
    }


    @Test
    void aVariableThatIsNeitherAListBooleanNorAConditionErrorsTheRuleAtRunTime()
    {
        // P5, dynamic: a raw variable (the pre-resolved hand-over form, invisible to stage A)
        // holding neither a list<boolean> nor a condition's answer — the rule ERRORs (D35)
        EvaluationContext ctx = EvaluationContext.builder().table(dt())
                .variables(Map.of("$u", List.of("2020-01-01", "x"))).build();
        RuleDefinitionException e = assertThrows(RuleDefinitionException.class,
                () -> NativeExprEvaluator.evaluate(CheckExpressionParser.parse("any($u)"), ctx));
        assertTrue(e.getMessage().contains("$u"), e.getMessage());
        assertTrue(e.getMessage().contains("any"), e.getMessage());
    }


    @Test
    void aPredicateOverAListVisibleOnlyAtRunTimeErrorsTheRule()
    {
        // P4, dynamic: a raw variable (the pre-resolved hand-over form) holding a list reaches
        // the predicate in a condition position — the same hint as the static refusal, as a
        // rule ERROR
        EvaluationContext ctx = EvaluationContext.builder().table(dt())
                .variables(Map.of("$u", List.of("2020-01-01", "x"))).build();
        RuleDefinitionException e = assertThrows(RuleDefinitionException.class,
                () -> NativeExprEvaluator.evaluate(CheckExpressionParser.parse("is_valid_date($u)"),
                        ctx));
        assertTrue(e.getMessage().contains("any("), e.getMessage());
        // the control: a scalar variable is the predicate's own answer
        EvaluationContext scalar = EvaluationContext.builder().table(dt())
                .variables(Map.of("$u", "2020-01-01")).build();
        assertEquals(4, NativeExprEvaluator
                .evaluate(CheckExpressionParser.parse("is_valid_date($u)"), scalar).cardinality());
    }


    @Test
    void aQuantifierNestedInsideJoinedStaysRefusedForTheLiftedShapeToo()
    {
        // §3.8 point 3 (runbook R2): PACK 3's refusal stands — the producer composes neither way
        ExpressionException e = assertThrows(ExpressionException.class, () -> ExprCompiler.compile(
                CheckExpressionParser.parse("none(joined(any(is_valid_date(values([A, B])))))")));
        assertTrue(e.getMessage().contains("nest"), e.getMessage());
        assertTrue(e.getMessage().contains("any"), e.getMessage());
    }


    @Test
    void anInlineConditionIsTheSingletonItsBoundSpellingIs()
    {
        // R5 review B MED-1 (coordinator decision: add the arm): stage A admits a condition at a
        // quantifier by shape (isPredicateAnswerShape), bound or inline (D16), and the compiler
        // answered only the bound spelling — the inline one had no native form. P6 both ways. A
        // bare operator condition stays the parser's refusal, so `not` is parenthesised too.
        String[] conditions =
        {
                "(A == \"x\")", "contains(A, \"13\")", "(not empty(A))"
        };
        for (String condition : conditions)
        {
            for (String quantifier : List.of("any", "all", "none"))
            {
                List<Long> bound = fired(quantifier + "($b)", "$b", condition);
                assertEquals(bound, fired(quantifier + "(" + condition + ")"),
                        quantifier + "(" + condition + ")");
            }
        }
        assertEquals(List.of(3L), fired("any((A == \"x\"))"));
        assertEquals(List.of(2L), fired("all(contains(A, \"13\"))"));
        assertEquals(List.of(4L), fired("none((not empty(A)))"));
    }


    @Test
    void aScalarPredicateBindingCompilesAsTheConditionItWasBeforeTheLift()
    {
        // R5 review B-LOW-2: a flagged predicate over a statically scalar operand can only answer
        // a condition, so its binding is the condition program (cachedBool's per-dataset cache
        // for a pure one), not the per-evaluation predicate arm; a list or a $ operand keeps the
        // arm
        assertTrue(ExprCompiler.compileBinding(CheckExpressionParser.parse("is_valid_date(A)"))
                .isCondition());
        assertTrue(ExprCompiler
                .compileBinding(CheckExpressionParser.parse("is_valid_date(values([A, B]))"))
                .isPredicate());
        assertTrue(ExprCompiler.compileBinding(CheckExpressionParser.parse("is_valid_date($l)"))
                .isPredicate());
        // and it answers as before: the condition binding's rows, quantified or compared
        assertEquals(List.of(1L), fired("any($b)", "$b", "is_valid_date(A)"));
        assertEquals(List.of(2L, 3L, 4L), fired("$b == false", "$b", "is_valid_date(A)"));
    }
}
