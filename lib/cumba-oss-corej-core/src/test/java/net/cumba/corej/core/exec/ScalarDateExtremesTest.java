package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.eval.ColumnTypeGate;
import net.cumba.corej.core.expr.eval.ColumnVector;
import net.cumba.corej.core.expr.eval.ComputedVector;
import net.cumba.corej.core.expr.eval.EvalRun;
import net.cumba.corej.core.expr.eval.ExprCompiler;
import net.cumba.corej.core.expr.eval.TypedValue;
import net.cumba.corej.core.expr.eval.Vector;
import net.cumba.corej.core.expr.typed.ExprType.Primitive;
import net.cumba.corej.core.expr.typed.StageAChecker;
import net.cumba.corej.core.expr.typed.StageAErrorKind;
import net.cumba.corej.core.expr.typed.StageAFinding;
import net.cumba.corej.core.expr.typed.StageAReport;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.values.DataValueMissing;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * {@code earliest_date(a, b)} / {@code latest_date(a, b)} ({@code PLAN-scalar-date-extremes},
 * {@link ScalarDateExtremes}): S1 arity and D19a name binding, S3 types, S4 candidates, S5 the
 * answer and the winning cell, S6 the no-answer identities, S9 a missing answer fires {@code !=} —
 * and the identity claim, that over every ordered pair of a fixed shape set the pair extreme equals
 * {@code min_date} / {@code max_date} over a two-row group holding the same two cells.
 * Mockito-free: real tables ({@link RealTableFixture}; an {@link OverlayDataTable} for the cells
 * that must carry a {@code .A} / {@code .B}), the real loader and {@link RuleRunner}.
 */
class ScalarDateExtremesTest
{

    private static final String EARLIEST = "earliest_date(A, B)";

    private static final String LATEST = "latest_date(A, B)";

    // ============================================================ fixtures

    /**
     * A two-column {@code STRING} table {@code T(A, B)} whose cells are {@link String}s or
     * {@link MissingValue}s — the one shape that can carry a {@code .A} into a character column
     * ({@code DataBufferString} stores {@code toString()}).
     */
    private static IDataTable pairs(Object[] a, Object[] b)
    {
        OverlayDataTable t = OverlayDataTable.empty("T", "T", a.length);
        t.addColumn("K", DataValueType.STRING, "K");
        t.addColumn("A", DataValueType.STRING, "A");
        t.addColumn("B", DataValueType.STRING, "B");
        for (int r = 0; r < a.length; r++)
        {
            t.setValue(r, 0, "K" + r);
            put(t, r, 1, a[r]);
            put(t, r, 2, b[r]);
        }
        return t;
    }


    private static void put(OverlayDataTable t, int row, int col, Object cell)
    {
        if (cell instanceof MissingValue mv)
        {
            t.setDataValue(row, col, new DataValueMissing(mv));
        }
        else
        {
            t.setValue(row, col, cell);
        }
    }


    private static EvaluationContext ctx(IDataTable table, IDataTable... others)
    {
        DatasetResolver resolver = name ->
        {
            for (IDataTable o : others)
            {
                if (o.getMetaData().getName().equalsIgnoreCase(name))
                {
                    return o;
                }
            }
            return null;
        };
        return EvaluationContext.builder().table(table).datasetResolver(resolver).build();
    }


    /** Evaluates {@code expression} as a value over {@code table} and answers the vector. */
    private static Vector eval(String expression, IDataTable table, IDataTable... others)
    {
        Vector v = ExprCompiler.evaluateValueExpression(CheckExpressionParser.parse(expression),
                ctx(table, others));
        assertNotNull(v, expression);
        return v;
    }


    private static Vector column(IDataTable table, String name)
    {
        int idx = table.getMetaData().getColumnIndex(name);
        return new ColumnVector(name, table.getColumn(idx),
                table.getMetaData().getColumn(idx).getType());
    }


    /** The row's answer as text, or {@code null} for a missing. */
    private static @Nullable String text(Vector v, int row)
    {
        TypedValue tv = v.value(row);
        return tv.missing() != null ? null : tv.cell().getValueAsString();
    }


