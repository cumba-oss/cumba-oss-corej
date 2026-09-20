package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.testkit.MockTable;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class VectorLayerTest
{

    static ColumnVector col(IDataTable t, String name)
    {
        int idx = t.getMetaData().getColumnIndex(name);
        return new ColumnVector(name, t.getColumn(idx), t.getMetaData().getColumn(idx).getType());
    }


    @Test
    void columnVector_stringColumn()
    {
        IDataTable t = MockTable.of().col("X", "abc", "", (String) null).build();
        ColumnVector v = col(t, "X");
        assertEquals(DataValueType.STRING, v.declaredType());
        assertEquals("abc", v.asString(0));
        assertFalse(v.isMissing(0));
        // empty string counts as missing (F3)
        assertTrue(v.isMissing(1));
        // null cell counts as missing
        assertTrue(v.isMissing(2));
        // resolvedObject: present -> string; a present empty string -> ""; missing/invalid ->
        // null. ⭐⭐ Rows 1 and 2 together ARE the distinction the owner ruling of 2026-09-18
        // protects, in the value channel: a stored "" (row 1) stays a present "" and a null char
        // cell (row 2) is missing and answers null. ⛔ This comment used to call row 2 "deliberately
        // still null", with making it "" a pending blindness step blocked on a date_* defect. That
        // step is RETIRED — "missing is not empty string" — so row 2 is settled, and row 1 is the
        // half that must never collapse onto it.
        assertEquals("abc", v.value(0).resolved());
        assertEquals("", v.value(1).resolved());
        assertNull(v.value(2).resolved());
    }


    @Test
    void columnVector_numericColumn()
    {
        IDataTable t = MockTable.of().colLong("N", 5L, null).build();
        ColumnVector v = col(t, "N");
        assertEquals(DataValueType.LONG, v.declaredType());
        assertEquals(5.0, v.asDouble(0));
        assertTrue(Double.isNaN(v.asDouble(1)));
        assertTrue(v.isMissing(1));
    }


    @Test
    void constVector_typeDerivation()
    {
        assertEquals(DataValueType.MISSING, ConstVector.of(null).declaredType());
        assertEquals(DataValueType.STRING, ConstVector.of("x").declaredType());
        assertEquals(DataValueType.LONG, ConstVector.of(5L).declaredType());
        assertEquals(DataValueType.LONG, ConstVector.of(5).declaredType());
        assertEquals(DataValueType.DOUBLE, ConstVector.of(5.0).declaredType());
        assertEquals(DataValueType.BOOLEAN, ConstVector.of(true).declaredType());
    }


    @Test
    void constVector_broadcastsAndCachesCell()
    {
        ConstVector v = ConstVector.of("M");
        assertEquals("M", v.value(0).resolved());
        assertEquals("M", v.value(99).resolved());
        assertSame(v.value(0).cell(), v.value(7).cell(), "broadcast cell should be cached");
        assertFalse(v.isMissing(0));

        ConstVector missing = ConstVector.of(null);
        assertTrue(missing.isMissing(0));
        assertNull(missing.value(0).resolved());
    }


    @Test
    void computedVector_memoisesProducer()
    {
        AtomicInteger calls = new AtomicInteger();
        ComputedVector v = new ComputedVector(3, DataValueType.STRING, row ->
        {
            calls.incrementAndGet();
            return row == 1 ? null : "v" + row;
        });
        assertEquals("v0", v.value(0).resolved());
        assertEquals("v0", v.value(0).resolved());
        assertEquals(1, calls.get(), "producer memoised per row");
        assertSame(v.value(0).cell(), v.value(0).cell(), "cell wrapper cached");
        assertTrue(v.isMissing(1));
        assertEquals(DataValueType.STRING, v.declaredType());
        assertEquals(2, calls.get(), "rows 0 and 1 computed exactly once each");
    }


    @Test
    void dataValues_wrapping()
    {
        IDataValue missing = DataValues.of(null);
        assertTrue(missing.isMissingOrInvalid());
        assertEquals("", missing.getValueAsString());
        assertTrue(Double.isNaN(missing.getValueAsDouble()));
        assertEquals(DataValueType.MISSING, missing.getType());

        IDataValue num = DataValues.of(3.5);
        assertEquals(3.5, num.getValueAsDouble());
        assertEquals(DataValueType.DOUBLE, num.getType());

        IDataValue str = DataValues.of("12");
        assertEquals(12.0, str.getValueAsDouble());
        assertEquals(DataValueType.STRING, str.getType());

        IDataValue nonNumeric = DataValues.of("abc");
        assertTrue(Double.isNaN(nonNumeric.getValueAsDouble()));
    }

}
