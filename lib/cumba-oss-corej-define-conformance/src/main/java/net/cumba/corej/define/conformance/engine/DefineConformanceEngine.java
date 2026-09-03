package net.cumba.corej.define.conformance.engine;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import javax.xml.parsers.ParserConfigurationException;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.DefineXmlConverter;
import net.cumba.corej.define.conformance.eval.DocumentContext;
import net.cumba.corej.define.conformance.eval.RuleEvaluator;
import net.cumba.corej.define.conformance.eval.RuleResult;
import net.cumba.corej.define.conformance.ordering.GlobalElementOrderingCheck;
import net.cumba.corej.define.conformance.report.Category;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.RuleExecution;
import net.cumba.corej.define.conformance.report.Severity;
import net.cumba.corej.define.conformance.rule.ConformanceRule;
import net.cumba.corej.define.conformance.rule.DefineRulePackageManifest;
import net.cumba.corej.define.conformance.rule.DefineRuleSelectionException;
import net.cumba.corej.define.conformance.rule.RuleRepository;
import net.cumba.corej.define.conformance.rule.RuleSet;
import net.cumba.corej.define.conformance.tree.ElementNode;
import net.cumba.corej.define.conformance.tree.ElementNodeBuilder;
import net.cumba.corej.define.conformance.xsd.DefinePrePass;
import org.jspecify.annotations.Nullable;
import org.w3c.dom.Document;
import org.xml.sax.SAXException;

/**
 * The top-level Define-XML conformance engine (plan §3.5). One
 * {@link #validate(DefineConformanceInput)} call runs, in order:
 *
 * <ol>
 * <li>the raw + XSD + classifier pre-pass ({@link DefinePrePass}), producing the pre-pass findings
 * and the detected version;</li>
 * <li>a DOM parse ({@link DefineDomIo#parse}) and normalised-tree build
 * ({@link ElementNodeBuilder#build});</li>
 * <li>the global element-ordering check ({@link GlobalElementOrderingCheck});</li>
 * <li>every declarative rule of the selected packages — {@link #validate} resolves them per run
 * from the requested families at the document's version — evaluated via {@link RuleEvaluator},
 * recording one {@link RuleExecution} per rule.</li>
 * </ol>
 *
 * <p>
 * Findings are assembled in that emission order: pre-pass, ordering, then rules.
 * </p>
 *
 * <h2>Unparseable documents</h2>
 * <p>
 * If the DOM parse throws (not well-formed, DOCTYPE, …) the engine does <b>not</b> propagate the
 * exception. It returns a report carrying the pre-pass findings plus one fatal finding: the
 * pre-pass already classifies a well-formedness failure as an XSD-category finding
 * ({@code PMDA-OD0001}), so that finding is reused; only if no such XSD-category finding is present
 * is a synthetic {@code DEFINE-XML-XSD} ({@link Category#XSD}, {@link Severity#ERROR}) finding
 * appended. The execution summary is empty in that case (no rule ran).
 * </p>
 */
public final class DefineConformanceEngine
{

    /**
     * Synthetic rule id for a fatal parse failure not attributable to a classified pre-pass
     * finding.
     */
    private static final String XSD_FALLBACK_RULE_ID = "DEFINE-XML-XSD";

    /**
     * The corpus, when the caller supplied one. {@code null} means this engine resolves its own
     * rules from {@link DefineConformanceInput}, which it cannot do until {@link #validate} has
     * read the document and learned its Define-XML version.
     */
    private final @Nullable List<ConformanceRule> explicitRules;

    private final GlobalElementOrderingCheck orderingCheck = new GlobalElementOrderingCheck();

    private final RuleEvaluator evaluator = new RuleEvaluator();

    /**
     * Builds an engine that resolves its own rules, per run, from the
     * {@link DefineConformanceInput} handed to {@link #validate}: the packages for the requested
     * families at the document's Define-XML version.
     *
     * <p>
     * ⚑ This used to load the whole YAML corpus eagerly via {@code RuleRepository.loadDefault()}.
     * It cannot any more, and the reason is ordering rather than taste: a package is keyed by
     * {@code (family, version)}, and the version is a property of the document, which no-one has
     * read at construction time. Selection therefore happens inside {@code validate}, immediately
     * after the pre-pass resolves the version — mirroring {@code StudyValidationService}, where the
     * caller states its selection and the service performs the lookup.
     * </p>
     */
    public DefineConformanceEngine()
    {
        explicitRules = null;
    }


