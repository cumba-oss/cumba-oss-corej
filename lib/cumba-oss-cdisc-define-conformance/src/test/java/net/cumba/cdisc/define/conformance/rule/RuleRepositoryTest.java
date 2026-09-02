package net.cumba.cdisc.define.conformance.rule;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RuleRepository}: directory loading, duplicate-id rejection, parse-error wrapping, the pure
 * directory-precedence resolver, additive file loading and the fail-loud guards.
 *
 * <p>
 * Every case here builds its own corpus in a {@code @TempDir}. It used to borrow the real shipped
 * corpus as a convenient non-empty directory, which made assertions depend on the corpus's size and
 * coupled this module's loader tests to a corpus that has since moved to {@code corej-cdisc-rules}
 * (PLAN-rules-module-consolidation D12). Synthetic corpora are both the necessary and the better
 * answer: the expected counts are now exact rather than relative.
 * </p>
 */
class RuleRepositoryTest
{

    private static String ruleYaml(String aRuleId)
    {
        return """
                Rule_Id: "%s"
                Sheet_Rule_Identifier: "1"
                Rule_Set: "CDISC"
                Element: "ItemRef"
                Attribute: "ItemOID"
                Applicable_Versions: ["2.1"]
                Source_Type: "Specification"
                Plain_Text_Rule: "Test rule."
                Message: "Test message."
                Check:
                  kind: "exists"
                  target: "@ItemOID"
                """.formatted(aRuleId);
    }


    private static void write(Path aFile, String aContent) throws IOException
    {
        Files.createDirectories(aFile.getParent());
        Files.writeString(aFile, aContent, UTF_8);
    }


    /** Skips a test when the ambient env/sysprop already points at a real Define-XML rules dir. */
    static void assumeNoAmbientRulesDir()
    {
        assumeTrue(
                System.getenv(RuleRepository.ENV_RULES_DIR) == null
                        && System.getProperty(RuleRepository.SP_RULES_DIR) == null,
                "requires no ambient Define-XML rules directory (env/sysprop) configured");
    }


    @Test
    void loadDirectoryWalksSubdirectoriesAndIgnoresNonYamlFiles(@TempDir Path aDir)
        throws IOException
    {
        write(aDir.resolve("CDISC/DEFINE-XML-0001.yaml"), ruleYaml("DEFINE-XML-0001"));
        write(aDir.resolve("PMDA/PMDA-DD0001.yaml"), ruleYaml("PMDA-DD0001"));
        write(aDir.resolve("notes.txt"), "not a rule");
        List<ConformanceRule> rules = RuleRepository.loadDirectory(aDir);
        assertEquals(List.of("DEFINE-XML-0001", "PMDA-DD0001"),
                rules.stream().map(ConformanceRule::ruleId).sorted().toList());
    }


