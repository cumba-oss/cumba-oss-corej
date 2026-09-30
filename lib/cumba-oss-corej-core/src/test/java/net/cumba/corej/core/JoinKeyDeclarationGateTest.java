package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * The join-key declaration gate in the LOADER ({@code PLAN-rule-unknown-keys-gate} §5.7, carrying
 * {@code PLAN-join-key-authoring-gate}'s owner-ruled Q3 "arm the ratchet"): a keyed
 * {@code Match_Datasets} entry whose key columns are not declared in {@code Requirements.Variables}
 * is a per-rule load error naming the rule, the entry and the key. The corpus lint
 * {@code JoinKeyDeclarationLintTest} pins the same shape rules over the authored corpus; this gate
 * reaches user and external packages, which the lint cannot see.
 *
 * <p>
 * Each shape has its well-formed twin (the control) and its sabotage — one declaration removed —
 * which must red naming rule and key: the same pairs the lint runs.
 * </p>
 */
class JoinKeyDeclarationGateTest
{

    private static Rule load(String members) throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString("{\"rules\":{\"x\":{" + members + "}}}")
                .getRules().get("x");
        assertNotNull(rule);
        return rule;
    }


    private static String rule(String id, String requirements, String matchDatasets)
    {
        return "\"Core\":{\"Id\":\"" + id + "\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Requirements\":{\"Variables\":{" + requirements + "}},\"Match_Datasets\":["
                + matchDatasets + "],"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]},"
                + "\"Check\":{\"expression\":\"not empty(USUBJID)\"}";
    }

    /** The {@code CDISC-AD0898}-shaped directive for a {@code &DOM&} template entry. */
    private static final String DOM_DIRECTIVE = "{\"token\":\"&DOM&\",\"over\":\"domain_from_variable\","
            + "\"pattern\":\"&DOM&SEQ\"}";

    /**
     * Adds a minimal {@code Expansion:} block to a {@code rule(...)} body whose Match_Datasets
     * carry a template entry — hygiene, so the gate under test is the only thing that can red the
     * rule: an undeclared token is a G2 load error of its own
     * ({@code PLAN-expansion-token-delimiters}), and {@code assertRedNaming} only tests
     * {@code contains}.
     */
    private static String withExpansion(String body, String directive)
    {
        return body.replace("\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},",
                "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},\"Expansion\":[" + directive
                        + "],");
    }


    private static void assertClean(String members) throws IOException
    {
        Rule rule = load(members);
        assertNull(rule.getLoadError(), "well-formed declaration flagged: " + rule.getLoadError());
    }


    private static String assertRedNaming(String members, String ruleId, String key, String what)
        throws IOException
    {
        String error = load(members).getLoadError();
        assertNotNull(error, "sabotage not reported for " + ruleId + " / " + key);
        assertTrue(
                error.contains("[" + ruleId + "] Match_Datasets") && error.contains(key)
                        && error.contains(what),
                "expected rule " + ruleId + ", key " + key + ", '" + what + "' in: " + error);
        return error;
    }


    @Test
    void singleKeyOrdinaryEntry() throws IOException
    {
        String md = "{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}";
        assertClean(rule("T-SINGLE",
                "\"All\":[\"USUBJID\"],\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]", md));
        assertRedNaming(rule("T-SINGLE", "\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]", md),
                "T-SINGLE", "USUBJID", "first key");
        assertRedNaming(rule("T-SINGLE", "\"All\":[\"USUBJID\"]", md), "T-SINGLE", "DM.USUBJID",
                "no Requirements.Variables.All_Or_None group");
    }


    @Test
    void multiKeyEntryNeedsAGroupPerKeyAndOnlySidesInIt() throws IOException
    {
        String md = "{\"Name\":\"TV\",\"Keys\":[\"VISITNUM\",\"VISIT\",\"VISITDY\"]}";
        String good = "\"All\":[\"VISITNUM:N\"],\"All_Or_None\":[[\"VISITNUM\",\"TV.VISITNUM\"],"
                + "[\"VISIT\",\"TV.VISIT\"],[\"VISITDY\",\"TV.VISITDY\"]]";
        assertClean(rule("T-MULTI", good, md));
        assertRedNaming(rule("T-MULTI", good.replace(",[\"VISITDY\",\"TV.VISITDY\"]", ""), md),
                "T-MULTI", "TV.VISITDY", "no Requirements.Variables.All_Or_None group");
        assertRedNaming(
                rule("T-MULTI",
                        good.replace("[\"VISITNUM\",\"TV.VISITNUM\"]",
                                "[\"VISITNUM\",\"TV.VISITNUM\",\"SV.EPOCH\"]"),
                        md),
                "T-MULTI", "SV.EPOCH", "not a side of key VISITNUM");
    }


    @Test
    void sidedKeyPairsLeftWithDatasetDotRight() throws IOException
    {
        String md = "{\"Name\":\"TV\",\"Keys\":[{\"left\":\"VISITDY\",\"right\":\"TVSTRL\"}]}";
        assertClean(rule("T-SIDED",
                "\"All\":[\"VISITDY\"],\"All_Or_None\":[[\"VISITDY\",\"TV.TVSTRL\"]]", md));
        assertRedNaming(
                rule("T-SIDED",
                        "\"All\":[\"VISITDY\"],\"All_Or_None\":[[\"VISITDY\",\"TV.VISITDY\"]]", md),
                "T-SIDED", "TV.TVSTRL", "no Requirements.Variables.All_Or_None group");
    }


    @Test
    void oneGroupMayPairOneKeyWithSeveralJoinedDatasets() throws IOException
    {
        String md = "{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]},{\"Name\":\"AE\",\"Keys\":[\"USUBJID\"]}";
        assertClean(rule("T-TWO",
                "\"All\":[\"USUBJID\"],\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\",\"AE.USUBJID\"]]",
                md));
        assertRedNaming(
                rule("T-TWO",
                        "\"All\":[\"USUBJID\"],\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]", md),
                "T-TWO", "AE.USUBJID", "no Requirements.Variables.All_Or_None group");
    }


    @Test
    void childKeysAreBareInAllAndNeverGrouped() throws IOException
    {
        String md = "{\"Name\":\"SUPP--\",\"Child\":true,\"Keys\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]}";
        assertClean(rule("T-CHILD", "\"All\":[\"QNAM\",\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]", md));
        assertRedNaming(rule("T-CHILD", "\"All\":[\"QNAM\",\"USUBJID\",\"IDVAR\"]", md), "T-CHILD",
                "IDVARVAL", "Child key");
        assertRedNaming(rule("T-CHILD",
                "\"All\":[\"QNAM\",\"USUBJID\",\"IDVAR\",\"IDVARVAL\"],\"All_Or_None\":[[\"IDVAR\",\"AE.IDVAR\"]]",
                md), "T-CHILD", "IDVAR", "named by an All_Or_None group");
    }


    @Test
    void aChildKeyAlsoKeyingAnOrdinaryEntryMayBeGrouped() throws IOException
    {
        // The exception (that plan's review round 2, M1): the DM entry's group legitimately names
        // USUBJID; remove the DM entry and the group is illegal again.
        String both = "{\"Name\":\"SUPP--\",\"Child\":true,\"Keys\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]},"
                + "{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}";
        String req = "\"All\":[\"QNAM\",\"USUBJID\",\"IDVAR\",\"IDVARVAL\"],\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]";
        assertClean(rule("T-CHILD-DM", req, both));
        assertRedNaming(rule("T-CHILD-DM", req,
                "{\"Name\":\"SUPP--\",\"Child\":true,\"Keys\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]}"),
                "T-CHILD-DM", "USUBJID", "named by an All_Or_None group");
        // ...and an expansion template sharing the key does NOT license the group (round 3, T1).
        assertRedNaming(withExpansion(rule("T-CHILD-TPL",
                "\"All\":[\"QNAM\",\"USUBJID\",\"IDVAR\",\"IDVARVAL\",\"STUDYID\"],\"All_Or_None\":[[\"USUBJID\",\"XX.USUBJID\"]]",
                "{\"Name\":\"SUPP--\",\"Child\":true,\"Keys\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]},"
                        + "{\"Name\":\"&DOM&\",\"Keys\":[\"STUDYID\",\"USUBJID\",\"&DOM&SEQ\"]}"),
                DOM_DIRECTIVE), "T-CHILD-TPL", "USUBJID", "named by an All_Or_None group");
    }


    @Test
    void anInertEntryLicensesNoGroup() throws IOException
    {
        // Review round 5, F1: a NAMELESS ordinary entry is exempt from the gate and inert at run
        // time (RuleRunner.buildJoinedDatasets skips it), so it must not license a group naming a
        // Child key — before this, Child USUBJID + {Keys:[USUBJID]} + [USUBJID, FOO.BAR] loaded
        // clean.
        String req = "\"All\":[\"QNAM\",\"USUBJID\",\"IDVAR\",\"IDVARVAL\"],\"All_Or_None\":[[\"USUBJID\",\"FOO.BAR\"]]";
        assertRedNaming(rule("T-CHILD-INERT", req,
                "{\"Name\":\"SUPP--\",\"Child\":true,\"Keys\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]},"
                        + "{\"Keys\":[\"USUBJID\"]}"),
                "T-CHILD-INERT", "USUBJID", "named by an All_Or_None group");
        // Round 5, F2 (the reviewer's input, agreed with the lint): an entry keyed on a token on
        // its RIGHT side is an expansion template — isExpansionTemplateEntry reads both sides —
        // so it licenses nothing either, whatever its name.
        assertRedNaming(withExpansion(rule("T-CHILD-RTPL",
                "\"All\":[\"QNAM\",\"USUBJID\",\"IDVAR\",\"IDVARVAL\"],\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]",
                "{\"Name\":\"SUPP--\",\"Child\":true,\"Keys\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]},"
                        + "{\"Name\":\"DM\",\"Keys\":[{\"left\":\"USUBJID\",\"right\":\"&K&\"}]}"),
                "{\"token\":\"&K&\",\"over\":\"shared_variables\",\"with\":\"DM\"}"),
                "T-CHILD-RTPL", "USUBJID", "named by an All_Or_None group");
    }


    @Test
    void anExpansionTemplateDeclaresOnlyItsFirstBareKey() throws IOException
    {
        // Q5: token keys and the &DOM&. half are exempt by construction (gate R6 bars them).
        String md = "{\"Name\":\"&DOM&\",\"Keys\":[\"STUDYID\",\"USUBJID\",\"&DOM&SEQ\"]}";
        String body = rule("T-TEMPLATE", "\"All\":[\"STUDYID\"]", md).replace(
                "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},",
                "\"Scope\":{\"Domains\":{\"Include\":[\"ADAE\"]}},\"Expansion\":[{\"token\":\"&DOM&\","
                        + "\"over\":\"domain_from_variable\",\"pattern\":\"&DOM&SEQ\"}],");
        Rule rule = load(body.replace("\"Check\":{\"expression\":\"not empty(USUBJID)\"}",
                "\"Check\":{\"expression\":\"not empty(&DOM&SEQ)\"}"));
        String error = rule.getLoadError();
        assertTrue(error == null || !error.contains("Match_Datasets '&DOM&'"),
                "the template entry declares STUDYID bare and nothing else: " + error);
        String sabotaged = body.replace("\"All\":[\"STUDYID\"]", "\"All\":[]").replace(
                "\"Check\":{\"expression\":\"not empty(USUBJID)\"}",
                "\"Check\":{\"expression\":\"not empty(&DOM&SEQ)\"}");
        assertRedNaming(sabotaged, "T-TEMPLATE", "STUDYID", "first key");
    }


    @Test
    void anEntryWithoutKeysDeclaresNothing() throws IOException
    {
        assertClean(rule("T-NOKEYS", "\"All\":[\"USUBJID\"]", "{\"Name\":\"RELREC\"}"));
        assertClean("\"Core\":{\"Id\":\"T-NOREQ\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Match_Datasets\":[{\"Name\":\"RELREC\"}],"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]},"
                + "\"Check\":{\"expression\":\"not empty(USUBJID)\"}");
    }


    @Test
    void typeSuffixesAndCaseFoldLikeTheLint() throws IOException
    {
        String md = "{\"Name\":\"DM\",\"Keys\":[\"usubjid\"]}";
        assertClean(rule("T-FOLD",
                "\"All\":[\"USUBJID:C\"],\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]", md));
        String error = load(rule("T-FOLD",
                "\"All\":[\"USUBJID:C\"],\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]", md))
                        .getLoadError();
        assertFalse(error != null && error.contains("Match_Datasets"), String.valueOf(error));
    }


    @Test
    void aNamelessChildEntryIsJudgedLikeANamedOne() throws IOException
    {
        // Review round 4, G1: ChildMatchPreMerger.applicableChildKeys falls back to the FIRST
        // Child entry whatever its name, so a nameless Child entry is joined on and must declare
        // its keys — the corpus lint judges it (String.valueOf(name)); the loader used to skip it.
        String md = "{\"Child\":true,\"Keys\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]}";
        assertClean(rule("T-CHILD-NONAME", "\"All\":[\"USUBJID\",\"IDVAR\",\"IDVARVAL\"]", md));
        String error = assertRedNaming(
                rule("T-CHILD-NONAME", "\"All\":[\"USUBJID\",\"IDVAR\"]", md), "T-CHILD-NONAME",
                "IDVARVAL", "Child key");
        assertTrue(error.contains("Match_Datasets '<unnamed>'"), error);
        // An ORDINARY entry without a name stays exempt: it has no NAME.RIGHT side to declare.
        assertClean(rule("T-NONAME", "\"All\":[\"USUBJID\"]", "{\"Keys\":[\"USUBJID\"]}"));
    }


    @Test
    void aMisspeltRequirementsFacetIsOneErrorNotTwo() throws IOException
    {
        // Review round 4, G3: `All_or_None` is R2's unknown-key error, with its hint. Judging the
        // declaration against the facet the author misspelt would report the same typo again as
        // "no All_Or_None group". One typo, one error.
        String md = "{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"]}";
        String error = load(rule("T-TYPO",
                "\"All\":[\"USUBJID\"],\"All_or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]", md))
                        .getLoadError();
        assertNotNull(error);
        assertTrue(error.contains("unknown key 'All_or_None' under 'Requirements.Variables'")
                && error.contains("did you mean 'All_Or_None'?"), error);
        assertFalse(error.contains("no Requirements.Variables.All_Or_None group"),
                "the declaration must not be judged against a misspelt facet: " + error);
        // ...and one level up: a misspelt `Variables` block is R2's error alone, too.
        String req = load(rule("T-TYPO-REQ", "\"All\":[\"USUBJID\"]", md)
                .replace("\"Requirements\":{\"Variables\":", "\"Requirements\":{\"Variabels\":"))
                        .getLoadError();
        assertNotNull(req);
        assertTrue(req.contains("unknown key 'Variabels' under 'Requirements'"), req);
        assertFalse(req.contains("Match_Datasets 'DM'"), req);
        // Control: the same shape with the facet spelt right and the group missing IS the §5.7
        // error — the suppression is keyed on the typo, not on the block.
        assertRedNaming(rule("T-TYPO-CTRL", "\"All\":[\"USUBJID\"]", md), "T-TYPO-CTRL",
                "DM.USUBJID", "no Requirements.Variables.All_Or_None group");
    }


    /**
     * The gate's population floor over the corpus it can see (review round 4, H6). The corpus lint
     * pins the keyed entries of the authored corpus, which lives in another repository; here the
     * same census runs over the engine's own rule package
     * ({@code rules/rulepackageloader-fixture.json}, a frozen, hand-maintained snapshot of seven
     * published rules that nothing syncs with the corpus) — every keyed entry of it loads with no
     * §5.7 error, and the three counts are pinned so a fixture edit that removes the last entry of
     * a kind cannot leave that arm untested in silence. The per-arm sabotage pairs above are what
     * prove each arm fires.
     */
    @Test
    void theResourcePackageIsDeclaredAndItsKeyedPopulationIsPinned() throws IOException
    {
        Path file = Path.of(System.getProperty("projectBasedir"),
                "src/test/resources/rules/rulepackageloader-fixture.json");
        int ordinary = 0;
        int child = 0;
        int template = 0;
        RulePackage pkg = RulePackageLoader.loadFromString(Files.readString(file));
        for (Rule rule : pkg.getRules().values())
        {
            String error = rule.getLoadError();
            assertTrue(error == null || !error.contains("Match_Datasets"),
                    file.getFileName() + ": " + error);
            if (rule.getMatchDatasets() == null)
            {
                continue;
            }
            for (MatchDataset md : rule.getMatchDatasets())
            {
                if (md.getKeys() == null || md.getKeys().isEmpty())
                {
                    continue;
                }
                boolean token = String.valueOf(md.getName()).startsWith("&")
                        || md.getKeys().stream().anyMatch(k -> k.startsWith("&"));
                if (token)
                {
                    template++;
                }
                else if (Boolean.TRUE.equals(md.getChild()))
                {
                    child++;
                }
                else
                {
                    ordinary++;
                }
            }
        }
        assertEquals(4, ordinary, "ordinary keyed entries in the resource package");
        assertEquals(3, child, "Child keyed entries in the resource package");
        assertEquals(0, template, "expansion-template keyed entries (none authored in it)");
    }
}
