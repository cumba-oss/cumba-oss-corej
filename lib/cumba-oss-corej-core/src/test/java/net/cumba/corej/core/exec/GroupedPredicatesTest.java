package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.IDataTable;
import org.junit.jupiter.api.Test;

/**
 * The D-W1-2 / plan-16 unit matrix, run for EACH grouped callable of wave 1 phase 4
 * ({@code PLAN-function-surface-wave1}, pre-go review L7): the two callables use different groupers
 * ({@code GroupSemantics.group} for {@code is_last_in_group}, {@code IndexHelper.groupByPresent}
 * for {@code has_mixed_emptiness_within_group}), so one callable's pass says nothing about the
 * other's. Every case runs through the loader and the runner on real tables (Mockito-free), exactly
 * as a corpus rule would: a compiled binding holding the call, read per row by {@code $x == true}.
 * The fired rows are the rows the predicate holds on.
 */
class GroupedPredicatesTest
{

    private static final double NOISE = 4.9999999999994;

    // ================================================================== is_last_in_group

    @Test
    void isLast_aNoiseKeyFormsTwoGroups() throws Exception
    {
        // 4.9999999999994 and 5.0 are two groups (D64h, exact identity): a merged group would make
        // row 1 the only last row.
        IDataTable t = RealTables.of("T").dbl("G", NOISE, NOISE, 5.0).lng("ORD", 1L, 2L, 1L)
                .build();
        assertEquals(Set.of(1L, 2L), fired(last("group=[G]"), t));
    }


    @Test
    void isLast_negativeZeroAndZeroAreOneGroup() throws Exception
    {
        IDataTable t = RealTables.of("T").dbl("G", -0.0, 0.0).lng("ORD", 1L, 2L).build();
        assertEquals(Set.of(1L), fired(last("group=[G]"), t), "one group (D84), row 1 is its last");
    }


    @Test
    void isLast_aMissingGroupKeyIsDroppedByDefaultAndKeptOnDemand() throws Exception
    {
        IDataTable t = RealTables.of("T").str("G", "S1", null).lng("ORD", 1L, 5L).build();
        assertEquals(Set.of(0L), fired(last("group=[G]"), t),
                "DROP_MISSING_KEYS default: the missing-keyed row answers false");
        assertEquals(Set.of(0L), fired(last("group=[G], keep_missings=false"), t));
        assertEquals(Set.of(0L, 1L), fired(last("group=[G], keep_missings=true"), t),
                "keep_missings=true folds the missing key into its own group");
    }


    @Test
    void isLast_ec44PartialAbsenceGroupsOnTheSurvivorsAndTotalAbsenceAnswersNothing()
        throws Exception
    {
        IDataTable t = RealTables.of("T").str("G", "S1", "S1", "S2").lng("ORD", 1L, 2L, 1L).build();
        assertEquals(Set.of(1L, 2L), fired(last("group=[G, EPOCH]"), t),
                "the absent EPOCH is dropped from the key");
        assertEquals(Set.of(), fired(last("group=[EPOCH]"), t),
                "every group column absent: the operator's empty result, no row is last");
    }


    @Test
    void isLast_tiesAtTheMaximumAreAllLastByOneIdentity() throws Exception
    {
        // D-W1-2: -0.0 and 0.0 tie (one identity), in either row order.
        IDataTable zeros = RealTables.of("T").str("G", "S", "S", "S").dbl("ORD", -0.0, 0.0, -1.0)
                .build();
        assertEquals(Set.of(0L, 1L), fired(last("group=[G]"), zeros));
        IDataTable zerosReversed = RealTables.of("T").str("G", "S", "S", "S")
                .dbl("ORD", 0.0, -1.0, -0.0).build();
        assertEquals(Set.of(0L, 2L), fired(last("group=[G]"), zerosReversed));
        // A noise pair does NOT tie: only 5.0 is last.
        IDataTable noise = RealTables.of("T").str("G", "S", "S").dbl("ORD", NOISE, 5.0).build();
        assertEquals(Set.of(1L), fired(last("group=[G]"), noise));
        // A CHAR ordering holding 5 and 5.0: numerically equal, identity-distinct — the byte order
        // of the text decides, in either row order.
        IDataTable chars = RealTables.of("T").str("G", "S", "S").str("ORD", "5", "5.0").build();
        assertEquals(Set.of(1L), fired(last("group=[G]"), chars));
        IDataTable charsReversed = RealTables.of("T").str("G", "S", "S").str("ORD", "5.0", "5")
                .build();
        assertEquals(Set.of(0L), fired(last("group=[G]"), charsReversed));
        // An all-missing group: every row ties at the maximum, every row is last (D34 #5).
        IDataTable missing = RealTables.of("T").str("G", "S", "S").lng("ORD", null, null).build();
        assertEquals(Set.of(0L, 1L), fired(last("group=[G]"), missing));
        // A duplicate ordering value in the source: both copies are last.
        IDataTable dup = RealTables.of("T").str("G", "S", "S", "S").lng("ORD", 1L, 2L, 2L).build();
        assertEquals(Set.of(1L, 2L), fired(last("group=[G]"), dup));
    }


    @Test
    void isLast_numericOrderingIsNumericNotLexicographic() throws Exception
    {
        IDataTable t = RealTables.of("T").str("G", "S", "S", "S").str("ORD", "9", "12", "10")
                .build();
        assertEquals(Set.of(1L), fired(last("group=[G]"), t), "12 > 9 numerically");
    }


    @Test
    void isLast_nonNumericOrderingComparesByText() throws Exception
    {
        IDataTable t = RealTables.of("T").str("G", "S", "S", "S").str("ORD", "B", "A", "C").build();
        assertEquals(Set.of(2L), fired(last("group=[G]"), t), "the textual maximum C is last");
    }


