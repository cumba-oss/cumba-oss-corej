package net.cumba.cdisc.core.model;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.UnaryOperator;
import org.jspecify.annotations.Nullable;

/**
 * The one place that interprets an {@code Outcome.Output_Variables} entry's exclusion marker
 * ({@code PLAN-authoring-grammar-unique-set-and-output-exclusion}, Requirement #2, E-1).
 *
 * <p>
 * Grammar (ruled 2026-08-23): a leading {@code !} marks the entry as an <em>exclusion</em> —
 * everything after it is the variable name, verbatim. Nothing else carries meaning here:
 * </p>
 *
 * <table>
 * <caption>Token table</caption>
 * <tr>
 * <th>token</th>
 * <th>meaning</th>
 * </tr>
 * <tr>
 * <td>{@code X}</td>
 * <td>include {@code X}</td>
 * </tr>
 * <tr>
 * <td>{@code --X}</td>
 * <td>include the domain-wildcard variable {@code --X} — {@code --} never means exclusion</td>
 * </tr>
 * <tr>
 * <td>{@code !X}</td>
 * <td>exclude {@code X} from the effective list</td>
 * </tr>
 * <tr>
 * <td>{@code !--X} / {@code !$id} / {@code !DS.COL}</td>
 * <td>exclude the wildcard variable / the operation result / the dotted foreign variable</td>
 * </tr>
 * <tr>
 * <td>{@code !} alone, {@code !!X}</td>
 * <td>malformed — a load error ({@link #malformed})</td>
 * </tr>
 * </table>
 *
 * <p>
 * An exclusion subtracts from the <em>derived</em> effective list
 * ({@code OutputVariableDeriver#derive}) and never creates an entry; the engine-side semantics
 * ("absent on every projection path") live there and in {@code RulePackageLoader}'s E-3 load
 * validation. This class is deliberately free of any {@code -}/{@code --} disambiguation — that
 * logic belonged to the rejected {@code -VAR} form and must not exist.
 * </p>
 *
 * <p>
 * ⚠ In YAML an unquoted {@code - !X} is a <em>tag</em>, not a string: every {@code rules-src} entry
 * is double-quoted ({@code - "!X"}), and the writers keep it so.
 * </p>
 */
public final class OutputVariableToken
{

    /** The exclusion marker. */
    public static final char MARKER = '!';

    private OutputVariableToken()
    {
    }


    /** {@code true} when {@code entry} carries the exclusion marker (well-formed or not). */
    public static boolean isExclusion(@Nullable String entry)
    {
        return entry != null && !entry.isEmpty() && entry.charAt(0) == MARKER;
    }


    /**
     * The variable name the entry refers to: the entry itself for an include, the text after the
     * marker for an exclusion. A malformed token yields its (possibly empty) remainder — callers
     * that must reject it check {@link #malformed} first.
     */
    public static String name(String entry)
    {
        return isExclusion(entry) ? entry.substring(1) : entry;
    }


    /**
     * {@code entry} with {@code f} applied to the <em>name</em> it carries and the exclusion marker
     * re-attached — {@code mapName("!--STRESC", resolve)} is {@code "!" + resolve("--STRESC")},
     * {@code mapName("--STRESC", resolve)} is {@code resolve("--STRESC")}.
     *
     * <p>
     * Every rewriter of an {@code Output_Variables} entry must go through here (E-1: the token
     * grammar lives in ONE place). A rewriter that tests the raw entry instead — {@code
     * v.startsWith("--") ? prefix + v.substring(2) : v} was the shipped shape — sees {@code !} in
     * position 0, leaves the wildcard unresolved, and hands the loader an entry that names nothing
     * the (resolved) rule derives: E-3.1 then tags a {@code loadError} and the rule reports
     * {@code ENGINE_ERROR} on every targeted dataset ({@code Fix #356}).
     * </p>
     *
     * <p>
     * A malformed token stays malformed: {@code "!"} maps its empty remainder and {@code "!!X"}
     * maps {@code "!X"}, so {@link #malformed} still rejects both afterwards.
     * </p>
     */
    public static String mapName(String entry, UnaryOperator<String> f)
    {
        return isExclusion(entry) ? MARKER + f.apply(entry.substring(1)) : f.apply(entry);
    }


    /**
     * Why {@code entry} is not a well-formed token, or {@code null} when it is. Only exclusions can
     * be malformed: a bare {@code !} names nothing, and {@code !!X} stacks the marker (and is also
     * a YAML type tag). A plain include is never judged here — its content is the engine's
     * business.
     */
    public static @Nullable String malformed(@Nullable String entry)
    {
        if (entry == null || !isExclusion(entry))
        {
            return null;
        }
        String rest = entry.substring(1);
        if (rest.isEmpty())
        {
            return "a bare '!' excludes nothing — write !VAR";
        }
        if (rest.charAt(0) == MARKER)
        {
            return "'" + entry + "' stacks the exclusion marker — write !VAR once";
        }
        return null;
    }


    /**
     * The include entries of {@code entries}, verbatim and in order (every exclusion token
     * removed); empty for {@code null}.
     */
    public static List<String> includes(@Nullable List<String> entries)
    {
        if (entries == null || entries.isEmpty())
        {
            return List.of();
        }
        List<String> out = new ArrayList<>(entries.size());
        for (String entry : entries)
        {
            if (!isExclusion(entry))
            {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }


    /**
     * The names {@code entries} excludes — {@code X} for every {@code !X} — deduplicated, in first
     * appearance order; empty for {@code null}. A malformed token contributes its remainder (so a
     * bare {@code !} contributes nothing); rejecting it is the loader's job.
     */
    public static Set<String> exclusions(@Nullable List<String> entries)
    {
        if (entries == null || entries.isEmpty())
        {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String entry : entries)
        {
            if (isExclusion(entry) && entry.length() > 1)
            {
                out.add(entry.substring(1));
            }
        }
        return out.isEmpty() ? Set.of() : Collections.unmodifiableSet(out);
    }


    /**
     * {@code entries} with every exclusion token removed <em>and</em> every name an exclusion token
     * names removed: the post-exclusion view of an authored list. Used where no derivation runs
     * (the {@code -Dcorej.autoOutputVariables=false} fallback, the equivalence fingerprints).
     */
    public static List<String> applyExclusions(@Nullable List<String> entries)
    {
        if (entries == null || entries.isEmpty())
        {
            return List.of();
        }
        Set<String> excluded = exclusions(entries);
        List<String> out = new ArrayList<>(entries.size());
        for (String entry : entries)
        {
            if (!isExclusion(entry) && !excluded.contains(entry))
            {
                out.add(entry);
            }
        }
        return List.copyOf(out);
    }
}
