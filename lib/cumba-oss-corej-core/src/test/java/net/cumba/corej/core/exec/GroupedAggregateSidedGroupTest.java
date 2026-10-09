package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.SequenceFunctionTest.dm;
import static net.cumba.corej.core.exec.SequenceFunctionTest.fired;
import static net.cumba.corej.core.exec.SequenceFunctionTest.loadError;
import static net.cumba.corej.core.exec.SequenceFunctionTest.run;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W1b RO ({@code PLAN-engine-gap-row-ordinal} §5 R3, ruled Q2 (a); coordinator D9): the
 * sided {@code group=} member {@code {left: X, right: Y}} — this row's {@code X} keys the
 * {@code domain=} table's {@code Y} groups — a dedicated literal of the expression grammar, legal
 * only as a {@code group=} member of a {@code domain=} grouped call ({@code max} / {@code max_date}
 * / {@code min_date}, {@code read_value}, {@code distinct}, {@code record_count},
 * {@code sequence}), a load error anywhere else, never spliced; the cross-table key-type check (GKI
 * Q2) applied per side; the record side read on the primary, never dropped.
 */
class GroupedAggregateSidedGroupTest
{

    private static final String SIDED = "group=[{left: ACTARMCD, right: ARMCD}]";

    @Test
    void theGroupedReadersKeyTheOtherTableByThisRowsColumn()
    {
        assertEquals(List.of(1L, 3L),
                fired(dm(), "$m == 3", "$m", "max(TAETORD, domain=\"TA\", " + SIDED + ")"),
                "max: S1 and S3 are on A1 (three elements), S2 on A2, S4 on no arm");
        assertEquals(List.of(4L),
                fired(dm(), "empty($m)", "$m", "max(TAETORD, domain=\"TA\", " + SIDED + ")"));
        assertEquals(List.of(2L, 4L),
                fired(dm(), "not contains($d, \"TRT\")", "$d",
                        "distinct(ETCD, domain=\"TA\", " + SIDED + ")"),
                "distinct: A2 plans no TRT; S4 reads the empty list, which `not contains` fires on too");
        assertEquals(List.of(2L, 4L),
                fired(dm(), "$n != 3", "$n", "record_count(domain=\"TA\", " + SIDED + ")"));
        assertEquals(List.of(1L, 3L),
                fired(dm(), "$v == \"FU\"", "$v", "read_value(ETCD, domain=\"TA\", " + SIDED
                        + ", filter=(TAETORD == 3)," + " mode=\"ONLY\")"));
        assertEquals(fired(dm(), "$m == 3", "$m", "max(TAETORD, domain=\"TA\", " + SIDED + ")"),
                fired(dm(), "$m == 3", "$m",
                        "max(TAETORD, domain=\"TA\", group=[{right: ARMCD, left: ACTARMCD}])"),
                "the two keys may be written in either order");
    }


    @Test
    void theSidedMemberIsLegalOnlyAsAGroupMemberUnderDomain()
    {
        assertTrue(loadError("$m == 3", "$m", "max(TAETORD, " + SIDED + ")").contains("domain="),
                "no domain=: the two sides would name the same table");
        assertTrue(loadError("ACTARMCD in [{left: ACTARMCD, right: ARMCD}]").contains("left"),
                "a sided key is no list element");
        assertTrue(loadError("{left: ACTARMCD, right: ARMCD} == \"A1\"").contains("left"),
                "nor an operand");
        assertTrue(loadError("$m == 3", "$m",
                "max(TAETORD, domain=\"TA\", group=[{left: --ARMCD, right: ARMCD}])")
                        .contains("--"),
                "a `--` side");
        assertTrue(loadError("$m == 3", "$m",
                "max(TAETORD, domain=\"TA\", group=[{left: DM.ARMCD, right: ARMCD}])")
                        .contains("DM.ARMCD"),
                "a qualified side");
        assertTrue(loadError("$m == 3", "$m",
                "max(TAETORD, domain=\"TA\", group=[{left: $x, right: ARMCD}])").contains("$x"),
                "a bound side is never spliced");
        assertTrue(loadError("is_last_in_group(by=AESEQ, group=[{left: A, right: B}])")
                .contains("left"), "a splice slot without domain= refuses it");
    }


    @Test
    void aCharNumKeyPairBetweenTheSidesErrorsTheRule()
    {
        IDataTable numeric = RealTableFixture.of("DM").str("DOMAIN", "DM", "DM")
                .str("USUBJID", "S1", "S2").dbl("ACTARMCD", 1.0, 2.0).build();
        RuleExecutionResult result = run(numeric, "$m == 3", "$m",
                "max(TAETORD, domain=\"TA\", " + SIDED + ")");
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(), "GKI Q2, per side");
        assertTrue(String.valueOf(result.getStatusMessage()).contains("ARMCD"),
                result.getStatusMessage());
        IDataTable noLeft = RealTableFixture.of("DM").str("DOMAIN", "DM").str("USUBJID", "S1")
                .build();
        RuleExecutionResult absent = run(noLeft, "$m == 3", "$m",
                "max(TAETORD, domain=\"TA\", " + SIDED + ")");
        assertEquals(RuleExecutionStatus.ERROR, absent.getStatus(),
                "the record side is never dropped: an absent left column is the rule's ERROR");
    }


    @Test
    void theLiteralParsesAndPrintsRoundTrip()
    {
        String text = "max(TAETORD, domain=\"TA\", group=[{left: ACTARMCD, right: ARMCD}])";
        Expr parsed = CheckExpressionParser.parse(text);
        assertEquals(text, ExpressionPrinter.print(parsed));
        Expr.Call call = (Expr.Call) parsed;
        Expr.Lit group = (Expr.Lit) call.kwargs().get("group");
        Expr member = ((List<?>) group.value()).stream().map(Expr.class::cast).findFirst()
                .orElseThrow();
        assertEquals(Expr.LitKind.SIDED, ((Expr.Lit) member).kind());
        assertEquals(text,
                ExpressionPrinter.print(CheckExpressionParser.parse(
                        "max(TAETORD, domain=\"TA\", group=[{right: ARMCD, left: ACTARMCD}])")),
                "printed in the canonical left, right order");
    }
}
