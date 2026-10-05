package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * The loader gates of the two fields W3f of {@code PLAN-merged-evaluation-table} binds:
 * {@code Match_Datasets[].Cardinality} (§3.4, MET #12, OD-5, OD-9, P-13) and the rule-level
 * {@code Supp_Keys} (§3.5.1, MET #18, MET #19, P-15).
 *
 * <p>
 * Each refusal sits beside its control — the nearest shape that must load clean — so a gate that
 * errors on everything cannot pass. Red-first: against the W3e loader every accepting case failed
 * (the two keys were unknown keys) and every refusing case failed for the wrong reason (the
 * unknown-key error, not the gate's).
 * </p>
 */
class MergedTableAuthoringGatesTest
{

    private static Rule load(String matchDatasets, String topLevel) throws IOException
    {
        String json = """
                {"rules":{"x":{"Core":{"Id":"T-W3F"},"Sensitivity":"Record",%s\
                "Match_Datasets":[%s],\
                "Outcome":{"Message":"m","Output_Variables":["USUBJID"]},\
                "Check":{"all":[{"expression": "not empty(USUBJID)"}]}}}}""".formatted(topLevel,
                matchDatasets);
        Rule rule = RulePackageLoader.loadFromString(KeyedJoinFixtures.declared(json)).getRules()
                .get("x");
        assertNotNull(rule, "the fixture must bind, or nothing below is measuring anything");
        return rule;
    }


    /** A rule whose Requirements the fixture authors itself (no declaration helper). */
    private static Rule loadAuthored(String ruleJson) throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString("{\"rules\":{\"x\":" + ruleJson + "}}")
                .getRules().get("x");
        assertNotNull(rule, "the fixture must bind");
        return rule;
    }


    private static void assertClean(Rule rule)
    {
        assertNull(rule.getLoadError(), rule.getLoadError());
    }


    private static void assertError(Rule rule, String fragment)
    {
        @Nullable
        String error = rule.getLoadError();
        assertNotNull(error, "expected a load error containing '" + fragment + "'");
        assertTrue(error.contains(fragment), "'" + fragment + "' not in: " + error);
        assertTrue(!error.contains("unknown key"), "bound, so never an unknown key: " + error);
    }

    private static final String DM = "{\"Name\":\"DM\",\"Keys\":[\"STUDYID\",\"USUBJID\"]%s}";

    // ---- Cardinality

    @Test
    void everySpellingOfCardinalityLoads() throws IOException
    {
        for (String c : new String[]
        {
                "1:1", "1:n", "n:1", "n:m", "N:1"
        })
        {
            Rule rule = load(DM.formatted(",\"Cardinality\":\"" + c + "\""), "");
            assertClean(rule);
            assertNull(rule.getLoadWarning(), c + ": a keyed entry has no IG cardinality");
        }
    }


    @Test
    void anUnknownCardinalityIsALoadErrorNeverASilentNToM() throws IOException
    {
        assertError(load(DM.formatted(",\"Cardinality\":\"many-to-one\""), ""),
                "Invalid Cardinality 'many-to-one' on Match_Datasets entry 'DM' — expected one of:"
                        + " 1:1, 1:n, n:1, n:m");
        assertError(load(DM.formatted(",\"Cardinality\":\"1:m\""), ""),
                "Invalid Cardinality '1:m'");
    }

    private static final String CHILD = "{\"Name\":\"SUPP--\",\"Keys\":[\"USUBJID\",\"IDVAR\","
            + "\"IDVARVAL\"],\"Child\":true%s}";

    @Test
    void aChildEntryDisagreeingWithTheIgWarnsAndIsNotServedBeforeW4() throws IOException
    {
        Rule agrees = load(CHILD.formatted(",\"Cardinality\":\"n:m\""), "");
        assertClean(agrees);
        assertNull(agrees.getLoadWarning(), "n:m is the IG's own (P-13)");
        Rule disagrees = load(CHILD.formatted(",\"Cardinality\":\"n:1\""), "");
        String warning = disagrees.getLoadWarning();
        assertNotNull(warning, "MET #12 (c): the disagreement is logged");
        assertTrue(warning.contains("the authored Cardinality n:1 of Child entry 'SUPP--'"
                + " disagrees with the IG's n:m"), warning);
        assertError(disagrees, "a Child: true entry is merged by the pre-merge until"
                + " PLAN-merged-evaluation-table W4");
    }


    @Test
    void aForwardRelrecEntryAcceptsOnlyNToMBeforeW5() throws IOException
    {
        assertClean(load("{\"Name\":\"RELREC\",\"Cardinality\":\"n:m\"}", ""));
        assertError(load("{\"Name\":\"RELREC\",\"Cardinality\":\"n:1\"}", ""),
                "a keyless entry (forward RELREC) runs its own expansion until"
                        + " PLAN-merged-evaluation-table W5");
    }


    @Test
    void aRuleOnTheLegacyArmCannotAuthorACheckingCardinality() throws IOException
    {
        // A chained qualified key keeps the whole rule on the legacy pipeline, which checks none.
        // The key declaration is the shape PLAN-rprfdy-offset-tp-join C1 requires.
        String chained = "{\"Core\":{\"Id\":\"T-W3F\"},\"Sensitivity\":\"Record\","
                + "\"Requirements\":{\"Variables\":{\"All\":[\"USUBJID\",\"DM.RPATHCD\"],"
                + "\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"],[\"DM.RPATHCD\",\"TP.RPATHCD\"],"
                + "[\"RPHASE\",\"TP.RPHASE\"]]}},"
                + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]%s},"
                + "{\"Name\":\"TP\",\"Keys\":[\"DM.RPATHCD\",\"RPHASE\"]}],"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]},"
                + "\"Check\":{\"expression\":\"not empty(USUBJID)\"}}";
        assertClean(loadAuthored(chained.formatted("")));
        assertError(loadAuthored(chained.formatted(",\"Cardinality\":\"n:1\"")),
                "Cardinality n:1 on Match_Datasets entry 'DM' would not be checked: the rule"
                        + " carries a forward RELREC entry or a chained qualified key");
    }

    // ---- Supp_Keys

    private static final String PLAIN_DM = DM.formatted("");

    @Test
    void anAuthoredSuppKeyLoadsInBothElementShapes() throws IOException
    {
        assertClean(load(PLAIN_DM, "\"Supp_Keys\":[\"STUDYID\",{\"left\":\"DOMAIN\","
                + "\"right\":\"RDOMAIN\"},\"USUBJID\",\"POOLID\",\"IDVAR\",\"IDVARVAL\"],"));
        assertClean(load(PLAIN_DM, "\"Supp_Keys\":[\"STUDYID\",\"USUBJID\"],"));
        assertClean(load(PLAIN_DM, "\"Supp_Merge\":true,\"Supp_Keys\":[\"USUBJID\"],"));
    }


    @Test
    void suppKeysWithoutTheMergeIsALoadError() throws IOException
    {
        assertClean(load(PLAIN_DM, "\"Supp_Merge\":false,"));
        assertError(load(PLAIN_DM, "\"Supp_Merge\":false,\"Supp_Keys\":[\"USUBJID\"],"),
                "Supp_Keys has no effect under Supp_Merge: false");
    }


    @Test
    void halfOfTheIdvarPairIsALoadError() throws IOException
    {
        assertError(load(PLAIN_DM, "\"Supp_Keys\":[\"USUBJID\",\"IDVAR\"],"),
                "Supp_Keys names IDVAR without IDVARVAL");
        assertError(load(PLAIN_DM, "\"Supp_Keys\":[\"USUBJID\",\"IDVARVAL\"],"),
                "Supp_Keys names IDVARVAL without IDVAR");
        // the pair is recognised by its right side, as the merge recognises it
        assertError(
                load(PLAIN_DM,
                        "\"Supp_Keys\":[\"USUBJID\",\"IDVAR\","
                                + "{\"left\":\"AESEQ\",\"right\":\"IDVARVAL\"}],"),
                "Supp_Keys names IDVARVAL with the left side 'AESEQ'");
    }


    @Test
    void anEmptyOrMalformedOrQualifiedSuppKeyIsALoadError() throws IOException
    {
        assertError(load(PLAIN_DM, "\"Supp_Keys\":[],"), "Supp_Keys is empty");
        assertError(load(PLAIN_DM, "\"Supp_Keys\":\"USUBJID\","), "Supp_Keys must be a list");
        assertError(load(PLAIN_DM, "\"Supp_Keys\":[{\"left\":\"DOMAIN\"}],"),
                "Supp_Keys has a malformed element");
        assertError(load(PLAIN_DM, "\"Supp_Keys\":[\"DM.USUBJID\"],"),
                "Supp_Keys names the qualified column 'DM.USUBJID'");
    }


    @Test
    void aStrayKeyInASuppKeysElementIsAnUnknownKey() throws IOException
    {
        String error = load(PLAIN_DM,
                "\"Supp_Keys\":[{\"left\":\"DOMAIN\",\"right\":\"RDOMAIN\"," + "\"rigth\":\"X\"}],")
                        .getLoadError();
        assertNotNull(error);
        assertTrue(error.contains("unknown key 'rigth' under 'Supp_Keys[0]'"), error);
    }


    @Test
    void suppKeysOnALegacyArmedRuleIsALoadError() throws IOException
    {
        assertError(load("{\"Name\":\"RELREC\"}", "\"Supp_Keys\":[\"USUBJID\"],"),
                "Supp_Keys would not be read: the rule carries a forward RELREC entry");
    }
}
