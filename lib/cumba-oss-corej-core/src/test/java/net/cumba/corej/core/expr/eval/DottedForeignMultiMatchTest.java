package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.cumba.corej.core.exec.DatasetLookup;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.JoinLookup;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ <b>The two surviving tests of the retired {@code NativeJoinedMultiMatchParityTest}, preserved
 * deliberately — and restored after review round 1 caught that they had been lost.</b>
 *
 * <p>
 * That class existed to assert native-vs-legacy parity for resolving an <b>unqualified</b> foreign
 * reference out of a {@code Match_Datasets} join. {@code PLAN-unqualified-name-primary-only}
 * deleted that machinery, so eleven of its thirteen tests asserted behaviour the owner's ruling
 * abolished and were retired with it. These two did not: they pin the <b>QUALIFIED</b>
 * ({@code DS.COL}) path, which the ruling leaves untouched and which is the half that must keep
 * working.
 * </p>
 *
 * <p>
 * ⛔ The plan said in so many words: <i>"The two dotted tests are <b>preserved</b> — move them to a
 * qualified-resolution test rather than deleting them with the class."</i> They were not; the class
 * went and nothing replaced them, and the commit claimed {@code NativeJoinedLhsTest} covered it
 * when that class gained no test and builds only a 1-to-1 lookup. ⇒ The specific thing that was
 * briefly unpinned: a 1-to-many join whose FIRST matched child cell is missing and a LATER match is
 * non-null. The dotted path is scalar first-match and must IGNORE the later value, in name and
 * value position alike — a first-missing/later-non-null regression would otherwise be invisible.
 * </p>
 *
 * <p>
 * ⭐ <b>Re-pointed at the PRODUCTION lookup on 2026-09-25</b>
 * ({@code PLAN-retire-dead-multi-match-lookup} wave A). Until then the join was a hand-written
 * {@code JoinLookup} double whose "all matches" payload nothing read — the dotted path is scalar,
 * so the double's multi-match was a comment, not a behaviour. Now the {@code SUPP} join is a real
 * {@link DatasetLookup} over a child table with TWO rows for primary row 0, and what the two tests
 * pin is {@code DatasetLookup}'s own first-wins indexing (the first child row for a key is the one
 * the map keeps). ⚠ That makes a third arm owed and present: the same data with the two child rows
 * SWAPPED must fire on row 0. Without it, a lookup that answered nothing (or the missing cell) for
 * any duplicated key would pass both original arms — "first-wins" and "duplicates yield nothing"
 * agree everywhere except on the row whose first match is the non-null one.
 * </p>
 */
class DottedForeignMultiMatchTest
{

    private static final String KEY = "USUBJID";

    private static final String TARGET = "QVAL";

    private static final String FOREIGN_DS = "SUPP";

    private static BitSet bits(int... rows)
    {
        BitSet bs = new BitSet();
        for (int r : rows)
        {
            bs.set(r);
        }
        return bs;
    }


    /** The primary: two subjects, {@code AVAL} 1 and 2. */
    private static IDataTable primary()
    {
        return MockTable.of().col(KEY, "S1", "S2").col("AVAL", "1", "2").build();
    }


    /**
     * The child, keyed on {@code USUBJID}: subject {@code S1} has TWO rows (a 1-to-many join for
     * primary row 0), subject {@code S2} one. {@code s1First} and {@code s1Second} are the two
     * {@code QVAL} cells of {@code S1} in child-row order; {@code null} is a missing cell.
     */
    private static IDataTable child(String s1First, String s1Second)
    {
        return MockTable.of().name(FOREIGN_DS).col(KEY, "S1", "S1", "S2")
                .col(TARGET, s1First, s1Second, "2").build();
    }


