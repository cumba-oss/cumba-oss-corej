package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.cumba.corej.core.model.ClassScope;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.DataTableMeta;
import org.junit.jupiter.api.Test;

class ScopeMatcherTest
{

    // ---- Domain matching ----

    @Test
    void testMatchesDomain_includeList()
    {
        // CORE-000027: Domains.Include = ["TE"]
        Rule rule = ruleWithDomainInclude("TE");
        assertTrue(ScopeMatcher.matchesDomain(rule, "TE"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "DM"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "AE"));
    }


    @Test
    void testMatchesDomain_multipleIncludes()
    {
        Rule rule = ruleWithDomainInclude("DM", "AE");
        assertTrue(ScopeMatcher.matchesDomain(rule, "DM"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "AE"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "VS"));
    }


    @Test
    void testMatchesDomain_excludeList()
    {
        Rule rule = ruleWithDomainExclude("DM", "SUPPQUAL");
        assertFalse(ScopeMatcher.matchesDomain(rule, "DM"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "SUPPQUAL"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "AE"));
    }


    @Test
    void testMatchesDomain_noScope()
    {
        Rule rule = new Rule();
        assertTrue(ScopeMatcher.matchesDomain(rule, "DM"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "AE"));
    }


    @Test
    void testMatchesDomain_noDomainScope()
    {
        Rule rule = new Rule();
        rule.setScope(new Scope());
        assertTrue(ScopeMatcher.matchesDomain(rule, "DM"));
    }


    @Test
    void testMatchesDomain_emptyInclude()
    {
        // Empty include list — no constraint
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setInclude(List.of());
        scope.setDomains(ds);
        rule.setScope(scope);
        assertTrue(ScopeMatcher.matchesDomain(rule, "DM"));
    }


    @Test
    void testMatchesDomain_nullDomainName()
    {
        Rule rule = ruleWithDomainInclude("TE");
        assertTrue(ScopeMatcher.matchesDomain(rule, null));
    }

    // ---- Domain matching: ALL wildcard ----


    @Test
    void testMatchesDomain_includeAll()
    {
        Rule rule = ruleWithDomainInclude("ALL");
        assertTrue(ScopeMatcher.matchesDomain(rule, "DM"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "AE"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "SUPPDM"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "RELREC"));
    }


