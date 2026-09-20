package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.CheckConditionExpression;
import net.cumba.corej.core.model.Outcome;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RuleCore;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * D76 / D96a — <b>an absent column evaluates as its TYPE-DERIVED default, and the type comes from
 * the rule's own expectation</b>: {@code MissingValue.MIS} where the rule numeric-expects the
 * column, the empty string everywhere else (D34 #3/#4).
 *
 * <p>
 * The two halves are asserted <b>side by side with their present-but-blank / present-value twins in
 * the same test</b>, so they can never again diverge silently — the pairing IS the EC-43 contract
 * (the corpus states it as {@code EC43-absent-equals-blank-control}).
 * </p>
 *
 * <p>
 * ⭐ <b>The character half is the regression this class was written for.</b> Phase 6c (D117/D120)
 * made the four order operators total under D34 #5: a genuine {@code MissingValue} sorts below
 * every non-missing value. D96a/D96c/D120f ruled the boundary — only a <em>genuine</em>
 * {@code MissingValue} is low; an absent character column is a char column whose every row is
 * {@code ""} (D34 #3), present-but-unpositionable, and answers <b>false for all six operators</b>
 * (SPEC §5.2(4)). The value-position materialisation of an absent column, however, produced a
 * carrier whose {@code missing()} was {@code MissingValue.MIS} ({@code ExprCompiler.valueRefPlan}'s
 * fall-through → {@code TypedValue}'s computed-missing default), so {@code date(MHSTDTC) > MHENDTC}
 * with {@code MHENDTC} ABSENT fired on every row while the same rule over a present-but-blank
 * {@code MHENDTC} correctly reported nothing. Both verdict instruments were blind to it: the study
 * corpus contains no absent-column date comparison, and {@code PrimitivesTest}'s differential feeds
 * only {@code ColumnVector}s — the one carrier shape that moved is the one shape it cannot see.
 * </p>
 *
 * <p>
 * ⚠⚠ <b>Both operand POSITIONS are pinned, because they diverged.</b> D131 corrected the value
 * position (the right operand) and left the name position folding to {@code ALL_MISSING}; D131a
 * closes it. The two are asserted side by side for the same reason absent is asserted beside blank
 * — one comparison read from its two ends must give one answer.
 * </p>
 *
 * <p>
 * ⛔ The numeric half is <b>correct as shipped and pinned here so the fix cannot overshoot</b>: a
 * numeric-expected absent column is {@code MissingValue.MIS} in every row (D34 #4) and therefore
 * still flips under the D34 #5 total order — {@code FDA-SD0084}'s {@code not empty(...)} guard
 * exists precisely for that flip.
 * </p>
 */
class AbsentColumnTypeDefaultTest
{

    /** MH with two populated start dates; {@code MHENDTC} ABSENT. */
    private static IDataTable absentEnd()
    {
        return MockTable.of().name("MH").col("MHSTDTC", "2020-01-02", "2020-03-04").build();
    }


    /** The same MH, {@code MHENDTC} present but BLANK on every row — the control twin. */
    private static IDataTable blankEnd()
    {
        return MockTable.of().name("MH").col("MHSTDTC", "2020-01-02", "2020-03-04")
                .col("MHENDTC", "", "").build();
    }


    private static Rule rule(String id, String expression)
    {
        Rule rule = new Rule();
        RuleCore core = new RuleCore();
        core.setId(id);
        rule.setCore(core);
        rule.setCheck(
                new CheckConditionExpression(CheckExpressionParser.parse(expression), expression));
        rule.setSensitivity(Sensitivity.RECORD);
        Outcome outcome = new Outcome();
        outcome.setMessage("m");
        outcome.setOutputVariables(List.of());
        rule.setOutcome(outcome);
        net.cumba.corej.core.RulePackageLoader.installNativeExpr(rule);
        return rule;
    }


    private static long violations(String expression, IDataTable table)
    {
        RuleExecutionResult result = RuleRunner.execute(rule("CORE-ABSDEF-1", expression), table);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(),
                () -> expression + " must execute, got " + result.getStatusMessage());
        return result.getViolationCount();
    }


    /**
     * The probe table of the 2026-09-17 regression report, pinned as a PAIR and now in <b>both
     * operand positions over all six operators</b>: for every {@code (position, operator)} the
     * absent fixture and the blank fixture must answer identically. That pairing IS the EC-43
     * contract, and the two positions are pinned together because they are what diverged — D131
     * fixed the value position, and the name position kept folding to {@code ALL_MISSING} until
     * D131a, so {@code date(ABSENT) < MHSTDTC} fired on every row while the mirrored
     * {@code date(MHSTDTC) > ABSENT} (the same fact, read from the other side) reported nothing.
     *
     * <p>
     * ⚠ The four order operators must additionally answer <b>zero</b> in both positions (D96a: the
     * operand is present-but-unpositionable, SPEC §5.2(4) — false for all six). {@code ==} / {@code
     * !=} are pinned only as a PAIR: the date family's {@code !=} answers {@code true} for an
     * unpositionable <em>left</em> cell ({@code compareCells}'s {@code negate}-on-missing contract,
     * D34a's surviving R-5.22 behaviour) and {@code false} for an unpositionable <em>right</em> one
     * — a position asymmetry that predates this fix, is identical for absent and blank, and is
     * therefore not an absent-column defect. Pinning a literal count here would freeze that
     * unrelated question into the EC-43 contract.
     * </p>
     */
    @ParameterizedTest
    @ValueSource(strings =
    {
            ">", ">=", "<", "<=", "==", "!="
    })
    @DisplayName("char half: an absent date operand answers exactly like a blank one — both sides")
    void absentCharDateOperandEqualsBlankInBothPositions(String op)
    {
        // VALUE position (right operand) — the half D131 fixed.
        assertAbsentEqualsBlank("date(MHSTDTC) " + op + " MHENDTC");
        // NAME position (left operand) — the half D131a left open; `<`/`<=` fired 2 of 2 rows here.
        assertAbsentEqualsBlank("date(MHENDTC) " + op + " MHSTDTC");
    }


    /**
     * The order half of the same table, where the absolute answer is ruled and not merely paired:
     * an unpositionable operand is false for every order operator, in either position.
     */
    @ParameterizedTest
    @ValueSource(strings =
    {
            ">", ">=", "<", "<="
    })
    @DisplayName("char half: an unpositionable date operand reports nothing, in either position")
    void unpositionableDateOperandReportsNothingInBothPositions(String op)
    {
        for (String expression : List.of("date(MHSTDTC) " + op + " MHENDTC",
                "date(MHENDTC) " + op + " MHSTDTC"))
        {
            assertEquals(0, violations(expression, blankEnd()),
                    () -> "'" + expression + "' over a blank operand is unpositionable"
                            + " (D96c / SPEC §5.2(4)) and must report nothing");
            assertEquals(0, violations(expression, absentEnd()),
                    () -> "'" + expression + "' over an ABSENT operand must answer the same:"
                            + " an absent char column is all-\"\" (D34 #3), NOT MissingValue.MIS,"
                            + " so it must not take the D34 #5 order arm");
        }
    }


    /** Asserts the EC-43 pairing for one expression: absent and blank answer identically. */
    private static void assertAbsentEqualsBlank(String expression)
    {
        long absent = violations(expression, absentEnd());
        long blank = violations(expression, blankEnd());
        assertEquals(blank, absent,
                () -> "EC43-absent-equals-blank: '" + expression + "' diverges — absent MHENDTC"
                        + " fired " + absent + " row(s), blank MHENDTC " + blank
                        + " — an absent char column is all-\"\" (D34 #3), NOT MissingValue.MIS,"
                        + " so it must not take the D34 #5 order arm");
    }


    /**
     * The numeric half, pinned beside its populated twin. {@code AEENDY} is numeric-expected (a
     * plain order-comparison operand, D76a), so ABSENT means {@code MissingValue.MIS} on every row
     * (D34 #4) and the D34 #5 total order applies: every present {@code AESTDY} sorts ABOVE it —
     * {@code >} fires on every row, {@code <} on none. A fix that folded every absent column to
     * {@code ""} would silence the {@code >} half; this test is what reddens then.
     */
    @Test
    @DisplayName("num half: a numeric-expected absent column keeps MIS and sorts low (D34 #5)")
    void numericExpectedAbsentColumnKeepsMissingAndSortsLow()
    {
        IDataTable absentEndDy = MockTable.of().name("AE").colLong("AESTDY", 1L, 2L).build();

        assertEquals(2, violations("AESTDY > AEENDY", absentEndDy),
                "AESTDY > <absent numeric-expected> is TRUE per row: MIS sorts below 1 and 2");
        assertEquals(0, violations("AESTDY < AEENDY", absentEndDy),
                "AESTDY < <absent numeric-expected> is FALSE per row under the same total order");

        // The populated twin, side by side: the same operators over supplied values.
        IDataTable presentEndDy = MockTable.of().name("AE").colLong("AESTDY", 1L, 2L)
                .colLong("AEENDY", 5L, 1L).build();
        assertEquals(1, violations("AESTDY > AEENDY", presentEndDy), "2 > 1 only");
        assertEquals(1, violations("AESTDY < AEENDY", presentEndDy), "1 < 5 only");

        // ⭐ The NAME position must overshoot no further: D131a mirrors valueRefPlan's
        // type-derived default, so a numeric-EXPECTED absent column stays MissingValue.MIS on the
        // LEFT too and keeps sorting low. A fix that folded every absent name operand to "" would
        // silence this `<` — which is what makes this the numeric counter-pin of the char pairing
        // above, in the position the char pairing found broken.
        assertEquals(0, violations("AEENDY > AESTDY", absentEndDy),
                "<absent numeric-expected> > AESTDY is FALSE per row: MIS sorts below 1 and 2");
        assertEquals(2, violations("AEENDY < AESTDY", absentEndDy),
                "<absent numeric-expected> < AESTDY is TRUE per row under the same total order");
    }


    /**
     * ⭐⭐ <b>The EC-43 pairing for the arms D131a unblocked</b> (terminal review M2/M3 — D34 #5-2's
     * plain-equality arm, D12's {@code ""} limb and D13's {@code len} / membership limbs). These
     * are the four shapes Lane B MEASURED as breaking while the name position still minted
     * {@code ALL_MISSING}: with both positions folding to the D76 default they answer exactly what
     * the present-but-blank twin answers, which is the whole content of the contract.
     *
     * <p>
     * ⚠ The absent side reports ONE dataset-level finding where the blank side reports two rows
     * (D111/D111a) — an absent column is a dataset fact decided before a row is read. So the
     * pairing here is "both fire" / "both silent", not an equal count; the count difference IS the
     * ruling, pinned by {@code EC43-not-equal-absent-operator} against its blank-control sibling.
     * </p>
     */
    @Test
    @DisplayName("the equality/len/membership arms keep absent and blank answering alike")
    void absentAndBlankAgreeOnTheEqualityFamilyArms()
    {
        // D34 #5-2's plain-equality arm: an absent column is NOT a missing identity, so a column
        // compared with itself is equal to itself and != is silent on both fixtures.
        assertEquals(0, violations("MHENDTC != MHENDTC", absentEnd()),
                "ABSENT != ABSENT must be silent — an absent char column is all-\"\", not MIS");
        assertEquals(0, violations("MHENDTC != MHENDTC", blankEnd()), "…as on the blank twin");

        // D12's "" limb: "" is a PRESENT value, so an absent char column equals it — on both.
        assertTrue(violations("MHENDTC == \"\"", absentEnd()) > 0,
                "ABSENT == \"\" is TRUE (D34 #3 + D12: the default is a present \"\")");
        assertEquals(2, violations("MHENDTC == \"\"", blankEnd()), "…both rows on the blank twin");
        assertEquals(0, violations("MHENDTC != \"\"", absentEnd()), "…so != \"\" is silent");
        assertEquals(0, violations("MHENDTC != \"\"", blankEnd()), "…on both");

        // D13's len limb: len("") is 0, so len(ABSENT) == 0 stays TRUE beside len(BLANK) == 0.
        assertTrue(violations("len(MHENDTC) == 0", absentEnd()) > 0,
                "len(ABSENT) == 0 is TRUE — the fold is \"\" (D76), which HAS length 0");
        assertEquals(2, violations("len(MHENDTC) == 0", blankEnd()), "…as on the blank twin");

        // D13's membership limb: "" is a member of a list containing it, absent and blank alike.
        assertTrue(violations("MHENDTC in [\"\", \"X\"]", absentEnd()) > 0,
                "ABSENT in [\"\",\"X\"] is TRUE — a present \"\" is a member");
        assertEquals(2, violations("MHENDTC in [\"\", \"X\"]", blankEnd()), "…on the blank twin");
    }


    /**
     * EC-43 blast-radius pin (acceptance #2 of the regression brief): the type-derived default must
     * not disturb {@code empty(absent)} — TRUE, folded to ONE dataset-level finding (D111/D111a,
     * the bare {@code empty(X)} shape; its named carrier was retired with the CORE family on
     * 2026-09-19, and 75 shipped rules still author it — 73 over a column of the dataset under
     * test, plus {@code FDA-SD1016} / {@code PMDA-SD1016}, whose {@code empty(TI.IETESTCD)} is a
     * dotted cross-dataset reference) — while the blank twin keeps its per-row report.
     */
    @Test
    @DisplayName("empty(absent) stays TRUE at dataset level; empty(blank) stays TRUE per row")
    void emptyOverAbsentStaysTrueAndDatasetLevel()
    {
        RuleExecutionResult absent = RuleRunner.execute(rule("CORE-ABSDEF-2", "empty(MHENDTC)"),
                absentEnd());
        assertTrue(absent.hasViolations(), "empty(<absent>) is TRUE (D34 #7)");
        assertEquals(1, absent.getViolationCount(),
                "the absent-column fact is reported once per dataset (D111a)");

        RuleExecutionResult blank = RuleRunner.execute(rule("CORE-ABSDEF-3", "empty(MHENDTC)"),
                blankEnd());
        assertEquals(2, blank.getViolationCount(),
                "a present-but-blank column keeps its per-row report (EC43 control)");
    }
}
