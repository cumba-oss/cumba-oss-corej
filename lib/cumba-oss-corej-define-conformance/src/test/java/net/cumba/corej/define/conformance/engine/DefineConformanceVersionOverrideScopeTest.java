package net.cumba.corej.define.conformance.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.define.conformance.report.Category;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.Severity;
import net.cumba.corej.define.conformance.rule.DefineRulePackageManifest;
import net.cumba.corej.define.conformance.rule.RuleSet;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The Define-XML 1.0 out-of-scope short-circuit versus {@code versionOverride} blankness
 * (F-corej-L3-01, work-order rows DCE-1 / DCE-2 / DCE-3).
 *
 * <p>
 * The engine treats a blank override as ABSENT everywhere ({@code resolveVersion},
 * {@code selectRules}); the short-circuit at the top of {@code validate} must agree. Before the
 * fix, {@code versionOverride("")} slipped past the null-only test there, so a 1.0 document was
 * validated against the <b>2.1</b> corpus, labelled {@code "2.1"}, with the
 * {@code DEFINE-XML-UNSUPPORTED} declaration absent — a wrong conformance verdict whose two
 * triggers differ only by {@code ""} vs {@code null}.
 * </p>
 */
class DefineConformanceVersionOverrideScopeTest
{

    /** A well-formed Define-XML 1.0 document (detected via {@code def:DefineVersion="1.0.0"}). */
    private static final String DEFINE_10 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.2"
                 xmlns:def="http://www.cdisc.org/ns/def/v1.0" FileOID="F1" ODMVersion="1.2.1">
              <Study OID="S1">
                <MetaDataVersion OID="M1" Name="m" def:DefineVersion="1.0.0"/>
              </Study>
            </ODM>
            """;

    private static final String DEFINE_21 = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1" FileOID="F1" ODMVersion="1.3.2">
              <Study OID="S1">
                <MetaDataVersion OID="M1" Name="m" def:DefineVersion="2.1.0"/>
              </Study>
            </ODM>
            """;

    private static final String UNSUPPORTED_RULE_ID = "DEFINE-XML-UNSUPPORTED";

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


    private static Path define(Path aDir, String aXml) throws IOException
    {
        Path p = aDir.resolve("d.xml");
        Files.writeString(p, aXml, StandardCharsets.UTF_8);
        return p;
    }


    private static DefineConformanceReport validate10(Path aDir, String aOverride)
        throws IOException
    {
        DefineConformanceInput.Builder builder = DefineConformanceInput
                .builder(define(aDir, DEFINE_10)).useDefaultSubmissionFolder(false)
                .rulesDir(corpus(aDir)).families(List.of(RuleSet.CDISC));
        if (aOverride != null)
        {
            builder.versionOverride(aOverride);
        }
        return new DefineConformanceEngine().validate(builder.build());
    }


    private static void assertOutOfScope(DefineConformanceReport aReport)
    {
        assertEquals("1.0", aReport.defineVersion());
        assertEquals(List.of(), aReport.executions(), "no rule may execute on a 1.0 document");
        ConformanceFinding unsupported = aReport.findings().stream()
                .filter(f -> UNSUPPORTED_RULE_ID.equals(f.getRuleId())).findFirst().orElseThrow(
                        () -> new AssertionError("missing " + UNSUPPORTED_RULE_ID + " finding"));
        assertEquals(Category.XSD, unsupported.getCategory());
        assertEquals(Severity.WARNING, unsupported.getSeverity());
        assertTrue(
                unsupported.getMessage()
                        .contains("the 2.0/2.1 conformance rules were not evaluated"),
                unsupported.getMessage());
    }


    /** DCE-3 — the already-correct side: a null override on a 1.0 document short-circuits. */
    @Test
    void nullOverride_on10Document_isOutOfScope(@TempDir Path aDir) throws IOException
    {
        assertOutOfScope(validate10(aDir, null));
    }


    /**
     * DCE-1, the F-corej-L3-01 fix: a BLANK override is absent, so a 1.0 document must take the
     * same out-of-scope short-circuit — not be validated against the 2.1 corpus and labelled
     * {@code "2.1"} with the unsupported-declaration missing.
     */
    @Test
    void blankOverride_on10Document_isOutOfScope(@TempDir Path aDir) throws IOException
    {
        assertOutOfScope(validate10(aDir, ""));
    }


    /** Same as above for a whitespace-only override — {@code isBlank}, not {@code isEmpty}. */
    @Test
    void whitespaceOverride_on10Document_isOutOfScope(@TempDir Path aDir) throws IOException
    {
        assertOutOfScope(validate10(aDir, "   "));
    }


    /**
     * The honour-the-override side of the same branch: a REAL override on a 1.0 document
     * deliberately skips the short-circuit ("they know what they're doing") and validates against
     * the overridden version.
     */
    @Test
    void realOverride_on10Document_skipsTheShortCircuit(@TempDir Path aDir) throws IOException
    {
        DefineConformanceReport report = validate10(aDir, "2.1");
        assertEquals("2.1", report.defineVersion());
        assertEquals(1, report.executions().size());
        assertTrue(report.findings().stream()
                .noneMatch(f -> UNSUPPORTED_RULE_ID.equals(f.getRuleId())));
    }


    /**
     * DCE-2 — the other conjunct: a blank override on a NON-1.0 document must not short-circuit.
     * Pins that the short-circuit tests the detected version, not just the override.
     */
    @Test
    void blankOverride_on21Document_validatesNormally(@TempDir Path aDir) throws IOException
    {
        DefineConformanceInput input = DefineConformanceInput.builder(define(aDir, DEFINE_21))
                .useDefaultSubmissionFolder(false).versionOverride("").rulesDir(corpus(aDir))
                .families(List.of(RuleSet.CDISC)).build();
        DefineConformanceReport report = new DefineConformanceEngine().validate(input);
        assertEquals("2.1", report.defineVersion());
        assertEquals(1, report.executions().size());
        assertTrue(report.findings().stream()
                .noneMatch(f -> UNSUPPORTED_RULE_ID.equals(f.getRuleId())));
    }

}
