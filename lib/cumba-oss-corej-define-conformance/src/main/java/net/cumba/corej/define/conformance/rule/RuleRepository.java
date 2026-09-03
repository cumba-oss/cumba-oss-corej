package net.cumba.corej.define.conformance.rule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Stream;
import org.jspecify.annotations.Nullable;

/**
 * Loads {@link ConformanceRule} YAML files from an external filesystem directory, validates each on
 * load, and rejects duplicate ids.
 *
 * <p>
 * The rule corpus is <b>not</b> bundled in the module jar — like {@code cumba-oss-corej-core}'s
 * SDTM rules, it lives in an operator-editable directory so a rule can be corrected, disabled, or
 * supplemented without a rebuild. {@link #loadDefault()} resolves that directory from, in order,
 * the {@link #ENV_RULES_DIR} environment variable, the {@link #SP_RULES_DIR} system property, then
 * the conventional {@link #DEFAULT_RULES_DIR}; when none resolves and no explicit rules are
 * supplied, loading fails with a clear message rather than silently reporting a clean run. The
 * canonical corpus is authored in {@code lib/cumba-oss-cdisc-rules/rules-define-src/} and generated
 * into {@code lib/cumba-oss-cdisc-rules/rules-define/}, which is what ships beside the launcher. It
 * left this module on 2026-09-01 so a rule, its scenarios and its packaging live together.
 * </p>
 *
 * <p>
 * ⚑ <b>Two load paths, and only one of them is a runtime path (D8, 2026-09-01).</b>
 * {@link #loadPackage} / {@link #loadPackages} read the <b>generated</b>
 * {@code rules-define-*.json} packages and are what a run uses. {@link #loadDefault} /
 * {@link #loadResolved} / {@link #loadDirectory} read the <b>authored</b> per-rule YAML; that
 * corpus lives in {@code cumba-oss-cdisc-rules} as {@code rules-define-src/} and is authoring and
 * review material, not a shipped artefact. No production code calls the YAML loaders any more —
 * they are kept for the generator and for tests, and adding a runtime caller would reintroduce the
 * very asymmetry this split removed, where what shipped and what was authored could silently
 * diverge.
 * </p>
 *
 * <p>
 * {@code custom}-kind rules name a {@link net.cumba.corej.define.conformance.eval.CustomCheck}
 * class that is still compiled into the jar and resolved from the classpath at evaluation time —
 * only the YAML is externalised.
 * </p>
 */
public final class RuleRepository
{

    /** Environment variable naming the external Define-XML rules directory. */
    public static final String ENV_RULES_DIR = "COREJ_DEFINE_RULES_DIR";

    /** System property naming the external Define-XML rules directory. */
    public static final String SP_RULES_DIR = "corej.define.rules.dir";

    /** Conventional rules directory, relative to the process CWD, used when nothing else is set. */
    public static final String DEFAULT_RULES_DIR = "./rules-define";

    private static final ObjectMapper MAPPER = new ObjectMapper(new YAMLFactory());

    /** Reader for the generated packages. Same databind layer, JSON factory. */
    private static final ObjectMapper JSON = new ObjectMapper();

    private RuleRepository()
    {
    }


    /**
     * Loads the corpus from the default-resolved directory ({@link #ENV_RULES_DIR} &rarr;
     * {@link #SP_RULES_DIR} &rarr; {@link #DEFAULT_RULES_DIR}). Throws
     * {@link IllegalStateException} if no directory resolves (so an unconfigured run fails loudly,
     * never silently clean).
     */
    public static List<ConformanceRule> loadDefault()
    {
        return loadResolved(null, List.of());
    }


    /**
     * Resolves the effective rules directory and loads it, plus any additive {@code aFiles}.
     *
     * <p>
     * Directory precedence: {@code aExplicitDir} &gt; {@link #ENV_RULES_DIR} &gt;
     * {@link #SP_RULES_DIR} &gt; {@link #DEFAULT_RULES_DIR} (if present). An explicit / env /
     * sysprop value pointing at a missing directory is a hard error (a typo must not silently fall
     * back); the conventional directory is presence-gated. {@code aFiles} are layered on top (site
     * rules); a files-only corpus (no resolvable directory) is allowed. Duplicate {@code Rule_Id}
     * across the directory and files is rejected. An otherwise-empty corpus is a hard error.
     * </p>
     *
     * @param aExplicitDir
     *            the CLI {@code --rules-dir}, or {@code null}
     * @param aFiles
     *            additive {@code --rules-file} paths (may be empty)
     */
    public static List<ConformanceRule> loadResolved(@Nullable Path aExplicitDir, List<Path> aFiles)
    {
        List<ConformanceRule> rules = new ArrayList<>();
        resolveRulesDir(aExplicitDir == null ? null : aExplicitDir.toString(),
                System.getenv(ENV_RULES_DIR), System.getProperty(SP_RULES_DIR))
                        .ifPresent(dir -> rules.addAll(loadDirectory(dir)));
        for (Path file : aFiles)
        {
            rules.add(parse(readString(file), file.toString()));
        }
        if (rules.isEmpty())
        {
            throw new DefineRuleSelectionException(
                    "No Define-XML rules loaded. Configure --rules-dir, the " + ENV_RULES_DIR
                            + " environment variable, or the -D" + SP_RULES_DIR
                            + " system property; create " + DEFAULT_RULES_DIR
                            + "; or pass --rules-file.");
        }
        return validateCorpus(rules);
    }


