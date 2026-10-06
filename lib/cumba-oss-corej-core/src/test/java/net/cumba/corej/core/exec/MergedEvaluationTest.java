package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.merge.MergeKind;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * W3a of {@code PLAN-merged-evaluation-table} ({@link MergedEvaluation}): the keyed parts and the
 * SUPP qualifiers are read from the merged view. Every behaviour change is shown against the legacy
 * arm ({@link MergedEvaluation#setLegacyForTests}) in the same test — the legacy result is the
 * red-first evidence, the view result the target; a read the view does not change is shown equal on
 * both arms; a planted row shift ({@link MergedEvaluation#plantForTests}) must move a finding, or
 * the view was not read.
 */
class MergedEvaluationTest
{

    @AfterEach
    void hooksOff()
    {
        MergedEvaluation.setLegacyForTests(false);
        MergedEvaluation.plantForTests(null);
    }


    private static Rule load(String ruleJson)
    {
        try
        {
            RulePackage pkg = RulePackageLoader
                    .loadFromString("{\"rules\":{\"X-1\":" + ruleJson + "}}");
            Rule rule = pkg.getRules().get("X-1");
            assertNull(rule.getLoadError(), "fixture must load: " + rule.getLoadError());
            return rule;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + ruleJson, e);
        }
    }


    /** An inventory-capable resolver, so the qualified key requirements can be decided. */
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
            public java.util.Set<String> availableDatasets()
            {
                java.util.Set<String> out = new java.util.LinkedHashSet<>();
                for (IDataTable t : tables)
                {
                    out.add(t.getMetaData().getName());
                }
                return out;
            }
        };
    }


    private static IDataTable ae()
    {
        return RealTableFixture.of("AE").str("STUDYID", "S1", "S1", "S1")
                .str("DOMAIN", "AE", "AE", "AE").str("USUBJID", "01", "01", "02")
                .lng("AESEQ", 1L, 2L, 1L).str("AETERM", "A", "B", "C").build();
    }


    private static IDataTable dm()
    {
        return RealTableFixture.of("DM").str("STUDYID", "S1", "S1", "S1")
                .str("USUBJID", "01", "02", "03").lng("AGE", 54L, 61L, 70L)
                .str("SEX", "F", "M", "F").build();
    }


    private static Rule keyedRule(String check)
    {
        return load("{\"Core\":{\"Id\":\"X-1\"},\"Requirements\":{\"Variables\":{"
                + "\"All\":[\"STUDYID\",\"USUBJID\"],\"All_Or_None\":[[\"STUDYID\",\"DM.STUDYID\"],"
                + "[\"USUBJID\",\"DM.USUBJID\"]]}},\"Match_Datasets\":[{\"Name\":\"DM\","
                + "\"Keys\":[\"STUDYID\",\"USUBJID\"],\"Join_Type\":\"left\"}],"
                + "\"Check\":{\"expression\":\"" + check + "\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"DM.AGE\",\"DM.SEX\"]}}");
    }


    private static Rule suppRule(String check, String outputVariables)
    {
        return load("{\"Core\":{\"Id\":\"X-1\"},\"Check\":{\"expression\":\"" + check + "\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[" + outputVariables
                + "]}}");
    }


    private static IDataTable suppAe(String... rows)
    {
        // rows: STUDYID|USUBJID|IDVAR|IDVARVAL|QVAL, all on QNAM AETRTEM, RDOMAIN AE
        int n = rows.length;
        String[] studyid = new String[n];
        String[] rdomain = new String[n];
        String[] usubjid = new String[n];
        String[] idvar = new String[n];
        String[] idvarval = new String[n];
        String[] qnam = new String[n];
        String[] qval = new String[n];
        for (int i = 0; i < n; i++)
        {
            String[] f = rows[i].split("\\|", -1);
            studyid[i] = f[0];
            rdomain[i] = "AE";
            usubjid[i] = f[1];
            idvar[i] = f[2];
            idvarval[i] = f[3];
            qnam[i] = "AETRTEM";
            qval[i] = f[4];
        }
        return RealTableFixture.of("SUPPAE").str("STUDYID", studyid).str("RDOMAIN", rdomain)
                .str("USUBJID", usubjid).str("IDVAR", idvar).str("IDVARVAL", idvarval)
                .str("QNAM", qnam).str("QVAL", qval).build();
    }


    private static RuleExecutionResult legacy(Rule rule, IDataTable table, DatasetResolver r)
    {
        MergedEvaluation.setLegacyForTests(true);
        try
        {
            return RuleRunnerCalls.execute(rule, table, r);
        }
        finally
        {
            MergedEvaluation.setLegacyForTests(false);
        }
    }


    @Test
    void keyedReadsAndTheMatchedFlagAgreeWithTheLegacyArm()
    {
        IDataTable ae = ae();
        IDataTable dm = RealTableFixture.of("DM").str("STUDYID", "S1").str("USUBJID", "01")
                .lng("AGE", 54L).str("SEX", "F").build();
        DatasetResolver r = resolver(ae, dm);
        for (String check : new String[]
        {
                "DM.AGE > 60 or DM.SEX == \\\"F\\\"", "not DM._matched_", "empty(DM.AGE)"
        })
        {
            Rule rule = keyedRule(check);
            RuleExecutionResult off = legacy(rule, ae, r);
            RuleExecutionResult on = RuleRunnerCalls.execute(rule, ae, r);
            assertEquals(RuleExecutionStatus.EXECUTED, on.getStatus(), check);
            assertEquals(off.getViolations(), on.getViolations(), check);
            assertFalse(on.getViolations().isEmpty(), check + " fired nowhere");
        }
    }


    @Test
    void aPlantedKeyedRowShiftMovesAFinding()
    {
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, dm());
        Rule rule = keyedRule("DM.AGE > 60");
        RuleExecutionResult clean = RuleRunnerCalls.execute(rule, ae, r);
        MergedEvaluation.plantForTests(MergeKind.KEYED);
        RuleExecutionResult planted = RuleRunnerCalls.execute(rule, ae, r);
        assertNotEquals(clean.getViolations(), planted.getViolations(),
                "the plant went unseen: the keyed reads are not served from the view");
    }


    @Test
    void aSuppQualifierIsReadFromTheViewAndAPlantedShiftIsSeen()
    {
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, suppAe("S1|01|AESEQ|1|Y", "S1|02|AESEQ|1|N"));
        Rule rule = suppRule("AETRTEM == \\\"Y\\\"", "\"AETRTEM\"");
        RuleExecutionResult off = legacy(rule, ae, r);
        RuleExecutionResult on = RuleRunnerCalls.execute(rule, ae, r);
        assertEquals(1, on.getViolations().size());
        assertEquals(off.getViolations(), on.getViolations());
        MergedEvaluation.plantForTests(MergeKind.SUPP);
        RuleExecutionResult planted = RuleRunnerCalls.execute(rule, ae, r);
        assertNotEquals(on.getViolations(), planted.getViolations(),
                "the plant went unseen: the qualifier is not served from the view");
    }


    @Test
    void aDuplicateQualifierErrorsTheRuleThatReadsItAndNoOther()
    {
        // §3.5.3 case 2: two SUPPAE rows qualify AE row 0 for AETRTEM. Legacy: the first wins
        // (red-first evidence); the view: the rule reading AETRTEM ERRORs quoting the flag, a rule
        // on AE that does not read it runs.
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, suppAe("S1|01|AESEQ|1|Y", "S1|01|AESEQ|1|N"));
        Rule reads = suppRule("AETRTEM == \\\"Y\\\"", "\"AETRTEM\"");
        RuleExecutionResult off = legacy(reads, ae, r);
        assertEquals(RuleExecutionStatus.EXECUTED, off.getStatus());
        assertEquals(1, off.getViolations().size(), "legacy: the first row won");
        RuleExecutionResult on = RuleRunnerCalls.execute(reads, ae, r);
        assertEquals(RuleExecutionStatus.ERROR, on.getStatus(), String.valueOf(on));
        String msg = String.valueOf(on.getStatusMessage());
        assertTrue(msg.contains("AETRTEM") && msg.contains("MERGE_ERROR")
                && msg.contains("more than one row qualifies one record"), msg);
        Rule other = suppRule("AETERM == \\\"A\\\"", "\"AETERM\"");
        RuleExecutionResult unaffected = RuleRunnerCalls.execute(other, ae, r);
        assertEquals(RuleExecutionStatus.EXECUTED, unaffected.getStatus());
        assertEquals(1, unaffected.getViolations().size());
    }


    @Test
    void anOutputVariableNamingAFlaggedQualifierErrorsTheRule()
    {
        // The static gate reads every referenced name, not only the Check's: a rule whose Check
        // never touches AETRTEM but reports it ERRORs too (it would report a value the data
        // cannot supply).
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, suppAe("S1|01|AESEQ|1|Y", "S1|01|AESEQ|1|N"));
        Rule reports = suppRule("AETERM == \\\"A\\\"", "\"AETERM\",\"AETRTEM\"");
        assertEquals(RuleExecutionStatus.EXECUTED, legacy(reports, ae, r).getStatus());
        RuleExecutionResult on = RuleRunnerCalls.execute(reports, ae, r);
        assertEquals(RuleExecutionStatus.ERROR, on.getStatus(), String.valueOf(on));
    }


    @Test
    void aSubjectAndARecordLevelRowForOneSubjectErrorAndAnUnknownIdvarErrors()
    {
        IDataTable ae = ae();
        Rule reads = suppRule("AETRTEM == \\\"Y\\\"", "\"AETRTEM\"");
        // case 3
        DatasetResolver level = resolver(ae, suppAe("S1|01||\u0000|N", "S1|01|AESEQ|1|Y"));
        assertEquals(RuleExecutionStatus.EXECUTED, legacy(reads, ae, level).getStatus());
        RuleExecutionResult on3 = RuleRunnerCalls.execute(reads, ae, level);
        assertEquals(RuleExecutionStatus.ERROR, on3.getStatus(), String.valueOf(on3));
        assertTrue(
                String.valueOf(on3.getStatusMessage())
                        .contains("a subject-level and a record-level row qualify one subject"),
                on3.getStatusMessage());
        // case 6
        DatasetResolver unknown = resolver(ae, suppAe("S1|01|AEGRPID|G1|Y"));
        assertEquals(RuleExecutionStatus.EXECUTED, legacy(reads, ae, unknown).getStatus());
        RuleExecutionResult on6 = RuleRunnerCalls.execute(reads, ae, unknown);
        assertEquals(RuleExecutionStatus.ERROR, on6.getStatus(), String.valueOf(on6));
        assertTrue(String.valueOf(on6.getStatusMessage()).contains("IDVAR AEGRPID is not a column"),
                on6.getStatusMessage());
    }


    @Test
    void theIgKeyCarriesStudyidAndRdomain()
    {
        // §3.5.1 "STUDYID in the key": a SUPPAE row of study S2 qualifies no AE record of S1.
        // Legacy (never read STUDYID): matched, 1 finding — the red-first evidence.
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, suppAe("S2|01|AESEQ|1|Y"));
        Rule reads = suppRule("AETRTEM == \\\"Y\\\"", "\"AETRTEM\"");
        assertEquals(1, legacy(reads, ae, r).getViolations().size());
        RuleExecutionResult on = RuleRunnerCalls.execute(reads, ae, r);
        assertEquals(RuleExecutionStatus.EXECUTED, on.getStatus());
        assertEquals(0, on.getViolations().size(), String.valueOf(on.getViolations()));
    }


    @Test
    void aFlaggedColumnReadThroughTheViewThrowsAtReadTime()
    {
        // The read-time backstop behind the static gate (OD-7): a reader that reaches the
        // qualifier column of a flagged QNAM gets the exception, never a default.
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, suppAe("S1|01|AESEQ|1|Y", "S1|01|AESEQ|1|N"));
        MergedEvaluation.Built built = MergedEvaluation.build(suppRule("AETERM == \\\"A\\\"", ""),
                ae, ae, r, null, "X-1", "AE");
        assertNotNull(built);
        assertEquals(java.util.Set.of("AETRTEM"), built.flagged().keySet());
        assertThrows(MergeErrorReadException.class, () -> built.suppColumn("AETRTEM"));
        assertNull(built.suppColumn("AETERM"), "a primary column is never a qualifier");
        assertNull(built.suppColumn("NOSUCH"));
    }


    @Test
    void anAbsentJoinedColumnFoldsToOneDatasetLevelAnswer()
    {
        // N24 (§3.3): DM.ARMNRS on a DM without ARMNRS is an absent column of the merged schema
        // and folds like a bare absent column — one dataset-level answer, not one per row. The
        // arm does not decide this (the level calculus reads the join through its lookup on
        // both); red-first evidence: against the BroadcastFold / LevelInstrument of 9e56b53 this
        // test reported 3 findings, one per row.
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, dm());
        Rule rule = keyedRule("DM.ARMNRS != \\\"SCREEN FAILURE\\\"");
        RuleExecutionResult on = RuleRunnerCalls.execute(rule, ae, r);
        assertEquals(RuleExecutionStatus.EXECUTED, on.getStatus(), String.valueOf(on));
        assertEquals(1, on.getViolations().size(), String.valueOf(on.getViolations()));
    }


    @Test
    void theViewIsBuiltOncePerSignatureOnADatasetsJoinCache()
    {
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, dm(), suppAe("S1|01|AESEQ|1|Y"));
        JoinCache cache = new JoinCache(new JoinCache.SharedIndexCache());
        MergedEvaluation.Built first = MergedEvaluation.build(keyedRule("DM.AGE > 60"), ae, ae, r,
                cache, "X-1", "AE");
        MergedEvaluation.Built second = MergedEvaluation.build(keyedRule("DM.SEX == \\\"F\\\""), ae,
                ae, r, cache, "X-1", "AE");
        assertNotNull(first);
        assertSame(first, second, "two rules with one merge signature share one view");
        MergedEvaluation.Built suppOnly = MergedEvaluation
                .build(suppRule("AETRTEM == \\\"Y\\\"", ""), ae, ae, r, cache, "X-1", "AE");
        assertNotNull(suppOnly);
        assertNotEquals(first, suppOnly, "a different signature is a different view");
        assertSame(ae, suppOnly.table(), "no row drops or expands: the primary is the table");
        assertEquals(Map.of(), suppOnly.lookups());
    }


    @Test
    void aForwardRelrecRuleIsServedAndNothingToMergeStaysOnTheLegacyArm()
    {
        IDataTable ae = ae();
        // W5: a forward RELREC rule builds the view even when RELREC does not resolve — the entry
        // is declared, its related-domain reads take the not-supplied default (P-1)
        Rule relrec = load("{\"Core\":{\"Id\":\"X-1\"},\"Match_Datasets\":[{\"Name\":\"RELREC\","
                + "\"Keys\":[]}],\"Check\":{\"expression\":\"AETERM == \\\"A\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}");
        MergedEvaluation.Built served = MergedEvaluation.build(relrec, ae, ae, resolver(ae), null,
                "X-1", "AE");
        assertNotNull(served, "W5: served by the view (RelrecEntries)");
        assertTrue(served.relrecDeclared());
        assertEquals(Map.of(), served.lookups(), "RELREC absent: no part, nothing row-mapped");
        Rule plain = load("{\"Core\":{\"Id\":\"X-1\"},\"Supp_Merge\":false,"
                + "\"Check\":{\"expression\":\"AETERM == \\\"A\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}");
        assertNull(MergedEvaluation.build(plain, ae, ae, resolver(ae), null, "X-1", "AE"),
                "Supp_Merge off and no keyed entry: nothing to merge");
        Rule suppOnly = suppRule("AETERM == \\\"A\\\"", "");
        assertNull(MergedEvaluation.build(suppOnly, ae, ae, resolver(ae), null, "X-1", "AE"),
                "the SUPP merge is declared but no SUPPAE resolves: nothing to merge, no view");
        MergedEvaluation.Built withSupp = MergedEvaluation.build(suppOnly, ae, ae,
                resolver(ae, suppAe("S1|01|AESEQ|1|Y")), null, "X-1", "AE");
        assertNotNull(withSupp, "a resolvable SUPPAE is merged");
        assertSame(ae, withSupp.table(), "a SUPP part never drops or expands a row");
        assertEquals(Map.of(), withSupp.flagged());
        assertNotNull(withSupp.suppColumn("AETRTEM"));
        assertNull(withSupp.suppColumn("AETERM"), "a primary column is never a qualifier");
    }
}
