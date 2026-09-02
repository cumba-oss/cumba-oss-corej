package net.cumba.cdisc.core.expr.convert;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.expr.CheckToExpr;
import net.cumba.cdisc.core.expr.ExpressionPrinter;
import net.cumba.cdisc.core.expr.ast.Expr;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.Rule;

/**
 * Offline expression-notation serializer for single {@link Rule}s ({@link #toExpressionJson}), used
 * by the corpus generators and the REST rule-definition overlay. Moved out of
 * {@code RulePackageLoader} by PLAN-engine-rules-decoupling — the engine never renders the
 * expression form at runtime; only the generators, the overlay and their tests do. (The plain
 * {@code toJson} serializer stayed with the loader: engine round-trip tests use it.)
 *
 * <p>
 * The {@code Check} / {@code Precondition} raise here runs {@link CheckToExpr#toExpr} — the same
 * raise {@code RulePackageLoader.tryRaiseToExpr} performs on every loaded rule — on the serialized
 * subtree, whole-tree: a subtree that raises fully is rendered as one {@code {"expression": "..."}}
 * node; one that does not (a leaf with no expression surface anywhere in it) is kept old-style in
 * its entirety, byte-faithful, which is always loadable. Until
 * {@code plans/done/PLAN-retire-corpus-transforms.md} phase 1 this routed through
 * {@code RulePackageConverter}'s conversion machinery, which could additionally fold the raisable
 * <em>parts</em> of a mixed composite; that partial fold retired with the machinery — every shipped
 * rule raises fully, so only the display of a foreign, partially-raisable rule changed (from partly
 * folded to wholly old-style).
 * </p>
 */
public final class RulePackageExpressionJson
{

    private static final System.Logger LOGGER = System
            .getLogger(RulePackageExpressionJson.class.getName());

    /** Mirrors {@code RulePackageLoader}'s mapper so title-case keys round-trip faithfully. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private RulePackageExpressionJson()
    {
    }


    /**
     * Serializes a single {@link Rule} like {@code RulePackageLoader.toJson(Rule)}, but with the
     * {@code Check} and {@code Precondition} subtrees rendered in expression notation via
     * {@link CheckToExpr} and the {@code Operations} array rewritten to function-call form via
     * {@link OperationExpressionPrinter} (the loader normalises a Form-B operation to field form,
     * so the display renders it back; the corpus-side rewriter retired with
     * {@code PLAN-retire-corpus-transforms.md} phase 8). A subtree with no full expression surface
     * stays old-style (byte-faithful), so the output is always loadable; a subtree whose rendering
     * fails unexpectedly is likewise kept as-is rather than failing the whole rule.
     *
     * @param rule
     *            the rule to serialize ({@code null} yields {@code "null"})
     * @return the rule as a JSON string with expression-notation checks
     * @throws java.io.UncheckedIOException
     *             if serialization fails
     */
    public static String toExpressionJson(Rule rule)
    {
        JsonNode tree;
        try
        {
            tree = MAPPER.valueToTree(rule);
        }
        catch (IllegalArgumentException e)
        {
            throw new java.io.UncheckedIOException("Failed to serialize rule to expression JSON",
                    new IOException(e));
        }
        if (tree instanceof ObjectNode obj)
        {
            for (String field : List.of("Check", "Precondition"))
            {
                JsonNode sub = obj.get(field);
                if (sub != null && !sub.isNull())
                {
                    try
                    {
                        obj.set(field, raiseSubtree(sub));
                    }
                    catch (RuntimeException e)
                    {
                        // Defensive: raiseSubtree keeps unraisable shapes old-style itself, but an
                        // unexpected failure must not lose the rule — keep the original (legacy)
                        // subtree for this field.
                        LOGGER.log(System.Logger.Level.WARNING,
                                "Expression rendering of ''{0}'' failed — keeping the legacy"
                                        + " subtree: {1}",
                                field, e.toString());
                    }
                }
            }
            JsonNode ops = obj.get("Operations");
            if (ops != null && !ops.isNull())
            {
                try
                {
                    obj.set("Operations", renderOperations(ops));
                }
                catch (RuntimeException e)
                {
                    // Defensive: renderOperations already keeps the legacy array on failure, but
                    // an
                    // unexpected error must not lose the rule — keep the original Operations array.
                    LOGGER.log(System.Logger.Level.WARNING,
                            "Expression rendering of ''Operations'' failed — keeping the legacy"
                                    + " array: {0}",
                            e.toString());
                }
            }
        }
        try
        {
            return MAPPER.writeValueAsString(tree);
        }
        catch (IOException e)
        {
            throw new java.io.UncheckedIOException("Failed to serialize rule to expression JSON",
                    e);
        }
    }


