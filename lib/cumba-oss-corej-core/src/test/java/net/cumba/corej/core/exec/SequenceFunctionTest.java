package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.ConstVector;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.Primitives;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W1b RO ({@code PLAN-engine-gap-row-ordinal} §5 R1, §6.1 Q1 / Q3): {@code sequence(name,
 * domain=, filter=, group=, by=[asc(COL), desc(COL)], keep_missings=)} — per row, its group's
 * values in sort-key order, duplicates kept, a missing cell at its position; ties keep file order;
 * no {@code by=} is file order; {@code asc} / {@code desc} honoured and ISO cells ordered by the
 * date rule; the sibling of {@code distinct(name, group=)} on the same grouped plan, so
 * {@code domain=}, {@code filter=}, an absent group column (EC-44), the no-group row (the empty
 * list) and the sided {@code group=} member (R3) behave as there.
 */
class SequenceFunctionTest
{

    static IDataTable se()
    {
        // S2's file order is SCRN, FU, TRT; its date order SCRN, TRT, FU. S3 has no date.
        return RealTableFixture.of("SE").str("DOMAIN", "SE", "SE", "SE", "SE", "SE", "SE", "SE")
                .str("USUBJID", "S1", "S1", "S1", "S2", "S2", "S2", "S3")
                .dbl("SESEQ", 1.0, 2.0, 3.0, 1.0, 2.0, 3.0, 1.0)
                .str("ETCD", "SCRN", "TRT", "FU", "SCRN", "FU", "TRT", "SCRN")
                .str("SESTDTC", "2020-01-01", "2020-01-10", "2020-02-01", "2020-03-01",
                        "2020-03-05", "2020-03-02", "")
                // (a LONG column: the OSS datatable refuses a null DOUBLE cell, both accept a
                // null LONG one as the missing)
                .lng("SEDY", 1L, 10L, 32L, 1L, null, 2L, null).build();
    }


    static IDataTable ta()
    {
        return RealTableFixture.of("TA").str("DOMAIN", "TA", "TA", "TA", "TA", "TA")
                .str("ARMCD", "A1", "A1", "A1", "A2", "A2").dbl("TAETORD", 1.0, 2.0, 3.0, 1.0, 2.0)
                .str("ETCD", "SCRN", "TRT", "FU", "SCRN", "FU")
                .str("EPOCH", "SCREENING", "TREATMENT", "FOLLOW-UP", "SCREENING", "FOLLOW-UP")
                .build();
    }


