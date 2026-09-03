package net.cumba.corej.core.expr.eval;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import net.cumba.corej.core.expr.ExpressionException;
import net.cumba.datatable.io.GenericServiceFactory;
import org.jspecify.annotations.Nullable;

/**
 * The {@code (name, arity) -> EvalFunction} registry. Built-in providers are discovered via the
 * project SPI ({@link GenericServiceFactory} over {@code META-INF/services/}) on class
 * initialisation; additional functions may be registered programmatically (for tests / embedding).
 *
 * <h2>Policies</h2>
 * <ul>
 * <li><b>Lookup</b> is by exact {@code (name, arity)}. An unknown pair raises an
 * {@link ExpressionException} at <i>compile</i> time (fail loudly, never silent).</li>
 * <li><b>Overloading</b> is by parameter count: the same name may be registered at several
 * arities.</li>
 * <li><b>Duplicate discovery</b> — two service-loaded providers contributing the same
 * {@code (name, arity)} is a configuration error and throws {@link IllegalStateException}.</li>
 * <li><b>Programmatic registration</b> replaces any existing entry for the pair (so embedders /
 * tests can override a built-in), and {@link #unregister(String, int)} removes one.</li>
 * </ul>
 *
 * <p>
 * The map is a {@link ConcurrentHashMap}; lookups are lock-free and thread-safe under the cohort
 * fan-out.
 * </p>
 */
public final class FunctionRegistry
{

    private record Key(String name, int arity)
    {
    }

    private static final ConcurrentMap<Key, FunctionDescriptor> REGISTRY = new ConcurrentHashMap<>();

    static
    {
        loadProviders();
    }

    private FunctionRegistry()
    {
    }

    /**
     * Discovers {@link FunctionProvider}s through the project SPI ({@link GenericServiceFactory})
     * instead of {@link java.util.ServiceLoader}, so native-evaluator functions are registered the
     * same way as every other Cumba service. The {@code META-INF/services/} resource format is
     * identical (one fully-qualified class name per line, {@code #} comments ignored).
     */
    private static final class ProviderFactory
            extends GenericServiceFactory<FunctionProvider, Object>
    {

        private ProviderFactory()
        {
            super(FunctionProvider.class);
        }


        @Override
        public List<FunctionProvider> getSuppliers()
        {
            return super.getSuppliers();
        }
    }

    private static void loadProviders()
    {
        for (FunctionProvider provider : new ProviderFactory().getSuppliers())
        {
            for (FunctionDescriptor descriptor : provider.functions())
            {
                Key key = new Key(descriptor.name(), descriptor.arity());
                FunctionDescriptor prev = REGISTRY.putIfAbsent(key, descriptor);
                if (prev != null && !prev.equals(descriptor))
                {
                    throw new IllegalStateException("Duplicate function registration for "
                            + descriptor.name() + "/" + descriptor.arity() + " from provider "
                            + provider.getClass().getName());
                }
            }
        }
    }


    /**
     * Registers (or replaces) a function overload programmatically. Intended for tests and
     * embedding scenarios that contribute functions without an SPI provider.
     */
    public static void register(FunctionDescriptor descriptor)
    {
        REGISTRY.put(new Key(descriptor.name(), descriptor.arity()), descriptor);
    }


    /** Removes a programmatically-registered overload; used by tests to restore isolation. */
    public static void unregister(String name, int arity)
    {
        REGISTRY.remove(new Key(name, arity));
    }


    /** {@code true} iff a function is registered for the exact {@code (name, arity)}. */
    public static boolean isRegistered(String name, int arity)
    {
        return REGISTRY.containsKey(new Key(name, arity));
    }


    /** The descriptor for {@code (name, arity)}, or {@code null} if none is registered. */
    public static @Nullable FunctionDescriptor descriptor(String name, int arity)
    {
        return REGISTRY.get(new Key(name, arity));
    }


    /**
     * Resolves the implementation for {@code (name, arity)}.
     *
     * @throws ExpressionException
     *             if no function is registered for the exact pair
     */
    public static EvalFunction resolve(String name, int arity)
    {
        FunctionDescriptor descriptor = REGISTRY.get(new Key(name, arity));
        if (descriptor == null)
        {
            throw new ExpressionException(
                    "No native function '" + name + "' with " + arity + " argument(s)");
        }
        return descriptor.fn();
    }


    /** A stable, sorted snapshot of every registered descriptor (for docs / diagnostics). */
    public static List<FunctionDescriptor> all()
    {
        List<FunctionDescriptor> out = new ArrayList<>(REGISTRY.values());
        out.sort(Comparator.comparing(FunctionDescriptor::name)
                .thenComparingInt(FunctionDescriptor::arity));
        return out;
    }

}
