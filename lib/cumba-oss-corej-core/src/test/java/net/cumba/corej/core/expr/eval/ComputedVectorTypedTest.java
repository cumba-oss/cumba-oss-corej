package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.datatable.values.DataValueSupport;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@link ComputedVector}'s typed variant — review findings F8 and F9 of
 * {@code PLAN-joined-column-typing}.
 */
class ComputedVectorTypedTest
{

    private static IDataValue num(double v)
    {
        return DataValueSupport.getAsDataValue(v, DataValueType.DOUBLE);
    }


    @Test
    @DisplayName("F9: the typed producer runs ONCE per row, however many views are read")
    void typedProducerRunsOncePerRow()
    {
        AtomicInteger calls = new AtomicInteger();
        ComputedVector v = ComputedVector.typed(3, DataValueType.DOUBLE, row ->
        {
            calls.incrementAndGet();
            return num(row + 0.5);
        });

        // Read both views of row 0, twice each. Before F9 the typed producer was invoked from
        // value() through a text-converting wrapper AND again from dataValue, so a row whose text
        // and typed forms were both read cost two lookupValue calls -- or two full lookupAllValues
        // scans for a candidates vector.
        v.value(0).cell();
        v.value(0).resolved();
        Primitives.targetAsDouble(v.value(0));
        v.value(0).cell();
        assertEquals(1, calls.get(), "row 0 must be produced exactly once");

        v.value(1).resolved();
        v.value(1).cell();
        assertEquals(2, calls.get(), "row 1 adds exactly one more");
    }


    @Test
    @DisplayName("both views agree, and the cell is the producer's own object")
    void viewsAgree()
    {
        IDataValue cell = num(4.25);
        ComputedVector v = ComputedVector.typed(1, DataValueType.DOUBLE, _ -> cell);
        assertSame(cell, v.value(0).cell(),
                "the typed view is the producer's own value, not a copy");
        assertEquals(cell.getValueAsString(), v.value(0).resolved(),
                "the text view is derived from that same cell");
        assertSame(cell, v.value(0).sourceCell(),
                "the typed comparison read (phase 3d) consumes the producer's own cell");
    }


    /**
     * ⭐ RE-BASED 2026-09-18: the producer used to be {@code _ -> null}, which pinned {@code null}
     * as an accepted row value. A row value is a real value or a {@code MissingValue}, never
     * {@code null} (see {@code ScalarSemantics.computedMissing}), and the producer's type argument
     * no longer permits it — NullAway rejects {@code _ -> null} at compile time. What the vector
     * ANSWERS for a no-result row is unchanged, and that is what is pinned below.
     */
    @Test
    @DisplayName("a no-result typed row answers MISSING as a cell and null as text")
    void nullRowIsMissing()
    {
        ComputedVector v = ComputedVector.typed(1, DataValueType.DOUBLE,
                _ -> net.cumba.corej.core.exec.ScalarSemantics.computedMissing());
        assertTrue(v.value(0).cell().isMissingOrInvalid());
        assertNull(v.value(0).resolved(), "a missing row must not render as the text \"null\"");
    }


    @Test
    @DisplayName("F8: gatedName is carried from construction, and defaults to un-gated")
    void gatedNameIsConstructorSet()
    {
        assertEquals("DM.AGE",
                ComputedVector.typed(1, DataValueType.DOUBLE, _ -> num(1), "DM.AGE").gatedName());
        assertNull(ComputedVector.typed(1, DataValueType.DOUBLE, _ -> num(1)).gatedName(),
                "no name means the column-type gate does not see it");
        assertNull(new ComputedVector(1, DataValueType.STRING, _ -> "x").gatedName());
    }
}
