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
 * {@code cumba-oss-corej-rules} — this module cannot depend on it (that would be a reactor cycle),
 * and should not: these are unit tests of the format, not of the corpus.
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


    private static Path writePackage(Path aDir, String aFile, RuleSet aFamily, String aVersion,
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
                DefineRulePackageManifest.packageFileName(RuleSet.CDISC, "2.1"));
        assertEquals("rules-define-pmda-2-0.json",
                DefineRulePackageManifest.packageFileName(RuleSet.PMDA, "2.0"));
    }

    // ---- package reading ----------------------------------------------------------------------


    @Test
    void aPackageRoundTripsItsFamilyVersionAndRules(@TempDir Path aDir) throws IOException
    {
        Path file = writePackage(aDir, "p.json", RuleSet.CDISC, "2.1",
                rule("DEFINE-XML-0001", "2.1"), rule("DEFINE-XML-0002", "2.1"));

        DefineRulePackage pkg = RuleRepository.loadPackage(file);

        assertEquals(RuleSet.CDISC, pkg.family());
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
        Path cdisc = writePackage(aDir, "c.json", RuleSet.CDISC, "2.1",
                rule("DEFINE-XML-0001", "2.1"));
        Path pmda = writePackage(aDir, "p.json", RuleSet.PMDA, "2.1", rule("PMDA-DD0012", "2.1"));

        List<ConformanceRule> rules = RuleRepository.loadPackages(List.of(cdisc, pmda));

        assertEquals(2, rules.size());
    }


    @Test
    void twoVersionsOfOneFamilyCollideOnRuleId(@TempDir Path aDir) throws IOException
    {
        // A rule applying to both versions is published in BOTH packages, so loading two versions
        // together would silently double it. The duplicate guard is what makes "exactly one
        // version at a time" enforceable rather than merely documented.
        Path v20 = writePackage(aDir, "v20.json", RuleSet.CDISC, "2.0",
                rule("DEFINE-XML-0001", "2.0", "2.1"));
        Path v21 = writePackage(aDir, "v21.json", RuleSet.CDISC, "2.1",
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
                manifest.find(RuleSet.CDISC, "2.1").orElseThrow().file());
        assertEquals(2, manifest.find(RuleSet.CDISC, "2.1").orElseThrow().ruleCount());
        assertEquals(Optional.empty(), manifest.find(RuleSet.PMDA, "2.0"));
        assertEquals(List.of("2.0", "2.1"), manifest.versionsFor(RuleSet.CDISC));
        assertEquals(List.of("2.1"), manifest.versionsFor(RuleSet.PMDA));
    }


    @Test
    void aDirectoryWithNoManifestIsAClearError(@TempDir Path aDir)
    {
        IOException e = assertThrows(IOException.class, () -> DefineRulePackageManifest.load(aDir));
        assertTrue(e.getMessage().contains(DefineRulePackageManifest.FILE_NAME), e.getMessage());
    }

}
