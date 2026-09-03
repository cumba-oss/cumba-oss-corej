package net.cumba.corej.core.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

public class CheckConditionDeserializer extends StdDeserializer<CheckCondition>
{

    private static final long serialVersionUID = 1L;

    public CheckConditionDeserializer()
    {
        super(CheckCondition.class);
    }


    @Override
    public CheckCondition deserialize(JsonParser p, DeserializationContext ctxt) throws IOException
    {
        JsonNode node = p.getCodec().readTree(p);
        return deserializeNode(node, ctxt);
    }


    /**
     * The condition binding, reachable without a {@link JsonParser} — the entry point
     * {@link RuleCheckDeserializer} delegates each branch of the {@code Check:} grammar to, so the
     * leaf / {@code all} / {@code any} / {@code not} / {@code expression} cases have exactly one
     * implementation whichever shape the {@code Check:} took.
     *
     * @param node
     *            the condition object
     * @param ctxt
     *            the deserialisation context, for the leaf binding
     * @return the bound condition
     * @throws IOException
     *             if the leaf binding fails
     */
    static CheckCondition fromNode(JsonNode node, DeserializationContext ctxt) throws IOException
    {
        return new CheckConditionDeserializer().deserializeNode(node, ctxt);
    }


    private CheckCondition deserializeNode(JsonNode node, DeserializationContext ctxt)
        throws IOException
    {
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
            return new CheckConditionAll(deserializeList(node.get("all"), ctxt));
        }
        if (node.has("any"))
        {
            return new CheckConditionAny(deserializeList(node.get("any"), ctxt));
        }
        if (node.has("not"))
        {
            return new CheckConditionNot(deserializeNode(node.get("not"), ctxt));
        }
        if (node.has("expression"))
        {
            // Expression-syntax leaf (Java-only extension): parse to the Expr IR. If it lowers to
            // the legacy CheckCondition AST, return that (the engine evaluates it unchanged). If it
            // uses a native-only construct (e.g. var_*/ds_* with an arbitrary-literal name) that
            // has
            // no legacy surface, keep it as a CheckConditionExpression carrying the Expr —
            // evaluated
            // through the native backend. A parse failure still surfaces as ExpressionException.
            String source = node.get("expression").asText();
            net.cumba.corej.core.expr.ast.Expr expr = net.cumba.corej.core.expr.CheckExpressionParser
                    .parse(source);
            try
            {
                return net.cumba.corej.core.expr.ExprLowering.toCheckCondition(expr);
            }
            catch (net.cumba.corej.core.expr.ExpressionException _)
            {
                return new CheckConditionExpression(expr, source);
            }
        }
        // Leaf node
        return ctxt.readTreeAsValue(node, CheckConditionLeaf.class);
    }


    private List<CheckCondition> deserializeList(JsonNode arrayNode, DeserializationContext ctxt)
        throws IOException
    {
        if (!arrayNode.isArray())
        {
            return List.of();
        }
        List<CheckCondition> conditions = new ArrayList<>();
        for (JsonNode element : arrayNode)
        {
            CheckCondition condition = deserializeNode(element, ctxt);
            // A JSON null element (e.g. `"all":[null]`) lowers to a null CheckCondition; drop it
            // rather than poison the list, which keeps every downstream walker null-free.
            if (condition != null)
            {
                conditions.add(condition);
            }
        }
        return conditions;
    }

}
