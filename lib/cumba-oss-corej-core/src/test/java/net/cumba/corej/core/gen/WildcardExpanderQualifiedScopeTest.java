package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.testkit.MockTable;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * Fix #124 — template expansion of <b>qualified</b> {@code Requirements.Variables} entries.
 * <p>
 * The point of these tests is the <em>tuple pairing</em>. A template Check over {@code TRTxxP} on
 * the primary, scoped by {@code ADSL.TRTxxPN}, must expand the scope entry with the <b>same</b>
 * {@code xx} the Check got: the {@code xx=01} rule requires {@code ADSL.TRT01PN} and must not be
 * satisfied by {@code ADSL.TRT02PN}. Substituting only on an exact whole-entry map hit would leave
 * the entry as a marker, and {@code ScopeMatcher}'s at-least-one matching would then accept any
 * {@code TRTnnPN} column — silently breaking the pairing.
 * </p>
 */
class WildcardExpanderQualifiedScopeTest
{

    private static Rule template(List<String> include, @Nullable List<String> exclude)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-124-WC");
        rule.setCore(core);
        rule.setCheck(CheckConditionLeaf.builder().name("TRTxxP").operator("non_empty").build());
        VariableRequirement vr = new VariableRequirement();
        vr.setAll(include);
        vr.setNone(exclude);
        Requirements req = new Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        return rule;
    }


    private static DataTableMeta adaeMeta()
    {
        // Two treatment periods on the primary => two expansion tuples (xx=01, xx=02).
        return MockTable.of().name("ADAE").col("TRT01P", "a").col("TRT02P", "b").build()
                .getMetaData();
    }


    private static List<String> includesOf(Rule rule)
    {
        VariableRequirement vars = rule.effectiveVariableRequirement();
        assertNotNull(vars, "the expanded rule must still carry Requirements.Variables");
        List<String> all = vars.getAll();
        return all == null ? List.of() : all;
    }


    private static List<String> allIncludes(List<Rule> rules)
    {
        List<String> out = new ArrayList<>();
        for (Rule r : rules)
        {
            out.addAll(includesOf(r));
        }
        return out;
    }


    @Test
    void qualifiedMarkerEntryBindsTheSameTupleAsTheCheck()
    {
        List<Rule> expanded = WildcardExpander.expand(template(List.of("ADSL.TRTxxPN"), null),
                adaeMeta());
        assertEquals(2, expanded.size(), "one rule per treatment period");
        List<String> includes = allIncludes(expanded);
        assertTrue(includes.contains("ADSL.TRT01PN"),
                "xx=01 expansion must require ADSL.TRT01PN, got " + includes);
        assertTrue(includes.contains("ADSL.TRT02PN"),
                "xx=02 expansion must require ADSL.TRT02PN, got " + includes);
        for (String entry : includes)
        {
            assertTrue(entry.startsWith("ADSL.TRT") && entry.endsWith("PN"), entry);
            assertTrue(!entry.contains("xx"), "the marker must be substituted, not left: " + entry);
        }
    }


    @Test
    void eachExpansionCarriesOnlyItsOwnTupleBinding()
    {
        List<Rule> expanded = WildcardExpander.expand(template(List.of("ADSL.TRTxxPN"), null),
                adaeMeta());
        for (Rule rule : expanded)
        {
            // The Check name and the scope entry must agree on the index — that IS the pairing.
            String checkName = ((CheckConditionLeaf) rule.getCheck()).getName();
            assertNotNull(checkName);
            String index = checkName.substring("TRT".length(), "TRT".length() + 2);
            assertEquals(List.of("ADSL.TRT" + index + "PN"), includesOf(rule),
                    "scope entry must bind the same tuple index as the Check name " + checkName);
        }
    }


    @Test
    void qualifiedEntryReusingTheCheckTokenIsSubstitutedFromTheMap()
    {
        // Here the scope entry's variable half IS the Check's template name, so the concrete
        // column already resolved for the Check is reused verbatim.
        List<Rule> expanded = WildcardExpander.expand(template(List.of("ADSL.TRTxxP"), null),
                adaeMeta());
        List<String> includes = allIncludes(expanded);
        assertTrue(includes.contains("ADSL.TRT01P"), includes.toString());
        assertTrue(includes.contains("ADSL.TRT02P"), includes.toString());
    }


    @Test
    void qualifiedLiteralEntryIsCarriedThroughUnchanged()
    {
        List<Rule> expanded = WildcardExpander.expand(template(List.of("DM.ARM"), null),
                adaeMeta());
        assertEquals(2, expanded.size());
        for (Rule rule : expanded)
        {
            assertEquals(List.of("DM.ARM"), includesOf(rule));
        }
    }


    @Test
    void qualifiedEntryNeedingAnUnboundGroupIsLeftAsATemplate()
    {
        // The tuple binds only `xx`; an entry needing `y` cannot be resolved and is left alone,
        // falling through to ScopeMatcher's at-least-one marker matching.
        List<Rule> expanded = WildcardExpander.expand(template(List.of("ADSL.CRITy"), null),
                adaeMeta());
        for (Rule rule : expanded)
        {
            assertEquals(List.of("ADSL.CRITy"), includesOf(rule));
        }
    }


    @Test
    void excludeEntriesAreExpandedToo()
    {
        List<Rule> expanded = WildcardExpander
                .expand(template(List.of("TRTxxP"), List.of("ADSL.TRTxxPN")), adaeMeta());
        for (Rule rule : expanded)
        {
            VariableRequirement vars = rule.effectiveVariableRequirement();
            assertNotNull(vars, "the expanded rule must still carry Requirements.Variables");
            List<String> exclude = vars.getNone();
            assertNotNull(exclude);
            assertEquals(1, exclude.size());
            assertTrue(exclude.get(0).matches("ADSL\\.TRT\\d{2}PN"), exclude.toString());
        }
    }


    @Test
    void globVariableHalfIsNotRewrittenByMarkerSubstitution()
    {
        // Review H1: ScopeMatcher.scopeEntryPattern resolves a glob AHEAD of the wildcard
        // markers, so the expander must not parse a glob half as a marker template. Without the
        // guard, WildcardPattern.parse("*DY") captures the `*` root and rewrites the entry to the
        // literal `DM.AESTDY` — silently destroying the glob.
        List<Rule> expanded = WildcardExpander.expand(starTemplate(List.of("DM.*DY")),
                aeMetaWithStarColumns());
        for (Rule rule : expanded)
        {
            assertEquals(List.of("DM.*DY"), includesOf(rule),
                    "a glob variable half must survive expansion unchanged");
        }
    }


    @Test
    void regexVariableHalfIsNotRewrittenByMarkerSubstitution()
    {
        // Same defect, sharper symptom: `w` inside a regex is a marker run, so substitution would
        // emit `DM./^\3+DTC$/` — an invalid back-reference. Expansions bypass RulePackageLoader,
        // so nothing would re-validate it and it would throw at match time.
        List<Rule> expanded = WildcardExpander.expand(starTemplate(List.of("DM./^\\w+DTC$/")),
                aeMetaWithStarColumns());
        for (Rule rule : expanded)
        {
            assertEquals(List.of("DM./^\\w+DTC$/"), includesOf(rule),
                    "a regex variable half must survive expansion unchanged");
            String entry = includesOf(rule).get(0);
            // Must still compile — the whole point of leaving it alone.
            assertNotNull(net.cumba.corej.core.exec.ScopeMatcher
                    .scopePattern(entry.substring(entry.indexOf('.') + 1)));
        }
    }


    /** A template whose Check uses a bare {@code *} root marker, paired per Fix #84. */
    private static Rule starTemplate(List<String> include)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-124-STAR");
        rule.setCore(core);
        rule.setCheck(CheckConditionLeaf.builder().name("*DTC").operator("non_empty").build());
        VariableRequirement vr = new VariableRequirement();
        vr.setAll(include);
        Requirements req = new Requirements();
        req.setVariables(vr);
        rule.setRequirements(req);
        return rule;
    }


    private static DataTableMeta aeMetaWithStarColumns()
    {
        return MockTable.of().name("AE").col("AESTDTC", "x").build().getMetaData();
    }


    @Test
    void unqualifiedEntriesKeepTheExactPreFix124Behaviour()
    {
        // Whole-entry map lookup only: a token the Check uses is substituted, one it does not use
        // is left untouched (it is NOT tuple-derived). Pins that the 883 rules already carrying a
        // variable scope expand exactly as before.
        List<Rule> expanded = WildcardExpander
                .expand(template(List.of("TRTxxP", "TRTxxPN", "USUBJID"), null), adaeMeta());
        for (Rule rule : expanded)
        {
            List<String> includes = includesOf(rule);
            assertEquals(3, includes.size());
            assertTrue(includes.get(0).matches("TRT\\d{2}P"),
                    "Check token substituted: " + includes);
            assertEquals("TRTxxPN", includes.get(1),
                    "a token the Check does not use stays a template (unchanged behaviour)");
            assertEquals("USUBJID", includes.get(2));
        }
    }

}
