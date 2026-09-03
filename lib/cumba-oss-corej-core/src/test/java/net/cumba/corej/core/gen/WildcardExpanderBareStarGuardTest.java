package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.expr.OperandClassifier;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Fix #84 — the <b>bare-{@code *} explosion guard</b> of {@link WildcardExpander#expand}, on both
 * Check surfaces (operator leaves and native expressions).
 *
 * <p>
 * A bare {@code "*"} in a NAME position parses to {@code ^(.+)$} and matches <em>every</em> column.
 * It is admissible only when a sibling {@code "*N"} / {@code "*C"} leaf anchors the pairing, and
 * then the tuples are seeded from the ANCHORED side. Without the guard a single template seeds one
 * expansion per column of the dataset — on a wide ADSL that is hundreds of concrete rules, each
 * checking a column the author never named. The failure is silent: the rules load, run, and report.
 * </p>
 *
 * <p>
 * ⚑ Every assertion here is an exact count or an exact expression, never "something came back": the
 * guard's whole content is <em>how many</em> rules an unanchored bare {@code *} produces, and the
 * answer must be zero.
 * </p>
 */
class WildcardExpanderBareStarGuardTest
{

    private static CheckConditionLeaf leaf(String name, String operator)
    {
        return CheckConditionLeaf.builder().name(name).operator(operator).build();
    }


    private static Rule template(String coreId, CheckCondition check)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        rule.setCheck(check);
        rule.setDescription("bare-star fixture");
        return rule;
    }


    private static Expr.Ref ref(String name)
    {
        return new Expr.Ref(name, OperandClassifier.classify(name, -1));
    }


    private static Expr.Call call(String name, Expr... args)
    {
        return new Expr.Call(name, List.of(args), Map.of());
    }


    private static CheckCondition nativeCheck(Expr expr)
    {
        return new CheckConditionExpression(expr, ExpressionPrinter.print(expr));
    }


    private static String rendered(Rule rule)
    {
        return ExpressionPrinter.print(CheckToExpr.toExpr(rule.getCheck()));
    }


    /** Two columns: one the {@code *FL} template matches, one it does not. */
    private static DataTableMeta flagTable()
    {
        return MockTable.of().name("ADAE").col("AEFL", "Y").col("AGE", "50").build().getMetaData();
    }

    // ------------------------------------------------------------------
    // Operator-leaf Check surface
    // ------------------------------------------------------------------


    /**
     * Pins the guard itself: a name-position bare {@code "*"} with NO {@code *N} / {@code *C}
     * sibling refuses to expand and produces exactly ZERO rules. Without the refusal the bare
     * {@code *} seeds one candidate tuple per column, so the template silently becomes one concrete
     * rule per column of whatever dataset it meets.
     */
    @Test
    @DisplayName("bare '*' with no *N/*C anchor expands to nothing at all")
    void unanchoredBareStarRefusesToExpand()
    {
        List<Rule> expanded = WildcardExpander.expand(
                template("WC-BARE-1",
                        new CheckConditionAll(
                                List.of(leaf("*", "non_empty"), leaf("*FL", "non_empty")))),
                flagTable());

        assertEquals(List.of(), expanded,
                "an unanchored bare '*' must seed NOTHING — one tuple per column is the "
                        + "explosion Fix #84 exists to prevent");
    }


    /**
     * The negative case for the same branch: once a {@code *N} sibling anchors the pairing the
     * template DOES expand — and it expands from the anchored side only, so a column the anchor
     * does not reach ({@code PARAM}) contributes no tuple. Exactly one rule, and the bare {@code *}
     * resolves to the anchor's root ({@code AVAL}), not to "every column".
     */
    @Test
    @DisplayName("bare '*' anchored by a sibling '*N' expands once, from the anchored side")
    void anchoredBareStarExpandsFromTheAnchor()
    {
        DataTableMeta meta = MockTable.of().name("ADAE").col("AVAL", "1").col("AVALN", "1")
                .col("PARAM", "x").build().getMetaData();

        List<Rule> expanded = WildcardExpander.expand(template("WC-BARE-2",
                new CheckConditionAll(List.of(leaf("*", "non_empty"), leaf("*N", "non_empty")))),
                meta);

        assertEquals(1, expanded.size(),
                "seeding is anchored: PARAM matches the bare '*' but not '*N', so it is not a "
                        + "tuple — got " + expanded.stream().map(Rule::effectiveId).toList());
        assertEquals("WC-BARE-2-AVAL", expanded.get(0).effectiveId());
        assertEquals("not empty(AVAL) and not empty(AVALN)", rendered(expanded.get(0)),
                "the bare '*' binds the ANCHOR's root, so the pair resolves together");
    }

    // ------------------------------------------------------------------
    // Native-expression Check surface
    // ------------------------------------------------------------------


    /**
     * The native analogue of the guard: an exists-family call whose first argument is the STRING
     * LITERAL {@code "*"} is a name position, so it engages the guard exactly as a leaf named
     * {@code "*"} does. Unanchored ⇒ zero rules.
     */
    @Test
    @DisplayName("native var_exists(\"*\") is a NAME position and engages the guard")
    void existsFamilyStringLiteralStarIsANamePosition()
    {
        Expr expr = new Expr.And(List.of(call("var_exists", new Expr.Lit(Expr.LitKind.STRING, "*")),
                call("var_exists", ref("*FL"))));

        List<Rule> expanded = WildcardExpander.expand(template("WC-BARE-3", nativeCheck(expr)),
                flagTable());

        assertEquals(List.of(), expanded,
                "var_exists(\"*\") names a column; unanchored it must refuse, or the template "
                        + "expands once per column (AEFL and AGE here)");
    }


    /**
     * The precision half, and the reason the name-position test is an allowlist rather than "any
     * {@code \"*\"} literal anywhere": a {@code "*"} sitting in a VALUE position of a
     * non-exists-family call is data, not a column name. It must NOT engage the guard — the shipped
     * {@code library_variable_label does_not_contain "*"} leaves of CDISC-AD0018 / 0708 / 0709 and
     * PMDA-AD0018 are exactly this shape, and treating them as bare-{@code *} targets would refuse
     * to expand four rules that expanded correctly before Fix #84.
     */
    @Test
    @DisplayName("a value-position \"*\" literal does NOT engage the guard")
    void valuePositionStarLiteralStillExpands()
    {
        Expr expr = new Expr.And(
                List.of(call("contains", new Expr.Lit(Expr.LitKind.STRING, "*"), ref("*FL")),
                        call("var_exists", ref("*FL"))));

        List<Rule> expanded = WildcardExpander.expand(template("WC-BARE-4", nativeCheck(expr)),
                flagTable());

        assertEquals(1, expanded.size(),
                "the \"*\" is an operand VALUE — the rule is a plain '*FL' template and must "
                        + "expand; got " + expanded.stream().map(Rule::effectiveId).toList());
        assertEquals("WC-BARE-4-AEFL", expanded.get(0).effectiveId());
        String text = rendered(expanded.get(0));
        assertTrue(text.contains("AEFL"), text);
        assertFalse(text.contains("*FL"),
                "the wildcard ref is resolved even though a '*' literal sits beside it: " + text);
    }

}
