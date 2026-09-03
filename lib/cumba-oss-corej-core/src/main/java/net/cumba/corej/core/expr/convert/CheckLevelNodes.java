package net.cumba.corej.core.expr.convert;

import com.fasterxml.jackson.databind.JsonNode;
import java.util.Iterator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The offline pipeline's copy of Plan C &#167;3.3's <b>two-way grammar dispatch</b> for a
 * {@code Check:} node, mirroring {@code RuleCheckDeserializer.bind} (engine) and
 * {@code RuleFingerprint.levels} (inventory): a node is a <b>level map</b> only when <b>every</b>
 * key is an authorable ladder level name; anything else — a plain condition, a mixed map, an
 * unknown level name — falls to the plain branch. A mixed or unknown-level map is a load error in
 * the engine, so treating it as plain here cannot mis-convert a well-formed rule; it merely leaves
 * a malformed one for the loader to reject.
 *
 * <p>
 * &#9888;&#9888; Every {@code Check}-tree walker in this package must consult this dispatch before
 * its {@code all}/{@code any}/{@code not}/{@code expression} cases: a level map has <em>none</em>
 * of those keys, so a walker that skips the dispatch silently falls through to its leaf branch and
 * rewrites nothing — which is exactly how the shipped-text guards (J3/J4) and the &#167;9.D
 * {@code cross_dataset_variable_metadata} rewrite went missing for level-keyed rules.
 * </p>
 */
public final class CheckLevelNodes
{

    /**
     * The authorable ladder, strictest first. Literals rather than a read of
     * {@code net.cumba.datatable.report.Severity} so that enum's report-only {@code NOTICE}
     * constant, which no rule may author, can never become a level key here (same stance as
     * {@code RuleFingerprint.LADDER}).
     */
    static final List<String> LADDER = List.of("REJECT", "ERROR", "WARNING", "INFO");

    /** The optional per-level message key; mirrors {@code RuleCheckDeserializer.MESSAGE_KEY}. */
    public static final String MESSAGE_KEY = "Message";

    private CheckLevelNodes()
    {
    }


    /**
     * Whether {@code check} is a level-keyed {@code Check:} — a non-empty object whose keys are
     * <b>all</b> authorable level names.
     *
     * @param check
     *            the {@code Check:} node, may be {@code null}
     * @return {@code true} for a level map, {@code false} for everything else
     */
    public static boolean isLevelMap(@Nullable JsonNode check)
    {
        if (check == null || !check.isObject() || check.isEmpty())
        {
            return false;
        }
        for (Iterator<String> names = check.fieldNames(); names.hasNext();)
        {
            if (!LADDER.contains(names.next()))
            {
                return false;
            }
        }
        return true;
    }

}
