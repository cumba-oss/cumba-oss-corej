package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.model.DomainScope;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Scope;
import org.junit.jupiter.api.Test;

/**
 * Exact-membership semantics for {@code Scope.Domains.Include} / {@code Scope.Domains.Exclude}. A
 * literal entry matches a dataset name only when the two are equal after normalisation (uppercase +
 * non-alphanumerics stripped), mirroring the reference Python engine's
 * {@code rule_processor._is_domain_name_included} / {@code _is_domain_name_excluded}. Extended and
 * split forms are reached through the callers' unsplit-name re-test, never through prefix matching.
 * Wildcard sentinels ({@code ALL}, {@code NONE}, {@code SUPP--}, {@code AP--}) and glob / regex
 * entries keep their own semantics.
 *
 * <p>
 * Supersedes the Fix #38 prefix contract: {@code Include = ["RE"]} must not select {@code RELREC}
 * or {@code RELSUB}, and {@code Include = ["SU"]} must not select {@code SUPPAE}.
 * </p>
 */
class ScopeMatcherDomainPrefixTest
{

    // ---- Include: exact only ----

    @Test
    void includeExactMatchStillWorks()
    {
        Rule rule = ruleWithDomainInclude("ADAE");
        assertTrue(ScopeMatcher.matchesDomain(rule, "ADAE"),
                "literal name must match its own Include entry");
    }


    @Test
    void includeDoesNotMatchExtendedForms()
    {
        Rule rule = ruleWithDomainInclude("ADAE");
        assertFalse(ScopeMatcher.matchesDomain(rule, "ADAEDV"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "ADAESI"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "ADAE_BACKUP"));
    }


    @Test
    void includeDoesNotMatchDifferentFamilies()
    {
        Rule rule = ruleWithDomainInclude("ADAE");
        assertFalse(ScopeMatcher.matchesDomain(rule, "ADCM"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "ADLB"));
    }


    @Test
    void includeReachesFamilyBreadthOnlyViaGlobOrRegex()
    {
        assertTrue(ScopeMatcher.matchesDomain(ruleWithDomainInclude("ADAE*"), "ADAEDV"));
        assertTrue(ScopeMatcher.matchesDomain(ruleWithDomainInclude("/^ADLB.*$/"), "ADLBC"));
    }


    @Test
    void matchIsCaseAndSeparatorInsensitive()
    {
        Rule rule = ruleWithDomainInclude("ADAE");
        assertTrue(ScopeMatcher.matchesDomain(rule, "adae"),
                "lowercase filename-derived name must match the uppercase Include entry");
        assertTrue(ScopeMatcher.matchesDomain(rule, "AD-AE"),
                "separator drift must still normalise to a match");
        assertFalse(ScopeMatcher.matchesDomain(rule, "AdAeDv"),
                "case-insensitivity must not resurrect prefix matching");
    }


    @Test
    void multipleIncludeEntries_anyExactMatches()
    {
        Rule rule = ruleWithDomainInclude("ADAE", "ADCM");
        assertTrue(ScopeMatcher.matchesDomain(rule, "ADAE"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "ADCM"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "ADCMRT"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "ADLB"));
    }

    // ---- Regression: the SEND collisions this change exists to kill ----


    @Test
    void includeRE_doesNotSelectRelationshipDatasets()
    {
        Rule rule = ruleWithDomainInclude("RE");
        assertTrue(ScopeMatcher.matchesDomain(rule, "RE"));
        assertEquals("domain RELREC not in Scope.Domains.Include [RE]",
                ScopeMatcher.describeDomainMismatch(rule, "RELREC"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "RELSUB"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "RELSPEC"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "RELREF"));
    }


    @Test
    void includeSU_doesNotSelectSuppDatasets()
    {
        Rule rule = ruleWithDomainInclude("SU");
        assertTrue(ScopeMatcher.matchesDomain(rule, "SU"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "SUPPAE"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "SUPPQUAL"));
    }

    // ---- Split / extended forms come from the unsplit-name re-test, not from prefixes ----


    @Test
    void splitDatasetMatchesViaDataDrivenUnsplitBase()
    {
        // LBCHEM carrying DOMAIN=LB is a split of LB (Python SDTMDatasetMetadata.unsplit_name).
        Rule rule = ruleWithDomainInclude("LB");
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "LBCHEM", "LB"));
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "LB1", "LB"));
        // …and without the DOMAIN evidence it is NOT a split, so it is out of scope (parity).
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "LBCHEM", "LBCHEM"));
    }


    @Test
    void nameHeuristicOverloadStillFoldsDigitSplits()
    {
        // The table-less 2-arg overload derives the base from SplitDatasetUtil.unsplitName.
        Rule rule = ruleWithDomainInclude("ADAE");
        assertTrue(ScopeMatcher.matchesDomain(rule, "ADAE01"));
        // The data-driven overload used in production sees no DOMAIN column for ADaM, so the
        // same dataset is not a split and falls out of scope — matching Python.
        assertNotNull(ScopeMatcher.describeDomainMismatch(rule, "ADAE01", "ADAE01"));
    }

    // ---- Exclude path symmetry ----


    @Test
    void excludeMatchesExactFormOnly()
    {
        Rule rule = ruleWithDomainExclude("ADAE");
        assertFalse(ScopeMatcher.matchesDomain(rule, "ADAE"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "ADAEDV"),
                "an extended name is no longer swallowed by an ADAE exclude");
    }


    @Test
    void excludeStillCatchesSplitViaUnsplitBase()
    {
        Rule rule = ruleWithDomainExclude("LB");
        assertEquals("domain LB1 matches Scope.Domains.Exclude entry LB",
                ScopeMatcher.describeDomainMismatch(rule, "LB1", "LB"));
    }


    @Test
    void excludeDoesNotAffectOtherFamilies()
    {
        Rule rule = ruleWithDomainExclude("ADAE");
        assertTrue(ScopeMatcher.matchesDomain(rule, "ADCM"));
    }


    @Test
    void excludeWinsOverInclude_onTheExactName()
    {
        Rule rule = ruleWithDomainIncludeExclude(List.of("ADLB", "ADLBC"), List.of("ADLBC"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "ADLB"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "ADLBC"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "ADLBH"), "not in Include any more");
    }

    // ---- Empty-list defaults + degenerate entries ----


    @Test
    void emptyIncludeAndExclude_matchesEverything()
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setInclude(List.of());
        ds.setExclude(List.of());
        scope.setDomains(ds);
        rule.setScope(scope);
        assertTrue(ScopeMatcher.matchesDomain(rule, "ADAE"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "ADCM"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "ADLB"));
    }


    @Test
    void entryNormalisingToEmpty_neverMatches()
    {
        // "__" survives RulePackageLoader's empty-entry check but normalises to "" — it must not
        // match anything, not even a dataset whose name also normalises to "".
        Rule rule = ruleWithDomainInclude("__");
        assertFalse(ScopeMatcher.matchesDomain(rule, "__"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "AE"));
    }

    // ---- Helpers ----


    private static Rule ruleWithDomainInclude(String... domains)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setInclude(List.of(domains));
        scope.setDomains(ds);
        rule.setScope(scope);
        return rule;
    }


    private static Rule ruleWithDomainExclude(String... domains)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setExclude(List.of(domains));
        scope.setDomains(ds);
        rule.setScope(scope);
        return rule;
    }


    private static Rule ruleWithDomainIncludeExclude(List<String> include, List<String> exclude)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setInclude(include);
        ds.setExclude(exclude);
        scope.setDomains(ds);
        rule.setScope(scope);
        return rule;
    }
}
