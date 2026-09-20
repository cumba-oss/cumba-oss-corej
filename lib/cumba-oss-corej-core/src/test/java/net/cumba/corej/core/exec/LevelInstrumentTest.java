package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BroadcastFold;
import net.cumba.corej.core.expr.typed.Granularity;
import net.cumba.corej.core.model.Operation;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Phase 5's {@link LevelInstrument}: the static-level-vs-runtime-fold comparison and its
 * disagreement classes (each of which is a phase finding, never a silently-preferred side), the
 * SPEC §8 effective-granularity observation, the D39a/Fix-#10 bind-time column refinements, and the
 * wiring through {@code RuleRunner}'s production fold sites.
 */
class LevelInstrumentTest
{

    private static net.cumba.corej.core.model.CheckConditionExpression expr(String source)
    {
        return new net.cumba.corej.core.model.CheckConditionExpression(
                net.cumba.corej.core.expr.CheckExpressionParser.parse(source), source);
    }


    @AfterEach
    void reset()
    {
        LevelInstrument.setFoldObserver(null);
        LevelInstrument.setGranularityObserver(null);
        LevelInstrument.setGroupOutputObserver(null);
        System.clearProperty("corej.level.instrument.dir");
        LevelInstrument.resetWriterForTests();
    }


    private static EvaluationContext ctx(IDataTable table, Map<String, Object> variables)
    {
        return EvaluationContext.builder().table(table).variables(variables).build();
    }


    private static IDataTable ae()
    {
        return MockTable.of().name("AE").col("AETERM", "x", "y").col("AESEV", "MILD", "SEVERE")
                .build();
    }


    private static LevelInstrument.FoldObservation observe(Rule rule, EvaluationContext ctx,
            Expr expr)
    {
        AtomicReference<LevelInstrument.FoldObservation> seen = new AtomicReference<>();
        LevelInstrument.setFoldObserver(seen::set);
        LevelInstrument.onFold(rule, ctx, expr, "check", BroadcastFold.fold(expr, ctx, false));
        LevelInstrument.FoldObservation obs = seen.get();
        assertNotNull(obs, "the fold observer must be called while the instrument is active");
        return obs;
    }

    // ------------------------------------------------------------------
    // Inactivity — the production default
    // ------------------------------------------------------------------


    @Test
    void theInstrumentIsInertWithoutAnObserverOrDirectory()
    {
        // Guard the guard: if the environment variable is set (a measurement build), activity is
        // configured deliberately and this assertion does not apply.
        if (System.getenv("COREJ_LEVEL_INSTRUMENT_DIR") == null)
        {
            assertFalse(LevelInstrument.active());
            // And the entry points are no-ops — nothing to assert beyond "no throw".
            LevelInstrument.onFold(new Rule(), ctx(ae(), Map.of()),
                    CheckExpressionParser.parse("AETERM == \"x\""), "check",
                    BroadcastFold.Verdict.UNKNOWN);
        }
    }

    // ------------------------------------------------------------------
    // The agreement classes
    // ------------------------------------------------------------------


    @Test
    void aLiteralComparisonAgreesDecided()
    {
        LevelInstrument.FoldObservation obs = observe(new Rule(), ctx(ae(), Map.of()),
                CheckExpressionParser.parse("\"a\" == \"a\""));
        assertEquals(LevelInstrument.FoldAgreement.AGREE_DECIDED, obs.agreement());
        assertEquals(BroadcastFold.Verdict.TRUE, obs.verdict());
        assertNotNull(obs.level());
        assertEquals("study", obs.level().describe());
    }


    @Test
    void aRowLeafAgreesUndecided()
    {
        LevelInstrument.FoldObservation obs = observe(new Rule(), ctx(ae(), Map.of()),
                CheckExpressionParser.parse("AETERM == \"x\""));
        assertEquals(LevelInstrument.FoldAgreement.AGREE_UNDECIDED, obs.agreement());
        assertEquals(BroadcastFold.Verdict.UNKNOWN, obs.verdict());
    }


    @Test
    void anAbsentColumnLeafAgreesDecided_theD39PopulationFolds()
    {
        // D111 (phase 7): D39a refines the absent column to dataset level AND the fold now
        // decides it — the same bindColumnLevel fact on both sides, so the phase-5 disagreement
        // class this instrument was built around is closed by construction. The verdict is the
        // operator's own polarity over all-missing: missing != "x" is TRUE.
        LevelInstrument.FoldObservation obs = observe(new Rule(), ctx(ae(), Map.of()),
                CheckExpressionParser.parse("ZZFOO != \"x\""));
        assertEquals(LevelInstrument.FoldAgreement.AGREE_DECIDED, obs.agreement());
        assertEquals(BroadcastFold.Verdict.TRUE, obs.verdict());
        assertNotNull(obs.level());
        assertEquals("dataset", obs.level().describe());
    }


