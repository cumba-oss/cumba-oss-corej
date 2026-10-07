package net.cumba.corej.ruletest.cdt.ruletest;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import net.cumba.cdisc.define.DefineXmlParser;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.EngineLimits;
import net.cumba.corej.core.exec.JoinCache;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.exec.ScopeVariableSource;
import net.cumba.corej.core.exec.Violation;
import net.cumba.corej.core.gen.TokenExpander;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.metadata.DefineXmlMetadataProvider;
import net.cumba.corej.core.metadata.OdmDefineXMLProvider;
import net.cumba.corej.core.metadata.RuntimeDictionaryProvider;
import net.cumba.corej.core.metadata.VlmResolver;
import net.cumba.corej.core.metadata.store.Presence;
import net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory;
import net.cumba.corej.core.model.Rule;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.merge.MergeStandard;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

/**
 * One rule × one {@code .cdt} scenario, judged exactly as the corpus gate judges it — the harness
 * lifted out of {@code cumba-corej-rules}' {@code AbstractRuleTestSuitesFactory} into main scope
 * ({@code PLAN-rule-authoring-api} phase 3) so the corpus factory and the rule editor run <b>one
 * implementation</b>: the factory maps the result back onto JUnit, the editor shows it.
 *
 * <h2>Three outcomes, not two</h2>
 *
 * <p>
 * The test-tree harness <em>aborted</em> through JUnit {@code Assumptions} and <em>failed</em>
 * through {@code Assertions}; here every one of those is an explicit {@link Outcome}. The mapping
 * (review H1, tabulated as the plan asked). ⚠ The detail is the old assertion's <em>message</em>,
 * not JUnit's rendering of it: no {@code ==> expected …} suffix, no {@code Assumption failed:}
 * prefix; and the three stops the old harness reached through an <em>exception</em>
 * ({@code NO_CARRIER}, {@code SIDECAR}, {@code DICTIONARIES}) were JUnit ERRORs and are
 * {@link Outcome#FAIL}s here — the verdict is the same, a red scenario (pack-2 review 8a-B L6):
 * </p>
 * <table>
 * <caption>JUnit state of the old harness → outcome of the runner</caption>
 * <tr>
 * <th>old site</th>
 * <th>condition</th>
 * <th>now</th>
 * </tr>
 * <tr>
 * <td>{@code runScenario} assertNotNull(primary)</td>
 * <td>{@code domain=} names no dataset of the file</td>
 * <td>{@link Outcome#FAIL} {@code NO_PRIMARY}</td>
 * </tr>
 * <tr>
 * <td>scope pre-gate</td>
 * <td>out of scope with any expectation but {@code expect=skipped} ({@code violation},
 * {@code noViolation}, {@code executionError}, and any future verdict)</td>
 * <td>{@link Outcome#FAIL} {@code SCOPE}; {@code expect=skipped} {@link Outcome#PASS} with
 * {@link ScopeGate#inScope()} false ({@code PLAN-scenario-scope-gate-execution-error} C1: the rule
 * never runs on the dataset, so it can neither fire nor error)</td>
 * </tr>
 * <tr>
 * <td>{@code ScenarioLibrarySupport.resolveOrSkip} assertFalse(fatal)</td>
 * <td>{@code #library-ref} and the configured store is of another format</td>
 * <td>{@link Outcome#FAIL} {@code STORE}</td>
 * </tr>
 * <tr>
 * <td>… assumeTrue(factory != null) / assumeTrue(resolved)</td>
 * <td>no store, or the store does not serve the product</td>
 * <td>{@link Outcome#ABORTED} {@code NO_STORE} / {@code PRODUCT_NOT_SERVED}</td>
 * </tr>
 * <tr>
 * <td>… requirePinnedCtPackages assertEquals(PRESENT)</td>
 * <td>a pinned {@code ct=} package the store lacks</td>
 * <td>{@link Outcome#FAIL} {@code CT_PACKAGE}</td>
 * </tr>
 * <tr>
 * <td>{@code ScenarioDeclaredScopeProvider.install} IllegalStateException</td>
 * <td>an ADaM axis declared with no carrier block</td>
 * <td>{@link Outcome#FAIL} {@code NO_CARRIER}</td>
 * </tr>
 * <tr>
 * <td>{@code parseDefineXml} UncheckedIOException (now the resolver's refusal, or the parse
 * failure)</td>
 * <td>the {@code #define-xml} sidecar cannot be read or parsed</td>
 * <td>{@link Outcome#FAIL} {@code SIDECAR}</td>
 * </tr>
 * <tr>
 * <td>{@code resolveDictionaries} UncheckedIOException</td>
 * <td>the dummy dictionary bundle cannot be loaded</td>
 * <td>{@link Outcome#FAIL} {@code DICTIONARIES}</td>
 * </tr>
 * <tr>
 * <td>{@code #library-ref} rescue in {@code judge}</td>
 * <td>{@code #library-ref}, {@code expect≠skipped}, the rule neither executed nor errored (a
 * degraded Library ⇒ SKIPPED); an ERRORED run is judged by {@code ExecutionVerdictCheck}
 * ({@code PLAN-scenario-scope-gate-execution-error} C6)</td>
 * <td>{@link Outcome#ABORTED} {@code REF_NOT_EXECUTED}</td>
 * </tr>
 * <tr>
 * <td>{@code ExecutionVerdictCheck} / {@code ViolationLocationCheck} assertTrue</td>
 * <td>the verdict or a location assertion fails</td>
 * <td>{@link Outcome#FAIL} {@code VERDICT} / {@code LOCATION}</td>
 * </tr>
 * <tr>
 * <td>— (editor entry only, {@link #runText})</td>
 * <td>the scenario text does not parse, or names a sidecar the tree refuses</td>
 * <td>{@link Outcome#FAIL} {@code PARSE}</td>
 * </tr>
 * <tr>
 * <td>— (editor entries only, {@link #run} / {@link #runText})</td>
 * <td>a store, the expander or the engine throws</td>
 * <td>{@link Outcome#FAIL} {@code INTERNAL}, a generic message; the cause goes to the log</td>
 * </tr>
 * </table>
 *
 * <p>
 * ⛔ <b>Sidecars are confined</b> ({@link SidecarResolver}): {@code #define-xml} resolves through
 * {@link RunEnvironment#sidecars()} here, and {@code #library-include} / {@code #define-include},
 * which the parser resolves, are confined by {@link #runText} — the entry a host holding scenario
 * <em>text</em> uses, so the confinement is the runner's and never left to its caller (pack-2
 * review 8a-B M2). A caller that parses for itself passes the same resolver's
 * {@link SidecarResolver#includeResolver}, as the corpus factory does.
 * </p>
 *
 * <p>
 * The rule to run is the caller's: the corpus resolves the declared standard's view through its
 * family packages ({@link ScenarioRuleSelector}), the editor assembles it from the buffer. The
 * declared-standard <em>membership</em> check and the location back-fill mode stay in the factory —
 * both are about the corpus, not about running a scenario.
 * </p>
 */
