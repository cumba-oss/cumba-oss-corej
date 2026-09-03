package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.expr.ExpressionPrinter;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.GroupingSpec;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.corej.core.model.VariableUniverse;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code RuleGenerator.expandSdtmPrefixRules} — the second of the two live corpus-delivery
 * expansions, and the third {@code new Rule()} clone site in the engine. It resolves a
 * domain-neutral {@code --DTC} rule into the concrete {@code AEDTC} rule the engine runs.
 *
 * <p>
 * The clone site's hazard is the one Plan C measured at 944 finding rows: {@code buildRule} starts
 * from a fresh {@code new Rule()}, so a top-level field this block does not name is <b>silently
 * dropped</b> from every {@code --} expansion — invisible to the loader, to both schemas and to the
 * writer, because the SOURCE rule still carries it.
 * </p>
 *
 * <p>
 * The {@code Sensitivity} handling here is deliberately three-legged and each leg is pinned below:
 * {@code buildRule} needs a non-null value so it is seeded with {@code Record}; an authored value
 * must override that seed; and a source rule that authored <em>nothing</em> must have the seed
 * cleared again so the derivation — not the seed — decides. Collapsing any leg silently
 * reclassifies the rule's verdict granularity.
 * </p>
 */
class SdtmPrefixExpansionCarryOverTest
{

    private static IDataTable aeTable()
    {
        return MockTable.of().name("AE").col("AEDTC", "2020-01-01").col("USUBJID", "S1").build();
    }


    private static Rule prefixTemplate(String coreId)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(coreId);
        rule.setCore(core);
        rule.setDescription("--DTC must be populated");
        rule.setCheck(new CheckConditionAll(
                List.of(CheckConditionLeaf.builder().name("--DTC").operator("non_empty").build())));
        rule.setVariableUniverse(VariableUniverse.DATA);
        GroupingSpec grouping = new GroupingSpec();
        grouping.setVariables(List.of("USUBJID"));
        grouping.setKeepMissings(Boolean.TRUE);
        rule.setGrouping(grouping);
        Outcome outcome = new Outcome();
        outcome.setMessage("--DTC is empty");
        rule.setOutcome(outcome);
        return rule;
    }


    private static Rule expandOnce(Rule template)
    {
        List<Rule> out = new ArrayList<>();
        new RuleGenerator(null, null).expandSdtmPrefixRules(aeTable().getMetaData(), "AE",
                List.of(template), out, new RuleGenerationReport("AE", null, null, null));
        assertEquals(1, out.size(), "one source rule expands to exactly one per-domain rule");
        Rule expanded = out.get(0);
        // ⛔ A load-errored expansion short-circuits deriveOmittedFields, which would make the
        // Sensitivity assertions below vacuously green. The fixture must be clean.
        assertNull(expanded.getLoadError(), expanded.getLoadError());
        return expanded;
    }


    /**
     * The expansion resolves the prefix and keeps the source rule's Core id verbatim (base-rule
     * first, no per-domain id), so the per-domain rows roll up onto the one base id in the report.
     */
    @Test
    @DisplayName("the -- prefix is resolved and the base Core id is kept")
    void thePrefixIsResolvedAndTheIdIsKept()
    {
        Rule expanded = expandOnce(prefixTemplate("PFX-1"));

        assertEquals("PFX-1", expanded.effectiveId(),
                "the per-domain rule keeps the base id so its rows roll up onto one rule");
        assertEquals("not empty(AEDTC)",
                ExpressionPrinter.print(CheckToExpr.toExpr(expanded.getCheck())),
                "an unresolved '--DTC' names no column, so the check would pass everywhere");
    }


    /**
     * {@code Variable_Universe} and the {@code Grouping:} block ride onto the expansion. Losing
     * either changes what the rule evaluates: the universe decides whether it reads data columns or
     * Define ItemDefs, the grouping keys decide the verdict granularity.
     */
    @Test
    @DisplayName("Variable_Universe and the Grouping block ride onto the expansion")
    void evaluationShapeRidesOntoTheExpansion()
    {
        Rule expanded = expandOnce(prefixTemplate("PFX-2"));

        assertEquals(VariableUniverse.DATA, expanded.getVariableUniverse(),
                "dropping Variable_Universe silently flips the rule between Define metadata and "
                        + "data columns");
        GroupingSpec grouping = expanded.getGrouping();
        assertNotNull(grouping,
                "the Grouping: block is a top-level field buildRule does not know about — the "
                        + "exact shape that cost 944 finding rows on Severity");
        assertEquals(List.of("USUBJID"), grouping.getVariables());
        assertEquals(true, grouping.getKeepMissings());
    }


    /**
     * An <b>authored</b> {@code Sensitivity} must win over {@code buildRule}'s {@code Record} seed
     * and must not be re-derived. The absent derivation rationale is the observable proof that the
     * author's value was carried rather than replaced.
     */
    @Test
    @DisplayName("an authored Sensitivity overrides the Record seed and is not re-derived")
    void authoredSensitivityWinsOverTheSeed()
    {
        Rule template = prefixTemplate("PFX-3");
        template.setSensitivity(Sensitivity.DATASET);

        Rule expanded = expandOnce(template);

        assertEquals(Sensitivity.DATASET, expanded.getSensitivity(),
                "the seed is Record; an authored Dataset must survive it, or every authored "
                        + "dataset-level rule silently becomes record-level");
        Map<String, String> rationale = expanded.getDerivationRationale();
        assertNull(rationale == null ? null : rationale.get("Sensitivity"),
                "the author stated the value, so nothing may be derived over it: " + rationale);
    }


    /**
     * The mirror leg: a source rule that authored no {@code Sensitivity} must have
     * {@code buildRule}'s {@code Record} seed <b>cleared</b> so the classifier decides. Leaving the
     * seed in place reinstates the old blanket {@code Record} fallback that predates the classifier
     * and was wrong for every rule yielding a single dataset-level verdict.
     */
    @Test
    @DisplayName("an omitted Sensitivity is cleared of the seed and derived instead")
    void omittedSensitivityIsDerivedNotSeeded()
    {
        Rule expanded = expandOnce(prefixTemplate("PFX-4"));

        assertNotNull(expanded.getSensitivity(),
                "the expansion bypasses the loader, so the generator must derive what the source "
                        + "omitted");
        assertNotNull(expanded.getDerivationRationale(),
                "the seed must be cleared BEFORE the derivation runs — a surviving 'Record' seed "
                        + "looks authored and suppresses the classifier entirely");
        assertNotNull(expanded.getDerivationRationale().get("Sensitivity"),
                "" + expanded.getDerivationRationale());
    }

}
