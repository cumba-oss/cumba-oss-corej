package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.model.DatasetScope;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Scope;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code Scope.Datasets} — selection by the dataset NAME (owner requirement #5,
 * {@code plans/PLAN-scope-requirements-split.md} &#167;4.6).
 *
 * <p>
 * ⚠ <b>These are hand-authored gate tests by construction.</b> No shipped rule carries the axis
 * until phase 4f restores it on two ADaM rules, so a green here proves the matcher works and proves
 * nothing about a shipped rule ({@code [[hand-authored-gate-tests-are-vacuous]]}). The corpus-count
 * assertion that would make the day one appears visible lives in another module and is phase 4's;
 * the label is here so a later reader does not over-trust the green.
 * </p>
 */
class ScopeDatasetsMatcherTest
{

    private static Rule datasetScoped(@Nullable List<String> include,
            @Nullable List<String> exclude)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-SCOPE-DS");
        rule.setCore(core);
        Scope scope = new Scope();
        DatasetScope datasets = new DatasetScope();
        datasets.setInclude(include);
        datasets.setExclude(exclude);
        scope.setDatasets(datasets);
        rule.setScope(scope);
        return rule;
    }


    @Test
    @DisplayName("a rule with no Datasets axis matches every dataset")
    void noAxisMatchesEverything()
    {
        assertNull(ScopeMatcher.describeDatasetMismatch(new Rule(), "ADSL"));
        Rule rule = datasetScoped(null, null);
        assertNull(ScopeMatcher.describeDatasetMismatch(rule, "ADSL"));
        assertNull(ScopeMatcher.describeDatasetMismatch(rule, null), "a null name gates nothing");
    }


    @Test
    @DisplayName("Include selects the named file and rejects every other")
    void includeLiteral()
    {
        Rule rule = datasetScoped(List.of("ADSL"), null);
        assertNull(ScopeMatcher.describeDatasetMismatch(rule, "ADSL"));
        assertTrue(ScopeMatcher.matchesDatasets(rule, "ADSL"));
        assertEquals("dataset ADAE not in Scope.Datasets.Include [ADSL]",
                ScopeMatcher.describeDatasetMismatch(rule, "ADAE"));
        assertFalse(ScopeMatcher.matchesDatasets(rule, "ADAE"));
    }


    @Test
    @DisplayName("matching is case- and punctuation-insensitive, like Scope.Domains")
    void normalisedMatching()
    {
        Rule rule = datasetScoped(List.of("ADSL"), null);
        assertNull(ScopeMatcher.describeDatasetMismatch(rule, "adsl"));
    }


    @Test
    @DisplayName("Exclude rejects a named file and names the entry that did it")
    void excludeNamesTheEntry()
    {
        Rule rule = datasetScoped(null, List.of("ADSL"));
        assertEquals("dataset ADSL matches Scope.Datasets.Exclude entry ADSL",
                ScopeMatcher.describeDatasetMismatch(rule, "ADSL"));
        assertNull(ScopeMatcher.describeDatasetMismatch(rule, "ADAE"));
    }


    @Test
    @DisplayName("the ALL / NONE sentinels keep their Scope.Domains meaning")
    void sentinels()
    {
        assertNull(ScopeMatcher.describeDatasetMismatch(datasetScoped(List.of("ALL"), null), "AE"),
                "Include: [ALL] is a legal no-op");
        assertNull(ScopeMatcher.describeDatasetMismatch(datasetScoped(null, List.of("NONE")), "AE"),
                "Exclude: [NONE] excludes nothing");
    }


    @Test
    @DisplayName("glob and /regex/ entries work — and are coreJ-only, not upstream-portable")
    void globAndRegex()
    {
        Rule glob = datasetScoped(List.of("ADTTE*"), null);
        assertNull(ScopeMatcher.describeDatasetMismatch(glob, "ADTTE"));
        assertNull(ScopeMatcher.describeDatasetMismatch(glob, "ADTTEX"));
        assertNotNull(ScopeMatcher.describeDatasetMismatch(glob, "ADAE"));

        Rule regex = datasetScoped(List.of("/^SUPP.*$/"), null);
        assertNull(ScopeMatcher.describeDatasetMismatch(regex, "SUPPLBCH"));
        assertNotNull(ScopeMatcher.describeDatasetMismatch(regex, "LB"));
    }


    @Test
    @DisplayName("the `--` token is STRICT: prefix + exactly two characters")
    void dashDashIsStrict()
    {
        Rule rule = datasetScoped(List.of("SUPP--"), null);
        assertNull(ScopeMatcher.describeDatasetMismatch(rule, "SUPPAE"), "6 characters: matches");
        assertNotNull(ScopeMatcher.describeDatasetMismatch(rule, "SUPPLBCH"),
                "8 characters: the `--` token is never 'any suffix'");
    }


    /**
     * ⚠⚠ <b>The trap the axis exists to document, pinned side by side with {@code Scope.Domains} so
     * the difference is visible rather than inferred.</b>
     *
     * <p>
     * On a split submission {@code Scope.Domains: ["LB"]} selects {@code LB1} through the
     * data-derived unsplit-base re-test; {@code Scope.Datasets: ["LB"]} selects <b>nothing</b>.
     * That absence is the feature — {@code Datasets} means <em>the file</em> — but it is also why a
     * {@code SUPP--}-scoped rule must not be migrated to this axis without a glob: on a real split
     * submission the member is the 8-character {@code SUPPLBCH}, which the strict {@code --} token
     * cannot match while {@code Domains} matches it through the base {@code SUPPLB}.
     * </p>
     */
    @Test
    @DisplayName("⚠⚠ NO split-base re-test — the one deliberate difference from Scope.Domains")
    void noSplitBaseRetest()
    {
        Rule byName = datasetScoped(List.of("LB"), null);
        assertNotNull(ScopeMatcher.describeDatasetMismatch(byName, "LB1"),
                "Scope.Datasets matches the file, so a split part is OUT of scope");

        Rule byDomain = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-SCOPE-DOM");
        byDomain.setCore(core);
        Scope scope = new Scope();
        DomainScope domains = new DomainScope();
        domains.setInclude(List.of("LB"));
        scope.setDomains(domains);
        byDomain.setScope(scope);
        assertNull(ScopeMatcher.describeDomainMismatch(byDomain, "LB1", "LB"),
                "control: Scope.Domains DOES cover the split part through its base re-test — if this"
                        + " ever goes red the contrast above is measuring nothing");
    }

}
