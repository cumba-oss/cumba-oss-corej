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
 * initialisation; the tests plant probe functions through a package-private seam.
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
 * <li><b>Programmatic registration</b> (the package-private test seam) replaces any existing entry
 * for the name, and {@link #unregister(String)} removes one.</li>
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
     * Registers (or replaces) a function descriptor programmatically — a <b>test seam</b>, used to
     * plant probe functions (dispatch-drift and kwarg probes). ⚑ It was a public "embedder"
     * extension point until 2026-09-25; no embedder existed anywhere in the stack, so the public
     * surface was retired ({@code PLAN-retire-dead-multi-match-lookup} K3) and the seam kept
     * package-private for the tests that depend on it. Production functions arrive only through the
     * {@link FunctionProvider} SPI.
     */
    static void register(FunctionDescriptor descriptor)
    {
        REGISTRY.put(descriptor.name(), descriptor);
    }


    /** Removes a programmatically-registered descriptor; a test seam restoring isolation (K3). */
    static void unregister(String name)
    {
        REGISTRY.remove(name);
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


    /** All registered descriptors, ordered by name (for diagnostics / docs). */
    public static List<FunctionDescriptor> all()
    {
        List<FunctionDescriptor> list = new ArrayList<>(REGISTRY.values());
        list.sort(Comparator.comparing(FunctionDescriptor::name));
        return list;
    }
}
