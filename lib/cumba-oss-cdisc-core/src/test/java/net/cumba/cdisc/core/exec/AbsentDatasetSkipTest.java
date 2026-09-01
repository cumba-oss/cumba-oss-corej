package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code Fix #222} — step 3 of {@code plans/PLAN-absent-required-dataset-skip.md}: an absent
 * dataset whose absence the run already reports must SILENCE its dependants, not flood them.
 *
 * <p>
 * Every behavioural case runs through {@link RuleRunner#execute} (per {@code EC-45}: a probe that
 * does not run through {@code RuleRunner} proves nothing about shipped behaviour), and every one is
 * paired with its own control — the same rule, same data, with the dataset <em>not</em> covered by
 * a presence rule — so a test that passed with the mechanism removed would be visible immediately.
 * </p>
 */
class AbsentDatasetSkipTest
{

    // --------------------------------------------------------------------------------- fixtures

    private static IDataTable ds(String name, String... cols)
    {
        MockTable mt = MockTable.of().name(name);
        for (String c : cols)
        {
            mt.col(c, "1");
        }
        if (cols.length == 0)
        {
            mt.col("X", "1");
        }
        return mt.build();
    }


    private static DatasetResolver resolverOf(Map<String, IDataTable> inventory)
    {
        return inventory::get;
    }


    /** Loads one rule body as the sole rule of a package (so {@code checkExpr} is retained). */
    private static Rule load(String ruleBody) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + ruleBody + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    private static RuleExecutionResult run(Rule rule, IDataTable primary, DatasetResolver resolver,
            Set<String> reported)
    {
        return RuleRunner.execute(rule, primary, resolver, primary.getMetaData().getName(), null,
                null, null, Integer.MAX_VALUE, null, null, null, reported);
    }


    /** {@code not ds_exists("<D>")} — the shipped shape of every bare presence rule. */
    private static String presenceRule(String id, String dataset)
    {
        return "{\"Core\":{\"Id\":\"" + id + "\"},"
                + "\"Sensitivity\":\"Study\",\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
                + "\"Check\":{\"expression\":\"not ds_exists(\\\"" + dataset + "\\\")\"},"
                + "\"Outcome\":{\"Message\":\"" + dataset + " is not present\"}}";
    }

    // ----------------------------------------------------- K5: the precondition is DERIVED


    @Test
    void coverageIsDerivedFromTheRunsOwnRules() throws Exception
    {
        Rule dmPresence = load(presenceRule("CDISC-CG0368", "DM"));
        Rule tsPresence = load(presenceRule("CDISC-CG9667", "TS"));
        assertEquals(Set.of("DM", "TS"),
                AbsentDatasetSkip.reportedDatasets(List.of(dmPresence, tsPresence)));
        // ⚠ and it is a property of the RUN: drop the presence rule and the coverage goes with it,
        // so an --include-rules run can never silence a dependant nothing reports.
        assertEquals(Set.of("DM"), AbsentDatasetSkip.reportedDatasets(List.of(dmPresence)));
        assertEquals(Set.of(), AbsentDatasetSkip.reportedDatasets(List.of()));
    }


    @Test
    void polarityIsRespected_aProhibitionIsNotAPresenceRule() throws Exception
    {
        // CDISC-CG0647 / CORE-000042 shape: a bare ds_exists fires when the dataset IS present.
        // It reports nothing about absence; counting it would silence dependants on nothing.
        Rule prohibition = load("{\"Core\":{\"Id\":\"CDISC-CG0647\"},"
                + "\"Sensitivity\":\"Study\"," + "\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
                + "\"Check\":{\"expression\":\"ds_exists(\\\"TT\\\")\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}");
        assertNull(AbsentDatasetSkip.barePresenceDataset(prohibition));
        assertEquals(Set.of(), AbsentDatasetSkip.reportedDatasets(List.of(prohibition)));
    }


