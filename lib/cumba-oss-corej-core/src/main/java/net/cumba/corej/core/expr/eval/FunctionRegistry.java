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
 * The {@code name -> FunctionDescriptor} registry. Built-in providers are discovered via the
 * project SPI ({@link GenericServiceFactory} over {@code META-INF/services/}) on class
 * initialisation; additional functions may be registered programmatically (for tests / embedding).
 *
 * <h2>Policies</h2>
 * <ul>
 * <li><b>Lookup</b> is by exact name. An unknown name raises an {@link ExpressionException} at
 * <i>compile</i> time (fail loudly, never silent).</li>
 * <li>⛔ <b>One descriptor per name</b> (phase 6b, D16/D17/D19a): the former {@code (name, arity)}
 * overload key is retired — what used to be an arity overload is an optional {@link Parameter}, and
 * {@link ArgumentBinder} binds arguments <em>before</em> resolution. Two service-loaded providers
 * contributing the same name is a configuration error and throws
 * {@link IllegalStateException}.</li>
 * <li><b>Programmatic registration</b> replaces any existing entry for the name (so embedders /
 * tests can override a built-in), and {@link #unregister(String)} removes one.</li>
 * </ul>
 *
 * <p>
 * The map is a {@link ConcurrentHashMap}; lookups are lock-free and thread-safe under the rule
 * fan-out.
 * </p>
 */
public final class FunctionRegistry
{

    private static final ConcurrentMap<String, FunctionDescriptor> REGISTRY = new ConcurrentHashMap<>();

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
                FunctionDescriptor prev = REGISTRY.putIfAbsent(descriptor.name(), descriptor);
                if (prev != null && !prev.equals(descriptor))
                {
                    throw new IllegalStateException(
                            "Duplicate function registration for " + descriptor.name()
                                    + " from provider " + provider.getClass().getName());
                }
            }
        }
    }


    /**
     * Registers (or replaces) a function descriptor programmatically. Intended for tests and
     * embedding scenarios that contribute functions without an SPI provider.
     */
    public static void register(FunctionDescriptor descriptor)
    {
        REGISTRY.put(descriptor.name(), descriptor);
    }


    /** Removes a programmatically-registered descriptor; used by tests to restore isolation. */
    public static void unregister(String name)
    {
        REGISTRY.remove(name);
    }


    /** {@code true} iff a function is registered under the name. */
    public static boolean isRegistered(String name)
    {
        return REGISTRY.containsKey(name);
    }


    /** The descriptor for {@code name}, or {@code null} if none is registered. */
    public static @Nullable FunctionDescriptor descriptor(String name)
    {
        return REGISTRY.get(name);
    }


    /**
     * The descriptor for {@code name} when a call carrying {@code positionalCount} positional
     * arguments can bind against it — the arity-shaped probe the pre-6b {@code (name, arity)}
     * lookup used to answer. {@code null} when the name is unknown <em>or</em> the count <b>exceeds
     * {@code maxArity}</b>.
     *
     * <p>
     * ⚠ Round 3: this said "falls outside {@code [minArity, maxArity]}". The body tests the upper
     * bound only — an <em>under</em>-filled call keeps its descriptor here and is rejected by
     * {@link ArgumentBinder}, the component that can also see the kwargs. No reaching caller
     * depends on the lower bound being tested here, so the <b>doc</b> was corrected to the body
     * rather than the body to the doc; tightening it is a behaviour change and would need its own
     * decision.
     * </p>
     */
    public static @Nullable FunctionDescriptor descriptorAccepting(String name, int positionalCount)
    {
        FunctionDescriptor d = REGISTRY.get(name);
        if (d == null || positionalCount > d.maxArity())
        {
            return null;
        }
        return d;
    }


    /**
     * Resolves the implementation for {@code name}.
     *
     * @throws ExpressionException
     *             if no function is registered for the name
     */
    public static EvalFunction resolve(String name)
    {
        FunctionDescriptor descriptor = REGISTRY.get(name);
        if (descriptor == null || descriptor.fn() == null)
        {
            throw new ExpressionException("No native function '" + name + "'");
        }
        return descriptor.fn();
    }


    /** All registered descriptors, ordered by name (for diagnostics / docs). */
    public static List<FunctionDescriptor> all()
    {
        List<FunctionDescriptor> list = new ArrayList<>(REGISTRY.values());
        list.sort(Comparator.comparing(FunctionDescriptor::name));
        return list;
    }
}