    @Test
    void anAbsenceInsideADeclinedShapeIsTheResidualAbsentColumnClass()
    {
        // The residual ABSENT_COLUMN population after D111: the walk's absent-column refinement
        // fires (ZZFOO is absent) and the derived level is dataset, but the leaf FAMILY is one
        // the fold deliberately leaves to other machinery (a whole-column verdict operator), so
        // the fold still declines. The refinement is evidence here, not the decline's cause —
        // see the DeclineReason.ABSENT_COLUMN javadoc.
        LevelInstrument.FoldObservation obs = observe(new Rule(), ctx(ae(), Map.of()),
                CheckExpressionParser.parse("has_same_values(ZZFOO, AESEV)"));
        assertEquals(LevelInstrument.FoldAgreement.FOLD_DECLINED, obs.agreement());
        assertEquals(LevelInstrument.DeclineReason.ABSENT_COLUMN.name(), obs.reason());
    }


    @Test
    void aContextScalarValueOperandAgreesDecided()
    {
        // The Fix #10 shape (CDISC-CG0413): a bare name on the VALUE side resolving to a scalar
        // context variable is a dataset fact in both engines (variables resolve before columns).
        // Without the bind-time refinement this would misclassify as FOLD_EXCEEDED.
        LevelInstrument.FoldObservation obs = observe(new Rule(), ctx(ae(), Map.of("DOMAIN", "AE")),
                CheckExpressionParser.parse("ds_name(\"DATA\") == DOMAIN"));
        assertEquals(LevelInstrument.FoldAgreement.AGREE_DECIDED, obs.agreement());
    }


    @Test
    void aRuntimePerRowOperationResultIsItsOwnDeclineClass()
    {
        // The static binding level says scalar (no declaration to say otherwise); the runtime
        // value materialised per-row. The materialised-kind probe is the one thing the static
        // level cannot subsume.
        Expr expr = new Expr.Binary(Expr.BinOp.EQ, new Expr.Ref("$g", OperandKind.OPERATION_REF),
                new Expr.Lit(Expr.LitKind.STRING, "a"));
        EvaluationContext ctx = ctx(ae(),
                Map.of("$g", new GroupedResult(List.of("USUBJID"), Map.of("S1", "a"))));
        LevelInstrument.FoldObservation obs = observe(new Rule(), ctx, expr);
        assertEquals(LevelInstrument.FoldAgreement.FOLD_DECLINED, obs.agreement());
        assertEquals(LevelInstrument.DeclineReason.OPERATION_RUNTIME_KIND.name(), obs.reason());
    }


    @Test
    void anAbsentProviderIsItsOwnDeclineClass()
    {
        // The D7 SKIPPED contract: a ds_* accessor over an absent DEFINE provider must stay
        // UNKNOWN at run time however dataset-level it is statically.
        LevelInstrument.FoldObservation obs = observe(new Rule(), ctx(ae(), Map.of()),
                CheckExpressionParser.parse("ds_label(\"DEFINE\") == \"x\""));
        assertEquals(LevelInstrument.FoldAgreement.FOLD_DECLINED, obs.agreement());
        assertEquals(LevelInstrument.DeclineReason.PROVIDER_ABSENT.name(), obs.reason());
    }


    @Test
    void aWholeColumnVerdictIsTheLegacyNarrownessClass()
    {
        // Statically a dataset verdict; the fold's legacy-parity scope never evaluated it (the
        // row path collapses it instead). The LEVEL is right — the fold is just one of several
        // dataset-level mechanisms.
        LevelInstrument.FoldObservation obs = observe(new Rule(), ctx(ae(), Map.of()),
                CheckExpressionParser.parse("has_same_values(AETERM, AESEV)"));
        assertEquals(LevelInstrument.FoldAgreement.FOLD_DECLINED, obs.agreement());
        assertEquals(LevelInstrument.DeclineReason.UNSUPPORTED_SHAPE.name(), obs.reason());
    }


