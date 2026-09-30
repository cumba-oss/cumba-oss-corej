package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.BeanDescription;
import com.fasterxml.jackson.databind.JavaType;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.ExpansionTokens;
import net.cumba.corej.core.model.ExpansionDirective;
import net.cumba.corej.core.model.ExpansionSource;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-expansion-token-delimiters} S5 — {@link ExpansionSurfaces} is the one list of rule
 * surfaces the loader gates G2 / G3 / R6 scan, and it must stay in lockstep with what
 * {@code TokenExpander.buildExpansion} substitutes.
 *
 * <p>
 * The claim is <b>no field is substituted behind the collector's back</b>, and it is checked over
 * the rule's whole Jackson tree rather than over a hand-picked field list: {@link #MAXIMAL} sets
 * every property Jackson writes for {@link Rule} and every model bean under it (asserted by
 * {@link #theFixtureSetsEveryPropertyJacksonWrites}, so a new field reds here until it is added),
 * and {@link #everyStringLeafIsSubstitutedExactlyWhereTheCollectorLooks} then puts a distinct
 * declared token into <b>every</b> String leaf of that tree, one leaf per expansion, and observes
 * the leaf's fate. One leaf at a time, because a leaf that is <em>dropped</em> from the expanded
 * rule vanishes exactly as a substituted one does — only the bound value turning up tells them
 * apart — and because a token carried verbatim makes the survivor check drop the whole expansion.
 * </p>
 *
 * <p>
 * A String leaf that is neither substituted nor collected must be in {@link #NOT_A_SURFACE}, with
 * its observed fate and the reason it is not a token surface; an entry that stops matching a leaf,
 * or whose leaf becomes collected, reds as stale. {@link #everyLeafAtOnce} is the same fixture with
 * every leaf filled at once.
 * </p>
 */
class ExpansionSurfacesTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    /** The one column every token binds to — a value no fixture text contains. */
    private static final String BOUND = "QZXV";

    /**
     * Every property Jackson writes, set once, with a multi-level {@code Check} (the level map is
     * the only shape with a per-level {@code Message}). {@code Expansion} is the one property left
     * out: the test writes the declarations itself. The {@code Keys} are qualified so that the
     * expander's primary-key presence check does not need them on the one-column table.
     */
    private static final String MAXIMAL = """
            {"id": "RULE-UUID",
             "Core": {"Id": "SURF-1", "Status": "Draft", "Version": "1"},
             "Description": "about X",
             "ExecutabilityHint": {"Category": "cat", "Detail": "detail"},
             "Authorities": [{"Organization": "CDISC", "Rule_Ids": ["R1"],
               "Standards": [{"Name": "SDTMIG", "Version": "3.4", "Substandard": "SUB",
                 "References": [{"Origin": "SDTM", "Version": "1",
                   "Rule_Identifier": {"Id": "RI", "Version": "1"},
                   "Citations": [{"Cited_Guidance": "g", "Document": "d", "Item": "i",
                                  "Section": "s"}]}]}]}],
             "Scope": {"Classes": {"Include": ["C"], "Exclude": ["C2"]},
               "Domains": {"Include": ["AE"], "Exclude": ["CM"], "include_split_datasets": true},
               "Datasets": {"Include": ["AE"], "Exclude": ["CM"]},
               "Use_Case": "INDH",
               "Data_Structures": {"Include": ["D"], "Exclude": ["D2"]},
               "Subclasses": {"Include": ["S"], "Exclude": ["S2"]}},
             "Requirements": {"Variables": {"All": ["A"], "Any": ["B", "C"], "None": ["N"],
                                            "All_Or_None": ["P", "Q"]},
               "Datasets": ["DM"], "Library": true, "Define": true, "Dictionary": true},
             "Check": {"ERROR": {"expression": "var_exists(A1)", "Message": "level msg"},
                       "WARNING": {"all": [{"expression": "var_exists(A2)"},
                                           {"not": {"expression": "var_exists(A3)"}}]}},
             "Precondition": {"any": [{"expression": "var_exists(P1)"}]},
             "Outcome": {"Message": "msg", "Output_Variables": ["O1", "ADSL.O2"]},
             "Bindings": [{"name": "$b", "expression": "record_count(filter=(B1 == \\"1\\"))"}],
             "Match_Datasets": [{"Name": "ADSL", "Keys": ["DM.USUBJID", {"left": "DM.K2", "right": "K3"}],
               "Child": false, "Join_Type": "left", "Join_As_String": "true", "keep_missings": true,
               "Filter": "F1 == \\"X\\""}],
             "Grouping_Variables": ["G1"],
             "Grouping": {"Variables": ["G2"], "keep_missings": true},
             "skipIfLibraryDefined": true, "Supp_Merge": true,
             "wildcards": {"W": {"min": 1, "max": 2}}, "wildcardExclude": ["WX"],
             "wildcardPairCatalogue": true,
             "Sensitivity": "Record", "Severity": "Error", "Executability": "Fully Executable",
             "Variable_Universe": "dataset"}
            """;

    /**
     * The fixtures as the rule's Jackson tree, in the two shapes of the {@code Check} key: the
     * level map of {@link #MAXIMAL}, and a plain condition (its {@code WARNING} level, promoted).
     */
    private static List<JsonNode> fixtures() throws Exception
    {
        ObjectNode plain = (ObjectNode) MAPPER.readTree(MAXIMAL);
        plain.set("Check", plain.get("Check").get("WARNING").deepCopy());
        return List.of(MAPPER.valueToTree(read(MAXIMAL)),
                MAPPER.valueToTree(MAPPER.treeToValue(plain, Rule.class)));
    }

    /** What happened to a leaf's token when the rule expanded. */
    enum Fate
    {
        /** The bound value replaced it in the expanded rule. */
        SUBSTITUTED,
        /** The field is not part of the expanded rule at all. */
        NOT_CARRIED,
        /**
         * Still in the expanded rule — the survivor check dropped the expansion with a stated
         * reason, or the field is one it cannot see.
         */
        CARRIED_VERBATIM
    }


    /**
     * A String leaf that is not a token surface by design.
     *
     * @param path
     *            the JSON pointer with every array index written {@code *}; a trailing {@code /**}
     *            covers the whole subtree
     * @param fate
     *            what the expander does with it, observed per leaf
     * @param reason
     *            why a token there needs no gate
     */
    record Exempt(String path, Fate fate, String reason)
    {
    }

    private static final String ENUM_REASON = "an enum: a token is an unrecognised value and a load"
            + " error (validateEnumFields), so it never reaches the expander";

    /** Every String leaf that is neither substituted nor collected, each with its reason. */
    private static final List<Exempt> NOT_A_SURFACE = List.of(
            new Exempt("/id", Fate.NOT_CARRIED,
                    "the rule UUID; every expansion is given a fresh deterministic one"),
            new Exempt("/Core/Id", Fate.CARRIED_VERBATIM,
                    "the expanded id is minted from it (<id>-<column>), so a token here survives and"
                            + " the survivor check drops every expansion with a stated reason"),
            new Exempt("/Core/Status", Fate.NOT_CARRIED,
                    "the expanded Core is minted: Status 'Generated'"),
            new Exempt("/Core/Version", Fate.NOT_CARRIED,
                    "the expanded Core is minted: Version '1'"),
            new Exempt("/ExecutabilityHint/**", Fate.NOT_CARRIED,
                    "prose, not carried into an expanded rule — so `R&D` there is never gated"),
            new Exempt("/Authorities/**", Fate.NOT_CARRIED,
                    "provenance (Organization, Standards, References, Citations, Rule_Ids), not"
                            + " carried into an expanded rule"),
            new Exempt("/Scope/Use_Case", Fate.CARRIED_VERBATIM,
                    "upper-case use-case codes, validated at load (R-4.10): a token is a load"
                            + " error before any expansion"),
            new Exempt("/Requirements/Datasets/*", Fate.CARRIED_VERBATIM,
                    "matched before expansion and NOT gated by R6 / G2 (only Requirements.Variables"
                            + " is): a declared token survives into every expansion, which the"
                            + " survivor check drops with a stated reason — filed by"
                            + " PLAN-expansion-token-delimiters lane ETD-Z"),
            new Exempt("/Bindings/*/name", Fate.CARRIED_VERBATIM,
                    "a binding's own $-name, the identifier a $-reference reads — never a token"
                            + " position; kept verbatim in the compiled binding, which is not"
                            + " serialised, and a `&NAME&` name cannot be referenced"),
            new Exempt("/wildcardExclude/*", Fate.NOT_CARRIED,
                    "the wildcard mechanism's own field (WildcardExpander), never part of a token"
                            + " expansion"),
            new Exempt("/Sensitivity", Fate.NOT_CARRIED, ENUM_REASON),
            new Exempt("/Severity", Fate.NOT_CARRIED, ENUM_REASON),
            new Exempt("/Executability", Fate.NOT_CARRIED, ENUM_REASON),
            new Exempt("/Variable_Universe", Fate.NOT_CARRIED, ENUM_REASON));

    private static Rule read(String json) throws Exception
    {
        return MAPPER.readValue(json, Rule.class);
    }


    private static Set<String> tokensIn(List<String> texts)
    {
        Set<String> out = new TreeSet<>();
        for (String text : texts)
        {
            ExpansionTokens.scan(text).occurrences().forEach(o -> out.add(o.text()));
        }
        return out;
    }


    private static List<String> textLeaves(JsonNode node)
    {
        List<String> out = new ArrayList<>();
        collectTextLeaves(node, "", out);
        return out;
    }


    private static void collectTextLeaves(JsonNode node, String path, List<String> out)
    {
        if (node.isTextual())
        {
            out.add(path);
        }
        else if (node.isArray())
        {
            for (int i = 0; i < node.size(); i++)
            {
                collectTextLeaves(node.get(i), path + "/" + i, out);
            }
        }
        else if (node.isObject())
        {
            node.properties()
                    .forEach(e -> collectTextLeaves(e.getValue(), path + "/" + e.getKey(), out));
        }
    }


    /** The leaf text carrying {@code token}: an expression position stays a valid expression. */
    private static String embed(String path, String token)
    {
        if (path.matches("/(Check|Precondition)(/.*)?/expression"))
        {
            return "var_exists(" + token + ")";
        }
        if (path.matches("/Bindings/\\d+/expression"))
        {
            return "record_count(filter=(" + token + " == \"1\"))";
        }
        if (path.matches("/Match_Datasets/\\d+/Filter"))
        {
            return token + " == \"X\"";
        }
        return token;
    }


    /** {@code tree} with each leaf of {@code tokens} (pointer → token) filled, as a loaded rule. */
    private static Rule fill(JsonNode tree, Map<String, String> tokens) throws Exception
    {
        JsonNode copy = tree.deepCopy();
        for (Map.Entry<String, String> e : tokens.entrySet())
        {
            String path = e.getKey();
            int slash = path.lastIndexOf('/');
            JsonNode parent = copy.at(path.substring(0, slash));
            String field = path.substring(slash + 1);
            TextNode value = TextNode.valueOf(embed(path, e.getValue()));
            if (parent instanceof ArrayNode array)
            {
                array.set(Integer.parseInt(field), value);
            }
            else
            {
                ((ObjectNode) parent).set(field, value);
            }
        }
        Rule rule = MAPPER.treeToValue(copy, Rule.class);
        // What the loader does before any expansion: the expander substitutes the COMPILED
        // bindings, so without this a Bindings leaf would read as dropped, not substituted.
        RulePackageLoader.materialiseBindings(rule);
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    /** Declares every token over {@code all_variables} and expands against one column. */
    private static WildcardExpander.ExpansionResult expand(Rule rule, Set<String> tokens)
    {
        List<ExpansionDirective> directives = new ArrayList<>();
        for (String token : tokens)
        {
            ExpansionDirective d = new ExpansionDirective();
            d.setToken(token);
            d.setOverJson(ExpansionSource.ALL_VARIABLES.getJsonValue());
            directives.add(d);
        }
        rule.setExpansion(directives);
        IDataTable table = MockTable.of().name("ADX").col(BOUND, "1").build();
        return TokenExpander.tryExpand(rule, table.getMetaData(),
                new TokenExpander.Context(null, null, "ADX"));
    }


    /**
     * Everything the expanded rule carries that a token could survive in: its JSON (minus the
     * minted ids, which name the bound column by construction) and its compiled bindings, which the
     * JSON does not show.
     */
    private static String rendering(Rule expanded)
    {
        ObjectNode json = MAPPER.valueToTree(expanded);
        json.remove("id");
        ((ObjectNode) json.get("Core")).remove("Id");
        return json + " " + expanded.getCompiledBindings();
    }


    private static Fate fateOf(Rule rule, String token)
    {
        WildcardExpander.ExpansionResult result = expand(rule, Set.of(token));
        if (result instanceof WildcardExpander.ExpansionResult.NoMatch noMatch)
        {
            assertTrue(noMatch.reason().contains("still carries the token '" + token + "'"),
                    noMatch.reason());
            return Fate.CARRIED_VERBATIM;
        }
        List<Rule> rules = assertInstanceOf(WildcardExpander.ExpansionResult.Expanded.class, result)
                .rules();
        assertEquals(1, rules.size());
        String rendering = rendering(rules.get(0));
        if (rendering.contains(token))
        {
            return Fate.CARRIED_VERBATIM;
        }
        return rendering.contains(BOUND) ? Fate.SUBSTITUTED : Fate.NOT_CARRIED;
    }


    private static @Nullable Exempt exemptFor(String path)
    {
        String shape = path.replaceAll("/\\d+(?=/|$)", "/*");
        for (Exempt e : NOT_A_SURFACE)
        {
            String p = e.path();
            if (p.endsWith("/**") ? shape.startsWith(p.substring(0, p.length() - 2))
                    : shape.equals(p))
            {
                return e;
            }
        }
        return null;
    }


    /**
     * The fixture is maximal: every property Jackson serialises for {@link Rule} and for each model
     * bean under it is present and non-null (every list non-empty), walked by Jackson's own
     * introspection — so a field added to the model reds here until the fixture carries it, and the
     * leaf test below cannot silently miss it. {@code Expansion} is the one deliberate absence.
     */
    @Test
    void theFixtureSetsEveryPropertyJacksonWrites() throws Exception
    {
        for (JsonNode fixture : fixtures())
        {
            List<String> missing = new ArrayList<>();
            requireEveryProperty(MAPPER.constructType(Rule.class), fixture, "", missing);
            assertEquals(List.of("/Expansion"), missing);
        }
    }


    private static void requireEveryProperty(JavaType type, @Nullable JsonNode node, String path,
            List<String> missing)
    {
        if (node == null || node.isNull())
        {
            missing.add(path);
            return;
        }
        if (type.isCollectionLikeType())
        {
            if (node.isEmpty())
            {
                missing.add(path + "/*");
            }
            node.forEach(element -> requireEveryProperty(type.getContentType(), element,
                    path + "/*", missing));
            return;
        }
        if (type.isMapLikeType())
        {
            node.properties().forEach(e -> requireEveryProperty(type.getContentType(), e.getValue(),
                    path + "/" + e.getKey(), missing));
            return;
        }
        Class<?> raw = type.getRawClass();
        if (raw.isEnum() || !raw.getPackageName().equals(Rule.class.getPackageName()))
        {
            return;
        }
        BeanDescription bean = MAPPER.getSerializationConfig().introspect(type);
        for (BeanPropertyDefinition property : bean.findProperties())
        {
            if (property.couldSerialize())
            {
                requireEveryProperty(property.getPrimaryType(), node.get(property.getName()),
                        path + "/" + property.getName(), missing);
            }
        }
    }


    /**
     * Every String leaf, one at a time, carrying a distinct declared token: a collected leaf must
     * be substituted (or, on a gated-only surface, carried verbatim), and a leaf the collector does
     * not list must be a {@link #NOT_A_SURFACE} entry with the fate that entry states. So a field
     * that {@code buildExpansion} starts rewriting without {@link ExpansionSurfaces} listing it
     * reds here, as does a listed field it stops rewriting.
     */
    @Test
    void everyStringLeafIsSubstitutedExactlyWhereTheCollectorLooks() throws Exception
    {
        List<String> failures = new ArrayList<>();
        Set<Exempt> used = new LinkedHashSet<>();
        for (JsonNode tree : fixtures())
        {
            List<String> leaves = textLeaves(tree);
            assertTrue(leaves.size() > 50, "the fixture has lost its leaves: " + leaves);
            for (int i = 0; i < leaves.size(); i++)
            {
                String path = leaves.get(i);
                String token = "&L" + i + "&";
                Rule rule = fill(tree, Map.of(path, token));
                boolean collected = tokensIn(ExpansionSurfaces.texts(rule)).contains(token);
                boolean gatedOnly = ExpansionSurfaces.gatedOnly(rule).stream()
                        .anyMatch(s -> s.text().contains(token));
                Exempt exempt = exemptFor(path);
                Fate expected;
                if (exempt != null)
                {
                    used.add(exempt);
                    if (collected)
                    {
                        failures.add(path + " is collected, so its NOT_A_SURFACE entry is stale");
                        continue;
                    }
                    expected = exempt.fate();
                }
                else if (!collected)
                {
                    failures.add(path + " is neither collected by ExpansionSurfaces nor listed in"
                            + " NOT_A_SURFACE (fate " + fateOf(rule, token) + ")");
                    continue;
                }
                else
                {
                    expected = gatedOnly ? Fate.CARRIED_VERBATIM : Fate.SUBSTITUTED;
                }
                Fate observed = fateOf(rule, token);
                if (observed != expected)
                {
                    failures.add(path + ": expected " + expected + ", observed " + observed
                            + (collected ? " (collected)" : " (not collected)"));
                }
            }
        }
        assertEquals(List.of(), failures);
        assertEquals(new LinkedHashSet<>(NOT_A_SURFACE), used,
                "every NOT_A_SURFACE entry must still match a leaf");
    }


    /**
     * The same fixture with EVERY leaf filled at once: the collector yields exactly the tokens of
     * the non-exempt leaves, and — the verbatim-carried leaves keeping their authored text, since
     * one surviving token drops the whole expansion — expanding with every token declared leaves
     * none behind anywhere in the expanded rule.
     */
    @Test
    void everyLeafAtOnce() throws Exception
    {
        JsonNode tree = MAPPER.valueToTree(read(MAXIMAL));
        List<String> leaves = textLeaves(tree);
        Map<String, String> all = new LinkedHashMap<>();
        Set<String> expectedCollected = new TreeSet<>();
        for (int i = 0; i < leaves.size(); i++)
        {
            String token = "&L" + i + "&";
            all.put(leaves.get(i), token);
            if (exemptFor(leaves.get(i)) == null)
            {
                expectedCollected.add(token);
            }
        }
        Rule full = fill(tree, all);
        assertEquals(expectedCollected, tokensIn(ExpansionSurfaces.texts(full)));

        Set<String> carried = tokensIn(ExpansionSurfaces.gatedOnly(full).stream()
                .map(ExpansionSurfaces.Surface::text).toList());
        Map<String, String> expandable = new LinkedHashMap<>();
        all.forEach((path, token) ->
        {
            Exempt exempt = exemptFor(path);
            boolean verbatim = exempt != null ? exempt.fate() == Fate.CARRIED_VERBATIM
                    : carried.contains(token);
            if (!verbatim)
            {
                expandable.put(path, token);
            }
        });
        Rule rule = fill(tree, expandable);

        WildcardExpander.ExpansionResult result = expand(rule, new TreeSet<>(expandable.values()));

        List<Rule> expanded = assertInstanceOf(WildcardExpander.ExpansionResult.Expanded.class,
                result, result.toString()).rules();
        assertEquals(1, expanded.size());
        Rule concrete = expanded.get(0);
        assertEquals(Set.of(), tokensIn(List.of(rendering(concrete))),
                "no token survives an expansion that bound every token");
        assertEquals(Set.of(), tokensIn(ExpansionSurfaces.texts(concrete)));
        assertEquals(BOUND, concrete.getOutcome().getMessage());
        assertEquals(BOUND, concrete.getCheckLevels().get(Severity.ERROR).message());
    }


    /**
     * The surface names the gates print. A level's {@code Message} is named after its level's
     * condition surface: {@code Check.Message} when the condition is {@code Check} (a single-level
     * map), {@code Check[ERROR].Message} beside {@code Check[ERROR]} on a multi-level rule.
     */
    @Test
    void surfacesNameWhereTheTextCameFrom() throws Exception
    {
        Set<String> multi = new LinkedHashSet<>();
        ExpansionSurfaces.surfaces(read(MAXIMAL)).forEach(s -> multi.add(s.where()));
        assertEquals(
                List.of("Check[ERROR]", "Check[WARNING]", "Precondition", "Check[ERROR].Message",
                        "Bindings[0]", "Description", "Outcome.Message", "Outcome.Output_Variables",
                        "Match_Datasets[0]", "Grouping.Variables", "Grouping_Variables",
                        "Requirements.Variables.All", "Requirements.Variables.Any",
                        "Requirements.Variables.None", "Requirements.Variables.All_Or_None",
                        "Scope.Domains.Include", "Scope.Domains.Exclude", "Scope.Datasets.Include",
                        "Scope.Datasets.Exclude", "Scope.Classes.Include", "Scope.Classes.Exclude",
                        "Scope.Data_Structures.Include", "Scope.Data_Structures.Exclude",
                        "Scope.Subclasses.Include", "Scope.Subclasses.Exclude"),
                List.copyOf(multi));

        Rule single = read("""
                {"Core": {"Id": "SURF-2"},
                 "Check": {"ERROR": {"expression": "var_exists(&C1&)", "Message": "&M1& is wrong"}},
                 "Description": "about &D1&"}
                """);
        assertEquals(
                List.of(new ExpansionSurfaces.Surface("Check", "var_exists(&C1&)"),
                        new ExpansionSurfaces.Surface("Check.Message", "&M1& is wrong"),
                        new ExpansionSurfaces.Surface("Description", "about &D1&")),
                ExpansionSurfaces.surfaces(single));
    }


    @Test
    void anEmptyRuleHasNoSurfaces()
    {
        Rule rule = new Rule();
        assertEquals(List.of(), ExpansionSurfaces.texts(rule));
    }

}