    /**
     * Builds an engine over a supplied rule set (test/fixture and embedder entry point). Rejects an
     * empty corpus so a mispathed / empty rules directory can never validate a document and report
     * it clean — the same fail-loud guarantee {@link RuleRepository#loadResolved} enforces, applied
     * at the engine boundary too.
     */
    public DefineConformanceEngine(List<ConformanceRule> aRules)
    {
        if (aRules.isEmpty())
        {
            throw new IllegalStateException(
                    "no Define-XML conformance rules supplied — refusing to validate against an "
                            + "empty corpus (a clean report on an empty ruleset would be misleading)");
        }
        explicitRules = List.copyOf(aRules);
    }


    /** Validates a document, timestamping the report with {@link Instant#now()}. */
    public DefineConformanceReport validate(DefineConformanceInput aInput)
    {
        return validate(aInput, Instant.now());
    }


    /**
     * Validates a document, using {@code aGeneratedAt} as the report timestamp (so tests can pin
     * it). Reading the define.xml bytes is the only failure that escapes as an exception
     * ({@link UncheckedIOException}); a parseable-but-invalid or unparseable document is reported,
     * never thrown (see class javadoc).
     */
    public DefineConformanceReport validate(DefineConformanceInput aInput, Instant aGeneratedAt)
    {
        byte[] bytes;
        try
        {
            bytes = Files.readAllBytes(aInput.defineXml());
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot read " + aInput.defineXml(), e);
        }

        DefinePrePass.Result prePass = DefinePrePass.run(bytes);
        List<ConformanceFinding> prePassFindings = prePass.findings();

        // Define-XML 1.0 is outside this validator's scope (plan §2.5): the rule corpus is 2.0/2.1
        // only, so running it against a 1.0 document would emit meaningless findings. Short-circuit
        // with the detected version preserved and a single out-of-scope finding — unless the caller
        // forces a version override (then honour it, they know what they're doing).
        if (aInput.versionOverride() == null
                && prePass.version() == DefineXmlConverter.Version.V1_0)
        {
            return outOfScopeReport(aInput, aGeneratedAt, prePassFindings);
        }

        String version = resolveVersion(aInput.versionOverride(), prePass.version());

        // ⛔ PARSE BEFORE SELECTING. A not-well-formed document has no detectable version, and
        // selectRules refuses an unknown version by design (D10) — so selecting first turned
        // "broken XML" into "state --define-version", threw out of validate(), and destroyed the
        // unparseableReport path below that this class promises never to throw past. Selection
        // needs the version; reporting an unparseable document does not need the rules.
        Document document;
        try
        {
            document = DefineDomIo.parse(new ByteArrayInputStream(bytes));
        }
        catch (IOException | ParserConfigurationException | SAXException _)
        {
            return unparseableReport(aInput, version, aGeneratedAt, prePassFindings);
        }

        List<ConformanceRule> rules = explicitRules != null ? explicitRules
                : selectRules(aInput, version, prePass.version());

        ElementNode root = ElementNodeBuilder.build(document);
        DocumentContext context = new DocumentContext(root, version, aInput.ctProvider(),
                aInput.resolvedSubmissionFolder().orElse(null),
                ElementNodeBuilder.stylesheetHrefs(document), aInput.libraryProvider());

        List<ConformanceFinding> orderingFindings = orderingCheck.check(root);

        List<ConformanceFinding> ruleFindings = new ArrayList<>();
        List<RuleExecution> executions = new ArrayList<>();
        for (ConformanceRule rule : rules)
        {
            RuleResult result = evaluator.evaluate(rule, context);
            ruleFindings.addAll(result.findings());
            executions.add(
                    new RuleExecution(rule.ruleId(), result.status(), result.findings().size()));
        }

        List<ConformanceFinding> all = new ArrayList<>(
                prePassFindings.size() + orderingFindings.size() + ruleFindings.size());
        all.addAll(prePassFindings);
        all.addAll(orderingFindings);
        all.addAll(ruleFindings);
        return new DefineConformanceReport(aInput.defineXml().toString(), version, aGeneratedAt,
                all, executions);
    }


