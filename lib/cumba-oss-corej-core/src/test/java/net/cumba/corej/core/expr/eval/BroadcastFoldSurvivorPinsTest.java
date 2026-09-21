package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.GroupedResult;
import net.cumba.corej.core.exec.VariableMetadataResult;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BroadcastFold.Verdict;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.junit.jupiter.api.Test;

/**
 * Value-level pins for the {@link BroadcastFold} decision surface that {@code BroadcastFoldTest}
 * exercises only incidentally.
 *
 * <p>
 * <b>Why this matters.</b> {@code BroadcastFold} decides <em>how a verdict broadcasts</em>: whether
 * a leaf collapses to one dataset-level answer or is handed to the row path. A defect here does not
 * change what a rule <em>means</em>, it changes <b>which rows the rule fires on</b> and how many
 * findings it reports — invisible to a rule reviewer, and wrong on real submissions. Every test
 * below therefore asserts the exact {@link Verdict} / boolean / column name, and every branch is
 * pinned with at least one case on each side.
 * </p>
 */
class BroadcastFoldSurvivorPinsTest
{

    private static final Expr.Lit LIT_A = new Expr.Lit(Expr.LitKind.STRING, "a");

    private static final Expr.Lit TRUE_LIT = new Expr.Lit(Expr.LitKind.BOOL, true);

    private static final Expr.Lit FALSE_LIT = new Expr.Lit(Expr.LitKind.BOOL, false);

    /** AE with AETERM / AESEV present and AEXX absent — the standard fixture below. */
    private static EvaluationContext ctx(Map<String, Object> variables)
    {
        return EvaluationContext.builder()
                .table(new SyntheticDataTable("AE", List.of("AETERM", "AESEV"), new String[]
                {
                        "x"
                }, 2)).variables(variables).domainPrefix("AE").build();
    }


    private static EvaluationContext ctx()
    {
        return ctx(Map.of());
    }


    private static Expr.Call call(String name, Expr... args)
    {
        return new Expr.Call(name, List.of(args), Map.of());
    }


    private static Expr.Ref col(String name)
    {
        return new Expr.Ref(name, OperandKind.COLUMN);
    }

    // ------------------------------------------------------------------
    // isFoldableColumnReference — the character-class gate that decides whether a
    // leaf is even eligible for the absent-column fold. Too permissive and engine
    // meta-names ("variable_name") get folded as if they were data columns; too
    // strict and a real absent column never folds.
    // ------------------------------------------------------------------


    @Test
    void foldableColumnReference_acceptsExactlyUpperDigitUnderscoreAfterAnUpperFirstChar()
    {
        // Accepting side — each case sits ON a character-class boundary, so a widened or
        // narrowed comparison ('A'..'Z', '0'..'9') flips it.
        assertTrue(BroadcastFold.isFoldableColumnReference("AETERM"), "plain upper-case column");
        assertTrue(BroadcastFold.isFoldableColumnReference("A"), "first char exactly 'A'");
        assertTrue(BroadcastFold.isFoldableColumnReference("Z"), "first char exactly 'Z'");
        assertTrue(BroadcastFold.isFoldableColumnReference("AA"), "tail char exactly 'A'");
        assertTrue(BroadcastFold.isFoldableColumnReference("AZ"), "tail char exactly 'Z'");
        assertTrue(BroadcastFold.isFoldableColumnReference("A0"), "tail digit exactly '0'");
        assertTrue(BroadcastFold.isFoldableColumnReference("A9"), "tail digit exactly '9'");
        assertTrue(BroadcastFold.isFoldableColumnReference("A_B"), "underscore is allowed in tail");
        assertTrue(BroadcastFold.isFoldableColumnReference("RFSTDTC"), "a real SDTM column");
    }


