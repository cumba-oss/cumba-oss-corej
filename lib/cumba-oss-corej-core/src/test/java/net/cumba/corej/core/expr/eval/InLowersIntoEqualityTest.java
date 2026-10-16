package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Set;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * ⭐ Phase 6b — <b>D81: {@code in} IS a disjunction of {@code ==}</b>. The pin that keeps it true:
 * for every (cell, member) pair, {@link Primitives#isMember} over the singleton {@code {member}}
 * answers exactly what {@link Primitives#equality} answers for {@code cell == "member"} — so a
 * membership over any set is the member-wise OR of equalities <em>by construction</em>, and a
 * future change to the equality tree that is not mirrored here reds this test instead of silently
 * re-opening the second comparison path (D81a).
 */
class InLowersIntoEqualityTest
{

    /** One table, every cell shape the equality tree branches on. */
    private static final IDataTable T = MockTable.of().col("C", "A", "", null, "10", "abc") // character-declared
            .colLong("N", 10L, 17L, null, 3L, 10L) // numeric-declared
            .build();

    private static final List<String> MEMBERS = List.of("A", "", "10", "010", "17", "abc", "17.0");

    @Test
    void membershipOverASingletonIsExactlyEquality()
    {
        for (String colName : List.of("C", "N"))
        {
            Vector col = VectorLayerTest.col(T, colName);
            for (String member : MEMBERS)
            {
                BitSet eq = Primitives.equality(col, ConstVector.of(member), 5, false, false, false,
                        false);
                for (int row = 0; row < 5; row++)
                {
                    boolean in = Primitives.isMember(col.value(row).cell(), Set.of(member));
                    assertEquals(eq.get(row), in, colName + "[" + row + "] vs \"" + member + "\"");
                }
            }
        }
    }


    /**
     * The D81a defect this closes: a numeric-typed cell now matches a member its {@code ==} would
     * match — {@code N in ["010"]} agrees with {@code N == "010"} (numeric mode, 10 == 10) where
     * the old textual set probe answered false. And the Step C tolerance (D64) reaches membership's
     * numeric arm by construction (D81c's linear scan).
     */
    @Test
    void numericModeAndToleranceReachMembership()
    {
        Vector n = VectorLayerTest.col(T, "N");
        assertTrue(Primitives.isMember(n.value(0).cell(), Set.of("010")),
                "LONG 10 in [\"010\"] — the numeric arm");
        assertTrue(Primitives.isMember(n.value(1).cell(), Set.of("17.0")), "LONG 17 in [\"17.0\"]");
        assertFalse(Primitives.isMember(n.value(3).cell(), Set.of("10")), "3 not in [10]");
    }


    /**
     * ⭐ D13's membership limb, which is D12's equality limb reached through D81: a genuine
     * {@code MissingValue} is a member of NO list, {@code ""} included. This test previously
     * asserted the opposite ({@code missing in [""]} is true, "the literal fold, same as
     * {@code == ""} today") — and the invariant it exists to pin, that membership answers exactly
     * what {@code ==} answers per member, is what carries it: {@code == ""} moved to false for a
     * missing at the same time and for the same ruling, so the two still agree. A BLANK cell is
     * untouched: {@code ""} is a present value and is still a member of a list containing it.
     */
    @Test
    void missingIsAMemberOfNoListAndBlankStillIs()
    {
        Vector c = VectorLayerTest.col(T, "C");
        assertFalse(Primitives.isMember(c.value(2).cell(), Set.of("")),
                "missing in [\"\"] is FALSE (D13) — and == \"\" is false for it too (D12)");
        assertFalse(Primitives.isMember(c.value(2).cell(), Set.of("Y", "N")));
        assertTrue(Primitives.isMember(c.value(1).cell(), Set.of("")),
                "a BLANK cell is a present \"\" (D34 #1) and IS a member of [\"\"]");
    }


    /**
     * D81d(ii) after SVY OQ6: a case-insensitive membership is the author's fold on both sides
     * ({@code upper(X) in upper($v)}), so the primitive compares exactly — it carries no
     * case-insensitive flag any more (B5 review ② L3 retired the dead parameter).
     */
    @Test
    void membershipComparesExactlyAndTheAuthorFoldsBothSides()
    {
        IDataTable t = MockTable.of().col("X", "yes", "No", "", "YES").build();
        Vector x = VectorLayerTest.col(t, "X");
        assertFalse(Primitives.isMember(x.value(0).cell(), Set.of("YES")), "exact: yes is not YES");
        assertTrue(Primitives.isMember(x.value(0).cell(), Set.of("yes")));
        assertFalse(Primitives.isMember(x.value(2).cell(), Set.of("YES")));
        assertTrue(Primitives.isMember(x.value(3).cell(), Set.of("YES")),
                "the author's fold puts the upper-cased cell against the upper-cased member");
    }

}