    /**
     * A report for a Define-XML 1.0 document: the version is labelled {@code "1.0"} (not
     * mislabelled 2.1), the pre-pass findings are kept, and one {@code DEFINE-XML-UNSUPPORTED}
     * finding records that the 2.0/2.1 rule corpus was not run. Empty execution summary.
     */
    private static DefineConformanceReport outOfScopeReport(DefineConformanceInput aInput,
            Instant aGeneratedAt, List<ConformanceFinding> aPrePassFindings)
    {
        List<ConformanceFinding> findings = new ArrayList<>(aPrePassFindings);
        findings.add(ConformanceFinding.builder() //
                .ruleId("DEFINE-XML-UNSUPPORTED") //
                .message("Define-XML 1.0 is outside this validator's scope; the 2.0/2.1 "
                        + "conformance rules were not evaluated.") //
                .category(Category.XSD) //
                .severity(Severity.WARNING) //
                .build());
        return new DefineConformanceReport(aInput.defineXml().toString(), "1.0", aGeneratedAt,
                findings, List.of());
    }


    private static DefineConformanceReport unparseableReport(DefineConformanceInput aInput,
            String aVersion, Instant aGeneratedAt, List<ConformanceFinding> aPrePassFindings)
    {
        List<ConformanceFinding> findings = new ArrayList<>(aPrePassFindings);
        if (findings.stream().noneMatch(DefineConformanceEngine::isXsdFatal))
        {
            findings.add(ConformanceFinding.builder() //
                    .ruleId(XSD_FALLBACK_RULE_ID) //
                    .message("Define-XML document is not well-formed and could not be parsed") //
                    .category(Category.XSD) //
                    .severity(Severity.ERROR) //
                    .build());
        }
        return new DefineConformanceReport(aInput.defineXml().toString(), aVersion, aGeneratedAt,
                findings, List.of());
    }


    /** A pre-pass finding that already reports the fatal well-formedness / schema failure. */
    private static boolean isXsdFatal(ConformanceFinding aFinding)
    {
        return aFinding.getCategory() == Category.XSD || "PMDA-OD0001".equals(aFinding.getRuleId())
                || XSD_FALLBACK_RULE_ID.equals(aFinding.getRuleId());
    }


