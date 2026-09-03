package net.cumba.corej.define.conformance.tree;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.xml.XMLConstants;
import net.cumba.cdisc.define.DefineDomUtil;
import org.w3c.dom.Attr;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NamedNodeMap;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import org.w3c.dom.ProcessingInstruction;

/**
 * Builds the normalised {@link ElementNode} tree by walking the raw DOM (parsed via
 * {@code DefineDomIo.parse}).
 *
 * <p>
 * The DOM — not the {@code net.cumba.cdisc.define} bean graph — is the source of truth here, for
 * reasons the Phase-0 bean-model audit made concrete: the bean model has no classes for the ARM
 * cluster and misses several 2.1 attributes, single-valued bean fields silently swallow duplicate
 * child elements (cardinality rules would be blind), bean field order is schema order rather than
 * source order (ordering rules would be no-ops), and namespaces are stripped (namespace rules would
 * be inexpressible). Walking the DOM removes the entire completeness problem: every element,
 * attribute, duplicate, and namespace in the file is visible, in true document order.
 * </p>
 *
 * <p>
 * Normalisation: element and attribute names are keyed by <b>bare local name</b> (the {@code def:}
 * prefix is a namespace matter, kept in {@link ElementNode#namespaceUri()}); {@code xmlns}
 * declarations are not attributes; an element's text content is captured from its direct text
 * children (trimmed, {@code null} when blank).
 * </p>
 */
public final class ElementNodeBuilder
{

    private ElementNodeBuilder()
    {
    }


    /** Builds the tree for a parsed Define-XML document, rooted at its document element. */
    public static ElementNode build(Document aDocument)
    {
        return fromElement(aDocument.getDocumentElement());
    }


    /** Builds one node (and its subtree) from a DOM element. */
    public static ElementNode fromElement(Element aElement)
    {
        Map<String, String> attributes = new LinkedHashMap<>();
        NamedNodeMap attrs = aElement.getAttributes();
        for (int i = 0; i < attrs.getLength(); i++)
        {
            Attr attr = (Attr) attrs.item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attr.getNamespaceURI()))
            {
                continue;
            }
            attributes.put(DefineDomUtil.localNameOf(attr), attr.getValue());
        }

        List<ElementNode> children = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        NodeList childNodes = aElement.getChildNodes();
        for (int i = 0; i < childNodes.getLength(); i++)
        {
            Node child = childNodes.item(i);
            if (child instanceof Element el)
            {
                children.add(fromElement(el));
            }
            else if (child.getNodeType() == Node.TEXT_NODE
                    || child.getNodeType() == Node.CDATA_SECTION_NODE)
            {
                text.append(child.getTextContent());
            }
        }
        String trimmed = text.toString().trim();
        return new ElementNode(DefineDomUtil.localNameOf(aElement), aElement.getNamespaceURI(),
                attributes, children, trimmed.isEmpty() ? null : trimmed);
    }


    /**
     * A synthetic non-adopting wrapper node (the evaluator's {@code Document} scope anchor); the
     * children keep their original parents.
     */
    public static ElementNode synthetic(String aLocalName, ElementNode aChild)
    {
        return ElementNode.syntheticParent(aLocalName, List.of(aChild));
    }


    /**
     * The {@code href} pseudo-attribute values of the document's {@code <?xml-stylesheet?>}
     * processing instructions, in document order. Only prolog PIs (before the root element) are
     * stylesheet links per the W3C xml-stylesheet recommendation; PIs elsewhere are ignored, as are
     * prolog PIs without an {@code href}. This is the one piece of the document the
     * {@link ElementNode} tree cannot carry — PIs are not elements — so it is extracted here,
     * straight off the DOM, for the {@code stylesheet_file_exists} check (PMDA DD0085).
     */
    public static List<String> stylesheetHrefs(Document aDocument)
    {
        List<String> hrefs = new ArrayList<>();
        NodeList children = aDocument.getChildNodes();
        for (int i = 0; i < children.getLength(); i++)
        {
            Node child = children.item(i);
            if (child instanceof Element)
            {
                break;
            }
            if (child instanceof ProcessingInstruction pi
                    && "xml-stylesheet".equals(pi.getTarget()))
            {
                Matcher matcher = STYLESHEET_HREF.matcher(pi.getData() == null ? "" : pi.getData());
                if (matcher.find())
                {
                    hrefs.add(matcher.group(1));
                }
            }
        }
        return List.copyOf(hrefs);
    }

    /**
     * {@code href="…"} / {@code href='…'} pseudo-attribute inside an xml-stylesheet PI. The
     * start-or-whitespace boundary keeps a {@code href=} embedded in a preceding pseudo-attribute
     * value (or a hypothetical {@code xhref=}) from matching.
     */
    private static final Pattern STYLESHEET_HREF = Pattern
            .compile("(?:^|\\s)href\\s*=\\s*[\"']([^\"']*)[\"']");

}