    @Test
    void conditionalPresenceRulesDoNotCount() throws Exception
    {
        // CG0407 shape: "EX must exist, but only when TA exists". That does NOT guarantee the run
        // reports EX's absence, so it cannot satisfy the precondition.
        Rule conditional = load("{\"Core\":{\"Id\":\"CDISC-CG0407\"},"
                + "\"Sensitivity\":\"Study\"," + "\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
                + "\"Check\":{\"expression\":\"ds_exists(\\\"TA\\\") and not ds_exists(\\\"EX\\\")\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}");
        assertNull(AbsentDatasetSkip.barePresenceDataset(conditional));
    }


    @Test
    void aScopedPresenceRuleDoesNotCount() throws Exception
    {
        // Scoped to one domain ⇒ it only runs when that domain exists ⇒ its verdict is not a
        // study-wide guarantee.
        Rule scoped = load("{\"Core\":{\"Id\":\"X\"},"
                + "\"Sensitivity\":\"Dataset\",\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Check\":{\"expression\":\"not ds_exists(\\\"DM\\\")\"},"
                + "\"Outcome\":{\"Message\":\"m\"}}");
        assertNull(AbsentDatasetSkip.barePresenceDataset(scoped));
    }

    // ------------------------------------------- the headline: SKIPPED, never a silent pass

