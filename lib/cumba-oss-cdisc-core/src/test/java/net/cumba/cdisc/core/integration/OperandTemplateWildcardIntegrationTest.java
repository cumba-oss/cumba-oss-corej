package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import com.fasterxml.jackson.databind.node.TextNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.MatchDataset;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Fix #37 — integration test for the Wildcard form of operand-template substitution.
 *
 * <p>
 * Synthetic rule:
 * </p>
 *
 * <pre>
 *   all:
 *     - { name: "PHSDT", operator: "non_empty" }
 *     - { name: "PHSDT", operator: "is_not_contained_by", value: "ADSL.PH${*}SDT" }
 * </pre>
 *
 * paired with {@code Match_Datasets: [ADSL/USUBJID]}. The first leaf is the non-empty guard so
 * <p>
 * missing PHSDT rows don't fire.
 * </p>
 *
 * <p>
 * ADSL has PH1SDT="2024-01-01", PH2SDT="2024-02-01". A row with PHSDT="2024-02-01" does not fire
 * (in set); PHSDT="1999-12-31" fires (not in set); PHSDT missing does not fire (guarded). When ADSL
 * has no matching PH&lt;digit&gt;SDT columns the rule fires on every populated PHSDT row — loud
 * failure mode.
 * </p>
 */
class OperandTemplateWildcardIntegrationTest
{

    private static Rule wildcardRule()
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-SUBST-WILDCARD");
        rule.setCore(core);
        rule.setSensitivity(Sensitivity.RECORD);
        MatchDataset md = new MatchDataset();
        md.setName("ADSL");
        md.setKeys(List.of("USUBJID"));
        rule.setMatchDatasets(List.of(md));
        CheckConditionLeaf nonEmptyGuard = CheckConditionLeaf.builder().name("PHSDT")
                .operator("non_empty").build();
        CheckConditionLeaf isNotContained = CheckConditionLeaf.builder().name("PHSDT")
                .operator("is_not_contained_by").value(TextNode.valueOf("ADSL.PH${*}SDT")).build();
        CheckConditionAll all = new CheckConditionAll(List.of(nonEmptyGuard, isNotContained));
        rule.setCheck(all);
        net.cumba.cdisc.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    @Test
    void wildcard_inSet_doesNotFire_outOfSetFires_missingGuarded()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("PH1SDT", "2024-01-01")
                .col("PH2SDT", "2024-02-01").name("ADSL").build();
        // Row 0: PHSDT in set → no fire.
        // Row 1: PHSDT not in set → fires.
        // Row 2: PHSDT missing → guarded by non_empty → no fire.
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1", "S1")
                .col("PHSDT", "2024-02-01", "1999-12-31", null).name("ADAE").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("ADAE", adae);
        DatasetResolver resolver = tables::get;

        RuleExecutionResult result = RuleRunner.execute(wildcardRule(), adae, resolver);
        assertEquals(1, result.getViolationCount(), "only the PHSDT=1999-12-31 row fires");
        assertEquals(1L, result.getViolations().get(0).getRow());
    }


    @Test
    void wildcard_emptyMatch_firesOnEveryPopulatedRow()
    {
        // ADSL with no matching PH<digit>SDT columns at all.
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("AGE", "42").name("ADSL").build();
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1")
                .col("PHSDT", "2024-02-01", "1999-12-31").name("ADAE").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("ADAE", adae);
        DatasetResolver resolver = tables::get;

        RuleExecutionResult result = RuleRunner.execute(wildcardRule(), adae, resolver);
        assertEquals(2, result.getViolationCount(),
                "both populated rows fire (loud failure mode for empty wildcard match)");
    }
}
