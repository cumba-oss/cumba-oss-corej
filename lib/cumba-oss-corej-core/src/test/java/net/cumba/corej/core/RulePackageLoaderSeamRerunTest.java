package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.report.Severity;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-stage-a-parameter-type-arming} review r1 seams M1 / r2: the loader's seam pass stands
 * the stage-A-covered cases down for a rule with a Check, trusting stage A to judge them — so when
 * stage A cannot judge the rule ({@code CHECKER_FAILURE}, never armed) {@code installNativeExpr}
 * re-runs the seams unjudged ({@link RulePackageLoader#rerunSeamsUnjudged}) and files the seam's
 * own message. The method is tested directly, and its call site through a rule whose stage A walk
 * genuinely fails — a {@code Binary} with no operator, which the walk switches on — beside an
 * inline literal the seam refuses.
 */
class RulePackageLoaderSeamRerunTest
{

    private static final String LITERAL = "IDVAR not in referenced_dataset_variables(\"RDOMAIN\")";

    private static SequencedMap<Severity, Expr> levels(Expr expr)
    {
        SequencedMap<Severity, Expr> levels = new LinkedHashMap<>();
        levels.put(Severity.ERROR, expr);
        return levels;
    }


    private static Rule rule(Expr check)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("CFX-RERUN");
        rule.setCore(core);
        rule.setCheck(new CheckConditionExpression(check, "hand-built"));
        return rule;
    }


    @Test
    void theRerunFilesTheSeamsOwnMessageAndLeavesACleanRuleAlone()
    {
        Rule refused = rule(CheckExpressionParser.parse(LITERAL));
        assertTrue(RulePackageLoader.rerunSeamsUnjudged(refused,
                levels(CheckExpressionParser.parse(LITERAL)).values(), null));
        assertNotNull(refused.getLoadError());
        assertTrue(
                refused.getLoadError()
                        .contains("takes a column reference, not the literal RDOMAIN"),
                refused.getLoadError());
        Rule clean = rule(
                CheckExpressionParser.parse("IDVAR not in referenced_dataset_variables(RDOMAIN)"));
        assertFalse(RulePackageLoader.rerunSeamsUnjudged(clean,
                levels(CheckExpressionParser
                        .parse("IDVAR not in referenced_dataset_variables(RDOMAIN)")).values(),
                null));
        assertNull(clean.getLoadError());
        // the Precondition is re-run too
        Rule pre = rule(CheckExpressionParser.parse("empty(AETERM)"));
        assertTrue(RulePackageLoader.rerunSeamsUnjudged(pre,
                levels(CheckExpressionParser.parse("empty(AETERM)")).values(),
                CheckExpressionParser.parse("not empty(dy(\"AESTDTC\", RFSTDTC))")));
        assertTrue(pre.getLoadError().contains("not the literal AESTDTC"), pre.getLoadError());
    }


    @Test
    void installNativeExprRerunsTheSeamsWhenStageAFailsOnTheRule()
    {
        // a Binary with no operator: canonicalisation and the seam pass carry it (neither reads
        // the operator), stage A's walk switches on it and throws — CHECKER_FAILURE on a tree the
        // loader otherwise accepts (no test seam). It is the FIRST conjunct, so the literal in
        // the second is never judged by stage A, exactly the gap the re-run closes.
        Expr broken = new Expr.Binary(null, new Expr.Ref("X", OperandKind.COLUMN),
                new Expr.Lit(Expr.LitKind.NUMBER, 1.0));
        Expr check = new Expr.And(List.of(broken, CheckExpressionParser.parse(LITERAL)));
        Rule rule = rule(check);
        RulePackageLoader.installNativeExpr(rule);
        assertNotNull(rule.getLoadError(), "stage A failed and the seams were re-run");
        assertTrue(
                rule.getLoadError().contains("takes a column reference, not the literal RDOMAIN"),
                rule.getLoadError());
        assertFalse(rule.getLoadError().contains("stage A: PARAMETER_TYPE"),
                "stage A did not judge it: " + rule.getLoadError());
    }


    /**
     * Runbook W1a fold, R5 review A-M1: the re-run judged the LEVELS of a set function's or a
     * per-call parameter's operands, of a {@code $} inside {@code filter=} and of a name-list
     * binding's reads over the compiled bindings only — so a per-row operand in a Check level or
     * the Precondition, after a {@code CHECKER_FAILURE}, loaded and read row 0 silently. Each case
     * is a level the loader otherwise accepts (the rule's bindings load clean), judged on the
     * re-run path with the seam's own sentence.
     */
    @Test
    void theRerunJudgesTheLevelOfEveryCheckLevelAndThePrecondition() throws Exception
    {
        String[][] cases =
        {
                {
                        "\"a\" in minus($p, subtract=[\"x\"])",
                        "cannot read the per-row or per-variable operand `$p`"
                },
                {
                        "extract_metadata(name=$p) == \"AE\"",
                        "argument 'name' of 'extract_metadata' in the Check is read once"
                },
                {
                        "record_count(domain=TS, filter=(TSVAL == $c)) > 0",
                        "argument 'filter' of 'record_count' in the Check reads the"
                                + " per-row or per-variable value `$c`"
                },
                {
                        "AETERM in $keys", "the Check: the binding $keys holds column names"
                }
        };
        for (String[] c : cases)
        {
            Rule rule = checkLess();
            assertTrue(RulePackageLoader.rerunSeamsUnjudged(rule,
                    levels(CheckExpressionParser.parse(c[0])).values(), null), c[0]);
            assertTrue(String.valueOf(rule.getLoadError()).contains(c[1]),
                    c[0] + " → " + rule.getLoadError());
            // the Precondition is judged alike
            Rule pre = checkLess();
            assertTrue(RulePackageLoader.rerunSeamsUnjudged(pre,
                    levels(CheckExpressionParser.parse("empty(AETERM)")).values(),
                    CheckExpressionParser.parse(c[0])), c[0]);
            assertTrue(
                    String.valueOf(pre.getLoadError())
                            .contains(c[1].replace("the Check", "the Precondition")),
                    c[0] + " → " + pre.getLoadError());
        }
        // the control: the same bindings read at dataset level / in a name slot load clean
        for (String clean : List.of("\"a\" in minus([\"a\"], subtract=[\"x\"])",
                "record_count(group=$keys) > 0", "not empty(values($keys))"))
        {
            Rule rule = checkLess();
            assertFalse(
                    RulePackageLoader.rerunSeamsUnjudged(rule,
                            levels(CheckExpressionParser.parse(clean)).values(), null),
                    clean + " → " + rule.getLoadError());
        }
    }


    /**
     * A Check-less rule binding a per-row value {@code $p}, a per-variable value {@code $c} and a
     * name list {@code $keys} — it loads clean, so every refusal above is the re-run's own.
     */
    private static Rule checkLess() throws Exception
    {
        Map<String, Object> rule = new LinkedHashMap<>();
        rule.put("Core", Map.of("Id", "CFX-RERUN-LEVEL"));
        rule.put("Bindings",
                List.of(Map.of("name", "$p", "expression", "AETERM"),
                        Map.of("name", "$c", "expression", "var_label(\"DATA\")"),
                        Map.of("name", "$keys", "expression", "[STUDYID, USUBJID]")));
        rule.put("Match_Datasets", List.of(Map.of("Name", "AE")));
        rule.put("Outcome", Map.of("Message", "m", "Output_Variables", List.of("$p")));
        String json = new ObjectMapper().writeValueAsString(Map.of("rules", Map.of("x", rule)));
        Rule loaded = RulePackageLoader.loadFromString(json).getRules().get("x");
        assertNotNull(loaded);
        assertNull(loaded.getLoadError(), loaded.getLoadError());
        return loaded;
    }
}
