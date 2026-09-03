package net.cumba.corej.define.conformance.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.define.conformance.report.Category;
import net.cumba.corej.define.conformance.rule.DefineRulePackageManifest;
import net.cumba.corej.define.conformance.rule.DefineRuleSelectionException;
import net.cumba.corej.define.conformance.rule.RuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rule selection inside {@link DefineConformanceEngine}: the engine resolves its own packages from
 * the {@code (family, version)} key, where the version comes from the document.
 *
 * <p>
 * These cases are all about the <b>refusals</b>. Every one of them used to be a silent default — an
 * unspecified family meant "every rule of both sheets", and an undetectable version meant "2.1".
 * Both were survivable while the version merely gated already-loaded rules; both became
 * wrong-answer generators once they started choosing the package. A test that only covered the
 * happy path would not notice them coming back.
 * </p>
 */
class DefineConformanceSelectionTest
{

    private static final String DEFINE_21 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1" FileOID="F1" ODMVersion="1.3.2">
              <Study OID="S1">
                <MetaDataVersion OID="M1" Name="m" def:DefineVersion="2.1.0"/>
              </Study>
            </ODM>
            """;

    /** A define.xml with no version signal at all, so detection returns nothing. */
    private static final String DEFINE_UNKNOWN = """
            <?xml version="1.0" encoding="UTF-8"?>
            <NotOdm/>
            """;

    private static Path corpus(Path aDir) throws IOException
    {
        Path dir = Files.createDirectories(aDir.resolve("rules-define"));
        Files.writeString(dir.resolve("rules-define-cdisc-2-1.json"), """
                {
                  "family" : "CDISC",
                  "version" : "2.1",
                  "rules" : {
                    "DEFINE-XML-0001" : {
                      "Rule_Id" : "DEFINE-XML-0001",
                      "Rule_Set" : "CDISC",
                      "Element" : "ODM",
                      "Applicable_Versions" : [ "2.1" ],
                      "Plain_Text_Rule" : "text",
                      "Message" : "message",
                      "Check" : { "kind" : "exists", "target" : "@FileOID" }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(DefineRulePackageManifest.FILE_NAME), """
                {
                  "generatedFrom" : "test",
                  "packages" : [
                    { "file" : "rules-define-cdisc-2-1.json", "family" : "CDISC",
                      "version" : "2.1", "ruleCount" : 1 }
                  ]
                }
                """, StandardCharsets.UTF_8);
        return dir;
    }


    private static Path define(Path aDir, String aName, String aXml) throws IOException
    {
        Path p = aDir.resolve(aName);
        Files.writeString(p, aXml, StandardCharsets.UTF_8);
        return p;
    }


    @Test
    void selectsThePackageForTheRequestedFamilyAtTheDocumentsVersion(@TempDir Path aDir)
        throws IOException
    {
        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_21)).useDefaultSubmissionFolder(false)
                .rulesDir(corpus(aDir)).families(List.of(RuleSet.CDISC)).build();

        DefineConformanceReport report = new DefineConformanceEngine().validate(input);

