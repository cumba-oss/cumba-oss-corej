package net.cumba.cdisc.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.ExpressionPrinter;
import net.cumba.cdisc.core.expr.OperandKind;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckConditionExpression;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Regression coverage for native-expression wildcard expansion: a templated variable name (e.g.
 * {@code TRTxxPN}, {@code *FL}, {@code *GRyN}) inside a <em>native expression</em> Check is
 * expanded to one concrete rule per matching column — wherever the wildcard ref occurs (exists
 * operands, function arguments, group-operator name operands, comparison / membership operands, and
 * {@code group=}/{@code within=} list keywords), plus the exists-family string-literal name operand
 * — while regex literals and value-position strings are left untouched. This restores the behaviour
 * the legacy operator-leaf form has always had. Before the fix the {@link WildcardExpander} skipped
 * {@code CheckConditionExpression}, so the shipped (native) {@code rules/} corpus — which
 * production loads — tested literally-named columns ({@code TRTxxPN}, {@code *FN}, …) that never
 * exist.
 */
class WildcardExpanderNativeExprTest
{

    private static Rule nativeRule(String expression)
    {
        Rule r = new Rule();
        r.setId(UUID.randomUUID().toString());
        RuleCore core = new RuleCore();
        core.setId("TEST-1");
        core.setStatus("Published");
        core.setVersion("1");
        r.setCore(core);
        r.setDescription("native wildcard test");
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
    void bareRefWildcardExpands()
    {
        IDataTable table = MockTable.withColumns("TRT01P", "TRT01PN", "TRT02P", "TRT02PN",
                "STUDYID");
        List<Rule> expanded = WildcardExpander.expand(nativeRule("var_exists(TRTxxPN)"),
                table.getMetaData());

        assertEquals(2, expanded.size());
        List<String> printed = expanded.stream().map(WildcardExpanderNativeExprTest::exprOf)
                .toList();
        assertTrue(printed.contains("var_exists(TRT01PN)"), printed.toString());
        assertTrue(printed.contains("var_exists(TRT02PN)"), printed.toString());

        // The former WILDCARD_COLUMN ref is re-classified to a plain COLUMN ref.
        Expr e0 = ((CheckConditionExpression) expanded.get(0).getCheck()).expr();
        Expr.Ref nameRef = (Expr.Ref) ((Expr.Call) e0).args().get(0);
        assertEquals(OperandKind.COLUMN, nameRef.kind());
    }


    @Test
    void stringLiteralWildcardExpands()
    {
        IDataTable table = MockTable.withColumns("TRT01PN", "TRT02PN", "STUDYID");
        List<Rule> expanded = WildcardExpander.expand(nativeRule("var_exists(\"TRTxxPN\")"),
                table.getMetaData());

        assertEquals(2, expanded.size());
        List<String> printed = expanded.stream().map(WildcardExpanderNativeExprTest::exprOf)
                .toList();
        assertTrue(printed.contains("var_exists(\"TRT01PN\")"), printed.toString());
        assertTrue(printed.contains("var_exists(\"TRT02PN\")"), printed.toString());
    }


    @Test
    void realisticAdamShapeExpands()
    {
        // CDISC-AD0075 native shape: var_exists(TRTxxPN) == true and not var_exists(TRTxxP).
        IDataTable table = MockTable.withColumns("TRT01P", "TRT01PN", "TRT02P", "TRT02PN",
                "STUDYID");
        List<Rule> expanded = WildcardExpander.expand(
                nativeRule("var_exists(TRTxxPN) == true and not var_exists(TRTxxP)"),
                table.getMetaData());

        assertEquals(2, expanded.size());
        String e0 = exprOf(expanded.get(0));
        assertFalse(e0.contains("xx"), "no wildcard token remains: " + e0);
        assertTrue(e0.contains("TRT01PN") && e0.contains("TRT01P"), e0);
    }


    @Test
    void multiWildcardCartesianExpands()
    {
        // TRxxPGyN has two groups (xx = 2-digit, y = integer): expands to the Cartesian of matches.
        IDataTable table = MockTable.withColumns("TR01PG1N", "TR01PG2N", "TR02PG1N", "STUDYID");
        List<Rule> expanded = WildcardExpander.expand(nativeRule("var_exists(TRxxPGyN)"),
                table.getMetaData());

        assertEquals(3, expanded.size());
        List<String> printed = expanded.stream().map(WildcardExpanderNativeExprTest::exprOf)
                .toList();
        assertTrue(printed.contains("var_exists(TR01PG1N)"), printed.toString());
        assertTrue(printed.contains("var_exists(TR01PG2N)"), printed.toString());
        assertTrue(printed.contains("var_exists(TR02PG1N)"), printed.toString());
    }


    @Test
    void tryExpandNativeReturnsExpanded()
    {
        IDataTable table = MockTable.withColumns("TRT01PN", "TRT02PN", "STUDYID");
        WildcardExpander.ExpansionResult res = WildcardExpander
                .tryExpand(nativeRule("var_exists(TRTxxPN)"), table.getMetaData());
        WildcardExpander.ExpansionResult.Expanded ex = assertInstanceOf(
                WildcardExpander.ExpansionResult.Expanded.class, res);
        assertEquals(2, ex.rules().size());
    }


    @Test
    void valuePositionStringNotExpanded()
    {
        // A wildcard-looking string in a NON-name position (the value arg of contains) must NOT be
        // collected/expanded — only exists-family name operands are in the allowlist.
        IDataTable table = MockTable.withColumns("AVAL", "STUDYID");
        WildcardExpander.ExpansionResult res = WildcardExpander
                .tryExpand(nativeRule("contains(AVAL, \"TRTxxPN\")"), table.getMetaData());
        assertInstanceOf(WildcardExpander.ExpansionResult.NotApplicable.class, res);
    }


    @Test
    void orWithMixedWildcardAndConcreteExists()
    {
        // Exercises the Or branch and the "concrete name passes through unchanged" path: the
        // TRTxxPN exists is expanded, while the var_exists(STUDYID) leaf is copied as-is.
        IDataTable table = MockTable.withColumns("TRT01PN", "TRT02PN", "STUDYID");
        List<Rule> expanded = WildcardExpander.expand(
                nativeRule("var_exists(TRTxxPN) or var_exists(STUDYID)"), table.getMetaData());

        assertEquals(2, expanded.size());
        List<String> printed = expanded.stream().map(WildcardExpanderNativeExprTest::exprOf)
                .toList();
        assertTrue(printed.contains("var_exists(TRT01PN) or var_exists(STUDYID)"),
                printed.toString());
        assertTrue(printed.contains("var_exists(TRT02PN) or var_exists(STUDYID)"),
                printed.toString());
    }


    @Test
    void concreteNativeExpressionNotApplicable()
    {
        IDataTable table = MockTable.withColumns("STUDYID", "USUBJID");
        WildcardExpander.ExpansionResult res = WildcardExpander
                .tryExpand(nativeRule("var_exists(STUDYID)"), table.getMetaData());
        assertInstanceOf(WildcardExpander.ExpansionResult.NotApplicable.class, res);
    }


    @Test
    void emptyArgAndMembershipBareRefExpands()
    {
        // CDISC-AD0006 shape: a bare wildcard ref as a function arg AND as a membership LHS, with a
        // list literal RHS. Exercises Ref expansion in non-exists positions + list recursion.
        IDataTable table = MockTable.withColumns("TRTFN", "DCTFN", "STUDYID");
        List<Rule> expanded = WildcardExpander
                .expand(nativeRule("not empty(*FN) and *FN not in [0, 1]"), table.getMetaData());

        assertEquals(2, expanded.size());
        List<String> printed = expanded.stream().map(WildcardExpanderNativeExprTest::exprOf)
                .toList();
        assertTrue(printed.contains("not empty(TRTFN) and TRTFN not in [0, 1]"),
                printed.toString());
        assertTrue(printed.contains("not empty(DCTFN) and DCTFN not in [0, 1]"),
                printed.toString());
    }


    @Test
    void groupOperatorTwoNameRefsExpands()
    {
        // has_multiple_values_for(name, key) — both positional args are bare wildcard refs.
        IDataTable table = MockTable.withColumns("TRT01PN", "TRT01P", "TRT02PN", "TRT02P",
                "STUDYID");
        List<Rule> expanded = WildcardExpander.expand(
                nativeRule("has_multiple_values_for(TRTxxPN, TRTxxP)"), table.getMetaData());

        assertEquals(2, expanded.size());
        List<String> printed = expanded.stream().map(WildcardExpanderNativeExprTest::exprOf)
                .toList();
        assertTrue(printed.contains("has_multiple_values_for(TRT01PN, TRT01P)"),
                printed.toString());
        assertTrue(printed.contains("has_multiple_values_for(TRT02PN, TRT02P)"),
                printed.toString());
    }


    @Test
    void notIsUniqueRelationshipExpands()
    {
        IDataTable table = MockTable.withColumns("TRT01PN", "TRT01P", "TRT02PN", "TRT02P",
                "STUDYID");
        List<Rule> expanded = WildcardExpander.expand(
                nativeRule("not is_unique_relationship(TRTxxPN, TRTxxP)"), table.getMetaData());

        assertEquals(2, expanded.size());
        List<String> printed = expanded.stream().map(WildcardExpanderNativeExprTest::exprOf)
                .toList();
        assertTrue(printed.contains("not is_unique_relationship(TRT01PN, TRT01P)"),
                printed.toString());
    }


    @Test
    void starPrefixWildcardExpands()
    {
        IDataTable table = MockTable.withColumns("SAFFL", "ITTFL", "STUDYID");
        List<Rule> expanded = WildcardExpander.expand(nativeRule("var_exists(*FL)"),
                table.getMetaData());

        assertEquals(2, expanded.size());
        List<String> printed = expanded.stream().map(WildcardExpanderNativeExprTest::exprOf)
                .toList();
        assertTrue(printed.contains("var_exists(SAFFL)"), printed.toString());
        assertTrue(printed.contains("var_exists(ITTFL)"), printed.toString());
    }


    @Test
    void multiGroupStarAndYExpands()
    {
        // *GRyN has two groups (* root and y integer): expands to the Cartesian of matches.
        IDataTable table = MockTable.withColumns("TRTGR1N", "TRTGR2N", "STUDYID");
        List<Rule> expanded = WildcardExpander.expand(nativeRule("var_exists(*GRyN)"),
                table.getMetaData());

        assertEquals(2, expanded.size());
        List<String> printed = expanded.stream().map(WildcardExpanderNativeExprTest::exprOf)
                .toList();
        assertTrue(printed.contains("var_exists(TRTGR1N)"), printed.toString());
        assertTrue(printed.contains("var_exists(TRTGR2N)"), printed.toString());
    }


    @Test
    void wildcardInsideKwargListExpands()
    {
        // A wildcard ref inside a group=/within= keyword list is expanded via the kwarg recursion.
        IDataTable table = MockTable.withColumns("TRT01PN", "TRT02PN", "USUBJID");
        List<Rule> expanded = WildcardExpander
                .expand(nativeRule("record_count(group=[TRTxxPN]) > 0"), table.getMetaData());

        assertEquals(2, expanded.size());
        List<String> printed = expanded.stream().map(WildcardExpanderNativeExprTest::exprOf)
                .toList();
        assertTrue(printed.stream().anyMatch(p -> p.contains("TRT01PN")), printed.toString());
        assertTrue(printed.stream().anyMatch(p -> p.contains("TRT02PN")), printed.toString());
        assertFalse(printed.stream().anyMatch(p -> p.contains("xx")), printed.toString());
    }


    @Test
    void regexLiteralWildcardTokenNotExpanded()
    {
        // CDISC-AD0018 shape: an 'xx'/'zz' token inside a /regex/ literal is NOT a column wildcard.
        IDataTable table = MockTable.withColumns("STUDYID", "USUBJID");
        WildcardExpander.ExpansionResult res = WildcardExpander
                .tryExpand(nativeRule("varname() !~ /(?:xx|zz)/"), table.getMetaData());
        assertInstanceOf(WildcardExpander.ExpansionResult.NotApplicable.class, res);
    }
}