    @Test
    void aValueShortCircuitExceedsTheStaticLevel()
    {
        // any[TRUE, row-leaf] decides from the VALUE of the decidable operand — Kleene
        // short-circuit, unpredictable by any static level. SPEC §1.3's join cannot represent
        // it: the finding, not a bug.
        LevelInstrument.FoldObservation obs = observe(new Rule(), ctx(ae(), Map.of()),
                CheckExpressionParser.parse("\"a\" == \"a\" or AETERM == \"x\""));
        assertEquals(LevelInstrument.FoldAgreement.FOLD_EXCEEDED, obs.agreement());
        assertEquals(LevelInstrument.ExceedReason.SHORT_CIRCUIT.name(), obs.reason());
        assertEquals(BroadcastFold.Verdict.TRUE, obs.verdict());
    }


    @Test
    void anUnderivableExpressionIsReportedAsSuch()
    {
        Expr broken = new Expr.Lit(Expr.LitKind.LIST, "not a list");
        LevelInstrument.FoldObservation obs = observe(new Rule(), ctx(ae(), Map.of()), broken);
        assertEquals(LevelInstrument.FoldAgreement.UNDERIVABLE, obs.agreement());
    }

    // ------------------------------------------------------------------
    // Effective granularity (SPEC §8)
    // ------------------------------------------------------------------


    @Test
    void anAllAbsentRecordRuleDerivesADatasetEffectiveGranularity()
    {
        Rule rule = new Rule();
        rule.setSensitivity(Sensitivity.RECORD);
        AtomicReference<LevelInstrument.GranularityObservation> seen = new AtomicReference<>();
        LevelInstrument.setGranularityObserver(seen::set);
        LevelInstrument.onEffectiveGranularity(rule, ctx(ae(), Map.of()),
                CheckExpressionParser.parse("ZZFOO != \"x\""));
        LevelInstrument.GranularityObservation obs = seen.get();
        assertNotNull(obs);
        assertEquals(Granularity.Simple.DATASET, obs.effective().granularity());
        assertTrue(obs.effective().coarsened());
    }


    @Test
    void aGroupOnlyDerivationCoarsensARecordRuleToTheDerivedKey()
    {
        // D39d: every operand group-level on one key — the rule reports per group with the key
        // derived from the expressions, never invented.
        Rule rule = new Rule();
        rule.setSensitivity(Sensitivity.RECORD);
        Operation op = new Operation();
        op.setId("$m");
        op.setOperator("max");
        op.setName("AESEQ");
        op.setGroup(List.of("USUBJID"));
        rule.setOperations(List.of(op));
        AtomicReference<LevelInstrument.GranularityObservation> seen = new AtomicReference<>();
        LevelInstrument.setGranularityObserver(seen::set);
        LevelInstrument.onEffectiveGranularity(rule, ctx(ae(), Map.of()),
                CheckExpressionParser.parse("$m > 5"));
        LevelInstrument.GranularityObservation obs = seen.get();
        assertNotNull(obs);
        assertEquals(new Granularity.Group(java.util.Set.of("USUBJID")),
                obs.effective().granularity());
        assertTrue(obs.effective().coarsened());
    }


    @Test
    void aDecidedVerdictOverARowLevelNonCombinatorIsAModelGap()
    {
        // No combinator, no decidable operand — if the fold ever decided here the level model
        // itself would be wrong. Classification-only: the verdict is fabricated, since no shipped
        // leaf produces it (that being the point).
        AtomicReference<LevelInstrument.FoldObservation> seen = new AtomicReference<>();
        LevelInstrument.setFoldObserver(seen::set);
        LevelInstrument.onFold(new Rule(), ctx(ae(), Map.of()),
                CheckExpressionParser.parse("AETERM == \"x\""), "check",
                BroadcastFold.Verdict.TRUE);
        assertNotNull(seen.get());
        assertEquals(LevelInstrument.FoldAgreement.FOLD_EXCEEDED, seen.get().agreement());
        assertEquals(LevelInstrument.ExceedReason.MODEL_GAP.name(), seen.get().reason());
    }


    @Test
    void anAbsentForeignDomainRefinesTheBindingLevelToDataset()
    {
        // Phase 6, D106d: the same shape as the MODEL_GAP test below, but the operation is
        // cross-dataset and its foreign domain does not resolve — the run's operation degenerates
        // to a scalar, the refined static level is dataset, and a decided fold AGREES instead of
        // exceeding. This was the whole harness MODEL_GAP population (96 cases, all cross-dataset
        // $-bindings over absent domains).
        Rule rule = crossDatasetGroupedRule();
        AtomicReference<LevelInstrument.FoldObservation> seen = new AtomicReference<>();
        LevelInstrument.setFoldObserver(seen::set);
        LevelInstrument.onFold(rule, ctx(ae(), Map.of()), CheckExpressionParser.parse("$n == 0"),
                "check", BroadcastFold.Verdict.TRUE);
        assertNotNull(seen.get());
        assertEquals(LevelInstrument.FoldAgreement.AGREE_DECIDED, seen.get().agreement());
    }


