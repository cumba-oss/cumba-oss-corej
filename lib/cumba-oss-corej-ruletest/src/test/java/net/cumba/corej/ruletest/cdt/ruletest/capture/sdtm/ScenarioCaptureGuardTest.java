package net.cumba.corej.ruletest.cdt.ruletest.capture.sdtm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.model.MatchDataset;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario.Verdict;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioCapture;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * F-corej-L3-02 / L3-03 / L3-04 — the capture-time guarantees of
 * {@link ScenarioCapture#captureWithSiblings}: it must snapshot without mutating the caller's
 * table, refuse a capture holding ANY unrepresentable dataset (not just an unrepresentable
 * primary), and filter a multi-key {@code Match_Datasets} sibling per key TUPLE, not per key
 * independently.
 *
 * <p>
 * Lives in a {@code …capture.sdtm} package and uses {@code CORE_…}-named methods because
 * {@code ScenarioCapture.findTestFrame}/{@code pickCategory} derive the scenario family from the
 * calling frame; {@code projectBasedir} is redirected to a temp dir so captures land there.
 * </p>
 */
class ScenarioCaptureGuardTest
{

    @TempDir
    Path tempDir;

    private String oldFlag;

    private String oldBasedir;

    @BeforeEach
    void enableCaptureIntoTempDir()
    {
        oldFlag = System.getProperty(ScenarioCapture.FLAG);
        oldBasedir = System.getProperty("projectBasedir");
        System.setProperty(ScenarioCapture.FLAG, "true");
        System.setProperty("projectBasedir", tempDir.toString());
    }


    @AfterEach
    void restoreProperties()
    {
        restore(ScenarioCapture.FLAG, oldFlag);
        restore("projectBasedir", oldBasedir);
    }


    private static void restore(String aKey, String aOld)
    {
        if (aOld == null)
        {
            System.clearProperty(aKey);
        }
        else
        {
            System.setProperty(aKey, aOld);
        }
    }


    private List<Path> cdtFiles() throws IOException
    {
        try (Stream<Path> walk = Files.walk(tempDir))
        {
            return walk.filter(p -> p.getFileName().toString().endsWith(".cdt")).toList();
        }
    }


    private static OverlayDataTable primaryAe()
    {
        OverlayDataTable t = OverlayDataTable.empty("AE", "Adverse Events", 2);
        t.addColumn("USUBJID", DataValueType.STRING, "Unique Subject Identifier");
        t.addColumn("VISITNUM", DataValueType.STRING, "Visit Number");
        t.setValue(0, "USUBJID", "SUBJ-A");
        t.setValue(0, "VISITNUM", "1");
        t.setValue(1, "USUBJID", "SUBJ-B");
        t.setValue(1, "VISITNUM", "2");
        return t;
    }


    private static Rule ruleWithSibling(String aName, List<String> aKeys)
    {
        MatchDataset md = new MatchDataset();
        md.setName(aName);
        md.setKeys(aKeys);
        Rule rule = new Rule();
        rule.setMatchDatasets(List.of(md));
        return rule;
    }

    // ---- F-corej-L3-04 ---------------------------------------------------------------


    @Test
    void CORE_L304_valid() throws IOException
    {
        // The primary's name ("SUPPAE") differs from the scenario domain ("AE"), so capture
        // relabels the emitted dataset -- but it must SNAPSHOT (wrap), never rename the caller's
        // live table in place: under -Dgenerate.scenarios=true a rename changes the state of the
        // very object the suite is still testing.
        OverlayDataTable primary = OverlayDataTable.empty("SUPPAE", "Supplemental AE", 1);
        primary.addColumn("USUBJID", DataValueType.STRING, "Unique Subject Identifier");
        primary.addColumn("QVAL", DataValueType.STRING, "Qualifier Value");
        primary.setValue(0, "USUBJID", "SUBJ-A");
        primary.setValue(0, "QVAL", "x");

        ScenarioCapture.captureWithSiblings("CORE-L304", Verdict.NO_VIOLATION, "AE", primary, null,
                null);

        assertEquals("SUPPAE", primary.getMetaData().getName(),
                "capture must not rename the caller's table in place");
        List<Path> files = cdtFiles();
        assertEquals(1, files.size(), "the scenario itself is still captured");
        assertTrue(files.get(0).getFileName().toString().endsWith("-AE.cdt"),
                "the EMITTED scenario carries the relabelled domain: " + files.get(0));
    }

    // ---- F-corej-L3-02 ---------------------------------------------------------------


    @Test
    void CORE_L302_valid() throws IOException
    {
        // The primary is representable, but the resolved sibling is a single-column table with
        // an all-null row -- exactly the shape the primary guard refuses because CdtWriter only
        // emits the `.` all-null sentinel when colCount > 1. The same writer property holds for
        // every dataset written, so the capture must be refused for the sibling too instead of
        // silently minting a fixture that replays with one row fewer.
        OverlayDataTable sibling = OverlayDataTable.empty("SUPPAE", "Supplemental AE", 2);
        sibling.addColumn("QVAL", DataValueType.STRING, "Qualifier Value");
        sibling.setValue(0, "QVAL", "x");
        // row 1 stays all-null
        DatasetResolver resolver = n -> "SUPPAE".equalsIgnoreCase(n) ? sibling : null;

        ScenarioCapture.captureWithSiblings("CORE-L302", Verdict.NO_VIOLATION, "AE", primaryAe(),
                ruleWithSibling("SUPPAE", null), resolver);

        assertEquals(List.of(), cdtFiles(),
                "an unrepresentable SIBLING must refuse the capture exactly as an"
                        + " unrepresentable primary does");
    }

    // ---- F-corej-L3-03 ---------------------------------------------------------------


    @Test
    void CORE_L303_valid() throws IOException
    {
        // Multi-key Match_Datasets: primary tuples are (SUBJ-A,1) and (SUBJ-B,2). The live
        // sibling row (SUBJ-A,2) passes BOTH per-key membership tests -- the Cartesian
        // relaxation -- but matches no primary tuple, so the tuple join must drop it.
        OverlayDataTable sibling = OverlayDataTable.empty("SUPPAE", "Supplemental AE", 2);
        sibling.addColumn("USUBJID", DataValueType.STRING, "Unique Subject Identifier");
        sibling.addColumn("VISITNUM", DataValueType.STRING, "Visit Number");
        sibling.addColumn("QVAL", DataValueType.STRING, "Qualifier Value");
        sibling.setValue(0, "USUBJID", "SUBJ-A");
        sibling.setValue(0, "VISITNUM", "1");
        sibling.setValue(0, "QVAL", "KEEPME");
        sibling.setValue(1, "USUBJID", "SUBJ-A");
        sibling.setValue(1, "VISITNUM", "2");
        sibling.setValue(1, "QVAL", "LEAKME");
        DatasetResolver resolver = n -> "SUPPAE".equalsIgnoreCase(n) ? sibling : null;

        ScenarioCapture.captureWithSiblings("CORE-L303", Verdict.NO_VIOLATION, "AE", primaryAe(),
                ruleWithSibling("SUPPAE", List.of("USUBJID", "VISITNUM")), resolver);

        List<Path> files = cdtFiles();
        assertEquals(1, files.size(), "capture written");
        String content = Files.readString(files.get(0));
        assertTrue(content.contains("KEEPME"),
                "the row matching a primary key TUPLE is captured:\n" + content);
        assertFalse(content.contains("LEAKME"),
                "a row matching every key independently but NO primary tuple must not be"
                        + " captured:\n" + content);
    }
}
