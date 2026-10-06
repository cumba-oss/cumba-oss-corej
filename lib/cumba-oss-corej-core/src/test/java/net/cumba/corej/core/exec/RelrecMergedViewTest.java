package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * W5 of {@code PLAN-merged-evaluation-table} (§3.6, MET #17, MET #21, OD-3, OD-4, OD-5, OD-9, P-14,
 * P-17, MQ11): a forward RELREC entry ({@code Name: RELREC}, no {@code Keys}) is served by the
 * merged evaluation view — every related domain a part qualified with its domain, the rows the
 * cross product of the related records per relationship — never by the retired
 * {@code RelrecRowExpander}. Each table of §3.6.2 / §3.6.3 is one test here, with the engine-side
 * behaviours the plan names: the join type, the RELTYPE cardinality default and its authored
 * override, SEND's cross-subject relationships, a declared qualifier no relationship serves, the
 * composition with a keyed expansion, OD-7's flagged domain, and the respelled {@code CDISC-CG0174}
 * shape.
 */
class RelrecMergedViewTest
{

    /** {@code aExtraRequirements}: further {@code Requirements} keys (JSON members) or null. */
    private static Rule rule(String aEntry, String aCheck, String aRequired,
            @Nullable String aExtraRequirements)
    {
        String json = "{\"rules\":{\"X-1\":{\"Core\":{\"Id\":\"X-1\"},\"Sensitivity\":\"Record\","
                + "\"Requirements\":{\"Variables\":{\"All\":[" + aRequired + "]}"
                + (aExtraRequirements == null ? "" : "," + aExtraRequirements) + "},"
                + "\"Match_Datasets\":[" + aEntry + "]," + "\"Check\":{\"expression\":\"" + aCheck
                + "\"}," + "\"Outcome\":{\"Message\":\"m\"}}}}";
        try
        {
            Rule rule = RulePackageLoader
                    .loadFromString(net.cumba.corej.core.KeyedJoinFixtures.declared(json))
                    .getRules().get("X-1");
            assertNotNull(rule);
            assertNull(rule.getLoadError(), "fixture must load: " + rule.getLoadError());
            return rule;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException(json, e);
        }
    }

    private static final String RELREC_ENTRY = "{\"Name\":\"RELREC\"}";