    /**
     * Builds a context whose single join ({@code SUPP}) is the production {@link DatasetLookup}
     * over {@code child}, keyed on {@code USUBJID}. The foreign schema carries {@code QVAL} so the
     * native {@code joinedColumnVector} schema-probe recognises it as join-carried.
     */
    private static EvaluationContext ctx(IDataTable primary, IDataTable child)
    {
        DatasetResolver resolver = ds -> FOREIGN_DS.equals(ds) ? child : null;
        JoinLookup lookup = Objects.requireNonNull(
                DatasetLookup.build(FOREIGN_DS, child, List.of(KEY)),
                "DatasetLookup.build answered null for a non-null dataset");
        return EvaluationContext.builder().table(primary).datasetResolver(resolver)
                .joinedDatasets(Map.of(FOREIGN_DS, lookup)).build();
    }


    /** A comparison whose right side is a column reference (value position resolves it). */
    private static String refLeaf(String name, String operator, String reference)
    {
        return name + ("not_equal_to".equals(operator) ? " != " : " == ") + reference;
    }


    /** Runs one canonical expression through the native engine (the legacy engine is retired). */
    private static BitSet nativeBits(String source, EvaluationContext context)
    {
        return NativeExprEvaluator
                .evaluate(net.cumba.corej.core.expr.CheckExpressionParser.parse(source), context);
    }

    // Dotted DS.COL reference -- NAME position, then VALUE position ----------------------------


    @Test
    void dottedForeignName_multiMatch_firstMatchWins()
    {
        // Dotted SUPP.QVAL in the NAME position. The dotted path is scalar first-match (the native
        // dottedVector calls JoinLookup.lookupValue), so the 1-to-many join has exactly one answer
        // per row: the FIRST child row for the key. Row 0: S1's first match has a MISSING QVAL ->
        // the joined value is missing -> AVAL "1" does not match (no fire). Row 1: "2" == AVAL "2"
        // -> fires. S1's LATER non-null match ("1") is intentionally NOT used by the dotted path.
        EvaluationContext context = ctx(primary(), child(null, "1"));
        BitSet r = nativeBits(refLeaf(FOREIGN_DS + '.' + TARGET, "equal_to", "AVAL"), context);

        assertEquals(bits(1), r, "dotted scalar first-match: only row 1 (first match=2) fires");
    }


    @Test
    void dottedForeignValuePosition_multiMatch_firstMatchWins()
    {
        // Dotted reference in the VALUE position: AVAL == SUPP.QVAL. The native dottedVector uses
        // scalar first-match here too. Row 0's first match is missing -> AVAL "1" does not equal a
        // missing; row 1's first (and only) match "2" == AVAL "2" -> fires.
        EvaluationContext context = ctx(primary(), child(null, "7"));
        BitSet r = nativeBits(refLeaf("AVAL", "equal_to", FOREIGN_DS + '.' + TARGET), context);

        assertEquals(bits(1), r, "dotted value-position scalar first-match: only row 1 fires");
    }


    /**
     * ⭐ The non-vacuity arm: the SAME data with S1's two child rows swapped, so the non-null cell
     * comes first. Now row 0 MUST fire — which is the one outcome that separates "first-wins" from
     * a lookup that ignores child-row order (answering nothing, or the missing cell, for any
     * duplicated key). The two arms above cannot see that difference: on their data both shapes
     * answer "no fire" for row 0.
     */
    @Test
    void swappedChildRows_firstMatchIsTheNonNullOne_row0Fires()
    {
        EvaluationContext context = ctx(primary(), child("1", null));

        BitSet name = nativeBits(refLeaf(FOREIGN_DS + '.' + TARGET, "equal_to", "AVAL"), context);
        assertEquals(bits(0, 1), name,
                "with the non-null child row FIRST, row 0's first match is \"1\" == AVAL \"1\" and"
                        + " must fire — a lookup blind to child-row order would answer no fire here"
                        + " and still pass the two arms above");

        BitSet value = nativeBits(refLeaf("AVAL", "equal_to", FOREIGN_DS + '.' + TARGET), context);
        assertEquals(bits(0, 1), value, "the same on the VALUE position");
    }

}
