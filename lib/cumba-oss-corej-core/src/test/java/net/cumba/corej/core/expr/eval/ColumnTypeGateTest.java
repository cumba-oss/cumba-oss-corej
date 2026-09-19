package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PLAN-column-type-conformance Phases 1′ and 3 — the {@code num()} conversion (R2/R10) and the
 * column-type mismatch gate (R4/R5/R8/R9/R12), including every §10 F9 exemption and the §4b F1
 * per-cell soft-failure pin.
 */
class ColumnTypeGateTest
{

    private static BitSet eval(String expr, EvaluationContext ctx)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }


    private static EvaluationContext ctxOf(IDataTable t)
    {
        return EvaluationContext.builder().table(t).build();
    }


    private static BitSet bits(int... set)
    {
        BitSet b = new BitSet();
        for (int i : set)
        {
            b.set(i);
        }
        return b;
    }


    /** DOSE Char (mixed content, the --STRESC shape); AVAL / BASE Num. */
    private static IDataTable table()
    {
        // colLong, not colDouble: MockTable's DOUBLE cells render raw.toString() ("10.0"),
        // while LONG cells render the canonical "10" a real numeric buffer produces — the string-
        // form assertions below (str(), $-list membership) depend on the canonical form.
        return MockTable.of().name("EX").col("DOSE", "10", "25", "abc", "")
                .colLong("AVAL", 10L, 25L, 1L, null).colLong("BASE", 10L, 20L, 1L, 2L).build();
    }

    // ---- direction 1: Char where numeric is expected (R3/R4) --------------------


    @Test
    @DisplayName("order comparison: a raw Char operand errors; num() satisfies, per-cell soft")
    void orderComparisonGatesCharAndNumSatisfies()
    {
        EvaluationContext c = ctxOf(table());
        ColumnTypeMismatchException ex = assertThrows(ColumnTypeMismatchException.class,
                () -> eval("DOSE > 5", c));
        // R4: the message names the variable, its declared type, and what was expected.
        assertTrue(ex.getMessage().contains("DOSE"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Char"), ex.getMessage());
        assertTrue(ex.getMessage().contains("numeric"), ex.getMessage());
        assertTrue(ex.getMessage().contains("num(DOSE)"), ex.getMessage());
        // F1: num() parses per cell; the "abc" and blank cells are MISSING — no violation, and
        // NEVER an escalation to a rule error.
        assertEquals(bits(1), eval("num(DOSE) > 10", c));
        assertEquals(bits(0), eval("num(DOSE) <= 5 or num(DOSE) == 10", c));
    }


    @Test
    @DisplayName("==/!= vs a numeric literal: raw Char errors, num() keeps the F2 verdicts")
    void equalityAgainstNumericLiteralGatesChar()
    {
        EvaluationContext c = ctxOf(table());
        assertThrows(ColumnTypeMismatchException.class, () -> eval("DOSE == 10", c));
        assertThrows(ColumnTypeMismatchException.class, () -> eval("DOSE != 10", c));
        // §4b F2 pins: num(X) == 10 and num(X) == "10" (string RHS parses via
        // comparisonTargetAsDouble).
        assertEquals(bits(0), eval("num(DOSE) == 10", c));
        assertEquals(bits(0), eval("num(DOSE) == \"10\"", c));
        // The unparseable cell is missing → folds to "" → != fires (the pre-R10 verdict).
        assertEquals(bits(1, 2, 3), eval("num(DOSE) != 10", c));
    }


    @Test
    @DisplayName("Char column vs Num column: the untyped comparison errors naming BOTH types")
    void columnPairMismatchNamesBothTypes()
    {
        EvaluationContext c = ctxOf(table());
        ColumnTypeMismatchException ex = assertThrows(ColumnTypeMismatchException.class,
                () -> eval("AVAL != DOSE", c));
        assertTrue(ex.getMessage().contains("AVAL"), ex.getMessage());
        assertTrue(ex.getMessage().contains("DOSE"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Num"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Char"), ex.getMessage());
        // The FDA-SD1212 / PMDA-SD1212 authoring: num(--STRESC) != --STRESN. Rows 0/1 parse and
        // agree (no
        // fire); row 2's "abc" is missing → "" vs "1" → fires; row 3 is missing on BOTH sides →
        // "" == "" → no fire (the both-missing contract).
        assertEquals(bits(2), eval("num(DOSE) != AVAL", c));
    }


    @Test
    @DisplayName("numeric-list membership: raw Char probe errors; num() probes per cell")
    void numericMembershipGatesChar()
    {
        EvaluationContext c = ctxOf(table());
        assertThrows(ColumnTypeMismatchException.class, () -> eval("DOSE in [10, 25]", c));
        assertThrows(ColumnTypeMismatchException.class, () -> eval("DOSE not in [10, 25]", c));
        assertEquals(bits(0, 1), eval("num(DOSE) in [10, 25]", c));
        assertEquals(bits(2, 3), eval("num(DOSE) not in [10, 25]", c));
    }


    @Test
    @DisplayName("numeric function args and arithmetic: raw Char errors, num() satisfies")
    void numericFunctionsAndArithmeticGateChar()
    {
        EvaluationContext c = ctxOf(table());
        assertThrows(ColumnTypeMismatchException.class, () -> eval("abs(DOSE) == 10", c));
        assertThrows(ColumnTypeMismatchException.class, () -> eval("between(DOSE, 5, 30)", c));
        // Only the != arithmetic shapes are native (compileArithmeticNotEqual); == falls back.
        // All three operand positions gate — name, dividend, divisor.
        assertThrows(ColumnTypeMismatchException.class, () -> eval("AVAL != BASE / DOSE", c));
        assertThrows(ColumnTypeMismatchException.class, () -> eval("AVAL != DOSE / BASE", c));
        assertThrows(ColumnTypeMismatchException.class, () -> eval("DOSE != BASE / AVAL", c));
        // The length operand of has_equal_length is a numeric read too (Primitives.integral).
        assertThrows(ColumnTypeMismatchException.class,
                () -> eval("has_equal_length(DOSE, DOSE)", c));
        assertEquals(bits(0, 1), eval("abs(num(DOSE)) >= 10", c));
        assertEquals(bits(0, 1), eval("between(num(DOSE), 5, 30)", c));
        // num() inside an arithmetic operand: rows 0/1 differ (10 != 10/10, 25 != 20/25) and
        // fire; rows 2/3 have a missing term (num("abc"), missing AVAL) and are skipped.
        assertEquals(bits(0, 1), eval("AVAL != BASE / num(DOSE)", c));
    }

    // ---- direction 2: Num where character is expected (R9/R12) ------------------


    @Test
    @DisplayName("regex subject: a Num column errors — its text form is formatting-dependent")
    void regexSubjectGatesNumericColumn()
    {
        EvaluationContext c = ctxOf(table());
        ColumnTypeMismatchException ex = assertThrows(ColumnTypeMismatchException.class,
                () -> eval("AVAL !~ /^\\d+$/", c));
        assertTrue(ex.getMessage().contains("AVAL"), ex.getMessage());
        assertTrue(ex.getMessage().contains("Num"), ex.getMessage());
        assertTrue(ex.getMessage().contains("character"), ex.getMessage());
        // A Char subject stays a perfectly legal regex read.
        assertEquals(bits(2), eval("not empty(DOSE) and DOSE !~ /^\\d+$/", c));
    }


    @Test
    @DisplayName("string-literal membership: a Num probe errors; dynamic sets stay ungated")
    void stringListMembershipGatesNumericColumn()
    {
        EvaluationContext c = ctxOf(table());
        assertThrows(ColumnTypeMismatchException.class, () -> eval("AVAL in [\"10\", \"25\"]", c));
        // A $-list is dynamic — its member types are not statically known, so no gate (F9): the
        // textual membership evaluates ("10"/"25" string forms match rows 0 and 1).
        EvaluationContext withVar = EvaluationContext.builder().table(table())
                .variables(Map.of("$ref", List.of("10", "25"))).build();
        assertEquals(bits(0, 1), eval("AVAL in $ref", withVar));
    }


    @Test
    @DisplayName("Num column vs string literal (the TDSTOFF != \"0\" family) errors — R12")
    void numericColumnVsStringLiteralErrors()
    {
        EvaluationContext c = ctxOf(table());
        assertThrows(ColumnTypeMismatchException.class, () -> eval("AVAL != \"0\"", c));
        assertEquals(bits(1, 2), eval("AVAL != 10 and non_empty(AVAL)", c));
    }

    // ---- §10 F9: where the gate must stay silent --------------------------------


    @Test
    @DisplayName("F9: an ABSENT column never gates — EC-38 all-missing verdicts are untouched")
    void absentColumnDoesNotGate()
    {
        // NODOSE resolves to no column: the fold makes it an all-missing column, and the
        // enclosing predicates keep their polarity — never a type error.
        EvaluationContext c = ctxOf(table());
        assertEquals(new BitSet(), eval("NODOSE > 5", c));
        assertEquals(new BitSet(), eval("NODOSE == 10", c));
        assertEquals(bits(0, 1, 2, 3), eval("NODOSE != 10", c), "NEQ fires on all-missing");
        assertEquals(bits(0, 1, 2, 3), eval("NODOSE not in [10, 25]", c));
        // ⚠ Accepted, documented gap (§10 F9): the gate is BLIND on absent columns — the same
        // expression that errors when DOSE is present evaluates silently when it is absent.
    }


    @Test
    @DisplayName("F9: $-refs and the type-insensitive / case-insensitive surfaces never gate")
    void dollarRefsAndExplicitSurfacesDoNotGate()
    {
        EvaluationContext c = EvaluationContext.builder().table(table())
                .variables(Map.of("$limit", "15")).build();
        // $-ref opposite a Num column: no static kind, no gate; comparison stays numeric-aware.
        assertEquals(bits(1), eval("AVAL > $limit", c));
        // str()==str() is the explicit type-insensitive surface (kept per R12's "no char()" —
        // str() compares string FORMS by declaration, not by accident).
        assertEquals(bits(2), eval("str(AVAL) == str(\"1\")", c));
        // upper() membership is the explicit case-insensitive surface.
        assertEquals(new BitSet(), eval("upper(DOSE) in [\"X\"]", c));
    }


    @Test
    @DisplayName("F9: the value() cursor of a per-variable rule never gates")
    void valueCursorDoesNotGate()
    {
        // A generic per-variable read over a Num column: value() yields an UN-NAMED ColumnVector,
        // exempt by design — erroring it would make every wildcard rule unusable on the other
        // type.
        EvaluationContext c = EvaluationContext.builder().table(table())
                .variables(Map.of("variable_name", "AVAL")).build();
        assertEquals(bits(0, 1, 2), eval("value() != \"\"", c));
    }


    @Test
    @DisplayName("date family stays un-gated: its numeric-vs-ISO dispatch is type-directed")
    void dateFamilyUntouched()
    {
        IDataTable t = MockTable.of().name("AE").col("DTC", "2024-01-02", "2024-01-01", "").build();
        assertEquals(bits(0), eval("DTC > date(\"2024-01-01\")", ctxOf(t)));
    }


    @Test
    @DisplayName("the gated column may sit on EITHER side; kindOf gates only Char/Num types")
    void literalLeftOfColumnAndKindClassification()
    {
        EvaluationContext c = ctxOf(table());
        // The mismatch fires with the CONVERSION on the left and the raw column on the right,
        // and the message describes the un-named side by its kind.
        ColumnTypeMismatchException ex = assertThrows(ColumnTypeMismatchException.class,
                () -> eval("num(AVAL) == DOSE", c));
        assertTrue(ex.getMessage().contains("the left operand is numeric"), ex.getMessage());
        assertTrue(ex.getMessage().contains("num(DOSE)"), ex.getMessage());
        // kindOf classifies exactly Char and Num; every other declared type is outside the gate.
        assertEquals(ColumnTypeGate.Kind.CHARACTER, ColumnTypeGate.kindOf(DataValueType.STRING));
        assertEquals(ColumnTypeGate.Kind.NUMERIC, ColumnTypeGate.kindOf(DataValueType.LONG));
        assertEquals(ColumnTypeGate.Kind.NUMERIC, ColumnTypeGate.kindOf(DataValueType.DOUBLE));
        assertNull(ColumnTypeGate.kindOf(DataValueType.BOOLEAN));
        assertNull(ColumnTypeGate.kindOf(DataValueType.MISSING));
    }

    // ---- the num() conversion itself --------------------------------------------


    @Test
    @DisplayName("numConversion publishes DOUBLE and parses per cell (missing, not error)")
    void numConversionUnit()
    {
        IDataTable t = MockTable.of().col("X", "10", "2.5", "abc", "")
                .colLong("N", 7L, null, 1L, 2L).build();
        Vector x = Primitives
                .numConversion(new ColumnVector("X", t.getColumn(0), DataValueType.STRING), 4);
        assertEquals(DataValueType.DOUBLE, x.declaredType());
        assertEquals(10.0, x.asDouble(0));
        assertEquals(2.5, x.asDouble(1));
        assertTrue(x.isMissing(2), "non-numeric → missing (F1)");
        assertTrue(x.isMissing(3), "blank → missing");
        assertEquals(10.0, x.resolvedObject(0), "value position resolves the parsed Double");
        // Over an already-numeric column the conversion is a no-op value-wise.
        Vector n = Primitives
                .numConversion(new ColumnVector("N", t.getColumn(1), DataValueType.LONG), 4);
        assertEquals(7.0, n.asDouble(0));
        assertTrue(n.isMissing(1));
    }


    @Test
    @DisplayName("num() on a $-name not in context keeps the empty-BitSet contract")
    void numOverUnresolvedDollarNameStaysNull()
    {
        EvaluationContext c = ctxOf(table());
        assertEquals(new BitSet(), eval("num($notthere) == 10", c),
                "a $-name absent from the context resolves nothing — num() must not change that");
    }

    // ---- end to end: RuleRunner maps the mismatch to ERROR per dataset ----------


    private static Rule load(String check) throws java.io.IOException
    {
        String body = "{\"Core\":{\"Id\":\"CT-1\"},\"Scope\":{\"Domains\":{\"Include\":[\"EX\"]}},"
                + "\"Check\":{\"expression\":\"" + check.replace("\"", "\\\"")
                + "\"},\"Outcome\":{\"Message\":\"m\"}}";
        return RulePackageLoader.loadFromString("{\"rules\":{\"CT-1\":" + body + "}}").getRules()
                .get("CT-1");
    }


    @Test
    @DisplayName("F1: the SAME rule errors on the mis-typed dataset and executes on the good one")
    void ruleRunnerMapsMismatchToErrorPerDataset() throws Exception
    {
        Rule r = load("DOSE > 5");
        assertNull(r.getLoadError(), "the gate is per dataset execution, NEVER a load error");

        // Dataset A ships DOSE as Char → ERROR with the __error__ sentinel, message naming types.
        IDataTable charDose = MockTable.of().name("EX").col("DOSE", "10", "3").build();
        RuleExecutionResult error = RuleRunner.execute(r, charDose, _ -> charDose);
        assertEquals(RuleExecutionStatus.ERROR, error.getStatus());
        assertEquals(1, error.getViolationCount(), "one __error__ sentinel violation");
        String sentinel = error.getViolations().getFirst().getValues().get("__error__");
        assertTrue(sentinel.startsWith("column-type mismatch: "), sentinel);
        assertTrue(sentinel.contains("DOSE") && sentinel.contains("Char"), sentinel);

        // Dataset B ships DOSE correctly as Num → the very same Rule object executes normally.
        IDataTable numDose = MockTable.of().name("EX").colLong("DOSE", 10L, 3L).build();
        RuleExecutionResult ok = RuleRunner.execute(r, numDose, _ -> numDose);
        assertEquals(RuleExecutionStatus.EXECUTED, ok.getStatus());
        assertEquals(1, ok.getViolationCount(), "row 0 (10 > 5) fires");
    }


    @Test
    @DisplayName("R10 mirror: a num() rule does not lower to v1 — it stays a native expression")
    void numRuleStaysOnTheNativeEvaluator() throws Exception
    {
        Rule r = load("num(DOSE) > 5");
        assertNull(r.getLoadError());
        assertInstanceOf(CheckConditionExpression.class, r.getCheck(),
                "ExprLowering must REJECT the conversion (no v1 surface) so the rule keeps its "
                        + "native expression instead of silently stripping num()");
        IDataTable charDose = MockTable.of().name("EX").col("DOSE", "10", "3", "abc").build();
        RuleExecutionResult res = RuleRunner.execute(r, charDose, _ -> charDose);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus());
        assertEquals(1, res.getViolationCount(), "only the parsing 10 fires; abc is missing (F1)");
    }

}
