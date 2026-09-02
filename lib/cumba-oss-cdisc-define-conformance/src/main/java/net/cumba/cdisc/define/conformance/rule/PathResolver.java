package net.cumba.cdisc.define.conformance.rule;

import java.util.ArrayList;
import java.util.List;
import net.cumba.cdisc.define.conformance.tree.ElementNode;

/**
 * Resolves the small relative-navigation grammar used by rule conditions and checks (plan §3.3).
 *
 * <p>
 * A path is {@code /}-separated steps evaluated from a context node:
 * </p>
 * <ul>
 * <li>{@code ..} — the parent element;</li>
 * <li>{@code Name} — all child elements with that bare local name (a leading {@code def:} or
 * {@code xml:} prefix is stripped, the tree is namespace-agnostic);</li>
 * <li>{@code @Attr} — terminal step only: the attribute's value on each resolved element.</li>
 * </ul>
 *
 * <p>
 * Example: {@code "../Origin/@Type"} — from an element, go to its parent, collect the parent's
 * {@code Origin} children, and read each one's {@code Type} attribute.
 * </p>
 */
public final class PathResolver
{

    private PathResolver()
    {
    }


    /**
     * Elements the path's element steps resolve to (the trailing {@code @Attr} step, if any, must
     * be handled by {@link #values}).
     */
    public static List<ElementNode> nodes(ElementNode aContext, String aPath)
    {
        List<ElementNode> current = new ArrayList<>();
        current.add(aContext);
        for (String step : aPath.split("/", -1))
        {
            if (step.isEmpty() || step.startsWith("@"))
            {
                continue;
            }
            List<ElementNode> next = new ArrayList<>();
            if ("..".equals(step))
            {
                for (ElementNode node : current)
                {
                    node.parent().ifPresent(next::add);
                }
            }
            else
            {
                String name = stripPrefix(step);
                for (ElementNode node : current)
                {
                    next.addAll(node.children(name));
                }
            }
            current = next;
        }
        return current;
    }


    /**
     * String values the full path resolves to: attribute values when the path ends in {@code @Attr}
     * (absent attributes contribute nothing), otherwise the resolved elements' text content
     * (elements without text contribute nothing).
     */
    public static List<String> values(ElementNode aContext, String aPath)
    {
        List<ElementNode> resolved = nodes(aContext, aPath);
        String last = aPath.substring(aPath.lastIndexOf('/') + 1);
        List<String> out = new ArrayList<>();
        if (last.startsWith("@"))
        {
            String attr = stripPrefix(last.substring(1));
            for (ElementNode node : resolved)
            {
                node.attribute(attr).ifPresent(out::add);
            }
        }
        else
        {
            for (ElementNode node : resolved)
            {
                node.text().ifPresent(out::add);
            }
        }
        return out;
    }


    /**
     * Deref-aware variant for the {@code compare} kind: a segment of the form
     * {@code @Attr->Element@Key} reads {@code Attr} off the current elements and jumps to the
     * {@code Element} whose {@code Key} attribute carries that value (via the document-wide index);
     * navigation then continues from the target. All other segments behave as {@link #values}.
     * Example: {@code "@ArchiveLocationID->leaf@ID/@href"}.
     */
    public static List<String> valuesWithDeref(ElementNode aContext, String aPath,
            net.cumba.cdisc.define.conformance.eval.OidResolver aResolver)
    {
        List<ElementNode> current = new ArrayList<>();
        current.add(aContext);
        String[] segments = aPath.split("/", -1);
        for (int i = 0; i < segments.length; i++)
        {
            String step = segments[i];
            if (step.isEmpty())
            {
                continue;
            }
            int arrow = step.indexOf("->");
            if (arrow > 0 && step.startsWith("@"))
            {
                String attr = stripPrefix(step.substring(1, arrow));
                String target = step.substring(arrow + 2);
                int at = target.indexOf('@');
                if (at <= 0)
                {
                    throw new IllegalStateException(
                            "deref step needs the form @Attr->Element@Key: " + step);
                }
                String targetElement = stripPrefix(target.substring(0, at));
                String targetKey = stripPrefix(target.substring(at + 1));
                List<ElementNode> next = new ArrayList<>();
                for (ElementNode node : current)
                {
                    node.attribute(attr)
                            .flatMap(v -> aResolver.resolve(targetElement, targetKey, v))
                            .ifPresent(next::add);
                }
                current = next;
            }
            else if (step.startsWith("@"))
            {
                // Terminal attribute read (must be last, as in values()).
                List<String> out = new ArrayList<>();
                String attr = stripPrefix(step.substring(1));
                for (ElementNode node : current)
                {
                    node.attribute(attr).ifPresent(out::add);
                }
                return out;
            }
            else if ("..".equals(step))
            {
                List<ElementNode> next = new ArrayList<>();
                for (ElementNode node : current)
                {
                    node.parent().ifPresent(next::add);
                }
                current = next;
            }
            else
            {
                String name = stripPrefix(step);
                List<ElementNode> next = new ArrayList<>();
                for (ElementNode node : current)
                {
                    next.addAll(node.children(name));
                }
                current = next;
            }
        }
        // Path ended on elements: their text content, as values() does.
        List<String> out = new ArrayList<>();
        for (ElementNode node : current)
        {
            node.text().ifPresent(out::add);
        }
        return out;
    }


    /** Strips a namespace prefix ({@code def:Standard} → {@code Standard}). */
    public static String stripPrefix(String aName)
    {
        int colon = aName.indexOf(':');
        return colon < 0 ? aName : aName.substring(colon + 1);
    }

}
