package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.cumba.cdisc.core.model.MatchDataset;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ChildMatchPreMerger}. Covers the early-return guards (E1-E5, E7-E9) and the
 * dispatch-style merge behaviour for each edge case enumerated in
 * {@code plans/PLAN-update-core-preMerge.md} (E10-E20).
 * <p>
 * Uses real {@link DataTableMeta} + {@link CachedDataTableColumn}-backed tables rather than Mockito
 * mocks because {@link net.cumba.datatable.impl.view.MergeDataTable#createMetaData} calls
 * {@link DataTableMeta#builderFrom} on the primary's meta, which dereferences
 * {@code getAllColumns()} — a Mockito mock returns {@code null} and NPEs.
 * </p>
 */
class ChildMatchPreMergerTest
{

    // ----------------------------------------------------------------------------------------
    // Early-return paths
    // ----------------------------------------------------------------------------------------

    @Test
    void preMerge_nullTable_returnsNull()
    {
        IDataTable result = ChildMatchPreMerger.preMerge(null, List.of(), _ -> null, null, null);
        assertSame(null, result);
    }


    @Test
    void preMerge_nullMatchDatasets_returnsTable()
    {
        IDataTable table = TableFixture.of("AE").str("X", "1").build();
        IDataTable result = ChildMatchPreMerger.preMerge(table, null, _ -> null, null, null);
        assertSame(table, result);
    }


    @Test
    void preMerge_emptyMatchDatasets_returnsTable()
    {
        IDataTable table = TableFixture.of("AE").str("X", "1").build();
        IDataTable result = ChildMatchPreMerger.preMerge(table, List.of(), _ -> null, null, null);
        assertSame(table, result);
    }


    @Test
    void preMerge_noChildEntries_returnsTable()
    {
        IDataTable table = TableFixture.of("AE").str("X", "1").build();
        IDataTable result = ChildMatchPreMerger.preMerge(table, List.of(md("DM", false)), _ -> null,
                null, null);
        assertSame(table, result);
    }


    @Test
    void preMerge_noChildEntries_nullChild_returnsTable()
    {
        IDataTable table = TableFixture.of("AE").str("X", "1").build();
        IDataTable result = ChildMatchPreMerger.preMerge(table, List.of(md("DM", null)), _ -> null,
                null, null);
        assertSame(table, result);
    }


    @Test
    void preMerge_childButMissingIdvarUsubjid_returnsTable()
    {
        // Primary lacks IDVAR/IDVARVAL/USUBJID → SUPP-style merge can't proceed (E3).
        IDataTable table = TableFixture.of("SUPPAE").str("STUDYID", "S1").str("VAL", "x").build();
        IDataTable result = ChildMatchPreMerger.preMerge(table, List.of(md("AE", true)), _ -> null,
                null, null);
        assertSame(table, result);
    }

    // ----------------------------------------------------------------------------------------
    // E10: child wins — primary's value takes precedence; augmented column never added.
    // ----------------------------------------------------------------------------------------


    @Test
    void preMerge_childWins_primaryColumnNotOverridden()
    {
        // Primary already exposes AETERM; the parent's AETERM must not appear as a separate
        // augmented column. Reading AETERM through the merged table must still hit the primary
        // value, not the parent's.
        IDataTable primary = TableFixture.of("SUPPAE")//
                .str("STUDYID", "S1", "S1")//
                .str("USUBJID", "U1", "U1")//
                .str("IDVAR", "AESEQ", "AESEQ")//
                .str("IDVARVAL", "1", "2")//
                .str("AETERM", "primary-term-1", "primary-term-2")//
                .str("RDOMAIN", "AE", "AE")//
                .build();
        IDataTable parent = TableFixture.of("AE")//
                .str("STUDYID", "S1", "S1")//
                .str("USUBJID", "U1", "U1")//
                .str("AESEQ", "1", "2")//
                .str("AETERM", "parent-term-1", "parent-term-2")//
                .build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary, List.of(md("AE", true)),
                resolver("AE", parent), "CORE-E10", null);

        assertNotSame(primary, result);
        int aetermIdx = result.getMetaData().getColumnIndex("AETERM");
        assertEquals("primary-term-1",
                result.getColumn(aetermIdx).getDataValue(0).getValueAsString());
        assertEquals("primary-term-2",
                result.getColumn(aetermIdx).getDataValue(1).getValueAsString());
    }

    // ----------------------------------------------------------------------------------------
    // E11: duplicate parent keys → first-wins.
    // ----------------------------------------------------------------------------------------


    @Test
    void preMerge_duplicateParentKeys_firstWins()
    {
        // Two parent rows share (STUDYID, USUBJID, AESEQ) = (S1, U1, 1). Both expose a different
        // AESEV — the merged primary row must see the FIRST parent row's value.
        IDataTable primary = TableFixture.of("SUPPAE")//
                .str("STUDYID", "S1")//
                .str("USUBJID", "U1")//
                .str("IDVAR", "AESEQ")//
                .str("IDVARVAL", "1")//
                .str("RDOMAIN", "AE")//
                .build();
        IDataTable parent = TableFixture.of("AE")//
                .str("STUDYID", "S1", "S1")//
                .str("USUBJID", "U1", "U1")//
                .str("AESEQ", "1", "1")//
                .str("AESEV", "MILD-FIRST", "SEVERE-SECOND")//
                .build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary, List.of(md("AE", true)),
                resolver("AE", parent), "CORE-E11", null);

        int aesevIdx = result.getMetaData().getColumnIndex("AESEV");
        assertEquals("MILD-FIRST", result.getColumn(aesevIdx).getDataValue(0).getValueAsString());
    }

    // ----------------------------------------------------------------------------------------
    // J7 part 2 / Fix #358 (CORE-000206): split parent domain ("LB" → lbch/lbhe/lbur, no
    // standalone "LB"). The pre-merge resolves the parent to the row-stacked UNION of the
    // members (SplitDomainResolution), so every SUPP row can reach its own parent record —
    // orphan findings appear only where NO member matches. The earlier first-member-only
    // reproduction of Python (merged_domains dedups by .domain) is a filed parity divergence
    // (PLAN-match-datasets-split-union.md §7).
    // ----------------------------------------------------------------------------------------


    @Test
    void preMerge_splitParent_resolvesUnion()
    {
        // Row 0 (IDVARVAL=1) matches lbch; row 1 (IDVARVAL=9, present only in lbhe) matches TOO —
        // proving the parent resolves to the lbch∪lbhe union, not the first member alone.
        IDataTable primary = TableFixture.of("SUPPLBCH")//
                .str("USUBJID", "U1", "U2")//
                .str("IDVAR", "LBSEQ", "LBSEQ")//
                .str("IDVARVAL", "1", "9")//
                .str("RDOMAIN", "LB", "LB")//
                .build();
        IDataTable lbch = TableFixture.of("LBCH")//
                .str("DOMAIN", "LB")//
                .str("USUBJID", "U1")//
                .str("LBSEQ", "1")//
                .str("LBORRES", "res-ch")//
                .build();
        IDataTable lbhe = TableFixture.of("LBHE")//
                .str("DOMAIN", "LB")//
                .str("USUBJID", "U2")//
                .str("LBSEQ", "9")//
                .str("LBORRES", "res-he")//
                .build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary,
                List.of(md("SUPP--", true, "USUBJID", "IDVAR", "IDVARVAL")),
                inventoryResolver(Map.of("LBCH", lbch, "LBHE", lbhe)), "CORE-000206", null);

        assertNotSame(primary, result);
        int lborresIdx = result.getMetaData().getColumnIndex("LBORRES");
        assertEquals("res-ch", result.getColumn(lborresIdx).getDataValue(0).getValueAsString());
        // Row 1 resolves in lbhe — a member of the union — so it matches (pre-Fix #358: MISSING).
        assertEquals("res-he", result.getColumn(lborresIdx).getDataValue(1).getValueAsString());
    }


    @Test
    void preMerge_splitParent_threeMembers_eachRowReachesItsOwnMember()
    {
        IDataTable primary = TableFixture.of("SUPPLB")//
                .str("USUBJID", "U1", "U2", "U3", "U4")//
                .str("IDVAR", "LBSEQ", "LBSEQ", "LBSEQ", "LBSEQ")//
                .str("IDVARVAL", "1", "9", "4", "77")//
                .str("RDOMAIN", "LB", "LB", "LB", "LB")//
                .build();
        IDataTable lbch = TableFixture.of("LBCH").str("DOMAIN", "LB").str("USUBJID", "U1")
                .str("LBSEQ", "1").str("LBORRES", "res-ch").build();
        IDataTable lbhe = TableFixture.of("LBHE").str("DOMAIN", "LB").str("USUBJID", "U2")
                .str("LBSEQ", "9").str("LBORRES", "res-he").build();
        IDataTable lbur = TableFixture.of("LBUR").str("DOMAIN", "LB").str("USUBJID", "U3")
                .str("LBSEQ", "4").str("LBORRES", "res-ur").build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary,
                List.of(md("SUPP--", true, "USUBJID", "IDVAR", "IDVARVAL")),
                inventoryResolver(Map.of("LBCH", lbch, "LBHE", lbhe, "LBUR", lbur)), "CORE-000206",
                null);

        int lborresIdx = result.getMetaData().getColumnIndex("LBORRES");
        assertEquals("res-ch", result.getColumn(lborresIdx).getDataValue(0).getValueAsString());
        assertEquals("res-he", result.getColumn(lborresIdx).getDataValue(1).getValueAsString());
        assertEquals("res-ur", result.getColumn(lborresIdx).getDataValue(2).getValueAsString());
        // Row 3 (IDVARVAL=77) matches NO member — the genuine orphan stays missing.
        assertTrue(result.getColumn(lborresIdx).getDataValue(3).isMissingOrInvalid());
    }


    @Test
    void preMerge_splitParent_memberWithoutTheIdvarColumn_rowsAreMissingNotAnError()
    {
        // lbur lacks LBSEQ entirely: union rows from lbur read LBSEQ as missing, so a SUPP row
        // pointing into lbur simply finds no match — no exception.
        IDataTable primary = TableFixture.of("SUPPLB")//
                .str("USUBJID", "U1", "U3")//
                .str("IDVAR", "LBSEQ", "LBSEQ")//
                .str("IDVARVAL", "1", "4")//
                .str("RDOMAIN", "LB", "LB")//
                .build();
        IDataTable lbch = TableFixture.of("LBCH").str("DOMAIN", "LB").str("USUBJID", "U1")
                .str("LBSEQ", "1").str("LBORRES", "res-ch").build();
        IDataTable lbur = TableFixture.of("LBUR").str("DOMAIN", "LB").str("USUBJID", "U3")
                .str("LBORRES", "res-ur").build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary,
                List.of(md("SUPP--", true, "USUBJID", "IDVAR", "IDVARVAL")),
                inventoryResolver(Map.of("LBCH", lbch, "LBUR", lbur)), "CORE-000206", null);

        int lborresIdx = result.getMetaData().getColumnIndex("LBORRES");
        assertEquals("res-ch", result.getColumn(lborresIdx).getDataValue(0).getValueAsString());
        assertTrue(result.getColumn(lborresIdx).getDataValue(1).isMissingOrInvalid(),
                "a member lacking the IDVAR column contributes missing cells, not a failure");
    }


    @Test
    void preMerge_splitParent_typeClashAcrossMembers_throwsForTheRuleErrorMapping()
    {
        // Ruling 1: an un-unionable split (column type clash) must surface as a rule ERROR —
        // preMerge throws InvalidJoinedDomainException and RuleRunner.execute maps it to the
        // __error__ sentinel (asserted end-to-end in RuleRunnerSplitJoinTest).
        IDataTable primary = TableFixture.of("SUPPLBCH")//
                .str("USUBJID", "U1")//
                .str("IDVAR", "LBSEQ")//
                .str("IDVARVAL", "1")//
                .str("RDOMAIN", "LB")//
                .build();
        IDataTable lbch = TableFixture.of("LBCH").str("DOMAIN", "LB").str("USUBJID", "U1")
                .lng("LBSTRESN", 1L).str("LBSEQ", "1").build();
        IDataTable lbhe = TableFixture.of("LBHE").str("DOMAIN", "LB").str("USUBJID", "U2")
                .str("LBSTRESN", "high").str("LBSEQ", "9").build();

        List<MatchDataset> mds = List.of(md("SUPP--", true, "USUBJID", "IDVAR", "IDVARVAL"));
        DatasetResolver resolver = inventoryResolver(Map.of("LBCH", lbch, "LBHE", lbhe));
        InvalidJoinedDomainException ex = org.junit.jupiter.api.Assertions.assertThrows(
                InvalidJoinedDomainException.class,
                () -> ChildMatchPreMerger.preMerge(primary, mds, resolver, "CORE-000206", null));
        assertTrue(ex.getMessage().contains("LBSTRESN"), ex.getMessage());
    }

    // ----------------------------------------------------------------------------------------
    // E14: column present on one parent, absent on another. Per-row dispatch chooses the right
    // parent; rows aimed at the parent that lacks the column return MISSING for that cell.
    // ----------------------------------------------------------------------------------------


    @Test
    void preMerge_columnAbsentOnOneParent_missingForThoseRows()
    {
        // Row 0 → AE (which has AETERM); row 1 → CM (which does NOT have AETERM).
        IDataTable primary = TableFixture.of("CO")//
                .str("STUDYID", "S1", "S1")//
                .str("USUBJID", "U1", "U1")//
                .str("RDOMAIN", "AE", "CM")//
                .str("IDVAR", "AESEQ", "CMSEQ")//
                .str("IDVARVAL", "1", "1")//
                .build();
        IDataTable ae = TableFixture.of("AE")//
                .str("STUDYID", "S1")//
                .str("USUBJID", "U1")//
                .str("AESEQ", "1")//
                .str("AETERM", "headache")//
                .build();
        IDataTable cm = TableFixture.of("CM")//
                .str("STUDYID", "S1")//
                .str("USUBJID", "U1")//
                .str("CMSEQ", "1")//
                .str("CMTRT", "aspirin")//
                .build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary, List.of(md("AE", true)),
                multi(Map.of("AE", ae, "CM", cm)), "CORE-E14", null);

        int aetermIdx = result.getMetaData().getColumnIndex("AETERM");
        assertEquals("headache", result.getColumn(aetermIdx).getDataValue(0).getValueAsString());
        assertTrue(result.getColumn(aetermIdx).getDataValue(1).isMissingOrInvalid(),
                "row 1 points to CM which lacks AETERM — must be MISSING");
    }

    // ----------------------------------------------------------------------------------------
    // E16: STUDYID is NOT a declared key (the shipped rules declare [USUBJID, IDVAR, IDVARVAL]), so
    // it is ignored entirely — Python parity. Match keys on USUBJID + the IDVAR-named value only.
    // ----------------------------------------------------------------------------------------


    @Test
    void preMerge_studyIdNotDeclared_isIgnored_matches()
    {
        // Primary has no STUDYID column, parent has STUDYID — under the old hard-coded key this was
        // an asymmetric STUDYID mismatch (no match). With STUDYID dropped (not declared) the match
        // succeeds on USUBJID alone, mirroring the Python engine.
        IDataTable primary = TableFixture.of("SUPPAE")//
                // intentionally no STUDYID column
                .str("USUBJID", "U1")//
                .str("IDVAR", "AESEQ")//
                .str("IDVARVAL", "1")//
                .str("RDOMAIN", "AE")//
                .build();
        IDataTable parent = TableFixture.of("AE")//
                .str("STUDYID", "S1")//
                .str("USUBJID", "U1")//
                .str("AESEQ", "1")//
                .str("AETERM", "headache")//
                .build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary, List.of(md("AE", true)),
                resolver("AE", parent), "CORE-E16-studyid-ignored", null);

        int aetermIdx = result.getMetaData().getColumnIndex("AETERM");
        assertEquals("headache", result.getColumn(aetermIdx).getDataValue(0).getValueAsString(),
                "STUDYID is not declared, so it must be ignored — match succeeds on USUBJID");
    }


    @Test
    void preMerge_multiStudySameUsubjid_matchesAcrossStudy()
    {
        // Different STUDYID on primary vs parent, same USUBJID. With STUDYID dropped (Python
        // parity)
        // the merge succeeds; the old hard-coded STUDYID key would have blocked it.
        IDataTable primary = TableFixture.of("SUPPAE")//
                .str("STUDYID", "S1")//
                .str("USUBJID", "U1")//
                .str("IDVAR", "AESEQ")//
                .str("IDVARVAL", "1")//
                .str("RDOMAIN", "AE")//
                .build();
        IDataTable parent = TableFixture.of("AE")//
                .str("STUDYID", "S2")// deliberately different
                .str("USUBJID", "U1")//
                .str("AESEQ", "1")//
                .str("AETERM", "headache")//
                .build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary, List.of(md("AE", true)),
                resolver("AE", parent), "CORE-E16-multistudy", null);

        int aetermIdx = result.getMetaData().getColumnIndex("AETERM");
        assertEquals("headache", result.getColumn(aetermIdx).getDataValue(0).getValueAsString(),
                "STUDYID is not declared, so a differing STUDYID must not block the match");
    }


    @Test
    void preMerge_studyIdDeclaredInKeys_isHonored()
    {
        // When STUDYID IS explicitly declared in Keys, a differing STUDYID must block the match.
        IDataTable primary = TableFixture.of("SUPPAE")//
                .str("STUDYID", "S2")//
                .str("USUBJID", "U1")//
                .str("IDVAR", "AESEQ")//
                .str("IDVARVAL", "1")//
                .str("RDOMAIN", "AE")//
                .build();
        IDataTable parent = TableFixture.of("AE")//
                .str("STUDYID", "S1")// differs from primary
                .str("USUBJID", "U1")//
                .str("AESEQ", "1")//
                .str("AETERM", "should-not-leak")//
                .build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary,
                List.of(md("AE", true, "STUDYID", "USUBJID", "IDVAR", "IDVARVAL")),
                resolver("AE", parent), "CORE-E16-studyid-declared", null);

        int aetermIdx = result.getMetaData().getColumnIndex("AETERM");
        assertTrue(result.getColumn(aetermIdx).getDataValue(0).isMissingOrInvalid(),
                "declared STUDYID must be honored — differing STUDYID blocks the match");
    }


    @Test
    void preMerge_honorsNonUsubjidStandardKey()
    {
        // Keys declare POOLID (not USUBJID) as the standard key — the join must use POOLID. The
        // primary has no USUBJID column at all; the old hard-coded USUBJID requirement would have
        // returned the primary unchanged (no merge).
        IDataTable primary = TableFixture.of("SUPPAE")//
                .str("POOLID", "P1", "P2")//
                .str("IDVAR", "AESEQ", "AESEQ")//
                .str("IDVARVAL", "1", "1")//
                .str("RDOMAIN", "AE", "AE")//
                .build();
        IDataTable parent = TableFixture.of("AE")//
                .str("POOLID", "P1")//
                .str("AESEQ", "1")//
                .str("AETERM", "headache")//
                .build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary,
                List.of(md("AE", true, "POOLID", "IDVAR", "IDVARVAL")), resolver("AE", parent),
                "CORE-poolid-key", null);

        int aetermIdx = result.getMetaData().getColumnIndex("AETERM");
        assertEquals("headache", result.getColumn(aetermIdx).getDataValue(0).getValueAsString(),
                "row with POOLID=P1 matches the parent on POOLID");
        assertTrue(result.getColumn(aetermIdx).getDataValue(1).isMissingOrInvalid(),
                "row with POOLID=P2 has no parent on POOLID — must be MISSING");
    }


    @Test
    void preMerge_missingStandardKeyValue_dropsThatKey()
    {
        // Per-key null semantics (Python pd.notna): a primary row whose standard-key (USUBJID)
        // value
        // is absent drops that key and matches the parent on the IDVAR-named value alone, via the
        // linear-scan fallback. Here the primary has no USUBJID column.
        IDataTable primary = TableFixture.of("SUPPAE")//
                // intentionally no USUBJID column → USUBJID value is null per row → key dropped
                .str("IDVAR", "AESEQ")//
                .str("IDVARVAL", "7")//
                .str("RDOMAIN", "AE")//
                .build();
        IDataTable parent = TableFixture.of("AE")//
                .str("USUBJID", "U1")//
                .str("AESEQ", "7")//
                .str("AETERM", "headache")//
                .build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary, List.of(md("AE", true)),
                resolver("AE", parent), "CORE-key-null-fallback", null);

        int aetermIdx = result.getMetaData().getColumnIndex("AETERM");
        assertEquals("headache", result.getColumn(aetermIdx).getDataValue(0).getValueAsString(),
                "missing USUBJID drops the key; match falls back to the IDVAR value (AESEQ=7)");
    }


    @Test
    void preMerge_studyIdMissingOnBothSides_matches()
    {
        // Symmetric: both sides lack STUDYID → match should succeed via (USUBJID, IDVARVAL).
        IDataTable primary = TableFixture.of("SUPPAE")//
                .str("USUBJID", "U1")//
                .str("IDVAR", "AESEQ")//
                .str("IDVARVAL", "1")//
                .str("RDOMAIN", "AE")//
                .build();
        IDataTable parent = TableFixture.of("AE")//
                .str("USUBJID", "U1")//
                .str("AESEQ", "1")//
                .str("AETERM", "headache")//
                .build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary, List.of(md("AE", true)),
                resolver("AE", parent), "CORE-E16-symmetric", null);

        int aetermIdx = result.getMetaData().getColumnIndex("AETERM");
        assertEquals("headache", result.getColumn(aetermIdx).getDataValue(0).getValueAsString());
    }

    // ----------------------------------------------------------------------------------------
    // E17: numeric IDVAR on the parent (AESEQ is LONG) ↔ string IDVARVAL on the primary ("5").
    // ----------------------------------------------------------------------------------------


    @Test
    void preMerge_numericIdvar_matchesStringIdvarval()
    {
        IDataTable primary = TableFixture.of("SUPPAE")//
                .str("STUDYID", "S1")//
                .str("USUBJID", "U1")//
                .str("IDVAR", "AESEQ")//
                .str("IDVARVAL", "5")// String "5"
                .str("RDOMAIN", "AE")//
                .build();
        IDataTable parent = TableFixture.of("AE")//
                .str("STUDYID", "S1")//
                .str("USUBJID", "U1")//
                .lng("AESEQ", 5L)// Long 5
                .str("AETERM", "headache")//
                .build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary, List.of(md("AE", true)),
                resolver("AE", parent), "CORE-E17", null);

        int aetermIdx = result.getMetaData().getColumnIndex("AETERM");
        assertEquals("headache", result.getColumn(aetermIdx).getDataValue(0).getValueAsString());
    }

    // ----------------------------------------------------------------------------------------
    // E18: rowCount > Integer.MAX_VALUE — primary returned unchanged + WARN.
    // ----------------------------------------------------------------------------------------


    @Test
    void preMerge_rowCountOverflow_returnsPrimaryUnchanged()
    {
        IDataTable primary = new OverflowingTable(TableFixture.of("SUPPAE")//
                .str("STUDYID", "S1")//
                .str("USUBJID", "U1")//
                .str("IDVAR", "AESEQ")//
                .str("IDVARVAL", "1")//
                .str("RDOMAIN", "AE")//
                .build());
        IDataTable parent = TableFixture.of("AE")//
                .str("STUDYID", "S1")//
                .str("USUBJID", "U1")//
                .str("AESEQ", "1")//
                .str("AETERM", "headache")//
                .build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary, List.of(md("AE", true)),
                resolver("AE", parent), "CORE-E18", null);
        assertSame(primary, result);
    }

    // ----------------------------------------------------------------------------------------
    // E19: mixed RDOMAIN — per-row dispatch to two different parents.
    // ----------------------------------------------------------------------------------------


    @Test
    void preMerge_mixedRdomain_perRowDispatch()
    {
        IDataTable primary = TableFixture.of("CO")//
                .str("STUDYID", "S1", "S1")//
                .str("USUBJID", "U1", "U1")//
                .str("RDOMAIN", "AE", "CM")//
                .str("IDVAR", "AESEQ", "CMSEQ")//
                .str("IDVARVAL", "1", "1")//
                .build();
        IDataTable ae = TableFixture.of("AE")//
                .str("STUDYID", "S1")//
                .str("USUBJID", "U1")//
                .str("AESEQ", "1")//
                .str("AETERM", "headache")//
                .build();
        IDataTable cm = TableFixture.of("CM")//
                .str("STUDYID", "S1")//
                .str("USUBJID", "U1")//
                .str("CMSEQ", "1")//
                .str("CMTRT", "aspirin")//
                .build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary, List.of(md("AE", true)),
                multi(Map.of("AE", ae, "CM", cm)), "CORE-E19", null);

        int aetermIdx = result.getMetaData().getColumnIndex("AETERM");
        int cmtrtIdx = result.getMetaData().getColumnIndex("CMTRT");
        // Row 0 → AE → AETERM populated, CMTRT MISSING.
        assertEquals("headache", result.getColumn(aetermIdx).getDataValue(0).getValueAsString());
        assertTrue(result.getColumn(cmtrtIdx).getDataValue(0).isMissingOrInvalid(),
                "row 0 hits AE which lacks CMTRT");
        // Row 1 → CM → CMTRT populated, AETERM MISSING.
        assertEquals("aspirin", result.getColumn(cmtrtIdx).getDataValue(1).getValueAsString());
        assertTrue(result.getColumn(aetermIdx).getDataValue(1).isMissingOrInvalid(),
                "row 1 hits CM which lacks AETERM");
    }

    // ----------------------------------------------------------------------------------------
    // E20: mixed IDVAR within one parent — two indexes built per (parent, IDVAR).
    // ----------------------------------------------------------------------------------------


    @Test
    void preMerge_mixedIdvar_perPairIndex()
    {
        // Row 0 matches AE via AESEQ=1; row 1 matches AE via AESPID="P-1".
        IDataTable primary = TableFixture.of("SUPPAE")//
                .str("STUDYID", "S1", "S1")//
                .str("USUBJID", "U1", "U1")//
                .str("RDOMAIN", "AE", "AE")//
                .str("IDVAR", "AESEQ", "AESPID")//
                .str("IDVARVAL", "1", "P-1")//
                .build();
        IDataTable ae = TableFixture.of("AE")//
                .str("STUDYID", "S1", "S1")//
                .str("USUBJID", "U1", "U1")//
                .str("AESEQ", "1", "2")//
                .str("AESPID", "P-1", "P-2")//
                .str("AETERM", "by-seq", "by-spid")//
                .build();

        IDataTable result = ChildMatchPreMerger.preMerge(primary, List.of(md("AE", true)),
                resolver("AE", ae), "CORE-E20", null);

        int aetermIdx = result.getMetaData().getColumnIndex("AETERM");
        assertEquals("by-seq", result.getColumn(aetermIdx).getDataValue(0).getValueAsString());
        // AESPID="P-1" is on parent row 0, so the AETERM for it is also "by-seq".
        assertEquals("by-seq", result.getColumn(aetermIdx).getDataValue(1).getValueAsString());
    }

    // ----------------------------------------------------------------------------------------
    // JoinCache reuse — second preMerge call observes a cache hit for the same (parent, IDVAR).
    // ----------------------------------------------------------------------------------------


    @Test
    void preMerge_joinCacheReuse_singleEntryPerParentIdvar() throws Exception
    {
        IDataTable primary = TableFixture.of("SUPPAE")//
                .str("STUDYID", "S1")//
                .str("USUBJID", "U1")//
                .str("IDVAR", "AESEQ")//
                .str("IDVARVAL", "1")//
                .str("RDOMAIN", "AE")//
                .build();
        IDataTable parent = TableFixture.of("AE")//
                .str("STUDYID", "S1")//
                .str("USUBJID", "U1")//
                .str("AESEQ", "1")//
                .str("AETERM", "headache")//
                .build();

        JoinCache.SharedIndexCache shared = new JoinCache.SharedIndexCache();
        JoinCache cache = new JoinCache(shared);

        ChildMatchPreMerger.preMerge(primary, List.of(md("AE", true)), resolver("AE", parent),
                "rule-1", cache);
        int sizeAfter1 = childMatchCacheSize(shared);

        ChildMatchPreMerger.preMerge(primary, List.of(md("AE", true)), resolver("AE", parent),
                "rule-2", cache);
        int sizeAfter2 = childMatchCacheSize(shared);

        assertEquals(1, sizeAfter1, "first preMerge must add exactly one (parent, IDVAR) entry");
        assertEquals(1, sizeAfter2,
                "second preMerge with same primary/parent/IDVAR must NOT rebuild the index");
    }

    // ----------------------------------------------------------------------------------------
    // Helpers
    // ----------------------------------------------------------------------------------------


    private static MatchDataset md(String name, Boolean child)
    {
        MatchDataset m = new MatchDataset();
        m.setName(name);
        m.setChild(child);
        return m;
    }


    private static MatchDataset md(String name, Boolean child, String... keys)
    {
        MatchDataset m = new MatchDataset();
        m.setName(name);
        m.setChild(child);
        m.setKeys(List.of(keys));
        return m;
    }


    private static DatasetResolver resolver(String name, IDataTable table)
    {
        return n -> name.equals(n) ? table : null;
    }


    private static DatasetResolver inventoryResolver(Map<String, IDataTable> byName)
    {
        // A WithInventory resolver whose resolve() only knows the split member names (lbch/lbhe),
        // so resolve("LB") returns null and the firstSplitMember fallback (via tablesForDomain)
        // must kick in. cdiscDomainOf reads each member's DOMAIN cell (= "LB").
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                return byName.get(name);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return byName.keySet();
            }
        };
    }


    private static DatasetResolver multi(Map<String, IDataTable> tables)
    {
        return tables::get;
    }


    @SuppressWarnings("unchecked")
    private static int childMatchCacheSize(JoinCache.SharedIndexCache shared) throws Exception
    {
        Field f = JoinCache.SharedIndexCache.class.getDeclaredField("childMatchCache");
        f.setAccessible(true);
        ConcurrentHashMap<String, ?> map = (ConcurrentHashMap<String, ?>) f.get(shared);
        return map.size();
    }

    // ----------------------------------------------------------------------------------------
    // Fixture builder — real DataTableMeta + CachedDataTableColumn.
    // ----------------------------------------------------------------------------------------

    private static final class TableFixture
    {

        private final String name;

        private final List<String> colNames = new ArrayList<>();

        private final List<DataValueType> colTypes = new ArrayList<>();

        private final List<Object[]> colData = new ArrayList<>();

        private TableFixture(String aName)
        {
            name = aName;
        }


        static TableFixture of(String aName)
        {
            return new TableFixture(aName);
        }


        TableFixture str(String aName, String... aValues)
        {
            colNames.add(aName);
            colTypes.add(DataValueType.STRING);
            colData.add(aValues);
            return this;
        }


        TableFixture lng(String aName, Long... aValues)
        {
            colNames.add(aName);
            colTypes.add(DataValueType.LONG);
            colData.add(aValues);
            return this;
        }


        IDataTable build()
        {
            int colCount = colNames.size();
            int rowCount = colData.isEmpty() ? 0 : colData.get(0).length;
            CachedDataTableColumn[] cols = new CachedDataTableColumn[colCount];
            DataTableColumnMeta[] metas = new DataTableColumnMeta[colCount];
            for (int c = 0; c < colCount; c++)
            {
                cols[c] = new CachedDataTableColumn(c, colTypes.get(c));
                metas[c] = DataTableColumnMeta.builder().index(c).name(colNames.get(c))
                        .label(colNames.get(c)).type(colTypes.get(c)).build();
                Object[] data = colData.get(c);
                for (int r = 0; r < rowCount; r++)
                {
                    cols[c].addElement(data[r]);
                }
                cols[c].complete();
            }
            DataTableMeta meta = DataTableMeta.builder().name(name).label(name).columns(metas)
                    .rowCount(rowCount).totalRowCount(rowCount).build();
            return new ColumnCachedDataTable(meta, cols);
        }
    }

    // ----------------------------------------------------------------------------------------
    // Delegating IDataTable that reports an oversize row count to exercise the E18 guard.
    // Only abstract methods need delegation; the default methods on IDataTable suffice.
    // ----------------------------------------------------------------------------------------


    private static final class OverflowingTable implements IDataTable
    {

        private final IDataTable delegate;

        OverflowingTable(IDataTable aDelegate)
        {
            delegate = aDelegate;
        }


        @Override
        public long getRowCount()
        {
            return (long) Integer.MAX_VALUE + 1L;
        }


        @Override
        public DataTableMeta getMetaData()
        {
            return delegate.getMetaData();
        }


        @Override
        public Object getValue(long aRow, int aColumn)
        {
            return delegate.getValue(aRow, aColumn);
        }


        @Override
        public IDataTableColumn getColumn(int aColumn)
        {
            return delegate.getColumn(aColumn);
        }
    }

}
