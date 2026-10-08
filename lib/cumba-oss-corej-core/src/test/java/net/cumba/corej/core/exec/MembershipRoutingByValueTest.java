package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.KeyedJoinFixtures;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Membership routed by the VALUE, not by the function's name ({@code PLAN-binding-substitutability}
 * C17 / C18, runbook W2 BS4, coordinator decision W2-D8): every right-hand call the earlier arms do
 * not claim is read on its vector and folded with the {@code toSet} contract (a dataset-level list
 * its set, a per-row list its row's set, a scalar a singleton) - so the call written in place
 * answers exactly what a {@code $} binding holding it answers (G1). The left-hand side has the twin
 * defect: a lifted call over a {@code $} value is decided per row, the way a {@code colref($x)}
 * probe already is (a row holding a list compares element-wise, any element a member - D81d).
 *
 * <p>
 * Before BS4 the inline right-hand spellings were a rule ERROR (the set builder's <i>"membership
 * right-hand side must be a list literal or a $-variable list"</i>) and a lifted left side compared
 * the list's rendered text ({@code "[a, b]"}) as one value.
 * </p>
 */
class MembershipRoutingByValueTest
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


    private static String json(String check, String... bindings)
    {
        StringBuilder b = new StringBuilder(
                "{\"Core\":{\"Id\":\"T-BS4\"},\"Sensitivity\":\"Record\"");
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


    /** The rows (1-based) {@code check} fires on, with {@code bindings} declared in order. */
    private static List<Long> fired(String check, String... bindings)
    {
        String ruleJson = json("not empty(USUBJID) and (" + check + ")", bindings);
        Rule r = load(ruleJson);
        assertNull(r.getLoadError(), r.getLoadError());
        IDataTable primary = ae();
        RuleExecutionResult result = RuleRunnerCalls.execute(r, primary,
                RealTables.inventoryOf(primary), primary.getMetaData().getName(), null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> ruleJson + " -> " + result.getStatusMessage());
        List<Long> rows = new ArrayList<>();
        result.getViolations().forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    private static String[] with(String[] bindings, String... more)
    {
        String[] out = new String[bindings.length + more.length];
        System.arraycopy(bindings, 0, out, 0, bindings.length);
        System.arraycopy(more, 0, out, bindings.length, more.length);
        return out;
    }


    /**
     * {@code inline} and its bound twin {@code bound} (which reads {@code $u: twin}) fire on the
     * same rows, and those rows are {@code expected}.
     */
    private static void assertTwins(List<Long> expected, String inline, String bound, String twin,
            String... bindings)
    {
        assertEquals(expected, fired(inline, bindings), inline);
        assertEquals(expected, fired(bound, with(bindings, "$u", twin)), bound + " / $u: " + twin);
    }

    // ------------------------------------------------------------------ C17: right-hand calls


    @Test
    void aLiftedCallOverADatasetLevelListIsItsSet()
    {
        String[] n =
        {
                "$n", "[\"a\", \"b\"]"
        };
        assertTwins(List.of(1L, 2L, 3L), "\"A\" in upper($n)", "\"A\" in $u", "upper($n)", n);
        assertTwins(List.of(), "\"C\" in upper($n)", "\"C\" in $u", "upper($n)", n);
        assertTwins(List.of(1L, 2L, 3L), "\"C\" not in upper($n)", "\"C\" not in $u", "upper($n)",
                n);
        // a column probe against the folded constant set: A = a / missing / c
        assertTwins(List.of(), "upper(A) in upper($n) and A == \"c\"",
                "upper(A) in $u and A == \"c\"", "upper($n)", n);
        assertTwins(List.of(1L), "upper(A) in upper($n)", "upper(A) in $u", "upper($n)", n);
    }


    @Test
    void aLiftedCallOverAPerRowListIsTheRowsSet()
    {
        // X: "a,b" / "y" / missing - split_by's per-row list, folded element-wise
        String[] s =
        {
                "$s", "split_by(X, \",\")"
        };
        assertTwins(List.of(1L), "\"A\" in upper($s)", "\"A\" in $u", "upper($s)", s);
        assertTwins(List.of(2L), "\"Y\" in upper($s)", "\"Y\" in $u", "upper($s)", s);
        assertTwins(List.of(2L, 3L), "\"B\" not in upper($s)", "\"B\" not in $u", "upper($s)", s);
        // a column probe against the row's own set: B = b / y / c
        assertTwins(List.of(1L, 2L), "upper(B) in upper($s)", "upper(B) in $u", "upper($s)", s);
    }


    @Test
    void aMissingRowIsTheEmptySet()
    {
        // the toSet contract: split_by(A) is [a] / missing / [c] - the missing row is the empty
        // set (the $ path's answer), so `in` never fires on it and `not in` always does
        String[] a =
        {
                "$a", "split_by(A, \",\")"
        };
        assertTwins(List.of(1L), "\"A\" in upper($a)", "\"A\" in $u", "upper($a)", a);
        assertTwins(List.of(2L, 3L), "\"A\" not in upper($a)", "\"A\" not in $u", "upper($a)", a);
    }

    // ------------------------------------------------------------------ C18: colref of any
    // argument


    @Test
    void colrefOfAComputedNameListReadsTheNamedCells()
    {
        // upper($v) names the columns A and B; their cells per row: [a, b] / [., y] / [c, c]
        String[] v =
        {
                "$v", "[\"a\", \"b\"]"
        };
        assertTwins(List.of(2L), "\"y\" in colref(upper($v))", "\"y\" in colref($u)", "upper($v)",
                v);
        assertTwins(List.of(3L), "\"c\" in colref(upper($v))", "\"c\" in colref($u)", "upper($v)",
                v);
        assertTwins(List.of(2L, 3L), "\"a\" not in colref(upper($v))", "\"a\" not in colref($u)",
                "upper($v)", v);
    }

    // ------------------------------------------------------------------ the left-hand side


    @Test
    void aLiftedLeftSideOverAPerRowListIsDecidedPerRow()
    {
        // lower($s): row 1 [a, b], row 2 [y], row 3 missing - a list row is a member when any
        // element is (D81d); the missing row is a scalar probe and takes the scalar arm
        String[] s =
        {
                "$s", "split_by(X, \",\")"
        };
        assertTwins(List.of(1L), "lower($s) in [\"b\"]", "$u in [\"b\"]", "lower($s)", s);
        assertTwins(List.of(2L, 3L), "lower($s) not in [\"b\"]", "$u not in [\"b\"]", "lower($s)",
                s);
        // the case-insensitive surface: upper($s) folds probe and members alike
        assertTwins(List.of(1L), "upper($s) in [\"b\"]", "$u in [\"B\"]", "upper($s)", s);
    }


    @Test
    void aLiftedLeftSideOverADatasetLevelListIsTheListOnEveryRow()
    {
        String[] n =
        {
                "$n", "[\"a\", \"b\"]"
        };
        assertTwins(List.of(1L, 2L, 3L), "lower($n) in [\"a\"]", "$u in [\"a\"]", "lower($n)", n);
        assertTwins(List.of(), "lower($n) in [\"c\"]", "$u in [\"c\"]", "lower($n)", n);
        assertTwins(List.of(1L, 2L, 3L), "upper($n) in [\"B\"]", "$u in [\"B\"]", "upper($n)", n);
    }


    @Test
    void aLiftedLeftSideOverAScalarBindingAnswersAsBefore()
    {
        // no row holds a list: the scalar arms, exactly as before BS4
        String[] a =
        {
                "$a", "trim(A)"
        };
        assertTwins(List.of(1L), "upper($a) in [\"a\"]", "upper($u) in [\"a\"]", "$a", a);
        assertTwins(List.of(3L), "lower($a) in [\"c\"]", "$u in [\"c\"]", "lower($a)", a);
    }
}
