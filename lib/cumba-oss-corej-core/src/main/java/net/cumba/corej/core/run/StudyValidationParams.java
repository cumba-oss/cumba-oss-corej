package net.cumba.corej.core.run;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.UnaryOperator;

import net.cumba.corej.core.report.LibraryValidator;
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
 * Mirrors the CLI's include/exclude semantics exactly. The "use all / filtered" intent is made
 * explicit via {@link #ruleSelectionMode()}:
 * </p>
 * <ul>
 * <li>{@link RuleSelectionMode#ALL} — include all loaded rules ({@link #includeRules()} and
 * {@link #excludeRules()} both empty).</li>
 * <li>{@link RuleSelectionMode#FILTERED} — keep only rules whose CORE id is in
 * {@link #includeRules()} (when non-empty) and not in {@link #excludeRules()}. This is the CLI's
 * behaviour whenever either list is non-empty.</li>
 * </ul>
 *
 * <p>
 * The mode is derived, never set: {@link Builder#includeRules(List)} /
 * {@link Builder#excludeRules(List)} switch it to {@code FILTERED} when either list is non-empty. ⚑
 * An explicit setter and a third mode, {@code NONE} ("run no bundled rules"), existed until
 * PLAN-retire-dead-multi-match-lookup U10 (2026-09-25); no product code ever set either.
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
        FILTERED
    }

    /** A use-case code: letters only (ruling T1-3). */
    private static final java.util.regex.Pattern USE_CASE_CODE = java.util.regex.Pattern
            .compile("[A-Za-z]+");

    private final IDataTableManager manager;

    private final @Nullable String dataLibrary;

    private final @Nullable String defineXmlPath;

    private final List<String> referenceData;

    private final List<String> metadataProducts;

    private final @Nullable String useCase;

    private final List<String> controlledTerminologyPackages;

    private final @Nullable String ctResolutionNote;

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

    private final @Nullable String metadataStore;

    private final @Nullable String dictionariesDir;

    private final Map<String, String> dictionaryVersions;

    private final LibraryValidator.@Nullable RuntimeListener runtimeListener;

    private final @Nullable ProgressListener progressListener;

    private final @Nullable BooleanSupplier cancellation;

    private final UnaryOperator<Runnable> taskDecorator;

    private StudyValidationParams(Builder b)
    {
        manager = b.manager;
        dataLibrary = b.dataLibrary;
        defineXmlPath = b.defineXmlPath;
        referenceData = List.copyOf(b.referenceData);
        // ⛔ Plan 2 R5 deleted §1b′ with -s / -v. An omitted --metadata-products is now simply
        // EMPTY here; the selected rule packages' declared standards supply the products, appended
        // by StudyValidationService.effectiveMetadataProducts (R7). This list may therefore be
        // empty — a package that declares nothing AND no -mp is a hard error, raised there.
        metadataProducts = List.copyOf(b.metadataProducts);
        useCase = b.useCase;
        controlledTerminologyPackages = List.copyOf(b.controlledTerminologyPackages);
        ctResolutionNote = b.ctResolutionNote;
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
        metadataStore = b.metadataStore;
        dictionariesDir = b.dictionariesDir;
        dictionaryVersions = Collections.unmodifiableMap(new LinkedHashMap<>(b.dictionaryVersions));
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


    /**
     * The run's use case ({@code -uc}), stripped; {@code null} when none was given (absent or
     * blank). Owner ruling X1: rules whose {@code Scope.Use_Case} names only other use cases are
     * reported {@code SKIPPED}; the value is also echoed as the report's {@code TIG_Use_Case}.
     */
    public @Nullable String useCase()
    {
        return useCase;
    }


    /** Controlled-terminology package ids ({@code -ct}); never {@code null}, possibly empty. */
    public List<String> controlledTerminologyPackages()
    {
        return controlledTerminologyPackages;
    }


    /**
     * CT-R3 — the caller's note on how {@link #controlledTerminologyPackages()} was resolved (e.g.
     * the manager's {@code <recent>} downgrade when fewer packages resolved than were selected), or
     * {@code null} when the selection resolved as given. Joined into the report's existing
     * {@code CT_Declaration_Mismatch} field rather than a new key (owner ruling 2026-09-09): engine
     * text first, this text appended, either alone when the other is null.
     */
    public @Nullable String ctResolutionNote()
    {
        return ctResolutionNote;
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
     * ({@link net.cumba.corej.core.exec.EngineLimits#DEFAULT_SEVERITY_THRESHOLD}, {@code Warning}),
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


    /**
     * The caller's explicit unified-metadata-store file for THIS run (the GUI's
     * {@code Metadata Store} field, a CLI flag), or {@code null} when the caller named none.
     * Carried per-run because it is the <b>top</b> tier of
     * {@code StoreMetadataProviderFactory.resolveConfiguredFile}'s precedence (explicit &gt;
     * {@code CDISC_METADATA_STORE} env &gt; {@code cdisc.metadata.store} sysprop) — smuggling the
     * user's named store through the system property instead ranks it BELOW the environment
     * variable, so an ambient {@code CDISC_METADATA_STORE} silently overrides the very store the
     * user typed. Exactly the {@link #dictionariesDir()} argument, and the same regression class:
     * at baseline the dialog's store fields reached the engine as real parameters no environment
     * variable could outrank.
     */
    public @Nullable String metadataStore()
    {
        return metadataStore;
    }


    /**
     * {@code PLAN-dictionary-seeder} — the caller's explicit installed-dictionary store root (the
     * CLI's {@code --dictionaries-dir}), or {@code null} when the caller named none. Carried
     * per-run, exactly like {@link #dictionaryVersions()}, because it is the <b>top</b> tier of
     * {@code DictionaryDirectoryResolver}'s precedence (explicit &gt; {@code
     * COREJ_DICTIONARIES_DIR} &gt; {@code -Dcorej.dictionariesDir} &gt; {@code ./dictionaries}) —
     * smuggling it through the system property instead would rank it BELOW the environment
     * variable, and every Docker image sets that variable: the flag would then silently lose to the
     * container default while install mode honoured it, so install and validate would read
     * different stores.
     */
    public @Nullable String dictionariesDir()
    {
        return dictionariesDir;
    }


    /**
     * {@code PLAN-dictionary-seeder} Phase 6b (D6) — caller-requested external-dictionary versions,
     * keyed by lower-cased type ({@code meddra}, {@code unii}, …). The highest-precedence
     * version-selection source: it outranks a define.xml {@code ExternalCodeList/@Version}, which
     * outranks the store's {@code selected-versions.json} manifest. Empty means "nothing requested
     * here" — never "no dictionaries".
     */
    public Map<String, String> dictionaryVersions()
    {
        return dictionaryVersions;
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
     * The run's use case as the engine reads it: stripped, and {@code null} for {@code null} or
     * blank — "no use case", which filters nothing (owner ruling X1, T1-3). Shared by the builder
     * and the client boundaries (CLI, REST) so every surface normalises alike.
     *
     * @param aUseCase
     *            the raw value, possibly {@code null}
     * @return the stripped value, or {@code null} when none was given
     */
    public static @Nullable String normalizeUseCase(@Nullable String aUseCase)
    {
        return aUseCase == null || aUseCase.isBlank() ? null : aUseCase.strip();
    }


    /**
     * Whether {@code aUseCase}, once stripped, is a <b>single</b> use-case code — letters only
     * ({@code INDH}, {@code PROD}, {@code nonclin}). Ruling T1-3: a comma list, an inner space or
     * punctuation is rejected loudly at the boundary, because {@code "INDH, PROD"} as one code
     * would silently exclude every rule that declares a use case. The vocabulary itself is not
     * checked: the corpus does not define one ({@code R-4.10} is syntax only). {@code null} and
     * blank are not codes — callers read them as "no use case" ({@link #normalizeUseCase}) before
     * asking.
     *
     * @param aUseCase
     *            the value to test
     * @return {@code true} when it is one well-formed code
     */
    public static boolean isWellFormedUseCase(@Nullable String aUseCase)
    {
        return aUseCase != null && USE_CASE_CODE.matcher(aUseCase.strip()).matches();
    }


    /**
     * The one message every boundary uses to reject a malformed use case.
     *
     * @param aUseCase
     *            the rejected value
     * @return the error text, naming the value
     */
    public static String useCaseError(String aUseCase)
    {
        return "the use case must be a single code of letters, e.g. INDH, NONCLIN or PROD (got '"
                + aUseCase + "'); a rule may list several use cases, a run names one";
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

        private List<String> referenceData = new ArrayList<>();

        private List<String> metadataProducts = new ArrayList<>();

        private @Nullable String useCase;

        private List<String> controlledTerminologyPackages = new ArrayList<>();

        private @Nullable String ctResolutionNote;

        private @Nullable String defineVersion;

        private RuleSelectionMode ruleSelectionMode = RuleSelectionMode.ALL;

        private List<String> includeRules = new ArrayList<>();

        private List<String> excludeRules = new ArrayList<>();

        private @Nullable String rulesDir;

        private List<String> rulesPackages = new ArrayList<>();

        private List<String> rulesFiles = new ArrayList<>();

        private Set<String> datasetFilter = new LinkedHashSet<>();

        private int ruleThreads = 1;

        private @Nullable Integer maxErrorsPerRule;

        private net.cumba.datatable.report.@Nullable Severity severityThreshold;

        private @Nullable String metadataStore;

        private @Nullable String dictionariesDir;

        private Map<String, String> dictionaryVersions = new LinkedHashMap<>();

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


        /**
         * The run's use case ({@code -uc}): stripped, and blank read as none
         * ({@link #normalizeUseCase}). A value that is not a single code is rejected by
         * {@link #build()} (ruling T1-3).
         */
        public Builder useCase(@Nullable String aUseCase)
        {
            useCase = normalizeUseCase(aUseCase);
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


        /** CT-R3 — the caller's CT-resolution note; see {@link #ctResolutionNote()}. */
        public Builder ctResolutionNote(@Nullable String aNote)
        {
            ctResolutionNote = aNote;
            return this;
        }


        /** Explicit Define-XML version ({@code -dv}). */
        public Builder defineVersion(@Nullable String aDefineVersion)
        {
            defineVersion = aDefineVersion;
            return this;
        }


        /**
         * Include CORE-id filter ({@code -r}). A non-empty list implicitly switches the mode to
         * {@link RuleSelectionMode#FILTERED} (the mode has no setter of its own). A {@code null}
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
         * {@link RuleSelectionMode#FILTERED} (the mode has no setter of its own). A {@code null}
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
            if (!includeRules.isEmpty() || !excludeRules.isEmpty())
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


        /**
         * The caller's explicit unified-metadata-store file for this run — the top tier of the
         * store resolution, above the {@code CDISC_METADATA_STORE} environment variable (see
         * {@link StudyValidationParams#metadataStore()}). {@code null} (the default) resolves from
         * the environment, then the {@code cdisc.metadata.store} system property.
         */
        public Builder metadataStore(@Nullable String aMetadataStore)
        {
            metadataStore = aMetadataStore;
            return this;
        }


        /**
         * The caller's explicit installed-dictionary store root ({@code --dictionaries-dir}) — the
         * top tier of the directory resolution (see
         * {@link StudyValidationParams#dictionariesDir()}). {@code null} (the default) resolves
         * from the environment.
         */
        public Builder dictionariesDir(@Nullable String aDictionariesDir)
        {
            dictionariesDir = aDictionariesDir;
            return this;
        }


        /**
         * Caller-requested external-dictionary versions, keyed by lower-cased type — the
         * highest-precedence selection source (see
         * {@link StudyValidationParams#dictionaryVersions()}). {@code null} clears to empty.
         */
        public Builder dictionaryVersions(@Nullable Map<String, String> aDictionaryVersions)
        {
            dictionaryVersions = aDictionaryVersions == null ? new LinkedHashMap<>()
                    : new LinkedHashMap<>(aDictionaryVersions);
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
            if (useCase != null && !isWellFormedUseCase(useCase))
            {
                throw new IllegalArgumentException(useCaseError(useCase));
            }
            return new StudyValidationParams(this);
        }
    }
}