    /** An inventory-capable resolver, so a keyed entry's key requirements can be decided. */
    private static DatasetResolver resolver(IDataTable... tables)
    {
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String name)
            {
                for (IDataTable t : tables)
                {
                    if (t.getMetaData().getName().equalsIgnoreCase(name))
                    {
                        return t;
                    }
                }
                return null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                Set<String> out = new java.util.LinkedHashSet<>();
                for (IDataTable t : tables)
                {
                    out.add(t.getMetaData().getName());
                }
                return out;
            }
        };
    }


    /** AE 01: seq 1 and seq 2. */
    private static IDataTable ae()
    {
        return RealTableFixture.of("AE").str("STUDYID", "S1", "S1").str("DOMAIN", "AE", "AE")
                .str("USUBJID", "01", "01").lng("AESEQ", 1L, 2L).str("AETERM", "HEADACHE", "RASH")
                .build();
    }


    /** CM 01: seq 1 ASPIRIN, seq 2 IBUPROFEN, seq 3 OMEPRAZOLE. */
    private static IDataTable cm()
    {
        return RealTableFixture.of("CM").str("STUDYID", "S1", "S1", "S1")
                .str("DOMAIN", "CM", "CM", "CM").str("USUBJID", "01", "01", "01")
                .lng("CMSEQ", 1L, 2L, 3L).str("CMTRT", "ASPIRIN", "IBUPROFEN", "OMEPRAZOLE")
                .build();
    }


    /** LB 01: seq 1 ALT, 2 AST, 3 BILI. */
    private static IDataTable lb()
    {
        return RealTableFixture.of("LB").str("STUDYID", "S1", "S1", "S1")
                .str("DOMAIN", "LB", "LB", "LB").str("USUBJID", "01", "01", "01")
                .lng("LBSEQ", 1L, 2L, 3L).str("LBTESTCD", "ALT", "AST", "BILI").build();
    }


    /** RELREC rows as RDOMAIN|IDVAR|IDVARVAL|RELID, subject 01, study S1. */
    private static IDataTable relrec(String... rows)
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
        String[] usubjid = new String[n];
        java.util.Arrays.fill(studyid, "S1");
        java.util.Arrays.fill(usubjid, "01");
        return RealTableFixture.of("RELREC").str("STUDYID", studyid).str("RDOMAIN", c[0])
                .str("USUBJID", usubjid).str("IDVAR", c[1]).str("IDVARVAL", c[2]).str("RELID", c[3])
                .build();
    }


    private static MergedEvaluation.Built view(Rule rule, IDataTable primary, DatasetResolver r)
    {
        MergedEvaluation.Built built = MergedEvaluation.build(rule, primary, primary, r, null,
                "X-1", primary.getMetaData().getName());
        assertNotNull(built, "a forward RELREC rule is served by the view");
        return built;
    }


    private static List<String> column(MergedEvaluation.Built built, String qualifier,
            String column)
    {
        JoinLookup lookup = built.lookups().get(qualifier);
        assertNotNull(lookup, qualifier + " is a part: " + built.lookups().keySet());
        List<String> out = new ArrayList<>();
        IDataTable t = built.table();
        for (long r = 0; r < t.getRowCount(); r++)
        {
            out.add(lookup.lookup(t, r, column));
        }
        return out;
    }


    private static Set<Long> rows(RuleExecutionResult r)
    {
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), String.valueOf(r));
        Set<Long> out = new TreeSet<>();
        r.getViolations().forEach(v -> out.add(v.getRowNumber() - 1));
        return out;
    }

    // ------------------------------------------------------------------ §3.6.2 / §3.6.3


    @Test
    void example1_oneCmAndThreeLb_threeRowsOfTheOneRelationship()
    {
        // §3.6.2 Example 1: AE seq 1 related (R1) to CM seq 1 and LB seq 1, 2, 3 ⇒ 3 rows.
        IDataTable rr = relrec("AE|AESEQ|1|R1", "CM|CMSEQ|1|R1", "LB|LBSEQ|1|R1", "LB|LBSEQ|2|R1",
                "LB|LBSEQ|3|R1");
        MergedEvaluation.Built built = view(
                rule(RELREC_ENTRY, "not empty(AETERM)", "\"AETERM\"", null), ae(),
                resolver(ae(), cm(), lb(), rr));
        assertEquals(3, built.table().getRowCount(), "AE seq 2 has no relationship: inner");
        assertEquals(List.of("ASPIRIN", "ASPIRIN", "ASPIRIN"), column(built, "CM", "CMTRT"));
        assertEquals(List.of("ALT", "AST", "BILI"), column(built, "LB", "LBTESTCD"));
        for (long r = 0; r < 3; r++)
        {
            assertEquals(0L, built.table().getRealRowIndex(r), "every row is the real AE row 0");
        }
    }


    @Test
    void example2_twoCmAndThreeLb_sixRowsEveryCombination()
    {
        // §3.6.2 Example 2: CM seq 1, 2 × LB seq 1, 2, 3 ⇒ 6 rows.
        IDataTable rr = relrec("AE|AESEQ|1|R1", "CM|CMSEQ|1|R1", "CM|CMSEQ|2|R1", "LB|LBSEQ|1|R1",
                "LB|LBSEQ|2|R1", "LB|LBSEQ|3|R1");
        MergedEvaluation.Built built = view(
                rule(RELREC_ENTRY, "not empty(AETERM)", "\"AETERM\"", null), ae(),
                resolver(ae(), cm(), lb(), rr));
        assertEquals(6, built.table().getRowCount());
        Set<String> combinations = new TreeSet<>();
        List<String> cmTrt = column(built, "CM", "CMTRT");
        List<String> lbTest = column(built, "LB", "LBTESTCD");
        for (int i = 0; i < 6; i++)
        {
            combinations.add(cmTrt.get(i) + "/" + lbTest.get(i));
        }
        assertEquals(Set.of("ASPIRIN/ALT", "ASPIRIN/AST", "ASPIRIN/BILI", "IBUPROFEN/ALT",
                "IBUPROFEN/AST", "IBUPROFEN/BILI"), combinations);
    }


    @Test
    void perRelationship_od4_relationshipsAreSummedNeverCrossed()
    {
        // §3.6.3: R1 = {CM seq 1, LB seq 1}; R2 = {CM seq 3} ⇒ 2 rows, never OMEPRAZOLE beside
        // ALT (the zip artefact across RELIDs).
        IDataTable rr = relrec("AE|AESEQ|1|R1", "CM|CMSEQ|1|R1", "LB|LBSEQ|1|R1", "AE|AESEQ|1|R2",
                "CM|CMSEQ|3|R2");
        MergedEvaluation.Built built = view(
                rule(RELREC_ENTRY, "not empty(AETERM)", "\"AETERM\"", null), ae(),
                resolver(ae(), cm(), lb(), rr));
        assertEquals(2, built.table().getRowCount());
        assertEquals(List.of("ASPIRIN", "OMEPRAZOLE"), column(built, "CM", "CMTRT"));
        List<String> lbTest = column(built, "LB", "LBTESTCD");
        assertEquals("ALT", lbTest.get(0));
        assertNull(lbTest.get(1), "R2 names no LB: the LB part is not supplied on that row");
        assertTrue(built.lookups().get("LB").matchedRow(built.table(), 0));
        assertFalse(built.lookups().get("LB").matchedRow(built.table(), 1),
                "LB._matched_ is \"this row carries an LB record\" (P-14)");
    }

    // ------------------------------------------------------------------ MET #21 join type


    @Test
    void joinType_innerDropsARecordWithNoRelationship_leftKeepsItWithFlagsFalse()
    {
        IDataTable rr = relrec("AE|AESEQ|1|R1", "CM|CMSEQ|1|R1");
        DatasetResolver r = resolver(ae(), cm(), rr);
        Rule inner = rule(RELREC_ENTRY, "not empty(AETERM)", "\"AETERM\"", null);
        MergedEvaluation.Built built = view(inner, ae(), r);
        assertEquals(1, built.table().getRowCount(), "inner: AE seq 2 is not evaluated (S2)");
        assertEquals(1, RuleRunnerCalls.execute(inner, ae(), r).getViolations().size());

        Rule left = rule("{\"Name\":\"RELREC\",\"Join_Type\":\"left\"}", "not RELREC._matched_",
                "\"AETERM\"", null);
        MergedEvaluation.Built leftView = view(left, ae(), r);
        assertEquals(2, leftView.table().getRowCount());
        JoinLookup any = leftView.lookups().get("RELREC");
        assertNotNull(any, "RELREC._matched_ = the record has any relationship (P-14)");
        assertTrue(any.matchedRow(leftView.table(), 0));
        assertFalse(any.matchedRow(leftView.table(), 1));
        assertFalse(leftView.lookups().get("CM").matchedRow(leftView.table(), 1));
        assertNull(leftView.lookups().get("CM").lookup(leftView.table(), 1, "CMTRT"));
        assertEquals(Set.of(1L), rows(RuleRunnerCalls.execute(left, ae(), r)),
                "the record with no relationship is the finding of `not RELREC._matched_`");
    }

    // ------------------------------------------------------------------ OD-3 SEND


    @Test
    void send_od3_aRelationshipSpansSubjectsAndPools()
    {
        // SENDIG 3.1.1 §8.2 Example 3: RELID is unique within the study; a row names its record
        // by USUBJID or POOLID. CL A1 relates (R1) to LB of pools P1 and P2; CL of pool P1 relates
        // (R2) to LB of A1.
        IDataTable cl = RealTableFixture.of("CL").str("STUDYID", "S1", "S1")
                .str("DOMAIN", "CL", "CL").str("USUBJID", "A1", "").str("POOLID", "", "P1")
                .lng("CLSEQ", 1L, 1L).str("CLTEST", "OBS", "POOLOBS").build();
        IDataTable lb = RealTableFixture.of("LB").str("STUDYID", "S1", "S1", "S1")
                .str("DOMAIN", "LB", "LB", "LB").str("USUBJID", "A1", "", "")
                .str("POOLID", "", "P1", "P2").lng("LBSEQ", 1L, 1L, 1L)
                .str("LBTESTCD", "ALT", "AST", "BILI").build();
        IDataTable rr = RealTableFixture.of("RELREC").str("STUDYID", "S1", "S1", "S1", "S1", "S1")
                .str("RDOMAIN", "CL", "LB", "LB", "CL", "LB").str("USUBJID", "A1", "", "", "", "A1")
                .str("POOLID", "", "P1", "P2", "P1", "")
                .str("IDVAR", "CLSEQ", "LBSEQ", "LBSEQ", "CLSEQ", "LBSEQ")
                .str("IDVARVAL", "1", "1", "1", "1", "1").str("RELID", "R1", "R1", "R1", "R2", "R2")
                .build();
        Rule rule = rule(RELREC_ENTRY, "LB._matched_ and CLTEST != LB.LBTESTCD", "\"CLTEST\"",
                null);
        MergedEvaluation.Built built = view(rule, cl, resolver(cl, lb, rr));
        assertEquals(3, built.table().getRowCount(), "A1 × {P1, P2} + P1 × {A1}");
        assertEquals(List.of("AST", "BILI", "ALT"), column(built, "LB", "LBTESTCD"));
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, cl, resolver(cl, lb, rr));
        assertEquals(3, result.getViolations().size());
        assertEquals(2, result.getTotalRows(), "real rows (OD-6)");
    }

    // ------------------------------------------------------------------ OD-5 / OD-9 RELTYPE


    /** FA/AE dataset-level fixture: AE declares ONE, FA MANY; AELNKID L1 repeats in AE. */
    private static DatasetResolver reltypeViolation()
    {
        IDataTable fa = RealTableFixture.of("FA").str("STUDYID", "S1", "S1")
                .str("DOMAIN", "FA", "FA").str("USUBJID", "01", "01").lng("FASEQ", 1L, 2L)
                .str("FALNKGRP", "L1", "L1").str("FAOBJ", "ERYTHEMA", "PAIN").build();
        IDataTable aeDup = RealTableFixture.of("AE").str("STUDYID", "S1", "S1")
                .str("DOMAIN", "AE", "AE").str("USUBJID", "01", "01").lng("AESEQ", 1L, 2L)
                .str("AELNKID", "L1", "L1").str("AETERM", "ERYTHEMA", "RASH").build();
        IDataTable rr = RealTableFixture.of("RELREC").str("STUDYID", "S1", "S1")
                .str("RDOMAIN", "AE", "FA").str("USUBJID", "", "")
                .str("IDVAR", "AELNKID", "FALNKGRP").str("IDVARVAL", "", "")
                .str("RELTYPE", "ONE", "MANY").str("RELID", "AEFA", "AEFA").build();
        return resolver(fa, aeDup, rr);
    }


    @Test
    void reltype_od5_aOneSideThatRepeatsIsTheRulesError()
    {
        DatasetResolver r = reltypeViolation();
        IDataTable fa = r.resolve("FA");
        assertNotNull(fa);
        RuleExecutionResult result = RuleRunnerCalls.execute(
                rule(RELREC_ENTRY, "AE._matched_ and FAOBJ != AE.AETERM", "\"FAOBJ\"", null), fa,
                r);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(), String.valueOf(result));
        String msg = String.valueOf(result.getStatusMessage());
        assertTrue(msg.startsWith(MergedEvaluation.MERGE_REFUSED_MESSAGE_PREFIX), msg);
        assertTrue(msg.contains("Cardinality") && msg.contains("AE"), msg);
    }


    @Test
    void reltype_od9_anAuthoredCardinalityOverridesTheReltypeDefault()
    {
        // The same data under an authored n:m: served, each FA record paired with both AEs.
        DatasetResolver r = reltypeViolation();
        IDataTable fa = r.resolve("FA");
        assertNotNull(fa);
        Rule rule = rule("{\"Name\":\"RELREC\",\"Cardinality\":\"n:m\"}",
                "AE._matched_ and FAOBJ != AE.AETERM", "\"FAOBJ\"", null);
        assertEquals(4, view(rule, fa, r).table().getRowCount());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, fa, r);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), String.valueOf(result));
        // ERYTHEMA ≠ RASH (one finding for FA seq 1), PAIN ≠ both (one finding, §3.8 identity
        // keeps the two AE values apart) — the count is the findings', not the rows'
        assertEquals(Set.of(0L, 1L), rows(result));
    }

    // ------------------------------------------------------------------ P-14 / P-1


    @Test
    void aDeclaredQualifierNoRelationshipServesReadsNotSuppliedAndItsFlagIsFalse()
    {
        // The respelled CG0174 shape names Events and Interventions domains the study lacks:
        // BE._matched_ is false on every row and BE.BETERM reads the not-supplied marker — never
        // a bind error, never an exception (P-14: a declared qualifier whose dataset has no data
        // on the study ⇒ P-1).
        IDataTable fa = RealTableFixture.of("FA").str("STUDYID", "S1").str("DOMAIN", "FA")
                .str("USUBJID", "01").lng("FASEQ", 1L).str("FAOBJ", "MYSTERY").build();
        IDataTable rr = relrec("AE|AESEQ|1|R1", "FA|FASEQ|1|R1");
        Rule rule = rule(RELREC_ENTRY,
                "(AE._matched_ and FAOBJ != AE.AETERM) or (BE._matched_ and FAOBJ != BE.BETERM)",
                "\"FAOBJ\"", null);
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, fa, resolver(fa, ae(), rr));
        assertEquals(Set.of(0L), rows(result), "the AE arm fires; the BE arm is false");
        Rule onlyBe = rule(RELREC_ENTRY, "BE._matched_ or FAOBJ == BE.BETERM", "\"FAOBJ\"", null);
        assertEquals(Set.of(), rows(RuleRunnerCalls.execute(onlyBe, fa, resolver(fa, ae(), rr))));
    }


    @Test
    void relrecAbsentFromTheStudy_everyRecordEvaluatesWithNothingRelated()
    {
        // P-1: an unsupplied RELREC never empties the rule — the rule runs on every record, every
        // related column not supplied and every flag false (today's expander answered 0 rows).
        IDataTable fa = RealTableFixture.of("FA").str("STUDYID", "S1").str("DOMAIN", "FA")
                .str("USUBJID", "01").lng("FASEQ", 1L).str("FAOBJ", "MYSTERY").build();
        Rule rule = rule(RELREC_ENTRY, "AE._matched_ and FAOBJ != AE.AETERM", "\"FAOBJ\"", null);
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, fa, resolver(fa, ae()));
        assertEquals(Set.of(), rows(result));
        assertEquals(1, result.getTotalRows());
        // the entry's OWN flag over an absent RELREC is the absent-dataset flood D89b guards: an
        // armed bind error undeclared, a clean SKIP once the rule declares RELREC in Requirements
        Rule notRelated = rule("{\"Name\":\"RELREC\",\"Join_Type\":\"left\"}",
                "not RELREC._matched_", "\"FAOBJ\"", null);
        RuleExecutionResult undeclared = RuleRunnerCalls.execute(notRelated, fa,
                resolver(fa, ae()));
        assertEquals(RuleExecutionStatus.ERROR, undeclared.getStatus(), String.valueOf(undeclared));
        assertTrue(
                String.valueOf(undeclared.getStatusMessage()).contains("MATCHED_FLAG_UNRESOLVABLE"),
                String.valueOf(undeclared.getStatusMessage()));
        Rule declared = rule("{\"Name\":\"RELREC\",\"Join_Type\":\"left\"}", "not RELREC._matched_",
                "\"FAOBJ\"", "\"Datasets\":[\"RELREC\"]");
        assertEquals(RuleExecutionStatus.SKIPPED,
                RuleRunnerCalls.execute(declared, fa, resolver(fa, ae())).getStatus());
    }

    // ------------------------------------------------------------------ MQ11


    @Test
    void mq11_aForwardRelrecComposesWithAKeyedExpansionOnOneRule()
    {
        // Today's engine ERRORs ("forward RELREC join combined with a key-based ... expansion");
        // the view composes the row maps: AE seq 1 × {LB 1, 2} × DM ⇒ 2 rows, DM.AGE on each.
        IDataTable dm = RealTableFixture.of("DM").str("STUDYID", "S1").str("DOMAIN", "DM")
                .str("USUBJID", "01").lng("AGE", 42L).build();
        IDataTable rr = relrec("AE|AESEQ|1|R1", "LB|LBSEQ|1|R1", "LB|LBSEQ|2|R1");
        Rule rule = rule(RELREC_ENTRY
                + ",{\"Name\":\"DM\",\"Keys\":[\"STUDYID\",\"USUBJID\"],\"Cardinality\":\"n:1\"}",
                "DM.AGE > 40 and LB._matched_ and LB.LBTESTCD != \\\"BILI\\\"",
                "\"STUDYID\",\"USUBJID\"", null);
        DatasetResolver r = resolver(ae(), dm, lb(), rr);
        MergedEvaluation.Built built = view(rule, ae(), r);
        assertEquals(2, built.table().getRowCount());
        assertEquals(List.of("42", "42"), column(built, "DM", "AGE"));
        assertEquals(List.of("ALT", "AST"), column(built, "LB", "LBTESTCD"));
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae(), r);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), String.valueOf(result));
        assertEquals(2, result.getViolations().size(), "one per related LB value");
    }

    // ------------------------------------------------------------------ OD-7 / P-14 collision


    @Test
    void od7_aRelatedDomainLackingTheColumnRelrecNamesErrorsTheRulesReadingIt()
    {
        // RELREC names CM.CMSPID but CM carries no CMSPID: the CM part is flagged (MERGE_ERROR), a
        // rule reading CM.CMTRT ERRORs (MET #15), a rule reading only AE and LB runs.
        IDataTable rr = relrec("AE|AESEQ|1|R1", "CM|CMSPID|1|R1", "LB|LBSEQ|1|R1");
        DatasetResolver r = resolver(ae(), cm(), lb(), rr);
        RuleExecutionResult flagged = RuleRunnerCalls.execute(
                rule(RELREC_ENTRY, "CM._matched_ and AETERM != CM.CMTRT", "\"AETERM\"", null), ae(),
                r);
        assertEquals(RuleExecutionStatus.ERROR, flagged.getStatus(), String.valueOf(flagged));
        assertTrue(
                String.valueOf(flagged.getStatusMessage())
                        .startsWith(MergedEvaluation.MERGE_ERROR_MESSAGE_PREFIX),
                String.valueOf(flagged.getStatusMessage()));
        RuleExecutionResult clean = RuleRunnerCalls.execute(
                rule(RELREC_ENTRY, "LB._matched_ and AETERM != LB.LBTESTCD", "\"AETERM\"", null),
                ae(), r);
        assertEquals(Set.of(0L), rows(clean));
    }


    @Test
    void p14_oneQualifierFromAKeyedEntryAndARelatedDomainIsRefused()
    {
        // A keyed entry Name: CM and a RELREC-related CM would both publish CM.CMTRT: the
        // datatable refuses the clash and the rule ERRORs under the merged table's prefix.
        IDataTable rr = relrec("AE|AESEQ|1|R1", "CM|CMSEQ|1|R1");
        Rule rule = rule(RELREC_ENTRY + ",{\"Name\":\"CM\",\"Keys\":[\"STUDYID\",\"USUBJID\"]}",
                "AETERM != CM.CMTRT", "\"STUDYID\",\"USUBJID\"", null);
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae(), resolver(ae(), cm(), rr));
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(), String.valueOf(result));
        assertTrue(
                String.valueOf(result.getStatusMessage())
                        .startsWith(MergedEvaluation.MERGE_REFUSED_MESSAGE_PREFIX),
                String.valueOf(result.getStatusMessage()));
    }

    // ------------------------------------------------------------------ CDISC-CG0174 shape

    /** CG0174's respelled Check, the AE and CM arms. */
    private static final String CG0174 = "not empty(FAOBJ) and ((AE._matched_ and FAOBJ != AE.AETERM"
            + " and not equalsIgnoreCase(FAOBJ, AE.AEDECOD)) or (CM._matched_ and FAOBJ != CM.CMTRT"
            + " and not equalsIgnoreCase(FAOBJ, CM.CMDECOD)))";

    @Test
    void cg0174_twoRelationships_theMismatchingParentIsOneFinding()
    {
        // The rulespec fixture: FA 1 (ASPIRIN) ↔ AE (AEDECOD ASPIRIN) in R1 — no finding; FA 2
        // (MYSTERY) ↔ CM (IBU / IBUPROFEN) in R2 — one finding. FA 1 also ↔ CM in R3: the
        // mismatch there fires once on FA 1, with the R1 row (AE supplied, CM not) silent.
        IDataTable fa = RealTableFixture.of("FA").str("STUDYID", "S1", "S1")
                .str("DOMAIN", "FA", "FA").str("USUBJID", "01", "01").lng("FASEQ", 1L, 2L)
                .str("FAOBJ", "ASPIRIN", "MYSTERY").build();
        IDataTable aeT = RealTableFixture.of("AE").str("STUDYID", "S1").str("DOMAIN", "AE")
                .str("USUBJID", "01").lng("AESEQ", 1L).str("AETERM", "Headache")
                .str("AEDECOD", "ASPIRIN").build();
        IDataTable cmT = RealTableFixture.of("CM").str("STUDYID", "S1").str("DOMAIN", "CM")
                .str("USUBJID", "01").lng("CMSEQ", 1L).str("CMTRT", "IBU")
                .str("CMDECOD", "IBUPROFEN").build();
        IDataTable rr = relrec("FA|FASEQ|1|R1", "AE|AESEQ|1|R1", "FA|FASEQ|2|R2", "CM|CMSEQ|1|R2",
                "FA|FASEQ|1|R3", "CM|CMSEQ|1|R3");
        Rule rule = rule(RELREC_ENTRY, CG0174, "\"FAOBJ\"", null);
        MergedEvaluation.Built built = view(rule, fa, resolver(fa, aeT, cmT, rr));
        assertEquals(3, built.table().getRowCount(), "FA 1: R1 + R3; FA 2: R2");
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, fa, resolver(fa, aeT, cmT, rr));
        List<Map<String, String>> values = result.getViolations().stream().map(Violation::getValues)
                .toList();
        assertEquals(2, result.getViolations().size(), String.valueOf(values));
        assertEquals(Set.of(0L, 1L), rows(result));
        // the derived Output_Variables are the Check's comparison operands (R-9.4): the related
        // columns print qualified, a part not supplied on the row prints its type default
        assertEquals("IBU", values.get(0).get("CM.CMTRT"), String.valueOf(values));
        assertEquals("", values.get(0).get("AE.AETERM"),
                "the R3 row carries no AE: not supplied prints its type default");
    }
}
