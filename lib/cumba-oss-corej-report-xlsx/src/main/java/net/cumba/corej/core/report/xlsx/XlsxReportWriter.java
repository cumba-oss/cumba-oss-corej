package net.cumba.corej.core.report.xlsx;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import net.cumba.corej.core.report.ReportSections;
import net.cumba.corej.core.report.ReportWriter;
import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Row.MissingCellPolicy;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.jspecify.annotations.Nullable;

/**
 * Writes a {@link ReportSections} as an XLSX workbook structurally and content-identical to the
 * Python CORE engine's Excel report
 * ({@code cdisc_rules_engine.services.reporting.excel_report.ExcelReport}).
 *
 * <h2>Template-driven</h2>
 *
 * <p>
 * The static layout — the five sheet names, the {@code Conformance Details} labels (column A), the
 * header row of the four list sheets, column widths, fonts, fills and autofilters — lives in the
 * shipped template {@code /templates/report-template.xlsx} (copied verbatim from the Python
 * resources). This writer only <em>fills cells</em>, exactly as the Python writer does: conformance
 * values into column B at fixed rows, and the data sheets from row 2 down.
 * </p>
 *
 * <h2>Parity notes</h2>
 * <ul>
 * <li>Sheet order/names: {@code Conformance Details}, {@code Dataset Details},
 * {@code Issue Summary}, {@code Issue Details}, {@code Rules Report}, plus the Java-only
 * {@code Skipped Rules} sheet ({@code Core ID}, {@code Dataset}, {@code Reason}) appended
 * programmatically — the template (copied verbatim from the Python resources) has no such
 * sheet.</li>
 * <li>List sheets are truncated to {@code maxRowsPerSheet} (default 10000; {@code null} =
 * unlimited), matching {@code MAX_REPORT_ROWS} / {@code --max-report-rows} resolution. JSON output
 * is never truncated.</li>
 * <li>{@code Issue Limit Per Sheet} (Conformance row 7) shows the effective limit — the literal
 * {@code "None"} when unlimited. This deliberately differs from the JSON report, which always emits
 * {@code null} for this field (Python parity).</li>
 * <li>List-valued cells ({@code variables}, {@code values}) are joined with {@code ", "}; numeric
 * columns ({@code size_kb}, {@code length}, {@code issues}, {@code row}) are written as
 * numbers.</li>
 * </ul>
 *
 * <h2>Obtaining one</h2>
 *
 * <p>
 * Through {@link net.cumba.corej.core.report.ReportManager} and the {@code xlsx} format, never by
 * direct construction from a consumer: this class lives in a pluggable module and a consumer that
 * imports it has re-created the engine-to-POI dependency the split removed. The constructor is
 * public only for {@link XlsxReportWriterSupplier} and this module's own tests.
 * </p>
 */
public final class XlsxReportWriter implements ReportWriter
{

    /** Classpath location of the template shipped with this module. */
    static final String TEMPLATE_RESOURCE = "/templates/report-template.xlsx";

    /** Default per-sheet row cap when neither the env var nor the writer property set one. */
    public static final int DEFAULT_MAX_ROWS = 10_000;

    /** Environment variable mirrored from the Python engine. */
    static final String MAX_REPORT_ROWS_ENV = "MAX_REPORT_ROWS";

    private static final System.Logger LOGGER = System.getLogger(XlsxReportWriter.class.getName());

    private static final String SHEET_CONFORMANCE = "Conformance Details";

    private static final String SHEET_DATASETS = "Dataset Details";

    private static final String SHEET_ISSUE_SUMMARY = "Issue Summary";

    private static final String SHEET_ISSUE_DETAILS = "Issue Details";

    private static final String SHEET_RULES = "Rules Report";

    private static final String SHEET_SKIPPED = "Skipped Rules";

    private static final String KEY_ISSUE_LIMIT_PER_SHEET = "Issue_Limit_Per_Sheet";

