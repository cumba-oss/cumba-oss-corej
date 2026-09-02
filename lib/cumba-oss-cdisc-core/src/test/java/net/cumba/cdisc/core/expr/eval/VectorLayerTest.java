package net.cumba.cdisc.core.expr.eval;

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
        return new ColumnVector(t.getColumn(idx), t.getMetaData().getColumn(idx).getType());
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
        // null. ⚠ The blank-character-cell case (row 2) is DELIBERATELY still null and not "":
        // making it "" is the blindness step, and it is blocked on the date_* defect recorded in
        // ScalarSemantics.resolvedString's javadoc.
        assertEquals("abc", v.resolvedObject(0));
        assertEquals("", v.resolvedObject(1));
        assertNull(v.resolvedObject(2));
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
        assertEquals("M", v.resolvedObject(0));
        assertEquals("M", v.resolvedObject(99));
        assertSame(v.dataValue(0), v.dataValue(7), "broadcast cell should be cached");
        assertFalse(v.isMissing(0));

        ConstVector missing = ConstVector.of(null);
        assertTrue(missing.isMissing(0));
        assertNull(missing.resolvedObject(0));
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
        assertEquals("v0", v.resolvedObject(0));
        assertEquals("v0", v.resolvedObject(0));
        assertEquals(1, calls.get(), "producer memoised per row");
        assertSame(v.dataValue(0), v.dataValue(0), "cell wrapper cached");
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
