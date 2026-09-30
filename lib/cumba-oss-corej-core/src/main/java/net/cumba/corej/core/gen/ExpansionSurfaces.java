package net.cumba.corej.core.gen;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.model.Binding;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.CheckConditionNot;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.VariableRequirement;
import org.jspecify.annotations.Nullable;

/**
 * The rule surfaces a declared expansion token is read from — in one place, so the loader gates G2
 * / G3 ({@code RulePackageLoader}) and a reviewer can see exactly what is gated
 * ({@code PLAN-expansion-token-delimiters} S5).
 *
 * <p>
 * The list is exactly what {@link TokenExpander#buildExpansion} substitutes: the authored source of
 * every Check level and of the Precondition, the authored {@code Bindings} expressions,
 * Description, {@code Outcome.Message}, {@code Output_Variables}, every text node of each
 * {@code Match_Datasets} entry's JSON tree (walked as {@code substituteTree} walks it: Name, Keys
 * on both sides, Filter, Join_As_String, Join_Type), {@code Grouping.Variables} and
 * {@code Grouping_Variables} — plus {@code Requirements.Variables}, which is not substituted but is
 * where gate R6 looks. Prose that is never substituted (ExecutabilityHint, Source, Standards,
 * Authorities, Scope, Core) is not a surface, so prose like {@code R&D} there is never gated.
 * {@code ExpansionSurfacesTest} keeps this in lockstep with the expander: a rule with a distinct
 * token in every substituted field must yield every one.
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
     *            the surface, e.g. {@code Check}, {@code Outcome.Output_Variables},
     *            {@code Match_Datasets[0]}
     * @param text
     *            the authored text
     */
    public record Surface(String where, String text)
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
        List<CheckCondition> levels = rule.checkConditions();
        for (int i = 0; i < levels.size(); i++)
        {
            collectCheck(levels.get(i), levels.size() == 1 ? "Check" : "Check[" + i + "]", out);
        }
        collectCheck(rule.getPrecondition(), "Precondition", out);
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
        Requirements req = rule.getRequirements();
        VariableRequirement vars = req == null ? null : req.getVariables();
        if (vars != null)
        {
            addAll(out, "Requirements.Variables.All", vars.getAll());
            addAll(out, "Requirements.Variables.Any", vars.anyUnion());
            addAll(out, "Requirements.Variables.None", vars.getNone());
            addAll(out, "Requirements.Variables.All_Or_None", vars.allOrNoneUnion());
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
