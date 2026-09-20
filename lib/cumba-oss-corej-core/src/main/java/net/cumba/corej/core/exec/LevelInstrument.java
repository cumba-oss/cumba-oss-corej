package net.cumba.corej.core.exec;

import java.io.IOException;
import java.io.PrintWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.cumba.corej.core.expr.OperandKind;
import net.cumba.corej.core.expr.ast.Expr;
import net.cumba.corej.core.expr.eval.BroadcastFold;
import net.cumba.corej.core.expr.eval.MetadataExprScan;
import net.cumba.corej.core.expr.eval.MetadataLevel;
import net.cumba.corej.core.expr.typed.ColumnLevelResolver;
import net.cumba.corej.core.expr.typed.Cursor;
import net.cumba.corej.core.expr.typed.EffectiveGranularity;
import net.cumba.corej.core.expr.typed.Granularity;
import net.cumba.corej.core.expr.typed.Level;
import net.cumba.corej.core.expr.typed.StageAChecker;
import net.cumba.corej.core.expr.typed.TypedExpr;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.Sensitivity;
import org.jspecify.annotations.Nullable;

/**
 * Phase 5 of {@code plans/PLAN-typed-expression-engine.md} — the comparator between the
 * <b>static</b> expression level (the typed tree of SPEC §1.3, refined at bind time by D39a's
 * absent-column rule) and the <b>runtime</b> {@link BroadcastFold} decision, plus the
 * effective-granularity observation of SPEC §8 (D39b-REV) and the D94c group-block output
 * observation.
 *
 * <p>
 * ⭐ <b>Where the two disagree, the disagreement is the phase's finding — and the RUNTIME verdict
 * always wins.</b> The classes are structural, not bugs to fix here:
 * </p>
 * <ul>
 * <li>{@link FoldAgreement#FOLD_DECLINED} — the static level says dataset-decidable, the fold stays
 * UNKNOWN: an absent provider (the deliberate D7 SKIPPED contract), a {@code $}-binding whose
 * materialised runtime kind is per-row, or a shape the fold leaves to other machinery (whole-column
 * verdicts, metadata accessors). ⭐ The class this instrument was built around — EC-43's
 * absent-column row path, the D39 population — was <b>closed by D111</b> (phase 7):
 * {@code BroadcastFold} now folds the absent-column leaf through the same {@code bindColumnLevel}
 * classification this instrument's resolver reads, so that population reports
 * {@link FoldAgreement#AGREE_DECIDED}. {@link DeclineReason#ABSENT_COLUMN} remains for the residual
 * shape: an absence refined <em>inside</em> a leaf family the fold still declines.</li>
 * <li>{@link FoldAgreement#FOLD_EXCEEDED} — the fold decides where the static level says row-level:
 * the Kleene short-circuit is <b>value</b>-dependent ({@code any[…, TRUE, …]} decides around
 * undecidable operands), which no static level can predict; anything else is a model gap.</li>
 * </ul>
 *
 * <p>
 * The instrument is inert unless activated — an observer set by a test, or an output directory via
 * the {@code corej.level.instrument.dir} system property / {@code COREJ_LEVEL_INSTRUMENT_DIR}
 * environment variable (each JVM appends to its own {@code level-instrument-<pid>.log}). When
 * inert, no derivation runs and the execution path is byte-identical to phase 4. Every entry point
 * swallows its own failures: an instrument defect must never affect an execution.
 * </p>
 */
public final class LevelInstrument
{

    private static final System.Logger LOGGER = System.getLogger(LevelInstrument.class.getName());

    /** How the static level and the runtime fold verdict relate at one fold site. */
    public enum FoldAgreement
    {
        /** Static dataset-decidable, fold decided — the level model and the fold agree. */
        AGREE_DECIDED,
        /** Static row/group/cursor level, fold UNKNOWN — agree: the row path is correct. */
        AGREE_UNDECIDED,
        /** Static dataset-decidable, fold UNKNOWN — see {@link DeclineReason}. */
        FOLD_DECLINED,
        /** Fold decided where the static level says row-level — see {@link ExceedReason}. */
        FOLD_EXCEEDED,
        /** The typed walk failed; no static level to compare. */
        UNDERIVABLE
    }


