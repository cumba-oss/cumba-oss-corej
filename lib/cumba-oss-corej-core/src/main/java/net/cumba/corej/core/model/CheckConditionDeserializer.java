package net.cumba.corej.core.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import org.jspecify.annotations.Nullable;

public class CheckConditionDeserializer extends StdDeserializer<CheckCondition>
{

    private static final long serialVersionUID = 1L;

    public CheckConditionDeserializer()
    {
        super(CheckCondition.class);
    }


    @Override
    public @Nullable CheckCondition deserialize(JsonParser p, DeserializationContext ctxt)
        throws IOException
    {
        JsonNode node = p.getCodec().readTree(p);
        return deserializeNode(node, ctxt);
    }


    /**
     * The condition binding, reachable without a {@link JsonParser} — the entry point
     * {@link RuleCheckDeserializer} delegates each branch of the {@code Check:} grammar to, so the
     * {@code all} / {@code any} / {@code not} / {@code expression} cases have exactly one
     * implementation whichever shape the {@code Check:} took.
     *
     * @param node
     *            the condition object
     * @param ctxt
     *            the deserialisation context, for the mismatch reporting
     * @return the bound condition, or {@code null} for a JSON null (which composites drop)
     * @throws IOException
     *             if the node is not a recognisable condition
     */
    static @Nullable CheckCondition fromNode(JsonNode node, DeserializationContext ctxt)
        throws IOException
    {
        return new CheckConditionDeserializer().deserializeNode(node, ctxt);
    }


    private @Nullable CheckCondition deserializeNode(JsonNode node, DeserializationContext ctxt)
        throws IOException
    {
        // A JSON null (e.g. an `all:[null]` element) binds to no condition; compositeList
        // drops the resulting null rather than poisoning the composite.
        if (node == null || node.isNull())
        {
            return null;
        }
        // ⛔⛔ Plan C §3.3 — a CHECK-LEVEL MAP IS NOT A CONDITION, and must never bind as one.
        // `Check:` dispatches level maps in RuleCheckDeserializer, which hands this method one
        // level's condition at a time. Anything that binds a whole `Check` node straight to
        // CheckCondition — a corpus instrument, a round-trip gate, a Precondition typed by mistake
        // — used to reach the leaf branch below, because a level map carries none of the
        // all/any/not/expression keys. Under a mapper with FAIL_ON_UNKNOWN_PROPERTIES disabled
        // that produced an ALL-NULL CheckConditionLeaf: sixteen null fields that bind clean, check
        // nothing, and compare EQUAL to another all-null leaf. That is exactly how
        // RulePackageConverterCorpusTest's round-trip passed while verifying nothing at all on the
        // 33 level entries of the 9 level-keyed rules. Say it out loud instead — and say it
        // regardless of the mapper's leniency, which is why this is not left to Jackson's
        // unknown-property machinery.
        List<String> levelNames = RuleCheckDeserializer.levelNames(node);
        if (!levelNames.isEmpty())
        {
            return ctxt.reportInputMismatch(CheckCondition.class,
                    "check level(s) %s cannot bind as a condition — a level map is the `Check:`"
                            + " grammar (RuleCheckDeserializer / Rule.effectiveCheckLevels), not a"
                            + " CheckCondition; bind each level's own condition instead",
                    levelNames);
        }
        if (node.has("all"))
        {
            return new CheckConditionAll(compositeList("all", node.get("all"), ctxt));
        }
        if (node.has("any"))
        {
            return new CheckConditionAny(compositeList("any", node.get("any"), ctxt));
        }
        if (node.has("not"))
        {
            // ⛔ D121 — a `not:` with nothing under it is an incorrect rule, and it must die HERE:
            // deserializeNode answers null for a JSON null, and a CheckConditionNot(null) used to
            // load clean only to NPE later in the load pipeline (CheckToExpr), killing the whole
            // package with an exception that named no rule.
            JsonNode inner = node.get("not");
            if (inner == null || inner.isNull())
            {
                return ctxt.reportInputMismatch(CheckCondition.class,
                        "`not:` holds no condition — write the negated condition under it, e.g."
                                + " `not: { expression: ... }`");
            }
            // requireNonNull: deserializeNode answers null for exactly a JSON null, which the
            // guard above has already rejected — the assertion states that rather than leaving the
            // reader to re-derive it (and rather than reintroducing the CheckConditionNot(null)
            // this branch was written to kill).
            return new CheckConditionNot(
                    java.util.Objects.requireNonNull(deserializeNode(inner, ctxt)));
        }
        if (node.has("expression"))
        {
            // ⭐⭐ Phase 7 of PLAN-typed-expression-engine: an expression Check is kept AS the
            // expression it was written as. What stood here was a round-trip — parse to Expr, lower
            // to the v1 CheckConditionLeaf AST, and let RulePackageLoader.installNativeExpr raise
            // it straight back to Expr through CheckToExpr before the native backend compiled it.
            // The v1 tree was never the evaluator; it was a representation the expression was
            // laundered through, and the laundering was lossy.
            //
            // ⭐ Measured before removal, over all 58 packages and every distinct check expression
            // (findings/FINDINGS-p7-lowering-census.md): 2 475 expressions, 2 407 lowered, and of
            // those 2 355 round-tripped BYTE-IDENTICAL, 52 differed, 0 threw. All 52 are one shape
            // — the v1 leaf holds ONE name/value pair, so `date(A) op date(B)` came back as
            // `date(A) op B` with the right operand's type tag dropped. That drop is
            // verdict-identical BY CONSTRUCTION under D102's six-operator differential
            // (`date(A) op B` = `date(A) op date(B)` = `A op date(B)`), which is what made phase
            // 3c's corpus rewrite safe to ship while this lowering was still in the path.
            //
            // A parse failure still surfaces as ExpressionException, exactly as before.
            String source = node.get("expression").asText();
            net.cumba.corej.core.expr.ast.Expr expr = net.cumba.corej.core.expr.CheckExpressionParser
                    .parse(source);
            return new CheckConditionExpression(expr, source);
        }
        // ⭐⭐ D121/D121b (PLAN-typed-expression-engine phase 7d): the v1 operator-leaf model is
        // RETIRED. A node carrying `operator:` is an INCORRECT rule under the expression engine,
        // and an incorrect rule fails loud on load — no fallback representation exists to be
        // faithful to. This rejection is the load-time successor of the OPGAP pin's rule-level
        // NO_NATIVE_CHECK_EXPR contract: served one stage earlier, and louder.
        if (node.has("operator"))
        {
            return ctxt.reportInputMismatch(CheckCondition.class,
                    "the operator-leaf Check form (`operator:`/`name:`/`value:`) is retired —"
                            + " write the condition as an `expression:` (found operator '%s')",
                    node.get("operator").asText());
        }
        // Anything else is not a condition the grammar knows. The pre-phase-7 engine bound it as
        // a CheckConditionLeaf — in the degenerate case an all-null leaf that loads clean and
        // checks nothing. Say it out loud instead.
        List<String> keys = new ArrayList<>();
        node.fieldNames().forEachRemaining(keys::add);
        return ctxt.reportInputMismatch(CheckCondition.class,
                "not a recognised Check condition — expected one of `all:`, `any:`, `not:`,"
                        + " `expression:`; found keys %s",
                keys);
    }