    @Test
    void foldableColumnReference_rejectsEverythingOutsideThatClass()
    {
        // Rejecting side — the negative case for every accept above.
        assertFalse(BroadcastFold.isFoldableColumnReference(null), "null is not a column");
        assertFalse(BroadcastFold.isFoldableColumnReference(""), "empty is not a column");
        assertFalse(BroadcastFold.isFoldableColumnReference("@X"), "'@' is one below 'A'");
        assertFalse(BroadcastFold.isFoldableColumnReference("[X"), "'[' is one above 'Z'");
        assertFalse(BroadcastFold.isFoldableColumnReference("0AE"), "a digit may not lead");
        assertFalse(BroadcastFold.isFoldableColumnReference("variable_name"),
                "engine meta-names start lower-case and must never fold as data columns");
        assertFalse(BroadcastFold.isFoldableColumnReference("AEterm"), "lower-case tail char");
        assertFalse(BroadcastFold.isFoldableColumnReference("AE@X"), "tail '@' is one below 'A'");
        assertFalse(BroadcastFold.isFoldableColumnReference("AE[X"), "tail '[' is one above 'Z'");
        assertFalse(BroadcastFold.isFoldableColumnReference("AE/X"), "tail '/' is one below '0'");
        assertFalse(BroadcastFold.isFoldableColumnReference("AE:X"), "tail ':' is one above '9'");
        assertFalse(BroadcastFold.isFoldableColumnReference("AE-X"), "'-' is not underscore");
        assertFalse(BroadcastFold.isFoldableColumnReference("$OP"), "a $-operation ref");
        assertFalse(BroadcastFold.isFoldableColumnReference("EX.AETERM"), "a dotted cross-ds name");
        assertFalse(BroadcastFold.isFoldableColumnReference("AE*"), "a wildcard capture");
        assertFalse(BroadcastFold.isFoldableColumnReference("A$B"), "a sigil mid-name");
        assertFalse(BroadcastFold.isFoldableColumnReference("A.B"), "a dot mid-name");
        assertFalse(BroadcastFold.isFoldableColumnReference("A*B"), "a star mid-name");
    }

    // ------------------------------------------------------------------
    // ⭐⭐ RETIRED SECTION, 2026-09-21 — anyJoinedDatasetHasColumn
    //
    // This section pinned the doctrine the owner's ruling ABOLISHED. Its own banner said it
    // verbatim: "a column that is absent from the primary table but surfaceable through a
    // Match_Datasets join must NOT fold to absent". Under `UVC unqualified` plus the uniformity
    // ruling a bare name the primary lacks folds to the absent-column constant no matter what any
    // join carries, and the method it pinned has been REMOVED (zero production callers).
    // ⛔ A survivor pin whose survivor is dead, asserting the negation of the live ruling, is worse
    // than no pin: the next reader takes it for the contract. Review round 1 found it; it was not
    // among the 28 tests the change dispositioned, because nothing made it fail.
    // ------------------------------------------------------------------

    // ------------------------------------------------------------------

    // ⚠ The joinedCtx helper went with it -- it built a join-bearing context for no other test.

    // ------------------------------------------------------------------
    // leafNameColumn / nameSideColumn — which operand is the leaf's NAME side.
    // Pinned through the one observable consequence: the empty/is_missing broadcast
    // TRUE over a wholly-absent column (one dataset-level finding).
    // ------------------------------------------------------------------


    @Test
    void emptyOverAnAbsentColumnBroadcastsTrue_viaTheLeafNameSide()
    {
        // AEXX is absent from AE and from every join ⇒ empty(AEXX) is TRUE for every row, and
        // this operator is one of the two that keep the broadcast (EC-43).
        assertEquals(Verdict.TRUE, BroadcastFold.fold(call("empty", col("AEXX")), ctx(), false),
                "absent column + empty() ⇒ one dataset-level TRUE");
        // D77: a wildcard spelling never reaches the fold any more — specialisation resolved it
        // at bind time, and one that survives is an error, not a name side to resolve.
        org.junit.jupiter.api.Assertions.assertThrows(
                net.cumba.corej.core.expr.ExpressionException.class,
                () -> BroadcastFold.fold(
                        call("empty", new Expr.Ref("--XX", OperandKind.WILDCARD_COLUMN)), ctx(),
                        false),
                "an unspecialised --prefixed name side is an error (D77b)");
        // is_missing is the second broadcasting operator.
        assertEquals(Verdict.TRUE,
                BroadcastFold.fold(call("is_missing", col("AEXX")), ctx(), false));
        // Negative 1 — the column is PRESENT, so nothing folds and the row path decides.
        assertEquals(Verdict.UNKNOWN,
                BroadcastFold.fold(call("empty", col("AETERM")), ctx(), false),
                "a present column never takes the absent-column short-circuit");
        // Negative 2 — an exists call is never a name side (it is a presence fact, evaluated).
        assertEquals(Verdict.FALSE,
                BroadcastFold.fold(call("var_exists", col("AEXX")), ctx(), false),
                "var_exists(AEXX) is evaluated as a presence fact, not folded as a name side");
        // Negative 3 — a zero-argument call has no name side at all.
        assertEquals(Verdict.UNKNOWN, BroadcastFold.fold(call("empty"), ctx(), false),
                "no positional argument ⇒ no name side ⇒ no fold");
        // Negative 4 — a non-column name side (an operation ref) is not foldable.
        assertEquals(Verdict.UNKNOWN,
                BroadcastFold.fold(
                        call("is_missing", new Expr.Ref("$op", OperandKind.OPERATION_REF)),
                        ctx(Map.of("$op", new GroupedResult(List.of("USUBJID"), Map.of()))), false),
                "a $-ref name side is not a column-presence question");
    }

