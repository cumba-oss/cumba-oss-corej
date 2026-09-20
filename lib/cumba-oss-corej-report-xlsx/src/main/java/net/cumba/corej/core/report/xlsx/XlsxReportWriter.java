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
import org.apache.poi.ss.usermodel.CellType;
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

    /**
     * D65: the three trailing count columns ({@code executed} / {@code skipped} / {@code errored})
     * have no header in the shipped template (copied verbatim from the Python resources), so
     * {@link #rulesReportSheet} appends their headers programmatically — the same pattern as the
     * Java-only {@code Skipped Rules} sheet.
     */
    private static final List<String> RULES_COLUMNS = List.of("core_id", "version", "cdisc_rule_id",
            "fda_rule_id", "message", "status", "executed", "skipped", "errored");

    /** Header labels for the D65 count columns appended to the {@code Rules Report} sheet. */
    private static final List<String> RULES_COUNT_HEADERS = List.of("Executed", "Skipped",
            "Errored");

    private static final List<String> SKIPPED_COLUMNS = List.of("core_id", "dataset", "reason");

    /** Header labels for the programmatically created {@code Skipped Rules} sheet. */
    private static final List<String> SKIPPED_HEADERS = List.of("Core ID", "Dataset", "Reason");

    /** Columns written as numeric cells when their value is a {@link Number}. */
    private static final Set<String> NUMERIC_COLUMNS = Set.of("size_kb", "length", "issues", "row",
            "executed", "skipped", "errored");

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
        return resolveMaxRows(aArgMaxRows, System.getenv(MAX_REPORT_ROWS_ENV));
    }


    /**
     * The whole of {@link #resolveMaxRows(Integer)} with the environment read <b>passed in</b>
     * rather than looked up.
     *
     * <p>
     * ⚠ This seam exists for a reason worth stating: a Java test cannot set an environment variable
     * in its own process, so with {@code System.getenv} called inline the entire environment branch
     * — the blank check, the {@code NumberFormatException} fallback and the {@code Math.max}
     * precedence rule — was <b>unreachable by any test</b>. Measured 2026-09-14: three of this
     * module's mutants sat in exactly those lines and pitest reported them NO_COVERAGE, meaning the
     * documented precedence ("the larger of the two wins") was asserted nowhere. Splitting the
     * lookup from the decision is the smallest change that makes the decision testable; the public
     * overload keeps the production behaviour identical.
     * </p>
     *
     * @param aArgMaxRows
     *            the caller-supplied limit, or {@code null} if not given.
     * @param aRawEnv
     *            the raw {@code MAX_REPORT_ROWS} value, or {@code null} when unset.
     * @return the effective limit, or {@code null} for unlimited.
     */
    static @Nullable Integer resolveMaxRows(@Nullable Integer aArgMaxRows, @Nullable String aRawEnv)
    {
        Integer env = null;
        String raw = aRawEnv;
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
            fillList(wb, rulesReportSheet(wb), aSections.rulesReport(), RULES_COLUMNS, wrapStyles);
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
     *
     * <p>
     * ⚠ Package-private rather than private so a test can exercise the <em>reuse</em> branch. The
     * shipped template has no such sheet, so every production call takes the create path and the
     * early {@code return sheet} — the branch that implements "a future template that ships the
     * sheet is used as-is" — was unreachable and untested (measured NO_COVERAGE, 2026-09-14). The
     * cheapest honest way to reach it is to call this twice on one workbook, which is exactly what
     * a template carrying the sheet would look like from here.
     * </p>
     */
    static Sheet skippedRulesSheet(XSSFWorkbook aWorkbook)
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


    /**
     * Returns the {@code Rules Report} sheet with the three D65 count-column headers
     * ({@code Executed} / {@code Skipped} / {@code Errored}) appended to its header row when they
     * are not already present. The shipped template (copied verbatim from the Python resources)
     * carries six header cells; the appended headers reuse the last template header's style so the
     * row reads as one. A future template that ships the headers — or a second call on the same
     * workbook — is used as-is.
     */
    static @Nullable Sheet rulesReportSheet(XSSFWorkbook aWorkbook)
    {
        Sheet sheet = aWorkbook.getSheet(SHEET_RULES);
        if (sheet == null)
        {
            return null;
        }
        Row header = sheet.getRow(0);
        if (header == null)
        {
            header = sheet.createRow(0);
        }
        int firstCountCol = RULES_COLUMNS.size() - RULES_COUNT_HEADERS.size();
        // ⚠ Presence is a non-blank VALUE, not a non-null cell: the shipped template carries
        // styled-but-empty placeholder cells beyond the six real headers, and POI returns those as
        // non-null — a getCell(...) != null guard silently skipped the append (measured: the
        // regenerated golden workbook came back with no Executed/Skipped/Errored headers at all).
        Cell existing = header.getCell(firstCountCol);
        if (existing != null && existing.getCellType() == CellType.STRING
                && !existing.getStringCellValue().isBlank())
        {
            return sheet; // headers already present (a template that ships them, or a re-call)
        }
        // firstCountCol is structurally positive (RULES_COLUMNS is the six template columns plus
        // the count columns), so the last template header is always a valid style source; it may
        // still be a null CELL on a template without a header row.
        Cell styleSource = header.getCell(firstCountCol - 1);
        for (int i = 0; i < RULES_COUNT_HEADERS.size(); i++)
        {
            Cell cell = header.getCell(firstCountCol + i, MissingCellPolicy.CREATE_NULL_AS_BLANK);
            if (styleSource != null)
            {
                cell.setCellStyle(styleSource.getCellStyle());
            }
            cell.setCellValue(RULES_COUNT_HEADERS.get(i));
            sheet.setColumnWidth(firstCountCol + i, 12 * 256);
        }
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


    /**
     * ⚠ Package-private rather than private for the same reason as {@link #skippedRulesSheet}: the
     * shipped template carries Conformance rows 1–23 and {@link #CONFORMANCE_ROWS} maps every key
     * into that range, so {@code createRow} — the branch that keeps this method working against a
     * future template with a row missing — is unreachable through the public {@code write} path and
     * measured NO_COVERAGE (2026-09-14). A test drives it with a sheet built without the row.
     */
    void fillConformance(@Nullable Sheet aSheet, ReportSections aSections)
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
