package net.cumba.corej.define.conformance.tree;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * A normalised, immutable view of one Define-XML element, built once per document by
 * {@link ElementNodeBuilder} from the raw DOM.
 *
 * <p>
 * Element and attribute names are <b>bare local names</b> ("Standard", not "def:Standard"); the
 * namespace URI is kept separately in {@link #namespaceUri()} for the rules that need it (e.g. PMDA
 * DD0002). {@link #children()} is true <b>source-document order</b>, duplicates included.
 * </p>
 */
public final class ElementNode
{

    private final String localName;

    @Nullable
    private final String namespaceUri;

    private final Map<String, String> attributes;

    private final List<ElementNode> children;

    @Nullable
    private final String text;

    @Nullable
    private ElementNode parent;

    ElementNode(String aLocalName, @Nullable String aNamespaceUri, Map<String, String> aAttributes,
            List<ElementNode> aChildren, @Nullable String aText)
    {
        this(aLocalName, aNamespaceUri, aAttributes, aChildren, aText, true);
    }


    private ElementNode(String aLocalName, @Nullable String aNamespaceUri,
            Map<String, String> aAttributes, List<ElementNode> aChildren, @Nullable String aText,
            boolean aAdoptChildren)
    {
        localName = aLocalName;
        namespaceUri = aNamespaceUri;
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(aAttributes));
        children = List.copyOf(aChildren);
        text = aText;
        if (aAdoptChildren)
        {
            for (ElementNode child : children)
            {
                child.parent = this;
            }
        }
    }


    /**
     * A synthetic wrapper node that does NOT adopt its children (their {@link #parent()} stays
     * whatever their real tree gave them — {@code empty} for a document root). Used for the
     * evaluator's document-scope anchor, so real xpaths never carry the synthetic segment.
     */
    static ElementNode syntheticParent(String aLocalName, List<ElementNode> aChildren)
    {
        return new ElementNode(aLocalName, null, Map.of(), aChildren, null, false);
    }


    /** The element's bare local name, e.g. {@code "ItemGroupDef"} or {@code "Standard"}. */
    public String localName()
    {
        return localName;
    }


    /** The element's namespace URI, or empty for unqualified/synthetic elements. */
    public Optional<String> namespaceUri()
    {
        return Optional.ofNullable(namespaceUri);
    }


    /** Attribute local name → string value; only attributes present in the source document. */
    public Map<String, String> attributes()
    {
        return attributes;
    }


    /** The value of one attribute, or empty when absent. */
    public Optional<String> attribute(String aLocalName)
    {
        return Optional.ofNullable(attributes.get(aLocalName));
    }


    /** Child elements in source-document order, duplicates included. */
    public List<ElementNode> children()
    {
        return children;
    }


    /** Child elements with the given bare local name. */
    public List<ElementNode> children(String aLocalName)
    {
        List<ElementNode> out = new ArrayList<>();
        for (ElementNode child : children)
        {
            if (child.localName.equals(aLocalName))
            {
                out.add(child);
            }
        }
        return out;
    }


    /** The parent element; empty only for the document root. */
    public Optional<ElementNode> parent()
    {
        return Optional.ofNullable(parent);
    }


    /** Text content for text-bearing leaves (e.g. {@code TranslatedText}); empty otherwise. */
    public Optional<String> text()
    {
        return Optional.ofNullable(text);
    }


    /**
     * This node and every descendant, depth-first. The list is rebuilt on each call — callers that
     * scan repeatedly should hold onto the result (see {@code DocumentContext}).
     */
    public List<ElementNode> selfAndDescendants()
    {
        List<ElementNode> out = new ArrayList<>();
        collect(this, out);
        return out;
    }


    private static void collect(ElementNode node, List<ElementNode> out)
    {
        out.add(node);
        for (ElementNode child : node.children)
        {
            collect(child, out);
        }
    }


    /**
     * An XPath-like location string for findings, e.g.
     * {@code /ODM/Study/MetaDataVersion/ItemGroupDef[@OID='IG.AE']/ItemRef[2]}. Elements carrying
     * an {@code OID} attribute are qualified by it; otherwise a 1-based positional index among
     * same-named siblings is used when there is more than one.
     */
    public String xpath()
    {
        StringBuilder sb = new StringBuilder(64);
        buildXpath(this, sb);
        return sb.toString();
    }


    private static void buildXpath(ElementNode node, StringBuilder sb)
    {
        ElementNode up = node.parent;
        if (up != null)
        {
            buildXpath(up, sb);
        }
        sb.append('/').append(node.localName);
        String oid = node.attributes.get("OID");
        if (oid != null)
        {
            sb.append("[@OID='").append(oid).append("']");
            return;
        }
        if (up != null)
        {
            List<ElementNode> sameName = up.children(node.localName);
            if (sameName.size() > 1)
            {
                int index = sameName.indexOf(node);
                sb.append('[').append(index + 1).append(']');
            }
        }
    }


    @Override
    public String toString()
    {
        return "ElementNode[" + localName + ", " + attributes.size() + " attrs, " + children.size()
                + " children]";
    }


    /** Identity-based: two nodes are equal only if they are the same tree node. */
    @Override
    public boolean equals(@Nullable Object aOther)
    {
        return this == aOther;
    }


    @Override
    public int hashCode()
    {
        return System.identityHashCode(this);
    }

}