    // ------------------------------------------------------------------
    // Bool-literal comparison of a broadcast verdict — `pred == true` / `pred != false`
    // must fold exactly as the bare predicate does, in BOTH operand orders.
    // ------------------------------------------------------------------


    @Test
    void boolLiteralComparisonsFoldAsTheBarePredicate_inBothOrdersAndBothPolarities()
    {
        Expr present = call("var_exists", col("AETERM"));
        Expr absent = call("var_exists", col("AEXX"));
        // literal on the RIGHT
        assertEquals(Verdict.TRUE,
                BroadcastFold.fold(new Expr.Binary(Expr.BinOp.EQ, present, TRUE_LIT), ctx(), false),
                "var_exists(AETERM) == true is the verdict's identity");
        assertEquals(Verdict.FALSE,
                BroadcastFold.fold(new Expr.Binary(Expr.BinOp.EQ, absent, TRUE_LIT), ctx(), false),
                "var_exists(AEXX) == true is FALSE, not merely undecided");
        // literal on the LEFT — the mirrored disjunct
        assertEquals(Verdict.TRUE,
                BroadcastFold.fold(new Expr.Binary(Expr.BinOp.EQ, TRUE_LIT, present), ctx(), false),
                "true == var_exists(AETERM) folds identically");
        assertEquals(Verdict.FALSE,
                BroadcastFold.fold(new Expr.Binary(Expr.BinOp.EQ, TRUE_LIT, absent), ctx(), false));
        // NEQ is the other accepted comparison operator
        assertEquals(
                Verdict.TRUE, BroadcastFold
                        .fold(new Expr.Binary(Expr.BinOp.NEQ, present, FALSE_LIT), ctx(), false),
                "var_exists(AETERM) != false is the same verdict");
        assertEquals(Verdict.FALSE, BroadcastFold
                .fold(new Expr.Binary(Expr.BinOp.NEQ, present, TRUE_LIT), ctx(), false));
        // Negative — an ORDERING comparison against a bool literal is NOT the verdict identity,
        // so it must not take this arm.
        assertEquals(Verdict.UNKNOWN,
                BroadcastFold.fold(new Expr.Binary(Expr.BinOp.LT, present, TRUE_LIT), ctx(), false),
                "only == / != reduce a predicate against a bool literal");
        // Negative — a STRING literal is not a bool literal, so no verdict identity.
        assertEquals(Verdict.UNKNOWN,
                BroadcastFold.fold(new Expr.Binary(Expr.BinOp.EQ, present, LIT_A), ctx(), false),
                "\"a\" is not a bool literal");
        // Negative — the non-literal side must itself be dataset-constant.
        assertEquals(Verdict.UNKNOWN,
                BroadcastFold.fold(
                        new Expr.Binary(Expr.BinOp.EQ, call("empty", col("AETERM")), TRUE_LIT),
                        ctx(), false),
                "empty(<data column>) is not dataset-constant, so == true stays undecided");
    }

    // ------------------------------------------------------------------
    // Bare leaves: a $-boolean verdict and a bare bool literal.
    // ------------------------------------------------------------------


