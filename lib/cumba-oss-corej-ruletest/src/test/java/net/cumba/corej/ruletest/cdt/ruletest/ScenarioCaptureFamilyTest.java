package net.cumba.corej.ruletest.cdt.ruletest;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario.Verdict;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * PLAN-dead-code-followups F-1 — where capture mode ({@code -Dgenerate.scenarios=true}) writes a
 * scenario. The target is the captured rule's FAMILY directory ({@code cdisc/}, {@code fda/},
 * {@code pmda/}, {@code draft/}), derived from its {@code Core.Id}: that is the subtree the
 * family's {@code RuleTestSuites<Family>FactoryTest} replays. Before the fix the family came from
 * the capturing suite's package ({@code .sdtm -> core/}, a directory deleted with the CORE family),
 * and a capture from any current SDTM suite method ({@code CDISC_CG…}, {@code FDA_SD…}) found no
 * test frame at all.
 *
 * <p>
 * Every capture here goes into a temp directory ({@code projectBasedir} is redirected), holding the
 * corpus's four family directories and nothing else. The test methods are named after the rule they
 * capture, exactly as the rule-test suites are, because the capture takes its file name's verdict
 * suffix from that method.
 * </p>
 */
class ScenarioCaptureFamilyTest
{

    private static final String SUITES = "src/test/resources/net/cumba/corej/core/ruletestsuites";

    @TempDir
    Path tempDir;

    private Path root;

    private String oldFlag;

    private String oldBasedir;

    @BeforeEach
    void enableCaptureIntoTempDir() throws IOException
    {
        oldFlag = System.getProperty(ScenarioCapture.FLAG);
        oldBasedir = System.getProperty("projectBasedir");
        System.setProperty(ScenarioCapture.FLAG, "true");
        System.setProperty("projectBasedir", tempDir.toString());
        root = tempDir.resolve(SUITES);
        for (String family : List.of("cdisc", "draft", "fda", "pmda"))
        {
            Files.createDirectories(root.resolve(family));
        }
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


    /** Every captured file, relative to the suites root, with {@code /} separators. */
    private List<String> captured() throws IOException
    {
        try (Stream<Path> walk = Files.walk(tempDir))
        {
            return walk.filter(p -> p.getFileName().toString().endsWith(".cdt"))
                    .map(p -> root.relativize(p).toString().replace('\\', '/')).sorted().toList();
        }
    }


    private static OverlayDataTable table(String aName)
    {
        OverlayDataTable t = OverlayDataTable.empty(aName, aName, 1);
        t.addColumn("STUDYID", DataValueType.STRING, "Study Identifier");
        t.addColumn("USUBJID", DataValueType.STRING, "Unique Subject Identifier");
        t.setValue(0, "STUDYID", "STUDY01");
        t.setValue(0, "USUBJID", "SUBJ-A");
        return t;
    }


    private static void capture(String aCoreId, Verdict aVerdict, String aDomain)
    {
        ScenarioCapture.captureWithSiblings(aCoreId, aVerdict, aDomain, table(aDomain), null, null);
    }


    @Test
    void FDA_SD0007_invalid() throws IOException
    {
        // An SDTM-suite rule of the FDA family: the scenario goes where the FDA factory replays.
        capture("FDA-SD0007", Verdict.VIOLATION, "AE");

        assertEquals(List.of("fda/FDA-SD0007/FDA-SD0007-invalid-AE.cdt"), captured());
    }


    @Test
    void CDISC_CG0554_C_invalid() throws IOException
    {
        // A multi-dash id: the whole id names the method prefix, only its letters name the family.
        capture("CDISC-CG0554-C", Verdict.VIOLATION, "AE");

        assertEquals(List.of("cdisc/CDISC-CG0554-C/CDISC-CG0554-C-invalid-AE.cdt"), captured());
    }


    @Test
    void PMDA_AD0001_valid2() throws IOException
    {
        // An ADaM rule of the PMDA family: the old package mapping sent every ADaM suite to cdisc/.
        capture("PMDA-AD0001", Verdict.NO_VIOLATION, "ADSL");

        assertEquals(List.of("pmda/PMDA-AD0001/PMDA-AD0001-valid-2-ADSL.cdt"), captured());
    }


    @Test
    void CORE_000001_valid() throws IOException
    {
        // The retired CORE family has no directory any more: refuse, never write where nothing
        // replays the scenario.
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> capture("CORE-000001", Verdict.NO_VIOLATION, "AE"));

        assertTrue(e.getMessage().contains(root.resolve("core").toString()),
                "the refusal names the missing family directory: " + e.getMessage());
        assertEquals(List.of(), captured(), "nothing may be written");
    }


    @Test
    void aCaptureFromAMethodNotNamedAfterTheRuleIsRefused() throws IOException
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> capture("FDA-SD0007", Verdict.VIOLATION, "AE"));

        assertTrue(e.getMessage().contains("FDA_SD0007"),
                "the refusal names the expected method prefix: " + e.getMessage());
        assertEquals(List.of(), captured(), "nothing may be written");
    }


    @Test
    void DRAFT_X0001_valid() throws IOException
    {
        // Two captures mapping onto one file name: the first stands, the second is refused.
        capture("DRAFT-X0001", Verdict.NO_VIOLATION, "DM");
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> capture("DRAFT-X0001", Verdict.NO_VIOLATION, "DM"));

        assertTrue(e.getMessage().contains("collision"), e.getMessage());
        assertEquals(List.of("draft/DRAFT-X0001/DRAFT-X0001-valid-DM.cdt"), captured());
    }


    @Test
    void FDA_SD0008_valid_captureModeOffWritesNothingAndNeverThrows() throws IOException
    {
        System.clearProperty(ScenarioCapture.FLAG);

        capture("FDA-SD0008", Verdict.NO_VIOLATION, "AE");
        capture("CORE-000002", Verdict.NO_VIOLATION, "AE");

        assertEquals(List.of(), captured());
    }


    @Test
    void familyDirectoryIsTheLowerCasedLeadingLetterRun()
    {
        assertEquals(root.resolve("cdisc"),
                ScenarioCapture.familyDirectory(root, "CDISC-SEND-0001"));
        assertEquals(root.resolve("fda"), ScenarioCapture.familyDirectory(root, "FDA-SE0001"));
        assertEquals(root.resolve("draft"), ScenarioCapture.familyDirectory(root, "DRAFT-0001"));
    }


    @Test
    void familyDirectoryRefusesAnIdWithoutAFamily()
    {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> ScenarioCapture.familyDirectory(root, "0001-CDISC"));

        assertTrue(e.getMessage().contains("0001-CDISC"), e.getMessage());
    }
}
