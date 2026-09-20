package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import net.cumba.corej.core.exec.DatasetLookup;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.exec.GroupedResult;
import net.cumba.corej.core.exec.VariableMetadataResult;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BroadcastFold.Verdict;
import net.cumba.datatable.testkit.SyntheticDataTable;
import org.junit.jupiter.api.Test;

/**
 * Guard-residual D1/D1b ({@code plans/done/PLAN-native-runtime-guard-residual.md}) — the tri-state
 * {@link BroadcastFold}: the Kleene truth table (mirroring the legacy {@code simplify} collapse
 * rules), the dataset-constant leaf classes, the missing-column fold mirror (Fix #40), and the
 * runtime {@code $}-operand helpers.
 */
class BroadcastFoldTest
{

    private static final Expr.Lit LIT_A = new Expr.Lit(Expr.LitKind.STRING, "a");

    private static final Expr.Lit LIT_B = new Expr.Lit(Expr.LitKind.STRING, "b");

    /** Decided-TRUE leaf: {@code "a" == "a"} — literal comparison, dataset-constant. */
    private static final Expr T = new Expr.Binary(Expr.BinOp.EQ, LIT_A, LIT_A);

    /** Decided-FALSE leaf: {@code "a" == "b"}. */
    private static final Expr F = new Expr.Binary(Expr.BinOp.EQ, LIT_A, LIT_B);

    /** Undecidable leaf: a {@code $}-comparison whose ref is a runtime GroupedResult. */
    private static final Expr U = new Expr.Binary(Expr.BinOp.EQ,
            new Expr.Ref("$g", OperandKind.OPERATION_REF), LIT_A);

    private static EvaluationContext ctx()
    {
        return ctx(Map.of("$g", new GroupedResult(List.of("USUBJID"), Map.of("S1", "a"))));
    }


    private static EvaluationContext ctx(Map<String, Object> variables)
    {
        return EvaluationContext.builder()
                .table(new SyntheticDataTable("AE", List.of("AETERM", "AESEV"), new String[]
                {
                        "x"
                }, 2)).variables(variables).domainPrefix("AE").build();
    }


    private static Verdict fold(Expr e)
    {
        return BroadcastFold.fold(e, ctx(), false);
    }

    // ------------------------------------------------------------------
    // Kleene truth table — exact mirror of the legacy simplify collapse.
    // ------------------------------------------------------------------


    @Test
    void kleeneTruthTable()
    {
        // and
        assertEquals(Verdict.TRUE, fold(new Expr.And(List.of(T, T))));
        assertEquals(Verdict.FALSE, fold(new Expr.And(List.of(T, F))));
        assertEquals(Verdict.FALSE, fold(new Expr.And(List.of(F, U))), "all[FALSE, U] decides");
        assertEquals(Verdict.FALSE, fold(new Expr.And(List.of(U, F))), "order-independent");
        assertEquals(Verdict.UNKNOWN, fold(new Expr.And(List.of(T, U))), "all[TRUE, U] undecided");
        // or
        assertEquals(Verdict.TRUE, fold(new Expr.Or(List.of(T, U))), "any[TRUE, U] decides");
        assertEquals(Verdict.TRUE, fold(new Expr.Or(List.of(U, T))), "order-independent");
        assertEquals(Verdict.UNKNOWN, fold(new Expr.Or(List.of(F, U))), "any[FALSE, U] undecided");
        assertEquals(Verdict.FALSE, fold(new Expr.Or(List.of(F, F))));
        // not
        assertEquals(Verdict.FALSE, fold(new Expr.Not(T)));
        assertEquals(Verdict.TRUE, fold(new Expr.Not(F)));
        assertEquals(Verdict.UNKNOWN, fold(new Expr.Not(U)));
        // nesting
        assertEquals(Verdict.TRUE,
                fold(new Expr.Or(List.of(new Expr.And(List.of(T, U)), new Expr.Not(F)))),
                "any[U-and, TRUE] still decides via the decided branch");
        // empty combinators mirror the legacy simplify defaults
        assertEquals(Verdict.TRUE, fold(new Expr.And(List.of())), "empty all[] is vacuously TRUE");
        assertEquals(Verdict.FALSE, fold(new Expr.Or(List.of())), "empty any[] is FALSE");
    }

    // ------------------------------------------------------------------
    // Leaf classes
    // ------------------------------------------------------------------


