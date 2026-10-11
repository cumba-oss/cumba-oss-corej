package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ {@code REG-Q3} — a join-key column present on <b>one</b> side only <b>leaves the key</b>;
 * absent on both sides it leaves the key too; with no key column left the rule ERRORS.
 *
 * <p>
 * Owner, 2026-10-09: <i>"Ok, I would like to change my ruling JKM R7 and move to the drop as
 * proposed with your warning."</i> — the proposal it accepts: <i>"drop a key column that is present
 * on one side only from the join key, with a WARNING per dropped column […], so a fan-out is always
 * traceable; no key column left ⇒ rule ERROR; a rule listing its key columns under
 * Requirements.Variables SKIPS instead."</i> It replaced {@code JKM R7}'s one-side half (the absent
 * side contributed the present side's type default, so only a blank cell could match).
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Why this class exists separately from {@link MissingValueJoinKeyTest}:</b> that class
 * varies the <em>cell</em>, this one varies the <em>column</em>. They are different rulings — R4/R5
 * govern a missing value, {@code REG-Q3} (and {@code JKM R7}'s both-absent and all-absent halves)
 * an absent column.
 * </p>
 *
 * <p>
 * ⛔ Every case below carries its own falsifier in the assertion message, because the shapes here
 * are degenerate by construction and a green over the wrong fixture proves nothing. The partners of
 * the one-side cases differ in the dropped column, so the drop shows as a <b>fan-out</b> (one
 * record, both partner rows) and the retired type default as a single blank-cell match.
 * </p>
 */
class AbsentJoinKeyColumnTest
{

    private static final String AE = "AE";

    private static final String USUBJID = "USUBJID";

    private static final String VISIT = "VISITNUM";

    private static final String AETERM = "AETERM";

    private static final List<String> FAN_OUT = List.of("0:HEADACHE", "0:NAUSEA", "1:HEADACHE",
            "1:NAUSEA");

    private static MatchDataset md(List<String> keys)
    {
        MatchDataset m = new MatchDataset();
        m.setName(AE);
        m.setKeys(keys);
        m.setJoinType("left");
        return m;
    }


    private static List<String> rows(MergedEvaluation.Built exp, String col)
    {
        IDataTable t = exp.table();
        JoinLookup lk = exp.lookups().get(AE);
        List<String> out = new ArrayList<>();
        for (long i = 0; i < t.getRowCount(); i++)
        {
            out.add(t.getRealRowIndex(i) + ":" + lk.lookup(t, i, col));
        }
        return out;
    }


    private static MergedEvaluation.Built expand(IDataTable dm, IDataTable ae, List<String> keys)
    {
        return expand(dm, ae, md(keys));
    }


    private static MergedEvaluation.Built expand(IDataTable dm, IDataTable ae, MatchDataset m)
    {
        var exp = ExecCalls.expand(dm, List.of(m), Map.of("DM", dm, AE, ae)::get, "R-TEST");
        assertNotNull(exp, "one keyed entry is expandable, so the expansion must be built");
        return exp;
    }


    /** Two DM rows of subject P1, no VISITNUM column. */
    private static IDataTable dmWithoutVisit()
    {
        return RealTables.of("DM").str(USUBJID, "P1", "P1").str("AGE", "34", "51").build();
    }


    /**
     * ⭐ Absent on ONE side, character: the component leaves the key, so USUBJID alone decides and
     * each primary row pairs BOTH partner rows — the fan-out the ruling's WARNING exists to make
     * traceable.
     */
    @Test
    void aCharacterKeyColumnAbsentOnOneSideIsDroppedFromTheKey()
    {
        // The primary has no VISITNUM at all; AE has it, holding "" on row 1 only.
        IDataTable ae = RealTables.of(AE).str(USUBJID, "P1", "P1").str(VISIT, "2", "")
                .str(AETERM, "HEADACHE", "NAUSEA").build();
        assertEquals(FAN_OUT, rows(expand(dmWithoutVisit(), ae, List.of(USUBJID, VISIT)), AETERM),
                "REG-Q3: VISITNUM is on AE only, so it leaves the key and every primary row pairs"
                        + " both AE rows. '[0:NAUSEA, 1:NAUSEA]' is the retired JKM R7 one-side"
                        + " default (\"\" on the primary side)");
    }


    /**
     * ⭐ Absent on ONE side, numeric: dropped the same way — the retired default was {@code MIS} for
     * a numeric column, and it is gone with the {@code ""} of the character case.
     */
    @Test
    void aNumericKeyColumnAbsentOnOneSideIsDroppedFromTheKey()
    {
        IDataTable ae = RealTables.of(AE).str(USUBJID, "P1", "P1").lng(VISIT, 2L, null)
                .str(AETERM, "HEADACHE", "NAUSEA").build();
        assertEquals(FAN_OUT, rows(expand(dmWithoutVisit(), ae, List.of(USUBJID, VISIT)), AETERM),
                "REG-Q3: a numeric key column on one side only leaves the key too. '[0:NAUSEA,"
                        + " 1:NAUSEA]' is the retired MIS default pairing the missing cell");
    }


    /**
     * ⭐⭐ Absent on BOTH sides: the component is <b>dropped</b> from the key, so the join is decided
     * by the remaining component alone. Behaviour-preserving — both sides carry the same constant,
     * so the component could not discriminate anyway.
     */
    @Test
    void aKeyColumnAbsentOnBOTHSidesIsDroppedFromTheKey()
    {
        IDataTable dm = MockTable.of().col(USUBJID, "P1", "P2").col("AGE", "34", "51").name("DM")
                .build();
        IDataTable ae = MockTable.of().col(USUBJID, "P2", "P1").col("AETERM", "NAUSEA", "HEADACHE")
                .name(AE).build();
        // VISITNUM exists in NEITHER table.
        assertEquals(List.of("0:HEADACHE", "1:NAUSEA"),
                rows(expand(dm, ae, List.of(USUBJID, VISIT)), "AETERM"),
                "R7: VISITNUM is absent on both sides, so it leaves the key and USUBJID decides."
                        + " ':null' would mean the dropped component still killed the match");
    }


    /**
     * ⛔⛔ ALL components absent: the key is empty, which is a cartesian product rather than a join,
     * so the rule ERRORS. ⚠ The control matters more than the throw: without it this test would
     * pass over a fixture that simply had no rows.
     */
    @Test
    void aKeyWhoseEVERYComponentIsAbsentOnBothSidesIsARuleError()
    {
        IDataTable dm = MockTable.of().col("AGE", "34", "51").name("DM").build();
        IDataTable ae = MockTable.of().col("AETERM", "HEADACHE", "NAUSEA").name(AE).build();
        var thrown = assertThrows(DegenerateJoinKeyException.class,
                () -> ExecCalls.expand(dm, List.of(md(List.of(USUBJID, VISIT))),
                        Map.of("DM", dm, AE, ae)::get, "R-TEST"),
                "R7: an empty key must ERROR, never match everything against everything");
        assertTrue(String.valueOf(thrown.getMessage()).contains("Requirements.Variables.All"),
                "the message must name the authored way out (the requirement gate), not only the"
                        + " defect: " + thrown.getMessage());
        assertEquals(2, dm.getRowCount(), "control: the fixture really has rows to join");
    }


    /**
     * ⛔ Every component on ONE side only: each leaves the key, none is left — the same ERROR as an
     * all-absent key ({@code REG-Q3}: <i>"no key column left ⇒ rule ERROR"</i>), raised by the
     * engine's gate so it names the authored key. Under the retired default it joined {@code ""}
     * against the present values and matched nothing.
     */
    @Test
    void aKeyWhoseEveryComponentIsOnOneSideOnlyIsARuleError()
    {
        IDataTable dm = RealTables.of("DM").str(USUBJID, "P1", "P2").build();
        IDataTable ae = RealTables.of(AE).str(VISIT, "1").str(AETERM, "HEADACHE").build();
        var thrown = assertThrows(DegenerateJoinKeyException.class,
                () -> ExecCalls.expand(dm, List.of(md(List.of(USUBJID, VISIT))),
                        Map.of("DM", dm, AE, ae)::get, "R-TEST"),
                "USUBJID on DM only and VISITNUM on AE only: no key column is on both sides");
        assertTrue(
                String.valueOf(thrown.getMessage())
                        .contains("no key column of (USUBJID, VISITNUM) is present on both"),
                thrown.getMessage());
    }


    /**
     * ⭐ The authored {@code keep_missings: false} path — {@code JKM R4}'s flag-OFF arm — judges the
     * components that stay in the key only: a dropped column is no longer a blank key part on every
     * primary row, so the flag no longer empties the join.
     */
    @Test
    void anAuthoredKeepMissingsFalseJudgesOnlyTheComponentsLeftInTheKey()
    {
        IDataTable ae = RealTables.of(AE).str(USUBJID, "P1", "P1").str(VISIT, "2", "")
                .str(AETERM, "HEADACHE", "NAUSEA").build();
        MatchDataset m = md(List.of(USUBJID, VISIT));
        m.setKeepMissings(Boolean.FALSE);
        assertEquals(FAN_OUT, rows(expand(dmWithoutVisit(), ae, m), AETERM),
                "keep_missings:false drops blank-keyed rows over the KEPT components; VISITNUM left"
                        + " the key, so its blank AE cell drops nothing. '[0:null, 1:null]' is the"
                        + " retired default (every primary row blank at VISITNUM)");
    }


    /**
     * ⭐ Non-vacuity control for the flag: on the same one-side shape it still governs a blank in a
     * KEPT component — a blank USUBJID pairs a blank USUBJID by default and nothing under
     * {@code keep_missings: false}.
     */
    @Test
    void theFlagStillGovernsABlankInAKeptComponent()
    {
        IDataTable dm = RealTables.of("DM").str(USUBJID, "P1", "").str("AGE", "34", "51").build();
        IDataTable ae = RealTables.of(AE).str(USUBJID, "P1", "").str(VISIT, "2", "")
                .str(AETERM, "HEADACHE", "NAUSEA").build();
        MatchDataset kept = md(List.of(USUBJID, VISIT));
        assertTrue(kept.keepMissingKeys(), "JKM R4: an unauthored flag means KEEP");
        assertEquals(List.of("0:HEADACHE", "1:NAUSEA"), rows(expand(dm, ae, kept), AETERM),
                "KEEP: P1 pairs P1 and \"\" pairs \"\" on USUBJID alone. '0:null' would mean the"
                        + " dropped VISITNUM still keyed (the primary's retired \"\" against \"2\")");
        MatchDataset dropped = md(List.of(USUBJID, VISIT));
        dropped.setKeepMissings(Boolean.FALSE);
        assertEquals(List.of("0:HEADACHE", "1:null"), rows(expand(dm, ae, dropped), AETERM),
                "DROP: the blank-USUBJID rows take no part; '1:NAUSEA' would mean the flag was"
                        + " ignored");
    }

    /**
     * ⭐⭐ The same one-side cases read through a keyed part's <b>lookup</b>
     * ({@link JoinLookup#matchedRow} and {@link JoinLookup#lookup}) over a partner with ONE row per
     * subject (the lookup helper reads primary rows, so the fixture must not fan out): the dropped
     * column lets a record pair a partner whose value the retired default ({@code ""} /
     * {@code MIS}) would not have matched.
     */
    @Nested
    class ThroughTheKeyedPartLookup
    {

        private JoinLookup lookupOver(IDataTable primary, IDataTable joined, List<String> keys)
        {
            return ExecCalls.viewLookup(primary, AE, joined, keys);
        }


        /** Absent on the PRIMARY side, character: dropped, so the record pairs AE's "2" row. */
        @Test
        void aCharacterKeyColumnAbsentOnThePrimarySideIsDropped()
        {
            IDataTable ae = RealTables.of(AE).str(USUBJID, "P1").str(VISIT, "2")
                    .str(AETERM, "HEADACHE").build();
            IDataTable dm = dmWithoutVisit();
            JoinLookup lk = lookupOver(dm, ae, List.of(USUBJID, VISIT));
            assertTrue(lk.matchedRow(dm, 0L) && lk.matchedRow(dm, 1L),
                    "REG-Q3: VISITNUM left the key, USUBJID pairs. Unmatched is the retired \"\""
                            + " default against \"2\"");
            assertEquals("HEADACHE", lk.lookup(dm, 0L, AETERM));
        }


        /** Absent on the PRIMARY side, numeric: dropped the same way. */
        @Test
        void aNumericKeyColumnAbsentOnThePrimarySideIsDropped()
        {
            IDataTable ae = RealTables.of(AE).str(USUBJID, "P1").lng(VISIT, 2L)
                    .str(AETERM, "HEADACHE").build();
            IDataTable dm = dmWithoutVisit();
            JoinLookup lk = lookupOver(dm, ae, List.of(USUBJID, VISIT));
            assertTrue(lk.matchedRow(dm, 0L),
                    "unmatched is the retired MIS default against the present 2");
            assertEquals("HEADACHE", lk.lookup(dm, 0L, AETERM));
        }


        /**
         * ⭐ Absent on the JOINED side — the shape of the two {@code AE} Child entries the
         * {@code JKM R7} plan measured: the component leaves the key, so a primary row holding a
         * value pairs as well as one holding {@code ""}.
         */
        @Test
        void aCharacterKeyColumnAbsentOnTheJoinedSideIsDropped()
        {
            IDataTable suppae = RealTables.of("SUPPAE").str(USUBJID, "P1", "P1")
                    .str("IDVAR", "AESEQ", "").build();
            IDataTable ae = RealTables.of(AE).str(USUBJID, "P1").str(AETERM, "HEADACHE").build();
            JoinLookup lk = lookupOver(suppae, ae, List.of(USUBJID, "IDVAR"));
            assertTrue(lk.matchedRow(suppae, 0L),
                    "IDVAR=AESEQ pairs on USUBJID alone — unmatched is the retired default (the"
                            + " joined side's \"\" against AESEQ)");
            assertTrue(lk.matchedRow(suppae, 1L));
            assertEquals("HEADACHE", lk.lookup(suppae, 0L, AETERM));
        }


        /**
         * ⭐ An absent numeric side no longer keys the join at all: the record pairs a partner whose
         * cell is {@code MIS_A} — the retired {@code MIS} default paired only a {@code MIS} cell
         * ({@code JKM R5}'s marker identity is no longer reached by an absent column).
         */
        @Test
        void anAbsentNumericSideNoLongerKeysTheJoin()
        {
            IDataTable dm = RealTables.of("DM").str(USUBJID, "P1").str("AGE", "34").build();
            IDataTable ae = RealTables.of(AE).str(USUBJID, "P1")
                    .dbl(VISIT, MissingValue.MIS_A.asDouble()).str(AETERM, "MIS_A").build();
            JoinLookup lk = lookupOver(dm, ae, List.of(USUBJID, VISIT));
            assertTrue(lk.matchedRow(dm, 0L),
                    "unmatched is the retired MIS default against the MIS_A cell");
            assertEquals("MIS_A", lk.lookup(dm, 0L, AETERM));
        }


        /** Absent on the JOINED side, numeric: a present primary cell pairs as well. */
        @Test
        void aNumericKeyColumnAbsentOnTheJoinedSideIsDropped()
        {
            IDataTable dm = RealTables.of("DM").str(USUBJID, "P1", "P1").lng(VISIT, 2L, null)
                    .build();
            IDataTable ae = RealTables.of(AE).str(USUBJID, "P1").str(AETERM, "HEADACHE").build();
            JoinLookup lk = lookupOver(dm, ae, List.of(USUBJID, VISIT));
            assertTrue(lk.matchedRow(dm, 0L),
                    "VISITNUM=2 pairs on USUBJID alone — unmatched is the retired MIS default");
            assertTrue(lk.matchedRow(dm, 1L));
        }


        /**
         * ⭐ A date key (a {@code STRING} column holding ISO dates — there is no {@code DATE} in
         * {@code DataValueType}) is dropped like any other column; the lookup and the merged rows
         * agree.
         */
        @Test
        void aDateKeyColumnAbsentOnOneSideIsDroppedOnBothReads()
        {
            IDataTable dm = dmWithoutVisit();
            IDataTable ae = RealTables.of(AE).str(USUBJID, "P1").str("AESTDTC", "2024-01-05")
                    .str(AETERM, "HEADACHE").build();
            JoinLookup lk = lookupOver(dm, ae, List.of(USUBJID, "AESTDTC"));
            assertEquals("HEADACHE", lk.lookup(dm, 0L, AETERM),
                    "null is the retired \"\" default against the present date");
            assertEquals(List.of("0:HEADACHE", "1:HEADACHE"),
                    rows(expand(dm, ae, List.of(USUBJID, "AESTDTC")), AETERM),
                    "the two reads must agree: the date column left the key on both");
        }


        /**
         * Both-absent still leaves the key (unchanged behaviour; the control for the arm above).
         */
        @Test
        void aKeyColumnAbsentOnBothSidesStillLeavesTheKey()
        {
            IDataTable dm = MockTable.of().col(USUBJID, "P1", "P2").col("AGE", "34", "51")
                    .name("DM").build();
            IDataTable ae = MockTable.of().col(USUBJID, "P2", "P1")
                    .col("AETERM", "NAUSEA", "HEADACHE").name(AE).build();
            JoinLookup lk = lookupOver(dm, ae, List.of(USUBJID, VISIT));
            assertEquals("HEADACHE", lk.lookup(dm, 0L, "AETERM"));
            assertEquals("NAUSEA", lk.lookup(dm, 1L, "AETERM"));
        }
    }
}
