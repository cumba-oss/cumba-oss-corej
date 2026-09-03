package net.cumba.corej.core.gen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import java.util.List;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionLeaf;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@link WildcardExpander#tryExpand(Rule, net.cumba.datatable.DataTableMeta)}'s three
 * outcomes (Expanded, NotApplicable, NoMatch) and the {@code containsWildcards} predicate edge
 * cases. Complements {@link WildcardExpanderTest}.
 */
class WildcardExpanderTryExpandTest
{

    @Test
    void tryExpand_nullCheck_returnsNotApplicable()
    {
        Rule rule = newRule("R1");
        rule.setCheck(null);

        IDataTable table = MockTable.withColumns("X");
        WildcardExpander.ExpansionResult result = WildcardExpander.tryExpand(rule,
                table.getMetaData());
        assertInstanceOf(WildcardExpander.ExpansionResult.NotApplicable.class, result);
    }


    @Test
    void tryExpand_noWildcardNames_returnsNotApplicable()
    {
        Rule rule = newRule("R2");
        rule.setCheck(CheckConditionLeaf.builder().name("USUBJID").operator("non_empty").build());

        IDataTable table = MockTable.withColumns("USUBJID");
        WildcardExpander.ExpansionResult result = WildcardExpander.tryExpand(rule,
                table.getMetaData());
        assertInstanceOf(WildcardExpander.ExpansionResult.NotApplicable.class, result);
    }


    @Test
    void tryExpand_matchedWildcard_returnsExpanded()
    {
        Rule rule = newRule("R3");
        rule.setCheck(new CheckConditionAll(
                List.of(CheckConditionLeaf.builder().name("*FL").operator("non_empty").build())));
        Outcome out = new Outcome();
        out.setMessage("v");
        out.setOutputVariables(List.of("*FL"));
        rule.setOutcome(out);

        IDataTable table = MockTable.withColumns("SAFFL", "ITTFL");
        WildcardExpander.ExpansionResult result = WildcardExpander.tryExpand(rule,
                table.getMetaData());
        WildcardExpander.ExpansionResult.Expanded ex = assertInstanceOf(
                WildcardExpander.ExpansionResult.Expanded.class, result);
        assertEquals(2, ex.rules().size());
    }


    @Test
    void tryExpand_unmatchedWildcard_returnsNoMatch()
    {
        Rule rule = newRule("R4");
        rule.setCheck(new CheckConditionAll(
                List.of(CheckConditionLeaf.builder().name("*FL").operator("non_empty").build())));

        // Columns that don't end in FL → no match for *FL.
        IDataTable table = MockTable.withColumns("STUDYID", "USUBJID", "AGE");
        WildcardExpander.ExpansionResult result = WildcardExpander.tryExpand(rule,
                table.getMetaData());
        WildcardExpander.ExpansionResult.NoMatch nm = assertInstanceOf(
                WildcardExpander.ExpansionResult.NoMatch.class, result);
        assertNotNull(nm.reason());
        assertTrue(nm.reason().contains("Template did not match"));
    }


    @Test
    void tryExpand_lowercaseLiteralOnly_returnsNotApplicable()
    {
        // Names like "Char" or "Screen Failure" are flagged as wildcard candidates by
        // collectWildcardNames but every lowercase run parses as "unknown marker → literal".
        // tryExpand walks each WildcardPattern.groupNames() and finds them empty → NotApplicable.
        Rule rule = newRule("R5");
        rule.setCheck(CheckConditionLeaf.builder().name("variable_data_type").operator("equal_to")
                .value(new TextNode("Char")).build());

        IDataTable table = MockTable.withColumns("X");
        WildcardExpander.ExpansionResult result = WildcardExpander.tryExpand(rule,
                table.getMetaData());
        // The leaf name starts with lowercase, so it is excluded from wildcard candidates,
        // and the literal text value supplies no real wildcard tokens either way.
        assertInstanceOf(WildcardExpander.ExpansionResult.NotApplicable.class, result);
    }


    @Test
    void containsWildcards_arrayValueWithWildcard()
    {
        // CheckConditionLeaf with array-valued wildcard reference triggers the
        // "leaf.getValue().isArray()" branch in collectWildcardNamesRecursive.
        Rule rule = newRule("R6");
        com.fasterxml.jackson.databind.node.ArrayNode arr = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .arrayNode();
        arr.add("USUBJID");
        arr.add("*FL");
        rule.setCheck(CheckConditionLeaf.builder().name("AGE").operator("is_contained_by")
                .value(arr).build());

        assertTrue(WildcardExpander.containsWildcards(rule));
    }


    @Test
    void containsWildcards_arrayValueAllLiteral()
    {
        Rule rule = newRule("R7");
        com.fasterxml.jackson.databind.node.ArrayNode arr = com.fasterxml.jackson.databind.node.JsonNodeFactory.instance
                .arrayNode();
        arr.add("M");
        arr.add("F");
        rule.setCheck(CheckConditionLeaf.builder().name("SEX").operator("is_contained_by")
                .value(arr).build());

        assertFalse(WildcardExpander.containsWildcards(rule));
    }


    @Test
    void containsWildcards_numericValue_ignored()
    {
        // numeric value in leaf has no wildcards regardless
        Rule rule = newRule("R8");
        rule.setCheck(CheckConditionLeaf.builder().name("AGE").operator("greater_than")
                .value(new IntNode(18)).build());
        assertFalse(WildcardExpander.containsWildcards(rule));
    }


    @Test
    void containsWildcards_nullCheck_returnsFalse()
    {
        Rule rule = newRule("R-null-check");
        rule.setCheck(null);
        assertFalse(WildcardExpander.containsWildcards(rule));
    }


    @Test
    void expand_returnsEmpty_whenNoWildcards()
    {
        // expand() short-circuits on collectWildcardNames returning empty (concrete name).
        Rule rule = newRule("R9");
        rule.setCheck(CheckConditionLeaf.builder().name("USUBJID").operator("non_empty").build());

        IDataTable table = MockTable.withColumns("USUBJID", "AGE");
        List<Rule> expanded = WildcardExpander.expand(rule, table.getMetaData());
        assertTrue(expanded.isEmpty());
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------


    private static Rule newRule(String coreId)
    {
        Rule rule = new Rule();
        rule.setId(java.util.UUID.randomUUID().toString());
        RuleCore core = new RuleCore();
        core.setId(coreId);
        core.setStatus("Published");
        core.setVersion("1");
        rule.setCore(core);
        rule.setDescription("Test rule");
        rule.setSensitivity(Sensitivity.RECORD);
        return rule;
    }
}
