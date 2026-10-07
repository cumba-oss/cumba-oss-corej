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

    /**
     * W4: the merged view checks a Child entry's authored cardinality (P-16), so the W3f staging
     * refusal is retired; the IG-disagreement WARNING stays (MET #12 (c), P-13).
     */
    @Test
    void aChildEntryDisagreeingWithTheIgWarnsAndIsServed() throws IOException
    {
        Rule agrees = load(CHILD.formatted(",\"Cardinality\":\"n:m\""), "");
        assertClean(agrees);
        assertNull(agrees.getLoadWarning(), "n:m is the IG's own (P-13)");
        Rule disagrees = load(CHILD.formatted(",\"Cardinality\":\"n:1\""), "");
        String warning = disagrees.getLoadWarning();
        assertNotNull(warning, "MET #12 (c): the disagreement is logged");
        assertTrue(warning.contains("the authored Cardinality n:1 of Child entry 'SUPP--'"
                + " disagrees with the IG's n:m"), warning);
        assertClean(disagrees);
    }


    /**
     * W4: a Child entry keeps every record (the bare pre-merge never dropped one), so an authored
     * {@code Join_Type} other than {@code left} would be silently ignored — a load error. (A Child
     * entry beside a chained qualified key was a second one until W6, which serves both shapes in
     * one view.)
     */
    @Test
    void aChildEntryIsLeftAndOnlyServedByTheMergedView() throws IOException
    {
        assertClean(load(CHILD.formatted(""), ""));
        assertClean(load(CHILD.formatted(",\"Join_Type\":\"left\""), ""));
        assertError(load(CHILD.formatted(",\"Join_Type\":\"inner\""), ""),
                "Join_Type 'inner' on Child: true Match_Datasets entry 'SUPP--' — a Child entry"
                        + " keeps every record (left)");
        assertClean(load(CHILD.formatted("") + ",{\"Name\":\"RELREC\",\"Child\":true}", ""));
        // W5: a forward RELREC entry is a part of the same view — a Child entry beside it is served
        assertClean(load(CHILD.formatted("") + ",{\"Name\":\"RELREC\"}", ""));
        assertNoChainedGate(loadAuthored(CHAINED.formatted(",{\"Name\":\"SUPP--\",\"Child\":true,"
                + "\"Keys\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]}")));
    }


    /**
     * W5: a forward RELREC entry's {@code Cardinality} is served by the view — an authored value
     * overrides every relationship's RELTYPE default (OD-9) — so every spelling loads; a keyless
     * entry that is not RELREC still builds no part and cannot be checked.
     */
    @Test
    void aForwardRelrecEntryAcceptsEveryCardinality() throws IOException
    {
        assertClean(load("{\"Name\":\"RELREC\",\"Cardinality\":\"n:m\"}", ""));
        assertClean(load("{\"Name\":\"RELREC\",\"Cardinality\":\"n:1\"}", ""));
        assertClean(load("{\"Name\":\"RELREC\",\"Cardinality\":\"1:1\"}", ""));
        assertError(load("{\"Name\":\"DS\",\"Cardinality\":\"n:1\"}", ""),
                "a keyless entry builds no part");
    }


    /**
     * W6: a chained qualified key is composed by the merged view, so the four load gates that kept
     * every view-only shape off such a rule (a forward RELREC entry, a checking
     * {@code Cardinality}, {@code Supp_Keys}, a {@code Child} entry) retired with the legacy arm.
     * Red-first: each of these loaded with its gate's error until W6.
     */
    @Test
    void aForwardRelrecEntryOnAChainedKeyRuleIsServed() throws IOException
    {
        assertClean(loadAuthored(CHAINED.formatted("")));
        assertNoChainedGate(loadAuthored(CHAINED.formatted(",{\"Name\":\"RELREC\"}")));
    }


    @Test
    void aChainedKeyRuleAuthorsACheckingCardinality() throws IOException
    {
        assertClean(loadAuthored(CHAINED.formatted("")));
        assertClean(loadAuthored(CHAINED_DM_EXTRA.formatted(",\"Cardinality\":\"n:1\"")));
    }


    /** No load error of the retired chained-key gates (the shape may carry others of its own). */
    private static void assertNoChainedGate(Rule rule)
    {
        @Nullable
        String error = rule.getLoadError();
        assertTrue(error == null || !error.contains("chained qualified key"), error);
    }

    /**
     * A rule with a chained qualified key (the shape {@code PLAN-rprfdy-offset-tp-join} C1
     * requires) — the last population of the legacy arm until W6 composed it in the view;
     * {@code %s} takes further {@code Match_Datasets} entries.
     */
    private static final String CHAINED = "{\"Core\":{\"Id\":\"T-W3F\"},\"Sensitivity\":\"Record\","
            + "\"Requirements\":{\"Variables\":{\"All\":[\"USUBJID\",\"DM.RPATHCD\"],"
            + "\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"],[\"DM.RPATHCD\",\"TP.RPATHCD\"],"
            + "[\"RPHASE\",\"TP.RPHASE\"]]}},"
            + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]},"
            + "{\"Name\":\"TP\",\"Keys\":[\"DM.RPATHCD\",\"RPHASE\"]}%s],"
            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]},"
            + "\"Check\":{\"expression\":\"not empty(USUBJID)\"}}";

    /** {@link #CHAINED} with {@code %s} inside the DM entry instead. */
    private static final String CHAINED_DM_EXTRA = CHAINED
            .replace("{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}",
                    "{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]%s}")
            .replace("\"RPHASE\"]}%s]", "\"RPHASE\"]}]");

    /** {@link #CHAINED} with {@code %s} before {@code Match_Datasets} (top-level keys). */
    private static final String CHAINED_TOP_EXTRA = CHAINED
            .replace("\"Match_Datasets\":[", "%s\"Match_Datasets\":[")
            .replace("\"RPHASE\"]}%s]", "\"RPHASE\"]}]");

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
    void suppKeysOnAChainedKeyRuleIsRead() throws IOException
    {
        // W6: the chained key is composed by the view, which reads the authored SUPP key
        assertClean(load("{\"Name\":\"RELREC\"}", "\"Supp_Keys\":[\"USUBJID\"],"));
        assertClean(loadAuthored(CHAINED_TOP_EXTRA.formatted("\"Supp_Keys\":[\"USUBJID\"],")));
    }

    // ---- Join_Type where nothing honours it (PLAN-silently-ignored-authoring-inputs E4, E5)

    private static final String KEYED = "{\"Name\":\"%s\",\"Keys\":[\"STUDYID\",\"USUBJID\"]%s}";

    /**
     * E4 (S3): a keyed, non-Child {@code SUPP*} / {@code SQ*} / {@code RELREC} entry always runs as
     * {@code left} ({@code MergedEvaluation.keyedSpecs}), so an authored {@code inner} would be
     * silently ignored — a load error. {@code left} and an absent {@code Join_Type} load clean
     * (D-e), and a non-SUPP {@code --} template honours its {@code Join_Type} after the specialiser
     * renames it (D-b2), so {@code inner} on {@code AE--} is clean. Red-first: every refusal loaded
     * clean before E4.
     */
    @Test
    void anInnerJoinTypeOnALeftOnlyEntryIsALoadError() throws IOException
    {
        for (String name : new String[]
        {
                "SUPPAE", "SUPP--", "SQAPMH", "SQAP--", "RELREC", "suppae"
        })
        {
            assertError(load(KEYED.formatted(name, ",\"Join_Type\":\"inner\""), ""),
                    "Join_Type 'inner' on Match_Datasets entry '" + name + "' has no effect");
            assertError(load(KEYED.formatted(name, ",\"Join_Type\":\"INNER\""), ""),
                    "Join_Type 'INNER' on Match_Datasets entry '" + name + "' has no effect");
            assertClean(load(KEYED.formatted(name, ",\"Join_Type\":\"left\""), ""));
            assertClean(load(KEYED.formatted(name, ""), ""));
        }
        // the planted controls: the ordinary and the renamed-template entries honour inner
        assertClean(load(KEYED.formatted("AE--", ",\"Join_Type\":\"inner\""), ""));
        assertClean(load(KEYED.formatted("DM", ",\"Join_Type\":\"inner\""), ""));
        assertClean(load(KEYED.formatted("SE", ",\"Join_Type\":\"inner\""), ""));
    }


    /**
     * E5: {@code Join_Type} on an entry that builds no keyed part and is neither {@code Child} nor
     * a forward {@code RELREC} entry has no reader at all — a keyless non-RELREC entry, and a keyed
     * entry without a {@code Name}. Any authored value is a load error; the forward RELREC, the
     * Child and the named keyed entries keep reading theirs. Red-first: every refusal loaded clean
     * before E5.
     */
    @Test
    void aJoinTypeOnAnEntryNothingReadsIsALoadError() throws IOException
    {
        assertError(load("{\"Name\":\"DS\",\"Join_Type\":\"left\"}", ""),
                "Join_Type 'left' on Match_Datasets entry 'DS' has no effect");
        assertError(load("{\"Name\":\"DS\",\"Join_Type\":\"inner\"}", ""),
                "Join_Type 'inner' on Match_Datasets entry 'DS' has no effect");
        assertError(load("{\"Keys\":[\"STUDYID\",\"USUBJID\"],\"Join_Type\":\"left\"}", ""),
                "Join_Type 'left' on Match_Datasets entry without a Name has no effect");
        // controls: the same value where a reader exists
        assertClean(load("{\"Name\":\"DS\"}", ""));
        assertClean(load("{\"Name\":\"RELREC\",\"Join_Type\":\"left\"}", ""));
        assertClean(load("{\"Name\":\"RELREC\",\"Join_Type\":\"inner\"}", ""));
        assertClean(load(DM.formatted(",\"Join_Type\":\"left\""), ""));
        assertClean(load(CHILD.formatted(",\"Join_Type\":\"left\""), ""));
    }
}