    /**
     * Conformance key → 1-based template row, mirroring
     * {@code SDTMReportData.get_conformance_details_data}. Column A carries the labels (from the
     * template); values go in column B. Optional keys absent from the section map keep the
     * template's default cell (e.g. {@code NAP}, {@code not configured}).
     */
    private static final Map<String, Integer> CONFORMANCE_ROWS = conformanceRows();

    /** Column orders for the four list sheets (the value-key sequence == the cell column order). */
    private static final List<String> DATASET_COLUMNS = List.of("filename", "label", "path",
            "modification_date", "size_kb", "length");

    private static final List<String> SUMMARY_COLUMNS = List.of("dataset", "core_id", "message",
            "issues");

    private static final List<String> DETAIL_COLUMNS = List.of("core_id", "message",
            "executability", "dataset", "USUBJID", "row", "SEQ", "variables", "values");

    private static final List<String> RULES_COLUMNS = List.of("core_id", "version", "cdisc_rule_id",
            "fda_rule_id", "message", "status");

    private static final List<String> SKIPPED_COLUMNS = List.of("core_id", "dataset", "reason");

    /** Header labels for the programmatically created {@code Skipped Rules} sheet. */
    private static final List<String> SKIPPED_HEADERS = List.of("Core ID", "Dataset", "Reason");

    /** Columns written as numeric cells when their value is a {@link Number}. */
    private static final Set<String> NUMERIC_COLUMNS = Set.of("size_kb", "length", "issues", "row");

    private final @Nullable Integer maxRowsPerSheet;

    /**
     * @param aMaxRowsPerSheet
     *            per-sheet row cap; {@code null} means unlimited
     */
    public XlsxReportWriter(@Nullable Integer aMaxRowsPerSheet)
    {
        maxRowsPerSheet = aMaxRowsPerSheet;
    }


    /**
     * Resolves the effective per-sheet row limit from the {@code MAX_REPORT_ROWS} environment
     * variable and the optional {@code maxRowsPerSheet} writer property, mirroring the Python
     * {@code ExcelReport.__init__} rules: take the max when both are set; {@code 0} means unlimited
     * ({@code null}); a negative value falls back to {@link #DEFAULT_MAX_ROWS}; absent both yields
     * the default.
     *
     * @param aArgMaxRows
     *            the caller-supplied limit, or {@code null} if not given
     * @return the effective limit, or {@code null} for unlimited
     */
    public static @Nullable Integer resolveMaxRows(@Nullable Integer aArgMaxRows)
    {
        Integer env = null;
        String raw = System.getenv(MAX_REPORT_ROWS_ENV);
        if (raw != null && !raw.isBlank())
        {
            try
            {
                env = Integer.valueOf(raw.trim());
            }
            catch (NumberFormatException _)
            {
                env = null;
            }
        }
        int result;
        if (env != null && aArgMaxRows != null)
        {
            result = Math.max(env, aArgMaxRows);
        }
        else if (env != null)
        {
            result = env;
        }
        else if (aArgMaxRows != null)
        {
            result = aArgMaxRows;
        }
        else
        {
            result = DEFAULT_MAX_ROWS;
        }
        if (result == 0)
        {
            return null;
        }
        if (result < 0)
        {
            return DEFAULT_MAX_ROWS;
        }
        return result;
    }


    /** Writes the workbook to the given stream. The stream is not closed. */
    @Override
    public void write(ReportSections aSections, OutputStream aOut) throws IOException
    {
        try (InputStream template = openTemplate(); XSSFWorkbook wb = new XSSFWorkbook(template))
        {
            // Cache of wrap-enabled styles derived from each distinct source (template) style, so
            // written data cells keep the template's per-cell formatting plus wrap-text (mirroring
            // openpyxl's in-place edit) without exceeding POI's cell-style limit.
            Map<Short, CellStyle> wrapStyles = new HashMap<>();

            fillConformance(wb.getSheet(SHEET_CONFORMANCE), aSections);
            fillList(wb, wb.getSheet(SHEET_DATASETS), aSections.datasetDetails(), DATASET_COLUMNS,
                    wrapStyles);
            fillList(wb, wb.getSheet(SHEET_ISSUE_SUMMARY), aSections.issueSummary(),
                    SUMMARY_COLUMNS, wrapStyles);
            fillList(wb, wb.getSheet(SHEET_ISSUE_DETAILS), aSections.issueDetails(), DETAIL_COLUMNS,
                    wrapStyles);
            fillList(wb, wb.getSheet(SHEET_RULES), aSections.rulesReport(), RULES_COLUMNS,
                    wrapStyles);
            fillList(wb, skippedRulesSheet(wb), aSections.skippedRules(), SKIPPED_COLUMNS,
                    wrapStyles);

            wb.write(aOut);
        }
    }


