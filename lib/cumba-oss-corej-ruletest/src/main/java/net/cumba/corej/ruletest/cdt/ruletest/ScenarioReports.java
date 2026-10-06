package net.cumba.corej.ruletest.cdt.ruletest;

import java.util.List;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.report.ReportAssembler;
import net.cumba.corej.core.report.ReportSections;
import net.cumba.corej.core.report.ValidationReportBuilder;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.report.ValidationReport;
import org.jspecify.annotations.Nullable;

/**
 * The single-run report adapter: one rule × one scenario as the {@link ReportSections} every report
 * writer takes ({@code JsonReportWriter}, the xlsx writer), assembled exactly as a study run's
 * report is — {@link ValidationReportBuilder} then {@link ReportAssembler} — so an editor shows a
 * scenario's findings in the shape the product's reports have ({@code PLAN-rule-authoring-api}
 * phase 3, item 5).
 */
public final class ScenarioReports
{

    private ScenarioReports()
    {
    }


    /**
     * The report sections of a run.
     *
     * @param aRule
     *            the rule that ran
     * @param aScenario
     *            the scenario
     * @param aResults
     *            the engine's results, one per concrete rule
     *            ({@link ScenarioRunner.ScenarioRunResult#results()})
     * @param aEngineVersion
     *            the engine version to print in the conformance block, or {@code null}
     * @return the sections
     */
    public static ReportSections sections(Rule aRule, RuleTestScenario aScenario,
            List<RuleExecutionResult> aResults, @Nullable String aEngineVersion)
    {
        String domain = aScenario.getDomain();
        ValidationReportBuilder builder = new ValidationReportBuilder();
        for (RuleExecutionResult result : aResults)
        {
            builder.add(domain, aScenario.getSource(), aRule, result);
        }
        ValidationReport report = builder.build();

        MapBackedLibraryMetadataProvider library = aScenario.getLibrary();
        LibraryRef ref = aScenario.getLibraryRef();
        String standard = ref != null ? ref.getStandard()
                : library != null ? library.getStandard() : null;
        String version = ref != null ? ref.getVersion()
                : library != null ? library.getVersion() : null;
        ReportAssembler.Conformance conformance = ReportAssembler.Conformance.builder()
                .standard(standard).version(version).coreEngineVersion(aEngineVersion).build();

        OverlayDataTable primary = aScenario.primaryTable();
        int columns = primary == null ? 0 : primary.getMetaData().getColumnCount();
        long rows = primary == null ? 0L : primary.getRowCount();
        ReportAssembler.DatasetInfo dataset = new ReportAssembler.DatasetInfo(aScenario.getSource(),
                primary == null ? null : primary.getMetaData().getLabel(), null, null, 0.0, rows,
                domain, columns);

        return new ReportAssembler().report(report).conformance(conformance)
                .datasets(List.of(dataset)).rules(List.of(aRule)).sections();
    }
}
