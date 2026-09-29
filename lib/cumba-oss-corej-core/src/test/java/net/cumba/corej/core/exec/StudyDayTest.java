package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.DataValues;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.DataValueMissing;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code dy(name, reference)} — wave 1's per-record port with a declared join
 * ({@code PLAN-function-surface-wave1} phase 2). The function-level cases pin the retired
 * {@code calculateStudyDay} arithmetic and the D36 missing hand-through; the rule-level cases pin
 * what the port changes: the reference is read through the declared join on every row copy, so with
 * a duplicated {@code DM.USUBJID} the binding agrees with the Check (ONE finding where the
 * operation's subject map gave TWO — the engine-level twin of
 * {@code CDISC-CG0006-invalid_duplicate_dm_usubjid-AE.cdt}); and the negative controls of R1 and
 * R6: one argument is an arity load error, a quoted reference — positional or {@code reference=} —
 * fails to load as a literal where a column is required. Mockito-free: real tables from the
 * testkit, real vectors.
 */
class StudyDayTest
{

    // ------------------------------------------------------------------ the arithmetic

    @Test
    void theReferenceDayIsDayOneAndThereIsNoDayZero()
    {
        Vector dates = strings("2020-01-15", "2020-01-14", "2020-01-16", "2020-02-14");
        Vector reference = strings("2020-01-15", "2020-01-15", "2020-01-15", "2020-01-15");
        List<Object> days = resolved(
                StudyDay.evaluate(EvalRun.ofRowCount(4), List.of(dates, reference)));
        assertEquals(List.of(1L, -1L, 2L, 31L), days,
                "on the reference date is day 1, the day before is day -1, no day 0");
    }


    @Test
    void aTimePartIsIgnoredAndAShortOrUnparsableSideIsMissing()
    {
        // Rows 1-4 are SHORT (never parsed); rows 5-6 are ten characters long and reach
        // LocalDate.parse, which throws — the DateTimeParseException branch (2020-02-30 is a
        // well-formed impossible date, abcdefghij is not a date at all).
        Vector dates = strings("2020-01-16T10:30", "2020-01", "abc", "", "2020-01-16", "2020-02-30",
                "abcdefghij");
        Vector reference = strings("2020-01-15", "2020-01-15", "2020-01-15", "2020-01-15", "2020",
                "2020-01-15", "2020-01-15");
        Vector out = StudyDay.evaluate(EvalRun.ofRowCount(7), List.of(dates, reference));
        assertEquals(2L, out.value(0).resolved(), "only the yyyy-MM-dd prefix counts");
        for (int row = 1; row < 7; row++)
        {
            assertSame(MissingValue.MIS, out.value(row).missing(),
                    "row " + row + " cannot be computed: every input is present, so it is the"
                            + " computed missing MIS (D36 #8), never null");
        }
    }


    @Test
    void aMissingInputCellIsMissingAndTheDayRendersAsAWholeNumber()
    {
        Vector dates = strings("2020-01-20", MissingValue.MIS);
        Vector reference = strings("2020-01-15", "2020-01-15");
        Vector out = StudyDay.evaluate(EvalRun.ofRowCount(2), List.of(dates, reference));
        assertEquals("6", out.value(0).cell().getValueAsString(),
                "the day renders as the retired operation's Long did — 6, not 6.0");
        assertSame(MissingValue.MIS, out.value(1).missing());
    }


