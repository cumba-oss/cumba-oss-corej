package net.cumba.cdisc.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.TextNode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Fix #152 — engine-gap <b>ADaM-G6</b>: a wildcard token inside an {@code Operations[]} entry.
 * <p>
 * {@code expandRule} used to hand the template's operations to the expanded rule <em>verbatim</em>
 * ({@code rule.setOperations(template.getOperations())}) while the Check, the Scope, the Outcome
 * and the Description were all substituted. The five shipped rules
 * {@code CDISC-AD0353/0354/0702/0703/0790} pair a Check over {@code ByIND} with
 * {@code max("AyIND", filter=ABLFL="Y", group=[…])}, so an expansion that bound {@code ByIND →
 * B1IND} left the operation reading a column named literally {@code AyIND}. That column is absent
 * from every dataset, {@code max} over an absent column yields nothing, and the dependent
 * {@code not_equal_to $baseline} comparison therefore fired on <b>every populated row</b> — a live
 * over-report, not a silent skip.
 * </p>
 * <p>
 * The operation-side template is normally <em>not</em> a key of the Check-derived substitution map
 * (its keys come from {@code collectWildcardNames}, which walks the Check tree alone), so the
 * binding has to be derived from the expansion <b>tuple</b> — exactly the mechanism Fix #124 uses
 * for a qualified {@code Scope.Variables} entry. The pairing assertion below is the point: the
 * {@code y=2} rule must read {@code A2IND}, never {@code A1IND}.
 * </p>
 */
class WildcardExpanderOperationNamesTest
{

    /** The AD0353 shape: a Check over {@code ByIND}, an operation over {@code AyIND}. */
    private static Rule baselineIndicatorTemplate()
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-G6");
        rule.setCore(core);
        rule.setDescription("ByIND is not equal to AyIND where ABLFL is equal to Y");

        rule.setCheck(new CheckConditionAll(
                List.of(CheckConditionLeaf.builder().name("ByIND").operator("non_empty").build(),
                        CheckConditionLeaf.builder().name("ByIND").operator("not_equal_to")
                                .value(new TextNode("$baseline_ayind")).build())));

        Operation op = new Operation();
        op.setId("$baseline_ayind");
        op.setOperator("max");
        op.setName("AyIND");
        Map<String, Object> filter = new LinkedHashMap<>();
        filter.put("ABLFL", "Y");
        op.setFilter(filter);
        op.setGroup(List.of("USUBJID", "PARAMCD"));
        rule.setOperations(List.of(op));

