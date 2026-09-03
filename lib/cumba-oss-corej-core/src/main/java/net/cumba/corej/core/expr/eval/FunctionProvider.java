package net.cumba.corej.core.expr.eval;

import java.util.Collection;

/**
 * Service-provider interface for contributing functions to the native evaluator. Providers are
 * discovered via the project SPI ({@code net.cumba.datatable.io.GenericServiceFactory} over
 * {@code META-INF/services/}) from the application classpath (decision #2 — no external plugin jars
 * / sandbox) and registered by {@code (name, arity)} in the {@link FunctionRegistry}. Adding a
 * function for a future rule means shipping a provider — no core engine change.
 */
public interface FunctionProvider
{

    /** The function overloads this provider contributes. */
    Collection<FunctionDescriptor> functions();
}