    /**
     * D85c / D86a ({@code PLAN-missing-identity-nonstring-functions}): a missing input's own cell
     * is the answer — identity kept, two distinct identities collapse to {@code MIS} — and the
     * identity is decided <b>before</b> the parse, as {@code arithmeticCell} and {@code substring}
     * do: with {@code X = .A}, {@code dy(X, R)} is {@code .A} although {@code R} holds a short
     * date. Only an all-present but short or unparsable input gives the computed {@code MIS}.
     */
    @Test
    void aMissingInputKeepsItsIdentityDecidedBeforeTheParse()
    {
        MissingValue a = MissingValue.MIS_A;
        MissingValue b = MissingValue.MIS_B;
        Vector dates = strings(a, a, "2020-01-20", a, MissingValue.MIS, "2020-01", b);
        Vector reference = strings("2020", b, b, a, "2020-01-15", a, "2020-01-15");
        Vector out = StudyDay.evaluate(EvalRun.ofRowCount(7), List.of(dates, reference));
        assertSame(a, out.value(0).missing(), "dy(.A, \"2020\") is .A — identity before parse");
        assertSame(MissingValue.MIS, out.value(1).missing(), "dy(.A, .B) is MIS (D86a)");
        assertSame(b, out.value(2).missing(), "dy(date, .B) is .B — the reference's own missing");
        assertSame(a, out.value(3).missing(), "dy(.A, .A) is .A — one distinct identity");
        assertSame(MissingValue.MIS, out.value(4).missing(), "dy(MIS, date) is MIS");
        assertSame(a, out.value(5).missing(), "dy(\"2020-01\", .A) is .A — before the parse");
        assertSame(b, out.value(6).missing(), "dy(.B, date) is .B");
    }

    // ------------------------------------------------------------------ the rule surface


    @Test
    void theBindingReadsTheMatchedDmRecordOnEveryRowCopy() throws Exception
    {
        // DM carries S1 twice with different RFSTDTC; the inner join row-multiplies the one AE
        // record (D32). AEDY=10 is right against the first DM row only, so exactly ONE copy fires.
        // The retired operation's last-non-missing-wins subject map gave day 6 to both copies.
        Rule rule = loadClean("AEDY != $dy", "$dy", "dy(AEDTC, DM.RFSTDTC)");
        IDataTable ae = RealTables.of("AE").str("USUBJID", "S1").str("AEDTC", "2020-01-10")
                .lng("AEDY", 10L).build();
        IDataTable dm = RealTables.of("DM").str("USUBJID", "S1", "S1")
                .str("RFSTDTC", "2020-01-01", "2020-01-05").build();
        RuleExecutionResult result = run(rule, ae, dm);
        assertEquals(1, result.getViolations().size(),
                "the copy paired with the second DM row computes day 6 and fires; the other agrees");
        assertEquals("6", result.getViolations().get(0).getValues().get("$dy"));
    }


    @Test
    void everyRowGetsItsOwnDayEvenWhenRowsShareASubject() throws Exception
    {
        // The retired GroupedResult keyed the day by (USUBJID, date); rows 0 and 2 share that key
        // and, being the same subject and date, computed the same day, so the collision could not
        // change an answer there. This pins the vector path's per-row independence: every row is
        // computed on its own, with nothing keyed to share.
        Rule rule = loadClean("$dy < 0", List.of("$dy"), "$dy", "dy(VSDTC, DM.RFSTDTC)");
        IDataTable vs = RealTables.of("VS").str("USUBJID", "S1", "S1", "S1")
                .str("VSDTC", "2019-12-30", "2020-01-01", "2019-12-30").build();
        IDataTable dm = RealTables.of("DM").str("USUBJID", "S1").str("RFSTDTC", "2020-01-01")
                .build();
        RuleExecutionResult result = run(rule, vs, dm);
        assertEquals(2, result.getViolations().size(), "rows 0 and 2 are day -2, row 1 is day 1");
        assertEquals("-2", result.getViolations().get(0).getValues().get("$dy"));
    }


    @Test
    void theKeywordFormIsTheSameBinding() throws Exception
    {
        Rule positional = loadClean("AEDY != $dy", "$dy", "dy(AEDTC, DM.RFXSTDTC)");
        Rule keyword = loadClean("AEDY != $dy", "$dy", "dy(AEDTC, reference=DM.RFXSTDTC)");
        IDataTable ae = RealTables.of("AE").str("USUBJID", "S1", "S1")
                .str("AEDTC", "2020-01-10", "2020-01-10").lng("AEDY", 6L, 3L).build();
        IDataTable dm = RealTables.of("DM").str("USUBJID", "S1").str("RFSTDTC", "2020-01-05")
                .str("RFXSTDTC", "2020-01-08").build();
        // Either reference yields exactly ONE finding (row 0 against RFXSTDTC, row 1 against
        // RFSTDTC), so the count alone cannot tell them apart: the row and the $dy value can.
        for (Rule rule : List.of(positional, keyword))
        {
            List<Violation> violations = run(rule, ae, dm).getViolations();
            assertEquals(1, violations.size(), "D9: one binding");
            assertEquals(0L, violations.get(0).getRow(),
                    "row 0 (AEDY=6) fires against RFXSTDTC; row 1 (AEDY=3) would fire against"
                            + " RFSTDTC");
            assertEquals("3", violations.get(0).getValues().get("$dy"),
                    "day 3 against RFXSTDTC, never day 6 against RFSTDTC");
        }
    }