    @Test
    void existsLeavesEvaluateNatively()
    {
        Expr present = new Expr.Call("var_exists",
                List.of(new Expr.Ref("AETERM", OperandKind.COLUMN)), Map.of());
        Expr absent = new Expr.Call("var_exists", List.of(new Expr.Ref("AEXX", OperandKind.COLUMN)),
                Map.of());
        Expr absentNeg = new Expr.Call("var_not_exists",
                List.of(new Expr.Ref("AEXX", OperandKind.COLUMN)), Map.of());
        assertEquals(Verdict.TRUE, fold(present));
        assertEquals(Verdict.FALSE, fold(absent));
        assertEquals(Verdict.TRUE, fold(absentNeg));
    }


    @Test
    void domainPrefixReachingTheFoldIsAnError()
    {
        // D77: `--TERM` is resolved by the specialisation stage before anything folds; one that
        // reaches the fold is an error, never an in-fold substitution (the old phase-2c mirror).
        Expr prefixed = new Expr.Call("var_exists",
                List.of(new Expr.Ref("--TERM", OperandKind.WILDCARD_COLUMN)), Map.of());
        org.junit.jupiter.api.Assertions.assertThrows(
                net.cumba.corej.core.expr.ExpressionException.class, () -> fold(prefixed));
    }


    @Test
    void substitutionTemplateExistsStaysUnknown()
    {
        // ${...} operand templates are per-row driver substitutions — the legacy classifier marks
        // them ROW even under exists, so the fold must not decide them.
        Expr templated = new Expr.Call("var_exists",
                List.of(new Expr.Ref("${VAR}", OperandKind.COLUMN)), Map.of());
        assertEquals(Verdict.UNKNOWN, fold(templated));
    }


    @Test
    void scalarOperationComparisonsEvaluate_groupedAndVmrStayUnknown()
    {
        EvaluationContext scalarCtx = ctx(Map.of("$x", "a"));
        Expr cmp = new Expr.Binary(Expr.BinOp.EQ, new Expr.Ref("$x", OperandKind.OPERATION_REF),
                LIT_A);
        assertEquals(Verdict.TRUE, BroadcastFold.fold(cmp, scalarCtx, false));

        assertEquals(Verdict.UNKNOWN, fold(U), "GroupedResult $-ref is never dataset-constant");

        EvaluationContext vmrCtx = ctx(
                Map.of("$vmr", new VariableMetadataResult(Map.of("AETERM", "Term"))));
        Expr vmrCmp = new Expr.Binary(Expr.BinOp.EQ,
                new Expr.Ref("$vmr", OperandKind.OPERATION_REF), LIT_A);
        assertEquals(Verdict.UNKNOWN, BroadcastFold.fold(vmrCmp, vmrCtx, false));
        assertTrue(BroadcastFold.isDatasetConstantLeaf(vmrCmp, vmrCtx, true),
                "the per-variable loop arm (allowVariableMetadata) accepts a VMR ref");
    }


    @Test
    void scalarContextVarValueSideEvaluates_columnValueSideStaysUnknown()
    {
        // CDISC-CG0413 family: the VALUE side is the Fix #10 DOMAIN context variable (raised as a
        // bare COLUMN ref). With the var present, both engines read the VARIABLE — the leaf is
        // dataset-constant; without it, the fold must stay UNKNOWN (the literal/column fallbacks
        // are not provably aligned).
        Expr leaf = new Expr.Binary(
                Expr.BinOp.EQ, new Expr.Call("ds_name",
                        List.of(new Expr.Lit(Expr.LitKind.STRING, "DATA")), Map.of()),
                new Expr.Ref("DOMAIN", OperandKind.COLUMN));
        EvaluationContext withVar = ctx(Map.of("DOMAIN", "AE"));
        assertEquals(Verdict.TRUE, BroadcastFold.fold(leaf, withVar, false),
                "ds_name == $DOMAIN-var on the AE table decides natively");
        EvaluationContext withoutVar = ctx(Map.of());
        assertEquals(Verdict.UNKNOWN, BroadcastFold.fold(leaf, withoutVar, false),
                "no context variable → not provably dataset-constant");
    }


