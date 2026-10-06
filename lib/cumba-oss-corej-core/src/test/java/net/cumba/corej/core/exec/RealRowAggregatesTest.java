package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.QualifiedNameFixture.check;
import static net.cumba.corej.core.exec.QualifiedNameFixture.exactInventory;
import static net.cumba.corej.core.exec.QualifiedNameFixture.executed;
import static net.cumba.corej.core.exec.QualifiedNameFixture.loadClean;
import static net.cumba.corej.core.exec.QualifiedNameFixture.outcome;
import static net.cumba.corej.core.exec.QualifiedNameFixture.ruleJson;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * OD-6 ({@code PLAN-merged-evaluation-table} §3.8, W3e; owner 2026-10-04): an aggregate or
 * uniqueness operator whose operands are all <b>primary</b> columns counts each primary record once
 * — by real row, never by value — on a table a one-to-many merge expanded; one that reads a merged
 * column still sees every merged row.
 */
class RealRowAggregatesTest
{

    /** {@code Match_Datasets} entry {@code name} keyed on {@code key}. */
    private static String match(String name, String key, String joinType)
    {
        return "\"Match_Datasets\":[{\"Name\":\"" + name + "\",\"Keys\":[\"" + key
                + "\"],\"Join_Type\":\"" + joinType + "\"}]";
    }


    private static RuleExecutionResult run(String match, String expression, IDataTable primary,
            IDataTable partner, String... out)
    {
        Rule r = loadClean(ruleJson("Record", match + "," + check(expression) + "," + outcome(out)),
                true);
        return executed(r, primary, exactInventory(primary, partner));
    }


    /** TM carries ECHO twice (the CDISC-CG0545 shape: TM repeats a MIDSTYPE). */
    private static IDataTable tm()
    {
        return RealTables.of("TM").str("MIDSTYPE", "ECHO", "ECHO", "MRI")
                .str("TMRPT", "N", "N", "N").build();
    }

    private static final String CG0545 = "TM.TMRPT == \"N\" and not is_unique_set([MIDSTYPE, USUBJID])";

    @Test
    @DisplayName("CDISC-CG0545 shape: a record repeated by TM is not a duplicate of itself")
    void isUniqueSetCountsEachRecordOnce()
    {
        // One SM record per milestone: unique within the subject. TM repeating ECHO expands the
        // ECHO record to two evaluation rows — counted by value they were a duplicate tuple.
        IDataTable sm = RealTables.of("SM").str("USUBJID", "S1", "S1")
                .str("MIDSTYPE", "ECHO", "MRI").build();
        RuleExecutionResult r = run(match("TM", "MIDSTYPE", "inner"), CG0545, sm, tm(), "USUBJID",
                "MIDSTYPE");
        assertEquals(0, r.getViolationCount(),
                "no SM record repeats (MIDSTYPE, USUBJID): " + QualifiedNameFixture.values(r));
    }


    @Test
    @DisplayName("two distinct records equal in every operand stay a true duplicate")
    void twoDistinctRecordsStayDuplicates()
    {
        IDataTable sm = RealTables.of("SM").str("USUBJID", "S1", "S1").str("SMSEQ", "1", "2")
                .str("MIDSTYPE", "ECHO", "ECHO").build();
        RuleExecutionResult r = run(match("TM", "MIDSTYPE", "inner"), CG0545, sm, tm(), "USUBJID",
                "SMSEQ");
        // (the effective Output_Variables add the Check's MIDSTYPE and TM.TMRPT)
        assertEquals(
                List.of(Map.of("USUBJID", "S1", "SMSEQ", "1", "MIDSTYPE", "ECHO", "TM.TMRPT", "N"),
                        Map.of("USUBJID", "S1", "SMSEQ", "2", "MIDSTYPE", "ECHO", "TM.TMRPT", "N")),
                QualifiedNameFixture.values(r),
                "both ECHO records fire, each once (its two TM rows report the same thing)");
    }


    @Test
    @DisplayName("an operator reading a merged column still sees every merged row")
    void anOperatorOverAMergedColumnSeesEveryMergedRow()
    {
        // TM.TMRPT is the same N on both ECHO rows of the one SM record: counted per merged row
        // the (MIDSTYPE, TM.TMRPT) tuple repeats.
        IDataTable sm = RealTables.of("SM").str("USUBJID", "S1").str("MIDSTYPE", "ECHO").build();
        RuleExecutionResult r = run(match("TM", "MIDSTYPE", "inner"),
                "not is_unique_set([MIDSTYPE, TM.TMRPT])", sm, tm(), "USUBJID");
        assertEquals(1, r.getViolationCount(),
                "a merged operand counts merged rows (one finding: the copies are one finding)");
    }


    /** P: k1, k2, k3; J carries k1 twice. */
    private static IDataTable p()
    {
        return RealTables.of("P").str("K", "k1", "k2", "k3").str("G", "g", "g", "h").build();
    }