public final class ScenarioRunner
{

    private static final System.Logger LOGGER = System.getLogger(ScenarioRunner.class.getName());

    /**
     * Loaded once per directory: the dummy dictionary bundle a scenario names. ⚠ Never evicted, by
     * design — a host keeps one bundle directory for its lifetime (the corpus its
     * {@code DUMMY_DICTIONARIES_DIR}); a host that rewrites the bundle in place at runtime keeps
     * the first load of that path until it restarts (pack-2 review 8a-B L7).
     */
    private static final ConcurrentHashMap<Path, RuntimeDictionaryProvider> DICTIONARIES = new ConcurrentHashMap<>();

    private ScenarioRunner()
    {
    }

    // ---- results --------------------------------------------------------------------------

    /** How a run ended. */
    public enum Outcome
    {
        /** The scenario's declaration holds (or the scope gate kept the rule from running). */
        PASS,
        /** The scenario's declaration does not hold, or the scenario cannot be run as written. */
        FAIL,
        /** The scenario cannot be judged here (no store, product not served, rule degraded). */
        ABORTED
    }


    /**
     * The scope pre-gate's verdict.
     *
     * @param inScope
     *            whether the primary dataset is in the rule's scope
     * @param mismatch
     *            the failing axis, or {@code null}
     */
    public record ScopeGate(boolean inScope, @Nullable String mismatch)
    {

        /** In scope. */
        public static final ScopeGate IN = new ScopeGate(true, null);
    }


    /**
     * The engine's outcome on a scenario, every expansion folded.
     *
     * @param violated
     *            at least one violation
     * @param executed
     *            the rule ran — neither SKIPPED nor ERROR
     * @param errored
     *            it did not run <em>because it failed</em>. Kept apart from {@code executed}
     *            because {@code expect=skipped} asserts a <b>deliberate</b> non-execution: an ERROR
     *            must fail such a scenario, not satisfy it, or a rule broken into unconditional
     *            ERROR would keep every skip fixture green (review F1)
     * @param violations
     *            every violation, across expansions
     * @param totalCount
     *            the engine's violation count (may exceed the list when truncated)
     * @param truncated
     *            whether the engine capped the list
     * @param notExecutedReason
     *            {@code status + ": " + statusMessage} when the rule did not execute, else
     *            {@code null}
     */
    public record EvalOutcome(boolean violated, boolean executed, boolean errored,
            List<Violation> violations, long totalCount, boolean truncated,
            @Nullable String notExecutedReason)
    {

        public EvalOutcome
        {
            violations = List.copyOf(violations);
        }
    }


    /**
     * Why a rule did not execute, with a kind an editor can switch on.
     *
     * @param kind
     *            the kind
     * @param text
     *            the engine's (or the gate's) own words
     */
    public record SkipReason(Kind kind, String text)
    {

        /** The kinds, derived from the engine's status messages and the scope gate. */
        public enum Kind
        {
            /** The scope pre-gate kept the rule from running. */
            SCOPE,
            /** {@code Requirements.Variables} / {@code Requirements.Datasets} not met. */
            REQUIREMENTS,
            /** No CDISC Library metadata, or the Library could not be consulted. */
            LIBRARY_UNAVAILABLE,
            /** No Define-XML metadata. */
            DEFINE_UNAVAILABLE,
            /** No external dictionary. */
            DICTIONARY_UNAVAILABLE,
            /** A provider answered no data for what the rule asked. */
            NO_DATA,
            /** Every declared check level is below the run threshold, or none evaluable. */
            LEVEL,
            /** The precondition was not met. */
            PRECONDITION,
            /** A wildcard or token template matched no column / dataset. */
            EXPANSION,
            /** The rule errored. */
            ERROR,
            /** Any other engine skip. */
            OTHER
        }

