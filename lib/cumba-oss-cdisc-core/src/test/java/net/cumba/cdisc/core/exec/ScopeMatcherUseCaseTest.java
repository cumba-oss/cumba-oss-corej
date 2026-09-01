package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.Scope;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ScopeMatcher#filterByUseCase} and the {@link ScopeMatcher#matchesUseCase}
 * branches not covered by {@link ScopeMatcherTest}.
 */
class ScopeMatcherUseCaseTest
{

    @Test
    void matchesUseCase_nullUseCase_returnsTrue()
    {
        Rule rule = ruleWithUseCase("INDH");
        assertTrue(ScopeMatcher.matchesUseCase(rule, null));
    }


    @Test
    void matchesUseCase_nullScope_returnsTrue()
    {
        Rule rule = new Rule();
        // no scope set
        assertTrue(ScopeMatcher.matchesUseCase(rule, "INDH"));
    }


    @Test
    void matchesUseCase_emptyUseCaseInScope_returnsTrue()
    {
        Rule rule = new Rule();
        Scope s = new Scope();
        s.setUseCase("");
        rule.setScope(s);
        assertTrue(ScopeMatcher.matchesUseCase(rule, "INDH"));
    }


    @Test
    void matchesUseCase_caseInsensitive()
    {
        Rule rule = ruleWithUseCase("indh");
        assertTrue(ScopeMatcher.matchesUseCase(rule, "INDH"));
    }


    @Test
    void matchesUseCase_csvList_matchesOne()
    {
        Rule rule = ruleWithUseCase("INDH, PROD ,NONCLIN");
        assertTrue(ScopeMatcher.matchesUseCase(rule, "PROD"));
        assertTrue(ScopeMatcher.matchesUseCase(rule, "NONCLIN"));
        // "noncl" is only a partial substring of "NONCLIN" — matching is full-string
        // case-insensitive, so partial inputs must not match.
        assertFalse(ScopeMatcher.matchesUseCase(rule, "noncl"));
    }


    @Test
    void matchesUseCase_csvList_noMatch()
    {
        Rule rule = ruleWithUseCase("INDH, PROD");
        assertFalse(ScopeMatcher.matchesUseCase(rule, "BLA"));
    }


    @Test
    void filterByUseCase_nullUseCase_returnsAllRulesCopy()
    {
        Rule a = new Rule();
        Rule b = new Rule();
        List<Rule> rules = List.of(a, b);
        List<Rule> out = ScopeMatcher.filterByUseCase(rules, null);
        assertEquals(2, out.size());
        // The implementation must return a fresh list to allow caller mutation.
        assertNotSame(rules, out);
        assertTrue(out.contains(a));
        assertTrue(out.contains(b));
    }


    @Test
    void filterByUseCase_filtersByMatching()
    {
        Rule a = ruleWithUseCase("INDH");
        Rule b = ruleWithUseCase("PROD");
        Rule c = new Rule(); // null scope → matches anything
        List<Rule> rules = List.of(a, b, c);

        List<Rule> indh = ScopeMatcher.filterByUseCase(rules, "INDH");
        assertEquals(2, indh.size());
        assertTrue(indh.contains(a));
        assertTrue(indh.contains(c));
        assertFalse(indh.contains(b));
    }


    @Test
    void filterByUseCase_emptyInput_returnsEmpty()
    {
        List<Rule> empty = ScopeMatcher.filterByUseCase(List.of(), "INDH");
        assertTrue(empty.isEmpty());
    }


    @Test
    void matchesClass_nullScope_returnsTrue()
    {
        Rule rule = new Rule();
        assertTrue(ScopeMatcher.matchesClass(rule, "EVENTS"));
    }


    @Test
    void matchesClass_nullClasses_returnsTrue()
    {
        Rule rule = new Rule();
        Scope s = new Scope();
        // no ClassScope set
        rule.setScope(s);
        assertTrue(ScopeMatcher.matchesClass(rule, "EVENTS"));
    }


    @Test
    void matchesClass_nullClassNameNoIncludeNoExclude_returnsTrue()
    {
        // Per Fix #41: strict-on-null. If both include & exclude are absent, the rule isn't
        // class-scoped at all → applies.
        Rule rule = new Rule();
        Scope s = new Scope();
        net.cumba.cdisc.core.model.ClassScope cls = new net.cumba.cdisc.core.model.ClassScope();
        s.setClasses(cls);
        rule.setScope(s);
        assertTrue(ScopeMatcher.matchesClass(rule, null));
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------


    private static Rule ruleWithUseCase(String useCase)
    {
        Rule rule = new Rule();
        Scope s = new Scope();
        s.setUseCase(useCase);
        rule.setScope(s);
        return rule;
    }
}
