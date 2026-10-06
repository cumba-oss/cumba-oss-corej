package net.cumba.corej.authoring;

import com.fasterxml.jackson.core.JsonLocation;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import net.cumba.corej.authoring.RuleDiagnostic.TextRange;
import net.cumba.corej.authoring.RuleDiagnostic.YamlPath;
import org.jspecify.annotations.Nullable;

/**
 * An index of where each path of a YAML document sits in its text — the source of the
 * {@link TextRange} a {@link RuleDiagnostic} carries.
 *
 * <p>
 * Jackson's tree model keeps no locations, so the document is walked once with the streaming
 * parser: every mapping key and every sequence element records the location of its key token (for a
 * key) or its first value token (for an element). A path that the walk never reached — a document
 * the parser could not finish — is simply absent from the index; the caller then attaches no range
 * rather than a wrong one.
 * </p>
 */
public final class YamlLocations
{

    private static final YAMLFactory YAML = new YAMLFactory();

    private final Map<String, TextRange> byPath;

    private final Map<String, ValueLocation> valueByPath;

    private final boolean complete;

    private YamlLocations(Map<String, TextRange> aByPath, Map<String, ValueLocation> aValueByPath,
            boolean aComplete)
    {
        byPath = aByPath;
        valueByPath = aValueByPath;
        complete = aComplete;
    }


    /** Whether the whole document was tokenised ({@code false} for a broken document). */
    public boolean isComplete()
    {
        return complete;
    }

    /**
     * Where a scalar value sits: its first character's line and column, whether that character is a
     * quote (so an offset into the value's text starts one column later), and whether the scalar
     * spans lines (a block or folded scalar, whose offsets this index does not map).
     */
    public record ValueLocation(int line, int column, boolean quoted, boolean multiline)
    {

        /**
         * The range of one character at a 0-based offset into the scalar's text, or {@code null}
         * for a multi-line scalar.
         */
        public @Nullable TextRange rangeAt(int aOffset)
        {
            if (multiline || aOffset < 0)
            {
                return null;
            }
            return TextRange.at(line, column + (quoted ? 1 : 0) + aOffset, 1);
        }
    }

    /**
     * Index a document. A document that cannot be tokenised to the end yields the index of the
     * prefix that could.
     *
     * @param aYaml
     *            the authored text
     * @return the index, possibly empty
     */
    public static YamlLocations index(String aYaml)
    {
        Map<String, TextRange> out = new LinkedHashMap<>();
        Map<String, ValueLocation> values = new LinkedHashMap<>();
        String[] lines = aYaml.split("\\R", -1);
        boolean complete = true;
        try (JsonParser p = YAML.createParser(aYaml))
        {
            walk(p, lines, out, values);
        }
        catch (IOException _)
        {
            // Partial index: a broken document locates what it can, and says so.
            complete = false;
        }
        return new YamlLocations(out, values, complete);
    }


    private static void walk(JsonParser aParser, String[] aLines, Map<String, TextRange> aOut,
            Map<String, ValueLocation> aValues)
        throws IOException
    {
        // Each frame: the path prefix of the container and, for an array, the next index.
        Deque<Frame> stack = new ArrayDeque<>();
        JsonToken t;
        while ((t = aParser.nextToken()) != null)
        {
            Frame top = stack.peek();
            switch (t)
            {
            case FIELD_NAME ->
            {
                String name = aParser.currentName();
                if (top != null)
                {
                    top.current = top.prefix.isEmpty() ? name : top.prefix + "/" + name;
                    aOut.put(top.current, rangeOf(aParser.currentTokenLocation(), name.length()));
                }
            }
            case START_OBJECT, START_ARRAY ->
            {
                String prefix = "";
                if (top != null)
                {
                    prefix = elementPath(top);
                    if (top.array)
                    {
                        aOut.put(prefix, rangeOf(aParser.currentTokenLocation(), 1));
                    }
                }
                stack.push(new Frame(prefix, t == JsonToken.START_ARRAY));
            }
            case END_OBJECT, END_ARRAY -> stack.pop();
            default ->
            {
                if (top == null)
                {
                    break;
                }
                String path = top.array ? elementPath(top) : top.current;
                JsonLocation loc = aParser.currentTokenLocation();
                if (top.array)
                {
                    aOut.put(path, rangeOf(loc, Math.max(aParser.getTextLength(), 1)));
                }
                if (t.isScalarValue())
                {
                    aValues.put(path, new ValueLocation(loc.getLineNr(), loc.getColumnNr(),
                            isQuoteAt(aLines, loc), aParser.getText().indexOf('\n') >= 0));
                }
            }
            }
        }
    }


    /**
     * The path of the value about to be consumed: for an array frame the next index, for an object
     * frame the field just named.
     */
    private static String elementPath(Frame aTop)
    {
        if (aTop.array)
        {
            String path = aTop.prefix.isEmpty() ? Integer.toString(aTop.nextIndex)
                    : aTop.prefix + "/" + aTop.nextIndex;
            aTop.nextIndex++;
            aTop.current = path;
            return path;
        }
        return aTop.current;
    }


    private static boolean isQuoteAt(String[] aLines, JsonLocation aLocation)
    {
        int line = aLocation.getLineNr() - 1;
        int col = aLocation.getColumnNr() - 1;
        if (line < 0 || line >= aLines.length || col < 0 || col >= aLines[line].length())
        {
            return false;
        }
        char c = aLines[line].charAt(col);
        return c == '"' || c == '\'';
    }


    private static TextRange rangeOf(JsonLocation aLocation, int aLength)
    {
        return TextRange.at(aLocation.getLineNr(), aLocation.getColumnNr(), aLength);
    }


    /**
     * The range of a path's key (or of a sequence element's first token).
     *
     * @param aPath
     *            the path
     * @return the range, or {@code null} when the index does not hold the path
     */
    public @Nullable TextRange rangeOf(YamlPath aPath)
    {
        return byPath.get(String.join("/", aPath.segments()));
    }


    /**
     * Where a path's scalar value starts.
     *
     * @param aPath
     *            the path
     * @return the location, or {@code null} when the path holds no scalar or is unknown
     */
    public @Nullable ValueLocation valueOf(YamlPath aPath)
    {
        return valueByPath.get(String.join("/", aPath.segments()));
    }


    /** The number of located paths. */
    public int size()
    {
        return byPath.size();
    }

    private static final class Frame
    {

        final String prefix;

        final boolean array;

        int nextIndex;

        String current;

        Frame(String aPrefix, boolean aArray)
        {
            prefix = aPrefix;
            array = aArray;
            current = aPrefix;
        }
    }
}
