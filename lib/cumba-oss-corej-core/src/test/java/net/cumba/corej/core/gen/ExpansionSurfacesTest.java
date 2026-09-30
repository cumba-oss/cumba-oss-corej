package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.SequencedMap;
import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.ExpansionTokens;
import net.cumba.corej.core.model.Binding;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.GroupingSpec;
import net.cumba.corej.core.model.LevelCheck;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.datatable.report.Severity;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-expansion-token-delimiters} S5 — {@link ExpansionSurfaces} is the one list of rule
 * surfaces the loader gates G2 / G3 scan, and it must stay in lockstep with what
 * {@code TokenExpander.buildExpansion} substitutes. A rule with a distinct token in <b>every</b>
 * substituted field must yield every one; dropping a field from the collector reds this test.
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


    @Test
    void everySubstitutedSurfaceIsCollected() throws Exception
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("SURF-1");
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
        rule.setRequirements(MAPPER.readValue("""
                {"Variables":{"All":["&R1&"],"Any":["&R2&","&R3&"],"None":["&R4&"],
                              "All_Or_None":["&R5&","&R6&"]}}
                """, Requirements.class));

        List<String> texts = ExpansionSurfaces.texts(rule);

        assertEquals(Set.of("&C1&", "&C2&", "&P1&", "&B1&", "&D1&", "&M1&", "&O1&", "&O2&", "&N1&",
                "&K1&", "&K2&", "&K3&", "&F1&", "&J1&", "&T1&", "&G1&", "&G2&", "&R1&", "&R2&",
                "&R3&", "&R4&", "&R5&", "&R6&"), tokensIn(texts));
        // A level's own Message is NOT substituted by buildExpansion (LevelCheck.mapConditions
        // maps the condition only), so it is not a surface — and that must stay visible here.
        assertTrue(texts.stream().noneMatch(t -> t.contains("&L1&")), texts.toString());
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
