package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import net.cumba.corej.core.model.Operation;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * E3 ({@code date_diff_days}) and E4 ({@code is_last_in_group}) operation arms, exercised through
 * {@link OperationExecutor#executeOne} with their raw {@link GroupedResult} inspected. The result
 * map is keyed by the joined key-column values (NUL-separated); single-column keys equal the raw
 * cell value, multi-column keys join the cell values with {@code "\0"}.
 */
class OperationExecutorDateDiffLastInGroupTest
{

    private static final String NUL = "\0";

    private static final DatasetResolver NO_RESOLVER = _ -> null;

    private static Operation makeOp(String id, String operator)
    {
        Operation op = new Operation();
        op.setId(id);
        op.setOperator(operator);
        return op;
    }

    // -- E3 date_diff_days : Mode 1 (two date columns) --------------------


    @Test
    void dateDiffDays_mode1_twoDateColumns_noOffset()
    {
        // --DTC minus --STDTC, same record; days-between with NO +1.
        IDataTable ds = MockTable.of().col("USUBJID", "S1", "S2", "S3")
                .col("MYDTC", "2020-01-10", "2020-02-01", "2020-01-01")
                .col("REFDTC", "2020-01-01", "2020-01-01", "2020-01-01").name("XX").build();
        Operation op = makeOp("$d", "date_diff_days");
        op.setName("MYDTC");
        op.setReference("REFDTC");

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, ds, NO_RESOLVER, null,
                new java.util.HashMap<>());
        assertEquals(List.of("MYDTC", "REFDTC"), gr.groupColumns());
        // 2020-01-10 - 2020-01-01 = 9 (no +1)
        assertEquals(9L, gr.results().get("2020-01-10" + NUL + "2020-01-01"));
        // 2020-02-01 - 2020-01-01 = 31
        assertEquals(31L, gr.results().get("2020-02-01" + NUL + "2020-01-01"));
        // same day = 0 (no +1)
        assertEquals(0L, gr.results().get("2020-01-01" + NUL + "2020-01-01"));
    }


    @Test
    void dateDiffDays_mode1_integerOffsetLiteral()
    {
        IDataTable ds = MockTable.of().col("MYDTC", "2020-01-10").col("REFDTC", "2020-01-01")
                .name("XX").build();
        Operation op = makeOp("$d", "date_diff_days");
        op.setName("MYDTC");
        op.setReference("REFDTC");
        op.setOffset("5");

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, ds, NO_RESOLVER, null,
                new java.util.HashMap<>());
        // 9 + 5 = 14
        assertEquals(14L, gr.results().get("2020-01-10" + NUL + "2020-01-01"));
    }


    @Test
    void dateDiffDays_mode1_offsetColumn()
    {
        IDataTable ds = MockTable.of().col("MYDTC", "2020-01-10", "2020-01-10")
                .col("REFDTC", "2020-01-01", "2020-01-01").col("OFF", "3", "10").name("XX").build();
        Operation op = makeOp("$d", "date_diff_days");
        op.setName("MYDTC");
        op.setReference("REFDTC");
        op.setOffset("OFF");

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, ds, NO_RESOLVER, null,
                new java.util.HashMap<>());
        assertEquals(List.of("MYDTC", "REFDTC", "OFF"), gr.groupColumns());
        // 9 + 3 and 9 + 10 keyed by (MYDTC, REFDTC, OFF)
        assertEquals(12L, gr.results().get("2020-01-10" + NUL + "2020-01-01" + NUL + "3"));
        assertEquals(19L, gr.results().get("2020-01-10" + NUL + "2020-01-01" + NUL + "10"));
    }


    @Test
    void dateDiffDays_mode1_missingOrShortDatesOmitted()
    {
        IDataTable ds = MockTable.of().col("MYDTC", "2020-01-10", "2020", "")
                .col("REFDTC", "2020-01-01", "2020-01-01", "2020-01-01").name("XX").build();
        Operation op = makeOp("$d", "date_diff_days");
        op.setName("MYDTC");
        op.setReference("REFDTC");

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, ds, NO_RESOLVER, null,
                new java.util.HashMap<>());
        assertEquals(9L, gr.results().get("2020-01-10" + NUL + "2020-01-01"));
        // partial "2020" (len 4) and blank are omitted
        assertNull(gr.results().get("2020" + NUL + "2020-01-01"));
        assertEquals(1, gr.results().size());
    }

    // -- E3 date_diff_days : Mode 2 (grouped-min cross-domain) ------------


    @Test
    void dateDiffDays_mode2_groupedMinReference()
    {
        // Target rows carry USUBJID + RPHASE + --DTC + --RPDY. Reference is the earliest SJSTDTC
        // per (USUBJID, RPHASE) sourced from the SJ domain.
        IDataTable target = MockTable.of().col("USUBJID", "S1", "S1", "S2")
                .col("RPHASE", "P1", "P1", "P1")
                .col("MYDTC", "2020-01-10", "2020-01-20", "2020-02-05").name("XX").build();
        IDataTable sj = MockTable.of().col("USUBJID", "S1", "S1", "S2")
                .col("RPHASE", "P1", "P1", "P1")
                .col("SJSTDTC", "2020-01-05", "2020-01-01", "2020-02-01").name("SJ").build();
        DatasetResolver resolver = name -> "SJ".equals(name) ? sj : null;

        Operation op = makeOp("$d", "date_diff_days");
        op.setName("MYDTC");
        op.setDomain("SJ");
        op.setReference("SJSTDTC");
        op.setGroup(List.of("USUBJID", "RPHASE"));

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, target, resolver, null,
                new java.util.HashMap<>());
        assertEquals(List.of("MYDTC", "USUBJID", "RPHASE"), gr.groupColumns());
        // S1 earliest SJSTDTC = 2020-01-01; 2020-01-10 - 2020-01-01 = 9
        assertEquals(9L, gr.results().get("2020-01-10" + NUL + "S1" + NUL + "P1"));
        // 2020-01-20 - 2020-01-01 = 19
        assertEquals(19L, gr.results().get("2020-01-20" + NUL + "S1" + NUL + "P1"));
        // S2 earliest SJSTDTC = 2020-02-01; 2020-02-05 - 2020-02-01 = 4
        assertEquals(4L, gr.results().get("2020-02-05" + NUL + "S2" + NUL + "P1"));
    }


    @Test
    void dateDiffDays_mode2_referenceExtremeMax_usesLatestReference()
    {
        // SEND-0403 shape: subtrahend is the LATEST SJENDTC per (USUBJID, RPHASE) —
        // reference_extreme
        // = "max" — not the earliest.
        IDataTable target = MockTable.of().col("USUBJID", "S1", "S1", "S2")
                .col("RPHASE", "P1", "P1", "P1")
                .col("MYDTC", "2020-01-10", "2020-01-20", "2020-02-15").name("XX").build();
        IDataTable sj = MockTable.of().col("USUBJID", "S1", "S1", "S2")
                .col("RPHASE", "P1", "P1", "P1")
                .col("SJENDTC", "2020-01-05", "2020-01-08", "2020-02-01").name("SJ").build();
        DatasetResolver resolver = name -> "SJ".equals(name) ? sj : null;

        Operation op = makeOp("$d", "date_diff_days");
        op.setName("MYDTC");
        op.setDomain("SJ");
        op.setReference("SJENDTC");
        op.setReferenceExtreme("max");
        op.setGroup(List.of("USUBJID", "RPHASE"));

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, target, resolver, null,
                new java.util.HashMap<>());
        // S1 LATEST SJENDTC = 2020-01-08; 2020-01-10 - 2020-01-08 = 2
        assertEquals(2L, gr.results().get("2020-01-10" + NUL + "S1" + NUL + "P1"));
        // 2020-01-20 - 2020-01-08 = 12
        assertEquals(12L, gr.results().get("2020-01-20" + NUL + "S1" + NUL + "P1"));
        // S2 LATEST SJENDTC = 2020-02-01; 2020-02-15 - 2020-02-01 = 14
        assertEquals(14L, gr.results().get("2020-02-15" + NUL + "S2" + NUL + "P1"));
    }


    @Test
    void dateDiffDays_mode2_absentDomain_returnsNull()
    {
        IDataTable target = MockTable.of().col("USUBJID", "S1").col("RPHASE", "P1")
                .col("MYDTC", "2020-01-10").name("XX").build();
        Operation op = makeOp("$d", "date_diff_days");
        op.setName("MYDTC");
        op.setDomain("SJ");
        op.setReference("SJSTDTC");
        op.setGroup(List.of("USUBJID", "RPHASE"));

        Object result = OperationExecutor.executeOne(op, target, NO_RESOLVER, null,
                new java.util.HashMap<>());
        assertNull(result, "absent reference domain ⇒ unresolvable ⇒ rule SKIPs");
    }


    @Test
    void dateDiffDays_mode2_subjectScalar_detectionRecordSemantics()
    {
        // SEND202-205 shape: reference = earliest EXSTDTC per USUBJID (group is USUBJID alone),
        // minuend = the record's own --DTC.
        IDataTable target = MockTable.of().col("USUBJID", "S1", "S1")
                .col("MYDTC", "2020-03-01", "2020-03-10").name("MA").build();
        IDataTable ex = MockTable.of().col("USUBJID", "S1", "S1")
                .col("EXSTDTC", "2020-02-15", "2020-02-01").name("EX").build();
        DatasetResolver resolver = name -> "EX".equals(name) ? ex : null;

        Operation op = makeOp("$d", "date_diff_days");
        op.setName("MYDTC");
        op.setDomain("EX");
        op.setReference("EXSTDTC");
        op.setGroup(List.of("USUBJID"));

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, target, resolver, null,
                new java.util.HashMap<>());
        // earliest EXSTDTC for S1 = 2020-02-01; 2020-03-01 - 2020-02-01 = 29; 2020-03-10 = 38
        assertEquals(29L, gr.results().get("2020-03-01" + NUL + "S1"));
        assertEquals(38L, gr.results().get("2020-03-10" + NUL + "S1"));
    }

    // -- E4 is_last_in_group ----------------------------------------------


    @Test
    void isLastInGroup_flagsMaxOrderingRowPerGroup()
    {
        IDataTable se = MockTable.of().col("USUBJID", "S1", "S1", "S1", "S2", "S2")
                .col("SESEQ", "1", "2", "3", "1", "2").col("SEENDTC", "a", "b", "c", "d", "e")
                .name("SE").build();
        Operation op = makeOp("$last", "is_last_in_group");
        op.setGroup(List.of("USUBJID"));
        op.setOrdering("SESEQ");

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, se, NO_RESOLVER, null,
                new java.util.HashMap<>());
        assertEquals(List.of("USUBJID", "SESEQ"), gr.groupColumns());
        assertEquals(false, gr.results().get("S1" + NUL + "1"));
        assertEquals(false, gr.results().get("S1" + NUL + "2"));
        assertEquals(true, gr.results().get("S1" + NUL + "3"), "last SESEQ in S1");
        assertEquals(false, gr.results().get("S2" + NUL + "1"));
        assertEquals(true, gr.results().get("S2" + NUL + "2"), "last SESEQ in S2");
        assertEquals(false, gr.defaultForMissingKey(), "absent group ⇒ default false");
    }


    @Test
    void isLastInGroup_orderingIndependentOfRowOrder()
    {
        // Rows out of SESEQ order — the max-ordering row is still the "last".
        IDataTable se = MockTable.of().col("USUBJID", "S1", "S1", "S1").col("SESEQ", "3", "1", "2")
                .name("SE").build();
        Operation op = makeOp("$last", "is_last_in_group");
        op.setGroup(List.of("USUBJID"));
        op.setOrdering("SESEQ");

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, se, NO_RESOLVER, null,
                new java.util.HashMap<>());
        assertEquals(true, gr.results().get("S1" + NUL + "3"), "SESEQ 3 is the max");
        assertEquals(false, gr.results().get("S1" + NUL + "1"));
        assertEquals(false, gr.results().get("S1" + NUL + "2"));
    }


    @Test
    void isLastInGroup_absentOrderingColumn_returnsNull()
    {
        IDataTable se = MockTable.of().col("USUBJID", "S1").name("SE").build();
        Operation op = makeOp("$last", "is_last_in_group");
        op.setGroup(List.of("USUBJID"));
        op.setOrdering("SESEQ");

        Object result = OperationExecutor.executeOne(op, se, NO_RESOLVER, null,
                new java.util.HashMap<>());
        assertNull(result, "absent ordering column ⇒ unresolvable");
    }


    @Test
    void isLastInGroup_missingParamsReturnNull()
    {
        IDataTable se = MockTable.of().col("USUBJID", "S1").col("SESEQ", "1").name("SE").build();
        Operation noOrdering = makeOp("$last", "is_last_in_group");
        noOrdering.setGroup(List.of("USUBJID"));
        assertNull(OperationExecutor.executeOne(noOrdering, se, NO_RESOLVER, null,
                new java.util.HashMap<>()), "no ordering ⇒ null");

        Operation noGroup = makeOp("$last", "is_last_in_group");
        noGroup.setOrdering("SESEQ");
        assertNull(OperationExecutor.executeOne(noGroup, se, NO_RESOLVER, null,
                new java.util.HashMap<>()), "no group ⇒ null");
    }


    // -- resolvePrefixes must not drop date_diff_days fields (regression) -------
    @Test
    void resolvePrefixes_preservesOffsetReferenceExtremeAndNamePattern()
    {
        // Regression: a `--`-prefixed date_diff_days (CDISC-SEND-0202..0205: name "--DTC",
        // offset "1") is routed through resolvePrefixes to resolve --DTC → <domain>DTC. The copy
        // routine historically omitted offset / reference_extreme / name_pattern, so the offset was
        // silently dropped ⇒ (date − reference) with no +1 ⇒ false positives on conformant rows.
        Operation op = makeOp("$d", "date_diff_days");
        op.setName("--DTC");
        op.setDomain("EX");
        op.setReference("EXSTDTC");
        op.setGroup(List.of("USUBJID"));
        op.setOffset("1");
        op.setReferenceExtreme("max");
        op.setNamePattern("^TR\\d+EDT$");
        // EC-23: the has_mixed_emptiness qualifier list must also survive prefix resolution; a
        // `--`-prefixed qualifier column is resolved to the evaluation domain.
        op.setQualifyingAnyPopulated(List.of("BASE", "--BASEC"));

        Operation resolved = OperationExecutor.resolvePrefixes(op, "TF");

        assertEquals("TFDTC", resolved.getName(), "--DTC must resolve to the evaluation domain");
        assertEquals("1", resolved.getOffset(), "offset must survive prefix resolution");
        assertEquals("max", resolved.getReferenceExtreme(), "reference_extreme must survive");
        assertEquals("^TR\\d+EDT$", resolved.getNamePattern(), "name_pattern must survive");
        assertEquals(List.of("BASE", "TFBASEC"), resolved.getQualifyingAnyPopulated(),
                "qualifying_any_populated must survive prefix resolution (with -- resolved)");
        // EC-18 / P5c: Mode-3 foreign-minuend fields must survive prefix resolution too.
        op.setMinuendDomain("PM");
        op.setMinuendMatch(List.of("USUBJID", "--SPID"));
        Operation resolved2 = OperationExecutor.resolvePrefixes(op, "TF");
        assertEquals("PM", resolved2.getMinuendDomain(), "minuend_domain must survive");
        assertEquals(List.of("USUBJID", "--SPID"), resolved2.getMinuendMatch(),
                "minuend_match `--` tokens are copied verbatim (resolved per-side at eval time)");
    }

    // -- E3 date_diff_days : Mode 3 (foreign minuend, sided --SPID match) --------


    @Test
    void dateDiffDays_mode3_foreignMinuendSidedMatch()
    {
        // TF (eval) minuend PMDTC read from the PM record matched on USUBJID + sided --SPID
        // (TFSPID = PMSPID); subtrahend = per-subject earliest EXSTDTC (Mode-2 grouped-min);
        // offset +1. Expected = (PMDTC - min EXSTDTC) + 1.
        IDataTable tf = MockTable.of().col("DOMAIN", "TF", "TF", "TF")
                .col("USUBJID", "S1", "S1", "S2").col("TFSPID", "M1", "M2", "M3").name("TF")
                .build();
        IDataTable pm = MockTable.of().col("DOMAIN", "PM", "PM", "PM")
                .col("USUBJID", "S1", "S1", "S2").col("PMSPID", "M1", "M2", "M3")
                .col("PMDTC", "2020-01-20", "2020-02-11", "2020-03-06").name("PM").build();
        IDataTable ex = MockTable.of().col("USUBJID", "S1", "S2")
                .col("EXSTDTC", "2020-01-05", "2020-03-01").name("EX").build();
        DatasetResolver resolver = name -> switch (name)
        {
        case "PM" -> pm;
        case "EX" -> ex;
        default -> null;
        };

        Operation op = makeOp("$d", "date_diff_days");
        op.setName("PMDTC"); // minuend column, read from the PM domain
        op.setMinuendDomain("PM");
        op.setMinuendMatch(List.of("USUBJID", "--SPID"));
        op.setDomain("EX"); // subtrahend source
        op.setReference("EXSTDTC");
        op.setGroup(List.of("USUBJID"));
        op.setOffset("1");

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, tf, resolver, null,
                new java.util.HashMap<>());
        // Key columns = left match keys (USUBJID, TFSPID) + group (USUBJID, deduped) = [USUBJID,
        // TFSPID].
        assertEquals(List.of("USUBJID", "TFSPID"), gr.groupColumns());
        // S1/M1: (2020-01-20 - 2020-01-05) + 1 = 15 + 1 = 16
        assertEquals(16L, gr.results().get("S1" + NUL + "M1"));
        // S1/M2: (2020-02-11 - 2020-01-05) + 1 = 37 + 1 = 38
        assertEquals(38L, gr.results().get("S1" + NUL + "M2"));
        // S2/M3: (2020-03-06 - 2020-03-01) + 1 = 5 + 1 = 6
        assertEquals(6L, gr.results().get("S2" + NUL + "M3"));
    }


    @Test
    void dateDiffDays_mode3_absentMinuendDomain_returnsNull()
    {
        IDataTable tf = MockTable.of().col("DOMAIN", "TF").col("USUBJID", "S1").col("TFSPID", "M1")
                .name("TF").build();
        IDataTable ex = MockTable.of().col("USUBJID", "S1").col("EXSTDTC", "2020-01-05").name("EX")
                .build();
        DatasetResolver resolver = name -> "EX".equals(name) ? ex : null; // no PM

        Operation op = makeOp("$d", "date_diff_days");
        op.setName("PMDTC");
        op.setMinuendDomain("PM");
        op.setMinuendMatch(List.of("USUBJID", "--SPID"));
        op.setDomain("EX");
        op.setReference("EXSTDTC");
        op.setGroup(List.of("USUBJID"));
        op.setOffset("1");

        Object result = OperationExecutor.executeOne(op, tf, resolver, null,
                new java.util.HashMap<>());
        assertNull(result, "absent minuend_domain ⇒ unresolvable ⇒ rule SKIPs");
    }


    @Test
    void dateDiffDays_mode3_blankSpidRowHasNoMatch()
    {
        // A TF row whose TFSPID is blank must not join (no spurious empty-key match) ⇒ no value.
        IDataTable tf = MockTable.of().col("DOMAIN", "TF", "TF").col("USUBJID", "S1", "S2")
                .col("TFSPID", "M1", "").name("TF").build();
        IDataTable pm = MockTable.of().col("DOMAIN", "PM", "PM").col("USUBJID", "S1", "S2")
                .col("PMSPID", "M1", "").col("PMDTC", "2020-01-20", "2020-03-06").name("PM")
                .build();
        IDataTable ex = MockTable.of().col("USUBJID", "S1", "S2")
                .col("EXSTDTC", "2020-01-05", "2020-03-01").name("EX").build();
        DatasetResolver resolver = name -> switch (name)
        {
        case "PM" -> pm;
        case "EX" -> ex;
        default -> null;
        };

        Operation op = makeOp("$d", "date_diff_days");
        op.setName("PMDTC");
        op.setMinuendDomain("PM");
        op.setMinuendMatch(List.of("USUBJID", "--SPID"));
        op.setDomain("EX");
        op.setReference("EXSTDTC");
        op.setGroup(List.of("USUBJID"));
        op.setOffset("1");

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, tf, resolver, null,
                new java.util.HashMap<>());
        // S1/M1 resolves; the blank-SPID S2 row produces no value.
        assertEquals(16L, gr.results().get("S1" + NUL + "M1"));
        assertNull(gr.results().get("S2" + NUL + ""), "a blank --SPID row must not join");
    }


    @Test
    void dateDiffDays_mode3_minuendMatchFallsBackToGroup()
    {
        // With minuend_match absent, the match falls back to `group` (same-named USUBJID). Each
        // subject has exactly one PM record here.
        IDataTable tf = MockTable.of().col("DOMAIN", "TF", "TF").col("USUBJID", "S1", "S2")
                .name("TF").build();
        IDataTable pm = MockTable.of().col("DOMAIN", "PM", "PM").col("USUBJID", "S1", "S2")
                .col("PMDTC", "2020-01-20", "2020-03-06").name("PM").build();
        IDataTable ex = MockTable.of().col("USUBJID", "S1", "S2")
                .col("EXSTDTC", "2020-01-05", "2020-03-01").name("EX").build();
        DatasetResolver resolver = name -> switch (name)
        {
        case "PM" -> pm;
        case "EX" -> ex;
        default -> null;
        };

        Operation op = makeOp("$d", "date_diff_days");
        op.setName("PMDTC");
        op.setMinuendDomain("PM");
        // minuend_match omitted ⇒ falls back to group = [USUBJID]
        op.setDomain("EX");
        op.setReference("EXSTDTC");
        op.setGroup(List.of("USUBJID"));
        op.setOffset("1");

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, tf, resolver, null,
                new java.util.HashMap<>());
        assertEquals(List.of("USUBJID"), gr.groupColumns());
        assertEquals(16L, gr.results().get("S1"));
        assertEquals(6L, gr.results().get("S2"));
    }

    // -- EC-46 : the Mode 2 subtrahend is selected by the determinacy rule ---
    //
    // buildGroupedExtremeDate is the fifth selector site and the one date_diff_days uses. Both
    // reference_extreme directions are exercised, because a partial destroys a group only when it
    // WINS the extreme -- so min-side and max-side need separate fixtures.


    /**
     * EC-46 min side: {@code S1}'s reference set is {@code {2012-06, 2012-06-20}}. The partial
     * could be the 1st, so the earliest reference is indeterminate and S1 gets <b>no entry at
     * all</b> — the same shape an all-blank group has always produced. {@code S2} is unaffected.
     */
    @Test
    void ec46_mode2MinSubtrahendIsIndeterminate()
    {
        IDataTable target = MockTable.of().col("USUBJID", "S1", "S2")
                .col("MYDTC", "2012-07-10", "2012-07-10").name("XX").build();
        IDataTable sj = MockTable.of().col("USUBJID", "S1", "S1", "S2", "S2")
                .col("SJSTDTC", "2012-06", "2012-06-20", "2012-06-10", "2012-06-20").name("SJ")
                .build();
        DatasetResolver resolver = name -> "SJ".equals(name) ? sj : null;

        Operation op = makeOp("$d", "date_diff_days");
        op.setName("MYDTC");
        op.setDomain("SJ");
        op.setReference("SJSTDTC");
        op.setGroup(List.of("USUBJID"));

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, target, resolver, null,
                new java.util.HashMap<>());
        assertFalse(gr.results().containsKey("2012-07-10" + NUL + "S1"),
                "S1's earliest reference is indeterminate, so no day count may be published");
        // S2: 2012-07-10 - 2012-06-10 = 30
        assertEquals(30L, gr.results().get("2012-07-10" + NUL + "S2"));
    }


    /**
     * EC-46 max side — the direction the shipped {@code CDISC-SEND-0403} actually authors. Here the
     * partial can reach past the complete date ({@code 2012-06-30} &gt; {@code 2012-06-20}), so the
     * latest reference is indeterminate.
     */
    @Test
    void ec46_mode2MaxSubtrahendIsIndeterminate()
    {
        IDataTable target = MockTable.of().col("USUBJID", "S1", "S2")
                .col("MYDTC", "2012-07-10", "2012-07-10").name("XX").build();
        IDataTable sj = MockTable.of().col("USUBJID", "S1", "S1", "S2", "S2")
                .col("SJSTDTC", "2012-06", "2012-06-20", "2012-06-10", "2012-06-20").name("SJ")
                .build();
        DatasetResolver resolver = name -> "SJ".equals(name) ? sj : null;

        Operation op = makeOp("$d", "date_diff_days");
        op.setName("MYDTC");
        op.setDomain("SJ");
        op.setReference("SJSTDTC");
        op.setGroup(List.of("USUBJID"));
        op.setReferenceExtreme("max");

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, target, resolver, null,
                new java.util.HashMap<>());
        assertFalse(gr.results().containsKey("2012-07-10" + NUL + "S1"),
                "2012-06 could be the 30th, later than 2012-06-20");
        // S2: 2012-07-10 - 2012-06-20 = 20
        assertEquals(20L, gr.results().get("2012-07-10" + NUL + "S2"));
    }


    /**
     * The control: an all-complete reference set is unchanged, and the min-side partial that
     * <i>cannot</i> beat the complete date still resolves (OQ1).
     */
    @Test
    void ec46_mode2ResolvesWhenThePartialCannotBeatTheCompleteDate()
    {
        IDataTable target = MockTable.of().col("USUBJID", "S1").col("MYDTC", "2012-07-10")
                .name("XX").build();
        IDataTable sj = MockTable.of().col("USUBJID", "S1", "S1")
                .col("SJSTDTC", "2012-06", "2012-06-01").name("SJ").build();
        DatasetResolver resolver = name -> "SJ".equals(name) ? sj : null;

        Operation op = makeOp("$d", "date_diff_days");
        op.setName("MYDTC");
        op.setDomain("SJ");
        op.setReference("SJSTDTC");
        op.setGroup(List.of("USUBJID"));

        GroupedResult gr = (GroupedResult) OperationExecutor.executeOne(op, target, resolver, null,
                new java.util.HashMap<>());
        // earliest = 2012-06-01 (no completion of 2012-06 precedes it); 2012-07-10 - 2012-06-01 =
        // 39
        assertEquals(39L, gr.results().get("2012-07-10" + NUL + "S1"));
    }

}
