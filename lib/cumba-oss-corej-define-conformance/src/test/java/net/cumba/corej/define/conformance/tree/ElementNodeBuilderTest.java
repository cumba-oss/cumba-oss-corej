package net.cumba.corej.define.conformance.tree;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.util.List;
import net.cumba.cdisc.define.DefineDomIo;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;

/**
 * {@link ElementNodeBuilder} / {@link ElementNode}: DOM normalisation (bare local names, xmlns
 * skipped, document order, text), navigation, xpath rendering, and the synthetic wrapper contract.
 */
class ElementNodeBuilderTest
{

    private static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <ODM xmlns="http://www.cdisc.org/ns/odm/v1.3"
                 xmlns:def="http://www.cdisc.org/ns/def/v2.1"
                 FileOID="F1" def:Context="Submission">
              <Study OID="ST.1">
                <ItemRef ItemOID="IT.A"/>
                <ItemRef ItemOID="IT.B"/>
                <Title>  Hello World  </Title>
                <Note><![CDATA[cdata text]]></Note>
              </Study>
            </ODM>
            """;

    private static ElementNode parse(String aXml)
    {
        try
        {
            Document doc = DefineDomIo.parse(new ByteArrayInputStream(aXml.getBytes(UTF_8)));
            return ElementNodeBuilder.build(doc);
        }
        catch (Exception e)
        {
            throw new IllegalStateException("cannot parse test XML", e);
        }
    }


    @Test
    void attributesAreKeyedByLocalNameAndXmlnsIsSkipped()
    {
        ElementNode root = parse(XML);
        assertEquals("ODM", root.localName());
        // def:Context is keyed by its bare local name; the two xmlns declarations are dropped.
        assertEquals(2, root.attributes().size());
        assertEquals("F1", root.attribute("FileOID").orElseThrow());
        assertEquals("Submission", root.attribute("Context").orElseThrow());
        assertTrue(root.attribute("Missing").isEmpty());
    }


    @Test
    void namespaceUriIsKeptSeparately()
    {
        ElementNode root = parse(XML);
        assertEquals("http://www.cdisc.org/ns/odm/v1.3", root.namespaceUri().orElseThrow());
    }


    @Test
    void childrenAreInDocumentOrderIncludingDuplicates()
    {
        ElementNode study = parse(XML).children().get(0);
        assertEquals("Study", study.localName());
        List<ElementNode> children = study.children();
        assertEquals(List.of("ItemRef", "ItemRef", "Title", "Note"),
                children.stream().map(ElementNode::localName).toList());
        assertEquals("IT.A", children.get(0).attribute("ItemOID").orElseThrow());
        assertEquals("IT.B", children.get(1).attribute("ItemOID").orElseThrow());
    }


    @Test
    void childrenByNameFiltersOnLocalName()
    {
        ElementNode study = parse(XML).children().get(0);
        assertEquals(2, study.children("ItemRef").size());
        assertEquals(1, study.children("Title").size());
        assertEquals(List.of(), study.children("NoSuch"));
    }


    @Test
    void textIsTrimmedAndBlankBecomesEmpty()
    {
        ElementNode study = parse(XML).children().get(0);
        assertEquals("Hello World", study.children("Title").get(0).text().orElseThrow());
        assertEquals("cdata text", study.children("Note").get(0).text().orElseThrow());
        // Element content is whitespace-only text between child elements — no text.
        assertTrue(study.text().isEmpty());
    }


    @Test
    void parentLinksAreSetAndRootHasNone()
    {
        ElementNode root = parse(XML);
        ElementNode study = root.children().get(0);
        assertTrue(root.parent().isEmpty());
        assertEquals(root, study.parent().orElseThrow());
        assertEquals(study, study.children("Title").get(0).parent().orElseThrow());
    }


    @Test
    void selfAndDescendantsIsDepthFirst()
    {
        ElementNode root = parse(XML);
        assertEquals(List.of("ODM", "Study", "ItemRef", "ItemRef", "Title", "Note"),
                root.selfAndDescendants().stream().map(ElementNode::localName).toList());
    }


    @Test
    void xpathQualifiesByOidAndFallsBackToPositionalIndex()
    {
        ElementNode root = parse(XML);
        ElementNode study = root.children().get(0);
        assertEquals("/ODM", root.xpath());
        assertEquals("/ODM/Study[@OID='ST.1']", study.xpath());
        // Two same-named ItemRef siblings without OID → 1-based positional index.
        assertEquals("/ODM/Study[@OID='ST.1']/ItemRef[1]",
                study.children("ItemRef").get(0).xpath());
        assertEquals("/ODM/Study[@OID='ST.1']/ItemRef[2]",
                study.children("ItemRef").get(1).xpath());
        // A single same-named child gets no index.
        assertEquals("/ODM/Study[@OID='ST.1']/Title", study.children("Title").get(0).xpath());
    }


    @Test
    void syntheticWrapperDoesNotAdoptItsChild()
    {
        ElementNode root = parse(XML);
        ElementNode document = ElementNodeBuilder.synthetic("Document", root);
        assertEquals("Document", document.localName());
        assertTrue(document.namespaceUri().isEmpty());
        assertEquals(List.of(root), document.children());
        // The child keeps its original (empty) parent, so xpaths never carry the synthetic node.
        assertTrue(root.parent().isEmpty());
        assertEquals("/ODM", root.xpath());
    }


    @Test
    void equalsAndHashCodeAreIdentityBased()
    {
        ElementNode first = parse(XML);
        ElementNode second = parse(XML);
        assertEquals(first, first);
        assertNotEquals(first, second);
        assertFalse(first.equals(null));
        assertEquals(System.identityHashCode(first), first.hashCode());
    }


    @Test
    void toStringSummarisesTheNode()
    {
        assertEquals("ElementNode[ODM, 2 attrs, 1 children]", parse(XML).toString());
    }

}
