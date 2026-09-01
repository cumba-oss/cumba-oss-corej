package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleExecutionStatus;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.model.JoinType;
import net.cumba.cdisc.core.model.MatchDataset;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code Fix #236} / {@code plans/PLAN-join-type-validation.md} — {@code Join_Type} was a free-form
 * string that <b>nothing</b> validated, and the engine's only value comparison is a negation
 * ({@code KeyMatchRowExpander}: {@code !"inner".equalsIgnoreCase(…)}), so every unrecognised value
 * was silently executed as a <b>left</b> join and reported nothing.
 *
 * <p>
 * ⚠⚠ The hazard this class also has to pin is the <em>opposite</em> one: validation must <b>not</b>
 * reject {@code null}. {@code RuleGenerator} never calls {@code normalizeJoinTypes}, so its
 * generated rules keep a null {@code Join_Type} — and that null is what keeps
 * {@code RuleCohortGrouper}'s equality-cohort path reachable ({@code Fix #233} / EC-74). A
 * validation that treated absence as a violation would kill that optimisation while looking
 * correct.
 * </p>
 */
class JoinTypeValidationTest
{

    /** A minimal loadable package whose single {@code Match_Datasets} entry carries {@code raw}. */
    private static String packageWithJoinType(String joinTypeJson)
    {
        return """
                {"rules":{"x":{"Core":{"Id":"T-JT"},\
                "Sensitivity":"Record",\
                "Match_Datasets":[{"Name":"ADSL","Keys":["USUBJID"]%s}],\
                "Outcome":{"Message":"m","Output_Variables":["USUBJID","AGE"]},\
                "Check":{"all":[{"name":"AGE","operator":"not_equal_to","value":"ADSL.AGE"}]}}}}"""
                .formatted(joinTypeJson);
    }


