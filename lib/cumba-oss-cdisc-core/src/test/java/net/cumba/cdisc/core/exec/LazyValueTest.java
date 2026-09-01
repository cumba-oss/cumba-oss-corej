package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class LazyValueTest
{

    @Test
    void singleGetInvokesSupplierExactlyOnce()
    {
        AtomicInteger calls = new AtomicInteger();
        LazyValue<String> lazy = new LazyValue<>(() ->
        {
            calls.incrementAndGet();
            return "hello";
        });

        assertEquals("hello", lazy.get());
        assertEquals(1, calls.get());
    }


    @Test
    void multipleGetsMemoiseTheResult()
    {
        AtomicInteger calls = new AtomicInteger();
        LazyValue<String> lazy = new LazyValue<>(() ->
        {
            calls.incrementAndGet();
            return "value-" + calls.get();
        });

        String first = lazy.get();
        String second = lazy.get();
        String third = lazy.get();

        assertEquals("value-1", first);
        assertSame(first, second, "memoised reference should be reused");
        assertSame(first, third);
        assertEquals(1, calls.get(), "supplier should run exactly once");
    }


    @Test
    void isComputedFlipsAfterFirstGet()
    {
        LazyValue<Integer> lazy = new LazyValue<>(() -> 42);

        assertFalse(lazy.isComputed(), "should be uncomputed before first get");
        assertEquals(42, lazy.get());
        assertTrue(lazy.isComputed(), "should be computed after first get");
    }


    @Test
    void supplierExceptionIsCachedAndRethrown()
    {
        AtomicInteger calls = new AtomicInteger();
        LazyValue<String> lazy = new LazyValue<>(() ->
        {
            calls.incrementAndGet();
            throw new IllegalStateException("boom");
        });

        IllegalStateException first = assertThrows(IllegalStateException.class, lazy::get);
        IllegalStateException second = assertThrows(IllegalStateException.class, lazy::get);
        IllegalStateException third = assertThrows(IllegalStateException.class, lazy::get);

        assertEquals("boom", first.getMessage());
        assertSame(first, second, "cached exception should be re-thrown without recompute");
        assertSame(first, third);
        assertEquals(1, calls.get(), "supplier must NOT be re-invoked on subsequent gets");
        assertTrue(lazy.isComputed(), "isComputed flips even when supplier threw");
    }


    @Test
    void nullResultIsValid()
    {
        AtomicInteger calls = new AtomicInteger();
        LazyValue<String> lazy = new LazyValue<>(() ->
        {
            calls.incrementAndGet();
            return null;
        });

        assertNull(lazy.get());
        assertNull(lazy.get());
        assertEquals(1, calls.get());
        assertTrue(lazy.isComputed());
    }


    @Test
    void concurrentGetsInvokeSupplierExactlyOnce() throws InterruptedException, ExecutionException
    {
        AtomicInteger calls = new AtomicInteger();
        CountDownLatch start = new CountDownLatch(1);
        // Slow supplier so threads collide on the synchronized block.
        LazyValue<Integer> lazy = new LazyValue<>(() ->
        {
            calls.incrementAndGet();
            try
            {
                Thread.sleep(50);
            }
            catch (InterruptedException _)
            {
                Thread.currentThread().interrupt();
            }
            return 7;
        });

        int threads = 16;
        ExecutorService exec = Executors.newFixedThreadPool(threads);
        try
        {
            Future<?>[] futures = new Future<?>[threads];
            for (int i = 0; i < threads; i++)
            {
                futures[i] = exec.submit(() ->
                {
                    start.await();
                    return lazy.get();
                });
            }
            start.countDown();
            for (Future<?> f : futures)
            {
                assertEquals(7, f.get(2, TimeUnit.SECONDS));
            }
        }
        catch (java.util.concurrent.TimeoutException e)
        {
            throw new AssertionError(e);
        }
        finally
        {
            exec.shutdownNow();
        }
        assertEquals(1, calls.get(), "supplier must run exactly once across all threads");
    }

}
