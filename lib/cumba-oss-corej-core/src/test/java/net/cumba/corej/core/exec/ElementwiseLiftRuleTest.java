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
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * The element-wise lift ({@code PLAN-elementwise-list-functions} §3.4–§3.6, §3.9; runbook W1a E4)
 * seen from a rule: {@code upper} / {@code lower} (and their aliases) over {@code values([X, Y])},
 * over a per-row list, over a dataset-level list and over a scalar — and the two load errors at the
 * lifted parameter (the bare-name list's hint, Q14 (c); the name-list binding, coordinator decision
 * 9). The scalar, per-row-list and dataset-level rows are the no-behaviour-change pins of the
 * migration (§3.9): they answer exactly what {@code caseFold}'s collection branch answered.
 */
class ElementwiseLiftRuleTest
{

    // ------------------------------------------------------------------ fixtures

    private static IDataTable ae()
    {
        return RealTables.of("AE").str("USUBJID", "S1", "S1", "S2").str("A", "a", null, "c")
                .str("B", "b", "y", "c").str("X", "a,b", "y", null).build();
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private static String json(String check, List<String> outputs, String... bindings)
    {
        StringBuilder b = new StringBuilder(
                "{\"Core\":{\"Id\":\"T-LIFT\"},\"Sensitivity\":\"Record\"");
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
        b.append(",\"Check\":{\"expression\":\"").append(esc(check))
                .append("\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[");
        for (int i = 0; i < outputs.size(); i++)
        {
            b.append(i == 0 ? "" : ",").append('"').append(esc(outputs.get(i))).append('"');
        }
        return b.append("]}}").toString();
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


    private static RuleExecutionResult run(String ruleJson)
    {
        Rule r = load(ruleJson);
        assertNull(r.getLoadError(), r.getLoadError());
        IDataTable primary = ae();
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                RealTables.inventoryOf(primary), primary.getMetaData().getName(), null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> ruleJson + " → " + result.getStatusMessage());
        return result;
    }


    private static List<Long> fired(String check, String... bindings)
    {
        RuleExecutionResult result = run(
                json("not empty(USUBJID) and (" + check + ")", List.of("USUBJID"), bindings));
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    private static String loadError(String check, String... bindings)
    {
        return String.valueOf(load(json(check, List.of("USUBJID"), bindings)).getLoadError());
    }


    /**
     * {@code $v} bound to {@code binding} (declared after {@code moreBindings}, which it may read),
     * reported on every row: its rendering per row.
     */
    private static List<String> rendered(String binding, String... moreBindings)
    {
        String[] bindings = new String[moreBindings.length + 2];
        System.arraycopy(moreBindings, 0, bindings, 0, moreBindings.length);
        bindings[moreBindings.length] = "$v";
        bindings[moreBindings.length + 1] = binding;
        RuleExecutionResult result = run(json("not empty(USUBJID)", List.of("$v"), bindings));
        List<String> out = new ArrayList<>();
        result.getViolations().forEach(v -> out.add(v.getValues().get("$v")));
        return out;
    }

    // ------------------------------------------------------------------ the new form


    @Test
    void theLiftOverValuesIsAPerRowListInInputOrder()
    {
        // A: a / «missing» / c, B: b / y / c — the fold applies to every member of every row, the
        // missing member stays missing at its position (Q5), and the list is a per-row membership
        // set on the right of `in` (Q8): row 2 holds "Y" (B only), row 3 holds "C" (both)
        assertEquals(List.of(2L), fired("\"Y\" in upper(values([A, B]))"));
        assertEquals(List.of(3L), fired("\"C\" in upper(values([A, B]))"));
        assertEquals(List.of(), fired("\"y\" in upper(values([A, B]))"));
        assertEquals(List.of("[A, B]", "[., Y]", "[C, C]"), rendered("upper(values([A, B]))"));
        assertEquals(List.of("[b, a]", "[y, .]", "[c, c]"), rendered("lower(values([B, A]))"));
    }


    @Test
    void theAliasesLiftIdentically()
    {
        // upcase / lowcase are registered aliases of upper / lower (the same EvalFunction) and
        // carry the same flag — UA (W2) relies on it
        assertEquals(rendered("upper(values([A, B]))"), rendered("upcase(values([A, B]))"));
        assertEquals(rendered("lower(values([A, B]))"), rendered("lowcase(values([A, B]))"));
        assertEquals(List.of(2L), fired("\"Y\" in upcase(values([A, B]))"));
    }


    @Test
    void countOverTheLiftIsTheCardinality()
    {
        // Q13: count / size stay the list's cardinality — two members on every row, the missing
        // member counted (it is a member)
        assertEquals(List.of(1L, 2L, 3L), fired("count(upper(values([A, B]))) == 2"));
    }


    @Test
    void aBareNameListAtTheLiftedParameterIsALoadErrorWithTheHint()
    {
        // Q14 (c): [A, B] keeps its one meaning — a list of column NAMES — so a value function
        // over it is refused with the ruled hint, never read as the names' text nor as the cells
        String error = loadError("not empty(upper([A, B]))");
        assertTrue(error.contains(
                "[A, B] is a list of column names; to read their values write values([A, B])"),
                error);
    }


    @Test
    void aNameListBindingAtTheLiftedParameterStaysTheHint()
    {
        // coordinator decision 9 (E2): a $keys binding of names is read only at a splice slot or by
        // values($keys) — the lifted arm must not admit it as a string list
        String error = loadError("not empty(upper($keys))", "$keys", "[A, B]");
        assertTrue(error.contains("holds column names"), error);
    }

    // ------------------------------------------------------------------ the migration pins


    @Test
    void aScalarArgumentAnswersAsBefore()
    {
        assertEquals(List.of(1L), fired("upper(A) == \"A\""));
        assertEquals(List.of(2L), fired("lower(B) == \"y\""));
        // a nested scalar call — the statically-scalar shape the compiler hands to the kernel
        assertEquals(List.of(3L), fired("upper(trim(A)) == \"C\""));
        // a scalar binding — decided on the vector at evaluation
        assertEquals(List.of(1L), fired("upper($a) == \"A\"", "$a", "trim(A)"));
    }


    @Test
    void aPerRowListAnswersAsBefore()
    {
        // X: "a,b" / "y" / «missing» — split_by's per-row list folds element-wise, a missing row
        // stays that missing (Q6), and the list is a per-row membership set
        assertEquals(List.of(1L), fired("\"B\" in upper(split_by(X, \",\"))"));
        assertEquals(List.of(1L, 2L),
                fired("\"Y\" in upper(split_by(X, \",\")) or \"A\" in upper(split_by(X, \",\"))"));
        List<String> folded = rendered("upper(split_by(X, \",\"))");
        List<String> unfolded = rendered("split_by(X, \",\")");
        assertEquals(List.of("[A, B]", "[Y]"), folded.subList(0, 2));
        assertEquals(unfolded.get(2), folded.get(2), "a missing row is that missing, unchanged");
    }


    @Test
    void aDatasetLevelListAnswersOnceAsBefore()
    {
        // a broadcast list (Q15): one folded constant for the dataset, read as a membership set
        assertEquals(List.of(1L, 2L, 3L),
                fired("\"A\" in $u", "$names", "[\"a\", \"b\"]", "$u", "upper($names)"));
        assertEquals(List.of("[A, B]", "[A, B]", "[A, B]"),
                rendered("upper($names)", "$names", "[\"a\", \"b\"]"));
        // a string-literal list is a value list already and lifts unchanged (Q14 (d))
        assertEquals(List.of("[A, B]", "[A, B]", "[A, B]"), rendered("upper([\"a\", \"b\"])"));
        assertEquals(List.of("[a]", "[a]", "[a]"), rendered("lower([\"A\"])"));
    }
}