    @Test
    void bareOperationRefAndBareBoolLiteralAreDatasetConstantLeaves()
    {
        // Asserted on the classifier rather than on fold(): a BARE leaf of either shape is
        // classified dataset-constant here and then declined by the native compiler, so fold()
        // cannot distinguish the two answers — the classification is the observable contract that
        // the per-variable arm and RulePackageLoader's broadcast flag both read.
        EvaluationContext c = ctx(Map.of("$b", true, "$f", false));
        assertTrue(BroadcastFold
                .isDatasetConstantLeaf(new Expr.Ref("$b", OperandKind.OPERATION_REF), c, false),
                "a bare $-boolean verdict is a dataset constant");
        assertTrue(BroadcastFold
                .isDatasetConstantLeaf(new Expr.Ref("$f", OperandKind.OPERATION_REF), c, false),
                "... regardless of the value it carries");
        assertTrue(BroadcastFold.isDatasetConstantLeaf(TRUE_LIT, c, false), "bare `true` literal");
        assertTrue(BroadcastFold.isDatasetConstantLeaf(FALSE_LIT, c, false), "bare `false`");
        // Negative — a bare data-column reference is per-row, never a dataset constant.
        assertFalse(BroadcastFold.isDatasetConstantLeaf(col("AETERM"), c, false),
                "a bare column reference reads rows");
        // Negative — a bare WILDCARD column reference likewise.
        assertFalse(
                BroadcastFold.isDatasetConstantLeaf(
                        new Expr.Ref("--TERM", OperandKind.WILDCARD_COLUMN), c, false),
                "a bare wildcard column reference reads rows");
        // Negative — a bare STRING literal is not a boolean verdict.
        assertFalse(BroadcastFold.isDatasetConstantLeaf(LIT_A, c, false),
                "a bare string literal is not a broadcast verdict");
    }

    // ------------------------------------------------------------------
    // providersAvailable — a LIBRARY-level accessor with no Library provider must stay
    // UNKNOWN so the rule reports SKIPPED, never a verdict computed over a null read.
    // ------------------------------------------------------------------


    @Test
    void libraryLevelAccessorWithoutProviderStaysUnknown_butADataOnlyLeafStillDecides()
    {
        Expr libraryCmp = new Expr.Binary(Expr.BinOp.EQ,
                call("ds_label", new Expr.Lit(Expr.LitKind.STRING, "DATA")),
                call("ds_label", new Expr.Lit(Expr.LitKind.STRING, "LIBRARY")));
        assertEquals(Verdict.UNKNOWN, BroadcastFold.fold(libraryCmp, ctx(), false),
                "no Library provider ⇒ the leaf must not be decided");
        // Negative: the identical shape over DATA only carries no provider requirement and
        // decides — so the veto is scoped to the provider levels actually read.
        Expr dataCmp = new Expr.Binary(Expr.BinOp.EQ,
                call("ds_name", new Expr.Lit(Expr.LitKind.STRING, "DATA")),
                new Expr.Lit(Expr.LitKind.STRING, "AE"));
        assertEquals(Verdict.TRUE, BroadcastFold.fold(dataCmp, ctx(), false),
                "a DATA-level leaf needs no provider and still decides");
    }

    // ------------------------------------------------------------------
    // isLibraryGateCall — the §9.C skip gates, which fold even with no provider.
    // Arity is load-bearing: `available()` with no operand is not a gate.
    // ------------------------------------------------------------------


    @Test
    void libraryGateCallsAreRecognisedByNameAndArity()
    {
        assertTrue(BroadcastFold.isLibraryGateCall(call("library_available")));
        assertTrue(BroadcastFold.isLibraryGateCall(call("available", LIT_A)));
        assertTrue(BroadcastFold.isLibraryGateCall(call("dictionary_available", LIT_A)));
        // Negative — wrong arity on each of the three.
        assertFalse(BroadcastFold.isLibraryGateCall(call("library_available", LIT_A)),
                "library_available takes no operand");
        assertFalse(BroadcastFold.isLibraryGateCall(call("available")),
                "available() with no operand is not the gate");
        assertFalse(BroadcastFold.isLibraryGateCall(call("available", LIT_A, LIT_A)),
                "available/2 is not the gate");
        assertFalse(BroadcastFold.isLibraryGateCall(call("dictionary_available")),
                "dictionary_available() with no type is not the gate");
        // Negative — an unrelated name.
        assertFalse(BroadcastFold.isLibraryGateCall(call("empty", LIT_A)));
    }

    // ------------------------------------------------------------------
    // isDatasetFactCall / isDatasetFactBoolCall — the operand shape gates.
    // ------------------------------------------------------------------


