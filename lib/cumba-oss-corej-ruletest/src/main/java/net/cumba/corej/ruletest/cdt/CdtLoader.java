package net.cumba.corej.ruletest.cdt;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import net.cumba.datatable.impl.support.OverlayDataTable;
import net.cumba.datatable.provider.cdt.CdtColumn;
import net.cumba.datatable.provider.cdt.CdtDataset;
import net.cumba.datatable.provider.cdt.CdtParser;
import net.cumba.datatable.provider.cdt.CdtValues;
import net.cumba.datatable.values.MissingValue;

/**
 * Loader for <code>.cdt</code> (Cumba Data Table) test-data files. This class is a thin adapter
 * over {@link CdtParser} in {@code net.cumba.datatable.provider.cdt} that produces a mutable
 * {@link OverlayDataTable} and applies the ADaM class-name shortcuts needed by the rule-test
 * fixtures.
 *
 * <h2>File format</h2>
 *
 * <pre>
 * # Comments start with hash. Blank lines allowed anywhere.
 *
 * dataset NAME [class=CLASS] [label="LABEL"] [key=value ...]
 * col NAME [type=Char|Num|Date|Time|DateTime] [label="..."] [length=N] [format=FMT] [codelist=NAME]
 * col NAME ...
 * ---
 * VALUE | VALUE | VALUE
 * VALUE | VALUE | VALUE
 * </pre>
 *
 * <p>
 * A file may contain multiple dataset blocks separated by matching fence lines; use
 * {@link #loadAll(Path)} / {@link #parseAll(String, String)} to retrieve them all.
 * {@link #load(Path)} and {@link #parse(String, String)} keep the single-dataset behaviour and
 * return the first dataset in the file.
 * </p>
 *
 * <h2>Class shortcuts</h2>
 *
 * <p>
 * On the <code>dataset</code> line the following shortcuts are expanded before being stored under
 * the {@code dataset_class} table-level metadata key:
 * </p>
 *
 * <ul>
 * <li><code>ADSL</code> → <code>SUBJECT LEVEL ANALYSIS DATASET</code></li>
 * <li><code>BDS</code> → <code>BASIC DATA STRUCTURE</code></li>
 * <li><code>OCCDS</code> → <code>OCCURRENCE DATA STRUCTURE</code></li>
 * <li><code>MD-BDS</code> → <code>MEDICAL DEVICE BASIC DATA STRUCTURE</code></li>
 * <li><code>MD-OCCDS</code> → <code>MEDICAL DEVICE OCCURRENCE DATA STRUCTURE</code></li>
 * </ul>
 *
 * <h2>ADaM structure / subclass shortcuts</h2>
 *
 * <p>
 * The <code>structure=</code> and <code>subclass=</code> dataset attributes declare the two ADaM
 * scope axes ({@code Scope.Data_Structures} / {@code Scope.Subclasses}) explicitly, stored under
 * the {@code dataset_structure} and {@code dataset_subclass} table-level metadata keys:
 * </p>
 *
 * <ul>
 * <li><code>structure=</code>: <code>ADSL</code>, <code>BDS</code>, <code>OCCDS</code>,
 * <code>OTHER</code> → the four data-structure tokens</li>
 * <li><code>subclass=</code>: <code>AE</code> → <code>ADVERSE EVENT</code>, <code>TTE</code> →
 * <code>TIME-TO-EVENT</code>, <code>MD-TTE</code> → <code>MEDICAL DEVICE TIME-TO-EVENT</code>,
 * <code>NCA</code> → <code>NON-COMPARTMENTAL ANALYSIS</code>, <code>POPPK</code> →
 * <code>POPULATION PHARMACOKINETIC ANALYSIS</code></li>
 * </ul>
 *
 * <p>
 * A scenario that declares neither gets both values <em>detected</em> from its columns
 * ({@code AdamDataStructureDetector} / {@code AdamSubclassDetector}), so a fixture cannot satisfy a
 * structure- or subclass-scoped rule by omission. Declaring one adds the <b>declared</b> tier — the
 * scenario's stand-in for a Define-XML <code>def:Class</code> / <code>def:SubClass</code>
 * declaration — and it obeys the engine's precedence, not the loader's: <b>since Fix #154 a
 * recognised declaration overrides the columns</b> ({@code corej.defineFirst} defaults to
 * {@code true}), and <code>-Dcorej.defineFirst=false</code> restores the old order, where the
 * column heuristic decides and the declaration only fills in when the heuristic yields
 * {@code ADAM OTHER} / no subclass.
 * </p>
 *
 * <h2>Source URI</h2>
 *
 * <p>
 * The <code>uri=</code> dataset attribute declares the dataset's <b>source location</b>, i.e. what
 * {@code getMetaData().getTableURI()} answers — the input of the
 * {@code extract_metadata("dataset_location")} / {@code ("filename")} accessors, which read the URI
 * and not the table-metadata map. Without it a <code>.cdt</code> table has no URI (an
 * {@link OverlayDataTable#empty(String, String, int)} table sets name / label / row count and
 * nothing else), so those accessors resolve to {@code null} and a file-name rule can never fire.
 * The value must be a syntactically valid {@link URI}; the attribute is <em>also</em> kept in the
 * table-metadata map so a scenario round-trips through {@code CdtWriter} unchanged.
 * </p>
 *
 * <p>
 * This loader stores the raw (shortcut-expanded) value and judges nothing: an unrecognised value is
 * stored verbatim, and the detectors then fold it to "not a data structure / subclass" and let the
 * heuristic decide alone — a bogus token is <em>ignored</em>, it does not become a declaration the
 * scope gate rejects. Consumers are {@code AbstractRuleTestSuitesFactory}'s scenario pre-gate and,
 * via {@code ScenarioDeclaredScopeProvider}, {@code RuleRunner}'s runtime gate; see
 * {@code README-CDT.md} in the suites module for the end-to-end contract.
 * </p>
 */