    /** Why the fold declined a statically dataset-decidable expression (first match wins). */
    public enum DeclineReason
    {
        /**
         * A {@code $}-operation reference materialised as a per-row / per-variable result where the
         * static binding level said scalar.
         */
        OPERATION_RUNTIME_KIND,
        /** A DEFINE/LIBRARY read with the provider absent — the D7 SKIPPED contract. */
        PROVIDER_ABSENT,
        /**
         * D39a's absent-column refinement fired during the walk and the fold still declined. Since
         * D111 (phase 7) the plain absent-column leaf FOLDS ({@code AGREE_DECIDED}), so this class
         * no longer means "the D39 population": what remains is the residual shape where the
         * absence sits inside a leaf family the fold deliberately leaves to other machinery (a
         * whole-column verdict, a metadata accessor) — the refinement is then evidence, not the
         * decline's cause.
         */
        ABSENT_COLUMN,
        /**
         * The leaf shape is outside the fold's legacy-parity set (whole-column verdicts, metadata
         * accessors the {@code evaluateMetadataNative} path owns, non-fold-equivalent BOOL calls).
         */
        UNSUPPORTED_SHAPE
    }


    /** Why the fold decided where the static level says row-level. */
    public enum ExceedReason
    {
        /**
         * A combinator carries a statically-decidable operand, so the Kleene fold can decide from
         * its VALUE ({@code any[…, TRUE, …]} / {@code all[…, FALSE, …]}) around row-level operands
         * — value-dependent, unpredictable by any static level.
         */
        SHORT_CIRCUIT,
        /** No decidable combinator operand in sight — a genuine gap in the level model. */
        MODEL_GAP
    }


    /** One fold-site comparison. */
    public record FoldObservation(String ruleId, String dataset, String site, @Nullable Level level,
            BroadcastFold.Verdict verdict, FoldAgreement agreement, @Nullable String reason)
    {
    }


    /** One per-(rule, dataset) effective-granularity derivation (SPEC §8). */
    public record GranularityObservation(String ruleId, String dataset, Sensitivity declared,
            Level derived, EffectiveGranularity effective)
    {
    }


    /** One group execution whose block-computed outputs were not constant over the flagged rows. */
    public record GroupOutputObservation(String ruleId, String dataset, Set<String> nonConstant)
    {

        public GroupOutputObservation
        {
            nonConstant = Set.copyOf(nonConstant);
        }
    }

    /** Monitor for the file writer — one line at a time under parallel dataset execution. */
    private static final Object WRITER_LOCK = new Object();

    private static final AtomicReference<@Nullable Consumer<FoldObservation>> FOLD_OBSERVER = new AtomicReference<>();

    private static final AtomicReference<@Nullable Consumer<GranularityObservation>> GRANULARITY_OBSERVER = new AtomicReference<>();

    private static final AtomicReference<@Nullable Consumer<GroupOutputObservation>> GROUP_OUTPUT_OBSERVER = new AtomicReference<>();

    private static final AtomicReference<@Nullable PrintWriter> WRITER = new AtomicReference<>();

    private static final AtomicBoolean WRITER_FAILED = new AtomicBoolean();

    private LevelInstrument()
    {
    }


    /** Sets (or clears) the fold observer. Test / measurement use only. */
    public static void setFoldObserver(@Nullable Consumer<FoldObservation> observer)
    {
        FOLD_OBSERVER.set(observer);
    }


    /** Sets (or clears) the effective-granularity observer. Test / measurement use only. */
    public static void setGranularityObserver(@Nullable Consumer<GranularityObservation> observer)
    {
        GRANULARITY_OBSERVER.set(observer);
    }


    /** Sets (or clears) the group-output observer. Test / measurement use only. */
    public static void setGroupOutputObserver(@Nullable Consumer<GroupOutputObservation> observer)
    {
        GROUP_OUTPUT_OBSERVER.set(observer);
    }


    /**
     * Whether the instrument is active. When it is not, the entry points return immediately — no
     * typed walk runs and execution is byte-identical to the uninstrumented engine.
     */
    static boolean active()
    {
        return FOLD_OBSERVER.get() != null || GRANULARITY_OBSERVER.get() != null
                || GROUP_OUTPUT_OBSERVER.get() != null || configuredDir() != null
                || WRITER.get() != null;
    }


    private static @Nullable String configuredDir()
    {
        String dir = System.getProperty("corej.level.instrument.dir");
        return dir != null ? dir : System.getenv("COREJ_LEVEL_INSTRUMENT_DIR");
    }


