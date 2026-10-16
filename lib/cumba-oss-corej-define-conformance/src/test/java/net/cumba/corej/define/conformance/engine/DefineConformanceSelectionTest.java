package net.cumba.corej.define.conformance.engine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.define.conformance.report.Category;
import net.cumba.corej.define.conformance.report.RuleExecution;
import net.cumba.corej.define.conformance.rule.DefineFamilySelectionException;
import net.cumba.corej.define.conformance.rule.DefineRulePackageManifest;
import net.cumba.corej.define.conformance.rule.DefineRuleSelectionException;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Rule selection inside {@link DefineConformanceEngine}: the engine resolves its own packages from
 * the {@code (family, version)} key, where the version comes from the document and the family set
 * from the corpus's {@code packages.json} (PLAN-define-families-from-packages).
 *
 * <p>
 * These cases are all about the <b>refusals</b>. Every one of them used to be a silent default — an
 * unspecified family meant "every rule of both sheets", and an undetectable version meant "2.1".
 * Both were survivable while the version merely gated already-loaded rules; both became
 * wrong-answer generators once they started choosing the package. A test that only covered the
 * happy path would not notice them coming back. With an open family set a second class joins them:
 * the typos the closed {@code RuleSet} enum used to catch for free (a manifest, package or rule
 * naming a family nothing publishes), which are now the engine's guards 1 / 1a.
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

    /** One rule as package JSON; {@code aRuleSet} / {@code aSourceType} null ⇒ field absent. */
    private static String rule(String aId, @Nullable String aRuleSet, @Nullable String aSourceType,
            String aVersion)
    {
        return "\"" + aId + "\" : { \"Rule_Id\" : \"" + aId + "\""
                + (aRuleSet == null ? "" : ", \"Rule_Set\" : \"" + aRuleSet + "\"")
                + (aSourceType == null ? "" : ", \"Source_Type\" : \"" + aSourceType + "\"")
                + ", \"Element\" : \"ODM\", \"Applicable_Versions\" : [ \"" + aVersion + "\" ],"
                + " \"Plain_Text_Rule\" : \"text\", \"Message\" : \"message\","
                + " \"Check\" : { \"kind\" : \"exists\", \"target\" : \"@FileOID\" } }";
    }


    /** A package file; {@code aFamily} null ⇒ the {@code family} field is absent. */
    private static String pkg(@Nullable String aFamily, String aVersion, String... aRules)
    {
        return "{ " + (aFamily == null ? "" : "\"family\" : \"" + aFamily + "\", ")
                + "\"version\" : \"" + aVersion + "\", \"rules\" : { " + String.join(",\n", aRules)
                + " } }\n";
    }


    private static String entry(String aFile, String aFamily, String aVersion, int aCount)
    {
        return "{ \"file\" : \"" + aFile + "\", \"family\" : \"" + aFamily + "\", \"version\" : \""
                + aVersion + "\", \"ruleCount\" : " + aCount + " }";
    }


    private static Path writeManifest(Path aDir, String... aEntries) throws IOException
    {
        Files.writeString(aDir.resolve(DefineRulePackageManifest.FILE_NAME),
                "{ \"generatedFrom\" : \"test\", \"packages\" : [ " + String.join(",\n", aEntries)
                        + " ] }\n",
                StandardCharsets.UTF_8);
        return aDir;
    }


    /** The one-rule CDISC 2.1 corpus most cases use. */
    private static Path corpus(Path aDir) throws IOException
    {
        Path dir = Files.createDirectories(aDir.resolve("rules-define"));
        Files.writeString(dir.resolve("rules-define-cdisc-2-1.json"),
                pkg("CDISC", "2.1", rule("DEFINE-XML-0001", "CDISC", null, "2.1")),
                StandardCharsets.UTF_8);
        return writeManifest(dir, entry("rules-define-cdisc-2-1.json", "CDISC", "2.1", 1));
    }


    /** CDISC 2.1, PMDA 2.0 only, FDA 2.1 — a third family the engine has never heard of. */
    private static Path threeFamilyCorpus(Path aDir) throws IOException
    {
        Path dir = Files.createDirectories(aDir.resolve("rules-define"));
        Files.writeString(dir.resolve("rules-define-cdisc-2-1.json"),
                pkg("CDISC", "2.1", rule("DEFINE-XML-0001", "CDISC", "Schema", "2.1")),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("rules-define-pmda-2-0.json"),
                pkg("PMDA", "2.0", rule("PMDA-DD0001", "PMDA", null, "2.0")),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("rules-define-fda-2-1.json"),
                pkg("FDA", "2.1", rule("FDA-DX0001", "FDA", null, "2.1")), StandardCharsets.UTF_8);
        return writeManifest(dir, entry("rules-define-cdisc-2-1.json", "CDISC", "2.1", 1),
                entry("rules-define-pmda-2-0.json", "PMDA", "2.0", 1),
                entry("rules-define-fda-2-1.json", "FDA", "2.1", 1));
    }


    private static Path define(Path aDir, String aName, String aXml) throws IOException
    {
        Path p = aDir.resolve(aName);
        Files.writeString(p, aXml, StandardCharsets.UTF_8);
        return p;
    }


    private static Path site(Path aDir, String aJson) throws IOException
    {
        return define(aDir, "site.json", aJson);
    }


    private static DefineConformanceInput.Builder input(Path aDir, Path aCorpus) throws IOException
    {
        return DefineConformanceInput.builder(define(aDir, "d.xml", DEFINE_21))
                .useDefaultSubmissionFolder(false).rulesDir(aCorpus);
    }


    private static List<String> ruleIds(DefineConformanceReport aReport)
    {
        return aReport.executions().stream().map(RuleExecution::getRuleId).toList();
    }


    @Test
    void selectsThePackageForTheRequestedFamilyAtTheDocumentsVersion(@TempDir Path aDir)
        throws IOException
    {
        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_21)).useDefaultSubmissionFolder(false)
                .rulesDir(corpus(aDir)).families(List.of("CDISC")).build();

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
        // Best effort: with a readable corpus the refusal names what could have been chosen.
        assertTrue(e.getMessage().contains("publishes: CDISC"), e.getMessage());
    }


    @Test
    void anUndetectableVersionIsARefusalRatherThanADefault(@TempDir Path aDir) throws IOException
    {
        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_UNKNOWN)).useDefaultSubmissionFolder(false)
                .rulesDir(corpus(aDir)).families(List.of("CDISC")).build();

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
                .versionOverride("2.1").rulesDir(corpus(aDir)).families(List.of("CDISC")).build();

        assertEquals("2.1", new DefineConformanceEngine().validate(input).defineVersion());
    }


    /**
     * Re-pointed (pre-go review A-6): the corpus publishes the named family, but not at the
     * document's version — the "published for that family" branch, not the unknown-family one.
     */
    @Test
    void aFamilyWithNoPackageAtThatVersionNamesWhatIsPublished(@TempDir Path aDir)
        throws IOException
    {
        Path dir = Files.createDirectories(aDir.resolve("rules-define"));
        Files.writeString(dir.resolve("rules-define-pmda-2-0.json"),
                pkg("PMDA", "2.0", rule("PMDA-DD0001", "PMDA", null, "2.0")),
                StandardCharsets.UTF_8);
        writeManifest(dir, entry("rules-define-pmda-2-0.json", "PMDA", "2.0", 1));
        DefineConformanceInput input = input(aDir, dir).families(List.of("PMDA")).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertInstanceOf(DefineFamilySelectionException.class, e,
                "a named family the corpus publishes at another version is a SELECTION fault");
        assertTrue(e.getMessage().contains("no PMDA rule package published for Define-XML 2.1"),
                e.getMessage());
        assertTrue(e.getMessage().contains("published for that family: [2.0]"), e.getMessage());
    }


    @Test
    void anUnknownFamilyIsRefusedNamingWhatTheManifestPublishes(@TempDir Path aDir)
        throws IOException
    {
        DefineConformanceInput input = input(aDir, corpus(aDir)).families(List.of("PMAD")).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertInstanceOf(DefineFamilySelectionException.class, e);
        assertTrue(e.getMessage().contains("unknown Define-XML rule family \"PMAD\""),
                e.getMessage());
        assertTrue(e.getMessage().contains("publishes: CDISC"), e.getMessage());
    }


    @Test
    void aFamilyNameIsMatchedIgnoringCase(@TempDir Path aDir) throws IOException
    {
        DefineConformanceInput input = input(aDir, corpus(aDir)).families(List.of("cdisc")).build();

        assertEquals(List.of("DEFINE-XML-0001"),
                ruleIds(new DefineConformanceEngine().validate(input)));
    }


    /** The family set is the corpus's: a family no code names loads, selects and runs. */
    @Test
    void aThirdFamilyTheEngineHasNeverHeardOfSelectsAndRuns(@TempDir Path aDir) throws IOException
    {
        DefineConformanceInput input = input(aDir, threeFamilyCorpus(aDir))
                .families(List.of("FDA", "CDISC")).build();

        assertEquals(List.of("FDA-DX0001", "DEFINE-XML-0001"),
                ruleIds(new DefineConformanceEngine().validate(input)));
    }

    // ---- every-published-family mode (DF3) ----------------------------------------------------


    @Test
    void everyPublishedFamilyRunsTheFamiliesPublishingTheVersionAndSkipsTheOthers(
            @TempDir Path aDir)
        throws IOException
    {
        Path dir = threeFamilyCorpus(aDir);
        DefineConformanceInput input = input(aDir, dir).everyPublishedFamily().build();

        DefineConformanceReport report = new DefineConformanceEngine().validate(input);

        // PMDA publishes 2.0 only: skipped, not refused, on a 2.1 document.
        assertEquals(List.of("DEFINE-XML-0001", "FDA-DX0001"), ruleIds(report));
        DefineConformanceEngine.FamilySelection selection = DefineConformanceEngine
                .selectFamilies(input, DefineRulePackageManifest.load(dir), "2.1", dir);
        assertEquals(List.of("CDISC", "FDA"), selection.run());
        assertEquals(List.of("PMDA"), selection.skipped());
    }


    @Test
    void everyPublishedFamilyWithNoFamilyAtThatVersionIsRefused(@TempDir Path aDir)
        throws IOException
    {
        Path dir = Files.createDirectories(aDir.resolve("rules-define"));
        Files.writeString(dir.resolve("rules-define-pmda-2-0.json"),
                pkg("PMDA", "2.0", rule("PMDA-DD0001", "PMDA", null, "2.0")),
                StandardCharsets.UTF_8);
        writeManifest(dir, entry("rules-define-pmda-2-0.json", "PMDA", "2.0", 1));
        DefineConformanceInput input = input(aDir, dir).everyPublishedFamily().build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertInstanceOf(DefineFamilySelectionException.class, e);
        assertTrue(e.getMessage().contains("no Define-XML rule family publishes version 2.1"),
                e.getMessage());
    }


    @Test
    void everyPublishedFamilyIsNotRefusedByTheNoFamilySelectedCheck(@TempDir Path aDir)
        throws IOException
    {
        // A-5: the refusal condition must test the mode, or the browser's blank selection would
        // be refused as "no family selected".
        DefineConformanceInput input = input(aDir, corpus(aDir)).everyPublishedFamily().build();

        assertEquals(1, new DefineConformanceEngine().validate(input).executions().size());
    }


    @Test
    void namingFamiliesAndEveryPublishedFamilyAtOnceIsAmbiguous()
    {
        DefineConformanceInput.Builder builder = DefineConformanceInput.builder(Path.of("d.xml"))
                .families(List.of("CDISC")).everyPublishedFamily();

        IllegalStateException e = assertThrows(IllegalStateException.class, builder::build);
        assertTrue(e.getMessage().contains("ambiguous"), e.getMessage());
    }

    // ---- guard 1: manifest-resolved packages must match their entry ---------------------------


    @Test
    void aManifestPackageWhoseFamilyDiffersFromItsEntryIsRefused(@TempDir Path aDir)
        throws IOException
    {
        Path dir = corpus(aDir);
        Files.writeString(dir.resolve("rules-define-cdisc-2-1.json"),
                pkg("PMDA", "2.1", rule("DEFINE-XML-0001", "PMDA", null, "2.1")),
                StandardCharsets.UTF_8);
        DefineConformanceInput input = input(aDir, dir).families(List.of("CDISC")).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertFalse(e instanceof DefineFamilySelectionException, "a corpus fault, not a selection");
        assertTrue(e.getMessage().contains("rules-define-cdisc-2-1.json"), e.getMessage());
        assertTrue(e.getMessage().contains("\"PMDA\"") && e.getMessage().contains("\"CDISC\""),
                e.getMessage());
    }


    @Test
    void aManifestPackageHoldingARuleOfAnotherFamilyIsRefused(@TempDir Path aDir) throws IOException
    {
        Path dir = corpus(aDir);
        Files.writeString(dir.resolve("rules-define-cdisc-2-1.json"),
                pkg("CDISC", "2.1", rule("DEFINE-XML-0001", "CDISC", null, "2.1"),
                        rule("DEFINE-XML-0002", "PMAD", null, "2.1")),
                StandardCharsets.UTF_8);
        DefineConformanceInput input = input(aDir, dir).families(List.of("CDISC")).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertTrue(e.getMessage().contains("DEFINE-XML-0002"), e.getMessage());
        assertTrue(e.getMessage().contains("\"PMAD\"") && e.getMessage().contains("\"CDISC\""),
                e.getMessage());
    }


    @Test
    void aManifestPackageHoldingARuleWithoutRuleSetIsRefused(@TempDir Path aDir) throws IOException
    {
        Path dir = corpus(aDir);
        Files.writeString(dir.resolve("rules-define-cdisc-2-1.json"),
                pkg("CDISC", "2.1", rule("DEFINE-XML-0001", null, null, "2.1")),
                StandardCharsets.UTF_8);
        DefineConformanceInput input = input(aDir, dir).families(List.of("CDISC")).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertTrue(e.getMessage().contains("DEFINE-XML-0001")
                && e.getMessage().contains("no Rule_Set"), e.getMessage());
    }


    @Test
    void aManifestPackageWithoutAFamilyIsRefused(@TempDir Path aDir) throws IOException
    {
        Path dir = corpus(aDir);
        Files.writeString(dir.resolve("rules-define-cdisc-2-1.json"),
                pkg(null, "2.1", rule("DEFINE-XML-0001", "CDISC", null, "2.1")),
                StandardCharsets.UTF_8);
        DefineConformanceInput input = input(aDir, dir).families(List.of("CDISC")).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertTrue(e.getMessage().contains("declares no family"), e.getMessage());
    }

    // ---- guard 1a: site packages (--define-rules-file) ----------------------------------------


    /**
     * A-1 / M-1: a site package is additive and has no manifest entry to match. It may omit its
     * family, hold a rule without {@code Rule_Set}, and hold a rule whose {@code Rule_Set} differs
     * from its family — everything the closed enum loaded still loads.
     */
    @Test
    void aSitePackageLoadsWithoutFamilyAndWithUnmatchedRuleSets(@TempDir Path aDir)
        throws IOException
    {
        Path site = site(aDir, pkg(null, "2.1", rule("SITE-0001", null, null, "2.1"),
                rule("SITE-0002", "CDISC", null, "2.1")));
        Path pmdaSite = define(aDir, "pmda-site.json",
                pkg("CDISC", "2.1", rule("SITE-0003", "PMDA", null, "2.1")));
        DefineConformanceInput input = input(aDir, threeFamilyCorpus(aDir))
                .families(List.of("CDISC")).rulesFiles(List.of(site, pmdaSite)).build();

        DefineConformanceReport report = new DefineConformanceEngine().validate(input);

        assertEquals(List.of("DEFINE-XML-0001", "SITE-0001", "SITE-0002", "SITE-0003"),
                ruleIds(report));
    }


    @Test
    void aSiteRuleNamingAnUnpublishedFamilyIsRefused(@TempDir Path aDir) throws IOException
    {
        Path site = site(aDir, pkg(null, "2.1", rule("SITE-0001", "PMAD", null, "2.1")));
        DefineConformanceInput input = input(aDir, corpus(aDir)).families(List.of("CDISC"))
                .rulesFiles(List.of(site)).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertTrue(e.getMessage().contains("SITE-0001") && e.getMessage().contains("\"PMAD\""),
                e.getMessage());
        assertTrue(e.getMessage().contains("published: CDISC"), e.getMessage());
    }


    @Test
    void aSitePackageDeclaringAnUnpublishedFamilyIsRefused(@TempDir Path aDir) throws IOException
    {
        Path site = site(aDir, pkg("FDA", "2.1", rule("SITE-0001", null, null, "2.1")));
        DefineConformanceInput input = input(aDir, corpus(aDir)).families(List.of("CDISC"))
                .rulesFiles(List.of(site)).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertTrue(e.getMessage().contains("site.json") && e.getMessage().contains("\"FDA\""),
                e.getMessage());
    }


    /** An engine category name is never a published family, so a site rule cannot hide under it. */
    @Test
    void aSiteRuleNamingAnEngineCategoryAsItsFamilyIsRefused(@TempDir Path aDir) throws IOException
    {
        Path site = site(aDir, pkg(null, "2.1", rule("SITE-0001", "Schema", null, "2.1")));
        DefineConformanceInput input = input(aDir, corpus(aDir)).families(List.of("CDISC"))
                .rulesFiles(List.of(site)).build();

        DefineRuleSelectionException e = assertThrows(DefineRuleSelectionException.class,
                () -> new DefineConformanceEngine().validate(input));
        assertTrue(e.getMessage().contains("\"Schema\""), e.getMessage());
    }


    @Test
    void aSiteRuleWithACaseVariantOfAPublishedFamilyLoads(@TempDir Path aDir) throws IOException
    {
        Path site = site(aDir, pkg(null, "2.1", rule("SITE-0001", "Pmda", null, "2.1")));
        DefineConformanceInput input = input(aDir, threeFamilyCorpus(aDir))
                .families(List.of("CDISC")).rulesFiles(List.of(site)).build();

        assertEquals(List.of("DEFINE-XML-0001", "SITE-0001"),
                ruleIds(new DefineConformanceEngine().validate(input)));
    }


    /**
     * The two documented category moves for site rules (DF1 (a), §5.6), pinned: a rule without
     * {@code Source_Type} reports under its family's category — {@code CDISC} here, where the enum
     * era said {@code SPECIFICATION} — and a case variant of a published family reports under the
     * manifest's spelling.
     */
    @Test
    void aSiteRuleWithoutSourceTypeReportsUnderItsFamilyCategory(@TempDir Path aDir)
        throws IOException
    {
        String firing = "\"Element\" : \"ODM\", \"Applicable_Versions\" : [ \"2.1\" ],"
                + " \"Plain_Text_Rule\" : \"text\", \"Message\" : \"message\","
                + " \"Check\" : { \"kind\" : \"exists\", \"target\" : \"@NoSuchAttribute\" } }";
        Path site = site(aDir, "{ \"version\" : \"2.1\", \"rules\" : { "
                + "\"SITE-0001\" : { \"Rule_Id\" : \"SITE-0001\", \"Rule_Set\" : \"CDISC\", "
                + firing
                + ", \"SITE-0002\" : { \"Rule_Id\" : \"SITE-0002\", \"Rule_Set\" : \"Pmda\", "
                + firing + " } }\n");
        DefineConformanceInput input = input(aDir, threeFamilyCorpus(aDir))
                .families(List.of("CDISC")).rulesFiles(List.of(site)).build();

        DefineConformanceReport report = new DefineConformanceEngine().validate(input);

        assertEquals(Category.of("CDISC"), categoryOf(report, "SITE-0001"));
        assertEquals(Category.of("PMDA"), categoryOf(report, "SITE-0002"));
    }


    private static Category categoryOf(DefineConformanceReport aReport, String aRuleId)
    {
        return aReport.findings().stream().filter(f -> aRuleId.equals(f.getRuleId())).findFirst()
                .orElseThrow(() -> new AssertionError(
                        aRuleId + " produced no finding: " + aReport.findings()))
                .getCategory();
    }

    // ---- corpus consistency ------------------------------------------------------------------


    @Test
    void aManifestNamingAMissingPackageIsAnInconsistentCorpus(@TempDir Path aDir) throws IOException
    {
        Path dir = corpus(aDir);
        Files.delete(dir.resolve("rules-define-cdisc-2-1.json"));

        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_21)).useDefaultSubmissionFolder(false)
                .rulesDir(dir).families(List.of("CDISC")).build();

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
                .useDefaultSubmissionFolder(false).rulesDir(corpus(aDir)).families(List.of("CDISC"))
                .build();

        DefineConformanceReport report = new DefineConformanceEngine().validate(input);

        // Pin the XSD-category fatal, not merely "some finding": the raw-declaration checks can
        // satisfy a bare count on their own, which would leave this test green even if the
        // unparseable path stopped being reached.
        assertTrue(report.findings().stream().anyMatch(f -> Category.XSD.equals(f.getCategory())),
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
                .rulesDir(dir).families(List.of("CDISC")).build();

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
        writeManifest(dir, entry("rules-define-cdisc-2-1.json", "CDISC", "2.1", 99));

        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_21)).useDefaultSubmissionFolder(false)
                .rulesDir(dir).families(List.of("CDISC")).build();

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
                .versionOverride("   ").rulesDir(corpus(aDir)).families(List.of("CDISC")).build();

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
        writeManifest(dir, entry("rules-define-cdisc-2-1.json", "CDISC", "2.1", 1));

        DefineConformanceInput input = DefineConformanceInput
                .builder(define(aDir, "d.xml", DEFINE_21)).useDefaultSubmissionFolder(false)
                .rulesDir(dir).families(List.of("CDISC")).build();

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