    @Test
    void isLast_quotedColumnsAndKeepMissingsWithoutAGroupAreLoadErrors() throws Exception
    {
        assertLoadError("is_last_in_group(ordering=\\\"ORD\\\", group=[G])", "column reference");
        assertLoadError("is_last_in_group(ordering=ORD, group=[\\\"G\\\"])", "column reference");
        assertLoadError("is_last_in_group(ordering=ORD, keep_missings=true)", "group");
        assertLoadError("is_last_in_group(ordering=ORD, group=[], keep_missings=true)",
                "keep_missings");
    }

    // ==================================================================
    // has_mixed_emptiness_within_group


    @Test
    void mixed_aNoiseKeyFormsTwoGroups() throws Exception
    {
        IDataTable t = RealTables.of("T").dbl("G", NOISE, NOISE, 5.0).str("V", "A", "", "B")
                .build();
        assertEquals(Set.of(0L, 1L), fired(mixed("V, group=[G]"), t),
                "the noise group {A, \"\"} is mixed, {B} is not");
    }


    @Test
    void mixed_negativeZeroAndZeroAreOneGroup() throws Exception
    {
        IDataTable t = RealTables.of("T").dbl("G", -0.0, 0.0).str("V", "A", "").build();
        assertEquals(Set.of(0L, 1L), fired(mixed("V, group=[G]"), t));
    }


    @Test
    void mixed_aMissingGroupKeyFoldsByDefaultAndIsDroppedOnDemand() throws Exception
    {
        IDataTable t = RealTables.of("T").str("G", "S1", null, null).str("V", "A", "", "B").build();
        assertEquals(Set.of(1L, 2L), fired(mixed("V, group=[G]"), t),
                "KEEP_MISSING_KEYS default: the missing keys fold into one mixed group");
        assertEquals(Set.of(1L, 2L), fired(mixed("V, group=[G], keep_missings=true"), t));
        assertEquals(Set.of(), fired(mixed("V, group=[G], keep_missings=false"), t),
                "keep_missings=false drops the missing-keyed rows; S1 alone is not mixed");
    }


    @Test
    void mixed_ec44AbsentGroupColumnsAndEc45AbsentSubject() throws Exception
    {
        IDataTable t = RealTables.of("T").str("G", "S1", "S1", "S2").str("V", "A", "", "B").build();
        assertEquals(Set.of(0L, 1L), fired(mixed("V, group=[G, EPOCH]"), t),
                "EC-44 partial: the absent EPOCH is dropped");
        assertEquals(Set.of(0L, 1L, 2L), fired(mixed("V, group=[EPOCH]"), t),
                "EC-44 total: one whole-table group, which is mixed");
        assertEquals(Set.of(0L, 1L, 2L), fired(mixed("V"), t), "no group: one total group");
        assertEquals(Set.of(), fired(mixed("ABSENT, group=[G]"), t),
                "EC-45 §1.3(2): an absent subject is all-unpopulated, nothing is mixed");
    }


    @Test
    void mixed_ec23QualifiersScopeTheRows() throws Exception
    {
        IDataTable t = RealTables.of("T").str("G", "S1", "S1", "S1").str("V", "A", "", "")
                .str("Q", "y", " ", null).build();
        assertEquals(Set.of(0L, 1L, 2L), fired(mixed("V, group=[G]"), t));
        assertEquals(Set.of(), fired(mixed("V, group=[G], qualifying_any_populated=[Q]"), t),
                "only row 0 qualifies (Q populated), and one row alone is not mixed");
        assertEquals(Set.of(), fired(mixed("V, group=[G], qualifying_any_populated=[NOPE]"), t),
                "EC-23: an all-absent qualifier list qualifies no row");
    }


    @Test
    void mixed_quotedColumnsAndKeepMissingsWithoutAGroupAreLoadErrors() throws Exception
    {
        assertLoadError("has_mixed_emptiness_within_group(\\\"V\\\", group=[G])",
                "column reference");
        assertLoadError("has_mixed_emptiness_within_group(V, group=[\\\"G\\\"])",
                "column reference");
        assertLoadError("has_mixed_emptiness_within_group(V, keep_missings=true)", "keep_missings");
        assertLoadError("has_mixed_emptiness_within_group(V, group=[], keep_missings=true)",
                "keep_missings");
    }

    // ================================================================== helpers


    private static String last(String arguments)
    {
        return "is_last_in_group(ordering=ORD, " + arguments + ")";
    }


    private static String mixed(String arguments)
    {
        return "has_mixed_emptiness_within_group(" + arguments + ")";
    }


    private static Set<Long> fired(String binding, IDataTable table) throws Exception
    {
        Rule rule = load(binding);
        assertNull(rule.getLoadError(), rule.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, table);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        Set<Long> rows = new TreeSet<>();
        result.getViolations().forEach(v -> rows.add(v.getRow()));
        return rows;
    }


    private static void assertLoadError(String binding, String naming) throws Exception
    {
        Rule rule = load(binding);
        assertNotNull(rule.getLoadError(), "expected a load error for " + binding);
        assertTrue(rule.getLoadError().contains(naming),
                binding + " → " + rule.getLoadError() + " should name " + naming);
    }


    private static Rule load(String binding) throws Exception
    {
        // Sensitivity is authored: a Check reading only the binding derives none, and a rule that
        // is not row-based collapses its BitSet to ONE finding (RuleRunner's non-row-based arm).
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"W1-GROUPED\"},"
                + "\"Sensitivity\":\"Record\"," + "\"Bindings\":[{\"name\":\"$p\",\"expression\":\""
                + binding + "\"}]," + "\"Check\":{\"expression\":\"$p == true\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"$p\"]}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(rule, "the rule loads");
        return rule;
    }

}
