package net.cumba.corej.core.model;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.regex.Pattern;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * Binds a rule's {@code Check:} key to a {@link RuleCheck}, dispatching on Plan C &#167;3.3's
 * grammar.
 *
 * <p>
 * <b>The discrimination is total and free:</b> every {@link CheckCondition} node name is lower-case
 * ({@code all} / {@code any} / {@code not} / {@code expression}, plus the leaf's {@code operator} /
 * {@code name} / {@code value} / …), and every ladder level name is UPPER-case. So:
 * </p>
 * <ul>
 * <li>all keys are level names &rArr; a <b>level map</b>, re-ordered strictest-first
 * ({@code REJECT} &rarr; {@code ERROR} &rarr; {@code WARNING} &rarr; {@code INFO});</li>
 * <li>no key is a level name &rArr; a plain {@link CheckCondition}, byte-identical to the
 * pre-Plan-C binding — this is all but <b>9</b> of the 3 804 shipped rules (re-measured 2026-08-26;
 * it read "all 3 804" for one day after the nine level maps shipped);</li>
 * <li>a <b>mixed</b> map is a grammar violation, and so is an <b>unknown</b> level name
 * ({@code NOTICE} included: it is a report-only kind that no rule may author).</li>
 * </ul>
 *
 * <p>
 * &#9888; A level's value is either a bare condition ({@code {expression: …}} / {@code {all: …}} /
 * …) or that same object carrying an additional {@code Message}. The {@code Message} key is
 * <b>stripped before the condition is bound</b>, so it can never reach {@link CheckConditionLeaf}
 * as a stray property.
 * </p>
 *
 * <p>
 * A grammar violation is <b>carried</b>, never thrown — see {@link RuleCheck} for why.
 * </p>
 */
public class RuleCheckDeserializer extends StdDeserializer<RuleCheck>
{

    private static final long serialVersionUID = 1L;

    /** The optional per-level message key (ruling 6); title-case like every other rule field. */
    static final String MESSAGE_KEY = "Message";

    /**
     * A key that <em>looks like</em> a level name: all-caps, so it can be nothing else in this
     * grammar. Used to turn a typo ({@code FATAL:}, {@code REJECTED:}) into a stated "unknown level
     * name" rather than a silently-nonsense {@link CheckConditionLeaf}.
     */
    private static final Pattern LEVEL_SHAPED = Pattern.compile("[A-Z][A-Z0-9_]*");

    /** The authorable ladder, in strictest-first order, for the error messages. */
    private static final String AUTHORABLE_LEVELS = "REJECT, ERROR, WARNING, INFO";

    public RuleCheckDeserializer()
    {
        super(RuleCheck.class);
    }


    /**
     * Whether {@code name} is a <b>canonical authorable</b> ladder level name.
     *
     * <p>
     * &#9873; Derived from {@link Severity} rather than from a literal list, so this path cannot
     * drift from the enum: it is the ladder minus the report-only {@code NOTICE}, and the spelling
     * must be the constant's own ({@code ERROR}, never {@code Error}) — a non-canonical casing is a
     * level typo, reported as such by {@link #bind}, not silently a condition key.
     * </p>
     *
     * @param name
     *            the map key
     * @return {@code true} for {@code REJECT} / {@code ERROR} / {@code WARNING} / {@code INFO}
     */
    static boolean isLevelName(String name)
    {
        Severity parsed = Severity.parseOrNull(name);
        return parsed != null && parsed != Severity.NOTICE && name.equals(parsed.name());
    }


    /**
     * The canonical level names among {@code node}'s keys, in encounter order — the shared detector
     * {@link CheckConditionDeserializer} uses to refuse to bind a level map as a condition.
     *
     * @param node
     *            any node; a non-object (or {@code null}) has no level names
     * @return the level names present, possibly empty, never {@code null}
     */
    static List<String> levelNames(@Nullable JsonNode node)
    {
        if (node == null || !node.isObject())
        {
            return List.of();
        }
        List<String> names = new ArrayList<>();
        for (Map.Entry<String, JsonNode> property : node.properties())
        {
            if (isLevelName(property.getKey()))
            {
                names.add(property.getKey());
            }
        }
        return names;
    }


    @Override
    public RuleCheck deserialize(JsonParser p, DeserializationContext ctxt) throws IOException
    {
        JsonNode node = p.getCodec().readTree(p);
        return bind(node, ctxt);
    }


