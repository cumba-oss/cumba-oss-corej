package net.cumba.cdisc.core.exec;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Tests for {@link FindingKeyMode} — the EC-40 {@code corej.findingKeys} knob. */
class FindingKeyModeTest
{

    @Test
    void parse_acceptsTheCanonicalNamesCaseInsensitively()
    {
        assertEquals(FindingKeyMode.OFF, FindingKeyMode.parse("off"));
        assertEquals(FindingKeyMode.DEFINE, FindingKeyMode.parse("DEFINE"));
        assertEquals(FindingKeyMode.FULL, FindingKeyMode.parse("  Full  "));
    }


    @Test
    void parse_acceptsTheBooleanAliases()
    {
        assertEquals(FindingKeyMode.OFF, FindingKeyMode.parse("false"));
        assertEquals(FindingKeyMode.OFF, FindingKeyMode.parse("none"));
        assertEquals(FindingKeyMode.FULL, FindingKeyMode.parse("true"));
        assertEquals(FindingKeyMode.FULL, FindingKeyMode.parse("all"));
    }


    @Test
    void parse_returnsNullForAbsentBlankOrUnrecognisedValues()
    {
        assertNull(FindingKeyMode.parse(null));
        assertNull(FindingKeyMode.parse(""));
        assertNull(FindingKeyMode.parse("   "));
        assertNull(FindingKeyMode.parse("sometimes"));
    }


    @Test
    void configured_defaultsToOffWhenTheSystemPropertyIsUnset()
    {
        String previous = System.getProperty(FindingKeyMode.PROP);
        System.clearProperty(FindingKeyMode.PROP);
        try
        {
            // The environment variable is not set in the test JVM, so this exercises the
            // final fallback: report enrichment is opt-in (D12).
            assertEquals(FindingKeyMode.OFF, FindingKeyMode.configured());
        }
        finally
        {
            if (previous != null)
            {
                System.setProperty(FindingKeyMode.PROP, previous);
            }
        }
    }


    @Test
    void configured_readsTheSystemProperty()
    {
        String previous = System.getProperty(FindingKeyMode.PROP);
        try
        {
            System.setProperty(FindingKeyMode.PROP, "full");
            assertEquals(FindingKeyMode.FULL, FindingKeyMode.configured());
            System.setProperty(FindingKeyMode.PROP, "define");
            assertEquals(FindingKeyMode.DEFINE, FindingKeyMode.configured());
            // An unrecognised value degrades to OFF rather than failing the run.
            System.setProperty(FindingKeyMode.PROP, "nonsense");
            assertEquals(FindingKeyMode.OFF, FindingKeyMode.configured());
        }
        finally
        {
            if (previous != null)
            {
                System.setProperty(FindingKeyMode.PROP, previous);
            }
            else
            {
                System.clearProperty(FindingKeyMode.PROP);
            }
        }
    }


    @Test
    void isEnabledAndAllowsNatural_gateTheTiers()
    {
        assertFalse(FindingKeyMode.OFF.isEnabled());
        assertTrue(FindingKeyMode.DEFINE.isEnabled());
        assertTrue(FindingKeyMode.FULL.isEnabled());

        assertFalse(FindingKeyMode.OFF.allowsNatural());
        assertFalse(FindingKeyMode.DEFINE.allowsNatural());
        assertTrue(FindingKeyMode.FULL.allowsNatural());
    }

}