        assertEquals("2.1", report.defineVersion());
        assertEquals(1, report.executions().size(), "the one rule in the 2.1 package must run");
        assertEquals("DEFINE-XML-0001", report.executions().get(0).getRuleId());
    }


    @Test
    void noFamilySelectedIsARefusal(@TempDir Path aDir) throws IOException
    {
        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_21)).useDefaultSubmissionFolder(false)
                .rulesDir(corpus(aDir)).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertTrue(e.getMessage().contains("family"), e.getMessage());
    }


    @Test
    void anUndetectableVersionIsARefusalRatherThanADefault(@TempDir Path aDir) throws IOException
    {
        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_UNKNOWN)).useDefaultSubmissionFolder(false)
                .rulesDir(corpus(aDir)).families(List.of(RuleSet.CDISC)).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertTrue(e.getMessage().contains("--define-version"), e.getMessage());
    }


    @Test
    void anExplicitVersionOverrideRescuesAnUndetectableDocument(@TempDir Path aDir)
        throws IOException
    {
        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_UNKNOWN)).useDefaultSubmissionFolder(false)
                .versionOverride("2.1").rulesDir(corpus(aDir)).families(List.of(RuleSet.CDISC))
                .build();

        assertEquals("2.1", new DefineConformanceEngine().validate(input).defineVersion());
    }


    @Test
    void aFamilyWithNoPackageAtThatVersionNamesWhatIsPublished(@TempDir Path aDir)
        throws IOException
    {
        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_21)).useDefaultSubmissionFolder(false)
                .rulesDir(corpus(aDir)).families(List.of(RuleSet.PMDA)).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertTrue(e.getMessage().contains("PMDA"), e.getMessage());
    }


    @Test
    void aManifestNamingAMissingPackageIsAnInconsistentCorpus(@TempDir Path aDir) throws IOException
    {
        Path dir = corpus(aDir);
        Files.delete(dir.resolve("rules-define-cdisc-2-1.json"));

        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_21)).useDefaultSubmissionFolder(false)
                .rulesDir(dir).families(List.of(RuleSet.CDISC)).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertTrue(e.getMessage().contains("inconsistent"), e.getMessage());
    }


    /**
     * ⛔ REGRESSION GUARD. Selection ran before the DOM parse, so a not-well-formed document — which
     * has no detectable version — hit the version refusal and threw out of {@code validate},
     * destroying the {@code unparseableReport} path this class promises never to throw past. The
     * pre-existing test for that path used the explicit-corpus constructor, which bypasses
     * selection, so nothing covered the self-resolving engine here.
     */
    @Test
    void aNotWellFormedDocumentIsReportedNotThrown(@TempDir Path aDir) throws IOException
    {
        Path broken = define(aDir, "broken.xml", "<ODM><Study></ODM>");
        DefineConformanceInput input = DefineConformanceInput.builder(broken)
                .useDefaultSubmissionFolder(false).rulesDir(corpus(aDir))
                .families(List.of(RuleSet.CDISC)).build();

        DefineConformanceReport report = new DefineConformanceEngine().validate(input);

        // Pin the XSD-category fatal, not merely "some finding": the raw-declaration checks can
        // satisfy a bare count on their own, which would leave this test green even if the
        // unparseable path stopped being reached.
        assertTrue(report.findings().stream().anyMatch(f -> f.getCategory() == Category.XSD),
                "a broken document must be REPORTED with its XSD fatal, not thrown past: "
                        + report.findings());
    }


    /**
     * A package emptied after its manifest was written — a partial copy, a failed edit, a full
     * disk. Loading zero rules and reporting the document clean is indistinguishable from a
     * conformant document, so it must refuse.
     */
    @Test
    void anEmptiedPackageIsRefusedRatherThanReportedClean(@TempDir Path aDir) throws IOException
    {
        Path dir = corpus(aDir);
        Files.writeString(dir.resolve("rules-define-cdisc-2-1.json"),
                "{ \"family\" : \"CDISC\", \"version\" : \"2.1\", \"rules\" : { } }",
                StandardCharsets.UTF_8);

        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_21)).useDefaultSubmissionFolder(false)
                .rulesDir(dir).families(List.of(RuleSet.CDISC)).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertTrue(e.getMessage().contains("empty corpus") || e.getMessage().contains("corrupt"),
                e.getMessage());
    }


    /** The manifest's ruleCount is a corruption check only if something actually reads it. */
    @Test
    void aPackageHoldingFewerRulesThanTheManifestClaimsIsRefused(@TempDir Path aDir)
        throws IOException
    {
        Path dir = corpus(aDir);
        Files.writeString(dir.resolve(DefineRulePackageManifest.FILE_NAME), """
                {
                  "generatedFrom" : "test",
                  "packages" : [
                    { "file" : "rules-define-cdisc-2-1.json", "family" : "CDISC",
                      "version" : "2.1", "ruleCount" : 99 }
                  ]
                }
                """, StandardCharsets.UTF_8);

        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_21)).useDefaultSubmissionFolder(false)
                .rulesDir(dir).families(List.of(RuleSet.CDISC)).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertTrue(e.getMessage().contains("corrupt"), e.getMessage());
    }


    /** A blank override is absent, not a version — it must not slip past into the 2.1 fallback. */
    @Test
    void aBlankVersionOverrideIsTreatedAsAbsent(@TempDir Path aDir) throws IOException
    {
        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_UNKNOWN)).useDefaultSubmissionFolder(false)
                .versionOverride("   ").rulesDir(corpus(aDir)).families(List.of(RuleSet.CDISC))
                .build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertTrue(e.getMessage().contains("--define-version"), e.getMessage());
    }


    /**
     * The distinction the type exists for: a rule whose {@code custom} check names a class that
     * cannot be instantiated is an ENGINE fault, not a selection fault, and must not be dressed up
     * as one. A front end that reports it as a usage error sends the operator to inspect their own
     * configuration for someone else's bug.
     */
    @Test
    void anEvaluationFaultIsNotASelectionFault(@TempDir Path aDir) throws IOException
    {
        Path dir = Files.createDirectories(aDir.resolve("rules-define"));
        Files.writeString(dir.resolve("rules-define-cdisc-2-1.json"), """
                {
                  "family" : "CDISC", "version" : "2.1",
                  "rules" : {
                    "DEFINE-XML-0001" : {
                      "Rule_Id" : "DEFINE-XML-0001", "Rule_Set" : "CDISC", "Element" : "ODM",
                      "Applicable_Versions" : [ "2.1" ], "Plain_Text_Rule" : "t",
                      "Message" : "m",
                      "Check" : { "kind" : "custom", "className" : "no.such.CheckClass" }
                    }
                  }
                }
                """, StandardCharsets.UTF_8);
        Files.writeString(dir.resolve(DefineRulePackageManifest.FILE_NAME), """
                {
                  "generatedFrom" : "test",
                  "packages" : [
                    { "file" : "rules-define-cdisc-2-1.json", "family" : "CDISC",
                      "version" : "2.1", "ruleCount" : 1 }
                  ]
                }
                """, StandardCharsets.UTF_8);

        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_21)).useDefaultSubmissionFolder(false)
                .rulesDir(dir).families(List.of(RuleSet.CDISC)).build();

        RuntimeException e = assertThrows(RuntimeException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertFalse(e instanceof DefineRuleSelectionException,
                "an unresolvable CustomCheck is an engine fault, not a selection fault — typing it"
                        + " as one would report it to the operator as their misconfiguration");
    }


    @Test
    void anExplicitCorpusBypassesSelectionEntirely(@TempDir Path aDir) throws IOException
    {
        // The DefineConformanceEngine(List) overload is what the corpus-driven suites use. It must
        // keep working without any family being named, because supplying the rules IS the
        // selection.
        var rules = net.cumba.corej.define.conformance.rule.RuleRepository
                .loadPackage(corpus(aDir).resolve("rules-define-cdisc-2-1.json")).ruleList();
        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_21)).useDefaultSubmissionFolder(false)
                .build();

        assertEquals(1, new DefineConformanceEngine(rules).validate(input).executions().size());
    }

}
