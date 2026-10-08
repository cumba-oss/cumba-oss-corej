package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;
import java.util.UUID;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W2 WX ({@code PLAN-wildcard-expander-name-slots} X1 / X3): the expander asks
 * {@code NameSlots} where a quoted template is a column name, instead of the hand-kept
 * exists-family list — so a quoted template at a metadata accessor's name slot expands like the one
 * at {@code var_exists}'s. Two slots are excluded in all three readers (collection, the bare-star
 * guard, substitution): {@code find_vars} (W2 — it resolves its template itself at run time, D93c)
 * and {@code cross_dataset_variable_metadata}'s {@code domain} ({@code W3} — {@code "*"} there
 * means every dataset, not a template).
 */
class WildcardExpanderNameSlotTest
{

    private static Rule nativeRule(String expression)
    {
        Rule r = new Rule();
        r.setId(UUID.randomUUID().toString());
        RuleCore core = new RuleCore();
        core.setId("TEST-WX");
        core.setStatus("Published");
        core.setVersion("1");
        r.setCore(core);
        r.setDescription("name-slot wildcard test");
        r.setSensitivity(Sensitivity.RECORD);
        r.setCheck(
                new CheckConditionExpression(CheckExpressionParser.parse(expression), expression));
        Outcome o = new Outcome();
        o.setMessage("v");
        r.setOutcome(o);
        return r;
    }


    private static String exprOf(Rule r)
    {
        return ExpressionPrinter.print(((CheckConditionExpression) r.getCheck()).expr());
    }


    @Test
    void aQuotedTemplateAtAMetadataAccessorNameSlotExpands()
    {
        IDataTable table = MockTable.withColumns("TRT01PN", "TRT02PN", "STUDYID");
        Rule template = nativeRule("var_label(\"TRTxxPN\") == \"\"");
        assertEquals(Set.of("TRTxxPN"), WildcardExpander.collectWildcardNames(template));
        List<String> printed = WildcardExpander.expand(template, table.getMetaData()).stream()
                .map(WildcardExpanderNameSlotTest::exprOf).toList();
        assertEquals(List.of("var_label(\"TRT01PN\") == \"\"", "var_label(\"TRT02PN\") == \"\""),
                printed);
    }


    @Test
    void aLevelLiteralIsNeverATemplate()
    {
        // the one-argument accessor form: a level literal is a level, not a name (V12)
        assertEquals(Set.of(), WildcardExpander
                .collectWildcardNames(nativeRule("not contains(var_label(\"LIBRARY\"), \"*\")")));
    }


    @Test
    void findVarsKeepsItsTemplateForItsOwnRunTimeResolution()
    {
        assertEquals(Set.of(),
                WildcardExpander
                        .collectWildcardNames(nativeRule("count(find_vars(\"TRTPGy\")) == 0")),
                "find_vars alone is not a template rule (W2)");
        IDataTable table = MockTable.withColumns("TRT01PN", "TRT02PN", "TRTPG1");
        List<String> printed = WildcardExpander
                .expand(nativeRule("var_exists(\"TRTxxPN\") and count(find_vars(\"TRTPGy\")) == 0"),
                        table.getMetaData())
                .stream().map(WildcardExpanderNameSlotTest::exprOf).toList();
        assertEquals(
                List.of("var_exists(\"TRT01PN\") and count(find_vars(\"TRTPGy\")) == 0",
                        "var_exists(\"TRT02PN\") and count(find_vars(\"TRTPGy\")) == 0"),
                printed, "the find_vars entry is never substituted");
    }


    @Test
    void crossDatasetDomainStarIsEveryDatasetNotATemplate()
    {
        String star = "cross_dataset_variable_metadata(\"label\", domain=\"*\") != \"\"";
        assertEquals(Set.of(), WildcardExpander.collectWildcardNames(nativeRule(star)),
                "domain=\"*\" alone is not a template rule (W3)");
        // with a real template beside it: the bare-star guard must not count domain="*" (it
        // would refuse the expansion for want of a *N / *C anchor) and the substitution must
        // leave it as written
        IDataTable table = MockTable.withColumns("A01N", "A02N");
        List<String> printed = WildcardExpander
                .expand(nativeRule("var_exists(\"AxxN\") and " + star), table.getMetaData())
                .stream().map(WildcardExpanderNameSlotTest::exprOf).toList();
        assertEquals(2, printed.size(), printed.toString());
        assertTrue(printed.stream().allMatch(p -> p.contains("domain=\"*\"")), printed.toString());
    }
}