    private static @Nullable MissingValue missing(Vector v, int row)
    {
        return v.value(row).missing();
    }


    /** One rule with the given bindings (JSON array body) and Check, through the real loader. */
    private static Rule load(String bindingsJson, String check)
    {
        try
        {
            RulePackage pkg = RulePackageLoader.loadFromString(
                    "{\"rules\":{\"SDE-1\":{" + "\"Core\":{\"Id\":\"SDE-1\"},\"Bindings\":["
                            + bindingsJson + "]," + "\"Check\":{\"expression\":\"" + check + "\"},"
                            + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"K\"]}}}}");
            return pkg.getRules().get("SDE-1");
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + check, e);
        }
    }


    private static Rule loaded(String bindingsJson, String check)
    {
        Rule rule = load(bindingsJson, check);
        assertNull(rule.getLoadError(), "expected the rule to load: " + rule.getLoadError());
        return rule;
    }


    private static String binding(String name, String expression)
    {
        return "{\"name\":\"" + name + "\",\"expression\":\"" + expression + "\"}";
    }


    private static int fires(String bindingsJson, String check, IDataTable primary,
            IDataTable... others)
    {
        RuleExecutionResult result = RuleRunnerCalls.execute(loaded(bindingsJson, check), primary,
                ctx(primary, others).getDatasetResolver());
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        return result.getViolations().size();
    }

    // ============================================================ S5 — the answer


    @Test
    void completeDatesAnswerTheEarlierOrLaterCellInEitherOrderAndAOnATie()
    {
        IDataTable t = pairs(new Object[]
        {
                "2020-01-02", "2020-01-01", "2020-01-01"
        }, new Object[]
        {
                "2020-01-01", "2020-01-02", "2020-01-01"
        });
        Vector e = eval(EARLIEST, t);
        Vector l = eval(LATEST, t);
        assertAll(() -> assertEquals("2020-01-01", text(e, 0)),
                () -> assertEquals("2020-01-01", text(e, 1)),
                () -> assertEquals("2020-01-02", text(l, 0)),
                () -> assertEquals("2020-01-02", text(l, 1)),
                () -> assertEquals("2020-01-01", text(e, 2)),
                () -> assertEquals("2020-01-01", text(l, 2)));
    }


    /**
     * A vector over the column's cells fetched ONCE — a column hands out a fresh wrapper per read,
     * a typed producer hands its cell through ({@code TypedValue.cell()}), so identity is testable.
     */
    private static Vector fixed(IDataTable table, String column, List<IDataValue> cells)
    {
        int idx = table.getMetaData().getColumnIndex(column);
        for (int r = 0; r < table.getRowCount(); r++)
        {
            cells.add(table.getColumn(idx).getDataValue(r));
        }
        return ComputedVector.typed((int) table.getRowCount(), DataValueType.STRING, cells::get);
    }


    @Test
    void theReturnedObjectIsTheWinningInputCellAndAOnATie()
    {
        // S5: the winning CELL is handed back — no copy — and on equal text a's cell, not b's.
        IDataTable t = RealTableFixture.of("T").str("A", "2020-01-05", "2020-01-01", "2020-01-01")
                .str("B", "2020-01-01", "2020-01-05", "2020-01-01").build();
        List<IDataValue> as = new ArrayList<>();
        List<IDataValue> bs = new ArrayList<>();
        Vector a = fixed(t, "A", as);
        Vector b = fixed(t, "B", bs);
        EvalRun run = EvalRun.fullRange(ctx(t));
        Vector e = ScalarDateExtremes.earliest(run, List.of(a, b));
        Vector l = ScalarDateExtremes.latest(run, List.of(a, b));
        assertAll(() -> assertSame(bs.get(0), e.value(0).cell(), "row 0: b"),
                () -> assertSame(as.get(1), e.value(1).cell(), "row 1: a"),
                () -> assertSame(as.get(0), l.value(0).cell(), "row 0: a"),
                () -> assertSame(bs.get(1), l.value(1).cell(), "row 1: b"),
                () -> assertSame(as.get(2), e.value(2).cell(), "the tie answers a's cell"),
                () -> assertSame(as.get(2), l.value(2).cell()));
    }


    @Test
    void ec46APartialBesideACompleteDateIsDeterminableOnlyWhenTheCompleteOneReachesItsHull()
    {
        // OQ1: min{2012-06, 2012-06-01} is the 1st (the partial cannot precede it); min{2012-06,
        // 2012-06-02} has no answer (the partial could be the 1st); max{2012-06, 2012-06-30} is
        // the 30th; two partials have no answer.
        IDataTable t = pairs(new Object[]
        {
                "2012-06", "2012-06", "2012-06", "2012-06"
        }, new Object[]
        {
                "2012-06-01", "2012-06-02", "2012-06-30", "2012-07"
        });
        Vector e = eval(EARLIEST, t);
        Vector l = eval(LATEST, t);
        assertAll(() -> assertEquals("2012-06-01", text(e, 0)),
                () -> assertEquals(MissingValue.MIS, missing(e, 1), "the partial could be the 1st"),
                () -> assertEquals(MissingValue.MIS, missing(l, 0)),
                () -> assertEquals("2012-06-30", text(l, 2)),
                () -> assertEquals(MissingValue.MIS, missing(e, 3), "two partials"),
                () -> assertEquals(MissingValue.MIS, missing(l, 3)));
    }


    @Test
    void aDateBesideADatetimeAnswersByTheirHullsAndAnOffsetIsNormalised()
    {
        // 2020-01-01 spans its whole day: earliest of (2020-01-01, 2020-01-01T08:00) is the DATE
        // (its hull starts at 00:00) and so is the latest (its hull ends at 23:59:59). D25: an
        // offset is normalised before the comparison — 08:00+02:00 is 06:00Z, earlier than 07:00Z.
        IDataTable t = pairs(new Object[]
        {
                "2020-01-01", "2020-01-01T08:00+02:00"
        }, new Object[]
        {
                "2020-01-01T08:00", "2020-01-01T07:00Z"
        });
        Vector e = eval(EARLIEST, t);
        Vector l = eval(LATEST, t);
        assertAll(() -> assertEquals("2020-01-01", text(e, 0)),
                () -> assertEquals("2020-01-01", text(l, 0)),
                () -> assertEquals("2020-01-01T08:00+02:00", text(e, 1)),
                () -> assertEquals("2020-01-01T07:00Z", text(l, 1)));
    }


    @Test
    void aPresentUnpositionableValueMakesTheAnswerUndeterminable()
    {
        // EC-46 OQ6 / OQ7: UNK and a calendar-impossible day are present but cannot be placed, so
        // no determined candidate can reach them — unlike a missing, which is skipped (below).
        IDataTable t = pairs(new Object[]
        {
                "UNK", "2020-02-30", "2020-01-01"
        }, new Object[]
        {
                "2020-01-01", "2020-01-01", "----06-15"
        });
        Vector e = eval(EARLIEST, t);
        Vector l = eval(LATEST, t);
        for (int r = 0; r < 3; r++)
        {
            assertEquals(MissingValue.MIS, missing(e, r), "row " + r);
            assertEquals(MissingValue.MIS, missing(l, r), "row " + r);
        }
    }

    // ============================================================ S4 / S6 — candidates and no
    // answer


    @Test
    void aBlankOrWhitespaceOnlyArgumentIsSkippedAndTwoBlanksAnswerTheComputedMissing()
    {
        IDataTable t = pairs(new Object[]
        {
                "", " ", " ", ""
        }, new Object[]
        {
                "2020-01-01", "2020-01-01", "2020-01-01", " "
        });
        Vector e = eval(EARLIEST, t);
        Vector l = eval(LATEST, t);
        assertAll(() -> assertEquals("2020-01-01", text(e, 0), "\"\" is no candidate"),
                () -> assertEquals("2020-01-01", text(e, 1), "whitespace is no candidate"),
                () -> assertEquals("2020-01-01", text(e, 2), "NBSP is no candidate"),
                () -> assertEquals("2020-01-01", text(l, 0)),
                () -> assertEquals(MissingValue.MIS, missing(e, 3), "blank only: computed MIS"),
                () -> assertEquals(MissingValue.MIS, missing(l, 3)));
    }


    @Test
    void aMissingArgumentIsSkippedNotPropagatedAndTwoMissingsCarryTheirIdentity()
    {
        // The extremes' rule (S4, D36), not the value functions' propagation: .A beside a date is
        // the date. Both missing: one identity is handed through (D85c), two collapse to MIS
        // (D86a) — also beside the computed MIS and beside a present "" (S6).
        Object[] a =
        {
                MissingValue.MIS_A, MissingValue.MIS_A, MissingValue.MIS_A, MissingValue.MIS_A,
                MissingValue.MIS_A, MissingValue.MIS
        };
        Object[] b =
        {
                "2020-01-01", MissingValue.MIS_A, MissingValue.MIS_B, MissingValue.MIS, "",
                MissingValue.MIS
        };
        IDataTable t = pairs(a, b);
        Vector e = eval(EARLIEST, t);
        Vector l = eval(LATEST, t);
        assertAll(() -> assertEquals("2020-01-01", text(e, 0), ".A + present: the present"),
                () -> assertEquals("2020-01-01", text(l, 0)),
                () -> assertEquals(MissingValue.MIS_A, missing(e, 1), ".A + .A: .A"),
                () -> assertEquals(MissingValue.MIS_A, missing(l, 1), ".A + .A: .A (latest too)"),
                () -> assertEquals(MissingValue.MIS, missing(e, 2), ".A + .B: MIS"),
                () -> assertEquals(MissingValue.MIS, missing(e, 3),
                        ".A + computed MIS: MIS (D86a)"),
                () -> assertEquals(MissingValue.MIS, missing(e, 4), ".A + \"\": the computed MIS"),
                () -> assertEquals(MissingValue.MIS, missing(l, 4)),
                () -> assertEquals(MissingValue.MIS, missing(e, 5)));
    }


    @Test
    void aMissingGroupedExtremeIsSkippedSoTheOtherPartDecides()
    {
        // The corpus shape: two grouped extremes over EX — an absent pool part (every non-pooled
        // subject) or an undeterminable one (S7, SDE D2 (a)) is the computed MIS and is skipped.
        IDataTable dm = RealTableFixture.of("DM").str("USUBJID", "S1", "S2", "S3").build();
        IDataTable ex = RealTableFixture.of("EX").str("USUBJID", "S1", "S1", "S2", "", "S3", "")
                .str("POOLID", "", "", "", "P1", "", "P3").str("EXSTDTC", "2020-01-05",
                        "2020-01-03", "2020-01-04", "2020-01-01", "2020-01", "2020-02-01")
                .build();
        IDataTable pooldef = RealTableFixture.of("POOLDEF").str("POOLID", "P1", "P3")
                .str("USUBJID", "S1", "S3").build();
        String own = "min_date(EXSTDTC, domain=\\\"EX\\\", group=[USUBJID])";
        String pool = "min_date(min_date(EXSTDTC, domain=\\\"EX\\\", group=[POOLID], filter=(not empty(POOLID))), domain=\\\"POOLDEF\\\", group=[USUBJID])";
        String bindings = binding("$own", own) + "," + binding("$pool", pool) + ","
                + binding("$first", "earliest_date($own, $pool)");
        assertEquals(1, fires(bindings, "$first == date(\\\"2020-01-01\\\")", dm, ex, pooldef),
                "S1: the pool's 2020-01-01 beats the own 2020-01-03");
        assertEquals(1, fires(bindings, "$first == date(\\\"2020-01-04\\\")", dm, ex, pooldef),
                "S2 is in no pool: the pool part is MIS and is skipped");
        assertEquals(1, fires(bindings, "$first == date(\\\"2020-02-01\\\")", dm, ex, pooldef),
                "S3's own 2020-01 is undeterminable (MIS): the pool part decides (D2 (a))");
    }


    @Test
    void aMissingAnswerFiresANotEqualComparison()
    {
        // S9 / EC-45: a derived value that cannot be derived compared with `!=` is reported.
        IDataTable t = pairs(new Object[]
        {
                "", "2020-01-01"
        }, new Object[]
        {
                "", "2020-01-01"
        });
        assertEquals(1, fires("", "date(\\\"2020-01-01\\\") != earliest_date(A, B)", t),
                "row 0 (no answer) fires, row 1 (equal) does not");
        assertEquals(1, fires("", "date(\\\"2020-01-01\\\") != latest_date(A, B)", t));
    }

    // ============================================================ S1 / S3 — arity, names, types


    @Test
    void exactlyTwoArgumentsPositionalOrByName()
    {
        IDataTable t = pairs(new Object[]
        {
                "2020-01-05"
        }, new Object[]
        {
                "2020-01-01"
        });
        // D19a: bound by name in either spelling, answering as the positional call does.
        assertEquals("2020-01-01", text(eval("earliest_date(A, b=B)", t), 0));
        assertEquals("2020-01-01", text(eval("earliest_date(a=A, b=B)", t), 0));
        assertEquals("2020-01-05", text(eval("latest_date(b=B, a=A)", t), 0));
        // The binder's errors (D19a) are load errors: one argument, three, a parameter bound twice,
        // an unknown name.
        for (String bad : List.of("earliest_date(A)", "earliest_date(A, B, A)",
                "earliest_date(A, a=B)", "latest_date(A, c=B)"))
        {
            Rule rule = load("", "empty(" + bad + ")");
            assertNotNull(rule.getLoadError(), bad + " must not load");
        }
        assertTrue(load("", "empty(earliest_date(A))").getLoadError()
                .contains("requires argument 'b'"));
        assertTrue(load("", "empty(earliest_date(A, B, A))").getLoadError()
                .contains("accepts at most 2 argument(s)"));
        assertTrue(load("", "empty(earliest_date(A, a=B))").getLoadError()
                .contains("already bound by position 1"));
        assertTrue(load("", "empty(latest_date(A, c=B))").getLoadError()
                .contains("has no parameter 'c'"));
    }


    @Test
    void theParametersAndTheResultAreDatesInStageA()
    {
        // S3: DATE in, DATE out — a DATE-typed binding meets the parameter; a string literal is
        // the PARAMETER_TYPE finding (write date("…")); the result types DATE like min_date, so
        // an untagged string literal beside it is the §5.2 mixed pair.
        Rule rule = new Rule();
        rule.setCompiledBindings(List.of(new CompiledBinding("$own",
                CheckExpressionParser.parse("min_date(EXSTDTC, domain=EX, group=[USUBJID])"),
                List.of(), null)));
        StageAReport typed = StageAChecker.check(rule,
                levels("date(RFXSTDTC) != earliest_date($own, EXSTDTC)"));
        assertEquals(List.of(), typed.findings());
        assertEquals(Primitive.DATE,
                typed.typedLevels().get(Severity.ERROR).children().get(1).type());
        StageAReport literal = StageAChecker.check(new Rule(),
                levels("empty(latest_date(A, \"2012-06-15\"))"));
        assertEquals(List.of(StageAErrorKind.PARAMETER_TYPE),
                literal.findings().stream().map(StageAFinding::kind).distinct().toList());
        assertEquals(List.of(),
                StageAChecker
                        .check(new Rule(), levels("empty(latest_date(A, date(\"2012-06-15\")))"))
                        .findings());
        StageAReport mixed = StageAChecker.check(new Rule(),
                levels("earliest_date(A, B) == \"2012-06-15\""));
        assertEquals(List.of(StageAErrorKind.MIXED_DATE_STRING_COMPARISON),
                mixed.findings().stream().map(StageAFinding::kind).distinct().toList());
    }


    private static java.util.SequencedMap<Severity, net.cumba.corej.core.expr.ast.Expr> levels(
            String expression)
    {
        java.util.SequencedMap<Severity, net.cumba.corej.core.expr.ast.Expr> levels = new java.util.LinkedHashMap<>();
        levels.put(Severity.ERROR, CheckExpressionParser.parse(expression));
        return levels;
    }


    @Test
    void theDeclaredResultTypeIsTheCommonInputTypeElseStringAndANumBesideADateIsObservedNotRefused()
    {
        IDataTable t = RealTableFixture.of("T").str("A", "2020-01-05").str("B", "2020-01-01")
                .lng("N", 5L).lng("M", 7L).dbl("D", 43_831.0).build();
        EvalRun run = EvalRun.fullRange(ctx(t));
        assertEquals(DataValueType.STRING, ScalarDateExtremes
                .earliest(run, List.of(column(t, "A"), column(t, "B"))).declaredType());
        List<String> seen = new ArrayList<>();
        ColumnTypeGate.setIsoConversionObserver(seen::add);
        try
        {
            Vector longs = ScalarDateExtremes.latest(run, List.of(column(t, "N"), column(t, "M")));
            assertEquals(DataValueType.LONG, longs.declaredType(), "equal input types are kept");
            assertEquals(MissingValue.MIS, missing(longs, 0), "a number is no date: no answer");
            assertEquals(2, observed(seen, "latest_date()"), "both numeric columns observed");
            seen.clear();
            // Unequal types declare STRING and are NOT refused (review A-M1): a number never wins
            // (IsoDateBounds.isDetermined needs a calendar-complete core), so the RowMax trap —
            // a numeric cell under a STRING declaration — cannot arise; the present number makes
            // the pair undeterminable, as min_date over the same two cells. D55: observed.
            Vector mixed = ScalarDateExtremes.earliest(run,
                    List.of(column(t, "A"), column(t, "D")));
            assertEquals(DataValueType.STRING, mixed.declaredType());
            assertEquals(MissingValue.MIS, missing(mixed, 0));
            assertEquals(1, observed(seen, "earliest_date()"), String.valueOf(seen));
            assertTrue(seen.get(0).contains("D is declared Num") && seen.get(0).contains("D55"),
                    seen.get(0));
            seen.clear();
            ScalarDateExtremes.earliest(run, List.of(column(t, "A"), column(t, "B")));
            assertEquals(0, observed(seen, "earliest_date()"), "a character pair is not observed");
        }
        finally
        {
            ColumnTypeGate.setIsoConversionObserver(null);
        }
    }


    @Test
    void aNumericOwnExtremeBesideTheStringPoolPartOfAStudyWithoutPooldefRuns()
    {
        // Review A-M1, the reachable shape: an XLSX EX whose EXSTDTC arrives as an Excel serial
        // types the own extreme DOUBLE; with no POOLDEF the pool part is the STRING computed MIS.
        // The pair must run — the rule answers MIS (a number never wins; EC-45 then reports it) —
        // where a Num/Char refusal ERRORed all four SEND rules for a provider artefact.
        IDataTable dm = RealTableFixture.of("DM").str("USUBJID", "S1").build();
        IDataTable ex = RealTableFixture.of("EX").str("USUBJID", "S1").dbl("EXSTDTC", 43_831.0)
                .str("POOLID", "").build();
        String own = "min_date(EXSTDTC, domain=\\\"EX\\\", group=[USUBJID])";
        String pool = "min_date(min_date(EXSTDTC, domain=\\\"EX\\\", group=[POOLID], filter=(not empty(POOLID))), domain=\\\"POOLDEF\\\", group=[USUBJID])";
        String bindings = binding("$own", own) + "," + binding("$pool", pool) + ","
                + binding("$first", "earliest_date($own, $pool)");
        assertEquals(1, fires(bindings, "empty($first)", dm, ex),
                "the rule runs and the first dose has no answer");
    }


    private static long observed(List<String> seen, String context)
    {
        return seen.stream().filter(m -> m.contains(context)).count();
    }

    // ============================================================ the identity claim

    /** The shape set: complete, partial, timed, offset, unpositionable, blank, missing. */
    private static final Object[] SHAPES =
    {
            "2020-01-01", "2020-01-02", "2019-12-31", "2020", "2020-01", "2020-02",
            "2020-01-01T08:00", "2020-01-01T00:00", "2020-01-01T23:59", "2020-01-02T00:00",
            "2020-01-01T08:00+02:00", "2020-01-01T06:00Z", "UNK", "2020-02-30", "", " ", " ",
            MissingValue.MIS, MissingValue.MIS_A, MissingValue.MIS_B
    };

    @Test
    void thePairExtremeEqualsTheGroupedExtremeOverATwoRowGroupForEveryOrderedPairOfShapes()
    {
        int n = SHAPES.length * SHAPES.length;
        Object[] a = new Object[n];
        Object[] b = new Object[n];
        Object[] key = new Object[2 * n];
        Object[] x = new Object[2 * n];
        int i = 0;
        for (Object sa : SHAPES)
        {
            for (Object sb : SHAPES)
            {
                a[i] = sa;
                b[i] = sb;
                key[2 * i] = "K" + i;
                key[2 * i + 1] = "K" + i;
                x[2 * i] = sa;
                x[2 * i + 1] = sb;
                i++;
            }
        }
        IDataTable dm = pairs(a, b);
        OverlayDataTable ex = OverlayDataTable.empty("EX", "EX", 2 * n);
        ex.addColumn("K", DataValueType.STRING, "K");
        ex.addColumn("X", DataValueType.STRING, "X");
        for (int r = 0; r < 2 * n; r++)
        {
            put(ex, r, 0, key[r]);
            put(ex, r, 1, x[r]);
        }
        Vector e = eval(EARLIEST, dm, ex);
        Vector l = eval(LATEST, dm, ex);
        Vector min = eval("min_date(X, domain=\"EX\", group=[K])", dm, ex);
        Vector max = eval("max_date(X, domain=\"EX\", group=[K])", dm, ex);
        List<String> differences = new ArrayList<>();
        int answered = 0;
        int carried = 0;
        for (int r = 0; r < n; r++)
        {
            String pair = "(" + a[r] + ", " + b[r] + ")";
            if (!same(e, min, r))
            {
                differences.add("earliest_date" + pair + " = " + render(e, r) + " but min_date = "
                        + render(min, r));
            }
            if (!same(l, max, r))
            {
                differences.add("latest_date" + pair + " = " + render(l, r) + " but max_date = "
                        + render(max, r));
            }
            if (missing(e, r) == null)
            {
                answered++;
            }
            else if (missing(e, r) != MissingValue.MIS)
            {
                carried++;
            }
        }
        assertEquals(List.of(), differences);
        // The matrix must exercise every arm, else agreement is vacuous: answered pairs, carried
        // identities (.A + .A, .B + .B) and computed missings all occur.
        assertTrue(answered > 100, "answered pairs: " + answered);
        assertEquals(2, carried, "the two same-identity pairs carry their identity");
        assertTrue(n - answered - carried > 100, "computed-missing pairs");
    }


    private static boolean same(Vector p, Vector g, int row)
    {
        return missing(p, row) == missing(g, row)
                && java.util.Objects.equals(text(p, row), text(g, row));
    }


    private static String render(Vector v, int row)
    {
        MissingValue m = missing(v, row);
        return m != null ? "«" + m + "»" : "\"" + text(v, row) + "\"";
    }


    /** The rule-level shape of the same claim: {@code earliest_date} over two bound extremes. */
    @Test
    void aDateTypedBindingIsAnAcceptedArgumentAndTheCheckReadsTheFunctionsAnswer()
    {
        IDataTable dm = RealTableFixture.of("DM").str("USUBJID", "S1").build();
        IDataTable ex = RealTableFixture.of("EX").str("USUBJID", "S1", "S1")
                .str("EXSTDTC", "2020-01-05", "2020-01-03").str("EXENDTC", "2020-01-06", "")
                .build();
        String bindings = binding("$st", "min_date(EXSTDTC, domain=\\\"EX\\\", group=[USUBJID])")
                + "," + binding("$en", "max_date(EXENDTC, domain=\\\"EX\\\", group=[USUBJID])")
                + "," + binding("$e", "earliest_date($st, $en)") + ","
                + binding("$l", "latest_date($st, $en)");
        assertEquals(1, fires(bindings, "$e == date(\\\"2020-01-03\\\")", dm, ex));
        assertEquals(1, fires(bindings, "$l == date(\\\"2020-01-06\\\")", dm, ex));
    }
}