    /**
     * Pure precedence resolver for the rules directory. Public since 2026-09-01: package selection
     * moved into {@code DefineConformanceEngine}, in a different package, which must resolve the
     * directory before it can read the manifest. Still pure — every arg/env/sysprop combination
     * stays unit-testable without mutating the process environment.
     *
     * @return the resolved directory, or empty when nothing is configured and the conventional
     *         directory is absent
     * @throws IllegalStateException
     *             if an explicit / env / sysprop value names a non-directory
     */
    public static Optional<Path> resolveRulesDir(@Nullable String aArg, @Nullable String aEnv,
            @Nullable String aSysprop)
    {
        String chosen = firstNonBlank(aArg, aEnv, aSysprop);
        if (chosen != null)
        {
            Path dir = Path.of(chosen);
            if (!Files.isDirectory(dir))
            {
                // A configured directory that is not there is a SELECTION problem — the caller
                // named it. Typed so a front end can report it as a usage error without having
                // to recognise the message.
                throw new DefineRuleSelectionException(
                        "Configured Define-XML rules directory not found: " + dir.toAbsolutePath());
            }
            return Optional.of(dir);
        }
        Path conventional = Path.of(DEFAULT_RULES_DIR);
        return Files.isDirectory(conventional) ? Optional.of(conventional) : Optional.empty();
    }


    /** Loads every {@code *.yaml} under a directory tree — the test-fixture entry point. */
    public static List<ConformanceRule> loadDirectory(Path aDirectory)
    {
        List<ConformanceRule> rules = new ArrayList<>();
        try (Stream<Path> paths = Files.walk(aDirectory))
        {
            List<Path> files = paths.filter(p -> p.toString().endsWith(".yaml"))
                    .sorted(Comparator.comparing(Path::toString)).toList();
            for (Path file : files)
            {
                rules.add(parse(Files.readString(file, StandardCharsets.UTF_8),
                        aDirectory.relativize(file).toString()));
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot read rules from " + aDirectory, e);
        }
        return validateCorpus(rules);
    }


    /**
     * Reads one generated rule package ({@code rules-define-<family>-<version>.json}) and validates
     * every rule in it exactly as the YAML path does.
     *
     * <p>
     * The package format is defined once, by {@link DefineRulePackage}; this reader and the offline
     * generator both go through it, so they cannot drift.
     * </p>
     */
    public static DefineRulePackage loadPackage(Path aFile)
    {
        try
        {
            DefineRulePackage pkg = JSON.readValue(Files.readString(aFile, StandardCharsets.UTF_8),
                    DefineRulePackage.class);
            for (ConformanceRule rule : pkg.rules().values())
            {
                rule.validate();
            }
            return pkg;
        }
        catch (IOException | IllegalStateException e)
        {
            throw new DefineRuleSelectionException(
                    "invalid rule package " + aFile + ": " + e.getMessage(), e);
        }
    }


    /**
     * Loads several packages into one corpus, rejecting duplicate {@code Rule_Id} across them.
     *
     * <p>
     * Loading two <em>families</em> for the same version is safe and supported —
     * {@code DEFINE-XML-*} and {@code PMDA-DD*}/{@code PMDA-OD*} are disjoint. Loading two
     * <em>versions</em> is not: a rule that applies to both is published in both, so the duplicate
     * check below is what stops that silently doubling the corpus.
     * </p>
     */
    public static List<ConformanceRule> loadPackages(List<Path> aFiles)
    {
        List<ConformanceRule> rules = new ArrayList<>();
        for (Path file : aFiles)
        {
            rules.addAll(loadPackage(file).ruleList());
        }
        if (rules.isEmpty())
        {
            // The same fail-loud guarantee loadResolved gives the YAML path, applied to the one
            // that actually runs. Without it a truncated or emptied package -- a partial cp into
            // the container's writable rules dir, a failed edit, a full disk -- loads zero rules
            // and the run reports the document conformant, with exit code 0.
            throw new DefineRuleSelectionException("no Define-XML rules loaded from " + aFiles
                    + " — refusing to validate against an empty corpus, because a clean report"
                    + " over no rules is indistinguishable from a clean document");
        }
        return validateCorpus(rules);
    }


    /** Parses and validates one rule document. */
    public static ConformanceRule parse(String aYaml, String aSourceName)
    {
        try
        {
            ConformanceRule rule = MAPPER.readValue(aYaml, ConformanceRule.class);
            rule.validate();
            return rule;
        }
        catch (IOException | IllegalStateException e)
        {
            throw new DefineRuleSelectionException(
                    "invalid rule file " + aSourceName + ": " + e.getMessage(), e);
        }
    }


    private static List<ConformanceRule> validateCorpus(List<ConformanceRule> aRules)
    {
        Set<String> ids = new HashSet<>();
        for (ConformanceRule rule : aRules)
        {
            if (!ids.add(rule.ruleId()))
            {
                throw new DefineRuleSelectionException("duplicate Rule_Id " + rule.ruleId());
            }
        }
        return List.copyOf(aRules);
    }


    private static @Nullable String firstNonBlank(@Nullable String... aValues)
    {
        for (String value : aValues)
        {
            if (value != null && !value.isBlank())
            {
                return value;
            }
        }
        return null;
    }


    private static String readString(Path aFile)
    {
        try
        {
            return Files.readString(aFile, StandardCharsets.UTF_8);
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot read rule file " + aFile, e);
        }
    }

}
