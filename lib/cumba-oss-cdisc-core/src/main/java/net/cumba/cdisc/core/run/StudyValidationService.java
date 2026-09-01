package net.cumba.cdisc.core.run;

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
import net.cumba.cdisc.core.CoreLibraryAccess;
import net.cumba.cdisc.core.RulePackageLoader;
import net.cumba.cdisc.core.exec.MetadataProvider;
import net.cumba.cdisc.core.metadata.AdamSubclassDetector;
import net.cumba.cdisc.core.metadata.CdiscLibraryProviderBuilder;
import net.cumba.cdisc.core.metadata.CompanionDomainsProvider;
import net.cumba.cdisc.core.metadata.MetadataLibraryProvider;
import net.cumba.cdisc.core.metadata.MetadataProductKeys;
import net.cumba.cdisc.core.metadata.pickle.PickleMetadataProviderFactory;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.core.report.LibraryValidator;
import net.cumba.cdisc.core.report.ReportAssembler;
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

        // Phase B: enrich metadata via CDISC Library API (best-effort). Metadata source is
        // the metadata-overlay library when a define.xml accompanies the data library, otherwise
        // the data library.
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
        MetadataProvider provider = buildProvider(manager, metadataLibrary, params, kind,
                effectiveProducts, runStandard);
        // Sponsor Define-XML metadata as an independent provider — the "define" level of the
        // three-level metadata model. Present only when a Define-XML was supplied (gated on
        // defineXmlPath; otherwise metadataLibrary is the data adapter, not a define). Carried for
        // the define_* operand family; consumed by the metadata-check evaluation path.
        MetadataProvider defineProvider = null;
        // The direct (ODM-backed) Define-XML provider, captured so the per-record value-level
        // metadata resolver (VlmResolver) can be built from the same parsed model.
        net.cumba.cdisc.core.gen.DefineXMLProvider directDefine = null;
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
            net.cumba.cdisc.core.gen.DefineXMLProvider direct = params.defineXmlProvider();
            if (direct == null)
            {
                direct = parseDefineXmlDirect(params.defineXmlPath());
            }
            directDefine = direct;
            defineProvider = direct != null
                    ? new net.cumba.cdisc.core.metadata.DefineXmlMetadataProvider(direct,
                            datatableDefine)
                    : datatableDefine;
        }
        else if (params.defineXmlProvider() != null)
        {
            // No define.xml path but an explicit direct provider supplied (e.g. tests / embedding).
            directDefine = params.defineXmlProvider();
            defineProvider = new net.cumba.cdisc.core.metadata.DefineXmlMetadataProvider(
                    directDefine);
        }
        // Per-record value-level metadata resolver (Value Check against Define XML VLM). Built from
        // the same parsed model as defineProvider; null when no Define-XML (or no ValueListDef) is
        // present, so VLM rules SKIP via the DEFINE provider gate exactly like defineProvider.
        net.cumba.cdisc.core.metadata.VlmResolver vlmResolver = directDefine != null
                ? net.cumba.cdisc.core.metadata.VlmResolver.from(directDefine.metaDataVersion())
                : null;
        if (vlmResolver != null && !vlmResolver.structuralWarnings().isEmpty())
        {
            for (String w : vlmResolver.structuralWarnings())
            {
                LOGGER.log(System.Logger.Level.WARNING, "Define-XML value-level metadata: {0}", w);
            }
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

        LibraryValidator.Builder vb = LibraryValidator.builder().provider(provider)
                .defineProvider(defineProvider).vlmResolver(vlmResolver)
                .dictionaryProvider(buildDictionaryProvider()).rules(rules)
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
        String ctVersion = params.controlledTerminologyPackages().isEmpty() ? null
                : String.join(", ", params.controlledTerminologyPackages());
        // Define-XML version: explicit param wins; otherwise read from the loaded library
        // metadata (Define-XML-backed libraries populate this; other library types return null).
        String defineXmlVersion = params.defineVersion() != null ? params.defineVersion()
                : provider.getDefineVersion();

        // Fix #369 — a degraded run must SAY SO in the report, not only in the log. Null (and so
        // absent from Conformance_Details) whenever the Library answered normally.
        String libraryMetadataBasis = null;
        if (provider.isLibraryUnavailable())
        {
            libraryMetadataBasis = net.cumba.cdisc.core.exec.OperationExecutor
                    .libraryAnswerable(provider)
                            ? "Define-XML (sponsor declarations) — the CDISC Library could not be "
                                    + "consulted for this run and -D"
                                    + net.cumba.cdisc.core.exec.OperationExecutor.DEGRADED_DEFINE_FALLBACK_PROPERTY
                                    + "=true was given"
                            : "unavailable — the CDISC Library could not be consulted for this run;"
                                    + " rules that cite it were SKIPPED";
            LOGGER.log(System.Logger.Level.WARNING, "Library metadata basis: {0}",
                    libraryMetadataBasis);
        }

        ReportAssembler.Conformance conformance = ReportAssembler.Conformance.builder()
                .standard(runStandard.standard()).version(runStandard.version())
                .subStandard(CompanionSdtmDefaults.tigLeg(effectiveProducts))
                .tigUseCase(params.useCase()).ctVersion(ctVersion)
                .defineXmlVersion(defineXmlVersion).libraryMetadataBasis(libraryMetadataBasis)
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
     * {@link net.cumba.cdisc.core.gen.DefineXMLProvider} for direct define-operand access. A
     * {@code <scheme>://} string is read as a URI; otherwise as a local file. Returns {@code null}
     * (and logs a warning) on any parse failure, so the run falls back to the datatable-backed
     * define metadata rather than aborting.
     */
    private static net.cumba.cdisc.core.gen.@Nullable DefineXMLProvider parseDefineXmlDirect(
            String path)
    {
        try
        {
            net.cumba.cdisc.define.ODM odm = path.contains("://")
                    ? new net.cumba.cdisc.define.DefineXmlParser().parse(URI.create(path))
                    : new net.cumba.cdisc.define.DefineXmlParser().parse(new File(path));
            return new net.cumba.cdisc.core.metadata.OdmDefineXMLProvider(odm);
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
     * T1 — builds the runtime external-dictionary provider from the checked-in dummy value-map
     * dictionaries. The directory is resolved from the {@code corej.dictionariesDir} system
     * property (default {@code "dictionaries"}, relative to the working directory / module
     * basedir). A missing directory or any read error yields an empty provider — every
     * dictionary-dependent rule then SKIPs — a declared ({@code $}-ref) operation through
     * {@code RuleRunner}'s eager dictionary arm ({@code Fix #268}), an inlined one through its
     * injected {@code dictionary_available(<type>)} precondition — never false-passing.
     */
    private static net.cumba.cdisc.core.metadata.@Nullable RuntimeDictionaryProvider buildDictionaryProvider()
    {
        String dir = System.getProperty("corej.dictionariesDir", "dictionaries");
        try
        {
            return net.cumba.cdisc.core.metadata.RuntimeDictionaryProvider
                    .loadDirectory(java.nio.file.Paths.get(dir));
        }
        catch (IOException e)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Could not load external dictionaries from {0}: {1} — dictionary rules will SKIP",
                    dir, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // CDISC Library enrichment
    // ------------------------------------------------------------------


    private MetadataProvider buildProvider(IDataTableManager manager, IDataTableLibraryRef library,
            StudyValidationParams params, StandardKind kind, List<String> effectiveProducts,
            RunStandard runStandard)
        throws IOException
    {
        // Offline path: when a Python pickle metadata cache is configured (flag / env / sysprop)
        // and it carries this run's products, source metadata from it and skip the network
        // entirely. Phase 7a: SDTM-family AND ADaM-family (the -s/-v product or any declared
        // ADaM product missing from the pickle falls through to the API path).
        MetadataProvider pickle = tryPickleProvider(params, kind, effectiveProducts, runStandard);
        if (pickle != null)
        {
            // On an ADaM-family run the companion wrap engages here too; the companion product
            // itself loads from the same pickle cache (companionFromPickle) — no API fallback
            // loader, deliberately: this path exists to stay offline.
            return maybeWrapCompanion(pickle, params, kind, effectiveProducts, null);
        }
        // The service always wants an access (Library rule endpoints are reachable anonymously
        // via the "dummy" key fallback), so we use open(...) not openIfConfigured() here.
        CoreLibraryAccess access = buildAccess(params);
        if (kind == StandardKind.UNKNOWN)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Standard {0} not supported for metadata enrichment. "
                            + "Running without enrichment.",
                    runStandard.standard());
        }
        // Facade absorbs the fetch / wire / degraded-fallback pipeline. CT selection now also
        // happens inside the builder; we just supply candidate ids + a progress hook so the
        // user-visible "Fetching CT package ..." line still fires.
        //
        // libraryAsStudy() makes this the pure CDISC-Library ("library") level of the three-level
        // metadata model — no study/define overlay polluting library_* operands. The sponsor
        // Define-XML flows independently via the defineProvider slot (see validate()). With a CT
        // package the product-derived library is used; without one it degrades to the legacy
        // study-backed build (the study passed below is the fallback for that case).
        // ⭐ metadataProducts(...) carries the run's EFFECTIVE ordered product list into the
        // provider: the user's own --metadata-products first, then the selected rule packages'
        // declared standards appended last (R7). Without it the subclass precedence chain has a
        // single product to walk and is inert on more than one.
        // ⚠ Since R5 removed -s/-v (and §1b′ with them) the user's half may be EMPTY; the
        // declarations are what make the effective list non-empty. A run where both are empty is
        // refused by runStandardOf before reaching here.
        MetadataProvider base = CdiscLibraryProviderBuilder.from(access)
                .study(manager.getMetadataLibrary(library)).libraryAsStudy()
                .standard(runStandard.standard()).version(runStandard.version())
                .metadataProducts(effectiveProducts)
                .ctPackageIds(params.controlledTerminologyPackages()).onCtFetch(id -> LOGGER
                        .log(System.Logger.Level.INFO, "Fetching CT package {0}...", id))
                .buildOrDegraded();
        // EC-14 layer (ii): on an ADaM-family run wrap the provider so standard_domains enumerates
        // the companion SDTMIG's domains; pickle-first, then a best-effort API build.
        return maybeWrapCompanion(base, params, kind, effectiveProducts,
                c -> buildCompanionViaApi(access, manager, library, c));
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
     * product is available (offline, or no pickle cache). {@link #maybeWrapCompanion} already logs
     * a WARNING there, so the degradation is never silent.
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
     *            the run parameters (standard / version / declared metadata products / pickle cache
     *            dir).
     * @param kind
     *            the resolved {@link StandardKind}.
     * @param apiLoader
     *            fallback that builds the companion via the CDISC Library API when the pickle cache
     *            has no product; may be {@code null} (the SDTM pickle path never needs it).
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
        MetadataProvider companion = companionFromPickle(params, c);
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
     * {@code isTigAdamRun} answered true and {@link #tryPickleProvider} routed the whole run onto
     * the ADaM leg. An SDTM-shaped TIG run then resolved every SDTM domain against an ADaM
     * provider, which returns empty for all of them (Fix #373) — so rules that used to SKIP visibly
     * reported "executed, no findings" instead.
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
     * Loads the companion SDTM product from the Python pickle cache when one is configured, else
     * {@code null}. Package-private static for unit testing. (The Q-12e hard error for an explicit
     * {@code --sdtm-version} miss went with the removed flag.)
     */
    static @Nullable MetadataProvider companionFromPickle(StudyValidationParams params,
            CompanionSdtmDefaults.Companion c)
    {
        Path dir = PickleMetadataProviderFactory.resolveConfiguredDir(params.pickleCacheDir());
        if (dir == null)
        {
            return null;
        }
        return PickleMetadataProviderFactory.open(dir)
                .forSdtm(c.loaderStandard(), c.loaderVersion(), null).orElse(null);
    }


    /**
     * Best-effort companion SDTM provider built via the CDISC Library API when the pickle cache has
     * no product. Only the {@code sdtmig} companion is buildable this way; a TIG companion requires
     * the pickle cache and returns {@code null} (⇒ {@code standard_domains} SKIPs).
     */
    private @Nullable MetadataProvider buildCompanionViaApi(CoreLibraryAccess access,
            IDataTableManager manager, IDataTableLibraryRef library,
            CompanionSdtmDefaults.Companion c)
    {
        if (!"sdtmig".equals(c.loaderStandard()))
        {
            return null;
        }
        try
        {
            return CdiscLibraryProviderBuilder.from(access)
                    .study(manager.getMetadataLibrary(library)).libraryAsStudy().standard("sdtmig")
                    .version(c.loaderVersion()).buildOrDegraded();
        }
        catch (IOException e)
        {
            LOGGER.log(System.Logger.Level.WARNING,
                    "Failed to build companion SDTM provider {0}: {1}", c.display(),
                    e.getMessage());
            return null;
        }
    }


    private static CoreLibraryAccess buildAccess(StudyValidationParams params)
    {
        String apiKey = System.getenv("CDISC_API_KEY");
        if (apiKey == null || apiKey.isBlank())
        {
            apiKey = System.getProperty("cdisc.library.api.key");
        }
        // null/blank apiKey is substituted with "dummy" by CoreLibraryAccess.open(...).
        Path cacheDir = params.cacheDir() != null
                ? new File(params.cacheDir()).getAbsoluteFile().toPath()
                : null;
        return CoreLibraryAccess.open(apiKey, null, cacheDir);
    }


    /**
     * Builds an offline metadata provider from the Python pickle cache when one is configured
     * (param / {@code CDISC_PICKLE_CACHE_DIR} / {@code cdisc.pickle.cache.dir}) and it actually
     * carries the run's products. Returns {@code null} — so the caller falls back to the CDISC
     * Library API path — for standards of no loadable family, when no cache is configured, or when
     * the cache lacks a needed product. Package-private static for unit testing.
     *
     * <p>
     * Phase 7a: both families load offline. An SDTM-family run goes through
     * {@code PickleMetadataProviderFactory.forSdtm}; an ADaM-family run — {@code kind == ADAM}, or
     * a declared TIG ADaM leg (§7-2) — through {@code forAdam}, whose ordered product list is
     * assembled by the same {@code DeclaredAdamProducts.assemble} choke point the API path uses.
     * §7-0: the SDTM library layer follows the <b>first declared SDTM-family product</b> in the
     * effective list — the user's {@code --metadata-products} first, then the selected packages'
     * declared standards (R7). ⚠ §1b′ and the {@code -s}/{@code -v} fallback it described are gone
     * (R5); with no SDTM-family key anywhere the run falls back to {@code runStandard}, which is
     * itself derived from the declared primaries.
     * </p>
     */
    static @Nullable MetadataProvider tryPickleProvider(StudyValidationParams params,
            StandardKind kind, List<String> effectiveProducts, RunStandard runStandard)
    {
        boolean adamFamily = kind == StandardKind.ADAM || isTigAdamRun(effectiveProducts);
        if (kind != StandardKind.SDTM && !adamFamily)
        {
            // UNKNOWN with nothing ADaM-shaped declared has no product to load.
            return null;
        }
        Path dir = PickleMetadataProviderFactory.resolveConfiguredDir(params.pickleCacheDir());
        if (dir == null)
        {
            return null;
        }
        PickleMetadataProviderFactory factory = PickleMetadataProviderFactory.open(dir);
        Optional<MetadataProvider> provider;
        if (kind == StandardKind.SDTM)
        {
            MetadataProductKeys.SdtmLoader loader = MetadataProductKeys
                    .firstSdtmLoader(effectiveProducts);
            String libStd = loader != null ? loader.standard() : runStandard.standard();
            String libVersion = loader != null ? loader.version() : runStandard.version();
            String sdtmCt = ctIdWithPrefix(params.controlledTerminologyPackages(), "sdtmct");
            provider = factory.forSdtm(libStd, libVersion, sdtmCt);
        }
        else
        {
            String adamCt = ctIdWithPrefix(params.controlledTerminologyPackages(), "adamct");
            String sdtmCt = ctIdWithPrefix(params.controlledTerminologyPackages(), "sdtmct");
            provider = factory.forAdam(runStandard.standard(), runStandard.version(),
                    effectiveProducts, adamCt, sdtmCt);
        }
        if (provider.isPresent())
        {
            LOGGER.log(System.Logger.Level.INFO,
                    "Using offline pickle metadata cache at {0} for {1} {2} (metadata products "
                            + "{3})",
                    dir, runStandard.standard(), runStandard.version(), effectiveProducts);
            return provider.get();
        }
        LOGGER.log(System.Logger.Level.WARNING,
                "Pickle cache {0} has no product for {1} {2}; falling back to CDISC Library API",
                dir, runStandard.standard(), runStandard.version());
        return null;
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
            List<net.cumba.cdisc.core.model.StandardRef> declared)
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
        net.cumba.cdisc.core.RulePackageManifest manifest = net.cumba.cdisc.core.RulePackageManifest
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

        List<net.cumba.cdisc.core.model.StandardRef> declared = new ArrayList<>();
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
    static RunStandard runStandardOf(List<net.cumba.cdisc.core.model.StandardRef> declared,
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
            List<net.cumba.cdisc.core.model.StandardRef> declared)
    {
        SequencedSet<String> out = new LinkedHashSet<>(params.metadataProducts());
        if (declared.isEmpty())
        {
            return List.copyOf(out);
        }
        List<String> ids = declared.stream().map(net.cumba.cdisc.core.model.StandardRef::id)
                .filter(id -> !id.isEmpty()).distinct().toList();
        try
        {
            out.addAll(net.cumba.cdisc.core.metadata.pickle.ProductKeyResolver
                    .resolveAllConfigured(ids, params.pickleCacheDir(), params.cacheDir()));
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
    static List<net.cumba.cdisc.core.model.StandardRef> declaredStandards(Path packageFile,
            RulePackage pkg, net.cumba.cdisc.core.RulePackageManifest manifest)
    {
        List<net.cumba.cdisc.core.model.StandardRef> fromFile = pkg.getStandards();
        if (fromFile != null && !fromFile.isEmpty())
        {
            return List.copyOf(fromFile);
        }
        Path name = packageFile.getFileName();
        String fileName = name == null ? "" : name.toString();
        return manifest.packages().stream().filter(e -> fileName.equals(e.file())).findFirst()
                .map(net.cumba.cdisc.core.RulePackageManifest.Entry::standards).orElse(List.of());
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
            net.cumba.cdisc.core.RulePackageManifest manifest)
    {
        List<String> missing = manifest.packages().stream()
                .map(net.cumba.cdisc.core.RulePackageManifest.Entry::file)
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
                .map(net.cumba.cdisc.core.RulePackageManifest.Entry::file)
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