    /**
     * The grammar dispatch, split out so it is testable without a parser.
     *
     * @param node
     *            the {@code Check:} value
     * @param ctxt
     *            the deserialisation context, for the leaf binding
     * @return the bound form, never {@code null}
     * @throws IOException
     *             if the underlying condition binding fails
     */
    static RuleCheck bind(@Nullable JsonNode node, DeserializationContext ctxt) throws IOException
    {
        if (node == null || node.isNull())
        {
            return RuleCheck.plain(null);
        }
        if (!node.isObject())
        {
            return RuleCheck.invalid(
                    "Check must be an object (a condition, or a map of " + "check levels), got "
                            + node.getNodeType().toString().toLowerCase(java.util.Locale.ROOT));
        }
        List<String> levelKeys = new ArrayList<>();
        List<String> unknownLevelKeys = new ArrayList<>();
        List<String> plainKeys = new ArrayList<>();
        for (Map.Entry<String, JsonNode> property : node.properties())
        {
            String name = property.getKey();
            Severity parsed = Severity.parseOrNull(name);
            if (isLevelName(name))
            {
                levelKeys.add(name);
            }
            else if (parsed != null || LEVEL_SHAPED.matcher(name).matches())
            {
                // `Notice`/`NOTICE` (never authorable), a non-canonical spelling (`Error:`), or an
                // all-caps typo. All three are "you meant a level and got it wrong", which must be
                // said out loud — falling through to the plain branch would bind a nonsense leaf.
                unknownLevelKeys.add(name);
            }
            else
            {
                plainKeys.add(name);
            }
        }

        if (!levelKeys.isEmpty() && !plainKeys.isEmpty())
        {
            return RuleCheck.invalid("Check mixes check levels " + levelKeys
                    + " with condition keys " + plainKeys
                    + " — a Check is either a single condition or a map of levels, never both");
        }
        if (!unknownLevelKeys.isEmpty())
        {
            return RuleCheck.invalid("Check declares unknown check level(s) " + unknownLevelKeys
                    + " — expected one of: " + AUTHORABLE_LEVELS);
        }
        if (levelKeys.isEmpty())
        {
            return RuleCheck.plain(CheckConditionDeserializer.fromNode(node, ctxt));
        }
        return bindLevels(node, levelKeys, ctxt);
    }


    private static RuleCheck bindLevels(JsonNode node, List<String> levelKeys,
            DeserializationContext ctxt)
        throws IOException
    {
        Map<Severity, LevelCheck> levels = new LinkedHashMap<>();
        for (String key : levelKeys)
        {
            JsonNode value = node.get(key);
            if (value == null || !value.isObject())
            {
                return RuleCheck.invalid("Check level " + key
                        + " must hold a condition object (optionally carrying a " + MESSAGE_KEY
                        + ")");
            }
            String message = null;
            JsonNode conditionNode = value;
            if (value.has(MESSAGE_KEY))
            {
                JsonNode m = value.get(MESSAGE_KEY);
                if (!m.isTextual())
                {
                    return RuleCheck.invalid(
                            "Check level " + key + ": " + MESSAGE_KEY + " must be a string");
                }
                message = m.asText();
                // Strip it before binding: `Message` is not part of the condition grammar and
                // would otherwise reach CheckConditionLeaf as an unbound property.
                ObjectNode stripped = ((ObjectNode) value).deepCopy();
                stripped.remove(MESSAGE_KEY);
                conditionNode = stripped;
            }
            if (conditionNode.isEmpty())
            {
                // Tested BEFORE binding, because fromNode never returns null for an object node —
                // it would bind `ERROR: {}` (or `ERROR: {Message: "m"}` after the strip above) to
                // an all-null leaf that loads clean and checks nothing.
                return RuleCheck.invalid("Check level " + key + " has no condition"
                        + (message != null
                                ? " — a level must carry a condition, not only a " + MESSAGE_KEY
                                : ""));
            }
            CheckCondition condition = CheckConditionDeserializer.fromNode(conditionNode, ctxt);
            levels.put(Severity.valueOf(key), new LevelCheck(condition, message));
        }
        SequencedMap<Severity, LevelCheck> ordered = LevelCheck.byLadder(levels);
        return RuleCheck.levelled(ordered);
    }

}