    private static IDataTable j()
    {
        return RealTables.of("J").str("K", "k1", "k1", "k2").str("S", "a", "b", "c").build();
    }


    @Test
    @DisplayName("record_count() counts the primary records, not the expanded rows")
    void recordCountCountsRealRows()
    {
        RuleExecutionResult r = run(match("J", "K", "left"), "record_count() != 3", p(), j(), "K");
        assertEquals(0, r.getViolationCount(),
                "P has three records: " + QualifiedNameFixture.values(r));
    }


    @Test
    @DisplayName("record_count(group=) over a primary column counts each record once")
    void groupedRecordCountCountsRealRows()
    {
        // group g holds k1 (expanded twice) and k2: two records, not three rows.
        RuleExecutionResult r = run(match("J", "K", "left"), "record_count(group=[G]) == 2", p(),
                j(), "K");
        assertEquals(List.of(Map.of("K", "k1"), Map.of("K", "k2")), QualifiedNameFixture.values(r));
    }


    @Test
    @DisplayName("record_count as an Output_Variable prints the records evaluated")
    void recordCountOutputVariableIsTheRecordCount()
    {
        RuleExecutionResult r = run(match("J", "K", "left"), "K == \"k2\"", p(), j(), "K",
                "record_count");
        assertEquals(List.of(Map.of("K", "k2", "record_count", "3")),
                QualifiedNameFixture.values(r));
    }


    /**
     * T2 review MED-2: a {@code colref} inside an aggregate is a dynamic read — what it reads is
     * known per row only, so the call is never evaluated over the real-row projection (whose rows
     * are not the merged view's rows): it sees every merged row, exactly as the authored
     * {@code J.S} does. RED before: the reduced evaluation read {@code colref("J.S")} at the
     * projection's row index in the merged view — another record's partner — and counted group
     * {@code g} 0 times instead of once.
     */
    @Test
    @DisplayName("a colref inside an aggregate reads every merged row, as the authored name does")
    void aColrefInsideAnAggregateSeesEveryMergedRow()
    {
        // inner: P's k1 is two merged rows (a, b), k2 one (c); k3 has no partner and is dropped.
        // Group g holds the merged rows a, b, c: exactly one has S == "c".
        RuleExecutionResult viaColref = run(match("J", "K", "inner"),
                "record_count(group=[G], filter=(colref(\"J.S\") == \"c\")) == 1", p(), j(), "K");
        RuleExecutionResult authored = run(match("J", "K", "inner"),
                "record_count(group=[G], filter=(J.S == \"c\")) == 1", p(), j(), "K");
        assertEquals(List.of("k1", "k2"), keys(authored), "the oracle: the authored J.S");
        assertEquals(keys(authored), keys(viaColref), "colref(\"J.S\") counts what J.S counts: "
                + QualifiedNameFixture.values(viaColref));
        // the planted control: a primary-only aggregate still counts by real row (OD-6) — group
        // g holds two records, not three merged rows
        assertEquals(List.of("k1", "k2"), keys(run(match("J", "K", "inner"),
                "record_count(group=[G], filter=(K != \"\")) == 2", p(), j(), "K")));
    }


    private static List<String> keys(RuleExecutionResult r)
    {
        return QualifiedNameFixture.values(r).stream().map(m -> m.get("K")).toList();
    }


    @Test
    @DisplayName("is_last_in_group: every copy of the last record is last, no copy of another")
    void isLastInGroupByRealRow()
    {
        // Ties are all last (D-W1-2), so the expanded copies of the last SE record were already all
        // last; pinned so the real-row evaluation is seen to keep it (M7 (c): SEND-0127 / 0283).
        IDataTable se = RealTables.of("SE").str("USUBJID", "S1", "S1").lng("SESEQ", 1L, 2L).build();
        IDataTable ds = RealTables.of("DS").str("USUBJID", "S1", "S1")
                .str("DSSTDTC", "2020-01-01", "2020-02-01").build();
        RuleExecutionResult last = run(match("DS", "USUBJID", "left"),
                "is_last_in_group(ordering=SESEQ, group=[USUBJID])", se, ds, "SESEQ", "DS.DSSTDTC");
        assertEquals(
                List.of(Map.of("SESEQ", "2", "DS.DSSTDTC", "2020-01-01"),
                        Map.of("SESEQ", "2", "DS.DSSTDTC", "2020-02-01")),
                QualifiedNameFixture.values(last));
        RuleExecutionResult notLast = run(match("DS", "USUBJID", "left"),
                "not is_last_in_group(ordering=SESEQ, group=[USUBJID])", se, ds, "SESEQ");
        assertEquals(List.of(Map.of("SESEQ", "1")), QualifiedNameFixture.values(notLast));
    }
}
