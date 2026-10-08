package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.corej.core.exec.ScalarSemantics;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * {@link ListVector} — K column-wide element vectors, a row's list materialised lazily and memoised
 * ({@code PLAN-elementwise-list-functions} §3.7, Q10).
 */
class ListVectorTest
{

    /** A per-row vector that counts its producer calls. */
    private static ComputedVector counting(AtomicInteger calls, String... rows)
    {
        return new ComputedVector(rows.length, DataValueType.STRING, row ->
        {
            calls.incrementAndGet();
            return rows[row];
        });
    }


    @Test
    void aRowIsTheElementCellsInInputOrder()
    {
        ListVector v = new ListVector(2, List.of(counting(new AtomicInteger(), "a1", "a2"),
                ConstVector.of("k"), counting(new AtomicInteger(), "c1", "c2")));
        assertEquals(3, v.size());
        assertEquals(List.of("a1", "k", "c1"), texts(v.value(0)));
        assertEquals(List.of("a2", "k", "c2"), texts(v.value(1)));
        assertEquals(DataValueType.STRING, v.declaredType());
        assertNull(v.gatedName());
    }


    @Test
    void aMissingElementStaysAtItsPositionAsItsOwnMissing()
    {
        ComputedVector missing = ComputedVector.typed(1, DataValueType.STRING,
                _ -> ScalarSemantics.computedMissing());
        ListVector v = new ListVector(1,
                List.of(ConstVector.of("a"), missing, ConstVector.of("c")));
        List<?> cells = (List<?>) v.value(0).resolved();
        assertEquals(3, cells.size(), "the list keeps its length (DCF Q4)");
        assertSame(MissingValue.MIS, ((IDataValue) cells.get(1)).getValue());
        assertEquals("a", ((IDataValue) cells.get(0)).getValueAsString());
        assertFalse(v.value(0).isMissing(), "the LIST is present; its member is missing");
    }


    @Test
    void aRowIsMaterialisedLazilyAndOnce()
    {
        AtomicInteger calls = new AtomicInteger();
        ListVector v = new ListVector(3,
                List.of(counting(calls, "a", "b", "c"), counting(calls, "x", "y", "z")));
        assertEquals(0, calls.get(), "constructing the list reads no cell");
        assertEquals(2, v.elements().size());
        assertEquals(0, calls.get(), "handing out the element vectors reads no cell");
        TypedValue row1 = v.value(1);
        assertEquals(2, calls.get(), "one row materialised: one read per element");
        assertSame(row1, v.value(1), "memoised: the same carrier, no second read");
        assertEquals(2, calls.get());
        assertEquals(List.of("b", "y"), texts(row1));
    }


    @Test
    void theElementsAreUnmodifiableAndTheRowListToo()
    {
        ListVector v = new ListVector(1, List.of(ConstVector.of("a")));
        assertThrows(UnsupportedOperationException.class,
                () -> v.elements().add(ConstVector.of("b")));
        List<?> cells = (List<?>) v.value(0).resolved();
        assertThrows(UnsupportedOperationException.class, () -> cells.remove(0));
    }


    private static List<String> texts(TypedValue row)
    {
        List<?> cells = (List<?>) row.resolved();
        return cells.stream().map(c -> ((IDataValue) c).getValueAsString()).toList();
    }
}