        static SkipReason of(Kind aKind, String aText)
        {
            return new SkipReason(aKind, aText);
        }


        /** Classify an engine status text ({@code "SKIPPED: Rule skipped — …"}). */
        static SkipReason classify(String aText, boolean aErrored)
        {
            String t = aText.toLowerCase(Locale.ROOT);
            Kind kind;
            if (aErrored || t.startsWith("error"))
            {
                kind = Kind.ERROR;
            }
            else if (t.contains("no library access") || t.contains("cdisc library could not be"))
            {
                kind = Kind.LIBRARY_UNAVAILABLE;
            }
            else if (t.contains("no define-xml metadata"))
            {
                kind = Kind.DEFINE_UNAVAILABLE;
            }
            else if (t.contains("external dictionar") && !t.contains("returned no data"))
            {
                kind = Kind.DICTIONARY_UNAVAILABLE;
            }
            else if (t.contains("returned no data"))
            {
                kind = Kind.NO_DATA;
            }
            else if (t.contains("severity threshold") || t.contains("no declared check level"))
            {
                kind = Kind.LEVEL;
            }
            else if (t.contains("precondition not met"))
            {
                kind = Kind.PRECONDITION;
            }
            else if (t.contains("requirements") || t.contains("required variable")
                    || t.contains("required dataset") || t.contains("variables."))
            {
                kind = Kind.REQUIREMENTS;
            }
            else if (t.contains("no column") || t.contains("not among the loaded datasets")
                    || t.contains("expansion"))
            {
                kind = Kind.EXPANSION;
            }
            else
            {
                kind = Kind.OTHER;
            }
            return new SkipReason(kind, aText);
        }
    }


    /**
     * Everything that happened before the verdict: the scope gate, the engine's outcome (when the
     * rule ran or was skipped by the engine), the raw results, or the reason nothing could run.
     *
     * @param scopeGate
     *            the pre-gate's verdict
     * @param outcome
     *            the folded engine outcome, or {@code null} when the gate kept the rule from
     *            running or the run stopped before execution
     * @param results
     *            the engine's results, one per concrete rule
     * @param stop
     *            why the run stopped before execution ({@code FAIL} or {@code ABORTED}), or
     *            {@code null}
     */
    public record ScenarioEvaluation(ScopeGate scopeGate, @Nullable EvalOutcome outcome,
            List<RuleExecutionResult> results, @Nullable Stop stop)
    {

        public ScenarioEvaluation
        {
            results = List.copyOf(results);
        }
    }


    /**
     * A stop before execution.
     *
     * @param outcome
     *            {@link Outcome#FAIL} or {@link Outcome#ABORTED}
     * @param code
     *            the mapping table's code
     * @param detail
     *            the message
     */
    public record Stop(Outcome outcome, String code, String detail)
    {
    }


    /**
     * The judged run.
     *
     * @param outcome
     *            how it ended
     * @param code
     *            {@code OK}, or the mapping table's code of the failure / abort
     * @param detail
     *            the old assertion's message (see the class javadoc: without JUnit's decoration)
     * @param scopeGate
     *            the pre-gate
     * @param evaluation
     *            the engine outcome, or {@code null} before execution
     * @param results
     *            the engine's results
     * @param skip
     *            why the rule did not execute, or {@code null} when it did
     * @param verdict
     *            the execution-state check's result, or {@code null} when not reached
     * @param locations
     *            the location check's result, or {@code null} when not reached
     */
    public record ScenarioRunResult(Outcome outcome, String code, String detail,
            ScopeGate scopeGate, @Nullable EvalOutcome evaluation,
            List<RuleExecutionResult> results, @Nullable SkipReason skip,
            ExecutionVerdictCheck.@Nullable Result verdict,
            ViolationLocationCheck.@Nullable Result locations)
    {

        public ScenarioRunResult
        {
            results = List.copyOf(results);
        }


        /** True for {@link Outcome#PASS}. */
        public boolean passed()
        {
            return outcome == Outcome.PASS;
        }
    }

    // ---- the run ----------------------------------------------------------------------------

    /**
     * Run and judge: {@link #evaluate} then {@link #judge}. Never throws for what the run raises —
     * a store, the expander or the engine failing is a {@link Outcome#FAIL} {@code INTERNAL} with a
     * generic message, the cause logged (the editor host's contract; pack-2 review 8a-B L7).
     *
     * @param aRule
     *            the rule to run (the scenario's declared view)
     * @param aScenario
     *            the parsed scenario
     * @param aScenarioFile
     *            the scenario's file, the base its sidecars are relative to; {@code null} for a
     *            scenario that names none
     * @param aEnvironment
     *            the host's store, dictionaries and sidecar resolver
     * @param aObserver
     *            sees every execution, or {@code null}
     * @return the judged run
     */
    public static ScenarioRunResult run(Rule aRule, RuleTestScenario aScenario,
            @Nullable Path aScenarioFile, RunEnvironment aEnvironment,
            @Nullable ExecutionObserver aObserver)
    {
        try
        {
            return runUnguarded(aRule, aScenario, aScenarioFile, aEnvironment, aObserver);
        }
        catch (RuntimeException e)
        {
            return internal(aScenario.getCoreId(), e);
        }
    }


