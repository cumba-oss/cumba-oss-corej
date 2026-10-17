package net.cumba.corej.ruletest.cdt.ruletest;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.model.RulePackage;
import org.jspecify.annotations.Nullable;

/**
 * Which <em>view</em> of a rule a scenario runs against: the assembled form of the same rule
 * differs across standard versions, so a scenario executes the view of the standard it declares
 * ({@code #library standard=… version=…} or {@code #library-ref}), else a deterministic canonical
 * fallback. Lifted from the corpus's {@code AbstractRuleTestSuitesFactory}
 * ({@code PLAN-rule-authoring-api} phase 3) so the rule editor selects exactly as the gate does.
 */
public final class ScenarioRuleSelector
{

    /**
     * Fallback resolution order for scenarios that declare no standard+version: the historic
     * canonical packages of the pre-family factories (ADaM suite → {@code adamig-1-3}, SDTM suite →
     * {@code sdtmig-3-4}), then SEND's shipping version, then any package containing the rule
     * (sorted, last wins — deterministic).
     */
    public static final List<String> DEFAULT_PACKAGE_PREFERENCE = List.of("adamig-1-3",
            "sdtmig-3-4", "sendig-3-1-1");

    /** The default standard/version of a {@code #library} block with no explicit declaration. */
    public static final String UNDECLARED_STANDARD = "test";

    private ScenarioRuleSelector()
    {
    }


    /**
     * The scenario's declared {@code <standard>-<version>}, or {@code null} if undeclared.
     *
     * @param aScenario
     *            the scenario
     * @return the declaration, dash form
     */
    public static @Nullable String declaredStandardVersion(RuleTestScenario aScenario)
    {
        LibraryRef ref = aScenario.getLibraryRef();
        if (ref != null)
        {
            return ref.getStandard() + "-" + ref.getVersion();
        }
        MapBackedLibraryMetadataProvider lib = aScenario.getLibrary();
        if (lib != null && !UNDECLARED_STANDARD.equals(lib.getStandard()))
        {
            return lib.getStandard() + "-" + lib.getVersion();
        }
        return null;
    }


    /**
     * The rule to execute from a package map: the declared standard+version's view when the
     * scenario declares one, else the first canonical package containing the rule
     * ({@link #DEFAULT_PACKAGE_PREFERENCE}), else — deterministically — the last sorted package
     * that contains it.
     *
     * @param aPackages
     *            {@code standard-version} → ({@code Core.Id} → rule), see {@link #loadPackages}
     * @param aScenario
     *            the scenario
     * @return the rule, or {@code null} when no package holds it
     */
    public static @Nullable Rule resolveIn(Map<String, Map<String, Rule>> aPackages,
            RuleTestScenario aScenario)
    {
        List<String> holding = new java.util.ArrayList<>();
        for (Map.Entry<String, Map<String, Rule>> e : aPackages.entrySet())
        {
            if (e.getValue() != null && e.getValue().containsKey(aScenario.getCoreId()))
            {
                holding.add(e.getKey());
            }
        }
        String key = packageKey(aScenario, holding);
        Map<String, Rule> pkg = key == null ? null : aPackages.get(key);
        return pkg != null ? pkg.get(aScenario.getCoreId()) : null;
    }


    /**
     * The {@code standard-version} key of the package a scenario's rule runs from — the one
     * selection {@link #resolveIn} makes, for a host that holds no package map (the rule editor
     * knows the packages the authored rule's {@code Standards} name, not their contents): the
     * declared standard+version when the scenario declares one (whether or not the rule is a
     * member), else the first canonical package holding the rule
     * ({@link #DEFAULT_PACKAGE_PREFERENCE}), else the last of the sorted holding packages.
     *
     * @param aScenario
     *            the scenario
     * @param aHolding
     *            the {@code standard-version} keys of the packages that contain the rule
     * @return the key, or {@code null} when the scenario declares none and no package holds the
     *         rule
     */
    public static @Nullable String packageKey(RuleTestScenario aScenario,
            java.util.Collection<String> aHolding)
    {
        String declared = declaredStandardVersion(aScenario);
        if (declared != null)
        {
            return declared;
        }
        for (String preferred : DEFAULT_PACKAGE_PREFERENCE)
        {
            if (aHolding.contains(preferred))
            {
                return preferred;
            }
        }
        TreeSet<String> sorted = new TreeSet<>(aHolding);
        return sorted.isEmpty() ? null : sorted.last();
    }


    /**
     * Load every {@code rules-<family>-*.json} package of a corpus directory, keyed by its
     * {@code standard-version} stem; each package's rules keyed by {@code Core.Id}. Views are kept
     * per package — the assembled form of the same rule differs across standard versions, so
     * {@link #resolveIn} picks the view matching the scenario.
     *
     * @param aRulesDir
     *            the generated corpus directory
     * @param aFamily
     *            the family token ({@code cdisc}, {@code fda}, …) — the package file prefix
     * @return the package map
     * @throws IllegalStateException
     *             when the directory holds no package of the family
     */
    public static Map<String, Map<String, Rule>> loadPackages(Path aRulesDir, String aFamily)
    {
        Map<String, Map<String, Rule>> packages = new HashMap<>();
        String prefix = "rules-" + aFamily + "-";
        try (DirectoryStream<Path> dir = Files.newDirectoryStream(aRulesDir, prefix + "*.json"))
        {
            for (Path p : dir)
            {
                String name = p.getFileName().toString();
                String standardVersion = name.substring(prefix.length(),
                        name.length() - ".json".length());
                RulePackage pkg = RulePackageLoader.load(p);
                Map<String, Rule> byCoreId = new HashMap<>();
                Map<String, Rule> loaded = pkg.getRules();
                if (loaded == null)
                {
                    throw new IllegalStateException("package " + p + " carries no rules map");
                }
                for (Rule rule : loaded.values())
                {
                    String coreId = rule.getCore() != null ? rule.getCore().getId() : rule.getId();
                    byCoreId.put(coreId, rule);
                }
                packages.put(standardVersion, byCoreId);
            }
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("could not load rule packages for family " + aFamily, e);
        }
        if (packages.isEmpty())
        {
            throw new IllegalStateException(
                    "no rule packages found for family " + aFamily + " under " + aRulesDir);
        }
        return packages;
    }
}
