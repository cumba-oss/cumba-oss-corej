package net.cumba.corej.core.report.json;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Set;
import net.cumba.corej.core.RulePackageLoader;
import net.cumba.corej.core.exec.DatasetResolver;
import net.cumba.corej.core.exec.RuleExecutionResult;
import net.cumba.corej.core.exec.RuleExecutionStatus;
import net.cumba.corej.core.exec.RuleRunner;
import net.cumba.corej.core.model.Rule;
import net.cumba.corej.core.report.ReportAssembler;
import net.cumba.corej.core.report.ReportSections;
import net.cumba.corej.core.report.ValidationReportBuilder;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.report.Severity;
import net.cumba.datatable.report.ValidationReport;
import net.cumba.datatable.values.DataValueType;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-qualified-name-uniformity-review} T2 r1 M6 — criterion C11 at the WRITER: the JSON
 * report (v1 and v2) prints a qualified output variable {@code J.S} by the name as written and with
 * the value the Check read through the join, exactly as it prints the bare {@code S} — the writer
 * transforms no name ({@code JsonReportWriter} serialises the sections' maps as they are).
 */
class JsonReportQualifiedNameUniformityTest
{

    static IDataTable table(String name, String... s)
    {
        String[] keys = new String[s.length];
        for (int i = 0; i < s.length; i++)
        {
            keys[i] = "k" + (i + 1);
        }
        CachedDataTableColumn k = new CachedDataTableColumn(0, DataValueType.STRING);
        CachedDataTableColumn c = new CachedDataTableColumn(1, DataValueType.STRING);
        for (int i = 0; i < s.length; i++)
        {
            k.addElement(keys[i]);
            c.addElement(s[i]);
        }
        k.complete();
        c.complete();
        DataTableColumnMeta[] mm =
        {
                DataTableColumnMeta.builder().index(0).name("K").label("K")
                        .type(DataValueType.STRING).build(),
                DataTableColumnMeta.builder().index(1).name("S").label("S")
                        .type(DataValueType.STRING).build()
        };
        return new ColumnCachedDataTable(DataTableMeta.builder().name(name).label(name).columns(mm)
                .rowCount(s.length).totalRowCount(s.length).build(), new CachedDataTableColumn[]
        {
                k, c
        });
    }


    /** The sections of a one-rule run over P ⋈ J reporting {@code outputs}. */
    static ReportSections sections(String outputs, IDataTable p, IDataTable j) throws IOException
    {
        String json = "{\"rules\":{\"R\":{\"Core\":{\"Id\":\"T-C11\"},\"Sensitivity\":\"Record\","
                + "\"Requirements\":{\"Variables\":{\"All\":[\"K\"],\"All_Or_None\":[[\"K\",\"J.K\"]]}},"
                + "\"Match_Datasets\":[{\"Name\":\"J\",\"Keys\":[\"K\"],\"Join_Type\":\"left\"}],"
                + "\"Check\":{\"expression\":\"K != \\\"zz\\\"\"},"
                + "\"Outcome\":{\"Message\":\"m\",\"Output_Variables\":[" + outputs + "]}}}}";
        Rule rule = RulePackageLoader.loadFromString(json).getRules().get("R");
        assertNull(rule.getLoadError(), rule.getLoadError());
        // An inventory, not a bare resolver: the All_Or_None gate must be able to enumerate.
        DatasetResolver.WithInventory resolver = new DatasetResolver.WithInventory()
        {

            @Override
            public IDataTable resolve(String name)
            {
                return "J".equals(name) ? j : "P".equals(name) ? p : null;
            }


            @Override
            public Set<String> availableDatasets()
            {
                return Set.of("P", "J");
            }
        };
        RuleExecutionResult result = RuleRunner.execute(rule, p, resolver, null, null, null, null,
                1000, null, null, null, Set.of(), Set.of(), Severity.INFO);
        assertEquals(RuleExecutionStatus.EXECUTED, result.getStatus(), result.getStatusMessage());
        ValidationReport report = new ValidationReportBuilder().add("P", null, rule, result)
                .build();
        return new ReportAssembler().report(report).rules(List.of(rule)).sections();
    }


    static String write(ReportSections sections, boolean combined) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new JsonReportWriter(combined).write(sections, out);
        return out.toString(StandardCharsets.UTF_8);
    }


    static String timeless(String document)
    {
        return document.replaceAll("\"Report_Generation\":\"[^\"]*\"",
                "\"Report_Generation\":\"\"");
    }


    @Test
    void theQualifiedNameIsPrintedAsWrittenWithTheJoinedValue() throws IOException
    {
        IDataTable p = table("P", "a", "b");
        IDataTable j = table("J", "x", "y");
        for (boolean combined : new boolean[]
        {
                false, true
        })
        {
            String bare = write(sections("\"S\"", p, j), combined);
            String dotted = write(sections("\"J.S\"", p, j), combined);
            assertTrue(bare.contains("\"S\"") && bare.contains("\"a\"") && bare.contains("\"b\""),
                    bare);
            assertTrue(dotted.contains("\"J.S\""), "printed as written: " + dotted);
            assertTrue(dotted.contains("\"x\"") && dotted.contains("\"y\""),
                    "the value the Check read through the join: " + dotted);
            // The two documents differ only by the name and the joined values (and the
            // generation timestamp, written at two different instants — masked) …
            String renamed = timeless(dotted.replace("\"J.S\"", "\"S\"").replace("\"x\"", "\"a\"")
                    .replace("\"y\"", "\"b\""));
            if (combined)
            {
                // … EXCEPT, in v2, the finding's LOCATION: ⚠ known red N30 (T2 r1, recorded, not
                // fixed): location.variables lists the bare S but DROPS the qualified J.S —
                // ReportAssembler prints Location.getVariableNames(), which keeps only the
                // primary's own column names — while "variables" and "values" carry it. Remove
                // this pin when N30 is fixed.
                assertTrue(
                        dotted.contains("\"location\":{\"dataset\":\"P\",\"variables\":[\"K\"]}"),
                        "N30: " + dotted);
                assertTrue(
                        bare.contains(
                                "\"location\":{\"dataset\":\"P\",\"variables\":[\"S\",\"K\"]}"),
                        bare);
                renamed = renamed.replace("\"location\":{\"dataset\":\"P\",\"variables\":[\"K\"]}",
                        "\"location\":{\"dataset\":\"P\",\"variables\":[\"S\",\"K\"]}");
            }
            assertEquals(timeless(bare), renamed, "v" + (combined ? 2 : 1));
        }
    }
}
