package net.cumba.corej.core.run;

import java.io.File;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.lang.ref.SoftReference;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.SequencedSet;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.function.Supplier;

import lombok.CustomLog;

import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.MetadataProvider;
import net.cumba.corej.core.metadata.AdamSubclassDetector;

import net.cumba.corej.core.metadata.CompanionDomainsProvider;
import net.cumba.corej.core.metadata.MetadataLibraryProvider;
import net.cumba.corej.core.metadata.MetadataProductKeys;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.report.LibraryValidator;
import net.cumba.corej.core.report.ReportAssembler;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.manager.IDataTableLibraryRef;
import net.cumba.datatable.manager.IDataTableManager;
import net.cumba.datatable.manager.IDataTableRef;
import net.cumba.datatable.manager.ILibraryMemberRef;
import net.cumba.datatable.report.ValidationReport;
import org.jspecify.annotations.Nullable;

/**
 * Reusable orchestration of a single CDISC study validation, independent of CLI argument parsing
 * and file I/O. Lifted out of the {@code CdiscValidate} command-line tool so that both the CLI and
 * a REST service can drive the engine through one code path.
 *
 * <h2>Sequence</h2>
 * <ol>
 * <li>resolve the data library (and optional metadata-overlay define.xml);</li>
 * <li>load datasets — targets (validated) plus lazy references (visible to cross-dataset rules but
 * never iterated);</li>
 * <li>optionally load additional reference libraries;</li>
 * <li>build a {@link MetadataProvider} (CDISC Library enrichment, best-effort);</li>
 * <li>load and filter rule packages;</li>
 * <li>run {@link LibraryValidator} sequentially over the targets;</li>
 * <li>assemble a {@link StudyValidationResult} (report + conformance + dataset infos + rules).</li>
 * </ol>
 *
 * <h2>Optional hooks</h2>
 * <ul>
 * <li>{@link StudyValidationParams#progressListener()} — fired as work happens (datasets
 * discovered, each dataset completed, each rule executed). Null = no-op.</li>
 * <li>{@link StudyValidationParams#cancellation()} — checked before dataset enumeration and again
 * between rule executions during validation; a {@code true} result aborts the run with
 * {@link CancelledException}. Null = never cancelled.</li>
 * <li>{@link StudyValidationParams#runtimeListener()} — passed straight to the validator alongside
 * the progress hook (the CLI uses it to write a per-rule runtime CSV). Null = none.</li>
 * </ul>
 *
 * <p>
 * Failures: unresolvable paths / unreadable files surface as {@link IOException}; "no datasets" and
 * "no rules selected" surface as {@link StudyValidationException}; cancellation surfaces as
 * {@link CancelledException}.
 * </p>
 *
 * <h2>Authentication</h2> The CDISC Library API key is read from the {@code CDISC_API_KEY}
 * environment variable, falling back to the {@code cdisc.library.api.key} system property. Without
 * a key, enrichment is skipped (degraded mode) and the run may produce SKIPPED rules.
 */
@CustomLog
public final class StudyValidationService
{

    /**
     * Environment variable that sets the default rules directory when no explicit
     * {@link StudyValidationParams#rulesDir()} is given. Takes precedence over
     * {@link #SP_RULES_DIR} (env-first, mirroring the {@code CDISC_API_KEY} convention).
     */
    public static final String ENV_RULES_DIR = "COREJ_RULES_DIR";

    /**
     * System-property counterpart to {@link #ENV_RULES_DIR} (lower precedence than the env var).
     */
    public static final String SP_RULES_DIR = "corej.rules.dir";

    /**
     * Rules directory used when none is configured anywhere: {@code ./rules}, relative to the
     * process working directory. Override per run via {@link StudyValidationParams#rulesDir()}, or
     * globally via {@link #ENV_RULES_DIR} / {@link #SP_RULES_DIR}.
     */
    public static final String DEFAULT_RULES_DIR = "./rules";

    private final @Nullable String coreEngineVersion;

    /**
     * Creates a service with no engine-version stamp (the conformance block's
     * {@code CORE_Engine_Version} will be omitted).
     */
    public StudyValidationService()
    {
        this(null);
    }


    /**
     * Creates a service that stamps {@code aCoreEngineVersion} into the conformance block of every
     * result. The CLI passes the value it reads from {@code /version.properties}.
     *
     * @param aCoreEngineVersion
     *            engine version string, or {@code null} to omit it
     */
    public StudyValidationService(@Nullable String aCoreEngineVersion)
    {
        coreEngineVersion = aCoreEngineVersion;
    }