    static IDataTable dm()
    {
        return RealTableFixture.of("DM").str("DOMAIN", "DM", "DM", "DM", "DM")
                .str("USUBJID", "S1", "S2", "S3", "S4").str("ACTARMCD", "A1", "A2", "A1", "A9")
                .build();
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    static Rule load(String check, String... bindings)
    {
        StringBuilder b = new StringBuilder("{\"rules\":{\"R\":{\"Core\":{\"Id\":\"R\"}");
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
        b.append(",\"Check\":{\"expression\":\"").append(esc(check)).append(
                "\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}");
        try
        {
            Rule r = RulePackageLoader.loadFromString(b.toString()).getRules().get("R");
            assertNotNull(r);
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    /** A rule with bindings and no Check — stage A never walks it (the loader's seams judge). */
    static Rule loadCheckless(String... bindings)
    {
        StringBuilder b = new StringBuilder(
                "{\"rules\":{\"R\":{\"Core\":{\"Id\":\"R\"},\"Bindings\":[");
        for (int i = 0; i < bindings.length; i += 2)
        {
            b.append(i == 0 ? "" : ",").append("{\"name\":\"").append(bindings[i])
                    .append("\",\"expression\":\"").append(esc(bindings[i + 1])).append("\"}");
        }
        b.append("],\"Outcome\":{\"Message\":\"m\"}}}}");
        try
        {
            Rule r = RulePackageLoader.loadFromString(b.toString()).getRules().get("R");
            assertNotNull(r);
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    static String loadError(String check, String... bindings)
    {
        return String.valueOf(load(check, bindings).getLoadError());
    }


    static RuleExecutionResult run(IDataTable primary, String check, String... bindings)
    {
        Rule r = load(check, bindings);
        assertNull(r.getLoadError(), () -> check + " -> " + r.getLoadError());
        return RuleRunnerCalls.execute(r, primary,
                RealTables.inventoryOf(primary, se(), ta(), dm()), primary.getMetaData().getName(),
                null);
    }


    static List<Long> fired(IDataTable primary, String check, String... bindings)
    {
        RuleExecutionResult result = run(primary, check, bindings);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " -> " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    static List<List<?>> rows(String call, IDataTable table)
    {
        EvaluationContext ctx = EvaluationContext.builder().table(table).build();
        Vector v = ExprCompiler.evaluateValueExpression(CheckExpressionParser.parse(call), ctx);
        assertNotNull(v, call);
        List<List<?>> out = new ArrayList<>();
        for (int row = 0; row < ctx.rowCount(); row++)
        {
            out.add((List<?>) v.value(row).resolved());
        }
        return out;
    }


    @Test
    void ordersEachGroupByTheKeysNotByFilePosition()
    {
        List<List<?>> byDate = rows("sequence(ETCD, group=[USUBJID], by=[asc(SESTDTC)])", se());
        assertEquals(List.of("SCRN", "TRT", "FU"), byDate.get(0));
        assertEquals(List.of("SCRN", "TRT", "FU"), byDate.get(3), "S2: date order, not file order");
        assertEquals(List.of("SCRN"), byDate.get(6));
        assertEquals(byDate.get(0), byDate.get(1), "every row of a group reads its group's list");
        List<List<?>> desc = rows("sequence(ETCD, group=[USUBJID], by=[desc(SESTDTC)])", se());
        assertEquals(List.of("FU", "TRT", "SCRN"), desc.get(0), "desc is honoured (Q3)");
        assertEquals(List.of("SCRN", "FU", "TRT"),
                rows("sequence(ETCD, group=[USUBJID], by=[asc(SESEQ)])", se()).get(3),
                "a numeric key orders numerically");
    }


    /**
     * SM-shaped rows for the documented {@code is_increasing} example: S1's HYPO2 has a year-only
     * start, after HYPO1's 2020-06-15; S2's HYPO1 starts 2021-03, after HYPO2's year 2020 — S2
     * numbers its intervals against the chronology.
     */
    static IDataTable sm()
    {
        return RealTableFixture.of("SM").str("DOMAIN", "SM", "SM", "SM", "SM")
                .str("USUBJID", "S1", "S1", "S2", "S2").str("MIDSTYPE", "H", "H", "H", "H")
                .str("MIDS", "HYPO1", "HYPO2", "HYPO1", "HYPO2")
                .str("SMSTDTC", "2020-06-15", "2021", "2021-03", "2020")
                .str("SMSEQ", "9", "10", "2", "1").build();
    }


    @Test
    void aSortKeyIsClassifiedOverItsCellsAYearAmongDatesIsAYear()
    {
        // R5 A-M1, owner O22 (a): CellOrder classifies PER SORT KEY — numeric iff every present
        // cell of the key is a number (D11's rule), else ISO-then-text with a 4-digit cell read
        // as a year. A cell-by-cell class put "2021" (a number) before every date
        assertEquals(List.of("HYPO1", "HYPO2"),
                rows("sequence(MIDS, group=[USUBJID], by=[asc(SMSTDTC)])", sm()).get(0),
                "2020-06-15 before the year 2021 in an ISO key");
        assertEquals(List.of("HYPO2", "HYPO1"),
                rows("sequence(MIDS, group=[USUBJID], by=[asc(SMSTDTC)])", sm()).get(2),
                "the year 2020 before 2021-03");
        assertEquals(List.of("HYPO2", "HYPO1"),
                rows("sequence(MIDS, group=[USUBJID], by=[desc(SMSTDTC)])", sm()).get(0));
        // an all-number key orders numerically ("9" before "10"), never as text
        assertEquals(List.of("HYPO1", "HYPO2"),
                rows("sequence(MIDS, group=[USUBJID], by=[asc(SMSEQ)])", sm()).get(0));
        // the documented example (functions.md, is_increasing): only S2 numbers against time.
        // The digits are extracted inside sequence's VALUE target and stay text — is_increasing
        // reads them numerically, every element being a number. (The example's former
        // num($mids ~ …) did not load — num() over a list is no element-wise conversion before
        // W2 E5 — and a ~ over the bound list in the Check's operand leaves the classifier no
        // column, so the findings collapse to one.)
        assertEquals(List.of(3L, 4L),
                fired(sm(), "not is_increasing($nums)", "$nums",
                        "sequence(MIDS ~ /^.*?([0-9]+)$/$1/, group=[USUBJID, MIDSTYPE],"
                                + " by=[asc(SMSTDTC)], filter=(MIDS =~ /[0-9]$/))"));
    }


    @Test
    void noKeysAndTiesKeepFileOrderDuplicatesKept()
    {
        assertEquals(List.of("SCRN", "FU", "TRT"),
                rows("sequence(ETCD, group=[USUBJID])", se()).get(3), "no by= is file order");
        assertEquals(List.of("SCRN", "FU", "TRT"),
                rows("sequence(ETCD, group=[USUBJID], by=[asc(USUBJID)])", se()).get(3),
                "a tie keeps file order (stable)");
        assertEquals(List.of("FU", "SCRN", "SCRN", "TRT"),
                rows("sequence(ETCD, by=[asc(ETCD)], filter=(USUBJID != " + "\"S2\"" + "))", se())
                        .get(0),
                "duplicates are kept");
    }


    /**
     * Runbook W2 fold (R5 PC-1, coordinator — O16 "nulls= and direction honoured" in both readers):
     * {@code sequence(by=)} places a blank or missing key cell by the key's {@code nulls=} with
     * {@code is_sorted_by}'s default — FIRST, under {@code asc} and {@code desc} alike (W2-D3), a
     * blank and a missing tied in file order — instead of after every value reversed by
     * {@code desc}. S3's SESTDTC is blank; SEDY is missing on rows 5 and 7.
     */
    @Test
    void aBlankOrMissingKeyIsPlacedByNullsFirstByDefaultUnderDescToo()
    {
        assertEquals(List.of("SCRN", "SCRN", "TRT", "FU", "SCRN", "TRT", "FU"),
                rows("sequence(ETCD, by=[asc(SESTDTC)])", se()).get(0), "first by default");
        assertEquals(List.of("SCRN", "FU", "TRT", "SCRN", "FU", "TRT", "SCRN"),
                rows("sequence(ETCD, by=[desc(SESTDTC)])", se()).get(0), "first under desc");
        assertEquals(List.of("SCRN", "TRT", "FU", "SCRN", "TRT", "FU", "SCRN"),
                rows("sequence(ETCD, by=[asc(SESTDTC, nulls=\"last\")])", se()).get(0));
        assertEquals(List.of("FU", "TRT", "SCRN", "FU", "TRT", "SCRN", "SCRN"),
                rows("sequence(ETCD, by=[desc(SESTDTC, nulls=\"last\")])", se()).get(0),
                "last under desc too: the placement is the key's, never the direction's");
        assertEquals(List.of("FU", "SCRN", "SCRN", "SCRN", "TRT", "TRT", "FU"),
                rows("sequence(ETCD, by=[asc(SEDY)])", se()).get(0),
                "two missing cells first, tied in file order");
        assertEquals(List.of("FU", "SCRN", "SCRN", "SCRN", "TRT", "TRT", "FU"),
                rows("sequence(ETCD, by=[asc(SEDY, nulls=\"first\")])", se()).get(0));
    }


    @Test
    void aMissingCellIsKeptAtItsPosition()
    {
        List<List<?>> seq = rows("sequence(SEDY, group=[USUBJID], by=[asc(SESEQ)])", se());
        assertEquals(List.of("1", "10", "32"), seq.get(0), "numeric cells as their canonical text");
        assertEquals(3, seq.get(3).size());
        assertInstanceOf(Primitives.MissingMember.class, seq.get(3).get(1),
                "the missing cell keeps its position (D81)");
        assertEquals(List.of("SCRN", "TRT", "FU", "SCRN", "TRT", "FU", "SCRN"),
                rows("sequence(ETCD, by=[asc(USUBJID), asc(SESTDTC)])", se()).get(0),
                "a second key breaks ties");
        Vector ungrouped = ExprCompiler.evaluateValueExpression(
                CheckExpressionParser.parse("sequence(ETCD, by=[asc(SESTDTC)])"),
                EvaluationContext.builder().table(se()).build());
        assertInstanceOf(ConstVector.class, ungrouped, "an ungrouped sequence is one list");
    }


    @Test
    void filterAndAnAbsentGroupColumnBehaveAsDistinctsDo()
    {
        assertEquals(List.of("TRT", "FU"), rows(
                "sequence(ETCD, group=[USUBJID], by=[asc(SESTDTC)], filter=(ETCD != \"SCRN\"))",
                se()).get(0));
        assertEquals(rows("sequence(ETCD, group=[USUBJID], by=[asc(SESTDTC)])", se()),
                rows("sequence(ETCD, group=[USUBJID, SITEID], by=[asc(SESTDTC)])", se()),
                "EC-44: an absent group column partitions nothing");
    }


    @Test
    void aSidedGroupMemberKeysTheOtherTableByThisRowsColumn()
    {
        // R3: this DM row's ACTARMCD keys TA's ARMCD groups; S4's arm has no TA rows
        String ta = "sequence(ETCD, domain=\"TA\", group=[{left: ACTARMCD, right: ARMCD}],"
                + " by=[asc(TAETORD)])";
        String se = "sequence(ETCD, domain=\"SE\", group=[USUBJID], by=[asc(SESTDTC)])";
        assertEquals(List.of(2L), fired(dm(), "not is_prefix_of($se, $ta)", "$se", se, "$ta", ta),
                "the flagship rule A: S2 passed SCRN, TRT, FU but arm A2 plans SCRN, FU");
        assertEquals(List.of(4L), fired(dm(), "empty($ta)", "$ta", ta),
                "a row whose key has no group reads the empty list");
        assertEquals(List.of(4L), fired(dm(), "count($ta) == 0", "$ta", ta));
    }


    @Test
    void theStrictReaderRefusesTheShapesThePlanCannotMean()
    {
        assertTrue(loadError("count(sequence([ETCD, SESEQ], group=[USUBJID])) > 0")
                .contains("sequence"), "a list target");
        assertTrue(loadError("count(sequence(ETCD, keep_missings=false)) > 0")
                .contains("keep_missings"), "keep_missings without a group");
        assertTrue(loadError("count(sequence(\"ETCD\", group=[USUBJID])) > 0").contains("string"),
                "a quoted target is a string (R1)");
        assertTrue(loadError("count(sequence(ETCD, group=[USUBJID], by=[upper(ETCD)])) > 0")
                .contains("cannot splice the per-row member"), "a per-row sort key");
        assertEquals("null",
                loadError("count(sequence(ETCD, group=[USUBJID], by=[asc(SESTDTC)])) > 0"));
    }
}
