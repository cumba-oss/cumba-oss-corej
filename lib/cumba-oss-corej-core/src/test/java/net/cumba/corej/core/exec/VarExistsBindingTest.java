package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.KeyedJoinFixtures;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-dynamic-column-corpus-respelling} §6.2 (owner ruling (a), 2026-10-04), end to end
 * through the loader and the runner: {@code var_exists($n)} / {@code var_not_exists($n)} over a
 * {@code $}-binding evaluate the BOUND value as the column name, per row — exactly what the inline
 * computed spelling {@code var_exists(<expression of $n>)} does
 * ({@code PLAN-binding-substitutability} G1: where {@code $x} is accepted, the expression
 * {@code $x} holds is accepted, and they answer alike). Before the fix a {@code $}-argument took
 * the literal path and asked for a column literally named {@code $n} — never present — so
 * {@code not var_exists($n)} fired on every row.
 */
class VarExistsBindingTest
{

    /** The S2 name: {@code ADSL.TRT<APERIOD:%02d>P}. */
    private static final String NAME = "concat(\"ADSL.TRT\", printf(\"%02d\", APERIOD), \"P\")";

    /**
     * The primary: row 1 period 1 (ADSL.TRT01P exists), row 2 period 2 (ADSL.TRT02P does not), row
     * 3 a missing driver, row 4 a non-integral driver (printf yields a computed missing, Q1). A
     * primary TRT01P column for the non-binding controls.
     */
    private static IDataTable adae()
    {
        return RealTables.of("ADAE").str("USUBJID", "S1", "S1", "S2", "S1")
                .dbl("APERIOD", 1.0, 2.0, MissingValue.MIS.asDouble(), 1.5)
                .str("TRT01P", "X", "X", "X", "X").build();
    }


