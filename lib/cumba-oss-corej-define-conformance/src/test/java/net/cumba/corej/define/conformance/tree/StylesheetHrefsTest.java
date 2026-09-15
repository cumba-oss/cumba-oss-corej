package net.cumba.corej.define.conformance.tree;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.ByteArrayInputStream;
import java.util.List;
import net.cumba.cdisc.define.DefineDomIo;
import org.junit.jupiter.api.Test;

/**
 * {@link ElementNodeBuilder#stylesheetHrefs} — the one piece of a define.xml the
 * {@link ElementNode} tree cannot carry, because {@code <?xml-stylesheet?>} is a processing
 * instruction and not an element. It is the sole input of the {@code stylesheet_file_exists} kind
 * (PMDA DD0085, "Missing Define XSL"), so an href this method fails to see is a stylesheet the
 * validator silently stops checking for.
 */
class StylesheetHrefsTest
{

    private static List<String> hrefs(String aXml)
    {
        try (var in = new ByteArrayInputStream(aXml.getBytes(UTF_8)))
        {
            return ElementNodeBuilder.stylesheetHrefs(DefineDomIo.parse(in));
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot parse test XML", e);
        }
    }


    @Test
    void prologStylesheetHrefsAreReturnedInDocumentOrder()
    {
        assertEquals(List.of("define2-1.xsl", "print.xsl"), hrefs("""
                <?xml version="1.0" encoding="UTF-8"?>
                <?xml-stylesheet type="text/xsl" href="define2-1.xsl"?>
                <?xml-stylesheet href='print.xsl' media="print"?>
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"/>
                """));
    }


    @Test
    void nonStylesheetAndHrefLessProcessingInstructionsAreIgnored()
    {
        assertEquals(List.of("real.xsl"), hrefs("""
                <?xml version="1.0" encoding="UTF-8"?>
                <?some-other-pi href="not-a-stylesheet.xsl"?>
                <?xml-stylesheet type="text/xsl"?>
                <?xml-stylesheet type="text/xsl" href="real.xsl"?>
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"/>
                """));
    }


    /**
     * The href pseudo-attribute must be matched on a word boundary: {@code xhref} is a different
     * pseudo-attribute and a stylesheet reference read out of it would be a fabricated finding.
     */
    @Test
    void anAttributeMerelyEndingInHrefIsNotAStylesheetReference()
    {
        assertEquals(List.of(), hrefs("""
                <?xml version="1.0" encoding="UTF-8"?>
                <?xml-stylesheet type="text/xsl" xhref="decoy.xsl"?>
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"/>
                """));
    }


    /**
     * Only PROLOG processing instructions are stylesheet links (W3C xml-stylesheet). A PI after the
     * root element has started is not one, and the scan stops at the root rather than continuing
     * into the document body.
     */
    @Test
    void processingInstructionsAfterTheRootElementAreNotStylesheetLinks()
    {
        assertEquals(List.of("prolog.xsl"), hrefs("""
                <?xml version="1.0" encoding="UTF-8"?>
                <?xml-stylesheet type="text/xsl" href="prolog.xsl"?>
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3">
                  <?xml-stylesheet type="text/xsl" href="inside.xsl"?>
                </ODM>
                <?xml-stylesheet type="text/xsl" href="trailing.xsl"?>
                """));
    }


    @Test
    void aDocumentWithNoStylesheetAtAllYieldsAnEmptyList()
    {
        assertEquals(List.of(), hrefs("""
                <?xml version="1.0" encoding="UTF-8"?>
                <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"/>
                """));
    }

}
