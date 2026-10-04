package net.cumba.corej.core.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Pattern;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.gen.WildcardExpander.WildcardPattern;

/**
 * {@code PLAN-library-template-label-resolution} — <b>the one template leg</b> of the LIBRARY-level
 * variable read for an ADaM product: how a dataset column finds its published variable when the
 * CDISC Library publishes a <em>naming template</em> ({@code TRTxxP}, {@code AVALCATy}) or a
 * <em>stem</em> ({@code *SDT}) rather than the column's own name. Called by
 * {@link MetadataLibraryProvider} over the structure-keyed candidates of a real product and by the
 * {@code .cdt} harness's map-backed provider over a scenario's {@code #library} rows, so a scenario
 * executes the same resolution the product does (a sidecar lists {@code TRTxxP}, never
 * {@code TRT01P}).
 *
 * <h2>The tiers — first answer wins</h2>
 * <ol>
 * <li><b>literal</b> in the dataset's own structure: the column's name equals a published name,
 * ignoring letter case (register {@code CIT §1});</li>
 * <li><b>index template</b> in the own structure: the column matches exactly one published template
 * under {@link WildcardExpander#indexTemplate} — the same compiled pattern the scope gate and the
 * core-variable walks use. Two matching templates are an ambiguity, and an ambiguity is <b>no
 * answer</b> (the read stops; it does not fall through to a weaker tier);</li>
 * <li><b>ADSL literal</b>, then <b>ADSL index template</b> (D9): an ADSL variable copied into a BDS
 * or OCCDS dataset resolves to its ADSL row when the dataset's own structure lacks it;</li>
 * <li><b>stem</b>: the longest published {@code *}-stem that is a proper suffix of the column
 * ({@code XXSDT} is {@code *SDT}'s, never {@code *DT}'s; {@code *DT} ⊂ {@code *SDT}, {@code *TM} ⊂
 * {@code *STM}/{@code *ETM}, {@code *DTM} ⊂ {@code *SDTM}/{@code *EDTM}, so the longest suffix is
 * never ambiguous).</li>
 * </ol>
 *
 * <h2>What the answer carries</h2>
 * <ul>
 * <li>a <b>literal</b> hit: the published attributes, {@code name} the published spelling, plus
 * {@value #LABEL_TEMPLATE_KEY} equal to the label — unless the published name itself carries an
 * index marker (a column spelled {@code TRTXXP} hits {@code TRTxxP} literally: it names no concrete
 * variable, so there is no template to hold it to) or the label carries a {@code {…}} phrase;</li>
 * <li>a <b>template</b> hit: {@code name} is the <em>template</em> ({@code TRTxxP}, decision D2 — a
 * rule can still tell a template-resolved column by its lowercase marker), {@code label} is the
 * published label with every index marker substituted as a whole word ({@code Planned Treatment
 * for Period xx} → {@code … Period 01}, {@code Subperiod w} → {@code Subperiod 1}), and
 * {@value #LABEL_TEMPLATE_KEY} is the same text in the matcher's grammar: for a name with a
 * {@code y} or {@code zz} marker a trailing {@code " ..."} is appended, the IG's descriptive tail
 * (D8 expressed through D11: {@code ...} is any text). A label that still carries a {@code {…}}
 * phrase after substitution is served as {@value #LABEL_TEMPLATE_KEY} only, never as {@code label}
 * — the accessor never hands back a template as a value;</li>
 * <li>a <b>stem</b> hit: {@code name} is the stem ({@code *SDT}) and {@value #LABEL_TEMPLATE_KEY}
 * the stem's published label verbatim ({@code {Start Date}}; ADaMIG 1.0: {@code Start Date of
 * ...}). Nothing else: a stem is a naming convention whose one published obligation the rules read
 * is the label phrase, and serving its type or core would make every type rule judge every sponsor
 * {@code *DT} column — a movement no rule's authority asks for. Rules that read
 * {@code var_label("LIBRARY")} therefore never see a stem (their {@code not empty} guard holds),
 * and the stem rules select their stem by {@code var_name("LIBRARY") == "*SDT"}.</li>
 * </ul>
 */
public final class LibraryTemplates
{

    /** The attribute key of the resolved label template (the {@code label_template} accessor). */
    public static final String LABEL_TEMPLATE_KEY = "label_template";