    /**
     * Returns the {@code Skipped Rules} sheet, creating it (with its header row and column widths)
     * when the template does not carry one. The shipped template is copied verbatim from the Python
     * engine's resources, which have no skipped-rules sheet — rather than editing the binary
     * resource, this writer appends the sheet programmatically; a future template that ships the
     * sheet is used as-is.
     */
    private static Sheet skippedRulesSheet(XSSFWorkbook aWorkbook)
    {
        Sheet sheet = aWorkbook.getSheet(SHEET_SKIPPED);
        if (sheet != null)
        {
            return sheet;
        }
        sheet = aWorkbook.createSheet(SHEET_SKIPPED);
        Row header = sheet.createRow(0);
        for (int c = 0; c < SKIPPED_HEADERS.size(); c++)
        {
            header.createCell(c).setCellValue(SKIPPED_HEADERS.get(c));
        }
        // Width units are 1/256th of a character: Core ID 24, Dataset 16, Reason 80 chars.
        sheet.setColumnWidth(0, 24 * 256);
        sheet.setColumnWidth(1, 16 * 256);
        sheet.setColumnWidth(2, 80 * 256);
        return sheet;
    }


    private static InputStream openTemplate()
    {
        InputStream in = XlsxReportWriter.class.getResourceAsStream(TEMPLATE_RESOURCE);
        if (in == null)
        {
            throw new UncheckedIOException(new IOException(
                    "Missing XLSX report template on classpath: " + TEMPLATE_RESOURCE));
        }
        return in;
    }


    private void fillConformance(@Nullable Sheet aSheet, ReportSections aSections)
    {
        if (aSheet == null)
        {
            return;
        }
        Map<String, Object> conformance = new LinkedHashMap<>(aSections.conformanceDetails());
        // The XLSX shows the effective row limit here, even though the JSON emits null (Python
        // parity). "None" represents the unlimited case.
        conformance.put(KEY_ISSUE_LIMIT_PER_SHEET,
                maxRowsPerSheet == null ? "None" : String.valueOf(maxRowsPerSheet));
        for (Map.Entry<String, Object> e : conformance.entrySet())
        {
            Integer row1 = CONFORMANCE_ROWS.get(e.getKey());
            Object value = e.getValue();
            if (row1 == null || value == null)
            {
                // Unknown key or absent optional value: leave the template's default cell intact.
                continue;
            }
            Row row = aSheet.getRow(row1 - 1);
            if (row == null)
            {
                row = aSheet.createRow(row1 - 1);
            }
            row.getCell(1, MissingCellPolicy.CREATE_NULL_AS_BLANK)
                    .setCellValue(String.valueOf(value));
        }
    }


    private void fillList(XSSFWorkbook aWorkbook, @Nullable Sheet aSheet,
            List<Map<String, Object>> aRows, List<String> aColumns,
            Map<Short, CellStyle> aWrapStyles)
    {
        if (aSheet == null)
        {
            return;
        }
        List<Map<String, Object>> data = truncate(aSheet.getSheetName(), aRows);
        for (int i = 0; i < data.size(); i++)
        {
            Map<String, Object> rowData = data.get(i);
            // Reuse the template's pre-formatted placeholder row/cell when present (row 0 is the
            // header), so the template's styling survives on the rows we write — only create when
            // the data outgrows the placeholders.
            int rowIdx = i + 1;
            Row row = aSheet.getRow(rowIdx);
            if (row == null)
            {
                row = aSheet.createRow(rowIdx);
            }
            for (int c = 0; c < aColumns.size(); c++)
            {
                String key = aColumns.get(c);
                Object value = rowData.get(key);
                Cell cell = row.getCell(c, MissingCellPolicy.CREATE_NULL_AS_BLANK);
                cell.setCellStyle(wrapStyleFor(aWorkbook, cell, aWrapStyles));
                writeCell(cell, key, value);
            }
        }
    }


