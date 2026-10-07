package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.KeyedJoinFixtures;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;
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
     * primary TRT01P column for the non-binding controls, whose cells NAME other columns (one
     * present, one absent): a binding over TRT01P read as a per-row name would answer row by row,
     * the reference written inline answers once for the column.
     */
    private static IDataTable adae()
    {
        return RealTables.of("ADAE").str("USUBJID", "S1", "S1", "S2", "S1")
                .dbl("APERIOD", 1.0, 2.0, MissingValue.MIS.asDouble(), 1.5)
                .str("TRT01P", "APERIOD", "ZZZ", "USUBJID", "ZZZ").build();
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
        // Rows 3 and 4 coincide above (both leave $n missing); a DRIVER guard tells them apart:
        // a non-integral period passes `not empty(APERIOD)` and still names nothing (the PMDA
        // spelling's arm, CDISC-AD0103's Q1), so only the NAME guard silences row 4.
        assertEquals(List.of(2L, 4L),
                fired("not empty(APERIOD) and not var_exists($n)", "$n", NAME),
                "a missing driver (row 3) is guarded by the driver; a non-integral one (row 4)"
                        + " is not");
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
        // ⚑ A bare column binding ($c: APERIOD) is a NAME FORM and answers as var_exists(APERIOD)
        // written inline (aNameFormBindingIsTheInlineSpelling); a column read per row is spelled
        // colref(…), bound or inline.
        assertEquals(List.of(), fired("var_exists($c)", "$c", "colref(\"APERIOD\")"));
        assertEquals(List.of(), fired("var_not_exists($c)", "$c", "colref(\"APERIOD\")"));
        assertEquals(fired("var_not_exists(colref(\"APERIOD\"))"),
                fired("var_not_exists($c)", "$c", "colref(\"APERIOD\")"));
        assertEquals(fired("var_not_exists(concat(\"\", USUBJID))"),
                fired("var_not_exists($c)", "$c", "concat(\"\", USUBJID)"),
                "a string cell is a name: S1 / S2 are no columns of ADAE");
    }

    // ------------------------------------------------------------------ name-form bindings
    // (T2 review of the S2 round, MEDIUM-2)

    /**
     * The three probe shapes every name-form kind is compared under: both polarities, and the
     * {@code not} that flips an unset row.
     */
    private static final List<String> PROBES = List.of("var_exists(%s)", "var_not_exists(%s)",
            "not var_exists(%s)");

    /**
     * Name-form binding sources and the rows {@code var_exists} fires for them: a binding whose
     * expression is a form the inline literal path handles — a bare column, a string literal, a
     * dotted {@code DS.X}, a {@code ${…}} template literal, a binding of such a binding — answers
     * as that name written inline, present and absent alike.
     */
    private static final List<List<Object>> NAME_FORMS = List.of(
            List.of("TRT01P", List.of(1L, 2L, 3L, 4L)), List.of("TRT09P", List.of()),
            List.of("\"TRT01P\"", List.of(1L, 2L, 3L, 4L)), List.of("\"ZZZ\"", List.of()),
            List.of("ADSL.TRT01P", List.of(1L, 2L, 3L, 4L)), List.of("ADSL.TRT09P", List.of()),
            List.of("\"ADSL.TRT01P\"", List.of(1L, 2L, 3L, 4L)),
            // the ${…} template truncates a non-integral driver (row 4: 1.5 → TRT01P, Q1)
            List.of("\"TRT${APERIOD:%02d}P\"", List.of(1L, 4L)));

    /**
     * D3 / D16 ({@code PLAN-binding-substitutability} G1): {@code $x: <name form>} then
     * {@code var_exists($x)} is exactly {@code var_exists(<name form>)} — the literal path, the
     * dataset-level fact. Before, every binding took the computed path: {@code $c: TRT01P} read
     * TRT01P's cells as names (rows 1 and 3 name present columns), an absent column or a dotted
     * reference read missing cells and left every row unset.
     */
    @Test
    void aNameFormBindingIsTheInlineSpelling()
    {
        List<String> disagreements = new ArrayList<>();
        for (List<Object> form : NAME_FORMS)
        {
            String source = (String) form.get(0);
            assertEquals(form.get(1), fired("var_exists(" + source + ")"), "inline " + source);
            for (String probe : PROBES)
            {
                agree(disagreements, probe.formatted("$x") + " with $x: " + source,
                        fired(probe.formatted(source)), fired(probe.formatted("$x"), "$x", source));
            }
        }
        assertEquals(List.of(), disagreements);
    }


    /** A binding of a name-form binding is followed to the name, as stage A follows one. */
    @Test
    void aBindingOfANameFormBindingIsFollowed()
    {
        for (String probe : PROBES)
        {
            assertEquals(fired(probe.formatted("TRT01P")),
                    fired(probe.formatted("$b"), "$a", "TRT01P", "$b", "$a"), probe);
            assertEquals(fired(probe.formatted("ADSL.TRT09P")),
                    fired(probe.formatted("$b"), "$a", "ADSL.TRT09P", "$b", "$a"), probe);
        }
    }


    /**
     * The {@code --} literal: specialised for the dataset (the bind-time D77 pass,
     * {@link RuleSpecialiser}; the runner applies it for the run's domain code) it is the domain's
     * column, bound or inline. With no domain code nothing can specialise it, and the inline name
     * meets the evaluator's D77b refusal — the bound one now does too, where the computed path read
     * it as an absent name (silently: no rule error).
     */
    @Test
    void aDomainPrefixLiteralBindingIsTheInlineSpelling()
    {
        IDataTable ae = RealTables.of("AE").str("USUBJID", "S1", "S2").dbl("AESEQ", 1.0, 2.0)
                .build();
        List<String> disagreements = new ArrayList<>();
        for (String probe : PROBES)
        {
            Rule inline = load(
                    json("not empty(USUBJID) and " + probe.formatted("\"--SEQ\""), "Record"));
            Rule bound = load(json("not empty(USUBJID) and " + probe.formatted("$n"), "Record",
                    "$n", "\"--SEQ\""));
            agree(disagreements, "specialiser: " + probe,
                    outcome(RuleSpecialiser.specialise(inline, ae, "AE"), ae, "AE"),
                    outcome(RuleSpecialiser.specialise(bound, ae, "AE"), ae, "AE"));
            agree(disagreements, "run for AE: " + probe, outcome(inline, ae, "AE"),
                    outcome(bound, ae, "AE"));
            agree(disagreements, "no domain code: " + probe, refusal(inline, ae),
                    refusal(bound, ae));
        }
        assertEquals(List.of(), disagreements);
        Rule exists = load(
                json("not empty(USUBJID) and var_exists($n)", "Record", "$n", "\"--SEQ\""));
        assertEquals("EXECUTED [1, 2]", outcome(exists, ae, "AE"), "AESEQ exists");
        assertTrue(refusal(exists, ae).contains("D77b"), refusal(exists, ae));
    }


    /** The evaluator's refusal of an unspecialised {@code --} name, run with no domain code. */
    private static String refusal(Rule rule, IDataTable table)
    {
        return String.valueOf(assertThrows(net.cumba.corej.core.expr.ExpressionException.class,
                () -> outcome(rule, table, null)).getMessage());
    }


    /** The status and fired rows of {@code rule} over {@code table} (ADSL beside it). */
    private static String outcome(@Nullable Rule rule, IDataTable table, @Nullable String domain)
    {
        assertNotNull(rule);
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table,
                RealTables.inventoryOf(table, adsl()), domain, null);
        List<Long> fired = new ArrayList<>();
        result.getViolations().forEach(v -> fired.add(v.getRowNumber()));
        return result.getStatus() + " " + fired;
    }


    /**
     * The four readers agree for EVERY binding kind: the classifier's Sensitivity, stage A's level,
     * the cached evaluation domain the runner dispatches on, and the rows reported — a name form at
     * the dataset level of its inline reference, a computed name at the record level of its inline
     * expression.
     */
    @Test
    void classifierStageAAndRuntimeAgreeForEveryBindingKind()
    {
        List<String> disagreements = new ArrayList<>();
        List<String> sources = new ArrayList<>();
        NAME_FORMS.forEach(f -> sources.add((String) f.get(0)));
        sources.add(NAME);
        sources.add("colref(\"TRT01P\")");
        for (String source : sources)
        {
            for (String probe : List.of("var_exists(%s)", "var_not_exists(%s)"))
            {
                Rule inline = load(json(probe.formatted(source), null));
                Rule bound = load(json(probe.formatted("$x"), null, "$x", source));
                String what = probe.formatted("$x") + " with $x: " + source;
                assertNull(bound.getLoadError(), bound.getLoadError());
                agree(disagreements, "classifier: " + what,
                        RuleClassifier.deriveSensitivity(inline).value(),
                        RuleClassifier.deriveSensitivity(bound).value());
                agree(disagreements, "stage A: " + what,
                        stageALevel(inline, probe.formatted(source)),
                        stageALevel(bound, probe.formatted("$x")));
                agree(disagreements, "evaluation domain: " + what, inline.getEvaluationDomain(),
                        bound.getEvaluationDomain());
                agree(disagreements, "rows: " + what, rows(json(probe.formatted(source), null)),
                        rows(json(probe.formatted("$x"), null, "$x", source)));
            }
        }
        assertEquals(List.of(), disagreements);
    }


    /** Records {@code what} when the inline and the bound reading differ. */
    private static void agree(List<String> disagreements, String what, @Nullable Object inline,
            @Nullable Object bound)
    {
        if (!java.util.Objects.equals(inline, bound))
        {
            disagreements.add(what + ": bound " + bound + ", inline " + inline);
        }
    }


    /** Stage A's level of {@code check} judged against {@code rule}'s bindings. */
    private static net.cumba.corej.core.expr.typed.Level stageALevel(Rule rule, String check)
    {
        java.util.SequencedMap<net.cumba.datatable.report.Severity, net.cumba.corej.core.expr.ast.Expr> levels = new java.util.LinkedHashMap<>();
        levels.put(net.cumba.datatable.report.Severity.ERROR,
                net.cumba.corej.core.expr.CheckExpressionParser.parse(check));
        return net.cumba.corej.core.expr.typed.StageAChecker.check(rule, levels).typedLevels()
                .get(net.cumba.datatable.report.Severity.ERROR).level();
    }


    /**
     * The rewrite itself ({@code BindingInliner.inlineNameSlotBindings}): it moves a name form into
     * a name slot — through a binding of a binding, the {@code varname()} cursor included, and
     * since {@code PLAN-string-literal-prefix-rewrite} S8 at every name slot ({@code ds_exists}
     * too) — and nothing else: a computed name, a number (the {@code PARAMETER_TYPE} path) and an
     * ordinary read of the binding stay as written, the very same instance.
     */
    @Test
    void theRewriteMovesOnlyNameForms()
    {
        java.util.Map<String, net.cumba.corej.core.expr.ast.Expr> bound = new java.util.LinkedHashMap<>();
        bound.put("$v", parse("varname()"));
        bound.put("$n", parse(NAME));
        bound.put("$k", parse("3"));
        bound.put("$c", parse("TRT01P"));
        bound.put("$d", parse("$c"));
        assertEquals(parse("var_exists(varname())"),
                net.cumba.corej.core.expr.convert.BindingInliner
                        .inlineNameSlotBindings(parse("var_exists($v)"), bound));
        assertEquals(parse("not var_not_exists(TRT01P) and ds_exists(TRT01P)"),
                net.cumba.corej.core.expr.convert.BindingInliner.inlineNameSlotBindings(
                        parse("not var_not_exists($d) and ds_exists($c)"), bound));
        net.cumba.corej.core.expr.ast.Expr unchanged = parse(
                "var_exists($n) or var_not_exists($k) or $c == \"X\" or var_exists($unknown)");
        assertSame(unchanged, net.cumba.corej.core.expr.convert.BindingInliner
                .inlineNameSlotBindings(unchanged, bound));
    }


    /**
     * A binding's own expression is rewritten against the bindings authored BEFORE it only, so a
     * forward reference stays a {@code $}-reference and still meets stage A's armed order check.
     */
    @Test
    void aForwardReferenceIsStillALoadError()
    {
        String error = load(json("$b == true", "Record", "$b", "var_exists($a)", "$a", "TRT01P"))
                .getLoadError();
        assertNotNull(error);
        assertTrue(error.contains("references $a, which is declared later"), error);
    }


    private static net.cumba.corej.core.expr.ast.Expr parse(String expression)
    {
        return net.cumba.corej.core.expr.CheckExpressionParser.parse(expression);
    }
}