    @Test
    void aResolvableForeignDomainKeepsTheDeclarationDerivedLevel()
    {
        // The counter-case: DS resolves, the grouped binding keeps group(K), and a decided fold
        // over it is still the (fabricated) MODEL_GAP — the refinement must not over-reach.
        Rule rule = crossDatasetGroupedRule();
        IDataTable ds = MockTable.of().name("DS").col("USUBJID", "S1").col("DSSTDTC", "2024")
                .build();
        EvaluationContext withDs = EvaluationContext.builder().table(ae())
                .datasetResolver(name -> "DS".equals(name) ? ds : null).build();
        AtomicReference<LevelInstrument.FoldObservation> seen = new AtomicReference<>();
        LevelInstrument.setFoldObserver(seen::set);
        LevelInstrument.onFold(rule, withDs, CheckExpressionParser.parse("$n == 0"), "check",
                BroadcastFold.Verdict.TRUE);
        assertNotNull(seen.get());
        assertEquals(LevelInstrument.FoldAgreement.FOLD_EXCEEDED, seen.get().agreement());
        assertEquals(LevelInstrument.ExceedReason.MODEL_GAP.name(), seen.get().reason());
    }


    @Test
    void theSplitSuppSelfReferenceDoesNotDegenerate()
    {
        // J7: a SUPP-family operation domain that collapsed to the unsplit family name runs
        // against the current (split-member) table — the executor redirects, so the instrument's
        // inventory reports it resolvable and the binding keeps its declared level.
        Rule rule = crossDatasetGroupedRule();
        rule.getOperations().getFirst().setDomain("SUPPLB");
        IDataTable supplbch = MockTable.of().name("SUPPLBCH").col("USUBJID", "S1").build();
        EvaluationContext ctx = EvaluationContext.builder().table(supplbch).build();
        AtomicReference<LevelInstrument.FoldObservation> seen = new AtomicReference<>();
        LevelInstrument.setFoldObserver(seen::set);
        LevelInstrument.onFold(rule, ctx, CheckExpressionParser.parse("$n == 0"), "check",
                BroadcastFold.Verdict.TRUE);
        assertNotNull(seen.get());
        assertEquals(LevelInstrument.FoldAgreement.FOLD_EXCEEDED, seen.get().agreement());
    }


    private static Rule crossDatasetGroupedRule()
    {
        Rule rule = new Rule();
        rule.setSensitivity(Sensitivity.RECORD);
        Operation op = new Operation();
        op.setId("$n");
        op.setOperator("record_count");
        op.setDomain("DS");
        op.setGroup(List.of("USUBJID"));
        rule.setOperations(List.of(op));
        return rule;
    }


    @Test
    void aThrowingObserverNeverEscapesTheEntryPoints()
    {
        // The instrument contract: a defect in it must never affect an execution.
        LevelInstrument.setFoldObserver(obs ->
        {
            throw new IllegalStateException("fold observer boom");
        });
        LevelInstrument.setGranularityObserver(obs ->
        {
            throw new IllegalStateException("granularity observer boom");
        });
        LevelInstrument.setGroupOutputObserver(obs ->
        {
            throw new IllegalStateException("group observer boom");
        });
        Rule rule = new Rule();
        rule.setSensitivity(Sensitivity.RECORD);
        EvaluationContext ctx = ctx(ae(), Map.of());
        Expr expr = CheckExpressionParser.parse("AETERM == \"x\"");
        LevelInstrument.onFold(rule, ctx, expr, "check", BroadcastFold.Verdict.UNKNOWN);
        LevelInstrument.onEffectiveGranularity(rule, ctx, expr);
        LevelInstrument.onGroupOutputs(ctx, java.util.Set.of("AEDECOD"));
    }


    @Test
    void granularityObservationSkipsQuietlyWithoutASensitivityOrALevel()
    {
        AtomicReference<LevelInstrument.GranularityObservation> seen = new AtomicReference<>();
        LevelInstrument.setGranularityObserver(seen::set);
        // No declared Sensitivity -> nothing to compare against.
        LevelInstrument.onEffectiveGranularity(new Rule(), ctx(ae(), Map.of()),
                CheckExpressionParser.parse("AETERM == \"x\""));
        assertEquals(null, seen.get());
        // An underivable expression -> no level to derive from.
        Rule rule = new Rule();
        rule.setSensitivity(Sensitivity.RECORD);
        LevelInstrument.onEffectiveGranularity(rule, ctx(ae(), Map.of()),
                new Expr.Lit(Expr.LitKind.LIST, "not a list"));
        assertEquals(null, seen.get());
    }

