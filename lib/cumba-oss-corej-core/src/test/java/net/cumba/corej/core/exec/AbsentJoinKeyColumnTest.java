package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ {@code JKM R7} — an <b>absent</b> join-key column is <b>present-but-empty</b>, and an empty
 * key is an error.
 *
 * <p>
 * Owner, 2026-09-21: <i>"Absent columns are treated like present, but empty (with default value).
 * So if a string column is absent on one side, this is treated as empty and stays empty in the
 * join. Absent in both sides can be dropped from the join. if all columns are absent, then the rule
 * should fail with an error."</i>
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Why this class exists separately from {@link KeyMatchMissingJoinKeyTest}:</b> that class
 * varies the <em>cell</em>, this one varies the <em>column</em>. They are different rulings — R4/R5
 * govern a missing value, R7 governs an absent column — and the two builders in this package
 * disagreed on R7 while agreeing on nothing about R4. {@code KeyHashing:131} already dropped a
 * both-sides-absent component (right) and let an all-absent key match every row against every row
 * (wrong); {@code KeyMatchRowExpander} made <em>any</em> absent column match nothing (wrong three
 * times).
 * </p>
 *
 * <p>
 * ⛔ Every case below carries its own falsifier in the assertion message, because the shapes here
 * are degenerate by construction and a green over the wrong fixture proves nothing.
 * </p>
 */
class AbsentJoinKeyColumnTest
{

    private static final String AE = "AE";

    private static final String USUBJID = "USUBJID";

    private static final String VISIT = "VISITNUM";

    private static MatchDataset md(List<String> keys)
    {
        MatchDataset m = new MatchDataset();
        m.setName(AE);
        m.setKeys(keys);
        m.setJoinType("left");
        return m;
    }


    private static List<String> rows(KeyMatchRowExpander.KeyMatchExpansion exp, String col)
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


    private static KeyMatchRowExpander.KeyMatchExpansion expand(IDataTable dm, IDataTable ae,
            List<String> keys)
    {
        var exp = ExecCalls.expand(dm, List.of(md(keys)), Map.of("DM", dm, AE, ae)::get, "R-TEST");
        assertNotNull(exp, "one keyed entry is expandable, so the expansion must be built");
        return exp;
    }


    /**
     * ⭐ Absent on ONE side, character: the absent side contributes {@code ""} for every row, so it
     * pairs the rows whose present side genuinely holds {@code ""} — and only those.
     */
    @Test
    void aCharacterKeyColumnAbsentOnOneSideContributesTheEmptyString()
    {
        // The primary has no VISITNUM at all; AE has it, holding "" on row 1 only.
        IDataTable dm = MockTable.of().col(USUBJID, "P1", "P1").col("AGE", "34", "51").name("DM")
                .build();
        IDataTable ae = MockTable.of().col(USUBJID, "P1", "P1").col(VISIT, "2", "")
                .col("AETERM", "HEADACHE", "NAUSEA").name(AE).build();
        assertEquals(List.of("0:NAUSEA", "1:NAUSEA"),
                rows(expand(dm, ae, List.of(USUBJID, VISIT)), "AETERM"),
                "R7: the absent VISITNUM contributes \"\" on the primary side, so both primary rows"
                        + " match ONLY the AE row whose VISITNUM is \"\". '0:HEADACHE' would mean the"
                        + " component was ignored; ':null' would mean the pre-R7 match-nothing");
    }


