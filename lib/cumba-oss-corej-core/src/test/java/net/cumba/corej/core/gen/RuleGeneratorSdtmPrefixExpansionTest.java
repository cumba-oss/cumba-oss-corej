package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code RuleGenerator.expandSdtmPrefixRules} — the {@code --}-prefix expansion that turns one
 * domain-neutral static rule into the concrete per-domain rule.
 *
 * <p>
 * {@code CheckLevelCloneSiteTest} already holds the level-map half of this method (the phase-3
 * Severity/level-drop site). What it does not hold is everything around it: mutation testing
 * reported 12 of the method's 24 mutants surviving, including the removal of four separate "carry
 * the source field onto the expanded child" calls. That is exactly the failure mode the method's
 * own comment records — {@code buildRule} starts from a fresh {@code new Rule()}, so a field not
 * named in the copy block is silently dropped, and the loss is invisible to the loader, both
 * schemas and the writer because the SOURCE rule still carries it.
 * </p>
 *
 * <p>
 * ⚠ One mutant here is <b>equivalent and deliberately not chased</b>: the
 * {@code domain.length() >= 2} boundary. For a two-character domain {@code substring(0, 2)} returns
 * the domain itself, so {@code >= 2} and {@code > 2} agree at every length; only the negation is
 * observable, and {@link #aLongerDomainCodeStillContributesATwoCharacterPrefix} kills that.
 * </p>
 */
class RuleGeneratorSdtmPrefixExpansionTest
{

    private static CheckConditionLeaf leaf(String name, String operator)
    {
        return CheckConditionLeaf.builder().name(name).operator(operator).build();
    }


    private static String rendered(CheckCondition c)
    {
        return ExpressionPrinter.print(net.cumba.corej.core.expr.CheckToExpr.toExpr(c));
    }


    private static IDataTable ae()
    {
        return MockTable.of().name("AE").col("AEDTC", "2020-01-01").build();
    }


    /** A minimal expandable template: one leaf naming {@code --DTC}. */
    private static Rule template()
    {
        Rule tpl = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-PFX");
        tpl.setCore(core);
        tpl.setDescription("prefix fixture");
        tpl.setSensitivity(Sensitivity.RECORD);
        tpl.setCheck(new CheckConditionAll(List.of(leaf("--DTC", "non_empty"))));
        return tpl;
    }


    private static List<Rule> expand(String domain, Rule... templates)
    {
        List<Rule> out = new ArrayList<>();
        new RuleGenerator(null, null).expandSdtmPrefixRules(ae().getMetaData(), domain,
                List.of(templates), out, new RuleGenerationReport(domain, null, null, null));
        return out;
    }

    // ---- the prefix itself ----


    @Test
    void aTwoCharacterDomainSubstitutesItsOwnCode()
    {
        List<Rule> out = expand("AE", template());

        assertEquals(1, out.size());
        assertEquals("not empty(AEDTC)",
                rendered(Objects.requireNonNull(out.getFirst().getCheck())));
    }


    @Test
    void aLongerDomainCodeStillContributesATwoCharacterPrefix()
    {
        // An AP-- member's domain is its full four-character name; the prefix is the first two.
        // Negating `domain.length() >= 2` would substitute the whole code ("APQSDTC").
        assertEquals("not empty(APDTC)",
                rendered(Objects.requireNonNull(expand("APQS", template()).getFirst().getCheck())));
    }


    @Test
    void aSingleCharacterDomainIsItsOwnPrefix()
    {
        assertEquals("not empty(XDTC)",
                rendered(Objects.requireNonNull(expand("X", template()).getFirst().getCheck())));
    }

    // ---- pass-through paths ----


    @Test
    void aRuleWithoutADashPrefixPassesThroughAsTheVerySameInstance()
    {
        Rule plain = template();
        plain.setCheck(new CheckConditionAll(List.of(leaf("AEDTC", "non_empty"))));

        List<Rule> out = expand("AE", plain);

        assertEquals(1, out.size());
        assertSame(plain, out.getFirst(), "an unexpandable rule must not be rebuilt");
    }


    @Test
    void aLoadErrorTaggedRulePassesThroughUnmodifiedEvenThoughItHasADashPrefix()
    {
        // Rebuilding it would drop the loadError, and RuleRunner.execute would stop emitting its
        // ERROR sentinel.
        Rule broken = template();
        broken.setLoadError("bad enum");

        List<Rule> out = expand("AE", broken);

        assertEquals(1, out.size());
        assertSame(broken, out.getFirst());
        assertEquals("bad enum", out.getFirst().getLoadError());
    }


    @Test
    void aRuleWithNoCheckIsDroppedEntirely()
    {
        Rule noCheck = template();
        noCheck.setCheck(null);

        assertEquals(List.of(), expand("AE", noCheck));
    }


    @Test
    void aDashPrefixCarriedOnlyInTheLeafValueStillTriggersExpansion()
    {
        // containsDashPrefix accepts either half of the leaf: the NAME or a textual VALUE.
        Rule valueSide = template();
        valueSide.setCheck(new CheckConditionAll(List.of(CheckConditionLeaf.builder().name("AEDTC")
                .operator("equal_to")
                .value(new com.fasterxml.jackson.databind.node.TextNode("--STDTC")).build())));

        List<Rule> out = expand("AE", valueSide);

        assertEquals(1, out.size());
        assertEquals("TEST-PFX", out.getFirst().effectiveId());
        assertEquals("AEDTC == AESTDTC",
                rendered(Objects.requireNonNull(out.getFirst().getCheck())));
    }

    // ---- the copy block: every field the fresh Rule() would otherwise drop ----


    @Test
    void theExpandedChildKeepsTheSourceIdAndDomainNeutralDescription()
    {
        Rule out = expand("AE", template()).getFirst();

        assertEquals("TEST-PFX", out.effectiveId(), "the base CORE id is kept verbatim");
        assertEquals("prefix fixture", out.getDescription(),
                "the description stays domain-neutral");
    }


    @Test
    void theExpandedChildKeepsTheSourceGroupingVariables()
    {
        Rule tpl = template();
        tpl.setGroupingVariables(List.of("USUBJID"));

        assertEquals(List.of("USUBJID"), expand("AE", tpl).getFirst().getGroupingVariables());
    }


    @Test
    void theExpandedChildKeepsTheSourceOperationsAndMatchDatasets()
    {
        Rule tpl = template();
        tpl.setOperations(List.of());
        tpl.setMatchDatasets(List.of());

        Rule out = expand("AE", tpl).getFirst();

        assertEquals(List.of(), out.getOperations());
        assertEquals(List.of(), out.getMatchDatasets());
    }

}
