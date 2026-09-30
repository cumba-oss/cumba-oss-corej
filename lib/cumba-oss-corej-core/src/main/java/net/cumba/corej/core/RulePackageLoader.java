package net.cumba.corej.core;

import com.fasterxml.jackson.databind.DeserializationFeature;
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
import net.cumba.corej.core.exec.OperandSubstitutor;
import net.cumba.corej.core.exec.OperandSubstitutor.OperandParseException;
import net.cumba.corej.core.exec.OperandSubstitutor.OperatorMismatchException;
import net.cumba.corej.core.exec.OperandSubstitutor.ParsedOperand;
import net.cumba.corej.core.exec.OperandSubstitutor.Position;
import net.cumba.corej.core.exec.OutputVariableDeriver;
import net.cumba.corej.core.exec.ProviderRequirements;
import net.cumba.corej.core.exec.RuleClassifier;
import net.cumba.corej.core.exec.ScopeMatcher;
import net.cumba.corej.core.exec.ScopeVariableEntry;
import net.cumba.corej.core.model.CheckCondition;
import net.cumba.corej.core.model.CheckConditionAll;
import net.cumba.corej.core.model.CheckConditionAny;
import net.cumba.corej.core.model.CheckConditionNot;
import net.cumba.corej.core.model.DatasetScope;
import net.cumba.corej.core.model.DomainScope;
import net.cumba.corej.core.model.Executability;
import net.cumba.corej.core.model.ExecutabilityHint;
import net.cumba.corej.core.model.LevelCheck;
import net.cumba.corej.core.model.Requirements;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import net.cumba.corej.core.model.Scope;
import net.cumba.corej.core.model.Sensitivity;
import net.cumba.corej.core.model.VariableRequirement;
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
        // Review E5 (PLAN-rule-unknown-keys-gate): every rule knows the key it was loaded under,
        // so a load error can name it even when Core.Id itself is the misspelt key.
        if (pkg.getRules() != null)
        {
            pkg.getRules().forEach((key, rule) ->
            {
                if (rule != null)
                {
                    rule.setLoadKey(key);
                }
            });
        }
        removeParkedRules(pkg);
        // materialiseBindings FIRST: the derivation and every later gate read the compiled
        // bindings, and a rule whose bindings were never parsed reads as if it had none.
        materialiseBindings(pkg);
        // Derivation (Sensitivity) runs BEFORE the field gates, whose Group-consistency check
        // reads the derived value; the shipped rules/ corpus does not author it.
        deriveOmittedFields(pkg);
        validateOperandSubstitution(pkg);
        validateEnumFields(pkg);
        // Sequenced with its sibling load gates, and deliberately BEFORE retainNativeExpr, so it
        // judges the AUTHORED Check — the shape the rule's author wrote. Since runbook W8 no load
        // pass inlines or drops a binding (the operation inliners went with the Operation
        // carrier), so every `$`-reference it judges is one the author wrote against the
        // author's own Bindings.
        validateOperationReferences(pkg);
        // (Fix #156's `--`-in-reference/ordering/offset gate retired with its last guarded field:
        // `ordering` went with is_last_in_group (wave 1), `reference` / `offset` with the
        // date_diff_days operation (runbook W2b). On the function surface `--` is resolved by the
        // typed column parameter, a quoted name is a load error (R1), and a `--` under a grouped
        // aggregate's domain= is a load error of GroupedAggregate's reader.)
        // D13 item 3 — a dictionary call naming no external_dictionary_type is unanswerable
        // by ANY install, so it is an authoring defect on the loadError channel, exactly like the
        // dangling $ above. Deliberately BEFORE injectInlineOperationGates: the injector only
        // gates a TYPED inline dictionary call, so a typeless one would otherwise evaluate with
        // no gate and no provider and silently false-pass (the closed hole this guard exists
        // for); running first also means the walk judges the authored Check only.
        validateDictionaryOperationTypes(pkg);
        normalizeJoinTypes(pkg);
        injectInlineOperationGates(pkg);
        retainNativeExpr(pkg);
        // AFTER retainNativeExpr: the OV derivation reads the installed checkExpr and compiled
        // bindings, so it runs once retainNativeExpr has put them in place.
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
            // PLAN-rule-unknown-keys-gate, T1-6 (a): the same silence one level up. A
            // package-level key has no rule to attach a loadError to, so — like the threshold
            // arm beside it — it fails the package load.
            throw new IOException("rule package declares unknown key '" + key
                    + "' — a package carries only 'rules' and 'standards'"
                    + net.cumba.corej.core.model.KeyHint.clause(key, BoundRuleKeys.RULE_PACKAGE,
                            presentKeys(pkg), java.util.Set.of())
                    + "; check the spelling");
        }
        for (RulePackage.UnknownStandardKey extra : pkg.getUnknownStandardKeys())
        {
            throw new IOException(
                    "rule package declares unknown key '" + extra.key() + "' under 'standards["
                            + extra.index() + "]' — a standards entry carries only 'id' and 'role'"
                            + net.cumba.corej.core.model.KeyHint.clause(extra.key(),
                                    BoundRuleKeys.STANDARD_REF, extra.present(), java.util.Set.of())
                            + "; check the spelling");
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
     * load-guard <em>severity switch</em>: {@link #validateOperationReferences(Rule)} and the
     * retired {@code validateUnresolvedOperationWildcards} consulted it to downgrade their
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
     * {@code RuleStorageAssembler} (part of the {@code rules-src/} → {@code rules/} generation flow
     * in {@code cumba-corej-rules}) omits a parked rule from the shipped packages entirely. That
     * skip cannot replace this one: a package that did not come out of that tool — hand-authored,
     * or handed to {@link #load(Path)} / {@link #loadFromString(String)} from outside the shipped
     * corpus — bypasses it, so a {@code Not Executable} rule can still reach the engine. Both
     * checks are required; neither is redundant.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>A caller that bypasses {@link #finishLoad} must invoke this method itself.</b>
     * {@code LibraryRuleMapper.mapRulePackage} (retired with the CDISC-Library rules ingestion,
     * cache P4) was exactly that case — it hand-picked the passes that made sense without the
     * operation-normalisation pass (itself retired with the Operation carrier in runbook W8) rather
     * than running the whole pipeline — so it called this explicitly. No caller outside this class
     * remains; the method is left package-private rather than private only so that a future
     * same-package path can meet the obligation. Any such path that assembles a {@link RulePackage}
     * outside {@code finishLoad} inherits it, or a rule sourced through it will declare itself not
     * executable and run anyway.
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
            // interchangeable: in a shipped package the key IS the Core.Id, but on the retired
            // CDISC-Library path (LibraryRuleMapper.mapRuleMap, gone with cache P4) it was the
            // rule's UUID — naming that in a warning told the reader nothing. The key still beats
            // ruleId()'s "<unknown>" literal for a body that omits `Core` altogether, which is the
            // only case where a multi-rule summary would otherwise degrade to "<unknown>,
            // <unknown>".
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
     * compatibility with the findings the shipped corpus currently produces, not by parity. ⭐
     * <b>Whether {@code inner} is the right default is now RULED:</b> triage finding S2
     * ({@code plans/done/PLAN-expired-justifications-triage.md}, and
     * {@code plans/done/PLAN-outer-join-type.md}) was answered by the owner on 2026-09-25 —
     * <i>"inner stays default"</i> — registered in {@code .claude/docs/rulings/value-semantics.md}
     * §11. Measured the same day: 192 of the 209 {@code rules-src} rules with
     * {@code Match_Datasets} rely on it.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Downstream consequence, easy to miss.</b> After this pass <b>no</b> loaded
     * {@code MatchDataset} has a null or blank {@code Join_Type}, so any code that treats "the
     * field is absent" as a distinguishable state is dead for loader-loaded rules. That is
     * precisely how {@code RuleCohortGrouper.equalityCohortKey}'s {@code getJoinType() != null}
     * rejection became unreachable for the corpus (EC-74) — while staying reachable for the
     * {@code CDISC-AD0591-<domain>-<var>} rules the {@code CROSS_DATASET_METADATA} generator built
     * per dataset, which never came through here (that generator is now deleted:
     * {@code plans/PLAN-remove-rule-generator.md}). ⚑ That grouper is retired
     * ({@code PLAN-retire-cohort-runner.md}); the example stands as the clearest illustration of
     * this pass's reach, and the reach itself is unchanged. The engine's
     * {@code KeyMatchRowExpander} refuses an entry that reaches it without a {@code Join_Type} (U15
     * of {@code PLAN-retire-dead-multi-match-lookup}), so a loader-bypassing rule cannot run.
     * </p>
     *
     * <p>
     * ⚑ <b>That family no longer exists.</b> Fix #366 stopped {@code CROSS_DATASET_METADATA} — the
     * sole minter of those rules — from firing, and {@code plans/PLAN-remove-rule-generator.md}
     * deleted the generator itself. No production path mints a rule that bypasses this loader; test
     * fixtures that build a {@code Rule} by hand do, and since U15 of
     * {@code PLAN-retire-dead-multi-match-lookup} they must stamp {@code Join_Type} themselves —
     * {@code KeyMatchRowExpander}'s former {@code left} fallback for an absent value is gone, and
     * an entry without one is refused at execution. ⚑ Its former companion, the
     * {@code getJoinType() != null} rejection, is already <b>deleted</b> rather than merely
     * unreachable — it lived in the retired {@code RuleCohortGrouper}
     * ({@code PLAN-retire-cohort-runner.md}).
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
     * production load performs rather than re-implementing it. It has no production caller outside
     * this class. Its callers outside it are test harnesses: {@code RuleScaffold}, the
     * {@code rulespec} drift-guard harness in the rule-corpus repository's tests, and tests in this
     * repository that build rules by hand.
     * </p>
     *
     * <p>
     * ⚑ <b>History.</b> {@code DatasetRuleResolver} bypasses {@link #load} and does not call this
     * method. That mattered while it <em>generated</em> rules: the
     * {@code CDISC-AD0591-}/{@code GEN-XDVAL-} family it minted kept a null {@code Join_Type} and
     * ran as a <b>left</b> join via {@code KeyMatchRowExpander}'s fallback ({@code Fix #233}). Fix
     * #366 stopped that family and {@code PLAN-remove-rule-generator} deleted the generator; the
     * resolver now delivers only the rules its caller hands it — in production, rules from
     * {@link #load}, which this pass has already normalised.
     * </p>
     */
    public static void normalizeJoinTypes(Rule rule)
    {
        if (rule == null || rule.getMatchDatasets() == null)
        {
            return;
        }
        for (net.cumba.corej.core.model.MatchDataset md : rule.getMatchDatasets())
        {
            // Fix #236: identical predicate and identical stamped value, now expressed through
            // the JoinType vocabulary so the default and the load-time gate cannot drift apart.
            if (md != null && net.cumba.corej.core.model.JoinType.isAbsent(md.getJoinType()))
            {
                md.setJoinType(net.cumba.corej.core.model.JoinType.INNER.getJsonValue());
            }
        }
    }


    /**
     * Materialises every rule's authored {@code Bindings:} entries into its compiled bindings
     * ({@link #materialiseBindings(Rule)}). Runs before {@link #retainNativeExpr}.
     */
    private static void materialiseBindings(RulePackage pkg)
    {
        if (pkg == null || pkg.getRules() == null)
        {
            return;
        }
        for (Rule rule : pkg.getRules().values())
        {
            materialiseBindings(rule);
        }
    }


    /**
     * Per-rule variant of {@link #materialiseBindings(RulePackage)}: parses each authored
     * {@code Bindings:} entry ({@code name:} + {@code expression:}, phase 7b) <b>once</b> into a
     * {@link net.cumba.corej.core.model.CompiledBinding}, compiled like the {@code Check}
     * ({@code PLAN-binding-expressions} wave 0). Since runbook W8
     * ({@code PLAN-retire-operation-surface}) this is the ONE kind of binding: the operation record
     * a single top-level operation call used to become went with the retired carrier, and the
     * unparseable-expression load error that path filed is filed here. Public for the same reason
     * as {@link #deriveOmittedFields(Rule)}: anything that binds a {@link Rule} outside this loader
     * — the {@code rulespec} harness ({@code RuleScaffold}), a tool, an editor preview — must apply
     * the same pass, or a shipped rule's declared bindings never reach {@code bindingOrder()} and
     * silently resolve {@code null}. Idempotent (guarded on the compiled list); a malformed entry
     * lands on the rule's {@code loadError} channel, preserving any earlier cause.
     *
     * <p>
     * ⛔ <b>One name per binding.</b> A name declared twice is a load error: the stage-A order check
     * records the <em>first</em> declaration and the runtime map the <em>last</em>, and that
     * disagreement would decide which declaration a {@code $}-reference reads. Every binding is
     * named (nothing could reference an anonymous one).
     * </p>
     *
     * @param rule
     *            the rule to materialise in place, may be {@code null}
     */
    public static void materialiseBindings(@Nullable Rule rule)
    {
        if (rule == null)
        {
            return;
        }
        try
        {
            List<net.cumba.corej.core.model.Binding> bindings = rule.getBindings();
            if (rule.getCompiledBindings() == null && bindings != null && !bindings.isEmpty())
            {
                rule.setCompiledBindings(compileAuthoredBindings(bindings));
            }
            // ⚠ NOT inside the bindings guard: `missing_values` is declared on an inline call
            // (min_date / max_date), which need not be a binding at all.
            validateMissingValuesPolarity(rule);
        }
        catch (net.cumba.corej.core.expr.RuleDefinitionException ex)
        {
            // Preserve any earlier load error (e.g. an enum/scope validation failure) rather
            // than clobbering it — the rule ERRORs either way, but the first cause is kept.
            String prior = rule.getLoadError();
            rule.setLoadError(prior != null ? prior + "; " + ex.getMessage() : ex.getMessage());
        }
    }


    /**
     * Wave 0 ({@code PLAN-binding-expressions} §5.1 R1), W8: parses each authored binding once.
     *
     * @return the compiled bindings in authored order, or {@code null} when there are none
     */
    private static @Nullable List<net.cumba.corej.core.model.CompiledBinding> compileAuthoredBindings(
            List<net.cumba.corej.core.model.Binding> bindings)
    {
        List<net.cumba.corej.core.model.CompiledBinding> compiled = new ArrayList<>(
                bindings.size());
        java.util.Set<String> seen = new java.util.HashSet<>();
        List<String> authoredBefore = new ArrayList<>();
        for (net.cumba.corej.core.model.Binding binding : bindings)
        {
            String name = binding.getName();
            String expression = binding.getExpression();
            if (expression == null || expression.isBlank())
            {
                throw new net.cumba.corej.core.expr.RuleDefinitionException("binding `" + name
                        + "` declares no `expression:` — a `Bindings:` entry is `name:` +"
                        + " `expression:`");
            }
            if (name == null || name.isBlank())
            {
                throw new net.cumba.corej.core.expr.RuleDefinitionException("the binding `"
                        + expression + "` declares no `name:` — a binding is read only through its"
                        + " `$`-name");
            }
            if (!seen.add(name))
            {
                throw new net.cumba.corej.core.expr.RuleDefinitionException("binding name `" + name
                        + "` is declared twice — a `Bindings:` name must be unique within the rule");
            }
            net.cumba.corej.core.expr.ast.Expr parsed;
            try
            {
                parsed = net.cumba.corej.core.expr.CheckExpressionParser.parse(expression);
            }
            catch (net.cumba.corej.core.expr.ExpressionException ex)
            {
                throw new net.cumba.corej.core.expr.RuleDefinitionException(
                        "invalid binding expression `" + expression + "`: " + ex.getMessage(), ex);
            }
            compiled.add(new net.cumba.corej.core.model.CompiledBinding(name, parsed,
                    authoredBefore, null));
            authoredBefore.add(name);
        }
        return compiled.isEmpty() ? null : compiled;
    }


    /**
     * EC-51 Half B / OQ3 — rejects {@code missing_values: "indeterminate"} on a call
     * ({@code min_date} / {@code max_date}, read directly or through a compiled binding or another
     * function's argument) whose result is consumed by a <b>positive-polarity</b> leaf, because
     * there the declaration does the exact opposite of what its author intends.
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
     * {@code Primitives.equalsTypedAware} (the equality anchor since D121 retired
     * {@code ScalarSemantics.equalsNumericAware}), whose textual fold takes <em>both</em> a
     * missing-but-not-{@code MissingValue} cell and a null target to {@code ""} — so
     * {@code equal_to} against a no-value extreme fires when the compared column is <em>itself</em>
     * empty, and goes silent when it is populated. The populated case is the one the rule is
     * written for, and silence there is unrecoverable, so {@code equal_to} sits in the silencing
     * set. By the same token {@code not_equal_to} does <em>not</em> report on an empty-vs-empty
     * row; the disposition's promise is therefore "reports on a populated row", not "always
     * reports".
     * </p>
     *
     * <p>
     * Nothing downstream would catch it — the rule would simply stop reporting — and a silent kill
     * runs against the house {@code absent ⇒ report} default. So it is a load error, on the same
     * channel as the value / operator guard of {@code GroupedAggregate}'s reader.
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
     * The declaring call is matched structurally ({@code inlineIndeterminate}); since runbook W2b
     * no operation carries {@code missing_values} any more, so there is no {@code $}-id arm.
     * </p>
     *
     * @throws net.cumba.corej.core.expr.RuleDefinitionException
     *             if a declaring call is consumed by a silencing leaf
     */
    private static void validateMissingValuesPolarity(Rule rule)
    {
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
        // PLAN-binding-expressions R2: an inline call INSIDE a compiled binding is on the inline
        // surface too, and gets the same value / operator rejections.
        List<net.cumba.corej.core.model.CompiledBinding> compiled = rule.getCompiledBindings();
        if (compiled != null)
        {
            compiled.forEach(binding -> validateInlineMissingValues(binding.expression()));
        }
        // Wave 4b (PLAN-scalar-metadata-functions D-W4b-1): the per-variable function is read only
        // through a binding — its value is the per-variable map the per-variable loop projects
        // onto the variable cursor, and only a `$`-binding is projected. The operation surface
        // refused an inline use the same way ("cannot be inlined").
        for (CheckCondition level : rule.checkConditions())
        {
            rejectInlinePerVariableCalls(level);
        }
        rejectInlinePerVariableCalls(rule.getPrecondition());
        if (compiled != null)
        {
            compiled.forEach(binding -> rejectInlinePerVariableCalls(binding.expression(), true));
        }
        validateTupleCorrespondence(rule);
        Map<String, String> silencing = new LinkedHashMap<>();
        for (CheckCondition level : rule.checkConditions())
        {
            collectSilencingConsumers(throughCompiledBindings(level, rule), false, silencing);
        }
        // The Precondition (Fix #13) gates whether the Check runs at all, so a positive-polarity
        // consumer there silences the rule just as effectively as one in the Check itself.
        collectSilencingConsumers(throughCompiledBindings(rule.getPrecondition(), rule), false,
                silencing);
        // R2: a comparison INSIDE a compiled binding is a consumer wherever the binding is read (a
        // condition binding `$b: $ind > X`), so each binding expression is walked on its own too.
        // A compiled binding read by the Check is ALSO judged at its point of use, through
        // throughCompiledBindings above — so `$c: $ind` consumed by `$c > X` is caught where its
        // polarity is known. Over-rejection is loud and the author restructures; under-rejection is
        // the silent death this guard exists to prevent.
        if (compiled != null)
        {
            compiled.forEach(
                    binding -> collectSilencingExpr(binding.expression(), false, silencing));
        }
        if (!silencing.isEmpty())
        {
            Map.Entry<String, String> first = silencing.entrySet().iterator().next();
            throw new net.cumba.corej.core.expr.RuleDefinitionException("`missing_values: "
                    + net.cumba.corej.core.exec.GroupedAggregate.MISSING_VALUES_INDETERMINATE
                    + "` on `" + first.getKey() + "` is consumed by the positive-polarity leaf `"
                    + first.getValue()
                    + "`, which reads an undeterminable extreme as \"no violation\" and would"
                    + " silence the check instead of reporting it");
        }
    }


    /**
     * The condition with every reference to a compiled binding read through
     * ({@code BindingInliner}) — so the polarity walk sees an indeterminate operation consumed via
     * a compiled binding exactly where its value is compared ({@code PLAN-binding-expressions} R2).
     * The very same condition when the rule has no compiled binding.
     */
    private static @Nullable CheckCondition throughCompiledBindings(
            @Nullable CheckCondition condition, Rule rule)
    {
        if (condition == null || rule.getCompiledBindings() == null
                || rule.getCompiledBindings().isEmpty())
        {
            return condition;
        }
        return inlineCompiledBindings(condition, rule);
    }


    private static CheckCondition inlineCompiledBindings(CheckCondition condition, Rule rule)
    {
        return switch (condition)
        {
        case CheckConditionAll all -> new CheckConditionAll(
                all.getConditions().stream().map(c -> inlineCompiledBindings(c, rule)).toList());
        case CheckConditionAny any -> new CheckConditionAny(
                any.getConditions().stream().map(c -> inlineCompiledBindings(c, rule)).toList());
        case CheckConditionNot not -> new CheckConditionNot(
                inlineCompiledBindings(not.getCondition(), rule));
        case net.cumba.corej.core.model.CheckConditionExpression expr ->
        {
            net.cumba.corej.core.expr.ast.Expr inlined = java.util.Objects.requireNonNull(
                    net.cumba.corej.core.expr.convert.BindingInliner.inline(expr.expr(), rule));
            yield inlined == expr.expr() ? expr
                    : new net.cumba.corej.core.model.CheckConditionExpression(inlined,
                            net.cumba.corej.core.expr.ExpressionPrinter.print(inlined));
        }
        };
    }


    /**
     * Applies the kwarg rejections the retired operation parser used to own ({@code fromCall}:
     * {@code keep_missings} on a non-grouping operation or without a {@code group}, and — since
     * runbook W2b, when its last consuming operation became a registry function —
     * {@code missing_values} as an unknown argument) to every operation authored <b>inline</b> in a
     * native Check expression.
     *
     * <p>
     * ⚠ <b>The inline surface is a genuinely separate load path.</b> Only a binding's expression is
     * compiled at load ({@code installNativeExpr}); an inline call in a Check level or the
     * Precondition is compiled lazily, so without this walk its argument errors would surface only
     * at its first evaluation. Validating here gives the inline surface the same {@code loadError}
     * channel, and the same message, as a binding. (Before the legacy evaluator was retired, the
     * compiler's own {@code fromCall} rejection silently degraded the rule to legacy evaluation;
     * that is why this gate exists.)
     * </p>
     */
    private static void validateInlineMissingValues(@Nullable CheckCondition condition)
    {
        if (condition == null)
        {
            return;
        }
        // ⛔ R2-7 (review round 2): an EXHAUSTIVE pattern switch over the sealed CheckCondition,
        // not an `instanceof` chain. This method is a VALIDATOR — a chain whose last `else if`
        // fails to match simply stops validating, so a FIFTH implementor would silently skip
        // inline `missing_values` validation on every Check shaped like it, with nothing red.
        // The switch has no `default`, so that fifth implementor fails to COMPILE here instead.
        // (Round 3: this said "sixth". CheckCondition permits FOUR -- All, Any, Not, Expression
        // -- so the next one is the fifth. The miscount was inherited from RuleRunner.)
        switch (condition)
        {
        case net.cumba.corej.core.model.CheckConditionExpression expression -> validateInlineMissingValues(
                expression.expr());
        case CheckConditionAll all -> all.getConditions()
                .forEach(RulePackageLoader::validateInlineMissingValues);
        case CheckConditionAny any -> any.getConditions()
                .forEach(RulePackageLoader::validateInlineMissingValues);
        case CheckConditionNot not -> validateInlineMissingValues(not.getCondition());
        }
    }


    /**
     * {@link #rejectInlinePerVariableCalls(net.cumba.corej.core.expr.ast.Expr, boolean)} per level.
     */
    private static void rejectInlinePerVariableCalls(@Nullable CheckCondition condition)
    {
        if (condition == null)
        {
            return;
        }
        switch (condition)
        {
        case net.cumba.corej.core.model.CheckConditionExpression expression -> rejectInlinePerVariableCalls(
                expression.expr(), false);
        case CheckConditionAll all -> all.getConditions()
                .forEach(RulePackageLoader::rejectInlinePerVariableCalls);
        case CheckConditionAny any -> any.getConditions()
                .forEach(RulePackageLoader::rejectInlinePerVariableCalls);
        case CheckConditionNot not -> rejectInlinePerVariableCalls(not.getCondition());
        }
    }


    /**
     * Wave 4b (D-W4b-1): a {@code cross_dataset_variable_metadata(…)} call anywhere but as the
     * whole expression of a binding ({@code rootAllowed}) is a load error.
     */
    private static void rejectInlinePerVariableCalls(net.cumba.corej.core.expr.ast.Expr expr,
            boolean rootAllowed)
    {
        if (!rootAllowed && expr instanceof net.cumba.corej.core.expr.ast.Expr.Call call
                && answersPerVariable(call.name()))
        {
            throw new net.cumba.corej.core.expr.RuleDefinitionException(call.name()
                    + " answers one value per variable and is read only through a binding: make it"
                    + " a binding's whole expression and read the binding ($x)");
        }
        childrenOf(expr).forEach(child -> rejectInlinePerVariableCalls(child, false));
    }


    /**
     * Whether {@code name} is registered as answering a per-variable map
     * ({@code FunctionDescriptor.perVariableMap} — declared once on the descriptor, combined review
     * of runbook W2–W8, W3+W4b L5).
     */
    private static boolean answersPerVariable(String name)
    {
        net.cumba.corej.core.expr.eval.FunctionDescriptor d = net.cumba.corej.core.expr.eval.FunctionRegistry
                .descriptor(name);
        return d != null && d.perVariableMap();
    }


    private static void validateInlineMissingValues(net.cumba.corej.core.expr.ast.Expr expr)
    {
        validateInlineCallShapes(expr);
        // Wave 4 (PLAN-list-functions D-W4-3), inverted by the combined review of runbook W2–W8
        // (W4 M4): every REGISTRY call in the expression — inline in a Check level or the
        // Precondition, nested at any depth — meets the compiler's argument seams at load. After
        // the tailored validators above, so their messages keep precedence; ONCE per root (W4 L1:
        // this ran at every level of the walk, re-walking each subtree — quadratic in the depth).
        validateRegistryCallSeams(expr);
    }


    /** The per-call tailored Check-operator validators of {@link #validateInlineMissingValues}. */
    private static void validateInlineCallShapes(net.cumba.corej.core.expr.ast.Expr expr)
    {
        if (expr instanceof net.cumba.corej.core.expr.ast.Expr.Call call)
        {
            if (call.kwargs().containsKey("keep_missings")
                    && !net.cumba.corej.core.exec.GroupedAggregate.isFunction(call.name())
                    && !net.cumba.corej.core.exec.ReadValue.NAME.equals(call.name())
                    && !net.cumba.corej.core.exec.RecordCount.NAME.equals(call.name())
                    && !net.cumba.corej.core.exec.Distinct.NAME.equals(call.name()))
            {
                // (W5: max / max_date / min_date and read_value read keep_missings= from their
                // bound slot in their own strict reader — GroupedAggregate.readPolicy — so the
                // Check-operator gate below does not judge them.)
                // ⚠⚠ `keep_missings` is the FIRST parameter to appear on BOTH the operation surface
                // and the Check-operator surface, so it cannot be routed through `fromCall` the way
                // `missing_values` was: the retired parser rejected any name that was not an
                // operation,
                // and
                // an inline `has_multiple_values_for(..., keep_missings=false)` would become a
                // bogus
                // "unknown operation function" load error on a perfectly valid rule.
                validateInlineCheckKeepMissings(call);
            }
            if (call.kwargs().containsKey("relation"))
            {
                // EC-87: the next-record comparison relation is a Check-operator kwarg only.
                validateInlineCheckRelation(call);
            }
            validateInlineUniqueSetShape(call);
        }
        childrenOf(expr).forEach(RulePackageLoader::validateInlineCallShapes);
    }

    /**
     * The compiler-dispatched calls whose load-time seam is a <b>tailored strict reader</b> —
     * {@code max} / {@code max_date} / {@code min_date}, {@code read_value}, {@code record_count},
     * {@code distinct}: an inline call meets the same reader a binding meets when it is compiled at
     * load (the missing_values vocabulary, the filter spelling, the required {@code group=}, the
     * target shapes), so the inline surface is loud at load, not at the first evaluation.
     */
    private static final Map<String, java.util.function.Consumer<net.cumba.corej.core.expr.ast.Expr.Call>> TAILORED_CALL_READERS = Map
            .of(net.cumba.corej.core.exec.GroupedAggregate.MAX,
                    net.cumba.corej.core.exec.GroupedAggregate::spec,
                    net.cumba.corej.core.exec.GroupedAggregate.MAX_DATE,
                    net.cumba.corej.core.exec.GroupedAggregate::spec,
                    net.cumba.corej.core.exec.GroupedAggregate.MIN_DATE,
                    net.cumba.corej.core.exec.GroupedAggregate::spec,
                    net.cumba.corej.core.exec.ReadValue.NAME,
                    net.cumba.corej.core.exec.ReadValue::spec,
                    net.cumba.corej.core.exec.RecordCount.NAME,
                    net.cumba.corej.core.exec.RecordCount::spec,
                    net.cumba.corej.core.exec.Distinct.NAME,
                    net.cumba.corej.core.exec.Distinct::spec);

    /**
     * The registry names {@link #validateRegistryCallSeams} does NOT hand to the generic binder
     * seam ({@code ExprCompiler.validateRegistryCall}), checked once on first use by
     * {@link #registryCallSeamExemptions()}.
     */
    private static final class SeamExemptions
    {

        static final java.util.Set<String> NAMES = checkedRegistryCallSeamExemptions();
    }

    /**
     * The registry names exempt from the generic load-time argument seam: exactly the
     * <b>compiler-dispatched</b> calls ({@code CompilerDispatchedCalls} — {@code fn == null}
     * descriptors the compiler compiles through its own dedicated arms, never through the generic
     * binder tail). They keep their tailored seams: the strict readers of
     * {@link #TAILORED_CALL_READERS} and the Check-operator validators of
     * {@link #validateInlineCallShapes} ({@code keep_missings=}, {@code relation=}, the unique-set
     * shape), which a generic binder pass would pre-empt. EVERY other registry function — the
     * functions the registry evaluates — meets the generic seam at load (combined review of runbook
     * W2–W8, W4 M4: the seam used to be an allowlist of the wave-4 / wave-4b names, so an inline
     * {@code row_max(name_pattern=TRTEDT)} or a dictionary call with a non-literal level loaded and
     * failed only at its first evaluation).
     *
     * <p>
     * The set is self-checked on first use so the exemption cannot pass vacuously or widen by
     * accident: it must be non-empty, every member must be a registered name whose descriptor
     * carries no {@code EvalFunction} (a registry-evaluated function can never hide in it), and
     * every tailored reader must be a member.
     * </p>
     *
     * @return the exempt names (unmodifiable)
     * @throws IllegalStateException
     *             if the exemption set violates one of its invariants
     */
    public static java.util.Set<String> registryCallSeamExemptions()
    {
        return SeamExemptions.NAMES;
    }


    private static java.util.Set<String> checkedRegistryCallSeamExemptions()
    {
        java.util.Set<String> names = new java.util.TreeSet<>();
        names.addAll(net.cumba.corej.core.expr.eval.spi.CompilerDispatchedCalls.booleanCallNames());
        names.addAll(net.cumba.corej.core.expr.eval.spi.CompilerDispatchedCalls
                .negationDispatchedBooleanCallNames());
        names.addAll(net.cumba.corej.core.expr.eval.spi.CompilerDispatchedCalls.valueCallNames());
        if (names.isEmpty())
        {
            throw new IllegalStateException(
                    "the registry-call seam exemption set is empty — the compiler-dispatched"
                            + " names were not found, so every tailored call would meet the"
                            + " generic binder seam");
        }
        for (String name : names)
        {
            net.cumba.corej.core.expr.eval.FunctionDescriptor d = net.cumba.corej.core.expr.eval.FunctionRegistry
                    .descriptor(name);
            if (d == null)
            {
                throw new IllegalStateException("registry-call seam exemption '" + name
                        + "' is not a registered function name");
            }
            if (d.fn() != null)
            {
                throw new IllegalStateException("registry-call seam exemption '" + name
                        + "' is a registry-evaluated function (it carries an EvalFunction), so"
                        + " it must meet the generic load-time seam");
            }
        }
        for (String tailored : TAILORED_CALL_READERS.keySet())
        {
            if (!names.contains(tailored))
            {
                throw new IllegalStateException("tailored call reader '" + tailored
                        + "' is not a compiler-dispatched registry name");
            }
        }
        return java.util.Collections.unmodifiableSet(names);
    }


    /**
     * Applies the load-time argument seams to every registry call in {@code expr}, nested calls and
     * list-literal members included; a seam's refusal is the rule's load error. A
     * compiler-dispatched call ({@link #registryCallSeamExemptions()}) meets its tailored strict
     * reader when it has one ({@link #TAILORED_CALL_READERS}) and otherwise its compiler arm; every
     * other registered name meets {@code ExprCompiler.validateRegistryCall} (the binder's arity /
     * unknown-parameter errors, the R1 column-vs-literal seam, the static-string, static-list and
     * vocabulary seams); an unknown name is left to the compiler's own error.
     */
    private static void validateRegistryCallSeams(net.cumba.corej.core.expr.ast.Expr expr)
    {
        switch (expr)
        {
        case net.cumba.corej.core.expr.ast.Expr.And and -> and.parts()
                .forEach(RulePackageLoader::validateRegistryCallSeams);
        case net.cumba.corej.core.expr.ast.Expr.Or or -> or.parts()
                .forEach(RulePackageLoader::validateRegistryCallSeams);
        case net.cumba.corej.core.expr.ast.Expr.Not not -> validateRegistryCallSeams(not.inner());
        case net.cumba.corej.core.expr.ast.Expr.Binary b ->
        {
            validateRegistryCallSeams(b.left());
            validateRegistryCallSeams(b.right());
        }
        case net.cumba.corej.core.expr.ast.Expr.Call call ->
        {
            try
            {
                java.util.function.Consumer<net.cumba.corej.core.expr.ast.Expr.Call> tailored = TAILORED_CALL_READERS
                        .get(call.name());
                if (tailored != null)
                {
                    tailored.accept(call);
                }
                else if (!registryCallSeamExemptions().contains(call.name()))
                {
                    net.cumba.corej.core.expr.eval.ExprCompiler.validateRegistryCall(call);
                }
            }
            catch (net.cumba.corej.core.expr.ExpressionException ex)
            {
                // (ExpressionException always carries a message; the valueOf is NullAway's due.)
                throw new net.cumba.corej.core.expr.RuleDefinitionException(
                        String.valueOf(ex.getMessage()), ex);
            }
            call.args().forEach(RulePackageLoader::validateRegistryCallSeams);
            call.kwargs().values().forEach(RulePackageLoader::validateRegistryCallSeams);
        }
        case net.cumba.corej.core.expr.ast.Expr.Lit lit ->
        {
            if (lit.kind() == net.cumba.corej.core.expr.ast.Expr.LitKind.LIST
                    && lit.value() instanceof List<?> items)
            {
                for (Object item : items)
                {
                    if (item instanceof net.cumba.corej.core.expr.ast.Expr e)
                    {
                        validateRegistryCallSeams(e);
                    }
                }
            }
        }
        case net.cumba.corej.core.expr.ast.Expr.Ref _ ->
                {
                }
        }
    }

    /** The uniqueness pair whose canonical authored form is {@code f([A, B, …])} (2026-08-23). */
    private static final java.util.Set<String> UNIQUE_SET_OPERATORS = java.util.Set
            .of("is_unique_set", "is_not_unique_set");

    /**
     * Owner requirement #1 (2026-08-23,
     * {@code plans/done/PLAN-authoring-grammar-unique-set-and-output-exclusion.md}): rejects, on
     * the {@code loadError} channel, every spelling of {@code is_(not_)unique_set} other than the
     * canonical single list operand {@code f([A, B, …])} — the retired {@code f(A, keys=[…])} /
     * {@code f(A, B)} / {@code f(A)} forms (with the migration text) and the authored empty list
     * {@code f([])} (the degenerate all-members-drop tuple is a runtime contract, never an authored
     * one).
     *
     * <p>
     * ⚠⚠ It must be a LOAD error, not an {@code ExprCompiler} {@code unsupported(...)}: the
     * compiler's throw DEGRADES the rule (it stops running) where the author expects an error — the
     * {@link #validateInlineCheckKeepMissings} reasoning. It reaches a call through a
     * {@link net.cumba.corej.core.model.CheckConditionExpression} — since phase 7 (D121) the only
     * Check shape there is, the leaf model and its {@code ExprLowering} round-trip being retired.
     * </p>
     */
    private static void validateInlineUniqueSetShape(net.cumba.corej.core.expr.ast.Expr.Call call)
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
            throw new net.cumba.corej.core.expr.RuleDefinitionException(
                    "`" + call.name() + "` no longer takes keys=" + migration);
        }
        if (call.args().size() != 1)
        {
            throw new net.cumba.corej.core.expr.RuleDefinitionException(
                    "`" + call.name() + "` takes exactly one list operand" + migration);
        }
        if (!(call.args().get(0) instanceof net.cumba.corej.core.expr.ast.Expr.Lit lit)
                || lit.kind() != net.cumba.corej.core.expr.ast.Expr.LitKind.LIST)
        {
            throw new net.cumba.corej.core.expr.RuleDefinitionException(
                    "`" + call.name() + "`'s operand must be a list literal" + migration);
        }
        if (((List<?>) lit.value()).isEmpty())
        {
            throw new net.cumba.corej.core.expr.RuleDefinitionException(
                    "`" + call.name() + "([])` has no members; list at least one column");
        }
    }

    /**
     * The operators that consume a grouping-key {@code keep_missings=} on the <b>Check</b> surface
     * — the allowlist the retired declared-leaf surface shared, kept so the inline surface rejects
     * the same set it always did.
     */
    private static final java.util.Set<String> CHECK_KEEP_MISSINGS_OPERATORS = java.util.Set.of(
            "has_multiple_values_for", "is_inconsistent_across_dataset", "is_not_unique_set",
            "is_unique_set", "present_on_multiple_rows_within",
            "not_present_on_multiple_rows_within", "does_not_have_next_corresponding_record",
            "has_next_corresponding_record", "empty_within_except_last_row",
            "target_is_not_sorted_by", "is_sorted_by",
            // wave 1 (PLAN-function-surface-wave1 D-W1-4): the two grouped callables ported from
            // the Operation surface consume keep_missings= as registry functions now.
            "is_last_in_group", "has_mixed_emptiness_within_group");

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
            net.cumba.corej.core.expr.ast.Expr.Call call)
    {
        net.cumba.corej.core.expr.ast.Expr v = call.kwargs().get("keep_missings");
        if (!(v instanceof net.cumba.corej.core.expr.ast.Expr.Lit lit)
                || lit.kind() != net.cumba.corej.core.expr.ast.Expr.LitKind.BOOL)
        {
            throw new net.cumba.corej.core.expr.RuleDefinitionException(
                    "`keep_missings` must be a boolean literal (true/false) on `" + call.name()
                            + "`");
        }
        if (!CHECK_KEEP_MISSINGS_OPERATORS.contains(call.name()))
        {
            throw new net.cumba.corej.core.expr.RuleDefinitionException(
                    "`keep_missings` is not supported by `" + call.name()
                            + "`; only the group-aware operators consume it");
        }
    }


    /**
     * EC-87 — rejects an unusable inline {@code relation=} on a Check-operator call, on the
     * {@code loadError} channel: a non-string literal, an operator other than the next-record pair
     * ({@link net.cumba.corej.core.model.NextRecordRelation#OPERATORS} — both Q1 twins, the
     * {@code keep_missings} lesson), or a spelling outside
     * {@link net.cumba.corej.core.model.NextRecordRelation#SPELLINGS}.
     *
     * <p>
     * ⚠⚠ The inline surface is the one that matters (see {@link #validateInlineCheckKeepMissings}):
     * {@code ExprCompiler}'s own throw on an unknown spelling would only <em>degrade</em> the rule,
     * and a typo'd {@code relation="=<"} that silently fell back to identity would keep the rule
     * quietly over-reporting with no fixture to catch it.
     * </p>
     */
    private static void validateInlineCheckRelation(net.cumba.corej.core.expr.ast.Expr.Call call)
    {
        net.cumba.corej.core.expr.ast.Expr v = call.kwargs().get("relation");
        if (!(v instanceof net.cumba.corej.core.expr.ast.Expr.Lit lit)
                || lit.kind() != net.cumba.corej.core.expr.ast.Expr.LitKind.STRING)
        {
            throw new net.cumba.corej.core.expr.RuleDefinitionException(
                    "`relation` must be a string literal on `" + call.name() + "`; expected one of "
                            + net.cumba.corej.core.model.NextRecordRelation.SPELLINGS);
        }
        if (!net.cumba.corej.core.model.NextRecordRelation.OPERATORS.contains(call.name()))
        {
            throw new net.cumba.corej.core.expr.RuleDefinitionException(
                    "`relation` is not supported by `" + call.name()
                            + "`; only has_next_corresponding_record consumes it");
        }
        if (net.cumba.corej.core.model.NextRecordRelation
                .fromSpelling((String) lit.value()) == null)
        {
            throw new net.cumba.corej.core.expr.RuleDefinitionException("unknown `relation` `"
                    + lit.value() + "` on `" + call.name() + "`; expected one of "
                    + net.cumba.corej.core.model.NextRecordRelation.SPELLINGS);
        }
    }

    /** The composite-membership probe function: {@code tuple(a, b, …)}. */
    private static final String TUPLE_FUNCTION = "tuple";

    /**
     * ⭐ <b>§4b of {@code plans/PLAN-membership-as-equality.md} (owner Q1 and Q4, 2026-09-21): a
     * tuple comparison whose operands cannot correspond must not load.</b> Composite membership
     * ({@code tuple(c1, …) [not] in $set}) is plain positional equality, with no check today that
     * the probe and the reference set agree — so a probe whose columns are a <b>permutation</b> of
     * the set's, or of a different <b>length</b>, can never match, and nothing says so.
     *
     * <p>
     * Exactly two shapes are decidable as guaranteed-dead, and the guard is deliberately that
     * narrow:
     * </p>
     * <ol>
     * <li><b>different arity</b> — Q1 property 4: both arities are statically known, so a mismatch
     * is an authoring mistake the loader can name, and answering {@code false} would hide it
     * forever;</li>
     * <li><b>the same multiset of column names in a different order</b> — Q4, ruled <b>(a) reject
     * it</b>: a transposition check written this way is indistinguishable from the slip, and the
     * corpus carries <b>zero</b> permutations, so nothing is lost today.</li>
     * </ol>
     *
     * <p>
     * ⛔ <b>Same length, different names is LEGAL and unchecked</b> — it is the normal use of the
     * feature, and the corpus proves it: {@code CDISC-SEND-0333} pairs {@code (PPNOMDY, PPTPTREF)}
     * against {@code (PCNOMDY, PCTPTREF)} and {@code PMDA-SD1143} pairs {@code (USUBJID, AESEQ)}
     * against {@code (USUBJID, IDVARVAL)}. A name-equality check would red both.
     * </p>
     *
     * <p>
     * ⚠⚠ <b>Why the loader and not {@code ExprCompiler}</b> — the same reasoning
     * {@link #validateInlineUniqueSetShape} carries: the compiler's throw <em>degrades</em> the
     * rule (it stops running) where the author expects an error. ⚠ And it has to be here for a
     * second reason the plan did not anticipate: <b>all 8 shipped sites bind the reference set
     * through a {@code $}-variable</b>, never an inline {@code distinct(...)}, so the set's column
     * list is only reachable with the rule's {@code Bindings:} in hand — which the compiler, whose
     * entry point is {@code compile(Expr)}, does not have. A guard written inside the compiler
     * would stand down on every site in the corpus and be <b>vacuous</b>.
     * </p>
     *
     * <p>
     * The guard stands down wherever either side is not statically known — a set bound to something
     * other than a list-literal {@code distinct(...)}, a probe argument that is not a plain column
     * reference — under the same partiality contract the rest of the checker uses: report
     * known-vs-known conflicts only.
     * </p>
     */
    private static void validateTupleCorrespondence(Rule rule)
    {
        // ⛔⛔ NO early return on an empty binding map. It was there, and it made the INLINE
        // list-target path — `tuple(…) not in distinct([…], domain="D")`, with no `Bindings:`
        // block at all — structurally UNREACHABLE: no bindings meant no map meant no walk.
        // Found by writing the test review round 1 asked for (finding 6), which is the point of
        // asking for it: the reviewer called the inline path "dead in the suite", and it was dead
        // in the ENGINE.
        Map<String, List<String>> setColumns = tupleSetColumnsByBinding(rule);
        for (CheckCondition level : rule.checkConditions())
        {
            validateTupleCorrespondence(level, setColumns);
        }
        validateTupleCorrespondence(rule.getPrecondition(), setColumns);
    }


    /**
     * Walks a Check level for composite-membership comparisons. ⛔ An EXHAUSTIVE pattern switch over
     * the sealed {@link CheckCondition}, never an {@code instanceof} chain, for
     * {@link #validateInlineMissingValues}'s stated reason: this is a VALIDATOR, and a chain whose
     * last branch fails to match simply stops validating.
     */
    private static void validateTupleCorrespondence(@Nullable CheckCondition condition,
            Map<String, List<String>> setColumns)
    {
        if (condition == null)
        {
            return;
        }
        switch (condition)
        {
        case net.cumba.corej.core.model.CheckConditionExpression expression -> validateTupleCorrespondence(
                expression.expr(), setColumns);
        case CheckConditionAll all -> all.getConditions()
                .forEach(c -> validateTupleCorrespondence(c, setColumns));
        case CheckConditionAny any -> any.getConditions()
                .forEach(c -> validateTupleCorrespondence(c, setColumns));
        case CheckConditionNot not -> validateTupleCorrespondence(not.getCondition(), setColumns);
        }
    }


    private static void validateTupleCorrespondence(net.cumba.corej.core.expr.ast.Expr expr,
            Map<String, List<String>> setColumns)
    {
        if (expr instanceof net.cumba.corej.core.expr.ast.Expr.Binary binary
                && (binary.op() == net.cumba.corej.core.expr.ast.Expr.BinOp.IN
                        || binary.op() == net.cumba.corej.core.expr.ast.Expr.BinOp.NOT_IN)
                && binary.left() instanceof net.cumba.corej.core.expr.ast.Expr.Call tupleCall
                && TUPLE_FUNCTION.equals(tupleCall.name()))
        {
            List<String> probe = columnNames(tupleCall.args());
            List<String> members = tupleSetColumns(binary.right(), setColumns);
            if (probe != null && members != null)
            {
                checkCorrespondence(probe, members);
            }
        }
        childrenOf(expr).forEach(child -> validateTupleCorrespondence(child, setColumns));
    }


    /**
     * The two rejections of §4b. ⭐ <b>No case folding</b>, and that is measured rather than
     * assumed: the expression parser refuses a lowercase operand outright (<i>"column names are
     * upper-case"</i>), so a mixed-case column name never reaches this method and a fold here could
     * not fire. One was written, and review round 1's test for it failed at the PARSER — which is
     * what proved it dead. {@code aLowercaseColumnNameCannotReachTheGuardAtAll} pins that parser
     * rule, so if it ever admits lowercase names this decision reds rather than silently going
     * wrong.
     */
    private static void checkCorrespondence(List<String> probe, List<String> members)
    {
        if (probe.size() != members.size())
        {
            throw new net.cumba.corej.core.expr.RuleDefinitionException(
                    "a composite membership compares " + probe.size() + " probe column(s) " + probe
                            + " against " + members.size() + " reference column(s) " + members
                            + " — the arities must match, or the comparison can never"
                            + " be true");
        }
        // ⭐ NO case folding, and that is measured rather than assumed. The expression parser
        // refuses a lowercase operand outright — *"a lowercase or underscore-containing operand
        // must be a registered built-in; column names are upper-case"* — so a mixed-case column
        // name never reaches this method, and a fold here could not fire. It was written, and
        // review round 1's test for it failed at the PARSER, proving the fold dead. A guard whose
        // branches cannot execute is how a guard quietly stops meaning anything.
        if (probe.equals(members))
        {
            return;
        }
        List<String> probeSorted = new ArrayList<>(probe);
        List<String> membersSorted = new ArrayList<>(members);
        java.util.Collections.sort(probeSorted);
        java.util.Collections.sort(membersSorted);
        if (probeSorted.equals(membersSorted))
        {
            throw new net.cumba.corej.core.expr.RuleDefinitionException(
                    "a composite membership probes " + probe + " against a reference set of the"
                            + " same columns in a different order " + members + " — the comparison"
                            + " is positional, so it can never be true. Reorder the `tuple(...)`"
                            + " to match the reference set");
        }
    }


    /**
     * The reference set's column list, resolved through the rule's {@code Bindings:} when the
     * right-hand side is a {@code $}-variable (the shape all 8 shipped sites use), or read directly
     * from an inline list-literal {@code distinct(...)}. {@code null} where it is not statically
     * known — the guard then stands down.
     */
    private static @Nullable List<String> tupleSetColumns(net.cumba.corej.core.expr.ast.Expr right,
            Map<String, List<String>> setColumns)
    {
        if (right instanceof net.cumba.corej.core.expr.ast.Expr.Ref ref)
        {
            return setColumns.get(ref.name());
        }
        return listTargetColumns(right);
    }


    /**
     * Every {@code Bindings:} entry whose expression is a list-literal operation, as
     * {@code $name -> [COL, …]}. ⚠ The binding's {@code name:} carries the {@code $}, and so does
     * the {@link net.cumba.corej.core.expr.ast.Expr.Ref} that reads it, so the two key the same map
     * without normalisation. A binding that fails to parse contributes nothing: this is a NARROW
     * guard, and the parse error itself is raised by the operation materialisation, which is the
     * code that owns it.
     */
    private static Map<String, List<String>> tupleSetColumnsByBinding(Rule rule)
    {
        List<net.cumba.corej.core.model.Binding> bindings = rule.getBindings();
        if (bindings == null || bindings.isEmpty())
        {
            return Map.of();
        }
        Map<String, List<String>> out = new LinkedHashMap<>();
        for (net.cumba.corej.core.model.Binding binding : bindings)
        {
            String name = binding.getName();
            String expression = binding.getExpression();
            if (name == null || expression == null || expression.isBlank())
            {
                continue;
            }
            List<String> columns = staticListColumns(expression);
            if (columns != null)
            {
                out.put(name, columns);
            }
        }
        return out;
    }


    /**
     * The statically-readable column list of a binding expression, or {@code null} when it is not
     * one. ⚠ An expression that does not parse contributes nothing rather than failing here: the
     * parse error is raised by the operation materialisation, which is the code that owns it, and
     * re-raising it from a narrow membership guard would misattribute the cause.
     */
    private static @Nullable List<String> staticListColumns(String expression)
    {
        try
        {
            return listTargetColumns(
                    net.cumba.corej.core.expr.CheckExpressionParser.parse(expression));
        }
        catch (RuntimeException _)
        {
            return null;
        }
    }


    /**
     * The column list of an operation call whose single positional argument is a list literal of
     * plain column references — {@code distinct([A, B], domain="D")} and its siblings. {@code null}
     * for every other shape.
     */
    private static @Nullable List<String> listTargetColumns(
            net.cumba.corej.core.expr.ast.@Nullable Expr expr)
    {
        if (!(expr instanceof net.cumba.corej.core.expr.ast.Expr.Call call)
                || call.args().size() != 1
                || !(call.args().get(0) instanceof net.cumba.corej.core.expr.ast.Expr.Lit lit)
                || lit.kind() != net.cumba.corej.core.expr.ast.Expr.LitKind.LIST)
        {
            return null;
        }
        if (!(lit.value() instanceof List<?> items))
        {
            return null;
        }
        List<net.cumba.corej.core.expr.ast.Expr> exprs = new ArrayList<>(items.size());
        for (Object item : items)
        {
            if (!(item instanceof net.cumba.corej.core.expr.ast.Expr element))
            {
                return null;
            }
            exprs.add(element);
        }
        return columnNames(exprs);
    }


    /**
     * The names of a list of expressions when <b>every</b> one is a plain column reference, else
     * {@code null} — a computed probe argument is not statically known and the guard stands down.
     */
    private static @Nullable List<String> columnNames(
            List<net.cumba.corej.core.expr.ast.Expr> exprs)
    {
        if (exprs.isEmpty())
        {
            return null;
        }
        List<String> names = new ArrayList<>(exprs.size());
        for (net.cumba.corej.core.expr.ast.Expr e : exprs)
        {
            if (!(e instanceof net.cumba.corej.core.expr.ast.Expr.Ref ref))
            {
                return null;
            }
            names.add(ref.name());
        }
        return names;
    }


    /** The direct sub-expressions of {@code expr}; empty for a bare operand. */
    private static List<net.cumba.corej.core.expr.ast.Expr> childrenOf(
            net.cumba.corej.core.expr.ast.Expr expr)
    {
        return switch (expr)
        {
        case net.cumba.corej.core.expr.ast.Expr.And and -> and.parts();
        case net.cumba.corej.core.expr.ast.Expr.Or or -> or.parts();
        case net.cumba.corej.core.expr.ast.Expr.Not not -> List.of(not.inner());
        case net.cumba.corej.core.expr.ast.Expr.Binary binary -> List.of(binary.left(),
                binary.right());
        case net.cumba.corej.core.expr.ast.Expr.Call call ->
        {
            List<net.cumba.corej.core.expr.ast.Expr> all = new ArrayList<>(call.args());
            all.addAll(call.kwargs().values());
            yield all;
        }
        case net.cumba.corej.core.expr.ast.Expr.Lit lit ->
        {
            if (lit.kind() != net.cumba.corej.core.expr.ast.Expr.LitKind.LIST)
            {
                yield List.of();
            }
            @SuppressWarnings("unchecked")
            List<net.cumba.corej.core.expr.ast.Expr> items = (List<net.cumba.corej.core.expr.ast.Expr>) lit
                    .value();
            yield items;
        }
        case net.cumba.corej.core.expr.ast.Expr.Ref _ -> List.of();
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
     * Walks the Check tree collecting {@code declaring call ⇒ silencing operator} pairs.
     * {@code negated} carries the parity of the enclosing {@code not:} nesting.
     */
    private static void collectSilencingConsumers(@Nullable CheckCondition condition,
            boolean negated, Map<String, String> out)
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
                .forEach(c -> collectSilencingConsumers(c, negated, out));
        case CheckConditionAny any -> any.getConditions()
                .forEach(c -> collectSilencingConsumers(c, negated, out));
        case CheckConditionNot not -> collectSilencingConsumers(not.getCondition(), !negated, out);
        case net.cumba.corej.core.model.CheckConditionExpression expr -> collectSilencingExpr(
                expr.expr(), negated, out);
        }
    }


    /**
     * The expression walk behind {@link #collectSilencingConsumers}. Only the infix comparisons
     * carry a polarity; anything else is left unjudged, matching the retired leaf walker's
     * treatment of unenumerated operators.
     */
    private static void collectSilencingExpr(net.cumba.corej.core.expr.ast.Expr expr,
            boolean negated, Map<String, String> out)
    {
        switch (expr)
        {
        case net.cumba.corej.core.expr.ast.Expr.And and -> and.parts()
                .forEach(p -> collectSilencingExpr(p, negated, out));
        case net.cumba.corej.core.expr.ast.Expr.Or or -> or.parts()
                .forEach(p -> collectSilencingExpr(p, negated, out));
        case net.cumba.corej.core.expr.ast.Expr.Not not -> collectSilencingExpr(not.inner(),
                !negated, out);
        case net.cumba.corej.core.expr.ast.Expr.Binary binary ->
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
                // A declaring call is its own consumer: it has no `$`-id, so it is matched
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
            childrenOf(binary).forEach(p -> collectSilencingExpr(p, negated, out));
        }
        default -> childrenOf(expr).forEach(p -> collectSilencingExpr(p, negated, out));
        }
    }


    /**
     * Returns a display name for a call declaring {@code missing_values: indeterminate} anywhere
     * inside {@code operand} (recursively, so a {@code min_date(…)} nested in
     * {@code date_diff_days(…)}'s argument is found — a conversion wrapper or an enclosing function
     * is transparent to it, and over-matching is the safe direction), or {@code null}. Such a call
     * has no {@code $}-id to look up, so the polarity gate recognises it structurally.
     */
    private static @Nullable String inlineIndeterminate(net.cumba.corej.core.expr.ast.Expr operand)
    {
        if (operand instanceof net.cumba.corej.core.expr.ast.Expr.Call call
                && call.kwargs()
                        .get("missing_values") instanceof net.cumba.corej.core.expr.ast.Expr.Lit lit
                && net.cumba.corej.core.exec.GroupedAggregate.MISSING_VALUES_INDETERMINATE
                        .equals(lit.value()))
        {
            return call.name() + "(…) inline";
        }
        for (net.cumba.corej.core.expr.ast.Expr child : childrenOf(operand))
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
     * exact gate shape the retired offline converter {@code OperationInliner} once baked into the
     * corpus: {@code library_available()} plus {@code available(<op-call>)} for a library-dependent
     * call, {@code dictionary_available("<type>")} per distinct dictionary type, and
     * {@code available(<op-call>)} for a define-dependent call ({@code available} covers the
     * absent-provider case; there is no define-presence builtin). The inliner's emptiness exemption
     * is honoured: a call whose every use is a direct {@code empty()} / {@code is_missing()}
     * operand gets no {@code available()} term, or the gate would make the rule unreachable.
     *
     * <p>
     * Idempotent by term presence: gates already in the {@code Precondition} (the entire shipped
     * corpus — held to zero injections by a committed corpus test) are never duplicated. Every
     * existing Precondition raises to an expression ({@code tryRaiseToExpr} cannot fail — the
     * load-warning bail-out for an unraisable one went with K7 of
     * {@code PLAN-retire-dead-multi-match-lookup}). Injections are recorded on
     * {@link Rule#getInjectedPreconditionGates()} and logged at {@code INFO}.
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
        LinkedHashMap<String, net.cumba.corej.core.expr.ast.Expr> needed = new LinkedHashMap<>();
        for (CheckCondition condition : rule.checkConditions())
        {
            net.cumba.corej.core.expr.ast.Expr check = tryRaiseToExpr(condition);
            collectGateTerms(check, check, needed);
        }
        if (needed.isEmpty())
        {
            return;
        }
        net.cumba.corej.core.expr.ast.Expr existing = null;
        if (rule.getPrecondition() != null)
        {
            existing = tryRaiseToExpr(rule.getPrecondition());
            for (net.cumba.corej.core.expr.ast.Expr term : flattenAnd(existing))
            {
                needed.remove(net.cumba.corej.core.expr.ExpressionPrinter.print(term));
            }
            if (needed.isEmpty())
            {
                return;
            }
        }
        List<net.cumba.corej.core.expr.ast.Expr> terms = new ArrayList<>(needed.values());
        if (existing != null)
        {
            terms.add(existing);
        }
        net.cumba.corej.core.expr.ast.Expr combined = terms.size() == 1 ? terms.get(0)
                : new net.cumba.corej.core.expr.ast.Expr.And(terms);
        rule.setPrecondition(new net.cumba.corej.core.model.CheckConditionExpression(combined,
                net.cumba.corej.core.expr.ExpressionPrinter.print(combined)));
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
    private static void collectGateTerms(net.cumba.corej.core.expr.ast.Expr node,
            net.cumba.corej.core.expr.ast.Expr check,
            Map<String, net.cumba.corej.core.expr.ast.Expr> needed)
    {
        switch (node)
        {
        case net.cumba.corej.core.expr.ast.Expr.And a -> a.parts()
                .forEach(part -> collectGateTerms(part, check, needed));
        case net.cumba.corej.core.expr.ast.Expr.Or o -> o.parts()
                .forEach(part -> collectGateTerms(part, check, needed));
        case net.cumba.corej.core.expr.ast.Expr.Not n -> collectGateTerms(n.inner(), check, needed);
        case net.cumba.corej.core.expr.ast.Expr.Binary b ->
        {
            collectGateTerms(b.left(), check, needed);
            collectGateTerms(b.right(), check, needed);
        }
        case net.cumba.corej.core.expr.ast.Expr.Call c ->
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


    /**
     * The gate terms one inline call demands, read through {@code ProviderNeeds} — the one reader
     * of provider needs ({@code PLAN-binding-expressions} §5.2 / I1): a registry function by its
     * provider capability, so a provider-backed function inline in the Check gets exactly the
     * {@code library_available()} / {@code available(<call>)} / {@code dictionary_available("…")}
     * terms the operation it replaced got. ({@code available(<call>)} reads the capability's
     * "answered but unusable" signal as "not available" — {@code ExprCompiler}'s availability-gate
     * arm.) The {@code dictionary_available} call is never gated on itself: it IS the gate, as the
     * eager binding arm has always treated it.
     */
    private static void gateTermsForCall(net.cumba.corej.core.expr.ast.Expr.Call call,
            net.cumba.corej.core.expr.ast.Expr check,
            Map<String, net.cumba.corej.core.expr.ast.Expr> needed)
    {
        net.cumba.corej.core.exec.ProviderNeeds needs = net.cumba.corej.core.exec.ProviderNeeds
                .ofCall(call);
        if (needs.isEmpty())
        {
            return;
        }
        if (needs.dictionary())
        {
            for (String type : needs.dictionaryTypes())
            {
                addGateTerm(needed,
                        new net.cumba.corej.core.expr.ast.Expr.Call("dictionary_available",
                                List.of(new net.cumba.corej.core.expr.ast.Expr.Lit(
                                        net.cumba.corej.core.expr.ast.Expr.LitKind.STRING, type)),
                                Map.of()));
            }
            return;
        }
        if (needs.library())
        {
            addGateTerm(needed, new net.cumba.corej.core.expr.ast.Expr.Call("library_available",
                    List.of(), Map.of()));
        }
        if (!testsOnlyEmptiness(check, call))
        {
            addGateTerm(needed, new net.cumba.corej.core.expr.ast.Expr.Call("available",
                    List.of(call), Map.of()));
        }
    }


    private static void addGateTerm(Map<String, net.cumba.corej.core.expr.ast.Expr> needed,
            net.cumba.corej.core.expr.ast.Expr term)
    {
        needed.putIfAbsent(net.cumba.corej.core.expr.ExpressionPrinter.print(term), term);
    }


    /** The AND-spine of an expression as a flat term list ({@code x} alone yields {@code [x]}). */
    private static List<net.cumba.corej.core.expr.ast.Expr> flattenAnd(
            net.cumba.corej.core.expr.ast.Expr expr)
    {
        if (expr instanceof net.cumba.corej.core.expr.ast.Expr.And and)
        {
            List<net.cumba.corej.core.expr.ast.Expr> out = new ArrayList<>();
            and.parts().forEach(p -> out.addAll(flattenAnd(p)));
            return out;
        }
        return List.of(expr);
    }


    /**
     * Whether every occurrence of {@code opCall} in {@code check} is the direct operand of an
     * {@code empty()} / {@code is_missing()} call — the rule tests <em>only</em> the operation
     * result's emptiness, so an {@code available(<op>)} gate would make it unreachable. Ported from
     * the retired {@code OperationInliner}, so loader-injected gates keep the exact shape it baked.
     */
    private static boolean testsOnlyEmptiness(net.cumba.corej.core.expr.ast.Expr check,
            net.cumba.corej.core.expr.ast.Expr opCall)
    {
        int[] counts = new int[2];
        walkEmptinessUse(check, opCall, false, counts);
        return counts[0] > 0 && counts[0] == counts[1];
    }


    private static void walkEmptinessUse(net.cumba.corej.core.expr.ast.Expr node,
            net.cumba.corej.core.expr.ast.Expr target, boolean parentIsEmptiness, int[] counts)
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
        boolean emptiness = node instanceof net.cumba.corej.core.expr.ast.Expr.Call c
                && ("empty".equals(c.name()) || "is_missing".equals(c.name()));
        switch (node)
        {
        case net.cumba.corej.core.expr.ast.Expr.And a -> a.parts()
                .forEach(p -> walkEmptinessUse(p, target, false, counts));
        case net.cumba.corej.core.expr.ast.Expr.Or o -> o.parts()
                .forEach(p -> walkEmptinessUse(p, target, false, counts));
        case net.cumba.corej.core.expr.ast.Expr.Not n -> walkEmptinessUse(n.inner(), target, false,
                counts);
        case net.cumba.corej.core.expr.ast.Expr.Binary b ->
        {
            walkEmptinessUse(b.left(), target, false, counts);
            walkEmptinessUse(b.right(), target, false, counts);
        }
        case net.cumba.corej.core.expr.ast.Expr.Call c ->
        {
            // F-corej-L2-04: walk kwargs with the same flag as args, so this walker sees the
            // same tree as collectGateTerms (which walks both) -- an operation call reached
            // only through a kwarg must be able to earn the emptiness exemption.
            c.args().forEach(arg -> walkEmptinessUse(arg, target, emptiness, counts));
            c.kwargs().values().forEach(v -> walkEmptinessUse(v, target, emptiness, counts));
        }
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
     * unconditionally: since the legacy {@code CheckEvaluator} retirement the native evaluator is
     * the only backend, so there is no flag and no kill-switch. Every Check raises
     * ({@code tryRaiseToExpr} cannot fail); a rule whose raised levels fail to compile keeps
     * {@code checkExpr == null} and can no longer be evaluated at all — {@code RuleRunner} reports
     * it as a per-rule {@code ERROR} rather than falling back — and that every shipped rule does
     * compile is gated corpus-wide by {@code NativeCorpusFullCoverageTest}. Sees the same
     * materialised Check tree.
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
     * {@code DatasetRuleResolver} seam, so generated/expanded concrete rules carry the same native
     * {@code checkExpr} a loader-loaded rule would): raise the rule's Check to {@code Expr},
     * canonicalize its metadata operands (Epic B4 — uniformly, for every rule, since phase 4 of
     * {@code PLAN-leaf-scope-domain-inference.md}), install the expression when the native backend
     * supports it, flag fold-equivalent broadcast verdicts (P3a) and cache the inferred evaluation
     * domain ({@code Rule.getEvaluationDomain()}) the runner dispatches on. No-op for a rule that
     * already carries a {@code checkExpr}, has a {@code loadError} or has no Check.
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
        SequencedMap<Severity, net.cumba.corej.core.expr.ast.Expr> levels = new LinkedHashMap<>();
        for (Map.Entry<Severity, LevelCheck> level : declared.entrySet())
        {
            net.cumba.corej.core.expr.ast.Expr raised = tryRaiseToExpr(
                    level.getValue().condition());
            levels.put(level.getKey(), raised);
        }
        // (Element B — the `variable_exists` lowering — is gone with the operation: runbook W2a
        // re-spelled every site as the compiled binding `var_exists("X")`, PLAN-operation-
        // replacements §2.4.)
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
        for (Map.Entry<Severity, net.cumba.corej.core.expr.ast.Expr> level : levels.entrySet())
        {
            level.setValue(net.cumba.corej.core.expr.MetadataOperandMapping
                    .canonicalizeMetadataOperands(level.getValue()));
        }
        // Wave 0 (PLAN-binding-expressions R24 / R9): the compiled bindings get the SAME
        // canonicalisation and their derived domain, before stage A types them with the Check's
        // own checker and before the runner routes on them.
        installCompiledBindings(rule);
        if (rule.getLoadError() != null)
        {
            return;
        }
        // Phase 2 of PLAN-typed-expression-engine.md: the stage-A checker (spec §2) runs here —
        // once per rule, on the same canonicalised IR the compiler sees, before anything is
        // installed. An ARMED finding files on the existing loadError/park channel (spec §9:
        // load error, rule parked, ERROR once); every armed kind is measured at ZERO newly
        // parked rules over the shipped corpora, so this changes no production verdict.
        // Observe-only findings are logged and offered to the measurement observer.
        net.cumba.corej.core.expr.typed.StageAChecker.runAndApply(rule, levels);
        if (rule.getLoadError() != null)
        {
            return;
        }
        try
        {
            installCompiledLevels(rule, levels);
        }
        catch (net.cumba.corej.core.expr.RuleDefinitionException ex)
        {
            // The expression is definitionally invalid (e.g. var_role at the DATA level): file
            // it as a rule load error so the rule reports ERROR and never evaluates.
            // (isSupported only swallows ExpressionException; a RuleDefinitionException — the
            // "rule is wrong" signal — propagates here.)
            rule.setLoadError(ex.getMessage());
        }
        raisePrecondition(rule);
    }


    /**
     * Wave 0 ({@code PLAN-binding-expressions} R24 / R9): readies the rule's <b>compiled</b>
     * bindings for stage A and the runner — each expression is metadata-canonicalised exactly as
     * the Check's levels are (the uniformity ruling: a name means the same in a binding as in the
     * Check), its derived evaluation domain is installed in authored order
     * ({@link net.cumba.corej.core.expr.eval.BindingDomains#forRule} — a binding sees the domains
     * of the bindings authored before it), and its plan is compiled once. A binding the native
     * backend cannot compile is a load error naming the binding, never a silent absent value.
     *
     * @param rule
     *            the rule whose compiled bindings to install, in place
     */
    private static void installCompiledBindings(Rule rule)
    {
        List<net.cumba.corej.core.model.CompiledBinding> compiled = rule.getCompiledBindings();
        if (compiled == null || compiled.isEmpty())
        {
            return;
        }
        List<net.cumba.corej.core.model.CompiledBinding> canonical = new ArrayList<>(
                compiled.size());
        for (net.cumba.corej.core.model.CompiledBinding binding : compiled)
        {
            // A fresh record with NO domain: an expanded or re-installed rule re-derives it from
            // the expression it now carries, never from a template's.
            canonical.add(new net.cumba.corej.core.model.CompiledBinding(
                    binding.name(), net.cumba.corej.core.expr.MetadataOperandMapping
                            .canonicalizeMetadataOperands(binding.expression()),
                    binding.predecessors(), null));
        }
        rule.setCompiledBindings(canonical);
        net.cumba.corej.core.expr.eval.BindingDomains kinds = net.cumba.corej.core.expr.eval.BindingDomains
                .forRule(rule);
        List<net.cumba.corej.core.model.CompiledBinding> installed = new ArrayList<>(
                canonical.size());
        for (net.cumba.corej.core.model.CompiledBinding binding : canonical)
        {
            try
            {
                net.cumba.corej.core.expr.eval.NativeExprEvaluator
                        .bindingProgram(binding.expression());
            }
            catch (net.cumba.corej.core.expr.ExpressionException
                    | net.cumba.corej.core.expr.RuleDefinitionException ex)
            {
                String error = "binding `" + binding.name() + "` has no native expression form: "
                        + ex.getMessage();
                rule.setLoadError(
                        rule.getLoadError() == null ? error : rule.getLoadError() + "; " + error);
                return;
            }
            installed.add(binding.withDomain(kinds.domainOf(binding.name())));
        }
        rule.setCompiledBindings(installed);
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
     * {@link Rule#getCheckExpr()} keeps meaning <em>the strictest level</em>, so every reader on
     * the single-level path is unchanged; {@link Rule#getEvaluationDomain()} becomes the
     * <b>join</b> of the levels' domains (&#167;3.3 step 2) so the finding unit does not change
     * shape between levels of one rule. The per-level maps are installed only when there is more
     * than one level, so a single-level rule carries exactly the fields it carried before.
     * </p>
     */
    private static void installCompiledLevels(Rule rule,
            SequencedMap<Severity, net.cumba.corej.core.expr.ast.Expr> levels)
    {
        for (net.cumba.corej.core.expr.ast.Expr level : levels.values())
        {
            if (!net.cumba.corej.core.expr.eval.NativeExprEvaluator.isSupported(level))
            {
                rejectUndecidableAllExpansion(rule);
                return;
            }
        }
        net.cumba.corej.core.expr.eval.Domain join = null;
        for (Map.Entry<Severity, net.cumba.corej.core.expr.ast.Expr> level : levels.entrySet())
        {
            // §3.2: the cached domain the runner dispatches on (and the report projects
            // FindingScope from). A memoised result of the inference, never an input to it.
            net.cumba.corej.core.expr.eval.Domain domain = net.cumba.corej.core.expr.eval.DomainScan
                    .infer(level.getValue(),
                            net.cumba.corej.core.expr.eval.BindingDomains.forRule(rule));
            join = join == null ? domain : join.join(domain);
        }
        Map.Entry<Severity, net.cumba.corej.core.expr.ast.Expr> strictest = levels.firstEntry();
        rule.setCheckExpr(strictest.getValue());
        rule.setEvaluationDomain(join);
        // Installed for every rule that DECLARED a level map — a one-entry map included, or its
        // single level would never build a levelPlan and that level's own Message would silently
        // fall back to Outcome.Message (and the violation would carry no claiming level). The
        // test must NOT be `levels.size() > 1`: for a plain `Check:` the map here is the
        // synthesised single level, and installing it would route all ~3 804 shipped rules
        // through executeLevels. Keep AbsentDatasetSkip on the same declared-map test (a third
        // site, RuleCohortGrouper, is retired — PLAN-retire-cohort-runner.md).
        SequencedMap<Severity, LevelCheck> declaredLevels = rule.getCheckLevels();
        if (declaredLevels != null && !declaredLevels.isEmpty())
        {
            rule.setCheckLevelExprs(new LinkedHashMap<>(levels));
        }
        checkVariableUniverse(rule, java.util.Objects.requireNonNull(join, "at least one level"),
                strictest.getValue());
    }


    /**
     * P6b: raises a fold-equivalent {@code Precondition} so the skip-on-false decision evaluates
     * natively. Non-broadcast preconditions stay {@code null} ("not fully resolvable ⇒ continue"),
     * exactly as the retired legacy fold left them.
     */
    private static void raisePrecondition(Rule rule)
    {
        if (rule.getPrecondition() == null)
        {
            return;
        }
        net.cumba.corej.core.expr.ast.Expr pre = tryRaiseToExpr(rule.getPrecondition());
        try
        {
            boolean broadcast = isBroadcastVerdictExpr(pre);
            if (net.cumba.corej.core.expr.eval.NativeExprEvaluator.isSupported(pre))
            {
                if (broadcast)
                {
                    rule.setPreconditionExpr(pre);
                }
            }
            else if (broadcast)
            {
                // Combined review of runbook W2–W8 (W8, filed pre-existing): a broadcast-shaped
                // gate the compiler REFUSES used to leave getPreconditionExpr() null, and a null
                // one means "not fully resolvable ⇒ continue" — the gate was silently ignored and
                // the rule ran ungated. Since R8 every Precondition is engine-written, so a refused
                // gate is an engine defect: loud, at load, naming the compiler's reason.
                String error = "[" + ruleId(rule) + "] the Precondition gate `"
                        + net.cumba.corej.core.expr.ExpressionPrinter.print(pre)
                        + "` has no native expression form (" + nativeRefusal(pre)
                        + ") — a refused gate would be silently ignored";
                rule.setLoadError(
                        rule.getLoadError() == null ? error : rule.getLoadError() + "; " + error);
            }
        }
        catch (net.cumba.corej.core.expr.RuleDefinitionException ex)
        {
            // Same disposition as the Check: a definitionally invalid Precondition (e.g. the
            // retired generic exists) is a rule load error, never a propagating throw.
            rule.setLoadError(rule.getLoadError() == null ? ex.getMessage()
                    : rule.getLoadError() + "; " + ex.getMessage());
        }
    }


    /** The compiler's refusal of {@code expr} (only called once {@code isSupported} said no). */
    private static String nativeRefusal(net.cumba.corej.core.expr.ast.Expr expr)
    {
        try
        {
            net.cumba.corej.core.expr.eval.ExprCompiler.compile(expr);
            return "the compiler gave no reason";
        }
        catch (net.cumba.corej.core.expr.ExpressionException ex)
        {
            return String.valueOf(ex.getMessage());
        }
    }


    /**
     * Installs an <b>engine-internal</b> {@code Precondition} on an already-loaded rule, leaving it
     * in exactly the state {@code finishLoad} would have produced: the tree on
     * {@code Rule.getPrecondition()} and, when it is a fold-equivalent broadcast verdict, its
     * native form on {@code Rule.getPreconditionExpr()}.
     *
     * <p>
     * ⭐ <b>This is the supported way to put a term on the {@code Precondition} tier, and since gate
     * R8 it is the only one.</b> Owner ruling Q3
     * ({@code plans/done/PLAN-scope-requirements-split.md} &#167;4.2) retired {@code Precondition}
     * as an <em>authoring</em> surface while keeping the tier itself untouched — the loader still
     * writes it, {@code RuleRunner} still evaluates it at phase 2e. A field that only the engine
     * may write needs an engine API to write it; before this method the only constructor was an
     * authored document, which is precisely what R8 now rejects.
     * </p>
     *
     * <p>
     * ⚠ It deliberately does <b>not</b> re-run the load gates. The value it installs is by
     * definition not authored — running R8 over it would reject the one channel the ruling kept
     * open. ⚑ No engine code calls it today (the engine's own writers of the tier are
     * {@code injectInlineOperationGates} and the two inliners); it is the constructor the engine's
     * tests use to put a term on the tier, and is kept as that seam
     * ({@code PLAN-retire-dead-multi-match-lookup} U4 / C6).
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
     * The define-item-only attributes ({@code PLAN-leaf-scope-domain-inference.md} §3.7): reads
     * that only a Define-XML ItemDef can answer. Legal under every universe; under
     * {@link net.cumba.corej.core.model.VariableUniverse#DATA} they are lint-surfaced (INFO), never
     * blocked, because a data column with no ItemDef simply reads as absent.
     */
    private static final java.util.Set<String> DEFINE_ITEM_ONLY_ACCESSORS = java.util.Set.of(
            "var_ccode", "var_codelist_extended_values", "var_external_dictionary",
            "var_external_dictionary_version", "define_variable_decode_matches");

    /**
     * The other half of gate <b>G3</b>: an {@code over: all_*} rule whose Check has <b>no native
     * form</b> is rejected at load, because the exclusivity test cannot be decided for it.
     *
     * <p>
     * ⚠ Found by review round 1 of {@code plans/PLAN-expansion-over-all-variables.md}. The domain
     * test lives at the end of {@link #installCompiledLevels}, and an earlier return skips it — a
     * level {@code NativeExprEvaluator} does not support (⚑ a second early return, for a level
     * {@code tryRaiseToExpr} could not raise, went with K7 of
     * {@code PLAN-retire-dead-multi-match-lookup}: the raise cannot fail). Without this, such a
     * rule would carry no load error, expand to one rule per column, and each minted copy would
     * then report the per-rule "no native expression form" ERROR: N duplicated error rows per
     * dataset where one belongs, and the R6 violation never reported to the author at all.
     * </p>
     *
     * <p>
     * ⚑ It is a load error rather than a silent skip for the same reason the domain half is: the
     * rule cannot be evaluated either way, so expanding it multiplies noise and tells the author
     * nothing. Blocking says exactly what is wrong.
     * </p>
     *
     * @param rule
     *            the rule whose native installation is being abandoned
     */
    private static void rejectUndecidableAllExpansion(Rule rule)
    {
        if (!hasAllVariablesExpansion(rule))
        {
            return;
        }
        String error = "[" + ruleId(rule) + "] Expansion over an 'all_*' source on a rule whose"
                + " Check has no native expression form — the expansion/cursor exclusivity cannot"
                + " be decided, and the rule could not be evaluated in any case; fix the Check or"
                + " drop the Expansion block";
        rule.setLoadError(rule.getLoadError() == null ? error : rule.getLoadError() + "; " + error);
    }


    /**
     * Whether the rule declares an expansion over one of the three dataset-enumerating sources.
     *
     * @param rule
     *            the rule to inspect
     * @return {@code true} when at least one directive names an {@code all_*} source
     */
    private static boolean hasAllVariablesExpansion(Rule rule)
    {
        return allVariablesExpansionSource(rule) != null;
    }


    /**
     * The first {@code all_*} expansion source the rule declares, or {@code null}.
     *
     * @param rule
     *            the rule to inspect
     * @return the source, or {@code null} when the rule declares none
     */
    private static net.cumba.corej.core.model.@Nullable ExpansionSource allVariablesExpansionSource(
            Rule rule)
    {
        List<net.cumba.corej.core.model.ExpansionDirective> directives = rule.getExpansion();
        if (directives == null || directives.isEmpty())
        {
            return null;
        }
        for (net.cumba.corej.core.model.ExpansionDirective d : directives)
        {
            net.cumba.corej.core.model.ExpansionSource over = d.getOver();
            if (over == net.cumba.corej.core.model.ExpansionSource.ALL_VARIABLES
                    || over == net.cumba.corej.core.model.ExpansionSource.ALL_NUMERIC_VARIABLES
                    || over == net.cumba.corej.core.model.ExpansionSource.ALL_CHARACTER_VARIABLES)
            {
                return over;
            }
        }
        return null;
    }


    /**
     * Gate <b>G3</b> of {@code plans/PLAN-expansion-over-all-variables.md} — a rule may use an
     * {@code over: all_*} expansion <b>or</b> the variable cursor, never both.
     *
     * <p>
     * <b>Owner ruling, 2026-09-21:</b> <i>"The idea is to not use it this way, either use the
     * {@code over: all_*} or use the {@code varname()} / {@code value()}. I would propose a check
     * and a block that either or, not both."</i> Both together multiply: the expansion mints one
     * rule per variable and each minted rule then iterates every variable again.
     * </p>
     *
     * <p>
     * ⛔⛔ <b>The test is the evaluation DOMAIN, not a scan for {@code varname()} / {@code value()}.
     * </b> Those two names are a small fraction of the cursor readers — {@code DomainScan} also
     * types an <em>arity-1</em> {@code var_*} accessor ({@code var_label("DATA")}, whose name
     * defaults to the cursor), every bare {@code variable_*} / {@code library_variable_*} /
     * {@code define_variable_*} operand, <b>any</b> {@code vlm_*} call regardless of its argument,
     * a per-variable {@code $}-operation, and the zero-argument form of the varname-anchored calls.
     * A name scan would pass {@code var_label("DATA") != var_label("LIBRARY")} and leave the
     * multiplication in place. {@code domain.varCursor()} is the same predicate
     * {@code BindingScope.of} routes on, so the guard and the runtime cannot disagree.
     * </p>
     *
     * <p>
     * ⚠ The {@code Precondition} is inferred <b>separately and explicitly</b>. This runs inside
     * {@code installCompiledLevels}, which is called before {@code raisePrecondition}, so
     * {@code getPreconditionExpr()} is still {@code null} here and the joined {@code domain} covers
     * the Check and the CheckLevels only. Measured 2026-09-21: no rule in the shipped corpus
     * authors a {@code Precondition} at all, so this arm is a no-op today — which is exactly why it
     * is written rather than assumed. A cursor-reading Precondition <em>is</em> constructible
     * ({@code var_exists(varname())} satisfies both {@code isBroadcastVerdictExpr} and
     * {@code DomainScan.existsCall}'s VARIABLE answer), and {@code TokenExpander} copies the
     * template's Precondition onto every minted rule.
     * </p>
     *
     * @param rule
     *            the rule being loaded
     * @param domain
     *            the evaluation domain joined across the Check's levels
     */
    private static void checkExpansionAndCursorAreExclusive(Rule rule,
            net.cumba.corej.core.expr.eval.Domain domain)
    {
        net.cumba.corej.core.model.ExpansionSource allSource = allVariablesExpansionSource(rule);
        if (allSource == null)
        {
            return;
        }
        boolean cursor = domain.varCursor() || preconditionReadsCursor(rule);
        if (!cursor)
        {
            return;
        }
        String error = "[" + ruleId(rule) + "] Expansion over '" + allSource.getJsonValue()
                + "' on a rule whose Check also reads the variable cursor (evaluation domain "
                + domain.label() + ") — the two multiply: one rule per variable, each iterating"
                + " every variable again. Use the expansion OR the cursor (varname(), value(),"
                + " an arity-1 var_* accessor, a vlm_* call, a per-variable $-operation), not both";
        rule.setLoadError(rule.getLoadError() == null ? error : rule.getLoadError() + "; " + error);
    }


    /**
     * Whether the rule's authored {@code Precondition} reads the variable cursor — the third
     * surface {@link #checkExpansionAndCursorAreExclusive} needs and the joined Check domain does
     * not cover. Inferred here rather than read off {@code getPreconditionExpr()}, which
     * {@code raisePrecondition} has not yet populated at this point in the load.
     *
     * @param rule
     *            the rule being loaded
     * @return {@code true} when a Precondition is present and carries a variable cursor
     */
    private static boolean preconditionReadsCursor(Rule rule)
    {
        if (rule.getPrecondition() == null)
        {
            return false;
        }
        try
        {
            net.cumba.corej.core.expr.ast.Expr pre = tryRaiseToExpr(rule.getPrecondition());
            return net.cumba.corej.core.expr.eval.DomainScan
                    .infer(pre, net.cumba.corej.core.expr.eval.BindingDomains.forRule(rule))
                    .varCursor();
        }
        catch (net.cumba.corej.core.expr.RuleDefinitionException
                | net.cumba.corej.core.expr.ExpressionException ex)
        {
            // An unparseable / invalid Precondition is reported by raisePrecondition's own
            // handling; this guard must not turn it into a different error.
            return false;
        }
    }


    /**
     * §3.7 validation of {@code Variable_Universe} against the inferred domain: {@code Define} on a
     * rule whose domain has no VAR cursor is a meaningless configuration and fails loud; a
     * define-item-only attribute read under the {@code Data} universe is legal and lint-surfaced.
     */
    private static void checkVariableUniverse(Rule rule,
            net.cumba.corej.core.expr.eval.Domain domain, net.cumba.corej.core.expr.ast.Expr expr)
    {
        checkExpansionAndCursorAreExclusive(rule, domain);
        net.cumba.corej.core.model.VariableUniverse universe = rule.getVariableUniverse();
        if (universe == net.cumba.corej.core.model.VariableUniverse.DEFINE && !domain.varCursor())
        {
            String error = "[" + ruleId(rule) + "] Variable_Universe: Define on a Check whose"
                    + " evaluation domain " + domain.label() + " has no variable cursor — the"
                    + " universe configures the VAR cursor, so it is meaningless here; drop the"
                    + " field or read the cursor (varname(), a var_* accessor)";
            rule.setLoadError(
                    rule.getLoadError() == null ? error : rule.getLoadError() + "; " + error);
            return;
        }
        if (universe == net.cumba.corej.core.model.VariableUniverse.DEFINE && domain.rowCursor())
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
        if (universe != net.cumba.corej.core.model.VariableUniverse.DEFINE && domain.varCursor()
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


    private static @Nullable String defineItemOnlyAccessor(net.cumba.corej.core.expr.ast.Expr e)
    {
        return switch (e)
        {
        case net.cumba.corej.core.expr.ast.Expr.Call c ->
        {
            if (DEFINE_ITEM_ONLY_ACCESSORS.contains(c.name()) && readsDefineLevel(c))
            {
                yield c.name();
            }
            for (net.cumba.corej.core.expr.ast.Expr a : c.args())
            {
                String hit = defineItemOnlyAccessor(a);
                if (hit != null)
                {
                    yield hit;
                }
            }
            for (net.cumba.corej.core.expr.ast.Expr a : c.kwargs().values())
            {
                String hit = defineItemOnlyAccessor(a);
                if (hit != null)
                {
                    yield hit;
                }
            }
            yield null;
        }
        case net.cumba.corej.core.expr.ast.Expr.And a -> firstDefineItemOnlyAccessor(a.parts());
        case net.cumba.corej.core.expr.ast.Expr.Or o -> firstDefineItemOnlyAccessor(o.parts());
        case net.cumba.corej.core.expr.ast.Expr.Not n -> defineItemOnlyAccessor(n.inner());
        case net.cumba.corej.core.expr.ast.Expr.Binary b ->
        {
            String hit = defineItemOnlyAccessor(b.left());
            yield hit != null ? hit : defineItemOnlyAccessor(b.right());
        }
        case net.cumba.corej.core.expr.ast.Expr.Ref _,net.cumba.corej.core.expr.ast.Expr.Lit _ -> null;
        };
    }


    /**
     * Whether an accessor call reads the DEFINE level: its last positional argument is the level
     * literal {@code "DEFINE"}, or it takes no level at all
     * ({@code define_variable_decode_matches}). {@code var_ccode} is shared with the LIBRARY level
     * and counts only at DEFINE.
     */
    private static boolean readsDefineLevel(net.cumba.corej.core.expr.ast.Expr.Call c)
    {
        if (c.args().isEmpty())
        {
            return true;
        }
        net.cumba.corej.core.expr.ast.Expr last = c.args().get(c.args().size() - 1);
        if (last instanceof net.cumba.corej.core.expr.ast.Expr.Lit lit
                && lit.kind() == net.cumba.corej.core.expr.ast.Expr.LitKind.STRING)
        {
            return "DEFINE".equalsIgnoreCase((String) lit.value());
        }
        return !"var_ccode".equals(c.name());
    }


    private static @Nullable String firstDefineItemOnlyAccessor(
            List<net.cumba.corej.core.expr.ast.Expr> parts)
    {
        for (net.cumba.corej.core.expr.ast.Expr p : parts)
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
     * Whether {@code expr} is a <b>fold-equivalent</b> dataset-broadcast verdict — one the retired
     * {@code CheckConditionOptimizer.partialEvaluateDataset} folded to a constant (one
     * dataset-level violation at row 0), so it can be evaluated once via
     * {@code NativeExprEvaluator.evaluateBroadcast} with bit-for-bit parity. Accepted leaves:
     * <ul>
     * <li>{@code exists(NAME)} / {@code not_exists(NAME)} on a bare reference — rule-type-resolved
     * by the native {@code OperatorRegistry.exists}: dataset presence for a Domain-Presence rule,
     * column presence for the metadata families (the dominant VMC variable-presence shape), with
     * dotted and {@code --}-prefix forms handled in the compiled closure;</li>
     * <li>a {@code $}-variable ({@link net.cumba.corej.core.expr.OperandKind#OPERATION_REF})
     * compared to a literal (e.g. {@code $MIDS_EXISTS == true}, {@code $multiple_race >= 1}) —
     * resolved from the row-independent operation results;</li>
     * <li>literals and the {@code and}/{@code or}/{@code not} combinators of the above.</li>
     * </ul>
     * Any per-row data operand — a bare {@code COLUMN} reference in comparison position, or a
     * non-{@code exists} function call — declines.
     */
    private static boolean isBroadcastVerdictExpr(net.cumba.corej.core.expr.ast.Expr expr)
    {
        return switch (expr)
        {
        case net.cumba.corej.core.expr.ast.Expr.And a -> a.parts().stream()
                .allMatch(RulePackageLoader::isBroadcastVerdictExpr);
        case net.cumba.corej.core.expr.ast.Expr.Or o -> o.parts().stream()
                .allMatch(RulePackageLoader::isBroadcastVerdictExpr);
        case net.cumba.corej.core.expr.ast.Expr.Not n -> isBroadcastVerdictExpr(n.inner());
        case net.cumba.corej.core.expr.ast.Expr.Binary b ->
        {
            boolean factPair = isDatasetPresenceOperand(b.left())
                    && isDatasetPresenceOperand(b.right());
            // A broadcast verdict compared to a BOOL literal (e.g. `var_exists(X) == true`) is
            // itself a broadcast verdict — `== true` / `!= false` is the identity, `== false` /
            // `!= true` the negation. Runtime mirror: BroadcastFold.isDatasetConstantLeaf.
            boolean eq = b.op() == net.cumba.corej.core.expr.ast.Expr.BinOp.EQ
                    || b.op() == net.cumba.corej.core.expr.ast.Expr.BinOp.NEQ;
            boolean boolEqVerdict = eq
                    && ((isBoolLiteral(b.left()) && isBroadcastVerdictExpr(b.right()))
                            || (isBoolLiteral(b.right()) && isBroadcastVerdictExpr(b.left())));
            yield factPair || boolEqVerdict;
        }
        case net.cumba.corej.core.expr.ast.Expr.Call c -> isExistsCall(c)
                || net.cumba.corej.core.expr.eval.BroadcastFold.isDatasetFactBoolCall(c)
                || net.cumba.corej.core.expr.eval.BroadcastFold.isLibraryGateCall(c);
        // A bare boolean reference verdict is only broadcast-safe when it is a $-operation result;
        // a bare COLUMN/DOTTED/WILDCARD reference reads per-row data and declines.
        case net.cumba.corej.core.expr.ast.Expr.Ref r -> r
                .kind() == net.cumba.corej.core.expr.OperandKind.OPERATION_REF;
        case net.cumba.corej.core.expr.ast.Expr.Lit _ -> false;
        };
    }


    /** An {@code exists}/{@code not_exists} call on a single bare reference (the dataset name). */
    private static boolean isExistsCall(net.cumba.corej.core.expr.ast.Expr.Call c)
    {
        return net.cumba.corej.core.expr.eval.BroadcastFold.isExistsCall(c);
    }


    /**
     * A broadcast-safe <b>dataset-fact</b> operand — everything the retired
     * {@code CheckConditionOptimizer.evaluateDatasetLeaf} folded at dataset level (R-P2,
     * {@code plans/done/PLAN-native-engine-residuals.md}). Single source:
     * {@link net.cumba.corej.core.expr.eval.BroadcastFold#isDatasetFactOperand}, shared with the
     * runtime tri-state fold so the load-time flag and the fold can never drift.
     */
    private static boolean isDatasetPresenceOperand(net.cumba.corej.core.expr.ast.Expr e)
    {
        return net.cumba.corej.core.expr.eval.BroadcastFold.isDatasetFactOperand(e);
    }


    /** Whether {@code e} is a BOOL literal ({@code true} or {@code false}). */
    private static boolean isBoolLiteral(net.cumba.corej.core.expr.ast.Expr e)
    {
        return e instanceof net.cumba.corej.core.expr.ast.Expr.Lit lit
                && lit.kind() == net.cumba.corej.core.expr.ast.Expr.LitKind.BOOL;
    }


    /**
     * Raises a Check tree to the {@link net.cumba.corej.core.expr.ast.Expr} IR.
     * {@link net.cumba.corej.core.expr.CheckToExpr#toExpr} is an exhaustive switch over the sealed
     * {@code CheckCondition} whose arms build {@code And} / {@code Or} / {@code Not} nodes or hand
     * back the expression a {@code CheckConditionExpression} already carries, so every loaded rule
     * raises; a tree that is not structurally sound is rejected at deserialisation
     * ({@code CheckConditionDeserializer}) and never reaches this method.
     *
     * <p>
     * ⚑ <b>History (K7 of {@code PLAN-retire-dead-multi-match-lookup}, 2026-09-25).</b> This method
     * used to catch {@code ExpressionException} and answer {@code null} "for a mixed / old-style
     * Check with no faithful expression surface", and its eight call sites carried {@code == null}
     * guards for that answer. The bytecode closure of everything reachable from {@code CheckToExpr}
     * constructs no {@code ExpressionException}, so the catch could not fire, the guards could not
     * be entered, and the two load warnings behind them ("a declared Check level cannot be raised",
     * "the existing Precondition cannot be raised") could not be emitted. The catch, the guards and
     * the source-parsing ratchet that enumerated them were removed together; a throw from here now
     * kills the load loudly, which is the direction D121 / H3 asked for.
     * </p>
     */
    private static net.cumba.corej.core.expr.ast.Expr tryRaiseToExpr(CheckCondition check)
    {
        return net.cumba.corej.core.expr.CheckToExpr.toExpr(check);
    }


    /**
     * Serializes a single {@link Rule} back to its JSON object form using the same mapper that
     * loads rule packs, so the title-case {@code @JsonProperty} keys round-trip faithfully. ⚑ No
     * production caller (the engine serialises rules through {@code TokenExpander}'s own mapper);
     * kept as the round-trip seam of the loader's mapper configuration, which the enum round-trip
     * tests pin ({@code PLAN-retire-dead-multi-match-lookup} U4 / C7).
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
            // R-4.10 / R-4.10a (PLAN-use-case-scope-filter, ruling T1-4): a malformed Use_Case
            // would silently exclude the rule from every run that names a use case.
            validateUseCaseShape(rule, errors);
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
            // `over:` drops the directive, a malformed token collides with a real column name,
            // and a token in Scope.Variables is matched literally by a scope gate that runs
            // BEFORE expansion — the shape that left 25 CDISC-AD rules always-skipped.
            validateExpansionDirectives(rule, errors);
            // G2 (PLAN-expansion-token-delimiters) — on EVERY rule, Expansion or not: a complete
            // `&NAME&` token the rule does not declare would otherwise reach the run as a column
            // literally named `&FOO&`, which the absent-column doctrine evaluates silently.
            validateDeclaredExpansionTokens(rule, errors);
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
            // PLAN-rule-unknown-keys-gate — owner, 2026-09-25: "unknown keys in a rule should
            // always result in a load error". Every other block's collector, walked once, in R2's
            // shape with the key's path. Runs here, on the AUTHORED objects: nothing before this
            // point replaces a bound object (deriveOmittedFields mutates fields), and the JSON
            // round-trip clones (TokenExpander, RuleSpecialiser) happen at
            // generation time, after every load gate.
            validateUnknownKeys(rule, errors);
            // PLAN-rule-unknown-keys-gate §5.7 — the join-key authoring gate the owner ruled for
            // the LOADER (PLAN-join-key-authoring-gate Q3 "arm the ratchet"): every keyed
            // Match_Datasets entry declares its key columns, so a study missing a key SKIPS the
            // rule instead of flooding. The corpus lint JoinKeyDeclarationLintTest is its
            // corpus-side twin; this arm reaches user and external packages too (T1-4 a).
            // ⚠ Not when the Requirements block itself carries an unknown key (review round 4,
            // G3): `All_or_None:` is R2's error with its hint, and judging the declaration
            // against a facet the author misspelt would report the same typo a second time as
            // "no All_Or_None group". One typo, one error; the declaration is judged once the
            // block binds.
            if (!requirementsBlockCarriesUnknownKeys(rule))
            {
                validateJoinKeyDeclarations(rule, errors);
            }
            // Gate R8 — an AUTHORED Precondition. Runs here, i.e. BEFORE
            // injectInlineOperationGates (finishLoad), so it judges the authored document and never
            // the loader's own injected availability terms.
            validateNoAuthoredPrecondition(rule, errors);
            if (!errors.isEmpty())
            {
                // ⚠ APPEND, never overwrite. Every sibling writer of this field appends; this one
                // used to assign, which was latent only because no rule reached here already
                // carrying an error from an EARLIER finishLoad pass (the missing_values polarity
                // rejection, validateMissingValuesPolarity; checkVariableUniverse). Gate R8 made
                // that
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
     * Keys that are not retired are reported by {@link #validateUnknownKeys}, generically and with
     * their path ({@code PLAN-rule-unknown-keys-gate}); until 2026-09-26 they stayed silently
     * dropped. This method keeps the retired spellings because its message names the remedy.
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
     * gates — the exact shape {@code injectInlineOperationGates} writes, and the shape the retired
     * {@code OperationInliner}'s {@code addLibraryPreconditionGate} wrote.
     *
     * <p>
     * ⚠⚠ This is what keeps gate R8 an <b>authoring</b> gate rather than a corpus gate. The
     * assembled {@code rules/} packages load through the same {@code CORPUS} path as authored
     * files, and the retired {@code OperationInliner} baked its gate into them <em>offline</em> —
     * so by the time the loader sees such a package the gate is indistinguishable from an authored
     * value by presence alone, and {@code injectedPreconditionGates} (set only by <em>this
     * process's</em> injection) is null. Recognising the gate shape is the only test that separates
     * the two. It is green in both directions today — both corpora carry zero {@code Precondition}
     * keys — which is precisely why it must be written down rather than left to be discovered the
     * first time an availability-dependent call is inlined into a package.
     * </p>
     *
     * <p>
     * ⚑ An earlier arm answered {@code false} for an unraisable {@code Precondition}; it went with
     * K7 of {@code PLAN-retire-dead-multi-match-lookup}, since {@code tryRaiseToExpr} cannot fail.
     * </p>
     */
    private static boolean isAvailabilityGateOnly(CheckCondition precondition)
    {
        net.cumba.corej.core.expr.ast.Expr raised = tryRaiseToExpr(precondition);
        for (net.cumba.corej.core.expr.ast.Expr term : flattenAnd(raised))
        {
            if (!(term instanceof net.cumba.corej.core.expr.ast.Expr.Call call)
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
     * {@code plans/done/PLAN-scope-requirements-split.md} dropped the {@code Scope.Variables}
     * binding that state is <em>unrepresentable</em>: the retired half binds to nothing and reaches
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
     * {@link net.cumba.corej.core.exec.ScopeMatcher}'s pattern arm compiles
     * {@link java.util.regex.Pattern#CASE_INSENSITIVE} and its literal arm lands on
     * {@code DataTableMeta}'s {@code equalsIgnoreCase} — so folding it is what makes this gate
     * agree with the thing it is guarding. Its sibling {@link #checkMatchDatasetRequirements} (R7)
     * folds identically.
     * </p>
     */
    private static void validateRequirementsShape(Rule rule, List<String> errors)
    {
        // R2's unknown-key arm first, in its own method, so the public composite
        // unknownKeyErrors can run exactly it and nothing else of R2/R3/R4 (review L2 / M2,
        // PLAN-rule-unknown-keys-gate). Same messages, same order as before the split.
        requirementsUnknownKeys(rule, errors);
        Requirements req = rule.getRequirements();
        if (req == null)
        {
            return;
        }
        VariableRequirement vars = req.getVariables();
        if (vars != null)
        {
            // ⛔ No "declares both spellings" arm any more, and deliberately: since phase 5 dropped
            // the Scope.Variables binding, a rule declaring both reaches gate R1
            // (validateRetiredScopeVariables) through Scope's unknown-key collector, which reports
            // the retired half by name. A second arm here could only fire on a state the model can
            // no longer represent.
            checkRequirementEntries(rule, vars.getAll(), "Requirements.Variables.All", errors);
            // ⚠ anyUnion() preserves every entry verbatim — nulls and blanks included — which is
            // what lets this R3 arm keep finding them across ALL groups (§D7).
            checkRequirementEntries(rule, vars.anyUnion(), "Requirements.Variables.Any", errors);
            checkRequirementEntries(rule, vars.getNone(), "Requirements.Variables.None", errors);
            checkRequirementEntries(rule, vars.allOrNoneUnion(),
                    "Requirements.Variables.All_Or_None", errors);
            checkTypeSuffixes(rule, vars, errors);
            checkAnyFacetShape(rule, vars, errors);
            checkAllOrNoneFacetShape(rule, vars, errors);
        }
        checkRequirementEntries(rule, req.getDatasets(), "Requirements.Datasets", errors);
    }


    /**
     * Gate R2's unknown-key arm — {@code Scope}, {@code Scope.Datasets}, {@code Requirements},
     * {@code Requirements.Variables} — extracted so {@link #unknownKeyErrors} can compose it
     * without the entry / type-suffix / facet-shape gates {@link #validateRequirementsShape} also
     * runs. Its requirement-specific wording is deliberately kept: tests pin it, and "is not
     * required" says more than the generic message can.
     */
    private static void requirementsUnknownKeys(Rule rule, List<String> errors)
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
            reportUnknownKeys(rule, "Scope", scope, BoundRuleKeys.SCOPE,
                    scope.getUnknownKeys().stream()
                            .filter(key -> !RETIRED_SCOPE_VARIABLES_KEY.equals(key)).toList(),
                    errors);
            if (scope.getDatasets() != null)
            {
                reportUnknownKeys(rule, "Scope.Datasets", scope.getDatasets(),
                        BoundRuleKeys.DATASET_SCOPE, scope.getDatasets().getUnknownKeys(), errors);
            }
        }
        if (req == null)
        {
            return;
        }
        reportUnknownKeys(rule, "Requirements", req, BoundRuleKeys.REQUIREMENTS,
                req.getUnknownKeys(), errors);
        VariableRequirement vars = req.getVariables();
        if (vars != null)
        {
            reportUnknownKeys(rule, "Requirements.Variables", vars,
                    BoundRuleKeys.VARIABLE_REQUIREMENT, vars.getUnknownKeys(), errors);
        }
    }


    /**
     * R2's message, shared by the four blocks that can carry an unbound key — with the same <i>did
     * you mean</i> hint as every other unknown-key gate (review E2).
     */
    private static void reportUnknownKeys(Rule rule, String where, Object block,
            java.util.Set<String> boundHere, java.util.Collection<String> unknownKeys,
            List<String> errors)
    {
        java.util.Set<String> present = null;
        for (String key : unknownKeys)
        {
            if (present == null)
            {
                present = presentKeys(block); // error path only (review P1)
            }
            errors.add(
                    "[" + ruleId(rule) + "] unknown key '" + key + "' under '" + where
                            + "': it binds to nothing, so whatever it was meant to require is not"
                            + " required" + net.cumba.corej.core.model.KeyHint.clause(key,
                                    boundHere, present, BoundRuleKeys.NEVER_HINTED)
                            + " — check the spelling");
        }
    }

    // ---------------------------------------------------------------------
    // PLAN-rule-unknown-keys-gate — every other block, one walker
    // ---------------------------------------------------------------------

    /** T1-1 (a): the two authoring-provenance keys get a message that names what they are. */
    private static final java.util.Set<String> PROVENANCE_KEYS = java.util.Set.of("Source",
            "Source_Proposed");

    /**
     * Whether a top-level key is already reported <b>by name</b> by another gate — the retired
     * spellings ({@link #validateRetiredUnderscoreKeys}) and the run-threshold spellings
     * ({@link #reportThresholdKeys}) — so {@link #validateUnknownKeys} leaves it to them and each
     * key yields exactly one message. The same report-by-name-then-skip rule R2 applies for R1.
     */
    private static boolean isTopLevelKeyReportedByName(String key)
    {
        return RETIRED_KEYS_RENAMED.containsKey(key) || RETIRED_KEYS_REMOVED.contains(key)
                || THRESHOLD_KEYS.contains(key);
    }


    /**
     * Owner, 2026-09-25 ({@code PLAN-rule-unknown-keys-gate}): <i>"unknown keys in a rule should
     * always result in a load error"</i> — read as <b>every level</b> of a rule.
     *
     * <p>
     * Walks every bound object of the rule that carries an unknown-key collector and reports each
     * collected key with its path, in gate R2's shape: {@code unknown key 'Mesage' under
     * 'Outcome'}, {@code … at the top level of the rule}, {@code … under
     * 'Authorities[1].Standards[0].References[0].Citations[0]'}. A key that equals one bound key of
     * that block ignoring case, or is one edit away, gets {@code did you mean 'X'?} (T1-3 a), and
     * {@code Source} / {@code Source_Proposed} at the top level get the provenance advice (T1-1 a):
     * they are authored metadata the corpus build strips from every released package.
     * </p>
     *
     * <p>
     * Keys already reported <b>by name</b> elsewhere are skipped here, so each key yields one
     * message: the retired and threshold spellings ({@link #isTopLevelKeyReportedByName}),
     * {@code Scope.Variables} (R1) and the {@code Scope} / {@code Scope.Datasets} /
     * {@code Requirements} / {@code Requirements.Variables} blocks (R2,
     * {@link #requirementsUnknownKeys}), and {@code Match_Datasets[]} itself
     * ({@link #checkMatchDatasetKeys}) — whose sided {@code Keys[]} elements are walked here. The
     * {@code Check} grammar's stray keys are parked by {@code RuleCheckDeserializer} and reported
     * by {@link #reportCheckGrammarError}; a stray key beside a {@code Precondition} condition
     * fails the package at parse (T1-5 a, accepted consequence). Parked rules
     * ({@code Executability: "Not Executable"}) are removed before any rule gate runs, so their
     * keys are never reported — Q-1, owner: no (nothing executes them, so nothing is silently
     * wrong).
     * </p>
     *
     * @param rule
     *            the bound rule
     * @param errors
     *            per-rule error accumulator; joined into {@link Rule#setLoadError} by the caller
     */
    static void validateUnknownKeys(Rule rule, List<String> errors)
    {
        reportUnknown(
                rule, null, rule, rule.getUnknownKeys().stream()
                        .filter(key -> !isTopLevelKeyReportedByName(key)).toList(),
                BoundRuleKeys.RULE, errors);
        if (rule.getCore() != null)
        {
            reportUnknown(rule, "Core", rule.getCore(), rule.getCore().getUnknownKeys(),
                    BoundRuleKeys.CORE, errors);
        }
        if (rule.getOutcome() != null)
        {
            reportUnknown(rule, "Outcome", rule.getOutcome(), rule.getOutcome().getUnknownKeys(),
                    BoundRuleKeys.OUTCOME, errors);
        }
        if (rule.getExecutabilityHint() != null)
        {
            reportUnknown(rule, "ExecutabilityHint", rule.getExecutabilityHint(),
                    rule.getExecutabilityHint().getUnknownKeys(), BoundRuleKeys.EXECUTABILITY_HINT,
                    errors);
        }
        if (rule.getGrouping() != null)
        {
            reportUnknown(rule, "Grouping", rule.getGrouping(), rule.getGrouping().getUnknownKeys(),
                    BoundRuleKeys.GROUPING, errors);
        }
        List<net.cumba.corej.core.model.ExpansionDirective> expansion = rule.getExpansion();
        if (expansion != null)
        {
            for (int i = 0; i < expansion.size(); i++)
            {
                if (expansion.get(i) != null)
                {
                    reportUnknown(rule, "Expansion[" + i + "]", expansion.get(i),
                            expansion.get(i).getUnknownKeys(), BoundRuleKeys.EXPANSION, errors);
                }
            }
        }
        Map<String, net.cumba.corej.core.model.WildcardFilter> wildcards = rule.getWildcards();
        if (wildcards != null)
        {
            wildcards.forEach((name, filter) ->
            {
                if (filter != null)
                {
                    reportUnknown(rule, "wildcards." + name, filter, filter.getUnknownKeys(),
                            BoundRuleKeys.WILDCARD_FILTER, errors);
                }
            });
        }
        Scope scope = rule.getScope();
        if (scope != null)
        {
            // Scope itself and Scope.Datasets are R2's (requirementsUnknownKeys).
            if (scope.getClasses() != null)
            {
                reportUnknown(rule, "Scope.Classes", scope.getClasses(),
                        scope.getClasses().getUnknownKeys(), BoundRuleKeys.CLASS_SCOPE, errors);
            }
            if (scope.getDomains() != null)
            {
                reportUnknown(rule, "Scope.Domains", scope.getDomains(),
                        scope.getDomains().getUnknownKeys(), BoundRuleKeys.DOMAIN_SCOPE, errors);
            }
            if (scope.getDataStructures() != null)
            {
                reportUnknown(rule, "Scope.Data_Structures", scope.getDataStructures(),
                        scope.getDataStructures().getUnknownKeys(),
                        BoundRuleKeys.DATA_STRUCTURE_SCOPE, errors);
            }
            if (scope.getSubclasses() != null)
            {
                reportUnknown(rule, "Scope.Subclasses", scope.getSubclasses(),
                        scope.getSubclasses().getUnknownKeys(), BoundRuleKeys.SUBCLASS_SCOPE,
                        errors);
            }
        }
        reportAuthorityKeys(rule, errors);
        List<net.cumba.corej.core.model.MatchDataset> matches = rule.getMatchDatasets();
        if (matches != null)
        {
            for (int i = 0; i < matches.size(); i++)
            {
                net.cumba.corej.core.model.MatchDataset md = matches.get(i);
                if (md == null)
                {
                    continue;
                }
                for (net.cumba.corej.core.model.MatchDataset.StrayElementKey stray : md
                        .strayElementKeys())
                {
                    reportUnknown(rule, "Match_Datasets[" + i + "].Keys[" + stray.index() + "]",
                            stray.present(), List.of(stray.key()), BoundRuleKeys.MATCH_KEY_ELEMENT,
                            errors);
                }
            }
        }
    }


    /** The {@code Authorities[i].Standards[j].References[k]…} tree, every level indexed. */
    private static void reportAuthorityKeys(Rule rule, List<String> errors)
    {
        List<net.cumba.corej.core.model.Authority> authorities = rule.getAuthorities();
        if (authorities == null)
        {
            return;
        }
        for (int i = 0; i < authorities.size(); i++)
        {
            net.cumba.corej.core.model.Authority authority = authorities.get(i);
            if (authority == null)
            {
                continue;
            }
            String at = "Authorities[" + i + "]";
            reportUnknown(rule, at, authority, authority.getUnknownKeys(), BoundRuleKeys.AUTHORITY,
                    errors);
            List<net.cumba.corej.core.model.AuthorityStandard> standards = authority.getStandards();
            if (standards == null)
            {
                continue;
            }
            for (int j = 0; j < standards.size(); j++)
            {
                net.cumba.corej.core.model.AuthorityStandard standard = standards.get(j);
                if (standard == null)
                {
                    continue;
                }
                String atStandard = at + ".Standards[" + j + "]";
                reportUnknown(rule, atStandard, standard, standard.getUnknownKeys(),
                        BoundRuleKeys.AUTHORITY_STANDARD, errors);
                List<net.cumba.corej.core.model.Reference> references = standard.getReferences();
                if (references == null)
                {
                    continue;
                }
                for (int k = 0; k < references.size(); k++)
                {
                    reportReferenceKeys(rule, atStandard + ".References[" + k + "]",
                            references.get(k), errors);
                }
            }
        }
    }


    private static void reportReferenceKeys(Rule rule, String at,
            net.cumba.corej.core.model.@Nullable Reference reference, List<String> errors)
    {
        if (reference == null)
        {
            return;
        }
        reportUnknown(rule, at, reference, reference.getUnknownKeys(), BoundRuleKeys.REFERENCE,
                errors);
        if (reference.getRuleIdentifier() != null)
        {
            reportUnknown(rule, at + ".Rule_Identifier", reference.getRuleIdentifier(),
                    reference.getRuleIdentifier().getUnknownKeys(), BoundRuleKeys.RULE_IDENTIFIER,
                    errors);
        }
        List<net.cumba.corej.core.model.Citation> citations = reference.getCitations();
        if (citations == null)
        {
            return;
        }
        for (int m = 0; m < citations.size(); m++)
        {
            if (citations.get(m) != null)
            {
                reportUnknown(rule, at + ".Citations[" + m + "]", citations.get(m),
                        citations.get(m).getUnknownKeys(), BoundRuleKeys.CITATION, errors);
            }
        }
    }


    /**
     * One message per unknown key, in R2's shape plus the path, the hint and — for the two
     * provenance keys at the top level — the advice that says what they are.
     *
     * @param where
     *            the dotted, indexed path of the block, or {@code null} for the rule's top level
     * @param boundHere
     *            the roster of the block, for the hint
     */
    private static void reportUnknown(Rule rule, @Nullable String where, Object block,
            java.util.Collection<String> keys, java.util.Set<String> boundHere, List<String> errors)
    {
        java.util.Set<String> present = null;
        for (String key : keys)
        {
            if (present == null)
            {
                present = presentKeys(block); // error path only (review P1)
            }
            String hint = net.cumba.corej.core.model.KeyHint.nearest(key, boundHere, present,
                    BoundRuleKeys.NEVER_HINTED);
            String advice = where == null && PROVENANCE_KEYS.contains(key)
                    ? " — Source / Source_Proposed are authoring provenance and are stripped from"
                            + " every released package; delete the key"
                    : " — check the spelling";
            errors.add("[" + ruleId(rule) + "] unknown key '" + key + "' "
                    + (where == null ? "at the top level of the rule" : "under '" + where + "'")
                    + ": it binds to nothing, so whatever it was meant to do is not done"
                    + (hint == null ? "" : "; did you mean '" + hint + "'?") + advice);
        }
    }


    /**
     * The <b>one</b> entry point for "every unknown or stray key of this rule", for a reader of
     * rule JSON that binds with its own mapper and never runs {@code finishLoad} — the corpus
     * repos' rulespec {@code RuleScaffold} (review M2, {@code PLAN-rule-unknown-keys-gate}
     * &#167;5.3). Composes exactly the unknown-key arms and nothing else: R1, R2's unknown-key arm
     * (not the entry / type-suffix / facet-shape gates {@link #validateRequirementsShape} also
     * runs), the {@code Match_Datasets[]} gate, the {@code Check} grammar error parked in
     * {@code Rule.getRawCheckLevels()} (so a stray condition key — which makes the binding drop the
     * condition — can never be swallowed by a reader that ignores that field), the retired and
     * threshold spellings, and {@link #validateUnknownKeys}. The loader keeps its own call sites;
     * this is a composition of the same private methods, not a second implementation.
     *
     * @param rule
     *            a rule bound from JSON (a programmatically built rule has no collected keys)
     * @return the error messages, one per key, in the loader's own wording; empty when clean
     */
    public static List<String> unknownKeyErrors(Rule rule)
    {
        List<String> errors = new ArrayList<>();
        validateRetiredScopeVariables(rule, errors);
        requirementsUnknownKeys(rule, errors);
        checkMatchDatasetKeys(rule, errors);
        reportCheckGrammarError(rule, errors);
        validateRetiredUnderscoreKeys(rule, errors);
        reportThresholdKeys(rule, errors);
        validateUnknownKeys(rule, errors);
        return errors;
    }

    // ---------------------------------------------------------------------
    // PLAN-rule-unknown-keys-gate §5.7 — the join-key declaration gate (owner:
    // PLAN-join-key-authoring-gate Q3)
    // ---------------------------------------------------------------------

    /** The guide heading the join-key messages point at. */
    private static final String JOIN_KEY_GUIDE = "rule-guide authoring/joins.md, 'Declare the key"
            + " — required for every keyed join'";

    /**
     * Every keyed {@code Match_Datasets} entry declares its key columns in
     * {@code Requirements.Variables} — the <b>loader</b> half of the join-key authoring gate
     * ({@code PLAN-join-key-authoring-gate} §6 Q1–Q9, ruled 2026-09-25; carried into
     * {@code PLAN-rule-unknown-keys-gate} §5.7). Without the declaration a study missing a key on
     * one side <b>floods</b> (every primary row fails to match) and a study missing every key on
     * both sides reaches the join's all-absent execution error; with it the rule SKIPS. The shape
     * rules are the corpus lint's ({@code JoinKeyDeclarationLintTest}), and no named exemption
     * exists — every exception is a shape:
     * <ul>
     * <li><b>ordinary entry</b> ({@code Name: DS}, single- or multi-key, any {@code Join_Type},
     * self-joins included): per key column an {@code All_Or_None} group holding the bare key and
     * {@code DS.KEY} (a sided key: {@code LEFT} and {@code DS.RIGHT}) whose every member is a side
     * of THAT key across the rule's ordinary entries (one group may pair one bare key with several
     * joined datasets — Q6 / P1); plus the entry's <b>first</b> key bare in {@code All} (Q9);</li>
     * <li><b>{@code Child: true}</b> (SUPP--, CO, SQ--, RELREC primaries): every key bare in
     * {@code All}, nothing on the parent side, and no group naming the key — unless an ordinary
     * entry of the same rule keys on it too (review round 2 M1 of that plan);</li>
     * <li><b>expansion template</b> ({@code &}-token name or key): the first key bare in
     * {@code All} when it is not a token; token keys are exempt BY CONSTRUCTION — gate R6 bars an
     * expansion token from {@code Requirements.Variables} (Q5).</li>
     * </ul>
     * Entries without {@code Keys} (a RELREC entry, a filter-only join) declare nothing, and so
     * does an <b>ordinary</b> entry without a {@code Name} (no {@code NAME.RIGHT} side exists) — a
     * nameless {@code Child} entry is judged like a named one, since the child pre-merger joins on
     * it. Type suffixes ({@code :N}) are stripped and names compared case-insensitively, as the
     * lint does.
     */
    static void validateJoinKeyDeclarations(Rule rule, List<String> errors)
    {
        List<net.cumba.corej.core.model.MatchDataset> joins = rule.getMatchDatasets();
        if (joins == null)
        {
            return;
        }
        VariableRequirement vars = rule.getRequirements() == null ? null
                : rule.getRequirements().getVariables();
        java.util.Set<String> all = foldedEntries(vars == null ? null : vars.getAll());
        List<java.util.Set<String>> groups = new ArrayList<>();
        if (vars != null && vars.getAllOrNoneGroups() != null)
        {
            vars.getAllOrNoneGroups().forEach(g -> groups.add(foldedEntries(g)));
        }
        for (net.cumba.corej.core.model.MatchDataset md : joins)
        {
            if (md == null)
            {
                continue;
            }
            List<String> left = md.getKeys();
            List<String> right = md.getRightKeys();
            if (left == null || right == null || left.isEmpty() || left.size() != right.size())
            {
                // no keys, or a malformed sided element (checkSidedKeys reports it)
                continue;
            }
            String name = md.getName();
            String where = "[" + ruleId(rule) + "] Match_Datasets '"
                    + (name == null ? "<unnamed>" : name) + "'";
            if (isExpansionTemplateEntry(md))
            {
                String first = left.get(0);
                if (!first.startsWith("&") && !all.contains(foldKey(first)))
                {
                    errors.add(where + ": first key " + first
                            + " is not bare in Requirements.Variables.All — " + JOIN_KEY_GUIDE);
                }
                continue;
            }
            if (Boolean.TRUE.equals(md.getChild()))
            {
                for (String k : left)
                {
                    if (!all.contains(foldKey(k)))
                    {
                        errors.add(where + ": Child key " + k
                                + " is not bare in Requirements.Variables.All — a Child entry's"
                                + " parent is resolved per row, so its keys are declared bare —"
                                + " " + JOIN_KEY_GUIDE);
                    }
                    if (groups.stream().anyMatch(g -> g.contains(foldKey(k)))
                            && !keysAnOrdinaryEntry(joins, k))
                    {
                        errors.add(where + ": Child key " + k
                                + " is named by an All_Or_None group — a Child key is declared"
                                + " bare in All only (no ordinary entry of this rule joins on it) — "
                                + JOIN_KEY_GUIDE);
                    }
                }
                continue;
            }
            if (name == null)
            {
                // An ORDINARY entry with no dataset name has no `NAME.RIGHT` side to declare
                // against — its own gate's business. ⚠ Only ordinary entries are exempt (review
                // round 4, G1): a nameless `Child: true` entry IS joined on —
                // ChildMatchPreMerger.applicableChildKeys falls back to the first Child entry
                // whatever its name — so it was judged above, as the corpus lint judges it.
                continue;
            }
            if (!all.contains(foldKey(left.get(0))))
            {
                errors.add(where + ": first key " + left.get(0)
                        + " is not bare in Requirements.Variables.All — " + JOIN_KEY_GUIDE);
            }
            for (int i = 0; i < left.size(); i++)
            {
                String bare = foldKey(left.get(i));
                String joined = foldKey(name + "." + right.get(i));
                java.util.Set<String> sides = joinSidesOf(joins, left.get(i));
                java.util.Set<String> group = groups.stream()
                        .filter(g -> g.contains(bare) && g.contains(joined)).findFirst()
                        .orElse(null);
                if (group == null)
                {
                    errors.add(where + ": key " + left.get(i)
                            + " has no Requirements.Variables.All_Or_None group holding both "
                            + left.get(i) + " and " + name + "." + right.get(i)
                            + " — every keyed join declares its key, so a study missing it SKIPS"
                            + " the rule instead of flooding — " + JOIN_KEY_GUIDE);
                    continue;
                }
                for (String member : group)
                {
                    if (!sides.contains(member))
                    {
                        errors.add(where + ": the All_Or_None group of key " + left.get(i)
                                + " carries " + member + ", which is not a side of key "
                                + left.get(i) + " — " + JOIN_KEY_GUIDE);
                    }
                }
            }
        }
    }


    /**
     * {@link #validateJoinKeyDeclarations} as a value, for a lane that binds a rule without running
     * {@code finishLoad} (the corpus's {@code RuleScaffold}) — the sibling of
     * {@link #unknownKeyErrors(Rule)}. Empty when every keyed join is declared in the ruled shape.
     *
     * <p>
     * ⚠ It does NOT apply the suppression the loader applies (review round 4, G3: the declaration
     * is not judged while R2 reports an unknown key on {@code Requirements} /
     * {@code Requirements.Variables}), so on a rule with a misspelt facet it reports the
     * declaration as missing. A caller that also runs {@link #unknownKeyErrors(Rule)} should run
     * that first and stop on its errors — as {@code RuleScaffold} does — so one typo stays one
     * error.
     * </p>
     *
     * @param rule
     *            a bound rule
     * @return the §5.7 load errors of the rule, in gate order
     */
    public static List<String> joinKeyDeclarationErrors(Rule rule)
    {
        List<String> errors = new ArrayList<>();
        validateJoinKeyDeclarations(rule, errors);
        return errors;
    }


    /**
     * Whether R2 already reports an unknown key on the rule's {@code Requirements} block or its
     * {@code Variables} block — the blocks {@link #validateJoinKeyDeclarations} reads.
     */
    private static boolean requirementsBlockCarriesUnknownKeys(Rule rule)
    {
        Requirements req = rule.getRequirements();
        if (req == null)
        {
            return false;
        }
        if (!req.getUnknownKeys().isEmpty())
        {
            return true;
        }
        VariableRequirement vars = req.getVariables();
        return vars != null && !vars.getUnknownKeys().isEmpty();
    }


    /** An entry named by an expansion token, or keyed on one ({@code &DOM&}, {@code &DOM&SEQ}). */
    private static boolean isExpansionTemplateEntry(net.cumba.corej.core.model.MatchDataset md)
    {
        List<String> left = md.getKeys();
        List<String> right = md.getRightKeys();
        return String.valueOf(md.getName()).startsWith("&")
                || (left != null && left.stream().anyMatch(k -> k.startsWith("&")))
                || (right != null && right.stream().anyMatch(k -> k.startsWith("&")));
    }


    /**
     * The legal members of a key's group: the bare key, and {@code NAME.RIGHT} for every ordinary
     * entry of the rule that joins on that bare key.
     */
    private static java.util.Set<String> joinSidesOf(
            List<net.cumba.corej.core.model.MatchDataset> joins, String bareKey)
    {
        java.util.Set<String> sides = new java.util.LinkedHashSet<>();
        sides.add(foldKey(bareKey));
        for (net.cumba.corej.core.model.MatchDataset md : joins)
        {
            if (md == null || md.getName() == null || Boolean.TRUE.equals(md.getChild())
                    || isExpansionTemplateEntry(md))
            {
                continue;
            }
            List<String> left = md.getKeys();
            List<String> right = md.getRightKeys();
            if (left == null || right == null)
            {
                continue;
            }
            for (int i = 0; i < Math.min(left.size(), right.size()); i++)
            {
                if (foldKey(left.get(i)).equals(foldKey(bareKey)))
                {
                    sides.add(foldKey(md.getName() + "." + right.get(i)));
                }
            }
        }
        return sides;
    }


    /**
     * Whether an ORDINARY entry (neither Child nor template) of the rule joins on the bare key. A
     * nameless ordinary entry is exempt from the gate AND inert at run time
     * ({@code RuleRunner.buildJoinedDatasets} skips it), so it licenses nothing (review round 5,
     * F1) — the lint's {@code keysAnOrdinaryEntry} reads it the same way.
     */
    private static boolean keysAnOrdinaryEntry(List<net.cumba.corej.core.model.MatchDataset> joins,
            String bareKey)
    {
        for (net.cumba.corej.core.model.MatchDataset md : joins)
        {
            if (md == null || md.getName() == null || Boolean.TRUE.equals(md.getChild())
                    || isExpansionTemplateEntry(md))
            {
                continue;
            }
            List<String> left = md.getKeys();
            if (left != null && left.stream().anyMatch(k -> foldKey(k).equals(foldKey(bareKey))))
            {
                return true;
            }
        }
        return false;
    }


    private static java.util.Set<String> foldedEntries(@Nullable List<String> entries)
    {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        if (entries != null)
        {
            for (String e : entries)
            {
                if (e != null)
                {
                    out.add(foldKey(e));
                }
            }
        }
        return out;
    }


    /** The lint's fold: the type suffix stripped, upper-cased. */
    private static String foldKey(String entry)
    {
        return stripTypeSuffix(entry.trim()).toUpperCase(java.util.Locale.ROOT);
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


    /**
     * Gate <b>R9</b> — the {@code :N} / {@code :C} type suffix
     * ({@code plans/PLAN-variable-type-requirements.md}). Two arms:
     *
     * <ol>
     * <li><b>D1</b> — a type suffix in {@code None} is a load error. {@code None: ["X:N"]} reads as
     * <i>"no NUMERIC X may be present"</i>, which is satisfied when X is absent <b>or</b> when X is
     * character — almost never what an author means, and the opposite of how it reads. ⚠ The
     * ruling's first draft justified this with <i>"{@code None} has zero corpus carriers"</i>,
     * which is <b>false</b>: 43 {@code rules-src} rules and 221 shipped instances carry the facet.
     * What has zero carriers is the <em>suffix</em>, which is why rejecting it costs nothing.</li>
     * <li>A malformed suffix in <b>any</b> facet — {@code X:}, {@code X:Z}, {@code X:NN},
     * {@code X:Numeric}, {@code A:B:C}. ⚠ {@code :Numeric} / {@code :Character} are the near-misses
     * ruling D7 makes likely by accepting {@code :Num} / {@code :Char}, so the message names all
     * four legal tags rather than merely rejecting.</li>
     * </ol>
     *
     * <p>
     * ⛔ A third arm was proposed — an {@code Any} group whose entries differ only by type letter
     * ({@code ["X:N","X:C"]}) — and <b>dropped</b>: {@link #checkAnyFacetShape}'s D3 arm counts
     * distinct entries through {@link #normalizedFacet}, which folds the suffix, so R4 already
     * errors on that shape. A second gate for it would have shipped with a test that could only
     * pass while the fold was absent.
     * </p>
     */
    private static void checkTypeSuffixes(Rule rule, VariableRequirement vars, List<String> errors)
    {
        reportTypeSuffixErrors(rule, vars.getAll(), "Requirements.Variables.All", null, errors);
        reportTypeSuffixErrors(rule, vars.anyUnion(), "Requirements.Variables.Any", null, errors);
        reportTypeSuffixErrors(rule, vars.getNone(), "Requirements.Variables.None",
                "which None does not accept (ruling D1): it would mean \"no variable of that type"
                        + " may be present\", which is also satisfied by a variable of the OTHER"
                        + " type — the opposite of how it reads. Drop the suffix, or express the"
                        + " type demand in All or Any",
                errors);
        // ⭐ PLAN-join-key-pairing: All_Or_None decides PRESENCE — all present or none — and a
        // column present with the wrong type is neither, so a suffix here has no honest reading.
        // Rejected on D1's precedent rather than given a third meaning. The remedy says plainly
        // that there is no conditional type demand: All is right only for the group's first key,
        // which ruling Q9 of the authoring gate already puts there (review rounds 1 L1, 2 E-L3).
        reportTypeSuffixErrors(rule, vars.allOrNoneUnion(), "Requirements.Variables.All_Or_None",
                "which All_Or_None does not accept: the facet decides presence — every entry"
                        + " present or none — and a column present with the wrong type is neither."
                        + " There is no conditional type demand: drop the suffix, and if the"
                        + " entry is the group's first key (which sits in All as well) put the"
                        + " suffix on its All entry",
                errors);
    }


    /**
     * One facet's type-suffix errors.
     *
     * @param facet
     *            the facet's full name, for the message
     * @param suffixRejection
     *            why a well-formed suffix is itself an error in this facet ({@code None}, ruling
     *            D1; {@code All_Or_None}) and the facet's own remedy, or {@code null} where a
     *            suffix is legal
     */
    private static void reportTypeSuffixErrors(Rule rule, @Nullable List<String> entries,
            String facet, @Nullable String suffixRejection, List<String> errors)
    {
        if (entries == null)
        {
            return;
        }
        for (String entry : entries)
        {
            if (entry == null || entry.isBlank())
            {
                continue; // R3's error, not this gate's
            }
            String malformed = ScopeVariableEntry.malformedTypeSuffix(entry);
            if (malformed != null)
            {
                errors.add("[" + ruleId(rule) + "] " + facet + " " + malformed);
            }
            else if (suffixRejection != null && ScopeVariableEntry.hasTypeSuffix(entry))
            {
                errors.add("[" + ruleId(rule) + "] " + facet + " entry '" + entry.trim()
                        + "' carries a type suffix, " + suffixRejection);
            }
        }
    }


    /**
     * R4 — the degenerate {@code Any} shapes and the three facet intersections (ruling Q9). Since
     * {@code Any} became groups (an AND of ORs): the mixed shape is its own error (ruling D2), the
     * two-distinct-entries minimum applies <b>per group</b> (ruling D3), zero groups
     * ({@code Any: []}) stays the unsatisfiable error it always was, a duplicate <b>across</b>
     * groups is a {@code WARNING} and never an error (ruling D4), and the three overlap arms fire
     * against the flat union.
     */
    private static void checkAnyFacetShape(Rule rule, VariableRequirement vars, List<String> errors)
    {
        checkGroupFacetShape(rule, "Any", vars.getAnyGroups(), vars.isAnyMixedShape(),
                "a one-entry group is All with extra ceremony and hides a truncated list, and an"
                        + " empty one is unsatisfiable",
                errors);
        List<String> anyUnion = vars.anyUnion();
        // ⚠⚠ The type suffix is folded in the two None arms. "Present and absent" is a
        // contradiction whatever type is demanded, so `All: ["X:N"]` + `None: ["X"]` must be
        // caught — unfolded it slips straight through.
        //
        // ⛔⛔ The Any×All arm is NEITHER folded nor unfolded: it is DIRECTIONAL, and the first
        // implementation got that wrong in one direction (review round 1, finding 2). Its message
        // is "the Any leg is then already satisfied by the All leg and says nothing", which is a
        // claim about IMPLICATION, and implication has a direction:
        // All: ["X"] + Any: [["X:N","Y"]] → the Any entry demands MORE. Legal, no error.
        // All: ["X:N"] + Any: [["X","Y"]] → the All entry demands more, so the Any entry IS
        // already satisfied. ERROR — and a blanket "never
        // fold" missed it, leaving the gate open exactly
        // where it was supposed to close.
        // All: ["X:N"] + Any: [["X:C","Y"]] → neither implies the other. Not this arm's error.
        reportAnyImpliedByAll(rule, anyUnion, vars.getAll(), errors);
        reportFacetOverlap(rule, "Any", anyUnion, "None", vars.getNone(),
                "the entry would have to be both present and absent", true, errors);
        reportFacetOverlap(rule, "All", vars.getAll(), "None", vars.getNone(),
                "the entry would have to be both present and absent", true, errors);
    }


    /**
     * The group arms of R4, shared by the two grouped facets ({@code Any}, and {@code All_Or_None}
     * since {@code PLAN-join-key-pairing}, whose ruling D1 says <i>"loader checks exactly as Any
     * has them"</i>): the mixed shape (D2), zero groups, fewer than {@link #MIN_ANY_ENTRIES}
     * distinct entries in a group (D3), and the cross-group duplicate warning (D4).
     *
     * @param facet
     *            the facet's key, for the messages
     * @param degenerateWhy
     *            the facet's own reading of a one-entry / empty group, appended to the D3 message
     */
    private static void checkGroupFacetShape(Rule rule, String facet,
            @Nullable List<List<String>> groups, boolean mixedShape, String degenerateWhy,
            List<String> errors)
    {
        String where = "Requirements.Variables." + facet;
        if (mixedShape)
        {
            // D2 is a SHAPE ruling, so the message must say "mixed shape": the parse wraps stray
            // flat entries as singleton groups to survive at all, and without this arm the D3 arm
            // below would report "group N needs at least 2 distinct entries" about a group the
            // author never typed.
            errors.add("[" + ruleId(rule) + "] " + where + " has a mixed shape —"
                    + " flat entries and groups in one array: author it either flat (one group)"
                    + " or as nested groups of entries, not both");
        }
        else if (groups != null)
        {
            if (groups.isEmpty())
            {
                errors.add("[" + ruleId(rule) + "] " + where + " needs at least " + MIN_ANY_ENTRIES
                        + " distinct entries, got 0 (from 0): " + degenerateWhy);
            }
            for (int g = 0; g < groups.size(); g++)
            {
                // ⚠ DISTINCT entries per group, not entries: `["AESEV","AESEV"]` is exactly the
                // degenerate one-column group this arm's own message describes, and counting the
                // raw list let it through. Folded the same way the overlap arms fold, so
                // `["AESEV","aesev"]` — which no consumer can tell apart — is caught too.
                List<String> group = groups.get(g);
                int distinct = normalizedFacet(group).size();
                if (distinct < MIN_ANY_ENTRIES)
                {
                    errors.add("[" + ruleId(rule) + "] " + where + " group " + (g + 1) + " (of "
                            + groups.size() + ") needs at least " + MIN_ANY_ENTRIES
                            + " distinct entries, got " + distinct + " (from " + group.size()
                            + "): " + degenerateWhy);
                }
            }
            warnOnCrossGroupDuplicates(rule, facet, groups);
        }
    }


    /**
     * R4's {@code All_Or_None} arms ({@code PLAN-join-key-pairing}): the group arms exactly as
     * {@code Any}'s, plus two of its own.
     *
     * <ul>
     * <li>An entry shared with {@code None} is guaranteed absent, so its group can only ever be
     * all-absent ({@code None} with extra ceremony): an error.</li>
     * <li>Pattern entries in one group must have the <b>same shape</b> — the same variable half
     * after the qualifier is removed (globs case-folded, since they compile case-insensitively;
     * marker templates and {@code /regex/} exact). The matcher compares pattern entries by the
     * concrete names they resolve to, and {@code [["TRTxxP", "TRTxxPN"]]} can never resolve to
     * equal sets, so it would skip on every conformant ADSL with nothing saying why (review round
     * 1, M3). Fail loud instead; whether such a pair should compare the <em>bound</em> values
     * ({@code xx}) is an open question the plan files.</li>
     * </ul>
     *
     * <p>
     * ⛔ An entry shared with {@code All} is <b>legal</b> — the first version of this gate rejected
     * it, and that contradicted ruling Q9 of {@code PLAN-join-key-authoring-gate}: a join's first
     * key is authored bare in {@code All} <em>and</em> in its group (review round 1, H1). The group
     * is then all-present by construction on its bare side, which is exactly what Q9 wants — the
     * facet still decides the qualified side. An entry shared with {@code Any} is legal too:
     * "present or absent together" and "one of these present" are independent claims.
     * </p>
     */
    private static void checkAllOrNoneFacetShape(Rule rule, VariableRequirement vars,
            List<String> errors)
    {
        List<List<String>> groups = vars.getAllOrNoneGroups();
        checkGroupFacetShape(rule, "All_Or_None", groups, vars.isAllOrNoneMixedShape(),
                "a group of one is always all-present-or-all-absent, so it pairs nothing, and an"
                        + " empty one pairs nothing either",
                errors);
        if (groups != null && !vars.isAllOrNoneMixedShape())
        {
            for (int g = 0; g < groups.size(); g++)
            {
                reportMixedPatternShapes(rule, groups.get(g), g + 1, groups.size(), errors);
            }
        }
        reportFacetOverlap(rule, "All_Or_None", vars.allOrNoneUnion(), "None", vars.getNone(),
                "None already forbids the entry, so the group can only ever be all-absent — move"
                        + " its other entries to None or drop this one from None",
                true, errors);
    }


    /**
     * The same-shape arm of {@link #checkAllOrNoneFacetShape}, for one group.
     *
     * <p>
     * ⚠ Three kinds of pattern, two identities (review rounds 2 E-L1 and 3 R3-2): a <b>glob</b>
     * compiles {@code CASE_INSENSITIVE} and its literal runs are quoted, so two spellings differing
     * only in case are one shape and are folded; a <b>marker template</b> ({@code TRTxxP}) is keyed
     * exactly because its lowercase markers are what make it a template ({@code TRTXXP} is a
     * literal) — the compiled template still matches column names ignoring case (CIT §1) — and a
     * <b>{@code /regex/}</b> carries escapes ({@code \d} vs {@code \D}) and inline flags
     * ({@code (?-i)}) that case-insensitive matching does not neutralise, so both are keyed
     * exactly. And a literal whose fold equals a pattern entry's fold
     * ({@code [["TRTxxP", "ADSL.TRTXXP"]]}) is reported too, naming <em>that</em> pattern entry
     * (R3-1): a mis-cased template is a literal name no dataset carries, so the group could never
     * be all-present and the rule would skip on every conformant ADSL.
     * </p>
     *
     * <p>
     * ⚠ The entry is parsed exactly as {@code ScopeMatcher.resolveEntryNames} parses it — raw, no
     * trim (review round 2, E-L2) — so the gate and the matcher see the same variable half; a
     * leading blank is part of the shape there and is part of the shape here.
     * </p>
     */
    private static void reportMixedPatternShapes(Rule rule, List<String> group, int index,
            int groupCount, List<String> errors)
    {
        Map<String, String> shapes = new LinkedHashMap<>(); // identity -> first spelling
        Map<String, String> patternFolds = new LinkedHashMap<>(); // fold -> first pattern spelling
        Map<String, String> literalFolds = new LinkedHashMap<>(); // fold -> first spelling
        for (String entry : group)
        {
            if (entry == null || entry.isBlank())
            {
                continue; // R3's error
            }
            String variable = ScopeVariableEntry.parse(entry).variable();
            String fold = variable.toUpperCase(java.util.Locale.ROOT);
            try
            {
                if (ScopeMatcher.isPatternEntry(variable))
                {
                    // glob: case-blind, fold; /regex/ and marker template: exact. The regex test
                    // is scopePattern's own, so the two cannot disagree about what a regex is.
                    boolean regex = variable.length() > 2 && variable.startsWith("/")
                            && variable.endsWith("/");
                    boolean glob = !regex && ScopeMatcher.scopePattern(variable) != null;
                    shapes.putIfAbsent(glob ? fold : variable, variable);
                    patternFolds.putIfAbsent(fold, variable);
                }
                else
                {
                    literalFolds.putIfAbsent(fold, variable);
                }
            }
            catch (PatternSyntaxException e)
            {
                // The pattern gate (validateScopePatternEntries) reports the invalid regex.
                LOGGER.log(System.Logger.Level.DEBUG, "invalid regex left to the pattern gate: {0}",
                        entry);
            }
        }
        String where = "[" + ruleId(rule) + "] Requirements.Variables.All_Or_None group " + index
                + " (of " + groupCount + ")";
        if (shapes.size() > 1)
        {
            errors.add(where + " pairs pattern entries of different shape " + shapes.values()
                    + " — the group is compared by the concrete names each pattern resolves to,"
                    + " and differently shaped patterns are not guaranteed to resolve to the same"
                    + " names, so the rule could skip on every dataset; pair the same pattern on"
                    + " each side (qualified as needed), or pair literal names");
        }
        for (Map.Entry<String, String> literal : literalFolds.entrySet())
        {
            String pattern = patternFolds.get(literal.getKey());
            if (pattern != null)
            {
                errors.add(where + " pairs the pattern entry '" + pattern + "' with the literal '"
                        + literal.getValue() + "', which differs from it"
                        + " only in case — a marker template with its markers upper-cased is a"
                        + " literal name no dataset carries, so the group could never be"
                        + " all-present; spell the markers (xx, zz, y, w) in lowercase on both"
                        + " sides");
            }
        }
    }


    /**
     * D4 — the same entry in two different {@code Any} groups is legal (each group stays
     * independently satisfiable) but suspicious enough to say out loud: a {@code WARNING} through
     * {@link #LOGGER}, never an error. Shared with {@code All_Or_None}, where a repeated entry is
     * legal for the same reason ({@code [[A, Y.A], [A, Z.A]]} says A travels with both).
     *
     * <p>
     * ⚠ Not to be confused with the {@code Any}×{@code All} overlap ERROR next door: <b>within</b>
     * {@code Any} a duplicate warns, <b>across</b> {@code Any} and {@code All} it stays an error —
     * the two messages are worded apart on purpose. Duplicates <em>within one group</em> are not
     * this method's business either: the D3 arm's distinct fold already collapses them.
     * </p>
     */
    private static void warnOnCrossGroupDuplicates(Rule rule, String facet,
            List<List<String>> anyGroups)
    {
        if (anyGroups.size() < 2)
        {
            return;
        }
        Map<String, Integer> firstGroupOf = new LinkedHashMap<>();
        Map<String, String> firstEntryOf = new LinkedHashMap<>();
        for (int g = 0; g < anyGroups.size(); g++)
        {
            for (String entry : anyGroups.get(g))
            {
                if (entry == null)
                {
                    continue; // R3's error, not a duplicate
                }
                // ⚑ Folds the tag, like normalizedFacet's distinctness count next door. Review
                // round 1, finding 4: they disagreed about what "the same entry" means, so
                // Any: [["X:N","A"], ["X:C","B"]] warned about nothing while its untagged twin
                // warned. A WARNING is not a gate, but two checks holding different ideas of
                // identity is how one of them later stops checking.
                String normalized = normalizeFacetEntry(stripTypeSuffix(entry));
                Integer first = firstGroupOf.putIfAbsent(normalized, g + 1);
                firstEntryOf.putIfAbsent(normalized, entry.trim());
                if (first != null && first != g + 1)
                {
                    // ⭐ Both channels, per the house idiom at :1219 and :2997 (review F2,
                    // 2026-09-17). The plan said "WARNING via the existing LOGGER" and appears
                    // not to have known setLoadWarning existed: with only the log stream, a
                    // duplicate is invisible to Rule.getLoadWarning() and so to any corpus lint
                    // or report surface that reads load warnings — its single trace would be a
                    // JUL record nobody keeps.
                    // ⚠ Review round 2, finding 3: this said "entry 'X:C' appears in group 1 and
                    // again in group 2", which folding made FALSE — group 1 may hold `X:N`, a
                    // different demand. The warning reaches rule.setLoadWarning() and so any
                    // corpus lint that reads load warnings, so it names the VARIABLE (what the
                    // fold actually found) and quotes both spellings when they differ.
                    String firstSpelling = firstEntryOf.get(normalized);
                    String sameSpelling = entry.trim().equals(firstSpelling) ? ""
                            : " (as '" + firstSpelling + "' and '" + entry.trim() + "')";
                    String warning = "[" + ruleId(rule) + "] Requirements.Variables." + facet
                            + " variable '" + normalized + "'" + sameSpelling + " appears in group "
                            + first + " and again in group " + (g + 1)
                            + " — allowed (ruling D4), each"
                            + " group stays independently satisfiable, but check the duplication"
                            + " is intended";
                    rule.setLoadWarning(rule.getLoadWarning() == null ? warning
                            : rule.getLoadWarning() + "; " + warning);
                    LOGGER.log(System.Logger.Level.WARNING, "{0}", warning);
                }
            }
        }
    }


    /**
     * The {@code Any}×{@code All} overlap arm (R4), <b>directional</b> — see the comment at its
     * call site. An {@code Any} entry is redundant when some {@code All} entry names the same
     * variable and its demand is at least as strong: the {@code All} leg then guarantees the
     * {@code Any} entry, so that entry contributes nothing to its group.
     *
     * @param anyUnion
     *            every {@code Any} entry, flattened across groups
     * @param all
     *            the {@code All} entries
     */
    private static void reportAnyImpliedByAll(Rule rule, List<String> anyUnion,
            @Nullable List<String> all, List<String> errors)
    {
        if (all == null || anyUnion.isEmpty())
        {
            return;
        }
        // variable identity -> the tags All demands for it (null tag = "presence only")
        Map<String, java.util.Set<String>> allTags = new LinkedHashMap<>();
        for (String entry : all)
        {
            if (entry != null)
            {
                allTags.computeIfAbsent(normalizeFacetEntry(stripTypeSuffix(entry)),
                        _ -> new java.util.LinkedHashSet<>()).add(tagKeyOf(entry));
            }
        }
        java.util.Set<String> reported = new java.util.LinkedHashSet<>();
        for (String entry : anyUnion)
        {
            if (entry == null)
            {
                continue;
            }
            String identity = normalizeFacetEntry(stripTypeSuffix(entry));
            java.util.Set<String> demanded = allTags.get(identity);
            String anyTag = tagKeyOf(entry);
            // Implied when All asks for presence only (any tag on the Any side would then be a
            // strictly stronger demand — NOT implied) or when All asks for the very same type.
            boolean implied = demanded != null
                    && (NO_TAG.equals(anyTag) || demanded.contains(anyTag));
            if (implied && reported.add(identity))
            {
                errors.add("[" + ruleId(rule) + "] Requirements.Variables entry '" + entry.trim()
                        + "' appears in both Any and All — the Any leg is then already satisfied"
                        + " by the All leg and says nothing");
            }
        }
    }

    /** Marker for "this entry demands presence only", so it can live in the same set as a tag. */
    private static final String NO_TAG = "";

    /** An entry's type demand as a comparable key: the normalised tag, or {@link #NO_TAG}. */
    private static String tagKeyOf(String entry)
    {
        ScopeVariableEntry parsed = ScopeVariableEntry.parse(entry);
        return parsed.requiredKind() == null ? NO_TAG : parsed.requiredKind().name();
    }

    /** The smallest {@code Any} list that means anything a plain {@code All} does not. */
    private static final int MIN_ANY_ENTRIES = 2;

    private static void reportFacetOverlap(Rule rule, String leftName, @Nullable List<String> left,
            String rightName, @Nullable List<String> right, String why, boolean foldTypeSuffix,
            List<String> errors)
    {
        if (left == null || right == null)
        {
            return;
        }
        java.util.Set<String> rightNormalized = new java.util.LinkedHashSet<>();
        for (String entry : right)
        {
            if (entry != null)
            {
                rightNormalized
                        .add(normalizeFacetEntry(foldTypeSuffix ? stripTypeSuffix(entry) : entry));
            }
        }
        // ⚠ One error per DISTINCT offending entry: since Any flattens through anyUnion() here, an
        // entry duplicated across two Any groups and also present in All would otherwise emit the
        // identical error string twice — noise that reads like two defects.
        java.util.Set<String> reported = new java.util.LinkedHashSet<>();
        for (String entry : left)
        {
            String key = entry == null ? null
                    : normalizeFacetEntry(foldTypeSuffix ? stripTypeSuffix(entry) : entry);
            if (entry != null && rightNormalized.contains(key) && reported.add(key))
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
                // ⭐ Folds the type suffix, so an Any group of ["X:N","X:C"] counts as ONE distinct
                // entry and R4's D3 arm rejects it: that disjunction is exactly "X present", the
                // degenerate one-column group D3 exists to catch, wearing a disguise. This is why
                // R9 needs no arm of its own for that shape.
                normalized.add(normalizeFacetEntry(stripTypeSuffix(entry)));
            }
        }
        return normalized;
    }


    /** One entry's half of {@link #normalizedFacet}. */
    private static String normalizeFacetEntry(String entry)
    {
        return entry.trim().toUpperCase(java.util.Locale.ROOT);
    }


    /**
     * The entry with any well-formed type suffix removed — its <b>variable identity</b>. Used where
     * two entries naming the same variable must count as one: the {@code None} overlap arms and
     * {@link #normalizedFacet}'s distinctness count.
     *
     * <p>
     * ⚠⚠ <b>Corrected after review round 2.</b> This said <i>"⛔ Deliberately NOT used by the
     * {@code Any}×{@code All} arm"</i>, and that is no longer true: {@link #reportAnyImpliedByAll}
     * calls it twice, to derive the variable identity on both sides. What that arm does NOT do is
     * decide on identity <em>alone</em> — it compares the type demands as well, because its message
     * is a claim about implication and implication has a direction. A ⛔-marked prohibition sitting
     * on the method a future arm would reach for is worse than no note.
     * </p>
     */
    private static String stripTypeSuffix(String entry)
    {
        ScopeVariableEntry parsed = ScopeVariableEntry.parse(entry);
        return parsed.isQualified() ? parsed.qualifier() + "." + parsed.variable()
                : parsed.variable();
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
            .of(net.cumba.corej.core.model.VariableUniverse.values())
            .map(net.cumba.corej.core.model.VariableUniverse::getJsonValue)
            .collect(java.util.stream.Collectors.joining(", "));

    /** Valid {@code Executability} values, in declaration order, for the load-error message. */
    private static final String EXECUTABILITY_VALUES = java.util.stream.Stream
            .of(Executability.values()).map(Executability::getJsonValue)
            .collect(java.util.stream.Collectors.joining(", "));

    /** Valid {@code Join_Type} values, in declaration order, for the load-error message. */
    private static final String JOIN_TYPE_VALUES = java.util.stream.Stream
            .of(net.cumba.corej.core.model.JoinType.values())
            .map(net.cumba.corej.core.model.JoinType::getJsonValue)
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
     * {@code LibraryRuleMapper} (until cache P4 retired it) so CDISC-Library-sourced rules failed
     * identically. Appends to a pre-existing {@code loadError} (e.g. an operand-substitution error)
     * instead of clobbering it.
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
        checkJoinAsString(rule, errors);
        checkKeepMissingsOnUngovernedEntry(rule, errors);
        checkChildEntryNames(rule, errors);
        checkSidedKeys(rule, errors);
        checkQualifiedKeys(rule, errors);
        checkQualifiedGroupMembers(rule, errors);
        checkMatchDatasetKeys(rule, errors);
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
        // here it also reached the retired LibraryRuleMapper, which called this method and nothing
        // else.
        checkProviderRequirements(rule, errors);
        // Gate R7 — a Match_Datasets secondary this rule does not declare. Its own channel, NOT
        // `warnings`: see Rule.getRequirementsGapWarning().
        checkMatchDatasetRequirements(rule);
        // Gate 3c (grouped operation ⇒ Rule_Type Record Data) is gone — phase 6 of
        // PLAN-leaf-scope-domain-inference.md: a grouped function answers per row,
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
                "a library_* operand or a library-dependent function", errors);
        checkProviderFlag(rule, "Define", req.getDefine(), derived.define(),
                "a define_* operand or a define-dependent function", errors);
        checkProviderFlag(rule, "Dictionary", req.getDictionary(), derived.dictionary(),
                "a valid_external_dictionary_* / dictionary_has_decode call", errors);
    }


    private static void checkProviderFlag(Rule rule, String name, @Nullable Boolean declared,
            boolean derived, String what, List<String> errors)
    {
        if (declared == null || declared == derived)
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
        List<net.cumba.corej.core.model.MatchDataset> joins = rule.getMatchDatasets();
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
        for (net.cumba.corej.core.model.MatchDataset join : joins)
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
        // PLAN-binding-expressions R6: a compiled binding's own $-references must resolve too — a
        // binding reading an undefined $y is as dangling as the Check doing so.
        java.util.Set<String> inBindings = new java.util.LinkedHashSet<>();
        List<net.cumba.corej.core.model.CompiledBinding> compiledBindings = rule
                .getCompiledBindings();
        if (compiledBindings != null)
        {
            for (net.cumba.corej.core.model.CompiledBinding binding : compiledBindings)
            {
                collectOperandRefs(binding.expression(), inBindings);
            }
        }
        java.util.Set<String> undefined = new java.util.LinkedHashSet<>(inCheck);
        undefined.addAll(inPrecondition);
        undefined.addAll(inBindings);
        if (undefined.isEmpty())
        {
            return;
        }
        for (net.cumba.corej.core.model.CompiledBinding binding : rule.bindingOrder())
        {
            if (binding.name() != null)
            {
                undefined.remove(binding.name());
            }
        }
        // Review round 1, L4: a name AUTHORED in `Bindings:` is not dangling even when its binding
        // did not materialise (a duplicate name, an unnamed or malformed entry) — that failure
        // carries its own load error, and a second "no binding defines it" message
        // misdiagnoses the rule.
        List<net.cumba.corej.core.model.Binding> authored = rule.getBindings();
        if (authored != null)
        {
            for (net.cumba.corej.core.model.Binding binding : authored)
            {
                if (binding != null && binding.getName() != null)
                {
                    undefined.remove(binding.getName());
                }
            }
        }
        if (undefined.isEmpty())
        {
            return;
        }
        boolean anyInCheck = undefined.stream().anyMatch(inCheck::contains);
        boolean anyInPrecondition = undefined.stream().anyMatch(inPrecondition::contains);
        boolean anyInBindings = undefined.stream().anyMatch(inBindings::contains);
        String surface = anyInCheck && anyInPrecondition ? "Check and Precondition"
                : anyInCheck ? "Check" : anyInPrecondition ? "Precondition" : "Bindings";
        if (anyInBindings && (anyInCheck || anyInPrecondition))
        {
            surface = surface + " and Bindings";
        }
        String message = "[" + ruleId(rule) + "] " + surface + " references the operand"
                + (undefined.size() == 1 ? " " : "s ") + String.join(", ", undefined)
                + " which no binding defines: the name never enters the evaluation"
                + " context, so the leaf yields no rows and the rule silently checks nothing";
        rule.setLoadError(
                rule.getLoadError() == null ? message : rule.getLoadError() + "; " + message);
    }

    // ---------------------------------------------------------------------
    // PLAN-dictionary-seeder Phase 6a (D13 item 3) — a dictionary operation naming no type
    // ---------------------------------------------------------------------


    /**
     * Tags a rule that carries a dictionary-dependent call (the registry functions
     * {@code valid_external_dictionary_*} / {@code dictionary_has_decode}, every one a function
     * since wave 3 — no operation needs a dictionary any more) whose
     * {@code external_dictionary_type} is absent, blank or not a static string literal.
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
     * package operations) name a type. This guard protects site/custom rules. (The library-sourced
     * path, while it existed — {@code LibraryRuleMapper}, retired by cache P4 — was wired beside
     * {@link #validateOperationReferences(RulePackage)}.)
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
        // ⚑ Every declared level, plus the Precondition — the same surfaces the wildcard guard
        // walks, for the same reason: an inline operation never reaches rule.getOperations().
        for (CheckCondition level : rule.checkConditions())
        {
            collectInlineTypelessDictionaryOps(level, "the Check", findings);
        }
        collectInlineTypelessDictionaryOps(rule.getPrecondition(), "the Precondition", findings);
        // PLAN-binding-expressions R8: a dictionary call nested in a COMPILED binding is on the
        // inline surface too.
        List<net.cumba.corej.core.model.CompiledBinding> compiled = rule.getCompiledBindings();
        if (compiled != null)
        {
            compiled.forEach(binding -> collectInlineTypelessDictionaryOps(binding.expression(),
                    "binding " + binding.name(), findings));
        }
        if (findings.isEmpty())
        {
            return;
        }
        String message = "[" + ruleId(rule) + "] " + String.join(", ", findings)
                + ": no installed dictionary can ever satisfy a dictionary call whose type is not"
                + " a static, non-blank string literal, so the rule is defective — fix the rule by"
                + " declaring external_dictionary_type as a non-blank string literal; installing"
                + " dictionaries cannot help";
        rule.setLoadError(
                rule.getLoadError() == null ? message : rule.getLoadError() + "; " + message);
    }


    /** Walks a Check/Precondition tree for operations authored inline in a native expression. */
    private static void collectInlineTypelessDictionaryOps(@Nullable CheckCondition condition,
            String where, List<String> findings)
    {
        if (condition == null)
        {
            return;
        }
        switch (condition)
        {
        case CheckConditionAll all -> all.getConditions()
                .forEach(c -> collectInlineTypelessDictionaryOps(c, where, findings));
        case CheckConditionAny any -> any.getConditions()
                .forEach(c -> collectInlineTypelessDictionaryOps(c, where, findings));
        case CheckConditionNot not -> collectInlineTypelessDictionaryOps(not.getCondition(), where,
                findings);
        case net.cumba.corej.core.model.CheckConditionExpression expression -> collectInlineTypelessDictionaryOps(
                expression.expr(), where, findings);
        }
    }


    /**
     * @param where
     *            the surface the call was authored on — {@code "the Check"},
     *            {@code "the Precondition"} or {@code "binding $x"} — so the finding sends the
     *            author to the right line; the message states which of the two defects it is (no
     *            type declared at all, or a type that is not a static string literal)
     */
    private static void collectInlineTypelessDictionaryOps(net.cumba.corej.core.expr.ast.Expr expr,
            String where, List<String> findings)
    {
        // Wave 1 (D-W1-3 (iv)/(v)): ProviderNeeds' typeless view — a registry dictionary function
        // whose external_dictionary_type is absent, unbindable or not a static string literal (no
        // operation needs a dictionary since wave 3). The gate is decided before any row is read,
        // so a type that is not a literal cannot be gated and is a load error.
        for (net.cumba.corej.core.expr.ast.Expr.Call call : net.cumba.corej.core.exec.ProviderNeeds
                .typelessDictionaryCalls(expr))
        {
            String kind = "inline function ";
            // The type is a REQUIRED parameter since wave 1, so a call that binds carries it (the
            // "declares no external_dictionary_type" arm that stood here was unreachable — W3 §8,
            // deleted in runbook W8).
            net.cumba.corej.core.expr.ast.Expr type = java.util.Objects.requireNonNull(
                    boundArgument(call,
                            net.cumba.corej.core.exec.DictionaryFunctions.TYPE_PARAMETER),
                    "a bound dictionary call carries its required external_dictionary_type");
            String defect;
            if (type instanceof net.cumba.corej.core.expr.ast.Expr.Lit lit
                    && lit.kind() == net.cumba.corej.core.expr.ast.Expr.LitKind.STRING
                    && String.valueOf(lit.value()).isBlank())
            {
                // A static string literal that names nothing is its own defect: "binds … to the
                // string literal , not a static string literal" contradicted itself (W1 review
                // round 2, L-4).
                defect = "declares a blank "
                        + net.cumba.corej.core.exec.DictionaryFunctions.TYPE_PARAMETER;
            }
            else
            {
                defect = "binds " + net.cumba.corej.core.exec.DictionaryFunctions.TYPE_PARAMETER
                        + " to " + describeArgument(type) + ", not a static string literal";
            }
            findings.add(kind + call.name() + "(…) in " + where + " " + defect);
        }
    }


    /**
     * The expression bound to {@code parameter} on a registry call, or {@code null} when the call
     * has no descriptor, does not bind, or leaves the parameter absent.
     */
    private static net.cumba.corej.core.expr.ast.@Nullable Expr boundArgument(
            net.cumba.corej.core.expr.ast.Expr.Call call, String parameter)
    {
        net.cumba.corej.core.expr.eval.FunctionDescriptor descriptor = net.cumba.corej.core.expr.eval.FunctionRegistry
                .descriptor(call.name());
        if (descriptor == null)
        {
            return null;
        }
        List<net.cumba.corej.core.expr.eval.Parameter> params = descriptor.parameters();
        try
        {
            List<net.cumba.corej.core.expr.ast.@Nullable Expr> bound = net.cumba.corej.core.expr.eval.ArgumentBinder
                    .bind(descriptor, call);
            for (int i = 0; i < params.size() && i < bound.size(); i++)
            {
                if (parameter.equals(params.get(i).name()))
                {
                    return bound.get(i);
                }
            }
        }
        catch (RuntimeException _)
        {
            return null; // an unbindable call is the compiler's own load error
        }
        return null;
    }


    private static String describeArgument(net.cumba.corej.core.expr.ast.Expr e)
    {
        return switch (e)
        {
        case net.cumba.corej.core.expr.ast.Expr.Ref ref -> "the column reference " + ref.name();
        case net.cumba.corej.core.expr.ast.Expr.Lit lit -> "the "
                + lit.kind().name().toLowerCase(java.util.Locale.ROOT) + " literal " + lit.value();
        case net.cumba.corej.core.expr.ast.Expr.Call c -> "the call " + c.name() + "(…)";
        default -> "an expression";
        };
    }


    /**
     * Collects every {@code $}-prefixed operand reference in a Check tree
     * ({@link net.cumba.corej.core.model.CheckConditionExpression} nodes, possibly under
     * {@code all}/{@code any}/{@code not} composites).
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
        case net.cumba.corej.core.model.CheckConditionExpression expr -> collectOperandRefs(
                expr.expr(), out);
        }
    }


    /**
     * Expression-form operand positions: every {@link net.cumba.corej.core.expr.ast.Expr.Ref}
     * reachable from {@code expr}.
     */
    private static void collectOperandRefs(net.cumba.corej.core.expr.ast.Expr expr,
            java.util.Set<String> out)
    {
        if (expr instanceof net.cumba.corej.core.expr.ast.Expr.Ref ref)
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
        if (!net.cumba.corej.core.exec.StudyRuleClassifier.hasUnrestrictedScope(rule))
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
     * {@code LibraryRuleMapper} (until cache P4 retired it) so CDISC-Library-sourced rules derived
     * identically.
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
                ? net.cumba.corej.core.model.OutputVariableToken
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
     * ({@link net.cumba.corej.core.model.OutputVariableToken}). Five checks, every one a
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
                net.cumba.corej.core.model.OutputVariableToken.includes(authored));
        java.util.Set<String> derived = null;
        for (String entry : authored)
        {
            if (!net.cumba.corej.core.model.OutputVariableToken.isExclusion(entry))
            {
                continue;
            }
            String malformed = net.cumba.corej.core.model.OutputVariableToken.malformed(entry);
            if (malformed != null)
            {
                errors.add(malformed);
                continue;
            }
            String name = net.cumba.corej.core.model.OutputVariableToken.name(entry);
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
        net.cumba.corej.core.model.GroupingSpec block = rule.getGrouping();
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
        reportCheckGrammarError(rule, errors);
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
        reportThresholdKeys(rule, errors);
    }


    /**
     * The {@code Check:} grammar violation {@code RuleCheckDeserializer} parked on the rule (a
     * mixed level map, an unknown level, a stray condition key), reported per rule. Shared by
     * {@link #checkCheckLevels} and {@link #unknownKeyErrors}.
     */
    private static void reportCheckGrammarError(Rule rule, List<String> errors)
    {
        String grammar = rule.getRawCheckLevels();
        if (grammar != null)
        {
            errors.add("[" + ruleId(rule) + "] " + grammar);
        }
        String precondition = rule.getRawPreconditionError();
        if (precondition != null)
        {
            errors.add("[" + ruleId(rule) + "] " + precondition);
        }
    }


    /**
     * The bound JSON keys a model object already carries (a non-null value behind a Jackson-known
     * property), so the hint never proposes one of them (review E3: {@code {"left": …, "lfet": …}}
     * must not hint {@code left}). A {@link java.util.Set} is taken as the keys themselves (the
     * JSON-node shapes hand their own field names in); a bean is asked through the mapper's
     * serialisation introspection, so Lombok getters, {@code @JsonGetter}s and aliases all count —
     * non-public accessors included ({@code MatchDataset.keysNode},
     * {@code VariableRequirement.writeAny}: review P2, they were read as absent before). ⚠ Read
     * only on the error path: every caller asks on the FIRST unknown key it reports, never before
     * (review P1 — computed eagerly it was 69 % of a clean corpus load);
     * {@link #PRESENT_KEYS_INTROSPECTIONS} counts the bean introspections so a test can pin that a
     * clean load performs none.
     */
    static java.util.Set<String> presentKeys(Object block)
    {
        java.util.Set<String> present = new java.util.HashSet<>();
        if (block instanceof java.util.Set<?> names)
        {
            names.forEach(n -> present.add(String.valueOf(n)));
            return present;
        }
        PRESENT_KEYS_INTROSPECTIONS.increment();
        // ⚠ The gate runs after deriveOmittedFields and the composite after normalizeJoinTypes
        // too: a value those passes STAMP is not one the author wrote, so it
        // must not hide the hint for a misspelt Sensitivity / Join_Type. Exactly the two stamped
        // keys are excluded (deriveOmittedFields → Rule.setSensitivity; normalizeJoinTypes →
        // MatchDataset.setJoinType); every other bound value is authored.
        java.util.Set<String> stamped = block instanceof Rule ? java.util.Set.of("Sensitivity")
                : block instanceof net.cumba.corej.core.model.MatchDataset
                        ? java.util.Set.of("Join_Type")
                        : java.util.Set.of();
        com.fasterxml.jackson.databind.BeanDescription bd = MAPPER.getSerializationConfig()
                .introspect(MAPPER.constructType(block.getClass()));
        for (com.fasterxml.jackson.databind.introspect.BeanPropertyDefinition p : bd
                .findProperties())
        {
            com.fasterxml.jackson.databind.introspect.AnnotatedMember accessor = p.getAccessor();
            if (accessor == null)
            {
                continue;
            }
            try
            {
                accessor.fixAccess(true);
                if (accessor.getValue(block) != null && !stamped.contains(p.getName()))
                {
                    present.add(p.getName());
                    for (com.fasterxml.jackson.databind.PropertyName alias : p.findAliases())
                    {
                        present.add(alias.getSimpleName());
                    }
                }
            }
            catch (IllegalArgumentException | IllegalStateException e)
            {
                // an accessor Jackson cannot read on this instance — not a present key
                LOGGER.log(System.Logger.Level.DEBUG, "presentKeys: {0}", e.toString());
            }
        }
        return present;
    }

    /**
     * How many bean introspections {@link #presentKeys} has performed in this JVM — the instrument
     * behind {@code UnknownKeysGateTest.aCleanLoadNeverIntrospectsForHints} (review P1).
     */
    static final java.util.concurrent.atomic.LongAdder PRESENT_KEYS_INTROSPECTIONS = new java.util.concurrent.atomic.LongAdder();

    /**
     * The nine run-threshold spellings found among a rule's unknown keys, each by name. Shared by
     * {@link #checkCheckLevels} and {@link #unknownKeyErrors}; {@link #validateUnknownKeys} skips
     * them so each is reported once.
     */
    private static void reportThresholdKeys(Rule rule, List<String> errors)
    {
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
     * later. ⚑ Its original motive is gone: the null used to be what kept
     * {@code RuleCohortGrouper}'s equality-cohort path reachable for the generated
     * {@code CDISC-AD0591-<domain>-<var>} family ({@code Fix #233} / EC-74), and that grouper is
     * retired ({@code PLAN-retire-cohort-runner.md}). The rule stands on its own terms: absence
     * means "not authored", and {@code normalizeJoinTypes} is the only thing entitled to decide
     * what it becomes.
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
        List<net.cumba.corej.core.model.MatchDataset> matches = rule.getMatchDatasets();
        if (matches == null)
        {
            return;
        }
        for (net.cumba.corej.core.model.MatchDataset md : matches)
        {
            if (md == null)
            {
                continue;
            }
            String raw = md.getJoinType();
            if (net.cumba.corej.core.model.JoinType.isAbsent(raw)
                    || net.cumba.corej.core.model.JoinType.fromJson(raw) != null)
            {
                continue;
            }
            errors.add("[" + ruleId(rule) + "] Invalid Join_Type '" + raw
                    + "' on Match_Datasets entry '" + md.getName() + "' — expected one of: "
                    + JOIN_TYPE_VALUES);
        }
    }

    /**
     * The JSON keys a {@code Match_Datasets} entry binds — the set the unknown-key message quotes.
     * Pinned against {@code MatchDataset}'s bound fields, and each key against its production
     * reader, by {@code MatchDatasetBoundKeysRosterTest}: a key bound with no reader is the
     * {@code Wildcard} defect again.
     */
    static final List<String> MATCH_DATASET_KEYS = List.of("Name", "Keys", "Child", "Join_Type",
            "Join_As_String", "keep_missings", "Filter");

    /**
     * {@code Match_Datasets.Wildcard}: bound since the monorepo and read by nothing; retired
     * 2026-09 ({@code PLAN-match-datasets-wildcard}, T1-1 A).
     */
    private static final String RETIRED_MATCH_DATASET_WILDCARD = "Wildcard";

    /**
     * Tags every key under a {@code Match_Datasets} entry that bound to no modelled property
     * ({@code PLAN-match-datasets-wildcard}, owner 2026-09-25: T1-1 A, T1-2 (b), T1-3 yes).
     *
     * <p>
     * ⭐⭐ <b>A load error, not silence.</b> The mapper runs with {@code FAIL_ON_UNKNOWN_PROPERTIES}
     * disabled, so before this gate {@code Join_type: "left"} was dropped at parse and
     * {@link #normalizeJoinTypes} stamped {@code inner} — the rows the author meant to keep were
     * removed with nothing reported; {@code Keep_Missings: false} kept, {@code filter} filtered
     * nothing. {@code Scope}, {@code Scope.Datasets}, {@code Requirements} and
     * {@code Requirements.Variables} already rejected unknown keys (gate R2);
     * {@code Match_Datasets} was the one authored block that did not. Same shape as R2's message.
     * </p>
     *
     * <p>
     * ⛔ {@code Wildcard} is reported as <b>retired</b>, once, and never also as unknown. T1-2 was
     * ruled (b): an ERROR from the start, no warning phase — which is safe only because the engine
     * carrying this gate is <b>not released</b> (meta-repo tag) until the corpus re-released
     * without the key is tagged and pinned in all five bundles (the pinned v0.4.0 carries it 17
     * times, on three released rules). The match is case-sensitive, like every key:
     * {@code wildcard} is an ordinary unknown key.
     * </p>
     *
     * <p>
     * ⚑ Runs in {@link #validateEnumFields(Rule)}, i.e. on the <b>authored</b> entries, before any
     * copy of a {@code MatchDataset}: {@code TokenExpander}'s JSON round trip (which cannot carry
     * the {@code @JsonIgnore} collector) and {@code RuleSpecialiser}'s copy both happen at
     * execution time. Kept a separate method so {@code PLAN-rule-unknown-keys-gate} can compose it
     * into its one unknown-key walker unchanged.
     * </p>
     *
     * @param rule
     *            the rule to check.
     * @param errors
     *            the collector to append to.
     */
    private static void checkMatchDatasetKeys(Rule rule, List<String> errors)
    {
        List<net.cumba.corej.core.model.MatchDataset> matches = rule.getMatchDatasets();
        if (matches == null)
        {
            return;
        }
        for (int i = 0; i < matches.size(); i++)
        {
            net.cumba.corej.core.model.MatchDataset md = matches.get(i);
            if (md == null)
            {
                continue;
            }
            String where = "'Match_Datasets[" + i + "]'"
                    + (md.getName() == null ? "" : " (entry '" + md.getName() + "')");
            for (String key : md.getUnknownKeys())
            {
                if (RETIRED_MATCH_DATASET_WILDCARD.equals(key))
                {
                    errors.add("[" + ruleId(rule) + "] retired key 'Wildcard' under " + where
                            + ": the engine never read it. A RELREC.<column> read takes the bound"
                            + " related record's column by its literal name, and"
                            + " RELREC.**<suffix> resolves per related record — delete the key");
                }
                else
                {
                    errors.add("[" + ruleId(rule) + "] unknown key '" + key + "' under " + where
                            + ": it binds to nothing, so whatever it was meant to do is not done"
                            + net.cumba.corej.core.model.KeyHint.clause(key,
                                    BoundRuleKeys.MATCH_DATASET, presentKeys(md),
                                    java.util.Set.of())
                            + " — check the spelling (an entry binds "
                            + String.join(", ", MATCH_DATASET_KEYS) + ")");
                }
            }
        }
    }


    /**
     * Tags a {@code Match_Datasets} entry whose {@code Keys} carry a <b>half-declared sided
     * element</b> with a load error ({@code PLAN-join-key-type-identity}, review round 2).
     *
     * <p>
     * ⚠⚠ A sided key is {@code {"left": "A", "right": "B"}}. {@code MatchDataset.getKeys()} and
     * {@code getRightKeys()} each <b>skip</b> an object element that lacks their side, so
     * {@code {"left": "A"}} alone makes the two lists different LENGTHS while
     * {@code hasSidedKeys()} still answers {@code true}. Every consumer indexes them in lockstep,
     * so the shape is unusable rather than merely odd — and before this check nothing rejected it
     * at any layer.
     * </p>
     *
     * <p>
     * ⚑ No shipped rule uses the sided shape at all (measured 2026-09-22: all 252 key occurrences
     * across 214 entries are bare strings), so this cannot reject anything that exists today.
     * </p>
     *
     * @param rule
     *            the rule to check.
     * @param errors
     *            the collector to append to.
     */
    private static void checkSidedKeys(Rule rule, List<String> errors)
    {
        List<net.cumba.corej.core.model.MatchDataset> matches = rule.getMatchDatasets();
        if (matches == null)
        {
            return;
        }
        for (net.cumba.corej.core.model.MatchDataset md : matches)
        {
            if (md == null)
            {
                continue;
            }
            // ⚑ Round 3, L2 — a WELL-FORMED sided key on a Child/RELREC/SUPP entry passes every
            // check above and is still wrong: ChildMatchPreMerger reads md.getKeys() (the LEFT
            // names) on both sides, so the SUPP pivot would match on the wrong child columns.
            // Unreachable in practice — the pivot is defined on SDTM-standard names that are
            // identical on both sides by construction, and no corpus entry is sided at all — but
            // un-guarded in principle, and one line closes the family.
            // ⚠⚠ textCarriedForeignKeyFamily, NOT excludedFromKeyTypeCheck: the wider predicate
            // also answers true for a NAMELESS entry, which is none of these families. Asking it
            // here would blame Child/RELREC/SUPP for an entry that has no Name -- the very defect
            // corrected in checkJoinAsStringOnExcludedEntry below, written twice in one change.
            if (md.hasSidedKeys()
                    && net.cumba.corej.core.exec.JoinKeyTypes.textCarriedForeignKeyFamily(md))
            {
                errors.add("[" + ruleId(rule) + "] Match_Datasets entry '" + md.getName()
                        + "' combines sided Keys with a Child / RELREC / SUPP-- entry. That family"
                        + " pivots on the standard IDVAR/IDVARVAL names, which are the same on both"
                        + " sides, and its merge reads the left names only — a sided declaration"
                        + " there would be silently ignored.");
                continue;
            }
            String malformed = md.malformedKeyElement();
            if (malformed == null)
            {
                continue;
            }
            errors.add("[" + ruleId(rule) + "] Match_Datasets entry '" + md.getName()
                    + "' has a malformed Keys element: " + malformed
                    + ". The two sides are indexed in lockstep, so an element either side cannot"
                    + " read is unusable — not merely odd");
        }
    }


    /**
     * The gate of a <b>qualified</b> {@code Match_Datasets} key ({@code PLAN-rprfdy-offset-tp-join}
     * C1; owner 2026-09-29: <i>"I would like to be able to give a qualified variable like
     * DM.RPATHCD in the join key … The joined data set needs the same variable without the domain
     * qualification, so DM.RPATHCD joins on RPATHCD."</i>). {@code Keys: ["DM.RPATHCD", "RPHASE"]}
     * on entry {@code TP} reads the record side of its first component from the row's bound
     * {@code DM} record and the joined side from {@code TP.RPATHCD}.
     *
     * <ul>
     * <li><b>D-SIDED</b> — the qualified form is a bare string only; a dotted {@code left} or
     * {@code right} inside a sided element is refused naming the bare spelling.</li>
     * <li><b>D-SRC</b> — the qualifier names an <b>earlier</b> entry of the same rule (never
     * itself, never a later one: the source is bound before this join runs) that is an <b>ordinary,
     * expandable</b> keyed join
     * ({@link net.cumba.corej.core.exec.JoinKeyTypes#governedByKeyTypeCheck}: not
     * {@code Child: true}, not RELREC, not {@code SUPP--}/{@code SQ}, no {@code --} template name,
     * with keys) and an <b>{@code inner}</b> join — an unmatched {@code left} row would have no
     * record-side value, and a {@code ""} key is excluded by the owner.</li>
     * <li>The entry carrying the qualified key is itself expandable (the ordinary keyed join is the
     * only reader of the shape), and neither part of the key carries a {@code --} / {@code &}
     * wildcard (the two sides resolve against different datasets).</li>
     * </ul>
     * Column inventories are data, so an unresolvable source at run time is a rule ERROR there
     * ({@code UnresolvedQualifiedKeyException}, D-ABSENT), not a load error here.
     * {@link #validateJoinKeyDeclarations} judges the declaration of the key like any other:
     * {@code DM.RPATHCD} bare in {@code All} when it is the first key, and one {@code All_Or_None}
     * group holding {@code DM.RPATHCD} and {@code TP.RPATHCD}.
     *
     * @param rule
     *            the rule to check.
     * @param errors
     *            the collector to append to.
     */
    private static void checkQualifiedKeys(Rule rule, List<String> errors)
    {
        List<net.cumba.corej.core.model.MatchDataset> matches = rule.getMatchDatasets();
        if (matches == null)
        {
            return;
        }
        for (int ei = 0; ei < matches.size(); ei++)
        {
            net.cumba.corej.core.model.MatchDataset md = matches.get(ei);
            if (md == null)
            {
                continue;
            }
            String where = "[" + ruleId(rule) + "] Match_Datasets entry '" + md.getName() + "'";
            String dottedSide = md.dottedSidedElement();
            if (dottedSide != null)
            {
                errors.add(where + ": a sided Keys element carries the dotted name " + dottedSide
                        + " — the qualified form is a bare string only: write \"" + dottedSide
                        + "\" as the key element; its joined side is the unqualified name");
            }
            List<String> keys = md.getKeys();
            List<@Nullable String> qualifiers = md.keyQualifiers();
            if (keys == null)
            {
                continue;
            }
            for (int i = 0; i < keys.size() && i < qualifiers.size(); i++)
            {
                String qualifier = qualifiers.get(i);
                if (qualifier == null)
                {
                    continue;
                }
                String key = keys.get(i);
                if (!net.cumba.corej.core.exec.JoinKeyTypes.governedByKeyTypeCheck(md))
                {
                    errors.add(where + ": qualified key " + key + " on an entry the ordinary keyed"
                            + " join does not read (Child: true, RELREC, SUPP--/SQ or a --"
                            + " template name) — a qualified key is read by the ordinary keyed"
                            + " join only");
                    continue;
                }
                if (key.contains("--") || key.contains("&"))
                {
                    errors.add(where + ": qualified key " + key + " carries a -- / & wildcard —"
                            + " the two sides of a qualified key resolve against different"
                            + " datasets, so name the column explicitly");
                    continue;
                }
                String sourceError = qualifiedSourceError(matches, qualifier, key, ei);
                if (sourceError != null)
                {
                    errors.add(where + ": qualified key " + key + " " + sourceError);
                }
            }
        }
    }


    /**
     * D-SRC for a qualified member of a grouped function's {@code group=} (C2) and of the
     * rule-level {@code Grouping} (C3) — {@code PLAN-rprfdy-offset-tp-join}: the qualifier names an
     * ordinary, expandable, {@code inner} entry of the rule ({@link #qualifiedSourceError}), and a
     * qualifier naming NO entry is this gate's load error on both surfaces — Stage A's
     * {@code DOTTED_REF_UNDECLARED} does reach a {@code group=} list member, but it is an UNARMED
     * kind (reported, never parking), and Stage A does not read the grouping at all. The readers'
     * own refusals — a qualified {@code group=} member without {@code domain=} (D-DOMAIN), with
     * {@code regex=} (D-REGEX), with a {@code --} inside — are load errors of the binding / Check
     * compile, before this gate runs.
     *
     * @param rule
     *            the rule to check.
     * @param errors
     *            the collector to append to.
     */
    private static void checkQualifiedGroupMembers(Rule rule, List<String> errors)
    {
        List<net.cumba.corej.core.model.MatchDataset> matches = rule.getMatchDatasets() == null
                ? List.of()
                : rule.getMatchDatasets();
        java.util.Set<String> judged = new java.util.LinkedHashSet<>();
        java.util.function.Consumer<net.cumba.corej.core.expr.ast.Expr> walk = new java.util.function.Consumer<>()
        {

            @Override
            public void accept(net.cumba.corej.core.expr.ast.Expr e)
            {
                switch (e)
                {
                case net.cumba.corej.core.expr.ast.Expr.And a -> a.parts().forEach(this);
                case net.cumba.corej.core.expr.ast.Expr.Or o -> o.parts().forEach(this);
                case net.cumba.corej.core.expr.ast.Expr.Not n -> accept(n.inner());
                case net.cumba.corej.core.expr.ast.Expr.Binary b ->
                {
                    accept(b.left());
                    accept(b.right());
                }
                case net.cumba.corej.core.expr.ast.Expr.Call c ->
                {
                    c.args().forEach(this);
                    c.kwargs().forEach((k, v) ->
                    {
                        if ("group".equals(k)
                                && v instanceof net.cumba.corej.core.expr.ast.Expr.Lit lit
                                && lit.kind() == net.cumba.corej.core.expr.ast.Expr.LitKind.LIST
                                && lit.value() instanceof List<?> items)
                        {
                            for (Object item : items)
                            {
                                if (item instanceof net.cumba.corej.core.expr.ast.Expr.Ref ref
                                        && ref.kind() == net.cumba.corej.core.expr.OperandKind.DOTTED_REF)
                                {
                                    judged.add(ref.name());
                                }
                            }
                        }
                        accept(v);
                    });
                }
                case net.cumba.corej.core.expr.ast.Expr.Lit lit when lit
                        .kind() == net.cumba.corej.core.expr.ast.Expr.LitKind.LIST
                        && lit.value() instanceof List<?> items ->
                {
                    // A list literal's items are expressions too (review round 1, lane 1's C2
                    // pointer): a grouped call inside one is judged like any other.
                    for (Object item : items)
                    {
                        if (item instanceof net.cumba.corej.core.expr.ast.Expr inner)
                        {
                            accept(inner);
                        }
                    }
                }
                default ->
                {
                    // a leaf: nothing below it
                }
                }
            }
        };
        // EVERY declared level, not getCheck() (the strictest alone — Rule.effectiveCheckLevels'
        // warning): a qualified member in a weaker level is judged as strictly (review round 1 of
        // PLAN-rprfdy-offset-tp-join, lane 2 M1 — an undeclared qualifier there ERRORed at run
        // time, a `left` source keyed its unmatched rows on the type default).
        rule.checkConditions().forEach(condition -> walkCheckExpressions(condition, walk));
        if (rule.getCompiledBindings() != null)
        {
            rule.getCompiledBindings().forEach(b -> walk.accept(b.expression()));
        }
        for (String member : judged)
        {
            judgeQualifiedMember(rule, matches, member, "group= member", errors);
        }
        List<String> grouping = rule.effectiveGroupingVariables();
        if (grouping != null)
        {
            for (String member : grouping)
            {
                if (member != null
                        && net.cumba.corej.core.model.MatchDataset.qualifierOf(member) != null)
                {
                    if (member.contains("--") || member.contains("&"))
                    {
                        errors.add("[" + ruleId(rule) + "] Grouping member " + member + " carries"
                                + " a -- / & wildcard — the record side of a qualified member is"
                                + " read from another dataset, so name the column explicitly");
                        continue;
                    }
                    judgeQualifiedMember(rule, matches, member, "Grouping member", errors);
                }
            }
        }
    }


    /**
     * One qualified member against D-SRC.
     */
    private static void judgeQualifiedMember(Rule rule,
            List<net.cumba.corej.core.model.MatchDataset> matches, String member, String where,
            List<String> errors)
    {
        String qualifier = java.util.Objects
                .requireNonNull(net.cumba.corej.core.model.MatchDataset.qualifierOf(member));
        String error = qualifiedSourceError(matches, qualifier, member, -1);
        if (error != null)
        {
            errors.add("[" + ruleId(rule) + "] " + where + " " + member + " " + error);
        }
    }


    /** Every parsed expression of a Check tree, in order. */
    private static void walkCheckExpressions(CheckCondition condition,
            java.util.function.Consumer<net.cumba.corej.core.expr.ast.Expr> visitor)
    {
        switch (condition)
        {
        case CheckConditionAll all -> all.getConditions()
                .forEach(c -> walkCheckExpressions(c, visitor));
        case CheckConditionAny any -> any.getConditions()
                .forEach(c -> walkCheckExpressions(c, visitor));
        case CheckConditionNot not -> walkCheckExpressions(not.getCondition(), visitor);
        case net.cumba.corej.core.model.CheckConditionExpression ce ->
        {
            net.cumba.corej.core.expr.ast.Expr expr = ce.expr();
            if (expr != null)
            {
                visitor.accept(expr);
            }
        }
        }
    }


    /**
     * D-SRC for every qualified-variable surface ({@code PLAN-rprfdy-offset-tp-join}): the reason
     * {@code qualifier} is not a usable source, or {@code null} when it is — an ordinary,
     * expandable, {@code inner} {@code Match_Datasets} entry of the rule, and (when
     * {@code beforeEntry} is non-negative, the C1 case) one declared BEFORE that entry.
     *
     * @param matches
     *            the rule's entries
     * @param qualifier
     *            the qualifier, e.g. {@code DM}
     * @param key
     *            the qualified name as authored, for the message
     * @param beforeEntry
     *            the index of the entry whose key names the source (C1), or {@code -1} for a
     *            {@code group=} / {@code Grouping} member (C2 / C3), which any entry may serve
     * @return the error text after the key, or {@code null}
     */
    static @Nullable String qualifiedSourceError(
            List<net.cumba.corej.core.model.MatchDataset> matches, String qualifier, String key,
            int beforeEntry)
    {
        int s = -1;
        for (int j = 0; j < matches.size(); j++)
        {
            net.cumba.corej.core.model.MatchDataset m = matches.get(j);
            if (m != null && qualifier.equals(m.getName()))
            {
                s = j;
                break;
            }
        }
        String unqualified = key.substring(qualifier.length() + 1);
        if (s < 0)
        {
            return "names no Match_Datasets entry " + qualifier + " of this rule — a qualified"
                    + " variable names where the record-side value comes from: the rule's own"
                    + " entry " + qualifier + ", read at the row's bound " + qualifier + " record";
        }
        if (beforeEntry >= 0 && s == beforeEntry)
        {
            return "names its own entry — the qualifier is where the RECORD-side value comes from"
                    + " (an earlier entry); the joined side is the unqualified " + unqualified;
        }
        if (beforeEntry >= 0 && s > beforeEntry)
        {
            return "names entry " + qualifier + ", which is declared LATER — the source of a"
                    + " qualified key is an earlier entry, bound before this join runs";
        }
        net.cumba.corej.core.model.MatchDataset source = java.util.Objects
                .requireNonNull(matches.get(s));
        if (!net.cumba.corej.core.exec.JoinKeyTypes.governedByKeyTypeCheck(source))
        {
            return "names entry " + qualifier + ", which is not an ordinary keyed join (Child:"
                    + " true, RELREC, SUPP--/SQ, a -- template name, or no Keys) — a qualified"
                    + " variable reads a record bound by the ordinary keyed join";
        }
        String joinType = source.getJoinType();
        if (joinType != null && !net.cumba.corej.core.model.JoinType.INNER.getJsonValue()
                .equalsIgnoreCase(joinType))
        {
            return "names entry " + qualifier + " with Join_Type " + joinType + " — the source of"
                    + " a qualified variable must be an inner join: an unmatched left row has no"
                    + " record-side value, and a \"\" key is excluded";
        }
        return null;
    }


    /**
     * Tags a {@code Join_As_String} that is present but not a boolean with a load error
     * ({@code PLAN-join-key-type-identity}, ruling {@code D4-R3}).
     *
     * <p>
     * ⛔ Deliberately strict: the <b>strings</b> {@code "true"} / {@code "false"} are rejected too,
     * not coerced. See {@link net.cumba.corej.core.model.MatchDataset#hasMalformedJoinAsString()}
     * for why — a flag whose mis-spelling silently takes effect is worse than one that fails.
     * </p>
     *
     * <p>
     * ⚑ The companion check that the flag is <i>usable</i> on this entry is
     * {@link #checkJoinAsStringOnExcludedEntry}, called from the same place.
     * </p>
     *
     * @param rule
     *            the rule to check.
     * @param errors
     *            the collector to append to.
     */
    private static void checkJoinAsString(Rule rule, List<String> errors)
    {
        List<net.cumba.corej.core.model.MatchDataset> matches = rule.getMatchDatasets();
        if (matches == null)
        {
            return;
        }
        for (net.cumba.corej.core.model.MatchDataset md : matches)
        {
            if (md == null || !md.hasMalformedJoinAsString())
            {
                continue;
            }
            errors.add("[" + ruleId(rule) + "] Invalid Join_As_String '" + md.rawJoinAsString()
                    + "' on Match_Datasets entry '" + md.getName()
                    + "' — expected the boolean true or false, unquoted");
        }
        checkJoinAsStringOnExcludedEntry(rule, matches, errors);
    }


    /**
     * Tags a {@code keep_missings: false} authored on an entry the ordinary keyed join does not
     * serve — {@code Child:true} / {@code RELREC} / {@code SUPP--} / {@code SQ*}, a nameless entry,
     * a keyless one ({@code PLAN-hashed-join-arm-absent-columns} review round 1, M1; the sibling of
     * {@link #checkJoinAsStringOnExcludedEntry}, on the same predicate).
     *
     * <p>
     * ⭐⭐ <b>A load error, not silence.</b> The flag is read at exactly one site,
     * {@code KeyMatchRowExpander.keySpec}, reached only through {@code expandableEntries} — i.e.
     * only for an entry {@link net.cumba.corej.core.exec.JoinKeyTypes#governedByKeyTypeCheck}
     * admits. The text-carried family's own merge ({@code ChildMatchPreMerger},
     * {@code RelrecRowExpander}) and the hashed {@code DatasetLookup} the remaining keyed non-Child
     * shapes reach KEEP a blank key unconditionally ({@code JKM R4}'s default) and never consult
     * the flag — and since {@code JKM R7}'s one-side rule landed on the hashed arm too, an absent
     * key column there is present-but-blank on every row, exactly the rows an authored
     * {@code false} claims to drop. An author who writes the flag there believes they have chosen
     * DROP and has not. An authored {@code true} is accepted: it states the default those paths
     * already implement.
     * </p>
     *
     * <p>
     * ⚑ Strictness is free: <b>zero</b> {@code Match_Datasets} entries in either corpus author
     * {@code keep_missings} (measured 2026-09-25).
     * </p>
     *
     * @param rule
     *            the rule to check.
     * @param errors
     *            the collector to append to.
     */
    private static void checkKeepMissingsOnUngovernedEntry(Rule rule, List<String> errors)
    {
        List<net.cumba.corej.core.model.MatchDataset> matches = rule.getMatchDatasets();
        if (matches == null)
        {
            return;
        }
        for (net.cumba.corej.core.model.MatchDataset md : matches)
        {
            // Only the NON-default `false` is refused: an authored `true` is what these paths do
            // anyway (KEEP, JKM R4), so it is harmless — the sibling gate refuses only the flag
            // that would change something and did not (review round 2, L3).
            if (md == null || !Boolean.FALSE.equals(md.getKeepMissings())
                    || net.cumba.corej.core.exec.JoinKeyTypes.governedByKeyTypeCheck(md))
            {
                continue;
            }
            errors.add("[" + ruleId(rule)
                    + "] keep_missings: false has no effect on Match_Datasets entry '"
                    + md.getName() + "' — " + ungovernedWhy(md) + "; this merge keeps blank keys"
                    + " unconditionally (JKM R4), so keep_missings: false has no effect here."
                    + " Remove it.");
        }
    }


    /**
     * Why an entry is outside the ordinary keyed join — shared by the two "has no effect" gates so
     * their messages blame the same half of the entry. ⚠ THREE reasons, not two: a NAMELESS entry
     * also satisfies {@code excludedFromKeyTypeCheck} (its {@code name == null} clause), so a
     * two-way ternary blamed the Child/RELREC/SUPP family for an entry that is none of them.
     *
     * @param md
     *            an entry {@code governedByKeyTypeCheck} rejects.
     * @return the reason, as a clause.
     */
    private static String ungovernedWhy(net.cumba.corej.core.model.MatchDataset md)
    {
        if (md.getName() == null)
        {
            return "the entry has no Name, so nothing resolves a joined dataset for it";
        }
        if (net.cumba.corej.core.exec.JoinKeyTypes.excludedFromKeyTypeCheck(md))
        {
            return "Child / RELREC / SUPP-- entries match a text-carried foreign key against a"
                    + " typed column by design and are not subject to join-key type identity";
        }
        return "the entry declares no Keys, so it builds no key comparison at all";
    }


    /**
     * Tags a {@code Child: true} entry whose {@code Name} is not a concrete dataset name and not a
     * template with a <b>trailing</b> {@code --} such as {@code SUPP--} — a {@code *},
     * {@code ${...}} or {@code &TOKEN&} name, a bare {@code --}, or a {@code --} anywhere but at
     * the end ({@code PLAN-hashed-join-arm-absent-columns} review rounds 1 L1 and 2 L4).
     *
     * <p>
     * A Child entry's name is never resolved to a joined dataset — the pointer join finds the
     * parent per row from {@code RDOMAIN} or the {@code SUPP} prefix. The name is read by exactly
     * two consumers, and neither can bind those shapes: {@code ChildMatchPreMerger} selects the
     * entry whose name equals the primary's, and {@code StageAChecker.entryFor} resolves a dotted
     * qualifier to it exactly or as an instance of its {@code --} template. Any other spelling is
     * an entry no check can ever name and no merge can ever select by name — a load error, so the
     * author learns it now rather than from a silently unselected entry. Zero carriers in either
     * corpus (the 11 Child entries are {@code AE}, {@code CO}, {@code RELREC}, {@code SUPP--}).
     * </p>
     *
     * @param rule
     *            the rule to check.
     * @param errors
     *            the collector to append to.
     */
    private static void checkChildEntryNames(Rule rule, List<String> errors)
    {
        List<net.cumba.corej.core.model.MatchDataset> matches = rule.getMatchDatasets();
        if (matches == null)
        {
            return;
        }
        for (net.cumba.corej.core.model.MatchDataset md : matches)
        {
            if (md == null || !Boolean.TRUE.equals(md.getChild()))
            {
                continue;
            }
            String name = md.getName();
            if (name == null)
            {
                // A nameless Child entry is ACCEPTED, and used: ChildMatchPreMerger takes it as
                // the firstChild fallback for the child-side keys, and no qualifier can name it —
                // there is nothing here to bind wrongly.
                continue;
            }
            // Both consumers must bind every accepted shape: ChildMatchPreMerger's
            // childEntryMatchesPrimary binds a template only as `<prefix>--` (a TRAILING `--`),
            // and StageAChecker.entryFor resolves any `--` — so the loader accepts the narrower
            // of the two (review round 2, L4).
            int dashes = name.indexOf("--");
            boolean trailingTemplate = dashes > 0 && dashes == name.length() - 2;
            boolean concrete = dashes < 0;
            if (!(concrete || trailingTemplate) || name.contains("*") || name.contains("${")
                    || name.contains("&"))
            {
                errors.add("[" + ruleId(rule) + "] Child: true Match_Datasets entry '" + name
                        + "' must name a concrete dataset or a template with a TRAILING -- such"
                        + " as SUPP-- — a Child entry is joined only through its pointer (RDOMAIN"
                        + " / IDVAR / IDVARVAL); its name selects the child-side keys"
                        + " (ChildMatchPreMerger binds `<prefix>--` only) and answers the stage-A"
                        + " qualifier checks, and a `*`, `${}`, `&`, bare `--` or non-trailing"
                        + " `--` name binds in neither");
            }
        }
    }


    /**
     * Tags a {@code Join_As_String: true} authored on an entry the join-key type check does not
     * govern — {@code Child:true} / {@code RELREC} / {@code SUPP--} / {@code SQ*}
     * ({@code PLAN-join-key-type-identity}, ruling {@code D4-R5}).
     *
     * <p>
     * ⭐⭐ <b>A load error, not a warning and not silence.</b> Those entries match a text-carried
     * foreign key against a typed column by design, and their coercion is {@code JKM R6}'s, not
     * this flag's. An author who writes the flag there believes they have controlled that join's
     * type behaviour — and has not. Accepting it silently would be the same defect the strictness
     * in {@link net.cumba.corej.core.model.MatchDataset#hasMalformedJoinAsString()} exists to
     * prevent, one level up: a flag that appears to work and does nothing.
     * </p>
     *
     * <p>
     * ⚑ Strictness is free here: <b>zero</b> corpus entries carry the flag (measured 2026-09-22
     * over all 3 435 rule files), so nothing shipped can trip this.
     * </p>
     *
     * @param rule
     *            the rule to check.
     * @param matches
     *            its {@code Match_Datasets} entries, never {@code null}.
     * @param errors
     *            the collector to append to.
     */
    private static void checkJoinAsStringOnExcludedEntry(Rule rule,
            List<net.cumba.corej.core.model.MatchDataset> matches, List<String> errors)
    {
        for (net.cumba.corej.core.model.MatchDataset md : matches)
        {
            if (md == null || !md.joinKeysAsString()
                    || net.cumba.corej.core.exec.JoinKeyTypes.governedByKeyTypeCheck(md))
            {
                continue;
            }
            // The message must say WHICH way the entry is ungoverned — "has no effect" with no
            // reason sends the author looking at the wrong half of the entry; the three reasons
            // are ungovernedWhy's, shared with the keep_missings gate (review round 2, L2).
            errors.add("[" + ruleId(rule) + "] Join_As_String has no effect on Match_Datasets entry"
                    + " '" + md.getName() + "' — " + ungovernedWhy(md) + ". Remove it.");
        }
    }


    /**
     * Guard-residual disposition ({@code PLAN-native-runtime-guard-residual}, user decision
     * 2026-06-12, option c), re-grounded by phase 6 of {@code PLAN-leaf-scope-domain-inference.md}:
     * a {@code library_dataset_*} / {@code define_dataset_*} operand is resolvable only through the
     * native {@code ds_*} accessor it canonicalises to
     * ({@link net.cumba.corej.core.expr.MetadataOperandMapping}); the phase-2a2 injection that once
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
        case net.cumba.corej.core.model.CheckConditionExpression _ ->
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
        Map<String, net.cumba.corej.core.model.WildcardFilter> filters = rule.getWildcards();
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
                    net.cumba.corej.core.gen.WildcardExpander.collectAvailableCaptureGroups(level));
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
            java.util.Set<String> names = net.cumba.corej.core.gen.WildcardExpander
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
            .of(net.cumba.corej.core.model.ExpansionSource.values())
            .map(net.cumba.corej.core.model.ExpansionSource::getJsonValue)
            .collect(java.util.stream.Collectors.joining(", "));

    /**
     * Fix #147: validates a rule's {@code Expansion:} block. Every rejection here is a shape whose
     * failure mode would otherwise be <b>silent</b> — the rule loads, the gate stays green and the
     * check tests nothing, which is exactly how {@code CDISC-AD0591}/{@code CDISC-AD0898} shipped
     * as no-ops for months.
     *
     * <ul>
     * <li><b>G1 — token present and of the form {@code &NAME&}</b>, {@code NAME = [A-Z][A-Z0-9]*}
     * ({@link net.cumba.corej.core.expr.ExpansionTokens#TOKEN}). CDISC variable names are
     * {@code [A-Z][A-Z0-9]*}; the delimiters are what keep a token from substituting inside a real
     * column name, and the closing {@code &} is what makes {@code &DOM&SEQ} readable as
     * {@code &DOM&} + {@code SEQ}. This subsumes the retired {@code --} check: {@code --} cannot
     * match the form.</li>
     * <li><b>no token declared twice.</b> Two directives with the same token would keep one binding
     * in the substitution map while the expanded id carries both suffixes. (The former "no token is
     * a prefix of another" check is gone: delimited tokens cannot contain each other.)</li>
     * <li><b>{@code over:} present and recognised.</b> An unknown source would otherwise drop the
     * directive and leave the token unsubstituted.</li>
     * <li><b>the source's own operand present</b> ({@code with:} / {@code pattern:}), and the
     * pattern contains the token <b>exactly once</b> — {@code bindDomainFromVariable} captures at
     * the first occurrence, so a second one would silently become literal text.</li>
     * <li><b>G3 — no unterminated or adjacent token</b> anywhere in the rule's rewritten surfaces
     * ({@link net.cumba.corej.core.gen.ExpansionSurfaces}): the retired spelling {@code &VAR} in a
     * string literal, an Output_Variables entry, a Match_Datasets key, a Requirements entry or the
     * {@code pattern:} is caught here, where the lexer cannot see it. Scoped to rules that declare
     * {@code Expansion:}, so HTML-entity prose (an ampersand followed by {@code lt;}) elsewhere
     * never trips it.</li>
     * <li><b>R6 — no token in {@code Requirements.Variables}, {@code Requirements.Datasets} or a
     * {@code Scope} name list.</b> All three are evaluated BEFORE expansion
     * ({@code DatasetRuleResolver} calls {@code describeScopeSkip} then {@code tryExpand}), so the
     * matcher sees the template and would test the token text literally — no such column or
     * dataset, rule skipped for every dataset, never expanded ({@code Requirements.Datasets} joined
     * the gate with K11 of {@code PLAN-scalar-date-extremes}; before it a token there loaded clean
     * and dropped every expansion at run time). R-4.9 is deliberately left untouched.</li>
     * <li><b>no declared token inside a {@code /regex/} literal</b> of a Check level, the
     * Precondition or a binding: a regex literal is never substituted, so the token would survive
     * into every expansion and drop it at run time.</li>
     * <li><b>no engine-owned wildcard markers in the same Check.</b> The two expansion mechanisms
     * are independent walks; combining them on one rule is unimplemented, so reject it at load
     * instead of expanding over one and silently ignoring the other.</li>
     * </ul>
     */
    private static void validateExpansionDirectives(Rule rule, List<String> errors)
    {
        List<net.cumba.corej.core.model.ExpansionDirective> directives = rule.getExpansion();
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
        for (net.cumba.corej.core.model.ExpansionDirective d : directives)
        {
            validateExpansionDirective(d, tokens, errors);
        }
        for (int i = 0; i < tokens.size(); i++)
        {
            for (int j = i + 1; j < tokens.size(); j++)
            {
                if (tokens.get(i).equals(tokens.get(j)))
                {
                    errors.add("Expansion token '" + tokens.get(i) + "' is declared twice");
                }
            }
        }
        validateNoExpansionTokenInScope(rule, tokens, errors);
        validateNoExpansionTokenInRegex(rule, tokens, errors);
        validateTokenSpelling(rule, errors);
        // ⚑ Plan C §3.3: an engine-owned marker in ANY level collides with the Expansion walk.
        if (rule.checkConditions().stream()
                .anyMatch(net.cumba.corej.core.gen.WildcardExpander::hasRealWildcards))
        {
            errors.add("Expansion cannot be combined with the engine-owned wildcard markers"
                    + " (xx/zz/y/w/*) in the same Check — the two expansions are independent walks");
        }
        // The `wildcard*` directives all steer the OTHER mechanism, and DatasetRuleResolver's
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
            net.cumba.corej.core.model.ExpansionDirective directive, List<String> tokens,
            List<String> errors)
    {
        String token = directive.getToken();
        boolean wellFormed = false;
        if (token == null || token.isBlank())
        {
            errors.add("Expansion entry has no 'token'");
        }
        else
        {
            // G1 (PLAN-expansion-token-delimiters, R-5.17). One pattern decides the form; it
            // rejects the sigil-free `VAR`, the retired undelimited `&VAR`, a lower-case name and
            // a `--`-bearing token (R-5.20, now subsumed) alike.
            wellFormed = net.cumba.corej.core.expr.ExpansionTokens.TOKEN.matcher(token).matches();
            if (!wellFormed)
            {
                errors.add("Expansion token '" + token + "' must have the form &NAME& with NAME ="
                        + " [A-Z][A-Z0-9]* (e.g. '&DOM&')");
            }
            tokens.add(token);
        }
        net.cumba.corej.core.model.ExpansionSource over = directive.getOver();
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
            else if (wellFormed)
            {
                // Exactly one scanned occurrence: none captures nothing, two would capture at
                // the first and read the second as literal text (bindDomainFromVariable uses
                // indexOf). A malformed token cannot occur at all, and G1 has already said so.
                long found = net.cumba.corej.core.expr.ExpansionTokens.scan(pattern).occurrences()
                        .stream().filter(o -> o.text().equals(token)).count();
                if (found != 1)
                {
                    errors.add("Expansion pattern '" + pattern + "' must contain its token '"
                            + token + "' exactly once (found " + found + ")");
                }
            }
        }
        // ⚠⚠ This is a switch STATEMENT, not an expression: it is NOT exhaustiveness-checked, so a
        // new ExpansionSource compiles clean here with no arm and NO WARNING, and a stray
        // `with:` / `pattern:` on it would then be silently ignored. These three arms exist
        // precisely because javac will not ask for them — the one guard is the test.
        case ALL_VARIABLES, ALL_NUMERIC_VARIABLES, ALL_CHARACTER_VARIABLES -> rejectUnusedSelectors(
                over, directive, errors);
        }
    }


    /**
     * The {@code all_*} sources enumerate the dataset under validation and take no selector, so a
     * present {@code with:} / {@code pattern:} / {@code known_domain_only:} is an authoring
     * mistake. Rejecting it is not pedantry: the field would otherwise be read by nothing, and the
     * author's intent — a narrowing they believed they had expressed — would vanish without a
     * trace.
     *
     * @param over
     *            the source being validated, named in the message
     * @param directive
     *            the directive to inspect
     * @param errors
     *            load errors, appended to
     */
    private static void rejectUnusedSelectors(net.cumba.corej.core.model.ExpansionSource over,
            net.cumba.corej.core.model.ExpansionDirective directive, List<String> errors)
    {
        String name = over.getJsonValue();
        if (directive.getWith() != null)
        {
            errors.add("Expansion over '" + name + "' does not take a 'with' dataset name");
        }
        if (directive.getPattern() != null)
        {
            errors.add("Expansion over '" + name + "' does not take a 'pattern'");
        }
        if (directive.getKnownDomainOnly() != null)
        {
            errors.add("Expansion over '" + name + "' does not take 'known_domain_only'");
        }
    }


    /**
     * Gate <b>G3</b> ({@code PLAN-expansion-token-delimiters} S6), on rules that declare
     * {@code Expansion:}: every stray {@code &}+letter that is neither a complete {@code &NAME&}
     * token nor part of the {@code &&} operator, and every token adjacent to {@code &} /
     * {@code &&}, anywhere in the rule's rewritten surfaces
     * ({@link net.cumba.corej.core.gen.ExpansionSurfaces}) plus the directives' own
     * {@code pattern:}. This is what catches the retired spelling ({@code &VAR}) where the lexer
     * cannot see it — a string literal, an Output_Variables entry, a Match_Datasets key, a
     * Requirements entry (R6 tests {@code contains("&VAR&")}, which {@code &VAR} no longer
     * satisfies) — and it is scoped to Expansion rules so HTML-entity prose (an ampersand followed
     * by {@code lt;}) and the legacy OSS {@code filter(X="&")} marker never trip it.
     */
    private static void validateTokenSpelling(Rule rule, List<String> errors)
    {
        List<net.cumba.corej.core.gen.ExpansionSurfaces.Surface> surfaces;
        try
        {
            surfaces = new ArrayList<>(net.cumba.corej.core.gen.ExpansionSurfaces.surfaces(rule));
        }
        catch (RuntimeException ex)
        {
            // Fail closed: a surface that cannot be collected is a surface that cannot be gated.
            errors.add("[" + ruleId(rule) + "] could not collect the rule's expansion surfaces: "
                    + ex.getMessage());
            return;
        }
        List<net.cumba.corej.core.model.ExpansionDirective> directives = rule.getExpansion();
        if (directives != null)
        {
            for (int i = 0; i < directives.size(); i++)
            {
                String pattern = directives.get(i).getPattern();
                if (pattern != null)
                {
                    surfaces.add(new net.cumba.corej.core.gen.ExpansionSurfaces.Surface(
                            "Expansion[" + i + "].pattern", pattern));
                }
            }
        }
        for (net.cumba.corej.core.gen.ExpansionSurfaces.Surface surface : surfaces)
        {
            net.cumba.corej.core.expr.ExpansionTokens.Scan scan = net.cumba.corej.core.expr.ExpansionTokens
                    .scan(surface.text());
            for (net.cumba.corej.core.expr.ExpansionTokens.Stray stray : scan.strays())
            {
                // The surface is named by the message itself: "… '&VAR' in
                // Outcome.Output_Variables (a token is …)".
                errors.add("[" + ruleId(rule) + "] " + net.cumba.corej.core.expr.ExpansionTokens
                        .strayMessage(stray.text(), surface.where()));
            }
            for (net.cumba.corej.core.expr.ExpansionTokens.Adjacency adjacency : scan.adjacencies())
            {
                errors.add("[" + ruleId(rule) + "] "
                        + net.cumba.corej.core.expr.ExpansionTokens.ADJACENCY_MESSAGE + " in "
                        + surface.where() + ": '" + adjacency.text() + "'");
            }
        }
    }


    /**
     * Gate <b>G2</b> ({@code PLAN-expansion-token-delimiters} S5), on <b>every</b> rule: each
     * complete {@code &NAME&} token found in a rewritten surface
     * ({@link net.cumba.corej.core.gen.ExpansionSurfaces}) must be a token the rule declares in
     * {@code Expansion:}. A rule without an {@code Expansion:} block may carry no token at all.
     * Without this an undeclared token reaches the run as a column literally named {@code &FOO&},
     * which the absent-column doctrine evaluates silently.
     */
    private static void validateDeclaredExpansionTokens(Rule rule, List<String> errors)
    {
        java.util.Set<String> declared = new java.util.HashSet<>();
        if (rule.getExpansion() != null)
        {
            for (net.cumba.corej.core.model.ExpansionDirective d : rule.getExpansion())
            {
                if (d.getToken() != null)
                {
                    declared.add(d.getToken());
                }
            }
        }
        List<net.cumba.corej.core.gen.ExpansionSurfaces.Surface> surfaces;
        try
        {
            surfaces = net.cumba.corej.core.gen.ExpansionSurfaces.surfaces(rule);
        }
        catch (RuntimeException ex)
        {
            // Fail closed, as validateTokenSpelling does; reported once per rule from here even
            // when that gate did not run (it runs on Expansion rules only).
            if (rule.getExpansion() == null || rule.getExpansion().isEmpty())
            {
                errors.add("[" + ruleId(rule)
                        + "] could not collect the rule's expansion surfaces: " + ex.getMessage());
            }
            return;
        }
        java.util.Set<String> reported = new java.util.HashSet<>();
        for (net.cumba.corej.core.gen.ExpansionSurfaces.Surface surface : surfaces)
        {
            for (net.cumba.corej.core.expr.ExpansionTokens.Occurrence occurrence : net.cumba.corej.core.expr.ExpansionTokens
                    .scan(surface.text()).occurrences())
            {
                if (!declared.contains(occurrence.text())
                        && reported.add(surface.where() + "\u0000" + occurrence.text()))
                {
                    errors.add("[" + ruleId(rule) + "] undeclared expansion token '"
                            + occurrence.text() + "' in " + surface.where()
                            + " (declare it in Expansion:, or remove it)");
                }
            }
        }
    }


    /**
     * Rejects a declared expansion token appearing anywhere in a variable requirement, in the
     * dataset requirement or in a {@code Scope} name list — gate <b>R6</b>, which is
     * {@code Scope.Variables}' original bar <em>re-pointed</em> onto {@code Requirements.Variables}
     * and, since the review of {@code PLAN-expansion-token-delimiters} ((a) L1), onto the
     * {@code Scope} name lists ({@code Domains} / {@code Datasets} / {@code Classes} /
     * {@code Data_Structures} / {@code Subclasses}, {@code Include} and {@code Exclude}) and, since
     * K11 of {@code PLAN-scalar-date-extremes}, onto {@code Requirements.Datasets} as well. See
     * {@link #validateExpansionDirectives} for why this bar exists rather than an R-4.9 relaxation.
     *
     * <p>
     * The surfaces are {@link net.cumba.corej.core.gen.ExpansionSurfaces#gatedOnly} — the one list
     * of gated-but-never-substituted fields, so the message can name the facet or list the author
     * actually wrote and G2 (undeclared token) and R6 (declared token) cannot disagree about what
     * they cover. The reason the bar transfers unchanged is that nothing about it was ever specific
     * to {@code Scope.Variables}: every one of these lists is matched <em>before</em> expansion, so
     * a token there is matched literally and the rule silently never runs.
     * </p>
     */
    private static void validateNoExpansionTokenInScope(Rule rule, List<String> tokens,
            List<String> errors)
    {
        if (tokens.isEmpty())
        {
            return;
        }
        for (net.cumba.corej.core.gen.ExpansionSurfaces.Surface surface : net.cumba.corej.core.gen.ExpansionSurfaces
                .gatedOnly(rule))
        {
            checkNoExpansionToken(rule, surface.text(), surface.where(), tokens, errors);
        }
    }


    /**
     * One entry's half of {@link #validateNoExpansionTokenInScope}.
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
    private static void checkNoExpansionToken(Rule rule, String entry, String where,
            List<String> tokens, List<String> errors)
    {
        for (String token : tokens)
        {
            if (entry.contains(token))
            {
                errors.add(
                        "[" + ruleId(rule) + "] Expansion token '" + token + "' must not appear in "
                                + where + " entry '" + entry + "' — that surface is matched"
                                + " before expansion and would match the token literally,"
                                + " silently skipping the rule for every dataset");
            }
        }
    }


    /**
     * Rejects a declared expansion token inside a {@code /regex/} literal of a Check level, the
     * Precondition or a compiled binding ({@code PLAN-expansion-token-delimiters}, review (a) L2).
     * A regex literal is never substituted ({@code WildcardExpander.substituteLit} skips
     * {@code REGEX}, deliberately — a token's characters are regex metacharacters' neighbours), so
     * such a token passes G2 (it is declared), is left in place by the expander and drops every
     * expansion at run time with a survivor reason. Refused at load, where the author reads it.
     */
    private static void validateNoExpansionTokenInRegex(Rule rule, List<String> tokens,
            List<String> errors)
    {
        if (tokens.isEmpty())
        {
            return;
        }
        for (net.cumba.corej.core.gen.ExpansionSurfaces.NamedCondition named : net.cumba.corej.core.gen.ExpansionSurfaces
                .namedConditions(rule))
        {
            walkCheckExpressions(named.condition(),
                    expr -> rejectTokenInRegex(rule, expr, tokens, named.where(), errors));
        }
        List<net.cumba.corej.core.model.CompiledBinding> bindings = rule.getCompiledBindings();
        if (bindings != null)
        {
            for (int i = 0; i < bindings.size(); i++)
            {
                rejectTokenInRegex(rule, bindings.get(i).expression(), tokens,
                        "Bindings[" + i + "]", errors);
            }
        }
    }


    /** The tree walk of {@link #validateNoExpansionTokenInRegex}: every literal, at any depth. */
    private static void rejectTokenInRegex(Rule rule, net.cumba.corej.core.expr.ast.Expr expr,
            List<String> tokens, String where, List<String> errors)
    {
        switch (expr)
        {
        case net.cumba.corej.core.expr.ast.Expr.Lit lit ->
        {
            if (lit.kind() == net.cumba.corej.core.expr.ast.Expr.LitKind.REGEX)
            {
                String regex = String.valueOf(lit.value());
                for (net.cumba.corej.core.expr.ExpansionTokens.Occurrence occurrence : net.cumba.corej.core.expr.ExpansionTokens
                        .scan(regex).occurrences())
                {
                    if (tokens.contains(occurrence.text()))
                    {
                        errors.add("[" + ruleId(rule) + "] expansion token '" + occurrence.text()
                                + "' inside the regex literal /" + regex + "/ in " + where
                                + " (a regex literal is never substituted)");
                    }
                }
            }
            else if (lit.kind() == net.cumba.corej.core.expr.ast.Expr.LitKind.LIST
                    && lit.value() instanceof List<?> elements)
            {
                for (Object element : elements)
                {
                    if (element instanceof net.cumba.corej.core.expr.ast.Expr e)
                    {
                        rejectTokenInRegex(rule, e, tokens, where, errors);
                    }
                }
            }
        }
        case net.cumba.corej.core.expr.ast.Expr.Ref _ ->
        {
            // a reference carries no literal
        }
        case net.cumba.corej.core.expr.ast.Expr.Call call ->
        {
            call.args().forEach(a -> rejectTokenInRegex(rule, a, tokens, where, errors));
            call.kwargs().values().forEach(a -> rejectTokenInRegex(rule, a, tokens, where, errors));
        }
        case net.cumba.corej.core.expr.ast.Expr.Binary binary ->
        {
            rejectTokenInRegex(rule, binary.left(), tokens, where, errors);
            rejectTokenInRegex(rule, binary.right(), tokens, where, errors);
        }
        case net.cumba.corej.core.expr.ast.Expr.And and -> and.parts()
                .forEach(p -> rejectTokenInRegex(rule, p, tokens, where, errors));
        case net.cumba.corej.core.expr.ast.Expr.Or or -> or.parts()
                .forEach(p -> rejectTokenInRegex(rule, p, tokens, where, errors));
        case net.cumba.corej.core.expr.ast.Expr.Not not -> rejectTokenInRegex(rule, not.inner(),
                tokens, where, errors);
        }
    }


    /**
     * Gate <b>R-4.10 / R-4.10a</b> ({@code plans/PLAN-use-case-scope-filter.md}, ruling T1-4): a
     * {@code Scope.Use_Case} that is not a comma-separated list of upper-case codes, or that lists
     * one code more than once, is a load error.
     *
     * <p>
     * Since the run's use case filters rules (owner ruling X1), a misspelt value is no longer
     * inert: {@code "INDH;PROD"} is one code that matches no use case, so the rule would be
     * excluded — reported {@code SKIPPED}, i.e. "not applicable" — from <em>every</em> run that
     * names one. An empty value ({@code ""}, {@code " "}, {@code ","}) is rejected too, for the
     * reason {@link #validateDomainScopeEntries} rejects an empty domain entry: it names nothing,
     * so it can only be an authoring slip (the matcher reads it as "no use case" and would run the
     * rule everywhere). Codes are read through {@link ScopeMatcher#useCaseCodes}, so gate and
     * matcher agree on what a code is.
     * </p>
     */
    private static void validateUseCaseShape(Rule rule, List<String> errors)
    {
        Scope scope = rule.getScope();
        String raw = scope == null ? null : scope.getUseCase();
        if (raw == null || ScopeMatcher.isWellFormedUseCaseValue(raw))
        {
            return;
        }
        String where = "[" + ruleId(rule) + "] Scope.Use_Case '" + raw + "'";
        List<String> codes = ScopeMatcher.useCaseCodes(raw);
        // ⚠ Review rounds 1-3: a message states only WHAT is wrong and the rule — never a
        // predicted consequence ("matches no use case", "would run everywhere"), because every such
        // claim turned out false for some input. Each optional clause is added only when it is
        // TRUE for this value.
        if (codes.isEmpty())
        {
            errors.add(where + " names no use case (R-4.10: upper-case letter codes,"
                    + " comma-separated, each listed once)");
            return;
        }
        StringBuilder msg = new StringBuilder(where).append(" is not a well-formed value (R-4.10:"
                + " upper-case letter codes, comma-separated, each listed once)");
        List<String> seen = new ArrayList<>();
        for (String code : codes)
        {
            if (seen.contains(code))
            {
                msg.append(" — lists '").append(code).append("' more than once (R-4.10a)");
                break;
            }
            seen.add(code);
        }
        List<String> canonical = new ArrayList<>();
        for (String code : codes)
        {
            String upper = code.toUpperCase(java.util.Locale.ROOT);
            if (!canonical.contains(upper))
            {
                canonical.add(upper);
            }
        }
        String suggestion = String.join(", ", canonical);
        if (ScopeMatcher.isWellFormedUseCaseValue(suggestion) && !suggestion.equals(raw))
        {
            msg.append(" — write '").append(suggestion).append('\'');
        }
        errors.add(msg.toString());
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
     * base ({@code DatasetIdentity.unsplitNameFromData}, from the {@code DOMAIN} column) is the
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
            checkVariableScopeList(requiredVars.anyUnion(), "Requirements.Variables", "Any", rule,
                    errors);
            checkVariableScopeList(requiredVars.getNone(), "Requirements.Variables", "None", rule,
                    errors);
            checkVariableScopeList(requiredVars.allOrNoneUnion(), "Requirements.Variables",
                    "All_Or_None", rule, errors);
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
                    net.cumba.corej.core.metadata.AdamDataStructureDetector.STRUCTURE_TOKENS,
                    "Data_Structures", "Include", rule, errors);
            checkTokenList(scope.getDataStructures().getExclude(),
                    net.cumba.corej.core.metadata.AdamDataStructureDetector.STRUCTURE_TOKENS,
                    "Data_Structures", "Exclude", rule, errors);
        }
        if (scope.getSubclasses() != null)
        {
            checkTokenList(scope.getSubclasses().getInclude(),
                    net.cumba.corej.core.metadata.AdamSubclassDetector.SUBCLASS_TOKENS,
                    "Subclasses", "Include", rule, errors);
            checkTokenList(scope.getSubclasses().getExclude(),
                    net.cumba.corej.core.metadata.AdamSubclassDetector.SUBCLASS_TOKENS,
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
        ScopeVariableEntry parsed = ScopeVariableEntry.parse(entry);
        // ⚠⚠ Test the VARIABLE half, not the raw entry. Review round 1, finding 5: with a tag the
        // raw entry ends in the tag, so `AE.:N` slipped past the trailing-dot rejection — and then
        // parsed UNqualified with the variable `AE.`, which no dataset carries, so the rule loaded
        // clean and skipped on every dataset. Same mistake as H3/F2, one gate over.
        String dotCheck = parsed.isQualified() ? parsed.qualifier() + "." + parsed.variable()
                : parsed.variable();
        boolean wholeEntryRegex = ScopeVariableEntry.isWholeEntryRegex(dotCheck);
        if (!wholeEntryRegex && (dotCheck.startsWith(".") || dotCheck.endsWith(".")))
        {
            errors.add(prefix + " must not start or end with '.'"
                    + " (a qualified entry is DATASET.VARIABLE)");
            return;
        }
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
        case net.cumba.corej.core.model.CheckConditionExpression ce ->
        {
            // The retired generic presence call (owner ruling 1 of
            // PLAN-leaf-scope-domain-inference.md)…
            String generic = genericPresenceCall(ce.expr());
            if (generic != null)
            {
                errors.add(genericPresenceError(ruleId, path, generic));
            }
            // …and the Fix #37 operand-substitution validation, re-homed from the retired
            // operator-leaf walker (phase 7d, D121): a malformed `${…}` template, or a `${*}`
            // wildcard in a position that is not list-aware, is a load error — the compiler's own
            // parse failure would only DECLINE the operand silently, which is the exact silent
            // degradation Fix #37 exists to rule out.
            validateSubstitutionTemplates(ce.expr(), null, false, path, errors, ruleId);
            // …and R-P4's non-executable operand, re-homed the same way: `dataset_metadata` is a
            // registered builtin token with NO resolution anywhere (not a dataset-fold name, not
            // variable-level, no provider mapping), so a rule reading it has never fired — fail
            // loudly at load (user decision: ADAM-ADD-100019) instead of reviving the silent
            // no-op through the expression spelling.
            collectNonExecutableOperandRefs(ce.expr(), path, errors, ruleId);
        }
        }
    }


    /**
     * The expression-side arm of Fix #37: validates every {@code ${…}} operand-substitution
     * template reachable from {@code expr}.
     *
     * <p>
     * Position mapping mirrors the retired leaf walker: a comparison's LEFT operand and a call's
     * first argument are the name position, a comparison's RIGHT operand is the value position. A
     * {@code ${*}} wildcard is legal only as the name of an exists-family call and as the
     * right-hand side of a membership ({@code in} / {@code not in}) — exactly
     * {@link OperandSubstitutor#validate}'s diagonal.
     * </p>
     */
    private static void validateSubstitutionTemplates(net.cumba.corej.core.expr.ast.Expr expr,
            @Nullable String operator, boolean valuePosition, String path, List<String> errors,
            String ruleId)
    {
        switch (expr)
        {
        case net.cumba.corej.core.expr.ast.Expr.Ref ref -> validateSubstitutionOperand(ref.name(),
                operator, valuePosition, path, errors, ruleId);
        case net.cumba.corej.core.expr.ast.Expr.Binary binary ->
        {
            String infix = switch (binary.op())
            {
            case EQ -> "equal_to";
            case NEQ -> "not_equal_to";
            case LT -> "less_than";
            case LE -> "less_than_or_equal_to";
            case GT -> "greater_than";
            case GE -> "greater_than_or_equal_to";
            case IN -> "is_contained_by";
            case NOT_IN -> "is_not_contained_by";
            case MATCH -> "matches_regex";
            case NMATCH -> "not_matches_regex";
            default -> null;
            };
            validateSubstitutionTemplates(binary.left(), infix, false, path, errors, ruleId);
            validateSubstitutionTemplates(binary.right(), infix, true, path, errors, ruleId);
        }
        case net.cumba.corej.core.expr.ast.Expr.Call call ->
        {
            List<net.cumba.corej.core.expr.ast.Expr> args = call.args();
            for (int i = 0; i < args.size(); i++)
            {
                net.cumba.corej.core.expr.ast.Expr arg = args.get(i);
                // The exists family accepts its name as a string literal; a template inside it
                // is validated like the bare-reference spelling.
                if (i == 0 && arg instanceof net.cumba.corej.core.expr.ast.Expr.Lit lit
                        && lit.kind() == net.cumba.corej.core.expr.ast.Expr.LitKind.STRING
                        && ("var_exists".equals(call.name()) || "var_not_exists".equals(call.name())
                                || "ds_exists".equals(call.name())
                                || "ds_not_exists".equals(call.name())))
                {
                    validateSubstitutionOperand((String) lit.value(), call.name(), false, path,
                            errors, ruleId);
                    continue;
                }
                validateSubstitutionTemplates(arg, i == 0 ? call.name() : operator, i != 0, path,
                        errors, ruleId);
            }
            call.kwargs().values().forEach(
                    v -> validateSubstitutionTemplates(v, call.name(), true, path, errors, ruleId));
        }
        default -> childrenOf(expr).forEach(child -> validateSubstitutionTemplates(child, operator,
                valuePosition, path, errors, ruleId));
        }
    }


    private static void validateSubstitutionOperand(@Nullable String name,
            @Nullable String operator, boolean valuePosition, String path, List<String> errors,
            String ruleId)
    {
        if (name == null || !OperandSubstitutor.hasPlaceholder(name))
        {
            return;
        }
        String positionLabel = valuePosition ? "value" : "name";
        try
        {
            ParsedOperand parsed = OperandSubstitutor.parse(name);
            OperandSubstitutor.validate(parsed, operator,
                    valuePosition ? Position.VALUE : Position.NAME);
        }
        catch (OperandParseException ex)
        {
            errors.add("[" + ruleId + "] " + path + " parse error: " + positionLabel + "=`" + name
                    + "`" + (operator != null ? ", operator=`" + operator + "`" : "") + ": "
                    + ex.getMessage());
        }
        catch (OperatorMismatchException ex)
        {
            errors.add("[" + ruleId + "] " + path + " operator mismatch: " + positionLabel + "=`"
                    + name + "`" + (operator != null ? ", operator=`" + operator + "`" : "") + ": "
                    + ex.getMessage());
        }
    }

    /**
     * Operand NAMES with no resolution on any engine (R-P4,
     * {@code plans/done/PLAN-native-engine-residuals.md}), re-homed from the retired leaf walker: a
     * bare reference to one is a load error, never a silent no-op.
     */
    private static final java.util.Set<String> NON_EXECUTABLE_OPERANDS = java.util.Set
            .of("dataset_metadata");

    private static void collectNonExecutableOperandRefs(net.cumba.corej.core.expr.ast.Expr expr,
            String path, List<String> errors, String ruleId)
    {
        if (expr instanceof net.cumba.corej.core.expr.ast.Expr.Ref ref
                && NON_EXECUTABLE_OPERANDS.contains(ref.name()))
        {
            errors.add("[" + ruleId + "] " + path + " uses operand '" + ref.name()
                    + "' which is not executable (no resolution on any engine; previously a"
                    + " silent no-op)");
        }
        childrenOf(expr)
                .forEach(child -> collectNonExecutableOperandRefs(child, path, errors, ruleId));
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
    static @Nullable String genericPresenceCall(net.cumba.corej.core.expr.ast.Expr e)
    {
        return switch (e)
        {
        case net.cumba.corej.core.expr.ast.Expr.Call c ->
        {
            if (GENERIC_PRESENCE_OPERATORS.contains(c.name()))
            {
                yield c.name();
            }
            for (net.cumba.corej.core.expr.ast.Expr a : c.args())
            {
                String hit = genericPresenceCall(a);
                if (hit != null)
                {
                    yield hit;
                }
            }
            for (net.cumba.corej.core.expr.ast.Expr a : c.kwargs().values())
            {
                String hit = genericPresenceCall(a);
                if (hit != null)
                {
                    yield hit;
                }
            }
            yield null;
        }
        case net.cumba.corej.core.expr.ast.Expr.And a -> firstGenericPresenceCall(a.parts());
        case net.cumba.corej.core.expr.ast.Expr.Or o -> firstGenericPresenceCall(o.parts());
        case net.cumba.corej.core.expr.ast.Expr.Not n -> genericPresenceCall(n.inner());
        case net.cumba.corej.core.expr.ast.Expr.Binary b ->
        {
            String hit = genericPresenceCall(b.left());
            yield hit != null ? hit : genericPresenceCall(b.right());
        }
        case net.cumba.corej.core.expr.ast.Expr.Ref _,net.cumba.corej.core.expr.ast.Expr.Lit _ -> null;
        };
    }


    private static @Nullable String firstGenericPresenceCall(
            List<net.cumba.corej.core.expr.ast.Expr> parts)
    {
        for (net.cumba.corej.core.expr.ast.Expr p : parts)
        {
            String hit = genericPresenceCall(p);
            if (hit != null)
            {
                return hit;
            }
        }
        return null;
    }

    /** What {@link #ruleId} yields for a rule carrying no identity at all. */
    private static final String UNKNOWN_RULE_ID = "<unknown>";

    private static String ruleId(Rule rule)
    {
        String id = rule.effectiveId();
        if (id != null)
        {
            return id;
        }
        String key = rule.getLoadKey();
        return key != null ? key : UNKNOWN_RULE_ID;
    }

}
