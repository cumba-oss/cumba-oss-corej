package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.RulePackageManifest.Entry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class RulePackageManifestTest
{

    private static RulePackageManifest sample()
    {
        return new RulePackageManifest(List.of(
                new Entry("rules-cdisc-sdtmig-3-4.json", "CDISC", "SDTMIG", "3.4", 368, List.of()),
                new Entry("rules-fda-sdtmig-3-4.json", "FDA", "SDTMIG", "3.4", 454, List.of()),
                new Entry("rules-pmda-sdtmig-3-4.json", "PMDA", "SDTMIG", "3.4", 440, List.of()),
                new Entry("rules-cdisc-adamig-1-3.json", "CDISC", "ADaMIG", "1.3", 698, List.of()),
                new Entry("rules-pmda-adamig-1-3.json", "PMDA", "ADaMIG", "1.3", 291, List.of())));
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
        assertEquals(5, loaded.packages().size());
        assertEquals(List.of("rules-cdisc-sdtmig-3-4.json"),
                loaded.forStandardVersion("SDTMIG", "3.4").stream().map(Entry::file)
                        .filter("rules-cdisc-sdtmig-3-4.json"::equals).toList());
    }


    @Test
    void forStandardVersion_returnsTheUnionSet()
    {
        // SDTMIG 3.4 -> the three family files (cdisc, fda, pmda).
        assertEquals(3, sample().forStandardVersion("SDTMIG", "3.4").size());
        // ADaMIG 1.3 -> the two family files (cdisc, pmda) after the ADAMCR->CDISC merge.
        assertEquals(2, sample().forStandardVersion("ADaMIG", "1.3").size());
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
