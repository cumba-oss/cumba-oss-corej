package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * <b>Plan 2, review finding R-1 / ruling V1</b> — {@code count} / {@code size}, and the
 * {@code normalize_space} twin from R-12.
 *
 * <p>
 * ⛔⛔ <b>The defect these exist to prevent.</b> {@code len} is STRING length. On a list-valued
 * operand it silently answers the length of the rendered list:
 * {@code len(["Unique Subject Identifier"])} is 27, not 1. A rule testing
 * {@code len(candidates) > 1} for "more than one candidate" is therefore true for EVERY non-empty
 * list, and {@code DRAFT-900044}'s INFO level fired on data that conformed. There was no
 * list-cardinality function in the registry at all.
 * </p>
 */
class ListCardinalityFunctionsTest
{

    /** Applies a 1-arity value builtin to a single constant cell, through the real registry. */
    private static Vector apply(String name, Object value)
    {
        Object out = FunctionRegistry.resolve(name, 1).apply(EvalRun.ofRowCount(1),
                List.of(ConstVector.of(value)));
        return (Vector) out;
    }


    private static long call(String name, Object value)
    {
        return Long.parseLong(apply(name, value).asString(0));
    }


    private static String callString(String name, Object value)
    {
        return apply(name, value).asString(0);
    }


    /**
     * ⭐ The regression this whole item is about, stated as a contrast: on the same one-element
     * list, {@code len} answers 27 and {@code count} answers 1. Asserting {@code count} alone would
     * not record WHY the function had to exist.
     */
    @Test
    void countIsElementCountWhereLenIsStringLength()
    {
        List<String> oneCandidate = List.of("Unique Subject Identifier");

        assertEquals(1L, call("count", oneCandidate), "one candidate is ONE");
        assertEquals(27L, call("len", oneCandidate),
                "len sees the RENDERED list '[Unique Subject Identifier]' — the defect");
    }


    /** The cardinality test the INFO level actually needs: >1 only when there really are >1. */
    @Test
    void countDiscriminatesOneCandidateFromSeveral()
    {
        assertEquals(1L, call("count", List.of("Non-Host Organism Identifier")));
        assertEquals(3L, call("count",
                List.of("Non-Host Organism Identifier", "Non-host Organism ID", "Other")));
    }


    /** {@code size} is the same function under its other name. */
    @Test
    void sizeIsAnAliasOfCount()
    {
        assertEquals(2L, call("size", List.of("a", "b")));
    }


    /**
     * A present scalar counts 1; a missing one counts 0.
     *
     * <p>
     * ⚑ An EMPTY STRING counts 0, not 1, because {@code ScalarSemantics.isMissing} treats it as
     * missing everywhere in this engine — the same convention that makes {@code len("")} and
     * {@code len(missing)} both 0. This assertion originally read 1, from my own notion of
     * "present"; the engine's established convention is the one that governs, and a function that
     * disagreed with it would be a second trap rather than a fix.
     * </p>
     */
    @Test
    void aScalarCountsOneAndAnAbsentValueCountsZero()
    {
        assertEquals(1L, call("count", "a label"));
        assertEquals(0L, call("count", null));
        assertEquals(0L, call("count", ""),
                "the engine treats \"\" as missing (ScalarSemantics.isMissing), and count agrees");
    }


    /**
     * ⛔ R-12 — the expressible twin of {@code RuleRunner.normaliseLabel}'s whitespace half. Without
     * it a rule can only {@code trim}, while the engine also collapses internal runs, so a dataset
     * label with a doubled internal space raised a false ERROR against an identical published one.
     */
    @Test
    void normalizeSpaceTrimsAndCollapsesInternalRuns()
    {
        assertEquals("Reported Name of Drug",
                callString("normalize_space", "  Reported Name of Drug  "));
        assertEquals("Reported Name of Drug",
                callString("normalize_space", "Reported  Name\tof   Drug"));
        assertEquals("", callString("normalize_space", "   "));
    }
}
