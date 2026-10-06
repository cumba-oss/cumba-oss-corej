package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.merge.ChildMergeSpec;
import net.cumba.datatable.merge.JoinType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * W4 of {@code PLAN-merged-evaluation-table} (§3.7, MQ1, MET #2): a {@code Child: true} parent is a
 * part of the merged evaluation view, its columns qualified with the parent domain
 * ({@code AE.AESMIE}) and supplied on the rows whose parent is that domain — never merged bare into
 * the evaluated table, as the retired {@code ChildMatchPreMerger} did. Each behaviour the pre-merge
 * had is shown here on the view, with the respelled rule shapes ({@code CDISC-CG0043},
 * {@code CDISC-CG0371}); where the view differs on purpose (P-13: every parent record a grouping
 * pointer addresses, not the first) the test says so.
 */
class ChildMergedViewTest
{

    @AfterEach
    void hooksOff()
    {
        MergedEvaluation.setLegacyForTests(false);
    }

    /** CG0371's respelled Check (MQ1). */
    private static final String REFERENCED_RECORD = "not empty(IDVAR) and not empty(IDVARVAL) and"
            + " str(IDVARVAL) != str(colref(concat(RDOMAIN, \\\".\\\", IDVAR)))";

    /** CG0043's respelled Check (MQ1). */
    private static final String AESOSP = "QNAM == \\\"AESOSP\\\" and var_exists(\\\"AE.AESMIE\\\")"
            + " and AE.AESMIE != \\\"Y\\\"";

    private static Rule rule(String aName, String aKeys, String aCheck, @Nullable String aExtra)
    {
        String json = "{\"rules\":{\"X-1\":{\"Core\":{\"Id\":\"X-1\"},"
                + "\"Requirements\":{\"Variables\":{\"All\":[" + aKeys + "]}},"
                + "\"Match_Datasets\":[{\"Name\":\"" + aName + "\",\"Child\":true,\"Keys\":["
                + aKeys + "]" + (aExtra == null ? "" : aExtra) + "}],"
                + "\"Check\":{\"expression\":\"" + aCheck + "\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}}}";
        try
        {
            Rule rule = RulePackageLoader.loadFromString(json).getRules().get("X-1");
            assertNull(rule.getLoadError(), "fixture must load: " + rule.getLoadError());
            return rule;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(json, e);
        }
    }

    private static final String KEYS = "\"USUBJID\",\"IDVAR\",\"IDVARVAL\"";

    private static DatasetResolver resolver(IDataTable... tables)
    {
        return name ->
        {
            for (IDataTable t : tables)
            {
                if (t.getMetaData().getName().equalsIgnoreCase(name))
                {
                    return t;
                }
            }
            return null;
        };
    }


    private static IDataTable ae()
    {
        return RealTableFixture.of("AE").str("STUDYID", "S1", "S1", "S1")
                .str("DOMAIN", "AE", "AE", "AE").str("USUBJID", "01", "01", "02")
                .lng("AESEQ", 1L, 2L, 1L).str("AESMIE", "Y", "N", "N").build();
    }


    private static IDataTable cm()
    {
        return RealTableFixture.of("CM").str("STUDYID", "S1").str("DOMAIN", "CM")
                .str("USUBJID", "01").lng("CMSEQ", 4L).str("CMTRT", "ASPIRIN").build();
    }


    /** SUPPAE rows as USUBJID|IDVAR|IDVARVAL|QNAM. */
    private static IDataTable suppae(String... rows)
    {
        int n = rows.length;
        String[][] c = new String[4][n];
        for (int i = 0; i < n; i++)
        {
            String[] f = rows[i].split("\\|", -1);
            for (int k = 0; k < 4; k++)
            {
                c[k][i] = f[k];
            }
        }
        String[] studyid = new String[n];
        String[] rdomain = new String[n];
        java.util.Arrays.fill(studyid, "S1");
        java.util.Arrays.fill(rdomain, "AE");
        return RealTableFixture.of("SUPPAE").str("STUDYID", studyid).str("RDOMAIN", rdomain)
                .str("USUBJID", c[0]).str("IDVAR", c[1]).str("IDVARVAL", c[2]).str("QNAM", c[3])
                .build();
    }


    private static Set<Long> rows(RuleExecutionResult r)
    {
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), String.valueOf(r));
        Set<Long> out = new TreeSet<>();
        // a finding's row number is 1-based
        r.getViolations().forEach(v -> out.add(v.getRowNumber() - 1));
        return out;
    }

    // ------------------------------------------------------------------ the parent part


    @Test
    void theParentIsAQualifiedPartPerRowDomainNeverBareColumns()
    {
        // CO points at AE on row 0 and at CM on row 1: one part per parent domain (MQ1), each
        // supplied only on its own rows.
        IDataTable co = RealTableFixture.of("CO").str("STUDYID", "S1", "S1")
                .str("RDOMAIN", "AE", "CM").str("USUBJID", "01", "01")
                .str("IDVAR", "AESEQ", "CMSEQ").str("IDVARVAL", "2", "4").str("COVAL", "a", "b")
                .build();
        DatasetResolver r = resolver(co, ae(), cm());
        MergedEvaluation.Built built = MergedEvaluation
                .build(rule("CO", KEYS, REFERENCED_RECORD, null), co, co, r, null, "X-1", "CO");
        assertNotNull(built);
        Map<String, JoinLookup> parts = built.lookups();
        assertEquals(Set.of("AE", "CM"), parts.keySet());
        IDataTable t = built.table();
        assertEquals("N", parts.get("AE").lookup(t, 0, "AESMIE"));
        assertNull(parts.get("AE").lookup(t, 1, "AESMIE"), "row 1's parent is CM");
        assertEquals("ASPIRIN", parts.get("CM").lookup(t, 1, "CMTRT"));
        assertNull(parts.get("CM").lookup(t, 0, "CMTRT"));
        assertTrue(parts.get("AE").matchedRow(t, 0));
        assertFalse(parts.get("AE").matchedRow(t, 1));
        assertTrue(t.getMetaData().getColumnIndex("AESMIE") < 0,
                "the parent's columns are never merged into the evaluated table bare");
        assertTrue(built.view().getMetaData().getColumnIndex("AE.AESMIE") >= 0);
    }


    @Test
    void theReferencedRecordRuleFiresOnlyForAPointerToNoRecord()
    {
        // CDISC-CG0371 respelled: row 1 points at AESEQ 99 (no record), row 2 at subject 02's
        // AESEQ 1 under USUBJID 01 — no record for that subject; rows 0 and 3 exist.
        IDataTable supp = suppae("01|AESEQ|1|X", "01|AESEQ|99|X", "02|AESEQ|2|X", "02|AESEQ|1|X");
        RuleExecutionResult r = RuleRunnerCalls
                .execute(rule("SUPP--", KEYS, REFERENCED_RECORD, null), supp, resolver(supp, ae()));
        assertEquals(Set.of(1L, 2L), rows(r));
    }


    @Test
    void everyRecordIsKeptAndAnUnmatchedOneReadsTheNotSuppliedMarker()
    {
        // CDISC-CG0043 / FDA-SD1143 respelled, the "no_parent" case: the record survives (left)
        // and AE.AESMIE reads "" there, so the rule fires — what the bare pre-merge did.
        IDataTable supp = suppae("01|AESEQ|1|AESOSP", "01|AESEQ|2|AESOSP", "01|AESEQ|7|AESOSP");
        RuleExecutionResult r = RuleRunnerCalls.execute(rule("AE", KEYS, AESOSP, null), supp,
                resolver(supp, ae()));
        assertEquals(Set.of(1L, 2L), rows(r), "row 0 is AESMIE Y; row 1 N; row 2 has no parent");
        assertEquals(3, r.getTotalRows(), "no row dropped");
        assertEquals(JoinType.LEFT, java.util.Objects
                .requireNonNull(
                        ChildEntries.spec(rule("AE", KEYS, AESOSP, null).getMatchDatasets(), supp))
                .getJoinType());
    }


    @Test
    void theSubjectKeyIsTheDeclaredKeysMinusThePointer()
    {
        // STUDYID is not declared: a pooled parent under another study still matches; declared, it
        // is honoured (the pre-merge's rule, from the authored Keys).
        IDataTable supp = RealTableFixture.of("SUPPAE").str("STUDYID", "S2").str("RDOMAIN", "AE")
                .str("USUBJID", "01").str("IDVAR", "AESEQ").str("IDVARVAL", "1").str("QNAM", "X")
                .build();
        DatasetResolver r = resolver(supp, ae());
        assertEquals(Set.of(), rows(
                RuleRunnerCalls.execute(rule("SUPP--", KEYS, REFERENCED_RECORD, null), supp, r)));
        assertEquals(Set.of(0L), rows(RuleRunnerCalls
                .execute(rule("SUPP--", "\"STUDYID\"," + KEYS, REFERENCED_RECORD, null), supp, r)));
        ChildMergeSpec spec = ChildEntries
                .spec(rule("SUPP--", KEYS, REFERENCED_RECORD, null).getMatchDatasets(), supp);
        assertEquals(List.of("USUBJID"), java.util.Objects.requireNonNull(spec).getSubjectKeys());
    }


    @Test
    void thePointerTokenIsTheIdvarvalTheRuleCompares()
    {
        // J5: " 1" (SAS padding) and "1.0" name the numeric AESEQ 1; the rule compares IDVARVAL as
        // text against the parent cell, so the evaluated IDVARVAL is the join token. "01" against a
        // STRING parent column stays "01" (strip only), and finds no "1".
        IDataTable supp = suppae("01|AESEQ| 1|X", "01|AESEQ|1.0|X", "01|AESEQ|2|X");
        DatasetResolver r = resolver(supp, ae());
        MergedEvaluation.Built built = MergedEvaluation.build(
                rule("SUPP--", KEYS, REFERENCED_RECORD, null), supp, supp, r, null, "X-1", "AE");
        IDataTable t = java.util.Objects.requireNonNull(built).table();
        int idvarval = t.getMetaData().getColumnIndex("IDVARVAL");
        assertEquals("1", t.getDataValue(0, idvarval).getValueAsString());
        assertEquals("1", t.getDataValue(1, idvarval).getValueAsString());
        assertEquals("2", t.getDataValue(2, idvarval).getValueAsString());
        assertEquals(Set.of(), rows(
                RuleRunnerCalls.execute(rule("SUPP--", KEYS, REFERENCED_RECORD, null), supp, r)));
        // conformant tokens: the primary itself, no copy
        IDataTable clean = suppae("01|AESEQ|1|X");
        assertSame(clean, ChildEntries.withPointerTokens(clean,
                java.util.Objects.requireNonNull(ChildEntries.spec(
                        rule("SUPP--", KEYS, REFERENCED_RECORD, null).getMatchDatasets(), clean)),
                r::resolve));
        // a string parent column: strip only
        IDataTable parent = RealTableFixture.of("XX").str("USUBJID", "01").str("XXREF", "1")
                .build();
        IDataTable pointer = RealTableFixture.of("CO").str("RDOMAIN", "XX").str("USUBJID", "01")
                .str("IDVAR", "XXREF").str("IDVARVAL", "01").build();
        assertEquals(Set.of(0L),
                rows(RuleRunnerCalls.execute(rule("CO", KEYS, REFERENCED_RECORD, null), pointer,
                        resolver(pointer, parent))));
    }


    @Test
    void aSuppPrimaryWithoutRdomainTakesTheParentItsNameImplies()
    {
        for (String name : List.of("SUPPAE", "SQAPAE"))
        {
            IDataTable supp = RealTableFixture.of(name).str("USUBJID", "01").str("IDVAR", "AESEQ")
                    .str("IDVARVAL", "2").str("QNAM", "AESOSP").build();
            MergedEvaluation.Built built = MergedEvaluation.build(
                    rule("SUPP--", KEYS, AESOSP, null), supp, supp, resolver(supp, ae()), null,
                    "X-1", "AE");
            IDataTable t = java.util.Objects.requireNonNull(built).table();
            assertEquals("N", built.lookups().get("AE").lookup(t, 0, "AESMIE"), name);
        }
    }


    @Test
    void aSplitParentIsReadThroughItsUnion()
    {
        // SUPPLBHE points at LB, split into LBCH / LBHE: the union serves every member's record.
        IDataTable lbch = RealTableFixture.of("LBCH").str("DOMAIN", "LB").str("USUBJID", "01")
                .lng("LBSEQ", 1L).build();
        IDataTable lbhe = RealTableFixture.of("LBHE").str("DOMAIN", "LB").str("USUBJID", "01")
                .lng("LBSEQ", 2L).build();
        IDataTable supp = RealTableFixture.of("SUPPLBHE").str("RDOMAIN", "LB", "LB", "LB")
                .str("USUBJID", "01", "01", "01").str("IDVAR", "LBSEQ", "LBSEQ", "LBSEQ")
                .str("IDVARVAL", "1", "2", "3").str("QNAM", "X", "X", "X").build();
        Map<String, IDataTable> byName = Map.of("LBCH", lbch, "LBHE", lbhe, "SUPPLBHE", supp);
        DatasetResolver r = new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                return byName.get(name.toUpperCase(java.util.Locale.ROOT));
            }


            @Override
            public Set<String> availableDatasets()
            {
                return byName.keySet();
            }
        };
        assertEquals(Set.of(2L), rows(
                RuleRunnerCalls.execute(rule("SUPP--", KEYS, REFERENCED_RECORD, null), supp, r)));
    }


    @Test
    void aGroupingPointerAddressesEveryParentRecordNotTheFirst()
    {
        // P-13 (IG n:m): IDVAR may be a grouping variable; the pre-merge read the FIRST parent
        // record, the view reads every one (one merged row each, one finding per distinct
        // finding, MET #22). Here AEGRPID G1 addresses AESEQ 1 (AESMIE Y) and 2 (N): the AESOSP
        // rule fires for the N record — the first-wins pre-merge saw only Y and stayed silent.
        IDataTable ae = RealTableFixture.of("AE").str("DOMAIN", "AE", "AE")
                .str("USUBJID", "01", "01").lng("AESEQ", 1L, 2L).str("AEGRPID", "G1", "G1")
                .str("AESMIE", "Y", "N").build();
        IDataTable supp = suppae("01|AEGRPID|G1|AESOSP");
        RuleExecutionResult r = RuleRunnerCalls.execute(rule("AE", KEYS, AESOSP, null), supp,
                resolver(supp, ae));
        assertEquals(Set.of(0L), rows(r));
        assertEquals(1, r.getViolations().size());
        // an authored Cardinality n:1 refuses that data (P-16), with the IG-disagreement WARNING
        RuleExecutionResult refused = RuleRunnerCalls.execute(
                rule("AE", KEYS, AESOSP, ",\"Cardinality\":\"n:1\""), supp, resolver(supp, ae));
        assertEquals(RuleExecutionStatus.ERROR, refused.getStatus(), String.valueOf(refused));
        assertTrue(String.valueOf(refused.getStatusMessage())
                .startsWith(MergedEvaluation.MERGE_REFUSED_MESSAGE_PREFIX));
    }


    @Test
    void aPointerToAColumnTheParentLacksErrorsNoRuleAndReadsNotSupplied()
    {
        // The datatable flags the AE part (MERGE_ERROR: pointer column AESPID is not a column of
        // AE); the engine raises no rule ERROR for a Child flag — the record reads the not-supplied
        // marker, as the pre-merge left it, and the referenced-record rule reports it (OD-1's
        // precedent). The other row is served as usual.
        IDataTable supp = suppae("01|AESEQ|1|AESOSP", "01|AESPID|1|AESOSP");
        DatasetResolver r = resolver(supp, ae());
        RuleExecutionResult refRule = RuleRunnerCalls
                .execute(rule("SUPP--", KEYS, REFERENCED_RECORD, null), supp, r);
        assertEquals(Set.of(1L), rows(refRule));
        RuleExecutionResult aesosp = RuleRunnerCalls.execute(rule("AE", KEYS, AESOSP, null), supp,
                r);
        assertEquals(Set.of(1L), rows(aesosp), "row 0 is AESMIE Y; row 1 reads \"\"");
        MergedEvaluation.Built built = MergedEvaluation.build(rule("AE", KEYS, AESOSP, null), supp,
                supp, r, null, "X-1", "AE");
        assertEquals(Map.of(), java.util.Objects.requireNonNull(built).flagged());
    }


    @Test
    void aChildRuleReadsItsParentThroughTheViewOnBothArmsAndSharesTheCachedView()
    {
        IDataTable supp = suppae("01|AESEQ|2|AESOSP");
        DatasetResolver r = resolver(supp, ae());
        Rule rule = rule("AE", KEYS, AESOSP, null);
        MergedEvaluation.setLegacyForTests(true);
        assertNotNull(MergedEvaluation.build(rule, supp, supp, r, null, "X-1", "AE"),
                "the bare pre-merge is gone: the legacy arm reads a parent through the view too");
        assertEquals(Set.of(0L), rows(RuleRunnerCalls.execute(rule, supp, r)));
        MergedEvaluation.setLegacyForTests(false);
        JoinCache cache = new JoinCache();
        MergedEvaluation.Built first = MergedEvaluation.build(rule, supp, supp, r, cache, "X-1",
                "AE");
        MergedEvaluation.Built second = MergedEvaluation.build(
                rule("AE", KEYS, "QNAM == \\\"AESOSP\\\" and AE.AESMIE == \\\"N\\\"", null), supp,
                supp, r, cache, "X-2", "AE");
        assertSame(first, second, "one view per (dataset, merge signature), P-3");
        assertTrue(java.util.Objects.requireNonNull(first).isShared());
    }


    @Test
    void noPointerNoPart()
    {
        // The pre-merge's early exits: no IDVAR / IDVARVAL column, keys naming neither, no
        // RDOMAIN and no SUPP name to imply a parent — no Child part.
        IDataTable noPointer = RealTableFixture.of("SUPPAE").str("RDOMAIN", "AE")
                .str("USUBJID", "01").str("QNAM", "X").build();
        Rule rule = rule("AE", KEYS, AESOSP, null);
        assertNull(ChildEntries.spec(rule.getMatchDatasets(), noPointer));
        IDataTable co = RealTableFixture.of("CO").str("USUBJID", "01").str("IDVAR", "AESEQ")
                .str("IDVARVAL", "1").build();
        assertNull(ChildEntries.spec(rule.getMatchDatasets(), co), "no RDOMAIN, no SUPP name");
        assertNull(ChildEntries.spec(
                rule("AE", "\"USUBJID\"", "QNAM == \\\"X\\\"", null).getMatchDatasets(),
                suppae("01|AESEQ|1|X")), "keys naming neither pointer");
    }
}
