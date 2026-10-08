package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;
import net.cumba.datatable.values.DataValueType;
import net.cumba.datatable.values.IDataValue;
import net.cumba.datatable.values.MissingValue;
import org.junit.jupiter.api.Test;

/**
 * The mechanics of {@link ElementwiseLift} ({@code PLAN-elementwise-list-functions} §3.6, runbook
 * W1a E4), one test per case, on the {@code upper} kernel and on a synthetic two-argument kernel:
 * the K column-wide calls over a {@link ListVector} (input order — the planted control reverses
 * it), the one call over a broadcast list, the flattened per-row lift with its regrouping and
 * row-expanded other arguments, the scalar pass-through, the element representations (a missing
 * element's identity, a nested list's rendering) and purity (a context-free run).
 */
class ElementwiseLiftTest
{

    private static final EvalFunction UPPER = Objects
            .requireNonNull(Objects.requireNonNull(FunctionRegistry.descriptor("upper")).fn());

    /** {@code a/b}: a two-argument kernel reading both arguments per row. */
    private static final EvalFunction JOIN = (run, args) -> new ComputedVector(run.rowCount(),
            DataValueType.STRING, row ->
            {
                Vector x = args.get(0);
                Vector y = args.get(1);
                return x.value(row).missing() != null ? x.value(row).cell()
                        : x.asString(row) + "/" + y.asString(row);
            });

    private static ComputedVector rows(Object... values)
    {
        return new ComputedVector(values.length, DataValueType.STRING, row -> values[row]);
    }


    private static List<String> texts(TypedValue tv)
    {
        List<String> out = new ArrayList<>();
        for (Object o : (List<?>) Objects.requireNonNull(tv.resolved()))
        {
            out.add(o instanceof IDataValue cell ? cell.getValueAsString() : String.valueOf(o));
        }
        return out;
    }


    private static Vector apply(EvalFunction fn, int rows, Vector... args)
    {
        return (Vector) fn.apply(EvalRun.ofRowCount(rows), List.of(args));
    }

    // ------------------------------------------------------------------ case 2: ListVector


    @Test
    void aListVectorIsLiftedAsKColumnWideCallsInInputOrder()
    {
        AtomicInteger aCalls = new AtomicInteger();
        ComputedVector a = new ComputedVector(2, DataValueType.STRING, row ->
        {
            aCalls.incrementAndGet();
            return row == 0 ? "a1" : "a2";
        });
        ListVector in = new ListVector(2,
                List.of(a, rows("b1", DataValues.of(MissingValue.MIS_A))));

        ListVector out = assertInstanceOf(ListVector.class, apply(UPPER, 2, in));

        assertEquals(2, out.size(), "K element vectors, one per input element vector");
        assertEquals(List.of("A1", "B1"), texts(out.value(0)), "input order");
        assertEquals("A2", texts(out.value(1)).get(0));
        // the missing member stays at its position as its own missing (Q5, identity kept)
        IDataValue member = (IDataValue) ((List<?>) Objects.requireNonNull(out.value(1).resolved()))
                .get(1);
        assertSame(MissingValue.MIS_A, TypedValue.missingIdentityOf(member));
        assertEquals(2, aCalls.get(), "each element row produced once — no per-row list built");
    }

    // ------------------------------------------------------------------ case 3: broadcast list


    @Test
    void aBroadcastListIsOneConstantWithEveryElementFoldedAndAMissingElementKept()
    {
        MissingValue missing = MissingValue.MIS_A;
        ConstVector out = assertInstanceOf(ConstVector.class,
                apply(UPPER, 0, ConstVector.of(List.of("a", missing, "b"))));
        List<?> folded = (List<?>) Objects.requireNonNull(out.value());
        assertEquals(List.of("A", missing, "B"), folded);
        assertSame(missing, folded.get(1), "the original element, not a re-wrapped missing");
        // an empty list folds to an empty list (Q6); a missing constant is handed through
        assertEquals(List.of(), ((ConstVector) apply(UPPER, 0, ConstVector.of(List.of()))).value());
        ConstVector none = ConstVector.of(null);
        assertSame(none, apply(UPPER, 0, none));
        // a nested element keeps TODAY's rendering (E4): the fold applies to its text — E7a (W4)
        // makes it the Q7 rule ERROR
        assertEquals(List.of("[A, B]", "C"),
                ((ConstVector) apply(UPPER, 0, ConstVector.of(List.of(List.of("a", "b"), "c"))))
                        .value());
    }


