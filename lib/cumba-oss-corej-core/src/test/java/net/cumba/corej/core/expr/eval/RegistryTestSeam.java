package net.cumba.corej.core.expr.eval;

/**
 * The public face of {@link FunctionRegistry}'s package-private test seam ({@code register} /
 * {@code unregister}) for tests <b>outside</b> {@code expr.eval} ({@code PLAN-binding-expressions}
 * §0.2 d): the wave-0 runner and loader tests plant a probe function — a per-row list producer, a
 * provider-capable function, an invocation counter — and must remove it again. Test source only; a
 * test-seam function never reaches the corpus's classpath.
 */
public final class RegistryTestSeam
{

    private RegistryTestSeam()
    {
    }


    /**
     * Registers {@code descriptor} until the returned handle is closed.
     *
     * @param descriptor
     *            the probe function
     * @return a handle whose {@code close()} unregisters it
     */
    public static Registration register(FunctionDescriptor descriptor)
    {
        FunctionRegistry.register(descriptor);
        return () -> FunctionRegistry.unregister(descriptor.name());
    }

    /** An {@link AutoCloseable} that does not throw — the registration handle. */
    @FunctionalInterface
    public interface Registration extends AutoCloseable
    {

        @Override
        void close();
    }

}
