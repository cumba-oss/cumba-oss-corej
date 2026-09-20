package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ScopeMatcher#matchesUseCase} (its {@code filterByUseCase} sibling went with D121
 * — main-dead after phase 7) and the branches not covered by {@link ScopeMatcherTest}.
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
        net.cumba.corej.core.model.ClassScope cls = new net.cumba.corej.core.model.ClassScope();
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
