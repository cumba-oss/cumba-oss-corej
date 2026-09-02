package net.cumba.cdisc.define.conformance.eval;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import net.cumba.cdisc.define.conformance.tree.ElementNode;

/**
 * Document-wide element index backing the {@code references} check kind (plan §3.2): for every
 * element type, every key-attribute value → node. Built once per document over the full node scan.
 */
public final class OidResolver
{

    /** element local name → key attribute → value → first node carrying it. */
    private final Map<String, Map<String, Map<String, ElementNode>>> index = new HashMap<>();

    OidResolver(List<ElementNode> aAllNodes)
    {
        for (ElementNode node : aAllNodes)
        {
            for (Map.Entry<String, String> attr : node.attributes().entrySet())
            {
                index.computeIfAbsent(node.localName(), _ -> new HashMap<>())
                        .computeIfAbsent(attr.getKey(), _ -> new HashMap<>())
                        .putIfAbsent(attr.getValue(), node);
            }
        }
    }


    /**
     * The element of type {@code aTargetElement} whose {@code aTargetKey} attribute equals
     * {@code aValue}, or empty when the reference does not resolve.
     */
    public Optional<ElementNode> resolve(String aTargetElement, String aTargetKey, String aValue)
    {
        Map<String, Map<String, ElementNode>> byKey = index.get(aTargetElement);
        if (byKey == null)
        {
            return Optional.empty();
        }
        Map<String, ElementNode> byValue = byKey.get(aTargetKey);
        return byValue == null ? Optional.empty() : Optional.ofNullable(byValue.get(aValue));
    }

}
