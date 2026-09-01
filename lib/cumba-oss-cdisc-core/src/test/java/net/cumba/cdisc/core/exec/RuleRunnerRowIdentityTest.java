package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Coverage for {@link RuleRunner#readRowIdentity}, which the rule-execution path uses on
 * RECORD/GROUP-sensitivity violations to populate the {@link Violation#getUsubjid()} /
 * {@link Violation#getSeq()} identity fields. Caller-side gating on Sensitivity lives in
 * {@code RuleRunner} itself; the helper is a leaf operation tested standalone.
 */
class RuleRunnerRowIdentityTest
{

    @Test
    void readsBothFieldsWhenColumnsArePresent()
    {
        IDataTable table = MockTable.of().col("USUBJID", "SUBJ-001", "SUBJ-002")
                .col("AESEQ", "1", "2").col("AETERM", "Headache", "Cough").build();

        RuleRunner.RowIdentity ri = RuleRunner.readRowIdentity(table, "AE", 0);

        assertEquals("SUBJ-001", ri.usubjid());
        assertEquals("1", ri.seq());
    }


    @Test
    void readsFromTheCorrectRow()
    {
        IDataTable table = MockTable.of().col("USUBJID", "SUBJ-001", "SUBJ-002", "SUBJ-003")
                .col("AESEQ", "1", "2", "3").build();

        RuleRunner.RowIdentity ri = RuleRunner.readRowIdentity(table, "AE", 2);

        assertEquals("SUBJ-003", ri.usubjid());
        assertEquals("3", ri.seq());
    }


    @Test
    void usubjidOnlyWhenSeqColumnMissing()
    {
        // DM has USUBJID but no DMSEQ; the fields are gated independently (Python parity), so
        // USUBJID is still emitted and SEQ stays null. Previously this returned NONE, dropping
        // the USUBJID from the finding.
        IDataTable table = MockTable.of().col("USUBJID", "SUBJ-001").col("AGE", "42").build();

        RuleRunner.RowIdentity ri = RuleRunner.readRowIdentity(table, "DM", 0);

        assertEquals("SUBJ-001", ri.usubjid());
        assertNull(ri.seq());
    }


    @Test
    void seqOnlyWhenUsubjidColumnMissing()
    {
        // TS has TSSEQ on its own but no USUBJID; SEQ is gated independently of USUBJID
        // (Python parity), so SEQ is emitted and USUBJID stays null.
        IDataTable table = MockTable.of().col("TSPARMCD", "STUDYID").col("TSSEQ", "1").build();

        RuleRunner.RowIdentity ri = RuleRunner.readRowIdentity(table, "TS", 0);

        assertNull(ri.usubjid());
        assertEquals("1", ri.seq());
    }


    @Test
    void handlesNullDomainName()
    {
        // No domain name → the helper builds the column name "SEQ" and skips it (we compare
        // against the literal "SEQ" to avoid colliding with the canonical key the writer
        // emits), so SEQ stays null. USUBJID is still emitted independently.
        IDataTable table = MockTable.of().col("USUBJID", "SUBJ-001").col("SEQ", "1").build();

        RuleRunner.RowIdentity ri = RuleRunner.readRowIdentity(table, null, 0);

        assertEquals("SUBJ-001", ri.usubjid());
        assertNull(ri.seq());
    }


    @Test
    void noneWhenNeitherColumnPresent()
    {
        // No USUBJID and no <DOMAIN>SEQ column → nothing to attach, returns the NONE sentinel.
        IDataTable table = MockTable.of().col("AETERM", "Headache").col("AGE", "42").build();

        RuleRunner.RowIdentity ri = RuleRunner.readRowIdentity(table, "AE", 0);

        assertSame(RuleRunner.RowIdentity.NONE, ri);
        assertNull(ri.usubjid());
        assertNull(ri.seq());
    }


    @Test
    void noneOnNegativeRow()
    {
        IDataTable table = MockTable.of().col("USUBJID", "SUBJ-001").col("AESEQ", "1").build();

        RuleRunner.RowIdentity ri = RuleRunner.readRowIdentity(table, "AE", -1);

        assertSame(RuleRunner.RowIdentity.NONE, ri);
    }


    @Test
    void emitsEmptyStringForMissingValueInRow()
    {
        IDataTable table = MockTable.of().col("USUBJID", "", "SUBJ-002").col("AESEQ", "1", "")
                .build();

        RuleRunner.RowIdentity ri0 = RuleRunner.readRowIdentity(table, "AE", 0);
        assertEquals("", ri0.usubjid(),
                "missing/empty USUBJID cell renders as the empty string (Python parity)");
        assertEquals("1", ri0.seq());

        RuleRunner.RowIdentity ri1 = RuleRunner.readRowIdentity(table, "AE", 1);
        assertEquals("SUBJ-002", ri1.usubjid());
        assertEquals("", ri1.seq());
    }
}
