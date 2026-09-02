package net.cumba.cdisc.core.metadata.dictionary;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * The packaged licence notices — compliance-sensitive content, so beyond "a notice exists" this
 * pins the claims that PLAN-dictionary-seeder §4.1–§4.2 corrected: no Creative Commons grant may be
 * asserted for MED-RT or the neoplasm subset, and LOINC's required notice must be the verbatim
 * text, ® symbols included.
 */
class DictionaryLicencesTest
{

    /** The text LOINC's licence prescribes, quoted in {@code dictionaries/README.md} §5.1. */
    private static final String LOINC_SHORT_LICENCE = """
            This material contains content from LOINC (http://loinc.org). LOINC is
            copyright © Regenstrief Institute, Inc. and the Logical Observation
            Identifiers Names and Codes (LOINC) Committee and is available at no
            cost under the license at http://loinc.org/license. LOINC® is a
            registered United States trademark of Regenstrief Institute, Inc.
            """;

    @ParameterizedTest(name = "{0} has a packaged notice")
    @ValueSource(strings =
    {
            "meddra", "whodrug", "loinc", "medrt", "unii", "snomed", "neoplasm"
    })
    void everyInstallableTypeHasANotice(String type)
    {
        String notice = DictionaryLicences.noticeFor(type);
        assertNotNull(notice, "no packaged notice for " + type);
        assertTrue(notice.contains("converted"),
                "each notice must say the file is a converted extract, not the authoritative "
                        + "release: " + type);
    }


    @Test
    void anUnknownTypeHasNone()
    {
        assertNull(DictionaryLicences.noticeFor("nosuchdictionary"));
        assertNull(DictionaryLicences.noticeFor("../../etc/passwd"));
    }


    /**
     * ⛔ Plan §4.2: the NCI CC BY 4.0 statement names only "The NCI Thesaurus™". MED-RT and the
     * CDISC/neoplasm subset must record absence of restriction, never a Creative Commons grant —
     * asserting a grant that does not exist is worse than shipping nothing.
     */
    @ParameterizedTest(name = "{0} asserts no Creative Commons grant")
    @ValueSource(strings =
    {
            "medrt", "neoplasm"
    })
    void noCreativeCommonsGrantIsAssertedForTheEvsHostedTypes(String type)
    {
        String notice = DictionaryLicences.noticeFor(type);
        assertNotNull(notice);
        assertTrue(notice.contains("NOT distributed under a Creative Commons licence"),
                "the notice must expressly negate the CC reading, not merely omit it: " + notice);
        assertTrue(notice.contains("free of charge") || notice.contains("17 U.S.C."),
                "and state the actual basis: " + notice);
    }


    @Test
    void theMedRtNoticeNamesTheRealProducer()
    {
        String notice = DictionaryLicences.noticeFor("medrt");
        assertNotNull(notice);
        assertTrue(notice.contains("Veterans"), "the producer is the VA/VHA, not NCI: " + notice);
        assertTrue(notice.contains("no licence or terms-of-use document is published"), notice);
    }


    /**
     * Plan §4.4 committed to asserting the notice against the <b>installer's output</b>, not the
     * source tree: this runs a real {@code install(...)} of a minimal LOINC distribution into a
     * temp store and byte-compares the file the installer actually wrote beside the data — so the
     * whole pipeline (classpath resource &rarr; {@code extraFilesFor} &rarr; installer write, UTF-8
     * throughout) is covered, and an encoding or write defect cannot hide behind an in-memory
     * string comparison.
     *
     * <p>
     * ⚠ The reference text here is still a transcription of the same prescribed notice — a
     * transcription error made twice would not be caught by any in-repo test; only a comparison
     * against loinc.org itself could. Note also the shipped text carries no {@code © 1995-<year>}
     * span; whether the licence requires one has NOT been verified against loinc.org (flagged in
     * the Phase 9 batch-B review, deliberately not guessed at here).
     * </p>
     */
    @Test
    void theLoincShortLicenceIsWrittenVerbatimByTheInstaller(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path tempDir)
        throws java.io.IOException
    {
        java.nio.file.Path raw = java.nio.file.Files.createDirectories(tempDir.resolve("raw"));
        java.nio.file.Files.writeString(raw.resolve("Loinc.csv"),
                "\"LOINC_NUM\",\"LONG_COMMON_NAME\",\"VersionLastChanged\"\n"
                        + "\"1558-6\",\"Fasting glucose [Mass/volume] in Serum or Plasma\","
                        + "\"2.80\"\n",
                java.nio.charset.StandardCharsets.UTF_8);
        java.nio.file.Path store = tempDir.resolve("store");
        DictionaryInstaller installer = new DictionaryInstaller(store);

        String version = installer.install(new LocalDictionarySource(raw), new LoincConverter(),
                DictionaryLicences.noticeFor("loinc"), DictionaryLicences.extraFilesFor("loinc"),
                false);

        assertNotNull(version, "the minimal distribution must install: "
                + installer.getReport().getSkipped() + " " + installer.getReport().getWarnings());
        byte[] written = java.nio.file.Files.readAllBytes(store.resolve("loinc").resolve(version)
                .resolve("LICENSES").resolve(DictionaryLicences.LOINC_SHORT_LICENCE_FILE));
        org.junit.jupiter.api.Assertions.assertArrayEquals(
                LOINC_SHORT_LICENCE.getBytes(java.nio.charset.StandardCharsets.UTF_8), written,
                "the required notice must land on disk byte-identical, ® and © included");
    }


    @Test
    void onlyLoincHasExtraFiles()
    {
        assertTrue(DictionaryLicences.extraFilesFor("medrt").isEmpty());
        assertFalse(DictionaryLicences.extraFilesFor("loinc").isEmpty());
    }
}
