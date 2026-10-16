package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.cumba.corej.core.KeyedJoinFixtures;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * Runbook W2 E5 ({@code PLAN-elementwise-list-functions} §3.6, §7.1, §7.6; owner Q2-Q8, Q13): the
 * rest of census class (a) and the five conversions / formatters lift over a list at their value
 * parameter, seen from a rule. Per function: a scalar answers as before; over
 * {@code values([X, Y])}, a per-row {@code split_by} list and a dataset-level list every element
 * answers as the scalar call answers it alone (the differential); a missing element, a missing list
 * and {@code []}; a list at another parameter refused at load (Q4); a lifted list against a scalar
 * refused at load (Q8); a reported binding over a {@code $} value keeps a type source; and a lifted
 * list as the probe of {@code in} - the textual and the temporal (D81) arms - asks whether any
 * element is a member. Before E5 a list answered its rendering: {@code len($n)} = 7 for
 * {@code ["a", "bb"]}, {@code substring($s, 1, 1)} = {@code "["}, {@code num($n)} = missing.
 */
class ElementwiseLiftRosterRuleTest
{

    // ------------------------------------------------------------------ fixtures

    /**
     * Row 1 / 2 / 3: text pairs A / B, a text column C missing on row 2, numbers N1 / N2, ISO dates
     * D1 / D2, ISO times T1 / T2, a comma list X (missing on row 3), a comma list of dates Y and a
     * comma list of numbers NL.
     */
    private static IDataTable ae()
    {
        return RealTables.of("AE").str("USUBJID", "S1", "S1", "S2").str("A", " ab ", "Cd", "e")
                .str("B", "x  y", " z", "fgh").str("C", "q", null, "r").dbl("N1", -1.5, 2.25, 3.0)
                .dbl("N2", 0.5, -7.75, 10.0).str("D1", "2020-01-15", "2021-03", "2019-12-31T23:59")
                .str("D2", "2022-02-28", "2020", "2018-06-01")
                .str("T1", "09:15", "23:59:30", "00:00").str("T2", "10:30:05", "12", "06:45")
                .str("X", "a,bb", "ccc", null)
                .str("Y", "1999-01-01,2020-01-01", "2020-01-15", "2020-02-29T23:00")
                .str("NL", "-10,3", "2.5", "4").build();
    }

    /**
     * Every newly flagged name (E5), as a call template over {@code @} — the value parameter — and
     * the pair of fixture columns of the value it reads.
     */
    private static final Map<String, String[]> CALLS = calls();

    private static Map<String, String[]> calls()
    {
        Map<String, String[]> m = new LinkedHashMap<>();
        for (String text : List.of("len", "length", "trim", "normalize_space", "char", "str"))
        {
            m.put(text, new String[]
            {
                    text + "(@)", "A", "B"
            });
        }
        m.put("prefix", new String[]
        {
                "prefix(@, 2)", "A", "B"
        });
        m.put("suffix", new String[]
        {
                "suffix(@, 2)", "A", "B"
        });
        m.put("substring", new String[]
        {
                "substring(@, 2, 2)", "A", "B"
        });
        m.put("lpad", new String[]
        {
                "lpad(@, 6, \"*\")", "A", "B"
        });
        for (String number : List.of("abs", "ceil", "floor", "round", "num"))
        {
            m.put(number, new String[]
            {
                    number + "(@)", "N1", "N2"
            });
        }
        for (String date : List.of("year", "month", "day", "date", "earliest_possible",
                "latest_possible"))
        {
            m.put(date, new String[]
            {
                    date + "(@)", "D1", "D2"
            });
        }
        m.put("time", new String[]
        {
                "time(@)", "T1", "T2"
        });
        return m;
    }


    private static String call(String name, String value)
    {
        return CALLS.get(name)[0].replace("@", value);
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
    }


    private static String json(String check, List<String> outputs, String... bindings)
    {
        StringBuilder b = new StringBuilder(
                "{\"Core\":{\"Id\":\"T-LIFT-E5\"},\"Sensitivity\":\"Record\"");
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
                () -> ruleJson + " -> " + result.getStatusMessage());
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


    /** Each rendering with a whole number's {@code .0} dropped — the canonical number text. */
    private static List<String> canonical(List<String> renderings)
    {
        return renderings.stream()
                .map(r -> r.matches("-?[0-9]+\\.0") ? r.substring(0, r.length() - 2) : r).toList();
    }