    /** Closes and clears the file writer so a test's directory configuration does not leak. */
    static void resetWriterForTests()
    {
        PrintWriter writer = WRITER.getAndSet(null);
        if (writer != null)
        {
            writer.close();
        }
        WRITER_FAILED.set(false);
    }


    /**
     * Compares the static level of {@code expr} against the runtime fold verdict at one fold site.
     * Called with the verdict the execution path is ABOUT TO ACT ON — the runtime verdict always
     * wins; this method only observes.
     */
    public static void onFold(Rule rule, EvaluationContext ctx, Expr expr, String site,
            BroadcastFold.Verdict verdict)
    {
        if (!active())
        {
            return;
        }
        try
        {
            RecordingResolver resolver = new RecordingResolver(ctx);
            TypedExpr typed = StageAChecker.deriveTyped(rule, expr, resolver,
                    operationInventory(ctx));
            FoldObservation obs = classify(ctx, expr, site, verdict, typed, resolver);
            emit(formatFold(obs));
            Consumer<FoldObservation> observer = FOLD_OBSERVER.get();
            if (observer != null)
            {
                observer.accept(obs);
            }
        }
        catch (RuntimeException ex)
        {
            LOGGER.log(System.Logger.Level.TRACE, "level instrument failed at onFold: {0}",
                    ex.toString());
        }
    }


    /**
     * Derives and observes the effective granularity of one (rule, dataset) — SPEC §8: the declared
     * {@code Sensitivity} is the maximum, the derived level may coarsen it (D39, D39d). Observation
     * only; emission routing is untouched in phase 5.
     */
    public static void onEffectiveGranularity(Rule rule, EvaluationContext ctx, Expr checkExpr)
    {
        if (!active())
        {
            return;
        }
        try
        {
            Sensitivity declared = rule.getSensitivity();
            if (declared == null)
            {
                return;
            }
            TypedExpr typed = StageAChecker.deriveTyped(rule, checkExpr, new RecordingResolver(ctx),
                    operationInventory(ctx));
            if (typed == null)
            {
                return;
            }
            List<String> keys = rule.effectiveGroupingVariables();
            EffectiveGranularity effective = EffectiveGranularity.of(declared,
                    keys == null ? Set.of() : new LinkedHashSet<>(keys), typed.level());
            GranularityObservation obs = new GranularityObservation(ruleIdOf(ctx), datasetOf(ctx),
                    declared, typed.level(), effective);
            emit(formatGranularity(obs));
            Consumer<GranularityObservation> observer = GRANULARITY_OBSERVER.get();
            if (observer != null)
            {
                observer.accept(obs);
            }
        }
        catch (RuntimeException ex)
        {
            LOGGER.log(System.Logger.Level.TRACE,
                    "level instrument failed at onEffectiveGranularity: {0}", ex.toString());
        }
    }


    /**
     * Records a group execution whose block-computed output values (D94c) were NOT constant over
     * the block's flagged rows — the D94a GROUP-VALUE population, rendered with the anchor value
     * until phase 5b's report shape lands. Called only when {@code nonConstant} is non-empty.
     */
    public static void onGroupOutputs(EvaluationContext ctx, Set<String> nonConstant)
    {
        if (!active())
        {
            return;
        }
        try
        {
            GroupOutputObservation obs = new GroupOutputObservation(ruleIdOf(ctx), datasetOf(ctx),
                    nonConstant);
            emit(formatGroupOutputs(obs));
            Consumer<GroupOutputObservation> observer = GROUP_OUTPUT_OBSERVER.get();
            if (observer != null)
            {
                observer.accept(obs);
            }
        }
        catch (RuntimeException ex)
        {
            LOGGER.log(System.Logger.Level.TRACE, "level instrument failed at onGroupOutputs: {0}",
                    ex.toString());
        }
    }

    // ------------------------------------------------------------------
    // Classification
    // ------------------------------------------------------------------


    /**
     * Whether {@code level} predicts the dataset-level fold can decide: granularity at or below
     * {@code dataset}, no per-variable cursor.
     */
    public static boolean datasetDecidable(Level level)
    {
        return level.granularity().rank() <= Granularity.Simple.DATASET.rank()
                && level.cursor() == Cursor.ABSENT;
    }


