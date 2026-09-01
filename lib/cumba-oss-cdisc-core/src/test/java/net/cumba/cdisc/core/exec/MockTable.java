package net.cumba.cdisc.core.exec;

import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

import java.util.LinkedHashMap;
import java.util.Map;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;

/**
 * Builds a mock IDataTable for testing. Column data is String-based by default.
 *
 * <p>
 * Numeric columns can be added via {@link #colLong(String, Long...)} and
 * {@link #colDouble(String, Double...)}; the resulting cells return {@link DataValueType#LONG} /
 * {@link DataValueType#DOUBLE} from {@code getType()} and the boxed numeric from
 * {@code getValue()}. Used by Fix #19's polymorphic date comparison tests so the LHS dispatch sees
 * numeric SAS dates as numeric.
 * </p>
 */
public final class MockTable
{

    private final Map<String, String[]> columns = new LinkedHashMap<>();

    private final Map<String, Long[]> longColumns = new LinkedHashMap<>();

    private final Map<String, Double[]> doubleColumns = new LinkedHashMap<>();

    private final Map<String, String[]> sasMissingColumns = new LinkedHashMap<>();

    private final Map<String, String> colLabels = new LinkedHashMap<>();

    private final Map<String, Integer> colLengths = new LinkedHashMap<>();

    private final Map<String, String> colFormats = new LinkedHashMap<>();

    private String tableName;

    private boolean caseInsensitiveColumns;

    private String tableLabel;

    private String tableUri;

    private final Map<String, Object> metaValues = new LinkedHashMap<>();

    public static MockTable of()
    {
        return new MockTable();
    }


    /**
     * Creates an IDataTable with the given column names, each with a single empty-string row.
     * Useful for tests that only need column metadata.
     */
    public static IDataTable withColumns(String... columnNames)
    {
        MockTable mt = new MockTable();
        for (String name : columnNames)
        {
            mt.col(name, "");
        }
        return mt.build();
    }


    public MockTable col(String name, String... values)
    {
        columns.put(name, values);
        return this;
    }


    /**
     * Adds a typed numeric column whose cells report {@link DataValueType#LONG} from
     * {@code getType()}. {@code null} entries become missing-or-invalid. Fix #19 polymorphic-date
     * tests use this to trigger the numeric branch on the LHS.
     */
    public MockTable colLong(String name, Long... values)
    {
        longColumns.put(name, values);
        return this;
    }


    /**
     * Adds a typed numeric column whose cells report {@link DataValueType#DOUBLE} from
     * {@code getType()}. {@code null} entries become missing-or-invalid.
     */
    public MockTable colDouble(String name, Double... values)
    {
        doubleColumns.put(name, values);
        return this;
    }


    /**
     * Adds a column whose {@code null} entries model a <b>real SAS missing marker</b>: they report
     * {@code isMissingOrInvalid() == true} <em>and</em> render as {@code "."} from
     * {@code getValueAsString()}, exactly as {@code DataValueMissing(MissingValue.MIS)} does.
     *
     * <p>
     * ⚠⚠ <b>Use this, not {@link #colLong}, whenever a test needs to observe whether code
     * <em>folded</em> a missing key.</b> {@code colLong}'s missing cell renders {@code ""}, which
     * is also what a fold produces — so a fold and a non-fold are indistinguishable through it and
     * any such assertion is <b>vacuous</b>. That is not a hypothetical: the grouped-key lockstep
     * test passed against deliberately broken code until it was moved onto this column type.
     * </p>
     */
    public MockTable colSasMissing(String name, String... values)
    {
        sasMissingColumns.put(name, values);
        return this;
    }


    /**
     * Sets optional column metadata — declared label, length, and display format — for an existing
     * column. Used by the metadata-accessor ({@code var_*}) tests. A {@code null} label/format or a
     * non-positive length leaves the corresponding mock getter at its default (null / 0).
     */
    public MockTable colMeta(String name, String label, int length, String format)
    {
        if (label != null)
        {
            colLabels.put(name, label);
        }
        if (length > 0)
        {
            colLengths.put(name, length);
        }
        if (format != null)
        {
            colFormats.put(name, format);
        }
        return this;
    }


    public MockTable name(String name)
    {
        this.tableName = name;
        return this;
    }


    /**
     * Makes {@code getColumnIndex} / {@code getOptionalColumn} resolve case-insensitively, which is
     * what real tables do by default ({@code DataTableMeta.columnNameCaseSensitive} is {@code
     * false}). Opt-in, so every existing test keeps the stricter exact-match stubbing.
     */
    public MockTable caseInsensitiveColumnNames()
    {
        this.caseInsensitiveColumns = true;
        return this;
    }