    /**
     * Resolves the rule packages for this run: one per requested family, at the document's
     * Define-XML version, plus any additive {@code --rules-file}.
     *
     * <p>
     * Two things are hard errors here rather than defaults, both because a wrong answer would be
     * silent:
     * </p>
     * <ul>
     * <li><b>No family named.</b> Before 2026-09-01 an unspecified selection meant "load every rule
     * of both sheets" — a decision no caller had made, and one that quietly changed meaning as the
     * corpus grew.</li>
     * <li><b>No version known.</b> The old code fell back to {@code "2.1"} for an undetectable
     * document. That was survivable when the version only drove a per-rule gate; now it picks the
     * <em>package</em>, so an undetectable document would be validated against a version nobody
     * chose. State it with {@code --define-version} instead.</li>
     * </ul>
     */
    private static List<ConformanceRule> selectRules(DefineConformanceInput aInput, String aVersion,
            DefineXmlConverter.@Nullable Version aDetected)
    {
        if (aInput.families().isEmpty())
        {
            throw new DefineRuleSelectionException(
                    "no Define-XML rule family selected — name at least" + " one of "
                            + java.util.Arrays.toString(RuleSet.values())
                            + " (CLI: --define-family). There is deliberately no default: running every"
                            + " sheet is a choice, not an absence of one.");
        }
        // Blank-aware, matching resolveVersion: it treats a blank override as absent, so
        // testing only for null here let "" past the refusal and straight into the 2.1
        // fallback this exists to close. Not reachable from the CLI, which normalises
        // blank to null — but an embedder passing cfg.getOrDefault(..., "") would hit it.
        if (isBlank(aInput.versionOverride()) && aDetected == null)
        {
            throw new DefineRuleSelectionException("the Define-XML version of " + aInput.defineXml()
                    + " could not be determined, and it selects the rule package to validate"
                    + " against. State it explicitly with --define-version 2.0 / 2.1.");
        }

        Path dir = RuleRepository
                .resolveRulesDir(aInput.rulesDir() == null ? null : aInput.rulesDir().toString(),
                        System.getenv(RuleRepository.ENV_RULES_DIR),
                        System.getProperty(RuleRepository.SP_RULES_DIR))
                .orElseThrow(() -> new DefineRuleSelectionException(
                        "no Define-XML rules directory configured. Set --define-rules-dir, "
                                + RuleRepository.ENV_RULES_DIR + ", -D"
                                + RuleRepository.SP_RULES_DIR + ", or create "
                                + RuleRepository.DEFAULT_RULES_DIR + "."));

        DefineRulePackageManifest manifest;
        try
        {
            manifest = DefineRulePackageManifest.load(dir);
        }
        catch (IOException e)
        {
            throw new DefineRuleSelectionException(
                    "cannot read the Define-XML rule manifest in " + dir, e);
        }

        List<Path> files = new ArrayList<>();
        int counted = 0;
        for (RuleSet family : aInput.families())
        {
            DefineRulePackageManifest.Entry entry = manifest.find(family, aVersion)
                    .orElseThrow(() -> new DefineRuleSelectionException(
                            "no " + family + " rule package published for Define-XML " + aVersion
                                    + " in " + dir + " (published for that family: "
                                    + manifest.versionsFor(family) + ")"));
            Path file = dir.resolve(entry.file());
            if (!Files.isRegularFile(file))
            {
                throw new DefineRuleSelectionException("the manifest in " + dir + " names "
                        + entry.file()
                        + ", which is not there — the corpus is inconsistent with its index");
            }
            files.add(file);
            counted += entry.ruleCount();
        }
        files.addAll(aInput.rulesFiles());
        List<ConformanceRule> selected = RuleRepository.loadPackages(files);
        // packages.json documents ruleCount as a cheap corruption check; until now nothing in
        // production read it. A package truncated after the manifest was written loads fewer
        // rules than the index claims, and every rule it lost simply stops firing.
        // Additive files can only ADD — loadPackages has already rejected duplicates — so the
        // reconciliation holds as an INEQUALITY even when one is present. Skipping the check
        // outright whenever --define-rules-file was passed would switch corruption detection off
        // in exactly the configuration the container entrypoint recommends for site rules.
        boolean fewerThanIndexed = selected.size() < counted;
        boolean countDisagrees = aInput.rulesFiles().isEmpty() && selected.size() != counted;
        if (fewerThanIndexed || countDisagrees)
        {
            throw new DefineRuleSelectionException("the Define-XML corpus in " + dir
                    + " is corrupt: its manifest accounts for " + counted
                    + " rules but the selected packages hold " + selected.size()
                    + ". If you edited a package by hand, update its ruleCount in packages.json.");
        }
        return selected;
    }


    private static boolean isBlank(@Nullable String aValue)
    {
        return aValue == null || aValue.isBlank();
    }


    /**
     * The effective version string the applicable-version gate uses: the explicit override when
     * given, else the pre-pass detected version mapped to {@code "2.0"}/{@code "2.1"}. An
     * undetected version falls back to {@code "2.1"} — the newest — mirroring the pre-pass'
     * newest-schema default.
     *
     * <p>
     * ⚠ That fallback survives only for an engine built over an <b>explicit</b> rule list, where
     * the version merely gates rules that are already loaded. When the engine selects its own
     * packages the version <em>chooses</em> them, and {@link #selectRules} refuses an undetected
     * version outright rather than silently validating against 2.1.
     * </p>
     */
    private static String resolveVersion(@Nullable String aOverride,
            DefineXmlConverter.@Nullable Version aDetected)
    {
        if (aOverride != null && !aOverride.isBlank())
        {
            return aOverride;
        }
        if (aDetected == DefineXmlConverter.Version.V2_0)
        {
            return "2.0";
        }
        return "2.1";
    }

}
