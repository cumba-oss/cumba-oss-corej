package net.cumba.corej.core.gen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import net.cumba.corej.core.model.Binding;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CheckConditionNot;
import net.cumba.corej.core.model.LevelCheck;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * The rule surfaces a declared expansion token is read from — in one place, so the loader gates G2
 * / G3 / R6 ({@code RulePackageLoader}) and a reviewer can see exactly what is gated
 * ({@code PLAN-expansion-token-delimiters} S5).
 *
 * <p>
 * The list is exactly what {@link TokenExpander#buildExpansion} substitutes: the authored source of
 * every Check level and of the Precondition, each level's own {@code Message}, the authored
 * {@code Bindings} expressions, Description, {@code Outcome.Message}, {@code Output_Variables},
 * every text node of each {@code Match_Datasets} entry's JSON tree (walked as
 * {@code substituteTree} walks it: Name, Keys on both sides, Filter, Join_As_String, Join_Type),
 * {@code Grouping.Variables} and {@code Grouping_Variables} — plus the surfaces that are gated but
 * copied verbatim ({@link #gatedOnly}): {@code Requirements.Variables} and the {@code Scope} name
 * lists, which are matched BEFORE expansion and where gate R6 looks. Prose that is never
 * substituted (ExecutabilityHint, Source, Standards, Authorities, Core) is not a surface, so prose
 * like {@code R&D} there is never gated. {@code ExpansionSurfacesTest} keeps this in lockstep with
 * the expander: a distinct declared token in every String leaf of a maximal rule's Jackson tree
 * must be substituted exactly where this class looks, and every leaf it does not look at is listed
 * there with the reason it is not a surface.
 * </p>
 *
 * <p>
 * No whole-rule JSON serialisation: a corpus load runs this on ≈3.8k rules, so only the per-entry
 * {@code Match_Datasets} tree is built (cheap), the rest are plain accessors.
 * </p>
 */
public final class ExpansionSurfaces
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ExpansionSurfaces()
    {
    }

    /**
     * One gated text and where it came from, for the message.
     *
     * @param where
     *            the surface, e.g. {@code Check}, {@code Check.Message} (single level),
     *            {@code Check[ERROR].Message} (multi-level), {@code Outcome.Output_Variables},
     *            {@code Match_Datasets[0]}, {@code Scope.Domains.Include}
     * @param text
     *            the authored text
     */
    public record Surface(String where, String text)
    {
    }


    /**
     * One Check-tree condition and the surface name it is reported under — a level ({@code Check},
     * or {@code Check[ERROR]} on a multi-level rule) or the {@code Precondition}.
     *
     * @param where
     *            the surface name
     * @param condition
     *            the parsed condition
     */
    public record NamedCondition(String where, CheckCondition condition)
    {
    }

    /**
     * The gated texts of {@code rule}, in surface order.
     *
     * @param rule
     *            the rule
     * @return the texts; empty for an empty rule
     */
    public static List<String> texts(Rule rule)
    {
        return surfaces(rule).stream().map(Surface::text).toList();
    }


    /**
     * The gated surfaces of {@code rule}, in surface order.
     *
     * @param rule
     *            the rule
     * @return the surfaces; empty for an empty rule
     * @throws IllegalArgumentException
     *             when a {@code Match_Datasets} entry cannot be rendered as a JSON tree (the caller
     *             fails the load closed rather than skipping the surface)
     */
    public static List<Surface> surfaces(Rule rule)
    {
        List<Surface> out = new ArrayList<>();
        for (NamedCondition named : namedConditions(rule))
        {
            collectCheck(named.condition(), named.where(), out);
        }
        SequencedMap<Severity, LevelCheck> levels = rule.getCheckLevels();
        if (levels != null)
        {
            for (Map.Entry<Severity, LevelCheck> e : levels.entrySet())
            {
                // Substituted by the expander (LevelCheck.map), so gated like Outcome.Message, and
                // named after its level's condition surface: Check.Message beside Check,
                // Check[ERROR].Message beside Check[ERROR].
                add(out, levelSurface(levels, e.getKey()) + ".Message", e.getValue().message());
            }
        }
        List<Binding> bindings = rule.getBindings();
        if (bindings != null)
        {
            for (int i = 0; i < bindings.size(); i++)
            {
                Binding b = bindings.get(i);
                if (b != null)
                {
                    add(out, "Bindings[" + i + "]", b.getExpression());
                }
            }
        }
        add(out, "Description", rule.getDescription());
        if (rule.getOutcome() != null)
        {
            add(out, "Outcome.Message", rule.getOutcome().getMessage());
            addAll(out, "Outcome.Output_Variables", rule.getOutcome().getOutputVariables());
        }
        List<MatchDataset> matchDatasets = rule.getMatchDatasets();
        if (matchDatasets != null)
        {
            for (int i = 0; i < matchDatasets.size(); i++)
            {
                MatchDataset md = matchDatasets.get(i);
                if (md != null)
                {
                    collectTree(MAPPER.valueToTree(md), "Match_Datasets[" + i + "]", out);
                }
            }
        }
        if (rule.getGrouping() != null)
        {
            addAll(out, "Grouping.Variables", rule.getGrouping().getVariables());
        }
        addAll(out, "Grouping_Variables", rule.getGroupingVariables());
        out.addAll(gatedOnly(rule));
        return List.copyOf(out);
    }


    /**
     * The Check levels and the Precondition of {@code rule}, each with the surface name the gates
     * report it under: {@code Check} for a single-level rule, {@code Check[<level>]} per level of a
     * multi-level one, {@code Precondition}.
     *
     * @param rule
     *            the rule
     * @return the named conditions, levels first
     */
    public static List<NamedCondition> namedConditions(Rule rule)
    {
        List<NamedCondition> out = new ArrayList<>();
        SequencedMap<Severity, LevelCheck> levels = rule.getCheckLevels();
        if (levels == null)
        {
            if (rule.getCheck() != null)
            {
                out.add(new NamedCondition("Check", rule.getCheck()));
            }
        }
        else
        {
            for (Map.Entry<Severity, LevelCheck> e : levels.entrySet())
            {
                out.add(new NamedCondition(levelSurface(levels, e.getKey()),
                        e.getValue().condition()));
            }
        }
        if (rule.getPrecondition() != null)
        {
            out.add(new NamedCondition("Precondition", rule.getPrecondition()));
        }
        return List.copyOf(out);
    }


    /**
     * The surface name of one level's condition: {@code Check} when the level map has one level
     * (the rule reads as a plain Check), {@code Check[<level>]} otherwise. A level's
     * {@code Message} is reported as this name plus {@code .Message}, so the two always agree.
     */
    private static String levelSurface(Map<Severity, LevelCheck> levels, Severity level)
    {
        return levels.size() == 1 ? "Check" : "Check[" + level + "]";
    }


    /**
     * The surfaces that are gated but never substituted, because they are matched <em>before</em>
     * the rule expands: the four {@code Requirements.Variables} facets and the {@code Scope} name
     * lists ({@code Domains} / {@code Datasets} / {@code Classes} / {@code Data_Structures} /
     * {@code Subclasses}, {@code Include} and {@code Exclude}). A declared token in any of them is
     * gate R6's error; an undeclared one is G2's.
     *
     * @param rule
     *            the rule
     * @return the surfaces, requirements first
     */
    public static List<Surface> gatedOnly(Rule rule)
    {
        List<Surface> out = new ArrayList<>();
        Requirements req = rule.getRequirements();
        VariableRequirement vars = req == null ? null : req.getVariables();
        if (vars != null)
        {
            addAll(out, "Requirements.Variables.All", vars.getAll());
            addAll(out, "Requirements.Variables.Any", vars.anyUnion());
            addAll(out, "Requirements.Variables.None", vars.getNone());
            addAll(out, "Requirements.Variables.All_Or_None", vars.allOrNoneUnion());
        }
        Scope scope = rule.getScope();
        if (scope != null)
        {
            if (scope.getDomains() != null)
            {
                addAll(out, "Scope.Domains.Include", scope.getDomains().getInclude());
                addAll(out, "Scope.Domains.Exclude", scope.getDomains().getExclude());
            }
            if (scope.getDatasets() != null)
            {
                addAll(out, "Scope.Datasets.Include", scope.getDatasets().getInclude());
                addAll(out, "Scope.Datasets.Exclude", scope.getDatasets().getExclude());
            }
            if (scope.getClasses() != null)
            {
                addAll(out, "Scope.Classes.Include", scope.getClasses().getInclude());
                addAll(out, "Scope.Classes.Exclude", scope.getClasses().getExclude());
            }
            if (scope.getDataStructures() != null)
            {
                addAll(out, "Scope.Data_Structures.Include",
                        scope.getDataStructures().getInclude());
                addAll(out, "Scope.Data_Structures.Exclude",
                        scope.getDataStructures().getExclude());
            }
            if (scope.getSubclasses() != null)
            {
                addAll(out, "Scope.Subclasses.Include", scope.getSubclasses().getInclude());
                addAll(out, "Scope.Subclasses.Exclude", scope.getSubclasses().getExclude());
            }
        }
        return List.copyOf(out);
    }


    private static void collectCheck(@Nullable CheckCondition condition, String where,
            List<Surface> out)
    {
        switch (condition)
        {
        case null ->
        {
            // no condition — nothing to collect
        }
        case CheckConditionAll all -> all.getConditions().forEach(c -> collectCheck(c, where, out));
        case CheckConditionAny any -> any.getConditions().forEach(c -> collectCheck(c, where, out));
        case CheckConditionNot not -> collectCheck(not.getCondition(), where, out);
        case CheckConditionExpression expr -> add(out, where, expr.source());
        }
    }


    private static void collectTree(JsonNode node, String where, List<Surface> out)
    {
        if (node.isTextual())
        {
            add(out, where, node.asText());
        }
        else if (node.isArray() || node.isObject())
        {
            node.forEach(child -> collectTree(child, where, out));
        }
    }


    private static void addAll(List<Surface> out, String where, @Nullable List<String> texts)
    {
        if (texts != null)
        {
            texts.forEach(t -> add(out, where, t));
        }
    }


    private static void add(List<Surface> out, String where, @Nullable String text)
    {
        if (text != null)
        {
            out.add(new Surface(where, text));
        }
    }

}
