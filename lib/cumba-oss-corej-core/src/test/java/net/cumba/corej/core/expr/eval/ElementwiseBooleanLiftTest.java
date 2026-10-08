package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.BitSet;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.corej.core.exec.JoinedRowFold.Quantifier;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * The BOOLEAN arm of {@link ElementwiseLift} ({@code PLAN-elementwise-boolean-predicates} §3.3, P2
 * / P6; runbook W1a EB1) on the {@code is_valid_date} kernel: the K column-wide calls over a
 * {@link ListVector} (the columns form), the one call over a broadcast list (the broadcast form),
 * the flattened per-row lift with scalar and missing rows as singletons (P6), the scalar
 * pass-through (the kernel's own {@link BitSet}, same object), a second argument row-expanded, and
 * purity (a context-free run).
 */
class ElementwiseBooleanLiftTest
{

    private static final EvalFunction IS_VALID_DATE = Objects.requireNonNull(
            Objects.requireNonNull(FunctionRegistry.descriptor("is_valid_date")).fn());

    private static ComputedVector rows(Object... values)
    {
        return new ComputedVector(values.length, DataValueType.STRING, row -> values[row]);
    }


    private static Object apply(EvalFunction fn, int rows, Vector... args)
    {
        return fn.apply(EvalRun.ofRowCount(rows), List.of(args));
    }


    private static BitSet bits(int... set)
    {
        BitSet out = new BitSet();
        for (int i : set)
        {
            out.set(i);
        }
        return out;
    }


    @Test
    void aListVectorIsLiftedAsKColumnWideCallsIntoTheColumnsForm()
    {
        AtomicInteger aReads = new AtomicInteger();
        ComputedVector a = new ComputedVector(3, DataValueType.STRING, row ->
        {
            aReads.incrementAndGet();
            return row == 1 ? "x" : "2020-01-0" + (row + 1);
        });
        ListVector in = new ListVector(3,
                List.of(a, rows("2020-02-02", "2020-02-30", DataValues.of(MissingValue.MIS_A))));

        BoolListVector out = assertInstanceOf(BoolListVector.class, apply(IS_VALID_DATE, 3, in));

        // row 0: [T, T]; row 1: [F, F]; row 2: [T, F] (a missing element is the kernel's own
        // answer on the missing: false)
        assertEquals(bits(0, 2), out.reduce(Quantifier.ANY));
        assertEquals(bits(0), out.reduce(Quantifier.ALL));
        assertEquals(bits(1), out.reduce(Quantifier.NONE));
        assertEquals(List.of(true, false), out.value(2).resolved());
        assertEquals(3, aReads.get(), "each element row read once — no per-row list built");
    }


    @Test
    void aBroadcastListIsOneCallOverItsElementsIntoTheBroadcastForm()
    {
        AtomicInteger calls = new AtomicInteger();
        EvalFunction counting = ElementwiseLift.lift("p", (run, args) ->
        {
            calls.incrementAndGet();
            return Primitives.stringPredicate(args.get(0), run.rowCount(), s -> s.startsWith("2"));
        }, true);
        Object out = apply(counting, 4, ConstVector.of(List.of("2020", "x", "2021")));
        BoolListVector list = assertInstanceOf(BoolListVector.class, out);
        assertTrue(list.isBroadcast());
        assertEquals(1, calls.get(), "one kernel call over the K elements");
        assertEquals(List.of(true, false, true), list.value(3).resolved());
        assertEquals(bits(0, 1, 2, 3), list.reduce(Quantifier.ANY));
        assertEquals(bits(), list.reduce(Quantifier.ALL));
        // the empty list: the conventions
        BoolListVector empty = assertInstanceOf(BoolListVector.class,
                apply(IS_VALID_DATE, 2, ConstVector.of(List.of())));
        assertEquals(bits(0, 1), empty.reduce(Quantifier.ALL));
        assertEquals(bits(), empty.reduce(Quantifier.ANY));
    }


    @Test
    void aPerRowListIsFlattenedAndAScalarOrMissingRowIsASingleton()
    {
        AtomicInteger calls = new AtomicInteger();
        EvalFunction counting = ElementwiseLift.lift("is_valid_date", (run, args) ->
        {
            calls.incrementAndGet();
            return Primitives.stringPredicate(args.get(0), run.rowCount(),
                    CalendarDates::isValidDate);
        }, true);
        // row 0: a list [valid, bad]; row 1: a scalar valid date; row 2: a missing; row 3: an
        // empty list; row 4: a list of two valid dates
        ComputedVector x = rows(List.of("2020-01-01", "bad"), "2020-01-02",
                DataValues.of(MissingValue.MIS), List.of(), List.of("2020-01-03", "2020-01-04"));
        BoolListVector out = assertInstanceOf(BoolListVector.class, apply(counting, 5, x));
        assertEquals(1, calls.get(), "one kernel call over the flattened range");
        assertEquals(bits(0, 1, 4), out.reduce(Quantifier.ANY));
        assertEquals(bits(1, 3, 4), out.reduce(Quantifier.ALL), "the empty row 3 is all([])");
        assertEquals(bits(2, 3), out.reduce(Quantifier.NONE), "the missing row 2 answers false");
        assertEquals(List.of(true, false), out.value(0).resolved());
        assertEquals(List.of(true), out.value(1).resolved(), "P6: a scalar row is a singleton");
        assertEquals(List.of(false), out.value(2).resolved(), "P6: a missing row is a singleton");
        assertEquals(List.of(), out.value(3).resolved());
    }


    @Test
    void aScalarArgumentIsTheKernelsOwnBitSet()
    {
        ColumnVectorLike scalar = new ColumnVectorLike();
        Object out = apply(IS_VALID_DATE, 3, scalar.vector);
        BitSet bitSet = assertInstanceOf(BitSet.class, out);
        assertEquals(bits(0, 2), bitSet);
        // a per-row vector no row of which holds a list: the kernel's answer, unchanged
        assertEquals(bits(0), apply(IS_VALID_DATE, 2, rows("2020-01-01", "x")));
        // the kernel under the lift is the registered scalar function
        EvalFunction kernel = ElementwiseLift.kernelOf(IS_VALID_DATE);
        assertTrue(ElementwiseLift.isLifted(IS_VALID_DATE));
        assertSame(IS_VALID_DATE, ElementwiseLift.lift("is_valid_date", IS_VALID_DATE, true),
                "lifting a lifted function is the identity");
        assertEquals(bits(0, 2), apply(kernel, 3, scalar.vector));
    }


    @Test
    void aSecondArgumentIsRowExpandedOverTheFlattenedRange()
    {
        // a two-argument predicate kernel: element starts with the row's prefix
        EvalFunction startsWith = ElementwiseLift.lift("p", (run, args) ->
        {
            BitSet out = new BitSet();
            for (int i = 0; i < run.rowCount(); i++)
            {
                if (args.get(0).asString(i).startsWith(args.get(1).asString(i)))
                {
                    out.set(i);
                }
            }
            return out;
        }, true);
        ComputedVector x = rows(List.of("ab", "ba"), List.of("ca", "cb", "x"));
        ComputedVector prefix = rows("a", "c");
        BoolListVector out = assertInstanceOf(BoolListVector.class,
                apply(startsWith, 2, x, prefix));
        assertEquals(List.of(true, false), out.value(0).resolved());
        assertEquals(List.of(true, true, false), out.value(1).resolved());
    }

    /** A column-like vector of three date texts (row 1 invalid). */
    private static final class ColumnVectorLike
    {

        final Vector vector = rows("2020-01-01", "2020-02-30", "2020-03-03");
    }
}
