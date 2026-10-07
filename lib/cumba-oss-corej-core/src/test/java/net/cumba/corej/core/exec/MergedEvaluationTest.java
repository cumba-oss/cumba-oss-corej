package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
 * SUPP qualifiers are read from the merged view. Until W6 every behaviour change was shown against
 * the legacy arm in the same test (the red-first evidence); W6 retired that arm, so the reads the
 * view did not change are pinned by the rows they report — the legacy arm's answer, recorded — and
 * a planted row shift ({@link MergedEvaluation#plantForTests}) must move a finding, or the view was
 * not read.
 */
class MergedEvaluationTest
{

    @AfterEach
    void hooksOff()
    {
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
        return keyedRule(check, "left");
    }


    private static Rule keyedRule(String check, String joinType)
    {
        return keyedRule(check, joinType, "");
    }


    /**
     * {@code outcomeExtra}: further {@code Outcome} entries, e.g. {@code Message_Folded} — Q6
     * ({@code PLAN-message-folded-corpus-alignment}): the absent-column fold is opt-in through it.
     */
    private static Rule keyedRule(String check, String joinType, String outcomeExtra)
    {
        return load("{\"Core\":{\"Id\":\"X-1\"},\"Requirements\":{\"Variables\":{"
                + "\"All\":[\"STUDYID\",\"USUBJID\"],\"All_Or_None\":[[\"STUDYID\",\"DM.STUDYID\"],"
                + "[\"USUBJID\",\"DM.USUBJID\"]]}},\"Match_Datasets\":[{\"Name\":\"DM\","
                + "\"Keys\":[\"STUDYID\",\"USUBJID\"],\"Join_Type\":\"" + joinType + "\"}],"
                + "\"Check\":{\"expression\":\"" + check + "\"},"
                + "\"Outcome\":{\"Message\":\"m\"," + outcomeExtra
                + "\"Output_Variables\":[\"DM.AGE\",\"DM.SEX\"]}}");
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


    /** The rows a result's findings sit on, in order. */
    private static java.util.List<Long> rows(RuleExecutionResult result)
    {
        return result.getViolations().stream().map(Violation::getRow).toList();
    }


    @Test
    void keyedReadsAndTheMatchedFlagKeepTheLegacyArmsAnswer()
    {
        // AE 01/1, 01/2, 02/1 left-joined to a DM holding subject 01 only (54, F): the rows each
        // check reported on both arms when W3a compared them, pinned since W6 retired the legacy
        IDataTable ae = ae();
        IDataTable dm = RealTableFixture.of("DM").str("STUDYID", "S1").str("USUBJID", "01")
                .lng("AGE", 54L).str("SEX", "F").build();
        DatasetResolver r = resolver(ae, dm);
        Map<String, java.util.List<Long>> expected = new java.util.LinkedHashMap<>();
        expected.put("DM.AGE > 60 or DM.SEX == \\\"F\\\"", java.util.List.of(0L, 1L));
        expected.put("not DM._matched_", java.util.List.of(2L));
        expected.put("empty(DM.AGE)", java.util.List.of(2L));
        for (Map.Entry<String, java.util.List<Long>> e : expected.entrySet())
        {
            RuleExecutionResult on = RuleRunnerCalls.execute(keyedRule(e.getKey()), ae, r);
            assertEquals(RuleExecutionStatus.EXECUTED, on.getStatus(), e.getKey());
            assertEquals(e.getValue(), rows(on), e.getKey());
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
        RuleExecutionResult on = RuleRunnerCalls.execute(rule, ae, r);
        assertEquals(java.util.List.of(0L), rows(on), "AE 01/1 is qualified Y (both arms, W3a)");
        MergedEvaluation.plantForTests(MergeKind.SUPP);
        RuleExecutionResult planted = RuleRunnerCalls.execute(rule, ae, r);
        assertNotEquals(on.getViolations(), planted.getViolations(),
                "the plant went unseen: the qualifier is not served from the view");
    }


    @Test
    void aDuplicateQualifierErrorsTheRuleThatReadsItAndNoOther()
    {
        // §3.5.3 case 2: two SUPPAE rows qualify AE row 0 for AETRTEM. The retired legacy pivot
        // let the first win (W3a's red-first evidence); the view: the rule reading AETRTEM ERRORs
        // quoting the flag, a rule on AE that does not read it runs.
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, suppAe("S1|01|AESEQ|1|Y", "S1|01|AESEQ|1|N"));
        Rule reads = suppRule("AETRTEM == \\\"Y\\\"", "\"AETRTEM\"");
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
        RuleExecutionResult on3 = RuleRunnerCalls.execute(reads, ae, level);
        assertEquals(RuleExecutionStatus.ERROR, on3.getStatus(), String.valueOf(on3));
        assertTrue(
                String.valueOf(on3.getStatusMessage())
                        .contains("a subject-level and a record-level row qualify one subject"),
                on3.getStatusMessage());
        // case 6
        DatasetResolver unknown = resolver(ae, suppAe("S1|01|AEGRPID|G1|Y"));
        RuleExecutionResult on6 = RuleRunnerCalls.execute(reads, ae, unknown);
        assertEquals(RuleExecutionStatus.ERROR, on6.getStatus(), String.valueOf(on6));
        assertTrue(String.valueOf(on6.getStatusMessage()).contains("IDVAR AEGRPID is not a column"),
                on6.getStatusMessage());
    }


    @Test
    void theIgKeyCarriesStudyidAndRdomain()
    {
        // §3.5.1 "STUDYID in the key": a SUPPAE row of study S2 qualifies no AE record of S1 (the
        // retired legacy pivot never read STUDYID: matched, 1 finding — W3a's red-first evidence).
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, suppAe("S2|01|AESEQ|1|Y"));
        Rule reads = suppRule("AETRTEM == \\\"Y\\\"", "\"AETRTEM\"");
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


    /** SUPPDM, subject-level rows as USUBJID|QNAM|QVAL (study S1). */
    private static IDataTable suppDm(String... rows)
    {
        int n = rows.length;
        String[][] c = new String[3][n];
        for (int i = 0; i < n; i++)
        {
            String[] f = rows[i].split("\\|", -1);
            for (int k = 0; k < 3; k++)
            {
                c[k][i] = f[k];
            }
        }
        String[] studyid = new String[n];
        String[] rdomain = new String[n];
        String[] blank = new String[n];
        java.util.Arrays.fill(studyid, "S1");
        java.util.Arrays.fill(rdomain, "DM");
        java.util.Arrays.fill(blank, "");
        return RealTableFixture.of("SUPPDM").str("STUDYID", studyid).str("RDOMAIN", rdomain)
                .str("USUBJID", c[0]).str("IDVAR", blank).str("IDVARVAL", blank).str("QNAM", c[1])
                .str("QVAL", c[2]).build();
    }


    /**
     * T2 M4 (OD-7, MQ5): a partner's SUPP qualifier two rows qualify for one record is flagged, and
     * every value read of it through the keyed part — {@code lookupValue}, {@code columnReader},
     * {@code lookup}, the three channels a dynamic read ({@code colref("DM.RACEOTH")}) takes —
     * throws, never reads the not-supplied default. Control: the partner's unflagged qualifier and
     * own column still read.
     */
    @Test
    void aFlaggedPartnerSuppQualifierThrowsOnEveryReadChannel()
    {
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, dm(),
                suppDm("01|RACEOTH|A", "01|RACEOTH|B", "01|DMOTHER|Z"));
        MergedEvaluation.Built built = MergedEvaluation.build(keyedRule("AETERM == \\\"A\\\""), ae,
                ae, r, null, "X-1", "AE");
        assertNotNull(built);
        assertEquals(java.util.Set.of("DM.RACEOTH"), built.flagged().keySet());
        JoinLookup dmPart = java.util.Objects.requireNonNull(built.lookups().get("DM"));
        IDataTable t = built.table();
        assertThrows(MergeErrorReadException.class,
                () -> dmPart.lookupValue(t, 0, "RACEOTH", false));
        java.util.function.LongFunction<net.cumba.datatable.values.IDataValue> reader = dmPart
                .columnReader(t, "RACEOTH", false);
        MergeErrorReadException ex = assertThrows(MergeErrorReadException.class,
                () -> reader.apply(0));
        assertTrue(
                String.valueOf(ex.getMessage())
                        .startsWith(MergedEvaluation.MERGE_ERROR_MESSAGE_PREFIX + "DM.RACEOTH"),
                ex.getMessage());
        assertThrows(MergeErrorReadException.class, () -> dmPart.lookup(t, 0, "RACEOTH"));
        assertTrue(dmPart.hasColumn(t, 0, "RACEOTH"), "the column exists; its values cannot");
        // the planted control: an unflagged qualifier and an own column of the same part read
        assertEquals("Z", dmPart.lookup(t, 0, "DMOTHER"));
        assertEquals("Z", dmPart.lookupValue(t, 0, "DMOTHER", false).getValueAsString());
        assertEquals("54", dmPart.columnReader(t, "AGE", true).apply(0).getValueAsString());
    }


    /** T2 M4 end to end: a {@code colref} to the flagged partner qualifier ERRORs the rule. */
    @Test
    void aDynamicReadOfAFlaggedPartnerQualifierErrorsTheRule()
    {
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, dm(),
                suppDm("01|RACEOTH|A", "01|RACEOTH|B", "01|DMOTHER|Z"));
        RuleExecutionResult flagged = RuleRunnerCalls.execute(
                keyedRule("colref(concat(\\\"DM.\\\", \\\"RACEOTH\\\")) == \\\"A\\\""), ae, r);
        assertEquals(RuleExecutionStatus.ERROR, flagged.getStatus(), String.valueOf(flagged));
        assertTrue(String.valueOf(flagged.getStatusMessage()).startsWith(
                MergedEvaluation.MERGE_ERROR_MESSAGE_PREFIX), flagged.getStatusMessage());
        RuleExecutionResult control = RuleRunnerCalls.execute(
                keyedRule("colref(concat(\\\"DM.\\\", \\\"DMOTHER\\\")) == \\\"Z\\\""), ae, r);
        assertEquals(RuleExecutionStatus.EXECUTED, control.getStatus(), String.valueOf(control));
        assertEquals(java.util.List.of(0L, 1L), rows(control), "subject 01's two AE records");
    }


    /** {@code base} with its rows in reverse order — a sorted view: not positional. */
    private static IDataTable reversed(IDataTable base)
    {
        int n = Math.toIntExact(base.getRowCount());
        net.cumba.datatable.impl.databuffer.IDataBufferNumeric map = net.cumba.datatable.impl.databuffer.DataBufferFactory
                .get().createForRange(0, Math.max(0, n - 1L));
        map.setExpectedSize(n);
        for (int i = n - 1; i >= 0; i--)
        {
            map.addValue(i);
        }
        return new net.cumba.datatable.impl.view.ViewDataTable(base,
                new net.cumba.datatable.impl.view.DataTableViewBuffer(map), null);
    }


    /**
     * T2 L1: a primary whose real rows are not its positions (a sorted {@code ViewDataTable}) under
     * a row-mapped view (an inner join drops AE 02/1). The evaluation table maps each merged row to
     * the primary's POSITION (part 0's base-row map) and reports its real row once — never the real
     * row read again as a position (the double mapping read the dropped record C and lost A).
     * Control: the unmerged rule over the same sorted primary reports A on the same real row.
     */
    @Test
    void aSortedPrimaryUnderARowMappedViewReadsAndReportsTheRightRecords()
    {
        IDataTable ae = reversed(ae());
        IDataTable dm01 = RealTableFixture.of("DM").str("STUDYID", "S1").str("USUBJID", "01")
                .lng("AGE", 54L).str("SEX", "F").build();
        DatasetResolver r = resolver(ae, dm01);
        MergedEvaluation.Built built = MergedEvaluation
                .build(keyedRule("AETERM == \\\"A\\\"", "inner"), ae, ae, r, null, "X-1", "AE");
        assertNotNull(built);
        assertTrue(built.view().isRowMapped(), "the inner join drops AE 02/1: row-mapped");
        IDataTable t = built.table();
        int term = t.getMetaData().getColumnIndex("AETERM");
        java.util.List<String> terms = new java.util.ArrayList<>();
        java.util.List<Long> real = new java.util.ArrayList<>();
        for (long i = 0; i < t.getRowCount(); i++)
        {
            terms.add(t.getDataValue(i, term).getValueAsString());
            real.add(t.getRealRowIndex(i));
        }
        assertEquals(java.util.List.of("B", "A"), terms, "the sorted primary's surviving records");
        assertEquals(java.util.List.of(1L, 0L), real, "each reported on its own base row");
        RuleExecutionResult dropped = RuleRunnerCalls
                .execute(keyedRule("AETERM == \\\"C\\\"", "inner"), ae, r);
        assertEquals(java.util.List.of(), rows(dropped), "C was dropped by the inner join");
        RuleExecutionResult merged = RuleRunnerCalls
                .execute(keyedRule("AETERM == \\\"A\\\"", "inner"), ae, r);
        RuleExecutionResult alone = RuleRunnerCalls.execute(suppRule("AETERM == \\\"A\\\"", ""), ae,
                resolver(ae));
        assertEquals(1, merged.getViolations().size(), String.valueOf(merged.getViolations()));
        assertEquals(rows(alone), rows(merged), "the same real row as the unmerged run");
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
        // Q6 (PLAN-message-folded-corpus-alignment): the fold is opt-in — the rule declares
        // Message_Folded.
        Rule rule = keyedRule("DM.ARMNRS != \\\"SCREEN FAILURE\\\"", "left",
                "\"Message_Folded\":\"m (folded)\",");
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
    void aForwardRelrecRuleIsServedAndNothingToMergeBuildsNoView()
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