    private static FoldObservation classify(EvaluationContext ctx, Expr expr, String site,
            BroadcastFold.Verdict verdict, @Nullable TypedExpr typed, RecordingResolver resolver)
    {
        String ruleId = ruleIdOf(ctx);
        String dataset = datasetOf(ctx);
        if (typed == null)
        {
            return new FoldObservation(ruleId, dataset, site, null, verdict,
                    FoldAgreement.UNDERIVABLE, null);
        }
        boolean decidable = datasetDecidable(typed.level());
        boolean decided = verdict != BroadcastFold.Verdict.UNKNOWN;
        FoldAgreement agreement;
        String reason = null;
        if (decidable == decided)
        {
            agreement = decided ? FoldAgreement.AGREE_DECIDED : FoldAgreement.AGREE_UNDECIDED;
        }
        else if (decidable)
        {
            agreement = FoldAgreement.FOLD_DECLINED;
            reason = declineReason(ctx, expr, resolver).name();
        }
        else
        {
            agreement = FoldAgreement.FOLD_EXCEEDED;
            reason = (hasDecidableCombinatorOperand(typed) ? ExceedReason.SHORT_CIRCUIT
                    : ExceedReason.MODEL_GAP).name();
        }
        return new FoldObservation(ruleId, dataset, site, typed.level(), verdict, agreement,
                reason);
    }


    /** First matching decline class; the order is part of the instrument's contract. */
    private static DeclineReason declineReason(EvaluationContext ctx, Expr expr,
            RecordingResolver resolver)
    {
        if (!BroadcastFold.operationRefsSafe(expr, ctx, false))
        {
            return DeclineReason.OPERATION_RUNTIME_KIND;
        }
        Set<MetadataLevel> levels = MetadataExprScan.providerLevelsUsed(expr);
        if ((levels.contains(MetadataLevel.DEFINE) && ctx.getDefineProvider() == null)
                || (levels.contains(MetadataLevel.LIBRARY) && ctx.getLibraryProvider() == null))
        {
            return DeclineReason.PROVIDER_ABSENT;
        }
        if (resolver.absentRefined)
        {
            return DeclineReason.ABSENT_COLUMN;
        }
        return DeclineReason.UNSUPPORTED_SHAPE;
    }