public final class CdtLoader
{

    private static final Map<String, String> CLASS_SHORTCUTS = Map.of("ADSL",
            "SUBJECT LEVEL ANALYSIS DATASET", "BDS", "BASIC DATA STRUCTURE", "OCCDS",
            "OCCURRENCE DATA STRUCTURE", "MD-BDS", "MEDICAL DEVICE BASIC DATA STRUCTURE",
            "MD-OCCDS", "MEDICAL DEVICE OCCURRENCE DATA STRUCTURE");

    /**
     * {@code Scope.Data_Structures} shortcuts for the {@code structure=} dataset attribute — the
     * four {@code AdamDataStructureDetector} tokens. An unrecognised value passes through verbatim,
     * so a scenario may declare a full token spelled out; a value that is not a structure token is
     * later folded away by the detector and the column heuristic decides alone. Keys are matched
     * exactly, like {@link #CLASS_SHORTCUTS}.
     */
    private static final Map<String, String> STRUCTURE_SHORTCUTS = Map.of("ADSL",
            "SUBJECT LEVEL ANALYSIS DATASET", "BDS", "BASIC DATA STRUCTURE", "OCCDS",
            "OCCURRENCE DATA STRUCTURE", "OTHER", "ADAM OTHER");

    /**
     * {@code Scope.Subclasses} shortcuts for the {@code subclass=} dataset attribute — the five
     * {@code AdamSubclassDetector} tokens. Same pass-through rule as {@link #STRUCTURE_SHORTCUTS}.
     */
    private static final Map<String, String> SUBCLASS_SHORTCUTS = Map.of("AE", "ADVERSE EVENT",
            "TTE", "TIME-TO-EVENT", "MD-TTE", "MEDICAL DEVICE TIME-TO-EVENT", "NCA",
            "NON-COMPARTMENTAL ANALYSIS", "POPPK", "POPULATION PHARMACOKINETIC ANALYSIS");