    /**
     * Parse and run a scenario held as <em>text</em> — the editor's entry: its
     * {@code #library-include} / {@code #define-include} sidecars resolve through the environment's
     * confined {@link SidecarResolver} (no resolver: an include is a parse error), and so does its
     * {@code #define-xml}. A scenario that does not parse is a {@link Outcome#FAIL} {@code PARSE};
     * messages name the scenario relative to the sidecar tree, never by an absolute path.
     *
     * @param aRule
     *            the rule to run
     * @param aCdtText
     *            the scenario's {@code .cdt} text
     * @param aScenarioFile
     *            the file the scenario is (or would be saved as) — the base its sidecars are
     *            relative to; its directory must exist inside the sidecar tree
     * @param aEnvironment
     *            the host's store, dictionaries and sidecar resolver
     * @param aObserver
     *            sees every execution, or {@code null}
     * @return the judged run
     */
    public static ScenarioRunResult runText(Rule aRule, String aCdtText, Path aScenarioFile,
            RunEnvironment aEnvironment, @Nullable ExecutionObserver aObserver)
    {
        SidecarResolver sidecars = aEnvironment.sidecars();
        String label = sidecars == null ? String.valueOf(aScenarioFile.getFileName())
                : sidecars.label(aScenarioFile);
        RuleTestScenario scenario;
        try
        {
            scenario = RuleTestCdt.parse(aCdtText, label,
                    sidecars == null ? null : sidecars.includeResolver(aScenarioFile));
        }
        catch (RuleTestCdtException e)
        {
            return stopped(new Stop(Outcome.FAIL, "PARSE", String.valueOf(e.getMessage())),
                    ScopeGate.IN);
        }
        catch (RuntimeException e)
        {
            return internal(label, e);
        }
        return run(aRule, scenario, aScenarioFile, aEnvironment, aObserver);
    }


    /** A FAIL {@code INTERNAL}: a generic message for the reader, the cause for the log. */
    private static ScenarioRunResult internal(String aWhat, RuntimeException aCause)
    {
        LOGGER.log(System.Logger.Level.WARNING, "scenario " + aWhat + " could not be run", aCause);
        return stopped(new Stop(Outcome.FAIL, "INTERNAL",
                "the scenario " + aWhat + " could not be run: an internal error ("
                        + aCause.getClass().getSimpleName() + ") — the host's log has the cause"),
                ScopeGate.IN);
    }


    private static ScenarioRunResult runUnguarded(Rule aRule, RuleTestScenario aScenario,
            @Nullable Path aScenarioFile, RunEnvironment aEnvironment,
            @Nullable ExecutionObserver aObserver)
    {
        OverlayDataTable primary = aScenario.primaryTable();
        if (primary == null)
        {
            return stopped(new Stop(Outcome.FAIL, "NO_PRIMARY",
                    "Scenario " + aScenario.getCoreId() + " declares domain="
                            + aScenario.getDomain()
                            + " but no such dataset is present in the file"),
                    ScopeGate.IN);
        }
        ScenarioEvaluation evaluation = evaluate(aRule, aScenario, primary, aScenarioFile,
                aEnvironment, aObserver);
        return judge(aScenario, evaluation, primary);
    }


