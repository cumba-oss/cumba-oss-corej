package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.BitSet;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.cdisc.core.expr.eval.Primitives;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionExpression;
import net.cumba.cdisc.core.model.Outcome;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RuleCore;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueDouble;
import net.cumba.datatable.values.DataValueMissing;
import net.cumba.datatable.values.DataValueString;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * EC-87 ({@code PLAN-next-record-value-comparison.md}) — the {@code relation=} kwarg on
 * {@code has_next_corresponding_record}: the §4.3 worked-example table, every row asserted on
 * <b>both</b> arms.
 *
 * <p>
 * ⚠⚠ The default arm is asserted explicitly on every row on purpose: D-2 promises that a rule
 * authoring no {@code relation=} (and one authoring {@code "=="}) behaves exactly as before, and
 * that promise is only tested if the old arm is exercised beside the new one — otherwise the new
 * arm silently becomes the only one. D-1 promises the relation <b>widens</b>: every pair the
 * identity arm accepts, the comparison arm accepts too, and {@code "<="} admits strictly more.
 * </p>
 *
 * <p>
 * The end-to-end tests go through {@code RulePackageLoader.installNativeExpr} and
 * {@code RuleRunner} over a {@link CheckConditionExpression} — the form every shipped rule
 * deserialises to — so the kwarg is proven from the authored text to the finding, not from a
 * hand-built lambda.
 * </p>
 */
class NextRecordRelationTest
{

    // ---------------------------------------------------------------- end-to-end plumbing

