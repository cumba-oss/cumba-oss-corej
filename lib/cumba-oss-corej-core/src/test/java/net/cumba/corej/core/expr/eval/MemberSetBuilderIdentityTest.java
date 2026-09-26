package net.cumba.corej.core.expr.eval;

import static net.cumba.corej.core.expr.eval.VectorLayerTest.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.GroupedResult;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * ⭐⭐ Every membership set-builder keeps a {@link MissingValue} member's <b>identity</b> — pinned
 * per builder, by putting a missing into each set directly.
 *
 * <p>
 * {@code PLAN-member-set-identity-hardening}, owner 2026-09-25: <i>"route all four."</i> Four
 * builders in {@link ExprCompiler} rendered each member with {@code item.toString()} —
 * {@code listAccessorSet}, the per-row VLM accessor set ({@code vlmListMembership}),
 * {@code groupedMembership} and {@code toSet} (the {@code $}-reference and inline-operation set).
 * {@code MissingValue.MIS.toString()} is {@code "."}, so a missing member became the text
 * {@code "."}: a <b>present</b> {@code "."} cell matched it, and a <b>missing</b> probe — which
 * {@code D81} + {@code D34 #5-2} make a member of a set holding that same missing — did not. That
 * is the {@code JKM R5} collision class, one level up from join keys.
 * </p>
 *
 * <p>
 * ⛔⛔ <b>Why these are minted fixtures and not a corpus differential.</b> No membership set in the
 * shipped corpus can contain a missing: list literals have no spelling for one, the metadata
 * accessors carry codelist strings, and {@code evalDistinct} / {@code evalDistinctGrouped} filter
 * missings and empties at construction. That was the argument for leaving the four builders alone —
 * <i>"none can receive a missing today"</i> — and it is a reachability argument, the shape that hid
 * {@code isMember}'s blanket early return for months. ⇒ a corpus run is BLIND to this change by
 * construction, and a green one is non-observation. The property is made true here instead: the
 * membership primitives accept only a {@link Primitives.MemberSet} (a type-level property), and
 * each builder is shown below to classify, not render.
 * </p>
 *
 * <p>
 * Each case asserts the two directions that a rendering would get wrong at once: the missing probe
 * IS a member, and the present {@code "."} probe is NOT.
 * </p>
 */
class MemberSetBuilderIdentityTest
{

    /** Row 0 a present {@code "."}, row 1 a present {@code "A"}, row 2 a genuine {@code MIS}. */
    private static IDataTable probeTable()
    {
        IDataTable t = MockTable.of().name("DS").colSasMissing("X", ".", "A", null)
                .col("G", "g1", "g1", "g1").build();
        // ⚠ By index lookup, not getColumn(0): the testkit orders plain columns before
        // SAS-missing ones, so column 0 is G.
        int x = t.getMetaData().getColumnIndex("X");
        assertTrue(TypedValue.missingIdentityOf(t.getColumn(x).getDataValue(2L)) != null,
                "fixture control: row 2 must be a genuine MissingValue, or every missing-probe"
                        + " assertion below is vacuous");
        assertTrue(TypedValue.missingIdentityOf(t.getColumn(x).getDataValue(0L)) == null,
                "fixture control: row 0 must be a PRESENT '.', not a missing");
        return t;
    }


    private static BitSet bits(int... set)
    {
        BitSet b = new BitSet();
        for (int i : set)
        {
            b.set(i);
        }
        return b;
    }


    private static BitSet eval(String expr, EvaluationContext ctx)
    {
        return NativeExprEvaluator.evaluate(CheckExpressionParser.parse(expr), ctx);
    }

    // ---- toSet: the $-reference and inline-operation set ---------------------------------------


    @Test
    void toSetClassifiesAMissingElementInsteadOfRenderingIt()
    {
        Primitives.MemberSet set = ExprCompiler.toSet(List.of("A", MissingValue.MIS), false);
        assertEquals(Set.of(MissingValue.MIS), set.missing(), "the missing keeps its identity");
        assertEquals(Set.of("A"), set.present(),
                "and is NOT rendered into the present members — a '.' here is the old rendering");
    }


    @Test
    void toSetClassifiesAMissingScalar()
    {
        Primitives.MemberSet set = ExprCompiler.toSet(MissingValue.MIS_A, false);
        assertEquals(Set.of(MissingValue.MIS_A), set.missing(),
                "a scalar result is a singleton, and a missing scalar keeps its identity too");
        assertTrue(set.present().isEmpty(), "no '.A' text may appear among the present members");
    }


    @Test
    void toSetKeepsItsOtherContractsUnchanged()
    {
        assertEquals(Set.of("", "B"), ExprCompiler.toSet(Arrays.asList(null, "B"), false).present(),
                "a null element still folds to \"\" (the raw channel stays nullable by contract)");
        assertEquals(Set.of("ABC"), ExprCompiler.toSet(List.of("abc"), true).present(),
                "the case-insensitive surface still upper-cases present members");
        assertEquals(Set.of("7"), ExprCompiler.toSet(7L, false).present(),
                "a present scalar is a singleton of its text");
        assertTrue(ExprCompiler.toSet(null, false).present().isEmpty(), "null is the empty set");
        assertTrue(
                ExprCompiler.toSet(new GroupedResult(List.of("G"), Map.of()), false).present()
                        .isEmpty(),
                "a GroupedResult is handled per row elsewhere, so here it is the empty set");
    }


    /** End to end through {@code buildSet}'s {@code $}-reference branch, which calls toSet. */
    @Test
    void dollarReferenceMembershipMatchesAMissingByIdentity()
    {
        IDataTable t = probeTable();
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("$ref", List.of(MissingValue.MIS, "A"))).build();
        assertEquals(bits(1, 2), eval("X in $ref", c),
                "row 2 (MIS) IS a member of a set holding MIS (D81 + D34 #5-2); row 0 (a present"
                        + " '.') is NOT — before the fix this read {0, 1}");
        assertEquals(bits(0), eval("X not in $ref", c), "not in is the exact complement");
    }


    @Test
    void dollarReferenceDifferentMarkerIsNotAMember()
    {
        IDataTable t = probeTable();
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("$ref", List.of(MissingValue.MIS_A))).build();
        assertEquals(bits(), eval("X in $ref", c),
                "MIS is not .A (JKM R5: identity is per marker), and a present '.' is neither");
    }

    // ---- groupedMembership: the per-row GroupedResult set ---------------------------------------


    @Test
    void groupedMembershipMatchesAMissingByIdentity()
    {
        IDataTable t = probeTable();
        GroupedResult grouped = new GroupedResult(List.of("G"),
                Map.<String, Object> of("g1", List.of(MissingValue.MIS, "A")));
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("$grp", grouped)).build();
        assertEquals(bits(1, 2), eval("X in $grp", c),
                "the per-row group set keeps MIS by identity: row 2 matches, the present '.' of"
                        + " row 0 does not — before the fix this read {0, 1}");
        assertEquals(bits(0), eval("X not in $grp", c), "not in is the exact complement");
    }


    @Test
    void groupedMembershipMatchesAMissingScalarGroupValue()
    {
        IDataTable t = probeTable();
        GroupedResult grouped = new GroupedResult(List.of("G"),
                Map.<String, Object> of("g1", MissingValue.MIS));
        BitSet fired = ExprCompiler.groupedMembership(col(t, "X"), grouped,
                EvalRun.fullRange(EvaluationContext.builder().table(t).build()), false, false);
        assertEquals(bits(2), fired,
                "a SCALAR missing group value is a singleton by identity — it used to render '.'"
                        + " and match row 0 instead of row 2");
    }

    // ---- listAccessorSet: the list-valued metadata accessor set
    // -----------------------------------


    @Test
    void listAccessorSetClassifiesAMissingElement()
    {
        ExprCompiler.ValuePlan plan = _ -> ConstVector
                .of(Arrays.asList("A", MissingValue.MIS, null));
        Primitives.MemberSet set = ExprCompiler.listAccessorSet(plan, EvalRun.ofRowCount(1), false);
        assertEquals(Set.of(MissingValue.MIS), set.missing(), "the missing keeps its identity");
        assertEquals(Set.of("A"), set.present(),
                "no '.' among the present members, and a null element is skipped (not \"\"), as"
                        + " the accessor builder always did");
    }


    @Test
    void listAccessorSetKeepsItsEmptyContracts()
    {
        ExprCompiler.ValuePlan list = _ -> ConstVector.of(List.of("A"));
        assertTrue(ExprCompiler.listAccessorSet(list, EvalRun.ofRowCount(0), false).present()
                .isEmpty(), "no row to read ⇒ the empty set");
        ExprCompiler.ValuePlan scalar = _ -> ConstVector.of("A");
        assertTrue(ExprCompiler.listAccessorSet(scalar, EvalRun.ofRowCount(1), false).present()
                .isEmpty(), "a non-list cell ⇒ the empty set");
        ExprCompiler.ValuePlan none = _ -> null;
        assertTrue(ExprCompiler.listAccessorSet(none, EvalRun.ofRowCount(1), false).present()
                .isEmpty(), "an absent vector ⇒ the empty set");
    }

    // ---- vlmListMembership: the per-row VLM accessor set
    // ------------------------------------------


    @Test
    void vlmListMembershipDoesNotMatchAPresentDotAgainstAMissingMember()
    {
        IDataTable t = probeTable();
        BitSet fired = ExprCompiler.vlmListMembership(col(t, "X"),
                ConstVector.of(List.of(MissingValue.MIS, "A")), 3, false, false);
        assertEquals(bits(1), fired,
                "only the present 'A' matches: the present '.' of row 0 must NOT match the MIS"
                        + " member (it did when the member was rendered), and the missing probe of"
                        + " row 2 makes no VLM decision at all");
    }


    /** Control: the probe CAN match {@code "."} — so the non-match above is identity, not a bug. */
    @Test
    void vlmListMembershipStillMatchesAPresentDotAgainstAPresentDotMember()
    {
        IDataTable t = probeTable();
        BitSet fired = ExprCompiler.vlmListMembership(col(t, "X"), ConstVector.of(List.of(".")), 3,
                false, false);
        assertEquals(bits(0), fired, "a present '.' member IS matched by a present '.' probe");
    }

    // ---- the consumers that take only the present members ---------------------------------------


    /**
     * Collection-LHS membership ({@code D81d}) compares the left list's items as strings, so a
     * missing member is matched by nothing there — with the present-{@code "."} control.
     */
    @Test
    void listMembershipIgnoresAMissingMemberButMatchesAPresentOne()
    {
        ConstVector lhs = ConstVector.of(List.of("."));
        assertEquals(bits(),
                Primitives.listMembership(lhs,
                        Primitives.MemberSet.of(List.of(MissingValue.MIS), false), 1, false, false),
                "a list item '.' is a string, never the missing MIS");
        assertEquals(bits(0), Primitives.listMembership(lhs,
                Primitives.MemberSet.ofStrings(Set.of(".")), 1, false, false),
                "control: the same item matches a present '.' member");
    }
}
