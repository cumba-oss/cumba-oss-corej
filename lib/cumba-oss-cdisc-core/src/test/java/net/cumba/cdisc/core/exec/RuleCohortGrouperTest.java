package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.List;
import net.cumba.cdisc.core.exec.RuleCohortGrouper.CohortKey;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.MatchDataset;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Tests that:
 *
 * <ul>
 * <li>The cohort eligibility predicate accepts/rejects rules per the documented criteria.</li>
 * <li>For an eligible cohort, {@link CohortRunner} produces the SAME findings as
 * {@link RuleRunner#execute} would for each rule run individually — byte-for-byte equivalence (the
 * parity contract).</li>
 * </ul>
 *
 * The second test is the load-bearing one: any future change to the engine's equality semantics
 * that isn't mirrored in {@code CohortRunner} will surface here as mismatched violation counts or
 * values.
 */
class RuleCohortGrouperTest
{

    // ------------------------------------------------------------------
    // Eligibility tests
    // ------------------------------------------------------------------

    @Test
    void rule_withSingleEqualityLeaf_andADSLJoin_isCohortEligible()
    {
        Rule r = cdiscAd0591("CDISC-AD0591-ADLB-TRTSDT", "TRTSDT", "ADSL.TRTSDT");
        CohortKey key = RuleCohortGrouper.cohortKey(r);
        assertNotNull(key, "single-leaf not_equal_to vs ADSL.<col> should be cohort-eligible");
    }


    @Test
    void rule_withLoadError_isNotCohortable()
    {
        // A cohort-eligible rule carrying a load error must be demoted to a singleton so the
        // load-error -> ERROR short-circuit in RuleRunner fires (it would otherwise be batched and
        // reported EXECUTED).
        Rule r = cdiscAd0591("CDISC-AD0591-ADLB-TRTSDT", "TRTSDT", "ADSL.TRTSDT");
        assertNotNull(RuleCohortGrouper.cohortKey(r));
        r.setLoadError("var_role is not available at the DATA level (rule is invalid)");
        assertEquals(null, RuleCohortGrouper.cohortKey(r));
    }


    @Test
    void twoSimilarRules_clusterIntoSameCohort()
    {
        Rule a = cdiscAd0591("CDISC-AD0591-ADLB-TRTSDT", "TRTSDT", "ADSL.TRTSDT");
        Rule b = cdiscAd0591("CDISC-AD0591-ADLB-AGE", "AGE", "ADSL.AGE");
        assertEquals(RuleCohortGrouper.cohortKey(a), RuleCohortGrouper.cohortKey(b));
    }


    @Test
    void differentOperators_doNotCluster()
    {
        Rule a = cdiscAd0591("X", "TRTSDT", "ADSL.TRTSDT");
        Rule b = cdiscAd0591("Y", "TRTSDT", "ADSL.TRTSDT");
        // Tweak b to use equal_to instead of not_equal_to.
        b.getCheck();
        b = ruleWithLeaf("Y", buildLeaf("TRTSDT", "equal_to", "ADSL.TRTSDT"),
                List.of(matchDataset("ADSL", List.of("USUBJID"))));
        assertNotEquals(RuleCohortGrouper.cohortKey(a), RuleCohortGrouper.cohortKey(b));
    }


    @Test
    void ruleWithOperations_isIneligible()
    {
        Rule r = cdiscAd0591("X", "TRTSDT", "ADSL.TRTSDT");
        Operation op = new Operation();
        op.setId("$x");
        op.setOperator("record_count");
        r.setOperations(List.of(op));
        assertEquals(null, RuleCohortGrouper.cohortKey(r));
    }


    @Test
    void ruleWithLiteralValue_isIneligible()
    {
        // value_is_literal=true means the value is a string literal, not a foreign-dataset ref.
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("TRTSDT")
                .operator("not_equal_to").value(TextNode.valueOf("PLACEBO")).valueIsLiteral(true)
                .build();
        Rule r = ruleWithLeaf("X", leaf, List.of(matchDataset("ADSL", List.of("USUBJID"))));
        assertEquals(null, RuleCohortGrouper.cohortKey(r));
    }


