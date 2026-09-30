package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpansionTokens;
import net.cumba.corej.core.model.Binding;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.ClassScope;
import net.cumba.corej.core.model.DataStructureScope;
import net.cumba.corej.core.model.DatasetScope;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.ExpansionDirective;
import net.cumba.corej.core.model.ExpansionSource;
import net.cumba.corej.core.model.GroupingSpec;
import net.cumba.corej.core.model.LevelCheck;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.SubclassScope;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-expansion-token-delimiters} S5 — {@link ExpansionSurfaces} is the one list of rule
 * surfaces the loader gates G2 / G3 scan, and it must stay in lockstep with what
 * {@code TokenExpander.buildExpansion} substitutes. The lockstep is asserted in <b>both</b>
 * directions: a rule with a distinct token in every substituted field must yield every one
 * (dropping a field from the collector reds {@link #everySubstitutedSurfaceIsCollected}), and
 * running that rule through the expander must make exactly the collected tokens vanish (a
 * substituted field the collector does not list — or a listed field the expander does not
 * substitute — reds {@link #exactlyTheCollectedSurfacesAreSubstituted}).
 */
class ExpansionSurfacesTest
{

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private static CheckConditionExpression expr(String source)
    {
        return new CheckConditionExpression(CheckExpressionParser.parse(source), source);
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


    private static Set<String> tokensInJson(Rule rule) throws Exception
    {
        return tokensIn(List.of(MAPPER.writeValueAsString(rule)));
    }


    /**
     * A rule with a distinct token in every field the expander rewrites — the substituted surfaces.
     * The non-substituted surfaces (Requirements, Scope) are added by
     * {@link #withGatedOnlySurfaces} so the expansion half of the lockstep can run this fixture
     * as-is.
     */
    private static Rule substitutedSurfacesFixture(String id) throws Exception
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(id);
        rule.setCore(core);
        SequencedMap<Severity, LevelCheck> levels = new LinkedHashMap<>();
        levels.put(Severity.ERROR, new LevelCheck(expr("var_exists(&C1&)"), "level &L1&"));
        levels.put(Severity.WARNING, new LevelCheck(expr("var_exists(&C2&.&C2&SEQ)"), null));
        rule.setCheckLevels(levels);
        rule.setPrecondition(expr("var_exists(&P1&)"));
        Binding binding = new Binding();
        binding.setName("$b");
        binding.setExpression("record_count(filter=(&B1& == \"1\"))");
        rule.setBindings(List.of(binding));
        rule.setDescription("about &D1&");
        Outcome outcome = new Outcome();
        outcome.setMessage("&M1& is wrong");
        outcome.setOutputVariables(List.of("&O1&", "ADSL.&O2&"));
        rule.setOutcome(outcome);
        rule.setMatchDatasets(List.of(MAPPER.readValue("""
                {"Name":"&N1&",
                 "Keys":["&K1&",{"left":"&K2&","right":"&K3&"}],
                 "Filter":"&F1& == \\"X\\"",
                 "Join_As_String":"&J1&",
                 "Join_Type":"&T1&"}
                """, MatchDataset.class)));
        GroupingSpec grouping = new GroupingSpec();
        grouping.setVariables(List.of("&G1&"));
        rule.setGrouping(grouping);
        rule.setGroupingVariables(List.of("&G2&"));
        return rule;
    }

    /** The tokens of {@link #substitutedSurfacesFixture}. */
    private static final Set<String> SUBSTITUTED = Set.of("&C1&", "&L1&", "&C2&", "&P1&", "&B1&",
            "&D1&", "&M1&", "&O1&", "&O2&", "&N1&", "&K1&", "&K2&", "&K3&", "&F1&", "&J1&", "&T1&",
            "&G1&", "&G2&");

    /**
     * Adds the surfaces that are gated (G2 / G3 / R6) but copied verbatim by the expander: the four
     * {@code Requirements.Variables} facets and the {@code Scope} name lists.
     */
    private static Rule withGatedOnlySurfaces(Rule rule) throws Exception
    {
        rule.setRequirements(MAPPER.readValue("""
                {"Variables":{"All":["&R1&"],"Any":["&R2&","&R3&"],"None":["&R4&"],
                              "All_Or_None":["&R5&","&R6&"]}}
                """, Requirements.class));
        Scope scope = new Scope();
        DomainScope domains = new DomainScope();
        domains.setInclude(List.of("&S1&"));
        domains.setExclude(List.of("&S2&"));
        scope.setDomains(domains);
        DatasetScope datasets = new DatasetScope();
        datasets.setInclude(List.of("&S3&"));
        datasets.setExclude(List.of("&S4&"));
        scope.setDatasets(datasets);
        ClassScope classes = new ClassScope();
        classes.setInclude(List.of("&S5&"));
        classes.setExclude(List.of("&S6&"));
        scope.setClasses(classes);
        DataStructureScope structures = new DataStructureScope();
        structures.setInclude(List.of("&S7&"));
        structures.setExclude(List.of("&S8&"));
        scope.setDataStructures(structures);
        SubclassScope subclasses = new SubclassScope();
        subclasses.setInclude(List.of("&S9&"));
        subclasses.setExclude(List.of("&S10&"));
        scope.setSubclasses(subclasses);
        rule.setScope(scope);
        return rule;
    }

    /** The tokens {@link #withGatedOnlySurfaces} adds. */
    private static final Set<String> GATED_ONLY = Set.of("&R1&", "&R2&", "&R3&", "&R4&", "&R5&",
            "&R6&", "&S1&", "&S2&", "&S3&", "&S4&", "&S5&", "&S6&", "&S7&", "&S8&", "&S9&",
            "&S10&");

    @Test
    void everySubstitutedSurfaceIsCollected() throws Exception
    {
        Rule rule = withGatedOnlySurfaces(substitutedSurfacesFixture("SURF-1"));

        List<String> texts = ExpansionSurfaces.texts(rule);

        Set<String> expected = new TreeSet<>(SUBSTITUTED);
        expected.addAll(GATED_ONLY);
        assertEquals(expected, tokensIn(texts));
        // A level's own Message IS substituted by buildExpansion (LevelCheck.map rewrites the
        // condition and the message), so it is a surface, named after its level.
        List<ExpansionSurfaces.Surface> surfaces = ExpansionSurfaces.surfaces(rule);
        assertTrue(
                surfaces.stream().anyMatch(
                        s -> "Check[ERROR].Message".equals(s.where()) && s.text().contains("&L1&")),
                surfaces.toString());
        assertTrue(
                surfaces.stream().anyMatch(
                        s -> "Scope.Domains.Include".equals(s.where()) && s.text().equals("&S1&")),
                surfaces.toString());
        assertTrue(surfaces.stream().anyMatch(
                s -> "Scope.Subclasses.Exclude".equals(s.where()) && s.text().equals("&S10&")),
                surfaces.toString());
    }


    /**
     * The other direction. Every token of the fixture is bound (one {@code all_variables} directive
     * per token, over a one-column table), the rule is expanded, and the tokens that vanished from
     * the rule's JSON must be exactly the tokens the collector lists — no field is substituted
     * behind the collector's back, and every collected substituted field really is rewritten (a
     * survivor would have dropped the expansion with a reason instead of producing a rule).
     */
    @Test
    void exactlyTheCollectedSurfacesAreSubstituted() throws Exception
    {
        Rule rule = substitutedSurfacesFixture("SURF-3");
        Set<String> collected = tokensIn(ExpansionSurfaces.texts(rule));
        Set<String> before = tokensInJson(rule);
        assertEquals(SUBSTITUTED, collected, "the fixture carries exactly the substituted tokens");
        assertEquals(SUBSTITUTED, before, "and the JSON view sees every one of them");
        List<ExpansionDirective> directives = new ArrayList<>();
        for (String token : collected)
        {
            ExpansionDirective d = new ExpansionDirective();
            d.setToken(token);
            d.setOverJson(ExpansionSource.ALL_VARIABLES.getJsonValue());
            directives.add(d);
        }
        rule.setExpansion(directives);
        IDataTable table = MockTable.of().name("ADX").col("XCOL", "1").build();

        WildcardExpander.ExpansionResult result = TokenExpander.tryExpand(rule, table.getMetaData(),
                new TokenExpander.Context(null, null, "ADX"));

        List<Rule> expanded = assertInstanceOf(WildcardExpander.ExpansionResult.Expanded.class,
                result, result.toString()).rules();
        assertEquals(1, expanded.size());
        Set<String> after = tokensInJson(expanded.get(0));
        Set<String> vanished = new TreeSet<>(before);
        vanished.removeAll(after);
        assertEquals(Set.of(), after, "no token survives an expansion that bound every token");
        assertEquals(collected, vanished, "every vanished token was on a collected surface");
        assertEquals(Set.of(), tokensIn(ExpansionSurfaces.texts(expanded.get(0))),
                "the expanded rule's surfaces carry no token");
        assertEquals("XCOL is wrong", expanded.get(0).getOutcome().getMessage());
        assertEquals("level XCOL", expanded.get(0).getCheckLevels().get(Severity.ERROR).message());
    }


    @Test
    void surfacesNameWhereTheTextCameFrom()
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("SURF-2");
        rule.setCore(core);
        rule.setCheck(expr("var_exists(&C1&)"));
        rule.setDescription("about &D1&");

        List<ExpansionSurfaces.Surface> surfaces = ExpansionSurfaces.surfaces(rule);

        assertEquals(2, surfaces.size(), surfaces.toString());
        assertEquals("Check", surfaces.get(0).where());
        assertEquals("var_exists(&C1&)", surfaces.get(0).text());
        assertEquals("Description", surfaces.get(1).where());
    }


    @Test
    void anEmptyRuleHasNoSurfaces()
    {
        Rule rule = new Rule();
        assertEquals(List.of(), ExpansionSurfaces.texts(rule));
    }

}
