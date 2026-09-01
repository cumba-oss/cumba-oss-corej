package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.List;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-underscore-field-retirement.md} §3 G — the retired-spelling load guard.
 *
 * <p>
 * The loader's mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so a rule left on the
 * old {@code _wildcards:} spelling would bind to nothing and expand <b>unfiltered</b> — a silent
 * behaviour change worse than the rename itself. The guard turns that into a per-rule
 * {@link Rule#getLoadError() loadError} naming the replacement.
 * </p>
 *
 * <p>
 * ⚑ It had two arms until Fix #366, because the two populations arrived by different routes: the
 * old spellings reach {@link Rule#getUnknownKeys()} (nothing binds them any more), while the
 * <em>new</em> spellings of the three template-steering fields bound to real {@code Rule} fields
 * and could only be seen by a field-value check. Deleting the engine's {@code rules-templates.json}
 * took those model fields with it, so both spellings are now unknown keys and one arm covers them;
 * the templates exemption both arms carried went the same way, and the loader has one entry point.
 * </p>
 */
class RetiredUnderscoreKeyGuardTest
{

    private static String packageOf(String ruleBody)
    {
        return "{\"rules\":{\"rule-1\":{\"Core\":{\"Id\":\"TEST-URK\"}," + ruleBody + "}}}";
    }


    private static Rule loadOne(String ruleBody) throws IOException
    {
        RulePackage pkg = RulePackageLoader.loadFromString(packageOf(ruleBody));
        return pkg.getRules().values().iterator().next();
    }

    // ---- Retired spellings, seen as unknown keys ----------------------------


    @Test
    void retiredSpellingOfARenamedFieldNamesItsReplacement() throws IOException
    {
        Rule rule = loadOne("\"_wildcards\": {\"xx\": {\"min\": 2}},"
                + "\"Check\": {\"all\": [{\"name\": \"TRTxxP\", \"operator\": \"var_exists\"}]}");
        String error = rule.getLoadError();
        assertNotNull(error, "a retired _wildcards spelling must tag loadError");
        assertTrue(error.contains("_wildcards"), error);
        assertTrue(error.contains("'wildcards'"), error);
        // …and, crucially, the value did NOT bind: the rule would have expanded unfiltered.
        assertNull(rule.getWildcards());
    }


    @Test
    void everyRenamedSpellingIsRejected() throws IOException
    {
        for (String key : List.of("_wildcards", "_wildcardExclude", "_wildcardPairCatalogue",
                "_skipIfLibraryDefined"))
        {
            Rule rule = loadOne("\"" + key + "\": true");
            String error = rule.getLoadError();
            assertNotNull(error, key + " must tag loadError");
            assertTrue(error.contains("rename it to '" + key.substring(1) + "'"),
                    key + ": " + error);
        }
    }


    /**
     * ⭐ The stop-condition of {@code PLAN-retire-engine-generated-rules.md}: deleting the built-in
     * templates must not cost the three template-steering fields their rejection path. They were
     * template-file-only and are now legal nowhere, so a stale key of <b>either</b> spelling on a
     * rule must still be an error rather than a silently-dropped unknown key — the failure the
     * whole guard exists to prevent. The advice is deletion; a rename would name a remedy that is
     * itself rejected on the author's next load.
     */
    @Test
    void bothSpellingsOfTheTemplateSteeringFieldsAreRejectedAsRemoved() throws IOException
    {
        for (String key : List.of("_templateFamily", "templateFamily", "_suffixExclusions",
                "suffixExclusions", "_requireAllWildcardsInDataset",
                "requireAllWildcardsInDataset"))
        {
            Rule rule = loadOne("\"" + key + "\": true");
            String error = rule.getLoadError();
            assertNotNull(error, key + " must tag loadError");
            assertTrue(error.contains("removed — delete the key"), key + ": " + error);
            assertFalse(error.contains("rename it to"), key + ": " + error);
            // It reached the unknown-key collector — i.e. the model really has stopped binding it.
            assertTrue(rule.getUnknownKeys().contains(key), rule.getUnknownKeys().toString());
        }
    }


    @Test
    void removedKeysAreRejectedWithoutARenameHint() throws IOException
    {
        for (String key : List.of("_templateNote", "_note", "_resolution", "_template", "_links"))
        {
            Rule rule = loadOne("\"" + key + "\": \"x\"");
            String error = rule.getLoadError();
            assertNotNull(error, key + " must tag loadError");
            assertTrue(error.contains("removed — delete the key"), key + ": " + error);
            assertFalse(error.contains("rename it to"), key + ": " + error);
        }
    }


    @Test
    void unknownNonRetiredKeyIsStillSilentlyDropped() throws IOException
    {
        Rule rule = loadOne("\"_category\": \"x\", \"SomethingElse\": 1");
        assertNull(rule.getLoadError(), "non-retired unknown keys keep the pre-existing behaviour");
        // The keys are recorded (that is what the guard reads) but nothing is rejected.
        assertTrue(rule.getUnknownKeys().containsAll(List.of("_category", "SomethingElse")),
                rule.getUnknownKeys().toString());
    }


    @Test
    void skipIfLibraryDefinedStaysUsableOnACorpusRule() throws IOException
    {
        // §9 ruling 2: D is renamed everywhere but remains a legal corpus field — only the `_`
        // spelling is rejected, never the field.
        Rule rule = loadOne("\"skipIfLibraryDefined\": true");
        assertNull(rule.getLoadError(), String.valueOf(rule.getLoadError()));
        assertEquals(true, rule.getSkipIfLibraryDefined());
    }

    // ---- The one load path (Fix #366) ---------------------------------------


    /**
     * ⭐ The exemption's carrier is gone. {@code rules-templates.json} was the sole reason this
     * guard was ever suppressed for a package, and it no longer exists as a classpath resource — so
     * no load path can be exempt, and the trio's model fields are gone with it. Three tests lived
     * here before Fix #366 and were removed with their subject: two pinned the templates exemption
     * itself, one pinned the shipped file's 45 rules and their 42/36/36/2 field census.
     */
    @Test
    void theBuiltInTemplatesResourceIsGone()
    {
        assertNull(RetiredUnderscoreKeyGuardTest.class.getResourceAsStream("/rules-templates.json"),
                "rules-templates.json was deleted by Fix #366; a copy back on the classpath would"
                        + " silently restore rules that belong to no package");
    }
}
