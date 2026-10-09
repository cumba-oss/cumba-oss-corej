package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W2 SB1 ({@code PLAN-is-sorted-by-direction} N1, Q1; coordinator W2-D3):
 * {@code is_sorted_by(target, by=[asc(COL), desc(COL)], within=)} honours each sort key's direction
 * and its {@code nulls="first"} / {@code nulls="last"} placement. A blank or missing key cell sorts
 * <b>first</b> when {@code nulls=} is omitted — today's placement — and under {@code desc} too: the
 * placement is independent of the direction, so reversing a key never drags a blank to the end.
 * Before SB1 the plan read the key NAMES only (D20), so a written {@code desc} was checked as
 * {@code asc}. The five corpus carriers ({@code CDISC-CG0620}, {@code CDISC-CG0662},
 * {@code CDISC-SEND-0130}, {@code CDISC-SEND-0130.1}, {@code CDISC-SEND-0354}) are all {@code asc}
 * with no {@code nulls=}: their shapes answer exactly as before.
 */
class IsSortedByDirectionTest
{

    /** The 1-based rows a Check fires on over {@code table}. */
    static List<Long> fired(IDataTable table, String check)
    {
        Rule rule = SequenceFunctionTest.load(check);
        assertEquals(null, rule.getLoadError(), () -> check + " -> " + rule.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table,
                RealTables.inventoryOf(table), table.getMetaData().getName());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " -> " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    private static List<Long> sorted(IDataTable table, String by)
    {
        return fired(table, "not is_sorted_by(SESEQ, by=" + by + ", within=USUBJID)");
    }


    @Test
    void descIsHonoured()
    {
        // S1's SESEQ runs against its dates, S2's with them
        IDataTable se = RealTableFixture.of("SE").str("USUBJID", "S1", "S1", "S1", "S2", "S2", "S2")
                .dbl("SESEQ", 1.0, 2.0, 3.0, 1.0, 2.0, 3.0).str("SESTDTC", "2020-03-01",
                        "2020-02-01", "2020-01-01", "2020-01-01", "2020-02-01", "2020-03-01")
                .build();
        assertEquals(List.of(1L, 2L, 3L), sorted(se, "[asc(SESTDTC)]"), "asc: S1 is unsorted");
        assertEquals(List.of(1L, 2L, 3L), sorted(se, "[SESTDTC]"), "a bare column is asc");
        assertEquals(List.of(4L, 5L, 6L), sorted(se, "[desc(SESTDTC)]"),
                "desc: S2 is the unsorted group now (before SB1 desc read as asc)");
    }


    @Test
    void mixedAscAndDescKeysEachKeepTheirOwnDirection()
    {
        // S1 ties on SESTDTC, so SEENDTC decides; S2's SESTDTC differs, so the first key decides
        IDataTable se = RealTableFixture.of("SE").str("USUBJID", "S1", "S1", "S1", "S2", "S2")
                .dbl("SESEQ", 1.0, 2.0, 3.0, 1.0, 2.0)
                .str("SESTDTC", "2020-01-01", "2020-01-01", "2020-01-01", "2020-01-01",
                        "2020-01-05")
                .str("SEENDTC", "2020-03-01", "2020-02-01", "2020-01-01", "2020-01-01",
                        "2020-12-31")
                .build();
        assertEquals(List.of(), sorted(se, "[asc(SESTDTC), desc(SEENDTC)]"),
                "S1 in descending SEENDTC order, S2 in ascending SESTDTC order");
        assertEquals(List.of(1L, 2L, 3L), sorted(se, "[asc(SESTDTC), asc(SEENDTC)]"),
                "the second key ascending reverses S1");
        assertEquals(List.of(4L, 5L), sorted(se, "[desc(SESTDTC), desc(SEENDTC)]"),
                "the first key descending reverses S2; S1 still ties on it");
        assertEquals(List.of(), sorted(se, "[SESTDTC, desc(SEENDTC)]"),
                "a bare column beside a descriptor is asc");
    }


    @Test
    void nullsFirstByDefaultUnderBothDirectionsAndLastOnRequest()
    {
        // S1: a BLANK key on its last SESEQ; S2: a MISSING key on its first, dates descending
        IDataTable se = RealTableFixture.of("SE").str("USUBJID", "S1", "S1", "S1", "S2", "S2", "S2")
                .dbl("SESEQ", 1.0, 2.0, 3.0, 1.0, 2.0, 3.0)
                .str("SESTDTC", "2020-01-01", "2020-02-01", "", null, "2020-02-01", "2020-01-01")
                .build();
        List<Long> all = List.of(1L, 2L, 3L, 4L, 5L, 6L);
        assertEquals(all, sorted(se, "[asc(SESTDTC)]"),
                "asc, nulls first (today): S1 3,1,2 and S2 1,3,2 are unsorted");
        assertEquals(all, sorted(se, "[asc(SESTDTC, nulls=\"first\")]"), "first is the default");
        assertEquals(List.of(4L, 5L, 6L), sorted(se, "[asc(SESTDTC, nulls=\"last\")]"),
                "asc, nulls last: S1 1,2,3 is sorted, S2 3,2,1 is not");
        assertEquals(List.of(1L, 2L, 3L), sorted(se, "[desc(SESTDTC)]"),
                "desc keeps nulls FIRST: S2 1,2,3 is sorted, S1 3,2,1 is not");
        assertEquals(all, sorted(se, "[desc(SESTDTC, nulls=\"last\")]"),
                "desc, nulls last: S1 2,1,3 and S2 2,3,1 are unsorted");
        String bogus = SequenceFunctionTest.loadError(
                "not is_sorted_by(SESEQ, by=[asc(SESTDTC, nulls=\"bogus\")], within=USUBJID)");
        assertTrue(bogus.contains("nulls= takes \"first\" or \"last\" (lower case), not \"bogus\""),
                "W4 SB3: an unknown nulls= value is a load error, no longer the silent default - "
                        + bogus);
    }


    /**
     * The five carriers' shape, YYYY-MM-DD keys (the snapshot study's SE: 752 / 752 rows): S1
     * sorted; S2's SESEQ against its dates; S3 a blank key first (sorted under nulls-first); S4 a
     * numeric target 2 before 10; S5 a missing key on its last SESEQ (unsorted under nulls-first);
     * a blank USUBJID group kept as its own group (FOLD_BLANK_KEYS) and unsorted; S6 a tie (stable,
     * file order).
     */
    private static IDataTable carrierShape(String domain)
    {
        String p = domain;
        return RealTableFixture.of(domain)
                .str("USUBJID", "S1", "S1", "S1", "S2", "S2", "S2", "S3", "S3", "S4", "S4", "S5",
                        "S5", "", "", "S6", "S6")
                .dbl(p + "SEQ", 1.0, 2.0, 3.0, 1.0, 2.0, 3.0, 1.0, 2.0, 2.0, 10.0, 1.0, 2.0, 1.0,
                        2.0, 1.0, 2.0)
                .str(p + "STDTC", "2020-01-01", "2020-01-10", "2020-02-01", "2020-03-01",
                        "2020-03-05", "2020-03-02", "", "2020-01-01", "2020-01-01", "2020-01-02",
                        "2020-01-01", null, "2020-05-01", "2020-04-01", "2020-01-01", "2020-01-01")
                .build();
    }


    @Test
    void theFiveCarrierShapesAnswerAsBefore()
    {
        List<Long> expected = List.of(4L, 5L, 6L, 11L, 12L, 13L, 14L);
        // CDISC-CG0620 / CDISC-CG0662 (SE; CG0620 also SM)
        assertEquals(expected,
                fired(carrierShape("SE"),
                        "not is_sorted_by(--SEQ, by=[asc(--STDTC)], within=USUBJID)"),
                "CG0620 / CG0662");
        assertEquals(expected,
                fired(carrierShape("SM"),
                        "not is_sorted_by(--SEQ, by=[asc(--STDTC)], within=USUBJID)"),
                "CG0620 on SM");
        // CDISC-SEND-0130 / CDISC-SEND-0130.1 (SE)
        assertEquals(expected,
                fired(carrierShape("SE"),
                        "not is_sorted_by(SESEQ, by=[asc(SESTDTC)], within=USUBJID)"),
                "SEND-0130 / SEND-0130.1");
        // CDISC-SEND-0354 (SJ)
        assertEquals(expected, fired(carrierShape("SJ"),
                "not is_sorted_by(SJSEQ, by=[asc(SJSTDTC)], within=USUBJID)"), "SEND-0354");
    }
}