    @Test
    void datasetFactCallRequiresLiteralArgumentsAndNoKwargs()
    {
        Expr.Lit dataLevel = new Expr.Lit(Expr.LitKind.STRING, "DATA");
        assertTrue(BroadcastFold.isDatasetFactCall(call("ds_name", dataLevel)),
                "a DATASET-scope accessor over a literal level is a dataset fact");
        assertTrue(BroadcastFold.isDatasetFactCall(call("record_count")));
        // Negative — a non-literal argument makes the accessor row-dependent in shape.
        assertFalse(BroadcastFold.isDatasetFactCall(call("ds_name", col("AETERM"))),
                "a column argument is not a literal level");
        // Negative — record_count with an argument is not the zero-arity dataset fact.
        assertFalse(BroadcastFold.isDatasetFactCall(call("record_count", dataLevel)));
        // Negative — kwargs disqualify outright.
        assertFalse(
                BroadcastFold.isDatasetFactCall(
                        new Expr.Call("ds_name", List.of(dataLevel), Map.of("k", LIT_A))),
                "kwargs are never part of a dataset-fact accessor");
        // A pure wrapper over facts stays a fact; over a data column it does not.
        assertTrue(BroadcastFold.isDatasetFactCall(call("upper", dataLevel)));
        assertFalse(BroadcastFold.isDatasetFactCall(call("upper", col("AETERM"))));
        assertFalse(BroadcastFold.isDatasetFactCall(call("upper")), "no argument to wrap");
    }


    @Test
    void datasetFactBoolCallRequiresAtLeastOneArgumentAndNoKwargs()
    {
        assertTrue(BroadcastFold.isDatasetFactBoolCall(call("empty", LIT_A)),
                "empty(<literal>) is a BOOLEAN predicate over a dataset fact");
        // Negative — zero arity: the early guard must reject, not fall through.
        assertFalse(BroadcastFold.isDatasetFactBoolCall(call("empty")),
                "a zero-argument call has no fact operands to be constant over");
        // Negative — kwargs present.
        assertFalse(
                BroadcastFold.isDatasetFactBoolCall(
                        new Expr.Call("empty", List.of(LIT_A), Map.of("k", LIT_A))),
                "kwargs disqualify the bool-call shape");
        // Negative — a per-row operand.
        assertFalse(BroadcastFold.isDatasetFactBoolCall(call("empty", col("AETERM"))));
        // Negative — not a registered BOOLEAN function.
        assertFalse(BroadcastFold.isDatasetFactBoolCall(call("upper", LIT_A)),
                "upper() is a VALUE function, not a boolean predicate");
    }

    // ------------------------------------------------------------------
    // isRowIndependentOperation (through isDatasetFactOperand) — an inlined operation
    // is a dataset fact only when it resolves to ONE value for the whole dataset.
    // Getting this wrong broadcasts a per-row answer to every row.
    // ------------------------------------------------------------------


    @Test
    void inlineOperationIsADatasetFactOnlyWhenItResolvesRowIndependently()
    {
        assertTrue(BroadcastFold.isDatasetFactOperand(call("distinct", col("AEDECOD"))),
                "distinct(VAR) yields one whole-dataset set");
        // Negative — an explicit group keyword makes it per-row.
        assertFalse(
                BroadcastFold.isDatasetFactOperand(new Expr.Call("distinct",
                        List.of(col("AEDECOD")), Map.of("group", col("USUBJID")))),
                "a `group` keyword resolves per row");
        // Negative — value_is_reference=true yields a per-row GroupedResult with no group kwarg.
        assertFalse(
                BroadcastFold.isDatasetFactOperand(new Expr.Call("distinct",
                        List.of(col("AEDECOD")), Map.of("value_is_reference", TRUE_LIT))),
                "distinct(VAR, value_is_reference=true) is per row");
        // ... and the same kwarg set to FALSE leaves it row-independent — the literal's VALUE is
        // what matters, not merely the kwarg's presence.
        assertTrue(
                BroadcastFold.isDatasetFactOperand(new Expr.Call("distinct",
                        List.of(col("AEDECOD")), Map.of("value_is_reference", FALSE_LIT))),
                "value_is_reference=false leaves distinct row-independent");
        // ... and a non-BOOL literal is not the `true` marker either.
        assertTrue(
                BroadcastFold
                        .isDatasetFactOperand(new Expr.Call("distinct", List.of(col("AEDECOD")),
                                Map.of("value_is_reference",
                                        new Expr.Lit(Expr.LitKind.STRING, "true")))),
                "a STRING \"true\" is not the boolean marker");
        // Negative — the always-grouped operations.
        assertFalse(BroadcastFold.isDatasetFactOperand(call("dy", col("AESTDTC"))),
                "dy is always per row");
        assertFalse(
                BroadcastFold.isDatasetFactOperand(
                        call("has_mixed_emptiness_within_group", col("AETERM"))),
                "has_mixed_emptiness_within_group is always per row");
        // Negative — a plain data column is never a dataset fact.
        assertFalse(BroadcastFold.isDatasetFactOperand(col("AETERM")));
        // Positive controls for the other operand shapes.
        assertTrue(BroadcastFold.isDatasetFactOperand(LIT_A));
        assertTrue(
                BroadcastFold.isDatasetFactOperand(new Expr.Ref("$op", OperandKind.OPERATION_REF)));
    }

