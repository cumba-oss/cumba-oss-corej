package net.cumba.corej.define.conformance.rule;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The generated-package format: {@link DefineRulePackage}, {@link DefineRulePackageManifest} and
 * the {@link RuleRepository} readers over them.
 *
 * <p>
 * Every case builds its own package in a {@code @TempDir}. The real corpus lives in
 * {@code corej-rules} — this module cannot depend on it (that would be a reactor cycle), and should
 * not: these are unit tests of the format, not of the corpus.
 * </p>
 */
class DefineRulePackageTest
{

    private static String rule(String aRuleId, String... aVersions)
    {
        String versions = String.join("\", \"", aVersions);
        return """
                "%s" : {
                  "Rule_Id" : "%s",
                  "Rule_Set" : "CDISC",
                  "Element" : "ODM",
                  "Applicable_Versions" : [ "%s" ],
                  "Plain_Text_Rule" : "text",
                  "Message" : "message",
                  "Check" : { "kind" : "exists", "target" : "@FileOID" }
                }""".formatted(aRuleId, aRuleId, versions);
    }


    private static Path writePackage(Path aDir, String aFile, String aFamily, String aVersion,
            String... aRules)
        throws IOException
    {
        Path p = aDir.resolve(aFile);
        Files.writeString(p, """
                {
                  "family" : "%s",
                  "version" : "%s",
                  "rules" : {
                %s
                  }
                }
                """.formatted(aFamily, aVersion, String.join(",\n", aRules)),
                StandardCharsets.UTF_8);
        return p;
    }

    // ---- file naming --------------------------------------------------------------------------


    @Test
    void packageFileNameIsFamilyLowercasedAndVersionDashed()
    {
        assertEquals("rules-define-cdisc-2-1.json",
                DefineRulePackageManifest.packageFileName("CDISC", "2.1"));
        assertEquals("rules-define-pmda-2-0.json",
                DefineRulePackageManifest.packageFileName("PMDA", "2.0"));
    }

    // ---- package reading ----------------------------------------------------------------------


    @Test
    void aPackageRoundTripsItsFamilyVersionAndRules(@TempDir Path aDir) throws IOException
    {
        Path file = writePackage(aDir, "p.json", "CDISC", "2.1", rule("DEFINE-XML-0001", "2.1"),
                rule("DEFINE-XML-0002", "2.1"));

        DefineRulePackage pkg = RuleRepository.loadPackage(file);

        assertEquals("CDISC", pkg.family());
        assertEquals("2.1", pkg.version());
        assertEquals(List.of("DEFINE-XML-0001", "DEFINE-XML-0002"),
                List.copyOf(pkg.rules().keySet()));
        assertEquals(2, pkg.ruleList().size());
    }


