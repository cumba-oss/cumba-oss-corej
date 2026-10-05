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
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-column-typed-function-absent-type} A1 / A2 — <b>a column-typed function over an
 * absent column takes that column's type from its use, as the column would</b> (owner, 2026-10-05:
 * <i>"a function result that is untyped because it's type is related to an absent variable, can be
 * handled in the same way as the variable would be handled if it would be used directly"</i>). The
 * sibling of {@link AbsentColumnTypeDefaultTest} for function results and bindings.
 *
 * <p>
 * Every case is asserted in the three position kinds a directly read absent column distinguishes
 * (D34 #3/#4, D76, D76a): a <b>numeric</b> position ({@code $v < 3} — fires iff the value is
 * {@code MissingValue.MIS}, which sorts lowest under D34 #5, and stays silent on {@code ""}), a
 * <b>character</b> position ({@code $v != ""} — fires iff the value is a missing, D12) and <b>no
 * typed position</b> ({@code len($v) == 0} — {@code len} records no kind; it answers {@code 0} for
 * {@code ""} and propagates a missing, so it fires iff the value is {@code ""}). ⚠ {@code empty()}
 * cannot tell the two apart (it flags both); {@code is_missing} could since register D50 was
 * enforced (2026-10-05, {@link #isMissingOverAnAbsentColumnFollowsItsTypeDefault}), but these pins
 * predate it and keep their discriminators. Conflicting positions take numeric (A3 = D76a) and no
 * position takes {@code ""} (A4 = D76a), exactly as for a directly read absent column.
 * </p>
 *
 * <p>
 * {@code coalesce} is <b>not</b> type-losing (§6 H2): its position passes through to its operands,
 * each defaults as if read directly, and its own result stays CO1's — every operand skipped ⇒
 * {@code MIS} in every position. {@code row_max} with no matching column follows D34 #3: MIS in a
 * numeric position (unchanged), {@code ""} otherwise.
 * </p>
 */
class ColumnTypedAbsentDefaultTest
{

    private static final String NUMERIC = "$v < 3";

    private static final String CHARACTER = "$v != \"\"";

    private static final String UNTYPED = "len($v) == 0";

    private static final String CONFLICT = "$v < 3 or $v == \"Y\"";

    /** DM, two subjects; no ZZ column, no ZZnn column. */
    private static IDataTable dm()
    {
        return RealTables.of("DM").str("USUBJID", "S1", "S2").lng("AGE", 30L, 40L)
                .str("SEX", "F", "M").build();
    }


    /** LB with a numeric result per subject; no LBZZ column. */
    private static IDataTable lb()
    {
        return RealTables.of("LB").str("USUBJID", "S1", "S2").lng("LBSEQ", 1L, 1L)
                .dbl("LBSTRESN", 5.0, 6.0).str("LBTESTCD", "ALT", "ALT").build();
    }


    /** ADSL joined by USUBJID; no ZZ column. */
    private static IDataTable adsl()
    {
        return RealTables.of("ADSL").str("USUBJID", "S1", "S2").str("ARM", "A", "B").build();
    }


    private static String rule(String check, boolean joinAdsl, List<String> outputs,
            String... bindings)
    {
        StringBuilder b = new StringBuilder(
                "{\"Core\":{\"Id\":\"T-CTAD\"},\"Sensitivity\":\"Record\"");
        if (joinAdsl)
        {
            b.append(",\"Match_Datasets\":[{\"Name\":\"ADSL\",\"Keys\":[\"USUBJID\"],")
                    .append("\"Join_Type\":\"left\"}]");
        }
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
        StringBuilder ov = new StringBuilder("\"USUBJID\"");
        for (String o : outputs)
        {
            ov.append(",\"").append(esc(o)).append('"');
        }
        return b.append(",\"Check\":{\"expression\":\"").append(esc(check))
                .append("\"},\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[").append(ov)
                .append("]}}").toString();
    }


    private static String esc(String s)
    {
        return s.replace("\\", "\\\\").replace("\"", "\\\"");
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


    private static RuleExecutionResult execute(String check, boolean joinAdsl, boolean withLb,
            List<String> outputs, String... bindings)
    {
        String guarded = "not empty(USUBJID) and (" + check + ")";
        Rule r = load(rule(guarded, joinAdsl, outputs, bindings));
        assertNull(r.getLoadError(), () -> check + " → load error " + r.getLoadError());
        IDataTable dm = dm();
        RuleExecutionResult result = RuleRunnerCalls.execute(r, dm,
                withLb ? RealTables.inventoryOf(dm, lb(), adsl())
                        : RealTables.inventoryOf(dm, adsl()),
                "DM", null);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> check + " → " + result.getStatusMessage());
        return result;
    }


    /** The fired rows (1-based). */
    private static List<Long> fired(String check, Case c)
    {
        List<Long> rows = new ArrayList<>();
        execute(check, c.joinAdsl, c.withLb, List.of(), c.bindings).getViolations()
                .forEach(v -> rows.add(v.getRowNumber()));
        return rows;
    }


    /**
     * The value a finding reports for {@code $v} when the Check reads it in the position of
     * {@code position} (or-ed with a term that always holds, so the finding exists either way).
     */
    private static Object reported(String position, Case c)
    {
        RuleExecutionResult result = execute("(" + position + ") or not empty(USUBJID)", c.joinAdsl,
                c.withLb, List.of("$v"), c.bindings);
        assertEquals(2, result.getViolations().size(), result::getStatusMessage);
        Object value = result.getViolations().get(0).getValues().get("$v");
        assertNotNull(value, "a finding value is never null (NK §1)");
        return value;
    }

    /**
     * One column-typed binding over an absent column (or dataset): its {@code name, expression}
     * pairs, the ADSL join, and whether LB is in the study.
     */
    private static final class Case
    {

        final String label;

        final boolean joinAdsl;

        final boolean withLb;

        final String[] bindings;

        Case(String aLabel, boolean aJoinAdsl, boolean aWithLb, String... aBindings)
        {
            label = aLabel;
            joinAdsl = aJoinAdsl;
            withLb = aWithLb;
            bindings = aBindings.clone();
        }
    }

    private static final List<Long> ALL = List.of(1L, 2L);

    private static final List<Long> NONE = List.of();

    /**
     * The use-derived default, asserted in every position: MIS (numeric, conflicting), {@code ""}
     * (character, untyped), and the report's typed default — {@code MIS} where the binding is read
     * numerically, {@code ""} otherwise.
     */
    private static void assertUseDerived(Case c)
    {
        assertEquals(ALL, fired(NUMERIC, c), c.label + ": a numeric position reads MIS (A1)");
        assertEquals(NONE, fired(CHARACTER, c), c.label + ": a character position reads \"\"");
        assertEquals(ALL, fired(UNTYPED, c), c.label + ": no typed position reads \"\" (A4)");
        assertEquals(ALL, fired(CONFLICT, c), c.label + ": numeric wins over character (A3)");
        assertEquals(MissingValue.MIS.toString(), String.valueOf(reported(NUMERIC, c)),
                c.label + ": reported as the numeric default, printed .");
        assertEquals("", reported(CHARACTER, c), c.label + ": reported as the char default");
        assertEquals("", reported(UNTYPED, c), c.label + ": reported as the untyped default");
    }

    // ------------------------------------------------------------------ the functions (A1, A5)


    @Test
    void readValueOverAnAbsentDataset()
    {
        assertUseDerived(new Case("read_value, absent D", false, false, "$v",
                "read_value(LBSTRESN, domain=LB, mode=\"FIRST\")"));
    }


    @Test
    void readValueOverAnAbsentColumn()
    {
        assertUseDerived(new Case("read_value, absent X", false, true, "$v",
                "read_value(LBZZ, domain=LB, mode=\"FIRST\")"));
    }


    @Test
    void groupedReadValueOverAnAbsentDatasetOrColumn()
    {
        assertUseDerived(new Case("grouped read_value, absent D", false, false, "$v",
                "read_value(LBSTRESN, domain=LB, group=[USUBJID], mode=\"FIRST\")"));
        assertUseDerived(new Case("grouped read_value, absent X", false, true, "$v",
                "read_value(LBZZ, domain=LB, group=[USUBJID], mode=\"FIRST\")"));
    }


    @Test
    void maxOverAnAbsentDatasetOrColumn()
    {
        assertUseDerived(new Case("max, absent D", false, false, "$v",
                "max(LBSTRESN, domain=LB, group=[USUBJID])"));
        assertUseDerived(new Case("max, absent X", false, true, "$v",
                "max(LBZZ, domain=LB, group=[USUBJID])"));
    }


    @Test
    void rowMaxWithNoMatchingColumn()
    {
        assertUseDerived(new Case("row_max, no column", false, false, "$v",
                "row_max(name_pattern=\"^ZZ[0-9]+$\")"));
    }


    @Test
    void coalesceOverAbsentOperandsStaysCo1sMissingInEveryPosition()
    {
        Case c = new Case("coalesce, all absent", false, false, "$v", "coalesce(ZZ, YY)");
        assertEquals(ALL, fired(NUMERIC, c), "CO1: every operand skipped ⇒ MIS");
        assertEquals(ALL, fired(CHARACTER, c), "CO1 in a character position too");
        assertEquals(NONE, fired(UNTYPED, c), "CO1 with no position: MIS, not \"\"");
    }


    @Test
    void coalescePassesItsPositionToItsOperands()
    {
        // The operand ZZ is read in the coalesce's numeric position, so ZZ itself — read again
        // directly in a position of no kind — defaults to MIS rule-wide (D76a: numeric when any
        // position expects numeric), as `ZZ < 3 or len(ZZ) == 0` would.
        Case c = new Case("coalesce operand", false, false, "$v", "coalesce(ZZ, 1)");
        assertEquals(NONE, fired("$v < 3 and len(ZZ) == 0", c),
                "ZZ is numeric-expected through the coalesce: MIS, so len(ZZ) is missing");
        assertEquals(ALL, fired("$v < 3 or len(ZZ) == 0", c), "the control: $v is 1");
    }

    // ------------------------------------------------------------------ bindings (A2)


    @Test
    void aPlainColumnBinding()
    {
        assertUseDerived(new Case("$v: ZZ", false, false, "$v", "ZZ"));
    }


    @Test
    void aDottedColumnBinding()
    {
        assertUseDerived(new Case("$v: ADSL.ZZ", true, false, "$v", "ADSL.ZZ"));
    }


    @Test
    void aColrefBinding()
    {
        assertUseDerived(new Case("$v: colref(\"ZZ\")", false, false, "$v", "colref(\"ZZ\")"));
    }


    @Test
    void aDollarChainResolvesToItsRoot()
    {
        assertUseDerived(new Case("$v: $a, $a: read_value", false, true, "$a",
                "read_value(LBZZ, domain=LB, mode=\"FIRST\")", "$v", "$a"));
        assertUseDerived(new Case("$v: $a, $a: ZZ", false, false, "$a", "ZZ", "$v", "$a"));
    }


    @Test
    void aCaseFoldIsNotFollowedBecauseItCannotStandInANumericPosition()
    {
        // Taken + why (P0): stage A types a case fold STRING, so a numeric position over one is a
        // PARAMETER_TYPE load error — the walk need not follow it, and a character or untyped
        // position takes "" either way (D76a).
        String error = load(rule("not empty(USUBJID) and ($v < 3)", false, List.of(), "$a", "ZZ",
                "$v", "upper($a)")).getLoadError();
        assertNotNull(error, "a numeric position over a case fold must not load");
        assertTrue(error.contains("PARAMETER_TYPE"), error);
        Case fold = new Case("$v: upper($a), $a: ZZ", false, false, "$a", "ZZ", "$v", "upper($a)");
        assertEquals(NONE, fired(CHARACTER, fold), "a character position reads \"\"");
        assertEquals(ALL, fired(UNTYPED, fold), "no typed position reads \"\"");
        assertEquals("", reported(CHARACTER, fold));
    }


    @Test
    void aBindingBehavesAsItsExpressionWrittenInPlace()
    {
        // D3 / D16: `$v: E; … $v …` answers as `… E …` — the inline spelling of every function.
        Case none = new Case("inline", false, true);
        for (String e : List.of("read_value(LBZZ, domain=LB, mode=\"FIRST\")",
                "max(LBZZ, domain=LB, group=[USUBJID])", "row_max(name_pattern=\"^ZZ[0-9]+$\")",
                "colref(\"ZZ\")", "ZZ"))
        {
            assertEquals(ALL, fired(e + " < 3", none), e + " in a numeric position");
            assertEquals(NONE, fired(e + " != \"\"", none), e + " in a character position");
            assertEquals(ALL, fired("len(" + e + ") == 0", none), e + " in no typed position");
        }
    }


    @Test
    void theCheckerReportsTheDefaultTheEngineApplies()
    {
        // §6 M2: RuleRunner and StageBChecker read ONE factory (TypeExpectations.forRule), so the
        // stage-B ABSENT_COLUMN report names the default the engine applies through a binding.
        Rule numeric = load(rule("not empty(USUBJID) and ($v < 3)", false, List.of(), "$v", "ZZ"));
        Rule character = load(
                rule("not empty(USUBJID) and ($v != \"\")", false, List.of(), "$v", "ZZ"));
        assertEquals(List.of("number"), absentDefaults(numeric));
        assertEquals(List.of("string"), absentDefaults(character));
    }


    private static List<String> absentDefaults(Rule rule)
    {
        List<String> out = new ArrayList<>();
        net.cumba.corej.core.expr.typed.StageBChecker
                .check(rule, dm(), false, null, java.util.Set.of()).findings().stream()
                .filter(f -> f
                        .kind() == net.cumba.corej.core.expr.typed.StageBErrorKind.ABSENT_COLUMN
                        && "ZZ".equals(f.binding()))
                .forEach(f -> out.add(f.message().contains("default type number") ? "number"
                        : f.message().contains("default type string") ? "string" : f.message()));
        return out;
    }

    // ------------------------------------------------------------------ per-column parity (LOW-3)


    @Test
    void twoCallsOverOneStaticColumnTakeOneKind()
    {
        // Two direct reads of one absent column take ONE kind (D76a: numeric wins), so two
        // different read_value / max calls over one static (dataset, column) do too — the owner's
        // rule read literally. LBZZ is absent from LB: the FIRST read makes the column numeric,
        // so the MAX read is MIS as well, and MIS == "" is false (D12).
        Case none = new Case("inline", false, true);
        String first = "read_value(LBZZ, domain=LB, mode=\"FIRST\")";
        assertEquals(NONE,
                fired(first + " > 5 or read_value(LBZZ, domain=LB, mode=\"MAX\")" + " == \"\"",
                        none),
                "read_value FIRST and MAX over one absent column");
        assertEquals(NONE,
                fired(first + " > 5 or max(LBZZ, domain=LB, group=[USUBJID]) == \"\"", none),
                "read_value and max over one absent column");
        assertEquals(NONE, fired("$a > 5 or $b == \"\"", new Case("bound", false, true, "$a", first,
                "$b", "read_value(LBZZ, domain=LB, mode=\"MAX\")")), "through bindings");
        // Controls: another column, another dataset, and a primary column of the same name
        // (the pre-go review's M2 leak) keep their own kind.
        assertEquals(ALL,
                fired(first + " > 5 or read_value(LBYY, domain=LB, mode=\"MAX\")" + " == \"\"",
                        none),
                "another column of LB stays character");
        assertEquals(ALL,
                fired(first + " > 5 or read_value(LBZZ, domain=ADSL, mode=\"MAX\")" + " == \"\"",
                        none),
                "another dataset's column stays character");
        assertEquals(ALL,
                fired("read_value(ZZ, domain=LB, mode=\"FIRST\") > 5 or ZZ == \"\"", none),
                "the primary column ZZ never takes the read_value target's kind");
    }


    @Test
    void theCheckerNamesTheKindAColumnTypedCallAppliesToItsTarget()
    {
        // LOW-4: an unpinned max reads the PRIMARY column, so stage B reports ZZ absent — and the
        // report names the kind the engine applies at the call (numeric: MIS), not only the
        // column's own direct-read default.
        Rule rule = load(
                rule("not empty(USUBJID) and (max(ZZ, group=[USUBJID]) < 3)", false, List.of()));
        List<String> messages = new ArrayList<>();
        net.cumba.corej.core.expr.typed.StageBChecker
                .check(rule, dm(), false, null, java.util.Set.of()).findings().stream()
                .filter(f -> f
                        .kind() == net.cumba.corej.core.expr.typed.StageBErrorKind.ABSENT_COLUMN
                        && "ZZ".equals(f.binding()))
                .forEach(f -> messages.add(f.message()));
        assertEquals(List.of("absent from DM; D76 default type string (missing = \"\"); as the"
                + " target of max(ZZ, group=[USUBJID]): number (missing = MissingValue.MIS)"),
                messages);
        assertEquals(ALL, fired("max(ZZ, group=[USUBJID]) < 3", new Case("max", false, false)),
                "what the engine applies: MIS");
    }


    @Test
    void isMissingOverAnAbsentColumnFollowsItsTypeDefault()
    {
        // D50 (owner 2026-10-05): is_missing is true only for a MissingValue. An absent column
        // reads its type default (D34 #3/#4, D76a): "" where no position expects numeric ⇒ false,
        // MIS where one does ⇒ true. empty() keeps firing on both (missing or "").
        Case none = new Case("inline", false, false);
        assertEquals(NONE, fired("is_missing(ZZ)", none), "absent char (untyped) column: \"\"");
        assertEquals(ALL, fired("is_missing(ZZ) or ZZ > 3", none),
                "absent numeric-expected column: MIS (and MIS > 3 is false, so only is_missing)");
        assertEquals(ALL, fired("empty(ZZ)", none), "empty() over an absent column: unchanged");
        assertEquals(NONE, fired("is_missing(SEX)", none), "a present value is not missing");
    }


    @Test
    void aPresentColumnIsUntouched()
    {
        // The control: a present column's value is its own, whatever the position.
        Case present = new Case("read_value, present", false, true, "$v",
                "read_value(LBSTRESN, domain=LB, mode=\"FIRST\")");
        assertEquals(NONE, fired(NUMERIC, present), "5.0 < 3 is false");
        assertEquals(ALL, fired(CHARACTER, present), "5.0 != \"\"");
        assertEquals("5", String.valueOf(reported(NUMERIC, present)));
    }
}
