package net.cumba.cdisc.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.cumba.cdisc.core.exec.EvaluationContext;
import net.cumba.cdisc.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * EC-43 — <b>derives</b> the absent-column exclusion set from behaviour, over a hand-written census
 * of surfaces.
 *
 * <p>
 * <b>Both halves of that sentence are load-bearing, and an earlier wording overstated it.</b>
 * {@link #SURFACES} <em>is</em> a hand-written list — what is derived is the
 * <em>classification</em>: which of those surfaces own their absent-column contract. The list must
 * therefore cover the operators the exclusion question is actually about, or the derivation is a
 * derivation over nothing. It now spans the whole of §2.6 <b>Table A</b> ({@code exists},
 * {@code not_exists}, {@code var_is_null}, {@code is_unique_set}, {@code is_unique_value},
 * {@code has_multiple_values_for}, {@code has_same_values},
 * {@code inconsistent_enumerated_columns}, {@code is_inconsistent_across_dataset},
 * {@code present_on_multiple_rows_within}, {@code empty_within_except_last_row}) alongside the
 * EC-43 population and Table B.
 * </p>
 *
 * <p>
 * The fork derives its own {@code ABSENT_TARGET_AWARE_OPERATORS} by monkeypatching
 * {@code _absent_target_as_missing} off and re-classifying by whether the operator raises. That
 * discriminator does not port: <b>nothing in {@code ExprCompiler} raises on an absent column</b> —
 * every site returns an empty {@code BitSet}, so "does it answer without the fold" is true of
 * {@code not_equal_to} exactly as it is of {@code is_unique_set}.
 * </p>
 *
 * <p>
 * What does port is the fork's <em>other</em> pin, and it is the house contract turned into a
 * decision procedure: <b>a surface is absent-column-aware iff its verdict over an absent column
 * differs from its verdict over a present-but-all-blank one.</b> That needs no raise, and — via
 * {@link ExprCompiler#absentFoldEnabled} — it stays re-runnable after the fix has shipped, which is
 * the property that keeps this a derivation rather than a frozen verdict table.
 * </p>
 *
 * <p>
 * A Table A surface appearing in the derived set is <b>not a defect</b>: it is the positive
 * statement that the operator resolves its target by raw column name and answers the presence
 * question itself ({@code exists} is the extreme case — that IS its question). The defect this test
 * guards against is the opposite: an EC-43-population surface drifting INTO the set.
 * </p>
 */
class AbsentColumnContractTest
{

    /** One representative leaf per compiled surface, all over the same target column. */
    private static final List<String> SURFACES = List.of(
            // directly-compiled negatives — the EC-43 population
            "TSVAL != \"X\"", "TSVAL !~ /^[1-9]\\d*$/", "TSVAL not in [\"Y\", \"N\"]",
            "not equalsIgnoreCase(TSVAL, \"x\")", "len(TSVAL) != 4",
            // positives — Q1 = uniform
            "TSVAL == \"X\"", "TSVAL =~ /^[1-9]\\d*$/", "TSVAL in [\"Y\", \"N\"]", "len(TSVAL) > 3",
            "len(TSVAL) < 200", "TSVAL > 3",
            // registry boolean predicates (the compileBoolCall generic tail)
            "is_integer(TSVAL)", "not is_integer(TSVAL)", "contains(TSVAL, \"Q\")",
            "not contains(TSVAL, \"Q\")", "starts_with(TSVAL, \"Q\")", "invalid_date(TSVAL)",
            // Fix #157 — the date-portion pair joins the EC-43 population: neither owns an
            // absent-column contract, so an absent TSVAL must behave exactly like an all-blank one
            // (positive silent, negative firing on every row).
            "is_complete_date_part(TSVAL)", "not is_complete_date_part(TSVAL)",
            // Table B — excluded from the fold, and must still agree
            "empty(TSVAL)", "not empty(TSVAL)", "is_missing(TSVAL)",
            // §2.6 Table A — the operators the exclusion question is ABOUT. They resolve their
            // target by raw column name, so the fold cannot reach them and they answer the
            // presence question themselves; several therefore land in the derived set.
            "var_exists(\"TSVAL\")", "not var_exists(\"TSVAL\")", "var_not_exists(\"TSVAL\")",
            "var_is_null(\"TSVAL\")", "not var_is_null(\"TSVAL\")",
            "is_unique_set([TSVAL, TSPARMCD])", "not is_unique_set([TSVAL, TSPARMCD])",
            "is_unique_value(TSVAL)", "is_not_unique_value(TSVAL)",
            "has_multiple_values_for(TSVAL, TSPARMCD)", "has_same_values(TSVAL)",
            "inconsistent_enumerated_columns(TSVAL)",
            "is_inconsistent_across_dataset(TSVAL, keys=[TSPARMCD])",
            "present_on_multiple_rows_within(TSVAL, within=TSPARMCD)",
            "empty_within_except_last_row(TSVAL, TSPARMCD, ordering=TSPARMCD)");

    /**
     * Surfaces that own their absent-column contract — the derived result, and every one of them is
     * a §2.6 Table A operator resolving its target by raw column name. Nothing from the EC-43
     * population may appear here: that would mean the fold failed to make absent equal blank.
     *
     * <p>
     * <b>EC-53 / Fix #143 shrank this set from ten surfaces to seven; {@code W32-E3} then took it
     * to SIX (see below).</b> The three that dropped out — {@code not is_unique_set(…)},
     * {@code is_unique_value(…)}, {@code is_not_unique_value(…)} — are exactly the surfaces backed
     * by {@code GroupSemantics.uniqueSetViolations}, whose absent-<em>target</em> early-out was the
     * last surviving carve-out from the all-missing contract. It now drops the absent target and
     * regroups on the surviving key columns, so those three agree with their all-blank twin like
     * everything else. Nothing else moved: the four presence predicates ARE the presence question,
     * and {@code has_same_values} / {@code present_on_multiple_rows_within} /
     * {@code empty_within_except_last_row} own separate contracts EC-53 did not touch. ⚠ The last
     * clause is now historical: {@code W32-E3} DID touch {@code present_on_multiple_rows_within}.
     * </p>
     *
     * <p>
     * ⭐⭐ <b>{@code W32-E3} shrank it again, seven → SIX, and this is EVIDENCE FOR that change
     * rather than a concession to it.</b> The surface that dropped out is
     * {@code present_on_multiple_rows_within(TSVAL, within=TSPARMCD)}. Retiring
     * {@code Blankness.MISSING_ONLY} makes {@code ""} blank in the grouping key, so an
     * <em>all-blank</em> {@code TSPARMCD} now drops every row exactly as an <em>absent</em>
     * {@code TSPARMCD} does. Previously the all-blank column formed one real {@code ""} group and
     * flagged, while the absent column flagged nothing — a disagreement that had no contract behind
     * it.
     * </p>
     *
     * <p>
     * ⚑ <b>That disagreement was a standing-policy violation, and the ruling removed it as a side
     * effect.</b> {@code STANDING-RULINGS.md} §1, 2026-08-03: <i>"an absent column is handled
     * exactly as a present column whose values are all missing."</i> This surface did not honour
     * that; it does now, and it needed no carve-out of its own to get there. ⇒ <b>one fewer
     * self-handling surface, not one more.</b>
     * </p>
     *
     * <p>
     * Note what stayed OUT of the set throughout, because it is the plan's Q3 answered
     * mechanically: {@code is_inconsistent_across_dataset(TSVAL, keys=[TSPARMCD])} already agreed
     * with its all-blank twin before Fix #143 and still does. Its own {@code nameIdx < 0} early-out
     * is a fast path, not a carve-out — a blank target is excluded from the group's distinct-value
     * count either way, so {@code counts.size() <= 1} and nothing is flagged.
     * </p>
     *
     * <p>
     * ⚠ Q3 was asked on a false premise, which this test is what corrects. The plan believed
     * {@code is_inconsistent_across_dataset} sat in the fork's
     * {@code ABSENT_TARGET_AWARE_OPERATORS} alongside {@code is_(not_)unique_set}. It does not —
     * that set holds {@code inconsistent_enumerated_columns}, a <em>different</em> operator
     * (verified against {@code check_operators/dataframe_operators.py:76-88}). So there was never a
     * carve-out to remove here on either lane. The derivation above is the reason to believe that,
     * and it does not depend on the fork at all.
     * </p>
     */
    private static final Set<String> EXPECTED_AWARE = Set.of("var_exists(\"TSVAL\")",
            "not var_exists(\"TSVAL\")", "var_not_exists(\"TSVAL\")", "has_same_values(TSVAL)",
            "empty_within_except_last_row(TSVAL, TSPARMCD, ordering=TSPARMCD)");

    @AfterEach
    void restoreFold()
    {
        ExprCompiler.absentFoldEnabled = true;
    }


    private static IDataTable absent()
    {
        return MockTable.of().name("TS").col("TSPARMCD", "PLANSUB", "PLANSUB").build();
    }


    private static IDataTable blank()
    {
        return MockTable.of().name("TS").col("TSPARMCD", "PLANSUB", "PLANSUB").col("TSVAL", "", "")
                .build();
    }


    /** The outcome of one surface — the verdict, or the exception type if it threw. */
    private static String outcome(String expression, IDataTable table)
    {
        try
        {
            BitSet bs = NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expression),
                    EvaluationContext.builder().table(table).build());
            return "value:" + bs;
        }
        catch (RuntimeException ex)
        {
            return "raised:" + ex.getClass().getSimpleName();
        }
    }


    /** The surfaces whose absent-column verdict differs from their all-blank one. */
    private static Set<String> disagreeing()
    {
        Set<String> out = new LinkedHashSet<>();
        for (String expression : SURFACES)
        {
            if (!outcome(expression, absent()).equals(outcome(expression, blank())))
            {
                out.add(expression);
            }
        }
        return out;
    }


    @Test
    @DisplayName("derivation: with the fold ON, only the self-handling surfaces disagree")
    void derivedExclusionSetIsExactlyTheSelfHandlingSurfaces()
    {
        assertEquals(EXPECTED_AWARE, disagreeing(),
                "a surface whose absent-column verdict differs from its all-blank one owns its own "
                        + "contract and must not be folded; any other difference is an EC-43 bug");
    }


    @Test
    @DisplayName("the derivation is re-runnable: with the fold OFF, far more surfaces disagree")
    void foldIsWhatCreatesTheAgreement()
    {
        Set<String> withFold = disagreeing();
        ExprCompiler.absentFoldEnabled = false;
        Set<String> withoutFold = disagreeing();

        assertTrue(withoutFold.size() > withFold.size(),
                "without the fold the absent/all-blank contract is broken on many more surfaces — "
                        + "that gap IS EC-43. with=" + withFold + " without=" + withoutFold);
        assertTrue(withoutFold.containsAll(withFold),
                "the fold may only ever REMOVE disagreements, never introduce one");

        List<String> newlyFixed = new ArrayList<>(withoutFold);
        newlyFixed.removeAll(withFold);
        assertFalse(newlyFixed.isEmpty(), "the fold must fix at least one surface");
    }


    @Test
    @DisplayName("empty/is_missing keep their FIRES_ON_ABSENT_COLUMN contract unchanged")
    void presencePredicatesAreUnaffectedByTheFold()
    {
        String fires = outcome("empty(TSVAL)", absent());
        ExprCompiler.absentFoldEnabled = false;
        assertEquals(fires, outcome("empty(TSVAL)", absent()),
                "empty() is excluded from the fold, so flipping the fold must not move it");
    }
}
