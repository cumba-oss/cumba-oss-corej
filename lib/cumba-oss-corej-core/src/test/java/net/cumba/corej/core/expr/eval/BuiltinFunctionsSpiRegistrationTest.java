package net.cumba.corej.core.expr.eval;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.core.expr.eval.spi.BuiltinFunctions;
import org.junit.jupiter.api.Test;

/**
 * Guards the SPI registration in
 * {@code META-INF/services/net.cumba.corej.core.expr.eval.FunctionProvider}.
 *
 * <p>
 * A stale fully-qualified class name in that services file is invisible to the compiler and to
 * every other test in this module: {@link FunctionRegistry} would simply find no provider, every
 * builtin function would be absent at runtime, and the build would stay green. This test is the
 * only thing that fails in that case.
 * </p>
 *
 * <p>
 * ⚠ The failure this catches is not a crash. A rule whose Check calls a function the registry
 * cannot resolve does not fail loudly — it fails to compile its expression and the rule is reported
 * as an execution error, one rule at a time, across a whole corpus run.
 * </p>
 */
class BuiltinFunctionsSpiRegistrationTest
{

    @Test
    void builtinFunctionsAreDiscoverableThroughTheSpi()
    {
        List<FunctionDescriptor> declared = new BuiltinFunctions().functions();
        assertFalse(declared.isEmpty(), "BuiltinFunctions must declare at least one function, "
                + "otherwise this guard would pass vacuously");

        for (FunctionDescriptor fd : declared)
        {
            assertTrue(FunctionRegistry.isRegistered(fd.name()),
                    () -> "BuiltinFunctions must be registered in META-INF/services — " + fd.name()
                            + " was not discovered through the SPI");
        }
    }
}