    /**
     * Renders a serialized {@code Operations} array in function-call form: a field-form entry
     * ({@code operator:} set) becomes {@code {"id": …, "expression": …}} via
     * {@link OperationExpressionPrinter}; an entry already carrying an {@code expression} (or an
     * unrecognisable one) is carried through verbatim.
     */
    private static JsonNode renderOperations(JsonNode ops)
    {
        if (!(ops instanceof com.fasterxml.jackson.databind.node.ArrayNode arr))
        {
            return ops;
        }
        com.fasterxml.jackson.databind.node.ArrayNode out = MAPPER.createArrayNode();
        for (JsonNode opNode : arr)
        {
            net.cumba.cdisc.core.model.Operation op;
            try
            {
                op = MAPPER.treeToValue(opNode, net.cumba.cdisc.core.model.Operation.class);
            }
            catch (com.fasterxml.jackson.core.JsonProcessingException _)
            {
                out.add(opNode);
                continue;
            }
            if (op.getExpression() != null || op.getOperator() == null)
            {
                out.add(opNode);
                continue;
            }
            ObjectNode rewritten = MAPPER.createObjectNode();
            if (op.getId() != null)
            {
                rewritten.put("id", op.getId());
            }
            rewritten.put("expression", OperationExpressionPrinter.print(op));
            out.add(rewritten);
        }
        return out;
    }


    /**
     * Renders one serialized {@code Check} / {@code Precondition} subtree in expression notation:
     * an {@code {"expression": …}} node is kept as-is, a level-keyed map is rendered per level
     * (each level's {@code Message} preserved), and any other shape is raised whole-tree through
     * {@link CheckToExpr#toExpr} — or kept old-style, byte-faithful, when the raise has no full
     * surface for it (including the empty-composite placeholder {@code {"all": []}}).
     */
    private static JsonNode raiseSubtree(JsonNode sub)
    {
        if (!sub.isObject())
        {
            return sub;
        }
        if (sub.hasNonNull("expression"))
        {
            return sub;
        }
        if (CheckLevelNodes.isLevelMap(sub))
        {
            ObjectNode out = MAPPER.createObjectNode();
            for (Map.Entry<String, JsonNode> level : sub.properties())
            {
                JsonNode value = level.getValue();
                JsonNode message = value.isObject() ? value.get(CheckLevelNodes.MESSAGE_KEY) : null;
                JsonNode condition = value;
                if (message != null && value instanceof ObjectNode levelObj)
                {
                    ObjectNode stripped = levelObj.deepCopy();
                    stripped.remove(CheckLevelNodes.MESSAGE_KEY);
                    condition = stripped;
                }
                JsonNode rendered = raiseSubtree(condition);
                if (message != null && rendered instanceof ObjectNode renderedObj)
                {
                    // Copy before re-attaching the Message: raiseSubtree may have returned the
                    // caller's node itself (the kept-old-style arm), which must not be mutated.
                    ObjectNode withMessage = renderedObj.deepCopy();
                    withMessage.set(CheckLevelNodes.MESSAGE_KEY, message);
                    rendered = withMessage;
                }
                out.set(level.getKey(), rendered);
            }
            return out;
        }
        try
        {
            CheckCondition bound = MAPPER.treeToValue(sub, CheckCondition.class);
            if (bound == null)
            {
                return sub;
            }
            Expr raised = CheckToExpr.toExpr(bound);
            String printed = ExpressionPrinter.print(raised);
            if (printed.isBlank())
            {
                // An empty composite ({"all": []} — the unimplemented-rule placeholder) prints
                // to nothing; keep the placeholder shape rather than emitting a blank expression.
                return sub;
            }
            ObjectNode out = MAPPER.createObjectNode();
            out.put("expression", printed);
            return out;
        }
        catch (RuntimeException | IOException _)
        {
            // No full expression surface (or an unbindable shape) — keep old-style, byte-faithful.
            return sub;
        }
    }
}