    @Test
    void loadDirectoryRejectsDuplicateRuleIds(@TempDir Path aDir) throws IOException
    {
        write(aDir.resolve("a.yaml"), ruleYaml("DEFINE-XML-0001"));
        write(aDir.resolve("b.yaml"), ruleYaml("DEFINE-XML-0001"));
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RuleRepository.loadDirectory(aDir));
        assertTrue(e.getMessage().contains("duplicate Rule_Id DEFINE-XML-0001"), e.getMessage());
    }


    @Test
    void loadDirectoryNamesTheBrokenFileOnParseError(@TempDir Path aDir) throws IOException
    {
        write(aDir.resolve("CDISC/broken.yaml"), "Rule_Id: [unclosed");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RuleRepository.loadDirectory(aDir));
        assertTrue(e.getMessage().contains("invalid rule file"), e.getMessage());
        assertTrue(e.getMessage().contains("broken.yaml"), e.getMessage());
    }


    @Test
    void loadDirectoryOnMissingDirectoryThrowsUncheckedIo(@TempDir Path aDir)
    {
        Path missing = aDir.resolve("does-not-exist");
        UncheckedIOException e = assertThrows(UncheckedIOException.class,
                () -> RuleRepository.loadDirectory(missing));
        assertTrue(e.getMessage().contains("cannot read rules from"), e.getMessage());
    }


    @Test
    void parseWrapsValidationFailuresWithTheSourceName()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RuleRepository.parse(ruleYaml(" "), "blank-id.yaml"));
        assertTrue(e.getMessage().contains("invalid rule file blank-id.yaml"), e.getMessage());
        assertTrue(e.getMessage().contains("Rule_Id is blank"), e.getMessage());
    }

    // ---- resolveRulesDir: pure precedence, every branch --------------------------------------


    @Test
    void explicitArgWinsOverEnvAndSysprop(@TempDir Path aDir) throws IOException
    {
        Path arg = Files.createDirectory(aDir.resolve("arg"));
        Path other = Files.createDirectory(aDir.resolve("other"));
        assertEquals(Optional.of(arg),
                RuleRepository.resolveRulesDir(arg.toString(), other.toString(), other.toString()));
    }


    @Test
    void envWinsOverSyspropWhenNoArg(@TempDir Path aDir) throws IOException
    {
        Path env = Files.createDirectory(aDir.resolve("env"));
        Path sysprop = Files.createDirectory(aDir.resolve("sp"));
        assertEquals(Optional.of(env),
                RuleRepository.resolveRulesDir(null, env.toString(), sysprop.toString()));
    }


    @Test
    void syspropUsedWhenNoArgOrEnv(@TempDir Path aDir) throws IOException
    {
        Path sysprop = Files.createDirectory(aDir.resolve("sp"));
        assertEquals(Optional.of(sysprop),
                RuleRepository.resolveRulesDir(null, null, sysprop.toString()));
    }


    @Test
    void configuredButMissingDirectoryIsHardError(@TempDir Path aDir)
    {
        // A configured value that does not exist must fail, never silently fall back.
        String missing = aDir.resolve("nope").toString();
        assertThrows(IllegalStateException.class,
                () -> RuleRepository.resolveRulesDir(missing, null, null));
        assertThrows(IllegalStateException.class,
                () -> RuleRepository.resolveRulesDir(null, missing, null));
        assertThrows(IllegalStateException.class,
                () -> RuleRepository.resolveRulesDir(null, null, missing));
    }


    @Test
    void blankValuesAreIgnored(@TempDir Path aDir) throws IOException
    {
        Path sysprop = Files.createDirectory(aDir.resolve("sp"));
        // Blank arg + blank env fall through to the sysprop.
        assertEquals(Optional.of(sysprop),
                RuleRepository.resolveRulesDir("  ", "", sysprop.toString()));
    }


    @Test
    void conventionalDirectoryIsPresenceGated() throws IOException
    {
        // Nothing configured: resolves the conventional ./rules-define iff it exists in the CWD.
        // Exercised within one method so no other test observes the transient relative directory.
        Path conventional = Path.of(RuleRepository.DEFAULT_RULES_DIR);
        assertEquals(Optional.empty(), RuleRepository.resolveRulesDir(null, null, null),
                "precondition: ./rules-define must be absent");
        Files.createDirectories(conventional);
        try
        {
            assertEquals(Optional.of(conventional),
                    RuleRepository.resolveRulesDir(null, null, null));
        }
        finally
        {
            Files.delete(conventional);
        }
    }

    // ---- loadResolved: additivity, guards ----------------------------------------------------


    @Test
    void additiveFileAugmentsTheDirectoryCorpus(@TempDir Path aDir) throws IOException
    {
        Path corpus = Files.createDirectory(aDir.resolve("corpus"));
        write(corpus.resolve("a.yaml"), ruleYaml("TEST-BASE-0001"));
        write(corpus.resolve("b.yaml"), ruleYaml("TEST-BASE-0002"));
        Path extra = aDir.resolve("extra.yaml");
        write(extra, ruleYaml("TEST-ADDED-0001"));

        List<ConformanceRule> augmented = RuleRepository.loadResolved(corpus, List.of(extra));

        assertEquals(3, augmented.size());
        assertTrue(augmented.stream().anyMatch(r -> "TEST-ADDED-0001".equals(r.ruleId())));
    }


    @Test
    void filesOnlyCorpusIsAllowed(@TempDir Path aDir) throws IOException
    {
        // Passing a null dir reads the ambient env/sysprop; skip if one resolves a real corpus
        // (which would add to the single expected file).
        assumeNoAmbientRulesDir();
        Path only = aDir.resolve("only.yaml");
        write(only, ruleYaml("TEST-ONLY-0001"));
        List<ConformanceRule> rules = RuleRepository.loadResolved(null, List.of(only));
        assertEquals(1, rules.size());
        assertEquals("TEST-ONLY-0001", rules.get(0).ruleId());
    }


    @Test
    void duplicateIdAcrossDirectoryAndFileIsRejected(@TempDir Path aDir) throws IOException
    {
        // An additive file repeating an id the directory already carries.
        Path corpus = Files.createDirectory(aDir.resolve("corpus"));
        write(corpus.resolve("a.yaml"), ruleYaml("TEST-DUPE-0001"));
        Path dupe = aDir.resolve("dupe.yaml");
        write(dupe, ruleYaml("TEST-DUPE-0001"));

        assertThrows(IllegalStateException.class,
                () -> RuleRepository.loadResolved(corpus, List.of(dupe)));
    }


    @Test
    void emptyCorpusIsHardError()
    {
        // No directory resolvable (unconfigured) and no files → fail loud, not silent-clean. Skip
        // (not fail) when the ambient env/sysprop points at a real corpus, e.g. a developer who has
        // COREJ_DEFINE_RULES_DIR exported after running the CLI locally.
        assumeNoAmbientRulesDir();
        assertThrows(IllegalStateException.class,
                () -> RuleRepository.loadResolved(null, List.of()));
    }


    @Test
    void resolvableButEmptyDirectoryIsHardError(@TempDir Path aDir)
    {
        // The directory EXISTS (so resolveRulesDir returns it) but holds zero rule files. This must
        // fail loud rather than validate a define.xml against an empty corpus and report it clean.
        assertThrows(IllegalStateException.class,
                () -> RuleRepository.loadResolved(aDir, List.of()));
    }

    /*
     * everyCustomRuleClassResolvesFromTheExternalCorpus lived here until 2026-09-01. The corpus
     * it read moved to corej-cdisc-rules (PLAN-rules-module-consolidation D12), and the assertion
     * moved with it as DefineRulePackageEquivalenceTest.everyCustomCheckClassInThePackagedCorpus-
     * Resolves — which is strictly stronger: it reads the SHIPPED packages rather than the
     * authored YAML, and it INSTANTIATES each class rather than only resolving it, so a class
     * without the public no-arg constructor the SPI requires now fails there too.
     */

    // ---- loadDefault via the real system property --------------------------------------------


    @Test
    void loadDefaultResolvesTheSyspropDirectory(@TempDir Path aDir) throws IOException
    {
        // env beats the sysprop this test sets (precedence), so an ambient env var would make
        // loadDefault() resolve a different corpus; skip in that case.
        assumeNoAmbientRulesDir();
        write(aDir.resolve("a.yaml"), ruleYaml("TEST-SYSPROP-0001"));
        String previous = System.getProperty(RuleRepository.SP_RULES_DIR);
        System.setProperty(RuleRepository.SP_RULES_DIR, aDir.toString());
        try
        {
            List<ConformanceRule> viaDefault = RuleRepository.loadDefault();
            assertEquals(1, viaDefault.size());
            assertEquals("TEST-SYSPROP-0001", viaDefault.get(0).ruleId());
        }
        finally
        {
            if (previous == null)
            {
                System.clearProperty(RuleRepository.SP_RULES_DIR);
            }
            else
            {
                System.setProperty(RuleRepository.SP_RULES_DIR, previous);
            }
        }
    }
}