    /**
     * Everything between resolving the rule and judging the verdict: the scope pre-gate, the
     * metadata axes and the execution itself — for the rule handed in, which need not be the one
     * the corpus packages resolve (the respelling differential runs a base text and a respelled
     * text through this very method).
     *
     * @param aRule
     *            the rule
     * @param aScenario
     *            the scenario
     * @param aPrimary
     *            its primary dataset
     * @param aScenarioFile
     *            its file, or {@code null}
     * @param aEnvironment
     *            the environment
     * @param aObserver
     *            the observer, or {@code null}
     * @return the evaluation
     */
    public static ScenarioEvaluation evaluate(Rule aRule, RuleTestScenario aScenario,
            OverlayDataTable aPrimary, @Nullable Path aScenarioFile, RunEnvironment aEnvironment,
            @Nullable ExecutionObserver aObserver)
    {
        // Production enforces the Domains/Classes scope axes upstream of RuleRunner
        // (DatasetRuleResolver -> ScopeMatcher), so mirror that gate here: an out-of-scope primary
        // dataset means the rule never runs -> "executed, zero violations". This makes Scope fixes
        // testable (an expect=noViolation scenario on out-of-scope data). The class axis is only
        // judged when the scenario declares a dataset class (`dataset NAME class=...`).
        String scopeMismatch = ScenarioScopeGate.describeScopeMismatch(aRule, aPrimary);
        if (scopeMismatch != null)
        {
            // Review F2: an out-of-scope primary is a NON-EXECUTION, exactly like a
            // Requirements.Variables skip — in production DatasetRuleResolver never even emits
            // the rule for this dataset. It must therefore be declared, not silently accepted: an
            // `expect=noViolation` scenario here passes while exercising nothing, which is the
            // same rot the verdict switch exists to prevent. `expect=skipped` is the honest
            // contract.
            // PLAN-scenario-scope-gate-execution-error C1: the guard is inverted — every
            // expectation but `skipped` fails here, so expect=executionError (the rule cannot
            // error on data it is never given) and any future verdict cannot pass silently.
            ScopeGate gate = new ScopeGate(false, scopeMismatch);
            if (aScenario.getExpect() != RuleTestScenario.Verdict.SKIPPED)
            {
                return new ScenarioEvaluation(gate, null, List.of(),
                        new Stop(Outcome.FAIL, "SCOPE", scopeFailure(aScenario, scopeMismatch)));
            }
            return new ScenarioEvaluation(gate, null, List.of(), null);
        }

        DatasetResolver.WithInventory resolver = aScenario.resolver();
        // Inline provider for normal scenarios; a real CDISC-Library provider for #library-ref
        // scenarios (aborts when no Library is configured).
        LibraryResolution libraryResolution = resolveLibrary(aScenario, aEnvironment.store());
        if (libraryResolution.stop() != null)
        {
            return new ScenarioEvaluation(ScopeGate.IN, null, List.of(), libraryResolution.stop());
        }
        MetadataProvider library = libraryResolution.provider();

        // Define axis: a #define-xml sidecar yields the production wiring (define provider + VLM
        // resolver from the same parsed model); else the synthetic map-backed #define double.
        MetadataProvider defineProvider = aScenario.getDefine();
        VlmResolver vlm = null;
        if (aScenario.getDefineXml() != null)
        {
            OdmDefineXMLProvider parsed;
            try
            {
                parsed = parseDefineXml(aScenarioFile, aScenario.getDefineXml(),
                        aEnvironment.sidecars());
            }
            catch (IOException | UncheckedIOException e)
            {
                return new ScenarioEvaluation(ScopeGate.IN, null, List.of(), new Stop(Outcome.FAIL,
                        "SIDECAR", e.getMessage() == null ? e.toString() : e.getMessage()));
            }
            defineProvider = new DefineXmlMetadataProvider(parsed, null);
            vlm = VlmResolver.from(parsed.metaDataVersion());
        }

        // Declared ADaM scope axis (structure= / subclass=). RuleRunner re-applies the
        // Data_Structures / Subclasses gates at execution time and reads the DECLARED tier from
        // `defineProvider != null ? defineProvider : libraryProvider` — it knows nothing about the
        // two table-metadata keys CdtLoader stores. Route the declaration into whichever of those
        // two slots RuleRunner will consult, so the pre-gate above and the engine see the same
        // declared value. A scenario that declares an axis with neither block is refused here (a
        // decorator over an empty slot would switch that level's availability self-skips off) —
        // see ScenarioDeclaredScopeProvider for the full argument.
        ScenarioDeclaredScopeProvider.Slots slots;
        try
        {
            slots = ScenarioDeclaredScopeProvider.install(aPrimary, library, defineProvider,
                    aScenario.getSource(), ScenarioScopeGate.readsDeclaredAdamTier(aRule));
        }
        catch (ScenarioDeclaredScopeProvider.MissingCarrierException e)
        {
            return new ScenarioEvaluation(ScopeGate.IN, null, List.of(),
                    new Stop(Outcome.FAIL, "NO_CARRIER", String.valueOf(e.getMessage())));
        }
        library = slots.library();
        defineProvider = slots.define();

        RuntimeDictionaryProvider dictionaries;
        try
        {
            dictionaries = resolveDictionaries(aScenario, aEnvironment.dictionariesDir());
        }
        catch (UncheckedIOException | IllegalStateException e)
        {
            return new ScenarioEvaluation(ScopeGate.IN, null, List.of(),
                    new Stop(Outcome.FAIL, "DICTIONARIES", String.valueOf(e.getMessage())));
        }

        List<RuleExecutionResult> results = new ArrayList<>();
        EvalOutcome outcome = evaluate(aRule, aPrimary, resolver, aScenario.getDomain(), library,
                defineProvider, dictionaries, vlm, aScenario.getRunLevel(), aObserver, results);
        return new ScenarioEvaluation(ScopeGate.IN, outcome, results, null);
    }


    /** The {@code SCOPE} failure text for an out-of-scope primary, per declared verdict. */
    private static String scopeFailure(RuleTestScenario aScenario, String aScopeMismatch)
    {
        return switch (aScenario.getExpect())
        {
        case VIOLATION -> aScenario.getCoreId()
                + " expected a violation but the primary dataset is outside the rule's scope: "
                + aScopeMismatch;
        case NO_VIOLATION -> aScenario.getCoreId()
                + " declares expect=noViolation but the rule never runs on this dataset: "
                + aScopeMismatch + " — the scenario is not exercising the rule; declare"
                + " 'expect=skipped', which is what being out of scope means.";
        case EXECUTION_ERROR -> aScenario.getCoreId()
                + " declares expect=executionError but the rule never runs on this dataset: "
                + aScopeMismatch + " — the rule cannot error on data it is never given; declare"
                + " 'expect=skipped', or give the fixture a primary dataset the rule's scope"
                + " admits.";
        default -> aScenario.getCoreId() + " declares expect=" + aScenario.getExpect()
                + " but the rule never runs on this dataset: " + aScopeMismatch
                + " — declare 'expect=skipped', which is what being out of scope means.";
        };
    }


