package net.cumba.corej.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for {@link EngineLimits}. The environment variable layer cannot be set from a test, so
 * these focus on the system-property layer, the default, and the {@code <= 0 ⇒ unlimited} rule —
 * which also exercises the env fall-through (env is absent in the test JVM).
 */
class EngineLimitsTest
{

    private String saved;

    @BeforeEach
    void capture()
    {
        saved = System.getProperty(EngineLimits.PROP);
        System.clearProperty(EngineLimits.PROP);
    }


    @AfterEach
    void restore()
    {
        if (saved == null)
        {
            System.clearProperty(EngineLimits.PROP);
        }
        else
        {
            System.setProperty(EngineLimits.PROP, saved);
        }
    }


    @Test
    void defaultIs1000_whenNothingConfigured()
    {
        assertEquals(1000, EngineLimits.maxErrorsPerRule());
        assertEquals(EngineLimits.DEFAULT_MAX_ERRORS_PER_RULE, EngineLimits.maxErrorsPerRule());
    }


    @Test
    void systemPropertyOverridesDefault()
    {
        System.setProperty(EngineLimits.PROP, "25");
        assertEquals(25, EngineLimits.maxErrorsPerRule());
    }


    @Test
    void zeroOrNegativeMeansUnlimited()
    {
        System.setProperty(EngineLimits.PROP, "0");
        assertEquals(Integer.MAX_VALUE, EngineLimits.maxErrorsPerRule());

        System.setProperty(EngineLimits.PROP, "-5");
        assertEquals(Integer.MAX_VALUE, EngineLimits.maxErrorsPerRule());
    }


    @Test
    void unparseablePropertyFallsBackToDefault()
    {
        System.setProperty(EngineLimits.PROP, "not-a-number");
        assertEquals(1000, EngineLimits.maxErrorsPerRule());
    }


    @Test
    void resolve_nullOverride_usesGlobal()
    {
        System.setProperty(EngineLimits.PROP, "7");
        assertEquals(7, EngineLimits.resolve(null));
    }


    @Test
    void resolve_explicitOverride_winsOverGlobal()
    {
        System.setProperty(EngineLimits.PROP, "7");
        assertEquals(42, EngineLimits.resolve(42));
    }


    @Test
    void resolve_nonPositiveOverride_isUnlimited()
    {
        assertEquals(Integer.MAX_VALUE, EngineLimits.resolve(0));
        assertEquals(Integer.MAX_VALUE, EngineLimits.resolve(-1));
    }

}
