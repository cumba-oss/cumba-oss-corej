package net.cumba.cdisc.define.conformance.xsd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.List;
import java.util.Objects;
import net.cumba.cdisc.define.DefineXmlConverter;
import net.cumba.cdisc.define.conformance.report.Category;
import net.cumba.cdisc.define.conformance.report.ConformanceFinding;
import net.cumba.cdisc.define.conformance.report.Severity;
import org.junit.jupiter.api.Test;

/**
 * End-to-end pre-pass over the {@code xsd-*} fixtures: every expectation below was pinned
 * empirically against the vendored schemas (JDK Xerces) before the fixtures were committed, so a
 * failing assertion means the classifier or the schema packaging drifted — not the fixture.
 */
class DefinePrePassTest
{

    private static DefinePrePass.Result run(String aFixture)
    {
        try (InputStream in = DefinePrePassTest.class.getResourceAsStream("/fixtures/" + aFixture))
        {
            return DefinePrePass.run(Objects.requireNonNull(in, aFixture).readAllBytes());
        }
        catch (IOException e)
        {
            throw new UncheckedIOException("cannot load fixture " + aFixture, e);
        }
    }


    private static ConformanceFinding sole(DefinePrePass.Result aResult)
    {
        assertEquals(1, aResult.findings().size(), () -> "findings: " + aResult.findings());
        return aResult.findings().get(0);
    }


    @Test
    void cleanDocumentHasNoFindings()
    {
        DefinePrePass.Result result = run("xsd-clean-21.xml");
        assertEquals(DefineXmlConverter.Version.V2_1, result.version());
        assertEquals(List.of(), result.findings());
    }


    @Test
    void clean20DocumentValidatesAgainst20Schema()
    {
        DefinePrePass.Result result = run("xsd-clean-20.xml");
        assertEquals(DefineXmlConverter.Version.V2_0, result.version());
        assertEquals(List.of(), result.findings());
    }


    /**
     * The repo's pilot fixture carries {@code def:Class} as an ItemGroupDef <em>attribute</em> (the
     * 2.0 style); Define-XML 2.1 makes it a child element, so the vendored 2.1 schema flags exactly
     * that one attribute. Documented expectation, not a fixture defect to fix here.
     */
    @Test
    void pilotCleanFixtureHasTheKnownDefClassSchemaFinding()
    {
        DefinePrePass.Result result = run("pilot-clean-21.xml");
        assertEquals(DefineXmlConverter.Version.V2_1, result.version());
        ConformanceFinding finding = sole(result);
        assertEquals("PMDA-DD0004", finding.getRuleId());
        assertTrue(finding.getMessage().contains("def:Class"), finding.getMessage());
    }


    @Test
    void missingRequiredAttributeMapsToDd0003()
    {
        ConformanceFinding finding = sole(run("xsd-missing-required-attribute-21.xml"));
        assertEquals("PMDA-DD0003", finding.getRuleId());
        assertEquals(Category.XSD, finding.getCategory());
        assertEquals(Severity.REJECT, finding.getSeverity());
        assertEquals(26, finding.getLine());
        assertNotNull(finding.getColumn());
        assertNull(finding.getElement());
        assertTrue(finding.getMessage().contains("'Mandatory' must appear on element 'ItemRef'"),
                finding.getMessage());
    }


    @Test
    void undeclaredAttributeMapsToDd0004()
    {
        ConformanceFinding finding = sole(run("xsd-undeclared-attribute-21.xml"));
        assertEquals("PMDA-DD0004", finding.getRuleId());
        assertEquals(Category.XSD, finding.getCategory());
        assertEquals(Severity.WARNING, finding.getSeverity());
        assertNotNull(finding.getLine());
        assertTrue(finding.getMessage().contains("cvc-complex-type.3.2"), finding.getMessage());
    }


    /**
     * Xerces reports mis-ordered and unknown elements through the identical
     * {@code cvc-complex-type.2.4.a} message, so wrong order maps to DD0007 (whose sheet MESSAGE is
     * that Xerces text verbatim); DD0008 is not distinguishable from the SAX stream.
     */
    @Test
    void wrongElementOrderMapsToDd0007()
    {
        ConformanceFinding finding = sole(run("xsd-wrong-element-order-21.xml"));
        assertEquals("PMDA-DD0007", finding.getRuleId());
        assertEquals(Severity.WARNING, finding.getSeverity());
        assertTrue(finding.getMessage().contains("Invalid content was found starting with element"),
                finding.getMessage());
    }


