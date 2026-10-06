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
 * rather than a wrong one. The walk covers the <b>first</b> document only; where a second one
 * starts is recorded ({@link #secondDocument()}) so the caller can refuse it rather than read half
 * a file (pack-2 review 8a-A F4).
 * </p>
 */
public final class YamlLocations
{

    private static final YAMLFactory YAML = new YAMLFactory();

    private final Map<String, TextRange> byPath;

    private final Map<String, ValueLocation> valueByPath;

    private final boolean complete;

    private final @Nullable TextRange secondDocument;

    private YamlLocations(Map<String, TextRange> aByPath, Map<String, ValueLocation> aValueByPath,
            boolean aComplete, @Nullable TextRange aSecondDocument)
    {
        byPath = aByPath;
        valueByPath = aValueByPath;
        complete = aComplete;
        secondDocument = aSecondDocument;
    }


    /** Whether the whole document was tokenised ({@code false} for a broken document). */
    public boolean isComplete()
    {
        return complete;
    }


    /**
     * Where a second YAML document starts (its {@code ---} marker, else its first token), or
     * {@code null} for a single-document text. Nothing reads past the first document, so a second
     * one is content the author wrote and no tool would ever see.
     */
    public @Nullable TextRange secondDocument()
    {
        return secondDocument;
    }

    /**
     * Where a scalar value sits, and how its text maps back onto the source: an offset into the
     * value's <em>parsed</em> text ({@code ExpressionException.getPosition()}) becomes the line and
     * column of the character it came from.
     *
     * <h2>Every scalar style (pack-2 review 8a-A F1)</h2>
     *
     * <p>
     * A plain scalar maps column for column on one line; a quoted one starts after its quote, and
     * its escapes ({@code \"}, {@code ''}, {@code \x41} …) are one character of the text over
     * several of the source; a block scalar ({@code |}, {@code >}, {@code >-} — the corpus's ~3 500
     * expressions) starts on the line <em>after</em> its indicator, with the indentation removed;
     * folding turns a line break into a space (or drops it, for a {@code \} continuation). The
     * source is re-scanned in the scalar's own style and the reconstruction compared with the
     * parser's text: only an exact match maps anything. ⛔ <b>Verify or nothing:</b> a style this
     * scanner does not reproduce (an alias, a tab-indented oddity), an offset past the end, or a
     * character whose source does not match yields {@code null}, and the caller falls back to the
     * key's range — never a confident wrong position.
     * </p>
     */
    public static final class ValueLocation
    {

        private final String[] lines;

        private final int line;

        private final int column;

        private final String text;

        ValueLocation(String[] aLines, int aLine, int aColumn, String aText)
        {
            lines = aLines;
            line = aLine;
            column = aColumn;
            text = aText;
        }


        /** The 1-based line of the value's first token (a block scalar's indicator). */
        public int line()
        {
            return line;
        }


        /** The 1-based column of the value's first token. */
        public int column()
        {
            return column;
        }


        /** The value's parsed text. */
        public String text()
        {
            return text;
        }


        /**
         * The range of one character at a 0-based offset into the scalar's text — or, for the
         * offset just past its end, of the position after its last character.
         *
         * @param aOffset
         *            the offset
         * @return the range, or {@code null} when the source cannot be mapped (see the class
         *         javadoc)
         */
        public @Nullable TextRange rangeAt(int aOffset)
        {
            if (aOffset < 0 || aOffset > text.length())
            {
                return null;
            }
            ScalarMap map = ScalarMap.of(lines, line - 1, column - 1, text);
            if (map == null)
            {
                return null;
            }
            if (aOffset == text.length())
            {
                if (aOffset == 0)
                {
                    return null;
                }
                int last = aOffset - 1;
                return TextRange.at(map.line[last] + 1, map.column[last] + 2, 1);
            }
            int l = map.line[aOffset];
            int c = map.column[aOffset];
            if (map.literal[aOffset] && lines[l].charAt(c) != text.charAt(aOffset))
            {
                return null;
            }
            return TextRange.at(l + 1, c + 1, 1);
        }


        @Override
        public String toString()
        {
            return "ValueLocation[" + line + ":" + column + "]";
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
        TextRange second = null;
        try (JsonParser p = YAML.createParser(aYaml))
        {
            JsonLocation next = walk(p, lines, out, values);
            if (next != null)
            {
                second = documentStart(lines, next);
            }
        }
        catch (IOException _)
        {
            // Partial index: a broken document locates what it can, and says so.
            complete = false;
        }
        return new YamlLocations(out, values, complete, second);
    }


    /**
     * Walk the first document; answer the location of the first token after it (a second document),
     * or {@code null}.
     */
    private static @Nullable JsonLocation walk(JsonParser aParser, String[] aLines,
            Map<String, TextRange> aOut, Map<String, ValueLocation> aValues)
        throws IOException
    {
        // Each frame: the path prefix of the container and, for an array, the next index.
        Deque<Frame> stack = new ArrayDeque<>();
        boolean rootSeen = false;
        JsonToken t;
        while ((t = aParser.nextToken()) != null)
        {
            if (rootSeen && stack.isEmpty())
            {
                // The first document's root value is complete: whatever follows is another one.
                return aParser.currentTokenLocation();
            }
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
                    aValues.put(path, new ValueLocation(aLines, loc.getLineNr(), loc.getColumnNr(),
                            aParser.getText()));
                }
            }
            }
            rootSeen = true;
        }
        return null;
    }


    /**
     * The start of a second document: the nearest {@code ---} (or {@code ...}) marker line at or
     * above the first token Jackson reports for it, else that token.
     */
    private static TextRange documentStart(String[] aLines, JsonLocation aToken)
    {
        for (int l = Math.min(aToken.getLineNr(), aLines.length) - 1; l >= 0; l--)
        {
            String s = aLines[l];
            if (s.startsWith("---") || s.startsWith("..."))
            {
                return TextRange.at(l + 1, 1, 3);
            }
        }
        return TextRange.at(aToken.getLineNr(), aToken.getColumnNr(), 1);
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


    /**
     * The source position (0-based line and column) of every character of a scalar's parsed text,
     * rebuilt by re-scanning the source in the scalar's style; {@code literal} marks a character
     * copied verbatim (an escape or a fold is not).
     */
    private static final class ScalarMap
    {

        final int[] line;

        final int[] column;

        final boolean[] literal;

        private final String expected;

        private final StringBuilder built = new StringBuilder();

        private ScalarMap(String aExpected)
        {
            expected = aExpected;
            line = new int[aExpected.length()];
            column = new int[aExpected.length()];
            literal = new boolean[aExpected.length()];
        }


        /** True once the whole text is mapped. */
        private boolean done()
        {
            return built.length() >= expected.length();
        }


        /** Emit one character; false when it disagrees with the parsed text. */
        private boolean emit(char aChar, int aLine, int aColumn, boolean aLiteral)
        {
            int i = built.length();
            if (i >= expected.length() || expected.charAt(i) != aChar)
            {
                return false;
            }
            built.append(aChar);
            line[i] = aLine;
            column[i] = aColumn;
            literal[i] = aLiteral;
            return true;
        }


        /** The map, or {@code null} when the scalar's source cannot be reproduced. */
        static @Nullable ScalarMap of(String[] aLines, int aLine, int aColumn, String aText)
        {
            if (aLine < 0 || aLine >= aLines.length || aColumn < 0)
            {
                return null;
            }
            ScalarMap map = new ScalarMap(aText);
            int col = skipProperties(aLines[aLine], aColumn);
            if (col < 0 || col >= aLines[aLine].length())
            {
                return aText.isEmpty() ? map : null;
            }
            char c = aLines[aLine].charAt(col);
            boolean ok = switch (c)
            {
            case '"' -> map.doubleQuoted(aLines, aLine, col + 1);
            case '\'' -> map.singleQuoted(aLines, aLine, col + 1);
            case '|', '>' -> map.block(aLines, aLine, col);
            case '*' -> false; // an alias: its text is another node's
            default -> map.plain(aLines, aLine, col);
            };
            return ok && map.done() ? map : null;
        }


        /** Past a tag ({@code !!str}) and an anchor ({@code &a}) before the scalar itself. */
        private static int skipProperties(String aLine, int aColumn)
        {
            int col = aColumn;
            while (col < aLine.length() && (aLine.charAt(col) == '!' || aLine.charAt(col) == '&'))
            {
                while (col < aLine.length() && !isBlank(aLine.charAt(col)))
                {
                    col++;
                }
                while (col < aLine.length() && isBlank(aLine.charAt(col)))
                {
                    col++;
                }
            }
            return col;
        }


        private static boolean isBlank(char aChar)
        {
            return aChar == ' ' || aChar == '\t';
        }


        /**
         * The line break(s) of a flow scalar (quoted or plain) at the end of line {@code aLine}:
         * one break folds to a space, {@code n} following empty lines to {@code n} newlines.
         * Answers the line the scalar continues on, or {@code -1}.
         */
        private int foldFlowBreak(String[] aLines, int aLine)
        {
            int next = aLine + 1;
            int empty = 0;
            while (next < aLines.length && aLines[next].isBlank())
            {
                empty++;
                next++;
            }
            if (next >= aLines.length)
            {
                return -1;
            }
            if (empty == 0 && !emit(' ', aLine, aLines[aLine].length(), false))
            {
                return -1;
            }
            for (int e = 0; e < empty; e++)
            {
                if (!emit('\n', aLine + 1 + e, 0, false))
                {
                    return -1;
                }
            }
            return next;
        }


        private static int firstNonBlank(String aLine)
        {
            int col = 0;
            while (col < aLine.length() && isBlank(aLine.charAt(col)))
            {
                col++;
            }
            return col;
        }


        private boolean doubleQuoted(String[] aLines, int aLine, int aColumn)
        {
            int l = aLine;
            int c = aColumn;
            while (!done())
            {
                String s = aLines[l];
                // literal blanks are content only when something other than a break follows
                int blanks = c;
                while (blanks < s.length() && isBlank(s.charAt(blanks)))
                {
                    blanks++;
                }
                if (blanks >= s.length())
                {
                    l = foldFlowBreak(aLines, l);
                    if (l < 0)
                    {
                        return false;
                    }
                    c = firstNonBlank(aLines[l]);
                    continue;
                }
                for (; c < blanks; c++)
                {
                    if (!emit(s.charAt(c), l, c, true))
                    {
                        return false;
                    }
                }
                char ch = s.charAt(c);
                if (ch == '"')
                {
                    return done();
                }
                if (ch != '\\')
                {
                    if (!emit(ch, l, c, true))
                    {
                        return false;
                    }
                    c++;
                    continue;
                }
                if (c + 1 >= s.length())
                {
                    // an escaped line break: the break and the next line's indentation vanish
                    l++;
                    if (l >= aLines.length)
                    {
                        return false;
                    }
                    c = firstNonBlank(aLines[l]);
                    continue;
                }
                int consumed = escape(s, c, l);
                if (consumed < 0)
                {
                    return false;
                }
                c += consumed;
            }
            return true;
        }


        /** One escape sequence at {@code aCol} (the backslash); its length, or {@code -1}. */
        private int escape(String aLine, int aCol, int aLineNr)
        {
            char e = aLine.charAt(aCol + 1);
            int hex = switch (e)
            {
            case 'x' -> 2;
            case 'u' -> 4;
            case 'U' -> 8;
            default -> 0;
            };
            if (hex > 0)
            {
                if (aCol + 2 + hex > aLine.length())
                {
                    return -1;
                }
                int cp;
                try
                {
                    cp = Integer.parseInt(aLine.substring(aCol + 2, aCol + 2 + hex), 16);
                }
                catch (NumberFormatException _)
                {
                    return -1;
                }
                if (!Character.isValidCodePoint(cp))
                {
                    return -1;
                }
                for (char ch : Character.toChars(cp))
                {
                    if (!emit(ch, aLineNr, aCol, false))
                    {
                        return -1;
                    }
                }
                return 2 + hex;
            }
            char out = switch (e)
            {
            case '0' -> '\0';
            case 'a' -> '\u0007';
            case 'b' -> '\b';
            case 't', '\t' -> '\t';
            case 'n' -> '\n';
            case 'v' -> '\u000B';
            case 'f' -> '\f';
            case 'r' -> '\r';
            case 'e' -> '\u001B';
            case ' ' -> ' ';
            case '"' -> '"';
            case '/' -> '/';
            case '\\' -> '\\';
            case 'N' -> '\u0085';
            case '_' -> ' ';
            case 'L' -> ' ';
            case 'P' -> ' ';
            default -> '￿';
            };
            if (out == '￿')
            {
                return -1;
            }
            // mapped to the escape's last character: `\"` points at the quote it produces
            return emit(out, aLineNr, aCol + 1, out == e) ? 2 : -1;
        }


        private boolean singleQuoted(String[] aLines, int aLine, int aColumn)
        {
            int l = aLine;
            int c = aColumn;
            while (!done())
            {
                String s = aLines[l];
                int blanks = c;
                while (blanks < s.length() && isBlank(s.charAt(blanks)))
                {
                    blanks++;
                }
                if (blanks >= s.length())
                {
                    l = foldFlowBreak(aLines, l);
                    if (l < 0)
                    {
                        return false;
                    }
                    c = firstNonBlank(aLines[l]);
                    continue;
                }
                for (; c < blanks; c++)
                {
                    if (!emit(s.charAt(c), l, c, true))
                    {
                        return false;
                    }
                }
                char ch = s.charAt(c);
                if (ch == '\'')
                {
                    if (c + 1 < s.length() && s.charAt(c + 1) == '\'')
                    {
                        if (!emit('\'', l, c + 1, true))
                        {
                            return false;
                        }
                        c += 2;
                        continue;
                    }
                    return done();
                }
                if (!emit(ch, l, c, true))
                {
                    return false;
                }
                c++;
            }
            return true;
        }


        private boolean plain(String[] aLines, int aLine, int aColumn)
        {
            int l = aLine;
            int c = aColumn;
            while (!done())
            {
                String s = aLines[l];
                // the line's content: up to a comment (" #") or the end, trailing blanks dropped
                int end = s.length();
                for (int i = c; i < s.length(); i++)
                {
                    if (s.charAt(i) == '#' && i > c && isBlank(s.charAt(i - 1)))
                    {
                        end = i;
                        break;
                    }
                }
                while (end > c && isBlank(s.charAt(end - 1)))
                {
                    end--;
                }
                for (; c < end && !done(); c++)
                {
                    if (!emit(s.charAt(c), l, c, true))
                    {
                        return false;
                    }
                }
                if (done())
                {
                    return true;
                }
                l = foldFlowBreak(aLines, l);
                if (l < 0)
                {
                    return false;
                }
                c = firstNonBlank(aLines[l]);
            }
            return true;
        }


        /**
         * A literal ({@code |}) or folded ({@code >}) block scalar whose indicator sits at
         * {@code aColumn}. Chomping needs no case of its own: the text is mapped up to its length,
         * so a stripped final break is simply never emitted.
         */
        private boolean block(String[] aLines, int aLine, int aColumn)
        {
            String header = aLines[aLine];
            boolean folded = header.charAt(aColumn) == '>';
            int explicit = 0;
            for (int i = aColumn + 1; i < header.length() && i <= aColumn + 2; i++)
            {
                char h = header.charAt(i);
                if (h >= '1' && h <= '9')
                {
                    explicit = h - '0';
                }
                else if (h != '-' && h != '+')
                {
                    break;
                }
            }
            int indent;
            if (explicit > 0)
            {
                indent = firstNonBlank(header) + explicit;
            }
            else
            {
                indent = -1;
                for (int l = aLine + 1; l < aLines.length && indent < 0; l++)
                {
                    if (!aLines[l].isBlank())
                    {
                        indent = firstNonBlank(aLines[l]);
                    }
                }
                if (indent < 0)
                {
                    return done();
                }
            }
            int empty = 0;
            boolean first = true;
            boolean prevSpaced = false;
            int prevLine = -1;
            for (int l = aLine + 1; l < aLines.length && !done(); l++)
            {
                String s = aLines[l];
                if (s.isBlank())
                {
                    empty++;
                    continue;
                }
                if (firstNonBlank(s) < indent)
                {
                    break;
                }
                boolean spaced = s.length() > indent && isBlank(s.charAt(indent));
                if (!separate(aLines, folded, first, prevSpaced, spaced, prevLine, l, empty))
                {
                    return false;
                }
                empty = 0;
                for (int c = indent; c < s.length() && !done(); c++)
                {
                    if (!emit(s.charAt(c), l, c, true))
                    {
                        return false;
                    }
                }
                first = false;
                prevSpaced = spaced;
                prevLine = l;
            }
            // the final break(s): clip keeps one, keep all; strip none — the length decides
            if (!done() && prevLine >= 0 && !emit('\n', prevLine, aLines[prevLine].length(), false))
            {
                return false;
            }
            for (int e = 0; e < empty && !done(); e++)
            {
                if (!emit('\n', prevLine + 1 + e, 0, false))
                {
                    return false;
                }
            }
            return done();
        }


        /** The separator between two content lines of a block scalar. */
        private boolean separate(String[] aLines, boolean aFolded, boolean aFirst,
                boolean aPrevSpaced, boolean aSpaced, int aPrevLine, int aLine, int aEmpty)
        {
            if (aFirst)
            {
                for (int e = 0; e < aEmpty; e++)
                {
                    if (!emit('\n', aLine - aEmpty + e, 0, false))
                    {
                        return false;
                    }
                }
                return true;
            }
            boolean fold = aFolded && !aPrevSpaced && !aSpaced;
            int breakColumn = aLines[aPrevLine].length();
            if (aEmpty == 0)
            {
                return emit(fold ? ' ' : '\n', aPrevLine, breakColumn, false);
            }
            if (!fold && !emit('\n', aPrevLine, breakColumn, false))
            {
                return false;
            }
            for (int e = 0; e < aEmpty; e++)
            {
                if (!emit('\n', aLine - aEmpty + e, 0, false))
                {
                    return false;
                }
            }
            return true;
        }
    }
}
