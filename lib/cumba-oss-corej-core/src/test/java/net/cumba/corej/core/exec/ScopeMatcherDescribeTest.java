package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;
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
 * {@code describeClassMismatch}, {@code describeVariablesMismatch}). ⚑ A parity test against the
 * boolean {@code matches*} API used to live here; that API left {@code src/main} with U1 of
 * {@code PLAN-retire-dead-multi-match-lookup}, its test forms in {@link ScopeMatcherCalls} are
 * {@code describe*(…) == null} by construction, and the parity check compared that helper with
 * itself — so it was deleted.
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
}
