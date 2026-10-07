package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-present-empty-column-fold} §4.4 — the two scan arms of the column-status functions
 * paired with the table arm on the same cells, so they cannot drift: the dotted (joined) arm
 * ({@code ExprCompiler.joinedColumnContent}) and the SUPP-qualifier arm
 * ({@code OperatorRegistry.columnContentOf}), each against the evaluation table's
 * {@code getColumnContent}.
 */
class ColumnStatusScanArmsTest
{

    private static Rule joinedRule(String check) throws Exception
    {
        String json = "{\"rules\":{\"T\":{\"Core\":{\"Id\":\"C7-ARM\"},\"Sensitivity\":\"Record\","
                + "\"Requirements\":{\"Variables\":{\"All\":[\"USUBJID\"],"
                + "\"All_Or_None\":[[\"USUBJID\",\"DM.USUBJID\"]]}},"
                + "\"Match_Datasets\":[{\"Name\":\"DM\",\"Keys\":[\"USUBJID\"],"
                + "\"Join_Type\":\"left\"}]," + "\"Check\":{\"expression\":\"" + check + "\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("T");
        assertNull(rule.getLoadError(), rule.getLoadError());
        return rule;
    }


    private static RealTableFixture aeWith(String column, String... values)
    {
        return RealTableFixture.of("AE").str("STUDYID", "S1", "S1").str("DOMAIN", "AE", "AE")
                .str("USUBJID", "01", "02").lng("AESEQ", 1L, 1L).str(column, values);
    }


    @Test
    void theDottedArmAgreesWithTheTableArm() throws Exception
    {
        // The same cells read as DM.V through the join and as V on the primary.
        String[][] cases =
        {
                {
                        "", ""
                },
                {
                        "a", "a"
                },
                {
                        "a", "b"
                },
        };
        for (String[] cells : cases)
        {
            for (String fn : new String[]
            {
                    "var_is_empty", "var_is_single_value", "var_is_all_missing"
            })
            {
                IDataTable dm = RealTableFixture.of("DM").str("STUDYID", "S1", "S1")
                        .str("USUBJID", "01", "02").str("V", cells).build();
                IDataTable aeTable = aeWith("V", cells).build();
                IDataTable aeBare = aeWith("OTHER", "x", "y").build();
                RuleExecutionResult dotted = RuleRunnerCalls.execute(joinedRule(fn + "(DM.V)"),
                        aeBare, RealTables.inventoryOf(dm, aeBare));
                RuleExecutionResult table = RuleRunnerCalls.execute(joinedRule(fn + "(V)"), aeTable,
                        RealTables.inventoryOf(dm, aeTable));
                assertEquals(table.getViolationCount(), dotted.getViolationCount(), fn + " over "
                        + String.join(",", cells) + ": dotted=" + dotted + " table=" + table);
            }
        }
    }


    @Test
    void theSuppQualifierArmAgreesWithTheTableArm() throws Exception
    {
        String[][] cases =
        {
                {
                        "", ""
                },
                {
                        "Y", "Y"
                },
                {
                        "Y", "N"
                },
        };
        for (String[] cells : cases)
        {
            for (String fn : new String[]
            {
                    "var_is_empty", "var_is_single_value", "var_is_all_missing"
            })
            {
                String json = "{\"rules\":{\"T\":{\"Core\":{\"Id\":\"C7-SUPPARM\"},"
                        + "\"Sensitivity\":\"Record\","
                        + "\"Requirements\":{\"Variables\":{\"All\":[\"USUBJID\"]}},"
                        + "\"Check\":{\"expression\":\"" + fn + "(AETRTEM)\"},"
                        + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}}}";
                Rule rule = RulePackageLoader.loadFromString(json).getRules().get("T");
                assertNull(rule.getLoadError(), rule.getLoadError());
                IDataTable ae = RealTableFixture.of("AE").str("STUDYID", "S1", "S1")
                        .str("DOMAIN", "AE", "AE").str("USUBJID", "01", "01").lng("AESEQ", 1L, 2L)
                        .build();
                IDataTable suppae = RealTableFixture.of("SUPPAE").str("STUDYID", "S1", "S1")
                        .str("RDOMAIN", "AE", "AE").str("USUBJID", "01", "01")
                        .str("IDVAR", "AESEQ", "AESEQ").str("IDVARVAL", "1", "2")
                        .str("QNAM", "AETRTEM", "AETRTEM").str("QVAL", cells).build();
                IDataTable aeDirect = RealTableFixture.of("AE").str("STUDYID", "S1", "S1")
                        .str("DOMAIN", "AE", "AE").str("USUBJID", "01", "01").lng("AESEQ", 1L, 2L)
                        .str("AETRTEM", cells).build();
                RuleExecutionResult supp = RuleRunnerCalls.execute(rule, ae,
                        RealTables.inventoryOf(suppae, ae));
                RuleExecutionResult direct = RuleRunnerCalls.execute(rule, aeDirect,
                        RealTables.inventoryOf(aeDirect));
                assertEquals(direct.getViolationCount(), supp.getViolationCount(), fn + " over "
                        + String.join(",", cells) + ": supp=" + supp + " direct=" + direct);
            }
        }
    }
}