    @Test
    void ruleWithRegexModifier_isIneligible()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("TRTSDT")
                .operator("not_equal_to").value(TextNode.valueOf("ADSL.TRTSDT")).regex(".*")
                .build();
        Rule r = ruleWithLeaf("X", leaf, List.of(matchDataset("ADSL", List.of("USUBJID"))));
        assertEquals(null, RuleCohortGrouper.cohortKey(r));
    }


    @Test
    void ruleWithoutMatchDatasets_isIneligible()
    {
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("SEX").operator("not_equal_to")
                .value(TextNode.valueOf("ADSL.SEX")).build();
        Rule r = ruleWithLeaf("X", leaf, List.of());
        assertEquals(null, RuleCohortGrouper.cohortKey(r));
    }


    @Test
    void ruleWithLiteralStringForValue_isIneligible()
    {
        // No dot in value → can't be a foreign-dataset ref.
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("TRTSDT")
                .operator("not_equal_to").value(TextNode.valueOf("PLACEBO")).build();
        Rule r = ruleWithLeaf("X", leaf, List.of(matchDataset("ADSL", List.of("USUBJID"))));
        assertEquals(null, RuleCohortGrouper.cohortKey(r));
    }

    // ------------------------------------------------------------------
    // EC-74 / Fix #233 — WHICH production path an equality rule arrives by decides whether the
    // equality cohort can fire at all. Both halves are pinned here because the pair is the
    // finding: neither assertion means anything without the other.
    // ------------------------------------------------------------------


    /**
     * Half 1 — a rule that arrives through {@link net.cumba.cdisc.core.RulePackageLoader} can never
     * be equality-cohorted. {@code normalizeJoinTypes} stamps {@code "inner"} onto every
     * {@code Match_Datasets} entry that omits {@code Join_Type}, and {@code equalityCohortKey}
     * rejects any non-null {@code Join_Type}.
     *
     * <p>
     * ⚠ The rule is built as JSON and driven through {@code loadFromString} — i.e. the REAL load
     * pipeline, not a hand-built stand-in. Asserting this on a hand-built rule would reproduce
     * exactly the blindness this test exists to remove: every other eligibility test in this class
     * bypasses the loader, which is why the stamping went unnoticed. The paired control below fixes
     * the shape so the only difference between the two is the loader.
     * </p>
     */
    @Test
    void equalityRuleLoadedThroughTheLoader_isNotCohortable_becauseJoinTypeIsStamped()
        throws Exception
    {
        String pkg = """
                {"rules":{"x":{"Core":{"Id":"T-XDVAL"},\
                "Sensitivity":"Record",\
                "Match_Datasets":[{"Name":"ADSL","Keys":["USUBJID"]}],\
                "Outcome":{"Message":"m","Output_Variables":["USUBJID","AGE"]},\
                "Check":{"all":[{"name":"AGE","operator":"not_equal_to","value":"ADSL.AGE"}]}}}}""";
        Rule loaded = net.cumba.cdisc.core.RulePackageLoader.loadFromString(pkg).getRules()
                .get("x");
        assertNotNull(loaded);
        assertNull(loaded.getLoadError(),
                "the fixture must load cleanly or the test proves nothing");

        List<MatchDataset> mds = loaded.getMatchDatasets();
        assertNotNull(mds);
        assertEquals(1, mds.size());
        assertEquals("inner", mds.get(0).getJoinType(),
                "the loader stamps inner onto an omitted Join_Type — if this ever changes, the "
                        + "assertion below is measuring nothing");

        assertNull(RuleCohortGrouper.cohortKey(loaded),
                "a loader-loaded equality rule cannot be cohorted: the stamped Join_Type is "
                        + "rejected by equalityCohortKey");
    }


    /**
     * Half 1's control — the byte-identical Check / Match_Datasets shape, built WITHOUT the loader,
     * IS cohort-eligible. This is what makes the assertion above non-vacuous: it isolates the
     * loader as the sole cause, rather than some unrelated defect in the fixture.
     */
    @Test
    void theSameShapeWithoutTheLoader_isCohortable_soTheLoaderIsTheSoleCause()
    {
        Rule handBuilt = cdiscAd0591("T-XDVAL", "AGE", "ADSL.AGE");
        assertNull(handBuilt.getMatchDatasets().get(0).getJoinType(),
                "the hand-built shape has no Join_Type — nothing normalised it");
        CohortKey key = RuleCohortGrouper.cohortKey(handBuilt);
        assertNotNull(key, "the identical shape IS eligible when it does not pass through the "
                + "loader — so the loader's stamping is the whole difference");
        assertEquals(RuleCohortGrouper.CohortKind.EQUALITY, key.kind());
    }


    /**
     * Half 2 — ⛔ the equality cohort is <b>NOT</b> dead in production.
     *
     * <p>
     * {@code RuleGenerator.generateCrossDatasetValueRules} builds the cross-dataset value family
     * fresh, per dataset, at validation time: one {@code not_equal_to(var, ADSL.var)} rule per
     * variable shared with ADSL, joined on USUBJID. Those rules never pass through
     * {@code RulePackageLoader}, so {@code normalizeJoinTypes} never runs on them and their
     * {@code Join_Type} stays {@code null} — which is exactly the state {@code equalityCohortKey}
     * requires. They all share one {@link CohortKey}, so they cluster into a single cohort of size
     * = (number of shared variables).
     * </p>
     *
     * <p>
     * This is the population the class javadoc's "CDISC-AD0591-&lt;dataset&gt;-&lt;column&gt;
     * family: TRTSDT vs ADSL.TRTSDT, AGE vs ADSL.AGE, …, joined by USUBJID" describes; it is
     * generated, not authored, which is why the shipped {@code rules/} corpus contains zero
     * equality-cohort-eligible rules while the optimisation still fires.
     * </p>
     */
    @Test
    void generatedCrossDatasetValueRules_bypassTheLoader_andDoCohort()
    {
        IDataTable adsl = MockTable.of().name("ADSL").col("STUDYID", "S1").col("USUBJID", "P1")
                .col("AGE", "40").col("SEX", "M").col("RACE", "WHITE").build();
        IDataTable adlb = MockTable.of().name("ADLB").col("STUDYID", "S1").col("USUBJID", "P1")
                .col("AGE", "41").col("SEX", "M").col("RACE", "ASIAN").build();

        net.cumba.cdisc.core.gen.RuleGenerator generator = new net.cumba.cdisc.core.gen.RuleGenerator(
                new EmptyMetadataProvider(), null, null, null,
                java.util.EnumSet.of(net.cumba.cdisc.core.gen.RuleCategory.CROSS_DATASET_METADATA));
        generator.setDomainName("ADLB");
        generator.setDatasetResolver(name -> "ADSL".equals(name) ? adsl : null);

        List<Rule> all = generator.generate(adlb).getRules();
        List<Rule> generated = all.stream().filter(r -> r.getCore() != null
                && r.getCore().getId() != null && r.getCore().getId().startsWith("CDISC-AD0591-"))
                .toList();
        assertEquals(3, generated.size(),
                "expected one CDISC-AD0591-<domain>-<var> rule per ADSL-shared non-key variable "
                        + "(AGE, SEX, RACE); all generated ids were "
                        + all.stream().map(r -> r.getCore() == null ? "?" : r.getCore().getId())
                                .toList());

        for (Rule r : generated)
        {
            List<MatchDataset> mds = r.getMatchDatasets();
            assertNotNull(mds, r.getCore().getId() + " must carry Match_Datasets");
            assertNull(mds.get(0).getJoinType(), r.getCore().getId()
                    + " must keep a null Join_Type — RuleGenerator never calls normalizeJoinTypes");
            CohortKey key = RuleCohortGrouper.cohortKey(r);
            assertNotNull(key, r.getCore().getId() + " must be equality-cohort eligible");
            assertEquals(RuleCohortGrouper.CohortKind.EQUALITY, key.kind());
        }

        // …and they cluster: the whole family collapses into ONE cohort, which is the optimisation.
        List<List<Rule>> groups = RuleCohortGrouper.group(generated);
        assertEquals(1, groups.size(), "every cross-dataset value rule shares one CohortKey");
        assertEquals(generated.size(), groups.get(0).size());
    }

    /**
     * Minimal {@link MetadataProvider} — every lookup empty, so only the cross-dataset value
     * generator produces anything.
     */
    private static final class EmptyMetadataProvider implements MetadataProvider
    {

        @Override
        public List<String> getRequiredVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getExpectedVariables(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public List<String> getModelColumnOrder(String domain)
        {
            return List.of();
        }


        @Override
        public boolean isDomainCustom(String domain)
        {
            return false;
        }


        @Override
        public List<String> getCodelistTerms(String codelistCode)
        {
            return List.of();
        }


        @Override
        public java.util.Map<String, String> getVariableMetadata(String domain, String variable)
        {
            return java.util.Map.of();
        }


        @Override
        public List<java.util.Map<String, String>> getDomainVariables(String domain)
        {
            return List.of();
        }


        @Override
        public java.util.Map<String, String> getDatasetMetadata(String domain)
        {
            return java.util.Map.of();
        }


        @Override
        public boolean isCodelistExtensible(String codelistName)
        {
            return false;
        }


        @Override
        public java.util.Map<String, String> getCodelistTermMappings(String codelistName)
        {
            return java.util.Map.of();
        }


        @Override
        public String getStandard()
        {
            return "adamig";
        }


        @Override
        public String getVersion()
        {
            return "1.3";
        }
    }

    @Test
    void groupOrderPreservedAndSingletonsKeptInPlace()
    {
        Rule cohortA = cdiscAd0591("CA1", "TRTSDT", "ADSL.TRTSDT");
        Rule singleton = nonCohortRule("S");
        Rule cohortB = cdiscAd0591("CA2", "AGE", "ADSL.AGE");
        Rule cohortC = cdiscAd0591("CA3", "RACE", "ADSL.RACE");

        List<List<Rule>> groups = RuleCohortGrouper
                .group(List.of(cohortA, singleton, cohortB, cohortC));
        // First emit: cohortA group (which will include cohortB and cohortC since they share
        // the same key). Then singleton.
        assertEquals(2, groups.size(), "expected two groups");
        assertEquals(3, groups.get(0).size(),
                "cohort group should contain all three eligible rules");
        assertEquals(List.of("CA1", "CA2", "CA3"), groups.get(0).stream().map(Rule::getId).toList(),
                "cohort group should preserve input order");
        assertEquals(1, groups.get(1).size());
        assertEquals("S", groups.get(1).get(0).getId());
    }

    // ------------------------------------------------------------------
    // Column-presence pre-screen (option A — drop offenders before cohort build)
    // ------------------------------------------------------------------


    @Test
    void group_demotesEqualityRule_whenPrimaryColumnAbsentFromMeta()
    {
        // Three rules clustering on (ADSL.<col>, equal_to). The dataset only has TRTSDT;
        // AECONTRT and AGE are missing. With meta passed in, only TRTSDT survives in the cohort
        // — the other two land as singletons so the per-rule path's column-presence folding
        // (Fix #40) handles them without the cohort runner's hard-throw.
        IDataTable adlb = MockTable.of().name("ADLB").col("USUBJID", "S1")
                .col("TRTSDT", "2020-01-01").build();

        Rule rTrtsdt = cdiscAd0591("R-TRTSDT", "TRTSDT", "ADSL.TRTSDT");
        Rule rAecontrt = cdiscAd0591("R-AECONTRT", "AECONTRT", "ADSL.AECONTRT");
        Rule rAge = cdiscAd0591("R-AGE", "AGE", "ADSL.AGE");

        List<List<Rule>> groups = RuleCohortGrouper.group(List.of(rTrtsdt, rAecontrt, rAge),
                adlb.getMetaData());

        // Expected: 1 cohort group (just TRTSDT — sized 1 because nothing else clustered),
        // plus 2 singletons. Order preserved per first-appearance.
        assertEquals(3, groups.size(),
                "TRTSDT clusters alone; AECONTRT and AGE demote to singletons");
        assertEquals(List.of("R-TRTSDT"), groups.get(0).stream().map(Rule::getId).toList());
        assertEquals(List.of("R-AECONTRT"), groups.get(1).stream().map(Rule::getId).toList());
        assertEquals(List.of("R-AGE"), groups.get(2).stream().map(Rule::getId).toList());
    }


    @Test
    void group_demotesMembershipRule_whenPrimaryColumnAbsentFromMeta()
    {
        // Same screen for membership-shape rules: VISITNUM is present, AECONTRT isn't.
        IDataTable adlb = MockTable.of().name("ADLB").col("USUBJID", "S1").col("VISITNUM", "1")
                .build();

        Rule rPresent = genCl("GEN-CL-ADLB-VISITNUM", "VISITNUM", "1", "2", "3");
        Rule rAbsent = genCl("GEN-CL-ADLB-AECONTRT", "AECONTRT", "Y", "N");

        List<List<Rule>> groups = RuleCohortGrouper.group(List.of(rPresent, rAbsent),
                adlb.getMetaData());

        assertEquals(2, groups.size());
        assertEquals(List.of("GEN-CL-ADLB-VISITNUM"),
                groups.get(0).stream().map(Rule::getId).toList());
        assertEquals(List.of("GEN-CL-ADLB-AECONTRT"),
                groups.get(1).stream().map(Rule::getId).toList());
    }


    @Test
    void group_unaffectedWhenMetaIsNull()
    {
        // Back-compat: no meta passed → pre-screen disabled → identical to legacy 1-arg call.
        Rule rTrtsdt = cdiscAd0591("R-TRTSDT", "TRTSDT", "ADSL.TRTSDT");
        Rule rAecontrt = cdiscAd0591("R-AECONTRT", "AECONTRT", "ADSL.AECONTRT");

        List<List<Rule>> withMeta = RuleCohortGrouper.group(List.of(rTrtsdt, rAecontrt), null);
        List<List<Rule>> legacy = RuleCohortGrouper.group(List.of(rTrtsdt, rAecontrt));

        // Both rules cluster (the legacy path has no meta to consult).
        assertEquals(legacy.size(), withMeta.size(),
                "null-meta overload must match 1-arg overload");
        assertEquals(1, withMeta.size(), "without column screening, both rules cluster");
        assertEquals(2, withMeta.get(0).size(),
                "without column screening, both rules cluster regardless of dataset shape");
    }


    @Test
    void group_keepsCohortIntactWhenAllPrimaryColumnsPresent()
    {
        // Negative regression — column screening must not split a cohort whose every rule
        // does have its primary column on the dataset.
        IDataTable adlb = MockTable.of().name("ADLB").col("USUBJID", "S1").col("TRTSDT", "x")
                .col("AGE", "45").build();

        Rule rTrtsdt = cdiscAd0591("R-TRTSDT", "TRTSDT", "ADSL.TRTSDT");
        Rule rAge = cdiscAd0591("R-AGE", "AGE", "ADSL.AGE");

        List<List<Rule>> groups = RuleCohortGrouper.group(List.of(rTrtsdt, rAge),
                adlb.getMetaData());

        assertEquals(1, groups.size(), "all primary columns present → cohort intact");
        assertEquals(2, groups.get(0).size());
    }

    // ------------------------------------------------------------------
    // Parity test — the load-bearing contract
    // ------------------------------------------------------------------


    @Test
    void cohortAndPerRuleProduceIdenticalResults()
    {
        // Build a primary BDS-like table (ADLB) with mixed values. Some rows match ADSL, some
        // don't. The fixture deliberately covers: equal, not equal, missing on primary, missing
        // on ADSL, both missing — so parity covers every branch in evalEquality.
        IDataTable adlb = MockTable.of().name("ADLB")
                .col("USUBJID", "S1", "S2", "S3", "S4", "S5", "S6")
                .col("TRTSDT", "2020-01-01", "2020-01-01", "2020-02-15", "", "2020-03-01", "")
                .col("AGE", "45", "67", "", "55", "32", "").build();
        IDataTable adsl = MockTable.of().name("ADSL")
                .col("USUBJID", "S1", "S2", "S3", "S4", "S5", "S6")
                .col("TRTSDT", "2020-01-01", "2020-02-01", "2020-02-15", "2020-04-01", "", "")
                .col("AGE", "45", "67", "30", "55", "32", "").build();

        DatasetResolver resolver = name -> "ADSL".equalsIgnoreCase(name) ? adsl : null;

        // Two cohort-eligible rules (TRTSDT and AGE vs ADSL).
        Rule rTrtsdt = cdiscAd0591("X-TRTSDT", "TRTSDT", "ADSL.TRTSDT");
        Rule rAge = cdiscAd0591("X-AGE", "AGE", "ADSL.AGE");

        // Per-rule path — independent JoinCaches for absolute parity
        JoinCache jcA = new JoinCache(new JoinCache.SharedIndexCache());
        JoinCache jcB = new JoinCache(new JoinCache.SharedIndexCache());
        RuleExecutionResult perTrtsdt = RuleRunner.execute(rTrtsdt, adlb, resolver, "AD", null,
                jcA);
        RuleExecutionResult perAge = RuleRunner.execute(rAge, adlb, resolver, "AD", null, jcB);

        // Cohort path — shared JoinCache
        JoinCache jcCohort = new JoinCache(new JoinCache.SharedIndexCache());
        List<RuleExecutionResult> cohortResults = CohortRunner.executeCohort(List.of(rTrtsdt, rAge),
                adlb, resolver, "AD", null, jcCohort);

        assertEquals(2, cohortResults.size());
        assertResultsEqual(perTrtsdt, cohortResults.get(0), "TRTSDT");
        assertResultsEqual(perAge, cohortResults.get(1), "AGE");
    }


    /**
     * The same parity contract for rules shaped the way <em>shipped</em> rules are shaped:
     * {@code Core.Id} set, raw {@code id} absent.
     *
     * <p>
     * The fixture above assigns {@code id} and {@code Core.Id} the same string, so the two id
     * sources agree and a divergence between them is invisible. Production rules carry no
     * {@code id} at all — the cohort path used to read it raw and stamped {@code ruleId = null},
     * while the per-rule path resolved the {@code Core.Id}. Same rule, two different result ids,
     * decided purely by whether the rule happened to be batched.
     * </p>
     */
    @Test
    void cohortAndPerRuleAgreeOnRuleIdForCoreIdOnlyRules()
    {
        IDataTable adlb = MockTable.of().name("ADLB").col("USUBJID", "S1", "S2")
                .col("TRTSDT", "2020-01-01", "2020-01-01").col("AGE", "45", "67").build();
        IDataTable adsl = MockTable.of().name("ADSL").col("USUBJID", "S1", "S2")
                .col("TRTSDT", "2020-01-01", "2020-02-01").col("AGE", "45", "30").build();
        DatasetResolver resolver = name -> "ADSL".equalsIgnoreCase(name) ? adsl : null;

        Rule rTrtsdt = coreIdOnlyAd0591("CDISC-AD0591-TRTSDT", "TRTSDT", "ADSL.TRTSDT");
        Rule rAge = coreIdOnlyAd0591("CDISC-AD0591-AGE", "AGE", "ADSL.AGE");
        assertNull(rTrtsdt.getId(), "precondition: shipped rules carry no raw id");

        RuleExecutionResult perTrtsdt = RuleRunner.execute(rTrtsdt, adlb, resolver, "AD", null,
                new JoinCache(new JoinCache.SharedIndexCache()));
        RuleExecutionResult perAge = RuleRunner.execute(rAge, adlb, resolver, "AD", null,
                new JoinCache(new JoinCache.SharedIndexCache()));

        List<RuleExecutionResult> cohortResults = CohortRunner.executeCohort(List.of(rTrtsdt, rAge),
                adlb, resolver, "AD", null, new JoinCache(new JoinCache.SharedIndexCache()));

        assertEquals("CDISC-AD0591-TRTSDT", cohortResults.get(0).getRuleId(),
                "the cohort path must stamp the rule's stable identity, not a null raw id");
        assertResultsEqual(perTrtsdt, cohortResults.get(0), "TRTSDT (Core.Id only)");
        assertResultsEqual(perAge, cohortResults.get(1), "AGE (Core.Id only)");
    }


    /** {@link #cdiscAd0591} but with {@code Core.Id} only — no raw {@code id}, as shipped. */
    private static Rule coreIdOnlyAd0591(String coreId, String varName, String foreignRef)
    {
        Rule r = cdiscAd0591(coreId, varName, foreignRef);
        r.setId(null);
        return r;
    }


    @Test
    void cohortHandlesEmptyCohortAsEmptyResult()
    {
        IDataTable adlb = MockTable.of().name("ADLB").col("USUBJID", "S1").col("TRTSDT", "x")
                .build();
        DatasetResolver resolver = _ -> null;
        JoinCache jc = new JoinCache(new JoinCache.SharedIndexCache());
        List<RuleExecutionResult> r = CohortRunner.executeCohort(List.of(), adlb, resolver, "AD",
                null, jc);
        assertTrue(r.isEmpty());
    }

    // ------------------------------------------------------------------
    // Membership cohort (Phase 4) — eligibility and parity
    // ------------------------------------------------------------------


    @Test
    void membershipShape_isCohortEligible_andClustersWithSameShape()
    {
        Rule a = genCl("GEN-CL-ADLB-VISITNUM", "VISITNUM", "1", "2", "3");
        Rule b = genCl("GEN-CL-ADLB-AGEGR1N", "AGEGR1N", "1", "2", "3", "4");
        CohortKey ka = RuleCohortGrouper.cohortKey(a);
        CohortKey kb = RuleCohortGrouper.cohortKey(b);
        assertNotNull(ka);
        assertEquals(ka, kb,
                "Membership rules with same shape (different col + terms) should cluster");
    }


    @Test
    void membershipAndEqualityCohorts_neverMergeEvenWithSameOperatorString()
    {
        Rule eq = cdiscAd0591("eq", "TRTSDT", "ADSL.TRTSDT");
        Rule mem = genCl("mem", "VISITNUM", "1", "2");
        assertNotEquals(RuleCohortGrouper.cohortKey(eq), RuleCohortGrouper.cohortKey(mem),
                "EQUALITY and MEMBERSHIP cohorts should never share a key — different cohort kinds");
    }


    @Test
    void membershipShape_withMatchDatasets_isIneligible()
    {
        // Membership cohort is pure per-row; rules with Match_Datasets fall to the per-rule path.
        Rule r = genClWithMatchDataset("X", "VISITNUM", List.of("1", "2"));
        assertEquals(null, RuleCohortGrouper.cohortKey(r));
    }


    @Test
    void membershipShape_withDifferentColumnNames_isIneligible()
    {
        // Both leaves must reference the same column. RuleGenerator never emits otherwise, but
        // the predicate guards against malformed input.
        CheckConditionLeaf nonEmpty = CheckConditionLeaf.builder().name("VISITNUM")
                .operator("non_empty").build();
        com.fasterxml.jackson.databind.node.ArrayNode arr = new com.fasterxml.jackson.databind.ObjectMapper()
                .createArrayNode().add("1");
        CheckConditionLeaf notIn = CheckConditionLeaf.builder().name("OTHERCOL")
                .operator("is_not_contained_by").value(arr).build();
        Rule r = ruleWithCheckAndOutputs("X", new CheckConditionAll(List.of(nonEmpty, notIn)),
                List.of(), List.of("VISITNUM"));
        assertEquals(null, RuleCohortGrouper.cohortKey(r));
    }


    @Test
    void membershipShape_withEmptyTermArray_isIneligible()
    {
        // Empty term list would mean every non-empty value violates — never what's intended.
        // Fall back to per-rule (which can produce the same result but isn't worth cohorting).
        com.fasterxml.jackson.databind.node.ArrayNode empty = new com.fasterxml.jackson.databind.ObjectMapper()
                .createArrayNode();
        CheckConditionLeaf nonEmpty = CheckConditionLeaf.builder().name("VISITNUM")
                .operator("non_empty").build();
        CheckConditionLeaf notIn = CheckConditionLeaf.builder().name("VISITNUM")
                .operator("is_not_contained_by").value(empty).build();
        Rule r = ruleWithCheckAndOutputs("X", new CheckConditionAll(List.of(nonEmpty, notIn)),
                List.of(), List.of("VISITNUM"));
        assertEquals(null, RuleCohortGrouper.cohortKey(r));
    }


    @Test
    void membershipCohortAndPerRuleProduceIdenticalResults()
    {
        // Fixture covers all branches of the composite Check:
        // Row 1: VISITNUM = "1" (in codelist), AGEGR1 = "Y" (in codelist) → no violation.
        // Row 2: VISITNUM = "2" (in codelist), AGEGR1 = "OLD" (NOT in codelist) → AGEGR1 violates.
        // Row 3: VISITNUM = "" (missing → non_empty fails), AGEGR1 = "Y" → no violation for either.
        // Row 4: VISITNUM = "99" (NOT in codelist), AGEGR1 = "" (missing) → VISITNUM violates only.
        // Row 5: VISITNUM = "5" (NOT in codelist), AGEGR1 = "X" (NOT in codelist) → both violate.
        IDataTable adlb = MockTable.of().name("ADLB").col("USUBJID", "S1", "S2", "S3", "S4", "S5")
                .col("VISITNUM", "1", "2", "", "99", "5").col("AGEGR1", "Y", "OLD", "Y", "", "X")
                .build();
        DatasetResolver resolver = _ -> null;

        Rule rVisit = genCl("X-VISITNUM", "VISITNUM", "1", "2", "3");
        Rule rAge = genCl("X-AGEGR1", "AGEGR1", "Y", "N", "U");

        // Per-rule path
        JoinCache jc1 = new JoinCache(new JoinCache.SharedIndexCache());
        JoinCache jc2 = new JoinCache(new JoinCache.SharedIndexCache());
        RuleExecutionResult perVisit = RuleRunner.execute(rVisit, adlb, resolver, "AD", null, jc1);
        RuleExecutionResult perAge = RuleRunner.execute(rAge, adlb, resolver, "AD", null, jc2);

        // Cohort path — single shared row pass
        JoinCache jcCohort = new JoinCache(new JoinCache.SharedIndexCache());
        List<RuleExecutionResult> cohortResults = CohortRunner.executeCohort(List.of(rVisit, rAge),
                adlb, resolver, "AD", null, jcCohort);

        assertEquals(2, cohortResults.size());
        assertResultsEqual(perVisit, cohortResults.get(0), "VISITNUM");
        assertResultsEqual(perAge, cohortResults.get(1), "AGEGR1");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------


    private static MatchDataset matchDataset(String name, List<String> keys)
    {
        MatchDataset md = new MatchDataset();
        md.setName(name);
        md.setKeys(new ArrayList<>(keys));
        return md;
    }


    private static CheckConditionLeaf buildLeaf(String name, String op, String foreignRef)
    {
        return CheckConditionLeaf.builder().name(name).operator(op)
                .value(TextNode.valueOf(foreignRef)).build();
    }


    private static Rule ruleWithLeaf(String id, CheckConditionLeaf leaf, List<MatchDataset> mds)
    {
        Rule r = new Rule();
        r.setId(id);
        RuleCore core = new RuleCore();
        core.setId(id);
        r.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage("test message for " + id);
        outcome.setOutputVariables(List.of("USUBJID", leaf.getName(),
                leaf.getValue() != null ? leaf.getValue().asText() : ""));
        r.setOutcome(outcome);
        r.setCheck(new CheckConditionAll(List.of(leaf)));
        r.setMatchDatasets(mds);
        r.setSensitivity(net.cumba.cdisc.core.model.Sensitivity.RECORD);
        net.cumba.cdisc.core.RulePackageLoader.installNativeExpr(r);
        return r;
    }


    /** Fabricates an CDISC-AD0591-style rule: not_equal_to(varName, ADSL.varName), USUBJID join. */
    private static Rule cdiscAd0591(String id, String varName, String foreignRef)
    {
        return ruleWithLeaf(id, buildLeaf(varName, "not_equal_to", foreignRef),
                List.of(matchDataset("ADSL", List.of("USUBJID"))));
    }


    private static Rule nonCohortRule(String id)
    {
        // No Match_Datasets → grouper rejects.
        CheckCondition check = new CheckConditionAll(
                List.of(buildLeaf("SEX", "not_equal_to", "ADSL.SEX")));
        Rule r = new Rule();
        r.setId(id);
        RuleCore core = new RuleCore();
        core.setId(id);
        r.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage("noncohort " + id);
        outcome.setOutputVariables(List.of("USUBJID", "SEX"));
        r.setOutcome(outcome);
        r.setCheck(check);
        r.setSensitivity(net.cumba.cdisc.core.model.Sensitivity.RECORD);
        net.cumba.cdisc.core.RulePackageLoader.installNativeExpr(r);
        return r;
    }


    /**
     * Fabricates a GEN-CL-style rule:
     * {@code all([non_empty(varName), is_not_contained_by(varName, [terms…])])}.
     */
    private static Rule genCl(String id, String varName, String... terms)
    {
        return genCl(id, varName, List.of(terms));
    }


    private static Rule genCl(String id, String varName, List<String> terms)
    {
        com.fasterxml.jackson.databind.node.ArrayNode arr = new com.fasterxml.jackson.databind.ObjectMapper()
                .createArrayNode();
        for (String t : terms)
        {
            arr.add(t);
        }
        CheckConditionLeaf nonEmpty = CheckConditionLeaf.builder().name(varName)
                .operator("non_empty").build();
        CheckConditionLeaf notIn = CheckConditionLeaf.builder().name(varName)
                .operator("is_not_contained_by").value(arr).build();
        return ruleWithCheckAndOutputs(id, new CheckConditionAll(List.of(nonEmpty, notIn)),
                List.of(), List.of(varName));
    }


    private static Rule genClWithMatchDataset(String id, String varName, List<String> terms)
    {
        Rule r = genCl(id, varName, terms);
        r.setMatchDatasets(List.of(matchDataset("ADSL", List.of("USUBJID"))));
        net.cumba.cdisc.core.RulePackageLoader.installNativeExpr(r);
        return r;
    }


    private static Rule ruleWithCheckAndOutputs(String id, CheckCondition check,
            List<MatchDataset> mds, List<String> outputs)
    {
        Rule r = new Rule();
        r.setId(id);
        RuleCore core = new RuleCore();
        core.setId(id);
        r.setCore(core);
        Outcome outcome = new Outcome();
        outcome.setMessage("test message for " + id);
        outcome.setOutputVariables(outputs);
        r.setOutcome(outcome);
        r.setCheck(check);
        r.setMatchDatasets(mds);
        r.setSensitivity(net.cumba.cdisc.core.model.Sensitivity.RECORD);
        net.cumba.cdisc.core.RulePackageLoader.installNativeExpr(r);
        return r;
    }


    private static void assertResultsEqual(RuleExecutionResult expected, RuleExecutionResult actual,
            String label)
    {
        assertEquals(expected.getRuleId(), actual.getRuleId(), label + " ruleId");
        assertEquals(expected.getMessage(), actual.getMessage(), label + " message");
        assertEquals(expected.getStatus(), actual.getStatus(), label + " status");
        assertEquals(expected.getTotalRows(), actual.getTotalRows(), label + " totalRows");
        assertEquals(expected.getViolationCount(), actual.getViolationCount(),
                label + " violation count");
        for (int i = 0; i < expected.getViolations().size(); i++)
        {
            Violation ve = expected.getViolations().get(i);
            Violation va = actual.getViolations().get(i);
            assertEquals(ve.getRow(), va.getRow(), label + " violation[" + i + "].row");
            assertEquals(ve.getValues(), va.getValues(), label + " violation[" + i + "].values");
        }
    }
}
