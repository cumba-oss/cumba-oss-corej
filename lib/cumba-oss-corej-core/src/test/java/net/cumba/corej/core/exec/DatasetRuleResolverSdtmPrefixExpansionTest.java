package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.gen.RuleGenerationReport;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Pins {@code DatasetRuleResolver.specialiseStaticRules} — the bind-time pass (D77) that hands one
 * domain-neutral static rule to {@link RuleSpecialiser} and delivers the concrete per-domain rule.
 *
 * <p>
 * {@code CheckLevelCloneSiteTest} holds the level-map half of this pass (the phase-3
 * Severity/level-drop site); this class holds the prefix policy and the copy semantics. Since D77
 * the copy is a full reflective shallow copy, so "field silently dropped from the clone" can no
 * longer happen by omission — the identity pins below assert the pass-through and carry contracts
 * instead.
 * </p>
 */
class DatasetRuleResolverSdtmPrefixExpansionTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }


    private static net.cumba.corej.core.model.CheckConditionExpression leaf(String name,
            String operator)
    {
        return expr(
                "non_empty".equals(operator) ? "not empty(" + name + ")" : "empty(" + name + ")");
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
        return expand(ae(), domain, templates);
    }


    private static List<Rule> expand(IDataTable table, String domain, Rule... templates)
    {
        List<Rule> out = new ArrayList<>();
        new DatasetRuleResolver(null).specialiseStaticRules(table, domain, List.of(templates), out,
                new RuleGenerationReport());
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
    void anAssociatedPersonsDomainSubstitutesItsParentSuffix()
    {
        // ⚠ Re-pinned under D77c (F1 / D77f): this test used to assert `APQS` -> `APDTC` — the
        // old expander's own `domain.substring(0, 2)` policy, which encoded the bug. An AP
        // dataset carries PARENT-prefixed variables (`RuleRunner`'s own EC-36 comment: "for an AP
        // dataset that means --TERM -> MHTERM, not APMHTERM"), so on an APID-bearing APQS dataset
        // the variable prefix is the 2-character parent suffix "QS" and `--DTC` is `QSDTC`.
        IDataTable apqs = MockTable.of().name("APQS").col("APID", "AP01").col("QSDTC", "2020")
                .build();
        assertEquals("not empty(QSDTC)", rendered(
                Objects.requireNonNull(expand(apqs, "APQS", template()).getFirst().getCheck())));
    }


    @Test
    void anApShapedDomainWithoutAnApidColumnKeepsTheFullCode()
    {
        // The AP suffix is gated on an APID column (Python's is_ap); without one the domain code
        // itself is the substitution — the D77c authority, not a truncation.
        assertEquals("not empty(APQSDTC)",
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
        valueSide.setCheck(new CheckConditionAll(List.of(expr("AEDTC == --STDTC"))));

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