    /**
     * Returns a wrap-text-enabled style derived from the cell's current (template) style, caching
     * by the source style's index so the workbook accumulates only one wrap variant per distinct
     * template style (POI caps total cell styles at 64k).
     */
    private static CellStyle wrapStyleFor(XSSFWorkbook aWorkbook, Cell aCell,
            Map<Short, CellStyle> aCache)
    {
        CellStyle src = aCell.getCellStyle();
        short key = src != null ? src.getIndex() : -1;
        CellStyle cached = aCache.get(key);
        if (cached != null)
        {
            return cached;
        }
        CellStyle wrap = aWorkbook.createCellStyle();
        if (src != null)
        {
            wrap.cloneStyleFrom(src);
        }
        wrap.setWrapText(true);
        aCache.put(key, wrap);
        return wrap;
    }


    private static void writeCell(Cell aCell, String aKey, @Nullable Object aValue)
    {
        if (aValue == null)
        {
            return; // leave blank, matching openpyxl's None handling
        }
        if (aValue instanceof List<?> list)
        {
            aCell.setCellValue(joinList(list));
            return;
        }
        if (NUMERIC_COLUMNS.contains(aKey) && aValue instanceof Number number)
        {
            aCell.setCellValue(number.doubleValue());
            return;
        }
        aCell.setCellValue(String.valueOf(aValue));
    }


    private static String joinList(List<?> aList)
    {
        return aList.stream().map(v -> v == null ? "null" : String.valueOf(v))
                .collect(Collectors.joining(", "));
    }


    private List<Map<String, Object>> truncate(String aSheetName, List<Map<String, Object>> aRows)
    {
        // A null or non-positive cap means "no truncation" (callers resolve 0 → null and negatives
        // → the default via resolveMaxRows, but guard here so a directly-constructed writer with a
        // stray negative cap can never throw on subList).
        if (maxRowsPerSheet == null || maxRowsPerSheet < 0 || aRows.size() <= maxRowsPerSheet)
        {
            return aRows;
        }
        LOGGER.log(System.Logger.Level.WARNING,
                "{0} truncated to limit of {1} rows. Total issues found: {2}", aSheetName,
                maxRowsPerSheet, aRows.size());
        return aRows.subList(0, maxRowsPerSheet);
    }


    private static Map<String, Integer> conformanceRows()
    {
        Map<String, Integer> m = new LinkedHashMap<>();
        m.put("Report_Generation", 2);
        m.put("Total_Runtime", 3);
        m.put("CORE_Engine_Version", 4);
        m.put("Issue_Limit_Per_Rule", 5);
        m.put("Issue_Limit_Per_Dataset", 6);
        m.put(KEY_ISSUE_LIMIT_PER_SHEET, 7);
        m.put("Standard", 9);
        m.put("Sub_Standard", 10);
        m.put("Version", 11);
        m.put("TIG_Use_Case", 12);
        m.put("CT_Version", 13);
        m.put("Define_XML_Version", 14);
        m.put("UNII_Version", 15);
        m.put("Med_RT_Version", 16);
        m.put("MedDRA_Version", 17);
        m.put("WHODRUG_Version", 18);
        m.put("SNOMED_Version", 19);
        m.put("LOINC_Version", 20);
        // D13 item 1 — the run-level dictionary degradation line. Its Fix #369 precedent
        // (Library_Metadata_Basis) reached the JSON and nothing else; both now have a template
        // row here so the XLSX — the artefact anyone actually reads — carries them too. On a
        // healthy run both are absent and the template's blank B cells survive untouched.
        m.put("Dictionary_Basis", 21);
        m.put("Neoplasm_Version", 22);
        m.put("Library_Metadata_Basis", 23);
        return Map.copyOf(m);
    }
}
