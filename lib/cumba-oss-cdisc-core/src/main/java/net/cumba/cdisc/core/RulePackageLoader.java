package net.cumba.cdisc.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.SequencedMap;
import java.util.regex.PatternSyntaxException;
import net.cumba.cdisc.core.exec.OperandSubstitutor;
import net.cumba.cdisc.core.exec.OperandSubstitutor.OperandParseException;
import net.cumba.cdisc.core.exec.OperandSubstitutor.OperatorMismatchException;
import net.cumba.cdisc.core.exec.OperandSubstitutor.ParsedOperand;
import net.cumba.cdisc.core.exec.OperandSubstitutor.Position;
import net.cumba.cdisc.core.exec.OutputVariableDeriver;
import net.cumba.cdisc.core.exec.ProviderRequirements;
import net.cumba.cdisc.core.exec.RuleClassifier;
import net.cumba.cdisc.core.exec.ScopeMatcher;
import net.cumba.cdisc.core.exec.ScopeVariableEntry;
import net.cumba.cdisc.core.model.CheckCondition;
import net.cumba.cdisc.core.model.CheckConditionAll;
import net.cumba.cdisc.core.model.CheckConditionAny;
import net.cumba.cdisc.core.model.CheckConditionConstant;
import net.cumba.cdisc.core.model.CheckConditionLeaf;
import net.cumba.cdisc.core.model.CheckConditionNot;
import net.cumba.cdisc.core.model.DatasetScope;
import net.cumba.cdisc.core.model.DomainScope;
import net.cumba.cdisc.core.model.Executability;
import net.cumba.cdisc.core.model.ExecutabilityHint;
import net.cumba.cdisc.core.model.LevelCheck;
import net.cumba.cdisc.core.model.Operation;
import net.cumba.cdisc.core.model.Requirements;
import net.cumba.cdisc.core.model.Rule;
import net.cumba.cdisc.core.model.RulePackage;
import net.cumba.cdisc.core.model.Scope;
import net.cumba.cdisc.core.model.Sensitivity;
import net.cumba.cdisc.core.model.VariableRequirement;
import net.cumba.datatable.report.Severity;
import org.jspecify.annotations.Nullable;

public class RulePackageLoader
{

    private static final System.Logger LOGGER = System.getLogger(RulePackageLoader.class.getName());