    @Test
    void testMatchesDomain_includeAllWithExclude()
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setInclude(List.of("ALL"));
        ds.setExclude(List.of("DM", "AE"));
        scope.setDomains(ds);
        rule.setScope(scope);
        assertFalse(ScopeMatcher.matchesDomain(rule, "DM"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "AE"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "VS"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "LB"));
    }

    // ---- Domain matching: SUPP-- / AP-- patterns ----


    @Test
    void testMatchesDomain_suppWildcard()
    {
        Rule rule = ruleWithDomainInclude("SUPP--");
        assertTrue(ScopeMatcher.matchesDomain(rule, "SUPPDM"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "SUPPAE"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "SUPPLB"));
        // The family wildcard is DELETED (supersedes Fix #34): `--` is strict, so `SUPP--`
        // needs SUPP plus exactly two characters. The bare prefix `"SUPP"` no longer matches.
        assertFalse(ScopeMatcher.matchesDomain(rule, "SUPP"));
        // Fix #12: SUPP letter-suffix splits (SUPPDMX = letter-split of SUPPDM) are
        // recognised as splits and fall under the SUPP-- scope via their unsplit base.
        assertTrue(ScopeMatcher.matchesDomain(rule, "SUPPDMX"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "DM"));
    }


    @Test
    void testMatchesDomain_apWildcard()
    {
        Rule rule = ruleWithDomainInclude("AP--");
        assertTrue(ScopeMatcher.matchesDomain(rule, "APCE"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "APMH"));
        // The family wildcard is DELETED (supersedes Fix #34): `AP--` needs AP plus exactly
        // two characters, so the bare prefix `"AP"` no longer matches.
        assertFalse(ScopeMatcher.matchesDomain(rule, "AP"));
        // Fix #12: AP letter-suffix splits (APMHX = letter-split of APMH) likewise match.
        assertTrue(ScopeMatcher.matchesDomain(rule, "APMHX"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "DM"));
    }


    @Test
    void testMatchesDomain_excludeSuppWildcard()
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setExclude(List.of("SUPP--", "AP--"));
        scope.setDomains(ds);
        rule.setScope(scope);
        assertFalse(ScopeMatcher.matchesDomain(rule, "SUPPDM"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "APCE"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "DM"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "AE"));
    }

    // ---- Domain matching: NONE in Exclude ----


    @Test
    void testMatchesDomain_excludeNone()
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setExclude(List.of("NONE"));
        scope.setDomains(ds);
        rule.setScope(scope);
        assertTrue(ScopeMatcher.matchesDomain(rule, "DM"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "AE"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "SUPPDM"));
    }

    // ---- Class matching ----


    @Test
    void testMatchesClass_includeAll()
    {
        Rule rule = ruleWithClassInclude("ALL");
        assertTrue(ScopeMatcher.matchesClass(rule, "EVENTS"));
        assertTrue(ScopeMatcher.matchesClass(rule, "FINDINGS"));
        assertTrue(ScopeMatcher.matchesClass(rule, "SPECIAL PURPOSE"));
    }


    @Test
    void testMatchesClass_includeAllWithExclude()
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        ClassScope cs = new ClassScope();
        cs.setInclude(List.of("ALL"));
        cs.setExclude(List.of("RELATIONSHIP"));
        scope.setClasses(cs);
        rule.setScope(scope);
        assertTrue(ScopeMatcher.matchesClass(rule, "EVENTS"));
        assertFalse(ScopeMatcher.matchesClass(rule, "RELATIONSHIP"));
    }


    @Test
    void testMatchesClass_includeList()
    {
        Rule rule = ruleWithClassInclude("SPECIAL PURPOSE");
        assertTrue(ScopeMatcher.matchesClass(rule, "SPECIAL PURPOSE"));
        assertFalse(ScopeMatcher.matchesClass(rule, "EVENTS"));
    }


    @Test
    void testMatchesClass_excludeList()
    {
        Rule rule = ruleWithClassExclude("RELATIONSHIP");
        assertFalse(ScopeMatcher.matchesClass(rule, "RELATIONSHIP"));
        assertTrue(ScopeMatcher.matchesClass(rule, "EVENTS"));
    }


    @Test
    void testMatchesClass_noScope()
    {
        Rule rule = new Rule();
        assertTrue(ScopeMatcher.matchesClass(rule, "EVENTS"));
    }


    @Test
    void testMatchesClass_nullClassName_strictRejectsClassScopedRule()
    {
        // Fix #41: when className is null AND the rule is class-scoped, reject. Mirrors
        // Python's rule_processor.rule_applies_to_class:255 (`class_name not in
        // included_classes`).
        Rule rule = ruleWithClassInclude("SPECIAL PURPOSE");
        assertFalse(ScopeMatcher.matchesClass(rule, null));
    }


    @Test
    void testMatchesClass_nullClassName_excludeOnlyRuleAlsoRejected()
    {
        // Symmetric on Exclude — strict-on-null rejects any class-scoped rule.
        Rule rule = ruleWithClassExclude("RELATIONSHIP");
        assertFalse(ScopeMatcher.matchesClass(rule, null));
    }


    @Test
    void testMatchesClass_nullClassName_unscopedRulePermissive()
    {
        // Strict-on-null only kicks in when the rule has a class scope. A rule with no class
        // Include/Exclude is permissive even when the class is undetermined.
        Rule rule = new Rule();
        assertTrue(ScopeMatcher.matchesClass(rule, null));
    }


    @Test
    void testMatchesClass_nullClassName_emptyClassScopePermissive()
    {
        // Edge case: ClassScope object exists but both Include and Exclude are empty/null.
        // Treat as "no class scope" → permissive.
        Rule rule = new Rule();
        Scope scope = new Scope();
        ClassScope cs = new ClassScope();
        cs.setInclude(List.of());
        cs.setExclude(List.of());
        scope.setClasses(cs);
        rule.setScope(scope);
        assertTrue(ScopeMatcher.matchesClass(rule, null));
    }


    @Test
    void testMatchesClass_libraryCasingMatchesUppercaseRule()
    {
        // CDISC Library returns "Events", "Findings", … in title case; rule scopes use
        // uppercase. Both must collide via normalisation.
        Rule rule = ruleWithClassInclude("EVENTS");
        assertTrue(ScopeMatcher.matchesClass(rule, "Events"));
        assertTrue(ScopeMatcher.matchesClass(rule, "events"));
    }


    @Test
    void testMatchesClass_specialPurposeHyphenSpaceVariants()
    {
        // Library returns "Special-Purpose"; rules write "SPECIAL PURPOSE" (and vice versa).
        Rule rule = ruleWithClassInclude("SPECIAL PURPOSE");
        assertTrue(ScopeMatcher.matchesClass(rule, "Special-Purpose"));
        assertTrue(ScopeMatcher.matchesClass(rule, "SPECIAL-PURPOSE"));
        assertTrue(ScopeMatcher.matchesClass(rule, "special purpose"));
    }


    @Test
    void testMatchesClass_findingsAboutFallbackInclude()
    {
        // FA datasets (class = "FINDINGS ABOUT") must satisfy a rule scoped only to "FINDINGS".
        Rule rule = ruleWithClassInclude("FINDINGS");
        assertTrue(ScopeMatcher.matchesClass(rule, "FINDINGS ABOUT"));
        assertTrue(ScopeMatcher.matchesClass(rule, "Findings About"));
        // And a rule scoped to FINDINGS ABOUT directly still works.
        Rule rule2 = ruleWithClassInclude("FINDINGS ABOUT");
        assertTrue(ScopeMatcher.matchesClass(rule2, "FINDINGS ABOUT"));
    }


    @Test
    void testMatchesClass_findingsAboutFallbackExclude()
    {
        // Symmetric on the exclude side: rule excluding FINDINGS also excludes FA datasets.
        Rule rule = ruleWithClassExclude("FINDINGS");
        assertFalse(ScopeMatcher.matchesClass(rule, "FINDINGS ABOUT"));
        assertFalse(ScopeMatcher.matchesClass(rule, "FINDINGS"));
        assertTrue(ScopeMatcher.matchesClass(rule, "EVENTS"));
    }

    // ---- Use_Case matching ----


    @Test
    void testMatchesUseCase_noScope()
    {
        Rule rule = new Rule();
        assertTrue(ScopeMatcher.matchesUseCase(rule, "INDH"));
    }


    @Test
    void testMatchesUseCase_noUseCaseSet()
    {
        Rule rule = new Rule();
        rule.setScope(new Scope());
        assertTrue(ScopeMatcher.matchesUseCase(rule, "INDH"));
    }


    @Test
    void testMatchesUseCase_singleValue()
    {
        Rule rule = ruleWithUseCase("INDH");
        assertTrue(ScopeMatcher.matchesUseCase(rule, "INDH"));
        assertFalse(ScopeMatcher.matchesUseCase(rule, "PROD"));
        assertFalse(ScopeMatcher.matchesUseCase(rule, "NONCLIN"));
    }


    @Test
    void testMatchesUseCase_multipleValues()
    {
        Rule rule = ruleWithUseCase("INDH, PROD");
        assertTrue(ScopeMatcher.matchesUseCase(rule, "INDH"));
        assertTrue(ScopeMatcher.matchesUseCase(rule, "PROD"));
        assertFalse(ScopeMatcher.matchesUseCase(rule, "NONCLIN"));
    }


    @Test
    void testMatchesUseCase_caseInsensitive()
    {
        Rule rule = ruleWithUseCase("INDH, PROD");
        assertTrue(ScopeMatcher.matchesUseCase(rule, "indh"));
        assertTrue(ScopeMatcher.matchesUseCase(rule, "Prod"));
    }


    @Test
    void testMatchesUseCase_nullUseCase()
    {
        Rule rule = ruleWithUseCase("INDH");
        assertTrue(ScopeMatcher.matchesUseCase(rule, null));
    }


    @Test
    void testMatchesUseCase_allThree()
    {
        Rule rule = ruleWithUseCase("INDH, PROD, NONCLIN");
        assertTrue(ScopeMatcher.matchesUseCase(rule, "INDH"));
        assertTrue(ScopeMatcher.matchesUseCase(rule, "PROD"));
        assertTrue(ScopeMatcher.matchesUseCase(rule, "NONCLIN"));
    }

    // ---- Helpers ----


    private static Rule ruleWithUseCase(String useCase)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        scope.setUseCase(useCase);
        rule.setScope(scope);
        return rule;
    }


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


    private static Rule ruleWithClassInclude(String... classes)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        ClassScope cs = new ClassScope();
        cs.setInclude(List.of(classes));
        scope.setClasses(cs);
        rule.setScope(scope);
        return rule;
    }


    private static Rule ruleWithClassExclude(String... classes)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        ClassScope cs = new ClassScope();
        cs.setExclude(List.of(classes));
        scope.setClasses(cs);
        rule.setScope(scope);
        return rule;
    }

    // ---- include_split_datasets tri-state filter ----
    // null = no split filtering (default)
    // true = only split datasets
    // false = only non-split datasets


    @Test
    void testMatchesDomain_splitTrue_matchesRegularSplit()
    {
        Rule rule = ruleWithSplitFilter(true);
        assertTrue(ScopeMatcher.matchesDomain(rule, "LB1"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "AE2"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "LB10"));
    }


    @Test
    void testMatchesDomain_splitTrue_matchesSuppAndApSplit()
    {
        Rule rule = ruleWithSplitFilter(true);
        assertTrue(ScopeMatcher.matchesDomain(rule, "SUPPDM1"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "SUPPAE2"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "APMH1"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "APCE2"));
    }


    @Test
    void testMatchesDomain_splitTrue_rejectsNonSplit()
    {
        Rule rule = ruleWithSplitFilter(true);
        assertFalse(ScopeMatcher.matchesDomain(rule, "DM"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "AE"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "RELREC"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "SUPPDM")); // base, not split
        assertFalse(ScopeMatcher.matchesDomain(rule, "APMH")); // base, not split
    }


    @Test
    void testMatchesDomain_splitTrue_withInclude_isConjunctiveNotAdditive()
    {
        // Include=[SUPP--] + include_split_datasets=true. The flag is a CONJUNCTIVE gate: in scope
        // iff the dataset matches Include (by name or by split base) AND is a split. This is the
        // deliberate java-only divergence from Python's _handle_split_domains, which instead
        // ADDITIVELY includes every split regardless of Include — under which `Include` is inert.
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setInclude(List.of("SUPP--"));
        ds.setIncludeSplitDatasets(true);
        scope.setDomains(ds);
        rule.setScope(scope);

        assertTrue(ScopeMatcher.matchesDomain(rule, "SUPPDM1")); // base SUPPDM matches + split
        assertTrue(ScopeMatcher.matchesDomain(rule, "SUPPAE2")); // base SUPPAE matches + split
        // Was true before the conjunctive split gate: matched Include but is NOT a split.
        assertFalse(ScopeMatcher.matchesDomain(rule, "SUPPDM"));
        // Was true before the conjunctive split gate: a split, but nothing in Include matches it.
        // This is
        // the leg that made `Include: [AP--]` inert on CDISC-CG0650 / CORE-000778.
        assertFalse(ScopeMatcher.matchesDomain(rule, "LB1"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "DM")); // non-split, misses Include
    }


    @Test
    void testMatchesDomain_splitTrue_withInclude_reportsTheRightReason()
    {
        // The two rejection legs must be distinguishable in the reason message, so a scope skip
        // can be attributed to the Include list or to the split gate.
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setInclude(List.of("AP--"));
        ds.setIncludeSplitDatasets(true);
        scope.setDomains(ds);
        rule.setScope(scope);

        // Split of AE (the shape all three CORE-000778 fixtures had): misses Include.
        assertEquals("domain APTOOLONG not in Scope.Domains.Include [AP--]",
                ScopeMatcher.describeDomainMismatch(rule, "APTOOLONG", "AE"));
        // Matches Include by name but is not a split.
        assertEquals(
                "domain APMH is not a split dataset but Scope.Domains.Include_Split_Datasets is"
                        + " true",
                ScopeMatcher.describeDomainMismatch(rule, "APMH", "APMH"));
        // Split whose data-derived base matches AP--: in scope.
        assertNull(ScopeMatcher.describeDomainMismatch(rule, "APTOOLONG", "APAE"));
    }


    @Test
    void testMatchesDomain_splitTrue_respectsExclude()
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setIncludeSplitDatasets(true);
        ds.setExclude(List.of("SUPP--"));
        scope.setDomains(ds);
        rule.setScope(scope);

        assertTrue(ScopeMatcher.matchesDomain(rule, "LB1"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "DM"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "SUPPDM"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "SUPPDM1")); // split but base excluded
    }


    @Test
    void testMatchesDomain_splitFalse_rejectsSplitDatasets()
    {
        Rule rule = ruleWithSplitFilter(false);
        assertTrue(ScopeMatcher.matchesDomain(rule, "DM"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "AE"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "SUPPDM"));
        assertFalse(ScopeMatcher.matchesDomain(rule, "LB1")); // split → rejected
        assertFalse(ScopeMatcher.matchesDomain(rule, "SUPPDM1")); // split → rejected
        assertFalse(ScopeMatcher.matchesDomain(rule, "APMH1")); // split → rejected
    }


    @Test
    void testMatchesDomain_splitNull_noFiltering()
    {
        // Default: no split filtering, matches everything
        Rule rule = ruleWithSplitFilter(null);
        assertTrue(ScopeMatcher.matchesDomain(rule, "DM"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "LB1"));
        assertTrue(ScopeMatcher.matchesDomain(rule, "SUPPDM1"));
    }


    private static Rule ruleWithSplitFilter(Boolean splitFilter)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setIncludeSplitDatasets(splitFilter);
        scope.setDomains(ds);
        rule.setScope(scope);
        return rule;
    }

    // ---- Variable matching ----


    @Test
    void testMatchesVariables_noScope_matchesAll()
    {
        Rule rule = new Rule();
        DataTableMeta meta = metaWith("STUDYID", "USUBJID", "AGE");
        assertTrue(ScopeMatcher.matchesVariables(rule, meta));
    }


    @Test
    void testMatchesVariables_noVariableScope_matchesAll()
    {
        Rule rule = ruleWithDomainInclude("ALL");
        DataTableMeta meta = metaWith("STUDYID", "DOMAIN");
        assertTrue(ScopeMatcher.matchesVariables(rule, meta));
    }


    @Test
    void testMatchesVariables_includePresent_matches()
    {
        Rule rule = ruleWithVariableInclude("USUBJID");
        DataTableMeta meta = metaWith("STUDYID", "USUBJID", "AGE");
        assertTrue(ScopeMatcher.matchesVariables(rule, meta));
    }


    @Test
    void testMatchesVariables_includeAbsent_doesNotMatch()
    {
        Rule rule = ruleWithVariableInclude("USUBJID");
        DataTableMeta meta = metaWith("STUDYID", "DOMAIN", "VISITNUM");
        assertFalse(ScopeMatcher.matchesVariables(rule, meta));
    }


    @Test
    void testMatchesVariables_includeMultiple_allRequired()
    {
        Rule rule = ruleWithVariableInclude("USUBJID", "VISITNUM");
        DataTableMeta metaWith = metaWith("STUDYID", "USUBJID", "VISITNUM");
        assertTrue(ScopeMatcher.matchesVariables(rule, metaWith));

        DataTableMeta metaPartial = metaWith("STUDYID", "USUBJID");
        assertFalse(ScopeMatcher.matchesVariables(rule, metaPartial));
    }


    @Test
    void testMatchesVariables_excludePresent_doesNotMatch()
    {
        Rule rule = ruleWithVariableExclude("POOLID");
        DataTableMeta meta = metaWith("STUDYID", "POOLID", "AGE");
        assertFalse(ScopeMatcher.matchesVariables(rule, meta));
    }


    @Test
    void testMatchesVariables_excludeAbsent_matches()
    {
        Rule rule = ruleWithVariableExclude("POOLID");
        DataTableMeta meta = metaWith("STUDYID", "USUBJID", "AGE");
        assertTrue(ScopeMatcher.matchesVariables(rule, meta));
    }


    @Test
    void testMatchesVariables_includeAndExclude_combined()
    {
        Rule rule = ruleWithVariableIncludeExclude(List.of("USUBJID"), List.of("POOLID"));
        // Has USUBJID, no POOLID → matches
        assertTrue(ScopeMatcher.matchesVariables(rule, metaWith("STUDYID", "USUBJID")));
        // Has USUBJID and POOLID → excluded
        assertFalse(ScopeMatcher.matchesVariables(rule, metaWith("STUDYID", "USUBJID", "POOLID")));
        // No USUBJID → required missing
        assertFalse(ScopeMatcher.matchesVariables(rule, metaWith("STUDYID", "DOMAIN")));
    }


    @Test
    void testMatchesVariables_nullMeta_matchesAll()
    {
        Rule rule = ruleWithVariableInclude("USUBJID");
        assertTrue(ScopeMatcher.matchesVariables(rule, null));
    }

    // ---- Variable scope helpers ----


    private static Rule ruleWithVariableInclude(String... vars)
    {
        Rule rule = new Rule();
        VariableRequirement vr = new VariableRequirement();
        vr.setAll(List.of(vars));
        Requirements req = new Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        return rule;
    }


    private static Rule ruleWithVariableExclude(String... vars)
    {
        Rule rule = new Rule();
        VariableRequirement vr = new VariableRequirement();
        vr.setNone(List.of(vars));
        Requirements req = new Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        return rule;
    }


    private static Rule ruleWithVariableIncludeExclude(List<String> include, List<String> exclude)
    {
        Rule rule = new Rule();
        VariableRequirement vr = new VariableRequirement();
        vr.setAll(include);
        vr.setNone(exclude);
        Requirements req = new Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        return rule;
    }


    private static DataTableMeta metaWith(String... columnNames)
    {
        var columns = new net.cumba.datatable.DataTableColumnMeta[columnNames.length];
        for (int i = 0; i < columnNames.length; i++)
        {
            columns[i] = net.cumba.datatable.DataTableColumnMeta.builder().index(i)
                    .name(columnNames[i]).build();
        }
        return DataTableMeta.builder().name("TEST").label("Test").rowCount(0).totalRowCount(0)
                .columns(columns).build();
    }

}