    /** The IG's "any text" placeholder, and the tail appended for a {@code y}/{@code zz} name. */
    public static final String ANY_TEXT = "...";

    private static final String NAME = "name";

    private static final String LABEL = "label";

    private static final String STEM_PREFIX = "*";

    private LibraryTemplates()
    {
    }

    /**
     * One published variable: its name as the Library spells it ({@code TRTxxP}, {@code *SDT},
     * {@code TRTSDT}) and its attributes ({@code label}, {@code simpleDatatype}, {@code core}, …).
     *
     * @param name
     *            the published name
     * @param attributes
     *            the published attributes; a {@code name} entry inside is ignored in favour of
     *            {@code name}
     */
    public record Entry(String name, Map<String, String> attributes)
    {

        public Entry
        {
            Objects.requireNonNull(name, "name");
            attributes = Collections.unmodifiableMap(new LinkedHashMap<>(attributes));
        }
    }


    /**
     * The candidates one read resolves against: the dataset's own structure's entries, the ADSL
     * entries (empty when the dataset is ADSL itself, or no ADSL structure is declared) and the
     * stems (every {@code *}-rooted entry the declared products publish, any structure).
     */
    public record Candidates(List<Entry> own, List<Entry> adsl, List<Entry> stems)
    {

        /** No candidates at all — every read answers the empty map. */
        public static final Candidates NONE = new Candidates(List.of(), List.of(), List.of());

        public Candidates
        {
            own = List.copyOf(own);
            adsl = List.copyOf(adsl);
            stems = List.copyOf(stems);
        }
    }

    /**
     * Resolves {@code aColumn} against {@code aCandidates} through the tiers of the class javadoc.
     *
     * @param aColumn
     *            the dataset's column name, in the dataset's own spelling
     * @param aCandidates
     *            the candidates
     * @return the resolved attributes (see the class javadoc), or an empty map when no tier answers
     *         or the index-template tier is ambiguous; never {@code null}
     */
    public static Map<String, String> resolve(String aColumn, Candidates aCandidates)
    {
        if (aColumn == null || aColumn.isEmpty())
        {
            return Map.of();
        }
        Map<String, String> hit = literal(aColumn, aCandidates.own());
        if (!hit.isEmpty())
        {
            return hit;
        }
        TemplateHit template = template(aColumn, aCandidates.own());
        if (template.ambiguous())
        {
            return Map.of();
        }
        if (!template.attributes().isEmpty())
        {
            return template.attributes();
        }
        hit = literal(aColumn, aCandidates.adsl());
        if (!hit.isEmpty())
        {
            return hit;
        }
        template = template(aColumn, aCandidates.adsl());
        if (template.ambiguous())
        {
            return Map.of();
        }
        if (!template.attributes().isEmpty())
        {
            return template.attributes();
        }
        return stem(aColumn, aCandidates.stems());
    }


    /**
     * {@code matches_label_template(label, template)} — D6 (b): whether a data label conforms to a
     * resolved label template, the IG's own rule read as a grammar (ADaMIG §3, the same in 1.1 and
     * 1.3):
     * <ul>
     * <li><b>{@code {phrase}}</b> — <i>"labels containing a word or phrase in brackets should be
     * replaced by the producer with appropriate text that contains the bracketed word or phrase
     * somewhere in the text"</i>: every brace phrase must occur in the label; the label is
     * otherwise free. A template with a phrase is judged by this clause alone.</li>
     * <li><b>{@code ...}</b> — any text, including none (D11: the IG's ellipsis, and the
     * descriptive tail a {@code y}/{@code zz} name is allowed, D8); the whitespace beside it is
     * optional, so {@code Analysis Category 1 ...} accepts {@code Analysis Category 1}.</li>
     * <li><b>fixed text</b> — exactly, letter case included, as the exact-label rules compare.</li>
     * <li><b>{@code Imput.} ≡ {@code Imputation}</b> on both sides (D7): the one abbreviation the
     * IG itself publishes (24 labels; metadata issue {@code #97}). No other.</li>
     * </ul>
     * A missing operand is the caller's business: {@code Primitives.matchesLabelTemplate} answers
     * {@code false} for a genuine missing on either side, as {@code contains} does (D13 / R2-20).
     *
     * @param aLabel
     *            the data label
     * @param aTemplate
     *            the resolved template, as {@code label_template("LIBRARY")} serves it
     * @return whether the label conforms
     */
    public static boolean matches(String aLabel, String aTemplate)
    {
        String label = normaliseImputation(aLabel);
        String template = normaliseImputation(aTemplate);
        List<String> phrases = bracePhrases(template);
        if (!phrases.isEmpty())
        {
            return phrases.stream().allMatch(label::contains);
        }
        return fixedTextRegex(template).matcher(label).matches();
    }

