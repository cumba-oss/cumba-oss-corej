package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;
import net.cumba.corej.core.model.Rule;
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
 * A grouped function's result (an operation's until runbook W8) is looked up by the typed key
 * identity, so a group column that is Char where the function grouped and Num where the result is
 * read would match no row — silently. The rule ERRORs instead, naming both columns and kinds;
 * asserted at {@link RuleRunner} level (the rule's status and message), not only as a unit
 * exception. Never an error: {@code LONG} against {@code DOUBLE} (both numeric), a kind the gate
 * does not classify ({@code D4-R6a}: the all-NA {@code .rds} column arrives as {@code BOOLEAN}),
 * and the text-carried family ({@code SUPP--}, {@code D4-R5}).
 * </p>
 */
class GroupedLookupKeyTypeTest
{

    /**
     * A record-sensitivity rule whose {@code $n} is {@code record_count(domain=…, group=[…])} — the
     * registry function since runbook W6 ({@code RecordCount}); the key-type check runs where the
     * broadcast binds the counted table to the evaluated one.
     */
    private static Rule rule(String aCheck, List<String> aOutputs)
    {
        return rule(aCheck, aOutputs, null);
    }


    private static Rule rule(String aCheck, List<String> aOutputs,
            @org.jspecify.annotations.Nullable String aBinding)
    {
        try
        {
            net.cumba.corej.core.model.RulePackage pkg = net.cumba.corej.core.RulePackageLoader
                    .loadFromString("{\"rules\":{\"TEST-P16-Q2\":{"
                            + "\"Core\":{\"Id\":\"TEST-P16-Q2\"},\"Sensitivity\":\"Record\","
                            + (aBinding == null ? ""
                                    : "\"Bindings\":[{\"name\":\"$n\",\"expression\":\"" + aBinding
                                            + "\"}],")
                            + "\"Check\":{\"expression\":\"" + aCheck.replace("\"", "\\\"") + "\"},"
                            + "\"Outcome\":{\"Message\":\"grouped lookup\",\"Output_Variables\":["
                            + aOutputs.stream().map(o -> "\"" + o + "\"")
                                    .collect(java.util.stream.Collectors.joining(","))
                            + "]}}}}");
            Rule rule = pkg.getRules().get("TEST-P16-Q2");
            if (rule.getLoadError() != null)
            {
                throw new IllegalArgumentException(rule.getLoadError());
            }
            return rule;
        }
        catch (java.io.IOException e)
        {
            throw new IllegalArgumentException(e);
        }
    }


    private static String countIn(String aDomain, String aGroup)
    {
        return "record_count(domain=" + aDomain + ", group=[" + aGroup + "])";
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
     * the rule's table, wherever the (since retired) operation was materialised.
     */
    @Test
    void anUnreadGroupedResultIsNeverChecked()
    {
        IDataTable aeTable = RealTables.of("AE").str("USUBJID", "1", "2").build();
        IDataTable dm = RealTables.of("DM").dbl("USUBJID", 1.0, 2.0).build();
        // (Since runbook W6 the count is a compiled binding, which the loader's D4a derivation
        // would report on every finding — a read; "!$n" keeps it out of the reported set so
        // genuinely nothing reads it.)
        RuleExecutionResult res = run(
                rule("not empty(USUBJID)", List.of("USUBJID", "!$n"), countIn("DM", "USUBJID")),
                aeTable, dm);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        assertEquals(2, res.getViolations().size());
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
        // Runbook W2a retired the supp_qnam_present operation: the qualifier is read per record
        // through SuppPivot (Supp_Merge, default true) — the same text-keyed IDVARVAL match.
        RuleExecutionResult res = run(rule("AETRTEM == \"Y\"", List.of("AESEQ")), aeTable, supp);
        assertEquals(RuleExecutionStatus.EXECUTED, res.getStatus(), res.getStatusMessage());
        // IDVARVAL "1" (text) found AESEQ 1 (a number): row 1 fires
        assertEquals(1, res.getViolations().size());
    }

    // ---------------------------------------------------------------- the unit contract


    /**
     * The static the grouped functions share: the key columns are compared position-wise, and a
     * column absent on either side has no kind to disagree with. (Until runbook W8 the same claim
     * was made through a {@code GroupedResult} instance, whose {@code TEXT} key mode and unknown
     * key types were never checked; both went with the record.)
     */
    @Test
    void requireCompatibleKeyColumnsComparesPositionallyAndSkipsWhatItCannotJudge()
    {
        IDataTable charTable = RealTables.of("AE").str("USUBJID", "1").str("X", "a").build();
        IDataTable numTable = RealTables.of("DM").dbl("USUBJID", 1.0).lng("X", 2L).build();
        List<String> usubjid = List.of("USUBJID");
        JoinKeyTypeMismatchException ex = assertThrows(JoinKeyTypeMismatchException.class,
                () -> GroupKeyIdentity.requireCompatibleKeyColumns(numTable, usubjid, charTable,
                        usubjid));
        assertTrue(ex.getMessage().contains("USUBJID is Numeric in DM"), ex.getMessage());
        assertDoesNotThrow(() -> GroupKeyIdentity.requireCompatibleKeyColumns(numTable, usubjid,
                numTable, usubjid));
        // position-wise: the second pair (Num X against Char X) is judged too
        JoinKeyTypeMismatchException second = assertThrows(JoinKeyTypeMismatchException.class,
                () -> GroupKeyIdentity.requireCompatibleKeyColumns(numTable,
                        List.of("USUBJID", "X"),
                        RealTables.of("AE").dbl("USUBJID", 1.0).str("X", "a").build(),
                        List.of("USUBJID", "X")));
        assertTrue(second.getMessage().contains("key column X is Numeric in DM"),
                second.getMessage());
        // a column the evaluated table lacks has no kind to disagree with
        assertDoesNotThrow(() -> GroupKeyIdentity.requireCompatibleKeyColumns(numTable, usubjid,
                RealTables.of("TS").str("TSPARMCD", "A").build(), usubjid));
        // nor one the grouped table lacks
        assertDoesNotThrow(() -> GroupKeyIdentity.requireCompatibleKeyColumns(numTable,
                List.of("ABSENT"), charTable, List.of("X")));
    }


    @Test
    void sidedKeyColumnsNameBothSides()
    {
        IDataTable foreign = RealTables.of("PM").dbl("PMSPID", 1.0).build();
        IDataTable eval = RealTables.of("TF").str("TFSPID", "1").build();
        JoinKeyTypeMismatchException ex = assertThrows(JoinKeyTypeMismatchException.class,
                () -> GroupKeyIdentity.requireCompatibleKeyColumns(foreign, List.of("PMSPID"), eval,
                        List.of("TFSPID")));
        assertTrue(ex.getMessage()
                .contains("key column PMSPID is Numeric in PM, where the function grouped, and its"
                        + " counterpart TFSPID is Character in TF"),
                ex.getMessage());
        assertDoesNotThrow(() -> GroupKeyIdentity.requireCompatibleKeyColumns(foreign,
                List.of("PMSPID"), foreign, List.of("PMSPID")));
    }
}
