package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link ScopeMatcher#describeUseCaseMismatch} / {@link ScopeMatcher#matchesUseCase} —
 * the {@code Scope.Use_Case} axis wired into production by {@code PLAN-use-case-scope-filter}
 * (owner ruling X1; its {@code filterByUseCase} sibling went with D121) — and the branches not
 * covered by {@link ScopeMatcherTest}.
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

    // ---- describeUseCaseMismatch: the reason text the Skipped_Rules row carries ----


    @Test
    void describe_singleCodeMismatch_namesTheRunValueAndTheRuleCodes()
    {
        assertEquals("use case NONCLIN not in Scope.Use_Case [INDH]",
                ScopeMatcher.describeUseCaseMismatch(ruleWithUseCase("INDH"), "NONCLIN"));
    }


    @Test
    void describe_multiCodeMismatch_listsTheStrippedCodesInAuthoredOrder()
    {
        assertEquals("use case INDH not in Scope.Use_Case [NONCLIN, PROD]",
                ScopeMatcher.describeUseCaseMismatch(ruleWithUseCase(" NONCLIN ,PROD"), "INDH"));
    }


    @Test
    void describe_anyListedCodeIncludesTheRule_T1_1()
    {
        // Ruling T1-1: "INDH, PROD" is not "a different use case" under PROD — it runs.
        assertNull(ScopeMatcher.describeUseCaseMismatch(ruleWithUseCase("INDH, PROD"), "PROD"));
        assertNull(ScopeMatcher.describeUseCaseMismatch(ruleWithUseCase("INDH, PROD"), "prod"));
    }


    @Test
    void describe_runValueIsStrippedAndItsSpellingIsReported()
    {
        assertNull(ScopeMatcher.describeUseCaseMismatch(ruleWithUseCase("PROD"), " PROD "));
        assertEquals("use case indh not in Scope.Use_Case [PROD]",
                ScopeMatcher.describeUseCaseMismatch(ruleWithUseCase("PROD"), "  indh "));
    }


    @Test
    void describe_blankRunValue_isNoUseCase()
    {
        assertNull(ScopeMatcher.describeUseCaseMismatch(ruleWithUseCase("INDH"), "   "));
        assertNull(ScopeMatcher.describeUseCaseMismatch(ruleWithUseCase("INDH"), ""));
    }


    /**
     * The one behaviour change inside the matcher (plan §1): a whitespace-only or comma-only rule
     * value used to split to {@code [""]}, match no use case and so be excluded under EVERY use
     * case. It holds no code, so it reads as declaring none. (On a loaded rule it never gets here —
     * the loader rejects it, ruling T1-4.)
     */
    @Test
    void describe_ruleValueHoldingNoCode_declaresNoUseCase()
    {
        for (String blank : List.of(" ", ",", " , ", ""))
        {
            assertNull(ScopeMatcher.describeUseCaseMismatch(ruleWithUseCase(blank), "INDH"),
                    "'" + blank + "'");
            assertTrue(ScopeMatcher.matchesUseCase(ruleWithUseCase(blank), "INDH"),
                    "'" + blank + "'");
        }
    }


    @Test
    void useCaseCodes_splitsStripsAndDropsEmptyCodes()
    {
        assertEquals(List.of(), ScopeMatcher.useCaseCodes(null));
        assertEquals(List.of(), ScopeMatcher.useCaseCodes(" , "));
        assertEquals(List.of("INDH", "PROD"), ScopeMatcher.useCaseCodes(" INDH,,PROD ,"));
        assertEquals(List.of("INDH;PROD"), ScopeMatcher.useCaseCodes("INDH;PROD"),
                "a non-comma separator is one code — which is why the loader rejects it");
    }


    @Test
    void isWellFormedUseCaseValue_isR410AndR410a()
    {
        for (String ok : List.of("INDH", "INDH, PROD", "NONCLIN,INDH , PROD"))
        {
            assertTrue(ScopeMatcher.isWellFormedUseCaseValue(ok), ok);
        }
        for (String bad : java.util.Arrays.asList(null, "", " ", ",", "INDH,", "indh", " INDH",
                "INDH;PROD", "INDH1", "INDH PROD", "INDH, PROD, INDH"))
        {
            assertFalse(ScopeMatcher.isWellFormedUseCaseValue(bad), String.valueOf(bad));
        }
    }


    @Test
    void rulesInUseCase_keepsMatchingUndeclaredAndLoadErrorRules()
    {
        Rule indh = ruleWithUseCase("INDH");
        Rule prod = ruleWithUseCase("PROD");
        Rule none = new Rule();
        Rule broken = ruleWithUseCase("PROD");
        broken.setLoadError("synthetic");
        List<Rule> all = List.of(indh, prod, none, broken);

        assertEquals(List.of(indh, none, broken), ScopeMatcher.rulesInUseCase(all, "indh"));
        assertSame(all, ScopeMatcher.rulesInUseCase(all, null), "no use case: the same list");
        assertSame(all, ScopeMatcher.rulesInUseCase(all, "  "), "blank: the same list");
        List<Rule> nothingExcluded = List.of(indh, none);
        assertSame(nothingExcluded, ScopeMatcher.rulesInUseCase(nothingExcluded, "INDH"),
                "nothing excluded: the same list");
    }


    @Test
    void matchesUseCase_agreesWithDescribe()
    {
        Rule rule = ruleWithUseCase("INDH");
        assertFalse(ScopeMatcher.matchesUseCase(rule, "NONCLIN"));
        assertTrue(ScopeMatcher.matchesUseCase(rule, "INDH"));
    }


    @Test
    void matchesClass_nullScope_returnsTrue()
    {
        Rule rule = new Rule();
        assertTrue(ScopeMatcherCalls.matchesClass(rule, "EVENTS"));
    }


    @Test
    void matchesClass_nullClasses_returnsTrue()
    {
        Rule rule = new Rule();
        Scope s = new Scope();
        // no ClassScope set
        rule.setScope(s);
        assertTrue(ScopeMatcherCalls.matchesClass(rule, "EVENTS"));
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
        assertTrue(ScopeMatcherCalls.matchesClass(rule, null));
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
