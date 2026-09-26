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
        String error = errorOf(plain("\"Outcom\":{\"Message\":\"typo\"}"));
        assertUnknownAt(error, "Outcom", "at the top level of the rule");
        assertTrue(error.contains("did you mean 'Outcome'?"), error);
    }


    @Test
    void aLowerCaseSpellingOfABoundKeyIsUnknownWithTheHint() throws IOException
    {
        // T1-3 (a): case-sensitive, reject, hint — never bind case-insensitively.
        String error = errorOf(plain("\"outcome\":{\"Message\":\"typo\"}"));
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
                + "\"Outcome\":{\"Message\":\"m\",\"Mesage\":\"m2\",\"Output_Variables\":[\"AESEQ\"]},"
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
    void aKeyBesideAPreconditionConditionFailsTheWholePackage()
    {
        // T1-5 (a), accepted consequence L3: no per-rule channel under Precondition, so the
        // condition grammar's own whole-package refusal applies — naming the key.
        IOException ex = assertThrows(IOException.class, () -> loadX(
                plain("\"Precondition\":{\"expression\":\"library_available()\",\"X\":1}")));
        assertTrue(ex.getMessage().contains("'X'"), ex.getMessage());
        assertTrue(ex.getMessage().contains("exactly one of all/any/not/expression"),
                ex.getMessage());
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
     * Every bound key of every class, well formed, loads with no error. Three rules because the
     * loader forbids some legal combinations on ONE rule ({@code Expansion} with the
     * {@code wildcard*} directives or with engine-owned wildcard markers; a {@code Grouping} block
     * with the flat {@code Grouping_Variables}).
     */
    @Test
    void everyBoundKeyStillLoads() throws IOException
    {
        String full = """
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
                """;
        RulePackage pkg = RulePackageLoader.loadFromString("{\"standards\":[{\"id\":\"sdtmig/3-4\","
                + "\"role\":\"primary\"}],\"rules\":{" + full + "}}");
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
