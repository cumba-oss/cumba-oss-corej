package net.cumba.corej.authoring;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.corej.authoring.RuleDiagnostic.Severity;
import net.cumba.corej.authoring.RuleDiagnostic.YamlPath;
import net.cumba.corej.core.BoundRuleKeys;
import net.cumba.corej.core.model.Rule;
import org.jspecify.annotations.Nullable;

/**
 * Turns the engine's per-rule load channel — {@code Rule.getLoadErrors()} and
 * {@code Rule.getLoadWarnings()}, the entries the loader filed one by one — into
 * {@link RuleDiagnostic}s, one per entry.
 *
 * <p>
 * ⛔ Never split the joined {@code Rule.getLoadError()} string: a single loader message may itself
 * contain {@code "; "} ("an empty entry … matches nothing; remove it …"), and splitting cut it into
 * a second, pathless diagnostic (pack-2 review 8a-A F3).
 * </p>
 *
 * <p>
 * The loader writes its messages as {@code "[<rule id>] <Key>[.<Key>…]: <text>"} wherever it knows
 * the key ({@code RulePackageLoader.appendLoadError}); that leading key chain, when its first
 * element is a bound top-level rule key ({@link BoundRuleKeys#RULE}), becomes the diagnostic's
 * {@link YamlPath}. A message that names no such key keeps {@code path == null} — the gate's own
 * knowledge is the only source, nothing is inferred from the text.
 * </p>
 */
public final class LoadErrorDiagnostics
{

    /** The diagnostic code of a load error segment. */
    public static final String LOAD_ERROR = "LOAD_ERROR";

    /** The diagnostic code of a load warning segment. */
    public static final String LOAD_WARNING = "LOAD_WARNING";

    /**
     * {@code [id] } prefix, then the dotted key chain the message opens with — followed by a colon
     * ({@code Check: …}), a space ({@code Requirements.Variables entry 'X' …}), a quote or the end
     * of the message.
     */
    private static final Pattern SHAPE = Pattern.compile(
            "^(?:\\[[^\\]]*\\]\\s*)?([A-Za-z_][A-Za-z0-9_]*(?:\\.[A-Za-z_][A-Za-z0-9_]*)*)(?=[\\s:'\"(,]|$)");

    private LoadErrorDiagnostics()
    {
    }


    /**
     * The diagnostics of a loaded rule's error and warning channels, errors first.
     *
     * @param aRule
     *            a rule after {@code RulePackageLoader} finished loading it
     * @param aLocations
     *            the authored document's index, for ranges; {@code null} attaches no ranges
     * @return the diagnostics, empty for a clean rule
     */
    public static List<RuleDiagnostic> of(Rule aRule, @Nullable YamlLocations aLocations)
    {
        List<RuleDiagnostic> out = new ArrayList<>();
        append(out, aRule.getLoadErrors(), LOAD_ERROR, Severity.ERROR, aLocations);
        append(out, aRule.getLoadWarnings(), LOAD_WARNING, Severity.WARNING, aLocations);
        return out;
    }


    private static void append(List<RuleDiagnostic> aOut, List<String> aEntries, String aCode,
            Severity aSeverity, @Nullable YamlLocations aLocations)
    {
        for (String entry : aEntries)
        {
            String text = entry.strip();
            if (text.isEmpty())
            {
                continue;
            }
            YamlPath path = pathOf(text);
            aOut.add(new RuleDiagnostic(aCode, aSeverity, text, path,
                    path == null || aLocations == null ? null : aLocations.rangeOf(path)));
        }
    }


    /**
     * The key path a load message names, or {@code null}.
     *
     * @param aMessage
     *            one entry of the load channel
     * @return the path when the message opens with a bound rule key chain
     */
    public static @Nullable YamlPath pathOf(String aMessage)
    {
        Matcher m = SHAPE.matcher(aMessage);
        if (!m.find())
        {
            return null;
        }
        String chain = m.group(1);
        String head = chain.contains(".") ? chain.substring(0, chain.indexOf('.')) : chain;
        return BoundRuleKeys.RULE.contains(head) ? YamlPath.ofDotted(chain) : null;
    }
}