    /**
     * Whether some combinator of the typed tree carries a statically dataset-decidable operand —
     * the structural precondition of a Kleene value short-circuit. Only the combinator spine is
     * walked: the fold short-circuits at {@code and}/{@code or}/{@code not} nodes and nowhere else.
     */
    private static boolean hasDecidableCombinatorOperand(TypedExpr typed)
    {
        if (!(typed.node() instanceof Expr.And || typed.node() instanceof Expr.Or
                || typed.node() instanceof Expr.Not))
        {
            return false;
        }
        for (TypedExpr child : typed.children())
        {
            if (datasetDecidable(child.level()) || hasDecidableCombinatorOperand(child))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * Phase 6, D106d — the instrument's dataset inventory for the {@code $}-binding-level
     * refinement: mirrors the <b>operation</b> resolution semantics, which are EXACT
     * ({@code DatasetResolver.resolve} — operations deliberately do not take the split-domain
     * union, a Fix #358 non-goal), plus the one executor exemption the exactness would misread: the
     * J7 split-SUPP self-reference redirect, where {@code resolveTargetTable} runs the operation
     * against the current table and the binding therefore does <b>not</b> degenerate. Deliberately
     * not {@code RuleRunner.foreignInventory}, whose split-union semantics are the <em>join</em>'s,
     * not the operation's.
     */
    private static net.cumba.corej.core.expr.typed.ForeignDatasetInventory operationInventory(
            EvaluationContext ctx)
    {
        return name ->
        {
            net.cumba.datatable.IDataTable resolved = ctx.getDatasetResolver().resolve(name);
            if (resolved == null)
            {
                String current = ctx.getTable().getMetaData().getName();
                if (current != null)
                {
                    String upper = current.toUpperCase(java.util.Locale.ROOT);
                    if ((upper.startsWith("SUPP") || upper.startsWith("SQAP"))
                            && upper.startsWith(name.toUpperCase(java.util.Locale.ROOT)))
                    {
                        return columnsOf(ctx.getTable());
                    }
                }
                return null;
            }
            return columnsOf(resolved);
        };
    }


    private static Set<String> columnsOf(net.cumba.datatable.IDataTable table)
    {
        var meta = table.getMetaData();
        Set<String> columns = new LinkedHashSet<>();
        for (int i = 0; i < meta.getColumnCount(); i++)
        {
            columns.add(meta.getColumn(i).getName());
        }
        return columns;
    }

    // ------------------------------------------------------------------
    // The bind-time column-level resolver (D39a + the Fix #10 context scalar)
    // ------------------------------------------------------------------

    /**
     * {@link ColumnLevelResolver} over one runtime context, recording whether D39a's absent-column
     * refinement fired (the classifier's {@link DeclineReason#ABSENT_COLUMN} evidence).
     */
    static final class RecordingResolver implements ColumnLevelResolver
    {

        private final EvaluationContext ctx;

        private boolean absentRefined;

        RecordingResolver(EvaluationContext ctx)
        {
            this.ctx = ctx;
        }


        @Override
        public @Nullable Level resolve(Expr.Ref ref)
        {
            if (ref.kind() != OperandKind.COLUMN)
            {
                return null;
            }
            return switch (BroadcastFold.bindColumnLevel(ref.name(), ctx))
            {
            case ROW -> null;
            case DATASET_CONTEXT_SCALAR -> Level.DATASET;
            case DATASET_ABSENT ->
            {
                absentRefined = true;
                yield Level.DATASET;
            }
            };
        }
    }

    // ------------------------------------------------------------------
    // Emission
    // ------------------------------------------------------------------

    private static String ruleIdOf(EvaluationContext ctx)
    {
        String id = ctx.getRuleId();
        return id != null ? id : "?";
    }


    private static String datasetOf(EvaluationContext ctx)
    {
        String name = ctx.getTable().getMetaData().getName();
        if (name != null && !name.isEmpty())
        {
            return name;
        }
        String domain = ctx.getDomainName();
        return domain != null ? domain : "?";
    }


    private static String formatFold(FoldObservation obs)
    {
        return "level-fold agreement=" + obs.agreement()
                + (obs.reason() != null ? " reason=" + obs.reason() : "") + " site=" + obs.site()
                + " rule=" + obs.ruleId() + " dataset=" + obs.dataset() + " level="
                + (obs.level() != null ? "\"" + obs.level().describe() + "\"" : "-") + " verdict="
                + obs.verdict();
    }


    private static String formatGranularity(GranularityObservation obs)
    {
        // D106g: the effective value is the full level — granularity AND cursor — because for
        // the per-variable rules the granularity coarsens while the multiplicity rides the
        // cursor, and "effective granularity" alone under-describes effective emission.
        return "level-granularity rule=" + obs.ruleId() + " dataset=" + obs.dataset() + " declared="
                + obs.declared() + " derived=\"" + obs.derived().describe() + "\" effective=\""
                + obs.effective().granularity().describe() + "\" cursor=" + obs.effective().cursor()
                + " coarsened=" + obs.effective().coarsened();
    }


    private static String formatGroupOutputs(GroupOutputObservation obs)
    {
        return "level-group-output rule=" + obs.ruleId() + " dataset=" + obs.dataset()
                + " nonconstant=" + new java.util.TreeSet<>(obs.nonConstant());
    }


    private static void emit(String line)
    {
        LOGGER.log(System.Logger.Level.DEBUG, "{0}", line);
        PrintWriter writer = writer();
        if (writer != null)
        {
            synchronized (WRITER_LOCK)
            {
                writer.println(line);
                writer.flush();
            }
        }
    }


    private static @Nullable PrintWriter writer()
    {
        PrintWriter existing = WRITER.get();
        if (existing != null || WRITER_FAILED.get())
        {
            return existing;
        }
        String dir = configuredDir();
        if (dir == null)
        {
            return null;
        }
        try
        {
            Path file = Path.of(dir, "level-instrument-" + ProcessHandle.current().pid() + ".log");
            Files.createDirectories(file.getParent());
            PrintWriter fresh = new PrintWriter(Files.newBufferedWriter(file,
                    StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.APPEND));
            if (WRITER.compareAndSet(null, fresh))
            {
                return fresh;
            }
            fresh.close();
            return WRITER.get();
        }
        catch (IOException | RuntimeException ex)
        {
            WRITER_FAILED.set(true);
            LOGGER.log(System.Logger.Level.WARNING,
                    "level instrument cannot open its output file: {0}", ex.toString());
            return null;
        }
    }

}