    /** {@code PMDA-AD0204}'s shape — the unguarded twin that floods on an absent DM. */
    private static final String DM_DEPENDENT = "{\"Core\":{\"Id\":\"R1\"},"
            + "\"Sensitivity\":\"Record\"," + "\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
            + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}],"
            + "\"Check\":{\"expression\":\"not empty(AGE) and AGE != DM.AGE\"},"
            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AGE\"]}}";

    @Test
    void absentAndReported_collapsesToSkipped_notToASilentPass() throws Exception
    {
        Rule rule = load(DM_DEPENDENT);
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1", "S2").col("AGE", "31", "32")
                .build();
        DatasetResolver noDm = resolverOf(Map.of("AE", ae));

        // CONTROL — DM absent and NOT reported by any presence rule: today's behaviour, a flood.
        RuleExecutionResult before = run(rule, ae, noDm, Set.of());
        assertEquals(RuleExecutionStatus.EXECUTED, before.getStatus());
        assertEquals(2, before.getViolations().size(),
                "control: an absent DM makes DM.AGE all-missing, so every row fires");

        // Fix #222 — DM's absence IS reported: the dependant is silenced, and it says so.
        RuleExecutionResult after = run(rule, ae, noDm, Set.of("DM"));
        assertEquals(RuleExecutionStatus.SKIPPED, after.getStatus());
        assertEquals(0, after.getViolations().size());
        assertNotNull(after.getStatusMessage());
        assertTrue(after.getStatusMessage().contains("DM"),
                "the skip reason must name the responsible dataset: " + after.getStatusMessage());
    }


    @Test
    void presentDataset_isNeverSuppressed() throws Exception
    {
        Rule rule = load(DM_DEPENDENT);
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").col("AGE", "31").build();
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S1").col("AGE", "99").build();
        // DM is covered by a presence rule AND present ⇒ the rule evaluates exactly as before.
        RuleExecutionResult r = run(rule, ae, resolverOf(Map.of("AE", ae, "DM", dm)), Set.of("DM"));
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus());
        assertEquals(1, r.getViolations().size(), "31 != 99 is a genuine finding");
    }

    // ----------------------------------------------- K5b: scoped to the DEPENDENCY, not the rule

    /**
     * The {@code CDISC-CG0007} / {@code FDA-SD1085} / {@code CORE-000138} shape:
     * {@code all[ any[ <local> , <reads DM> ] , <local> ]}. Rule-granular SKIP would delete the
     * purely-local finding — 25 rules / 27 (rule, dataset) pairs in Population B, 18 of the pairs
     * on DM.
     */
    private static final String LOCAL_SIBLING = "{\"Core\":{\"Id\":\"R1\"},"
            + "\"Sensitivity\":\"Record\"," + "\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
            + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}],"
            + "\"Check\":{\"expression\":"
            + "\"(FLAG == \\\"X\\\" or empty(DM.RFSTDTC)) and not empty(DY)\"},"
            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"DY\"]}}";

    @Test
    void siblingBranchThatDoesNotReadTheAbsentDatasetStillEvaluates() throws Exception
    {
        Rule rule = load(LOCAL_SIBLING);
        // row 0 fires on the LOCAL disjunct; rows 1/2 only ever fire through the DM disjunct.
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1", "S2", "S3")
                .col("FLAG", "X", "Z", "Z").col("DY", "1", "1", "").build();
        DatasetResolver noDm = resolverOf(Map.of("AE", ae));

        // CONTROL — uncovered DM: empty(DM.RFSTDTC) is true for every row, so the DM disjunct
        // floods rows 0 AND 1.
        RuleExecutionResult before = run(rule, ae, noDm, Set.of());
        assertEquals(List.of(0L, 1L), rows(before));

        // Fix #222 — only the DM disjunct is suppressed. The flood on row 1 goes; the local
        // finding on row 0 SURVIVES, and the rule is NOT skipped.
        RuleExecutionResult after = run(rule, ae, noDm, Set.of("DM"));
        assertEquals(RuleExecutionStatus.EXECUTED, after.getStatus(),
                "a rule with a surviving local branch must not report SKIPPED");
        assertEquals(List.of(0L), rows(after),
                "the purely-local finding must survive — rule-granular SKIP would delete it");
    }


    private static List<Long> rows(RuleExecutionResult result)
    {
        // getRow() is the 0-based row index (getRowNumber() is its 1-based report form).
        return result.getViolations().stream().map(Violation::getRow).sorted().toList();
    }

    // ------------------------------------------------ Fix #358 (D7): split domains are PRESENT


    /** A record rule whose whole Check depends on the joined LB — the SKIP candidate shape. */
    private static String lbJoinRule(String id)
    {
        return "{\"Core\":{\"Id\":\"" + id + "\"},"
                + "\"Sensitivity\":\"Record\",\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
                + "\"Match_Datasets\":[{\"Name\":\"LB\",\"Keys\":[\"USUBJID\",\"LBSEQ\"],"
                + "\"Join_Type\":\"left\"}]," + "\"Check\":{\"expression\":\"empty(LB.LBORRES)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}";
    }


    /**
     * A rule joining {@code LB} on a submission that ships LB <b>split</b> is NOT skipped: the join
     * now resolves the member union, so the dependency is satisfiable and silencing it would hide
     * real findings. ⚠ Built on a real {@code WithInventory} fixture ({@link RealTables}) — this
     * class's {@code resolverOf} is a plain lambda, through which the union branch is unreachable
     * and the case would be vacuous.
     */
    @Test
    void splitDomainCountsAsPresent_ruleIsNotSkipped() throws Exception
    {
        Rule rule = load(lbJoinRule("TEST-SPLIT-PRESENT"));
        IDataTable adlb = RealTables.of("ADLB").str("USUBJID", "U1", "U3").str("LBSEQ", "1", "7")
                .build();
        IDataTable lbch = RealTables.of("lbch").str("DOMAIN", "LB").str("USUBJID", "U1")
                .str("LBSEQ", "1").str("LBORRES", "res-ch").build();
        IDataTable lbhe = RealTables.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "U2")
                .str("LBSEQ", "9").str("LBORRES", "res-he").build();

        RuleExecutionResult res = run(rule, adlb, RealTables.inventoryOf(adlb, lbch, lbhe),
                Set.of("LB"));
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(),
                "a split LB is present — the dependant must run against the union");
        // And it genuinely evaluated: U3's row has no LB match, so the empty() check fires there.
        assertEquals(List.of(1L), rows(res));
    }


    /**
     * Review F2: a rule that reads the split domain ONLY through a dotted Check reference (no
     * {@code Match_Datasets}) is runnable too — the dotted {@code var_exists} unions (Fix #358 site
     * 7) — so its presence test must be widened as well, or the rule would be silenced while the
     * (equally widened) presence rule no longer reports the domain at all.
     */
    @Test
    void dottedOnlyCandidate_onASplitDomain_isNotSkipped() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"TEST-DOTTED-SPLIT\"},"
                + "\"Sensitivity\":\"Record\",\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
                + "\"Check\":{\"expression\":\"var_exists(LB.LBORRES) and empty(X)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}");
        IDataTable primary = RealTables.of("ADLB").str("USUBJID", "U1", "U2").str("X", "v", "")
                .build();
        IDataTable lbch = RealTables.of("lbch").str("DOMAIN", "LB").str("USUBJID", "U1")
                .str("LBORRES", "res-ch").build();
        IDataTable lbhe = RealTables.of("lbhe").str("DOMAIN", "LB").str("USUBJID", "U2")
                .str("LBORRES", "res-he").build();

        RuleExecutionResult res = run(rule, primary, RealTables.inventoryOf(primary, lbch, lbhe),
                Set.of("LB"));
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(),
                "a dotted-only reader of a split domain must run, not be silenced: "
                        + res.getStatusMessage());
        assertEquals(List.of(1L), rows(res), "var_exists(LB.LBORRES) is true via the union");

        // Control: LB truly absent -> the covered dependant still SKIPs.
        IDataTable dm = RealTables.of("DM").str("DOMAIN", "DM").str("USUBJID", "U1").build();
        RuleExecutionResult absent = run(rule, primary, RealTables.inventoryOf(primary, dm),
                Set.of("LB"));
        assertEquals(RuleExecutionStatus.SKIPPED, absent.getStatus());
    }


    /** Control: with no LB member at all, the covered dependant still SKIPs exactly as before. */
    @Test
    void trulyAbsentDomain_staysSkipped() throws Exception
    {
        Rule rule = load(lbJoinRule("TEST-SPLIT-ABSENT"));
        IDataTable adlb = RealTables.of("ADLB").str("USUBJID", "U1").str("LBSEQ", "1").build();
        IDataTable dm = RealTables.of("DM").str("DOMAIN", "DM").str("USUBJID", "U1").build();
        RuleExecutionResult res = run(rule, adlb, RealTables.inventoryOf(adlb, dm), Set.of("LB"));
        assertEquals(RuleExecutionStatus.SKIPPED, res.getStatus());
    }

    // ------------------------------------------------------------ K5c: the intent opt-out


    /**
     * {@code CDISC-SEND-0105}'s shape — <i>"SPECIES is empty in DM AND TS does not carry it"</i>.
     * TS's absence is one of the <b>disjuncts being tested</b>, not a precondition, so SKIP must
     * not apply however well-covered TS is.
     */
    private static String speciesRule(String id)
    {
        return "{\"Core\":{\"Id\":\"" + id + "\"},"
                + "\"Sensitivity\":\"Record\",\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
                + "\"Match_Datasets\":[{\"Name\":\"TS\",\"Keys\":[\"STUDYID\"]}],"
                + "\"Check\":{\"expression\":\"empty(SPECIES) and empty(TS.TSVAL)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"SPECIES\"]}}";
    }


    @Test
    void intentOptOutRulesAreNeverSilenced() throws Exception
    {
        IDataTable dm = MockTable.of().name("DM").col("STUDYID", "S").col("SPECIES", "").build();
        DatasetResolver noTs = resolverOf(Map.of("DM", dm));

        // A rule of the SAME SHAPE but outside the opt-out collapses, as it should.
        RuleExecutionResult other = run(load(speciesRule("SOME-OTHER-RULE")), dm, noTs,
                Set.of("TS"));
        assertEquals(RuleExecutionStatus.SKIPPED, other.getStatus());

        // ⚠ Each of the four opted-out ids keeps its finding.
        for (String id : AbsentDatasetSkip.INTENT_OPT_OUT_RULE_IDS)
        {
            RuleExecutionResult r = run(load(speciesRule(id)), dm, noTs, Set.of("TS"));
            assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), id + " must not be skipped");
            assertEquals(1, r.getViolations().size(),
                    id + " tests TS's absence — silencing it destroys the finding");
        }
    }


    @Test
    void theOptOutPopulationIsTheFourRulesStep1Derived()
    {
        // Pinned from Fix #207's derivation (documentation/derivation/intent-absence-opt-out.tsv).
        // Widening this set silently exempts rules nobody adjudicated, so it is asserted verbatim.
        assertEquals(Set.of("CDISC-SEND-0105", "CDISC-SEND-0105.1", "CDISC-SEND-0106",
                "CDISC-SEND-0106.1"), AbsentDatasetSkip.INTENT_OPT_OUT_RULE_IDS);
    }

    // -------------------------------------------------------- the mechanism cannot eat itself


    @Test
    void aPresenceRuleStillFiresOnItsOwnAbsentDataset() throws Exception
    {
        // ds_exists is the question, never a "reading" — suppressing it would make the guarantee
        // silence the very rule it rests on.
        Rule presence = load(presenceRule("CDISC-CG0368", "DM"));
        IDataTable ae = ds("AE");
        RuleExecutionResult r = run(presence, ae, resolverOf(Map.of("AE", ae)), Set.of("DM"));
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus());
        assertEquals(1, r.getViolations().size(), "the presence rule must still report DM absent");
    }


    @Test
    void aRuleNeverSuppressesReadingsOfTheDatasetItRunsAgainst() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Record\",\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
                + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}],"
                + "\"Check\":{\"expression\":\"not empty(AGE) and AGE != DM.AGE\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AGE\"]}}");
        // The primary IS DM, so DM cannot be "absent" here whatever the resolver says.
        AbsentDatasetSkip.Decision d = AbsentDatasetSkip.decide(rule, _ -> null, Set.of("DM"), "DM",
                "DM");
        assertFalse(d.applies());
    }


    @Test
    void aPresenceTestInsideABranchIsNeverSuppressedEither() throws Exception
    {
        // The narrower half of the same guard, and the one that needs a rule to reach the dataset
        // BOTH ways: `any[ ds_not_exists(EX) , <reads EX.EXDOSE> ]`. Suppressing the second
        // disjunct is right — it cannot be assessed. Suppressing the FIRST would invert the rule's
        // meaning, because ds_not_exists is the question, not a reading, and the whole rule would
        // collapse to SKIPPED on exactly the input it exists to report.
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Record\",\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
                + "\"Match_Datasets\":[{\"Name\":\"EX\",\"Keys\":[\"USUBJID\"]}],"
                + "\"Check\":{\"expression\":"
                + "\"not ds_exists(\\\"EX\\\") or not empty(EX.EXDOSE)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}");
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").build();
        RuleExecutionResult r = run(rule, ae, resolverOf(Map.of("AE", ae)), Set.of("EX"));
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(),
                "the ds_not_exists disjunct survives, so the rule is not skipped");
        assertEquals(1, r.getViolations().size(), "and it still reports that EX is absent");
    }

    // ------------------------------------------------ the operation-domain arm (~80 % of deps)


    @Test
    void anOperationPinnedToAnAbsentReportedDomainIsSuppressed() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Record\",\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
                + "\"Operations\":[{\"id\":\"$ta_armcd\",\"operator\":\"distinct\","
                + "\"name\":\"ARMCD\",\"domain\":\"TA\"}],"
                + "\"Check\":{\"expression\":\"ARMCD not in $ta_armcd\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"ARMCD\"]}}");
        IDataTable dm = MockTable.of().name("DM").col("ARMCD", "A", "B").build();
        DatasetResolver noTa = resolverOf(Map.of("DM", dm));

        // CONTROL — uncovered TA: the reference set is empty, so every row "does not match".
        assertEquals(2, run(rule, dm, noTa, Set.of()).getViolations().size());

        // Fix #222 — TA's absence is reported, so the mismatch nobody could assess is silenced.
        RuleExecutionResult after = run(rule, dm, noTa, Set.of("TA"));
        assertEquals(RuleExecutionStatus.SKIPPED, after.getStatus());
        assertTrue(after.getStatusMessage() != null && after.getStatusMessage().contains("TA"));
    }

    // ---------------------------------------------------------------- the rewrite, in isolation


    @Test
    void notIsOpaque_anUnevaluableNegationIsFalseNotTrue() throws Exception
    {
        // `not <reads DM>` must suppress to FALSE. Descending into the `not` and folding its
        // operand to false would invert it to TRUE — a fabricated finding on an absent dataset.
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Record\",\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
                + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}],"
                + "\"Check\":{\"expression\":\"not empty(DM.RFSTDTC)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}");
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1", "S2").build();
        RuleExecutionResult r = run(rule, ae, resolverOf(Map.of("AE", ae)), Set.of("DM"));
        assertEquals(RuleExecutionStatus.SKIPPED, r.getStatus());
        assertEquals(0, r.getViolations().size());
    }


    @Test
    void anUncoveredDatasetChangesNothingAtAll() throws Exception
    {
        // The precondition is the whole design: a dataset whose absence NOTHING reports must keep
        // today's behaviour, whatever it is. (§0.5 stratum C — every intent case sits here.)
        Rule rule = load(DM_DEPENDENT);
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").col("AGE", "31").build();
        AbsentDatasetSkip.Decision d = AbsentDatasetSkip.decide(rule, resolverOf(Map.of("AE", ae)),
                Set.of("TS", "EX"), "AE", "AE");
        assertFalse(d.applies());
        assertEquals(List.of(), d.suppressedDatasets());
    }


    @Test
    void declaredButUnreadDatasetsAreNotSuppressed() throws Exception
    {
        // A Match_Datasets join the Check never dereferences names a dataset but reads nothing
        // from it — there is no leaf to silence, so the rule must run untouched.
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Record\",\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
                + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}],"
                + "\"Check\":{\"expression\":\"not empty(AGE)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AGE\"]}}");
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").col("AGE", "31").build();
        AbsentDatasetSkip.Decision d = AbsentDatasetSkip.decide(rule, resolverOf(Map.of("AE", ae)),
                Set.of("DM"), "AE", "AE");
        assertFalse(d.applies());
        assertEquals(1,
                run(rule, ae, resolverOf(Map.of("AE", ae)), Set.of("DM")).getViolations().size());
    }


    @Test
    void everySuppressedDatasetIsNamedInTheSkipReason() throws Exception
    {
        Rule rule = load("{\"Core\":{\"Id\":\"R1\"},"
                + "\"Sensitivity\":\"Record\",\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}},"
                + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]},"
                + "{\"Name\":\"TS\",\"Keys\":[\"STUDYID\"]}],"
                + "\"Check\":{\"expression\":\"empty(DM.AGE) and empty(TS.TSVAL)\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}");
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").col("STUDYID", "S").build();
        RuleExecutionResult r = run(rule, ae, resolverOf(Map.of("AE", ae)), Set.of("DM", "TS"));
        assertEquals(RuleExecutionStatus.SKIPPED, r.getStatus());
        String reason = r.getStatusMessage();
        assertNotNull(reason);
        assertTrue(reason.contains("DM") && reason.contains("TS"),
                "both responsible datasets must be named: " + reason);
    }


    @Test
    void anEmptyCoverageSetIsBehaviourIdenticalToThePreFixEngine() throws Exception
    {
        Rule rule = load(DM_DEPENDENT);
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1", "S2").col("AGE", "31", "32")
                .build();
        DatasetResolver noDm = resolverOf(Map.of("AE", ae));
        Map<Long, Map<String, String>> withCoverage = valuesOf(run(rule, ae, noDm, Set.of()));
        // The 11-argument overload (every pre-Fix #222 caller) must route to the same behaviour.
        Map<Long, Map<String, String>> legacyOverload = valuesOf(RuleRunner.execute(rule, ae, noDm,
                "AE", null, null, null, Integer.MAX_VALUE, null, null, null));
        assertEquals(legacyOverload, withCoverage);
        assertEquals(2, withCoverage.size());
    }


    private static Map<Long, Map<String, String>> valuesOf(RuleExecutionResult result)
    {
        Map<Long, Map<String, String>> out = new LinkedHashMap<>();
        for (Violation v : result.getViolations())
        {
            out.put(v.getRowNumber(), v.getValues());
        }
        return out;
    }

}
