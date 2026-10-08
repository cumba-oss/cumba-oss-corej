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


    @Test
    void aMissingCellIsKeptAtItsPositionAndSortsLast()
    {
        List<List<?>> seq = rows("sequence(SEDY, group=[USUBJID], by=[asc(SESEQ)])", se());
        assertEquals(List.of("1", "10", "32"), seq.get(0), "numeric cells as their canonical text");
        assertEquals(3, seq.get(3).size());
        assertInstanceOf(Primitives.MissingMember.class, seq.get(3).get(1),
                "the missing cell keeps its position (D81)");
        assertEquals(List.of("SCRN", "TRT", "FU", "SCRN", "TRT", "FU", "SCRN"),
                rows("sequence(ETCD, by=[asc(USUBJID), asc(SESTDTC)])", se()).get(0),
                "a missing / blank key sorts last; a second key breaks ties");
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