    @Test
    void boolCallsOverDatasetFactsDecide_p6FindingA1_afterD121()
    {
        // ⭐ RE-EXPECTED by D121/D121a (terminal review L1). P6's finding A1 kept a hand-written
        // roster (FOLD_EQUIVALENT_BOOL_CALLS) mirroring the legacy
        // CheckConditionOptimizer.SUPPORTED_METADATA_OPERATORS, so that a BOOLEAN call OUTSIDE it
        // stayed UNKNOWN and its legacy leaf survived to the row path — preserving the verdict
        // MULTIPLICITY of the two engines. Phase 7d deleted the reference copy, so there is no
        // longer anything for the roster to be faithful to, and D121a rules the question itself
        // out of the engine.
        //
        // What decides now is the PROPERTY, not the name: every argument is a dataset fact, so the
        // call has ONE value for the whole dataset whichever function it is. ⚑ Measured free on
        // the corpus — no non-roster BOOLEAN call over dataset-fact-only arguments exists in
        // rules-src, so nothing shipped changes multiplicity.
        Expr isInt = new Expr.Call("is_integer",
                List.of(new Expr.Ref("$x", OperandKind.OPERATION_REF)), Map.of());
        assertEquals(Verdict.TRUE, BroadcastFold.fold(isInt, ctx(Map.of("$x", "5")), false),
                "is_integer over a dataset-level $-operand is a dataset fact and decides");
        // …and the boundary still holds: empty($x) decided before and decides now.
        Expr emptyCall = new Expr.Call("empty",
                List.of(new Expr.Ref("$x", OperandKind.OPERATION_REF)), Map.of());
        assertEquals(Verdict.FALSE, BroadcastFold.fold(emptyCall, ctx(Map.of("$x", "5")), false));
        // ⛔ The control that keeps this from becoming "everything folds": an argument that is NOT
        // a dataset fact — a PRESENT per-row column of the table — still declines, so a row-level
        // rule keeps its per-row findings. (AETERM is a real column of this fixture; an ABSENT
        // name would be a dataset fact under EC-43 and would decide, which is correct and is
        // exactly why the control has to name a present one.)
        Expr overColumn = new Expr.Call("is_integer",
                List.of(new Expr.Ref("AETERM", OperandKind.COLUMN)), Map.of());
        assertEquals(Verdict.UNKNOWN, BroadcastFold.fold(overColumn, ctx(Map.of("$x", "5")), false),
                "a per-row column operand is not a dataset fact — the fold must still decline");
    }


    @Test
    void providerAbsentDatasetAccessorStaysUnknown_p6FindingB1()
    {
        // ds_label("DEFINE") with no define provider: the fold must NOT compute a verdict over a
        // null provider read — UNKNOWN preserves the dispatch's documented SKIPPED contract (D7).
        Expr defineCmp = new Expr.Binary(Expr.BinOp.NEQ,
                new Expr.Call("ds_label", List.of(new Expr.Lit(Expr.LitKind.STRING, "DATA")),
                        Map.of()),
                new Expr.Call("ds_label", List.of(new Expr.Lit(Expr.LitKind.STRING, "DEFINE")),
                        Map.of()));
        assertEquals(Verdict.UNKNOWN, BroadcastFold.fold(defineCmp, ctx(Map.of()), false),
                "no define provider → not dataset-decidable");
    }


    @Test
    void builtinDatasetOperandsStayUnknown_defenseInDepth()
    {
        // library_dataset_* / define_dataset_* outside Dataset Metadata Check are loadError-tagged
        // since the guard-residual disposition (RuleLoadValidationDatasetProviderOperandTest), so
        // raised rules never reach the fold; for a synthetic expression the fold conservatively
        // stays UNKNOWN rather than guessing semantics for the bare ref.
        Expr corner = new Expr.Binary(Expr.BinOp.EQ,
                new Expr.Ref("library_dataset_class", OperandKind.BUILTIN), LIT_A);
        assertEquals(Verdict.UNKNOWN, fold(corner));
    }

    // ------------------------------------------------------------------
    // Missing-column fold mirror (Fix #40)
    // ------------------------------------------------------------------


    @Test
    void absentColumnLeafFoldsAtDatasetLevel_presentColumnStaysRowLevel()
    {
        Expr missing = new Expr.Binary(Expr.BinOp.EQ, new Expr.Ref("AEXX", OperandKind.COLUMN),
                LIT_A);
        Expr present = new Expr.Binary(Expr.BinOp.EQ, new Expr.Ref("AETERM", OperandKind.COLUMN),
                LIT_A);
        // D111 / D39a: an absent column is a dataset-level constant (bindColumnLevel), so the
        // leaf evaluates ONCE — the compiled program folds the read to all-missing (EC-43) and
        // the operator computes its own polarity: missing == "a" is FALSE, its negation TRUE.
        // This deliberately reports differently from a present-but-all-blank column (one
        // dataset-level finding vs one per row): D111 rules the difference epistemic — absence
        // is a schema fact known before a row is read, blankness a data fact.
        assertEquals(Verdict.FALSE, fold(missing));
        assertEquals(Verdict.TRUE, fold(new Expr.Not(missing)));
        // A PRESENT column stays row-level, whatever its values — the fold never reads data.
        assertEquals(Verdict.UNKNOWN, fold(present));
        // Wrapped name side (upper(AEXX) == "A") folds identically — a pure value function of a
        // dataset-level operand is itself dataset-level (the DomainScan fall-through mirrored in
        // absentColumnLeafLevel).
        Expr wrapped = new Expr.Binary(Expr.BinOp.EQ,
                new Expr.Call("upper", List.of(new Expr.Ref("AEXX", OperandKind.COLUMN)), Map.of()),
                LIT_A);
        assertEquals(Verdict.FALSE, fold(wrapped));
        // A leaf mixing the absent column with a PRESENT one is row-level — the level join
        // (finest of the operands) declines, so the row path keeps its per-row findings.
        Expr mixed = new Expr.Binary(Expr.BinOp.EQ, new Expr.Ref("AEXX", OperandKind.COLUMN),
                new Expr.Ref("AETERM", OperandKind.COLUMN));
        assertEquals(Verdict.UNKNOWN, fold(mixed));
        // D77: a --prefix name side no longer resolves here — an unspecialised one is an error.
        Expr prefixed = new Expr.Binary(Expr.BinOp.EQ,
                new Expr.Ref("--SEV", OperandKind.WILDCARD_COLUMN), LIT_A);
        org.junit.jupiter.api.Assertions.assertThrows(
                net.cumba.corej.core.expr.ExpressionException.class, () -> fold(prefixed));
    }


