package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class ViolationSinkTest
{

    private static Violation v(long row)
    {
        return new Violation(row, Map.of());
    }


    @Test
    void storesUpToCap_andCountsTrueTotalViaRecordSkipped()
    {
        ViolationSink sink = new ViolationSink(3);
        int card = 10;
        int taken = 0;
        for (int r = 0; r < card && sink.wantsMore(); r++)
        {
            sink.store(v(r));
            taken++;
        }
        sink.recordSkipped(card - taken);

        assertEquals(3, sink.stored().size());
        assertEquals(10, sink.total());
        assertTrue(sink.truncated());
    }


    @Test
    void wantsMoreFlipsAtCap()
    {
        ViolationSink sink = new ViolationSink(2);
        assertTrue(sink.wantsMore());
        sink.store(v(0));
        assertTrue(sink.wantsMore());
        sink.store(v(1));
        assertFalse(sink.wantsMore());
    }


    @Test
    void notTruncated_whenUnderCap()
    {
        ViolationSink sink = new ViolationSink(5);
        sink.store(v(0));
        sink.store(v(1));
        sink.recordSkipped(0);

        assertEquals(2, sink.stored().size());
        assertEquals(2, sink.total());
        assertFalse(sink.truncated());
    }


    @Test
    void addStoresWhenRoom_alwaysCounts()
    {
        ViolationSink sink = new ViolationSink(1);
        sink.add(v(0)); // stored + counted
        sink.add(v(1)); // counted only (full)
        sink.add(v(2)); // counted only (full)

        assertEquals(1, sink.stored().size());
        assertEquals(3, sink.total());
        assertTrue(sink.truncated());
    }


    @Test
    void unlimitedCapStoresEverything()
    {
        ViolationSink sink = new ViolationSink(Integer.MAX_VALUE);
        for (int r = 0; r < 1000; r++)
        {
            if (sink.wantsMore())
            {
                sink.store(v(r));
            }
        }
        assertEquals(1000, sink.stored().size());
        assertEquals(1000, sink.total());
        assertFalse(sink.truncated());
    }


    @Test
    void recordSkippedIgnoresNonPositive()
    {
        ViolationSink sink = new ViolationSink(5);
        sink.store(v(0));
        sink.recordSkipped(0);
        sink.recordSkipped(-3);
        assertEquals(1, sink.total());
    }

}