    /**
     * The verdict half: the scenario's declared execution state and its optional location
     * assertions, judged against an evaluation.
     *
     * @param aScenario
     *            the scenario
     * @param aEvaluation
     *            the evaluation
     * @param aPrimary
     *            the primary dataset (the location check reads it)
     * @return the judged run
     */
    public static ScenarioRunResult judge(RuleTestScenario aScenario,
            ScenarioEvaluation aEvaluation, OverlayDataTable aPrimary)
    {
        if (aEvaluation.stop() != null)
        {
            return stopped(aEvaluation.stop(), aEvaluation.scopeGate());
        }
        EvalOutcome outcome = aEvaluation.outcome();
        if (outcome == null)
        {
            // out of scope with expect=skipped, the only expectation the gate accepts (C1):
            // passes without running
            return new ScenarioRunResult(Outcome.PASS, "OK",
                    "out of scope: " + aEvaluation.scopeGate().mismatch(), aEvaluation.scopeGate(),
                    null, List.of(), SkipReason.of(SkipReason.Kind.SCOPE,
                            String.valueOf(aEvaluation.scopeGate().mismatch())),
                    null, null);
        }
        SkipReason skip = outcome.executed() ? null
                : SkipReason.classify(String.valueOf(outcome.notExecutedReason()),
                        outcome.errored());

        // #library-ref only: a configured-but-degraded Library leaves every (sub-)rule SKIPPED —
        // abort rather than fail the verdict assertion.
        // Review F6: a scenario that DECLARES expect=skipped must be asserted, not aborted. The
        // rescue exists for a #library-ref scenario degraded by a missing Library; applying it to
        // a deliberate skip would silently abort the very contract under test.
        // PLAN-scenario-scope-gate-execution-error C6: an ERRORED run is not a degraded Library —
        // it is judged below (ExecutionVerdictCheck), so an ERROR shows red, never as a skip, and
        // an expect=executionError #library-ref scenario can pass.
        if (aScenario.getExpect() != RuleTestScenario.Verdict.SKIPPED
                && aScenario.getLibraryRef() != null && !outcome.executed() && !outcome.errored())
        {
            return new ScenarioRunResult(Outcome.ABORTED, "REF_NOT_EXECUTED",
                    "metadata store available but rule not executable — skipping "
                            + aScenario.getCoreId(),
                    aEvaluation.scopeGate(), outcome, aEvaluation.results(), skip, null, null);
        }

        // Assert the observed EXECUTION STATE against the declared one before judging violations.
        // A SKIPPED / ERROR rule reports zero violations, so without this an `expect=noViolation`
        // scenario passes whether or not it exercised the rule at all — which is how a fixture
        // rots: tighten a rule's scope, or trim away the column that scope needs, and the scenario
        // keeps passing while testing nothing. `expect=skipped` is the positive form.
        // ⚠ notExecutedReason is `status + ": " + statusMessage`, so the engine's own status word
        // rides along in the detail. Deliberate: in a failure message that word is the first
        // thing the reader needs.
        ExecutionVerdictCheck.Result verdict = ExecutionVerdictCheck.verify(aScenario,
                new ExecutionVerdictCheck.Outcome(outcome.executed(), outcome.errored(),
                        outcome.violated(), outcome.totalCount(), outcome.notExecutedReason()));
        if (!verdict.pass())
        {
            return new ScenarioRunResult(Outcome.FAIL, "VERDICT", verdict.detail(),
                    aEvaluation.scopeGate(), outcome, aEvaluation.results(), skip, verdict, null);
        }

        // Optional location assertions (#expectViolationCount / #expectViolationAt); a no-op for
        // verdict-only scenarios. Violations are aggregated across wildcard expansions.
        ViolationLocationCheck.Result loc = ViolationLocationCheck.verify(aScenario,
                outcome.violations(), outcome.totalCount(), outcome.truncated(), aPrimary);
        if (!loc.pass())
        {
            return new ScenarioRunResult(Outcome.FAIL, "LOCATION", loc.detail(),
                    aEvaluation.scopeGate(), outcome, aEvaluation.results(), skip, verdict, loc);
        }
        return new ScenarioRunResult(Outcome.PASS, "OK", verdict.detail(), aEvaluation.scopeGate(),
                outcome, aEvaluation.results(), skip, verdict, loc);
    }


    private static ScenarioRunResult stopped(Stop aStop, ScopeGate aGate)
    {
        return new ScenarioRunResult(aStop.outcome(), aStop.code(), aStop.detail(), aGate, null,
                List.of(), null, null, null);
    }

    // ---- the library axis -------------------------------------------------------------------

    /**
     * The library provider a scenario runs with, or why the run must stop.
     *
     * @param provider
     *            the inline provider (possibly {@code null}), or the store-backed one
     * @param stop
     *            the stop, or {@code null}
     */
    public record LibraryResolution(@Nullable MetadataProvider provider, @Nullable Stop stop)
    {
    }

