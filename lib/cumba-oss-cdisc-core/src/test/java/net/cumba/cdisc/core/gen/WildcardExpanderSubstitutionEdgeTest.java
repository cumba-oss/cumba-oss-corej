package net.cumba.cdisc.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.expr.ExpressionPrinter;
import net.cumba.cdisc.core.expr.OperandClassifier;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionExpression;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * The <b>substitution</b> half of {@link WildcardExpander}: what a template token is allowed to
 * rewrite, and what it must leave alone.
 *
 * <p>
 * Every case here is a silent-harm shape. A value literal rewritten as if it were a column name
 * changes what a correct rule compares against; a name left un-rewritten points the rule at a
 * column that cannot exist, so the Check yields nothing and the rule reports clean; a tuple
 * assembled from the wrong groups pairs {@code xx=01} with {@code y=2}. None of these is visible in
 * a rule review — the authored rule is correct in each case.
 * </p>
 */
class WildcardExpanderSubstitutionEdgeTest
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
        return rule;
    }


    private static Rule expandOnce(Rule template, DataTableMeta meta)
    {
        List<Rule> expanded = WildcardExpander.expand(template, meta);
        assertEquals(1, expanded.size(), "expected exactly one expansion, got "
                + expanded.stream().map(Rule::effectiveId).toList());
        return expanded.get(0);
    }


    private static String rendered(Rule rule)
    {
        return ExpressionPrinter.print(CheckToExpr.toExpr(rule.getCheck()));
    }


    private static List<CheckCondition> partsOf(Rule rule)
    {
        CheckCondition check = rule.getCheck();
        assertNotNull(check);
        return ((CheckConditionAll) check).getConditions();
    }


    /**
     * A leaf {@code value} flagged {@code value_is_literal} is <b>data</b>, not a column name, and
     * must survive the expansion untouched. If the substitution nulls or rewrites it, the concrete
     * rule compares its column against nothing — a rule that fires on every row or on none, with
     * the authored rule still reading correctly.
     */
    @Test
    @DisplayName("a literal leaf value survives the expansion untouched")
    void literalLeafValueIsCarriedThroughUnchanged()
    {
        DataTableMeta meta = MockTable.of().name("ADAE").col("TRT01P", "PLACEBO").build()
                .getMetaData();
        CheckConditionLeaf comparison = CheckConditionLeaf.builder().name("TRTxxP")
                .operator("equal_to").value(new TextNode("PLACEBO")).valueIsLiteral(Boolean.TRUE)
                .build();

        Rule expanded = expandOnce(template("WC-SUB-1", new CheckConditionAll(List.of(comparison))),
                meta);

        CheckConditionLeaf got = (CheckConditionLeaf) partsOf(expanded).get(0);
        assertEquals("TRT01P", got.getName(), "the NAME is rewritten…");
        assertNotNull(got.getValue(),
                "…but the literal VALUE must survive — a dropped comparand silently changes what "
                        + "the rule tests");
        assertEquals("PLACEBO", got.getValue().asText());
        assertEquals(true, got.getValueIsLiteral(),
                "the literal flag itself must survive, or the value is re-read as a column name");
    }


    /**
     * A leaf whose name and value are both untouched is returned <b>as the same object</b> rather
     * than rebuilt. This is not cosmetic: the rebuild is lossy by construction — it names each
     * field explicitly and {@code include_empty} is not among them (a documented pre-existing gap)
     * — so rebuilding a leaf that needed no rewriting silently deletes fields from rules the
     * expansion was never supposed to touch.
     */
    @Test
    @DisplayName("a leaf needing no rewrite is shared, not rebuilt (the rebuild is lossy)")
    void anUntouchedLeafIsSharedNotRebuilt()
    {
        DataTableMeta meta = MockTable.of().name("ADAE").col("TRT01P", "A").col("USUBJID", "U")
                .build().getMetaData();
        CheckConditionLeaf untouched = leaf("USUBJID", "non_empty");

        Rule expanded = expandOnce(
                template("WC-SUB-2",
                        new CheckConditionAll(List.of(leaf("TRTxxP", "non_empty"), untouched))),
                meta);

        List<CheckCondition> parts = partsOf(expanded);
        assertEquals("TRT01P", ((CheckConditionLeaf) parts.get(0)).getName(),
                "the wildcard leaf IS rewritten");
        assertSame(untouched, parts.get(1),
                "a leaf with nothing to rewrite must come back as the same object — rebuilding it "
                        + "runs it through a field-by-field copy that drops include_empty");
    }


    /**
     * A wildcard inside an <b>array</b> leaf value (a membership list of columns) is substituted
     * element by element, and the non-wildcard elements are left alone. Without this the concrete
     * rule tests membership against a list containing the literal string {@code TRTxxP}, which
     * matches nothing.
     */
    @Test
    @DisplayName("wildcards inside an array leaf value are substituted element-wise")
    void wildcardsInsideAnArrayValueAreSubstituted()
    {
        DataTableMeta meta = MockTable.of().name("ADAE").col("TRT01P", "A").col("USUBJID", "U")
                .build().getMetaData();
        ArrayNode value = JsonNodeFactory.instance.arrayNode();
        value.add("TRTxxP");
        value.add("USUBJID");
        CheckConditionLeaf membership = CheckConditionLeaf.builder().name("TRTxxP")
                .operator("is_contained_by").value(value).build();

        Rule expanded = expandOnce(template("WC-SUB-3", new CheckConditionAll(List.of(membership))),
                meta);

        CheckConditionLeaf got = (CheckConditionLeaf) partsOf(expanded).get(0);
        assertNotNull(got.getValue());
        assertTrue(got.getValue().isArray(), "the array shape must survive: " + got.getValue());
        assertEquals(2, got.getValue().size());
        assertEquals("TRT01P", got.getValue().get(0).asText(),
                "the wildcard element is bound to this tuple's column");
        assertEquals("USUBJID", got.getValue().get(1).asText(),
                "a non-wildcard element is carried through untouched");
    }


    /**
     * The {@code not_exists} shape: a template names a column the dataset does <b>not</b> carry, so
     * no matched instance exists and the concrete name has to be <em>reconstructed</em> from the
     * tuple. Reconstructing it wrong (leaving the marker text in place) makes the absence check
     * test a column called literally {@code TRTxxPN} — which is absent in every dataset, so the
     * rule passes everywhere.
     */
    @Test
    @DisplayName("a template column absent from the dataset is reconstructed from the tuple")
    void anAbsentTemplateColumnIsRebuiltFromTheTuple()
    {
        // TRT01PN is deliberately NOT a column: the second leaf must still resolve to it.
        DataTableMeta meta = MockTable.of().name("ADAE").col("TRT01P", "A").build().getMetaData();

        Rule expanded = expandOnce(
                template("WC-SUB-4",
                        new CheckConditionAll(
                                List.of(leaf("TRTxxP", "non_empty"), leaf("TRTxxPN", "empty")))),
                meta);

        assertEquals("WC-SUB-4-TRT01P", expanded.effectiveId());
        List<CheckCondition> parts = partsOf(expanded);
        assertEquals("TRT01P", ((CheckConditionLeaf) parts.get(0)).getName());
        assertEquals("TRT01PN", ((CheckConditionLeaf) parts.get(1)).getName(),
                "the marker must be replaced by the tuple's captured value even though no column "
                        + "matched — leaving 'TRTxxPN' makes the check vacuous");
    }


    /**
     * Disjoint capture groups: no single pattern covers the union {@code {xx, y}}, so the tuples
     * are assembled by cross-joining the partial patterns. Skipping that fallback drops the rule
     * silently — it expands to nothing and reports clean.
     */
    @Test
    @DisplayName("disjoint-group templates are assembled by the cross-join fallback")
    void disjointGroupTemplatesAreCrossJoined()
    {
        DataTableMeta meta = MockTable.of().name("LB").col("A01LB", "1").col("B2LB", "2").build()
                .getMetaData();

        Rule expanded = expandOnce(
                template("WC-SUB-5",
                        new CheckConditionAll(
                                List.of(leaf("AxxLB", "non_empty"), leaf("ByLB", "non_empty")))),
                meta);

        assertEquals("WC-SUB-5-A01LB", expanded.effectiveId());
        assertEquals("not empty(A01LB) and not empty(B2LB)", rendered(expanded),
                "both halves of the disjoint tuple must resolve; without the cross-join the "
                        + "template yields no rule at all");
    }


    /**
     * The native-expression walk must visit <b>both</b> sides of a comparison. A wildcard appearing
     * only on the right-hand side is still a column-name template; miss it and the rule is not even
     * recognised as a template, so it ships comparing against a column named {@code *FL}.
     */
    @Test
    @DisplayName("a wildcard on the RIGHT of a native comparison is still expanded")
    void nativeComparisonRightHandWildcardIsExpanded()
    {
        DataTableMeta meta = MockTable.of().name("ADAE").col("AEFL", "Y").col("STUDYID", "S")
                .build().getMetaData();
        Expr expr = new Expr.Binary(Expr.BinOp.EQ,
                new Expr.Ref("STUDYID", OperandClassifier.classify("STUDYID", -1)),
                new Expr.Ref("*FL", OperandClassifier.classify("*FL", -1)));

        Rule expanded = expandOnce(template("WC-SUB-6",
                new CheckConditionExpression(expr, ExpressionPrinter.print(expr))), meta);

        assertEquals("WC-SUB-6-AEFL", expanded.effectiveId());
        assertEquals("STUDYID == AEFL", rendered(expanded),
                "the right operand is a template too — an unwalked right side leaves '*FL' as a "
                        + "literal column name");
    }


    /**
     * An operation's row-filter <b>keys</b> are column positions and are rewritten; an operation
     * that declares no filter must keep {@code null} rather than gaining an empty filter, because
     * "no filter" and "a filter that excludes nothing" are different inputs to the executor.
     */
    @Test
    @DisplayName("operation filter keys are rewritten; an absent filter stays absent")
    void operationFilterKeysAreRewrittenAndAbsenceIsPreserved()
    {
        DataTableMeta meta = MockTable.of().name("ADAE").col("TRT01P", "A").build().getMetaData();

        Operation filtered = new Operation();
        filtered.setId("$peak");
        filtered.setOperator("max");
        filtered.setName("TRTxxP");
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("TRTxxP", "PLACEBO");
        filtered.setFilter(filter);

        Operation unfiltered = new Operation();
        unfiltered.setId("$all");
        unfiltered.setOperator("max");
        unfiltered.setName("TRTxxP");

        Rule template = template("WC-SUB-7",
                new CheckConditionAll(List.of(leaf("TRTxxP", "non_empty"))));
        template.setOperations(List.of(filtered, unfiltered));

        Rule expanded = expandOnce(template, meta);

        List<Operation> ops = expanded.getOperations();
        assertNotNull(ops);
        assertEquals(2, ops.size());
        assertEquals(Map.of("TRT01P", "PLACEBO"), ops.get(0).getFilter(),
                "the filter KEY is a column position and is bound to the tuple; the VALUE is data "
                        + "and is not");
        assertNull(ops.get(1).getFilter(),
                "an operation with no filter must not acquire an empty one");

        // …and a template that declares no Operations at all must not acquire an empty list:
        // "no operations" and "an empty operations block" are different inputs to the executor
        // and round-trip differently through the writer.
        Rule noOps = expandOnce(
                template("WC-SUB-8", new CheckConditionAll(List.of(leaf("TRTxxP", "non_empty")))),
                meta);
        assertNull(noOps.getOperations(),
                "an expansion of a template with no Operations must keep the field absent");
    }

}