    // ------------------------------------------------------------------
    // operationRefsSafe — the runtime $-operand safety walk. It must reach EVERY
    // position of the tree: a grouped $-ref hidden in an OR branch or in a call's
    // KEYWORD argument is just as unsafe as one in the obvious place.
    // ------------------------------------------------------------------


    @Test
    void operationRefSafetyWalkReachesCombinatorsAndKeywordArguments()
    {
        EvaluationContext c = ctx(
                Map.of("$g", new GroupedResult(List.of("USUBJID"), Map.of()), "$s", "scalar"));
        Expr grouped = new Expr.Ref("$g", OperandKind.OPERATION_REF);
        Expr scalar = new Expr.Ref("$s", OperandKind.OPERATION_REF);
        Expr safeBinary = new Expr.Binary(Expr.BinOp.EQ, scalar, LIT_A);
        Expr unsafeBinary = new Expr.Binary(Expr.BinOp.EQ, grouped, LIT_A);

        assertTrue(BroadcastFold.operationRefsSafe(new Expr.And(List.of(safeBinary, safeBinary)), c,
                false), "an all-scalar AND is safe");
        assertFalse(BroadcastFold.operationRefsSafe(new Expr.And(List.of(safeBinary, unsafeBinary)),
                c, false), "one grouped ref anywhere in an AND makes it unsafe");
        assertTrue(BroadcastFold.operationRefsSafe(new Expr.Or(List.of(safeBinary, safeBinary)), c,
                false), "an all-scalar OR is safe");
        assertFalse(BroadcastFold.operationRefsSafe(new Expr.Or(List.of(safeBinary, unsafeBinary)),
                c, false), "one grouped ref anywhere in an OR makes it unsafe");
        assertFalse(BroadcastFold.operationRefsSafe(new Expr.Not(unsafeBinary), c, false),
                "negation does not launder a grouped ref");
        // Keyword-argument position — the arm a walk that only visits positional args misses.
        assertTrue(
                BroadcastFold.operationRefsSafe(
                        new Expr.Call("f", List.of(scalar), Map.of("k", scalar)), c, false),
                "scalar refs in both positions are safe");
        assertFalse(
                BroadcastFold.operationRefsSafe(
                        new Expr.Call("f", List.of(scalar), Map.of("k", grouped)), c, false),
                "a grouped ref in a KEYWORD argument is unsafe");
        // VariableMetadataResult is safe only on the per-variable arm.
        EvaluationContext vmrCtx = ctx(
                Map.of("$v", new VariableMetadataResult(Map.of("AETERM", "Term"))));
        Expr vmr = new Expr.Ref("$v", OperandKind.OPERATION_REF);
        assertFalse(BroadcastFold.operationRefsSafe(vmr, vmrCtx, false));
        assertTrue(BroadcastFold.operationRefsSafe(vmr, vmrCtx, true));
    }

    // ------------------------------------------------------------------
    // hasVariableMetadataRef (through hasOperationRefOfType) — the question "does this
    // tree touch a per-variable metadata result anywhere?".
    //
    // ⚠ Round 3: this header claimed RuleRunner reads it "to choose per-variable native
    // routing, so a missed arm routes a per-variable rule down the whole-dataset path". It
    // does NOT. The single live consumer is RuleRunner's `projectVmr`:
    // hasVariableMetadataRef(checkExpr, ctx) && vmrRefsOnlyInGuardPosition(checkExpr, ctx)
    // which decides VMR PROJECTION — project the VMR entries per column, or keep the raw
    // (unprojected) Step-4 view — and selects no routing path at all. A missed arm therefore
    // makes projectVmr FALSE, i.e. the unprojected Step-4 contract, not a whole-dataset route.
    //
    // These pins deliberately stop at the walker arms and do not pin that consumer: the
    // projection decision lives in the `&& vmrRefsOnlyInGuardPosition` conjunct, so pinning it
    // here would be pinning the wrong half of the conjunction.
    //
    // ⚠ R2-6: this method lost its ONLY direct assertions as deletion collateral —
    // BroadcastFoldTest.operationRefTypeHelpers asserted the deleted hasGroupedOperationRef
    // AND this surviving one, and went whole. Restored here, widened to the walker arms the
    // deleted test did not reach.
    // ------------------------------------------------------------------


