package net.cumba.corej.authoring;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Function help, read from the rule guide's function reference
 * ({@code rule-guide/reference/functions.md} in the corpus repository): one {@link Entry} per
 * {@code ### <name>} section — the signature line, the summary paragraph, the parameter table and
 * the named sub-sections (<b>Example</b>, <b>Missing and empty</b>, <b>When there is nothing to
 * answer</b>, …) — so an editor's tooltips and completion say what the guide says, and the guide
 * stays the one place function documentation is written (owner RE-Q7).
 *
 * <p>
 * The parse is deliberately plain: {@code ### name} headings open an entry; the first line of the
 * form {@code `name(…)` → **type**} is the signature; the first paragraph after it is the summary;
 * a {@code | parameter | type | required | description |} table is the parameters; a line starting
 * with {@code **Title**} opens a named sub-section. A function the guide documents only as an alias
 * inside another's section has no entry here — {@link #aliasOf} answers which section mentions it.
 * The corpus's {@code FunctionReferenceParameterTableTest} holds the tables to the engine's
 * {@code FunctionDescriptor}s, so what this reads is what the engine accepts.
 * </p>
 */
public final class FunctionHelp
{

    /** One parameter row of a section's table. */
    public record Parameter(String name, String type, boolean required, String description)
    {
    }


    /**
     * One function's help.
     *
     * @param name
     *            the function name (the section heading)
     * @param signature
     *            the signature line's code span, e.g. {@code max(name, group=, domain=, …)}
     * @param returnType
     *            the bold type after the arrow, e.g. {@code value}, {@code boolean}; empty when the
     *            section has no signature line
     * @param summary
     *            the first paragraph after the signature, as one line
     * @param parameters
     *            the parameter table's rows, in table order; empty when the section has none
     * @param sections
     *            the named sub-sections in order ({@code Example}, {@code Missing and empty}, …),
     *            each the markdown text that follows its title
     */
    public record Entry(String name, String signature, String returnType, String summary,
            List<Parameter> parameters, Map<String, String> sections)
    {

        public Entry
        {
            parameters = List.copyOf(parameters);
            sections = Collections.unmodifiableMap(new LinkedHashMap<>(sections));
        }
    }

    private static final Pattern HEADING = Pattern.compile("^### (\\S+)\\s*$");

    private static final Pattern SIGNATURE = Pattern
            .compile("^`([^`]+)`\\s*(?:→|->)\\s*\\*\\*([^*]+)\\*\\*");

    private static final Pattern SUBSECTION = Pattern.compile("^\\*\\*([^*]+)\\*\\*\\s*(.*)$");

    private static final String TABLE_HEADER = "| parameter | type | required | description |";

    private final Map<String, Entry> entries;

    private final Map<String, String> aliases;

    private FunctionHelp(Map<String, Entry> aEntries, Map<String, String> aAliases)
    {
        entries = Collections.unmodifiableMap(new LinkedHashMap<>(aEntries));
        aliases = Collections.unmodifiableMap(new LinkedHashMap<>(aAliases));
    }


    /**
     * Parse the function reference.
     *
     * @param aMarkdown
     *            the page's text
     * @return the help
     */
    public static FunctionHelp parse(String aMarkdown)
    {
        Map<String, Entry> entries = new LinkedHashMap<>();
        Map<String, String> aliases = new LinkedHashMap<>();
        String current = null;
        List<String> body = new ArrayList<>();
        for (String line : aMarkdown.split("\\R", -1))
        {
            Matcher h = HEADING.matcher(line);
            if (h.matches() || line.startsWith("## "))
            {
                if (current != null)
                {
                    entries.put(current, build(current, body));
                }
                current = h.matches() ? h.group(1) : null;
                body = new ArrayList<>();
            }
            else
            {
                body.add(line);
            }
        }
        if (current != null)
        {
            entries.put(current, build(current, body));
        }
        // a name mentioned in backticks inside another section is documented as that section's
        // alias (the roster test's rule): record the first such mention
        for (Entry e : entries.values())
        {
            Matcher m = Pattern.compile("`([a-z_][a-z0-9_]*)(?:`|\\()").matcher(render(e));
            while (m.find())
            {
                String mentioned = m.group(1);
                if (!entries.containsKey(mentioned) && !aliases.containsKey(mentioned)
                        && !mentioned.equals(e.name()))
                {
                    aliases.put(mentioned, e.name());
                }
            }
        }
        return new FunctionHelp(entries, aliases);
    }


    private static String render(Entry aEntry)
    {
        StringBuilder sb = new StringBuilder(aEntry.summary());
        for (Parameter p : aEntry.parameters())
        {
            sb.append('\n').append(p.description());
        }
        for (String s : aEntry.sections().values())
        {
            sb.append('\n').append(s);
        }
        return sb.toString();
    }


    private static Entry build(String aName, List<String> aBody)
    {
        String signature = "";
        String returnType = "";
        StringBuilder summary = new StringBuilder();
        List<Parameter> parameters = new ArrayList<>();
        Map<String, String> sections = new LinkedHashMap<>();
        String currentSection = null;
        StringBuilder sectionText = new StringBuilder();
        boolean inTable = false;
        boolean summaryDone = false;
        boolean signatureSeen = false;
        for (String line : aBody)
        {
            if (!signatureSeen)
            {
                Matcher sig = SIGNATURE.matcher(line.strip());
                if (sig.find())
                {
                    signature = sig.group(1).strip();
                    returnType = sig.group(2).strip();
                    signatureSeen = true;
                    continue;
                }
            }
            if (line.strip().equals(TABLE_HEADER))
            {
                inTable = true;
                summaryDone = true;
                continue;
            }
            if (inTable)
            {
                if (line.startsWith("|---"))
                {
                    continue;
                }
                if (line.startsWith("|"))
                {
                    parameters.add(row(line));
                    continue;
                }
                inTable = false;
            }
            Matcher sub = SUBSECTION.matcher(line.strip());
            if (sub.matches() && !line.strip().startsWith("**One positional")
                    && !line.strip().startsWith("**Two rules"))
            {
                if (currentSection != null)
                {
                    sections.put(currentSection, sectionText.toString().strip());
                }
                currentSection = sub.group(1).strip();
                sectionText = new StringBuilder(sub.group(2).strip());
                summaryDone = true;
                continue;
            }
            if (currentSection != null)
            {
                sectionText.append('\n').append(line);
            }
            else if (!summaryDone)
            {
                if (line.isBlank())
                {
                    if (summary.length() > 0)
                    {
                        summaryDone = true;
                    }
                }
                else
                {
                    if (summary.length() > 0)
                    {
                        summary.append(' ');
                    }
                    summary.append(line.strip());
                }
            }
        }
        if (currentSection != null)
        {
            sections.put(currentSection, sectionText.toString().strip());
        }
        return new Entry(aName, signature, returnType, summary.toString(), parameters, sections);
    }


    private static Parameter row(String aLine)
    {
        String body = aLine.strip();
        body = body.substring(1, body.endsWith("|") ? body.length() - 1 : body.length());
        List<String> cells = splitCells(body);
        String name = cells.isEmpty() ? "" : cells.get(0).strip().replace("`", "");
        String type = cells.size() > 1 ? cells.get(1).strip() : "";
        String required = cells.size() > 2 ? cells.get(2).strip().toLowerCase(Locale.ROOT) : "";
        String description = cells.size() > 3 ? cells.get(3).strip() : "";
        return new Parameter(name, type, required.replace("*", "").startsWith("yes"), description);
    }


    /** Split a table row on unescaped pipes ({@code \|} inside a cell is a literal pipe). */
    private static List<String> splitCells(String aBody)
    {
        List<String> out = new ArrayList<>();
        StringBuilder cell = new StringBuilder();
        for (int i = 0; i < aBody.length(); i++)
        {
            char c = aBody.charAt(i);
            if (c == '\\' && i + 1 < aBody.length() && aBody.charAt(i + 1) == '|')
            {
                cell.append('|');
                i++;
            }
            else if (c == '|')
            {
                out.add(cell.toString());
                cell.setLength(0);
            }
            else
            {
                cell.append(c);
            }
        }
        out.add(cell.toString());
        return out;
    }


    /** The entry of a function, or {@code null} when the guide has no section for it. */
    public @Nullable Entry entry(String aName)
    {
        return entries.get(aName);
    }


    /**
     * The section that documents a function as an alias ({@code var_not_exists} →
     * {@code var_exists}), or {@code null}.
     */
    public @Nullable String aliasOf(String aName)
    {
        return aliases.get(aName);
    }


    /** Every entry, in page order. */
    public Map<String, Entry> entries()
    {
        return entries;
    }
}