    private static Rule rule(String relationKwarg)
    {
        String text = "not has_next_corresponding_record(SJENDTC, SJSTDTC, keep_missings=false, "
                + "ordering=SJSEQ" + relationKwarg + ", within=USUBJID)";
        CheckCondition check = new CheckConditionExpression(CheckExpressionParser.parse(text),
                text);
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId("EC87-" + relationKwarg.replaceAll("[^A-Za-z=<>]", ""));
        rule.setCore(core);
        rule.setCheck(check);
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("m");
        outcome.setOutputVariables(List.of());
        rule.setOutcome(outcome);
        net.cumba.cdisc.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    /** Two SJ rows of one subject: row 1 ends {@code end}, row 2 starts {@code nextStart}. */
    private static IDataTable pair(String end, String nextStart)
    {
        return MockTable.of().name("SJ").col("USUBJID", "R-001", "R-001").col("SJSEQ", "1", "2")
                .col("SJSTDTC", "2019-12-01", nextStart).col("SJENDTC", end, "2020-02-01").build();
    }


    private static Set<Long> firedRows(Rule rule, IDataTable table)
    {
        return RuleRunner.execute(rule, table).getViolations().stream().map(Violation::getRowNumber)
                .collect(Collectors.toSet());
    }

    // ---------------------------------------------------------------- §4.3, rows 1-4 and 8


    /**
     * The §4.3 table on determinate and partial dates, both arms. Column 3 is the shipped behaviour
     * (no kwarg), column 4 is {@code relation="<="}.
     */
    @ParameterizedTest(name = "end {0} / next start {1}: default fires={2}, <= fires={3}")
    @CsvSource(
    {
            // 1: equal — silent on both
            "2020-01-15, 2020-01-15, false, false",
            // 2: the day IMMEDIATELY BEFORE — #129's whole point: fires today, silent under <=
            "2020-01-14, 2020-01-15, true, false",
            // 3: overlap — fires on both
            "2020-01-20, 2020-01-15, true, true",
            // 4: a six-week gap — the honest cost of the ruled <= reading (admitted)
            "2019-12-01, 2020-01-15, true, false",
            // 8: same month-precision value — identity keeps it silent; the hull rule alone would
            // have called 2020-01 <= 2020-01 FALSE, so this row is the D-1 disjunction detector
            "2020-01, 2020-01, false, false",
            // a partial end strictly inside the hull of the next start is not <= it (∀-hull)
            "2020-01, 2020-01-15, true, true",
            // but a partial end whose whole hull precedes the next start is admitted
            "2019-12, 2020-01-15, true, false",
    })
    void workedExampleTableOnBothArms(String end, String nextStart, boolean defaultFires,
            boolean atMostFires)
    {
        IDataTable t = pair(end, nextStart);
        assertEquals(defaultFires ? Set.of(1L) : Set.of(), firedRows(rule(""), t),
                "default arm (no kwarg) — D-2");
        assertEquals(defaultFires ? Set.of(1L) : Set.of(), firedRows(rule(", relation=\"==\""), t),
                "relation=\"==\" must be identical to omitting the kwarg");
        assertEquals(atMostFires ? Set.of(1L) : Set.of(), firedRows(rule(", relation=\"<=\""), t),
                "relation=\"<=\" arm");
        assertTrue(!atMostFires || defaultFires,
                "D-1: the relation may only WIDEN — a pair silent by identity must stay silent");
    }


    @Test
    @DisplayName(">= is the mirror: identity or a strictly LATER end corresponds")
    void atLeastIsTheMirrorRelation()
    {
        Rule atLeast = rule(", relation=\">=\"");
        assertEquals(Set.of(), firedRows(atLeast, pair("2020-01-15", "2020-01-15")), "equal");
        assertEquals(Set.of(), firedRows(atLeast, pair("2020-01-20", "2020-01-15")), "later");
        assertEquals(Set.of(1L), firedRows(atLeast, pair("2020-01-14", "2020-01-15")),
                "earlier fires under >=");
    }


    @Test
    @DisplayName("the current row's bit is set, never the next row's; the last row never fires")
    void findingLandsOnTheCurrentRow()
    {
        IDataTable t = MockTable.of().name("SJ").col("USUBJID", "R-001", "R-001", "R-001")
                .col("SJSEQ", "1", "2", "3")
                .col("SJSTDTC", "2020-01-01", "2020-01-15", "2020-01-25")
                .col("SJENDTC", "2020-01-20", "2020-01-25", "2020-02-01").build();
        assertEquals(Set.of(1L), firedRows(rule(", relation=\"<=\""), t));
        assertEquals(Set.of(1L), firedRows(rule(""), t));
    }

    // ---------------------------------------------------------------- §4.3, rows 5-7 (D-3)


    /**
     * D-3 — missing and blank cells keep today's disposition on both arms: the comparison arm
     * answers {@code false} on them (a missing left cell / a null right operand), so the
     * disjunction falls back to identity, i.e. {@code W38-A1} / Fix #249 exactly.
     */
    @Test
    void missingAndBlankNeighboursKeepTheIdentityDisposition()
    {
        // 5: missing / missing — corresponds (same missing marker)
        IDataTable bothMissing = pair(null, null);
        assertEquals(Set.of(), firedRows(rule(""), bothMissing));
        assertEquals(Set.of(), firedRows(rule(", relation=\"<=\""), bothMissing));
        // 6: missing / value — fires on both arms
        IDataTable missingVsValue = pair(null, "2020-01-15");
        assertEquals(Set.of(1L), firedRows(rule(""), missingVsValue));
        assertEquals(Set.of(1L), firedRows(rule(", relation=\"<=\""), missingVsValue));
        // value / missing — fires on both arms (a null right operand contributes nothing)
        IDataTable valueVsMissing = pair("2020-01-15", null);
        assertEquals(Set.of(1L), firedRows(rule(""), valueVsMissing));
        assertEquals(Set.of(1L), firedRows(rule(", relation=\"<=\""), valueVsMissing));
        // 7: "" / "" — corresponds; if the relation REPLACED identity, "" <= "" would be false
        IDataTable bothBlank = pair("", "");
        assertEquals(Set.of(), firedRows(rule(""), bothBlank));
        assertEquals(Set.of(), firedRows(rule(", relation=\"<=\""), bothBlank));
        // "" / value — fires on both arms
        IDataTable blankVsValue = pair("", "2020-01-15");
        assertEquals(Set.of(1L), firedRows(rule(""), blankVsValue));
        assertEquals(Set.of(1L), firedRows(rule(", relation=\"<=\""), blankVsValue));
    }

    // ---------------------------------------------------------------- GroupSemantics level


    private static IDataTableColumn cells(IDataValue... cells)
    {
        IDataTableColumn col = mock(IDataTableColumn.class);
        lenient().when(col.getDataValue(anyLong()))
                .thenAnswer(inv -> cells[((Long) inv.getArgument(0)).intValue()]);
        return col;
    }


    private static IDataValue str(String v)
    {
        return new DataValueString(v);
    }


    private static Set<Integer> bits(BitSet bs)
    {
        Set<Integer> out = new HashSet<>();
        bs.stream().forEach(out::add);
        return out;
    }


    /**
     * The ExprCompiler's {@code "<="} relation, rebuilt here so the D-1 shape is pinned at source.
     */
    private static GroupSemantics.NeighbourRelation atMost()
    {
        return (cur,
                next) -> GroupSemantics.identityCorresponds(cur, next) || Primitives.compareCells(
                        cur, next.isMissingOrInvalid() ? null : next.getValueAsString(), -1, true,
                        false, false);
    }


    /**
     * A neighbour-pair corpus carrying every identity: complete and partial dates, both blank
     * kinds, a numeric pair, a mixed pair.
     */
    private static IDataValue[] corpusCurrent()
    {
        return new IDataValue[]
        {
                str("2020-01-15"), str("2020-01-14"), str("2020-01-20"), str("2020-01"),
                str("2019-12"), str(""), new DataValueMissing(MissingValue.MIS),
                new DataValueMissing(MissingValue.MIS), new DataValueDouble(5.0),
                new DataValueDouble(5.0), str("x")
        };
    }


    private static IDataValue[] corpusNext()
    {
        return new IDataValue[]
        {
                str("2020-01-15"), str("2020-01-15"), str("2020-01-15"), str("2020-01"),
                str("2020-01-15"), str(""), new DataValueMissing(MissingValue.MIS), str(""),
                new DataValueDouble(7.0), new DataValueDouble(4.0), str("x")
        };
    }


    @Test
    @DisplayName("the five-argument overload and the identity relation produce the same bits")
    void identityOverloadReproducesTheShippedResultExactly()
    {
        IDataValue[] cur = corpusCurrent();
        IDataValue[] nxt = corpusNext();
        // Interleave so each corpus pair is a (row i, row i+1) neighbour under its own ordering.
        for (int i = 0; i < cur.length; i++)
        {
            IDataTableColumn name = cells(cur[i], str("unused"));
            IDataTableColumn value = cells(str("unused"), nxt[i]);
            IDataTableColumn order = cells(str("1"), str("2"));
            BitSet five = new BitSet();
            GroupSemantics.flagNoNextCorrespondingRecord(name, value, order, new int[]
            {
                    0, 1
            }, five);
            BitSet six = new BitSet();
            GroupSemantics.flagNoNextCorrespondingRecord(name, value, order, new int[]
            {
                    0, 1
            }, six, GroupSemantics::identityCorresponds);
            assertEquals(bits(five), bits(six), "pair " + i + ": D-2 — byte-identical behaviour");
        }
    }


    @Test
    @DisplayName("D-1: the <= relation's accepted set is a STRICT superset of identity's")
    void atMostIsAStrictSupersetOfIdentity()
    {
        IDataValue[] cur = corpusCurrent();
        IDataValue[] nxt = corpusNext();
        Set<Integer> acceptedByIdentity = new HashSet<>();
        Set<Integer> acceptedByAtMost = new HashSet<>();
        for (int i = 0; i < cur.length; i++)
        {
            if (GroupSemantics.identityCorresponds(cur[i], nxt[i]))
            {
                acceptedByIdentity.add(i);
            }
            if (atMost().corresponds(cur[i], nxt[i]))
            {
                acceptedByAtMost.add(i);
            }
        }
        assertTrue(acceptedByAtMost.containsAll(acceptedByIdentity),
                "every pair identity accepts, <= accepts: " + acceptedByIdentity + " vs "
                        + acceptedByAtMost);
        assertTrue(acceptedByAtMost.size() > acceptedByIdentity.size(), "strictly wider");
        // The exact sets, so a drift in either arm is named by pair index.
        assertEquals(Set.of(0, 3, 5, 6, 10), acceptedByIdentity);
        // + 1 (day before), 4 (partial hull wholly before), 8 (numeric 5 <= 7); NOT 2 (overlap),
        // NOT 7 (Missing vs ""), NOT 9 (numeric 5 > 4).
        assertEquals(Set.of(0, 1, 3, 4, 5, 6, 8, 10), acceptedByAtMost);
    }


    @Test
    @DisplayName("D-5: the numeric branch is inherited — a numeric neighbour pair compares numerically")
    void numericPairsTakeTheNumericBranch()
    {
        assertTrue(atMost().corresponds(new DataValueDouble(5.0), new DataValueDouble(7.0)));
        assertTrue(atMost().corresponds(new DataValueDouble(5.0), new DataValueDouble(5.0)));
        assertFalse(atMost().corresponds(new DataValueDouble(5.0), new DataValueDouble(4.0)));
        // D-5: a mixed numeric/ISO pair is malformed data and must stay REPORTED under a relation,
        // exactly as it is under identity (review finding 1: compareCells's "true regardless of
        // direction" is the date_* operators' fire verdict; read as "corresponds" it would have
        // silenced every row of a numeric-typed SJENDTC against a character SJSTDTC).
        assertFalse(atMost().corresponds(new DataValueDouble(5.0), str("2020-01-15")));
        assertFalse(atMost().corresponds(str("2020-01-15"), new DataValueDouble(5.0)));
    }
}