    /**
     * The member list of an {@code all:}/{@code any:} composite — and the grammar's loudness for
     * the malformed spellings (D121: an incorrect rule fails loud on load, no fallback).
     *
     * <p>
     * ⛔ This method is the composite grammar's <b>sole producer</b> ({@code CheckExpressionParser}
     * can never yield an empty {@code And}/{@code Or}), so what it lets through is what the engine
     * runs. It used to answer {@code List.of()} for <em>any</em> non-array node — a mapping, a
     * scalar, a JSON {@code null} — and an empty {@code all} then compiled to the vacuous truth
     * that FIRES ON EVERY ROW ({@code ExprCompiler.compileAnd}), turning a natural YAML slip (a
     * single condition written as a mapping instead of a one-element list) into silent corpus-wide
     * over-reporting with every gate green. An empty {@code any} failed the other way: it silently
     * never fires. Both directions are wrong, so both are load errors now.
     * </p>
     */
    private List<CheckCondition> compositeList(String key, JsonNode arrayNode,
            DeserializationContext ctxt)
        throws IOException
    {
        if (!arrayNode.isArray())
        {
            return ctxt.reportInputMismatch(CheckCondition.class,
                    "`%s:` must be a LIST of conditions — found %s; a single condition still"
                            + " takes the list form, e.g. `%s: [ { expression: ... } ]`",
                    key, arrayNode.getNodeType(), key);
        }
        List<CheckCondition> conditions = new ArrayList<>();
        for (JsonNode element : arrayNode)
        {
            CheckCondition condition = deserializeNode(element, ctxt);
            // A JSON null element (e.g. `"all":[null, {...}]`) lowers to a null CheckCondition;
            // drop it rather than poison the list, which keeps every downstream walker null-free.
            // A list that ends up EMPTY after the drop is caught below.
            if (condition != null)
            {
                conditions.add(condition);
            }
        }
        if (conditions.isEmpty())
        {
            // D121, decided deliberately: an explicitly empty composite is an ERROR, not a legal
            // no-op. An empty `all` is vacuously true — it fires on every row, which is never
            // what an author meant — and an empty `any` can never be satisfied, so the rule is
            // silently inert. Measured before making this fatal (2026-09-17): zero occurrences
            // across all 104 generated packages and 8 578 rulespec / rules-src YAML fixtures.
            return ctxt.reportInputMismatch(CheckCondition.class,
                    "`%s:` holds no conditions — an empty `all` fires on every row (vacuous"
                            + " truth) and an empty `any` can never fire; give `%s:` at least one"
                            + " condition",
                    key, key);
        }
        return conditions;
    }

}
