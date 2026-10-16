package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicLong;
import net.cumba.corej.core.exec.EvaluationContext;
import net.cumba.corej.core.expr.CheckExpressionParser;
import net.cumba.datatable.IDataTable;
import net.cumba.datatable.IDataTableColumn;
import net.cumba.datatable.testkit.MockTable;
import org.junit.jupiter.api.Test;

/**
 * B5 review ① MED-1 (PACK H fold; owner priority: performance and memory first in the engine). A
 * {@code Char} length operand whose every cell is {@code ""} is a PRESENT value to the arithmetic
 * (the {@code ""} of a populated character cell stays a value) and BLANK to the column-type gate
 * ({@code SVY OQ11}'s exemption), so {@code substring(X, 1, LEN)} reached {@code integral()} on
 * every row — and {@code integral()} re-ran {@code ColumnTypeGate.requireNumericRead}, whose blank
 * scan walks the whole column: {@code n} scans of {@code n} cells. The gate is a function of the
 * VECTOR (the callers hoist it once), and {@link ColumnVector#isBlank} memoises its scan, so the
 * operand's cells are read a bounded number of times — here at most three per row (one blank scan,
 * one per-row read, slack for a second gate site).
 */
class BlankCharLengthOperandCostTest
{

    private static final int ROWS = 300;

    @Test
    void aBlankCharLengthOperandIsScannedOnceNotPerRow()
    {
        String[] x = new String[ROWS];
        Arrays.fill(x, "abc");
        String[] blank = new String[ROWS];
        Arrays.fill(blank, "");
        IDataTable real = MockTable.of().name("T").col("X", x).col("LEN", blank).build();
        AtomicLong lenReads = new AtomicLong();
        IDataTable counting = counting(real, real.getMetaData().getColumnIndex("LEN"), lenReads);
        BitSet fired = NativeExprEvaluator.evaluate(
                CheckExpressionParser.parse("empty(substring(X, 1, LEN))"), EvaluationContext
                        .builder().table(counting).variables(new LinkedHashMap<>()).build());
        assertEquals(ROWS, fired.cardinality(),
                "a \"\" length is a present, non-integral value: the slice is the computed missing");
        assertTrue(lenReads.get() <= 3L * ROWS, "the cells of LEN were read " + lenReads
                + " times for " + ROWS + " rows: the blank scan ran per row");
    }


    /** {@code real} with column {@code column} counting every cell read on {@code reads}. */
    private static IDataTable counting(IDataTable real, int column, AtomicLong reads)
    {
        InvocationHandler table = (proxy, method, args) ->
        {
            Object answer = invoke(method, real, args);
            if ("getColumn".equals(method.getName()) && args != null && args.length == 1
                    && args[0] instanceof Integer idx && idx == column
                    && answer instanceof IDataTableColumn col)
            {
                return countingColumn(col, reads);
            }
            return answer;
        };
        return (IDataTable) Proxy.newProxyInstance(IDataTable.class.getClassLoader(), new Class<?>[]
        {
                IDataTable.class
        }, table);
    }


    private static IDataTableColumn countingColumn(IDataTableColumn col, AtomicLong reads)
    {
        InvocationHandler handler = (proxy, method, args) ->
        {
            String name = method.getName();
            if ("getDataValue".equals(name) || "isEmptyOrMissing".equals(name)
                    || "getValue".equals(name))
            {
                reads.incrementAndGet();
            }
            return invoke(method, col, args);
        };
        return (IDataTableColumn) Proxy.newProxyInstance(IDataTableColumn.class.getClassLoader(),
                new Class<?>[]
                {
                        IDataTableColumn.class
                }, handler);
    }


    private static Object invoke(Method method, Object target, Object[] args) throws Throwable
    {
        try
        {
            return method.invoke(target, args);
        }
        catch (java.lang.reflect.InvocationTargetException e)
        {
            throw e.getCause();
        }
    }
}
