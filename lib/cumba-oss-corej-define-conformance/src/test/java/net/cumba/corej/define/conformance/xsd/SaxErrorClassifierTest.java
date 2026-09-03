package net.cumba.corej.define.conformance.xsd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.cumba.corej.define.conformance.report.Category;
import net.cumba.corej.define.conformance.report.ConformanceFinding;
import net.cumba.corej.define.conformance.report.Severity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.xml.sax.SAXParseException;

/**
 * Unit-level mapping table for the SAX→PMDA-id classifier; the message texts are verbatim JDK
 * Xerces output captured against the vendored schemas.
 */
class SaxErrorClassifierTest
{

    private static SaxProblem error(String aMessage)
    {
        return new SaxProblem(SaxProblem.Kind.ERROR, aMessage, 7, 21);
    }


    @ParameterizedTest
    @CsvSource(delimiter = '|', value =
    { //
            "cvc-complex-type.3.2.2: Attribute 'Bogus' is not allowed to appear in element 'ItemDef'.|PMDA-DD0004|WARNING",
            "cvc-complex-type.3.2.1: Element 'ODM' does not have an attribute wildcard.|PMDA-DD0004|WARNING",
            "cvc-complex-type.4: Attribute 'Mandatory' must appear on element 'ItemRef'.|PMDA-DD0003|REJECT",
            "cvc-complex-type.2.4.b: The content of element 'GlobalVariables' is not complete.|PMDA-DD0006|REJECT",
            "cvc-complex-type.2.4.a: Invalid content was found starting with element 'Description'.|PMDA-DD0007|WARNING",
            "cvc-complex-type.2.4.d: Invalid content was found starting with element 'X'.|PMDA-DD0007|WARNING",
            "cvc-elt.1.a: Cannot find the declaration of element 'NotODM'.|PMDA-OD0012|REJECT",
            "cvc-attribute.3: The value 'abc' of attribute 'OrderNumber' on element 'ItemRef' is not valid with respect to its type, 'integer'.|PMDA-OD0013|WARNING",
            "cvc-datatype-valid.1.2.1: 'abc' is not a valid value for 'positiveInteger'.|PMDA-OD0013|WARNING",
            "cvc-attribute.3: The value 'yesterday' of attribute 'CreationDateTime' on element 'ODM' is not valid with respect to its type, 'datetime'.|PMDA-OD0017|WARNING",
            "cvc-datatype-valid.1.2.1: 'yesterday' is not a valid value for 'dateTime'.|PMDA-OD0017|WARNING",
            "cvc-attribute.3: The value 'Nonsense' of attribute 'FileType' on element 'ODM' is not valid with respect to its type, 'FileType'.|PMDA-DD0001|REJECT",
            "cvc-enumeration-valid: Value 'Nonsense' is not facet-valid with respect to enumeration '[Snapshot, Transactional]'.|PMDA-DD0001|REJECT",
            "cvc-attribute.3: mangled message without the quoted type|PMDA-DD0001|REJECT",
            "some completely unrecognised parser error|PMDA-DD0001|REJECT"
    })
    void mapsErrorMessagesOntoPmdaIds(String aMessage, String aRuleId, Severity aSeverity)
    {
        ConformanceFinding finding = SaxErrorClassifier.classify(error(aMessage));
        assertEquals(aRuleId, finding.getRuleId());
        assertEquals(aSeverity, finding.getSeverity());
        assertEquals(Category.XSD, finding.getCategory());
        assertEquals(7, finding.getLine());
        assertEquals(21, finding.getColumn());
        assertTrue(finding.getMessage().endsWith(": " + aMessage), finding.getMessage());
    }


    @Test
    void fatalProblemsMapToOd0001RegardlessOfMessage()
    {
        ConformanceFinding finding = SaxErrorClassifier.classify(new SaxProblem(
                SaxProblem.Kind.FATAL, "The element type \"Study\" must be terminated.", 4, 3));
        assertEquals("PMDA-OD0001", finding.getRuleId());
        assertEquals(Severity.REJECT, finding.getSeverity());
        assertTrue(finding.getMessage().startsWith("XML is not well-formed: "),
                finding.getMessage());
    }


    /** Parser warnings are not schema violations; they stay on the generic id, not DD0001. */
    @Test
    void unmatchedParserWarningsUseTheGenericRuleId()
    {
        ConformanceFinding finding = SaxErrorClassifier
                .classify(new SaxProblem(SaxProblem.Kind.WARNING, "odd parser warning", 1, 1));
        assertEquals(SaxErrorClassifier.GENERIC_RULE_ID, finding.getRuleId());
        assertEquals(Severity.WARNING, finding.getSeverity());
    }


    /**
     * One bad typed value arrives twice (datatype view + attribute view) at the same location; the
     * classifier keeps a single finding and prefers the {@code cvc-attribute.3} message, regardless
     * of report order.
     */
    @Test
    void dedupesDoubleReportedDatatypeViolationsPreferringTheAttributeMessage()
    {
        SaxProblem datatype = error(
                "cvc-datatype-valid.1.2.1: 'abc' is not a valid value for 'integer'.");
        SaxProblem attribute = error("cvc-attribute.3: The value 'abc' of attribute 'OrderNumber'"
                + " on element 'ItemRef' is not valid with respect to its type, 'integer'.");

        for (List<SaxProblem> order : List.of(List.of(datatype, attribute),
                List.of(attribute, datatype)))
        {
            List<ConformanceFinding> findings = SaxErrorClassifier.toFindings(order);
            assertEquals(1, findings.size(), () -> "findings: " + findings);
            assertEquals("PMDA-OD0013", findings.get(0).getRuleId());
            assertTrue(findings.get(0).getMessage().contains("cvc-attribute.3"),
                    findings.get(0).getMessage());
        }
    }


    @Test
    void distinctLocationsAndRulesAreNeverMerged()
    {
        SaxProblem first = error("cvc-complex-type.4: Attribute 'A' must appear on element 'E'.");
        SaxProblem second = new SaxProblem(SaxProblem.Kind.ERROR,
                "cvc-complex-type.4: Attribute 'B' must appear on element 'E'.", 8, 4);
        SaxProblem third = error(
                "cvc-complex-type.3.2.2: Attribute 'C' is not allowed to appear in element 'E'.");
        assertEquals(3, SaxErrorClassifier.toFindings(List.of(first, second, third)).size());
    }


    @Test
    void saxProblemCapturesLocationAndToleratesNullMessage()
    {
        SaxProblem problem = SaxProblem.of(SaxProblem.Kind.ERROR,
                new SAXParseException(null, null, null, 12, 34));
        assertEquals("", problem.message());
        assertEquals(12, problem.line());
        assertEquals(34, problem.column());
    }

}
