package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
import java.util.List;
import net.cumba.corej.core.model.ClassScope;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Tests for the reason-bearing scope describers ({@code describeDomainMismatch},
 * {@code describeClassMismatch}, {@code describeVariablesMismatch}) and their parity with the
 * boolean API (which is implemented on top of them).
 */
class ScopeMatcherDescribeTest
{

    // ------------------------------------------------------------------
    // Fixtures
    // ------------------------------------------------------------------

    private static Rule ruleWithDomainInclude(String... domains)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setInclude(Arrays.asList(domains));
        scope.setDomains(ds);
        rule.setScope(scope);
        return rule;
    }


    private static Rule ruleWithDomainExclude(String... domains)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setExclude(Arrays.asList(domains));
        scope.setDomains(ds);
        rule.setScope(scope);
        return rule;
    }


    private static Rule ruleWithClassInclude(String... classes)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        ClassScope cs = new ClassScope();
        cs.setInclude(Arrays.asList(classes));
        scope.setClasses(cs);
        rule.setScope(scope);
        return rule;
    }


    private static Rule ruleWithClassExclude(String... classes)
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        ClassScope cs = new ClassScope();
        cs.setExclude(Arrays.asList(classes));
        scope.setClasses(cs);
        rule.setScope(scope);
        return rule;
    }


    private static Rule ruleWithVariableInclude(String... vars)
    {
        Rule rule = new Rule();
        VariableRequirement vr = new VariableRequirement();
        vr.setAll(Arrays.asList(vars));
        Requirements req = new Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        return rule;
    }


    private static Rule ruleWithVariableExclude(String... vars)
    {
        Rule rule = new Rule();
        VariableRequirement vr = new VariableRequirement();
        vr.setNone(Arrays.asList(vars));
        Requirements req = new Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        return rule;
    }


    private static DataTableMeta meta(String... columns)
    {
        return MockTable.withColumns(columns).getMetaData();
    }

    // ------------------------------------------------------------------
    // Domain describer
    // ------------------------------------------------------------------


    @Test
    void domainMatch_returnsNull()
    {
        Rule rule = ruleWithDomainInclude("AE", "CM");
        assertNull(ScopeMatcherCalls.describeDomainMismatch(rule, "AE"));
        assertNull(ScopeMatcherCalls.describeDomainMismatch(rule, "CM"));
    }


    @Test
    void domainIncludeMiss_namesTheEntryList()
    {
        Rule rule = ruleWithDomainInclude("AE", "CM");
        assertEquals("domain EX not in Scope.Domains.Include [AE, CM]",
                ScopeMatcherCalls.describeDomainMismatch(rule, "EX"));
    }


    @Test
    void domainExcludeHit_namesTheEntry()
    {
        Rule rule = ruleWithDomainExclude("SUPP--");
        assertEquals("domain SUPPAE matches Scope.Domains.Exclude entry SUPP--",
                ScopeMatcherCalls.describeDomainMismatch(rule, "SUPPAE"));
    }


    @Test
    void domainExcludeLiteralHit_namesTheEntry()
    {
        Rule rule = ruleWithDomainExclude("DM", "AE");
        assertEquals("domain AE matches Scope.Domains.Exclude entry AE",
                ScopeMatcherCalls.describeDomainMismatch(rule, "AE"));
    }


    @Test
    void domainExcludeSplitBaseHit_namesTheBaseEntry()
    {
        // LB1 is a split dataset whose base LB matches the Exclude entry.
        Rule rule = ruleWithDomainExclude("LB");
        String reason = ScopeMatcherCalls.describeDomainMismatch(rule, "LB1");
        assertNotNull(reason);
        assertTrue(reason.contains("Scope.Domains.Exclude entry LB"), reason);
    }


    @Test
    void domainNoScope_returnsNull()
    {
        assertNull(ScopeMatcherCalls.describeDomainMismatch(new Rule(), "DM"));
        assertNull(ScopeMatcherCalls.describeDomainMismatch(ruleWithDomainInclude("TE"), null));
    }


    @Test
    void domainSplitFilterTrue_describesNonSplitMiss()
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setIncludeSplitDatasets(Boolean.TRUE);
        scope.setDomains(ds);
        rule.setScope(scope);
        assertEquals(
                "domain DM is not a split dataset but Scope.Domains.Include_Split_Datasets is true",
                ScopeMatcherCalls.describeDomainMismatch(rule, "DM"));
        assertNull(ScopeMatcherCalls.describeDomainMismatch(rule, "LB1"));
    }


    @Test
    void domainSplitFilterFalse_describesSplitHit()
    {
        Rule rule = new Rule();
        Scope scope = new Scope();
        DomainScope ds = new DomainScope();
        ds.setIncludeSplitDatasets(Boolean.FALSE);
        scope.setDomains(ds);
        rule.setScope(scope);
        assertEquals(
                "domain LB1 is a split dataset but Scope.Domains.Include_Split_Datasets is false",
                ScopeMatcherCalls.describeDomainMismatch(rule, "LB1"));
        assertNull(ScopeMatcherCalls.describeDomainMismatch(rule, "DM"));
    }

    // ------------------------------------------------------------------
    // Class describer
    // ------------------------------------------------------------------


    @Test
    void classMatch_returnsNull()
    {
        Rule rule = ruleWithClassInclude("EVENTS");
        assertNull(ScopeMatcher.describeClassMismatch(rule, "EVENTS"));
        assertNull(ScopeMatcher.describeClassMismatch(new Rule(), null));
    }


    @Test
    void classIncludeMiss_namesTheEntryList()
    {
        Rule rule = ruleWithClassInclude("FINDINGS");
        assertEquals("class EVENTS not in Scope.Classes.Include [FINDINGS]",
                ScopeMatcher.describeClassMismatch(rule, "EVENTS"));
    }


    @Test
    void classNullStrict_describesUndeterminedClass()
    {
        // Fix #41 strict-on-null: a class-scoped rule on a dataset whose class is unknown.
        Rule rule = ruleWithClassInclude("EVENTS");
        assertEquals("dataset class undetermined but rule has a Classes scope",
                ScopeMatcher.describeClassMismatch(rule, null));
        Rule excl = ruleWithClassExclude("EVENTS");
        assertEquals("dataset class undetermined but rule has a Classes scope",
                ScopeMatcher.describeClassMismatch(excl, null));
    }


    @Test
    void classExcludeHit_namesTheEntry()
    {
        Rule rule = ruleWithClassExclude("EVENTS");
        assertEquals("class EVENTS matches Scope.Classes.Exclude entry EVENTS",
                ScopeMatcher.describeClassMismatch(rule, "EVENTS"));
    }


    @Test
    void classExcludeFindingsSubsumption_namesTheFindingsEntry()
    {
        // FINDINGS ABOUT datasets are subsumed under FINDINGS-scoped excludes.
        Rule rule = ruleWithClassExclude("FINDINGS");
        assertEquals("class FINDINGS ABOUT matches Scope.Classes.Exclude entry FINDINGS",
                ScopeMatcher.describeClassMismatch(rule, "FINDINGS ABOUT"));
    }

    // ------------------------------------------------------------------
    // Variables describer
    // ------------------------------------------------------------------


    @Test
    void variablesMatch_returnsNull()
    {
        Rule rule = ruleWithVariableInclude("AESTDTC");
        assertNull(ScopeMatcherCalls.describeVariablesMismatch(rule, meta("AESTDTC", "USUBJID")));
        assertNull(ScopeMatcherCalls.describeVariablesMismatch(new Rule(), meta("USUBJID")));
        assertNull(ScopeMatcherCalls.describeVariablesMismatch(rule, null));
    }


    @Test
    void variablesIncludeMiss_namesTheVariable()
    {
        Rule rule = ruleWithVariableInclude("AESTDTC");
        assertEquals("Requirements.Variables.All variable AESTDTC not present in dataset",
                ScopeMatcherCalls.describeVariablesMismatch(rule, meta("USUBJID")));
    }


    @Test
    void variablesExcludeHit_namesTheVariable()
    {
        Rule rule = ruleWithVariableExclude("QVAL");
        assertEquals("Requirements.Variables.None variable QVAL present in dataset",
                ScopeMatcherCalls.describeVariablesMismatch(rule, meta("USUBJID", "QVAL")));
    }

    // ------------------------------------------------------------------
    // Parity: boolean API == (describer == null)
    // ------------------------------------------------------------------


    @Test
    void booleanApiAgreesWithDescribers()
    {
        List<Rule> rules = List.of(new Rule(), ruleWithDomainInclude("AE", "CM"),
                ruleWithDomainInclude("ALL"), ruleWithDomainInclude("SUPP--"),
                ruleWithDomainExclude("SUPP--"), ruleWithDomainExclude("DM", "NONE"),
                ruleWithDomainExclude("ALL"), ruleWithDomainInclude("LB"),
                ruleWithDomainExclude("LB"));
        List<String> domains = Arrays.asList("AE", "CM", "DM", "EX", "LB", "LB1", "SUPPAE",
                "SUPPLBHM", "APFACM", "RELREC", null);
        for (Rule rule : rules)
        {
            for (String domain : domains)
            {
                assertEquals(ScopeMatcherCalls.describeDomainMismatch(rule, domain) == null,
                        ScopeMatcherCalls.matchesDomain(rule, domain),
                        "domain parity for " + domain + " on " + describeScope(rule));
            }
        }

        List<Rule> classRules = List.of(new Rule(), ruleWithClassInclude("EVENTS"),
                ruleWithClassInclude("FINDINGS"), ruleWithClassExclude("FINDINGS"),
                ruleWithClassExclude("EVENTS"), ruleWithClassInclude("ALL"));
        List<String> classes = Arrays.asList("EVENTS", "FINDINGS", "FINDINGS ABOUT",
                "SPECIAL PURPOSE", null);
        for (Rule rule : classRules)
        {
            for (String cls : classes)
            {
                assertEquals(ScopeMatcher.describeClassMismatch(rule, cls) == null,
                        ScopeMatcherCalls.matchesClass(rule, cls), "class parity for " + cls);
            }
        }

        List<Rule> varRules = List.of(new Rule(), ruleWithVariableInclude("AESTDTC"),
                ruleWithVariableInclude("AESTDTC", "AEENDTC"), ruleWithVariableExclude("QVAL"));
        List<DataTableMeta> metas = Arrays.asList(meta("AESTDTC", "AEENDTC", "QVAL"),
                meta("AESTDTC"), meta("USUBJID"), null);
        for (Rule rule : varRules)
        {
            for (DataTableMeta m : metas)
            {
                assertEquals(ScopeMatcherCalls.describeVariablesMismatch(rule, m) == null,
                        ScopeMatcherCalls.matchesVariables(rule, m), "variables parity");
            }
        }
    }


    private static String describeScope(Rule rule)
    {
        Scope scope = rule.getScope();
        if (scope == null || scope.getDomains() == null)
        {
            return "(no scope)";
        }
        return "Include=" + scope.getDomains().getInclude() + " Exclude="
                + scope.getDomains().getExclude();
    }
}
