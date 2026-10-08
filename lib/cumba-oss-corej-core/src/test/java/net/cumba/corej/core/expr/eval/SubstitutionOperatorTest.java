package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * The substitution operator {@code X ~ /pattern/replacement/}
 * ({@code PLAN-engine-gap-string-extract} §3, owner-ruled 2026-10-07; runbook W1b SX): a
 * <b>search</b> for the first match ({@code find}, as {@code =~}), the first match replaced with
 * Java {@code Matcher} replacement syntax, a STRING result; <b>no match answers missing</b> (never
 * the input — {@code coalesce(X ~ /a/b/, X)} is the replace-or-keep form); a missing subject stays
 * missing; a numeric subject is the {@code =~} error; {@code (?i)} inside the pattern passes
 * through; over a list subject the operator applies to every element (the element-wise lift). The
 * parser gives {@code ~} the tightest binding: a postfix on a primary, under arithmetic and
 * comparison, left-associative.
 */
class SubstitutionOperatorTest
{

    private static Expr parse(String source)
    {
        return CheckExpressionParser.parse(source);
    }


    private static EvaluationContext ctx(IDataTable t)
    {
        return EvaluationContext.builder().table(t).datasetResolver(_ -> null).domainName("AE")
                .build();
    }


    private static EvaluationContext ctx(IDataTable t, Map<String, Object> variables)
    {
        return EvaluationContext.builder().table(t).datasetResolver(_ -> null).domainName("AE")
                .variables(variables).build();
    }


    private static BitSet eval(String source, IDataTable t)
    {
        return NativeExprEvaluator.evaluate(parse(source), ctx(t));
    }

    // ------------------------------------------------------------------ the parser


    @Test
    void theOperatorIsAPostfixOnAPrimaryUnderArithmeticAndComparison()
    {
        Expr.Binary eq = assertInstanceOf(Expr.Binary.class, parse("X ~ /a/b/ == \"b\""));
        assertEquals(Expr.BinOp.EQ, eq.op());
        Expr.Binary subst = assertInstanceOf(Expr.Binary.class, eq.left());
        assertEquals(Expr.BinOp.SUBST, subst.op());
        Expr.Lit lit = assertInstanceOf(Expr.Lit.class, subst.right());
        assertEquals(Expr.LitKind.SUBSTITUTION, lit.kind());
        assertEquals(new Expr.Substitution("a", "b"), lit.value());

        Expr.Binary add = assertInstanceOf(Expr.Binary.class,
                ((Expr.Binary) parse("X ~ /a/b/ + 1 == 2")).left());
        assertEquals(Expr.BinOp.ADD, add.op());
        assertEquals(Expr.BinOp.SUBST, ((Expr.Binary) add.left()).op());

        Expr.Binary chained = (Expr.Binary) ((Expr.Binary) parse("X ~ /a/b/ ~ /b/c/ == \"c\""))
                .left();
        assertEquals(Expr.BinOp.SUBST, chained.op());
        assertEquals(Expr.BinOp.SUBST, ((Expr.Binary) chained.left()).op(), "left-associative");

        Expr.Binary grouped = (Expr.Binary) ((Expr.Binary) parse("(A + B) ~ /x/y/ == \"z\""))
                .left();
        assertEquals(Expr.BinOp.SUBST, grouped.op());
        assertEquals(Expr.BinOp.ADD, ((Expr.Binary) grouped.left()).op());
    }


    @Test
    void theRightSideMustBeASubstitutionLiteral()
    {
        ExpressionException string = assertThrows(ExpressionException.class,
                () -> parse("X ~ \"a\" == \"b\""));
        assertTrue(string.getMessage().contains("/pattern/replacement/"), string.getMessage());
        assertThrows(ExpressionException.class, () -> parse("X ~ Y == \"b\""));
        assertThrows(ExpressionException.class, () -> parse("X ~ (Y) == \"b\""));
        assertThrows(ExpressionException.class, () -> parse("X ~ /a/ == \"b\""),
                "a regex literal without the replacement part");
        assertThrows(ExpressionException.class, () -> parse("X ~"));
    }


    @Test
    void thePrinterRoundTripsTheOperatorBare()
    {
        for (String text : List.of("X ~ /a/b/ == \"b\"", "num(X ~ /^.*?([0-9]+)$/$1/) > 0",
                "X ~ /a/b/ ~ /b/c/ == \"c\"", "(A + B) ~ /x/y/ == \"z\"",
                "not empty(X ~ /\\\\d+\\//\\\\$\\//)"))
        {
            assertEquals(text, ExpressionPrinter.print(parse(text)));
        }
    }

    // ------------------------------------------------------------------ evaluation


    @Test
    void anAnchoredPatternExtractsAndNoMatchIsMissing()
    {
        IDataTable t = MockTable.of().col("X", "AESTDTC", "aestdtc", "AETERM").build();
        BitSet extracted = eval("X ~ /(?i)^(.*)DTC$/$1/ == \"AEST\"", t);
        assertTrue(extracted.get(0));
        assertFalse(extracted.get(1), "(?i) matches, the capture keeps the lower case");
        assertTrue(eval("X ~ /(?i)^(.*)DTC$/$1/ == \"aest\"", t).get(1));
        assertFalse(extracted.get(2));
        BitSet missing = eval("empty(X ~ /(?i)^(.*)DTC$/$1/)", t);
        assertFalse(missing.get(0));
        assertTrue(missing.get(2), "no match answers missing, never the input");
        assertFalse(eval("X ~ /(?i)^(.*)DTC$/$1/ == \"AETERM\"", t).get(2),
                "no match: the input is NOT returned");
        assertFalse(eval("X ~ /^(.*)DTC$/$1/ == \"aest\"", t).get(1),
                "without (?i) the lower-case value does not match");
    }


