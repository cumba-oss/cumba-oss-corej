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
                .table(new SyntheticStringTable("AE", List.of("AETERM", "AESEV"), new String[]
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
    void domainPrefixExistsResolvesInFold()
    {
        // --TERM resolves to AETERM via the context's domain prefix, exactly like the compiled
        // exists closure and the legacy phase-2c rewrite before the fold.
        Expr prefixed = new Expr.Call("var_exists",
                List.of(new Expr.Ref("--TERM", OperandKind.WILDCARD_COLUMN)), Map.of());
        assertEquals(Verdict.TRUE, fold(prefixed));
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
        // CORE-000598 family: the VALUE side is the Fix #10 DOMAIN context variable (raised as a
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
    void nonFoldableBoolCallsStayUnknown_p6FindingA1()
    {
        // is_integer($x) is BOOLEAN-registered but NOT in the legacy fold's operator surface
        // (SUPPORTED_METADATA_OPERATORS) — the legacy leaf survives to the row path, so the
        // native fold must stay UNKNOWN to preserve the verdict multiplicity.
        Expr isInt = new Expr.Call("is_integer",
                List.of(new Expr.Ref("$x", OperandKind.OPERATION_REF)), Map.of());
        assertEquals(Verdict.UNKNOWN, BroadcastFold.fold(isInt, ctx(Map.of("$x", "5")), false));
        // empty($x) IS fold-equivalent and decides.
        Expr emptyCall = new Expr.Call("empty",
                List.of(new Expr.Ref("$x", OperandKind.OPERATION_REF)), Map.of());
        assertEquals(Verdict.FALSE, BroadcastFold.fold(emptyCall, ctx(Map.of("$x", "5")), false));
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
    void missingColumnComparisonStaysUnknown_soTheRowPathDecidesIt()
    {
        Expr missing = new Expr.Binary(Expr.BinOp.EQ, new Expr.Ref("AEXX", OperandKind.COLUMN),
                LIT_A);
        Expr present = new Expr.Binary(Expr.BinOp.EQ, new Expr.Ref("AETERM", OperandKind.COLUMN),
                LIT_A);
        // EC-43: an absent column no longer short-circuits here. It folds to all-missing at the
        // leaf and the ROW path computes the verdict, exactly as it does for a column that is
        // present but blank on every row — which is the contract. Short-circuiting would also
        // report differently (one dataset-level finding vs one per row), so absent and blank would
        // disagree on the finding SHAPE even where they agreed on the verdict.
        assertEquals(Verdict.UNKNOWN, fold(missing));
        assertEquals(Verdict.UNKNOWN, fold(present));
        // ... and the negated shape likewise defers to the row path.
        assertEquals(Verdict.UNKNOWN, fold(new Expr.Not(missing)));
        // Wrapped name side (upper(AEXX) == "A") folds identically — the legacy fold keys on the
        // leaf NAME regardless of the operator's value wrappers.
        Expr wrapped = new Expr.Binary(Expr.BinOp.EQ,
                new Expr.Call("upper", List.of(new Expr.Ref("AEXX", OperandKind.COLUMN)), Map.of()),
                LIT_A);
        assertEquals(Verdict.UNKNOWN, fold(wrapped));
        // --prefix name side resolves before the presence check: --SEV → AESEV (present).
        Expr prefixed = new Expr.Binary(Expr.BinOp.EQ,
                new Expr.Ref("--SEV", OperandKind.WILDCARD_COLUMN), LIT_A);
        assertEquals(Verdict.UNKNOWN, fold(prefixed));
    }


    @Test
    void joinedDatasetSurfaceableColumnDoesNotFold()
    {
        // AEXX is absent from the primary table but present on a joined dataset — the legacy
        // fold leaves the leaf for row-level resolution, so the native fold must too.
        SyntheticStringTable ex = new SyntheticStringTable("EX", List.of("USUBJID", "AEXX"),
                new String[]
                {
                        "S1"
                }, 1);
        DatasetLookup lookup = DatasetLookup.build("EX", ex, List.of("USUBJID"));
        EvaluationContext joinedCtx = EvaluationContext.builder()
                .table(new SyntheticStringTable("AE", List.of("USUBJID", "AETERM"), new String[]
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
    void operationRefTypeHelpers()
    {
        EvaluationContext c = ctx(
                Map.of("$g", new GroupedResult(List.of("USUBJID"), Map.of("S1", "a")), "$vmr",
                        new VariableMetadataResult(Map.of("AETERM", "Term")), "$s", "scalar"));
        Expr grouped = new Expr.Binary(Expr.BinOp.EQ, new Expr.Ref("$g", OperandKind.OPERATION_REF),
                LIT_A);
        Expr vmr = new Expr.Binary(Expr.BinOp.EQ, new Expr.Ref("$vmr", OperandKind.OPERATION_REF),
                LIT_A);
        Expr scalar = new Expr.Binary(Expr.BinOp.EQ, new Expr.Ref("$s", OperandKind.OPERATION_REF),
                LIT_A);
        assertTrue(BroadcastFold.hasGroupedOperationRef(grouped, c));
        assertFalse(BroadcastFold.hasGroupedOperationRef(vmr, c));
        assertTrue(BroadcastFold.hasVariableMetadataRef(vmr, c));
        assertFalse(BroadcastFold.hasVariableMetadataRef(scalar, c));
        assertFalse(BroadcastFold.hasVariableMetadataRef(grouped, c));
    }


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
