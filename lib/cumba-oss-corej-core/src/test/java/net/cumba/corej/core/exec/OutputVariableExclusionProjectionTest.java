package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Ruling 5 of {@code PLAN-authoring-grammar-unique-set-and-output-exclusion} ({@code Fix #354}): an
 * excluded variable is <b>absent</b> on every projection path — no key at all, never a null-valued
 * key. One test per path ({@code T-OV-P1}…{@code T-OV-P5}, plus {@code P5b}/{@code P5c} for the
 * cohort path's two fallback halves), each asserting BOTH arms in the same violation: the excluded
 * name is not a key, a retained sibling is. A test that only asserted absence would pass a filter
 * that removed everything.
 *
 * <p>
 * Paths 2, 3 and 4 are the ones that would have inherited the null-key policy
 * ({@code putUnlessDerivedNull} keeps a null for an <em>authored</em> entry) — they are the point
 * of the ruling. Plus the Fix #15 inference fallback with a fully excluded list, which would
 * otherwise re-project what the author excluded.
 * </p>
 */
class OutputVariableExclusionProjectionTest
{

    private static Rule load(String body) throws Exception
    {
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"R1\":" + body + "}}");
        Rule rule = pkg.getRules().get("R1");
        assertNotNull(rule);
        assertNull(rule.getLoadError(), "rule must load cleanly: " + rule.getLoadError());
        return rule;
    }


    private static Map<String, String> singleViolationValues(RuleExecutionResult result)
    {
        assertNotNull(result);
        assertEquals(1, result.getViolationCount(),
                "status=" + result.getStatus() + " violations=" + result.getViolations());
        return result.getViolations().get(0).getValues();
    }

    // ------------------------------------------------------------ T-OV-P1 extractOutputValues


    @Test
    void p1RecordLevelProjectionOmitsTheExcludedNameAndKeepsTheSibling() throws Exception
    {
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression":"not empty(AETERM) and empty(AEDECOD) and not empty(AESEV)"},
                 "Outcome":{"Message":"m","Output_Variables":["!AEDECOD"]}}""");
        assertEquals(List.of("AETERM", "AESEV"), rule.getEffectiveOutputVariables());
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").col("AETERM", "H")
                .col("AEDECOD", "").col("AESEV", "MILD").build();

        Map<String, String> values = singleViolationValues(RuleRunner.execute(rule, ae));

        assertFalse(values.containsKey("AEDECOD"), values.toString());
        assertEquals("H", values.get("AETERM"));
        assertEquals("MILD", values.get("AESEV"));
    }

    // ------------------------------------------------------------ T-OV-P2 buildVariableViolation


    @Test
    void p2VariableMetadataProjectionIsAbsentNotNullKeyed() throws Exception
    {
        // variable_format is DERIVED (the Check reads it) and, unresolved, would be omitted as
        // derived; variable_label is AUTHORED-shaped in the historical null-key policy. Exclude
        // variable_format and keep variable_name + variable_label.
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"all":[{"name":"variable_name","operator":"matches_regex","value":"^AETERM$"},
                                         {"name":"variable_format","operator":"empty"}]},
                         "Outcome":{"Message":"m","Output_Variables":["variable_name","variable_label","!variable_format"]}}""");
        assertEquals(List.of("variable_name", "variable_label"),
                rule.getEffectiveOutputVariables());
        IDataTable table = MockTable.of().name("AE").col("AETERM", "X", "Y").build();

        Map<String, String> values = singleViolationValues(RuleRunner.execute(rule, table));

        assertFalse(values.containsKey("variable_format"), values.toString());
        assertEquals("AETERM", values.get("variable_name"));
        assertTrue(values.containsKey("variable_label"),
                "retained authored sibling stays (null-keyed, the historical policy): " + values);
    }


    @Test
    void p2ControlAuthoredUnresolvedEntryStillKeepsTheNullKey() throws Exception
    {
        // Without the token the same entry lands as a null-valued key — proving the absence
        // above is the exclusion's doing and not a silent change of the null-key policy.
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"all":[{"name":"variable_name","operator":"matches_regex","value":"^AETERM$"},
                                         {"name":"variable_format","operator":"empty"}]},
                         "Outcome":{"Message":"m","Output_Variables":["variable_name","variable_format"]}}""");
        IDataTable table = MockTable.of().name("AE").col("AETERM", "X", "Y").build();

        Map<String, String> values = singleViolationValues(RuleRunner.execute(rule, table));

        assertTrue(values.containsKey("variable_format"));
        assertNull(values.get("variable_format"));
    }

    // ------------------------------------------------------------ T-OV-P3
    // buildDefineVariableViolation


    @Test
    void p3DefineDrivenProjectionIsAbsentNotNullKeyed() throws Exception
    {
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Variable_Universe":"Define","Sensitivity":"Dataset",
                         "Check":{"all":[{"name":"define_variable_role","operator":"not_equal_to",
                                          "value":"library_variable_role"}]},
                         "Outcome":{"Message":"m","Output_Variables":["define_variable_name","!library_variable_role"]}}""");
        assertTrue(rule.getEffectiveOutputVariables().contains("define_variable_role"),
                "precondition: define_variable_role is derived: "
                        + rule.getEffectiveOutputVariables());
        assertFalse(rule.getEffectiveOutputVariables().contains("library_variable_role"));
        MetadataProvider define = new StubMetadataProvider().variable("DM",
                Map.of("name", "SEX", "role", "Qualifier"));
        MetadataProvider library = new StubMetadataProvider().variable("DM",
                Map.of("name", "SEX", "role", "Identifier"));
        IDataTable dm = MockTable.of().name("DM").col("SEX", "F").build();

        Map<String, String> values = singleViolationValues(
                RuleRunner.execute(rule, dm, _ -> null, "DM", library, null, define));

        assertFalse(values.containsKey("library_variable_role"), values.toString());
        assertEquals("SEX", values.get("define_variable_name"));
        assertEquals("Qualifier", values.get("define_variable_role"));
    }

    // ------------------------------------------------------------ T-OV-P4 addVariableRowViolations


    @Test
    void p4PerVariableRowProjectionIsAbsentNotNullKeyed() throws Exception
    {
        // value() per-row rule: variable_name / variable_value / variable_label are the
        // native per-(variable,row) arms (:1884 put, :1888 putUnlessDerivedNull, :1896 put); the
        // trailing `else` re-enters extractOutputValues for a plain column. Exclude
        // variable_label (the putUnlessDerivedNull arm) and a plain column; keep the rest.
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"all":[{"name":"variable_name","operator":"matches_regex","value":"^DESC$"},
                                         {"name":"variable_value","operator":"longer_than","value":3},
                                         {"name":"variable_label","operator":"empty"}]},
                         "Outcome":{"Message":"m","Output_Variables":["variable_name","variable_value","!variable_label"]}}""");
        assertEquals(List.of("variable_name", "variable_value"),
                rule.getEffectiveOutputVariables());
        IDataTable table = MockTable.of().name("ADSL").col("USUBJID", "S1", "S2")
                .col("DESC", "abcdef", "ab").build();

        Map<String, String> values = singleViolationValues(
                RuleRunner.execute(rule, table, _ -> null, "ADSL", null, null, null));

        assertFalse(values.containsKey("variable_label"), values.toString());
        assertEquals("DESC", values.get("variable_name"));
        assertEquals("abcdef", values.get("variable_value"));
    }

    // ------------------------------------------------------------ T-OV-P5 CohortRunner


    /**
     * The cohort-eligible rule shape the {@code T-OV-P5} family shares, built exactly as
     * {@code RuleCohortGrouperTest} builds it (a package-loaded rule carries a normalised
     * {@code Join_Type}, which the grouper rejects): a single equality leaf against a foreign ref,
     * key-based {@code Match_Datasets}, no operations / grouping / precondition.
     *
     * @param authoredOutputVariables
     *            the authored {@code Outcome.Output_Variables}
     * @param derive
     *            whether to run the load-time derivation. {@code false} leaves the rule on the
     *            authored-only reading — the shape the {@code -Dcorej.autoOutputVariables=false}
     *            kill-switch presents, and the only way to reach an EMPTY effective list on a
     *            single-leaf Check (with the derivation on, the derived list and the Fix #15
     *            inference read the same leaf, so they empty together)
     */
    private static Rule cohortEligibleRule(List<String> authoredOutputVariables, boolean derive)
    {
        Rule rule = new Rule();
        rule.setId("R1");
        net.cumba.corej.core.model.RuleCore core = new net.cumba.corej.core.model.RuleCore();
        core.setId("R1");
        rule.setCore(core);
        net.cumba.corej.core.model.Outcome outcome = new net.cumba.corej.core.model.Outcome();
        outcome.setMessage("m");
        outcome.setOutputVariables(authoredOutputVariables);
        rule.setOutcome(outcome);
        rule.setCheck(new net.cumba.corej.core.model.CheckConditionAll(
                List.of(net.cumba.corej.core.model.CheckConditionLeaf.builder().name("AGE")
                        .operator("not_equal_to")
                        .value(com.fasterxml.jackson.databind.node.TextNode.valueOf("ADSL.AGE"))
                        .build())));
        net.cumba.corej.core.model.MatchDataset md = new net.cumba.corej.core.model.MatchDataset();
        md.setName("ADSL");
        md.setKeys(new java.util.ArrayList<>(List.of("USUBJID")));
        rule.setMatchDatasets(List.of(md));
        rule.setSensitivity(net.cumba.corej.core.model.Sensitivity.RECORD);
        RulePackageLoader.installNativeExpr(rule);
        if (derive)
        {
            RulePackageLoader.deriveOutputVariables(rule);
        }
        assertNull(rule.getLoadError(), rule.getLoadError());
        assertNotNull(RuleCohortGrouper.cohortKey(rule), "precondition: cohort-eligible");
        return rule;
    }


    /** The ADLB primary / ADSL foreign pair the {@code T-OV-P5} family runs against. */
    private static IDataTable adlb()
    {
        return MockTable.of().name("ADLB").col("USUBJID", "S1", "S2").col("AGE", "45", "67")
                .col("TRTSDT", "2020-01-01", "2020-01-01").build();
    }


    private static DatasetResolver adslResolver()
    {
        IDataTable adsl = MockTable.of().name("ADSL").col("USUBJID", "S1", "S2")
                .col("AGE", "45", "30").build();
        return name -> "ADSL".equalsIgnoreCase(name) ? adsl : null;
    }


    @Test
    void p5CohortProjectionMatchesThePerRulePathAndOmitsTheExcludedName()
    {
        // TRTSDT is an authored context column, AGE is derived from the leaf, ADSL.AGE is derived
        // and excluded.
        Rule rule = cohortEligibleRule(List.of("TRTSDT", "!ADSL.AGE"), true);
        assertEquals(List.of("TRTSDT", "AGE"), rule.getEffectiveOutputVariables());
        IDataTable adlb = adlb();
        DatasetResolver resolver = adslResolver();

        List<RuleExecutionResult> cohort = CohortRunner.executeCohort(List.of(rule), adlb, resolver,
                "AD", null, new JoinCache(new JoinCache.SharedIndexCache()));
        RuleExecutionResult perRule = RuleRunner.execute(rule, adlb, resolver, "AD", null,
                new JoinCache(new JoinCache.SharedIndexCache()));

        assertEquals(1, cohort.size());
        Map<String, String> cohortValues = singleViolationValues(cohort.get(0));
        Map<String, String> perRuleValues = singleViolationValues(perRule);
        assertFalse(cohortValues.containsKey("ADSL.AGE"), cohortValues.toString());
        assertEquals("67", cohortValues.get("AGE"));
        assertEquals("2020-01-01", cohortValues.get("TRTSDT"));
        assertEquals(perRuleValues, cohortValues, "cohort / per-rule parity");
    }


    @Test
    void p5bCohortTakesTheFixFifteenFallbackExactlyAsThePerRulePathDoes()
    {
        // An EMPTY effective list. The per-rule path infers AGE from the Check leaf; CohortRunner
        // carried no fallback at all and projected {} — a byte-identity break its own contract
        // forbids, found by the Fix #354 review (2026-08-23).
        Rule rule = cohortEligibleRule(List.of("!TRTSDT"), false);
        assertEquals(List.of(), rule.effectiveOutputVariablesOrAuthored());
        IDataTable adlb = adlb();
        DatasetResolver resolver = adslResolver();
        // Vacuity control: the inference the empty list falls back to really does yield AGE.
        assertEquals(List.of("AGE"), List
                .copyOf(RuleRunner.collectCheckLeafColumns(rule.getCheck(), adlb.getMetaData())));

        List<RuleExecutionResult> cohort = CohortRunner.executeCohort(List.of(rule), adlb, resolver,
                "AD", null, new JoinCache(new JoinCache.SharedIndexCache()));
        RuleExecutionResult perRule = RuleRunner.execute(rule, adlb, resolver, "AD", null,
                new JoinCache(new JoinCache.SharedIndexCache()));

        assertEquals(1, cohort.size());
        Map<String, String> cohortValues = singleViolationValues(cohort.get(0));
        assertEquals("67", cohortValues.get("AGE"), "the fallback projects the Check's leaf");
        assertFalse(cohortValues.containsKey("TRTSDT"), cohortValues.toString());
        assertEquals(singleViolationValues(perRule), cohortValues,
                "cohort / per-rule parity on the Fix #15 fallback");
    }


    @Test
    void p5cCohortFallbackHonoursTheExclusionsExactlyAsThePerRulePathDoes()
    {
        // The E-2 half of the same fallback: a list that is empty BECAUSE of `!X` must not be
        // re-populated by the inference. A fallback added WITHOUT the exclusion filter projects
        // AGE here; both paths must project neither name.
        Rule rule = cohortEligibleRule(List.of("!AGE"), false);
        assertEquals(List.of(), rule.effectiveOutputVariablesOrAuthored());
        assertEquals(Set.of("AGE"), rule.excludedOutputVariablesOrAuthored());
        IDataTable adlb = adlb();
        DatasetResolver resolver = adslResolver();
        // Vacuity control: without the filter the inference would re-project the excluded AGE.
        assertEquals(List.of("AGE"), List
                .copyOf(RuleRunner.collectCheckLeafColumns(rule.getCheck(), adlb.getMetaData())));

        List<RuleExecutionResult> cohort = CohortRunner.executeCohort(List.of(rule), adlb, resolver,
                "AD", null, new JoinCache(new JoinCache.SharedIndexCache()));
        RuleExecutionResult perRule = RuleRunner.execute(rule, adlb, resolver, "AD", null,
                new JoinCache(new JoinCache.SharedIndexCache()));

        assertEquals(1, cohort.size());
        Map<String, String> cohortValues = singleViolationValues(cohort.get(0));
        assertFalse(cohortValues.containsKey("AGE"), cohortValues.toString());
        assertEquals(singleViolationValues(perRule), cohortValues,
                "cohort / per-rule parity on the excluded fallback");
    }

    // ------------------------------------------------------------ the Fix #15 fallback


    @Test
    void fullyExcludedListDoesNotFallBackToReprojectingTheExcludedColumns() throws Exception
    {
        // Every derivable entry is excluded ⇒ the effective list is EMPTY ⇒ the Fix #15
        // inference would re-project AETERM and AEDECOD from the Check leaves. It must not.
        Rule rule = load("""
                {"Core":{"Id":"R1"},"Sensitivity":"Record",
                 "Check":{"expression":"not empty(AETERM) and empty(AEDECOD)"},
                 "Outcome":{"Message":"m","Output_Variables":["!AETERM","!AEDECOD"]}}""");
        assertEquals(List.of(), rule.getEffectiveOutputVariables());
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1").col("AETERM", "H")
                .col("AEDECOD", "").build();
        // The vacuity control: the inference the empty list falls back to WOULD re-project both.
        assertEquals(List.of("AETERM", "AEDECOD"),
                List.copyOf(RuleRunner.collectCheckLeafColumns(rule.getCheck(), ae.getMetaData())));

        RuleExecutionResult result = RuleRunner.execute(rule, ae);

        Map<String, String> values = singleViolationValues(result);
        assertFalse(values.containsKey("AETERM"), values.toString());
        assertFalse(values.containsKey("AEDECOD"), values.toString());
        assertEquals("S1", result.getViolations().get(0).getUsubjid(),
                "the out-of-band identity still rides");
    }


    @Test
    void fullyExcludedPerVariableListDoesNotFallBackToTheMetadataDefault() throws Exception
    {
        // Per-variable path with every derived entry excluded ⇒ empty list ⇒ path 2's
        // "no Output_Variables" branch would emit the full metadata map (variable_name,
        // variable_label, …). The exclusions still hold there.
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"all":[{"name":"variable_name","operator":"matches_regex","value":"^AETERM$"},
                                         {"name":"variable_label","operator":"empty"}]},
                         "Outcome":{"Message":"m","Output_Variables":["!variable_name","!variable_label"]}}""");
        assertEquals(List.of(), rule.getEffectiveOutputVariables());
        IDataTable table = MockTable.of().name("AE").col("AETERM", "X").build();

        Map<String, String> values = singleViolationValues(RuleRunner.execute(rule, table));

        assertFalse(values.containsKey("variable_name"), values.toString());
        assertFalse(values.containsKey("variable_label"), values.toString());
    }


    @Test
    void fullyExcludedPerVariableRowListDoesNotFallBackToTheRowDefault() throws Exception
    {
        // Path 4 with every derived entry excluded ⇒ empty list ⇒ addVariableRowViolations'
        // "no Output_Variables" branch would emit variable_name / variable_label /
        // variable_value. The exclusions still hold there.
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Record",
                         "Check":{"all":[{"name":"variable_name","operator":"matches_regex","value":"^DESC$"},
                                         {"name":"variable_value","operator":"longer_than","value":3}]},
                         "Outcome":{"Message":"m","Output_Variables":["!variable_name","!variable_value"]}}""");
        assertEquals(List.of(), rule.getEffectiveOutputVariables());
        IDataTable table = MockTable.of().name("ADSL").col("USUBJID", "S1", "S2")
                .col("DESC", "abcdef", "ab").build();

        Map<String, String> values = singleViolationValues(
                RuleRunner.execute(rule, table, _ -> null, "ADSL", null, null, null));

        assertFalse(values.containsKey("variable_name"), values.toString());
        assertFalse(values.containsKey("variable_value"), values.toString());
    }


    @Test
    void fullyExcludedGroupedListDoesNotFallBackToReprojectingTheExcludedColumns() throws Exception
    {
        // The executeGrouped twin of the Fix #15 fallback.
        Rule rule = load(
                """
                        {"Core":{"Id":"R1"},"Sensitivity":"Group",
                         "Grouping":{"Variables":["USUBJID"]},
                         "Check":{"expression":"not is_unique_set([USUBJID, AETERM]) and not empty(AETERM)"},
                         "Outcome":{"Message":"m","Output_Variables":["!AETERM"]}}""");
        assertEquals(List.of(), rule.getEffectiveOutputVariables());
        IDataTable ae = MockTable.of().name("AE").col("USUBJID", "S1", "S1").col("AETERM", "H", "H")
                .build();

        RuleExecutionResult result = RuleRunner.execute(rule, ae);

        assertNotNull(result);
        assertTrue(result.getViolationCount() >= 1, "status=" + result.getStatus());
        for (Violation v : result.getViolations())
        {
            assertFalse(v.getValues().containsKey("AETERM"), v.getValues().toString());
        }
    }
}
