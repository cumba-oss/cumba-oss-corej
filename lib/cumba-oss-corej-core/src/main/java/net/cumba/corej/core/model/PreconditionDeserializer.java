package net.cumba.corej.core.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import java.io.IOException;
import java.util.List;

/**
 * Binds a rule's {@code Precondition:} key the way {@link RuleCheckDeserializer} binds
 * {@code Check:} — a condition, or a <b>carried</b> grammar error — so a stray key beside a
 * precondition condition is a <b>per-rule</b> load error like every other unknown key (T1-5 a;
 * review E6 / E7 of {@code PLAN-rule-unknown-keys-gate}). Until then the field bound straight
 * through {@link CheckConditionDeserializer}, whose refusal is a whole-package exception: one typo
 * failed every clean sibling, and a <em>parked</em> rule's typo failed the package before
 * {@code removeParkedRules} could drop the rule (Q-1: parked rules are out of the gate).
 *
 * <p>
 * No level map here — a precondition is one condition — so the binding is the plain half of
 * {@code RuleCheckDeserializer.bind}: the stray-key / missing-dispatch walk first, then
 * {@link CheckConditionDeserializer#fromNode}. Its result is a {@link RuleCheck} carrying either
 * the condition or the grammar error; {@code Rule.setPreconditionJson} lands each in its field.
 * </p>
 */
public class PreconditionDeserializer extends StdDeserializer<RuleCheck>
{

    private static final long serialVersionUID = 1L;

    public PreconditionDeserializer()
    {
        super(RuleCheck.class);
    }


    @Override
    public RuleCheck deserialize(JsonParser p, DeserializationContext ctxt) throws IOException
    {
        JsonNode node = p.getCodec().readTree(p);
        if (node == null || node.isNull())
        {
            return RuleCheck.plain(null);
        }
        if (!node.isObject())
        {
            return RuleCheck.invalid("Precondition must be a condition object, got "
                    + node.getNodeType().toString().toLowerCase(java.util.Locale.ROOT));
        }
        List<String> stray = CheckConditionDeserializer.strayKeys(node, "Precondition");
        if (!stray.isEmpty())
        {
            return RuleCheck.invalid(RuleCheckDeserializer.strayKeyMessage(stray));
        }
        return RuleCheck.plain(CheckConditionDeserializer.fromNode(node, ctxt));
    }
}