    @Test
    void variableMetadataRefDetection_discriminatesByResolvedType()
    {
        EvaluationContext c = ctx(
                Map.of("$g", new GroupedResult(List.of("USUBJID"), Map.of("S1", "a")), "$vmr",
                        new VariableMetadataResult(Map.of("AETERM", "Term")), "$s", "scalar"));
        assertTrue(BroadcastFold.hasVariableMetadataRef(opEq("$vmr"), c),
                "a $-ref resolving to a VariableMetadataResult is what per-variable routing keys on");
        assertFalse(BroadcastFold.hasVariableMetadataRef(opEq("$s"), c),
                "a scalar $-result is not per-variable");
        assertFalse(BroadcastFold.hasVariableMetadataRef(opEq("$g"), c),
                "a GroupedResult is per-ROW, not per-variable — the two helpers must not agree");
        assertFalse(BroadcastFold.hasVariableMetadataRef(opEq("$unbound"), c),
                "an unresolvable $-ref resolves to null, which is no instance of anything");
        assertFalse(
                BroadcastFold.hasVariableMetadataRef(
                        new Expr.Binary(Expr.BinOp.EQ, col("AETERM"), LIT_A), c),
                "a COLUMN ref is not an OPERATION_REF, whatever its name resolves to");
        assertFalse(BroadcastFold.hasVariableMetadataRef(LIT_A, c), "a literal holds no ref");
    }


    @Test
    void variableMetadataRefDetection_coversCombinatorsAndKeywordArguments()
    {
        EvaluationContext c = ctx(Map.of("$vmr",
                new VariableMetadataResult(Map.of("AETERM", "Term")), "$s", "scalar"));
        Expr vmr = opEq("$vmr");
        Expr plain = opEq("$s");

        assertTrue(BroadcastFold.hasVariableMetadataRef(new Expr.And(List.of(plain, vmr)), c),
                "an AND branch carrying the ref makes the tree per-variable");
        assertFalse(BroadcastFold.hasVariableMetadataRef(new Expr.And(List.of(plain, plain)), c));
        assertTrue(BroadcastFold.hasVariableMetadataRef(new Expr.Or(List.of(plain, vmr)), c),
                "an OR branch carrying the ref makes the tree per-variable");
        assertFalse(BroadcastFold.hasVariableMetadataRef(new Expr.Or(List.of(plain, plain)), c));
        assertTrue(BroadcastFold.hasVariableMetadataRef(new Expr.Not(vmr), c),
                "negation does not hide the ref");
        assertTrue(
                BroadcastFold.hasVariableMetadataRef(new Expr.Binary(Expr.BinOp.EQ, LIT_A,
                        new Expr.Ref("$vmr", OperandKind.OPERATION_REF)), c),
                "the RIGHT operand of a comparison is walked too");
        assertTrue(
                BroadcastFold.hasVariableMetadataRef(
                        call("f", new Expr.Ref("$vmr", OperandKind.OPERATION_REF)), c),
                "a positional call argument is walked");
        assertTrue(
                BroadcastFold
                        .hasVariableMetadataRef(
                                new Expr.Call("f",
                                        List.of(new Expr.Ref("$s", OperandKind.OPERATION_REF)),
                                        Map.of("k",
                                                new Expr.Ref("$vmr", OperandKind.OPERATION_REF))),
                                c),
                "a ref in a KEYWORD argument is walked — the arm operationRefsSafe needed too");
        assertFalse(BroadcastFold.hasVariableMetadataRef(call("f", LIT_A), c));
    }


    /** {@code $name == "a"} — the shape every per-variable routing question arrives in. */
    private static Expr opEq(String name)
    {
        return new Expr.Binary(Expr.BinOp.EQ, new Expr.Ref(name, OperandKind.OPERATION_REF), LIT_A);
    }

    // ------------------------------------------------------------------
    // readsRowData — decides per-variable routing granularity: no row reads means one
    // broadcast verdict per variable, row reads mean per-(variable, row) evaluation.
    // ------------------------------------------------------------------