    /** ADSL: both subjects, one planned-treatment column. */
    private static IDataTable adsl()
    {
        return RealTables.of("ADSL").str("USUBJID", "S1", "S2").str("TRT01P", "A", "B").build();
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    /** A rule over ADSL; {@code sensitivity} {@code null} leaves it to the classifier. */
    private static String json(String check, String sensitivity, String... bindings)
    {
        StringBuilder b = new StringBuilder("{\"Core\":{\"Id\":\"T-VEB\"}");
        if (sensitivity != null)
        {
            b.append(",\"Sensitivity\":\"").append(sensitivity).append('"');
        }
        b.append(",\"Match_Datasets\":[{\"Name\":\"ADSL\",\"Keys\":[\"USUBJID\"],")
                .append("\"Join_Type\":\"left\"}]");
        if (bindings.length > 0)
        {
            b.append(",\"Bindings\":[");
            for (int i = 0; i < bindings.length; i += 2)
            {
                b.append(i == 0 ? "" : ",").append("{\"name\":\"").append(bindings[i])
                        .append("\",\"expression\":\"").append(esc(bindings[i + 1])).append("\"}");
            }
            b.append(']');
        }
        return b.append(",\"Check\":{\"expression\":\"").append(esc(check))
                .append("\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}")
                .toString();
    }


    private static Rule load(String ruleJson)
    {
        try
        {
            Rule r = RulePackageLoader
                    .loadFromString(
                            KeyedJoinFixtures.declared("{\"rules\":{\"R\":" + ruleJson + "}}"))
                    .getRules().get("R");
            assertNotNull(r);
            return r;
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException(e);
        }
    }


    private static List<Long> rows(String ruleJson)
    {
        Rule r = load(ruleJson);
        assertNull(r.getLoadError(), r.getLoadError());
        IDataTable primary = adae();
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                RealTables.inventoryOf(primary, adsl()), "ADAE", null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    /** The fired rows (1-based) of a row-guarded {@code check}, Record sensitivity. */
    private static List<Long> fired(String check, String... bindings)
    {
        return rows(json("not empty(USUBJID) and (" + check + ")", "Record", bindings));
    }


    @Test
    void aBoundNameIsEvaluatedPerRowAsTheInlineOne()
    {
        assertEquals(List.of(1L), fired("var_exists($n)", "$n", NAME),
                "period 1 → ADSL.TRT01P exists; period 2 → TRT02P does not; a missing and a"
                        + " non-integral driver leave the row unset");
        assertEquals(List.of(2L), fired("var_not_exists($n)", "$n", NAME),
                "var_not_exists: the missing and the non-integral driver rows stay unset too");
        assertEquals(fired("var_exists(" + NAME + ")"), fired("var_exists($n)", "$n", NAME),
                "substitutable: the binding answers as its expression written inline");
        assertEquals(fired("var_not_exists(" + NAME + ")"),
                fired("var_not_exists($n)", "$n", NAME));
    }


    /**
     * QS (b): the ruled S2 spelling, guarded, stays silent for a missing or non-integral period.
     */
    @Test
    void theRuledGuardSilencesAMissingAndANonIntegralDriver()
    {
        assertEquals(List.of(2L, 3L, 4L), fired("not var_exists($n)", "$n", NAME),
                "`not` flips the unset rows to set — which is why QS (b) guards the name");
        assertEquals(fired("not var_exists(" + NAME + ")"),
                fired("not var_exists($n)", "$n", NAME));
        assertEquals(List.of(2L), fired("not empty($n) and not var_exists($n)", "$n", NAME));
        assertEquals(List.of(2L),
                fired("not empty(APERIOD) and not empty($n) and not var_exists($n)", "$n", NAME));
    }


    /**
     * The classifier and the router see a per-row fact: unguarded and with no explicit Sensitivity,
     * the bound spelling reports the same rows as the inline one (a dataset-level classification
     * would collapse or broadcast them).
     */
    @Test
    void aBoundNameIsARecordLevelFactForTheClassifier()
    {
        Rule bound = load(json("not empty($n) and not var_exists($n)", null, "$n", NAME));
        assertEquals(Sensitivity.RECORD, RuleClassifier.deriveSensitivity(bound).value(),
                RuleClassifier.deriveSensitivity(bound).toString());
        Rule alone = load(json("var_not_exists($n)", null, "$n", NAME));
        assertEquals(Sensitivity.RECORD, RuleClassifier.deriveSensitivity(alone).value(),
                RuleClassifier.deriveSensitivity(alone).toString());
        assertEquals(List.of(2L),
                rows(json("not empty($n) and not var_exists($n)", null, "$n", NAME)));
        assertEquals(rows(json("var_not_exists(" + NAME + ")", null)),
                rows(json("var_not_exists($n)", null, "$n", NAME)));
    }


    /** A binding with no row cursor names one column for every row. */
    @Test
    void aDatasetLevelBindingNamesOneColumnForEveryRow()
    {
        assertEquals(List.of(1L, 2L, 3L, 4L), fired("var_exists($n)", "$n", "\"ADSL.TRT01P\""));
        assertEquals(List.of(), fired("var_exists($n)", "$n", "\"ADSL.TRT09P\""));
        assertEquals(List.of(1L, 2L, 3L, 4L), fired("var_not_exists($n)", "$n", "\"ADSL.TRT09P\""));
        assertEquals(List.of(1L, 2L, 3L, 4L), fired("var_exists($n)", "$n", "\"TRT01P\""),
                "a bare bound name asks the evaluated dataset, as var_exists(\"TRT01P\") does");
    }


    /** Controls: a reference that is not a {@code $}-binding keeps the literal-name path. */
    @Test
    void aNonBindingReferenceIsUnchanged()
    {
        assertEquals(List.of(1L, 2L, 3L, 4L), fired("var_exists(TRT01P)"));
        assertEquals(List.of(1L, 2L, 3L, 4L), fired("var_exists(ADSL.TRT01P)"));
        assertEquals(List.of(), fired("var_exists(ZZZ)"));
        assertEquals(List.of(1L, 2L, 3L, 4L), fired("var_not_exists(ZZZ)"));
        assertEquals(List.of(1L, 2L, 3L, 4L), fired("var_not_exists(ADSL.TRT02P)"));
        assertEquals(List.of(1L, 2L, 3L, 4L), fired("var_exists(\"TRT01P\")"));
    }


    /**
     * A binding whose static type cannot name a column is refused at load, exactly as the same
     * expression written inline is (D3: an unsupported actual type errors the rule; the
     * {@code colref} precedent, Q7).
     */
    @Test
    void aStaticallyNonStringNameIsALoadErrorBoundOrInline()
    {
        String bound = load(json("var_exists($k)", "Record", "$k", "num(APERIOD) + 1"))
                .getLoadError();
        assertNotNull(bound, "a number-typed binding cannot name a column");
        assertTrue(bound.contains("PARAMETER_TYPE"), bound);
        String inline = load(json("var_exists(num(APERIOD) + 1)", "Record")).getLoadError();
        assertNotNull(inline, "the same expression inline");
        assertTrue(inline.contains("PARAMETER_TYPE"), inline);
        String list = load(json("var_not_exists($l)", "Record", "$l", "find_vars(\"ADSL.TRTxxP\")"))
                .getLoadError();
        assertNotNull(list, "a list of names is not one name");
        assertTrue(list.contains("PARAMETER_TYPE"), list);
    }


    /**
     * A binding over a COLUMN has no static type (a cell is read per row), so it loads; a present
     * non-string cell names no column and leaves the row unset for both polarities, as the inline
     * computed form does ({@code ExprCompiler.compileComputedExists}).
     */
    @Test
    void aNonStringCellNamesNoColumnForEitherPolarity()
    {
        assertEquals(List.of(), fired("var_exists($c)", "$c", "APERIOD"));
        assertEquals(List.of(), fired("var_not_exists($c)", "$c", "APERIOD"));
        assertEquals(fired("var_not_exists(concat(\"\", USUBJID))"),
                fired("var_not_exists($c)", "$c", "concat(\"\", USUBJID)"),
                "a string cell is a name: S1 / S2 are no columns of ADAE");
    }
}
