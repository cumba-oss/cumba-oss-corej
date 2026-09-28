package net.cumba.corej.ruletest.cdt.ruletest.capture.sdtm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.corej.core.model.CompiledBinding;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.ruletest.cdt.ruletest.RuleTestScenario.Verdict;
import net.cumba.corej.ruletest.cdt.ruletest.ScenarioCapture;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.values.DataValueType;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * {@code PLAN-binding-expressions} R28: a <b>compiled</b> binding reads datasets exactly as a
 * declared operation does — a nested call's {@code domain=}, a {@code ds_exists} / {@code
 * ds_not_exists} presence probe, an inventory call — so a captured scenario must carry what the
 * binding reads, or it does not reproduce the run. Before wave 0 the capture walked
 * {@code getOperations()} and the Check only, so each of these siblings was silently dropped (the
 * negative control of every test below: with no compiled binding the sibling is absent).
 *
 * <p>
 * The method names follow the capture's own convention ({@code ScenarioCapture.findTestFrame} takes
 * the verdict from the method named after the rule), as in {@link ScenarioCaptureGuardTest}.
 * </p>
 */
class ScenarioCaptureCompiledBindingTest
{

    @TempDir
    Path tempDir;

    private @Nullable String oldFlag;

    private @Nullable String oldBasedir;

    @BeforeEach
    void enableCaptureIntoTempDir() throws IOException
    {
        oldFlag = System.getProperty(ScenarioCapture.FLAG);
        oldBasedir = System.getProperty("projectBasedir");
        System.setProperty(ScenarioCapture.FLAG, "true");
        System.setProperty("projectBasedir", tempDir.toString());
        Files.createDirectories(
                tempDir.resolve("src/test/resources/net/cumba/corej/core/ruletestsuites/cdisc"));
    }


    @AfterEach
    void restoreProperties()
    {
        restore(ScenarioCapture.FLAG, oldFlag);
        restore("projectBasedir", oldBasedir);
    }


    private static void restore(String aKey, @Nullable String aOld)
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


    private String capturedScenario() throws IOException
    {
        try (Stream<Path> walk = Files.walk(tempDir))
        {
            List<Path> files = walk.filter(p -> p.getFileName().toString().endsWith(".cdt"))
                    .toList();
            assertEquals(1, files.size(), "exactly one scenario is captured: " + files);
            return Files.readString(files.get(0));
        }
    }


    private static OverlayDataTable table(String aName, String aColumn, String aValue)
    {
        OverlayDataTable t = OverlayDataTable.empty(aName, aName, 1);
        t.addColumn("USUBJID", DataValueType.STRING, "Unique Subject Identifier");
        t.addColumn(aColumn, DataValueType.STRING, aColumn);
        t.setValue(0, "USUBJID", "SUBJ-A");
        t.setValue(0, aColumn, aValue);
        return t;
    }


    private static Rule ruleBinding(String aExpression)
    {
        Rule rule = new Rule();
        rule.setCompiledBindings(List.of(new CompiledBinding("$b",
                CheckExpressionParser.parse(aExpression), List.of(), null)));
        return rule;
    }


    /** A resolver over fixed tables, with the inventory an inventory call scans. */
    private static DatasetResolver.WithInventory resolver(OverlayDataTable... aTables)
    {
        Map<String, IDataTable> byName = new LinkedHashMap<>();
        for (OverlayDataTable t : aTables)
        {
            byName.put(t.getMetaData().getName(), t);
        }
        return new DatasetResolver.WithInventory()
        {

            @Override
            public @Nullable IDataTable resolve(String aName)
            {
                return byName.get(aName);
            }


            @Override
            public Set<String> availableDatasets()
            {
                return byName.keySet();
            }
        };
    }


    @Test
    void CDISC_L401_valid() throws IOException
    {
        // `domain=` of a call nested in a comparison, under a `not`, in an `and`: every arm of the
        // binding walk reaches it.
        Rule rule = ruleBinding("USUBJID == \"X\" and not (USUBJID in distinct(USUBJID,"
                + " domain=\"dm\") or USUBJID == \"Y\")");
        ScenarioCapture.captureWithSiblings("CDISC-L401", Verdict.NO_VIOLATION, "AE",
                table("AE", "AETERM", "HEADACHE"), rule,
                resolver(table("DM", "AGE", "40"), table("LB", "LBTEST", "ALB")));

        String scenario = capturedScenario();
        assertTrue(scenario.contains("dataset DM"), "the binding's domain= read: " + scenario);
        assertFalse(scenario.contains("dataset LB"), "an unread dataset stays out: " + scenario);
    }


    @Test
    void CDISC_L402_valid() throws IOException
    {
        // A presence probe inside the binding: the resolver has TS, so it is stubbed; SUPPAE is
        // absent, so it stays absent (the scenario asserts its absence by omitting it).
        Rule rule = ruleBinding("ds_exists(\"TS\") or ds_not_exists(SUPPAE)");
        ScenarioCapture.captureWithSiblings("CDISC-L402", Verdict.NO_VIOLATION, "AE",
                table("AE", "AETERM", "HEADACHE"), rule, resolver(table("TS", "TSVAL", "V")));

        String scenario = capturedScenario();
        assertTrue(scenario.contains("dataset TS"), "the binding's presence probe: " + scenario);
        assertFalse(scenario.contains("dataset SUPPAE"), scenario);
    }


    @Test
    void CDISC_L403_valid() throws IOException
    {
        // An inventory call nested in an argument: every resolver-known dataset is stubbed.
        Rule rule = ruleBinding("count(dataset_names()) > 0");
        ScenarioCapture.captureWithSiblings("CDISC-L403", Verdict.NO_VIOLATION, "AE",
                table("AE", "AETERM", "HEADACHE"), rule,
                resolver(table("DM", "AGE", "40"), table("LB", "LBTEST", "ALB")));

        String scenario = capturedScenario();
        assertTrue(scenario.contains("dataset DM") && scenario.contains("dataset LB"),
                "an inventory call inside a binding scans the study: " + scenario);
    }


    @Test
    void CDISC_L404_valid() throws IOException
    {
        // The negative control: the same resolver, no compiled binding — no sibling is read.
        ScenarioCapture.captureWithSiblings("CDISC-L404", Verdict.NO_VIOLATION, "AE",
                table("AE", "AETERM", "HEADACHE"), new Rule(),
                resolver(table("DM", "AGE", "40"), table("TS", "TSVAL", "V")));

        String scenario = capturedScenario();
        assertFalse(scenario.contains("dataset DM") || scenario.contains("dataset TS"), scenario);
    }

}
