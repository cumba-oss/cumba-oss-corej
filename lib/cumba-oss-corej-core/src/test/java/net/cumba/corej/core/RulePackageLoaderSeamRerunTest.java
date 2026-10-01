package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.LinkedHashMap;
import java.util.List;
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
}