    private CdtLoader()
    {
    }


    public static OverlayDataTable load(Path aPath) throws IOException
    {
        String content = Files.readString(aPath, StandardCharsets.UTF_8);
        return parse(content, aPath.toString());
    }


    public static OverlayDataTable loadResource(String aResourcePath) throws IOException
    {
        return parse(readResource(aResourcePath), aResourcePath);
    }


    public static OverlayDataTable parse(String aContent, String aSourceName)
    {
        List<CdtDataset> all = parseDatasets(aContent, aSourceName);
        return buildTable(all.get(0));
    }


    /**
     * Load all datasets from the given file path. Files containing a single dataset yield a
     * one-element list; files with multiple dataset blocks yield them in the order they appear.
     */
    public static List<OverlayDataTable> loadAll(Path aPath) throws IOException
    {
        String content = Files.readString(aPath, StandardCharsets.UTF_8);
        return parseAll(content, aPath.toString());
    }


    /**
     * Load all datasets from the given classpath resource.
     */
    public static List<OverlayDataTable> loadAllResource(String aResourcePath) throws IOException
    {
        return parseAll(readResource(aResourcePath), aResourcePath);
    }


    /**
     * Parse all datasets from the given file content string.
     */
    public static List<OverlayDataTable> parseAll(String aContent, String aSourceName)
    {
        List<CdtDataset> all = parseDatasets(aContent, aSourceName);
        List<OverlayDataTable> out = new ArrayList<>(all.size());
        for (CdtDataset ds : all)
        {
            out.add(buildTable(ds));
        }
        return out;
    }

    // ---- internals -----------------------------------------------------------------


    private static List<CdtDataset> parseDatasets(String aContent, String aSourceName)
    {
        try
        {
            return CdtParser.parseAll(aContent, aSourceName);
        }
        catch (net.cumba.datatable.provider.cdt.CdtParseException ex)
        {
            // Throwable.getMessage() is @Nullable; CdtParseException requires a non-null
            // message, so fall back to the cause's string form when the message is absent.
            throw new CdtParseException(
                    Objects.requireNonNullElseGet(ex.getMessage(), ex::toString), ex);
        }
    }


