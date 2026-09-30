package net.cumba.corej.core.expr.eval;

import static net.cumba.corej.core.expr.eval.VectorLayerTest.col;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
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
 * {@code groupedMembership} (retired with {@code GroupedResult} in runbook W8 — its per-row
 * successor is {@code boundMembership}'s per-row arm, pinned below) and {@code toSet} (the
 * {@code $}-reference set). {@code MissingValue.MIS.toString()} is {@code "."}, so a missing member
 * became the text {@code "."}: a <b>present</b> {@code "."} cell matched it, and a <b>missing</b>
 * probe — which {@code D81} + {@code D34 #5-2} make a member of a set holding that same missing —
 * did not. That is the {@code JKM R5} collision class, one level up from join keys.
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

    // ---- toSet: the $-reference set -------------------------------------------------------------


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
        assertEquals(Set.of("ABC"), ExprCompiler.toSet(List.of("abc"), true).present(),
                "the case-insensitive surface still upper-cases present members");
        assertEquals(Set.of("7"), ExprCompiler.toSet(7L, false).present(),
                "a present scalar is a singleton of its text");
        assertTrue(ExprCompiler.toSet(null, false).present().isEmpty(), "null is the empty set");
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

    // ---- boundMembership: the per-row compiled-binding set -------------------------------------


    /**
     * The per-row set a per-row compiled binding answers (one list per row). Until runbook W8 this
     * was pinned on {@code groupedMembership} over a {@code GroupedResult} in the variables map;
     * that shape is gone, and {@code boundMembership}'s per-row arm is the builder left.
     */
    @Test
    void perRowBoundMembershipMatchesAMissingByIdentity()
    {
        IDataTable t = probeTable();
        List<Object> members = List.of(MissingValue.MIS, "A");
        Vector perRow = new ComputedVector(3, DataValueType.STRING, _ -> members);
        assertEquals(bits(1, 2),
                ExprCompiler.boundMembership(col(t, "X"), perRow, 3, false, false, false),
                "the per-row set keeps MIS by identity: row 2 matches, the present '.' of row 0"
                        + " does not");
        assertEquals(bits(0),
                ExprCompiler.boundMembership(col(t, "X"), perRow, 3, true, false, false),
                "not in is the exact complement");
    }

    // ---- listAccessorSet: the list-valued metadata accessor set
    // -----------------------------------


    @Test
    void listAccessorSetClassifiesAMissingElement()
    {
        ExprCompiler.ValuePlan plan = _ -> ConstVector.of(List.of("A", MissingValue.MIS));
        Primitives.MemberSet set = ExprCompiler.listAccessorSet(plan, EvalRun.ofRowCount(1), false);
        assertEquals(Set.of(MissingValue.MIS), set.missing(), "the missing keeps its identity");
        assertEquals(Set.of("A"), set.present(), "no '.' among the present members");
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
     * Collection-LHS membership ({@code D81d}): a present left item is compared against the present
     * members only, so a missing member is never matched by a string — with the present-{@code "."}
     * control.
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


    /**
     * Review round 1, R4 — a MISSING left item of collection-LHS membership is compared by identity
     * ({@code D34 #5-2}): a member of a set holding the same missing, of no other, and never of a
     * present {@code "."} — it used to render to {@code "."} and match exactly that.
     */
    @Test
    void listMembershipComparesAMissingLeftItemByIdentity()
    {
        ConstVector lhs = ConstVector.of(List.of(MissingValue.MIS));
        assertEquals(bits(0),
                Primitives.listMembership(lhs,
                        Primitives.MemberSet.of(List.of(MissingValue.MIS), false), 1, false, false),
                "MIS in {MIS}");
        assertEquals(bits(), Primitives.listMembership(lhs,
                Primitives.MemberSet.of(List.of(MissingValue.MIS_A), false), 1, false, false),
                "MIS is not .A");
        assertEquals(bits(),
                Primitives.listMembership(lhs, Primitives.MemberSet.ofStrings(Set.of(".")), 1,
                        false, false),
                "a missing left item is not the text '.' — it matched until 2026-09-26");
        IDataTable t = probeTable();
        ConstVector cellLhs = ConstVector
                .of(List.of(t.getColumn(t.getMetaData().getColumnIndex("X")).getDataValue(2L)));
        assertEquals(bits(0),
                Primitives.listMembership(cellLhs,
                        Primitives.MemberSet.of(List.of(MissingValue.MIS), false), 1, false, false),
                "a missing CELL as a left item is classified the same way");
    }


    /** Review round 1, R5 — an {@code IDataValue} member carrying a missing keeps its identity. */
    @Test
    void memberSetClassifiesAMissingDataValueMember()
    {
        IDataTable t = probeTable();
        int x = t.getMetaData().getColumnIndex("X");
        Primitives.MemberSet set = Primitives.MemberSet
                .of(List.of(t.getColumn(x).getDataValue(2L), "A"), false);
        assertEquals(Set.of(MissingValue.MIS), set.missing(),
                "a missing IDataValue member is classified by its identity");
        assertEquals(Set.of("A"), set.present(), "and is not rendered among the present members");
        assertEquals(MissingValue.MIS_A, Primitives.MemberSet.missingIdentityOfMember(
                new net.cumba.datatable.values.DataValueDouble(MissingValue.MIS_A.asDouble())),
                "a NaN-carrying numeric cell yields its marker");
        assertEquals(null, Primitives.MemberSet.missingIdentityOfMember("."),
                "a present '.' string is not a missing");
    }


    /** The case-insensitive surface keeps a missing member's identity too. */
    @Test
    void caseInsensitiveMembershipMatchesAMissingByIdentity()
    {
        IDataTable t = probeTable();
        EvaluationContext c = EvaluationContext.builder().table(t)
                .variables(Map.of("$ref", List.of(MissingValue.MIS, "a"))).build();
        assertEquals(bits(1, 2), eval("upper(X) in $ref", c),
                "row 1 'A' matches the folded 'a'; row 2 MIS matches MIS; row 0 '.' matches"
                        + " nothing");
    }


    /**
     * The per-row set's present {@code ""} member (review round 1, L-4 — re-pinned from the retired
     * {@code groupedMembershipFoldsANullElementToTheEmptyString}, whose {@code null} element
     * register {@code NNL §1} made impossible; re-pinned again from {@code groupedMembership} onto
     * {@code boundMembership}'s per-row arm in runbook W8): a present {@code ""} member matches a
     * present blank row and NOT a missing row ({@code D12}). Mockito-free.
     */
    @Test
    void perRowBoundMembershipMatchesAPresentEmptyMemberOnlyAgainstABlankRow()
    {
        IDataTable t = MissingCellTables.of("DS").str("X", "", "A", MissingValue.MIS)
                .str("G", "g1", "g1", "g1").build();
        List<Object> members = List.of("", "B");
        Vector perRow = new ComputedVector(3, DataValueType.STRING, _ -> members);
        assertEquals(bits(0),
                ExprCompiler.boundMembership(col(t, "X"), perRow, 3, false, false, false),
                "the present \"\" member is matched by the present blank of row 0 and NOT by the"
                        + " missing of row 2 (D12)");
    }


    /**
     * LOW-2 / L3 — the list-accessor source of {@code not_contains_all} and its tokens compare as
     * key components, so a missing source member never satisfies a {@code "."} token. ⚠ Pinned on
     * the two calls the site composes: a real metadata accessor carries codelist strings and cannot
     * put a missing into the list.
     */
    @Test
    void notContainsAllAccessorSourceIsNotSatisfiedByADotToken()
    {
        ExprCompiler.ValuePlan plan = _ -> ConstVector.of(List.of(MissingValue.MIS, "A"));
        Set<Object> allowed = ExprCompiler.listAccessorSet(plan, EvalRun.ofRowCount(1), false)
                .asComponents();
        assertEquals(bits(0),
                Primitives.notContainsAllTokens(ConstVector.of(List.of("A", ".")), allowed, 1),
                "the '.' token is not allowed: the MIS member is not the text '.'");
        assertEquals(bits(),
                Primitives.notContainsAllTokens(ConstVector.of(List.of("A")), allowed, 1),
                "control: a token that IS allowed does not fire");
    }


    /**
     * Review round 1, R3 — the {@code $}-sources of {@code not_contains_all} render a missing
     * member as its component token, the identity {@code distinctColumnValues} already keeps: never
     * satisfied by a {@code "."} token or required value, satisfied only by the same missing.
     */
    @Test
    void notContainsAllDollarSourcesKeepAMissingMembersIdentity()
    {
        IDataTable t = MockTable.of().name("DS").col("TOK", "A;.", "A").build();
        EvaluationContext perRow = EvaluationContext.builder().table(t)
                .variables(Map.of("$allowed", List.of(MissingValue.MIS, "A"))).build();
        assertEquals(bits(0), eval("not_contains_all($allowed, split_by(TOK, \";\"))", perRow),
                "row 0's '.' token is not satisfied by the MIS member — it was until 2026-09-26");

        EvaluationContext broadcast = EvaluationContext.builder().table(t)
                .variables(Map.of("$src", List.of(MissingValue.MIS, "A"), "$reqMis",
                        List.of(MissingValue.MIS), "$reqDot", List.of(".")))
                .build();
        assertEquals(bits(), eval("not_contains_all($src, $reqMis)", broadcast),
                "a required MIS IS satisfied by a source MIS (D34 #5-2)");
        assertEquals(bits(0, 1), eval("not_contains_all($src, $reqDot)", broadcast),
                "a required '.' is NOT satisfied by a source MIS — every row fires");
    }


    /**
     * Review round 1, R2 — composite membership keys keep each component's identity on BOTH sides
     * ({@code D11}, {@code D34 #5-2}, {@code NVE §4.4}): a missing and a present blank are two
     * different components. Before, both sides folded a missing to {@code ""}, so row 1 and row 3
     * below matched a reference tuple they are not.
     */
    @Test
    void tupleMembershipKeepsAMissingComponentsIdentity()
    {
        IDataTable tv = MockTable.of().name("TV").col("VISIT", "W1", "W2")
                .colSasMissing("VISITNUM", null, "").build();
        IDataTable vs = MockTable.of().name("VS").col("VISIT", "W1", "W1", "W2", "W2")
                .colSasMissing("VISITNUM", null, "", "", null).build();
        EvaluationContext ctx = EvaluationContext.builder().table(vs).domainPrefix("VS")
                .datasetResolver(name -> "TV".equals(name) ? tv : null).build();
        assertEquals(bits(1, 3),
                eval("tuple(VISIT, VISITNUM) not in distinct([VISIT, VISITNUM], domain=\"TV\")",
                        ctx),
                "(W1,MIS) and (W2,\"\") are TV rows; (W1,\"\") and (W2,MIS) are not — both read"
                        + " as TV rows until 2026-09-26");
    }


    /**
     * The tuple normaliser keeps a missing element as a {@link Primitives.MissingMember} identity,
     * marker by marker, and that identity prints as the marker — never a control-character token
     * (review round 2, M1).
     */
    @Test
    void toTupleKeyKeepsAMissingElementAsItsIdentity()
    {
        List<Object> mis = ExprCompiler.toTupleKey(List.of("A", MissingValue.MIS));
        List<Object> misA = ExprCompiler.toTupleKey(List.of("A", MissingValue.MIS_A));
        List<Object> dot = ExprCompiler.toTupleKey(List.of("A", "."));
        List<Object> blank = ExprCompiler.toTupleKey(List.of("A", ""));
        assertTrue(mis != null && misA != null && dot != null && blank != null);
        assertEquals(4, Set.of(mis, misA, dot, blank).size(),
                "MIS, .A, a present '.' and a present \"\" are four different components");
        assertEquals(List.of("A", ""), blank, "a present empty string is its own component");
        assertEquals("[A, .]", mis.toString(), "the identity prints as the report's marker");
    }


    /**
     * Review round 2, L3 — a notContainsAllTokens TOKEN that is a missing is compared by identity
     * (the mirror of R4): allowed only by the same missing, never by a present {@code "."}.
     */
    @Test
    void notContainsAllTokensComparesAMissingTokenByIdentity()
    {
        ConstVector tokens = ConstVector.of(List.of("A", MissingValue.MIS));
        assertEquals(bits(),
                Primitives.notContainsAllTokens(tokens,
                        Set.of("A", new Primitives.MissingMember(MissingValue.MIS)), 1),
                "a MIS token is allowed by a MIS member");
        assertEquals(bits(0), Primitives.notContainsAllTokens(tokens, Set.of("A", "."), 1),
                "a MIS token is NOT allowed by a present '.' — it was, rendered, until round 2");
        assertEquals(bits(0),
                Primitives.notContainsAllTokens(tokens,
                        Set.of("A", new Primitives.MissingMember(MissingValue.MIS_A)), 1),
                "MIS is not .A");
    }
}
