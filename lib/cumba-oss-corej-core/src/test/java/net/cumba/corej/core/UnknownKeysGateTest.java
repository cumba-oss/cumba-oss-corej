package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-rule-unknown-keys-gate} — owner, 2026-09-25: <i>"unknown keys in a rule should always
 * result in a load error"</i>. One case per JSON object shape a rule can contain, each planting one
 * key the model does not bind and asserting a per-rule {@link Rule#getLoadError() loadError} naming
 * the key <b>and its path</b> — while a clean sibling rule in the same package stays clean.
 *
 * <p>
 * ⭐ Why: the loader's mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled. Before this
 * gate only {@code Scope} / {@code Scope.Datasets} / {@code Requirements} /
 * {@code Requirements.Variables} (gate R2), {@code Match_Datasets[]} and the retired / threshold
 * spellings noticed an unbound key; a key planted at 15 other levels of a real v0.4.0 package
 * loaded clean and the rules ran on their defaults — {@code Outcome.Mesage} reported with no
 * message, {@code Scope.Domains.X} ran against every dataset, a key beside {@code expression:} in
 * {@code Check} was discarded by the condition grammar (it dispatches on the first grammar key and
 * never looks at the rest).
 * </p>
 *
 * <p>
 * ⚑ <b>Red-first, demonstrated:</b> this class was run against the unmodified engine before the
 * gate existed; the reds are recorded in the plan's status file. {@link #everyBoundKeyStillLoads()}
 * is the positive control without which a gate that errors on everything would pass every case. The
 * <i>no double report</i> cases pin that a key already reported <b>by name</b> elsewhere (retired
 * spellings, threshold spellings, {@code Scope.Variables}, R2's blocks) yields exactly one message.
 * </p>
 */
class UnknownKeysGateTest
{

    /** A clean sibling that must stay clean whatever is planted on rule {@code x}. */
    private static final String CLEAN_SIBLING = """
            "y":{"Core":{"Id":"T-CLEAN"},"Sensitivity":"Record",\
            "Scope":{"Domains":{"Include":["AE"]}},\
            "Outcome":{"Message":"m","Output_Variables":["AESEQ"]},\
            "Check":{"expression":"not empty(AESEQ)"}}""";

    /**
     * Loads a package holding rule {@code x} (built from the given members, comma-separated JSON)
     * beside the clean sibling, and asserts the sibling is untouched.
     */
    private static Rule loadX(String xMembers) throws IOException
    {
        RulePackage pkg = RulePackageLoader
                .loadFromString("{\"rules\":{\"x\":{" + xMembers + "}," + CLEAN_SIBLING + "}}");
        Rule y = pkg.getRules().get("y");
        assertNotNull(y);
        assertNull(y.getLoadError(), "the clean sibling must stay clean: " + y.getLoadError());
        Rule x = pkg.getRules().get("x");
        assertNotNull(x, "the fixture must bind, or nothing below is measuring anything");
        return x;
    }


    /**
     * {@link #plain} without its {@code Outcome}, for a hint that must not be "already present".
     */
    private static String withoutOutcome(String extraMembers)
    {
        return "\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\"}"
                + (extraMembers.isEmpty() ? "" : "," + extraMembers);
    }


    /** A plain rule body: everything a rule needs, plus the given extra members. */
    private static String plain(String extraMembers)
    {
        return "\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\"}"
                + (extraMembers.isEmpty() ? "" : "," + extraMembers);
    }


    private static String errorOf(String xMembers) throws IOException
    {
        String error = loadX(xMembers).getLoadError();
        assertNotNull(error, "expected a load error for: " + xMembers);
        return error;
    }


    private static void assertUnknownAt(String error, String key, String where)
    {
        String expected = "[T-UKG] unknown key '" + key + "' " + where;
        assertTrue(error.contains(expected), "expected <" + expected + "> in: " + error);
        assertTrue(error.contains("binds to nothing"), error);
    }


    private static int count(String haystack, String needle)
    {
        int n = 0;
        for (int i = haystack.indexOf(needle); i >= 0; i = haystack.indexOf(needle, i + 1))
        {
            n++;
        }
        return n;
    }

    // ---- the top level -------------------------------------------------------


    @Test
    void aTopLevelUnknownKeyIsALoadErrorWithACaseHint() throws IOException
    {
        String error = errorOf(withoutOutcome("\"Outcom\":{\"Message\":\"typo\"}"));
        assertUnknownAt(error, "Outcom", "at the top level of the rule");
        assertTrue(error.contains("did you mean 'Outcome'?"), error);
        // ...and beside a present Outcome the hint would name what the author already has (E3).
        String beside = errorOf(plain("\"Outcom\":{\"Message\":\"typo\"}"));
        assertUnknownAt(beside, "Outcom", "at the top level of the rule");
        assertFalse(beside.contains("did you mean"), beside);
    }


    @Test
    void aLowerCaseSpellingOfABoundKeyIsUnknownWithTheHint() throws IOException
    {
        // T1-3 (a): case-sensitive, reject, hint — never bind case-insensitively.
        String error = errorOf(withoutOutcome("\"outcome\":{\"Message\":\"typo\"}"));
        assertUnknownAt(error, "outcome", "at the top level of the rule");
        assertTrue(error.contains("did you mean 'Outcome'?"), error);
    }


    @Test
    void aKeyWithNoCaseNeighbourGetsNoHint() throws IOException
    {
        String error = errorOf(plain("\"Foo\":1"));
        assertUnknownAt(error, "Foo", "at the top level of the rule");
        assertFalse(error.contains("did you mean"), error);
        assertTrue(error.contains("check the spelling"), error);
    }


    @Test
    void aRetiredSpellingIsNeverOfferedAsAHint() throws IOException
    {
        // `operations` is one case off `Operations`, which is itself rejected on the next load —
        // suggesting it would name a remedy that fails (review L1).
        String error = errorOf(plain("\"operations\":[]"));
        assertUnknownAt(error, "operations", "at the top level of the rule");
        assertFalse(error.contains("did you mean"), error);
        String error2 = errorOf(plain("\"rule_type\":\"Record Data\""));
        assertUnknownAt(error2, "rule_type", "at the top level of the rule");
        assertFalse(error2.contains("did you mean"), error2);
    }


    @Test
    void sourceAtTheTopLevelGetsTheProvenanceAdvice() throws IOException
    {
        // T1-1 (a): not engine format. Every released package is trimmed of it; a hand-converted
        // authored YAML is where it turns up.
        String error = errorOf(plain("\"Source\":{\"Domains\":\"AE\",\"Rule\":\"text\"}"));
        assertUnknownAt(error, "Source", "at the top level of the rule");
        assertTrue(error.contains("authoring provenance"), error);
        assertTrue(error.contains("delete the key"), error);
        String error2 = errorOf(plain("\"Source_Proposed\":{\"Sheet_Issue\":\"x\"}"));
        assertUnknownAt(error2, "Source_Proposed", "at the top level of the rule");
        assertTrue(error2.contains("authoring provenance"), error2);
    }

    // ---- the nested blocks ---------------------------------------------------


    @Test
    void coreVersoin() throws IOException
    {
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\",\"Versoin\":\"1\"},"
                + "\"Sensitivity\":\"Record\",\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\"}");
        assertUnknownAt(error, "Versoin", "under 'Core'");
        assertTrue(error.contains("did you mean 'Version'?"), error);
    }


    @Test
    void outcomeMesage() throws IOException
    {
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Mesage\":\"m2\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\"}");
        assertUnknownAt(error, "Mesage", "under 'Outcome'");
        assertTrue(error.contains("did you mean 'Message'?"), error);
    }


    @Test
    void executabilityHintX() throws IOException
    {
        String error = errorOf(
                plain("\"ExecutabilityHint\":{\"Category\":\"Library\",\"Detail\":\"d\",\"X\":1}"));
        assertUnknownAt(error, "X", "under 'ExecutabilityHint'");
    }


    @Test
    void authoritiesDeepPathsAreIndexed() throws IOException
    {
        String error = errorOf(plain("\"Authorities\":[" + "{\"Organization\":\"CDISC\"},"
                + "{\"Organization\":\"FDA\",\"Standards\":[{\"Name\":\"SDTMIG\",\"Version\":\"3.4\","
                + "\"References\":[{\"Origin\":\"o\",\"Rule_Identifier\":{\"Id\":\"SD1\",\"X\":1},"
                + "\"Citations\":[{\"Document\":\"d\",\"Y\":2}],\"Z\":3}],\"W\":4}],\"V\":5}]"));
        assertUnknownAt(error, "X",
                "under 'Authorities[1].Standards[0].References[0].Rule_Identifier'");
        assertUnknownAt(error, "Y",
                "under 'Authorities[1].Standards[0].References[0].Citations[0]'");
        assertUnknownAt(error, "Z", "under 'Authorities[1].Standards[0].References[0]'");
        assertUnknownAt(error, "W", "under 'Authorities[1].Standards[0]'");
        assertUnknownAt(error, "V", "under 'Authorities[1]'");
        assertFalse(error.contains("Authorities[0]"), "the clean entry is not blamed: " + error);
    }


    @Test
    void scopeChildrenEachReportTheirOwnPath() throws IOException
    {
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Classes\":{\"Include\":[\"EVENTS\"],\"A\":1},"
                + "\"Domains\":{\"Include\":[\"AE\"],\"B\":1},"
                + "\"Data_Structures\":{\"Include\":[\"BASIC DATA STRUCTURE\"],\"C\":1},"
                + "\"Subclasses\":{\"Include\":[\"ADVERSE EVENT\"],\"D\":1}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\"}");
        assertUnknownAt(error, "A", "under 'Scope.Classes'");
        assertUnknownAt(error, "B", "under 'Scope.Domains'");
        assertUnknownAt(error, "C", "under 'Scope.Data_Structures'");
        assertUnknownAt(error, "D", "under 'Scope.Subclasses'");
    }


    @Test
    void scopeIncludeSplitDatasetsBesideDomainsStaysR2sMessage() throws IOException
    {
        // The authoring guidelines' named trap. Already red through R2's Scope arm; pinned so the
        // new walker never reports it a second time.
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"LB\"]},\"include_split_datasets\":true},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"LBSEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(LBSEQ)\"}");
        assertTrue(error.contains("unknown key 'include_split_datasets' under 'Scope'"), error);
        assertTrue(error.contains("is not required"), "R2's own wording: " + error);
        assertEquals(1, count(error, "'include_split_datasets'"), "reported once: " + error);
    }


    @Test
    void groupingX() throws IOException
    {
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Group\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Grouping\":{\"Variables\":[\"USUBJID\"],\"keep_missings\":true,\"X\":1},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\"}");
        assertUnknownAt(error, "X", "under 'Grouping'");
    }


    @Test
    void expansionEntriesAreIndexed() throws IOException
    {
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"ADAE\"]}},"
                + "\"Expansion\":[{\"token\":\"&VAR\",\"over\":\"shared_variables\",\"with\":\"ADSL\"},"
                + "{\"token\":\"&OTH\",\"over\":\"all_variables\",\"X\":1}],"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]},"
                + "\"Check\":{\"expression\":\"not empty(`&VAR`) and not empty(`&OTH`)\"}");
        assertUnknownAt(error, "X", "under 'Expansion[1]'");
        assertFalse(error.contains("Expansion[0]"), error);
    }


    @Test
    void wildcardFiltersAreNamedByTheirGroup() throws IOException
    {
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"ADSL\"]}},"
                + "\"wildcards\":{\"xx\":{\"min\":1,\"max\":9,\"X\":1}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"TRTxxP\"]},"
                + "\"Check\":{\"all\":[{\"expression\":\"var_exists(\\\"TRTxxP\\\")\"}]}");
        assertUnknownAt(error, "X", "under 'wildcards.xx'");
    }


    @Test
    void aSidedKeyElementWithAnExtraKeyIsIndexed() throws IOException
    {
        String error = errorOf(plain("\"Match_Datasets\":[{\"Name\":\"DM\","
                + "\"Keys\":[\"USUBJID\",{\"left\":\"AESEQ\",\"right\":\"DMSEQ\",\"X\":1}]}]"));
        assertUnknownAt(error, "X", "under 'Match_Datasets[0].Keys[1]'");
    }

    // ---- the Check grammar (T1-5 a: per rule) --------------------------------


    @Test
    void aKeyBesideExpressionInAPlainCheckIsALoadError() throws IOException
    {
        // Before: the condition grammar dispatched on `expression` and discarded `Mesage`.
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\",\"Mesage\":\"m\"}");
        assertTrue(error.contains("[T-UKG] unknown key 'Mesage' under 'Check'"), error);
        assertTrue(error.contains("exactly one of all/any/not/expression"), error);
    }


    @Test
    void aLevelShapedKeyBesideExpressionKeepsTheUnknownLevelMessage() throws IOException
    {
        // Review M1: `X` matches LEVEL_SHAPED and already fails as an unknown level today; the
        // stray-key walk runs AFTER that check, so the more specific message is kept.
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\",\"X\":1}");
        assertTrue(error.contains("unknown check level(s) [X]"), error);
        assertFalse(error.contains("unknown key"), error);
    }


    @Test
    void aNestedCompositeMemberIsIndexed() throws IOException
    {
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"all\":[{\"expression\":\"not empty(AESEQ)\"},"
                + "{\"expression\":\"not empty(AETERM)\",\"X\":1}]}");
        assertTrue(error.contains("[T-UKG] unknown key 'X' under 'Check.all[1]'"), error);
        assertFalse(error.contains("Check.all[0]"), error);
    }


    @Test
    void aNotMemberIsPathed() throws IOException
    {
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"not\":{\"expression\":\"empty(AESEQ)\",\"X\":1}}");
        assertTrue(error.contains("[T-UKG] unknown key 'X' under 'Check.not'"), error);
    }


    @Test
    void anExpressionBesideAllIsTheLostKey() throws IOException
    {
        // `all` dispatched first; the `expression` was silently lost.
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"all\":[{\"expression\":\"not empty(AESEQ)\"}],"
                + "\"expression\":\"not empty(AETERM)\"}");
        assertTrue(error.contains("[T-UKG] unknown key 'expression' under 'Check'"), error);
    }


    @Test
    void aKeyInALevelEntryBesideMessageIsPathedByLevel() throws IOException
    {
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Severity\":\"Error\",\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"ERROR\":{\"expression\":\"not empty(AESEQ)\",\"Message\":\"m\","
                + "\"X\":1}}");
        assertTrue(error.contains("[T-UKG] unknown key 'X' under 'Check.ERROR'"), error);
        assertFalse(error.contains("'Message'"), "Message is a legal level key: " + error);
    }


    @Test
    void aKeyBesideAPreconditionConditionIsAPerRuleLoadError() throws IOException
    {
        // Review E6 / E7: Precondition binds through PreconditionDeserializer now — the same
        // carried-error shape as Check — so one typo costs one rule, never the package.
        String error = errorOf(
                plain("\"Precondition\":{\"expression\":\"library_available()\",\"X\":1}"));
        assertTrue(error.contains("[T-UKG] unknown key 'X' under 'Precondition'"), error);
        assertTrue(error.contains("exactly one of all/any/not/expression"), error);
    }


    @Test
    void aParkedRuleWithAPreconditionTypoIsDroppedWithTheRule() throws IOException
    {
        // Q-1 (owner: no): parked rules are out of the gate. Before E6 the typo failed the whole
        // package at parse, before removeParkedRules could drop the rule.
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"x\":{"
                + plain("\"Executability\":\"Not Executable\","
                        + "\"Precondition\":{\"expression\":\"library_available()\",\"X\":1}")
                + "}," + CLEAN_SIBLING + "}}");
        assertNull(pkg.getRules().get("x"), "the parked rule is removed");
        assertNull(pkg.getRules().get("y").getLoadError(), "the sibling is untouched");
    }


    @Test
    void aMisspeltDispatchKeyInsideALevelIsAPerRuleError() throws IOException
    {
        // Review E7: `expresion` beside Message used to be "not a recognised Check condition"
        // for the whole package, clean siblings included.
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Severity\":\"Error\",\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"ERROR\":{\"Message\":\"m\",\"expresion\":\"not empty(AESEQ)\"}}");
        assertTrue(error.contains("[T-UKG] no condition key under 'Check.ERROR'"), error);
        assertTrue(error.contains("'expresion': did you mean 'expression'?"), error);
        String plainCase = errorOf(
                plain("").replace("\"Check\":{\"expression\"", "\"Check\":{\"expresion\""));
        assertTrue(plainCase.contains("no condition key under 'Check'"), plainCase);
        String nested = errorOf(plain("").replace("\"Check\":{\"expression\":\"not empty(AESEQ)\"}",
                "\"Check\":{\"all\":[{\"expresion\":\"not empty(AESEQ)\"}]}"));
        assertTrue(nested.contains("no condition key under 'Check.all[0]'"), nested);
    }


    @Test
    void aStrayConditionKeyGetsTheHint() throws IOException
    {
        String error = errorOf(plain("").replace("\"Check\":{\"expression\":\"not empty(AESEQ)\"}",
                "\"Check\":{\"all\":[{\"expression\":\"not empty(AESEQ)\"}],\"Any\":[]}"));
        assertTrue(error.contains("unknown key 'Any' under 'Check'; did you mean 'any'?"), error);
    }

    // ---- the package level (T1-6 a) ------------------------------------------


    @Test
    void aPackageLevelUnknownKeyFailsThePackageLoad()
    {
        IOException ex = assertThrows(IOException.class, () -> RulePackageLoader
                .loadFromString("{\"X\":1,\"rules\":{" + CLEAN_SIBLING + "}}"));
        assertTrue(ex.getMessage().contains("unknown key 'X'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("'rules'") && ex.getMessage().contains("'standards'"),
                ex.getMessage());
    }


    @Test
    void aStandardsEntryExtraKeyFailsThePackageLoadNamingItsIndex()
    {
        IOException ex = assertThrows(IOException.class,
                () -> RulePackageLoader.loadFromString("{\"standards\":[{\"id\":\"sdtmig/3-4\","
                        + "\"role\":\"primary\"},{\"id\":\"adam/adamig-1-3\",\"X\":1}],"
                        + "\"rules\":{" + CLEAN_SIBLING + "}}"));
        assertTrue(ex.getMessage().contains("unknown key 'X'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("standards[1]"), ex.getMessage());
        assertTrue(ex.getMessage().contains("'id'") && ex.getMessage().contains("'role'"),
                ex.getMessage());
    }


    @Test
    void aWellFormedStandardsListStillBindsThroughTheRecord() throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadFromString(
                "{\"standards\":[{\"id\":\"sdtmig/3-4\",\"role\":\"companion\"},{\"id\":\"x\"}],"
                        + "\"rules\":{" + CLEAN_SIBLING + "}}");
        assertNotNull(pkg.getStandards());
        assertEquals(2, pkg.getStandards().size());
        assertEquals("sdtmig/3-4", pkg.getStandards().get(0).id());
        assertEquals(net.cumba.corej.core.model.StandardRef.Role.COMPANION,
                pkg.getStandards().get(0).role());
        assertEquals(net.cumba.corej.core.model.StandardRef.Role.PRIMARY,
                pkg.getStandards().get(1).role(), "the record's default still applies");
    }

    // ---- the hint everywhere (review E2 / E3) ---------------------------------


    @Test
    void theR2BlocksHintTooScopeDomains() throws IOException
    {
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\"}");
        assertTrue(error.contains("unknown key 'domains' under 'Scope'"), error);
        assertTrue(error.contains("did you mean 'Domains'?"), error);
        String req = errorOf(plain("\"Requirements\":{\"Variables\":{\"Al\":[\"AESEQ\"]}}"));
        assertTrue(req.contains("did you mean 'All'?"), req);
    }


    @Test
    void theMatchDatasetsGateHintsToo() throws IOException
    {
        String error = errorOf(plain("\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],"
                + "\"Join_type\":\"left\"}]"));
        assertTrue(error.contains("unknown key 'Join_type' under 'Match_Datasets[0]'"), error);
        assertTrue(error.contains("did you mean 'Join_Type'?"), error);
    }


    @Test
    void thePackageArmsHintToo()
    {
        IOException pkg = assertThrows(IOException.class,
                () -> RulePackageLoader.loadFromString("{\"Rules\":{" + CLEAN_SIBLING + "}}"));
        assertTrue(pkg.getMessage().contains("unknown key 'Rules'"), pkg.getMessage());
        assertTrue(pkg.getMessage().contains("did you mean 'rules'?"), pkg.getMessage());
        IOException std = assertThrows(IOException.class,
                () -> RulePackageLoader.loadFromString("{\"standards\":[{\"Id\":\"sdtmig/3-4\"}],"
                        + "\"rules\":{" + CLEAN_SIBLING + "}}"));
        assertTrue(std.getMessage().contains("unknown key 'Id' under 'standards[0]'"),
                std.getMessage());
        assertTrue(std.getMessage().contains("did you mean 'id'?"), std.getMessage());
    }


    @Test
    void aHintNeverProposesAKeyTheObjectAlreadyCarries() throws IOException
    {
        // Review E3: the sided element has `left`; hinting it would tell the author to write
        // what is already there.
        String error = errorOf(plain("\"Match_Datasets\":[{\"Name\":\"DM\","
                + "\"Keys\":[{\"left\":\"AESEQ\",\"right\":\"DMSEQ\",\"lfet\":\"X\"}]}]"));
        assertTrue(error.contains("unknown key 'lfet' under 'Match_Datasets[0].Keys[0]'"), error);
        assertFalse(error.contains("did you mean"), error);
        // ...and a bean too: Outcome already has Message, so Mesage beside it gets no hint.
        String bean = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Mesage\":\"m2\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\"}");
        assertTrue(bean.contains("unknown key 'Mesage' under 'Outcome'"), bean);
        assertFalse(bean.contains("did you mean"), bean);
    }


    @Test
    void aNullStandardsEntryIsAStatedError()
    {
        // Review E4: this used to surface as a bare NullPointerException.
        IOException ex = assertThrows(IOException.class, () -> RulePackageLoader
                .loadFromString("{\"standards\":[null],\"rules\":{" + CLEAN_SIBLING + "}}"));
        assertTrue(ex.getMessage().contains("standards[0] is null"), ex.getMessage());
        assertFalse(ex.getMessage().contains("NullPointerException"), ex.getMessage());
    }


    @Test
    void aMisspeltCoreIdIsNamedByThePackageKey() throws IOException
    {
        // Review E5: the very key that is wrong is the rule's identity — the package map key
        // names it instead of "<unknown>".
        RulePackage pkg = RulePackageLoader.loadFromString("{\"rules\":{\"my-rule\":{"
                + "\"Core\":{\"ID\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\"}}}}");
        String error = pkg.getRules().get("my-rule").getLoadError();
        assertNotNull(error);
        assertTrue(error.contains("[my-rule] unknown key 'ID' under 'Core'"), error);
        assertTrue(error.contains("did you mean 'Id'?"), error);
        assertFalse(error.contains("<unknown>"), error);
    }

    // ---- review round 2 (P1-P6) -----------------------------------------------


    @Test
    void aCleanLoadNeverIntrospectsForHints() throws IOException
    {
        // Review P1: presentKeys (a fresh Jackson introspection per block) was 69 % of a clean
        // corpus load when computed before the loop; it is error-path only now.
        long before = RulePackageLoader.PRESENT_KEYS_INTROSPECTIONS.sum();
        RulePackageLoader.loadFromString(FULL_PACKAGE);
        assertEquals(before, RulePackageLoader.PRESENT_KEYS_INTROSPECTIONS.sum(),
                "a clean load must not introspect a single bean for hints");
        loadX(plain("\"Outcom\":1"));
        assertTrue(RulePackageLoader.PRESENT_KEYS_INTROSPECTIONS.sum() > before,
                "the error path does introspect (the counter is live)");
    }


    @Test
    void nonPublicAccessorsCountAsPresent() throws IOException
    {
        // Review P2: Keys (keysNode), Join_As_String (joinAsStringNode) and Any / All_Or_None
        // (writeAny / writeAllOrNone) sit behind non-public accessors and read as absent before —
        // so {Keys, Key} hinted 'Keys'. Present now: no hint. Absent: hint.
        String keys = errorOf(plain("\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],"
                + "\"Key\":[\"X\"]}]"));
        assertTrue(keys.contains("unknown key 'Key' under 'Match_Datasets[0]'"), keys);
        assertFalse(keys.contains("did you mean"), keys);
        String keysAbsent = errorOf(
                plain("\"Match_Datasets\":[{\"Name\":\"DM\",\"Key\":[\"X\"]}]"));
        assertTrue(keysAbsent.contains("did you mean 'Keys'?"), keysAbsent);
        String jas = errorOf(plain("\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],"
                + "\"Join_As_String\":true,\"Join_as_String\":true}]"));
        assertTrue(jas.contains("unknown key 'Join_as_String'"), jas);
        assertFalse(jas.contains("did you mean"), jas);
        String any = errorOf(
                plain("\"Requirements\":{\"Variables\":{\"Any\":[\"AETERM\",\"AEDECOD\"],"
                        + "\"Ayn\":[\"X\"]}}"));
        assertTrue(any.contains("unknown key 'Ayn' under 'Requirements.Variables'"), any);
        assertFalse(any.contains("did you mean"), any);
        String aon = errorOf(plain("\"Requirements\":{\"Variables\":{\"All\":[\"USUBJID\"],"
                + "\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]],\"All_or_None\":[]}}"));
        assertTrue(aon.contains("unknown key 'All_or_None'"), aon);
        assertFalse(aon.contains("did you mean"), aon);
    }


    @Test
    void thePreconditionMessageDoesNotMentionMessage() throws IOException
    {
        // Review P3: Message is legal beside a Check LEVEL's condition only.
        String error = errorOf(
                plain("\"Precondition\":{\"expression\":\"library_available()\",\"X\":1}"));
        assertTrue(error.contains("unknown key 'X' under 'Precondition'"), error);
        assertFalse(error.contains("Message"), error);
        String plainCheck = errorOf(
                plain("").replace("\"Check\":{\"expression\":\"not empty(AESEQ)\"}",
                        "\"Check\":{\"expression\":\"not empty(AESEQ)\",\"X\":1}"));
        assertFalse(plainCheck.contains("may also carry Message"), plainCheck);
        String level = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Severity\":\"Error\",\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"ERROR\":{\"expression\":\"not empty(AESEQ)\",\"X\":1}}");
        assertTrue(level.contains("may also carry Message"), level);
    }


    @Test
    void eachHintIsAttributedToItsKey() throws IOException
    {
        // Review P4: several keys, none a condition keyword — each hint names its key.
        String error = errorOf(plain("").replace("\"Check\":{\"expression\":\"not empty(AESEQ)\"}",
                "\"Check\":{\"expresion\":\"not empty(AESEQ)\",\"nto\":{}}"));
        assertTrue(error.contains("no condition key under 'Check' — found [expresion, nto]"),
                error);
        assertTrue(error.contains("'expresion': did you mean 'expression'?"), error);
        assertTrue(error.contains("'nto': did you mean 'not'?"), error);
    }


    @Test
    void aLevelEntryHintsMessage() throws IOException
    {
        // Review P5: Message is a candidate at a level entry's root.
        String beside = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Severity\":\"Error\",\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"ERROR\":{\"Mesage\":\"e\",\"expression\":\"not empty(AESEQ)\"}}");
        assertTrue(
                beside.contains(
                        "unknown key 'Mesage' under 'Check.ERROR'; did you mean 'Message'?"),
                beside);
        String alone = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Severity\":\"Error\",\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"ERROR\":{\"Mesage\":\"e\"}}");
        assertTrue(alone.contains("no condition key under 'Check.ERROR'"), alone);
        assertTrue(alone.contains("'Mesage': did you mean 'Message'?"), alone);
        // ...but not beside a Message the entry already carries.
        String present = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Severity\":\"Error\",\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"ERROR\":{\"Message\":\"e\",\"Mesage\":\"e\",\"expression\":\"not empty(AESEQ)\"}}");
        assertTrue(present.contains("unknown key 'Mesage' under 'Check.ERROR'"), present);
        assertFalse(present.contains("did you mean"), present);
    }


    @Test
    void aCaseOnlyMatchWinsOverTheAliasOneEditAway() throws IOException
    {
        // Review P6: data_structures is a case-only miss of Data_Structures and one edit from
        // the alias "Data Structures"; the nearer wins.
        String error = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]},\"data_structures\":{\"Include\":[\"BASIC DATA STRUCTURE\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\"}");
        assertTrue(error.contains("unknown key 'data_structures' under 'Scope'"), error);
        assertTrue(error.contains("did you mean 'Data_Structures'?"), error);
    }

    // ---- no double report ----------------------------------------------------


    @Test
    void keysReportedByNameElsewhereAreReportedExactlyOnce() throws IOException
    {
        String retired = errorOf(plain("\"_wildcards\":{\"xx\":{\"min\":2}}"));
        assertEquals(1, count(retired, "'_wildcards'"), retired);
        assertTrue(retired.contains("rename it to 'wildcards'"), retired);
        assertFalse(retired.contains("unknown key"), retired);

        String threshold = errorOf(plain("\"Severity_Threshold\":\"Error\""));
        assertEquals(1, count(threshold, "'Severity_Threshold'"), threshold);
        assertFalse(threshold.contains("unknown key"), threshold);

        String scopeVars = errorOf("\"Core\":{\"Id\":\"T-UKG\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"AE\"]},\"Variables\":{\"Include\":[\"AESEQ\"]}},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"AESEQ\"]},"
                + "\"Check\":{\"expression\":\"not empty(AESEQ)\"}");
        assertTrue(scopeVars.contains("retired field 'Scope.Variables'"), scopeVars);
        assertFalse(scopeVars.contains("unknown key 'Variables'"), scopeVars);

        String req = errorOf(plain("\"Requirements\":{\"Variables\":{\"Al\":[\"AESEQ\"]}}"));
        assertEquals(1, count(req, "'Al'"), req);
        assertTrue(req.contains("is not required"), "R2's own wording is kept: " + req);
        assertFalse(req.contains("is not done"), req);

        String md = errorOf(plain("\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],"
                + "\"Join_type\":\"left\"}]"));
        assertEquals(1, count(md, "'Join_type'"), md);
    }

    // ---- the positive control ------------------------------------------------

    /**
     * The positive-control package: three rules that together author every bound key of every class
     * (the loader forbids some legal combinations on ONE rule — {@code Expansion} with the
     * {@code wildcard*} directives or with engine-owned wildcard markers, a {@code Grouping} block
     * with the flat {@code Grouping_Variables}) plus a {@code standards[]} entry. Also the tree the
     * generic per-class plant of {@link #everyRosterClassIsWalkedByTheGate} plants into.
     */
    private static final String FULL_PACKAGE = "{\"standards\":[{\"id\":\"sdtmig/3-4\","
            + "\"role\":\"primary\"}],\"rules\":{"
            + """

                    "full":{"Core":{"Id":"T-FULL","Status":"Published","Version":"1"},
                      "Description":"d","Sensitivity":"Group","Severity":"Error",
                      "Executability":"Fully Executable","Variable_Universe":"Data",
                      "ExecutabilityHint":{"Category":"Library","Detail":"d"},
                      "Authorities":[{"Organization":"CDISC","Rule_Ids":["CG0001"],
                        "Standards":[{"Name":"SDTMIG","Version":"3.4","Substandard":"s",
                          "References":[{"Origin":"o","Version":"v",
                            "Rule_Identifier":{"Id":"CG0001","Version":"1"},
                            "Citations":[{"Cited_Guidance":"g","Document":"d","Item":"i","Section":"s"}]}]}]}],
                      "Scope":{"Classes":{"Include":["EVENTS"],"Exclude":["FINDINGS"]},
                        "Domains":{"Include":["AE"],"Exclude":["DS"],"include_split_datasets":true},
                        "Datasets":{"Include":["AE"],"Exclude":["AE2"]},
                        "Data_Structures":{"Include":["BASIC DATA STRUCTURE"],"Exclude":["OCCURRENCE DATA STRUCTURE"]},
                        "Subclasses":{"Include":["ADVERSE EVENT"],"Exclude":["TIME-TO-EVENT"]},
                        "Use_Case":"ANALYSIS"},
                      "Requirements":{"Variables":{"All":["AESEQ","USUBJID"],"Any":["AETERM","AEDECOD"],
                          "None":["AEFOO"],"All_Or_None":[["USUBJID","DM.USUBJID"]]},
                        "Datasets":["DM"]},
                      "Bindings":[{"name":"$n","expression":"record_count(group=[USUBJID])"}],
                      "Match_Datasets":[{"Name":"DM","Keys":["USUBJID",{"left":"AESEQ","right":"DMSEQ"}],
                        "Join_Type":"left","Join_As_String":true,"keep_missings":false,"Filter":"not empty(ARM)"}],
                      "Grouping":{"Variables":["USUBJID"],"keep_missings":true},
                      "Precondition":{"expression":"library_available()"},
                      "Outcome":{"Message":"m","Output_Variables":["AESEQ"]},
                      "Check":{"ERROR":{"expression":"not empty(AESEQ) and $n > 0","Message":"e"},
                               "WARNING":{"all":[{"any":[{"not":{"expression":"empty(AETERM)"}}]}]}}},
                    "expansion":{"Core":{"Id":"T-EXP"},"Sensitivity":"Record",
                      "Scope":{"Domains":{"Include":["ADAE"]}},
                      "Expansion":[{"token":"&VAR","over":"shared_variables","with":"ADSL"},
                        {"token":"&DOM","over":"domain_from_variable","pattern":"&DOMSEQ","known_domain_only":true}],
                      "Outcome":{"Message":"m","Output_Variables":["USUBJID"]},
                      "Check":{"expression":"not empty(`&VAR`) and not empty(`&DOMSEQ`)"}},
                    "wild":{"Core":{"Id":"T-WILD"},"Sensitivity":"Group",
                      "Scope":{"Domains":{"Include":["ADSL"]}},
                      "wildcards":{"xx":{"min":1,"max":9}},"wildcardExclude":["TRT01P"],
                      "wildcardPairCatalogue":false,"skipIfLibraryDefined":true,
                      "Grouping_Variables":["USUBJID"],
                      "Outcome":{"Message":"m","Output_Variables":["TRTxxP"]},
                      "Check":{"all":[{"expression":"var_exists(\\"TRTxxP\\")"}]}}
                    """
            + "}}";

    /**
     * Where, in {@link #FULL_PACKAGE}, one instance of each roster class lives — a JSON pointer. ⛔
     * Exact over {@code BoundRuleKeys.BY_CLASS}: a class added to the model without a plant
     * location reds {@link #everyRosterClassIsWalkedByTheGate} (review E1).
     */
    private static final Map<Class<?>, String> PLANT_AT = Map.ofEntries(
            Map.entry(RulePackage.class, ""),
            Map.entry(net.cumba.corej.core.model.StandardRef.class, "/standards/0"),
            Map.entry(Rule.class, "/rules/full"),
            Map.entry(net.cumba.corej.core.model.RuleCore.class, "/rules/full/Core"),
            Map.entry(net.cumba.corej.core.model.Outcome.class, "/rules/full/Outcome"),
            Map.entry(net.cumba.corej.core.model.ExecutabilityHint.class,
                    "/rules/full/ExecutabilityHint"),
            Map.entry(net.cumba.corej.core.model.Authority.class, "/rules/full/Authorities/0"),
            Map.entry(net.cumba.corej.core.model.AuthorityStandard.class,
                    "/rules/full/Authorities/0/Standards/0"),
            Map.entry(net.cumba.corej.core.model.Reference.class,
                    "/rules/full/Authorities/0/Standards/0/References/0"),
            Map.entry(net.cumba.corej.core.model.RuleIdentifier.class,
                    "/rules/full/Authorities/0/Standards/0/References/0/Rule_Identifier"),
            Map.entry(net.cumba.corej.core.model.Citation.class,
                    "/rules/full/Authorities/0/Standards/0/References/0/Citations/0"),
            Map.entry(net.cumba.corej.core.model.Scope.class, "/rules/full/Scope"),
            Map.entry(net.cumba.corej.core.model.ClassScope.class, "/rules/full/Scope/Classes"),
            Map.entry(net.cumba.corej.core.model.DomainScope.class, "/rules/full/Scope/Domains"),
            Map.entry(net.cumba.corej.core.model.DatasetScope.class, "/rules/full/Scope/Datasets"),
            Map.entry(net.cumba.corej.core.model.DataStructureScope.class,
                    "/rules/full/Scope/Data_Structures"),
            Map.entry(net.cumba.corej.core.model.SubclassScope.class,
                    "/rules/full/Scope/Subclasses"),
            Map.entry(net.cumba.corej.core.model.Requirements.class, "/rules/full/Requirements"),
            Map.entry(net.cumba.corej.core.model.VariableRequirement.class,
                    "/rules/full/Requirements/Variables"),
            Map.entry(net.cumba.corej.core.model.Binding.class, "/rules/full/Bindings/0"),
            Map.entry(net.cumba.corej.core.model.MatchDataset.class,
                    "/rules/full/Match_Datasets/0"),
            Map.entry(net.cumba.corej.core.model.GroupingSpec.class, "/rules/full/Grouping"),
            Map.entry(net.cumba.corej.core.model.ExpansionDirective.class,
                    "/rules/expansion/Expansion/1"),
            Map.entry(net.cumba.corej.core.model.WildcardFilter.class, "/rules/wild/wildcards/xx"));

    /** The three classes whose plant fails the whole package rather than one rule. */
    private static final Set<Class<?>> WHOLE_PACKAGE = Set.of(RulePackage.class,
            net.cumba.corej.core.model.StandardRef.class, net.cumba.corej.core.model.Binding.class);

    /**
     * ⭐ Review E1 — "always" holds for a class added later: one generic plant per roster class, at
     * that class's location in the positive-control package, must surface as a load error naming
     * the plant. A model class with a collector but no walker arm (or no plant location) reds here.
     */
    @Test
    void everyRosterClassIsWalkedByTheGate() throws Exception
    {
        assertEquals(BoundRuleKeys.BY_CLASS.keySet(), PLANT_AT.keySet(),
                "every roster class needs a plant location, and only roster classes have one");
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        List<String> failures = new java.util.ArrayList<>();
        for (Map.Entry<Class<?>, String> e : PLANT_AT.entrySet())
        {
            String plant = "PLANT_" + e.getKey().getSimpleName();
            com.fasterxml.jackson.databind.JsonNode root = mapper.readTree(FULL_PACKAGE);
            com.fasterxml.jackson.databind.JsonNode target = root.at(e.getValue());
            assertTrue(target.isObject(), e.getValue() + " must locate an object");
            ((com.fasterxml.jackson.databind.node.ObjectNode) target).put(plant, 1);
            String json = mapper.writeValueAsString(root);
            if (WHOLE_PACKAGE.contains(e.getKey()))
            {
                try
                {
                    RulePackageLoader.loadFromString(json);
                    failures.add(e.getKey().getSimpleName() + ": package loaded despite " + plant);
                }
                catch (IOException expected)
                {
                    if (!expected.getMessage().contains(plant))
                    {
                        failures.add(e.getKey().getSimpleName() + ": refusal does not name " + plant
                                + ": " + expected.getMessage());
                    }
                }
                continue;
            }
            RulePackage pkg = RulePackageLoader.loadFromString(json);
            String ruleKey = e.getValue().substring("/rules/".length()).split("/", -1)[0];
            String error = pkg.getRules().get(ruleKey).getLoadError();
            if (error == null || !error.contains("unknown key '" + plant + "'"))
            {
                failures.add(e.getKey().getSimpleName() + " at " + e.getValue()
                        + ": plant not reported: " + error);
            }
            for (Map.Entry<String, Rule> r : pkg.getRules().entrySet())
            {
                if (!r.getKey().equals(ruleKey) && r.getValue().getLoadError() != null)
                {
                    failures.add(e.getKey().getSimpleName() + ": sibling " + r.getKey()
                            + " blamed: " + r.getValue().getLoadError());
                }
            }
        }
        assertTrue(failures.isEmpty(), String.join("\n", failures));
    }


    /**
     * Every bound key of every class, well formed, loads with no error — the positive control
     * without which a gate that errors on everything would pass every case above. ⚑ "Every bound
     * key" as judged by THIS gate: the keys other gates own are exercised in their well-formed
     * shape only — {@code Requirements.Library} / {@code Define} / {@code Dictionary} (R5, declared
     * ⟺ derived, so omitted here as the corpus omits them), {@code Match_Datasets[].Child} (its own
     * name / key gates; the entry here is an ordinary join) and the {@code Scope} alias
     * {@code Data Structures} (an alias of a key already present).
     */
    @Test
    void everyBoundKeyStillLoads() throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadFromString(FULL_PACKAGE);
        assertEquals(3, pkg.getRules().size());
        for (Map.Entry<String, Rule> e : pkg.getRules().entrySet())
        {
            assertNull(e.getValue().getLoadError(),
                    e.getKey() + " must load clean: " + e.getValue().getLoadError());
        }
        Rule fullRule = pkg.getRules().get("full");
        assertNotNull(fullRule);
        // The keys BOUND, so the control is not a rule that lost half its content on the way in.
        assertNotNull(fullRule.getAuthorities());
        assertNotNull(fullRule.getAuthorities().get(0).getStandards().get(0).getReferences().get(0)
                .getCitations().get(0).getSection());
        assertNotNull(fullRule.getScope().getSubclasses().getExclude());
        assertNotNull(fullRule.getGrouping().getKeepMissings());
        assertNotNull(fullRule.getCheckLevels());
        assertEquals(2, fullRule.getCheckLevels().size());
        Rule wild = pkg.getRules().get("wild");
        assertNotNull(wild);
        assertEquals(List.of("TRT01P"), wild.getWildcardExclude());
        assertEquals(9, wild.getWildcards().get("xx").getMax());
        Rule expansion = pkg.getRules().get("expansion");
        assertNotNull(expansion);
        assertEquals(true, expansion.getExpansion().get(1).getKnownDomainOnly());
    }
}