        Outcome outcome = new Outcome();
        outcome.setMessage("ByIND is not equal to AyIND");
        outcome.setOutputVariables(new ArrayList<>(List.of("PARAMCD", "AyIND", "ByIND")));
        rule.setOutcome(outcome);
        return rule;
    }


    private static DataTableMeta metaWith(String... columns)
    {
        MockTable b = MockTable.of().name("ADQS");
        for (String c : columns)
        {
            b = b.col(c, "x");
        }
        return b.build().getMetaData();
    }


    private static Operation onlyOperation(Rule rule)
    {
        List<Operation> ops = rule.getOperations();
        assertNotNull(ops, "the expanded rule kept its operations");
        assertEquals(1, ops.size());
        return ops.get(0);
    }


    @Test
    void operationNameIsBoundToTheSameTupleAsTheCheck()
    {
        List<Rule> expanded = WildcardExpander.expand(baselineIndicatorTemplate(),
                metaWith("USUBJID", "PARAMCD", "ABLFL", "A1IND", "B1IND"));

        assertEquals(1, expanded.size(), "one y-binding present in the metadata");
        assertEquals("A1IND", onlyOperation(expanded.get(0)).getName(),
                "the operation's AyIND must follow the Check's ByIND onto y=1");
    }


    @Test
    void everyTupleGetsItsOwnOperationBinding()
    {
        List<Rule> expanded = WildcardExpander.expand(baselineIndicatorTemplate(),
                metaWith("USUBJID", "PARAMCD", "ABLFL", "A1IND", "B1IND", "A2IND", "B2IND"));

        assertEquals(2, expanded.size(), "y=1 and y=2");
        Map<String, String> checkToOp = new LinkedHashMap<>();
        for (Rule r : expanded)
        {
            CheckConditionAll all = (CheckConditionAll) r.getCheck();
            assertNotNull(all);
            CheckConditionLeaf first = (CheckConditionLeaf) all.getConditions().get(0);
            checkToOp.put(first.getName(), onlyOperation(r).getName());
        }
        assertEquals(Map.of("B1IND", "A1IND", "B2IND", "A2IND"), checkToOp,
                "each expansion pairs its own y — B2IND must never be compared against A1IND");
    }


    @Test
    void nonColumnPositionsAreCarriedOverUnchanged()
    {
        Rule expanded = WildcardExpander.expand(baselineIndicatorTemplate(),
                metaWith("USUBJID", "PARAMCD", "ABLFL", "A1IND", "B1IND")).get(0);
        Operation op = onlyOperation(expanded);

        assertEquals("$baseline_ayind", op.getId(), "the operation id is a $-ref, not a column");
        assertEquals("max", op.getOperator());
        assertEquals(List.of("USUBJID", "PARAMCD"), op.getGroup(),
                "concrete group columns pass through");
        assertNotNull(op.getFilter());
        assertEquals(Map.of("ABLFL", "Y"), op.getFilter(),
                "a filter key is a column position but ABLFL is concrete; the value is data");
    }


    @Test
    void outputVariablesAndTextFollowTheOperationBinding()
    {
        Rule expanded = WildcardExpander.expand(baselineIndicatorTemplate(),
                metaWith("USUBJID", "PARAMCD", "ABLFL", "A1IND", "B1IND")).get(0);

        assertNotNull(expanded.getOutcome());
        List<String> outputs = expanded.getOutcome().getOutputVariables();
        assertNotNull(outputs);
        assertTrue(outputs.contains("A1IND"),
                "AD0790 ships AyIND in Output_Variables; it is a column name and must expand — "
                        + "saw " + outputs);
        assertTrue(outputs.contains("B1IND"), "saw " + outputs);
        assertEquals("B1IND is not equal to A1IND", expanded.getOutcome().getMessage(),
                "the message names the same two concrete columns");
        assertEquals("B1IND is not equal to A1IND where ABLFL is equal to Y",
                expanded.getDescription());
    }


    @Test
    void aTemplateWithoutOperationWildcardsKeepsSharingTheOperationList()
    {
        Rule template = baselineIndicatorTemplate();
        List<Operation> ops = template.getOperations();
        assertNotNull(ops);
        // Concrete operation name => the rewrite is identity => the shared list must come back.
        ops.get(0).setName("AVAL");
        Outcome outcome = template.getOutcome();
        assertNotNull(outcome);
        outcome.setOutputVariables(List.of("PARAMCD"));

        Rule expanded = WildcardExpander
                .expand(template, metaWith("USUBJID", "PARAMCD", "ABLFL", "AVAL", "B1IND")).get(0);

        assertSame(ops, expanded.getOperations(),
                "an all-identity rewrite must hand back the template's own list, not a copy");
    }


    @Test
    void theTemplateOperationIsNeverMutated()
    {
        Rule template = baselineIndicatorTemplate();
        List<Operation> ops = template.getOperations();
        assertNotNull(ops);

        WildcardExpander.expand(template,
                metaWith("USUBJID", "PARAMCD", "ABLFL", "A1IND", "B1IND", "A2IND", "B2IND"));

        assertEquals("AyIND", ops.get(0).getName(),
                "expansion must copy: two tuples sharing one Operation instance would make the "
                        + "second expansion overwrite the first");
    }

}