    // ------------------------------------------------------------------
    // The measurement file channel
    // ------------------------------------------------------------------


    @Test
    void anUnusableDirectoryDisablesTheFileChannelWithoutFailing(@TempDir Path dir) throws Exception
    {
        // Point the "directory" at a regular file: opening the writer must fail once, latch, and
        // never affect the observation path.
        Path file = dir.resolve("not-a-directory");
        Files.writeString(file, "x");
        System.setProperty("corej.level.instrument.dir", file.toString());
        AtomicReference<LevelInstrument.FoldObservation> seen = new AtomicReference<>();
        LevelInstrument.setFoldObserver(seen::set);
        LevelInstrument.onFold(new Rule(), ctx(ae(), Map.of()),
                CheckExpressionParser.parse("\"a\" == \"a\""), "check", BroadcastFold.Verdict.TRUE);
        assertNotNull(seen.get(), "the observer channel must survive a writer failure");
    }


    @Test
    void theDirectoryChannelWritesOneLinePerObservation(@TempDir Path dir) throws Exception
    {
        System.setProperty("corej.level.instrument.dir", dir.toString());
        Expr expr = CheckExpressionParser.parse("\"a\" == \"a\"");
        EvaluationContext ctx = ctx(ae(), Map.of());
        LevelInstrument.onFold(new Rule(), ctx, expr, "check",
                BroadcastFold.fold(expr, ctx, false));
        List<Path> files;
        try (var stream = Files.list(dir))
        {
            files = stream.toList();
        }
        assertEquals(1, files.size());
        String content = Files.readString(files.get(0));
        assertTrue(content.contains("level-fold agreement=AGREE_DECIDED"), content);
        assertTrue(content.contains("site=check"), content);
    }

    // ------------------------------------------------------------------
    // Wiring through the production fold sites
    // ------------------------------------------------------------------


    @Test
    void theCheckFoldSiteAndTheGranularityObservationRunInsideExecute()
    {
        Rule rule = new Rule();
        rule.setId("TEST-L1");
        rule.setSensitivity(Sensitivity.RECORD);
        rule.setCheck(expr("AETERM == \"x\""));
        // Phase 7 (owner, 2026-09-16): the dataset-level fold site runs for EVERY rule that has a
        // compiled expression — there is no longer a carve-out asking whether the retired
        // leaf-based engine would also have folded it. A checkExpr is all this fixture needs.
        rule.setCheckExpr(CheckExpressionParser.parse("AETERM == \"x\""));
        AtomicReference<LevelInstrument.FoldObservation> fold = new AtomicReference<>();
        AtomicReference<LevelInstrument.GranularityObservation> gran = new AtomicReference<>();
        LevelInstrument.setFoldObserver(fold::set);
        LevelInstrument.setGranularityObserver(gran::set);

        RuleExecutionResult result = RuleRunner.execute(rule, ae());

        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus());
        assertNotNull(fold.get(), "the check fold site must observe");
        assertEquals("check", fold.get().site());
        assertEquals(LevelInstrument.FoldAgreement.AGREE_UNDECIDED, fold.get().agreement());
        assertNotNull(gran.get(), "the effective-granularity site must observe");
        assertEquals(Sensitivity.RECORD, gran.get().declared());
        assertFalse(gran.get().effective().coarsened());
    }


    @Test
    void thePreconditionFoldSiteObservesBeforeTheSkip()
    {
        Rule rule = new Rule();
        rule.setId("TEST-L2");
        rule.setSensitivity(Sensitivity.RECORD);
        rule.setCheckExpr(CheckExpressionParser.parse("AETERM == \"x\""));
        rule.setCheck(expr("AETERM == \"x\""));
        rule.setPrecondition(expr("var_exists(\"ZZFOO\")"));
        rule.setPreconditionExpr(CheckExpressionParser.parse("var_exists(\"ZZFOO\")"));
        AtomicReference<LevelInstrument.FoldObservation> fold = new AtomicReference<>();
        LevelInstrument.setFoldObserver(fold::set);

        RuleExecutionResult result = RuleRunner.execute(rule, ae());

        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus());
        assertNotNull(fold.get(), "the precondition fold site must observe");
        assertEquals("precondition", fold.get().site());
        assertEquals(LevelInstrument.FoldAgreement.AGREE_DECIDED, fold.get().agreement());
        assertEquals(BroadcastFold.Verdict.FALSE, fold.get().verdict());
    }

}
