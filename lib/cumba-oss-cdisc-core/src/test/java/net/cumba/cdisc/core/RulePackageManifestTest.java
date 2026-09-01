package net.cumba.cdisc.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.cumba.cdisc.core.RulePackageManifest.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulePackageManifestTest
{

    private static RulePackageManifest sample()
    {
        return new RulePackageManifest(
                List.of(new Entry("rules-cdisc-sdtmig-3-4.json", "CDISC", "SDTMIG", "3.4", 368),
                        new Entry("rules-core-sdtmig-3-4.json", "CORE", "SDTMIG", "3.4", 445),
                        new Entry("rules-fda-sdtmig-3-4.json", "FDA", "SDTMIG", "3.4", 454),
                        new Entry("rules-pmda-sdtmig-3-4.json", "PMDA", "SDTMIG", "3.4", 440),
                        new Entry("rules-cdisc-adamig-1-3.json", "CDISC", "ADaMIG", "1.3", 698),
                        new Entry("rules-pmda-adamig-1-3.json", "PMDA", "ADaMIG", "1.3", 291)));
    }


    @Test
    void load_returnsEmptyWhenManifestAbsent(@TempDir Path dir) throws IOException
    {
        RulePackageManifest manifest = RulePackageManifest.load(dir);
        assertTrue(manifest.packages().isEmpty());
    }


    @Test
    void writeTo_thenLoad_roundTrips(@TempDir Path dir) throws IOException
    {
        sample().writeTo(dir);
        RulePackageManifest loaded = RulePackageManifest.load(dir);
        assertEquals(6, loaded.packages().size());
        assertEquals("rules-cdisc-sdtmig-3-4.json",
                loaded.find("CDISC", "SDTMIG", "3.4").orElseThrow().file());
    }


    @Test
    void find_isCaseInsensitiveOnFamilyAndStandard()
    {
        RulePackageManifest m = sample();
        assertEquals("rules-fda-sdtmig-3-4.json",
                m.find("fda", "sdtmig", "3.4").orElseThrow().file());
        assertEquals("rules-fda-sdtmig-3-4.json",
                m.find("FDA", "SDTMIG", "3.4").orElseThrow().file());
    }


    @Test
    void find_toleratesDisplayOrEncodedVersion()
    {
        RulePackageManifest m = sample();
        // manifest stores "3.4"; a caller may pass the file-encoded "3-4".
        assertEquals("rules-core-sdtmig-3-4.json",
                m.find("CORE", "SDTMIG", "3-4").orElseThrow().file());
        assertEquals("rules-core-sdtmig-3-4.json",
                m.find("CORE", "SDTMIG", "3.4").orElseThrow().file());
    }


    @Test
    void find_absentTupleIsEmpty()
    {
        assertTrue(sample().find("CORE", "ADaMIG", "1.3").isEmpty());
        assertFalse(sample().find("CDISC", "ADaMIG", "1.3").isEmpty());
    }


    @Test
    void forFamily_returnsAllOfAFamily()
    {
        List<Entry> cdisc = sample().forFamily("CDISC");
        assertEquals(2, cdisc.size());
    }


    @Test
    void forStandardVersion_returnsTheUnionSet()
    {
        // SDTMIG 3.4 -> the four family files (cdisc, core, fda, pmda).
        assertEquals(4, sample().forStandardVersion("SDTMIG", "3.4").size());
        // ADaMIG 1.3 -> the two family files (cdisc, pmda) after the ADAMCR->CDISC merge.
        assertEquals(2, sample().forStandardVersion("ADaMIG", "1.3").size());
    }


    @Test
    void toPaths_resolvesAgainstDir(@TempDir Path dir)
    {
        List<Path> paths = RulePackageManifest.toPaths(dir, sample().forFamily("PMDA"));
        assertEquals(2, paths.size());
        assertTrue(paths.stream().allMatch(p -> p.startsWith(dir)));
    }


    @Test
    void enc_lowercasesAndDashesDotsAndSpaces()
    {
        assertEquals("3-4", RulePackageManifest.enc("3.4"));
        assertEquals("3-1-1", RulePackageManifest.enc("3.1.1"));
        assertEquals("sendig-dart", RulePackageManifest.enc("SENDIG-DART"));
        assertEquals("sdtm-and-sdtmig", RulePackageManifest.enc("SDTM AND SDTMIG"));
    }
}