    private static String readResource(String aResourcePath) throws IOException
    {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        if (cl == null)
        {
            cl = CdtLoader.class.getClassLoader();
        }
        try (InputStream in = cl.getResourceAsStream(aResourcePath))
        {
            if (in == null)
            {
                throw new IOException("CDT resource not found on classpath: " + aResourcePath);
            }
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }


    private static OverlayDataTable buildTable(CdtDataset aDs)
    {
        try
        {
            return buildTableUnchecked(aDs);
        }
        catch (net.cumba.datatable.provider.cdt.CdtParseException ex)
        {
            // Throwable.getMessage() is @Nullable; CdtParseException requires a non-null
            // message, so fall back to the cause's string form when the message is absent.
            throw new CdtParseException(
                    Objects.requireNonNullElseGet(ex.getMessage(), ex::toString), ex);
        }
    }


    /**
     * Parse the {@code uri=} dataset attribute. A malformed value is an authoring error, reported
     * with the dataset name rather than swallowed — a silently dropped URI would turn a
     * {@code dataset_location} scenario into one that passes while exercising nothing.
     */
    private static URI parseUri(String aValue, String aDatasetName)
    {
        try
        {
            return new URI(aValue);
        }
        catch (URISyntaxException ex)
        {
            throw new CdtParseException(
                    "dataset " + aDatasetName + ": invalid uri=" + aValue + " — " + ex.getReason(),
                    ex);
        }
    }


    private static OverlayDataTable buildTableUnchecked(CdtDataset aDs)
    {
        String label = aDs.getLabel() != null ? aDs.getLabel() : aDs.getName();
        OverlayDataTable table = OverlayDataTable.empty(aDs.getName(), label,
                aDs.getDataRows().size());

        List<CdtColumn> columns = aDs.getColumns();
        for (CdtColumn col : columns)
        {
            table.addColumn(col.getName(), CdtValues.toDataValueType(col.getType()),
                    col.getLabel() != null ? col.getLabel() : col.getName());
            if (col.getLength() != null)
            {
                table.setColumnLength(col.getName(), col.getLength());
            }
            if (col.getFormat() != null)
            {
                table.setColumnFormat(col.getName(), col.getFormat());
            }
            if (col.getCodelist() != null)
            {
                // Table-level metadata key scoped to the column — kept for
                // compatibility with existing rule-engine consumers.
                table.setTableMetaData(col.getName() + ".codelist", col.getCodelist());
            }
        }

        String className = aDs.getAttrs().get("class");
        if (className != null)
        {
            String expanded = CLASS_SHORTCUTS.getOrDefault(className, className);
            table.setTableMetaData("dataset_class", expanded);
        }
        String structure = aDs.getAttrs().get("structure");
        if (structure != null)
        {
            table.setTableMetaData("dataset_structure",
                    STRUCTURE_SHORTCUTS.getOrDefault(structure, structure));
        }
        String subclass = aDs.getAttrs().get("subclass");
        if (subclass != null)
        {
            table.setTableMetaData("dataset_subclass",
                    SUBCLASS_SHORTCUTS.getOrDefault(subclass, subclass));
        }
        // The source location. Deliberately NOT excluded from the generic attribute loop below:
        // CdtWriter emits table-metadata keys but not the URI, so keeping the raw string in the
        // map is what lets a scenario round-trip. Nothing reads the "uri" metadata key itself —
        // extract_metadata("dataset_location") reads getTableURI().
        String uri = aDs.getAttrs().get("uri");
        if (uri != null)
        {
            table.setTableURI(parseUri(uri, aDs.getName()));
        }
        for (Map.Entry<String, String> e : aDs.getAttrs().entrySet())
        {
            if ("class".equals(e.getKey()) || "structure".equals(e.getKey())
                    || "subclass".equals(e.getKey()))
            {
                continue;
            }
            table.setTableMetaData(e.getKey(), e.getValue());
        }

        List<List<String>> rows = aDs.getDataRows();
        for (int r = 0; r < rows.size(); r++)
        {
            List<String> row = rows.get(r);
            for (int c = 0; c < columns.size(); c++)
            {
                Object value = CdtValues.parseValue(row.get(c), columns.get(c).getType());
                // A missing cell is left unset: OverlayDataTable already answers an unset cell
                // with DataValueMissing, so writing an override would add nothing.
                // CdtValues.parseValue spells a missing cell per the column's storage type —
                // null for Char, MissingValue for the numeric types, because DataBufferDouble
                // rejects null — so both spellings have to be filtered here. Skipping the
                // MissingValue keeps this loader's raw getValue() answering null for a blank
                // numeric cell exactly as before; changing that would move IDataTable.hashCodeAt
                // for 313 blank numeric cells across 215 rule-test fixtures.
                if (value != null && !(value instanceof MissingValue))
                {
                    table.setValue(r, columns.get(c).getName(), value);
                }
            }
        }

        return table;
    }

    /**
     * Compatibility alias for {@link net.cumba.datatable.provider.cdt.CdtParseException}. Preserved
     * so existing test code referring to {@code CdtLoader.CdtParseException} keeps compiling. New
     * code should use {@link net.cumba.datatable.provider.cdt.CdtParseException} directly.
     */
    public static final class CdtParseException
            extends net.cumba.datatable.provider.cdt.CdtParseException
    {

        private static final long serialVersionUID = 1L;

        public CdtParseException(String aMessage)
        {
            super(aMessage);
        }


        public CdtParseException(String aMessage, Throwable aCause)
        {
            super(aMessage, aCause);
        }
    }
}