    /**
     * Resolve the {@link MetadataProvider} for a scenario.
     *
     * <ul>
     * <li>Non-ref scenario → the inline provider (possibly {@code null}); unchanged behaviour.</li>
     * <li>{@code #library-ref} scenario → a real provider over our unified metadata store. The run
     * is <em>aborted</em> when there is no store, the store is unreadable, or it does not serve the
     * referenced product. It <b>fails</b> when the configured store is of another format version,
     * and when the ref pins a {@code ct=} package the store does not hold — the resolver would
     * substitute an empty package and a scenario could pass against no terms at all. The product is
     * checked <em>before</em> the pinned CT, so a store that lacks the product aborts rather than
     * fails (README-CDT, skip gate 1; LAC review round 2, L-4).</li>
     * </ul>
     *
     * @param aScenario
     *            the scenario
     * @param aStore
     *            the host's store availability
     * @return the resolution
     */
    public static LibraryResolution resolveLibrary(RuleTestScenario aScenario,
            RunEnvironment.StoreAvailability aStore)
    {
        LibraryRef ref = aScenario.getLibraryRef();
        if (ref == null)
        {
            return new LibraryResolution(aScenario.getLibrary(), null);
        }
        StoreMetadataProviderFactory factory = aStore.factory();
        if (aStore.fatal())
        {
            return new LibraryResolution(null, new Stop(Outcome.FAIL, "STORE",
                    aStore.unavailableReason() + " — failing " + aScenario.getCoreId()));
        }
        if (factory == null)
        {
            return new LibraryResolution(null, new Stop(Outcome.ABORTED, "NO_STORE",
                    aStore.unavailableReason() + " — skipping " + aScenario.getCoreId()));
        }
        Optional<MetadataProvider> resolved = ScenarioLibraryResolver.resolve(ref, factory);
        if (resolved.isEmpty())
        {
            return new LibraryResolution(null,
                    new Stop(Outcome.ABORTED, "PRODUCT_NOT_SERVED",
                            aStore.origin() + " does not serve " + ref.getStandard() + " "
                                    + ref.getVersion() + " (product not in the store) — skipping "
                                    + aScenario.getCoreId()));
        }
        for (String id : ref.getCtPackages())
        {
            Presence presence = factory.presence(id);
            if (presence != Presence.PRESENT)
            {
                return new LibraryResolution(null, new Stop(Outcome.FAIL, "CT_PACKAGE",
                        aScenario.getCoreId() + " pins ct=" + id + " but " + aStore.origin()
                                + " answers " + presence + " for it — the rule would run against"
                                + " an empty package and could pass vacuously. Re-seed the store"
                                + " or re-pin the scenario."));
            }
        }
        return new LibraryResolution(resolved.get(), null);
    }

    // ---- execution ----------------------------------------------------------------------------


    /**
     * Evaluates a rule against the scenario's primary dataset. {@code wildcards} template rules are
     * expanded against the present columns ({@link WildcardExpander}) and every concrete expansion
     * is executed; results aggregate across expansions.
     *
     * @param aRule
     *            the rule
     * @param aPrimary
     *            the primary dataset
     * @param aResolver
     *            the scenario's dataset resolver
     * @param aDomain
     *            the scenario's domain
     * @param aLibrary
     *            the library provider, or {@code null}
     * @param aDefine
     *            the define provider, or {@code null}
     * @param aDictionaries
     *            the dictionary provider, or {@code null}
     * @param aVlm
     *            the VLM resolver, or {@code null}
     * @param aRunLevel
     *            the scenario's {@code #runLevel}, or {@code null} for the engine default
     * @param aObserver
     *            the observer, or {@code null}
     * @param aResults
     *            receives every engine result
     * @return the folded outcome
     */
    public static EvalOutcome evaluate(Rule aRule, OverlayDataTable aPrimary,
            DatasetResolver.WithInventory aResolver, String aDomain,
            @Nullable MetadataProvider aLibrary, @Nullable MetadataProvider aDefine,
            @Nullable RuntimeDictionaryProvider aDictionaries, @Nullable VlmResolver aVlm,
            @Nullable Severity aRunLevel, @Nullable ExecutionObserver aObserver,
            List<RuleExecutionResult> aResults)
    {
        // nativeEval=true runs the native expression engine — the engine production uses
        // (StudyValidationParams / LibraryValidator both default nativeEval=true; the legacy
        // engine has been removed). Mirrors AbstractSdtmRuleTest.execute.
        // Fix #147: the three-arg entry point, so a declared-token `Expansion:` template expands
        // here exactly as it does in DatasetRuleResolver. With the two-arg form an
        // Expansion-bearing rule falls through as NotApplicable and the scenario silently executes
        // the TEMPLATE — testing a column literally named '&VAR&', which no fixture can have.
        TokenExpander.Context expansionContext = new TokenExpander.Context(
                ScopeVariableSource.of(aResolver, aPrimary), aLibrary, aDomain);
        return switch (WildcardExpander.tryExpand(aRule, aPrimary.getMetaData(), expansionContext))
        {
        case WildcardExpander.ExpansionResult.NotApplicable _ ->
        {
            RuleExecutionResult r = execute(aRule, aPrimary, aResolver, aDomain, aLibrary, aDefine,
                    aDictionaries, aVlm, aRunLevel, aObserver);
            aResults.add(r);
            List<Violation> v = r.getViolations() != null ? r.getViolations() : List.of();
            boolean ran = !(r.isSkipped() || r.isError());
            yield new EvalOutcome(r.hasViolations(), ran, r.isError(), v, r.getViolationCount(),
                    r.isTruncated(), ran ? null : r.getStatus() + ": " + r.getStatusMessage());
        }
        // No matching column: the rule does not apply to this fixture, so nothing was exercised.
        // Reported as not-executed so an expect=noViolation scenario cannot pass vacuously when
        // its fixture lost every wildcard-matching column — declare expect=skipped if the
        // non-applicability IS the contract under test (same discipline as the scope gate).
        // Fix #147 — carry the expander's own reason through. A declared-token template states
        // exactly why it could not bind ("dataset 'ADSL' is not among the loaded datasets"),
        // and that sentence is the whole point of the mechanism: never a silent zero-expansion.
        case WildcardExpander.ExpansionResult.NoMatch(String reason) -> new EvalOutcome(false,
                false, false, List.of(), 0, false, "SKIPPED: " + reason);
        // A partially dropped expansion (a token surviving substitution, a join key the fixture
        // lacks) is the resolver's audit concern; the scenario runner executes what survived.
        case WildcardExpander.ExpansionResult.Expanded(List<Rule> concrete, _) ->
        {
            boolean violated = false;
            boolean executed = false;
            boolean errored = false;
            String notExecuted = null;
            List<Violation> all = new ArrayList<>();
            long total = 0;
            boolean truncated = false;
            for (Rule r : concrete)
            {
                RuleExecutionResult res = execute(r, aPrimary, aResolver, aDomain, aLibrary,
                        aDefine, aDictionaries, aVlm, aRunLevel, aObserver);
                aResults.add(res);
                if (!(res.isSkipped() || res.isError()))
                {
                    executed = true;
                }
                else
                {
                    // Any errored expansion taints the set: expect=skipped must not be satisfied
                    // by a rule that is broken for some tuples (review F1).
                    errored |= res.isError();
                    if (notExecuted == null)
                    {
                        // First non-executing expansion explains the set when NONE executes.
                        notExecuted = res.getStatus() + ": " + res.getStatusMessage();
                    }
                }
                if (res.hasViolations())
                {
                    violated = true;
                }
                if (res.getViolations() != null)
                {
                    all.addAll(res.getViolations());
                }
                total += res.getViolationCount();
                truncated |= res.isTruncated();
            }
            yield new EvalOutcome(violated, executed, errored, all, total, truncated,
                    executed ? null : notExecuted);
        }
        };
    }