    @Test
    void aRuleThatFailsValidationIsRejectedAtLoad(@TempDir Path aDir) throws IOException
    {
        // Applicable_Versions is empty — ConformanceRule.validate() rejects it. The packaged path
        // must validate exactly as the authored path does, or a malformed generated package would
        // load clean and mis-evaluate.
        Path file = aDir.resolve("bad.json");
        Files.writeString(file, """
                {
                  "family" : "CDISC",
                  "version" : "2.1",
                  "rules" : {
                    "DEFINE-XML-0001" : {
                      "Rule_Id" : "DEFINE-XML-0001",
                      "Rule_Set" : "CDISC",
                      "Element" : "ODM",
                      "Applicable_Versions" : [ ],
                      "Plain_Text_Rule" : "text",
                      "Message" : "message",
                      "Check" : { "kind" : "exists", "target" : "@FileOID" }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RuleRepository.loadPackage(file));
        assertTrue(e.getMessage().contains("bad.json"), e.getMessage());
    }

    // ---- multi-package loading ----------------------------------------------------------------


    @Test
    void twoFamiliesLoadTogetherBecauseTheirIdsAreDisjoint(@TempDir Path aDir) throws IOException
    {
        Path cdisc = writePackage(aDir, "c.json", "CDISC", "2.1", rule("DEFINE-XML-0001", "2.1"));
        Path pmda = writePackage(aDir, "p.json", "PMDA", "2.1", rule("PMDA-DD0012", "2.1"));

        List<ConformanceRule> rules = RuleRepository.loadPackages(List.of(cdisc, pmda));

        assertEquals(2, rules.size());
    }


    @Test
    void twoVersionsOfOneFamilyCollideOnRuleId(@TempDir Path aDir) throws IOException
    {
        // A rule applying to both versions is published in BOTH packages, so loading two versions
        // together would silently double it. The duplicate guard is what makes "exactly one
        // version at a time" enforceable rather than merely documented.
        Path v20 = writePackage(aDir, "v20.json", "CDISC", "2.0",
                rule("DEFINE-XML-0001", "2.0", "2.1"));
        Path v21 = writePackage(aDir, "v21.json", "CDISC", "2.1",
                rule("DEFINE-XML-0001", "2.0", "2.1"));

        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RuleRepository.loadPackages(List.of(v20, v21)));
        assertTrue(e.getMessage().contains("DEFINE-XML-0001"), e.getMessage());
    }

    // ---- manifest -----------------------------------------------------------------------------


    @Test
    void theManifestFindsByFamilyAndVersionAndListsVersions(@TempDir Path aDir) throws IOException
    {
        Files.writeString(aDir.resolve(DefineRulePackageManifest.FILE_NAME), """
                {
                  "generatedFrom" : "test",
                  "packages" : [
                    { "file" : "rules-define-cdisc-2-0.json", "family" : "CDISC",
                      "version" : "2.0", "ruleCount" : 1 },
                    { "file" : "rules-define-cdisc-2-1.json", "family" : "CDISC",
                      "version" : "2.1", "ruleCount" : 2 },
                    { "file" : "rules-define-pmda-2-1.json", "family" : "PMDA",
                      "version" : "2.1", "ruleCount" : 3 }
                  ]
                }
                """, StandardCharsets.UTF_8);

        DefineRulePackageManifest manifest = DefineRulePackageManifest.load(aDir);

        assertEquals("test", manifest.generatedFrom());
        assertEquals(3, manifest.packages().size());
        assertEquals("rules-define-cdisc-2-1.json",
                manifest.find("CDISC", "2.1").orElseThrow().file());
        assertEquals(2, manifest.find("CDISC", "2.1").orElseThrow().ruleCount());
        assertEquals(Optional.empty(), manifest.find("PMDA", "2.0"));
        assertEquals(List.of("2.0", "2.1"), manifest.versionsFor("CDISC"));
        assertEquals(List.of("2.1"), manifest.versionsFor("PMDA"));
    }


    @Test
    void aDirectoryWithNoManifestIsAClearError(@TempDir Path aDir)
    {
        IOException e = assertThrows(IOException.class, () -> DefineRulePackageManifest.load(aDir));
        assertTrue(e.getMessage().contains(DefineRulePackageManifest.FILE_NAME), e.getMessage());
    }

    // ---- open family set (PLAN-define-families-from-packages) ---------------------------------


    private static void writeManifest(Path aDir, String aJson) throws IOException
    {
        Files.writeString(aDir.resolve(DefineRulePackageManifest.FILE_NAME), aJson,
                StandardCharsets.UTF_8);
    }


    /**
     * The family set is whatever {@code packages.json} publishes: a third family the engine has
     * never heard of loads. Red-first: under the closed {@code RuleSet} enum Jackson could not bind
     * {@code "FDA"} and the load threw.
     */
    @Test
    void aManifestMayPublishAFamilyTheEngineHasNeverHeardOf(@TempDir Path aDir) throws IOException
    {
        writeManifest(aDir, """
                {
                  "generatedFrom" : "test",
                  "packages" : [
                    { "file" : "rules-define-cdisc-2-1.json", "family" : "CDISC",
                      "version" : "2.1", "ruleCount" : 1 },
                    { "file" : "rules-define-fda-2-1.json", "family" : "FDA",
                      "version" : "2.1", "ruleCount" : 2 }
                  ]
                }
                """);

        DefineRulePackageManifest manifest = DefineRulePackageManifest.load(aDir);

        assertEquals(2, manifest.packages().size());
        assertEquals("FDA", manifest.packages().get(1).family());
    }


    /**
     * A newer corpus may carry a manifest field this engine does not know; the reader must ignore
     * it, as the data side's {@code RulePackageManifest} does. Red-first: the strict mapper threw
     * on the unknown property.
     */
    @Test
    void theManifestReaderIgnoresAnUnknownProperty(@TempDir Path aDir) throws IOException
    {
        writeManifest(aDir, """
                {
                  "generatedFrom" : "test",
                  "schemaVersion" : 2,
                  "packages" : [
                    { "file" : "rules-define-cdisc-2-1.json", "family" : "CDISC",
                      "version" : "2.1", "ruleCount" : 1, "label" : "CDISC Define-XML 2.1" }
                  ]
                }
                """);

        DefineRulePackageManifest manifest = DefineRulePackageManifest.load(aDir);

        assertEquals(1, manifest.packages().size());
    }


    @Test
    void familiesAreListedOnceEachInManifestOrderAndResolvedIgnoringCase(@TempDir Path aDir)
        throws IOException
    {
        writeManifest(aDir, """
                {
                  "generatedFrom" : "test",
                  "packages" : [
                    { "file" : "rules-define-pmda-2-0.json", "family" : "PMDA",
                      "version" : "2.0", "ruleCount" : 1 },
                    { "file" : "rules-define-cdisc-2-0.json", "family" : "CDISC",
                      "version" : "2.0", "ruleCount" : 1 },
                    { "file" : "rules-define-cdisc-2-1.json", "family" : "CDISC",
                      "version" : "2.1", "ruleCount" : 1 }
                  ]
                }
                """);

        DefineRulePackageManifest manifest = DefineRulePackageManifest.load(aDir);

        assertEquals(List.of("PMDA", "CDISC"), manifest.families());
        assertEquals(Optional.of("CDISC"), manifest.canonicalFamily(" cdisc "));
        assertEquals(Optional.of("PMDA"), manifest.canonicalFamily("Pmda"));
        assertEquals(Optional.empty(), manifest.canonicalFamily("PMAD"));
        assertEquals(Optional.empty(), manifest.canonicalFamily(null));
        assertEquals(Optional.empty(), manifest.canonicalFamily("  "));
        assertEquals("rules-define-cdisc-2-1.json",
                manifest.find("cdisc", "2.1").orElseThrow().file());
        assertEquals(List.of("2.0", "2.1"), manifest.versionsFor("Cdisc"));
    }


    @Test
    void aManifestEntryWithoutAFamilyIsRefused(@TempDir Path aDir) throws IOException
    {
        writeManifest(aDir, """
                {
                  "generatedFrom" : "test",
                  "packages" : [
                    { "file" : "rules-define-x-2-1.json", "version" : "2.1", "ruleCount" : 1 }
                  ]
                }
                """);

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> DefineRulePackageManifest.load(aDir));
        assertTrue(e.getMessage().contains("names no family"), e.getMessage());
        assertTrue(e.getMessage().contains(DefineRulePackageManifest.FILE_NAME), e.getMessage());

        writeManifest(aDir, """
                {
                  "generatedFrom" : "test",
                  "packages" : [
                    { "file" : "rules-define-x-2-1.json", "family" : "  ", "version" : "2.1",
                      "ruleCount" : 1 }
                  ]
                }
                """);
        assertThrows(DefineRuleSelectionException.class,
                () -> DefineRulePackageManifest.load(aDir));
    }


    /** A family spelled like an engine category would merge its findings into that category. */
    @Test
    void aFamilySpelledLikeAnEngineCategoryIsRefused(@TempDir Path aDir) throws IOException
    {
        writeManifest(aDir, """
                {
                  "generatedFrom" : "test",
                  "packages" : [
                    { "file" : "rules-define-schema-2-1.json", "family" : "schema",
                      "version" : "2.1", "ruleCount" : 1 }
                  ]
                }
                """);

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> DefineRulePackageManifest.load(aDir));
        assertTrue(e.getMessage().contains("\"schema\""), e.getMessage());
        assertTrue(e.getMessage().contains("category"), e.getMessage());
    }


    /** One spelling per family, so {@code canonicalFamily} has exactly one answer. */
    @Test
    void twoFamiliesDifferingOnlyInCaseAreRefused(@TempDir Path aDir) throws IOException
    {
        writeManifest(aDir, """
                {
                  "generatedFrom" : "test",
                  "packages" : [
                    { "file" : "rules-define-pmda-2-0.json", "family" : "PMDA",
                      "version" : "2.0", "ruleCount" : 1 },
                    { "file" : "rules-define-pmda-2-1.json", "family" : "Pmda",
                      "version" : "2.1", "ruleCount" : 1 }
                  ]
                }
                """);

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> DefineRulePackageManifest.load(aDir));
        assertTrue(e.getMessage().contains("differ only in case"), e.getMessage());
    }

}
