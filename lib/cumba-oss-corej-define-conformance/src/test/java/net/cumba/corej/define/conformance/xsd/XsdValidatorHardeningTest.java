package net.cumba.corej.define.conformance.xsd;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Locale;
import net.cumba.cdisc.define.DefineXmlConverter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * How the vendored schema package is <b>loaded and hardened</b>, as opposed to what it reports.
 *
 * <p>
 * ⚠ Every test here starts by clearing {@link XsdValidator}'s compiled-schema cache. That cache is
 * a JVM-lifetime memo, so without the reset {@code loadSchema}, its
 * {@code ClasspathResourceResolver} and the {@code LSInput} it hands back run exactly once per JVM
 * — after which any assertion about them is vacuous. This is the same shape as {@code corej-core}'s
 * compiled-program cache: the memo is right in production and blinds the measurement in a test.
 * </p>
 */
class XsdValidatorHardeningTest
{

    /**
     * A define.xml whose DOCTYPE points at an external DTD. A validator that has not had
     * {@code ACCESS_EXTERNAL_DTD} disabled would try to fetch it.
     */
    private static final String EXTERNAL_DTD = """
            <?xml version="1.0" encoding="UTF-8"?>
            <!DOCTYPE ODM SYSTEM "http://example.invalid/evil.dtd">
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"/>
            """;

    @BeforeEach
    void dropTheSchemaCache()
    {
        XsdValidator.resetSchemaCache();
    }


    /**
     * The vendored package compiles from the classpath alone — no network, no filesystem. Removing
     * the resource resolver, or serving its imports from anything but the vendored {@code /xsd/}
     * tree, makes this fail rather than silently reaching outside the jar.
     */
    @Test
    void bothVendoredSchemaPackagesCompileFromTheClasspathAfterACacheDrop()
    {
        assertEquals(List.of(), XsdValidator.validate(clean21(), DefineXmlConverter.Version.V2_1));
        XsdValidator.resetSchemaCache();
        assertEquals(List.of(), XsdValidator.validate(clean20(), DefineXmlConverter.Version.V2_0));
    }


    /** The 2.0 and 2.1 packages are genuinely different schemas, not one entry served twice. */
    @Test
    void theTwoVersionsResolveToDifferentSchemas()
    {
        assertFalse(XsdValidator.schemaFor(DefineXmlConverter.Version.V2_0)
                .equals(XsdValidator.schemaFor(DefineXmlConverter.Version.V2_1)));
        // A 2.0 document is rejected by the 2.1 package, which is what makes them distinguishable.
        assertTrue(XsdValidator.validate(clean20(), DefineXmlConverter.Version.V2_1).stream()
                .anyMatch(p -> p.kind() == SaxProblem.Kind.ERROR));
    }


    /** The cache really is a cache: the same Schema instance comes back for the same version. */
    @Test
    void schemaForMemoisesPerVersionAndTheResetSeamDefeatsIt()
    {
        var first = XsdValidator.schemaFor(DefineXmlConverter.Version.V2_1);
        assertTrue(first == XsdValidator.schemaFor(DefineXmlConverter.Version.V2_1));
        XsdValidator.resetSchemaCache();
        assertFalse(first == XsdValidator.schemaFor(DefineXmlConverter.Version.V2_1));
    }


    /**
     * ⭐ The XXE guard. A document declaring an external DTD must be reported as an access
     * restriction, never fetched. Without {@code ACCESS_EXTERNAL_DTD} the parser would attempt the
     * network retrieval — a validator that phones out on a sponsor's submission document.
     */
    @Test
    void anExternalDtdIsRefusedRatherThanFetched()
    {
        List<SaxProblem> problems = XsdValidator.validate(EXTERNAL_DTD.getBytes(UTF_8),
                DefineXmlConverter.Version.V2_1);
        assertTrue(
                problems.stream().anyMatch(
                        p -> p.message().toLowerCase(Locale.ROOT).contains("accessexternaldtd")),
                () -> "expected an accessExternalDTD restriction, got: " + problems);
    }


    private static byte[] clean21()
    {
        return fixture("xsd-clean-21.xml");
    }


    private static byte[] clean20()
    {
        return fixture("xsd-clean-20.xml");
    }


    private static byte[] fixture(String aName)
    {
        try (var in = XsdValidatorHardeningTest.class.getResourceAsStream("/fixtures/" + aName))
        {
            return java.util.Objects.requireNonNull(in, aName).readAllBytes();
        }
        catch (java.io.IOException e)
        {
            throw new java.io.UncheckedIOException("cannot load fixture " + aName, e);
        }
    }

}
