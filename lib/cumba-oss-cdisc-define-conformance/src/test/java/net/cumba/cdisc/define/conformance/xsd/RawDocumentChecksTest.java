package net.cumba.cdisc.define.conformance.xsd;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import net.cumba.cdisc.define.DefineDomIo;
import net.cumba.cdisc.define.DefineXmlConverter;
import net.cumba.cdisc.define.conformance.report.Category;
import net.cumba.cdisc.define.conformance.report.ConformanceFinding;
import net.cumba.cdisc.define.conformance.report.Severity;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

class RawDocumentChecksTest
{

    private static final String DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n";

    private static byte[] utf8(String aText)
    {
        return aText.getBytes(StandardCharsets.UTF_8);
    }


    private static byte[] concat(byte[] aPrefix, byte[] aRest)
    {
        byte[] joined = new byte[aPrefix.length + aRest.length];
        System.arraycopy(aPrefix, 0, joined, 0, aPrefix.length);
        System.arraycopy(aRest, 0, joined, aPrefix.length, aRest.length);
        return joined;
    }


    private static Document parse(String aXml)
    {
        try
        {
            return DefineDomIo.parse(new ByteArrayInputStream(utf8(aXml)));
        }
        catch (Exception e)
        {
            throw new IllegalStateException(e);
        }
    }


    @Test
    void xmlDeclarationAcceptedPlainAndWithUtf8Bom()
    {
        byte[] plain = utf8(DECL + "<ODM/>");
        assertTrue(RawDocumentChecks.xmlDeclaration(plain).isEmpty());

        byte[] bom =
        {
                (byte) 0xEF, (byte) 0xBB, (byte) 0xBF
        };
        assertTrue(RawDocumentChecks.xmlDeclaration(concat(bom, plain)).isEmpty());
    }


    @Test
    void xmlDeclarationAcceptedInUtf16WithAndWithoutBom()
    {
        // The JDK prepends the BOM itself for UTF-16 big-endian.
        assertTrue(RawDocumentChecks
                .xmlDeclaration((DECL + "<ODM/>").getBytes(StandardCharsets.UTF_16)).isEmpty());
        assertTrue(RawDocumentChecks
                .xmlDeclaration((DECL + "<ODM/>").getBytes(StandardCharsets.UTF_16LE)).isEmpty());
        assertTrue(RawDocumentChecks
                .xmlDeclaration((DECL + "<ODM/>").getBytes(StandardCharsets.UTF_16BE)).isEmpty());
    }


    @Test
    void encodingAcceptsThePermittedSetAndTolerantForms()
    {
        assertTrue(RawDocumentChecks.xmlEncoding(utf8(DECL + "<ODM/>")).isEmpty());
        assertTrue(RawDocumentChecks
                .xmlEncoding(utf8("<?xml version=\"1.0\" encoding=\"iso-8859-1\"?>\n<ODM/>"))
                .isEmpty(), "case-insensitive");
        assertTrue(RawDocumentChecks
                .xmlEncoding(utf8("<?xml version=\"1.0\" encoding='UTF-16'?>\n<ODM/>")).isEmpty(),
                "single quotes");
        // No declaration, or a declaration without encoding=, is not this rule's finding.
        assertTrue(RawDocumentChecks.xmlEncoding(utf8("<ODM/>")).isEmpty());
        assertTrue(
                RawDocumentChecks.xmlEncoding(utf8("<?xml version=\"1.0\"?>\n<ODM/>")).isEmpty());
    }


    @Test
    void invalidEncodingYieldsOd0011Warning()
    {
        ConformanceFinding finding = RawDocumentChecks
                .xmlEncoding(utf8("<?xml version=\"1.0\" encoding=\"Shift_JIS\"?>\n<ODM/>"))
                .orElseThrow();
        assertEquals("PMDA-OD0011", finding.getRuleId());
        assertEquals(Severity.WARNING, finding.getSeverity());
        assertEquals(Category.PMDA, finding.getCategory());
        assertTrue(finding.getMessage().contains("Shift_JIS"), finding.getMessage());
    }


    @Test
    void missingDeclarationYieldsOd0010Reject()
    {
        ConformanceFinding finding = RawDocumentChecks.xmlDeclaration(utf8("<ODM/>")).orElseThrow();
        assertEquals("PMDA-OD0010", finding.getRuleId());
        assertEquals(Category.PMDA, finding.getCategory());
        assertEquals(Severity.REJECT, finding.getSeverity());
    }


    /** The declaration must be the very first content — leading whitespace is not tolerated. */
    @Test
    void leadingWhitespaceOrEmptyFileFailsTheDeclarationCheck()
    {
        assertFalse(RawDocumentChecks.startsWithXmlDeclaration(utf8("\n" + DECL + "<ODM/>")));
        assertFalse(RawDocumentChecks.startsWithXmlDeclaration(new byte[0]));
        assertFalse(RawDocumentChecks.startsWithXmlDeclaration(utf8("<?x")));
    }


    @Test
    void namespaceDeclarationsCleanWhenBothNamespacesPresent()
    {
        Document doc = parse(DECL + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\""
                + " xmlns:def=\"http://www.cdisc.org/ns/def/v2.1\"/>");
        assertEquals(List.of(),
                RawDocumentChecks.namespaceDeclarations(doc, DefineXmlConverter.Version.V2_1));
    }


    @Test
    void missingBothNamespacesYieldsOneFindingEach()
    {
        Document doc = parse(DECL + "<ODM/>");
        List<ConformanceFinding> findings = RawDocumentChecks.namespaceDeclarations(doc,
                DefineXmlConverter.Version.V2_1);
        assertEquals(2, findings.size(), () -> "findings: " + findings);
        for (ConformanceFinding finding : findings)
        {
            assertEquals("PMDA-DD0002", finding.getRuleId());
            assertEquals(Category.PMDA, finding.getCategory());
            assertEquals(Severity.REJECT, finding.getSeverity());
            assertEquals("ODM", finding.getElement());
        }
        assertTrue(findings.get(0).getMessage().contains("http://www.cdisc.org/ns/odm/v1.3"),
                findings.get(0).getMessage());
        assertTrue(findings.get(1).getMessage().contains("http://www.cdisc.org/ns/def/v2.1"),
                findings.get(1).getMessage());
    }


    /** A 2.0 document must declare the 2.0 {@code def} namespace, not the 2.1 one. */
    @Test
    void version20RequiresTheV20DefNamespace()
    {
        Document doc = parse(DECL + "<ODM xmlns=\"http://www.cdisc.org/ns/odm/v1.3\""
                + " xmlns:def=\"http://www.cdisc.org/ns/def/v2.1\"/>");
        List<ConformanceFinding> findings = RawDocumentChecks.namespaceDeclarations(doc,
                DefineXmlConverter.Version.V2_0);
        assertEquals(1, findings.size(), () -> "findings: " + findings);
        assertTrue(findings.get(0).getMessage().contains("http://www.cdisc.org/ns/def/v2.0"),
                findings.get(0).getMessage());
    }


    @Test
    void version10IsRejected()
    {
        Document doc = parse(DECL + "<ODM/>");
        assertThrows(IllegalArgumentException.class, () -> RawDocumentChecks
                .namespaceDeclarations(doc, DefineXmlConverter.Version.V1_0));
    }

}