    private static final Pattern BRACE_PHRASE = Pattern.compile("\\{([^}]*)\\}");

    private static final String IMPUT_ABBREVIATION = "Imput.";

    private static final String IMPUTATION = "Imputation";

    static String normaliseImputation(String aText)
    {
        return aText.replace(IMPUT_ABBREVIATION, IMPUTATION);
    }


    static List<String> bracePhrases(String aTemplate)
    {
        List<String> out = new ArrayList<>();
        java.util.regex.Matcher m = BRACE_PHRASE.matcher(aTemplate);
        while (m.find())
        {
            String phrase = m.group(1).strip();
            if (!phrase.isEmpty())
            {
                out.add(phrase);
            }
        }
        return out;
    }


    /**
     * The template's fixed text as an anchored regex: each {@code ...} becomes {@code .*} with the
     * whitespace beside it optional, everything else is quoted.
     */
    static Pattern fixedTextRegex(String aTemplate)
    {
        String[] fixed = aTemplate.split(Pattern.quote(ANY_TEXT), -1);
        StringBuilder regex = new StringBuilder("^");
        for (int i = 0; i < fixed.length; i++)
        {
            String part = fixed[i];
            if (i > 0)
            {
                regex.append("\\s*.*\\s*");
                part = part.stripLeading();
            }
            if (i < fixed.length - 1)
            {
                part = part.stripTrailing();
            }
            if (!part.isEmpty())
            {
                regex.append(Pattern.quote(part));
            }
        }
        return Pattern.compile(regex.append('$').toString(), Pattern.DOTALL);
    }


    /**
     * The index markers of a published name ({@code y}, {@code zz} …), or an empty list for a
     * literal or a stem.
     */
    static List<String> markersOf(String aPublishedName)
    {
        WildcardPattern pattern = WildcardExpander.indexTemplate(aPublishedName);
        return pattern == null ? List.of() : pattern.groupNames();
    }


    /**
     * Whether the IG allows descriptive text after the label: the name carries {@code y} or
     * {@code zz}.
     */
    static boolean allowsDescriptiveTail(String aPublishedName)
    {
        List<String> markers = markersOf(aPublishedName);
        return markers.contains("y") || markers.contains("zz");
    }


    private static Map<String, String> literal(String aColumn, List<Entry> aEntries)
    {
        for (Entry entry : aEntries)
        {
            if (entry.name().equalsIgnoreCase(aColumn))
            {
                Map<String, String> out = withName(entry);
                String label = entry.attributes().get(LABEL);
                if (label != null && !hasPhrase(label) && markersOf(entry.name()).isEmpty())
                {
                    out.put(LABEL_TEMPLATE_KEY, label);
                }
                return Collections.unmodifiableMap(out);
            }
        }
        return Map.of();
    }

    /** The outcome of the index-template tier: an answer, nothing, or an ambiguity. */
    private record TemplateHit(Map<String, String> attributes, boolean ambiguous)
    {

        static final TemplateHit NONE = new TemplateHit(Map.of(), false);

        static final TemplateHit AMBIGUOUS = new TemplateHit(Map.of(), true);
    }