    private static RuleExecutionResult execute(Rule aRule, OverlayDataTable aPrimary,
            DatasetResolver.WithInventory aResolver, String aDomain,
            @Nullable MetadataProvider aLibrary, @Nullable MetadataProvider aDefine,
            @Nullable RuntimeDictionaryProvider aDictionaries, @Nullable VlmResolver aVlm,
            @Nullable Severity aRunLevel, @Nullable ExecutionObserver aObserver)
    {
        // Plan C §3.4: the scenario's `#runLevel`, or the engine default (Warning) when it
        // declares none — so every scenario in this suite that says nothing is a
        // default-threshold baseline, exactly as it was before per-level Checks existed.
        // The scenario's `#library standard=` (or `#library-ref`) is the run standard its SUPP--
        // and RELREC merges are read under (PLAN-merged-evaluation-table T2 fold, H1): a
        // throwaway per-execution JoinCache carries it, exactly as LibraryValidator's per-dataset
        // cache carries the derived run standard. An undeclared library ("test") stays UNKNOWN,
        // the POOLID inference.
        JoinCache joinCache = new JoinCache(null,
                MergeStandard.ofStandardName(aLibrary != null ? aLibrary.getStandard() : null));
        RuleExecutionResult result = RuleRunner.execute(aRule, aPrimary, aResolver, aDomain,
                aLibrary, joinCache, aDefine, Integer.MAX_VALUE, null, aDictionaries, aVlm,
                Set.of(), Set.of(), EngineLimits.resolveSeverityThreshold(aRunLevel));
        if (aObserver != null)
        {
            // PLAN-output-variable-null-key-provenance §2.1: the corpus's finding-map null guard
            // judges every scenario execution here, and counts it.
            aObserver.afterExecution(
                    "scenario " + aDomain + " / " + aPrimary.getMetaData().getName(), aRule,
                    result);
        }
        return result;
    }

    // ---- sidecars and dictionaries -------------------------------------------------------


    /**
     * Parse a {@code #define-xml} sidecar, resolved against the scenario file's directory. A
     * refusal of the resolver passes through in its own words (why, tree-relative); a sidecar that
     * resolves but does not parse is named by its payload, never by an absolute path (pack-2 review
     * 8a-B L5).
     */
    private static OdmDefineXMLProvider parseDefineXml(@Nullable Path aScenarioFile,
            String aRelative, @Nullable SidecarResolver aSidecars)
        throws IOException
    {
        if (aScenarioFile == null || aSidecars == null)
        {
            throw new IOException("#define-xml " + aRelative
                    + " cannot be resolved: the scenario has no file or no sidecar tree");
        }
        InputStream opened = aSidecars.open(aScenarioFile, aRelative);
        try (InputStream in = opened)
        {
            return new OdmDefineXMLProvider(new DefineXmlParser().parse(in));
        }
        catch (IOException | RuntimeException e)
        {
            throw new IOException("could not parse #define-xml sidecar " + aRelative + ": "
                    + (e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()), e);
        }
    }


    private static @Nullable RuntimeDictionaryProvider resolveDictionaries(
            RuleTestScenario aScenario, @Nullable Path aDictionariesDir)
    {
        if (aScenario.getDictionaries() == null)
        {
            return null;
        }
        // Parser guarantees the only accepted value is "dummy".
        if (aDictionariesDir == null)
        {
            throw new IllegalStateException("the scenario names #dictionaries "
                    + aScenario.getDictionaries() + " but this run has no dictionary bundle");
        }
        return DICTIONARIES.computeIfAbsent(aDictionariesDir, dir ->
        {
            try
            {
                return RuntimeDictionaryProvider.loadDirectory(dir);
            }
            catch (IOException e)
            {
                throw new UncheckedIOException(
                        "could not load the dummy dictionaries from " + dir.getFileName(), e);
            }
        });
    }


    /** The equality the old harness used for the primary's identity in messages. */
    static String describe(RuleTestScenario aScenario)
    {
        return Objects.requireNonNullElse(aScenario.getSource(), aScenario.getCoreId());
    }
}
