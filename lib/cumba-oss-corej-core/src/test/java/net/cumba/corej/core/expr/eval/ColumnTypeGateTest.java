package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
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
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

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
        // F1: num() parses per cell; the "abc" and blank cells are MISSING — and NEVER an
        // escalation to a rule error.
        assertEquals(bits(1), eval("num(DOSE) > 10", c));
        // ⭐ Phase 6c (D117/D34 #5): a MissingValue sorts BELOW every non-missing value, so the
        // two missing cells (rows 2 and 3) fire under `<= 5` where before the flip they were
        // silent. Row 0 still comes from the `== 10` arm — equality is untouched by the order
        // flip, which is what makes this expression a two-sided witness.
        assertEquals(bits(0, 2, 3), eval("num(DOSE) <= 5 or num(DOSE) == 10", c));
        assertEquals(bits(2, 3), eval("num(DOSE) <= 5", c), "the order arm alone");
        assertEquals(bits(0), eval("num(DOSE) == 10", c), "the equality arm alone, unmoved");
    }


    @Test
    @DisplayName("==/!= vs a numeric literal: raw Char errors, num() keeps the F2 verdicts")
    void equalityAgainstNumericLiteralGatesChar()
    {
        EvaluationContext c = ctxOf(table());
        assertThrows(ColumnTypeMismatchException.class, () -> eval("DOSE == 10", c));
        assertThrows(ColumnTypeMismatchException.class, () -> eval("DOSE != 10", c));
        // §4b F2 pins: num(X) == 10 and num(X) == "10" (a string RHS is parsed as a number).
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
        // The num()-on-one-side authoring, `num(--STRESC) != --STRESN` (its shipped carrier was
        // retired with the CORE family, 2026-09-19). Rows 0/1 parse and agree (no
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
        // First-class arithmetic (phase 3d): every operand position gates — the compared
        // column through the equality gate's static NUMERIC side, the dividend and divisor
        // through the arithmetic plan's own numeric-read gate.
        assertThrows(ColumnTypeMismatchException.class, () -> eval("AVAL != BASE / DOSE", c));
        assertThrows(ColumnTypeMismatchException.class, () -> eval("AVAL != DOSE / BASE", c));
        assertThrows(ColumnTypeMismatchException.class, () -> eval("DOSE != BASE / AVAL", c));
        // The length operand of has_equal_length is a numeric read too (Primitives.integral).
        assertThrows(ColumnTypeMismatchException.class,
                () -> eval("has_equal_length(DOSE, DOSE)", c));
        assertEquals(bits(0, 1), eval("abs(num(DOSE)) >= 10", c));
        assertEquals(bits(0, 1), eval("between(num(DOSE), 5, 30)", c));
        // num() inside an arithmetic operand: rows 0/1 differ (10 != 10/10, 25 != 20/25) and
        // fire. Row 2 fires since phase 3d: num("abc") is a computed missing that propagates
        // into the quotient (D36 #8/D86a) and the present AVAL never equals it. Row 3 stays
        // silent: AVAL and the num("")-poisoned quotient are BOTH missing, and the equality
        // fold answers equal.
        assertEquals(bits(0, 1, 2), eval("AVAL != BASE / num(DOSE)", c));
    }


    @Test
    @DisplayName("prefix/suffix n and substring start/length gate a Char column whatever the data")
    void lengthArgumentsGateCharEvenWhenEveryOtherOperandIsMissing()
    {
        // The gate is a function of the VECTOR (its declared type), so it must not depend on the
        // rows: with X missing on EVERY row, a string producer takes its D36 early return (the
        // missing X's own cell) before it ever reads n / start / length — a per-row gate inside
        // integral() would then never run, and a Char length column would pass silently on
        // exactly the data that hides the defect. Hoisted to vector construction, it errors
        // regardless of the data (as between/abs/round/floor/ceil already did).
        IDataTable t = MissingCellTables.of("LB").str("X", MissingValue.MIS, MissingValue.MIS_A)
                .str("NCHAR", "2", "3").build();
        EvaluationContext c = ctxOf(t);
        // assertAll: each spelling is judged and reported on its own.
        assertAll(List
                .of("prefix(X, NCHAR) == \"a\"", "suffix(X, NCHAR) == \"a\"",
                        "substring(X, NCHAR) == \"a\"", "substring(X, 1, NCHAR) == \"a\"")
                .stream()
                .map(expr -> (Executable) () -> assertThrows(ColumnTypeMismatchException.class,
                        () -> eval(expr, c), expr)));
        // Control: the num() authoring satisfies the gate over the same all-missing data — the
        // throw above is the gate, not the fixture — and a missing X never equals "a".
        assertEquals(new BitSet(), eval("prefix(X, num(NCHAR)) == \"a\"", c));
        assertEquals(new BitSet(), eval("substring(X, 1, num(NCHAR)) == \"a\"", c));
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
        // ⭐ Row 3 (AVAL's null LONG cell) joined the firing set with D12: `X != ""` is TRUE for a
        // MissingValue, because a missing equals NO present value and "" is a present value. The
        // gate question this test asks — does value() gate? — is unchanged; only the count moved.
        assertEquals(bits(0, 1, 2, 3), eval("value() != \"\"", c));
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
        assertEquals(10.0, x.value(0).resolved(), "value position resolves the parsed Double");
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

    // ---- ECT-Q5 ⓐ / B5 S12: an all-empty column has no type and is not gated ----


    /** DOSE Char populated, AVAL Num populated, EMPTYC Char all-blank, EMPTYN Num all-missing. */
    private static IDataTable withEmptyColumns()
    {
        return MockTable.of().name("EX").col("DOSE", "10", "25", "abc", "")
                .colLong("AVAL", 10L, 25L, 1L, null).col("EMPTYC", "", "", "", "")
                .colLong("EMPTYN", null, null, null, null).build();
    }


    /**
     * {@code ECT-Q5} ⓐ (owner 2026-10-16, built here as B5 S12 / T14): a column whose every cell is
     * {@code ""} or missing ({@code ColumnContent.isBlank()}) is exempt from all three gate checks
     * — its declared type is the provider's guess (CSV types an all-blank column Char, XLSX Num), a
     * property of the transport, not of the data; the ERROR it raised hid the (rule, dataset)'s
     * other findings. A populated column of the wrong kind still ERRORs (the controls).
     */
    @Test
    @DisplayName("S12: an all-empty column is exempt from the three gate checks")
    void anAllEmptyColumnIsNotGated()
    {
        EvaluationContext c = ctxOf(withEmptyColumns());
        // (1) a Char all-blank column in a numeric position
        assertEquals(new BitSet(), assertDoesNotThrow(() -> eval("EMPTYC > 5", c)));
        // (2) a Num all-missing column as a regex subject
        assertEquals(new BitSet(), assertDoesNotThrow(() -> eval("EMPTYN =~ /^\\d+$/", c)));
        // (3) Char/Num equality with an empty column on EITHER side
        assertDoesNotThrow(() -> eval("EMPTYN == DOSE", c));
        assertDoesNotThrow(() -> eval("AVAL != EMPTYC", c));
        assertDoesNotThrow(() -> eval("EMPTYC == 10", c),
                "an empty Char against a numeric literal");
        assertDoesNotThrow(() -> eval("EMPTYN != \"0\"", c),
                "an empty Num against a string literal");
        // controls: the populated columns keep every gate (R3, R9, R12)
        assertThrows(ColumnTypeMismatchException.class, () -> eval("DOSE > 5", c));
        assertThrows(ColumnTypeMismatchException.class, () -> eval("AVAL =~ /^\\d+$/", c));
        assertThrows(ColumnTypeMismatchException.class, () -> eval("AVAL != DOSE", c));
    }


    /**
     * T14 (M5): the gate reaches a JOINED column through a {@code ComputedVector}, whose blankness
     * is answered from the <b>source</b> column — never from the merged cells, where an unmatched
     * row carries the type default (D72a-1) and a populated source with no matched row would read
     * blank.
     */
    @Test
    @DisplayName("S12: a joined all-blank source column is exempt; an unmatched populated one is not")
    void aJoinedAllBlankSourceColumnIsNotGated()
    {
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "U1").colLong("AGE", 30L).build();
        IDataTable blankDm = MockTable.of().name("DM").col("USUBJID", "U1").col("ARM", "").build();
        EvaluationContext blank = joinedCtx(ae, blankDm);
        assertEquals(new BitSet(), assertDoesNotThrow(() -> eval("DM.ARM > 5", blank)));
        assertDoesNotThrow(() -> eval("DM.ARM == AGE", blank));
        // control: ARM populated on a subject AE does not carry — no merged row matched, every
        // merged ARM cell is the type default — is NOT blank at the source: still an ERROR
        IDataTable unmatchedDm = MockTable.of().name("DM").col("USUBJID", "U9")
                .col("ARM", "PLACEBO").build();
        EvaluationContext unmatched = joinedCtx(ae, unmatchedDm);
        assertThrows(ColumnTypeMismatchException.class, () -> eval("DM.ARM > 5", unmatched));
    }


    /** T14 (3): a remapped vector answers its inner vector's blankness, as it answers its name. */
    @Test
    @DisplayName("S12: a remapped vector over an all-blank column is exempt")
    void aRemappedVectorOverAnAllBlankColumnIsNotGated()
    {
        IDataTable t = withEmptyColumns();
        int emptyc = t.getMetaData().getColumnIndex("EMPTYC");
        int dose = t.getMetaData().getColumnIndex("DOSE");
        Vector blank = ComputedVector.remapped(
                new ColumnVector("EMPTYC", t.getColumn(emptyc), DataValueType.STRING), 4, r -> r);
        assertDoesNotThrow(() -> ColumnTypeGate.requireNumericRead(blank, "a numeric position"));
        Vector populated = ComputedVector.remapped(
                new ColumnVector("DOSE", t.getColumn(dose), DataValueType.STRING), 4, r -> r);
        assertThrows(ColumnTypeMismatchException.class,
                () -> ColumnTypeGate.requireNumericRead(populated, "a numeric position"));
    }


    private static EvaluationContext joinedCtx(IDataTable primary, IDataTable dm)
    {
        net.cumba.corej.core.exec.JoinLookup lk = net.cumba.corej.core.exec.ExecCalls
                .viewLookup(primary, "DM", dm, List.of("USUBJID"));
        net.cumba.corej.core.exec.DatasetResolver res = n -> "DM".equals(n) ? dm : null;
        return EvaluationContext.builder().table(primary).datasetResolver(res)
                .mergedView(net.cumba.corej.core.exec.ExecCalls.joinsView(Map.of("DM", lk)))
                .build();
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
        RuleExecutionResult error = RuleRunnerCalls.execute(r, charDose, _ -> charDose);
        assertEquals(RuleExecutionStatus.ERROR, error.getStatus());
        assertEquals(1, error.getViolationCount(), "one __error__ sentinel violation");
        String sentinel = error.getViolations().getFirst().getValues().get("__error__");
        assertTrue(sentinel.startsWith("column-type mismatch: "), sentinel);
        assertTrue(sentinel.contains("DOSE") && sentinel.contains("Char"), sentinel);

        // Dataset B ships DOSE correctly as Num → the very same Rule object executes normally.
        IDataTable numDose = MockTable.of().name("EX").colLong("DOSE", 10L, 3L).build();
        RuleExecutionResult ok = RuleRunnerCalls.execute(r, numDose, _ -> numDose);
        assertEquals(RuleExecutionStatus.EXECUTED, ok.getStatus());
        assertEquals(1, ok.getViolationCount(), "row 0 (10 > 5) fires");
    }


    @Test
    @DisplayName("R10 mirror: a num() rule stays a native expression")
    void numRuleStaysOnTheNativeEvaluator() throws Exception
    {
        Rule r = load("num(DOSE) > 5");
        assertNull(r.getLoadError());
        assertInstanceOf(CheckConditionExpression.class, r.getCheck(),
                "the rule must keep its native expression — nothing may silently strip num()"
                        + " (historically the v1 lowering refused it; the model is now retired)");
        IDataTable charDose = MockTable.of().name("EX").col("DOSE", "10", "3", "abc").build();
        RuleExecutionResult res = RuleRunnerCalls.execute(r, charDose, _ -> charDose);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus());
        assertEquals(1, res.getViolationCount(), "only the parsing 10 fires; abc is missing (F1)");
    }

}