    public MockTable label(String label)
    {
        this.tableLabel = label;
        return this;
    }


    /** Sets the dataset's source-file URI, backing {@code getMetaData().getTableURI()}. */
    public MockTable uri(String uri)
    {
        this.tableUri = uri;
        return this;
    }


    /** Sets a generic dataset-metadata value, backing {@code getMetaData().getMetaData(key)}. */
    public MockTable metaValue(String key, Object value)
    {
        this.metaValues.put(key, value);
        return this;
    }


    public IDataTable build()
    {
        // Unified column registry: name -> per-row IDataValue + raw Object + column-meta type,
        // preserving insertion order across the three typed maps.
        LinkedHashMap<String, Object[]> rawByName = new LinkedHashMap<>();
        LinkedHashMap<String, IDataValue[]> dvByName = new LinkedHashMap<>();
        LinkedHashMap<String, DataValueType> colTypeByName = new LinkedHashMap<>();
        int rowCount = -1;

        for (Map.Entry<String, String[]> e : columns.entrySet())
        {
            String[] data = e.getValue();
            if (rowCount < 0)
            {
                rowCount = data.length;
            }
            Object[] raw = new Object[data.length];
            IDataValue[] dvs = new IDataValue[data.length];
            for (int r = 0; r < data.length; r++)
            {
                raw[r] = data[r];
                dvs[r] = mockDataValue(data[r]);
            }
            rawByName.put(e.getKey(), raw);
            dvByName.put(e.getKey(), dvs);
            colTypeByName.put(e.getKey(), DataValueType.STRING);
        }
        for (Map.Entry<String, String[]> e : sasMissingColumns.entrySet())
        {
            String[] data = e.getValue();
            if (rowCount < 0)
            {
                rowCount = data.length;
            }
            Object[] raw = new Object[data.length];
            IDataValue[] dvs = new IDataValue[data.length];
            for (int r = 0; r < data.length; r++)
            {
                raw[r] = data[r];
                dvs[r] = mockSasMissingDataValue(data[r]);
            }
            rawByName.put(e.getKey(), raw);
            dvByName.put(e.getKey(), dvs);
            colTypeByName.put(e.getKey(), DataValueType.LONG);
        }
        for (Map.Entry<String, Long[]> e : longColumns.entrySet())
        {
            Long[] data = e.getValue();
            if (rowCount < 0)
            {
                rowCount = data.length;
            }
            Object[] raw = new Object[data.length];
            IDataValue[] dvs = new IDataValue[data.length];
            for (int r = 0; r < data.length; r++)
            {
                raw[r] = data[r];
                dvs[r] = mockNumericDataValue(data[r], DataValueType.LONG);
            }
            rawByName.put(e.getKey(), raw);
            dvByName.put(e.getKey(), dvs);
            colTypeByName.put(e.getKey(), DataValueType.LONG);
        }
        for (Map.Entry<String, Double[]> e : doubleColumns.entrySet())
        {
            Double[] data = e.getValue();
            if (rowCount < 0)
            {
                rowCount = data.length;
            }
            Object[] raw = new Object[data.length];
            IDataValue[] dvs = new IDataValue[data.length];
            for (int r = 0; r < data.length; r++)
            {
                raw[r] = data[r];
                dvs[r] = mockNumericDataValue(data[r], DataValueType.DOUBLE);
            }
            rawByName.put(e.getKey(), raw);
            dvByName.put(e.getKey(), dvs);
            colTypeByName.put(e.getKey(), DataValueType.DOUBLE);
        }

        if (rawByName.isEmpty())
        {
            throw new IllegalStateException("Need at least one column");
        }
        String[] colNames = rawByName.keySet().toArray(String[]::new);
        final int rc = rowCount;

        IDataTable table = mock(IDataTable.class);
        DataTableMeta meta = mock(DataTableMeta.class);
        lenient().when(table.getMetaData()).thenReturn(meta);
        lenient().when(table.getRowCount()).thenReturn((long) rc);
        // MockTable represents a non-filtered/non-sorted base table — display row index
        // equals real row index. RuleRunner now emits Violation rows via getRealRowIndex
        // (commit 9b7bf4b3f), so the unstubbed default of 0 would mask the actual row.
        lenient().when(table.getRealRowIndex(anyLong())).thenAnswer(inv -> inv.getArgument(0));
        lenient().when(meta.getColumnCount()).thenReturn(colNames.length);
        lenient().when(meta.getName()).thenReturn(tableName);
        lenient().when(meta.getLabel()).thenReturn(tableLabel);
        lenient().when(meta.getTableURI())
                .thenReturn(tableUri == null ? null : java.net.URI.create(tableUri));
        metaValues.forEach((k, v) -> lenient().when(meta.getMetaData(k)).thenReturn(v));

        // Default: column not found
        lenient().when(meta.getOptionalColumn(anyString())).thenReturn(null);
        lenient().when(meta.getColumnIndex(anyString())).thenReturn(-1);

        for (int c = 0; c < colNames.length; c++)
        {
            String colName = colNames[c];
            Object[] raw = rawByName.get(colName);
            IDataValue[] dvs = dvByName.get(colName);
            final int colIdx = c;

            DataTableColumnMeta colMeta = mock(DataTableColumnMeta.class);
            lenient().when(colMeta.getName()).thenReturn(colName);
            lenient().when(colMeta.getIndex()).thenReturn(colIdx);
            DataValueType colType = colTypeByName.get(colName);
            lenient().when(colMeta.getType()).thenReturn(colType);
            lenient().when(colMeta.getLabel()).thenReturn(colLabels.get(colName));
            lenient().when(colMeta.getDisplayFormat()).thenReturn(colFormats.get(colName));
            Integer declaredLen = colLengths.get(colName);
            lenient().when(colMeta.getLength()).thenReturn(declaredLen != null ? declaredLen : 0);

            lenient().when(meta.getOptionalColumn(colName)).thenReturn(colMeta);
            lenient().when(meta.getColumnIndex(colName)).thenReturn(colIdx);
            lenient().when(meta.getColumn(colIdx)).thenReturn(colMeta);
            if (caseInsensitiveColumns)
            {
                for (String variant : new String[]
                {
                        colName.toUpperCase(java.util.Locale.ROOT),
                        colName.toLowerCase(java.util.Locale.ROOT)
                })
                {
                    lenient().when(meta.getOptionalColumn(variant)).thenReturn(colMeta);
                    lenient().when(meta.getColumnIndex(variant)).thenReturn(colIdx);
                }
            }

            IDataTableColumn col = mock(IDataTableColumn.class);
            lenient().when(col.getRowCount()).thenReturn((long) rc);
            lenient().when(table.getColumn(colIdx)).thenReturn(col);

            for (int r = 0; r < rc; r++)
            {
                lenient().when(col.getDataValue(r)).thenReturn(dvs[r]);
                lenient().when(table.getDataValue(r, colIdx)).thenReturn(dvs[r]);
                lenient().when(table.getValue(r, colIdx)).thenReturn(raw[r]);
            }

            // ⚠ The RAW accessor and the blankness fast paths must be stubbed too, or the mock is
            // not a faithful column: a real IDataTableColumn answers getValue(row) and
            // getDataValue(row) about the same cell, and its isMissingOrNull / isEmptyOrMissing
            // defaults are computed from getValue. Mockito does NOT run an unstubbed default
            // method — it answers false — so leaving these out makes any production code that
            // takes the allocation-free path (which is the point of those methods) read every
            // cell as a populated blank. Measured: it turned WildcardValueCollectionTest's
            // collected values from [1, 3] into ["", ""].
            //
            // ⚠⚠ Stubbed ONCE PER COLUMN with an answer, not once per (row, method) like the
            // block above. Mockito stubbing is not free: doing these four per cell added ~8k
            // stubbings to a 1024-row two-column table, and JoinCacheConcurrencyTest — which
            // rebuilds such tables on every worker thread behind a hard 60 s latch — went from
            // green to a reproducible "workers did not finish" timeout. Prefer an answer over a
            // per-row stub for anything added here.
            final IDataValue[] cells = dvs;
            lenient().when(col.getValue(anyLong())).thenAnswer(inv -> cellAt(cells, inv));
            lenient().when(col.isMissingOrNull(anyLong()))
                    .thenAnswer(inv -> isMissingOrNull(cellAt(cells, inv)));
            lenient().when(col.isEmptyOrMissing(anyLong()))
                    .thenAnswer(inv -> isBlank(cellAt(cells, inv)));
            lenient().when(table.isMissingOrNull(anyLong(), eq(colIdx)))
                    .thenAnswer(inv -> isMissingOrNull(cellAt(cells, inv)));
            lenient().when(table.isEmptyOrMissing(anyLong(), eq(colIdx)))
                    .thenAnswer(inv -> isBlank(cellAt(cells, inv)));
        }

        return table;
    }


