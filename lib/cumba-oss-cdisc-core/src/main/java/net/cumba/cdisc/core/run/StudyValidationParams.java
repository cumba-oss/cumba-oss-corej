package net.cumba.cdisc.core.run;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import net.cumba.cdisc.core.report.LibraryValidator;
import net.cumba.datatable.manager.IDataTableManager;
import org.jspecify.annotations.Nullable;

/**
 * Immutable, engine-relevant inputs for a single {@link StudyValidationService} run.
 *
 * <p>
 * These are the inputs that the CLI used to hold in its {@code Args} struct, minus the
 * CLI-presentation concerns (output file path, output format, runtime-report path, help flag,
 * ignored-option set). The CLI maps its parsed {@code Args} onto this object; a REST layer maps a
 * request DTO onto it. Build with {@link #builder()}.
 * </p>
 *
 * <h2>Data inputs</h2>
 *
 * <p>
 * Exactly the {@code -d} / {@code -dxp} shape the CLI exposes:
 * </p>
 * <ul>
 * <li>{@link #dataLibrary()} present: the data library (directory, file, or URI). All members are
 * validation targets (subject to {@link #datasetFilter()}). {@link #defineXmlPath()}, if present,
 * provides metadata enrichment only.</li>
 * <li>{@link #dataLibrary()} absent but {@link #defineXmlPath()} present: the define.xml acts as
 * both the data library and the metadata source (legacy fallback).</li>
 * </ul>
 *
 * <h2>Rule selection</h2>
 *
 * <p>
 * Mirrors the CLI's include/exclude semantics exactly. The "use none / all / filtered" intent is
 * made explicit via {@link #ruleSelectionMode()}:
 * </p>
 * <ul>
 * <li>{@link RuleSelectionMode#ALL} — include all loaded rules ({@link #includeRules()} and
 * {@link #excludeRules()} both empty).</li>
 * <li>{@link RuleSelectionMode#FILTERED} — keep only rules whose CORE id is in
 * {@link #includeRules()} (when non-empty) and not in {@link #excludeRules()}. This is the CLI's
 * behaviour whenever either list is non-empty.</li>
 * <li>{@link RuleSelectionMode#NONE} — select no bundled rules at all (an empty include set after
 * an explicit "none" request); the service treats the filtered result as empty.</li>
 * </ul>
 *
 * <p>
 * For backward compatibility with the CLI, {@link Builder#includeRules(List)} /
 * {@link Builder#excludeRules(List)} implicitly switch the mode to {@code FILTERED} when either is
 * non-empty (unless {@code NONE} was explicitly requested).
 * </p>
 */
public final class StudyValidationParams
{

    /** How the bundled / loaded rule set is narrowed before validation. */
    public enum RuleSelectionMode
    {

        /** Run every loaded rule (no include/exclude filtering). */
        ALL,

        /** Run only rules surviving the include/exclude CORE-id filter. */
        FILTERED,

        /** Run no bundled rules at all. */
        NONE
    }

    private final IDataTableManager manager;

    private final @Nullable String dataLibrary;

    private final @Nullable String defineXmlPath;

    private final List<String> referenceData;

    private final List<String> metadataProducts;

    private final @Nullable String useCase;

    private final List<String> controlledTerminologyPackages;

    private final @Nullable String defineVersion;

    private final RuleSelectionMode ruleSelectionMode;

    private final List<String> includeRules;

    private final List<String> excludeRules;

    private final @Nullable String rulesDir;

    private final List<String> rulesPackages;

    private final List<String> rulesFiles;

    private final Set<String> datasetFilter;

    private final int ruleThreads;

    private final @Nullable Integer maxErrorsPerRule;

    private final net.cumba.datatable.report.@Nullable Severity severityThreshold;

    private final @Nullable String cacheDir;

    private final @Nullable String pickleCacheDir;

    private final LibraryValidator.@Nullable RuntimeListener runtimeListener;

    private final @Nullable ProgressListener progressListener;

