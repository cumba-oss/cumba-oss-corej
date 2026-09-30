package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BroadcastFold;
import net.cumba.corej.core.expr.eval.Domain;
import net.cumba.corej.core.expr.typed.Granularity;
import net.cumba.corej.core.model.CompiledBinding;
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
                CheckExpressionParser.parse(source), source);
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
    void aRuntimePerRowBindingValueIsItsOwnDeclineClass()
    {
        // The static binding level says scalar (no declaration to say otherwise); the runtime
        // value is per-row. The runtime-kind probe is the one thing the static level cannot
        // subsume. (The per-row value was an operation's grouped result until runbook W8; a
        // per-row compiled binding hands over its Vector.)
        Expr expr = new Expr.Binary(Expr.BinOp.EQ, new Expr.Ref("$g", OperandKind.OPERATION_REF),
                new Expr.Lit(Expr.LitKind.STRING, "a"));
        EvaluationContext ctx = ctx(ae(), Map.of("$g", new BindingValue(new CompiledBinding("$g",
                CheckExpressionParser.parse("upper(AETERM)"), List.of(), Domain.ROW))));
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
        // (max is a registry function since runbook W5: the grouped binding is a compiled one.)
        Rule rule = loadedRule("{\"Core\":{\"Id\":\"L-G\"},\"Sensitivity\":\"Record\","
                + "\"Bindings\":[{\"name\":\"$m\",\"expression\":\"max(AESEQ, group=[USUBJID])\"}],"
                + "\"Check\":{\"expression\":\"$m > 5\"}}");
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


    /** A one-rule package through the production loader. */
    private static Rule loadedRule(String ruleJson)
    {
        try
        {
            Rule rule = net.cumba.corej.core.RulePackageLoader
                    .loadFromString("{\"rules\":{\"L-1\":" + ruleJson + "}}").getRules().values()
                    .iterator().next();
            assertNull(rule.getLoadError(), rule.getLoadError());
            return rule;
        }
        catch (Exception e)
        {
            throw new IllegalArgumentException("bad test fixture: " + ruleJson, e);
        }
    }

    // (The three D106d tests — a cross-dataset grouped OPERATION whose absent foreign domain
    // refines the binding level to dataset, the resolvable counter-case and the split-SUPP
    // self-reference — went with the last grouped operation in runbook W7: the refinement,
    // StageAChecker.degeneratesToScalar, reads declared operations only, and record_count /
    // distinct are compiled bindings since W6 / W7. Recorded for the combined review in
    // PLAN-distinct-function §8.)


    @Test
    void aThrowingObserverNeverEscapesTheEntryPoints()
    {
        // The instrument contract: a defect in it must never affect an execution.
        AtomicInteger foldCalls = new AtomicInteger();
        AtomicInteger granularityCalls = new AtomicInteger();
        AtomicInteger groupCalls = new AtomicInteger();
        LevelInstrument.setFoldObserver(obs ->
        {
            foldCalls.incrementAndGet();
            throw new IllegalStateException("fold observer boom");
        });
        LevelInstrument.setGranularityObserver(obs ->
        {
            granularityCalls.incrementAndGet();
            throw new IllegalStateException("granularity observer boom");
        });
        LevelInstrument.setGroupOutputObserver(obs ->
        {
            groupCalls.incrementAndGet();
            throw new IllegalStateException("group observer boom");
        });
        Rule rule = new Rule();
        rule.setSensitivity(Sensitivity.RECORD);
        EvaluationContext ctx = ctx(ae(), Map.of());
        Expr expr = CheckExpressionParser.parse("AETERM == \"x\"");
        assertDoesNotThrow(() -> LevelInstrument.onFold(rule, ctx, expr, "check",
                BroadcastFold.Verdict.UNKNOWN));
        assertDoesNotThrow(() -> LevelInstrument.onEffectiveGranularity(rule, ctx, expr));
        assertDoesNotThrow(() -> LevelInstrument.onGroupOutputs(ctx, java.util.Set.of("AEDECOD")));
        // Non-vacuity: each observer was really reached and really threw, so the containment
        // above was exercised rather than skipped.
        assertEquals(1, foldCalls.get(), "the fold observer must have been reached");
        assertEquals(1, granularityCalls.get(), "the granularity observer must have been reached");
        assertEquals(1, groupCalls.get(), "the group-output observer must have been reached");
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

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae());

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

        RuleExecutionResult result = RuleRunnerCalls.execute(rule, ae());

        assertEquals(RuleExecutionStatus.SKIPPED, result.getStatus());
        assertNotNull(fold.get(), "the precondition fold site must observe");
        assertEquals("precondition", fold.get().site());
        assertEquals(LevelInstrument.FoldAgreement.AGREE_DECIDED, fold.get().agreement());
        assertEquals(BroadcastFold.Verdict.FALSE, fold.get().verdict());
    }

}
