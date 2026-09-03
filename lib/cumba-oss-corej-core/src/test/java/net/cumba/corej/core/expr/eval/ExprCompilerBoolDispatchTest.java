package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.ArrayList;
import java.util.BitSet;
import java.util.List;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * Dispatch guard over {@code ExprCompiler.compileBoolCall} — the long {@code if}-chain that maps a
 * boolean function name onto its {@code BoolPlan}.
 *
 * <p>
 * Mutation testing reported 25 surviving {@code NullReturnVals} mutants in that method: replacing
 * the plan a branch returns with {@code null} changed nothing that any test noticed. The lines were
 * <em>covered</em> — the tests reaching them compile expressions without ever evaluating them, so a
 * null plan is never dereferenced. A null plan in production would NPE at evaluation time, on the
 * first study that used the function.
 * </p>
 *
 * <p>
 * This test therefore <b>evaluates</b> one representative call per branch and requires a real
 * verdict back. It deliberately asserts the dispatch contract (a supported call compiles to a
 * usable plan) rather than each function's semantics — those live in the per-function tests. The
 * spellings below are taken from the rules corpus and the existing tests, not invented.
 * </p>
 *
 * <p>
 * Branches taking {@code $operation} operands ({@code contains_all}, {@code shares_elements_with},
 * {@code is_ordered_subset_of} and their negatives) need an Operations-bearing context and are not
 * covered here.
 * </p>
 */
class ExprCompilerBoolDispatchTest
{

    private static IDataTable subject()
    {
        return MockTable.of().name("AE").col("USUBJID", "S01", "S01", "S02")
                .col("DOMAIN", "AE", "AE", "AE").col("AESEQ", "1", "2", "1")
                .col("AEDECOD", "RASH", "RASH", "ACHE").col("AEREL", "Y", "Y", "N")
                .col("AERELN", "1", "1", "2").col("TSVAL", "A", "", "C")
                .col("TSPARMCD", "P1", "P2", "P3").col("TDSTOFF", "P1D", "XX", "P2D")
                .col("NAME", "ABC", "ABD", "ABE").col("VALUE", "ABC", "ZZZ", "ABE")
                .col("X", "12345", "123", "12345").col("STRESU", "mg", "mg", "kg")
                .col("TESTCD", "T1", "T1", "T2").col("MHCAT", "C1", "C1", "C1").build();
    }


    private static EvaluationContext ctx()
    {
        IDataTable dm = MockTable.of().name("DM").col("USUBJID", "S01").build();
        DatasetResolver resolver = name -> "DM".equals(name) ? dm : null;
        return EvaluationContext.builder().table(subject()).datasetResolver(resolver).build();
    }

    /** One representative call per dispatch branch that is drivable from plain columns. */
    private static final List<String> CALLS = List.of("ds_exists(DM)", "ds_not_exists(DM)",
            "var_exists(AEDECOD)", "var_not_exists(NOSUCH)", "var_is_null(TSVAL)",
            "invalid_duration(TDSTOFF, negative=false)",
            "does_not_equal_string_part(NAME, VALUE, regex=\"^AB\")", "has_not_equal_length(X, 5)",
            "has_equal_length(X, 5)", "has_multiple_values_for(AEREL, AERELN)",
            "present_on_multiple_rows_within(AESEQ, min_count=2, within=USUBJID)",
            "empty_within_except_last_row(TSVAL, TSPARMCD, ordering=TSPARMCD)",
            "is_not_unique_relationship(AEDECOD, USUBJID)", "is_not_unique_set([USUBJID, DOMAIN])",
            "is_unique_set([USUBJID, DOMAIN])",
            "is_inconsistent_across_dataset(STRESU, keys=[TESTCD])",
            "inconsistent_enumerated_columns(TSVAL)", "has_same_values(MHCAT)",
            "is_unique_relationship(AEDECOD, keys=[USUBJID])");

    @Test
    void everySupportedBooleanCallCompilesToAUsablePlan()
    {
        EvaluationContext c = ctx();
        List<String> broken = new ArrayList<>();

        for (String call : CALLS)
        {
            try
            {
                BitSet verdict = NativeExprEvaluator.evaluate(CheckExpressionParser.parse(call), c);
                if (verdict == null)
                {
                    broken.add(call + " -> null verdict");
                }
            }
            catch (RuntimeException e)
            {
                // A null plan from the dispatch surfaces here as an NPE.
                broken.add(call + " -> " + e.getClass().getSimpleName() + ": " + e.getMessage());
            }
        }

        assertNotNull(broken);
        if (!broken.isEmpty())
        {
            throw new AssertionError("these boolean calls did not compile to a usable plan:\n  "
                    + String.join("\n  ", broken));
        }
    }

}