    /**
     * ⭐ Absent on ONE side, numeric: the constant is a {@code MissingValue}, not {@code ""} — a
     * numeric cell cannot hold an empty string. It therefore pairs the rows whose present side is a
     * missing numeric.
     */
    @Test
    void aNumericKeyColumnAbsentOnOneSideContributesTheNumericMissing()
    {
        IDataTable dm = MockTable.of().col(USUBJID, "P1", "P1").col("AGE", "34", "51").name("DM")
                .build();
        IDataTable ae = MockTable.of().col(USUBJID, "P1", "P1").colLong(VISIT, 2L, null)
                .col("AETERM", "HEADACHE", "NAUSEA").name(AE).build();
        assertEquals(List.of("0:NAUSEA", "1:NAUSEA"),
                rows(expand(dm, ae, List.of(USUBJID, VISIT)), "AETERM"),
                "R7: an absent NUMERIC column contributes a MissingValue, so it matches the missing"
                        + " numeric cell and not the present 2. '0:HEADACHE' would mean it keyed as"
                        + " \"\" or was ignored");
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
     * ⭐ The authored {@code keep_missings: false} path — {@code JKM R4}'s flag-OFF arm, and the
     * only way to get the historical DROP back. With the character key absent on the primary side
     * every primary row is blank at that component, so nothing matches at all: a consequence of the
     * author's own declaration, recorded here so it is not read as a defect later.
     */
    @Test
    void anAuthoredKeepMissingsFalseRestoresTheDropAndThenMatchesNothing()
    {
        IDataTable dm = MockTable.of().col(USUBJID, "P1", "P1").col("AGE", "34", "51").name("DM")
                .build();
        IDataTable ae = MockTable.of().col(USUBJID, "P1", "P1").col(VISIT, "2", "")
                .col("AETERM", "HEADACHE", "NAUSEA").name(AE).build();
        MatchDataset m = md(List.of(USUBJID, VISIT));
        m.setKeepMissings(Boolean.FALSE);
        var exp = ExecCalls.expand(dm, List.of(m), Map.of("DM", dm, AE, ae)::get, "R-TEST");
        assertNotNull(exp);
        assertEquals(List.of("0:null", "1:null"), rows(exp, "AETERM"),
                "keep_missings:false drops every blank-keyed row, and an absent column makes every"
                        + " row blank. '0:NAUSEA' would mean the flag was ignored");
    }


    /**
     * ⭐ Non-vacuity control for the flag: the same fixture with the flag at its DEFAULT must give
     * the opposite answer. Without this, the test above would pass just as well if the expansion
     * had silently produced nothing for an unrelated reason.
     */
    @Test
    void theFlagIsWhatMakesTheDifference()
    {
        IDataTable dm = MockTable.of().col(USUBJID, "P1", "P1").col("AGE", "34", "51").name("DM")
                .build();
        IDataTable ae = MockTable.of().col(USUBJID, "P1", "P1").col(VISIT, "2", "")
                .col("AETERM", "HEADACHE", "NAUSEA").name(AE).build();
        MatchDataset kept = md(List.of(USUBJID, VISIT));
        assertTrue(kept.keepMissingKeys(), "JKM R4: an unauthored flag means KEEP");
        assertEquals(List.of("0:NAUSEA", "1:NAUSEA"),
                rows(expand(dm, ae, List.of(USUBJID, VISIT)), "AETERM"),
                "the default KEEP matches where the authored DROP matched nothing");
    }

    /**
     * ⭐⭐ The same three one-side cases on the <b>hashed</b> arm ({@code KeyHashing.KeyMatcher},
     * reached through {@link DatasetLookup}). Until 2026-09-25 this arm answered <i>no match</i>
     * for a one-side-absent component — the one row of {@code JKM R7} that was implemented on the
     * expander arm only, reverted here by the E4 non-harm review because a {@code Child: true}
     * entry then also reached this arm. {@code PLAN-hashed-join-arm-absent-columns} (option A)
     * removed that lookup and landed the rule here in the same change.
     *
     * <p>
     * ⚠ No shipped entry reaches this arm any more (every non-expandable keyed entry in both
     * corpora is {@code Child: true}), so no corpus scenario and no findings snapshot can see these
     * cases. These tests ARE the instrument; the falsifier in each message is the pre-fix answer.
     * </p>
     */
    @Nested
    class TheHashedArm
    {

        private DatasetLookup lookupOver(IDataTable joined, List<String> keys)
        {
            DatasetLookup lk = DatasetLookup.build(AE, joined, keys);
            assertNotNull(lk, "a keyed lookup over a non-null table must be built");
            return lk;
        }


        /** Absent on the PRIMARY side, character: the primary contributes {@code ""}. */
        @Test
        void aCharacterKeyColumnAbsentOnThePrimarySideContributesTheEmptyString()
        {
            IDataTable dm = MockTable.of().col(USUBJID, "P1", "P1").col("AGE", "34", "51")
                    .name("DM").build();
            IDataTable ae = MockTable.of().col(USUBJID, "P1", "P1").col(VISIT, "2", "")
                    .col("AETERM", "HEADACHE", "NAUSEA").name(AE).build();
            DatasetLookup lk = lookupOver(ae, List.of(USUBJID, VISIT));
            assertTrue(lk.matchedRow(dm, 0L) && lk.matchedRow(dm, 1L),
                    "R7 on the hashed arm: the absent VISITNUM contributes \"\" on the primary"
                            + " side, so both primary rows match the AE row whose VISITNUM is"
                            + " \"\". Unmatched is the pre-fix guard (`return false`)");
            assertEquals("NAUSEA", lk.lookup(dm, 0L, "AETERM"),
                    "and it is THAT row: HEADACHE would mean the component was ignored");
        }


        /** Absent on the PRIMARY side, numeric: the constant is the numeric missing, not "". */
        @Test
        void aNumericKeyColumnAbsentOnThePrimarySideContributesTheNumericMissing()
        {
            IDataTable dm = MockTable.of().col(USUBJID, "P1", "P1").col("AGE", "34", "51")
                    .name("DM").build();
            IDataTable ae = MockTable.of().col(USUBJID, "P1", "P1").colLong(VISIT, 2L, null)
                    .col("AETERM", "HEADACHE", "NAUSEA").name(AE).build();
            DatasetLookup lk = lookupOver(ae, List.of(USUBJID, VISIT));
            assertTrue(lk.matchedRow(dm, 0L),
                    "R7: an absent NUMERIC column contributes a MissingValue, which pairs the"
                            + " missing numeric cell. Unmatched is the pre-fix guard");
            assertEquals("NAUSEA", lk.lookup(dm, 0L, "AETERM"),
                    "HEADACHE would mean the absent side keyed as the present 2");
        }


        /**
         * ⭐ Absent on the JOINED side — the shape of the two {@code AE} Child entries the plan
         * measured ({@code [n, -1, -1]} on the joined side): a primary row whose present value is
         * {@code ""} pairs the joined side's {@code ""}; a primary row holding a value does not.
         */
        @Test
        void aCharacterKeyColumnAbsentOnTheJoinedSidePairsOnlyTheEmptyPrimaryCell()
        {
            IDataTable suppae = MockTable.of().col(USUBJID, "P1", "P1").col("IDVAR", "AESEQ", "")
                    .name("SUPPAE").build();
            IDataTable ae = MockTable.of().col(USUBJID, "P1").col("AETERM", "HEADACHE").name(AE)
                    .build();
            DatasetLookup lk = lookupOver(ae, List.of(USUBJID, "IDVAR"));
            assertFalse(lk.matchedRow(suppae, 0L),
                    "IDVAR=AESEQ against the joined side's \"\" is a mismatch — matched would"
                            + " mean the absent component was dropped instead of defaulted");
            assertTrue(lk.matchedRow(suppae, 1L),
                    "IDVAR=\"\" against the joined side's \"\" is a match — unmatched is the"
                            + " pre-fix guard");
            assertEquals("HEADACHE", lk.lookup(suppae, 1L, "AETERM"));
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
            DatasetLookup lk = lookupOver(ae, List.of(USUBJID, VISIT));
            assertEquals("HEADACHE", lk.lookup(dm, 0L, "AETERM"));
            assertEquals("NAUSEA", lk.lookup(dm, 1L, "AETERM"));
        }
    }
}
