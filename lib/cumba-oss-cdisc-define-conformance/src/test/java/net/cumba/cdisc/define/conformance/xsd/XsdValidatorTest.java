package net.cumba.cdisc.define.conformance.xsd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import net.cumba.cdisc.define.DefineXmlConverter;
import org.junit.jupiter.api.Test;

class XsdValidatorTest
{

    private static byte[] fixture(String aName)
    {
        try (InputStream in = XsdValidatorTest.class.getResourceAsStream("/fixtures/" + aName))
        {
            return Objects.requireNonNull(in, aName).readAllBytes();
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot load fixture " + aName, e);
        }
    }


    @Test
    void schemaCompilesAndCleanDocumentsProduceNoProblems()
    {
        assertEquals(List.of(), XsdValidator.validate(fixture("xsd-clean-21.xml"),
                DefineXmlConverter.Version.V2_1));
        assertEquals(List.of(), XsdValidator.validate(fixture("xsd-clean-20.xml"),
                DefineXmlConverter.Version.V2_0));
    }


    @Test
    void problemsCarryKindMessageAndLocation()
    {
        List<SaxProblem> problems = XsdValidator.validate(
                fixture("xsd-missing-required-attribute-21.xml"), DefineXmlConverter.Version.V2_1);
        assertEquals(1, problems.size(), () -> "problems: " + problems);
        SaxProblem problem = problems.get(0);
        assertEquals(SaxProblem.Kind.ERROR, problem.kind());
        assertTrue(problem.message().startsWith("cvc-complex-type.4:"), problem.message());
        assertEquals(26, problem.line());
        assertTrue(problem.column() > 0);
    }


    /** A fatal aborts the parse; everything collected up to and including it is returned. */
    @Test
    void fatalProblemsAreCollectedNotThrown()
    {
        List<SaxProblem> problems = XsdValidator.validate(fixture("xsd-not-well-formed-21.xml"),
                DefineXmlConverter.Version.V2_1);
        assertEquals(1, problems.size(), () -> "problems: " + problems);
        assertEquals(SaxProblem.Kind.FATAL, problems.get(0).kind());
    }


    /** A 2.1 document validated against the 2.0 schema fails — the version gate matters. */
    @Test
    void versionMismatchIsVisible()
    {
        List<SaxProblem> problems = XsdValidator.validate(fixture("xsd-clean-21.xml"),
                DefineXmlConverter.Version.V2_0);
        assertTrue(problems.stream().anyMatch(problem -> problem.kind() == SaxProblem.Kind.ERROR),
                () -> "problems: " + problems);
    }


    @Test
    void version10HasNoVendoredSchema()
    {
        assertThrows(IllegalArgumentException.class, () -> XsdValidator
                .validate(fixture("xsd-clean-21.xml"), DefineXmlConverter.Version.V1_0));
    }

}
