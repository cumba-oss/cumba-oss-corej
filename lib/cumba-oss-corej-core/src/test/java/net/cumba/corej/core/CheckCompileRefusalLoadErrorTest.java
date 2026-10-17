package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.model.Rule;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * PACK I E6 (PI-Q5 ⓐ): a Check the compiler refuses is a <b>load error</b> on a non-template rule —
 * as a compiled Binding's refusal always was — instead of a rule that loads clean with no
 * {@code checkExpr} and reports ERROR at every run. The load error carries the compiler's own
 * reason. A template (a Check carrying a wildcard name, {@link WildcardExpander#isTemplate}) is
 * expanded per dataset and loads as before, without a {@code checkExpr}.
 */
class CheckCompileRefusalLoadErrorTest
{

    private static Rule load(String check) throws Exception
    {
        return loadBody("\"Check\":{\"expression\":\"" + check.replace("\"", "\\\"") + "\"}");
    }


    private static Rule load2(String error, String warning) throws Exception
    {
        return loadBody("\"Check\":{\"ERROR\":{\"expression\":\"" + error.replace("\"", "\\\"")
                + "\"},\"WARNING\":{\"expression\":\"" + warning.replace("\"", "\\\"") + "\"}}");
    }


    private static Rule loadBody(String checkJson) throws Exception
    {
        String body = "{\"Core\":{\"Id\":\"E6-1\"},\"Sensitivity\":\"Record\","
                + "\"Scope\":{\"Domains\":{\"Include\":[\"ALL\"]}}," + checkJson
                + ",\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}";
        Rule r = RulePackageLoader.loadFromString("{\"rules\":{\"x\":" + body + "}}").getRules()
                .get("x");
        assertNotNull(r, "the rule parses");
        return r;
    }


    /**
     * The guide authors both under {@code not}; the positive form has no plan. Before E6 it loaded
     * clean (ERROR "the Check has no native expression form" at every run).
     */
    @Test
    @DisplayName("the positive form of a negation-dispatched operator is a load error naming it")
    void thePositiveFormOfANegationDispatchedOperatorIsALoadError() throws Exception
    {
        Rule sorted = load("is_sorted_by(TSSEQ, by=[asc(TSGRPID)], within=USUBJID)");
        assertNotNull(sorted.getLoadError(), "is_sorted_by(…) must not load clean");
        assertTrue(sorted.getLoadError().contains("is_sorted_by"), sorted.getLoadError());
        assertTrue(sorted.getLoadError().contains("not is_sorted_by"), sorted.getLoadError());
        assertNull(sorted.getCheckExpr());

        Rule next = load("has_next_corresponding_record(AESEQ, AEDECOD, within=USUBJID, by=AESEQ)");
        assertNotNull(next.getLoadError(), "has_next_corresponding_record(…) must not load clean");
        assertTrue(next.getLoadError().contains("has_next_corresponding_record"),
                next.getLoadError());
        assertTrue(next.getLoadError().contains("not has_next_corresponding_record"),
                next.getLoadError());
        assertNull(next.getCheckExpr());
    }


    /** Control: the negated spellings load and compile as before. */
    @Test
    void theNegatedSpellingsLoadAndCompile() throws Exception
    {
        for (String check : new String[]
        {
                "not is_sorted_by(TSSEQ, by=[asc(TSGRPID)], within=USUBJID)",
                "not has_next_corresponding_record(AESEQ, AEDECOD, within=USUBJID, by=AESEQ)"
        })
        {
            Rule r = load(check);
            assertNull(r.getLoadError(), check + ": " + r.getLoadError());
            assertNotNull(r.getCheckExpr(), check + " compiles");
        }
    }


    /**
     * The reader's own refusal reaches the author through a Check too (it did through a compiled
     * Binding only — {@code PositionalKeepMissingsTest}): the W4 / W8 "read by keyword only".
     */
    @Test
    void aReadersRefusalOfACheckIsALoadErrorWithTheReadersText() throws Exception
    {
        Rule r = load("has_multiple_values_for(A, K, W, true)");
        assertNotNull(r.getLoadError(), "must not load clean");
        assertTrue(r.getLoadError().contains("read by keyword only"), r.getLoadError());
        assertTrue(!r.getLoadError().contains("Expression construct not supported"),
                "the compiler's prefix is stripped: " + r.getLoadError());
        assertNull(r.getCheckExpr());
    }


    /** A multi-level rule names the level the compiler refused. */
    @Test
    void aRefusedLevelOfAMultiLevelRuleIsNamed() throws Exception
    {
        Rule r = load2("A == \"x\"", "is_sorted_by(TSSEQ, by=[asc(TSGRPID)], within=USUBJID)");
        assertNotNull(r.getLoadError(), "must not load clean");
        assertTrue(r.getLoadError().contains("WARNING"), r.getLoadError());
        assertTrue(r.getLoadError().contains("is_sorted_by"), r.getLoadError());
        assertNull(r.getCheckExpr());
    }


    /** A template is expanded per dataset: no checkExpr at load, and no load error (as today). */
    @Test
    void aTemplateStillLoadsClean() throws Exception
    {
        Rule template = load("empty(TRTxxP)");
        assertTrue(WildcardExpander.isTemplate(template), "the fixture is a template");
        assertNull(template.getLoadError(), template.getLoadError());
        assertNull(template.getCheckExpr(), "a template compiles per expansion, not at load");
    }
}
