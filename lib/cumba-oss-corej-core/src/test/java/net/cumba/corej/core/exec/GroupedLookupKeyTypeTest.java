package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.CheckToExpr;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * Q2 of {@code PLAN-grouping-key-identity} (owner 2026-09-27: <i>"on relrec/ supp this might need
 * to be supported and we have a general flag for text merge. Beside this, I agree to error
 * out."</i>; register {@code D4-R1/R2}: <i>"I do not want a silent mismatch."</i>).
 *
 * <p>
 * A grouped operation's result is looked up by the typed key identity, so a group column that is
 * Char where the operation grouped and Num where the result is read would match no row — silently.
 * The rule ERRORs instead, naming both columns and kinds; asserted at {@link RuleRunner} level (the
 * rule's status and message), not only as a unit exception. Never an error: {@code LONG} against
 * {@code DOUBLE} (both numeric), a kind the gate does not classify ({@code D4-R6a}: the all-NA
 * {@code .rds} column arrives as {@code BOOLEAN}), and the text-carried family ({@code SUPP--},
 * {@code D4-R5}).
 * </p>
 */
class GroupedLookupKeyTypeTest
{

    private static Rule rule(String aCheck, List<String> aOutputs, Operation... aOps)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("TEST-P16-Q2");
        rule.setCore(core);
        rule.setScope(new Scope());
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("grouped lookup");
        outcome.setOutputVariables(aOutputs);
        rule.setOutcome(outcome);
        rule.setOperations(List.of(aOps));
        CheckConditionExpression check = new CheckConditionExpression(
                CheckExpressionParser.parse(aCheck), aCheck);
        rule.setCheck(check);
        rule.setCheckExpr(CheckToExpr.toExpr(check));
        return rule;
    }


    private static Operation countIn(String aDomain, String aGroup)
    {
        Operation op = new Operation();
        op.setId("$n");
        op.setOperator("record_count");
        op.setDomain(aDomain);
        op.setGroup(List.of(aGroup));
        return op;
    }


    private static RuleExecutionResult run(Rule aRule, IDataTable aEval, IDataTable... aOthers)
    {
        IDataTable[] all = Arrays.copyOf(aOthers, aOthers.length + 1);
        all[aOthers.length] = aEval;
        return RuleRunnerCalls.execute(aRule, aEval, RealTables.inventoryOf(all));
    }


    /** A one-column {@code BOOLEAN} table whose every cell is missing — the all-NA R shape. */
    private static IDataTable allNaBoolean(String aName, String aColumn, int aRows)
    {
        CachedDataTableColumn col = new CachedDataTableColumn(0, DataValueType.BOOLEAN);
        for (int i = 0; i < aRows; i++)
        {
            col.addElement(null);
        }
        col.complete();
        DataTableMeta meta = DataTableMeta.builder().name(aName).label(aName)
                .setColumns(DataTableColumnMeta.builder().index(0).name(aColumn).label(aColumn)
                        .type(DataValueType.BOOLEAN).build())
                .rowCount(aRows).totalRowCount(aRows).build();
        return new ColumnCachedDataTable(meta, col);
    }


    @Test
    void aCharNumKeyPairAcrossTablesErrorsTheRule()
    {
        IDataTable aeTable = RealTables.of("AE").str("USUBJID", "1", "2").build();
        IDataTable dm = RealTables.of("DM").dbl("USUBJID", 1.0, 1.0).build();
        RuleExecutionResult res = run(rule("$n > 1", List.of("USUBJID"), countIn("DM", "USUBJID")),
                aeTable, dm);
        assertEquals(RuleExecutionStatus.ERROR, res.getStatus(), res.getStatusMessage());
        String msg = String.valueOf(res.getStatusMessage());
        assertTrue(msg.startsWith("Grouped lookup: key column USUBJID is Numeric in DM"), msg);
        assertTrue(msg.contains("and Character in AE, where the result is read"), msg);
        assertEquals(msg, res.getViolations().get(0).getValues().get("__error__"));
    }


    /**
     * The check never reads {@code $n}: only the report's {@code Output_Variables} does, per
     * violating row. The type check still runs — once, where the result is bound to the rule's
     * table — and the rule ERRORs.
     */
    @Test
    void anOutputOnlyReadErrorsTheRuleToo()
    {
        IDataTable aeTable = RealTables.of("AE").str("USUBJID", "1", "2").build();
        IDataTable dm = RealTables.of("DM").dbl("USUBJID", 1.0, 2.0).build();
        RuleExecutionResult res = run(
                rule("not empty(USUBJID)", List.of("USUBJID", "$n"), countIn("DM", "USUBJID")),
                aeTable, dm);
        assertEquals(RuleExecutionStatus.ERROR, res.getStatus(), res.getStatusMessage());
        assertTrue(String.valueOf(res.getStatusMessage()).startsWith("Grouped lookup:"),
                res.getStatusMessage());
    }


    /**
     * The check runs where a result is READ, against the table the lookup reads (review round 1,
     * L3). A grouped result nothing reads is never checked — it used to be checked eagerly, against
     * the rule's table, wherever the operation was materialised.
     */
    @Test
    void anUnreadGroupedResultIsNeverChecked()
    {
        IDataTable aeTable = RealTables.of("AE").str("USUBJID", "1", "2").build();
        IDataTable dm = RealTables.of("DM").dbl("USUBJID", 1.0, 2.0).build();
        RuleExecutionResult res = run(
                rule("not empty(USUBJID)", List.of("USUBJID"), countIn("DM", "USUBJID")), aeTable,
                dm);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        assertEquals(2, res.getViolations().size());
    }


    /**
     * The binding is memoised per (result, TABLE): one context table passing binds nothing else.
     */
    @Test
    void theBindingIsPerTable()
    {
        IDataTable numTable = RealTables.of("DM").dbl("USUBJID", 1.0).build();
        IDataTable charTable = RealTables.of("AE").str("USUBJID", "1").build();
        GroupedResult grouped = new GroupedResult(List.of("USUBJID"), Map.of(), null,
                GroupedResult.KeyMode.IDENTITY,
                GroupedResult.KeyTypes.of(numTable, List.of("USUBJID")));
        EvaluationContext onNum = EvaluationContext.builder().table(numTable).build();
        onNum.requireCompatibleGroupedKeys(grouped);
        onNum.requireCompatibleGroupedKeys(grouped);
        assertEquals(numTable, onNum.getKeyCheckedGroupedResults().get(grouped));
        EvaluationContext onChar = onNum.toBuilder().table(charTable).build();
        assertThrows(JoinKeyTypeMismatchException.class,
                () -> onChar.requireCompatibleGroupedKeys(grouped));
    }


    @Test
    void longAgainstDoubleIsOneKind()
    {
        IDataTable aeTable = RealTables.of("AE").dbl("K", 5.0, 6.0).build();
        IDataTable dm = RealTables.of("DM").lng("K", 5L, 5L).build();
        RuleExecutionResult res = run(rule("$n > 1", List.of("K"), countIn("DM", "K")), aeTable,
                dm);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        // and 5L meets 5.0: the row keyed 5.0 reads the count 2 and fires
        assertEquals(1, res.getViolations().size());
    }


    @Test
    void anUnclassifiedKindIsNeverAMismatch()
    {
        IDataTable aeTable = RealTables.of("AE").str("K", "A", "B").build();
        IDataTable dm = allNaBoolean("DM", "K", 2);
        RuleExecutionResult res = run(rule("$n > 5", List.of("K"), countIn("DM", "K")), aeTable,
                dm);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
    }


    @Test
    void theSuppJoinIsATextJoinAndNeverChecked()
    {
        IDataTable aeTable = RealTables.of("AE").str("USUBJID", "S1", "S1").dbl("AESEQ", 1.0, 2.0)
                .build();
        IDataTable supp = RealTables.of("SUPPAE").str("USUBJID", "S1").str("RDOMAIN", "AE")
                .str("IDVAR", "AESEQ").str("IDVARVAL", "1").str("QNAM", "AETRTEM").str("QVAL", "Y")
                .build();
        Operation op = new Operation();
        op.setId("$v");
        op.setOperator("supp_qnam_value");
        op.setDomain("SUPPAE");
        op.setKeyValue("AETRTEM");
        RuleExecutionResult res = run(rule("$v == \"Y\"", List.of("AESEQ"), op), aeTable, supp);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        // IDVARVAL "1" (text) found AESEQ 1 (a number): row 1 fires
        assertEquals(1, res.getViolations().size());
    }

    // ---------------------------------------------------------------- the unit contract


    @Test
    void requireCompatibleKeysComparesPositionallyAndSkipsWhatItCannotJudge()
    {
        IDataTable charTable = RealTables.of("AE").str("USUBJID", "1").str("X", "a").build();
        IDataTable numTable = RealTables.of("DM").dbl("USUBJID", 1.0).lng("X", 2L).build();
        GroupedResult grouped = new GroupedResult(List.of("USUBJID"), Map.of(), null,
                GroupedResult.KeyMode.IDENTITY,
                GroupedResult.KeyTypes.of(numTable, List.of("USUBJID")));
        JoinKeyTypeMismatchException ex = assertThrows(JoinKeyTypeMismatchException.class,
                () -> grouped.requireCompatibleKeys(charTable));
        assertTrue(ex.getMessage().contains("USUBJID is Numeric in DM"), ex.getMessage());
        assertDoesNotThrow(() -> grouped.requireCompatibleKeys(numTable));
        // a column the evaluated table lacks has no kind to disagree with
        assertDoesNotThrow(() -> grouped
                .requireCompatibleKeys(RealTables.of("TS").str("TSPARMCD", "A").build()));
        // TEXT mode, or unknown key types: never checked
        assertDoesNotThrow(() -> new GroupedResult(List.of("USUBJID"), Map.of(), null,
                GroupedResult.KeyMode.TEXT, GroupedResult.KeyTypes.of(numTable, List.of("USUBJID")))
                        .requireCompatibleKeys(charTable));
        assertDoesNotThrow(() -> new GroupedResult(List.of("USUBJID"), Map.of())
                .requireCompatibleKeys(charTable));
        assertEquals(Arrays.asList(ColumnTypeGate.Kind.NUMERIC, null),
                GroupedResult.KeyTypes.of(numTable, List.of("USUBJID", "ABSENT")).kinds());
    }


    @Test
    void sidedKeyColumnsNameBothSides()
    {
        IDataTable foreign = RealTables.of("PM").dbl("PMSPID", 1.0).build();
        IDataTable eval = RealTables.of("TF").str("TFSPID", "1").build();
        JoinKeyTypeMismatchException ex = assertThrows(JoinKeyTypeMismatchException.class,
                () -> GroupedResult.requireCompatibleKeyColumns(foreign, List.of("PMSPID"), eval,
                        List.of("TFSPID")));
        assertTrue(ex.getMessage()
                .contains("key column PMSPID is Numeric in PM, where the operation grouped, and its"
                        + " counterpart TFSPID is Character in TF"),
                ex.getMessage());
        assertDoesNotThrow(() -> GroupedResult.requireCompatibleKeyColumns(foreign,
                List.of("PMSPID"), foreign, List.of("PMSPID")));
    }
}
