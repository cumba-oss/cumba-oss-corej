package net.cumba.corej.core.exec;

import static net.cumba.corej.core.exec.SequenceFunctionTest.fired;
import static net.cumba.corej.core.exec.SequenceFunctionTest.rows;
import static net.cumba.corej.core.exec.SequenceFunctionTest.se;
import static net.cumba.corej.core.exec.SequenceFunctionTest.ta;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.expr.eval.spi.CompilerDispatchedCalls;
import org.junit.jupiter.api.Test;

/**
 * Runbook W1b RO ({@code PLAN-engine-gap-row-ordinal} §5 R2 / R4, §6.2 R6; coordinator D11): the
 * list predicates {@code is_prefix_of(a, b)} (and {@code is_not_prefix_of}),
 * {@code is_increasing(list)} (strict) and the list transform {@code collapse_runs(list)} —
 * ordinary registry functions over vectors, so they read per-row lists ({@code sequence}'s output)
 * and dataset-level lists alike. Element identity is the set-operation element's (text exact, a
 * missing by identity). {@code is_increasing} compares numerically iff every element is numeric,
 * else as text; an empty or one-element list is increasing; a missing element answers false (the
 * two-valued BOOLEAN kind has no missing).
 */
class OrderedListFunctionsTest
{

    private static final String AB = "minus([\"A\", \"B\", \"X\"], subtract=[\"X\"])";

    private static final String ABC = "minus([\"A\", \"B\", \"C\", \"X\"], subtract=[\"X\"])";

    private static final String EMPTY = "minus([\"X\"], subtract=[\"X\"])";

    /** The rows a dataset-level verdict fires on, row-guarded so every row is judged. */
    private static int all(String check, String... bindings)
    {
        return fired(se(), "not empty(USUBJID) and (" + check + ")", bindings).size();
    }


    @Test
    void isPrefixOfOverDatasetLevelLists()
    {
        assertEquals(7, all("is_prefix_of($a, $b)", "$a", AB, "$b", ABC));
        assertEquals(0, all("is_prefix_of($b, $a)", "$a", AB, "$b", ABC));
        assertEquals(7, all("is_prefix_of($a, $a)", "$a", AB), "a list is its own prefix");
        assertEquals(7, all("is_prefix_of($e, $a)", "$a", AB, "$e", EMPTY),
                "the empty list is a prefix of every list");
        assertEquals(0, all("is_prefix_of($a, $e)", "$a", AB, "$e", EMPTY));
        assertEquals(7, all("is_not_prefix_of($b, $a)", "$a", AB, "$b", ABC));
        assertEquals(all("not is_prefix_of($b, $a)", "$a", AB, "$b", ABC),
                all("is_not_prefix_of($b, $a)", "$a", AB, "$b", ABC));
        assertEquals(0, all("is_prefix_of($a, $n)", "$a", AB, "$n", "[\"a\", \"b\"]"),
                "text exact: no case folding");
    }