    private final @Nullable BooleanSupplier cancellation;

    private final UnaryOperator<Runnable> taskDecorator;

    private final net.cumba.cdisc.core.gen.@Nullable DefineXMLProvider defineXmlProvider;

    private StudyValidationParams(Builder b)
    {
        manager = b.manager;
        dataLibrary = b.dataLibrary;
        defineXmlPath = b.defineXmlPath;
        defineXmlProvider = b.defineXmlProvider;
        referenceData = List.copyOf(b.referenceData);
        // ⛔ Plan 2 R5 deleted §1b′ with -s / -v. An omitted --metadata-products is now simply
        // EMPTY here; the selected rule packages' declared standards supply the products, appended
        // by StudyValidationService.effectiveMetadataProducts (R7). This list may therefore be
        // empty — a package that declares nothing AND no -mp is a hard error, raised there.
        metadataProducts = List.copyOf(b.metadataProducts);
        useCase = b.useCase;
        controlledTerminologyPackages = List.copyOf(b.controlledTerminologyPackages);
        defineVersion = b.defineVersion;
        ruleSelectionMode = b.ruleSelectionMode;
        includeRules = List.copyOf(b.includeRules);
        excludeRules = List.copyOf(b.excludeRules);
        rulesDir = b.rulesDir;
        rulesPackages = List.copyOf(b.rulesPackages);
        rulesFiles = List.copyOf(b.rulesFiles);
        datasetFilter = Collections.unmodifiableSet(new LinkedHashSet<>(b.datasetFilter));
        ruleThreads = b.ruleThreads;
        maxErrorsPerRule = b.maxErrorsPerRule;
        severityThreshold = b.severityThreshold;
        cacheDir = b.cacheDir;
        pickleCacheDir = b.pickleCacheDir;
        runtimeListener = b.runtimeListener;
        progressListener = b.progressListener;
        cancellation = b.cancellation;
        taskDecorator = b.taskDecorator;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------


    /** The data-table manager used to resolve libraries and load tables. Never {@code null}. */
    public IDataTableManager manager()
    {
        return manager;
    }


    /**
     * Data library path or URI ({@code -d}); {@code null} when the run is driven by
     * {@link #defineXmlPath()} alone.
     */
    public @Nullable String dataLibrary()
    {
        return dataLibrary;
    }


    /** Optional define.xml path ({@code -dxp}); {@code null} when absent. */
    public @Nullable String defineXmlPath()
    {
        return defineXmlPath;
    }


    /**
     * Optional direct Define-XML provider supplied by the caller. When present, the engine reads
     * the {@code define_*} operands straight from it (wrapped in a
     * {@link net.cumba.cdisc.core.metadata.DefineXmlMetadataProvider}) instead of converting the
     * Define-XML into the datatable metadata model — the direct-access path for
     * {@code Define Item Metadata Check against Library Metadata} rules. {@code null} falls back to
     * the datatable-backed define provider derived from {@link #defineXmlPath()}.
     */
    public net.cumba.cdisc.core.gen.@Nullable DefineXMLProvider defineXmlProvider()
    {
        return defineXmlProvider;
    }


    /** Reference-data library paths ({@code -rd}); never {@code null}, possibly empty. */
    public List<String> referenceData()
    {
        return referenceData;
    }


    /**
     * The declared CDISC Library metadata products ({@code -mp} / {@code --metadata-products}) as
     * resolved {@code standards/...} cache keys, in the user's precedence order (first match wins).
     * Never {@code null}, but <b>may be empty</b>: Plan 2 (R5) removed {@code -s}/{@code -v}, and
     * with them §1b′'s implied default. The selected rule packages' declared standards are appended
     * to this list by {@code StudyValidationService.effectiveMetadataProducts} (R7); a package that
     * declares none and a run with no {@code -mp} is a hard error raised there.
     *
     * <p>
     * ⛔ Metadata-only — {@code -mp} NEVER selects rules (R4). Rules are selected by {@code -rp} /
     * {@code --rules-package} and {@code --rules-file}.
     * </p>
     */
    public List<String> metadataProducts()
    {
        return metadataProducts;
    }


    /** TIG use case ({@code -uc}); {@code null} when absent. */
    public @Nullable String useCase()
    {
        return useCase;
    }


    /** Controlled-terminology package ids ({@code -ct}); never {@code null}, possibly empty. */
    public List<String> controlledTerminologyPackages()
    {
        return controlledTerminologyPackages;
    }


    /** Explicit Define-XML version ({@code -dv}); {@code null} to read it from the library. */
    public @Nullable String defineVersion()
    {
        return defineVersion;
    }


    /** How the loaded rule set is narrowed. Never {@code null}. */
    public RuleSelectionMode ruleSelectionMode()
    {
        return ruleSelectionMode;
    }


    /** Include CORE-id filter ({@code -r}); never {@code null}, possibly empty. */
    public List<String> includeRules()
    {
        return includeRules;
    }


    /** Exclude CORE-id filter ({@code -er}); never {@code null}, possibly empty. */
    public List<String> excludeRules()
    {
        return excludeRules;
    }


    /** Rules directory ({@code --rules-dir}); {@code null} to use the service default. */
    public @Nullable String rulesDir()
    {
        return rulesDir;
    }


    /**
     * Rule packages selected by short name ({@code -rp} / {@code --rules-package}), e.g.
     * {@code cdisc-adamig-1-3} for {@code rules-cdisc-adamig-1-3.json}. Never {@code null},
     * possibly empty.
     *
     * <p>
     * Together with {@link #rulesFiles()} this is the run's <b>explicit</b> rule selection: when
     * either is non-empty the run executes exactly their union and the conventional
     * {@code (family, standard, version)} packages are not consulted. When both are empty the run
     * falls back to that conventional selection.
     * </p>
     */
    public List<String> rulesPackages()
    {
        return rulesPackages;
    }


    /** Extra rule-package files ({@code --rules-file}); never {@code null}, possibly empty. */
    public List<String> rulesFiles()
    {
        return rulesFiles;
    }


    /**
     * Dataset target filter ({@code -ds}); empty means "every member is a target". Members not in a
     * non-empty filter become lazy references. Never {@code null}.
     */
    public Set<String> datasetFilter()
    {
        return datasetFilter;
    }


    /** Rule worker threads per dataset ({@code -t}); {@code >= 1}. */
    public int ruleThreads()
    {
        return ruleThreads;
    }


    /**
     * Per-run override of the per-rule findings cap. {@code null} follows the global
     * {@code corej.maxErrorsPerRule} / {@code MAX_ERRORS_PER_RULE} configuration; a value
     * {@code <= 0} means unlimited.
     */
    public @Nullable Integer maxErrorsPerRule()
    {
        return maxErrorsPerRule;
    }


    /**
     * The run's <b>severity threshold</b> (Plan C §3.4, ruling 4) — the weakest check level this
     * run evaluates. {@code null} means the engine default
     * ({@link net.cumba.cdisc.core.exec.EngineLimits#DEFAULT_SEVERITY_THRESHOLD}, {@code Warning}),
     * so {@code REJECT} + {@code ERROR} + {@code WARNING} evaluate and {@code INFO} does not.
     *
     * <p>
     * ⚑ A <b>run</b> option and nothing else: the CLI's {@code --severity-level}, the REST
     * {@code CheckRunRequest} field and the {@code .cdt} {@code #runLevel} directive all set this
     * one value, and no rule package or rule may carry one.
     * </p>
     *
     * @return the declared threshold, or {@code null} for the engine default
     */
    public net.cumba.datatable.report.@Nullable Severity severityThreshold()
    {
        return severityThreshold;
    }


    /** CDISC Library API cache directory ({@code -ca}); {@code null} for the client default. */
    public @Nullable String cacheDir()
    {
        return cacheDir;
    }


    /**
     * Python pickle metadata cache directory ({@code --pickle-cache}); {@code null} to fall back to
     * {@code CDISC_PICKLE_CACHE_DIR} / {@code cdisc.pickle.cache.dir}, then the CDISC Library API.
     */
    public @Nullable String pickleCacheDir()
    {
        return pickleCacheDir;
    }


    /**
     * Optional per-rule runtime listener wired straight onto the validator (the CLI uses this to
     * write its runtime CSV). {@code null} for none.
     */
    public LibraryValidator.@Nullable RuntimeListener runtimeListener()
    {
        return runtimeListener;
    }


    /** Optional progress callback. {@code null} for none. */
    public @Nullable ProgressListener progressListener()
    {
        return progressListener;
    }


    /**
     * Optional cancellation check. When it returns {@code true} the service aborts at the next
     * dataset boundary by throwing {@link CancelledException}. {@code null} means "never
     * cancelled".
     */
    public @Nullable BooleanSupplier cancellation()
    {
        return cancellation;
    }


    /**
     * Optional task decorator applied to every async task the engine submits to its parallel
     * executors. The decorator is applied <em>on the submitting thread</em> (where any thread-bound
     * context is live), so callers can re-establish that context on the worker thread that
     * ultimately runs the task — robust against thread pooling, reuse, and virtual-vs-platform
     * threads. Never {@code null}; defaults to {@link UnaryOperator#identity()} (no-op).
     */
    public UnaryOperator<Runnable> taskDecorator()
    {
        return taskDecorator;
    }


    public static Builder builder()
    {
        return new Builder();
    }

    /**
     * Mutable builder for {@link StudyValidationParams}. Only {@link #manager(IDataTableManager)}
     * is required; everything else has a sensible default (empty collections, {@code null}
     * optionals, {@code ruleThreads == 1}, {@link RuleSelectionMode#ALL}). ⚑ {@code standard} /
     * {@code version} / {@code families} were removed by Plan 2 (R5): a run's standard is derived
     * from the rule packages it selects.
     */
    // Staged builder: the required manager stays unset until its fluent
    // setters run; NullAway's init check can't follow that staged-assignment pattern.
    @SuppressWarnings("NullAway.Init")
    public static final class Builder
    {

        private IDataTableManager manager;

        private @Nullable String dataLibrary;

        private @Nullable String defineXmlPath;

        private net.cumba.cdisc.core.gen.@Nullable DefineXMLProvider defineXmlProvider;

        private List<String> referenceData = new ArrayList<>();

        private List<String> metadataProducts = new ArrayList<>();

        private @Nullable String useCase;

        private List<String> controlledTerminologyPackages = new ArrayList<>();

        private @Nullable String defineVersion;

        private RuleSelectionMode ruleSelectionMode = RuleSelectionMode.ALL;

        private boolean modeExplicit;

        private List<String> includeRules = new ArrayList<>();

        private List<String> excludeRules = new ArrayList<>();

        private @Nullable String rulesDir;

        private List<String> rulesPackages = new ArrayList<>();

        private List<String> rulesFiles = new ArrayList<>();

        private Set<String> datasetFilter = new LinkedHashSet<>();

        private int ruleThreads = 1;

        private @Nullable Integer maxErrorsPerRule;

        private net.cumba.datatable.report.@Nullable Severity severityThreshold;

        private @Nullable String cacheDir;

        private @Nullable String pickleCacheDir;

        private LibraryValidator.@Nullable RuntimeListener runtimeListener;

        private @Nullable ProgressListener progressListener;

        private @Nullable BooleanSupplier cancellation;

        private UnaryOperator<Runnable> taskDecorator = UnaryOperator.identity();

        private Builder()
        {
        }


        /** The data-table manager (required). */
        public Builder manager(IDataTableManager aManager)
        {
            manager = aManager;
            return this;
        }


        /** Data library path or URI ({@code -d}). */
        public Builder dataLibrary(@Nullable String aDataLibrary)
        {
            dataLibrary = aDataLibrary;
            return this;
        }


        /** Optional define.xml path ({@code -dxp}). */
        public Builder defineXmlPath(@Nullable String aDefineXmlPath)
        {
            defineXmlPath = aDefineXmlPath;
            return this;
        }


        /**
         * Optional direct Define-XML provider (the ODM-backed direct-access path). When set, the
         * engine reads {@code define_*} operands from it rather than the datatable metadata model.
         */
        public Builder defineXmlProvider(
                net.cumba.cdisc.core.gen.@Nullable DefineXMLProvider aDefineXmlProvider)
        {
            defineXmlProvider = aDefineXmlProvider;
            return this;
        }


        /** Reference-data library paths ({@code -rd}). A {@code null} argument clears the list. */
        public Builder referenceData(List<String> aReferenceData)
        {
            referenceData = aReferenceData != null ? new ArrayList<>(aReferenceData)
                    : new ArrayList<>();
            return this;
        }


        /**
         * Declared metadata products ({@code -mp} / {@code --metadata-products}) as resolved
         * {@code standards/...} cache keys, highest precedence first. A {@code null} or empty
         * argument clears the list, restoring the omitted-flag default (the product implied by
         * {@code standard}/{@code version}).
         */
        public Builder metadataProducts(@Nullable List<String> aProducts)
        {
            metadataProducts = aProducts != null ? new ArrayList<>(aProducts) : new ArrayList<>();
            return this;
        }


        /** TIG use case ({@code -uc}). */
        public Builder useCase(@Nullable String aUseCase)
        {
            useCase = aUseCase;
            return this;
        }


        /**
         * Controlled-terminology package ids ({@code -ct}). A {@code null} argument clears them.
         */
        public Builder controlledTerminologyPackages(List<String> aPackages)
        {
            controlledTerminologyPackages = aPackages != null ? new ArrayList<>(aPackages)
                    : new ArrayList<>();
            return this;
        }


        /** Explicit Define-XML version ({@code -dv}). */
        public Builder defineVersion(@Nullable String aDefineVersion)
        {
            defineVersion = aDefineVersion;
            return this;
        }


        /**
         * Sets the rule-selection mode explicitly. Once set, {@link #includeRules(List)} /
         * {@link #excludeRules(List)} will not override it (so callers can force {@code NONE} even
         * with an empty include list, or {@code ALL} while ignoring stray filters).
         */
        public Builder ruleSelectionMode(RuleSelectionMode aMode)
        {
            ruleSelectionMode = aMode != null ? aMode : RuleSelectionMode.ALL;
            modeExplicit = true;
            return this;
        }


        /**
         * Include CORE-id filter ({@code -r}). A non-empty list implicitly switches the mode to
         * {@link RuleSelectionMode#FILTERED} unless the mode was set explicitly. A {@code null}
         * argument clears the list.
         */
        public Builder includeRules(List<String> aIncludeRules)
        {
            includeRules = aIncludeRules != null ? new ArrayList<>(aIncludeRules)
                    : new ArrayList<>();
            maybeSwitchToFiltered();
            return this;
        }


        /**
         * Exclude CORE-id filter ({@code -er}). A non-empty list implicitly switches the mode to
         * {@link RuleSelectionMode#FILTERED} unless the mode was set explicitly. A {@code null}
         * argument clears the list.
         */
        public Builder excludeRules(List<String> aExcludeRules)
        {
            excludeRules = aExcludeRules != null ? new ArrayList<>(aExcludeRules)
                    : new ArrayList<>();
            maybeSwitchToFiltered();
            return this;
        }


        private void maybeSwitchToFiltered()
        {
            if (!modeExplicit && (!includeRules.isEmpty() || !excludeRules.isEmpty()))
            {
                ruleSelectionMode = RuleSelectionMode.FILTERED;
            }
        }


        /** Rules directory ({@code --rules-dir}). */
        public Builder rulesDir(@Nullable String aRulesDir)
        {
            rulesDir = aRulesDir;
            return this;
        }


        /**
         * Rule packages by short name ({@code -rp} / {@code --rules-package}). A {@code null}
         * argument clears them.
         */
        public Builder rulesPackages(@Nullable List<String> aRulesPackages)
        {
            rulesPackages = aRulesPackages != null ? new ArrayList<>(aRulesPackages)
                    : new ArrayList<>();
            return this;
        }


        /** Extra rule-package files ({@code --rules-file}). A {@code null} argument clears them. */
        public Builder rulesFiles(List<String> aRulesFiles)
        {
            rulesFiles = aRulesFiles != null ? new ArrayList<>(aRulesFiles) : new ArrayList<>();
            return this;
        }


        /** Dataset target filter ({@code -ds}). A {@code null} argument clears it. */
        public Builder datasetFilter(Set<String> aDatasetFilter)
        {
            datasetFilter = aDatasetFilter != null ? new LinkedHashSet<>(aDatasetFilter)
                    : new LinkedHashSet<>();
            return this;
        }


        /** Rule worker threads per dataset ({@code -t}); must be {@code >= 1}. */
        public Builder ruleThreads(int aRuleThreads)
        {
            ruleThreads = aRuleThreads;
            return this;
        }


        /**
         * Per-run override of the per-rule findings cap. {@code null} (the default) follows the
         * global {@code corej.maxErrorsPerRule} / {@code MAX_ERRORS_PER_RULE} configuration; a
         * value {@code <= 0} means unlimited.
         */
        public Builder maxErrorsPerRule(@Nullable Integer aMaxErrorsPerRule)
        {
            maxErrorsPerRule = aMaxErrorsPerRule;
            return this;
        }


        /**
         * The run's severity threshold — the weakest check level to evaluate (Plan C §3.4).
         * {@code null} (the default) means {@code Warning}.
         *
         * @param aSeverityThreshold
         *            the weakest rung to evaluate, or {@code null} for the engine default
         * @return this builder
         */
        public Builder severityThreshold(
                net.cumba.datatable.report.@Nullable Severity aSeverityThreshold)
        {
            severityThreshold = aSeverityThreshold;
            return this;
        }


        /** CDISC Library API cache directory ({@code -ca}). */
        public Builder cacheDir(@Nullable String aCacheDir)
        {
            cacheDir = aCacheDir;
            return this;
        }


        /** Python pickle metadata cache directory ({@code --pickle-cache}). */
        public Builder pickleCacheDir(@Nullable String aPickleCacheDir)
        {
            pickleCacheDir = aPickleCacheDir;
            return this;
        }


        /** Optional per-rule runtime listener. */
        public Builder runtimeListener(LibraryValidator.@Nullable RuntimeListener aRuntimeListener)
        {
            runtimeListener = aRuntimeListener;
            return this;
        }


        /** Optional progress callback. */
        public Builder progressListener(@Nullable ProgressListener aProgressListener)
        {
            progressListener = aProgressListener;
            return this;
        }


        /** Optional cancellation check ({@code null} = never cancelled). */
        public Builder cancellation(@Nullable BooleanSupplier aCancellation)
        {
            cancellation = aCancellation;
            return this;
        }


        /**
         * Optional task decorator applied to every async task the engine submits to its parallel
         * executors, on the submitting thread. Lets a caller re-establish thread-bound context on
         * the worker thread that runs the task. Defaults to {@link UnaryOperator#identity()}
         * (no-op, so non-REST callers are unaffected). Must not be {@code null}.
         */
        public Builder taskDecorator(UnaryOperator<Runnable> d)
        {
            taskDecorator = Objects.requireNonNull(d, "taskDecorator");
            return this;
        }


        public StudyValidationParams build()
        {
            Objects.requireNonNull(manager, "manager");
            if (ruleThreads < 1)
            {
                throw new IllegalArgumentException(
                        "ruleThreads must be >= 1 (got " + ruleThreads + ")");
            }
            return new StudyValidationParams(this);
        }
    }
}
