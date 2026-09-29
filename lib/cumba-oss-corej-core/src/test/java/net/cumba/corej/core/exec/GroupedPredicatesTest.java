package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import java.util.TreeSet;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * The D-W1-2 / plan-16 unit matrix, run for EACH grouped callable of wave 1 phase 4
 * ({@code PLAN-function-surface-wave1}, pre-go review L7): the two callables use different groupers
 * ({@code GroupSemantics.group} for {@code is_last_in_group}, {@code IndexHelper.groupByPresent}
 * for {@code has_mixed_emptiness_within_group}), so one callable's pass says nothing about the
 * other's. Every case runs through the loader and the runner on real tables (Mockito-free), exactly
 * as a corpus rule would: a compiled binding holding the call, read per row by {@code $x == true}.
 * The fired rows are the rows the predicate holds on. No case authors {@code Sensitivity}: the rule
 * derives {@code Record} from the binding's column parameters alone (review round 1 M3), and
 * {@link #bindingOnlyCheckDerivesRecordSensitivityAndReportsEveryRow} pins that a rule whose Check
 * reads only the binding still reports one finding per row.
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
        // Both table shapes: NZL O1 stores a buffer-built -0.0 as 0.0, so build() alone never
        // enters the key-level zero fold; buildRaw() keeps the -0.0 (the computed-key shape).
        IDataTable t = RealTables.of("T").dbl("G", -0.0, 0.0).lng("ORD", 1L, 2L).build();
        assertEquals(Set.of(1L), fired(last("group=[G]"), t), "one group (D84), row 1 is its last");
        IDataTable raw = RealTables.of("T").dbl("G", -0.0, 0.0).lng("ORD", 1L, 2L).buildRaw();
        assertEquals(Set.of(1L), fired(last("group=[G]"), raw), "raw -0.0: still one group");
    }


    @Test
    void isLast_positionalKeepMissingsIsTheSameBinding() throws Exception
    {
        // D9: keep_missings bound by position is the same binding as the keyword (review round
        // 1 M1: the kwargs-only reader bound the positional and ignored it).
        IDataTable t = RealTables.of("T").str("G", "S1", null).lng("ORD", 1L, 5L).build();
        assertEquals(Set.of(0L, 1L), fired("is_last_in_group(ORD, [G], true)", t),
                "positional keep_missings=true keeps the missing-keyed row");
        assertEquals(Set.of(0L), fired("is_last_in_group(ORD, [G], false)", t));
        assertLoadError("is_last_in_group(ORD, [], true)", "keep_missings");
        assertLoadError("is_last_in_group(ORD, [G], 1)", "boolean literal");
    }


    @Test
    void isLast_arityIsALoadError() throws Exception
    {
        assertLoadError("is_last_in_group(ORD, [G], true, G)", "at most 3");
        assertLoadError("is_last_in_group(ordering=ORD)", "group");
        assertLoadError("is_last_in_group(group=[G])", "ordering");
    }


    @Test
    void isLast_domainPrefixOperandsResolve() throws Exception
    {
        // ordering=--SEQ / group=[--GRP] resolve against the evaluated domain (AE) before the plan
        // runs, exactly as the plain names do.
        IDataTable ae = RealTables.of("AE").str("AEGRP", "S", "S").lng("AESEQ", 1L, 2L).build();
        Rule rule = load("is_last_in_group(ordering=--SEQ, group=[--GRP])");
        assertNull(rule.getLoadError(), rule.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae, _ -> null, "AE");
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(Set.of(1L), rows(result));
    }


    @Test
    void bindingOnlyCheckDerivesRecordSensitivityAndReportsEveryRow() throws Exception
    {
        // Review round 1 M3: a rule whose Check reads ONLY the binding — `$p == true` over an
        // is_last_in_group call with no positional argument — used to derive no Sensitivity (the
        // classifier walk read no operand from ordering= / group=), so RuleRunner treated it as
        // non-row-based and collapsed the per-row BitSet to ONE finding. The classifier now reads
        // the column references bound to the call's COLUMN_REFERENCE parameters.
        Rule rule = load("is_last_in_group(ordering=ORD, group=[G])");
        assertEquals(Sensitivity.RECORD, rule.getSensitivity(),
                "derived from the binding's column parameters, not authored");
        IDataTable t = RealTables.of("T").str("G", "S", "S", "S").lng("ORD", 1L, 2L, 2L).build();
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, t);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(2, result.getViolations().size(), "one finding per tied row, never collapsed");
        assertEquals(Set.of(1L, 2L), rows(result));
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
        // D-W1-2: -0.0 and 0.0 tie (one identity), in either row order. Buffer-built (build())
        // AND raw (buildRaw()): NZL O1 stores a buffer-built -0.0 as 0.0, so the build() cases
        // alone never exercise compareOrdering's zero fold (review round 1 M2 — proved by
        // deleting normalizeZero: the raw cases red, the built ones stay green).
        IDataTable zeros = RealTables.of("T").str("G", "S", "S", "S").dbl("ORD", -0.0, 0.0, -1.0)
                .build();
        assertEquals(Set.of(0L, 1L), fired(last("group=[G]"), zeros));
        IDataTable zerosReversed = RealTables.of("T").str("G", "S", "S", "S")
                .dbl("ORD", 0.0, -1.0, -0.0).build();
        assertEquals(Set.of(0L, 2L), fired(last("group=[G]"), zerosReversed));
        IDataTable zerosRaw = RealTables.of("T").str("G", "S", "S", "S").dbl("ORD", -0.0, 0.0, -1.0)
                .buildRaw();
        assertEquals(Set.of(0L, 1L), fired(last("group=[G]"), zerosRaw), "raw -0.0 first");
        IDataTable zerosRawReversed = RealTables.of("T").str("G", "S", "S", "S")
                .dbl("ORD", 0.0, -1.0, -0.0).buildRaw();
        assertEquals(Set.of(0L, 2L), fired(last("group=[G]"), zerosRawReversed), "raw -0.0 last");
        IDataTable zerosRawOnly = RealTables.of("T").str("G", "S", "S").dbl("ORD", -0.0, 0.0)
                .buildRaw();
        assertEquals(Set.of(0L, 1L), fired(last("group=[G]"), zerosRawOnly),
                "a two-row raw tie: both last, whichever the max search visits first");
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
        // Two DIFFERENT missing markers order by their value byte (D34 #5-1), never by their
        // text: MIS__ (._, byte 60) ranks below MIS (., byte 64), so only the `.` row is last —
        // where the text order (`.` < `._`) would have made the `._` row last.
        IDataTable markers = RealTables.of("T").str("G", "S", "S", "S")
                .dbl("ORD", MissingValue.MIS__.asDouble(), MissingValue.MIS.asDouble(),
                        MissingValue.MIS__.asDouble())
                .build();
        assertEquals(Set.of(1L), fired(last("group=[G]"), markers), "`.` (64) above `._` (60)");
        IDataTable markersReversed = RealTables.of("T").str("G", "S", "S")
                .dbl("ORD", MissingValue.MIS.asDouble(), MissingValue.MIS__.asDouble()).build();
        assertEquals(Set.of(0L), fired(last("group=[G]"), markersReversed));
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
    void isLast_aMixedCharOrderingIsNotATotalOrderAndTheAnswerFollowsRowOrder() throws Exception
    {
        // Pins TODAY'S behaviour, not a ruling (review round 1, lane 3 L2): on a CHAR column
        // mixing numeric-looking and textual values the ranking cycles — 3 < 10 (numeric),
        // 10 < 2x (text), 2x < 3 (text) — so the maximum the linear search finds depends on the
        // row order. The same multiset {3, 3, 10, 2x} answers two different sets. No register
        // ruling orders such a column and both corpus sites order by the numeric SESEQ; filed in
        // plans/findings/FINDINGS-function-surface-wave1-review.md.
        IDataTable a = RealTables.of("T").str("G", "S", "S", "S", "S")
                .str("ORD", "3", "10", "2x", "3").build();
        assertEquals(Set.of(0L, 3L), fired(last("group=[G]"), a),
                "3 → 10 → 2x → 3: the search ends on 3, both 3 rows are last");
        IDataTable b = RealTables.of("T").str("G", "S", "S", "S", "S")
                .str("ORD", "3", "3", "10", "2x").build();
        assertEquals(Set.of(3L), fired(last("group=[G]"), b),
                "3 → 3 → 10 → 2x: the search ends on 2x, only that row is last");
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
        IDataTable raw = RealTables.of("T").dbl("G", -0.0, 0.0).str("V", "A", "").buildRaw();
        assertEquals(Set.of(0L, 1L), fired(mixed("V, group=[G]"), raw), "raw -0.0: one group");
    }


    @Test
    void mixed_positionalKeepMissingsIsTheSameBinding() throws Exception
    {
        // D9 (review round 1 M1): the fourth positional is keep_missings.
        IDataTable t = RealTables.of("T").str("G", "S1", null, null).str("V", "A", "", "B").build();
        assertEquals(Set.of(), fired("has_mixed_emptiness_within_group(V, [G], [], false)", t),
                "positional keep_missings=false drops the missing-keyed rows");
        assertEquals(Set.of(1L, 2L),
                fired("has_mixed_emptiness_within_group(V, [G], [], true)", t));
        assertLoadError("has_mixed_emptiness_within_group(V, [], [], true)", "keep_missings");
        assertLoadError("has_mixed_emptiness_within_group(V, [G], [], G)", "boolean literal");
    }


    @Test
    void mixed_arityIsALoadError() throws Exception
    {
        assertLoadError("has_mixed_emptiness_within_group(V, [G], [], true, V)", "at most 4");
        assertLoadError("has_mixed_emptiness_within_group(group=[G])", "name");
    }


    @Test
    void mixed_domainPrefixOperandsResolve() throws Exception
    {
        IDataTable ae = RealTables.of("AE").str("AEGRP", "S", "S", "S").str("AETYPE", "A", "", "")
                .str("AEQ", "y", "y", null).build();
        Rule rule = load("has_mixed_emptiness_within_group(--TYPE, group=[--GRP],"
                + " qualifying_any_populated=[--Q])");
        assertNull(rule.getLoadError(), rule.getLoadError());
        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae, _ -> null, "AE");
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        assertEquals(Set.of(0L, 1L, 2L), rows(result),
                "rows 0-1 qualify (AEQ populated) and are mixed; the block is reported whole");
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
        return rows(result);
    }


    private static Set<Long> rows(RuleExecutionResult result)
    {
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
        // Sensitivity is NOT authored: the rule derives Record from the binding's column
        // parameters (review round 1 M3; until then a Check reading only the binding derived none
        // and the runner collapsed the BitSet to ONE finding, which this file worked around by
        // authoring the field).
        String pkg = "{\"rules\":{\"x\":{\"Core\":{\"Id\":\"W1-GROUPED\"},"
                + "\"Bindings\":[{\"name\":\"$p\",\"expression\":\"" + binding + "\"}],"
                + "\"Check\":{\"expression\":\"$p == true\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"$p\"]}}}}";
        Rule rule = RulePackageLoader.loadFromString(pkg).getRules().get("x");
        assertNotNull(rule, "the rule loads");
        return rule;
    }

}