    // ------------------------------------------------------------------ negative controls


    @Test
    void oneArgumentIsAnArityLoadError() throws Exception
    {
        // R6 / P-Q8: the retired operation silently defaulted a missing reference to RFSTDTC.
        String error = loadError("AEDY != $dy", "$dy", "dy(AEDTC)");
        assertTrue(error.contains("reference"),
                "the load error names the missing parameter: " + error);
    }


    @Test
    void aQuotedReferenceFailsToLoadPositionallyAndAsAKeyword() throws Exception
    {
        // R1: a quoted "RFXSTDTC" is a STRING literal, never a column — the release note's
        // retired spelling `reference="RFXSTDTC"` must fail loudly, not compute against a constant.
        for (String spelling : List.of("dy(AEDTC, \\\"RFXSTDTC\\\")",
                "dy(AEDTC, reference=\\\"RFXSTDTC\\\")"))
        {
            String error = loadError("AEDY != $dy", "$dy", spelling);
            assertTrue(error.contains("column reference") && error.contains("RFXSTDTC"), spelling
                    + " must fail on the literal-where-a-column-is-required seam: " + error);
        }
    }

    // ------------------------------------------------------------------ helpers


    /** A typed STRING vector; each cell a {@link String} or a {@link MissingValue} (any one). */
    private static Vector strings(Object... cells)
    {
        return ComputedVector.typed(cells.length, DataValueType.STRING,
                row -> cells[row] instanceof MissingValue mv ? new DataValueMissing(mv)
                        : DataValues.of(cells[row]));
    }


    private static List<Object> resolved(Vector v)
    {
        java.util.ArrayList<Object> out = new java.util.ArrayList<>();
        for (int row = 0; row < 4; row++)
        {
            out.add(v.value(row).resolved());
        }
        return out;
    }


    private static Rule load(String check, List<String> outputs, String... bindings)
        throws Exception
    {
        StringBuilder declared = new StringBuilder();
        for (int i = 0; i < bindings.length; i += 2)
        {
            if (i > 0)
            {
                declared.append(',');
            }
            declared.append("{\"name\":\"").append(bindings[i]).append("\",\"expression\":\"")
                    .append(bindings[i + 1]).append("\"}");
        }
        StringBuilder outs = new StringBuilder();
        for (String o : outputs)
        {
            if (!outs.isEmpty())
            {
                outs.append(',');
            }
            outs.append('"').append(o).append('"');
        }
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"W1-DY\"},"
                + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}],"
                + "\"Requirements\":{\"Variables\":{\"All\":[\"USUBJID\"],"
                + "\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]}}," + "\"Bindings\":[" + declared
                + "]," + "\"Check\":{\"expression\":\"" + check + "\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[" + outs + "]}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(rule, "the rule loads");
        return rule;
    }


    private static Rule loadClean(String check, String... bindings) throws Exception
    {
        return loadClean(check, List.of("$dy"), bindings);
    }


    private static Rule loadClean(String check, List<String> outputs, String... bindings)
        throws Exception
    {
        Rule rule = load(check, outputs, bindings);
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    private static String loadError(String check, String... bindings) throws Exception
    {
        Rule rule = load(check, List.of("$dy"), bindings);
        assertNotNull(rule.getLoadError(), "expected a load error for " + bindings[1]);
        return rule.getLoadError();
    }


    private static RuleExecutionResult run(Rule rule, IDataTable primary, IDataTable dm)
    {
        // An inventory-bearing resolver: the join-key gate (All_Or_None [USUBJID, DM.USUBJID])
        // must be able to decide that DM exists before the rule may run.
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, primary,
                RealTables.inventoryOf(primary, dm), null, null, null, null, Integer.MAX_VALUE,
                null, null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result;
    }

}
