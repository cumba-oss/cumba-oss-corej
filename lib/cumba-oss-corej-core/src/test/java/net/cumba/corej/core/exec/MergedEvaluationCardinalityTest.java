package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.merge.MergeException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * W3f of {@code PLAN-merged-evaluation-table}: the authored {@code Cardinality} of a
 * {@code Match_Datasets} entry (§3.4, MET #12, P-16, OD-10) and the authored {@code Supp_Keys}
 * (§3.5.1, MET #13 / #18) reach the merged evaluation table.
 *
 * <p>
 * Every violation is shown against its <b>control</b> — the same data without the authored value,
 * where the merge expands (or keys on the IG) as before — so a test that passes because the field
 * is never read cannot pass: the control and the authored run must differ. The legacy arm
 * ({@link MergedEvaluation#setLegacyForTests}) checks no cardinality and keys SUPP on the IG only;
 * where it runs it is the red-first evidence of the same test.
 * </p>
 */
class MergedEvaluationCardinalityTest
{

    @AfterEach
    void hooksOff()
    {
        MergedEvaluation.setLegacyForTests(false);
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


    /** DM rows as STUDYID|USUBJID|AGE. */
    private static IDataTable dm(String... rows)
    {
        int n = rows.length;
        String[] studyid = new String[n];
        String[] usubjid = new String[n];
        Long[] age = new Long[n];
        for (int i = 0; i < n; i++)
        {
            String[] f = rows[i].split("\\|", -1);
            studyid[i] = f[0];
            usubjid[i] = f[1];
            age[i] = Long.valueOf(f[2]);
        }
        return RealTableFixture.of("DM").str("STUDYID", studyid).str("USUBJID", usubjid)
                .lng("AGE", age).build();
    }


    /**
     * A rule joining DM: {@code keys} as JSON array items, {@code extra} added to the entry (e.g.
     * {@code ,"Cardinality":"n:1"}).
     */
    private static Rule dmRule(String keys, String extra)
    {
        StringBuilder groups = new StringBuilder();
        java.util.List<String> names = java.util.Arrays
                .asList(keys.replace("\"", "").split(",", -1));
        for (String k : names)
        {
            groups.append(groups.isEmpty() ? "" : ",").append("[\"").append(k).append("\",\"DM.")
                    .append(k).append("\"]");
        }
        String first = names.get(0);
        return load("{\"Core\":{\"Id\":\"X-1\"},\"Requirements\":{\"Variables\":{" + "\"All\":[\""
                + first + "\"],\"All_Or_None\":[" + groups + "]}},"
                + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[" + keys + "]" + extra + "}],"
                + "\"Check\":{\"expression\":\"DM.AGE > 60\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"DM.AGE\"]}}");
    }

    private static final String STUDY_SUBJECT = "\"STUDYID\",\"USUBJID\"";

    private static final String N_TO_ONE = ",\"Cardinality\":\"n:1\"";

    private static void assertCardinalityError(RuleExecutionResult result, String... fragments)
    {
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(), String.valueOf(result));
        String msg = String.valueOf(result.getStatusMessage());
        assertTrue(msg.startsWith(MergedEvaluation.MERGE_REFUSED_MESSAGE_PREFIX), msg);
        for (String f : fragments)
        {
            assertTrue(msg.contains(f), "'" + f + "' not in: " + msg);
        }
    }


    @Test
    void aDuplicateDmKeyUnderNToOneErrorsTheRuleInsteadOfExpanding()
    {
        // §3.4 Example B. Control (unauthored = n:m): AE subject 01's two records each expand into
        // two merged rows, one per DM row — the old answer, and the legacy arm's even when n:1 is
        // authored (it checks no cardinality).
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, dm("S1|01|70", "S1|02|30", "S1|01|71"));
        RuleExecutionResult control = RuleRunnerCalls.execute(dmRule(STUDY_SUBJECT, ""), ae, r);
        assertEquals(RuleExecutionStatus.EXECUTED, control.getStatus(), String.valueOf(control));
        assertEquals(4, control.getViolations().size(), "n:m expands: " + control.getViolations());
        Rule nToOne = dmRule(STUDY_SUBJECT, N_TO_ONE);
        MergedEvaluation.setLegacyForTests(true);
        RuleExecutionResult legacy = RuleRunnerCalls.execute(nToOne, ae, r);
        MergedEvaluation.setLegacyForTests(false);
        assertEquals(RuleExecutionStatus.EXECUTED, legacy.getStatus());
        assertCardinalityError(RuleRunnerCalls.execute(nToOne, ae, r), "Cardinality n:1",
                "DM has duplicate key (STUDYID=S1, USUBJID=01) at rows 1 and 3");
    }


    @Test
    void aDuplicateWhoseSubjectNeverAppearsInTheEvaluatedDatasetStillErrors()
    {
        // P-16: the WHOLE partner side is checked — the owner's "DM must not contain duplicate
        // keys", not "no AE record meets a duplicate".
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, dm("S1|01|70", "S1|02|30", "S1|03|40", "S1|03|41"));
        assertEquals(RuleExecutionStatus.EXECUTED,
                RuleRunnerCalls.execute(dmRule(STUDY_SUBJECT, ""), ae, r).getStatus());
        assertCardinalityError(RuleRunnerCalls.execute(dmRule(STUDY_SUBJECT, N_TO_ONE), ae, r),
                "(STUDYID=S1, USUBJID=03) at rows 3 and 4");
    }


    @Test
    void aPooledDmIsNoViolationOnceTheKeyCarriesStudyid()
    {
        // OD-10: one USUBJID under two studies is unique per (STUDYID, USUBJID) (SDTMIG 3.4 sec.
        // 4.2.3). Keyed on USUBJID alone the same DM is a duplicate — why the corpus adds STUDYID.
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, dm("S1|01|70", "S1|02|30", "S2|01|71"));
        RuleExecutionResult pooled = RuleRunnerCalls.execute(dmRule(STUDY_SUBJECT, N_TO_ONE), ae,
                r);
        assertEquals(RuleExecutionStatus.EXECUTED, pooled.getStatus(), String.valueOf(pooled));
        assertEquals(2, pooled.getViolations().size(), "subject 01's two AE records, once each");
        assertCardinalityError(RuleRunnerCalls.execute(dmRule("\"USUBJID\"", N_TO_ONE), ae, r),
                "(USUBJID=01) at rows 1 and 3");
    }


    @Test
    void blankPartnerKeysAreDuplicatesOfEachOtherUnlessTheyDoNotParticipate()
    {
        // §3.4: participating blank keys count as duplicates of each other (JKM R5: two "" are one
        // key); under keep_missings: false they do not participate (P-16) and nothing is checked.
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, dm("S1|01|70", "S1||30", "S1||31"));
        assertCardinalityError(RuleRunnerCalls.execute(dmRule(STUDY_SUBJECT, N_TO_ONE), ae, r),
                "at rows 2 and 3");
        RuleExecutionResult dropped = RuleRunnerCalls
                .execute(dmRule(STUDY_SUBJECT, N_TO_ONE + ",\"keep_missings\":false"), ae, r);
        assertEquals(RuleExecutionStatus.EXECUTED, dropped.getStatus(), String.valueOf(dropped));
    }


    @Test
    void aFilteredOutDuplicateDoesNotParticipate()
    {
        // P-16: the check covers the participating rows, after the Filter mask.
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, dm("S1|01|70", "S1|02|30", "S1|01|20"));
        assertCardinalityError(RuleRunnerCalls.execute(dmRule(STUDY_SUBJECT, N_TO_ONE), ae, r),
                "at rows 1 and 3");
        RuleExecutionResult masked = RuleRunnerCalls
                .execute(dmRule(STUDY_SUBJECT, N_TO_ONE + ",\"Filter\":\"AGE > 25\""), ae, r);
        assertEquals(RuleExecutionStatus.EXECUTED, masked.getStatus(), String.valueOf(masked));
        assertEquals(2, masked.getViolations().size(), String.valueOf(masked.getViolations()));
    }


    @Test
    void oneToManyChecksTheEvaluatedSide()
    {
        // 1:n declares the evaluated dataset unique: AE carries subject 01 twice.
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, dm("S1|01|70", "S1|02|30"));
        assertEquals(RuleExecutionStatus.EXECUTED, RuleRunnerCalls
                .execute(dmRule(STUDY_SUBJECT, ",\"Cardinality\":\"n:m\""), ae, r).getStatus());
        assertCardinalityError(
                RuleRunnerCalls.execute(dmRule(STUDY_SUBJECT, ",\"Cardinality\":\"1:n\""), ae, r),
                "Cardinality 1:n", "AE has duplicate key (STUDYID=S1, USUBJID=01) at rows 1 and 2");
        assertCardinalityError(
                RuleRunnerCalls.execute(dmRule(STUDY_SUBJECT, ",\"Cardinality\":\"1:1\""), ae, r),
                "Cardinality 1:1", "AE has duplicate key");
    }


    @Test
    void theCardinalityIsPartOfTheMergeSignature()
    {
        // P-3: two rules over one DM with different Cardinality must not share a view — an n:m
        // view cached first would otherwise serve the n:1 rule its expansion.
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, dm("S1|01|70", "S1|02|30", "S1|01|71"));
        JoinCache cache = new JoinCache(new JoinCache.SharedIndexCache());
        assertNotNull(
                MergedEvaluation.build(dmRule(STUDY_SUBJECT, ""), ae, ae, r, cache, "X-1", "AE"));
        assertThrows(MergeException.class, () -> MergedEvaluation
                .build(dmRule(STUDY_SUBJECT, N_TO_ONE), ae, ae, r, cache, "X-1", "AE"));
    }


    @Test
    void aRefusedSignatureIsDecidedOncePerDataset()
    {
        // JoinCache (P-16 "memoised"): the refusal is a fact of (dataset, signature); every rule
        // of the signature re-raises the one decision instead of re-indexing DM to find it again.
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, dm("S1|01|70", "S1|02|30", "S1|01|71"));
        JoinCache cache = new JoinCache(new JoinCache.SharedIndexCache());
        MergeException first = assertThrows(MergeException.class, () -> MergedEvaluation
                .build(dmRule(STUDY_SUBJECT, N_TO_ONE), ae, ae, r, cache, "X-1", "AE"));
        MergeException second = assertThrows(MergeException.class, () -> MergedEvaluation
                .build(dmRule(STUDY_SUBJECT, N_TO_ONE), ae, ae, r, cache, "X-2", "AE"));
        assertSame(first, second, "the second rule re-raises the memoised refusal");
        assertEquals(1, cache.mergedViewCount(), "one signature decided");
        assertTrue(cache.mergedViews().isEmpty(), "a refusal is not a view");
    }

    // ---- Supp_Keys


    private static IDataTable suppAe(String studyid)
    {
        return RealTableFixture.of("SUPPAE").str("STUDYID", studyid).str("RDOMAIN", "AE")
                .str("USUBJID", "01").str("IDVAR", "AESEQ").str("IDVARVAL", "1")
                .str("QNAM", "AETRTEM").str("QVAL", "Y").build();
    }


    private static Rule suppRule(@Nullable String suppKeys)
    {
        return load("{\"Core\":{\"Id\":\"X-1\"}"
                + (suppKeys == null ? "" : ",\"Supp_Keys\":" + suppKeys)
                + ",\"Check\":{\"expression\":\"AETRTEM == \\\"Y\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AETRTEM\"]}}");
    }

    private static final String NO_STUDYID = "[{\"left\":\"DOMAIN\",\"right\":\"RDOMAIN\"},"
            + "\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]";

    @Test
    void anAuthoredSuppKeyReplacesTheIgKey()
    {
        // §3.5.1: the IG's key carries STUDYID, so a SUPPAE row of study S2 qualifies no AE record
        // of S1 (the control). An authored key without STUDYID is the author's: the row qualifies
        // AE row 0.
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, suppAe("S2"));
        RuleExecutionResult ig = RuleRunnerCalls.execute(suppRule(null), ae, r);
        assertEquals(RuleExecutionStatus.EXECUTED, ig.getStatus(), String.valueOf(ig));
        assertEquals(0, ig.getViolations().size(), String.valueOf(ig.getViolations()));
        RuleExecutionResult authored = RuleRunnerCalls.execute(suppRule(NO_STUDYID), ae, r);
        assertEquals(RuleExecutionStatus.EXECUTED, authored.getStatus(), String.valueOf(authored));
        assertEquals(1, authored.getViolations().size(), String.valueOf(authored.getViolations()));
        assertEquals(0, authored.getViolations().get(0).getRow());
    }


    @Test
    void theAuthoredSuppKeyIsPartOfTheMergeSignature()
    {
        IDataTable ae = ae();
        DatasetResolver r = resolver(ae, suppAe("S2"));
        JoinCache cache = new JoinCache(new JoinCache.SharedIndexCache());
        MergedEvaluation.Built ig = MergedEvaluation.build(suppRule(null), ae, ae, r, cache, "X-1",
                "AE");
        MergedEvaluation.Built authored = MergedEvaluation.build(suppRule(NO_STUDYID), ae, ae, r,
                cache, "X-1", "AE");
        assertNotNull(ig);
        assertNotNull(authored);
        assertEquals(2, cache.mergedViewCount(), "two keys, two views");
        assertTrue(ig != authored, "an authored key never reads the IG-keyed view");
    }


    @Test
    void aJoinedDatasetsQualifiersAreKeyedByTheSameSuppKeys()
    {
        // MQ5: DM.<QNAM> is served "under the same Supp_Merge / Supp_Keys". SUPPDM of study S2
        // qualifies subject 01's DM record only under an authored key without STUDYID.
        IDataTable ae = ae();
        IDataTable suppdm = RealTableFixture.of("SUPPDM").str("STUDYID", "S2").str("RDOMAIN", "DM")
                .str("USUBJID", "01").str("IDVAR", "").str("IDVARVAL", "").str("QNAM", "RACEOTH")
                .str("QVAL", "OTHER").build();
        DatasetResolver r = resolver(ae, dm("S1|01|70", "S1|02|30"), suppdm);
        String head = "{\"Core\":{\"Id\":\"X-1\"},\"Requirements\":{\"Variables\":{"
                + "\"All\":[\"USUBJID\"],\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]}},"
                + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}]";
        String tail = ",\"Check\":{\"expression\":\"DM.RACEOTH == \\\"OTHER\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"DM.RACEOTH\"]}}";
        RuleExecutionResult ig = RuleRunnerCalls.execute(load(head + tail), ae, r);
        assertEquals(RuleExecutionStatus.EXECUTED, ig.getStatus(), String.valueOf(ig));
        assertEquals(0, ig.getViolations().size(), String.valueOf(ig.getViolations()));
        RuleExecutionResult authored = RuleRunnerCalls
                .execute(load(head + ",\"Supp_Keys\":[{\"left\":\"DOMAIN\",\"right\":\"RDOMAIN\"},"
                        + "\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]" + tail), ae, r);
        assertEquals(RuleExecutionStatus.EXECUTED, authored.getStatus(), String.valueOf(authored));
        assertEquals(2, authored.getViolations().size(), "subject 01's two AE records");
    }
}
