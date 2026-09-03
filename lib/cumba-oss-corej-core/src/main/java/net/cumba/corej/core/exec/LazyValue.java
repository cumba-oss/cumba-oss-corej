package net.cumba.corej.core.exec;

import java.util.function.Supplier;
import org.jspecify.annotations.Nullable;

/**
 * Lazy, memoising value holder used by {@link RuleRunner} to defer Operation evaluation until the
 * first read. The variables map populated in phase 2a stores {@code LazyValue<Object>} instances
 * under each Operation's {@code $id}; downstream read sites unwrap via
 * {@link EvaluationContext#resolveVariable(String)} so an Operation whose result is never consulted
 * (e.g., because the Check tree folds to a dataset-level constant before any leaf references it)
 * never runs.
 *
 * <p>
 * Concurrency: the engine is mostly single-threaded per rule today, but the supplier may close over
 * an {@link EvaluationContext} that could be shared by a future parallel evaluator. Double-checked
 * locking on {@code computed} keeps the contract correct without paying for synchronisation on
 * every {@link #get()} once the value is materialised.
 * </p>
 *
 * <p>
 * Error semantics: if the supplier throws, the exception is cached on the first {@link #get()} and
 * re-thrown on every subsequent call. The supplier is never re-invoked. This keeps supplier-side
 * bugs loud rather than silently retrying.
 * </p>
 *
 * @param <T>
 *            the type produced by the wrapped supplier
 */
public final class LazyValue<T>
{

    private final Supplier<T> supplier;

    private volatile boolean computed;

    private volatile @Nullable T value;

    private volatile @Nullable RuntimeException error;

    public LazyValue(Supplier<T> supplier)
    {
        this.supplier = supplier;
    }


    /**
     * Returns the wrapped value, computing it on first access. Subsequent calls return the cached
     * value. If the supplier threw on the first call, the same exception is re-thrown on every
     * subsequent call (no recompute).
     */
    public @Nullable T get()
    {
        if (!computed)
        {
            synchronized (this)
            {
                if (!computed)
                {
                    try
                    {
                        value = supplier.get();
                    }
                    catch (RuntimeException e)
                    {
                        error = e;
                        computed = true;
                        throw e;
                    }
                    computed = true;
                }
            }
        }
        if (error != null)
        {
            throw error;
        }
        return value;
    }


    /**
     * Returns whether the value has been computed (forced) yet. Exposed for tests so they can
     * assert that a particular Operation was never invoked.
     */
    public boolean isComputed()
    {
        return computed;
    }

}