    private static TemplateHit template(String aColumn, List<Entry> aEntries)
    {
        Entry match = null;
        Map<String, String> captured = null;
        // Two rows of one published name are one template (adam-occds-1-0 publishes DECDORGw
        // twice); two DIFFERENT templates matching the column are an ambiguity.
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (Entry entry : aEntries)
        {
            byName.putIfAbsent(entry.name(), entry);
        }
        for (Entry entry : byName.values())
        {
            WildcardPattern pattern = WildcardExpander.indexTemplate(entry.name());
            if (pattern == null)
            {
                continue;
            }
            Map<String, String> markers = pattern.capture(aColumn);
            if (markers == null)
            {
                continue;
            }
            if (match != null)
            {
                return TemplateHit.AMBIGUOUS;
            }
            match = entry;
            captured = markers;
        }
        if (match == null || captured == null)
        {
            return TemplateHit.NONE;
        }
        Map<String, String> out = withName(match);
        String label = match.attributes().get(LABEL);
        if (label != null)
        {
            String substituted = substituteMarkers(label, captured);
            if (hasPhrase(substituted))
            {
                out.remove(LABEL);
            }
            else
            {
                out.put(LABEL, substituted);
            }
            String template = substituted;
            if (allowsDescriptiveTail(match.name()) && !substituted.endsWith(ANY_TEXT))
            {
                template = substituted + " " + ANY_TEXT;
            }
            out.put(LABEL_TEMPLATE_KEY, template);
        }
        return new TemplateHit(Collections.unmodifiableMap(out), false);
    }


    private static Map<String, String> stem(String aColumn, List<Entry> aStems)
    {
        String upper = aColumn.toUpperCase(Locale.ROOT);
        Entry best = null;
        for (Entry entry : aStems)
        {
            String name = entry.name();
            if (!name.startsWith(STEM_PREFIX) || name.length() < 2)
            {
                continue;
            }
            String suffix = name.substring(1).toUpperCase(Locale.ROOT);
            // A proper suffix: the root (`*`) binds at least one character.
            if (upper.length() > suffix.length() && upper.endsWith(suffix)
                    && (best == null || suffix.length() > best.name().length() - 1))
            {
                best = entry;
            }
        }
        if (best == null)
        {
            return Map.of();
        }
        Map<String, String> out = new LinkedHashMap<>();
        out.put(NAME, best.name());
        String label = best.attributes().get(LABEL);
        if (label != null)
        {
            out.put(LABEL_TEMPLATE_KEY, label);
        }
        return Collections.unmodifiableMap(out);
    }


    /**
     * Every index marker replaced, as a whole word, by the digits the column bound to it:
     * {@code Planned Treatment for Period xx} + {@code {xx=01}} → {@code Planned Treatment for
     * Period 01}. A marker inside a word ({@code Fixxed}) is left alone.
     */
    static String substituteMarkers(String aLabel, Map<String, String> aCaptured)
    {
        String out = aLabel;
        for (Map.Entry<String, String> e : aCaptured.entrySet())
        {
            out = Pattern.compile("(?<![A-Za-z])" + Pattern.quote(e.getKey()) + "(?![A-Za-z])")
                    .matcher(out)
                    .replaceAll(java.util.regex.Matcher.quoteReplacement(e.getValue()));
        }
        return out;
    }


    private static boolean hasPhrase(String aLabel)
    {
        return aLabel.indexOf('{') >= 0;
    }


    private static Map<String, String> withName(Entry aEntry)
    {
        Map<String, String> out = new LinkedHashMap<>(aEntry.attributes());
        out.put(NAME, aEntry.name());
        return out;
    }


    /**
     * The entries of a map-backed row set: {@code name} → attributes, the name read from the map.
     */
    public static List<Entry> entriesOf(Map<String, Map<String, String>> aRowsByKey)
    {
        List<Entry> out = new ArrayList<>(aRowsByKey.size());
        for (Map.Entry<String, Map<String, String>> e : aRowsByKey.entrySet())
        {
            Map<String, String> attributes = e.getValue();
            String name = attributes.get(NAME);
            out.add(new Entry(name != null && !name.isEmpty() ? name : e.getKey(), attributes));
        }
        return out;
    }


    /** The {@code *}-rooted entries of {@code aEntries}, first occurrence of each name kept. */
    public static List<Entry> stemsOf(List<Entry> aEntries)
    {
        Map<String, Entry> byName = new LinkedHashMap<>();
        for (Entry entry : aEntries)
        {
            if (entry.name().startsWith(STEM_PREFIX))
            {
                byName.putIfAbsent(entry.name().toUpperCase(Locale.ROOT), entry);
            }
        }
        return List.copyOf(byName.values());
    }
}