    @Test
    void incompleteElementContentMapsToDd0006()
    {
        ConformanceFinding finding = sole(run("xsd-incomplete-content-21.xml"));
        assertEquals("PMDA-DD0006", finding.getRuleId());
        assertEquals(Severity.REJECT, finding.getSeverity());
        assertTrue(finding.getMessage().contains("'GlobalVariables' is not complete"),
                finding.getMessage());
    }


    @Test
    void missingXmlDeclarationMapsToOd0010()
    {
        ConformanceFinding finding = sole(run("xsd-no-xml-declaration-21.xml"));
        assertEquals("PMDA-OD0010", finding.getRuleId());
        assertEquals(Category.PMDA, finding.getCategory());
        assertEquals(Severity.REJECT, finding.getSeverity());
        assertNull(finding.getLine());
    }


    @Test
    void missingDefNamespaceReportsDd0002AlongsideSchemaFindings()
    {
        DefinePrePass.Result result = run("xsd-missing-def-namespace-21.xml");
        assertEquals(DefineXmlConverter.Version.V2_1, result.version());
        List<ConformanceFinding> dd0002 = result.findings().stream()
                .filter(finding -> finding.getRuleId().equals("PMDA-DD0002")).toList();
        assertEquals(1, dd0002.size(), () -> "findings: " + result.findings());
        assertEquals(Category.PMDA, dd0002.get(0).getCategory());
        assertEquals(Severity.REJECT, dd0002.get(0).getSeverity());
        assertEquals("ODM", dd0002.get(0).getElement());
        assertTrue(dd0002.get(0).getMessage().contains("http://www.cdisc.org/ns/def/v2.1"),
                dd0002.get(0).getMessage());
        // pinned schema noise on this fixture: def:Context + def:DefineVersion missing (DD0003
        // twice) and the unprefixed DefineVersion not allowed (DD0004)
        assertEquals(2, result.findings().stream()
                .filter(finding -> finding.getRuleId().equals("PMDA-DD0003")).count());
        assertEquals(1, result.findings().stream()
                .filter(finding -> finding.getRuleId().equals("PMDA-DD0004")).count());
        assertEquals(4, result.findings().size());
    }


    @Test
    void notWellFormedDocumentMapsToOd0001AndHasNoVersion()
    {
        DefinePrePass.Result result = run("xsd-not-well-formed-21.xml");
        assertNull(result.version());
        ConformanceFinding finding = sole(result);
        assertEquals("PMDA-OD0001", finding.getRuleId());
        assertEquals(Category.XSD, finding.getCategory());
        assertEquals(Severity.REJECT, finding.getSeverity());
        assertEquals(10, finding.getLine());
        assertTrue(finding.getMessage().contains("must be terminated by the matching end-tag"),
                finding.getMessage());
    }


    @Test
    void wrongRootElementMapsToOd0012()
    {
        ConformanceFinding finding = sole(run("xsd-wrong-root-21.xml"));
        assertEquals("PMDA-OD0012", finding.getRuleId());
        assertEquals(Severity.REJECT, finding.getSeverity());
        assertTrue(finding.getMessage().contains("'NotODM'"), finding.getMessage());
    }


    /** Xerces double-reports one bad value; the classifier dedupes to a single finding. */
    @Test
    void invalidIntegerValueMapsToDeduplicatedOd0013()
    {
        ConformanceFinding finding = sole(run("xsd-invalid-integer-21.xml"));
        assertEquals("PMDA-OD0013", finding.getRuleId());
        assertEquals(Severity.WARNING, finding.getSeverity());
        assertEquals(26, finding.getLine());
        assertTrue(finding.getMessage().contains("attribute 'OrderNumber'"), finding.getMessage());
    }


    @Test
    void invalidDatetimeValueMapsToDeduplicatedOd0017()
    {
        ConformanceFinding finding = sole(run("xsd-invalid-datetime-21.xml"));
        assertEquals("PMDA-OD0017", finding.getRuleId());
        assertEquals(Severity.WARNING, finding.getSeverity());
        assertTrue(finding.getMessage().contains("attribute 'CreationDateTime'"),
                finding.getMessage());
    }


    /** An enum violation matches no mapped key and lands on the sheet's DD0001 catch-all. */
    @Test
    void unmappedSchemaErrorFallsBackToDd0001CatchAll()
    {
        ConformanceFinding finding = sole(run("xsd-invalid-enum-value-21.xml"));
        assertEquals("PMDA-DD0001", finding.getRuleId());
        assertEquals(Category.XSD, finding.getCategory());
        assertEquals(Severity.REJECT, finding.getSeverity());
        assertTrue(finding.getMessage().contains("attribute 'FileType'"), finding.getMessage());
    }

}