    @Test
    void aBroadcastListWithAPerRowOtherArgumentIsLiftedPerRow()
    {
        Vector out = apply(ElementwiseLift.lift("join", JOIN), 2, ConstVector.of(List.of("a", "b")),
                rows("1", "2"));
        assertInstanceOf(ComputedVector.class, out);
        assertEquals(List.of("a/1", "b/1"), texts(out.value(0)));
        assertEquals(List.of("a/2", "b/2"), texts(out.value(1)));
    }

    // ------------------------------------------------------------------ case 4: per-row list


    @Test
    void aPerRowListIsFlattenedLiftedOnceAndRegroupedPerRow()
    {
        AtomicInteger kernelCalls = new AtomicInteger();
        EvalFunction counting = ElementwiseLift.lift("upper", (run, args) ->
        {
            kernelCalls.incrementAndGet();
            return ElementwiseLift.kernelOf(UPPER).apply(run, args);
        });
        Vector in = rows(List.of("yes", "n"), "scalar", DataValues.of(MissingValue.MIS_A),
                List.of("q", MissingValue.MIS), List.of());

        Vector out = apply(counting, 5, in);

        assertEquals(1, kernelCalls.get(), "the kernel runs once over the flattened range");
        assertEquals(List.of("YES", "N"), out.value(0).resolved());
        assertEquals("SCALAR", out.value(1).resolved(), "a scalar row answers as the scalar call");
        assertSame(MissingValue.MIS_A, out.value(2).missing(), "a missing row is that missing");
        List<?> row3 = (List<?>) Objects.requireNonNull(out.value(3).resolved());
        assertEquals("Q", row3.get(0));
        assertSame(MissingValue.MIS, row3.get(1), "a missing element is the original element");
        assertEquals(List.of(), out.value(4).resolved());
        assertEquals(DataValueType.STRING, out.declaredType());
    }


    @Test
    void theOtherArgumentsAreRowExpandedSoEveryElementReadsItsRowsValue()
    {
        Vector out = apply(ElementwiseLift.lift("join", JOIN), 3,
                rows(List.of("a", "b"), "s", List.of("c")), rows("1", "2", "3"));
        assertEquals(List.of("a/1", "b/1"), texts(out.value(0)));
        assertEquals("s/2", out.value(1).resolved());
        assertEquals(List.of("c/3"), texts(out.value(2)));
    }


    @Test
    void aPerRowVectorWithoutAnyListRowIsTheUnchangedKernelCall()
    {
        AtomicInteger kernelCalls = new AtomicInteger();
        EvalFunction counting = ElementwiseLift.lift("upper", (run, args) ->
        {
            kernelCalls.incrementAndGet();
            return ElementwiseLift.kernelOf(UPPER).apply(run, args);
        });
        Vector out = apply(counting, 3, rows("a", DataValues.of(MissingValue.MIS), "c"));
        assertEquals(1, kernelCalls.get());
        assertEquals("A", out.value(0).resolved());
        assertSame(MissingValue.MIS, out.value(1).missing());
        assertEquals("C", out.value(2).resolved());
    }

    // ------------------------------------------------------------------ case 1 and the wrapper


    @Test
    void aScalarConstantIsTheUnchangedKernelCallAndTheWrapperIsIdempotent()
    {
        ConstVector folded = assertInstanceOf(ConstVector.class,
                apply(UPPER, 0, ConstVector.of("ab")));
        assertEquals("AB", folded.value());
        assertTrue(ElementwiseLift.isLifted(UPPER), "the registry publishes the lifted function");
        EvalFunction kernel = ElementwiseLift.kernelOf(UPPER);
        assertFalse(ElementwiseLift.isLifted(kernel));
        assertSame(UPPER, ElementwiseLift.lift("upper", UPPER), "lifting a lifted function");
        assertSame(kernel, ElementwiseLift.kernelOf(kernel));
        // the kernel alone does NOT fold a list — the collection branch lives in the lift now
        ConstVector raw = assertInstanceOf(ConstVector.class,
                apply(kernel, 0, ConstVector.of(List.of("a", "b"))));
        assertEquals("[A, B]", raw.value());
    }


    @Test
    void theLiftedCallIsContextFree()
    {
        // every case above ran under EvalRun.ofRowCount (a null context): a flagged kernel reads
        // run.rowCount() and its arguments only (§3.4 purity) — asserted once more on the K-call
        // path, which builds its own runs
        ListVector in = new ListVector(1, List.of(rows("x"), rows("y")));
        assertNull(EvalRun.ofRowCount(1).ctx());
        assertEquals(List.of("X", "Y"), texts(apply(UPPER, 1, in).value(0)));
    }

}