    @Test
    void isPrefixOfOverPerRowLists()
    {
        // each row's own-group sequence against the dataset-level TA order of arm A1
        String seq = "sequence(ETCD, group=[USUBJID], by=[asc(SESTDTC)])";
        String a1 = "sequence(ETCD, domain=\"TA\", by=[asc(TAETORD)], filter=(ARMCD == \"A1\"))";
        assertEquals(List.of(), fired(se(), "not is_prefix_of($s, $t)", "$s", seq, "$t", a1),
                "S1 and S2 walked SCRN, TRT, FU; S3 SCRN — all prefixes of A1's plan");
        String a2 = "sequence(ETCD, domain=\"TA\", by=[asc(TAETORD)], filter=(ARMCD == \"A2\"))";
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L),
                fired(se(), "not is_prefix_of($s, $t)", "$s", seq, "$t", a2));
    }


    @Test
    void isIncreasingIsStrictNumericIffAllNumericElseText()
    {
        assertEquals(7, all("is_increasing($l)", "$l", "[1, 2, 3]"));
        assertEquals(0, all("is_increasing($l)", "$l", "[1, 2, 2]"), "strict");
        assertEquals(0, all("is_increasing($l)", "$l", "[3, 2]"));
        assertEquals(7, all("is_increasing($l)", "$l", "[3]"), "one element");
        assertEquals(7, all("is_increasing($e)", "$e", EMPTY), "empty");
        assertEquals(7, all("is_increasing($l)", "$l", "[\"9\", \"10\"]"),
                "every element numeric: numeric order (text would say 9 > 10)");
        assertEquals(0, all("is_increasing($l)", "$l", "[\"10\", \"9\"]"));
        assertEquals(7, all("is_increasing($l)", "$l", "[\"9\", \"x\"]"),
                "one non-numeric element: text order");
        assertEquals(0, all("is_increasing($l)", "$l", "[\"b\", \"a\"]"));
    }


    @Test
    void isIncreasingOverPerRowListsAndAMissingElement()
    {
        // S2's SESEQ in date order is 1, 3, 2 — not increasing; S1's 1, 2, 3 is; S3's one is
        assertEquals(List.of(4L, 5L, 6L), fired(se(), "not is_increasing($s)", "$s",
                "sequence(SESEQ, group=[USUBJID], by=[asc(SESTDTC)])"));
        // a missing element: S2's SEDY by SESEQ is 1, missing, 2 — the predicate is false, so
        // the positive spelling does not fire and the negated one does
        assertEquals(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L), fired(se(), "is_increasing($d)", "$d",
                "sequence(SEDY, group=[USUBJID], by=[asc(SESEQ)], filter=(not empty(SEDY)) )"),
                "filtered: S2 reads 1, 2 and S3 the empty list — increasing");
        assertEquals(List.of(4L, 5L, 6L, 7L), fired(se(), "not is_increasing($d)", "$d",
                "sequence(SEDY, group=[USUBJID], by=[asc(SESEQ)])"));
    }


    @Test
    void collapseRunsCollapsesAdjacentDuplicatesOnly()
    {
        assertEquals(7,
                all("count(collapse_runs($l)) == 4", "$l", "[\"S\", \"T\", \"T\", \"F\", \"T\"]"));
        assertEquals(7, all("count(collapse_runs($e)) == 0", "$e", EMPTY));
        // row B: an EPOCH that is not one contiguous block in the arm's TAETORD order
        List<List<?>> runs = rows(
                "collapse_runs(sequence(EPOCH, group=[ARMCD], by=[asc(TAETORD)]))", ta());
        assertEquals(List.of("SCREENING", "TREATMENT", "FOLLOW-UP"), runs.get(0));
        assertEquals(List.of("SCREENING", "FOLLOW-UP"), runs.get(3));
        assertEquals(List.of(),
                fired(ta(),
                        "count(collapse_runs(sequence(EPOCH, group=[ARMCD], by=[asc(TAETORD)])))"
                                + " != count(distinct(EPOCH, group=[ARMCD]))"));
    }


    @Test
    void theRegistryFunctionsAreNotCompilerDispatched()
    {
        // D11: ordinary registry functions over vectors, not the $-names-only dispatch of
        // is_ordered_subset_of — the dispatch roster keeps its 33 names
        assertEquals(33, CompilerDispatchedCalls.booleanCallNames().size());
        for (String name : List.of("is_prefix_of", "is_not_prefix_of", "is_increasing"))
        {
            assertTrue(!CompilerDispatchedCalls.booleanCallNames().contains(name), name);
        }
        // a scalar where a list is read is a rule-definition error, never a silent singleton
        net.cumba.corej.core.expr.RuleDefinitionException scalar = org.junit.jupiter.api.Assertions
                .assertThrows(net.cumba.corej.core.expr.RuleDefinitionException.class,
                        () -> SequenceFunctionTest.run(se(), "is_increasing(SESEQ)"));
        assertTrue(String.valueOf(scalar.getMessage()).contains("takes a list"),
                scalar.getMessage());
    }
}
