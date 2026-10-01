package net.cumba.corej.core.report.xlsx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
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
import org.apache.poi.ss.usermodel.DataFormatter;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.ss.usermodel.WorkbookFactory;
import org.junit.jupiter.api.Test;

/**
 * {@code PLAN-qualified-name-uniformity-review} T2 r1 M6 — criterion C11 at the XLSX writer: the
 * "Issue Details" sheet prints a qualified output variable {@code J.S} by the name as written and
 * with the value the Check read through the join, exactly as it prints the bare {@code S}
 * ({@code XlsxReportWriter.writeCell} prints {@code String.valueOf} / a joined list, nothing more).
 */
class XlsxReportQualifiedNameUniformityTest
{

    static IDataTable table(String name, String... s)
    {
        CachedDataTableColumn k = new CachedDataTableColumn(0, DataValueType.STRING);
        CachedDataTableColumn c = new CachedDataTableColumn(1, DataValueType.STRING);
        for (int i = 0; i < s.length; i++)
        {
            k.addElement("k" + (i + 1));
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


    /** The "variables" / "values" cells of every Issue Details row, as {@code name=value}. */
    static List<String> issueDetails(ReportSections sections) throws IOException
    {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        new XlsxReportWriter(null).write(sections, out);
        List<String> rows = new ArrayList<>();
        try (Workbook wb = WorkbookFactory.create(new ByteArrayInputStream(out.toByteArray())))
        {
            Sheet sheet = wb.getSheet("Issue Details");
            assertNotNull(sheet, "the Issue Details sheet");
            DataFormatter fmt = new DataFormatter();
            // The header row is the template's; the data columns are XlsxReportWriter's
            // DETAIL_COLUMNS in order: core_id, message, executability, dataset, USUBJID, row,
            // SEQ, variables, values — so variables is column 7 and values column 8. The data
            // rows below validate the choice (a wrong column would not read "S=a").
            int variables = 7;
            int values = 8;
            for (int r = 1; r <= sheet.getLastRowNum(); r++)
            {
                Row row = sheet.getRow(r);
                if (row == null)
                {
                    continue;
                }
                String names = fmt.formatCellValue(row.getCell(variables));
                if (names.isBlank())
                {
                    continue; // the template pre-creates blank rows
                }
                rows.add(names + "=" + fmt.formatCellValue(row.getCell(values)));
            }
        }
        return rows;
    }


    @Test
    void theQualifiedNameIsPrintedAsWrittenWithTheJoinedValue() throws IOException
    {
        IDataTable p = table("P", "a", "b");
        IDataTable j = table("J", "x", "y");
        List<String> bare = issueDetails(sections("\"S\"", p, j));
        List<String> dotted = issueDetails(sections("\"J.S\"", p, j));
        assertEquals(List.of("S, K=a, k1", "S, K=b, k2"), bare);
        assertEquals(List.of("J.S, K=x, k1", "J.S, K=y, k2"), dotted,
                "printed as written, with the value the Check read through the join");
        List<String> renamed = new ArrayList<>();
        for (String row : dotted)
        {
            renamed.add(row.replace("J.S", "S").replace("=x,", "=a,").replace("=y,", "=b,"));
        }
        assertEquals(bare, renamed, "the two sheets differ only by the name and the joined values");
    }
}
