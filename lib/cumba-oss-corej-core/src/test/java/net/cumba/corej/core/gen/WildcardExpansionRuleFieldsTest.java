package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.GroupingSpec;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.corej.core.model.VariableRequirement;
import net.cumba.corej.core.model.VariableUniverse;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code WildcardExpander.expandRule} builds every concrete expansion <b>field by field</b> from a
 * fresh {@code new Rule()}. A field the rebuild forgets is <b>silently dropped</b> from every
 * expanded child, and the drop is invisible to the loader, to both schemas and to the writer,
 * because the TEMPLATE still carries it. Plan C measured that exact shape costing 944 finding rows
 * on {@code Severity}.
 *
 * <p>
 * This test pins the carried fields whose loss changes what the engine <em>runs</em> rather than
 * how it reports: the identity ({@code Id}, {@code Status}, {@code Version}), the evaluation
 * universe ({@code Variable_Universe}), the authored {@code Sensitivity} and its derivation, the
 * join keys ({@code Match_Datasets}), the grouping keys, the operation column names (Fix #152) and
 * the requirement gate. Each assertion is an exact value; a "not null" would pass on a rule the
 * expander silently emptied.
 * </p>
 */
class WildcardExpansionRuleFieldsTest
{

    private static final String TEMPLATE_ID = "WCF-1";

    private static final String EXPANDED_ID = "WCF-1-TRT01P";

    /** ADSL-shaped columns: one treatment period, so exactly one expansion tuple (xx=01). */
    private static DataTableMeta oneTreatmentPeriod()
    {
        return MockTable.of().name("ADAE").col("TRT01P", "A").col("TRT01PN", "1").build()
                .getMetaData();
    }


    private static MatchDataset adslOnUsubjid()
    {
        MatchDataset md = new MatchDataset();
        md.setName("ADSL");
        md.setKeys(List.of("USUBJID"));
        md.setJoinType("left");
        return md;
    }


    private static Operation maxOverTheSiblingColumn()
    {
        // Fix #152 shape: the Operation names a DIFFERENT wildcard template from the Check
        // (`TRTxxPN` vs `TRTxxP`), which is exactly how five shipped rules came to read a column
        // named literally "AyIND".
        Operation op = new Operation();
        op.setId("$peak");
        op.setOperator("max");
        op.setName("TRTxxPN");
        return op;
    }


    /**
     * The authored-{@code Sensitivity} template: everything is declared, so nothing may be derived
     * and nothing may be dropped.
     */
    private static Rule authoredTemplate()
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(TEMPLATE_ID);
        core.setStatus("Published");
        core.setVersion("7");
        rule.setCore(core);
        rule.setCheck(CheckConditionLeaf.builder().name("TRTxxP").operator("non_empty").build());
        rule.setDescription("TRTxxP must agree with TRTxxPN");
        rule.setSensitivity(Sensitivity.DATASET);
        rule.setVariableUniverse(VariableUniverse.DATA);
        rule.setMatchDatasets(List.of(adslOnUsubjid()));
        rule.setGroupingVariables(List.of("USUBJID"));
        rule.setOperations(List.of(maxOverTheSiblingColumn()));

        VariableRequirement vars = new VariableRequirement();
        vars.setAll(List.of("ADSL.TRTxxPN"));
        Requirements req = new Requirements();
        req.setVariables(vars);
        rule.setRequirements(req);

        Outcome outcome = new Outcome();
        outcome.setMessage("TRTxxP is empty");
        outcome.setOutputVariables(List.of("TRTxxP"));
        rule.setOutcome(outcome);
        return rule;
    }


    private static Rule expandOnce(Rule template)
    {
        List<Rule> expanded = WildcardExpander.expand(template, oneTreatmentPeriod());
        assertEquals(1, expanded.size(),
                "one treatment period ⇒ exactly one expansion: " + expanded);
        Rule concrete = expanded.get(0);
        // ⛔ A load-errored expansion short-circuits deriveOmittedFields / deriveOutputVariables,
        // which would make several assertions below vacuously green. The fixture must be clean.
        assertNull(concrete.getLoadError(), concrete.getLoadError());
        return concrete;
    }


    /**
     * The generated rule's identity. {@code Id} is the deterministic UUID of the expanded Core id —
     * two runs over the same dataset must produce byte-identical rule ids, or every downstream
     * report keyed by rule id churns. {@code Status}/{@code Version} mark the rule as generated
     * rather than as the authored template it came from.
     */
    @Test
    @DisplayName("expansion identity: deterministic UUID, Status=Generated, Version=1")
    void expansionCarriesItsGeneratedIdentity()
    {
        Rule expanded = expandOnce(authoredTemplate());

        assertEquals(EXPANDED_ID, expanded.effectiveId());
        assertEquals(
                UUID.nameUUIDFromBytes(EXPANDED_ID.getBytes(StandardCharsets.UTF_8)).toString(),
                expanded.getId(),
                "the rule Id must be the deterministic UUID of the expanded Core id — a null or "
                        + "empty Id makes every expansion indistinguishable downstream");
        assertNotNull(expanded.getCore());
        assertEquals("Generated", expanded.getCore().getStatus(),
                "the expansion is generated, not the template's authored Published");
        assertEquals("1", expanded.getCore().getVersion(),
                "the expansion carries its own version, not the template's 7");
    }


    /**
     * The fields whose loss changes evaluation. {@code Variable_Universe} decides whether the rule
     * reads data columns or Define ItemDefs; {@code Match_Datasets} is the join without which a
     * cross-dataset Check sees nothing; the grouping keys decide the verdict granularity.
     */
    @Test
    @DisplayName("expansion carries Variable_Universe, Match_Datasets and the grouping keys")
    void expansionCarriesTheEvaluationShape()
    {
        Rule expanded = expandOnce(authoredTemplate());

        assertEquals(VariableUniverse.DATA, expanded.getVariableUniverse(),
                "dropping Variable_Universe silently flips the rule from Define metadata onto "
                        + "data columns");
        List<MatchDataset> joins = expanded.getMatchDatasets();
        assertNotNull(joins, "an expansion with no Match_Datasets cannot see the joined dataset");
        assertEquals(1, joins.size());
        assertEquals("ADSL", joins.get(0).getName());
        assertEquals(List.of("USUBJID"), joins.get(0).getKeys());
        assertEquals(List.of("USUBJID"), expanded.getGroupingVariables(),
                "the grouping keys decide the verdict granularity; losing them silently makes a "
                        + "grouped rule record-scoped");
    }


    /**
     * An <em>authored</em> {@code Sensitivity} rides onto the child untouched and the derivation
     * must NOT run: a derived value would overwrite the author's explicit choice, and the
     * derivation rationale is the observable proof that it stayed out of the way.
     */
    @Test
    @DisplayName("an authored Sensitivity survives and is not re-derived")
    void authoredSensitivityIsCarriedNotDerived()
    {
        Rule expanded = expandOnce(authoredTemplate());

        assertEquals(Sensitivity.DATASET, expanded.getSensitivity(),
                "the template authored Dataset; a dropped Sensitivity is re-derived to something "
                        + "else and the rule's verdict granularity silently changes");
        Map<String, String> rationale = expanded.getDerivationRationale();
        assertNull(rationale == null ? null : rationale.get("Sensitivity"),
                "nothing was omitted, so nothing may be derived — a Sensitivity rationale here "
                        + "means the authored value was lost and replaced: " + rationale);
    }


    /**
     * The mirror case: a template that omits {@code Sensitivity} must have it <b>derived</b> on the
     * expansion, because the expansion bypasses {@code RulePackageLoader} entirely and would
     * otherwise run with no sensitivity at all. The {@code Grouping:} block rides across at the
     * same time — the flat list is renamed a few lines up, so a template that migrated to the block
     * would lose its grouping entirely if this field were forgotten.
     */
    @Test
    @DisplayName("an omitted Sensitivity is derived on the expansion, and Grouping rides across")
    void omittedSensitivityIsDerivedAndGroupingRidesAcross()
    {
        Rule template = authoredTemplate();
        template.setSensitivity(null);
        template.setGroupingVariables(null);
        GroupingSpec grouping = new GroupingSpec();
        grouping.setVariables(List.of("USUBJID"));
        grouping.setKeepMissings(Boolean.TRUE);
        template.setGrouping(grouping);

        Rule expanded = expandOnce(template);

        assertNotNull(expanded.getSensitivity(),
                "the expansion bypasses the loader, so the generator must derive what the "
                        + "template omitted — a null Sensitivity here is a rule with no "
                        + "granularity");
        assertNotNull(expanded.getDerivationRationale(),
                "the derivation must actually have run; the rationale is its receipt");
        assertNotNull(expanded.getDerivationRationale().get("Sensitivity"),
                "…and it must be the Sensitivity derivation specifically: "
                        + expanded.getDerivationRationale());
        GroupingSpec expandedGrouping = expanded.getGrouping();
        assertNotNull(expandedGrouping, "the Grouping: block must ride onto the expansion");
        assertEquals(List.of("USUBJID"), expandedGrouping.getVariables());
        assertEquals(true, expandedGrouping.getKeepMissings());
    }


    /**
     * Fix #152 — an {@code Operations[]} entry naming a column template the Check does NOT name is
     * still bound from this expansion's tuple. Handing the template's operations across verbatim is
     * how five shipped rules came to read a column named literally {@code AyIND}: absent column ⇒
     * {@code max} yields nothing ⇒ the comparison fired on every populated row.
     */
    @Test
    @DisplayName("an Operation naming a different wildcard template is bound from the same tuple")
    void operationColumnNamesAreBoundFromTheTuple()
    {
        Rule expanded = expandOnce(authoredTemplate());

        List<Operation> ops = expanded.getOperations();
        assertNotNull(ops, "the operations must ride onto the expansion");
        assertEquals(1, ops.size(),
                "the expansion must keep its operation — without it the $peak reference resolves "
                        + "to nothing");
        assertEquals("TRT01PN", ops.get(0).getName(),
                "the operation's column is bound from THIS tuple (xx=01), not left as the "
                        + "template token");
        assertEquals("$peak", ops.get(0).getId(), "non-column positions are copied verbatim");
        assertEquals("max", ops.get(0).getOperator());
    }


    /**
     * The requirement gate runs BEFORE expansion, so it matches its entries literally: an entry
     * left as a template token skips the expanded rule on every dataset. The {@code Any} / {@code
     * None} facets the template did not author must stay <b>absent</b> rather than becoming empty
     * lists — an authored-but-empty facet is a different statement from an unauthored one.
     */
    @Test
    @DisplayName("Requirements.Variables is bound per tuple, and unauthored facets stay absent")
    void requirementVariablesAreBoundAndUnauthoredFacetsStayAbsent()
    {
        Rule expanded = expandOnce(authoredTemplate());

        Requirements req = expanded.getRequirements();
        assertNotNull(req);
        VariableRequirement vars = req.getVariables();
        assertNotNull(vars);
        assertEquals(List.of("ADSL.TRT01PN"), vars.getAll(),
                "the qualified entry binds the SAME xx the Check got");
        assertNull(vars.getAny(),
                "the template authored no Any facet; an empty list is an authored statement and "
                        + "would change what the gate asserts");
        assertNull(vars.getNone(), "…and likewise for None");
    }


    /**
     * EC-37 — an expansion gets the same effective-{@code Output_Variables} derivation a
     * loader-loaded rule gets, computed on the post-expansion concrete Check. Without it the
     * expansion runs with the derivation never having happened, so the finding rows carry a
     * different variable set from the identical hand-authored rule.
     */
    @Test
    @DisplayName("the effective Output_Variables derivation runs on the expansion")
    void effectiveOutputVariablesAreDerivedOnTheExpansion()
    {
        Rule expanded = expandOnce(authoredTemplate());

        assertEquals(List.of("TRT01P"), expanded.getOutcome().getOutputVariables(),
                "the authored token is resolved to this tuple's column");
        assertNotNull(expanded.getEffectiveOutputVariables(),
                "the EC-37 derivation must have run on the expansion — a null here means the "
                        + "expansion reports a different variable set from the same rule loaded "
                        + "concretely");
        assertEquals(List.of("TRT01P", "$peak", "TRT01PN"), expanded.getEffectiveOutputVariables(),
                "the derivation reads the POST-expansion Check and operations, so the operation "
                        + "result and its bound column join the authored column");
    }

}