    @Test
    void anUnanchoredPatternReplacesTheFirstMatchOnly()
    {
        IDataTable t = MockTable.of().col("X", "a-b-c", "abc").build();
        assertTrue(eval("X ~ /-/ / == \"a b-c\"", t).get(0), "first match only, rest kept");
        assertTrue(eval("empty(X ~ /-/ /)", t).get(1));
        assertTrue(eval("coalesce(X ~ /-/ /, X) == \"abc\"", t).get(1),
                "replace-or-keep is spelled with coalesce");
        assertTrue(eval("coalesce(X ~ /-/ /, X) == \"a b-c\"", t).get(0));
        assertTrue(eval("X ~ /b// == \"a--c\"", t).get(0), "an empty replacement deletes");
        assertTrue(eval("X ~ /(a)-(b)/$2-$1/ == \"b-a-c\"", t).get(0), "groups reorder");
        assertTrue(eval("X ~ /(?<first>a)/${first}${first}/ == \"aa-b-c\"", t).get(0),
                "named groups");
        assertTrue(eval("X ~ /a/\\$1/ == \"$1-b-c\"", t).get(0), "an escaped $ is literal");
        assertTrue(eval("X ~ /a/\\//== \"/-b-c\"", t).get(0), "an escaped / is literal");
    }


    @Test
    void theExtractFeedsNumAndChains()
    {
        IDataTable t = MockTable.of().col("MIDS", "HYPO3", "HYPO12", "SCREEN").build();
        assertTrue(eval("num(MIDS ~ /^.*?([0-9]+)$/$1/) == 3", t).get(0));
        assertTrue(eval("num(MIDS ~ /^.*?([0-9]+)$/$1/) == 12", t).get(1));
        assertTrue(eval("empty(num(MIDS ~ /^.*?([0-9]+)$/$1/))", t).get(2));
        assertTrue(eval("MIDS ~ /^HYPO/H/ ~ /3/THREE/ == \"HTHREE\"", t).get(0), "chained");
        assertTrue(eval("empty(MIDS ~ /^HYPO/H/ ~ /3/THREE/)", t).get(1),
                "a chain's second no-match is missing");
        assertTrue(eval("empty(MIDS ~ /^HYPO/H/ ~ /3/THREE/)", t).get(2),
                "a missing subject (the first no-match) stays missing through the chain");
    }


    @Test
    void aMissingSubjectStaysMissing()
    {
        IDataTable t = MockTable.of().col("X", "abc", (String) null, "").build();
        assertFalse(eval("empty(X ~ /b/x/)", t).get(0));
        assertTrue(eval("empty(X ~ /b/x/)", t).get(1));
        assertFalse(eval("X ~ /.*/x/ == \"x\"", t).get(1), "a missing is not matched by .*");
        assertTrue(eval("X ~ /^$/empty/ == \"empty\"", t).get(2),
                "a present empty string is a value and matches ^$");
    }


    @Test
    void aNumericSubjectIsTheRegexMatchError()
    {
        IDataTable t = MockTable.of().colDouble("N", 1.0).build();
        assertThrows(ColumnTypeMismatchException.class, () -> eval("N ~ /1/x/ == \"x\"", t));
        assertThrows(ColumnTypeMismatchException.class, () -> eval("N =~ /1/", t),
                "the same gate as the match operators");
    }


    @Test
    void aListSubjectIsSubstitutedElementWise()
    {
        IDataTable t = MockTable.of().col("X", "AESTDTC,AETERM,AEENDTC", (String) null).build();
        assertTrue(eval("\"AEST\" in split_by(X, \",\") ~ /^(.*)DTC$/$1/", t).get(0));
        assertTrue(eval("\"AEEN\" in split_by(X, \",\") ~ /^(.*)DTC$/$1/", t).get(0));
        assertFalse(eval("\"AETERM\" in split_by(X, \",\") ~ /^(.*)DTC$/$1/", t).get(0),
                "the no-match element is missing at its position, not the input");
        assertTrue(eval("count(split_by(X, \",\") ~ /^(.*)DTC$/$1/) == 3", t).get(0),
                "the list keeps its length");
        assertTrue(eval("\"AEST\" in values([X, Y]) ~ /^(.*)DTC$/$1/",
                MockTable.of().col("X", "AESTDTC").col("Y", "AETERM").build()).get(0));
        // a dataset-level list binding, broadcast
        List<String> names = List.of("AESTDTC", "AEENDTC", "AETERM");
        EvaluationContext bound = ctx(MockTable.of().col("X", "a").build(),
                Map.of("$names", names));
        assertTrue(NativeExprEvaluator.evaluate(parse("\"AEEN\" in $names ~ /^(.*)DTC$/$1/"), bound)
                .get(0));
        assertFalse(NativeExprEvaluator
                .evaluate(parse("\"AETERM\" in $names ~ /^(.*)DTC$/$1/"), bound).get(0));
        assertTrue(NativeExprEvaluator.evaluate(parse("count($names ~ /^(.*)DTC$/$1/) == 3"), bound)
                .get(0));
    }
}