    @Test
    void joinedDatasetSurfaceableColumnDoesNotFold()
    {
        // AEXX is absent from the primary table but present on a joined dataset — the legacy
        // fold leaves the leaf for row-level resolution, so the native fold must too.
        SyntheticDataTable ex = new SyntheticDataTable("EX", List.of("USUBJID", "AEXX"),
                new String[]
                {
                        "S1"
                }, 1);
        DatasetLookup lookup = DatasetLookup.build("EX", ex, List.of("USUBJID"));
        EvaluationContext joinedCtx = EvaluationContext.builder()
                .table(new SyntheticDataTable("AE", List.of("USUBJID", "AETERM"), new String[]
                {
                        "S1"
                }, 1)).joinedDatasets(Map.of("EX", lookup))
                .datasetResolver(n -> "EX".equals(n) ? ex : null).build();
        Expr viaJoin = new Expr.Binary(Expr.BinOp.EQ, new Expr.Ref("AEXX", OperandKind.COLUMN),
                LIT_A);
        assertEquals(Verdict.UNKNOWN, BroadcastFold.fold(viaJoin, joinedCtx, false));
    }

    // ------------------------------------------------------------------
    // Runtime $-operand helpers
    // ------------------------------------------------------------------


    @Test
    void readsRowDataClassification()
    {
        EvaluationContext c = ctx();
        assertTrue(
                BroadcastFold.readsRowData(new Expr.Binary(Expr.BinOp.EQ,
                        new Expr.Ref("AETERM", OperandKind.COLUMN), LIT_A), c),
                "bare column comparison reads rows");
        assertFalse(
                BroadcastFold.readsRowData(new Expr.Call("var_exists",
                        List.of(new Expr.Ref("AETERM", OperandKind.COLUMN)), Map.of()), c),
                "presence facts are dataset-level, not row reads");
        assertTrue(BroadcastFold.readsRowData(new Expr.Call("value", List.of(), Map.of()), c),
                "value() reads the current variable's cells");
        assertTrue(BroadcastFold.readsRowData(U, c), "a grouped $-ref resolves per row");
        assertFalse(
                BroadcastFold.readsRowData(
                        new Expr.Binary(Expr.BinOp.EQ,
                                new Expr.Ref("$x", OperandKind.OPERATION_REF), LIT_A),
                        ctx(Map.of("$x", "scalar"))),
                "a scalar $-ref is row-independent");
    }


    @Test
    void vmrGuardPositionDetection()
    {
        EvaluationContext c = ctx(
                Map.of("$vmr", new VariableMetadataResult(Map.of("AETERM", "Term"))));
        Expr guard = new Expr.Binary(Expr.BinOp.NEQ,
                new Expr.Ref("$vmr", OperandKind.OPERATION_REF),
                new Expr.Ref("variable_label", OperandKind.BUILTIN));
        Expr valuePos = new Expr.Binary(Expr.BinOp.EQ, new Expr.Ref("AETERM", OperandKind.COLUMN),
                new Expr.Ref("$vmr", OperandKind.OPERATION_REF));
        assertTrue(BroadcastFold.vmrRefsOnlyInGuardPosition(guard, c));
        assertFalse(BroadcastFold.vmrRefsOnlyInGuardPosition(valuePos, c));
        assertFalse(
                BroadcastFold.vmrRefsOnlyInGuardPosition(new Expr.And(List.of(guard, valuePos)), c),
                "a mixed-position tree must NOT project (the Step-4 raw-object contract wins)");
    }

}