    /**
     * The raw stored object behind the cell a stubbed accessor was asked for, taken from the same
     * {@link IDataValue} the {@code getDataValue} stubs return so the two views cannot disagree.
     * Out-of-range rows answer {@code null}, which reads as blank — the same shape a real column's
     * short-buffer edge case produces.
     */
    private static Object cellAt(IDataValue[] aCells, org.mockito.invocation.InvocationOnMock aInv)
    {
        long row = aInv.getArgument(0);
        if (row < 0 || row >= aCells.length)
        {
            return null;
        }
        IDataValue dv = aCells[(int) row];
        return dv != null ? dv.getValue() : null;
    }


    /** Mirrors {@code IDataTableColumn.isMissingOrNull}'s default over a raw stored value. */
    private static boolean isMissingOrNull(Object aCell)
    {
        return aCell == null || aCell instanceof MissingValue;
    }


    /** Mirrors {@code IDataTableColumn.isEmptyOrMissing}'s default over a raw stored value. */
    private static boolean isBlank(Object aCell)
    {
        return isMissingOrNull(aCell) || (aCell instanceof String s && s.isEmpty());
    }


    /**
     * A cell modelling a real SAS missing marker: missing-or-invalid, and rendering {@code "."}
     * rather than {@code ""}. See {@link #colSasMissing} for why the distinction matters.
     */
    private static IDataValue mockSasMissingDataValue(String raw)
    {
        IDataValue dv = mock(IDataValue.class);
        if (raw == null)
        {
            lenient().when(dv.isMissingOrInvalid()).thenReturn(true);
            lenient().when(dv.getValue()).thenReturn(net.cumba.datatable.values.MissingValue.MIS);
            lenient().when(dv.getValueAsString()).thenReturn(".");
            lenient().when(dv.getValueAsDouble()).thenReturn(Double.NaN);
            lenient().when(dv.getType()).thenReturn(DataValueType.MISSING);
            return dv;
        }
        lenient().when(dv.isMissingOrInvalid()).thenReturn(false);
        lenient().when(dv.getValue()).thenReturn(raw);
        lenient().when(dv.getValueAsString()).thenReturn(raw);
        lenient().when(dv.getType()).thenReturn(DataValueType.LONG);
        // ⚠ Parse BEFORE opening the stub. Calling Double.parseDouble inside when(...) leaves
        // Mockito with an unfinished stubbing when it throws, which surfaces as a confusing
        // UnfinishedStubbingException in an unrelated later test.
        double asDouble;
        try
        {
            asDouble = Double.parseDouble(raw);
        }
        catch (NumberFormatException _)
        {
            asDouble = Double.NaN;
        }
        lenient().when(dv.getValueAsDouble()).thenReturn(asDouble);
        return dv;
    }