    /** The rendering of a two-element list of the two scalar renderings, row by row. */
    private static List<String> pairs(List<String> first, List<String> second)
    {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < first.size(); i++)
        {
            out.add("[" + first.get(i) + ", " + second.get(i) + "]");
        }
        return out;
    }

    // ------------------------------------------------------------------ the differential


    @Test
    void overValuesEveryElementAnswersAsTheScalarCallAnswersItAlone()
    {
        // Q3 / Q10 case 2: K column-wide kernel calls, element k in slot 0, the other arguments
        // the same for every k (prefix's n, substring's start / length, lpad's width / fill)
        for (Map.Entry<String, String[]> e : CALLS.entrySet())
        {
            String name = e.getKey();
            String[] columns = e.getValue();
            // a list element that is a computed number is its canonical text (R5 B-MED-2: "3",
            // as a dataset-level number renders); the per-row scalar call's own cell still
            // renders a whole double "3.0" (DataValues.of - unchanged), so it is compared canonical
            List<String> first = canonical(rendered(call(name, columns[1])));
            List<String> second = canonical(rendered(call(name, columns[2])));
            assertEquals(pairs(first, second),
                    rendered(call(name, "values([" + columns[1] + ", " + columns[2] + "])")),
                    name + " over values([X, Y])");
        }
    }


    @Test
    void aScalarArgumentAnswersAsBefore()
    {
        // the statically scalar shape (a column) is the kernel call: pinned answers
        assertEquals(List.of("4", "2", "1"), rendered("len(A)"));
        assertEquals(List.of("ab", "Cd", "e"), rendered("trim(A)"));
        assertEquals(List.of("ab", "d", ""), rendered("substring(A, 2, 2)"),
                "a start beyond the value is the empty slice \"\" (SVY OQ1, 2026-10-16)");
        assertEquals(List.of("2020", "2021", "2019"), rendered("year(D1)"));
        assertEquals(List.of("-2", "2", "3"), rendered("floor(N1)"));
        assertEquals(List.of(1L), fired("len(A) == 4"));
        assertEquals(List.of(1L), fired("date(D1) == date(\"2020-01-15\")"));
    }


    @Test
    void aPerRowListIsLiftedElementByElement()
    {
        // X: "a,bb" / "ccc" / missing - split_by's per-row list, Q10 case 4; a missing row is
        // that missing (Q6), unchanged
        assertEquals(List.of("[1, 2]", "[3]"), rendered("len(split_by(X, \",\"))").subList(0, 2));
        assertEquals(rendered("split_by(X, \",\")").get(2),
                rendered("len(split_by(X, \",\"))").get(2), "a missing list is that missing");
        assertEquals(List.of("[A, B]", "[C]"),
                rendered("substring(upper(split_by(X, \",\")), 1, 1)").subList(0, 2));
        // the list is a per-row membership set (Q8): "bb" is a trimmed member of row 1 only
        assertEquals(List.of(1L), fired("\"bb\" in trim(split_by(X, \",\"))"));
        assertEquals(List.of(1L), fired("2 in len(split_by(X, \",\"))"));
    }


    @Test
    void aDatasetLevelListIsLiftedOnceAndTodaysRenderingIsGone()
    {
        // the R2 probes (PLAN-elementwise-list-functions §7.6): len($n) answered 7 - the length
        // of the rendering "[a, bb]"; substring($s, 1, 1) answered "["; num($n) answered missing
        assertEquals(List.of("[1, 2]", "[1, 2]", "[1, 2]"),
                rendered("len($n)", "$n", "[\"a\", \"bb\"]"));
        assertEquals(List.of("[a, c]", "[a, c]", "[a, c]"),
                rendered("substring($s, 1, 1)", "$s", "[\"ab\", \"cd\"]"));
        // a parsed element is a typed number and renders canonically, as a dataset-level num()
        // does ("1", never "1.0" - runbook W2 fold, R5 B-MED-2)
        assertEquals(List.of("[1, 2.5]", "[1, 2.5]", "[1, 2.5]"),
                rendered("num($n)", "$n", "[\"1\", \"2.5\"]"));
        assertEquals(List.of("[  a,  bb]", "[  a,  bb]", "[  a,  bb]"),
                rendered("lpad($n, 3)", "$n", "[\"a\", \"bb\"]"));
        // a string-literal list is a value list already (Q14 (d))
        assertEquals(List.of("[3, 1]", "[3, 1]", "[3, 1]"), rendered("len([\"abc\", \"d\"])"));
    }


    @Test
    void aMissingElementStaysMissingAtItsPositionAndAnEmptyListIsEmpty()
    {
        // C is missing on row 2: the element answers the scalar function's own rule (Q5) - for
        // the string functions identity - and the list keeps its length
        List<String> lens = rendered("len(values([C, A]))");
        assertEquals("[1, 4]", lens.get(0));
        assertEquals(rendered("values([C, A])").get(1).replace("Cd", "2"), lens.get(1),
                "the missing element is kept as it is, the present one counted");
        // [] lifts to [] (Q6)
        assertEquals(List.of("[]", "[]", "[]"),
                rendered("trim($e)", "$e", "minus([\"a\"], [\"a\"])"));
    }

    // ------------------------------------------------------------------ the load errors


    @Test
    void aLiftedListAgainstAScalarIsALoadError()
    {
        // Q8: a lifted list compared with a scalar is the armed PARAMETER_TYPE - membership is
        // spelled `in`
        for (Map.Entry<String, String[]> e : CALLS.entrySet())
        {
            String name = e.getKey();
            String[] columns = e.getValue();
            String list = call(name, "values([" + columns[1] + ", " + columns[2] + "])");
            String scalar = switch (columns[1])
            {
            case "A" -> "\"a\"";
            case "D1" -> "date(\"2020\")";
            case "T1" -> "time(\"09:00\")";
            default -> "1";
            };
            String error = loadError(list + " == " + scalar);
            assertTrue(error.contains("comparison operands disagree: list<"), name + ": " + error);
        }
        assertTrue(loadError("len(values([A, B])) == 1").contains("list<number> EQ number"));
        assertTrue(loadError("trim($n) == \"a\"", "$n", "[\"a\", \"b\"]")
                .contains("list<string> EQ string"));
    }


    @Test
    void aListAtAnotherParameterIsALoadError()
    {
        // Q4: every other parameter of a lifted function is scalar per row - a list there is
        // the armed PARAMETER_TYPE, never a zip or a cartesian product
        String[] nums =
        {
                "$n", "[1, 2]"
        };
        for (String check : List.of("not empty(prefix(A, $n))", "not empty(suffix(A, $n))",
                "not empty(substring(A, $n))", "not empty(substring(A, 1, $n))",
                "not empty(lpad(A, $n))"))
        {
            String error = loadError(check, nums);
            assertTrue(error.contains("list<number>"), check + ": " + error);
        }
        // unit= is a static string literal (BL1's seam) - a list there is refused by it
        String unit = loadError("not empty(len(A, unit=$u))", "$u", "[\"chars\"]");
        assertTrue(unit.contains("argument 'unit' of 'len' takes a static string literal"), unit);
    }


    @Test
    void aReportedBindingOverADollarValueKeepsATypeSource()
    {
        // a lifted call over a $ value types unknown at load (a list or a scalar - nobody knows
        // until the run), yet a REPORTED binding of it keeps the scalar function's declared row as
        // its type source - never "is reported but has no type source" (PLAN-elementwise-list-
        // functions §7.6, the reported-binding hazard)
        assertEquals(List.of("[1, 2]", "[3]"),
                rendered("len($u)", "$u", "split_by(X, \",\")").subList(0, 2));
        assertEquals(List.of("4", "2", "1"), rendered("len($a)", "$a", "A"));
        assertEquals(List.of("ab", "Cd", "e"), rendered("trim($a)", "$a", "A"));
        assertEquals(List.of("2020-01-15", "2021-03", "2019-12-31T23:59"),
                rendered("date($a)", "$a", "D1"));
        assertEquals(List.of("[1, 2]", "[1, 2]", "[1, 2]"),
                rendered("num($n)", "$n", "[\"1\", \"2\"]"));
        // R5 B-LOW-1: an alias chain over such a binding keeps the root's type source - with
        // $u of unknown type (an extract_metadata key no table pins), $v: $b, $b: $a, $a: len($u)
        // loads and renders as len($u) does; before, the chain's own root ($a, a reference)
        // declared nothing and the reported $v was refused ("has no type source")
        assertEquals(rendered("len($u)", "$u", "extract_metadata(\"foo\")"),
                rendered("$b", "$u", "extract_metadata(\"foo\")", "$a", "len($u)", "$b", "$a"));
    }


    @Test
    void aLiftedNumericListRendersCanonicallyWhateverProducedIt()
    {
        // runbook W2 fold, R5 B-MED-2: a lifted numeric list carries TYPED cells, so it renders
        // as a dataset-level number does - "10", never "10.0" - whichever lift case built it
        // case 3, a dataset-level list: each element as the scalar call over it renders
        assertEquals(pairs(rendered("abs(-10)"), rendered("abs(3)")),
                rendered("abs(split_by(\"-10,3\", \",\"))"));
        assertEquals(List.of("[1, 2.5]", "[1, 2.5]", "[1, 2.5]"),
                rendered("abs($n)", "$n", "[1.0, 2.5]"));
        // case 4, a per-row list (NL: "-10,3" / "2.5" / "4")
        assertEquals(List.of("[10, 3]", "[2.5]", "[4]"), rendered("abs(split_by(NL, \",\"))"));
        assertEquals(List.of("[-10, 3]", "[2.5]", "[4]"),
                rendered("num($n)", "$n", "split_by(NL, \",\")"));
        // case 2, values([X, Y]) (N1: -1.5 / 2.25 / 3.0, N2: 0.5 / -7.75 / 10.0) - and case 2 and
        // case 3 render one list identically
        assertEquals(List.of("[1.5, 0.5]", "[2.25, 7.75]", "[3, 10]"),
                rendered("abs(values([N1, N2]))"));
        assertEquals(rendered("abs(values([N1, N2]))").get(2),
                rendered("abs($n)", "$n", "[3.0, 10]").get(0));
        assertEquals(List.of("[-2, 0]", "[2, -8]", "[3, 10]"), rendered("floor(values([N1, N2]))"));
    }


    @Test
    void aNumberResultLiftedProbeComparesEachElementNumerically()
    {
        // runbook W2 fold, R5 B-MED-1: a lifted call whose scalar result is a NUMBER takes the
        // numeric any-element arm against an all-numeric literal, as the scalar call does - not
        // only num(): abs($n) over a list fires like abs(-10) in [10]
        assertEquals(fired("abs(-10) in [10]"),
                fired("abs($n) in [10]", "$n", "split_by(\"-10,3\", \",\")"));
        assertEquals(List.of(1L, 2L, 3L),
                fired("abs($n) in [10]", "$n", "split_by(\"-10,3\", \",\")"));
        // a statically list-valued probe: row 3 holds 10 (N2 = 10.0); 10.0 and 10 are one number
        assertEquals(List.of(3L), fired("abs(values([N1, N2])) in [10]"));
        assertEquals(List.of(3L), fired("abs(values([N1, N2])) in [10.0]"));
        assertEquals(List.of(1L, 2L), fired("abs(values([N1, N2])) not in [10]"));
        assertEquals(List.of(2L), fired("round(values([N1, N2])) in [2.0]"));
        // a per-row list (NL: "-10,3" / "2.5" / "4")
        assertEquals(List.of(2L), fired("abs(split_by(NL, \",\")) in [2.50]"));
        // the numeric arm is =='s, tolerance included (D9): 16.99999999997 is a member of [17]
        // as abs(-16.99999999997) in [17] says - its text "16.99999999997" never is
        assertEquals(fired("abs(-16.99999999997) in [17]"),
                fired("abs($n) in [17]", "$n", "split_by(\"-16.99999999997,3\", \",\")"));
        assertEquals(List.of(1L, 2L, 3L),
                fired("abs($n) in [17]", "$n", "split_by(\"-16.99999999997,3\", \",\")"));
    }

    // ------------------------------------------------------------------ membership


    @Test
    void aLiftedListProbeAsksWhetherAnyElementIsAMember()
    {
        // D81d: a statically list-valued probe - a lift over values([X, Y]) - is the list-LHS
        // shape, any element a member; before, the list's rendering was the one probe
        // trim: "ab" / "x y" (row 1), "Cd" / "z" (row 2), "e" / "fgh" (row 3)
        assertEquals(List.of(1L, 2L), fired("trim(values([A, B])) in [\"ab\", \"z\"]"));
        assertEquals(List.of(3L), fired("trim(values([A, B])) not in [\"ab\", \"z\"]"));
        // as the per-row decision of a lift over a $ value (BS4's probe), row by row
        assertEquals(List.of(1L), fired("len($u) in [2]", "$u", "split_by(X, \",\")"));
    }


    @Test
    void aTemporalListProbeComparesEachElementByTheHullRule()
    {
        // the LIST half of an element-wise date probe is temporal (D81, after P-W2a): each
        // element is compared by the hull rule, exactly as the scalar probe - 2020-01-01 is the
        // day of 2020-01-01T09:15, which the TEXTUAL any-element arm answers false
        String[] split =
        {
                "$s", "split_by(Y, \",\")"
        };
        // Y: {1999-01-01, 2020-01-01} / {2020-01-15} / {2020-02-29T23:00}
        assertEquals(List.of(1L), fired("date($s) in [date(\"2020-01-01T09:15\")]", split));
        assertEquals(List.of(2L, 3L), fired("date($s) not in [date(\"2020-01-01T09:15\")]", split));
        // a value set: date() over a $-list of strings (a list binding holds plain literals),
        // read on its value (TM1 / BS4) and compared member by member by the hull rule
        String[] withSet =
        {
                "$s", "split_by(Y, \",\")", "$d", "[\"2020-01-01T09:15\", \"2020-02-29\"]"
        };
        assertEquals(List.of(1L, 3L), fired("date($s) in date($d)", withSet));
        // a per-row member set too: D1 / D2 as the row's members
        assertEquals(List.of(1L), fired("date($s) in values([D1, D2])", "$s",
                "split_by(\"2020-01-15T10:00,1900\", \",\")"));
        // and a statically list-valued probe
        assertEquals(List.of(3L), fired("date(values([D1, D2])) in [date(\"2018-06-01T08:00\")]"));
        assertEquals(List.of(1L, 3L),
                fired("time(values([T1, T2])) in [time(\"09:15:00\"), time(\"06:45\")]"));
    }
}