    @Test
    void rowDataDetectionCoversCombinators_existsSpellings_andKeywordArguments()
    {
        EvaluationContext c = ctx(
                Map.of("$g", new GroupedResult(List.of("USUBJID"), Map.of()), "$s", "scalar"));
        Expr rowRead = col("AETERM");
        Expr constant = LIT_A;

        assertTrue(BroadcastFold.readsRowData(new Expr.And(List.of(constant, rowRead)), c),
                "an AND branch reading rows makes the tree row-reading");
        assertFalse(BroadcastFold.readsRowData(new Expr.And(List.of(constant, constant)), c));
        assertTrue(BroadcastFold.readsRowData(new Expr.Or(List.of(constant, rowRead)), c),
                "an OR branch reading rows makes the tree row-reading");
        assertFalse(BroadcastFold.readsRowData(new Expr.Or(List.of(constant, constant)), c));
        // The exists family: a concrete name is a dataset fact, a ${...} template is a per-row
        // driver substitution — and that holds for the STRING-LITERAL spelling too, which an
        // argument-only walk would misclassify as constant.
        assertFalse(BroadcastFold.readsRowData(call("var_exists", col("AETERM")), c));
        assertFalse(
                BroadcastFold.readsRowData(
                        call("var_exists", new Expr.Lit(Expr.LitKind.STRING, "AETERM")), c),
                "the string-literal spelling of a concrete name is still a dataset fact");
        assertTrue(BroadcastFold.readsRowData(call("var_exists", col("${VAR}")), c),
                "a ${...} template under exists is a per-row substitution");
        assertTrue(
                BroadcastFold.readsRowData(
                        call("var_exists", new Expr.Lit(Expr.LitKind.STRING, "${VAR}")), c),
                "... including when the template is spelled as a string literal");
        // A non-exists call is classified by its own operands, in BOTH argument positions.
        assertFalse(BroadcastFold.readsRowData(call("upper", constant), c));
        assertTrue(BroadcastFold.readsRowData(call("upper", rowRead), c));
        assertTrue(
                BroadcastFold.readsRowData(
                        new Expr.Call("f", List.of(constant), Map.of("k", rowRead)), c),
                "a row read in a KEYWORD argument still reads rows");
        assertFalse(BroadcastFold
                .readsRowData(new Expr.Call("f", List.of(constant), Map.of("k", constant)), c));
    }

    // ------------------------------------------------------------------
    // vmrRefsOnlyInGuardPosition — position-dependent legacy behaviour. A VMR ref on
    // the RIGHT of a comparison reaches a resolver with no VMR branch, so projecting it
    // per column there would silently disagree with the legacy engine.
    // ------------------------------------------------------------------


    @Test
    void vmrValuePositionDetectionReachesOrBranchesAndCallArguments()
    {
        EvaluationContext c = ctx(
                Map.of("$v", new VariableMetadataResult(Map.of("AETERM", "Term"))));
        Expr vmr = new Expr.Ref("$v", OperandKind.OPERATION_REF);
        Expr guard = new Expr.Binary(Expr.BinOp.NEQ, vmr,
                new Expr.Ref("variable_label", OperandKind.BUILTIN));
        Expr valuePos = new Expr.Binary(Expr.BinOp.EQ, col("AETERM"), vmr);

        assertTrue(BroadcastFold.vmrRefsOnlyInGuardPosition(new Expr.Or(List.of(guard, guard)), c),
                "an OR of guard-position leaves projects");
        assertFalse(
                BroadcastFold.vmrRefsOnlyInGuardPosition(new Expr.Or(List.of(guard, valuePos)), c),
                "one value-position VMR inside an OR blocks projection");
        // Wrapped on the value side: the walk must descend into the call's arguments.
        assertFalse(
                BroadcastFold.vmrRefsOnlyInGuardPosition(
                        new Expr.Binary(Expr.BinOp.EQ, col("AETERM"), call("upper", vmr)), c),
                "a VMR wrapped in upper() on the value side is still value position");
        assertTrue(
                BroadcastFold.vmrRefsOnlyInGuardPosition(
                        new Expr.Binary(Expr.BinOp.EQ, call("upper", vmr), col("AETERM")), c),
                "the same wrapper on the NAME side stays guard position");
        // ... and into the call's keyword arguments.
        assertFalse(
                BroadcastFold.vmrRefsOnlyInGuardPosition(new Expr.Binary(Expr.BinOp.EQ,
                        col("AETERM"), new Expr.Call("f", List.of(LIT_A), Map.of("k", vmr))), c),
                "a VMR in a KEYWORD argument on the value side blocks projection");
        assertTrue(
                BroadcastFold.vmrRefsOnlyInGuardPosition(new Expr.Binary(Expr.BinOp.EQ,
                        col("AETERM"), new Expr.Call("f", List.of(LIT_A), Map.of("k", LIT_A))), c),
                "a call with no VMR anywhere projects");
    }

}
