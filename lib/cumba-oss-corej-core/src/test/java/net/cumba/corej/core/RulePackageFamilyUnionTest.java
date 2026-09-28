package net.cumba.corej.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.cumba.corej.core.model.RulePackage;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@link RulePackageLoader#loadFamilyUnion} and its compatibility shim
 * {@link RulePackageLoader#loadCombined}: the rules of every family package that
 * {@code packages.json} lists for one {@code (standard, version)} are merged into one package — and
 * only those. The corpus's scenario harness loads its rules through this path.
 *
 * <p>
 * The rules directory is written by the test: two families publish SDTMIG 3.4, a third package
 * publishes SDTMIG 3.2 and must stay out of the 3.4 union.
 * </p>
 */
class RulePackageFamilyUnionTest
{

    private static String pkg(String... ids)
    {
        StringBuilder sb = new StringBuilder("{\"rules\":{");
        for (int i = 0; i < ids.length; i++)
        {
            sb.append(i == 0 ? "" : ",").append('"').append(ids[i])
                    .append("\":{\"Core\":{\"Id\":\"").append(ids[i])
                    .append("\"},\"Sensitivity\":\"Record\",")
                    .append("\"Check\":{\"expression\":\"not empty(USUBJID)\"},")
                    .append("\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[\"USUBJID\"]}}");
        }
        return sb.append("}}").toString();
    }


    private static Path rulesDir(Path dir) throws IOException
    {
        Files.writeString(dir.resolve("rules-cdisc-sdtmig-3-4.json"), pkg("CDISC-B", "CDISC-A"),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("rules-fda-sdtmig-3-4.json"), pkg("FDA-A"),
                StandardCharsets.UTF_8);
        Files.writeString(dir.resolve("rules-cdisc-sdtmig-3-2.json"), pkg("CDISC-OLD"),
                StandardCharsets.UTF_8);
        new RulePackageManifest(List.of(
                new RulePackageManifest.Entry("rules-cdisc-sdtmig-3-4.json", "CDISC", "SDTMIG",
                        "3.4", 2, List.of()),
                new RulePackageManifest.Entry("rules-fda-sdtmig-3-4.json", "FDA", "SDTMIG", "3.4",
                        1, List.of()),
                new RulePackageManifest.Entry("rules-cdisc-sdtmig-3-2.json", "CDISC", "SDTMIG",
                        "3.2", 1, List.of()))).writeTo(dir);
        return dir;
    }


    @Test
    void theUnionMergesEveryFamilyOfOneStandardVersionInIdOrder(@TempDir Path tmp)
        throws IOException
    {
        RulePackage union = RulePackageLoader.loadFamilyUnion(rulesDir(tmp), "SDTMIG", "3.4");
        assertEquals(List.of("CDISC-A", "CDISC-B", "FDA-A"), List.copyOf(union.getRules().keySet()),
                "both 3.4 families, Core.Id-sorted; the 3.2 package stays out");
    }


    @Test
    void theCombinedShimParsesTheStandardAndVersionFromTheFileName(@TempDir Path tmp)
        throws IOException
    {
        Path dir = rulesDir(tmp);
        // The combined file never existed and is never opened — only its name is read.
        RulePackage union = RulePackageLoader.loadCombined(dir.resolve("rules-sdtmig-3-4.json"));
        assertEquals(List.of("CDISC-A", "CDISC-B", "FDA-A"),
                List.copyOf(union.getRules().keySet()));
        RulePackage older = RulePackageLoader.loadCombined(dir.resolve("rules-sdtmig-3-2.json"));
        assertEquals(List.of("CDISC-OLD"), List.copyOf(older.getRules().keySet()));
    }


    @Test
    void theCombinedShimRejectsANameItCannotParse(@TempDir Path tmp)
    {
        IOException notRules = assertThrows(IOException.class,
                () -> RulePackageLoader.loadCombined(tmp.resolve("sdtmig-3-4.json")));
        assertTrue(notRules.getMessage().contains("not a combined rules file name"),
                notRules.getMessage());
        IOException noVersion = assertThrows(IOException.class,
                () -> RulePackageLoader.loadCombined(tmp.resolve("rules-sdtmig.json")));
        assertTrue(noVersion.getMessage().contains("cannot parse standard-version"),
                noVersion.getMessage());
        IOException noParent = assertThrows(IOException.class,
                () -> RulePackageLoader.loadCombined(Path.of("rules-sdtmig-3-4.json")));
        assertTrue(noParent.getMessage().contains("no parent directory"), noParent.getMessage());
    }
}