    private static Rule load(String joinTypeJson) throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString(packageWithJoinType(joinTypeJson)).getRules()
                .get("x");
        assertNotNull(rule, "the fixture must bind, or nothing below is measuring anything");
        return rule;
    }


    private static Rule loadWith(String joinType) throws IOException
    {
        return load(",\"Join_Type\":\"" + joinType + "\"");
    }

    // ------------------------------------------------------------------
    // A1 — a present-but-unrecognised value is LOUD, not a silent left join
    // ------------------------------------------------------------------


    @Test
    void authoredOuter_filesALoadError_insteadOfRunningSilentlyAsLeft() throws IOException
    {
        Rule rule = loadWith("outer");
        String error = rule.getLoadError();
        assertNotNull(error, "an authored Join_Type the engine does not implement must not load "
                + "cleanly — before Fix #236 it ran as a left join and reported nothing");
        assertTrue(error.contains("Invalid Join_Type 'outer'"),
                "the error must quote the authored value verbatim; was: " + error);
        assertTrue(error.contains("inner") && error.contains("left"),
                "the error must enumerate the legal vocabulary; was: " + error);
        assertTrue(error.contains("ADSL"),
                "the error must name the Match_Datasets entry so a multi-entry rule is "
                        + "actionable; was: " + error);
    }


    @Test
    void aTypo_filesALoadError_becauseTheNegationWouldHaveMadeItLeft() throws IOException
    {
        Rule rule = loadWith("iner");
        assertNotNull(rule.getLoadError(),
                "'iner' is the live consequence the plan names: not equal to \"inner\", so the "
                        + "negation runs it as left and nothing reports it");
    }


    @Test
    void aValueFromAnotherVocabulary_filesALoadError() throws IOException
    {
        assertNotNull(loadWith("right").getLoadError(), "`right` is not implemented here");
        assertNotNull(loadWith("full").getLoadError(), "`full` is not implemented here");
    }


    /**
     * ⚠ Padding is <b>not</b> forgiven, and that is deliberate. {@code KeyMatchRowExpander}
     * compares with {@code equalsIgnoreCase} and no trim, so {@code " inner "} executes as a
     * <b>left</b> join. Accepting it at load would bless a value that runs as something else —
     * re-creating the exact silent divergence this gate exists to close.
     */
    @Test
    void whitespacePaddedInner_filesALoadError_becauseTheEngineWouldRunItAsLeft() throws IOException
    {
        assertNotNull(loadWith(" inner ").getLoadError(),
                "' inner ' is not equal-ignore-case to 'inner', so the engine runs it as left — "
                        + "validation must agree with the engine, not with the author's intent");
    }


    /**
     * ⭐ The acceptance the plan states as {@code A1}: not merely "a loadError is set", but that the
     * rule <b>reports ERROR and never evaluates</b>. This runs it.
     */
    @Test
    void theRuleReportsError_andNeverEvaluates() throws IOException
    {
        Rule rule = loadWith("outer");
        IDataTable adlb = MockTable.of().name("ADLB").col("USUBJID", "P1").col("AGE", "41").build();

        RuleExecutionResult result = RuleRunner.execute(rule, adlb);

        assertNotNull(result);
        assertEquals(RuleExecutionStatus.ERROR, result.getStatus(),
                "a Join_Type the engine cannot honour must surface as ERROR, not as a silently "
                        + "left-joined PASS");
        assertNotNull(result.getStatusMessage());
        assertTrue(result.getStatusMessage().contains("Invalid Join_Type 'outer'"),
                "the status message carries the diagnosis; was: " + result.getStatusMessage());
    }

    // ------------------------------------------------------------------
    // A2 — the legal values, in any case, and the two ABSENCE states
    // ------------------------------------------------------------------


    @Test
    void bothLegalValues_loadCleanly_inEveryCase() throws IOException
    {
        for (String value : List.of("inner", "INNER", "Inner", "left", "LEFT", "Left"))
        {
            Rule rule = loadWith(value);
            assertNull(rule.getLoadError(), "'" + value + "' is legal — KeyMatchRowExpander "
                    + "already interprets it, so the gate must accept it");
            assertEquals(value, joinTypeOf(rule),
                    "an authored value must survive normalisation verbatim");
        }
    }


    @Test
    void absentJoinType_isNotAViolation_andStillNormalisesToInner() throws IOException
    {
        Rule rule = load("");
        assertNull(rule.getLoadError(), "absence is legal everywhere — it means 'not authored'");
        assertEquals("inner", joinTypeOf(rule),
                "normalizeJoinTypes still stamps the default; Fix #236 changed nothing here");
    }


    @Test
    void blankJoinType_isNotAViolation_andStillNormalisesToInner() throws IOException
    {
        Rule rule = loadWith("   ");
        assertNull(rule.getLoadError(), "blank is absence, not a value");
        assertEquals("inner", joinTypeOf(rule), "blank normalises to the default, as before");
    }


    /**
     * ⚠⚠ The load-bearing half: the validator judges the <b>string when present</b> and never the
     * absence. A hand-built rule with a null {@code Join_Type} — the shape every
     * {@code RuleGenerator} rule has, since it bypasses {@code normalizeJoinTypes} — must pass the
     * gate untouched. {@code RuleCohortGrouperTest} pins the consequence (the generated
     * {@code CDISC-AD0591-} family still reaches the equality cohort); this pins the cause.
     */
    @Test
    void aNullJoinTypeIsNeverRejected_soTheGeneratedCohortPathStaysReachable()
    {
        Rule rule = new Rule();
        MatchDataset md = new MatchDataset();
        md.setName("ADSL");
        md.setKeys(List.of("USUBJID"));
        assertNull(md.getJoinType(), "the fixture must start null or it proves nothing");
        rule.setMatchDatasets(List.of(md));

        RulePackageLoader.validateEnumFields(rule);

        assertNull(rule.getLoadError(),
                "a null Join_Type must never file a load error — RuleGenerator never calls "
                        + "normalizeJoinTypes, and that null is what keeps the equality-cohort "
                        + "path reachable (Fix #233 / EC-74)");
        assertNull(md.getJoinType(), "validation must not write to the field either");
    }


    /** Every entry is judged, not only the first — a multi-entry rule can hide the bad one. */
    @Test
    void aLaterMatchDatasetsEntryIsJudgedToo() throws IOException
    {
        String json = """
                {"rules":{"x":{"Core":{"Id":"T-JT2"},\
                "Sensitivity":"Record",\
                "Match_Datasets":[{"Name":"ADSL","Keys":["USUBJID"],"Join_Type":"left"},\
                {"Name":"ADAE","Keys":["USUBJID"],"Join_Type":"outer"}],\
                "Outcome":{"Message":"m","Output_Variables":["USUBJID","AGE"]},\
                "Check":{"all":[{"name":"AGE","operator":"not_equal_to","value":"ADSL.AGE"}]}}}}""";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(rule);
        String error = rule.getLoadError();
        assertNotNull(error, "the second entry's value is just as fatal as the first's");
        assertTrue(error.contains("ADAE"),
                "the message must name the offending entry; was: " + error);
    }

    // ------------------------------------------------------------------
    // The vocabulary itself
    // ------------------------------------------------------------------


    @Test
    void fromJson_isCaseInsensitiveAndClosed()
    {
        assertSame(JoinType.INNER, JoinType.fromJson("inner"));
        assertSame(JoinType.INNER, JoinType.fromJson("INNER"));
        assertSame(JoinType.LEFT, JoinType.fromJson("left"));
        assertSame(JoinType.LEFT, JoinType.fromJson("LeFt"));
        assertNull(JoinType.fromJson("outer"));
        assertNull(JoinType.fromJson(""));
        assertNull(JoinType.fromJson(null));
        assertNull(JoinType.fromJson(" inner "), "no trimming — see the enum's javadoc");
    }


    @Test
    void isAbsent_isExactlyNullOrBlank()
    {
        assertTrue(JoinType.isAbsent(null));
        assertTrue(JoinType.isAbsent(""));
        assertTrue(JoinType.isAbsent("   "));
        assertFalse(JoinType.isAbsent("inner"));
        assertFalse(JoinType.isAbsent("nonsense"));
    }


    @Test
    void jsonValues_areTheTwoTheEngineImplements()
    {
        assertEquals(2, JoinType.values().length,
                "adding a join type means auditing every `inner` comparison site, not extending "
                        + "this enum alone (KeyMatchRowExpander's test is a negation)");
        assertEquals("inner", JoinType.INNER.getJsonValue());
        assertEquals("left", JoinType.LEFT.getJsonValue());
    }


    private static @Nullable String joinTypeOf(Rule rule)
    {
        List<MatchDataset> mds = rule.getMatchDatasets();
        assertNotNull(mds);
        assertEquals(1, mds.size());
        return mds.get(0).getJoinType();
    }

}
