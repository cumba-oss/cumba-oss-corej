package net.cumba.corej.core.metadata;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.cumba.corej.core.gen.WildcardExpander;
import net.cumba.corej.core.gen.WildcardExpander.WildcardPattern;
import org.jspecify.annotations.Nullable;

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
 * <li>⚠ <b>one published name with two different labels is an ambiguity too</b> (T2 review H1):
 * {@code adam-occds-1-0} / {@code -1-1} and the TIG publish {@code DECDORGw} twice in one structure
 * — {@code PT in Original Dictionary w} (MedDRA) and {@code Standardized Med Name in Orig
 * Dict w} (WHO Drug) — so which label a {@code DECDORG1} column must carry depends on a dictionary
 * the name does not tell. Rows of one name merge only when their labels agree (compared with
 * whitespace collapsed, so a cosmetic double space is no disagreement); otherwise the tier answers
 * nothing. The same holds for a literal name published twice;</li>
 * <li><b>ADSL literal</b>, then <b>ADSL index template</b> (D9): an ADSL variable copied into a BDS
 * or OCCDS dataset resolves to its ADSL row when the dataset's own structure lacks it;</li>
 * <li><b>stem</b>: the longest published {@code *}-stem that is a proper suffix of the column
 * ({@code XXSDT} is {@code *SDT}'s, never {@code *DT}'s; {@code *DT} ⊂ {@code *SDT}, {@code *TM} ⊂
 * {@code *STM}/{@code *ETM}, {@code *DTM} ⊂ {@code *SDTM}/{@code *EDTM}, so the longest suffix is
 * never ambiguous).</li>
 * </ol>
 *
 * <p>
 * <b>The governing chain is a list of tiers</b>, most specific first (a subclass structure, then
 * its base — {@code MetadataLibraryProvider.governingTiers}). Literals are looked up tier by tier
 * before any template, as one flat list was; the template tier, too, is judged tier by tier: the
 * first tier with a matching template answers, so a subclass that re-publishes a name with its own
 * label ({@code adam-adae-1-0}'s {@code AOCCzzFL}) governs its base's row instead of colliding with
 * it. Ambiguity is a property of ONE tier.
 * </p>
 *
 * <p>
 * <b>Cost.</b> A {@link Candidates} compiles each template's pattern once and memoises the
 * resolution of each column it is asked about, so a run that asks the same (structure set, column)
 * for every rule pays for the tier walk once.
 * </p>
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
     * The candidates one read resolves against: the dataset's own governing chain (its tiers, most
     * specific first), the ADSL chain (empty when the dataset is ADSL itself, or no ADSL structure
     * is declared) and the stems (every {@code *}-rooted entry the declared products publish, any
     * structure).
     *
     * <p>
     * Built once per (structure set, subclass set) by the provider and then shared by every read:
     * each template's {@link WildcardPattern} is compiled here, once, and every column's resolution
     * is memoised (T2 review M2). The memo is unbounded on purpose — its keys are the column names
     * a study actually has, a few hundred at most. Thread-safe: the tiers are immutable and the
     * memo is a {@link ConcurrentHashMap}.
     * </p>
     */
    public static final class Candidates
    {

        /** No candidates at all — every read answers the empty map. */
        public static final Candidates NONE = new Candidates(List.of(), List.of(), List.of());

        private final List<Tier> own;

        private final List<Tier> adsl;

        private final List<Entry> stems;

        private final Map<String, Map<String, String>> resolved = new ConcurrentHashMap<>();

        /**
         * One tier each — the shape of a provider with no subclass axis (the {@code .cdt} harness).
         *
         * @param aOwn
         *            the dataset's own structure's entries
         * @param aAdsl
         *            the ADSL entries
         * @param aStems
         *            the stems
         */
        public Candidates(List<Entry> aOwn, List<Entry> aAdsl, List<Entry> aStems)
        {
            this(new Tier[]
            {
                    new Tier(aOwn)
            }, new Tier[]
            {
                    new Tier(aAdsl)
            }, aStems);
        }


        private Candidates(Tier[] aOwn, Tier[] aAdsl, List<Entry> aStems)
        {
            own = List.of(aOwn);
            adsl = List.of(aAdsl);
            stems = List.copyOf(aStems);
        }


        /**
         * The candidates of a governing chain: one entry list per tier, most specific first (a
         * subclass structure, then its base).
         *
         * @param aOwnTiers
         *            the dataset's own chain
         * @param aAdslTiers
         *            the ADSL chain
         * @param aStems
         *            the stems
         * @return the candidates
         */
        public static Candidates ofTiers(List<List<Entry>> aOwnTiers, List<List<Entry>> aAdslTiers,
                List<Entry> aStems)
        {
            return new Candidates(tiersOf(aOwnTiers), tiersOf(aAdslTiers), aStems);
        }


        private static Tier[] tiersOf(List<List<Entry>> aTiers)
        {
            return aTiers.stream().map(Tier::new).toArray(Tier[]::new);
        }
    }


    /**
     * One tier of a governing chain: its entries in publication order, and its index templates
     * grouped by published name with their patterns compiled once. A name published twice with
     * labels that disagree is kept, marked {@code conflicting}: a column it matches has no answer
     * (T2 review H1).
     */
    private static final class Tier
    {

        private final List<Entry> entries;

        private final List<Template> templates;

        Tier(List<Entry> aEntries)
        {
            entries = List.copyOf(aEntries);
            Map<String, Template> byName = new LinkedHashMap<>();
            for (Entry entry : entries)
            {
                Template seen = byName.get(entry.name());
                if (seen == null)
                {
                    WildcardPattern pattern = WildcardExpander.indexTemplate(entry.name());
                    if (pattern != null)
                    {
                        byName.put(entry.name(), new Template(entry, pattern, false));
                    }
                }
                else if (!seen.conflicting() && !sameLabel(seen.entry(), entry))
                {
                    byName.put(entry.name(), new Template(seen.entry(), seen.pattern(), true));
                }
            }
            templates = List.copyOf(byName.values());
        }
    }


    /** One published index template of a tier, its pattern compiled. */
    private record Template(Entry entry, WildcardPattern pattern, boolean conflicting)
    {
    }

    /**
     * Resolves {@code aColumn} against {@code aCandidates} through the tiers of the class javadoc,
     * memoised per {@link Candidates} and column.
     *
     * @param aColumn
     *            the dataset's column name, in the dataset's own spelling
     * @param aCandidates
     *            the candidates
     * @return the resolved attributes (see the class javadoc), or an empty map when no tier answers
     *         or a tier is ambiguous; never {@code null}
     */
    public static Map<String, String> resolve(@Nullable String aColumn, Candidates aCandidates)
    {
        if (aColumn == null || aColumn.isEmpty())
        {
            return Map.of();
        }
        return aCandidates.resolved.computeIfAbsent(aColumn, c -> resolveUncached(c, aCandidates));
    }


    private static Map<String, String> resolveUncached(String aColumn, Candidates aCandidates)
    {
        Hit hit = firstLiteral(aColumn, aCandidates.own);
        if (hit == Hit.NONE)
        {
            hit = firstTemplate(aColumn, aCandidates.own);
        }
        if (hit == Hit.NONE)
        {
            hit = firstLiteral(aColumn, aCandidates.adsl);
        }
        if (hit == Hit.NONE)
        {
            hit = firstTemplate(aColumn, aCandidates.adsl);
        }
        if (hit == Hit.NONE)
        {
            return stem(aColumn, aCandidates.stems);
        }
        // an ambiguity is no answer: the read stops, it does not fall through
        return hit.attributes;
    }


    /** The literal tier over a chain: the first tier publishing the name answers. */
    private static Hit firstLiteral(String aColumn, List<Tier> aChain)
    {
        for (Tier tier : aChain)
        {
            Hit hit = literal(aColumn, tier);
            if (hit != Hit.NONE)
            {
                return hit;
            }
        }
        return Hit.NONE;
    }


    /** The index-template tier over a chain: the first tier with a matching template answers. */
    private static Hit firstTemplate(String aColumn, List<Tier> aChain)
    {
        for (Tier tier : aChain)
        {
            Hit hit = template(aColumn, tier);
            if (hit != Hit.NONE)
            {
                return hit;
            }
        }
        return Hit.NONE;
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
     * optional, so {@code Analysis Category 1 ...} accepts {@code Analysis Category 1}. ⚠ <b>Where
     * the fixed text beside it ends (or starts) with a letter or digit, the free text is either
     * empty or separated from it by a non-alphanumeric character</b> (T2 review M1/L5): text glued
     * to the fixed text is not free text, it changes the word or the index the fixed text ends in —
     * {@code Analysis Value Category 12} is {@code AVALCAT12}'s label, not {@code AVALCAT1}'s with
     * a tail. The IG's own wording supports exactly this reading: descriptive text is allowed <i>at
     * the end</i> of the label — after it, not inside its last word. Any non-alphanumeric character
     * is a boundary ({@code Analysis Flag 01, Primary}), because the IG prescribes no separator; an
     * inner {@code ...} between two alphanumerics needs one on each side, and the empty free text
     * there collapses them into one ({@code Pooled Site Group Code}).</li>
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
        return matcher(aTemplate).test(aLabel);
    }


    /**
     * {@link #matches} with the template compiled once: the predicate a caller applies to many
     * labels of one template (a column's rows).
     *
     * @param aTemplate
     *            the resolved template
     * @return whether a label conforms to it
     */
    public static Predicate<String> matcher(String aTemplate)
    {
        String template = normaliseImputation(aTemplate);
        List<String> phrases = bracePhrases(template);
        if (!phrases.isEmpty())
        {
            return label ->
            {
                String normalised = normaliseImputation(label);
                return phrases.stream().allMatch(normalised::contains);
            };
        }
        Pattern regex = fixedTextRegex(template);
        return label -> regex.matcher(normaliseImputation(label)).matches();
    }

    /**
     * A one-entry memo of {@link #matcher}: the last template and its compiled predicate. Made for
     * a scan over one column, whose template is the same on every row (T2 review M2: the matcher
     * compiled its regex once per ROW). Not thread-safe — one per scan.
     */
    public static final class TemplateMatcher
    {

        private @Nullable String lastTemplate;

        private Predicate<String> lastMatcher = _ -> false;

        /**
         * As {@link LibraryTemplates#matches}.
         *
         * @param aLabel
         *            the data label
         * @param aTemplate
         *            the resolved template
         * @return whether the label conforms
         */
        public boolean matches(String aLabel, String aTemplate)
        {
            if (!aTemplate.equals(lastTemplate))
            {
                lastMatcher = matcher(aTemplate);
                lastTemplate = aTemplate;
            }
            return lastMatcher.test(aLabel);
        }
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
        Matcher m = BRACE_PHRASE.matcher(aTemplate);
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
     * The template's fixed text as an anchored regex: each {@code ...} becomes free text (see
     * {@link #freeText}) with the whitespace beside it optional, everything else is quoted.
     */
    static Pattern fixedTextRegex(String aTemplate)
    {
        String[] fixed = aTemplate.split(Pattern.quote(ANY_TEXT), -1);
        StringBuilder regex = new StringBuilder("^");
        String previous = "";
        for (int i = 0; i < fixed.length; i++)
        {
            String part = fixed[i];
            if (i > 0)
            {
                part = part.stripLeading();
            }
            if (i < fixed.length - 1)
            {
                part = part.stripTrailing();
            }
            if (i > 0)
            {
                regex.append(freeText(previous, part));
            }
            if (!part.isEmpty())
            {
                regex.append(Pattern.quote(part));
            }
            previous = part;
        }
        return Pattern.compile(regex.append('$').toString(), Pattern.DOTALL);
    }


    /**
     * The regex of one {@code ...} between the fixed text {@code aBefore} and {@code aAfter} (each
     * stripped of the whitespace beside the ellipsis): any text — but where a side ends (or starts)
     * with a letter or digit, the free text is empty or begins (ends) with a non-alphanumeric
     * character, and between two such sides it is a non-empty run bounded so on both ends.
     */
    private static String freeText(String aBefore, String aAfter)
    {
        boolean left = !aBefore.isEmpty() && isAsciiAlnum(aBefore.charAt(aBefore.length() - 1));
        boolean right = !aAfter.isEmpty() && isAsciiAlnum(aAfter.charAt(0));
        if (left && right)
        {
            return "[^\\p{Alnum}](?:.*[^\\p{Alnum}])?";
        }
        if (left)
        {
            return "(?:[^\\p{Alnum}].*)?";
        }
        if (right)
        {
            return "(?:.*[^\\p{Alnum}])?";
        }
        return ".*";
    }


    /** {@code \p{Alnum}}'s character set: ASCII letters and digits. */
    private static boolean isAsciiAlnum(char aChar)
    {
        return aChar < 128 && Character.isLetterOrDigit(aChar);
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

    /**
     * The outcome of one tier: an answer, nothing ({@link #NONE}), or an ambiguity
     * ({@link #AMBIGUOUS}, which carries the empty map — no answer, and the read stops).
     */
    private static final class Hit
    {

        static final Hit NONE = new Hit(Map.of());

        static final Hit AMBIGUOUS = new Hit(Map.of());

        private final Map<String, String> attributes;

        Hit(Map<String, String> aAttributes)
        {
            attributes = aAttributes;
        }
    }

    private static Hit literal(String aColumn, Tier aTier)
    {
        Entry first = null;
        for (Entry entry : aTier.entries)
        {
            if (entry.name().equalsIgnoreCase(aColumn))
            {
                if (first == null)
                {
                    first = entry;
                }
                else if (!sameLabel(first, entry))
                {
                    return Hit.AMBIGUOUS; // T2 review H1: one name, two labels
                }
            }
        }
        if (first == null)
        {
            return Hit.NONE;
        }
        Map<String, String> out = withName(first);
        String label = first.attributes().get(LABEL);
        if (label != null && !hasPhrase(label) && markersOf(first.name()).isEmpty())
        {
            out.put(LABEL_TEMPLATE_KEY, label);
        }
        return new Hit(Collections.unmodifiableMap(out));
    }


    private static Hit template(String aColumn, Tier aTier)
    {
        Template match = null;
        Map<String, String> captured = null;
        for (Template candidate : aTier.templates)
        {
            Map<String, String> markers = candidate.pattern().capture(aColumn);
            if (markers == null)
            {
                continue;
            }
            if (match != null)
            {
                return Hit.AMBIGUOUS; // two different templates match the column
            }
            match = candidate;
            captured = markers;
        }
        if (match == null || captured == null)
        {
            return Hit.NONE;
        }
        if (match.conflicting())
        {
            return Hit.AMBIGUOUS; // T2 review H1: one template name, two labels (DECDORGw)
        }
        Map<String, String> out = withName(match.entry());
        String label = match.entry().attributes().get(LABEL);
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
            if (allowsDescriptiveTail(match.entry().name()) && !substituted.endsWith(ANY_TEXT))
            {
                template = substituted + " " + ANY_TEXT;
            }
            out.put(LABEL_TEMPLATE_KEY, template);
        }
        return new Hit(Collections.unmodifiableMap(out));
    }


    /**
     * Whether two rows of one name agree on their label, compared with whitespace collapsed (a run
     * of whitespace is one space, the ends stripped): a cosmetic double space is no disagreement.
     * Our store holds no such pair (measured 2026-10-04: no ADaM or TIG label carries a double or
     * an edge space — the seeder normalises them), so the collapse only keeps a future print defect
     * from turning one template into no answer.
     */
    private static boolean sameLabel(Entry aFirst, Entry aSecond)
    {
        return Objects.equals(collapseWhitespace(aFirst.attributes().get(LABEL)),
                collapseWhitespace(aSecond.attributes().get(LABEL)));
    }


    private static @Nullable String collapseWhitespace(@Nullable String aLabel)
    {
        return aLabel == null ? null : WHITESPACE_RUN.matcher(aLabel.strip()).replaceAll(" ");
    }

    private static final Pattern WHITESPACE_RUN = Pattern.compile("\\s+");

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
            Pattern marker = MARKER_WORDS.get(e.getKey());
            if (marker == null)
            {
                marker = markerWord(e.getKey());
            }
            out = marker.matcher(out).replaceAll(Matcher.quoteReplacement(e.getValue()));
        }
        return out;
    }


    private static Pattern markerWord(String aMarker)
    {
        return Pattern.compile("(?<![A-Za-z])" + Pattern.quote(aMarker) + "(?![A-Za-z])");
    }

    /**
     * The whole-word pattern of each index marker {@link WildcardExpander} knows, compiled once (T2
     * review M2); a marker outside this set is compiled on the spot.
     */
    private static final Map<String, Pattern> MARKER_WORDS = Map.of("xx", markerWord("xx"), "zz",
            markerWord("zz"), "y", markerWord("y"), "w", markerWord("w"));

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
