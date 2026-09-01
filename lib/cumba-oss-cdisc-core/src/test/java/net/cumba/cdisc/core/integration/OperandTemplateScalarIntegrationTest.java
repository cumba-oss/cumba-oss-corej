package net.cumba.cdisc.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.cumba.cdisc.core.exec.DatasetResolver;
import net.cumba.cdisc.core.exec.MockTable;
import net.cumba.cdisc.core.exec.RuleExecutionResult;
import net.cumba.cdisc.core.exec.RuleRunner;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.MatchDataset;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Fix #37 — integration test for the Scalar form of operand-template substitution.
 *
 * <p>
 * Synthetic rule:
 *
 * <pre>
 * {name: "AP${APERIOD:%02d}SDT", operator: "var_exists"}
 * </pre>
 *
 * paired with {@code Match_Datasets: [ADSL/USUBJID]}. ADSL has columns AP01SDT and AP02SDT but not
 * AP03SDT. The primary table has rows with APERIOD = 1, 2, 3. Expected: row 1 → no fire (column
 * exists); row 2 → no fire; row 3 → fire (column missing).
 * </p>
 *
 * <p>
 * The leaf uses {@code not_exists} in the rule body so the test exercises "fire when a row's
 * substituted column is absent". {@code exists} would invert the assertions but is otherwise
 * equivalent.
 * </p>
 */
class OperandTemplateScalarIntegrationTest
{

    private static Rule notExistsRule()
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-SUBST-SCALAR");
        rule.setCore(core);
        rule.setSensitivity(Sensitivity.RECORD);
        // Cross-dataset Match_Datasets so a JoinLookup is built (needed for the
        // dotted-name lookup the Scalar substitution would otherwise miss).
        MatchDataset md = new MatchDataset();
        md.setName("ADSL");
        md.setKeys(List.of("USUBJID"));
        rule.setMatchDatasets(List.of(md));
        // Substitute against primary-row APERIOD; the schema check is local to
        // the primary dataset because the operand has no foreign-dataset prefix.
        CheckConditionLeaf leaf = CheckConditionLeaf.builder().name("ADSL.AP${APERIOD:%02d}SDT")
                .operator("var_not_exists").build();
        rule.setCheck(leaf);
        net.cumba.cdisc.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    @Test
    void scalarSubstitution_perRowExistence()
    {
        IDataTable adsl = MockTable.of().col("USUBJID", "S1").col("AP01SDT", "2024-01-01")
                .col("AP02SDT", "2024-02-01").name("ADSL").build();
        // Primary dataset rows — APERIOD drives the substituted column name.
        IDataTable adae = MockTable.of().col("USUBJID", "S1", "S1", "S1")
                .colLong("APERIOD", 1L, 2L, 3L).name("ADAE").build();
        Map<String, IDataTable> tables = new HashMap<>();
        tables.put("ADSL", adsl);
        tables.put("ADAE", adae);
        DatasetResolver resolver = tables::get;

        Rule rule = notExistsRule();
        RuleExecutionResult result = RuleRunner.execute(rule, adae, resolver);
        assertEquals(1, result.getViolationCount(),
                "only the APERIOD=3 row fires (AP03SDT does not exist in ADSL)");
        assertEquals(2L, result.getViolations().get(0).getRow(),
                "the firing row is row index 2 (APERIOD=3)");
    }
}