    // ⚠ STRICT_DUPLICATE_DETECTION is load-bearing for the §3.3 level-map grammar: a duplicate
    // key ({"Check": {"ERROR": …, "ERROR": …}}) is collapsed last-wins by the tree parser BEFORE
    // RuleCheckDeserializer can see it, so a declared level would be dropped silently. Rejecting
    // duplicates at parse is the only place the defect is still visible; the parse exception
    // names the line/column rather than the rule, which is the best available signal here.
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false).configure(
                    com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION, true);

    public static RulePackage load(Path path) throws IOException
    {
        try (InputStream is = Files.newInputStream(path))
        {
            return load(is);
        }
    }


    public static RulePackage load(InputStream inputStream) throws IOException
    {
        return finishLoad(MAPPER.readValue(inputStream, RulePackage.class));
    }


    public static RulePackage loadFromString(String json) throws IOException
    {
        return finishLoad(MAPPER.readValue(json, RulePackage.class));
    }


    /**
     * Loads and merges every family-scoped package for one {@code (standard, version)} from a rules
     * directory — the union that the pre-split combined {@code rules-<standard>-<version>.json}
     * package held. Packages are resolved via the directory's {@code packages.json} manifest
     * ({@link RulePackageManifest#forStandardVersion}); each is fully {@link #load(Path) loaded}
     * (normalised / validated) and their rule maps combined. A {@code Core.Id} is unique to one
     * family, so the merge has no key collisions.
     *
     * @param rulesDir
     *            the rules directory (holds the family packages + {@code packages.json})
     * @param standard
     *            the standard name (display {@code SDTMIG} or encoded {@code sdtmig})
     * @param version
     *            the version (display {@code 3.4} or file-encoded {@code 3-4})
     * @return a {@link RulePackage} whose rule map is the union across families (possibly empty)
     * @throws IOException
     *             if the manifest or any package cannot be read
     */
    public static RulePackage loadFamilyUnion(Path rulesDir, String standard, String version)
        throws IOException
    {
        RulePackageManifest manifest = RulePackageManifest.load(rulesDir);
        // Core.Id-sorted so the union reproduces the pre-split combined package's rule order
        // exactly
        // (each family package is Core.Id-sorted; a Core.Id is unique to one family, so no
        // clashes).
        Map<String, Rule> merged = new java.util.TreeMap<>();
        for (RulePackageManifest.Entry e : manifest.forStandardVersion(standard, version))
        {
            RulePackage pkg = load(rulesDir.resolve(e.file()));
            if (pkg.getRules() != null)
            {
                merged.putAll(pkg.getRules());
            }
        }
        RulePackage union = new RulePackage();
        union.setRules(merged);
        return union;
    }


    /**
     * Compatibility shim for callers that referenced a pre-split combined
     * {@code rules-<standard>-<version>.json} file (the file no longer exists; its rules now live
     * in the per-family packages). Derives the rules directory and {@code (standard, version)} from
     * {@code combinedFile}'s parent and simple stem — the shipped combined names never used a
     * dashed standard — then returns the {@link #loadFamilyUnion family union}. The file is never
     * opened.
     *
     * @param combinedFile
     *            an old-style {@code .../rules/rules-<standard>-<version>.json} path
     * @return the family-union {@link RulePackage} for that {@code (standard, version)}
     * @throws IOException
     *             if the manifest or a package cannot be read
     */
    public static RulePackage loadCombined(Path combinedFile) throws IOException
    {
        Path dir = combinedFile.getParent();
        if (dir == null)
        {
            throw new IOException("combined rules path has no parent directory: " + combinedFile);
        }
        Path fileName = combinedFile.getFileName();
        String name = fileName == null ? "" : fileName.toString();
        if (!name.startsWith("rules-") || !name.endsWith(".json"))
        {
            throw new IOException("not a combined rules file name: " + name);
        }
        String stem = name.substring("rules-".length(), name.length() - ".json".length());
        int dash = stem.indexOf('-');
        if (dash <= 0 || dash == stem.length() - 1)
        {
            throw new IOException("cannot parse standard-version from: " + name);
        }
        return loadFamilyUnion(dir, stem.substring(0, dash), stem.substring(dash + 1));
    }


    /**
     * Shared post-parse pipeline for both load entry points: validation / normalisation /
     * native-retention over the already-bound package.
     * <p>
     * Empty input never reaches here — {@code readValue} itself raises a
     * {@link com.fasterxml.jackson.databind.exc.MismatchedInputException} (an {@link IOException})
     * for end-of-input. The {@code null} guard below covers the JSON literal {@code null}, which
     * binds to a {@code null} package; it keeps that case an {@link IOException} rather than
     * returning {@code null} to the caller.
     * </p>
     */
    private static RulePackage finishLoad(@Nullable RulePackage pkg) throws IOException
    {
        if (pkg == null)
        {
            throw new IOException("No content to map to a RulePackage (empty input)");
        }
        // Fix #159 — BEFORE every other pass: a rule declaring Executability: "Not Executable" is
        // parked, i.e. dropped from the package here and never seen again. Running it through
        // normalisation, derivation and the load gates first would spend work on a rule that will
        // never execute, and — worse — would let a parked rule acquire a loadError nobody can act
        // on. Executability is bound by Jackson at parse time, so it is already readable here.
        // Plan C §3.4 (ruling 4) — BEFORE anything is parked, derived or evaluated: a package that
        // declares a run severity threshold is rejected whole. The key is not a rule's problem and
        // has no per-rule channel, so it fails the load rather than becoming 3 804 identical
        // loadErrors; and it must fail rather than be dropped, or an author would believe a
        // threshold was in force when it was not.
        validateNoPackageSeverityThreshold(pkg);
        removeParkedRules(pkg);
        // normalizeOperations FIRST: it is the only pass that fills in operator/group/filter/domain
        // for an expression-form (Form B) operation, and the derivation reads exactly those four
        // fields. Deriving before it leaves 947 Form-B operations looking operator-less, which
        // silently collapses 80 rules to Record Data / Record.
        normalizeOperations(pkg);
        // Derivation (Sensitivity) runs BEFORE the field gates, whose Group-consistency check
        // reads the derived value; the shipped rules/ corpus does not author it.
        deriveOmittedFields(pkg);
        validateOperandSubstitution(pkg);
        validateEnumFields(pkg);
        // Sequenced with its sibling load gates, and deliberately BEFORE retainNativeExpr, so it
        // judges the AUTHORED Check — the shape rules-legacy/ carries and the shape the rule's
        // author wrote. It would also read correctly afterwards, but only because
        // inlineVariableExistsOps / inlineSplitByOps rewrite `Check` (and `Precondition`) in
        // lockstep with dropping the operations they inlined (see the setCheck calls in each): an
        // inliner that dropped an operation WITHOUT rewriting the tree would make its own operand
        // look undefined here. That lockstep is pinned by
        // DanglingOperationReferenceLoadTest.inlinedOperationsAreDroppedInLockstepWithTheCheck.
        validateOperationReferences(pkg);
        // Fix #156 — same silence class, one field-position over: a `--` parked in an Operation's
        // reference / ordering / offset is copied verbatim by OperationExecutor.resolvePrefixes,
        // reaches getColumnIndex as the literal "--…", misses, and the operation yields nothing.
        // Runs after normalizeOperations (so a Form-B expression operation has its fields bound)
        // and after retainNativeExpr is still pending, so the AUTHORED Check is what gets walked —
        // the same surface validateOperationReferences judges.
        validateUnresolvedOperationWildcards(pkg);
        // D13 item 3 — a dictionary operation naming no external_dictionary_type is unanswerable
        // by ANY install, so it is an authoring defect on the loadError channel, exactly like the
        // dangling $ above. Deliberately BEFORE injectInlineOperationGates: the injector only
        // gates a TYPED inline dictionary call, so a typeless one would otherwise evaluate with
        // no gate and no provider and silently false-pass (the closed hole this guard exists
        // for); running first also means the walk judges the authored Check only, though the
        // guard's dictionary_available exclusion would make it injection-safe either way.
        validateDictionaryOperationTypes(pkg);
        normalizeJoinTypes(pkg);
        injectInlineOperationGates(pkg);
        retainNativeExpr(pkg);
        // AFTER retainNativeExpr: the OV derivation reads checkExpr and must see the
        // post-inlining state — inlineVariableExistsOps / inlineSplitByOps have already dropped the
        // OV entries of the operations they inlined away. ⚠ Not all of them: a variable_exists
        // operation whose $-id the rule REPORTS is now retained (VariableExistsInliner.reported),
        // so the derivation sees a live operation plus its authored OV entry — which is exactly the
        // state it is meant to read, and why this ordering still holds.
        deriveOutputVariables(pkg);
        return pkg;
    }


    /**
     * Plan C &#167;3.4, ruling 4 — <b>a rule package may not carry a run severity threshold</b>.
     *
     * <p>
     * The threshold says which of a rule's declared levels this <em>run</em> evaluates. Attached to
     * a package it would make one rule behave differently in two packages, which is precisely what
     * the {@code rules/} findings-diff invariant forbids: a rule's behaviour is a property of the
     * rule, and the corpus is the thing acceptance is measured against. The three legitimate
     * surfaces are the CLI's {@code --severity-level}, the REST {@code CheckRunRequest} field and
     * the {@code .cdt} {@code #runLevel} directive.
     * </p>
     *
     * @param pkg
     *            the freshly-bound package
     * @throws IOException
     *             if the package declares any {@link #THRESHOLD_KEYS} spelling
     */
    static void validateNoPackageSeverityThreshold(RulePackage pkg) throws IOException
    {
        for (String key : pkg.getUnknownKeys())
        {
            if (THRESHOLD_KEYS.contains(key))
            {
                throw new IOException("rule package declares '" + key
                        + "' — the severity threshold is a RUN option (--severity-level /"
                        + " CheckRunRequest.severityThreshold / #runLevel), never a package or"
                        + " per-rule field");
            }
        }
    }

    // ---------------------------------------------------------------------
    // Fix #159 — `Executability: "Not Executable"` parks a rule
    // ---------------------------------------------------------------------

    /**
     * Prefix on every parking log line, so a reader (and the corpus ratchets) can find them without
     * matching prose.
     */
    private static final String PARKED_MARKER = "[parked]";

    /**
     * Drops every rule declaring {@code Executability: "Not Executable"} from {@code pkg}, so it is
     * never normalised, never validated and never run.
     *
     * <p>
     * <b>The field now means what its name says.</b> Until {@code Fix #159} it was purely a
     * load-guard <em>severity switch</em>: {@link #validateOperationReferences(Rule)} and
     * {@link #validateUnresolvedOperationWildcards(Rule)} consulted it to downgrade their
     * {@code loadError} to a {@code loadWarning}, so a rule could declare itself not executable and
     * then execute, report findings and be counted. The justification recorded for that — two
     * shipped ADaM rules authored ahead of engine capability, {@code CDISC-AD0591} and
     * {@code CDISC-AD0898}, which "must keep loading and running" — expired when {@code Fix #147}
     * made both {@code Fully Executable} via author-declared {@code Expansion:} tokens. Both
     * severity branches are gone; there is nothing left to downgrade, because a parked rule never
     * reaches a load gate.
     * </p>
     *
     * <p>
     * <b>Why the corpus generator ALSO skips these rules, and why that is not duplication.</b>
     * {@code RuleStorageAssembler} (the {@code rules-src/} → {@code rules-legacy/} → {@code rules/}
     * regeneration flow) omits a parked rule from the shipped packages entirely — which is what
     * parks it in the <em>Python fork</em> too, since {@code rules-legacy/} is the fork's corpus
     * and the fork has no equivalent of this pass. That skip cannot replace this one: the rule
     * editor, the CDISC-Library API path and any hand-authored package all bypass the generation
     * tool, so a {@code Not Executable} rule can still reach the engine. Both checks are required;
     * neither is redundant.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>A caller that bypasses {@link #finishLoad} must invoke this method itself.</b>
     * {@link LibraryRuleMapper#mapRulePackage} is exactly that case — it hand-picks the passes that
     * make sense without {@code normalizeOperations} rather than running the whole pipeline — so it
     * calls this explicitly. That is why this method is package-private rather than private. Any
     * future path that assembles a {@link RulePackage} outside {@code finishLoad} inherits the same
     * obligation, or a rule sourced through it will declare itself not executable and run anyway. ⚠
     * The upstream CDISC-Library corpus is <em>not</em> this repo's corpus and is not under this
     * project's control, so "zero rules declare the value today" is a fact about {@code rules-src/}
     * only.
     * </p>
     *
     * <p>
     * <b>Warning granularity.</b> One {@code WARNING} line <em>per package</em> — the count plus
     * the parked ids — because one line per rule per run is how people learn to ignore warnings.
     * The per-rule detail, including each rule's {@code ExecutabilityHint.Detail} (the "why"), is
     * logged at {@code DEBUG}. A package that parks nothing logs nothing at all.
     * </p>
     *
     * @param pkg
     *            the package to prune in place, may be {@code null}
     */
    static void removeParkedRules(@Nullable RulePackage pkg)
    {
        if (pkg == null || pkg.getRules() == null)
        {
            return;
        }
        List<String> parked = new ArrayList<>();
        for (java.util.Iterator<Map.Entry<String, Rule>> it = pkg.getRules().entrySet()
                .iterator(); it.hasNext();)
        {
            Map.Entry<String, Rule> entry = it.next();
            Rule rule = entry.getValue();
            if (rule == null || rule.getExecutability() != Executability.NOT_EXECUTABLE)
            {
                continue;
            }
            it.remove();
            // The rule's own Core.Id first, the map key only as a fallback. ⚠ The two are NOT
            // interchangeable: in a shipped package the key IS the Core.Id, but on the
            // CDISC-Library path (LibraryRuleMapper.mapRuleMap) it is the rule's UUID — naming
            // that in a warning tells the reader nothing. The key still beats ruleId()'s
            // "<unknown>" literal for a body that omits `Core` altogether, which is the only case
            // where a multi-rule summary would otherwise degrade to "<unknown>, <unknown>".
            String id = ruleId(rule);
            if (UNKNOWN_RULE_ID.equals(id) && entry.getKey() != null && !entry.getKey().isBlank())
            {
                id = entry.getKey();
            }
            parked.add(id);
            LOGGER.log(System.Logger.Level.DEBUG, "{0}",
                    PARKED_MARKER + " " + id
                            + " declares Executability: \"Not Executable\" and was not loaded — "
                            + parkingReason(rule));
        }
        if (parked.isEmpty())
        {
            return;
        }
        // Sorted so the summary is deterministic regardless of the package's rule-map ordering.
        List<String> ids = parked.stream().sorted().toList();
        LOGGER.log(System.Logger.Level.WARNING, "{0}", PARKED_MARKER + " " + ids.size() + " rule"
                + (ids.size() == 1 ? "" : "s") + " declaring Executability: \"Not Executable\" "
                + (ids.size() == 1 ? "was" : "were") + " not loaded and will not run: "
                + String.join(", ", ids) + " (enable DEBUG on " + RulePackageLoader.class.getName()
                + " for each rule's ExecutabilityHint)");
    }


    /**
     * The parked rule's own stated reason — its {@code ExecutabilityHint.Detail}, falling back to
     * the hint {@code Category} and then to an explicit "none declared", so the DEBUG line never
     * silently trails off. A parked rule with no hint is a silent delete; saying so is how that
     * becomes visible.
     */
    private static String parkingReason(Rule rule)
    {
        ExecutabilityHint hint = rule.getExecutabilityHint();
        if (hint == null)
        {
            return "no ExecutabilityHint declared";
        }
        String detail = hint.getDetail();
        if (detail != null && !detail.isBlank())
        {
            return detail;
        }
        String category = hint.getCategory();
        return category != null && !category.isBlank() ? "ExecutabilityHint Category: " + category
                : "no ExecutabilityHint declared";
    }


    /**
     * Defaults each {@code Match_Datasets} entry's {@code Join_Type} to {@code inner} when absent.
     *
     * <p>
     * <b>What it means today.</b> A corpus rule that omits {@code Join_Type} is joined
     * {@code inner}, so an unmatched primary row is dropped. Rules that must keep an unmatched
     * primary row — absence / empty checks such as CDISC-AD0053 ({@code DM.USUBJID empty} for a
     * subject not in DM) — carry an explicit {@code Join_Type: left}. That is the only value the
     * shipped corpus authors at all (158 occurrences across {@code rules/*.json} when this was last
     * measured, {@code Fix #233}); it authors no {@code inner} and no other value. Independent of
     * the Check format: {@code Match_Datasets} is a {@code Rule} property, the same for leaf-form
     * and native-expression rules, so one pass covers both.
     * </p>
     *
     * <p>
     * ⚠ <b>The original justification for choosing {@code inner} has expired.</b> It was chosen to
     * mirror the Python engine's {@code merge_sdtm_datasets}, which fell back to {@code inner} for
     * a rule-dataset entry without {@code join_type} ({@code dataset_preprocessor.py}), so that a
     * rule omitting the field produced the same rows in both engines. Wave 33 deleted
     * {@code rules-legacy/} and the entire Python lane — <b>there is no second engine to agree with
     * any more.</b> The behaviour is deliberately left unchanged, but it is now justified only by
     * compatibility with the findings the shipped corpus currently produces, not by parity. Whether
     * {@code inner} is the right default at all is an open behavioural question (triage finding S2,
     * {@code plans/PLAN-expired-justifications-triage.md}, and
     * {@code plans/done/PLAN-outer-join-type.md}).
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Downstream consequence, easy to miss.</b> After this pass <b>no</b> loaded
     * {@code MatchDataset} has a null or blank {@code Join_Type}, so any code that treats "the
     * field is absent" as a distinguishable state is dead for loader-loaded rules. That is
     * precisely how {@code RuleCohortGrouper.equalityCohortKey}'s {@code getJoinType() != null}
     * rejection became unreachable for the corpus (EC-74) — while staying reachable for the
     * {@code CDISC-AD0591-<domain>-<var>} rules {@code RuleGenerator} built per dataset, which
     * never come through here. The engine's {@code KeyMatchRowExpander} keeps {@code left} as a
     * defensive fallback for exactly those loader-bypassing rules.
     * </p>
     *
     * <p>
     * ⚑ <b>Since Fix #366 that family no longer runs in production.</b> {@code LibraryValidator}
     * enables only {@code RuleCategory.corpusDeliveryOnly()}, so {@code CROSS_DATASET_METADATA} —
     * the sole minter of those rules — never fires; the {@code getJoinType() != null} rejection and
     * the {@code left} fallback are now unreachable outside tests. They stay because the generator
     * code stays (disable now, delete later); both become deletable with it.
     * </p>
     */
    private static void normalizeJoinTypes(RulePackage pkg)
    {
        if (pkg == null || pkg.getRules() == null)
        {
            return;
        }
        for (Rule rule : pkg.getRules().values())
        {
            normalizeJoinTypes(rule);
        }
    }


    /**
     * Per-rule {@code Join_Type} normalisation: injects the default ({@code inner}) into any
     * {@code Match_Datasets} entry that carries no explicit {@code Join_Type}. See
     * {@link #normalizeJoinTypes(RulePackage)} for what that default means and why its original
     * justification has expired.
     *
     * <p>
     * Public so a harness that bypasses {@link #load} can apply the identical normalisation a
     * production load performs rather than re-implementing it. The parity module's
     * {@code RuleScaffold} is the only caller.
     * </p>
     *
     * <p>
     * ⚠⚠ {@code RuleGenerator} also bypasses {@link #load} — it calls {@link #installNativeExpr}
     * and the Output_Variables derivation, but <b>not</b> this method. Its generated
     * {@code Match_Datasets} (e.g. the {@code CDISC-AD0591-}/{@code GEN-XDVAL-} cross-dataset value
     * family) therefore keep a null {@code Join_Type} and are executed as a <b>left</b> join via
     * {@code KeyMatchRowExpander}'s fallback, whereas a corpus rule with the identical
     * {@code Match_Datasets} is executed as an <b>inner</b> join. Whether that divergence is
     * intended has not been established; it is recorded, not resolved, by {@code Fix #233}. ⚑
     * <b>Moot for shipped runs since Fix #366</b>: that family is no longer generated in
     * production, so the divergence survives only as a property of test-constructed generators.
     * </p>
     */
    public static void normalizeJoinTypes(Rule rule)
    {
        if (rule == null || rule.getMatchDatasets() == null)
        {
            return;
        }
        for (net.cumba.cdisc.core.model.MatchDataset md : rule.getMatchDatasets())
        {
            // Fix #236: identical predicate and identical stamped value, now expressed through
            // the JoinType vocabulary so the default and the load-time gate cannot drift apart.
            if (md != null && net.cumba.cdisc.core.model.JoinType.isAbsent(md.getJoinType()))
            {
                md.setJoinType(net.cumba.cdisc.core.model.JoinType.INNER.getJsonValue());
            }
        }
    }


    /**
     * Rewrites every {@code Operation} authored in function-call form (Form B,
     * {@code Operation.expression}) to its equivalent field form via
     * {@link net.cumba.cdisc.core.expr.convert.OperationExpressionParser}, so the
     * {@code OperationExecutor} only ever sees field-form operations. A field-form operation passes
     * through unchanged. A malformed operation expression is filed on the rule's {@code loadError}
     * channel (so the rule reports ERROR and never evaluates), mirroring the native-install path.
     * Runs before {@link #retainNativeExpr}, which does not inspect operations.
     */
    private static void normalizeOperations(RulePackage pkg)
    {
        if (pkg == null || pkg.getRules() == null)
        {
            return;
        }
        for (Rule rule : pkg.getRules().values())
        {
            normalizeOperations(rule);
        }
    }


    /**
     * Per-rule variant of {@link #normalizeOperations(RulePackage)}: rewrites every expression-form
     * (Form B) {@code Operations} entry to the field form the {@code OperationExecutor} consumes.
     * Public for the same reason as {@link #deriveOmittedFields(Rule)}: anything that binds a
     * {@link Rule} outside this loader — the parity harness ({@code RuleScaffold}), a tool, an
     * editor preview — must apply the same pass, or a shipped native rule's declared operations
     * look operator-less and silently resolve {@code null}. Idempotent; a malformed expression
     * lands on the rule's {@code loadError} channel, preserving any earlier cause.
     *
     * @param rule
     *            the rule to normalise in place, may be {@code null}
     */
    public static void normalizeOperations(@Nullable Rule rule)
    {
        if (rule == null)
        {
            return;
        }
        List<Operation> ops = rule.getOperations();
        try
        {
            if (ops != null && !ops.isEmpty())
            {
                ops.replaceAll(
                        net.cumba.cdisc.core.expr.convert.OperationExpressionParser::normalize);
                // EC-51 Half B — re-run the per-operation guard over the normalised list so a
                // FIELD-FORM operation gets the identical treatment: it never passes through
                // `fromCall`, and Jackson binds `missing_values` on an operator that cannot
                // consume it without complaint. Idempotent for the operations just normalised.
                for (Operation op : ops)
                {
                    net.cumba.cdisc.core.expr.convert.OperationExpressionParser
                            .validateMissingValues(op);
                    // Same three-surface reasoning as missing_values: a FIELD-FORM operation never
                    // passes through `fromCall`, so Jackson binds `keep_missings` on an operator
                    // that cannot consume it without complaint.
                    net.cumba.cdisc.core.expr.convert.OperationExpressionParser
                            .validateKeepMissings(op);
                    // EC-85: same again for `model_class` — and this one also NORMALISES the
                    // field-form value, so the executor only ever sees the canonical spelling.
                    net.cumba.cdisc.core.expr.convert.OperationExpressionParser
                            .validateModelClass(op);
                }
            }
            // ⚠ NOT inside the `ops` guard: an operation authored INLINE in the Check expression
            // never appears in `Operations` at all, so a rule with no `Operations` list can still
            // declare `missing_values` — and would otherwise skip all three rejections.
            validateMissingValuesPolarity(rule, ops);
        }
        catch (net.cumba.cdisc.core.expr.RuleDefinitionException ex)
        {
            // Preserve any earlier load error (e.g. an enum/scope validation failure) rather
            // than clobbering it — the rule ERRORs either way, but the first cause is kept.
            String prior = rule.getLoadError();
            rule.setLoadError(prior != null ? prior + "; " + ex.getMessage() : ex.getMessage());
        }
    }


    /**
     * EC-51 Half B / OQ3 — rejects {@code missing_values: "indeterminate"} on an operation whose
     * result is consumed by a <b>positive-polarity</b> leaf, because there the declaration does the
     * exact opposite of what its author intends.
     *
     * <p>
     * {@code indeterminate} makes the operation yield <em>no value</em> for a group holding a
     * missing candidate, and a {@code $}-ref that resolves to no value reaches the primitives as a
     * null operand. {@code Primitives.dateComparison}, {@code datePartComparison} and
     * {@code comparison} answer that unconditionally — {@code negate} for the first two, "no
     * violation" for the third — so a <em>negative</em> date consumer ({@code date_not_equal_to})
     * reports and a <em>positive</em> one ({@code date_greater_than}, {@code date_less_than},
     * {@code date_equal_to}) goes silent. Measured over the shipped corpus that is not an edge
     * case: 21 of the 35 date-extreme rules consume their extreme positively, 17 through
     * {@code date_greater_than}/{@code date_less_than} and 4 through a positive self-anchoring
     * {@code equal_to} inside the same {@code all:}.
     * </p>
     *
     * <p>
     * ⚠ <b>{@code equal_to} is the one family whose answer is conditional, and it is classified by
     * the case that matters.</b> {@code Primitives.equality} routes through
     * {@code ScalarSemantics.equalsNumericAware}, which folds <em>both</em> a missing cell and a
     * null target to {@code ""} — so {@code equal_to} against a no-value extreme fires when the
     * compared column is <em>itself</em> empty, and goes silent when it is populated. The populated
     * case is the one the rule is written for, and silence there is unrecoverable, so
     * {@code equal_to} sits in the silencing set. By the same token {@code not_equal_to} does
     * <em>not</em> report on an empty-vs-empty row; the disposition's promise is therefore "reports
     * on a populated row", not "always reports".
     * </p>
     *
     * <p>
     * Nothing downstream would catch it — the rule would simply stop reporting — and a silent kill
     * runs against the house {@code absent ⇒ report} default. So it is a load error, on the same
     * channel as the per-operator guard in
     * {@link net.cumba.cdisc.core.expr.convert.OperationExpressionParser#validateMissingValues}.
     * </p>
     *
     * <p>
     * <b>Deliberately conservative.</b> It rejects on <em>any</em> positive-polarity consuming
     * leaf, without modelling whether that leaf sits under {@code all:} (where one silent conjunct
     * kills the rule) or {@code any:} (where it only loses one alternative). Over-rejection is loud
     * and the author restructures; under-rejection is the silent death this guard exists to
     * prevent. An enclosing {@code not:} <em>is</em> modelled, because it genuinely inverts which
     * side reports, and the {@code Precondition} tree is walked alongside the {@code Check} because
     * a gate that stops firing stops the rule just as dead. Operators outside the two enumerated
     * sets are not judged at all.
     * </p>
     *
     * <p>
     * Costs nothing on a corpus that declares the field nowhere: the walk is entered only when some
     * operation actually carries {@code indeterminate}.
     * </p>
     *
     * @throws net.cumba.cdisc.core.expr.RuleDefinitionException
     *             if a declared operation is consumed by a silencing leaf
     */
    private static void validateMissingValuesPolarity(Rule rule, @Nullable List<Operation> ops)
    {
        List<String> declared = new ArrayList<>();
        if (ops != null)
        {
            for (Operation op : ops)
            {
                String id = op == null ? null : op.getId();
                if (op != null
                        && Operation.MISSING_VALUES_INDETERMINATE.equals(op.getMissingValues())
                        && id != null && !id.isEmpty())
                {
                    declared.add(id);
                }
            }
        }
        // ⚠ An INLINE operation call carries its own declaration and has no `$`-id to collect, so
        // the two surfaces are validated together: `validateInlineMissingValues` applies the value
        // and operator rejections to every inline call in the tree, and the polarity walk below
        // recognises a declaring call as a consumer in its own right.
        // ⚑ Plan C §3.3: EVERY declared level, not just the strictest. A gate that reads
        // getCheck() alone sees nothing of a weaker level, so a rejected inline `missing_values`
        // sitting in an INFO level would load clean and mis-evaluate at runtime.
        for (CheckCondition level : rule.checkConditions())
        {
            validateInlineMissingValues(level);
        }
        validateInlineMissingValues(rule.getPrecondition());
        Map<String, String> silencing = new LinkedHashMap<>();
        for (CheckCondition level : rule.checkConditions())
        {
            collectSilencingConsumers(level, declared, false, silencing);
        }
        // The Precondition (Fix #13) gates whether the Check runs at all, so a positive-polarity
        // consumer there silences the rule just as effectively as one in the Check itself.
        collectSilencingConsumers(rule.getPrecondition(), declared, false, silencing);
        if (!silencing.isEmpty())
        {
            Map.Entry<String, String> first = silencing.entrySet().iterator().next();
            throw new net.cumba.cdisc.core.expr.RuleDefinitionException("`missing_values: "
                    + Operation.MISSING_VALUES_INDETERMINATE + "` on operation `" + first.getKey()
                    + "` is consumed by the positive-polarity leaf `" + first.getValue()
                    + "`, which reads an undeterminable extreme as \"no violation\" and would"
                    + " silence the check instead of reporting it");
        }
    }


    /**
     * EC-51 Half B — applies the value and operator rejections of
     * {@link net.cumba.cdisc.core.expr.convert.OperationExpressionParser#validateMissingValues} to
     * every operation authored <b>inline</b> in a native Check expression.
     *
     * <p>
     * ⚠ <b>The inline surface is a genuinely separate load path.</b> An inline call never reaches
     * the rule's {@code Operations} list, so nothing in {@code normalizeOperations} sees it; and
     * the native compiler's own {@code fromCall} throw would only degrade the rule to LEGACY
     * evaluation rather than erroring it, which is the silent outcome this field's design rules
     * out. Validating here puts the inline surface on the same {@code loadError} channel as the
     * other two.
     * </p>
     */
    private static void validateInlineMissingValues(@Nullable CheckCondition condition)
    {
        if (condition instanceof net.cumba.cdisc.core.model.CheckConditionExpression expression)
        {
            validateInlineMissingValues(expression.expr());
        }
        else if (condition instanceof CheckConditionAll all)
        {
            all.getConditions().forEach(RulePackageLoader::validateInlineMissingValues);
        }
        else if (condition instanceof CheckConditionAny any)
        {
            any.getConditions().forEach(RulePackageLoader::validateInlineMissingValues);
        }
        else if (condition instanceof CheckConditionNot not)
        {
            validateInlineMissingValues(not.getCondition());
        }
        else if (condition instanceof CheckConditionLeaf leaf && leaf.getRelation() != null)
        {
            // EC-87 ⚠⚠ The deserializer LOWERS an authored expression to this leaf whenever it
            // can, so for every shipped carrier the `relation=` kwarg arrives here as a leaf
            // field, never as an inline call — and a bad value on the leaf would otherwise make
            // `CheckToExpr` / `ExprCompiler` throw an ExpressionException that `tryRaiseToExpr`
            // swallows, leaving the rule on the LEGACY leaf evaluator, which knows no relation:
            // the silent fallback to identity this validation exists to rule out.
            validateLeafRelation(leaf);
        }
    }


    /**
     * EC-87 — the declared-leaf half of {@link #validateInlineCheckRelation}: a {@code relation} on
     * an operator other than the next-record pair, or with a spelling outside
     * {@link net.cumba.cdisc.core.model.NextRecordRelation#SPELLINGS}, is a load error.
     */
    private static void validateLeafRelation(CheckConditionLeaf leaf)
    {
        String op = leaf.getOperator();
        if (op == null || !net.cumba.cdisc.core.model.NextRecordRelation.OPERATORS.contains(op))
        {
            throw new net.cumba.cdisc.core.expr.RuleDefinitionException(
                    "`relation` is not supported by `" + op
                            + "`; only has_next_corresponding_record consumes it");
        }
        if (net.cumba.cdisc.core.model.NextRecordRelation.fromSpelling(leaf.getRelation()) == null)
        {
            throw new net.cumba.cdisc.core.expr.RuleDefinitionException("unknown `relation` `"
                    + leaf.getRelation() + "` on `" + op + "`; expected one of "
                    + net.cumba.cdisc.core.model.NextRecordRelation.SPELLINGS);
        }
    }


    private static void validateInlineMissingValues(net.cumba.cdisc.core.expr.ast.Expr expr)
    {
        if (expr instanceof net.cumba.cdisc.core.expr.ast.Expr.Call call)
        {
            boolean isOperationCall = net.cumba.cdisc.core.model.OperationType
                    .fromJson(call.name()) != null;
            if (isOperationCall && (call.kwargs().containsKey("missing_values")
                    || call.kwargs().containsKey("keep_missings")
                    || call.kwargs().containsKey("model_class")))
            {
                // Rebuilding the Operation is what applies BOTH rejections: fromCall runs the kwarg
                // loop (so a list/number value is rejected by the same code as Form B) and then
                // validateMissingValues (value enum + consuming operator + date_diff_days Mode 2),
                // validateKeepMissings (consuming operator + non-empty group) and EC-85's
                // validateModelClass (consuming operator + known class spelling).
                net.cumba.cdisc.core.expr.convert.OperationExpressionParser.fromCall(call, null);
            }
            else if (!isOperationCall && call.kwargs().containsKey("keep_missings"))
            {
                // ⚠⚠ `keep_missings` is the FIRST parameter to appear on BOTH the operation surface
                // and the Check-operator surface, so it cannot be routed through `fromCall` the way
                // `missing_values` is: `fromCall` rejects any name that is not an OperationType,
                // and
                // an inline `has_multiple_values_for(..., keep_missings=false)` would become a
                // bogus
                // "unknown operation function" load error on a perfectly valid rule.
                validateInlineCheckKeepMissings(call);
            }
            if (!isOperationCall && call.kwargs().containsKey("relation"))
            {
                // EC-87: the next-record comparison relation is a Check-operator kwarg only.
                validateInlineCheckRelation(call);
            }
            if (!isOperationCall)
            {
                validateInlineUniqueSetShape(call);
            }
        }
        childrenOf(expr).forEach(RulePackageLoader::validateInlineMissingValues);
    }

    /** The uniqueness pair whose canonical authored form is {@code f([A, B, …])} (2026-08-23). */
    private static final java.util.Set<String> UNIQUE_SET_OPERATORS = java.util.Set
            .of("is_unique_set", "is_not_unique_set");

    /**
     * Owner requirement #1 (2026-08-23,
     * {@code plans/PLAN-authoring-grammar-unique-set-and-output-exclusion.md}): rejects, on the
     * {@code loadError} channel, every spelling of {@code is_(not_)unique_set} other than the
     * canonical single list operand {@code f([A, B, …])} — the retired {@code f(A, keys=[…])} /
     * {@code f(A, B)} / {@code f(A)} forms (with the migration text) and the authored empty list
     * {@code f([])} (the degenerate all-members-drop tuple is a runtime contract, never an authored
     * one).
     *
     * <p>
     * ⚠⚠ It must be a LOAD error, not an {@code ExprCompiler} {@code unsupported(...)}: the
     * compiler's throw DEGRADES the rule (it stops running) where the author expects an error — the
     * {@link #validateInlineCheckKeepMissings} reasoning. ⚠ It reaches a call only through a
     * {@link net.cumba.cdisc.core.model.CheckConditionExpression}: a Check that
     * {@code ExprLowering} lowers to a leaf is never re-inspected here, and both spellings lower to
     * the identical leaf (D-2). The arming step is therefore
     * {@code ExprLowering.functionOperatorLeaf} refusing the old shape for the pair — Plan A Phase
     * 2 step 2; until then this validator fires only on a Check the deserializer left native.
     * </p>
     */
    private static void validateInlineUniqueSetShape(net.cumba.cdisc.core.expr.ast.Expr.Call call)
    {
        if (!UNIQUE_SET_OPERATORS.contains(call.name()))
        {
            return;
        }
        String migration = "; write " + call.name()
                + "([TARGET, KEY, …]) — one list operand, order preserved (the first member has"
                + " no special meaning)";
        if (call.kwargs().containsKey("keys"))
        {
            throw new net.cumba.cdisc.core.expr.RuleDefinitionException(
                    "`" + call.name() + "` no longer takes keys=" + migration);
        }
        if (call.args().size() != 1)
        {
            throw new net.cumba.cdisc.core.expr.RuleDefinitionException(
                    "`" + call.name() + "` takes exactly one list operand" + migration);
        }
        if (!(call.args().get(0) instanceof net.cumba.cdisc.core.expr.ast.Expr.Lit lit)
                || lit.kind() != net.cumba.cdisc.core.expr.ast.Expr.LitKind.LIST)
        {
            throw new net.cumba.cdisc.core.expr.RuleDefinitionException(
                    "`" + call.name() + "`'s operand must be a list literal" + migration);
        }
        if (((List<?>) lit.value()).isEmpty())
        {
            throw new net.cumba.cdisc.core.expr.RuleDefinitionException(
                    "`" + call.name() + "([])` has no members; list at least one column");
        }
    }

    /**
     * The operators that consume a grouping-key {@code keep_missings=} on the <b>Check</b> surface.
     * Mirrors {@code CheckToExpr}'s declared-surface allowlist; kept in this shape so the inline
     * and declared surfaces reject the same set.
     */
    private static final java.util.Set<String> CHECK_KEEP_MISSINGS_OPERATORS = java.util.Set.of(
            "has_multiple_values_for", "is_inconsistent_across_dataset", "is_not_unique_set",
            "is_unique_set", "present_on_multiple_rows_within",
            "not_present_on_multiple_rows_within", "does_not_have_next_corresponding_record",
            "has_next_corresponding_record", "empty_within_except_last_row",
            "target_is_not_sorted_by", "is_sorted_by");

    /**
     * Rejects an unusable inline {@code keep_missings=} on a Check-operator call, on the
     * {@code loadError} channel.
     *
     * <p>
     * ⚠⚠ <b>The inline surface is the one that matters here.</b> Shipped rules inline their
     * conditions, and {@code ExprCompiler}'s own {@code unsupported(...)} throw would degrade the
     * rule rather than error it — the silent outcome this parameter's design rules out. An earlier
     * boolean parameter's validation was bypassed on exactly this path until its own review caught
     * it.
     * </p>
     */
    private static void validateInlineCheckKeepMissings(
            net.cumba.cdisc.core.expr.ast.Expr.Call call)
    {
        net.cumba.cdisc.core.expr.ast.Expr v = call.kwargs().get("keep_missings");
        if (!(v instanceof net.cumba.cdisc.core.expr.ast.Expr.Lit lit)
                || lit.kind() != net.cumba.cdisc.core.expr.ast.Expr.LitKind.BOOL)
        {
            throw new net.cumba.cdisc.core.expr.RuleDefinitionException(
                    "`keep_missings` must be a boolean literal (true/false) on `" + call.name()
                            + "`");
        }
        if (!CHECK_KEEP_MISSINGS_OPERATORS.contains(call.name()))
        {
            throw new net.cumba.cdisc.core.expr.RuleDefinitionException(
                    "`keep_missings` is not supported by `" + call.name()
                            + "`; only the group-aware operators consume it");
        }
    }


    /**
     * EC-87 — rejects an unusable inline {@code relation=} on a Check-operator call, on the
     * {@code loadError} channel: a non-string literal, an operator other than the next-record pair
     * ({@link net.cumba.cdisc.core.model.NextRecordRelation#OPERATORS} — both Q1 twins, the
     * {@code keep_missings} lesson), or a spelling outside
     * {@link net.cumba.cdisc.core.model.NextRecordRelation#SPELLINGS}.
     *
     * <p>
     * ⚠⚠ The inline surface is the one that matters (see {@link #validateInlineCheckKeepMissings}):
     * {@code ExprCompiler}'s own throw on an unknown spelling would only <em>degrade</em> the rule,
     * and a typo'd {@code relation="=<"} that silently fell back to identity would keep the rule
     * quietly over-reporting with no fixture to catch it.
     * </p>
     */
    private static void validateInlineCheckRelation(net.cumba.cdisc.core.expr.ast.Expr.Call call)
    {
        net.cumba.cdisc.core.expr.ast.Expr v = call.kwargs().get("relation");
        if (!(v instanceof net.cumba.cdisc.core.expr.ast.Expr.Lit lit)
                || lit.kind() != net.cumba.cdisc.core.expr.ast.Expr.LitKind.STRING)
        {
            throw new net.cumba.cdisc.core.expr.RuleDefinitionException(
                    "`relation` must be a string literal on `" + call.name() + "`; expected one of "
                            + net.cumba.cdisc.core.model.NextRecordRelation.SPELLINGS);
        }
        if (!net.cumba.cdisc.core.model.NextRecordRelation.OPERATORS.contains(call.name()))
        {
            throw new net.cumba.cdisc.core.expr.RuleDefinitionException(
                    "`relation` is not supported by `" + call.name()
                            + "`; only has_next_corresponding_record consumes it");
        }
        if (net.cumba.cdisc.core.model.NextRecordRelation
                .fromSpelling((String) lit.value()) == null)
        {
            throw new net.cumba.cdisc.core.expr.RuleDefinitionException("unknown `relation` `"
                    + lit.value() + "` on `" + call.name() + "`; expected one of "
                    + net.cumba.cdisc.core.model.NextRecordRelation.SPELLINGS);
        }
    }


    /** The direct sub-expressions of {@code expr}; empty for a bare operand. */
    private static List<net.cumba.cdisc.core.expr.ast.Expr> childrenOf(
            net.cumba.cdisc.core.expr.ast.Expr expr)
    {
        return switch (expr)
        {
        case net.cumba.cdisc.core.expr.ast.Expr.And and -> and.parts();
        case net.cumba.cdisc.core.expr.ast.Expr.Or or -> or.parts();
        case net.cumba.cdisc.core.expr.ast.Expr.Not not -> List.of(not.inner());
        case net.cumba.cdisc.core.expr.ast.Expr.Binary binary -> List.of(binary.left(),
                binary.right());
        case net.cumba.cdisc.core.expr.ast.Expr.Call call ->
        {
            List<net.cumba.cdisc.core.expr.ast.Expr> all = new ArrayList<>(call.args());
            all.addAll(call.kwargs().values());
            yield all;
        }
        case net.cumba.cdisc.core.expr.ast.Expr.Lit lit ->
        {
            if (lit.kind() != net.cumba.cdisc.core.expr.ast.Expr.LitKind.LIST)
            {
                yield List.of();
            }
            @SuppressWarnings("unchecked")
            List<net.cumba.cdisc.core.expr.ast.Expr> items = (List<net.cumba.cdisc.core.expr.ast.Expr>) lit
                    .value();
            yield items;
        }
        case net.cumba.cdisc.core.expr.ast.Expr.Ref _ -> List.of();
        };
    }

    /**
     * Positive-polarity operators: with the compared operand resolving to no value the leaf does
     * <b>not</b> report, so an {@code indeterminate} extreme silences it.
     */
    private static final java.util.Set<String> SILENT_ON_NO_VALUE = java.util.Set.of("equal_to",
            "equal_to_case_insensitive", "greater_than", "greater_than_or_equal_to", "less_than",
            "less_than_or_equal_to", "date_equal_to", "date_greater_than",
            "date_greater_than_or_equal_to", "date_less_than", "date_less_than_or_equal_to",
            "date_part_equal_to", "time_part_equal_to");

    /**
     * Negative-polarity operators: with the compared operand resolving to no value the leaf
     * <b>does</b> report — which is the disposition's whole point, and is therefore silencing only
     * when an enclosing {@code not:} inverts it.
     */
    private static final java.util.Set<String> REPORTS_ON_NO_VALUE = java.util.Set.of(
            "not_equal_to", "not_equal_to_case_insensitive", "date_not_equal_to",
            "date_part_not_equal_to", "time_part_not_equal_to");

    /**
     * Walks the Check tree collecting {@code declared id ⇒ silencing operator} pairs.
     * {@code negated} carries the parity of the enclosing {@code not:} nesting.
     */
    private static void collectSilencingConsumers(@Nullable CheckCondition condition,
            List<String> declared, boolean negated, Map<String, String> out)
    {
        // Handled before the switch rather than as a `case null` arm: an empty arm in a pattern
        // switch reads to SpotBugs as SF_SWITCH_FALLTHROUGH.
        if (condition == null)
        {
            return;
        }
        switch (condition)
        {
        case CheckConditionAll all -> all.getConditions()
                .forEach(c -> collectSilencingConsumers(c, declared, negated, out));
        case CheckConditionAny any -> any.getConditions()
                .forEach(c -> collectSilencingConsumers(c, declared, negated, out));
        case CheckConditionNot not -> collectSilencingConsumers(not.getCondition(), declared,
                !negated, out);
        case CheckConditionLeaf leaf -> collectSilencingLeaf(leaf, declared, negated, out);
        case CheckConditionConstant _ ->
        {
            // constants carry no operand, so there is nothing to judge
        }
        case net.cumba.cdisc.core.model.CheckConditionExpression expr -> collectSilencingExpr(
                expr.expr(), declared, negated, out);
        }
    }


    private static void collectSilencingLeaf(CheckConditionLeaf leaf, List<String> declared,
            boolean negated, Map<String, String> out)
    {
        String operator = leaf.getOperator();
        if (operator == null || !isSilencing(operator, negated))
        {
            return;
        }
        for (String id : declared)
        {
            boolean referenced = id.equals(leaf.getName())
                    || (leaf.getNames() != null && leaf.getNames().contains(id))
                    || (leaf.getValue() != null && leaf.getValue().isTextual()
                            && id.equals(leaf.getValue().asText()));
            if (referenced)
            {
                out.putIfAbsent(id, operator);
            }
        }
    }


    /**
     * The native-only twin of {@link #collectSilencingLeaf}, for a Check that could not be lowered
     * to operator-leaf form. Only the infix comparisons carry a polarity; anything else is left
     * unjudged, matching the leaf walker's treatment of unenumerated operators.
     */
    private static void collectSilencingExpr(net.cumba.cdisc.core.expr.ast.Expr expr,
            List<String> declared, boolean negated, Map<String, String> out)
    {
        switch (expr)
        {
        case net.cumba.cdisc.core.expr.ast.Expr.And and -> and.parts()
                .forEach(p -> collectSilencingExpr(p, declared, negated, out));
        case net.cumba.cdisc.core.expr.ast.Expr.Or or -> or.parts()
                .forEach(p -> collectSilencingExpr(p, declared, negated, out));
        case net.cumba.cdisc.core.expr.ast.Expr.Not not -> collectSilencingExpr(not.inner(),
                declared, !negated, out);
        case net.cumba.cdisc.core.expr.ast.Expr.Binary binary ->
        {
            String operator = switch (binary.op())
            {
            case EQ -> "equal_to";
            case LT -> "less_than";
            case GT -> "greater_than";
            case LE -> "less_than_or_equal_to";
            case GE -> "greater_than_or_equal_to";
            case NEQ -> "not_equal_to";
            default -> null;
            };
            if (operator != null && isSilencing(operator, negated))
            {
                for (String id : declared)
                {
                    if (referencesId(binary.left(), id) || referencesId(binary.right(), id))
                    {
                        out.putIfAbsent(id, operator);
                    }
                }
                // An INLINE declaring call is its own consumer: it has no `$`-id, so it is matched
                // structurally rather than by name.
                String inline = inlineIndeterminate(binary.left());
                if (inline == null)
                {
                    inline = inlineIndeterminate(binary.right());
                }
                if (inline != null)
                {
                    out.putIfAbsent(inline, operator);
                }
            }
            // The operands themselves may hold further comparisons (an arithmetic sub-tree, a
            // call argument), so the walk continues rather than stopping at the Binary.
            childrenOf(binary).forEach(p -> collectSilencingExpr(p, declared, negated, out));
        }
        default -> childrenOf(expr).forEach(p -> collectSilencingExpr(p, declared, negated, out));
        }
    }


    /**
     * Whether {@code operand} reads the declared operation {@code id}.
     *
     * <p>
     * ⚠ <b>Recursive, and deliberately over-matching.</b> The comparison operand is not always a
     * bare {@code $}-ref: {@code ExprLowering}'s own operand readers strip the {@code date(…)} /
     * {@code num(…)} / {@code lowcase(…)} wrappers before naming the operand, so a rule written
     * {@code date($min_ds) == DSSTDTC} means exactly what {@code $min_ds == DSSTDTC} means. A
     * literal {@code instanceof Expr.Ref} test would judge the first and miss the second — the same
     * rule text getting two verdicts depending on whether an unrelated sibling conjunct happened to
     * block lowering. Descending into every sub-expression keeps the guard on its stated policy
     * that over-rejection is the safe direction.
     * </p>
     */
    private static boolean referencesId(net.cumba.cdisc.core.expr.ast.Expr operand, String id)
    {
        if (operand instanceof net.cumba.cdisc.core.expr.ast.Expr.Ref ref && id.equals(ref.name()))
        {
            return true;
        }
        for (net.cumba.cdisc.core.expr.ast.Expr child : childrenOf(operand))
        {
            if (referencesId(child, id))
            {
                return true;
            }
        }
        return false;
    }


    /**
     * The inline twin of {@link #referencesId}: returns a display name for an operation call
     * declaring {@code missing_values: indeterminate} anywhere inside {@code operand}, or
     * {@code null}. Such a call <em>is</em> the operation — there is no {@code $}-id to look up —
     * so the polarity gate has to recognise it structurally or the whole inline authoring surface
     * escapes the third rejection.
     */
    private static @Nullable String inlineIndeterminate(net.cumba.cdisc.core.expr.ast.Expr operand)
    {
        if (operand instanceof net.cumba.cdisc.core.expr.ast.Expr.Call call
                && call.kwargs()
                        .get("missing_values") instanceof net.cumba.cdisc.core.expr.ast.Expr.Lit lit
                && Operation.MISSING_VALUES_INDETERMINATE.equals(lit.value()))
        {
            return call.name() + "(…) inline";
        }
        for (net.cumba.cdisc.core.expr.ast.Expr child : childrenOf(operand))
        {
            String nested = inlineIndeterminate(child);
            if (nested != null)
            {
                return nested;
            }
        }
        return null;
    }


    private static boolean isSilencing(String operator, boolean negated)
    {
        return negated ? REPORTS_ON_NO_VALUE.contains(operator)
                : SILENT_ON_NO_VALUE.contains(operator);
    }


    /**
     * Package-level driver for {@link #injectInlineOperationGates(Rule)} — see that overload for
     * what the pass does. A no-op for a {@code null} package or one with no rules.
     */
    private static void injectInlineOperationGates(RulePackage pkg)
    {
        if (pkg == null || pkg.getRules() == null)
        {
            return;
        }
        for (Rule rule : pkg.getRules().values())
        {
            injectInlineOperationGates(rule);
        }
    }


    /**
     * Restores the legacy SKIP contract for <b>hand-authored</b> native rules
     * ({@code PLAN-classifier-redesign}, Phase-0 filed hazard): a Check that inlines a
     * library/define/dictionary-dependent operation call without an availability gate broadcasts
     * {@code null} when the provider is absent — no row fires and the rule silently PASSes where
     * the declaration-keyed legacy path reports SKIPPED. This pass injects, per missing term, the
     * exact gate shape {@code OperationInliner} bakes into the shipped corpus:
     * {@code library_available()} plus {@code available(<op-call>)} for a library-dependent call,
     * {@code dictionary_available("<type>")} per distinct dictionary type, and
     * {@code available(<op-call>)} for a define-dependent call ({@code available} covers the
     * absent-provider case; there is no define-presence builtin). The inliner's emptiness exemption
     * is honoured: a call whose every use is a direct {@code empty()} / {@code is_missing()}
     * operand gets no {@code available()} term, or the gate would make the rule unreachable.
     *
     * <p>
     * Idempotent by term presence: gates already in the {@code Precondition} (the entire shipped
     * corpus — held to zero injections by a committed corpus test) are never duplicated. An
     * existing Precondition that cannot be raised to an expression leaves the rule untouched with a
     * {@link Rule#getLoadWarning() load warning}, mirroring the inliner's own bail-out. Injections
     * are recorded on {@link Rule#getInjectedPreconditionGates()} and logged at {@code INFO}.
     * </p>
     */
    static void injectInlineOperationGates(@Nullable Rule rule)
    {
        if (rule == null || rule.getLoadError() != null || rule.getCheck() == null)
        {
            return;
        }
        // ⚑ Plan C §3.3: the availability gate is a property of the RULE (the Precondition is
        // shared across levels), so the terms are collected from every declared level. An inlined
        // library call in a weaker level needs the provider just as much as one in the strictest.
        LinkedHashMap<String, net.cumba.cdisc.core.expr.ast.Expr> needed = new LinkedHashMap<>();
        for (CheckCondition condition : rule.checkConditions())
        {
            net.cumba.cdisc.core.expr.ast.Expr check = tryRaiseToExpr(condition);
            if (check == null)
            {
                return;
            }
            collectGateTerms(check, check, needed);
        }
        if (needed.isEmpty())
        {
            return;
        }
        net.cumba.cdisc.core.expr.ast.Expr existing = null;
        if (rule.getPrecondition() != null)
        {
            existing = tryRaiseToExpr(rule.getPrecondition());
            if (existing == null)
            {
                String warning = "[" + ruleId(rule) + "] Check inlines availability-dependent"
                        + " operation calls but the existing Precondition cannot be raised to an"
                        + " expression — the availability gate was NOT injected; without it the"
                        + " rule silently PASSes instead of SKIPPING when the provider is absent";
                rule.setLoadWarning(rule.getLoadWarning() == null ? warning
                        : rule.getLoadWarning() + "; " + warning);
                LOGGER.log(System.Logger.Level.WARNING, "{0}", warning);
                return;
            }
            for (net.cumba.cdisc.core.expr.ast.Expr term : flattenAnd(existing))
            {
                needed.remove(net.cumba.cdisc.core.expr.ExpressionPrinter.print(term));
            }
            if (needed.isEmpty())
            {
                return;
            }
        }
        List<net.cumba.cdisc.core.expr.ast.Expr> terms = new ArrayList<>(needed.values());
        if (existing != null)
        {
            terms.add(existing);
        }
        net.cumba.cdisc.core.expr.ast.Expr combined = terms.size() == 1 ? terms.get(0)
                : new net.cumba.cdisc.core.expr.ast.Expr.And(terms);
        rule.setPrecondition(new net.cumba.cdisc.core.model.CheckConditionExpression(combined,
                net.cumba.cdisc.core.expr.ExpressionPrinter.print(combined)));
        String injected = String.join(" and ", needed.keySet());
        rule.setInjectedPreconditionGates(injected);
        LOGGER.log(System.Logger.Level.INFO,
                "[{0}] injected availability gate into Precondition: {1} (an inlined"
                        + " library/define/dictionary operation without it silently PASSes"
                        + " instead of SKIPPING when the provider is absent)",
                ruleId(rule), injected);
    }


    /**
     * Collects the gate terms {@code node}'s inline operation calls demand, keyed by printed form
     * (insertion-ordered, deduplicated).
     */
    private static void collectGateTerms(net.cumba.cdisc.core.expr.ast.Expr node,
            net.cumba.cdisc.core.expr.ast.Expr check,
            Map<String, net.cumba.cdisc.core.expr.ast.Expr> needed)
    {
        switch (node)
        {
        case net.cumba.cdisc.core.expr.ast.Expr.And a -> a.parts()
                .forEach(part -> collectGateTerms(part, check, needed));
        case net.cumba.cdisc.core.expr.ast.Expr.Or o -> o.parts()
                .forEach(part -> collectGateTerms(part, check, needed));
        case net.cumba.cdisc.core.expr.ast.Expr.Not n -> collectGateTerms(n.inner(), check, needed);
        case net.cumba.cdisc.core.expr.ast.Expr.Binary b ->
        {
            collectGateTerms(b.left(), check, needed);
            collectGateTerms(b.right(), check, needed);
        }
        case net.cumba.cdisc.core.expr.ast.Expr.Call c ->
        {
            gateTermsForCall(c, check, needed);
            c.args().forEach(arg -> collectGateTerms(arg, check, needed));
            c.kwargs().values().forEach(v -> collectGateTerms(v, check, needed));
        }
        default ->
        {
            // Lit / Ref — nothing to gate ($-refs stay declared and go through the eager
            // declaration-keyed SKIP gates in RuleRunner).
        }
        }
    }


    private static void gateTermsForCall(net.cumba.cdisc.core.expr.ast.Expr.Call call,
            net.cumba.cdisc.core.expr.ast.Expr check,
            Map<String, net.cumba.cdisc.core.expr.ast.Expr> needed)
    {
        if (!net.cumba.cdisc.core.expr.eval.ExprCompiler.isInlineOperation(call))
        {
            return;
        }
        Operation op;
        try
        {
            op = net.cumba.cdisc.core.expr.convert.OperationExpressionParser.fromCall(call, null);
        }
        catch (RuntimeException _)
        {
            return; // not a well-formed operation call — the compiler will reject it on its own
        }
        net.cumba.cdisc.core.model.OperationType type = op.getOperationType();
        if (net.cumba.cdisc.core.exec.OperationExecutor.isDictionaryDependent(type))
        {
            if (op.getExternalDictionaryType() != null)
            {
                addGateTerm(needed,
                        new net.cumba.cdisc.core.expr.ast.Expr.Call("dictionary_available",
                                List.of(new net.cumba.cdisc.core.expr.ast.Expr.Lit(
                                        net.cumba.cdisc.core.expr.ast.Expr.LitKind.STRING,
                                        op.getExternalDictionaryType())),
                                Map.of()));
            }
            return;
        }
        boolean library = net.cumba.cdisc.core.exec.OperationExecutor.isLibraryDependent(type);
        boolean define = net.cumba.cdisc.core.exec.OperationExecutor.isDefineDependent(type);
        if (!library && !define)
        {
            return;
        }
        if (library)
        {
            addGateTerm(needed, new net.cumba.cdisc.core.expr.ast.Expr.Call("library_available",
                    List.of(), Map.of()));
        }
        if (!testsOnlyEmptiness(check, call))
        {
            addGateTerm(needed, new net.cumba.cdisc.core.expr.ast.Expr.Call("available",
                    List.of(call), Map.of()));
        }
    }


    private static void addGateTerm(Map<String, net.cumba.cdisc.core.expr.ast.Expr> needed,
            net.cumba.cdisc.core.expr.ast.Expr term)
    {
        needed.putIfAbsent(net.cumba.cdisc.core.expr.ExpressionPrinter.print(term), term);
    }


    /** The AND-spine of an expression as a flat term list ({@code x} alone yields {@code [x]}). */
    private static List<net.cumba.cdisc.core.expr.ast.Expr> flattenAnd(
            net.cumba.cdisc.core.expr.ast.Expr expr)
    {
        if (expr instanceof net.cumba.cdisc.core.expr.ast.Expr.And and)
        {
            List<net.cumba.cdisc.core.expr.ast.Expr> out = new ArrayList<>();
            and.parts().forEach(p -> out.addAll(flattenAnd(p)));
            return out;
        }
        return List.of(expr);
    }


    /**
     * Whether every occurrence of {@code opCall} in {@code check} is the direct operand of an
     * {@code empty()} / {@code is_missing()} call — the rule tests <em>only</em> the operation
     * result's emptiness, so an {@code available(<op>)} gate would make it unreachable. Ported from
     * {@code OperationInliner} so loader-injected gates match the corpus-baked ones exactly.
     */
    private static boolean testsOnlyEmptiness(net.cumba.cdisc.core.expr.ast.Expr check,
            net.cumba.cdisc.core.expr.ast.Expr opCall)
    {
        int[] counts = new int[2];
        walkEmptinessUse(check, opCall, false, counts);
        return counts[0] > 0 && counts[0] == counts[1];
    }


    private static void walkEmptinessUse(net.cumba.cdisc.core.expr.ast.Expr node,
            net.cumba.cdisc.core.expr.ast.Expr target, boolean parentIsEmptiness, int[] counts)
    {
        if (node.equals(target))
        {
            counts[0]++;
            if (parentIsEmptiness)
            {
                counts[1]++;
            }
            return;
        }
        boolean emptiness = node instanceof net.cumba.cdisc.core.expr.ast.Expr.Call c
                && ("empty".equals(c.name()) || "is_missing".equals(c.name()));
        switch (node)
        {
        case net.cumba.cdisc.core.expr.ast.Expr.And a -> a.parts()
                .forEach(p -> walkEmptinessUse(p, target, false, counts));
        case net.cumba.cdisc.core.expr.ast.Expr.Or o -> o.parts()
                .forEach(p -> walkEmptinessUse(p, target, false, counts));
        case net.cumba.cdisc.core.expr.ast.Expr.Not n -> walkEmptinessUse(n.inner(), target, false,
                counts);
        case net.cumba.cdisc.core.expr.ast.Expr.Binary b ->
        {
            walkEmptinessUse(b.left(), target, false, counts);
            walkEmptinessUse(b.right(), target, false, counts);
        }
        case net.cumba.cdisc.core.expr.ast.Expr.Call c -> c.args()
                .forEach(arg -> walkEmptinessUse(arg, target, emptiness, counts));
        default ->
        {
            // Lit / Ref — leaf nodes with no nested operands to walk.
        }
        }
    }


    /**
     * Reconstructs the native-evaluator expression for each native-eligible rule whose Check raises
     * and compiles on the native backend, storing it on {@link Rule#getCheckExpr()} (mirroring the
     * {@code loadError} runtime-only precedent — never serialised). The dispatch sites use it
     * whenever the {@code nativeEval} flag is on; the {@code --no-native-eval} kill-switch keeps
     * the legacy engine. A rule that fails to raise or to compile keeps {@code checkExpr == null}
     * and runs entirely on the legacy path — recorded as a LEGACY execution by
     * {@code NativeExecutionRecorder} and gated corpus-wide by
     * {@code NativeCorpusFullCoverageTest}. Sees the same materialised Check tree.
     *
     * <p>
     * Per-rule work is {@link #installNativeExpr(Rule)}; this driver only walks the package.
     * </p>
     */
    private static void retainNativeExpr(RulePackage pkg)
    {
        if (pkg == null || pkg.getRules() == null)
        {
            return;
        }
        for (Rule rule : pkg.getRules().values())
        {
            installNativeExpr(rule);
        }
    }


    /**
     * The single native-retention decision (P5: shared by THIS loader and the per-dataset
     * {@code RuleGenerator} seam, so generated/expanded concrete rules carry the same native
     * {@code checkExpr} a loader-loaded rule would): raise the rule's Check to {@code Expr},
     * canonicalize its metadata operands (Epic B4 — uniformly, for every rule, since phase 4 of
     * {@code PLAN-leaf-scope-domain-inference.md}), install the expression when the native backend
     * supports it, flag fold-equivalent broadcast verdicts (P3a) and cache the inferred
     * {@linkplain Rule#getEvaluationDomain() evaluation domain} the runner dispatches on. No-op for
     * a rule that already carries a {@code checkExpr}, has a {@code loadError} or has no Check.
     */
    public static void installNativeExpr(@Nullable Rule rule)
    {
        if (rule == null || rule.getLoadError() != null || rule.getCheck() == null
                || rule.getCheckExpr() != null)
        {
            return;
        }
        // Plan C §3.3 step 2 — ONE Expr per declared level. For the 3 804 single-level rules
        // `levels` holds exactly one entry whose condition IS rule.getCheck(), so every step below
        // runs on the same argument, in the same order, as it did before levels existed.
        SequencedMap<Severity, LevelCheck> declared = rule.effectiveCheckLevels();
        SequencedMap<Severity, net.cumba.cdisc.core.expr.ast.Expr> levels = new LinkedHashMap<>();
        for (Map.Entry<Severity, LevelCheck> level : declared.entrySet())
        {
            net.cumba.cdisc.core.expr.ast.Expr raised = tryRaiseToExpr(
                    level.getValue().condition());
            if (raised == null)
            {
                // No expression surface at some level ⇒ the rule has no native form at all. All or
                // nothing: installing the levels that DID raise would silently drop a level's
                // verdict, and a rule that reports fewer levels than it declares is worse than one
                // that reports the "no native expression form" ERROR.
                return;
            }
            levels.put(level.getKey(), raised);
        }
        // Element B: lower a `variable_exists` operation consumed as `$X == true/false` into the
        // Check as the var_exists(<col>) function — via the shared mapping the offline converter
        // (OperationInliner) also drives — so the parity Java lane, which compiles the org-form
        // fixture natively through this seam, evaluates `var_exists()` exactly as the shipped
        // rules/ do. A no-op for production rules/ (already inlined ⇒ no variable_exists
        // operation).
        inlineVariableExistsOps(rule, levels);
        // T9: lower a `split_by` operation into the per-row split_by(<col>, "<delim>") value
        // function — via the shared SplitByInliner mapping the offline OperationInliner also drives
        // — so the parity Java native fixture-compile path evaluates the split membership rule
        // exactly as the shipped rules/ do. A no-op for production rules/ (already inlined).
        inlineSplitByOps(rule, levels);
        // ⚠ The two seams above stayed no-ops for production rules/ across
        // plans/done/PLAN-operations-no-inline.md (D31, 2026-08-08), which stopped OperationInliner
        // inlining OPERATIONS but deliberately kept it lowering these two RETIRED operators —
        // neither has an OperationType, so a rule still declaring one fails to load once the
        // corpus renders it in Form B ("unknown operation function"). Measured with the corpus
        // lowerings gated off: 21 rules gained exactly that loadError.
        //
        // ⚑ variable_exists is no longer retired: OperationType.VARIABLE_EXISTS exists again, as
        // the REPORTING carriage of the var_exists(X) function (not a second verdict surface). The
        // lowering therefore stays — the Check keeps saying var_exists(X), so no verdict moves —
        // but it now RETAINS an operation whose $-id the rule declares in Outcome.Output_Variables
        // (VariableExistsInliner.reported), so that output variable has a value again. split_by is
        // still genuinely retired: see SplitByInliner for why a per-row token list has no
        // operation shape today.
        // ⚑ The Plan J3/J4 absent-column guard injection that stood here is DELETED
        // (2026-08-26, owner-ruled). Every guard the corpus needs is now AUTHORED — as
        // Requirements.Variables.All where absence means there is nothing to check, or as a
        // visible branch-scoped var_exists guard in the Check where it does not. Injecting
        // here as a "safety net" was not safe: guards were flattened into the enclosing
        // conjunction, so on a FLAT conjunction one absent column silenced the whole rule
        // (the branch-scoping that makes `A or (var_exists(X) and B)` harmless has no
        // equivalent inside an `And`). Foreign rules (rule editor, CDISC-Library,
        // TokenExpander) are deliberately out of scope: they author their own guards.
        // Epic B4, uniform since leaf-scope phase 4: canonicalize bare metadata-operand
        // references (variable_label, library_variable_*, define_variable_*, dataset_*, …) that
        // CheckToExpr left as plain refs in non-comparison positions (inside len()/regex/
        // non_empty/membership) into their var_*/ds_* accessor form, so they are recognised by
        // MetadataExprScan.containsMetadataFunction and by DomainScan. The per-row variable_value
        // operand is preserved verbatim (it reverses to null); the variable_name anchor is
        // preserved and handled as a per-variable cursor (P4b). Measured 2026-08-22 on the
        // shipped corpus: no rule outside the former metadata families carries a bare builtin
        // operand, so the R-P2 type split this replaces was a no-op there.
        for (Map.Entry<Severity, net.cumba.cdisc.core.expr.ast.Expr> level : levels.entrySet())
        {
            level.setValue(net.cumba.cdisc.core.expr.MetadataOperandMapping
                    .canonicalizeMetadataOperands(level.getValue()));
        }
        try
        {
            installCompiledLevels(rule, levels);
        }
        catch (net.cumba.cdisc.core.expr.RuleDefinitionException ex)
        {
            // The expression is definitionally invalid (e.g. var_role at the DATA level): file
            // it as a rule load error so the rule reports ERROR and never evaluates / cohorts.
            // (isSupported only swallows ExpressionException; a RuleDefinitionException — the
            // "rule is wrong" signal — propagates here.)
            rule.setLoadError(ex.getMessage());
        }
        raisePrecondition(rule);
    }


    /**
     * Installs the compiled levels &#8212; the tail of {@link #installNativeExpr}, after every
     * level has been raised, inlined, guarded and canonicalised.
     *
     * <p>
     * <b>All or nothing.</b> If any level's expression has no native form, nothing is installed and
     * the rule reports the "no native expression form" ERROR at runtime; installing the supported
     * levels only would silently drop a declared level's verdict, which is a worse failure than a
     * loud one. For a single-level rule this is exactly the pre-Plan-C behaviour, one
     * {@code isSupported} call on one expression.
     * </p>
     *
     * <p>
     * {@link Rule#getCheckExpr()}, {@link Rule#isBroadcastCheckExpr()} keep meaning <em>the
     * strictest level</em>, so every reader on the single-level path is unchanged;
     * {@link Rule#getEvaluationDomain()} becomes the <b>join</b> of the levels' domains (&#167;3.3
     * step 2) so the finding unit does not change shape between levels of one rule. The per-level
     * maps are installed only when there is more than one level, so a single-level rule carries
     * exactly the fields it carried before.
     * </p>
     */
    private static void installCompiledLevels(Rule rule,
            SequencedMap<Severity, net.cumba.cdisc.core.expr.ast.Expr> levels)
    {
        for (net.cumba.cdisc.core.expr.ast.Expr level : levels.values())
        {
            if (!net.cumba.cdisc.core.expr.eval.NativeExprEvaluator.isSupported(level))
            {
                return;
            }
        }
        java.util.Set<Severity> broadcast = new java.util.LinkedHashSet<>();
        net.cumba.cdisc.core.expr.eval.Domain join = null;
        for (Map.Entry<Severity, net.cumba.cdisc.core.expr.ast.Expr> level : levels.entrySet())
        {
            // P3a: flag fold-equivalent broadcast verdicts so RuleRunner routes them to
            // the native dataset-level broadcast evaluation (one violation at row 0 —
            // the legacy partialEvaluateDataset fold projection).
            if (isBroadcastVerdictExpr(level.getValue()))
            {
                broadcast.add(level.getKey());
            }
            // §3.2: the cached domain the runner dispatches on (and the report projects
            // FindingScope from). A memoised result of the inference, never an input to it.
            net.cumba.cdisc.core.expr.eval.Domain domain = net.cumba.cdisc.core.expr.eval.DomainScan
                    .infer(level.getValue(),
                            net.cumba.cdisc.core.expr.eval.OperationKinds.forRule(rule));
            join = join == null ? domain : join.join(domain);
        }
        Map.Entry<Severity, net.cumba.cdisc.core.expr.ast.Expr> strictest = levels.firstEntry();
        rule.setCheckExpr(strictest.getValue());
        rule.setBroadcastCheckExpr(broadcast.contains(strictest.getKey()));
        rule.setEvaluationDomain(join);
        // Installed for every rule that DECLARED a level map — a one-entry map included, or its
        // single level would never build a levelPlan and that level's own Message would silently
        // fall back to Outcome.Message (and the violation would carry no claiming level). The
        // test must NOT be `levels.size() > 1`: for a plain `Check:` the map here is the
        // synthesised single level, and installing it would route all ~3 804 shipped rules
        // through executeLevels. Keep RuleCohortGrouper / AbsentDatasetSkip on the same
        // declared-map test.
        SequencedMap<Severity, LevelCheck> declaredLevels = rule.getCheckLevels();
        if (declaredLevels != null && !declaredLevels.isEmpty())
        {
            rule.setCheckLevelExprs(new LinkedHashMap<>(levels));
            rule.setBroadcastCheckLevels(broadcast);
        }
        checkVariableUniverse(rule, java.util.Objects.requireNonNull(join, "at least one level"),
                strictest.getValue());
    }


    /**
     * Writes the (possibly rewritten) level expressions back onto the rule as {@code Check}
     * conditions, in expression form.
     *
     * <p>
     * The single-level branch is the {@code rule.setCheck(new CheckConditionExpression(…))} the two
     * operation-inlining seams have always done; the level branch does the same for every level,
     * preserving each level's own {@code Message}.
     * </p>
     */
    private static void writeBackLevelChecks(Rule rule,
            SequencedMap<Severity, net.cumba.cdisc.core.expr.ast.Expr> levels)
    {
        SequencedMap<Severity, LevelCheck> declared = rule.getCheckLevels();
        if (declared == null)
        {
            net.cumba.cdisc.core.expr.ast.Expr only = levels.firstEntry().getValue();
            rule.setCheck(new net.cumba.cdisc.core.model.CheckConditionExpression(only,
                    net.cumba.cdisc.core.expr.ExpressionPrinter.print(only)));
            return;
        }
        SequencedMap<Severity, LevelCheck> out = new LinkedHashMap<>();
        for (Map.Entry<Severity, LevelCheck> level : declared.entrySet())
        {
            net.cumba.cdisc.core.expr.ast.Expr rewritten = java.util.Objects
                    .requireNonNull(levels.get(level.getKey()), "every declared level was raised");
            out.put(level.getKey(),
                    new LevelCheck(
                            new net.cumba.cdisc.core.model.CheckConditionExpression(rewritten,
                                    net.cumba.cdisc.core.expr.ExpressionPrinter.print(rewritten)),
                            level.getValue().message()));
        }
        rule.setCheckLevels(out);
    }


    /**
     * P6b: raises a fold-equivalent {@code Precondition} so the skip-on-false decision evaluates
     * natively. Non-broadcast preconditions stay {@code null} — the legacy fold cannot decide them
     * either ("not fully resolvable ⇒ continue"), so both engines continue identically.
     */
    private static void raisePrecondition(Rule rule)
    {
        if (rule.getPrecondition() == null)
        {
            return;
        }
        net.cumba.cdisc.core.expr.ast.Expr pre = tryRaiseToExpr(rule.getPrecondition());
        try
        {
            if (pre != null && net.cumba.cdisc.core.expr.eval.NativeExprEvaluator.isSupported(pre)
                    && isBroadcastVerdictExpr(pre))
            {
                rule.setPreconditionExpr(pre);
            }
        }
        catch (net.cumba.cdisc.core.expr.RuleDefinitionException ex)
        {
            // Same disposition as the Check: a definitionally invalid Precondition (e.g. the
            // retired generic exists) is a rule load error, never a propagating throw.
            rule.setLoadError(rule.getLoadError() == null ? ex.getMessage()
                    : rule.getLoadError() + "; " + ex.getMessage());
        }
    }


    /**
     * Installs an <b>engine-internal</b> {@code Precondition} on an already-loaded rule, leaving it
     * in exactly the state {@code finishLoad} would have produced: the tree on
     * {@link Rule#getPrecondition()} and, when it is a fold-equivalent broadcast verdict, its
     * native form on {@link Rule#getPreconditionExpr()}.
     *
     * <p>
     * ⭐ <b>This is the supported way to put a term on the {@code Precondition} tier, and since gate
     * R8 it is the only one.</b> Owner ruling Q3 ({@code plans/PLAN-scope-requirements-split.md}
     * &#167;4.2) retired {@code Precondition} as an <em>authoring</em> surface while keeping the
     * tier itself untouched — the loader still writes it, {@code RuleRunner} still evaluates it at
     * phase 2e. A field that only the engine may write needs an engine API to write it; before this
     * method the only constructor was an authored document, which is precisely what R8 now rejects.
     * </p>
     *
     * <p>
     * ⚠ It deliberately does <b>not</b> re-run the load gates. The caller is the engine (or a test
     * standing in for it), and the value it installs is by definition not authored — running R8
     * over it would reject the one channel the ruling kept open.
     * </p>
     *
     * @param rule
     *            the loaded rule to install onto
     * @param precondition
     *            the engine-internal precondition tree, or {@code null} to clear it
     */
    public static void installEngineInternalPrecondition(Rule rule,
            @Nullable CheckCondition precondition)
    {
        rule.setPrecondition(precondition);
        rule.setPreconditionExpr(null);
        raisePrecondition(rule);
    }


    /**
     * Element B: lowers a {@code variable_exists} operation consumed in {@code check} as
     * {@code $X == true} / {@code $X == false} into the {@code var_exists(<col>)} /
     * {@code not var_exists(<col>)} check function, dropping the inlined operation from the rule —
     * <b>except</b> one whose {@code $}-id the rule declares in {@code Outcome.Output_Variables},
     * which is retained so its value can still be reported (see
     * {@link net.cumba.cdisc.core.expr.convert.VariableExistsInliner#reported}). Returns
     * {@code check} unchanged when the rule has no eligible {@code variable_exists} operation (the
     * production case: the shipped {@code rules/} are already inlined by {@code OperationInliner},
     * so this fires only for the org-form parity fixtures).
     *
     * <p>
     * Shares {@link net.cumba.cdisc.core.expr.convert.VariableExistsInliner} with the offline
     * converter so the native fixture-compile path and the shipped {@code rules/} cannot diverge.
     * Eligibility is computed over {@code check} only; the {@code variable_exists} operations in
     * the corpus are never referenced from a Precondition.
     * </p>
     */
    private static void inlineVariableExistsOps(Rule rule,
            SequencedMap<Severity, net.cumba.cdisc.core.expr.ast.Expr> levels)
    {
        Map<String, String> candidates = net.cumba.cdisc.core.expr.convert.VariableExistsInliner
                .candidateColumns(rule.getOperations());
        if (candidates.isEmpty())
        {
            return;
        }
        // Mirror OperationInliner: eligibility (and the rewrite) span the Check AND the
        // Precondition — an operation may only be inlined when *every* reference to its $-id (in
        // either tree) is a `$X == true/false` operand. If a Precondition is present but cannot be
        // raised, bail conservatively (leave the operations field-form) rather than drop an op that
        // an un-analysable Precondition might still reference.
        net.cumba.cdisc.core.expr.ast.Expr pre = null;
        if (rule.getPrecondition() != null)
        {
            pre = tryRaiseToExpr(rule.getPrecondition());
            if (pre == null)
            {
                return;
            }
        }
        // Plan C §3.3: eligibility spans EVERY declared level, not just the strictest. An
        // operation referenced from a weaker level is still referenced; dropping it because the
        // strictest level happens not to mention it would leave that level with a dangling $-ref.
        List<net.cumba.cdisc.core.expr.ast.Expr> scope = new ArrayList<>(levels.values());
        if (pre != null)
        {
            scope.add(pre);
        }
        Map<String, String> eligible = net.cumba.cdisc.core.expr.convert.VariableExistsInliner
                .eligible(scope, candidates);
        if (eligible.isEmpty())
        {
            return;
        }
        // Drop the inlined operations so RuleRunner does not re-execute the verdict the Check now
        // carries as var_exists(...) — EXCEPT the ones the rule reports. A reported id keeps its
        // operation (and its Output_Variables entry) so its $-result still materialises for the
        // finding: variable_exists has an OperationType again, and executing it costs one column
        // lookup. See VariableExistsInliner.reported — the verdict stays on the function either
        // way, so this cannot move a violation count.
        java.util.Set<String> reported = net.cumba.cdisc.core.expr.convert.VariableExistsInliner
                .reported(eligible.keySet(),
                        rule.getOutcome() == null ? null : rule.getOutcome().getOutputVariables());
        List<Operation> ops = rule.getOperations();
        if (ops != null)
        {
            List<Operation> kept = new ArrayList<>(ops.size());
            for (Operation op : ops)
            {
                if (op.getId() == null || !eligible.containsKey(op.getId())
                        || reported.contains(op.getId()))
                {
                    kept.add(op);
                }
            }
            rule.setOperations(kept.isEmpty() ? null : kept);
        }
        // A dropped operation no longer materialises a $-result, so its now-dangling
        // Output_Variable reference goes with it — mirroring
        // OperationInliner.removeInlinedOutputVariables so the parity Java lane emits the same
        // output as the shipped rules/. A RETAINED operation keeps both.
        if (rule.getOutcome() != null && rule.getOutcome().getOutputVariables() != null)
        {
            List<String> keptVars = new ArrayList<>();
            for (String ov : rule.getOutcome().getOutputVariables())
            {
                // Compare the bare id: an exclusion token `!$X` follows its `$X` (E-2) — dropped
                // with the operation it names, never left dangling.
                String id = net.cumba.cdisc.core.model.OutputVariableToken.name(ov);
                if (!eligible.containsKey(id) || reported.contains(id))
                {
                    keptVars.add(ov);
                }
            }
            rule.getOutcome().setOutputVariables(keptVars);
        }
        // Rewrite the Precondition the same way (an eligible $X may appear in it), so the
        // dropped operation leaves no dangling $-ref. Its expression form is raised again by
        // installNativeExpr's Precondition pass below.
        if (pre != null)
        {
            net.cumba.cdisc.core.expr.ast.Expr preRewritten = net.cumba.cdisc.core.expr.convert.VariableExistsInliner
                    .rewrite(pre, eligible);
            rule.setPrecondition(new net.cumba.cdisc.core.model.CheckConditionExpression(
                    preRewritten, net.cumba.cdisc.core.expr.ExpressionPrinter.print(preRewritten)));
        }
        for (Map.Entry<Severity, net.cumba.cdisc.core.expr.ast.Expr> level : levels.entrySet())
        {
            level.setValue(net.cumba.cdisc.core.expr.convert.VariableExistsInliner
                    .rewrite(level.getValue(), eligible));
        }
        // Replace the org-form Check tree with the inlined expression form. RuleRunner's Fix #15
        // output-variable inference reads rule.getCheck() (the tree) and would otherwise re-collect
        // the now-dropped $X operand from the `$X == true` leaf; an expression-form Check yields no
        // inferred columns, exactly as the shipped rules/ load. Native evaluation uses the
        // checkExpr installNativeExpr sets from the rewritten expressions.
        writeBackLevelChecks(rule, levels);
    }


    /**
     * T9: lowers a {@code split_by} operation into the per-row {@code split_by(<col>, "<delim>")}
     * value function within {@code check}, dropping the inlined operation from the rule. Returns
     * {@code check} unchanged when the rule has no referenced {@code split_by} operation (the
     * production case: the shipped {@code rules/} are already inlined by {@code OperationInliner},
     * so this fires only for the org-form parity fixtures).
     *
     * <p>
     * Shares {@link net.cumba.cdisc.core.expr.convert.SplitByInliner} with the offline converter so
     * the native fixture-compile path and the shipped {@code rules/} cannot diverge. Eligibility
     * spans the Check and the Precondition (an eligible {@code $}-id may appear in either); a
     * present-but-unraisable Precondition bails conservatively (leaves the operations field-form).
     * </p>
     */
    private static void inlineSplitByOps(Rule rule,
            SequencedMap<Severity, net.cumba.cdisc.core.expr.ast.Expr> levels)
    {
        Map<String, net.cumba.cdisc.core.expr.ast.Expr> candidates = net.cumba.cdisc.core.expr.convert.SplitByInliner
                .candidateCalls(rule.getOperations());
        if (candidates.isEmpty())
        {
            return;
        }
        net.cumba.cdisc.core.expr.ast.Expr pre = null;
        if (rule.getPrecondition() != null)
        {
            pre = tryRaiseToExpr(rule.getPrecondition());
            if (pre == null)
            {
                return;
            }
        }
        // Plan C §3.3: the reference scope is every declared level — see inlineVariableExistsOps.
        List<net.cumba.cdisc.core.expr.ast.Expr> scope = new ArrayList<>(levels.values());
        if (pre != null)
        {
            scope.add(pre);
        }
        Map<String, net.cumba.cdisc.core.expr.ast.Expr> eligible = net.cumba.cdisc.core.expr.convert.SplitByInliner
                .referenced(scope, candidates);
        if (eligible.isEmpty())
        {
            return;
        }
        // Drop the inlined operations so RuleRunner does not try to execute the split_by operator
        // (which has no OperationType and would resolve to null).
        List<Operation> ops = rule.getOperations();
        if (ops != null)
        {
            List<Operation> kept = new ArrayList<>(ops.size());
            for (Operation op : ops)
            {
                if (op.getId() == null || !eligible.containsKey(op.getId()))
                {
                    kept.add(op);
                }
            }
            rule.setOperations(kept.isEmpty() ? null : kept);
        }
        if (rule.getOutcome() != null && rule.getOutcome().getOutputVariables() != null)
        {
            List<String> keptVars = new ArrayList<>();
            for (String ov : rule.getOutcome().getOutputVariables())
            {
                // Bare id, so `!$X` follows `$X` (E-2) — see inlineVariableExistsOps.
                if (!eligible.containsKey(net.cumba.cdisc.core.model.OutputVariableToken.name(ov)))
                {
                    keptVars.add(ov);
                }
            }
            rule.getOutcome().setOutputVariables(keptVars);
        }
        if (pre != null)
        {
            net.cumba.cdisc.core.expr.ast.Expr preRewritten = net.cumba.cdisc.core.expr.convert.SplitByInliner
                    .rewrite(pre, eligible);
            rule.setPrecondition(new net.cumba.cdisc.core.model.CheckConditionExpression(
                    preRewritten, net.cumba.cdisc.core.expr.ExpressionPrinter.print(preRewritten)));
        }
        for (Map.Entry<Severity, net.cumba.cdisc.core.expr.ast.Expr> level : levels.entrySet())
        {
            level.setValue(net.cumba.cdisc.core.expr.convert.SplitByInliner
                    .rewrite(level.getValue(), eligible));
        }
        writeBackLevelChecks(rule, levels);
    }

    /**
     * The define-item-only attributes ({@code PLAN-leaf-scope-domain-inference.md} §3.7): reads
     * that only a Define-XML ItemDef can answer. Legal under every universe; under
     * {@link net.cumba.cdisc.core.model.VariableUniverse#DATA} they are lint-surfaced (INFO), never
     * blocked, because a data column with no ItemDef simply reads as absent.
     */
    private static final java.util.Set<String> DEFINE_ITEM_ONLY_ACCESSORS = java.util.Set.of(
            "var_ccode", "var_codelist_extended_values", "var_external_dictionary",
            "var_external_dictionary_version", "define_variable_decode_matches");

    /**
     * §3.7 validation of {@code Variable_Universe} against the inferred domain: {@code Define} on a
     * rule whose domain has no VAR cursor is a meaningless configuration and fails loud; a
     * define-item-only attribute read under the {@code Data} universe is legal and lint-surfaced.
     */
    private static void checkVariableUniverse(Rule rule,
            net.cumba.cdisc.core.expr.eval.Domain domain, net.cumba.cdisc.core.expr.ast.Expr expr)
    {
        net.cumba.cdisc.core.model.VariableUniverse universe = rule.getVariableUniverse();
        if (universe == net.cumba.cdisc.core.model.VariableUniverse.DEFINE && !domain.varCursor())
        {
            String error = "[" + ruleId(rule) + "] Variable_Universe: Define on a Check whose"
                    + " evaluation domain " + domain.label() + " has no variable cursor — the"
                    + " universe configures the VAR cursor, so it is meaningless here; drop the"
                    + " field or read the cursor (varname(), a var_* accessor)";
            rule.setLoadError(
                    rule.getLoadError() == null ? error : rule.getLoadError() + "; " + error);
            return;
        }
        if (universe == net.cumba.cdisc.core.model.VariableUniverse.DEFINE && domain.rowCursor())
        {
            // The per-(variable, row) path iterates the data columns only (every column has
            // cells; an ItemDef absent from the data has none). Until a Define universe is
            // defined for that path, accepting the field there would silently ignore it.
            String error = "[" + ruleId(rule) + "] Variable_Universe: Define on a Check whose"
                    + " evaluation domain " + domain.label() + " also carries the row cursor —"
                    + " the Define-XML ItemDefs have no rows to iterate, so the universe is not"
                    + " supported on a per-(variable, row) Check; drop the field or the cell read";
            rule.setLoadError(
                    rule.getLoadError() == null ? error : rule.getLoadError() + "; " + error);
            return;
        }
        if (universe != net.cumba.cdisc.core.model.VariableUniverse.DEFINE && domain.varCursor()
                && LOGGER.isLoggable(System.Logger.Level.INFO))
        {
            String accessor = defineItemOnlyAccessor(expr);
            if (accessor != null)
            {
                LOGGER.log(System.Logger.Level.INFO,
                        "[{0}] reads the define-item-only attribute {1} under the Data variable"
                                + " universe: a data column with no Define-XML ItemDef reads it as"
                                + " absent. Declare Variable_Universe: Define to iterate the"
                                + " ItemDefs instead (PLAN-leaf-scope-domain-inference.md §3.7)",
                        ruleId(rule), accessor);
            }
        }
    }


    private static @Nullable String defineItemOnlyAccessor(net.cumba.cdisc.core.expr.ast.Expr e)
    {
        return switch (e)
        {
        case net.cumba.cdisc.core.expr.ast.Expr.Call c ->
        {
            if (DEFINE_ITEM_ONLY_ACCESSORS.contains(c.name()) && readsDefineLevel(c))
            {
                yield c.name();
            }
            for (net.cumba.cdisc.core.expr.ast.Expr a : c.args())
            {
                String hit = defineItemOnlyAccessor(a);
                if (hit != null)
                {
                    yield hit;
                }
            }
            for (net.cumba.cdisc.core.expr.ast.Expr a : c.kwargs().values())
            {
                String hit = defineItemOnlyAccessor(a);
                if (hit != null)
                {
                    yield hit;
                }
            }
            yield null;
        }
        case net.cumba.cdisc.core.expr.ast.Expr.And a -> firstDefineItemOnlyAccessor(a.parts());
        case net.cumba.cdisc.core.expr.ast.Expr.Or o -> firstDefineItemOnlyAccessor(o.parts());
        case net.cumba.cdisc.core.expr.ast.Expr.Not n -> defineItemOnlyAccessor(n.inner());
        case net.cumba.cdisc.core.expr.ast.Expr.Binary b ->
        {
            String hit = defineItemOnlyAccessor(b.left());
            yield hit != null ? hit : defineItemOnlyAccessor(b.right());
        }
        case net.cumba.cdisc.core.expr.ast.Expr.Ref _,net.cumba.cdisc.core.expr.ast.Expr.Lit _ -> null;
        };
    }


    /**
     * Whether an accessor call reads the DEFINE level: its last positional argument is the level
     * literal {@code "DEFINE"}, or it takes no level at all
     * ({@code define_variable_decode_matches}). {@code var_ccode} is shared with the LIBRARY level
     * and counts only at DEFINE.
     */
    private static boolean readsDefineLevel(net.cumba.cdisc.core.expr.ast.Expr.Call c)
    {
        if (c.args().isEmpty())
        {
            return true;
        }
        net.cumba.cdisc.core.expr.ast.Expr last = c.args().get(c.args().size() - 1);
        if (last instanceof net.cumba.cdisc.core.expr.ast.Expr.Lit lit
                && lit.kind() == net.cumba.cdisc.core.expr.ast.Expr.LitKind.STRING)
        {
            return "DEFINE".equalsIgnoreCase((String) lit.value());
        }
        return !"var_ccode".equals(c.name());
    }


    private static @Nullable String firstDefineItemOnlyAccessor(
            List<net.cumba.cdisc.core.expr.ast.Expr> parts)
    {
        for (net.cumba.cdisc.core.expr.ast.Expr p : parts)
        {
            String hit = defineItemOnlyAccessor(p);
            if (hit != null)
            {
                return hit;
            }
        }
        return null;
    }


    /**
     * Whether {@code expr} is a <b>fold-equivalent</b> dataset-broadcast verdict — one the legacy
     * {@code CheckConditionOptimizer.partialEvaluateDataset} folds to a constant (one dataset-level
     * violation at row 0), so it can be evaluated once via
     * {@code NativeExprEvaluator.evaluateBroadcast} with bit-for-bit parity. Accepted leaves:
     * <ul>
     * <li>{@code exists(NAME)} / {@code not_exists(NAME)} on a bare reference — rule-type-resolved
     * by the native {@code OperatorRegistry.exists}: dataset presence for a Domain-Presence rule,
     * column presence for the metadata families (the dominant VMC variable-presence shape), with
     * dotted and {@code --}-prefix forms handled in the compiled closure;</li>
     * <li>a {@code $}-variable ({@link net.cumba.cdisc.core.expr.OperandKind#OPERATION_REF})
     * compared to a literal (e.g. {@code $MIDS_EXISTS == true}, {@code $multiple_race >= 1}) —
     * resolved from the row-independent operation results;</li>
     * <li>literals and the {@code and}/{@code or}/{@code not} combinators of the above.</li>
     * </ul>
     * Any per-row data operand — a bare {@code COLUMN} reference in comparison position, or a
     * non-{@code exists} function call — declines.
     */
    private static boolean isBroadcastVerdictExpr(net.cumba.cdisc.core.expr.ast.Expr expr)
    {
        return switch (expr)
        {
        case net.cumba.cdisc.core.expr.ast.Expr.And a -> a.parts().stream()
                .allMatch(RulePackageLoader::isBroadcastVerdictExpr);
        case net.cumba.cdisc.core.expr.ast.Expr.Or o -> o.parts().stream()
                .allMatch(RulePackageLoader::isBroadcastVerdictExpr);
        case net.cumba.cdisc.core.expr.ast.Expr.Not n -> isBroadcastVerdictExpr(n.inner());
        case net.cumba.cdisc.core.expr.ast.Expr.Binary b ->
        {
            boolean factPair = isDatasetPresenceOperand(b.left())
                    && isDatasetPresenceOperand(b.right());
            // A broadcast verdict compared to a BOOL literal (e.g. `var_exists(X) == true`) is
            // itself a broadcast verdict — `== true` / `!= false` is the identity, `== false` /
            // `!= true` the negation. Runtime mirror: BroadcastFold.isDatasetConstantLeaf.
            boolean eq = b.op() == net.cumba.cdisc.core.expr.ast.Expr.BinOp.EQ
                    || b.op() == net.cumba.cdisc.core.expr.ast.Expr.BinOp.NEQ;
            boolean boolEqVerdict = eq
                    && ((isBoolLiteral(b.left()) && isBroadcastVerdictExpr(b.right()))
                            || (isBoolLiteral(b.right()) && isBroadcastVerdictExpr(b.left())));
            yield factPair || boolEqVerdict;
        }
        case net.cumba.cdisc.core.expr.ast.Expr.Call c -> isExistsCall(c)
                || net.cumba.cdisc.core.expr.eval.BroadcastFold.isDatasetFactBoolCall(c)
                || net.cumba.cdisc.core.expr.eval.BroadcastFold.isLibraryGateCall(c);
        // A bare boolean reference verdict is only broadcast-safe when it is a $-operation result;
        // a bare COLUMN/DOTTED/WILDCARD reference reads per-row data and declines.
        case net.cumba.cdisc.core.expr.ast.Expr.Ref r -> r
                .kind() == net.cumba.cdisc.core.expr.OperandKind.OPERATION_REF;
        case net.cumba.cdisc.core.expr.ast.Expr.Lit _ -> false;
        };
    }


    /** An {@code exists}/{@code not_exists} call on a single bare reference (the dataset name). */
    private static boolean isExistsCall(net.cumba.cdisc.core.expr.ast.Expr.Call c)
    {
        return net.cumba.cdisc.core.expr.eval.BroadcastFold.isExistsCall(c);
    }


    /**
     * A broadcast-safe <b>dataset-fact</b> operand — everything the legacy
     * {@code CheckConditionOptimizer.evaluateDatasetLeaf} folds at dataset level (R-P2,
     * {@code plans/done/PLAN-native-engine-residuals.md}). Single source:
     * {@link net.cumba.cdisc.core.expr.eval.BroadcastFold#isDatasetFactOperand}, shared with the
     * runtime tri-state fold so the load-time flag and the fold can never drift.
     */
    private static boolean isDatasetPresenceOperand(net.cumba.cdisc.core.expr.ast.Expr e)
    {
        return net.cumba.cdisc.core.expr.eval.BroadcastFold.isDatasetFactOperand(e);
    }


    /** Whether {@code e} is a BOOL literal ({@code true} or {@code false}). */
    private static boolean isBoolLiteral(net.cumba.cdisc.core.expr.ast.Expr e)
    {
        return e instanceof net.cumba.cdisc.core.expr.ast.Expr.Lit lit
                && lit.kind() == net.cumba.cdisc.core.expr.ast.Expr.LitKind.BOOL;
    }


    /**
     * Raises a Check tree to the {@link net.cumba.cdisc.core.expr.ast.Expr} IR, returning
     * {@code null} for a mixed / old-style Check that has no faithful expression surface (so the
     * rule keeps {@code checkExpr == null} and runs on the legacy path).
     */
    private static net.cumba.cdisc.core.expr.ast.@Nullable Expr tryRaiseToExpr(CheckCondition check)
    {
        try
        {
            return net.cumba.cdisc.core.expr.CheckToExpr.toExpr(check);
        }
        catch (net.cumba.cdisc.core.expr.ExpressionException _)
        {
            return null;
        }
    }


    /**
     * Serializes a single {@link Rule} back to its JSON object form using the same mapper that
     * loads rule packs, so the title-case {@code @JsonProperty} keys round-trip faithfully.
     *
     * @param rule
     *            the rule to serialize ({@code null} yields {@code "null"})
     * @return the rule as a JSON string
     * @throws java.io.UncheckedIOException
     *             if serialization fails
     */
    public static String toJson(Rule rule)
    {
        try
        {
            return MAPPER.writeValueAsString(rule);
        }
        catch (IOException e)
        {
            throw new java.io.UncheckedIOException("Failed to serialize rule to JSON", e);
        }
    }


    private RulePackageLoader()
    {
    }

    // ---------------------------------------------------------------------
    // Fix #37 — operand-template substitution validation
    // ---------------------------------------------------------------------


    /**
     * Walks every rule's Check tree once, parsing any leaf {@code name} / {@code value} that
     * contains a <code>"${"</code> operand template and validating the operator-compatibility
     * matrix. Errors are collected per-rule into {@link Rule#getLoadError()} (joined with
     * <code>"; "</code>); the rule is not removed from the package, so other rules still load.
     *
     * <p>
     * ⚑ Every pass below runs for every package. The {@code PackageProvenance} parameter this
     * method used to take existed for one reason — exempting the engine's own
     * {@code rules-templates.json} from {@link #validateRetiredUnderscoreKeys} — and went with that
     * file (Fix #366).
     * </p>
     *
     * @param pkg
     *            the bound package
     */
    static void validateOperandSubstitution(RulePackage pkg)
    {
        if (pkg == null || pkg.getRules() == null)
        {
            return;
        }
        for (Rule rule : pkg.getRules().values())
        {
            if (rule == null)
            {
                continue;
            }
            List<String> errors = new ArrayList<>();
            // ⚑ Plan C §3.3: every declared level (one entry, and that entry IS getCheck(), for
            // every rule that authors a plain Check:).
            for (CheckCondition level : rule.checkConditions())
            {
                walkCheck(level, "Check", errors, ruleId(rule));
            }
            if (rule.getPrecondition() != null)
            {
                walkCheck(rule.getPrecondition(), "Precondition", errors, ruleId(rule));
            }
            // Reject empty/null entries in Scope.Domains.Include / Exclude: a zero-length entry
            // is not a dataset name, so it can only be an authoring slip. (This check arrived
            // with the since-retired Fix #38 prefix semantics, where an empty entry would have
            // matched every dataset; under exact matching it matches nothing instead — either
            // way it is never what the rule author meant.)
            validateDomainScopeEntries(rule, errors);
            // Phase 4 (PLAN-extend-expression-engine) — pre-compile glob/regex entries in
            // Scope.Domains and Scope.Variables so an invalid /…/ regex fails loud at load
            // time instead of blowing up scope matching at generation time.
            validateScopePatternEntries(rule, errors);
            // Fix #24 — reject wildcards keys that don't appear as a captured group in any
            // leaf wildcard. A typo'd group name silently does nothing, masking the author's
            // intent; surfacing it as a load error catches it at boot.
            validateWildcardFilters(rule, errors);
            // Fix #147 — an Expansion: block is only usable if the engine can substitute its
            // token unambiguously. Every failure mode below is silent otherwise: an unknown
            // `over:` drops the directive, a sigil-free token collides with a real column name,
            // and a token in Scope.Variables is matched literally by a scope gate that runs
            // BEFORE expansion — the shape that left 25 CDISC-AD rules always-skipped.
            validateExpansionDirectives(rule, errors);
            // A library_dataset_* / define_dataset_* operand no ds_* accessor serves is
            // definitionally wrong — fail at load (the former "outside Dataset Metadata Check"
            // half of this gate died with the rule type, leaf-scope phase 6).
            validateDatasetProviderOperands(rule, errors);
            // PLAN-underscore-field-retirement: the `_`-prefixed spellings are gone. The mapper
            // is lenient, so a stale `_wildcards:` would bind to nothing and the rule would
            // expand UNFILTERED — a silent behaviour change worse than the rename. ⚑ Fix #366
            // dropped the one exemption this guard had (the engine's own rules-templates.json):
            // the file is gone, so nothing is exempt and the guard runs on every package.
            validateRetiredUnderscoreKeys(rule, errors);
            // Gate R1 — the retired Scope.Variables spelling, ARMED since phase 5 dropped the
            // binding. NOTHING legitimately carries Scope.Variables: measured 2026-08-25, zero of
            // the 14 416 shipped rules/ records and zero of the 3 804 rules-src/checks files.
            validateRetiredScopeVariables(rule, errors);
            // Gates R2 / R3 / R4 — the Requirements block's shape.
            // A misspelled facet or an unsatisfiable Any is wrong wherever the package came from.
            // (R1a, "declares both spellings", retired with the Scope.Variables binding: the model
            // can no longer hold both, and the surviving half is R1's.)
            validateRequirementsShape(rule, errors);
            // Gate R8 — an AUTHORED Precondition. Runs here, i.e. BEFORE
            // injectInlineOperationGates (finishLoad), so it judges the authored document and never
            // the loader's own injected availability terms.
            validateNoAuthoredPrecondition(rule, errors);
            if (!errors.isEmpty())
            {
                // ⚠ APPEND, never overwrite. Every sibling writer of this field appends; this one
                // used to assign, which was latent only because no rule reached here already
                // carrying an error from an EARLIER finishLoad pass (normalizeOperations' EC-51
                // polarity rejection, :556; checkVariableUniverse, :1750/:1763). Gate R8 made that
                // reachable — a rule with an authored Precondition AND a silencing consumer lost
                // the first diagnosis entirely and reported only R8's.
                String joined = String.join("; ", errors);
                rule.setLoadError(
                        rule.getLoadError() == null ? joined : rule.getLoadError() + "; " + joined);
            }
        }
    }

    // ---------------------------------------------------------------------
    // PLAN-underscore-field-retirement — retired `_`-spelling guard
    // ---------------------------------------------------------------------

    /**
     * Retired {@code _}-prefixed rule keys that still have a modelled successor <em>on a corpus
     * rule</em>, mapped to the new spelling. Jackson no longer binds these, so they reach
     * {@link Rule#getUnknownKeys()}.
     *
     * <p>
     * The template-steering trio is deliberately <b>not</b> here: with the built-in templates gone
     * (Fix #366) neither spelling has a legal carrier anywhere, so the remedy is deletion — see
     * {@link #RETIRED_KEYS_REMOVED}.
     * </p>
     */
    private static final Map<String, String> RETIRED_KEYS_RENAMED = Map.of("_wildcards",
            "wildcards", "_wildcardExclude", "wildcardExclude", "_wildcardPairCatalogue",
            "wildcardPairCatalogue", "_skipIfLibraryDefined", "skipIfLibraryDefined");

    /**
     * Retired rule keys with no successor: the field itself is gone, so the only remedy is
     * deletion.
     *
     * <p>
     * ⚑ Fix #366 folded the three template-steering fields in here, in <b>both</b> spellings. They
     * were legal only inside the engine's {@code rules-templates.json}; that file is deleted, the
     * {@code Rule} model no longer binds them, and the post-filters that read them are gone. Both
     * spellings therefore reach {@link Rule#getUnknownKeys()} now, and both must be rejected: a
     * stale {@code templateFamily:} left on a rule would otherwise be dropped in silence, which is
     * exactly the failure this guard exists to prevent. (This is why the second, field-value arm
     * this method used to carry could be removed — there is no bound field left for it to read.)
     * </p>
     */
    private static final java.util.Set<String> RETIRED_KEYS_REMOVED = java.util.Set.of(
            "_templateNote", "_note", "_resolution", "_template", "_links", "_templateFamily",
            "templateFamily", "_suffixExclusions", "suffixExclusions",
            "_requireAllWildcardsInDataset", "requireAllWildcardsInDataset");

    /**
     * PLAN-underscore-field-retirement: rejects every retired rule-field spelling.
     *
     * <p>
     * The retired keys bind to no property any more, so the lenient mapper routes them to
     * {@link Rule#getUnknownKeys()}. Left unguarded a stale {@code _wildcards:} is silently dropped
     * and the rule expands unfiltered — the failure this guard exists to prevent. The advice is the
     * new spelling for the four fields a rule may still carry ({@link #RETIRED_KEYS_RENAMED}) and
     * <em>deletion</em> for everything in {@link #RETIRED_KEYS_REMOVED}, which since Fix #366
     * includes both spellings of the three template-steering fields.
     * </p>
     *
     * <p>
     * ⚑ The second arm this method used to carry — a field-value check for {@code templateFamily} /
     * {@code suffixExclusions} / {@code requireAllWildcardsInDataset}, which <em>bound</em> and so
     * could never reach the unknown-key collector — went with those model fields (Fix #366). The
     * keys did not lose their rejection path; they moved into the arm below.
     * </p>
     *
     * <p>
     * Keys that are not retired stay silently dropped exactly as before.
     * </p>
     *
     * @param rule
     *            the bound rule
     * @param errors
     *            per-rule error accumulator; joined into {@link Rule#setLoadError} by the caller
     */
    private static void validateRetiredUnderscoreKeys(Rule rule, List<String> errors)
    {
        for (String key : rule.getUnknownKeys())
        {
            String replacement = RETIRED_KEYS_RENAMED.get(key);
            if (RETIRED_KEYS_REMOVED.contains(key))
            {
                // Ordered ahead of the rename branch on purpose: the template-steering trio does
                // have a non-underscore spelling, but it is not a legal one anywhere any more.
                // Advising the rename would name a remedy that is itself rejected on the next load.
                errors.add("retired rule field '" + key + "': removed — delete the key");
            }
            else if (replacement != null)
            {
                errors.add("retired rule field '" + key + "': rename it to '" + replacement + "'");
            }
        }
    }

    // ---------------------------------------------------------------------
    // PLAN-scope-requirements-split — the Requirements gates R1–R4, R8
    // ---------------------------------------------------------------------

    /**
     * The one retired {@code Scope} key gate R1 owns by name — and therefore the one key gate R2's
     * {@code Scope} arm must not also report generically.
     */
    private static final String RETIRED_SCOPE_VARIABLES_KEY = "Variables";

    /**
     * Gate R1 — the retired {@code Scope.Variables} spelling.
     *
     * <p>
     * Reads {@link Scope#getUnknownKeys()}, exactly as {@link #validateRetiredUnderscoreKeys} reads
     * {@link Rule#getUnknownKeys()}. It was written <b>self-arming</b>: while {@link Scope} still
     * bound a {@code Variables} property the key never reached the collector and the gate was
     * inert; phase 5 dropped that property and the gate now <b>fires from JSON</b>. That is the
     * whole point — the mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES} disabled, so without a
     * gate a leftover {@code Scope: {Variables: …}} binds to nothing and the rule silently runs
     * <em>unrestricted</em>: not a skipped rule, a rule with its requirement deleted.
     * </p>
     *
     * <p>
     * ⚠ <b>Never exempted.</b> This gate was deliberately un-gated even while the engine's own
     * {@code rules-templates.json} was exempt from {@link #validateRetiredUnderscoreKeys}: on a
     * template the diagnosis does not merely move later, it disappears from the whole pipeline, and
     * every rule the template expands to would run unrestricted. That asymmetry is moot since Fix
     * #366 — nothing is exempt from anything here any more — but the reasoning is why no
     * per-package exemption should be reintroduced for a load gate.
     * </p>
     */
    private static void validateRetiredScopeVariables(Rule rule, List<String> errors)
    {
        Scope scope = rule.getScope();
        if (scope == null || !scope.getUnknownKeys().contains(RETIRED_SCOPE_VARIABLES_KEY))
        {
            return;
        }
        errors.add("[" + ruleId(rule) + "] retired field 'Scope.Variables': move it to"
                + " 'Requirements.Variables' (Include -> All, Exclude -> None). Scope says WHERE a"
                + " rule runs; a variable requirement says what the rule needs in order to answer"
                + " at all, and left here it binds to nothing and the restriction disappears");
    }


    /**
     * Gate R8 — an <b>authored</b> {@code Precondition} (owner ruling Q3).
     *
     * <p>
     * The field stays engine-internal: the loader writes it (availability gates for inlined library
     * / define / dictionary calls) and {@code RuleRunner} evaluates it, both unchanged. What
     * retires is the authoring door. A field that only the loader writes and only the runner reads
     * has no authoring contract to keep, and leaving the door open lets the corpus grow a value
     * tier that duplicates {@code Requirements} (metadata tier) and {@code Check} (value tier).
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Ordering is the whole difficulty.</b> This runs from
     * {@link #validateOperandSubstitution}, which {@code finishLoad} calls <em>before</em>
     * {@link #injectInlineOperationGates}. Wired anywhere downstream of the injection it would red
     * precisely the rules the injection exists for.
     * </p>
     *
     * <p>
     * ⛔ There used to be a second early return here on {@code getInjectedPreconditionGates() !=
     * null}, described as belt-and-braces for a package put through {@code finishLoad} twice. It
     * was <b>unreachable and untested</b>: {@link #validateOperandSubstitution} has exactly one
     * caller, always before the injection, and {@code injectedPreconditionGates} is
     * {@code @JsonIgnore} so it can never be authored — an unconditional bypass keyed on a field,
     * which any future path re-running the validator on an injected package would have turned into
     * a silent disabling of R8 for exactly the rules injection touched. It was also redundant:
     * {@link #injectInlineOperationGates} ANDs its gates in FRONT of any surviving authored
     * {@code Precondition} and returns early on a rule that already carries a {@code loadError}, so
     * a rule R8 rejected is never injected into, and a second pass therefore only ever sees an
     * injection-only {@code Precondition} — which {@link #isAvailabilityGateOnly} recognises on its
     * own. That recogniser is now the single, reachable, tested door.
     * </p>
     *
     * <p>
     * ⚠ Deliberately never exempted: an authored {@code Precondition} is wrong from a template and
     * from an externally supplied package too.
     * </p>
     */
    private static void validateNoAuthoredPrecondition(Rule rule, List<String> errors)
    {
        CheckCondition precondition = rule.getPrecondition();
        if (precondition == null || isAvailabilityGateOnly(precondition))
        {
            return;
        }
        errors.add("[" + ruleId(rule) + "] 'Precondition' is not an authorable field: it is an"
                + " engine-internal channel carrying machine-emitted availability gates"
                + " (library_available() / dictionary_available(<type>) / available(<call>))."
                + " Express a metadata-answerable condition as a 'Requirements' facet, and a value"
                + " condition as a Check conjunct");
    }

    /** The three call names the availability-gate writers emit, and nothing else. */
    private static final java.util.Set<String> AVAILABILITY_GATE_CALLS = java.util.Set
            .of("library_available", "dictionary_available", "available");

    /**
     * Whether every AND-term of {@code precondition} is one of the machine-emitted availability
     * gates — the exact shape {@code injectInlineOperationGates}, {@code inlineVariableExistsOps},
     * {@code inlineSplitByOps} and {@code OperationInliner.addLibraryPreconditionGate} write.
     *
     * <p>
     * ⚠⚠ This is what keeps gate R8 an <b>authoring</b> gate rather than a corpus gate. The
     * assembled {@code rules/} packages load through the same {@code CORPUS} path as authored
     * files, and {@code OperationInliner} bakes its gate into them <em>offline</em> — so by the
     * time the loader sees such a package the gate is indistinguishable from an authored value by
     * presence alone, and {@code injectedPreconditionGates} (set only by <em>this process's</em>
     * injection) is null. Recognising the gate shape is the only test that separates the two. It is
     * green in both directions today — both corpora carry zero {@code Precondition} keys — which is
     * precisely why it must be written down rather than left to be discovered the first time the
     * generator inlines an availability-dependent call.
     * </p>
     *
     * <p>
     * An unraisable {@code Precondition} answers {@code false}: no writer emits one, so it can only
     * be authored.
     * </p>
     */
    private static boolean isAvailabilityGateOnly(CheckCondition precondition)
    {
        net.cumba.cdisc.core.expr.ast.Expr raised = tryRaiseToExpr(precondition);
        if (raised == null)
        {
            return false;
        }
        for (net.cumba.cdisc.core.expr.ast.Expr term : flattenAnd(raised))
        {
            if (!(term instanceof net.cumba.cdisc.core.expr.ast.Expr.Call call)
                    || !AVAILABILITY_GATE_CALLS.contains(call.name()))
            {
                return false;
            }
        }
        return true;
    }


    /**
     * Gates R2 / R3 / R4 — the shape of the {@code Requirements} block.
     *
     * <ul>
     * <li>⛔ <b>R1a</b> — <b>retired.</b> It rejected {@code Scope.Variables} <em>and</em>
     * {@code Requirements.Variables} declared at once, because
     * {@link Rule#effectiveVariableRequirement()} would silently prefer the new block and the rule
     * would run on a requirement its author never sees. Since phase 5 of
     * {@code plans/PLAN-scope-requirements-split.md} dropped the {@code Scope.Variables} binding
     * that state is <em>unrepresentable</em>: the retired half binds to nothing and reaches
     * {@link Scope#getUnknownKeys()}, so <b>R1</b> ({@link #validateRetiredScopeVariables})
     * diagnoses the rule and names the half to delete. Kept in this list, marked retired, so a
     * reader looking for the gate the specification once named finds where it went.</li>
     * <li><b>R2</b> — an unknown key under {@code Scope}, {@code Scope.Datasets},
     * {@code Requirements} or {@code Requirements.Variables}. The mapper is lenient, so a
     * misspelled facet is a requirement that silently does not exist — and a misspelled
     * <em>scope</em> facet is worse than a missing one: {@code Scope: {Domians: …}} does not narrow
     * the rule to nothing, it runs the rule against every dataset in the study.</li>
     * <li><b>R3</b> — an empty or null entry in any requirement list. Mirrors the
     * {@code Scope.Domains} empty-entry rejection: an empty entry is not a name, so it can only be
     * an authoring slip.</li>
     * <li><b>R4</b> — the degenerate {@code Any} shapes (owner ruling Q9, all six load errors): a
     * one-entry or empty {@code Any}, and any non-empty intersection between two facets.</li>
     * </ul>
     *
     * <p>
     * ⚠ Facet intersections are compared on the trimmed, <b>upper-cased</b> entry. Two entries that
     * resolve to the same column by different spellings ({@code --DTC} and {@code AEDTC}) are still
     * not detected here, and deliberately so: that resolution depends on the dataset, which load
     * time does not have. Case, by contrast, is <em>not</em> a difference to any consumer —
     * {@link net.cumba.cdisc.core.exec.ScopeMatcher}'s pattern arm compiles
     * {@link java.util.regex.Pattern#CASE_INSENSITIVE} and its literal arm lands on
     * {@code DataTableMeta}'s {@code equalsIgnoreCase} — so folding it is what makes this gate
     * agree with the thing it is guarding. Its sibling {@link #checkMatchDatasetRequirements} (R7)
     * folds identically.
     * </p>
     */
    private static void validateRequirementsShape(Rule rule, List<String> errors)
    {
        Scope scope = rule.getScope();
        Requirements req = rule.getRequirements();
        if (scope != null)
        {
            // ⚠⚠ Scope's OWN unknown keys, not just its children's. Until this arm existed the
            // only reader of Scope.getUnknownKeys() was gate R1, which tests contains("Variables")
            // — one literal key — so `Scope: {Domians: {Include: ["AE"]}}` loaded clean and the
            // rule ran against EVERY dataset in the study: verbatim the failure this gate's
            // contract says it prevents, and the same for Class / Use_Cases / Data_Structure /
            // Dataset. RETIRED_SCOPE_VARIABLES_KEY is skipped here because R1 already reports it,
            // and by name — R1's message tells the author where to move the block, which the
            // generic unknown-key message cannot.
            reportUnknownKeys(rule, "Scope",
                    scope.getUnknownKeys().stream()
                            .filter(key -> !RETIRED_SCOPE_VARIABLES_KEY.equals(key)).toList(),
                    errors);
            if (scope.getDatasets() != null)
            {
                reportUnknownKeys(rule, "Scope.Datasets", scope.getDatasets().getUnknownKeys(),
                        errors);
            }
        }
        if (req == null)
        {
            return;
        }
        reportUnknownKeys(rule, "Requirements", req.getUnknownKeys(), errors);
        VariableRequirement vars = req.getVariables();
        if (vars != null)
        {
            reportUnknownKeys(rule, "Requirements.Variables", vars.getUnknownKeys(), errors);
            // ⛔ No "declares both spellings" arm any more, and deliberately: since phase 5 dropped
            // the Scope.Variables binding, a rule declaring both reaches gate R1
            // (validateRetiredScopeVariables) through Scope's unknown-key collector, which reports
            // the retired half by name. A second arm here could only fire on a state the model can
            // no longer represent.
            checkRequirementEntries(rule, vars.getAll(), "Requirements.Variables.All", errors);
            checkRequirementEntries(rule, vars.getAny(), "Requirements.Variables.Any", errors);
            checkRequirementEntries(rule, vars.getNone(), "Requirements.Variables.None", errors);
            checkAnyFacetShape(rule, vars, errors);
        }
        checkRequirementEntries(rule, req.getDatasets(), "Requirements.Datasets", errors);
    }


    /** R2's message, shared by the three blocks that can carry an unbound key. */
    private static void reportUnknownKeys(Rule rule, String where,
            java.util.Collection<String> unknownKeys, List<String> errors)
    {
        for (String key : unknownKeys)
        {
            errors.add("[" + ruleId(rule) + "] unknown key '" + key + "' under '" + where
                    + "': it binds to nothing, so whatever it was meant to require is not"
                    + " required — check the spelling");
        }
    }


    /** R3 — an empty or null entry in a requirement list. One error per list is enough. */
    private static void checkRequirementEntries(Rule rule, @Nullable List<String> entries,
            String where, List<String> errors)
    {
        if (entries == null)
        {
            return;
        }
        for (String entry : entries)
        {
            if (entry == null || entry.isBlank())
            {
                errors.add("[" + ruleId(rule) + "] " + where + " contains an empty/null entry —"
                        + " an empty entry names nothing; remove it or replace it with a name");
                return;
            }
        }
    }


    /** R4 — the degenerate {@code Any} shapes and the three facet intersections (ruling Q9). */
    private static void checkAnyFacetShape(Rule rule, VariableRequirement vars, List<String> errors)
    {
        List<String> any = vars.getAny();
        // ⚠ DISTINCT entries, not entries: `Any: ["AESEV","AESEV"]` is exactly the degenerate
        // one-column Any this arm's own message describes, and counting the raw list let it
        // through. Folded the same way the overlap arms fold, so `["AESEV","aesev"]` — which no
        // consumer can tell apart — is caught too.
        int distinctAny = any == null ? 0 : normalizedFacet(any).size();
        if (any != null && distinctAny < MIN_ANY_ENTRIES)
        {
            errors.add("[" + ruleId(rule) + "] Requirements.Variables.Any needs at least "
                    + MIN_ANY_ENTRIES + " distinct entries, got " + distinctAny + " (from "
                    + any.size() + "): a one-entry Any is All with extra ceremony and hides a"
                    + " truncated list, and an empty one is unsatisfiable");
        }
        reportFacetOverlap(rule, "Any", any, "All", vars.getAll(),
                "the Any leg is then already satisfied by the All leg and says nothing", errors);
        reportFacetOverlap(rule, "Any", any, "None", vars.getNone(),
                "the entry would have to be both present and absent", errors);
        reportFacetOverlap(rule, "All", vars.getAll(), "None", vars.getNone(),
                "the entry would have to be both present and absent", errors);
    }

    /** The smallest {@code Any} list that means anything a plain {@code All} does not. */
    private static final int MIN_ANY_ENTRIES = 2;

    private static void reportFacetOverlap(Rule rule, String leftName, @Nullable List<String> left,
            String rightName, @Nullable List<String> right, String why, List<String> errors)
    {
        if (left == null || right == null)
        {
            return;
        }
        java.util.Set<String> rightNormalized = normalizedFacet(right);
        for (String entry : left)
        {
            if (entry != null && rightNormalized.contains(normalizeFacetEntry(entry)))
            {
                errors.add("[" + ruleId(rule) + "] Requirements.Variables entry '" + entry.trim()
                        + "' appears in both " + leftName + " and " + rightName + " — " + why);
            }
        }
    }


    /**
     * One facet's entries as the comparison sees them: trimmed, upper-cased, nulls dropped,
     * duplicates collapsed.
     *
     * <p>
     * ⚠⚠ The case fold is the whole point, and it is not cosmetic. Every consumer of a variable
     * requirement matches case-blind — {@code ScopeMatcher.scopePattern} compiles
     * {@link java.util.regex.Pattern#CASE_INSENSITIVE}, and the literal arm lands on
     * {@code DataTableMeta}'s {@code equalsIgnoreCase} — so before this fold
     * {@code {"All":["AESEV"],"None":["aesev"]}} loaded with <b>no error</b> and then required
     * {@code AESEV} to be both present and absent: a rule that matches no dataset, ever, and says
     * nothing about why. The same shape made an {@code Any} leg silently vacuous against
     * {@code All} and unsatisfiable against {@code None}. Gate R7
     * ({@link #checkMatchDatasetRequirements}) has always folded this way; this is the two halves
     * of one plan agreeing.
     * </p>
     *
     * <p>
     * Folding is safe for the pattern spellings too: a {@code /…/} regex and a {@code --}-glob are
     * both compiled {@code CASE_INSENSITIVE}, so two entries differing only in case denote the same
     * column set and collapsing them cannot produce a false overlap.
     * </p>
     *
     * @param entries
     *            the authored facet list
     * @return the normalized entries, in encounter order
     */
    private static java.util.Set<String> normalizedFacet(List<String> entries)
    {
        java.util.Set<String> normalized = new java.util.LinkedHashSet<>();
        for (String entry : entries)
        {
            if (entry != null)
            {
                normalized.add(normalizeFacetEntry(entry));
            }
        }
        return normalized;
    }


    /** One entry's half of {@link #normalizedFacet}. */
    private static String normalizeFacetEntry(String entry)
    {
        return entry.trim().toUpperCase(java.util.Locale.ROOT);
    }

    // ---------------------------------------------------------------------
    // Phase 2 (PLAN-extend-expression-engine) — fail loud on invalid enum values
    // ---------------------------------------------------------------------

    /** Valid {@code Sensitivity} JSON values, in declaration order, for the load-error message. */
    private static final String SENSITIVITY_VALUES = java.util.stream.Stream
            .of(Sensitivity.values()).map(Sensitivity::getJsonValue)
            .collect(java.util.stream.Collectors.joining(", "));

    /**
     * The severities a <b>rule</b> may author — the four-rung ladder, strictest first.
     *
     * <p>
     * ⚠ This is deliberately <b>narrower</b> than {@code Severity.values()}. The report enum also
     * carries {@code NOTICE}, which is a report-only kind produced by the engine and authored by no
     * rule; without this set the generic present-but-unrecognised gate would happily accept
     * {@code Severity: "Notice"} on a rule, because it parses.
     * </p>
     */
    private static final java.util.Set<Severity> RULE_SEVERITIES = java.util.EnumSet
            .of(Severity.REJECT, Severity.ERROR, Severity.WARNING, Severity.INFO);

    private static final String SEVERITY_VALUES = RULE_SEVERITIES.stream()
            .map(Severity::getJsonValue).collect(java.util.stream.Collectors.joining(", "));

    /**
     * Every spelling of a <b>run severity threshold</b> this loader recognises well enough to
     * reject — on a package and on a rule alike (Plan C &#167;3.4, ruling 4).
     *
     * <p>
     * ⛔ The threshold is a <b>run option and nothing else</b>: the CLI's {@code --severity-level},
     * the REST {@code CheckRunRequest} field and the {@code .cdt} {@code #runLevel} directive all
     * set the same {@code StudyValidationParams.severityThreshold}. It is not a package field — a
     * per-package threshold would let one rule behave differently in two packages, contradicting
     * the {@code rules/} findings-diff invariant — and it is not a per-rule field either: a rule
     * declares <em>which levels exist</em>, never <em>which levels run</em>.
     * </p>
     *
     * <p>
     * The alternative to naming the spellings is silence: with {@code FAIL_ON_UNKNOWN_PROPERTIES}
     * disabled an unmodelled key is dropped without trace, and an author would believe a threshold
     * was in force when it was not.
     * </p>
     */
    private static final java.util.Set<String> THRESHOLD_KEYS = java.util.Set.of(
            "Severity_Threshold", "severity_threshold", "severityThreshold", "SeverityThreshold",
            "Severity_Level", "severity_level", "severityLevel", "Run_Level", "runLevel");

    /** Valid {@code Variable_Universe} values, in declaration order, for the load-error message. */
    private static final String VARIABLE_UNIVERSE_VALUES = java.util.stream.Stream
            .of(net.cumba.cdisc.core.model.VariableUniverse.values())
            .map(net.cumba.cdisc.core.model.VariableUniverse::getJsonValue)
            .collect(java.util.stream.Collectors.joining(", "));

    /** Valid {@code Executability} values, in declaration order, for the load-error message. */
    private static final String EXECUTABILITY_VALUES = java.util.stream.Stream
            .of(Executability.values()).map(Executability::getJsonValue)
            .collect(java.util.stream.Collectors.joining(", "));

    /** Valid {@code Join_Type} values, in declaration order, for the load-error message. */
    private static final String JOIN_TYPE_VALUES = java.util.stream.Stream
            .of(net.cumba.cdisc.core.model.JoinType.values())
            .map(net.cumba.cdisc.core.model.JoinType::getJsonValue)
            .collect(java.util.stream.Collectors.joining(", "));

    /**
     * Walks every rule and tags those whose {@code Sensitivity} / {@code Executability} /
     * {@code Join_Type} carry a <b>present but unrecognized</b> string with a load error, so the
     * rule executes as an ERROR (the existing {@code loadError} sentinel in
     * {@code RuleRunner.execute}) instead of silently degrading to the {@code null}-field
     * behaviour. An <b>absent</b> field stays legal (today's {@code null} semantics — e.g. rules
     * without {@code Sensitivity} derive it).
     */
    static void validateEnumFields(RulePackage pkg)
    {
        if (pkg == null || pkg.getRules() == null)
        {
            return;
        }
        for (Rule rule : pkg.getRules().values())
        {
            validateEnumFields(rule);
        }
    }


    /**
     * Per-rule variant of {@link #validateEnumFields(RulePackage)}, also applied by
     * {@link LibraryRuleMapper} so CDISC-Library-sourced rules fail identically. Appends to a
     * pre-existing {@code loadError} (e.g. an operand-substitution error) instead of clobbering it.
     */
    static void validateEnumFields(@Nullable Rule rule)
    {
        if (rule == null)
        {
            return;
        }
        List<String> errors = new ArrayList<>();
        // Owner ruling 6 (Q3) of PLAN-leaf-scope-domain-inference.md: an externally supplied rule
        // still carrying Rule_Type is a LOAD ERROR naming the migration — no silent translation.
        if (rule.getRejectedRuleType() != null)
        {
            errors.add(ruleTypeRejection(rule));
        }
        checkEnumField(rule, rule.getRawSensitivity(), rule.getSensitivity(), "Sensitivity",
                SENSITIVITY_VALUES, errors);
        checkEnumField(rule, rule.getRawExecutability(), rule.getExecutability(), "Executability",
                EXECUTABILITY_VALUES, errors);
        checkEnumField(rule, rule.getRawVariableUniverse(), rule.getVariableUniverse(),
                "Variable_Universe", VARIABLE_UNIVERSE_VALUES, errors);
        checkSeverityField(rule, errors);
        checkCheckLevels(rule, errors);
        checkJoinTypes(rule, errors);
        checkStudySensitivityScope(rule, errors);
        // Gate 3a (the Python one-frame-per-rule compatibility warning) is gone — phase 2 of
        // PLAN-leaf-scope-domain-inference.md: Java never needed the invariant it validated.
        // Gates 3b/3c stay errors — they are mechanical contradictions that mis-execute in Java
        // too.
        List<String> warnings = new ArrayList<>();
        checkDomainWildcardPrefix(rule, warnings);
        checkGroupSensitivityConsistency(rule, errors);
        // Gate R5 — Requirements.Library/.Define/.Dictionary ⟺ the DERIVED dependency. A
        // value-agreement gate, so it belongs beside gate 3b and not with the shape gates; being
        // here it also reaches LibraryRuleMapper, which calls this method and nothing else.
        checkProviderRequirements(rule, errors);
        // Gate R7 — a Match_Datasets secondary this rule does not declare. Its own channel, NOT
        // `warnings`: see Rule.getRequirementsGapWarning().
        checkMatchDatasetRequirements(rule);
        // Gate 3c (grouped operation ⇒ Rule_Type Record Data) is gone — phase 6 of
        // PLAN-leaf-scope-domain-inference.md: a grouped operation is a per-row GroupedResult,
        // which DomainScan reads as the row cursor; the domain forces the row path by construction.
        if (!warnings.isEmpty())
        {
            String joinedWarnings = String.join("; ", warnings);
            rule.setLoadWarning(rule.getLoadWarning() == null ? joinedWarnings
                    : rule.getLoadWarning() + "; " + joinedWarnings);
            LOGGER.log(System.Logger.Level.WARNING, "{0}", joinedWarnings);
        }
        if (errors.isEmpty())
        {
            return;
        }
        String joined = String.join("; ", errors);
        rule.setLoadError(
                rule.getLoadError() == null ? joined : rule.getLoadError() + "; " + joined);
    }

    // ---------------------------------------------------------------------
    // PLAN-scope-requirements-split — the value-agreement gates R5 and R7
    // ---------------------------------------------------------------------


    /**
     * Gate R5 — <b>{@code Requirements.Library} / {@code .Define} / {@code .Dictionary} ⟺ the
     * DERIVED dependency</b> (owner ruling Q4, option (c)).
     *
     * <p>
     * The field documents a fact the engine computes ({@link ProviderRequirements}); it never
     * declares one. Omitting it is always legal — that is the whole corpus today, and the assembler
     * materialises the value into the shipped package. An <em>authored</em> value that disagrees
     * with the derivation is an authoring contradiction: the runtime arms read the derivation
     * regardless, so silently preferring either side would ship a rule whose declaration lies about
     * what it does.
     * </p>
     */
    private static void checkProviderRequirements(Rule rule, List<String> errors)
    {
        Requirements req = rule.getRequirements();
        if (req == null || (req.getLibrary() == null && req.getDefine() == null
                && req.getDictionary() == null))
        {
            return;
        }
        ProviderRequirements derived = ProviderRequirements.of(rule);
        checkProviderFlag(rule, "Library", req.getLibrary(), derived.library(),
                "a library_* operand or a library-dependent Operation", errors);
        checkProviderFlag(rule, "Define", req.getDefine(), derived.define(),
                "a define_* operand or a define-dependent Operation", errors);
        checkProviderFlag(rule, "Dictionary", req.getDictionary(), derived.dictionary(),
                "a valid_external_dictionary_* / dictionary_has_decode call", errors);
    }


    private static void checkProviderFlag(Rule rule, String name, @Nullable Boolean declared,
            boolean derived, String what, List<String> errors)
    {
        if (declared == null || declared.booleanValue() == derived)
        {
            return;
        }
        errors.add("[" + ruleId(rule) + "] Requirements." + name + " is declared " + declared
                + " but the rule " + (derived ? "DOES" : "does NOT") + " use " + what
                + " — the field documents the derivation and cannot contradict it");
    }


    /**
     * Gate R7 (advisory) — a {@code Match_Datasets[].Name} the rule joins against but does not
     * declare in {@code Requirements.Datasets} (owner ruling Q5).
     *
     * <p>
     * A missing secondary is a DEBUG no-op at runtime: the join is simply not built and the rule
     * evaluates with its dotted references unresolved. Making the join <em>implicitly</em> required
     * would move findings on every one of those rules with no per-rule ruling, which is the
     * opposite of how this house changes semantics — so the gap is recorded rather than enforced,
     * and no promotion lane is scheduled.
     * </p>
     *
     * <p>
     * ⚠⚠ The finding lands on {@link Rule#getRequirementsGapWarning()}, <b>not</b> on
     * {@code loadWarning}: every carrier trips this on day one, and
     * {@code CrossCorpusDerivationTest} holds the shipped corpus to zero {@code loadWarning}s. That
     * assertion is not weakened to accommodate a warning it was built to catch.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Read this gate for what it currently is.</b> Measured on the shipped corpus 2026-08-25:
     * <b>923 of 923</b> {@code Match_Datasets}-carrying entries (<b>247</b> distinct
     * {@code Core.Id}s) trip it, and the {@code Requirements.Datasets} carriers (34 entries, 11
     * ids) are <b>disjoint</b> from the join carriers — so not one gap is closed today. The channel
     * it writes to is {@code @JsonIgnore}, never serialised, logged at DEBUG, and has exactly two
     * readers repo-wide: this loader and {@code RequirementsLoadGateTest}. A gate that fires on
     * 100&nbsp;% of its population into a channel nothing consumes measures nothing; it is kept
     * because ruling Q5 chose recording over enforcing, and it becomes informative only when a lane
     * starts declaring join secondaries. Anyone tempted to read a low gap count off it should know
     * that the count has never been anything but "all of them".
     * </p>
     */
    private static void checkMatchDatasetRequirements(Rule rule)
    {
        List<net.cumba.cdisc.core.model.MatchDataset> joins = rule.getMatchDatasets();
        if (joins == null || joins.isEmpty())
        {
            return;
        }
        Requirements req = rule.getRequirements();
        java.util.Set<String> declared = new java.util.LinkedHashSet<>();
        if (req != null && req.getDatasets() != null)
        {
            for (String entry : req.getDatasets())
            {
                if (entry != null)
                {
                    declared.add(entry.trim().toUpperCase(java.util.Locale.ROOT));
                }
            }
        }
        java.util.Set<String> missing = new java.util.LinkedHashSet<>();
        for (net.cumba.cdisc.core.model.MatchDataset join : joins)
        {
            String name = join == null ? null : join.getName();
            if (name != null && !name.isBlank()
                    && !declared.contains(name.trim().toUpperCase(java.util.Locale.ROOT)))
            {
                missing.add(name.trim());
            }
        }
        if (missing.isEmpty())
        {
            return;
        }
        String gap = "[" + ruleId(rule) + "] Match_Datasets " + missing
                + " not declared in Requirements.Datasets: a secondary that does not resolve is a"
                + " silent no-join, and the rule then evaluates on a changed meaning";
        rule.setRequirementsGapWarning(rule.getRequirementsGapWarning() == null ? gap
                : rule.getRequirementsGapWarning() + "; " + gap);
        LOGGER.log(System.Logger.Level.DEBUG, "{0}", gap);
    }

    // ---------------------------------------------------------------------
    // PLAN-dangling-operation-reference-load-check — a Check operand no Operation defines
    // ---------------------------------------------------------------------


    /**
     * Tags a rule whose {@code Check} (or {@code Precondition}) references a {@code $}-operand that
     * no {@code Operations} entry defines.
     *
     * <p>
     * Such a rule loads cleanly, passes every gate and <b>checks nothing</b>: an operand name
     * absent from the evaluation context is the legacy <em>"Variable not in context"</em> contract,
     * which {@code ExprCompiler.nameRefPlan} implements as {@code null ⇒ empty BitSet} — the leaf
     * contributes no rows. Nothing downstream distinguishes that silence from a clean dataset, and
     * no runtime path produces it: an operation that <em>cannot run</em> SKIPs the whole rule
     * instead ("no Library access", "library returned no data", absent Define-XML). A dangling
     * {@code $} is therefore a <b>corpus</b> state, and belongs to load.
     * </p>
     *
     * <p>
     * <b>A dangling {@code $} is always a {@code loadError}</b> — the rule then reports ERROR
     * through the {@code RuleRunner.execute} sentinel rather than passing silently. There is no
     * severity downgrade: before {@code Fix #159} a rule declaring
     * {@code Executability: "Not Executable"} was demoted to a {@code loadWarning} so it could keep
     * loading and running, but that field now <em>parks</em> the rule — {@code removeParkedRules}
     * drops it from the package before this gate ever sees it. Every rule reaching here has claimed
     * to be executable, so every finding here is an error.
     * </p>
     *
     * <p>
     * Both {@code Check} shapes are walked, because both ship: {@code rules/} carries the operand
     * inside a parsed expression ({@code CheckConditionExpression}) or a lowered operator leaf, and
     * {@code rules-legacy/} carries the same rule in leaf form. ⚠ The walk is over the parsed tree,
     * never the source text — a textual {@code $}-scan would false-positive on the {@code $} inside
     * a string literal and on the {@code ${VAR:fmt}} operand-substitution templates that appear
     * mid-name in 42 shipped Check expressions ({@code ADSL.TRT${APERIOD:%02d}P}).
     * </p>
     *
     * <p>
     * ⚠ An operation authored <b>inline</b> in a Check expression cannot produce a false positive
     * here: an inline call carries its own declaration and has no {@code $}-id to reference, so it
     * never appears on either side of the subtraction.
     * </p>
     */
    static void validateOperationReferences(@Nullable RulePackage pkg)
    {
        if (pkg == null || pkg.getRules() == null)
        {
            return;
        }
        for (Rule rule : pkg.getRules().values())
        {
            validateOperationReferences(rule);
        }
    }


    /**
     * Per-rule variant of {@link #validateOperationReferences(RulePackage)}. Appends to a
     * pre-existing {@code loadError} / {@code loadWarning} instead of clobbering it, so an earlier
     * cause (an operand-substitution or enum failure) keeps its first diagnosis.
     *
     * @param rule
     *            the rule to judge in place, may be {@code null}
     */
    static void validateOperationReferences(@Nullable Rule rule)
    {
        if (rule == null)
        {
            return;
        }
        java.util.Set<String> inCheck = new java.util.LinkedHashSet<>();
        // ⚑ Plan C §3.3: a $-ref that resolves nowhere is just as dangling in a weaker level.
        for (CheckCondition level : rule.checkConditions())
        {
            collectOperandRefs(level, inCheck);
        }
        // The Precondition gates whether the Check runs at all, so an operand that never resolves
        // there is at least as fatal: the gate itself contributes no rows. Collected separately
        // only so the message can name the surface it actually found — a diagnostic that says
        // "Check" about a Precondition sends its reader to the wrong half of the rule.
        java.util.Set<String> inPrecondition = new java.util.LinkedHashSet<>();
        collectOperandRefs(rule.getPrecondition(), inPrecondition);
        java.util.Set<String> undefined = new java.util.LinkedHashSet<>(inCheck);
        undefined.addAll(inPrecondition);
        if (undefined.isEmpty())
        {
            return;
        }
        List<Operation> ops = rule.getOperations();
        if (ops != null)
        {
            for (Operation op : ops)
            {
                if (op != null && op.getId() != null)
                {
                    undefined.remove(op.getId());
                }
            }
        }
        if (undefined.isEmpty())
        {
            return;
        }
        boolean anyInCheck = undefined.stream().anyMatch(inCheck::contains);
        boolean anyInPrecondition = undefined.stream().anyMatch(inPrecondition::contains);
        String surface = anyInCheck && anyInPrecondition ? "Check and Precondition"
                : anyInCheck ? "Check" : "Precondition";
        String message = "[" + ruleId(rule) + "] " + surface + " references the operand"
                + (undefined.size() == 1 ? " " : "s ") + String.join(", ", undefined)
                + " which no Operations entry defines: the name never enters the evaluation"
                + " context, so the leaf yields no rows and the rule silently checks nothing";
        rule.setLoadError(
                rule.getLoadError() == null ? message : rule.getLoadError() + "; " + message);
    }

    // ---------------------------------------------------------------------
    // Fix #156 — a `--` parked in an Operation field resolvePrefixes never resolves
    // ---------------------------------------------------------------------

    /** The literal domain-prefix wildcard token, as authored. */
    private static final String WILDCARD_TOKEN = "--";

    /**
     * The load-finding fragment {@link #validateUnresolvedOperationWildcards(Rule)} emits; the
     * corpus ratchet greps for it.
     */
    private static final String UNRESOLVED_WILDCARD_MARKER = "is not `--`-resolved";

    /**
     * Tags a rule whose {@code Operations} carry the {@code --} wildcard in {@code reference},
     * {@code ordering} or {@code offset} — the three <b>column-naming</b> Operation fields that
     * {@code OperationExecutor.resolvePrefixes} copies <b>verbatim</b>.
     *
     * <p>
     * All three reach {@code DataTableMeta.getColumnIndex} unchanged, so a literal {@code "--SEQ"}
     * misses every column and the operation quietly produces nothing: {@code evalIsLastInGroup}
     * returns {@code null} on {@code ordIdx < 0}, {@code evalDateDiffDays} silently treats an
     * unparseable, unresolvable {@code offset} as {@code 0} (a wrong answer, not a skip), and
     * {@code evalDy} / {@code evalDateDiffDays} read a missing {@code reference} column as "no
     * reference date". Nothing downstream distinguishes that from clean data — the same silence
     * class as {@link #validateOperationReferences(Rule)}, so it gets the same load channel.
     * </p>
     *
     * <p>
     * <b>Why a guard and not resolution.</b> The three fields have no single well-defined prefix to
     * substitute. {@code reference} names a column of the <em>evaluation</em> record in
     * {@code date_diff_days} Mode 1, of the <em>foreign {@code domain}</em> dataset in Mode 2, and
     * of <em>{@code DM}</em> in {@code dy} — three different datasets, one field. Substituting the
     * evaluation domain's variable prefix would therefore be wrong in two of the three modes, and
     * wrong silently. {@code ordering} and {@code offset} are unambiguous (both are read off the
     * evaluation table), but they are guarded alongside {@code reference} so the rule an author
     * learns is one rule and not a per-field table.
     * </p>
     *
     * <p>
     * ⚠ <b>{@code minuend_match} is deliberately NOT in this set.</b> Its {@code --} tokens are
     * resolved <em>per side</em> at evaluation time by
     * {@code OperationExecutor.buildForeignMinuendResolver} — {@code "--SPID"} becomes
     * {@code TFSPID} on the evaluation row and {@code PMSPID} on the matched {@code minuend_domain}
     * record (SENDIG §6.3.15.1 Assumption 5). {@code resolvePrefixes} leaves it alone <em>because
     * that is the design</em>, as {@code Operation#minuendMatch}'s javadoc and
     * {@code OperationExecutor}'s copy comment both state, and as
     * {@code OperationExecutorDateDiffLastInGroupTest} pins. Guarding it would reject the one shape
     * the field exists for.
     * </p>
     *
     * <p>
     * <b>Always a {@code loadError}</b>, exactly as {@link #validateOperationReferences(Rule)}: a
     * rule declaring {@code Executability: "Not Executable"} is parked by {@code removeParkedRules}
     * before this gate runs ({@code Fix #159}), so there is no self-declared severity downgrade
     * left to apply.
     * </p>
     *
     * <p>
     * ⚠ <b>Both operation surfaces are walked.</b> An operation authored <b>inline</b> in a native
     * Check expression never reaches {@code rule.getOperations()}, so the declared-list walk alone
     * would miss it — the same separate-load-path hazard {@code validateInlineMissingValues}
     * documents. This is <em>not</em> a sentinel-prefix collision risk: the walk keys off
     * {@code ExprCompiler.isInlineOperation}, which requires a real {@code OperationType} name, so
     * a native leaf function that merely shares a kwarg name is never mistaken for an operation.
     * Three functions carry an {@code ordering=} kwarg across the 29 shipped uses in {@code rules/}
     * and only one of them is an {@code OperationType}: {@code has_next_corresponding_record} (16)
     * and {@code empty_within_except_last_row} (7) are native Check functions,
     * {@code is_last_in_group} (6) is the operation.
     * </p>
     *
     * @param pkg
     *            the package to judge in place, may be {@code null}
     */
    static void validateUnresolvedOperationWildcards(@Nullable RulePackage pkg)
    {
        if (pkg == null || pkg.getRules() == null)
        {
            return;
        }
        for (Rule rule : pkg.getRules().values())
        {
            validateUnresolvedOperationWildcards(rule);
        }
    }


    /**
     * Per-rule variant of {@link #validateUnresolvedOperationWildcards(RulePackage)}. Appends to a
     * pre-existing {@code loadError} / {@code loadWarning} rather than clobbering it, so an earlier
     * cause keeps its first diagnosis.
     *
     * <p>
     * ⚠ Deliberately <b>not</b> wired into {@code LibraryRuleMapper}, unlike its two neighbours:
     * {@code LibraryRuleMapper.mapOperation} binds neither {@code reference} nor {@code ordering}
     * nor {@code offset} (the CDISC-Library operation model has no such members), so every
     * library-sourced operation carries {@code null} in all three and the call would be
     * unconditionally vacuous.
     * </p>
     *
     * @param rule
     *            the rule to judge in place, may be {@code null}
     */
    static void validateUnresolvedOperationWildcards(@Nullable Rule rule)
    {
        if (rule == null)
        {
            return;
        }
        List<String> findings = new ArrayList<>();
        List<Operation> ops = rule.getOperations();
        if (ops != null)
        {
            for (Operation op : ops)
            {
                String id = op == null || op.getId() == null || op.getId().isEmpty() ? "<unnamed>"
                        : op.getId();
                collectUnresolvedWildcardFields(op, "operation " + id, findings);
            }
        }
        // ⚑ Plan C §3.3: every declared level.
        for (CheckCondition level : rule.checkConditions())
        {
            collectInlineUnresolvedWildcards(level, findings);
        }
        collectInlineUnresolvedWildcards(rule.getPrecondition(), findings);
        if (findings.isEmpty())
        {
            return;
        }
        String message = "[" + ruleId(rule) + "] " + String.join(", ", findings)
                + ": that position " + UNRESOLVED_WILDCARD_MARKER
                + " (OperationExecutor.resolvePrefixes copies"
                + " reference/ordering/offset verbatim, because the column they name is not always"
                + " in the evaluation dataset), so the literal `--` reaches the column lookup,"
                + " misses, and the operation yields nothing — the rule silently checks nothing."
                + " Author the resolved column name";
        rule.setLoadError(
                rule.getLoadError() == null ? message : rule.getLoadError() + "; " + message);
    }


    /**
     * Appends a finding for each of {@code reference} / {@code ordering} / {@code offset} that
     * carries a {@code --}. {@code minuend_match} is excluded by design — see
     * {@link #validateUnresolvedOperationWildcards(RulePackage)}.
     */
    private static void collectUnresolvedWildcardFields(@Nullable Operation op, String where,
            List<String> findings)
    {
        if (op == null)
        {
            return;
        }
        addUnresolvedWildcardField(op.getReference(), "reference", where, findings);
        addUnresolvedWildcardField(op.getOrdering(), "ordering", where, findings);
        addUnresolvedWildcardField(op.getOffset(), "offset", where, findings);
    }


    private static void addUnresolvedWildcardField(@Nullable String value, String field,
            String where, List<String> findings)
    {
        if (value != null && value.contains(WILDCARD_TOKEN))
        {
            findings.add(where + " declares " + field + "=\"" + value + "\"");
        }
    }


    /** Walks a Check/Precondition tree for operations authored inline in a native expression. */
    private static void collectInlineUnresolvedWildcards(@Nullable CheckCondition condition,
            List<String> findings)
    {
        if (condition == null)
        {
            return;
        }
        switch (condition)
        {
        case CheckConditionAll all -> all.getConditions()
                .forEach(c -> collectInlineUnresolvedWildcards(c, findings));
        case CheckConditionAny any -> any.getConditions()
                .forEach(c -> collectInlineUnresolvedWildcards(c, findings));
        case CheckConditionNot not -> collectInlineUnresolvedWildcards(not.getCondition(),
                findings);
        case net.cumba.cdisc.core.model.CheckConditionExpression expression -> collectInlineUnresolvedWildcards(
                expression.expr(), findings);
        case CheckConditionLeaf _,CheckConditionConstant _ ->
        {
            // A legacy leaf carries no inline operation; its own `ordering` is a Check-tree
            // position resolved (or not) by CheckConditionTransformer, not by this pass.
        }
        }
    }


    private static void collectInlineUnresolvedWildcards(net.cumba.cdisc.core.expr.ast.Expr expr,
            List<String> findings)
    {
        if (expr instanceof net.cumba.cdisc.core.expr.ast.Expr.Call call
                && net.cumba.cdisc.core.expr.eval.ExprCompiler.isInlineOperation(call))
        {
            collectUnresolvedWildcardFields(inlineOperationOrNull(call),
                    "inline operation " + call.name() + "(…)", findings);
        }
        childrenOf(expr).forEach(child -> collectInlineUnresolvedWildcards(child, findings));
    }


    /**
     * Rebuilds the {@link Operation} an inline call declares, or {@code null} when the call is not
     * a well-formed one — the compiler rejects that on its own terms, and a malformed call is not
     * this pass's finding to report. Mirrors the bail-out of {@code gateTermsForCall}.
     */
    private static @Nullable Operation inlineOperationOrNull(
            net.cumba.cdisc.core.expr.ast.Expr.Call call)
    {
        try
        {
            return net.cumba.cdisc.core.expr.convert.OperationExpressionParser.fromCall(call, null);
        }
        catch (RuntimeException _)
        {
            return null;
        }
    }

    // ---------------------------------------------------------------------
    // PLAN-dictionary-seeder Phase 6a (D13 item 3) — a dictionary operation naming no type
    // ---------------------------------------------------------------------

    /**
     * The load-finding fragment {@link #validateDictionaryOperationTypes(Rule)} emits; tests and
     * any corpus ratchet grep for it.
     */
    private static final String TYPELESS_DICTIONARY_MARKER = "declares no external_dictionary_type";

    /**
     * Tags a rule that carries a dictionary-dependent operation
     * ({@code valid_external_dictionary_*} / {@code dictionary_has_decode}) with a null or blank
     * {@code external_dictionary_type}.
     *
     * <p>
     * <b>Why load, why error</b> (owner-ruled, D13 item 3). Such an operation can never be
     * satisfied by <em>any</em> install — there is no type to install — so it is an authoring
     * defect, not a runtime input-availability condition. Reporting it as SKIPPED with "no external
     * dictionary loaded" sends the operator to install something that cannot help;
     * {@code loadError} sends the author to the rule, through the same {@code RuleRunner.execute}
     * ERROR sentinel as {@link #validateOperationReferences(Rule)}'s dangling {@code $}.
     * </p>
     *
     * <p>
     * ⚠ <b>Both operation surfaces are walked, and the inline walk closes a real hole:</b>
     * {@code gateTermsForCall} injects the {@code dictionary_available(type)} precondition gate for
     * an inline dictionary operation <em>only when the type is non-null</em>, and the eager SKIP
     * arm in {@code RuleRunner} only sees <em>declared</em> operations — so a typeless
     * <b>inline</b> dictionary operation used to get no gate at all and evaluated with no provider:
     * every executor arm answered {@code null}, the null broadcast, and the rule false-passed
     * silently. With this guard it never loads cleanly in the first place.
     * </p>
     *
     * <p>
     * ⚠ <b>{@code dictionary_available} is deliberately excluded</b>, mirroring the eager SKIP arm:
     * that operation <em>is</em> the availability gate, its executor arm is total
     * ({@code isAvailable(null)} is plain {@code false}, never a silent null), and the gates this
     * loader itself injects are calls of it — so it can neither false-pass nor be "unanswerable".
     * </p>
     *
     * <p>
     * The shipped corpus is untouched: all 98 {@code rules-src} dictionary rules (417 generated
     * package operations) name a type. This guard protects site/custom rules, and the
     * library-sourced path is wired through {@code LibraryRuleMapper} beside
     * {@link #validateOperationReferences(RulePackage)}.
     * </p>
     *
     * @param pkg
     *            the package to judge in place, may be {@code null}
     */
    static void validateDictionaryOperationTypes(@Nullable RulePackage pkg)
    {
        if (pkg == null || pkg.getRules() == null)
        {
            return;
        }
        for (Rule rule : pkg.getRules().values())
        {
            validateDictionaryOperationTypes(rule);
        }
    }


    /**
     * Per-rule variant of {@link #validateDictionaryOperationTypes(RulePackage)}. Appends to a
     * pre-existing {@code loadError} rather than clobbering it, so an earlier cause keeps its first
     * diagnosis.
     *
     * @param rule
     *            the rule to judge in place, may be {@code null}
     */
    static void validateDictionaryOperationTypes(@Nullable Rule rule)
    {
        if (rule == null)
        {
            return;
        }
        List<String> findings = new ArrayList<>();
        List<Operation> ops = rule.getOperations();
        if (ops != null)
        {
            for (Operation op : ops)
            {
                String id = op == null || op.getId() == null || op.getId().isEmpty() ? "<unnamed>"
                        : op.getId();
                collectTypelessDictionaryOperation(op, "operation " + id, findings);
            }
        }
        // ⚑ Every declared level, plus the Precondition — the same surfaces the wildcard guard
        // walks, for the same reason: an inline operation never reaches rule.getOperations().
        for (CheckCondition level : rule.checkConditions())
        {
            collectInlineTypelessDictionaryOps(level, findings);
        }
        collectInlineTypelessDictionaryOps(rule.getPrecondition(), findings);
        if (findings.isEmpty())
        {
            return;
        }
        String message = "[" + ruleId(rule) + "] " + String.join(", ", findings)
                + ": no installed dictionary can ever satisfy an operation that names no type, so"
                + " the rule is defective — fix the rule by declaring external_dictionary_type;"
                + " installing dictionaries cannot help";
        rule.setLoadError(
                rule.getLoadError() == null ? message : rule.getLoadError() + "; " + message);
    }


    /**
     * Appends a finding when {@code op} is a dictionary-dependent operation (other than the
     * {@code dictionary_available} gate) whose {@code external_dictionary_type} is null or blank.
     */
    private static void collectTypelessDictionaryOperation(@Nullable Operation op, String where,
            List<String> findings)
    {
        if (op == null)
        {
            return;
        }
        net.cumba.cdisc.core.model.OperationType type = op.getOperationType();
        if (type == net.cumba.cdisc.core.model.OperationType.DICTIONARY_AVAILABLE
                || !net.cumba.cdisc.core.exec.OperationExecutor.isDictionaryDependent(type))
        {
            return;
        }
        String dictionaryType = op.getExternalDictionaryType();
        if (dictionaryType == null || dictionaryType.isBlank())
        {
            findings.add(where + " (" + type + ") " + TYPELESS_DICTIONARY_MARKER);
        }
    }


    /** Walks a Check/Precondition tree for operations authored inline in a native expression. */
    private static void collectInlineTypelessDictionaryOps(@Nullable CheckCondition condition,
            List<String> findings)
    {
        if (condition == null)
        {
            return;
        }
        switch (condition)
        {
        case CheckConditionAll all -> all.getConditions()
                .forEach(c -> collectInlineTypelessDictionaryOps(c, findings));
        case CheckConditionAny any -> any.getConditions()
                .forEach(c -> collectInlineTypelessDictionaryOps(c, findings));
        case CheckConditionNot not -> collectInlineTypelessDictionaryOps(not.getCondition(),
                findings);
        case net.cumba.cdisc.core.model.CheckConditionExpression expression -> collectInlineTypelessDictionaryOps(
                expression.expr(), findings);
        case CheckConditionLeaf _,CheckConditionConstant _ ->
        {
            // A legacy leaf carries no inline operation.
        }
        }
    }


    private static void collectInlineTypelessDictionaryOps(net.cumba.cdisc.core.expr.ast.Expr expr,
            List<String> findings)
    {
        if (expr instanceof net.cumba.cdisc.core.expr.ast.Expr.Call call
                && net.cumba.cdisc.core.expr.eval.ExprCompiler.isInlineOperation(call))
        {
            collectTypelessDictionaryOperation(inlineOperationOrNull(call),
                    "inline operation " + call.name() + "(…)", findings);
        }
        childrenOf(expr).forEach(child -> collectInlineTypelessDictionaryOps(child, findings));
    }


    /**
     * Collects every {@code $}-prefixed operand reference in a Check tree, covering both authored
     * shapes ({@link CheckConditionLeaf} and
     * {@link net.cumba.cdisc.core.model.CheckConditionExpression}).
     */
    private static void collectOperandRefs(@Nullable CheckCondition condition,
            java.util.Set<String> out)
    {
        // Handled before the switch rather than as a `case null` arm, matching
        // collectSilencingConsumers: an empty arm in a pattern switch reads to SpotBugs as
        // SF_SWITCH_FALLTHROUGH.
        if (condition == null)
        {
            return;
        }
        switch (condition)
        {
        case CheckConditionAll all -> all.getConditions().forEach(c -> collectOperandRefs(c, out));
        case CheckConditionAny any -> any.getConditions().forEach(c -> collectOperandRefs(c, out));
        case CheckConditionNot not -> collectOperandRefs(not.getCondition(), out);
        case CheckConditionLeaf leaf -> collectOperandRefs(leaf, out);
        case CheckConditionConstant _ ->
        {
            // a constant carries no operand
        }
        case net.cumba.cdisc.core.model.CheckConditionExpression expr -> collectOperandRefs(
                expr.expr(), out);
        }
    }

    /**
     * Operators whose {@code value} operand {@code CheckToExpr} emits as a <b>literal regardless of
     * the {@code value_is_literal} / {@code value_is_reference} flags</b>, so a leading {@code $}
     * there is a character in a substring / pattern / length, never an operation id.
     *
     * <p>
     * Derived from the three routing helpers that bypass {@code CheckToExpr.value}:
     * {@code substringValue} (the contains / starts_with / ends_with family, whose own javadoc says
     * "emitted as a string literal regardless of any flag"), {@code regex} and {@code affixMatches}
     * (the regex family), and {@code lengthValue} (which reads the value with {@code asInt}, so a
     * textual value becomes the number {@code 0}). Without this set a rule matching a literal
     * dollar amount — {@code {"operator": "contains", "value": "$50"}} — would be rejected as a
     * dangling operand.
     * </p>
     */
    private static final java.util.Set<String> LITERAL_VALUE_OPERATORS = java.util.Set.of(
            "contains", "does_not_contain", "contains_case_insensitive",
            "does_not_contain_case_insensitive", "starts_with", "ends_with", "matches_regex",
            "not_matches_regex", "prefix_matches_regex", "not_prefix_matches_regex",
            "suffix_matches_regex", "not_suffix_matches_regex", "has_equal_length",
            "has_not_equal_length");

    /**
     * Leaf-form operand positions: every field {@code CheckToExpr} raises with {@code ref(…)}.
     *
     * <ul>
     * <li>{@code name} and each {@code names} entry — always references.</li>
     * <li>a bare textual {@code value} without {@code value_is_literal}, mirroring
     * {@code CheckToExpr.value} — except for {@link #LITERAL_VALUE_OPERATORS}, which never reach
     * that helper.</li>
     * <li>⚠ each textual element of an <b>array</b> {@code value}. For the function/group family
     * the array does <em>not</em> become a LIST of literals — both of {@code
     * CheckToExpr.functionLeaf}'s arms raise each element as {@code e.isTextual() ? ref(…) :
     * literal(e)}, and {@code ExprCompiler.expandRefKeys} then splices a {@code $}-keyed member out
     * to its column list. Which arm runs depends on the operator: for {@code
     * ExprLowering.UNIQUE_SET_OPERATORS} {@code functionLeaf} inlines the elements itself, into the
     * single list operand the 2026-08-23 grammar requires ({@code f([name, …value])}); every other
     * group operator still routes the array through {@code arrayOperand} into its {@code keys=}
     * kwarg. ⚠ The five worked examples this bullet used to name — {@code CDISC-CG0562},
     * {@code CORE-001034}, {@code FDA-SD1117}, {@code PMDA-SD1117}, {@code PMDA-SD1152} — are all
     * uniqueness carriers authored as {@code Check.expression} today, so none of them reaches this
     * leaf path at all, let alone {@code arrayOperand}; the position is validated for the leaf
     * input shape, not for a shipped rule. Missing it is not a silent PASS but a silent <em>wrong
     * answer</em>: {@code GroupSemantics.uniqueSetViolations} simply drops an unresolvable key
     * column, so the uniqueness set gets coarser and the rule over-reports.</li>
     * <li>{@code within} (raised entry-by-entry by {@code withinOperand}, including nested
     * coalesce-groups) and {@code ordering} ({@code ref(leaf.getOrdering())}). No shipped rule puts
     * a {@code $} there today; they are covered because the engine would resolve one.</li>
     * </ul>
     *
     * <p>
     * ⚠ Both sides matter. {@code CDISC-AD0591}'s third leaf is
     * {@code $current_value != $adsl_value} — a name-position-only walk catches half of it.
     * </p>
     */
    private static void collectOperandRefs(CheckConditionLeaf leaf, java.util.Set<String> out)
    {
        addOperandRef(leaf.getName(), out);
        if (leaf.getNames() != null)
        {
            leaf.getNames().forEach(n -> addOperandRef(n, out));
        }
        JsonNode value = leaf.getValue();
        if (value != null && !Boolean.TRUE.equals(leaf.getValueIsLiteral())
                && !LITERAL_VALUE_OPERATORS.contains(leaf.getOperator()))
        {
            if (value.isTextual())
            {
                addOperandRef(value.asText(), out);
            }
            else if (value.isArray())
            {
                value.forEach(item ->
                {
                    if (item.isTextual())
                    {
                        addOperandRef(item.asText(), out);
                    }
                });
            }
        }
        collectWithinRefs(leaf.getWithin(), out);
        addOperandRef(leaf.getOrdering(), out);
    }


    /**
     * The {@code within} partition operand, which {@code CheckToExpr.withinOperand} raises
     * entry-by-entry via {@code ref(…)}. Polymorphic on the wire — a single column name, a list of
     * them, or a list containing a nested coalesce-group list — so the walk recurses.
     */
    private static void collectWithinRefs(@Nullable JsonNode within, java.util.Set<String> out)
    {
        if (within == null)
        {
            return;
        }
        if (within.isTextual())
        {
            addOperandRef(within.asText(), out);
        }
        else if (within.isArray())
        {
            within.forEach(item -> collectWithinRefs(item, out));
        }
    }


    /**
     * Expression-form operand positions: every {@link net.cumba.cdisc.core.expr.ast.Expr.Ref}
     * reachable from {@code expr}.
     */
    private static void collectOperandRefs(net.cumba.cdisc.core.expr.ast.Expr expr,
            java.util.Set<String> out)
    {
        if (expr instanceof net.cumba.cdisc.core.expr.ast.Expr.Ref ref)
        {
            addOperandRef(ref.name(), out);
        }
        // A `$` inside an Expr.Lit is a string character, not a reference, and childrenOf only
        // descends into a LIST literal's items — which are themselves literals.
        childrenOf(expr).forEach(child -> collectOperandRefs(child, out));
    }


    /**
     * Records {@code name} as an operand reference when it carries the {@code $} sigil the
     * evaluation context is keyed by. The test is exactly {@code ExprCompiler.nameRefPlan}'s
     * ({@code name.startsWith("$")}), so this pass judges precisely the names that path resolves
     * against {@code ctx.getVariables()} — and no others. The one deliberate subtraction is the Fix
     * #37 operand-substitution template {@code ${VAR[:fmt]}} / {@code ${*}}, which
     * {@link OperandSubstitutor} owns and {@link #validateOperandSubstitution} already validates:
     * every shipped occurrence sits mid-name ({@code ADSL.TRT${APERIOD:%02d}P}) so the sigil test
     * excludes it anyway, but a leading one would be a template too, never an operation id.
     */
    private static void addOperandRef(@Nullable String name, java.util.Set<String> out)
    {
        if (name != null && name.length() > 1 && name.charAt(0) == '$' && name.charAt(1) != '{')
        {
            out.add(name);
        }
    }


    /**
     * {@code Sensitivity: "Study"} together with a restricted {@code Scope} is an authoring
     * contradiction, and is recorded as a load error so the rule reports ERROR loudly.
     *
     * <p>
     * A study-level finding describes the submission and has no dataset to attach to. A rule that
     * also declares a dataset scope is therefore saying two incompatible things: that its finding
     * belongs to the study, and that it only applies to certain datasets. In practice such a rule
     * is a dataset rule whose {@code Sensitivity} is wrong — the finding belongs to the dataset the
     * scope names. Failing loud mirrors the loader's existing behaviour for an unrecognised
     * {@code Sensitivity} value, and stops the rule quietly falling back to the per-dataset path
     * where the contradiction would be invisible.
     * </p>
     */
    private static void checkStudySensitivityScope(Rule rule, List<String> errors)
    {
        if (rule.getSensitivity() != Sensitivity.STUDY)
        {
            return;
        }
        if (!net.cumba.cdisc.core.exec.StudyRuleClassifier.hasUnrestrictedScope(rule))
        {
            errors.add("Sensitivity `Study` requires an unrestricted Scope (Domains.Include [ALL]"
                    + " with no Classes / Data_Structures / Subclasses / Datasets facet, no"
                    + " Requirements.Variables All / Any / None facet, and no Domains.Exclude): a"
                    + " study-level finding has no dataset to attach to, so a scoped rule must"
                    + " declare a dataset-level Sensitivity instead");
        }
    }


    /**
     * Fills in {@code Sensitivity} for every rule that omits it
     * ({@code PLAN-derive-rule-type-sensitivity} phase 6; the {@code Rule_Type} half died with the
     * field, {@code PLAN-leaf-scope-domain-inference.md} phase 7).
     *
     * <p>
     * The field is recoverable from the rest of the rule body, so {@code rules-src} and the native
     * {@code rules/} corpus no longer carry it. <strong>An authored value always wins:</strong>
     * derivation runs only where the field is absent, so an explicit value is never second-guessed.
     * It runs <em>after</em> {@link #validateEnumFields} so an unrecognised authored value is still
     * reported as itself rather than being silently replaced.
     * </p>
     */
    private static void deriveOmittedFields(RulePackage pkg)
    {
        if (pkg.getRules() == null)
        {
            return;
        }
        // One INFO line per package load, not one per rule per load. The per-rule detail is at
        // DEBUG (see logAppliedLikely). Ruling Q1(b) asks that "a run shows which rules execute on
        // a heuristic rather than a certain classification" — that is served once per run; the
        // per-load repetition was an artefact of the corpus being re-loaded once per test class
        // (measured: 128 loads in one module, ~98% of a 48 MB gate log).
        Map<String, Integer> likely = new LinkedHashMap<>();
        pkg.getRules().values().forEach(rule -> deriveOmittedFields(rule, likely));
        if (!likely.isEmpty())
        {
            // No package identifier is available to name here: RulePackage models only `rules`
            // and `unknownKeys`, with no name/id of its own.
            LOGGER.log(System.Logger.Level.INFO,
                    "{0} rule(s) applied a LIKELY derivation: {1} (per-rule detail at DEBUG)",
                    likely.values().stream().mapToInt(Integer::intValue).sum(), likely);
        }
    }


    /**
     * Per-rule variant of {@link #deriveOmittedFields(RulePackage)}, also applied by
     * {@link LibraryRuleMapper} so CDISC-Library-sourced rules derive identically.
     *
     * <p>
     * Public because the corpus no longer carries these fields: anything that binds a {@link Rule}
     * outside this loader — a tool, a probe, an editor preview — must complete it the same way, or
     * it will evaluate a rule the engine would collapse differently. Idempotent, and a no-op on a
     * rule that already carries the field or that failed validation.
     * </p>
     *
     * @param rule
     *            the rule to complete in place, may be {@code null}
     */
    public static void deriveOmittedFields(@Nullable Rule rule)
    {
        deriveOmittedFields(rule, null);
    }


    /**
     * {@link #deriveOmittedFields(Rule)}, additionally tallying applied {@code LIKELY} derivations
     * into {@code likelyTally} (field &#8594; count) so the package-level caller can emit
     * <b>one</b> summary line for the whole load. {@code null} when there is no load to summarise —
     * the public single-rule entry point above, used by the generators.
     *
     * @param rule
     *            the rule to fill in, or {@code null}
     * @param likelyTally
     *            the per-load tally to add to, or {@code null} to tally nothing
     */
    private static void deriveOmittedFields(@Nullable Rule rule,
            @Nullable Map<String, Integer> likelyTally)
    {
        if (rule == null || rule.getLoadError() != null)
        {
            return;
        }
        Map<String, String> rationale = new LinkedHashMap<>();
        // An unparseable authored value also leaves the typed field null, and that must stay an
        // error rather than being quietly replaced — so the raw field decides whether anything was
        // authored at all.
        if (rule.getSensitivity() == null && rule.getRawSensitivity() == null)
        {
            RuleClassifier.Derived<Sensitivity> derived = RuleClassifier.deriveSensitivity(rule);
            if (derived.value() != null && derived.confidence() != RuleClassifier.Confidence.NONE)
            {
                rule.setSensitivity(derived.value());
                rationale.put("Sensitivity", derived.rationale());
                logAppliedLikely(rule, "Sensitivity", derived.value().getJsonValue(),
                        derived.confidence(), derived.rationale(), likelyTally);
            }
        }
        else
        {
            logDerivationDisagreement(rule, "Sensitivity",
                    rule.getSensitivity() == null ? null : rule.getSensitivity().getJsonValue(),
                    describeDerived(RuleClassifier.deriveSensitivity(rule)));
        }
        if (!rationale.isEmpty())
        {
            rule.setDerivationRationale(rationale);
        }
    }


    /**
     * Q1(a) of {@code PLAN-derive-rule-type-sensitivity} (decided 2026-07-30): when a rule carries
     * an <em>authored</em> value and the derivation disagrees, say so at {@code INFO}. The explicit
     * value wins (decision D2) — this line exists so an author can consciously <em>disagree with
     * the derivation</em> (the documented override workflow) and see that the disagreement is
     * intentional, which is why it is not a WARNING.
     */
    private static void logDerivationDisagreement(Rule rule, String field,
            @Nullable String authored, @Nullable String derived)
    {
        if (authored == null || derived == null || authored.equals(derived))
        {
            return;
        }
        LOGGER.log(System.Logger.Level.INFO,
                // ''{2}'' / ''{3}'' — see logAppliedLikely: the single-quoted form lost BOTH
                // values, so the line whose whole job is to show which two values disagree showed
                // neither.
                "[{0}] authored {1} ''{2}'' overrides the derivation ''{3}'' (explicit value wins)",
                ruleId(rule), field, authored, derived);
    }


    /**
     * Q1(b): an applied {@code LIKELY}-confidence derivation is visible at {@code INFO}, so a run
     * shows which rules execute on a heuristic rather than a certain classification.
     */
    private static void logAppliedLikely(Rule rule, String field, String value,
            RuleClassifier.Confidence confidence, String rationale,
            @Nullable Map<String, Integer> likelyTally)
    {
        if (confidence != RuleClassifier.Confidence.LIKELY)
        {
            return;
        }
        if (likelyTally != null)
        {
            likelyTally.merge(field, 1, Integer::sum);
        }
        // ''{2}'' — NOT '{2}'. System.Logger formats through java.text.MessageFormat, where a
        // SINGLE quote opens a quoted literal: "'{2}'" renders as the text {2} and the value is
        // never printed. This line emitted a literal "{2}" for its entire life. The doubled form
        // is the codebase's own idiom (see the ''{1}'' uses elsewhere).
        LOGGER.log(System.Logger.Level.DEBUG, "[{0}] derived {1} ''{2}'' (LIKELY: {3})",
                ruleId(rule), field, value, rationale);
    }


    /** The derived value's json text, or {@code null} when the derivation abstained. */
    private static @Nullable String describeDerived(RuleClassifier.Derived<?> derived)
    {
        Object v = derived.value();
        return switch (v)
        {
        case Sensitivity sv -> sv.getJsonValue();
        case null, default -> null;
        };
    }

    /**
     * EC-37 kill-switch for the Output_Variables derivation ({@code PLAN-auto-output-variables}).
     * Default ON; a production escape hatch, not a staged rollout. Read per call rather than
     * latched in a constant so an operator can flip it on a live run — the same contract as
     * {@code corej.studyAnchorPass} ({@code LibraryValidator}).
     */
    private static final String AUTO_OV_PROPERTY = "corej.autoOutputVariables";

    private static boolean autoOutputVariablesEnabled()
    {
        return !"false".equalsIgnoreCase(System.getProperty(AUTO_OV_PROPERTY));
    }


    private static void deriveOutputVariables(RulePackage pkg)
    {
        if (pkg.getRules() == null)
        {
            return;
        }
        for (Rule rule : pkg.getRules().values())
        {
            deriveOutputVariables(rule);
        }
    }


    /**
     * Installs the effective Output_Variables ({@code OutputVariableDeriver#derive}) on
     * {@code rule}'s runtime-only field and records the derived delta in the
     * {@code derivationRationale} under the key {@code "Output_Variables"} (EC-37). Runs after
     * {@link #installNativeExpr} wherever a rule is loaded, expanded or generated; a no-op when the
     * rule is {@code null}, failed to load, or {@code -Dcorej.autoOutputVariables=false}.
     */
    public static void deriveOutputVariables(@Nullable Rule rule)
    {
        if (rule == null || rule.getLoadError() != null)
        {
            return;
        }
        // E-3 runs whether or not the derivation is enabled: a malformed or contradictory
        // exclusion token is an authoring error, not a derivation artefact, and the kill-switch
        // fallback (Rule#effectiveOutputVariablesOrAuthored) applies the same tokens.
        validateOutputVariableExclusions(rule);
        if (rule.getLoadError() != null || !autoOutputVariablesEnabled())
        {
            return;
        }
        List<String> effective = OutputVariableDeriver.derive(rule);
        rule.setEffectiveOutputVariables(effective);
        rule.setExcludedOutputVariables(OutputVariableDeriver.excludedOf(rule));
        List<String> authored = rule.getOutcome() != null
                ? net.cumba.cdisc.core.model.OutputVariableToken
                        .includes(rule.getOutcome().getOutputVariables())
                : List.of();
        List<String> delta = new ArrayList<>(effective);
        delta.removeAll(authored);
        if (!delta.isEmpty())
        {
            // Copy defensively: deriveOmittedFields always installs a fresh LinkedHashMap
            // today, but this pass must stay correct if a future writer stores an
            // immutable map.
            Map<String, String> rationale = rule.getDerivationRationale() != null
                    ? new LinkedHashMap<>(rule.getDerivationRationale())
                    : new LinkedHashMap<>();
            rationale.put("Output_Variables", "derived: " + String.join(", ", delta));
            rule.setDerivationRationale(rationale);
        }
    }


    /**
     * E-3 of {@code PLAN-authoring-grammar-unique-set-and-output-exclusion} — the load-time
     * validation of {@code !X} exclusion tokens in {@code Outcome.Output_Variables}
     * ({@link net.cumba.cdisc.core.model.OutputVariableToken}). Five checks, every one a
     * {@code loadError} appended to any earlier diagnosis (the {@link #validateOperationReferences}
     * channel):
     * <ol>
     * <li>{@code !X} must name something the rule <em>derives</em> — {@code X} must be in
     * {@link OutputVariableDeriver#derivedSet}, the set the derivation would add. An exclusion's
     * whole purpose is to suppress an auto-derived entry (ruling 5), so {@code !X} for an {@code X}
     * the rule never derives names nothing — the typo case. This is also what makes
     * {@code !variable_name} legal exactly on the rules where {@code variable_name} is
     * derived.</li>
     * <li>{@code X} authored and {@code !X} authored is a contradiction, not a precedence
     * puzzle.</li>
     * <li>{@code !X} twice is tolerated (R-9.10 dedups).</li>
     * <li>{@code !X} with {@code X} a location variable ({@code USUBJID}, {@code ASEQ},
     * {@code --SEQ}) is rejected: those ride out-of-band and are re-injected by the report builder,
     * so the projection cannot withhold them. Judged by
     * {@link OutputVariableDeriver#isLocationVariable(Rule, String)}, which also resolves
     * {@code --SEQ} against the rule's pinned domains — otherwise the per-domain expansion's
     * substituted {@code !LBSEQ} would evade the check on the way out ({@code Fix #356}).</li>
     * <li>A bare {@code !} or a stacked {@code !!X} is malformed.</li>
     * </ol>
     * Plain include entries stay unvalidated (a separate decision — plan §7 finding 5).
     */
    static void validateOutputVariableExclusions(@Nullable Rule rule)
    {
        if (rule == null || rule.getOutcome() == null
                || rule.getOutcome().getOutputVariables() == null)
        {
            return;
        }
        List<String> authored = rule.getOutcome().getOutputVariables();
        List<String> errors = new ArrayList<>();
        java.util.Set<String> includes = new java.util.HashSet<>(
                net.cumba.cdisc.core.model.OutputVariableToken.includes(authored));
        java.util.Set<String> derived = null;
        for (String entry : authored)
        {
            if (!net.cumba.cdisc.core.model.OutputVariableToken.isExclusion(entry))
            {
                continue;
            }
            String malformed = net.cumba.cdisc.core.model.OutputVariableToken.malformed(entry);
            if (malformed != null)
            {
                errors.add(malformed);
                continue;
            }
            String name = net.cumba.cdisc.core.model.OutputVariableToken.name(entry);
            // Fix #356: judged against the rule's pinned domains too, so the per-domain
            // expansion's substituted spelling (`!--SEQ` -> `!LBSEQ`) is the same load error as
            // the authored one — the verbatim set test alone only catches the pre-expansion form.
            if (OutputVariableDeriver.isLocationVariable(rule, name))
            {
                errors.add("!" + name + " cannot exclude a location variable (" + name
                        + " is attached to every finding out-of-band, not by the projection)");
                continue;
            }
            if (includes.contains(name))
            {
                errors.add(name + " is both authored and excluded (!" + name
                        + ") — remove one of the two");
                continue;
            }
            if (derived == null)
            {
                derived = OutputVariableDeriver.derivedSet(rule);
            }
            if (!derived.contains(name))
            {
                errors.add("!" + name + " names nothing the rule derives"
                        + " — an exclusion suppresses an auto-derived entry and " + name
                        + " would never be derived here");
            }
        }
        if (errors.isEmpty())
        {
            return;
        }
        String message = "[" + ruleId(rule) + "] Outcome.Output_Variables: "
                + String.join("; ", errors);
        rule.setLoadError(
                rule.getLoadError() == null ? message : rule.getLoadError() + "; " + message);
    }


    /**
     * Gate 3b — <b>{@code Grouping_Variables} ⟺ {@code Sensitivity: Group}</b>
     * ({@code PLAN-derive-rule-type-sensitivity} §2.4).
     *
     * <p>
     * The two fields are one decision expressed twice, so any disagreement is an authoring
     * contradiction. Declaring {@code Group} without grouping variables leaves Java falling through
     * to the ungrouped path silently while Python reports a configuration error; declaring grouping
     * variables under any other {@code Sensitivity} means the explicit value would override the
     * derivation and quietly discard the grouping. Both shapes are absent from the corpus (38/38
     * agree), so this gate is green on day one and exists to keep it that way once the field is
     * dropped from {@code rules-src} and {@code Group} becomes a derived value.
     * </p>
     */
    private static void checkGroupSensitivityConsistency(Rule rule, List<String> errors)
    {
        checkGroupingShape(rule, errors);
        List<String> grouping = rule.effectiveGroupingVariables();
        boolean hasGrouping = grouping != null && !grouping.isEmpty();
        if (rule.getSensitivity() == Sensitivity.GROUP && !hasGrouping)
        {
            errors.add("[" + ruleId(rule) + "] Sensitivity `Group` requires a non-empty"
                    + " Grouping_Variables: without it Java silently evaluates the rule ungrouped"
                    + " and Python reports a configuration error");
        }
        if (hasGrouping && rule.getSensitivity() != null
                && rule.getSensitivity() != Sensitivity.GROUP)
        {
            errors.add("[" + ruleId(rule) + "] Grouping_Variables " + grouping
                    + " requires Sensitivity `Group`, not `" + rule.getSensitivity().getJsonValue()
                    + "`: any other value discards the grouping and reports at the wrong"
                    + " granularity");
        }
    }


    /**
     * Rejects a malformed rule-level grouping declaration. The engine accepts <b>both</b> the flat
     * {@code Grouping_Variables:} and the {@code Grouping: { Variables:, keep_missings: }} block so
     * the corpus is never ahead of the engine during the migration, but two things are always
     * errors:
     *
     * <ol>
     * <li><b>declaring both shapes</b> — {@code effectiveGroupingVariables()} would silently prefer
     * the block and discard the flat list, so a rule whose two lists disagree would run on a key
     * its author never sees;</li>
     * <li><b>a block carrying {@code keep_missings} with no {@code Variables}</b> — a grouping-key
     * disposition with no grouping key. The nesting was chosen to make this state unrepresentable,
     * but YAML can still express it, so it is rejected rather than ignored.</li>
     * </ol>
     */
    private static void checkGroupingShape(Rule rule, List<String> errors)
    {
        net.cumba.cdisc.core.model.GroupingSpec block = rule.getGrouping();
        if (block == null)
        {
            return;
        }
        boolean blockHasVars = block.getVariables() != null && !block.getVariables().isEmpty();
        if (rule.getGroupingVariables() != null && !rule.getGroupingVariables().isEmpty())
        {
            errors.add("[" + ruleId(rule) + "] declares both `Grouping:` and the flat"
                    + " `Grouping_Variables:`; use one — the block wins, so the flat list would be"
                    + " silently discarded");
        }
        if (!blockHasVars && block.getKeepMissings() != null)
        {
            errors.add("[" + ruleId(rule) + "] `Grouping.keep_missings` requires a non-empty"
                    + " `Grouping.Variables`: a grouping-key disposition with no grouping key has no"
                    + " effect");
        }
    }


    /**
     * The migration guidance for a rule that still carries {@code Rule_Type} (owner ruling 6): drop
     * the field; declare {@code Variable_Universe: Define} iff the rule needs the Define-XML
     * ItemDef universe — the old {@code Define Item Metadata Check against Library Metadata}
     * semantics, the single non-derivable bit the taxonomy carried.
     */
    static String ruleTypeRejection(Rule rule)
    {
        return "[" + ruleId(rule) + "] Rule_Type '" + rule.getRejectedRuleType()
                + "' is no longer a rule field — the engine infers the evaluation domain from the"
                + " Check (PLAN-leaf-scope-domain-inference.md). Drop the field — if the rule"
                + " iterated the Define-XML ItemDefs (the former 'Define Item Metadata Check"
                + " against Library Metadata'), declare Variable_Universe: \"Define\" instead";
    }


    /**
     * The present-but-invalid gate for a rule's {@code Severity} (Plan C ruling 1).
     *
     * <p>
     * ⚠ It cannot reuse {@link #checkEnumField} because "parsed to a constant" is not the same as
     * "legal on a rule": {@code NOTICE} parses and is still not authorable (see
     * {@link #RULE_SEVERITIES}).
     * </p>
     *
     * <p>
     * ⚑ An authored {@code Severity: "Error"} is <b>valid and loads</b> — it is merely
     * non-canonical, and {@code RuleCanonicalizer} strips it. Rejecting it here would make the
     * field's default asymmetric with {@code Sensitivity}, which the corpus does carry explicitly
     * in places. Canonicalisation is not a load concern.
     * </p>
     */
    private static void checkSeverityField(Rule rule, List<String> errors)
    {
        String raw = rule.getRawSeverity();
        if (raw != null && !RULE_SEVERITIES.contains(rule.getSeverity()))
        {
            errors.add("[" + ruleId(rule) + "] Invalid Severity '" + raw + "' — expected one of: "
                    + SEVERITY_VALUES);
        }
    }


    /**
     * Plan C &#167;3.3 — the level-keyed {@code Check} gates, all three of them.
     *
     * <ol>
     * <li>A &#167;3.3 <b>grammar violation</b> the Jackson binding carried rather than threw (a
     * mixed map, an unknown level name, a non-string {@code Message}). Reported here, and only
     * here, because this is the first place that knows which rule it was — exactly the
     * {@code rawSeverity} arrangement.</li>
     * <li>The <b>strictest declared level must equal the rule's {@code Severity}</b>. A rule
     * declaring {@code {ERROR, INFO}} under {@code Severity: "Warning"} says two different things
     * about how bad its worst finding is, and the engine would believe the level while every
     * catalogue and export believed the field.</li>
     * <li>A <b>per-rule run threshold</b> is rejected outright: a rule declares which levels
     * <em>exist</em>, never which ones <em>run</em> ({@link #THRESHOLD_KEYS}).</li>
     * </ol>
     */
    private static void checkCheckLevels(Rule rule, List<String> errors)
    {
        String grammar = rule.getRawCheckLevels();
        if (grammar != null)
        {
            errors.add("[" + ruleId(rule) + "] " + grammar);
        }
        var levels = rule.getCheckLevels();
        if (levels != null && !levels.isEmpty())
        {
            Severity strictest = levels.firstEntry().getKey();
            if (strictest != rule.effectiveSeverity())
            {
                errors.add("[" + ruleId(rule) + "] Check declares " + strictest
                        + " as its strictest level but the rule's Severity is "
                        + rule.effectiveSeverity().getJsonValue()
                        + " — Severity is the highest level a rule declares, and the two cannot"
                        + " disagree");
            }
        }
        for (String key : rule.getUnknownKeys())
        {
            if (THRESHOLD_KEYS.contains(key))
            {
                errors.add("[" + ruleId(rule) + "] '" + key
                        + "' is not a rule field — the severity threshold is a RUN option"
                        + " (--severity-level / CheckRunRequest.severityThreshold / #runLevel)."
                        + " A rule declares which levels exist, never which levels run");
            }
        }
    }


    private static void checkEnumField(Rule rule, @Nullable String raw, @Nullable Object parsed,
            String field, String expected, List<String> errors)
    {
        if (raw != null && parsed == null)
        {
            errors.add("[" + ruleId(rule) + "] Invalid " + field + " '" + raw
                    + "' — expected one of: " + expected);
        }
    }


    /**
     * {@code Fix #236} — the same "present but unrecognised" gate for every
     * {@code Match_Datasets[].Join_Type}.
     *
     * <p>
     * ⚠⚠ <b>Why this is a gate rather than a fallback.</b> The engine's only value comparison is a
     * <em>negation</em> — {@code KeyMatchRowExpander}'s
     * {@code !"inner".equalsIgnoreCase(getJoinType())} — so <b>every</b> value that is not
     * {@code inner} runs as a <b>left</b> join. An authored typo, a case error or a value from
     * another vocabulary ({@code outer}, {@code full}, {@code right}) therefore produced
     * plausible-but-wrong rows and reported nothing at all. Filing a {@code loadError} makes the
     * rule report ERROR through the {@code RuleRunner.execute} sentinel — the same channel a
     * malformed operation expression uses — rather than failing the whole load.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Absence is NOT a violation and must never become one.</b> {@code null} / blank means
     * "not authored": {@link #normalizeJoinTypes(Rule)} stamps {@code inner} onto it a few passes
     * later, and {@code RuleGenerator} — which never calls that method — relies on the null
     * surviving, because a null {@code Join_Type} is what keeps {@code RuleCohortGrouper}'s
     * equality-cohort path reachable for the generated {@code CDISC-AD0591-<domain>-<var>} family
     * ({@code Fix #233} / EC-74). Rejecting {@code null} here would kill that optimisation
     * outright.
     * </p>
     *
     * <p>
     * ⚑ Runs on the <b>authored</b> value: {@code validateEnumFields} is sequenced before
     * {@code normalizeJoinTypes} in {@link #finishLoad}, so the value judged here is the one the
     * author wrote, not the stamped default.
     * </p>
     *
     * <p>
     * ✅ Costs nothing on today's corpus: parsing every {@code rules/} package finds exactly two
     * states — {@code "left"} (85 entries across 18 distinct rules) and absent (895 entries). There
     * is no third value. ⚠ Re-measured 2026-08-17 after {@code D-TA-2} / {@code D-TA-6a}; the
     * figures were 158 / 38 / 832 while the over-firing left joins were still authored. ⚠ The
     * absent leg moved 899 → 895 on 2026-08-18 (twin-alignment wave TA-4, ruling {@code CL-E.2}):
     * {@code PMDA-SD1143} dropped its {@code Match_Datasets} block and it ships into four packages.
     * The {@code "left"} leg did not move.
     * </p>
     */
    private static void checkJoinTypes(Rule rule, List<String> errors)
    {
        List<net.cumba.cdisc.core.model.MatchDataset> matches = rule.getMatchDatasets();
        if (matches == null)
        {
            return;
        }
        for (net.cumba.cdisc.core.model.MatchDataset md : matches)
        {
            if (md == null)
            {
                continue;
            }
            String raw = md.getJoinType();
            if (net.cumba.cdisc.core.model.JoinType.isAbsent(raw)
                    || net.cumba.cdisc.core.model.JoinType.fromJson(raw) != null)
            {
                continue;
            }
            errors.add("[" + ruleId(rule) + "] Invalid Join_Type '" + raw
                    + "' on Match_Datasets entry '" + md.getName() + "' — expected one of: "
                    + JOIN_TYPE_VALUES);
        }
    }


    /**
     * Guard-residual disposition ({@code PLAN-native-runtime-guard-residual}, user decision
     * 2026-06-12, option c), re-grounded by phase 6 of {@code PLAN-leaf-scope-domain-inference.md}:
     * a {@code library_dataset_*} / {@code define_dataset_*} operand is resolvable only through the
     * native {@code ds_*} accessor it canonicalises to
     * ({@link net.cumba.cdisc.core.expr.MetadataOperandMapping}); the phase-2a2 injection that once
     * served the bare name on one rule type is gone. An operand no accessor serves can never mean
     * what its author intended — tag it with a load error, mirroring the P1b
     * non-executable-operator precedent; the {@code ds_*} form carries the proper
     * SKIPPED-when-provider-absent contract on every rule. Checked on the NAME side of Check and
     * Precondition leaves; textual VALUE sides follow the universal var-or-literal resolution
     * contract and stay valid.
     */
    private static void validateDatasetProviderOperands(Rule rule, List<String> errors)
    {
        // Phase 6 of PLAN-leaf-scope-domain-inference.md: the former "only on the
        // dataset-metadata family" gate is gone — every rule canonicalises a library_dataset_* /
        // define_dataset_* operand to its ds_*("LIBRARY" / "DEFINE") accessor. What remains is the
        // error on an operand NO accessor serves.
        // ⚑ Plan C §3.3: every declared level.
        for (CheckCondition level : rule.checkConditions())
        {
            collectDatasetProviderOperands(level, "Check", errors, ruleId(rule));
        }
        if (rule.getPrecondition() != null)
        {
            collectDatasetProviderOperands(rule.getPrecondition(), "Precondition", errors,
                    ruleId(rule));
        }
    }


    private static void collectDatasetProviderOperands(CheckCondition condition, String path,
            List<String> errors, String ruleId)
    {
        if (condition == null)
        {
            return;
        }
        switch (condition)
        {
        case CheckConditionAll all ->
        {
            for (int i = 0; i < all.getConditions().size(); i++)
            {
                collectDatasetProviderOperands(all.getConditions().get(i), path + ".all[" + i + "]",
                        errors, ruleId);
            }
        }
        case CheckConditionAny any ->
        {
            for (int i = 0; i < any.getConditions().size(); i++)
            {
                collectDatasetProviderOperands(any.getConditions().get(i), path + ".any[" + i + "]",
                        errors, ruleId);
            }
        }
        case CheckConditionNot not -> collectDatasetProviderOperands(not.getCondition(),
                path + ".not", errors, ruleId);
        case CheckConditionLeaf leaf ->
        {
            String name = leaf.getName();
            if (name != null
                    && (name.startsWith("library_dataset_") || name.startsWith("define_dataset_"))
                    && net.cumba.cdisc.core.expr.MetadataOperandMapping
                            .forwardOperand(name) == null)
            {
                errors.add("[" + ruleId + "] " + path + " uses operand '" + name
                        + "', which no ds_* accessor serves (previously a silent empty-string"
                        + " comparison) — name a dataset attribute the accessors provide");
            }
        }
        case CheckConditionConstant _ ->
        {
            // constants carry no operand
        }
        case net.cumba.cdisc.core.model.CheckConditionExpression _ ->
        {
            // native-only expression — dataset provider metadata is read via the ds_* accessors,
            // which are valid on every eligible type (compiler-validated)
        }
        }
    }


    /**
     * Fix #24: walks the rule's {@code wildcards} map and tags the rule with a load error for any
     * group key that doesn't appear as a captured marker ({@code xx}, {@code zz}, {@code y},
     * {@code w}, or {@code *}) in at least one leaf wildcard name. Filter values themselves are
     * validated by Jackson on parse.
     */
    private static void validateWildcardFilters(Rule rule, List<String> errors)
    {
        validateWildcardPairDirectives(rule, errors);
        Map<String, net.cumba.cdisc.core.model.WildcardFilter> filters = rule.getWildcards();
        if (filters == null || filters.isEmpty())
        {
            return;
        }
        if (rule.getCheck() == null)
        {
            errors.add("wildcards declared on rule with no Check tree");
            return;
        }
        // ⚑ Plan C §3.3: a capture group declared in any level is available to the filter.
        java.util.Set<String> availableGroups = new java.util.LinkedHashSet<>();
        for (CheckCondition level : rule.checkConditions())
        {
            availableGroups.addAll(
                    net.cumba.cdisc.core.gen.WildcardExpander.collectAvailableCaptureGroups(level));
        }
        for (String key : filters.keySet())
        {
            if (!availableGroups.contains(key))
            {
                errors.add("wildcards group '" + key
                        + "' not present in any Check leaf wildcard (available: " + availableGroups
                        + ")");
            }
        }
    }


    /**
     * Fix #84 (Group B / B4): validates the empty-suffix wildcard pairing directives
     * {@code wildcardExclude} and {@code wildcardPairCatalogue}. Both are only meaningful on a
     * bare-{@code *} / {@code *N} / {@code *C} pairing template, so a rule that declares either but
     * carries no Check tree, or declares the catalogue without both a bare {@code *} primary and a
     * {@code *N}/{@code *C} secondary leaf, is tagged with a load error. Blank exclusion entries
     * are rejected (they would compile to a match-everything pattern).
     */
    private static void validateWildcardPairDirectives(Rule rule, List<String> errors)
    {
        List<String> exclude = rule.getWildcardExclude();
        boolean useCatalogue = Boolean.TRUE.equals(rule.getWildcardPairCatalogue());
        if ((exclude == null || exclude.isEmpty()) && !useCatalogue)
        {
            return;
        }
        if (rule.getCheck() == null)
        {
            errors.add("wildcardExclude/wildcardPairCatalogue declared on rule with no Check tree");
            return;
        }
        if (exclude != null)
        {
            for (String entry : exclude)
            {
                if (entry == null || entry.isBlank())
                {
                    errors.add("wildcardExclude contains a null/blank entry");
                }
            }
        }
        if (useCatalogue)
        {
            java.util.Set<String> names = net.cumba.cdisc.core.gen.WildcardExpander
                    .collectWildcardNames(rule);
            boolean bareStar = names.contains("*");
            boolean anchor = names.contains("*N") || names.contains("*C");
            if (!bareStar || !anchor)
            {
                errors.add("wildcardPairCatalogue requires a bare '*' primary leaf and a '*N'/'*C'"
                        + " secondary leaf (present: " + names + ")");
            }
        }
    }

    /** Valid {@code Expansion[].over} values, in declaration order, for the load-error message. */
    private static final String EXPANSION_SOURCE_VALUES = java.util.stream.Stream
            .of(net.cumba.cdisc.core.model.ExpansionSource.values())
            .map(net.cumba.cdisc.core.model.ExpansionSource::getJsonValue)
            .collect(java.util.stream.Collectors.joining(", "));

    /**
     * Fix #147: validates a rule's {@code Expansion:} block. Every rejection here is a shape whose
     * failure mode would otherwise be <b>silent</b> — the rule loads, the gate stays green and the
     * check tests nothing, which is exactly how {@code CDISC-AD0591}/{@code CDISC-AD0898} shipped
     * as no-ops for months.
     *
     * <ul>
     * <li><b>token present and sigil-bearing.</b> CDISC variable names are {@code [A-Z][A-Z0-9]*};
     * a token drawn from that alphabet would substitute inside a real column name.</li>
     * <li><b>no token is a prefix of another.</b> Substitution is ordered longest-first so it is
     * deterministic, but two tokens where one contains the other are an authoring slip whose result
     * depends on that ordering — reject rather than resolve.</li>
     * <li><b>{@code over:} present and recognised.</b> An unknown source would otherwise drop the
     * directive and leave the token unsubstituted.</li>
     * <li><b>the source's own operand present</b> ({@code with:} / {@code pattern:}), and the
     * pattern actually contains the token.</li>
     * <li><b>no token in {@code Scope.Variables}.</b> Scope is evaluated BEFORE expansion
     * ({@code RuleGenerator} calls {@code describeScopeSkip} then {@code tryExpand}), so the
     * matcher sees the template and would test the token text literally — no such column, rule
     * skipped for every dataset, never expanded. R-4.9 is deliberately left untouched.</li>
     * <li><b>no engine-owned wildcard markers in the same Check.</b> The two expansion mechanisms
     * are independent walks; combining them on one rule is unimplemented, so reject it at load
     * instead of expanding over one and silently ignoring the other.</li>
     * </ul>
     */
    private static void validateExpansionDirectives(Rule rule, List<String> errors)
    {
        List<net.cumba.cdisc.core.model.ExpansionDirective> directives = rule.getExpansion();
        if (directives == null || directives.isEmpty())
        {
            return;
        }
        if (rule.getCheck() == null)
        {
            errors.add("Expansion declared on rule with no Check tree");
            return;
        }
        List<String> tokens = new ArrayList<>();
        for (net.cumba.cdisc.core.model.ExpansionDirective d : directives)
        {
            validateExpansionDirective(d, tokens, errors);
        }
        for (int i = 0; i < tokens.size(); i++)
        {
            for (int j = 0; j < tokens.size(); j++)
            {
                if (i != j && tokens.get(i).contains(tokens.get(j)))
                {
                    errors.add("Expansion token '" + tokens.get(j) + "' occurs inside token '"
                            + tokens.get(i) + "' — substitution order would decide the result");
                }
            }
        }
        validateNoExpansionTokenInScope(rule, tokens, errors);
        // ⚑ Plan C §3.3: an engine-owned marker in ANY level collides with the Expansion walk.
        if (rule.checkConditions().stream()
                .anyMatch(net.cumba.cdisc.core.gen.WildcardExpander::hasRealWildcards))
        {
            errors.add("Expansion cannot be combined with the engine-owned wildcard markers"
                    + " (xx/zz/y/w/*) in the same Check — the two expansions are independent walks");
        }
        // The `wildcard*` directives all steer the OTHER mechanism, and RuleGenerator's
        // applyTemplatePostFilters reads them by splitting an expanded id at the FIRST '-' after
        // the base id — which is wrong for a multi-directive token expansion (`X-AGE-AESEQ` yields
        // the "column" `AGE-AESEQ`). Rejecting the combination closes that latent wrong answer
        // instead of leaving it to be discovered by whoever first uses two directives.
        // ⚑ `suffixExclusions` / `requireAllWildcardsInDataset` left this list with Fix #366: the
        // fields are gone from the model, so no rule can carry either any more.
        List<String> wildcardOnly = new ArrayList<>();
        if (rule.getWildcards() != null && !rule.getWildcards().isEmpty())
        {
            wildcardOnly.add("wildcards");
        }
        if (rule.getWildcardExclude() != null && !rule.getWildcardExclude().isEmpty())
        {
            wildcardOnly.add("wildcardExclude");
        }
        if (Boolean.TRUE.equals(rule.getWildcardPairCatalogue()))
        {
            wildcardOnly.add("wildcardPairCatalogue");
        }
        if (Boolean.TRUE.equals(rule.getSkipIfLibraryDefined()))
        {
            wildcardOnly.add("skipIfLibraryDefined");
        }
        if (!wildcardOnly.isEmpty())
        {
            errors.add("Expansion cannot be combined with the wildcard-mechanism directives "
                    + wildcardOnly + " — they steer the other expansion and are not applied to a"
                    + " declared-token expansion");
        }
    }


    /** Single-directive half of {@link #validateExpansionDirectives}; appends the token seen. */
    private static void validateExpansionDirective(
            net.cumba.cdisc.core.model.ExpansionDirective directive, List<String> tokens,
            List<String> errors)
    {
        String token = directive.getToken();
        if (token == null || token.isBlank())
        {
            errors.add("Expansion entry has no 'token'");
        }
        else
        {
            if (token.chars().allMatch(Character::isLetterOrDigit))
            {
                errors.add("Expansion token '" + token + "' carries no sigil — a token drawn from"
                        + " the CDISC name alphabet [A-Z0-9] would substitute inside a real"
                        + " column name; use e.g. '&" + token + "'");
            }
            else if (token.contains("--"))
            {
                // R-5.20. '--' is sigil-bearing, so the check above lets it through — but it
                // already means "the caller-supplied domain code" and was deliberately re-anchored
                // under EC-36 / Fix #125. A token containing it would make substitute() do a blind
                // String.replace("--", …) across the whole rule body, hitting --SEQ, SUPP-- and
                // every anchored prefix.
                errors.add("Expansion token '" + token + "' contains '--', which already means the"
                        + " caller-supplied domain code (EC-36 / Fix #125); choose a token that"
                        + " cannot collide with it");
            }
            tokens.add(token);
        }
        net.cumba.cdisc.core.model.ExpansionSource over = directive.getOver();
        if (over == null)
        {
            errors.add("Expansion entry has invalid 'over' value '" + directive.getOverJson()
                    + "' (expected one of: " + EXPANSION_SOURCE_VALUES + ")");
            return;
        }
        switch (over)
        {
        case SHARED_VARIABLES ->
        {
            if (directive.getWith() == null || directive.getWith().isBlank())
            {
                errors.add("Expansion over 'shared_variables' requires a 'with' dataset name");
            }
        }
        case DOMAIN_FROM_VARIABLE ->
        {
            String pattern = directive.getPattern();
            if (pattern == null || pattern.isBlank())
            {
                errors.add("Expansion over 'domain_from_variable' requires a 'pattern'");
            }
            else if (token != null && !token.isBlank() && !pattern.contains(token))
            {
                errors.add("Expansion pattern '" + pattern + "' does not contain its token '"
                        + token + "' — nothing would be captured");
            }
        }
        }
    }


    /**
     * Rejects a declared expansion token appearing anywhere in a variable requirement — gate
     * <b>R6</b>, which is {@code Scope.Variables}' original bar <em>re-pointed</em> onto
     * {@code Requirements.Variables}. See {@link #validateExpansionDirectives} for why this bar
     * exists rather than an R-4.9 relaxation.
     *
     * <p>
     * The facets are scanned by name rather than through
     * {@link Rule#effectiveVariableRequirement()}, so the message can name the facet the author
     * actually wrote. The reason the bar transfers unchanged is that nothing about it was ever
     * specific to {@code Scope}: the requirement gate runs <em>before</em> expansion, so a token
     * there is matched literally and the rule silently never runs.
     * </p>
     */
    private static void validateNoExpansionTokenInScope(Rule rule, List<String> tokens,
            List<String> errors)
    {
        if (tokens.isEmpty())
        {
            return;
        }
        Requirements req = rule.getRequirements();
        VariableRequirement vars = req == null ? null : req.getVariables();
        if (vars != null)
        {
            checkNoExpansionToken(rule, vars.getAll(), "Requirements.Variables.All", tokens,
                    errors);
            checkNoExpansionToken(rule, vars.getAny(), "Requirements.Variables.Any", tokens,
                    errors);
            checkNoExpansionToken(rule, vars.getNone(), "Requirements.Variables.None", tokens,
                    errors);
        }
    }


    /**
     * One list's half of {@link #validateNoExpansionTokenInScope}.
     *
     * <p>
     * ⚠ The {@code [ruleId]} prefix matters here specifically: {@code loadError} is a single joined
     * string per rule, but a package's diagnostics are read together, and R6 was the only gate in
     * the {@code Requirements} family whose message did not name its rule — so in a multi-rule
     * package it was the one finding a reader could not attribute. (Its siblings under
     * {@link #validateExpansionDirectives} are unprefixed too; those are older and out of this
     * gate's scope.)
     * </p>
     */
    private static void checkNoExpansionToken(Rule rule, @Nullable List<String> entries,
            String where, List<String> tokens, List<String> errors)
    {
        if (entries == null)
        {
            return;
        }
        for (String entry : entries)
        {
            for (String token : tokens)
            {
                if (entry != null && entry.contains(token))
                {
                    errors.add("[" + ruleId(rule) + "] Expansion token '" + token
                            + "' must not appear in " + where + " entry '" + entry
                            + "' — the scope gate runs"
                            + " before expansion and would match the token literally,"
                            + " silently skipping the rule for every dataset");
                }
            }
        }
    }


    /**
     * Fix #38: walks {@code rule.Scope.Domains.Include} and {@code .Exclude} and tags the rule with
     * a load error for any null or zero-length entry. Under Fix #38's prefix-matching semantics an
     * empty entry would match every dataset, which is never intended.
     */
    private static void validateDomainScopeEntries(Rule rule, List<String> errors)
    {
        Scope scope = rule.getScope();
        if (scope == null)
        {
            return;
        }
        // Scope.Datasets inherits the empty-entry rejection unchanged: it shares Scope.Domains'
        // entry vocabulary and firstMatchingDomainEntry, so an empty entry is exactly as
        // meaningless there (PLAN-scope-requirements-split §4.6).
        DatasetScope datasets = scope.getDatasets();
        if (datasets != null)
        {
            checkDomainList(datasets.getInclude(), "Datasets", "Include", rule, errors);
            checkDomainList(datasets.getExclude(), "Datasets", "Exclude", rule, errors);
        }
        DomainScope domains = scope.getDomains();
        if (domains == null)
        {
            return;
        }
        checkDomainList(domains.getInclude(), "Domains", "Include", rule, errors);
        checkDomainList(domains.getExclude(), "Domains", "Exclude", rule, errors);
    }

    /**
     * The two 2-character {@code --} wildcard prefixes that are <b>not</b> domain codes and are
     * therefore legitimate: an AP dataset's data-derived base is {@code AP} + the 2-character
     * parent code ({@code APMH}), and a SUPP-renamed-for-length dataset's base is {@code SQ} +
     * {@code RDOMAIN} ({@code SQLB}). Both are 4 characters, which is exactly what a 2-character
     * {@code --} prefix demands. See {@link #checkDomainWildcardPrefix}.
     */
    private static final java.util.Set<String> LEGITIMATE_TWO_CHAR_WILDCARD_PREFIXES = java.util.Set
            .of("AP", "SQ");

    /** Length of a {@code --} prefix that is presumed to be an SDTM domain code. */
    private static final int DOMAIN_CODE_LENGTH = 2;

    /**
     * Warns about a {@code Scope.Domains} {@code --} token whose prefix is a 2-character <em>domain
     * code</em> — e.g. {@code FA--}, {@code LB--}. Such a token is silently wrong in the invisible
     * direction, which is why it earns a gate rather than a guideline entry.
     *
     * <p>
     * The {@code --} contract is strict: {@code FA--} matches a name of exactly four characters. It
     * therefore catches {@code FALB} but <b>misses</b> the split {@code FALBHM}, whose data-derived
     * base ({@code OperationExecutor.unsplitNameFromData}, from the {@code DOMAIN} column) is the
     * <em>2-character</em> {@code FA} — a length no {@code FA--} token can match. The result is a
     * false negative: the rule quietly stops covering the split forms. The correct scope is the
     * plain domain code, {@code Include: ["FA"]}, which the split re-test matches for every member
     * of the family.
     * </p>
     *
     * <p>
     * {@code AP--} and {@code SQ--} are exempt ({@link #LEGITIMATE_TWO_CHAR_WILDCARD_PREFIXES}):
     * neither {@code AP} nor {@code SQ} is a domain code, and both families' bases are four
     * characters. Longer prefixes ({@code SUPP--}, {@code APFA--}) are exempt for the same reason —
     * a prefix that is not a domain code cannot be a dataset's base.
     * </p>
     *
     * <p>
     * Measured at the time of writing: <b>0</b> shipped rules trip this. It exists so the next one
     * is caught at load rather than by an audit.
     * </p>
     *
     * @param rule
     *            the rule being validated
     * @param warnings
     *            the accumulating load-warning list
     */
    static void checkDomainWildcardPrefix(Rule rule, List<String> warnings)
    {
        Scope scope = rule.getScope();
        if (scope == null || scope.getDomains() == null)
        {
            return;
        }
        DomainScope domains = scope.getDomains();
        checkDomainWildcardPrefixList(domains.getInclude(), "Include", rule, warnings);
        checkDomainWildcardPrefixList(domains.getExclude(), "Exclude", rule, warnings);
    }


    private static void checkDomainWildcardPrefixList(@Nullable List<String> entries, String which,
            Rule rule, List<String> warnings)
    {
        if (entries == null)
        {
            return;
        }
        for (String entry : entries)
        {
            if (entry == null || entry.isEmpty() || !entry.contains("--"))
            {
                continue;
            }
            // A pattern entry (glob / regex) is not a `--` wildcard at all —
            // firstMatchingDomainEntry
            // gives scopePattern precedence, so the strict-length reasoning below does not apply.
            if (ScopeMatcher.scopePattern(entry) != null)
            {
                continue;
            }
            String prefix = entry.replace("--", "").toUpperCase(java.util.Locale.ROOT);
            if (prefix.length() == DOMAIN_CODE_LENGTH
                    && !LEGITIMATE_TWO_CHAR_WILDCARD_PREFIXES.contains(prefix))
            {
                warnings.add("[" + ruleId(rule) + "] Scope.Domains." + which + " entry '" + entry
                        + "' has a 2-character wildcard prefix, which reads as an SDTM domain code."
                        + " A `--` token matches a name of exactly " + (prefix.length() + 2)
                        + " characters, so it misses every split form of " + prefix
                        + " (whose data-derived base is the 2-character '" + prefix
                        + "' itself) — a silent false negative. Scope the plain domain code"
                        + " instead: '" + prefix + "'.");
            }
        }
    }


    private static void checkDomainList(@Nullable List<String> entries, String element,
            String which, Rule rule, List<String> errors)
    {
        if (entries == null)
        {
            return;
        }
        for (String entry : entries)
        {
            if (entry == null || entry.isEmpty())
            {
                errors.add("[" + ruleId(rule) + "] Scope." + element + "." + which
                        + " contains an empty/null entry — an empty entry is not a dataset name"
                        + " and matches nothing; remove it or replace it with a dataset name.");
                // One error per list is enough to flag the issue.
                return;
            }
        }
    }


    /**
     * Phase 4 (PLAN-extend-expression-engine): walks {@code rule.Scope.Domains},
     * {@code rule.Scope.Datasets} and {@code Requirements.Variables} entries and tags the rule with
     * a load error for any entry whose pattern fails to compile
     * ({@link ScopeMatcher#scopePattern}). Only the {@code /…/} regex form can fail — the glob
     * translation quotes every literal run. Validating the raw entry is sufficient for
     * {@code Scope.Domains}: {@code --} resolution replaces the leading two dashes with two letters
     * and can change neither the pattern-form detection nor regex validity.
     * <p>
     * Fix #124: {@code Requirements.Variables} entries additionally go through
     * {@link #checkVariableScopeEntry}, which splits a qualified {@code DATASET.VARIABLE} entry and
     * validates the two halves separately.
     * </p>
     */
    private static void validateScopePatternEntries(Rule rule, List<String> errors)
    {
        // The same entry validation for the new spelling — an invalid /…/ in a requirement is
        // exactly as fatal at match time as one in the scope it replaces. ⚠ Ahead of the Scope
        // null-guard on purpose: Requirements is a TOP-LEVEL block, so a rule carrying one and no
        // Scope at all would otherwise skip validation entirely.
        Requirements req = rule.getRequirements();
        VariableRequirement requiredVars = req == null ? null : req.getVariables();
        if (requiredVars != null)
        {
            checkVariableScopeList(requiredVars.getAll(), "Requirements.Variables", "All", rule,
                    errors);
            checkVariableScopeList(requiredVars.getAny(), "Requirements.Variables", "Any", rule,
                    errors);
            checkVariableScopeList(requiredVars.getNone(), "Requirements.Variables", "None", rule,
                    errors);
        }
        Scope scope = rule.getScope();
        if (scope == null)
        {
            return;
        }
        DomainScope domains = scope.getDomains();
        if (domains != null)
        {
            checkPatternList(domains.getInclude(), "Domains", "Include", rule, errors);
            checkPatternList(domains.getExclude(), "Domains", "Exclude", rule, errors);
        }
        DatasetScope datasets = scope.getDatasets();
        if (datasets != null)
        {
            checkPatternList(datasets.getInclude(), "Datasets", "Include", rule, errors);
            checkPatternList(datasets.getExclude(), "Datasets", "Exclude", rule, errors);
        }
        // Fix #117/#118: the Data_Structures / Subclasses vocabularies are closed — an unknown
        // token can never match a detected value, so it would silently disable (Include) or
        // no-op (Exclude) the scope. Fail loud at load instead.
        if (scope.getDataStructures() != null)
        {
            checkTokenList(scope.getDataStructures().getInclude(),
                    net.cumba.cdisc.core.metadata.AdamDataStructureDetector.STRUCTURE_TOKENS,
                    "Data_Structures", "Include", rule, errors);
            checkTokenList(scope.getDataStructures().getExclude(),
                    net.cumba.cdisc.core.metadata.AdamDataStructureDetector.STRUCTURE_TOKENS,
                    "Data_Structures", "Exclude", rule, errors);
        }
        if (scope.getSubclasses() != null)
        {
            checkTokenList(scope.getSubclasses().getInclude(),
                    net.cumba.cdisc.core.metadata.AdamSubclassDetector.SUBCLASS_TOKENS,
                    "Subclasses", "Include", rule, errors);
            checkTokenList(scope.getSubclasses().getExclude(),
                    net.cumba.cdisc.core.metadata.AdamSubclassDetector.SUBCLASS_TOKENS,
                    "Subclasses", "Exclude", rule, errors);
        }
    }


    /**
     * Fix #117/#118: validates a closed-vocabulary scope list ({@code Data_Structures} /
     * {@code Subclasses}). Review findings 4/5/7/8 tightened this to <b>byte-exact canonical
     * tokens</b>: a case/whitespace variant would load here but silently match nothing in the
     * Python twin gate (raw string compare) and the exact-{@code ALL} sentinel checks of both
     * matchers — exactly the silent-disable failure mode this validation exists to prevent. The
     * {@code ALL} sentinel is Include-only ({@code Exclude: ["ALL"]} can never match a detected
     * token and would be a guaranteed no-op). Unknown tokens tag the rule with a load error
     * (fail-loud sentinel, Fix #37 semantics); the known-token list in the message is sorted for
     * deterministic loadError strings.
     */
    private static void checkTokenList(@Nullable List<String> entries,
            java.util.Set<String> knownTokens, String element, String which, Rule rule,
            List<String> errors)
    {
        if (entries == null)
        {
            return;
        }
        boolean isInclude = "Include".equals(which);
        for (String entry : entries)
        {
            if (entry == null)
            {
                continue;
            }
            if ("ALL".equals(entry) && !isInclude)
            {
                errors.add("[" + ruleId(rule) + "] Scope." + element + "." + which
                        + " must not contain the ALL sentinel (it can never match a detected"
                        + " token — the exclusion would be a silent no-op)");
                continue;
            }
            if (!"ALL".equals(entry) && !knownTokens.contains(entry))
            {
                errors.add("[" + ruleId(rule) + "] Scope." + element + "." + which + " entry '"
                        + entry + "' is not an exact known token (known: "
                        + new java.util.TreeSet<>(knownTokens) + (isInclude ? ", ALL)" : ")"));
            }
        }
    }


    private static void checkPatternList(@Nullable List<String> entries, String element,
            String which, Rule rule, List<String> errors)
    {
        if (entries == null)
        {
            return;
        }
        for (String entry : entries)
        {
            if (entry == null)
            {
                continue;
            }
            try
            {
                ScopeMatcher.scopePattern(entry);
            }
            catch (PatternSyntaxException ex)
            {
                errors.add("[" + ruleId(rule) + "] Scope." + element + "." + which + " entry '"
                        + entry + "' is not a valid pattern: " + ex.getMessage());
            }
        }
    }


    /**
     * Fix #124: {@code Scope.Variables} counterpart of {@link #checkPatternList}. Each entry is
     * split by {@link ScopeVariableEntry#parse} before its pattern is compiled, so the qualifier of
     * a cross-dataset entry ({@code DM.ARM}) never reaches the pattern compiler and the variable
     * half is validated on its own.
     *
     * @param entries
     *            the Include or Exclude list, possibly {@code null}
     * @param which
     *            {@code "Include"} or {@code "Exclude"}, for the message
     * @param rule
     *            the rule being validated
     * @param errors
     *            the accumulating load-error list
     */
    private static void checkVariableScopeList(@Nullable List<String> entries, String element,
            String which, Rule rule, List<String> errors)
    {
        if (entries == null)
        {
            return;
        }
        for (String entry : entries)
        {
            if (entry != null)
            {
                checkVariableScopeEntry(entry, element, which, rule, errors);
            }
        }
    }


    /**
     * Validates a single {@code Scope.Variables} entry. Rejected shapes (each a hard load error,
     * surfaced by {@code RuleRunner.execute} as an ERROR sentinel):
     * <ul>
     * <li>a leading or trailing {@code .} — {@code ScopeVariableEntry.parse} leaves such an entry
     * unqualified, so it would silently degrade into a literal column name that can never
     * match;</li>
     * <li>a second {@code .} in the variable half ({@code A.B.C}) — no column name contains a dot,
     * so this is always an authoring error rather than a never-matching entry;</li>
     * <li>a leading {@code --} in the variable half of a qualified entry ({@code AE.--SEQ}) — the
     * domain it should resolve against is ambiguous (the primary's prefix or the qualifier's), so
     * the shape is rejected rather than given a guessed meaning;</li>
     * <li>an invalid {@code /…/} regex in the variable half.</li>
     * </ul>
     * A whole-entry {@code /…/} regex is exempt from every dot rule — a regular expression contains
     * dots by construction.
     */
    private static void checkVariableScopeEntry(String entry, String element, String which,
            Rule rule, List<String> errors)
    {
        String prefix = "[" + ruleId(rule) + "] " + element + "." + which + " entry '" + entry
                + "'";
        boolean wholeEntryRegex = ScopeVariableEntry.isWholeEntryRegex(entry);
        if (!wholeEntryRegex && (entry.startsWith(".") || entry.endsWith(".")))
        {
            errors.add(prefix + " must not start or end with '.'"
                    + " (a qualified entry is DATASET.VARIABLE)");
            return;
        }
        ScopeVariableEntry parsed = ScopeVariableEntry.parse(entry);
        String variable = parsed.variable();
        if (parsed.isQualified())
        {
            if (!ScopeVariableEntry.isWholeEntryRegex(variable) && variable.indexOf('.') >= 0)
            {
                errors.add(prefix + " carries more than one '.'"
                        + " — a qualified entry is DATASET.VARIABLE");
                return;
            }
            if (variable.startsWith("--"))
            {
                errors.add(prefix + " uses a '--' domain prefix in the variable half of a"
                        + " qualified entry, which has no unambiguous resolution domain");
                return;
            }
        }
        try
        {
            ScopeMatcher.scopePattern(variable);
        }
        catch (PatternSyntaxException ex)
        {
            errors.add(prefix + " is not a valid pattern: " + ex.getMessage());
        }
    }


    private static void walkCheck(CheckCondition condition, String path, List<String> errors,
            String ruleId)
    {
        if (condition == null)
        {
            return;
        }
        switch (condition)
        {
        case CheckConditionAll all ->
        {
            for (int i = 0; i < all.getConditions().size(); i++)
            {
                walkCheck(all.getConditions().get(i), path + ".all[" + i + "]", errors, ruleId);
            }
        }
        case CheckConditionAny any ->
        {
            for (int i = 0; i < any.getConditions().size(); i++)
            {
                walkCheck(any.getConditions().get(i), path + ".any[" + i + "]", errors, ruleId);
            }
        }
        case CheckConditionNot not -> walkCheck(not.getCondition(), path + ".not", errors, ruleId);
        case CheckConditionLeaf leaf -> validateLeaf(leaf, path, errors, ruleId);
        case CheckConditionConstant _ ->
        {
            // constants carry no operand
        }
        case net.cumba.cdisc.core.model.CheckConditionExpression ce ->
        {
            // native-only expression — operands are already resolved in the Expr; the only leaf
            // check that applies is the retired generic presence call (owner ruling 1 of
            // PLAN-leaf-scope-domain-inference.md).
            String generic = genericPresenceCall(ce.expr());
            if (generic != null)
            {
                errors.add(genericPresenceError(ruleId, path, generic));
            }
        }
        }
    }

    /**
     * The retired generic presence operators. Their meaning depended on the rule's
     * {@code Rule_Type} (column presence on a data rule, dataset presence on a Domain Presence
     * Check) — the one disambiguation the type used to carry. Owner ruling 1 (2026-08-13) dropped
     * them: an authored rule spells the fact it means, {@code var_exists} / {@code var_not_exists}
     * for column presence and {@code ds_exists} / {@code ds_not_exists} for dataset presence.
     */
    static final java.util.Set<String> GENERIC_PRESENCE_OPERATORS = java.util.Set.of("exists",
            "not_exists");

    static String genericPresenceError(String ruleId, String path, String operator)
    {
        return "[" + ruleId + "] " + path + " uses the retired generic presence operator '"
                + operator + "' — spell the fact the rule means: var_exists(X) / var_not_exists(X)"
                + " for column presence, ds_exists(X) / ds_not_exists(X) for dataset presence";
    }


    /** The first generic presence call in {@code e}, or {@code null}. */
    static @Nullable String genericPresenceCall(net.cumba.cdisc.core.expr.ast.Expr e)
    {
        return switch (e)
        {
        case net.cumba.cdisc.core.expr.ast.Expr.Call c ->
        {
            if (GENERIC_PRESENCE_OPERATORS.contains(c.name()))
            {
                yield c.name();
            }
            for (net.cumba.cdisc.core.expr.ast.Expr a : c.args())
            {
                String hit = genericPresenceCall(a);
                if (hit != null)
                {
                    yield hit;
                }
            }
            for (net.cumba.cdisc.core.expr.ast.Expr a : c.kwargs().values())
            {
                String hit = genericPresenceCall(a);
                if (hit != null)
                {
                    yield hit;
                }
            }
            yield null;
        }
        case net.cumba.cdisc.core.expr.ast.Expr.And a -> firstGenericPresenceCall(a.parts());
        case net.cumba.cdisc.core.expr.ast.Expr.Or o -> firstGenericPresenceCall(o.parts());
        case net.cumba.cdisc.core.expr.ast.Expr.Not n -> genericPresenceCall(n.inner());
        case net.cumba.cdisc.core.expr.ast.Expr.Binary b ->
        {
            String hit = genericPresenceCall(b.left());
            yield hit != null ? hit : genericPresenceCall(b.right());
        }
        case net.cumba.cdisc.core.expr.ast.Expr.Ref _,net.cumba.cdisc.core.expr.ast.Expr.Lit _ -> null;
        };
    }


    private static @Nullable String firstGenericPresenceCall(
            List<net.cumba.cdisc.core.expr.ast.Expr> parts)
    {
        for (net.cumba.cdisc.core.expr.ast.Expr p : parts)
        {
            String hit = genericPresenceCall(p);
            if (hit != null)
            {
                return hit;
            }
        }
        return null;
    }

    /**
     * Operators that NO engine implements: absent from {@code CheckOperator}/the legacy
     * {@code OperatorRegistry} (where they were a silent "Unknown operator" no-op returning an
     * empty BitSet) and deliberately declined by the native compiler to preserve that parity. Under
     * the zero-fallback program (PLAN-native-engine-full-coverage, decision P1b) a rule using one
     * must surface as a load error — never as a silently-passing no-op.
     */
    private static final java.util.Set<String> NON_EXECUTABLE_OPERATORS = java.util.Set.of(
            // The affix-compare variants CheckToExpr can raise but the legacy OperatorRegistry
            // never implemented (absent from CheckOperator → silent "Unknown operator" no-op).
            // Tagged per the same P1b decision so a rule using one fails loudly at load instead of
            // silently passing on legacy / silently firing on native (P9 review finding 2).
            // suffix_equal_to is now executable on both backends (native suffix(X,n)==; legacy
            // OperatorRegistry::evalSuffixEqualTo) — J5 / CORE-DRAFT-900007.
            "prefix_is_contained_by", "suffix_not_equal_to", "suffix_is_contained_by");

    /**
     * Operand NAMES with no resolution on either engine (R-P4,
     * {@code plans/done/PLAN-native-engine-residuals.md}): {@code dataset_metadata} is registered
     * as a builtin token but is not a dataset-fold name, not variable-level, and has no provider
     * mapping — a leaf naming it has never fired anywhere. Tagged at load like the non-executable
     * operators above.
     */
    private static final java.util.Set<String> NON_EXECUTABLE_OPERANDS = java.util.Set
            .of("dataset_metadata");

    private static void validateLeaf(CheckConditionLeaf leaf, String path, List<String> errors,
            String ruleId)
    {
        if (leaf.getOperator() != null && GENERIC_PRESENCE_OPERATORS.contains(leaf.getOperator()))
        {
            errors.add(genericPresenceError(ruleId, path, leaf.getOperator()));
        }
        // P1b — non-executable operator: fail at load instead of silently passing at runtime.
        if (leaf.getOperator() != null && NON_EXECUTABLE_OPERATORS.contains(leaf.getOperator()))
        {
            errors.add("[" + ruleId + "] " + path + " uses operator '" + leaf.getOperator()
                    + "' which is not executable (no engine implementation; previously a silent"
                    + " no-op)");
        }
        // R-P4 (PLAN-native-engine-residuals) — non-executable OPERAND: dataset_metadata has no
        // resolution on either engine (not a dataset-level fold name, not variable-level, no
        // provider mapping; at row level the column miss yields an empty BitSet) — a rule using
        // it has never fired. Fail loudly at load (user decision: ADAM-ADD-100019).
        if (leaf.getName() != null && NON_EXECUTABLE_OPERANDS.contains(leaf.getName()))
        {
            errors.add("[" + ruleId + "] " + path + " uses operand '" + leaf.getName()
                    + "' which is not executable (no resolution on any engine; previously a"
                    + " silent no-op)");
        }
        // Review F2 (PLAN-extend-expression-engine) — ds_exists/ds_not_exists take a PLAIN
        // dataset name only. The dotted / filter / ${...} / --prefix forms are rejected by the
        // native compiler (ExprCompiler.compileExists) and the lowering, but a leaf authored
        // directly in legacy JSON reaches neither: evalDsExists would silently resolver-miss,
        // and ds_not_exists with a ${...} name would silently fire every row.
        if (("ds_exists".equals(leaf.getOperator()) || "ds_not_exists".equals(leaf.getOperator()))
                && leaf.getName() != null)
        {
            String dsName = leaf.getName();
            if (dsName.indexOf('.') >= 0 || dsName.indexOf('=') >= 0 || dsName.contains("${")
                    || dsName.contains("--"))
            {
                errors.add("[" + ruleId + "] operator '" + leaf.getOperator()
                        + "' expects a plain dataset name, found '" + dsName + "'");
            }
        }
        // Name side
        String name = leaf.getName();
        if (OperandSubstitutor.hasPlaceholder(name))
        {
            try
            {
                ParsedOperand parsed = OperandSubstitutor.parse(name);
                OperandSubstitutor.validate(parsed, leaf.getOperator(), Position.NAME);
            }
            catch (OperandParseException | OperatorMismatchException ex)
            {
                errors.add(formatLeafError(ruleId, path, leaf, "name", name, ex));
            }
        }
        // Value side — only when textual
        JsonNode value = leaf.getValue();
        if (value != null && value.isTextual())
        {
            String text = value.asText();
            if (OperandSubstitutor.hasPlaceholder(text))
            {
                try
                {
                    ParsedOperand parsed = OperandSubstitutor.parse(text);
                    OperandSubstitutor.validate(parsed, leaf.getOperator(), Position.VALUE);
                }
                catch (OperandParseException | OperatorMismatchException ex)
                {
                    errors.add(formatLeafError(ruleId, path, leaf, "value", text, ex));
                }
            }
        }
    }


    private static String formatLeafError(@Nullable String ruleId, String path,
            CheckConditionLeaf leaf, String operandPos, @Nullable String operand, Exception ex)
    {
        String msg = ex instanceof OperatorMismatchException ? "operator mismatch" : "parse error";
        StringBuilder sb = new StringBuilder();
        sb.append('[').append(ruleId).append("] ");
        sb.append(path).append(' ');
        sb.append(msg).append(": ");
        sb.append(operandPos).append("=`").append(operand).append("`");
        if (leaf.getOperator() != null)
        {
            sb.append(", operator=`").append(leaf.getOperator()).append('`');
        }
        sb.append(": ").append(ex.getMessage());
        return sb.toString();
    }

    /** What {@link #ruleId} yields for a rule carrying no identity at all. */
    private static final String UNKNOWN_RULE_ID = "<unknown>";

    private static String ruleId(Rule rule)
    {
        String id = rule.effectiveId();
        return id != null ? id : UNKNOWN_RULE_ID;
    }

}
