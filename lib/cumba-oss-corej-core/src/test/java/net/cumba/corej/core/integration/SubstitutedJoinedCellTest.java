package net.cumba.corej.core.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunnerCalls;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * The value path of a <b>dotted</b> scalar operand template: {@code ADSL.PH${APHASEN:%d}SDT} names,
 * per row, a column of the joined {@code ADSL} — the row's {@code APHASEN} picks which one — and
 * the Check compares that joined cell with the row's own {@code PHSDT}. The existence form of the
 * same template ({@code var_not_exists(ADSL.…)}) is {@link OperandTemplateScalarIntegrationTest};
 * this class covers the cell read — the row's driver picks the column, the join key picks the
 * joined row — and a row whose driver cannot be substituted.
 *
 * <p>
 * The rule is hand-written for the mechanism ({@link #PHASE_START_RULE}).
 * </p>
 */
class SubstitutedJoinedCellTest
{

    private static final String PHASE_START_RULE = """
            {"rules":{"P1":{"Core":{"Id":"T-PHASE-START"},"Sensitivity":"Record",
             "Scope":{"Domains":{"Include":["ADAE"]}},
             "Match_Datasets":[{"Name":"ADSL","Keys":["USUBJID"],"Join_Type":"left"}],
             "Requirements":{"Variables":{"All":["USUBJID"],
               "All_Or_None":[["USUBJID","ADSL.USUBJID"]]}},
             "Check":{"expression":"not empty(APHASEN) and PHSDT != ADSL.PH${APHASEN:%d}SDT"},
             "Outcome":{"Message":"m","Output_Variables":["APHASEN","PHSDT"]}}}}""";

    private static Rule rule() throws IOException
    {
        Rule rule = RulePackageLoader.loadFromString(PHASE_START_RULE).getRules().get("P1");
        assertNull(rule.getLoadError(), "the hand-written rule must load: " + rule.getLoadError());
        return rule;
    }


    @Test
    void theRowsDriverPicksTheJoinedColumnItIsComparedWith() throws IOException
    {
        IDataTable adsl = MockTable.of().name("ADSL").col("USUBJID", "S1", "S2")
                .col("PH1SDT", "2024-01-01", "2024-03-01").col("PH2SDT", "2024-02-01", "2024-04-01")
                .build();
        // row 0: phase 1, equals ADSL.PH1SDT -> no fire
        // row 1: phase 2, differs from ADSL.PH2SDT -> fires
        // row 2: phase 2 of S2, equals S2's ADSL.PH2SDT -> no fire (the join picks S2's row)
        // row 3: APHASEN missing -> the template names no column (a computed MIS), and the
        // guard keeps the row quiet; the run must neither error nor read a wrong column
        IDataTable adae = MockTable.of().name("ADAE").col("USUBJID", "S1", "S1", "S2", "S2")
                .colLong("APHASEN", 1L, 2L, 2L, null)
                .col("PHSDT", "2024-01-01", "2024-02-09", "2024-04-01", "2024-05-05").build();
        Map<String, IDataTable> tables = Map.of("ADSL", adsl, "ADAE", adae);
        // An inventory-aware resolver, as production's and the scenario harness's are: the
        // All_Or_None key group naming ADSL is decidable only against the study's inventory.
        DatasetResolver resolver = new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String name)
            {
                return tables.get(name);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return tables.keySet();
            }
        };

        RuleExecutionResult r = RuleRunnerCalls.execute(rule(), adae, resolver);
        assertEquals(RuleExecutionStatus.EXECUTED, r.getStatus(), r.getStatusMessage());
        List<Violation> v = r.getViolations();
        assertEquals(1, v.size(), "only row 1 differs from the joined cell its driver names");
        assertEquals(1L, v.get(0).getRow());
        assertEquals("2024-02-09", v.get(0).getValues().get("PHSDT"));
    }
}
