package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.datatable.DataTableColumnMeta;
import net.cumba.datatable.DataTableMeta;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.impl.CachedDataTableColumn;
import net.cumba.datatable.impl.ColumnCachedDataTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Step B of {@code PLAN-joined-column-typing}: {@code JoinLookup} gains typed accessors
 * <b>beside</b> the text ones, and {@link DatasetLookup} answers them from the parent cell.
 *
 * <p>
 * The migration's whole safety argument is that the typed accessor agrees with the text accessor
 * everywhere except precision — same matches, same blank contract, same 0..N shape — so value
 * readers can move one at a time while the report-text and wildcard consumers stay on
 * {@code lookup}. These tests pin that agreement.
 * </p>
 *
 * <p>
 * ⚠ Real {@link CachedDataTableColumn}s, never {@code MockTable}: MockTable stubs
 * {@code IDataValue} and renders {@code raw.toString()}, so {@code getAsDoubleCleaned} never runs
 * and the precision assertion below could not fail.
 * </p>
 */
class DatasetLookupTypedValueTest
{

    private static final double BIG = 1.0E13 + 1;

    @Test
    @DisplayName("typed lookup keeps the exact value where the text lookup rounds to 12 digits")
    void typedLookupIsExact()
    {
        IDataTable primary = t("AE", col("USUBJID", DataValueType.STRING, "U1"));
        IDataTable joined = t("DM", col("USUBJID", DataValueType.STRING, "U1"),
                col("VAL", DataValueType.DOUBLE, BIG));
        DatasetLookup lk = DatasetLookup.build("DM", joined, List.of("USUBJID"));
        assertNotNull(lk);

        // The text accessor still rounds -- unchanged on purpose, report text depends on it.
        assertEquals("10000000000000", lk.lookup(primary, 0, "VAL"));
        // The typed accessor does not.
        IDataValue dv = lk.lookupValue(primary, 0, "VAL");
        assertNotNull(dv);
        assertEquals(BIG, dv.getValueAsDouble());
        assertEquals(DataValueType.DOUBLE, lk.declaredTypeOf("VAL"));
    }


    @Test
    @DisplayName("typed and text accessors agree on matches, blanks and absence")
    void typedAgreesWithTextEverywhereElse()
    {
        IDataTable primary = t("AE", col("USUBJID", DataValueType.STRING, "U1", "U2", "U3"));
        IDataTable joined = t("DM", col("USUBJID", DataValueType.STRING, "U1", "U2"),
                col("ARM", DataValueType.STRING, "A", null),
                col("AGE", DataValueType.LONG, 30L, null));
        DatasetLookup lk = DatasetLookup.build("DM", joined, List.of("USUBJID"));
        assertNotNull(lk);

        // row 0 -- a present character value
        assertEquals("A", lk.lookup(primary, 0, "ARM"));
        assertEquals("A", lk.lookupValue(primary, 0, "ARM").getValueAsString());
        // row 1 -- a BLANK character cell reads "" through both accessors (the lookup() contract)
        assertEquals("", lk.lookup(primary, 1, "ARM"));
        assertEquals("", lk.lookupValue(primary, 1, "ARM").getValueAsString());
        // row 1 -- a BLANK NUMERIC cell is null through both
        assertNull(lk.lookup(primary, 1, "AGE"));
        assertNull(lk.lookupValue(primary, 1, "AGE"));
        // row 2 -- no matched joined row at all
        assertNull(lk.lookup(primary, 2, "ARM"));
        assertNull(lk.lookupValue(primary, 2, "ARM"));
        // an absent column
        assertNull(lk.lookup(primary, 0, "NOSUCH"));
        assertNull(lk.lookupValue(primary, 0, "NOSUCH"));
        // ⛔ MISSING, not STRING: an absent column's type is UNKNOWN, and STRING would make it
        // indistinguishable from a character column -- which is what made the gate error
        // "DM.AGE is declared Char" on a Num column.
        assertEquals(DataValueType.MISSING, lk.declaredTypeOf("NOSUCH"));
    }


    @Test
    @DisplayName("lookupAllValues mirrors lookupAll's 0..N shape and missing-cell filter")
    void lookupAllValuesMirrorsLookupAll()
    {
        IDataTable primary = t("DM", col("USUBJID", DataValueType.STRING, "U1"));
        IDataTable joined = t("AE", col("USUBJID", DataValueType.STRING, "U1", "U1", "U1"),
                col("SEQ", DataValueType.LONG, 1L, null, 3L));
        DatasetLookup lk = DatasetLookup.build("AE", joined, List.of("USUBJID"));
        assertNotNull(lk);

        List<String> text = lk.lookupAll(primary, 0, "SEQ");
        List<IDataValue> typed = lk.lookupAllValues(primary, 0, "SEQ");
        assertEquals(text.size(), typed.size(), "the missing middle cell is filtered by both");
        assertEquals(2, typed.size());
        for (int i = 0; i < text.size(); i++)
        {
            assertEquals(text.get(i), typed.get(i).getValueAsString());
        }
        assertTrue(lk.lookupAllValues(primary, 0, "NOSUCH").isEmpty());
    }


    @Test
    @DisplayName("the JoinLookup defaults keep an unmigrated implementation on today's behaviour")
    void defaultsWrapTheTextAccessor()
    {
        JoinLookup unmigrated = new JoinLookup()
        {

            @Override
            public String lookup(IDataTable primaryTable, long row, String columnName)
            {
                return "7";
            }


            @Override
            public String getDatasetName()
            {
                return "DS";
            }
        };
        IDataTable primary = t("AE", col("X", DataValueType.STRING, "a"));
        assertEquals("7", unmigrated.lookupValue(primary, 0, "ANY").getValueAsString());
        assertEquals(DataValueType.STRING, unmigrated.lookupValue(primary, 0, "ANY").getType());
        // The interface default means "unknown", so an unmigrated implementation is NOT gated
        // rather than being gated as Char.
        assertEquals(DataValueType.MISSING, unmigrated.declaredTypeOf("ANY"));
    }

    // A varargs carrier for the table builder below, never compared or hashed — the array-valued
    // equals/hashCode this check guards against is unreachable here.
    @SuppressWarnings("ArrayRecordComponent")
    private record Col(String name, DataValueType type, Object[] values)
    {
    }

    private static Col col(String name, DataValueType type, Object... values)
    {
        return new Col(name, type, values);
    }


    private static IDataTable t(String name, Col... cols)
    {
        int rc = cols[0].values().length;
        CachedDataTableColumn[] cc = new CachedDataTableColumn[cols.length];
        DataTableColumnMeta[] mm = new DataTableColumnMeta[cols.length];
        for (int c = 0; c < cols.length; c++)
        {
            cc[c] = new CachedDataTableColumn(c, cols[c].type());
            mm[c] = DataTableColumnMeta.builder().index(c).name(cols[c].name())
                    .label(cols[c].name()).type(cols[c].type()).build();
            for (int r = 0; r < rc; r++)
            {
                cc[c].addElement(cols[c].values()[r]);
            }
            cc[c].complete();
        }
        return new ColumnCachedDataTable(DataTableMeta.builder().name(name).label(name).columns(mm)
                .rowCount(rc).totalRowCount(rc).build(), cc);
    }
}