    private static IDataValue mockNumericDataValue(Number raw, DataValueType type)
    {
        IDataValue dv = mock(IDataValue.class);
        if (raw == null)
        {
            lenient().when(dv.isMissingOrInvalid()).thenReturn(true);
            lenient().when(dv.getValue()).thenReturn(null);
            lenient().when(dv.getValueAsString()).thenReturn("");
            lenient().when(dv.getValueAsDouble()).thenReturn(Double.NaN);
            lenient().when(dv.getType()).thenReturn(DataValueType.MISSING);
            return dv;
        }
        lenient().when(dv.isMissingOrInvalid()).thenReturn(false);
        lenient().when(dv.getValue()).thenReturn(raw);
        lenient().when(dv.getValueAsString()).thenReturn(raw.toString());
        lenient().when(dv.getValueAsDouble()).thenReturn(raw.doubleValue());
        lenient().when(dv.getType()).thenReturn(type);
        return dv;
    }


    private static IDataValue mockDataValue(String raw)
    {
        IDataValue dv = mock(IDataValue.class);
        if (raw == null)
        {
            lenient().when(dv.isMissingOrInvalid()).thenReturn(true);
            lenient().when(dv.getValue()).thenReturn(null);
            lenient().when(dv.getValueAsString()).thenReturn("");
            lenient().when(dv.getValueAsDouble()).thenReturn(Double.NaN);
            lenient().when(dv.getType()).thenReturn(DataValueType.MISSING);
            return dv;
        }
        lenient().when(dv.isMissingOrInvalid()).thenReturn(false);
        lenient().when(dv.getValue()).thenReturn(raw);
        lenient().when(dv.getValueAsString()).thenReturn(raw);
        lenient().when(dv.getType()).thenReturn(DataValueType.STRING);
        try
        {
            double d = Double.parseDouble(raw);
            lenient().when(dv.getValueAsDouble()).thenReturn(d);
        }
        catch (NumberFormatException _)
        {
            lenient().when(dv.getValueAsDouble()).thenReturn(Double.NaN);
        }
        return dv;
    }

}