    /**
     * Runs the full validation described by {@code params} and returns its result.
     *
     * @param params
     *            the engine inputs and optional hooks
     * @return the assembled result (report, conformance, dataset infos, rules)
     * @throws IOException
     *             on unresolvable / unreadable data inputs
     * @throws StudyValidationException
     *             when the library has no datasets or no rules are selected
     * @throws CancelledException
     *             when the cancellation check reports {@code true}
     */
    public StudyValidationResult validate(StudyValidationParams params) throws IOException
    {
        long start = System.currentTimeMillis();
        // Phase 11 finding F9: the declared-subclass WARN latch is JVM-global and keyed only by
        // (dataset, token), so without this a second study's identically-named dataset would be
        // silently un-warned because the first study consumed the entry. Re-arm per run.
        AdamSubclassDetector.resetDeclarationWarnings();
        IDataTableManager manager = params.manager();

        // Phase 6 flag shape:
        // dataLibrary → the data library (any shape: dir, file, URI). Required source for
        // dataset enumeration unless defineXmlPath acts as the fallback (below).
        // defineXmlPath → optional define.xml. Two modes:
        // • If dataLibrary is given: define.xml provides metadata enrichment for the data library.
        // • If dataLibrary is absent: define.xml acts as the data library too (legacy fallback).
        IDataTableLibraryRef dataLibrary;
        IDataTableLibraryRef metadataLibrary;
        if (params.dataLibrary() != null)
        {
            dataLibrary = resolveLibrary(manager, params.dataLibrary());
            LOGGER.log(System.Logger.Level.INFO, "Using data library: {0}", params.dataLibrary());
            if (params.defineXmlPath() != null)
            {
                Path dxp = Path.of(params.defineXmlPath());
                requireExists(dxp);
                metadataLibrary = manager.getLibraryRef(dxp.toUri(), null);
                LOGGER.log(System.Logger.Level.INFO, "Using metadata overlay (define.xml): {0}",
                        dxp);
            }
            else
            {
                metadataLibrary = dataLibrary;
            }
        }
        else if (params.defineXmlPath() != null)
        {
            // Fallback: define.xml alone drives both data and metadata.
            Path dxp = Path.of(params.defineXmlPath());
            requireExists(dxp);
            dataLibrary = manager.getLibraryRef(dxp.toUri(), null);
            metadataLibrary = dataLibrary;
            LOGGER.log(System.Logger.Level.INFO, "Using define.xml as both data + metadata: {0}",
                    dxp);
        }
        else
        {
            throw new StudyValidationException(
                    "provide a data library or a define.xml path before validating");
        }

        checkCancelled(params.cancellation(), "before dataset load");

        // Phase A: load datasets. Members listed in the dataset filter become validation targets;
        // the rest are registered as lazy references so cross-dataset rules can still resolve them
        // without paying the load cost up front. Files designated as the define.xml / rules
        // file(s) are excluded — they are not data tables and must never be opened as one.
        Set<Path> excludedFiles = excludedLibraryFiles(params);
        LoadedLibrary loaded = loadDatasets(manager, dataLibrary, params.datasetFilter(),
                excludedFiles);
        List<DatasetEntry> datasets = loaded.targets();
        if (datasets.isEmpty())
        {
            throw new StudyValidationException(
                    params.datasetFilter().isEmpty() ? "library contains no datasets."
                            : "--dataset filter matched no library members.");
        }

        ProgressListener progress = params.progressListener();
        if (progress != null)
        {
            progress.onDatasetsDiscovered(datasets.size());
        }

        // Phase A2: load reference libraries (e.g. SDTM data when validating ADaM). Their members
        // are registered with the validator as lazy references — visible to cross-dataset rules
        // through the DatasetResolver but never iterated as validation targets themselves.
        List<ReferenceDataset> externalReferences = new ArrayList<>();
        for (String refPath : params.referenceData())
        {
            externalReferences.addAll(loadReferenceLibrary(manager, refPath, datasets, loaded));
        }

        // Phase A3 (define-ct plan §4.5): parse the sponsor Define-XML BEFORE the metadata
        // provider is built. The declared CT packages (def:Standards, §4.1) must be known when the
        // provider's CT selection is resolved, so the parse — which used to sit after
        // buildProvider — is hoisted here, and the ONE parsed model then feeds all three
        // consumers: the CT declaration, defineProvider, and vlmResolver. Never parse twice.
        //
        // Sponsor Define-XML metadata as an independent provider — the "define" level of the
        // three-level metadata model. Present only when a Define-XML was supplied (gated on
        // defineXmlPath; otherwise metadataLibrary is the data adapter, not a define). Carried for
        // the define_* operand family; consumed by the metadata-check evaluation path.
        MetadataProvider defineProvider = null;
        // F-corej-L2-07 — the third member of the metadata-basis family (Fix #369 / D13): when
        // the Define-XML fails to parse, the run degrades and the REPORT must say so, not only
        // the log. Null (and so absent from Conformance_Details) whenever the define parsed
        // cleanly or the run has none.
        String defineMetadataBasis = null;
        // The direct (ODM-backed) Define-XML provider, captured so the per-record value-level
        // metadata resolver (VlmResolver) can be built from the same parsed model.
        net.cumba.corej.core.gen.DefineXMLProvider directDefine = null;
        if (params.defineXmlPath() != null)
        {
            // The datatable-backed define provider serves dataset-level define metadata for every
            // define rule type (the fallback below).
            net.cumba.datatable.metadata.IMetadataLibrary defineMeta = manager
                    .getMetadataLibrary(metadataLibrary);
            MetadataProvider datatableDefine = defineMeta != null
                    ? MetadataLibraryProvider.forDefine(defineMeta)
                    : null;
            // Direct Define-XML access (PLAN-define-item-metadata-parity-929-1081): read the
            // define_* operands straight from the parsed Define-XML, bypassing the lossy
            // ODM -> IMetadataLibrary conversion that drops the codelist ccode / coded codes. An
            // explicit caller-supplied provider wins; otherwise parse the define.xml path directly.
            net.cumba.corej.core.gen.DefineXMLProvider direct = params.defineXmlProvider();
            if (direct == null)
            {
                direct = parseDefineXmlDirect(params.defineXmlPath());
                if (direct == null)
                {
                    defineMetadataBasis = datatableDefine != null
                            ? "datatable conversion — the Define-XML at " + params.defineXmlPath()
                                    + " could not be parsed directly; define metadata degraded to"
                                    + " the lossy ODM→datatable conversion (codelist C-codes and"
                                    + " coded codes absent) and value-level (VLM) rules were"
                                    + " SKIPPED"
                            : "unavailable — the Define-XML at " + params.defineXmlPath()
                                    + " could not be parsed and no datatable define metadata is"
                                    + " available; define-dependent rules were SKIPPED";
                    LOGGER.log(System.Logger.Level.WARNING, "Define metadata basis: {0}",
                            defineMetadataBasis);
                }
            }
            directDefine = direct;
            defineProvider = direct != null
                    ? new net.cumba.corej.core.metadata.DefineXmlMetadataProvider(direct,
                            datatableDefine)
                    : datatableDefine;
        }
        else if (params.defineXmlProvider() != null)
        {
            // No define.xml path but an explicit direct provider supplied (e.g. tests / embedding).
            directDefine = params.defineXmlProvider();
            defineProvider = new net.cumba.corej.core.metadata.DefineXmlMetadataProvider(
                    directDefine);
        }
        // Per-record value-level metadata resolver (Value Check against Define XML VLM). Built from
        // the same parsed model as defineProvider; null when no Define-XML (or no ValueListDef) is
        // present, so VLM rules SKIP via the DEFINE provider gate exactly like defineProvider.
        net.cumba.corej.core.metadata.VlmResolver vlmResolver = directDefine != null
                ? net.cumba.corej.core.metadata.VlmResolver.from(directDefine.metaDataVersion())
                : null;
        if (vlmResolver != null && !vlmResolver.structuralWarnings().isEmpty())
        {
            for (String w : vlmResolver.structuralWarnings())
            {
                LOGGER.log(System.Logger.Level.WARNING, "Define-XML value-level metadata: {0}", w);
            }
        }
        // §4.1 — the CT packages the Define-XML declares (empty for 2.0, no define, or a define
        // declaring none). Read once here; consulted by the CT selection and the §4.2 mismatch
        // note below.
        List<net.cumba.corej.core.gen.CtStandardRef> declaredCt = directDefine != null
                ? directDefine.declaredCtPackages()
                : List.of();

        // Phase B: build the run's metadata provider. Metadata source is the metadata-overlay
        // library when a define.xml accompanies the data library, otherwise the data library.
        // R7 — resolve the rule selection FIRST: the packages' declared standards join the
        // effective metadata-product list the provider is built from. This is also what retires
        // the hardwired ADaMIG->SDTMIG companion table (R9/R10): the companion is now a declared
        // product like any other.
        RuleSelection selection = selectRulePackages(params);
        List<String> effectiveProducts = effectiveMetadataProducts(params, selection.declared());
        requireDisambiguatedTigLeg(params.metadataProducts(), effectiveProducts);
        // R5 — the run's standard is DERIVED from the selected packages' declared primaries (or,
        // for a package that declares none, from the first --metadata-products entry). There is no
        // -s/-v to read any more.
        RunStandard runStandard = runStandardOf(selection.declared(), effectiveProducts);
        StandardKind kind = StandardKind.fromName(runStandard.standard());
        // §4.2 (D1) — resolve the run's CT selection: an explicit user selection wins outright
        // (the declaration is then out of play entirely — §4.4 row 6); a blank field takes the
        // define's declared set; with neither, the run has no CT (row 1: SKIP, never abort).
        CtSelection ctSelection = CtSelection.resolve(params.controlledTerminologyPackages(),
                declaredCt);
        if (ctSelection.source() == CtSelection.Source.DEFINE)
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "CT Packages is blank; using the Define-XML's declared CT set: {0}",
                    String.join(", ", ctSelection.packageIds()));
        }
        MetadataProvider provider = buildProvider(manager, metadataLibrary, params, kind,
                effectiveProducts, runStandard, ctSelection);
        // §4.2 — a divergence between what the define declares and what the run used is a
        // run-level note (a property of the RUN, never a finding on the data). Reported on every
        // divergent run, whichever way the precedence went.
        // CT-R3 (owner ruling 2026-09-09): the caller's resolution note (e.g. the manager's
        // <recent> downgrade) joins the SAME field -- engine text first, caller text appended,
        // either alone when the other is null; both null on an agreeing run keeps the field
        // absent, so every existing consumer of CT_Declaration_Mismatch is unchanged.
        String ctDeclarationMismatch = joinCtNotes(
                ctDeclarationMismatch(declaredCt, ctSelection.packageIds()),
                params.ctResolutionNote());
        if (ctDeclarationMismatch != null)
        {
            LOGGER.log(System.Logger.Level.WARNING, "CT declaration mismatch: {0}",
                    ctDeclarationMismatch);
        }

        // Phase C: load rule packages (selection already resolved in Phase B for R7).
        List<Rule> rules = loadRules(selection);
        rules = filterRules(rules, params);
        LOGGER.log(System.Logger.Level.INFO, "Selected {0} rule(s) for validation", rules.size());
        if (rules.isEmpty())
        {
            throw new StudyValidationException("no rules selected for validation.");
        }

        // Phase D: run validation. Datasets are validated one after the other so that the
        // per-rule runtime report is unambiguous (no overlapping rule timings between datasets).
        LOGGER.log(System.Logger.Level.INFO, "Rule worker threads per dataset: {0}{1}",
                params.ruleThreads(), params.ruleThreads() == 1 ? " (sequential)" : "");

        LibraryValidator.RuntimeListener listener = combinedListener(params.runtimeListener(),
                progress, params.cancellation());

        // §6.1 gap 2 — the provider-absence skip, decided HERE rather than discovered per rule
        // deep inside evaluation. A keyless run is the normal case for the population R2 targets,
        // so the run must be able to say what it cannot check before it starts checking.
        // ⚠ libraryAnswerable, not `provider != null`: a DEGRADED provider is non-null and cannot
        // serve LIBRARY-level reads (Fix #369), which is precisely the run with the most skips.
        net.cumba.corej.core.exec.ProviderRequirements.SkipForecast skipForecast = net.cumba.corej.core.exec.ProviderRequirements
                .forecast(rules,
                        net.cumba.corej.core.exec.OperationExecutor.libraryAnswerable(provider),
                        defineProvider != null);
        logSkipForecast(skipForecast);

        // Fix #218 — the run-level fact behind cross-standard SKIP. See crossStandardDatasets().
        Set<String> crossStandard = crossStandardDatasets(provider);
        if (!crossStandard.isEmpty())
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "Cross-standard datasets known to this run: {0}. A rule whose whole Check "
                            + "depends on one of them that was not supplied will report SKIPPED "
                            + "rather than PASS.",
                    crossStandard.size());
        }

        // Hoisted so the SAME provider instance feeds the run and the report (§2.5): the
        // Conformance dictionary-version fields and the Dictionary_Basis line below must describe
        // exactly what the rules could consult, never a re-resolved directory.
        // Phase 6b (D6): version selection, per type — CLI option > define.xml
        // ExternalCodeList/@Version > the store's selected-versions.json manifest > skip.
        // The first two merge here; the manifest fallback lives inside DictionaryStore.load.
        net.cumba.corej.core.metadata.RuntimeDictionaryProvider dictionaryProvider = buildDictionaryProvider(
                params.dictionariesDir(),
                requestedDictionaryVersions(params.dictionaryVersions(), directDefine));

        LibraryValidator.Builder vb = LibraryValidator.builder().provider(provider)
                .defineProvider(defineProvider).vlmResolver(vlmResolver)
                .dictionaryProvider(dictionaryProvider).rules(rules)
                .libraryUri(dataLibrary.getUri()).sequential(true).ruleThreads(params.ruleThreads())
                .maxErrorsPerRule(params.maxErrorsPerRule())
                .severityThreshold(params.severityThreshold()).runtimeListener(listener)
                .crossStandardDatasets(crossStandard).taskDecorator(params.taskDecorator());
        if (progress != null)
        {
            // Live per-dataset progress: the validator fires this as each dataset finishes (in
            // sequential mode, in target order on the orchestration thread).
            vb.datasetListener(progress::onDatasetCompleted);
        }
        for (DatasetEntry d : datasets)
        {
            vb.targetDataset(d.domain(), d.fileName(), d.tableSupplier());
        }
        for (ReferenceDataset ref : loaded.references())
        {
            vb.referenceDataset(ref.domain(), ref.supplier());
        }
        for (ReferenceDataset ref : externalReferences)
        {
            vb.referenceDataset(ref.domain(), ref.supplier());
        }
        LibraryValidator validator = vb.build();
        ValidationReport report = validator.validate();

        double elapsedSeconds = (System.currentTimeMillis() - start) / 1000.0;

        // Per-dataset completion is now delivered live from inside validator.validate() via the
        // dataset listener wired above — no end-of-run replay needed.

        // Phase E: assemble the result.
        // §4.2 — CT_Version reports what the run actually used, from whichever source the
        // selection resolved (a define-derived set must not report as "no CT").
        String ctVersion = ctSelection.packageIds().isEmpty() ? null
                : String.join(", ", ctSelection.packageIds());
        // Define-XML version: explicit param wins; otherwise read from the loaded library
        // metadata (Define-XML-backed libraries populate this; other library types return null).
        String defineXmlVersion = params.defineVersion() != null ? params.defineVersion()
                : provider.getDefineVersion();

        // Fix #369 — a degraded run must SAY SO in the report, not only in the log. Null (and so
        // absent from Conformance_Details) whenever the Library answered normally.
        String libraryMetadataBasis = null;
        if (provider.isLibraryUnavailable())
        {
            libraryMetadataBasis = net.cumba.corej.core.exec.OperationExecutor
                    .libraryAnswerable(provider)
                            ? "Define-XML (sponsor declarations) — the CDISC Library could not be "
                                    + "consulted for this run and -D"
                                    + net.cumba.corej.core.exec.OperationExecutor.DEGRADED_DEFINE_FALLBACK_PROPERTY
                                    + "=true was given"
                            // §6.1 gap 2 — the COUNT, not just the fact. Every library-dependent
                            // rule of this run is unanswerable in this branch (the library cannot
                            // be consulted at all), so the forecast's dependent count IS the
                            // skipped count; the per-rule ids go to the DEBUG log, not here.
                            : "unavailable — the CDISC Library could not be consulted for this"
                                    + " run; the " + skipForecast.libraryDependent()
                                    + " library-dependent rules in this run were SKIPPED";
            LOGGER.log(System.Logger.Level.WARNING, "Library metadata basis: {0}",
                    libraryMetadataBasis);
        }

        // §2.5 / D6 — the dictionary-version fields, from the provider the run actually consulted.
        // versionOf() is null for an unloaded type (and for a loaded dictionary that declares no
        // version), so a healthy report only names what really answered; the XLSX shows its
        // template default ("not configured") for the rest.
        // D13 item 1 — the run-level Dictionary_Basis line. Null (and so absent from
        // Conformance_Details) whenever every dictionary rule in this run could be answered;
        // under D12 the degraded state is the DEFAULT, so when non-null it must reach every
        // surface someone could read as "clean" — the JUL log here, the JSON report, the XLSX
        // Conformance Details sheet, the REST projection, and (Phase 6b) the CLI's stderr line
        // via ReportAssembler.Conformance#dictionaryBasis().
        String dictionaryBasis = dictionaryBasis(dictionaryProvider, rules);
        if (dictionaryBasis != null)
        {
            LOGGER.log(System.Logger.Level.WARNING, "Dictionary basis: {0}", dictionaryBasis);
        }

        ReportAssembler.Conformance conformance = ReportAssembler.Conformance.builder()
                .standard(runStandard.standard()).version(runStandard.version())
                .subStandard(CompanionSdtmDefaults.tigLeg(effectiveProducts))
                .tigUseCase(params.useCase()).ctVersion(ctVersion)
                .ctDeclarationMismatch(ctDeclarationMismatch).defineXmlVersion(defineXmlVersion)
                .libraryMetadataBasis(libraryMetadataBasis).dictionaryBasis(dictionaryBasis)
                .defineMetadataBasis(defineMetadataBasis)
                .uniiVersion(versionOf(dictionaryProvider, "unii"))
                .medRtVersion(versionOf(dictionaryProvider, "medrt"))
                .meddraVersion(versionOf(dictionaryProvider, "meddra"))
                .whodrugVersion(versionOf(dictionaryProvider, "whodrug"))
                .snomedVersion(versionOf(dictionaryProvider, "snomed"))
                .loincVersion(versionOf(dictionaryProvider, "loinc"))
                .neoplasmVersion(versionOf(dictionaryProvider, "neoplasm"))
                .totalRuntimeSeconds(elapsedSeconds).coreEngineVersion(coreEngineVersion).build();

        int findingCount = countFindings(report);
        LOGGER.log(System.Logger.Level.INFO,
                "Validation complete: {0} findings across {1} dataset(s) in {2}s.", findingCount,
                datasets.size(), String.format(Locale.ROOT, "%.2f", elapsedSeconds));

        return new StudyValidationResult(report, conformance, buildDatasetInfos(datasets), rules,
                findingCount, elapsedSeconds, validator.getExecutionSummaries(),
                validator.getGeneratedRules(), validator.getSdtmPrefixExpandedIds());
    }

    // ------------------------------------------------------------------
    // Cancellation + progress wiring
    // ------------------------------------------------------------------


    private static void checkCancelled(@Nullable BooleanSupplier cancellation, String phase)
    {
        if (cancellation != null && cancellation.getAsBoolean())
        {
            throw new CancelledException("study validation cancelled (" + phase + ")");
        }
    }


    /**
     * Combines the caller's runtime listener (e.g. the CLI's CSV writer), the progress listener and
     * the cancellation check into the single {@link LibraryValidator.RuntimeListener} the validator
     * accepts. The runtime listener fires on the validator's per-rule path; in {@code sequential}
     * mode this runs on the orchestration thread and is <em>not</em> caught by the validator's
     * dataset-load/rule-generation guards, so a {@link CancelledException} thrown from here
     * propagates straight out of {@code validate()} — aborting cleanly at the next rule boundary
     * (finer than a dataset boundary). The result is null only when all three inputs are null.
     */
    private static LibraryValidator.@Nullable RuntimeListener combinedListener(
            LibraryValidator.@Nullable RuntimeListener runtimeListener,
            @Nullable ProgressListener progress, @Nullable BooleanSupplier cancellation)
    {
        if (runtimeListener == null && progress == null && cancellation == null)
        {
            return null;
        }
        return entry ->
        {
            checkCancelled(cancellation, "during rule execution");
            if (runtimeListener != null)
            {
                runtimeListener.onRuleExecuted(entry);
            }
            if (progress != null)
            {
                progress.onRuleExecuted(entry);
            }
        };
    }


    /**
     * Normalises a dataset name or dataset-filter entry to the comparison key used when matching
     * the filter against library members: the part before the last {@code .} (extension stripped),
     * upper-cased. Semantics match how {@code FolderMember} derives a member name, so a filter
     * naming a file (e.g. {@code lb.csv}) and the bare member name ({@code LB}) compare equal.
     * Idempotent on already-bare names.
     */
    static String stripExtUpper(String s)
    {
        if (s == null)
        {
            return "";
        }
        int dot = s.lastIndexOf('.');
        String base = dot >= 0 ? s.substring(0, dot) : s;
        return base.toUpperCase(Locale.ROOT);
    }

    // ------------------------------------------------------------------
    // Library + dataset loading
    // ------------------------------------------------------------------


    private static void requireExists(Path path) throws IOException
    {
        if (!Files.exists(path))
        {
            throw new IOException("define.xml not found: " + path);
        }
    }


    /**
     * Parses the Define-XML at {@code path} into an ODM-backed
     * {@link net.cumba.corej.core.gen.DefineXMLProvider} for direct define-operand access. A
     * {@code <scheme>://} string is read as a URI; otherwise as a local file. Returns {@code null}
     * (and logs a warning) on any parse failure, so the run falls back to the datatable-backed
     * define metadata rather than aborting.
     */
    private static net.cumba.corej.core.gen.@Nullable DefineXMLProvider parseDefineXmlDirect(
            String path)
    {
        try
        {
            net.cumba.cdisc.define.ODM odm = path.contains("://")
                    ? new net.cumba.cdisc.define.DefineXmlParser().parse(URI.create(path))
                    : new net.cumba.cdisc.define.DefineXmlParser().parse(new File(path));
            return new net.cumba.corej.core.metadata.OdmDefineXMLProvider(odm);
        }
        catch (IOException | RuntimeException e)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Direct Define-XML parse failed for {0}; using datatable define metadata. {1}",
                    path, e);
            return null;
        }
    }


    /**
     * Resolves a path/URI to an {@link IDataTableLibraryRef} by dispatching through the manager's
     * library SPI. Accepts:
     * <ul>
     * <li>A {@code <scheme>://...} URI (e.g. {@code file://}, {@code ssh://}) — passed through
     * as-is.</li>
     * <li>A local directory or file path — converted to a {@code file://} URI via
     * {@link Path#toUri()}. The manager's SPI dispatches by URI extension / content marker to the
     * right reader (define.xml, XLSX, RDA, DataSet-JSON, directory-of-CSV, single SAS7BDAT,
     * etc.).</li>
     * </ul>
     * Throws {@link IOException} if a local path doesn't exist or the manager cannot resolve the
     * library.
     */
    private static IDataTableLibraryRef resolveLibrary(IDataTableManager manager, String pathOrUri)
        throws IOException
    {
        if (pathOrUri == null || pathOrUri.isEmpty())
        {
            throw new IOException("library path is empty");
        }
        URI uri;
        if (pathOrUri.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*"))
        {
            uri = URI.create(pathOrUri);
        }
        else
        {
            Path p = Path.of(pathOrUri);
            if (!Files.exists(p))
            {
                throw new IOException("path not found: " + p);
            }
            uri = p.toUri();
        }
        IDataTableLibraryRef ref = manager.getLibraryRef(uri, null);
        if (ref == null)
        {
            throw new IOException(
                    "no library reader available for: " + uri + " (unsupported file format?)");
        }
        return ref;
    }


    /**
     * Resolves the set of library files that must never be enumerated as datasets: the configured
     * {@link StudyValidationParams#defineXmlPath()} and every
     * {@link StudyValidationParams#rulesFiles()} entry. Paths are absolute + normalized so they
     * compare equal to the {@code file://} member URIs produced by the folder library.
     * Reference-data files are deliberately not excluded — they are meant to be loaded as tables.
     */
    private static Set<Path> excludedLibraryFiles(StudyValidationParams params)
    {
        Set<Path> out = new LinkedHashSet<>();
        if (params.defineXmlPath() != null)
        {
            out.add(Path.of(params.defineXmlPath()).toAbsolutePath().normalize());
        }
        for (String rulesFile : params.rulesFiles())
        {
            if (rulesFile != null && !rulesFile.isBlank())
            {
                out.add(Path.of(rulesFile).toAbsolutePath().normalize());
            }
        }
        return out;
    }


    private static LoadedLibrary loadDatasets(IDataTableManager manager,
            IDataTableLibraryRef library, Set<String> targetFilter, Set<Path> excludedFiles)
        throws IOException
    {
        // Normalise filter — empty means "validate every member". Filter entries are normalised the
        // same way library member names are derived (extension stripped, upper-cased; see
        // FolderMember), so a filter naming a file with its extension (e.g. "lb.csv", what the web
        // UI sends) matches the bare member name ("LB") and the run is not aborted with
        // "--dataset filter matched no library members".
        Set<String> filterUpper = new LinkedHashSet<>();
        for (String s : targetFilter)
        {
            filterUpper.add(stripExtUpper(s));
        }
        Set<String> matched = new LinkedHashSet<>();

        List<DatasetEntry> targets = new ArrayList<>();
        List<ReferenceDataset> references = new ArrayList<>();
        for (ILibraryMemberRef member : manager.getLibraryMembers(library).toList())
        {
            String name = member.getName();
            String upper = stripExtUpper(name);
            boolean isTarget = filterUpper.isEmpty() || filterUpper.contains(upper);

            // File-system metadata is cheap to capture without loading the table.
            URI uri = member.getUri() != null ? URI.create(member.getUri()) : null;

            // Skip files designated as the define.xml / rules file(s): they are not data tables
            // and must not be opened as one (a rules *.json otherwise collides with DataSet-JSON).
            if (!excludedFiles.isEmpty() && uri != null && "file".equalsIgnoreCase(uri.getScheme()))
            {
                Path memberPath = Path.of(uri).toAbsolutePath().normalize();
                if (excludedFiles.contains(memberPath))
                {
                    LOGGER.log(System.Logger.Level.INFO,
                            "  [skipped] {0} — designated define.xml/rules file, not a dataset",
                            name);
                    continue;
                }
            }

            String fileName = fileNameOf(uri);
            long fileSize = 0;
            String modificationDate = null;
            String parentPath = null;
            if (uri != null && "file".equalsIgnoreCase(uri.getScheme()))
            {
                Path p = Path.of(uri);
                if (Files.exists(p))
                {
                    fileSize = Files.size(p);
                    modificationDate = LocalDateTime
                            .ofInstant(Files.getLastModifiedTime(p).toInstant(),
                                    ZoneId.systemDefault())
                            .withNano(0).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME);
                    Path parent = p.getParent();
                    parentPath = parent != null ? parent.toString() : null;
                }
            }

            // Soft-cached supplier: loads the table on first call, holds it via SoftReference so
            // the JVM can reclaim it between dataset runs under heap pressure. Failure to load
            // (corrupt file, etc.) propagates as UncheckedIOException — the validator catches
            // and records a per-dataset warning so other datasets still validate.
            Supplier<IDataTable> rawLoad = () ->
            {
                try
                {
                    IDataTableRef tableRef = manager.getDataTableRef(member, null);
                    return manager.getDataTable(tableRef);
                }
                catch (IOException e)
                {
                    throw new UncheckedIOException(e);
                }
            };

            if (isTarget)
            {
                targets.add(new DatasetEntry(name, fileName, parentPath, fileSize, modificationDate,
                        rawLoad));
                matched.add(upper);
                LOGGER.log(System.Logger.Level.INFO, "  [target] {0} (loaded on first access)",
                        name);
            }
            else
            {
                // Reference: same soft cache, but never iterated as a validation target.
                references.add(new ReferenceDataset(name, softMemoised(rawLoad)));
                LOGGER.log(System.Logger.Level.INFO, "  [reference] {0} (loaded only if needed)",
                        name);
            }
        }

        // Warn about filter entries that didn't match any member.
        for (String requested : filterUpper)
        {
            if (!matched.contains(requested))
            {
                LOGGER.log(System.Logger.Level.WARNING,
                        "--dataset {0} did not match any library member.", requested);
            }
        }
        return new LoadedLibrary(targets, references);
    }


    /**
     * Loads a reference library (e.g. SDTM data when validating ADaM). The path may be either a
     * directory containing {@code define.xml} or a {@code define.xml} file directly. Every member
     * of the library becomes a lazily-loaded reference dataset on the validator. Domain names
     * already present in the target library or in the in-library reference set are skipped (with a
     * warning) — targets must take precedence over external references.
     */
    private static List<ReferenceDataset> loadReferenceLibrary(IDataTableManager manager,
            String refPath, List<DatasetEntry> targetDatasets, LoadedLibrary inLibraryRefs)
        throws IOException
    {
        // Phase 6: same library-resolution shape as the primary data library. Accepts any path /
        // file / URI; the manager's SPI dispatches by extension or content marker.
        IDataTableLibraryRef refLibrary = resolveLibrary(manager, refPath);
        LOGGER.log(System.Logger.Level.INFO, "Loading reference library: {0}", refPath);

        Set<String> alreadyKnown = new LinkedHashSet<>();
        for (DatasetEntry d : targetDatasets)
        {
            alreadyKnown.add(d.domain().toUpperCase(Locale.ROOT));
        }
        for (ReferenceDataset r : inLibraryRefs.references())
        {
            alreadyKnown.add(r.domain().toUpperCase(Locale.ROOT));
        }

        List<ReferenceDataset> out = new ArrayList<>();
        for (ILibraryMemberRef member : manager.getLibraryMembers(refLibrary).toList())
        {
            String name = member.getName();
            String upper = name == null ? "" : name.toUpperCase(Locale.ROOT);
            if (alreadyKnown.contains(upper))
            {
                LOGGER.log(System.Logger.Level.INFO,
                        "  [reference-data] skipped {0} — domain already loaded from primary library",
                        name);
                continue;
            }
            alreadyKnown.add(upper);
            out.add(new ReferenceDataset(name, softMemoised(() ->
            {
                try
                {
                    IDataTableRef ref = manager.getDataTableRef(member, null);
                    return manager.getDataTable(ref);
                }
                catch (IOException e)
                {
                    throw new UncheckedIOException(e);
                }
            })));
            LOGGER.log(System.Logger.Level.INFO, "  [reference-data] {0} (loaded only if needed)",
                    name);
        }
        return out;
    }


    /**
     * Memoised supplier whose cached value is held via {@link SoftReference}, so the JVM can
     * reclaim it under heap pressure. On the next call after eviction the delegate is invoked again
     * and the result re-cached. Single-load semantics under contention via a synchronised
     * {@code get()} — uncontended monitor entry is cheap, and the validator only consults each
     * supplier a handful of times across a run.
     */
    static <T> Supplier<T> softMemoised(Supplier<T> delegate)
    {
        return new Supplier<>()
        {

            private @Nullable SoftReference<T> ref;

            @Override
            public synchronized T get()
            {
                T cached = ref != null ? ref.get() : null;
                if (cached != null)
                {
                    return cached;
                }
                T loaded = delegate.get();
                ref = new SoftReference<>(loaded);
                return loaded;
            }
        };
    }


    /**
     * Builds the per-dataset metadata block for the JSON report. Reads from the
     * {@link DatasetMetadataSnapshot} populated by the validator's first table load — under normal
     * operation the snapshot is already filled and this method does no I/O. If a target was
     * filtered out before validation, {@link DatasetEntry#metadata()} forces a load.
     */
    private static List<ReportAssembler.DatasetInfo> buildDatasetInfos(List<DatasetEntry> datasets)
    {
        List<ReportAssembler.DatasetInfo> out = new ArrayList<>(datasets.size());
        for (DatasetEntry d : datasets)
        {
            DatasetMetadataSnapshot meta;
            try
            {
                meta = d.metadata();
            }
            catch (RuntimeException e)
            {
                // The dataset could not be opened as a table (already recorded as an ERROR finding
                // during validation). Emit a best-effort info row with no label/rows so the run
                // still completes rather than aborting at report assembly.
                meta = new DatasetMetadataSnapshot(null, 0L, 0);
            }
            out.add(new ReportAssembler.DatasetInfo(d.fileName(), meta.label(), d.parentPath(),
                    d.modificationDate(), d.fileSize() / 1000.0, meta.rowCount(), d.domain(),
                    meta.columnCount()));
        }
        return out;
    }

    // ------------------------------------------------------------------
    // External-dictionary provider (T1)
    // ------------------------------------------------------------------


    /**
     * T1 — builds the runtime external-dictionary provider from the installed dictionary store.
     *
     * <p>
     * The directory is resolved by {@code DictionaryDirectoryResolver}: the caller's explicit
     * directory ({@link StudyValidationParams#dictionariesDir()}, the CLI's
     * {@code --dictionaries-dir}, carried per-run exactly like {@code dictionaryVersions}) &gt;
     * {@code COREJ_DICTIONARIES_DIR} &gt; the {@code corej.dictionariesDir} system property &gt;
     * the conventional {@code ./dictionaries}. The explicit directory MUST travel as the resolver's
     * first argument — handing it over as the system property instead would rank it <em>below</em>
     * the environment variable, which every Docker image sets, so the flag would silently lose to
     * the container default while install mode honoured it. A <em>configured</em> directory that
     * does not exist is a hard error, rethrown as {@link StudyValidationException} so every caller
     * surfaces it as the operational error it is (the CLI's {@code Error:} line and exit 2) rather
     * than a raw stack trace; the conventional default is presence-gated.
     * </p>
     *
     * <p>
     * No directory, an unreadable one, or a store in which no version is selected all yield a
     * provider without that dictionary — every dependent rule then SKIPs, a declared
     * ({@code $}-ref) operation through {@code RuleRunner}'s eager dictionary arm
     * ({@code Fix #268}) and an inlined one through its injected
     * {@code dictionary_available(<type>)} precondition — never false-passing.
     * </p>
     */
    static net.cumba.corej.core.metadata.@Nullable RuntimeDictionaryProvider buildDictionaryProvider(
            @Nullable String explicitDir, Map<String, String> requestedVersions)
    {
        Optional<Path> dir;
        try
        {
            dir = net.cumba.corej.core.metadata.dictionary.DictionaryDirectoryResolver
                    .resolve(explicitDir);
        }
        catch (IllegalStateException e)
        {
            // D4's hard error stays hard — a typo'd configured store must kill the run, not
            // degrade to a clean-looking report — but it must reach the user as the CLI's
            // usage-style "Error: ..." line, not as an uncaught stack trace.
            throw new StudyValidationException(
                    java.util.Objects.requireNonNullElse(e.getMessage(), e.toString()), e);
        }
        if (dir.isEmpty())
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "No dictionary directory configured or present — dictionary rules will SKIP. "
                            + "Install dictionaries and point at them with --dictionaries-dir, "
                            + "COREJ_DICTIONARIES_DIR or -Dcorej.dictionariesDir.");
            return null;
        }
        try
        {
            return net.cumba.corej.core.metadata.dictionary.DictionaryStore.load(dir.get(),
                    requestedVersions);
        }
        catch (IOException e)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Could not load external dictionaries from {0}: {1} — dictionary rules will SKIP",
                    dir.get(), e.getMessage());
            return null;
        }
    }


    /**
     * D6 — merges the two caller-side version-selection sources, CLI over define.xml, into the
     * {@code requested} map {@code DictionaryStore.load} binds with (the manifest sits below both,
     * inside the store). A define.xml states how the study was actually coded, so it outranks the
     * install-time manifest; the CLI option stays the escape hatch for a define believed wrong.
     *
     * @param cliVersions
     *            versions the caller named explicitly (lower-cased type &rarr; version)
     * @param directDefine
     *            the run's parsed Define-XML provider, or {@code null} when none was supplied
     */
    static Map<String, String> requestedDictionaryVersions(Map<String, String> cliVersions,
            net.cumba.corej.core.gen.@Nullable DefineXMLProvider directDefine)
    {
        Map<String, String> merged = new java.util.LinkedHashMap<>();
        if (directDefine != null)
        {
            merged.putAll(directDefine.externalDictionaryVersions());
        }
        for (Map.Entry<String, String> e : cliVersions.entrySet())
        {
            if (!e.getValue().isBlank())
            {
                merged.put(e.getKey().toLowerCase(Locale.ROOT), e.getValue());
            }
        }
        return merged;
    }


    /** The loaded release of {@code type}, or {@code null} — null-provider tolerant. */
    private static @Nullable String versionOf(
            net.cumba.corej.core.metadata.@Nullable RuntimeDictionaryProvider dict, String type)
    {
        return dict == null ? null : dict.versionOf(type);
    }


    /**
     * The one up-front line about what this run cannot check — {@code §6.1} gap 2 of
     * {@code plans/PLAN-metadata-cache-unification.md}.
     *
     * <p>
     * ⭐ It is emitted <b>before</b> the first dataset is validated, and it is silent when nothing
     * will skip. The per-rule SKIPPED statuses still carry the detail; what was missing was the
     * total, at a point where the user can still act on it (supply a cache, supply a define.xml)
     * instead of reading it out of a finished report.
     * </p>
     *
     * @param forecast
     *            the forecast for this run's selected rules
     */
    private static void logSkipForecast(
            net.cumba.corej.core.exec.ProviderRequirements.SkipForecast forecast)
    {
        if (forecast.skippedRuleCount() == 0)
        {
            return;
        }
        LOGGER.log(System.Logger.Level.INFO,
                "{0} rule(s) will be SKIPPED for want of metadata: {1} of {2} library-dependent"
                        + " (no CDISC Library metadata available to this run), {3} of {4}"
                        + " define-dependent (no Define-XML supplied)",
                forecast.skippedRuleCount(), forecast.library().size(), forecast.libraryDependent(),
                forecast.define().size(), forecast.defineDependent());
        LOGGER.log(System.Logger.Level.DEBUG, "  library-dependent, skipped: {0}",
                forecast.library());
        LOGGER.log(System.Logger.Level.DEBUG, "  define-dependent, skipped: {0}",
                forecast.define());
    }


    /**
     * D13 item 1 / D6 — the run-level {@code Dictionary_Basis} line: which dictionary types loaded
     * (with versions), which required ones did not and <em>why</em> (the same operator-actionable
     * diagnosis the per-rule SKIP reasons carry), and how many of this run's dictionary rules could
     * actually be answered.
     *
     * <p>
     * Null — and therefore absent from {@code Conformance_Details}, following the
     * {@code Library_Metadata_Basis} precedent ({@code Fix #369}) — when every dictionary rule in
     * the run is answerable, including the trivial case of a run selecting no dictionary rules. A
     * dictionary rule here is one declaring a {@code valid_external_dictionary_*} /
     * {@code dictionary_has_decode} operation; the {@code dictionary_available} gate is not
     * counted, because a rule gated by it is <em>designed</em> to answer either way. Operations
     * authored inline are not walked: no shipped rule inlines one, a typeless one is a load error
     * ({@code RulePackageLoader.validateDictionaryOperationTypes}), and a typed inline one is
     * self-gating — its rule answers either way, like the gate itself.
     * </p>
     *
     * @param dict
     *            the provider the run consulted, may be {@code null} (no directory configured)
     * @param rules
     *            the rules selected for this run (post-filter)
     */
    static @Nullable String dictionaryBasis(
            net.cumba.corej.core.metadata.@Nullable RuntimeDictionaryProvider dict,
            List<Rule> rules)
    {
        int dictionaryRules = 0;
        int answerable = 0;
        Set<String> unavailableNeeded = new java.util.TreeSet<>();
        for (Rule rule : rules)
        {
            Set<String> needed = requiredDictionaryTypes(rule);
            if (needed.isEmpty())
            {
                continue;
            }
            dictionaryRules++;
            boolean ok = true;
            for (String type : needed)
            {
                if (dict == null || !dict.isAvailable(type))
                {
                    ok = false;
                    unavailableNeeded.add(type);
                }
            }
            if (ok)
            {
                answerable++;
            }
        }
        if (dictionaryRules == 0 || answerable == dictionaryRules)
        {
            return null;
        }
        StringBuilder basis = new StringBuilder("external dictionaries degraded: ")
                .append(answerable).append(" of ").append(dictionaryRules)
                .append(" dictionary rules in this run were answerable, the rest SKIPPED.")
                .append(" Loaded: ");
        if (dict == null || dict.loadedTypes().isEmpty())
        {
            basis.append("none");
        }
        else
        {
            boolean first = true;
            for (String type : dict.loadedTypes())
            {
                basis.append(first ? "" : ", ").append(type);
                first = false;
                String version = dict.versionOf(type);
                if (version != null && !version.isBlank())
                {
                    basis.append(' ').append(version);
                }
            }
        }
        basis.append(". Not loaded: ");
        boolean first = true;
        for (String type : unavailableNeeded)
        {
            basis.append(first ? "" : "; ").append("external dictionary ").append(type).append(' ')
                    .append(dict == null
                            ? net.cumba.corej.core.metadata.RuntimeDictionaryProvider
                                    .notInstalledDetail()
                            : dict.unavailabilityDetail(type));
            first = false;
        }
        return basis.toString();
    }


    /**
     * The external-dictionary types this rule's declared operations require — empty for a
     * non-dictionary rule. Typeless dictionary operations contribute nothing (they are a load
     * error, and such a rule reports ERROR, not SKIP); {@code dictionary_available} is the gate,
     * not a requirement.
     */
    private static Set<String> requiredDictionaryTypes(Rule rule)
    {
        List<net.cumba.corej.core.model.Operation> ops = rule.getOperations();
        if (ops == null || ops.isEmpty())
        {
            return Set.of();
        }
        Set<String> needed = new LinkedHashSet<>();
        for (net.cumba.corej.core.model.Operation op : ops)
        {
            net.cumba.corej.core.model.OperationType type = op.getOperationType();
            if (type == net.cumba.corej.core.model.OperationType.DICTIONARY_AVAILABLE
                    || !net.cumba.corej.core.exec.OperationExecutor.isDictionaryDependent(type))
            {
                continue;
            }
            String dictionaryType = op.getExternalDictionaryType();
            if (dictionaryType != null && !dictionaryType.isBlank())
            {
                needed.add(dictionaryType.toLowerCase(Locale.ROOT));
            }
        }
        return needed;
    }

    // ------------------------------------------------------------------
    // CDISC Library enrichment
    // ------------------------------------------------------------------


    private MetadataProvider buildProvider(IDataTableManager manager, IDataTableLibraryRef library,
            StudyValidationParams params, StandardKind kind, List<String> effectiveProducts,
            RunStandard runStandard, CtSelection ctSelection)
        throws IOException
    {
        // Cache P4 (PLAN-metadata-cache-unification.md §6, ruling R2): the CDISC Library API
        // path was CUT here — a validation run can no longer reach the network, full stop. The
        // unified metadata store (CDISC_METADATA_STORE / cdisc.metadata.store) is the one
        // metadata source; without one that serves this run, the run degrades and its
        // library-dependent rules SKIP (the P0b forecast reports them up front).
        MetadataProvider stored = tryStoreProvider(params, kind, effectiveProducts, runStandard,
                ctSelection);
        if (stored != null)
        {
            return maybeWrapCompanion(stored, params, kind, effectiveProducts, null);
        }
        // The offline pickle leg that used to sit here (tryPickleProvider) was deleted by cache
        // 8g, once cumba-corej-rules' harness moved onto the store: the unified metadata store is
        // the ONE metadata source of a validation run. A pickle-configured deployment migrates by
        // seeding a store from its pickle directory (PickleStoreSeeder — the seeding surfaces are
        // P4b's cross-repo work).
        if (kind == StandardKind.UNKNOWN)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Standard {0} not supported for metadata enrichment. "
                            + "Running without enrichment.",
                    runStandard.standard());
            return maybeWrapCompanion(
                    new MetadataLibraryProvider(requireMetadataLibrary(manager, library)), params,
                    kind, effectiveProducts, null);
        }
        // R2: no store configured, or the configured one cannot serve this run. Degrade loudly —
        // library-dependent rules SKIP with this cause — instead of falling back to the network
        // (the pre-P4 behaviour for exactly this situation was a doomed "dummy"-key API attempt
        // that ended in the same degraded provider, minus the honest message).
        MetadataProvider degraded = MetadataLibraryProvider.degraded(
                requireMetadataLibrary(manager, library),
                new IOException("No unified metadata store is available for "
                        + runStandard.standard() + " " + runStandard.version() + " (configure "
                        + net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory.STORE_ENV
                        + " / "
                        + net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory.STORE_PROPERTY
                        + " and seed it); library-dependent rules will SKIP"));
        return maybeWrapCompanion(degraded, params, kind, effectiveProducts, null);
    }


    /**
     * The study's metadata library, or a diagnosis.
     *
     * <p>
     * {@link IDataTableManager#getMetadataLibrary} is {@code @Nullable} by contract — the interface
     * <em>default</em> returns {@code null} outright, and only a manager that overrides it answers
     * at all. Both enrichment-free legs of {@link #buildProvider} fed the result straight into
     * {@link MetadataLibraryProvider}, whose constructor ends in
     * {@code Objects.requireNonNull(aLibrary, "library")} — so a manager without metadata support
     * failed a clinical run with a bare {@code NullPointerException: library}, naming neither the
     * manager nor the study. NullAway is what surfaced it.
     * </p>
     *
     * <p>
     * ⚠ Not a silent degrade: every query the engine makes about the study's own columns goes
     * through this library, so a run without one cannot answer anything and must stop. The shipped
     * {@code LocalDataTableManager} always has one (it falls back to a column-metadata adapter over
     * the library itself), which is why the hole was latent rather than observed.
     * </p>
     *
     * @param aManager
     *            the manager serving the run
     * @param aLibrary
     *            the study library being validated
     * @return the attached metadata library, never {@code null}
     * @throws IOException
     *             when the manager attaches none
     */
    private static net.cumba.datatable.metadata.IMetadataLibrary requireMetadataLibrary(
            IDataTableManager aManager, IDataTableLibraryRef aLibrary)
        throws IOException
    {
        net.cumba.datatable.metadata.IMetadataLibrary metadata = aManager
                .getMetadataLibrary(aLibrary);
        if (metadata == null)
        {
            throw new IOException("the data table manager " + aManager.getClass().getName()
                    + " attaches no metadata library to this study; a validation run needs one "
                    + "to read the study's own column metadata");
        }
        return metadata;
    }


    /**
     * {@code Fix #218} ({@code plans/PLAN-cross-standard-absence-skip.md}) — the dataset names that
     * belong to a CDISC standard <b>this run does not validate</b>.
     *
     * <p>
     * The owner's invocation ruling is that <i>"when ADaM is validated, SDTM is made available for
     * the cross-standard checks ONLY"</i>. ⇒ SKIP for a rule such as {@code CDISC-AD0204}
     * (<code>var_exists(DM.AGE) and AGE != DM.AGE</code>) must engage on <b>"SDTM was not
     * supplied"</b> — a property of the <em>invocation</em> — and never on <i>"an ADaM package
     * reports DM missing"</i>, which a package-scoped precondition structurally cannot express.
     * </p>
     *
     * <p>
     * ⚑ The catalogue already exists and is already ADaM-conditional: {@link #maybeWrapCompanion}
     * wraps the run provider in a {@link CompanionDomainsProvider} <b>iff</b> the run is
     * ADaM-family <b>and</b> a companion SDTM product resolved, and that decorator's <em>only</em>
     * overridden accessor is {@link MetadataProvider#getStandardDatasetNames()}, answered from the
     * companion SDTMIG product (EC-14 layer (ii)). So the {@code instanceof} below is the precise
     * test: on a non-ADaM run the same accessor returns the run's <b>own</b> standard's datasets,
     * which must never be treated as foreign, and the {@code instanceof} excludes exactly that
     * case.
     * </p>
     *
     * <p>
     * ⚠ Degrades to an empty set — i.e. to the pre-{@code Fix #218} engine — when no companion
     * product is available (no store, or the store lacks it). {@link #maybeWrapCompanion} already
     * logs a WARNING there, so the degradation is never silent.
     * </p>
     *
     * @param provider
     *            the run's metadata provider, as returned by {@code buildProvider}
     * @return the upper-cased cross-standard dataset names; empty when the run has none
     */
    static Set<String> crossStandardDatasets(@Nullable MetadataProvider provider)
    {
        if (!(provider instanceof CompanionDomainsProvider companion))
        {
            return Set.of();
        }
        List<String> names = companion.getStandardDatasetNames();
        if (names == null || names.isEmpty())
        {
            return Set.of();
        }
        Set<String> out = new LinkedHashSet<>();
        for (String name : names)
        {
            if (name != null && !name.isBlank())
            {
                out.add(name.trim().toUpperCase(Locale.ROOT));
            }
        }
        return Set.copyOf(out);
    }


    /**
     * EC-14 layer (ii) — on an ADaM-family run, wrap {@code base} in a
     * {@link CompanionDomainsProvider} so the {@code standard_domains} operation enumerates the
     * companion SDTM product's domains instead of the (empty) ADaM set. Non-ADaM runs are returned
     * unchanged. Package-private static so the seam is unit-testable without a full run.
     *
     * <p>
     * ⛔ <b>This is the whole surface a declared SDTM product touches on an ADaM run</b> (plan
     * §2.4): {@link CompanionDomainsProvider} overrides {@code getStandardDatasetNames()} and
     * delegates everything else, so declaring {@code sdtmig/3-1-1} changes which product answers
     * that one accessor and nothing else. It is never injected into the ADaM
     * {@code MetadataLibraryProvider}, whose required/expected/column-order accessors branch on
     * {@code hasSdtmProduct()}.
     * </p>
     *
     * @param base
     *            the run's metadata provider.
     * @param params
     *            the run parameters (standard / version / declared metadata products).
     * @param kind
     *            the resolved {@link StandardKind}.
     * @param apiLoader
     *            extra fallback companion loader consulted after the store leg; {@code null} in
     *            production since cache P4 cut the CDISC Library API path (the parameter survives
     *            as the injection seam existing tests — including {@code cumba-corej-rules}'
     *            companion test — drive this method through).
     * @return {@code base}, or a {@link CompanionDomainsProvider} wrapping it.
     */
    static MetadataProvider maybeWrapCompanion(MetadataProvider base, StudyValidationParams params,
            StandardKind kind, List<String> effectiveProducts,
            @Nullable Function<CompanionSdtmDefaults.Companion, @Nullable MetadataProvider> apiLoader)
    {
        boolean adamFamily = kind == StandardKind.ADAM || isTigAdamRun(effectiveProducts);
        if (!adamFamily)
        {
            return base;
        }
        CompanionSdtmDefaults.Companion c = CompanionSdtmDefaults.resolve(effectiveProducts);
        if (c == null)
        {
            // R10 — no declared companion means NO companion. The engine already logs the
            // consequence loudly below; there is deliberately no "newest SDTMIG" guess any more.
            LOGGER.log(System.Logger.Level.WARNING,
                    "No companion SDTM product is declared or requested; standard_domains rules "
                            + "will SKIP. Declare one via --metadata-products, or use a rules "
                            + "package that declares a companion.");
            return base;
        }
        // ⚑ The Q-12d "defaulted to the newest SDTMIG" warning lived here. R10 deleted the
        // fallback itself, so nothing can set Companion.defaulted() any more and the branch went
        // with it; a run with no declared companion is reported above, before this point.
        MetadataProvider companion = companionFromStore(c, params.metadataStore());
        if (companion == null && apiLoader != null)
        {
            companion = apiLoader.apply(c);
        }
        if (companion == null)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Companion SDTM product {0} unavailable; standard_domains rules will SKIP.",
                    c.display());
            return base;
        }
        // Q-12g: surface the effective companion version and where it came from. ⚠ A user's own
        // --metadata-products declaration must not be reported as a "house default mapping":
        // ruling 6 makes the table a fallback, and the log has to say which branch answered.
        LOGGER.log(System.Logger.Level.INFO, "Companion SDTM domains for standard_domains: {0}{1}.",
                c.display(), companionOrigin(c));
        return new CompanionDomainsProvider(base, companion);
    }


    /** How the companion in {@code aCompanion} was chosen, for the run log. */
    private static String companionOrigin(CompanionSdtmDefaults.Companion aCompanion)
    {
        if (aCompanion.declared())
        {
            return " (declared metadata product)";
        }
        return aCompanion.defaulted() ? " (defaulted)" : " (house default mapping)";
    }


    /**
     * <b>Review finding R-2 / ruling V2</b> — a rule package declaring MORE THAN ONE TIG leg must
     * be disambiguated by an explicit {@code --metadata-products}; the run fails loudly otherwise.
     *
     * <p>
     * ⛔⛔ <b>What this replaces: a loud SKIP that had silently become a vacuous PASS.</b> The two
     * shipped TIG packages declare four primaries — {@code tig/1-0/}{@code {adam,cdash,sdtm,send}}
     * — with {@code adam} FIRST. R7 appends all four to the effective product list, and
     * {@code CompanionSdtmDefaults.tigLeg} returns the leg of the FIRST TIG key it sees, so
     * {@code isTigAdamRun} answered true and the then-current pickle provider leg (now the store
     * path) routed the whole run onto the ADaM leg. An SDTM-shaped TIG run then resolved every SDTM
     * domain against an ADaM provider, which returns empty for all of them (Fix #373) — so rules
     * that used to SKIP visibly reported "executed, no findings" instead.
     * </p>
     *
     * <p>
     * ⚠ Before Phase 3 the list was just {@code [standards/tig/1-0]}, which has no leg, so this
     * could not arise: the provider declined, the run degraded, and the SKIP was visible. The
     * regression came in with the declarations, not with the routing.
     * </p>
     *
     * <p>
     * ⚑ Genuine mixed-family routing is "Proposal A" (§1), which this plan deliberately does not
     * build. Until it exists, guessing a family from declaration ORDER is the one thing we must not
     * do, so the run asks the user instead.
     * </p>
     */
    static void requireDisambiguatedTigLeg(List<String> userProducts,
            List<String> effectiveProducts)
    {
        Set<String> legs = new LinkedHashSet<>();
        for (String key : effectiveProducts)
        {
            String leg = tigLegOfLoose(key);
            if (leg != null)
            {
                legs.add(leg);
            }
        }
        if (legs.size() <= 1)
        {
            return;
        }
        for (String product : userProducts)
        {
            if (tigLegOfLoose(product) != null)
            {
                return; // the user named a leg — that choice governs (R7 puts it first).
            }
        }
        Set<String> declaredLegs = legs;
        throw new StudyValidationException("The selected rule package(s) declare more than one TIG "
                + "leg " + declaredLegs + ", so the run cannot tell which standard to resolve "
                + "metadata against. Name one with -mp / --metadata-products (for example "
                + "'tig/1-0/sdtm'). Choosing for you would silently decide the run's whole "
                + "metadata family from the order the legs happen to be declared in.");
    }


    /**
     * {@code MetadataProductKeys.tigLegOf} for a key in EITHER spelling.
     *
     * <p>
     * ⚠ Its {@code TIG_LEG_KEY} pattern requires the {@code standards/} namespace, but a package's
     * declared id is stored bare ({@code tig/1-0/adam}) and a user's {@code -mp} token may be
     * either. Reading a declared id with the strict form silently answers "no leg" for every
     * package — which is exactly how the first cut of this guard failed to fire.
     * </p>
     */
    private static @Nullable String tigLegOfLoose(@Nullable String idOrKey)
    {
        if (idOrKey == null || idOrKey.isBlank())
        {
            return null;
        }
        String key = idOrKey.startsWith("standards/") ? idOrKey : "standards/" + idOrKey;
        return MetadataProductKeys.tigLegOf(key);
    }


    /**
     * True when this is a TIG ADaM run — a declared metadata product key
     * {@code standards/tig/<v>/adam}, the successor of the removed {@code -s tig -ss adam} form.
     *
     * <p>
     * ⚠ This answers "is the FIRST declared TIG leg the adam one", not "is an adam leg declared"
     * (see {@code CompanionSdtmDefaults.tigLeg}). {@link #requireDisambiguatedTigLeg} is what stops
     * that distinction deciding a multi-leg run silently.
     * </p>
     */
    private static boolean isTigAdamRun(List<String> effectiveProducts)
    {
        return CompanionSdtmDefaults.declaresTigAdam(effectiveProducts);
    }


    /**
     * Builds a metadata provider from the unified metadata store when one is configured
     * ({@code CDISC_METADATA_STORE} / {@code cdisc.metadata.store}) and it carries the run's
     * products. Returns {@code null} — the caller then degrades the run (cache P4 / ruling R2:
     * library-dependent rules SKIP; there is no other metadata path any more) — when no store is
     * configured, the store cannot be opened, or it lacks a needed product. Package-private static
     * for unit testing.
     *
     * <p>
     * {@code PUBLISHED_CT_PACKAGES} on this path is the store's whole published enumeration on
     * every family (plan §1.1-1).
     * </p>
     */
    static @Nullable MetadataProvider tryStoreProvider(StudyValidationParams params,
            StandardKind kind, List<String> effectiveProducts, RunStandard runStandard)
    {
        // The pre-P4 (define-ct) entry point: the CT selection is the user's field alone. Kept
        // delegating — cumba-corej-rules' test tree (read-only to this lane) calls this shape.
        return tryStoreProvider(params, kind, effectiveProducts, runStandard,
                CtSelection.resolve(params.controlledTerminologyPackages(), List.of()));
    }


    /**
     * As {@link #tryStoreProvider(StudyValidationParams, StandardKind, List, RunStandard)}, with
     * the run's resolved {@link CtSelection} (define-ct plan §4.2) supplying the CT package ids.
     */
    static @Nullable MetadataProvider tryStoreProvider(StudyValidationParams params,
            StandardKind kind, List<String> effectiveProducts, RunStandard runStandard,
            CtSelection ctSelection)
    {
        boolean adamFamily = kind == StandardKind.ADAM || isTigAdamRun(effectiveProducts);
        if (kind != StandardKind.SDTM && !adamFamily)
        {
            return null;
        }
        // ⭐ F2 (final cross-plan review): the run's OWN store parameter is the top tier — an
        // explicitly named store must never lose to an ambient CDISC_METADATA_STORE.
        Path file = net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory
                .resolveConfiguredFile(params.metadataStore());
        if (file == null)
        {
            return null;
        }
        net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory factory;
        try
        {
            factory = net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory.open(file);
        }
        catch (IOException e)
        {
            // A configured store that cannot be opened is worth a loud line, but the disposition
            // is the R2 degraded run, not an abort: since cache P4 there is no other metadata
            // path, so the library-dependent rules SKIP visibly.
            LOGGER.log(System.Logger.Level.WARNING,
                    "Configured metadata store {0} cannot be opened ({1}); the run degrades and "
                            + "the library-dependent rules will SKIP.",
                    file, e.getMessage());
            return null;
        }
        Optional<MetadataProvider> provider;
        // The CT package ids this run intends to load from the store — the §4.4 "named" set,
        // after root filtering (a root the run does not consume is not named, §4.5.1).
        List<String> namedCtIds;
        if (kind == StandardKind.SDTM)
        {
            MetadataProductKeys.SdtmLoader loader = MetadataProductKeys
                    .firstSdtmLoader(effectiveProducts);
            String libStd = loader != null ? loader.standard() : runStandard.standard();
            String libVersion = loader != null ? loader.version() : runStandard.version();
            // §4.3 (define-ct plan): ALL matching packages join the merge, newest first — and a
            // SEND-family run's own CT root (sendct) precedes the sdtmct fallback root.
            List<String> ctIds = new ArrayList<>();
            if (runStandard.standard() != null
                    && runStandard.standard().toLowerCase(Locale.ROOT).startsWith("send"))
            {
                ctIds.addAll(ctIdsWithPrefix(ctSelection.packageIds(), "sendct"));
            }
            ctIds.addAll(ctIdsWithPrefix(ctSelection.packageIds(), "sdtmct"));
            namedCtIds = ctIds;
            provider = factory.forSdtm(libStd, libVersion, ctIds);
        }
        else
        {
            List<String> adamCts = ctIdsWithPrefix(ctSelection.packageIds(), "adamct");
            List<String> sdtmCts = ctIdsWithPrefix(ctSelection.packageIds(), "sdtmct");
            namedCtIds = new ArrayList<>(adamCts);
            namedCtIds.addAll(sdtmCts);
            provider = factory.forAdam(runStandard.standard(), runStandard.version(),
                    effectiveProducts, adamCts, sdtmCts);
        }
        if (provider.isPresent())
        {
            // §4.4 (D3, define-ct P5): the store is serving this run, so every CT package the run
            // NAMED for a root it consumes must actually be present — a named-but-unavailable
            // package aborts, never quietly becomes the empty substitution inside the factory.
            // Root filtering already happened above, so a declared package for a root this run
            // does not consume was never named here (§4.5.1: ignored silently); and with a
            // populated user field the declaration never reached the selection at all (row 6).
            requireNamedCtPackagesPresent(factory, file, namedCtIds, ctSelection.source());
            LOGGER.log(System.Logger.Level.INFO,
                    "Using unified metadata store at {0} for {1} {2} (metadata products {3})", file,
                    runStandard.standard(), runStandard.version(), effectiveProducts);
            return provider.get();
        }
        LOGGER.log(System.Logger.Level.WARNING,
                "Metadata store {0} has no product for {1} {2}; the run degrades and the "
                        + "library-dependent rules will SKIP.",
                file, runStandard.standard(), runStandard.version());
        return null;
    }


    /**
     * Loads the companion SDTM product from the configured unified metadata store, else
     * {@code null} — since cache 8g the only offline companion source (the pickle leg that used to
     * follow it is deleted), ahead of the {@code apiLoader} test seam. {@code aExplicitStore} is
     * the run's own {@link StudyValidationParams#metadataStore()}, so the companion is read from
     * the SAME store {@link #tryStoreProvider} served the run from — never from an ambient
     * {@code CDISC_METADATA_STORE} outranking the store the caller named (F2).
     */
    static @Nullable MetadataProvider companionFromStore(CompanionSdtmDefaults.Companion c,
            @Nullable String aExplicitStore)
    {
        Path file = net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory
                .resolveConfiguredFile(aExplicitStore);
        if (file == null)
        {
            return null;
        }
        try
        {
            return net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory.open(file)
                    .forSdtm(c.loaderStandard(), c.loaderVersion(), List.<String> of())
                    .orElse(null);
        }
        catch (IOException e)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Configured metadata store {0} cannot be opened for the companion SDTM "
                            + "product ({1}).",
                    file, e.getMessage());
            return null;
        }
    }


    /**
     * §4.4 (owner ruling D3, define-ct P5) — <b>abort when something named is missing or unusable;
     * skip when nothing is named at all.</b> Called only once the store is actually serving the
     * run: every CT package id the run named for a consumed root must be
     * {@link net.cumba.corej.core.metadata.store.Presence#PRESENT}, else the run aborts naming the
     * package — the factory's quiet empty-package substitution must never stand in for a package
     * someone asked for.
     *
     * <p>
     * The two abort rows this implements: a missing <em>declared</em> package while the declaration
     * is in play (row 3 — escapable, because filling the field takes the declaration out of play
     * entirely, row 6), and a missing <em>user-selected</em> package (row 5). An empty
     * {@code aNamedCtIds} — nothing named ({@code CtSelection.Source.NONE}), or every id
     * root-filtered away — checks nothing: that is the cache ruling's territory (run with no CT;
     * CT-dependent rules SKIP visibly).
     * </p>
     *
     * @param aFactory
     *            the factory over the serving store
     * @param aStoreFile
     *            the store file, for the message
     * @param aNamedCtIds
     *            the root-filtered CT package ids this run intends to load
     * @param aSource
     *            where the selection came from (decides the message, not the check)
     * @throws StudyValidationException
     *             when a named package is absent or its id is malformed
     */
    static void requireNamedCtPackagesPresent(
            net.cumba.corej.core.metadata.store.StoreMetadataProviderFactory aFactory,
            Path aStoreFile, List<String> aNamedCtIds, CtSelection.Source aSource)
    {
        for (String id : aNamedCtIds)
        {
            net.cumba.corej.core.metadata.store.Presence presence = aFactory.presence(id);
            if (presence == net.cumba.corej.core.metadata.store.Presence.PRESENT)
            {
                continue;
            }
            String problem = presence == net.cumba.corej.core.metadata.store.Presence.MALFORMED
                    ? "is not a valid CT package id (expected <family>ct-<yyyy-mm-dd>)"
                    : "is not held by the metadata store at " + aStoreFile;
            if (aSource == CtSelection.Source.DEFINE)
            {
                throw new StudyValidationException("The Define-XML declares "
                        + "controlled-terminology package '" + id + "' (def:Standards), which "
                        + problem + ". Seed the store with it, or fill the CT Packages field "
                        + "explicitly - an explicit selection takes the define's declaration "
                        + "out of play.");
            }
            throw new StudyValidationException("Controlled-terminology package '" + id
                    + "' was requested, but it " + problem + ".");
        }
    }


    /**
     * The §4.2 run-level mismatch note, or {@code null} when there is nothing to report: the define
     * declares no CT packages, or the declared set equals (order-insensitively) the set of package
     * ids the run used.
     *
     * <p>
     * The comparison is against the run's CT <em>selection</em>, before any root filtering — §4.5.1
     * rules that a declared package for a root the run does not consume is ignored
     * <em>silently</em>, so post-filter comparison would wrongly flag exactly that case.
     * </p>
     *
     * @param aDeclared
     *            the define's declared CT packages (empty when none)
     * @param aUsed
     *            the CT package ids the run's selection resolved to
     * @return the note text, or {@code null} when declaration and selection agree
     */
    static @Nullable String ctDeclarationMismatch(
            List<net.cumba.corej.core.gen.CtStandardRef> aDeclared, List<String> aUsed)
    {
        if (aDeclared.isEmpty())
        {
            return null;
        }
        Set<String> declaredIds = new LinkedHashSet<>();
        for (net.cumba.corej.core.gen.CtStandardRef ref : aDeclared)
        {
            declaredIds.add(ref.packageId());
        }
        Set<String> usedIds = new LinkedHashSet<>(aUsed);
        if (declaredIds.equals(usedIds))
        {
            return null;
        }
        return "define declares " + String.join(", ", declaredIds) + "; run used "
                + (usedIds.isEmpty() ? "none" : String.join(", ", usedIds));
    }


    /**
     * CT-R3 (owner ruling 2026-09-09) — joins the engine's computed CT declaration mismatch with
     * the caller's {@link StudyValidationParams#ctResolutionNote() resolution note} into the ONE
     * existing {@code CT_Declaration_Mismatch} field: engine text first, caller text appended,
     * either alone when the other is null/blank, {@code null} when both are — so the "absent when
     * they agree" contract of the field is unchanged.
     */
    static @Nullable String joinCtNotes(@Nullable String aEngineMismatch,
            @Nullable String aCallerNote)
    {
        boolean haveCaller = aCallerNote != null && !aCallerNote.isBlank();
        if (aEngineMismatch == null)
        {
            return haveCaller ? aCallerNote : null;
        }
        return haveCaller ? aEngineMismatch + "; " + aCallerNote : aEngineMismatch;
    }


    /** First id in {@code aIds} starting with {@code aPrefix} (e.g. {@code sdtmct}), or null. */
    static @Nullable String ctIdWithPrefix(List<String> aIds, String aPrefix)
    {
        for (String id : aIds)
        {
            if (id != null && id.startsWith(aPrefix))
            {
                return id;
            }
        }
        return null;
    }


    /**
     * Every id in {@code aIds} starting with {@code aPrefix}, newest first (define-ct plan §4.3: a
     * multi-package selection is merged with newest-first precedence within a root; the
     * lexicographic order works because CT ids end in an ISO date).
     */
    static List<String> ctIdsWithPrefix(List<String> aIds, String aPrefix)
    {
        List<String> out = new ArrayList<>();
        for (String id : aIds)
        {
            if (id != null && id.startsWith(aPrefix))
            {
                out.add(id);
            }
        }
        out.sort(java.util.Comparator.reverseOrder());
        return out;
    }

    // ------------------------------------------------------------------
    // Rule loading
    // ------------------------------------------------------------------


    /**
     * Resolve the effective rules directory. Precedence: an explicit value (the CLI's
     * {@code --rules-dir} or a REST request) wins; otherwise the {@link #ENV_RULES_DIR} environment
     * variable, then the {@link #SP_RULES_DIR} system property; finally {@link #DEFAULT_RULES_DIR}.
     */
    static String resolveRulesDir(@Nullable String explicit, @Nullable String envValue,
            @Nullable String propValue)
    {
        if (explicit != null && !explicit.isBlank())
        {
            return explicit;
        }
        if (envValue != null && !envValue.isBlank())
        {
            return envValue;
        }
        if (propValue != null && !propValue.isBlank())
        {
            return propValue;
        }
        return DEFAULT_RULES_DIR;
    }


    private static String resolveRulesDir(@Nullable String explicit)
    {
        return resolveRulesDir(explicit, System.getenv(ENV_RULES_DIR),
                System.getProperty(SP_RULES_DIR));
    }


    /**
     * The effective rules directory for the current environment ({@link #ENV_RULES_DIR} /
     * {@link #SP_RULES_DIR} / {@link #DEFAULT_RULES_DIR}), with no per-run override. Exposed so the
     * REST layer can enumerate the available rule packs (standards / versions / rule ids) from the
     * same directory a run would use.
     */
    public static String effectiveRulesDir()
    {
        return resolveRulesDir(null);
    }

    /** The invariant filename prefix of a rule package: {@code rules-<short>.json}. */
    private static final String RULE_PACKAGE_PREFIX = "rules-";

    /** The invariant filename suffix of a rule package: {@code rules-<short>.json}. */
    private static final String RULE_PACKAGE_SUFFIX = ".json";

    /**
     * The packages a run selects, and the library standards they declare (R6/R7).
     *
     * @param files
     *            the selected package files, in load order
     * @param declared
     *            every standard those packages declare, in package order
     */
    record RuleSelection(SequencedSet<Path> files,
            List<net.cumba.corej.core.model.StandardRef> declared)
    {
    }

    /**
     * Resolves which packages the run executes and reads their declarations. Runs BEFORE metadata
     * provider construction, because R7 folds the declared standards into the effective
     * {@code --metadata-products} list the provider is built from.
     *
     * @param params
     *            the run parameters
     * @return the selection and its declarations
     * @throws IOException
     *             if a package cannot be read
     */
    static RuleSelection selectRulePackages(StudyValidationParams params) throws IOException
    {
        Path rulesDir = Path.of(resolveRulesDir(params.rulesDir()));

        // ⚑ A `rules-templates.json` in the rules directory used to be loaded unconditionally
        // here, ahead of everything the caller selected. That branch went with the engine's
        // built-in templates (Fix #366): a rule that belongs to no package must not run.

        // The run's selected package files, in load order and de-duplicated by real path. The
        // explicit selection (-rp ∪ --rules-file) wins outright; only when the caller named
        // neither do we fall back to the conventional (family, standard, version) packages.
        //
        // ⛔ R3 / ruling Q2: the two explicit arms UNION (a named package plus a sponsor's own
        // file runs both). Before Plan 2 a single --rules-file suppressed the conventional
        // packages entirely; that gate is gone, replaced by "explicit beats conventional".
        net.cumba.corej.core.RulePackageManifest manifest = net.cumba.corej.core.RulePackageManifest
                .load(rulesDir);
        validateManifestAgainstDisk(rulesDir, manifest);

        SequencedSet<Path> selected = new LinkedHashSet<>();
        for (Path p : resolvePackageShortNames(rulesDir, params.rulesPackages()))
        {
            selected.add(p);
        }
        for (String file : params.rulesFiles())
        {
            Path p = Path.of(file);
            if (!Files.exists(p))
            {
                // ⚑ Warn-and-continue is the PRE-EXISTING contract (pinned by
                // CdiscValidateTest.run_rulesFileMissing_logsWarning_continues). Plan 2 does not
                // overturn it: no ruling calls for it, and the run still cannot pass silently —
                // an unresolved file leaves the selection empty, which fails below.
                LOGGER.log(System.Logger.Level.WARNING, "Rules file not found: {0}", p);
                continue;
            }
            if (!selected.add(p.toAbsolutePath().normalize()) && !params.rulesPackages().isEmpty())
            {
                LOGGER.log(System.Logger.Level.INFO,
                        "Rules file {0} is already selected by --rules-package; loaded once.", p);
            }
        }
        // ⛔ R3: a rule package MUST be selected.
        //
        // ⚠ Accuracy note: an empty selection did NOT previously pass silently — the rules-empty
        // guard further down validate() already threw "no rules selected for validation." What
        // this adds is a message that can be acted on (the directory searched + the short names
        // actually available), and it fires on the SELECTION rather than on the merged rule list,
        // so "named a package that resolved to nothing" is distinguishable from "the packages
        // were empty". The genuinely new failure is an UNKNOWN named package, which
        // pickConventionalRulesFiles-style skipping would have swallowed.
        if (selected.isEmpty())
        {
            throw new StudyValidationException(noRulePackageSelectedMessage(rulesDir));
        }

        List<net.cumba.corej.core.model.StandardRef> declared = new ArrayList<>();
        for (Path pack : selected)
        {
            declared.addAll(declaredStandards(pack, RulePackageLoader.load(pack), manifest));
        }
        return new RuleSelection(selected, List.copyOf(declared));
    }


    private static List<Rule> loadRules(RuleSelection selection) throws IOException
    {
        List<Rule> all = new ArrayList<>();
        for (Path pack : selection.files())
        {
            RulePackage pkg = RulePackageLoader.load(pack);
            Map<String, Rule> pkgRules = rulesOf(pkg);
            all.addAll(pkgRules.values());
            LOGGER.log(System.Logger.Level.INFO, "Loaded {0} rule(s) from {1}", pkgRules.size(),
                    pack);
        }
        return all;
    }


    /**
     * The run's standard, derived from the selected packages' declared primaries (R5/R7/R8).
     *
     * <p>
     * A package that declares no {@code primary} — an unmanifested package, or a sponsor's own
     * {@code --rules-file} — falls back to the first {@code --metadata-products} entry. With
     * neither, the run has nothing to resolve metadata against and <b>fails loud</b>, which is R6's
     * "a package whose standards cannot be determined requires an explicit {@code -mp}".
     * </p>
     *
     * @param declared
     *            the selected packages' declared standards
     * @param effectiveProducts
     *            the effective product list
     * @return the derived run standard, never null
     */
    static RunStandard runStandardOf(List<net.cumba.corej.core.model.StandardRef> declared,
            List<String> effectiveProducts)
    {
        RunStandard fromPackages = RunStandard.from(declared);
        if (fromPackages != null)
        {
            return fromPackages;
        }
        if (!effectiveProducts.isEmpty())
        {
            return RunStandard.of(effectiveProducts.get(0));
        }
        throw new StudyValidationException(
                "The selected rule package(s) declare no CDISC Library standard, and no "
                        + "--metadata-products was given, so there is nothing to resolve metadata "
                        + "against. Name a product with -mp / --metadata-products, or select a "
                        + "package that declares its standards.");
    }


    /**
     * <b>R7</b> — the effective metadata-product list: the run's own {@code --metadata-products}
     * entries first, then the selected packages' declared standards <b>appended LAST</b>, so a
     * declaration never outranks something the user typed.
     *
     * <p>
     * Declared ids resolve through {@code ProductKeyResolver} exactly as user tokens do, so a
     * package declaring a product CDISC never published (e.g. {@code sendig/dart-1-2}) fails loud
     * naming the candidates, rather than resolving to a plausible wrong product.
     * </p>
     *
     * @param params
     *            the run parameters
     * @param declared
     *            the selected packages' declared standards
     * @return the effective product keys, in precedence order, duplicates removed
     */
    static List<String> effectiveMetadataProducts(StudyValidationParams params,
            List<net.cumba.corej.core.model.StandardRef> declared)
    {
        SequencedSet<String> out = new LinkedHashSet<>(params.metadataProducts());
        if (declared.isEmpty())
        {
            return List.copyOf(out);
        }
        List<String> ids = declared.stream().map(net.cumba.corej.core.model.StandardRef::id)
                .filter(id -> !id.isEmpty()).distinct().toList();
        try
        {
            out.addAll(net.cumba.corej.core.metadata.pickle.ProductKeyResolver
                    .resolveAllConfigured(ids, params.pickleCacheDir(), null));
        }
        catch (IllegalArgumentException e)
        {
            // ⛔ Review finding R-8 — these ids come from the PACKAGE's declaration, not from the
            // user's -mp, but ProductKeyResolver's message is hard-coded "Cannot resolve
            // --metadata-products: …". Three of the 58 shipped packages declare an unresolvable
            // primary (sendig/dart-1-2, which CDISC never published), so selecting one of them
            // told the user their --metadata-products was wrong when they had not passed it — and
            // as an IllegalArgumentException no CLI catch handled it, so it surfaced as a raw
            // stack trace and exit 1 instead of the clean "Error: …" / exit 2 every other
            // operational failure gives. Q1's fail-loud ruling is preserved: this still fails, and
            // still names the offending token; it just blames the right thing.
            throw new StudyValidationException("The selected rule package(s) declare a CDISC "
                    + "Library standard that cannot be resolved. " + e.getMessage()
                    + " Select a different rule package, or name a resolvable product with "
                    + "-mp / --metadata-products.", e);
        }
        return List.copyOf(out);
    }


    /**
     * The library standards a selected package declares (R6), resolved <b>file first, then the
     * {@code packages.json} cache</b>. Empty when neither declares any — under R6 such a package
     * needs an explicit {@code --metadata-products}.
     *
     * <p>
     * ⚑ The manifest copy is a cache for fast lookup, not an authority: when a package file
     * declares its own standards they win outright, so re-generating the corpus cannot silently
     * contradict a package.
     * </p>
     *
     * @param packageFile
     *            the resolved package path (its file name keys the manifest)
     * @param pkg
     *            the loaded package
     * @param manifest
     *            the rules directory's manifest (possibly empty)
     * @return the declared standards, never null
     */
    static List<net.cumba.corej.core.model.StandardRef> declaredStandards(Path packageFile,
            RulePackage pkg, net.cumba.corej.core.RulePackageManifest manifest)
    {
        List<net.cumba.corej.core.model.StandardRef> fromFile = pkg.getStandards();
        if (fromFile != null && !fromFile.isEmpty())
        {
            return List.copyOf(fromFile);
        }
        Path name = packageFile.getFileName();
        String fileName = name == null ? "" : name.toString();
        return manifest.packages().stream().filter(e -> fileName.equals(e.file())).findFirst()
                .map(net.cumba.corej.core.RulePackageManifest.Entry::standards).orElse(List.of());
    }


    /**
     * Phase 2 / R12 — reconciles {@code packages.json} against the rules directory.
     *
     * <p>
     * <b>The two cases are deliberately asymmetric.</b> A package on disk that the manifest does
     * not list still RUNS and is only logged (R12: <i>the filesystem decides what can run; the
     * manifest is metadata about it</i>) — manifest staleness is real here, the corpus regen is a
     * multi-step pipeline and a stale artefact is a known hazard in this repo. The mirror case is
     * an ERROR: a manifest entry naming a file that is absent is not staleness but a broken corpus,
     * and the conventional arm would otherwise resolve it to nothing and skip.
     * </p>
     *
     * @param rulesDir
     *            the rules directory
     * @param manifest
     *            its manifest (empty when absent)
     */
    static void validateManifestAgainstDisk(Path rulesDir,
            net.cumba.corej.core.RulePackageManifest manifest)
    {
        List<String> missing = manifest.packages().stream()
                .map(net.cumba.corej.core.RulePackageManifest.Entry::file)
                .filter(f -> f != null && !Files.isRegularFile(rulesDir.resolve(f))).sorted()
                .toList();
        if (!missing.isEmpty())
        {
            throw new StudyValidationException("packages.json names " + missing.size()
                    + " package file(s) that are absent " + "from " + rulesDir.toAbsolutePath()
                    + ": " + String.join(", ", missing)
                    + ". The rules corpus and its manifest disagree.");
        }
        Set<String> manifested = manifest.packages().stream()
                .map(net.cumba.corej.core.RulePackageManifest.Entry::file)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        if (manifested.isEmpty())
        {
            // No manifest at all is a supported shape (a custom rules dir of loose packages);
            // reporting every file as unmanifested would be noise, not a signal.
            return;
        }
        List<String> unmanifested = availableShortNames(rulesDir).stream()
                .map(sn -> RULE_PACKAGE_PREFIX + sn + RULE_PACKAGE_SUFFIX)
                .filter(f -> !manifested.contains(f)).toList();
        if (!unmanifested.isEmpty())
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "{0} rule package(s) in {1} are absent from packages.json and will still run "
                            + "(the filesystem decides what can run): {2}",
                    unmanifested.size(), rulesDir.toAbsolutePath(),
                    String.join(", ", unmanifested));
        }
    }


    /**
     * Resolves rule-package short names ({@code -rp}) onto files in {@code rulesDir}: a short name
     * {@code cdisc-adamig-1-3} names {@code rules-cdisc-adamig-1-3.json} (R1 — the {@code rules-}
     * prefix and {@code .json} suffix are invariant).
     *
     * <p>
     * ⛔ <b>R12 — the filesystem decides what can run</b>, not {@code packages.json}: a package
     * present on disk but absent from the manifest still resolves here. An unknown short name is an
     * error naming the directory searched and the names that <em>are</em> available; it is never
     * skipped.
     * </p>
     */
    private static List<Path> resolvePackageShortNames(Path rulesDir, List<String> shortNames)
    {
        List<Path> out = new ArrayList<>();
        for (String name : shortNames)
        {
            String trimmed = name == null ? "" : name.trim();
            if (trimmed.isEmpty())
            {
                continue;
            }
            Path candidate = rulesDir.resolve(RULE_PACKAGE_PREFIX + trimmed + RULE_PACKAGE_SUFFIX);
            if (!Files.isRegularFile(candidate))
            {
                throw new StudyValidationException("Unknown rule package '" + trimmed + "' — no "
                        + candidate.getFileName() + " in " + rulesDir.toAbsolutePath()
                        + ". Available: " + String.join(", ", availableShortNames(rulesDir)));
            }
            out.add(candidate.toAbsolutePath().normalize());
        }
        return out;
    }


    /**
     * The rule-package short names available in {@code rulesDir}, sorted — every
     * {@code rules-<short>.json} on disk (R12), with the invariant prefix and suffix stripped.
     * Empty when the directory is absent or unreadable.
     */
    static List<String> availableShortNames(Path rulesDir)
    {
        if (!Files.isDirectory(rulesDir))
        {
            return List.of();
        }
        try (java.util.stream.Stream<Path> entries = Files.list(rulesDir))
        {
            return entries.map(Path::getFileName).filter(java.util.Objects::nonNull)
                    .map(Path::toString)
                    .filter(n -> n.startsWith(RULE_PACKAGE_PREFIX)
                            && n.endsWith(RULE_PACKAGE_SUFFIX))
                    .map(n -> n.substring(RULE_PACKAGE_PREFIX.length(),
                            n.length() - RULE_PACKAGE_SUFFIX.length()))
                    .filter(n -> !n.isEmpty()).sorted().toList();
        }
        catch (IOException _)
        {
            return List.of();
        }
    }


    /** The R3 failure message: what was searched, and what could have been named instead. */
    private static String noRulePackageSelectedMessage(Path rulesDir)
    {
        List<String> available = availableShortNames(rulesDir);
        StringBuilder sb = new StringBuilder("No rule package selected. Name one with "
                + "-rp / --rules-package (or supply --rules-file). Rules directory searched: ")
                        .append(rulesDir.toAbsolutePath()).append('.');
        if (available.isEmpty())
        {
            sb.append(" That directory contains no ").append(RULE_PACKAGE_PREFIX).append("*")
                    .append(RULE_PACKAGE_SUFFIX).append(" packages.");
        }
        else
        {
            sb.append(" Available packages: ").append(String.join(", ", available)).append('.');
        }
        return sb.toString();
    }


    private static List<Rule> filterRules(List<Rule> rules, StudyValidationParams params)
    {
        switch (params.ruleSelectionMode())
        {
        case NONE:
            return List.of();
        case ALL:
            return rules;
        case FILTERED:
        default:
            break;
        }
        if (params.includeRules().isEmpty() && params.excludeRules().isEmpty())
        {
            return rules;
        }
        Set<String> include = new LinkedHashSet<>(params.includeRules());
        Set<String> exclude = new LinkedHashSet<>(params.excludeRules());
        List<Rule> out = new ArrayList<>(rules.size());
        for (Rule r : rules)
        {
            String coreId = coreIdOf(r);
            if (!include.isEmpty() && !include.contains(coreId))
            {
                continue;
            }
            if (exclude.contains(coreId))
            {
                continue;
            }
            out.add(r);
        }
        return out;
    }


    private static @Nullable String coreIdOf(Rule rule)
    {
        return rule.effectiveId();
    }

    // ------------------------------------------------------------------
    // Reporting helpers
    // ------------------------------------------------------------------


    private static int countFindings(ValidationReport report)
    {
        int total = 0;
        for (var m : report.getMembers())
        {
            for (var f : m.getFindings())
            {
                total += Math.max(1, f.getRowCount());
            }
        }
        return total;
    }


    private static @Nullable String fileNameOf(@Nullable URI uri)
    {
        if (uri == null)
        {
            return null;
        }
        String path = uri.getPath();
        if (path == null || path.isEmpty())
        {
            return null;
        }
        int slash = path.lastIndexOf('/');
        return slash >= 0 && slash < path.length() - 1 ? path.substring(slash + 1) : path;
    }

    // ------------------------------------------------------------------
    // Standard kind
    // ------------------------------------------------------------------

    enum StandardKind
    {

        SDTM, ADAM, UNKNOWN;

        static StandardKind fromName(String name)
        {
            if (name == null)
            {
                return UNKNOWN;
            }
            String n = name.toLowerCase(Locale.ROOT);
            if (n.startsWith("sdtm") || n.equals("send") || n.equals("sendig"))
            {
                return SDTM;
            }
            if (n.startsWith("adam"))
            {
                return ADAM;
            }
            return UNKNOWN;
        }
    }

    // ------------------------------------------------------------------
    // Internal types
    // ------------------------------------------------------------------


    /**
     * Snapshot of metadata that can only be read off a loaded {@link IDataTable}. Captured once,
     * the first time the table is loaded, and retained strongly so the report writer can read it
     * after the table itself has been GC'd (the table is held only via {@link SoftReference} once
     * the validator's per-dataset run returns).
     */
    private record DatasetMetadataSnapshot(@Nullable String label, long rowCount, int columnCount)
    {
    }


    /**
     * A library member registered with the validator as a target. The {@link IDataTable} is held
     * via a soft-cached supplier that loads on first {@link #tableSupplier()}{@code .get()} and
     * captures a {@link DatasetMetadataSnapshot} as a side effect of that first load. The snapshot
     * survives subsequent GC of the underlying table, so the report writer can render the dataset's
     * label / row count / column count without forcing a reload.
     */
    private static final class DatasetEntry
    {

        private final String domain;

        private final @Nullable String fileName;

        private final @Nullable String parentPath;

        private final long fileSize;

        private final @Nullable String modificationDate;

        private final Supplier<IDataTable> tableSupplier;

        private volatile @Nullable DatasetMetadataSnapshot snapshot;

        DatasetEntry(String aDomain, @Nullable String aFileName, @Nullable String aParentPath,
                long aFileSize, @Nullable String aModificationDate, Supplier<IDataTable> aRawLoad)
        {
            domain = aDomain;
            fileName = aFileName;
            parentPath = aParentPath;
            fileSize = aFileSize;
            modificationDate = aModificationDate;
            tableSupplier = softMemoised(() ->
            {
                IDataTable t = aRawLoad.get();
                captureSnapshot(t);
                return t;
            });
        }


        String domain()
        {
            return domain;
        }


        @Nullable
        String fileName()
        {
            return fileName;
        }


        @Nullable
        String parentPath()
        {
            return parentPath;
        }


        long fileSize()
        {
            return fileSize;
        }


        @Nullable
        String modificationDate()
        {
            return modificationDate;
        }


        /** Soft-cached supplier handed to {@link LibraryValidator.Builder#targetDataset}. */
        Supplier<IDataTable> tableSupplier()
        {
            return tableSupplier;
        }


        /**
         * Returns the snapshot if one has been captured, or forces a load to capture it. The report
         * writer calls this after validation, by which time validation has already loaded the table
         * at least once — so this is normally a cheap field read.
         */
        // tableSupplier.get() is called for its side effect: it loads the table and triggers
        // captureSnapshot via the dataset-level listener. The returned IDataTable is discarded.
        @SuppressWarnings("ReturnValueIgnored")
        DatasetMetadataSnapshot metadata()
        {
            DatasetMetadataSnapshot s = snapshot;
            if (s != null)
            {
                return s;
            }
            // Loading the table fires the dataset-level listener, which captures the snapshot.
            tableSupplier.get();
            return java.util.Objects.requireNonNull(snapshot, "snapshot capture failed");
        }


        private void captureSnapshot(IDataTable table)
        {
            if (snapshot == null)
            {
                synchronized (this)
                {
                    if (snapshot == null)
                    {
                        snapshot = new DatasetMetadataSnapshot(table.getMetaData().getLabel(),
                                table.getRowCount(), table.getColumnCount());
                    }
                }
            }
        }
    }


    /** A library member that is loaded only on demand and never iterated as a target. */
    private record ReferenceDataset(String domain, Supplier<IDataTable> supplier)
    {
    }


    /**
     * Result of {@link StudyValidationService#loadDatasets} — split into targets and references.
     */
    private record LoadedLibrary(List<DatasetEntry> targets, List<ReferenceDataset> references)
    {
    }

    /**
     * Non-null view of a loaded package's rule map ({@code RulePackage.getRules()} may be null).
     */
    private static Map<String, Rule> rulesOf(RulePackage pkg)
    {
        Map<String, Rule> rules = pkg.getRules();
        return rules != null ? rules : Map.of();
    }
}
